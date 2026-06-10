package com.crowja.damiupos;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
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
import com.crowja.damiupos.db.CustomerDao;
import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.GalonStockDao;
import com.crowja.damiupos.db.OrderInboxDao;
import com.crowja.damiupos.db.SettingsDao;
import com.crowja.damiupos.db.TransactionDao;
import com.crowja.damiupos.model.OrderInbox;
import com.crowja.damiupos.model.Transaction;
import com.crowja.damiupos.wa.NotificationAlertHelper;
import com.crowja.damiupos.wa.ParsedOrder;
import com.crowja.damiupos.wa.WaListenerService;

import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private TextView tvPendapatan, tvGalonTerjual, tvTransaksiHariIni;
    private TextView tvGalonBeredar, tvTotalPelanggan;
    private TextView tvEmptyRecent;
    private RecyclerView rvRecentTransactions;
    private TransactionAdapter adapter;

    private CustomerDao customerDao;
    private TransactionDao transactionDao;
    private SettingsDao settingsDao;
    private GalonStockDao galonStockDao;
    private OrderInboxDao orderInboxDao;
    private com.google.android.material.button.MaterialButton btnFollowUp;
    private com.google.android.material.button.MaterialButton btnStokGalon;
    private ColorStateList originalFollowUpTint;
    private ColorStateList originalStokGalonTint;
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
        tvEmptyRecent = findViewById(R.id.tvEmptyRecent);
        rvRecentTransactions = findViewById(R.id.rvRecentTransactions);

        adapter = new TransactionAdapter(true);
        rvRecentTransactions.setLayoutManager(new LinearLayoutManager(this));
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

        findViewById(R.id.btnPelanggan).setOnClickListener(v -> {
            startActivity(new Intent(this, CustomerListActivity.class));
        });

        btnStokGalon = findViewById(R.id.btnStokGalon);
        originalStokGalonTint = btnStokGalon.getBackgroundTintList();
        btnStokGalon.setOnClickListener(v -> {
            btnStokGalon.clearAnimation();
            startActivity(new Intent(this, GalonStockActivity.class));
        });

        findViewById(R.id.btnProduk).setOnClickListener(v -> {
            startActivity(new Intent(this, ProductListActivity.class));
        });

        findViewById(R.id.btnDaftarTransaksi).setOnClickListener(v -> {
            startActivity(new Intent(this, TransactionListActivity.class));
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

        // Banner ad di dashboard (auto-hide saat Pro)
        android.view.ViewGroup adContainer = findViewById(R.id.adContainer);
        if (adContainer != null) {
            com.crowja.damiupos.ads.AdManager.getInstance(this).attachBanner(this, adContainer);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
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

        tvVersion.setText("v" + BuildConfig.VERSION_NAME);

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
        // Listen broadcast pesanan WA baru
        IntentFilter f = new IntentFilter(WaListenerService.ACTION_NEW_ORDER);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(newOrderReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(newOrderReceiver, f);
        }
        refreshOrderInboxBanner();
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

    private void updateStockIndicator() {
        if (btnStokGalon == null) return;
        int threshold = settingsDao.getStockAlert();
        int stok = galonStockDao.getStokTersedia();

        btnStokGalon.setText("Stok Galon");

        TextView tvStokBadge = findViewById(R.id.tvStokBadge);
        if (tvStokBadge != null) {
            tvStokBadge.setText(String.valueOf(stok));
        }

        if (stok <= threshold) {
            btnStokGalon.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#D32F2F")));
            startBlink(btnStokGalon);
        } else {
            btnStokGalon.clearAnimation();
            if (originalStokGalonTint != null) {
                btnStokGalon.setBackgroundTintList(originalStokGalonTint);
            }
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

        // Blinking indicators
        updateFollowUpIndicator();
        updateStockIndicator();

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
