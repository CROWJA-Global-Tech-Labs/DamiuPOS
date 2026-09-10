package com.crowja.damiupos;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.View;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import com.crowja.damiupos.db.CustomerDao;
import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.DeliveryObstacleDao;
import com.crowja.damiupos.db.SettingsDao;
import com.crowja.damiupos.model.Customer;
import com.crowja.damiupos.util.BitmapUtils;
import com.google.android.material.textfield.TextInputEditText;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import com.crowja.damiupos.util.CameraIntents;

/**
 * "Laporan Kendala Pengiriman" — kurir melaporkan kendala yang menghambat SATU RIT (motor rusak,
 * banjir, ban bocor, …): alasan + foto bukti + pelanggan mana saja yang terdampak.
 *
 * <p>Dibuka dari menu ⋮ layar Antrian Delivery. Daftar pelanggan terdampak dikirim pemanggil sebagai
 * SNAPSHOT (id lokal) — bukan dibaca ulang di sini — supaya laporannya menggambarkan antrean pada
 * detik kejadian, bukan antrean yang sudah berubah saat kurir selesai mengetik.
 *
 * <p>Setelah tersimpan, staf ditawari "Beritahu Konsumen": WhatsApp dibuka SATU PER SATU langsung ke
 * chat tiap pelanggan (lewat extra "jid") dengan FOTO kendala sudah terlampir; staf sendiri yang
 * menekan Kirim. Itu memang cara paling jujur yang tersedia — WhatsApp tak mengizinkan pengiriman
 * gambar otomatis, dan menekan "Kirim" secara otomatis untuk lampiran (jalur arm() milik
 * WaAutoSendService) TIDAK terverifikasi terhadap chat tujuan sehingga foto bisa mendarat di chat
 * orang lain. Kirim manual per pelanggan jauh lebih lambat, tapi tak pernah salah alamat.
 */
public class DeliveryObstacleActivity extends AppCompatActivity {

    /** id lokal transaksi antrean yang terdampak (long[]). */
    public static final String EXTRA_TRX_IDS = "trx_ids";

    private static final int REQ_CAMERA = 6301;
    private static final int REQ_PICK_IMAGE = 6302;
    private static final int REQ_PERM_CAMERA = 6303;
    private static final int REQ_PERM_CONTACTS = 6304;

    /** Preset alasan KENDALA di jalan — bukan alasan pembatalan order (itu milik dialog Void).
     *  "Lainnya" sengaja mengosongkan field, bukan mengisi teks literal. */
    private static final String[] OBSTACLE_PRESETS = {
            "Motor rusak", "Ban bocor", "Kehabisan bensin", "Banjir", "Hujan deras",
            "Jalan macet total", "Jalan ditutup", "Kecelakaan", "Stok air habis",
            "Lainnya (jelaskan)",
    };

    private TextInputEditText etAlasan;
    private ImageView ivFoto;
    private LinearLayout affectedList;

    private String currentPhotoPath;
    /** Foto terakhir yang BERHASIL — dipulihkan bila staf membatalkan kamera (kalau tidak,
     *  currentPhotoPath menunjuk file 0 byte hasil createTempFile yang batal terisi). */
    private String lastGoodPhotoPath;

    private final List<Customer> affected = new ArrayList<>();
    private final List<CheckBox> boxes = new ArrayList<>();

    private DeliveryObstacleDao dao;

    /** Antrean "Beritahu Konsumen" yang sedang berjalan (indeks pelanggan berikutnya). */
    private List<Customer> notifyQueue = new ArrayList<>();
    private int notifyIndex = 0;
    private int notifySent = 0;
    private long savedObstacleId = -1;
    private String savedPhotoPath = "";
    private String savedReason = "";
    private boolean notifyRunning = false;
    /** Sudah menekan "Buka WhatsApp" untuk pelanggan ini, menunggu staf kembali. */
    private boolean awaitingWaReturn = false;
    /** Activity benar-benar sempat ke BELAKANG (WhatsApp betul-betul mengambil layar). */
    private boolean wentToBackground = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_delivery_obstacle);

        androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        dao = new DeliveryObstacleDao(DatabaseHelper.getInstance(this));
        etAlasan = findViewById(R.id.etAlasan);
        ivFoto = findViewById(R.id.ivFoto);
        affectedList = findViewById(R.id.affectedList);

        // Pintasan alasan — komponen chip yang sama dengan dialog Void (gaya seragam), tapi
        // PRESET-nya khas kendala pengiriman: preset Void ("Berubah pikiran", "Tidak ada orang", …)
        // bicara soal pembatalan order oleh pelanggan, sama sekali bukan kendala di jalan.
        ((android.widget.FrameLayout) findViewById(R.id.quickReasonHolder))
                .addView(DeliveryVoidDialog.buildQuickReasonRow(this, etAlasan, OBSTACLE_PRESETS));

        loadAffected();

        findViewById(R.id.btnFoto).setOnClickListener(v -> takePhoto());
        findViewById(R.id.btnPickGallery).setOnClickListener(v -> pickFromGallery());
        findViewById(R.id.btnSimpan).setOnClickListener(v -> save());
    }

    /** Pelanggan terdampak dari snapshot id transaksi yang dikirim Antrian Delivery. */
    private void loadAffected() {
        long[] ids = getIntent().getLongArrayExtra(EXTRA_TRX_IDS);
        com.crowja.damiupos.db.TransactionDao tDao =
                new com.crowja.damiupos.db.TransactionDao(DatabaseHelper.getInstance(this));
        CustomerDao cDao = new CustomerDao(DatabaseHelper.getInstance(this));

        java.util.HashSet<Long> seen = new java.util.HashSet<>();
        if (ids != null) {
            for (long id : ids) {
                com.crowja.damiupos.model.Transaction t = tDao.getById(id);
                if (t == null || t.getCustomerId() <= 0 || !seen.add(t.getCustomerId())) continue;
                Customer c = cDao.getById(t.getCustomerId());
                if (c != null) affected.add(c);
            }
        }

        TextView title = findViewById(R.id.tvAffectedTitle);
        title.setText("Pelanggan terdampak (" + affected.size() + ")");

        if (affected.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("Tidak ada pelanggan di antrean saat ini.");
            empty.setTextColor(getResources().getColor(R.color.grey_medium));
            empty.setTextSize(13f);
            affectedList.addView(empty);
            return;
        }

        for (Customer c : affected) {
            boolean hasWa = hasUsablePhone(c.getPhone());
            CheckBox cb = new CheckBox(this);
            cb.setText(safe(c.getName()) + (hasWa ? "" : "  (tanpa nomor WA)"));
            cb.setChecked(hasWa);
            cb.setEnabled(hasWa);   // tanpa nomor = tak mungkin diberitahu; jangan beri harapan palsu
            cb.setTextSize(14f);
            boxes.add(cb);
            affectedList.addView(cb);
        }
    }

    // ------------------------------------------------------------------ foto

    private void takePhoto() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA}, REQ_PERM_CAMERA);
            return;
        }
        Intent intent = CameraIntents.preferBackCamera(new Intent(MediaStore.ACTION_IMAGE_CAPTURE));
        if (intent.resolveActivity(getPackageManager()) == null) {
            Toast.makeText(this, "Tidak ada app kamera", Toast.LENGTH_SHORT).show();
            return;
        }
        File photoFile;
        try {
            photoFile = createImageFile();
        } catch (IOException e) {
            Toast.makeText(this, "Gagal membuat file foto: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            return;
        }
        Uri photoURI = FileProvider.getUriForFile(this,
                getApplicationContext().getPackageName() + ".fileprovider", photoFile);
        intent.putExtra(MediaStore.EXTRA_OUTPUT, photoURI);
        startActivityForResult(intent, REQ_CAMERA);
    }

    private void pickFromGallery() {
        // ACTION_GET_CONTENT (bukan ACTION_PICK): andal di semua versi Android & tanpa izin storage.
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(Intent.createChooser(intent, "Pilih foto"), REQ_PICK_IMAGE);
        } catch (Exception ex) {
            Toast.makeText(this, "Tidak dapat membuka galeri", Toast.LENGTH_SHORT).show();
        }
    }

    /** File WAJIB di bawah getExternalFilesDir(PICTURES) — satu-satunya root yang dideklarasikan
     *  file_paths.xml; direktori lain membuat FileProvider melempar "Failed to find configured root".
     *  Jangan pernah getCacheDir(): MediaUploader mengosongkan photo_path bila filenya lenyap. */
    private File createImageFile() throws IOException {
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        File image = File.createTempFile("KENDALA_" + timeStamp, ".jpg", storageDir);
        currentPhotoPath = image.getAbsolutePath();
        return image;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERM_CAMERA && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            takePhoto();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQ_CAMERA) {
            if (resultCode == RESULT_OK && currentPhotoPath != null) {
                lastGoodPhotoPath = currentPhotoPath;
                showFotoPreview(BitmapUtils.decodeForScreen(this, currentPhotoPath));
            } else {
                // Batal memotret: buang file kosong & kembalikan foto sebelumnya (kalau ada).
                if (currentPhotoPath != null) {
                    try { new File(currentPhotoPath).delete(); } catch (Exception ignored) {}
                }
                currentPhotoPath = lastGoodPhotoPath;
            }
            return;
        }

        if (requestCode == REQ_PICK_IMAGE && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri == null) return;
            try {
                File dest = createImageFile();
                if (!BitmapUtils.copyUriToFile(this, uri, dest)) {
                    throw new IOException("Tidak dapat baca foto");
                }
                lastGoodPhotoPath = currentPhotoPath;
                showFotoPreview(BitmapUtils.decodeForScreen(this, currentPhotoPath));
            } catch (Exception ex) {
                Toast.makeText(this, "Gagal salin foto: " + ex.getMessage(), Toast.LENGTH_SHORT).show();
                currentPhotoPath = lastGoodPhotoPath;
            }
        }
    }

    /** Tint placeholder WAJIB di-clear sebelum setImageBitmap — kalau tidak, foto ikut diwarnai
     *  (SRC_IN) dan tampil sebagai kotak abu-abu solid. */
    private void showFotoPreview(Bitmap photo) {
        if (photo != null) {
            ivFoto.setImageTintList(null);
            ivFoto.setImageBitmap(photo);
        } else {
            ivFoto.setImageTintList(android.content.res.ColorStateList.valueOf(
                    getResources().getColor(R.color.grey_medium)));
            ivFoto.setImageResource(android.R.drawable.ic_menu_camera);
        }
    }

    // ------------------------------------------------------------------ simpan

    private void save() {
        String reason = etAlasan.getText() != null ? etAlasan.getText().toString().trim() : "";
        if (reason.isEmpty()) {
            etAlasan.setError("Tulis dulu kendalanya");
            etAlasan.requestFocus();
            return;
        }

        List<Customer> ticked = tickedCustomers();
        List<String> uuids = new ArrayList<>();
        CustomerDao cDao = new CustomerDao(DatabaseHelper.getInstance(this));
        for (Customer c : ticked) {
            String u = cDao.getSyncUuidById(c.getId());
            if (u != null && !u.isEmpty()) uuids.add(u);
        }

        com.crowja.damiupos.sync.SyncSettings cfg = new com.crowja.damiupos.sync.SyncSettings(
                new SettingsDao(DatabaseHelper.getInstance(this)));

        long id = dao.insert(reason, currentPhotoPath, uuids, 0, 0, cfg.getDeviceUuid());
        // Satu-satunya pemicu MediaUploader + push — tanpa ini foto & laporan menunggu tick berikutnya.
        com.crowja.damiupos.sync.SyncScheduler.syncNow(getApplicationContext());

        savedObstacleId = id;
        savedReason = reason;
        savedPhotoPath = currentPhotoPath != null ? currentPhotoPath : "";

        if (ticked.isEmpty()) {
            Toast.makeText(this, "Laporan kendala terkirim.", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        confirmNotify(ticked);
    }

    private List<Customer> tickedCustomers() {
        List<Customer> out = new ArrayList<>();
        for (int i = 0; i < boxes.size() && i < affected.size(); i++) {
            if (boxes.get(i).isChecked() && hasUsablePhone(affected.get(i).getPhone())) {
                out.add(affected.get(i));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ Beritahu Konsumen

    /** Konfirmasi yang JUJUR: sebutkan bahwa staf menekan Kirim sendiri di tiap chat. */
    private void confirmNotify(List<Customer> targets) {
        new AlertDialog.Builder(this)
                .setTitle("Beritahu Konsumen?")
                .setMessage(targets.size() + " pelanggan akan diberitahu lewat WhatsApp.\n\n"
                        + "WhatsApp dibuka SATU PER SATU langsung ke chat pelanggan, alasan + foto "
                        + "kendala sudah terlampir — Anda tinggal menekan Kirim di tiap chat, lalu "
                        + "kembali ke aplikasi ini untuk lanjut ke pelanggan berikutnya.")
                .setPositiveButton("Mulai Beritahu", (d, w) -> {
                    // Izin kontak dipakai untuk menyimpan nomor pelanggan supaya WhatsApp mau
                    // membuka chat-nya langsung. Ditolak → blast tetap jalan, cuma lewat "Kirim ke…".
                    if (!com.crowja.damiupos.wa.WaContactEnsure.hasPermission(this)) {
                        ActivityCompat.requestPermissions(this, new String[]{
                                Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS,
                        }, REQ_PERM_CONTACTS);
                    }
                    notifyQueue = targets;
                    notifyIndex = 0;
                    notifySent = 0;
                    notifyRunning = true;
                    stepNotify();
                })
                .setNegativeButton("Nanti Saja", (d, w) -> {
                    Toast.makeText(this, "Laporan tersimpan. Pelanggan belum diberitahu.",
                            Toast.LENGTH_LONG).show();
                    finish();
                })
                .setCancelable(false)
                .show();
    }

    /** Tampilkan pelanggan ke-N + tombol buka WhatsApp; dipanggil ulang tiap staf kembali. */
    private void stepNotify() {
        if (notifyIndex >= notifyQueue.size()) {
            finishNotify();
            return;
        }
        Customer c = notifyQueue.get(notifyIndex);
        new AlertDialog.Builder(this)
                .setTitle("Pelanggan " + (notifyIndex + 1) + " dari " + notifyQueue.size())
                .setMessage(safe(c.getName()) + "\n" + safe(c.getPhone())
                        + "\n\nBuka WhatsApp, tekan Kirim, lalu kembali ke sini.")
                .setPositiveButton("Buka WhatsApp", (d, w) -> {
                    awaitingWaReturn = true;
                    wentToBackground = false;
                    if (sendToWhatsApp(c)) {
                        notifySent++;   // "dibuka" — bukan bukti terkirim, lihat finishNotify
                        notifyIndex++;
                        return;
                    }
                    // WhatsApp gagal dibuka sama sekali: jangan gantung antreannya (onResume tak
                    // akan pernah lanjut karena layar tak pernah berpindah) — tawarkan lagi
                    // pelanggan yang SAMA supaya staf bisa coba ulang atau melewatinya.
                    awaitingWaReturn = false;
                    stepNotify();
                })
                .setNeutralButton("Lewati", (d, w) -> {
                    notifyIndex++;
                    stepNotify();
                })
                .setNegativeButton("Berhenti", (d, w) -> finishNotify())
                .setCancelable(false)
                .show();
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Layar benar-benar berpindah ke app lain (WhatsApp). Ini penanda yang dipercaya untuk
        // membedakan "staf sudah di WhatsApp" dari sekadar onResume sesaat.
        if (awaitingWaReturn) wentToBackground = true;
    }

    /**
     * Staf kembali dari WhatsApp → tawarkan pelanggan berikutnya.
     *
     * <p>WAJIB menunggu {@link #wentToBackground}: onResume juga berjalan pada saat-saat lain
     * (mis. tepat setelah dialog ditutup, sebelum WhatsApp sempat mengambil alih layar). Tanpa
     * penjaga ini dialog berikutnya muncul seketika, menarik task aplikasi ini ke depan, dan
     * WhatsApp yang baru saja dibuka langsung terdorong ke belakang — persis gejala
     * "WA terbuka lalu tiba-tiba tertutup".
     */
    @Override
    protected void onResume() {
        super.onResume();
        if (notifyRunning && awaitingWaReturn && wentToBackground) {
            awaitingWaReturn = false;
            wentToBackground = false;
            stepNotify();
        }
    }

    private void finishNotify() {
        notifyRunning = false;
        if (savedObstacleId > 0) {
            try {
                JSONObject results = new JSONObject();
                results.put("opened", notifySent);
                results.put("target", notifyQueue.size());
                dao.markNotified(savedObstacleId, notifySent, results.toString());
                com.crowja.damiupos.sync.SyncScheduler.syncNow(getApplicationContext());
            } catch (Exception ignored) {}
        }
        // Kata-katanya sengaja "dibuka", BUKAN "terkirim": aplikasi tak pernah tahu apakah staf
        // benar-benar menekan Kirim di WhatsApp, dan melaporkan sukses yang tak teramati adalah
        // kebohongan yang akan dipercaya owner saat pelanggan komplain tak dapat kabar.
        new AlertDialog.Builder(this)
                .setTitle("Selesai")
                .setMessage(notifySent + " dari " + notifyQueue.size()
                        + " chat pelanggan sudah dibuka.\n\nLaporan kendala tersimpan & terkirim ke dashboard.")
                .setPositiveButton("Tutup", (d, w) -> finish())
                .setCancelable(false)
                .show();
    }

    /**
     * Buka LANGSUNG jendela chat pelanggan di WhatsApp dengan foto kendala sudah terlampir dan
     * alasan sebagai caption — TANPA layar "pilih kontak".
     *
     * <p>Kuncinya extra {@code "jid"}: WhatsApp memakainya untuk melompati pemilih kontak dan
     * membuka chat nomor itu dengan lampiran sudah menempel. Extra itu hanya dihormati oleh
     * activity penerima share-nya ({@code com.whatsapp.contact.picker.ContactPicker} — namanya
     * memang "picker", tapi dengan jid ia TIDAK menampilkan daftar kontak, langsung ke chat).
     * Karena itu komponennya ditembak eksplisit dulu; kalau nama itu berubah di versi WhatsApp
     * tertentu, {@code resolveActivity} dipakai sebagai cadangan (juga tetap membawa jid).
     *
     * <p>setComponent, bukan sekadar setPackage: Samsung Freecess diam-diam membatalkan
     * startActivity berbasis setPackage ke WhatsApp yang sedang beku — tanpa exception, sehingga
     * tombolnya tampak mati. Chooser sistem hanya dipakai kalau WhatsApp benar-benar tak ada.
     */
    private boolean sendToWhatsApp(Customer c) {
        String text = "Mohon maaf, pengiriman pesanan Anda hari ini terkendala.\n\n"
                + savedReason + "\n\nKami segera menindaklanjuti. Terima kasih atas pengertiannya 🙏";
        String jid = waJid(c.getPhone());

        File photo = savedPhotoPath.isEmpty() ? null : new File(savedPhotoPath);
        Uri uri = null;
        if (photo != null && photo.exists()) {
            try {
                uri = FileProvider.getUriForFile(this,
                        getApplicationContext().getPackageName() + ".fileprovider", photo);
            } catch (Exception ignored) {
                uri = null;
            }
        }
        // Tanpa foto (atau URI gagal dibuat) tak ada gunanya jalur share: wa.me membuka chat
        // pelanggan langsung dengan teksnya, itu sudah persis yang dibutuhkan.
        if (uri == null || jid == null) {
            return openWaTextOnly(c.getPhone(), text);
        }

        // WhatsApp hanya menghormati "jid" untuk nomor yang TERSIMPAN di kontak HP (diuji langsung
        // di perangkat, lihat WaContactEnsure). Nomor pelanggan yang belum tersimpan akan membuat
        // WhatsApp jatuh ke layar "Kirim ke…". Jadi simpan dulu — sekali saja per pelanggan.
        com.crowja.damiupos.wa.WaContactEnsure.ensure(this, c.getName(), c.getPhone());

        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("image/jpeg");
        send.putExtra(Intent.EXTRA_STREAM, uri);
        send.putExtra(Intent.EXTRA_TEXT, text);       // jadi caption foto
        send.putExtra("jid", jid);                    // ← lompati pemilih kontak, buka chat ini
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        String wa = pickWaPackage();
        if (wa != null) {
            // SENGAJA TIDAK memanggil WaAutoSendService.arm(): verifikasi tujuannya hanya berlaku
            // untuk pesan TEKS (membaca kotak ketik chat). Pada layar pratinjau media verifikasi itu
            // runtuh, jadi klik-otomatis bisa mengirim foto ke chat orang lain.
            //
            // Kandidat komponen, BERURUT. WhatsApp mengganti nama kelas share-nya antar rilis
            // (2.26 memakai com.whatsapp.contact.ui.picker.ExternalShareAlias; rilis lama
            // com.whatsapp.contact.picker.ContactPicker — perhatikan sisipan ".ui."), jadi
            // resolveActivity tetap disimpan sebagai jaring pengaman di urutan TERAKHIR.
            java.util.List<android.content.ComponentName> candidates = new ArrayList<>();
            // ALIAS share dulu, resolveActivity BELAKANGAN. Alasannya terbukti di perangkat:
            // ExternalShareAlias adalah activity-alias, dan ResolveInfo.activityInfo.name untuk
            // sebuah alias mengembalikan targetActivity-nya (…picker.ContactPicker) — yaitu layar
            // "Kirim ke…" itu sendiri, yang MENGABAIKAN jid. Menembak alias-nya langsung membuat
            // WhatsApp membuka MediaComposer dengan penerima sudah terpilih.
            for (String cls : new String[]{
                    "com.whatsapp.contact.ui.picker.ExternalShareAlias",
                    "com.whatsapp.contact.picker.ContactPicker"}) {
                try {
                    android.content.ComponentName cn = new android.content.ComponentName(wa, cls);
                    getPackageManager().getActivityInfo(cn, 0);
                    candidates.add(cn);
                } catch (Exception ignored) {
                    // versi WhatsApp ini tak punya kelas itu → lewati
                }
            }
            Intent probe = new Intent(Intent.ACTION_SEND).setType("image/jpeg").setPackage(wa);
            android.content.pm.ResolveInfo info = getPackageManager().resolveActivity(probe, 0);
            if (info != null && info.activityInfo != null) {
                android.content.ComponentName cn = new android.content.ComponentName(
                        info.activityInfo.packageName, info.activityInfo.name);
                if (!candidates.contains(cn)) candidates.add(cn);
            }

            for (android.content.ComponentName target : candidates) {
                Intent direct = new Intent(send).setComponent(target);
                direct.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    startActivity(direct);
                    return true;
                } catch (Exception ignored) {
                    // komponen ada tapi menolak (mis. tak exported) → coba kandidat berikutnya
                }
            }
            send.setPackage(wa);
            send.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(send);
                return true;
            } catch (Exception ignored) {
                send.setPackage(null);
            }
        }
        try {
            startActivity(Intent.createChooser(send, "Kirim ke pelanggan"));
            return true;
        } catch (Exception e) {
            Toast.makeText(this, "Tidak dapat membuka WhatsApp", Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    private boolean openWaTextOnly(String phone, String text) {
        String d = digits(phone);
        if (d.startsWith("0")) d = "62" + d.substring(1);
        else if (d.startsWith("8")) d = "62" + d;
        try {
            Intent i = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://wa.me/" + d + "?text=" + Uri.encode(text)));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return true;
        } catch (Exception e) {
            Toast.makeText(this, "Tidak dapat membuka WhatsApp", Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    private String pickWaPackage() {
        android.content.pm.PackageManager pm = getPackageManager();
        try { pm.getPackageInfo("com.whatsapp", 0); return "com.whatsapp"; } catch (Exception ignored) {}
        try { pm.getPackageInfo("com.whatsapp.w4b", 0); return "com.whatsapp.w4b"; } catch (Exception ignored) {}
        return null;
    }

    /** Nomor → JID "62XXXXXXXXXX@s.whatsapp.net" (cermin ReceiptActivity.waJid). */
    private static String waJid(String phone) {
        String d = digits(phone);
        if (d.startsWith("0")) d = "62" + d.substring(1);
        else if (d.startsWith("8")) d = "62" + d;
        return d.length() >= 9 ? d + "@s.whatsapp.net" : null;
    }

    private static boolean hasUsablePhone(String phone) {
        return digits(phone).length() >= 9;
    }

    private static String digits(String s) {
        return s == null ? "" : s.replaceAll("[^0-9]", "");
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
