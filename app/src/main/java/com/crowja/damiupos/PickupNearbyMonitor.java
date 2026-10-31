package com.crowja.damiupos;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.SettingsDao;
import com.crowja.damiupos.db.TransactionDao;
import com.crowja.damiupos.model.Transaction;
import com.crowja.damiupos.sync.SyncApi;
import com.crowja.damiupos.sync.SyncSettings;
import com.crowja.damiupos.util.PickupNearbyLogic;
import com.crowja.damiupos.util.PickupNearbyLogic.Candidate;
import com.crowja.damiupos.util.PickupNearbyLogic.Event;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 📥 Peringatan "Galon Kembali di sekitar rute" — dimiliki {@link LocationService} (foreground service
 * lokasi yang memang hidup sepanjang shift), logikanya di {@link PickupNearbyLogic}.
 *
 * <p>AKTIF hanya bila sakelar perangkat menyala (halaman Perangkat → /api/me pickup_alert_enabled) DAN
 * (rit sedang berjalan di HP ini ATAU panel navigasi {@link NavigationPipActivity} hidup) — dinilai tiap
 * poll (~60 dtk), saat navigasi mulai/selesai, saat rit berubah, dan saat setelan berubah. Selama aktif
 * saja ia memasang request FusedLocation SENDIRI (akurasi tinggi, ±4 dtk, min 5 m); begitu tidak aktif,
 * request itu dicabut (baterai).
 *
 * <p>Kandidat: (a) baris lokal KEMBALI PENDING di Antrean Saya / Pesanan Terbuka bertitik peta, bukan
 * anggota rit berjalan / tujuan panel navigasi; (b) Pesanan Terbuka & antrean perangkat lain dari
 * {@code /api/devices/queue-all} (pickup_only, tidak in_progress, bertitik peta) — disegarkan tiap 3 menit
 * di latar, gagal = diam (offline).
 *
 * <p>Peringatan: notifikasi heads-up kanal {@value #CHANNEL_ALERT} (getar khas + bunyi notifikasi bawaan),
 * berlangsung selama di dalam radius, diulang tiap 45 dtk (maks 3×). TIDAK membuka layar apa pun sendiri,
 * tanpa full-screen intent, tanpa dialog — navigasi Google Maps tak terganggu. Aksi "📥 Ambil" membuka
 * antrean (alur Selesai pickup) dan "Nanti" menutupnya. Terlewat tanpa Ambil → POST
 * /api/delivery/pickup-passed (Jurnal Pesanan) + notifikasi senyap "terlewat".
 */
public final class PickupNearbyMonitor {

    static final String CHANNEL_ALERT = "pickup_nearby";
    static final String CHANNEL_PASSED = "pickup_nearby_passed";
    /** Id notifikasi (tag = kunci kandidat, jadi beberapa peringatan bisa tampil bersamaan). */
    static final int NOTIF_ALERT = 7971;
    static final int NOTIF_PASSED = 7972;

    private static final long FIX_INTERVAL_MS = 4_000L;
    private static final long FIX_MIN_INTERVAL_MS = 2_000L;
    private static final float FIX_MIN_DISTANCE_M = 5f;
    /** Fix lebih tua dari ini (cache FusedLocation) diabaikan. */
    private static final long FIX_MAX_AGE_MS = 30_000L;
    private static final long TICK_MS = 5_000L;
    private static final long LOCAL_REFRESH_MS = 30_000L;
    private static final long REMOTE_REFRESH_MS = 3 * 60_000L;
    /** Daftar perangkat lain yang tak bisa disegarkan selama ini dianggap basi (dibuang). */
    private static final long REMOTE_STALE_MS = 10 * 60_000L;
    /** Jaring pengaman: peringatan yang ditinggal proses mati hilang sendiri. */
    private static final long ALERT_TIMEOUT_MS = 10 * 60_000L;
    private static final long PASSED_TIMEOUT_MS = 6 * 60 * 60_000L;
    /** Lapor terlewat: langsung, lalu ulang bila offline (best effort). */
    private static final long[] REPORT_RETRY_MS = {0L, 30_000L, 120_000L, 300_000L};
    /** Getar khas "ta-ta-taaa" — beda dari notifikasi lain aplikasi ini. */
    private static final long[] VIBRATION = {0L, 250L, 120L, 250L, 120L, 700L};
    private static final int COLOR_ALERT = 0xFFC2410C;
    private static final int COLOR_PASSED = 0xFF9A3412;

    private static volatile PickupNearbyMonitor sInstance;
    /** Pendengar panel navigasi (dipanggil di thread UI) — pita "Galon kembali dekat". */
    private static volatile Runnable sUiListener;
    /** Peringatan tampil terdekat; null = tak ada / monitor tak aktif. */
    private static volatile PickupNearbyLogic.Alert sCurrent;

    private final Context app;
    private final FusedLocationProviderClient fused;
    private final HandlerThread thread;
    /** Semua keadaan di bawah hanya disentuh di thread ini. */
    private final Handler h;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private final PickupNearbyLogic.Tracker tracker = new PickupNearbyLogic.Tracker();
    /** Ember jarak (5 m) yang terakhir ditulis per notifikasi — pembaruan senyap hanya bila berubah. */
    private final Map<String, Long> shownBucket = new HashMap<>();

    private volatile boolean closed;
    private boolean active;
    private LocationCallback callback;
    private int radiusM = PickupNearbyLogic.DEFAULT_RADIUS_M;
    private List<Candidate> local = Collections.emptyList();
    private List<Candidate> remote = Collections.emptyList();
    private long localAt, remoteAt, remoteOkAt;
    private boolean remoteLoading;
    private String lastSig = "";

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!active || closed) return;
            long now = System.currentTimeMillis();
            refreshCandidates(false);
            // Kurir berhenti → tak ada fix baru (min 5 m): order yang selesai / diambil kurir lain tetap
            // harus tertutup peringatannya, dan bunyi ulang tetap jalan selama ia di dalam radius.
            handle(tracker.sync(candidates(now), now));
            handle(tracker.onTick(now));
            publish();
            h.postDelayed(this, TICK_MS);
        }
    };

    PickupNearbyMonitor(Context ctx) {
        app = ctx.getApplicationContext();
        fused = LocationServices.getFusedLocationProviderClient(app);
        thread = new HandlerThread("pickup-nearby");
        thread.start();
        h = new Handler(thread.getLooper());
        sInstance = this;
        // Peringatan sisa proses sebelumnya (dibunuh saat peringatan tampil) tak boleh tertinggal tanpa pemilik.
        h.post(this::cancelStaleAlerts);
    }

    // ================================================================== API (thread mana pun)

    /** Nilai ulang AKTIF/tidak (poll LocationService). */
    void evaluate() {
        if (!closed) h.post(this::evaluateNow);
    }

    /** Navigasi mulai/selesai, rit berubah, order selesai, setelan berubah → nilai ulang + segarkan antrean lokal. */
    public static void recheck() {
        final PickupNearbyMonitor m = sInstance;
        if (m == null || m.closed) return;
        m.h.post(() -> {
            m.localAt = 0L;
            m.evaluateNow();
            if (m.active) {
                long now = System.currentTimeMillis();
                m.handle(m.tracker.sync(m.candidates(now), now));
                m.publish();
            }
        });
    }

    /** Service berhenti: GPS dicabut, peringatan tampil ditutup (tanpa laporan). */
    void shutdown() {
        closed = true;
        if (sInstance == this) sInstance = null;
        sCurrent = null;
        notifyUi();
        h.post(() -> {
            stopActive();
            thread.quitSafely();
        });
        net.shutdown();
    }

    /** "📥 Ambil" (notifikasi / pita panel navigasi): tak dilaporkan terlewat + jeda 20 menit. */
    public static void markActed(Context ctx, String key) {
        if (key == null || key.isEmpty()) return;
        cancelAlertNotification(ctx, key);
        final PickupNearbyMonitor m = sInstance;
        if (m == null || m.closed) return;
        m.h.post(() -> {
            m.tracker.markActed(key, System.currentTimeMillis());
            m.shownBucket.remove(key);
            m.publish();
        });
    }

    /** "Nanti": peringatan ditutup + jeda 20 menit — TETAP dilaporkan terlewat bila kurir menjauh. */
    public static void snooze(Context ctx, String key) {
        if (key == null || key.isEmpty()) return;
        cancelAlertNotification(ctx, key);
        final PickupNearbyMonitor m = sInstance;
        if (m == null || m.closed) return;
        m.h.post(() -> {
            m.tracker.markSnoozed(key, System.currentTimeMillis());
            m.shownBucket.remove(key);
            m.publish();
        });
    }

    /** Peringatan tampil terdekat (pita panel navigasi); null bila tak ada. */
    @Nullable
    public static PickupNearbyLogic.Alert currentAlert() {
        return sCurrent;
    }

    /** Satu pendengar saja (panel navigasi bersifat singleTask). Dipanggil di thread UI. */
    public static void setUiListener(@Nullable Runnable r) {
        sUiListener = r;
    }

    public static void clearUiListener(Runnable r) {
        if (sUiListener == r) sUiListener = null;
    }

    /** Intent "📥 Ambil": antrean menjalankan alur Selesai pickup untuk kandidat ini. */
    static Intent ambilIntent(Context ctx, Candidate c, boolean fromNav) {
        return new Intent(ctx, DeliveryQueueActivity.class)
                .putExtra(DeliveryQueueActivity.EXTRA_NEARBY_PICKUP_UUID, c.uuid)
                .putExtra(DeliveryQueueActivity.EXTRA_NEARBY_PICKUP_SOURCE, c.source)
                .putExtra(DeliveryQueueActivity.EXTRA_NEARBY_PICKUP_LOCAL_ID, c.localId)
                .putExtra(DeliveryQueueActivity.EXTRA_NEARBY_PICKUP_EXPECTED, c.expectedDevice)
                .putExtra(DeliveryQueueActivity.EXTRA_NEARBY_PICKUP_NAME, c.name)
                .putExtra(DeliveryQueueActivity.EXTRA_NEARBY_PICKUP_FROM_NAV, fromNav)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
    }

    // ================================================================== aktif / tidak

    private void evaluateNow() {
        if (closed) return;
        boolean want = false;
        try {
            SettingsDao sdao = new SettingsDao(DatabaseHelper.getInstance(app));
            SyncSettings cfg = new SyncSettings(sdao);
            radiusM = cfg.getPickupAlertRadiusM();
            boolean running = !sdao.getDeliveryRunningTrxIds().isEmpty();
            want = cfg.isEnrolled() && hasFineLocation()
                    && PickupNearbyLogic.shouldBeActive(cfg.isPickupAlertEnabled(), running, NavigationPipActivity.isLive());
        } catch (Throwable ignored) {
        }
        if (want && !active) startActive();
        else if (!want && active) stopActive();
        if (active) refreshCandidates(false);
        publish();
    }

    private void startActive() {
        LocationRequest req = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, FIX_INTERVAL_MS)
                .setMinUpdateIntervalMillis(FIX_MIN_INTERVAL_MS)
                .setMinUpdateDistanceMeters(FIX_MIN_DISTANCE_M)
                .build();
        LocationCallback cb = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult result) {
                Location loc = result.getLastLocation();
                if (loc != null) onFix(loc);
            }
        };
        try {
            fused.requestLocationUpdates(req, cb, thread.getLooper());
        } catch (SecurityException | IllegalStateException e) {
            return;
        }
        callback = cb;
        active = true;
        localAt = 0L;
        remoteAt = 0L;
        refreshCandidates(true);
        h.removeCallbacks(tick);
        h.postDelayed(tick, TICK_MS);
    }

    private void stopActive() {
        if (callback != null) {
            try {
                fused.removeLocationUpdates(callback);
            } catch (Exception ignored) {
            }
            callback = null;
        }
        active = false;
        h.removeCallbacks(tick);
        handle(tracker.clear());
        shownBucket.clear();
        local = Collections.emptyList();
        remote = Collections.emptyList();
        remoteOkAt = 0L;
        publish();
    }

    private boolean hasFineLocation() {
        return ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    // ================================================================== fix GPS

    private void onFix(Location loc) {
        if (!active || closed) return;
        if (!PickupNearbyLogic.acceptFix(loc.hasAccuracy(), loc.getAccuracy(), radiusM)) return;
        long ageMs = (SystemClock.elapsedRealtimeNanos() - loc.getElapsedRealtimeNanos()) / 1_000_000L;
        if (ageMs > FIX_MAX_AGE_MS) return;
        long now = System.currentTimeMillis();
        refreshCandidates(false);
        handle(tracker.onFix(candidates(now), loc.getLatitude(), loc.getLongitude(), radiusM, now));
        publish();
    }

    private List<Candidate> candidates(long now) {
        List<Candidate> r = now - remoteOkAt <= REMOTE_STALE_MS ? remote : Collections.<Candidate>emptyList();
        return PickupNearbyLogic.merge(local, r);
    }

    // ================================================================== kandidat

    private void refreshCandidates(boolean force) {
        long now = System.currentTimeMillis();
        if (force || now - localAt >= LOCAL_REFRESH_MS) {
            localAt = now;
            local = loadLocal();
        }
        if (!remoteLoading && (force || now - remoteAt >= REMOTE_REFRESH_MS)) {
            remoteAt = now;
            loadRemoteAsync();
        }
    }

    /** Antrean Saya + Pesanan Terbuka lokal: KEMBALI bertitik peta, bukan rit berjalan / tujuan panel navigasi. */
    private List<Candidate> loadLocal() {
        try {
            DatabaseHelper db = DatabaseHelper.getInstance(app);
            TransactionDao dao = new TransactionDao(db);
            Set<Long> running = new SettingsDao(db).getDeliveryRunningTrxIds();
            Set<Long> navTargets = NavigationPipActivity.liveTargetIds();
            List<Candidate> out = new ArrayList<>();
            for (Transaction t : dao.getDeliveryQueue()) {
                if (!Transaction.TYPE_KEMBALI.equals(t.getType()) || t.hasPendingVoidRequest()) continue;
                boolean busy = running.contains(t.getId()) || navTargets.contains(t.getId());
                if (busy || t.isVisitTask() || !DeliveryPlanner.hasGeo(t)) continue;
                Candidate c = PickupNearbyLogic.localCandidate(t.getId(), dao.getSyncUuidById(t.getId()),
                        t.getType(), t.getCatatan(), t.getJumlahGalon(), DeliveryPlanner.lat(t), DeliveryPlanner.lng(t),
                        t.isOpenDispatch(), false, t.getCustomerName());
                if (c != null) out.add(c);
            }
            return out;
        } catch (Throwable e) {
            return local;   // DB sibuk — pakai daftar sebelumnya
        }
    }

    private void loadRemoteAsync() {
        final SyncSettings cfg;
        try {
            cfg = new SyncSettings(new SettingsDao(DatabaseHelper.getInstance(app)));
            if (!cfg.isEnrolled()) return;
        } catch (Throwable e) {
            return;
        }
        remoteLoading = true;
        try {
            net.execute(() -> {
                List<Candidate> got = null;
                try {
                    JSONArray q = new SyncApi(cfg).devicesQueueAll().optJSONArray("queue");
                    got = parseRemote(q, cfg.getDeviceUuid());
                } catch (Throwable ignored) {
                    // offline / server sibuk → diam; daftar lama tetap dipakai sampai basi
                }
                final List<Candidate> fGot = got;
                h.post(() -> {
                    remoteLoading = false;
                    if (fGot != null) {
                        remote = fGot;
                        remoteOkAt = System.currentTimeMillis();
                    }
                });
            });
        } catch (Exception e) {
            remoteLoading = false;   // executor ditutup
        }
    }

    private static List<Candidate> parseRemote(@Nullable JSONArray q, String myDevice) {
        List<Candidate> out = new ArrayList<>();
        if (q == null) return out;
        for (int i = 0; i < q.length(); i++) {
            JSONObject o = q.optJSONObject(i);
            if (o == null) continue;
            // Server sudah mengecualikan antrean pemanggil; jaga-jaga bila tidak.
            if (myDevice != null && !myDevice.isEmpty() && myDevice.equals(str(o, "device_group_uuid"))) continue;
            Candidate c = PickupNearbyLogic.remoteCandidate(str(o, "uuid"), str(o, "name"), o.optInt("galon", 0),
                    num(o, "latitude"), num(o, "longitude"), o.optBoolean("pickup_only", false),
                    o.optBoolean("in_progress", false), o.optBoolean("open_dispatch", false),
                    str(o, "device_group_label"), str(o, "routed_uuid"), str(o, "note"));
            if (c != null) out.add(c);
        }
        return out;
    }

    private static String str(JSONObject o, String k) {
        if (!o.has(k) || o.isNull(k)) return "";
        String v = o.optString(k, "");
        return "null".equals(v) ? "" : v;
    }

    @Nullable
    private static Double num(JSONObject o, String k) {
        if (!o.has(k) || o.isNull(k)) return null;
        double v = o.optDouble(k, Double.NaN);
        return Double.isNaN(v) ? null : v;
    }

    // ================================================================== kejadian → notifikasi / laporan

    private void handle(List<Event> evs) {
        if (evs == null) return;
        for (Event e : evs) {
            if (e == null || e.cand == null) continue;
            switch (e.type) {
                case PickupNearbyLogic.EV_ALERT:
                case PickupNearbyLogic.EV_REALERT:
                    postAlert(e, true);
                    break;
                case PickupNearbyLogic.EV_UPDATE:
                    Long b = shownBucket.get(e.cand.key);
                    if (b == null || b != bucket(e.distanceM)) postAlert(e, false);
                    break;
                case PickupNearbyLogic.EV_PASSED:
                    cancelAlertNotification(app, e.cand.key);
                    shownBucket.remove(e.cand.key);
                    reportPassed(e.cand, PickupNearbyLogic.reportDistance(e.minDistanceM), 0);
                    postPassed(e.cand);
                    break;
                case PickupNearbyLogic.EV_CANCEL:
                    cancelAlertNotification(app, e.cand.key);
                    shownBucket.remove(e.cand.key);
                    break;
                default:
                    break;
            }
        }
    }

    private static long bucket(double d) {
        return Double.isNaN(d) ? -1L : Math.round(d / 5.0);
    }

    /** @param loud true = bunyi + getar + heads-up (peringatan pertama / ulang); false = perbarui teks saja. */
    private void postAlert(Event e, boolean loud) {
        Candidate c = e.cand;
        ensureChannels(app);
        String text = PickupNearbyLogic.alertText(c.galon, e.distanceM, c.source, c.holder);
        // "Nanti" — juga dipakai saat kurir MENGUSAP peringatan (deleteIntent), supaya pembaruan jarak
        // berikutnya tak memunculkannya lagi; tetap dilaporkan terlewat bila ia menjauh.
        PendingIntent later = PendingIntent.getBroadcast(app, ("nanti:" + c.key).hashCode(),
                new Intent(app, LaterReceiver.class).setAction(LaterReceiver.ACTION_LATER)
                        .putExtra(LaterReceiver.EXTRA_KEY, c.key),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder b = new NotificationCompat.Builder(app, CHANNEL_ALERT)
                .setSmallIcon(R.drawable.ic_inbox)
                .setContentTitle(PickupNearbyLogic.alertTitle(c.name))
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text + "\n" + PickupNearbyLogic.ALERT_FOOTER))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setColor(COLOR_ALERT)
                .setOngoing(true)
                .setAutoCancel(false)
                .setOnlyAlertOnce(!loud)
                .setTimeoutAfter(ALERT_TIMEOUT_MS)
                .addAction(0, "📥 Ambil", PendingIntent.getActivity(app, ("ambil:" + c.key).hashCode(),
                        ambilIntent(app, c, false), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
                .addAction(0, "Nanti", later)
                .setDeleteIntent(later);
        try {
            NotificationManagerCompat.from(app).notify(c.key, NOTIF_ALERT, b.build());
            shownBucket.put(c.key, bucket(e.distanceM));
        } catch (SecurityException ignored) {
            // Android 13+ tanpa izin notifikasi — pita panel navigasi tetap tampil
        }
    }

    private void postPassed(Candidate c) {
        ensureChannels(app);
        String title = PickupNearbyLogic.passedTitle(c.name);
        String text = c.galon + " galon kembali · " + PickupNearbyLogic.sourceLabel(c.source, c.holder);
        NotificationCompat.Builder b = new NotificationCompat.Builder(app, CHANNEL_PASSED)
                .setSmallIcon(R.drawable.ic_inbox)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(title + "\n" + text))
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setColor(COLOR_PASSED)
                .setOnlyAlertOnce(true)
                .setTimeoutAfter(PASSED_TIMEOUT_MS);
        try {
            NotificationManagerCompat.from(app).notify(c.key, NOTIF_PASSED, b.build());
        } catch (SecurityException ignored) {
        }
    }

    /** POST /api/delivery/pickup-passed (best effort; diulang bila offline / server 5xx). */
    private void reportPassed(Candidate c, int distanceM, int attempt) {
        if (closed || c.uuid.isEmpty()) return;   // baris lokal belum tersinkron: server tak mengenalnya
        try {
            net.execute(() -> {
                boolean retry;
                try {
                    SyncSettings cfg = new SyncSettings(new SettingsDao(DatabaseHelper.getInstance(app)));
                    if (!cfg.isEnrolled()) return;
                    new SyncApi(cfg).pickupPassed(c.uuid, distanceM);
                    retry = false;
                } catch (SyncApi.SyncException se) {
                    retry = se.code >= 500 || se.code == 429;   // 404/422 = order sudah bukan galon kembali menunggu
                } catch (Throwable t) {
                    retry = true;   // offline
                }
                int next = attempt + 1;
                if (retry && next < REPORT_RETRY_MS.length && !closed) {
                    h.postDelayed(() -> reportPassed(c, distanceM, next), REPORT_RETRY_MS[next]);
                }
            });
        } catch (Exception ignored) {
            // executor ditutup
        }
    }

    private void publish() {
        PickupNearbyLogic.Alert a = active && !closed ? tracker.nearestAlerted() : null;
        sCurrent = a;
        String sig = a == null ? "" : a.cand.key + "|" + Math.round(a.distanceM);
        if (sig.equals(lastSig)) return;
        lastSig = sig;
        notifyUi();
    }

    private void notifyUi() {
        Runnable l = sUiListener;
        if (l != null) main.post(l);
    }

    // ================================================================== notifikasi

    static void ensureChannels(Context ctx) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) return;
        if (nm.getNotificationChannel(CHANNEL_ALERT) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ALERT,
                    "Galon Kembali di Sekitar Rute", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Peringatan saat lewat dekat pelanggan yang punya tugas galon kembali (navigasi / rit berjalan)");
            AudioAttributes aa = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            ch.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), aa);
            ch.enableVibration(true);
            ch.setVibrationPattern(VIBRATION);
            ch.enableLights(true);
            ch.setLightColor(COLOR_ALERT);
            nm.createNotificationChannel(ch);
        }
        if (nm.getNotificationChannel(CHANNEL_PASSED) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_PASSED,
                    "Galon Kembali Terlewat", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Catatan galon kembali yang terlewat tanpa diambil (tercatat di Jurnal Pesanan)");
            nm.createNotificationChannel(ch);
        }
    }

    static void cancelAlertNotification(Context ctx, String key) {
        try {
            NotificationManagerCompat.from(ctx).cancel(key, NOTIF_ALERT);
        } catch (Exception ignored) {
        }
    }

    private void cancelStaleAlerts() {
        try {
            NotificationManager nm = app.getSystemService(NotificationManager.class);
            if (nm == null) return;
            for (StatusBarNotification sbn : nm.getActiveNotifications()) {
                if (sbn.getId() == NOTIF_ALERT) nm.cancel(sbn.getTag(), NOTIF_ALERT);
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * Aksi "Nanti" notifikasi peringatan (siaran eksplisit, tidak diekspor). Penerima manifest — bukan
     * dinamis — supaya tetap bisa menutup peringatan walau proses sempat dimatikan.
     */
    public static class LaterReceiver extends BroadcastReceiver {
        static final String ACTION_LATER = "com.crowja.damiupos.action.PICKUP_NEARBY_LATER";
        static final String EXTRA_KEY = "pickup_nearby_key";

        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || !ACTION_LATER.equals(intent.getAction())) return;
            snooze(context, intent.getStringExtra(EXTRA_KEY));
        }
    }
}
