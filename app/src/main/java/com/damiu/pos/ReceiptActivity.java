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
    public static final String EXTRA_TOTAL_HARGA = "total_harga";
    public static final String EXTRA_CATATAN = "catatan";

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
        toolbar.setNavigationOnClickListener(v -> finish());

        tvReceiptContent = findViewById(R.id.tvReceiptContent);
        receiptContainer = findViewById(R.id.receiptContainer);

        receiptText = buildReceipt();
        tvReceiptContent.setText(receiptText);

        findViewById(R.id.btnPrint).setOnClickListener(v -> printReceipt());
        findViewById(R.id.btnShare).setOnClickListener(v -> shareReceipt());
    }

    private String buildReceipt() {
        String customerName = getIntent().getStringExtra(EXTRA_CUSTOMER_NAME);
        String productName = getIntent().getStringExtra(EXTRA_PRODUCT_NAME);
        int jumlah = getIntent().getIntExtra(EXTRA_JUMLAH, 0);
        double hargaPerGalon = getIntent().getDoubleExtra(EXTRA_HARGA_PER_GALON, 0);
        double ongkir = getIntent().getDoubleExtra(EXTRA_ONGKIR, 0);
        double totalHarga = getIntent().getDoubleExtra(EXTRA_TOTAL_HARGA, 0);
        String catatan = getIntent().getStringExtra(EXTRA_CATATAN);

        NumberFormat nf = NumberFormat.getInstance(new Locale("id", "ID"));
        String tanggal = new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.US).format(new Date());

        StringBuilder sb = new StringBuilder();

        // Header
        sb.append(center("DAMIU POS")).append("\n");
        sb.append(center("Depot Air Minum Isi Ulang")).append("\n");
        sb.append(line('=')).append("\n");

        // Date
        sb.append("Tgl: ").append(tanggal).append("\n");
        sb.append(line('-')).append("\n");

        // Customer
        sb.append("Pelanggan:\n");
        sb.append("  ").append(customerName != null ? customerName : "-").append("\n");

        // Product
        if (productName != null && !productName.isEmpty()) {
            sb.append("Jenis Air:\n");
            sb.append("  ").append(productName).append("\n");
        }

        sb.append(line('-')).append("\n");

        // Items
        sb.append(leftRight("Jumlah", jumlah + " galon")).append("\n");
        sb.append(leftRight("Harga/galon", "Rp " + nf.format(hargaPerGalon))).append("\n");
        if (ongkir > 0) {
            sb.append(leftRight("Ongkir/galon", "Rp " + nf.format(ongkir))).append("\n");
        }
        sb.append(line('-')).append("\n");

        // Total
        sb.append(leftRight("TOTAL", "Rp " + nf.format(totalHarga))).append("\n");
        sb.append(line('=')).append("\n");

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
        try {
            Bitmap bitmap = captureView(receiptContainer);
            File file = new File(getExternalFilesDir("receipts"), "struk_" + System.currentTimeMillis() + ".png");
            file.getParentFile().mkdirs();
            FileOutputStream fos = new FileOutputStream(file);
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
            fos.close();

            Uri uri = FileProvider.getUriForFile(this,
                    getApplicationContext().getPackageName() + ".fileprovider", file);

            // Send to WhatsApp if phone available
            if (phone != null && !phone.isEmpty()) {
                String waNumber = phone.replaceAll("[^0-9]", "");
                if (waNumber.startsWith("0")) {
                    waNumber = "62" + waNumber.substring(1);
                }
                Intent waIntent = new Intent(Intent.ACTION_SEND);
                waIntent.setType("image/png");
                waIntent.setPackage("com.whatsapp");
                waIntent.putExtra(Intent.EXTRA_STREAM, uri);
                waIntent.putExtra(Intent.EXTRA_TEXT, receiptText);
                waIntent.putExtra("jid", waNumber + "@s.whatsapp.net");
                waIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try {
                    startActivity(waIntent);
                    return;
                } catch (Exception e) {
                    // WhatsApp not available, fallback to chooser
                }
            }

            // Fallback: share via chooser
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("image/png");
            shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
            shareIntent.putExtra(Intent.EXTRA_TEXT, receiptText);
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(shareIntent, "Bagikan Struk"));
        } catch (IOException e) {
            // Fallback: share as text
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
