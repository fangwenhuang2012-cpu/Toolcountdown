package com.openclaw.countdown;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.DhcpInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;

public class VietMapStreamReader {
    private static final String TAG = "VietMapStreamReader";
    public static final String DEFAULT_VIETMAP_RTSP_URL = "rtsp://192.168.1.254/pjfirst";
    
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
    private long lastWakeUpAttemptTime = 0;
    private String detectedGatewayIp = "192.168.1.254";

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

    private String getCameraGatewayIp() {
        try {
            if (context != null) {
                WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (wm != null) {
                    android.net.DhcpInfo dhcp = wm.getDhcpInfo();
                    if (dhcp != null && dhcp.gateway != 0) {
                        int g = dhcp.gateway;
                        String ip = (g & 0xFF) + "." + ((g >> 8) & 0xFF) + "." + ((g >> 16) & 0xFF) + "." + ((g >> 24) & 0xFF);
                        if (!"0.0.0.0".equals(ip)) {
                            return ip;
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return "192.168.1.254";
    }

    private String[] getCandidateHttpEndpoints(String gatewayIp) {
        return new String[]{
            "http://" + gatewayIp + ":8192",                 // Novatek Viewfinder / Live MJPEG stream (Port 8192)
            "http://" + gatewayIp + "/?custom=1&cmd=2017",  // Vietmap / Novatek Live Frame CGI
            "http://" + gatewayIp + ":8080/?action=stream", // MJPEG Stream port 8080
            "http://" + gatewayIp + ":8080/videofeed",
            "http://" + gatewayIp + "/cgi-bin/snapshot.cgi",
            "http://" + gatewayIp + "/snapshot.jpg",
            "http://" + gatewayIp + "/live.jpg",
            "http://" + gatewayIp + "/jpg/image.jpg",
            "http://192.168.1.254:8192",
            "http://192.168.1.254/?custom=1&cmd=2017",
            "http://192.168.1.254/cgi-bin/snapshot.cgi",
            "http://192.168.42.1:8192",
            "http://192.168.42.1/cgi-bin/snapshot.cgi"
        };
    }

    private String[] getCandidateRtspUrls(String gatewayIp) {
        return new String[]{
            "rtsp://" + gatewayIp + "/pjfirst",
            "rtsp://" + gatewayIp + "/sjcam.mov",
            "rtsp://" + gatewayIp + ":554/liveRTSP/av4",
            "rtsp://" + gatewayIp + ":554/liveRTSP/v1",
            "rtsp://" + gatewayIp + "/live",
            "rtsp://" + gatewayIp + ":554/ch0",
            "rtsp://" + gatewayIp + ":8554/live",
            "rtsp://192.168.1.254/pjfirst"
        };
    }

    /**
     * Gửi lệnh kích hoạt (Wake-up / Start Live View Mode) cho camera VietMap / Novatek chipset
     */
    private void sendNovatekWakeupCommand(String gatewayIp) {
        try {
            String[] wakeUrls = new String[]{
                "http://" + gatewayIp + "/?custom=1&cmd=2001&par=1",
                "http://" + gatewayIp + "/?custom=1&cmd=2001",
                "http://" + gatewayIp + "/?custom=1&cmd=3014"
            };

            for (String wakeUrl : wakeUrls) {
                try {
                    URL url = new URL(wakeUrl);
                    HttpURLConnection conn = null;
                    if (cameraWifiNetwork != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        conn = (HttpURLConnection) cameraWifiNetwork.openConnection(url);
                    } else {
                        conn = (HttpURLConnection) url.openConnection();
                    }
                    conn.setConnectTimeout(800);
                    conn.setReadTimeout(800);
                    conn.setRequestMethod("GET");
                    conn.connect();
                    int code = conn.getResponseCode();
                    Log.d(TAG, "Novatek wake-up (" + wakeUrl + ") resp: " + code);
                    conn.disconnect();
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
    }

    /**
     * Dual Network Routing:
     * Định tuyến Process Network sang Wi-Fi Camera VietMap,
     * đồng thời giữ nguyên kết nối 4G LTE/SIM cho Android Box.
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

        detectedGatewayIp = getCameraGatewayIp();
        Log.d(TAG, "Gateway IP nhận diện: " + detectedGatewayIp);

        if (context != null && cameraWifiNetwork == null) {
            bindCameraNetworkWithoutDisablingMobileData(context);
        }

        streamThread = new HandlerThread("VietMapStreamThread");
        streamThread.start();
        streamHandler = new Handler(streamThread.getLooper());

        Log.d(TAG, "Khởi chạy luồng lấy hình ảnh VietMap...");

        if (statusListener != null) {
            statusListener.onStatusUpdated("Đang kết nối luồng Camera...", false);
        }

        streamHandler.post(new Runnable() {
            @Override
            public void run() {
                if (!isStreaming) return;

                int nextDelay = 300;
                Bitmap sampleFrame = null;
                try {
                    sampleFrame = fetchFrame();
                    if (sampleFrame != null) {
                        consecutiveFailures = 0;
                        if (callback != null) {
                            callback.onFrameCaptured(sampleFrame);
                        }
                        if (detector != null) {
                            detector.processFrame(sampleFrame);
                        }
                        nextDelay = 200; // ~5 FPS khi có luồng hình ổn định
                    } else {
                        consecutiveFailures++;
                        if (consecutiveFailures > 4) {
                            nextDelay = 600; // Giãn nhịp khi đang quét để tránh tràn bộ đệm
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

    private Bitmap fetchFrame() {
        detectedGatewayIp = getCameraGatewayIp();
        String[] httpEndpoints = getCandidateHttpEndpoints(detectedGatewayIp);
        String[] rtspUrls = getCandidateRtspUrls(detectedGatewayIp);

        long currentTime = System.currentTimeMillis();

        // 1. Gửi lệnh Wakeup định kỳ mỗi 8 giây nếu chưa có hình
        if (currentTime - lastWakeUpAttemptTime > 8000) {
            lastWakeUpAttemptTime = currentTime;
            sendNovatekWakeupCommand(detectedGatewayIp);
        }

        // 2. Thử Endpoint HTTP / MJPEG active trước
        if (activeSnapshotEndpointIndex < httpEndpoints.length) {
            String activeEndpoint = httpEndpoints[activeSnapshotEndpointIndex];
            Bitmap bmp = activeEndpoint.contains(":8192") || activeEndpoint.contains("stream") 
                    ? fetchMjpegStreamFrame(activeEndpoint) 
                    : fetchHttpSnapshotFrame(activeEndpoint);
            if (bmp != null) {
                if (statusListener != null) {
                    statusListener.onStatusUpdated("Đã nhận luồng Camera (Live)", true);
                }
                return bmp;
            }
        }
        
        // 3. Quét luân phiên các endpoint HTTP & MJPEG khác
        for (int i = 0; i < httpEndpoints.length; i++) {
            if (i == activeSnapshotEndpointIndex) continue;
            String candidate = httpEndpoints[i];
            Bitmap bmp = candidate.contains(":8192") || candidate.contains("stream") 
                    ? fetchMjpegStreamFrame(candidate) 
                    : fetchHttpSnapshotFrame(candidate);
            if (bmp != null) {
                activeSnapshotEndpointIndex = i;
                if (statusListener != null) {
                    statusListener.onStatusUpdated("Đã nhận luồng Camera (Live)", true);
                }
                return bmp;
            }
        }

        // 4. Fallback sang RTSP với khoảng giãn 3 giây/lần
        if (currentTime - lastRtspAttemptTime > 3000) {
            lastRtspAttemptTime = currentTime;
            for (String rtspCandidate : rtspUrls) {
                Bitmap rtspBitmap = fetchRtspFrameSafe(rtspCandidate);
                if (rtspBitmap != null) {
                    if (statusListener != null) {
                        statusListener.onStatusUpdated("Đã nhận luồng Camera (RTSP)", true);
                    }
                    return rtspBitmap;
                }
            }
        }

        if (statusListener != null) {
            statusListener.onStatusUpdated("Đang dò tìm luồng (" + detectedGatewayIp + ")...", false);
        }
        return null;
    }

    private Bitmap fetchMjpegStreamFrame(String mjpegUrl) {
        HttpURLConnection conn = null;
        InputStream in = null;
        try {
            URL url = new URL(mjpegUrl);
            if (cameraWifiNetwork != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                conn = (HttpURLConnection) cameraWifiNetwork.openConnection(url);
            } else {
                conn = (HttpURLConnection) url.openConnection();
            }
            conn.setConnectTimeout(1200);
            conn.setReadTimeout(1800);
            conn.setUseCaches(false);
            conn.setRequestProperty("User-Agent", "VietMap/1.0");
            conn.setRequestProperty("Accept", "*/*");
            conn.connect();

            int code = conn.getResponseCode();
            if (code == 200) {
                in = conn.getInputStream();
                ByteArrayOutputStream baos = new ByteArrayOutputStream(65536);
                int prev = -1;
                int cur;
                boolean inJpeg = false;
                long startTime = System.currentTimeMillis();

                while ((cur = in.read()) != -1) {
                    if (System.currentTimeMillis() - startTime > 1800) break;

                    if (!inJpeg) {
                        if (prev == 0xFF && cur == 0xD8) {
                            inJpeg = true;
                            baos.write(0xFF);
                            baos.write(0xD8);
                        }
                    } else {
                        baos.write(cur);
                        if (prev == 0xFF && cur == 0xD9) {
                            byte[] jpegData = baos.toByteArray();
                            BitmapFactory.Options opts = new BitmapFactory.Options();
                            opts.inSampleSize = 2;
                            return BitmapFactory.decodeByteArray(jpegData, 0, jpegData.length, opts);
                        }
                    }
                    prev = cur;
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (in != null) {
                try { in.close(); } catch (Exception ignored) {}
            }
            if (conn != null) {
                conn.disconnect();
            }
        }
        return null;
    }

    private Bitmap fetchRtspFrameSafe(String url) {
        MediaMetadataRetriever mmr = null;
        try {
            mmr = new MediaMetadataRetriever();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
                HashMap<String, String> headers = new HashMap<String, String>();
                headers.put("User-Agent", "VietMap AI");
                mmr.setDataSource(url, headers);
            } else {
                mmr.setDataSource(url);
            }
            return mmr.getFrameAtTime(-1);
        } catch (Exception e) {
            Log.d(TAG, "RTSP probe (" + url + ") failed: " + e.getMessage());
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
            connection.setConnectTimeout(1200);
            connection.setReadTimeout(1200);
            connection.setUseCaches(false);
            connection.setDoInput(true);
            connection.setRequestProperty("User-Agent", "VietMap/1.0");
            connection.setRequestProperty("Accept", "image/jpeg, image/png, */*");
            connection.connect();

            int code = connection.getResponseCode();
            if (code == 200) {
                input = connection.getInputStream();
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inSampleSize = 2;
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

