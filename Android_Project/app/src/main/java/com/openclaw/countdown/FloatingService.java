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

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        createNotificationChannel();
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Đếm Ngược Floating")
                .setContentText("Cửa sổ nổi đếm ngược đang chạy")
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
        int screenW = metrics.widthPixels;
        int screenH = metrics.heightPixels;

        int defaultWidth = Math.min(Math.round(420 * density), Math.round(screenW * 0.85f));
        int defaultHeight = Math.min(Math.round(290 * density), Math.round(screenH * 0.85f));
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
    }

    public class WebAppInterface {
        @JavascriptInterface
        public void updateWindowBounds(final int x, final int y, final int width, final int height) {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (windowManager != null && webView != null && params != null) {
                        android.util.DisplayMetrics m = getResources().getDisplayMetrics();
                        int sW = m.widthPixels;
                        int sH = m.heightPixels;

                        params.x = Math.max(0, Math.min(x, sW - 40));
                        params.y = Math.max(0, Math.min(y, sH - 40));
                        params.width = Math.min(Math.max(40, width), sW);
                        params.height = Math.min(Math.max(40, height), sH);
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
                        params.x = Math.max(0, params.x + dx);
                        params.y = Math.max(0, params.y + dy);
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
        if (webView != null && windowManager != null) {
            try {
                windowManager.removeView(webView);
            } catch (Exception ignored) {}
            webView.destroy();
        }
    }
}
