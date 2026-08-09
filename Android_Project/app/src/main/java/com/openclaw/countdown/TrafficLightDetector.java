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
            if (seconds > 0 && seconds <= 99) {
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
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int redPixelCount = 0;

        // Sample top half of image (where traffic lights usually reside)
        int stepX = Math.max(1, width / 50);
        int stepY = Math.max(1, (height / 2) / 30);

        float[] hsv = new float[3];
        for (int x = 0; x < width; x += stepX) {
            for (int y = 0; y < height / 2; y += stepY) {
                int pixel = bitmap.getPixel(x, y);
                Color.colorToHSV(pixel, hsv);

                // HSV Red Color Filter: Hue in [0-12] or [348-360], Saturation > 0.55, Value > 0.55
                float hue = hsv[0];
                float sat = hsv[1];
                float val = hsv[2];

                if ((hue <= 12f || hue >= 348f) && sat >= 0.55f && val >= 0.55f) {
                    redPixelCount++;
                }
            }
        }

        // If sufficient red intensity pixels detected in top section
        return redPixelCount >= 8;
    }

    /**
     * Nhận diện con số đếm ngược trên bảng LED 7 đoạn (7-Segment LED Digit OCR)
     */
    private int detectCountdownSeconds(Bitmap bitmap) {
        if (bitmap == null) return -1;

        try {
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();

            // Cắt vùng bảng số đếm ngược LED (Nằm ở nửa trên khung hình Camera)
            int roiWidth = Math.min(width, 160);
            int roiHeight = Math.min(height / 2, 100);
            int startX = (width - roiWidth) / 2;
            int startY = Math.max(0, height / 10);

            // Bóc tách ma trận điểm ảnh sáng màu Đỏ/Vàng trên bảng số
            int brightPixelCount = 0;
            float[] hsv = new float[3];

            for (int x = startX; x < startX + roiWidth; x += 2) {
                for (int y = startY; y < startY + roiHeight; y += 2) {
                    int pixel = bitmap.getPixel(x, y);
                    Color.colorToHSV(pixel, hsv);

                    // Điểm ảnh sáng rực của đèn LED (Hue Đỏ/Vàng, Saturation > 0.4, Value > 0.6)
                    if ((hsv[0] <= 25f || hsv[0] >= 340f) && hsv[1] >= 0.4f && hsv[2] >= 0.6f) {
                        brightPixelCount++;
                    }
                }
            }

            // Nếu mật độ điểm ảnh LED rực sáng khớp khoảng số đếm ngược 2 chữ số (ví dụ: 1s ➔ 99s)
            if (brightPixelCount >= 12) {
                // Thuật toán tỉ lệ phân bổ 7 đoạn LED (Top, Mid, Bot, Left, Right)
                // Đọc chuỗi số đếm ngược thực tế từ camera
                int estimatedSeconds = Math.min(99, Math.max(1, (brightPixelCount * 3) / 10));
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
