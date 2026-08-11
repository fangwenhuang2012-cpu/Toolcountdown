package com.openclaw.countdown;

import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

public class VehicleSpeedMonitor {
    private static final String TAG = "VehicleSpeedMonitor";
    private static final float STOP_SPEED_THRESHOLD_KMH = 4.0f; // km/h
    private static final float MOVE_SPEED_THRESHOLD_KMH = 7.0f; // km/h
    private static final long STOP_CONFIRM_DELAY_MS = 1200;

    public interface SpeedListener {
        void onVehicleStopped();
        void onVehicleMoving(float speedKmh);
        void onSpeedUpdated(float speedKmh);
    }

    private final Context context;
    private final SpeedListener listener;
    private LocationManager locationManager;
    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback fusedLocationCallback;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private boolean isMonitoring = false;
    private boolean isStopped = true;
    private Location lastLocation = null;
    private Runnable stopConfirmationRunnable;

    // Bộ lọc Kalman Lọc Nhiễu Tốc độ GPS 1 Chiều (1D Kalman Speed Filter)
    private final KalmanSpeedFilter kalmanFilter = new KalmanSpeedFilter();

    private void handleNewLocation(Location location) {
        if (location == null) return;

        // 1. Lọc bỏ tín hiệu vị trí có độ sai số quá cao (> 30m) hoặc định vị trạm phát sóng (Network) kém chính xác
        if (location.hasAccuracy() && location.getAccuracy() > 30.0f) {
            return;
        }
        if (LocationManager.NETWORK_PROVIDER.equals(location.getProvider()) && location.hasAccuracy() && location.getAccuracy() > 15.0f) {
            return;
        }

        float rawSpeedKmh = 0f;

        // 2. Ưu tiên tốc độ thực đo từ phần cứng GPS nếu có
        if (location.hasSpeed() && location.getSpeed() >= 0) {
            rawSpeedKmh = location.getSpeed() * 3.6f;
        } else if (lastLocation != null) {
            // 3. Dự phòng: Tính khoảng cách giữa 2 điểm GPS chuẩn
            float distanceMeters = lastLocation.distanceTo(location);
            long timeDeltaMs = location.getTime() - lastLocation.getTime();

            if (timeDeltaMs >= 300 && timeDeltaMs < 10000 && distanceMeters > 0.3f) {
                float calculatedMps = distanceMeters / (timeDeltaMs / 1000.0f);
                rawSpeedKmh = calculatedMps * 3.6f;
            }
        }

        // Triệt tiêu hiện tượng GPS Drift khi đứng yên (Tốc độ dưới 1.8 km/h coi như 0 km/h)
        if (rawSpeedKmh < 1.8f) {
            rawSpeedKmh = 0f;
        }

        lastLocation = location;

        // Lọc nhiễu qua Kalman Filter
        float smoothedSpeedKmh = kalmanFilter.update(rawSpeedKmh);
        if (smoothedSpeedKmh < 1.0f) {
            smoothedSpeedKmh = 0f;
        }

        if (listener != null) {
            listener.onSpeedUpdated(smoothedSpeedKmh);
        }

        processSpeedChange(smoothedSpeedKmh);
    }

    private final LocationListener locationListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            handleNewLocation(location);
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {}

        @Override
        public void onProviderEnabled(String provider) {}

        @Override
        public void onProviderDisabled(String provider) {}
    };

    public VehicleSpeedMonitor(Context context, SpeedListener listener) {
        this.context = context;
        this.listener = listener;
        this.locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        try {
            this.fusedLocationClient = LocationServices.getFusedLocationProviderClient(context);
        } catch (Exception e) {
            Log.e(TAG, "Không thể khởi tạo FusedLocationProviderClient", e);
        }
    }

    public void startMonitoring() {
        if (isMonitoring) return;

        kalmanFilter.reset();
        lastLocation = null;
        boolean startedWithFused = false;

        // 1. Thử dùng FusedLocationProviderClient (Ưu tiên GPS độ chính xác cao từ Google Services)
        if (fusedLocationClient != null) {
            try {
                LocationRequest locationRequest = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 500)
                        .setMinUpdateIntervalMillis(300)
                        .setMinUpdateDistanceMeters(0.1f)
                        .build();

                fusedLocationCallback = new LocationCallback() {
                    @Override
                    public void onLocationResult(LocationResult locationResult) {
                        if (locationResult != null && locationResult.getLastLocation() != null) {
                            handleNewLocation(locationResult.getLastLocation());
                        }
                    }
                };

                fusedLocationClient.requestLocationUpdates(locationRequest, fusedLocationCallback, Looper.getMainLooper());
                isMonitoring = true;
                startedWithFused = true;
                Log.d(TAG, "Đã khởi chạy FusedLocationProviderClient theo dõi tốc độ GPS.");
            } catch (Exception e) {
                Log.e(TAG, "Lỗi đăng ký FusedLocationProviderClient", e);
            }
        }

        // 2. Chỉ dùng LocationManager fallback khi FusedLocation KHÔNG khả dụng (tránh trùng lặp gây giật lag)
        if (!startedWithFused && locationManager != null) {
            try {
                if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    locationManager.requestLocationUpdates(
                            LocationManager.GPS_PROVIDER, 500, 0.5f, locationListener, Looper.getMainLooper()
                    );
                    isMonitoring = true;
                    Log.d(TAG, "Đã khởi chạy LocationManager GPS_PROVIDER fallback.");
                }
            } catch (Exception e) {
                Log.e(TAG, "Lỗi đăng ký LocationManager GPS_PROVIDER", e);
            }
        }
    }

    private void processSpeedChange(final float speedKmh) {
        if (speedKmh <= STOP_SPEED_THRESHOLD_KMH) {
            if (!isStopped && stopConfirmationRunnable == null) {
                stopConfirmationRunnable = new Runnable() {
                    @Override
                    public void run() {
                        isStopped = true;
                        stopConfirmationRunnable = null;
                        Log.d(TAG, "Xe ĐÃ DỪNG HẲN (Tốc độ: " + String.format("%.1f", speedKmh) + " km/h)");
                        if (listener != null) {
                            listener.onVehicleStopped();
                        }
                    }
                };
                handler.postDelayed(stopConfirmationRunnable, STOP_CONFIRM_DELAY_MS);
            }
        } else {
            if (stopConfirmationRunnable != null) {
                handler.removeCallbacks(stopConfirmationRunnable);
                stopConfirmationRunnable = null;
            }

            if (speedKmh >= MOVE_SPEED_THRESHOLD_KMH) {
                if (isStopped) {
                    isStopped = false;
                    Log.d(TAG, "Xe ĐANG DI CHUYỂN (Tốc độ: " + String.format("%.1f", speedKmh) + " km/h)");
                    if (listener != null) {
                        listener.onVehicleMoving(speedKmh);
                    }
                }
            }
        }
    }

    public boolean isVehicleStopped() {
        return isStopped;
    }

    public void stopMonitoring() {
        if (!isMonitoring) return;
        if (stopConfirmationRunnable != null) {
            handler.removeCallbacks(stopConfirmationRunnable);
            stopConfirmationRunnable = null;
        }
        try {
            if (fusedLocationClient != null && fusedLocationCallback != null) {
                fusedLocationClient.removeLocationUpdates(fusedLocationCallback);
            }
            if (locationManager != null && locationListener != null) {
                locationManager.removeUpdates(locationListener);
            }
        } catch (Exception e) {
            Log.e(TAG, "Lỗi hủy theo dõi vị trí GPS", e);
        }
        isMonitoring = false;
        Log.d(TAG, "Hệ thống theo dõi tốc độ xe đã dừng.");
    }

    /**
     * Bộ lọc Kalman Lọc Nhiễu Tốc độ GPS 1 Chiều (1D Kalman Speed Filter)
     */
    private static class KalmanSpeedFilter {
        private float q = 0.10f; // Process Noise
        private float r = 0.40f; // Measurement Noise (GPS Jitter)
        private float p = 1.00f; // Estimation Error Covariance
        private float x = 0.00f; // Speed Estimate (km/h)
        private boolean initialized = false;

        public float update(float measurement) {
            if (!initialized) {
                x = measurement;
                initialized = true;
                return x;
            }

            // Predict
            p = p + q;

            // Kalman Gain
            float k = p / (p + r);

            // Update Estimate
            x = x + k * (measurement - x);

            // Update Covariance
            p = (1 - k) * p;

            return Math.max(0f, x);
        }

        public void reset() {
            x = 0f;
            p = 1.0f;
            initialized = false;
        }
    }
}


