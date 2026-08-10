package com.openclaw.countdown;

import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

public class VehicleSpeedMonitor {
    private static final String TAG = "VehicleSpeedMonitor";
    private static final float STOP_SPEED_THRESHOLD_KMH = 5.0f; // km/h (cho phép nhích nhẹ dưới 5km/h tại đèn đỏ)
    private static final float MOVE_SPEED_THRESHOLD_KMH = 8.0f; // km/h (chỉ ẩn khi xe tăng tốc trên 8km/h)
    private static final long STOP_CONFIRM_DELAY_MS = 1500; // 1.5s xác nhận dừng hẳn

    public interface SpeedListener {
        void onVehicleStopped();
        void onVehicleMoving(float speedKmh);
        void onSpeedUpdated(float speedKmh);
    }

    private final Context context;
    private final SpeedListener listener;
    private LocationManager locationManager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean isMonitoring = false;
    private boolean isStopped = false;
    private Runnable stopConfirmationRunnable;

    // Bộ lọc Kalman Lọc Nhiễu GPS 1 chiều (1D Kalman Speed Filter)
    private final KalmanSpeedFilter kalmanFilter = new KalmanSpeedFilter();

    private final LocationListener locationListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            if (location == null) return;

            // Đọc tốc độ thô từ GPS (m/s -> km/h)
            float rawSpeedKmh = location.hasSpeed() ? (location.getSpeed() * 3.6f) : 0f;

            // Đưa qua Bộ lọc Kalman lọc nhiễu nhảy ảo dưới nhà cao tầng/gầm cầu
            float smoothedSpeedKmh = kalmanFilter.update(rawSpeedKmh);

            if (listener != null) {
                listener.onSpeedUpdated(smoothedSpeedKmh);
            }

            processSpeedChange(smoothedSpeedKmh);
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
    }

    public void startMonitoring() {
        if (isMonitoring) return;

        kalmanFilter.reset();

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
                isMonitoring = true;
                Log.d(TAG, "Vehicle speed monitoring (Kalman Filtered) started.");
            }
        } catch (SecurityException e) {
            Log.e(TAG, "Thiếu quyền vị trí GPS", e);
        } catch (Exception e) {
            Log.e(TAG, "Lỗi khởi chạy cập nhật GPS", e);
        }

        // Fallback: Mặc định phát tín hiệu Xe Đã Dừng (0 km/h) sau 1.5s nếu chưa nhận vị trí GPS
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!isStopped) {
                    isStopped = true;
                    Log.d(TAG, "Default zero-speed initial fallback triggered.");
                    if (listener != null) {
                        listener.onSpeedUpdated(0f);
                        listener.onVehicleStopped();
                    }
                }
            }
        }, 1500);
    }

    private void processSpeedChange(final float speedKmh) {
        if (speedKmh <= STOP_SPEED_THRESHOLD_KMH) {
            if (!isStopped && stopConfirmationRunnable == null) {
                stopConfirmationRunnable = new Runnable() {
                    @Override
                    public void run() {
                        isStopped = true;
                        stopConfirmationRunnable = null;
                        Log.d(TAG, "Xe ĐÃ DỪNG HẲN (Tốc độ mượt Kalman: " + String.format("%.1f", speedKmh) + " km/h)");
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
                    Log.d(TAG, "Xe ĐANG DI CHUYỂN (Tốc độ mượt Kalman: " + String.format("%.1f", speedKmh) + " km/h)");
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
            if (locationManager != null && locationListener != null) {
                locationManager.removeUpdates(locationListener);
            }
        } catch (Exception e) {
            Log.e(TAG, "Lỗi hủy theo dõi vị trí GPS", e);
        }
        isMonitoring = false;
        isStopped = false;
        Log.d(TAG, "Hệ thống theo dõi tốc độ xe đã dừng.");
    }

    /**
     * Bộ lọc Kalman Lọc Nhiễu Tốc độ GPS 1 Chiều (1D Kalman Speed Filter)
     */
    private static class KalmanSpeedFilter {
        private float q = 0.05f; // Process Noise
        private float r = 0.80f; // Measurement Noise (GPS Jitter)
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
