package com.damiu.pos;

import android.app.ProgressDialog;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.ContactsContract;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.damiu.pos.adapter.ContactPickerAdapter;
import com.damiu.pos.db.CustomerDao;
import com.damiu.pos.db.DatabaseHelper;
import com.damiu.pos.model.Customer;
import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Layar pemilihan kontak yang akan di-import sebagai pelanggan.
 * - Memuat kontak telepon di thread terpisah dengan progress spinner.
 * - User bisa search, select all / none, atau centang manual per kontak.
 * - Kontak yang sudah ada di DB ditandai dan di-disable.
 * - Setelah user tap "Import", menjalankan insert di thread terpisah dengan
 *   ProgressDialog, lalu finish().
 *
 * Permission READ_CONTACTS harus sudah granted sebelum memanggil activity ini.
 */
public class ContactPickerActivity extends AppCompatActivity {

    private RecyclerView rvContacts;
    private TextView tvEmpty, tvSelectedCount;
    private ProgressBar progressLoading;
    private CheckBox cbSelectAll;
    private CheckBox cbHideSynced;
    private EditText etSearch;
    private MaterialButton btnImport;

    private ContactPickerAdapter adapter;
    private CustomerDao customerDao;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_contact_picker);

        Toolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        customerDao = new CustomerDao(DatabaseHelper.getInstance(this));

        rvContacts = findViewById(R.id.rvContacts);
        tvEmpty = findViewById(R.id.tvEmpty);
        tvSelectedCount = findViewById(R.id.tvSelectedCount);
        progressLoading = findViewById(R.id.progressLoading);
        cbSelectAll = findViewById(R.id.cbSelectAll);
        cbHideSynced = findViewById(R.id.cbHideSynced);
        etSearch = findViewById(R.id.etSearchContact);
        btnImport = findViewById(R.id.btnImport);

        adapter = new ContactPickerAdapter();
        adapter.setListener((selected, total) -> updateSelectionUi(selected, total));
        rvContacts.setLayoutManager(new LinearLayoutManager(this));
        rvContacts.setAdapter(adapter);

        // Default: sembunyikan kontak yang sudah sinkron supaya user fokus ke kontak baru
        adapter.setHideSynced(cbHideSynced.isChecked());
        cbHideSynced.setOnCheckedChangeListener((button, isChecked) -> {
            adapter.setHideSynced(isChecked);
            updateEmptyState();
        });

        cbSelectAll.setOnClickListener(v -> adapter.selectAllVisible(cbSelectAll.isChecked()));

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                adapter.filter(s.toString().trim());
                updateEmptyState();
            }
        });

        btnImport.setOnClickListener(v -> importSelected());
        btnImport.setEnabled(false);

        loadContactsAsync();
    }

    /**
     * Sync rv/empty visibility dengan jumlah item yang sedang tampil,
     * dan berikan pesan empty yang lebih informatif saat user hide yang sudah sinkron.
     */
    private void updateEmptyState() {
        boolean empty = adapter.getItemCount() == 0;
        rvContacts.setVisibility(empty ? View.GONE : View.VISIBLE);
        tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (empty) {
            if (cbHideSynced != null && cbHideSynced.isChecked()) {
                tvEmpty.setText("Semua kontak sudah tersinkron.\nMatikan filter untuk melihat semuanya.");
            } else {
                tvEmpty.setText("Tidak ada kontak ditemukan");
            }
        }
    }

    private void updateSelectionUi(int selected, int total) {
        tvSelectedCount.setText(selected + " dipilih");
        btnImport.setText("Import " + selected + " Pelanggan");
        btnImport.setEnabled(selected > 0);
        // Keep master checkbox in sync without triggering click listener
        cbSelectAll.setOnCheckedChangeListener(null);
        cbSelectAll.setChecked(selected > 0 && selected == total);
        // No need to reattach — we handle via setOnClickListener above
    }

    private void loadContactsAsync() {
        progressLoading.setVisibility(View.VISIBLE);
        rvContacts.setVisibility(View.GONE);
        tvEmpty.setVisibility(View.GONE);

        executor.execute(() -> {
            List<ContactPickerAdapter.ContactEntry> entries = readContacts();
            mainHandler.post(() -> {
                progressLoading.setVisibility(View.GONE);
                adapter.setData(entries);
                if (entries.isEmpty()) {
                    tvEmpty.setText("Tidak ada kontak pada perangkat");
                    tvEmpty.setVisibility(View.VISIBLE);
                    rvContacts.setVisibility(View.GONE);
                } else {
                    updateEmptyState();
                }
            });
        });
    }

    /**
     * Baca semua kontak yang punya nomor telepon. Dedup dengan 8 digit terakhir
     * supaya nomor dengan prefix berbeda (0812 vs +62812) tidak muncul dobel.
     * Untuk setiap kontak, cek apakah sudah terdaftar di DB.
     */
    private List<ContactPickerAdapter.ContactEntry> readContacts() {
        List<ContactPickerAdapter.ContactEntry> list = new ArrayList<>();
        ContentResolver cr = getContentResolver();
        Cursor cursor = cr.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                new String[]{
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                        ContactsContract.CommonDataKinds.Phone.NUMBER
                },
                null, null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC");
        if (cursor == null) return list;

        Set<String> seenSuffix = new HashSet<>();
        while (cursor.moveToNext()) {
            String name = cursor.getString(0);
            String phone = cursor.getString(1);
            if (name == null || name.isEmpty() || phone == null || phone.isEmpty()) continue;

            String normalized = phone.replaceAll("[^0-9]", "");
            if (normalized.length() < 4) continue;
            String suffix = normalized.substring(
                    normalized.length() - Math.min(normalized.length(), 8));
            if (!seenSuffix.add(suffix)) continue;

            boolean already = customerDao.existsByPhone(phone);
            list.add(new ContactPickerAdapter.ContactEntry(name, phone, already));
        }
        cursor.close();
        return list;
    }

    private void importSelected() {
        List<ContactPickerAdapter.ContactEntry> selected = adapter.getSelected();
        if (selected.isEmpty()) {
            Toast.makeText(this, "Belum ada kontak yang dipilih", Toast.LENGTH_SHORT).show();
            return;
        }

        ProgressDialog progress = new ProgressDialog(this);
        progress.setTitle("Mengimpor Kontak");
        progress.setMessage("0 / " + selected.size());
        progress.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        progress.setMax(selected.size());
        progress.setCancelable(false);
        progress.show();

        executor.execute(() -> {
            int imported = 0;
            int skipped = 0;
            int total = selected.size();
            for (int i = 0; i < total; i++) {
                ContactPickerAdapter.ContactEntry e = selected.get(i);
                if (customerDao.existsByPhone(e.phone)) {
                    skipped++;
                } else {
                    customerDao.insert(new Customer(e.name, e.phone, ""));
                    imported++;
                }
                final int done = i + 1;
                mainHandler.post(() -> {
                    progress.setProgress(done);
                    progress.setMessage(done + " / " + total);
                });
            }

            final int importedFinal = imported;
            final int skippedFinal = skipped;
            mainHandler.post(() -> {
                progress.dismiss();
                String msg = "Sinkronisasi selesai!\n" + importedFinal + " kontak diimpor";
                if (skippedFinal > 0) msg += ", " + skippedFinal + " dilewati";
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                setResult(RESULT_OK, new Intent().putExtra("imported", importedFinal));
                finish();
            });
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }
}
