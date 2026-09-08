package com.openclaw.zalosound;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.media.SoundPool;
import android.net.Uri;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.util.Log;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public class SoundManager {
    private static final String TAG = "ZaloSoundManager";

    private static SoundManager instance;
    private final Context context;
    private final PrefsHelper prefs;

    // SoundPool for zero-latency, instant sound playback
    private SoundPool soundPool;
    private final Map<Integer, Integer> soundMap = new HashMap<>();
    private volatile boolean isSoundPoolLoaded = false;
    private int activeSoundPoolStreamId = 0;

    // MediaPlayer fallback for custom user-selected audio files
    private MediaPlayer mediaPlayer;
    private Ringtone currentRingtone;

    // ==================== DEDUPLICATION & ANTI-SPAM ENGINE ====================
    private static class KeyRecord {
        long timestamp;
        long notifWhen;
        String lastText;

        KeyRecord(long timestamp, long notifWhen, String lastText) {
            this.timestamp = timestamp;
            this.notifWhen = notifWhen;
            this.lastText = lastText != null ? lastText : "";
        }
    }

    // 1. Khoảng cách thời gian tối thiểu giữa 2 lần phát âm thanh toàn cục (2.5s)
    private static final long MIN_AUDIO_PLAY_INTERVAL_MS = 2500L;
    private long lastAudioPlayTimestamp = 0;

    // 2. Global notifWhen timestamp cache (15 giây) - Bắt trọn vẹn mọi notification cập nhật/summary/push/sync
    private static final long NOTIF_WHEN_WINDOW_MS = 15000L;
    private final Map<Long, Long> recentNotifWhenCache = new HashMap<>();

    // 3. Global Text-Only content cache (3.5 giây) - Ngăn trùng nội dung tin nhắn xuyên suốt giữa các sự kiện
    private static final long TEXT_CONTENT_WINDOW_MS = 3500L;
    private final Map<String, Long> recentTextOnlyCache = new HashMap<>();

    // 4. Cache lưu Notification Key / ID kèm timestamp & notifWhen (Cửa sổ 10 giây)
    private static final long KEY_DEDUPLICATION_WINDOW_MS = 10000L;
    private final Map<String, KeyRecord> recentNotifKeyCache = new HashMap<>();

    // 5. Cache lưu Fingerprint nội dung (Người gửi/nhóm đã chuẩn hóa + Nội dung chuẩn hóa) (Cửa sổ 12 giây)
    private static final long FINGERPRINT_WINDOW_MS = 12000L;
    private final Map<String, Long> recentFingerprintCache = new HashMap<>();

    // 6. Cache lưu thời điểm phát theo từng người gửi/nhóm (Tối thiểu 2.8s hoặc theo Anti-Spam cấu hình)
    private static final long MIN_SENDER_COOLDOWN_MS = 2800L;
    private final Map<String, Long> lastSenderSpamTimes = new HashMap<>();

    // Built-in sound indices
    public static final int SOUND_ZALO_CLASSIC = 0;
    public static final int SOUND_TING_MODERN = 1;
    public static final int SOUND_DING_SOFT = 2;
    public static final int SOUND_POP = 3;
    public static final int SOUND_MUTE = 4;
    public static final int SOUND_CUSTOM_FILE = 5;

    public static synchronized SoundManager getInstance(Context context) {
        if (instance == null) {
            instance = new SoundManager(context.getApplicationContext());
        }
        return instance;
    }

    private SoundManager(Context context) {
        this.context = context;
        this.prefs = new PrefsHelper(context);
        initSoundPool();
    }

    private void initSoundPool() {
        try {
            AudioAttributes audioAttributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setLegacyStreamType(AudioManager.STREAM_NOTIFICATION)
                    .build();

            soundPool = new SoundPool.Builder()
                    .setMaxStreams(2)
                    .setAudioAttributes(audioAttributes)
                    .build();

            soundMap.put(SOUND_ZALO_CLASSIC, soundPool.load(context, R.raw.sound_direct_zalo, 1));
            soundMap.put(SOUND_TING_MODERN, soundPool.load(context, R.raw.sound_direct_ting, 1));
            soundMap.put(SOUND_DING_SOFT, soundPool.load(context, R.raw.sound_ding_soft, 1));
            soundMap.put(SOUND_POP, soundPool.load(context, R.raw.sound_group_pop, 1));

            soundPool.setOnLoadCompleteListener((pool, sampleId, status) -> {
                if (status == 0) {
                    isSoundPoolLoaded = true;
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Error initializing SoundPool", e);
        }
    }

    /**
     * Bộ lọc chống trùng lặp (Deduplication) và chống spam dồn dập (Anti-Spam) 6 tầng hoàn hảo.
     * @return true nếu CẦN CHẶN (bỏ qua), false nếu HỢP LỆ để phát âm thanh
     */
    public synchronized boolean shouldDebounce(String notifKey, int notifId, long notifWhen, long postTime, String senderOrGroup, String messageText) {
        long now = System.currentTimeMillis();

        String keyIdentifier = (notifKey != null && !notifKey.trim().isEmpty()) ? notifKey.trim() : ("id_" + notifId);
        String cleanSender = NotificationClassifier.cleanSenderOrGroupName(senderOrGroup).toLowerCase();
        String cleanText = NotificationClassifier.cleanMessageContent(messageText).toLowerCase();
        String fingerprint = cleanSender + "||" + cleanText;

        // ----------------------------------------------------
        // TẦNG 0: LỌC THEO NOTIFICATION 'WHEN' TIMESTAMP TOÀN CỤC (15 GIÂY)
        // Khi Zalo bắn Push -> Sync -> Summary -> Badge update, mốc thời gian tin nhắn (when) là DUY NHẤT.
        // Kiểm tra này bắt 100% duplicate bất kể thay đổi notification key, ID hay sender tag.
        // ----------------------------------------------------
        if (notifWhen > 0) {
            Long lastWhenSeen = recentNotifWhenCache.get(notifWhen);
            if (lastWhenSeen != null && (now - lastWhenSeen < NOTIF_WHEN_WINDOW_MS)) {
                Log.d(TAG, "Debounced [Tier 0 - Global When]: Duplicate message timestamp " + notifWhen + " (elapsed: " + (now - lastWhenSeen) + "ms)");
                recentNotifWhenCache.put(notifWhen, now);
                return true;
            }
            recentNotifWhenCache.put(notifWhen, now);
        }

        // ----------------------------------------------------
        // TẦNG 1: LỌC TRÙNG LẶP NOTIFICATION KEY & ID (10 GIÂY)
        // Bắt các update trên cùng 1 notification channel/id
        // ----------------------------------------------------
        KeyRecord prevKey = recentNotifKeyCache.get(keyIdentifier);
        if (prevKey != null) {
            long elapsed = now - prevKey.timestamp;
            if (elapsed < KEY_DEDUPLICATION_WINDOW_MS) {
                if ((notifWhen > 0 && notifWhen == prevKey.notifWhen) || elapsed < 2800L || (!cleanText.isEmpty() && cleanText.equals(prevKey.lastText))) {
                    Log.d(TAG, "Debounced [Tier 1 - Key]: Duplicate notification update for key " + keyIdentifier + " (elapsed: " + elapsed + "ms, when: " + notifWhen + ")");
                    prevKey.timestamp = now;
                    if (!cleanText.isEmpty()) {
                        prevKey.lastText = cleanText;
                    }
                    if (!fingerprint.equals("||")) recentFingerprintCache.put(fingerprint, now);
                    if (!cleanSender.isEmpty()) lastSenderSpamTimes.put(cleanSender, now);
                    return true;
                }
            }
        }
        recentNotifKeyCache.put(keyIdentifier, new KeyRecord(now, notifWhen, cleanText));

        // ----------------------------------------------------
        // TẦNG 2: LỌC NỘI DUNG TIN NHẮN TOÀN CỤC XUYÊN SUỐT (3.5 GIÂY)
        // Ngăn 2 thông báo liên tiếp có cùng nội dung (ví dụ: "[hình ảnh]", "alo anh em") bất kể tên gửi
        // ----------------------------------------------------
        if (!cleanText.isEmpty()) {
            Long lastTextTime = recentTextOnlyCache.get(cleanText);
            if (lastTextTime != null && (now - lastTextTime < TEXT_CONTENT_WINDOW_MS)) {
                Log.d(TAG, "Debounced [Tier 2 - Content Window]: Duplicate content received within 3.5s -> '" + cleanText + "' (elapsed: " + (now - lastTextTime) + "ms)");
                recentTextOnlyCache.put(cleanText, now);
                if (!fingerprint.equals("||")) recentFingerprintCache.put(fingerprint, now);
                if (!cleanSender.isEmpty()) lastSenderSpamTimes.put(cleanSender, now);
                return true;
            }
            recentTextOnlyCache.put(cleanText, now);
        }

        // ----------------------------------------------------
        // TẦNG 3: LỌC TRÙNG LẶP FINGERPRINT (NGƯỜI GỬI + NỘI DUNG) (12 GIÂY)
        // ----------------------------------------------------
        if (!fingerprint.equals("||") && !cleanText.isEmpty()) {
            Long lastFpTime = recentFingerprintCache.get(fingerprint);
            if (lastFpTime != null && (now - lastFpTime < FINGERPRINT_WINDOW_MS)) {
                Log.d(TAG, "Debounced [Tier 3 - Fingerprint]: Duplicate fingerprint ignored -> " + fingerprint + " (elapsed: " + (now - lastFpTime) + "ms)");
                recentFingerprintCache.put(fingerprint, now);
                if (!cleanSender.isEmpty()) lastSenderSpamTimes.put(cleanSender, now);
                return true;
            }
            recentFingerprintCache.put(fingerprint, now);
        }

        // ----------------------------------------------------
        // TẦNG 4: CHỐNG SPAM / HARD COOLDOWN THEO NGƯỜI GỬI HOẶC NHÓM (TỐI THIỂU 2.8s HOẶC THEO CẤU HÌNH)
        // ----------------------------------------------------
        long effectiveSenderCooldown = MIN_SENDER_COOLDOWN_MS;
        if (prefs.isAntiSpamEnabled()) {
            long userCooldown = prefs.getAntiSpamSeconds() * 1000L;
            effectiveSenderCooldown = Math.max(MIN_SENDER_COOLDOWN_MS, userCooldown);
        }

        if (!cleanSender.isEmpty()) {
            Long lastSenderTime = lastSenderSpamTimes.get(cleanSender);
            if (lastSenderTime != null && (now - lastSenderTime < effectiveSenderCooldown)) {
                Log.d(TAG, "Debounced [Tier 4 - Sender Cooldown]: Active for '" + senderOrGroup + "' (" + (now - lastSenderTime) + "ms < " + effectiveSenderCooldown + "ms)");
                lastSenderSpamTimes.put(cleanSender, now);
                return true;
            }
            lastSenderSpamTimes.put(cleanSender, now);
        }

        // ----------------------------------------------------
        // TẦNG 5: KHÓA THỜI GIAN PHÁT TOÀN CỤC (Ít nhất 2.5 GIÂY GIỮA 2 LẦN PHÁT BẤT KỲ)
        // ----------------------------------------------------
        if (now - lastAudioPlayTimestamp < MIN_AUDIO_PLAY_INTERVAL_MS) {
            Log.d(TAG, "Debounced [Tier 5 - Global Interlock]: Active (" + (now - lastAudioPlayTimestamp) + "ms < " + MIN_AUDIO_PLAY_INTERVAL_MS + "ms)");
            return true;
        }

        // Đạt chuẩn -> Cập nhật mốc phát âm thanh gần nhất
        lastAudioPlayTimestamp = now;
        cleanupCache(now);
        return false;
    }

    public synchronized boolean shouldDebounce(String notifKey, int notifId, String senderOrGroup, String messageText) {
        return shouldDebounce(notifKey, notifId, 0L, 0L, senderOrGroup, messageText);
    }

    private void cleanupCache(long now) {
        if (recentNotifWhenCache.size() > 50) {
            Iterator<Map.Entry<Long, Long>> it = recentNotifWhenCache.entrySet().iterator();
            while (it.hasNext()) {
                if (now - it.next().getValue() > 30000L) {
                    it.remove();
                }
            }
        }

        if (recentTextOnlyCache.size() > 50) {
            Iterator<Map.Entry<String, Long>> it = recentTextOnlyCache.entrySet().iterator();
            while (it.hasNext()) {
                if (now - it.next().getValue() > 15000L) {
                    it.remove();
                }
            }
        }

        if (recentNotifKeyCache.size() > 50) {
            Iterator<Map.Entry<String, KeyRecord>> it = recentNotifKeyCache.entrySet().iterator();
            while (it.hasNext()) {
                if (now - it.next().getValue().timestamp > 30000L) {
                    it.remove();
                }
            }
        }

        if (recentFingerprintCache.size() > 50) {
            Iterator<Map.Entry<String, Long>> it = recentFingerprintCache.entrySet().iterator();
            while (it.hasNext()) {
                if (now - it.next().getValue() > 30000L) {
                    it.remove();
                }
            }
        }

        if (lastSenderSpamTimes.size() > 50) {
            Iterator<Map.Entry<String, Long>> it = lastSenderSpamTimes.entrySet().iterator();
            while (it.hasNext()) {
                if (now - it.next().getValue() > 60000L) {
                    it.remove();
                }
            }
        }
    }

    /**
     * Phát âm thanh cho Tin nhắn Cá nhân (1-1)
     */
    public synchronized void playDirectMessageSound(String notifKey, int notifId, long notifWhen, long postTime, String sender, String text) {
        if (!prefs.isDirectEnabled()) {
            Log.d(TAG, "Direct sound disabled in settings.");
            return;
        }

        if (shouldDebounce(notifKey, notifId, notifWhen, postTime, sender, text)) {
            return;
        }

        int soundIndex = prefs.getDirectSoundIndex();
        String customUri = prefs.getDirectCustomUri();

        playSound(soundIndex, customUri);
        triggerVibrateIfEnabled();
    }

    public synchronized void playDirectMessageSound(String notifKey, int notifId, String sender, String text) {
        playDirectMessageSound(notifKey, notifId, 0L, 0L, sender, text);
    }

    public synchronized void playDirectMessageSound(String sender, String text) {
        playDirectMessageSound(null, 0, 0L, 0L, sender, text);
    }

    public synchronized void playDirectMessageSound() {
        playDirectMessageSound(null, 0, 0L, 0L, "", "");
    }

    /**
     * Phát âm thanh riêng cho Contact VIP cụ thể
     */
    public synchronized void playContactSound(String notifKey, int notifId, long notifWhen, long postTime, int soundIndex, String customUri, String sender, String text) {
        if (shouldDebounce(notifKey, notifId, notifWhen, postTime, sender, text)) {
            return;
        }

        playSound(soundIndex, customUri);
        triggerVibrateIfEnabled();
    }

    public synchronized void playContactSound(String notifKey, int notifId, int soundIndex, String customUri, String sender, String text) {
        playContactSound(notifKey, notifId, 0L, 0L, soundIndex, customUri, sender, text);
    }

    public synchronized void playContactSound(int soundIndex, String customUri, String sender, String text) {
        playContactSound(null, 0, 0L, 0L, soundIndex, customUri, sender, text);
    }

    public synchronized void playContactSound(int soundIndex, String customUri) {
        playContactSound(null, 0, 0L, 0L, soundIndex, customUri, "", "");
    }

    /**
     * Phát âm thanh cho Tin nhắn Nhóm (Group)
     */
    public synchronized void playGroupMessageSound(String notifKey, int notifId, long notifWhen, long postTime, String groupTitle, String text) {
        if (!prefs.isGroupEnabled()) {
            Log.d(TAG, "Group sound disabled in settings.");
            return;
        }

        if (shouldDebounce(notifKey, notifId, notifWhen, postTime, groupTitle, text)) {
            return;
        }

        int soundIndex = prefs.getGroupSoundIndex();
        String customUri = prefs.getGroupCustomUri();

        playSound(soundIndex, customUri);
        triggerVibrateIfEnabled();
    }

    public synchronized void playGroupMessageSound(String notifKey, int notifId, String groupTitle, String text) {
        playGroupMessageSound(notifKey, notifId, 0L, 0L, groupTitle, text);
    }

    public synchronized void playGroupMessageSound(String groupTitle, String text) {
        playGroupMessageSound(null, 0, 0L, 0L, groupTitle, text);
    }

    public synchronized void playGroupMessageSound() {
        playGroupMessageSound(null, 0, 0L, 0L, "", "");
    }

    /**
     * Lấy tên hiển thị của âm thanh
     */
    public String getSoundDisplayName(int soundIndex, String customName) {
        switch (soundIndex) {
            case SOUND_ZALO_CLASSIC:
                return context.getString(R.string.sound_builtin_zalo);
            case SOUND_TING_MODERN:
                return context.getString(R.string.sound_builtin_ting);
            case SOUND_DING_SOFT:
                return context.getString(R.string.sound_builtin_iphone);
            case SOUND_POP:
                return context.getString(R.string.sound_builtin_pop);
            case SOUND_MUTE:
                return context.getString(R.string.sound_builtin_mute);
            case SOUND_CUSTOM_FILE:
                return (customName != null && !customName.trim().isEmpty()) ? customName : context.getString(R.string.sound_custom_device);
            default:
                return context.getString(R.string.sound_builtin_zalo);
        }
    }

    /**
     * Phát thử âm thanh (Test sound)
     */
    public synchronized void testSound(int soundIndex, String customUri) {
        playSound(soundIndex, customUri);
    }

    private synchronized void playSound(int soundIndex, String customUri) {
        if (soundIndex == SOUND_MUTE) {
            Log.d(TAG, "Sound is set to MUTE");
            return;
        }

        // Dừng âm thanh đang phát trước đó để không bị đè/đúp tiếng
        stopActiveAudio();

        // 1. Nếu là âm thanh từ file người dùng chọn
        if (soundIndex == SOUND_CUSTOM_FILE && customUri != null && !customUri.isEmpty()) {
            playCustomUri(customUri);
            return;
        }

        // 2. Nếu là âm thanh tích hợp sẵn -> Dùng SoundPool phát tức thì 0ms latency
        if (soundPool != null && soundMap.containsKey(soundIndex)) {
            Integer poolId = soundMap.get(soundIndex);
            if (poolId != null) {
                int streamId = soundPool.play(poolId, 1.0f, 1.0f, 1, 0, 1.0f);
                if (streamId != 0) {
                    activeSoundPoolStreamId = streamId;
                    return;
                }
            }
        }

        // 3. Fallback sang MediaPlayer nếu SoundPool chưa sẵn sàng
        playFallbackRawSound(soundIndex);
    }

    private synchronized void stopActiveAudio() {
        try {
            if (soundPool != null && activeSoundPoolStreamId != 0) {
                soundPool.stop(activeSoundPoolStreamId);
                activeSoundPoolStreamId = 0;
            }
        } catch (Exception ignored) {}

        stopCustomAudio();
    }

    private void playCustomUri(String uriString) {
        try {
            stopCustomAudio();
            Uri uri = Uri.parse(uriString);
            currentRingtone = RingtoneManager.getRingtone(context, uri);
            if (currentRingtone != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    AudioAttributes attributes = new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build();
                    currentRingtone.setAudioAttributes(attributes);
                }
                currentRingtone.play();
                return;
            }

            // Fallback to MediaPlayer
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            mediaPlayer.setDataSource(context, uri);
            mediaPlayer.setOnPreparedListener(MediaPlayer::start);
            mediaPlayer.setOnCompletionListener(mp -> stopCustomAudio());
            mediaPlayer.prepareAsync();
        } catch (Exception e) {
            Log.e(TAG, "Failed to play custom URI sound: " + uriString, e);
        }
    }

    private void playFallbackRawSound(int soundIndex) {
        try {
            stopCustomAudio();
            int rawResId = getRawResourceForIndex(soundIndex);
            if (rawResId != 0) {
                mediaPlayer = MediaPlayer.create(context, rawResId);
                if (mediaPlayer != null) {
                    mediaPlayer.setOnCompletionListener(mp -> stopCustomAudio());
                    mediaPlayer.start();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Fallback MediaPlayer failed", e);
        }
    }

    private int getRawResourceForIndex(int index) {
        switch (index) {
            case SOUND_ZALO_CLASSIC:
                return R.raw.sound_direct_zalo;
            case SOUND_TING_MODERN:
                return R.raw.sound_direct_ting;
            case SOUND_DING_SOFT:
                return R.raw.sound_ding_soft;
            case SOUND_POP:
                return R.raw.sound_group_pop;
            default:
                return R.raw.sound_direct_zalo;
        }
    }

    private void triggerVibrateIfEnabled() {
        if (!prefs.isVibrateEnabled()) return;

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                VibratorManager vm = (VibratorManager) context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                if (vm != null) {
                    vm.getDefaultVibrator().vibrate(VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE));
                }
            } else {
                Vibrator v = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
                if (v != null && v.hasVibrator()) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        v.vibrate(VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE));
                    } else {
                        v.vibrate(120);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to trigger vibration", e);
        }
    }

    private synchronized void stopCustomAudio() {
        try {
            if (currentRingtone != null && currentRingtone.isPlaying()) {
                currentRingtone.stop();
            }
            currentRingtone = null;
        } catch (Exception ignored) {}

        try {
            if (mediaPlayer != null) {
                if (mediaPlayer.isPlaying()) {
                    mediaPlayer.stop();
                }
                mediaPlayer.release();
                mediaPlayer = null;
            }
        } catch (Exception ignored) {}
    }
}
