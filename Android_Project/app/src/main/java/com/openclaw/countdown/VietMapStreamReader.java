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

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
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

    private long lastRtspAttemptTime = 0;
    private long lastWakeUpAttemptTime = 0;
    private String detectedGatewayIp = "192.168.1.254";

    public VietMapStreamReader(Context context, String streamUrl, FrameCallback callback, TrafficLightDetector detector) {
        this.context = context;
        this.streamUrl = (streamUrl != null && !streamUrl.isEmpty()) ? streamUrl : DEFAULT_VIETMAP_RTSP_URL;
        this.callback = callback;
        this.detector = detector;

        ensureWifiNetworkBound();
    }

    public void setStatusListener(StreamStatusListener listener) {
        this.statusListener = listener;
    }

    /**
     * Chủ động quét và bind Process vào Interface mạng Wi-Fi của Camera
     * Đảm bảo Android Box không đẩy request nội bộ 192.168.1.254 ra mạng 4G/SIM.
     */
    public void ensureWifiNetworkBound() {
        if (context == null) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm != null) {
                    Network[] networks = cm.getAllNetworks();
                    if (networks != null) {
                        for (Network net : networks) {
                            NetworkCapabilities caps = cm.getNetworkCapabilities(net);
                            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                                cameraWifiNetwork = net;
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                    cm.bindProcessToNetwork(net);
                                } else {
                                    ConnectivityManager.setProcessDefaultNetwork(net);
                                }
                                Log.d(TAG, "Đã gán thành công Wi-Fi Network cho Process: " + net);
                                return;
                            }
                        }
                    }

                    // Đăng ký Callback lắng nghe mạng Wi-Fi
                    NetworkRequest request = new NetworkRequest.Builder()
                            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                            .build();
                    cm.requestNetwork(request, new ConnectivityManager.NetworkCallback() {
                        @Override
                        public void onAvailable(Network network) {
                            cameraWifiNetwork = network;
                            try {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                    cm.bindProcessToNetwork(network);
                                } else {
                                    ConnectivityManager.setProcessDefaultNetwork(network);
                                }
                            } catch (Exception ignored) {}
                        }
                    });
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Lỗi khi bind mạng Wi-Fi", e);
        }
    }

    private String getCameraGatewayIp() {
        try {
            if (context != null) {
                WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (wm != null) {
                    DhcpInfo dhcp = wm.getDhcpInfo();
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

    /**
     * Gửi toàn bộ tập lệnh Novatek Handshake để camera bắt đầu phát luồng video
     */
    private void sendNovatekHandshake(String gatewayIp) {
        String[] handshakeUrls = new String[]{
            "http://" + gatewayIp + "/?custom=1&cmd=3001",         // Heartbeat / Connect
            "http://" + gatewayIp + "/?custom=1&cmd=1001",         // Query System Info
            "http://" + gatewayIp + "/?custom=1&cmd=2001&par=1",   // Start Live View / Movie preview
            "http://" + gatewayIp + "/?custom=1&cmd=2001",         // Start Stream
            "http://" + gatewayIp + "/?custom=1&cmd=3014",         // Preview Ready Check
            "http://" + gatewayIp + "/?custom=1&cmd=3016",         // Start Live Stream Broadcast
            "http://" + gatewayIp + "/?custom=1&cmd=1005",         // Switch Mode
            "http://" + gatewayIp + "/?custom=1&cmd=2002&par=0"    // Stop SD loop conflict
        };

        for (String urlStr : handshakeUrls) {
            try {
                URL url = new URL(urlStr);
                HttpURLConnection conn = null;
                if (cameraWifiNetwork != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    conn = (HttpURLConnection) cameraWifiNetwork.openConnection(url);
                } else {
                    conn = (HttpURLConnection) url.openConnection();
                }
                conn.setConnectTimeout(600);
                conn.setReadTimeout(600);
                conn.setRequestMethod("GET");
                conn.setRequestProperty("User-Agent", "VietMap/1.0");
                conn.connect();
                int respCode = conn.getResponseCode();
                Log.d(TAG, "Handshake (" + urlStr + ") -> " + respCode);
                conn.disconnect();
            } catch (Exception ignored) {}
        }
    }

    public void startStreaming() {
        if (isStreaming) return;
        isStreaming = true;

        ensureWifiNetworkBound();
        detectedGatewayIp = getCameraGatewayIp();

        streamThread = new HandlerThread("VietMapStreamWorker");
        streamThread.start();
        streamHandler = new Handler(streamThread.getLooper());

        Log.d(TAG, "Bắt đầu tiến trình streaming camera VietMap (" + detectedGatewayIp + ")...");

        if (statusListener != null) {
            statusListener.onStatusUpdated("Đang kích hoạt Camera (" + detectedGatewayIp + ")...", false);
        }

        streamHandler.post(new Runnable() {
            @Override
            public void run() {
                if (!isStreaming) return;

                ensureWifiNetworkBound();
                detectedGatewayIp = getCameraGatewayIp();

                // 1. Kích hoạt camera
                sendNovatekHandshake(detectedGatewayIp);

                // 2. Thử mở luồng persistent TCP Socket MJPEG (Port 8192 & 8080)
                if (statusListener != null) {
                    statusListener.onStatusUpdated("Đang mở luồng Live (Port 8192)...", false);
                }
                boolean streamActive = streamMjpegSocketLoop(detectedGatewayIp, 8192);

                if (!streamActive && isStreaming) {
                    if (statusListener != null) {
                        statusListener.onStatusUpdated("Đang thử luồng Live (Port 8080)...", false);
                    }
                    streamActive = streamMjpegSocketLoop(detectedGatewayIp, 8080);
                }

                if (!streamActive && isStreaming) {
                    // 3. Fallback sang HTTP Snapshot CGI polling (?cmd=2017 hoặc snapshot.cgi)
                    if (statusListener != null) {
                        statusListener.onStatusUpdated("Đang thử luồng Snapshot (" + detectedGatewayIp + ")...", false);
                    }
                    Bitmap snap = fetchHttpSnapshotFrame("http://" + detectedGatewayIp + "/?custom=1&cmd=2017");
                    if (snap == null) {
                        snap = fetchHttpSnapshotFrame("http://" + detectedGatewayIp + "/cgi-bin/snapshot.cgi");
                    }
                    if (snap == null) {
                        snap = fetchHttpSnapshotFrame("http://" + detectedGatewayIp + "/snapshot.jpg");
                    }

                    if (snap != null) {
                        if (statusListener != null) {
                            statusListener.onStatusUpdated("Đã nhận luồng Camera (Snapshot CGI)", true);
                        }
                        if (callback != null) callback.onFrameCaptured(snap);
                        if (detector != null) detector.processFrame(snap);
                        snap.recycle();
                        streamActive = true;
                    } else {
                        // 4. Fallback sang RTSP
                        long now = System.currentTimeMillis();
                        if (now - lastRtspAttemptTime > 3000) {
                            lastRtspAttemptTime = now;
                            if (statusListener != null) {
                                statusListener.onStatusUpdated("Đang dò tìm luồng RTSP...", false);
                            }
                            Bitmap rtspBmp = fetchRtspFrameSafe("rtsp://" + detectedGatewayIp + "/pjfirst");
                            if (rtspBmp == null) {
                                rtspBmp = fetchRtspFrameSafe("rtsp://" + detectedGatewayIp + "/sjcam.mov");
                            }
                            if (rtspBmp == null) {
                                rtspBmp = fetchRtspFrameSafe("rtsp://" + detectedGatewayIp + "/live");
                            }
                            if (rtspBmp != null) {
                                if (statusListener != null) {
                                    statusListener.onStatusUpdated("Đã nhận luồng Camera (RTSP)", true);
                                }
                                if (callback != null) callback.onFrameCaptured(rtspBmp);
                                if (detector != null) detector.processFrame(rtspBmp);
                                rtspBmp.recycle();
                                streamActive = true;
                            }
                        }
                    }
                }

                if (!streamActive && isStreaming) {
                    if (statusListener != null) {
                        statusListener.onStatusUpdated("Đang kết nối lại Camera (" + detectedGatewayIp + ")...", false);
                    }
                }

                if (isStreaming && streamHandler != null) {
                    streamHandler.postDelayed(this, streamActive ? 150 : 800);
                }
            }
        });
    }

    /**
     * Bộ giải mã MJPEG thời gian thực qua Persistent TCP Socket:
     * Giữ nguyên 1 kết nối duy nhất, liên tục nhận và bóc tách từng khung hình JPEG (0xFF 0xD8 -> 0xFF 0xD9)
     */
    private boolean streamMjpegSocketLoop(String host, int port) {
        Socket socket = null;
        InputStream in = null;
        try {
            ensureWifiNetworkBound();
            if (cameraWifiNetwork != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                socket = cameraWifiNetwork.getSocketFactory().createSocket();
            } else {
                socket = new Socket();
            }
            socket.setSoTimeout(3000);
            socket.connect(new InetSocketAddress(host, port), 1800);

            OutputStream out = socket.getOutputStream();
            String getReq = "GET / HTTP/1.1\r\nHost: " + host + ":" + port + "\r\nUser-Agent: VietMap/1.0\r\nAccept: */*\r\nConnection: keep-alive\r\n\r\n";
            out.write(getReq.getBytes());
            out.flush();

            in = new BufferedInputStream(socket.getInputStream(), 65536);
            ByteArrayOutputStream frameBuffer = new ByteArrayOutputStream(65536);
            byte[] readBuffer = new byte[8192];
            int bytesRead;
            boolean inJpeg = false;
            int lastByte = -1;
            long lastFrameTime = System.currentTimeMillis();
            int frameCount = 0;

            while (isStreaming && (bytesRead = in.read(readBuffer)) != -1) {
                for (int i = 0; i < bytesRead; i++) {
                    int currentByte = readBuffer[i] & 0xFF;

                    if (!inJpeg) {
                        if (lastByte == 0xFF && currentByte == 0xD8) {
                            inJpeg = true;
                            frameBuffer.reset();
                            frameBuffer.write(0xFF);
                            frameBuffer.write(0xD8);
                        }
                    } else {
                        frameBuffer.write(currentByte);
                        if (lastByte == 0xFF && currentByte == 0xD9) {
                            inJpeg = false;
                            byte[] jpegBytes = frameBuffer.toByteArray();
                            if (jpegBytes.length > 2048) {
                                BitmapFactory.Options opts = new BitmapFactory.Options();
                                opts.inSampleSize = 2; // Tăng tốc độ giải mã AI
                                Bitmap bmp = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.length, opts);
                                if (bmp != null) {
                                    frameCount++;
                                    lastFrameTime = System.currentTimeMillis();
                                    if (statusListener != null) {
                                        statusListener.onStatusUpdated("Đã nhận luồng Camera (Live MJPEG)", true);
                                    }
                                    if (callback != null) {
                                        callback.onFrameCaptured(bmp);
                                    }
                                    if (detector != null) {
                                        detector.processFrame(bmp);
                                    }
                                    bmp.recycle();
                                }
                            }
                            frameBuffer.reset();
                        }
                    }
                    lastByte = currentByte;
                }

                // Gửi Heartbeat duy trì phiên mỗi 5 giây
                if (System.currentTimeMillis() - lastWakeUpAttemptTime > 5000) {
                    lastWakeUpAttemptTime = System.currentTimeMillis();
                    sendNovatekHandshake(host);
                }

                // Nếu quá 3.5s không nhận được khung hình -> socket bị nghẽn, ngắt để tái kết nối
                if (System.currentTimeMillis() - lastFrameTime > 3500) {
                    Log.w(TAG, "Socket port " + port + " timeout không có frame mới -> Reconnect");
                    break;
                }
            }
            return frameCount > 0;
        } catch (Exception e) {
            Log.d(TAG, "Socket stream port " + port + " info: " + e.getMessage());
            return false;
        } finally {
            if (in != null) {
                try { in.close(); } catch (Exception ignored) {}
            }
            if (socket != null) {
                try { socket.close(); } catch (Exception ignored) {}
            }
        }
    }

    private Bitmap fetchHttpSnapshotFrame(String snapshotUrl) {
        HttpURLConnection connection = null;
        InputStream input = null;
        try {
            ensureWifiNetworkBound();
            URL url = new URL(snapshotUrl);
            if (cameraWifiNetwork != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                connection = (HttpURLConnection) cameraWifiNetwork.openConnection(url);
            } else {
                connection = (HttpURLConnection) url.openConnection();
            }
            connection.setConnectTimeout(1000);
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
                try { input.close(); } catch (Exception ignored) {}
            }
            if (connection != null) {
                connection.disconnect();
            }
        }
        return null;
    }

    private Bitmap fetchRtspFrameSafe(String url) {
        MediaMetadataRetriever mmr = null;
        try {
            ensureWifiNetworkBound();
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

