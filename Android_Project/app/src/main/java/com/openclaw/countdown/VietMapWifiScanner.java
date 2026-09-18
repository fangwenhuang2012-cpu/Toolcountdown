package com.openclaw.countdown;

import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkRequest;
import android.net.TransportInfo;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSpecifier;
import android.net.wifi.WifiNetworkSuggestion;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class VietMapWifiScanner {
    private static final String TAG = "VietMapWifiScanner";
    private static final String[] VIETMAP_KEYWORDS = {
        "vietmap",
        "ts-c1",
        "ts2k",
        "ts-2k",
        "kc01",
        "c61",
        "c62",
        "c63",
        "c65",
        "speedmap",
        "r4a",
        "papago",
        "dashcam",
        "idvr"
    };

    public interface WifiScanListener {
        void onVietMapCamFound(String ssid, int signalLevel);
        void onConnectedToVietMapCam(String ssid);
        void onError(String errorMsg);
    }

    private final Context context;
    private final WifiManager wifiManager;
    private final WifiScanListener listener;
    private ConnectivityManager.NetworkCallback networkCallback;

    public VietMapWifiScanner(Context context, WifiScanListener listener) {
        this.context = context;
        this.wifiManager = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        this.listener = listener;
    }

    public void scanForVietMapCam() {
        if (wifiManager == null) {
            if (listener != null) listener.onError("Wi-Fi Manager không khả dụng");
            return;
        }

        // Kiểm tra xem đã kết nối sẵn tới Wi-Fi camera hợp lệ chưa
        String currentSsid = getCurrentWifiSSID();
        if (isVietMapSSID(currentSsid) || isWifiInterfaceActive()) {
            Log.d(TAG, "Đã kết nối sẵn tới Wi-Fi Camera: " + currentSsid);
            if (listener != null) {
                listener.onConnectedToVietMapCam(currentSsid);
            }
            return;
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            try {
                if (!wifiManager.isWifiEnabled()) {
                    wifiManager.setWifiEnabled(true);
                }
            } catch (Exception ignored) {}
        }

        final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
        Runnable scanRunnable = new Runnable() {
            int attempts = 0;
            @Override
            public void run() {
                try {
                    // Double check before scanning
                    String checkSsid = getCurrentWifiSSID();
                    if (isVietMapSSID(checkSsid)) {
                        if (listener != null) listener.onConnectedToVietMapCam(checkSsid);
                        return;
                    }
                    List<ScanResult> results = wifiManager.getScanResults();
                    boolean found = false;
                    ScanResult bestMatch = null;
                    if (results != null) {
                        for (ScanResult result : results) {
                            if (result.SSID != null && isVietMapSSID(result.SSID)) {
                                if (bestMatch == null || result.level > bestMatch.level) {
                                    bestMatch = result;
                                }
                            }
                        }
                    }
                    if (bestMatch != null) {
                        Log.d(TAG, "Tìm thấy Camera VietMap Wi-Fi mạnh nhất: " + bestMatch.SSID + " (Tín hiệu: " + bestMatch.level + " dBm)");
                        if (listener != null) {
                            listener.onVietMapCamFound(bestMatch.SSID, bestMatch.level);
                        }
                        found = true;
                    }
                    if (!found && attempts < 4) { // Dò nhiều đợt để bắt kịp sóng cam
                        wifiManager.startScan();
                        attempts++;
                        handler.postDelayed(this, 4000);
                    } else if (!found) {
                        Log.d(TAG, "Không tìm thấy Wi-Fi Camera VietMap sau nhiều lần quét");
                        if (listener != null) {
                            listener.onError("Chưa tìm thấy Wi-Fi Camera VietMap");
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Lỗi khi dò Wi-Fi Camera VietMap", e);
                }
            }
        };
        handler.post(scanRunnable);
    }

    public boolean isWifiInterfaceActive() {
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                Network[] networks = cm.getAllNetworks();
                if (networks != null) {
                    for (Network net : networks) {
                        NetworkCapabilities caps = cm.getNetworkCapabilities(net);
                        if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                            return true;
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    public boolean isVietMapSSID(String ssid) {
        if (ssid == null || ssid.trim().isEmpty()) return false;
        String s = ssid.trim();
        if (s.equals("<unknown ssid>") || s.equals("0x") || s.equals("\"\"") || s.equals("Chưa kết nối Wi-Fi Camera VietMap")) {
            return false;
        }
        if (s.equals("Đã kết nối Wi-Fi Local (Camera VietMap)")) {
            return true;
        }
        String ssidLower = s.toLowerCase();
        if (ssidLower.startsWith("chưa") || ssidLower.startsWith("đang") || ssidLower.startsWith("lỗi") || 
            ssidLower.startsWith("hủy") || ssidLower.startsWith("phát hiện") || ssidLower.startsWith("vui lòng")) {
            return false;
        }
        for (String keyword : VIETMAP_KEYWORDS) {
            if (ssidLower.contains(keyword.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    public void openWifiSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_WIFI_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            if (listener != null) listener.onError("Đã mở Cài đặt Wi-Fi. Vui lòng chọn Wi-Fi Camera VietMap");
        } catch (Exception e) {
            Log.e(TAG, "Lỗi khi mở cài đặt Wi-Fi", e);
        }
    }

    public void connectToVietMapCam(final String ssid, final String passwordParam) {
        if ("Camera VietMap".equals(ssid) || (ssid != null && ssid.contains("Chưa kết nối"))) {
            openWifiSettings();
            return;
        }

        final String password = (passwordParam == null || passwordParam.isEmpty()) ? "12345678" : passwordParam;

        Log.d(TAG, "Đang kết nối tới Wi-Fi Camera VietMap: " + ssid);

        // Sử dụng NetworkSpecifier trên Android 10+ (API 29+) để kết nối riêng kênh Cam cục bộ
        // Không dùng WifiNetworkSuggestion và KHÔNG gọi bindProcessToNetwork để tránh làm Android Box mất mạng SIM 4G
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                WifiNetworkSpecifier.Builder builder = new WifiNetworkSpecifier.Builder();
                builder.setSsid(ssid);
                builder.setWpa2Passphrase(password);

                WifiNetworkSpecifier specifier = builder.build();

                NetworkRequest request = new NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                        .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .setNetworkSpecifier(specifier)
                        .build();

                final ConnectivityManager connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
                
                if (networkCallback != null) {
                    try {
                        connectivityManager.unregisterNetworkCallback(networkCallback);
                    } catch (Exception ignored) {}
                }

                networkCallback = new ConnectivityManager.NetworkCallback() {
                    @Override
                    public void onAvailable(Network network) {
                        super.onAvailable(network);
                        Log.d(TAG, "Đã kết nối thành công qua NetworkSpecifier (Kênh Cam cục bộ): " + ssid);
                        
                        if (listener != null) {
                            listener.onConnectedToVietMapCam(ssid);
                        }
                        
                        Intent intent = new Intent("com.openclaw.countdown.WIFI_CONNECTED");
                        intent.putExtra("ssid", ssid);
                        intent.setPackage(context.getPackageName());
                        context.sendBroadcast(intent);
                    }

                    @Override
                    public void onUnavailable() {
                        super.onUnavailable();
                        Log.e(TAG, "NetworkSpecifier onUnavailable. Chuyển sang chế độ kết nối cấu hình...");
                        if (listener != null) {
                            listener.onError("Vui lòng chạm chọn Wi-Fi " + ssid + " trong cài đặt");
                        }
                        Intent intent = new Intent("com.openclaw.countdown.WIFI_FAILED");
                        intent.setPackage(context.getPackageName());
                        context.sendBroadcast(intent);
                    }
                };

                connectivityManager.requestNetwork(request, networkCallback);
                if (listener != null) {
                    listener.onError("Đang kết nối " + ssid + "...");
                }
            } catch (Exception e) {
                Log.e(TAG, "Lỗi khi kết nối Wi-Fi (Android 10+)", e);
                if (listener != null) listener.onError("Lỗi API kết nối: " + e.getMessage());
            }
        } else {
            // Android 9 trở xuống: Kết nối trực tiếp qua WifiConfiguration
            try {
                WifiConfiguration conf = new WifiConfiguration();
                conf.SSID = "\"" + ssid + "\"";
                conf.preSharedKey = "\"" + password + "\"";

                int netId = wifiManager.addNetwork(conf);
                wifiManager.disconnect();
                wifiManager.enableNetwork(netId, true);
                wifiManager.reconnect();

                if (listener != null) {
                    listener.onConnectedToVietMapCam(ssid);
                }
            } catch (Exception e) {
                Log.e(TAG, "Lỗi khi kết nối Wi-Fi (Android 9 trở xuống)", e);
            }
        }
    }

    public void disconnectFromVietMapCam() {
        try {
            if (networkCallback != null) {
                ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm != null) {
                    try {
                        cm.unregisterNetworkCallback(networkCallback);
                    } catch (Exception ignored) {}
                }
                networkCallback = null;
            }
            if (listener != null) {
                listener.onError("Đã ngắt kết nối Wi-Fi Cam");
            }
        } catch (Exception e) {
            Log.e(TAG, "Lỗi khi ngắt kết nối Wi-Fi Cam", e);
        }
    }

    public String getCurrentWifiSSID() {
        // 1. Thử lấy SSID từ NetworkCapabilities trên Android 10+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm != null) {
                    Network[] networks = cm.getAllNetworks();
                    if (networks != null) {
                        for (Network net : networks) {
                            NetworkCapabilities caps = cm.getNetworkCapabilities(net);
                            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                                TransportInfo tInfo = caps.getTransportInfo();
                                if (tInfo instanceof WifiInfo) {
                                    String s = ((WifiInfo) tInfo).getSSID();
                                    if (s != null) {
                                        s = s.replace("\"", "").trim();
                                        if (!s.equals("<unknown ssid>") && !s.equals("0x") && !s.isEmpty()) {
                                            return s;
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        // 2. Thử lấy qua WifiManager
        if (wifiManager != null) {
            try {
                WifiInfo info = wifiManager.getConnectionInfo();
                if (info != null && info.getSSID() != null) {
                    String ssid = info.getSSID().replace("\"", "").trim();
                    if (!ssid.equals("<unknown ssid>") && !ssid.equals("0x") && !ssid.isEmpty()) {
                        return ssid;
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Lỗi lấy Wi-Fi SSID hiện tại", e);
            }
        }

        // 3. Kiểm tra xem card Wi-Fi có đang kết nối nội bộ không
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
                if (activeNetwork != null && activeNetwork.getType() == ConnectivityManager.TYPE_WIFI && activeNetwork.isConnected()) {
                    return "Đã kết nối Wi-Fi Local (Camera VietMap)";
                }
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    Network[] allNetworks = cm.getAllNetworks();
                    if (allNetworks != null) {
                        for (Network network : allNetworks) {
                            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                                return "Đã kết nối Wi-Fi Local (Camera VietMap)";
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {}

        return "Chưa kết nối Wi-Fi Camera VietMap";
    }
}
