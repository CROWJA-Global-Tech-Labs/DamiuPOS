package com.crowja.damiupos;

import android.app.Activity;
import android.text.InputType;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.crowja.damiupos.db.CustomerDao;
import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.TransactionDao;
import com.crowja.damiupos.db.UserDao;
import com.crowja.damiupos.model.Customer;
import com.crowja.damiupos.model.Transaction;
import com.crowja.damiupos.sync.SyncEngine;
import com.crowja.damiupos.sync.SyncScheduler;

/**
 * "Tarik Galon & Hapus Pelanggan": menarik galon depot yang masih beredar di pelanggan, dengan
 * opsi SEKALIAN menghapus pelanggannya — dipakai untuk pelanggan yang sudah dianggap tidak aktif
 * lagi (mis. selesai Daftar Kunjungan Urgent tanpa hasil, atau Pelanggan Promosi yang tak kunjung
 * konversi): satu tindakan menutup urusan botol YANG BEREDAR sekaligus membersihkan rosternya,
 * tanpa perlu berpindah ke Detail Pelanggan untuk menghapus terpisah.
 *
 * <p>Dicatat sebagai transaksi KEMBALI: stok fisik depot bertambah dan "Galon Dipinjam" pelanggan
 * berkurang. Jumlah default = galon yang sedang dipinjam, dibaca lewat
 * {@link CustomerDao#getByIdMerged(long)} supaya saldonya GABUNGAN lintas perangkat — pada salinan
 * hasil sinkron yang tak membawa transaksi lokal, saldo lokal selalu 0 dan kolomnya akan selalu
 * terisi 0.
 *
 * <p><b>Pembukuan promo tetap dijaga, dan itu sebabnya penarikan bisa jadi DUA baris.</b> Sisa
 * galon promosi (diberikan − sudah ditarik) punya arti sendiri: ia membatasi tawaran tarik-ulang
 * lintas perangkat dan dipakai metrik konversi Pelanggan Promosi. Maka saat menarik, jumlahnya
 * dipecah otomatis:
 * <ul>
 *   <li>sebanyak-banyaknya sisa promo dicatat bermarker {@link #MARKER_PROMO};</li>
 *   <li>selebihnya bermarker {@link #MARKER} biasa.</li>
 * </ul>
 * Kalau salah satu bagian nol, hanya satu baris yang dibuat. Menggabungkan semuanya ke satu marker
 * akan merusak salah satu buku: pakai marker promo untuk semua → "sudah ditarik" bisa melebihi yang
 * pernah diberikan; pakai marker biasa untuk semua → botol promosi terlihat masih beredar selamanya.
 *
 * <p>Menarik LEBIH banyak dari yang tercatat tidak diblokir, hanya dikonfirmasi: pencatatan
 * pinjam-kembali di lapangan memang bisa tertinggal, dan memblokirnya akan memaksa staf
 * membatalkan penarikan yang sebenarnya sah.
 *
 * <p><b>Menghapus pelanggan HANYA untuk admin</b> (cermin {@code User.canDeleteCustomer()} yang
 * dipakai gerbang "Hapus" di Detail Pelanggan) — staf lapangan lain tetap bisa menarik galon tanpa
 * opsi hapus. Checkbox-nya default TAK TERCENTANG: menghapus pelanggan itu ireversibel (menghapus
 * seluruh riwayat transaksi lokalnya juga — sama seperti peringatan di Detail Pelanggan), jadi
 * harus dipilih sengaja, bukan kebiasaan default.
 *
 * <p><b>Urutan sinkron-lalu-hapus itu WAJIB, bukan sekadar rapi.</b> Skema lokal customers→
 * transactions punya {@code ON DELETE CASCADE}: menghapus baris pelanggan menghapus SELURUH baris
 * transaksinya di perangkat ini juga, TERMASUK transaksi KEMBALI yang baru saja disimpan di atas.
 * Kalau pelanggan dihapus sebelum transaksi itu sempat terkirim, "galon ditarik"-nya lenyap dari
 * server sama sekali — depot terlihat menerima botol yang sebenarnya tidak pernah tercatat. Maka
 * saat checkbox dicentang, dilakukan sinkron BLOKING dulu (bukan sekadar memicu {@link
 * SyncScheduler#syncNow}) dan penghapusan hanya jalan kalau sinkron itu berhasil; kalau gagal
 * (offline/gagal jaringan), galonnya tetap tercatat tapi pelanggan TIDAK dihapus — staf tinggal
 * mengulang penghapusan lewat Detail Pelanggan begitu sinyal kembali.
 */
public final class TarikGalon {

    /** Marker penarikan umum. */
    public static final String MARKER = "[TARIK GALON]";

    /** Marker penarikan yang membebani jatah PROMOSI. Nilainya HARUS tetap sama dengan yang dibaca
     *  TransactionDao.getPromoPulledGalon dan SyncController — mengubahnya membuat seluruh riwayat
     *  penarikan promo lama tak terhitung lagi. */
    public static final String MARKER_PROMO = TransactionDao.PROMO_PULL_MARKER;

    private TarikGalon() {}

    /** Judul menu yang mencerminkan kemampuan user saat ini — admin melihat opsi hapus di judulnya
     *  sejak dari menu, non-admin (yang tak akan pernah melihat checkbox-nya) tidak dijanjikan hal
     *  yang tak bisa mereka lakukan. */
    public static String menuLabel(Activity act) {
        return UserDao.isCurrentUserAdmin(act) ? "📥 Tarik Galon & Hapus Pelanggan" : "📥 Tarik Galon";
    }

    /** Muat saldo + sisa promo pelanggan di background lalu tampilkan dialog penarikan. */
    public static void show(Activity act, long customerId, Runnable onSaved) {
        DatabaseHelper dbh = DatabaseHelper.getInstance(act);
        TransactionDao tdao = new TransactionDao(dbh);
        CustomerDao cdao = new CustomerDao(dbh);
        new Thread(() -> {
            Customer cust = cdao.getByIdMerged(customerId);
            // Kedua sisi WAJIB lintas perangkat: akuisisi promo dicatat di HP marketing dan
            // penarikannya bisa dari perangkat lain — hitungan lokal saja membuat sisa promo salah.
            int promo = cdao.mergedPromoGalon(customerId);
            int pulled = cdao.mergedPromoPulledGalon(customerId);
            boolean canDelete = UserDao.isCurrentUserAdmin(act);
            act.runOnUiThread(() -> {
                if (act.isFinishing() || act.isDestroyed()) return;
                render(act, tdao, cdao, cust, Math.max(0, promo - pulled), canDelete, onSaved);
            });
        }).start();
    }

    private static void render(Activity act, TransactionDao tdao, CustomerDao cdao, Customer cust,
                               int promoRemaining, boolean canDelete, Runnable onSaved) {
        if (cust == null) {
            Toast.makeText(act, "Pelanggan tidak ditemukan", Toast.LENGTH_SHORT).show();
            return;
        }
        final int saldo = Math.max(0, cust.getSaldoGalon());

        StringBuilder msg = new StringBuilder();
        msg.append("Pelanggan: ").append(cust.getName() != null ? cust.getName() : "-").append('\n');
        msg.append("💧 Galon dipinjam tercatat: ").append(saldo).append(" galon\n");
        if (promoRemaining > 0) {
            msg.append("🎁 Di antaranya galon promosi: ").append(promoRemaining).append(" galon\n");
        }
        msg.append('\n');
        msg.append("Galon yang ditarik dicatat sebagai Galon Kembali: stok depot bertambah dan "
                + "galon dipinjam pelanggan berkurang.");
        if (promoRemaining > 0) {
            msg.append(" Bagian yang termasuk jatah promosi dicatat terpisah otomatis — tak perlu "
                    + "dipilih sendiri.");
        }
        if (saldo == 0) {
            msg.append("\n\n⚠️ Menurut catatan, pelanggan ini tidak sedang meminjam galon. "
                    + "Kalau tetap ada galon yang ditarik, isi jumlahnya sendiri.");
        }

        final EditText etQty = new EditText(act);
        etQty.setInputType(InputType.TYPE_CLASS_NUMBER);
        etQty.setHint("Jumlah galon ditarik");
        etQty.setText(String.valueOf(saldo));                 // default = yang sedang dipinjam
        etQty.setSelection(etQty.getText().length());

        LinearLayout wrap = new LinearLayout(act);
        wrap.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(20 * act.getResources().getDisplayMetrics().density);
        wrap.setPadding(pad, 0, pad, 0);
        wrap.addView(etQty, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // Checkbox hapus HANYA untuk admin — staf lain tak pernah melihatnya sama sekali, bukan
        // sekadar dinonaktifkan (menyembunyikan lebih jujur daripada menunjukkan opsi yang ditolak).
        final CheckBox cbDelete = canDelete ? new CheckBox(act) : null;
        if (cbDelete != null) {
            cbDelete.setText("Sekaligus hapus pelanggan ini setelah galon ditarik");
            cbDelete.setChecked(false);   // default AMAN: hapus itu ireversibel, harus dipilih sengaja
            TextView warn = new TextView(act);
            warn.setText("Menghapus pelanggan juga menghapus seluruh riwayat transaksinya di "
                    + "perangkat ini (sama seperti hapus dari Detail Pelanggan) dan tidak bisa "
                    + "dibatalkan.");
            warn.setTextSize(11);
            warn.setTextColor(0xFF9E9E9E);
            wrap.addView(cbDelete, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            wrap.addView(warn, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        }

        AlertDialog dialog = new AlertDialog.Builder(act)
                .setTitle(canDelete ? "📥 Tarik Galon & Hapus Pelanggan" : "📥 Tarik Galon")
                .setMessage(msg)
                .setView(wrap)
                .setPositiveButton("YA, TARIK GALON", null)   // di-override agar tak auto-dismiss saat invalid
                .setNegativeButton("Batal", null)
                .create();
        // Label tombol mengikuti centang, supaya jelas di titik tekan apa yang akan terjadi.
        if (cbDelete != null) {
            cbDelete.setOnCheckedChangeListener((btn, checked) -> {
                android.widget.Button pos = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
                if (pos != null) pos.setText(checked ? "TARIK & HAPUS PELANGGAN" : "YA, TARIK GALON");
            });
        }
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
                    boolean deleteAfter = cbDelete != null && cbDelete.isChecked();
                    // Lebih-tarik hanya DIKONFIRMASI, tidak diblokir — lihat alasannya di docblock.
                    if (saldo > 0 && qty > saldo) {
                        new AlertDialog.Builder(act)
                                .setTitle("Lebih dari yang tercatat")
                                .setMessage("Tercatat cuma " + saldo + " galon dipinjam, tapi kamu menarik "
                                        + qty + ". Saldo pelanggan bisa jadi minus. Lanjutkan?")
                                .setPositiveButton("Lanjutkan", (d2, w2) -> {
                                    dialog.dismiss();
                                    confirmThenSave(act, tdao, cdao, cust, qty, promoRemaining, deleteAfter, onSaved);
                                })
                                .setNegativeButton("Batal", null)
                                .show();
                        return;
                    }
                    dialog.dismiss();
                    confirmThenSave(act, tdao, cdao, cust, qty, promoRemaining, deleteAfter, onSaved);
                }));
        dialog.show();
    }

    /** Gerbang konfirmasi KEDUA khusus saat penghapusan diminta — menggabungkan dua tindakan
     *  (tarik + hapus) dalam satu klik pantas dijaga ekstra, meski checkbox-nya sendiri sudah
     *  eksplisit dicentang. Cermin nada peringatan confirmDelete() di Detail Pelanggan. */
    private static void confirmThenSave(Activity act, TransactionDao tdao, CustomerDao cdao, Customer cust,
                                        int qty, int promoRemaining, boolean deleteAfter, Runnable onSaved) {
        if (!deleteAfter) {
            save(act, tdao, cdao, cust, qty, promoRemaining, false, onSaved);
            return;
        }
        new AlertDialog.Builder(act)
                .setTitle("Konfirmasi Hapus")
                .setMessage("Setelah " + qty + " galon ditarik, \""
                        + (cust.getName() != null ? cust.getName() : "pelanggan ini")
                        + "\" akan DIHAPUS PERMANEN beserta riwayat transaksinya. Yakin?")
                .setPositiveButton("YA, HAPUS", (d, w) ->
                        save(act, tdao, cdao, cust, qty, promoRemaining, true, onSaved))
                .setNegativeButton("Batal", null)
                .show();
    }

    /**
     * Simpan KEMBALI (created_by_name distempel DAO; KEMBALI tak pernah diantrikan), lalu — bila
     * diminta — hapus pelanggannya SETELAH transaksi itu berhasil disinkron. Semuanya di background
     * thread: penyisipan lokal, sinkron jaringan (kalau deleteAfter), dan penghapusan.
     *
     * Dipecah dua baris bila penarikan melampaui sisa promo — alasan lengkapnya di docblock kelas.
     */
    private static void save(Activity act, TransactionDao tdao, CustomerDao cdao, Customer cust, int qty,
                             int promoRemaining, boolean deleteAfter, Runnable onSaved) {
        new Thread(() -> {
            int promoPart = Math.max(0, Math.min(qty, promoRemaining));
            int plainPart = qty - promoPart;
            int written = 0;

            if (promoPart > 0) {
                written += insert(tdao, cust, promoPart,
                        MARKER_PROMO + " " + promoPart + " galon ditarik dari pelanggan promosi") ? promoPart : 0;
            }
            if (plainPart > 0) {
                written += insert(tdao, cust, plainPart,
                        MARKER + " " + plainPart + " galon ditarik dari pelanggan") ? plainPart : 0;
            }

            if (written <= 0) {
                act.runOnUiThread(() -> {
                    if (act.isFinishing() || act.isDestroyed()) return;
                    Toast.makeText(act, "Gagal menyimpan penarikan galon", Toast.LENGTH_SHORT).show();
                });
                return;
            }

            String who = cust.getName() != null ? cust.getName() : "pelanggan";
            String pullMsg = promoPart > 0 && plainPart > 0
                    ? "📥 " + written + " galon ditarik dari " + who
                      + " (" + promoPart + " promosi + " + plainPart + " biasa)"
                    : "📥 " + written + " galon ditarik dari " + who;

            if (!deleteAfter) {
                SyncScheduler.syncNow(act.getApplicationContext());
                act.runOnUiThread(() -> {
                    if (act.isFinishing() || act.isDestroyed()) return;
                    Toast.makeText(act, pullMsg, Toast.LENGTH_LONG).show();
                    if (onSaved != null) onSaved.run();
                });
                return;
            }

            // WAJIB sinkron BLOKING dulu (bukan syncNow yang cuma menjadwalkan) — lihat penjelasan
            // ON DELETE CASCADE di docblock kelas. Penghapusan hanya jalan kalau ini benar berhasil.
            SyncEngine.Result res;
            try {
                res = new SyncEngine(act.getApplicationContext()).sync();
            } catch (Exception e) {
                res = null;
            }
            boolean synced = res != null && res.ok;

            if (!synced) {
                act.runOnUiThread(() -> {
                    if (act.isFinishing() || act.isDestroyed()) return;
                    Toast.makeText(act, pullMsg + ". Pelanggan BELUM dihapus — perangkat sedang "
                            + "offline atau sinkron gagal. Coba hapus lagi dari Detail Pelanggan "
                            + "setelah tersambung internet.", Toast.LENGTH_LONG).show();
                    if (onSaved != null) onSaved.run();   // galon-nya tetap masuk daftar terbaru
                });
                return;
            }

            cdao.delete(cust.getId());
            SyncScheduler.syncNow(act.getApplicationContext());   // dorong tombstone penghapusan segera
            act.runOnUiThread(() -> {
                if (act.isFinishing() || act.isDestroyed()) return;
                Toast.makeText(act, pullMsg + ". " + who + " dihapus.", Toast.LENGTH_LONG).show();
                if (onSaved != null) onSaved.run();
            });
        }).start();
    }

    private static boolean insert(TransactionDao tdao, Customer cust, int qty, String note) {
        Transaction k = new Transaction();
        k.setCustomerId(cust.getId());
        k.setType(Transaction.TYPE_KEMBALI);
        k.setJumlahGalon(qty);
        k.setTotalHarga(0);
        k.setCatatan(note);
        return tdao.insert(k) > 0;
    }
}
