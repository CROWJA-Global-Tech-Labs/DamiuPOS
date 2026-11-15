package com.crowja.damiupos;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.crowja.damiupos.adapter.FrekuensiAdapter;
import com.crowja.damiupos.db.CustomerDao;
import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.TransactionDao;
import com.crowja.damiupos.db.SettingsDao;
import com.crowja.damiupos.db.UserDao;
import com.crowja.damiupos.model.Customer;
import com.crowja.damiupos.model.User;
import com.crowja.damiupos.report.SalesTrendPanel;
import com.crowja.damiupos.view.SimpleBarChart;

import java.util.ArrayList;
import java.util.List;

/**
 * Laporan. Tab "📈 Tren Penjualan" (se-cabang dari server, Admin/SPV/Marketing — {@link SalesTrendPanel})
 * + tab "📊 Perangkat ini" (grafik & pelanggan dari DB lokal, isi Laporan lama). Ubin Beranda
 * "Tren Penjualan" membuka tab Tren langsung lewat {@link #EXTRA_TAB} = {@link #TAB_TREN}.
 */
public class ReportActivity extends AppCompatActivity {

    public static final String EXTRA_TAB = "report_tab";
    public static final String TAB_TREN = "tren";
    public static final String TAB_LOCAL = "local";

    private SalesTrendPanel trendPanel;
    private boolean localLoaded;

    private SimpleBarChart chartBulanan, chartHarian;
    private TextView tvEmptyBulanan, tvEmptyHarian, tvEmptyTop;
    private RecyclerView rvFrekuensi, rvTopCustomer;

    private TransactionDao transactionDao;
    private CustomerDao customerDao;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_report);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        DatabaseHelper dbHelper = DatabaseHelper.getInstance(this);
        transactionDao = new TransactionDao(dbHelper);
        customerDao = new CustomerDao(dbHelper);

        chartBulanan = findViewById(R.id.chartBulanan);
        chartHarian = findViewById(R.id.chartHarian);
        tvEmptyBulanan = findViewById(R.id.tvEmptyBulanan);
        tvEmptyHarian = findViewById(R.id.tvEmptyHarian);
        tvEmptyTop = findViewById(R.id.tvEmptyTop);
        rvFrekuensi = findViewById(R.id.rvFrekuensi);
        rvTopCustomer = findViewById(R.id.rvTopCustomer);

        rvFrekuensi.setLayoutManager(new LinearLayoutManager(this));
        rvTopCustomer.setLayoutManager(new LinearLayoutManager(this));

        boolean trend = canViewTrend(dbHelper);
        String want = getIntent().getStringExtra(EXTRA_TAB);
        if (TAB_TREN.equals(want) && !trend) {
            android.widget.Toast.makeText(this, "Tren Penjualan hanya untuk Admin, SPV, dan Marketing.",
                    android.widget.Toast.LENGTH_LONG).show();
        }
        if (!trend) {
            showLocal();
            return;
        }
        // Dibuka dari menu "Tren" (Tema v2): hanya Tren, tanpa tab Laporan.
        if (TAB_TREN.equals(want)) {
            findViewById(R.id.reportTabBar).setVisibility(View.GONE);
            showTrend();
            return;
        }
        // Isi Laporan lama (DB perangkat) khusus Admin, seperti sebelumnya; SPV/Marketing hanya tab Tren.
        boolean local = isAdminOrLegacy(dbHelper);
        View bar = findViewById(R.id.reportTabBar);
        bar.setVisibility(local ? View.VISIBLE : View.GONE);
        if (local) {
            com.google.android.material.button.MaterialButtonToggleGroup tabs = findViewById(R.id.reportTabs);
            tabs.addOnButtonCheckedListener((g, id, checked) -> {
                if (!checked) return;
                if (id == R.id.btnTabTren) showTrend();
                else showLocal();
            });
            tabs.check(TAB_LOCAL.equals(want) ? R.id.btnTabLocal : R.id.btnTabTren);
        } else {
            showTrend();
        }
    }

    private void showTrend() {
        if (toolbarTitle() != null) toolbarTitle().setTitle("Tren");
        findViewById(R.id.scrollLocal).setVisibility(View.GONE);
        findViewById(R.id.swipeTren).setVisibility(View.VISIBLE);
        if (trendPanel == null) {
            trendPanel = new SalesTrendPanel(this, findViewById(R.id.swipeTren), findViewById(R.id.trendContainer));
            trendPanel.start();
        }
    }

    private void showLocal() {
        if (toolbarTitle() != null) toolbarTitle().setTitle("Laporan");
        findViewById(R.id.swipeTren).setVisibility(View.GONE);
        findViewById(R.id.scrollLocal).setVisibility(View.VISIBLE);
        if (!localLoaded) {
            localLoaded = true;
            loadCharts();
        }
    }

    private Toolbar toolbarTitle() {
        return findViewById(R.id.toolbar);
    }

    @androidx.annotation.Nullable
    private User currentUser(DatabaseHelper db) {
        try {
            SettingsDao s = new SettingsDao(db);
            long uid = s.isMultiUserEnabled() ? s.getCurrentUserId() : 0;
            return uid > 0 ? new UserDao(db).getById(uid) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Self-guard: tab Tren hanya Admin/SPV/Marketing (server juga menolak peran lain). */
    private boolean canViewTrend(DatabaseHelper db) {
        User u = currentUser(db);
        return u != null && u.canViewSalesTrend();
    }

    private boolean isAdminOrLegacy(DatabaseHelper db) {
        User u = currentUser(db);
        return u == null || u.isAdmin();
    }

    private void loadCharts() {
        int colorPrimary = com.crowja.damiupos.util.ThemeColors.primary(this);
        int colorAccent = ContextCompat.getColor(this, R.color.accent);
        int colorGreen = ContextCompat.getColor(this, R.color.green);

        // 1. Monthly sales chart (last 6 months)
        List<String[]> monthlySales = transactionDao.getMonthlySales(6);
        if (monthlySales.isEmpty()) {
            chartBulanan.setVisibility(View.GONE);
            tvEmptyBulanan.setVisibility(View.VISIBLE);
        } else {
            chartBulanan.setVisibility(View.VISIBLE);
            tvEmptyBulanan.setVisibility(View.GONE);
            List<SimpleBarChart.BarData> monthlyData = new ArrayList<>();
            for (String[] row : monthlySales) {
                // row: [YYYY-MM, total_galon, total_pendapatan]
                String label = row[0].length() >= 7 ? row[0].substring(5) : row[0]; // MM
                monthlyData.add(new SimpleBarChart.BarData(label, Float.parseFloat(row[1]), colorPrimary));
            }
            chartBulanan.setData(monthlyData);
        }

        // 2. Daily sales chart (current month)
        List<String[]> dailySales = transactionDao.getDailySalesThisMonth();
        if (dailySales.isEmpty()) {
            chartHarian.setVisibility(View.GONE);
            tvEmptyHarian.setVisibility(View.VISIBLE);
        } else {
            chartHarian.setVisibility(View.VISIBLE);
            tvEmptyHarian.setVisibility(View.GONE);

            // Adjust chart width based on number of days (in px: dp * density).
            float density = getResources().getDisplayMetrics().density;
            int chartWidth = (int) (Math.max(dailySales.size() * 56, 360) * density);
            android.view.ViewGroup.LayoutParams lp = chartHarian.getLayoutParams();
            lp.width = chartWidth;
            chartHarian.setLayoutParams(lp);

            List<SimpleBarChart.BarData> dailyData = new ArrayList<>();
            for (String[] row : dailySales) {
                dailyData.add(new SimpleBarChart.BarData(row[0], Float.parseFloat(row[1]), colorAccent));
            }
            chartHarian.setData(dailyData);
        }

        // 3. Top 10 customers by galon purchased (horizontal bar list, clickable)
        List<Customer> topCustomers = customerDao.getTopCustomers(10);
        if (topCustomers.isEmpty()) {
            rvTopCustomer.setVisibility(View.GONE);
            tvEmptyTop.setVisibility(View.VISIBLE);
        } else {
            rvTopCustomer.setVisibility(View.VISIBLE);
            tvEmptyTop.setVisibility(View.GONE);
            FrekuensiAdapter topAdapter = new FrekuensiAdapter(FrekuensiAdapter.MODE_GALON);
            topAdapter.setOnItemClickListener(this::openCustomerDetail);
            rvTopCustomer.setAdapter(topAdapter);
            topAdapter.setData(topCustomers);
        }

        // 4. Frequency table (reuse top customers)
        FrekuensiAdapter frekuensiAdapter = new FrekuensiAdapter(FrekuensiAdapter.MODE_FREKUENSI);
        frekuensiAdapter.setOnItemClickListener(this::openCustomerDetail);
        rvFrekuensi.setAdapter(frekuensiAdapter);
        frekuensiAdapter.setData(topCustomers);
    }

    private void openCustomerDetail(Customer c) {
        Intent intent = new Intent(this, CustomerDetailActivity.class);
        intent.putExtra("customer_id", c.getId());
        startActivity(intent);
    }
}
