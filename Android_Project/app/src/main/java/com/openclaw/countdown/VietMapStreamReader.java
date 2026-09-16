package com.openclaw.countdown;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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
import android.os.Looper;
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
import java.util.List;

/**
 * VietMapStreamReader
 * Chuyên trách kéo luồng Video trực tiếp từ Camera VietMap (Đặc biệt tối ưu cho TS-C1 Novatek NT96675)
 * Hỗ trợ cơ chế Mạng Kép (Dual Network): Ép Socket/HTTP vào card mạng Wi-Fi của Cam,
 * bảo toàn 100% kết nối SIM 4G/LTE cho Android Box (Google Maps, Youtube, Zing MP3 hoạt động bình thường).
 */
public class VietMapStreamReader {
    private static final String TAG = "VietMapStreamReader";
    public static final String DEFAULT_VIETMAP_IP = "192.168.1.254";

    public enum StreamMode {
        AUTO,               // Tự động thử Socket 8192, fallback sang Snapshot CGI
        FORCE_SOCKET_8192,  // Ép buộc dùng TCP Socket MJPEG Port 8192
        FORCE_SNAPSHOT_CGI  // Ép buộc dùng HTTP CGI Snapshot polling (?custom=1&cmd=2017)
    }

    public interface FrameCallback {
        void onFrameCaptured(Bitmap bitmap);
        void onStreamError(String errorMessage);
    }

    public interface StreamStatusListener {
        void onStatusUpdated(String streamStatus, boolean isConnected, int currentFps, int frameCount);
    }

    public interface StreamLogListener {
        void onLog(String message);
    }

    private final Context context;
    private final FrameCallback callback;
    private final TrafficLightDetector detector;
    private StreamStatusListener statusListener;
    private StreamLogListener logListener;

    private volatile boolean isStreaming = false;
    private HandlerThread streamThread;
    private Handler streamHandler;
    private final Handler mainHandler;

    private volatile Network cameraWifiNetwork = null;
    private ConnectivityManager.NetworkCallback wifiNetworkCallback = null;

    private StreamMode currentMode = StreamMode.AUTO;
    private String activeCameraIp = DEFAULT_VIETMAP_IP;
    private int activeStreamingPort = 8192;
    private long lastWakeUpAttemptTime = 0;

    // Bộ đếm FPS & Stats
    private int totalFramesReceived = 0;
    private int frameCountInterval = 0;
    private long lastFpsUpdateTime = 0;
    private int currentFps = 0;

    public VietMapStreamReader(Context context, FrameCallback callback, TrafficLightDetector detector) {
        this.context = context != null ? context.getApplicationContext() : null;
        this.callback = callback;
        this.detector = detector;
        this.mainHandler = new Handler(Looper.getMainLooper());

        ensureWifiNetworkBound();
    }

    public void setStatusListener(StreamStatusListener listener) {
        this.statusListener = listener;
    }

    public void setLogListener(StreamLogListener listener) {
        this.logListener = listener;
    }

    public void setStreamMode(StreamMode mode) {
        this.currentMode = mode;
        postLog("Đã đổi chế độ Stream: " + mode.name());
    }

    public StreamMode getStreamMode() {
        return currentMode;
    }

    private void postLog(final String msg) {
        Log.d(TAG, msg);
        if (logListener != null) {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (logListener != null) {
                        logListener.onLog(msg);
                    }
                }
            });
        }
    }

    /**
     * Ràng buộc chặt chẽ Process / Socket vào Interface Wi-Fi của Cam
     * Không cho phép các request nội bộ chạy nhầm ra card SIM 4G LTE của Android Box.
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
                            postLog("Đã ghim (bind) Socket vào Wi-Fi Network: " + net);
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
                            postLog("Wi-Fi Interface khả dụng: " + network);
                        }

                        @Override
                        public void onLost(Network network) {
                            if (cameraWifiNetwork != null && cameraWifiNetwork.equals(network)) {
                                cameraWifiNetwork = null;
                                postLog("Mất kết nối Wi-Fi Interface!");
                            }
                        }
                    };
                    cm.requestNetwork(request, wifiNetworkCallback);
                }
            }
        } catch (Exception e) {
            postLog("Lỗi ghim mạng Wi-Fi: " + e.getMessage());
        }
    }

    private String formatIp(int ipInt) {
        return (ipInt & 0xFF) + "." + ((ipInt >> 8) & 0xFF) + "." + ((ipInt >> 16) & 0xFF) + "." + ((ipInt >> 24) & 0xFF);
    }

    /**
     * Tìm chính xác IP Gateway của Cam TS-C1 từ LinkProperties và DHCP
     */
    public List<String> getCandidateIps() {
        List<String> ips = new ArrayList<>();
        try {
            if (context != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm != null && cameraWifiNetwork != null) {
                    LinkProperties lp = cm.getLinkProperties(cameraWifiNetwork);
                    if (lp != null) {
                        List<RouteInfo> routes = lp.getRoutes();
                        if (routes != null) {
                            for (RouteInfo route : routes) {
                                if (route.hasGateway() && route.getGateway() != null) {
                                    String gw = route.getGateway().getHostAddress();
                                    if (gw != null && !gw.equals("0.0.0.0") && !gw.contains(":") && !ips.contains(gw)) {
                                        ips.add(gw);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {}

        try {
            if (context != null) {
                WifiManager wm = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
                if (wm != null) {
                    DhcpInfo dhcp = wm.getDhcpInfo();
                    if (dhcp != null && dhcp.gateway != 0) {
                        String gIp = formatIp(dhcp.gateway);
                        if (!"0.0.0.0".equals(gIp) && !ips.contains(gIp)) {
                            ips.add(gIp);
                        }
                    }
                }
            }
        } catch (Exception ignored) {}

        // Mặc định chuẩn 100% của TS-C1 và các dòng VietMap Novatek
        if (!ips.contains(DEFAULT_VIETMAP_IP)) ips.add(DEFAULT_VIETMAP_IP);
        if (!ips.contains("192.168.0.1")) ips.add("192.168.0.1");
        if (!ips.contains("192.168.1.1")) ips.add("192.168.1.1");

        return ips;
    }

    private Socket createWifiBoundSocket() {
        try {
            ensureWifiNetworkBound();
            if (cameraWifiNetwork != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                return cameraWifiNetwork.getSocketFactory().createSocket();
            }
        } catch (Exception ignored) {}
        return new Socket();
    }

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
     * Gửi chuỗi lệnh Handshake của VietMap TS-C1 (Novatek NT96675)
     */
    public boolean sendVietMapHandshake(String gatewayIp) {
        boolean handshakeOk = false;
        try {
            postLog("[TS-C1] Đang gửi Handshake tới " + gatewayIp + "...");

            // Lệnh 1: Heartbeat / Connect
            String connectUrl = "http://" + gatewayIp + "/?custom=1&cmd=3001";
            HttpURLConnection conn = openWifiConnection(connectUrl);
            if (conn != null) {
                conn.setConnectTimeout(800);
                conn.setReadTimeout(800);
                conn.setRequestMethod("GET");
                conn.setRequestProperty("User-Agent", "VIETMAP REC/2.0");
                int respCode = conn.getResponseCode();
                conn.disconnect();
                if (respCode == 200) {
                    handshakeOk = true;
                    postLog("[TS-C1] Lệnh cmd=3001 OK (HTTP 200)");
                }
            }

            // Lệnh 2: Chuyển sang Movie Live View Preview Mode
            String liveModeUrl = "http://" + gatewayIp + "/?custom=1&cmd=2001&par=1";
            HttpURLConnection liveConn = openWifiConnection(liveModeUrl);
            if (liveConn != null) {
                liveConn.setConnectTimeout(800);
                liveConn.setReadTimeout(800);
                liveConn.setRequestMethod("GET");
                liveConn.setRequestProperty("User-Agent", "VIETMAP REC/2.0");
                int code2 = liveConn.getResponseCode();
                liveConn.disconnect();
                postLog("[TS-C1] Lệnh cmd=2001 (Live View) -> HTTP " + code2);
            }

            // Lệnh 3: Kích hoạt Sub-channel Live Stream
            String subStreamUrl = "http://" + gatewayIp + "/?custom=1&cmd=2016&par=1";
            HttpURLConnection subConn = openWifiConnection(subStreamUrl);
            if (subConn != null) {
                subConn.setConnectTimeout(600);
                subConn.setReadTimeout(600);
                subConn.setRequestMethod("GET");
                subConn.setRequestProperty("User-Agent", "VIETMAP REC/2.0");
                subConn.getResponseCode();
                subConn.disconnect();
            }

            // Đợi 800ms cho phần cứng Novatek khởi động bộ nén MJPEG
            Thread.sleep(800);
            postLog("[TS-C1] Handshake hoàn tất. Bộ mã hóa Cam sẵn sàng!");
        } catch (Exception e) {
            postLog("[TS-C1] Handshake exception: " + e.getMessage());
        }
        return handshakeOk;
    }

    public void triggerManualHandshake() {
        if (streamHandler != null) {
            streamHandler.post(new Runnable() {
                @Override
                public void run() {
                    sendVietMapHandshake(activeCameraIp);
                }
            });
        }
    }

    public void fetchSingleTestSnapshot() {
        if (streamHandler != null) {
            streamHandler.post(new Runnable() {
                @Override
                public void run() {
                    postLog("[Test] Thử tải 1 ảnh Snapshot từ " + activeCameraIp + "...");
                    String testUrl = "http://" + activeCameraIp + "/?custom=1&cmd=2017";
                    Bitmap bmp = fetchHttpSnapshotFrame(testUrl);
                    if (bmp != null) {
                        postLog("[Test] Đã nhận thành công ảnh Snapshot (" + bmp.getWidth() + "x" + bmp.getHeight() + ")!");
                        handleDecodedFrame(bmp, "Test Snapshot");
                    } else {
                        postLog("[Test] Không lấy được ảnh. Hãy bấm 'Kích hoạt Cam' trước!");
                    }
                }
            });
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
                                cameraWifiNetwork = net;
                                return true;
                            }
                        }
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

        postLog("Bắt đầu tiến trình kết nối & kéo luồng video TS-C1...");

        streamHandler.post(new Runnable() {
            @Override
            public void run() {
                if (!isStreaming) return;

                if (!isWifiAvailable()) {
                    updateStatus("Chờ kết nối Wi-Fi Camera VietMap...", false);
                    if (isStreaming && streamHandler != null) {
                        streamHandler.postDelayed(this, 2000);
                    }
                    return;
                }

                ensureWifiNetworkBound();
                List<String> candidateIps = getCandidateIps();
                boolean streamSuccess = false;

                for (String ip : candidateIps) {
                    if (!isStreaming) break;
                    activeCameraIp = ip;
                    updateStatus("Đang kết nối Cam IP: " + ip + "...", false);

                    // 1. Kiểm tra nhanh Port 80 và gửi Handshake Novatek
                    boolean port80Open = isPortOpen(ip, 80, 400);
                    if (port80Open) {
                        sendVietMapHandshake(ip);
                    }

                    // Chế độ 1: Snapshot CGI nếu chọn FORCE_SNAPSHOT_CGI
                    if (currentMode == StreamMode.FORCE_SNAPSHOT_CGI) {
                        updateStatus("Luồng Snapshot CGI (" + ip + ")...", false);
                        streamSuccess = streamSnapshotPollingLoop(ip);
                        if (streamSuccess) break;
                    }

                    // Chế độ 2: TCP Socket Port 8192 (Novatek Live Stream)
                    boolean port8192Open = isPortOpen(ip, 8192, 500);
                    if (port8192Open && currentMode != StreamMode.FORCE_SNAPSHOT_CGI && isStreaming) {
                        activeStreamingPort = 8192;
                        updateStatus("Mở luồng Live MJPEG (Port 8192)...", false);
                        streamSuccess = streamMjpegSocketLoop(ip, 8192);
                        if (streamSuccess) break;
                    }

                    // Chế độ 3: Snapshot CGI Fallback (Cực kỳ ổn định trên TS-C1)
                    if (port80Open && isStreaming) {
                        updateStatus("Mở luồng Snapshot CGI (" + ip + ")...", false);
                        streamSuccess = streamSnapshotPollingLoop(ip);
                        if (streamSuccess) break;
                    }
                }

                if (!streamSuccess && isStreaming) {
                    updateStatus("Đang thử lại kết nối (" + activeCameraIp + ")...", false);
                }

                if (isStreaming && streamHandler != null) {
                    streamHandler.postDelayed(this, streamSuccess ? 50 : 1500);
                }
            }
        });
    }

    private void updateStatus(final String statusText, final boolean isConnected) {
        if (statusListener != null) {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (statusListener != null) {
                        statusListener.onStatusUpdated(statusText, isConnected, currentFps, totalFramesReceived);
                    }
                }
            });
        }
    }

    private void handleDecodedFrame(final Bitmap bmp, final String modeName) {
        if (bmp == null) return;
        totalFramesReceived++;
        frameCountInterval++;
        long now = System.currentTimeMillis();
        if (now - lastFpsUpdateTime >= 1000) {
            currentFps = frameCountInterval;
            frameCountInterval = 0;
            lastFpsUpdateTime = now;
            updateStatus("Đã nhận luồng Cam (" + modeName + " - " + currentFps + " FPS)", true);
        }

        // Bắn trực tiếp Frame lên UI Callback (MainActivity & FloatingService)
        if (callback != null) {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (callback != null && !bmp.isRecycled()) {
                        callback.onFrameCaptured(bmp);
                    }
                }
            });
        }

        // Truyền sang Detector để phân tích AI
        if (detector != null && !bmp.isRecycled()) {
            detector.processFrame(bmp);
        }
    }

    /**
     * Vòng lặp đọc nhị phân JPEG liên tục từ TCP Socket (Port 8192)
     */
    private boolean streamMjpegSocketLoop(String host, int port) {
        Socket socket = null;
        InputStream in = null;
        try {
            socket = createWifiBoundSocket();
            socket.setSoTimeout(3500);
            socket.connect(new InetSocketAddress(host, port), 2500);

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

            postLog("[Socket] Đã kết nối TCP Port " + port + ". Đang đọc byte frames...");

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
                                opts.inSampleSize = 1;
                                Bitmap bmp = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.length, opts);
                                if (bmp != null) {
                                    frameCount++;
                                    lastFrameTime = System.currentTimeMillis();
                                    handleDecodedFrame(bmp, "Live MJPEG");
                                }
                            }
                            frameBuffer.reset();
                        }
                    }
                    lastByte = currentByte;
                }

                // Gửi Keep-Alive cho TS-C1 mỗi 5 giây
                if (System.currentTimeMillis() - lastWakeUpAttemptTime > 5000) {
                    lastWakeUpAttemptTime = System.currentTimeMillis();
                    sendVietMapHandshake(host);
                }

                if (System.currentTimeMillis() - lastFrameTime > 4000) {
                    postLog("[Socket] Quá thời gian chờ frame trên port " + port);
                    break;
                }
            }
            return frameCount > 0;
        } catch (Exception e) {
            postLog("[Socket] Exception: " + e.getMessage());
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
     * Vòng lặp Snapshot HTTP CGI liên tục (Port 80) - Fallback chuẩn 100% cho TS-C1
     */
    private boolean streamSnapshotPollingLoop(String host) {
        String snapshotUrl = "http://" + host + "/?custom=1&cmd=2017";
        Bitmap testBmp = fetchHttpSnapshotFrame(snapshotUrl);
        if (testBmp == null) {
            snapshotUrl = "http://" + host + "/cgi-bin/hi3510/snap.cgi";
            testBmp = fetchHttpSnapshotFrame(snapshotUrl);
        }

        if (testBmp == null) {
            postLog("[Snapshot] Không phản hồi trên cổng CGI");
            return false;
        }

        postLog("[Snapshot] Khởi chạy luồng ảnh CGI liên tục (~12 FPS)...");
        handleDecodedFrame(testBmp, "Snapshot CGI");

        int successFrames = 1;
        long loopStart = System.currentTimeMillis();

        while (isStreaming && (System.currentTimeMillis() - loopStart < 20000)) {
            long frameStart = System.currentTimeMillis();
            Bitmap snap = fetchHttpSnapshotFrame(snapshotUrl);
            if (snap != null) {
                successFrames++;
                handleDecodedFrame(snap, "Snapshot CGI");
            } else {
                break;
            }

            if (System.currentTimeMillis() - lastWakeUpAttemptTime > 5000) {
                lastWakeUpAttemptTime = System.currentTimeMillis();
                sendVietMapHandshake(host);
            }

            long elapsed = System.currentTimeMillis() - frameStart;
            if (elapsed < 80) { // Duy trì ~12 FPS
                try {
                    Thread.sleep(80 - elapsed);
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

            connection.setConnectTimeout(900);
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
                opts.inSampleSize = 1;
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
        updateStatus("Đã dừng luồng Video", false);
        postLog("Luồng VietMap stream đã dừng.");
    }

    public boolean isStreaming() {
        return isStreaming;
    }

    public String getActiveCameraIp() {
        return activeCameraIp;
    }
}
