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

    // ==================== DEDUPLICATION & ANTI-SPAM ====================
    // 1. Khoảng cách thời gian tối thiểu giữa 2 lần phát âm thanh (Hard Global Cooldown: 1.2s)
    // Ngăn chặn hoàn toàn việc Android/Zalo bắn 2 event thông báo liên tiếp làm đúp nhạc chuông.
    private static final long MIN_AUDIO_PLAY_INTERVAL_MS = 1200L;
    private long lastAudioPlayTimestamp = 0;

    // 2. Cache lưu Notification Key / ID để chặn event Update cùng thông báo trong 3.5s
    private final Map<String, Long> recentNotifKeyCache = new HashMap<>();

    // 3. Cache lưu Fingerprint nội dung (Người gửi + Nội dung chuẩn hóa) trong 6s
    private final Map<String, Long> recentFingerprintCache = new HashMap<>();

    // 4. Cache lưu thời điểm phát theo từng người gửi cho bộ lọc Anti-Spam người dùng cấu hình
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
     * Bộ lọc chống trùng lặp (Deduplication) và chống spam dồn dập (Anti-Spam) 2 tầng.
     * @return true nếu CẦN CHẶN (bỏ qua), false nếu HỢP LỆ để phát âm thanh
     */
    public synchronized boolean shouldDebounce(String notifKey, int notifId, String senderOrGroup, String messageText) {
        long now = System.currentTimeMillis();

        // ----------------------------------------------------
        // TẦNG 1: BẢO VỆ CỨNG TOÀN CỤC (MANDATORY HARD SYSTEM DEBOUNCE)
        // Luôn luôn hoạt động bất kể người dùng bật/tắt Anti-Spam
        // để xử lý triệt để hiện tượng Android/Zalo gửi trùng lặp thông báo.
        // ----------------------------------------------------

        // 1.1 Khóa thời gian phát tối thiểu giữa 2 âm thanh (ít nhất 1.2 giây)
        if (now - lastAudioPlayTimestamp < MIN_AUDIO_PLAY_INTERVAL_MS) {
            Log.d(TAG, "Debounced: Global audio cooldown active (" + (now - lastAudioPlayTimestamp) + "ms < " + MIN_AUDIO_PLAY_INTERVAL_MS + "ms)");
            return true;
        }

        // 1.2 Lọc theo Notification Key / ID trong 3.5 giây
        String keyIdentifier = (notifKey != null && !notifKey.trim().isEmpty()) ? notifKey.trim() : ("id_" + notifId);
        Long lastKeyTime = recentNotifKeyCache.get(keyIdentifier);
        if (lastKeyTime != null && (now - lastKeyTime < 3500L)) {
            Log.d(TAG, "Debounced: Duplicate notification key/id ignored -> " + keyIdentifier);
            return true;
        }
        recentNotifKeyCache.put(keyIdentifier, now);

        // 1.3 Lọc theo Fingerprint nội dung đã chuẩn hóa trong 6.0 giây
        String cleanSender = senderOrGroup != null ? senderOrGroup.trim().toLowerCase() : "";
        String cleanText = NotificationClassifier.cleanMessageContent(messageText).toLowerCase();
        String fingerprint = cleanSender + "||" + cleanText;

        if (!fingerprint.equals("||")) {
            Long lastFpTime = recentFingerprintCache.get(fingerprint);
            if (lastFpTime != null && (now - lastFpTime < 6000L)) {
                Log.d(TAG, "Debounced: Duplicate content fingerprint ignored -> " + fingerprint);
                return true;
            }
            recentFingerprintCache.put(fingerprint, now);
        }

        // Dọn dẹp cache định kỳ
        cleanupCache(now);

        // ----------------------------------------------------
        // TẦNG 2: CHỐNG SPAM THEO CẤU HÌNH NGƯỜI DÙNG (USER ANTI-SPAM SETTING)
        // ----------------------------------------------------
        if (prefs.isAntiSpamEnabled()) {
            long debounceMs = prefs.getAntiSpamSeconds() * 1000L;
            if (debounceMs < 2000L) {
                debounceMs = 4000L;
            }

            if (!cleanSender.isEmpty()) {
                Long lastSenderTime = lastSenderSpamTimes.get(cleanSender);
                if (lastSenderTime != null && (now - lastSenderTime < debounceMs)) {
                    Log.d(TAG, "Anti-spam: Debounced consecutive message from: " + senderOrGroup + " (cooldown " + debounceMs + "ms)");
                    return true;
                }
                lastSenderSpamTimes.put(cleanSender, now);
            }
        }

        // Đạt chuẩn -> Cập nhật mốc phát âm thanh gần nhất
        lastAudioPlayTimestamp = now;
        return false;
    }

    private void cleanupCache(long now) {
        if (recentNotifKeyCache.size() > 50) {
            Iterator<Map.Entry<String, Long>> it = recentNotifKeyCache.entrySet().iterator();
            while (it.hasNext()) {
                if (now - it.next().getValue() > 10000L) {
                    it.remove();
                }
            }
        }

        if (recentFingerprintCache.size() > 50) {
            Iterator<Map.Entry<String, Long>> it = recentFingerprintCache.entrySet().iterator();
            while (it.hasNext()) {
                if (now - it.next().getValue() > 15000L) {
                    it.remove();
                }
            }
        }

        if (lastSenderSpamTimes.size() > 50) {
            Iterator<Map.Entry<String, Long>> it = lastSenderSpamTimes.entrySet().iterator();
            while (it.hasNext()) {
                if (now - it.next().getValue() > 30000L) {
                    it.remove();
                }
            }
        }
    }

    /**
     * Phát âm thanh cho Tin nhắn Cá nhân (1-1)
     */
    public synchronized void playDirectMessageSound(String notifKey, int notifId, String sender, String text) {
        if (!prefs.isDirectEnabled()) {
            Log.d(TAG, "Direct sound disabled in settings.");
            return;
        }

        if (shouldDebounce(notifKey, notifId, sender, text)) {
            return;
        }

        int soundIndex = prefs.getDirectSoundIndex();
        String customUri = prefs.getDirectCustomUri();

        playSound(soundIndex, customUri);
        triggerVibrateIfEnabled();
    }

    public synchronized void playDirectMessageSound(String sender, String text) {
        playDirectMessageSound(null, 0, sender, text);
    }

    public synchronized void playDirectMessageSound() {
        playDirectMessageSound(null, 0, "", "");
    }

    /**
     * Phát âm thanh riêng cho Contact VIP cụ thể
     */
    public synchronized void playContactSound(String notifKey, int notifId, int soundIndex, String customUri, String sender, String text) {
        if (shouldDebounce(notifKey, notifId, sender, text)) {
            return;
        }

        playSound(soundIndex, customUri);
        triggerVibrateIfEnabled();
    }

    public synchronized void playContactSound(int soundIndex, String customUri, String sender, String text) {
        playContactSound(null, 0, soundIndex, customUri, sender, text);
    }

    public synchronized void playContactSound(int soundIndex, String customUri) {
        playContactSound(null, 0, soundIndex, customUri, "", "");
    }

    /**
     * Phát âm thanh cho Tin nhắn Nhóm (Group)
     */
    public synchronized void playGroupMessageSound(String notifKey, int notifId, String groupTitle, String text) {
        if (!prefs.isGroupEnabled()) {
            Log.d(TAG, "Group sound disabled in settings.");
            return;
        }

        if (shouldDebounce(notifKey, notifId, groupTitle, text)) {
            return;
        }

        int soundIndex = prefs.getGroupSoundIndex();
        String customUri = prefs.getGroupCustomUri();

        playSound(soundIndex, customUri);
        triggerVibrateIfEnabled();
    }

    public synchronized void playGroupMessageSound(String groupTitle, String text) {
        playGroupMessageSound(null, 0, groupTitle, text);
    }

    public synchronized void playGroupMessageSound() {
        playGroupMessageSound(null, 0, "", "");
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
