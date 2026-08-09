package com.openclaw.countdown;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.content.pm.PackageManager;

public class MainActivity extends Activity {
    private static final int CODE_DRAW_OVER_OTHER_APP_PERMISSION = 2084;
    private static final int CODE_LOCATION_PERMISSION = 2085;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Vui lòng cấp quyền 'Vẽ lên ứng dụng khác' (Overlay) để Đếm Ngược AI VietMap hiển thị", Toast.LENGTH_LONG).show();
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, CODE_DRAW_OVER_OTHER_APP_PERMISSION);
        } else {
            checkLocationPermissionAndStart();
        }
    }

    private void checkLocationPermissionAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Vui lòng cấp quyền Vị trí (GPS) để AI tự động đo tốc độ xe dừng hẳn", Toast.LENGTH_LONG).show();
                requestPermissions(new String[]{
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                }, CODE_LOCATION_PERMISSION);
                return;
            }
        }
        startFloatingService();
    }

    private void startFloatingService() {
        // Tự động dò và gợi ý kết nối Wi-Fi Camera VietMap khi mở ứng dụng
        VietMapWifiScanner wifiScanner = new VietMapWifiScanner(this, new VietMapWifiScanner.WifiScanListener() {
            @Override
            public void onVietMapCamFound(String ssid, int signalLevel) {
                Toast.makeText(MainActivity.this, "Tự động phát hiện Camera VietMap: " + ssid, Toast.LENGTH_LONG).show();
            }

            @Override
            public void onConnectedToVietMapCam(String ssid) {
                Toast.makeText(MainActivity.this, "Đã kết nối ngầm tới Camera: " + ssid, Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onError(String errorMsg) {}
        });
        wifiScanner.scanForVietMapCam();

        Intent intent = new Intent(MainActivity.this, FloatingService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(this, intent);
        } else {
            startService(intent);
        }
        finish();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == CODE_LOCATION_PERMISSION) {
            // Proceed to start floating service regardless of location permission result (with fallback)
            startFloatingService();
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == CODE_DRAW_OVER_OTHER_APP_PERMISSION) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
                checkLocationPermissionAndStart();
            } else {
                Toast.makeText(this, "Cần cấp quyền Overlay để ứng dụng Đếm Ngược AI VietMap hiển thị!", Toast.LENGTH_LONG).show();
                finish();
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }
}
