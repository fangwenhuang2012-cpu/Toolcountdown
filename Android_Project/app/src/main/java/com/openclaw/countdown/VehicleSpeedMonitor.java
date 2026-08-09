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
    private static final long STOP_CONFIRM_DELAY_MS = 1500; // 1.5 seconds stop confirmation

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

    private final LocationListener locationListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            if (location == null) return;

            // Speed in km/h (location.getSpeed() returns meters/second)
            float speedKmh = location.hasSpeed() ? (location.getSpeed() * 3.6f) : 0f;

            if (listener != null) {
                listener.onSpeedUpdated(speedKmh);
            }

            processSpeedChange(speedKmh);
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

        try {
            if (locationManager != null) {
                if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    locationManager.requestLocationUpdates(
                            LocationManager.GPS_PROVIDER, 500, 0, locationListener, Looper.getMainLooper()
                    );
                } else if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    locationManager.requestLocationUpdates(
                            LocationManager.NETWORK_PROVIDER, 500, 0, locationListener, Looper.getMainLooper()
                    );
                }
                isMonitoring = true;
                Log.d(TAG, "Vehicle speed monitoring started.");
            }
        } catch (SecurityException e) {
            Log.e(TAG, "Location permission missing", e);
        } catch (Exception e) {
            Log.e(TAG, "Error starting location updates", e);
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
                        Log.d(TAG, "Vehicle CONFIRMED STOPPED (Speed: " + speedKmh + " km/h)");
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
                    Log.d(TAG, "Vehicle MOVING (Speed: " + speedKmh + " km/h)");
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
            Log.e(TAG, "Error stopping location updates", e);
        }
        isMonitoring = false;
        isStopped = false;
        Log.d(TAG, "Vehicle speed monitoring stopped.");
    }
}
