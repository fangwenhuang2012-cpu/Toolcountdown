package com.openclaw.zalosound;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.cardview.widget.CardView;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private PrefsHelper prefs;
    private SoundManager soundManager;

    private TextView tvServiceStatusBadge;
    private CardView cardPermission;
    private Button btnGrantPermission;

    private SwitchCompat switchDirect;
    private Spinner spinnerDirectSound;
    private Button btnTestDirectSound;
    private Button btnPickDirectCustom;

    // VIP Contacts UI
    private TextView tvVipEmptyState;
    private LinearLayout containerVipContacts;
    private Button btnAddVipContact;

    private SwitchCompat switchGroup;
    private Spinner spinnerGroupSound;
    private Button btnTestGroupSound;
    private Button btnPickGroupCustom;

    private SwitchCompat switchAntiSpam;
    private SwitchCompat switchVibrate;
    private Button btnOpenZaloSettings;

    private boolean isDirectCustomPending = false;
    private boolean isGroupCustomPending = false;
    private boolean isContactCustomPending = false;

    // Dialog state holders for custom audio picker
    private Spinner currentDialogSpinner = null;
    private TextView currentDialogTvCustom = null;
    private String currentDialogCustomUri = "";
    private String currentDialogCustomSoundName = "";

    private final ActivityResultLauncher<Intent> ringtonePickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI);
                    if (uri == null) {
                        uri = result.getData().getData();
                    }
                    if (uri != null) {
                        String uriString = uri.toString();
                        if (isDirectCustomPending) {
                            prefs.setDirectCustomUri(uriString);
                            prefs.setDirectSoundIndex(SoundManager.SOUND_CUSTOM_FILE);
                            spinnerDirectSound.setSelection(SoundManager.SOUND_CUSTOM_FILE);
                            Toast.makeText(this, "Đã chọn chuông 1-1 từ máy!", Toast.LENGTH_SHORT).show();
                        } else if (isGroupCustomPending) {
                            prefs.setGroupCustomUri(uriString);
                            prefs.setGroupSoundIndex(SoundManager.SOUND_CUSTOM_FILE);
                            spinnerGroupSound.setSelection(SoundManager.SOUND_CUSTOM_FILE);
                            Toast.makeText(this, "Đã chọn chuông Nhóm từ máy!", Toast.LENGTH_SHORT).show();
                        } else if (isContactCustomPending) {
                            currentDialogCustomUri = uriString;
                            try {
                                Ringtone r = RingtoneManager.getRingtone(this, uri);
                                if (r != null) {
                                    currentDialogCustomSoundName = r.getTitle(this);
                                } else {
                                    currentDialogCustomSoundName = uri.getLastPathSegment();
                                }
                            } catch (Exception e) {
                                currentDialogCustomSoundName = "File âm thanh";
                            }
                            if (currentDialogSpinner != null) {
                                currentDialogSpinner.setSelection(SoundManager.SOUND_CUSTOM_FILE);
                            }
                            if (currentDialogTvCustom != null) {
                                currentDialogTvCustom.setText("File: " + currentDialogCustomSoundName);
                                currentDialogTvCustom.setVisibility(View.VISIBLE);
                            }
                            Toast.makeText(this, "Đã chọn chuông cho người này!", Toast.LENGTH_SHORT).show();
                        }
                    }
                }
                isDirectCustomPending = false;
                isGroupCustomPending = false;
                isContactCustomPending = false;
            }
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = new PrefsHelper(this);
        soundManager = SoundManager.getInstance(this);

        initViews();
        setupSoundSpinners();
        setupListeners();
        renderVipContacts();
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkNotificationPermission();
    }

    private void initViews() {
        tvServiceStatusBadge = findViewById(R.id.tvServiceStatusBadge);
        cardPermission = findViewById(R.id.cardPermission);
        btnGrantPermission = findViewById(R.id.btnGrantPermission);

        switchDirect = findViewById(R.id.switchDirect);
        spinnerDirectSound = findViewById(R.id.spinnerDirectSound);
        btnTestDirectSound = findViewById(R.id.btnTestDirectSound);
        btnPickDirectCustom = findViewById(R.id.btnPickDirectCustom);

        tvVipEmptyState = findViewById(R.id.tvVipEmptyState);
        containerVipContacts = findViewById(R.id.containerVipContacts);
        btnAddVipContact = findViewById(R.id.btnAddVipContact);

        switchGroup = findViewById(R.id.switchGroup);
        spinnerGroupSound = findViewById(R.id.spinnerGroupSound);
        btnTestGroupSound = findViewById(R.id.btnTestGroupSound);
        btnPickGroupCustom = findViewById(R.id.btnPickGroupCustom);

        switchAntiSpam = findViewById(R.id.switchAntiSpam);
        switchVibrate = findViewById(R.id.switchVibrate);
        btnOpenZaloSettings = findViewById(R.id.btnOpenZaloSettings);

        // Load saved preferences
        switchDirect.setChecked(prefs.isDirectEnabled());
        switchGroup.setChecked(prefs.isGroupEnabled());
        switchAntiSpam.setChecked(prefs.isAntiSpamEnabled());
        switchVibrate.setChecked(prefs.isVibrateEnabled());
    }

    private List<String> getSoundOptionsList() {
        List<String> soundOptions = new ArrayList<>();
        soundOptions.add(getString(R.string.sound_builtin_zalo));
        soundOptions.add(getString(R.string.sound_builtin_ting));
        soundOptions.add(getString(R.string.sound_builtin_iphone));
        soundOptions.add(getString(R.string.sound_builtin_pop));
        soundOptions.add(getString(R.string.sound_builtin_mute));
        soundOptions.add(getString(R.string.sound_custom_device));
        return soundOptions;
    }

    private void setupSoundSpinners() {
        List<String> soundOptions = getSoundOptionsList();

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, soundOptions);

        spinnerDirectSound.setAdapter(adapter);
        spinnerGroupSound.setAdapter(adapter);

        int directIdx = prefs.getDirectSoundIndex();
        if (directIdx >= 0 && directIdx < soundOptions.size()) {
            spinnerDirectSound.setSelection(directIdx);
        }

        int groupIdx = prefs.getGroupSoundIndex();
        if (groupIdx >= 0 && groupIdx < soundOptions.size()) {
            spinnerGroupSound.setSelection(groupIdx);
        }
    }

    private void setupListeners() {
        btnGrantPermission.setOnClickListener(v -> openNotificationAccessSettings());

        // 1-1 Controls
        switchDirect.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.setDirectEnabled(isChecked);
            spinnerDirectSound.setEnabled(isChecked);
            btnTestDirectSound.setEnabled(isChecked);
            btnPickDirectCustom.setEnabled(isChecked);
        });

        spinnerDirectSound.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position == SoundManager.SOUND_CUSTOM_FILE && TextUtils.isEmpty(prefs.getDirectCustomUri())) {
                    pickCustomRingtone(1);
                } else {
                    prefs.setDirectSoundIndex(position);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        btnTestDirectSound.setOnClickListener(v -> {
            int selected = spinnerDirectSound.getSelectedItemPosition();
            soundManager.testSound(selected, prefs.getDirectCustomUri());
        });

        btnPickDirectCustom.setOnClickListener(v -> pickCustomRingtone(1));

        // VIP Contacts Controls
        btnAddVipContact.setOnClickListener(v -> showContactRuleDialog(null));

        // Group Controls
        switchGroup.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.setGroupEnabled(isChecked);
            spinnerGroupSound.setEnabled(isChecked);
            btnTestGroupSound.setEnabled(isChecked);
            btnPickGroupCustom.setEnabled(isChecked);
        });

        spinnerGroupSound.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position == SoundManager.SOUND_CUSTOM_FILE && TextUtils.isEmpty(prefs.getGroupCustomUri())) {
                    pickCustomRingtone(2);
                } else {
                    prefs.setGroupSoundIndex(position);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        btnTestGroupSound.setOnClickListener(v -> {
            int selected = spinnerGroupSound.getSelectedItemPosition();
            soundManager.testSound(selected, prefs.getGroupCustomUri());
        });

        btnPickGroupCustom.setOnClickListener(v -> pickCustomRingtone(2));

        // Advanced Switches
        switchAntiSpam.setOnCheckedChangeListener((buttonView, isChecked) -> prefs.setAntiSpamEnabled(isChecked));
        switchVibrate.setOnCheckedChangeListener((buttonView, isChecked) -> prefs.setVibrateEnabled(isChecked));

        // Zalo System Settings button
        btnOpenZaloSettings.setOnClickListener(v -> openZaloSystemSettings());
    }

    // ==================== VIP CONTACTS MANAGEMENT ====================

    private void renderVipContacts() {
        List<ContactRule> rules = prefs.getContactRules();
        containerVipContacts.removeAllViews();

        if (rules.isEmpty()) {
            tvVipEmptyState.setVisibility(View.VISIBLE);
        } else {
            tvVipEmptyState.setVisibility(View.GONE);
            LayoutInflater inflater = LayoutInflater.from(this);

            for (ContactRule rule : rules) {
                View itemView = inflater.inflate(R.layout.item_contact_rule, containerVipContacts, false);

                TextView tvItemContactName = itemView.findViewById(R.id.tvItemContactName);
                TextView tvItemSoundName = itemView.findViewById(R.id.tvItemSoundName);
                SwitchCompat switchItemEnable = itemView.findViewById(R.id.switchItemEnable);
                Button btnItemTestSound = itemView.findViewById(R.id.btnItemTestSound);
                Button btnItemEdit = itemView.findViewById(R.id.btnItemEdit);
                Button btnItemDelete = itemView.findViewById(R.id.btnItemDelete);

                tvItemContactName.setText(rule.getContactName());
                String soundName = soundManager.getSoundDisplayName(rule.getSoundIndex(), rule.getCustomSoundName());
                tvItemSoundName.setText("Chuông: " + soundName);

                switchItemEnable.setChecked(rule.isEnabled());
                switchItemEnable.setOnCheckedChangeListener((buttonView, isChecked) -> {
                    rule.setEnabled(isChecked);
                    prefs.addOrUpdateContactRule(rule);
                });

                btnItemTestSound.setOnClickListener(v -> soundManager.testSound(rule.getSoundIndex(), rule.getCustomUri()));

                btnItemEdit.setOnClickListener(v -> showContactRuleDialog(rule));

                btnItemDelete.setOnClickListener(v -> {
                    new AlertDialog.Builder(this)
                            .setTitle("Xóa chuông liên hệ")
                            .setMessage("Bạn có chắc chắn muốn xóa cài đặt chuông cho '" + rule.getContactName() + "'?")
                            .setPositiveButton("Xóa", (dialog, which) -> {
                                prefs.deleteContactRule(rule.getId());
                                renderVipContacts();
                                Toast.makeText(this, R.string.toast_deleted_contact_rule, Toast.LENGTH_SHORT).show();
                            })
                            .setNegativeButton("Hủy", null)
                            .show();
                });

                containerVipContacts.addView(itemView);
            }
        }
    }

    private void showContactRuleDialog(ContactRule existingRule) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_contact_rule, null);
        builder.setView(dialogView);

        AlertDialog dialog = builder.create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        TextView tvDialogTitle = dialogView.findViewById(R.id.tvDialogTitle);
        EditText etContactName = dialogView.findViewById(R.id.etContactName);
        Spinner spinnerDialogSound = dialogView.findViewById(R.id.spinnerDialogSound);
        TextView tvDialogCustomFileName = dialogView.findViewById(R.id.tvDialogCustomFileName);
        Button btnDialogTestSound = dialogView.findViewById(R.id.btnDialogTestSound);
        Button btnDialogPickCustom = dialogView.findViewById(R.id.btnDialogPickCustom);
        Button btnDialogCancel = dialogView.findViewById(R.id.btnDialogCancel);
        Button btnDialogSave = dialogView.findViewById(R.id.btnDialogSave);

        // Setup Spinner
        List<String> soundOptions = getSoundOptionsList();
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, soundOptions);
        spinnerDialogSound.setAdapter(adapter);

        currentDialogSpinner = spinnerDialogSound;
        currentDialogTvCustom = tvDialogCustomFileName;

        if (existingRule != null) {
            tvDialogTitle.setText(R.string.dialog_edit_vip_title);
            etContactName.setText(existingRule.getContactName());
            currentDialogCustomUri = existingRule.getCustomUri();
            currentDialogCustomSoundName = existingRule.getCustomSoundName();

            int soundIdx = existingRule.getSoundIndex();
            if (soundIdx >= 0 && soundIdx < soundOptions.size()) {
                spinnerDialogSound.setSelection(soundIdx);
            }
            if (soundIdx == SoundManager.SOUND_CUSTOM_FILE && !TextUtils.isEmpty(currentDialogCustomSoundName)) {
                tvDialogCustomFileName.setText("File: " + currentDialogCustomSoundName);
                tvDialogCustomFileName.setVisibility(View.VISIBLE);
            }
        } else {
            tvDialogTitle.setText(R.string.dialog_add_vip_title);
            currentDialogCustomUri = "";
            currentDialogCustomSoundName = "";
            spinnerDialogSound.setSelection(SoundManager.SOUND_DING_SOFT);
            tvDialogCustomFileName.setVisibility(View.GONE);
        }

        spinnerDialogSound.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position == SoundManager.SOUND_CUSTOM_FILE && TextUtils.isEmpty(currentDialogCustomUri)) {
                    pickCustomRingtone(3);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        btnDialogPickCustom.setOnClickListener(v -> pickCustomRingtone(3));

        btnDialogTestSound.setOnClickListener(v -> {
            int selected = spinnerDialogSound.getSelectedItemPosition();
            soundManager.testSound(selected, currentDialogCustomUri);
        });

        btnDialogCancel.setOnClickListener(v -> dialog.dismiss());

        btnDialogSave.setOnClickListener(v -> {
            String contactName = etContactName.getText().toString().trim();
            if (contactName.isEmpty()) {
                Toast.makeText(this, R.string.toast_empty_contact_name, Toast.LENGTH_SHORT).show();
                return;
            }

            int selectedSound = spinnerDialogSound.getSelectedItemPosition();
            ContactRule ruleToSave;
            if (existingRule != null) {
                ruleToSave = existingRule;
                ruleToSave.setContactName(contactName);
                ruleToSave.setSoundIndex(selectedSound);
                ruleToSave.setCustomUri(currentDialogCustomUri);
                ruleToSave.setCustomSoundName(currentDialogCustomSoundName);
            } else {
                ruleToSave = new ContactRule(contactName, selectedSound, currentDialogCustomUri, currentDialogCustomSoundName, true);
            }

            prefs.addOrUpdateContactRule(ruleToSave);
            renderVipContacts();
            Toast.makeText(this, R.string.toast_saved_contact_rule, Toast.LENGTH_SHORT).show();
            dialog.dismiss();
        });

        dialog.show();
    }

    // ==================== SYSTEM & HELPER METHODS ====================

    private boolean isNotificationServiceEnabled() {
        String pkgName = getPackageName();
        final String flat = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        if (!TextUtils.isEmpty(flat)) {
            final String[] names = flat.split(":");
            for (String name : names) {
                final ComponentName cn = ComponentName.unflattenFromString(name);
                if (cn != null && TextUtils.equals(pkgName, cn.getPackageName())) {
                    return true;
                }
            }
        }
        return false;
    }

    private void checkNotificationPermission() {
        boolean isGranted = isNotificationServiceEnabled();
        if (isGranted) {
            tvServiceStatusBadge.setText("● ĐANG HOẠT ĐỘNG");
            tvServiceStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.status_active));
            cardPermission.setVisibility(View.GONE);
        } else {
            tvServiceStatusBadge.setText("● CHƯA CẤP QUYỀN");
            tvServiceStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.status_inactive));
            cardPermission.setVisibility(View.VISIBLE);
        }
    }

    private void openNotificationAccessSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Không thể mở cài đặt cấp quyền", Toast.LENGTH_SHORT).show();
        }
    }

    private void openZaloSystemSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:com.zing.zalo"));
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Không tìm thấy ứng dụng Zalo trên máy", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Mode:
     * 1: Direct 1-1 custom ringtone
     * 2: Group custom ringtone
     * 3: VIP Contact custom ringtone
     */
    private void pickCustomRingtone(int mode) {
        isDirectCustomPending = (mode == 1);
        isGroupCustomPending = (mode == 2);
        isContactCustomPending = (mode == 3);

        String title;
        if (mode == 1) {
            title = "Chọn chuông tin nhắn 1-1";
        } else if (mode == 2) {
            title = "Chọn chuông tin nhắn Nhóm";
        } else {
            title = "Chọn chuông riêng cho liên hệ";
        }

        Intent intent = new Intent(RingtoneManager.ACTION_RINGTONE_PICKER);
        intent.putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION);
        intent.putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, title);
        intent.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true);
        intent.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true);

        try {
            ringtonePickerLauncher.launch(intent);
        } catch (Exception e) {
            Intent audioPicker = new Intent(Intent.ACTION_GET_CONTENT);
            audioPicker.setType("audio/*");
            ringtonePickerLauncher.launch(audioPicker);
        }
    }
}
