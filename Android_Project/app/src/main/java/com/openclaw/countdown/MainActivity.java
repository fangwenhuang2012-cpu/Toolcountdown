package com.openclaw.countdown;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * MainActivity
 * Trình xem Video Trực Tiếp Camera VietMap (VietMap Live Camera Viewer)
 * Hoạt động tương tự app VIETMAP REC:
 * - Kết nối trực tiếp Wi-Fi Camera VietMap (TS-C1, KC01, TS2K, C61, C65...)
 * - Hiển thị luồng video thời gian thực siêu mượt, độ trễ thấp
 * - Hỗ trợ chế độ Toàn Màn Hình cho Android Box trên xe hơi
 */
public class MainActivity extends Activity {
    private static final int PERMISSION_REQUEST_CODE = 3001;

    private LinearLayout rootContainer;
    private LinearLayout layoutHeader;
    private View viewLiveStatusDot;
    private TextView tvWifiStatus;
    private TextView tvFpsBadge;
    private TextView tvStreamProtocolBadge;

    private ImageView ivCameraFeed;
    private LinearLayout layoutConnecting;
    private TextView tvConnectingTitle;
    private TextView tvConnectingSubtitle;
    private LinearLayout layoutVideoStats;
    private TextView tvVideoResolution;
    private TextView tvFrameCounter;

    private LinearLayout layoutControls;
    private Button btnHandshake;
    private Button btnWifiSettings;
    private Button btnSwitchProtocol;
    private Button btnFullscreen;
    private Button btnToggleLogs;

    private LinearLayout layoutLogConsole;
    private ScrollView svLogs;
    private TextView tvLogs;

    private VietMapStreamClient streamClient;
    private VietMapWifiHelper wifiHelper;
    private boolean isFullscreenMode = false;
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        bindViews();
        setupListeners();
        setupWifiAndStream();
        checkPermissionsAndStart();
    }

    private void bindViews() {
        rootContainer = findViewById(R.id.rootContainer);
        layoutHeader = findViewById(R.id.layoutHeader);
        viewLiveStatusDot = findViewById(R.id.viewLiveStatusDot);
        tvWifiStatus = findViewById(R.id.tvWifiStatus);
        tvFpsBadge = findViewById(R.id.tvFpsBadge);
        tvStreamProtocolBadge = findViewById(R.id.tvStreamProtocolBadge);

        ivCameraFeed = findViewById(R.id.ivCameraFeed);
        layoutConnecting = findViewById(R.id.layoutConnecting);
        tvConnectingTitle = findViewById(R.id.tvConnectingTitle);
        tvConnectingSubtitle = findViewById(R.id.tvConnectingSubtitle);
        layoutVideoStats = findViewById(R.id.layoutVideoStats);
        tvVideoResolution = findViewById(R.id.tvVideoResolution);
        tvFrameCounter = findViewById(R.id.tvFrameCounter);

        layoutControls = findViewById(R.id.layoutControls);
        btnHandshake = findViewById(R.id.btnHandshake);
        btnWifiSettings = findViewById(R.id.btnWifiSettings);
        btnSwitchProtocol = findViewById(R.id.btnSwitchProtocol);
        btnFullscreen = findViewById(R.id.btnFullscreen);
        btnToggleLogs = findViewById(R.id.btnToggleLogs);

        layoutLogConsole = findViewById(R.id.layoutLogConsole);
        svLogs = findViewById(R.id.svLogs);
        tvLogs = findViewById(R.id.tvLogs);
    }

    private void appendLog(String log) {
        String timestamp = timeFormat.format(new Date());
        final String line = "[" + timestamp + "] " + log + "\n";
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (tvLogs != null) {
                    tvLogs.append(line);
                    if (svLogs != null) {
                        svLogs.post(new Runnable() {
                            @Override
                            public void run() {
                                svLogs.fullScroll(View.FOCUS_DOWN);
                            }
                        });
                    }
                }
            }
        });
    }

    private void setupWifiAndStream() {
        wifiHelper = new VietMapWifiHelper(this);
        wifiHelper.setListener(new VietMapWifiHelper.WifiStateListener() {
            @Override
            public void onWifiStateChanged(boolean isWifiEnabled, String currentSsid, boolean isCameraSsid, String gatewayIp) {
                if (!isWifiEnabled) {
                    tvWifiStatus.setText("Wi-Fi: Đang tắt. Vui lòng bật Wi-Fi");
                    viewLiveStatusDot.setBackgroundColor(Color.parseColor("#EF4444")); // Red
                } else {
                    String camText = isCameraSsid ? " (Cam VietMap)" : "";
                    tvWifiStatus.setText("Wi-Fi: " + currentSsid + camText);
                    if (!isCameraSsid && currentSsid.startsWith("Chưa kết nối")) {
                        viewLiveStatusDot.setBackgroundColor(Color.parseColor("#EF4444"));
                    }
                }
                appendLog("Trạng thái Wi-Fi: " + currentSsid + " (Gateway: " + gatewayIp + ")");
            }
        });

        streamClient = new VietMapStreamClient(this);

        streamClient.setFrameListener(new VietMapStreamClient.FrameListener() {
            @Override
            public void onFrameDecoded(final Bitmap bitmap, final int width, final int height) {
                if (layoutConnecting.getVisibility() == View.VISIBLE) {
                    layoutConnecting.setVisibility(View.GONE);
                }
                ivCameraFeed.setImageBitmap(bitmap);
                tvVideoResolution.setText("Độ phân giải: " + width + "x" + height);
                viewLiveStatusDot.setBackgroundColor(Color.parseColor("#22C55E")); // Green
            }
        });

        streamClient.setStatusListener(new VietMapStreamClient.StatusListener() {
            @Override
            public void onStatusChanged(String statusText, boolean isLive, int fps, int totalFrames, String activeIp, String modeName) {
                tvFpsBadge.setText(fps + " FPS");
                tvFrameCounter.setText("Khung hình: " + totalFrames);
                tvConnectingSubtitle.setText(statusText);

                if (!isLive) {
                    viewLiveStatusDot.setBackgroundColor(Color.parseColor("#F59E0B")); // Yellow / Orange
                }
            }
        });

        streamClient.setLogListener(new VietMapStreamClient.LogListener() {
            @Override
            public void onLog(String logMessage) {
                appendLog(logMessage);
            }
        });
    }

    private void setupListeners() {
        // Nút 1: Kích hoạt / Làm mới Handshake
        btnHandshake.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                appendLog("Thực hiện kích hoạt lại Handshake...");
                Toast.makeText(MainActivity.this, "Đang gửi lệnh kích hoạt Cam...", Toast.LENGTH_SHORT).show();
                if (streamClient != null) {
                    streamClient.triggerManualHandshake();
                }
            }
        });

        // Nút 2: Mở Cài đặt Wi-Fi của Android Box
        btnWifiSettings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                appendLog("Mở trang cài đặt Wi-Fi hệ thống...");
                if (wifiHelper != null) {
                    wifiHelper.openWifiSettings();
                }
            }
        });

        // Nút 3: Đổi Giao Thức Stream (AUTO -> SOCKET_8192 -> HTTP_MJPEG -> SNAPSHOT_CGI -> AUTO)
        btnSwitchProtocol.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (streamClient == null) return;
                VietMapStreamClient.StreamProtocol current = streamClient.getProtocol();
                VietMapStreamClient.StreamProtocol next;
                if (current == VietMapStreamClient.StreamProtocol.AUTO) {
                    next = VietMapStreamClient.StreamProtocol.SOCKET_8192;
                } else if (current == VietMapStreamClient.StreamProtocol.SOCKET_8192) {
                    next = VietMapStreamClient.StreamProtocol.HTTP_MJPEG;
                } else if (current == VietMapStreamClient.StreamProtocol.HTTP_MJPEG) {
                    next = VietMapStreamClient.StreamProtocol.SNAPSHOT_CGI;
                } else {
                    next = VietMapStreamClient.StreamProtocol.AUTO;
                }

                streamClient.setProtocol(next);
                tvStreamProtocolBadge.setText("Mode: " + next.getDisplayName());
                Toast.makeText(MainActivity.this, "Chế độ: " + next.getDisplayName(), Toast.LENGTH_SHORT).show();

                // Restart stream để áp dụng ngay lập tức
                layoutConnecting.setVisibility(View.VISIBLE);
                streamClient.stopStream();
                streamClient.startStream();
            }
        });

        // Nút 4: Bật/Tắt Toàn Màn Hình (Chế độ lái xe)
        btnFullscreen.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleFullscreen();
            }
        });

        // Chạm vào khung hình Video để thoát chế độ Toàn Màn Hình
        ivCameraFeed.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (isFullscreenMode) {
                    toggleFullscreen();
                }
            }
        });

        // Nút 5: Ẩn / Hiện Bảng Nhật Ký
        btnToggleLogs.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (layoutLogConsole.getVisibility() == View.VISIBLE) {
                    layoutLogConsole.setVisibility(View.GONE);
                    btnToggleLogs.setText("📋 Mở Nhật Ký");
                } else {
                    layoutLogConsole.setVisibility(View.VISIBLE);
                    btnToggleLogs.setText("📋 Ẩn Nhật Ký");
                }
            }
        });
    }

    private void toggleFullscreen() {
        isFullscreenMode = !isFullscreenMode;
        if (isFullscreenMode) {
            layoutHeader.setVisibility(View.GONE);
            layoutControls.setVisibility(View.GONE);
            layoutLogConsole.setVisibility(View.GONE);
            layoutVideoStats.setVisibility(View.GONE);
            rootContainer.setPadding(0, 0, 0, 0);
            Toast.makeText(this, "Chạm vào màn hình để hiện lại thanh điều khiển", Toast.LENGTH_SHORT).show();
        } else {
            layoutHeader.setVisibility(View.VISIBLE);
            layoutControls.setVisibility(View.VISIBLE);
            layoutVideoStats.setVisibility(View.VISIBLE);
            btnToggleLogs.setText("📋 Ẩn Nhật Ký");
            layoutLogConsole.setVisibility(View.VISIBLE);
            int p = (int) (8 * getResources().getDisplayMetrics().density);
            rootContainer.setPadding(p, p, p, p);
        }
    }

    private void checkPermissionsAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                appendLog("Yêu cầu cấp quyền Vị trí & Wi-Fi...");
                if (Build.VERSION.SDK_INT >= 33) {
                    requestPermissions(new String[]{
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                            Manifest.permission.NEARBY_WIFI_DEVICES
                    }, PERMISSION_REQUEST_CODE);
                } else {
                    requestPermissions(new String[]{
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                    }, PERMISSION_REQUEST_CODE);
                }
                return;
            }
        }
        startCameraPlayback();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            startCameraPlayback();
        }
    }

    private void startCameraPlayback() {
        appendLog("Bắt đầu theo dõi Wi-Fi và kéo luồng Video...");
        if (wifiHelper != null) {
            wifiHelper.startListening();
        }
        if (streamClient != null) {
            streamClient.startStream();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (wifiHelper != null) {
            wifiHelper.notifyState();
        }
        if (streamClient != null && !streamClient.isRunning()) {
            streamClient.startStream();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (wifiHelper != null) {
            wifiHelper.stopListening();
        }
        if (streamClient != null) {
            streamClient.stopStream();
        }
    }
}
