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
    private static final float STOP_SPEED_THRESHOLD_KMH = 5.0f; // km/h
    private static final float MOVE_SPEED_THRESHOLD_KMH = 8.0f; // km/h
    private static final long STOP_CONFIRM_DELAY_MS = 1500;

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

    // Bộ lọc Kalman Lọc Nhiễu GPS 1 chiều (1D Kalman Speed Filter)
    private final KalmanSpeedFilter kalmanFilter = new KalmanSpeedFilter();

    private void handleNewLocation(Location location) {
        if (location == null) return;

        float rawSpeedKmh = 0f;

        // 1. Nếu thiết bị hỗ trợ hasSpeed() và getSpeed() > 0
        if (location.hasSpeed() && location.getSpeed() > 0) {
            rawSpeedKmh = location.getSpeed() * 3.6f;
        } else if (lastLocation != null) {
            // 2. Fallback thủ công: Tính tốc độ dựa trên khoảng cách (distanceTo) và chênh lệch thời gian
            float distanceMeters = lastLocation.distanceTo(location);
            long timeDeltaMs = location.getTime() - lastLocation.getTime();

            if (timeDeltaMs > 100 && timeDeltaMs < 10000 && distanceMeters > 0.2f) {
                float calculatedMps = distanceMeters / (timeDeltaMs / 1000.0f);
                rawSpeedKmh = calculatedMps * 3.6f;
            }
        }

        lastLocation = location;

        // Lọc qua Kalman
        float smoothedSpeedKmh = kalmanFilter.update(rawSpeedKmh);

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

        // 1. Thử dùng FusedLocationProviderClient (Google Play Services)
        if (fusedLocationClient != null) {
            try {
                LocationRequest locationRequest = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 400)
                        .setMinUpdateIntervalMillis(250)
                        .setMinUpdateDistanceMeters(0f)
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
                Log.d(TAG, "Đã khởi chạy FusedLocationProviderClient theo dõi tốc độ GPS.");
            } catch (SecurityException e) {
                Log.e(TAG, "Thiếu quyền vị trí GPS cho FusedLocation", e);
            } catch (Exception e) {
                Log.e(TAG, "Lỗi đăng ký FusedLocationProviderClient", e);
            }
        }

        // 2. Dự phòng bằng LocationManager chuẩn (GPS_PROVIDER + NETWORK_PROVIDER + PASSIVE_PROVIDER)
        try {
            if (locationManager != null) {
                if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    locationManager.requestLocationUpdates(
                            LocationManager.GPS_PROVIDER, 400, 0, locationListener, Looper.getMainLooper()
                    );
                }
                if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    locationManager.requestLocationUpdates(
                            LocationManager.NETWORK_PROVIDER, 400, 0, locationListener, Looper.getMainLooper()
                    );
                }
                if (LocationManager.PASSIVE_PROVIDER != null) {
                    try {
                        locationManager.requestLocationUpdates(
                                LocationManager.PASSIVE_PROVIDER, 400, 0, locationListener, Looper.getMainLooper()
                        );
                    } catch (Exception ignored) {}
                }
                isMonitoring = true;
                Log.d(TAG, "Đã khởi chạy LocationManager fallback theo dõi tốc độ GPS.");
            }
        } catch (SecurityException e) {
            Log.e(TAG, "Thiếu quyền vị trí GPS cho LocationManager", e);
        } catch (Exception e) {
            Log.e(TAG, "Lỗi đăng ký LocationManager", e);
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
        private float q = 0.15f; // Process Noise
        private float r = 0.35f; // Measurement Noise (GPS Jitter)
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

