package com.openclaw.zalosound;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

public class ZaloNotificationService extends NotificationListenerService {
    private static final String TAG = "ZaloNotifService";
    private SoundManager soundManager;
    private PrefsHelper prefs;

    @Override
    public void onCreate() {
        super.onCreate();
        soundManager = SoundManager.getInstance(this);
        prefs = new PrefsHelper(this);
        Log.i(TAG, "ZaloNotificationService started and ready.");
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) return;

        NotificationClassifier.ClassificationResult result = NotificationClassifier.classify(sbn);
        if (result.getType() == NotificationClassifier.MessageType.SYSTEM_IGNORE) {
            return;
        }

        String notifKey = sbn.getKey();
        int notifId = sbn.getId();
        long notifWhen = sbn.getNotification() != null ? sbn.getNotification().when : 0L;
        long postTime = sbn.getPostTime();
        String senderOrGroup = result.getSenderName();
        String messageText = result.getMessageText();

        switch (result.getType()) {
            case DIRECT_1_1:
                ContactRule rule = prefs.findMatchingRule(senderOrGroup);
                if (rule != null && rule.isEnabled()) {
                    Log.i(TAG, ">>> [ZALO VIP CONTACT: " + senderOrGroup + "] Triggering VIP Sound: " + rule.getSoundIndex() + " (Key: " + notifKey + ", When: " + notifWhen + ")");
                    soundManager.playContactSound(notifKey, notifId, notifWhen, postTime, rule.getSoundIndex(), rule.getCustomUri(), senderOrGroup, messageText);
                } else {
                    Log.i(TAG, ">>> [ZALO 1-1 MESSAGE: " + senderOrGroup + "] Triggering Default Direct Sound (Key: " + notifKey + ", When: " + notifWhen + ")");
                    soundManager.playDirectMessageSound(notifKey, notifId, notifWhen, postTime, senderOrGroup, messageText);
                }
                break;

            case GROUP:
                Log.i(TAG, ">>> [ZALO GROUP MESSAGE: " + senderOrGroup + "] Triggering Group Sound (Key: " + notifKey + ", When: " + notifWhen + ")");
                soundManager.playGroupMessageSound(notifKey, notifId, notifWhen, postTime, senderOrGroup, messageText);
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
