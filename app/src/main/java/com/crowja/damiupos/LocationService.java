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
import com.crowja.damiupos.sync.OnlineTasks;
import com.crowja.damiupos.sync.SyncEngine;
import com.crowja.damiupos.sync.SyncSettings;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

/**
 * Foreground service for the whole shift (clock in → Pulang, INCLUDING istirahat).
 * Two responsibilities:
 *   1. Report the clocked-in staff's location — only while WORKING (not on break).
 *   2. Drive the online layer by POLLING the server (~60s) the entire shift, so the
 *      app stays in sync and receives dashboard commands/broadcasts in the
 *      background reliably (a foreground service survives Doze; WorkManager does not).
 *
 * On istirahat the service stays alive in poll-only mode (GPS stopped — no location
 * tracked during a break); it is stopped on Pulang. The persistent notification is
 * the foreground-service disclosure.
 */
public class LocationService extends Service {

    private static final String CHANNEL = "damiu_location";
    private static final int NOTIF_ID = 7861;

    /** Extra: true = poll only, no GPS (istirahat). */
    public static final String EXTRA_POLL_ONLY = "poll_only";
    /** How often to poll the server while a shift is open (working or on break). */
    private static final long POLL_SECONDS = 60;

    /** True while the shift service is live; lets a config change reconfigure it. */
    public static volatile boolean RUNNING = false;

    private FusedLocationProviderClient fused;
    private LocationCallback callback;
    private String staffUuid;
    private ScheduledExecutorService poller;

    /** Start/continue the shift service in WORKING mode (GPS + polling). */
    public static void start(Context ctx) {
        SyncSettings cfg = new SyncSettings(new SettingsDao(DatabaseHelper.getInstance(ctx)));
        if (!cfg.isEnrolled() || !cfg.isLocationTrackingEnabled()) return;
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) return;
        ContextCompat.startForegroundService(ctx, new Intent(ctx, LocationService.class));
    }

    /** Keep the shift service alive in POLL-ONLY mode during istirahat (no GPS). */
    public static void startBreak(Context ctx) {
        SyncSettings cfg = new SyncSettings(new SettingsDao(DatabaseHelper.getInstance(ctx)));
        if (!cfg.isEnrolled()) return;
        // The foreground service is typed "location"; starting it needs the location
        // permission that working mode already held (break always follows working).
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) return;
        ContextCompat.startForegroundService(ctx,
                new Intent(ctx, LocationService.class).putExtra(EXTRA_POLL_ONLY, true));
    }

    public static void stop(Context ctx) {
        ctx.stopService(new Intent(ctx, LocationService.class));
    }

    /** Re-apply the (possibly new) reporting interval to a session already running. No-op otherwise. */
    public static void reconfigure(Context ctx) {
        if (RUNNING) start(ctx);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        fused = LocationServices.getFusedLocationProviderClient(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        boolean pollOnly = intent != null && intent.getBooleanExtra(EXTRA_POLL_ONLY, false);
        ensureChannel();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, buildNotification(pollOnly),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(NOTIF_ID, buildNotification(pollOnly));
        }
        RUNNING = true;

        SyncSettings cfg = new SyncSettings(new SettingsDao(DatabaseHelper.getInstance(this)));
        if (!cfg.isEnrolled()) {
            stopSelf();
            return START_NOT_STICKY;
        }

        // Poll the server for the whole shift (working + break) — this is the
        // background "active polling" that keeps the app synced & command-ready.
        ensurePolling();

        if (pollOnly) {
            stopGps();           // istirahat: no location tracking, polling only
        } else {
            startGps(cfg);       // working: GPS + polling
        }
        return START_STICKY;     // keep alive until Pulang (stop) or self-stop
    }

    /** Begin (or re-apply) location updates for the working part of the shift. */
    private void startGps(SyncSettings cfg) {
        staffUuid = LocationReporter.currentStaffUuid(this);
        if (staffUuid == null || !cfg.isLocationTrackingEnabled()) return;  // poll continues
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) return;
        // Drop any previous request so a re-start (config change) applies the new interval.
        if (callback != null) {
            try { fused.removeLocationUpdates(callback); } catch (Exception ignored) {}
        }
        long interval = cfg.getLocationIntervalMs();   // admin-configurable, default 10 min
        LocationRequest req = new LocationRequest.Builder(
                Priority.PRIORITY_BALANCED_POWER_ACCURACY, interval)
                .setMinUpdateIntervalMillis(interval)
                .setMinUpdateDistanceMeters(0f)
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
        } catch (SecurityException ignored) {}
    }

    /** Stop location updates (entering istirahat) — polling keeps running. */
    private void stopGps() {
        if (fused != null && callback != null) {
            try { fused.removeLocationUpdates(callback); } catch (Exception ignored) {}
            callback = null;
        }
    }

    /** Start the ~60s server-poll loop once (off the main thread). */
    private void ensurePolling() {
        if (poller != null) return;
        poller = Executors.newSingleThreadScheduledExecutor();
        poller.scheduleWithFixedDelay(() -> {
            try {
                Context app = getApplicationContext();
                SettingsDao sdao = new SettingsDao(DatabaseHelper.getInstance(app));
                SyncSettings cfg = new SyncSettings(sdao);
                // Shift ended elsewhere / unenrolled → wind down the service.
                if (!cfg.isEnrolled() || !sdao.isShiftActive()) { stopSelf(); return; }
                new SyncEngine(app).sync();
                OnlineTasks.tick(app);   // config (/me heartbeat), version, broadcasts, commands
            } catch (Throwable ignored) {}
        }, 0, POLL_SECONDS, TimeUnit.SECONDS);
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
        RUNNING = false;
        if (fused != null && callback != null) {
            try { fused.removeLocationUpdates(callback); } catch (Exception ignored) {}
        }
        if (poller != null) {
            try { poller.shutdownNow(); } catch (Exception ignored) {}
            poller = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private Notification buildNotification(boolean pollOnly) {
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String title = pollOnly ? "Sinkronisasi aktif (istirahat)" : "Lokasi & sinkronisasi aktif";
        String text = pollOnly
                ? "Aplikasi tetap tersinkron dengan server. Lokasi tidak dilacak saat istirahat."
                : "Posisi dibagikan ke admin & data tersinkron selama shift berjalan.";
        return new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
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
