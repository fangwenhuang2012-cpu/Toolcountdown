package com.openclaw.zalosound;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

public class ZaloNotificationService extends NotificationListenerService {
    private static final String TAG = "ZaloNotifService";
    private SoundManager soundManager;

    @Override
    public void onCreate() {
        super.onCreate();
        soundManager = SoundManager.getInstance(this);
        Log.i(TAG, "ZaloNotificationService started and ready.");
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) return;

        NotificationClassifier.MessageType type = NotificationClassifier.classify(sbn);

        switch (type) {
            case DIRECT_1_1:
                Log.i(TAG, ">>> [ZALO 1-1 MESSAGE] Triggering Direct Sound.");
                soundManager.playDirectMessageSound();
                break;

            case GROUP:
                Log.i(TAG, ">>> [ZALO GROUP MESSAGE] Triggering Group Sound.");
                soundManager.playGroupMessageSound();
                break;

            case CALL:
                Log.d(TAG, "Zalo Call detected - Ignoring.");
                break;

            case SYSTEM_IGNORE:
            default:
                break;
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        // Không cần xử lý khi dismiss thông báo
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "ZaloNotificationService destroyed.");
    }
}
