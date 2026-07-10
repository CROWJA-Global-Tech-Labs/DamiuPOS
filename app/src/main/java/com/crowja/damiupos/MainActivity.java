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

import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

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

    /** Receiver: saat sinkron membawa data baru dari server (mis. order delivery
     *  dibuat di dashboard web), segarkan dashboard supaya badge Antrian Delivery
     *  langsung update tanpa harus keluar-masuk layar. */
    private final BroadcastReceiver syncedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refreshDashboard();
        }
    };

    /** Receiver: pesan admin dari dashboard ("Kirim Pesan ke Perangkat") tiba saat
     *  layar tampil → tampilkan popup dialog (selain notifikasi Android). */
    private final BroadcastReceiver adminMsgReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            showPendingAdminMessage();
        }
    };
    /** Popup pesan admin yang sedang tampil (supaya tidak menumpuk). */
    private androidx.appcompat.app.AlertDialog adminMsgDialog;

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

        // Input Promosi Galon: buat pelanggan BARU dulu (form lengkap), lalu lanjut ke Transaksi
        // Baru mode Promosi (toggle Gratis/Berbayar). Poin promosi otomatis dihitung di server.
        View btnPromosi = findViewById(R.id.btnPromosi);
        if (btnPromosi != null) btnPromosi.setOnClickListener(v ->
                startActivity(new Intent(this, CustomerFormActivity.class)
                        .putExtra(CustomerFormActivity.EXTRA_PROMOSI_FLOW, true)));

        btnFollowUp = findViewById(R.id.btnFollowUp);
        originalFollowUpTint = btnFollowUp.getBackgroundTintList();
        btnFollowUp.setOnClickListener(v -> {
            btnFollowUp.clearAnimation();
            startActivity(new Intent(this, FollowUpActivity.class));
        });

        findViewById(R.id.btnAntrianDelivery).setOnClickListener(v ->
                startActivity(new Intent(this, DeliveryQueueActivity.class)));

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
        // Pulang: konfirmasi → selfie → catat OUT → laporan + apresiasi.
        if (btnClockOut != null) btnClockOut.setOnClickListener(v -> confirmPulang());
        // Admin: logout biasa (tanpa absensi/laporan).
        if (btnAdminLogout != null) btnAdminLogout.setOnClickListener(v -> doAdminLogout());

        // Siapkan channel pengingat jam kerja + minta izin notifikasi (Android 13+).
        ensureNotificationAccess();

        // Online: keep periodic sync armed + check for a newer app version on launch.
        com.crowja.damiupos.sync.SyncScheduler.schedulePeriodic(getApplicationContext());
        com.crowja.damiupos.sync.VersionUpdater.checkAndPrompt(this);
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
        boolean isMarketing = false;
        if (show) {
            com.crowja.damiupos.model.User cur =
                    new com.crowja.damiupos.db.UserDao(DatabaseHelper.getInstance(this)).getById(uid);
            isAdmin = cur != null && cur.isAdmin();
            isViewer = cur != null && cur.isViewer();
            isMarketing = cur != null && cur.isMarketing();
        }
        boolean tracksAttendance = show && !isAdmin && !isViewer && !isMarketing; // hanya staf yang absen

        // Viewer tidak boleh buat transaksi → sembunyikan aksi cepat Jual/Kembali.
        View qaJual = findViewById(R.id.btnJualGalon);
        View qaKembali = findViewById(R.id.btnGalonKembali);
        if (qaJual != null) qaJual.setVisibility(isViewer ? View.GONE : View.VISIBLE);
        if (qaKembali != null) qaKembali.setVisibility(isViewer ? View.GONE : View.VISIBLE);

        // Marketing hanya melakukan Promosi → sembunyikan panel Jual/Kembali,
        // tampilkan tombol Promosi (Admin lihat keduanya).
        View cardQuickActions = findViewById(R.id.cardQuickActions);
        if (cardQuickActions != null) {
            cardQuickActions.setVisibility(isMarketing ? View.GONE : View.VISIBLE);
        }
        // Marketing tidak ikut flow delivery (antrean kiriman dikerjakan kurir/staf depot)
        // → sembunyikan tombol Delivery beserta badge-nya (parent FrameLayout keduanya).
        View btnDelivery = findViewById(R.id.btnAntrianDelivery);
        if (btnDelivery != null && btnDelivery.getParent() instanceof View) {
            ((View) btnDelivery.getParent()).setVisibility(isMarketing ? View.GONE : View.VISIBLE);
        }

        // Input Promosi Galon: Marketing & Admin selalu; staf lain bila promo_enabled (salary_config).
        View btnPromosi = findViewById(R.id.btnPromosi);
        if (btnPromosi != null) {
            boolean canPromosi = show
                    && new com.crowja.damiupos.db.UserDao(DatabaseHelper.getInstance(this)).canPromosi(uid);
            btnPromosi.setVisibility(canPromosi ? View.VISIBLE : View.GONE);
        }

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
            tvShift.setText(isViewer ? "Viewer" : isMarketing ? "Marketing" : "Admin");
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
        String uname = settingsDao.getCurrentUserName();
        new AlertDialog.Builder(this)
                .setTitle("Istirahat?")
                .setMessage("Aplikasi terkunci selama istirahat. Tekan \"Lanjut Kerja\" "
                        + "untuk melanjutkan tanpa PIN, atau rekan lain bisa login.")
                .setPositiveButton("Ya, Istirahat", (d, w) -> {
                    long attId = new AttendanceDao(DatabaseHelper.getInstance(this))
                            .log(uid, Attendance.EVENT_BREAK);
                    LocationService.stampAttendanceLocation(this, attId);   // GPS istirahat untuk dashboard
                    com.crowja.damiupos.sync.SyncScheduler.syncNow(getApplicationContext());   // absensi real-time
                    // Pause pengingat jam kerja — di-rearm saat clock in lagi.
                    WorkHoursReminder.cancel(getApplicationContext(), uid);
                    // Istirahat: berhenti melacak LOKASI, tapi service tetap hidup
                    // (poll-only) supaya polling background tetap aktif selama shift.
                    LocationService.pollOnly(getApplicationContext());
                    // Ingat siapa yang istirahat → tombol "Lanjut Kerja" 1 ketukan di layar login
                    // (tanpa PIN/selfie). Shift tetap terbuka sampai Pulang.
                    settingsDao.setBreakUser(uid, uname);
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

        // "Jam kerja hari ini" untuk popup apresiasi — dibatasi tanggal hari ini
        // (akurat walau shift sempat dibiarkan terbuka lintas hari).
        long workMs = ShiftReporter.workedMillisToday(dbHelper, uid);

        // Catat OUT + foto pulang.
        long outAttId = attDao.log(uid, Attendance.EVENT_OUT, logoutPhoto);
        LocationService.stampAttendanceLocation(this, outAttId);   // GPS pulang untuk dashboard
        com.crowja.damiupos.sync.SyncScheduler.syncNow(getApplicationContext());   // absensi real-time
        // Shift selesai → batalkan pengingat jam kerja + lepas sesi SEGERA
        // (sinkron). Kalau popup apresiasi hilang karena rotasi/destroy, gate
        // onCreate tetap mengarahkan ke login (tidak terjebak masih "login").
        WorkHoursReminder.cancel(getApplicationContext(), uid);
        // Shift selesai → tutup flag + hentikan GPS, TAPI service tetap hidup dalam
        // mode poll-only supaya sinkronisasi dengan dashboard tetap jalan di
        // background (mis. karyawan baru ditambah admin tetap terkirim ke HP).
        settingsDao.setShiftActive(false);
        LocationService.pollOnly(getApplicationContext());
        pendingLogoutUid = 0;
        pendingLogoutName = null;
        settingsDao.clearCurrentUser();

        // Data shift (termasuk event OUT + foto) tersimpan lokal & didorong ke
        // server — admin melihatnya di dashboard web. Tidak ada lagi email.
        com.crowja.damiupos.sync.SyncScheduler.syncNow(getApplicationContext());

        showAppreciationThenLogout(uname, workMs);
    }

    /**
     * Tampilkan kata-kata apresiasi + total jam kerja hari ini, lalu lanjutkan
     * ke layar login setelah user menekan OK. Dialog tidak bisa dibatalkan
     * supaya clock out selalu tuntas.
     */
    private void showAppreciationThenLogout(String uname, long workMs) {
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
        if (item.getItemId() == R.id.action_sync) {
            startActivity(new Intent(this, SyncSettingsActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
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
        // Multi-user login may have just been turned on from the dashboard (synced via
        // app_settings) — enforce the login/clock-in gate now, without needing a cold restart.
        // Guard isFinishing() so we don't double-launch when onCreate already gated + finished.
        if (!isFinishing() && settingsDao.isMultiUserEnabled() && settingsDao.getCurrentUserId() <= 0) {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }
        refreshDashboard();
        // Online (REST, no MQTT): kick a sync + online tick (config/version/
        // broadcasts) so opening the app pulls fresh data and admin messages.
        com.crowja.damiupos.sync.SyncScheduler.syncNow(getApplicationContext());
        // Hangatkan cache logo depot (disinkron dari web) supaya struk pertama pun sudah ada logonya.
        new Thread(() -> com.crowja.damiupos.util.DepotLogo.ensureDownloaded(getApplicationContext())).start();
        // Sinkronisasi berkelanjutan: nyalakan service polling yang berjalan terus
        // selama app hidup (termasuk background), tidak bergantung pada shift —
        // sehingga perubahan dari dashboard (karyawan baru, dll.) sampai real-time.
        LocationService.ensureOnline(getApplicationContext());
        // Listen broadcast "sinkron membawa data baru" → badge Antrian Delivery
        // (dan KPI/transaksi terakhir) ikut update real-time saat dashboard terbuka.
        IntentFilter syncedFilter = new IntentFilter(com.crowja.damiupos.sync.SyncEngine.ACTION_SYNCED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(syncedReceiver, syncedFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(syncedReceiver, syncedFilter);
        }
        // Listen pesan admin baru → popup dialog saat dashboard tampil.
        IntentFilter adminMsgFilter = new IntentFilter(
                com.crowja.damiupos.sync.OnlineNotifier.ACTION_ADMIN_MESSAGE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(adminMsgReceiver, adminMsgFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(adminMsgReceiver, adminMsgFilter);
        }
        refreshOrderInboxBanner();
        // Re-evaluasi visibilitas ikon inbox (auto-detect bisa di-toggle di Pengaturan).
        invalidateOptionsMenu();
        // Tampilkan pesan admin yang tertunda (mis. tiba saat app di background).
        showPendingAdminMessage();
    }

    @Override
    protected void onPause() {
        super.onPause();
        try { unregisterReceiver(syncedReceiver); } catch (Exception ignored) {}
        try { unregisterReceiver(adminMsgReceiver); } catch (Exception ignored) {}
    }

    /**
     * Tampilkan pesan admin dari dashboard ("Kirim Pesan ke Perangkat") sebagai popup
     * dialog (selain notifikasi Android). Sumber kebenaran = pending message di settings,
     * jadi tampil sekali lalu dibersihkan (tidak dobel antara broadcast & onResume).
     */
    private void showPendingAdminMessage() {
        if (isFinishing() || settingsDao == null) return;
        if (!settingsDao.hasPendingAdminMessage()) return;
        String title = settingsDao.getPendingAdminMessageTitle();
        String body = settingsDao.getPendingAdminMessageBody();
        settingsDao.clearPendingAdminMessage();
        if (adminMsgDialog != null && adminMsgDialog.isShowing()) adminMsgDialog.dismiss();
        adminMsgDialog = new androidx.appcompat.app.AlertDialog.Builder(this)
                .setIcon(android.R.drawable.ic_dialog_email)
                .setTitle(title == null || title.isEmpty() ? "Pesan dari Admin" : title)
                .setMessage(body)
                .setPositiveButton("OK", null)
                .show();
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

    /** Badge jumlah order di Antrian Delivery (PENDING). */
    private void updateDeliveryBadge() {
        TextView badge = findViewById(R.id.tvDeliveryBadge);
        if (badge == null) return;
        int count = 0;
        try {
            count = transactionDao.countDeliveryQueue();
        } catch (Exception ignored) {}
        if (count > 0) {
            badge.setText(String.valueOf(count));
            badge.setVisibility(View.VISIBLE);
        } else {
            badge.setVisibility(View.GONE);
        }
    }

    private void updateStockIndicator() {
        // Indikator stok sekarang menempel di card "Galon Beredar"
        // (tile Stok Galon sudah dihapus, card jadi pintu masuknya).
        TextView tvStokBadge = findViewById(R.id.tvStokBadge);
        if (tvStokBadge == null) return;
        int threshold = settingsDao.getStockAlert();
        // Pakai tersedia "resmi" (server-authoritative bila sync) — SAMA dengan layar Stok Galon &
        // dashboard web; sebelumnya badge memakai angka murni lokal sehingga bisa berbeda dari isi menu.
        int stok = galonStockDao.getStokTersediaResmi(
                new com.crowja.damiupos.sync.SyncSettings(settingsDao));

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
        updateDeliveryBadge();

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
        // Karyawan marketing tidak menangani pesanan → jangan tampilkan/bunyikan alert pesanan
        // (order baru dari WA/web). Perlakukan seperti tidak ada pesanan pending.
        int pendingCount = com.crowja.damiupos.db.UserDao.isCurrentUserMarketing(this)
                ? 0 : orderInboxDao.countPending();
        if (pendingCount == 0) {
            // Reset ke tampilan default + matikan alert
            tvToolbarTitle.setText(R.string.app_name);
            tvToolbarSubtitle.setText(DEFAULT_TOOLBAR_SUBTITLE);
            mainToolbar.setBackgroundColor(originalToolbarColor);
            NotificationAlertHelper.stopAlert();
            // Hentikan juga service SUARA pesanan (OrderAlertService) — untuk marketing pendingCount
            // dipaksa 0, jadi cabang ini mematikan alarm yang mungkin tertinggal jalan dari sesi user
            // sebelumnya (non-marketing) saat kini yang login marketing / saat pesanan sudah beres.
            com.crowja.damiupos.wa.OrderAlertService.stop(this);
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
