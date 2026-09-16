package com.openclaw.countdown;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * MainActivity
 * Trình xem Video Live View trực tiếp từ Camera VietMap TS-C1 (Tương tự app VIETMAP REC)
 * Hiển thị hình ảnh thời gian thực, bảng chẩn đoán mạng, FPS và các nút điều khiển luồng.
 */
public class MainActivity extends Activity {
    private static final int CODE_DRAW_OVER_OTHER_APP_PERMISSION = 2084;
    private static final int CODE_LOCATION_PERMISSION = 2085;

    private ImageView ivCameraLive;
    private LinearLayout layoutConnecting;
    private TextView tvWifiStatus;
    private TextView tvFpsBadge;
    private TextView tvStreamModeBadge;
    private TextView tvResolutionStats;
    private TextView tvFrameCountStats;
    private TextView tvLogs;
    private ScrollView svLogs;

    private Button btnHandshake;
    private Button btnSwitchEngine;
    private Button btnSnapshotTest;
    private Button btnStartFloating;

    private VietMapStreamReader streamReader;
    private VietMapWifiScanner wifiScanner;
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bindViews();
        setupListeners();
        checkPermissionsAndStart();
    }

    private void bindViews() {
        ivCameraLive = findViewById(R.id.ivCameraLive);
        layoutConnecting = findViewById(R.id.layoutConnecting);
        tvWifiStatus = findViewById(R.id.tvWifiStatus);
        tvFpsBadge = findViewById(R.id.tvFpsBadge);
        tvStreamModeBadge = findViewById(R.id.tvStreamModeBadge);
        tvResolutionStats = findViewById(R.id.tvResolutionStats);
        tvFrameCountStats = findViewById(R.id.tvFrameCountStats);
        tvLogs = findViewById(R.id.tvLogs);
        svLogs = findViewById(R.id.svLogs);

        btnHandshake = findViewById(R.id.btnHandshake);
        btnSwitchEngine = findViewById(R.id.btnSwitchEngine);
        btnSnapshotTest = findViewById(R.id.btnSnapshotTest);
        btnStartFloating = findViewById(R.id.btnStartFloating);
    }

    private void appendLog(String log) {
        String timestamp = timeFormat.format(new Date());
        String line = "[" + timestamp + "] " + log + "\n";
        tvLogs.append(line);
        svLogs.post(new Runnable() {
            @Override
            public void run() {
                svLogs.fullScroll(View.FOCUS_DOWN);
            }
        });
    }

    private void setupListeners() {
        // Nút 1: Gửi lệnh Handshake Novatek kích hoạt Cam TS-C1
        btnHandshake.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                appendLog("Bấm kích hoạt Handshake TS-C1...");
                if (streamReader != null) {
                    streamReader.triggerManualHandshake();
                }
            }
        });

        // Nút 2: Chuyển đổi giữa Socket MJPEG và Snapshot CGI
        btnSwitchEngine.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (streamReader == null) return;
                VietMapStreamReader.StreamMode current = streamReader.getStreamMode();
                if (current == VietMapStreamReader.StreamMode.AUTO) {
                    streamReader.setStreamMode(VietMapStreamReader.StreamMode.FORCE_SNAPSHOT_CGI);
                    tvStreamModeBadge.setText("Mode: Ép Snapshot CGI");
                    btnSwitchEngine.setText("🔄 Mode: Snapshot");
                } else if (current == VietMapStreamReader.StreamMode.FORCE_SNAPSHOT_CGI) {
                    streamReader.setStreamMode(VietMapStreamReader.StreamMode.FORCE_SOCKET_8192);
                    tvStreamModeBadge.setText("Mode: Ép Socket 8192");
                    btnSwitchEngine.setText("🔄 Mode: Socket 8192");
                } else {
                    streamReader.setStreamMode(VietMapStreamReader.StreamMode.AUTO);
                    tvStreamModeBadge.setText("Mode: Tự động");
                    btnSwitchEngine.setText("🔄 Mode: Tự động");
                }
            }
        });

        // Nút 3: Chụp thử 1 ảnh Snapshot
        btnSnapshotTest.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                appendLog("Đang gửi yêu cầu test 1 ảnh Snapshot (?custom=1&cmd=2017)...");
                if (streamReader != null) {
                    streamReader.fetchSingleTestSnapshot();
                }
            }
        });

        // Nút 4: Mở Widget nổi HUD
        btnStartFloating.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(MainActivity.this)) {
                    Toast.makeText(MainActivity.this, "Vui lòng cấp quyền Vẽ lên ứng dụng khác (Overlay)", Toast.LENGTH_LONG).show();
                    Intent overlayIntent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + getPackageName()));
                    startActivityForResult(overlayIntent, CODE_DRAW_OVER_OTHER_APP_PERMISSION);
                    return;
                }
                startFloatingHUDService();
            }
        });
    }

    private void checkPermissionsAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                appendLog("Yêu cầu quyền Vị trí (GPS & Wi-Fi)...");
                if (Build.VERSION.SDK_INT >= 33) {
                    requestPermissions(new String[]{
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                            Manifest.permission.NEARBY_WIFI_DEVICES
                    }, CODE_LOCATION_PERMISSION);
                } else {
                    requestPermissions(new String[]{
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                    }, CODE_LOCATION_PERMISSION);
                }
                return;
            }
        }
        startCameraEngine();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CODE_LOCATION_PERMISSION) {
            startCameraEngine();
        }
    }

    private void startCameraEngine() {
        appendLog("Bắt đầu khởi động bộ đọc video Camera VietMap...");
        appendLog("⚡ Tự khởi động cùng xe: Sẵn sàng (BootCompletedReceiver)");

        // 1. Quét & Kiểm tra Wi-Fi Cam
        wifiScanner = new VietMapWifiScanner(this, new VietMapWifiScanner.WifiScanListener() {
            @Override
            public void onVietMapCamFound(String ssid, int signalLevel) {
                tvWifiStatus.setText("Wi-Fi: " + ssid + " (Tín hiệu: " + signalLevel + " dBm)");
                appendLog("Tìm thấy Wi-Fi Cam: " + ssid);
            }

            @Override
            public void onConnectedToVietMapCam(String ssid) {
                tvWifiStatus.setText("Wi-Fi: " + ssid + " (Đã kết nối)");
                appendLog("Đã kết nối Wi-Fi Cam: " + ssid);
            }

            @Override
            public void onError(String errorMsg) {
                tvWifiStatus.setText("Wi-Fi: " + errorMsg);
                appendLog("Wi-Fi info: " + errorMsg);
            }
        });
        String currentSsid = wifiScanner.getCurrentWifiSSID();
        tvWifiStatus.setText("Wi-Fi: " + currentSsid);
        wifiScanner.scanForVietMapCam();

        // 2. Khởi tạo Trình đọc luồng Video trực tiếp
        streamReader = new VietMapStreamReader(this, new VietMapStreamReader.FrameCallback() {
            @Override
            public void onFrameCaptured(final Bitmap bitmap) {
                if (layoutConnecting.getVisibility() == View.VISIBLE) {
                    layoutConnecting.setVisibility(View.GONE);
                }
                ivCameraLive.setImageBitmap(bitmap);
                tvResolutionStats.setText("Kích thước: " + bitmap.getWidth() + "x" + bitmap.getHeight());
            }

            @Override
            public void onStreamError(String errorMessage) {
                appendLog("Stream error: " + errorMessage);
            }
        }, null);

        streamReader.setLogListener(new VietMapStreamReader.StreamLogListener() {
            @Override
            public void onLog(String message) {
                appendLog(message);
            }
        });

        streamReader.setStatusListener(new VietMapStreamReader.StreamStatusListener() {
            @Override
            public void onStatusUpdated(String streamStatus, boolean isConnected, int currentFps, int frameCount) {
                tvFpsBadge.setText(currentFps + " FPS");
                tvFrameCountStats.setText("Đã nhận: " + frameCount + " frames");
            }
        });

        streamReader.startStreaming();
    }

    private void startFloatingHUDService() {
        appendLog("Khởi chạy Floating HUD Service...");
        Intent intent = new Intent(MainActivity.this, FloatingService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(this, intent);
        } else {
            startService(intent);
        }
        Toast.makeText(this, "Đã mở Widget Nổi HUD Đếm Ngược!", Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (streamReader != null) {
            streamReader.stopStreaming();
        }
    }
}
