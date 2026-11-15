package com.crowja.damiupos;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;
import androidx.core.view.WindowCompat;
import androidx.core.widget.ImageViewCompat;

import com.crowja.damiupos.db.CustomerDao;
import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.GalonStockDao;
import com.crowja.damiupos.db.SettingsDao;
import com.crowja.damiupos.db.TransactionDao;
import com.crowja.damiupos.db.UserDao;
import com.crowja.damiupos.model.Transaction;
import com.crowja.damiupos.model.TransactionItem;
import com.crowja.damiupos.model.User;
import com.crowja.damiupos.sync.SyncApi;
import com.crowja.damiupos.sync.SyncScheduler;
import com.crowja.damiupos.sync.SyncSettings;
import com.crowja.damiupos.sync.WaInboxApi;
import com.crowja.damiupos.util.HomeTiles;
import com.crowja.damiupos.util.AiStatusMonitor;
import com.crowja.damiupos.util.ForegroundDuties;
import com.crowja.damiupos.util.GojekUi;
import com.crowja.damiupos.util.Ts;
import com.crowja.damiupos.wa.NotificationAlertHelper;
import com.crowja.damiupos.wa.OrderAlertService;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.switchmaterial.SwitchMaterial;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Beranda "Tampilan Baru (gaya Gojek)" khusus sesi admin (lihat {@link GojekUi}). Satu activity, empat tab
 * (Beranda · Pesanan · Chat · Akun) yang ditukar lewat visibilitas, tanpa fragment.
 *
 * <p><b>Pengganti beranda lama untuk admin.</b> Semua menu yang dulu tersedia bagi admin di MainActivity
 * tetap terjangkau (grid Beranda / lembar "Semua menu" / tab Akun) dengan aturan tampil yang sama, dan semua
 * kewajiban layar induk (ForegroundDuties, penerima sinkron & pesan admin, armada wajib, popup Lengkapi Data,
 * peringatan belum tersinkron, izin latar, status AI + sirine, alarm pesanan WA) ikut dijalankan di sini —
 * kehilangan satu saja = regresi diam-diam.</p>
 *
 * <p><b>Kinerja.</b> Agregat DB (hitungan, tren, daftar) dihitung di satu thread latar dengan penjaga urutan
 * ({@link #snapSeq}); hanya hasil permintaan TERAKHIR yang digambar. Tiap bagian digambar dalam try/catch
 * sendiri supaya satu kueri gagal tak menjatuhkan seluruh beranda.</p>
 */
public class AdminHomeActivity extends AppCompatActivity {

    private static final String TAG = "AdminHome";

    private static final int TAB_BERANDA = 0;
    private static final int TAB_PESANAN = 1;
    private static final int TAB_CHAT = 2;
    private static final int TAB_AKUN = 3;
    private static final String STATE_TAB = "gj_tab";

    /** Kartu penjualan + badge Chat WA disegarkan berkala selama beranda tampil (sama dengan beranda lama). */
    private static final long TICK_MS = 60_000L;
    private static final long BOOT_OK_DELAY_MS = 2500L;
    private static final int REQ_BG_LOCATION = 9314;
    private static final int REQ_LOCATION = 9312;
    private static final int RECENT_LIMIT = 8;
    private static final int LIST_CAP = 30;
    private static final int TREND_WINDOW = 30;          // hari (periode "per bulan")
    private static final String NL = "\n";

    private static final int SEV_DANGER = 0;
    private static final int SEV_WARNING = 1;
    private static final int SEV_INFO = 2;

    private static final String G_OPS = "Operasional";
    private static final String G_CUST = "Pelanggan & promo";
    private static final String G_REPORT = "Laporan";
    private static final String G_WA = "WhatsApp";
    private static final String G_SET = "Pengaturan";
    private static final String G_INFO = "Info";
    private static final String[] GROUP_ORDER = {G_OPS, G_CUST, G_REPORT, G_WA, G_SET, G_INFO};

    private static final int[] NAV_IDS = {R.id.gjNavBeranda, R.id.gjNavPesanan, R.id.gjNavChat, R.id.gjNavAkun};
    private static final int[] NAV_ICONS = {R.drawable.gj_ic_home, R.drawable.gj_ic_receipt,
            R.drawable.gj_ic_chat, R.drawable.gj_ic_person};
    private static final String[] NAV_LABELS = {"Beranda", "Pesanan", "Chat", "Akun"};
    private static final int[] TAB_IDS = {R.id.gjTabBeranda, R.id.gjTabPesanan, R.id.gjTabChat, R.id.gjTabAkun};

    private SettingsDao settingsDao;
    private CustomerDao customerDao;
    private TransactionDao transactionDao;
    private GalonStockDao galonStockDao;
    private UserDao userDao;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private ExecutorService io;
    private final AtomicInteger snapSeq = new AtomicInteger();
    private final AtomicInteger salesSeq = new AtomicInteger();
    private Snapshot snap = new Snapshot();
    private MenuState ms = new MenuState();
    private final List<MenuEntry> registry = new ArrayList<>();
    private AiStatusMonitor ai;
    private final NumberFormat nf = NumberFormat.getInstance(new Locale("id", "ID"));

    private int currentTab = TAB_BERANDA;
    private int pesananPage = 0;
    private boolean pesananDirty = true;
    private boolean chatDirty = true;
    private boolean receiversOn;
    /** Sudah minta izin lokasi "sepanjang waktu" pada SESI proses ini? Sengaja non-persisten (sama dgn beranda lama). */
    private boolean bgLocationAsked;
    private boolean deliverySwitchBusy;
    private boolean waLoading;
    private int waUnread = -1;
    private JSONArray waAccounts;
    private boolean waFailed;
    private long issueRefreshAt;
    private AlertDialog adminMsgDialog;

    private final View[] tabViews = new View[4];
    private final View[] navTabs = new View[4];
    private final List<String> gridIds = new ArrayList<>();
    private final List<TileHolder> gridTiles = new ArrayList<>();

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            refreshSales();
            refreshWaUnread();
            handler.postDelayed(this, TICK_MS);
        }
    };

    /** Sinkron membawa data baru → segarkan semua + cek ulang gerbang (Guided Delivery dsb) + popup Lengkapi Data. */
    private final BroadcastReceiver syncedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (isFinishing() || isDestroyed()) return;
            if (redirectIfNotForMe()) return;
            refreshAll();
            safe("incomplete", AdminHomeActivity.this::showPendingIncompleteWarnings);
        }
    };

    /** Pesan admin dari dashboard ("Kirim Pesan ke Perangkat") tiba saat layar tampil → popup. */
    private final BroadcastReceiver adminMsgReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            safe("adminMsg", AdminHomeActivity.this::showPendingAdminMessage);
        }
    };

    // ================================================================== lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Tandai "boot berjalan" PALING AWAL: bila proses mati sebelum boot sukses, MainActivity
        // mematikan tampilan baru otomatis (GojekUi.recoverFromCrashedBoot).
        GojekUi.markBootStart(this);
        super.onCreate(savedInstanceState);
        settingsDao = new SettingsDao(DatabaseHelper.getInstance(this));
        if (redirectIfNotForMe()) return;

        setContentView(R.layout.activity_admin_home);
        DatabaseHelper db = DatabaseHelper.getInstance(this);
        customerDao = new CustomerDao(db);
        transactionDao = new TransactionDao(db);
        galonStockDao = new GalonStockDao(db);
        userDao = new UserDao(db);
        io = Executors.newSingleThreadExecutor();
        ai = new AiStatusMonitor(this, this::paintAi);

        buildRegistry();
        safe("menuState", () -> ms = readMenuState());
        safe("bindTabs", this::bindTabs);
        safe("bindNav", this::bindNav);
        safe("bindBeranda", this::bindBeranda);
        safe("bindPesanan", this::bindPesanan);
        safe("header", this::renderHeader);
        safe("grid", this::renderGrid);
        safe("ai", () -> paintAi(AiStatusMonitor.STATE_UNKNOWN, ""));
        setupBack();

        int tab = savedInstanceState != null ? savedInstanceState.getInt(STATE_TAB, TAB_BERANDA) : TAB_BERANDA;
        selectTab(tab);

        // Banner iklan (otomatis GONE saat Pro) — sekali di onCreate, sama dengan beranda lama.
        safe("ads", () -> {
            ViewGroup ad = findViewById(R.id.gjhAdContainer);
            if (ad != null) com.crowja.damiupos.ads.AdManager.getInstance(this).attachBanner(this, ad);
        });

        // Izin notifikasi + channel pengingat, sinkron berkala, cek versi baru (sama dengan beranda lama).
        safe("fgLaunch", () -> ForegroundDuties.onLaunch(this));
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_TAB, currentTab);
    }

    @Override
    protected void onResume() {
        super.onResume();
        staleOrderAlertStopped = false;
        if (isFinishing()) return;
        // Login / peran / Guided Delivery / preferensi tampilan bisa berubah saat layar ini di background.
        if (redirectIfNotForMe()) return;

        // Gerbang versi dinonaktifkan, sinkron sekali, service "online" tetap hidup.
        safe("fgResume", () -> ForegroundDuties.onResume(this));
        final Context app = getApplicationContext();
        // Hangatkan cache logo depot + suara sirine komplain (disinkron dari web).
        new Thread(() -> {
            try {
                com.crowja.damiupos.util.DepotLogo.ensureDownloaded(app);
            } catch (Throwable t) {
                Log.w(TAG, "logo", t);
            }
        }, "gj-logo").start();
        new Thread(() -> {
            try {
                com.crowja.damiupos.util.ComplaintSirenSound.ensureDownloaded(app);
            } catch (Throwable t) {
                Log.w(TAG, "siren", t);
            }
        }, "gj-siren").start();
        // Pengecualian baterai + lokasi "sepanjang waktu" supaya sync & lapor koordinat tetap jalan.
        safe("reliability", this::ensureBackgroundReliability);
        registerReceivers();
        refreshAll();
        safe("adminMsg", this::showPendingAdminMessage);
        safe("armada", () -> ArmadaPicker.askIfNeeded(this));
        safe("incomplete", this::showPendingIncompleteWarnings);
        warnIfUnsyncedTransactions();
        handler.removeCallbacks(ticker);
        handler.postDelayed(ticker, TICK_MS);
        // Boot sukses: sengaja TAK dibatalkan di onPause (pakai app context) — admin yang langsung membuka
        // menu lain dalam 2,5 dtk tak boleh dianggap crash.
        handler.postDelayed(() -> GojekUi.markBootOk(app), BOOT_OK_DELAY_MS);
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceivers();
        handler.removeCallbacks(ticker);
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Sirine "AI mati" berhenti saat layar tak tampil; lanjut lagi saat kembali (AiStatusMonitor.refresh).
        if (ai != null) ai.onStop();
        // Kedip kartu pesanan WA berhenti (suara tetap di OrderAlertService).
        NotificationAlertHelper.stopAlert();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(ticker);
        if (io != null) io.shutdown();
        if (ai != null) ai.release();
        try {
            if (adminMsgDialog != null && adminMsgDialog.isShowing()) adminMsgDialog.dismiss();
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }

    private void registerReceivers() {
        if (receiversOn) return;
        try {
            IntentFilter f = new IntentFilter(com.crowja.damiupos.sync.SyncEngine.ACTION_SYNCED);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(syncedReceiver, f, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(syncedReceiver, f);
            }
            ForegroundDuties.registerAdminMessageReceiver(this, adminMsgReceiver);
            receiversOn = true;
        } catch (Throwable t) {
            Log.e(TAG, "registerReceivers", t);
        }
    }

    private void unregisterReceivers() {
        if (!receiversOn) return;
        receiversOn = false;
        try {
            unregisterReceiver(syncedReceiver);
        } catch (Exception ignored) {
        }
        try {
            unregisterReceiver(adminMsgReceiver);
        } catch (Exception ignored) {
        }
    }

    /**
     * Gerbang: layar ini HANYA untuk sesi admin dengan tampilan baru aktif, bukan perangkat Guided Delivery,
     * bukan peran beranda-Pengisian, dan (multi-user) sudah ada yang login. Selain itu lempar ke MainActivity
     * (yang mengarahkan ulang dengan benar). @return true bila dialihkan (pemanggil wajib langsung return).
     */
    private boolean redirectIfNotForMe() {
        boolean leave;
        try {
            leave = !GojekUi.isEnabled(this);
            if (!leave && new SyncSettings(settingsDao).isGuidedDeliveryDevice()) leave = true;
            if (!leave) {
                User cur = UserDao.currentUser(this);
                if (cur != null && cur.usesPengisianHome()) leave = true;
            }
            if (!leave && settingsDao.isMultiUserEnabled() && settingsDao.getCurrentUserId() <= 0) leave = true;
        } catch (Throwable t) {
            Log.e(TAG, "gate", t);
            leave = true;
        }
        if (!leave) return false;
        startActivity(new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        GojekUi.markBootOk(this);
        finish();
        return true;
    }

    private void setupBack() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (currentTab != TAB_BERANDA) {
                    selectTab(TAB_BERANDA);
                    return;
                }
                setEnabled(false);
                getOnBackPressedDispatcher().onBackPressed();
                setEnabled(true);
            }
        });
    }

    // ================================================================== refresh

    /** Segarkan semua bagian: penjualan (server), agregat DB (latar), WA belum dibaca, status AI, eskalasi komplain. */
    private void refreshAll() {
        safe("meRefresh", this::maybeRefreshMe);
        refreshSales();
        loadSnapshot();
        refreshWaUnread();
        safe("ai", () -> {
            if (ai != null) ai.refresh();
        });
        // Eskalasi AI komplain dari server (maks 1x/menit), lalu gambar ulang hitungannya.
        long now = SystemClock.elapsedRealtime();
        if (issueRefreshAt == 0 || now - issueRefreshAt > 60_000L) {
            issueRefreshAt = now;
            safe("issues", () -> IssueReminder.refreshAsync(this, false, () -> handler.post(() -> {
                if (!isFinishing() && !isDestroyed()) loadSnapshot();
            })));
        }
    }

    private void loadSnapshot() {
        if (io == null || io.isShutdown()) return;
        final int seq = snapSeq.incrementAndGet();
        final Context app = getApplicationContext();
        try {
            io.execute(() -> {
                if (seq != snapSeq.get()) return;   // sudah ada permintaan yang lebih baru
                final Snapshot s;
                try {
                    s = computeSnapshot(app);
                } catch (Throwable t) {
                    Log.e(TAG, "snapshot", t);
                    return;
                }
                handler.post(() -> {
                    if (seq != snapSeq.get() || isFinishing() || isDestroyed()) return;
                    snap = s;
                    renderSnapshot();
                });
            });
        } catch (Throwable t) {
            Log.w(TAG, "loadSnapshot", t);
        }
    }

    private interface IntQ {
        int get() throws Exception;
    }

    private interface ListQ<T> {
        List<T> get() throws Exception;
    }

    private static int qi(String what, IntQ q) {
        try {
            return q.get();
        } catch (Throwable t) {
            Log.w(TAG, "query " + what, t);
            return 0;
        }
    }

    private static <T> List<T> ql(String what, ListQ<T> q) {
        try {
            List<T> l = q.get();
            return l != null ? l : new ArrayList<>();
        } catch (Throwable t) {
            Log.w(TAG, "query " + what, t);
            return new ArrayList<>();
        }
    }

    /** Thread latar: semua hitungan & daftar untuk keempat tab. */
    private Snapshot computeSnapshot(Context app) {
        Snapshot s = new Snapshot();
        s.loaded = true;
        s.menu = readMenuState();
        final SyncSettings cfg = new SyncSettings(settingsDao);
        s.queue = qi("queue", () -> transactionDao.countDeliveryQueue());
        s.complaints = qi("complaints", () -> transactionDao.countActiveComplaints());
        s.followUp = qi("followup", () -> customerDao.countFollowUpCandidates(settingsDao.getFollowupDays()));
        s.visitUrgent = qi("visit", () -> customerDao.countVisitUrgent());
        s.stockAlert = qi("stockAlert", () -> settingsDao.getStockAlert());
        try {
            // Tersedia "resmi" (server-authoritative bila sinkron) — sama dengan layar Stok Galon & web.
            s.stok = galonStockDao.getStokTersediaResmi(cfg);
            s.stockKnown = true;
        } catch (Throwable t) {
            Log.w(TAG, "query stok", t);
        }
        s.galonBeredar = qi("beredar", () -> customerDao.getTotalGalonBeredar());
        s.customers = qi("customers", () -> customerDao.getTotalCustomers());
        s.unsynced = qi("unsynced", () -> transactionDao.countUnsynced(15));

        // Tren: rata-rata/hari 30 hari terakhir vs 30 hari sebelumnya (matematika refreshTrends lama).
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            Calendar cal = Calendar.getInstance();
            String today = sdf.format(cal.getTime());
            cal.add(Calendar.DAY_OF_YEAR, -(TREND_WINDOW - 1));
            String curStart = sdf.format(cal.getTime());   // [today-29 .. today] = 30 hari
            cal.add(Calendar.DAY_OF_YEAR, -1);
            String prevEnd = sdf.format(cal.getTime());    // today-30
            cal.add(Calendar.DAY_OF_YEAR, -(TREND_WINDOW - 1));
            String prevStart = sdf.format(cal.getTime());  // [today-59 .. today-30] = 30 hari
            // [count, galon_jual, galon_kembali, pendapatan]
            s.cur = transactionDao.getSummaryByDateRange(curStart, today);
            s.prev = transactionDao.getSummaryByDateRange(prevStart, prevEnd);
            s.curNew = customerDao.getCountCreatedBetween(curStart, today);
            s.prevNew = customerDao.getCountCreatedBetween(prevStart, prevEnd);
            s.trendOk = s.cur != null && s.prev != null && s.cur.length >= 4 && s.prev.length >= 4;
        } catch (Throwable t) {
            Log.w(TAG, "query trends", t);
        }
        final String todayF = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        List<Transaction> todayList = ql("today", () -> transactionDao.getByDateRange(todayF, todayF));
        Collections.reverse(todayList);   // terbaru di atas
        s.today = todayList;
        s.recent = ql("recent", () -> transactionDao.getRecent(RECENT_LIMIT));
        s.queueList = ql("queueList", () -> transactionDao.getDeliveryQueue());
        if (s.menu.tracksAttendance) {
            try {
                ShiftReporter.Shift sh = ShiftReporter.computeShift(DatabaseHelper.getInstance(app), s.menu.uid);
                String info = "Kerja " + ShiftReporter.formatDuration(sh.workMillis);
                if (sh.breakCount > 0) info += " • Istirahat " + sh.breakCount + "x";
                try {
                    String[] period = AttendanceRecap.currentPeriod(settingsDao.getPayrollCutoffDay());
                    AttendanceRecap.PeriodSummary ps = AttendanceRecap.computePeriodSummary(
                            DatabaseHelper.getInstance(app), s.menu.uid, period[0], period[1],
                            settingsDao.getDailyNormalHours());
                    info += NL + MainActivity.fmtHours(ps.totalHours) + " / " + MainActivity.fmtHours(ps.requiredHours);
                } catch (Throwable ignored) {
                }
                s.shiftInfo = info;
                s.shiftOpen = sh.clockIn != null;
            } catch (Throwable t) {
                Log.w(TAG, "query shift", t);
                s.shiftInfo = "Sedang bekerja";
            }
        }

        // WA Perkenalan: hanya perangkat petugas; hitungan disaring ke wilayah tugasnya.
        try {
            if (s.menu.introWa) {
                s.introCount = IntroWaDuty.filterPending(customerDao.getPromoIntroPending(), cfg).size();
            }
        } catch (Throwable t) {
            Log.w(TAG, "query intro", t);
        }
        try {
            s.proTemp = settingsDao.isProTempActive() && !settingsDao.isProSubscriber();
            s.proTempUntil = settingsDao.getProTempUntil();
        } catch (Throwable t) {
            Log.w(TAG, "query pro", t);
        }
        // Pengingat Komplain & Masalah belum dibaca (popup/sirene) — dulu dipanggil tiap render badge komplain.
        try {
            IssueReminder.check(app);
        } catch (Throwable t) {
            Log.w(TAG, "issue check", t);
        }
        return s;
    }

    private void renderSnapshot() {
        if (snap.menu != null) ms = snap.menu;
        safe("header", this::renderHeader);
        safe("shiftDuties", this::runShiftDuties);
        safe("grid", this::renderGrid);
        safe("trend", this::renderSalesTrend);
        safe("alerts", this::renderAlerts);
        safe("summary", this::renderSummary);
        safe("recent", this::renderRecent);
        safe("navBadges", this::renderNavBadges);
        pesananDirty = true;
        chatDirty = true;
        if (currentTab == TAB_PESANAN) safe("pesanan", this::renderPesanan);
        else if (currentTab == TAB_CHAT) safe("chat", this::renderChat);
        else if (currentTab == TAB_AKUN) safe("akun", this::renderAkun);
    }

    private void safe(String what, Runnable r) {
        try {
            r.run();
        } catch (Throwable t) {
            Log.e(TAG, "section " + what, t);
        }
    }

    /**
     * Kartu Penjualan hari ini — jalur admin beranda lama: total SE-DEPOT dari server (endpoint yang sama
     * dengan Pencapaian Penjualan) bila terhubung; selain itu angka lokal HP ini dengan keterangan jujur.
     */
    private void refreshSales() {
        if (settingsDao == null || transactionDao == null) return;
        final int seq = salesSeq.incrementAndGet();
        final SyncSettings cfg = new SyncSettings(settingsDao);
        final boolean enrolled = cfg.isEnrolled();
        // Admin/Marketing/SPV: se-depot dari server; peran lain: transaksi ATAS NAMANYA sendiri (lokal).
        final boolean branch = ms.branchSales;
        final String staff = branch ? null : (ms.uid > 0 ? settingsDao.getCurrentUserName() : null);
        final String staffName = staff != null && !staff.isEmpty() ? staff : null;
        new Thread(() -> {
            JSONObject totals = null;
            if (branch && enrolled) {
                try {
                    JSONObject res = new SyncApi(cfg).salesAchievement("today", null, null, null);
                    totals = res != null ? res.optJSONObject("totals") : null;
                } catch (Throwable t) {
                    Log.w(TAG, "salesAchievement: " + t);
                }
            }
            double rev = 0;
            int galon = 0;
            int trx = 0;
            String scope;
            if (totals != null) {
                rev = totals.optDouble("revenue", 0);
                galon = totals.optInt("galon", 0);
                trx = totals.optInt("trx", 0);
                scope = "se-depot";
            } else {
                try {
                    rev = transactionDao.getPendapatanHariIni(staffName);
                    galon = transactionDao.getGalonTerjualHariIni(staffName);
                    trx = transactionDao.getTransaksiHariIni(staffName);
                } catch (Throwable t) {
                    Log.w(TAG, "sales local", t);
                }
                if (!branch) {
                    scope = staffName != null ? "milik Anda" : "HP ini";
                } else {
                    // Server tak terjangkau → angka LOKAL, keterangan jujur bahwa itu bukan angka se-depot.
                    scope = enrolled ? "HP ini · offline" : "HP ini";
                }
            }
            final double revF = rev;
            final int galonF = galon;
            final int trxF = trx;
            final String scopeF = scope;
            handler.post(() -> {
                if (seq != salesSeq.get() || isFinishing() || isDestroyed()) return;
                safe("sales", () -> renderSales(revF, galonF, trxF, scopeF));
            });
        }, "gj-sales").start();
    }

    /** Total pesan WA belum dibaca (semua akun) + daftar akun untuk tab Chat. Gagal = badge tak berubah. */
    private void refreshWaUnread() {
        if (!ms.canWaChat || waLoading) return;
        waLoading = true;
        final Context app = getApplicationContext();
        new Thread(() -> {
            int total = -1;
            JSONArray arr = null;
            try {
                WaInboxApi.Res r = new WaInboxApi(app).accounts();
                if (r.ok() && r.body != null) {
                    arr = r.body.optJSONArray("accounts");
                    total = 0;
                    for (int i = 0; arr != null && i < arr.length(); i++) {
                        JSONObject a = arr.optJSONObject(i);
                        if (a != null) total += a.optInt("unread");
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "wa accounts: " + t);
            }
            final int totalF = total;
            final JSONArray arrF = arr;
            handler.post(() -> {
                waLoading = false;
                if (isFinishing() || isDestroyed()) return;
                if (totalF >= 0) {
                    waUnread = totalF;
                    waAccounts = arrF != null ? arrF : new JSONArray();
                    waFailed = false;
                } else {
                    waFailed = true;
                }
                safe("gridBadges", this::updateGridTiles);
                safe("navBadges", this::renderNavBadges);
                if (currentTab == TAB_CHAT) safe("chat", this::renderChat);
                else chatDirty = true;
            });
        }, "gj-wa-unread").start();
    }

    // ================================================================== menu registry

    private interface Vis {
        boolean ok(MenuState m);
    }

    private interface Count {
        int of(Snapshot s);
    }

    private static final class MenuEntry {
        final String id;
        final String label;
        final String shortLabel;
        final String emoji;
        final int tintRes;
        final String group;
        final Vis vis;
        final Runnable action;
        Count badge;
        boolean blink;
        boolean stockTag;

        MenuEntry(String id, String label, String shortLabel, String emoji, int tintRes, String group,
                  Vis vis, Runnable action) {
            this.id = id;
            this.label = label;
            this.shortLabel = shortLabel;
            this.emoji = emoji;
            this.tintRes = tintRes;
            this.group = group;
            this.vis = vis;
            this.action = action;
        }

        MenuEntry badge(Count c) {
            badge = c;
            return this;
        }

        MenuEntry blink() {
            blink = true;
            return this;
        }

        MenuEntry stockTag() {
            stockTag = true;
            return this;
        }

        String gridLabel() {
            return shortLabel != null ? shortLabel : label;
        }
    }

    private MenuEntry add(String id, String label, String shortLabel, String emoji, int tintRes, String group,
                          Vis vis, Runnable action) {
        MenuEntry e = new MenuEntry(id, label, shortLabel, emoji, tintRes, group, vis, action);
        registry.add(e);
        return e;
    }

    /**
     * Registri tunggal semua menu admin (grid Beranda, lembar "Semua menu", pencarian). Aturan tampil = aturan
     * beranda lama untuk admin: role-based lewat predikat User, "*" = hanya perangkat terhubung (enrolled),
     * WA Perkenalan = centang perangkat petugas, Promo = UserDao.canPromosi.
     */
    private void buildRegistry() {
        registry.clear();
        // ---- Operasional
        add("jual", "Jual Air Minum", "Jual", "🛒", R.color.gj_green_tint, G_OPS,
                m -> m.user == null || !m.user.isViewer(), () -> openTrx(Transaction.TYPE_JUAL));
        add("kembali", "Botol Galon Kembali", "Kembali", "↩️", R.color.gj_blue_tint, G_OPS,
                m -> m.user == null || !m.user.isViewer(), () -> openTrx(Transaction.TYPE_KEMBALI));
        add("antrean", "Antrean Delivery", "Antrean", "🚚", R.color.gj_green_tint, G_OPS,
                AdminHomeActivity::queueVisible, () -> open(DeliveryQueueActivity.class)).badge(s -> s.queue);
        add("terjadwal", "Pesanan Terjadwal", "Terjadwal", "🗓️", R.color.gj_green_tint, G_OPS,
                m -> m.admin && m.enrolled, () -> open(ScheduledOrderActivity.class));
        add("keliling", "Mode Keliling", null, "🛵", R.color.gj_green_tint, G_OPS,
                m -> m.user == null || !m.user.isViewer(), () -> open(IderModeActivity.class));
        add("pengisian", "Pengisian Live", "Pengisian", "🚰", R.color.gj_blue_tint, G_OPS,
                m -> m.uid > 0, this::openPengisianLive);
        add("rekor", "Rekor Antar", null, "🏁", R.color.gj_orange_tint, G_OPS,
                m -> m.user != null && m.user.canViewDeliveryRecord(), () -> open(DeliveryRecordActivity.class));
        add("setoran", "Setoran", null, "💵", R.color.gj_blue_tint, G_OPS,
                m -> true, () -> DepositDialog.show(this));
        add("pengeluaran", "Pengeluaran", null, "💸", R.color.gj_red_tint, G_OPS,
                m -> true, () -> open(ExpenseListActivity.class));
        // ---- Pelanggan & promo
        add("pelanggan", "Pelanggan", null, "👥", R.color.gj_blue_tint, G_CUST,
                m -> true, () -> open(CustomerListActivity.class));
        add("followup", "Follow Up", null, "🔔", R.color.gj_orange_tint, G_CUST,
                m -> true, () -> open(FollowUpActivity.class)).badge(s -> s.followUp);
        add("kunjungan", "Kunjungan Urgent", "Kunjungan", "🧭", R.color.gj_purple_tint, G_CUST,
                m -> m.admin || (m.user != null && m.user.isMarketing()), () -> open(VisitListActivity.class))
                .badge(s -> s.visitUrgent);
        add("komplain", "Komplain & Masalah", "Komplain", "⚠️", R.color.gj_red_tint, G_CUST,
                m -> true, () -> open(ComplaintListActivity.class)).badge(s -> s.complaints).blink();
        add("promo", "Promo Galon", null, "🎁", R.color.gj_orange_tint, G_CUST,
                m -> m.canPromosi, () -> startSafely(new Intent(this, CustomerFormActivity.class)
                        .putExtra(CustomerFormActivity.EXTRA_PROMOSI_FLOW, true)));
        add("promocust", "Cust. Promo", null, "📈", R.color.gj_orange_tint, G_CUST,
                m -> m.canPromosi, () -> open(PromoCustomersActivity.class));
        add("introwa", "WA Perkenalan", null, "👋", R.color.gj_green_tint, G_CUST,
                m -> m.introWa, () -> open(IntroWaActivity.class)).badge(s -> s.introCount);
        add("reseller", "Reseller", null, "🤝", R.color.gj_purple_tint, G_CUST,
                m -> m.admin, () -> open(ResellerListActivity.class));
        // ---- Laporan
        add("laporan", "Report & Grafik", "Laporan", "📊", R.color.gj_blue_tint, G_REPORT,
                m -> m.admin, () -> open(ReportActivity.class));
        add("target", "Target Sales", null, "🏆", R.color.gj_orange_tint, G_REPORT,
                m -> m.user != null && m.user.canViewSalesAchievement(), () -> open(SalesAchievementActivity.class));
        add("export", "Export", null, "📤", R.color.gj_blue_tint, G_REPORT,
                m -> m.admin, () -> open(ExportActivity.class));
        add("growth", "Pertumbuhan Galon Beredar", "Pertumbuhan", "📉", R.color.gj_green_tint, G_REPORT,
                m -> m.admin && m.enrolled, () -> open(GalonGrowthActivity.class));
        add("stok", "Stok Galon", null, "💧", R.color.gj_blue_tint, G_REPORT,
                m -> m.admin, () -> open(GalonStockActivity.class)).stockTag();
        add("transaksi", "Transaksi", null, "🧾", R.color.gj_blue_tint, G_REPORT,
                m -> true, () -> open(TransactionListActivity.class));
        // ---- WhatsApp
        add("chatwa", "Chat WA", null, "💬", R.color.gj_green_tint, G_WA,
                m -> m.canWaChat, () -> open(WaInboxActivity.class)).badge(s -> Math.max(0, waUnread));
        add("whatsapp", "WhatsApp", null, "📣", R.color.gj_green_tint, G_WA,
                m -> m.admin && m.enrolled, () -> open(WhatsAppMenuActivity.class));
        // ---- Pengaturan
        add("pengaturan", "Pengaturan", null, "🛠️", R.color.gj_grey_tint, G_SET,
                m -> m.admin, () -> open(SettingsActivity.class));
        add("perangkat", "Pengaturan Perangkat", "Perangkat", "⚙️", R.color.gj_grey_tint, G_SET,
                m -> m.admin && m.enrolled, () -> open(DeviceAdminActivity.class));
        add("dashweb", "Dashboard Web", null, "🖥️", R.color.gj_blue_tint, G_SET,
                m -> m.admin && m.enrolled, () -> open(AdminWebActivity.class));
        add("sinkron", "Sinkronisasi Online", "Sinkronisasi", "🔄", R.color.gj_blue_tint, G_SET,
                m -> true, () -> open(SyncSettingsActivity.class));
        add("armada", "Armada", null, "🛻", R.color.gj_green_tint, G_SET,
                m -> m.enrolled && m.deliveryDevice, () -> ArmadaPicker.change(this));
        // ---- Info
        add("tentang", "Tentang", null, "ℹ️", R.color.gj_grey_tint, G_INFO,
                m -> true, this::showAboutDialog);
        add("changelog", "Riwayat versi", null, "📜", R.color.gj_grey_tint, G_INFO,
                m -> true, () -> open(ChangelogActivity.class));
    }

    private MenuEntry entry(String id) {
        for (MenuEntry e : registry) if (e.id.equals(id)) return e;
        return null;
    }

    private boolean visible(MenuEntry e) {
        try {
            return e.vis.ok(ms);
        } catch (Throwable t) {
            Log.w(TAG, "vis " + e.id, t);
            return false;
        }
    }

    private int badgeOf(MenuEntry e) {
        if (e.badge == null || snap == null) return 0;
        try {
            return e.badge.of(snap);
        } catch (Throwable t) {
            return 0;
        }
    }

    private void runEntry(MenuEntry e) {
        try {
            e.action.run();
        } catch (Throwable t) {
            Log.e(TAG, "menu " + e.id, t);
            Toast.makeText(this, "Menu belum bisa dibuka.", Toast.LENGTH_SHORT).show();
        }
    }

    private void open(Class<?> c) {
        startSafely(new Intent(this, c));
    }

    private void startSafely(Intent i) {
        try {
            startActivity(i);
        } catch (Throwable t) {
            Log.e(TAG, "start " + i, t);
            Toast.makeText(this, "Layar belum bisa dibuka.", Toast.LENGTH_SHORT).show();
        }
    }

    private void openTrx(String type) {
        startSafely(new Intent(this, TransactionActivity.class).putExtra("type", type));
    }

    private void openTodayTransactions() {
        startSafely(new Intent(this, TransactionListActivity.class)
                .putExtra(TransactionListActivity.EXTRA_DATE_FILTER, TransactionListActivity.DATE_FILTER_TODAY));
    }

    private void openReceipt(Transaction t) {
        startSafely(new Intent(this, ReceiptActivity.class)
                .putExtra(ReceiptActivity.EXTRA_TRANSACTION_ID, t.getId()));
    }

    // ================================================================== state models

    /** Fakta peran/perangkat untuk aturan tampil menu (dibaca ulang tiap snapshot). */
    private static final class MenuState {
        long uid;
        User user;
        boolean admin;
        boolean viewer;
        boolean marketing;
        /** Admin/Marketing/SPV: kartu penjualan = total se-depot dari server (refreshSalesCards lama). */
        boolean branchSales;
        /** Staf/SPV (bukan admin/viewer/marketing) — Istirahat/Pulang, shift, GPS (refreshOperatorBar lama). */
        boolean tracksAttendance;
        /** Marketing/Pengisian: tanpa alarm pesanan WA / ikon inbox. */
        boolean alertSilenced;
        String roleLabel = "";
        boolean canWaChat;
        boolean canPromosi;
        boolean enrolled;
        boolean deliveryDevice;
        boolean introWa;
        String name = "";
        String branch = "";
        String depot = "";

        String place() {
            if (branch != null && !branch.isEmpty()) return branch;
            if (depot != null && !depot.isEmpty()) return depot;
            return "DAMIU POS";
        }
    }

    private MenuState readMenuState() {
        MenuState m = new MenuState();
        try {
            SyncSettings cfg = new SyncSettings(settingsDao);
            m.enrolled = cfg.isEnrolled();
            m.deliveryDevice = cfg.isDeliveryDevice();
            m.introWa = cfg.isIntroWaDevice();
            m.branch = cfg.getBranchName();
        } catch (Throwable t) {
            Log.w(TAG, "menuState sync", t);
        }
        try {
            m.uid = settingsDao.isMultiUserEnabled() ? settingsDao.getCurrentUserId() : 0;
            m.user = m.uid > 0 && userDao != null ? userDao.getById(m.uid) : null;
            m.admin = m.user != null && m.user.isAdmin();
            m.viewer = m.user != null && m.user.isViewer();
            m.marketing = m.user != null && m.user.isMarketing();
            m.branchSales = m.user != null && (m.admin || m.marketing || m.user.isSpv());
            m.tracksAttendance = m.uid > 0 && m.user != null && !m.admin && !m.viewer && !m.marketing;
            m.alertSilenced = m.user != null && !m.user.receivesOperationalAlerts();
            m.roleLabel = roleLabel(m.user);
            m.canWaChat = m.user != null && m.user.canUseWaChat();
            m.canPromosi = m.uid > 0 && userDao != null && userDao.canPromosi(m.uid);
            m.name = settingsDao.getCurrentUserName();
            if ((m.name == null || m.name.isEmpty()) && m.user != null) m.name = m.user.getName();
            if (m.name == null) m.name = "";
            m.depot = settingsDao.getDepotName();
        } catch (Throwable t) {
            Log.w(TAG, "menuState user", t);
        }
        return m;
    }

    private static String roleLabel(User u) {
        if (u == null) return "";
        if (u.isAdmin()) return "Admin";
        if (u.isSpv()) return "SPV";
        if (u.isViewer()) return "Viewer";
        if (u.isMarketing()) return "Marketing";
        if (u.isPengisian()) return "Pengisian";
        return "Staf";
    }

    /** Antrean Delivery tampil? Marketing hanya bila HP-nya dicentang Delivery (refreshOperatorBar lama). */
    private static boolean queueVisible(MenuState m) {
        return !(m.marketing && !m.deliveryDevice);
    }

    /** Hasil satu putaran agregat DB (thread latar). */
    private static final class Snapshot {
        /** false = placeholder awal (belum ada kueri yang selesai). */
        boolean loaded;
        MenuState menu;
        int queue;
        int complaints;
        int followUp;
        int visitUrgent;
        int stok;
        int stockAlert;
        boolean stockKnown;
        int galonBeredar;
        int customers;
        int unsynced;
        int introCount;
        boolean proTemp;
        long proTempUntil;
        boolean trendOk;
        double[] cur;
        double[] prev;
        int curNew;
        int prevNew;
        List<Transaction> recent = new ArrayList<>();
        List<Transaction> today = new ArrayList<>();
        List<Transaction> queueList = new ArrayList<>();
        /** Peran berabsen: "Kerja Xj Ym • Istirahat Nx\nperiode/ideal"; null = bukan peran berabsen. */
        String shiftInfo;
        boolean shiftOpen;
    }

    private static final class TileHolder {
        MenuEntry e;
        View root;
        View box;
        TextView badge;
        TextView tag;
    }

    private static final class AlertItem {
        final int sev;
        final String emoji;
        final String title;
        final String body;
        final String cta;
        final Runnable action;
        final boolean blink;

        AlertItem(int sev, String emoji, String title, String body, String cta, Runnable action, boolean blink) {
            this.sev = sev;
            this.emoji = emoji;
            this.title = title;
            this.body = body;
            this.cta = cta;
            this.action = action;
            this.blink = blink;
        }
    }

    // ================================================================== tabs & bottom nav

    private void bindTabs() {
        for (int i = 0; i < 4; i++) tabViews[i] = findViewById(TAB_IDS[i]);
    }

    private void bindNav() {
        for (int i = 0; i < 4; i++) {
            View tab = findViewById(NAV_IDS[i]);
            navTabs[i] = tab;
            if (tab == null) continue;
            ImageView icon = tab.findViewById(R.id.gjhNavIcon);
            TextView label = tab.findViewById(R.id.gjhNavLabel);
            if (icon != null) icon.setImageResource(NAV_ICONS[i]);
            if (label != null) label.setText(NAV_LABELS[i]);
            tab.setContentDescription(NAV_LABELS[i]);
            final int idx = i;
            tab.setOnClickListener(v -> selectTab(idx));
        }
    }

    private void selectTab(int idx) {
        if (idx < 0 || idx > 3) idx = TAB_BERANDA;
        currentTab = idx;
        for (int i = 0; i < 4; i++) {
            if (tabViews[i] != null) tabViews[i].setVisibility(i == idx ? View.VISIBLE : View.GONE);
            View tab = navTabs[i];
            if (tab == null) continue;
            boolean active = i == idx;
            tab.setSelected(active);
            View ind = tab.findViewById(R.id.gjhNavIndicator);
            if (ind != null) ind.setVisibility(active ? View.VISIBLE : View.INVISIBLE);
            ImageView icon = tab.findViewById(R.id.gjhNavIcon);
            if (icon != null) {
                ImageViewCompat.setImageTintList(icon, ColorStateList.valueOf(
                        col(active ? R.color.gj_primary : R.color.gj_text_secondary)));
            }
            TextView label = tab.findViewById(R.id.gjhNavLabel);
            if (label != null) {
                label.setTextColor(col(active ? R.color.gj_text_primary : R.color.gj_text_secondary));
                label.setTypeface(Typeface.DEFAULT, active ? Typeface.BOLD : Typeface.NORMAL);
            }
        }
        applyStatusBar(idx == TAB_BERANDA);
        if (idx == TAB_PESANAN) {
            if (pesananDirty) safe("pesanan", this::renderPesanan);
        } else if (idx == TAB_CHAT) {
            if (chatDirty) safe("chat", this::renderChat);
            refreshWaUnread();
        } else if (idx == TAB_AKUN) {
            safe("akun", this::renderAkun);
        }
    }

    /** Beranda: status bar hijau (menyatu dengan pita header, ikon terang); tab lain: putih + ikon gelap. */
    private void applyStatusBar(boolean green) {
        try {
            Window w = getWindow();
            w.setStatusBarColor(col(green ? R.color.gj_primary : R.color.gj_surface));
            WindowCompat.getInsetsController(w, w.getDecorView()).setAppearanceLightStatusBars(!green);
        } catch (Throwable t) {
            Log.w(TAG, "statusBar", t);
        }
    }

    private void renderNavBadges() {
        setNavBadge(TAB_PESANAN, queueVisible(ms) ? snap.queue : 0);
        setNavBadge(TAB_CHAT, ms.canWaChat ? Math.max(0, waUnread) : 0);
    }

    private void setNavBadge(int idx, int n) {
        View tab = navTabs[idx];
        if (tab == null) return;
        TextView b = tab.findViewById(R.id.gjhNavBadge);
        if (b == null) return;
        b.setVisibility(n > 0 ? View.VISIBLE : View.GONE);
        b.setText(fmtCount(n));
        tab.setContentDescription(NAV_LABELS[idx] + (n > 0 ? ", " + n + " baru" : ""));
    }

    // ================================================================== Beranda

    private void bindBeranda() {
        View avatar = findViewById(R.id.gjhAvatarWrap);
        if (avatar != null) avatar.setOnClickListener(v -> selectTab(TAB_AKUN));
        View search = findViewById(R.id.gjhSearch);
        if (search != null) search.setOnClickListener(v -> safe("searchSheet", this::showSearchSheet));
        ImageView searchIcon = findViewById(R.id.gjhSearchIcon);
        if (searchIcon != null) {
            ImageViewCompat.setImageTintList(searchIcon, ColorStateList.valueOf(col(R.color.gj_text_secondary)));
        }
        View aiPill = findViewById(R.id.gjhAiPill);
        if (aiPill != null) {
            // Hitam 15% di atas #00880D → teks putih kecil tetap ~6:1 (putih 20% hanya ~3.4:1).
            aiPill.setBackground(rounded(ColorUtils.setAlphaComponent(col(R.color.gj_tag_black), 38), 14));
            aiPill.setOnClickListener(v -> {
                if (ai != null) ai.onIndicatorClick();
            });
        }
        View sumAi = findViewById(R.id.gjhSumAi);
        if (sumAi != null) sumAi.setOnClickListener(v -> {
            if (ai != null) ai.onIndicatorClick();
        });
        ImageView sumAiIcon = findViewById(R.id.gjhSumAiIcon);
        if (sumAiIcon != null) {
            ImageViewCompat.setImageTintList(sumAiIcon, ColorStateList.valueOf(col(R.color.gj_text_secondary)));
        }

        TextView salesIcon = findViewById(R.id.gjhSalesIcon);
        if (salesIcon != null) salesIcon.setBackground(rounded(col(R.color.gj_blue_tint), 10));
        TextView amount = findViewById(R.id.gjhSalesAmount);
        if (amount != null) amount.setText("Rp …");
        TextView sub = findViewById(R.id.gjhSalesSub);
        if (sub != null) sub.setText("Memuat…");
        clickable(R.id.gjhSalesMain, v -> openTodayTransactions());
        clickable(R.id.gjhActJual, v -> openTrx(Transaction.TYPE_JUAL));
        clickable(R.id.gjhActKembali, v -> openTrx(Transaction.TYPE_KEMBALI));
        clickable(R.id.gjhActLainnya, v -> safe("salesMore", this::showSalesMoreSheet));
        clickable(R.id.gjhSeeAll, v -> open(TransactionListActivity.class));
        clickable(R.id.gjhSumGalon, v -> open(GalonStockActivity.class));
        clickable(R.id.gjhSumCust, v -> open(CustomerListActivity.class));
        clickable(R.id.gjhSumIssue, v -> open(ComplaintListActivity.class));
        clickable(R.id.gjhOpIstirahat, v -> doIstirahat());
        clickable(R.id.gjhOpPulang, v -> doPulang());
    }

    private void clickable(int id, View.OnClickListener l) {
        View v = findViewById(id);
        if (v != null) v.setOnClickListener(l);
    }

    private void renderHeader() {
        TextView hello = findViewById(R.id.gjhHello);
        String name = ms.name != null ? ms.name.trim() : "";
        if (hello != null) hello.setText(name.isEmpty() ? "Halo 👋" : "Halo, " + name + " 👋");
        TextView sub = findViewById(R.id.gjhHelloSub);
        if (sub != null) sub.setText(ms.place() + (ms.roleLabel.isEmpty() ? "" : " · " + ms.roleLabel));
        String initial = name.isEmpty() ? "A" : name.substring(0, 1).toUpperCase(Locale.ROOT);
        TextView av = findViewById(R.id.gjhAvatar);
        if (av != null) av.setText(initial);
        TextView av2 = findViewById(R.id.gjhAkunAvatar);
        if (av2 != null) av2.setText(initial);
        safe("roleViews", this::renderRoleViews);
    }

    /** Bagian Beranda yang tampil per peran (refreshOperatorBar lama) + pita operator berabsen. */
    private void renderRoleViews() {
        int jk = ms.viewer ? View.GONE : View.VISIBLE;   // Viewer tak boleh membuat transaksi
        View j = findViewById(R.id.gjhActJual);
        if (j != null) j.setVisibility(jk);
        View k = findViewById(R.id.gjhActKembali);
        if (k != null) k.setVisibility(jk);
        // Galon beredar / stok: khusus admin (kartu GLN lama).
        View g = findViewById(R.id.gjhSumGalon);
        if (g != null) g.setVisibility(ms.admin ? View.VISIBLE : View.GONE);
        View strip = findViewById(R.id.gjhOperatorStrip);
        if (strip != null) strip.setVisibility(ms.tracksAttendance ? View.VISIBLE : View.GONE);
        TextView n = findViewById(R.id.gjhOpName);
        if (n != null) n.setText(ms.name);
        TextView sh = findViewById(R.id.gjhOpShift);
        if (sh != null && snap.shiftInfo != null) sh.setText(snap.shiftInfo);
    }

    private void renderSales(double revenue, int galon, int trx, String scope) {
        TextView amount = findViewById(R.id.gjhSalesAmount);
        if (amount != null) amount.setText("Rp " + nf.format(revenue));
        TextView sub = findViewById(R.id.gjhSalesSub);
        if (sub != null) sub.setText(trx + " trx · " + galon + " galon · " + scope);
        View main = findViewById(R.id.gjhSalesMain);
        if (main != null) {
            main.setContentDescription("Penjualan hari ini Rp " + nf.format(revenue) + ", " + trx
                    + " transaksi, " + galon + " galon, " + scope + ". Ketuk untuk daftar transaksi hari ini.");
        }
    }

    private void renderSalesTrend() {
        TextView tv = findViewById(R.id.gjhSalesTrend);
        if (tv == null) return;
        if (!snap.trendOk) {
            tv.setVisibility(View.GONE);
            return;
        }
        double w = TREND_WINDOW;
        double curAvg = snap.cur[3] / w;
        double prevAvg = snap.prev[3] / w;
        applyTrendChip(tv, trendDir(curAvg, prevAvg), "Rp " + compactRupiah(curAvg));
    }

    private void renderSummary() {
        TextView galon = findViewById(R.id.gjhSumGalonValue);
        if (galon != null) galon.setText(nf.format(snap.galonBeredar));
        TextView galonSub = findViewById(R.id.gjhSumGalonSub);
        if (galonSub != null) galonSub.setText(snap.stockKnown ? "Stok " + snap.stok : "se-depot");
        TextView cust = findViewById(R.id.gjhSumCustValue);
        if (cust != null) cust.setText(nf.format(snap.customers));
        double w = TREND_WINDOW;
        if (snap.trendOk) {
            // Galon beredar — penambahan bersih / hari (galon JUAL - galon KEMBALI); pelanggan baru / hari.
            setTrendNumber(findViewById(R.id.gjhSumGalonTrend),
                    (snap.cur[1] - snap.cur[2]) / w, (snap.prev[1] - snap.prev[2]) / w, true);
            setTrendNumber(findViewById(R.id.gjhSumCustTrend), snap.curNew / w, snap.prevNew / w, true);
        }
        TextView issue = findViewById(R.id.gjhSumIssueValue);
        if (issue != null) issue.setText(fmtCount(snap.complaints));
        updateIssueBlink();
    }

    /** Kartu Masalah berkedip bila ada komplain aktif ATAU AI mati (sama dengan kartu Masalah lama). */
    private void updateIssueBlink() {
        View card = findViewById(R.id.gjhSumIssue);
        if (card == null) return;
        boolean on = snap.complaints > 0 || (ai != null && ai.isDead());
        if (on) {
            if (card.getAnimation() == null) startBlink(card);
        } else {
            card.clearAnimation();
        }
    }

    /** Dipanggil AiStatusMonitor: label + dot di pil header & kartu Masalah, kedip, kartu "AI MATI". */
    private void paintAi(int state, String text) {
        String label = AiStatusMonitor.label(state);
        TextView l1 = findViewById(R.id.gjhAiLabel);
        if (l1 != null) l1.setText(label);
        View d1 = findViewById(R.id.gjhAiDot);
        if (d1 != null) {
            int c = state == AiStatusMonitor.STATE_ALIVE ? col(R.color.gj_green_tint)
                    : state == AiStatusMonitor.STATE_DEAD ? col(R.color.gj_badge_red) : col(R.color.gj_border_strong);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(c);
            g.setStroke(Math.max(1, px(1.5f)), col(R.color.gj_surface));
            d1.setBackground(g);
        }
        View pill = findViewById(R.id.gjhAiPill);
        if (pill != null) pill.setContentDescription(text == null || text.isEmpty() ? label : text);
        TextView l2 = findViewById(R.id.gjhSumAiLabel);
        if (l2 != null) l2.setText(label);
        View d2 = findViewById(R.id.gjhSumAiDot);
        if (d2 != null) {
            int c = state == AiStatusMonitor.STATE_ALIVE ? col(R.color.gj_primary)
                    : state == AiStatusMonitor.STATE_DEAD ? col(R.color.gj_badge_red) : col(R.color.gj_text_tertiary);
            d2.setBackground(oval(c));
        }
        View sumAi = findViewById(R.id.gjhSumAi);
        if (sumAi != null) sumAi.setContentDescription(text == null || text.isEmpty() ? label : text);
        updateIssueBlink();
        safe("alerts", this::renderAlerts);
    }

    // ---------------------------------------------------------------- service grid

    private void renderGrid() {
        LinearLayout grid = findViewById(R.id.gjhGrid);
        if (grid == null) return;
        List<MenuEntry> pick = new ArrayList<>();
        for (String id : HomeTiles.pick(savedHomeTiles(), this::visibleId)) {
            MenuEntry e = entry(id);
            if (e != null) pick.add(e);
        }
        List<String> ids = new ArrayList<>();
        for (MenuEntry e : pick) ids.add(e.id);
        if (!ids.equals(gridIds) || grid.getChildCount() == 0) {
            grid.removeAllViews();
            gridTiles.clear();
            gridIds.clear();
            gridIds.addAll(ids);
            LinearLayout row = null;
            int cells = ((pick.size() + 1 + 3) / 4) * 4;
            for (int i = 0; i < cells; i++) {
                if (i % 4 == 0) {
                    row = newRow();
                    grid.addView(row);
                }
                if (i < pick.size()) {
                    gridTiles.add(inflateTile(row, pick.get(i), true, null));
                } else if (i == pick.size()) {
                    addLainnyaTile(row);
                } else {
                    addSpacer(row);
                }
            }
        }
        updateGridTiles();
    }

    private boolean visibleId(String id) {
        MenuEntry e = entry(id);
        return e != null && visible(e);
    }

    /** Ubin Beranda pilihan user ini di perangkat ini (null = bawaan). */
    private List<String> savedHomeTiles() {
        try {
            return HomeTiles.parse(settingsDao.get(HomeTiles.key(ms != null ? ms.uid : 0), ""));
        } catch (Throwable t) {
            Log.w(TAG, "home tiles", t);
            return null;
        }
    }

    /** Lembar "Atur menu": pilih menu yang tampil di Beranda + urutannya (per user per perangkat). */
    private void showEditTilesSheet() {
        final BottomSheetDialog d = newSheet();
        View root = LayoutInflater.from(this).inflate(R.layout.gjh_sheet_list, null, false);
        TextView title = root.findViewById(R.id.gjhSheetTitle);
        if (title != null) title.setText("Atur menu Beranda");
        LinearLayout body = root.findViewById(R.id.gjhSheetBody);
        final List<MenuEntry> all = new ArrayList<>();
        for (String g : GROUP_ORDER) for (MenuEntry e : registry) if (g.equals(e.group) && visible(e)) all.add(e);
        final List<String> sel = new ArrayList<>(HomeTiles.pick(savedHomeTiles(), this::visibleId));
        final LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        final TextView hint = new TextView(this);
        hint.setPadding(px(16), 0, px(16), px(8));
        hint.setTextColor(col(R.color.gj_text_secondary));
        hint.setTextSize(13);
        body.addView(hint);
        body.addView(list);
        final Runnable[] render = new Runnable[1];
        render[0] = () -> {
            hint.setText("Centang menu yang tampil di Beranda (" + HomeTiles.MIN + "–" + HomeTiles.MAX
                    + "), atur urutan dengan ▲▼. Dipilih: " + sel.size() + ". \"Lainnya\" selalu di akhir.");
            list.removeAllViews();
            List<MenuEntry> ordered = new ArrayList<>();
            for (String id : sel) {
                MenuEntry e = entry(id);
                if (e != null) ordered.add(e);
            }
            for (MenuEntry e : all) if (!sel.contains(e.id)) ordered.add(e);
            for (MenuEntry e : ordered) {
                View v = LayoutInflater.from(this).inflate(R.layout.gjh_item_tile_edit, list, false);
                final int pos = sel.indexOf(e.id);
                CheckBox cb = v.findViewById(R.id.gjhEditCheck);
                TextView emoji = v.findViewById(R.id.gjhEditEmoji);
                TextView label = v.findViewById(R.id.gjhEditLabel);
                TextView up = v.findViewById(R.id.gjhEditUp);
                TextView down = v.findViewById(R.id.gjhEditDown);
                emoji.setText(e.emoji);
                emoji.setBackground(rounded(col(e.tintRes), 10));
                label.setText(e.label);
                cb.setChecked(pos >= 0);
                cb.setContentDescription("Tampilkan " + e.label + " di Beranda");
                cb.setOnClickListener(x -> {
                    if (sel.contains(e.id)) {
                        sel.remove(e.id);
                    } else if (sel.size() >= HomeTiles.MAX) {
                        Toast.makeText(this, "Maksimal " + HomeTiles.MAX + " menu di Beranda.",
                                Toast.LENGTH_SHORT).show();
                    } else {
                        sel.add(e.id);
                    }
                    render[0].run();
                });
                v.setOnClickListener(x -> cb.performClick());
                boolean canUp = pos > 0;
                boolean canDown = pos >= 0 && pos < sel.size() - 1;
                up.setVisibility(pos >= 0 ? View.VISIBLE : View.INVISIBLE);
                down.setVisibility(pos >= 0 ? View.VISIBLE : View.INVISIBLE);
                up.setEnabled(canUp);
                down.setEnabled(canDown);
                up.setAlpha(canUp ? 1f : 0.3f);
                down.setAlpha(canDown ? 1f : 0.3f);
                up.setContentDescription("Naikkan " + e.label);
                down.setContentDescription("Turunkan " + e.label);
                up.setOnClickListener(x -> {
                    if (pos > 0) {
                        Collections.swap(sel, pos, pos - 1);
                        render[0].run();
                    }
                });
                down.setOnClickListener(x -> {
                    if (pos >= 0 && pos < sel.size() - 1) {
                        Collections.swap(sel, pos, pos + 1);
                        render[0].run();
                    }
                });
                list.addView(v);
            }
        };
        render[0].run();
        View footer = LayoutInflater.from(this).inflate(R.layout.gjh_sheet_tiles_footer, body, false);
        footer.findViewById(R.id.gjhTilesReset).setOnClickListener(x -> {
            sel.clear();
            sel.addAll(HomeTiles.defaults(this::visibleId));
            render[0].run();
        });
        footer.findViewById(R.id.gjhTilesSave).setOnClickListener(x -> {
            if (sel.size() < HomeTiles.MIN) {
                Toast.makeText(this, "Pilih minimal " + HomeTiles.MIN + " menu.", Toast.LENGTH_SHORT).show();
                return;
            }
            List<String> known = new ArrayList<>();
            for (MenuEntry e : registry) known.add(e.id);
            List<String> clean = HomeTiles.sanitize(sel, known);
            try {
                long uid = ms != null ? ms.uid : 0;
                boolean isDefault = clean.equals(HomeTiles.defaults(this::visibleId));
                settingsDao.set(HomeTiles.key(uid), isDefault ? "" : HomeTiles.serialize(clean));
            } catch (Throwable t) {
                Log.w(TAG, "save home tiles", t);
                Toast.makeText(this, "Gagal menyimpan.", Toast.LENGTH_SHORT).show();
                return;
            }
            try {
                d.dismiss();
            } catch (Throwable ignored) {
            }
            safe("grid", this::renderGrid);
            Toast.makeText(this, "Menu Beranda disimpan.", Toast.LENGTH_SHORT).show();
        });
        body.addView(footer);
        d.setContentView(root);
        expandOnShow(d, true);
        d.show();
    }

    private void updateGridTiles() {
        for (TileHolder h : gridTiles) updateTile(h);
    }

    private LinearLayout newRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBaselineAligned(false);
        row.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private void addSpacer(LinearLayout row) {
        View sp = new View(this);
        row.addView(sp, new LinearLayout.LayoutParams(0, 1, 1f));
    }

    /** @param sheet lembar yang ditutup sebelum aksi dijalankan (null untuk grid Beranda). */
    private TileHolder inflateTile(LinearLayout row, MenuEntry e, boolean grid, BottomSheetDialog sheet) {
        View v = LayoutInflater.from(this).inflate(R.layout.gjh_item_tile, row, false);
        TileHolder h = new TileHolder();
        h.e = e;
        h.root = v;
        h.box = v.findViewById(R.id.gjhTileBox);
        h.badge = v.findViewById(R.id.gjhTileBadge);
        h.tag = v.findViewById(R.id.gjhTileTag);
        TextView emoji = v.findViewById(R.id.gjhTileEmoji);
        if (emoji != null) emoji.setText(e.emoji);
        TextView label = v.findViewById(R.id.gjhTileLabel);
        if (label != null) {
            label.setText(grid ? e.gridLabel() : e.label);
            label.setMaxLines(grid ? 1 : 2);
        }
        if (h.box != null) h.box.setBackground(rounded(col(e.tintRes), 16));
        v.setOnClickListener(x -> {
            if (sheet != null) {
                try {
                    sheet.dismiss();
                } catch (Throwable ignored) {
                }
            }
            runEntry(e);
        });
        if (grid) {
            v.setOnLongClickListener(x -> {
                safe("editTiles", this::showEditTilesSheet);
                return true;
            });
        }
        row.addView(v);
        updateTile(h);
        return h;
    }

    private void updateTile(TileHolder h) {
        int n = badgeOf(h.e);
        if (h.badge != null) {
            h.badge.setVisibility(n > 0 ? View.VISIBLE : View.GONE);
            h.badge.setText(fmtCount(n));
        }
        String cd = h.e.label + (n > 0 ? ", " + n : "");
        if (h.e.blink && h.box != null) {
            if (n > 0) {
                if (h.box.getAnimation() == null) startBlink(h.box);
            } else {
                h.box.clearAnimation();
            }
        }
        if (h.tag != null) {
            boolean low = h.e.stockTag && snap.stockKnown && snap.stok <= snap.stockAlert;
            if (low) {
                h.tag.setText("STOK " + snap.stok);
                h.tag.setBackground(rounded(col(R.color.gj_badge_red), 4));
                h.tag.setVisibility(View.VISIBLE);
                if (h.tag.getAnimation() == null) startBlink(h.tag);
                cd += ", stok rendah " + snap.stok;
            } else {
                h.tag.clearAnimation();
                h.tag.setVisibility(View.GONE);
            }
        }
        if (h.root != null) h.root.setContentDescription(cd);
    }

    private void addLainnyaTile(LinearLayout row) {
        View v = LayoutInflater.from(this).inflate(R.layout.gjh_item_tile, row, false);
        View box = v.findViewById(R.id.gjhTileBox);
        if (box != null) {
            ViewGroup.LayoutParams lp = box.getLayoutParams();
            lp.width = px(48);
            lp.height = px(48);
            box.setLayoutParams(lp);
            box.setBackground(oval(col(R.color.gj_grey_tint)));
        }
        TextView emoji = v.findViewById(R.id.gjhTileEmoji);
        if (emoji != null) emoji.setVisibility(View.GONE);
        ImageView img = v.findViewById(R.id.gjhTileImg);
        if (img != null) {
            img.setImageResource(R.drawable.gj_ic_apps);
            ImageViewCompat.setImageTintList(img, ColorStateList.valueOf(col(R.color.gj_grey_glyph)));
            img.setVisibility(View.VISIBLE);
        }
        TextView label = v.findViewById(R.id.gjhTileLabel);
        if (label != null) label.setText("Lainnya");
        v.setContentDescription("Lainnya — semua menu");
        v.setOnClickListener(x -> safe("allMenu", this::showAllMenuSheet));
        v.setOnLongClickListener(x -> {
            safe("editTiles", this::showEditTilesSheet);
            return true;
        });
        row.addView(v);
    }

    // ---------------------------------------------------------------- Perlu perhatian

    private List<AlertItem> buildAlerts() {
        List<AlertItem> l = new ArrayList<>();
        final Context app = getApplicationContext();
        if (ai != null && ai.isDead()) {
            l.add(new AlertItem(SEV_DANGER, "🤖", "AI MATI",
                    "AI provider mati — pantau WhatsApp manual sampai AI hidup lagi.", "Lihat",
                    () -> ai.showDownDialog(), false));
        }
        if (snap.complaints > 0) {
            l.add(new AlertItem(SEV_DANGER, "⚠️", "Komplain aktif " + fmtCount(snap.complaints),
                    "Ada komplain atau masalah pesanan yang belum selesai.", "Lihat",
                    () -> open(ComplaintListActivity.class), false));
        }
        if (ms.admin && snap.stockKnown && snap.stok <= snap.stockAlert) {
            l.add(new AlertItem(SEV_WARNING, "💧", "Stok galon rendah",
                    "Stok tersedia tinggal " + snap.stok + " (batas peringatan " + snap.stockAlert + ").",
                    "Cek stok", () -> open(GalonStockActivity.class), false));
        }
        if (snap.unsynced > 0) {
            l.add(new AlertItem(SEV_WARNING, "☁️", snap.unsynced + " transaksi belum tersinkron",
                    "Pastikan HP online; sistem akan mencoba mengirim ulang otomatis.", "Sinkronkan",
                    () -> {
                        SyncScheduler.syncNow(app);
                        Toast.makeText(this, "Sinkronisasi dimulai…", Toast.LENGTH_SHORT).show();
                    }, false));
        }
        if (snap.followUp > 0) {
            l.add(new AlertItem(SEV_INFO, "🔔", "Follow-up " + snap.followUp + " pelanggan",
                    "Pelanggan yang sudah waktunya dihubungi lagi.", "Buka",
                    () -> open(FollowUpActivity.class), false));
        }
        MenuEntry visit = entry("kunjungan");
        if (snap.visitUrgent > 0 && visit != null && visible(visit)) {
            l.add(new AlertItem(SEV_INFO, "🧭", "Kunjungan urgent " + snap.visitUrgent,
                    "Pelanggan yang perlu segera dikunjungi.", "Buka",
                    () -> open(VisitListActivity.class), false));
        }
        if (snap.proTemp) {
            long remMs = Math.max(0L, snap.proTempUntil - System.currentTimeMillis());
            long hrs = remMs / 3_600_000L;
            long mins = (remMs % 3_600_000L) / 60_000L;
            l.add(new AlertItem(SEV_INFO, "🎁", "Pro Trial",
                    (hrs > 0 ? hrs + "j " + mins + "m" : mins + " menit") + " sisa — ketuk untuk Upgrade.", "Upgrade",
                    () -> open(UpgradeActivity.class), false));
        }
        return l;
    }

    /** Sekali per tampilan: sirine pesanan WA lama sudah dimatikan. */
    private boolean staleOrderAlertStopped;

    private void renderAlerts() {
        LinearLayout row = findViewById(R.id.gjhAlertRow);
        View section = findViewById(R.id.gjhAlertSection);
        if (row == null || section == null) return;
        List<AlertItem> list = buildAlerts();
        row.removeAllViews();
        for (AlertItem a : list) inflateAlert(row, a);
        section.setVisibility(list.isEmpty() ? View.GONE : View.VISIBLE);
        View dot = findViewById(R.id.gjhAvatarDot);
        if (dot != null) dot.setVisibility(list.isEmpty() ? View.GONE : View.VISIBLE);
        // Fitur "Pesanan dari WA" dihapus: matikan kedip + sirine pesanan yang mungkin tertinggal dari
        // versi sebelum pembaruan (hanya setelah kueri pertama selesai, sama dgn perilaku lama).
        if (!snap.loaded || staleOrderAlertStopped) return;
        staleOrderAlertStopped = true;
        NotificationAlertHelper.stopAlert();
        OrderAlertService.stop(this);
    }

    private View inflateAlert(LinearLayout row, AlertItem a) {
        View v = LayoutInflater.from(this).inflate(R.layout.gjh_item_alert, row, false);
        int bgRes = a.sev == SEV_DANGER ? R.color.gj_danger_bg : a.sev == SEV_WARNING ? R.color.gj_warning_bg : R.color.gj_info_bg;
        int fgRes = a.sev == SEV_DANGER ? R.color.gj_danger_text : a.sev == SEV_WARNING ? R.color.gj_warning_text : R.color.gj_info_text;
        int fg = col(fgRes);
        v.setBackground(new RippleDrawable(ColorStateList.valueOf(ColorUtils.setAlphaComponent(fg, 31)),
                rounded(col(bgRes), 16), null));
        TextView emoji = v.findViewById(R.id.gjhAlertEmoji);
        if (emoji != null) emoji.setText(a.emoji);
        TextView title = v.findViewById(R.id.gjhAlertTitle);
        if (title != null) {
            title.setText(a.title);
            title.setTextColor(fg);
        }
        TextView body = v.findViewById(R.id.gjhAlertBody);
        if (body != null) {
            body.setText(a.body);
            body.setTextColor(fg);
        }
        TextView cta = v.findViewById(R.id.gjhAlertCta);
        if (cta != null) {
            cta.setText(a.cta);
            cta.setTextColor(fg);
            cta.setBackground(rounded(col(R.color.gj_surface), 14));
            cta.setOnClickListener(x -> runAlert(a));
        }
        v.setContentDescription(a.title + ". " + a.body);
        v.setOnClickListener(x -> runAlert(a));
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) v.getLayoutParams();
        if (lp == null) {
            lp = new LinearLayout.LayoutParams(px(280), ViewGroup.LayoutParams.MATCH_PARENT);
        } else {
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
        }
        row.addView(v, lp);
        return v;
    }

    private void runAlert(AlertItem a) {
        try {
            a.action.run();
        } catch (Throwable t) {
            Log.e(TAG, "alert " + a.title, t);
        }
    }

    // ---------------------------------------------------------------- Transaksi terakhir

    private void renderRecent() {
        LinearLayout list = findViewById(R.id.gjhRecentList);
        TextView empty = findViewById(R.id.gjhRecentEmpty);
        if (list == null) return;
        list.removeAllViews();
        for (Transaction t : snap.recent) {
            if (t != null) addOrderCard(list, t, false);
        }
        if (empty != null) empty.setVisibility(snap.recent.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void addOrderCard(LinearLayout parent, Transaction t, boolean queueMode) {
        View v = LayoutInflater.from(this).inflate(R.layout.gjh_item_order, parent, false);
        boolean jual = Transaction.TYPE_JUAL.equals(t.getType());
        TextView icon = v.findViewById(R.id.gjhOrderIcon);
        if (icon != null) {
            icon.setText(queueMode ? "🚚" : jual ? "🛒" : "↩️");
            icon.setBackground(rounded(col(jual || queueMode ? R.color.gj_green_tint : R.color.gj_blue_tint), 10));
        }
        String name = t.getCustomerName() != null && !t.getCustomerName().trim().isEmpty()
                ? t.getCustomerName().trim() : "Pelanggan";
        TextView tvName = v.findViewById(R.id.gjhOrderName);
        if (tvName != null) tvName.setText(name);
        StringBuilder meta = new StringBuilder(itemSummary(t, jual));
        String when = queueMode ? Ts.hm(t.getDeliveryQueuedAt()) : shortWhen(t.getEffectiveTime());
        if (when != null && !when.isEmpty()) meta.append(" · ").append(queueMode ? "antre " + when : when);
        if (queueMode && t.getCustomerAddress() != null && !t.getCustomerAddress().trim().isEmpty()) {
            meta.append(NL).append(t.getCustomerAddress().trim());
        }
        TextView tvMeta = v.findViewById(R.id.gjhOrderMeta);
        if (tvMeta != null) tvMeta.setText(meta.toString());
        TextView total = v.findViewById(R.id.gjhOrderTotal);
        String totalText = jual || t.getTotalHarga() > 0 ? "Rp " + nf.format(t.getTotalHarga()) : t.getJumlahGalon() + " galon";
        if (total != null) total.setText(totalText);
        TextView pill = v.findViewById(R.id.gjhOrderPill);
        if (pill != null) {
            if (queueMode) {
                if (t.isOpenDispatch()) stylePill(pill, "Terbuka", R.color.gj_info_bg, R.color.gj_info_text);
                else if (t.isOrderPriority()) stylePill(pill, "⚡ Prioritas", R.color.gj_danger_bg, R.color.gj_danger_text);
                else stylePill(pill, "Antre", R.color.gj_warning_bg, R.color.gj_warning_text);
            } else if (jual) {
                stylePill(pill, "JUAL", R.color.gj_primary_container, R.color.gj_on_primary_container);
            } else {
                stylePill(pill, "KEMBALI", R.color.gj_info_bg, R.color.gj_info_text);
            }
        }
        v.setContentDescription(name + ", " + meta + ", " + totalText);
        v.setOnClickListener(x -> {
            if (queueMode) open(DeliveryQueueActivity.class);
            else openReceipt(t);
        });
        parent.addView(v);
    }

    private String itemSummary(Transaction t, boolean jual) {
        if (!jual) return "Kembali · " + t.getJumlahGalon() + " galon";
        List<TransactionItem> items = t.getItems();
        if (items != null && items.size() > 1) return items.size() + " produk · " + t.getJumlahGalon() + " galon";
        String p = t.getProductName();
        if ((p == null || p.trim().isEmpty()) && items != null && items.size() == 1 && items.get(0) != null) {
            p = items.get(0).productName;
        }
        if (p == null || p.trim().isEmpty()) p = "Galon";
        return p.trim() + " · " + t.getJumlahGalon() + " galon";
    }

    /** Jam (HH:mm) bila hari ini, selain itu "dd/MM HH:mm" — dari stempel bentuk apa pun (lokal/ISO-UTC). */
    private String shortWhen(String stamp) {
        String local = Ts.local(stamp);   // "yyyy-MM-dd HH:mm:ss" lokal, "" bila tak terurai
        if (local.length() < 16) return "";
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        if (local.startsWith(today)) return local.substring(11, 16);
        return local.substring(8, 10) + "/" + local.substring(5, 7) + " " + local.substring(11, 16);
    }

    private void stylePill(TextView tv, String text, int bgRes, int fgRes) {
        tv.setText(text);
        tv.setTextColor(col(fgRes));
        tv.setBackground(rounded(col(bgRes), 10));
        tv.setVisibility(View.VISIBLE);
    }

    // ---------------------------------------------------------------- sheets

    private BottomSheetDialog newSheet() {
        return new BottomSheetDialog(this, R.style.GjHome_BottomSheetDialog);
    }

    /** Buka lembar terentang penuh (lewati keadaan setengah). */
    private void expandOnShow(BottomSheetDialog d, boolean fullHeight) {
        d.setOnShowListener(x -> {
            try {
                View sheet = d.findViewById(com.google.android.material.R.id.design_bottom_sheet);
                if (sheet != null && fullHeight) {
                    ViewGroup.LayoutParams lp = sheet.getLayoutParams();
                    lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
                    sheet.setLayoutParams(lp);
                }
                BottomSheetBehavior<FrameLayout> b = d.getBehavior();
                b.setSkipCollapsed(true);
                b.setState(BottomSheetBehavior.STATE_EXPANDED);
            } catch (Throwable t) {
                Log.w(TAG, "sheet expand", t);
            }
        });
    }

    /** Lembar "Semua menu": SEMUA menu admin dikelompokkan, 4 ubin per baris. */
    private void showAllMenuSheet() {
        final BottomSheetDialog d = newSheet();
        View root = LayoutInflater.from(this).inflate(R.layout.gjh_sheet_list, null, false);
        TextView title = root.findViewById(R.id.gjhSheetTitle);
        if (title != null) title.setText("Semua menu");
        TextView act = root.findViewById(R.id.gjhSheetAction);
        if (act != null) {
            act.setText("✏️ Atur menu");
            act.setContentDescription("Atur menu Beranda");
            act.setVisibility(View.VISIBLE);
            act.setOnClickListener(x -> {
                try {
                    d.dismiss();
                } catch (Throwable ignored) {
                }
                safe("editTiles", this::showEditTilesSheet);
            });
        }
        LinearLayout body = root.findViewById(R.id.gjhSheetBody);
        for (String g : GROUP_ORDER) {
            List<MenuEntry> es = new ArrayList<>();
            for (MenuEntry e : registry) if (g.equals(e.group) && visible(e)) es.add(e);
            if (es.isEmpty()) continue;
            body.addView(groupHeader(g, 16, 16));
            LinearLayout grid = new LinearLayout(this);
            grid.setOrientation(LinearLayout.VERTICAL);
            grid.setPadding(px(8), 0, px(8), 0);
            LinearLayout row = null;
            int cells = ((es.size() + 3) / 4) * 4;
            for (int i = 0; i < cells; i++) {
                if (i % 4 == 0) {
                    row = newRow();
                    grid.addView(row);
                }
                if (i < es.size()) inflateTile(row, es.get(i), false, d);
                else addSpacer(row);
            }
            body.addView(grid);
        }
        d.setContentView(root);
        expandOnShow(d, true);
        d.show();
    }

    /** Lembar aksi mini "Lainnya" di kartu Penjualan: Setoran, Pengeluaran, Laporan, Export, Transaksi. */
    private void showSalesMoreSheet() {
        final BottomSheetDialog d = newSheet();
        View root = LayoutInflater.from(this).inflate(R.layout.gjh_sheet_list, null, false);
        TextView title = root.findViewById(R.id.gjhSheetTitle);
        if (title != null) title.setText("Lainnya");
        LinearLayout body = root.findViewById(R.id.gjhSheetBody);
        String[][] items = {
                {"setoran", "Catat setoran tunai hari ini"},
                {"pengeluaran", "Catat & lihat pengeluaran"},
                {"laporan", "Report & grafik penjualan"},
                {"export", "Ekspor data ke file"},
                {"transaksi", "Daftar semua transaksi"}};
        for (String[] it : items) {
            final MenuEntry e = entry(it[0]);
            if (e == null || !visible(e)) continue;
            if (body.getChildCount() > 0) body.addView(insetDivider());
            String label = "laporan".equals(e.id) ? "Laporan" : e.label;
            body.addView(makeRow(body, e.emoji, label, it[1], v -> {
                d.dismiss();
                runEntry(e);
            }));
        }
        d.setContentView(root);
        expandOnShow(d, false);
        d.show();
    }

    /** Lembar pencarian: saring registri menu secara langsung + pintasan cari pelanggan / transaksi. */
    private void showSearchSheet() {
        final BottomSheetDialog d = newSheet();
        View root = LayoutInflater.from(this).inflate(R.layout.gjh_sheet_search, null, false);
        final EditText input = root.findViewById(R.id.gjhSearchInput);
        final LinearLayout results = root.findViewById(R.id.gjhSearchResults);
        ImageView icon = root.findViewById(R.id.gjhSearchFieldIcon);
        if (icon != null) ImageViewCompat.setImageTintList(icon, ColorStateList.valueOf(col(R.color.gj_text_secondary)));
        renderSearchResults(d, results, "");
        if (input != null) {
            input.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                }

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                }

                @Override
                public void afterTextChanged(Editable s) {
                    safe("search", () -> renderSearchResults(d, results, s.toString().trim()));
                }
            });
            input.setOnEditorActionListener((tv, actionId, ev) -> {
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    hideKeyboard(input);
                    return true;
                }
                return false;
            });
        }
        d.setContentView(root);
        expandOnShow(d, true);
        Window w = d.getWindow();
        if (w != null) {
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    | WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        }
        d.show();
        if (input != null) input.requestFocus();
    }

    private void renderSearchResults(BottomSheetDialog d, LinearLayout results, String q) {
        if (results == null) return;
        results.removeAllViews();
        String ql = q.toLowerCase(Locale.ROOT);
        String quoted = q.isEmpty() ? "" : " “" + q + "”";
        results.addView(groupHeader("Cari data", 16, 8));
        results.addView(makeRow(results, "👥", "Cari pelanggan" + quoted, "Buka daftar pelanggan", v -> {
            d.dismiss();
            open(CustomerListActivity.class);
        }));
        results.addView(insetDivider());
        results.addView(makeRow(results, "🧾", "Cari transaksi" + quoted, "Buka daftar transaksi / struk", v -> {
            d.dismiss();
            open(TransactionListActivity.class);
        }));
        results.addView(groupHeader(q.isEmpty() ? "Semua menu" : "Menu", 20, 8));
        int n = 0;
        for (final MenuEntry e : registry) {
            if (!visible(e)) continue;
            if (!ql.isEmpty()) {
                boolean hit = e.label.toLowerCase(Locale.ROOT).contains(ql)
                        || (e.shortLabel != null && e.shortLabel.toLowerCase(Locale.ROOT).contains(ql))
                        || e.group.toLowerCase(Locale.ROOT).contains(ql);
                if (!hit) continue;
            }
            if (n > 0) results.addView(insetDivider());
            View row = makeRow(results, e.emoji, e.label, e.group, v -> {
                d.dismiss();
                runEntry(e);
            });
            int b = badgeOf(e);
            TextView badge = row.findViewById(R.id.gjhRowBadge);
            if (badge != null && b > 0) {
                badge.setText(fmtCount(b));
                badge.setVisibility(View.VISIBLE);
            }
            results.addView(row);
            n++;
        }
        if (n == 0) {
            TextView none = new TextView(this);
            none.setText("Tidak ada menu yang cocok.");
            none.setTextColor(col(R.color.gj_text_secondary));
            none.setTextSize(14);
            none.setPadding(px(16), px(8), px(16), px(8));
            results.addView(none);
        }
    }

    private void hideKeyboard(View v) {
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
        } catch (Throwable ignored) {
        }
    }

    // ---------------------------------------------------------------- shared row helpers

    /** Baris 56dp (belum ditambahkan ke parent — pemanggil yang menambahkan). */
    private View makeRow(ViewGroup parent, String emoji, String title, String sub, View.OnClickListener click) {
        View r = LayoutInflater.from(this).inflate(R.layout.gjh_item_row, parent, false);
        TextView ic = r.findViewById(R.id.gjhRowIcon);
        if (ic != null) ic.setText(emoji);
        TextView tt = r.findViewById(R.id.gjhRowTitle);
        if (tt != null) tt.setText(title);
        TextView st = r.findViewById(R.id.gjhRowSub);
        if (st != null) {
            boolean has = sub != null && !sub.isEmpty();
            st.setVisibility(has ? View.VISIBLE : View.GONE);
            if (has) st.setText(sub);
        }
        ImageView chev = r.findViewById(R.id.gjhRowChevron);
        if (chev != null) ImageViewCompat.setImageTintList(chev, ColorStateList.valueOf(col(R.color.gj_text_secondary)));
        if (click != null) r.setOnClickListener(click);
        return r;
    }

    private View insetDivider() {
        View d = new View(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, px(1)));
        lp.setMarginStart(px(56));
        d.setLayoutParams(lp);
        d.setBackgroundColor(col(R.color.gj_divider));
        return d;
    }

    private TextView groupHeader(String text, int topDp, int bottomDp) {
        TextView h = new TextView(this);
        h.setText(text);
        h.setTextSize(13);
        h.setTextColor(col(R.color.gj_text_secondary));
        h.setPadding(px(16), px(topDp), px(16), px(bottomDp));
        return h;
    }

    /** Kartu grup putih 12dp (tab Akun / Chat); @return wadah baris di dalamnya. */
    private LinearLayout addGroup(LinearLayout parent, String title) {
        if (title != null) parent.addView(groupHeader(title, 20, 8));
        else {
            View gap = new View(this);
            parent.addView(gap, new LinearLayout.LayoutParams(1, px(20)));
        }
        MaterialCardView card = new MaterialCardView(this);
        card.setRadius(dp(12));
        card.setCardElevation(0f);
        card.setStrokeWidth(Math.max(1, px(1)));
        card.setStrokeColor(col(R.color.gj_divider));
        card.setCardBackgroundColor(col(R.color.gj_surface));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginStart(px(16));
        lp.setMarginEnd(px(16));
        parent.addView(card, lp);
        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        card.addView(inner, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return inner;
    }

    private View addGroupRow(LinearLayout group, String emoji, String title, String sub, View.OnClickListener click) {
        if (group.getChildCount() > 0) group.addView(insetDivider());
        View r = makeRow(group, emoji, title, sub, click);
        group.addView(r);
        return r;
    }

    // ================================================================== Tab Pesanan

    private void bindPesanan() {
        int[] tabs = {R.id.gjhPTab0, R.id.gjhPTab1, R.id.gjhPTab2};
        for (int i = 0; i < tabs.length; i++) {
            final int idx = i;
            clickable(tabs[i], v -> selectPesananPage(idx));
        }
        clickable(R.id.gjhBtnQueue, v -> open(DeliveryQueueActivity.class));
        clickable(R.id.gjhBtnAllTrx, v -> open(TransactionListActivity.class));
        clickable(R.id.gjhChipQueue, v -> open(DeliveryQueueActivity.class));
        selectPesananPage(0);
    }

    private void selectPesananPage(int idx) {
        pesananPage = idx;
        int[] pages = {R.id.gjhPPage0, R.id.gjhPPage1, R.id.gjhPPage2};
        int[] labels = {R.id.gjhPTab0Label, R.id.gjhPTab1Label, R.id.gjhPTab2Label};
        int[] lines = {R.id.gjhPTab0Line, R.id.gjhPTab1Line, R.id.gjhPTab2Line};
        int[] tabIds = {R.id.gjhPTab0, R.id.gjhPTab1, R.id.gjhPTab2};
        for (int i = 0; i < 3; i++) {
            boolean on = i == idx;
            View p = findViewById(pages[i]);
            if (p != null) p.setVisibility(on ? View.VISIBLE : View.GONE);
            TextView l = findViewById(labels[i]);
            if (l != null) {
                l.setTextColor(col(on ? R.color.gj_text_primary : R.color.gj_text_tertiary));
                l.setTypeface(Typeface.DEFAULT, on ? Typeface.BOLD : Typeface.NORMAL);
            }
            View line = findViewById(lines[i]);
            if (line != null) line.setVisibility(on ? View.VISIBLE : View.INVISIBLE);
            View tab = findViewById(tabIds[i]);
            if (tab != null) tab.setSelected(on);
        }
    }

    private void renderPesanan() {
        pesananDirty = false;
        boolean qVis = queueVisible(ms);
        for (int id : new int[]{R.id.gjhChipQueue, R.id.gjhBtnQueue}) {
            View v = findViewById(id);
            if (v != null) v.setVisibility(qVis ? View.VISIBLE : View.GONE);
        }
        TextView chipQ = findViewById(R.id.gjhChipQueue);
        if (chipQ != null) chipQ.setText("🚚 " + snap.queue + " antrean di HP ini");
        // Berjalan: antrean delivery lokal (sama dengan badge/Antrian Delivery).
        LinearLayout q = findViewById(R.id.gjhQueueList);
        if (q != null) {
            q.removeAllViews();
            int shown = 0;
            for (Transaction t : snap.queueList) {
                if (t == null) continue;
                if (shown >= LIST_CAP) break;
                addOrderCard(q, t, true);
                shown++;
            }
            int more = snap.queueList.size() - shown;
            if (more > 0) q.addView(moreText("+" + more + " lainnya — buka Antrean Delivery"));
        }
        View qEmpty = findViewById(R.id.gjhQueueEmpty);
        if (qEmpty != null) qEmpty.setVisibility(snap.queueList.isEmpty() ? View.VISIBLE : View.GONE);

        // Riwayat: transaksi hari ini (terbaru di atas).
        LinearLayout tl = findViewById(R.id.gjhTodayList);
        if (tl != null) {
            tl.removeAllViews();
            int shown = 0;
            for (Transaction t : snap.today) {
                if (t == null) continue;
                if (shown >= LIST_CAP) break;
                addOrderCard(tl, t, false);
                shown++;
            }
            int more = snap.today.size() - shown;
            if (more > 0) tl.addView(moreText("+" + more + " transaksi lain hari ini"));
        }
        TextView title = findViewById(R.id.gjhTodayTitle);
        if (title != null) title.setText("Transaksi hari ini (" + snap.today.size() + ")");
        View tEmpty = findViewById(R.id.gjhTodayEmpty);
        if (tEmpty != null) tEmpty.setVisibility(snap.today.isEmpty() ? View.VISIBLE : View.GONE);

        // Terjadwal: hanya perangkat terhubung + admin (sama dengan tombol beranda lama).
        TextView info = findViewById(R.id.gjhSchedInfo);
        com.google.android.material.button.MaterialButton btn = findViewById(R.id.gjhBtnSched);
        boolean ok = ms.admin && ms.enrolled;
        if (info != null) {
            info.setText(ok
                    ? "Jadwal kunjungan rutin pelanggan — pesanan dibuat otomatis sesuai jadwalnya dan masuk ke antrean."
                    : "Pesanan terjadwal butuh perangkat yang terhubung ke server. Hubungkan dulu lewat Sinkronisasi Online.");
        }
        if (btn != null) {
            btn.setText(ok ? "Buka Pesanan Terjadwal" : "Buka Sinkronisasi Online");
            btn.setOnClickListener(v -> open(ok ? ScheduledOrderActivity.class : SyncSettingsActivity.class));
        }
    }

    private TextView moreText(String s) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(13);
        tv.setTextColor(col(R.color.gj_text_secondary));
        tv.setPadding(px(16), px(8), px(16), px(8));
        return tv;
    }

    // ================================================================== Tab Chat

    private void renderChat() {
        chatDirty = false;
        LinearLayout actions = findViewById(R.id.gjhChatActions);
        if (actions != null) {
            actions.removeAllViews();
            int n = 0;
            if (ms.canWaChat) {
                addCircle(actions, "💬", "Chat WA", R.color.gj_green_tint, Math.max(0, waUnread),
                        () -> open(WaInboxActivity.class));
                n++;
            }
            addCircle(actions, "⚠️", "Komplain", R.color.gj_red_tint, snap.complaints,
                    () -> open(ComplaintListActivity.class));
            n++;
            if (ms.admin && ms.enrolled) {
                addCircle(actions, "📣", "Broadcast", R.color.gj_blue_tint, 0,
                        () -> open(WhatsAppMenuActivity.class));
                n++;
            }
            while (n++ < 4) addSpacer(actions);
        }

        TextView state = findViewById(R.id.gjhWaState);
        LinearLayout list = findViewById(R.id.gjhWaAccounts);
        if (list == null) return;
        list.removeAllViews();
        String msg = null;
        if (!ms.canWaChat) {
            msg = "Chat WA tidak tersedia untuk peran ini.";
        } else if (waAccounts == null) {
            msg = waFailed ? "Daftar akun belum bisa dimuat — periksa koneksi internet." : "Memuat akun WhatsApp…";
        } else if (waAccounts.length() == 0) {
            msg = "Belum ada akun WhatsApp yang terhubung.";
        }
        if (state != null) {
            state.setVisibility(msg != null ? View.VISIBLE : View.GONE);
            if (msg != null) state.setText(msg);
        }
        if (msg != null) return;
        LinearLayout group = addGroup(list, null);
        for (int i = 0; i < waAccounts.length(); i++) {
            JSONObject a = waAccounts.optJSONObject(i);
            if (a == null) continue;
            String acc = a.optString("account", "");
            String label = a.optString("label", acc);
            if (label == null || label.isEmpty() || "null".equals(label)) label = acc.isEmpty() ? "Akun WhatsApp" : acc;
            String st = a.isNull("status") ? "" : a.optString("status", "");
            String sub = "open".equals(st) || st.isEmpty() ? "Terhubung" : "⚠ " + st;
            int unread = a.optInt("unread");
            View r = addGroupRow(group, "💬", label, sub, v -> open(WaInboxActivity.class));
            TextView b = r.findViewById(R.id.gjhRowBadge);
            if (b != null && unread > 0) {
                b.setText(fmtCount(unread));
                b.setVisibility(View.VISIBLE);
            }
            r.setContentDescription(label + ", " + sub + (unread > 0 ? ", " + unread + " belum dibaca" : ""));
        }
    }

    private void addCircle(LinearLayout parent, String emoji, String label, int tintRes, int badge, Runnable action) {
        View v = LayoutInflater.from(this).inflate(R.layout.gjh_item_circle_action, parent, false);
        TextView e = v.findViewById(R.id.gjhCircleEmoji);
        if (e != null) {
            e.setText(emoji);
            e.setBackground(oval(col(tintRes)));
        }
        TextView l = v.findViewById(R.id.gjhCircleLabel);
        if (l != null) l.setText(label);
        TextView b = v.findViewById(R.id.gjhCircleBadge);
        if (b != null) {
            b.setVisibility(badge > 0 ? View.VISIBLE : View.GONE);
            b.setText(fmtCount(badge));
        }
        v.setContentDescription(label + (badge > 0 ? ", " + badge : ""));
        v.setOnClickListener(x -> {
            try {
                action.run();
            } catch (Throwable t) {
                Log.e(TAG, "circle " + label, t);
            }
        });
        parent.addView(v);
    }

    // ================================================================== Tab Akun

    private void renderAkun() {
        TextView name = findViewById(R.id.gjhAkunName);
        if (name != null) name.setText(ms.name != null && !ms.name.trim().isEmpty() ? ms.name.trim()
                : ms.roleLabel.isEmpty() ? "Pengguna" : ms.roleLabel);
        TextView dev = findViewById(R.id.gjhAkunDevice);
        if (dev != null) dev.setText(ms.place());
        TextView ver = findViewById(R.id.gjhAkunVersion);
        if (ver != null) ver.setText("Versi aplikasi " + BuildConfig.VERSION_NAME);
        renderHeader();

        LinearLayout groups = findViewById(R.id.gjhAkunGroups);
        if (groups == null) return;
        groups.removeAllViews();
        // Status delivery dibaca segar (bisa baru berubah lewat sakelar / ArmadaPicker).
        SyncSettings cfg = new SyncSettings(settingsDao);
        boolean enrolled = cfg.isEnrolled();
        boolean deliveryDevice = cfg.isDeliveryDevice();

        // ---- Perangkat
        LinearLayout g = addGroup(groups, "Perangkat");
        if (ms.admin && enrolled) {
            View r = addGroupRow(g, "🚚", "Delivery di HP ini", null, null);
            safe("deliverySwitch", () -> bindDeliverySwitch(r, cfg));
        }
        if (enrolled && deliveryDevice) {
            String armadaName = cfg.getArmadaName();
            String sub = cfg.isArmadaNonDelivery() ? "Non Delivery" : armadaName.isEmpty() ? "Pilih armada"
                    : armadaName + " · " + cfg.getMaxLoad() + " galon"
                    + (cfg.getArmadaPartner().isEmpty() ? "" : " · 🤝 " + cfg.getArmadaPartner());
            addGroupRow(g, "🛻", "Armada", sub, v -> ArmadaPicker.change(this));
        }
        addGroupRow(g, "🔄", "Sinkronisasi Online", null, v -> open(SyncSettingsActivity.class));
        if (ms.admin && enrolled) {
            addGroupRow(g, "⚙️", "Pengaturan Perangkat", null, v -> open(DeviceAdminActivity.class));
        }

        // ---- Depot
        g = addGroup(groups, "Depot");
        if (ms.admin) addGroupRow(g, "🛠️", "Pengaturan", null, v -> open(SettingsActivity.class));
        if (ms.admin && enrolled) {
            addGroupRow(g, "🖥️", "Dashboard Web", "Semua fitur web di HP", v -> open(AdminWebActivity.class));
            addGroupRow(g, "💬", "WhatsApp", "Akun, broadcast, status", v -> open(WhatsAppMenuActivity.class));
        }

        // ---- Tampilan (admin saja: setelan berlaku untuk seluruh HP)
        if (ms.admin) {
            g = addGroup(groups, "Tampilan");
            View look = addGroupRow(g, "✨", "Tampilan Tema v2", "Matikan untuk kembali ke tampilan lama.", null);
            safe("lookSwitch", () -> bindLookSwitch(look));
        }

        // ---- Info
        g = addGroup(groups, "Info");
        addGroupRow(g, "ℹ️", "Tentang", "v" + BuildConfig.VERSION_NAME, v -> showAboutDialog());
        addGroupRow(g, "📜", "Riwayat versi", null, v -> open(ChangelogActivity.class));

        // ---- Keluar (peran berabsen memakai kartu shift Istirahat/Pulang di atas tab Akun)
        if (ms.tracksAttendance) return;
        g = addGroup(groups, null);
        View out = addGroupRow(g, "🚪", "Keluar", null, v -> doAdminLogout());
        TextView outTitle = out.findViewById(R.id.gjhRowTitle);
        if (outTitle != null) {
            outTitle.setTextColor(col(R.color.gj_danger));
            outTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        }
        View chev = out.findViewById(R.id.gjhRowChevron);
        if (chev != null) chev.setVisibility(View.GONE);
    }

    /**
     * Sakelar "Delivery di HP ini" (port bindDeliverySwitch lama): mengubah is_delivery HP INI lewat
     * /api/admin/devices/{uuid}/flags; mati → konfirmasi; nyala → pilih armada dulu (ArmadaPicker); gagal → kembali.
     */
    private void bindDeliverySwitch(View row, SyncSettings cfg) {
        final SwitchMaterial sw = row.findViewById(R.id.gjhRowSwitch);
        View chev = row.findViewById(R.id.gjhRowChevron);
        if (chev != null) chev.setVisibility(View.GONE);
        if (sw == null) return;
        // Status selalu dari SyncSettings; jangan dipulihkan sistem (id kembar antarbaris → listener terpicu palsu).
        sw.setSaveEnabled(false);
        sw.setFocusable(false);
        sw.setClickable(false);
        sw.setVisibility(View.VISIBLE);
        sw.setOnCheckedChangeListener(null);
        boolean on = cfg.isDeliveryDevice() && !cfg.isArmadaNonDelivery();   // Non Delivery = tak ikut mengantar → mati
        sw.setChecked(on);
        sw.setEnabled(!deliverySwitchBusy);
        a11ySwitchRow(row, on);
        TextView sub = row.findViewById(R.id.gjhRowSub);
        if (sub != null) {
            sub.setVisibility(View.VISIBLE);
            sub.setText(deliverySwitchBusy ? "Menyimpan…" : on ? "HP ini menerima order antar" : "HP ini tidak menerima order antar");
        }
        row.setOnClickListener(v -> {
            if (!deliverySwitchBusy) sw.toggle();
        });
        sw.setOnCheckedChangeListener((b, checked) -> {
            if (!checked) {
                new AlertDialog.Builder(this)
                        .setTitle("Matikan Delivery di HP ini?")
                        .setMessage("HP ini tidak lagi jadi tujuan pengantaran: tidak muncul di pilihan perangkat dan tidak menerima order baru. Order yang sudah di antriannya tetap ada.")
                        .setPositiveButton("Matikan", (d, w) -> setThisDeviceDelivery(false))
                        .setNegativeButton("Batal", (d, w) -> rebindAkun())
                        .setOnCancelListener(d -> rebindAkun())
                        .show();
            } else {
                ArmadaPicker.forDeliveryOn(this, () -> setThisDeviceDelivery(true), this::rebindAkun);
            }
        });
    }

    /** ArmadaPicker melapor lewat invalidateOptionsMenu(); layar ini tak punya menu → gambar ulang Akun. */
    @Override
    public void invalidateOptionsMenu() {
        super.invalidateOptionsMenu();
        rebindAkun();
    }

    private void rebindAkun() {
        if (isFinishing() || isDestroyed()) return;
        if (currentTab == TAB_AKUN) safe("akun", this::renderAkun);
    }

    private void setThisDeviceDelivery(boolean on) {
        final SyncSettings cfg = new SyncSettings(settingsDao);
        String staffUuid = com.crowja.damiupos.sync.LocationReporter.currentStaffUuid(this);
        String myUuid = cfg.getDeviceUuid();
        if (staffUuid == null || staffUuid.isEmpty() || myUuid == null || myUuid.isEmpty()) {
            Toast.makeText(this, "Login sebagai Admin & sinkronkan dulu.", Toast.LENGTH_LONG).show();
            rebindAkun();
            return;
        }
        deliverySwitchBusy = true;
        rebindAkun();
        new Thread(() -> {
            String msg;
            boolean ok = false;
            try {
                JSONObject b = new JSONObject();
                b.put("is_delivery", on);
                new SyncApi(cfg).adminDeviceUpdate(myUuid, "flags", b, staffUuid);
                cfg.setDeliveryDevice(on);
                ok = true;
                msg = on ? "HP ini jadi perangkat delivery." : "Delivery di HP ini dimatikan.";
            } catch (SyncApi.SyncException se) {
                String m = null;
                try {
                    m = new JSONObject(se.body).optString("message", null);
                } catch (Exception ignored) {
                }
                msg = m != null && !m.isEmpty() ? m : "Ditolak server (kode " + se.code + ").";
            } catch (Exception e) {
                msg = "Gagal — periksa koneksi.";
            }
            final String msgF = msg;
            final boolean okF = ok;
            runOnUiThread(() -> {
                deliverySwitchBusy = false;
                if (isFinishing() || isDestroyed()) return;
                Toast.makeText(this, msgF, Toast.LENGTH_LONG).show();
                rebindAkun();
                if (okF) {
                    refreshAll();
                    com.crowja.damiupos.sync.OnlineTasks.forceConfigRefreshNow(getApplicationContext(), null);
                }
            });
        }).start();
    }

    /** Sakelar "Tampilan Tema v2": OFF → konfirmasi → preferensi mati → MainActivity (tugas baru). */
    /** Baris = satu kontrol aksesibel bertipe Switch (sakelar sendiri tak fokus/klik). */
    private static void a11ySwitchRow(View row, final boolean checked) {
        androidx.core.view.ViewCompat.setAccessibilityDelegate(row, new androidx.core.view.AccessibilityDelegateCompat() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, androidx.core.view.accessibility.AccessibilityNodeInfoCompat info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName("android.widget.Switch");
                info.setCheckable(true);
                info.setChecked(checked);
            }
        });
    }

    private void bindLookSwitch(View row) {
        final SwitchMaterial sw = row.findViewById(R.id.gjhRowSwitch);
        View chev = row.findViewById(R.id.gjhRowChevron);
        if (chev != null) chev.setVisibility(View.GONE);
        if (sw == null) return;
        sw.setSaveEnabled(false);
        sw.setFocusable(false);
        sw.setClickable(false);
        sw.setVisibility(View.VISIBLE);
        sw.setOnCheckedChangeListener(null);
        sw.setChecked(true);
        a11ySwitchRow(row, true);
        row.setOnClickListener(v -> sw.toggle());
        sw.setOnCheckedChangeListener((b, checked) -> {
            if (checked) return;
            new AlertDialog.Builder(this)
                    .setTitle("Kembali ke tampilan lama?")
                    .setMessage(ms.admin
                            ? "Beranda akan kembali ke tampilan sebelumnya. Tampilan Tema v2 bisa dinyalakan lagi dari Pengaturan."
                            : "Beranda akan kembali ke tampilan sebelumnya. Untuk menyalakan Tampilan Tema v2 lagi, minta admin membukanya dari Pengaturan di HP ini.")
                    .setPositiveButton("Ya, kembali", (d, w) -> {
                        GojekUi.setPreferred(this, false);
                        startActivity(new Intent(this, MainActivity.class)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
                        finish();
                    })
                    .setNegativeButton("Batal", (d, w) -> sw.setChecked(true))
                    .setOnCancelListener(d -> sw.setChecked(true))
                    .show();
        });
    }

    /** Admin: logout biasa tanpa absensi/laporan (sama dengan doAdminLogout beranda lama). */
    private void doAdminLogout() {
        settingsDao.clearCurrentUser();
        Intent i = new Intent(this, LoginActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(i);
        finish();
    }

    private void showAboutDialog() {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_about, null);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(view)
                .setPositiveButton("Tutup", null)
                .create();

        TextView tvVersion = view.findViewById(R.id.tvAboutVersion);
        TextView tvSubStatus = view.findViewById(R.id.tvSubStatus);
        TextView tvSubPlan = view.findViewById(R.id.tvSubPlan);
        TextView tvSubExpiry = view.findViewById(R.id.tvSubExpiry);
        com.google.android.material.button.MaterialButton btnUpgrade = view.findViewById(R.id.btnAboutUpgrade);
        com.google.android.material.button.MaterialButton btnManage = view.findViewById(R.id.btnAboutManage);
        com.google.android.material.button.MaterialButton btnChangelog = view.findViewById(R.id.btnAboutChangelog);

        if (tvVersion != null) tvVersion.setText("v" + BuildConfig.VERSION_NAME);
        if (btnChangelog != null) {
            btnChangelog.setOnClickListener(v -> {
                open(ChangelogActivity.class);
                dialog.dismiss();
            });
        }
        boolean pro = settingsDao.isProActive();
        if (pro) {
            if (tvSubStatus != null) {
                tvSubStatus.setText("Pro Aktif");
                tvSubStatus.setTextColor(col(R.color.gj_primary));
            }
            String productId = settingsDao.getProProductId();
            if (tvSubPlan != null) {
                if (BuildConfig.SUB_PRODUCT_MONTHLY.equals(productId)) {
                    tvSubPlan.setText("Paket: Bulanan");
                    tvSubPlan.setVisibility(View.VISIBLE);
                } else if (BuildConfig.SUB_PRODUCT_YEARLY.equals(productId)) {
                    tvSubPlan.setText("Paket: Tahunan");
                    tvSubPlan.setVisibility(View.VISIBLE);
                }
            }
            long expiry = settingsDao.getProExpiryAt();
            if (expiry > 0 && tvSubExpiry != null) {
                SimpleDateFormat sdf = new SimpleDateFormat("d MMM yyyy", new Locale("id", "ID"));
                tvSubExpiry.setText("Berlaku hingga: " + sdf.format(new Date(expiry)));
                tvSubExpiry.setVisibility(View.VISIBLE);
            }
            if (btnUpgrade != null) {
                btnUpgrade.setText("Status: Pro");
                btnUpgrade.setEnabled(false);
            }
            if (btnManage != null) {
                btnManage.setVisibility(View.VISIBLE);
                btnManage.setOnClickListener(v -> {
                    try {
                        Uri uri = Uri.parse("https://play.google.com/store/account/subscriptions?sku="
                                + (productId == null ? "" : productId) + "&package=" + getPackageName());
                        startActivity(new Intent(Intent.ACTION_VIEW, uri));
                        dialog.dismiss();
                    } catch (Exception e) {
                        Toast.makeText(this, "Tidak dapat membuka Play Store", Toast.LENGTH_SHORT).show();
                    }
                });
            }
        } else {
            if (tvSubStatus != null) {
                tvSubStatus.setText("Gratis");
                tvSubStatus.setTextColor(col(R.color.gj_text_primary));
            }
            if (btnUpgrade != null) {
                btnUpgrade.setOnClickListener(v -> {
                    open(UpgradeActivity.class);
                    dialog.dismiss();
                });
            }
        }
        dialog.show();
    }

    // ================================================================== duties (port beranda lama)

    /** Tampilkan pesan admin tertunda ("Kirim Pesan ke Perangkat") — sekali, lalu dibersihkan. */
    private void showPendingAdminMessage() {
        adminMsgDialog = ForegroundDuties.showPendingAdminMessage(this, settingsDao, adminMsgDialog);
    }

    /**
     * Popup "⚠️ Lengkapi Data Pelanggan" untuk pesanan baru yang pelanggannya belum lengkap foto/koordinat
     * (dikumpulkan saat sinkron). Satu popup per kunjungan layar; sisanya diantrekan lagi.
     */
    private void showPendingIncompleteWarnings() {
        if (isFinishing() || settingsDao == null || customerDao == null) return;
        // Setting cabut-kredit nonaktif untuk cabang ini → tak ada void yang perlu dicegah.
        if (!settingsDao.isRevokeCreditIncompleteEnabled()) return;
        List<Long> ids = settingsDao.takePendingIncompleteWarn();
        if (ids.isEmpty()) return;
        com.crowja.damiupos.model.Customer toShow = null;
        List<Long> rest = new ArrayList<>();
        for (Long id : ids) {
            com.crowja.damiupos.model.Customer c = customerDao.getById(id);
            if (!IncompleteCustomerDialog.shouldWarn(c)) continue;   // sudah lengkap → buang
            if (toShow == null) toShow = c;
            else rest.add(id);
        }
        if (!rest.isEmpty()) settingsDao.addPendingIncompleteWarn(rest);
        if (toShow != null) IncompleteCustomerDialog.warn(this, toShow);
    }

    /** Peringatkan bila ada transaksi lama (>15 mnt) yang belum tersinkron (lihat TransactionDao.countUnsynced). */
    private void warnIfUnsyncedTransactions() {
        if (transactionDao == null) return;
        new Thread(() -> {
            final int stuck;
            try {
                stuck = transactionDao.countUnsynced(15);
            } catch (Throwable ignored) {
                return;
            }
            if (stuck <= 0) return;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                Toast.makeText(this,
                        "⚠ " + stuck + " transaksi belum tersinkron ke server. Pastikan HP online; "
                                + "sistem akan mencoba mengirim ulang otomatis.",
                        Toast.LENGTH_LONG).show();
                SyncScheduler.syncNow(getApplicationContext());
            });
        }, "gj-unsynced").start();
    }

    /**
     * Minta izin/pengaturan yang membuat sinkronisasi + lapor koordinat TETAP JALAN saat app tidak di
     * foreground (port ensureBackgroundReliability lama): (1) pengecualian optimasi baterai — sekali saja;
     * (2) lokasi "sepanjang waktu" — tiap sesi proses sampai diberi, bila pelacakan aktif & fine sudah diberi.
     */
    private void ensureBackgroundReliability() {
        SyncSettings cfg = new SyncSettings(new SettingsDao(DatabaseHelper.getInstance(this)));
        if (!cfg.isEnrolled()) return;
        android.content.SharedPreferences p = getSharedPreferences("damiu_reliability", MODE_PRIVATE);

        if (!p.getBoolean("battery_asked", false)) {
            p.edit().putBoolean("battery_asked", true).apply();
            try {
                android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
                if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                    Intent i = new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                            .setData(Uri.parse("package:" + getPackageName()));
                    if (i.resolveActivity(getPackageManager()) != null) startActivity(i);
                }
            } catch (Throwable ignored) {
            }
        }

        if (cfg.isLocationTrackingEnabled()
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                && !bgLocationAsked
                && ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION)
                == android.content.pm.PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            bgLocationAsked = true;
            androidx.core.app.ActivityCompat.requestPermissions(this,
                    new String[]{android.Manifest.permission.ACCESS_BACKGROUND_LOCATION}, REQ_BG_LOCATION);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_LOCATION && grantResults.length > 0
                && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            LocationService.start(this);
            ensureBackgroundReliability();   // lanjut minta lokasi "sepanjang waktu" + pengecualian baterai
        } else if (requestCode == REQ_BG_LOCATION) {
            // Apapun hasilnya, mulai/segarkan service — dgn bg-location ter-grant, restart dari background/boot
            // nanti bisa membaca GPS.
            LocationService.ensureOnline(getApplicationContext());
        }
    }

    // ---------------------------------------------------------------- shift (peran berabsen, port refreshOperatorBar)

    private boolean locationAsked;
    private long lastMeRefreshAt;

    /** Shift terbuka: pengingat jam kerja, popup apresiasi jam terpenuhi, pelacakan lokasi (beranda lama). */
    private void runShiftDuties() {
        if (!snap.loaded || !ms.tracksAttendance || !snap.shiftOpen || ms.uid <= 0) return;
        WorkHoursReminder.schedule(getApplicationContext(), ms.uid);
        safe("workMet", () -> MainActivity.maybeShowWorkHoursAppreciation(this, settingsDao, ms.uid));
        ensureLocationTracking();
    }

    /** Lacak lokasi staf selama shift; minta izin sekali bila perlu (ensureLocationTracking lama). */
    private void ensureLocationTracking() {
        SyncSettings cfg = new SyncSettings(settingsDao);
        if (!cfg.isEnrolled() || !cfg.isLocationTrackingEnabled()) return;
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION)
                == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            LocationService.start(this);
        } else if (!locationAsked) {
            locationAsked = true;
            androidx.core.app.ActivityCompat.requestPermissions(this,
                    new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION}, REQ_LOCATION);
        }
    }

    /** Marketing: status "Delivery" HP lewat /api/me bisa tertunda — segarkan maks. 1x/menit (beranda lama). */
    private void maybeRefreshMe() {
        if (!ms.marketing || System.currentTimeMillis() - lastMeRefreshAt <= 60_000L) return;
        final SyncSettings cfg = new SyncSettings(settingsDao);
        if (!cfg.isEnrolled()) return;
        lastMeRefreshAt = System.currentTimeMillis();
        final boolean before = cfg.isDeliveryDevice();
        com.crowja.damiupos.sync.OnlineTasks.forceConfigRefreshNow(getApplicationContext(), () -> {
            boolean after = new SyncSettings(settingsDao).isDeliveryDevice();
            if (after != before) handler.post(() -> {
                if (!isFinishing() && !isDestroyed()) loadSnapshot();
            });
        });
    }

    private void doIstirahat() {
        com.crowja.damiupos.util.ShiftActions.confirmBreak(this, settingsDao);
    }

    /**
     * Pulang: pemeriksaan antrean + dialog jam kerja dijalankan DI SINI (sama dengan beranda lama), lalu
     * selfie + catat OUT diserahkan ke MainActivity (EXTRA_AUTO_CLOCKOUT → startSelfieThenClockOut).
     */
    private void doPulang() {
        MainActivity.warnIncompleteDeliveryThenConfirmPulang(this, settingsDao, transactionDao, () -> {
            startSafely(new Intent(this, MainActivity.class).putExtra(MainActivity.EXTRA_AUTO_CLOCKOUT, true));
            finish();
        });
    }

    // ---------------------------------------------------------------- Pengisian Live (port apa adanya)

    /**
     * Beralih ke Pengisian Live sementara Antrean Saya belum kosong: (1) ingatkan menekan Selesai pada antrean
     * yang SUDAH diantar → "OK, SUDAH SEMUA"; (2) konfirmasi sisa antrean dialihkan ke perangkat lain →
     * "YA, ALIHKAN". Baru setelah itu layar Pengisian dibuka.
     */
    private void openPengisianLive() {
        int pending = 0;
        try {
            pending = transactionDao.countDeliveryQueue();
        } catch (Exception ignored) {
        }
        if (pending <= 0) {
            startActivity(new Intent(this, PengisianActivity.class));
            return;
        }
        final int n = pending;
        new AlertDialog.Builder(this)
                .setTitle("Masih Ada " + n + " Antrean")
                .setMessage("Apakah kamu akan mengirim kembali setelah mengisi?" + NL + NL
                        + "YA: Pengisian dibuka 1 jam. Antrean tetap milikmu (masih bisa diambil alih), antrean baru tetap bisa "
                        + "masuk, dan Pengisian otomatis tertutup saat waktunya habis." + NL + NL
                        + "TIDAK: antrean dilepas dan Pengisian dibuka tanpa batas waktu.")
                .setPositiveButton("Ya, Kirim Kembali", (d, w) -> openPengisianWillReturn())
                .setNegativeButton("Tidak", (d, w) -> confirmReleaseThenPengisian(n))
                .setNeutralButton("Batal", null)
                .show();
    }

    /** Pengisian 1 jam: server diberi tahu lewat latar; layar tetap dibuka walau jaringan sedang buruk. */
    private void openPengisianWillReturn() {
        final SyncSettings cfg = new SyncSettings(settingsDao);
        final Context app = getApplicationContext();
        if (cfg.isEnrolled()) {
            new Thread(() -> {
                try {
                    new SyncApi(cfg).fillReturn();
                } catch (Exception e) {
                    runOnUiThread(() -> Toast.makeText(app, "Server belum menerima: antrean baru bisa tertahan selama kamu mengisi.", Toast.LENGTH_LONG).show());
                }
            }).start();
        }
        startActivity(new Intent(this, PengisianActivity.class)
                .putExtra(PengisianActivity.EXTRA_RETURN_UNTIL, System.currentTimeMillis() + 3600000L));
    }

    private void confirmReleaseThenPengisian(int n) {
        new AlertDialog.Builder(this)
                .setCancelable(false)
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setTitle("Masih Ada " + n + " Antrean Belum Selesai")
                .setMessage("Sebelum beralih ke Pengisian, pastikan semua antrean yang SUDAH kamu antar sudah ditekan "
                        + "✓ Selesai di Antrian Delivery." + NL + NL + "Sudah semua?")
                .setPositiveButton("OK, SUDAH SEMUA", (d, w) -> confirmHandOverQueueThenPengisian())
                .setNeutralButton("Buka Antrian Delivery", (d, w) -> startActivity(new Intent(this, DeliveryQueueActivity.class)))
                .setNegativeButton("Batal", null)
                .show();
    }

    private void confirmHandOverQueueThenPengisian() {
        final List<Transaction> rest = new ArrayList<>();
        try {
            for (Transaction t : transactionDao.getDeliveryQueue()) {
                if (t != null && !t.isOpenDispatch()) rest.add(t);
            }
        } catch (Exception ignored) {
        }
        if (rest.isEmpty()) {
            startActivity(new Intent(this, PengisianActivity.class));
            return;
        }
        StringBuilder sb = new StringBuilder(rest.size() + " antrean yang belum Selesai akan DIALIHKAN ke perangkat lain:" + NL + NL);
        for (int i = 0; i < rest.size() && i < 8; i++) {
            sb.append("• ").append(rest.get(i).getCustomerName() != null ? rest.get(i).getCustomerName() : "Pelanggan")
                    .append(" (").append(rest.get(i).getJumlahGalon()).append(" gal)").append(NL);
        }
        if (rest.size() > 8) sb.append("• +").append(rest.size() - 8).append(" lainnya").append(NL);
        sb.append(NL).append("Dialihkan seketika sesuai prioritas perangkat penugasan: Aturan Perangkat → pemilik wilayah (area primer) yang sedang bertugas → perangkat bertugas yang memegang wilayah itu sebagai area sekunder → perangkat bertugas dengan antrean paling ringan. Bila tak ada yang bertugas, jadi Pesanan Terbuka.");
        new AlertDialog.Builder(this)
                .setCancelable(false)
                .setTitle("Alihkan Antrean?")
                .setMessage(sb.toString())
                .setPositiveButton("YA, ALIHKAN", (d, w) -> handOverQueueThenPengisian(rest))
                .setNegativeButton("Batal", null)
                .show();
    }

    private void handOverQueueThenPengisian(List<Transaction> rest) {
        final SyncSettings cfg = new SyncSettings(settingsDao);
        if (!cfg.isEnrolled()) {
            Toast.makeText(this, "Perangkat belum terhubung ke server — antrean tidak bisa dialihkan.", Toast.LENGTH_LONG).show();
            return;
        }
        final android.app.ProgressDialog wait = new android.app.ProgressDialog(this);
        wait.setMessage("Mengalihkan " + rest.size() + " antrean…");
        wait.setCancelable(false);
        wait.show();
        new Thread(() -> {
            String err = null;
            JSONObject r = null;
            try {
                r = new SyncApi(cfg).handoverAll();
            } catch (SyncApi.SyncException se) {
                String m = se.serverMessage();
                err = m != null && !m.isEmpty() ? m : "Gagal mengalihkan (kode " + se.code + ").";
            } catch (Exception e) {
                err = "Gagal mengalihkan — periksa koneksi internet.";
            }
            final JSONObject fr = r;
            final String fe = err;
            runOnUiThread(() -> {
                try {
                    wait.dismiss();
                } catch (Exception ignored) {
                }
                if (isFinishing() || isDestroyed()) return;
                if (fe != null) {
                    new AlertDialog.Builder(this).setTitle("⚠️ Antrean Belum Dialihkan").setMessage(fe)
                            .setPositiveButton("OK", null).show();
                    return;
                }
                SyncScheduler.syncNow(getApplicationContext());
                StringBuilder msg = new StringBuilder(fr != null ? fr.optString("message", "Antrean dialihkan.") : "Antrean dialihkan.").append(NL);
                JSONArray it = fr != null ? fr.optJSONArray("items") : null;
                for (int i = 0; it != null && i < it.length() && i < 12; i++) {
                    JSONObject o = it.optJSONObject(i);
                    if (o != null) msg.append(NL).append("• ").append(o.optString("name")).append(" → ").append(o.optString("to"));
                }
                new AlertDialog.Builder(this).setTitle("✅ Antrean Dialihkan").setMessage(msg.toString())
                        .setCancelable(false)
                        .setPositiveButton("Buka Pengisian", (d, w) -> startActivity(new Intent(this, PengisianActivity.class)))
                        .show();
            });
        }).start();
    }

    // ================================================================== trend math (port refreshTrends)

    /** -1 = TURUN, 0 = STAGNAN, 1 = NAIK. Ambang ±5%. */
    private static int trendDir(double cur, double prev) {
        double base = Math.max(Math.abs(prev), 0.0001);
        double pct = (cur - prev) / base;
        if (pct > 0.05) return 1;
        if (pct < -0.05) return -1;
        return 0;
    }

    private void applyTrendChip(TextView tv, int dir, String avgText) {
        if (tv == null) return;
        String arrow = dir > 0 ? "▲" : dir < 0 ? "▼" : "▬";
        int bg = dir > 0 ? R.color.gj_primary_container : dir < 0 ? R.color.gj_danger_bg : R.color.gj_fill_subtle;
        int fg = dir > 0 ? R.color.gj_on_primary_container : dir < 0 ? R.color.gj_danger_text : R.color.gj_text_secondary;
        tv.setText(arrow + " " + avgText + "/hari");
        tv.setTextColor(col(fg));
        tv.setBackground(rounded(col(bg), 10));
        tv.setContentDescription((dir > 0 ? "Naik" : dir < 0 ? "Turun" : "Stabil") + ", rata-rata " + avgText
                + " per hari dibanding 30 hari sebelumnya");
        tv.setVisibility(View.VISIBLE);
    }

    private void setTrendNumber(TextView tv, double curAvg, double prevAvg, boolean signed) {
        String v = oneDecimal(curAvg);
        if (signed && curAvg > 0) v = "+" + v;
        applyTrendChip(tv, trendDir(curAvg, prevAvg), v);
    }

    private static String compactRupiah(double v) {
        double a = Math.abs(v);
        if (a >= 1_000_000_000d) return oneDecimal(v / 1_000_000_000d) + "M";
        if (a >= 1_000_000d) return oneDecimal(v / 1_000_000d) + "jt";
        if (a >= 1_000d) return oneDecimal(v / 1_000d) + "rb";
        return String.valueOf(Math.round(v));
    }

    private static String oneDecimal(double v) {
        return String.format(new Locale("id", "ID"), "%.1f", v);
    }

    // ================================================================== small helpers

    private static String fmtCount(int n) {
        return n > 99 ? "99+" : String.valueOf(n);
    }

    private int col(int res) {
        return ContextCompat.getColor(this, res);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    private int px(float v) {
        return Math.round(dp(v));
    }

    private GradientDrawable rounded(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private GradientDrawable oval(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        return g;
    }

    private static void startBlink(View view) {
        AlphaAnimation blink = new AlphaAnimation(1f, 0.3f);
        blink.setDuration(500);
        blink.setRepeatMode(Animation.REVERSE);
        blink.setRepeatCount(Animation.INFINITE);
        view.startAnimation(blink);
    }
}
