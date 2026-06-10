package com.crowja.damiupos;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.crowja.damiupos.adapter.CustomerAdapter;
import com.crowja.damiupos.db.CustomerDao;
import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.SettingsDao;
import com.crowja.damiupos.model.Customer;
import com.crowja.damiupos.paywall.PaywallDialogFragment;

import java.util.List;
import java.util.Set;

public class CustomerListActivity extends AppCompatActivity
        implements CustomerAdapter.OnCustomerClickListener,
                   CustomerAdapter.OnSelectionChangedListener {

    private static final int REQUEST_PERMISSION_CONTACTS = 200;

    private RecyclerView rvCustomers;
    private TextView tvEmpty;
    private EditText etSearch;
    private CustomerAdapter adapter;
    private CustomerDao customerDao;
    private int sortMode = CustomerDao.SORT_NAME;
    private Toolbar toolbar;
    /** Kalau true, toolbar di-switch jadi mode "N dipilih" + menu Hapus. */
    private int selectedCount = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_customer_list);

        toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> {
            // Nav klik: kalau lagi selection mode → exit selection dulu,
            // baru tutup activity kalau ditekan sekali lagi.
            if (adapter != null && adapter.isSelectionMode()) {
                adapter.clearSelection();
            } else {
                finish();
            }
        });

        customerDao = new CustomerDao(DatabaseHelper.getInstance(this));

        rvCustomers = findViewById(R.id.rvCustomers);
        tvEmpty = findViewById(R.id.tvEmpty);
        etSearch = findViewById(R.id.etSearch);

        adapter = new CustomerAdapter(this);
        adapter.setOnSelectionChangedListener(this);
        rvCustomers.setLayoutManager(new LinearLayoutManager(this));
        rvCustomers.setAdapter(adapter);

        findViewById(R.id.fabAdd).setOnClickListener(v -> tryAddCustomer());

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                loadCustomers(s.toString().trim());
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadCustomers(etSearch.getText().toString().trim());
    }

    /**
     * Cek Free tier limit ({@link BuildConfig#FREE_MAX_CUSTOMERS}) sebelum
     * buka form pelanggan baru. Pro user lewat tanpa cek. Free user yang
     * sudah hit limit kena paywall — kalau dia unlock via rewarded ad,
     * form langsung dibuka.
     */
    private void tryAddCustomer() {
        SettingsDao settings = new SettingsDao(DatabaseHelper.getInstance(this));
        if (settings.isProActive()) {
            startActivity(new Intent(this, CustomerFormActivity.class));
            return;
        }
        int total = customerDao.getTotalCustomers();
        if (total < BuildConfig.FREE_MAX_CUSTOMERS) {
            startActivity(new Intent(this, CustomerFormActivity.class));
            return;
        }
        // Hit limit — show paywall
        String reason = "Pengguna Gratis dibatasi " + BuildConfig.FREE_MAX_CUSTOMERS
                + " pelanggan. Upgrade Pro untuk tambah pelanggan tanpa batas.";
        PaywallDialogFragment.show(getSupportFragmentManager(), reason,
                () -> startActivity(new Intent(this, CustomerFormActivity.class)));
    }

    private void loadCustomers(String keyword) {
        List<Customer> list;
        if (keyword.isEmpty()) {
            list = customerDao.getAll(sortMode);
        } else {
            list = customerDao.search(keyword, sortMode);
        }
        // Konsumsi gl/hr butuh perhitungan hari → sort di Java (DAO balikin urut nama).
        if (sortMode == CustomerDao.SORT_KONSUMSI) {
            java.util.Collections.sort(list, (a, b) ->
                    Double.compare(b.getKonsumsiPerHari(), a.getKonsumsiPerHari()));
        }
        adapter.setData(list);

        if (list.isEmpty()) {
            tvEmpty.setVisibility(View.VISIBLE);
            rvCustomers.setVisibility(View.GONE);
        } else {
            tvEmpty.setVisibility(View.GONE);
            rvCustomers.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        rebuildMenu(menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        menu.clear();
        rebuildMenu(menu);
        return super.onPrepareOptionsMenu(menu);
    }

    /** Isi menu berdasarkan apakah lagi selection mode atau normal mode. */
    private void rebuildMenu(Menu menu) {
        if (selectedCount > 0) {
            // Selection mode — tampilkan action Hapus + Pilih Semua
            menu.add(0, 101, 0, "Hapus")
                    .setIcon(android.R.drawable.ic_menu_delete)
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
            menu.add(0, 102, 1, "Pilih Semua")
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        } else {
            // Normal mode — menu existing
            menu.add(0, 2, 0, "Urutkan")
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
            menu.add(0, 1, 1, "Sinkronisasi dari Kontak")
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == 1) { syncFromContacts(); return true; }
        if (id == 2) { showSortDialog(); return true; }
        if (id == 101) { confirmDeleteSelected(); return true; }
        if (id == 102) { adapter.selectAll(); return true; }
        return super.onOptionsItemSelected(item);
    }

    private void showSortDialog() {
        final int[] modes = {
                CustomerDao.SORT_NAME,
                CustomerDao.SORT_TOTAL_ORDERED,
                CustomerDao.SORT_KONSUMSI,
                CustomerDao.SORT_PINJAM
        };
        String[] options = {
                "Nama (A-Z)",
                "Total Galon Order (terbanyak)",
                "Konsumsi Tertinggi (gl/hr)",
                "Galon Dipinjam (terbanyak)"
        };
        int checked = 0;
        for (int i = 0; i < modes.length; i++) {
            if (modes[i] == sortMode) { checked = i; break; }
        }
        new AlertDialog.Builder(this)
                .setTitle("Urutkan Pelanggan")
                .setSingleChoiceItems(options, checked, (dialog, which) -> {
                    sortMode = modes[which];
                    loadCustomers(etSearch.getText().toString().trim());
                    dialog.dismiss();
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    private void syncFromContacts() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.READ_CONTACTS}, REQUEST_PERMISSION_CONTACTS);
            return;
        }
        startActivity(new Intent(this, ContactPickerActivity.class));
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSION_CONTACTS) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                syncFromContacts();
            } else {
                Toast.makeText(this, "Permission kontak ditolak", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    public void onCustomerClick(Customer customer) {
        Intent intent = new Intent(this, CustomerDetailActivity.class);
        intent.putExtra("customer_id", customer.getId());
        startActivity(intent);
    }

    // ==========================================================================
    // Multi-select + bulk delete
    // ==========================================================================

    @Override
    public void onSelectionChanged(int count) {
        this.selectedCount = count;
        // Update toolbar title + nav icon supaya jelas user lagi di mode mana
        if (count > 0) {
            toolbar.setTitle(count + " dipilih");
            toolbar.setNavigationIcon(android.R.drawable.ic_menu_close_clear_cancel);
        } else {
            toolbar.setTitle(R.string.pelanggan);
            toolbar.setNavigationIcon(R.drawable.ic_arrow_back);
        }
        // Refresh menu (Hapus/Pilih Semua vs Urutkan/Sinkronisasi)
        invalidateOptionsMenu();
    }

    private void confirmDeleteSelected() {
        Set<Long> ids = adapter.getSelectedIds();
        if (ids.isEmpty()) return;
        int n = ids.size();
        new AlertDialog.Builder(this)
                .setTitle("Hapus Pelanggan")
                .setMessage("Hapus " + n + " pelanggan terpilih beserta riwayat transaksinya? "
                        + "Tindakan ini tidak bisa dibatalkan.")
                .setPositiveButton("Hapus", (d, w) -> {
                    int deleted = customerDao.deleteMany(ids);
                    adapter.clearSelection();
                    loadCustomers(etSearch.getText().toString().trim());
                    Toast.makeText(this, deleted + " pelanggan dihapus",
                            Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    @Override
    public void onBackPressed() {
        // Back keluar selection mode dulu, baru tutup activity
        if (adapter != null && adapter.isSelectionMode()) {
            adapter.clearSelection();
            return;
        }
        super.onBackPressed();
    }
}
