package com.damiu.pos;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.damiu.pos.adapter.TransactionAdapter;
import com.damiu.pos.db.CustomerDao;
import com.damiu.pos.db.DatabaseHelper;
import com.damiu.pos.db.TransactionDao;
import com.damiu.pos.model.Customer;
import com.damiu.pos.model.Transaction;

import java.util.List;

public class CustomerDetailActivity extends AppCompatActivity {

    private long customerId;
    private CustomerDao customerDao;
    private TransactionDao transactionDao;

    private TextView tvNama, tvTelepon, tvAlamat;
    private TextView tvGalonKeluar, tvGalonKembali, tvSaldoGalon;
    private TextView tvEmptyHistory;
    private RecyclerView rvTransactions;
    private TransactionAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_customer_detail);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        DatabaseHelper dbHelper = DatabaseHelper.getInstance(this);
        customerDao = new CustomerDao(dbHelper);
        transactionDao = new TransactionDao(dbHelper);

        customerId = getIntent().getLongExtra("customer_id", -1);
        if (customerId == -1) {
            finish();
            return;
        }

        tvNama = findViewById(R.id.tvNama);
        tvTelepon = findViewById(R.id.tvTelepon);
        tvAlamat = findViewById(R.id.tvAlamat);
        tvGalonKeluar = findViewById(R.id.tvGalonKeluar);
        tvGalonKembali = findViewById(R.id.tvGalonKembali);
        tvSaldoGalon = findViewById(R.id.tvSaldoGalon);
        tvEmptyHistory = findViewById(R.id.tvEmptyHistory);
        rvTransactions = findViewById(R.id.rvTransactions);

        adapter = new TransactionAdapter(false);
        rvTransactions.setLayoutManager(new LinearLayoutManager(this));
        rvTransactions.setAdapter(adapter);

        // WhatsApp button
        findViewById(R.id.btnWhatsapp).setOnClickListener(v -> openWhatsApp());

        // Edit button
        findViewById(R.id.btnEdit).setOnClickListener(v -> {
            Intent intent = new Intent(this, CustomerFormActivity.class);
            intent.putExtra("customer_id", customerId);
            startActivity(intent);
        });

        // Delete button
        findViewById(R.id.btnHapus).setOnClickListener(v -> confirmDelete());

        // FAB - new transaction for this customer
        findViewById(R.id.fabAddTransaction).setOnClickListener(v -> {
            Intent intent = new Intent(this, TransactionActivity.class);
            intent.putExtra("customer_id", customerId);
            intent.putExtra("type", Transaction.TYPE_JUAL);
            startActivity(intent);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadData();
    }

    private void loadData() {
        Customer customer = customerDao.getById(customerId);
        if (customer == null) {
            finish();
            return;
        }

        tvNama.setText(customer.getName());
        tvTelepon.setText(customer.getPhone() != null && !customer.getPhone().isEmpty()
                ? customer.getPhone() : "-");
        tvAlamat.setText(customer.getAddress() != null && !customer.getAddress().isEmpty()
                ? customer.getAddress() : "-");

        tvGalonKeluar.setText(String.valueOf(customer.getGalonKeluar()));
        tvGalonKembali.setText(String.valueOf(customer.getGalonKembali()));
        tvSaldoGalon.setText(String.valueOf(customer.getSaldoGalon()));

        // Load transaction history
        List<Transaction> transactions = transactionDao.getByCustomerId(customerId);
        adapter.setData(transactions);

        if (transactions.isEmpty()) {
            tvEmptyHistory.setVisibility(View.VISIBLE);
            rvTransactions.setVisibility(View.GONE);
        } else {
            tvEmptyHistory.setVisibility(View.GONE);
            rvTransactions.setVisibility(View.VISIBLE);
        }
    }

    private void openWhatsApp() {
        Customer customer = customerDao.getById(customerId);
        if (customer == null) return;

        String phone = customer.getPhone();
        if (phone == null || phone.trim().isEmpty()) {
            Toast.makeText(this, R.string.no_telepon_kosong, Toast.LENGTH_SHORT).show();
            return;
        }

        // Format phone number: remove leading 0, add 62
        phone = phone.trim().replaceAll("[^0-9]", "");
        if (phone.startsWith("0")) {
            phone = "62" + phone.substring(1);
        } else if (!phone.startsWith("62")) {
            phone = "62" + phone;
        }

        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setData(Uri.parse("https://wa.me/" + phone));
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, R.string.wa_tidak_tersedia, Toast.LENGTH_SHORT).show();
        }
    }

    private void confirmDelete() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.konfirmasi_hapus)
                .setMessage(R.string.yakin_hapus_pelanggan)
                .setPositiveButton(R.string.hapus, (dialog, which) -> {
                    customerDao.delete(customerId);
                    Toast.makeText(this, "Pelanggan dihapus", Toast.LENGTH_SHORT).show();
                    finish();
                })
                .setNegativeButton(R.string.batal, null)
                .show();
    }
}
