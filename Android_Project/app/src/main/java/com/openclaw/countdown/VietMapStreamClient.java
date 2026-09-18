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
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * VietMapStreamClient
 * Bộ điều khiển và kéo luồng Video trực tiếp từ Camera VietMap (TS-C1, KC01, TS2K, C61, C65, Papago...)
 * Hoạt động tương tự cơ chế của ứng dụng VIETMAP REC.
 * Hỗ trợ:
 * - Cơ chế Ghim Mạng (Dual-Network Binding): Bảo toàn kết nối SIM 4G cho Android Box.
 * - Chuỗi Handshake Novatek (cmd=3001, 2001, 2016) + Vòng lặp Keep-Alive 3 giây.
 * - 3 Chế độ Stream: TCP Socket 8192 (Độ trễ thấp), HTTP MJPEG Stream, Snapshot CGI Polling.
 * - Tự động dò IP Gateway (192.168.1.254, 192.168.1.1, ...).
 */
public class VietMapStreamClient {
    private static final String TAG = "VietMapStreamClient";

    public static final String DEFAULT_CAM_IP = "192.168.1.254";

    public enum StreamProtocol {
        AUTO("Tự động"),
        SOCKET_8192("TCP Socket 8192 (Novatek Live)"),
        HTTP_MJPEG("HTTP MJPEG Stream"),
        SNAPSHOT_CGI("HTTP Snapshot CGI Polling");

        private final String displayName;
        StreamProtocol(String displayName) {
            this.displayName = displayName;
        }
        public String getDisplayName() {
            return displayName;
        }
    }

    public interface FrameListener {
        void onFrameDecoded(Bitmap bitmap, int width, int height);
    }

    public interface StatusListener {
        void onStatusChanged(String statusText, boolean isLive, int fps, int totalFrames, String activeIp, String modeName);
    }

    public interface LogListener {
        void onLog(String logMessage);
    }

    private final Context context;
    private final Handler mainHandler;

    private FrameListener frameListener;
    private StatusListener statusListener;
    private LogListener logListener;

    private StreamProtocol selectedProtocol = StreamProtocol.AUTO;
    private volatile boolean isRunning = false;

    private HandlerThread workerThread;
    private Handler workerHandler;

    private Thread keepAliveThread;
    private volatile boolean isKeepAliveActive = false;

    private volatile Network wifiNetwork = null;
    private ConnectivityManager.NetworkCallback networkCallback = null;

    private String activeIp = DEFAULT_CAM_IP;
    private String currentActiveMode = "Chưa kết nối";
    private int totalFrames = 0;
    private int frameCountInterval = 0;
    private long lastFpsCalcTime = 0;
    private int currentFps = 0;

    public VietMapStreamClient(Context context) {
        this.context = context.getApplicationContext();
        this.mainHandler = new Handler(Looper.getMainLooper());
        bindWifiInterface();
    }

    public void setFrameListener(FrameListener listener) {
        this.frameListener = listener;
    }

    public void setStatusListener(StatusListener listener) {
        this.statusListener = listener;
    }

    public void setLogListener(LogListener listener) {
        this.logListener = listener;
    }

    public void setProtocol(StreamProtocol protocol) {
        this.selectedProtocol = protocol;
        postLog("Đã chọn giao thức luồng: " + protocol.getDisplayName());
    }

    public StreamProtocol getProtocol() {
        return selectedProtocol;
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

    private void updateStatus(final String text, final boolean isLive) {
        if (statusListener != null) {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (statusListener != null) {
                        statusListener.onStatusChanged(text, isLive, currentFps, totalFrames, activeIp, currentActiveMode);
                    }
                }
            });
        }
    }

    /**
     * Ghim các kết nối Socket & HTTP của Client vào card mạng Wi-Fi của Cam.
     * Giúp Android Box vừa xem được Cam qua Wi-Fi vừa giữ nguyên kết nối Internet qua 4G LTE.
     */
    public synchronized void bindWifiInterface() {
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return;

            Network[] networks = cm.getAllNetworks();
            if (networks != null) {
                for (Network net : networks) {
                    NetworkCapabilities caps = cm.getNetworkCapabilities(net);
                    if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                        wifiNetwork = net;
                        return;
                    }
                }
            }

            if (networkCallback == null) {
                NetworkRequest request = new NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                        .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build();

                networkCallback = new ConnectivityManager.NetworkCallback() {
                    @Override
                    public void onAvailable(Network network) {
                        wifiNetwork = network;
                        postLog("Ghim card mạng Wi-Fi thành công (" + network + ")");
                    }

                    @Override
                    public void onLost(Network network) {
                        if (wifiNetwork != null && wifiNetwork.equals(network)) {
                            wifiNetwork = null;
                            postLog("Mất kết nối card mạng Wi-Fi Cam!");
                        }
                    }
                };
                cm.requestNetwork(request, networkCallback);
            }
        } catch (Exception e) {
            postLog("Lỗi ghim card mạng Wi-Fi: " + e.getMessage());
        }
    }

    public List<String> discoverCandidateIps() {
        List<String> list = new ArrayList<>();
        try {
            WifiManager wm = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                DhcpInfo dhcp = wm.getDhcpInfo();
                if (dhcp != null) {
                    if (dhcp.gateway != 0) {
                        String gIp = formatIp(dhcp.gateway);
                        if (!"0.0.0.0".equals(gIp) && !list.contains(gIp)) list.add(gIp);
                    }
                    if (dhcp.serverAddress != 0) {
                        String sIp = formatIp(dhcp.serverAddress);
                        if (!"0.0.0.0".equals(sIp) && !list.contains(sIp)) list.add(sIp);
                    }
                }
            }
        } catch (Exception ignored) {}

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && wifiNetwork != null) {
                ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm != null) {
                    LinkProperties lp = cm.getLinkProperties(wifiNetwork);
                    if (lp != null && lp.getRoutes() != null) {
                        for (RouteInfo r : lp.getRoutes()) {
                            if (r.hasGateway() && r.getGateway() != null) {
                                String gw = r.getGateway().getHostAddress();
                                if (gw != null && !gw.equals("0.0.0.0") && !gw.contains(":") && !list.contains(gw)) {
                                    list.add(gw);
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {}

        if (!list.contains(DEFAULT_CAM_IP)) list.add(DEFAULT_CAM_IP);
        if (!list.contains("192.168.1.1")) list.add("192.168.1.1");
        if (!list.contains("192.168.0.1")) list.add("192.168.0.1");
        if (!list.contains("192.168.43.1")) list.add("192.168.43.1");
        if (!list.contains("192.168.2.1")) list.add("192.168.2.1");
        return list;
    }

    private String formatIp(int ip) {
        return (ip & 0xFF) + "." + ((ip >> 8) & 0xFF) + "." + ((ip >> 16) & 0xFF) + "." + ((ip >> 24) & 0xFF);
    }

    private Socket createWifiSocket() {
        try {
            bindWifiInterface();
            if (wifiNetwork != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                return wifiNetwork.getSocketFactory().createSocket();
            }
        } catch (Exception ignored) {}
        return new Socket();
    }

    private HttpURLConnection openWifiHttpConnection(String urlStr) {
        try {
            bindWifiInterface();
            URL url = new URL(urlStr);
            if (wifiNetwork != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                return (HttpURLConnection) wifiNetwork.openConnection(url);
            } else {
                return (HttpURLConnection) url.openConnection();
            }
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Gửi chuỗi lệnh Handshake của Camera VietMap Novatek
     */
    public boolean performNovatekHandshake(String ip) {
        boolean ok = false;
        try {
            postLog("[Handshake] Gửi lệnh khởi động tới " + ip + "...");

            // Lệnh 1: cmd=3001 (Heartbeat / Connect)
            HttpURLConnection conn1 = openWifiHttpConnection("http://" + ip + "/?custom=1&cmd=3001");
            if (conn1 != null) {
                conn1.setConnectTimeout(1500);
                conn1.setReadTimeout(1500);
                conn1.setRequestMethod("GET");
                conn1.setRequestProperty("User-Agent", "VIETMAP REC/2.0");
                int resp1 = conn1.getResponseCode();
                conn1.disconnect();
                if (resp1 == 200) {
                    ok = true;
                    postLog("[Handshake] cmd=3001: OK (HTTP 200)");
                }
            }

            // Lệnh 2: cmd=2001&par=1 (Movie Live View Mode)
            HttpURLConnection conn2 = openWifiHttpConnection("http://" + ip + "/?custom=1&cmd=2001&par=1");
            if (conn2 != null) {
                conn2.setConnectTimeout(1500);
                conn2.setReadTimeout(1500);
                conn2.setRequestMethod("GET");
                conn2.setRequestProperty("User-Agent", "VIETMAP REC/2.0");
                int resp2 = conn2.getResponseCode();
                conn2.disconnect();
                postLog("[Handshake] cmd=2001: HTTP " + resp2);
            }

            // Lệnh 3: cmd=2016&par=1 (Bật Sub-Stream)
            HttpURLConnection conn3 = openWifiHttpConnection("http://" + ip + "/?custom=1&cmd=2016&par=1");
            if (conn3 != null) {
                conn3.setConnectTimeout(1200);
                conn3.setReadTimeout(1200);
                conn3.setRequestMethod("GET");
                conn3.setRequestProperty("User-Agent", "VIETMAP REC/2.0");
                conn3.getResponseCode();
                conn3.disconnect();
            }

            Thread.sleep(300);
        } catch (Exception e) {
            postLog("[Handshake] Ghi chú: " + e.getMessage());
        }
        return ok;
    }

    private void startKeepAlive(final String targetIp) {
        stopKeepAlive();
        isKeepAliveActive = true;
        keepAliveThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (isKeepAliveActive && isRunning) {
                    try {
                        Thread.sleep(3000);
                        if (!isKeepAliveActive || !isRunning) break;
                        HttpURLConnection conn = openWifiHttpConnection("http://" + targetIp + "/?custom=1&cmd=3001");
                        if (conn != null) {
                            conn.setConnectTimeout(1200);
                            conn.setReadTimeout(1200);
                            conn.setRequestMethod("GET");
                            conn.setRequestProperty("User-Agent", "VIETMAP REC/2.0");
                            conn.getResponseCode();
                            conn.disconnect();
                        }
                    } catch (Exception ignored) {}
                }
            }
        }, "VietMapKeepAliveThread");
        keepAliveThread.setDaemon(true);
        keepAliveThread.start();
    }

    private void stopKeepAlive() {
        isKeepAliveActive = false;
        if (keepAliveThread != null) {
            keepAliveThread.interrupt();
            keepAliveThread = null;
        }
    }

    private boolean isPortAvailable(String host, int port, int timeoutMs) {
        Socket s = null;
        try {
            s = createWifiSocket();
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

    public void startStream() {
        if (isRunning) return;
        isRunning = true;
        bindWifiInterface();

        workerThread = new HandlerThread("VietMapStreamClientWorker");
        workerThread.start();
        workerHandler = new Handler(workerThread.getLooper());

        postLog("Khởi động tiến trình xem Video trực tiếp...");

        workerHandler.post(new Runnable() {
            @Override
            public void run() {
                if (!isRunning) return;

                bindWifiInterface();
                List<String> candidateIps = discoverCandidateIps();
                boolean streamWorked = false;

                for (String ip : candidateIps) {
                    if (!isRunning) break;
                    activeIp = ip;
                    updateStatus("Đang kiểm tra Camera tại " + ip + "...", false);

                    // 1. Gửi Handshake
                    performNovatekHandshake(ip);
                    startKeepAlive(ip);

                    // 2. Chạy giao thức được cấu hình
                    if (selectedProtocol == StreamProtocol.SOCKET_8192) {
                        currentActiveMode = "TCP Socket 8192";
                        updateStatus("Mở luồng TCP Socket 8192 (" + ip + ")...", false);
                        streamWorked = runSocket8192Loop(ip);
                    } else if (selectedProtocol == StreamProtocol.HTTP_MJPEG) {
                        currentActiveMode = "HTTP MJPEG";
                        updateStatus("Mở luồng HTTP MJPEG (" + ip + ")...", false);
                        streamWorked = runHttpMjpegLoop(ip);
                    } else if (selectedProtocol == StreamProtocol.SNAPSHOT_CGI) {
                        currentActiveMode = "Snapshot CGI";
                        updateStatus("Mở luồng Snapshot CGI (" + ip + ")...", false);
                        streamWorked = runSnapshotPollingLoop(ip);
                    } else {
                        // AUTO MODE
                        // Ưu tiên 1: TCP Port 8192
                        if (isPortAvailable(ip, 8192, 1000)) {
                            currentActiveMode = "TCP Socket 8192";
                            updateStatus("Mở luồng TCP Socket 8192 (" + ip + ")...", false);
                            streamWorked = runSocket8192Loop(ip);
                            if (streamWorked) break;
                        }

                        // Ưu tiên 2: Snapshot CGI Polling
                        if (isRunning) {
                            currentActiveMode = "Snapshot CGI";
                            updateStatus("Mở luồng Snapshot CGI (" + ip + ")...", false);
                            streamWorked = runSnapshotPollingLoop(ip);
                            if (streamWorked) break;
                        }

                        // Ưu tiên 3: HTTP MJPEG
                        if (isRunning) {
                            currentActiveMode = "HTTP MJPEG";
                            updateStatus("Mở luồng HTTP MJPEG (" + ip + ")...", false);
                            streamWorked = runHttpMjpegLoop(ip);
                            if (streamWorked) break;
                        }
                    }

                    if (streamWorked) break;
                }

                if (!streamWorked && isRunning) {
                    updateStatus("Đang chờ tín hiệu Camera (" + activeIp + ")...", false);
                }

                if (isRunning && workerHandler != null) {
                    workerHandler.postDelayed(this, streamWorked ? 100 : 1500);
                }
            }
        });
    }

    private void dispatchFrame(final Bitmap bmp) {
        if (bmp == null || bmp.isRecycled()) return;
        totalFrames++;
        frameCountInterval++;
        long now = System.currentTimeMillis();
        if (now - lastFpsCalcTime >= 1000) {
            currentFps = frameCountInterval;
            frameCountInterval = 0;
            lastFpsCalcTime = now;
            updateStatus("Đang xem trực tiếp (" + currentFps + " FPS)", true);
        }

        if (frameListener != null) {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (frameListener != null && !bmp.isRecycled()) {
                        frameListener.onFrameDecoded(bmp, bmp.getWidth(), bmp.getHeight());
                    }
                }
            });
        }
    }

    /**
     * Vòng lặp giải mã TCP Socket MJPEG chuẩn Novatek (Port 8192)
     */
    private boolean runSocket8192Loop(String host) {
        Socket socket = null;
        InputStream in = null;
        try {
            socket = createWifiSocket();
            socket.setSoTimeout(4000);
            socket.connect(new InetSocketAddress(host, 8192), 2000);

            OutputStream out = socket.getOutputStream();
            String getReq = "GET / HTTP/1.1\r\nHost: " + host + ":8192\r\nUser-Agent: VIETMAP REC/2.0\r\nAccept: */*\r\nConnection: keep-alive\r\n\r\n";
            out.write(getReq.getBytes());
            out.flush();

            in = new BufferedInputStream(socket.getInputStream(), 65536);
            ByteArrayOutputStream frameBuffer = new ByteArrayOutputStream(65536);
            byte[] readBuffer = new byte[8192];
            int bytesRead;
            boolean inJpeg = false;
            int lastByte = -1;
            long lastFrameTime = System.currentTimeMillis();
            int receivedInThisSession = 0;

            postLog("[Socket 8192] Đã kết nối TCP tới " + host + ":8192");

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            opts.inSampleSize = 1;

            while (isRunning && (bytesRead = in.read(readBuffer)) != -1) {
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
                            byte[] jpegData = frameBuffer.toByteArray();
                            if (jpegData.length > 1024) {
                                Bitmap bmp = BitmapFactory.decodeByteArray(jpegData, 0, jpegData.length, opts);
                                if (bmp != null) {
                                    receivedInThisSession++;
                                    lastFrameTime = System.currentTimeMillis();
                                    dispatchFrame(bmp);
                                }
                            }
                            frameBuffer.reset();
                        }
                    }
                    lastByte = currentByte;
                }

                if (System.currentTimeMillis() - lastFrameTime > 4000) {
                    postLog("[Socket 8192] Quá thời gian chờ frame mới");
                    break;
                }
            }
            return receivedInThisSession > 0;
        } catch (Exception e) {
            postLog("[Socket 8192] Ghi chú: " + e.getMessage());
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
     * Vòng lặp Snapshot CGI Polling (?custom=1&cmd=2017)
     */
    private boolean runSnapshotPollingLoop(String host) {
        String[] snapshotUrls = new String[]{
                "http://" + host + "/?custom=1&cmd=2017",
                "http://" + host + "/?custom=1&cmd=1001",
                "http://" + host + "/cgi-bin/hi3510/snap.cgi",
                "http://" + host + "/snapshot.jpg",
                "http://" + host + "/snap.jpg"
        };

        String workingUrl = null;
        for (String u : snapshotUrls) {
            Bitmap testBmp = fetchSingleSnapshot(u);
            if (testBmp != null) {
                workingUrl = u;
                dispatchFrame(testBmp);
                break;
            }
        }

        if (workingUrl == null) {
            postLog("[Snapshot CGI] Không phản hồi trên cổng CGI (" + host + ")");
            return false;
        }

        postLog("[Snapshot CGI] Tìm thấy URL: " + workingUrl);
        int successCount = 1;
        long loopStartTime = System.currentTimeMillis();

        while (isRunning && (System.currentTimeMillis() - loopStartTime < 25000)) {
            long frameStart = System.currentTimeMillis();
            Bitmap bmp = fetchSingleSnapshot(workingUrl);
            if (bmp != null) {
                successCount++;
                dispatchFrame(bmp);
            } else {
                break;
            }

            long elapsed = System.currentTimeMillis() - frameStart;
            if (elapsed < 60) { // ~16 FPS
                try {
                    Thread.sleep(60 - elapsed);
                } catch (InterruptedException ignored) {}
            }
        }
        return successCount > 1;
    }

    /**
     * Vòng lặp HTTP MJPEG Stream
     */
    private boolean runHttpMjpegLoop(String host) {
        String[] mjpegUrls = new String[]{
                "http://" + host + ":8192/",
                "http://" + host + "/live.mjpg",
                "http://" + host + "/video.cgi",
                "http://" + host + "/?custom=1&cmd=2016"
        };

        for (String mjpegUrl : mjpegUrls) {
            HttpURLConnection conn = null;
            InputStream in = null;
            try {
                conn = openWifiHttpConnection(mjpegUrl);
                if (conn == null) continue;
                conn.setConnectTimeout(2000);
                conn.setReadTimeout(3500);
                conn.setRequestProperty("User-Agent", "VIETMAP REC/2.0");
                conn.connect();

                if (conn.getResponseCode() == 200) {
                    in = new BufferedInputStream(conn.getInputStream(), 65536);
                    ByteArrayOutputStream frameBuffer = new ByteArrayOutputStream(65536);
                    byte[] readBuffer = new byte[8192];
                    int bytesRead;
                    boolean inJpeg = false;
                    int lastByte = -1;
                    int received = 0;
                    long lastFrameTime = System.currentTimeMillis();

                    BitmapFactory.Options opts = new BitmapFactory.Options();
                    opts.inPreferredConfig = Bitmap.Config.RGB_565;

                    while (isRunning && (bytesRead = in.read(readBuffer)) != -1) {
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
                                    byte[] jpegData = frameBuffer.toByteArray();
                                    if (jpegData.length > 1024) {
                                        Bitmap bmp = BitmapFactory.decodeByteArray(jpegData, 0, jpegData.length, opts);
                                        if (bmp != null) {
                                            received++;
                                            lastFrameTime = System.currentTimeMillis();
                                            dispatchFrame(bmp);
                                        }
                                    }
                                    frameBuffer.reset();
                                }
                            }
                            lastByte = currentByte;
                        }
                        if (System.currentTimeMillis() - lastFrameTime > 4000) break;
                    }
                    if (received > 0) return true;
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
        }
        return false;
    }

    private Bitmap fetchSingleSnapshot(String urlStr) {
        HttpURLConnection conn = null;
        InputStream in = null;
        ByteArrayOutputStream buffer = null;
        try {
            conn = openWifiHttpConnection(urlStr);
            if (conn == null) return null;
            conn.setConnectTimeout(1500);
            conn.setReadTimeout(1800);
            conn.setUseCaches(false);
            conn.setRequestProperty("User-Agent", "VIETMAP REC/2.0");
            conn.connect();

            if (conn.getResponseCode() == 200) {
                in = new BufferedInputStream(conn.getInputStream(), 32768);
                buffer = new ByteArrayOutputStream(32768);
                byte[] temp = new byte[8192];
                int read;
                while ((read = in.read(temp)) != -1) {
                    buffer.write(temp, 0, read);
                }
                byte[] data = buffer.toByteArray();
                if (data.length > 500) {
                    BitmapFactory.Options opts = new BitmapFactory.Options();
                    opts.inPreferredConfig = Bitmap.Config.RGB_565;
                    return BitmapFactory.decodeByteArray(data, 0, data.length, opts);
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (buffer != null) {
                try { buffer.close(); } catch (Exception ignored) {}
            }
            if (in != null) {
                try { in.close(); } catch (Exception ignored) {}
            }
            if (conn != null) {
                conn.disconnect();
            }
        }
        return null;
    }

    public void triggerManualHandshake() {
        if (workerHandler != null) {
            workerHandler.post(new Runnable() {
                @Override
                public void run() {
                    performNovatekHandshake(activeIp);
                }
            });
        }
    }

    public void stopStream() {
        if (!isRunning) return;
        isRunning = false;
        stopKeepAlive();

        if (workerHandler != null) {
            workerHandler.removeCallbacksAndMessages(null);
        }
        if (workerThread != null) {
            workerThread.quitSafely();
            workerThread = null;
        }

        updateStatus("Đã dừng luồng Video", false);
        postLog("Đã dừng kết nối Camera.");
    }

    public boolean isRunning() {
        return isRunning;
    }

    public String getActiveIp() {
        return activeIp;
    }
}
