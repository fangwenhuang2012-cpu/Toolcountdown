package com.openclaw.countdown;

import android.content.Context;
import android.content.IntentFilter;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSpecifier;
import android.net.NetworkRequest;
import android.net.NetworkCapabilities;
import android.net.ConnectivityManager;
import android.os.Build;
import android.os.PatternMatcher;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

public class VietMapWifiScanner {
    private static final String TAG = "VietMapWifiScanner";
    private static final String[] VIETMAP_KEYWORDS = {"VIETMAP", "VietMap", "vietmap", "KC01", "SPEEDMAP", "TS-2K", "C65", "PAPAGO"};

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

        if (!wifiManager.isWifiEnabled()) {
            wifiManager.setWifiEnabled(true);
        }

        try {
            List<ScanResult> results = wifiManager.getScanResults();
            if (results != null) {
                for (ScanResult result : results) {
                    if (result.SSID != null && isVietMapSSID(result.SSID)) {
                        Log.d(TAG, "Tìm thấy Camera VietMap Wi-Fi: " + result.SSID);
                        if (listener != null) {
                            listener.onVietMapCamFound(result.SSID, result.level);
                        }
                    }
                }
            }
            wifiManager.startScan();
        } catch (Exception e) {
            Log.e(TAG, "Lỗi khi dò Wi-Fi Camera VietMap", e);
        }
    }

    private boolean isVietMapSSID(String ssid) {
        for (String keyword : VIETMAP_KEYWORDS) {
            if (ssid.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    public void connectToVietMapCam(String ssid, String password) {
        if (password == null || password.isEmpty()) {
            password = "12345678"; // Mật khẩu mặc định phổ biến của camera VietMap
        }

        Log.d(TAG, "Đang tự động kết nối tới Wi-Fi Camera VietMap: " + ssid);

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
                            if (listener != null) {
                                listener.onConnectedToVietMapCam(ssid);
                            }
                        }
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "Lỗi khi tự động kết nối Wi-Fi (Android 10+)", e);
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
                Log.e(TAG, "Lỗi khi tự động kết nối Wi-Fi (Android 9 trở xuống)", e);
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
        return "Chưa kết nối Wi-Fi Camera VietMap";
    }
}
