package com.openclaw.countdown;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
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
    
    // Danh sách đầy đủ các điểm Snapshot / Stream HTTP phổ biến của Camera Hành Trình VietMap / Papago / 70mai / Dashcam
    public static final String[] SNAPSHOT_ENDPOINTS = new String[]{
        "http://192.168.1.254/cgi-bin/snapshot.cgi",
        "http://192.168.42.1/cgi-bin/snapshot.cgi",
        "http://192.168.1.254/snapshot.jpg",
        "http://192.168.0.1/snapshot.jpg",
        "http://192.168.1.254:8080/videofeed",
        "http://192.168.1.254/live.jpg",
        "http://192.168.1.254/jpg/image.jpg",
        "http://192.168.42.1/jpg/image.jpg",
        "http://192.168.1.254/video.cgi",
        "http://192.168.1.254:8080/?action=snapshot"
    };

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
    private Network cameraWifiNetwork = null;
    private Context context = null;

    private int activeSnapshotEndpointIndex = 0;
    private int consecutiveFailures = 0;
    private long lastRtspAttemptTime = 0;

    public VietMapStreamReader(Context context, String streamUrl, FrameCallback callback, TrafficLightDetector detector) {
        this.context = context;
        this.streamUrl = (streamUrl != null && !streamUrl.isEmpty()) ? streamUrl : DEFAULT_VIETMAP_RTSP_URL;
        this.callback = callback;
        this.detector = detector;

        if (context != null) {
            bindCameraNetworkWithoutDisablingMobileData(context);
        }
    }

    public void setStatusListener(StreamStatusListener listener) {
        this.statusListener = listener;
    }

    /**
     * Dual Network Routing:
     * Định tuyến Process Network sang Wi-Fi Camera VietMap (192.168.1.254 / 192.168.42.1),
     * đồng thời giữ nguyên kết nối 4G LTE/SIM cho Android Box để các ứng dụng khác vẫn dùng 4G 100% bình thường.
     */
    public void bindCameraNetworkWithoutDisablingMobileData(final Context ctx) {
        if (ctx == null) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                final ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm != null) {
                    NetworkRequest request = new NetworkRequest.Builder()
                            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                            .build();

                    cm.requestNetwork(request, new ConnectivityManager.NetworkCallback() {
                        @Override
                        public void onAvailable(Network network) {
                            cameraWifiNetwork = network;
                            Log.d(TAG, "Wi-Fi VietMap Local Network bound for process.");
                            try {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                    cm.bindProcessToNetwork(network);
                                } else {
                                    ConnectivityManager.setProcessDefaultNetwork(network);
                                }
                            } catch (Exception e) {
                                Log.e(TAG, "Lỗi bindProcessToNetwork", e);
                            }
                        }
                    });
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Lỗi bind mạng cục bộ camera", e);
        }
    }

    public void startStreaming() {
        if (isStreaming) return;
        isStreaming = true;
        consecutiveFailures = 0;

        if (context != null && cameraWifiNetwork == null) {
            bindCameraNetworkWithoutDisablingMobileData(context);
        }

        streamThread = new HandlerThread("VietMapStreamThread");
        streamThread.start();
        streamHandler = new Handler(streamThread.getLooper());

        Log.d(TAG, "Khởi chạy luồng lấy hình ảnh VietMap từ: " + streamUrl);

        if (statusListener != null) {
            statusListener.onStatusUpdated("Đang kết nối luồng Camera...", false);
        }

        streamHandler.post(new Runnable() {
            @Override
            public void run() {
                if (!isStreaming) return;

                int nextDelay = 300; // Mặc định 300ms (~3.3 FPS)
                Bitmap sampleFrame = null;
                try {
                    sampleFrame = fetchFrame(streamUrl);
                    if (sampleFrame != null) {
                        consecutiveFailures = 0;
                        if (callback != null) {
                            callback.onFrameCaptured(sampleFrame);
                        }
                        if (detector != null) {
                            detector.processFrame(sampleFrame);
                        }
                        nextDelay = 250; // Giữ ~4 FPS khi có hình
                    } else {
                        consecutiveFailures++;
                        if (consecutiveFailures > 5) {
                            nextDelay = 800; // Giảm tần số khi chưa thấy camera để tiết kiệm CPU & RAM
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Lỗi lấy khung hình Camera VietMap", e);
                    if (callback != null) {
                        callback.onStreamError(e.getMessage());
                    }
                    if (statusListener != null) {
                        statusListener.onStatusUpdated("Lỗi luồng: " + e.getMessage(), false);
                    }
                } finally {
                    // GIẢI PHÓNG BITMAP TRIỆT ĐỂ ĐỂ TRÁNH RÒ RỈ BỘ NHỚ KHỚP MÀN HÌNH ĐƠ TRÊN ANDROID BOX
                    if (sampleFrame != null && !sampleFrame.isRecycled()) {
                        sampleFrame.recycle();
                    }
                }

                if (isStreaming && streamHandler != null) {
                    streamHandler.postDelayed(this, nextDelay);
                }
            }
        });
    }

    private Bitmap fetchFrame(String url) {
        // 1. Thử Endpoint HTTP Snapshot active trước
        String activeEndpoint = SNAPSHOT_ENDPOINTS[activeSnapshotEndpointIndex];
        Bitmap httpBitmap = fetchHttpSnapshotFrame(activeEndpoint);
        
        if (httpBitmap == null) {
            // Thử luân phiên các endpoint snapshot khác
            for (int i = 0; i < SNAPSHOT_ENDPOINTS.length; i++) {
                if (i == activeSnapshotEndpointIndex) continue;
                httpBitmap = fetchHttpSnapshotFrame(SNAPSHOT_ENDPOINTS[i]);
                if (httpBitmap != null) {
                    activeSnapshotEndpointIndex = i;
                    break;
                }
            }
        }

        if (httpBitmap != null) {
            if (statusListener != null) {
                statusListener.onStatusUpdated("Đã kết nối Camera (Snapshot Live)", true);
            }
            return httpBitmap;
        }

        // 2. Fallback sang RTSP với khoảng giãn 4 giây/lần để tránh đè nén làm treo hệ thống
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastRtspAttemptTime > 4000) {
            lastRtspAttemptTime = currentTime;
            Bitmap rtspBitmap = fetchRtspFrameSafe(url);
            if (rtspBitmap != null) {
                if (statusListener != null) {
                    statusListener.onStatusUpdated("Đã kết nối Camera (RTSP Live)", true);
                }
                return rtspBitmap;
            }
        }

        if (statusListener != null) {
            statusListener.onStatusUpdated("Chưa thấy tín hiệu Camera (Đang quét 192.168.x.x)", false);
        }
        return null;
    }

    private Bitmap fetchRtspFrameSafe(String url) {
        MediaMetadataRetriever mmr = null;
        try {
            mmr = new MediaMetadataRetriever();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ECLAIR_MR1) {
                mmr.setDataSource(url, new HashMap<String, String>());
            } else {
                mmr.setDataSource(url);
            }
            return mmr.getFrameAtTime(-1);
        } catch (Exception e) {
            Log.e(TAG, "Lỗi lấy luồng RTSP từ " + url, e);
        } finally {
            if (mmr != null) {
                try {
                    mmr.release();
                } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    private Bitmap fetchHttpSnapshotFrame(String snapshotUrl) {
        HttpURLConnection connection = null;
        InputStream input = null;
        try {
            URL url = new URL(snapshotUrl);
            if (cameraWifiNetwork != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                connection = (HttpURLConnection) cameraWifiNetwork.openConnection(url);
            } else {
                connection = (HttpURLConnection) url.openConnection();
            }
            connection.setConnectTimeout(600);
            connection.setReadTimeout(600);
            connection.setUseCaches(false);
            connection.setDoInput(true);
            connection.connect();

            if (connection.getResponseCode() == 200) {
                input = connection.getInputStream();
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inSampleSize = 2; // Hạ bớt độ phân giải khung hình để tiết kiệm RAM & CPU xử lý AI
                return BitmapFactory.decodeStream(input, null, opts);
            }
        } catch (Exception ignored) {
        } finally {
            if (input != null) {
                try {
                    input.close();
                } catch (Exception ignored) {}
            }
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
        Log.d(TAG, "Luồng VietMap stream đã dừng.");
    }

    public boolean isStreaming() {
        return isStreaming;
    }
}

