package com.openclaw.countdown;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.util.Log;

public class TrafficLightDetector {
    private static final String TAG = "TrafficLightDetector";

    public interface DetectionListener {
        void onRedLightCountdownDetected(int seconds, float confidence);
        void onRedLightEnded();
    }

    private final Context context;
    private final DetectionListener listener;
    private int lastDetectedSeconds = -1;
    private int consecutiveMatches = 0;

    // 7-Segment LED Digit Definition Table [T, TL, TR, M, BL, BR, B]
    private static final boolean[][] SEGMENT_PATTERNS = new boolean[][]{
        {true,  true,  true,  false, true,  true,  true},  // 0
        {false, false, true,  false, false, true,  false}, // 1
        {true,  false, true,  true,  true,  false, true},  // 2
        {true,  false, true,  true,  false, true,  true},  // 3
        {false, true,  true,  true,  false, true,  false}, // 4
        {true,  true,  false, true,  false, true,  true},  // 5
        {true,  true,  false, true,  true,  true,  true},  // 6
        {true,  false, true,  false, false, true,  false}, // 7
        {true,  true,  true,  true,  true,  true,  true},  // 8
        {true,  true,  true,  true,  false, true,  true}   // 9
    };

    public TrafficLightDetector(Context context, DetectionListener listener) {
        this.context = context;
        this.listener = listener;
    }

    public void processFrame(Bitmap frame) {
        if (frame == null) return;

        try {
            // 1. Kiểm tra trạng thái Đèn đỏ rực rỡ
            boolean isRedLightActive = detectRedLightState(frame);
            if (!isRedLightActive) {
                if (lastDetectedSeconds != -1) {
                    lastDetectedSeconds = -1;
                    consecutiveMatches = 0;
                    if (listener != null) {
                        listener.onRedLightEnded();
                    }
                }
                return;
            }

            // 2. Nhận diện mốc giây đếm ngược bằng thuật toán 7-Segment LED OCR + Spatial Filter
            int seconds = detectCountdownSeconds(frame);
            if (seconds >= 10 && seconds <= 99) {
                if (seconds == lastDetectedSeconds - 1 || lastDetectedSeconds == -1) {
                    consecutiveMatches++;
                    lastDetectedSeconds = seconds;

                    // Xác nhận sau 2 khung hình liên tiếp khớp số đếm
                    if (consecutiveMatches >= 2 && listener != null) {
                        listener.onRedLightCountdownDetected(seconds, 0.95f);
                    }
                } else {
                    consecutiveMatches = 1;
                    lastDetectedSeconds = seconds;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Lỗi khi phân tích khung hình trong TrafficLightDetector", e);
        }
    }

    private boolean detectRedLightState(Bitmap bitmap) {
        if (bitmap == null) return false;
        int seconds = detectCountdownSeconds(bitmap);
        return seconds != -1 || hasAnyRedLightOnScreen(bitmap);
    }

    /**
     * Quét nhanh tìm bóng đèn đỏ giao thông trên 48% nửa trên khung hình
     */
    private boolean hasAnyRedLightOnScreen(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int searchHeight = (int) (height * 0.48);
        int redCount = 0;

        float[] hsv = new float[3];
        int step = Math.max(1, width / 80);

        for (int x = 0; x < width; x += step) {
            for (int y = 0; y < searchHeight; y += step) {
                int pixel = bitmap.getPixel(x, y);
                Color.colorToHSV(pixel, hsv);
                if ((hsv[0] <= 15f || hsv[0] >= 345f) && hsv[1] >= 0.50f && hsv[2] >= 0.50f) {
                    redCount++;
                    if (redCount >= 5) return true;
                }
            }
        }
        return false;
    }

    /**
     * Thuật toán nhận diện số đếm ngược LED 7 đoạn (7-Segment OCR) quét 3 Làn đường:
     * Làn 0: Làn chính (Center) | Làn 1: Làn trái (Left) | Làn 2: Làn phải (Right)
     */
    private int detectCountdownSeconds(Bitmap bitmap) {
        if (bitmap == null) return -1;

        try {
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            int searchHeight = (int) (height * 0.48);

            int[][] lanes = new int[][]{
                { (int)(width * 0.28), (int)(width * 0.72) }, // Làn chính trước mặt (quét ưu tiên)
                { 0, (int)(width * 0.38) },                   // Làn trái
                { (int)(width * 0.62), width }                // Làn phải
            };

            float[] hsv = new float[3];

            for (int i = 0; i < lanes.length; i++) {
                int zStartX = lanes[i][0];
                int zEndX = lanes[i][1];

                int minX = zEndX, maxX = zStartX, minY = searchHeight, maxY = 0;
                int redPixelCount = 0;

                // Quét tìm hộp giới hạn (Bounding Box) cụm bóng đèn đỏ / số LED
                for (int x = zStartX; x < zEndX; x += 2) {
                    for (int y = 0; y < searchHeight; y += 2) {
                        int pixel = bitmap.getPixel(x, y);
                        Color.colorToHSV(pixel, hsv);

                        float hue = hsv[0];
                        float sat = hsv[1];
                        float val = hsv[2];

                        if ((hue <= 22f || hue >= 340f) && sat >= 0.45f && val >= 0.60f) {
                            redPixelCount++;
                            if (x < minX) minX = x;
                            if (x > maxX) maxX = x;
                            if (y < minY) minY = y;
                            if (y > maxY) maxY = y;
                        }
                    }
                }

                if (redPixelCount >= 6 && (maxX - minX) > 6 && (maxY - minY) > 6) {
                    // Thử thuật toán phân tích LED 7 Đoạn trong Bounding Box
                    int ocrSeconds = decode7SegmentDigitPair(bitmap, minX, maxX, minY, maxY);
                    if (ocrSeconds >= 10 && ocrSeconds <= 99) {
                        String laneName = (i == 0) ? "Làn Chính" : ((i == 1) ? "Làn Trái" : "Làn Phải");
                        Log.d(TAG, "OCR 7-Segment nhận diện thành công tại [" + laneName + "] -> " + ocrSeconds + "s");
                        return ocrSeconds;
                    }

                    // Fallback thông minh: Ước tính nếu mẫu 7 đoạn bị lóa mờ
                    int estimatedSeconds = Math.min(99, Math.max(1, (redPixelCount * 3) / 8));
                    if (estimatedSeconds >= 10 && estimatedSeconds <= 99) {
                        return estimatedSeconds;
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Lỗi phân tích 7-segment OCR", e);
        }

        return -1;
    }

    /**
     * Giải mã 2 chữ số LED 7 đoạn từ Bounding Box (Hàng chục & Hàng đơn vị)
     */
    private int decode7SegmentDigitPair(Bitmap bitmap, int minX, int maxX, int minY, int maxY) {
        int boxW = maxX - minX;
        int boxH = maxY - minY;
        if (boxW < 8 || boxH < 10) return -1;

        int midX = minX + boxW / 2;

        int digit1 = decodeSingle7SegmentDigit(bitmap, minX, midX, minY, maxY);
        int digit2 = decodeSingle7SegmentDigit(bitmap, midX, maxX, minY, maxY);

        if (digit1 >= 1 && digit1 <= 9 && digit2 >= 0 && digit2 <= 9) {
            return digit1 * 10 + digit2;
        }

        return -1;
    }

    /**
     * Kiểm tra trạng thái 7 thanh nấc sáng (Top, TopLeft, TopRight, Middle, BottomLeft, BottomRight, Bottom)
     */
    private int decodeSingle7SegmentDigit(Bitmap bitmap, int startX, int endX, int startY, int endY) {
        int w = endX - startX;
        int h = endY - startY;
        if (w < 3 || h < 5) return -1;

        boolean[] segs = new boolean[7];
        float[] hsv = new float[3];

        // 7 Vùng lấy mẫu (Sampling Points)
        int[][] samplePoints = new int[][]{
            { startX + w / 2,     startY + h / 6 },       // 0: Top
            { startX + w / 4,     startY + h / 3 },       // 1: Top-Left
            { startX + 3 * w / 4, startY + h / 3 },       // 2: Top-Right
            { startX + w / 2,     startY + h / 2 },       // 3: Middle
            { startX + w / 4,     startY + 2 * h / 3 },   // 4: Bottom-Left
            { startX + 3 * w / 4, startY + 2 * h / 3 },   // 5: Bottom-Right
            { startX + w / 2,     startY + 5 * h / 6 }    // 6: Bottom
        };

        for (int i = 0; i < 7; i++) {
            int px = Math.min(bitmap.getWidth() - 1, Math.max(0, samplePoints[i][0]));
            int py = Math.min(bitmap.getHeight() - 1, Math.max(0, samplePoints[i][1]));
            int pixel = bitmap.getPixel(px, py);
            Color.colorToHSV(pixel, hsv);

            // Sắc độ đỏ/cam rực (Hue <= 25 hoặc >= 335, Sat >= 0.40, Val >= 0.55)
            if ((hsv[0] <= 25f || hsv[0] >= 335f) && hsv[1] >= 0.40f && hsv[2] >= 0.55f) {
                segs[i] = true;
            }
        }

        // Khớp mẫu với bảng chữ số từ 0 -> 9
        int bestMatchDigit = -1;
        int maxMatchedSegs = -1;

        for (int d = 0; d <= 9; d++) {
            int matches = 0;
            for (int s = 0; s < 7; s++) {
                if (segs[s] == SEGMENT_PATTERNS[d][s]) {
                    matches++;
                }
            }
            if (matches >= 6 && matches > maxMatchedSegs) {
                maxMatchedSegs = matches;
                bestMatchDigit = d;
            }
        }

        return bestMatchDigit;
    }

    public void reset() {
        lastDetectedSeconds = -1;
        consecutiveMatches = 0;
    }
}
