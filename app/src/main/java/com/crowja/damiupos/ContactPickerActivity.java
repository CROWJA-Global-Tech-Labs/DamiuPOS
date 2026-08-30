package com.crowja.damiupos;

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

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.crowja.damiupos.adapter.ContactPickerAdapter;
import com.crowja.damiupos.db.CustomerDao;
import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.SettingsDao;
import com.crowja.damiupos.model.Customer;
import com.crowja.damiupos.sync.SyncApi;
import com.crowja.damiupos.sync.SyncSettings;
import com.google.android.material.button.MaterialButton;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    /** Satu kontak telepon + daftar nomornya (urut, sudah dedup di dalam kontak). */
    private static final class ContactGroup {
        final String name;
        final List<String> phones = new ArrayList<>();
        final Set<String> seen = new HashSet<>();   // dedup 8 digit terakhir DI DALAM kontak
        ContactGroup(String name) { this.name = name; }
    }

    /** Rapikan nama: trim + ubah spasi beruntun jadi satu spasi (mis. "AYU  LAUNDRY" → "AYU LAUNDRY"). */
    private static String cleanName(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s+", " ");
    }

    /** 8 digit terakhir (toleran prefix 0812 vs +62812), atau "" kalau terlalu pendek. */
    private static String phoneSuffix(String phone) {
        String n = phone.replaceAll("[^0-9]", "");
        if (n.length() < 4) return "";
        return n.substring(n.length() - Math.min(n.length(), 8));
    }

    /**
     * Baca semua kontak yang punya nomor telepon. Nomor identik (8 digit terakhir
     * sama) di-dedup, baik di dalam satu kontak maupun lintas kontak.
     *
     * <p>Tiap nama yang akan muncul lebih dari sekali — entah karena SATU kontak
     * punya beberapa nomor, ATAU beberapa kontak terpisah memakai nama yang sama —
     * diberi sufiks " #1", " #2", … berurutan. Mis. "Ayu Laundry" dengan 2 nomor
     * jadi "Ayu Laundry #1" dan "Ayu Laundry #2". Nama unik tidak diberi sufiks.
     */
    private List<ContactPickerAdapter.ContactEntry> readContacts() {
        List<ContactPickerAdapter.ContactEntry> list = new ArrayList<>();
        ContentResolver cr = getContentResolver();
        Cursor cursor = cr.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                new String[]{
                        ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                        ContactsContract.CommonDataKinds.Phone.NUMBER
                },
                null, null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC");
        if (cursor == null) return list;

        // Kumpulkan nomor per kontak (urut nama, lalu urut nomor sesuai cursor).
        LinkedHashMap<String, ContactGroup> groups = new LinkedHashMap<>();
        while (cursor.moveToNext()) {
            String contactId = cursor.getString(0);
            String name = cursor.getString(1);
            String phone = cursor.getString(2);
            if (name == null || name.isEmpty() || phone == null || phone.isEmpty()) continue;
            String suffix = phoneSuffix(phone);
            if (suffix.isEmpty()) continue;

            String key = contactId != null ? contactId : name;
            ContactGroup g = groups.get(key);
            if (g == null) { g = new ContactGroup(name); groups.put(key, g); }
            if (g.seen.add(suffix)) g.phones.add(phone);   // dedup di dalam kontak
        }
        cursor.close();

        // Dedup global (8 digit terakhir), kumpulkan pasangan nama+nomor sesuai urutan.
        Set<String> globalSeen = new HashSet<>();
        List<String[]> flat = new ArrayList<>();   // [name, phone]
        for (ContactGroup g : groups.values()) {
            for (String phone : g.phones) {
                if (globalSeen.add(phoneSuffix(phone))) flat.add(new String[]{g.name, phone});
            }
        }

        // Nama yang muncul >1 kali (kontak banyak nomor ATAU beberapa kontak senama —
        // termasuk variasi spasi-ganda/kapitalisasi) diberi sufiks " #1", " #2", …
        // berurutan; nama unik dibiarkan (sudah dirapikan spasinya). Pengelompokan
        // pakai nama ternormalisasi (lowercase, spasi tunggal) supaya "AYU  LAUNDRY"
        // dan "AYU LAUNDRY" dianggap sama.
        Map<String, Integer> nameCount = new HashMap<>();
        Map<String, String> baseByKey = new HashMap<>();   // key → casing kanonik (rapi)
        for (String[] fp : flat) {
            String clean = cleanName(fp[0]);
            String key = clean.toLowerCase(Locale.ROOT);
            nameCount.merge(key, 1, Integer::sum);
            if (!baseByKey.containsKey(key)) baseByKey.put(key, clean);
        }
        Map<String, Integer> nameIdx = new HashMap<>();
        for (String[] fp : flat) {
            String clean = cleanName(fp[0]);
            String key = clean.toLowerCase(Locale.ROOT);
            String displayName = nameCount.get(key) > 1
                    ? baseByKey.get(key) + " #" + nameIdx.merge(key, 1, Integer::sum)
                    : clean;
            boolean already = customerDao.existsByPhone(fp[1]);
            list.add(new ContactPickerAdapter.ContactEntry(displayName, fp[1], already));
        }
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
            // Cross-check with the server FIRST: skip any number an admin deleted on the dashboard so
            // we don't resurrect it. Offline / not enrolled → empty set → import behaves as before.
            final Set<String> deletedOnServer = fetchDeletedOnServer(selected);

            int imported = 0;
            int skipped = 0;
            final List<ContactPickerAdapter.ContactEntry> deletedSkipped = new ArrayList<>();
            int total = selected.size();
            for (int i = 0; i < total; i++) {
                ContactPickerAdapter.ContactEntry e = selected.get(i);
                if (deletedOnServer.contains(e.phone)) {
                    deletedSkipped.add(e);   // dihapus di server → jangan di-import lagi
                } else if (customerDao.existsByPhone(e.phone)) {
                    skipped++;
                } else {
                    // Normalisasi nomor kontak ke format lokal 08XXXX sebelum simpan (cek dedup tetap kanonik).
                    customerDao.insert(new Customer(e.name, com.crowja.damiupos.util.PhoneUtils.toLocal08(e.phone), ""));
                    imported++;
                }
                final int done = i + 1;
                mainHandler.post(() -> {
                    progress.setProgress(done);
                    progress.setMessage(done + " / " + total);
                });
            }

            // Setelah impor: rapikan penomoran nama yang sama (mis. satu usaha dua nomor)
            // supaya pelanggan baru DAN yang sudah ada sebelumnya konsisten jadi
            // "Nama #1", "Nama #2", … — bukan campuran "Nama" + "Nama #2".
            final int renamedFinal = customerDao.numberDuplicateNames();
            com.crowja.damiupos.sync.SyncScheduler.syncNow(getApplicationContext());

            final int importedFinal = imported;
            final int skippedFinal = skipped;
            mainHandler.post(() -> {
                progress.dismiss();
                String summary = importedFinal + " kontak diimpor";
                if (skippedFinal > 0) summary += ", " + skippedFinal + " dilewati (sudah ada)";
                if (renamedFinal > 0) {
                    summary += "\n" + renamedFinal + " pelanggan bernama sama diberi nomor #1, #2, …";
                }

                if (!deletedSkipped.isEmpty()) {
                    // Pelanggan yang sudah dihapus di server → laporkan jumlah + daftarnya.
                    StringBuilder sb = new StringBuilder();
                    sb.append(deletedSkipped.size())
                            .append(" pelanggan tidak di-import karena sudah dihapus di server:\n");
                    for (ContactPickerAdapter.ContactEntry e : deletedSkipped) {
                        sb.append("\n• ").append(e.name);
                        if (e.phone != null && !e.phone.isEmpty()) sb.append(" (").append(e.phone).append(")");
                    }
                    sb.append("\n\n").append(summary);
                    new AlertDialog.Builder(this)
                            .setTitle("Sebagian tidak di-import")
                            .setMessage(sb.toString())
                            .setCancelable(false)
                            .setPositiveButton("Mengerti", (d, w) -> {
                                setResult(RESULT_OK, new Intent().putExtra("imported", importedFinal));
                                finish();
                            })
                            .show();
                } else {
                    Toast.makeText(this, "Sinkronisasi selesai!\n" + summary, Toast.LENGTH_LONG).show();
                    setResult(RESULT_OK, new Intent().putExtra("imported", importedFinal));
                    finish();
                }
            });
        });
    }

    /**
     * Ask the server which of these contacts' phone numbers were DELETED on the dashboard. Returns the
     * exact input phone strings to skip. Best-effort: offline / not enrolled / any error → empty set,
     * so contact import still works without a connection (it just can't honour server-side deletions).
     */
    private Set<String> fetchDeletedOnServer(List<ContactPickerAdapter.ContactEntry> selected) {
        Set<String> result = new HashSet<>();
        try {
            SyncSettings cfg = new SyncSettings(new SettingsDao(DatabaseHelper.getInstance(this)));
            if (!cfg.isEnrolled()) return result;
            JSONArray phones = new JSONArray();
            for (ContactPickerAdapter.ContactEntry e : selected) {
                if (e.phone != null && !e.phone.isEmpty()) phones.put(e.phone);
            }
            if (phones.length() == 0) return result;
            JSONObject r = new SyncApi(cfg).customersDeletedCheck(phones);
            JSONArray arr = r.optJSONArray("deleted");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    String p = arr.optString(i, "");
                    if (!p.isEmpty()) result.add(p);
                }
            }
        } catch (Throwable ignored) {
            // Offline or server error — import normally (server deletions just aren't enforced now).
        }
        return result;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }
}
