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
    private boolean isSoundPoolLoaded = false;

    // MediaPlayer fallback for custom user-selected audio files
    private MediaPlayer mediaPlayer;
    private Ringtone currentRingtone;

    // Unified Anti-spam / Debounce state
    private long lastGlobalPlayTime = 0;
    private final Map<String, Long> lastSenderPlayTimes = new HashMap<>();
    private final Map<String, Long> recentMessageCache = new HashMap<>();

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
                    .setMaxStreams(4)
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
     * Kiểm tra chống spam & trùng lặp thông báo
     * Trả về true nếu cần CHẶN (debounced), false nếu hợp lệ để phát âm thanh
     */
    private synchronized boolean shouldDebounce(String senderName, String messageText) {
        if (!prefs.isAntiSpamEnabled()) {
            return false;
        }

        long now = System.currentTimeMillis();
        long debounceMs = prefs.getAntiSpamSeconds() * 1000L;
        if (debounceMs < 2000L) {
            debounceMs = 4000L; // Tối thiểu 4 giây nếu cấu hình không hợp lệ
        }

        // 1. Kiểm tra thông báo trùng lặp tuyệt đối (Cùng người gửi + cùng nội dung trong vòng 10 giây)
        String contentKey = (senderName != null ? senderName.trim() : "") + "||" + (messageText != null ? messageText.trim() : "");
        if (!contentKey.equals("||")) {
            Long lastContentTime = recentMessageCache.get(contentKey);
            if (lastContentTime != null && (now - lastContentTime < 10000L)) {
                Log.d(TAG, "Anti-spam: Duplicate notification content ignored -> " + contentKey);
                return true;
            }
            recentMessageCache.put(contentKey, now);
        }

        // Dọn dẹp cache nếu danh sách quá lớn
        if (recentMessageCache.size() > 50) {
            Iterator<Map.Entry<String, Long>> it = recentMessageCache.entrySet().iterator();
            while (it.hasNext()) {
                if (now - it.next().getValue() > 30000L) {
                    it.remove();
                }
            }
        }

        // 2. Chặn toàn cục (Global Debounce) - Bất kỳ âm thanh nào đã phát trong khoảng debounceMs đều chặn
        if (now - lastGlobalPlayTime < debounceMs) {
            Log.d(TAG, "Anti-spam: Global debounced (elapsed " + (now - lastGlobalPlayTime) + "ms < " + debounceMs + "ms).");
            return true;
        }

        // 3. Chặn theo người gửi cụ thể (Per-sender Debounce)
        if (senderName != null && !senderName.trim().isEmpty()) {
            String cleanSender = senderName.trim().toLowerCase();
            Long lastSenderTime = lastSenderPlayTimes.get(cleanSender);
            if (lastSenderTime != null && (now - lastSenderTime < debounceMs)) {
                Log.d(TAG, "Anti-spam: Debounced consecutive message from sender: " + senderName);
                return true;
            }
            lastSenderPlayTimes.put(cleanSender, now);
        }

        lastGlobalPlayTime = now;
        return false;
    }

    /**
     * Phát âm thanh cho Tin nhắn Cá nhân (1-1)
     */
    public synchronized void playDirectMessageSound(String sender, String text) {
        if (!prefs.isDirectEnabled()) {
            Log.d(TAG, "Direct sound disabled in settings.");
            return;
        }

        if (shouldDebounce(sender, text)) {
            return;
        }

        int soundIndex = prefs.getDirectSoundIndex();
        String customUri = prefs.getDirectCustomUri();

        playSound(soundIndex, customUri);
        triggerVibrateIfEnabled();
    }

    public synchronized void playDirectMessageSound() {
        playDirectMessageSound("", "");
    }

    /**
     * Phát âm thanh riêng cho Contact VIP cụ thể
     */
    public synchronized void playContactSound(int soundIndex, String customUri, String sender, String text) {
        if (shouldDebounce(sender, text)) {
            return;
        }

        playSound(soundIndex, customUri);
        triggerVibrateIfEnabled();
    }

    public synchronized void playContactSound(int soundIndex, String customUri) {
        playContactSound(soundIndex, customUri, "", "");
    }

    /**
     * Phát âm thanh cho Tin nhắn Nhóm (Group)
     */
    public synchronized void playGroupMessageSound(String groupTitle, String text) {
        if (!prefs.isGroupEnabled()) {
            Log.d(TAG, "Group sound disabled in settings.");
            return;
        }

        if (shouldDebounce(groupTitle, text)) {
            return;
        }

        int soundIndex = prefs.getGroupSoundIndex();
        String customUri = prefs.getGroupCustomUri();

        playSound(soundIndex, customUri);
        triggerVibrateIfEnabled();
    }

    public synchronized void playGroupMessageSound() {
        playGroupMessageSound("", "");
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

    private void playSound(int soundIndex, String customUri) {
        if (soundIndex == SOUND_MUTE) {
            Log.d(TAG, "Sound is set to MUTE");
            return;
        }

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
                    return;
                }
            }
        }

        // 3. Fallback sang MediaPlayer nếu SoundPool chưa sẵn sàng
        playFallbackRawSound(soundIndex);
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
