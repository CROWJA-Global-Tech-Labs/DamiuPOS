package com.damiu.pos;

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
import com.damiu.pos.db.CustomerDao;
import com.damiu.pos.db.DatabaseHelper;
import com.damiu.pos.db.TransactionDao;
import com.damiu.pos.model.Customer;
import com.damiu.pos.model.Transaction;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;

public class TransactionActivity extends AppCompatActivity {

    private MaterialButtonToggleGroup toggleType;
    private TextView tvSelectedCustomer, tvTotalHarga;
    private TextInputEditText etJumlahGalon, etHargaPerGalon, etCatatan;
    private TextInputLayout tilHarga;
    private View cardCustomer;

    private CustomerDao customerDao;
    private TransactionDao transactionDao;

    private long selectedCustomerId = -1;
    private String selectedCustomerName = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_transaction);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        DatabaseHelper dbHelper = DatabaseHelper.getInstance(this);
        customerDao = new CustomerDao(dbHelper);
        transactionDao = new TransactionDao(dbHelper);

        toggleType = findViewById(R.id.toggleType);
        tvSelectedCustomer = findViewById(R.id.tvSelectedCustomer);
        tvTotalHarga = findViewById(R.id.tvTotalHarga);
        etJumlahGalon = findViewById(R.id.etJumlahGalon);
        etHargaPerGalon = findViewById(R.id.etHargaPerGalon);
        etCatatan = findViewById(R.id.etCatatan);
        tilHarga = findViewById(R.id.tilHarga);
        cardCustomer = findViewById(R.id.cardCustomer);

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

        findViewById(R.id.btnSimpan).setOnClickListener(v -> save());

        updateTotal();
    }

    private void updateTypeUI(boolean isJual) {
        if (isJual) {
            tilHarga.setVisibility(View.VISIBLE);
        } else {
            tilHarga.setVisibility(View.GONE);
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
            double total = jumlah * harga;
            NumberFormat nf = NumberFormat.getInstance(new Locale("id", "ID"));
            tvTotalHarga.setText("Rp " + nf.format(total));
        } catch (NumberFormatException e) {
            tvTotalHarga.setText("Rp 0");
        }
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
            tvSelectedCustomer.setText(customer.getName());
            dialog.dismiss();
        });

        rvCustomers.setLayoutManager(new LinearLayoutManager(this));
        rvCustomers.setAdapter(adapter);

        // Load initial data
        adapter.setData(customerDao.getAll());

        // Search filter
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
            totalHarga = jumlah * hargaPerGalon;
        }

        Transaction trx = new Transaction();
        trx.setCustomerId(selectedCustomerId);
        trx.setType(isJual ? Transaction.TYPE_JUAL : Transaction.TYPE_KEMBALI);
        trx.setJumlahGalon(jumlah);
        trx.setHargaPerGalon(hargaPerGalon);
        trx.setTotalHarga(totalHarga);
        String catatan = etCatatan.getText() != null
                ? etCatatan.getText().toString().trim() : "";
        if (!catatan.isEmpty()) {
            trx.setCatatan(catatan);
        }

        transactionDao.insert(trx);

        String msg = isJual
                ? "Berhasil: Jual " + jumlah + " galon ke " + selectedCustomerName
                : "Berhasil: " + jumlah + " galon kembali dari " + selectedCustomerName;
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();

        finish();
    }
}
