package com.crowja.damiupos;

import android.app.Activity;
import android.app.AppOpsManager;
import android.app.PendingIntent;
import android.app.PictureInPictureParams;
import android.app.RemoteAction;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.provider.Settings;
import android.util.Rational;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Lifecycle;

import com.crowja.damiupos.checkout.CheckoutStrukText;
import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.ProductDao;
import com.crowja.damiupos.db.SettingsDao;
import com.crowja.damiupos.db.TransactionDao;
import com.crowja.damiupos.model.Product;
import com.crowja.damiupos.model.Transaction;
import com.crowja.damiupos.model.TransactionItem;
import com.crowja.damiupos.sync.SyncEngine;
import com.crowja.damiupos.util.DeliveryNavLogic;
import com.crowja.damiupos.util.DeliveryNavLogic.DoorMoney;
import com.crowja.damiupos.util.DeliveryNavLogic.PayKind;
import com.crowja.damiupos.util.DeliveryNavLogic.StopState;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 🧭 NAVIGASI PENGIRIMAN — jendela melayang (Picture-in-Picture) di atas Google Maps.
 *
 * <p>Alur: tombol 🧭 Navigasi rit di Antrian Saya (atau "🧭 Navigasi" satu order di Preview / panel
 * mode terpandu) meluncurkan layar ini di TASK-nya sendiri (taskAffinity terpisah di manifest, jadi
 * antrean tidak ikut mengecil), lalu layar ini masuk PiP dan membuka Google Maps layar penuh. Kurir
 * mengemudi dengan Maps sementara DAMIU POS melayang menampilkan perhentian AKTIF: nama konsumen,
 * jumlah galon, total yang ditagih, "Perhentian k/N" dan nama lokasi (pelanggan multi-lokasi).
 *
 * <p>Data dibaca SEGAR dari DB (bukan dititipkan lewat Intent): antrean hanya mengirim id lokal
 * order rit (urutan rit), lalu layar ini menyegarkan diri saat resume / ganti mode PiP / sinkron
 * selesai / tiap 30 detik — jadi setelah kurir menandai Selesai di aplikasi, jendela melayang
 * menampilkan perhentian berikutnya. Begitu semua habis: "Rit selesai ✓" lalu menutup sendiri.
 *
 * <p>Diketuk/diperbesar → layar penuh dengan tombol "🧭 Lanjut Navigasi" (rute perhentian tersisa)
 * dan "📋 Kembali ke Antrian". PiP tak didukung HP / dimatikan pengguna untuk aplikasi ini → Maps
 * tetap dibuka (antrean membukanya langsung, seperti dulu) + petunjuk SEKALI cara mengaktifkannya.
 * Tanpa izin baru (bukan overlay SYSTEM_ALERT_WINDOW) — PiP bawaan Android saja.
 */
public class NavigationPipActivity extends AppCompatActivity {

    static final String EXTRA_TRX_IDS = "nav_pip_trx_ids";
    static final String EXTRA_MAPS_URL = "nav_pip_maps_url";
    static final String EXTRA_SINGLE = "nav_pip_single";
    static final String EXTRA_LAT = "nav_pip_lat";
    static final String EXTRA_LNG = "nav_pip_lng";

    /** Aksi PiP "Buka DAMIU" — siaran ke penerima dinamis milik layar ini (tidak diekspor). */
    private static final String ACTION_OPEN_QUEUE = "com.crowja.damiupos.action.NAV_PIP_OPEN_QUEUE";

    private static final String PREFS = "nav_pip";
    private static final String KEY_HINT_SHOWN = "pip_hint_shown";

    private static final long REFRESH_MS = 30000L;
    private static final long FINISH_DELAY_MS = 4000L;
    /** Jeda kecil sebelum masuk PiP: jendela sempat tergambar dulu, animasi mengecil tak kosong. */
    private static final long LAUNCH_DELAY_MS = 150L;
    /**
     * Cadangan onNewIntent saat masih melayang: normalnya sistem memperbesar jendela (±300 ms
     * animasi, lebih lama di HP lambat) lalu onResume yang meluncurkan. Hanya bila setelah selama ini
     * jendela TETAP melayang (ROM yang tak memperbesar) Maps dibuka dari sini.
     */
    private static final long PINNED_RESTART_FALLBACK_MS = 1500L;

    /**
     * Instans yang hidup (thread UI saja) — tombol Navigasi berikutnya mengarahkan ulang jendela yang
     * sedang MELAYANG di tempat ({@link #retargetPinned}) alih-alih startActivity.
     */
    private static WeakReference<NavigationPipActivity> sLive;

    // ---- keadaan (thread UI) ----
    /** Id lokal order rit menurut URUTAN rit; order yang masuk rit belakangan ditambahkan di ekor. */
    private final List<Long> ids = new ArrayList<>();
    private boolean single;
    private double fallbackLat, fallbackLng;
    /** URL rute dari antrean untuk peluncuran pertama — persis rute yang dulu dibuka tombol itu. */
    private String launchMapsUrl;
    /** Buka Maps + masuk PiP SEKALI pada kesempatan berikutnya (onCreate / onNewIntent). */
    private boolean pendingLaunch;
    /** PiP gagal dimasuki → tetap layar penuh (di belakang Maps) + kartu petunjuk. */
    private boolean pipFailed;
    /** Sedang menuju antrean / menutup diri — jangan masuk PiP otomatis lagi. */
    private boolean leaving;
    private boolean finishScheduled;
    /** Naik tiap intent baru: hasil muat dari intent lama dibuang. */
    private int gen;
    private Snapshot last;

    /** Order yang sudah dipastikan DONE — status final, tak perlu dibaca ulang tiap penyegaran. */
    private final Set<Long> doneIds = Collections.newSetFromMap(new ConcurrentHashMap<Long, Boolean>());

    private final Handler ui = new Handler(Looper.getMainLooper());
    private ExecutorService io;

    private View pipCompact, fullRoot, pipHintCard, btnLanjut;
    private TextView tvPipStop, tvPipName, tvPipGalon, tvPipPay;
    private TextView tvFullStop, tvFullName, tvFullLoc, tvFullGalon, tvFullProducts, tvFullPay, tvFullPayHint;
    private TextView tvFullNext, tvFullNoGeo, tvPipHint;

    private final Runnable refreshTick = new Runnable() {
        @Override
        public void run() {
            refresh();
            ui.postDelayed(this, REFRESH_MS);
        }
    };

    private final Runnable launchRunnable = this::launchNow;

    private final Runnable finishRunnable = () -> {
        leaving = true;
        disableAutoEnter();
        finishAndRemoveTask();
    };

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent != null ? intent.getAction() : null;
            if (ACTION_OPEN_QUEUE.equals(action)) {
                openQueueAndClose();
            } else if (SyncEngine.ACTION_SYNCED.equals(action)) {
                refresh();
            }
        }
    };

    // =====================================================================================
    // Pintu masuk dari DeliveryQueueActivity
    // =====================================================================================

    /**
     * Navigasi RIT: buka jendela melayang yang lalu membuka {@code mapsUrl} di Google Maps.
     * @param ritIds  id lokal SEMUA perhentian rit bertitik peta, urutan rit (Maps hanya 10 pertama;
     *                jendela melayang tetap memantau sampai habis)
     * @return false → jendela melayang tak dipakai (PiP tak didukung/dimatikan, tak ada aplikasi
     *         peta, atau gagal diluncurkan) — pemanggil membuka Maps langsung seperti dulu.
     */
    static boolean launchRit(Activity from, long[] ritIds, String mapsUrl) {
        if (ritIds == null || ritIds.length == 0 || mapsUrl == null) return false;
        if (!canOpen(from, routeIntent(from, mapsUrl, true))) return false;
        Intent i = new Intent(from, NavigationPipActivity.class)
                .putExtra(EXTRA_TRX_IDS, ritIds)
                .putExtra(EXTRA_MAPS_URL, mapsUrl)
                .putExtra(EXTRA_SINGLE, false);
        return launch(from, i);
    }

    /** Navigasi SATU order (Preview / panel mode terpandu) — sama, untuk satu tujuan. */
    static boolean launchSingle(Activity from, long trxId, double lat, double lng) {
        if (trxId <= 0 || !DeliveryNavLogic.hasGeo(lat, lng)) return false;
        Intent i = new Intent(from, NavigationPipActivity.class)
                .putExtra(EXTRA_TRX_IDS, new long[]{trxId})
                .putExtra(EXTRA_SINGLE, true)
                .putExtra(EXTRA_LAT, lat)
                .putExtra(EXTRA_LNG, lng);
        return launch(from, i);
    }

    private static boolean launch(Activity from, Intent i) {
        if (retargetPinned(i)) return true;
        if (!isPipUsable(from)) return false;
        try {
            from.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Jendela sedang MELAYANG → arahkan ulang DI TEMPAT (perhentian baru + buka Maps dari sini),
     * tanpa startActivity. Memulai ulang activity di task yang ter-pin membuat SystemUI MEMPERBESAR
     * jendelanya (onActivityRestartAttempt → expandPip/expandLeavePip, ±300 ms animasi sebelum
     * onResume): tiap tekan berkedip membesar-mengecil, dan di HP lambat Maps bisa terbuka saat
     * jendela masih membesar sehingga ringkasan tertinggal layar penuh DI BELAKANG Maps. Dipanggil
     * di thread UI (klik di antrean), sama dengan thread instansnya.
     *
     * @return true = sudah ditangani (Maps dibuka, atau gagal dengan toast "Tidak ada aplikasi peta").
     */
    private static boolean retargetPinned(Intent i) {
        NavigationPipActivity live = sLive != null ? sLive.get() : null;
        if (live == null || live.isFinishing() || live.isDestroyed()) return false;
        boolean pinned;
        try {
            pinned = live.isInPictureInPictureMode();
        } catch (RuntimeException e) {
            pinned = false;
        }
        if (!pinned) return false;   // layar penuh / PiP gagal → startActivity biasa (onNewIntent + onResume)
        live.adoptTarget(i);
        live.pendingLaunch = false;
        live.ui.removeCallbacks(live.launchRunnable);
        live.pipFailed = false;
        live.refresh();
        if (!live.openMaps(true)) {
            live.leaving = true;
            live.disableAutoEnter();
            live.finishAndRemoveTask();
        }
        return true;
    }

    // =====================================================================================
    // Maps — dipakai jendela melayang DAN jalur cadangan antrean
    // =====================================================================================

    /** Rute banyak titik: aplikasi Google Maps bila terpasang, selain itu penangan VIEW apa pun. */
    static Intent routeIntent(Context ctx, String url, boolean newTask) {
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(DeliveryNavLogic.MAPS_PACKAGE);
        if (i.resolveActivity(ctx.getPackageManager()) == null) {
            i.setPackage(null);
        }
        if (newTask) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return i;
    }

    /** Satu tujuan: google.navigation (langsung belokan-demi-belokan), cadangan pin peta web. */
    static Intent singleNavIntent(Context ctx, double lat, double lng, boolean newTask) {
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(DeliveryNavLogic.navigationUri(lat, lng)))
                .setPackage(DeliveryNavLogic.MAPS_PACKAGE);
        if (i.resolveActivity(ctx.getPackageManager()) == null) {
            i = new Intent(Intent.ACTION_VIEW, Uri.parse(DeliveryNavLogic.webPinUrl(lat, lng)));
        }
        if (newTask) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return i;
    }

    private static boolean canOpen(Context ctx, Intent i) {
        try {
            return i.resolveActivity(ctx.getPackageManager()) != null;
        } catch (Exception e) {
            return false;
        }
    }

    /** Buka peta; "Tidak ada aplikasi peta" bila gagal. */
    static boolean startMaps(Context ctx, Intent i) {
        try {
            ctx.startActivity(i);
            return true;
        } catch (Exception e) {
            Toast.makeText(ctx, "Tidak ada aplikasi peta", Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    // =====================================================================================
    // Ketersediaan PiP
    // =====================================================================================

    /** HP mendukung PiP (Android Go / RAM kecil sering tidak). */
    static boolean isPipSupported(Context ctx) {
        try {
            return ctx.getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE);
        } catch (Exception e) {
            return false;
        }
    }

    /** Pengguna TIDAK mematikan "Gambar-dalam-gambar" untuk aplikasi ini (Setelan › Akses khusus). */
    @SuppressWarnings("deprecation")
    static boolean isPipAllowedByUser(Context ctx) {
        try {
            AppOpsManager ops = (AppOpsManager) ctx.getSystemService(Context.APP_OPS_SERVICE);
            if (ops == null) return true;
            int mode = Build.VERSION.SDK_INT >= 29
                    ? ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_PICTURE_IN_PICTURE, Process.myUid(), ctx.getPackageName())
                    : ops.checkOpNoThrow(AppOpsManager.OPSTR_PICTURE_IN_PICTURE, Process.myUid(), ctx.getPackageName());
            return mode != AppOpsManager.MODE_IGNORED && mode != AppOpsManager.MODE_ERRORED;
        } catch (Exception e) {
            return true;   // tak bisa memastikan → coba saja; enterPictureInPictureMode yang memutuskan
        }
    }

    static boolean isPipUsable(Context ctx) {
        return isPipSupported(ctx) && isPipAllowedByUser(ctx);
    }

    /**
     * Petunjuk SEKALI (per pemasangan) saat PiP tak bisa dipakai — Maps tetap dibuka, ringkasan
     * perhentian saja yang tak melayang. Toast teks tetap tampil walau Maps sudah di depan.
     */
    static void showPipHintOnce(Context ctx) {
        if (isPipUsable(ctx)) return;
        try {
            SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            if (p.getBoolean(KEY_HINT_SHOWN, false)) return;
            p.edit().putBoolean(KEY_HINT_SHOWN, true).apply();
        } catch (Exception ignored) {
        }
        Toast.makeText(ctx, pipHintText(ctx), Toast.LENGTH_LONG).show();
    }

    private static String pipHintText(Context ctx) {
        if (!isPipSupported(ctx)) {
            return "HP ini tidak mendukung jendela melayang (Picture-in-Picture) — ringkasan perhentian "
                    + "hanya tampil di aplikasi.";
        }
        return "Jendela melayang DAMIU POS dimatikan. Aktifkan: Setelan › Aplikasi › DAMIU POS › "
                + "Gambar-dalam-gambar (Picture-in-picture).";
    }

    // =====================================================================================
    // Siklus hidup
    // =====================================================================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_navigation_pip);
        bindViews();
        sLive = new WeakReference<>(this);
        io = Executors.newSingleThreadExecutor();
        // Dibuat ulang: pakai sasaran TERAKHIR (retargetPinned/onNewIntent), bukan Intent peluncuran awal.
        boolean restored = savedInstanceState != null && savedInstanceState.containsKey(EXTRA_TRX_IDS);
        readIntent(restored ? new Intent().putExtras(savedInstanceState) : getIntent());
        // Dibuat ULANG (proses dibunuh saat melayang): kurir sudah di Maps — jangan dibuka lagi.
        pendingLaunch = savedInstanceState == null;

        IntentFilter f = new IntentFilter();
        f.addAction(ACTION_OPEN_QUEUE);
        f.addAction(SyncEngine.ACTION_SYNCED);   // sinkron menarik perubahan (mis. Selesai dari web)
        ContextCompat.registerReceiver(this, receiver, f, ContextCompat.RECEIVER_NOT_EXPORTED);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                // Layar penuh → kembali MELAYANG (navigasi masih berjalan); PiP tak bisa → tutup.
                if (!isInPictureInPictureMode() && enterPip()) {
                    pipFailed = false;
                    return;
                }
                leaving = true;
                disableAutoEnter();
                finishAndRemoveTask();
            }
        });

        applyMode(isInPictureInPictureMode());
        refresh();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Tombol Navigasi ditekan lagi (mis. setelah menandai Selesai) saat jendela ini TIDAK
        // melayang (layar penuh di belakang Maps / PiP gagal) → instans singleTask yang sama dipakai
        // ulang dengan rit/order baru. Yang sedang melayang sudah ditangani retargetPinned.
        adoptTarget(intent);
        pendingLaunch = true;
        refresh();
        // onResume menyusul (task dibawa ke depan / jendela diperbesar sistem) dan meluncurkan.
        // Cadangan HANYA bila ternyata masih melayang — lihat PINNED_RESTART_FALLBACK_MS & launchNow.
        if (isInPictureInPictureMode()) scheduleLaunch(PINNED_RESTART_FALLBACK_MS);
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle out) {
        super.onSaveInstanceState(out);
        long[] arr = new long[ids.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = ids.get(i);
        out.putLongArray(EXTRA_TRX_IDS, arr);
        out.putBoolean(EXTRA_SINGLE, single);
        out.putString(EXTRA_MAPS_URL, launchMapsUrl);
        out.putDouble(EXTRA_LAT, fallbackLat);
        out.putDouble(EXTRA_LNG, fallbackLng);
    }

    /** Ganti sasaran (rit / order) dari Intent baru; hasil muat milik sasaran lama dibuang. */
    private void adoptTarget(Intent intent) {
        setIntent(intent);
        readIntent(intent);
        gen++;
        last = null;
        leaving = false;
        finishScheduled = false;
        ui.removeCallbacks(finishRunnable);
    }

    @Override
    protected void onStart() {
        super.onStart();
        ui.removeCallbacks(refreshTick);
        ui.postDelayed(refreshTick, REFRESH_MS);
    }

    @Override
    protected void onResume() {
        super.onResume();
        leaving = false;
        // Pulang dari Setelan (petunjuk PiP) / Maps: nyalakan lagi auto-melayang (Android 12+).
        updateParams(true);
        refresh();
        if (pendingLaunch) scheduleLaunch(LAUNCH_DELAY_MS);
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Saat melayang activity hanya PAUSED (tetap terlihat) — onStop berarti benar-benar tak
        // terlihat, jadi penyegaran berkala boleh berhenti.
        ui.removeCallbacks(refreshTick);
    }

    @Override
    protected void onDestroy() {
        if (sLive != null && sLive.get() == this) sLive = null;
        ui.removeCallbacksAndMessages(null);
        try {
            unregisterReceiver(receiver);
        } catch (Exception ignored) {
        }
        if (io != null) io.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        // Android 12+ memakai setAutoEnterEnabled; versi lama: Home / pindah aplikasi dari layar
        // penuh → kembali melayang.
        if (Build.VERSION.SDK_INT < 31 && !leaving && !isInPictureInPictureMode() && enterPip()) {
            pipFailed = false;
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean inPip, @NonNull Configuration newConfig) {
        super.onPictureInPictureModeChanged(inPip, newConfig);
        if (!inPip && getLifecycle().getCurrentState() == Lifecycle.State.CREATED) {
            // Ditutup lewat ✕ jendela melayang (bukan diperbesar): selesai, jangan tertinggal
            // tersembunyi di task-nya sendiri.
            leaving = true;
            finishAndRemoveTask();
            return;
        }
        applyMode(inPip);
        refresh();
    }

    // =====================================================================================
    // Masuk PiP + buka Maps
    // =====================================================================================

    private void readIntent(Intent intent) {
        ids.clear();
        long[] arr = intent != null ? intent.getLongArrayExtra(EXTRA_TRX_IDS) : null;
        if (arr != null) {
            for (long id : arr) {
                if (id > 0 && !ids.contains(id)) ids.add(id);
            }
        }
        single = intent != null && intent.getBooleanExtra(EXTRA_SINGLE, false);
        launchMapsUrl = intent != null ? intent.getStringExtra(EXTRA_MAPS_URL) : null;
        fallbackLat = intent != null ? intent.getDoubleExtra(EXTRA_LAT, 0.0) : 0.0;
        fallbackLng = intent != null ? intent.getDoubleExtra(EXTRA_LNG, 0.0) : 0.0;
    }

    private void scheduleLaunch(long delayMs) {
        ui.removeCallbacks(launchRunnable);
        ui.postDelayed(launchRunnable, delayMs);
    }

    private void launchNow() {
        if (!pendingLaunch || isFinishing() || isDestroyed()) return;
        boolean inPip = isInPictureInPictureMode();
        if (!inPip && !getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED)) {
            // Sedang diperbesar / dibawa ke depan tapi belum RESUMED: enterPictureInPictureMode pasti
            // ditolak (→ kartu petunjuk palsu, lalu Maps menutupi layar penuh ini). pendingLaunch
            // dibiarkan — onResume yang meluncurkan.
            return;
        }
        pendingLaunch = false;
        if (!inPip) {
            inPip = enterPip();
            pipFailed = !inPip;
            applyMode(inPip);
        }
        if (!openMaps(true)) {
            // Tak ada aplikasi peta → jendela melayang tanpa navigasi tak ada gunanya.
            leaving = true;
            disableAutoEnter();
            finishAndRemoveTask();
            return;
        }
        if (pipFailed) showPipHintOnce(this);
    }

    /** Masuk PiP; false bila tak didukung / dimatikan / ditolak sistem — TIDAK pernah crash. */
    private boolean enterPip() {
        if (!isPipUsable(this)) return false;
        try {
            return enterPictureInPictureMode(buildParams(true));
        } catch (RuntimeException e) {
            // IllegalStateException (activity tak mendukung / keadaan tak sah), IllegalArgumentException
            // (rasio di luar batas perangkat), atau keanehan OEM — perlakukan sebagai "PiP tak bisa".
            return false;
        }
    }

    private PictureInPictureParams buildParams(boolean autoEnter) {
        PictureInPictureParams.Builder b = new PictureInPictureParams.Builder()
                .setAspectRatio(new Rational(16, 9));
        List<RemoteAction> actions = pipActions();
        if (!actions.isEmpty()) b.setActions(actions);
        if (Build.VERSION.SDK_INT >= 31) {
            // Home dari layar penuh → otomatis melayang lagi.
            b.setAutoEnterEnabled(autoEnter);
            // Isi teks, bukan video: cross-fade saat diubah ukurannya lebih rapi daripada diregang.
            b.setSeamlessResizeEnabled(false);
        }
        return b.build();
    }

    /** Aksi di menu jendela melayang: "Buka DAMIU" → antrean di depan + jendela ini ditutup. */
    private List<RemoteAction> pipActions() {
        List<RemoteAction> out = new ArrayList<>();
        try {
            Intent i = new Intent(ACTION_OPEN_QUEUE).setPackage(getPackageName());
            PendingIntent pi = PendingIntent.getBroadcast(this, 7801, i,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            out.add(new RemoteAction(Icon.createWithResource(this, R.drawable.ic_pip_open_app),
                    "Buka DAMIU", "Buka antrean DAMIU POS", pi));
        } catch (Exception ignored) {
        }
        return out;
    }

    /** Matikan auto-PiP sebelum sengaja pergi (ke antrean / Setelan / selesai) supaya tak berkedip melayang. */
    private void disableAutoEnter() {
        updateParams(false);
    }

    /** Perbarui parameter PiP (aksi + auto-melayang Android 12+) — diam bila PiP tak bisa dipakai. */
    private void updateParams(boolean autoEnter) {
        if (Build.VERSION.SDK_INT < 31 || !isPipUsable(this)) return;
        try {
            setPictureInPictureParams(buildParams(autoEnter));
        } catch (RuntimeException ignored) {
        }
    }

    /**
     * @param fromLaunchExtras true = peluncuran pertama: rute PERSIS kiriman antrean (data segar
     *                         mungkin belum termuat). false = "Lanjut Navigasi": perhentian tersisa.
     */
    private boolean openMaps(boolean fromLaunchExtras) {
        Snapshot s = last;
        Intent i;
        if (single) {
            double lat = fallbackLat, lng = fallbackLng;
            if (!fromLaunchExtras && s != null && s.active != null && DeliveryPlanner.hasGeo(s.active)) {
                lat = DeliveryPlanner.lat(s.active);
                lng = DeliveryPlanner.lng(s.active);
            }
            if (!DeliveryNavLogic.hasGeo(lat, lng)) return false;
            i = singleNavIntent(this, lat, lng, true);
        } else {
            String url = fromLaunchExtras || s == null ? launchMapsUrl : s.routeUrl;
            if (url == null && s != null && !fromLaunchExtras) {
                Toast.makeText(this, "Tidak ada perhentian tersisa", Toast.LENGTH_SHORT).show();
                return false;
            }
            if (url == null) return false;
            i = routeIntent(this, url, true);
        }
        return startMaps(this, i);
    }

    /** "🧭 Lanjut Navigasi" (layar penuh): melayang lagi + Maps untuk perhentian tersisa. */
    private void continueNavigation() {
        // Selalu dicoba lagi: pengguna mungkin baru mengaktifkan PiP lewat kartu petunjuk.
        boolean inPip = isInPictureInPictureMode() || enterPip();
        pipFailed = !inPip;
        applyMode(inPip);
        if (openMaps(false)) {
            if (pipFailed) showPipHintOnce(this);
            if (!single && last != null && last.pendingCount > DeliveryNavLogic.MAX_MAPS_STOPS) {
                Toast.makeText(this, "Dibatasi 10 tujuan (batas Google Maps)", Toast.LENGTH_SHORT).show();
            }
        }
    }

    /** "📋 Kembali ke Antrian" / aksi PiP "Buka DAMIU": antrean ke depan, jendela ini ditutup. */
    private void openQueueAndClose() {
        leaving = true;
        disableAutoEnter();
        Intent q = new Intent(this, DeliveryQueueActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        try {
            startActivity(q);
        } catch (Exception ignored) {
        }
        finishAndRemoveTask();
    }

    private void openPipSettings() {
        leaving = true;
        disableAutoEnter();
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", getPackageName(), null)));
        } catch (Exception e) {
            Toast.makeText(this, "Setelan tidak dapat dibuka", Toast.LENGTH_SHORT).show();
        }
    }

    // =====================================================================================
    // Data
    // =====================================================================================

    /** Hasil satu kali muat (thread latar) — dirender utuh di thread UI. */
    private static final class Snapshot {
        List<Long> order = new ArrayList<>();
        Transaction active;
        Transaction next;
        int k, n;
        boolean finished;
        int pendingCount;
        int noGeoCount;
        String routeUrl;
        String products = "";
        /** Uang di pintu perhentian aktif (refund & hutang lama) — sama dgn popup Detail/Preview antrean. */
        DoorMoney money;
        /** Leg checkout yang BUKAN pintu tagih hutang lama: di mana hutang itu ditagih; "" bila tak relevan. */
        String debtElsewhere = "";
        /** Pengingat tagih leg checkout ("" untuk order biasa) — sama dgn popup antrean. */
        String legHint = "";
    }

    private void refresh() {
        if (io == null || io.isShutdown()) return;
        final List<Long> order = new ArrayList<>(ids);
        final boolean singleMode = single;
        final int g = gen;
        final Context app = getApplicationContext();
        try {
            io.execute(() -> {
                Snapshot s;
                try {
                    s = load(app, order, singleMode);
                } catch (Exception e) {
                    s = null;   // DB sibuk/terkunci — coba lagi pada penyegaran berikutnya
                }
                final Snapshot fs = s;
                ui.post(() -> {
                    if (fs == null || g != gen || isDestroyed()) return;
                    apply(fs);
                });
            });
        } catch (Exception ignored) {
            // RejectedExecutionException saat sedang ditutup
        }
    }

    private Snapshot load(Context app, List<Long> order, boolean singleMode) {
        DatabaseHelper db = DatabaseHelper.getInstance(app);
        TransactionDao dao = new TransactionDao(db);
        // Antrian Saya + Pesanan Terbuka (PENDING) dengan koordinat efektif & lokasi tujuan terisi.
        Map<Long, Transaction> queued = new HashMap<>();
        for (Transaction t : dao.getDeliveryQueue()) {
            queued.put(t.getId(), t);
        }
        Set<Long> running = singleMode ? Collections.<Long>emptySet()
                : new SettingsDao(db).getDeliveryRunningTrxIds();

        List<Long> list = new ArrayList<>(order);
        if (!singleMode) {
            // Order yang MASUK rit setelah navigasi dimulai ("Tambah ke rit") ikut dipantau di ekor.
            for (Long id : running) {
                Transaction t = queued.get(id);
                if (!list.contains(id) && t != null && DeliveryPlanner.hasGeo(t)) list.add(id);
            }
        }

        List<StopState> states = new ArrayList<>(list.size());
        List<Transaction> rows = new ArrayList<>(list.size());
        for (Long id : list) {
            Transaction t = queued.get(id);
            // Rit: masih anggota rit berjalan (dihentikan/dilepas = bukan perhentian lagi) dan
            // bertitik peta (yang tanpa titik tak pernah masuk rute Maps).
            boolean pending = t != null && (singleMode || (DeliveryPlanner.hasGeo(t) && running.contains(id)));
            if (!pending && singleMode && t == null) {
                // Satu order di luar Antrian Saya (mis. Preview antrean Tertunda): masih menunggu
                // diantar selama statusnya PENDING/TERTUNDA — koordinat tetap dari Intent.
                Transaction fresh = dao.getById(id);
                if (fresh != null && TransactionDao.isQueuedForDelivery(fresh.getDeliveryStatus())) {
                    t = fresh;
                    pending = true;
                }
            }
            if (pending) {
                states.add(StopState.PENDING);
            } else if (doneIds.contains(id)) {
                states.add(StopState.DONE);
            } else {
                Transaction fresh = dao.getById(id);
                if (fresh != null && Transaction.DELIVERY_DONE.equals(fresh.getDeliveryStatus())) {
                    doneIds.add(id);
                    states.add(StopState.DONE);
                } else {
                    states.add(StopState.GONE);
                }
            }
            rows.add(t);
        }

        Snapshot s = new Snapshot();
        s.order = list;
        int a = DeliveryNavLogic.activeIndex(states);
        s.active = a >= 0 ? rows.get(a) : null;
        int nx = DeliveryNavLogic.nextPendingIndex(states, a);
        s.next = a >= 0 && nx >= 0 ? rows.get(nx) : null;
        s.k = DeliveryNavLogic.progressNumber(states);
        s.n = DeliveryNavLogic.trackedCount(states);
        s.finished = DeliveryNavLogic.isRitFinished(states);

        List<double[]> pts = new ArrayList<>();
        for (int i = 0; i < states.size(); i++) {
            Transaction t = rows.get(i);
            if (states.get(i) == StopState.PENDING && t != null && DeliveryPlanner.hasGeo(t)) {
                pts.add(new double[]{DeliveryPlanner.lat(t), DeliveryPlanner.lng(t)});
            }
        }
        s.pendingCount = pts.size();
        s.routeUrl = DeliveryNavLogic.buildDirUrl(pts, true);

        if (!singleMode) {
            for (Long id : running) {
                Transaction t = queued.get(id);
                if (t != null && !DeliveryPlanner.hasGeo(t)) s.noGeoCount++;
            }
        }
        if (s.active != null) {
            s.products = productLine(db, s.active);
            loadDoorMoney(db, s.active, s);
        }
        return s;
    }

    /**
     * Yang DITAGIH di pintu perhentian aktif: total_harga − saldo refund yang sudah memotongnya +
     * hutang lama yang ditagih di pintu ini. Dibaca lewat helper yang SAMA dengan popup Detail /
     * Preview antrean (refundSummaryText / debtSummaryText), jadi kurir tak melihat dua angka berbeda.
     */
    private static void loadDoorMoney(DatabaseHelper db, Transaction t, Snapshot s) {
        double refund = DeliveryQueueActivity.refundUsedFor(db, t);
        DeliveryQueueActivity.PriorDebt pd = DeliveryQueueActivity.priorDebtFor(db, t);
        s.money = new DoorMoney(t.getTotalHarga(), refund, pd.amount, pd.here());
        if (pd.amount > 0 && !pd.here() && pd.door != null) {
            s.debtElsewhere = CheckoutStrukText.oldDebtElsewhereLine(pd.door, DeliveryNavLogic.rupiah(pd.amount));
        }
        s.legHint = DeliveryQueueActivity.checkoutCollectHint(t);
    }

    /** "MIN ×2 · RO ×1" — slug produk (cermin kapsul kartu antrean), cadangan nama dipotong. */
    private static String productLine(DatabaseHelper db, Transaction t) {
        List<TransactionItem> items = t.getItems();
        if (items == null || items.isEmpty()) return "";
        Map<String, Product> byName = new HashMap<>();
        try {
            for (Product p : new ProductDao(db).getAll()) {
                String k = normName(p.getName());
                if (!k.isEmpty()) byName.put(k, p);
            }
        } catch (Exception ignored) {
        }
        List<String> labels = new ArrayList<>();
        List<Integer> qtys = new ArrayList<>();
        for (TransactionItem it : items) {
            if (it == null) continue;
            Product p = byName.get(normName(it.productName));
            String slug = p != null ? p.getSlug() : null;
            String label;
            if (slug != null && !slug.trim().isEmpty()) {
                label = slug.trim();
            } else {
                String nm = it.productName != null ? it.productName.trim() : "";
                label = nm.isEmpty() ? "Galon" : (nm.length() <= 10 ? nm : nm.substring(0, 10).trim() + "…");
            }
            labels.add(label);
            qtys.add(it.jumlah);
        }
        return DeliveryNavLogic.productSummary(labels, qtys);
    }

    private static String normName(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s+", " ").toLowerCase(Locale.getDefault());
    }

    // =====================================================================================
    // Tampilan
    // =====================================================================================

    private void bindViews() {
        pipCompact = findViewById(R.id.pipCompact);
        fullRoot = findViewById(R.id.fullRoot);
        pipHintCard = findViewById(R.id.pipHintCard);
        btnLanjut = findViewById(R.id.btnLanjutNavigasi);
        tvPipStop = findViewById(R.id.tvPipStop);
        tvPipName = findViewById(R.id.tvPipName);
        tvPipGalon = findViewById(R.id.tvPipGalon);
        tvPipPay = findViewById(R.id.tvPipPay);
        tvFullStop = findViewById(R.id.tvFullStop);
        tvFullName = findViewById(R.id.tvFullName);
        tvFullLoc = findViewById(R.id.tvFullLoc);
        tvFullGalon = findViewById(R.id.tvFullGalon);
        tvFullProducts = findViewById(R.id.tvFullProducts);
        tvFullPay = findViewById(R.id.tvFullPay);
        tvFullPayHint = findViewById(R.id.tvFullPayHint);
        tvFullNext = findViewById(R.id.tvFullNext);
        tvFullNoGeo = findViewById(R.id.tvFullNoGeo);
        tvPipHint = findViewById(R.id.tvPipHint);
        btnLanjut.setOnClickListener(v -> continueNavigation());
        findViewById(R.id.btnKembaliAntrian).setOnClickListener(v -> openQueueAndClose());
        findViewById(R.id.btnPipSettings).setOnClickListener(v -> openPipSettings());
        tvPipName.setText("Memuat…");
    }

    private void applyMode(boolean inPip) {
        pipCompact.setVisibility(inPip ? View.VISIBLE : View.GONE);
        fullRoot.setVisibility(inPip ? View.GONE : View.VISIBLE);
        boolean showHint = !inPip && (pipFailed || !isPipUsable(this));
        pipHintCard.setVisibility(showHint ? View.VISIBLE : View.GONE);
        if (showHint) {
            tvPipHint.setText(isPipUsable(this)
                    ? "Jendela melayang tidak bisa dibuka saat ini. Ringkasan perhentian tetap ada di layar ini."
                    : pipHintText(this));
        }
    }

    private void apply(Snapshot s) {
        last = s;
        for (Long id : s.order) {
            if (!ids.contains(id)) ids.add(id);
        }
        render(s);
        if (s.active == null) {
            if (!finishScheduled) {
                finishScheduled = true;
                ui.postDelayed(finishRunnable, FINISH_DELAY_MS);
            }
        } else if (finishScheduled) {
            finishScheduled = false;
            ui.removeCallbacks(finishRunnable);
        }
    }

    private void render(Snapshot s) {
        Transaction t = s.active;
        if (t == null) {
            String head = s.finished ? (single ? "Pengiriman selesai ✓" : "Rit selesai ✓") : "Tidak ada perhentian aktif";
            String sub = s.finished ? (single ? "Order sudah ditandai Selesai" : "Semua perhentian sudah diantar")
                    : (single ? "Order tidak ada di antrean lagi" : "Rit dihentikan atau order dipindah");
            tvPipStop.setText(single ? "🧭 Navigasi" : "🧭 Rit");
            tvPipName.setText(head);
            tvPipGalon.setText(sub);
            tvPipPay.setText("");
            tvFullStop.setText("");
            tvFullName.setText(head);
            tvFullLoc.setVisibility(View.GONE);
            tvFullGalon.setText(sub);
            tvFullProducts.setVisibility(View.GONE);
            tvFullPay.setText("");
            tvFullPayHint.setVisibility(View.GONE);
            tvFullNext.setVisibility(View.GONE);
            tvFullNoGeo.setVisibility(View.GONE);
            btnLanjut.setVisibility(View.GONE);
            return;
        }
        btnLanjut.setVisibility(View.VISIBLE);

        String name = t.getCustomerName() != null && !t.getCustomerName().trim().isEmpty()
                ? t.getCustomerName().trim() : "Pelanggan";
        String loc = clean(t.getDeliveryDestName());
        String stop = single ? "🧭 Navigasi" : "Perhentian " + s.k + "/" + s.n;

        boolean pickup = DeliveryPlanner.isPickupOnly(t);
        String galon = (pickup ? "↩ Ambil " : "💧 ") + t.getJumlahGalon() + " galon";

        boolean cashBon = t.getCatatan() != null && t.getCatatan().contains(TransactionDao.CASH_BON_MARKER);
        PayKind kind = DeliveryNavLogic.payKind(t.getTotalHarga(), t.getPaymentMethod(), t.isPaymentConfirmed(), cashBon);
        DoorMoney money = s.money != null ? s.money : DoorMoney.plain(t.getTotalHarga());
        PayKind shown = DeliveryNavLogic.displayKind(kind, money);
        // Angka = yang DITAGIH di pintu (setelah saldo refund, + hutang lama pintu ini) — bukan
        // total_harga kotor; rinciannya di layar penuh.
        String pay = "💰 " + DeliveryNavLogic.payLine(kind, money, t.getPaymentMethodLabel());

        // ---- jendela melayang (ringkas) ----
        tvPipStop.setText(loc.isEmpty() ? stop : stop + " · 📍 " + loc);
        tvPipName.setText("👤 " + name);
        // Kapsul produk hanya bila muat pendek — jumlah galon harus tetap besar terbaca; rincian
        // lengkapnya ada di layar penuh.
        String galonWithProducts = galon + " · " + s.products;
        tvPipGalon.setText(!s.products.isEmpty() && galonWithProducts.length() <= 24 ? galonWithProducts : galon);
        tvPipPay.setText(pay);
        tvPipPay.setTextColor(pipPayColor(shown));

        // ---- layar penuh ----
        tvFullStop.setText(single ? "🧭 Navigasi ke pelanggan" : "Perhentian " + s.k + " dari " + s.n);
        tvFullName.setText("👤 " + name);
        String addr = clean(t.getCustomerAddress());
        if (!loc.isEmpty()) {
            tvFullLoc.setText("📍 Kirim ke: " + loc);
            tvFullLoc.setVisibility(View.VISIBLE);
        } else if (!addr.isEmpty()) {
            tvFullLoc.setText("🏠 " + addr);
            tvFullLoc.setVisibility(View.VISIBLE);
        } else {
            tvFullLoc.setVisibility(View.GONE);
        }
        tvFullGalon.setText(galon);
        tvFullProducts.setText(s.products);
        tvFullProducts.setVisibility(s.products.isEmpty() ? View.GONE : View.VISIBLE);
        tvFullPay.setText(pay);
        tvFullPay.setTextColor(fullPayColor(shown));
        StringBuilder hintSb = new StringBuilder(DeliveryNavLogic.payHint(kind, money));
        // 🧺 Leg checkout: hutang lama ditagih di pintu lain / tagih hanya lokasi ini — kalimat yang
        // sama dengan popup Detail Order & Tandai Selesai.
        for (String extra : new String[]{s.debtElsewhere, s.legHint}) {
            if (extra == null || extra.trim().isEmpty()) continue;
            if (hintSb.length() > 0) hintSb.append('\n');
            hintSb.append(extra.trim());
        }
        String hint = hintSb.toString();
        tvFullPayHint.setText(hint);
        tvFullPayHint.setVisibility(hint.isEmpty() ? View.GONE : View.VISIBLE);

        if (s.next != null) {
            String nn = s.next.getCustomerName() != null ? s.next.getCustomerName().trim() : "";
            tvFullNext.setText("➡ Berikutnya: " + (nn.isEmpty() ? "Pelanggan" : nn));
            tvFullNext.setVisibility(View.VISIBLE);
        } else {
            tvFullNext.setVisibility(View.GONE);
        }
        if (s.noGeoCount > 0) {
            tvFullNoGeo.setText("⚠ " + s.noGeoCount + " order rit belum punya titik peta (tak masuk rute) — cek di antrean.");
            tvFullNoGeo.setVisibility(View.VISIBLE);
        } else {
            tvFullNoGeo.setVisibility(View.GONE);
        }
    }

    private static String clean(String s) {
        String v = s != null ? s.trim() : "";
        return "null".equalsIgnoreCase(v) ? "" : v;
    }

    /** Warna terang di atas biru tua jendela melayang. */
    private static int pipPayColor(PayKind k) {
        switch (k) {
            case PAID:
                return 0xFF86EFAC;
            case HUTANG:
            case CASH_BON:
                return 0xFFFCA5A5;
            case FREE:
                return 0xFFFFFFFF;
            case COLLECT:
            default:
                return 0xFFFDE047;
        }
    }

    /** Warna gelap di atas kartu putih layar penuh (biru = sama dengan badge TOTAL antrean). */
    private static int fullPayColor(PayKind k) {
        switch (k) {
            case PAID:
                return 0xFF15803D;
            case HUTANG:
            case CASH_BON:
                return 0xFFB91C1C;
            case FREE:
                return 0xFF475569;
            case COLLECT:
            default:
                return 0xFF0369A1;
        }
    }
}
