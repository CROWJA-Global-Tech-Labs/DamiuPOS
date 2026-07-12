package com.crowja.damiupos;

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

    private long customerId;
    private DatabaseHelper dbHelper;
    private CustomerDao customerDao;
    private TransactionDao transactionDao;
    private com.crowja.damiupos.db.SettingsDao settingsDao;
    private Customer currentCustomer;

    private TextView tvNama, tvTelepon, tvAlamat, tvOrigin, tvAfiliasi;
    private TextView tvGalonKeluar, tvGalonKembali, tvSaldoGalon;
    private TextView tvEmptyHistory, tvHistoryHeader, tvHistoryNote;
    private RecyclerView rvTransactions;
    private TransactionAdapter adapter;
    private ShapeableImageView ivFoto;
    private MaterialCardView cardMap;
    private WebView webMap;
    private boolean mapLoaded;

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
        tvOrigin = findViewById(R.id.tvOrigin);
        tvAfiliasi = findViewById(R.id.tvAfiliasi);
        tvHistoryHeader = findViewById(R.id.tvHistoryHeader);
        tvHistoryNote = findViewById(R.id.tvHistoryNote);
        ivFoto = findViewById(R.id.ivFoto);
        cardMap = findViewById(R.id.cardMap);
        webMap = findViewById(R.id.webMap);
        tvGalonKeluar = findViewById(R.id.tvGalonKeluar);
        tvGalonKembali = findViewById(R.id.tvGalonKembali);
        tvSaldoGalon = findViewById(R.id.tvSaldoGalon);
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

        // Delete button
        findViewById(R.id.btnHapus).setOnClickListener(v -> confirmDelete());

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
        tvTelepon.setText(customer.getPhone() != null && !customer.getPhone().isEmpty()
                ? customer.getPhone() : "-");
        tvAlamat.setText(customer.getAddress() != null && !customer.getAddress().isEmpty()
                ? customer.getAddress() : "-");

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

        loadPhoto(customer.getPhotoPath(), customer.getPhotoUrl());
        loadMap(customer);

        // "Total Galon" = semua galon JUAL yang diambil pelanggan (akuisisi/beli/pinjam) — supaya
        // jumlah galon selalu terlihat walau galon dibeli (bukan dipinjam). "Sisa Pinjam" = saldo.
        tvGalonKeluar.setText(String.valueOf(customer.getGalonTotalOrdered()));
        tvGalonKembali.setText(String.valueOf(customer.getGalonKembali()));
        tvSaldoGalon.setText(String.valueOf(customer.getSaldoGalon()));

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

    @SuppressLint("SetJavaScriptEnabled")
    private void loadMap(Customer customer) {
        double lat = customer.getLatitude();
        double lng = customer.getLongitude();
        if (lat == 0 && lng == 0) {
            cardMap.setVisibility(View.GONE);
            return;
        }
        cardMap.setVisibility(View.VISIBLE);

        findViewById(R.id.btnNavigate).setOnClickListener(v -> {
            try {
                Uri geo = Uri.parse("geo:" + lat + "," + lng + "?q=" + lat + "," + lng
                        + "(" + Uri.encode(customer.getName()) + ")");
                Intent intent = new Intent(Intent.ACTION_VIEW, geo);
                startActivity(intent);
            } catch (Exception e) {
                Toast.makeText(this, "Aplikasi peta tidak tersedia", Toast.LENGTH_SHORT).show();
            }
        });

        if (mapLoaded) return;
        mapLoaded = true;

        WebSettings ws = webMap.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        // UA pengenal aplikasi supaya penyedia tile tidak memblokir request.
        ws.setUserAgentString(MapTiles.userAgent());
        webMap.setOnClickListener(v -> findViewById(R.id.btnNavigate).performClick());

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
                + " scrollWheelZoom:false, doubleClickZoom:false, touchZoom:false, boxZoom:false, keyboard:false})"
                + ".setView([" + lat + "," + lng + "], 16);"
                + "L.tileLayer('" + MapTiles.LEAFLET_URL + "', {subdomains:'" + MapTiles.SUBDOMAINS
                + "', maxZoom:19, attribution:'" + MapTiles.ATTRIBUTION + "'}).addTo(map);"
                + "L.marker([" + lat + "," + lng + "]).addTo(map);"
                + "</script></body></html>";
        webMap.loadDataWithBaseURL("https://unpkg.com", html, "text/html", "utf-8", null);
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
}
