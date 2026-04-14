package com.damiu.pos;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.damiu.pos.adapter.CustomerAdapter;
import com.damiu.pos.adapter.ProductAdapter;
import com.damiu.pos.db.CustomerDao;
import com.damiu.pos.db.DatabaseHelper;
import com.damiu.pos.db.ProductDao;
import com.damiu.pos.db.SettingsDao;
import com.damiu.pos.db.TransactionDao;
import com.damiu.pos.model.Customer;
import com.damiu.pos.model.Product;
import com.damiu.pos.model.Transaction;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;

public class TransactionActivity extends AppCompatActivity {

    private MaterialButtonToggleGroup toggleType;
    private TextView tvSelectedCustomer, tvSelectedProduct, tvTotalHarga;
    private TextInputEditText etJumlahGalon, etHargaPerGalon, etOngkir, etCatatan;
    private TextInputLayout tilHarga, tilOngkir;
    private View cardCustomer, cardProduct;

    private CustomerDao customerDao;
    private ProductDao productDao;
    private TransactionDao transactionDao;
    private SettingsDao settingsDao;

    private long selectedCustomerId = -1;
    private String selectedCustomerName = "";
    private String selectedCustomerPhone = "";
    private long selectedProductId = 0;
    private String selectedProductName = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_transaction);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        DatabaseHelper dbHelper = DatabaseHelper.getInstance(this);
        customerDao = new CustomerDao(dbHelper);
        productDao = new ProductDao(dbHelper);
        transactionDao = new TransactionDao(dbHelper);
        settingsDao = new SettingsDao(dbHelper);

        toggleType = findViewById(R.id.toggleType);
        tvSelectedCustomer = findViewById(R.id.tvSelectedCustomer);
        tvSelectedProduct = findViewById(R.id.tvSelectedProduct);
        tvTotalHarga = findViewById(R.id.tvTotalHarga);
        etJumlahGalon = findViewById(R.id.etJumlahGalon);
        etHargaPerGalon = findViewById(R.id.etHargaPerGalon);
        etOngkir = findViewById(R.id.etOngkir);
        etCatatan = findViewById(R.id.etCatatan);
        tilHarga = findViewById(R.id.tilHarga);
        tilOngkir = findViewById(R.id.tilOngkir);
        cardCustomer = findViewById(R.id.cardCustomer);
        cardProduct = findViewById(R.id.cardProduct);

        // Set default ongkir from settings
        double defaultOngkir = settingsDao.getDefaultOngkir();
        if (defaultOngkir > 0) {
            etOngkir.setText(String.valueOf((long) defaultOngkir));
        }

        // Pre-select type from intent
        String type = getIntent().getStringExtra("type");
        if (Transaction.TYPE_KEMBALI.equals(type)) {
            toggleType.check(R.id.btnTypeKembali);
            updateTypeUI(false);
        }

        // Pre-select customer from intent
        long preCustomerId = getIntent().getLongExtra("customer_id", -1);
        if (preCustomerId != -1) {
            Customer c = customerDao.getById(preCustomerId);
            if (c != null) {
                selectedCustomerId = c.getId();
                selectedCustomerName = c.getName();
                selectedCustomerPhone = c.getPhone();
                tvSelectedCustomer.setText(c.getName());
            }
        }

        // Toggle type listener
        toggleType.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (isChecked) {
                updateTypeUI(checkedId == R.id.btnTypeJual);
            }
        });

        // Customer selector
        cardCustomer.setOnClickListener(v -> showCustomerPicker());

        // Product selector
        cardProduct.setOnClickListener(v -> showProductPicker());

        // Price calculation
        TextWatcher calcWatcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                updateTotal();
            }
        };
        etJumlahGalon.addTextChangedListener(calcWatcher);
        etHargaPerGalon.addTextChangedListener(calcWatcher);
        etOngkir.addTextChangedListener(calcWatcher);

        findViewById(R.id.btnSimpan).setOnClickListener(v -> save());

        updateTotal();
    }

    private void updateTypeUI(boolean isJual) {
        if (isJual) {
            tilHarga.setVisibility(View.VISIBLE);
            tilOngkir.setVisibility(View.VISIBLE);
            cardProduct.setVisibility(View.VISIBLE);
        } else {
            tilHarga.setVisibility(View.GONE);
            tilOngkir.setVisibility(View.GONE);
            cardProduct.setVisibility(View.GONE);
        }
        updateTotal();
    }

    private boolean isJualSelected() {
        return toggleType.getCheckedButtonId() == R.id.btnTypeJual;
    }

    private void updateTotal() {
        if (!isJualSelected()) {
            tvTotalHarga.setText("Rp 0");
            return;
        }
        try {
            int jumlah = Integer.parseInt(etJumlahGalon.getText().toString().trim());
            double harga = Double.parseDouble(etHargaPerGalon.getText().toString().trim());
            double ongkir = 0;
            String ongkirStr = etOngkir.getText().toString().trim();
            if (!ongkirStr.isEmpty()) {
                ongkir = Double.parseDouble(ongkirStr);
            }
            double total = jumlah * (harga + ongkir);
            NumberFormat nf = NumberFormat.getInstance(new Locale("id", "ID"));
            tvTotalHarga.setText("Rp " + nf.format(total));
        } catch (NumberFormatException e) {
            tvTotalHarga.setText("Rp 0");
        }
    }

    private void showProductPicker() {
        List<Product> products = productDao.getAll();
        if (products.isEmpty()) {
            Toast.makeText(this, "Belum ada jenis air. Tambahkan di menu Jenis Air Minum.", Toast.LENGTH_LONG).show();
            return;
        }

        String[] names = new String[products.size()];
        for (int i = 0; i < products.size(); i++) {
            Product p = products.get(i);
            NumberFormat nf = NumberFormat.getInstance(new Locale("id", "ID"));
            names[i] = p.getName() + " — Rp " + nf.format(p.getHargaJual());
        }

        new AlertDialog.Builder(this)
                .setTitle("Pilih Jenis Air")
                .setItems(names, (dialog, which) -> {
                    Product selected = products.get(which);
                    selectedProductId = selected.getId();
                    selectedProductName = selected.getName();
                    tvSelectedProduct.setText(selected.getName());
                    // Auto-fill harga jual
                    etHargaPerGalon.setText(String.valueOf((long) selected.getHargaJual()));
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    private void showCustomerPicker() {
        View dialogView = LayoutInflater.from(this)
                .inflate(R.layout.dialog_select_customer, null);

        RecyclerView rvCustomers = dialogView.findViewById(R.id.rvCustomers);
        EditText etSearch = dialogView.findViewById(R.id.etSearchCustomer);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.pilih_pelanggan)
                .setView(dialogView)
                .setNegativeButton(R.string.batal, null)
                .create();

        CustomerAdapter adapter = new CustomerAdapter(customer -> {
            selectedCustomerId = customer.getId();
            selectedCustomerName = customer.getName();
            selectedCustomerPhone = customer.getPhone();
            tvSelectedCustomer.setText(customer.getName());
            dialog.dismiss();
        });

        rvCustomers.setLayoutManager(new LinearLayoutManager(this));
        rvCustomers.setAdapter(adapter);

        adapter.setData(customerDao.getAll());

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                String keyword = s.toString().trim();
                if (keyword.isEmpty()) {
                    adapter.setData(customerDao.getAll());
                } else {
                    adapter.setData(customerDao.search(keyword));
                }
            }
        });

        dialog.show();
    }

    private void save() {
        if (selectedCustomerId == -1) {
            Toast.makeText(this, "Pilih pelanggan terlebih dahulu", Toast.LENGTH_SHORT).show();
            return;
        }

        String jumlahStr = etJumlahGalon.getText() != null
                ? etJumlahGalon.getText().toString().trim() : "";
        if (jumlahStr.isEmpty()) {
            etJumlahGalon.setError("Wajib diisi");
            return;
        }
        int jumlah;
        try {
            jumlah = Integer.parseInt(jumlahStr);
        } catch (NumberFormatException e) {
            etJumlahGalon.setError("Angka tidak valid");
            return;
        }
        if (jumlah <= 0) {
            etJumlahGalon.setError("Harus lebih dari 0");
            return;
        }

        boolean isJual = isJualSelected();
        double hargaPerGalon = 0;
        double ongkir = 0;
        double totalHarga = 0;

        if (isJual) {
            String hargaStr = etHargaPerGalon.getText() != null
                    ? etHargaPerGalon.getText().toString().trim() : "";
            if (hargaStr.isEmpty()) {
                etHargaPerGalon.setError("Wajib diisi");
                return;
            }
            try {
                hargaPerGalon = Double.parseDouble(hargaStr);
            } catch (NumberFormatException e) {
                etHargaPerGalon.setError("Angka tidak valid");
                return;
            }
            String ongkirStr = etOngkir.getText() != null
                    ? etOngkir.getText().toString().trim() : "";
            if (!ongkirStr.isEmpty()) {
                try {
                    ongkir = Double.parseDouble(ongkirStr);
                } catch (NumberFormatException ignored) {}
            }
            totalHarga = jumlah * (hargaPerGalon + ongkir);
        }

        // Auto-exchange: if selling to customer who has gallons out, auto-return same qty
        if (isJual) {
            Customer cust = customerDao.getById(selectedCustomerId);
            if (cust != null && cust.getSaldoGalon() > 0) {
                int saldo = cust.getSaldoGalon();
                int autoReturn = Math.min(jumlah, saldo);
                if (autoReturn > 0) {
                    Transaction kembali = new Transaction();
                    kembali.setCustomerId(selectedCustomerId);
                    kembali.setType(Transaction.TYPE_KEMBALI);
                    kembali.setJumlahGalon(autoReturn);
                    kembali.setCatatan("Tukar galon (otomatis)");
                    transactionDao.insert(kembali);
                }
            }
        }

        Transaction trx = new Transaction();
        trx.setCustomerId(selectedCustomerId);
        trx.setProductId(selectedProductId);
        trx.setType(isJual ? Transaction.TYPE_JUAL : Transaction.TYPE_KEMBALI);
        trx.setJumlahGalon(jumlah);
        trx.setHargaPerGalon(hargaPerGalon);
        trx.setOngkir(ongkir);
        trx.setTotalHarga(totalHarga);
        String catatan = etCatatan.getText() != null
                ? etCatatan.getText().toString().trim() : "";
        if (!catatan.isEmpty()) {
            trx.setCatatan(catatan);
        }

        transactionDao.insert(trx);

        if (isJual) {
            // Show receipt for sales
            Intent receiptIntent = new Intent(this, ReceiptActivity.class);
            receiptIntent.putExtra(ReceiptActivity.EXTRA_CUSTOMER_NAME, selectedCustomerName);
            receiptIntent.putExtra(ReceiptActivity.EXTRA_CUSTOMER_PHONE, selectedCustomerPhone);
            receiptIntent.putExtra(ReceiptActivity.EXTRA_PRODUCT_NAME, selectedProductName);
            receiptIntent.putExtra(ReceiptActivity.EXTRA_JUMLAH, jumlah);
            receiptIntent.putExtra(ReceiptActivity.EXTRA_HARGA_PER_GALON, hargaPerGalon);
            receiptIntent.putExtra(ReceiptActivity.EXTRA_ONGKIR, ongkir);
            receiptIntent.putExtra(ReceiptActivity.EXTRA_TOTAL_HARGA, totalHarga);
            String catatanStr = etCatatan.getText() != null ? etCatatan.getText().toString().trim() : "";
            receiptIntent.putExtra(ReceiptActivity.EXTRA_CATATAN, catatanStr);
            startActivity(receiptIntent);
            finish();
        } else {
            Toast.makeText(this, "Berhasil: " + jumlah + " galon kembali dari " + selectedCustomerName,
                    Toast.LENGTH_SHORT).show();
            finish();
        }
    }
}
