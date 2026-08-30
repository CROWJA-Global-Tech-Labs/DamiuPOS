package com.crowja.damiupos;

import android.Manifest;
import android.app.DatePickerDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.exifinterface.media.ExifInterface;

import com.crowja.damiupos.db.CustomerDao;
import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.model.Customer;
import com.google.android.material.textfield.TextInputEditText;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

public class CustomerFormActivity extends AppCompatActivity {

    /** Caller bisa baca id pelanggan baru lewat data.getLongExtra(EXTRA_NEW_CUSTOMER_ID, -1) */
    public static final String EXTRA_NEW_CUSTOMER_ID = "new_customer_id";

    /** Dibuka dari "Input Promosi Galon": setelah simpan pelanggan BARU, lanjut ke Transaksi
     *  Baru mode Promosi (toggle Gratis/Berbayar) untuk pelanggan tsb. */
    public static final String EXTRA_PROMOSI_FLOW = "promosi_flow";

    private static final int REQUEST_CAMERA = 100;
    /** Workflow gabungan "Ambil Foto & Koordinat" (kamera live + GPS gate). */
    private static final int REQUEST_PHOTO_COORD = 106;
    private static final int REQUEST_PERMISSION_CAMERA = 101;
    private static final int REQUEST_PERMISSION_LOCATION = 102;
    private static final int REQUEST_PICK_MAP = 103;
    private static final int REQUEST_PERMISSION_CONTACTS = 104;
    private static final int REQUEST_PICK_GALLERY = 105;
    /** Kamera untuk foto KOLEKSI per-lokasi (beda dari REQUEST_CAMERA = foto rumah tunggal). */
    private static final int REQUEST_CAMERA_LOCATION = 107;
    private static final int REQUEST_PERMISSION_CAMERA_LOCATION = 108;

    /** Prefill mode buat-baru (caller: bubble "+ Trx" saat pelanggan chat WA belum terdaftar). */
    public static final String EXTRA_PREFILL_NAME = "prefill_name";
    public static final String EXTRA_PREFILL_PHONE = "prefill_phone";

    /** Id pelanggan yang baru di-insert, dipakai untuk setResult */
    private long lastInsertedId = -1;

    private String pendingContactName;
    private String pendingContactPhone;
    /** Nomol WA yang di-prefill bubble "+ Trx" apa adanya (bisa non-08). Bila field telp masih
     *  persis nilai ini saat simpan, guard format 08 dikecualikan (nomor WA asli tak boleh hilang). */
    private String waPrefillPhone;

    private TextInputEditText etNama, etTelepon, etAlamat, etCatatanOrder;
    /** Nomor HP TAMBAHAN (opsional) — satu orang bisa punya beberapa nomor. etTelepon = nomor utama. */
    private android.widget.LinearLayout llPhonesExtra;
    private final java.util.List<android.widget.EditText> extraPhoneRows = new java.util.ArrayList<>();
    private android.widget.LinearLayout llHargaProduk;
    private final java.util.List<PriceRow> priceRows = new ArrayList<>();
    private ImageView ivFotoRumah;

    /** Satu baris harga per produk pada form (kunci = product uuid). */
    private static final class PriceRow {
        final String uuid;
        final TextInputEditText et;
        final double def;   // harga standar produk — pembanding "override vs ikut standar" saat simpan
        PriceRow(String uuid, TextInputEditText et, double def) { this.uuid = uuid; this.et = et; this.def = def; }
    }
    private CustomerDao customerDao;
    private long editId = -1;
    /** Marketing & operator (staf/SPV) → wajib foto + koordinat saat input pelanggan baru. */
    private boolean requirePhotoCoord = false;

    private String currentPhotoPath;
    /** Photo path loaded when editing — used to detect a photo change (→ re-upload). */
    private String originalPhotoPath;
    /** Path foto VALID terakhir — restore saat kamera dibatalkan / galeri gagal, supaya
     *  currentPhotoPath tidak menggantung di file temp 0-byte (yang bisa menghapus foto lama). */
    private String lastGoodPhotoPath;

    /** Satu baris lokasi pada form (multi-lokasi; baris PERTAMA = lokasi utama). */
    private final class LocationRow {
        final View view;
        final TextInputEditText etName;
        final TextView tvCoord;
        final com.google.android.material.button.MaterialButton btnGps, btnPeta, btnMaps, btnGmapsLink;
        final com.google.android.material.checkbox.MaterialCheckBox cbWajib;
        final android.widget.ProgressBar pb;
        final android.widget.LinearLayout llFoto;
        double lat, lng;
        /** Id stabil lokasi — kunci nama berkas koleksi foto (lihat Customer.Location#id). */
        String id;
        /** Koleksi foto lokasi (URL server, maks 5) — bisa diedit langsung di form ini. */
        final java.util.List<String> photos = new java.util.ArrayList<>();

        LocationRow(View v) {
            view = v;
            etName = v.findViewById(R.id.etLokasiNama);
            tvCoord = v.findViewById(R.id.tvLokasiKoordinat);
            btnGps = v.findViewById(R.id.btnLokasiSaatIni);
            btnPeta = v.findViewById(R.id.btnPilihPetaRow);
            btnMaps = v.findViewById(R.id.btnBukaMapsRow);
            btnGmapsLink = v.findViewById(R.id.btnPakaiGmapsRow);
            cbWajib = v.findViewById(R.id.cbWajibOngkirRow);
            pb = v.findViewById(R.id.pbLokasiAkurasiRow);
            llFoto = v.findViewById(R.id.llLokasiFoto);
        }
    }

    private android.widget.LinearLayout llLokasi;
    private final java.util.List<LocationRow> locationRows = new ArrayList<>();
    /** Baris yang meluncurkan picker peta — hasil onActivityResult diarahkan ke sini. */
    private LocationRow pendingMapRow;
    /** Baris yang meluncurkan pencarian GPS "Lokasi Saat Ini" (juga baris tertunda
     *  saat menunggu izin lokasi) — update pencarian & commit diarahkan ke sini. */
    private LocationRow searchRow;
    /** Baris yang meluncurkan kamera foto koleksi lokasi (juga baris tertunda saat menunggu
     *  izin kamera) — hasil onActivityResult diunggah ke baris ini. */
    private LocationRow pendingPhotoRow;
    /** Path file sementara foto koleksi lokasi yang sedang diambil (beda dari currentPhotoPath
     *  = foto rumah tunggal). */
    private String pendingLocationPhotoPath;

    // --- Pencarian koordinat GPS akurat (Lokasi Saat Ini) ---
    /** Akurasi maksimal (meter) yang diterima sebelum koordinat dianggap valid. */
    private static final float REQUIRED_ACCURACY_M = 5f;
    /** Batas tunggu sebelum menawarkan opsi pakai-koordinat-terbaik / coba lagi / batal. */
    private static final long LOCATION_TIMEOUT_MS = 60_000L;
    private LocationManager locationManager;
    private LocationListener activeLocationListener;
    private android.os.Handler locationTimeoutHandler;
    private Runnable locationTimeoutRunnable;
    private boolean searchingLocation = false;
    /** Fix terbaik sejauh ini SELAMA pencarian — belum di-commit ke baris lokasi
     *  supaya "Batal" tidak menimpa koordinat pelanggan yang sudah tersimpan (edit). */
    private double searchLat, searchLng;
    private float searchAccuracy = Float.MAX_VALUE;

    private com.google.android.material.checkbox.MaterialCheckBox cbReseller;
    private com.google.android.material.checkbox.MaterialCheckBox cbKomisiAddToPrice;
    /** resellerSince existing dari DB (kalau edit) — dipertahankan kalau tetap reseller. */
    private String existingResellerSince;
    /** Tautan reseller: sync_uuid reseller rujukan (Transaksi Baru auto-pilih afiliasi). Null = tak ditautkan. */
    private com.google.android.material.button.MaterialButton btnLinkedReseller;
    private String linkedResellerUuid;
    /** Muncul saat pelanggan dijadikan/ditautkan reseller — checked (default) melewati kewajiban
     *  foto+koordinat pelanggan baru (requirePhotoCoord) untuk pelanggan reseller/terafiliasi. */
    private com.google.android.material.checkbox.MaterialCheckBox cbSkipPhotoCoordReseller;

    private String selectedCreatedAt; // tanggal daftar terpilih (yyyy-MM-dd HH:mm:ss)
    private final SimpleDateFormat dbDateFmt =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
    private final SimpleDateFormat displayDateFmt =
            new SimpleDateFormat("d MMM yyyy", new Locale("id", "ID"));
    private com.google.android.material.button.MaterialButton btnTanggalDaftar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_customer_form);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        customerDao = new CustomerDao(DatabaseHelper.getInstance(this));

        // Marketing & operator (staf/SPV) wajib melengkapi foto rumah + koordinat saat input
        // pelanggan BARU. Admin & mode single-user (owner) dikecualikan.
        com.crowja.damiupos.db.SettingsDao sess = new com.crowja.damiupos.db.SettingsDao(DatabaseHelper.getInstance(this));
        long uid = sess.getCurrentUserId();
        com.crowja.damiupos.model.User cur = uid > 0
                ? new com.crowja.damiupos.db.UserDao(DatabaseHelper.getInstance(this)).getById(uid) : null;
        requirePhotoCoord = cur != null && (cur.isMarketing() || cur.isStaf() || cur.isSpv());

        etNama = findViewById(R.id.etNama);
        etTelepon = findViewById(R.id.etTelepon);
        etAlamat = findViewById(R.id.etAlamat);
        etCatatanOrder = findViewById(R.id.etCatatanOrder);
        llHargaProduk = findViewById(R.id.llHargaProduk);
        ivFotoRumah = findViewById(R.id.ivFotoRumah);
        llLokasi = findViewById(R.id.llLokasi);
        findViewById(R.id.btnTambahLokasi).setOnClickListener(v -> addLocationRow(null));
        llPhonesExtra = findViewById(R.id.llPhonesExtra);
        findViewById(R.id.btnAddPhone).setOnClickListener(v -> addPhoneRow(""));

        // Reseller + sub-opsi "Tambahkan Komisi ke Harga Air Minum"
        // (hanya tampil saat "Jadikan Reseller" dicentang).
        cbReseller = findViewById(R.id.cbReseller);
        cbKomisiAddToPrice = findViewById(R.id.cbKomisiAddToPrice);
        cbKomisiAddToPrice.setVisibility(cbReseller.isChecked() ? View.VISIBLE : View.GONE);
        cbReseller.setOnCheckedChangeListener((b, checked) -> {
            cbKomisiAddToPrice.setVisibility(checked ? View.VISIBLE : View.GONE);
            // Default ON: saat pelanggan dijadikan reseller, komisi-ke-harga ikut aktif.
            cbKomisiAddToPrice.setChecked(checked);
            updateSkipPhotoCoordVisibility();
        });

        // Tautan Reseller: reseller rujukan pelanggan → Transaksi Baru auto-pilih afiliasi ke reseller ini.
        btnLinkedReseller = findViewById(R.id.btnLinkedReseller);
        btnLinkedReseller.setOnClickListener(v -> showLinkedResellerPicker());
        cbSkipPhotoCoordReseller = findViewById(R.id.cbSkipPhotoCoordReseller);

        // Check if editing existing customer
        editId = getIntent().getLongExtra("customer_id", -1);
        // Pelanggan BARU: batasi input telp ke angka saja, maks 13 digit (tuntunan format 08…, 9–13).
        // Mode edit dilewati — setText nomor lama berformat lain (mis. "+62…") jangan sampai terpotong.
        if (editId == -1) {
            // Prefill dari bubble "+ Trx": pelanggan chat WA belum terdaftar → nomor/nama terisi.
            String preName = getIntent().getStringExtra(EXTRA_PREFILL_NAME);
            String prePhone = getIntent().getStringExtra(EXTRA_PREFILL_PHONE);
            prePhone = prePhone != null ? prePhone.trim() : null;
            // Nomor WA bisa non-08 (landline / luar negeri, mis. "02150938888" / "6591234567").
            // Untuk prefill non-08 JANGAN pasang filter angka/13-digit — kalau tidak, nomornya
            // terpotong/terblokir; simpan apa adanya seperti mode edit (guard save juga dikecualikan).
            boolean prefillNon08 = prePhone != null && !prePhone.isEmpty()
                    && !(prePhone.matches("\\d{9,13}") && prePhone.startsWith("08"));
            if (!prefillNon08) {
                etTelepon.setKeyListener(
                        android.text.method.DigitsKeyListener.getInstance("0123456789"));
                etTelepon.setFilters(new android.text.InputFilter[]{
                        new android.text.InputFilter.LengthFilter(13)});
            }
            if (preName != null && !preName.trim().isEmpty()) etNama.setText(preName.trim());
            if (prePhone != null && !prePhone.isEmpty()) {
                etTelepon.setText(prePhone);
                waPrefillPhone = prePhone;
            }
        }
        if (editId != -1) {
            toolbar.setTitle(R.string.edit_pelanggan);
            Customer customer = customerDao.getById(editId);
            if (customer != null) {
                etNama.setText(customer.getName());
                // Nomor utama = phones[0]; nomor tambahan → baris repeater.
                java.util.List<String> ph = customer.getPhonesOrDefault();
                etTelepon.setText(ph.isEmpty() ? "" : ph.get(0));
                for (int i = 1; i < ph.size(); i++) addPhoneRow(ph.get(i));
                etAlamat.setText(customer.getAddress());
                etCatatanOrder.setText(customer.getOrderNote());
                if (customer.getCreatedAt() != null && !customer.getCreatedAt().isEmpty()) {
                    selectedCreatedAt = customer.getCreatedAt();
                }
                // Reseller state existing
                cbReseller.setChecked(customer.isReseller());
                cbKomisiAddToPrice.setChecked(customer.isKomisiAddToPrice());
                existingResellerSince = customer.getResellerSince();
                linkedResellerUuid = customer.getLinkedResellerUuid();
                updateLinkedResellerLabel();
                // Harga khusus per produk (override tersimpan; produk tanpa override → kosong = standar).
                buildProductPriceRows(customer);

                // Tampilkan foto rumah: utamakan FILE LOKAL; bila tak ada (pelanggan tersinkron dari
                // perangkat lain — foto hanya ada sebagai photo_url di server), UNDUH & tampilkan dari
                // URL supaya kotak foto tidak blank saat mengedit pelanggan lintas-perangkat. Ini murni
                // untuk TAMPILAN — currentPhotoPath TIDAK diubah, jadi save() tak menganggapnya foto
                // baru (tak meng-clear photo_url / tak re-upload).
                boolean shownLocal = false;
                if (customer.getPhotoPath() != null && !customer.getPhotoPath().isEmpty()) {
                    currentPhotoPath = customer.getPhotoPath();
                    originalPhotoPath = currentPhotoPath;
                    lastGoodPhotoPath = currentPhotoPath;
                    File photoFile = new File(currentPhotoPath);
                    if (photoFile.exists()) {
                        ivFotoRumah.setImageBitmap(loadRotatedBitmap(currentPhotoPath));
                        shownLocal = true;
                    }
                }
                if (!shownLocal && customer.getPhotoUrl() != null && !customer.getPhotoUrl().isEmpty()) {
                    loadFotoRumahFromUrl(customer.getPhotoUrl());
                }

                // Multi-lokasi: DAO sudah lazy-synthesize "Kediaman" dari koordinat legacy.
                buildLocationRows(customer);
            }
        } else {
            // Pelanggan baru → satu baris harga per produk, prefill harga standar (bisa diedit).
            buildProductPriceRows(null);
            buildLocationRows(null);
        }

        // Alur gabungan: satu tombol menangkap foto rumah + koordinat lokasi utama sekaligus
        // (kamera live, shutter aktif hanya saat akurasi GPS ≤ 10 m). "Galeri" tetap sebagai
        // cadangan foto-saja; koordinat granular per-lokasi tetap lewat tombol di tiap baris.
        findViewById(R.id.btnFoto).setOnClickListener(v -> takePhotoAndCoordinate());
        findViewById(R.id.btnPickGallery).setOnClickListener(v -> pickFromGallery());
        findViewById(R.id.btnSimpan).setOnClickListener(v -> save());

        // --- Tanggal Daftar (default hari ini) ---
        btnTanggalDaftar = findViewById(R.id.btnTanggalDaftar);
        if (selectedCreatedAt == null || selectedCreatedAt.isEmpty()) {
            selectedCreatedAt = dbDateFmt.format(new Date());
        }
        updateTanggalButton();
        btnTanggalDaftar.setOnClickListener(v -> showDatePicker());

        if (cbReseller == null) cbReseller = findViewById(R.id.cbReseller);

        // Pelanggan BARU: wajib input & validasi NOMOR HP dulu — form baru terbuka setelah lolos.
        // Kalau nomor sudah terdaftar, tawarkan langsung ke pelanggan itu (buat transaksi / batal)
        // alih-alih melanjutkan input duplikat.
        if (editId == -1) {
            showPhoneGateDialog(getIntent().getStringExtra(EXTRA_PREFILL_PHONE));
        }
    }

    /**
     * Gerbang wajib sebelum form pelanggan baru bisa diisi: minta nomor HP, validasi, lalu cek
     * duplikat (kanonik, peka +62/62/0 — {@link com.crowja.damiupos.db.CustomerDao#canonicalPhone}).
     * Non-cancelable — staf tidak bisa melewati form kosong tanpa nomor valid; tombol "Batal"
     * eksplisit menutup activity ini.
     */
    private void showPhoneGateDialog(String prefill) {
        View wrap = android.view.LayoutInflater.from(this).inflate(R.layout.dialog_phone_gate, null);
        com.google.android.material.textfield.TextInputLayout til = wrap.findViewById(R.id.tilPhoneGate);
        com.google.android.material.textfield.TextInputEditText et = wrap.findViewById(R.id.etPhoneGate);
        if (prefill != null && !prefill.trim().isEmpty()) et.setText(prefill.trim());

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Nomor HP Pelanggan")
                .setView(wrap)
                .setCancelable(false)
                .setPositiveButton("Lanjut", null)   // override di bawah — cegah auto-dismiss saat invalid
                .setNegativeButton("Batal", (d, w) -> finish())
                .create();
        dialog.setOnShowListener(dlg -> {
            android.widget.Button btn = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            btn.setOnClickListener(v -> {
                String raw = et.getText() != null ? et.getText().toString().trim() : "";
                if (raw.isEmpty()) {
                    til.setError("Nomor HP wajib diisi");
                    return;
                }
                if (!isValidPhoneNumber(raw)) {
                    til.setError("Nomor HP tidak valid");
                    return;
                }
                til.setError(null);
                Customer dup = customerDao.findByPhoneCanonical(raw);
                if (dup != null) {
                    dialog.dismiss();
                    showExistingCustomerDialog(dup, raw);
                    return;
                }
                // Lolos — isi nomor ke form & buka form pelanggan baru.
                etTelepon.setText(raw);
                dialog.dismiss();
            });
        });
        dialog.show();
    }

    /** Validasi ringan nomor HP Indonesia: digit saja (setelah dibersihkan), panjang wajar. */
    private static boolean isValidPhoneNumber(String raw) {
        String digits = raw.replaceAll("\\D+", "");
        if (digits.length() < 9) return false;
        String canon = com.crowja.damiupos.db.CustomerDao.canonicalPhone(raw);
        return canon.length() >= 10 && canon.length() <= 15;
    }

    /**
     * Nomor sudah terdaftar — tampilkan siapa pemiliknya. "Buat Transaksi" mengembalikan pelanggan
     * ini ke pemanggil (bila dibuka dari pemilih pelanggan Transaksi Baru → auto-terpilih di sana)
     * atau langsung membuka Transaksi Baru untuknya; "Ganti Nomor" kembali ke gerbang; "Batal"
     * menutup form ini tanpa menambah apa pun.
     */
    private void showExistingCustomerDialog(Customer existing, String triedPhone) {
        String msg = "Nomor " + triedPhone + " sudah terdaftar atas nama:\n\n• "
                + (existing.getName() != null ? existing.getName() : "(tanpa nama)")
                + "\n\nBuat transaksi untuk pelanggan ini, atau periksa kembali nomornya.";
        new AlertDialog.Builder(this)
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setTitle("⚠️ Pelanggan Sudah Ada")
                .setCancelable(false)
                .setMessage(msg)
                .setPositiveButton("Buat Transaksi", (d, w) -> {
                    android.content.ComponentName caller = getCallingActivity();
                    if (caller != null && TransactionActivity.class.getName().equals(caller.getClassName())) {
                        // Dibuka dari pemilih pelanggan Transaksi Baru → kembalikan sebagai hasil
                        // "pelanggan baru" supaya TransactionActivity auto-memilihnya (lihat
                        // TransactionActivity.onActivityResult REQUEST_NEW_CUSTOMER).
                        setResult(RESULT_OK, new Intent()
                                .putExtra(EXTRA_NEW_CUSTOMER_ID, existing.getId()));
                    } else {
                        startActivity(new Intent(this, TransactionActivity.class)
                                .putExtra("customer_id", existing.getId()));
                    }
                    finish();
                })
                .setNeutralButton("Ganti Nomor", (d, w) -> showPhoneGateDialog(null))
                .setNegativeButton("Batal", (d, w) -> finish())
                .show();
    }

    /** Bangun ulang baris lokasi dari model (DAO sudah lazy-synthesize "Kediaman" dari
     *  koordinat legacy). Pelanggan baru / tanpa lokasi → satu baris kosong "Kediaman". */
    private void buildLocationRows(Customer existing) {
        llLokasi.removeAllViews();
        locationRows.clear();
        java.util.List<Customer.Location> locs = existing != null ? existing.getLocations() : null;
        if (locs != null && !locs.isEmpty()) {
            for (Customer.Location l : locs) addLocationRow(l);
        } else {
            addLocationRow(null);
            // Pelanggan LAMA tanpa baris lokasi (koordinat belum diisi — lokasi tanpa koordinat
            // memang dibuang saat simpan) TETAP punya preferensi ongkir di kolom skalar. Ikuti nilai
            // tersimpan itu, jangan pakai default "baru" — kalau tidak, form MENAMPILKAN Wajib Ongkir
            // yang tak ada di database, sementara Transaksi Baru membaca nilai aslinya → terlihat
            // seperti "wajib ongkir tidak berfungsi", padahal formnya yang keliru.
            if (existing != null && !locationRows.isEmpty()) {
                locationRows.get(0).cbWajib.setChecked(existing.isWajibOngkir());
            }
        }
    }

    /** Tambahkan satu baris lokasi (null = baris kosong; baris pertama diberi nama default). */
    private void addLocationRow(Customer.Location loc) {
        View v = android.view.LayoutInflater.from(this)
                .inflate(R.layout.item_customer_location, llLokasi, false);
        final LocationRow row = new LocationRow(v);
        if (loc != null) {
            row.etName.setText(loc.name);
            row.lat = loc.lat;
            row.lng = loc.lng;
            row.id = loc.id;
            if (loc.photos != null) row.photos.addAll(loc.photos);
            // Lokasi TERSIMPAN: hormati nilai aslinya (jangan paksa ke default — pelanggan yang
            // sengaja bebas ongkir tak boleh diam-diam berubah saat formnya dibuka/diedit).
            row.cbWajib.setChecked(loc.wajibOngkir);
        } else {
            // Lokasi BARU (pelanggan baru / tambah lokasi): default Wajib Ongkir = AKTIF.
            row.cbWajib.setChecked(true);
            if (locationRows.isEmpty()) row.etName.setText(Customer.DEFAULT_LOCATION_NAME);
        }
        // Id stabil selalu ada (bahkan baris baru) — foto yang diunggah SEBELUM baris pernah
        // tersimpan tetap perlu nama berkas yang tak bentrok dengan baris lain.
        if (row.id == null || row.id.trim().isEmpty()) {
            row.id = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        }
        row.btnGps.setOnClickListener(x -> getLocation(row));
        row.btnPeta.setOnClickListener(x -> pickFromMap(row));
        row.btnMaps.setOnClickListener(x -> openInGoogleMaps(row));
        row.btnGmapsLink.setOnClickListener(x -> showMapsLinkDialog(row));
        v.findViewById(R.id.btnHapusLokasi).setOnClickListener(x -> removeLocationRow(row));
        llLokasi.addView(v);
        locationRows.add(row);
        updateKoordinatDisplay(row);
        renderLocationPhotos(row);
    }

    /** Batas koleksi foto per lokasi (cermin Customer::MAX_LOCATION_PHOTOS di web). */
    private static final int MAX_LOCATION_PHOTOS = 5;

    /** Render ulang strip thumbnail + tombol "+ Foto" satu baris lokasi dari row.photos. */
    private void renderLocationPhotos(LocationRow row) {
        row.llFoto.removeAllViews();
        int thumb = dpToPx(56);
        int margin = dpToPx(4);
        for (int i = 0; i < row.photos.size(); i++) {
            final int idx = i;
            final String url = row.photos.get(i);
            android.widget.FrameLayout wrap = new android.widget.FrameLayout(this);
            android.widget.LinearLayout.LayoutParams wlp =
                    new android.widget.LinearLayout.LayoutParams(thumb, thumb);
            wlp.setMargins(0, 0, margin, 0);
            wrap.setLayoutParams(wlp);

            ImageView iv = new ImageView(this);
            iv.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setImageResource(android.R.drawable.ic_menu_gallery);
            iv.setBackgroundColor(0xFFECECEC);
            wrap.addView(iv);
            loadLocationThumb(iv, url);

            TextView rm = new TextView(this);
            rm.setText("✕");
            rm.setTextColor(0xFFFFFFFF);
            rm.setTextSize(11f);
            rm.setGravity(android.view.Gravity.CENTER);
            rm.setBackgroundResource(R.drawable.bg_circle);
            rm.getBackground().setTint(0xFFC62828);
            int rmSize = dpToPx(18);
            android.widget.FrameLayout.LayoutParams rlp = new android.widget.FrameLayout.LayoutParams(rmSize, rmSize);
            rlp.gravity = android.view.Gravity.TOP | android.view.Gravity.END;
            rm.setLayoutParams(rlp);
            rm.setOnClickListener(x -> {
                row.photos.remove(idx);
                renderLocationPhotos(row);
            });
            wrap.addView(rm);

            row.llFoto.addView(wrap);
        }
        if (row.photos.size() < MAX_LOCATION_PHOTOS) {
            com.google.android.material.button.MaterialButton add =
                    new com.google.android.material.button.MaterialButton(this,
                            null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
            add.setText("+ Foto");
            add.setTextSize(11f);
            add.setAllCaps(false);
            android.widget.LinearLayout.LayoutParams alp = new android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, thumb);
            add.setLayoutParams(alp);
            add.setMinWidth(0);
            add.setMinimumWidth(0);
            add.setOnClickListener(x -> addLocationPhotoClicked(row));
            row.llFoto.addView(add);
        }
    }

    /** Unduh (cache lokal) + tampilkan satu thumbnail koleksi lokasi. */
    private void loadLocationThumb(ImageView iv, String url) {
        final String name = "custloc_" + editId + "_" + Integer.toHexString(url.hashCode()) + ".jpg";
        new Thread(() -> {
            final File f = com.crowja.damiupos.util.BitmapUtils.downloadToCache(
                    getApplicationContext(), url, name);
            final Bitmap b = f != null
                    ? com.crowja.damiupos.util.BitmapUtils.decodeSampled(f.getAbsolutePath(), 300, 300)
                    : null;
            if (f != null && b == null) f.delete();
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || b == null) return;
                iv.setImageBitmap(b);
            });
        }).start();
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    /** Tombol "+ Foto" satu baris lokasi: perlu pelanggan TERSIMPAN (uuid sudah ada) dan
     *  kuota belum penuh, lalu minta izin kamera (bila belum) dan ambil foto. */
    private void addLocationPhotoClicked(LocationRow row) {
        if (editId == -1) {
            Toast.makeText(this, "Simpan pelanggan dulu, baru bisa menambah foto lokasi",
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (row.photos.size() >= MAX_LOCATION_PHOTOS) {
            Toast.makeText(this, "Maksimal " + MAX_LOCATION_PHOTOS + " foto per lokasi",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        pendingPhotoRow = row;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA}, REQUEST_PERMISSION_CAMERA_LOCATION);
            return;
        }
        takeLocationPhoto(row);
    }

    private void takeLocationPhoto(LocationRow row) {
        Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        if (takePictureIntent.resolveActivity(getPackageManager()) == null) return;
        File photoFile;
        try {
            photoFile = createLocationImageFile();
        } catch (IOException e) {
            Toast.makeText(this, "Gagal membuat file foto", Toast.LENGTH_SHORT).show();
            return;
        }
        Uri photoURI = FileProvider.getUriForFile(this,
                getApplicationContext().getPackageName() + ".fileprovider", photoFile);
        takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, photoURI);
        pendingPhotoRow = row;
        startActivityForResult(takePictureIntent, REQUEST_CAMERA_LOCATION);
    }

    private File createLocationImageFile() throws IOException {
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        File image = File.createTempFile("LOKASI_" + timeStamp, ".jpg", storageDir);
        pendingLocationPhotoPath = image.getAbsolutePath();
        return image;
    }

    /** Kompres (720p) + unggah foto lokasi yang baru diambil, lalu tempel URL-nya ke row.photos
     *  dan render ulang strip. Sinkron terhadap koneksi saat itu — beda dari foto rumah yang
     *  diunggah belakangan via MediaUploader, koleksi lokasi TIDAK punya jalur pending offline. */
    private void uploadLocationPhoto(LocationRow row, String localPath) {
        if (!locationRows.contains(row)) {
            new File(localPath).delete();
            return;
        }
        Toast.makeText(this, "Mengunggah foto…", Toast.LENGTH_SHORT).show();
        final String customerUuid = customerDao.getSyncUuidById(editId);
        new Thread(() -> {
            String url = null, err = null;
            try {
                File tmp = new File(new File(localPath).getParentFile(),
                        "loc_upl_" + System.nanoTime() + ".jpg");
                File toUpload = com.crowja.damiupos.util.BitmapUtils
                        .compressForUpload(localPath, tmp, 1280, 85) ? tmp : new File(localPath);
                com.crowja.damiupos.sync.SyncSettings cfg = new com.crowja.damiupos.sync.SyncSettings(
                        new com.crowja.damiupos.db.SettingsDao(DatabaseHelper.getInstance(this)));
                org.json.JSONObject r = new com.crowja.damiupos.sync.SyncApi(cfg)
                        .uploadLocationPhoto(customerUuid, row.id, toUpload);
                url = r.optString("url", "");
                if (tmp.exists()) tmp.delete();
            } catch (Exception e) {
                err = "Gagal mengunggah foto — periksa koneksi internet.";
            } finally {
                new File(localPath).delete();
            }
            final String fUrl = url, fErr = err;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (fUrl != null && !fUrl.isEmpty()) {
                    if (locationRows.contains(row)) {
                        row.photos.add(fUrl);
                        renderLocationPhotos(row);
                    }
                } else {
                    Toast.makeText(this, fErr != null ? fErr : "Gagal mengunggah foto",
                            Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    /** Hapus baris lokasi. Menghapus SEMUA baris = menghapus semua lokasi pelanggan
     *  (tersimpan sebagai "[]" sehingga clear ikut tersinkron). */
    private void removeLocationRow(LocationRow row) {
        if (row == searchRow) {
            stopLocationSearch();
            searchRow = null;
        }
        if (row == pendingMapRow) pendingMapRow = null;
        llLokasi.removeView(row.view);
        locationRows.remove(row);
    }

    /** Satu baris "Nomor HP lain": EditText telp + tombol ✕. Nomor tambahan opsional (etTelepon =
     *  nomor utama). Dibangun programatik supaya tak perlu layout baru. */
    private void addPhoneRow(String value) {
        android.widget.LinearLayout row = new android.widget.LinearLayout(this);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        int dp8 = Math.round(8 * getResources().getDisplayMetrics().density);
        row.setPadding(0, dp8, 0, 0);
        android.widget.EditText et = new android.widget.EditText(this);
        et.setInputType(android.text.InputType.TYPE_CLASS_PHONE);
        et.setHint("Nomor lain");
        if (value != null) et.setText(value);
        row.addView(et, new android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        android.widget.ImageButton rm = new android.widget.ImageButton(this);
        rm.setImageResource(android.R.drawable.ic_menu_close_clear_cancel);
        rm.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        rm.setContentDescription("Hapus nomor ini");
        rm.setOnClickListener(v -> {
            llPhonesExtra.removeView(row);
            extraPhoneRows.remove(et);
        });
        row.addView(rm);
        llPhonesExtra.addView(row);
        extraPhoneRows.add(et);
    }

    /** Nomor HP untuk disimpan: nomor UTAMA (etTelepon) + nomor tambahan non-kosong. Urutan dijaga
     *  (utama dulu); dedup dilakukan DAO (putPhones). Kosong semua → daftar kosong. */
    private java.util.List<String> collectPhones(String primary) {
        // Normalisasi tiap nomor ke format lokal 08XXXX sebelum disimpan (primer + tambahan).
        java.util.List<String> out = new java.util.ArrayList<>();
        String p0 = com.crowja.damiupos.util.PhoneUtils.toLocal08(primary == null ? "" : primary.trim());
        if (!p0.isEmpty()) out.add(p0);
        for (android.widget.EditText et : extraPhoneRows) {
            String v = et.getText() != null ? et.getText().toString().trim() : "";
            String pv = com.crowja.damiupos.util.PhoneUtils.toLocal08(v);
            if (!pv.isEmpty()) out.add(pv);
        }
        return out;
    }

    /**
     * Nomor yang BELUM pernah ada pada pelanggan ini (dibandingkan kanonik, jadi "0812…" ≡
     * "+62 812…"). Hanya nomor baru yang perlu dicek ulang ke WhatsApp — mengecek ulang nomor lama
     * tiap kali staf mengedit alamat/foto akan memunculkan WhatsApp tanpa alasan.
     */
    private java.util.List<String> newPhonesSince(Customer before, java.util.List<String> after) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (after == null) return out;
        java.util.Set<String> old = new java.util.HashSet<>();
        if (before != null && before.getPhones() != null) {
            for (String p : before.getPhones()) {
                String c = com.crowja.damiupos.util.PhoneUtils.canonical(p);
                if (!c.isEmpty()) old.add(c);
            }
        }
        for (String p : after) {
            String c = com.crowja.damiupos.util.PhoneUtils.canonical(p);
            if (!c.isEmpty() && !old.contains(c)) out.add(p);
        }
        return out;
    }

    /** Jalankan cek WhatsApp untuk nomor-nomor ini (no-op bila kosong). Tak pernah memblokir simpan
     *  — lihat {@link com.crowja.damiupos.wa.WaNumberCheck}. */
    private void scheduleWaCheck(long customerId, java.util.List<String> phones) {
        if (phones == null || phones.isEmpty()) return;
        com.crowja.damiupos.wa.WaNumberCheck.checkAndFlag(this, customerId, phones);
    }

    /** Kumpulkan lokasi dari baris form (urutan tampilan = urutan simpan; baris pertama
     *  ber-koordinat = lokasi utama). Baris tanpa koordinat (0,0) dilewati; nama kosong
     *  → "Kediaman". Bisa kosong = user menghapus semua lokasi. */
    private java.util.List<Customer.Location> collectLocations() {
        java.util.List<Customer.Location> out = new ArrayList<>();
        for (LocationRow r : locationRows) {
            if (r.lat == 0 && r.lng == 0) continue;
            String name = r.etName.getText() != null ? r.etName.getText().toString().trim() : "";
            if (name.isEmpty()) name = Customer.DEFAULT_LOCATION_NAME;
            Customer.Location loc = new Customer.Location(name, r.lat, r.lng, r.cbWajib.isChecked());
            loc.id = r.id;
            loc.photos.addAll(r.photos);
            loc.photo = r.photos.isEmpty() ? null : r.photos.get(0);
            out.add(loc);
        }
        return out;
    }

    /** Pilih gambar dari galeri (tanpa izin storage; pakai document picker). */
    private void pickFromGallery() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("image/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(Intent.createChooser(i, "Pilih foto"), REQUEST_PICK_GALLERY);
        } catch (Exception e) {
            Toast.makeText(this, "Tidak ada aplikasi galeri", Toast.LENGTH_SHORT).show();
        }
    }

    /** Buka workflow "Ambil Foto & Koordinat": foto rumah + koordinat lokasi utama sekaligus. */
    private void takePhotoAndCoordinate() {
        startActivityForResult(new Intent(this, PhotoCoordinateActivity.class), REQUEST_PHOTO_COORD);
    }

    private void takePhoto() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA}, REQUEST_PERMISSION_CAMERA);
            return;
        }

        Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        if (takePictureIntent.resolveActivity(getPackageManager()) != null) {
            File photoFile = null;
            try {
                photoFile = createImageFile();
            } catch (IOException e) {
                Toast.makeText(this, "Gagal membuat file foto", Toast.LENGTH_SHORT).show();
                return;
            }

            if (photoFile != null) {
                Uri photoURI = FileProvider.getUriForFile(this,
                        getApplicationContext().getPackageName() + ".fileprovider",
                        photoFile);
                takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, photoURI);
                startActivityForResult(takePictureIntent, REQUEST_CAMERA);
            }
        }
    }

    private File createImageFile() throws IOException {
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        String imageFileName = "RUMAH_" + timeStamp;
        File storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        File image = File.createTempFile(imageFileName, ".jpg", storageDir);
        currentPhotoPath = image.getAbsolutePath();
        return image;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CAMERA && resultCode == RESULT_OK) {
            if (currentPhotoPath != null) {
                ivFotoRumah.setImageBitmap(loadRotatedBitmap(currentPhotoPath));
                lastGoodPhotoPath = currentPhotoPath;
            }
        } else if (requestCode == REQUEST_CAMERA) {
            // Kamera dibatalkan: createImageFile() sudah terlanjur menimpa currentPhotoPath
            // dengan file temp 0-byte. Tanpa reset, save() menganggapnya "foto baru" →
            // foto lama pelanggan terhapus dari HP + dashboard. Kembalikan path valid terakhir.
            if (currentPhotoPath != null && !currentPhotoPath.equals(lastGoodPhotoPath)) {
                try { new File(currentPhotoPath).delete(); } catch (Exception ignored) {}
            }
            currentPhotoPath = lastGoodPhotoPath;
        } else if (requestCode == REQUEST_CAMERA_LOCATION) {
            LocationRow row = pendingPhotoRow;
            pendingPhotoRow = null;
            if (resultCode == RESULT_OK && row != null && pendingLocationPhotoPath != null) {
                uploadLocationPhoto(row, pendingLocationPhotoPath);
            } else if (pendingLocationPhotoPath != null) {
                // Kamera dibatalkan / gagal — buang file temp 0-byte, tak ada yang diunggah.
                try { new File(pendingLocationPhotoPath).delete(); } catch (Exception ignored) {}
            }
            pendingLocationPhotoPath = null;
        } else if (requestCode == REQUEST_PICK_GALLERY && resultCode == RESULT_OK && data != null
                && data.getData() != null) {
            try {
                File dest = createImageFile();   // sets currentPhotoPath
                if (com.crowja.damiupos.util.BitmapUtils.copyUriToFile(this, data.getData(), dest)) {
                    ivFotoRumah.setImageBitmap(loadRotatedBitmap(currentPhotoPath));
                    lastGoodPhotoPath = currentPhotoPath;
                } else {
                    try { dest.delete(); } catch (Exception ignored) {}
                    currentPhotoPath = lastGoodPhotoPath;
                    Toast.makeText(this, "Gagal memuat foto dari galeri", Toast.LENGTH_SHORT).show();
                }
            } catch (IOException e) {
                Toast.makeText(this, "Gagal menyimpan foto", Toast.LENGTH_SHORT).show();
            }
        } else if (requestCode == REQUEST_PICK_MAP && resultCode == RESULT_OK && data != null) {
            // Arahkan hasil picker ke BARIS yang meluncurkannya (multi-lokasi).
            if (pendingMapRow != null && locationRows.contains(pendingMapRow)) {
                pendingMapRow.lat = data.getDoubleExtra(MapPickerActivity.EXTRA_LATITUDE, 0);
                pendingMapRow.lng = data.getDoubleExtra(MapPickerActivity.EXTRA_LONGITUDE, 0);
                updateKoordinatDisplay(pendingMapRow);
            }
            pendingMapRow = null;
        } else if (requestCode == REQUEST_PHOTO_COORD && resultCode == RESULT_OK && data != null) {
            // Workflow gabungan: set foto rumah + koordinat lokasi UTAMA (baris pertama) sekaligus.
            String path = data.getStringExtra(PhotoCoordinateActivity.EXTRA_PHOTO_PATH);
            if (path != null && !path.isEmpty()) {
                currentPhotoPath = path;
                lastGoodPhotoPath = path;
                ivFotoRumah.setImageBitmap(loadRotatedBitmap(currentPhotoPath));
            }
            double lat = data.getDoubleExtra(PhotoCoordinateActivity.EXTRA_LAT, 0);
            double lng = data.getDoubleExtra(PhotoCoordinateActivity.EXTRA_LNG, 0);
            if (lat != 0 || lng != 0) {
                if (locationRows.isEmpty()) addLocationRow(null);   // pastikan ada baris utama
                LocationRow primary = locationRows.get(0);
                primary.lat = lat;
                primary.lng = lng;
                updateKoordinatDisplay(primary);
            }
            Toast.makeText(this, "Foto & koordinat lokasi utama tersimpan", Toast.LENGTH_SHORT).show();
        }
    }

    /** Preview foto hemat memori (downsample + RGB_565 + EXIF). */
    private Bitmap loadRotatedBitmap(String photoPath) {
        return com.crowja.damiupos.util.BitmapUtils.decodeSampled(photoPath, 1024, 1024);
    }

    /** Unduh foto rumah dari server (pelanggan lintas-perangkat tanpa file lokal) lalu tampilkan.
     *  Hanya untuk PRATINJAU di form Edit — tidak menyentuh currentPhotoPath, jadi save() tetap
     *  menganggap foto TIDAK berubah (photo_url tak di-clear, tak ada re-upload). */
    private void loadFotoRumahFromUrl(String url) {
        final String name = "custform_" + editId + "_" + Integer.toHexString(url.hashCode()) + ".jpg";
        new Thread(() -> {
            final File f = com.crowja.damiupos.util.BitmapUtils.downloadToCache(
                    getApplicationContext(), url, name);
            final Bitmap b = f != null
                    ? com.crowja.damiupos.util.BitmapUtils.decodeSampled(f.getAbsolutePath(), 1024, 1024)
                    : null;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || b == null) return;
                // Jangan timpa kalau user keburu ambil foto baru sambil menunggu unduhan.
                if (currentPhotoPath == null || currentPhotoPath.equals(originalPhotoPath)) {
                    ivFotoRumah.setImageBitmap(b);
                }
            });
        }).start();
    }

    private void updateKoordinatDisplay(LocationRow row) {
        if (row == null) return;
        if (row.lat != 0 || row.lng != 0) {
            row.tvCoord.setText(String.format(Locale.US, "%.6f, %.6f", row.lat, row.lng));
            row.btnMaps.setVisibility(android.view.View.VISIBLE);
        } else {
            row.tvCoord.setText("Belum ada koordinat");
            row.btnMaps.setVisibility(android.view.View.GONE);
        }
    }

    private void updateTanggalButton() {
        if (btnTanggalDaftar == null) return;
        try {
            Date d = dbDateFmt.parse(selectedCreatedAt);
            btnTanggalDaftar.setText("Tanggal Daftar: "
                    + (d != null ? displayDateFmt.format(d) : "Hari ini"));
        } catch (Exception e) {
            btnTanggalDaftar.setText("Tanggal Daftar: Hari ini");
        }
    }

    private void showDatePicker() {
        Calendar cal = Calendar.getInstance();
        try {
            Date d = dbDateFmt.parse(selectedCreatedAt);
            if (d != null) cal.setTime(d);
        } catch (Exception ignored) {}
        new DatePickerDialog(this, (view, year, month, dayOfMonth) -> {
            Calendar picked = Calendar.getInstance();
            picked.set(Calendar.YEAR, year);
            picked.set(Calendar.MONTH, month);
            picked.set(Calendar.DAY_OF_MONTH, dayOfMonth);
            selectedCreatedAt = dbDateFmt.format(picked.getTime());
            updateTanggalButton();
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show();
    }

    /** Buka koordinat sebuah baris lokasi di aplikasi Google Maps (intent). */
    private void openInGoogleMaps(LocationRow row) {
        if (row.lat == 0 && row.lng == 0) {
            Toast.makeText(this, "Belum ada koordinat", Toast.LENGTH_SHORT).show();
            return;
        }
        String label = etNama.getText() != null ? etNama.getText().toString().trim() : "";
        String locName = row.etName.getText() != null ? row.etName.getText().toString().trim() : "";
        if (!locName.isEmpty()) label = label.isEmpty() ? locName : label + " — " + locName;
        String uri = "geo:" + row.lat + "," + row.lng + "?q=" + row.lat + "," + row.lng
                + (label.isEmpty() ? "" : "(" + Uri.encode(label) + ")");
        Intent gmaps = new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
        gmaps.setPackage("com.google.android.apps.maps");
        if (gmaps.resolveActivity(getPackageManager()) != null) {
            startActivity(gmaps);
        } else {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://www.google.com/maps/search/?api=1&query="
                            + row.lat + "," + row.lng)));
        }
    }

    private void pickFromMap(LocationRow row) {
        pendingMapRow = row;
        Intent intent = new Intent(this, MapPickerActivity.class);
        if (row.lat != 0 || row.lng != 0) {
            intent.putExtra(MapPickerActivity.EXTRA_LATITUDE, row.lat);
            intent.putExtra(MapPickerActivity.EXTRA_LONGITUDE, row.lng);
        }
        startActivityForResult(intent, REQUEST_PICK_MAP);
    }

    /** "Pakai Link Google Maps": dialog tempel link (panjang/pendek) atau teks "lat, lng" →
     *  koordinat baris ini. Cermin popup web (App\Support\MapsLink via /api/maps-link/resolve). */
    private void showMapsLinkDialog(LocationRow row) {
        int pad = dpToPx(20);
        final com.google.android.material.textfield.TextInputEditText input =
                new com.google.android.material.textfield.TextInputEditText(this);
        input.setHint("Tempel link Google Maps atau \"lat, lng\"");
        input.setSingleLine(false);
        input.setMinLines(2);
        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setPadding(pad, pad, pad, 0);
        box.addView(input);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Pakai Link Google Maps")
                .setView(box)
                .setPositiveButton("Proses", null)
                .setNegativeButton("Batal", null)
                .create();
        dialog.setOnShowListener(d -> {
            android.widget.Button pos = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            pos.setOnClickListener(v -> {
                String text = input.getText() != null ? input.getText().toString().trim() : "";
                if (text.isEmpty()) {
                    Toast.makeText(this, "Tempel link atau koordinat dulu", Toast.LENGTH_SHORT).show();
                    return;
                }
                resolveMapsLinkForRow(dialog, pos, row, text);
            });
        });
        dialog.show();
    }

    private void resolveMapsLinkForRow(AlertDialog dialog, android.widget.Button pos,
                                        LocationRow row, String text) {
        pos.setEnabled(false);
        pos.setText("Memproses…");
        com.crowja.damiupos.sync.SyncSettings cfg = new com.crowja.damiupos.sync.SyncSettings(
                new com.crowja.damiupos.db.SettingsDao(DatabaseHelper.getInstance(this)));
        if (!cfg.isEnrolled()) {
            Toast.makeText(this, "Perangkat belum terhubung ke server.", Toast.LENGTH_LONG).show();
            dialog.dismiss();
            return;
        }
        new Thread(() -> {
            Double lat = null, lng = null;
            String err = null;
            try {
                org.json.JSONObject r = new com.crowja.damiupos.sync.SyncApi(cfg).resolveMapsLink(text);
                lat = r.optDouble("lat", Double.NaN);
                lng = r.optDouble("lng", Double.NaN);
                if (lat.isNaN() || lng.isNaN()) { lat = null; lng = null; err = "Respons server tak berisi koordinat."; }
            } catch (com.crowja.damiupos.sync.SyncApi.SyncException se) {
                try { err = new org.json.JSONObject(se.body).optString("message", null); } catch (Exception ignored) {}
                if (err == null) err = "Gagal memproses link (kode " + se.code + ").";
            } catch (Exception e) {
                err = "Gagal memproses — periksa koneksi internet.";
            }
            final Double fLat = lat, fLng = lng;
            final String fErr = err;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (fLat != null && fLng != null) {
                    row.lat = fLat;
                    row.lng = fLng;
                    updateKoordinatDisplay(row);
                    Toast.makeText(this, "Koordinat terisi dari link Maps", Toast.LENGTH_SHORT).show();
                    dialog.dismiss();
                } else {
                    Toast.makeText(this, fErr, Toast.LENGTH_LONG).show();
                    pos.setEnabled(true);
                    pos.setText("Proses");
                }
            });
        }).start();
    }

    /**
     * "Lokasi Saat Ini" untuk SATU baris lokasi: tombol ini berperan ganda — mulai
     * pencarian, atau (kalau sedang mencari) membatalkannya. Koordinat baru HANYA
     * di-commit ke baris peluncur setelah akurasi GPS ≤{@link #REQUIRED_ACCURACY_M}
     * meter tercapai (atau user memilih "Pakai Koordinat Ini" saat timeout) — supaya
     * membatalkan pencarian tidak menimpa koordinat pelanggan yang sudah tersimpan (edit).
     */
    private void getLocation(LocationRow row) {
        if (searchingLocation) {
            boolean sameRow = (row == searchRow);
            LocationRow prev = searchRow;
            stopLocationSearch();
            updateKoordinatDisplay(prev);   // kembalikan tampilan ke koordinat ter-commit
            if (sameRow) return;            // tombol yang sama = batal
        }
        searchRow = row;   // baris peluncur — juga dipakai ulang setelah izin diberikan
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQUEST_PERMISSION_LOCATION);
            return;
        }

        if (locationManager == null) {
            locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        }
        if (locationManager == null) {
            Toast.makeText(this, "Location service tidak tersedia", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            Toast.makeText(this, "Aktifkan GPS untuk mendapat koordinat akurat (≤5m)",
                    Toast.LENGTH_LONG).show();
            return;
        }

        startLocationSearch();
    }

    private void startLocationSearch() {
        final LocationRow row = searchRow;
        if (row == null) return;
        searchingLocation = true;
        searchAccuracy = Float.MAX_VALUE;
        row.pb.setVisibility(View.VISIBLE);
        row.btnGps.setText("Batal Mencari");
        row.btnPeta.setEnabled(false);
        row.tvCoord.setText("Mencari lokasi GPS akurat (≤" + (int) REQUIRED_ACCURACY_M + "m)...");

        activeLocationListener = new LocationListener() {
            @Override
            public void onLocationChanged(@NonNull Location location) {
                searchLat = location.getLatitude();
                searchLng = location.getLongitude();
                searchAccuracy = location.getAccuracy();
                row.tvCoord.setText(String.format(Locale.US,
                        "Mencari... %.6f, %.6f (akurasi ±%.0fm, butuh ≤%dm)",
                        searchLat, searchLng, searchAccuracy, (int) REQUIRED_ACCURACY_M));
                if (searchAccuracy <= REQUIRED_ACCURACY_M) {
                    row.lat = searchLat;
                    row.lng = searchLng;
                    stopLocationSearch();
                    updateKoordinatDisplay(row);
                    Toast.makeText(CustomerFormActivity.this,
                            "Koordinat akurat didapat (±" + Math.round(searchAccuracy) + "m)",
                            Toast.LENGTH_SHORT).show();
                }
            }

            @Override public void onProviderEnabled(@NonNull String p) {}

            @Override
            public void onProviderDisabled(@NonNull String p) {
                Toast.makeText(CustomerFormActivity.this, "GPS dimatikan", Toast.LENGTH_SHORT).show();
                stopLocationSearch();
                updateKoordinatDisplay(row);
            }
        };

        try {
            locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER, 1000L, 0f, activeLocationListener, getMainLooper());
        } catch (SecurityException e) {
            Toast.makeText(this, "Permission lokasi ditolak", Toast.LENGTH_SHORT).show();
            stopLocationSearch();
            updateKoordinatDisplay(row);
            return;
        }

        locationTimeoutHandler = new android.os.Handler(getMainLooper());
        locationTimeoutRunnable = this::onLocationSearchTimeout;
        locationTimeoutHandler.postDelayed(locationTimeoutRunnable, LOCATION_TIMEOUT_MS);
    }

    /** Setelah {@link #LOCATION_TIMEOUT_MS} tanpa mencapai akurasi target: tawarkan
     *  pakai fix terbaik yang didapat / coba lagi / batal — jangan menunggu tanpa batas. */
    private void onLocationSearchTimeout() {
        if (!searchingLocation) return;
        final LocationRow row = searchRow;
        boolean haveFix = searchAccuracy < Float.MAX_VALUE;
        stopLocationSearch();
        if (row == null) return;

        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle("Akurasi GPS Belum Tercapai")
                .setMessage(haveFix
                        ? String.format(Locale.US,
                                "Setelah %d detik, akurasi terbaik yang didapat adalah ±%.0fm "
                                        + "(target ≤%dm).\n\nPastikan berada di ruang terbuka untuk hasil "
                                        + "lebih akurat.",
                                LOCATION_TIMEOUT_MS / 1000, searchAccuracy, (int) REQUIRED_ACCURACY_M)
                        : "Tidak berhasil mendapat sinyal GPS. Pastikan berada di ruang terbuka lalu coba lagi.")
                .setNegativeButton("Coba Lagi", (d, w) -> { searchRow = row; startLocationSearch(); });
        if (haveFix) {
            b.setPositiveButton("Pakai Koordinat Ini", (d, w) -> {
                row.lat = searchLat;
                row.lng = searchLng;
                updateKoordinatDisplay(row);
            });
            b.setNeutralButton("Batal", (d, w) -> updateKoordinatDisplay(row));
        } else {
            b.setPositiveButton("Batal", (d, w) -> updateKoordinatDisplay(row));
        }
        b.setCancelable(false).show();
    }

    /** Hentikan listener + timer + reset UI baris yang sedang mencari. Tidak menyentuh
     *  koordinat baris — pemanggil yang memutuskan apakah fix terakhir di-commit atau dibuang. */
    private void stopLocationSearch() {
        searchingLocation = false;
        if (locationManager != null && activeLocationListener != null) {
            try { locationManager.removeUpdates(activeLocationListener); } catch (Exception ignored) {}
        }
        activeLocationListener = null;
        if (locationTimeoutHandler != null && locationTimeoutRunnable != null) {
            locationTimeoutHandler.removeCallbacks(locationTimeoutRunnable);
        }
        if (searchRow != null) {
            searchRow.pb.setVisibility(View.GONE);
            searchRow.btnGps.setText("Lokasi Saat Ini");
            searchRow.btnPeta.setEnabled(true);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopLocationSearch();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            if (requestCode == REQUEST_PERMISSION_CAMERA) {
                takePhoto();
            } else if (requestCode == REQUEST_PERMISSION_LOCATION) {
                // Lanjutkan pencarian untuk baris yang meminta izin (masih tersimpan di searchRow).
                if (searchRow != null && locationRows.contains(searchRow)) getLocation(searchRow);
            } else if (requestCode == REQUEST_PERMISSION_CONTACTS) {
                if (pendingContactName != null && pendingContactPhone != null) {
                    writeContact(pendingContactName, pendingContactPhone);
                    pendingContactName = null;
                    pendingContactPhone = null;
                }
            } else if (requestCode == REQUEST_PERMISSION_CAMERA_LOCATION) {
                if (pendingPhotoRow != null && locationRows.contains(pendingPhotoRow)) {
                    takeLocationPhoto(pendingPhotoRow);
                }
            }
        } else {
            if (requestCode == REQUEST_PERMISSION_CONTACTS) {
                Toast.makeText(this, "Izin kontak ditolak, tidak disimpan ke HP",
                        Toast.LENGTH_SHORT).show();
                finish();
            } else {
                Toast.makeText(this, "Permission ditolak", Toast.LENGTH_SHORT).show();
            }
        }
    }

    /** Dialog pilih reseller rujukan pelanggan (opsional). Simpan sync_uuid reseller terpilih. */
    private void showLinkedResellerPicker() {
        // Dedup salinan lintas-perangkat (nomor sama) → satu reseller per orang di picker.
        java.util.List<Customer> resellers = CustomerDao.dedupeByIdentity(customerDao.getResellers());
        // Kecualikan diri sendiri (reseller tak bisa ditautkan ke reseller lain).
        java.util.List<Customer> pick = new ArrayList<>();
        for (Customer r : resellers) {
            if (editId == -1 || r.getId() != editId) pick.add(r);
        }
        if (pick.isEmpty()) {
            Toast.makeText(this, "Belum ada reseller di cabang ini.", Toast.LENGTH_SHORT).show();
            return;
        }
        final CharSequence[] items = new CharSequence[pick.size() + 1];
        items[0] = "— Tidak ditautkan —";
        for (int i = 0; i < pick.size(); i++) {
            Customer r = pick.get(i);
            String phone = r.getPhone() != null && !r.getPhone().isEmpty() ? "  ·  " + r.getPhone() : "";
            items[i + 1] = r.getName() + phone;
        }
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Tautkan ke Reseller")
                .setItems(items, (d, w) -> {
                    linkedResellerUuid = (w == 0) ? null : customerDao.getSyncUuidById(pick.get(w - 1).getId());
                    updateLinkedResellerLabel();
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    /** Tampilkan nama reseller tertaut di tombol (atau "Tidak ditautkan"). */
    private void updateLinkedResellerLabel() {
        if (btnLinkedReseller == null) return;
        String name = null;
        if (linkedResellerUuid != null && !linkedResellerUuid.isEmpty()) {
            long rid = customerDao.getIdBySyncUuid(linkedResellerUuid);
            if (rid > 0) {
                Customer r = customerDao.getById(rid);
                if (r != null) name = r.getName();
            }
        }
        btnLinkedReseller.setText(name != null ? "Reseller: " + name : "Tidak ditautkan — ketuk untuk pilih reseller");
        updateSkipPhotoCoordVisibility();
    }

    /** Tampilkan opsi "Lewati wajib foto & koordinat" saat pelanggan ini dijadikan reseller ATAU
     *  ditautkan ke reseller lain; sembunyikan (+reset checked) selain itu. */
    private void updateSkipPhotoCoordVisibility() {
        if (cbSkipPhotoCoordReseller == null) return;
        boolean relevant = (cbReseller != null && cbReseller.isChecked())
                || (linkedResellerUuid != null && !linkedResellerUuid.isEmpty());
        cbSkipPhotoCoordReseller.setVisibility(relevant ? View.VISIBLE : View.GONE);
        if (!relevant) cbSkipPhotoCoordReseller.setChecked(true);   // reset default utk lain kali muncul
    }

    /** Nomor yang SUDAH lolos cek pelanggan-terhapus di server — supaya save() lanjutan
     *  (dipanggil ulang setelah cek async) tidak berputar memeriksa nomor yang sama lagi. */
    private String deletedCheckedPhone = null;

    /**
     * Popup ❗ pemblokir penambahan pelanggan duplikat. {@code deleted}=true → nomor milik
     * pelanggan yang sudah DIHAPUS dari dashboard (tidak boleh didaftarkan ulang dari HP;
     * pulihkan lewat menu Pelanggan Terhapus di web bila memang diperlukan).
     */
    private void showDuplicateBlockedDialog(String existingName, String phone, boolean deleted) {
        String msg = deleted
                ? "Nomor " + phone + " pernah terdaftar sebagai pelanggan yang sudah DIHAPUS " +
                  "dari dashboard.\n\nPenambahan diblokir. Bila pelanggan ini memang harus " +
                  "kembali, pulihkan dari menu \"Pelanggan Terhapus\" di dashboard web."
                : "Nomor " + phone + " sudah terdaftar atas nama:\n\n• "
                  + (existingName != null ? existingName : "(tanpa nama)")
                  + "\n\nPenambahan diblokir — gunakan pelanggan yang sudah ada "
                  + "(cari di daftar Pelanggan), atau perbaiki nomornya bila memang orang berbeda.";
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setTitle("⚠️ Pelanggan Sudah Ada")
                .setMessage(msg)
                .setPositiveButton("MENGERTI", null)
                .show();
    }

    /**
     * Cek ke server apakah nomor ini milik pelanggan yang sudah DIHAPUS di dashboard
     * (endpoint {@code /api/customers/deleted-check} — kanonik +62/62/0, branch-scoped),
     * lalu lanjutkan save() bila lolos. Best-effort: belum provisioning / offline / server
     * error → LANJUT simpan (input lapangan tak boleh terblokir jaringan); duplikat aktif
     * sudah tertangkap oleh cek lokal di atasnya.
     */
    /** Cek server apakah SALAH SATU nomor pelanggan cocok tombstone pelanggan terhapus (semua nomor
     *  dikirim; server mencocokkan phones[]). Key gabungan → re-entry save() tak loop. */
    private void runDeletedCheckThenSave(java.util.List<String> phones) {
        String key = android.text.TextUtils.join(",", phones);
        com.crowja.damiupos.db.SettingsDao sd =
                new com.crowja.damiupos.db.SettingsDao(DatabaseHelper.getInstance(this));
        com.crowja.damiupos.sync.SyncSettings cfg = new com.crowja.damiupos.sync.SyncSettings(sd);
        if (!cfg.isEnrolled()) {
            deletedCheckedPhone = key;
            save();
            return;
        }
        androidx.appcompat.app.AlertDialog waiting = new androidx.appcompat.app.AlertDialog.Builder(this)
                .setMessage("Memeriksa nomor di server…")
                .setCancelable(false)
                .show();
        new Thread(() -> {
            boolean deleted = false;
            try {
                org.json.JSONArray arr = new org.json.JSONArray();
                for (String p : phones) arr.put(p);
                org.json.JSONObject r = new com.crowja.damiupos.sync.SyncApi(cfg)
                        .customersDeletedCheck(arr);
                org.json.JSONArray del = r.optJSONArray("deleted");
                deleted = del != null && del.length() > 0;
            } catch (Exception ignored) {
                // Offline/timeout → tidak bisa cek; jangan blokir akuisisi di lapangan.
            }
            boolean fDeleted = deleted;
            runOnUiThread(() -> {
                try { waiting.dismiss(); } catch (Exception ignored) {}
                if (isFinishing() || isDestroyed()) return;
                if (fDeleted) {
                    showDuplicateBlockedDialog(null, phones.isEmpty() ? "" : phones.get(0), true);
                } else {
                    deletedCheckedPhone = key;
                    save();   // lanjut alur simpan normal — semua nomor sudah lolos
                }
            });
        }).start();
    }

    private void save() {
        String nama = etNama.getText() != null ? etNama.getText().toString().trim() : "";
        String telepon = etTelepon.getText() != null ? etTelepon.getText().toString().trim() : "";
        // Normalisasi nomor utama ke format lokal 08XXXX SEBELUM validasi & simpan (mis. "+62 812…",
        // "812…", "62812…" → "0812…"). Nomor kosong tetap dibiarkan kosong. Sinkron dgn PhoneUtils web.
        telepon = com.crowja.damiupos.util.PhoneUtils.toLocal08(telepon);
        String alamat = etAlamat.getText() != null ? etAlamat.getText().toString().trim() : "";

        if (nama.isEmpty()) {
            etNama.setError("Nama wajib diisi");
            etNama.requestFocus();
            return;
        }

        // Guard FORMAT nomor telp untuk pelanggan BARU: harus diawali 08, hanya angka, 9–13 digit.
        // (Kosong tetap diperbolehkan = pelanggan tanpa nomor.) Edit tak dipagari agar nomor lama
        // berformat lain — mis. "+62…" hasil sinkron — tak menghalangi simpan.
        if (editId == -1 && !telepon.isEmpty()
                && !telepon.equals(waPrefillPhone)   // nomor WA asli (mis. non-08) dikecualikan
                && (!telepon.matches("\\d{9,13}") || !telepon.startsWith("08"))) {
            etTelepon.setError("Nomor HP harus diawali 08, hanya angka, dan 9–13 digit");
            etTelepon.requestFocus();
            return;
        }

        // Guard anti-duplikat (hanya pelanggan BARU): dibandingkan secara KANONIK, mencakup SEMUA
        // nomor (utama + tambahan) — nomor kedua pun tak boleh bentrok dengan pelanggan lain (kalau
        // bentrok, satu nomor akan me-resolve ke DUA pelanggan → ambiguitas identitas yang dicegah
        // fitur ini). "0812…" ≡ "+62 812…" ≡ "62812…" dianggap sama.
        java.util.List<String> allPhones = collectPhones(telepon);
        if (editId == -1 && !allPhones.isEmpty()) {
            // 1) Duplikat AKTIF di database lokal (pelanggan branch-wide, semua salinan perangkat).
            for (String p : allPhones) {
                Customer dup = customerDao.findByPhoneCanonical(p);
                if (dup != null) {
                    showDuplicateBlockedDialog(dup.getName(), p, false);
                    return;
                }
            }
            // 2) Pelanggan TERHAPUS di dashboard (tombstone) → tanya server (semua nomor), lalu lanjut.
            String phonesKey = android.text.TextUtils.join(",", allPhones);
            if (!phonesKey.equals(deletedCheckedPhone)) {
                runDeletedCheckThenSave(allPhones);
                return;
            }
        }

        // Lokasi (multi): baris tanpa koordinat dilewati; entri PERTAMA = lokasi utama.
        java.util.List<Customer.Location> locs = collectLocations();
        Customer.Location primary = locs.isEmpty() ? null : locs.get(0);

        // Marketing & operator (staf/SPV) wajib melengkapi FOTO RUMAH + KOORDINAT saat input pelanggan
        // BARU (akuisisi harus terverifikasi lokasi & foto). Pelanggan lama (edit) tidak diblokir agar
        // tak menyulitkan koreksi. Koordinat yang dicek = lokasi UTAMA (baris pertama ber-koordinat).
        // Pelanggan reseller/terafiliasi: opsi "Lewati wajib foto & koordinat" (tampil hanya saat
        // relevan — lihat updateSkipPhotoCoordVisibility) membebaskan dari kewajiban ini, mengikuti
        // permintaan user bahwa pelanggan terafiliasi reseller tak perlu diverifikasi lokasi fisik.
        boolean skipForReseller = cbSkipPhotoCoordReseller != null
                && cbSkipPhotoCoordReseller.getVisibility() == View.VISIBLE
                && cbSkipPhotoCoordReseller.isChecked();
        if (requirePhotoCoord && editId == -1 && !skipForReseller) {
            boolean hasPhoto = currentPhotoPath != null && !currentPhotoPath.isEmpty()
                    && new File(currentPhotoPath).exists();
            boolean hasCoord = primary != null;
            if (!hasPhoto || !hasCoord) {
                String pesan = !hasPhoto && !hasCoord ? "Wajib ambil FOTO RUMAH dan KOORDINAT pelanggan baru."
                        : (!hasPhoto ? "Wajib ambil FOTO RUMAH pelanggan baru." : "Wajib ambil KOORDINAT pelanggan baru.");
                Toast.makeText(this, pesan, Toast.LENGTH_LONG).show();
                return;
            }
        }

        // Jaga-jaga: path menggantung ke file kosong/hilang (mis. kamera batal, process death)
        // → anggap foto TIDAK berubah, jangan sampai menimpa/menghapus foto lama.
        if (currentPhotoPath != null && !currentPhotoPath.equals(originalPhotoPath)) {
            File cf = new File(currentPhotoPath);
            if (!cf.exists() || cf.length() == 0) currentPhotoPath = originalPhotoPath;
        }

        // Foto baru/diganti → kompres ke 720p (sisi panjang ≤1280, JPEG) SEBELUM disimpan,
        // supaya file yang diunggah ke web kecil & cepat (kamera 12MP ≈ 5–8 MB → ~200 KB).
        if (currentPhotoPath != null && !currentPhotoPath.equals(originalPhotoPath)) {
            compressPhotoTo720p(currentPhotoPath);
        }

        Customer customer = new Customer(nama, telepon, alamat);
        String catatanOrder = etCatatanOrder.getText() != null ? etCatatanOrder.getText().toString().trim() : "";
        customer.setOrderNote(catatanOrder.isEmpty() ? null : catatanOrder);
        // Multi-nomor: nomor utama (etTelepon) + nomor tambahan. DAO (putPhones) mencerminkan
        // phone = phones[0]. Daftar disimpan supaya nomor kedua ikut tersinkron ke web & HP lain.
        customer.setPhones(collectPhones(telepon));
        customer.setPhotoPath(currentPhotoPath);
        customer.setCreatedAt(selectedCreatedAt);
        // Multi-lokasi + mirror kompatibilitas: entri pertama → latitude/longitude/wajib_ongkir
        // legacy (DAO menerapkan mirror yang sama saat menulis kolom; hapus semua lokasi → 0/false).
        customer.setLocations(locs);
        customer.setLatitude(primary != null ? primary.lat : 0);
        customer.setLongitude(primary != null ? primary.lng : 0);
        // Wajib Ongkir = aturan per-PELANGGAN, tak boleh hilang hanya karena belum ada koordinat:
        // collectLocations membuang baris tanpa koordinat, jadi kalau pelanggan belum dipetakan,
        // ambil centang wajib dari baris apa pun (form transaksi memakai scalar ini saat tanpa lokasi).
        boolean wajib = primary != null && primary.wajibOngkir;
        if (primary == null) {
            for (LocationRow r : locationRows) {
                if (r.cbWajib != null && r.cbWajib.isChecked()) { wajib = true; break; }
            }
        }
        customer.setWajibOngkir(wajib);

        // Reseller: kalau dicentang & belum pernah jadi reseller → set since=now.
        // Kalau sudah pernah (edit), pertahankan tanggal lama supaya komisi
        // historis tidak berubah.
        boolean isReseller = cbReseller != null && cbReseller.isChecked();
        customer.setReseller(isReseller);
        // Tautan reseller: reseller sendiri tak boleh ditautkan ke reseller lain (afiliasi = dirinya).
        customer.setLinkedResellerUuid(isReseller ? null : linkedResellerUuid);
        // Komisi-ke-harga hanya berlaku kalau pelanggan reseller.
        customer.setKomisiAddToPrice(isReseller
                && cbKomisiAddToPrice != null && cbKomisiAddToPrice.isChecked());
        if (isReseller) {
            customer.setResellerSince(
                    existingResellerSince != null && !existingResellerSince.isEmpty()
                            ? existingResellerSince
                            : dbDateFmt.format(new Date()));
        } else {
            customer.setResellerSince(existingResellerSince); // simpan utk histori
        }
        // Harga khusus per produk: kumpulkan input → Map (kosong → null = ikut harga produk standar).
        customer.setProductPrices(collectProductPrices());

        if (editId != -1) {
            customer.setId(editId);
            // Penugasan perangkat (override) diatur dari web (web-authoritative, di-skip saat push).
            // Form HP tak menyuntingnya, jadi salin nilai lama supaya update tak mengosongkannya
            // secara lokal (jadi transaksi memakai default yang benar sampai pull berikutnya).
            Customer existingC = customerDao.getById(editId);
            if (existingC != null) {
                customer.setAssignedDeviceUuid(existingC.getAssignedDeviceUuid());
            }
            customerDao.update(customer);
            // Nomor BARU/BERUBAH → cek ke WhatsApp di latar belakang; tandai bermasalah bila mati.
            scheduleWaCheck(editId, newPhonesSince(existingC, allPhones));
            // Photo changed → clear the stale server URL so the new file re-uploads
            // (MediaUploader runs before push, so the dashboard URL stays current).
            if (currentPhotoPath != null && !currentPhotoPath.equals(originalPhotoPath)) {
                customerDao.clearPhotoUrl(editId);
                // Foto lama tak lagi direferensikan — hapus supaya storage HP depot
                // (24/7) tidak membengkak oleh file 5–8 MB yang yatim.
                if (originalPhotoPath != null) {
                    try { new File(originalPhotoPath).delete(); } catch (Exception ignored) {}
                }
            }
            // Kirim segera ke web (unggah foto + push baris) tanpa menunggu worker 15-menit.
            com.crowja.damiupos.sync.SyncScheduler.syncNow(getApplicationContext());
            Toast.makeText(this, "Pelanggan berhasil diupdate", Toast.LENGTH_SHORT).show();
            finish();
        } else {
            lastInsertedId = customerDao.insert(customer);
            // Pelanggan baru → semua nomornya dicek ke WhatsApp di latar belakang.
            scheduleWaCheck(lastInsertedId, allPhones);
            // Kirim segera ke web (unggah foto + push baris) tanpa menunggu worker 15-menit.
            com.crowja.damiupos.sync.SyncScheduler.syncNow(getApplicationContext());
            // Caller (mis. TransactionActivity) bisa langsung auto-pick
            // pelanggan yang baru dibuat tanpa harus search ulang.
            if (lastInsertedId > 0) {
                setResult(RESULT_OK, new Intent()
                        .putExtra(EXTRA_NEW_CUSTOMER_ID, lastInsertedId));
            }
            // Alur "Input Promosi Galon": lanjut ke Transaksi Baru mode Promosi untuk pelanggan baru.
            if (getIntent().getBooleanExtra(EXTRA_PROMOSI_FLOW, false) && lastInsertedId > 0) {
                Toast.makeText(this, "Pelanggan baru disimpan — lanjut input galon", Toast.LENGTH_SHORT).show();
                startActivity(new Intent(this, TransactionActivity.class)
                        .putExtra(TransactionActivity.EXTRA_PROMOSI, true)
                        .putExtra(TransactionActivity.EXTRA_PROMOSI_CUSTOMER_ID, lastInsertedId));
                finish();
                return;
            }
            Toast.makeText(this, "Pelanggan berhasil ditambahkan", Toast.LENGTH_SHORT).show();
            // Auto-save to phone contacts (new customers only, with phone number)
            if (!telepon.isEmpty()) {
                saveToContacts(nama, telepon);
                // finish() handled inside contact-save flow
            } else {
                finish();
            }
        }
    }

    /**
     * Kompres foto rumah in-place ke 720p (sisi panjang ≤1280, JPEG q85). Rotasi EXIF
     * ikut di-bake sehingga foto tampil tegak di web. Gagal → file asli dibiarkan;
     * MediaUploader tetap men-downscale saat unggah, jadi foto tidak pernah hilang.
     */
    private void compressPhotoTo720p(String path) {
        try {
            File src = new File(path);
            if (!src.exists() || src.length() == 0) return;
            File tmp = new File(src.getParentFile(), src.getName() + ".720");
            if (com.crowja.damiupos.util.BitmapUtils.compressForUpload(path, tmp, 1280, 85)
                    && tmp.length() > 0) {
                // rename() POSIX mengganti tujuan secara atomik; fallback delete+rename.
                boolean replaced = tmp.renameTo(src) || (src.delete() && tmp.renameTo(src));
                // Buang tmp HANYA kalau file asli masih ada — kalau asli sudah terlanjur
                // terhapus dan rename gagal, tmp adalah satu-satunya salinan foto.
                if (!replaced && src.exists()) tmp.delete();
            } else {
                tmp.delete();
            }
        } catch (Throwable ignored) {
            // Kompresi hanya optimasi — jangan pernah menggagalkan simpan pelanggan.
        }
    }

    /** Bangun satu baris input harga untuk SETIAP produk. Prefill: edit → harga khusus tersimpan
     *  (produk tanpa override dibiarkan kosong = ikut standar); pelanggan baru → harga standar. */
    private void buildProductPriceRows(Customer existing) {
        if (llHargaProduk == null) return;
        llHargaProduk.removeAllViews();
        priceRows.clear();
        java.util.List<com.crowja.damiupos.model.Product> products =
                new com.crowja.damiupos.db.ProductDao(DatabaseHelper.getInstance(this)).getAll();
        java.util.Map<String, Double> overrides = existing != null ? existing.getProductPrices() : null;
        for (com.crowja.damiupos.model.Product p : products) {
            if (p.getUuid() == null || p.getUuid().isEmpty()) continue;   // butuh uuid sebagai kunci sync
            View row = android.view.LayoutInflater.from(this)
                    .inflate(R.layout.item_customer_product_price, llHargaProduk, false);
            ((TextView) row.findViewById(R.id.tvProdukNama)).setText(p.getName());
            TextInputEditText et = row.findViewById(R.id.etProdukHarga);
            Double ov = overrides != null ? overrides.get(p.getUuid()) : null;
            // SELALU tampilkan harga EFEKTIF (override khusus pelanggan, atau harga standar) — baik
            // saat tambah maupun EDIT — supaya field tidak terlihat kosong. Nilai yang sama dengan
            // harga standar TIDAK disimpan sebagai override (lihat collectProductPrices), jadi
            // pelanggan tanpa penyesuaian tetap ikut harga standar (tak "beku" saat form dibuka).
            et.setText(fmtPrice(ov != null ? ov : p.getHargaJual()));
            llHargaProduk.addView(row);
            priceRows.add(new PriceRow(p.getUuid(), et, p.getHargaJual()));
        }
    }

    /** Kumpulkan harga per produk dari input → Map { uuid: harga }. Kosong → null (ikut standar). */
    private java.util.Map<String, Double> collectProductPrices() {
        java.util.Map<String, Double> map = new java.util.HashMap<>();
        for (PriceRow r : priceRows) {
            String s = r.et.getText() != null ? r.et.getText().toString().trim() : "";
            if (s.isEmpty()) continue;   // dikosongkan = ikut harga standar
            try {
                double v = Double.parseDouble(s.replace(",", "."));
                // Hanya simpan sebagai override kalau BEDA dari harga standar. Nilai = standar
                // (mis. field ter-prefill dan tidak diubah) dianggap "ikut standar" → tidak
                // membekukan harga pelanggan pada nilai default saat form kebetulan dibuka+disimpan.
                if (Math.abs(v - r.def) > 0.001) map.put(r.uuid, v);
            } catch (NumberFormatException ignored) {}
        }
        return map.isEmpty() ? null : map;
    }

    /** Tampilkan harga tanpa ".0" untuk nilai bulat. */
    private static String fmtPrice(double v) {
        if (v == Math.floor(v) && !Double.isInfinite(v)) return String.valueOf((long) v);
        return String.valueOf(v);
    }

    private void saveToContacts(String name, String phone) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_CONTACTS)
                != PackageManager.PERMISSION_GRANTED) {
            pendingContactName = name;
            pendingContactPhone = phone;
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.WRITE_CONTACTS},
                    REQUEST_PERMISSION_CONTACTS);
            return;
        }
        writeContact(name, phone);
    }

    /**
     * Simpan ke kontak HP lewat {@link com.crowja.damiupos.wa.WaContactEnsure} — SATU implementasi
     * yang sama dipakai penyimpanan otomatis saat transaksi dibuat, jadi keduanya tak bisa
     * menyimpang. Bonusnya: helper itu memakai PhoneLookup, sehingga nomor yang SUDAH ada tidak
     * lagi digandakan (versi lama di sini selalu menyisipkan baris baru).
     */
    private void writeContact(String name, String phone) {
        boolean ok = com.crowja.damiupos.wa.WaContactEnsure.ensure(this, name, phone);
        Toast.makeText(this, ok ? "Disimpan ke kontak HP" : "Gagal simpan ke kontak",
                ok ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG).show();
        finish();
    }
}
