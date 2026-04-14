package com.damiu.pos;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.os.Bundle;
import android.provider.ContactsContract;
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

import com.damiu.pos.adapter.CustomerAdapter;
import com.damiu.pos.db.CustomerDao;
import com.damiu.pos.db.DatabaseHelper;
import com.damiu.pos.model.Customer;

import java.util.List;

public class CustomerListActivity extends AppCompatActivity implements CustomerAdapter.OnCustomerClickListener {

    private static final int REQUEST_PERMISSION_CONTACTS = 200;

    private RecyclerView rvCustomers;
    private TextView tvEmpty;
    private EditText etSearch;
    private CustomerAdapter adapter;
    private CustomerDao customerDao;
    private int sortMode = CustomerDao.SORT_NAME;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_customer_list);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        customerDao = new CustomerDao(DatabaseHelper.getInstance(this));

        rvCustomers = findViewById(R.id.rvCustomers);
        tvEmpty = findViewById(R.id.tvEmpty);
        etSearch = findViewById(R.id.etSearch);

        adapter = new CustomerAdapter(this);
        rvCustomers.setLayoutManager(new LinearLayoutManager(this));
        rvCustomers.setAdapter(adapter);

        findViewById(R.id.fabAdd).setOnClickListener(v -> {
            startActivity(new Intent(this, CustomerFormActivity.class));
        });

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

    private void loadCustomers(String keyword) {
        List<Customer> list;
        if (keyword.isEmpty()) {
            list = customerDao.getAll(sortMode);
        } else {
            list = customerDao.search(keyword, sortMode);
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
        menu.add(0, 2, 0, "Urutkan")
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        menu.add(0, 1, 1, "Sinkronisasi dari Kontak")
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == 1) {
            syncFromContacts();
            return true;
        }
        if (item.getItemId() == 2) {
            showSortDialog();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void showSortDialog() {
        String[] options = {"Nama (A-Z)", "Galon Terbanyak"};
        new AlertDialog.Builder(this)
                .setTitle("Urutkan Pelanggan")
                .setSingleChoiceItems(options, sortMode, (dialog, which) -> {
                    sortMode = which;
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

        new AlertDialog.Builder(this)
                .setTitle("Sinkronisasi Kontak")
                .setMessage("Semua kontak dari telepon yang memiliki nomor telepon akan diimpor sebagai pelanggan baru. Kontak yang sudah ada (berdasarkan nomor telepon) akan dilewati.\n\nLanjutkan?")
                .setPositiveButton("Sinkronisasi", (dialog, which) -> doSyncContacts())
                .setNegativeButton("Batal", null)
                .show();
    }

    private void doSyncContacts() {
        ContentResolver cr = getContentResolver();
        Cursor cursor = cr.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                new String[]{
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                        ContactsContract.CommonDataKinds.Phone.NUMBER
                },
                null, null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC");

        if (cursor == null) {
            Toast.makeText(this, "Gagal membaca kontak", Toast.LENGTH_SHORT).show();
            return;
        }

        int imported = 0;
        int skipped = 0;
        java.util.Set<String> processedPhones = new java.util.HashSet<>();

        while (cursor.moveToNext()) {
            String name = cursor.getString(0);
            String phone = cursor.getString(1);

            if (name == null || name.isEmpty() || phone == null || phone.isEmpty()) {
                continue;
            }

            // Normalize phone for dedup within this import
            String normalized = phone.replaceAll("[^0-9]", "");
            if (normalized.length() < 4) continue;

            String suffix = normalized.substring(normalized.length() - Math.min(normalized.length(), 8));
            if (processedPhones.contains(suffix)) {
                continue;
            }
            processedPhones.add(suffix);

            // Check if already exists in database
            if (customerDao.existsByPhone(phone)) {
                skipped++;
                continue;
            }

            Customer customer = new Customer(name, phone, "");
            customerDao.insert(customer);
            imported++;
        }
        cursor.close();

        String msg = "Sinkronisasi selesai!\n" + imported + " kontak diimpor";
        if (skipped > 0) {
            msg += ", " + skipped + " sudah ada";
        }
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();

        loadCustomers(etSearch.getText().toString().trim());
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
}
