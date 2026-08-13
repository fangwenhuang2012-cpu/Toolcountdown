package com.openclaw.countdown;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.core.app.NotificationCompat;

public class FloatingService extends Service {
    private static final String CHANNEL_ID = "FloatingServiceChannel";
    private static final int NOTIFICATION_ID = 1001;

    private WindowManager windowManager;
    private WebView webView;
    private WindowManager.LayoutParams params;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private VehicleSpeedMonitor speedMonitor;
    private VietMapStreamReader streamReader;
    private TrafficLightDetector detector;
    private VietMapWifiScanner wifiScanner;

    private String currentWifiSsid = "Đang dò Wi-Fi VietMap...";
    private String currentStreamStatus = "Chưa kết nối luồng Camera";
    private String currentGpsSpeed = "0 km/h (Sẵn sàng)";
    private String currentAiStatus = "Đang chờ xe dừng hẳn";
    private boolean isRedLightActive = false;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        createNotificationChannel();
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Đếm Ngược AI VietMap")
                .setContentText("Tự động nhận diện đèn đỏ VietMap đang chạy ngầm")
                .setSmallIcon(R.drawable.ic_notification)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setOngoing(true)
                .build();

        startForeground(NOTIFICATION_ID, notification);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            stopSelf();
            return;
        }

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        // Fix WebView Service Context Crash: Use ContextThemeWrapper
        ContextThemeWrapper contextThemeWrapper = new ContextThemeWrapper(this, R.style.Theme_FloatingCountdown);
        webView = new WebView(contextThemeWrapper);

        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setAllowFileAccess(true);
        webSettings.setTextZoom(100); // Lock text zoom to 100% to prevent Android system font zoom distortion
        webSettings.setUseWideViewPort(true);
        webSettings.setLoadWithOverviewMode(true);
        webSettings.setSupportZoom(false);
        webSettings.setBuiltInZoomControls(false);
        webSettings.setDisplayZoomControls(false);

        webView.setBackgroundColor(Color.TRANSPARENT);
        webView.setWebViewClient(new WebViewClient());
        webView.addJavascriptInterface(new WebAppInterface(), "AndroidBridge");

        int layoutType;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            layoutType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            layoutType = WindowManager.LayoutParams.TYPE_PHONE;
        }

        float density = getResources().getDisplayMetrics().density;
        android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            windowManager.getDefaultDisplay().getRealMetrics(metrics);
        }
        int screenW = metrics.widthPixels;
        int screenH = metrics.heightPixels;

        int defaultWidth = Math.min(Math.round(350 * density), screenW);
        int defaultHeight = Math.min(Math.round(405 * density), screenH);
        int defaultX = Math.round(15 * density);
        int defaultY = Math.round(15 * density);

        params = new WindowManager.LayoutParams(
                defaultWidth,
                defaultHeight,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );

        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.x = defaultX;
        params.y = defaultY;

        try {
            windowManager.addView(webView, params);
            webView.loadUrl("file:///android_asset/countdown_standalone.html");
        } catch (Exception e) {
            e.printStackTrace();
            stopSelf();
        }

        setupAIServices();
    }

    private String lastPushedJs = "";

    private void pushStatusToUi() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (webView != null) {
                    String js = "if(window.updateSystemStatus){window.updateSystemStatus('" +
                            currentWifiSsid.replace("'", "\\'") + "', '" +
                            currentStreamStatus.replace("'", "\\'") + "', '" +
                            currentGpsSpeed.replace("'", "\\'") + "', '" +
                            currentAiStatus.replace("'", "\\'") + "');}";
                    if (!js.equals(lastPushedJs)) {
                        lastPushedJs = js;
                        webView.evaluateJavascript(js, null);
                    }
                }
            }
        });
    }

    private void setupAIServices() {
        wifiScanner = new VietMapWifiScanner(this, new VietMapWifiScanner.WifiScanListener() {
            private String lastPromptedSsid = null;

            @Override
            public void onVietMapCamFound(final String ssid, int signalLevel) {
                currentWifiSsid = "Phát hiện: " + ssid;
                pushStatusToUi();

                android.content.SharedPreferences prefs = getSharedPreferences("VietMapConfig", MODE_PRIVATE);
                String savedPass = prefs.getString("wifi_pass_" + ssid, null);
                
                if (savedPass != null) {
                    wifiScanner.connectToVietMapCam(ssid, savedPass);
                } else if (!ssid.equals(lastPromptedSsid)) {
                    lastPromptedSsid = ssid;
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (webView != null) {
                                webView.evaluateJavascript("if(window.showWifiPasswordPrompt){window.showWifiPasswordPrompt('" + ssid + "');}", null);
                            }
                        }
                    });
                }
            }

            @Override
            public void onConnectedToVietMapCam(String ssid) {
                currentWifiSsid = ssid;
                pushStatusToUi();
            }

            @Override
            public void onError(String errorMsg) {}
        });
        currentWifiSsid = wifiScanner.getCurrentWifiSSID();
        wifiScanner.scanForVietMapCam();

        detector = new TrafficLightDetector(this, new TrafficLightDetector.DetectionListener() {
            @Override
            public void onRedLightCountdownDetected(final int seconds, float confidence) {
                isRedLightActive = true;
                currentAiStatus = "Đang soi đếm ngược: " + seconds + "s";
                pushStatusToUi();
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (webView != null) {
                            webView.evaluateJavascript("if(window.setCameraCountdown){window.setCameraCountdown(" + seconds + ");}", null);
                            if (params != null && windowManager != null) {
                                params.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                                try {
                                    windowManager.updateViewLayout(webView, params);
                                } catch (Exception e) {}
                            }
                        }
                    }
                });
            }

            @Override
            public void onRedLightEnded() {
                isRedLightActive = false;
                currentAiStatus = "Đèn xanh - Cho phép đi";
                pushStatusToUi();
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (webView != null) {
                            webView.evaluateJavascript("if(window.onRedLightEnded){window.onRedLightEnded();}", null);
                            if (params != null && windowManager != null) {
                                mainHandler.postDelayed(new Runnable() {
                                    @Override
                                    public void run() {
                                        params.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                                        try {
                                            windowManager.updateViewLayout(webView, params);
                                        } catch (Exception e) {}
                                    }
                                }, 800);
                            }
                        }
                    }
                });
            }
        });

        streamReader = new VietMapStreamReader(this, null, null, detector);
        streamReader.setStatusListener(new VietMapStreamReader.StreamStatusListener() {
            @Override
            public void onStatusUpdated(String streamStatus, boolean isConnected) {
                currentStreamStatus = streamStatus;
                pushStatusToUi();
            }
        });
        streamReader.startStreaming();

        speedMonitor = new VehicleSpeedMonitor(this, new VehicleSpeedMonitor.SpeedListener() {
            @Override
            public void onVehicleStopped() {
                currentGpsSpeed = "0 km/h (Đã dừng)";
                if (isRedLightActive) {
                    currentAiStatus = "Xe dừng - Tiếp tục đếm ngược";
                } else {
                    currentAiStatus = "Xe dừng - AI đang soi camera";
                }
                pushStatusToUi();
                if (streamReader != null && !streamReader.isStreaming()) {
                    streamReader.startStreaming();
                }
                
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (webView != null) {
                            webView.evaluateJavascript("if(window.onVehicleStopped){window.onVehicleStopped();}", null);
                            if (params != null && windowManager != null) {
                                params.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                                try {
                                    windowManager.updateViewLayout(webView, params);
                                } catch (Exception e) {}
                            }
                        }
                    }
                });
            }

            @Override
            public void onVehicleMoving(float speedKmh) {
                if (isRedLightActive) {
                    if (speedKmh < 10.0f) {
                        currentGpsSpeed = String.format("%.0f km/h (Đang nhích xe)", speedKmh);
                        pushStatusToUi();
                        return;
                    } else {
                        currentGpsSpeed = String.format("%.0f km/h (Vượt 10km/h - Ẩn HUD)", speedKmh);
                        currentAiStatus = "Đang đếm ngầm (Background)";
                        pushStatusToUi();
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                if (webView != null) {
                                    webView.evaluateJavascript("if(window.onVehicleMovedBackground){window.onVehicleMovedBackground();}", null);
                                    if (params != null && windowManager != null) {
                                        params.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                                        try {
                                            windowManager.updateViewLayout(webView, params);
                                        } catch (Exception e) {}
                                    }
                                }
                            }
                        });
                        return;
                    }
                }
                isRedLightActive = false;
                
                currentGpsSpeed = String.format("%.0f km/h (Đang di chuyển)", speedKmh);
                currentAiStatus = "Xe di chuyển - Thu nhỏ HUD";
                pushStatusToUi();
                if (detector != null) {
                    detector.reset();
                }
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (webView != null) {
                            // Collapse về bubble nhỏ thay vì ẩn hẳn → người dùng vẫn bấm xem báo cáo được
                            webView.evaluateJavascript("if(window.onVehicleMovedBackground){window.onVehicleMovedBackground();}", null);
                            if (params != null && windowManager != null) {
                                // Giữ nguyên FLAG_NOT_FOCUSABLE nhưng bỏ FLAG_NOT_TOUCHABLE để bấm được
                                params.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                                try {
                                    windowManager.updateViewLayout(webView, params);
                                } catch (Exception e) {}
                            }
                        }
                    }
                });
            }

            @Override
            public void onSpeedUpdated(float speedKmh) {
                currentGpsSpeed = String.format("%.0f km/h (%s)", speedKmh, speedKmh <= 5.0f ? "Đã dừng" : "Đang di chuyển");
                pushStatusToUi();
            }
        });

        speedMonitor.startMonitoring();
        pushStatusToUi();
    }

    public class WebAppInterface {
        @JavascriptInterface
        public void updateWindowBounds(final int x, final int y, final int width, final int height) {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (windowManager != null && webView != null && params != null) {
                        android.util.DisplayMetrics m = getResources().getDisplayMetrics();
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                            windowManager.getDefaultDisplay().getRealMetrics(m);
                        }
                        int sW = m.widthPixels;
                        int sH = m.heightPixels;
                        float d = m.density;

                        int targetW = Math.round(width * d);
                        int targetH = Math.round(height * d);

                        params.width = Math.min(Math.max(Math.round(40 * d), targetW), sW);
                        params.height = Math.min(Math.max(Math.round(40 * d), targetH), sH);

                        params.x = Math.max(0, Math.min(params.x, sW - params.width));
                        params.y = Math.max(0, Math.min(params.y, sH - params.height));
                        try {
                            windowManager.updateViewLayout(webView, params);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                }
            });
        }

        @JavascriptInterface
        public void moveWindow(final int dx, final int dy) {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (windowManager != null && webView != null && params != null) {
                        android.util.DisplayMetrics m = getResources().getDisplayMetrics();
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                            windowManager.getDefaultDisplay().getRealMetrics(m);
                        }
                        int sW = m.widthPixels;
                        int sH = m.heightPixels;

                        params.x = Math.max(0, Math.min(params.x + dx, sW - params.width));
                        params.y = Math.max(0, Math.min(params.y + dy, sH - params.height));
                        try {
                            windowManager.updateViewLayout(webView, params);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                }
            });
        }

        @JavascriptInterface
        public void simulateVehicleStopAndRedLight(final int seconds) {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (webView != null) {
                        webView.evaluateJavascript("if(window.setCameraCountdown){window.setCameraCountdown(" + seconds + ");}", null);
                    }
                }
            });
        }

        @JavascriptInterface
        public void closeApp() {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    stopSelf();
                }
            });
        }

        @JavascriptInterface
        public void setWindowFocusable(final boolean focusable) {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (windowManager != null && webView != null && params != null) {
                        if (focusable) {
                            // Bật focus: cho phép nhập bàn phím (ví dụ: nhập mật khẩu Wi-Fi)
                            params.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
                        } else {
                            // Tắt focus: trở lại overlay không chặn cảm ứng bín dưới
                            params.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
                        }
                        try {
                            windowManager.updateViewLayout(webView, params);
                        } catch (Exception e) {}
                    }
                }
            });
        }

        @JavascriptInterface
        public void hideWindow() {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (params != null && windowManager != null) {
                        params.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                        try {
                            windowManager.updateViewLayout(webView, params);
                        } catch (Exception e) {}
                    }
                }
            });
        }

        @JavascriptInterface
        public void resetDetector() {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    isRedLightActive = false;
                    if (detector != null) {
                        detector.reset();
                    }
                    currentAiStatus = "Đã hết đèn đỏ (Reset AI)";
                    pushStatusToUi();
                }
            });
        }

        @JavascriptInterface
        public void submitWifiPassword(final String ssid, final String password) {
            android.content.SharedPreferences prefs = getSharedPreferences("VietMapConfig", MODE_PRIVATE);
            prefs.edit().putString("wifi_pass_" + ssid, password).apply();
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (wifiScanner != null) {
                        wifiScanner.connectToVietMapCam(ssid, password);
                    }
                }
            });
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "Floating Service Channel",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (speedMonitor != null) {
            speedMonitor.stopMonitoring();
        }
        if (streamReader != null) {
            streamReader.stopStreaming();
        }
        if (webView != null && windowManager != null) {
            try {
                windowManager.removeView(webView);
            } catch (Exception ignored) {}
            webView.destroy();
        }
    }
}
