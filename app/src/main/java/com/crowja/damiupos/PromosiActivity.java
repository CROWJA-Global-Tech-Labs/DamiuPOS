package com.crowja.damiupos;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.crowja.damiupos.adapter.CustomerAdapter;
import com.crowja.damiupos.db.CustomerDao;
import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.ProductDao;
import com.crowja.damiupos.db.SettingsDao;
import com.crowja.damiupos.db.TransactionDao;
import com.crowja.damiupos.model.Customer;
import com.crowja.damiupos.model.Product;
import com.crowja.damiupos.model.Transaction;
import com.crowja.damiupos.model.TransactionItem;

import java.util.ArrayList;
import java.util.List;

/**
 * "Promosi Galon Gratis" — alur mirip Transaksi Baru, dipakai akun Marketing
 * (dan Admin) untuk memberi galon GRATIS ke pelanggan (utamakan pelanggan baru).
 * Tersimpan sebagai transaksi JUAL bertotal Rp 0 dengan penanda {@code [PROMOSI]}.
 */
public class PromosiActivity extends AppCompatActivity {

    /** Penanda di catatan transaksi untuk membedakan promosi dari penjualan. */
    public static final String PROMO_MARKER = "[PROMOSI]";

    private static final int REQUEST_NEW_CUSTOMER = 6201;

    private CustomerDao customerDao;
    private ProductDao productDao;
    private TransactionDao transactionDao;
    private SettingsDao settingsDao;

    private TextView tvSelectedCustomer, tvTotalGalon, tvEmptyItems;
    private EditText etCatatan;
    private LinearLayout llItems;

    private long selectedCustomerId = -1;
    private String selectedCustomerName = "";
    private String selectedCustomerPhone = "";

    private static final class Entry {
        final Product product;
        final EditText etQty;
        Entry(Product p, EditText q) { this.product = p; this.etQty = q; }
    }
    private final List<Entry> entries = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_promosi);

        Toolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        DatabaseHelper db = DatabaseHelper.getInstance(this);
        customerDao = new CustomerDao(db);
        productDao = new ProductDao(db);
        transactionDao = new TransactionDao(db);
        settingsDao = new SettingsDao(db);

        tvSelectedCustomer = findViewById(R.id.tvSelectedCustomer);
        tvTotalGalon = findViewById(R.id.tvTotalGalon);
        tvEmptyItems = findViewById(R.id.tvEmptyItems);
        etCatatan = findViewById(R.id.etCatatan);
        llItems = findViewById(R.id.llItems);

        findViewById(R.id.cardCustomer).setOnClickListener(v -> showCustomerPicker());
        findViewById(R.id.btnSimpanPromosi).setOnClickListener(v -> trySave());

        buildProductEntries();
        updateTotal();
    }

    private void buildProductEntries() {
        llItems.removeAllViews();
        entries.clear();
        List<Product> products = productDao.getAll();
        for (Product p : products) {
            View row = LayoutInflater.from(this)
                    .inflate(R.layout.item_product_entry, llItems, false);
            TextView tvDot = row.findViewById(R.id.tvItemDot);
            TextView tvName = row.findViewById(R.id.tvItemName);
            EditText etQty = row.findViewById(R.id.etItemQty);
            EditText etPrice = row.findViewById(R.id.etItemPrice);
            View btnMinus = row.findViewById(R.id.btnItemMinus);
            View btnPlus = row.findViewById(R.id.btnItemPlus);

            tvName.setText(p.getName());
            try {
                tvDot.setTextColor(p.getColor() != null && !p.getColor().isEmpty()
                        ? Color.parseColor(p.getColor()) : Color.parseColor("#1565C0"));
            } catch (Exception e) {
                tvDot.setTextColor(Color.parseColor("#1565C0"));
            }
            // Promo = gratis → sembunyikan baris harga, ganti dengan "GRATIS".
            View priceRow = (View) etPrice.getParent();
            priceRow.setVisibility(View.GONE);
            TextView free = new TextView(this);
            free.setText("GRATIS");
            free.setTextColor(Color.parseColor("#2E7D32"));
            free.setTextSize(12f);
            ((LinearLayout) priceRow.getParent()).addView(free,
                    ((LinearLayout) priceRow.getParent()).indexOfChild(priceRow) + 1);

            etQty.setText("0");
            etQty.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void afterTextChanged(Editable s) { updateTotal(); }
            });
            btnMinus.setOnClickListener(v -> {
                int q = parseIntOr(etQty, 0);
                if (q > 0) etQty.setText(String.valueOf(q - 1));
            });
            btnPlus.setOnClickListener(v -> etQty.setText(String.valueOf(parseIntOr(etQty, 0) + 1)));

            llItems.addView(row);
            entries.add(new Entry(p, etQty));
        }
        tvEmptyItems.setVisibility(products.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private int totalGalon() {
        int t = 0;
        for (Entry e : entries) t += parseIntOr(e.etQty, 0);
        return t;
    }

    private void updateTotal() {
        tvTotalGalon.setText("Total: " + totalGalon() + " galon (GRATIS)");
    }

    private void trySave() {
        if (selectedCustomerId <= 0) {
            Toast.makeText(this, "Pilih atau tambah pelanggan dulu", Toast.LENGTH_SHORT).show();
            return;
        }
        int total = totalGalon();
        if (total <= 0) {
            Toast.makeText(this, "Tentukan jumlah galon gratis (minimal 1)", Toast.LENGTH_SHORT).show();
            return;
        }

        StringBuilder summary = new StringBuilder();
        summary.append("Pelanggan: ").append(selectedCustomerName).append("\n");
        for (Entry e : entries) {
            int q = parseIntOr(e.etQty, 0);
            if (q > 0) summary.append("• ").append(q).append("× ")
                    .append(e.product.getName()).append("\n");
        }
        summary.append("\nTotal: ").append(total).append(" galon — GRATIS");

        new AlertDialog.Builder(this)
                .setTitle("Konfirmasi Promosi")
                .setMessage(summary.toString())
                .setPositiveButton("Simpan", (d, w) -> doSave())
                .setNegativeButton("Batal", null)
                .show();
    }

    private void doSave() {
        List<TransactionItem> items = new ArrayList<>();
        int total = 0;
        for (Entry e : entries) {
            int q = parseIntOr(e.etQty, 0);
            if (q <= 0) continue;
            items.add(new TransactionItem(e.product.getId(), e.product.getName(), q, 0));
            total += q;
        }

        String note = etCatatan.getText() != null ? etCatatan.getText().toString().trim() : "";
        String uname = settingsDao.getCurrentUserName();
        StringBuilder catatan = new StringBuilder(PROMO_MARKER);
        if (uname != null && !uname.isEmpty()) catatan.append(" (oleh ").append(uname).append(")");
        if (!note.isEmpty()) catatan.append(" ").append(note);

        Transaction trx = new Transaction();
        trx.setCustomerId(selectedCustomerId);
        trx.setType(Transaction.TYPE_JUAL);
        trx.setItems(items);
        trx.setJumlahGalon(total);
        trx.setHargaPerGalon(0);
        trx.setTotalHarga(0);
        trx.setOngkir(0);
        trx.setOngkirType(Transaction.ONGKIR_NONE);
        // Bawa sendiri → tidak ada botol galon dipinjam/ditagih (murni air gratis).
        trx.setGalonOwnership(Transaction.OWNERSHIP_BAWA_SENDIRI);
        trx.setHargaBotolGalon(0);
        trx.setPaymentMethod(null);
        trx.setCatatan(catatan.toString());
        transactionDao.insert(trx);

        Toast.makeText(this, "Promosi tersimpan: " + total + " galon gratis untuk "
                + selectedCustomerName + " 🎁", Toast.LENGTH_LONG).show();
        finish();
    }

    // ----------------------------------------------------- customer picker

    private void showCustomerPicker() {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_select_customer, null);
        RecyclerView rv = dialogView.findViewById(R.id.rvCustomers);
        EditText etSearch = dialogView.findViewById(R.id.etSearchCustomer);
        View btnPickUmum = dialogView.findViewById(R.id.btnPickUmum);
        View btnPickNew = dialogView.findViewById(R.id.btnPickNew);

        // Promo ke pelanggan bernama → sembunyikan "Umum".
        if (btnPickUmum != null) btnPickUmum.setVisibility(View.GONE);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Pilih / Tambah Pelanggan")
                .setView(dialogView)
                .setNegativeButton("Batal", null)
                .create();

        CustomerAdapter adapter = new CustomerAdapter(c -> {
            applyCustomer(c);
            dialog.dismiss();
        });
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(adapter);
        adapter.setData(customerDao.getAll());

        if (btnPickNew != null) btnPickNew.setOnClickListener(v -> {
            dialog.dismiss();
            startActivityForResult(new Intent(this, CustomerFormActivity.class), REQUEST_NEW_CUSTOMER);
        });

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                String k = s.toString().trim();
                adapter.setData(k.isEmpty() ? customerDao.getAll() : customerDao.search(k));
            }
        });
        dialog.show();
    }

    private void applyCustomer(Customer c) {
        selectedCustomerId = c.getId();
        selectedCustomerName = c.getName();
        selectedCustomerPhone = c.getPhone() != null ? c.getPhone() : "";
        tvSelectedCustomer.setText(c.getName());
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_NEW_CUSTOMER && resultCode == RESULT_OK && data != null) {
            long newId = data.getLongExtra(CustomerFormActivity.EXTRA_NEW_CUSTOMER_ID, -1);
            if (newId > 0) {
                Customer c = customerDao.getById(newId);
                if (c != null) applyCustomer(c);
            }
        }
    }

    private int parseIntOr(EditText et, int def) {
        if (et == null || et.getText() == null) return def;
        String s = et.getText().toString().trim();
        if (s.isEmpty()) return def;
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return def; }
    }
}
