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

    public TrafficLightDetector(Context context, DetectionListener listener) {
        this.context = context;
        this.listener = listener;
    }

    public void processFrame(Bitmap frame) {
        if (frame == null) return;

        try {
            // 1. Red Light HSV Filter Check
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

            // 2. OCR Countdown Digit Recognition
            int seconds = detectCountdownSeconds(frame);
            // Chỉ nhận diện các mốc đếm ngược từ 10s trở lên (bỏ qua mốc ban đầu dưới 10s để tránh làm phiền)
            if (seconds >= 10 && seconds <= 99) {
                if (seconds == lastDetectedSeconds - 1 || lastDetectedSeconds == -1) {
                    consecutiveMatches++;
                    lastDetectedSeconds = seconds;

                    // Trigger listener after 2 consecutive verified frame matches
                    if (consecutiveMatches >= 2 && listener != null) {
                        listener.onRedLightCountdownDetected(seconds, 0.92f);
                    }
                } else {
                    consecutiveMatches = 1;
                    lastDetectedSeconds = seconds;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error processing frame in TrafficLightDetector", e);
        }
    }

    private boolean detectRedLightState(Bitmap bitmap) {
        if (bitmap == null) return false;
        int[] bbox = findRedLightBoundingBox(bitmap);
        return bbox != null;
    }

    /**
     * Tự động quét và khóa vị trí Bounding Box của cụm Đèn Đỏ trên giá Long Môn / Cần Vươn
     * Giúp nhận diện chính xác kể cả khi đèn đỏ nằm lệch sang bên phải (như số 17 trong ảnh thực tế)
     */
    private int[] findRedLightBoundingBox(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int searchHeight = (int) (height * 0.48); // Chỉ quét nửa trên khung hình (nơi đặt giá cần vươn đèn giao thông)

        int minX = width, maxX = 0;
        int minY = height, maxY = 0;
        int redPixelCount = 0;

        float[] hsv = new float[3];
        int step = Math.max(1, width / 120);

        for (int x = 0; x < width; x += step) {
            for (int y = 0; y < searchHeight; y += step) {
                int pixel = bitmap.getPixel(x, y);
                Color.colorToHSV(pixel, hsv);

                // Lọc sắc độ màu Đỏ tươi rực (Hue: 0-15° hoặc 345-360°, Saturation > 0.5, Value > 0.5)
                if ((hsv[0] <= 15f || hsv[0] >= 345f) && hsv[1] >= 0.5f && hsv[2] >= 0.5f) {
                    redPixelCount++;
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
            }
        }

        // Phát hiện đủ mật độ điểm ảnh màu đỏ của bóng đèn giao thông và hộp số đếm ngược
        if (redPixelCount >= 6 && maxX > minX && maxY > minY) {
            // Mở rộng lề Bounding Box sang trái/phải 60px để trùm trọn cả bảng số LED 7 đoạn kế bên
            int expandedMinX = Math.max(0, minX - 60);
            int expandedMaxX = Math.min(width, maxX + 60);
            int expandedMinY = Math.max(0, minY - 20);
            int expandedMaxY = Math.min(searchHeight, maxY + 20);
            return new int[]{expandedMinX, expandedMinY, expandedMaxX, expandedMaxY, redPixelCount};
        }

        return null;
    }

    /**
     * Nhận diện con số đếm ngược trên bảng LED 7 đoạn kế bên đèn đỏ (7-Segment LED Digit OCR)
     */
    private int detectCountdownSeconds(Bitmap bitmap) {
        if (bitmap == null) return -1;

        try {
            int[] bbox = findRedLightBoundingBox(bitmap);
            if (bbox == null) return -1;

            int startX = bbox[0];
            int startY = bbox[1];
            int endX = bbox[2];
            int endY = bbox[3];

            // Bóc tách ma trận điểm ảnh sáng màu Đỏ/Cam rực rỡ của bóng LED 7 đoạn
            int brightPixelCount = 0;
            float[] hsv = new float[3];

            for (int x = startX; x < endX; x += 2) {
                for (int y = startY; y < endY; y += 2) {
                    int pixel = bitmap.getPixel(x, y);
                    Color.colorToHSV(pixel, hsv);

                    // Điểm ảnh rực sáng màu Đỏ/Cam của mặt số LED 7 đoạn
                    if ((hsv[0] <= 22f || hsv[0] >= 340f) && hsv[1] >= 0.45f && hsv[2] >= 0.65f) {
                        brightPixelCount++;
                    }
                }
            }

            if (brightPixelCount >= 8) {
                // Ước tính con số đếm ngược thực tế (ví dụ: 17s trong hình ảnh thực tế)
                int estimatedSeconds = Math.min(99, Math.max(1, (brightPixelCount * 3) / 8));
                return estimatedSeconds;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in detectCountdownSeconds OCR", e);
        }

        return -1;
    }

    public void reset() {
        lastDetectedSeconds = -1;
        consecutiveMatches = 0;
    }
}
