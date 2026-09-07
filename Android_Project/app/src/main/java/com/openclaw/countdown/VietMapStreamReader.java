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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

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

    private long lastWakeUpAttemptTime = 0;
    private String activeCameraIp = "192.168.1.254";
    private int activeStreamingPort = -1;

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

    private String formatIp(int ipInt) {
        return (ipInt & 0xFF) + "." + ((ipInt >> 8) & 0xFF) + "." + ((ipInt >> 16) & 0xFF) + "." + ((ipInt >> 24) & 0xFF);
    }

    private List<String> getCandidateIps() {
        List<String> ips = new ArrayList<>();
        try {
            if (context != null) {
                WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (wm != null) {
                    DhcpInfo dhcp = wm.getDhcpInfo();
                    if (dhcp != null) {
                        if (dhcp.serverAddress != 0) {
                            String sIp = formatIp(dhcp.serverAddress);
                            if (!"0.0.0.0".equals(sIp) && !ips.contains(sIp)) ips.add(sIp);
                        }
                        if (dhcp.gateway != 0) {
                            String gIp = formatIp(dhcp.gateway);
                            if (!"0.0.0.0".equals(gIp) && !ips.contains(gIp)) ips.add(gIp);
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        if (!ips.contains("192.168.1.254")) ips.add("192.168.1.254");
        if (!ips.contains("192.168.0.1")) ips.add("192.168.0.1");
        if (!ips.contains("192.168.1.1")) ips.add("192.168.1.1");
        if (!ips.contains("192.168.42.1")) ips.add("192.168.42.1");
        if (!ips.contains("192.168.43.1")) ips.add("192.168.43.1");
        return ips;
    }

    /**
     * Quét nhanh TCP Socket SYN để kiểm tra cổng nào đang mở trên Camera (<250ms)
     */
    private boolean isPortOpen(String host, int port, int timeoutMs) {
        Socket s = null;
        try {
            ensureWifiNetworkBound();
            if (cameraWifiNetwork != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                s = cameraWifiNetwork.getSocketFactory().createSocket();
            } else {
                s = new Socket();
            }
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (s != null) {
                try { s.close(); } catch (Exception ignored) {}
            }
        }
    }

    private void sendNovatekHandshake(String gatewayIp) {
        String[] handshakeUrls = new String[]{
            "http://" + gatewayIp + "/?custom=1&cmd=3001",
            "http://" + gatewayIp + "/?custom=1&cmd=1001",
            "http://" + gatewayIp + "/?custom=1&cmd=2001&par=1",
            "http://" + gatewayIp + "/?custom=1&cmd=3014",
            "http://" + gatewayIp + "/?custom=1&cmd=3016",
            "http://" + gatewayIp + "/?custom=1&cmd=1005"
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
                conn.setConnectTimeout(400);
                conn.setReadTimeout(400);
                conn.setRequestMethod("GET");
                conn.setRequestProperty("User-Agent", "VietMap/1.0");
                conn.connect();
                conn.getResponseCode();
                conn.disconnect();
            } catch (Exception ignored) {}
        }
    }

    public boolean isWifiAvailable() {
        if (context == null) return false;
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    Network[] networks = cm.getAllNetworks();
                    if (networks != null) {
                        for (Network net : networks) {
                            NetworkCapabilities caps = cm.getNetworkCapabilities(net);
                            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                                return true;
                            }
                        }
                    }
                } else {
                    android.net.NetworkInfo ni = cm.getActiveNetworkInfo();
                    if (ni != null && ni.getType() == ConnectivityManager.TYPE_WIFI && ni.isConnected()) {
                        return true;
                    }
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    public void startStreaming() {
        if (isStreaming) return;
        isStreaming = true;

        ensureWifiNetworkBound();

        streamThread = new HandlerThread("VietMapStreamWorker");
        streamThread.start();
        streamHandler = new Handler(streamThread.getLooper());

        Log.d(TAG, "Bắt đầu tiến trình scanning & streaming camera VietMap...");

        streamHandler.post(new Runnable() {
            @Override
            public void run() {
                if (!isStreaming) return;

                if (!isWifiAvailable()) {
                    if (statusListener != null) {
                        statusListener.onStatusUpdated("Chờ kết nối Wi-Fi Camera VietMap...", false);
                    }
                    if (isStreaming && streamHandler != null) {
                        streamHandler.postDelayed(this, 3000);
                    }
                    return;
                }

                ensureWifiNetworkBound();
                List<String> candidateIps = getCandidateIps();
                boolean streamSuccess = false;

                // Thử từng IP (Server IP, Gateway IP, 192.168.1.254, ...)
                for (String ip : candidateIps) {
                    if (!isStreaming) break;

                    if (statusListener != null) {
                        statusListener.onStatusUpdated("Đang quét IP Cam: " + ip + "...", false);
                    }

                    // 1. Quét nhanh xem các cổng nào đang mở trên IP này
                    boolean port8192Open = isPortOpen(ip, 8192, 250);
                    boolean port554Open  = isPortOpen(ip, 554, 250);
                    boolean port80Open   = isPortOpen(ip, 80, 250);
                    boolean port8080Open = isPortOpen(ip, 8080, 250);
                    boolean port7060Open = isPortOpen(ip, 7060, 250);

                    Log.d(TAG, "Kết quả quét IP " + ip + " -> 8192:" + port8192Open + ", 554:" + port554Open + ", 80:" + port80Open + ", 8080:" + port8080Open + ", 7060:" + port7060Open);

                    // Nếu cổng 80 mở -> Gửi lệnh khởi tạo
                    if (port80Open) {
                        sendNovatekHandshake(ip);
                    }

                    // ƯU TIÊN 1: Cổng 8192 (Novatek Live MJPEG)
                    if (port8192Open) {
                        activeCameraIp = ip;
                        activeStreamingPort = 8192;
                        if (statusListener != null) {
                            statusListener.onStatusUpdated("Mở luồng Live (Port 8192)...", false);
                        }
                        streamSuccess = streamMjpegSocketLoop(ip, 8192);
                        if (streamSuccess) break;
                    }

                    // ƯU TIÊN 2: Cổng 554 (RTSP Stream)
                    if (port554Open && isStreaming) {
                        activeCameraIp = ip;
                        activeStreamingPort = 554;
                        if (statusListener != null) {
                            statusListener.onStatusUpdated("Mở luồng RTSP (Port 554)...", false);
                        }
                        String[] rtspPaths = new String[]{
                            "rtsp://" + ip + "/pjfirst",
                            "rtsp://" + ip + "/sjcam.mov",
                            "rtsp://" + ip + "/live",
                            "rtsp://" + ip + ":554/liveRTSP/av4",
                            "rtsp://" + ip + ":554/liveRTSP/v1",
                            "rtsp://" + ip + ":554/ch0"
                        };
                        for (String path : rtspPaths) {
                            Bitmap b = fetchRtspFrameSafe(path);
                            if (b != null) {
                                if (statusListener != null) {
                                    statusListener.onStatusUpdated("Đã nhận luồng Camera (RTSP)", true);
                                }
                                if (callback != null) callback.onFrameCaptured(b);
                                if (detector != null) detector.processFrame(b);
                                b.recycle();
                                streamSuccess = true;
                                break;
                            }
                        }
                        if (streamSuccess) break;
                    }

                    // ƯU TIÊN 3: Cổng 8080 / 7060 (MJPEG Stream)
                    if ((port8080Open || port7060Open) && isStreaming) {
                        int p = port8080Open ? 8080 : 7060;
                        activeCameraIp = ip;
                        activeStreamingPort = p;
                        if (statusListener != null) {
                            statusListener.onStatusUpdated("Mở luồng Live (Port " + p + ")...", false);
                        }
                        streamSuccess = streamMjpegSocketLoop(ip, p);
                        if (streamSuccess) break;
                    }

                    // ƯU TIÊN 4: Cổng 80 (HTTP Snapshot CGI)
                    if (port80Open && isStreaming) {
                        activeCameraIp = ip;
                        activeStreamingPort = 80;
                        if (statusListener != null) {
                            statusListener.onStatusUpdated("Thử lấy luồng Snapshot (Port 80)...", false);
                        }
                        Bitmap snap = fetchHttpSnapshotFrame("http://" + ip + "/?custom=1&cmd=2017");
                        if (snap == null) snap = fetchHttpSnapshotFrame("http://" + ip + "/cgi-bin/snapshot.cgi");
                        if (snap == null) snap = fetchHttpSnapshotFrame("http://" + ip + "/snapshot.jpg");
                        if (snap != null) {
                            if (statusListener != null) {
                                statusListener.onStatusUpdated("Đã nhận luồng Camera (Snapshot CGI)", true);
                            }
                            if (callback != null) callback.onFrameCaptured(snap);
                            if (detector != null) detector.processFrame(snap);
                            snap.recycle();
                            streamSuccess = true;
                            break;
                        }
                    }
                }

                if (!streamSuccess && isStreaming) {
                    if (statusListener != null) {
                        statusListener.onStatusUpdated("Đang kết nối lại Cam (" + activeCameraIp + ")...", false);
                    }
                }

                if (isStreaming && streamHandler != null) {
                    streamHandler.postDelayed(this, streamSuccess ? 120 : 1200);
                }
            }
        });
    }

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
                                opts.inSampleSize = 2;
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

                if (System.currentTimeMillis() - lastWakeUpAttemptTime > 5000) {
                    lastWakeUpAttemptTime = System.currentTimeMillis();
                    sendNovatekHandshake(host);
                }

                if (System.currentTimeMillis() - lastFrameTime > 3500) {
                    Log.w(TAG, "Socket port " + port + " timeout -> reconnect");
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
            connection.setConnectTimeout(800);
            connection.setReadTimeout(1000);
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

