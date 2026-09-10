package com.crowja.damiupos;

import android.Manifest;
import android.app.DatePickerDialog;
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
import com.google.android.material.button.MaterialButton;

import java.util.Calendar;
import java.util.List;
import java.util.Locale;
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
    /** Marketing: filter "Hanya Pelanggan Hari Ini" (default AKTIF) — fokus ke akuisisi
     *  hari berjalan; hilangkan centang untuk melihat semua pelanggan. */
    private com.google.android.material.checkbox.MaterialCheckBox cbHariIni;
    /** Filter "Hanya Pelanggan Saya" (semua role): pelanggan kini branch-wide (semua perangkat),
     *  centang ini untuk fokus ke pelanggan milik perangkat ini saja. Default tak dicentang. */
    private com.google.android.material.checkbox.MaterialCheckBox cbSaya;
    /** Filter "Hanya Bermasalah" (semua role): tampilkan hanya pelanggan dengan issue aktif. */
    private com.google.android.material.checkbox.MaterialCheckBox cbBermasalah;
    /** Filter "Hanya Pelanggan Wilayah Saya": pelanggan yang wilayah petanya ditugaskan ke perangkat
     *  ini (config wilayah dari web). Hanya tampil bila perangkat ini punya wilayah tertugas. */
    private com.google.android.material.checkbox.MaterialCheckBox cbWilayahSaya;
    private boolean marketingUser = false;
    private boolean canDeleteCustomer = true;   // Admin/owner saja boleh hapus pelanggan dari HP
    /** Filter "Ditambahkan" — rentang tanggal pendaftaran (created_at). Null = semua tanggal.
     *  STATIC (in-memory, level proses): filter TETAP berlaku saat user berpindah-pindah activity
     *  lalu kembali ke daftar pelanggan, dan otomatis BERSIH saat aplikasi di-restart (proses mati).
     *  Sengaja TIDAK disimpan ke SharedPreferences/DB. Clear manual = pilih "Semua Tanggal (Clear)". */
    private MaterialButton btnDateRange;
    private static String createdFrom = null;   // yyyy-MM-dd (tanggal lokal)
    private static String createdTo = null;
    private static String createdLabel = "Semua";   // label tombol utk dipulihkan saat kembali
    /** Filter "Wilayah Administratif" (Kecamatan/Desa, hasil reverse-geocode server) — null = tanpa
     *  filter level itu. STATIC seperti createdFrom/To: bertahan antar-kunjungan activity, bersih
     *  saat proses restart. */
    private static String selectedKecamatan = null;
    private static String selectedDesa = null;
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
        rvCustomers.setHasFixedSize(true);
        rvCustomers.setAdapter(adapter);

        findViewById(R.id.fabAdd).setOnClickListener(v -> tryAddCustomer());

        // Marketing → tampilkan filter "Hanya Pelanggan Hari Ini", default dicentang.
        // (setChecked SEBELUM listener supaya tidak memicu load ganda; onResume yang memuat.)
        SettingsDao sess = new SettingsDao(DatabaseHelper.getInstance(this));
        long uid = sess.getCurrentUserId();
        com.crowja.damiupos.model.User cur = uid > 0
                ? new com.crowja.damiupos.db.UserDao(DatabaseHelper.getInstance(this)).getById(uid)
                : null;
        marketingUser = cur != null && cur.isMarketing();
        // Hapus pelanggan: Admin saja (uid<=0 = mode single-user/owner = boleh). Non-admin: tombol
        // "Hapus" di mode seleksi disembunyikan + aksi di-guard.
        canDeleteCustomer = uid <= 0 || cur == null || cur.canDeleteCustomer();
        cbHariIni = findViewById(R.id.cbHariIni);
        if (marketingUser) {
            cbHariIni.setVisibility(View.VISIBLE);
            // Default dicentang — KECUALI filter "Ditambahkan" (static, lintas-activity) sedang
            // aktif: keduanya memfilter created_at, jadi jangan tampil saling bertentangan.
            cbHariIni.setChecked(createdFrom == null);
            cbHariIni.setOnCheckedChangeListener((b, checked) -> {
                // "Hari Ini" dan rentang "Ditambahkan" sama-sama memfilter created_at → jangan
                // berlaku bersamaan; mengaktifkan "Hari Ini" mereset rentang ke Semua.
                if (checked) clearDateRange();
                loadCustomers(etSearch.getText().toString().trim());
            });
        }

        // "Hanya Pelanggan Saya" — semua role. Pelanggan sekarang branch-wide (tersinkron ke semua
        // perangkat); centang untuk hanya menampilkan milik perangkat ini.
        cbBermasalah = findViewById(R.id.cbBermasalah);
        cbBermasalah.setOnCheckedChangeListener((b, checked) ->
                loadCustomers(etSearch.getText().toString().trim()));
        cbWilayahSaya = findViewById(R.id.cbWilayahSaya);
        cbWilayahSaya.setOnCheckedChangeListener((b, checked) ->
                loadCustomers(etSearch.getText().toString().trim()));
        // Tampilkan filter wilayah hanya bila perangkat ini punya wilayah tertugas di config.
        if (deviceHasWilayah()) cbWilayahSaya.setVisibility(android.view.View.VISIBLE);
        cbSaya = findViewById(R.id.cbSaya);
        cbSaya.setOnCheckedChangeListener((b, checked) ->
                loadCustomers(etSearch.getText().toString().trim()));

        // Filter "Ditambahkan" — rentang tanggal pendaftaran pelanggan (untuk semua role).
        btnDateRange = findViewById(R.id.btnDateRange);
        btnDateRange.setOnClickListener(v -> pickDateRange());
        // Pulihkan tampilan filter yang masih aktif (state static, bertahan lintas-activity).
        btnDateRange.setText("Ditambahkan: " + createdLabel);

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                // Debounce: satukan ketikan cepat → 1 query, bukan 1 query per huruf (berat di HP lama).
                scheduleSearch(s.toString().trim());
            }
        });
    }

    private final android.os.Handler searchHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable pendingSearch;

    private void scheduleSearch(String keyword) {
        if (pendingSearch != null) searchHandler.removeCallbacks(pendingSearch);
        pendingSearch = () -> loadCustomers(keyword);
        searchHandler.postDelayed(pendingSearch, 280);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        searchHandler.removeCallbacksAndMessages(null);   // jangan biarkan debounce menembak activity mati
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

    /** Perangkat ini punya wilayah tertugas di config peta? (menentukan tampil-tidaknya filter wilayah). */
    private boolean deviceHasWilayah() {
        try {
            com.crowja.damiupos.sync.SyncSettings cfg =
                    new com.crowja.damiupos.sync.SyncSettings(new SettingsDao(DatabaseHelper.getInstance(this)));
            String myUuid = cfg.getDeviceUuid();
            org.json.JSONArray z = Wilayah.parseZones(cfg.getWilayahZones());
            if (z == null || myUuid == null || myUuid.isEmpty()) return false;
            for (int i = 0; i < z.length(); i++) {
                org.json.JSONObject o = z.optJSONObject(i);
                if (o != null && myUuid.equals(o.optString("device", ""))) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private void loadCustomers(String keyword) {
        // Rentang "Ditambahkan" mendahului centang "Hari Ini" (keduanya memfilter created_at).
        boolean rangeActive = createdFrom != null && createdTo != null;
        boolean todayOnly = !rangeActive && marketingUser && cbHariIni != null && cbHariIni.isChecked();
        // Label selalu memuat jumlah pelanggan HARI INI (terlepas dari status centang),
        // supaya marketing tahu capaian akuisisi hari berjalan sekilas pandang.
        if (marketingUser && cbHariIni != null) {
            cbHariIni.setText("Hanya Pelanggan Hari Ini (" + customerDao.countRegisteredToday() + ")");
        }
        List<Customer> list;
        if (keyword.isEmpty()) {
            if (rangeActive) list = customerDao.getAllCreatedBetween(sortMode, createdFrom, createdTo);
            else if (todayOnly) list = customerDao.getRegisteredToday(sortMode);
            else list = customerDao.getAll(sortMode);
        } else {
            if (rangeActive) list = customerDao.searchCreatedBetween(keyword, sortMode, createdFrom, createdTo);
            else if (todayOnly) list = customerDao.searchRegisteredToday(keyword, sortMode);
            else list = customerDao.search(keyword, sortMode);
        }
        // Dedup tampilan DULU: satukan salinan lintas-perangkat (nomor sama, atau nama bila tanpa
        // nomor) jadi satu baris + JUMLAHKAN agregatnya — wakil = salinan MILIK perangkat ini bila
        // ada. Merge sebelum filter "Hanya Pelanggan Saya" supaya total tidak under-report.
        list = CustomerDao.dedupeForDisplay(list);
        // "Hanya Pelanggan Saya" di level ORANG: rep dipilih mengutamakan salinan sendiri, jadi
        // rep.isMine()==true iff grup punya salinan milik perangkat ini. Tetap tampilkan total penuh.
        if (cbSaya != null && cbSaya.isChecked()) {
            List<Customer> mine = new java.util.ArrayList<>();
            for (Customer c : list) if (c.isMine()) mine.add(c);
            list = mine;
        }
        // Filter "Hanya Bermasalah": sisakan pelanggan dengan issue aktif (hasOpenIssue).
        if (cbBermasalah != null && cbBermasalah.isChecked()) {
            List<Customer> flagged = new java.util.ArrayList<>();
            for (Customer c : list) if (c.hasOpenIssue()) flagged.add(c);
            list = flagged;
        }
        // Filter "Hanya Pelanggan Wilayah Saya": pelanggan yang wilayah petanya (arah dari depot)
        // ditugaskan ke PERANGKAT INI menurut config wilayah web.
        if (cbWilayahSaya != null && cbWilayahSaya.isChecked()) {
            com.crowja.damiupos.sync.SyncSettings cfg =
                    new com.crowja.damiupos.sync.SyncSettings(new SettingsDao(DatabaseHelper.getInstance(this)));
            String center = cfg.getBranchCenter(), zones = cfg.getWilayahZones(), myUuid = cfg.getDeviceUuid();
            List<Customer> mine = new java.util.ArrayList<>();
            for (Customer c : list) {
                // Perangkat EFEKTIF: override per-pelanggan menang atas wilayah otomatis.
                String dev = Wilayah.effectiveDevice(c.getAssignedDeviceUuid(), center, zones,
                        c.getLatitude(), c.getLongitude());
                if (dev != null && dev.equals(myUuid)) mine.add(c);
            }
            list = mine;
        }
        // Filter "Wilayah Administratif" (Kecamatan/Desa, hasil reverse-geocode server).
        if (selectedKecamatan != null) {
            List<Customer> f = new java.util.ArrayList<>();
            for (Customer c : list) if (selectedKecamatan.equals(c.getKecamatan())) f.add(c);
            list = f;
        }
        if (selectedDesa != null) {
            List<Customer> f = new java.util.ArrayList<>();
            for (Customer c : list) if (selectedDesa.equals(c.getDesa())) f.add(c);
            list = f;
        }
        // Konsumsi gl/hr butuh perhitungan hari → sort di Java (DAO balikin urut nama).
        if (sortMode == CustomerDao.SORT_KONSUMSI) {
            java.util.Collections.sort(list, (a, b) ->
                    Double.compare(b.getKonsumsiPerHari(), a.getKonsumsiPerHari()));
        }
        adapter.setData(list);

        if (list.isEmpty()) {
            tvEmpty.setText(rangeActive
                    ? "Tidak ada pelanggan ditambahkan pada rentang ini"
                    : todayOnly
                        ? "Belum ada pelanggan baru hari ini"
                        : getString(R.string.belum_ada_pelanggan));
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
            // Selection mode — "Hapus" hanya untuk Admin/owner; Pilih Semua tetap untuk semua.
            if (canDeleteCustomer) {
                menu.add(0, 101, 0, "Hapus")
                        .setIcon(android.R.drawable.ic_menu_delete)
                        .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
            }
            menu.add(0, 102, 1, "Pilih Semua")
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        } else {
            // Normal mode — menu existing
            menu.add(0, 4, 0, "Peta Persebaran")
                    .setIcon(android.R.drawable.ic_dialog_map)
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
            menu.add(0, 2, 1, "Urutkan")
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
            menu.add(0, 1, 2, "Sinkronisasi dari Kontak")
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
            menu.add(0, 3, 3, "Hapus Duplikat")
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
            String wilayahLabel = "Wilayah Administratif"
                    + (selectedKecamatan != null || selectedDesa != null ? " ●" : "");
            menu.add(0, 5, 4, wilayahLabel)
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == 1) { syncFromContacts(); return true; }
        if (id == 2) { showSortDialog(); return true; }
        if (id == 3) { confirmDedupe(); return true; }
        if (id == 5) { showAdminAreaFilterDialog(); return true; }
        if (id == 4) { startActivity(new Intent(this, CustomerMapActivity.class)); return true; }
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

    /** Dialog filter "Wilayah Administratif": 2 dropdown independen (Kecamatan/Desa) dari
     *  hasil reverse-geocode koordinat pelanggan (server) — "Semua" = tanpa filter level itu. */
    private void showAdminAreaFilterDialog() {
        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = Math.round(16 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad / 2, pad, 0);

        final android.widget.Spinner[] spinners = new android.widget.Spinner[2];
        String[] labels = {"Kecamatan", "Desa"};
        List<List<String>> options = java.util.Arrays.asList(
                customerDao.getDistinctKecamatan(), customerDao.getDistinctDesa());
        String[] current = {selectedKecamatan, selectedDesa};

        for (int i = 0; i < 2; i++) {
            android.widget.TextView label = new android.widget.TextView(this);
            label.setText(labels[i]);
            label.setTextSize(12f);
            label.setPadding(0, pad / 2, 0, 0);
            root.addView(label);

            List<String> opts = new java.util.ArrayList<>();
            opts.add("Semua " + labels[i]);
            opts.addAll(options.get(i));
            android.widget.ArrayAdapter<String> ad = new android.widget.ArrayAdapter<>(
                    this, android.R.layout.simple_spinner_item, opts);
            ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            android.widget.Spinner sp = new android.widget.Spinner(this);
            sp.setAdapter(ad);
            int sel = current[i] != null ? opts.indexOf(current[i]) : 0;
            sp.setSelection(Math.max(0, sel));
            root.addView(sp);
            spinners[i] = sp;
        }

        new AlertDialog.Builder(this)
                .setTitle("Wilayah Administratif")
                .setView(root)
                .setPositiveButton("Terapkan", (d, w) -> {
                    selectedKecamatan = spinners[0].getSelectedItemPosition() == 0 ? null
                            : (String) spinners[0].getSelectedItem();
                    selectedDesa = spinners[1].getSelectedItemPosition() == 0 ? null
                            : (String) spinners[1].getSelectedItem();
                    invalidateOptionsMenu();
                    loadCustomers(etSearch.getText().toString().trim());
                })
                .setNeutralButton("Reset", (d, w) -> {
                    selectedKecamatan = null;
                    selectedDesa = null;
                    invalidateOptionsMenu();
                    loadCustomers(etSearch.getText().toString().trim());
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    // ==========================================================================
    // Filter "Ditambahkan" (rentang tanggal pendaftaran / created_at)
    // ==========================================================================

    /** Dialog preset rentang tanggal "Ditambahkan" (+ opsi rentang kustom). */
    private void pickDateRange() {
        String[] options = {"Semua Tanggal (Clear)", "Hari Ini", "7 Hari Terakhir",
                "30 Hari Terakhir", "Bulan Ini", "Rentang Kustom..."};
        new AlertDialog.Builder(this)
                .setTitle("Ditambahkan (Tanggal Daftar)")
                .setItems(options, (d, which) -> {
                    Calendar cal = Calendar.getInstance();
                    String today = fmt(cal);
                    switch (which) {
                        case 0: applyDateRange(null, null, "Semua"); break;
                        case 1: applyDateRange(today, today, "Hari Ini"); break;
                        case 2:
                            cal.add(Calendar.DAY_OF_YEAR, -6);
                            applyDateRange(fmt(cal), today, "7 Hari"); break;
                        case 3:
                            cal.add(Calendar.DAY_OF_YEAR, -29);
                            applyDateRange(fmt(cal), today, "30 Hari"); break;
                        case 4:
                            cal.set(Calendar.DAY_OF_MONTH, 1);
                            applyDateRange(fmt(cal), today, "Bulan Ini"); break;
                        case 5: pickCustomRange(); break;
                    }
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    /** Rentang kustom: pilih tanggal awal lalu tanggal akhir (dibalik otomatis bila terbalik). */
    private void pickCustomRange() {
        Calendar cal = Calendar.getInstance();
        DatePickerDialog start = new DatePickerDialog(this, (vS, y1, m1, d1) -> {
            Calendar s = Calendar.getInstance();
            s.set(y1, m1, d1);
            String sd = fmt(s);
            DatePickerDialog end = new DatePickerDialog(this, (vE, y2, m2, d2) -> {
                Calendar e = Calendar.getInstance();
                e.set(y2, m2, d2);
                String ed = fmt(e);
                String from = sd, to = ed;
                if (from.compareTo(to) > 0) { String tmp = from; from = to; to = tmp; }
                applyDateRange(from, to, from + " → " + to);
            }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH));
            end.setTitle("Tanggal Akhir");
            end.show();
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH));
        start.setTitle("Tanggal Awal");
        start.show();
    }

    /** Terapkan rentang (from/to = null → Semua) + segarkan daftar tepat sekali.
     *  State-nya static → tetap berlaku saat user berpindah activity (sampai Clear / app restart). */
    private void applyDateRange(String from, String to, String label) {
        createdFrom = from;
        createdTo = to;
        createdLabel = label;
        btnDateRange.setText("Ditambahkan: " + label);
        // Memilih tanggal via tombol = mengambil alih filter created_at → matikan centang "Hari Ini"
        // (marketing) supaya tak saling menimpa, termasuk saat memilih "Semua" (lihat semua tanggal).
        // setChecked(false) sudah memicu reload (nilai rentang sudah di-set), jadi tak memuat dobel.
        if (marketingUser && cbHariIni != null && cbHariIni.isChecked()) {
            cbHariIni.setChecked(false);
        } else {
            loadCustomers(etSearch.getText().toString().trim());
        }
    }

    /** Reset rentang ke Semua (tanpa reload — pemanggil yang memuat ulang). */
    private void clearDateRange() {
        createdFrom = null;
        createdTo = null;
        createdLabel = "Semua";
        if (btnDateRange != null) btnDateRange.setText("Ditambahkan: Semua");
    }

    private String fmt(Calendar c) {
        return String.format(Locale.US, "%04d-%02d-%02d",
                c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }

    /** Konfirmasi lalu jalankan pembersihan duplikat pelanggan. */
    private void confirmDedupe() {
        new AlertDialog.Builder(this)
                .setTitle("Hapus Duplikat")
                .setMessage("Rapikan daftar pelanggan?\n\n"
                        + "1. Nomor HP SAMA → digabungkan jadi satu (diutamakan yang punya "
                        + "transaksi, koordinat, lalu foto; data terkait dialihkan, sisanya dihapus).\n\n"
                        + "2. Nama SAMA tapi nomor BERBEDA (mis. satu usaha dua nomor) → "
                        + "diberi nomor \"Nama #1\", \"Nama #2\", … (tidak dihapus).")
                .setPositiveButton("Rapikan", (d, w) -> runDedupe())
                .setNegativeButton("Batal", null)
                .show();
    }

    private void runDedupe() {
        Toast.makeText(this, "Merapikan pelanggan…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            final CustomerDao.DedupeResult r = customerDao.mergeDuplicatesByPhone();
            final int renamed = customerDao.numberDuplicateNames();   // setelah merge
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                // Dorong perubahan (penggabungan + penomoran + tombstone) ke dashboard.
                com.crowja.damiupos.sync.SyncScheduler.syncNow(getApplicationContext());
                loadCustomers(etSearch.getText().toString().trim());
                StringBuilder sb = new StringBuilder();
                if (r.deleted > 0) {
                    sb.append(r.deleted).append(" pelanggan duplikat (nomor HP sama) digabungkan dari ")
                            .append(r.groups).append(" nomor.\n");
                }
                if (renamed > 0) {
                    sb.append(renamed).append(" pelanggan bernama sama diberi nomor #1, #2, ….\n");
                }
                if (sb.length() == 0) sb.append("Tidak ada duplikat nomor HP atau nama sama ditemukan.");
                new AlertDialog.Builder(this)
                        .setTitle("Rapikan Pelanggan Selesai")
                        .setMessage(sb.toString().trim())
                        .setPositiveButton("OK", null)
                        .show();
            });
        }).start();
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
        if (!canDeleteCustomer) {   // guard: non-admin tak boleh hapus (mis. via jalur tak terduga)
            Toast.makeText(this, "Hanya Admin yang boleh menghapus pelanggan", Toast.LENGTH_SHORT).show();
            return;
        }
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
