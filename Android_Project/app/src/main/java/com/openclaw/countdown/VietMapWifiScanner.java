package com.openclaw.countdown;

import android.content.Context;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSpecifier;
import android.net.NetworkRequest;
import android.net.NetworkCapabilities;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.util.Log;

import java.net.InetAddress;
import java.util.List;

public class VietMapWifiScanner {
    private static final String TAG = "VietMapWifiScanner";
    private static final String[] VIETMAP_KEYWORDS = {
        "VIETMAP", "VietMap", "vietmap", "KC01", "SPEEDMAP", "TS-2K", "C65", "PAPAGO", 
        "DVR", "70mai", "NAVIFLEX", "DASHCAM", "CAR", "VNAV", "WiFi"
    };

    public interface WifiScanListener {
        void onVietMapCamFound(String ssid, int signalLevel);
        void onConnectedToVietMapCam(String ssid);
        void onError(String errorMsg);
    }

    private final Context context;
    private final WifiManager wifiManager;
    private final WifiScanListener listener;

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
                    List<ScanResult> results = wifiManager.getScanResults();
                    boolean found = false;
                    if (results != null) {
                        for (ScanResult result : results) {
                            if (result.SSID != null && isVietMapSSID(result.SSID)) {
                                Log.d(TAG, "Tìm thấy Camera VietMap Wi-Fi: " + result.SSID);
                                if (listener != null) {
                                    listener.onVietMapCamFound(result.SSID, result.level);
                                }
                                found = true;
                                break; // Stop after finding one
                            }
                        }
                    }
                    if (!found && attempts < 2) { // Try for 10 seconds
                        wifiManager.startScan();
                        attempts++;
                        handler.postDelayed(this, 5000);
                    } else if (!found) {
                        Log.d(TAG, "Không tìm thấy Wi-Fi Camera VietMap sau nhiều lần quét");
                        // Tự động báo cho ứng dụng biết để mở giao diện yêu cầu kết nối
                        if (listener != null) {
                            listener.onVietMapCamFound("Camera VietMap", 0); // Giả lập để hiện popup đòi wifi
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Lỗi khi dò Wi-Fi Camera VietMap", e);
                }
            }
        };
        handler.post(scanRunnable);
    }

    private boolean isVietMapSSID(String ssid) {
        if (ssid == null) return false;
        for (String keyword : VIETMAP_KEYWORDS) {
            if (ssid.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    public void connectToVietMapCam(final String ssid, final String passwordParam) {
        if ("Camera VietMap".equals(ssid) || (ssid != null && ssid.contains("Chưa kết nối"))) {
            Log.d(TAG, "Không có SSID cụ thể, mở cài đặt Wi-Fi hệ thống...");
            try {
                android.content.Intent intent = new android.content.Intent(android.provider.Settings.ACTION_WIFI_SETTINGS);
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
                if (listener != null) listener.onError("Vui lòng tự chọn Wi-Fi VietMap trong Cài đặt");
            } catch (Exception e) {
                Log.e(TAG, "Lỗi khi mở cài đặt Wi-Fi", e);
            }
            return;
        }

        final String password = (passwordParam == null || passwordParam.isEmpty()) ? "12345678" : passwordParam;

        Log.d(TAG, "Đang kết nối tới Wi-Fi Camera VietMap: " + ssid);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                WifiNetworkSpecifier specifier = new WifiNetworkSpecifier.Builder()
                        .setSsid(ssid)
                        .setWpa2Passphrase(password)
                        .build();

                NetworkRequest request = new NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                        .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .setNetworkSpecifier(specifier)
                        .build();

                ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm != null) {
                    cm.requestNetwork(request, new ConnectivityManager.NetworkCallback() {
                        @Override
                        public void onAvailable(android.net.Network network) {
                            Log.d(TAG, "Đã kết nối Wi-Fi Camera VietMap thành công!");
                            // BIND PROCESS TO THIS NETWORK: 
                            // This ensures our app uses the Dashcam Wi-Fi for streaming,
                            // while allowing the rest of the Android OS (Google Maps, etc.) to keep using 4G/Cellular!
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                cm.bindProcessToNetwork(network);
                            } else {
                                ConnectivityManager.setProcessDefaultNetwork(network);
                            }
                            if (listener != null) {
                                listener.onConnectedToVietMapCam(ssid);
                            }
                        }
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "Lỗi khi kết nối Wi-Fi (Android 10+)", e);
            }
        } else {
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

    public String getCurrentWifiSSID() {
        if (wifiManager != null) {
            try {
                android.net.wifi.WifiInfo info = wifiManager.getConnectionInfo();
                if (info != null && info.getSSID() != null) {
                    String ssid = info.getSSID().replace("\"", "");
                    if (!ssid.equals("<unknown ssid>") && !ssid.isEmpty()) {
                        return ssid;
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Lỗi lấy Wi-Fi SSID hiện tại", e);
            }
        }

        // Check if Wi-Fi interface is connected
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
                if (activeNetwork != null && activeNetwork.getType() == ConnectivityManager.TYPE_WIFI && activeNetwork.isConnected()) {
                    return "Wi-Fi Camera VietMap (Đã nối local)";
                }
            }
        } catch (Exception ignored) {}

        return "Chưa kết nối Wi-Fi Camera VietMap";
    }
}
