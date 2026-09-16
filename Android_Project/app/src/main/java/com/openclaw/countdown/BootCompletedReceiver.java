package com.openclaw.countdown;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import androidx.core.content.ContextCompat;

/**
 * BootCompletedReceiver
 * Tự động kích hoạt FloatingService (HUD Đếm ngược & Kết nối ngầm Cam VietMap)
 * ngay khi xe nổ máy / Android Box khởi động hoàn tất.
 */
public class BootCompletedReceiver extends BroadcastReceiver {
    private static final String TAG = "BootCompletedReceiver";
    public static final String PREF_NAME = "VietMapConfig";
    public static final String KEY_AUTO_START = "auto_start_on_boot";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        Log.d(TAG, "Nhận tín hiệu Broadcast khởi động hệ thống: " + action);

        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action)
                || "com.htc.intent.action.QUICKBOOT_POWERON".equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                || "android.intent.action.REBOOT".equals(action)) {

            SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            boolean isAutoStartEnabled = prefs.getBoolean(KEY_AUTO_START, true);

            if (!isAutoStartEnabled) {
                Log.d(TAG, "Tính năng tự khởi động cùng hệ thống đã bị tắt trong Cài đặt.");
                return;
            }

            // Kiểm tra quyền Overlay trên Android 6.0+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
                Log.w(TAG, "Chưa cấp quyền Vẽ lên ứng dụng khác (Overlay) -> Bỏ qua tự khởi chạy.");
                return;
            }

            Log.i(TAG, "Đang tự động khởi chạy FloatingService cho Android Box khi nổ máy...");
            try {
                Intent serviceIntent = new Intent(context, FloatingService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ContextCompat.startForegroundService(context, serviceIntent);
                } else {
                    context.startService(serviceIntent);
                }
                Log.i(TAG, "Đã khởi chạy FloatingService thành công khi khởi động Android Box!");
            } catch (Exception e) {
                Log.e(TAG, "Lỗi khi tự khởi động FloatingService: " + e.getMessage(), e);
            }
        }
    }
}
