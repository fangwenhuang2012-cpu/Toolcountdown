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
        "Vietmap-TS-C1_423ced"
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

        // Kiểm tra xem đã kết nối sẵn chưa
        String currentSsid = getCurrentWifiSSID();
        if (isVietMapSSID(currentSsid)) {
            Log.d(TAG, "Đã kết nối sẵn tới: " + currentSsid);
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
                        Log.d(TAG, "Tìm thấy Camera VietMap Wi-Fi mạnh nhất: " + bestMatch.SSID + " (Tín hiệu: " + bestMatch.level + ")");
                        if (listener != null) {
                            listener.onVietMapCamFound(bestMatch.SSID, bestMatch.level);
                        }
                        found = true;
                    }
                    if (!found && attempts < 2) { // Try for 10 seconds
                        wifiManager.startScan();
                        attempts++;
                        handler.postDelayed(this, 5000);
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

    private boolean isVietMapSSID(String ssid) {
        if (ssid == null) return false;
        String ssidLower = ssid.toLowerCase();
        for (String keyword : VIETMAP_KEYWORDS) {
            if (ssidLower.contains(keyword.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    public void connectToVietMapCam(final String ssid, final String passwordParam) {
        if ("Camera VietMap".equals(ssid) || (ssid != null && ssid.contains("Chưa kết nối"))) {
            Log.d(TAG, "Không có SSID cụ thể, yêu cầu người dùng tự mở cài đặt...");
            try {
                android.content.Intent intent = new android.content.Intent(android.provider.Settings.ACTION_WIFI_SETTINGS);
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
                if (listener != null) listener.onError("Đã mở Cài đặt. Vui lòng tự chọn Wi-Fi Camera VietMap");
            } catch (Exception e) {
                Log.e(TAG, "Lỗi khi mở cài đặt Wi-Fi", e);
            }
            return;
        }

        final String password = (passwordParam == null || passwordParam.isEmpty()) ? "12345678" : passwordParam;

        Log.d(TAG, "Đang kết nối tới Wi-Fi Camera VietMap: " + ssid);

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
                    public void onAvailable(android.net.Network network) {
                        super.onAvailable(network);
                        Log.d(TAG, "Đã kết nối thành công qua NetworkSpecifier: " + ssid);
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            connectivityManager.bindProcessToNetwork(network);
                        }
                        
                        if (listener != null) {
                            listener.onConnectedToVietMapCam(ssid);
                        }
                        
                        android.content.Intent intent = new android.content.Intent("com.openclaw.countdown.WIFI_CONNECTED");
                        intent.putExtra("ssid", ssid);
                        intent.setPackage(context.getPackageName());
                        context.sendBroadcast(intent);
                    }

                    @Override
                    public void onUnavailable() {
                        super.onUnavailable();
                        Log.e(TAG, "Kết nối bị hủy hoặc thất bại qua NetworkSpecifier.");
                        if (listener != null) {
                            listener.onError("Lỗi kết nối hoặc đã hủy bỏ.");
                        }
                        android.content.Intent intent = new android.content.Intent("com.openclaw.countdown.WIFI_FAILED");
                        intent.setPackage(context.getPackageName());
                        context.sendBroadcast(intent);
                    }
                };

                connectivityManager.requestNetwork(request, networkCallback);
                if (listener != null) {
                    listener.onError("Vui lòng xác nhận kết nối trên màn hình...");
                }
            } catch (Exception e) {
                Log.e(TAG, "Lỗi khi kết nối Wi-Fi (Android 10+)", e);
                if (listener != null) listener.onError("Lỗi API kết nối: " + e.getMessage());
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
                    // fallback text if we can't get SSID due to permissions
                    return "Đã kết nối Wi-Fi Local (Có thể là Camera)";
                }
                
                // On Android Q+, if they connected to a Wi-Fi without internet, it might not be the "active" network
                android.net.Network[] allNetworks = cm.getAllNetworks();
                for (android.net.Network network : allNetworks) {
                    NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                    if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                        return "Đã kết nối Wi-Fi Local (Có thể là Camera)";
                    }
                }
            }
        } catch (Exception ignored) {}

        return "Chưa kết nối Wi-Fi Camera VietMap";
    }
}
