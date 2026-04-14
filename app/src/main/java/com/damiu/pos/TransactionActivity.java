package com.damiu.pos;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
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
import com.damiu.pos.db.ProductDao;
import com.damiu.pos.db.SettingsDao;
import com.damiu.pos.db.TransactionDao;
import com.damiu.pos.model.Customer;
import com.damiu.pos.model.Product;
import com.damiu.pos.model.Transaction;
import com.damiu.pos.model.TransactionItem;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class TransactionActivity extends AppCompatActivity {

    private MaterialButtonToggleGroup toggleType, toggleOngkirMode, toggleOwnership;
    private TextView tvSelectedCustomer, tvTotalHarga, tvEmptyItems;
    private TextInputEditText etJumlahKembali, etOngkir, etCatatan;
    private TextInputEditText etHargaGantiRugi, etJumlahRusak;
    private TextInputEditText etHargaBotol;
    private TextInputLayout tilOngkir, tilJumlahKembali, tilHargaBotol;
    private View ongkirModeContainer, cardCustomer, cardItems, cardOwnership, gantiRugiContainer;
    private LinearLayout llItems;

    private boolean syncingKembali = false;
    private boolean userEditedKembali = false;
    private boolean syncingOngkir = false;
    private boolean userEditedOngkir = false;

    private CustomerDao customerDao;
    private ProductDao productDao;
    private TransactionDao transactionDao;
    private SettingsDao settingsDao;

    private long selectedCustomerId = -1;
    private String selectedCustomerName = "";
    private String selectedCustomerPhone = "";

    private final List<TransactionItem> items = new ArrayList<>();

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
        tvTotalHarga = findViewById(R.id.tvTotalHarga);
        etJumlahKembali = findViewById(R.id.etJumlahKembali);
        etOngkir = findViewById(R.id.etOngkir);
        etCatatan = findViewById(R.id.etCatatan);
        tilOngkir = findViewById(R.id.tilOngkir);
        tilJumlahKembali = findViewById(R.id.tilJumlahKembali);
        toggleOngkirMode = findViewById(R.id.toggleOngkirMode);
        ongkirModeContainer = findViewById(R.id.ongkirModeContainer);
        cardCustomer = findViewById(R.id.cardCustomer);
        cardItems = findViewById(R.id.cardItems);
        llItems = findViewById(R.id.llItems);
        tvEmptyItems = findViewById(R.id.tvEmptyItems);
        gantiRugiContainer = findViewById(R.id.gantiRugiContainer);
        etHargaGantiRugi = findViewById(R.id.etHargaGantiRugi);
        etJumlahRusak = findViewById(R.id.etJumlahRusak);
        cardOwnership = findViewById(R.id.cardOwnership);
        toggleOwnership = findViewById(R.id.toggleOwnership);
        tilHargaBotol = findViewById(R.id.tilHargaBotol);
        etHargaBotol = findViewById(R.id.etHargaBotol);

        // Prefill ganti rugi price from settings (default 35.000)
        etHargaGantiRugi.setText(String.valueOf((long) settingsDao.getHargaBotolGalon()));

        // Prefill harga botol dari settings
        etHargaBotol.setText(String.valueOf((long) settingsDao.getHargaBotolGalon()));

        // Restore last ownership state
        String lastOwnership = settingsDao.getLastGalonOwnership();
        if (Transaction.OWNERSHIP_BELI.equals(lastOwnership)) {
            toggleOwnership.check(R.id.btnOwnershipBeli);
        } else {
            toggleOwnership.check(R.id.btnOwnershipPinjam);
        }

        toggleOwnership.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (isChecked) {
                updateOwnershipUI();
                updateTotal();
            }
        });

        toggleOngkirMode.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (isChecked) updateOngkirUI();
        });

        // Pre-select type from intent
        String type = getIntent().getStringExtra("type");
        if (Transaction.TYPE_KEMBALI.equals(type)) {
            toggleType.check(R.id.btnTypeKembali);
        }

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

        toggleType.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (isChecked) updateTypeUI(checkedId == R.id.btnTypeJual);
        });

        cardCustomer.setOnClickListener(v -> showCustomerPicker());
        findViewById(R.id.btnAddItem).setOnClickListener(v -> showItemDialog(null, -1));

        TextWatcher calcWatcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) { updateTotal(); }
        };
        etOngkir.addTextChangedListener(calcWatcher);
        etHargaGantiRugi.addTextChangedListener(calcWatcher);
        etJumlahKembali.addTextChangedListener(calcWatcher);
        etJumlahRusak.addTextChangedListener(calcWatcher);
        etHargaBotol.addTextChangedListener(calcWatcher);

        etOngkir.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                if (!syncingOngkir) userEditedOngkir = true;
            }
        });

        etJumlahKembali.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                if (!syncingKembali) userEditedKembali = true;
            }
        });

        findViewById(R.id.btnSimpan).setOnClickListener(v -> save());

        refreshItemsView();
        updateTypeUI(isJualSelected());
        updateOngkirUI();
        updateTotal();
    }

    private void updateTypeUI(boolean isJual) {
        if (isJual) {
            cardItems.setVisibility(View.VISIBLE);
            tilJumlahKembali.setVisibility(View.VISIBLE);
            ongkirModeContainer.setVisibility(View.VISIBLE);
            gantiRugiContainer.setVisibility(View.GONE);
            cardOwnership.setVisibility(View.VISIBLE);
            updateOngkirUI();
            updateOwnershipUI();
        } else {
            cardItems.setVisibility(View.GONE);
            tilJumlahKembali.setVisibility(View.VISIBLE);
            tilJumlahKembali.setHint("Jumlah Botol Galon Kembali");
            tilOngkir.setVisibility(View.GONE);
            ongkirModeContainer.setVisibility(View.GONE);
            gantiRugiContainer.setVisibility(View.VISIBLE);
            cardOwnership.setVisibility(View.GONE);
        }
        updateTotal();
    }

    private boolean isBeliSelected() {
        return toggleOwnership.getCheckedButtonId() == R.id.btnOwnershipBeli;
    }

    private void updateOwnershipUI() {
        tilHargaBotol.setVisibility(isBeliSelected() ? View.VISIBLE : View.GONE);
    }

    private boolean isJualSelected() {
        return toggleType.getCheckedButtonId() == R.id.btnTypeJual;
    }

    private String getSelectedOngkirType() {
        int id = toggleOngkirMode.getCheckedButtonId();
        if (id == R.id.btnOngkirNone) return Transaction.ONGKIR_NONE;
        if (id == R.id.btnOngkirBorongan) return Transaction.ONGKIR_BORONGAN;
        return Transaction.ONGKIR_PER_GALON;
    }

    private void updateOngkirUI() {
        String mode = getSelectedOngkirType();
        if (Transaction.ONGKIR_NONE.equals(mode)) {
            tilOngkir.setVisibility(View.GONE);
            userEditedOngkir = false;
        } else {
            tilOngkir.setVisibility(View.VISIBLE);
            if (Transaction.ONGKIR_BORONGAN.equals(mode)) {
                tilOngkir.setHint("Ongkos Kirim Borongan");
                userEditedOngkir = false;
                applyBoronganDefault();
            } else {
                tilOngkir.setHint("Ongkos Kirim / Botol Galon");
                userEditedOngkir = false;
                double defaultOngkir = settingsDao.getDefaultOngkir();
                syncingOngkir = true;
                etOngkir.setText(defaultOngkir > 0
                        ? String.valueOf((long) defaultOngkir) : "");
                syncingOngkir = false;
            }
        }
        updateTotal();
    }

    private int getTotalJumlah() {
        int sum = 0;
        for (TransactionItem it : items) sum += it.jumlah;
        return sum;
    }

    private void applyBoronganDefault() {
        double defaultOngkir = settingsDao.getDefaultOngkir();
        double borongan = getTotalJumlah() * defaultOngkir;
        syncingOngkir = true;
        etOngkir.setText(borongan > 0 ? String.valueOf((long) borongan) : "");
        syncingOngkir = false;
    }

    private void updateTotal() {
        if (!isJualSelected()) {
            // KEMBALI mode: total = jumlah_rusak * harga ganti rugi
            double harga = parseDoubleOr(etHargaGantiRugi, 0);
            int jumlahKembali = parseIntOr(etJumlahKembali, 0);
            int jumlahRusak = parseIntOr(etJumlahRusak, -1);
            if (jumlahRusak < 0) jumlahRusak = jumlahKembali;
            if (jumlahRusak > jumlahKembali) jumlahRusak = jumlahKembali;
            double total = harga * jumlahRusak;
            NumberFormat nfK = NumberFormat.getInstance(new Locale("id", "ID"));
            tvTotalHarga.setText("Rp " + nfK.format(total));
            return;
        }
        double subtotal = 0;
        for (TransactionItem it : items) subtotal += it.getSubtotal();

        double ongkir = 0;
        String ongkirStr = etOngkir.getText() != null
                ? etOngkir.getText().toString().trim() : "";
        if (!ongkirStr.isEmpty()) {
            try { ongkir = Double.parseDouble(ongkirStr); } catch (NumberFormatException ignored) {}
        }
        String mode = getSelectedOngkirType();
        double total;
        if (Transaction.ONGKIR_BORONGAN.equals(mode)) {
            total = subtotal + ongkir;
        } else if (Transaction.ONGKIR_NONE.equals(mode)) {
            total = subtotal;
        } else {
            total = subtotal + ongkir * getTotalJumlah();
        }
        // Tambahkan harga botol × jumlah jika pelanggan membeli botol galon
        if (isBeliSelected()) {
            double hargaBotol = parseDoubleOr(etHargaBotol, 0);
            total += hargaBotol * getTotalJumlah();
        }
        NumberFormat nf = NumberFormat.getInstance(new Locale("id", "ID"));
        tvTotalHarga.setText("Rp " + nf.format(total));
    }

    // --- Items UI ---

    private void refreshItemsView() {
        llItems.removeAllViews();
        NumberFormat nf = NumberFormat.getInstance(new Locale("id", "ID"));
        for (int i = 0; i < items.size(); i++) {
            final int idx = i;
            TransactionItem it = items.get(i);
            View row = LayoutInflater.from(this).inflate(R.layout.item_trx_line, llItems, false);
            TextView tvName = row.findViewById(R.id.tvItemName);
            TextView tvCalc = row.findViewById(R.id.tvItemCalc);
            TextView tvSub = row.findViewById(R.id.tvItemSubtotal);
            ImageButton btnDel = row.findViewById(R.id.btnItemDelete);
            tvName.setText(it.productName);
            tvCalc.setText(it.jumlah + " x Rp " + nf.format(it.hargaPerGalon));
            tvSub.setText("Rp " + nf.format(it.getSubtotal()));
            row.setOnClickListener(v -> showItemDialog(items.get(idx), idx));
            btnDel.setOnClickListener(v -> {
                items.remove(idx);
                refreshItemsView();
                syncKembaliToTotalJumlah();
                if (Transaction.ONGKIR_BORONGAN.equals(getSelectedOngkirType()) && !userEditedOngkir) {
                    applyBoronganDefault();
                }
                updateTotal();
            });
            llItems.addView(row);
        }
        tvEmptyItems.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void syncKembaliToTotalJumlah() {
        if (!userEditedKembali) {
            syncingKembali = true;
            etJumlahKembali.setText(String.valueOf(getTotalJumlah()));
            syncingKembali = false;
        }
    }

    private void showItemDialog(TransactionItem editing, int editIdx) {
        List<Product> products = productDao.getAll();
        if (products.isEmpty()) {
            Toast.makeText(this,
                    "Belum ada jenis air. Tambahkan di menu Jenis Air Minum.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_item_trx, null);
        TextView tvProduct = dialogView.findViewById(R.id.tvProduct);
        EditText etQty = dialogView.findViewById(R.id.etQty);
        EditText etPrice = dialogView.findViewById(R.id.etPrice);

        final long[] chosenPid = {0};
        final String[] chosenName = {""};

        if (editing != null) {
            chosenPid[0] = editing.productId;
            chosenName[0] = editing.productName;
            tvProduct.setText(editing.productName);
            etQty.setText(String.valueOf(editing.jumlah));
            etPrice.setText(String.valueOf((long) editing.hargaPerGalon));
        } else {
            etQty.setText("1");
        }

        tvProduct.setOnClickListener(v -> {
            CharSequence[] names = new CharSequence[products.size()];
            NumberFormat nf = NumberFormat.getInstance(new Locale("id", "ID"));
            for (int i = 0; i < products.size(); i++) {
                Product p = products.get(i);
                int color;
                try {
                    color = p.getColor() != null && !p.getColor().isEmpty()
                            ? Color.parseColor(p.getColor())
                            : Color.parseColor("#1565C0");
                } catch (Exception e) {
                    color = Color.parseColor("#1565C0");
                }
                SpannableStringBuilder sb = new SpannableStringBuilder();
                int dotStart = sb.length();
                sb.append("\u25CF  ");
                sb.setSpan(new ForegroundColorSpan(color), dotStart, dotStart + 1,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new RelativeSizeSpan(1.4f), dotStart, dotStart + 1,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                int nameStart = sb.length();
                sb.append(p.getName());
                sb.setSpan(new ForegroundColorSpan(color), nameStart, sb.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new StyleSpan(android.graphics.Typeface.BOLD),
                        nameStart, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.append("  — Rp ").append(nf.format(p.getHargaJual()));
                names[i] = sb;
            }
            new AlertDialog.Builder(this)
                    .setTitle("Pilih Jenis Air")
                    .setItems(names, (d, w) -> {
                        Product p = products.get(w);
                        chosenPid[0] = p.getId();
                        chosenName[0] = p.getName();
                        tvProduct.setText(p.getName());
                        if (etPrice.getText().toString().trim().isEmpty()) {
                            etPrice.setText(String.valueOf((long) p.getHargaJual()));
                        }
                    })
                    .show();
        });

        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(editing == null ? "Tambah Item" : "Edit Item")
                .setView(dialogView)
                .setPositiveButton(editing == null ? "Tambah" : "Simpan", null)
                .setNegativeButton("Batal", null);
        AlertDialog dialog = b.create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    if (chosenPid[0] <= 0) {
                        Toast.makeText(this, "Pilih jenis air", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    int qty;
                    double price;
                    try {
                        qty = Integer.parseInt(etQty.getText().toString().trim());
                        price = Double.parseDouble(etPrice.getText().toString().trim());
                    } catch (NumberFormatException e) {
                        Toast.makeText(this, "Angka tidak valid", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (qty <= 0) {
                        Toast.makeText(this, "Jumlah harus > 0", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    TransactionItem newItem = new TransactionItem(
                            chosenPid[0], chosenName[0], qty, price);
                    if (editIdx >= 0) {
                        items.set(editIdx, newItem);
                    } else {
                        items.add(newItem);
                    }
                    refreshItemsView();
                    syncKembaliToTotalJumlah();
                    if (Transaction.ONGKIR_BORONGAN.equals(getSelectedOngkirType())
                            && !userEditedOngkir) {
                        applyBoronganDefault();
                    }
                    updateTotal();
                    dialog.dismiss();
                }));
        dialog.show();
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
            @Override public void afterTextChanged(Editable s) {
                String k = s.toString().trim();
                adapter.setData(k.isEmpty() ? customerDao.getAll() : customerDao.search(k));
            }
        });
        dialog.show();
    }

    private void save() {
        if (selectedCustomerId == -1) {
            Toast.makeText(this, "Pilih pelanggan terlebih dahulu", Toast.LENGTH_SHORT).show();
            return;
        }

        boolean isJual = isJualSelected();
        int totalJumlah = 0;
        double subtotal = 0;
        double ongkir = 0;
        double totalHarga = 0;
        String ongkirType = Transaction.ONGKIR_NONE;

        if (isJual) {
            if (items.isEmpty()) {
                Toast.makeText(this, "Tambahkan minimal 1 item", Toast.LENGTH_SHORT).show();
                return;
            }
            for (TransactionItem it : items) {
                totalJumlah += it.jumlah;
                subtotal += it.getSubtotal();
            }
            ongkirType = getSelectedOngkirType();
            if (!Transaction.ONGKIR_NONE.equals(ongkirType)) {
                String s = etOngkir.getText() != null ? etOngkir.getText().toString().trim() : "";
                if (!s.isEmpty()) {
                    try { ongkir = Double.parseDouble(s); } catch (NumberFormatException ignored) {}
                }
            }
            if (Transaction.ONGKIR_BORONGAN.equals(ongkirType)) {
                totalHarga = subtotal + ongkir;
            } else if (Transaction.ONGKIR_NONE.equals(ongkirType)) {
                totalHarga = subtotal;
            } else {
                totalHarga = subtotal + ongkir * totalJumlah;
            }
            // Tambahkan harga botol × jumlah jika pelanggan membeli botol galon
            if (isBeliSelected()) {
                double hargaBotol = parseDoubleOr(etHargaBotol, 0);
                if (hargaBotol <= 0) {
                    etHargaBotol.setError("Harga botol harus > 0");
                    return;
                }
                totalHarga += hargaBotol * totalJumlah;
            }
        } else {
            String qtyStr = etJumlahKembali.getText() != null
                    ? etJumlahKembali.getText().toString().trim() : "";
            if (qtyStr.isEmpty()) {
                etJumlahKembali.setError("Wajib diisi");
                return;
            }
            try {
                totalJumlah = Integer.parseInt(qtyStr);
            } catch (NumberFormatException e) {
                etJumlahKembali.setError("Angka tidak valid");
                return;
            }
            if (totalJumlah <= 0) {
                etJumlahKembali.setError("Harus > 0");
                return;
            }
            // Ganti rugi galon rusak
            double hargaGR = parseDoubleOr(etHargaGantiRugi, 0);
            if (hargaGR > 0) {
                int rusak = parseIntOr(etJumlahRusak, -1);
                if (rusak < 0) rusak = totalJumlah;
                if (rusak <= 0) {
                    etJumlahRusak.setError("Jumlah botol galon rusak harus > 0");
                    return;
                }
                if (rusak > totalJumlah) {
                    etJumlahRusak.setError("Tidak boleh > jumlah kembali");
                    return;
                }
                totalHarga = hargaGR * rusak;
            }
        }

        int jumlahKembali = 0;
        if (isJual) {
            String k = etJumlahKembali.getText() != null ? etJumlahKembali.getText().toString().trim() : "";
            if (!k.isEmpty()) {
                try {
                    jumlahKembali = Integer.parseInt(k);
                } catch (NumberFormatException e) {
                    etJumlahKembali.setError("Angka tidak valid");
                    return;
                }
            }
            if (jumlahKembali < 0) {
                etJumlahKembali.setError("Tidak boleh negatif");
                return;
            }
        }

        // Confirmation dialog
        NumberFormat nf = NumberFormat.getInstance(new Locale("id", "ID"));
        SpannableStringBuilder msg = new SpannableStringBuilder();
        int colorKeluar = Color.parseColor("#D32F2F");
        int colorKembali = Color.parseColor("#2E7D32");
        if (isJual) {
            if (isBeliSelected()) {
                appendStyled(msg, "Botol Galon Dibeli: " + totalJumlah + " botol\n",
                        Color.parseColor("#1565C0"), true, 1.15f);
            } else {
                appendStyled(msg, "Botol Galon Keluar (Pinjam): " + totalJumlah + " botol galon\n", colorKeluar, true, 1.15f);
            }
            appendStyled(msg, "Botol Galon Kembali: " + jumlahKembali + " botol galon\n\n", colorKembali, true, 1.15f);
        } else {
            appendStyled(msg, "Botol Galon Kembali: " + totalJumlah + " botol galon\n", colorKembali, true, 1.15f);
            if (totalHarga > 0) {
                double hargaGR = parseDoubleOr(etHargaGantiRugi, 0);
                int rusak = parseIntOr(etJumlahRusak, -1);
                if (rusak < 0) rusak = totalJumlah;
                appendStyled(msg, "Botol Galon Rusak: " + rusak + " × Rp " + nf.format(hargaGR) + "\n",
                        colorKeluar, true, 1.05f);
                appendStyled(msg, "Ganti Rugi: Rp " + nf.format(totalHarga) + "\n\n",
                        Color.parseColor("#1565C0"), true, 1.1f);
            } else {
                msg.append("\n");
            }
        }
        msg.append("Pelanggan: ").append(selectedCustomerName).append("\n");
        if (isJual) {
            for (TransactionItem it : items) {
                msg.append("• ").append(it.productName).append(": ")
                        .append(String.valueOf(it.jumlah)).append(" x Rp ")
                        .append(nf.format(it.hargaPerGalon)).append("\n");
            }
            if (Transaction.ONGKIR_PER_GALON.equals(ongkirType) && ongkir > 0) {
                msg.append("Ongkir/botol galon: Rp ").append(nf.format(ongkir)).append("\n");
            } else if (Transaction.ONGKIR_BORONGAN.equals(ongkirType) && ongkir > 0) {
                msg.append("Ongkir borongan: Rp ").append(nf.format(ongkir)).append("\n");
            } else {
                msg.append("Ongkir: -\n");
            }
            if (isBeliSelected()) {
                double hb = parseDoubleOr(etHargaBotol, 0);
                msg.append("Beli Botol: ").append(String.valueOf(totalJumlah))
                        .append(" × Rp ").append(nf.format(hb)).append("\n");
            }
            msg.append("\n");
            appendStyled(msg, "Total: Rp " + nf.format(totalHarga),
                    Color.parseColor("#1565C0"), true, 1.1f);
        }

        final int fTotalJumlah = totalJumlah;
        final int fJumlahKembali = jumlahKembali;
        final double fOngkir = ongkir;
        final double fTotal = totalHarga;
        final boolean fIsJual = isJual;
        final String fOngkirType = ongkirType;
        final String fOwnership = (isJual && isBeliSelected())
                ? Transaction.OWNERSHIP_BELI : Transaction.OWNERSHIP_PINJAM;
        final double fHargaBotol = (isJual && isBeliSelected())
                ? parseDoubleOr(etHargaBotol, 0) : 0;

        new AlertDialog.Builder(this)
                .setTitle("Konfirmasi Transaksi")
                .setMessage(msg)
                .setPositiveButton("Simpan", (d, w) -> doSave(fIsJual, fTotalJumlah,
                        fOngkir, fTotal, fJumlahKembali, fOngkirType,
                        fOwnership, fHargaBotol))
                .setNegativeButton("Batal", null)
                .show();
    }

    private double parseDoubleOr(TextInputEditText et, double def) {
        if (et == null || et.getText() == null) return def;
        String s = et.getText().toString().trim();
        if (s.isEmpty()) return def;
        try { return Double.parseDouble(s); } catch (NumberFormatException e) { return def; }
    }

    private int parseIntOr(TextInputEditText et, int def) {
        if (et == null || et.getText() == null) return def;
        String s = et.getText().toString().trim();
        if (s.isEmpty()) return def;
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return def; }
    }

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

    private void doSave(boolean isJual, int totalJumlah, double ongkir,
                        double totalHarga, int jumlahKembali, String ongkirType,
                        String ownership, double hargaBotol) {
        // Persist last ownership selection for next transaction
        if (isJual && ownership != null) {
            settingsDao.setLastGalonOwnership(ownership);
        }
        if (isJual && jumlahKembali > 0) {
            Transaction kembali = new Transaction();
            kembali.setCustomerId(selectedCustomerId);
            kembali.setType(Transaction.TYPE_KEMBALI);
            kembali.setJumlahGalon(jumlahKembali);
            kembali.setCatatan("Tukar botol galon");
            transactionDao.insert(kembali);
        }

        Transaction trx = new Transaction();
        trx.setCustomerId(selectedCustomerId);
        trx.setType(isJual ? Transaction.TYPE_JUAL : Transaction.TYPE_KEMBALI);
        trx.setJumlahGalon(totalJumlah);
        trx.setOngkir(ongkir);
        trx.setOngkirType(ongkirType);
        trx.setTotalHarga(totalHarga);
        if (isJual) {
            trx.setGalonOwnership(ownership);
            trx.setHargaBotolGalon(hargaBotol);
        }
        double hargaGR = 0;
        int rusak = 0;
        if (isJual) {
            trx.setItems(items);
            // Backward-compat: put first item's product_id and price as primary
            if (!items.isEmpty()) {
                TransactionItem first = items.get(0);
                trx.setProductId(first.productId);
                trx.setHargaPerGalon(first.hargaPerGalon);
            }
        } else if (totalHarga > 0) {
            // Ganti rugi KEMBALI: compute & store per-galon price based on rusak count
            hargaGR = parseDoubleOr(etHargaGantiRugi, 0);
            rusak = parseIntOr(etJumlahRusak, -1);
            if (rusak < 0) rusak = totalJumlah;
            trx.setHargaPerGalon(hargaGR);
        }
        String catatan = etCatatan.getText() != null ? etCatatan.getText().toString().trim() : "";
        // For ganti rugi, prepend a marker into catatan so receipt can detect on re-view
        if (!isJual && totalHarga > 0) {
            String marker = "[GANTI RUGI " + rusak + " galon rusak]";
            catatan = catatan.isEmpty() ? marker : (marker + " " + catatan);
        }
        if (!catatan.isEmpty()) trx.setCatatan(catatan);
        transactionDao.insert(trx);

        if (isJual) {
            Intent r = new Intent(this, ReceiptActivity.class);
            r.putExtra(ReceiptActivity.EXTRA_CUSTOMER_NAME, selectedCustomerName);
            r.putExtra(ReceiptActivity.EXTRA_CUSTOMER_PHONE, selectedCustomerPhone);
            r.putExtra(ReceiptActivity.EXTRA_ITEMS_JSON,
                    TransactionItem.listToJson(items));
            r.putExtra(ReceiptActivity.EXTRA_ONGKIR, ongkir);
            r.putExtra(ReceiptActivity.EXTRA_ONGKIR_TYPE, ongkirType);
            r.putExtra(ReceiptActivity.EXTRA_TOTAL_HARGA, totalHarga);
            String catatanStr = etCatatan.getText() != null ? etCatatan.getText().toString().trim() : "";
            r.putExtra(ReceiptActivity.EXTRA_CATATAN, catatanStr);

            if (settingsDao.isPointsEnabled()) {
                double ppa = settingsDao.getPointsPerAmount();
                int reward = settingsDao.getPointsRewardThreshold();
                // Exclude biaya beli botol galon dari dasar perhitungan poin
                double thisTrxBasis = totalHarga - (hargaBotol * totalJumlah);
                if (thisTrxBasis < 0) thisTrxBasis = 0;
                int pointsThisTrx = (int) Math.floor(thisTrxBasis / ppa);
                // Lifetime dihitung dari penjualan saja, mengurangi porsi harga botol.
                // Transaksi yang sudah tersimpan akan dihitung ulang via SQL,
                // sehingga kita tambahkan dasar poin transaksi ini secara manual.
                double lifetimePrev = transactionDao.getTotalJualPointsBasisByCustomer(selectedCustomerId);
                double lifetime = lifetimePrev + thisTrxBasis;
                int totalPoints = (int) Math.floor(lifetime / ppa);
                int pointsBefore = (int) Math.floor(lifetimePrev / ppa);
                int rewardsBefore = reward > 0 ? pointsBefore / reward : 0;
                int rewardsAfter = reward > 0 ? totalPoints / reward : 0;
                boolean rewardUnlocked = rewardsAfter > rewardsBefore;
                r.putExtra(ReceiptActivity.EXTRA_POINTS_ENABLED, true);
                r.putExtra(ReceiptActivity.EXTRA_POINTS_EARNED, pointsThisTrx);
                r.putExtra(ReceiptActivity.EXTRA_POINTS_TOTAL, totalPoints);
                r.putExtra(ReceiptActivity.EXTRA_POINTS_REWARD, reward);
                r.putExtra(ReceiptActivity.EXTRA_REWARD_UNLOCKED, rewardUnlocked);
                r.putExtra(ReceiptActivity.EXTRA_REWARDS_NEW_COUNT, rewardsAfter - rewardsBefore);
            }
            startActivity(r);
            finish();
        } else {
            if (totalHarga > 0) {
                Intent r = new Intent(this, ReceiptActivity.class);
                r.putExtra(ReceiptActivity.EXTRA_CUSTOMER_NAME, selectedCustomerName);
                r.putExtra(ReceiptActivity.EXTRA_CUSTOMER_PHONE, selectedCustomerPhone);
                r.putExtra(ReceiptActivity.EXTRA_PRODUCT_NAME, "Ganti Rugi Botol Galon Rusak");
                r.putExtra(ReceiptActivity.EXTRA_JUMLAH, rusak);
                r.putExtra(ReceiptActivity.EXTRA_HARGA_PER_GALON, hargaGR);
                r.putExtra(ReceiptActivity.EXTRA_ONGKIR, 0.0);
                r.putExtra(ReceiptActivity.EXTRA_ONGKIR_TYPE, Transaction.ONGKIR_NONE);
                r.putExtra(ReceiptActivity.EXTRA_TOTAL_HARGA, totalHarga);
                r.putExtra(ReceiptActivity.EXTRA_IS_GANTI_RUGI, true);
                r.putExtra(ReceiptActivity.EXTRA_GANTI_RUGI_KEMBALI, totalJumlah);
                String catatanStr = etCatatan.getText() != null ? etCatatan.getText().toString().trim() : "";
                r.putExtra(ReceiptActivity.EXTRA_CATATAN, catatanStr);
                startActivity(r);
                finish();
            } else {
                Toast.makeText(this, "Berhasil: " + totalJumlah + " botol galon kembali dari "
                        + selectedCustomerName, Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }
}
