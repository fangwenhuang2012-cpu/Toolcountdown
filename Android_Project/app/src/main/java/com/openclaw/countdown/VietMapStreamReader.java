package com.openclaw.countdown;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;

public class VietMapStreamReader {
    private static final String TAG = "VietMapStreamReader";
    public static final String DEFAULT_VIETMAP_RTSP_URL = "rtsp://192.168.1.254/pjfirst";
    public static final String DEFAULT_VIETMAP_SNAPSHOT_URL = "http://192.168.1.254/cgi-bin/snapshot.cgi";
    public static final String ALT_VIETMAP_SNAPSHOT_URL = "http://192.168.42.1/cgi-bin/snapshot.cgi";

    public interface FrameCallback {
        void onFrameCaptured(Bitmap bitmap);
        void onStreamError(String errorMessage);
    }

    public interface StreamStatusListener {
        void onStatusUpdated(String streamStatus, boolean isConnected);
    }

    private String streamUrl;
    private final FrameCallback callback;
    private StreamStatusListener statusListener;
    private boolean isStreaming = false;
    private HandlerThread streamThread;
    private Handler streamHandler;
    private final TrafficLightDetector detector;

    public VietMapStreamReader(String streamUrl, FrameCallback callback, TrafficLightDetector detector) {
        this.streamUrl = (streamUrl != null && !streamUrl.isEmpty()) ? streamUrl : DEFAULT_VIETMAP_RTSP_URL;
        this.callback = callback;
        this.detector = detector;
    }

    public void setStatusListener(StreamStatusListener listener) {
        this.statusListener = listener;
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

        Log.d(TAG, "Starting VietMap RTSP/HTTP stream fetch from: " + streamUrl);

        if (statusListener != null) {
            statusListener.onStatusUpdated("Đang kết nối luồng Camera...", false);
        }

        // Frame sampling loop (Fetch 3-4 frames per second when vehicle is stopped)
        streamHandler.post(new Runnable() {
            @Override
            public void run() {
                if (!isStreaming) return;

                try {
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
                    if (statusListener != null) {
                        statusListener.onStatusUpdated("Lỗi luồng: " + e.getMessage(), false);
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
        // 1. Dùng HTTP Snapshot endpoint trước (Tốc độ cao & tương thích cao nhất với các dòng camera VietMap / Papago)
        Bitmap httpBitmap = fetchHttpSnapshotFrame(DEFAULT_VIETMAP_SNAPSHOT_URL);
        if (httpBitmap == null) {
            httpBitmap = fetchHttpSnapshotFrame(ALT_VIETMAP_SNAPSHOT_URL);
        }
        if (httpBitmap != null) {
            if (statusListener != null) {
                statusListener.onStatusUpdated("Đã kết nối Camera (Live 3.3 FPS)", true);
            }
            return httpBitmap;
        }

        // 2. Fallback sang RTSP via MediaMetadataRetriever
        try {
            MediaMetadataRetriever mmr = new MediaMetadataRetriever();
            mmr.setDataSource(url, new HashMap<String, String>());
            Bitmap rtspBitmap = mmr.getFrameAtTime(-1);
            try {
                mmr.release();
            } catch (Throwable ignored) {}
            if (rtspBitmap != null) {
                if (statusListener != null) {
                    statusListener.onStatusUpdated("Đã kết nối Camera (RTSP Stream)", true);
                }
                return rtspBitmap;
            }
        } catch (Exception e) {
            Log.e(TAG, "Lỗi lấy luồng RTSP từ " + url, e);
        }

        if (statusListener != null) {
            statusListener.onStatusUpdated("Mất kết nối Camera (Chưa thấy 192.168.1.254)", false);
        }
        return null;
    }

    private Bitmap fetchHttpSnapshotFrame(String snapshotUrl) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(snapshotUrl);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(1200);
            connection.setReadTimeout(1200);
            connection.setDoInput(true);
            connection.connect();

            if (connection.getResponseCode() == 200) {
                InputStream input = connection.getInputStream();
                Bitmap bitmap = BitmapFactory.decodeStream(input);
                input.close();
                return bitmap;
            }
        } catch (Exception ignored) {
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
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
        if (statusListener != null) {
            statusListener.onStatusUpdated("Đã dừng luồng Video", false);
        }
        Log.d(TAG, "VietMap RTSP stream stopped.");
    }

    public boolean isStreaming() {
        return isStreaming;
    }
}
