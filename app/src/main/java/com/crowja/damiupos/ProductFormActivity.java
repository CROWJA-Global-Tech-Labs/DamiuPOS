package com.crowja.damiupos;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.GridLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.ProductDao;
import com.crowja.damiupos.model.Product;
import com.google.android.material.textfield.TextInputEditText;

import java.text.NumberFormat;
import java.util.Locale;

public class ProductFormActivity extends AppCompatActivity {

    private static final String[] COLOR_PALETTE = {
            "#1565C0", "#0277BD", "#00838F", "#00695C",
            "#2E7D32", "#558B2F", "#F9A825", "#FF8F00",
            "#EF6C00", "#D84315", "#C62828", "#AD1457",
            "#6A1B9A", "#4527A0", "#283593", "#37474F"
    };

    private TextInputEditText etNama, etHargaJual, etHargaModal;
    private TextView tvProfit;
    private GridLayout colorPalette;
    private ProductDao productDao;
    private long editId = -1;
    private String selectedColor = COLOR_PALETTE[0];
    private final NumberFormat nf = NumberFormat.getInstance(new Locale("id", "ID"));

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_product_form);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        productDao = new ProductDao(DatabaseHelper.getInstance(this));

        etNama = findViewById(R.id.etNama);
        etHargaJual = findViewById(R.id.etHargaJual);
        etHargaModal = findViewById(R.id.etHargaModal);
        tvProfit = findViewById(R.id.tvProfit);
        colorPalette = findViewById(R.id.colorPalette);

        setupColorPalette();

        // Check if editing
        editId = getIntent().getLongExtra("product_id", -1);
        if (editId != -1) {
            toolbar.setTitle("Edit Jenis Air");
            Product product = productDao.getById(editId);
            if (product != null) {
                etNama.setText(product.getName());
                etHargaJual.setText(String.valueOf((long) product.getHargaJual()));
                etHargaModal.setText(String.valueOf((long) product.getHargaModal()));
                if (product.getColor() != null) {
                    selectedColor = product.getColor();
                    highlightSelectedColor();
                }
            }
        }

        // Profit auto-calculate
        TextWatcher profitWatcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                updateProfit();
            }
        };
        etHargaJual.addTextChangedListener(profitWatcher);
        etHargaModal.addTextChangedListener(profitWatcher);
        updateProfit();

        findViewById(R.id.btnSimpan).setOnClickListener(v -> save());
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        if (editId != -1) {
            menu.add(0, 1, 0, R.string.hapus)
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == 1) {
            confirmDelete();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void updateProfit() {
        try {
            double jual = Double.parseDouble(etHargaJual.getText().toString().trim());
            double modal = Double.parseDouble(etHargaModal.getText().toString().trim());
            double profit = jual - modal;
            tvProfit.setText("Rp " + nf.format(profit));
            tvProfit.setTextColor(getResources().getColor(
                    profit >= 0 ? R.color.green : R.color.red));
        } catch (NumberFormatException e) {
            tvProfit.setText("Rp 0");
        }
    }

    private void save() {
        String nama = etNama.getText() != null ? etNama.getText().toString().trim() : "";
        String jualStr = etHargaJual.getText() != null ? etHargaJual.getText().toString().trim() : "";
        String modalStr = etHargaModal.getText() != null ? etHargaModal.getText().toString().trim() : "";

        if (nama.isEmpty()) {
            etNama.setError("Nama wajib diisi");
            etNama.requestFocus();
            return;
        }
        if (jualStr.isEmpty()) {
            etHargaJual.setError("Harga jual wajib diisi");
            etHargaJual.requestFocus();
            return;
        }
        if (modalStr.isEmpty()) {
            etHargaModal.setError("Harga modal wajib diisi");
            etHargaModal.requestFocus();
            return;
        }

        double hargaJual, hargaModal;
        try {
            hargaJual = Double.parseDouble(jualStr);
            hargaModal = Double.parseDouble(modalStr);
        } catch (NumberFormatException e) {
            Toast.makeText(this, "Format harga tidak valid", Toast.LENGTH_SHORT).show();
            return;
        }

        Product product = new Product(nama, hargaJual, hargaModal);
        product.setColor(selectedColor);

        if (editId != -1) {
            product.setId(editId);
            productDao.update(product);
            Toast.makeText(this, "Jenis air berhasil diupdate", Toast.LENGTH_SHORT).show();
        } else {
            productDao.insert(product);
            Toast.makeText(this, "Jenis air berhasil ditambahkan", Toast.LENGTH_SHORT).show();
        }

        finish();
    }

    private void setupColorPalette() {
        int size = (int) (36 * getResources().getDisplayMetrics().density);
        int margin = (int) (4 * getResources().getDisplayMetrics().density);

        for (String color : COLOR_PALETTE) {
            View swatch = new View(this);
            GridLayout.LayoutParams params = new GridLayout.LayoutParams();
            params.width = size;
            params.height = size;
            params.setMargins(margin, margin, margin, margin);
            swatch.setLayoutParams(params);

            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.OVAL);
            bg.setColor(Color.parseColor(color));
            swatch.setBackground(bg);
            swatch.setTag(color);

            swatch.setOnClickListener(v -> {
                selectedColor = color;
                highlightSelectedColor();
            });

            colorPalette.addView(swatch);
        }
        highlightSelectedColor();
    }

    private void highlightSelectedColor() {
        float density = getResources().getDisplayMetrics().density;
        for (int i = 0; i < colorPalette.getChildCount(); i++) {
            View child = colorPalette.getChildAt(i);
            String color = (String) child.getTag();
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.OVAL);
            bg.setColor(Color.parseColor(color));
            if (color.equals(selectedColor)) {
                bg.setStroke((int) (3 * density), Color.parseColor("#212121"));
            }
            child.setBackground(bg);
        }
    }

    private void confirmDelete() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.konfirmasi_hapus)
                .setMessage("Yakin ingin menghapus jenis air ini?")
                .setPositiveButton(R.string.hapus, (dialog, which) -> {
                    productDao.delete(editId);
                    Toast.makeText(this, "Jenis air dihapus", Toast.LENGTH_SHORT).show();
                    finish();
                })
                .setNegativeButton(R.string.batal, null)
                .show();
    }
}
