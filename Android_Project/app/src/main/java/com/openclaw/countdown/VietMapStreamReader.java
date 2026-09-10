package com.openclaw.countdown;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.ConnectivityManager;
import android.net.DhcpInfo;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.RouteInfo;
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
import java.net.InetAddress;
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
    private volatile boolean isStreaming = false;
    private HandlerThread streamThread;
    private Handler streamHandler;
    private final TrafficLightDetector detector;
    private volatile Network cameraWifiNetwork = null;
    private final Context context;

    private long lastWakeUpAttemptTime = 0;
    private String activeCameraIp = "192.168.1.254";
    private int activeStreamingPort = -1;
    private ConnectivityManager.NetworkCallback wifiNetworkCallback = null;

    // FPS Counter
    private int frameCountInterval = 0;
    private long lastFpsUpdateTime = 0;
    private int currentFps = 0;

    public VietMapStreamReader(Context context, String streamUrl, FrameCallback callback, TrafficLightDetector detector) {
        this.context = context != null ? context.getApplicationContext() : null;
        this.streamUrl = (streamUrl != null && !streamUrl.isEmpty()) ? streamUrl : DEFAULT_VIETMAP_RTSP_URL;
        this.callback = callback;
        this.detector = detector;

        ensureWifiNetworkBound();
    }

    public void setStatusListener(StreamStatusListener listener) {
        this.statusListener = listener;
    }

    /**
     * Chủ động quét và bind Process / Socket vào Interface mạng Wi-Fi của Camera
     * Đảm bảo Android Box không đẩy request nội bộ của Cam ra mạng SIM 4G/LTE.
     */
    public synchronized void ensureWifiNetworkBound() {
        if (context == null) return;
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
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
                            Log.d(TAG, "Đã bind thành công Wi-Fi Network cho Process: " + net);
                            return;
                        }
                    }
                }

                if (wifiNetworkCallback == null) {
                    NetworkRequest request = new NetworkRequest.Builder()
                            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                            .build();
                    wifiNetworkCallback = new ConnectivityManager.NetworkCallback() {
                        @Override
                        public void onAvailable(Network network) {
                            cameraWifiNetwork = network;
                            Log.d(TAG, "NetworkCallback onAvailable: " + network);
                            try {
                                ConnectivityManager mgr = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
                                if (mgr != null) {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                        mgr.bindProcessToNetwork(network);
                                    } else {
                                        ConnectivityManager.setProcessDefaultNetwork(network);
                                    }
                                }
                            } catch (Exception ignored) {}
                        }

                        @Override
                        public void onLost(Network network) {
                            if (cameraWifiNetwork != null && cameraWifiNetwork.equals(network)) {
                                cameraWifiNetwork = null;
                            }
                        }
                    };
                    cm.requestNetwork(request, wifiNetworkCallback);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Lỗi khi bind mạng Wi-Fi", e);
        }
    }

    private String formatIp(int ipInt) {
        return (ipInt & 0xFF) + "." + ((ipInt >> 8) & 0xFF) + "." + ((ipInt >> 16) & 0xFF) + "." + ((ipInt >> 24) & 0xFF);
    }

    /**
     * Tìm chính xác IP Gateway thực tế của Camera từ LinkProperties và DHCP
     */
    public List<String> getCandidateIps() {
        List<String> ips = new ArrayList<>();
        
        // 1. Ưu tiên cao nhất: Lấy Gateway trực tiếp từ LinkProperties của mạng Wi-Fi
        try {
            if (context != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm != null) {
                    Network targetNet = cameraWifiNetwork;
                    if (targetNet == null) {
                        Network[] networks = cm.getAllNetworks();
                        if (networks != null) {
                            for (Network net : networks) {
                                NetworkCapabilities caps = cm.getNetworkCapabilities(net);
                                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                                    targetNet = net;
                                    cameraWifiNetwork = net;
                                    break;
                                }
                            }
                        }
                    }

                    if (targetNet != null) {
                        LinkProperties lp = cm.getLinkProperties(targetNet);
                        if (lp != null) {
                            List<RouteInfo> routes = lp.getRoutes();
                            if (routes != null) {
                                for (RouteInfo route : routes) {
                                    if (route.hasGateway() && route.getGateway() != null) {
                                        String gw = route.getGateway().getHostAddress();
                                        if (gw != null && !gw.equals("0.0.0.0") && !gw.startsWith("fe80") && !gw.contains(":") && !ips.contains(gw)) {
                                            Log.d(TAG, "Tìm thấy IP Gateway từ LinkProperties: " + gw);
                                            ips.add(gw);
                                        }
                                    }
                                }
                            }
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                InetAddress dhcpServer = lp.getDhcpServerAddress();
                                if (dhcpServer != null) {
                                    String s = dhcpServer.getHostAddress();
                                    if (s != null && !ips.contains(s)) {
                                        ips.add(s);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Lỗi đọc LinkProperties", e);
        }

        // 2. Lấy Gateway / Server từ WifiManager DHCP
        try {
            if (context != null) {
                WifiManager wm = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
                if (wm != null) {
                    DhcpInfo dhcp = wm.getDhcpInfo();
                    if (dhcp != null) {
                        if (dhcp.serverAddress != 0) {
                            String sIp = formatIp(dhcp.serverAddress);
                            if (!"0.0.0.0".equals(sIp) && !ips.contains(sIp)) {
                                ips.add(sIp);
                            }
                        }
                        if (dhcp.gateway != 0) {
                            String gIp = formatIp(dhcp.gateway);
                            if (!"0.0.0.0".equals(gIp) && !ips.contains(gIp)) {
                                ips.add(gIp);
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {}

        // 3. Các IP mặc định của dòng VietMap TS-C1, SpeedMap, KC01, Novatek & SigmaStar
        if (!ips.contains("192.168.1.254")) ips.add("192.168.1.254");
        if (!ips.contains("192.168.0.1"))   ips.add("192.168.0.1");
        if (!ips.contains("192.168.1.1"))   ips.add("192.168.1.1");
        if (!ips.contains("192.168.42.1"))  ips.add("192.168.42.1");
        if (!ips.contains("192.168.10.1"))  ips.add("192.168.10.1");
        if (!ips.contains("192.168.2.1"))   ips.add("192.168.2.1");

        return ips;
    }

    /**
     * Mở Socket đã được ràng buộc trực tiếp vào Network Wi-Fi để không bị 4G can thiệp
     */
    private Socket createWifiBoundSocket() {
        try {
            ensureWifiNetworkBound();
            if (cameraWifiNetwork != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                return cameraWifiNetwork.getSocketFactory().createSocket();
            }
        } catch (Exception ignored) {}
        return new Socket();
    }

    /**
     * Quét nhanh TCP Socket SYN để kiểm tra cổng mở
     */
    private boolean isPortOpen(String host, int port, int timeoutMs) {
        Socket s = null;
        try {
            s = createWifiBoundSocket();
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

    /**
     * Mở HTTP Connection đã được ràng buộc vào Network Wi-Fi
     */
    private HttpURLConnection openWifiConnection(String urlStr) {
        try {
            ensureWifiNetworkBound();
            URL url = new URL(urlStr);
            if (cameraWifiNetwork != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                return (HttpURLConnection) cameraWifiNetwork.openConnection(url);
            } else {
                return (HttpURLConnection) url.openConnection();
            }
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Gửi chuỗi lệnh Handshake của app VIETMAP REC dành riêng cho dòng TS-C1 / Novatek / SigmaStar
     */
    private boolean sendVietMapHandshake(String gatewayIp) {
        boolean handshakeOk = false;
        try {
            // Bước 1: Khởi tạo kết nối (Heartbeat / Connect)
            String connectUrl = "http://" + gatewayIp + "/?custom=1&cmd=3001";
            HttpURLConnection conn = openWifiConnection(connectUrl);
            if (conn != null) {
                conn.setConnectTimeout(600);
                conn.setReadTimeout(600);
                conn.setRequestMethod("GET");
                conn.setRequestProperty("User-Agent", "VIETMAP REC/2.0");
                int respCode = conn.getResponseCode();
                conn.disconnect();
                if (respCode == 200) {
                    handshakeOk = true;
                    Log.d(TAG, "VietMap TS-C1 Handshake 3001 OK (" + gatewayIp + ")");
                }
            }

            // Bước 2: Chuyển sang chế độ Live View Movie Mode
            String liveModeUrl = "http://" + gatewayIp + "/?custom=1&cmd=2001&par=1";
            HttpURLConnection liveConn = openWifiConnection(liveModeUrl);
            if (liveConn != null) {
                liveConn.setConnectTimeout(600);
                liveConn.setReadTimeout(600);
                liveConn.setRequestMethod("GET");
                liveConn.setRequestProperty("User-Agent", "VIETMAP REC/2.0");
                liveConn.getResponseCode();
                liveConn.disconnect();
            }

            // Bước 3: Lấy thông tin thiết bị
            String infoUrl = "http://" + gatewayIp + "/?custom=1&cmd=3014";
            HttpURLConnection infoConn = openWifiConnection(infoUrl);
            if (infoConn != null) {
                infoConn.setConnectTimeout(500);
                infoConn.setReadTimeout(500);
                infoConn.setRequestMethod("GET");
                infoConn.getResponseCode();
                infoConn.disconnect();
            }

            // Fallback SigmaStar / MStar Handshake nếu có
            String sstarUrl = "http://" + gatewayIp + "/cgi-bin/hi3510/param.cgi?cmd=getserverinfo";
            HttpURLConnection sstarConn = openWifiConnection(sstarUrl);
            if (sstarConn != null) {
                sstarConn.setConnectTimeout(400);
                sstarConn.setReadTimeout(400);
                sstarConn.setRequestMethod("GET");
                if (sstarConn.getResponseCode() == 200) {
                    handshakeOk = true;
                }
                sstarConn.disconnect();
            }

            // Đợi 200ms để camera kích hoạt bộ mã hóa Live Stream
            Thread.sleep(200);
        } catch (Exception e) {
            Log.d(TAG, "Handshake info: " + e.getMessage());
        }
        return handshakeOk;
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
                                cameraWifiNetwork = net;
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
                        streamHandler.postDelayed(this, 2500);
                    }
                    return;
                }

                ensureWifiNetworkBound();
                List<String> candidateIps = getCandidateIps();
                boolean streamSuccess = false;

                for (String ip : candidateIps) {
                    if (!isStreaming) break;

                    if (statusListener != null) {
                        statusListener.onStatusUpdated("Kiểm tra Cam IP: " + ip + "...", false);
                    }

                    // 1. Kiểm tra nhanh các cổng phổ biến
                    boolean port80Open   = isPortOpen(ip, 80, 300);
                    boolean port8192Open = isPortOpen(ip, 8192, 300);
                    boolean port8080Open = isPortOpen(ip, 8080, 300);
                    boolean port7060Open = isPortOpen(ip, 7060, 300);
                    boolean port554Open  = isPortOpen(ip, 554, 300);

                    Log.d(TAG, "Kết quả quét IP " + ip + " -> 80:" + port80Open + ", 8192:" + port8192Open + ", 8080:" + port8080Open + ", 7060:" + port7060Open + ", 554:" + port554Open);

                    // Nếu cổng 80 mở -> Gửi Handshake của VietMap TS-C1
                    if (port80Open) {
                        if (statusListener != null) {
                            statusListener.onStatusUpdated("Handshake TS-C1 (" + ip + ")...", false);
                        }
                        sendVietMapHandshake(ip);
                        // Sau handshake, kiểm tra lại cổng 8192 xem đã kích hoạt chưa
                        if (!port8192Open) {
                            port8192Open = isPortOpen(ip, 8192, 400);
                        }
                    }

                    // ƯU TIÊN 1: Cổng 8192 (Novatek Live MJPEG Stream - Chuẩn TS-C1)
                    if (port8192Open && isStreaming) {
                        activeCameraIp = ip;
                        activeStreamingPort = 8192;
                        if (statusListener != null) {
                            statusListener.onStatusUpdated("Mở luồng Live (Port 8192)...", false);
                        }
                        streamSuccess = streamMjpegSocketLoop(ip, 8192);
                        if (streamSuccess) break;
                    }

                    // ƯU TIÊN 2: Snapshot CGI Polling (Port 80 - 100% ỔN ĐỊNH VỚI DÒNG TS-C1)
                    if (port80Open && isStreaming) {
                        activeCameraIp = ip;
                        activeStreamingPort = 80;
                        if (statusListener != null) {
                            statusListener.onStatusUpdated("Thử lấy luồng Snapshot (" + ip + ")...", false);
                        }
                        streamSuccess = streamSnapshotPollingLoop(ip);
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

                    // ƯU TIÊN 4: Cổng 554 (RTSP Stream)
                    if (port554Open && isStreaming) {
                        activeCameraIp = ip;
                        activeStreamingPort = 554;
                        if (statusListener != null) {
                            statusListener.onStatusUpdated("Mở luồng RTSP (Port 554)...", false);
                        }
                        String[] rtspPaths = new String[]{
                            "rtsp://" + ip + "/pjfirst",
                            "rtsp://" + ip + "/live",
                            "rtsp://" + ip + "/sjcam.mov",
                            "rtsp://" + ip + ":554/liveRTSP/av4",
                            "rtsp://" + ip + ":554/ch0"
                        };
                        for (String path : rtspPaths) {
                            Bitmap b = fetchRtspFrameSafe(path);
                            if (b != null) {
                                handleDecodedFrame(b, "RTSP");
                                b.recycle();
                                streamSuccess = true;
                                break;
                            }
                        }
                        if (streamSuccess) break;
                    }
                }

                if (!streamSuccess && isStreaming) {
                    if (statusListener != null) {
                        statusListener.onStatusUpdated("Đang thử lại kết nối Cam (" + activeCameraIp + ")...", false);
                    }
                }

                if (isStreaming && streamHandler != null) {
                    streamHandler.postDelayed(this, streamSuccess ? 100 : 1500);
                }
            }
        });
    }

    private void handleDecodedFrame(Bitmap bmp, String modeName) {
        frameCountInterval++;
        long now = System.currentTimeMillis();
        if (now - lastFpsUpdateTime >= 1000) {
            currentFps = frameCountInterval;
            frameCountInterval = 0;
            lastFpsUpdateTime = now;
            if (statusListener != null) {
                statusListener.onStatusUpdated("Đã nhận luồng Cam (" + modeName + " - " + currentFps + " FPS)", true);
            }
        }

        if (callback != null) {
            callback.onFrameCaptured(bmp);
        }
        if (detector != null) {
            detector.processFrame(bmp);
        }
    }

    /**
     * Đọc luồng video MJPEG liên tục từ Socket (Port 8192 / 7060 / 8080)
     */
    private boolean streamMjpegSocketLoop(String host, int port) {
        Socket socket = null;
        InputStream in = null;
        try {
            socket = createWifiBoundSocket();
            socket.setSoTimeout(3500);
            socket.connect(new InetSocketAddress(host, port), 2000);

            OutputStream out = socket.getOutputStream();
            String getReq = "GET / HTTP/1.1\r\nHost: " + host + ":" + port + "\r\nUser-Agent: VIETMAP REC/2.0\r\nAccept: */*\r\nConnection: keep-alive\r\n\r\n";
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
                                    handleDecodedFrame(bmp, "Live MJPEG");
                                    bmp.recycle();
                                }
                            }
                            frameBuffer.reset();
                        }
                    }
                    lastByte = currentByte;
                }

                // Gửi Keep-Alive cho TS-C1 mỗi 4 giây
                if (System.currentTimeMillis() - lastWakeUpAttemptTime > 4000) {
                    lastWakeUpAttemptTime = System.currentTimeMillis();
                    sendVietMapHandshake(host);
                }

                if (System.currentTimeMillis() - lastFrameTime > 4000) {
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

    /**
     * Luồng chụp Snapshot HTTP CGI liên tục (Port 80) - Fallback siêu mượt cho TS-C1
     */
    private boolean streamSnapshotPollingLoop(String host) {
        String[] snapshotUrls = new String[]{
            "http://" + host + "/?custom=1&cmd=2017",
            "http://" + host + "/cgi-bin/hi3510/snap.cgi",
            "http://" + host + "/snapshot.jpg",
            "http://" + host + "/web/cgi-bin/hi3510/snap.cgi"
        };

        String validSnapshotUrl = null;
        for (String testUrl : snapshotUrls) {
            Bitmap testBmp = fetchHttpSnapshotFrame(testUrl);
            if (testBmp != null) {
                validSnapshotUrl = testUrl;
                handleDecodedFrame(testBmp, "Snapshot CGI");
                testBmp.recycle();
                break;
            }
        }

        if (validSnapshotUrl == null) {
            return false;
        }

        int successFrames = 1;
        long loopStart = System.currentTimeMillis();

        // Chạy vòng lặp Snapshot liên tục 8-10 FPS trong khoảng thời gian
        while (isStreaming && (System.currentTimeMillis() - loopStart < 15000)) {
            long frameStart = System.currentTimeMillis();
            Bitmap snap = fetchHttpSnapshotFrame(validSnapshotUrl);
            if (snap != null) {
                successFrames++;
                handleDecodedFrame(snap, "Snapshot CGI");
                snap.recycle();
            } else {
                break;
            }

            // Gửi Keep-Alive định kỳ
            if (System.currentTimeMillis() - lastWakeUpAttemptTime > 4000) {
                lastWakeUpAttemptTime = System.currentTimeMillis();
                sendVietMapHandshake(host);
            }

            long elapsed = System.currentTimeMillis() - frameStart;
            if (elapsed < 100) { // Duy trì ~10 FPS
                try {
                    Thread.sleep(100 - elapsed);
                } catch (InterruptedException ignored) {}
            }
        }

        return successFrames > 1;
    }

    private Bitmap fetchHttpSnapshotFrame(String snapshotUrl) {
        HttpURLConnection connection = null;
        InputStream input = null;
        try {
            connection = openWifiConnection(snapshotUrl);
            if (connection == null) return null;

            connection.setConnectTimeout(800);
            connection.setReadTimeout(1200);
            connection.setUseCaches(false);
            connection.setDoInput(true);
            connection.setRequestProperty("User-Agent", "VIETMAP REC/2.0");
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
                headers.put("User-Agent", "VIETMAP REC/2.0");
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


