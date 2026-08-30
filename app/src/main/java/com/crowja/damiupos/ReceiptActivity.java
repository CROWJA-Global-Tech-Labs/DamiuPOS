package com.crowja.damiupos;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.FileProvider;

import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.SettingsDao;
import com.crowja.damiupos.db.TransactionDao;
import com.crowja.damiupos.model.TransactionItem;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public class ReceiptActivity extends AppCompatActivity {

    public static final String EXTRA_CUSTOMER_NAME = "customer_name";
    public static final String EXTRA_CUSTOMER_PHONE = "customer_phone";
    /** Local customers._id of the buyer — lets the struk-share attach active campaign links
     *  (and record an anti-rebroadcast delivery) for this customer. <=0 = no customer / walk-in. */
    public static final String EXTRA_CUSTOMER_ID = "customer_id";
    /** Nama pengirim WA dari OrderInbox (kalau transaksi dibuat dari inbox).
     *  Dipakai untuk lookup tambahan di Android Contacts kalau nama DAMIU POS
     *  customer berbeda dari nama kontak HP (mis. "My Dj" vs "My Dj ❤️"). */
    public static final String EXTRA_INBOX_SENDER_NAME = "inbox_sender_name";
    public static final String EXTRA_PRODUCT_NAME = "product_name";
    public static final String EXTRA_JUMLAH = "jumlah";
    public static final String EXTRA_HARGA_PER_GALON = "harga_per_galon";
    public static final String EXTRA_ONGKIR = "ongkir";
    public static final String EXTRA_ONGKIR_TYPE = "ongkir_type";
    /** Harga & kepemilikan botol — dipakai rekonsiliasi ongkir (beli botol BUKAN ongkir). */
    public static final String EXTRA_HARGA_BOTOL = "harga_botol";
    public static final String EXTRA_OWNERSHIP = "galon_ownership";
    public static final String EXTRA_TOTAL_HARGA = "total_harga";
    public static final String EXTRA_CATATAN = "catatan";
    public static final String EXTRA_PAYMENT_METHOD = "payment_method";
    public static final String EXTRA_POINTS_ENABLED = "points_enabled";
    public static final String EXTRA_POINTS_EARNED = "points_earned";
    public static final String EXTRA_POINTS_TOTAL = "points_total";
    public static final String EXTRA_POINTS_REWARD = "points_reward";
    public static final String EXTRA_ITEMS_JSON = "items_json";
    public static final String EXTRA_REWARD_UNLOCKED = "reward_unlocked";
    public static final String EXTRA_REWARDS_NEW_COUNT = "rewards_new_count";
    /** 🔁 Order kembali PERTAMA (pembelian ke-2 pelanggan) — popup informasi + status prioritas.
     *  Cermin TransactionController::store di web (session flash repeatOrderPrompt). */
    public static final String EXTRA_FIRST_REPEAT_ORDER = "first_repeat_order";
    public static final String EXTRA_FIRST_REPEAT_PRIORITIZED = "first_repeat_prioritized";
    /** 🎁 "Bonus Beli N Gratis 1" terpenuhi pada order ini — deskripsi siap-tampil ("1× Galon 19L"),
     *  null/kosong = tak ada bonus. Cermin popup web (App\Support\ProductBonus). */
    public static final String EXTRA_PRODUCT_BONUS_GRANTED = "product_bonus_granted";
    public static final String EXTRA_TRANSACTION_ID = "transaction_id";
    /** sync_uuid transaksi ini — dipakai untuk menampilkan gift yang di-klaim (redeemed) oleh
     *  transaksi ini di struk (gambar + teks WA). Diisi dari Transaksi Baru & saat re-share. */
    public static final String EXTRA_GIFT_TRX_UUID = "gift_trx_uuid";
    /** ID transaksi unik struk (<KODE>-DDMMYY-<COUNTER>) — cermin App\Support\ReceiptNumber (web). */
    public static final String EXTRA_RECEIPT_NO = "receipt_no";
    /** _id transaksi yang BARU dibuat — hanya untuk popup "pelanggan punya gift produk".
     *  Sengaja terpisah dari EXTRA_TRANSACTION_ID: extra itu memicu hidrasi ulang seluruh
     *  extras dari DB, yang akan menimpa data poin/reward yang sudah disusun TransactionActivity. */
    public static final String EXTRA_GIFT_TRX_ID = "gift_trx_id";
    /** True = jangan tawarkan kirim struk ke pelanggan di sini (struk dikirim saat
     *  order ditandai Selesai di Antrian Delivery). Dipakai oleh Transaksi Baru (JUAL). */
    public static final String EXTRA_DEFER_CUSTOMER_SEND = "defer_customer_send";
    /** Token link lacak pengiriman → tombol "Kirim Struk + Tracking" pada struk penjualan
     *  mengirim {trackBase}/tracking/{token} ke pelanggan bersama gambar struk. */
    public static final String EXTRA_DELIVERY_TOKEN = "delivery_token";
    public static final String EXTRA_IS_GANTI_RUGI = "is_ganti_rugi";
    public static final String EXTRA_GANTI_RUGI_KEMBALI = "ganti_rugi_kembali";
    /** Jumlah botol galon kosong yang dikembalikan pelanggan pada transaksi JUAL ini. */
    public static final String EXTRA_JUMLAH_KEMBALI = "jumlah_kembali";
    /** True = pelanggan baru (transaksi ini yang pertama untuk pelanggan tsb) → ditandai di struk. */
    public static final String EXTRA_IS_NEW_CUSTOMER = "is_new_customer";

    // --- Struk Pencairan Komisi (reseller) ---
    public static final String EXTRA_IS_KOMISI_PAYOUT = "is_komisi_payout";
    public static final String EXTRA_RESELLER_NAME = "reseller_name";
    public static final String EXTRA_PAYOUT_TYPE = "payout_type";   // AIR | UANG
    public static final String EXTRA_PAYOUT_GALON = "payout_galon";
    public static final String EXTRA_PAYOUT_NILAI = "payout_nilai";
    public static final String EXTRA_PAYOUT_AMOUNT = "payout_amount";
    public static final String EXTRA_SALDO_BEFORE = "saldo_before";
    public static final String EXTRA_SALDO_AFTER = "saldo_after";
    public static final String EXTRA_SALDO_DIPOTONG = "saldo_dipotong"; // bagian JUAL yang dibayar dari saldo komisi
    /** Bagian JUAL yang dibayar dari SALDO REFUND (uang kembali). Dibaca dari buku besar refund. */
    public static final String EXTRA_REFUND_DIPOTONG = "refund_dipotong";

    private static final int RECEIPT_WIDTH = 32; // chars for 58mm printer
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private View receiptCard;   // kartu struk modern yang di-capture jadi PNG
    private String receiptText; // versi teks monospace (untuk printer Bluetooth)
    private long loadedTransactionId = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_receipt);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        // Popup iklan 5 detik setelah transaksi selesai (struk terbuka).
        // Otomatis di-cancel kalau activity di-destroy duluan atau user Pro.
        com.crowja.damiupos.ads.AdManager.getInstance(this)
                .scheduleInterstitialAfterDelay(this, 5000L);

        receiptCard = findViewById(R.id.rcCard);

        // If invoked with EXTRA_TRANSACTION_ID, hydrate extras from DB
        long trxId = getIntent().getLongExtra(EXTRA_TRANSACTION_ID, -1);
        if (trxId > 0) {
            hydrateFromTransaction(trxId);
        }

        // Teks monospace tetap dibangun untuk printer Bluetooth (58mm); kartu modern
        // di layar + gambar struk diisi dari data extras yang sama.
        receiptText = buildReceipt();
        populateReceiptCard();
        // Keputusan kampanye diambil dari SERVER (otoritatif) di latar, SEBELUM preview digambar —
        // hasilnya menyusul lalu preview & tombol Kirim memakainya. Lihat prefetchServerCampaigns().
        prefetchServerCampaigns();
        populateStrukTeksPreview();
        wireStrukViewToggle();

        com.google.android.material.button.MaterialButton btnShare = findViewById(R.id.btnShare);
        String custName = getIntent().getStringExtra(EXTRA_CUSTOMER_NAME);
        if (custName != null && !custName.isEmpty()) {
            btnShare.setText("Bagikan ke " + custName);
        } else {
            btnShare.setText("Bagikan ke Pelanggan");
        }

        // Saat dibuka dari Transaksi Baru (JUAL), tombol akhir di struk ini langsung mengirim
        // struk + link lacak ke pelanggan via WhatsApp (lihat blok di bawah). Penyelesaian order
        // di Antrian Delivery cukup tekan "Selesai" — struk tidak dibuka/dikirim ulang di sana.
        boolean deferCustomerSend = getIntent().getBooleanExtra(EXTRA_DEFER_CUSTOMER_SEND, false);
        boolean hasTracking = getIntent().getStringExtra(EXTRA_DELIVERY_TOKEN) != null
                && !getIntent().getStringExtra(EXTRA_DELIVERY_TOKEN).isEmpty();
        View btnExportWaView = findViewById(R.id.btnExportWa);
        if (deferCustomerSend) {
            // Struk penjualan baru: tombol akhir langsung MENGIRIM struk (gambar) + link lacak
            // ke pelanggan via WhatsApp, lalu kembali ke Beranda. Tombol bagi/ekspor manual
            // disembunyikan karena pengiriman sudah ditangani tombol akhir.
            btnShare.setVisibility(View.GONE);
            if (btnExportWaView != null) btnExportWaView.setVisibility(View.GONE);
            TextView note = findViewById(R.id.tvDeferNote);
            if (note != null) {
                note.setText(hasTracking
                        ? "Tekan tombol di bawah untuk mengirim struk + link lacak pengiriman ke pelanggan via WhatsApp."
                        : "Tekan tombol di bawah untuk mengirim struk ke pelanggan via WhatsApp.");
                note.setVisibility(View.VISIBLE);
            }
            com.google.android.material.button.MaterialButton btnDoneSend = findViewById(R.id.btnDone);
            btnDoneSend.setText(hasTracking
                    ? "Lanjutkan & Kirim Struk + Tracking" : "Lanjutkan & Kirim Struk");
        }
        // Celebration dialog if customer unlocked a reward this transaction
        if (getIntent().getBooleanExtra(EXTRA_REWARD_UNLOCKED, false)) {
            int rewardsNew = getIntent().getIntExtra(EXTRA_REWARDS_NEW_COUNT, 1);
            String rewardCustName = getIntent().getStringExtra(EXTRA_CUSTOMER_NAME);
            String msg = (rewardCustName != null && !rewardCustName.isEmpty() ? rewardCustName : "Pelanggan")
                    + " berhak mendapat "
                    + (rewardsNew > 1 ? rewardsNew + " hadiah" : "hadiah")
                    + " dari program loyalitas!\n\nJangan lupa berikan hadiahnya "
                    + "dan beri tahu pelanggan.";
            new AlertDialog.Builder(this)
                    .setTitle("\uD83C\uDF89 Selamat! Pelanggan Dapat Hadiah")
                    .setMessage(msg)
                    .setPositiveButton("OK", null)
                    .setCancelable(false)
                    .show();
        }
        // \uD83D\uDD01 Order kembali PERTAMA: pelanggan baru saja balik order kedua kalinya \u2014 cermin
        // _repeat_order_prompt.blade.php di web.
        if (getIntent().getBooleanExtra(EXTRA_FIRST_REPEAT_ORDER, false)) {
            boolean prioritized = getIntent().getBooleanExtra(EXTRA_FIRST_REPEAT_PRIORITIZED, false);
            String repeatCustName = getIntent().getStringExtra(EXTRA_CUSTOMER_NAME);
            String repeatMsg = (repeatCustName != null && !repeatCustName.isEmpty() ? repeatCustName : "Pelanggan")
                    + " baru saja balik order \u2014 ini pembelian KEDUA mereka sejak bergabung. "
                    + "Tanda retensi yang bagus, layak diprioritaskan."
                    + (prioritized
                        ? "\n\n\u2B50 Order ini otomatis ditandai PRIORITAS \u2014 akan tampil paling atas di antrian delivery."
                        : "");
            new AlertDialog.Builder(this)
                    .setTitle("\uD83D\uDD01 Order Kembali Pertama")
                    .setMessage(repeatMsg)
                    .setPositiveButton("OK", null)
                    .setCancelable(false)
                    .show();
        }
        // \uD83C\uDF81 Bonus "Beli N Gratis 1" terpenuhi \u2014 cermin _repeat_order_prompt / popup web.
        String bonusGranted = getIntent().getStringExtra(EXTRA_PRODUCT_BONUS_GRANTED);
        if (bonusGranted != null && !bonusGranted.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("\uD83C\uDF81 Bonus Terpenuhi!")
                    .setMessage("Pelanggan berhak dapat GRATIS: " + bonusGranted
                            + "\n\nSudah otomatis ditambahkan ke transaksi ini \u2014 jangan lupa serahkan galonnya.")
                    .setPositiveButton("OK", null)
                    .setCancelable(false)
                    .show();
        }

        findViewById(R.id.btnPrint).setOnClickListener(v -> printReceipt());
        btnShare.setOnClickListener(v -> shareReceipt());

        // Tombol akhir. Untuk struk penjualan baru (deferCustomerSend): kirim struk + link
        // lacak ke pelanggan via WhatsApp, lalu kembali ke Beranda (struk ini di atas Beranda,
        // jadi cukup finish()). Selain itu: langsung ke Beranda ("Selesai").
        final boolean sendOnDone = deferCustomerSend;
        findViewById(R.id.btnDone).setOnClickListener(v -> {
            if (sendOnDone) {
                sendStrukWithTracking();
                finish();
            } else {
                goMain();
            }
        });

        // Save trxId for menu delete action
        this.loadedTransactionId = trxId;
        View btnExportWa = findViewById(R.id.btnExportWa);
        if (btnExportWa != null) {
            btnExportWa.setOnClickListener(v -> exportToWhatsApp());
        }
    }

    private void exportToWhatsApp() {
        // Struk dikirim sebagai GAMBAR (PNG) saja — bukan teks.
        final Uri uri = prepareReceiptImageUri();
        if (uri == null) {
            Toast.makeText(this, "Gambar struk belum siap", Toast.LENGTH_SHORT).show();
            return;
        }
        String waPackage = pickWaPackage();
        if (waPackage == null) {
            Toast.makeText(this, "WhatsApp tidak terpasang", Toast.LENGTH_SHORT).show();
            return;
        }

        // Direct component launch to WA's image ContactPicker — bypasses Samsung Freecess
        // which silently drops setPackage-based startActivity on frozen apps.
        android.content.pm.PackageManager pm = getPackageManager();
        android.content.pm.ResolveInfo info;
        {
            Intent probe = new Intent(Intent.ACTION_SEND);
            probe.setType("image/png");
            probe.setPackage(waPackage);
            info = pm.resolveActivity(probe, 0);
        }

        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("image/png");
        send.putExtra(Intent.EXTRA_STREAM, uri);
        send.setClipData(android.content.ClipData.newRawUri("", uri));
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        if (info != null) {
            send.setComponent(new android.content.ComponentName(
                    info.activityInfo.packageName, info.activityInfo.name));
            send.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(send);
                return;
            } catch (Exception e) {
                android.util.Log.e("DAMIU", "direct component launch failed", e);
            }
        }

        // Fallback: system chooser (gambar)
        send.setComponent(null);
        send.setPackage(null);
        try {
            startActivity(Intent.createChooser(send, "Bagikan Struk via"));
        } catch (Exception e) {
            Toast.makeText(this, "Gagal membuka aplikasi share: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void hydrateFromTransaction(long id) {
        TransactionDao dao = new TransactionDao(DatabaseHelper.getInstance(this));
        com.crowja.damiupos.model.Transaction t = dao.getById(id);
        if (t == null) {
            Toast.makeText(this, "Transaksi tidak ditemukan", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        Intent i = getIntent();
        i.putExtra(EXTRA_CUSTOMER_NAME, t.getCustomerName());
        i.putExtra(EXTRA_CUSTOMER_ID, t.getCustomerId());   // re-share dari struk tersimpan tetap bawa kampanye
        // Gift yang di-klaim transaksi ini → struk yang dibuka ulang tetap menampilkan hadiahnya.
        i.putExtra(EXTRA_GIFT_TRX_UUID, dao.getSyncUuidById(id));
        i.putExtra(EXTRA_RECEIPT_NO, t.getReceiptNo());
        // Lookup phone
        com.crowja.damiupos.db.CustomerDao cDao = new com.crowja.damiupos.db.CustomerDao(DatabaseHelper.getInstance(this));
        com.crowja.damiupos.model.Customer c = cDao.getById(t.getCustomerId());
        if (c != null) i.putExtra(EXTRA_CUSTOMER_PHONE, c.getPhone());
        i.putExtra(EXTRA_PRODUCT_NAME, t.getProductName());
        // Pelanggan baru: record pelanggan didaftarkan di hari yang sama dengan transaksi ini
        // (pelanggan baru / walk-in baru). Pelanggan lama yang baru pertama beli TIDAK ditandai.
        i.putExtra(EXTRA_IS_NEW_CUSTOMER, dao.isNewCustomerOnDay(t.getCustomerId(), t.getTanggal()));
        i.putExtra(EXTRA_JUMLAH, t.getJumlahGalon());
        i.putExtra(EXTRA_HARGA_PER_GALON, t.getHargaPerGalon());
        i.putExtra(EXTRA_ONGKIR, t.getOngkir());
        i.putExtra(EXTRA_ONGKIR_TYPE, t.getOngkirType());
        i.putExtra(EXTRA_HARGA_BOTOL, t.getHargaBotolGalon());
        i.putExtra(EXTRA_OWNERSHIP, t.getGalonOwnership());
        i.putExtra(EXTRA_TOTAL_HARGA, t.getTotalHarga());
        i.putExtra(EXTRA_CATATAN, t.getCatatan());
        // Token link lacak → struk yang dibuka ulang (mis. kirim ulang dari Antrian Delivery)
        // tetap menampilkan QR + link tracking di gambar.
        if (t.getDeliveryToken() != null && !t.getDeliveryToken().isEmpty()) {
            i.putExtra(EXTRA_DELIVERY_TOKEN, t.getDeliveryToken());
        }
        // Transaksi yang dibuat di Web Dashboard: pada struk, kolom catatan cukup tampilkan asal +
        // waktu pembuatan (bukan rincian komposit dari web). Penanda "dibuat di Web" disisipkan
        // server saat menyusun catatan. Catatan pencairan komisi (di bawah) tetap utuh.
        if (t.getCatatan() != null && t.getCatatan().contains("dibuat di Web")) {
            i.putExtra(EXTRA_CATATAN, "Transaksi dibuat di DAMIU Web Dashboard " + trxTime(t.getTanggal()));
        }
        if (t.getPaymentMethod() != null) i.putExtra(EXTRA_PAYMENT_METHOD, t.getPaymentMethod());
        if (t.getItems() != null && !t.getItems().isEmpty()) {
            i.putExtra(EXTRA_ITEMS_JSON, TransactionItem.listToJson(t.getItems()));
        }
        // Returned empties recorded with this sale (the "Tukar botol galon" KEMBALI row(s)
        // created for the same customer at the same time) — shown on the struk.
        if (com.crowja.damiupos.model.Transaction.TYPE_JUAL.equals(t.getType())) {
            int kembali = dao.getReturnedGalonForSale(t.getCustomerId(), t.getTanggal());
            if (kembali > 0) i.putExtra(EXTRA_JUMLAH_KEMBALI, kembali);
        }
        // Detect ganti rugi KEMBALI: catatan starts with "[GANTI RUGI N galon rusak]"
        if (com.crowja.damiupos.model.Transaction.TYPE_KEMBALI.equals(t.getType())
                && t.getTotalHarga() > 0) {
            i.putExtra(EXTRA_IS_GANTI_RUGI, true);
            i.putExtra(EXTRA_GANTI_RUGI_KEMBALI, t.getJumlahGalon());
            int rusak = t.getJumlahGalon();
            String cat = t.getCatatan();
            if (cat != null) {
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("\\[GANTI RUGI (\\d+) galon rusak\\]").matcher(cat);
                if (m.find()) {
                    try { rusak = Integer.parseInt(m.group(1)); } catch (Exception ignored) {}
                }
            }
            i.putExtra(EXTRA_JUMLAH, rusak);
            i.putExtra(EXTRA_PRODUCT_NAME, "Ganti Rugi Galon Rusak");
        }
        // Pencairan komisi (AIR) — marker di catatan → render struk variant payout.
        if (t.getCatatan() != null && t.getCatatan().contains("[PENCAIRAN KOMISI]")) {
            i.putExtra(EXTRA_IS_KOMISI_PAYOUT, true);
            i.putExtra(EXTRA_RESELLER_NAME, t.getCustomerName());
            i.putExtra(EXTRA_PAYOUT_TYPE, "AIR");
            i.putExtra(EXTRA_PAYOUT_GALON, t.getJumlahGalon());
            double total = 0;
            java.util.regex.Matcher mm = java.util.regex.Pattern
                    .compile("nilai Rp ([0-9.]+)").matcher(t.getCatatan());
            if (mm.find()) {
                try { total = Double.parseDouble(mm.group(1).replace(".", "")); } catch (Exception ignored) {}
            }
            i.putExtra(EXTRA_PAYOUT_AMOUNT, total);
            if (t.getJumlahGalon() > 0) i.putExtra(EXTRA_PAYOUT_NILAI, total / t.getJumlahGalon());
            i.putExtra(EXTRA_CATATAN, t.getCatatan());
        }
        // JUAL yang dibayar dari saldo komisi (marker "[SALDO KOMISI Rp X]") → rincian potongan
        // di struk saat di-review ulang.
        if (t.getCatatan() != null && t.getCatatan().contains("[SALDO KOMISI")) {
            java.util.regex.Matcher ms = java.util.regex.Pattern
                    .compile("\\[SALDO KOMISI Rp ([0-9.]+)\\]").matcher(t.getCatatan());
            if (ms.find()) {
                try {
                    double dip = Double.parseDouble(ms.group(1).replace(".", ""));
                    if (dip > 0) i.putExtra(EXTRA_SALDO_DIPOTONG, dip);
                } catch (Exception ignored) {}
            }
        }
        // Potongan SALDO REFUND: dibaca dari BUKU BESAR lewat uuid transaksi (bukan penanda
        // catatan — penanda bisa hilang bila catatan diedit). Fallback ke penanda untuk baris
        // yang buku besarnya belum sempat tersinkron ke perangkat ini.
        double refundDip = 0;
        String trxUuidForRefund = dao.getSyncUuidById(t.getId());
        if (trxUuidForRefund != null && !trxUuidForRefund.isEmpty()) {
            refundDip = new com.crowja.damiupos.db.CustomerRefundDao(DatabaseHelper.getInstance(this))
                    .usedForTransaction(trxUuidForRefund);
        }
        if (refundDip <= 0 && t.getCatatan() != null && t.getCatatan().contains("[REFUND")) {
            java.util.regex.Matcher mr = java.util.regex.Pattern
                    .compile("\\[REFUND Rp ([0-9.]+)\\]").matcher(t.getCatatan());
            if (mr.find()) {
                try { refundDip = Double.parseDouble(mr.group(1).replace(".", "")); } catch (Exception ignored) {}
            }
        }
        if (refundDip > 0) i.putExtra(EXTRA_REFUND_DIPOTONG, refundDip);

        // Points info recalculated from DB at view time (no history-cross detection)
        SettingsDao s = new SettingsDao(DatabaseHelper.getInstance(this));
        if (s.isPointsEnabled() && com.crowja.damiupos.model.Transaction.TYPE_JUAL.equals(t.getType())) {
            double ppa = s.getPointsPerAmount();
            int reward = s.getPointsRewardThreshold();
            // Exclude biaya beli botol galon dari dasar perhitungan poin
            double trxBasis = t.getTotalHarga() - (t.getHargaBotolGalon() * t.getJumlahGalon());
            if (trxBasis < 0) trxBasis = 0;
            int pointsEarned = (int) Math.floor(trxBasis / ppa);
            double lifetime = dao.getTotalJualPointsBasisByCustomer(t.getCustomerId());
            int totalPoints = (int) Math.floor(lifetime / ppa);
            i.putExtra(EXTRA_POINTS_ENABLED, true);
            i.putExtra(EXTRA_POINTS_EARNED, pointsEarned);
            i.putExtra(EXTRA_POINTS_TOTAL, totalPoints);
            i.putExtra(EXTRA_POINTS_REWARD, reward);
        }
        // Override toolbar title to indicate view mode
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("Struk Transaksi");
        }
    }

    /** Format `tanggal` transaksi → "dd/MM/yyyy HH:mm" untuk catatan struk Web. Toleran terhadap
     *  format tersimpan ("yyyy-MM-dd HH:mm:ss" lokal atau "yyyy-MM-ddTHH:mm:ssZ" ISO); ditampilkan
     *  apa adanya (tanpa konversi zona). Kosong/format tak dikenal → string asal. */
    private static String trxTime(String ts) {
        if (ts == null || ts.length() < 16) return ts == null ? "" : ts;
        try {
            String date = ts.substring(0, 10);     // yyyy-MM-dd
            String hm = ts.substring(11, 16);      // HH:mm (pemisah ' ' atau 'T')
            return date.substring(8, 10) + "/" + date.substring(5, 7) + "/" + date.substring(0, 4) + " " + hm;
        } catch (Exception e) {
            return ts;
        }
    }

    // ============================ Struk gambar modern ============================

    /** Isi kartu struk modern (di-capture jadi PNG) dari extras yang sama dengan versi
     *  teks. Menangani 3 varian: Penjualan (JUAL), Ganti Rugi, dan Pencairan Komisi. */
    /** Isi tab "Teks" — preview pesan WA LENGKAP (rincian + kampanye aktif), sama persis dengan
     *  yang benar-benar terkirim lewat {@link #sendStrukWithTracking()}. */
    private void populateStrukTeksPreview() {
        TextView tv = findViewById(R.id.tvStrukTeks);
        if (tv == null) return;
        tv.setText(composeTrackingCaption(getIntent().getStringExtra(EXTRA_CUSTOMER_NAME)));
        // Jawaban server bisa tiba setelah layar tergambar → gambar ulang preview supaya yang
        // dilihat staf identik dengan yang akan terkirim.
        if (!serverCampaignsSettled) {
            withServerCampaigns(this::populateStrukTeksPreview);
        }
    }

    /** Toggle tab "Teks" ⇄ "Gambar" di atas struk — default Teks (lihat activity_receipt.xml). */
    private void wireStrukViewToggle() {
        View cardTeks = findViewById(R.id.cardStrukTeks);
        View cardGambar = findViewById(R.id.cardStrukGambar);
        com.google.android.material.button.MaterialButtonToggleGroup toggle = findViewById(R.id.strukViewToggle);
        if (toggle == null || cardTeks == null || cardGambar == null) return;
        toggle.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            boolean showTeks = checkedId == R.id.btnStrukTeks;
            cardTeks.setVisibility(showTeks ? View.VISIBLE : View.GONE);
            cardGambar.setVisibility(showTeks ? View.GONE : View.VISIBLE);
        });
        toggle.check(R.id.btnStrukTeks);
    }

    private void populateReceiptCard() {
        if (receiptCard == null) return;
        Intent in = getIntent();
        NumberFormat nf = NumberFormat.getInstance(new Locale("id", "ID"));
        int dotColor = getResources().getColor(R.color.primary);
        int greyDot = getResources().getColor(R.color.text_secondary);

        boolean isPayout = in.getBooleanExtra(EXTRA_IS_KOMISI_PAYOUT, false);
        boolean isGantiRugi = in.getBooleanExtra(EXTRA_IS_GANTI_RUGI, false);

        // --- Header depot ---
        SettingsDao settingsDao = new SettingsDao(DatabaseHelper.getInstance(this));
        String depotName = settingsDao.getDepotName();
        String depotAddr = settingsDao.getDepotAddress();
        String depotPhone = settingsDao.getDepotPhone();
        cardText(R.id.rcDepotName, depotName != null && !depotName.isEmpty() ? depotName : "DAMIU POS");
        cardShow(R.id.rcDepotAddr, depotAddr != null && !depotAddr.isEmpty()
                ? depotAddr.replaceAll("\\s*\\r?\\n\\s*", ", ") : null);
        cardShow(R.id.rcDepotPhone, depotPhone != null && !depotPhone.isEmpty() ? "Telp " + depotPhone : null);

        // Logo depot (disinkron dari web): dipakai dari cache lokal SECARA SINKRON supaya ikut
        // ter-capture saat struk dijadikan gambar; bila ada, badge tetes-air default disembunyikan.
        final android.widget.ImageView rcLogo = receiptCard.findViewById(R.id.rcLogo);
        final View rcDefaultBadge = receiptCard.findViewById(R.id.rcDefaultBadge);
        applyLogoToCard(rcLogo, rcDefaultBadge,
                com.crowja.damiupos.util.DepotLogo.cachedBitmap(getApplicationContext()));
        // Bila cache masih dingin (mis. logo baru di-set), unduh di background lalu SEGARKAN kartu —
        // supaya struk yang sedang dibuka pun ikut menampilkan logo begitu unduhan selesai.
        new Thread(() -> {
            if (com.crowja.damiupos.util.DepotLogo.ensureDownloaded(getApplicationContext())) {
                final android.graphics.Bitmap bmp = com.crowja.damiupos.util.DepotLogo.cachedBitmap(getApplicationContext());
                runOnUiThread(() -> applyLogoToCard(rcLogo, rcDefaultBadge, bmp));
            }
        }).start();

        cardText(R.id.rcTypeChip, isPayout ? "Pencairan Komisi" : (isGantiRugi ? "Ganti Rugi" : "Penjualan"));

        // --- Meta tanggal + nomor ---
        cardText(R.id.rcMetaDate, new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.US).format(new Date()));
        long trxId = in.getLongExtra(EXTRA_TRANSACTION_ID, -1);
        // ID transaksi unik (App\Support\ReceiptNumber) WAJIB dicetak bila ada; baris lama sebelum
        // fitur ini (tanpa receipt_no) jatuh ke nomor urut harian lama.
        String cardReceiptNo = in.getStringExtra(EXTRA_RECEIPT_NO);
        if (cardReceiptNo != null && !cardReceiptNo.isEmpty()) {
            cardShow(R.id.rcMetaNo, cardReceiptNo);
        } else {
            int orderNo = trxId > 0
                    ? new TransactionDao(DatabaseHelper.getInstance(this)).dailyOrderNo(trxId) : 0;
            cardShow(R.id.rcMetaNo, orderNo > 0 ? "No. " + orderNo : null);
        }

        // --- Pelanggan / Reseller ---
        String custName = isPayout ? in.getStringExtra(EXTRA_RESELLER_NAME) : in.getStringExtra(EXTRA_CUSTOMER_NAME);
        if (custName == null || custName.isEmpty()) custName = in.getStringExtra(EXTRA_CUSTOMER_NAME);
        String custPhone = in.getStringExtra(EXTRA_CUSTOMER_PHONE);
        View custRow = receiptCard.findViewById(R.id.rcCustomerRow);
        boolean isNewCustomer = !isPayout && in.getBooleanExtra(EXTRA_IS_NEW_CUSTOMER, false);
        if (custName != null && !custName.isEmpty()) {
            cardText(R.id.rcCustName, custName);
            cardText(R.id.rcAvatar, initials(custName));
            cardShow(R.id.rcCustPhone, (!isPayout && custPhone != null && !custPhone.isEmpty()) ? custPhone : null);
            View newBadge = receiptCard.findViewById(R.id.rcNewBadge);
            if (newBadge != null) newBadge.setVisibility(isNewCustomer ? View.VISIBLE : View.GONE);
            custRow.setVisibility(View.VISIBLE);
        } else {
            custRow.setVisibility(View.GONE);
        }

        LinearLayout items = receiptCard.findViewById(R.id.rcItems);
        items.removeAllViews();
        double totalHarga = in.getDoubleExtra(EXTRA_TOTAL_HARGA, 0);

        if (isPayout) {
            boolean air = "AIR".equals(in.getStringExtra(EXTRA_PAYOUT_TYPE));
            int galon = in.getIntExtra(EXTRA_PAYOUT_GALON, 0);
            double nilai = in.getDoubleExtra(EXTRA_PAYOUT_NILAI, 0);
            double amount = in.getDoubleExtra(EXTRA_PAYOUT_AMOUNT, 0);
            double before = in.getDoubleExtra(EXTRA_SALDO_BEFORE, -1);
            double after = in.getDoubleExtra(EXTRA_SALDO_AFTER, -1);
            if (air) {
                addCardRow(items, "Pencairan air minum",
                        galon + " galon × Rp " + nf.format(Math.round(nilai)), null, dotColor);
            } else {
                addCardRow(items, "Pencairan uang tunai", null, null, dotColor);
            }
            if (before >= 0) addCardRow(items, "Saldo sebelum", null, "Rp " + nf.format(Math.round(before)), null);
            if (after >= 0) addCardRow(items, "Saldo sesudah", null, "Rp " + nf.format(Math.round(after)), null);
            cardText(R.id.rcTotalValue, "Rp " + nf.format(Math.round(amount)));
            hide(R.id.rcKembaliRow); hide(R.id.rcSaldoBox); hide(R.id.rcPaymentRow); hide(R.id.rcPointsBox);
            cardNotes(stripMarkers(in.getStringExtra(EXTRA_CATATAN)));
            return;
        }

        int totalGalon = 0;
        if (isGantiRugi) {
            int rusak = in.getIntExtra(EXTRA_JUMLAH, 0);
            double hargaGR = in.getDoubleExtra(EXTRA_HARGA_PER_GALON, 0);
            String pname = in.getStringExtra(EXTRA_PRODUCT_NAME);
            addCardRow(items, pname != null && !pname.isEmpty() ? pname : "Ganti Rugi Galon Rusak",
                    rusak + " × Rp " + nf.format(hargaGR), "Rp " + nf.format(totalHarga), dotColor);
            int kembali = in.getIntExtra(EXTRA_GANTI_RUGI_KEMBALI, 0);
            if (kembali > 0) addCardRow(items, "Galon kembali", null, kembali + " galon", null);
            hide(R.id.rcKembaliRow); hide(R.id.rcSaldoBox); hide(R.id.rcPointsBox);
        } else {
            String itemsJson = in.getStringExtra(EXTRA_ITEMS_JSON);
            List<TransactionItem> list = TransactionItem.listFromJson(itemsJson);
            if (list != null && !list.isEmpty()) {
                for (TransactionItem it : list) {
                    addCardRow(items, it.productName != null ? it.productName : "-",
                            it.jumlah + " × Rp " + nf.format(it.hargaPerGalon),
                            "Rp " + nf.format(it.getSubtotal()), dotColor);
                    totalGalon += it.jumlah;
                }
            } else {
                String pname = in.getStringExtra(EXTRA_PRODUCT_NAME);
                int jumlah = in.getIntExtra(EXTRA_JUMLAH, 0);
                double harga = in.getDoubleExtra(EXTRA_HARGA_PER_GALON, 0);
                addCardRow(items, pname != null && !pname.isEmpty() ? pname : "Air minum",
                        jumlah + " × Rp " + nf.format(harga), "Rp " + nf.format(jumlah * harga), dotColor);
                totalGalon = jumlah;
            }
            // Ongkir
            double ongkir = in.getDoubleExtra(EXTRA_ONGKIR, 0);
            String ongkirType = in.getStringExtra(EXTRA_ONGKIR_TYPE);
            if ("per_galon".equals(ongkirType) && ongkir > 0) {
                addCardRow(items, "Ongkos kirim", totalGalon + " × Rp " + nf.format(ongkir),
                        "Rp " + nf.format(totalGalon * ongkir), greyDot);
            } else if ("borongan".equals(ongkirType) && ongkir > 0) {
                addCardRow(items, "Ongkos kirim (borongan)", null, "Rp " + nf.format(ongkir), greyDot);
            }
            // Galon kembali
            int jumlahKembali = in.getIntExtra(EXTRA_JUMLAH_KEMBALI, 0);
            if (jumlahKembali > 0) {
                cardText(R.id.rcKembaliVal, jumlahKembali + " botol");
                show(R.id.rcKembaliRow);
            } else {
                hide(R.id.rcKembaliRow);
            }
            // Potongan pembayaran: saldo komisi &/atau SALDO REFUND. Kotak rincian tampil bila
            // salah satu dipakai; "Sisa dibayar" memperhitungkan KEDUANYA.
            double saldoDipotong = in.getDoubleExtra(EXTRA_SALDO_DIPOTONG, 0);
            double refundDipotong = in.getDoubleExtra(EXTRA_REFUND_DIPOTONG, 0);
            if (saldoDipotong > 0 || refundDipotong > 0) {
                if (saldoDipotong > 0) {
                    cardText(R.id.rcSaldoDipotong, "− Rp " + nf.format(Math.round(saldoDipotong)));
                    show(R.id.rcSaldoKomisiRow);
                } else {
                    hide(R.id.rcSaldoKomisiRow);
                }
                if (refundDipotong > 0) {
                    cardText(R.id.rcRefundDipotong, "− Rp " + nf.format(Math.round(refundDipotong)));
                    show(R.id.rcRefundRow);
                } else {
                    hide(R.id.rcRefundRow);
                }
                cardText(R.id.rcSisaBayar, "Rp " + nf.format(
                        Math.round(Math.max(0, totalHarga - saldoDipotong - refundDipotong))));
                double saldoSisa = in.getDoubleExtra(EXTRA_SALDO_AFTER, -1);
                if (saldoDipotong > 0 && saldoSisa >= 0) {
                    cardText(R.id.rcSisaSaldo, "Rp " + nf.format(Math.round(saldoSisa)));
                    show(R.id.rcSisaSaldoRow);
                } else {
                    hide(R.id.rcSisaSaldoRow);
                }
                show(R.id.rcSaldoBox);
            } else {
                hide(R.id.rcSaldoBox);
            }
            // Poin
            if (in.getBooleanExtra(EXTRA_POINTS_ENABLED, false)) {
                int earned = in.getIntExtra(EXTRA_POINTS_EARNED, 0);
                int total = in.getIntExtra(EXTRA_POINTS_TOTAL, 0);
                int reward = in.getIntExtra(EXTRA_POINTS_REWARD, 0);
                cardText(R.id.rcPointsEarned, "+" + earned);
                cardText(R.id.rcPointsTotal, String.valueOf(total));
                if (reward > 0) {
                    int remaining = reward - (total % reward);
                    cardText(R.id.rcPointsNext, remaining + " poin lagi");
                    show(R.id.rcPointsNextRow);
                } else {
                    hide(R.id.rcPointsNextRow);
                }
                show(R.id.rcPointsBox);
            } else {
                hide(R.id.rcPointsBox);
            }
        }

        cardText(R.id.rcTotalValue, "Rp " + nf.format(totalHarga));

        // Metode pembayaran
        String payLabel = paymentLabel(in.getStringExtra(EXTRA_PAYMENT_METHOD));
        if (!payLabel.isEmpty()) {
            cardText(R.id.rcPaymentPill, payLabel);
            show(R.id.rcPaymentRow);
        } else {
            hide(R.id.rcPaymentRow);
        }

        cardNotes(stripMarkers(in.getStringExtra(EXTRA_CATATAN)));
        renderGiftBox();
        maybeOfferProductGift();
        // Link tracking TIDAK ditanam di gambar — dikirim sebagai pesan teks terpisah di WA
        // (EXTRA_TEXT) supaya gambar struk tetap bersih dan link-nya bisa diklik.
    }

    /** Gift yang di-klaim transaksi ini → kotak amber "🎁 GIFT UNTUK ANDA" + alasan pada gambar struk. */
    /** Popup gift produk hanya sekali per layar struk (renderGiftBox bisa dipanggil ulang). */
    private boolean giftOffered = false;

    /**
     * Sesaat setelah transaksi jadi: kalau pelanggan punya gift PRODUK yang belum diberikan,
     * tanyakan wujudnya — tambah sebagai baris Rp 0 (produknya boleh ditukar ke yang benar-benar
     * dibeli), atau gratiskan sebagian item yang dibeli. Menutup popup = gift tetap pending dan
     * ditawarkan lagi di transaksi berikutnya. Cermin popup yang sama di dashboard web.
     */
    private void maybeOfferProductGift() {
        if (giftOffered) return;
        final long trxId = getIntent().getLongExtra(EXTRA_GIFT_TRX_ID, -1);
        final String trxUuid = getIntent().getStringExtra(EXTRA_GIFT_TRX_UUID);
        final long custId = getIntent().getLongExtra(EXTRA_CUSTOMER_ID, -1);
        if (trxId <= 0 || trxUuid == null || trxUuid.isEmpty() || custId <= 0) return;

        final com.crowja.damiupos.db.CustomerGiftDao giftDao =
                new com.crowja.damiupos.db.CustomerGiftDao(DatabaseHelper.getInstance(this));
        java.util.List<com.crowja.damiupos.db.CustomerGiftDao.Gift> gifts =
                giftDao.pendingProductForCustomer(custId);
        if (gifts.isEmpty()) return;
        giftOffered = true;

        final com.crowja.damiupos.db.CustomerGiftDao.Gift gift = gifts.get(0);
        final java.util.List<TransactionItem> items = new java.util.ArrayList<>(
                TransactionItem.listFromJson(getIntent().getStringExtra(EXTRA_ITEMS_JSON)));

        // Satu daftar pilihan: tambah (nama gift) → tambah (tukar ke tiap item dibeli) → gratiskan
        // tiap baris yang jumlahnya cukup. Indeksnya dipetakan lewat dua list paralel di bawah.
        final java.util.List<String> labels = new java.util.ArrayList<>();
        final java.util.List<Integer> swapIdx = new java.util.ArrayList<>();   // -1 = pakai nama gift
        final java.util.List<Integer> freeIdx = new java.util.ArrayList<>();   // -1 = bukan "gratiskan"

        labels.add("➕ Tambah gratis: " + gift.qty + " pcs " + gift.itemName);
        swapIdx.add(-1);
        freeIdx.add(-1);
        for (int i = 0; i < items.size(); i++) {
            TransactionItem it = items.get(i);
            if (it.productName != null && !it.productName.equals(gift.itemName)) {
                labels.add("➕ Tambah gratis (tukar): " + gift.qty + " pcs " + it.productName);
                swapIdx.add(i);
                freeIdx.add(-1);
            }
        }
        for (int i = 0; i < items.size(); i++) {
            TransactionItem it = items.get(i);
            if (it.jumlah >= gift.qty) {
                labels.add("🏷️ Gratiskan " + gift.qty + " pcs dari " + it.productName
                        + " (total −Rp " + NumberFormat.getInstance(new Locale("id", "ID")).format(Math.round(gift.qty * it.hargaPerGalon)) + ")");
                swapIdx.add(-1);
                freeIdx.add(i);
            }
        }

        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("🎁 Pelanggan punya gift")
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    int swap = swapIdx.get(which);
                    int free = freeIdx.get(which);
                    double total = getIntent().getDoubleExtra(EXTRA_TOTAL_HARGA, 0);
                    int galon = 0;
                    for (TransactionItem it : items) galon += it.jumlah;

                    if (free >= 0) {
                        // Gratiskan: baris dipecah jadi sisa berbayar + baris Rp 0. Galon tidak
                        // bertambah (tak ada galon ekstra keluar), totalnya yang berkurang.
                        TransactionItem line = items.get(free);
                        int rest = line.jumlah - gift.qty;
                        total = Math.max(0, total - (gift.qty * line.hargaPerGalon));
                        if (rest > 0) {
                            items.set(free, new TransactionItem(line.productId, line.productName, rest, line.hargaPerGalon));
                        } else {
                            items.remove(free);
                        }
                        items.add(new TransactionItem(line.productId, line.productName, gift.qty, 0));
                    } else {
                        // Tambah: galon fisik memang keluar → jumlah galon bertambah, total tetap.
                        String name = swap >= 0 ? items.get(swap).productName : gift.itemName;
                        long pid = swap >= 0 ? items.get(swap).productId : 0;
                        items.add(new TransactionItem(pid, name, gift.qty, 0));
                        galon += gift.qty;
                    }

                    new TransactionDao(DatabaseHelper.getInstance(this))
                            .applyGiftItems(trxId, items, total, galon);
                    giftDao.redeemOne(gift.localId, trxUuid,
                            new com.crowja.damiupos.db.SettingsDao(DatabaseHelper.getInstance(this))
                                    .getCurrentUserName());

                    // Struk digambar ulang dari extras → perbarui dulu, lalu susun ulang layar ini.
                    getIntent().putExtra(EXTRA_ITEMS_JSON, TransactionItem.listToJson(items));
                    getIntent().putExtra(EXTRA_TOTAL_HARGA, total);
                    com.crowja.damiupos.sync.SyncScheduler.syncNow(getApplicationContext());
                    // Gambar ulang di tempat, BUKAN recreate(): onCreate menjadwalkan iklan
                    // interstitial, jadi menyusun ulang activity akan memunculkan iklan kedua.
                    // populateReceiptCard() mengosongkan kontainer item lebih dulu, jadi aman
                    // dipanggil berulang.
                    receiptText = buildReceipt();
                    populateReceiptCard();
                    populateStrukTeksPreview();
                })
                .setNegativeButton("Nanti saja", null)   // gift tetap pending
                .show();
    }

    private void renderGiftBox() {
        View box = receiptCard.findViewById(R.id.rcGiftBox);
        LinearLayout items = receiptCard.findViewById(R.id.rcGiftItems);
        if (box == null || items == null) return;
        java.util.List<com.crowja.damiupos.db.CustomerGiftDao.Gift> gifts = redeemedGifts();
        items.removeAllViews();
        if (gifts.isEmpty()) { box.setVisibility(View.GONE); return; }
        for (com.crowja.damiupos.db.CustomerGiftDao.Gift g : gifts) {
            TextView label = new TextView(this);
            label.setText(g.label());
            label.setTextColor(0xFF78350F);
            label.setTextSize(13);
            label.setTypeface(label.getTypeface(), android.graphics.Typeface.BOLD);
            label.setPadding(0, dp(3), 0, 0);
            items.addView(label);
            if (g.reason != null && !g.reason.isEmpty()) {
                TextView reason = new TextView(this);
                reason.setText("Alasan: " + g.reason);
                reason.setTextColor(0xFF92400E);
                reason.setTextSize(11);
                items.addView(reason);
            }
        }
        box.setVisibility(View.VISIBLE);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** Gift di-klaim (redeemed) oleh transaksi struk ini — kosong bila tak ada / uuid tak diketahui. */
    private java.util.List<com.crowja.damiupos.db.CustomerGiftDao.Gift> redeemedGifts() {
        String trxUuid = getIntent().getStringExtra(EXTRA_GIFT_TRX_UUID);
        if (trxUuid == null || trxUuid.isEmpty()) return new java.util.ArrayList<>();
        return new com.crowja.damiupos.db.CustomerGiftDao(DatabaseHelper.getInstance(this))
                .redeemedForTransaction(trxUuid);
    }

    // ----- helper kartu modern -----
    private void cardText(int id, String s) {
        TextView t = receiptCard.findViewById(id);
        if (t != null) t.setText(s);
    }

    private void cardShow(int id, String s) {
        TextView t = receiptCard.findViewById(id);
        if (t == null) return;
        if (s != null && !s.isEmpty()) { t.setText(s); t.setVisibility(View.VISIBLE); }
        else t.setVisibility(View.GONE);
    }

    private void show(int id) { View v = receiptCard.findViewById(id); if (v != null) v.setVisibility(View.VISIBLE); }
    private void hide(int id) { View v = receiptCard.findViewById(id); if (v != null) v.setVisibility(View.GONE); }

    private void cardNotes(String note) {
        cardShow(R.id.rcNotes, note != null && !note.isEmpty() ? "Catatan: " + note : null);
    }

    /** Tampilkan logo depot (disinkron dari web) di kartu struk; bila ada, sembunyikan badge
     *  tetes-air default. Bisa dipanggil ulang dari UI thread saat unduhan logo selesai. */
    private void applyLogoToCard(android.widget.ImageView rcLogo, View rcDefaultBadge, android.graphics.Bitmap logo) {
        if (rcLogo == null) return;
        if (logo != null) {
            rcLogo.setImageBitmap(logo);
            rcLogo.setVisibility(View.VISIBLE);
            if (rcDefaultBadge != null) rcDefaultBadge.setVisibility(View.GONE);
        } else {
            rcLogo.setVisibility(View.GONE);
            if (rcDefaultBadge != null) rcDefaultBadge.setVisibility(View.VISIBLE);
        }
    }

    private void addCardRow(LinearLayout container, String name, String sub, String amount, Integer dotColor) {
        View row = getLayoutInflater().inflate(R.layout.receipt_image_item, container, false);
        TextView dot = row.findViewById(R.id.rcItemDot);
        if (dotColor != null) dot.setTextColor(dotColor); else dot.setVisibility(View.GONE);
        ((TextView) row.findViewById(R.id.rcItemName)).setText(name);
        TextView subv = row.findViewById(R.id.rcItemSub);
        if (sub != null && !sub.isEmpty()) subv.setText(sub); else subv.setVisibility(View.GONE);
        TextView amt = row.findViewById(R.id.rcItemAmount);
        if (amount != null && !amount.isEmpty()) amt.setText(amount); else amt.setVisibility(View.GONE);
        container.addView(row);
    }

    private static String stripMarkers(String catatan) {
        if (catatan == null) return "";
        return catatan.replaceAll("\\[(SALDO KOMISI|REFUND) Rp [0-9.]+\\]", "")
                .replace("[PENCAIRAN KOMISI]", "").trim();
    }

    /**
     * Catatan transaksi yang LAYAK dibaca PELANGGAN (untuk pesan WA). Kolom catatan dipakai ganda:
     * catatan manual staf SEKALIGUS penanda pembukuan internal — penanda dibuang supaya pelanggan
     * tak menerima "[PENCAIRAN KOMISI]" / "[SALDO KOMISI Rp 50.000]" di struknya. Cermin
     * App\Support\StrukWa::customerNote di web; ubah keduanya bersamaan.
     */
    static String customerNote(String catatan) {
        if (catatan == null) return "";
        String s = catatan;
        // Transaksi yang dibuat di WEB menyimpan JEJAK AUDIT komposit di kolom ini (nomor HP
        // pelanggan, nama pembuat, salinan seluruh rincian struk) dengan catatan manusia menempel
        // di ekor sebagai "Catatan: …". Kirim HANYA ekornya; tanpa ekor → tak ada yang layak tampil.
        int web = s.toLowerCase(java.util.Locale.ROOT).indexOf("dibuat di web");
        if (web >= 0) {
            int p = s.toLowerCase(java.util.Locale.ROOT).lastIndexOf("catatan:");
            if (p < 0) return "";
            s = s.substring(p + "catatan:".length());
        }
        s = s.replaceAll(
                "(?i)\\[(SALDO KOMISI|REFUND|GANTI RUGI|PENCAIRAN KOMISI|JUAL BOTOL KOSONG|PROMOSI|TARIK GALON PROMOSI|ORDER ONLINE|BAYAR HUTANG|CASH BON|BAYAR SEBAGIAN)[^\\]]*\\]", " ");
        s = s.replaceAll("\\s+", " ");
        // Sisa pemisah menggantung setelah penanda dibuang ("· catatan" / "- catatan").
        s = s.replaceAll("^[\\s·\\-–—,;:]+", "")
             .replaceAll("[\\s·\\-–—,;:]+$", "");
        return s.trim();
    }

    private static String initials(String name) {
        if (name == null) return "?";
        StringBuilder s = new StringBuilder();
        for (String p : name.trim().split("\\s+")) {
            if (!p.isEmpty()) {
                s.append(Character.toUpperCase(p.charAt(0)));
                if (s.length() >= 2) break;
            }
        }
        return s.length() == 0 ? "?" : s.toString();
    }

    private String buildReceipt() {
        if (getIntent().getBooleanExtra(EXTRA_IS_KOMISI_PAYOUT, false)) {
            return buildPayoutReceipt();
        }
        String customerName = getIntent().getStringExtra(EXTRA_CUSTOMER_NAME);
        String customerPhone = getIntent().getStringExtra(EXTRA_CUSTOMER_PHONE);
        String productName = getIntent().getStringExtra(EXTRA_PRODUCT_NAME);
        int jumlah = getIntent().getIntExtra(EXTRA_JUMLAH, 0);
        double hargaPerGalon = getIntent().getDoubleExtra(EXTRA_HARGA_PER_GALON, 0);
        String itemsJson = getIntent().getStringExtra(EXTRA_ITEMS_JSON);
        List<TransactionItem> items = TransactionItem.listFromJson(itemsJson);
        double ongkir = getIntent().getDoubleExtra(EXTRA_ONGKIR, 0);
        String ongkirType = getIntent().getStringExtra(EXTRA_ONGKIR_TYPE);
        if (ongkirType == null) ongkirType = "per_galon";
        double totalHarga = getIntent().getDoubleExtra(EXTRA_TOTAL_HARGA, 0);
        String catatan = getIntent().getStringExtra(EXTRA_CATATAN);
        // Marker potongan saldo komisi punya baris rincian sendiri di bawah TOTAL,
        // jadi jangan ikut tercetak mentah di baris "Catatan:".
        if (catatan != null) {
            catatan = catatan.replaceAll("\\[(SALDO KOMISI|REFUND) Rp [0-9.]+\\]", "").trim();
        }

        NumberFormat nf = NumberFormat.getInstance(new Locale("id", "ID"));
        String tanggal = new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.US).format(new Date());

        SettingsDao settingsDao = new SettingsDao(DatabaseHelper.getInstance(this));
        String depotName = settingsDao.getDepotName();
        String depotAddress = settingsDao.getDepotAddress();
        String depotPhone = settingsDao.getDepotPhone();

        StringBuilder sb = new StringBuilder();

        // Header
        if (depotName != null && !depotName.isEmpty()) {
            sb.append(centerBlock(depotName.toUpperCase(Locale.getDefault()))).append("\n");
        } else {
            sb.append(center("DAMIU POS")).append("\n");
        }
        if (depotAddress != null && !depotAddress.isEmpty()) {
            for (String ln : depotAddress.split("\\r?\\n")) {
                sb.append(centerBlock(ln)).append("\n");
            }
        } else {
            sb.append(center("Depot Air Minum Isi Ulang")).append("\n");
        }
        if (depotPhone != null && !depotPhone.isEmpty()) {
            sb.append(centerBlock("HP: " + depotPhone)).append("\n");
        }
        sb.append(line('=')).append("\n");

        // Date
        sb.append("Tgl: ").append(tanggal).append("\n");

        boolean isGantiRugi = getIntent().getBooleanExtra(EXTRA_IS_GANTI_RUGI, false);
        if (isGantiRugi) {
            sb.append(line('-')).append("\n");
            sb.append(center("** STRUK GANTI RUGI **")).append("\n");
            sb.append(center("Galon Rusak di Pelanggan")).append("\n");
            int totalKembali = getIntent().getIntExtra(EXTRA_GANTI_RUGI_KEMBALI, 0);
            if (totalKembali > 0) {
                sb.append(leftRight("Total galon kembali", totalKembali + " galon")).append("\n");
            }
        }
        sb.append(line('-')).append("\n");

        // Customer — format "Nama (NO.HP)" kalau ada nomor
        sb.append("Pelanggan:\n");
        StringBuilder custLine = new StringBuilder("  ");
        custLine.append(customerName != null && !customerName.isEmpty()
                ? customerName : "-");
        if (customerPhone != null && !customerPhone.isEmpty()) {
            custLine.append(" (").append(customerPhone).append(")");
        }
        sb.append(wrapText(custLine.toString())).append("\n");
        // Penanda pelanggan baru (transaksi pertama pelanggan ini).
        if (getIntent().getBooleanExtra(EXTRA_IS_NEW_CUSTOMER, false)) {
            sb.append(center("** PELANGGAN BARU **")).append("\n");
        }

        sb.append(line('-')).append("\n");

        // Items — detailed breakdown
        int totalGalon = 0;
        if (items != null && !items.isEmpty()) {
            for (TransactionItem it : items) {
                sb.append(wrapText(it.productName != null ? it.productName : "-")).append("\n");
                sb.append(leftRight(
                        "  " + it.jumlah + " x Rp " + nf.format(it.hargaPerGalon),
                        "Rp " + nf.format(it.getSubtotal()))).append("\n");
                totalGalon += it.jumlah;
            }
        } else {
            // Legacy single-product fallback
            if (productName != null && !productName.isEmpty()) {
                sb.append(wrapText(productName)).append("\n");
            } else {
                sb.append("Air minum\n");
            }
            double subtotalAir = jumlah * hargaPerGalon;
            sb.append(leftRight(
                    "  " + jumlah + " x Rp " + nf.format(hargaPerGalon),
                    "Rp " + nf.format(subtotalAir))).append("\n");
            totalGalon = jumlah;
        }

        if ("per_galon".equals(ongkirType) && ongkir > 0) {
            double subtotalOngkir = totalGalon * ongkir;
            sb.append("Ongkos kirim\n");
            sb.append(leftRight(
                    "  " + totalGalon + " x Rp " + nf.format(ongkir),
                    "Rp " + nf.format(subtotalOngkir))).append("\n");
        } else if ("borongan".equals(ongkirType) && ongkir > 0) {
            sb.append("Ongkos kirim (borongan)\n");
            sb.append(leftRight("", "Rp " + nf.format(ongkir))).append("\n");
        }

        // Botol galon kosong yang ditukar/dikembalikan pelanggan pada penjualan ini.
        int jumlahKembali = getIntent().getIntExtra(EXTRA_JUMLAH_KEMBALI, 0);
        if (!isGantiRugi && jumlahKembali > 0) {
            sb.append(leftRight("Galon kembali", jumlahKembali + " botol")).append("\n");
        }

        sb.append(line('-')).append("\n");

        // Total
        sb.append(leftRight("TOTAL", "Rp " + nf.format(totalHarga))).append("\n");
        // Pencairan komisi: bagian order yang dibayar dari saldo komisi reseller.
        double saldoDipotong = getIntent().getDoubleExtra(EXTRA_SALDO_DIPOTONG, 0);
        if (saldoDipotong > 0) {
            sb.append(leftRight("Dari saldo komisi",
                    "- Rp " + nf.format(Math.round(saldoDipotong)))).append("\n");
            double sisaBayar = Math.max(0, totalHarga - saldoDipotong);
            sb.append(leftRight("Sisa dibayar", "Rp " + nf.format(Math.round(sisaBayar)))).append("\n");
            double saldoSisa = getIntent().getDoubleExtra(EXTRA_SALDO_AFTER, -1);
            if (saldoSisa >= 0) {
                sb.append(leftRight("Sisa saldo komisi",
                        "Rp " + nf.format(Math.round(saldoSisa)))).append("\n");
            }
        }
        // SALDO REFUND yang dipakai membayar order ini → pelanggan melihat potongannya sendiri
        // dan berapa yang benar-benar dibayar. TOTAL di atas sengaja tidak dikurangi.
        double refundDipotongWa = getIntent().getDoubleExtra(EXTRA_REFUND_DIPOTONG, 0);
        if (refundDipotongWa > 0) {
            sb.append(leftRight("Dari saldo refund",
                    "- Rp " + nf.format(Math.round(refundDipotongWa)))).append("\n");
            double sisaBayarRef = Math.max(0, totalHarga - saldoDipotong - refundDipotongWa);
            sb.append(leftRight("Sisa dibayar", "Rp " + nf.format(Math.round(sisaBayarRef)))).append("\n");
        }
        sb.append(line('=')).append("\n");

        // Metode pembayaran (kalau ada — transaksi JUAL)
        String payLabel = paymentLabel(getIntent().getStringExtra(EXTRA_PAYMENT_METHOD));
        if (!payLabel.isEmpty()) {
            sb.append(leftRight("Pembayaran dengan", payLabel)).append("\n");
            sb.append(line('-')).append("\n");
        }

        // Points section
        boolean pointsEnabled = getIntent().getBooleanExtra(EXTRA_POINTS_ENABLED, false);
        if (pointsEnabled) {
            int pointsEarned = getIntent().getIntExtra(EXTRA_POINTS_EARNED, 0);
            int pointsTotal = getIntent().getIntExtra(EXTRA_POINTS_TOTAL, 0);
            int reward = getIntent().getIntExtra(EXTRA_POINTS_REWARD, 0);

            boolean rewardUnlocked = getIntent().getBooleanExtra(EXTRA_REWARD_UNLOCKED, false);
            int rewardsNew = getIntent().getIntExtra(EXTRA_REWARDS_NEW_COUNT, 0);

            if (rewardUnlocked) {
                sb.append(line('*')).append("\n");
                sb.append(center("*** SELAMAT! ***")).append("\n");
                if (rewardsNew > 1) {
                    sb.append(center("Anda Mendapat " + rewardsNew + " Hadiah!")).append("\n");
                } else {
                    sb.append(center("Anda Mendapat Hadiah!")).append("\n");
                }
                sb.append(center("Klaim ke petugas kami")).append("\n");
                sb.append(line('*')).append("\n");
            }

            sb.append(center("* POIN LOYALITAS *")).append("\n");
            sb.append(leftRight("Poin transaksi ini", "+" + pointsEarned)).append("\n");
            sb.append(leftRight("Total poin Anda", String.valueOf(pointsTotal))).append("\n");
            if (reward > 0) {
                int remaining = reward - (pointsTotal % reward);
                int rewardsEarned = pointsTotal / reward;
                if (rewardsEarned > 0 && !rewardUnlocked) {
                    sb.append(center("Total " + rewardsEarned + "x hadiah terkumpul")).append("\n");
                }
                sb.append(leftRight("Poin u/ hadiah berikut", String.valueOf(remaining))).append("\n");
            }
            sb.append(line('-')).append("\n");
        }

        // Notes
        if (catatan != null && !catatan.isEmpty()) {
            sb.append(wrapText("Catatan: " + catatan)).append("\n");
            sb.append(line('-')).append("\n");
        }

        // Gift pelanggan — hadiah yang di-klaim transaksi ini (+ alasan + apresiasi).
        java.util.List<com.crowja.damiupos.db.CustomerGiftDao.Gift> gifts = redeemedGifts();
        if (!gifts.isEmpty()) {
            sb.append(center("*** GIFT UNTUK ANDA ***")).append("\n");
            for (com.crowja.damiupos.db.CustomerGiftDao.Gift g : gifts) {
                sb.append(wrapText("  " + g.label())).append("\n");
                if (g.reason != null && !g.reason.isEmpty()) {
                    sb.append(wrapText("  Alasan: " + g.reason)).append("\n");
                }
            }
            sb.append(center("Terima kasih telah menjadi")).append("\n");
            sb.append(center("pelanggan setia kami")).append("\n");
            sb.append(line('-')).append("\n");
        }

        // Footer
        sb.append(center("Terima Kasih")).append("\n");
        sb.append(center("Semoga Berkah")).append("\n");
        sb.append("\n");
        sb.append(center("POWERED BY DAMIU POS")).append("\n");
        sb.append(center("by FREZ Tech & Innovations Lab")).append("\n");
        // Info kecil: siapa yang membuat/cetak struk (user yang sedang login di perangkat).
        String generatedBy = settingsDao.getCurrentUserName();
        if (generatedBy != null && !generatedBy.trim().isEmpty()) {
            sb.append(center("Struk dibuat oleh: " + generatedBy)).append("\n");
        }

        return sb.toString();
    }

    /** Struk Pencairan Komisi reseller (AIR = galon, UANG = tunai). */
    private String buildPayoutReceipt() {
        NumberFormat nf = NumberFormat.getInstance(new Locale("id", "ID"));
        String tanggal = new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.US).format(new Date());
        SettingsDao settingsDao = new SettingsDao(DatabaseHelper.getInstance(this));
        String depotName = settingsDao.getDepotName();
        String depotAddress = settingsDao.getDepotAddress();
        String depotPhone = settingsDao.getDepotPhone();

        String reseller = getIntent().getStringExtra(EXTRA_RESELLER_NAME);
        if (reseller == null || reseller.isEmpty()) reseller = getIntent().getStringExtra(EXTRA_CUSTOMER_NAME);
        boolean air = "AIR".equals(getIntent().getStringExtra(EXTRA_PAYOUT_TYPE));
        int galon = getIntent().getIntExtra(EXTRA_PAYOUT_GALON, 0);
        double nilai = getIntent().getDoubleExtra(EXTRA_PAYOUT_NILAI, 0);
        double amount = getIntent().getDoubleExtra(EXTRA_PAYOUT_AMOUNT, 0);
        double before = getIntent().getDoubleExtra(EXTRA_SALDO_BEFORE, -1);
        double after = getIntent().getDoubleExtra(EXTRA_SALDO_AFTER, -1);
        String catatan = getIntent().getStringExtra(EXTRA_CATATAN);

        StringBuilder sb = new StringBuilder();
        sb.append(centerBlock((depotName != null && !depotName.isEmpty()
                ? depotName : "DAMIU POS").toUpperCase(Locale.getDefault()))).append("\n");
        if (depotAddress != null && !depotAddress.isEmpty()) {
            for (String ln : depotAddress.split("\\r?\\n")) sb.append(centerBlock(ln)).append("\n");
        }
        if (depotPhone != null && !depotPhone.isEmpty()) sb.append(centerBlock("HP: " + depotPhone)).append("\n");
        sb.append(line('=')).append("\n");
        sb.append(center("** STRUK PENCAIRAN KOMISI **")).append("\n");
        sb.append(line('-')).append("\n");
        sb.append("Tgl: ").append(tanggal).append("\n");
        sb.append("Reseller:\n").append(wrapText("  " + (reseller != null && !reseller.isEmpty() ? reseller : "-"))).append("\n");
        sb.append(line('-')).append("\n");
        if (air) {
            sb.append("Pencairan: Air Minum\n");
            sb.append("  ").append(galon).append(" galon x Rp ").append(nf.format(Math.round(nilai))).append("\n");
        } else {
            sb.append("Pencairan: Uang Tunai\n");
        }
        sb.append(leftRight("Total dipotong", "Rp " + nf.format(Math.round(amount)))).append("\n");
        sb.append(line('-')).append("\n");
        if (before >= 0) sb.append(leftRight("Saldo sebelum", "Rp " + nf.format(Math.round(before)))).append("\n");
        if (after >= 0) sb.append(leftRight("Saldo sesudah", "Rp " + nf.format(Math.round(after)))).append("\n");
        sb.append(line('=')).append("\n");
        if (catatan != null && !catatan.isEmpty()) {
            String c = catatan.replace("[PENCAIRAN KOMISI]", "").trim();
            // buang ringkasan nilai yang sudah tampil di atas, sisakan catatan manual setelah "—"
            int dash = c.indexOf('—');
            String manual = dash >= 0 ? c.substring(dash + 1).trim() : "";
            if (!manual.isEmpty()) {
                sb.append(wrapText("Catatan: " + manual)).append("\n");
                sb.append(line('-')).append("\n");
            }
        }
        sb.append(center("Terima Kasih")).append("\n");
        sb.append("\n");
        sb.append(center("POWERED BY DAMIU POS")).append("\n");
        sb.append(center("by FREZ Tech & Innovations Lab")).append("\n");
        String generatedBy = settingsDao.getCurrentUserName();
        if (generatedBy != null && !generatedBy.trim().isEmpty()) {
            sb.append(center("Struk dibuat oleh: " + generatedBy)).append("\n");
        }
        return sb.toString();
    }

    /** Map kode metode bayar ke label struk; "" kalau kosong/tidak dikenal. */
    private static String paymentLabel(String code) {
        if (com.crowja.damiupos.model.Transaction.PAY_TUNAI.equals(code)) return "Tunai";
        if (com.crowja.damiupos.model.Transaction.PAY_QRIS.equals(code)) return "QRIS";
        if (com.crowja.damiupos.model.Transaction.PAY_TRANSFER.equals(code)) return "Transfer";
        return "";
    }

    private String center(String text) {
        if (text.length() >= RECEIPT_WIDTH) return text;
        int pad = (RECEIPT_WIDTH - text.length()) / 2;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pad; i++) sb.append(' ');
        sb.append(text);
        return sb.toString();
    }

    private String line(char ch) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < RECEIPT_WIDTH; i++) sb.append(ch);
        return sb.toString();
    }

    private String leftRight(String left, String right) {
        if (left == null) left = "";
        if (right == null) right = "";
        // Jangan biarkan baris melebihi lebar struk → potong sisi kiri (label),
        // nilai di kanan tetap utuh. Minimal 1 spasi pemisah.
        int maxLeft = RECEIPT_WIDTH - right.length() - 1;
        if (maxLeft < 0) maxLeft = 0;
        if (left.length() > maxLeft) left = left.substring(0, maxLeft);
        int space = RECEIPT_WIDTH - left.length() - right.length();
        if (space < 1) space = 1;
        StringBuilder sb = new StringBuilder();
        sb.append(left);
        for (int i = 0; i < space; i++) sb.append(' ');
        sb.append(right);
        return sb.toString();
    }

    /**
     * Pecah teks jadi beberapa baris ≤ {@link #RECEIPT_WIDTH} (pisah di spasi;
     * kata yang lebih panjang dari lebar struk dipotong keras) supaya tidak ada
     * teks yang overflow/wrap berantakan saat struk dirender jadi gambar.
     */
    private String wrapText(String s) {
        if (s == null) return "";
        if (s.length() <= RECEIPT_WIDTH) return s;
        StringBuilder out = new StringBuilder();
        StringBuilder ln = new StringBuilder();
        for (String w : s.split(" ")) {
            while (w.length() > RECEIPT_WIDTH) {
                if (ln.length() > 0) { out.append(ln).append('\n'); ln.setLength(0); }
                out.append(w, 0, RECEIPT_WIDTH).append('\n');
                w = w.substring(RECEIPT_WIDTH);
            }
            if (ln.length() == 0) ln.append(w);
            else if (ln.length() + 1 + w.length() <= RECEIPT_WIDTH) ln.append(' ').append(w);
            else { out.append(ln).append('\n'); ln.setLength(0); ln.append(w); }
        }
        if (ln.length() > 0) out.append(ln);
        return out.toString();
    }

    /** center() untuk teks yang mungkin panjang: wrap dulu, lalu center tiap baris. */
    private String centerBlock(String s) {
        String[] lines = wrapText(s).split("\n", -1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) out.append('\n');
            out.append(center(lines[i]));
        }
        return out.toString();
    }

    private void printReceipt() {
        BluetoothAdapter btAdapter = BluetoothAdapter.getDefaultAdapter();
        if (btAdapter == null) {
            Toast.makeText(this, "Bluetooth tidak tersedia", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!btAdapter.isEnabled()) {
            Toast.makeText(this, "Aktifkan Bluetooth terlebih dahulu", Toast.LENGTH_SHORT).show();
            return;
        }

        Set<BluetoothDevice> pairedDevices = btAdapter.getBondedDevices();
        if (pairedDevices.isEmpty()) {
            Toast.makeText(this, "Tidak ada printer Bluetooth yang terpasang. Pair printer di pengaturan Bluetooth.", Toast.LENGTH_LONG).show();
            return;
        }

        // Show paired device picker
        List<BluetoothDevice> printerList = new ArrayList<>(pairedDevices);
        String[] names = new String[printerList.size()];
        for (int i = 0; i < printerList.size(); i++) {
            BluetoothDevice d = printerList.get(i);
            names[i] = d.getName() + "\n" + d.getAddress();
        }

        new AlertDialog.Builder(this)
                .setTitle("Pilih Printer Bluetooth")
                .setItems(names, (dialog, which) -> {
                    BluetoothDevice device = printerList.get(which);
                    sendToPrinter(device);
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    private void sendToPrinter(BluetoothDevice device) {
        new Thread(() -> {
            BluetoothSocket socket = null;
            try {
                socket = device.createRfcommSocketToServiceRecord(SPP_UUID);
                socket.connect();
                OutputStream os = socket.getOutputStream();

                // ESC/POS commands
                byte[] init = {0x1B, 0x40}; // Initialize
                byte[] centerAlign = {0x1B, 0x61, 0x01}; // Center
                byte[] leftAlign = {0x1B, 0x61, 0x00}; // Left
                byte[] boldOn = {0x1B, 0x45, 0x01};
                byte[] boldOff = {0x1B, 0x45, 0x00};
                byte[] feed = {0x0A, 0x0A, 0x0A}; // Feed lines
                byte[] cut = {0x1D, 0x56, 0x00}; // Full cut

                os.write(init);
                os.write(leftAlign);
                os.write(receiptText.getBytes("UTF-8"));
                os.write(feed);
                os.write(cut);
                os.flush();

                runOnUiThread(() ->
                        Toast.makeText(this, "Struk berhasil dicetak", Toast.LENGTH_SHORT).show());
            } catch (IOException e) {
                runOnUiThread(() ->
                        Toast.makeText(this, "Gagal mencetak: " + e.getMessage(), Toast.LENGTH_LONG).show());
            } finally {
                if (socket != null) {
                    try { socket.close(); } catch (IOException ignored) {}
                }
            }
        }).start();
    }

    private void shareReceipt() {
        // Ikuti tab yang SEDANG TERLIHAT (toggle Teks/Gambar di atas struk) — bukan selalu gambar.
        // Tab "Teks" (default) berisi tautan QRIS/lacak yang bisa DIKETUK di WhatsApp; membagikannya
        // sebagai gambar PNG (perilaku lama) meratakan tautan itu jadi teks statis tak bisa diklik.
        com.google.android.material.button.MaterialButtonToggleGroup toggle = findViewById(R.id.strukViewToggle);
        if (toggle != null && toggle.getCheckedButtonId() == R.id.btnStrukTeks) {
            sendStrukWithTracking();
            return;
        }
        // Tab "Gambar": dibagikan sebagai GAMBAR (PNG). Bila pelanggan punya nomor HP, langsung tuju
        // chat WhatsApp pelanggan itu (lewat extra "jid") → TIDAK perlu memilih kontak; struk masuk
        // ke kotak kirim chat-nya, staf tinggal menekan "Kirim". (WhatsApp tidak mengizinkan menekan
        // "Kirim" otomatis lewat intent demi anti-spam, jadi satu ketukan tetap dilakukan staf.)
        final Uri uri = prepareReceiptImageUri();
        if (uri == null) {
            Toast.makeText(this, "Gambar struk belum siap", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("image/png");
        send.putExtra(Intent.EXTRA_STREAM, uri);
        send.setClipData(android.content.ClipData.newRawUri("", uri));
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        // Nomor pelanggan → JID → buka langsung chat-nya (lewati pemilih kontak WhatsApp).
        String jid = waJid(getIntent().getStringExtra(EXTRA_CUSTOMER_PHONE));
        if (jid != null) send.putExtra("jid", jid);

        // Samsung Freecess diam-diam membatalkan startActivity berbasis setPackage ke WhatsApp
        // yang "frozen" → WA cuma membuka Home & gambar tak terkirim (= "tidak bisa di-share").
        // Resolve komponen SEND WhatsApp lalu luncurkan langsung; kalau gagal / tak ada WA →
        // jatuh ke chooser sistem (bisa pilih app lain).
        String wa = pickWaPackage();
        if (wa != null) {
            Intent probe = new Intent(Intent.ACTION_SEND).setType("image/png").setPackage(wa);
            android.content.pm.ResolveInfo info = getPackageManager().resolveActivity(probe, 0);
            if (info != null) {
                Intent direct = new Intent(send).setComponent(new android.content.ComponentName(
                        info.activityInfo.packageName, info.activityInfo.name));
                direct.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    // Auto-kirim: hanya saat fitur aktif & kita memang menuju chat pelanggan (jid).
                    // Service akan menekan "Kirim" WhatsApp dalam beberapa detik ke depan; kalau
                    // izin Aksesibilitas belum diberi, arm() tak berdampak (struk tetap siap kirim).
                    if (jid != null && com.crowja.damiupos.wa.WaAutoSendService.isEnabled(this)) {
                        com.crowja.damiupos.wa.WaAutoSendService.arm();
                    }
                    startActivity(direct);
                    return;
                } catch (Exception e) {
                    android.util.Log.e("DAMIU", "WA direct share failed", e);
                    com.crowja.damiupos.wa.WaAutoSendService.disarm();   // jangan biarkan ter-arm menggantung
                }
            }
        }
        shareViaChooser(uri);
    }

    private void shareImageViaWa(Uri uri, String waPackage) {
        if (uri == null) {
            Toast.makeText(this, "Gambar struk belum siap", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Intent waImg = new Intent(Intent.ACTION_SEND);
            waImg.setType("image/png");
            waImg.setPackage(waPackage);
            waImg.putExtra(Intent.EXTRA_STREAM, uri);
            waImg.setClipData(android.content.ClipData.newRawUri("", uri));
            waImg.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(waImg);
        } catch (Exception e) {
            Toast.makeText(this, "Gagal membuka WhatsApp: " + e.getMessage(),
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void shareViaChooser(Uri uri) {
        if (uri == null) {
            Toast.makeText(this, "Gambar struk belum siap", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("image/png");
        shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
        shareIntent.setClipData(android.content.ClipData.newRawUri("", uri));
        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(shareIntent, "Bagikan Struk"));
    }

    private Uri prepareReceiptImageUri() {
        try {
            Bitmap bitmap = captureView(receiptCard);
            File file = new File(getExternalFilesDir("receipts"),
                    "struk_" + System.currentTimeMillis() + ".png");
            if (file.getParentFile() != null) file.getParentFile().mkdirs();
            FileOutputStream fos = new FileOutputStream(file);
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
            fos.close();
            return FileProvider.getUriForFile(this,
                    getApplicationContext().getPackageName() + ".fileprovider", file);
        } catch (IOException e) {
            Toast.makeText(this, "Gagal menyimpan struk: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
            return null;
        }
    }

    private String pickWaPackage() {
        android.content.pm.PackageManager pm = getPackageManager();
        try { pm.getPackageInfo("com.whatsapp", 0); return "com.whatsapp"; }
        catch (Exception ignored) {}
        try { pm.getPackageInfo("com.whatsapp.w4b", 0); return "com.whatsapp.w4b"; }
        catch (Exception ignored) {}
        return null;
    }

    /**
     * Nomor HP (mis. "0812…", "+62 812…", "62812…") → JID WhatsApp "62XXXXXXXXXX@s.whatsapp.net".
     * Dipakai sebagai extra "jid" pada intent ACTION_SEND agar WhatsApp membuka langsung chat
     * pelanggan tsb tanpa pemilih kontak. null bila nomor kosong/terlalu pendek.
     */
    private static String waJid(String phone) {
        if (phone == null) return null;
        String d = phone.replaceAll("[^0-9]", "");
        if (d.startsWith("0")) {
            d = "62" + d.substring(1);          // 08xx → 628xx
        } else if (d.startsWith("8")) {
            d = "62" + d;                        // 8xx (tanpa 0/62) → 628xx
        }
        // Sudah "62…" atau kode negara lain → biarkan apa adanya.
        return d.length() >= 9 ? d + "@s.whatsapp.net" : null;
    }

    /** Beranda (MainActivity) — bersihkan back-stack supaya tidak perlu navigate manual. */
    private void goMain() {
        Intent home = new Intent(this, MainActivity.class);
        home.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(home);
        finish();
    }

    /**
     * Order ini benar-benar DIANTAR? Dipakai untuk memutuskan baris "Ongkir: *FREE*" — penjualan di
     * tempat/ambil sendiri tak punya ongkir untuk digratiskan. Penandanya token pengiriman, sinyal
     * yang sama dengan link lacak (dan dengan delivery_status/token di web). CATATAN: jangan memakai
     * ongkir_type='none' sebagai penanda "tanpa pengiriman" — di HP "Tanpa" berarti ongkir GRATIS.
     */
    private static boolean isDeliveryOrder(android.content.Intent in) {
        String token = in.getStringExtra(EXTRA_DELIVERY_TOKEN);

        return token != null && !token.isEmpty();
    }

    /** Pesan WhatsApp: template (Konfigurasi web, sinkron semua perangkat; placeholder {nama}
     *  {depot}) + (jika ada) link lacak pengiriman live + teks kampanye aktif. Dipakai untuk teks
     *  yang benar-benar dikirim MAUPUN preview di layar (tab "Teks") — keduanya harus identik. */
    private String composeTrackingCaption(String custName) {
        String name = (custName != null && !custName.isEmpty()) ? custName : "Pelanggan";
        SettingsDao settings = new SettingsDao(DatabaseHelper.getInstance(this));
        // Urutan pesan: salam template dulu, BARU rincian pembelian (teks, tanpa foto struk),
        // baru tracking + kampanye di bawahnya.
        StringBuilder sb = new StringBuilder(settings.getWaStrukTemplate()
                .replace("{nama}", name)
                .replace("{depot}", settings.getDepotName())
                .replace("{fast-order}", fastOrderLine()));
        String strukText = composeTextStruk();
        if (!strukText.isEmpty()) sb.append("\n\n").append(strukText);
        // Link lacak standalone DIHAPUS 2026-08: pelacakan kini digabung ke link kampanye ORDER
        // persisten (appendActiveCampaigns di bawah) — begitu kampanye itu aktif & menyasar
        // pelanggan, link /c/{token}-nya SELALU dilampirkan (tak lagi digerbang kelayakan), jadi
        // satu link itu sudah cukup untuk lacak + riwayat + pesan lagi. Cermin StrukWa::compose (web).
        appendActiveCampaigns(sb);
        // Setelan cabang "struk tanpa emoji" dibuang di SATU titik akhir, sama seperti
        // StrukWa::compose di web, supaya tak ada bagian pesan yang terlewat.
        return settings.isWaStrukNoEmoji() ? stripEmoji(sb.toString()) : sb.toString();
    }

    /**
     * Struk ringkas berbentuk teks untuk pesan WA (menggantikan foto struk):
     * <pre>
     * Produk A 1 x Rp9.000 = Rp9.000
     * Ongkir Per Galon Rp2.000 x 3 = Rp6.000
     * *Total: Rp21.000 (Bayar Tunai)*
     * </pre>
     * Data dari intent extras (items_json / fallback produk tunggal + ongkir + total + metode bayar).
     */
    private String composeTextStruk() {
        try {
            java.text.NumberFormat nf = java.text.NumberFormat.getInstance(new java.util.Locale("id", "ID"));
            android.content.Intent in = getIntent();
            // ID transaksi unik (App\Support\ReceiptNumber) — WAJIB disematkan di judul rincian bila
            // baris ini sudah punya receipt_no (baris lama sebelum fitur ini: judul polos).
            String receiptNo = in.getStringExtra(EXTRA_RECEIPT_NO);
            StringBuilder sb = new StringBuilder("🧾 *Rincian Pembelian"
                    + (receiptNo != null && !receiptNo.isEmpty() ? " - " + receiptNo : "") + "*");
            int totalGalon = 0;
            double itemsSum = 0;   // untuk rekonsiliasi ongkir dari total yang dibayar
            String itemsJson = in.getStringExtra(EXTRA_ITEMS_JSON);
            List<TransactionItem> items = itemsJson != null ? TransactionItem.listFromJson(itemsJson) : null;
            if (items != null && !items.isEmpty()) {
                for (TransactionItem it : items) {
                    if (it.jumlah <= 0) continue;
                    totalGalon += it.jumlah;
                    itemsSum += it.jumlah * it.hargaPerGalon;
                    sb.append("\n").append(it.productName != null ? it.productName : "Produk")
                            .append(" ").append(it.jumlah)
                            .append(" x Rp").append(nf.format(Math.round(it.hargaPerGalon)))
                            .append(" = Rp").append(nf.format(Math.round(it.jumlah * it.hargaPerGalon)));
                }
            } else {
                // Transaksi lama tanpa items_json → satu baris dari extras produk tunggal.
                String pname = in.getStringExtra(EXTRA_PRODUCT_NAME);
                int jumlah = in.getIntExtra(EXTRA_JUMLAH, 0);
                double harga = in.getDoubleExtra(EXTRA_HARGA_PER_GALON, 0);
                if (jumlah > 0) {
                    totalGalon = jumlah;
                    itemsSum = jumlah * harga;
                    sb.append("\n").append(pname != null ? pname : "Air Minum")
                            .append(" ").append(jumlah)
                            .append(" x Rp").append(nf.format(Math.round(harga)))
                            .append(" = Rp").append(nf.format(Math.round(jumlah * harga)));
                }
            }
            double ongkir = in.getDoubleExtra(EXTRA_ONGKIR, 0);
            String ongkirType = in.getStringExtra(EXTRA_ONGKIR_TYPE);
            double total = in.getDoubleExtra(EXTRA_TOTAL_HARGA, 0);
            // ONGKIR — angkanya DITURUNKAN dari total yang benar-benar dibayar (cermin PERSIS
            // StrukWa::rincian + Transaction::strukExtras di web): sisa total yang tak dijelaskan
            // baris produk & botol dikembalikan ke ongkir. Satu rumus melayani ketiga bentuk
            // penyimpanan (tarif per galon HP / nilai datar / order web yang meleburnya ke total),
            // dan "Gratis Ongkir" hanya diklaim bila baris yang DICETAK berjumlah pas dengan *Total*.
            double botol = "BELI".equals(in.getStringExtra(EXTRA_OWNERSHIP))
                    ? in.getDoubleExtra(EXTRA_HARGA_BOTOL, 0) * totalGalon : 0;
            double colOngkir = ongkir <= 0 ? 0
                    : ("per_galon".equals(ongkirType) ? ongkir * totalGalon : ongkir);
            double unexplained = total - itemsSum - botol - colOngkir;
            double paidOngkir = unexplained > 0.5 ? colOngkir + unexplained : colOngkir;
            if (paidOngkir > 0.5 && ongkir > 0 && totalGalon > 0
                    && Math.abs(ongkir * totalGalon - paidOngkir) < 1) {
                sb.append("\nOngkir Per Galon Rp").append(nf.format(Math.round(ongkir)))
                        .append(" x ").append(totalGalon)
                        .append(" = Rp").append(nf.format(Math.round(paidOngkir)));
            } else if (paidOngkir > 0.5) {
                sb.append("\nOngkir = Rp").append(nf.format(Math.round(paidOngkir)));
            } else if (isDeliveryOrder(in) && itemsSum > 0 && Math.abs(total - itemsSum - botol) <= 0.5) {
                // Diantar & seluruh rupiah sudah dijelaskan baris cetak → ongkirnya memang digratiskan.
                // Cermin App\Support\StrukWa::rincian di web (isFreeDelivery) — teks "Gratis Ongkir"
                // (bukan "FREE") supaya pelanggan langsung paham tanpa perlu tahu bahasa Inggris.
                sb.append("\nGratis Ongkir");
            }
            // Metode pembayaran DILEBUR ke baris Total (satu baris) — "*Total: Rp5.000 (Bayar
            // Tunai)*", DILEWATI bila ada pelunasan hutang lama (di bawah) -- *Total* sudah gabungan
            // dua hal berbeda, jadi tak dilabeli satu metode bayar saja. Cermin App\Support\StrukWa
            // ::rincian di web.
            String pay = in.getStringExtra(EXTRA_PAYMENT_METHOD);
            String payLabel = null;
            if (pay != null && !pay.isEmpty()) {
                payLabel = " (Bayar " + pay.substring(0, 1).toUpperCase()
                        + pay.substring(1).toLowerCase(java.util.Locale.ROOT) + ")";
            }
            long custIdForDebt = in.getLongExtra(EXTRA_CUSTOMER_ID, -1);
            // PELUNASAN HUTANG LAMA lewat transaksi ini ("Sekalian Lunasi Hutang" saat checkout, atau
            // bayar sebagian saat Selesai) -- uang TAMBAHAN di luar tagihan penjualan, dicetak sebagai
            // BARIS TAGIHAN tersendiri ("Hutang (Pembelian Sebelumnya <ID_TRX>): Rp...") lalu dilebur
            // ke *Total* -- supaya *Total* SELALU sama dengan uang yang benar-benar diserahkan
            // pelanggan hari ini, bukan ringkasan terpisah sesudahnya. Cermin App\Support\StrukWa
            // ::rincian di web.
            String trxUuidForDebt = in.getStringExtra(EXTRA_GIFT_TRX_UUID);
            com.crowja.damiupos.db.CustomerDebtDao debtDao =
                    new com.crowja.damiupos.db.CustomerDebtDao(DatabaseHelper.getInstance(this));
            double debtPaidNow = trxUuidForDebt != null ? debtDao.paidForTransaction(trxUuidForDebt) : 0;
            if (debtPaidNow > 0) {
                List<String> debtOrigins = custIdForDebt > 0 ? debtDao.originReceiptsFor(custIdForDebt, 1) : new ArrayList<>();
                String debtKet = !debtOrigins.isEmpty()
                        ? "Pembelian Sebelumnya " + android.text.TextUtils.join(", ", debtOrigins)
                        : "Hutang Sebelumnya";
                sb.append("\nHutang (").append(debtKet).append("): Rp").append(nf.format(Math.round(debtPaidNow)));
            }
            // SALDO REFUND yang dipakai membayar order ini -> pelanggan harus melihat berapa yang
            // dipotong dan berapa sisa yang benar-benar dibayar. Bila ada potongan, label pembayaran
            // PINDAH ke baris "Sisa dibayar" (Total tetap kotor). Urutan & kata-katanya cermin
            // App\Support\StrukWa::rincian di web -- dulu blok ini tak ada sama sekali di HP,
            // jadi pelanggan menerima "Total: Rp30.000" tanpa keterangan bahwa itu sudah lunas dari saldonya.
            double refundDipotong = in.getDoubleExtra(EXTRA_REFUND_DIPOTONG, 0);
            double grandTotal = total + debtPaidNow;
            sb.append("\n*Total: Rp").append(nf.format(Math.round(grandTotal))).append("*")
                    .append(refundDipotong > 0 || debtPaidNow > 0 ? "" : (payLabel != null ? payLabel : ""));
            if (refundDipotong > 0) {
                sb.append("\nDipotong saldo refund: -Rp").append(nf.format(Math.round(refundDipotong)));
                sb.append("\n*Sisa dibayar: Rp")
                        .append(nf.format(Math.round(Math.max(0, grandTotal - refundDipotong))))
                        .append("*").append(debtPaidNow > 0 ? "" : (payLabel != null ? payLabel : ""));
            }
            if (pay != null && !pay.isEmpty()) {
                // QRIS: link statis PER CABANG airfrez.com/{slug}-qris (slug dari /api/me, diatur
                // admin di Kelola Cabang) — satu QRIS per cabang, bukan lagi satu untuk semua
                // cabang (tiap cabang punya rekening/merchant sendiri). Halaman itu sendiri
                // live-fetch gambar QRIS terkini, jadi tak perlu di-update di sini saat admin
                // re-upload. Slug kosong = cabang belum dikonfigurasi → tak ada baris disematkan.
                if ("QRIS".equalsIgnoreCase(pay)) {
                    String slug = new com.crowja.damiupos.sync.SyncSettings(
                            new SettingsDao(DatabaseHelper.getInstance(this))).getBranchSlug();
                    if (slug != null && !slug.trim().isEmpty()) {
                        sb.append("\nUnduh gambar QRIS: https://airfrez.com/").append(slug.trim()).append("-qris");
                    }
                } else if ("TRANSFER".equalsIgnoreCase(pay)) {
                    String bankDetail = new SettingsDao(DatabaseHelper.getInstance(this)).getBankTransferDetail();
                    if (bankDetail != null && !bankDetail.trim().isEmpty()) {
                        sb.append("\n").append(bankDetail.trim());
                    }
                }
            }
            // Order ini dibuat OTOMATIS oleh fitur "Pesanan Terjadwal" (web-only, ScheduledOrders::
            // createAutoTertunda) — deteksi lewat header audit di catatan MENTAH (sebelum
            // customerNote() membuangnya), cermin App\Support\StrukWa::rincian di web.
            String rawCatatan = in.getStringExtra(EXTRA_CATATAN);
            if (rawCatatan != null && rawCatatan.toLowerCase(java.util.Locale.ROOT).contains("pesanan terjadwal")
                    && rawCatatan.toLowerCase(java.util.Locale.ROOT).contains("dibuat di web")) {
                sb.append("\n📅 [TERJADWAL]");
            }
            // Catatan transaksi (bila ada & bukan sekadar penanda internal) — cermin baris "Catatan:"
            // pada struk cetak/foto + StrukWa::rincian di web.
            String note = customerNote(rawCatatan);
            if (!note.isEmpty()) {
                sb.append("\n📝 Catatan: ").append(note);
            }
            // SISA HUTANG pelanggan -- dibaca LIVE dari buku besar, sudah termasuk piutang baru dari
            // transaksi ini sendiri (Cash Bon / bayar sebagian) bila ada. Cermin App\Support\StrukWa
            // ::rincian di web. Pelanggan tak diketahui (walk-in/Umum) -> tak ada baris ini.
            if (custIdForDebt > 0) {
                double sisaHutang = debtDao.balanceFor(custIdForDebt);
                if (sisaHutang > 0) {
                    sb.append("\n\n🧾 *Sisa Hutang Anda saat ini: Rp")
                            .append(nf.format(Math.round(sisaHutang))).append("*");
                    // ID transaksi terhutang asalnya — pelanggan tahu hutang ini berasal dari
                    // pembelian yang mana. {@see CustomerDebtDao#originReceiptsFor}
                    List<String> origins = debtDao.originReceiptsFor(custIdForDebt, 1);
                    if (!origins.isEmpty()) {
                        sb.append("\n(dari transaksi ").append(android.text.TextUtils.join(", ", origins)).append(")");
                    }
                }
                // TIDAK menegaskan "Hutang Anda sudah LUNAS" di sini walau sisaHutang sudah 0 lewat
                // pelunasan transaksi ini -- keputusan lunas/belum/sebagian dibayar baru FINAL saat
                // order diselesaikan (popup Selesai/Ubah), bukan saat transaksi ini dibuat/dicetak
                // struknya. Nominal pelunasannya sendiri (baris "Hutang (Pembelian Sebelumnya
                // ...)"/"Total" di atas) tetap dicetak -- itu fakta uang yang sungguh diterima,
                // bukan simpulan status. Cermin App\Support\StrukWa::rincian di web.
            }
            return sb.toString();
        } catch (Throwable t) {
            return "";   // struk teks best-effort — jangan gagalkan pengiriman pesan
        }
    }

    /**
     * Sisipkan judul + link tiap kampanye aktif yang menyasar perangkat ini dan BELUM TERPENUHI untuk
     * pelanggan ini (belum pernah diklik). Link DILAMPIRKAN ULANG di tiap struk sampai pelanggan klik —
     * atau kampanye dihapus/dinonaktifkan di web. Memakai delivery yang sama (token tetap) lalu memicu
     * sinkronisasi agar link {@code /c/{token}} bisa dibuka. No-op tanpa pelanggan / belum terhubung server.
     *
     * INFO "sematkan di struk" ({@code p.strukInline}): judul + teks pendek LANGSUNG, tanpa link — tak
     * perlu {@link com.crowja.damiupos.db.CampaignDao#ensureDelivery} / sinkronisasi dulu.
     */
    /** Placeholder {fast-order}: "Pesan-antar Jalur Cepat: <link Order Online persisten pelanggan>",
     *  kampanye ORDER aktif pertama yang menyasarnya — cermin StrukWa::fastOrderLine (web). '' bila
     *  tak ada pelanggan/kampanye ORDER aktif (placeholder lenyap, bukan menyisakan link kosong). */
    private String fastOrderLine() {
        try {
            long custId = getIntent().getLongExtra(EXTRA_CUSTOMER_ID, -1);
            if (custId <= 0) return "";
            com.crowja.damiupos.sync.SyncSettings cfg = new com.crowja.damiupos.sync.SyncSettings(
                    new SettingsDao(DatabaseHelper.getInstance(this)));
            if (!cfg.isEnrolled()) return "";
            String deviceUuid = cfg.getDeviceUuid();
            String base = cfg.getTrackBaseUrl();
            if (deviceUuid == null || deviceUuid.isEmpty() || base == null || base.isEmpty()) return "";
            base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;

            com.crowja.damiupos.db.CampaignDao cDao =
                    new com.crowja.damiupos.db.CampaignDao(DatabaseHelper.getInstance(this));
            for (com.crowja.damiupos.db.CampaignDao.Pending p : cDao.activeForCustomer(deviceUuid, custId, campaignScheduleDate())) {
                if (!p.isOrder) continue;
                String path = cDao.ensureDelivery(p.campaignLocalId, custId);
                if (path == null) continue;
                return "Pesan-antar Jalur Cepat: " + base + "/" + path;
            }
        } catch (Throwable ignored) {
            // Best-effort, sama seperti appendActiveCampaigns: kegagalan tak boleh menggagalkan struk.
        }
        return "";
    }

    /**
     * Tanggal untuk menilai JADWAL KAMPANYE pada struk ini ("yyyy-MM-dd"), atau null = hari ini.
     *
     * <p>Order TERTUNDA: struk disusun hari ini tetapi galonnya baru sampai pada tanggal LANJUT,
     * jadi kampanye berjadwal harus dinilai di tanggal itu — info "promo Sabtu" pada order yang
     * dijadwalkan lanjut Sabtu memang layak disematkan walau struknya dibuat Kamis. Cermin
     * Transaction::strukScheduleDate() di web.</p>
     */
    private String campaignScheduleDate() {
        try {
            long trxId = getIntent().getLongExtra(EXTRA_TRANSACTION_ID, -1);
            if (trxId <= 0) return null;
            com.crowja.damiupos.model.Transaction t =
                    new com.crowja.damiupos.db.TransactionDao(DatabaseHelper.getInstance(this)).getById(trxId);
            if (t == null) return null;
            if (!com.crowja.damiupos.model.Transaction.DELIVERY_TERTUNDA.equals(t.getDeliveryStatus())) {
                return null;
            }
            String resume = t.getDeliveryTertundaResumeAt();
            return resume != null && resume.length() >= 10 ? resume.substring(0, 10) : null;
        } catch (Throwable ignored) {
            return null;   // best-effort: gagal baca → pakai hari ini, seperti sebelumnya
        }
    }

    /**
     * Buang emoji/piktograf (termasuk variation selector & ZWJ) lalu rapikan spasi sisa.
     * Rentang karakternya cermin App\Support\StrukWa::stripEmoji di server, supaya struk
     * dari HP dan dari dashboard menghasilkan teks yang sama saat setelan itu aktif.
     */
    private static String stripEmoji(String s) {
        if (s == null || s.isEmpty()) return s;
        String out = s.replaceAll("[\\x{1F000}-\\x{1FAFF}\\x{2190}-\\x{2BFF}\\x{FE00}-\\x{FE0F}\\x{200D}\\x{20E3}]", "");
        out = out.replaceAll("[ \\t]{2,}", " ");
        out = out.replaceAll("(?m)^[ \\t]+|[ \\t]+$", "");
        return out.trim();
    }

    // ---------------------------------------------------------------- kampanye: server otoritatif
    /**
     * Satu kampanye yang boleh dilampirkan, PERSIS seperti keputusan server.
     * {@code url} sudah lengkap (server yang membuat delivery/token-nya), jadi HP hanya merender.
     */
    private static final class ServerCampaign {
        String title;
        String url;
        boolean inline;
        String text;
        boolean isOrder;
        boolean trackingOnly;
    }

    /** Hasil dari server; null = belum/ tak tersedia → jatuh ke cermin lokal (CampaignDao). */
    private volatile java.util.List<ServerCampaign> serverCampaigns;

    /** Sudah selesai mencoba (berhasil ATAU gagal) — pembeda "masih jalan" vs "memang tak ada". */
    private volatile boolean serverCampaignsSettled;

    /** Antre menunggu hasil fetch (mis. tombol Kirim WA ditekan saat fetch masih jalan). */
    private final java.util.List<Runnable> serverCampaignsWaiters = new java.util.ArrayList<>();

    /**
     * AMBIL keputusan kampanye dari SERVER, bukan menghitungnya ulang di HP.
     *
     * <p>Aturan "kampanye mana yang ikut struk" berlapis (aktif, jadwal tanggal/hari, berhenti
     * melampir setelah diklik/terkirim, kelayakan pesan-ulang, urutan prioritas). Selama HP juga
     * menghitungnya sendiri, ada DUA implementasi atas satu aturan — dan cermin yang meleset
     * langsung terlihat pelanggan: kampanye berjadwal Rabu/Minggu pernah ikut terkirim di hari
     * Sabtu karena HP-nya memakai APK lama yang belum tahu ada jadwal.</p>
     *
     * <p>Karena itu: ONLINE → server yang memutuskan (fungsi yang sama dengan struk web & PDF),
     * OFFLINE → cermin lokal {@link com.crowja.damiupos.db.CampaignDao} sebagai jaring pengaman.
     * Dijalankan di latar sejak layar struk dibuka, jadi hasilnya sudah siap saat staf menekan
     * Kirim; tak pernah memblokir UI.</p>
     */
    private void prefetchServerCampaigns() {
        long custId = getIntent().getLongExtra(EXTRA_CUSTOMER_ID, -1);
        if (custId <= 0) {
            settleServerCampaigns(null);
            return;
        }
        final com.crowja.damiupos.sync.SyncSettings cfg = new com.crowja.damiupos.sync.SyncSettings(
                new SettingsDao(DatabaseHelper.getInstance(this)));
        if (!cfg.isEnrolled()) {
            settleServerCampaigns(null);
            return;
        }
        final String custUuid = new com.crowja.damiupos.db.CustomerDao(DatabaseHelper.getInstance(this))
                .getSyncUuidById(custId);
        if (custUuid == null || custUuid.isEmpty()) {
            settleServerCampaigns(null);
            return;
        }
        final String trxUuid = getIntent().getStringExtra(EXTRA_GIFT_TRX_UUID);

        new Thread(() -> {
            java.util.List<ServerCampaign> out = null;
            try {
                org.json.JSONObject res = new com.crowja.damiupos.sync.SyncApi(cfg)
                        .campaignAttachments(custUuid, trxUuid);
                org.json.JSONArray arr = res != null ? res.optJSONArray("items") : null;
                if (arr != null) {
                    out = new java.util.ArrayList<>();
                    for (int i = 0; i < arr.length(); i++) {
                        org.json.JSONObject o = arr.optJSONObject(i);
                        if (o == null) continue;
                        ServerCampaign sc = new ServerCampaign();
                        sc.title = o.optString("title", "");
                        sc.url = o.optString("url", "");
                        sc.inline = o.optBoolean("inline", false);
                        sc.text = o.isNull("text") ? null : o.optString("text", null);
                        sc.isOrder = o.optBoolean("order", false);
                        sc.trackingOnly = o.optBoolean("tracking_only", false);
                        out.add(sc);
                    }
                }
            } catch (Throwable ignored) {
                // Offline / server tak terjangkau → biarkan null: cermin lokal yang dipakai.
            }
            final java.util.List<ServerCampaign> result = out;
            runOnUiThread(() -> settleServerCampaigns(result));
        }).start();
    }

    /** Tandai fetch selesai + jalankan yang menunggu (dipanggil di UI thread). */
    private void settleServerCampaigns(java.util.List<ServerCampaign> result) {
        serverCampaigns = result;
        serverCampaignsSettled = true;
        java.util.List<Runnable> waiters = new java.util.ArrayList<>(serverCampaignsWaiters);
        serverCampaignsWaiters.clear();
        for (Runnable r : waiters) {
            try { r.run(); } catch (Throwable ignored) {}
        }
    }

    /**
     * Jalankan {@code action} setelah keputusan kampanye server tersedia (atau gagal). Tidak pernah
     * memblokir: bila fetch masih jalan, action-nya diantrekan dan dijalankan saat hasilnya tiba.
     */
    private void withServerCampaigns(Runnable action) {
        if (serverCampaignsSettled) {
            action.run();
            return;
        }
        serverCampaignsWaiters.add(action);
    }

    /**
     * Render bagian kampanye DARI keputusan server. Mengembalikan false bila server tak menjawab
     * (offline) supaya pemanggil jatuh ke cermin lokal.
     */
    private boolean appendServerCampaigns(StringBuilder sb) {
        java.util.List<ServerCampaign> list = serverCampaigns;
        if (list == null) return false;
        boolean any = false;
        for (ServerCampaign p : list) {
            if (p.trackingOnly) {
                if (p.url == null || p.url.isEmpty()) continue;
                sb.append("\n\n🚚 Pantau progres pengiriman & lokasi kurir secara langsung di sini:\n")
                        .append(p.url);
                any = true;
                continue;
            }
            String body;
            if (p.inline) {
                body = p.text != null ? p.text : "";
            } else {
                if (p.url == null || p.url.isEmpty()) continue;
                body = p.isOrder ? p.url
                        : "Untuk info selengkapnya, silakan klik link di bawah ini:\n" + p.url;
            }
            sb.append("\n\n📣 *").append(p.title != null ? p.title : "").append("*\n").append(body);
            any = true;
        }
        if (any) {
            sb.append("\n\nTerima kasih 🙏");
        }
        return true;   // server sudah menjawab — daftar kosong pun keputusan yang sah
    }

    private void appendActiveCampaigns(StringBuilder sb) {
        long custId = getIntent().getLongExtra(EXTRA_CUSTOMER_ID, -1);
        if (custId <= 0) return;
        // ONLINE: keputusan SERVER yang dipakai (satu implementasi aturan, tak bisa menyimpang).
        // Blok lokal di bawah hanya berjalan saat server tak terjangkau. Lihat prefetchServerCampaigns().
        if (appendServerCampaigns(sb)) return;
        try {
            com.crowja.damiupos.sync.SyncSettings cfg = new com.crowja.damiupos.sync.SyncSettings(
                    new SettingsDao(DatabaseHelper.getInstance(this)));
            if (!cfg.isEnrolled()) return;                 // tanpa server, link tak akan resolve
            String deviceUuid = cfg.getDeviceUuid();
            String base = cfg.getTrackBaseUrl();
            if (deviceUuid == null || deviceUuid.isEmpty() || base == null || base.isEmpty()) return;
            base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;

            com.crowja.damiupos.db.CampaignDao cDao =
                    new com.crowja.damiupos.db.CampaignDao(DatabaseHelper.getInstance(this));
            java.util.List<com.crowja.damiupos.db.CampaignDao.Pending> pending =
                    cDao.activeForCustomer(deviceUuid, custId, campaignScheduleDate());
            boolean any = false;
            for (com.crowja.damiupos.db.CampaignDao.Pending p : pending) {
                if (p.trackingOnly) {
                    // Belum layak pesan-ulang (min_repeat_orders): sembunyikan judul + ajakan "Pesan
                    // lagi" — tapi link-nya (halaman yang sama) tetap berguna untuk lacak pengiriman +
                    // riwayat, jadi dilampirkan sebagai baris netral tanpa framing promosi kampanye.
                    // Cermin StrukWa::campaigns() di web.
                    String path = cDao.ensureDelivery(p.campaignLocalId, custId);
                    if (path == null) continue;
                    sb.append("\n\n🚚 Pantau progres pengiriman & lokasi kurir secara langsung di sini:\n")
                            .append(base).append("/").append(path);
                    any = true;
                    continue;
                }
                String body;
                if (p.strukInline) {
                    // Sematkan langsung: teks pendek, TANPA link — tak perlu delivery/token sinkron
                    // dulu supaya link resolve; teksnya sudah lengkap di sini.
                    body = p.strukText != null ? p.strukText : "";
                } else {
                    // "c/{token}" atau (kampanye ORDER, sekali server memintal slug-nya) "fast/{slug}".
                    String path = cDao.ensureDelivery(p.campaignLocalId, custId);
                    if (path == null) continue;             // sudah diklik (terpenuhi) → lewati
                    // Cukup judul (📣 + tebal WA *…*) + ajakan klik link; isi selengkapnya ada di halaman
                    // kampanye, bukan di pesan WA — biar pesannya ringkas. ORDER: judul + link TELANJANG,
                    // tanpa kalimat pengantar (cermin StrukWa::campaigns() di web — link-nya sendiri
                    // sudah jadi rumah pesan-ulang + lacak + riwayat).
                    body = p.isOrder
                            ? base + "/" + path
                            : "Untuk info selengkapnya, silakan klik link di bawah ini:\n" + base + "/" + path;
                }
                sb.append("\n\n📣 *").append(p.title != null ? p.title : "").append("*\n").append(body);
                any = true;
            }
            if (any) {
                sb.append("\n\nTerima kasih 🙏");
                // Dorong sinkronisasi supaya delivery sampai ke server sebelum pelanggan membuka link.
                com.crowja.damiupos.sync.SyncScheduler.syncNow(getApplicationContext());
            }
        } catch (Throwable ignored) {
            // Kampanye bersifat best-effort: kegagalan apa pun tak boleh menggagalkan kirim struk.
        }
    }

    /**
     * Kirim struk TEKS (rincian pembelian di awal pesan + link lacak) ke pelanggan via
     * WhatsApp — TANPA foto struk. Karena murni teks, extra "jid" aman dipakai → WA langsung
     * membuka chat pelanggan (tanpa pemilih kontak) bila nomornya ada.
     * Fallback: chooser sistem. Dipanggil dari tombol akhir struk penjualan.
     */
    private void sendStrukWithTracking() {
        // Tunggu keputusan kampanye dari server bila fetch-nya masih jalan — pesan yang TERKIRIM ke
        // pelanggan tak boleh memakai cermin lokal hanya karena staf menekan Kirim beberapa ratus
        // milidetik lebih cepat. Tidak memblokir: aksinya diantrekan lalu dijalankan ulang.
        if (!serverCampaignsSettled) {
            withServerCampaigns(this::sendStrukWithTracking);
            return;
        }
        String caption = composeTrackingCaption(getIntent().getStringExtra(EXTRA_CUSTOMER_NAME));
        String waPackage = pickWaPackage();

        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_TEXT, caption);
        // Teks murni (tanpa lampiran gambar) → jalur direct-to-chat "jid" tidak men-drop apa pun.
        String jid = waJid(getIntent().getStringExtra(EXTRA_CUSTOMER_PHONE));
        if (jid != null) send.putExtra("jid", jid);

        if (waPackage != null) {
            // PENTING: pakai DIRECT COMPONENT, bukan setPackage. Samsung Freecess DIAM-DIAM men-drop
            // startActivity berbasis setPackage saat WhatsApp dalam keadaan beku (tanpa lempar
            // exception, jadi fallback chooser pun tak terpicu) → tombol "tidak bereaksi". Resolve
            // activity tujuan lalu setComponent (pola yang sama dengan exportToWhatsApp).
            android.content.pm.PackageManager pm = getPackageManager();
            Intent probe = new Intent(Intent.ACTION_SEND);
            probe.setType("text/plain");
            probe.setPackage(waPackage);
            android.content.pm.ResolveInfo info = pm.resolveActivity(probe, 0);
            if (info != null) {
                send.setComponent(new android.content.ComponentName(
                        info.activityInfo.packageName, info.activityInfo.name));
                send.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try { startActivity(send); return; }
                catch (Exception e) {
                    android.util.Log.e("DAMIU", "WA direct send failed", e);
                    send.setComponent(null);
                }
            }
            // Resolve gagal → coba setPackage biasa sebelum jatuh ke chooser.
            send.setPackage(waPackage);
            send.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try { startActivity(send); return; } catch (Exception ignored) { send.setPackage(null); }
        }
        // Fallback terakhir: chooser sistem (teks).
        send.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(Intent.createChooser(send, "Kirim Struk ke Pelanggan"));
        } catch (Exception e) {
            Toast.makeText(this, "Tidak dapat membuka aplikasi kirim", Toast.LENGTH_SHORT).show();
        }
    }

    private Bitmap captureView(View view) {
        // Belt-and-suspenders vs OS "force dark" (Samsung One UI inverts Light-themed views before
        // draw(), turning the captured struk into a solid dark/gray PNG). The theme already sets
        // android:forceDarkAllowed=false; here we also force it off on the exact view tree we draw,
        // and fill the canvas white so any residual transparency composites to white (not grey).
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            view.setForceDarkAllowed(false);
        }
        // rcCard hidup di dalam cardStrukGambar, yang mulai GONE (tab default "Teks" — lihat
        // wireStrukViewToggle). Sebuah View GONE tak pernah diukur oleh induknya, jadi getWidth()/
        // getHeight() masih 0 kalau staf langsung tekan Bagikan tanpa pernah membuka tab "Gambar" —
        // Bitmap.createBitmap(0,0,...) lalu crash (IllegalArgumentException). Ukur+layout manual di
        // sini kalau perlu, TANPA mengubah visibility (tak ada kedip pindah tab).
        if (view.getWidth() == 0 || view.getHeight() == 0) {
            int width = getWindow().getDecorView().getWidth();
            int widthSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY);
            int heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
            view.measure(widthSpec, heightSpec);
            view.layout(0, 0, view.getMeasuredWidth(), view.getMeasuredHeight());
        }
        Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(android.graphics.Color.WHITE);
        view.draw(canvas);
        return bitmap;
    }

    @Override
    public boolean onCreateOptionsMenu(android.view.Menu menu) {
        // Menu (Edit/Hapus) hanya untuk struk yang dibuka dari record DB. Item ditampilkan per-izin:
        // Hapus = Admin saja; Edit terbatas = staf operator (Staf/SPV/Admin). Marketing/Viewer: tak ada.
        boolean canDelete = currentUserCanDeleteTransaction();
        boolean canEdit = currentUserCanEditLimited();
        if (loadedTransactionId > 0 && (canDelete || canEdit)) {
            getMenuInflater().inflate(R.menu.menu_receipt, menu);
            android.view.MenuItem del = menu.findItem(R.id.action_delete_transaction);
            if (del != null) del.setVisible(canDelete);
            android.view.MenuItem edit = menu.findItem(R.id.action_edit_transaction);
            if (edit != null) edit.setVisible(canEdit);
        }
        return super.onCreateOptionsMenu(menu);
    }

    /** Marketing tidak boleh hapus transaksi; tanpa login (single-user) = boleh. */
    private boolean currentUserCanDeleteTransaction() {
        long uid = new com.crowja.damiupos.db.SettingsDao(
                DatabaseHelper.getInstance(this)).getCurrentUserId();
        if (uid <= 0) return true;
        com.crowja.damiupos.model.User u =
                new com.crowja.damiupos.db.UserDao(DatabaseHelper.getInstance(this)).getById(uid);
        return u == null || u.canDeleteTransaction();
    }

    /** Edit terbatas (metode pembayaran / jumlah galon kembali): staf operator; single-user = boleh. */
    private boolean currentUserCanEditLimited() {
        long uid = new com.crowja.damiupos.db.SettingsDao(
                DatabaseHelper.getInstance(this)).getCurrentUserId();
        if (uid <= 0) return true;
        com.crowja.damiupos.model.User u =
                new com.crowja.damiupos.db.UserDao(DatabaseHelper.getInstance(this)).getById(uid);
        return u == null || u.canEditTransactionLimited();
    }

    @Override
    public boolean onOptionsItemSelected(android.view.MenuItem item) {
        if (item.getItemId() == R.id.action_delete_transaction) {
            confirmDeleteTransaction();
            return true;
        }
        if (item.getItemId() == R.id.action_edit_transaction) {
            showEditTransactionDialog();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /**
     * Edit TERBATAS transaksi untuk staf operator. Tergantung jenis transaksi:
     *  - JUAL → ubah METODE PEMBAYARAN (Tunai/QRIS/Transfer). Nominal tidak berubah.
     *  - KEMBALI → ubah JUMLAH GALON yang dikembalikan (perbaiki salah hitung botol).
     * Perubahan disinkron (edited_at bump → LWW) lalu struk dimuat ulang.
     */
    private void showEditTransactionDialog() {
        if (loadedTransactionId <= 0) return;
        if (!currentUserCanEditLimited()) {
            Toast.makeText(this, "Anda tidak boleh mengubah transaksi", Toast.LENGTH_SHORT).show();
            return;
        }
        com.crowja.damiupos.model.Transaction t =
                new TransactionDao(DatabaseHelper.getInstance(this)).getById(loadedTransactionId);
        if (t == null) {
            Toast.makeText(this, "Transaksi tidak ditemukan", Toast.LENGTH_SHORT).show();
            return;
        }
        if (com.crowja.damiupos.model.Transaction.TYPE_KEMBALI.equals(t.getType())) {
            showEditKembaliGalon(t);
        } else {
            showEditPaymentMethod(t);
        }
    }

    private void showEditPaymentMethod(com.crowja.damiupos.model.Transaction t) {
        final String[] methods = {
                com.crowja.damiupos.model.Transaction.PAY_TUNAI,
                com.crowja.damiupos.model.Transaction.PAY_QRIS,
                com.crowja.damiupos.model.Transaction.PAY_TRANSFER };
        final String[] labels = { "Tunai", "QRIS", "Transfer" };
        int sel = 0;
        for (int i = 0; i < methods.length; i++) {
            if (methods[i].equals(t.getPaymentMethod())) { sel = i; break; }
        }
        final int[] choice = { sel };
        new AlertDialog.Builder(this)
                .setTitle("Ubah Metode Pembayaran")
                .setSingleChoiceItems(labels, sel, (d, w) -> choice[0] = w)
                .setPositiveButton("Simpan", (d, w) -> {
                    new TransactionDao(DatabaseHelper.getInstance(this))
                            .updatePaymentMethod(loadedTransactionId, methods[choice[0]]);
                    com.crowja.damiupos.sync.SyncScheduler.syncNow(getApplicationContext());
                    Toast.makeText(this, "Metode pembayaran diperbarui", Toast.LENGTH_SHORT).show();
                    recreate();   // muat ulang struk dengan nilai baru
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    private void showEditKembaliGalon(com.crowja.damiupos.model.Transaction t) {
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        input.setText(String.valueOf(t.getJumlahGalon()));
        input.setSelectAllOnFocus(true);
        int pad = Math.round(getResources().getDisplayMetrics().density * 20);
        android.widget.FrameLayout wrap = new android.widget.FrameLayout(this);
        wrap.setPadding(pad, pad / 2, pad, 0);
        wrap.addView(input);
        new AlertDialog.Builder(this)
                .setTitle("Ubah Jumlah Galon Kembali")
                .setView(wrap)
                .setPositiveButton("Simpan", (d, w) -> {
                    int g;
                    try { g = Integer.parseInt(input.getText().toString().trim()); }
                    catch (Exception e) { g = -1; }
                    if (g <= 0) {
                        Toast.makeText(this, "Jumlah galon harus lebih dari 0", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    new TransactionDao(DatabaseHelper.getInstance(this))
                            .updateKembaliGalon(loadedTransactionId, g);
                    com.crowja.damiupos.sync.SyncScheduler.syncNow(getApplicationContext());
                    Toast.makeText(this, "Jumlah galon kembali diperbarui", Toast.LENGTH_SHORT).show();
                    recreate();
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    private void confirmDeleteTransaction() {
        if (loadedTransactionId <= 0) return;
        if (!currentUserCanDeleteTransaction()) {
            Toast.makeText(this, "Hanya admin yang bisa menghapus transaksi",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Hapus Transaksi?")
                .setMessage("Yakin hapus transaksi ini? Tindakan tidak bisa dibatalkan.")
                .setPositiveButton("YA, HAPUS", (d, w) -> {
                    TransactionDao dao = new TransactionDao(DatabaseHelper.getInstance(this));
                    int rows = dao.delete(loadedTransactionId);
                    if (rows > 0) {
                        Toast.makeText(this, "Transaksi dihapus", Toast.LENGTH_SHORT).show();
                        finish();
                    } else {
                        Toast.makeText(this, "Gagal menghapus transaksi", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("TIDAK", null)
                .show();
    }

}
