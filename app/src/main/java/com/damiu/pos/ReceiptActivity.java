package com.damiu.pos;

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

import com.damiu.pos.db.DatabaseHelper;
import com.damiu.pos.db.SettingsDao;
import com.damiu.pos.db.TransactionDao;
import com.damiu.pos.model.TransactionItem;

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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_receipt);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> {
            com.damiu.pos.ads.AdManager.getInstance(this)
                    .maybeShowInterstitialAfterTransaction(this);
            finish();
        });

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
        com.damiu.pos.model.Transaction t = dao.getById(id);
        if (t == null) {
            Toast.makeText(this, "Transaksi tidak ditemukan", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        Intent i = getIntent();
        i.putExtra(EXTRA_CUSTOMER_NAME, t.getCustomerName());
        // Lookup phone
        com.damiu.pos.db.CustomerDao cDao = new com.damiu.pos.db.CustomerDao(DatabaseHelper.getInstance(this));
        com.damiu.pos.model.Customer c = cDao.getById(t.getCustomerId());
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
        if (com.damiu.pos.model.Transaction.TYPE_KEMBALI.equals(t.getType())
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
        if (s.isPointsEnabled() && com.damiu.pos.model.Transaction.TYPE_JUAL.equals(t.getType())) {
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

        // Customer
        sb.append("Pelanggan:\n");
        sb.append("  ").append(customerName != null ? customerName : "-").append("\n");

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
        String phone = getIntent().getStringExtra(EXTRA_CUSTOMER_PHONE);

        // Capture receipt as image
        Uri uri = null;
        try {
            Bitmap bitmap = captureView(receiptContainer);
            File file = new File(getExternalFilesDir("receipts"), "struk_" + System.currentTimeMillis() + ".png");
            if (file.getParentFile() != null) file.getParentFile().mkdirs();
            FileOutputStream fos = new FileOutputStream(file);
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
            fos.close();

            uri = FileProvider.getUriForFile(this,
                    getApplicationContext().getPackageName() + ".fileprovider", file);
        } catch (IOException e) {
            Toast.makeText(this, "Gagal menyimpan struk: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }

        // Detect installed WhatsApp package
        String waPackage = null;
        android.content.pm.PackageManager pm = getPackageManager();
        try { pm.getPackageInfo("com.whatsapp", 0); waPackage = "com.whatsapp"; } catch (Exception ignored) {}
        if (waPackage == null) {
            try { pm.getPackageInfo("com.whatsapp.w4b", 0); waPackage = "com.whatsapp.w4b"; } catch (Exception ignored) {}
        }

        String waNumber = null;
        if (phone != null && !phone.isEmpty()) {
            waNumber = phone.replaceAll("[^0-9]", "");
            if (waNumber.startsWith("0")) waNumber = "62" + waNumber.substring(1);
            else if (waNumber.startsWith("62")) { /* ok */ }
            else if (waNumber.startsWith("8")) waNumber = "62" + waNumber;
        }

        if (waPackage != null) {
            // Prefer: direct chat with customer (text only, pre-selected contact)
            if (waNumber != null && !waNumber.isEmpty()) {
                try {
                    Intent chatIntent = new Intent(Intent.ACTION_VIEW);
                    chatIntent.setData(Uri.parse("https://wa.me/" + waNumber
                            + "?text=" + Uri.encode("```\n" + receiptText + "\n```")));
                    chatIntent.setPackage(waPackage);
                    startActivity(chatIntent);
                    return;
                } catch (Exception e) {
                    // fall through to image share
                }
            }

            // Image share: WA opens contact picker, user selects contact manually
            if (uri != null) {
                try {
                    Intent waImg = new Intent(Intent.ACTION_SEND);
                    waImg.setType("image/png");
                    waImg.setPackage(waPackage);
                    waImg.putExtra(Intent.EXTRA_STREAM, uri);
                    waImg.putExtra(Intent.EXTRA_TEXT, receiptText);
                    waImg.setClipData(android.content.ClipData.newRawUri("", uri));
                    waImg.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(waImg);
                    return;
                } catch (Exception e) {
                    Toast.makeText(this, "Gagal membuka WhatsApp: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            }
        }

        // WhatsApp not installed — fallback chooser
        if (uri != null) {
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("image/png");
            shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
            shareIntent.putExtra(Intent.EXTRA_TEXT, receiptText);
            shareIntent.setClipData(android.content.ClipData.newRawUri("", uri));
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Toast.makeText(this, "WhatsApp tidak terpasang", Toast.LENGTH_SHORT).show();
            startActivity(Intent.createChooser(shareIntent, "Bagikan Struk"));
        } else {
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("text/plain");
            shareIntent.putExtra(Intent.EXTRA_TEXT, receiptText);
            startActivity(Intent.createChooser(shareIntent, "Bagikan Struk"));
        }
    }

    private Bitmap captureView(View view) {
        Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        view.draw(canvas);
        return bitmap;
    }
}
