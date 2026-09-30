package com.crowja.damiupos;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.SettingsDao;
import com.crowja.damiupos.db.UserDao;
import com.crowja.damiupos.model.User;
import com.crowja.damiupos.pengisian.FillOutbox;
import com.crowja.damiupos.pengisian.FillOverlay;
import com.crowja.damiupos.pengisian.FillPlan;
import com.crowja.damiupos.pengisian.FillText;
import com.crowja.damiupos.pengisian.PengisianStore;
import com.crowja.damiupos.pengisian.PengisianSync;
import com.crowja.damiupos.sync.SyncApi;
import com.crowja.damiupos.sync.SyncSettings;
import com.crowja.damiupos.sync.VersionUpdater;
import com.crowja.damiupos.util.ForegroundDuties;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 🚰 "Pengisian" — layar panduan isi galon untuk karyawan berperan Pengisian (Day-time). Beranda
 * terkunci untuk peran itu ({@code MainActivity} mengalihkan ke sini sebelum setContentView dan di
 * onResume).
 *
 * <p>Semua angka (pesanan berjalan, cadangan, target, stok siap di rak, "isi N lagi") dirakit
 * SERVER ({@code GET /api/fill/plan} → App\Support\FillPlan) — layar ini TIDAK menghitung apa pun
 * dari transaksi lokal, karena transaksi device-isolated di lapisan sync dan HP tak mungkin melihat
 * pesanan perangkat lain. Yang dilakukan HP hanya: menampilkan, mencatat ketukan pekerja lewat
 * kotak keluar lokal ({@link PengisianStore}) yang ditempelkan sebagai overlay
 * ({@link FillOverlay}) supaya angka langsung turun tanpa ketuk dua kali, dan membunyikan tanda
 * saat kebutuhan naik.
 *
 * <p>Pola pemuatan disalin dari {@link DeliveryRecordActivity}: SwipeRefresh + Handler 15 dtk yang
 * hidup di onResume/mati di onPause + thread latar + penjaga isFinishing/isDestroyed; penyegaran
 * otomatis menelan galat, hanya yang manual yang melapor. Tombol Kembali diblokir untuk peran
 * Pengisian (satu-satunya jalan keluar yang sah: Pulang, lewat {@code EXTRA_AUTO_CLOCKOUT} seperti
 * {@code DeliveryQueueActivity.launchGuidedClockOut}).
 *
 * <p>Layar ini menggantikan beranda untuk peran Pengisian, jadi ia juga memikul kewajiban
 * onCreate/onResume MainActivity yang tak pernah dijalankan untuk peran itu ({@link ForegroundDuties}:
 * gerbang versi, nyala-ulang sinkron/online, popup pesan admin, izin notifikasi).
 *
 * <p>Catatan kompatibilitas: APK LAMA tak mengenal peran "pengisian" (tak absen, beranda penuh).
 * Terbitkan APK ini & tunggu update wajib (hash) menjangkau semua HP SEBELUM memberi seseorang
 * peran itu di dashboard — tak ada kode yang bisa memperbaiki perilaku APK lama dari sisi sini.
 *
 * <p>Layar dikunci potret di manifest: dialog (mis. hitung stok dengan angka yang sedang diketik)
 * tak dipertahankan saat rotasi/recreate. Bila activity tetap dibuat ulang (ganti tema/bahasa),
 * hanya {@code countPrompted} yang dipulihkan supaya "Hitung stok awal dulu" tak muncul lagi.
 */
public class PengisianActivity extends AppCompatActivity {

    /** Selaras dengan batas server: isi 1..500 per ketukan, hitung stok 0..100000, maks 60 produk. */
    private static final int MAX_FILL_QTY = 500;
    private static final int MAX_COUNT_QTY = 100_000;
    private static final int MAX_COUNT_ROWS = 60;

    /** Rencana bergerak tiap pesanan masuk/keluar — 15 dtk (server membalas murah bila rev sama). */
    private static final long POLL_MS = 15_000L;

    private static final int COLOR_NEED = Color.parseColor("#D84315");
    private static final int COLOR_MET = Color.parseColor("#2E7D32");
    private static final int COLOR_IDLE = Color.parseColor("#455A64");
    private static final int COLOR_NEUTRAL = Color.parseColor("#607D8B");

    private SettingsDao settingsDao;
    private SyncSettings cfg;
    private PengisianStore store;
    private User me;
    private String staffUuid = "";

    // ---- state (hanya disentuh thread UI)
    private FillPlan serverPlan;              // rencana server terakhir (tanpa overlay)
    private List<FillOutbox.Op> pendingOps = new ArrayList<>();
    private int foreignPending;               // ketukan staf LAIN yang tertahan di HP ini (tak ikut overlay)
    private long planAtMs;                    // kapan rencana itu terakhir dipastikan segar / disimpan
    private boolean offline;
    private String blockedMessage;
    private Integer prevToFill;               // total "perlu diisi" pada render sebelumnya
    private boolean seenFresh;                // sudah pernah render dari hasil segar (bukan cache)
    private boolean countPrompted;            // "Hitung stok awal dulu" sekali per sesi layar
    private boolean othersExpanded;
    private String lastRev = "";

    private static final String STATE_COUNT_PROMPTED = "countPrompted";

    /** Popup pesan admin yang sedang tampil (anti-tumpuk) + penerima broadcast pesan baru. */
    private AlertDialog adminMsgDialog;
    private boolean adminReceiverRegistered;
    private final BroadcastReceiver adminMsgReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            adminMsgDialog = ForegroundDuties.showPendingAdminMessage(
                    PengisianActivity.this, settingsDao, adminMsgDialog);
        }
    };

    private final AtomicBoolean syncBusy = new AtomicBoolean(false);
    private volatile boolean syncAgain;

    // ---- views
    private SwipeRefreshLayout swipe;
    private TextView tvStaff, tvUpdated, tvForeignNote, tvPendingOut, tvBanner, tvHeroTitle, tvHeroValue, tvHeroSub,
            tvUnmapped, tvOthersToggle, tvEmpty;
    private View heroBox;
    private LinearLayout productsBox, othersBox;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            requestSync(false);
            handler.postDelayed(this, POLL_MS);
        }
    };

    private Ringtone chime;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Penjaga sendiri: hanya Pengisian & Admin. Activity wajib menjaga dirinya — deep link &
        // notifikasi mendarat di sini tanpa lewat MainActivity.
        me = UserDao.currentUser(this);
        if (me == null || !me.canOpenPengisianScreen()) {
            Toast.makeText(this, "Layar ini khusus karyawan Pengisian", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        countPrompted = savedInstanceState != null && savedInstanceState.getBoolean(STATE_COUNT_PROMPTED, false);

        setContentView(R.layout.activity_pengisian);

        DatabaseHelper db = DatabaseHelper.getInstance(this);
        settingsDao = new SettingsDao(db);
        cfg = new SyncSettings(settingsDao);
        store = new PengisianStore(settingsDao);
        try {
            String u = new UserDao(db).getSyncUuidById(me.getId());
            staffUuid = u != null ? u : "";
        } catch (Exception ignored) {
        }

        tvStaff = findViewById(R.id.tvStaff);
        tvUpdated = findViewById(R.id.tvUpdated);
        tvForeignNote = findViewById(R.id.tvForeignNote);
        tvPendingOut = findViewById(R.id.tvPendingOut);
        tvBanner = findViewById(R.id.tvBanner);
        heroBox = findViewById(R.id.heroBox);
        tvHeroTitle = findViewById(R.id.tvHeroTitle);
        tvHeroValue = findViewById(R.id.tvHeroValue);
        tvHeroSub = findViewById(R.id.tvHeroSub);
        tvUnmapped = findViewById(R.id.tvUnmapped);
        productsBox = findViewById(R.id.productsBox);
        othersBox = findViewById(R.id.othersBox);
        tvOthersToggle = findViewById(R.id.tvOthersToggle);
        tvEmpty = findViewById(R.id.tvEmpty);
        swipe = findViewById(R.id.swipe);
        swipe.setOnRefreshListener(() -> requestSync(true));

        String name = settingsDao.getCurrentUserName();
        tvStaff.setText("Pengisian · " + (name != null && !name.isEmpty() ? name : me.getName()));

        findViewById(R.id.btnCount).setOnClickListener(v -> showCountDialog());
        findViewById(R.id.btnHistory).setOnClickListener(v -> showHistoryDialog());
        findViewById(R.id.btnPulang).setOnClickListener(v -> confirmPulang());
        tvOthersToggle.setOnClickListener(v -> {
            othersExpanded = !othersExpanded;
            render(false);
        });

        // Kembali diblokir untuk peran Pengisian (jalan keluar sah = Pulang). Admin yang membuka
        // layar ini untuk melihat boleh kembali seperti biasa.
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (me != null && me.usesPengisianHome()) {
                    Toast.makeText(PengisianActivity.this,
                            "Gunakan tombol Pulang untuk keluar.", Toast.LENGTH_SHORT).show();
                } else {
                    finish();
                }
            }
        });

        // Tampilkan dulu rencana terakhir yang tersimpan (banner menjelaskan kalau ternyata offline),
        // supaya membuka layar di depot tanpa sinyal tak menampilkan layar kosong.
        Object[] cached = store.loadCache();
        if (cached != null) {
            try {
                serverPlan = FillPlan.parse((String) cached[1]);
                lastRev = serverPlan.rev;
                planAtMs = (Long) cached[0];
            } catch (IllegalArgumentException ignored) {
                serverPlan = null;
            }
        }
        // Kewajiban layar induk yang tak pernah jalan untuk peran ini (MainActivity dialihkan sebelum
        // onCreate-nya rampung): izin notifikasi, sinkron berkala, cek versi (hash).
        ForegroundDuties.onLaunch(this);
        reloadPending();
        render(false);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(STATE_COUNT_PROMPTED, countPrompted);
    }

    /** Muat ulang kotak keluar: HANYA milik staf ini yang jadi overlay/penghitung; milik orang lain hanya dicatat jumlahnya. */
    private void reloadPending() {
        pendingOps = store.pendingFor(staffUuid);
        foreignPending = store.foreignCount(staffUuid);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (isFinishing()) return;
        // Peran bisa berubah/di-logout saat layar di background → re-cek, jangan biarkan pengguna
        // lain yang login di HP yang sama melihat layar ini.
        User cur = UserDao.currentUser(this);
        if (cur == null || !cur.canOpenPengisianScreen()) {
            finish();
            return;
        }
        me = cur;
        // Kewajiban onResume MainActivity yang dilewati peran ini: gerbang versi (dinonaktifkan +
        // wajib-update hash), sinkron + service online, popup pesan admin.
        ForegroundDuties.onResume(this);
        VersionUpdater.maybePrompt(this);
        if (!adminReceiverRegistered) {
            ForegroundDuties.registerAdminMessageReceiver(this, adminMsgReceiver);
            adminReceiverRegistered = true;
        }
        adminMsgDialog = ForegroundDuties.showPendingAdminMessage(this, settingsDao, adminMsgDialog);
        handler.removeCallbacks(ticker);
        handler.post(ticker);   // poll sekarang, lalu tiap POLL_MS
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(ticker);
        if (adminReceiverRegistered) {
            try { unregisterReceiver(adminMsgReceiver); } catch (Exception ignored) { }
            adminReceiverRegistered = false;
        }
        stopChime();
    }

    // ===================================================================== sinkron

    /**
     * Satu putaran: kirim kotak keluar → ambil rencana. @param manual true = tarik-segarkan
     * pengguna → kegagalan DILAPORKAN; false = otomatis → ditelan (toast tiap 15 dtk di depot
     * tanpa sinyal itu siksaan).
     */
    private void requestSync(boolean manual) {
        if (!cfg.isEnrolled()) {
            swipe.setRefreshing(false);
            showEmpty("Perangkat belum terhubung ke server.");
            return;
        }
        if (staffUuid.isEmpty()) {
            // Baris staf baru sampai lewat sync ±60 dtk: cari lagi sebelum menyerah.
            try {
                String u = new UserDao(DatabaseHelper.getInstance(this)).getSyncUuidById(me.getId());
                if (u != null) staffUuid = u;
            } catch (Exception ignored) {
            }
        }
        if (staffUuid.isEmpty()) {
            swipe.setRefreshing(false);
            blockedMessage = "Akun kamu belum tersinkron dari server. Tunggu sebentar lalu tarik layar ke bawah.";
            render(false);
            return;
        }
        if (!syncBusy.compareAndSet(false, true)) {
            syncAgain = true;   // ketukan baru datang saat putaran jalan → ulangi begitu selesai
            return;
        }
        if (manual) swipe.setRefreshing(true);
        final String revSnap = lastRev;
        final String staff = staffUuid;

        new Thread(() -> {
            PengisianSync.Result res = null;
            try {
                res = PengisianSync.run(new SyncApi(cfg), store, staff, revSnap);
                if (res.planJson != null) store.saveCache(res.planJson, System.currentTimeMillis());
            } catch (Throwable ignored) {
            } finally {
                syncBusy.set(false);
            }
            final PengisianSync.Result out = res;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                onSyncResult(out, manual);
                if (syncAgain) {
                    syncAgain = false;
                    requestSync(false);
                }
            });
        }).start();
    }

    private void onSyncResult(PengisianSync.Result res, boolean manual) {
        swipe.setRefreshing(false);
        reloadPending();
        if (res == null) {
            offline = true;
            render(false);
            return;
        }
        for (String msg : res.rejections) {
            Toast.makeText(this, "Catatan ditolak server: " + msg, Toast.LENGTH_LONG).show();
        }
        if (res.expired > 0) {
            Toast.makeText(this, res.expired + " catatan tertahan lebih dari 24 jam dibuang (tak pernah terkirim).",
                    Toast.LENGTH_LONG).show();
        }
        if (res.blockedMessage != null) {
            blockedMessage = res.blockedMessage;
            render(false);
            return;
        }
        blockedMessage = null;
        offline = res.offline;

        boolean fresh = false;
        if (res.planJson != null) {
            try {
                serverPlan = FillPlan.parse(res.planJson);
                lastRev = serverPlan.rev;
                fresh = true;
            } catch (IllegalArgumentException ignored) {
                offline = true;
            }
        } else if (res.unchanged) {
            fresh = true;
        }
        if (fresh) planAtMs = System.currentTimeMillis();
        if (offline && manual) {
            Toast.makeText(this, "Tidak ada koneksi — menampilkan data terakhir", Toast.LENGTH_SHORT).show();
        }
        render(fresh);
        maybePromptCount();
    }

    // ===================================================================== tampilan

    private void showEmpty(String msg) {
        tvEmpty.setText(msg);
        tvEmpty.setVisibility(View.VISIBLE);
    }

    /** Rencana yang ditampilkan = rencana server + ketukan yang belum terkirim. */
    private FillPlan viewPlan() {
        return FillOverlay.apply(serverPlan, pendingOps);
    }

    /**
     * @param fresh true bila render ini berasal dari hasil server yang baru tiba (hanya itu yang
     *              boleh membunyikan tanda "kebutuhan naik"; render karena ketukan/ubah tampilan tidak).
     */
    private void render(boolean fresh) {
        if (isFinishing() || isDestroyed()) return;
        SimpleDateFormat hms = new SimpleDateFormat("HH:mm:ss", Locale.US);

        // --- header + banner
        if (planAtMs > 0) {
            tvUpdated.setText((offline ? "data lama dari " : "diperbarui ") + hms.format(new Date(planAtMs)));
        } else {
            tvUpdated.setText("memuat…");
        }
        if (foreignPending > 0) {
            tvForeignNote.setText("ℹ " + foreignPending + " catatan staf lain tertahan di HP ini "
                    + "(tak ikut angka ini; dibuang otomatis setelah 24 jam)");
            tvForeignNote.setVisibility(View.VISIBLE);
        } else {
            tvForeignNote.setVisibility(View.GONE);
        }
        if (pendingOps.isEmpty()) {
            tvPendingOut.setVisibility(View.GONE);
        } else {
            tvPendingOut.setText("⏳ " + pendingOps.size() + " belum terkirim");
            tvPendingOut.setVisibility(View.VISIBLE);
        }
        if (blockedMessage != null) {
            tvBanner.setText("⛔ " + blockedMessage);
            tvBanner.setVisibility(View.VISIBLE);
        } else if (offline) {
            tvBanner.setText("Offline – data lama"
                    + (planAtMs > 0 ? " (" + hms.format(new Date(planAtMs)) + ")" : "")
                    + ". Ketukanmu tetap tersimpan dan terkirim saat sinyal kembali.");
            tvBanner.setVisibility(View.VISIBLE);
        } else {
            tvBanner.setVisibility(View.GONE);
        }

        FillPlan plan = viewPlan();
        productsBox.removeAllViews();
        othersBox.removeAllViews();

        if (plan == null) {
            heroBox.setVisibility(View.GONE);
            tvUnmapped.setVisibility(View.GONE);
            tvOthersToggle.setVisibility(View.GONE);
            othersBox.setVisibility(View.GONE);
            showEmpty(blockedMessage != null ? "Tidak bisa memuat rencana isi galon."
                    : "Belum ada data. Tarik layar ke bawah untuk memuat.");
            return;
        }
        tvEmpty.setVisibility(View.GONE);

        // --- kartu ringkasan
        heroBox.setVisibility(View.VISIBLE);
        int heroColor = FillPlan.STATUS_NEED.equals(plan.status) ? COLOR_NEED
                : FillPlan.STATUS_MET.equals(plan.status) ? COLOR_MET : COLOR_IDLE;
        heroBox.setBackground(rounded(heroColor, 16));
        tvHeroTitle.setText(FillText.heroTitle(plan));
        tvHeroValue.setText(FillText.heroValue(plan));
        tvHeroSub.setText(FillText.heroSub(plan));

        String warn = FillText.unmappedWarning(plan);
        if (warn != null) {
            tvUnmapped.setText(warn);
            tvUnmapped.setVisibility(View.VISIBLE);
        } else {
            tvUnmapped.setVisibility(View.GONE);
        }

        // --- kartu produk: aktif di atas, sisanya dilipat di "Produk lain"
        List<FillPlan.Product> others = new ArrayList<>();
        for (FillPlan.Product p : plan.products) {
            if (FillText.isActive(p)) productsBox.addView(buildCard(productsBox, p));
            else others.add(p);
        }
        if (others.isEmpty()) {
            tvOthersToggle.setVisibility(View.GONE);
            othersBox.setVisibility(View.GONE);
        } else {
            tvOthersToggle.setText("Produk lain (" + others.size() + ")  "
                    + (othersExpanded ? "▲" : "▼"));
            tvOthersToggle.setVisibility(View.VISIBLE);
            if (othersExpanded) {
                for (FillPlan.Product p : others) othersBox.addView(buildCard(othersBox, p));
            }
            othersBox.setVisibility(othersExpanded ? View.VISIBLE : View.GONE);
        }
        if (plan.products.isEmpty()) showEmpty("Belum ada produk galon di cabang ini.");

        // --- tanda saat kebutuhan NAIK (overlay sudah memuat ketukan sendiri, jadi ketukan sendiri
        //     yang hanya menurunkan angka — atau batal miliknya — tak membunyikan apa pun)
        int now = plan.totals.toFill;
        if (seenFresh) {
            if (fresh && FillOverlay.toFillIncreased(prevToFill, now)) alertIncrease();
            prevToFill = now;
        } else if (fresh) {
            seenFresh = true;
            prevToFill = now;
        }
    }

    private View buildCard(ViewGroup parent, FillPlan.Product p) {
        View v = LayoutInflater.from(this).inflate(R.layout.item_pengisian_product, parent, false);
        v.findViewById(R.id.vChip).setBackgroundColor(parseColor(p.color, COLOR_NEUTRAL));
        ((TextView) v.findViewById(R.id.tvName)).setText(p.name);
        ((TextView) v.findViewById(R.id.tvDetail)).setText(FillText.detail(p));

        boolean need = p.toFill > 0;
        boolean met = !need && p.target > 0;
        int accent = need ? COLOR_NEED : met ? COLOR_MET : COLOR_NEUTRAL;

        TextView head = v.findViewById(R.id.tvHeadline);
        head.setText(FillText.headline(p));
        head.setTextColor(need ? Color.parseColor("#BF360C") : met ? COLOR_MET : Color.parseColor("#546E7A"));

        TextView badge = v.findViewById(R.id.tvBadge);
        if (need || met) {
            badge.setText(need ? "PERLU ISI" : "CUKUP");
            badge.setBackground(rounded(accent, 8));
            badge.setVisibility(View.VISIBLE);
        } else {
            badge.setVisibility(View.GONE);
        }

        ProgressBar pb = v.findViewById(R.id.pb);
        pb.setProgress(FillText.progressPercent(p));
        pb.setProgressTintList(android.content.res.ColorStateList.valueOf(accent));
        pb.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#CFD8DC")));
        pb.setVisibility(p.target > 0 ? View.VISIBLE : View.GONE);

        TextView filled = v.findViewById(R.id.tvFilledToday);
        if (p.filledToday > 0) {
            filled.setText("Sudah diisi hari ini: " + p.filledToday);
            filled.setVisibility(View.VISIBLE);
        } else {
            filled.setVisibility(View.GONE);
        }

        v.findViewById(R.id.btnPlus1).setOnClickListener(x -> logFill(p, 1));
        v.findViewById(R.id.btnPlus5).setOnClickListener(x -> logFill(p, 5));
        v.findViewById(R.id.btnPlus10).setOnClickListener(x -> logFill(p, 10));
        v.findViewById(R.id.btnOther).setOnClickListener(x -> showOtherQtyDialog(p));
        return v;
    }

    // ===================================================================== aksi

    /** Identitas staf HARUS ada sebelum mencatat: ketukan tanpa pemilik bisa terkirim atas nama orang lain. */
    private boolean requireStaffIdentity() {
        if (!staffUuid.isEmpty()) return true;
        Toast.makeText(this, "Akun kamu belum tersinkron dari server — tunggu sebentar lalu coba lagi.",
                Toast.LENGTH_LONG).show();
        return false;
    }

    /** Masukkan operasi (kotak keluar DULU, tak hilang walau sinyal putus), tampilkan, lalu kirim. */
    private void enqueueAndSync(FillOutbox.Op op) {
        store.enqueue(op);
        reloadPending();
        render(false);
        requestSync(false);
    }

    /** Catat isi: ke kotak keluar DULU (tak hilang walau sinyal putus), tampilkan, lalu kirim. */
    private void logFill(FillPlan.Product p, int qty) {
        if (qty < 1 || qty > MAX_FILL_QTY) {
            Toast.makeText(this, "Jumlah isi 1 sampai " + MAX_FILL_QTY, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!requireStaffIdentity()) return;
        // Waktu ketukan dicatat di sini (jam dinding + elapsedRealtime); yang dikirim ke server adalah
        // umurnya saat kirim (age_seconds) — server mencap dengan jamnya sendiri.
        enqueueAndSync(FillOutbox.Op.fill(staffUuid, p.uuid, qty,
                System.currentTimeMillis(), SystemClock.elapsedRealtime()));
    }

    private void showOtherQtyDialog(FillPlan.Product p) {
        final EditText et = numberField("Jumlah galon", null);
        int pad = dp(20);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(pad, dp(8), pad, 0);
        box.addView(et, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("Isi " + p.name)
                .setMessage("Berapa galon yang baru selesai diisi? (1–" + MAX_FILL_QTY + ")")
                .setView(box)
                .setPositiveButton("Catat", null)
                .setNegativeButton("Batal", null)
                .create();
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(b -> {
            int qty = parseInt(et.getText().toString(), -1);
            if (qty < 1 || qty > MAX_FILL_QTY) {
                et.setError("1 sampai " + MAX_FILL_QTY);
                return;
            }
            d.dismiss();
            logFill(p, qty);
        }));
        d.show();
    }

    /** Sekali per sesi layar, bila server minta hitung stok hari ini. */
    private void maybePromptCount() {
        FillPlan plan = viewPlan();
        if (countPrompted || plan == null || !plan.needsCount) return;
        if (isFinishing() || isDestroyed()) return;
        countPrompted = true;
        new AlertDialog.Builder(this)
                .setTitle("Hitung stok awal dulu")
                .setMessage("Hitung galon siap yang ada di rak sekarang supaya angka \"ISI ... LAGI\" akurat.")
                .setPositiveButton("Hitung Sekarang", (d, w) -> showCountDialog())
                .setNegativeButton("Nanti", null)
                .show();
    }

    private static final int COLOR_ESTIMATE = Color.parseColor("#78909C");
    private static final int COLOR_COUNTED = Color.parseColor("#212121");

    /**
     * Dialog "Hitung Stok Rak": satu kolom angka per produk. Kolom terisi PERKIRAAN sistem (abu-abu,
     * miring) sekadar memudahkan; yang dikirim HANYA kolom yang pekerja ketik sendiri — perkiraan yang
     * tak disentuh bukan hasil hitung fisik, dan mengirimnya sebagai hitungan membuat jangkar palsu
     * (stok awal terkunci 0, atau angka basi menimpa penjualan yang terjadi selagi dialog terbuka).
     */
    private void showCountDialog() {
        FillPlan plan = viewPlan();
        if (plan == null || plan.products.isEmpty()) {
            Toast.makeText(this, "Data produk belum dimuat", Toast.LENGTH_SHORT).show();
            return;
        }
        final List<FillPlan.Product> rows = new ArrayList<>(
                plan.products.subList(0, Math.min(plan.products.size(), MAX_COUNT_ROWS)));
        final List<EditText> fields = new ArrayList<>();
        final boolean[] touched = new boolean[rows.size()];

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        list.setPadding(pad, dp(4), pad, dp(4));
        for (int i = 0; i < rows.size(); i++) {
            final int idx = i;
            FillPlan.Product p = rows.get(i);
            View row = LayoutInflater.from(this).inflate(R.layout.item_pengisian_count_row, list, false);
            ((TextView) row.findViewById(R.id.tvCountName)).setText(p.name);
            final EditText et = row.findViewById(R.id.etCountQty);
            et.setText(String.valueOf(p.ready));
            et.setTextColor(COLOR_ESTIMATE);
            et.setTypeface(et.getTypeface(), Typeface.ITALIC);
            et.setSelectAllOnFocus(true);
            // Pasang SETELAH setText supaya isian awal tak dihitung sebagai ketikan pekerja.
            et.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) { }
                @Override public void onTextChanged(CharSequence s, int st, int b, int c) { }
                @Override
                public void afterTextChanged(Editable e) {
                    if (touched[idx]) return;
                    touched[idx] = true;
                    et.setTextColor(COLOR_COUNTED);
                    et.setTypeface(Typeface.create(et.getTypeface(), Typeface.NORMAL), Typeface.BOLD);
                }
            });
            fields.add(et);
            list.addView(row);
        }
        ScrollView sv = new ScrollView(this);
        sv.addView(list);

        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("Hitung Stok Rak")
                .setMessage("Isi jumlah FISIK galon siap di rak untuk tiap produk yang kamu hitung. "
                        + "Angka abu-abu = perkiraan sistem dan TIDAK dikirim kecuali kamu mengetiknya. "
                        + "Kosongkan yang tak dihitung.")
                .setView(sv)
                .setPositiveButton("Simpan", null)
                .setNegativeButton("Batal", null)
                .create();
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(b -> {
            List<FillOutbox.Count> counts = new ArrayList<>();
            boolean ok = true;
            for (int i = 0; i < rows.size(); i++) {
                if (!touched[i]) continue;   // perkiraan sistem yang tak disentuh → bukan hasil hitung
                String s = fields.get(i).getText().toString().trim();
                if (s.isEmpty()) continue;
                int q = parseInt(s, -1);
                if (q < 0 || q > MAX_COUNT_QTY) {
                    fields.get(i).setError("0 sampai " + MAX_COUNT_QTY);
                    ok = false;
                    continue;
                }
                counts.add(new FillOutbox.Count(rows.get(i).uuid, q));
            }
            if (!ok) return;
            if (counts.isEmpty()) {
                Toast.makeText(this, "Ketik jumlah fisik minimal satu produk", Toast.LENGTH_SHORT).show();
                return;
            }
            if (!requireStaffIdentity()) return;
            d.dismiss();
            enqueueAndSync(FillOutbox.Op.count(staffUuid, counts,
                    System.currentTimeMillis(), SystemClock.elapsedRealtime()));
            Toast.makeText(this, "Stok rak dicatat (" + counts.size() + " produk)", Toast.LENGTH_SHORT).show();
        }));
        d.show();
    }

    /** Riwayat hari ini + ketukan yang belum terkirim; "Batal" pada yang masih dalam jendela batal DAN milikmu. */
    private void showHistoryDialog() {
        FillPlan plan = viewPlan();
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        list.setPadding(pad, dp(4), pad, dp(4));

        final AlertDialog[] holder = new AlertDialog[1];
        boolean any = false;

        // Belum terkirim (tanpa Batal: belum ada barisnya di server; begitu terkirim muncul di bawah).
        for (FillOutbox.Op op : pendingOps) {
            if (FillOutbox.TYPE_VOID.equals(op.type)) continue;
            View row = LayoutInflater.from(this).inflate(R.layout.item_pengisian_log, list, false);
            String label;
            if (FillOutbox.TYPE_FILL.equals(op.type)) {
                FillPlan.Product p = plan != null ? plan.product(op.productUuid) : null;
                label = "Isi · " + (p != null ? p.name : "?") + " · +" + op.qty;
            } else {
                label = "Hitung stok · " + op.counts.size() + " produk";
            }
            ((TextView) row.findViewById(R.id.tvLogLabel)).setText(label);
            ((TextView) row.findViewById(R.id.tvLogMeta)).setText("⏳ menunggu terkirim");
            row.findViewById(R.id.btnLogVoid).setVisibility(View.GONE);
            list.addView(row);
            any = true;
        }
        if (plan != null) {
            // Batal hanya di baris milik sendiri (atau admin) — server menolak selainnya (not_owner).
            // Sesi hitung stok digabung jadi satu baris dan dibatalkan lewat batch_uuid-nya.
            boolean admin = me != null && me.isAdmin();
            for (FillText.HistoryRow hr : FillText.historyRows(plan.recent, staffUuid, admin)) {
                View row = LayoutInflater.from(this).inflate(R.layout.item_pengisian_log, list, false);
                ((TextView) row.findViewById(R.id.tvLogLabel)).setText(hr.label);
                ((TextView) row.findViewById(R.id.tvLogMeta)).setText(hr.meta);
                View btn = row.findViewById(R.id.btnLogVoid);
                if (hr.voidTarget != null) {
                    btn.setOnClickListener(x -> confirmVoid(hr, holder[0]));
                } else {
                    btn.setVisibility(View.GONE);
                }
                list.addView(row);
                any = true;
            }
        }
        if (!any) {
            TextView tv = new TextView(this);
            tv.setText("Belum ada catatan hari ini.");
            tv.setTextColor(Color.parseColor("#455A64"));
            tv.setTextSize(15f);
            tv.setPadding(0, dp(12), 0, dp(12));
            list.addView(tv);
        }
        ScrollView sv = new ScrollView(this);
        sv.addView(list);
        holder[0] = new AlertDialog.Builder(this)
                .setTitle("Riwayat hari ini")
                .setView(sv)
                .setPositiveButton("Tutup", null)
                .create();
        holder[0].show();
    }

    private void confirmVoid(FillText.HistoryRow hr, AlertDialog parent) {
        new AlertDialog.Builder(this)
                .setTitle("Batalkan catatan ini?")
                .setMessage(hr.label + "\n\nBatal hanya bisa dalam 15 menit setelah dicatat.")
                .setPositiveButton("Ya, Batalkan", (d, w) -> {
                    if (!requireStaffIdentity()) return;
                    enqueueAndSync(FillOutbox.Op.voidOf(staffUuid, hr.voidTarget,
                            System.currentTimeMillis(), SystemClock.elapsedRealtime()));
                    if (parent != null) parent.dismiss();
                })
                .setNegativeButton("Tidak", null)
                .show();
    }

    /** Pulang: konfirmasi, lalu alur selfie+clock-out milik MainActivity (EXTRA_AUTO_CLOCKOUT). */
    private void confirmPulang() {
        StringBuilder msg = new StringBuilder("Kamu akan absen pulang dan keluar dari perangkat ini.");
        if (!pendingOps.isEmpty()) {
            msg.append("\n\n⚠ Masih ada ").append(pendingOps.size())
                    .append(" catatan belum terkirim (offline). Catatan tetap tersimpan dan terkirim ")
                    .append("saat KAMU login lagi di HP ini dengan sinyal (dibuang otomatis setelah 24 jam; ")
                    .append("staf lain yang login tak akan mengirimnya).");
            requestSync(false);   // coba kirim sekarang
        }
        new AlertDialog.Builder(this)
                .setTitle("Selesai — Pulang?")
                .setMessage(msg.toString())
                .setPositiveButton("Ya, Pulang", (d, w) -> launchClockOut())
                .setNegativeButton("Batal", null)
                .show();
    }

    private void launchClockOut() {
        Intent i = new Intent(this, MainActivity.class);
        i.putExtra(MainActivity.EXTRA_AUTO_CLOCKOUT, true);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(i);
        finish();
    }

    // ===================================================================== tanda bunyi

    /** Kebutuhan isi naik antara dua pembaruan: bunyi pendek (stream alarm) + getar. Hanya foreground. */
    private void alertIncrease() {
        try {
            Vibrator vib = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (vib != null && vib.hasVibrator()) {
                vib.vibrate(VibrationEffect.createWaveform(new long[]{0, 250, 120, 250}, -1));
            }
        } catch (Exception ignored) {
        }
        try {
            stopChime();
            Uri snd = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            if (snd == null) snd = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            Ringtone rt = RingtoneManager.getRingtone(getApplicationContext(), snd);
            if (rt == null) return;
            rt.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            chime = rt;
            rt.play();
        } catch (Exception ignored) {
            // suara hanya penanda — kartu yang berubah tetap terlihat
        }
    }

    private void stopChime() {
        try {
            if (chime != null && chime.isPlaying()) chime.stop();
        } catch (Exception ignored) {
        }
    }

    // ===================================================================== util

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private EditText numberField(String hint, String preset) {
        EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setHint(hint);
        et.setTextColor(Color.parseColor("#212121"));
        et.setHintTextColor(Color.parseColor("#78909C"));
        et.setTextSize(22f);
        et.setGravity(android.view.Gravity.CENTER);
        et.setMinHeight(dp(52));
        if (preset != null) et.setText(preset);
        return et;
    }

    private static int parseColor(String s, int fallback) {
        if (s == null || s.isEmpty()) return fallback;
        try {
            return Color.parseColor(s.startsWith("#") || Character.isLetter(s.charAt(0)) ? s : "#" + s);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private static int parseInt(String s, int fallback) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
