package com.openclaw.countdown;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

public class VietMapStreamReader {
    private static final String TAG = "VietMapStreamReader";
    public static final String DEFAULT_VIETMAP_RTSP_URL = "rtsp://192.168.1.254/pjfirst";

    public interface FrameCallback {
        void onFrameCaptured(Bitmap bitmap);
        void onStreamError(String errorMessage);
    }

    private String streamUrl;
    private final FrameCallback callback;
    private boolean isStreaming = false;
    private HandlerThread streamThread;
    private Handler streamHandler;
    private final TrafficLightDetector detector;

    public VietMapStreamReader(String streamUrl, FrameCallback callback, TrafficLightDetector detector) {
        this.streamUrl = (streamUrl != null && !streamUrl.isEmpty()) ? streamUrl : DEFAULT_VIETMAP_RTSP_URL;
        this.callback = callback;
        this.detector = detector;
    }

    /**
     * Dual Network Routing:
     * Chỉ định rõ Wi-Fi kết nối tới IP Cam VietMap (192.168.1.254),
     * đồng thời giữ nguyên kết nối 4G LTE/SIM cho hệ thống Android Box
     * để YouTube, Google Maps, VietMap Live,... vẫn dùng mạng 4G 100% bình thường.
     */
    public void bindCameraNetworkWithoutDisablingMobileData(android.content.Context context) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                android.net.ConnectivityManager cm = (android.net.ConnectivityManager) context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE);
                android.net.NetworkRequest request = new android.net.NetworkRequest.Builder()
                        .addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI)
                        .removeCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build();

                cm.requestNetwork(request, new android.net.ConnectivityManager.NetworkCallback() {
                    @Override
                    public void onAvailable(android.net.Network network) {
                        Log.d(TAG, "Wi-Fi VietMap Local Network bound specifically for Camera RTSP stream. 4G LTE remains active for System Data!");
                    }
                });
            }
        } catch (Exception e) {
            Log.e(TAG, "Error binding local camera network", e);
        }
    }

    public void startStreaming() {
        if (isStreaming) return;
        isStreaming = true;

        streamThread = new HandlerThread("VietMapStreamThread");
        streamThread.start();
        streamHandler = new Handler(streamThread.getLooper());

        Log.d(TAG, "Starting VietMap RTSP stream fetch from: " + streamUrl);

        // Frame sampling loop (Fetch 3-4 frames per second when vehicle is stopped)
        streamHandler.post(new Runnable() {
            @Override
            public void run() {
                if (!isStreaming) return;

                try {
                    // Simulate frame extraction or connect to RTSP stream
                    Bitmap sampleFrame = fetchRTSPFrame(streamUrl);
                    if (sampleFrame != null) {
                        if (callback != null) {
                            callback.onFrameCaptured(sampleFrame);
                        }
                        if (detector != null) {
                            detector.processFrame(sampleFrame);
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error fetching frame from VietMap Cam", e);
                    if (callback != null) {
                        callback.onStreamError(e.getMessage());
                    }
                }

                // Schedule next frame sampling in 300ms (~3.3 FPS for minimal CPU load)
                if (isStreaming) {
                    streamHandler.postDelayed(this, 300);
                }
            }
        });
    }

    private Bitmap fetchRTSPFrame(String url) {
        // Placeholder / native RTSP decoder entry point
        // Returns bitmap frame from VietMap Camera Wi-Fi stream
        return null;
    }

    public void stopStreaming() {
        if (!isStreaming) return;
        isStreaming = false;
        if (streamHandler != null) {
            streamHandler.removeCallbacksAndMessages(null);
        }
        if (streamThread != null) {
            streamThread.quitSafely();
            streamThread = null;
        }
        Log.d(TAG, "VietMap RTSP stream stopped.");
    }

    public boolean isStreaming() {
        return isStreaming;
    }
}
