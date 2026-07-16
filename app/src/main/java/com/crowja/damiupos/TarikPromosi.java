package com.crowja.damiupos;

import android.app.Activity;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.crowja.damiupos.db.CustomerDao;
import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.TransactionDao;
import com.crowja.damiupos.model.Customer;
import com.crowja.damiupos.model.Transaction;
import com.crowja.damiupos.sync.SyncScheduler;

/**
 * "Tarik Galon Promosi" (staf marketing): pelanggan yang diberi galon promosi GRATIS namun tak
 * kunjung order ulang dikunjungi dan botol promosinya DITARIK kembali ke depot. Dicatat sebagai
 * transaksi KEMBALI bermarker {@link #MARKER}:
 * <ul>
 *   <li>stok fisik depot bertambah (Galon Kembali) — botolnya benar-benar pulang;</li>
 *   <li>"Galon Dipinjam" pelanggan berkurang (promo gratis umumnya tercatat PINJAM);</li>
 *   <li>NETRAL untuk poin promosi, konversi Pelanggan Promosi, follow-up/Daftar Kunjungan,
 *       dan antrian delivery — KEMBALI tidak pernah masuk metrik-metrik itu.</li>
 * </ul>
 * Jumlah tarikan dibatasi sisa galon promosi (diberikan − sudah ditarik) agar tak lebih-tarik;
 * KEDUA sisi dihitung LINTAS PERANGKAT (agg server srv_promo_galon / srv_promo_pulled digabung
 * dengan hitungan lokal per grup dedup) — penarikan dari perangkat mana pun tetap terhitung.
 */
public final class TarikPromosi {

    /** Marker catatan transaksi KEMBALI hasil penarikan (dipakai juga untuk hitung sisa). */
    public static final String MARKER = TransactionDao.PROMO_PULL_MARKER;

    private TarikPromosi() {}

    /** Muat data promo pelanggan di background lalu tampilkan dialog penarikan. */
    public static void show(Activity act, long customerId, Runnable onSaved) {
        DatabaseHelper dbh = DatabaseHelper.getInstance(act);
        TransactionDao tdao = new TransactionDao(dbh);
        CustomerDao cdao = new CustomerDao(dbh);
        new Thread(() -> {
            // getByIdMerged: saldo "Galon Dipinjam" di dialog harus gabungan lintas perangkat —
            // pada salinan hasil sync tanpa transaksi lokal, saldo lokal selalu 0 dan peringatan
            // lebih-tarik berbunyi palsu setiap kali (staf jadi terlatih mengabaikannya).
            Customer cust = cdao.getByIdMerged(customerId);
            // KEDUA sisi lintas perangkat (Σ max(lokal, agg server) se-grup dedup). Akuisisi
            // promo tercatat di HP marketing — salinan hasil sync di perangkat ini tak membawa
            // transaksinya, jadi hitungan lokal saja menolak penarikan dengan keliru. Sisi
            // "sudah ditarik" ikut lintas perangkat, atau perangkat lain menarik ULANG galon
            // yang sama (transaksi KEMBALI tak pernah sync antar perangkat).
            int promo = cdao.mergedPromoGalon(customerId);
            int pulled = cdao.mergedPromoPulledGalon(customerId);
            act.runOnUiThread(() -> {
                if (act.isFinishing() || act.isDestroyed()) return;
                render(act, tdao, cust, promo, pulled, onSaved);
            });
        }).start();
    }

    private static void render(Activity act, TransactionDao tdao, Customer cust,
                               int promo, int pulled, Runnable onSaved) {
        if (cust == null) {
            Toast.makeText(act, "Pelanggan tidak ditemukan", Toast.LENGTH_SHORT).show();
            return;
        }
        if (promo <= 0) {
            Toast.makeText(act, "Pelanggan ini tidak memiliki galon promosi (gratis) yang tercatat "
                    + "— di perangkat ini maupun menurut data server. Bila baru diberikan dari "
                    + "perangkat lain, tunggu sinkronisasi.", Toast.LENGTH_LONG).show();
            return;
        }
        final int remaining = promo - pulled;
        if (remaining <= 0) {
            Toast.makeText(act, "Semua galon promosi pelanggan ini (" + promo + " galon) sudah "
                    + "pernah ditarik.", Toast.LENGTH_LONG).show();
            return;
        }

        int saldo = cust.getSaldoGalon();
        StringBuilder msg = new StringBuilder();
        msg.append("Pelanggan: ").append(cust.getName() != null ? cust.getName() : "-").append('\n');
        msg.append("🎁 Galon promosi diberikan: ").append(promo).append(" galon\n");
        if (pulled > 0) msg.append("📥 Sudah ditarik sebelumnya: ").append(pulled).append(" galon\n");
        msg.append("💧 Galon dipinjam tercatat: ").append(saldo).append(" galon\n\n");
        msg.append("Botol yang ditarik dicatat sebagai Galon Kembali: stok depot bertambah dan "
                + "galon dipinjam pelanggan berkurang. Tidak memengaruhi poin promosi/konversi.");
        if (remaining > saldo) {
            msg.append("\n\n⚠️ Jumlah tarikan melebihi galon dipinjam yang tercatat — kemungkinan "
                    + "botol promosinya dicatat bukan sebagai \"Di Pinjam\"; saldo pelanggan bisa minus.");
        }

        final EditText etQty = new EditText(act);
        etQty.setInputType(InputType.TYPE_CLASS_NUMBER);
        etQty.setHint("Jumlah galon ditarik (maks " + remaining + ")");
        etQty.setText(String.valueOf(remaining));
        LinearLayout wrap = new LinearLayout(act);
        int pad = Math.round(20 * act.getResources().getDisplayMetrics().density);
        wrap.setPadding(pad, 0, pad, 0);
        wrap.addView(etQty, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        AlertDialog dialog = new AlertDialog.Builder(act)
                .setTitle("📥 Tarik Galon Promosi")
                .setMessage(msg)
                .setView(wrap)
                .setPositiveButton("YA, TARIK GALON", null)   // di-override agar tak auto-dismiss saat invalid
                .setNegativeButton("Batal", null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    int qty;
                    try {
                        qty = Integer.parseInt(etQty.getText().toString().trim());
                    } catch (Exception e) {
                        etQty.setError("Angka tidak valid");
                        return;
                    }
                    if (qty < 1) { etQty.setError("Minimal 1 galon"); return; }
                    if (qty > remaining) { etQty.setError("Maksimal " + remaining + " galon"); return; }
                    dialog.dismiss();
                    save(act, tdao, cust, qty, onSaved);
                }));
        dialog.show();
    }

    /** Simpan KEMBALI bermarker (created_by_name distempel DAO; KEMBALI tak pernah diantrikan). */
    private static void save(Activity act, TransactionDao tdao, Customer cust, int qty, Runnable onSaved) {
        Transaction k = new Transaction();
        k.setCustomerId(cust.getId());
        k.setType(Transaction.TYPE_KEMBALI);
        k.setJumlahGalon(qty);
        k.setTotalHarga(0);
        k.setCatatan(MARKER + " " + qty + " galon ditarik dari pelanggan promosi");
        long id = tdao.insert(k);
        if (id > 0) {
            SyncScheduler.syncNow(act.getApplicationContext());
            Toast.makeText(act, "📥 " + qty + " galon promosi ditarik dari "
                    + (cust.getName() != null ? cust.getName() : "pelanggan")
                    + " — tercatat & tersinkron.", Toast.LENGTH_LONG).show();
            if (onSaved != null) onSaved.run();
        } else {
            Toast.makeText(act, "Gagal mencatat penarikan galon", Toast.LENGTH_LONG).show();
        }
    }
}
