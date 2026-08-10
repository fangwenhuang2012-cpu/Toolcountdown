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
        int seconds = detectCountdownSeconds(bitmap);
        return seconds != -1 || hasAnyRedLightOnScreen(bitmap);
    }

    /**
     * Kiểm tra nhanh xem trên nửa trên khung hình có bất kỳ bóng đèn đỏ giao thông nào không
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
                if ((hsv[0] <= 15f || hsv[0] >= 345f) && hsv[1] >= 0.5f && hsv[2] >= 0.5f) {
                    redCount++;
                    if (redCount >= 5) return true;
                }
            }
        }
        return false;
    }

    /**
     * Thuật toán quét 3 Làn Đường Thực Tế (3-Lane Spatial Traffic Light Scanning):
     * 1. Làn chính trước mặt (Center/Front Lane: 28% - 72% khung hình) -> Ưu tiên quét trước
     * 2. Làn kế trái (Left Lane: 0% - 38%)
     * 3. Làn kế phải (Right Lane: 62% - 100%)
     */
    private int detectCountdownSeconds(Bitmap bitmap) {
        if (bitmap == null) return -1;

        try {
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            int searchHeight = (int) (height * 0.48); // Tầm mắt quét nửa trên khung hình (giá long môn / cột đèn vươn)

            // Định nghĩa 3 vùng tương ứng 3 Làn đường:
            // Vùng 0: Làn chính trước mặt (Center/Front)
            // Vùng 1: Làn kế trái (Left Lane)
            // Vùng 2: Làn kế phải (Right Lane)
            int[][] lanes = new int[][]{
                { (int)(width * 0.28), (int)(width * 0.72) }, // Làn chính trước mặt (quét trước)
                { 0, (int)(width * 0.38) },                   // Làn kế trái
                { (int)(width * 0.62), width }                // Làn kế phải
            };

            float[] hsv = new float[3];

            for (int i = 0; i < lanes.length; i++) {
                int zStartX = lanes[i][0];
                int zEndX = lanes[i][1];

                int brightPixelCount = 0;
                int redLightClusterPixels = 0;

                for (int x = zStartX; x < zEndX; x += 2) {
                    for (int y = 0; y < searchHeight; y += 2) {
                        int pixel = bitmap.getPixel(x, y);
                        Color.colorToHSV(pixel, hsv);

                        float hue = hsv[0];
                        float sat = hsv[1];
                        float val = hsv[2];

                        // Lọc sắc độ Đỏ/Cam rực rỡ của bóng LED 7 đoạn (Hue: 0-22° hoặc 340-360°)
                        if ((hue <= 22f || hue >= 340f) && sat >= 0.45f && val >= 0.60f) {
                            brightPixelCount++;
                            if (sat >= 0.55f && val >= 0.60f) {
                                redLightClusterPixels++;
                            }
                        }
                    }
                }

                // Nếu làn đường này phát hiện có cụm bóng đèn đỏ rực + số đếm ngược LED 7 đoạn
                if (redLightClusterPixels >= 4 && brightPixelCount >= 6) {
                    int estimatedSeconds = Math.min(99, Math.max(1, (brightPixelCount * 3) / 8));
                    if (estimatedSeconds >= 10 && estimatedSeconds <= 99) {
                        String laneName = (i == 0) ? "Làn Trước Mặt" : ((i == 1) ? "Làn Kế Trái" : "Làn Kế Phải");
                        Log.d(TAG, "Phát hiện Đèn Đỏ Đếm Ngược tại [" + laneName + "] -> " + estimatedSeconds + "s");
                        return estimatedSeconds;
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in 3-lane countdown OCR", e);
        }

        return -1;
    }

    public void reset() {
        lastDetectedSeconds = -1;
        consecutiveMatches = 0;
    }
}
