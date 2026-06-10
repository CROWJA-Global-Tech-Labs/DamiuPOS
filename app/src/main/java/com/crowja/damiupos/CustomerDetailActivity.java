package com.crowja.damiupos;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.webkit.WebView;
import android.webkit.WebSettings;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.imageview.ShapeableImageView;

import java.io.File;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.crowja.damiupos.adapter.TransactionAdapter;
import com.crowja.damiupos.db.CustomerDao;
import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.TransactionDao;
import com.crowja.damiupos.model.Customer;
import com.crowja.damiupos.model.Transaction;

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
    private ShapeableImageView ivFoto;
    private MaterialCardView cardMap;
    private WebView webMap;
    private boolean mapLoaded;

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
        ivFoto = findViewById(R.id.ivFoto);
        cardMap = findViewById(R.id.cardMap);
        webMap = findViewById(R.id.webMap);
        tvGalonKeluar = findViewById(R.id.tvGalonKeluar);
        tvGalonKembali = findViewById(R.id.tvGalonKembali);
        tvSaldoGalon = findViewById(R.id.tvSaldoGalon);
        tvEmptyHistory = findViewById(R.id.tvEmptyHistory);
        rvTransactions = findViewById(R.id.rvTransactions);

        adapter = new TransactionAdapter(false);
        rvTransactions.setLayoutManager(new LinearLayoutManager(this));
        rvTransactions.setAdapter(adapter);

        adapter.setOnItemClickListener(trx -> {
            boolean hasStruk = Transaction.TYPE_JUAL.equals(trx.getType())
                    || trx.getTotalHarga() > 0; // ganti rugi KEMBALI
            if (!hasStruk) {
                Toast.makeText(this, "Struk hanya tersedia untuk transaksi penjualan atau ganti rugi",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            Intent intent = new Intent(this, ReceiptActivity.class);
            intent.putExtra(ReceiptActivity.EXTRA_TRANSACTION_ID, trx.getId());
            startActivity(intent);
        });

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

        loadPhoto(customer.getPhotoPath());
        loadMap(customer);

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

    private void loadPhoto(String path) {
        if (path != null && !path.isEmpty()) {
            File f = new File(path);
            if (f.exists()) {
                try {
                    ivFoto.setImageBitmap(BitmapFactory.decodeFile(path));
                    final String finalPath = path;
                    ivFoto.setOnClickListener(v -> showFullScreenPhoto(finalPath));
                    return;
                } catch (Exception ignored) {}
            }
        }
        ivFoto.setImageResource(android.R.drawable.ic_menu_gallery);
        ivFoto.setOnClickListener(null);
    }

    private void showFullScreenPhoto(String path) {
        android.graphics.Bitmap bmp = BitmapFactory.decodeFile(path);
        if (bmp == null) {
            Toast.makeText(this, "Foto tidak dapat dimuat", Toast.LENGTH_SHORT).show();
            return;
        }
        android.widget.ImageView iv = new android.widget.ImageView(this);
        iv.setImageBitmap(bmp);
        iv.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        iv.setBackgroundColor(android.graphics.Color.BLACK);
        iv.setAdjustViewBounds(true);

        android.app.Dialog dialog = new android.app.Dialog(this,
                android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        dialog.setContentView(iv, new android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT));
        iv.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void loadMap(Customer customer) {
        double lat = customer.getLatitude();
        double lng = customer.getLongitude();
        if (lat == 0 && lng == 0) {
            cardMap.setVisibility(View.GONE);
            return;
        }
        cardMap.setVisibility(View.VISIBLE);

        findViewById(R.id.btnNavigate).setOnClickListener(v -> {
            try {
                Uri geo = Uri.parse("geo:" + lat + "," + lng + "?q=" + lat + "," + lng
                        + "(" + Uri.encode(customer.getName()) + ")");
                Intent intent = new Intent(Intent.ACTION_VIEW, geo);
                startActivity(intent);
            } catch (Exception e) {
                Toast.makeText(this, "Aplikasi peta tidak tersedia", Toast.LENGTH_SHORT).show();
            }
        });

        if (mapLoaded) return;
        mapLoaded = true;

        WebSettings ws = webMap.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        webMap.setOnClickListener(v -> findViewById(R.id.btnNavigate).performClick());

        String html = "<!DOCTYPE html><html><head>"
                + "<meta charset='utf-8'>"
                + "<meta name='viewport' content='width=device-width, initial-scale=1.0, user-scalable=no'>"
                + "<link rel='stylesheet' href='https://unpkg.com/leaflet@1.9.4/dist/leaflet.css' />"
                + "<script src='https://unpkg.com/leaflet@1.9.4/dist/leaflet.js'></script>"
                + "<style>html,body,#map{height:100%;margin:0;padding:0;}</style>"
                + "</head><body><div id='map'></div>"
                + "<script>"
                + "var map = L.map('map', {zoomControl:false, attributionControl:false, dragging:false,"
                + " scrollWheelZoom:false, doubleClickZoom:false, touchZoom:false, boxZoom:false, keyboard:false})"
                + ".setView([" + lat + "," + lng + "], 16);"
                + "L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png').addTo(map);"
                + "L.marker([" + lat + "," + lng + "]).addTo(map);"
                + "</script></body></html>";
        webMap.loadDataWithBaseURL("https://openstreetmap.org/", html, "text/html", "utf-8", null);
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
