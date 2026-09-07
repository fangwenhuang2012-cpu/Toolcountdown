package com.openclaw.zalosound;

import org.json.JSONException;
import org.json.JSONObject;
import java.util.UUID;

public class ContactRule {
    private String id;
    private String contactName;
    private int soundIndex;
    private String customUri;
    private String customSoundName;
    private boolean enabled;

    public ContactRule() {
        this.id = UUID.randomUUID().toString();
        this.contactName = "";
        this.soundIndex = SoundManager.SOUND_DING_SOFT;
        this.customUri = "";
        this.customSoundName = "";
        this.enabled = true;
    }

    public ContactRule(String contactName, int soundIndex, String customUri, String customSoundName, boolean enabled) {
        this.id = UUID.randomUUID().toString();
        this.contactName = contactName != null ? contactName.trim() : "";
        this.soundIndex = soundIndex;
        this.customUri = customUri != null ? customUri : "";
        this.customSoundName = customSoundName != null ? customSoundName : "";
        this.enabled = enabled;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getContactName() {
        return contactName;
    }

    public void setContactName(String contactName) {
        this.contactName = contactName != null ? contactName.trim() : "";
    }

    public int getSoundIndex() {
        return soundIndex;
    }

    public void setSoundIndex(int soundIndex) {
        this.soundIndex = soundIndex;
    }

    public String getCustomUri() {
        return customUri;
    }

    public void setCustomUri(String customUri) {
        this.customUri = customUri != null ? customUri : "";
    }

    public String getCustomSoundName() {
        return customSoundName;
    }

    public void setCustomSoundName(String customSoundName) {
        this.customSoundName = customSoundName != null ? customSoundName : "";
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean matchesSender(String sender) {
        if (sender == null || contactName == null || contactName.trim().isEmpty()) {
            return false;
        }
        String cleanSender = sender.trim().toLowerCase();
        String cleanContact = contactName.trim().toLowerCase();

        if (cleanSender.equals(cleanContact)) {
            return true;
        }

        // Khớp thông minh nếu tên người gửi trên Zalo có kèm emoji, biệt danh hoặc danh xưng
        if (cleanContact.length() >= 2 && cleanSender.contains(cleanContact)) {
            return true;
        }
        if (cleanSender.length() >= 2 && cleanContact.contains(cleanSender)) {
            return true;
        }

        return false;
    }

    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("id", id);
            json.put("contactName", contactName);
            json.put("soundIndex", soundIndex);
            json.put("customUri", customUri);
            json.put("customSoundName", customSoundName);
            json.put("enabled", enabled);
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return json;
    }

    public static ContactRule fromJson(JSONObject json) {
        if (json == null) return null;
        ContactRule rule = new ContactRule();
        rule.id = json.optString("id", UUID.randomUUID().toString());
        rule.contactName = json.optString("contactName", "");
        rule.soundIndex = json.optInt("soundIndex", SoundManager.SOUND_DING_SOFT);
        rule.customUri = json.optString("customUri", "");
        rule.customSoundName = json.optString("customSoundName", "");
        rule.enabled = json.optBoolean("enabled", true);
        return rule;
    }
}
