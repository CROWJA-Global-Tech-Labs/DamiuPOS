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
import com.crowja.damiupos.model.Customer;
import com.crowja.damiupos.view.SimpleBarChart;

import java.util.ArrayList;
import java.util.List;

public class ReportActivity extends AppCompatActivity {

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

        loadCharts();
    }

    private void loadCharts() {
        int colorPrimary = ContextCompat.getColor(this, R.color.primary);
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
