package com.crowja.damiupos;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.os.BatteryManager;
import android.os.Build;
import android.os.IBinder;
import android.os.Looper;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.SettingsDao;
import com.crowja.damiupos.sync.LocationReporter;
import com.crowja.damiupos.sync.SyncSettings;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

/**
 * Foreground service that reports the clocked-in staff's location while on shift.
 * Started on clock in, stopped on Istirahat / Pulang / logout. Battery-efficient
 * (balanced power, ~45s / 30m). The persistent notification is the disclosure.
 */
public class LocationService extends Service {

    private static final String CHANNEL = "damiu_location";
    private static final int NOTIF_ID = 7861;

    private FusedLocationProviderClient fused;
    private LocationCallback callback;
    private String staffUuid;

    public static void start(Context ctx) {
        SyncSettings cfg = new SyncSettings(new SettingsDao(DatabaseHelper.getInstance(ctx)));
        if (!cfg.isEnrolled() || !cfg.isLocationTrackingEnabled()) return;
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) return;
        ContextCompat.startForegroundService(ctx, new Intent(ctx, LocationService.class));
    }

    public static void stop(Context ctx) {
        ctx.stopService(new Intent(ctx, LocationService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        fused = LocationServices.getFusedLocationProviderClient(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        ensureChannel();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(NOTIF_ID, buildNotification());
        }

        staffUuid = LocationReporter.currentStaffUuid(this);
        SyncSettings cfg = new SyncSettings(new SettingsDao(DatabaseHelper.getInstance(this)));
        if (staffUuid == null || !cfg.isEnrolled() || !cfg.isLocationTrackingEnabled()) {
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            stopSelf();
            return START_NOT_STICKY;
        }

        LocationRequest req = new LocationRequest.Builder(
                Priority.PRIORITY_BALANCED_POWER_ACCURACY, 45_000L)
                .setMinUpdateIntervalMillis(30_000L)
                .setMinUpdateDistanceMeters(30f)
                .build();

        callback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult result) {
                Location loc = result.getLastLocation();
                if (loc != null) sendFix(loc);
            }
        };
        try {
            fused.requestLocationUpdates(req, callback, Looper.getMainLooper());
        } catch (SecurityException e) {
            stopSelf();
        }
        return START_STICKY;   // keep tracking until explicitly stopped
    }

    private void sendFix(Location loc) {
        final double lat = loc.getLatitude();
        final double lng = loc.getLongitude();
        final Float acc = loc.hasAccuracy() ? loc.getAccuracy() : null;
        final Float speed = loc.hasSpeed() ? loc.getSpeed() : null;
        final Float bearing = loc.hasBearing() ? loc.getBearing() : null;
        final Integer battery = batteryPercent();
        new Thread(() -> LocationReporter.report(getApplicationContext(),
                staffUuid, lat, lng, acc, speed, bearing, battery)).start();
    }

    private Integer batteryPercent() {
        try {
            Intent b = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (b == null) return null;
            int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            if (level < 0 || scale <= 0) return null;
            return Math.round(level * 100f / scale);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public void onDestroy() {
        if (fused != null && callback != null) {
            try { fused.removeLocationUpdates(callback); } catch (Exception ignored) {}
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private Notification buildNotification() {
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("Lokasi aktif saat bekerja")
                .setContentText("Posisi Anda dibagikan ke admin selama shift berjalan.")
                .setOngoing(true)
                .setContentIntent(pi)
                .build();
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null || nm.getNotificationChannel(CHANNEL) != null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL,
                "Pelacakan Lokasi", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Berbagi lokasi ke admin selama jam kerja");
        nm.createNotificationChannel(ch);
    }
}
