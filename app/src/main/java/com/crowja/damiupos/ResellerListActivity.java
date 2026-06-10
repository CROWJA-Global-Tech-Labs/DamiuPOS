package com.crowja.damiupos;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.crowja.damiupos.adapter.CustomerAdapter;
import com.crowja.damiupos.db.CustomerDao;
import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.ResellerKomisiCalculator;
import com.crowja.damiupos.db.ResellerWithdrawalDao;
import com.crowja.damiupos.db.SettingsDao;
import com.crowja.damiupos.model.Customer;
import com.google.android.material.textfield.TextInputEditText;

import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Daftar reseller + saldo komisi masing-masing.
 * Komisi dihitung {@link ResellerKomisiCalculator} (mendukung rate per jenis
 * air minum). Tambah reseller langsung dari sini via FAB → pilih pelanggan.
 */
public class ResellerListActivity extends AppCompatActivity {

    private RecyclerView rv;
    private TextView tvEmpty, tvSummary;
    private ResellerAdapter adapter;
    private CustomerDao customerDao;
    private ResellerWithdrawalDao wdDao;
    private SettingsDao settingsDao;
    private DatabaseHelper dbHelper;

    private static final NumberFormat NF = NumberFormat.getInstance(new Locale("id", "ID"));

    // Mode urutan daftar reseller.
    private static final int SORT_KOMISI = 0;   // jumlah komisi didapatkan
    private static final int SORT_GALON = 1;    // jumlah galon terjual
    private static final int SORT_BERGABUNG = 2; // waktu bergabung (terbaru dulu)
    private int sortMode = SORT_KOMISI;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reseller_list);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        dbHelper = DatabaseHelper.getInstance(this);
        customerDao = new CustomerDao(dbHelper);
        wdDao = new ResellerWithdrawalDao(dbHelper);
        settingsDao = new SettingsDao(dbHelper);

        rv = findViewById(R.id.rvResellers);
        tvEmpty = findViewById(R.id.tvEmpty);
        tvSummary = findViewById(R.id.tvSummary);

        adapter = new ResellerAdapter();
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(adapter);

        findViewById(R.id.fabAddReseller).setOnClickListener(v -> showAddResellerPicker());
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 1, 0, "Urutkan")
                .setIcon(android.R.drawable.ic_menu_sort_by_size)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == 1) {
            showSortDialog();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void showSortDialog() {
        String[] options = {
                "Jumlah komisi didapatkan",
                "Jumlah galon terjual",
                "Waktu bergabung (terbaru)"
        };
        new AlertDialog.Builder(this)
                .setTitle("Urutkan Reseller")
                .setSingleChoiceItems(options, sortMode, (dialog, which) -> {
                    sortMode = which;
                    dialog.dismiss();
                    refresh();
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        double rate = settingsDao.getResellerKomisi();
        List<Customer> resellers = customerDao.getResellers();
        List<Row> rows = new ArrayList<>();
        double totalSaldo = 0;
        for (Customer c : resellers) {
            Row r = new Row();
            r.customer = c;
            ResellerKomisiCalculator.Result res =
                    ResellerKomisiCalculator.hitung(dbHelper, c);
            r.earned = res.totalKomisi;
            r.galon = 0;
            for (ResellerKomisiCalculator.Entry e : res.entries) r.galon += e.totalGalon;
            r.withdrawn = wdDao.getTotalWithdrawn(c.getId());
            r.saldo = r.earned - r.withdrawn;
            totalSaldo += r.saldo;
            rows.add(r);
        }
        sortRows(rows);
        adapter.setData(rows);

        tvSummary.setText(resellers.size() + " reseller  ·  Komisi default Rp "
                + NF.format(Math.round(rate)) + "/galon  ·  Total saldo: Rp "
                + NF.format(Math.round(totalSaldo)));

        tvEmpty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
        rv.setVisibility(rows.isEmpty() ? View.GONE : View.VISIBLE);
    }

    // =======================================================================
    // Tambah reseller dari menu ini: picker pelanggan non-reseller + search
    // =======================================================================

    private void showAddResellerPicker() {
        View content = LayoutInflater.from(this)
                .inflate(R.layout.dialog_select_customer, null, false);
        // Sembunyikan quick-pick (Umum / Baru) — tidak relevan untuk reseller.
        View quickRow = content.findViewById(R.id.quickPickRow);
        if (quickRow != null) quickRow.setVisibility(View.GONE);
        View divider = content.findViewById(R.id.quickPickDivider);
        if (divider != null) divider.setVisibility(View.GONE);

        RecyclerView rvCustomers = content.findViewById(R.id.rvCustomers);
        TextInputEditText etSearch = content.findViewById(R.id.etSearchCustomer);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Pilih Pelanggan → Reseller")
                .setView(content)
                .setNegativeButton("Batal", null)
                .create();

        CustomerAdapter pickAdapter = new CustomerAdapter(c -> {
            dialog.dismiss();
            makeReseller(c);
        });
        rvCustomers.setLayoutManager(new LinearLayoutManager(this));
        rvCustomers.setAdapter(pickAdapter);

        pickAdapter.setData(nonResellers(""));
        if (etSearch != null) {
            etSearch.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void afterTextChanged(Editable s) {
                    pickAdapter.setData(nonResellers(s.toString().trim()));
                }
            });
        }

        dialog.show();
    }

    /** Pelanggan yang BELUM reseller (exclude Umum), terfilter keyword. */
    private List<Customer> nonResellers(String keyword) {
        List<Customer> src = keyword.isEmpty()
                ? customerDao.getAll() : customerDao.search(keyword);
        List<Customer> out = new ArrayList<>();
        for (Customer c : src) {
            if (!c.isReseller() && !CustomerDao.UMUM_NAME.equals(c.getName())) {
                out.add(c);
            }
        }
        return out;
    }

    private void makeReseller(Customer c) {
        c.setReseller(true);
        if (c.getResellerSince() == null || c.getResellerSince().isEmpty()) {
            c.setResellerSince(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                    .format(new Date()));
        }
        customerDao.update(c);
        Toast.makeText(this, c.getName() + " sekarang reseller ✓", Toast.LENGTH_SHORT).show();
        refresh();
        // Langsung buka detail supaya bisa atur komisi per jenis air
        startActivity(new Intent(this, ResellerDetailActivity.class)
                .putExtra(ResellerDetailActivity.EXTRA_CUSTOMER_ID, c.getId()));
    }

    /** Urutkan rows sesuai {@link #sortMode} (semua descending/terbaru dulu). */
    private void sortRows(List<Row> rows) {
        java.util.Collections.sort(rows, (a, b) -> {
            switch (sortMode) {
                case SORT_GALON:
                    return Integer.compare(b.galon, a.galon);
                case SORT_BERGABUNG:
                    // resellerSince ISO "yyyy-MM-dd HH:mm:ss" → string compare,
                    // terbaru dulu. Null (tanpa tanggal) ditaruh paling akhir.
                    String sa = a.customer.getResellerSince();
                    String sb = b.customer.getResellerSince();
                    if (sa == null && sb == null) return 0;
                    if (sa == null) return 1;
                    if (sb == null) return -1;
                    return sb.compareTo(sa);
                case SORT_KOMISI:
                default:
                    return Double.compare(b.earned, a.earned);
            }
        });
    }

    private static class Row {
        Customer customer;
        double earned;
        double withdrawn;
        double saldo;
        int galon;
    }

    private class ResellerAdapter extends RecyclerView.Adapter<ResellerAdapter.VH> {
        private List<Row> data = new ArrayList<>();

        void setData(List<Row> rows) { this.data = rows; notifyDataSetChanged(); }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_reseller, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            Row r = data.get(position);
            Customer c = r.customer;
            String name = c.getName();
            h.tvInitial.setText(name != null && !name.isEmpty()
                    ? String.valueOf(name.charAt(0)).toUpperCase(Locale.getDefault()) : "?");
            h.tvName.setText(name);
            h.tvDetail.setText(r.galon + " galon  ·  Komisi Rp "
                    + NF.format(Math.round(r.earned))
                    + "  ·  Cair Rp " + NF.format(Math.round(r.withdrawn)));
            h.tvSaldo.setText("Rp " + NF.format(Math.round(r.saldo)));

            h.itemView.setOnClickListener(v ->
                    startActivity(new Intent(ResellerListActivity.this,
                            ResellerDetailActivity.class)
                            .putExtra(ResellerDetailActivity.EXTRA_CUSTOMER_ID, c.getId())));
        }

        @Override
        public int getItemCount() { return data.size(); }

        class VH extends RecyclerView.ViewHolder {
            TextView tvInitial, tvName, tvDetail, tvSaldo;
            VH(View v) {
                super(v);
                tvInitial = v.findViewById(R.id.tvInitial);
                tvName = v.findViewById(R.id.tvName);
                tvDetail = v.findViewById(R.id.tvDetail);
                tvSaldo = v.findViewById(R.id.tvSaldo);
            }
        }
    }
}
