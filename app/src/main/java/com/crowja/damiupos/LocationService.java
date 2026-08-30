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
import android.net.ConnectivityManager;
import android.net.Network;
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
    private static final long POLL_MS = POLL_SECONDS * 1000L;

    /** True while the shift service is live; lets a config change reconfigure it. */
    public static volatile boolean RUNNING = false;

    private FusedLocationProviderClient fused;
    private LocationCallback callback;
    private String staffUuid;
    private ScheduledExecutorService poller;
    /** Fires an immediate sync the moment internet returns — flushes transactions created while the
     *  phone was offline (mis. penjualan di lapangan) without waiting for the 60s poll or a WorkManager
     *  constraint job (unreliable on OEM battery-optimized phones). */
    private ConnectivityManager netMgr;
    private ConnectivityManager.NetworkCallback netCallback;
    private volatile long lastReconnectSyncMs = 0;
    /** True while a dedicated high-frequency GPS request is subscribed (delivery in progress). */
    private volatile boolean fastGpsOn = false;

    /** Start/continue the shift service in WORKING mode (GPS + polling). */
    public static void start(Context ctx) {
        SyncSettings cfg = new SyncSettings(new SettingsDao(DatabaseHelper.getInstance(ctx)));
        if (!cfg.isEnrolled() || !cfg.isLocationTrackingEnabled()) return;
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) return;
        safeStart(ctx, new Intent(ctx, LocationService.class));
    }

    /**
     * Mulai foreground service dgn aman: Android 12+ melempar
     * {@code ForegroundServiceStartNotAllowedException} (subclass IllegalStateException) bila
     * dipanggil dari background tanpa pengecualian yang berlaku. Jangan sampai crash — kalau gagal,
     * setidaknya jalankan sinkronisasi lewat WorkManager supaya data tetap terkirim, dan pasang
     * watchdog agar service dicoba lagi nanti (dari alarm persis yang MEMANG dikecualikan).
     */
    private static void safeStart(Context ctx, Intent intent) {
        Context app = ctx.getApplicationContext();
        try {
            ContextCompat.startForegroundService(app, intent);
        } catch (Throwable t) {
            try { com.crowja.damiupos.sync.SyncScheduler.syncNow(app); } catch (Throwable ignored) {}
        }
        // Selalu jaga watchdog tetap ter-arm (idempoten) — pemulihan berkala bila service dibunuh.
        try { com.crowja.damiupos.sync.ServiceRestartReceiver.arm(app); } catch (Throwable ignored) {}
    }

    /**
     * (Re)start the service in POLL-ONLY mode (no GPS). Used for istirahat, after
     * Pulang, and when location sharing is turned off — polling keeps running so the
     * app stays synced with the dashboard in the background regardless of shift.
     */
    public static void pollOnly(Context ctx) {
        SyncSettings cfg = new SyncSettings(new SettingsDao(DatabaseHelper.getInstance(ctx)));
        if (!cfg.isEnrolled()) return;
        // The foreground service is typed "location"; starting it needs the location
        // permission (the only FGS type without an Android-14 daily time cap).
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) return;
        safeStart(ctx, new Intent(ctx, LocationService.class).putExtra(EXTRA_POLL_ONLY, true));
    }

    /**
     * Ensure the continuous online/sync service is running whenever the app is open
     * and enrolled — independent of any shift — so dashboard changes (new staff,
     * config, commands, broadcasts) reach the phone in near real-time even in the
     * background. No-op if already running (keeps its current GPS/poll mode) or if
     * location permission isn't granted yet (the foreground-service type requires it).
     */
    public static void ensureOnline(Context ctx) {
        if (RUNNING) return;
        pollOnly(ctx);
    }

    public static void stop(Context ctx) {
        ctx.stopService(new Intent(ctx, LocationService.class));
    }

    /** Re-apply the (possibly new) reporting interval to a session already running. No-op otherwise. */
    public static void reconfigure(Context ctx) {
        if (RUNNING) start(ctx);
    }

    /**
     * Best-effort: stamp an attendance event with the device's last known GPS so clock in/out
     * carries location for the dashboard (no "Tanpa lokasi"). Async + no-op when permission or a
     * fix is unavailable; updates the row + marks it dirty so the next sync pushes the coordinates.
     */
    /**
     * Ambil lokasi terakhir perangkat (async) → callback dengan Location, atau null bila izin
     * belum diberi / tak ada fix. Dipakai Antrian Delivery untuk anchor "rute terpendek".
     */
    public static void lastLocation(Context ctx,
            com.google.android.gms.tasks.OnSuccessListener<Location> cb) {
        Context app = ctx.getApplicationContext();
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            cb.onSuccess(null);
            return;
        }
        try {
            LocationServices.getFusedLocationProviderClient(app).getLastLocation()
                    .addOnSuccessListener(cb)
                    .addOnFailureListener(e -> cb.onSuccess(null));
        } catch (SecurityException se) {
            cb.onSuccess(null);
        } catch (Exception e) {
            cb.onSuccess(null);
        }
    }

    public static void stampAttendanceLocation(Context ctx, long attendanceId) {
        if (attendanceId <= 0) return;
        Context app = ctx.getApplicationContext();
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        try {
            LocationServices.getFusedLocationProviderClient(app).getLastLocation()
                    .addOnSuccessListener(loc -> {
                        if (loc == null) return;
                        new com.crowja.damiupos.db.AttendanceDao(DatabaseHelper.getInstance(app))
                                .setLocation(attendanceId, loc.getLatitude(), loc.getLongitude());
                        com.crowja.damiupos.sync.SyncScheduler.syncNow(app);   // push the coordinates promptly
                    });
        } catch (SecurityException ignored) {
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        fused = LocationServices.getFusedLocationProviderClient(this);
        registerReconnectSync();
    }

    /** Kick a sync as soon as the device (re)gains internet, so a sale recorded offline uploads
     *  immediately on reconnect instead of lingering until the next poll/app-open. Debounced to at
     *  most once per 10s (network flaps fire onAvailable repeatedly); syncNow itself also coalesces. */
    private void registerReconnectSync() {
        try {
            netMgr = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (netMgr == null) return;
            final Context app = getApplicationContext();
            netCallback = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) {
                    long now = System.currentTimeMillis();
                    if (now - lastReconnectSyncMs < 10_000L) return;   // debounce flapping
                    lastReconnectSyncMs = now;
                    try {
                        if (new SyncSettings(new SettingsDao(DatabaseHelper.getInstance(app))).isEnrolled()) {
                            com.crowja.damiupos.sync.SyncScheduler.syncNow(app);
                        }
                    } catch (Throwable ignored) {}
                }
            };
            netMgr.registerDefaultNetworkCallback(netCallback);
        } catch (Throwable ignored) {}
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        boolean reqPollOnly = intent != null && intent.getBooleanExtra(EXTRA_POLL_ONLY, false);
        SettingsDao sdao = new SettingsDao(DatabaseHelper.getInstance(this));
        SyncSettings cfg = new SyncSettings(sdao);

        // GPS only when genuinely working: a shift is open, not on break (poll-only),
        // location sharing is on, and a staff is clocked in. Otherwise poll-only — but
        // the service still runs (and polls) so the app stays synced in the background.
        boolean shiftActive = sdao.isShiftActive();
        boolean wantGps = !reqPollOnly && shiftActive && cfg.isLocationTrackingEnabled()
                && LocationReporter.currentStaffUuid(this) != null;

        ensureChannel();
        // PENTING: startForeground() sendiri bisa MELEMPAR — bukan hanya startForegroundService().
        //  • Android 12+: promosi FGS dari background tanpa pengecualian → ForegroundServiceStartNotAllowedException.
        //  • Android 14+: tipe "location" adalah while-in-use → SecurityException bila di-start dari
        //    background tanpa izin lokasi latar (mis. restart dari boot/alarm, FINE hanya "saat dipakai").
        // Exception ini muncul di SINI (async, main-thread), DI LUAR jangkauan safeStart. Tanpa guard
        // ini, service crash di setiap boot / tembakan watchdog. Tangkap → sinkron via WorkManager +
        // coba lagi nanti lewat watchdog, lalu berhenti (tanpa startForeground yang sukses, FGS tak
        // boleh lanjut — sekaligus mencegah ANR "did not call startForeground()").
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, buildNotification(wantGps, shiftActive),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            } else {
                startForeground(NOTIF_ID, buildNotification(wantGps, shiftActive));
            }
        } catch (Throwable t) {
            try { com.crowja.damiupos.sync.SyncScheduler.syncNow(getApplicationContext()); } catch (Throwable ignored) {}
            try { com.crowja.damiupos.sync.ServiceRestartReceiver.arm(this); } catch (Throwable ignored) {}
            stopSelf();
            return START_NOT_STICKY;
        }
        RUNNING = true;

        if (!cfg.isEnrolled()) {
            com.crowja.damiupos.sync.ServiceRestartReceiver.cancel(this);   // unenrolled → stop resurrecting
            stopSelf();
            return START_NOT_STICKY;
        }

        // Pasang watchdog alarm (idempoten): bila service ini dibunuh OEM/Doze saat app di
        // background, alarm persis berkala akan menghidupkannya lagi (jalur start-FGS yang
        // diizinkan Android 12+ dari background).
        com.crowja.damiupos.sync.ServiceRestartReceiver.arm(this);

        // Continuous background polling — runs whenever enrolled (shift or not), so the
        // app receives dashboard changes (new staff/config/commands) in near real-time.
        ensurePolling();

        applyLocationMode(cfg, wantGps);
        return START_STICKY;     // keep alive until unenrolled (or explicit full stop)
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
            fastGpsOn = true;
        } catch (SecurityException ignored) {}
    }

    /** Stop the dedicated GPS request (istirahat or no active delivery) — polling keeps running. */
    private void stopGps() {
        if (fused != null && callback != null) {
            try { fused.removeLocationUpdates(callback); } catch (Exception ignored) {}
            callback = null;
        }
        fastGpsOn = false;
    }

    /**
     * Pick the GPS cadence. A dedicated GPS request runs ONLY while working AND the server interval is
     * at most our poll — i.e. a delivery is in progress (server hands back 60s) → fresh fixes pushed
     * every minute. With no active delivery the interval is the slow battery-friendly value (> poll), so
     * we drop the dedicated GPS and let the ~60s poll report one fix ("berbarengan dengan sinkron pooling").
     */
    private void applyLocationMode(SyncSettings cfg, boolean working) {
        boolean fast = working && cfg.getLocationIntervalMs() <= POLL_MS;
        if (fast && !fastGpsOn) startGps(cfg);
        else if (!fast && fastGpsOn) stopGps();
    }

    /** Idle reporting: post the last known fix once, piggybacked on a server poll (no dedicated GPS). */
    private void reportLastLocationOnce() {
        final String uuid = LocationReporter.currentStaffUuid(this);
        if (uuid == null) return;
        staffUuid = uuid;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) return;
        try {
            fused.getLastLocation().addOnSuccessListener(loc -> { if (loc != null) sendFix(loc); });
        } catch (SecurityException ignored) {}
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
                // Only wind down when the device is no longer enrolled — otherwise the
                // poll runs forever (shift or not) for real-time dashboard sync.
                if (!cfg.isEnrolled()) {
                    com.crowja.damiupos.sync.ServiceRestartReceiver.cancel(app);
                    stopSelf();
                    return;
                }
                new SyncEngine(app).sync();
                OnlineTasks.tick(app);   // config (/me heartbeat), version, broadcasts, commands

                // Re-evaluate the live interval pulled by the tick: switch to dedicated 60s GPS when a
                // delivery is active, drop it when not. When idle, send a fix together with this poll.
                boolean working = sdao.isShiftActive() && cfg.isLocationTrackingEnabled()
                        && LocationReporter.currentStaffUuid(app) != null;
                applyLocationMode(cfg, working);
                if (working && cfg.getLocationIntervalMs() > POLL_MS) reportLastLocationOnce();
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
        if (netMgr != null && netCallback != null) {
            try { netMgr.unregisterNetworkCallback(netCallback); } catch (Exception ignored) {}
            netCallback = null;
        }
        if (poller != null) {
            try { poller.shutdownNow(); } catch (Exception ignored) {}
            poller = null;
        }
        super.onDestroy();
    }

    /**
     * User menyapu app dari Recents. Di banyak OEM (Samsung/Xiaomi) ini membunuh proses + service,
     * dan START_STICKY sering TIDAK dihormati → sinkronisasi/lokasi mati sampai app dibuka lagi.
     * Saat ini dipanggil, service MASIH foreground (belum benar-benar mati), jadi kita boleh
     * memicu start-ulang: pasang watchdog untuk beberapa detik lagi supaya service bangkit kembali
     * meski prosesnya keburu di-tear-down. Hanya bila masih terhubung ke cabang.
     */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        try {
            Context app = getApplicationContext();
            if (new SyncSettings(new SettingsDao(DatabaseHelper.getInstance(app))).isEnrolled()) {
                // Jeda pendek → bangkit cepat setelah disapu (bukan menunggu siklus 15 menit).
                com.crowja.damiupos.sync.ServiceRestartReceiver.armIn(app, 3000L);
            }
        } catch (Throwable ignored) {}
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private Notification buildNotification(boolean gps, boolean shiftActive) {
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String title, text;
        if (gps) {
            title = "Lokasi & sinkronisasi aktif";
            text = "Posisi dibagikan ke admin & data tersinkron selama shift berjalan.";
        } else if (shiftActive) {
            title = "Sinkronisasi aktif (istirahat)";
            text = "Aplikasi tetap tersinkron dengan server. Lokasi tidak dilacak saat istirahat.";
        } else {
            title = "Sinkronisasi aktif";
            text = "Aplikasi tetap tersinkron dengan dashboard di latar belakang.";
        }
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
