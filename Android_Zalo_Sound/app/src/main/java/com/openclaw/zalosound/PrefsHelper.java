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
    private static final String KEY_ANTI_SPAM_SECONDS = "key_anti_spam_seconds";
    private static final String KEY_VIBRATE_ENABLED = "key_vibrate_enabled";
    private static final String KEY_CONTACT_RULES = "key_contact_rules_json";

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

    public int getAntiSpamSeconds() {
        return prefs.getInt(KEY_ANTI_SPAM_SECONDS, 4); // Mặc định 4 giây
    }

    public void setAntiSpamSeconds(int seconds) {
        prefs.edit().putInt(KEY_ANTI_SPAM_SECONDS, seconds).apply();
    }

    public boolean isVibrateEnabled() {
        return prefs.getBoolean(KEY_VIBRATE_ENABLED, false);
    }

    public void setVibrateEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_VIBRATE_ENABLED, enabled).apply();
    }

    // ==================== VIP CONTACT RULES ====================

    public java.util.List<ContactRule> getContactRules() {
        java.util.List<ContactRule> list = new java.util.ArrayList<>();
        String jsonStr = prefs.getString(KEY_CONTACT_RULES, "");
        if (jsonStr == null || jsonStr.trim().isEmpty()) {
            return list;
        }
        try {
            org.json.JSONArray array = new org.json.JSONArray(jsonStr);
            for (int i = 0; i < array.length(); i++) {
                org.json.JSONObject obj = array.optJSONObject(i);
                if (obj != null) {
                    ContactRule rule = ContactRule.fromJson(obj);
                    if (rule != null) {
                        list.add(rule);
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return list;
    }

    public void saveContactRules(java.util.List<ContactRule> list) {
        if (list == null) return;
        org.json.JSONArray array = new org.json.JSONArray();
        for (ContactRule rule : list) {
            if (rule != null) {
                array.put(rule.toJson());
            }
        }
        prefs.edit().putString(KEY_CONTACT_RULES, array.toString()).apply();
    }

    public void addOrUpdateContactRule(ContactRule rule) {
        if (rule == null) return;
        java.util.List<ContactRule> list = getContactRules();
        boolean found = false;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).getId().equals(rule.getId())) {
                list.set(i, rule);
                found = true;
                break;
            }
        }
        if (!found) {
            list.add(rule);
        }
        saveContactRules(list);
    }

    public void deleteContactRule(String ruleId) {
        if (ruleId == null) return;
        java.util.List<ContactRule> list = getContactRules();
        java.util.Iterator<ContactRule> it = list.iterator();
        while (it.hasNext()) {
            ContactRule rule = it.next();
            if (ruleId.equals(rule.getId())) {
                it.remove();
                break;
            }
        }
        saveContactRules(list);
    }

    public ContactRule findMatchingRule(String senderName) {
        if (senderName == null || senderName.trim().isEmpty()) {
            return null;
        }
        java.util.List<ContactRule> list = getContactRules();
        for (ContactRule rule : list) {
            if (rule.isEnabled() && rule.matchesSender(senderName)) {
                return rule;
            }
        }
        return null;
    }
}
