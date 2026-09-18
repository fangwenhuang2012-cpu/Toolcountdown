package com.openclaw.countdown;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.DhcpInfo;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import java.util.Locale;

/**
 * VietMapWifiHelper
 * Hỗ trợ theo dõi, nhận diện và quản lý kết nối Wi-Fi tới Camera hành trình VietMap
 * (TS-C1, KC01, TS2K, C61, C65, SpeedMap, Papago, v.v.)
 */
public class VietMapWifiHelper {
    private static final String TAG = "VietMapWifiHelper";

    public static final String DEFAULT_CAM_IP = "192.168.1.254";

    private static final String[] KNOWN_CAM_SSID_PREFIXES = {
            "VIETMAP",
            "TS-C1",
            "TSC1",
            "TS2K",
            "TS-2K",
            "KC01",
            "C61",
            "C62",
            "C63",
            "C65",
            "SPEEDMAP",
            "R4A",
            "PAPAGO",
            "DASHCAM",
            "IDVR",
            "CARDV",
            "NOVATEK",
            "MSTAR"
    };

    public interface WifiStateListener {
        void onWifiStateChanged(boolean isWifiEnabled, String currentSsid, boolean isCameraSsid, String gatewayIp);
    }

    private final Context context;
    private final WifiManager wifiManager;
    private final ConnectivityManager connectivityManager;
    private WifiStateListener listener;
    private BroadcastReceiver wifiReceiver;
    private boolean isRegistered = false;

    public VietMapWifiHelper(Context context) {
        this.context = context.getApplicationContext();
        this.wifiManager = (WifiManager) this.context.getSystemService(Context.WIFI_SERVICE);
        this.connectivityManager = (ConnectivityManager) this.context.getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    public void setListener(WifiStateListener listener) {
        this.listener = listener;
    }

    public void startListening() {
        if (isRegistered) return;
        try {
            wifiReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent intent) {
                    notifyState();
                }
            };
            IntentFilter filter = new IntentFilter();
            filter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
            filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
            filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
            context.registerReceiver(wifiReceiver, filter);
            isRegistered = true;
            notifyState();
        } catch (Exception e) {
            Log.e(TAG, "Lỗi đăng ký WifiReceiver", e);
        }
    }

    public void stopListening() {
        if (!isRegistered || wifiReceiver == null) return;
        try {
            context.unregisterReceiver(wifiReceiver);
        } catch (Exception ignored) {}
        isRegistered = false;
        wifiReceiver = null;
    }

    public void notifyState() {
        if (listener == null) return;
        boolean enabled = isWifiEnabled();
        String ssid = getCurrentSsid();
        boolean isCam = isVietMapSsid(ssid);
        String gateway = getGatewayIp();
        listener.onWifiStateChanged(enabled, ssid, isCam, gateway);
    }

    public boolean isWifiEnabled() {
        if (wifiManager != null) {
            return wifiManager.isWifiEnabled();
        }
        return false;
    }

    public String getCurrentSsid() {
        try {
            if (wifiManager != null) {
                WifiInfo info = wifiManager.getConnectionInfo();
                if (info != null) {
                    String ssid = info.getSSID();
                    if (ssid != null) {
                        ssid = ssid.trim();
                        if (ssid.startsWith("\"") && ssid.endsWith("\"") && ssid.length() >= 2) {
                            ssid = ssid.substring(1, ssid.length() - 1);
                        }
                        if (!ssid.isEmpty() && !ssid.equals("<unknown ssid>") && !ssid.equals("0x")) {
                            return ssid;
                        }
                    }
                }
            }
        } catch (Exception ignored) {}

        // Fallback kiểm tra qua ConnectivityManager
        try {
            if (connectivityManager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                Network[] networks = connectivityManager.getAllNetworks();
                if (networks != null) {
                    for (Network network : networks) {
                        NetworkCapabilities caps = connectivityManager.getNetworkCapabilities(network);
                        if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                            return "Đã kết nối Wi-Fi";
                        }
                    }
                }
            }
        } catch (Exception ignored) {}

        return "Chưa kết nối Wi-Fi";
    }

    public boolean isVietMapSsid(String ssid) {
        if (ssid == null || ssid.trim().isEmpty()) return false;
        String s = ssid.trim().toUpperCase(Locale.ROOT);
        if (s.contains("UNKNOWN") || s.equals("0X") || s.startsWith("CHƯA") || s.startsWith("ĐANG")) {
            return false;
        }
        for (String prefix : KNOWN_CAM_SSID_PREFIXES) {
            if (s.contains(prefix)) {
                return true;
            }
        }
        // Nếu tên Wi-Fi hiển thị generic nhưng đã kết nối interface Wi-Fi
        return false;
    }

    public String getGatewayIp() {
        try {
            if (wifiManager != null) {
                DhcpInfo dhcp = wifiManager.getDhcpInfo();
                if (dhcp != null && dhcp.gateway != 0) {
                    return (dhcp.gateway & 0xFF) + "." +
                            ((dhcp.gateway >> 8) & 0xFF) + "." +
                            ((dhcp.gateway >> 16) & 0xFF) + "." +
                            ((dhcp.gateway >> 24) & 0xFF);
                }
                if (dhcp != null && dhcp.serverAddress != 0) {
                    return (dhcp.serverAddress & 0xFF) + "." +
                            ((dhcp.serverAddress >> 8) & 0xFF) + "." +
                            ((dhcp.serverAddress >> 16) & 0xFF) + "." +
                            ((dhcp.serverAddress >> 24) & 0xFF);
                }
            }
        } catch (Exception ignored) {}
        return DEFAULT_CAM_IP;
    }

    public void openWifiSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_WIFI_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "Lỗi mở trang cài đặt Wi-Fi", e);
        }
    }
}
