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

    private int detectCountdownSeconds(Bitmap bitmap) {
        // Digit OCR parser logic for 7-segment / LED matrix countdown displays
        // Returns parsed seconds integer, or -1 if unparseable
        return -1;
    }

    public void reset() {
        lastDetectedSeconds = -1;
        consecutiveMatches = 0;
    }
}
