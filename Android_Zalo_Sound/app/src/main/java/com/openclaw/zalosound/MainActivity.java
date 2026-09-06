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
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
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

    private SwitchCompat switchGroup;
    private Spinner spinnerGroupSound;
    private Button btnTestGroupSound;
    private Button btnPickGroupCustom;

    private SwitchCompat switchAntiSpam;
    private SwitchCompat switchVibrate;
    private Button btnOpenZaloSettings;

    private boolean isDirectCustomPending = false;
    private boolean isGroupCustomPending = false;

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
                        }
                    }
                }
                isDirectCustomPending = false;
                isGroupCustomPending = false;
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

    private void setupSoundSpinners() {
        List<String> soundOptions = new ArrayList<>();
        soundOptions.add(getString(R.string.sound_builtin_zalo));
        soundOptions.add(getString(R.string.sound_builtin_ting));
        soundOptions.add(getString(R.string.sound_builtin_iphone));
        soundOptions.add(getString(R.string.sound_builtin_pop));
        soundOptions.add(getString(R.string.sound_builtin_mute));
        soundOptions.add(getString(R.string.sound_custom_device));

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
                    pickCustomRingtone(true);
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

        btnPickDirectCustom.setOnClickListener(v -> pickCustomRingtone(true));

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
                    pickCustomRingtone(false);
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

        btnPickGroupCustom.setOnClickListener(v -> pickCustomRingtone(false));

        // Advanced Switches
        switchAntiSpam.setOnCheckedChangeListener((buttonView, isChecked) -> prefs.setAntiSpamEnabled(isChecked));
        switchVibrate.setOnCheckedChangeListener((buttonView, isChecked) -> prefs.setVibrateEnabled(isChecked));

        // Zalo System Settings button
        btnOpenZaloSettings.setOnClickListener(v -> openZaloSystemSettings());
    }

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

    private void pickCustomRingtone(boolean forDirect) {
        isDirectCustomPending = forDirect;
        isGroupCustomPending = !forDirect;

        Intent intent = new Intent(RingtoneManager.ACTION_RINGTONE_PICKER);
        intent.putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION);
        intent.putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, forDirect ? "Chọn chuông tin nhắn 1-1" : "Chọn chuông tin nhắn Nhóm");
        intent.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true);
        intent.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true);

        try {
            ringtonePickerLauncher.launch(intent);
        } catch (Exception e) {
            // Fallback to general audio picker
            Intent audioPicker = new Intent(Intent.ACTION_GET_CONTENT);
            audioPicker.setType("audio/*");
            ringtonePickerLauncher.launch(audioPicker);
        }
    }
}
