package com.crowja.damiupos;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.os.Build;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.view.LayoutInflater;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.crowja.damiupos.adapter.TransactionAdapter;
import com.crowja.damiupos.db.AttendanceDao;
import com.crowja.damiupos.db.CustomerDao;
import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.GalonStockDao;
import com.crowja.damiupos.db.OrderInboxDao;
import com.crowja.damiupos.db.SettingsDao;
import com.crowja.damiupos.db.TransactionDao;
import com.crowja.damiupos.model.Attendance;
import com.crowja.damiupos.model.OrderInbox;
import com.crowja.damiupos.model.Transaction;
import com.crowja.damiupos.wa.NotificationAlertHelper;
import com.crowja.damiupos.wa.ParsedOrder;
import com.crowja.damiupos.wa.WaListenerService;

import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    /** Intent extra dari LoginActivity: true kalau staf baru saja clock in →
     *  tampilkan info jumlah Transaksi Pending (popup + notifikasi). */
    public static final String EXTRA_JUST_CLOCKED_IN = "just_clocked_in";

    private TextView tvPendapatan, tvGalonTerjual, tvTransaksiHariIni;
    private TextView tvGalonBeredar, tvTotalPelanggan;
    private TextView tvPendapatanTrend, tvGalonTerjualTrend, tvTransaksiTrend;
    private TextView tvGalonBeredarTrend, tvTotalPelangganTrend;
    private TextView tvEmptyRecent;
    private RecyclerView rvRecentTransactions;
    private TransactionAdapter adapter;

    private CustomerDao customerDao;
    private TransactionDao transactionDao;
    private SettingsDao settingsDao;
    private GalonStockDao galonStockDao;
    private OrderInboxDao orderInboxDao;
    private com.google.android.material.button.MaterialButton btnFollowUp;
    private ColorStateList originalFollowUpTint;
    private TextView tvToolbarTitle, tvToolbarSubtitle;
    private Toolbar mainToolbar;
    private int originalToolbarColor;
    private static final String DEFAULT_TOOLBAR_SUBTITLE = "Point of Sales Khusus Depot Air Minum";

    /** Receiver untuk update toolbar saat ada pesanan baru dari WA. */
    private final BroadcastReceiver newOrderReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refreshOrderInboxBanner();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // First-run setup wizard
        settingsDao = new SettingsDao(DatabaseHelper.getInstance(this));
        if (!settingsDao.isWizardCompleted()) {
            startActivity(new Intent(this, WizardActivity.class));
            finish();
            return;
        }

        // Gate multi user & absensi: wajib login (clock in) sebelum pakai app.
        if (settingsDao.isMultiUserEnabled() && settingsDao.getCurrentUserId() <= 0) {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }

        setContentView(R.layout.activity_main);

        Toolbar toolbar = findViewById(R.id.toolbar);
        mainToolbar = toolbar;
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayShowTitleEnabled(false);
        }
        tvToolbarTitle = findViewById(R.id.tvToolbarTitle);
        tvToolbarSubtitle = findViewById(R.id.tvToolbarSubtitle);
        originalToolbarColor = getResources().getColor(R.color.primary);
        // Tap toolbar saat ada pesanan baru → akui (acknowledge) +
        // buka inbox; sound + blink stop.
        toolbar.setOnClickListener(v -> {
            if (orderInboxDao != null && orderInboxDao.countPending() > 0) {
                acknowledgeAlerts();
                startActivity(new Intent(this, OrderInboxActivity.class));
            }
        });

        DatabaseHelper dbHelper = DatabaseHelper.getInstance(this);
        customerDao = new CustomerDao(dbHelper);
        transactionDao = new TransactionDao(dbHelper);
        galonStockDao = new GalonStockDao(dbHelper);
        orderInboxDao = new OrderInboxDao(dbHelper);

        tvPendapatan = findViewById(R.id.tvPendapatan);
        tvGalonTerjual = findViewById(R.id.tvGalonTerjual);
        tvTransaksiHariIni = findViewById(R.id.tvTransaksiHariIni);
        tvGalonBeredar = findViewById(R.id.tvGalonBeredar);
        tvTotalPelanggan = findViewById(R.id.tvTotalPelanggan);
        tvPendapatanTrend = findViewById(R.id.tvPendapatanTrend);
        tvGalonTerjualTrend = findViewById(R.id.tvGalonTerjualTrend);
        tvTransaksiTrend = findViewById(R.id.tvTransaksiTrend);
        tvGalonBeredarTrend = findViewById(R.id.tvGalonBeredarTrend);
        tvTotalPelangganTrend = findViewById(R.id.tvTotalPelangganTrend);
        tvEmptyRecent = findViewById(R.id.tvEmptyRecent);
        rvRecentTransactions = findViewById(R.id.rvRecentTransactions);

        adapter = new TransactionAdapter(true);
        rvRecentTransactions.setLayoutManager(new LinearLayoutManager(this));
        rvRecentTransactions.setHasFixedSize(true);
        rvRecentTransactions.setAdapter(adapter);
        adapter.setOnItemClickListener(trx -> {
            Intent i = new Intent(this, ReceiptActivity.class);
            i.putExtra(ReceiptActivity.EXTRA_TRANSACTION_ID, trx.getId());
            startActivity(i);
        });

        // Quick action buttons
        findViewById(R.id.btnJualGalon).setOnClickListener(v -> {
            Intent intent = new Intent(this, TransactionActivity.class);
            intent.putExtra("type", Transaction.TYPE_JUAL);
            startActivity(intent);
        });

        findViewById(R.id.btnGalonKembali).setOnClickListener(v -> {
            Intent intent = new Intent(this, TransactionActivity.class);
            intent.putExtra("type", Transaction.TYPE_KEMBALI);
            startActivity(intent);
        });

        btnFollowUp = findViewById(R.id.btnFollowUp);
        originalFollowUpTint = btnFollowUp.getBackgroundTintList();
        btnFollowUp.setOnClickListener(v -> {
            btnFollowUp.clearAnimation();
            startActivity(new Intent(this, FollowUpActivity.class));
        });

        findViewById(R.id.btnLaporan).setOnClickListener(v -> {
            startActivity(new Intent(this, ReportActivity.class));
        });

        findViewById(R.id.btnExport).setOnClickListener(v -> {
            startActivity(new Intent(this, ExportActivity.class));
        });

        findViewById(R.id.btnReseller).setOnClickListener(v -> {
            startActivity(new Intent(this, ResellerListActivity.class));
        });

        findViewById(R.id.btnPengeluaran).setOnClickListener(v -> {
            startActivity(new Intent(this, ExpenseListActivity.class));
        });

        findViewById(R.id.btnPengaturan).setOnClickListener(v -> {
            startActivity(new Intent(this, SettingsActivity.class));
        });

        findViewById(R.id.btnAnalisa).setOnClickListener(v ->
                startActivity(new Intent(this, AnalyticsActivity.class)));

        // Tap card-card "hari ini" → Daftar Transaksi dengan filter Hari Ini
        View.OnClickListener todayListClick = v -> {
            Intent i = new Intent(this, TransactionListActivity.class);
            i.putExtra(TransactionListActivity.EXTRA_DATE_FILTER,
                    TransactionListActivity.DATE_FILTER_TODAY);
            startActivity(i);
        };
        View cardPendapatan = findViewById(R.id.cardPendapatan);
        if (cardPendapatan != null) cardPendapatan.setOnClickListener(todayListClick);
        View cardGalonTerjual = findViewById(R.id.cardGalonTerjual);
        if (cardGalonTerjual != null) cardGalonTerjual.setOnClickListener(todayListClick);
        View cardTransaksi = findViewById(R.id.cardTransaksi);
        if (cardTransaksi != null) cardTransaksi.setOnClickListener(todayListClick);

        // Card statistik menggantikan tile menu yang redundan:
        // Galon Beredar → Stok Galon, Total Pelanggan → daftar Pelanggan.
        View cardGalonBeredar = findViewById(R.id.cardGalonBeredar);
        if (cardGalonBeredar != null) {
            cardGalonBeredar.setOnClickListener(v -> {
                cardGalonBeredar.clearAnimation();
                startActivity(new Intent(this, GalonStockActivity.class));
            });
        }
        View cardTotalPelanggan = findViewById(R.id.cardTotalPelanggan);
        if (cardTotalPelanggan != null) {
            cardTotalPelanggan.setOnClickListener(v ->
                    startActivity(new Intent(this, CustomerListActivity.class)));
        }

        // Banner ad di dashboard (auto-hide saat Pro)
        android.view.ViewGroup adContainer = findViewById(R.id.adContainer);
        if (adContainer != null) {
            com.crowja.damiupos.ads.AdManager.getInstance(this).attachBanner(this, adContainer);
        }

        // Operator bar (multi user & absensi)
        View btnIstirahat = findViewById(R.id.btnIstirahat);
        View btnClockOut = findViewById(R.id.btnClockOut);
        View btnAdminLogout = findViewById(R.id.btnAdminLogout);
        if (btnIstirahat != null) btnIstirahat.setOnClickListener(v -> doIstirahat());
        // Pulang: ingatkan transaksi pending besok dulu → konfirmasi → selfie →
        // catat OUT → laporan + apresiasi.
        if (btnClockOut != null) btnClockOut.setOnClickListener(v -> remindPendingBeforePulang());
        // Admin: logout biasa (tanpa absensi/laporan).
        if (btnAdminLogout != null) btnAdminLogout.setOnClickListener(v -> doAdminLogout());

        // Menu Karyawan (absensi) — admin only.
        View cardKaryawan = findViewById(R.id.cardKaryawan);
        if (cardKaryawan != null) {
            cardKaryawan.setOnClickListener(v ->
                    startActivity(new Intent(this, UserListActivity.class)));
        }

        // Siapkan channel pengingat jam kerja + minta izin notifikasi (Android 13+).
        ensureNotificationAccess();

        // Staf baru clock in → beritahu jumlah Transaksi Pending (popup + notif).
        if (getIntent().getBooleanExtra(EXTRA_JUST_CLOCKED_IN, false)) {
            getIntent().removeExtra(EXTRA_JUST_CLOCKED_IN);   // sekali saja
            maybeNotifyPendingOnClockIn();
        }

        // Online: keep periodic sync armed + check for a newer app version on launch.
        com.crowja.damiupos.sync.SyncScheduler.schedulePeriodic(getApplicationContext());
        com.crowja.damiupos.sync.VersionUpdater.checkAndPrompt(this);
    }

    /**
     * Saat staf masuk kerja kembali: kalau ada Transaksi Pending, tampilkan
     * popup jumlahnya + notifikasi Android. No-op kalau tidak ada pending.
     */
    private void maybeNotifyPendingOnClockIn() {
        int count;
        try {
            count = new com.crowja.damiupos.db.PendingTransactionDao(
                    DatabaseHelper.getInstance(this)).countPending();
        } catch (Exception e) { return; }
        if (count <= 0) return;

        firePendingTrxNotification(count);

        if (isFinishing() || isDestroyed()) return;
        String first = settingsDao.getCurrentUserName();
        first = (first != null && !first.isEmpty()) ? first : "Kak";
        SpannableStringBuilder msg = new SpannableStringBuilder();
        msg.append("Selamat datang kembali, " + first + "!\n\n");
        appendStyled(msg, "Ada " + count + " transaksi pending",
                Color.parseColor("#D32F2F"), true, 1.2f);
        msg.append(" yang menunggu untuk dieksekusi hari ini. Ketuk pill merah di "
                + "tombol \"Jual Air Minum\" untuk membukanya.");
        new AlertDialog.Builder(this)
                .setTitle("📋 Transaksi Pending")
                .setMessage(msg)
                .setPositiveButton("Lihat Sekarang", (d, w) ->
                        startActivity(new Intent(this, PendingTransactionListActivity.class)))
                .setNegativeButton("Nanti", null)
                .show();
    }

    /** Notifikasi Android: jumlah Transaksi Pending saat clock in. */
    private void firePendingTrxNotification(int count) {
        String channelId = "pending_trx";
        android.app.NotificationManager nm =
                (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && nm.getNotificationChannel(channelId) == null) {
            android.app.NotificationChannel ch = new android.app.NotificationChannel(
                    channelId, "Transaksi Pending",
                    android.app.NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription("Pengingat transaksi pending saat mulai bekerja");
            nm.createNotificationChannel(ch);
        }
        Intent open = new Intent(this, PendingTransactionListActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int piFlags = android.app.PendingIntent.FLAG_UPDATE_CURRENT
                | android.app.PendingIntent.FLAG_IMMUTABLE;
        android.app.PendingIntent pi = android.app.PendingIntent.getActivity(this, 0, open, piFlags);
        androidx.core.app.NotificationCompat.Builder b =
                new androidx.core.app.NotificationCompat.Builder(this, channelId)
                        .setSmallIcon(android.R.drawable.ic_menu_recent_history)
                        .setContentTitle("Ada " + count + " transaksi pending")
                        .setContentText("Ketuk untuk melihat & eksekusi transaksi yang tertunda.")
                        .setPriority(androidx.core.app.NotificationCompat.PRIORITY_DEFAULT)
                        .setCategory(androidx.core.app.NotificationCompat.CATEGORY_REMINDER)
                        .setContentIntent(pi)
                        .setAutoCancel(true);
        try {
            androidx.core.app.NotificationManagerCompat.from(this).notify(7821, b.build());
        } catch (SecurityException ignored) {
            // POST_NOTIFICATIONS belum diberikan — abaikan.
        }
    }

    private static final int REQ_POST_NOTIF = 9311;

    /** Buat channel pengingat jam kerja + minta izin POST_NOTIFICATIONS (API 33+). */
    private void ensureNotificationAccess() {
        WorkHoursReminder.ensureChannel(this);
        if (Build.VERSION.SDK_INT >= 33
                && androidx.core.content.ContextCompat.checkSelfPermission(this,
                        android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            androidx.core.app.ActivityCompat.requestPermissions(this,
                    new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, REQ_POST_NOTIF);
        }
    }

    private static final int REQ_LOCATION = 9312;
    private boolean locationAsked;

    /** Start staff location tracking while on shift; request the permission once if needed. */
    private void ensureLocationTracking() {
        com.crowja.damiupos.sync.SyncSettings cfg = new com.crowja.damiupos.sync.SyncSettings(
                new SettingsDao(DatabaseHelper.getInstance(this)));
        if (!cfg.isEnrolled() || !cfg.isLocationTrackingEnabled()) return;
        if (androidx.core.content.ContextCompat.checkSelfPermission(this,
                android.Manifest.permission.ACCESS_FINE_LOCATION)
                == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            LocationService.start(this);
        } else if (!locationAsked) {
            locationAsked = true;
            androidx.core.app.ActivityCompat.requestPermissions(this,
                    new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION}, REQ_LOCATION);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_LOCATION && grantResults.length > 0
                && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            LocationService.start(this);
        }
    }

    /**
     * Sebelum Pulang: popup pengingat (tanda seru) — apakah ada transaksi/pesanan
     * yang direncanakan untuk BESOK? Kalau ya, buat Transaksi Pending sekarang
     * agar tidak terlupa; kalau tidak, lanjut proses Pulang.
     */
    private void remindPendingBeforePulang() {
        int count = 0;
        try {
            count = new com.crowja.damiupos.db.PendingTransactionDao(
                    DatabaseHelper.getInstance(this)).countPending();
        } catch (Exception ignored) {}

        StringBuilder msg = new StringBuilder();
        msg.append("Apakah ada transaksi/pesanan yang direncanakan untuk BESOK?\n\n");
        msg.append("Kalau ada, buat sebagai Transaksi Pending sekarang agar besok "
                + "tidak terlupa.");
        if (count > 0) {
            msg.append("\n\nSaat ini sudah ada ").append(count)
                    .append(" transaksi pending tercatat.");
        }
        new AlertDialog.Builder(this)
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setTitle("⚠ Cek Transaksi Pending")
                .setMessage(msg.toString())
                .setCancelable(false)
                // Buat pending dulu → buka Jual Air (pakai tombol "Simpan sebagai
                // Pending" di sana). Setelah selesai, tekan Pulang lagi.
                .setPositiveButton("Buat Pending", (d, w) -> {
                    Intent i = new Intent(this, TransactionActivity.class);
                    i.putExtra("type", Transaction.TYPE_JUAL);
                    startActivity(i);
                })
                .setNegativeButton("Lanjut Pulang", (d, w) -> confirmPulang())
                .setNeutralButton("Batal", null)
                .show();
    }

    /** Konfirmasi sebelum Pulang (clock out) — gate selfie + pencatatan OUT. */
    private void confirmPulang() {
        long uid = settingsDao.getCurrentUserId();
        StringBuilder msg = new StringBuilder("Akhiri shift dan catat jam pulang sekarang? "
                + "Laporan shift akan dikirim ke admin.");
        try {
            String[] period = AttendanceRecap.currentPeriod(settingsDao.getPayrollCutoffDay());
            AttendanceRecap.PeriodSummary ps = AttendanceRecap.computePeriodSummary(
                    DatabaseHelper.getInstance(this), uid, period[0], period[1],
                    settingsDao.getDailyNormalHours());
            msg.append("\n\nPeriode cut-off (").append(period[0]).append(" s/d ")
                    .append(period[1]).append("):\n");
            msg.append("Kerja ").append(fmtHours(ps.totalHours))
                    .append(" dari ideal ").append(fmtHours(ps.requiredHours)).append(".\n");
            double diff = ps.diffHours;
            if (diff >= 0.05) {
                msg.append("Status: LEBIH ").append(fmtHours(diff)).append(" — mantap! 🎉");
            } else if (diff <= -0.05) {
                msg.append("Status: KURANG ").append(fmtHours(-diff)).append(".");
            } else {
                msg.append("Status: CUKUP ✓");
            }
        } catch (Exception ignored) {}
        new AlertDialog.Builder(this)
                .setTitle("Konfirmasi Pulang")
                .setMessage(msg.toString())
                .setPositiveButton("Ya, Pulang", (d, w) -> startSelfieThenClockOut())
                .setNegativeButton("Batal", null)
                .show();
    }

    /** Format jam desimal → "Xj Ym" (pakai formatter shift). */
    private String fmtHours(double hours) {
        return ShiftReporter.formatDuration(Math.round(hours * 3600000.0));
    }

    /**
     * Update bar operator: tampil saat multi user aktif & sudah login.
     * Staf → tombol Istirahat + Pulang (+ info shift). Admin → tanpa absensi,
     * hanya tombol Logout, + tile Karyawan.
     */
    private void refreshOperatorBar() {
        View card = findViewById(R.id.cardOperator);
        if (card == null) return;
        long uid = settingsDao.getCurrentUserId();
        boolean show = settingsDao.isMultiUserEnabled() && uid > 0;
        card.setVisibility(show ? View.VISIBLE : View.GONE);

        boolean isAdmin = false;
        boolean isViewer = false;
        if (show) {
            com.crowja.damiupos.model.User cur =
                    new com.crowja.damiupos.db.UserDao(DatabaseHelper.getInstance(this)).getById(uid);
            isAdmin = cur != null && cur.isAdmin();
            isViewer = cur != null && cur.isViewer();
        }
        boolean tracksAttendance = show && !isAdmin && !isViewer; // hanya staf yang absen

        // Menu Karyawan hanya untuk admin yang sedang login.
        View cardKaryawan = findViewById(R.id.cardKaryawan);
        if (cardKaryawan != null) cardKaryawan.setVisibility(isAdmin ? View.VISIBLE : View.GONE);

        // Viewer tidak boleh buat transaksi → sembunyikan aksi cepat Jual/Kembali.
        View qaJual = findViewById(R.id.btnJualGalon);
        View qaKembali = findViewById(R.id.btnGalonKembali);
        if (qaJual != null) qaJual.setVisibility(isViewer ? View.GONE : View.VISIBLE);
        if (qaKembali != null) qaKembali.setVisibility(isViewer ? View.GONE : View.VISIBLE);

        if (!show) return;

        View btnIstirahat = findViewById(R.id.btnIstirahat);
        View btnClockOut = findViewById(R.id.btnClockOut);
        View btnAdminLogout = findViewById(R.id.btnAdminLogout);
        TextView tvName = findViewById(R.id.tvOperatorName);
        TextView tvShift = findViewById(R.id.tvOperatorShift);
        tvName.setText(settingsDao.getCurrentUserName());

        if (!tracksAttendance) {
            // Admin & Viewer: tanpa absensi, hanya tombol Logout.
            if (btnIstirahat != null) btnIstirahat.setVisibility(View.GONE);
            if (btnClockOut != null) btnClockOut.setVisibility(View.GONE);
            if (btnAdminLogout != null) btnAdminLogout.setVisibility(View.VISIBLE);
            tvShift.setText(isViewer ? "Viewer" : "Admin");
        } else {
            if (btnIstirahat != null) btnIstirahat.setVisibility(View.VISIBLE);
            if (btnClockOut != null) btnClockOut.setVisibility(View.VISIBLE);
            if (btnAdminLogout != null) btnAdminLogout.setVisibility(View.GONE);
            try {
                ShiftReporter.Shift s = ShiftReporter.computeShift(
                        DatabaseHelper.getInstance(this), uid);
                String info = "Kerja " + ShiftReporter.formatDuration(s.workMillis);
                if (s.breakCount > 0) info += " • Istirahat " + s.breakCount + "x";
                // Akumulasi jam kerja periode cut-off (elapsed / ideal).
                try {
                    String[] period = AttendanceRecap.currentPeriod(settingsDao.getPayrollCutoffDay());
                    AttendanceRecap.PeriodSummary ps = AttendanceRecap.computePeriodSummary(
                            DatabaseHelper.getInstance(this), uid, period[0], period[1],
                            settingsDao.getDailyNormalHours());
                    info += "\n" + fmtHours(ps.totalHours) + " / "
                            + fmtHours(ps.requiredHours);
                } catch (Exception ignored) {}
                tvShift.setText(info);
                // Self-heal: pastikan pengingat "jam kerja terpenuhi" terjadwal
                // selama shift masih terbuka (no-op kalau target sudah tercapai).
                if (s.clockIn != null) {
                    WorkHoursReminder.schedule(getApplicationContext(), uid);
                    maybeShowWorkHoursAppreciation(uid);
                    // Lacak lokasi staff selama shift berjalan (no-op kalau izin/
                    // pelacakan dimatikan). Dihentikan saat Istirahat/Pulang.
                    ensureLocationTracking();
                }
            } catch (Exception e) {
                tvShift.setText("Sedang bekerja");
            }
        }
    }

    private Ringtone appreciationRingtone;

    /**
     * Popup apresiasi (sekali per periode cut-off per staf) begitu akumulasi jam
     * kerja PERIODE sudah memenuhi target (hari kerja ideal × jam ideal/hari).
     * Tombol "OK" + suara nyaring. Pendamping notifikasi Android.
     */
    private void maybeShowWorkHoursAppreciation(long uid) {
        String[] period = AttendanceRecap.currentPeriod(settingsDao.getPayrollCutoffDay());
        AttendanceRecap.PeriodSummary ps = AttendanceRecap.computePeriodSummary(
                DatabaseHelper.getInstance(this), uid, period[0], period[1],
                settingsDao.getDailyNormalHours());
        if (ps.requiredHours <= 0 || ps.totalHours < ps.requiredHours) return;
        String guardKey = "work_met_popup_" + uid;     // sekali per periode cut-off
        if (period[1].equals(settingsDao.get(guardKey, ""))) return;
        if (isFinishing() || isDestroyed()) return;
        settingsDao.set(guardKey, period[1]);

        String uname = settingsDao.getCurrentUserName();
        String first = (uname != null && !uname.isEmpty()) ? uname : "Kak";
        SpannableStringBuilder msg = new SpannableStringBuilder();
        msg.append("Hebat, " + first + "! Jam kerja periode cut-off ini sudah terpenuhi.\n\n");
        appendStyled(msg, "Total periode: " + fmtHours(ps.totalHours)
                        + " / target " + fmtHours(ps.requiredHours),
                Color.parseColor("#1565C0"), true, 1.15f);
        msg.append("\n\nTerima kasih atas dedikasimu. 🎉");

        playLoudChime();
        new AlertDialog.Builder(this)
                .setTitle("🎉 Jam Kerja Terpenuhi!")
                .setMessage(msg)
                .setCancelable(false)
                .setPositiveButton("OK", (d, w) -> stopChime())
                .setOnDismissListener(d -> stopChime())
                .show();
    }

    /** Mainkan nada nyaring (stream alarm) sekali. */
    private void playLoudChime() {
        try {
            Uri snd = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (snd == null) snd = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            Ringtone rt = RingtoneManager.getRingtone(getApplicationContext(), snd);
            if (rt == null) return;
            rt.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            appreciationRingtone = rt;
            rt.play();
        } catch (Exception ignored) {}
    }

    private void stopChime() {
        try {
            if (appreciationRingtone != null && appreciationRingtone.isPlaying()) {
                appreciationRingtone.stop();
            }
        } catch (Exception ignored) {}
    }

    /**
     * Tombol Istirahat: catat event BREAK, lepas sesi (current user → 0),
     * lalu kembali ke layar login. App jadi idle — wajib clock in lagi.
     */
    private void doIstirahat() {
        long uid = settingsDao.getCurrentUserId();
        if (uid <= 0) return;
        new AlertDialog.Builder(this)
                .setTitle("Istirahat?")
                .setMessage("Aplikasi akan terkunci. Anda perlu clock in lagi (PIN) "
                        + "untuk melanjutkan bekerja.")
                .setPositiveButton("Ya, Istirahat", (d, w) -> {
                    new AttendanceDao(DatabaseHelper.getInstance(this))
                            .log(uid, Attendance.EVENT_BREAK);
                    // Pause pengingat jam kerja — di-rearm saat clock in lagi.
                    WorkHoursReminder.cancel(getApplicationContext(), uid);
                    LocationService.stop(getApplicationContext());
                    settingsDao.clearCurrentUser();
                    Intent i = new Intent(this, LoginActivity.class);
                    i.putExtra(LoginActivity.EXTRA_FROM_BREAK, true);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(i);
                    finish();
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    private static final int REQ_SELFIE_LOGOUT = 702;
    private long pendingLogoutUid;
    private String pendingLogoutName;

    /** Tombol Pulang: ambil selfie wajah dulu, baru proses clock out. */
    private void startSelfieThenClockOut() {
        long uid = settingsDao.getCurrentUserId();
        if (uid <= 0) return;
        pendingLogoutUid = uid;
        pendingLogoutName = settingsDao.getCurrentUserName();
        Intent cam = new Intent(this, CameraCaptureActivity.class);
        cam.putExtra(CameraCaptureActivity.EXTRA_LABEL, "Pulang");
        startActivityForResult(cam, REQ_SELFIE_LOGOUT);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_SELFIE_LOGOUT) {
            // Batal eksplisit di peringatan lokasi → batalkan pulang, shift jalan terus.
            if (data != null && data.getBooleanExtra(
                    CameraCaptureActivity.EXTRA_USER_CANCELLED, false)) {
                pendingLogoutUid = 0;
                pendingLogoutName = null;
                return;
            }
            String photo = data != null
                    ? data.getStringExtra(CameraCaptureActivity.EXTRA_PHOTO_PATH) : null;
            finishClockOut(photo);
        }
    }

    /**
     * Selesaikan Pulang: catat OUT (+foto), kirim laporan shift (teks + foto
     * login & pulang) ke email admin, rekap bulanan kalau cut-off, lalu login.
     */
    private void finishClockOut(String logoutPhoto) {
        long uid = pendingLogoutUid > 0 ? pendingLogoutUid : settingsDao.getCurrentUserId();
        String uname = pendingLogoutName != null ? pendingLogoutName : settingsDao.getCurrentUserName();
        if (uid <= 0) return;
        DatabaseHelper dbHelper = DatabaseHelper.getInstance(this);
        com.crowja.damiupos.db.AttendanceDao attDao = new AttendanceDao(dbHelper);

        // Guard anti-double-OUT: kalau shift sudah ditutup (mis. popup apresiasi
        // hilang karena rotasi/destroy lalu user menekan Pulang lagi), jangan
        // catat OUT kedua / kirim email kosong — langsung ke login saja.
        Attendance last = attDao.getLastEvent(uid);
        if (last != null && Attendance.EVENT_OUT.equals(last.getEvent())) {
            proceedToLoginAfterClockOut();
            return;
        }

        // Foto login diambil SEBELUM OUT (setelah OUT, shift berjalan tertutup).
        String loginPhoto = attDao.getCurrentShiftLoginPhoto(uid);
        String summary = ShiftReporter.buildSummaryText(this, dbHelper, uid, uname);
        // "Jam kerja hari ini" untuk popup apresiasi — dibatasi tanggal hari ini
        // (akurat walau shift sempat dibiarkan terbuka lintas hari).
        long workMs = ShiftReporter.workedMillisToday(dbHelper, uid);

        // Catat OUT + foto pulang.
        attDao.log(uid, Attendance.EVENT_OUT, logoutPhoto);
        // Shift selesai → batalkan pengingat jam kerja + lepas sesi SEGERA
        // (sinkron). Kalau popup apresiasi hilang karena rotasi/destroy, gate
        // onCreate tetap mengarahkan ke login (tidak terjebak masih "login").
        WorkHoursReminder.cancel(getApplicationContext(), uid);
        LocationService.stop(getApplicationContext());
        pendingLogoutUid = 0;
        pendingLogoutName = null;
        settingsDao.clearCurrentUser();

        // Rekap pekanan & bulanan (cut-off) yang due di-enqueue & dikirim lewat
        // progress window di bawah (bersama laporan shift) — bukan async lagi,
        // supaya user lihat progress & tetap standby sampai semua terkirim.

        // Kirim laporan shift (teks + foto login & pulang) ke email admin.
        if (settingsDao.isShiftEmailConfigured()) {
            String depot = settingsDao.getDepotName();
            if (depot == null || depot.isEmpty()) depot = "DAMIU POS";
            String stamp = new java.text.SimpleDateFormat("dd MMM yyyy HH:mm",
                    new Locale("id", "ID")).format(new java.util.Date());
            String subject = "Laporan Shift - " + depot + " - " + uname + " - " + stamp;
            String body = summary
                    + "\n\nFoto wajah saat Clock In & Pulang terlampir."
                    + "\nDikirim otomatis oleh DAMIU POS.";
            java.util.List<java.io.File> atts = new java.util.ArrayList<>();
            addIfExists(atts, loginPhoto);
            addIfExists(atts, logoutPhoto);
            // Write-ahead ke antrian, lalu kirim dengan progress bar — user
            // diminta tetap standby sampai semua laporan terkirim.
            ShiftEmailSender.enqueue(getApplicationContext(), "Laporan Shift",
                    subject, body, atts, null);
            sendReportsWithProgressThenLogout(uid, uname, workMs);
        } else {
            Toast.makeText(this,
                    "Email/SMTP admin belum diatur — laporan tidak terkirim",
                    Toast.LENGTH_LONG).show();
            showAppreciationThenLogout(uname, workMs, 0);
        }
    }

    /**
     * Progress bar pengiriman laporan (shift + laporan lain di antrian) saat
     * Pulang. Setelah selesai (sukses/gagal), lanjut ke popup apresiasi lalu
     * login. Dialog tidak bisa dibatalkan supaya user menunggu hingga selesai.
     */
    private void sendReportsWithProgressThenLogout(long uid, String uname, long workMs) {
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_sending_report, null);
        final android.widget.ProgressBar pb = view.findViewById(R.id.pbSending);
        final TextView status = view.findViewById(R.id.tvSendingStatus);
        status.setText("Menyiapkan laporan…");
        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setView(view)
                .setCancelable(false)
                .create();
        dlg.show();

        final Context app = getApplicationContext();
        final DatabaseHelper db = DatabaseHelper.getInstance(this);
        // Bangun + enqueue rekap pekanan/bulanan + slip gaji (di hari cut-off) yang
        // due (build berat: ZIP/PDF/XLSX → background thread), lalu kirim SEMUA
        // lewat progress window. Marker di-set oleh enqueue; antrian retry jika gagal.
        new Thread(() -> {
            try {
                AttendanceRecap.enqueueDueWeeklyRecap(app, db);
                AttendanceRecap.enqueueDueRecap(app, db);
                AttendanceRecap.enqueueDuePayslip(app, db, uid, uname);
            } catch (Throwable ignored) {}
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                ShiftEmailSender.flushAllWithProgress(app,
                        new ShiftEmailSender.ProgressCallback() {
                            @Override
                            public void onProgress(int current, int total, String label) {
                                if (isFinishing() || isDestroyed()) return;
                                pb.setMax(Math.max(total, 1));
                                pb.setProgress(current);
                                status.setText("Mengirim " + current + " dari " + total
                                        + " — " + label);
                            }
                            @Override
                            public void onComplete(int sent, int remaining) {
                                try { dlg.dismiss(); } catch (Throwable ignored) {}
                                // Kalau activity sudah hilang (mis. rotasi), sesi sudah
                                // di-clear → gate onCreate mengarahkan ke login.
                                if (isFinishing() || isDestroyed()) return;
                                showAppreciationThenLogout(uname, workMs, remaining);
                            }
                        });
            });
        }, "report-prep").start();
    }

    /**
     * Tampilkan kata-kata apresiasi + total jam kerja hari ini, lalu lanjutkan
     * ke layar login setelah user menekan OK. Dialog tidak bisa dibatalkan
     * supaya clock out selalu tuntas.
     */
    private void showAppreciationThenLogout(String uname, long workMs, int remaining) {
        String first = (uname != null && !uname.isEmpty()) ? uname : "Kak";
        SpannableStringBuilder msg = new SpannableStringBuilder();
        msg.append("Terima kasih, " + first + "! Kerja kerasmu hari ini sangat berarti.\n\n");
        appendStyled(msg, "Total kerja hari ini: " + ShiftReporter.formatDurationLong(workMs),
                Color.parseColor("#1565C0"), true, 1.15f);
        // Bonus penjualan: hari ini + akumulasi periode cut-off (kalau diaktifkan).
        if (settingsDao.isSalesBonusEnabled()) {
            try {
                NumberFormat bnf = NumberFormat.getInstance(new Locale("id", "ID"));
                String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
                String[] period = AttendanceRecap.currentPeriod(settingsDao.getPayrollCutoffDay());
                int galonToday = (int) transactionDao.getSummaryByDateRange(today, today)[1];
                int galonPeriod = (int) transactionDao.getSummaryByDateRange(period[0], today)[1];
                double bonusToday = ShiftReporter.salesBonusValue(settingsDao, galonToday);
                double bonusPeriod = ShiftReporter.salesBonusValue(settingsDao, galonPeriod);
                msg.append("\n\n");
                appendStyled(msg, "Bonus penjualan hari ini: Rp " + bnf.format(Math.round(bonusToday))
                                + " (" + galonToday + " galon)",
                        Color.parseColor("#2E7D32"), true, 1.05f);
                msg.append("\n");
                appendStyled(msg, "Akumulasi bonus periode ini: Rp " + bnf.format(Math.round(bonusPeriod))
                                + " (" + galonPeriod + " galon)",
                        Color.parseColor("#2E7D32"), false, 1f);
            } catch (Exception ignored) {}
        }
        if (remaining > 0) {
            msg.append("\n\n");
            appendStyled(msg, "⚠ " + remaining + " laporan belum terkirim — akan dicoba "
                            + "lagi otomatis saat login berikutnya.",
                    Color.parseColor("#E65100"), false, 1f);
        }
        msg.append("\n\nIstirahat yang cukup, sampai jumpa besok! 👋");
        new AlertDialog.Builder(this)
                .setTitle("👋 Selamat Pulang!")
                .setMessage(msg)
                .setCancelable(false)
                .setPositiveButton("OK", (d, w) -> proceedToLoginAfterClockOut())
                .show();
    }

    /** Lepas sesi + kembali ke layar login (dipanggil setelah popup apresiasi). */
    private void proceedToLoginAfterClockOut() {
        pendingLogoutUid = 0;
        pendingLogoutName = null;
        settingsDao.clearCurrentUser();
        Intent i = new Intent(this, LoginActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(i);
        finish();
    }

    /** Append teks berwarna/bold/diperbesar ke SpannableStringBuilder. */
    private void appendStyled(SpannableStringBuilder sb, String text,
                              int color, boolean bold, float sizeMul) {
        int start = sb.length();
        sb.append(text);
        int end = sb.length();
        sb.setSpan(new ForegroundColorSpan(color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (bold) {
            sb.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), start, end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (sizeMul != 1f) {
            sb.setSpan(new RelativeSizeSpan(sizeMul), start, end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    private static void addIfExists(java.util.List<java.io.File> list, String path) {
        if (path == null || path.isEmpty()) return;
        java.io.File f = new java.io.File(path);
        if (f.exists()) list.add(f);
    }

    /** Admin: logout biasa tanpa absensi/laporan. */
    private void doAdminLogout() {
        settingsDao.clearCurrentUser();
        Intent i = new Intent(this, LoginActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(i);
        finish();
    }


    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        // Ikon amplop (inbox pesanan WA) hanya tampil kalau auto-detect aktif.
        MenuItem inbox = menu.findItem(R.id.action_inbox);
        if (inbox != null) inbox.setVisible(settingsDao.isWaAutoDetectEnabled());
        // "Slip Gaji Saya" hanya untuk staf yang sedang login (yang punya absensi).
        MenuItem slip = menu.findItem(R.id.action_payslip);
        if (slip != null) {
            boolean staff = false;
            long uid = settingsDao.getCurrentUserId();
            if (settingsDao.isMultiUserEnabled() && uid > 0) {
                com.crowja.damiupos.model.User u = new com.crowja.damiupos.db.UserDao(
                        DatabaseHelper.getInstance(this)).getById(uid);
                staff = u != null && u.tracksAttendance();
            }
            slip.setVisible(staff);
        }
        // "Kirim Pending Laporan" hanya muncul kalau ada laporan gagal di antrian.
        MenuItem pend = menu.findItem(R.id.action_send_pending);
        if (pend != null) {
            int n = 0;
            try { n = ShiftEmailSender.listPending(this).size(); } catch (Throwable ignored) {}
            pend.setTitle(n > 0 ? "Kirim Pending Laporan (" + n + ")" : "Kirim Pending Laporan");
            pend.setVisible(n > 0);
        }
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.action_info) {
            showAboutDialog();
            return true;
        }
        if (item.getItemId() == R.id.action_inbox) {
            // Buka inbox + acknowledge supaya alert sound stop
            acknowledgeAlerts();
            startActivity(new Intent(this, OrderInboxActivity.class));
            return true;
        }
        if (item.getItemId() == R.id.action_payslip) {
            exportMyPayslip();
            return true;
        }
        if (item.getItemId() == R.id.action_send_pending) {
            sendPendingReports();
            return true;
        }
        if (item.getItemId() == R.id.action_sync) {
            startActivity(new Intent(this, SyncSettingsActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /**
     * Staf export slip gaji-nya sendiri sebagai XLSX ter-password (password =
     * PIN admin). Pilih periode cut-off lalu bagikan.
     */
    private void exportMyPayslip() {
        long uid = settingsDao.getCurrentUserId();
        if (uid <= 0) return;
        final String uname = settingsDao.getCurrentUserName();
        final String adminPin = new com.crowja.damiupos.db.UserDao(
                DatabaseHelper.getInstance(this)).getPrimaryAdminPin();
        if (adminPin == null || adminPin.isEmpty()) {
            Toast.makeText(this, "PIN admin belum tersedia untuk mengunci file slip.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        int cutoff = settingsDao.getPayrollCutoffDay();
        final String[] cur = AttendanceRecap.monthlyPeriod(cutoff);
        final String[] prev = AttendanceRecap.previousPeriod(cutoff);
        String[] labels = {
                "Periode berjalan (" + cur[0] + " s/d " + cur[1] + ")",
                "Periode lalu (" + prev[0] + " s/d " + prev[1] + ")"
        };
        new AlertDialog.Builder(this)
                .setTitle("Slip Gaji Saya (XLSX terkunci)")
                .setItems(labels, (d, which) ->
                        doExportMyPayslip(uid, uname, adminPin, which == 0 ? cur : prev))
                .setNegativeButton("Batal", null)
                .show();
    }

    private void doExportMyPayslip(long uid, String uname, String pin, String[] period) {
        final Context app = getApplicationContext();
        final DatabaseHelper db = DatabaseHelper.getInstance(this);
        Toast.makeText(this, "Membuat slip (terkunci)…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            // ZIP ber-password (PIN admin) berisi XLSX biasa — diterima WA,
            // diekstrak pakai PIN, lalu XLSX terbuka di penampil apa pun.
            java.io.File f = PayslipXlsx.buildProtectedZip(app, db, uid, uname,
                    period[0], period[1], pin);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (f == null) {
                    Toast.makeText(this, "Gagal membuat slip gaji.", Toast.LENGTH_LONG).show();
                    return;
                }
                try {
                    Uri uri = androidx.core.content.FileProvider.getUriForFile(
                            this, getPackageName() + ".fileprovider", f);
                    String mime = "application/zip";
                    Intent i = new Intent(Intent.ACTION_SEND);
                    i.setType(mime);
                    i.putExtra(Intent.EXTRA_STREAM, uri);
                    i.putExtra(Intent.EXTRA_SUBJECT, "Slip Gaji " + uname);
                    i.setClipData(new android.content.ClipData("Slip Gaji",
                            new String[]{mime}, new android.content.ClipData.Item(uri)));
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    Toast.makeText(this, "ZIP dikunci PIN admin. Ekstrak pakai PIN admin → "
                            + "XLSX terbuka di app apa pun.", Toast.LENGTH_LONG).show();
                    startActivity(Intent.createChooser(i, "Bagikan Slip Gaji"));
                } catch (Exception e) {
                    Toast.makeText(this, "Gagal membagikan: " + e.getMessage(),
                            Toast.LENGTH_LONG).show();
                }
            });
        }, "my-payslip").start();
    }

    /** Kirim ulang semua laporan yang gagal terkirim (antrian pending). */
    private void sendPendingReports() {
        float d = getResources().getDisplayMetrics().density;
        android.widget.LinearLayout row = new android.widget.LinearLayout(this);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        int pad = (int) (20 * d);
        row.setPadding(pad, pad, pad, pad);
        android.widget.ProgressBar pb = new android.widget.ProgressBar(this);
        TextView tv = new TextView(this);
        tv.setText("Mengirim laporan pending…");
        tv.setPadding((int) (16 * d), 0, 0, 0);
        row.addView(pb);
        row.addView(tv);
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setView(row).setCancelable(false).create();
        dlg.show();
        ShiftEmailSender.resendAllAsync(getApplicationContext(), (sent, remaining) -> {
            try { dlg.dismiss(); } catch (Throwable ignored) {}
            if (isFinishing() || isDestroyed()) return;
            Toast.makeText(this, sent + " laporan terkirim"
                    + (remaining > 0 ? ", " + remaining + " masih gagal" : "") + ".",
                    Toast.LENGTH_LONG).show();
            invalidateOptionsMenu();
        });
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
        com.google.android.material.button.MaterialButton btnUpgrade =
                view.findViewById(R.id.btnAboutUpgrade);
        com.google.android.material.button.MaterialButton btnManage =
                view.findViewById(R.id.btnAboutManage);
        com.google.android.material.button.MaterialButton btnChangelog =
                view.findViewById(R.id.btnAboutChangelog);

        tvVersion.setText("v" + BuildConfig.VERSION_NAME);

        if (btnChangelog != null) {
            btnChangelog.setOnClickListener(v -> {
                startActivity(new Intent(this, ChangelogActivity.class));
                dialog.dismiss();
            });
        }

        boolean pro = settingsDao.isProActive();
        if (pro) {
            tvSubStatus.setText("Pro Aktif");
            tvSubStatus.setTextColor(getResources().getColor(R.color.green));

            String productId = settingsDao.getProProductId();
            if (BuildConfig.SUB_PRODUCT_MONTHLY.equals(productId)) {
                tvSubPlan.setText("Paket: Bulanan");
                tvSubPlan.setVisibility(View.VISIBLE);
            } else if (BuildConfig.SUB_PRODUCT_YEARLY.equals(productId)) {
                tvSubPlan.setText("Paket: Tahunan");
                tvSubPlan.setVisibility(View.VISIBLE);
            }

            long expiry = settingsDao.getProExpiryAt();
            if (expiry > 0) {
                java.text.SimpleDateFormat sdf =
                        new java.text.SimpleDateFormat("d MMM yyyy", new Locale("id", "ID"));
                tvSubExpiry.setText("Berlaku hingga: " + sdf.format(new java.util.Date(expiry)));
                tvSubExpiry.setVisibility(View.VISIBLE);
            }

            btnUpgrade.setText("Status: Pro");
            btnUpgrade.setEnabled(false);
            btnManage.setVisibility(View.VISIBLE);
            btnManage.setOnClickListener(v -> {
                try {
                    Uri uri = Uri.parse(
                            "https://play.google.com/store/account/subscriptions?sku="
                                    + (productId == null ? "" : productId)
                                    + "&package=" + getPackageName());
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                    dialog.dismiss();
                } catch (Exception e) {
                    Toast.makeText(this, "Tidak dapat membuka Play Store",
                            Toast.LENGTH_SHORT).show();
                }
            });
        } else {
            tvSubStatus.setText("Gratis");
            tvSubStatus.setTextColor(getResources().getColor(R.color.text_primary));
            btnUpgrade.setOnClickListener(v -> {
                startActivity(new Intent(this, UpgradeActivity.class));
                dialog.dismiss();
            });
        }

        dialog.show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshDashboard();
        // Online realtime: (re)connect MQTT for version/broadcast/sync push.
        com.crowja.damiupos.sync.MqttManager.get().ensureConnected(getApplicationContext());
        // Listen broadcast pesanan WA baru
        IntentFilter f = new IntentFilter(WaListenerService.ACTION_NEW_ORDER);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(newOrderReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(newOrderReceiver, f);
        }
        refreshOrderInboxBanner();
        // Re-evaluasi visibilitas ikon inbox (auto-detect bisa di-toggle di Pengaturan).
        invalidateOptionsMenu();
    }

    @Override
    protected void onPause() {
        super.onPause();
        try { unregisterReceiver(newOrderReceiver); } catch (Exception ignored) {}
    }

    private void startBlink(View view) {
        AlphaAnimation blink = new AlphaAnimation(1f, 0.3f);
        blink.setDuration(500);
        blink.setRepeatMode(Animation.REVERSE);
        blink.setRepeatCount(Animation.INFINITE);
        view.startAnimation(blink);
    }

    private void updateFollowUpIndicator() {
        if (btnFollowUp == null) return;
        int count = customerDao.countFollowUpCandidates(settingsDao.getFollowupDays());
        btnFollowUp.setText("Follow Up");

        TextView tvFollowUpBadge = findViewById(R.id.tvFollowUpBadge);
        if (tvFollowUpBadge != null) {
            if (count > 0) {
                tvFollowUpBadge.setText(String.valueOf(count));
                tvFollowUpBadge.setVisibility(View.VISIBLE);
            } else {
                tvFollowUpBadge.setVisibility(View.GONE);
            }
        }

        if (count > 0) {
            btnFollowUp.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#D32F2F")));
            startBlink(btnFollowUp);
        } else {
            btnFollowUp.clearAnimation();
            if (originalFollowUpTint != null) {
                btnFollowUp.setBackgroundTintList(originalFollowUpTint);
            }
        }
    }

    /**
     * Badge "Transaksi Pending" di tombol Jual Air Minum: tampil & berkedip
     * selama ada pending; ketuk badge → daftar Transaksi Pending. Disembunyikan
     * untuk Viewer (tombol Jual juga disembunyikan untuknya).
     */
    private void updatePendingTrxIndicator() {
        TextView badge = findViewById(R.id.tvPendingBadge);
        if (badge == null) return;
        int count = 0;
        try {
            count = new com.crowja.damiupos.db.PendingTransactionDao(
                    DatabaseHelper.getInstance(this)).countPending();
        } catch (Exception ignored) {}
        View jualBtn = findViewById(R.id.btnJualGalon);
        boolean jualVisible = jualBtn == null || jualBtn.getVisibility() == View.VISIBLE;
        if (count > 0 && jualVisible) {
            badge.setText(String.valueOf(count));
            badge.setVisibility(View.VISIBLE);
            startBlink(badge);
            badge.setOnClickListener(v ->
                    startActivity(new Intent(this, PendingTransactionListActivity.class)));
        } else {
            badge.clearAnimation();
            badge.setVisibility(View.GONE);
        }
    }

    private void updateStockIndicator() {
        // Indikator stok sekarang menempel di card "Galon Beredar"
        // (tile Stok Galon sudah dihapus, card jadi pintu masuknya).
        TextView tvStokBadge = findViewById(R.id.tvStokBadge);
        if (tvStokBadge == null) return;
        int threshold = settingsDao.getStockAlert();
        int stok = galonStockDao.getStokTersedia();

        tvStokBadge.setText("Stok: " + stok);

        if (stok <= threshold) {
            tvStokBadge.setTextColor(Color.parseColor("#D32F2F"));
            startBlink(tvStokBadge);
        } else {
            tvStokBadge.clearAnimation();
            tvStokBadge.setTextColor(Color.parseColor("#26A69A"));
        }
    }

    private void refreshDashboard() {
        NumberFormat nf = NumberFormat.getInstance(new Locale("id", "ID"));

        // Pro Temp chip — countdown ke expiry kalau user pakai rewarded ad
        TextView chipProTemp = findViewById(R.id.chipProTemp);
        if (chipProTemp != null) {
            if (settingsDao.isProTempActive() && !settingsDao.isProSubscriber()) {
                long remMs = settingsDao.getProTempUntil() - System.currentTimeMillis();
                long hrs = remMs / 3_600_000L;
                long mins = (remMs % 3_600_000L) / 60_000L;
                String label = hrs > 0
                        ? "🎁 Pro Trial: " + hrs + "j " + mins + "m sisa  •  tap untuk Upgrade"
                        : "🎁 Pro Trial: " + mins + " menit sisa  •  tap untuk Upgrade";
                chipProTemp.setText(label);
                chipProTemp.setVisibility(View.VISIBLE);
                chipProTemp.setOnClickListener(v ->
                        startActivity(new Intent(this, UpgradeActivity.class)));
            } else {
                chipProTemp.setVisibility(View.GONE);
            }
        }

        double pendapatan = transactionDao.getPendapatanHariIni();
        tvPendapatan.setText("Rp " + nf.format(pendapatan));

        int galonTerjual = transactionDao.getGalonTerjualHariIni();
        tvGalonTerjual.setText(String.valueOf(galonTerjual));

        int trxHariIni = transactionDao.getTransaksiHariIni();
        tvTransaksiHariIni.setText(String.valueOf(trxHariIni));

        int galonBeredar = customerDao.getTotalGalonBeredar();
        tvGalonBeredar.setText(String.valueOf(galonBeredar));

        int totalPelanggan = customerDao.getTotalCustomers();
        tvTotalPelanggan.setText(String.valueOf(totalPelanggan));

        refreshTrends();

        // Blinking indicators
        updateFollowUpIndicator();
        updateStockIndicator();
        refreshOperatorBar();
        // Setelah refreshOperatorBar (visibilitas tombol Jual sudah final untuk
        // role saat ini) → evaluasi badge Transaksi Pending.
        updatePendingTrxIndicator();

        // Recent transactions
        List<Transaction> recent = transactionDao.getRecent(10);
        adapter.setData(recent);

        if (recent.isEmpty()) {
            tvEmptyRecent.setVisibility(TextView.VISIBLE);
            rvRecentTransactions.setVisibility(RecyclerView.GONE);
        } else {
            tvEmptyRecent.setVisibility(TextView.GONE);
            rvRecentTransactions.setVisibility(RecyclerView.VISIBLE);
        }
        // Refresh banner pesanan WA pending
        refreshOrderInboxBanner();
    }

    // ----------------------------------------------------------- Tren harian

    private static final int TREND_WINDOW = 30;          // hari (periode "per bulan")
    private static final int TREND_UP = 0xFF69F0AE;      // hijau muda
    private static final int TREND_DOWN = 0xFFFF8A80;    // merah muda
    private static final int TREND_FLAT = 0xFFE0E0E0;    // abu terang

    /**
     * Hitung rata-rata per hari (window 30 hari ≈ per bulan) untuk 5 metrik
     * dashboard, lalu tampilkan sebagai chip kecil dengan ikon panah arah tren
     * dibanding 30 hari sebelumnya: NAIK (panah atas), TURUN (panah bawah),
     * STAGNAN (garis). Warna hijau/merah/abu sesuai arah.
     */
    private void refreshTrends() {
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
        double[] cur = transactionDao.getSummaryByDateRange(curStart, today);
        double[] prev = transactionDao.getSummaryByDateRange(prevStart, prevEnd);
        double w = TREND_WINDOW;

        // 1. Pendapatan rata-rata / hari
        setTrendRupiah(tvPendapatanTrend, cur[3] / w, prev[3] / w);
        // 2. Galon terjual rata-rata / hari
        setTrendNumber(tvGalonTerjualTrend, cur[1] / w, prev[1] / w, false);
        // 3. Transaksi rata-rata / hari
        setTrendNumber(tvTransaksiTrend, cur[0] / w, prev[0] / w, false);
        // 4. Galon beredar — penambahan bersih / hari (galon JUAL - galon KEMBALI)
        setTrendNumber(tvGalonBeredarTrend, (cur[1] - cur[2]) / w, (prev[1] - prev[2]) / w, true);
        // 5. Pelanggan baru rata-rata / hari
        int curNew = customerDao.getCountCreatedBetween(curStart, today);
        int prevNew = customerDao.getCountCreatedBetween(prevStart, prevEnd);
        setTrendNumber(tvTotalPelangganTrend, curNew / w, prevNew / w, true);
    }

    /** -1 = TURUN, 0 = STAGNAN, 1 = NAIK. Ambang ±5%. */
    private int trendDir(double cur, double prev) {
        double base = Math.max(Math.abs(prev), 0.0001);
        double pct = (cur - prev) / base;
        if (pct > 0.05) return 1;
        if (pct < -0.05) return -1;
        return 0;
    }

    private void applyTrend(TextView tv, int dir, String avgText) {
        if (tv == null) return;
        String arrow = dir > 0 ? "▲" : dir < 0 ? "▼" : "▬";
        int color = dir > 0 ? TREND_UP : dir < 0 ? TREND_DOWN : TREND_FLAT;
        tv.setText(arrow + " " + avgText + "/hari");
        tv.setTextColor(color);
        tv.setVisibility(View.VISIBLE);
    }

    private void setTrendRupiah(TextView tv, double curAvg, double prevAvg) {
        applyTrend(tv, trendDir(curAvg, prevAvg), "Rp " + compactRupiah(curAvg));
    }

    private void setTrendNumber(TextView tv, double curAvg, double prevAvg, boolean signed) {
        String v = oneDecimal(curAvg);
        if (signed && curAvg > 0) v = "+" + v;
        applyTrend(tv, trendDir(curAvg, prevAvg), v);
    }

    private String compactRupiah(double v) {
        double a = Math.abs(v);
        if (a >= 1_000_000_000d) return oneDecimal(v / 1_000_000_000d) + "M";
        if (a >= 1_000_000d) return oneDecimal(v / 1_000_000d) + "jt";
        if (a >= 1_000d) return oneDecimal(v / 1_000d) + "rb";
        return String.valueOf(Math.round(v));
    }

    private String oneDecimal(double v) {
        return String.format(new Locale("id", "ID"), "%.1f", v);
    }

    /**
     * Update toolbar berdasarkan jumlah pesanan WA yang masih PENDING.
     * Sound + blink jalan SELALU selama ada pesanan pending — baru stop
     * setelah user approve (Selesai / Buat Trx + Selesai) atau Tolak
     * SEMUA pesanan di inbox.
     */
    private void refreshOrderInboxBanner() {
        if (tvToolbarTitle == null || orderInboxDao == null) return;
        int pendingCount = orderInboxDao.countPending();
        if (pendingCount == 0) {
            // Reset ke tampilan default + matikan alert
            tvToolbarTitle.setText(R.string.app_name);
            tvToolbarSubtitle.setText(DEFAULT_TOOLBAR_SUBTITLE);
            mainToolbar.setBackgroundColor(originalToolbarColor);
            NotificationAlertHelper.stopAlert();
            return;
        }
        OrderInbox latest = orderInboxDao.getLatestPending();
        if (latest == null) return;
        ParsedOrder parsed = ParsedOrder.fromJson(latest.getParsedJson());

        String sender = latest.getSenderName() != null
                ? latest.getSenderName() : "(unknown)";
        String prefix = pendingCount > 1
                ? "📨 " + pendingCount + " pesanan baru — " : "📨 ";
        tvToolbarTitle.setText(prefix + sender);

        String sub;
        if (parsed.isOrder && !parsed.items.isEmpty()) {
            sub = parsed.shortSummary();
            if (parsed.urgent) sub += " ⚡";
            sub += " — Tap untuk konfirmasi";
        } else {
            sub = "Tap untuk lihat & konfirmasi";
        }
        tvToolbarSubtitle.setText(sub);
        // Warna accent supaya menonjol
        mainToolbar.setBackgroundColor(getResources().getColor(R.color.accent));

        // Blink toolbar — visual saja. Sound di-handle OrderAlertService
        // yang berjalan sebagai foreground service supaya tetap bunyi
        // di background.
        NotificationAlertHelper.startAlert(mainToolbar);
    }

    /**
     * "Akui" — dipanggil saat user tap toolbar atau action Inbox di menu.
     * Sebelumnya menyetop sound, sekarang HANYA membuka Inbox; sound &
     * blink terus jalan sampai SEMUA pending di-approve / tolak (per
     * permintaan user, supaya alert benar-benar mengikat).
     */
    private void acknowledgeAlerts() {
        // No-op — sound stop hanya kalau pending count benar-benar 0
        // (refreshOrderInboxBanner). Method dipertahankan supaya pemanggil
        // (toolbar click / menu action) tetap bisa dipakai untuk hook
        // analytics atau perilaku tambahan kalau perlu nanti.
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Stop blink animation saat activity background (mencegah memory
        // leak). Sound tetap jalan di OrderAlertService — visual saja
        // yang pause.
        NotificationAlertHelper.stopAlert();
    }
}
