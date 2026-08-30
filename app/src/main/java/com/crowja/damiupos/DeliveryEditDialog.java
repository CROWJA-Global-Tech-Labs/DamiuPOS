package com.crowja.damiupos;

import android.app.Activity;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.ProductDao;
import com.crowja.damiupos.db.SettingsDao;
import com.crowja.damiupos.db.TransactionDao;
import com.crowja.damiupos.db.UserDao;
import com.crowja.damiupos.model.Product;
import com.crowja.damiupos.model.Transaction;
import com.crowja.damiupos.model.TransactionItem;
import com.crowja.damiupos.sync.SyncApi;
import com.crowja.damiupos.sync.SyncScheduler;
import com.crowja.damiupos.sync.SyncSettings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * EDIT sebuah order antrian delivery: tambah/hapus baris produk, ubah jumlah & harga, ongkir, +
 * galon kembali AKTUAL. Alasan OPSIONAL. Server memutuskan jalurnya (cermin
 * TransactionController::requestEdit di web, sisi admin):
 * <ul>
 * <li>Order MASIH di antrian (PENDING/TERTUNDA) → diterapkan LANGSUNG, lalu email LAPORAN
 * (bukan minta izin) ke email laporan cabang.</li>
 * <li>Order sudah tak di antrian (jarang — race, mis. selesai di HP lain sesaat sebelum submit)
 * → tetap alur token IZINKAN/TOLAK lama (alasan jadi wajib, server yang menolak bila kosong).</li>
 * </ul>
 * Hasil akhir Rp 0 SELALU ditolak server — pakai Void untuk membatalkan pesanan, bukan Edit.
 * Ongkir dikosongkan → TIDAK disertakan di payload, server mempertahankan nilai lama (termasuk
 * penskalaan tarif per-galon) — hanya isi bila BENAR-BENAR ingin mengubahnya.
 */
public final class DeliveryEditDialog {

    private DeliveryEditDialog() {}

    private static class EditRow {
        String pid;          // pid Android lokal bila baris ini berasal dari item lama (boleh null)
        final String name;
        final EditText etQty;
        final EditText etPrice;
        final View rowView;
        EditRow(String pid, String name, EditText etQty, EditText etPrice, View rowView) {
            this.pid = pid; this.name = name; this.etQty = etQty; this.etPrice = etPrice; this.rowView = rowView;
        }
    }

    public static void show(final Activity act, final Transaction queueRow) {
        if (act == null || queueRow == null) return;
        if (!Transaction.TYPE_JUAL.equals(queueRow.getType())) return;

        final DatabaseHelper dbh = DatabaseHelper.getInstance(act);
        final SyncSettings cfg = new SyncSettings(new SettingsDao(dbh));
        if (!cfg.isEnrolled()) {
            toast(act, "Perangkat belum terhubung ke server — edit perlu koneksi.");
            return;
        }

        final TransactionDao dao = new TransactionDao(dbh);
        // Baris antrian tidak membawa tanggal → muat penuh untuk prefill galon kembali berpasangan.
        final Transaction full = dao.getById(queueRow.getId());
        final Transaction t = full != null ? full : queueRow;
        final List<TransactionItem> items = (t.getItems() != null && !t.getItems().isEmpty())
                ? t.getItems() : queueRow.getItems();
        if (items == null || items.isEmpty()) {
            toast(act, "Transaksi tanpa rincian item — edit lewat Dashboard.");
            return;
        }
        int kembaliNow = 0;
        if (t.getCustomerId() > 0 && t.getTanggal() != null && !t.getTanggal().isEmpty()) {
            kembaliNow = dao.getReturnedGalonForSale(t.getCustomerId(), t.getTanggal());
        }

        final int pad = dp(act, 16);
        final LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, dp(act, 8), pad, dp(act, 4));

        final TextView hint = new TextView(act);
        hint.setText("Ubah pesanan \"" + safe(queueRow.getCustomerName()) + "\" sesuai AKTUAL di lokasi — "
                + "tambah/hapus produk, jumlah, harga, ongkir. Selama order masih di antrian & hasil "
                + "akhir bukan Rp 0, perubahan berlaku LANGSUNG (laporan dikirim ke email laporan).");
        hint.setTextSize(13f);
        root.addView(hint);

        final TextView itemsLabel = new TextView(act);
        itemsLabel.setText("Item Pesanan:");
        itemsLabel.setTextSize(13f);
        itemsLabel.setTypeface(itemsLabel.getTypeface(), android.graphics.Typeface.BOLD);
        itemsLabel.setPadding(0, dp(act, 12), 0, dp(act, 2));
        root.addView(itemsLabel);

        final LinearLayout rowsContainer = new LinearLayout(act);
        rowsContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(rowsContainer);

        final List<EditRow> rows = new ArrayList<>();
        for (TransactionItem it : items) {
            String nm = (it.productName != null && !it.productName.isEmpty()) ? it.productName : "Galon";
            String pid = it.productId > 0 ? String.valueOf(it.productId) : null;
            addRow(act, rowsContainer, rows, pid, nm, String.valueOf(it.jumlah),
                    it.hargaPerGalon > 0 ? formatPlain(it.hargaPerGalon) : "");
        }

        final Button btnAddProduct = new Button(act);
        btnAddProduct.setText("➕ Tambah Produk");
        btnAddProduct.setAllCaps(false);
        btnAddProduct.setPadding(0, dp(act, 6), 0, 0);
        root.addView(btnAddProduct);
        btnAddProduct.setOnClickListener(v -> showAddProductPicker(act, dbh, rowsContainer, rows));

        final TextView ongkirLabel = new TextView(act);
        ongkirLabel.setText("Ongkir:");
        ongkirLabel.setTextSize(13f);
        ongkirLabel.setPadding(0, dp(act, 14), 0, 0);
        root.addView(ongkirLabel);

        final EditText ongkir = new EditText(act);
        ongkir.setInputType(InputType.TYPE_CLASS_NUMBER);
        ongkir.setHint("Kosongkan jika ongkir tidak berubah");
        root.addView(ongkir);

        final TextView kembaliLabel = new TextView(act);
        kembaliLabel.setText("Galon Kembali Aktual (galon kosong yang benar-benar diterima):");
        kembaliLabel.setTextSize(13f);
        kembaliLabel.setPadding(0, dp(act, 14), 0, 0);
        root.addView(kembaliLabel);

        final EditText kembali = new EditText(act);
        kembali.setInputType(InputType.TYPE_CLASS_NUMBER);
        kembali.setHint("Galon kembali");
        kembali.setText(String.valueOf(Math.max(0, kembaliNow)));
        root.addView(kembali);

        final EditText note = new EditText(act);
        note.setHint("Alasan perubahan (opsional)…");
        note.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        note.setMinLines(2);
        note.setPadding(0, dp(act, 10), 0, 0);
        root.addView(note);
        root.addView(DeliveryVoidDialog.buildQuickReasonRow(act, note));

        final ScrollView scroll = new ScrollView(act);
        scroll.addView(root);

        final AlertDialog dialog = new AlertDialog.Builder(act)
                .setTitle("✏️ Ubah Pesanan")
                .setView(scroll)
                .setPositiveButton("Simpan Perubahan", null)
                .setNegativeButton("Batal", null)
                .create();

        dialog.setOnShowListener(d -> {
            final Button pos = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            pos.setOnClickListener(v -> {
                final List<EditRow> liveRows = new ArrayList<>(rows);   // baris yang belum dihapus
                double totalQty = 0;
                for (EditRow r : liveRows) {
                    double q = parseNonNegOr(r.etQty.getText().toString(), -1);
                    if (q < 0) {
                        toast(act, "Isi jumlah \"" + r.name + "\" dengan angka ≥ 0.");
                        return;
                    }
                    double p = parseNonNegOr(r.etPrice.getText().toString(), -1);
                    if (p < 0) {
                        toast(act, "Isi harga \"" + r.name + "\" dengan angka ≥ 0.");
                        return;
                    }
                    totalQty += q;
                }
                if (totalQty <= 0) {
                    toast(act, "Jumlah hasil edit 0 — untuk membatalkan pesanan gunakan Void.");
                    return;
                }
                int kb;
                try { kb = Integer.parseInt(kembali.getText().toString().trim()); }
                catch (NumberFormatException e) { kb = -1; }
                if (kb < 0) {
                    toast(act, "Isi Galon Kembali Aktual dengan angka ≥ 0.");
                    return;
                }
                final String ongkirText = ongkir.getText().toString().trim();
                Double ongkirVal = null;
                if (!ongkirText.isEmpty()) {
                    double ov = parseNonNegOr(ongkirText, -1);
                    if (ov < 0) {
                        toast(act, "Isi ongkir dengan angka ≥ 0, atau kosongkan.");
                        return;
                    }
                    ongkirVal = ov;
                }
                final String n = note.getText().toString().trim();
                final Double fOngkirVal = ongkirVal;
                final int fKb = kb;

                // Tinjau hasil AKHIR pesanan → tampilkan dulu untuk dikonfirmasi SEBELUM benar-benar
                // mengirim (edit langsung tak bisa "dibatalkan" setelah tersimpan di server, jadi
                // staf harus melihat rinciannya lebih dulu).
                String summary = buildFinalReviewSummary(liveRows, fOngkirVal, fKb, n);
                new AlertDialog.Builder(act)
                        .setTitle("Tinjau Pesanan")
                        .setMessage(summary)
                        .setPositiveButton("Ya, Simpan", (cd, cw) -> {
                            pos.setEnabled(false);
                            pos.setText("Menyimpan…");
                            dialog.setCancelable(false);
                            final Button neg = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
                            if (neg != null) neg.setEnabled(false);
                            submit(act, dialog, pos, neg, dbh, cfg, t, liveRows, fOngkirVal, fKb, n);
                        })
                        .setNegativeButton("Periksa Lagi", null)
                        .show();
            });
        });
        dialog.show();
    }

    /**
     * Tinjauan pesanan HASIL AKHIR (bukan daftar perubahan) untuk popup konfirmasi sebelum kirim:
     * tiap baris item + subtotalnya, ongkir, total, galon kembali aktual, alasan (bila diisi).
     */
    private static String buildFinalReviewSummary(List<EditRow> liveRows, Double ongkirVal,
                                                    int kb, String reason) {
        java.text.NumberFormat nf = java.text.NumberFormat.getInstance(new java.util.Locale("id", "ID"));
        StringBuilder sb = new StringBuilder();

        sb.append("Item Pesanan:\n");
        double totalItems = 0;
        for (EditRow r : liveRows) {
            double qty = parseNonNegOr(r.etQty.getText().toString(), 0);
            double price = parseNonNegOr(r.etPrice.getText().toString(), 0);
            double subtotal = qty * price;
            totalItems += subtotal;
            sb.append("• ").append(r.name).append(": ").append(fmtQty(qty))
                    .append(" × Rp ").append(nf.format(Math.round(price)))
                    .append(" = Rp ").append(nf.format(Math.round(subtotal))).append('\n');
        }

        sb.append("\nOngkir: ").append(ongkirVal != null
                ? "Rp " + nf.format(Math.round(ongkirVal)) : "tidak diubah");

        sb.append("\nTotal: Rp ").append(nf.format(Math.round(totalItems)));
        if (ongkirVal != null) {
            sb.append(" + Rp ").append(nf.format(Math.round(ongkirVal)))
                    .append(" = Rp ").append(nf.format(Math.round(totalItems + ongkirVal)));
        } else {
            sb.append(" (+ ongkir, belum termasuk — tak diubah)");
        }

        sb.append("\n\nGalon Kembali Aktual: ").append(kb);
        if (!reason.isEmpty()) {
            sb.append("\nAlasan: ").append(reason);
        }
        return sb.toString();
    }

    /** Jumlah tanpa desimal bila bulat (mis. "3" bukan "3.0"); satu angka di belakang koma bila tidak. */
    private static String fmtQty(double q) {
        return (Math.floor(q) == q) ? String.valueOf((long) q) : String.valueOf(q);
    }

    /** Tambah satu baris produk (item lama ATAU produk baru) ke kontainer + daftar pelacak. */
    private static void addRow(Activity act, LinearLayout container, List<EditRow> rows,
                                String pid, String name, String qtyText, String priceText) {
        final LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(act, 8), 0, 0);

        final LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final TextView label = new TextView(act);
        label.setText(name);
        label.setTextSize(13f);
        col.addView(label);

        final LinearLayout fieldsRow = new LinearLayout(act);
        fieldsRow.setOrientation(LinearLayout.HORIZONTAL);

        final EditText qty = new EditText(act);
        qty.setInputType(InputType.TYPE_CLASS_NUMBER);
        qty.setHint("Jumlah");
        qty.setText(qtyText);
        qty.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        fieldsRow.addView(qty);

        final EditText price = new EditText(act);
        price.setInputType(InputType.TYPE_CLASS_NUMBER);
        price.setHint("Harga/pcs");
        price.setText(priceText);
        LinearLayout.LayoutParams priceLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        priceLp.setMarginStart(dp(act, 8));
        price.setLayoutParams(priceLp);
        fieldsRow.addView(price);

        col.addView(fieldsRow);
        row.addView(col);

        final Button remove = new Button(act);
        remove.setText("✕");
        remove.setAllCaps(false);
        LinearLayout.LayoutParams removeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        removeLp.setMarginStart(dp(act, 6));
        remove.setLayoutParams(removeLp);
        row.addView(remove);

        container.addView(row);
        final EditRow er = new EditRow(pid, name, qty, price, row);
        rows.add(er);
        remove.setOnClickListener(v -> {
            container.removeView(row);
            rows.remove(er);
        });
    }

    /** Pemilih produk untuk "➕ Tambah Produk" — daftar katalog lokal, produk yang SUDAH ada di
     *  baris (lewat nama, case-insensitive) disaring agar tak menambah baris duplikat; naikkan
     *  jumlah baris yang sudah ada sebagai gantinya. */
    private static void showAddProductPicker(Activity act, DatabaseHelper dbh,
                                               LinearLayout rowsContainer, List<EditRow> rows) {
        java.util.Set<String> already = new java.util.HashSet<>();
        for (EditRow r : rows) already.add(r.name.trim().toLowerCase(java.util.Locale.ROOT));
        List<Product> all = new ProductDao(dbh).getAll();
        final List<Product> avail = new ArrayList<>();
        for (Product p : all) {
            if (!already.contains(p.getName().trim().toLowerCase(java.util.Locale.ROOT))) avail.add(p);
        }
        if (avail.isEmpty()) {
            toast(act, "Semua produk katalog sudah ada di baris — naikkan jumlahnya langsung.");
            return;
        }
        final String[] names = new String[avail.size()];
        for (int i = 0; i < avail.size(); i++) names[i] = avail.get(i).getName();
        new AlertDialog.Builder(act)
                .setTitle("Pilih Produk")
                .setItems(names, (d, which) -> {
                    Product p = avail.get(which);
                    String pid = p.getId() > 0 ? String.valueOf(p.getId()) : null;
                    addRow(act, rowsContainer, rows, pid, p.getName(), "1",
                            p.getHargaJual() > 0 ? formatPlain(p.getHargaJual()) : "");
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    private static void submit(final Activity act, final AlertDialog dialog, final Button pos,
                               final Button neg, final DatabaseHelper dbh, final SyncSettings cfg,
                               final Transaction t, final List<EditRow> rows, final Double ongkir,
                               final int kembali, final String reason) {
        new Thread(() -> {
            String errMsg = null;
            String okMsg = null;
            try {
                String trxUuid = new TransactionDao(dbh).getSyncUuidById(t.getId());
                if (trxUuid == null || trxUuid.isEmpty()) {
                    SyncScheduler.syncNow(act.getApplicationContext());
                    errMsg = "Transaksi belum tersinkron ke server. Coba lagi sebentar.";
                } else {
                    final SettingsDao sdao = new SettingsDao(dbh);
                    final long uid = sdao.getCurrentUserId();
                    final String uname = sdao.getCurrentUserName();

                    JSONObject body = new JSONObject();
                    body.put("transaction_uuid", trxUuid);
                    if (t.getEditedAt() != null && !t.getEditedAt().isEmpty()) {
                        body.put("base_edited_at", t.getEditedAt());
                    }
                    JSONArray arr = new JSONArray();
                    for (EditRow r : rows) {
                        JSONObject o = new JSONObject();
                        o.put("name", r.name);
                        o.put("qty", parseNonNegOr(r.etQty.getText().toString(), 0));
                        o.put("price", parseNonNegOr(r.etPrice.getText().toString(), 0));
                        if (r.pid != null && !r.pid.isEmpty()) o.put("pid", r.pid);
                        arr.put(o);
                    }
                    body.put("items", arr);
                    if (ongkir != null) body.put("ongkir", ongkir);
                    body.put("jual_return_qty", kembali);
                    if (!reason.isEmpty()) body.put("reason", reason);
                    if (uname != null && !uname.isEmpty()) body.put("requester_name", uname);
                    if (uid > 0) {
                        String reqUuid = new UserDao(dbh).getSyncUuidById(uid);
                        if (reqUuid != null && !reqUuid.isEmpty()) body.put("requester_staff_uuid", reqUuid);
                    }
                    if (t.getCustomerName() != null) body.put("customer_name", t.getCustomerName());

                    JSONObject r = new SyncApi(cfg).proposeTrxEdit(body);
                    boolean applied = r.optBoolean("applied", false);
                    okMsg = r.optString("message", applied
                            ? "Perubahan tersimpan & tersinkron." : "Pengajuan edit dikirim untuk persetujuan.");
                    // Diterapkan LANGSUNG di server → tarik perubahan itu supaya struk/antrian di HP
                    // ini ikut termutakhir tanpa menunggu jadwal sync berikutnya.
                    if (applied) SyncScheduler.syncNow(act.getApplicationContext());
                }
            } catch (SyncApi.SyncException se) {
                errMsg = extractMessage(se.body);
                if (errMsg == null) errMsg = "Gagal mengirim (kode " + se.code + ").";
            } catch (Exception e) {
                errMsg = "Gagal mengirim — periksa koneksi internet.";
            }

            final String fOk = okMsg;
            final String fErr = errMsg;
            act.runOnUiThread(() -> {
                if (act.isFinishing() || act.isDestroyed()) return;
                if (fOk != null) {
                    Toast.makeText(act, fOk, Toast.LENGTH_LONG).show();
                    dialog.dismiss();
                } else {
                    Toast.makeText(act, fErr, Toast.LENGTH_LONG).show();
                    pos.setEnabled(true);
                    pos.setText("Simpan Perubahan");
                    dialog.setCancelable(true);
                    if (neg != null) neg.setEnabled(true);
                }
            });
        }).start();
    }

    /** Ambil pesan ramah dari body JSON error server ({"message":"..."}); null bila gagal. */
    private static String extractMessage(String body) {
        if (body == null || body.isEmpty()) return null;
        try {
            return new JSONObject(body).optString("message", null);
        } catch (Exception e) {
            return null;
        }
    }

    private static double parseNonNegOr(String s, double fallback) {
        if (s == null) return fallback;
        try {
            double v = Double.parseDouble(s.trim());
            return v >= 0 ? v : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int dp(Activity act, int v) {
        return Math.round(v * act.getResources().getDisplayMetrics().density);
    }

    /** Angka tanpa pemisah ribuan (isi awal EditText harga) — beda dari formatRupiah tampilan. */
    private static String formatPlain(double v) {
        return String.valueOf((long) v);
    }

    private static String safe(String s) {
        return s != null && !s.isEmpty() ? s : "Pelanggan";
    }

    private static void toast(Activity act, String msg) {
        Toast.makeText(act, msg, Toast.LENGTH_LONG).show();
    }
}
