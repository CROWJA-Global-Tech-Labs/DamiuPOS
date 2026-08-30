package com.crowja.damiupos;

import com.crowja.damiupos.map.LiveDeviceOverlay;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.webkit.WebView;
import android.webkit.WebSettings;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.imageview.ShapeableImageView;

import java.io.File;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.crowja.damiupos.adapter.TransactionAdapter;
import com.crowja.damiupos.db.CustomerDao;
import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.TransactionDao;
import com.crowja.damiupos.model.Customer;
import com.crowja.damiupos.model.Transaction;

import java.util.List;

public class CustomerDetailActivity extends AppCompatActivity {

    /** Menu: tandai "Sudah Order Ulang" (serah-terima pelanggan marketing → perangkat lain). */
    private static final int MENU_HANDOFF = 10;
    /** Menu: buat & bagikan link publik 7 hari (kartu info pelanggan). */
    private static final int MENU_SHARE_LINK = 11;
    /** Menu: tandai detail pelanggan bermasalah (nomor/koordinat/dll keliru) agar diperbaiki. */
    private static final int MENU_FLAG_PROBLEM = 12;
    /** Menu: tandai pelanggan PRIORITAS (utamakan → naik ke atas antrian delivery & follow up). */
    private static final int MENU_PRIORITY = 13;

    /** Kategori masalah: kunci tersinkron (cermin web) → label Indonesia. Urutan dijaga. */
    private static final String[] ISSUE_KEYS = {"phone", "coordinate", "photo", "address", "other"};
    private static final String[] ISSUE_LABELS = {
            "Nomor HP tidak sesuai", "Koordinat/lokasi tidak sesuai",
            "Foto tidak sesuai", "Alamat tidak lengkap/keliru", "Lainnya"};

    private long customerId;
    private DatabaseHelper dbHelper;
    private CustomerDao customerDao;
    private TransactionDao transactionDao;
    private com.crowja.damiupos.db.SettingsDao settingsDao;
    private Customer currentCustomer;

    private TextView tvNama, tvTelepon, tvAlamat, tvCatatanOrder, tvOrigin, tvAfiliasi, tvIssueBanner;
    private TextView tvSaldoGalon;
    private MaterialCardView cardHutang;
    private TextView tvHutangSisa;
    private TextView tvHutangRiwayat;
    private com.google.android.material.button.MaterialButton btnBayarHutang;
    private com.google.android.material.button.MaterialButton btnCatatHutang;
    private com.crowja.damiupos.db.CustomerDebtDao debtDao;
    private TextView tvEmptyHistory, tvHistoryHeader, tvHistoryNote;
    private RecyclerView rvTransactions;
    private TransactionAdapter adapter;
    private ShapeableImageView ivFoto;
    private MaterialCardView cardMap;
    private WebView webMap;
    private boolean mapLoaded;

    /** Pin posisi LIVE perangkat lain — dipasang di semua peta aplikasi. */
    private LiveDeviceOverlay liveDev;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_customer_detail);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        dbHelper = DatabaseHelper.getInstance(this);
        customerDao = new CustomerDao(dbHelper);
        transactionDao = new TransactionDao(dbHelper);
        settingsDao = new com.crowja.damiupos.db.SettingsDao(dbHelper);

        customerId = getIntent().getLongExtra("customer_id", -1);
        if (customerId == -1) {
            finish();
            return;
        }

        tvNama = findViewById(R.id.tvNama);
        tvTelepon = findViewById(R.id.tvTelepon);
        tvAlamat = findViewById(R.id.tvAlamat);
        tvCatatanOrder = findViewById(R.id.tvCatatanOrder);
        tvCatatanOrder.setOnClickListener(v -> showCatatanOrderDialog());
        tvOrigin = findViewById(R.id.tvOrigin);
        tvAfiliasi = findViewById(R.id.tvAfiliasi);
        tvIssueBanner = findViewById(R.id.tvIssueBanner);
        tvIssueBanner.setOnClickListener(v -> showFlagProblemDialog());
        tvHistoryHeader = findViewById(R.id.tvHistoryHeader);
        tvHistoryNote = findViewById(R.id.tvHistoryNote);
        ivFoto = findViewById(R.id.ivFoto);
        cardMap = findViewById(R.id.cardMap);
        webMap = findViewById(R.id.webMap);
        tvSaldoGalon = findViewById(R.id.tvSaldoGalon);
        cardHutang = findViewById(R.id.cardHutang);
        tvHutangSisa = findViewById(R.id.tvHutangSisa);
        tvHutangRiwayat = findViewById(R.id.tvHutangRiwayat);
        btnBayarHutang = findViewById(R.id.btnBayarHutang);
        btnCatatHutang = findViewById(R.id.btnCatatHutang);
        debtDao = new com.crowja.damiupos.db.CustomerDebtDao(DatabaseHelper.getInstance(this));
        btnBayarHutang.setOnClickListener(v -> showDebtDialog(false));
        btnCatatHutang.setOnClickListener(v -> showDebtDialog(true));
        tvEmptyHistory = findViewById(R.id.tvEmptyHistory);
        rvTransactions = findViewById(R.id.rvTransactions);

        adapter = new TransactionAdapter(false);
        rvTransactions.setLayoutManager(new LinearLayoutManager(this));
        // Tanpa setHasFixedSize: RV riwayat ini tingginya wrap_content (di dalam ScrollView),
        // ukurannya berubah mengikuti isi → setHasFixedSize(true) salah di sini.
        rvTransactions.setAdapter(adapter);

        adapter.setOnItemClickListener(trx -> {
            boolean hasStruk = Transaction.TYPE_JUAL.equals(trx.getType())
                    || trx.getTotalHarga() > 0; // ganti rugi KEMBALI
            if (!hasStruk) {
                Toast.makeText(this, "Struk hanya tersedia untuk transaksi penjualan atau ganti rugi",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            Intent intent = new Intent(this, ReceiptActivity.class);
            intent.putExtra(ReceiptActivity.EXTRA_TRANSACTION_ID, trx.getId());
            startActivity(intent);
        });

        // WhatsApp button
        findViewById(R.id.btnWhatsapp).setOnClickListener(v -> openWhatsApp());

        // Edit button
        findViewById(R.id.btnEdit).setOnClickListener(v -> {
            Intent intent = new Intent(this, CustomerFormActivity.class);
            intent.putExtra("customer_id", customerId);
            startActivity(intent);
        });

        // Delete button — HANYA Admin (& mode single-user/owner). Non-admin: sembunyikan.
        View btnHapus = findViewById(R.id.btnHapus);
        btnHapus.setOnClickListener(v -> confirmDelete());
        btnHapus.setVisibility(canDeleteCustomer() ? View.VISIBLE : View.GONE);

        // FAB - new transaction for this customer
        findViewById(R.id.fabAddTransaction).setOnClickListener(v -> {
            Intent intent = new Intent(this, TransactionActivity.class);
            intent.putExtra("customer_id", customerId);
            intent.putExtra("type", Transaction.TYPE_JUAL);
            startActivity(intent);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadData();
    }

    @Override
    public boolean onCreateOptionsMenu(android.view.Menu menu) {
        menu.add(0, MENU_HANDOFF, 0, "✔ Sudah Order Ulang")
                .setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_NEVER);
        menu.add(0, MENU_SHARE_LINK, 1, "🔗 Bagikan Link Publik (7 hari)")
                .setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_NEVER);
        menu.add(0, MENU_FLAG_PROBLEM, 2, "⚠️ Tandai Bermasalah")
                .setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_NEVER);
        menu.add(0, MENU_PRIORITY, 3, "⭐ Jadikan Prioritas")
                .setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_NEVER);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(android.view.Menu menu) {
        android.view.MenuItem item = menu.findItem(MENU_HANDOFF);
        if (item != null) {
            item.setVisible(canShowHandoff());
        }
        android.view.MenuItem share = menu.findItem(MENU_SHARE_LINK);
        if (share != null) {
            share.setVisible(canShowShareLink());
        }
        android.view.MenuItem flag = menu.findItem(MENU_FLAG_PROBLEM);
        if (flag != null) {
            boolean open = currentCustomer != null && currentCustomer.hasOpenIssue();
            flag.setTitle(open ? "⚠️ Ubah Laporan Masalah" : "⚠️ Tandai Bermasalah");
        }
        android.view.MenuItem prio = menu.findItem(MENU_PRIORITY);
        if (prio != null) {
            boolean p = currentCustomer != null && currentCustomer.isPriority();
            prio.setTitle(p ? "⭐ Batalkan Prioritas" : "⭐ Jadikan Prioritas");
        }
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(android.view.MenuItem item) {
        if (item.getItemId() == MENU_HANDOFF) {
            confirmHandoff();
            return true;
        }
        if (item.getItemId() == MENU_SHARE_LINK) {
            sharePublicLink();
            return true;
        }
        if (item.getItemId() == MENU_FLAG_PROBLEM) {
            showFlagProblemDialog();
            return true;
        }
        if (item.getItemId() == MENU_PRIORITY) {
            showPriorityDialog();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /** Link publik butuh server → hanya tampil saat sinkronisasi online aktif & pelanggan bukan "Umum". */
    private boolean canShowShareLink() {
        if (currentCustomer == null || CustomerDao.UMUM_NAME.equalsIgnoreCase(
                currentCustomer.getName() != null ? currentCustomer.getName().trim() : "")) {
            return false;
        }
        com.crowja.damiupos.sync.SyncSettings sync =
                new com.crowja.damiupos.sync.SyncSettings(settingsDao);
        return sync.isEnabled() && !sync.getDeviceUuid().isEmpty() && !sync.getToken().isEmpty();
    }

    /**
     * Minta server membuat link publik 7 hari untuk pelanggan ini (butuh online), lalu buka pemilih
     * "Bagikan" (WhatsApp, dsb.) dengan URL-nya. Kartu di link menampilkan nama, telp→wa.me, harga
     * produk, wajib ongkir, foto + koordinat.
     */
    private void sharePublicLink() {
        String syncUuid = customerDao.getSyncUuidById(customerId);
        if (syncUuid == null || syncUuid.isEmpty()) {
            Toast.makeText(this, "Pelanggan belum tersinkron — coba lagi setelah sinkronisasi.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, "Membuat link…", Toast.LENGTH_SHORT).show();
        final com.crowja.damiupos.sync.SyncSettings cfg =
                new com.crowja.damiupos.sync.SyncSettings(settingsDao);
        final String name = currentCustomer != null && currentCustomer.getName() != null
                ? currentCustomer.getName() : "Pelanggan";
        new Thread(() -> {
            String url = null;
            try {
                org.json.JSONObject r = new com.crowja.damiupos.sync.SyncApi(cfg).createCustomerShareLink(syncUuid);
                url = r.optString("url", "");
            } catch (Exception ignored) { /* offline / server error → tampilkan pesan gagal */ }
            final String finalUrl = url;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (finalUrl == null || finalUrl.isEmpty()) {
                    Toast.makeText(this, "Gagal membuat link. Pastikan perangkat online lalu coba lagi.",
                            Toast.LENGTH_LONG).show();
                    return;
                }
                Intent share = new Intent(Intent.ACTION_SEND);
                share.setType("text/plain");
                share.putExtra(Intent.EXTRA_TEXT, "Info pelanggan " + name + " (berlaku 7 hari):\n" + finalUrl);
                startActivity(Intent.createChooser(share, "Bagikan Link Pelanggan"));
            });
        }).start();
    }

    /**
     * Menu "Sudah Order Ulang" tampil hanya bila: peran boleh (Admin/Marketing — single-user
     * tanpa login = boleh), sinkronisasi online aktif (serah-terima butuh server), pelanggan
     * bukan "Umum", dan belum pernah diserahterimakan.
     */
    /** Boleh hapus pelanggan? Admin saja; mode single-user (uid<=0) = owner = boleh. */
    private boolean canDeleteCustomer() {
        long uid = settingsDao.getCurrentUserId();
        if (uid <= 0) return true;
        com.crowja.damiupos.model.User u = new com.crowja.damiupos.db.UserDao(dbHelper).getById(uid);
        return u == null || u.canDeleteCustomer();
    }

    private boolean canShowHandoff() {
        if (currentCustomer == null || currentCustomer.isHandedOver()) return false;
        if (CustomerDao.UMUM_NAME.equalsIgnoreCase(
                currentCustomer.getName() != null ? currentCustomer.getName().trim() : "")) {
            return false;
        }
        com.crowja.damiupos.sync.SyncSettings sync =
                new com.crowja.damiupos.sync.SyncSettings(settingsDao);
        if (!sync.isEnabled() || sync.getDeviceUuid().isEmpty()) return false;
        long uid = settingsDao.getCurrentUserId();
        if (uid <= 0) return true;   // single-user (tanpa login) = akses penuh
        com.crowja.damiupos.model.User u = new com.crowja.damiupos.db.UserDao(dbHelper).getById(uid);
        return u == null || u.canHandoffCustomer();
    }

    /**
     * Konfirmasi lalu tandai "Sudah Order Ulang": pelanggan disembunyikan dari daftar perangkat
     * INI dan (lewat sync) dikirim ke SEMUA perangkat lain di cabang + dashboard. Data & riwayat
     * tidak dihapus. Offline-first — kalau sedang offline, serah-terima terjadi di sync berikutnya.
     */
    private void confirmHandoff() {
        // Pilih KAPAN pelanggan terakhir order (default hari ini/sekarang) via date + time picker,
        // lalu konfirmasi. Waktu itu jadi tanggal serah-terima (handed_over_at).
        final java.util.Calendar cal = java.util.Calendar.getInstance();
        android.app.DatePickerDialog dp = new android.app.DatePickerDialog(this, (view, y, m, d) -> {
            cal.set(java.util.Calendar.YEAR, y);
            cal.set(java.util.Calendar.MONTH, m);
            cal.set(java.util.Calendar.DAY_OF_MONTH, d);
            new android.app.TimePickerDialog(this, (tv, h, min) -> {
                cal.set(java.util.Calendar.HOUR_OF_DAY, h);
                cal.set(java.util.Calendar.MINUTE, min);
                cal.set(java.util.Calendar.SECOND, 0);
                confirmHandoffAt(cal);
            }, cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE), true).show();
        }, cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH), cal.get(java.util.Calendar.DAY_OF_MONTH));
        dp.setTitle("Kapan terakhir order?");
        dp.getDatePicker().setMaxDate(System.currentTimeMillis());   // order terakhir tak mungkin di masa depan
        dp.show();
    }

    /** Konfirmasi akhir + tandai "Sudah Order Ulang" pada waktu order terakhir yang dipilih. */
    private void confirmHandoffAt(java.util.Calendar cal) {
        String name = currentCustomer != null && currentCustomer.getName() != null
                ? currentCustomer.getName() : "Pelanggan";
        final String ts = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                .format(cal.getTime());
        String tsLabel = new java.text.SimpleDateFormat("d MMM yyyy HH:mm", new java.util.Locale("id"))
                .format(cal.getTime());
        new AlertDialog.Builder(this)
                .setTitle("Sudah Order Ulang?")
                .setMessage("Tandai \"" + name + "\" sudah order ulang pada " + tsLabel + "?\n\n"
                        + "• Pelanggan akan HILANG dari daftar di perangkat ini\n"
                        + "• Otomatis DIKIRIM ke perangkat depot lain & dashboard\n"
                        + "• Riwayat transaksi tidak dihapus")
                .setNegativeButton("Batal", null)
                .setPositiveButton("Tandai", (d, w) -> {
                    com.crowja.damiupos.sync.SyncSettings sync =
                            new com.crowja.damiupos.sync.SyncSettings(settingsDao);
                    customerDao.markHandedOver(customerId, sync.getDeviceUuid(), ts);
                    com.crowja.damiupos.sync.SyncScheduler.syncNow(getApplicationContext());
                    Toast.makeText(this, name + " ditandai sudah order ulang — dikirim ke perangkat lain",
                            Toast.LENGTH_LONG).show();
                    finish();   // kembali ke daftar (pelanggan sudah tidak tampil di sini)
                })
                .show();
    }

    /**
     * "Tandai Bermasalah": laporkan detail pelanggan yang keliru (nomor/koordinat/foto/alamat/lainnya)
     * agar owner/admin memperbaiki. Checkbox kategori + catatan opsional; bila sudah ada masalah aktif,
     * kategori/catatan sebelumnya di-pra-isi dan tersedia tombol netral "Sudah diperbaiki". Tersinkron
     * dua-arah lewat kolom issue_* (cermin web).
     */
    /** Label Indonesia untuk kunci kategori masalah; kunci tak dikenal → kunci apa adanya. */
    private String issueLabelFor(String key) {
        for (int i = 0; i < ISSUE_KEYS.length; i++) {
            if (ISSUE_KEYS[i].equals(key)) {
                return ISSUE_LABELS[i];
            }
        }
        return key;
    }

    /**
     * Ubah Catatan Order (instruksi pengiriman tetap, mis. "titip satpam") — otomatis ditambahkan
     * ke SETIAP transaksi baru pelanggan ini (cermin TransactionActivity.doSave / web storePending).
     * Tersinkron dua-arah lewat customers.order_note.
     */
    private void showCatatanOrderDialog() {
        if (currentCustomer == null) {
            return;
        }
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setHint("mis. Titip satpam gerbang selatan");
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setMaxLines(4);
        if (currentCustomer.getOrderNote() != null) {
            input.setText(currentCustomer.getOrderNote());
            input.setSelection(input.getText().length());
        }
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        input.setPadding(pad, pad / 2, pad, pad / 2);

        new AlertDialog.Builder(this)
                .setTitle("Catatan Order")
                .setMessage("Ditambahkan otomatis ke catatan tiap transaksi baru pelanggan ini.")
                .setView(input)
                .setNegativeButton("Batal", null)
                .setPositiveButton("Simpan", (d, w) -> {
                    String note = input.getText() != null ? input.getText().toString() : "";
                    customerDao.updateOrderNote(customerId, note);
                    com.crowja.damiupos.sync.SyncScheduler.syncNow(getApplicationContext());
                    loadData();
                })
                .show();
    }

    private void showFlagProblemDialog() {
        if (currentCustomer == null) {
            return;
        }
        final String name = currentCustomer.getName() != null ? currentCustomer.getName() : "Pelanggan";
        final java.util.List<String> active = currentCustomer.issueFlagList();

        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, 0);

        final android.widget.CheckBox[] boxes = new android.widget.CheckBox[ISSUE_KEYS.length];
        for (int i = 0; i < ISSUE_KEYS.length; i++) {
            android.widget.CheckBox cb = new android.widget.CheckBox(this);
            cb.setText(ISSUE_LABELS[i]);
            cb.setChecked(active.contains(ISSUE_KEYS[i]));
            boxes[i] = cb;
            box.addView(cb);
        }
        final android.widget.EditText note = new android.widget.EditText(this);
        note.setHint("Catatan (opsional): jelaskan masalahnya…");
        note.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        note.setMaxLines(3);
        if (currentCustomer.hasOpenIssue() && currentCustomer.getIssueNote() != null) {
            note.setText(currentCustomer.getIssueNote());
        }
        box.addView(note);

        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(currentCustomer.hasOpenIssue() ? "Ubah Laporan Masalah" : "Tandai Bermasalah")
                .setView(box)
                .setNegativeButton("Batal", null)
                .setPositiveButton("Simpan Laporan", null);   // override nanti agar validasi tak menutup dialog
        if (currentCustomer.hasOpenIssue()) {
            // "Sudah diperbaiki" = ajukan penyelesaian + catatan → persetujuan owner via email laporan
            // (perangkat standalone: ditandai selesai lokal). Bukan lagi penutupan langsung.
            b.setNeutralButton("✓ Sudah diperbaiki", (d, w) ->
                    IssueResolveDialog.show(this, currentCustomer, "detail", this::loadData));
        }
        final AlertDialog dlg = b.create();
        dlg.setOnShowListener(di -> dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            java.util.List<String> picked = new java.util.ArrayList<>();
            for (int i = 0; i < boxes.length; i++) {
                if (boxes[i].isChecked()) {
                    picked.add(ISSUE_KEYS[i]);
                }
            }
            if (picked.isEmpty()) {
                Toast.makeText(this, "Pilih minimal satu jenis masalah", Toast.LENGTH_SHORT).show();
                return;
            }
            String reporter = settingsDao.getCurrentUserName();
            if (reporter == null || reporter.isEmpty()) {
                reporter = "(perangkat)";
            }
            customerDao.markProblematic(customerId, android.text.TextUtils.join(",", picked),
                    note.getText().toString(), reporter);
            com.crowja.damiupos.sync.SyncScheduler.syncNow(getApplicationContext());
            Toast.makeText(this, name + " ditandai bermasalah — dikirim ke dashboard & perangkat lain",
                    Toast.LENGTH_LONG).show();
            dlg.dismiss();
            loadData();
        }));
        dlg.show();
    }

    /** Tandai/batalkan pelanggan PRIORITAS. Bila sudah prioritas → tawaran batalkan; selain itu
     *  input alasan opsional + jadikan prioritas. Tersinkron dua-arah ke web & perangkat lain. */
    private void showPriorityDialog() {
        if (currentCustomer == null) {
            return;
        }
        final String name = currentCustomer.getName() != null ? currentCustomer.getName() : "Pelanggan";

        if (currentCustomer.isPriority()) {
            new AlertDialog.Builder(this)
                    .setTitle("Pelanggan Prioritas")
                    .setMessage(currentCustomer.getPriorityReason() != null
                            ? ("Alasan: " + currentCustomer.getPriorityReason())
                            : "Pelanggan ini ditandai prioritas.")
                    .setNegativeButton("Tutup", null)
                    .setPositiveButton("Batalkan Prioritas", (d, w) -> {
                        customerDao.clearPriority(customerId);
                        com.crowja.damiupos.sync.SyncScheduler.syncNow(getApplicationContext());
                        Toast.makeText(this, "Status prioritas " + name + " dibatalkan", Toast.LENGTH_LONG).show();
                        loadData();
                    })
                    .show();
            return;
        }

        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        final android.widget.EditText reason = new android.widget.EditText(this);
        reason.setHint("Alasan (opsional): mis. langganan besar…");
        reason.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        reason.setMaxLines(2);
        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(reason);

        new AlertDialog.Builder(this)
                .setTitle("Jadikan Prioritas")
                .setMessage("Pelanggan prioritas naik ke atas antrian delivery & follow up.")
                .setView(box)
                .setNegativeButton("Batal", null)
                .setPositiveButton("⭐ Jadikan Prioritas", (d, w) -> {
                    String by = settingsDao.getCurrentUserName();
                    if (by == null || by.isEmpty()) {
                        by = "(perangkat)";
                    }
                    customerDao.markPriority(customerId, reason.getText().toString(), by);
                    com.crowja.damiupos.sync.SyncScheduler.syncNow(getApplicationContext());
                    Toast.makeText(this, name + " ditandai PRIORITAS — dikirim ke dashboard & perangkat lain",
                            Toast.LENGTH_LONG).show();
                    loadData();
                })
                .show();
    }

    /**
     * Kartu HUTANG: sisa + tiga baris riwayat terakhir. Kartu SELALU tampil (bukan hanya saat
     * berhutang) supaya "+ Hutang" tetap terjangkau untuk mencatat kurang-bayar; tombol pelunasan
     * yang disembunyikan saat tak ada hutang. Sisa dihitung dari buku besar, tak pernah disimpan
     * sebagai kolom — lihat CustomerDebtDao.
     */
    private void renderHutang() {
        double sisa = debtDao.balanceFor(customerId);
        cardHutang.setVisibility(View.VISIBLE);
        tvHutangSisa.setText(rupiah(sisa));
        btnBayarHutang.setVisibility(sisa > 0 ? View.VISIBLE : View.GONE);

        java.util.List<com.crowja.damiupos.db.CustomerDebtDao.Entry> riwayat = debtDao.historyFor(customerId, 3);
        if (riwayat.isEmpty()) {
            tvHutangRiwayat.setText("Belum pernah berhutang.");
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (com.crowja.damiupos.db.CustomerDebtDao.Entry e : riwayat) {
            if (sb.length() > 0) sb.append("\n");
            sb.append(e.isDebt() ? "+ " : "− ").append(rupiah(e.amount))
              .append(e.isDebt() ? " berhutang" : " dibayar");
            if (e.byName != null && !e.byName.isEmpty()) sb.append(" · ").append(e.byName);
        }
        tvHutangRiwayat.setText(sb.toString());
    }

    private String rupiah(double v) {
        return "Rp " + java.text.NumberFormat.getNumberInstance(new java.util.Locale("in", "ID"))
                .format(Math.round(v));
    }

    /**
     * Dialog catat hutang ({@code tambah}) atau catat pembayaran. Pembayaran boleh DICICIL dan
     * dipagari sisa hutang di DAO — nominal dari dialog tak dipercaya mentah. Nama pencatat ikut
     * tersimpan supaya jelas siapa yang menerima uangnya.
     */
    private void showDebtDialog(boolean tambah) {
        double sisa = debtDao.balanceFor(customerId);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);

        final android.widget.EditText nominal = new android.widget.EditText(this);
        nominal.setHint("Nominal (Rp)");
        nominal.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        if (!tambah && sisa > 0) nominal.setText(String.valueOf((long) Math.round(sisa)));

        final android.widget.EditText alasan = new android.widget.EditText(this);
        alasan.setHint(tambah ? "Alasan (opsional)" : "Catatan (opsional)");
        alasan.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        alasan.setMaxLines(2);

        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(nominal);
        box.addView(alasan);

        new AlertDialog.Builder(this)
                .setTitle(tambah ? "Catat Hutang" : "Catat Pembayaran")
                .setMessage(tambah
                        ? "Hutang di luar penjualan (mis. kurang bayar)."
                        : "Sisa hutang " + rupiah(sisa) + " — boleh dibayar sebagian.")
                .setView(box)
                .setNegativeButton("Batal", null)
                .setPositiveButton("Simpan", (d, w) -> {
                    double amt;
                    try {
                        amt = Double.parseDouble(nominal.getText().toString().trim());
                    } catch (NumberFormatException ex) {
                        amt = 0;
                    }
                    if (amt <= 0) {
                        Toast.makeText(this, "Nominal belum diisi", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    String by = settingsDao.getCurrentUserName();
                    if (by == null || by.isEmpty()) by = "(perangkat)";
                    String note = alasan.getText().toString().trim();
                    if (tambah) {
                        debtDao.charge(customerId, amt, note.isEmpty() ? null : note, null, by);
                        Toast.makeText(this, "Hutang dicatat: " + rupiah(amt), Toast.LENGTH_LONG).show();
                    } else {
                        double paid = debtDao.pay(customerId, amt, null, by, note.isEmpty() ? null : note);
                        double after = debtDao.balanceFor(customerId);
                        Toast.makeText(this, paid <= 0
                                ? "Tidak ada sisa hutang"
                                : "Pembayaran " + rupiah(paid)
                                        + (after > 0 ? " — sisa " + rupiah(after) : " — LUNAS"),
                                Toast.LENGTH_LONG).show();
                    }
                    com.crowja.damiupos.sync.SyncScheduler.syncNow(getApplicationContext());
                    loadData();
                })
                .show();
    }

    private void loadData() {
        // Agregat GABUNGAN lintas-perangkat (jumlah transaksi/galon/konsumsi dijumlah dari semua
        // salinan orang ini) → angka SAMA seperti dashboard web & antar perangkat.
        Customer customer = customerDao.getByIdMerged(customerId);
        if (customer == null) {
            finish();
            return;
        }
        currentCustomer = customer;
        invalidateOptionsMenu();   // menu "Sudah Order Ulang" bergantung status pelanggan

        // Nama gabungan lintas-perangkat ("NAMA1 / NAMA2") bila orang yang sama bernama beda;
        // getByIdMerged mengisinya lewat applyMergedAggregates. Fallback ke nama asli.
        tvNama.setText(customer.getDisplayName());
        // Multi-nomor: tampilkan SEMUA nomor (utama dulu). Satu orang bisa punya beberapa nomor.
        java.util.List<String> detailPhones = customer.getPhonesOrDefault();
        tvTelepon.setText(detailPhones.isEmpty() ? "-" : android.text.TextUtils.join(", ", detailPhones));
        tvAlamat.setText(customer.getAddress() != null && !customer.getAddress().isEmpty()
                ? customer.getAddress() : "-");

        // Catatan Order: instruksi pengiriman tetap — ketuk teksnya untuk edit (placeholder = trigger).
        boolean hasOrderNote = customer.getOrderNote() != null && !customer.getOrderNote().trim().isEmpty();
        tvCatatanOrder.setText(hasOrderNote
                ? "📝 " + customer.getOrderNote().trim()
                : "📝 Ketuk untuk menambahkan Catatan Order");

        // Tag perangkat asal pelanggan (gabungan lintas salinan).
        java.util.List<String> origins = customer.getOriginLabels();
        if (origins != null && !origins.isEmpty()) {
            tvOrigin.setText("📱 Perangkat: " + android.text.TextUtils.join(" + ", origins));
            tvOrigin.setVisibility(View.VISIBLE);
        } else {
            tvOrigin.setVisibility(View.GONE);
        }

        // Reseller yang terafiliasi (linked_reseller_uuid, sudah diangkat ke wakil lintas salinan).
        // Resolusi nama by sync_uuid; sembunyikan bila tak tertaut / reseller belum ada di HP ini.
        String linkedUuid = customer.getLinkedResellerUuid();
        String linkedName = (linkedUuid != null && !linkedUuid.isEmpty())
                ? customerDao.nameBySyncUuid(linkedUuid) : null;
        if (linkedName != null && !linkedName.isEmpty()) {
            tvAfiliasi.setText("🤝 Terafiliasi ke reseller: " + linkedName);
            tvAfiliasi.setVisibility(View.VISIBLE);
        } else if (linkedUuid != null && !linkedUuid.isEmpty()) {
            tvAfiliasi.setText("🤝 Terafiliasi ke reseller");
            tvAfiliasi.setVisibility(View.VISIBLE);
        } else {
            tvAfiliasi.setVisibility(View.GONE);
        }

        // ⚠️ Banner detail bermasalah — kategori aktif + catatan; tap membuka dialog laporan.
        if (customer.hasOpenIssue()) {
            StringBuilder sb = new StringBuilder("⚠️ Detail bermasalah — perlu diperbaiki");
            java.util.List<String> keys = customer.issueFlagList();
            for (String k : keys) {
                sb.append("\n• ").append(issueLabelFor(k));
            }
            if (customer.getIssueNote() != null && !customer.getIssueNote().trim().isEmpty()) {
                sb.append("\nCatatan: ").append(customer.getIssueNote().trim());
            }
            sb.append("\n(ketuk untuk ubah / tandai sudah diperbaiki)");
            tvIssueBanner.setText(sb.toString());
            tvIssueBanner.setVisibility(View.VISIBLE);
        } else {
            tvIssueBanner.setVisibility(View.GONE);
        }

        loadPhoto(customer.getPhotoPath(), customer.getPhotoUrl());
        loadMap(customer);

        // Satu angka jelas: "Galon Dipinjam" = galon yang masih dipinjam pelanggan (belum kembali).
        tvSaldoGalon.setText(String.valueOf(customer.getSaldoGalon()));

        renderHutang();

        // Jumlah transaksi GABUNGAN (lintas perangkat) di header histori.
        int mergedTrx = customer.getTotalTransaksi();
        tvHistoryHeader.setText(getString(R.string.histori_transaksi)
                + (mergedTrx > 0 ? " (" + mergedTrx + ")" : ""));

        // Riwayat yang bisa ditampilkan = transaksi LOKAL perangkat ini (transaksi isolasi
        // per-perangkat, tidak branch-wide). Kalau total gabungan > lokal, beri catatan jujur.
        List<Transaction> transactions = transactionDao.getByCustomerId(customerId);
        adapter.setData(transactions);

        int otherDevices = mergedTrx - transactions.size();
        if (otherDevices > 0 && !transactions.isEmpty()) {
            tvHistoryNote.setText("+" + otherDevices + " transaksi lain tercatat di perangkat lain");
            tvHistoryNote.setVisibility(View.VISIBLE);
        } else {
            tvHistoryNote.setVisibility(View.GONE);
        }

        if (transactions.isEmpty()) {
            // Ada transaksi (di perangkat lain) tapi tak ada yang lokal → jelaskan, jangan "kosong".
            tvEmptyHistory.setText(mergedTrx > 0
                    ? mergedTrx + " transaksi tercatat di perangkat lain"
                    : getString(R.string.belum_ada_transaksi));
            tvEmptyHistory.setVisibility(View.VISIBLE);
            rvTransactions.setVisibility(View.GONE);
        } else {
            tvEmptyHistory.setVisibility(View.GONE);
            rvTransactions.setVisibility(View.VISIBLE);
        }
    }

    /** Foto rumah: file lokal kalau ada; kalau tidak (baris hasil sync dari perangkat lain) unduh
     *  dari {@code photoUrl} ke cache lalu tampilkan — meniru pola ExpenseDetailActivity. */
    private void loadPhoto(String path, String photoUrl) {
        if (path != null && !path.isEmpty()) {
            File f = new File(path);
            if (f.exists()) {
                try {
                    ivFoto.setImageBitmap(com.crowja.damiupos.util.BitmapUtils
                            .decodeSampled(path, 1024, 1024));
                    final String finalPath = path;
                    ivFoto.setOnClickListener(v -> showFullScreenPhoto(finalPath));
                    return;
                } catch (Exception ignored) {}
            }
        }
        if (photoUrl != null && !photoUrl.isEmpty()) {
            loadPhotoFromUrl(photoUrl);
            return;
        }
        ivFoto.setImageResource(android.R.drawable.ic_menu_gallery);
        ivFoto.setOnClickListener(null);
    }

    /** Unduh foto rumah dari server (baris sinkron tanpa file lokal) di background lalu tampilkan. */
    private void loadPhotoFromUrl(String url) {
        ivFoto.setImageResource(android.R.drawable.ic_menu_gallery);
        ivFoto.setOnClickListener(null);
        final String name = "cust_" + customerId + "_" + Integer.toHexString(url.hashCode()) + ".jpg";
        new Thread(() -> {
            final File f = com.crowja.damiupos.util.BitmapUtils.downloadToCache(
                    getApplicationContext(), url, name);
            final android.graphics.Bitmap b = f != null
                    ? com.crowja.damiupos.util.BitmapUtils.decodeSampled(f.getAbsolutePath(), 1024, 1024)
                    : null;
            // File ter-cache tapi gagal decode (rusak/format tak didukung) → BUANG supaya pembukaan
            // berikutnya mengunduh ulang, bukan macet di placeholder karena cache-hit file rusak.
            if (f != null && b == null) {
                f.delete();
                android.util.Log.w("DAMIU", "customer photo decode failed, cache cleared: " + url);
            }
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || b == null) return;
                ivFoto.setImageBitmap(b);
                ivFoto.setOnClickListener(v -> showFullScreenPhoto(f.getAbsolutePath()));
            });
        }).start();
    }

    private void showFullScreenPhoto(String path) {
        android.graphics.Bitmap bmp = com.crowja.damiupos.util.BitmapUtils
                .decodeForScreen(this, path);
        if (bmp == null) {
            Toast.makeText(this, "Foto tidak dapat dimuat", Toast.LENGTH_SHORT).show();
            return;
        }
        android.widget.ImageView iv = new android.widget.ImageView(this);
        iv.setImageBitmap(bmp);
        iv.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        iv.setBackgroundColor(android.graphics.Color.BLACK);
        iv.setAdjustViewBounds(true);

        android.app.Dialog dialog = new android.app.Dialog(this,
                android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        dialog.setContentView(iv, new android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT));
        iv.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }

    /**
     * "Lihat koleksi foto" per lokasi pelanggan: grid thumbnail (unduh+cache dari URL, sama seperti
     * foto rumah) → ketuk salah satu untuk layar penuh. Koleksi hanya diunggah lewat web (maks
     * {@value #MAX_LOCATION_PHOTOS_HINT} foto) — HP di sini murni PENAMPIL, bukan pengunggah.
     */
    private static final int MAX_LOCATION_PHOTOS_HINT = 5;

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void showLocationPhotoCollection(String locLabel, java.util.List<String> photoUrls) {
        int pad = dp(12);
        android.widget.GridLayout grid = new android.widget.GridLayout(this);
        grid.setColumnCount(3);
        grid.setPadding(pad, pad, pad, pad);

        int thumbSize = dp(96);
        int thumbMargin = dp(4);
        for (String url : photoUrls) {
            android.widget.ImageView iv = new android.widget.ImageView(this);
            android.widget.GridLayout.LayoutParams lp = new android.widget.GridLayout.LayoutParams();
            lp.width = thumbSize;
            lp.height = thumbSize;
            lp.setMargins(thumbMargin, thumbMargin, thumbMargin, thumbMargin);
            iv.setLayoutParams(lp);
            iv.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
            iv.setImageResource(android.R.drawable.ic_menu_gallery);
            iv.setBackgroundColor(0xFFECECEC);
            grid.addView(iv);
            loadLocationThumb(iv, url);
        }

        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(grid);

        new AlertDialog.Builder(this)
                .setTitle("📍 " + locLabel + " — Koleksi Foto")
                .setView(scroll)
                .setPositiveButton("Tutup", null)
                .show();
    }

    /** Unduh (cache lokal) + tampilkan satu thumbnail koleksi; ketuk → layar penuh. */
    private void loadLocationThumb(android.widget.ImageView iv, String url) {
        final String name = "custloc_" + customerId + "_" + Integer.toHexString(url.hashCode()) + ".jpg";
        new Thread(() -> {
            final File f = com.crowja.damiupos.util.BitmapUtils.downloadToCache(
                    getApplicationContext(), url, name);
            final android.graphics.Bitmap b = f != null
                    ? com.crowja.damiupos.util.BitmapUtils.decodeSampled(f.getAbsolutePath(), 300, 300)
                    : null;
            if (f != null && b == null) f.delete();
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || b == null) return;
                iv.setImageBitmap(b);
                iv.setOnClickListener(v -> showFullScreenPhoto(f.getAbsolutePath()));
            });
        }).start();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void loadMap(Customer customer) {
        // Multi-koordinat: susun daftar lokasi bernama (fallback ke koordinat legacy bila belum ada),
        // buang (0,0) = belum diisi. Tanpa koordinat sama sekali → sembunyikan kartu.
        java.util.List<Customer.Location> locs = new java.util.ArrayList<>();
        if (customer.getLocations() != null) {
            for (Customer.Location l : customer.getLocations()) {
                if (l != null && (l.lat != 0 || l.lng != 0)) locs.add(l);
            }
        }
        if (locs.isEmpty() && (customer.getLatitude() != 0 || customer.getLongitude() != 0)) {
            locs.add(new Customer.Location(Customer.DEFAULT_LOCATION_NAME,
                    customer.getLatitude(), customer.getLongitude(), customer.isWajibOngkir()));
        }
        if (locs.isEmpty()) {
            cardMap.setVisibility(View.GONE);
            return;
        }
        cardMap.setVisibility(View.VISIBLE);

        // Judul "Lokasi Pelanggan (N)" bila lebih dari satu.
        TextView title = findViewById(R.id.tvLocationTitle);
        if (title != null) {
            title.setText(locs.size() > 1 ? "Lokasi Pelanggan (" + locs.size() + ")" : "Lokasi Pelanggan");
        }

        // Tombol Navigasi di header → lokasi UTAMA (entri pertama).
        Customer.Location primary = locs.get(0);
        findViewById(R.id.btnNavigate).setOnClickListener(v ->
                navigateTo(customer.getName(), primary.name, primary.lat, primary.lng));

        // Daftar per-lokasi: nama + badge ★Utama / 🚚 Wajib Ongkir + tombol Navigasi ke titik itu.
        android.widget.LinearLayout list = findViewById(R.id.locationsList);
        list.removeAllViews();
        // Sembunyikan daftar bila cuma satu lokasi tanpa Wajib Ongkir (peta + header sudah cukup).
        boolean anyWajib = false;
        for (Customer.Location l : locs) if (l.wajibOngkir) anyWajib = true;
        if (locs.size() > 1 || anyWajib) {
            android.view.LayoutInflater inf = getLayoutInflater();
            for (int i = 0; i < locs.size(); i++) {
                Customer.Location l = locs.get(i);
                View row = inf.inflate(R.layout.item_customer_location_detail, list, false);
                TextView nm = row.findViewById(R.id.tvLocName);
                TextView utama = row.findViewById(R.id.tvLocUtama);
                TextView wajib = row.findViewById(R.id.tvLocWajib);
                nm.setText(l.name != null && !l.name.isEmpty() ? l.name : Customer.DEFAULT_LOCATION_NAME);
                utama.setVisibility(i == 0 && locs.size() > 1 ? View.VISIBLE : View.GONE);
                wajib.setVisibility(l.wajibOngkir ? View.VISIBLE : View.GONE);
                row.findViewById(R.id.btnLocNav).setOnClickListener(v ->
                        navigateTo(customer.getName(), l.name, l.lat, l.lng));
                // Koleksi foto lokasi (maks 5, diunggah lewat web) — hanya muncul bila ada isinya.
                com.google.android.material.button.MaterialButton btnPhotos = row.findViewById(R.id.btnLocPhotos);
                java.util.List<String> photos = l.photos != null ? l.photos : java.util.Collections.emptyList();
                if (!photos.isEmpty()) {
                    btnPhotos.setVisibility(View.VISIBLE);
                    btnPhotos.setText(photos.size() > 1 ? "Foto (" + photos.size() + ")" : "Foto");
                    String locLabel = l.name != null && !l.name.isEmpty() ? l.name : Customer.DEFAULT_LOCATION_NAME;
                    btnPhotos.setOnClickListener(v -> showLocationPhotoCollection(locLabel, photos));
                }
                list.addView(row);
            }
        }

        if (mapLoaded) return;
        mapLoaded = true;

        WebSettings ws = webMap.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        // UA pengenal aplikasi supaya penyedia tile tidak memblokir request.
        ws.setUserAgentString(MapTiles.userAgent());
        webMap.setOnClickListener(v -> findViewById(R.id.btnNavigate).performClick());

        // Bangun marker semua lokasi + popup nama (+ Wajib Ongkir). fitBounds bila >1, else setView.
        StringBuilder markers = new StringBuilder();
        StringBuilder pts = new StringBuilder();
        for (Customer.Location l : locs) {
            String label = (l.name != null && !l.name.isEmpty() ? l.name : Customer.DEFAULT_LOCATION_NAME)
                    + (l.wajibOngkir ? " 🚚 Wajib Ongkir" : "");
            markers.append("L.marker([").append(l.lat).append(',').append(l.lng)
                    .append("]).addTo(map).bindPopup('").append(jsEscape(label)).append("');");
            if (pts.length() > 0) pts.append(',');
            pts.append('[').append(l.lat).append(',').append(l.lng).append(']');
        }
        String view = locs.size() > 1
                ? "map.fitBounds([" + pts + "], {padding:[30,30], maxZoom:17});"
                : "map.setView([" + primary.lat + "," + primary.lng + "], 16);";

        String html = "<!DOCTYPE html><html><head>"
                + "<meta charset='utf-8'>"
                + "<meta name='viewport' content='width=device-width, initial-scale=1.0, user-scalable=no'>"
                + "<link rel='stylesheet' href='https://unpkg.com/leaflet@1.9.4/dist/leaflet.css' />"
                + "<script src='https://unpkg.com/leaflet@1.9.4/dist/leaflet.js'></script>"
                + "<style>html,body,#map{height:100%;margin:0;padding:0;}"
                + ".leaflet-control-attribution{font-size:9px;}</style>"
                + "</head><body><div id='map'></div>"
                + "<script>"
                + "var map = L.map('map', {zoomControl:false, dragging:false,"
                + " scrollWheelZoom:false, doubleClickZoom:false, touchZoom:false, boxZoom:false, keyboard:false});"
                + "L.tileLayer('" + MapTiles.LEAFLET_URL + "', {subdomains:'" + MapTiles.SUBDOMAINS
                + "', maxZoom:19, attribution:'" + MapTiles.ATTRIBUTION + "'}).addTo(map);"
                + markers
                + view
                + "</script></body></html>";
        webMap.setWebViewClient(new android.webkit.WebViewClient() {
            @Override
            public void onPageFinished(android.webkit.WebView view, String url) {
                if (liveDev == null) {
                    liveDev = new LiveDeviceOverlay(CustomerDetailActivity.this, webMap);
                }
                liveDev.start();
            }
        });
        webMap.loadDataWithBaseURL("https://unpkg.com", html, "text/html", "utf-8", null);
    }

    /** Buka aplikasi peta untuk navigasi ke satu koordinat (label = nama pelanggan + nama lokasi). */
    private void navigateTo(String custName, String locName, double lat, double lng) {
        try {
            String label = custName + (locName != null && !locName.isEmpty() ? " - " + locName : "");
            Uri geo = Uri.parse("geo:" + lat + "," + lng + "?q=" + lat + "," + lng
                    + "(" + Uri.encode(label) + ")");
            startActivity(new Intent(Intent.ACTION_VIEW, geo));
        } catch (Exception e) {
            Toast.makeText(this, "Aplikasi peta tidak tersedia", Toast.LENGTH_SHORT).show();
        }
    }

    /** Escape aman untuk string literal JavaScript (petik + backslash). */
    private static String jsEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("'", "\\'");
    }

    private void openWhatsApp() {
        Customer customer = customerDao.getById(customerId);
        if (customer == null) return;

        String phone = customer.getPhone();
        if (phone == null || phone.trim().isEmpty()) {
            Toast.makeText(this, R.string.no_telepon_kosong, Toast.LENGTH_SHORT).show();
            return;
        }

        // Format phone number: remove leading 0, add 62
        phone = phone.trim().replaceAll("[^0-9]", "");
        if (phone.startsWith("0")) {
            phone = "62" + phone.substring(1);
        } else if (!phone.startsWith("62")) {
            phone = "62" + phone;
        }

        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setData(Uri.parse("https://wa.me/" + phone));
            startActivity(intent);
            // Tercatat sebagai follow-up (sama seperti tombol "Follow Up WA" di daftar/peta Follow
            // Up) — supaya "kapan terakhir di-follow-up" di web ikut ter-update walau staf chat
            // langsung dari sini, bukan dari menu Follow Up. Lihat WhatsAppFollowUp.
            customerDao.markFollowedUp(customerId);
        } catch (Exception e) {
            Toast.makeText(this, R.string.wa_tidak_tersedia, Toast.LENGTH_SHORT).show();
        }
    }

    private void confirmDelete() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.konfirmasi_hapus)
                .setMessage(R.string.yakin_hapus_pelanggan)
                .setPositiveButton(R.string.hapus, (dialog, which) -> {
                    customerDao.delete(customerId);
                    Toast.makeText(this, "Pelanggan dihapus", Toast.LENGTH_SHORT).show();
                    finish();
                })
                .setNegativeButton(R.string.batal, null)
                .show();
    }

    @Override
    protected void onDestroy() {
        if (liveDev != null) liveDev.stop();
        super.onDestroy();
    }

}
