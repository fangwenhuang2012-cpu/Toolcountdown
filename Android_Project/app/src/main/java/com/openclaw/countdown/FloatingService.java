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
                    webView.evaluateJavascript(js, null);
                }
            }
        });
    }

    private void setupAIServices() {
        wifiScanner = new VietMapWifiScanner(this, new VietMapWifiScanner.WifiScanListener() {
            @Override
            public void onVietMapCamFound(String ssid, int signalLevel) {
                currentWifiSsid = "Phát hiện: " + ssid;
                pushStatusToUi();
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
                currentAiStatus = "Đang soi đếm ngược: " + seconds + "s";
                pushStatusToUi();
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (webView != null) {
                            webView.evaluateJavascript("if(window.setCameraCountdown){window.setCameraCountdown(" + seconds + ");}", null);
                        }
                    }
                });
            }

            @Override
            public void onRedLightEnded() {
                currentAiStatus = "Đèn xanh - Cho phép đi";
                pushStatusToUi();
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (webView != null) {
                            webView.evaluateJavascript("if(window.onRedLightEnded){window.onRedLightEnded();}", null);
                        }
                    }
                });
            }
        });

        streamReader = new VietMapStreamReader(null, null, detector);
        streamReader.setStatusListener(new VietMapStreamReader.StreamStatusListener() {
            @Override
            public void onStatusUpdated(String streamStatus, boolean isConnected) {
                currentStreamStatus = streamStatus;
                pushStatusToUi();
            }
        });

        speedMonitor = new VehicleSpeedMonitor(this, new VehicleSpeedMonitor.SpeedListener() {
            @Override
            public void onVehicleStopped() {
                currentGpsSpeed = "0 km/h (Đã dừng)";
                currentAiStatus = "Xe dừng - Bật luồng AI soi camera";
                pushStatusToUi();
                if (streamReader != null && !streamReader.isStreaming()) {
                    streamReader.startStreaming();
                }
            }

            @Override
            public void onVehicleMoving(float speedKmh) {
                currentGpsSpeed = String.format("%.0f km/h (Đang di chuyển)", speedKmh);
                currentAiStatus = "Xe di chuyển - Ẩn Tool AI";
                pushStatusToUi();
                if (streamReader != null && streamReader.isStreaming()) {
                    streamReader.stopStreaming();
                }
                if (detector != null) {
                    detector.reset();
                }
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (webView != null) {
                            webView.evaluateJavascript("if(window.onVehicleMoved){window.onVehicleMoved();}", null);
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
