package com.openclaw.zalosound;

import android.content.Context;
import android.content.SharedPreferences;

public class PrefsHelper {
    private static final String PREF_NAME = "zalo_sound_pro_prefs";

    // Keys
    private static final String KEY_DIRECT_ENABLED = "key_direct_enabled";
    private static final String KEY_DIRECT_SOUND_INDEX = "key_direct_sound_index";
    private static final String KEY_DIRECT_CUSTOM_URI = "key_direct_custom_uri";

    private static final String KEY_GROUP_ENABLED = "key_group_enabled";
    private static final String KEY_GROUP_SOUND_INDEX = "key_group_sound_index";
    private static final String KEY_GROUP_CUSTOM_URI = "key_group_custom_uri";

    private static final String KEY_ANTI_SPAM_ENABLED = "key_anti_spam_enabled";
    private static final String KEY_VIBRATE_ENABLED = "key_vibrate_enabled";

    private final SharedPreferences prefs;

    public PrefsHelper(Context context) {
        this.prefs = context.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public boolean isDirectEnabled() {
        return prefs.getBoolean(KEY_DIRECT_ENABLED, true);
    }

    public void setDirectEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_DIRECT_ENABLED, enabled).apply();
    }

    public int getDirectSoundIndex() {
        return prefs.getInt(KEY_DIRECT_SOUND_INDEX, 0); // Default: Sound 0 (Zalo Classic)
    }

    public void setDirectSoundIndex(int index) {
        prefs.edit().putInt(KEY_DIRECT_SOUND_INDEX, index).apply();
    }

    public String getDirectCustomUri() {
        return prefs.getString(KEY_DIRECT_CUSTOM_URI, "");
    }

    public void setDirectCustomUri(String uri) {
        prefs.edit().putString(KEY_DIRECT_CUSTOM_URI, uri).apply();
    }

    public boolean isGroupEnabled() {
        return prefs.getBoolean(KEY_GROUP_ENABLED, true);
    }

    public void setGroupEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_GROUP_ENABLED, enabled).apply();
    }

    public int getGroupSoundIndex() {
        return prefs.getInt(KEY_GROUP_SOUND_INDEX, 1); // Default: Sound 1 (Ting Nhẹ / Pop)
    }

    public void setGroupSoundIndex(int index) {
        prefs.edit().putInt(KEY_GROUP_SOUND_INDEX, index).apply();
    }

    public String getGroupCustomUri() {
        return prefs.getString(KEY_GROUP_CUSTOM_URI, "");
    }

    public void setGroupCustomUri(String uri) {
        prefs.edit().putString(KEY_GROUP_CUSTOM_URI, uri).apply();
    }

    public boolean isAntiSpamEnabled() {
        return prefs.getBoolean(KEY_ANTI_SPAM_ENABLED, true);
    }

    public void setAntiSpamEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_ANTI_SPAM_ENABLED, enabled).apply();
    }

    public boolean isVibrateEnabled() {
        return prefs.getBoolean(KEY_VIBRATE_ENABLED, false);
    }

    public void setVibrateEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_VIBRATE_ENABLED, enabled).apply();
    }
}
