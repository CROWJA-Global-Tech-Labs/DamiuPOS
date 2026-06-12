package com.crowja.damiupos;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.net.Uri;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
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
    /** Nama pengirim WA dari OrderInbox (kalau transaksi dibuat dari inbox).
     *  Dipakai untuk lookup tambahan di Android Contacts kalau nama DAMIU POS
     *  customer berbeda dari nama kontak HP (mis. "My Dj" vs "My Dj ❤️"). */
    public static final String EXTRA_INBOX_SENDER_NAME = "inbox_sender_name";
    public static final String EXTRA_PRODUCT_NAME = "product_name";
    public static final String EXTRA_JUMLAH = "jumlah";
    public static final String EXTRA_HARGA_PER_GALON = "harga_per_galon";
    public static final String EXTRA_ONGKIR = "ongkir";
    public static final String EXTRA_ONGKIR_TYPE = "ongkir_type";
    public static final String EXTRA_TOTAL_HARGA = "total_harga";
    public static final String EXTRA_CATATAN = "catatan";
    public static final String EXTRA_POINTS_ENABLED = "points_enabled";
    public static final String EXTRA_POINTS_EARNED = "points_earned";
    public static final String EXTRA_POINTS_TOTAL = "points_total";
    public static final String EXTRA_POINTS_REWARD = "points_reward";
    public static final String EXTRA_ITEMS_JSON = "items_json";
    public static final String EXTRA_REWARD_UNLOCKED = "reward_unlocked";
    public static final String EXTRA_REWARDS_NEW_COUNT = "rewards_new_count";
    public static final String EXTRA_TRANSACTION_ID = "transaction_id";
    public static final String EXTRA_IS_GANTI_RUGI = "is_ganti_rugi";
    public static final String EXTRA_GANTI_RUGI_KEMBALI = "ganti_rugi_kembali";

    private static final int RECEIPT_WIDTH = 32; // chars for 58mm printer
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private TextView tvReceiptContent;
    private LinearLayout receiptContainer;
    private String receiptText;
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

        tvReceiptContent = findViewById(R.id.tvReceiptContent);
        receiptContainer = findViewById(R.id.receiptContainer);

        // If invoked with EXTRA_TRANSACTION_ID, hydrate extras from DB
        long trxId = getIntent().getLongExtra(EXTRA_TRANSACTION_ID, -1);
        if (trxId > 0) {
            hydrateFromTransaction(trxId);
        }

        receiptText = buildReceipt();
        tvReceiptContent.setText(receiptText);

        com.google.android.material.button.MaterialButton btnShare = findViewById(R.id.btnShare);
        String custName = getIntent().getStringExtra(EXTRA_CUSTOMER_NAME);
        if (custName != null && !custName.isEmpty()) {
            btnShare.setText("Bagikan ke " + custName);
        } else {
            btnShare.setText("Bagikan ke Pelanggan");
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

        findViewById(R.id.btnPrint).setOnClickListener(v -> printReceipt());
        btnShare.setOnClickListener(v -> shareReceipt());

        // "Selesai" — clear back stack & balik ke MainActivity, supaya
        // user tidak perlu navigate back manual lewat TransactionActivity dulu.
        findViewById(R.id.btnDone).setOnClickListener(v -> {
            Intent home = new Intent(this, MainActivity.class);
            home.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP
                    | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(home);
            finish();
        });

        // Save trxId for menu delete action
        this.loadedTransactionId = trxId;
        View btnExportWa = findViewById(R.id.btnExportWa);
        android.util.Log.d("DAMIU", "btnExportWa view=" + btnExportWa
                + " visible=" + (btnExportWa != null ? btnExportWa.getVisibility() : "?"));
        if (btnExportWa != null) {
            btnExportWa.setOnClickListener(v -> {
                android.util.Log.d("DAMIU", "btnExportWa CLICKED");
                Toast.makeText(this, "Export WA tapped", Toast.LENGTH_SHORT).show();
                exportToWhatsApp();
            });
        }
    }

    private void exportToWhatsApp() {
        String waPackage = null;
        android.content.pm.PackageManager pm = getPackageManager();
        try { pm.getPackageInfo("com.whatsapp", 0); waPackage = "com.whatsapp"; } catch (Exception ignored) {}
        if (waPackage == null) {
            try { pm.getPackageInfo("com.whatsapp.w4b", 0); waPackage = "com.whatsapp.w4b"; } catch (Exception ignored) {}
        }
        if (waPackage == null) {
            Toast.makeText(this, "WhatsApp tidak terpasang", Toast.LENGTH_SHORT).show();
            return;
        }

        // Direct component launch to WA's ContactPicker — bypasses Samsung Freecess
        // which silently drops setPackage-based startActivity on frozen apps.
        android.content.pm.ResolveInfo info;
        {
            Intent probe = new Intent(Intent.ACTION_SEND);
            probe.setType("text/plain");
            probe.setPackage(waPackage);
            info = pm.resolveActivity(probe, 0);
        }
        android.util.Log.d("DAMIU", "exportToWhatsApp pkg=" + waPackage
                + " resolve=" + (info != null ? info.activityInfo.name : "null"));

        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_TEXT, "```\n" + receiptText + "\n```");

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

        // Fallback: system chooser (lets user pick WA from share sheet)
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
        // Lookup phone
        com.crowja.damiupos.db.CustomerDao cDao = new com.crowja.damiupos.db.CustomerDao(DatabaseHelper.getInstance(this));
        com.crowja.damiupos.model.Customer c = cDao.getById(t.getCustomerId());
        if (c != null) i.putExtra(EXTRA_CUSTOMER_PHONE, c.getPhone());
        i.putExtra(EXTRA_PRODUCT_NAME, t.getProductName());
        i.putExtra(EXTRA_JUMLAH, t.getJumlahGalon());
        i.putExtra(EXTRA_HARGA_PER_GALON, t.getHargaPerGalon());
        i.putExtra(EXTRA_ONGKIR, t.getOngkir());
        i.putExtra(EXTRA_ONGKIR_TYPE, t.getOngkirType());
        i.putExtra(EXTRA_TOTAL_HARGA, t.getTotalHarga());
        i.putExtra(EXTRA_CATATAN, t.getCatatan());
        if (t.getItems() != null && !t.getItems().isEmpty()) {
            i.putExtra(EXTRA_ITEMS_JSON, TransactionItem.listToJson(t.getItems()));
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

    private String buildReceipt() {
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

        NumberFormat nf = NumberFormat.getInstance(new Locale("id", "ID"));
        String tanggal = new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.US).format(new Date());

        SettingsDao settingsDao = new SettingsDao(DatabaseHelper.getInstance(this));
        String depotName = settingsDao.getDepotName();
        String depotAddress = settingsDao.getDepotAddress();
        String depotPhone = settingsDao.getDepotPhone();

        StringBuilder sb = new StringBuilder();

        // Header
        if (depotName != null && !depotName.isEmpty()) {
            sb.append(center(depotName.toUpperCase(Locale.getDefault()))).append("\n");
        } else {
            sb.append(center("DAMIU POS")).append("\n");
        }
        if (depotAddress != null && !depotAddress.isEmpty()) {
            for (String ln : depotAddress.split("\\r?\\n")) {
                sb.append(center(ln)).append("\n");
            }
        } else {
            sb.append(center("Depot Air Minum Isi Ulang")).append("\n");
        }
        if (depotPhone != null && !depotPhone.isEmpty()) {
            sb.append(center("HP: " + depotPhone)).append("\n");
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
        sb.append(custLine).append("\n");

        sb.append(line('-')).append("\n");

        // Items — detailed breakdown
        int totalGalon = 0;
        if (items != null && !items.isEmpty()) {
            for (TransactionItem it : items) {
                sb.append(it.productName != null ? it.productName : "-").append("\n");
                sb.append(leftRight(
                        "  " + it.jumlah + " x Rp " + nf.format(it.hargaPerGalon),
                        "Rp " + nf.format(it.getSubtotal()))).append("\n");
                totalGalon += it.jumlah;
            }
        } else {
            // Legacy single-product fallback
            if (productName != null && !productName.isEmpty()) {
                sb.append(productName).append("\n");
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

        sb.append(line('-')).append("\n");

        // Total
        sb.append(leftRight("TOTAL", "Rp " + nf.format(totalHarga))).append("\n");
        sb.append(line('=')).append("\n");

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
            sb.append("Catatan: ").append(catatan).append("\n");
            sb.append(line('-')).append("\n");
        }

        // Footer
        sb.append(center("Terima Kasih")).append("\n");
        sb.append(center("Semoga Berkah")).append("\n");
        sb.append("\n");
        sb.append(center("POWERED BY DAMIU POS")).append("\n");
        sb.append(center("Available in Google Playstore")).append("\n");

        return sb.toString();
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
        int space = RECEIPT_WIDTH - left.length() - right.length();
        if (space < 1) space = 1;
        StringBuilder sb = new StringBuilder();
        sb.append(left);
        for (int i = 0; i < space; i++) sb.append(' ');
        sb.append(right);
        return sb.toString();
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
        // Pakai nomor PERSIS yg ditampilkan di header struk
        // (EXTRA_CUSTOMER_PHONE — sudah di-override dgn inbox sender_phone
        // di TransactionActivity kalau dari inbox). User tidak perlu pilih
        // nomor mana — yg di struk = yg dipakai.
        String dbPhone = getIntent().getStringExtra(EXTRA_CUSTOMER_PHONE);

        final Uri uri = prepareReceiptImageUri();
        final String waPackage = pickWaPackage();

        if (waPackage == null) {
            shareViaChooser(uri);
            return;
        }

        if (dbPhone == null || dbPhone.trim().isEmpty()) {
            // Tidak ada nomor di struk → fallback image share
            // (WA tampilkan contact picker, user pilih manual)
            shareImageViaWa(uri, waPackage);
            return;
        }

        // Confirm dialog tetap muncul sebagai safety net — user masih bisa
        // edit kalau ada typo, tapi tidak ada picker multi-nomor.
        confirmShareToPhone(dbPhone, uri, waPackage);
    }

    /**
     * Kumpulkan semua nomor kandidat:
     * <ol>
     *     <li>Phone pelanggan dari DAMIU POS DB</li>
     *     <li>SEMUA nomor dari Android Contacts yg match nama pelanggan</li>
     *     <li>SEMUA nomor dari Android Contacts yg match nama pengirim WA
     *         (kalau berbeda, mis. "My Dj" di DB vs "My Dj ❤️" di kontak HP)</li>
     * </ol>
     * Dedup pakai canonical digit form ("0812..." dan "+62 812..." → 1).
     */
    private java.util.List<String> collectShareCandidates(String dbPhone,
                                                          String customerName,
                                                          String inboxSenderName) {
        java.util.LinkedHashMap<String, String> byNormalized =
                new java.util.LinkedHashMap<>();
        addPhoneCandidate(byNormalized, dbPhone);
        for (String p : lookupContactPhonesByName(customerName)) {
            addPhoneCandidate(byNormalized, p);
        }
        // Tambahan lookup dgn nama WA sender — sering nama kontak HP lebih
        // kaya (emoji, suffix) drpd nama DAMIU POS customer
        if (inboxSenderName != null && !inboxSenderName.isEmpty()
                && !inboxSenderName.equalsIgnoreCase(customerName)) {
            for (String p : lookupContactPhonesByName(inboxSenderName)) {
                addPhoneCandidate(byNormalized, p);
            }
        }
        return new java.util.ArrayList<>(byNormalized.values());
    }

    private static void addPhoneCandidate(
            java.util.LinkedHashMap<String, String> map, String phone) {
        if (phone == null || phone.trim().isEmpty()) return;
        String norm = phone.replaceAll("[^0-9]", "");
        if (norm.startsWith("0")) norm = "62" + norm.substring(1);
        else if (!norm.startsWith("62")) norm = "62" + norm;
        if (norm.length() < 8) return;
        if (!map.containsKey(norm)) map.put(norm, phone.trim());
    }

    /**
     * Cari semua nomor di Android Contacts berdasarkan display name.
     * 2-step: cari CONTACT_ID dulu, baru query semua phone untuk ID itu.
     * Reliable utk kontak yg ter-merge dari multi-account (Samsung).
     */
    private java.util.List<String> lookupContactPhonesByName(String name) {
        java.util.List<String> phones = new java.util.ArrayList<>();
        if (name == null || name.isEmpty()) return phones;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
                != PackageManager.PERMISSION_GRANTED) {
            return phones;
        }
        // Step 1: cari CONTACT_ID by name (exact, fallback fuzzy)
        java.util.List<Long> ids = findContactIds(name, false);
        if (ids.isEmpty()) ids = findContactIds(name, true);
        if (ids.isEmpty()) return phones;
        // Step 2: query SEMUA phone for those IDs
        StringBuilder placeholders = new StringBuilder();
        String[] args = new String[ids.size()];
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) placeholders.append(',');
            placeholders.append('?');
            args[i] = String.valueOf(ids.get(i));
        }
        Cursor c = null;
        try {
            c = getContentResolver().query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    new String[]{ContactsContract.CommonDataKinds.Phone.NUMBER},
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID
                            + " IN (" + placeholders + ")",
                    args,
                    "CASE " + ContactsContract.CommonDataKinds.Phone.TYPE
                            + " WHEN 2 THEN 0 ELSE 1 END ASC");
            while (c != null && c.moveToNext()) {
                String number = c.getString(0);
                if (number != null && !number.trim().isEmpty()) phones.add(number.trim());
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return phones;
    }

    private java.util.List<Long> findContactIds(String name, boolean fuzzy) {
        java.util.List<Long> ids = new java.util.ArrayList<>();
        String selection = fuzzy
                ? ContactsContract.Contacts.DISPLAY_NAME + " LIKE ?"
                : ContactsContract.Contacts.DISPLAY_NAME + " = ?";
        String[] args = fuzzy ? new String[]{name + "%"} : new String[]{name};
        Cursor c = null;
        try {
            c = getContentResolver().query(
                    ContactsContract.Contacts.CONTENT_URI,
                    new String[]{ContactsContract.Contacts._ID},
                    selection, args, null);
            while (c != null && c.moveToNext()) ids.add(c.getLong(0));
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return ids;
    }

    /** Picker dialog "Pilih nomor" → setelah pick, lanjut confirm dialog. */
    private void pickPhoneForShare(java.util.List<String> phones, Uri uri, String waPackage) {
        String[] arr = phones.toArray(new String[0]);
        new AlertDialog.Builder(this)
                .setTitle("Pilih nomor pelanggan")
                .setItems(arr, (d, which) ->
                        confirmShareToPhone(arr[which], uri, waPackage))
                .setNegativeButton("Batal", null)
                .show();
    }

    /**
     * Dialog konfirmasi: tampilkan nomor pelanggan yg akan ter-buka di WA,
     * editable supaya user bisa correct kalau salah. 3 opsi:
     *   - Lanjutkan: launch WA chat dgn nomor yg di-edit
     *   - Pilih Kontak Lain: image share → WA tampilkan contact picker
     *   - Batal
     */
    private void confirmShareToPhone(String phone, Uri imgUri, String waPackage) {
        android.widget.LinearLayout wrap = new android.widget.LinearLayout(this);
        wrap.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        wrap.setPadding(pad, pad, pad, 0);

        TextView header = new TextView(this);
        header.setText("Bagikan struk via WA ke nomor:\n(periksa & edit kalau salah)");
        header.setTextSize(13);
        header.setTextColor(getResources().getColor(R.color.text_secondary));
        wrap.addView(header);

        final android.widget.EditText etPhone = new android.widget.EditText(this);
        etPhone.setText(formatPhoneDisplay(phone));
        etPhone.setInputType(android.text.InputType.TYPE_CLASS_PHONE);
        wrap.addView(etPhone);

        new AlertDialog.Builder(this)
                .setTitle("Konfirmasi Nomor Pelanggan")
                .setView(wrap)
                .setPositiveButton("Lanjutkan", (d, w) -> {
                    String userPhone = etPhone.getText() != null
                            ? etPhone.getText().toString().trim() : "";
                    if (userPhone.isEmpty()) {
                        Toast.makeText(this, "Nomor kosong",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    String normalized = normalizeWaNumber(userPhone);
                    if (normalized.length() < 10) {
                        Toast.makeText(this, "Nomor terlalu pendek",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    launchWaChat(normalized, waPackage);
                })
                .setNeutralButton("Pilih Kontak Lain", (d, w) -> {
                    // Image share — WA buka contact picker, user pilih manual
                    shareImageViaWa(imgUri, waPackage);
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    private void launchWaChat(String waNumber, String waPackage) {
        try {
            Intent chatIntent = new Intent(Intent.ACTION_VIEW);
            chatIntent.setData(Uri.parse("https://wa.me/" + waNumber
                    + "?text=" + Uri.encode("```\n" + receiptText + "\n```")));
            chatIntent.setPackage(waPackage);
            startActivity(chatIntent);
        } catch (Exception e) {
            Toast.makeText(this, "Gagal buka WA: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
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
            waImg.putExtra(Intent.EXTRA_TEXT, receiptText);
            waImg.setClipData(android.content.ClipData.newRawUri("", uri));
            waImg.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(waImg);
        } catch (Exception e) {
            Toast.makeText(this, "Gagal membuka WhatsApp: " + e.getMessage(),
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void shareViaChooser(Uri uri) {
        if (uri != null) {
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("image/png");
            shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
            shareIntent.putExtra(Intent.EXTRA_TEXT, receiptText);
            shareIntent.setClipData(android.content.ClipData.newRawUri("", uri));
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(shareIntent, "Bagikan Struk"));
        } else {
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("text/plain");
            shareIntent.putExtra(Intent.EXTRA_TEXT, receiptText);
            startActivity(Intent.createChooser(shareIntent, "Bagikan Struk"));
        }
    }

    private Uri prepareReceiptImageUri() {
        try {
            Bitmap bitmap = captureView(receiptContainer);
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

    private static String normalizeWaNumber(String phone) {
        String d = phone.replaceAll("[^0-9]", "");
        if (d.startsWith("0")) d = "62" + d.substring(1);
        else if (!d.startsWith("62")) d = "62" + d;
        return d;
    }

    /** Format display: "+62 812 3456 7890" supaya mudah verify user. */
    private static String formatPhoneDisplay(String phone) {
        String d = normalizeWaNumber(phone);
        if (d.length() < 4) return "+" + d;
        StringBuilder sb = new StringBuilder("+").append(d, 0, 2);
        for (int i = 2; i < d.length(); ) {
            int chunk = (i == 2) ? 3 : 4;
            int end = Math.min(d.length(), i + chunk);
            sb.append(' ').append(d, i, end);
            i = end;
        }
        return sb.toString();
    }

    private Bitmap captureView(View view) {
        Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        view.draw(canvas);
        return bitmap;
    }

    @Override
    public boolean onCreateOptionsMenu(android.view.Menu menu) {
        // Hanya tampilkan menu hapus kalau struk dibuka dari record DB.
        if (loadedTransactionId > 0) {
            getMenuInflater().inflate(R.menu.menu_receipt, menu);
        }
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(android.view.MenuItem item) {
        if (item.getItemId() == R.id.action_delete_transaction) {
            confirmDeleteTransaction();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void confirmDeleteTransaction() {
        if (loadedTransactionId <= 0) return;
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
