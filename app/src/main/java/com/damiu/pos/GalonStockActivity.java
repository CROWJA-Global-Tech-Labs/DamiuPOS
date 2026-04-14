package com.damiu.pos;

import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.damiu.pos.adapter.StockHistoryAdapter;
import com.damiu.pos.db.DatabaseHelper;
import com.damiu.pos.db.GalonStockDao;
import com.google.android.material.textfield.TextInputEditText;

import java.util.List;

public class GalonStockActivity extends AppCompatActivity {

    private TextView tvStokTersedia, tvStokMasuk, tvGalonKeluar, tvGalonKembali;
    private TextView tvEmptyHistory;
    private TextInputEditText etJumlahStok, etCatatanStok;
    private RecyclerView rvStockHistory;
    private StockHistoryAdapter adapter;
    private GalonStockDao galonStockDao;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_galon_stock);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        galonStockDao = new GalonStockDao(DatabaseHelper.getInstance(this));

        tvStokTersedia = findViewById(R.id.tvStokTersedia);
        tvStokMasuk = findViewById(R.id.tvStokMasuk);
        tvGalonKeluar = findViewById(R.id.tvGalonKeluar);
        tvGalonKembali = findViewById(R.id.tvGalonKembali);
        tvEmptyHistory = findViewById(R.id.tvEmptyHistory);
        etJumlahStok = findViewById(R.id.etJumlahStok);
        etCatatanStok = findViewById(R.id.etCatatanStok);
        rvStockHistory = findViewById(R.id.rvStockHistory);

        adapter = new StockHistoryAdapter((id, position) -> confirmDeleteStock(id));
        rvStockHistory.setLayoutManager(new LinearLayoutManager(this));
        rvStockHistory.setAdapter(adapter);

        findViewById(R.id.btnTambahStok).setOnClickListener(v -> addStock());

        refreshData();
    }

    private void addStock() {
        String jumlahStr = etJumlahStok.getText() != null ? etJumlahStok.getText().toString().trim() : "";
        String catatan = etCatatanStok.getText() != null ? etCatatanStok.getText().toString().trim() : "";

        if (jumlahStr.isEmpty()) {
            etJumlahStok.setError("Jumlah wajib diisi");
            etJumlahStok.requestFocus();
            return;
        }

        int jumlah;
        try {
            jumlah = Integer.parseInt(jumlahStr);
            if (jumlah <= 0) {
                etJumlahStok.setError("Jumlah harus lebih dari 0");
                etJumlahStok.requestFocus();
                return;
            }
        } catch (NumberFormatException e) {
            etJumlahStok.setError("Format tidak valid");
            etJumlahStok.requestFocus();
            return;
        }

        galonStockDao.addStock(jumlah, catatan.isEmpty() ? null : catatan);
        Toast.makeText(this, "Stok berhasil ditambahkan", Toast.LENGTH_SHORT).show();

        etJumlahStok.setText("");
        etCatatanStok.setText("");
        refreshData();
    }

    private void confirmDeleteStock(long id) {
        new AlertDialog.Builder(this)
                .setTitle("Hapus Stok")
                .setMessage("Yakin ingin menghapus data stok ini?")
                .setPositiveButton("Hapus", (dialog, which) -> {
                    galonStockDao.deleteStock(id);
                    Toast.makeText(this, "Data stok dihapus", Toast.LENGTH_SHORT).show();
                    refreshData();
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    private void refreshData() {
        int stokMasuk = galonStockDao.getTotalStokMasuk();
        int galonKeluar = galonStockDao.getTotalGalonKeluar();
        int galonKembali = galonStockDao.getTotalGalonKembali();
        int stokTersedia = stokMasuk - galonKeluar + galonKembali;

        tvStokTersedia.setText(String.valueOf(stokTersedia));
        tvStokMasuk.setText(String.valueOf(stokMasuk));
        tvGalonKeluar.setText(String.valueOf(galonKeluar));
        tvGalonKembali.setText(String.valueOf(galonKembali));

        // Color based on stock level
        if (stokTersedia <= 0) {
            tvStokTersedia.setTextColor(getResources().getColor(R.color.red));
        } else if (stokTersedia < 10) {
            tvStokTersedia.setTextColor(getResources().getColor(R.color.accent));
        } else {
            tvStokTersedia.setTextColor(getResources().getColor(R.color.primary));
        }

        // Stock history
        List<String[]> history = galonStockDao.getStockHistory();
        adapter.setData(history);

        if (history.isEmpty()) {
            tvEmptyHistory.setVisibility(TextView.VISIBLE);
            rvStockHistory.setVisibility(RecyclerView.GONE);
        } else {
            tvEmptyHistory.setVisibility(TextView.GONE);
            rvStockHistory.setVisibility(RecyclerView.VISIBLE);
        }
    }
}
