package com.crowja.damiupos;

import android.app.Activity;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.SettingsDao;
import com.crowja.damiupos.db.TransactionDao;
import com.crowja.damiupos.db.UserDao;
import com.crowja.damiupos.model.Customer;
import com.crowja.damiupos.model.Transaction;
import com.crowja.damiupos.model.TransactionItem;
import com.crowja.damiupos.sync.SyncApi;
import com.crowja.damiupos.sync.SyncScheduler;
import com.crowja.damiupos.sync.SyncSettings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.Locale;

/**
 * "Ubah Lokasi Pengiriman": pindahkan tujuan "Kirim ke" order ANTRIAN ini ke salah satu lokasi
 * TERSIMPAN pelanggan (multi-alamat) — dipakai saat lokasi yang kepakai di order salah (mis. staf
 * salah pilih saat Transaksi Baru) atau pelanggan minta dikirim ke alamat lain miliknya. Hanya
 * relevan untuk pelanggan yang PUNYA >1 lokasi tersimpan (gerbang di pemanggil,
 * {@see DeliveryQueueActivity#showUbahMenu}) — pelanggan satu-lokasi tak ada yang bisa dipilih.
 *
 * <p>Item/harga/ongkir/galon kembali TIDAK disentuh — dikirim APA ADANYA (baris DB saat ini) ke
 * endpoint yang SAMA dengan "Ubah Pesanan" ({@see DeliveryEditDialog}, {@code POST /api/edit-requests}),
 * jadi server (yang menghormati {@code delivery_dest_*} HANYA bila kuncinya dikirim — lihat
 * {@code EditRequestController::store}) tidak menganggap ada perubahan lain selain tujuan kirim.
 * Server yang menentukan jalurnya: order MASIH di antrian (PENDING/TERTUNDA) → diterapkan LANGSUNG
 * + email laporan; selain itu (jarang — race, order selesai di HP lain sesaat sebelum submit) →
 * alur persetujuan lama (alasan WAJIB, yang dialog ini tak minta — staf melihat pesan errornya dan
 * mengulang lewat "Ubah Pesanan" bila itu terjadi, sama seperti edge case serupa di sana).</p>
 */
public final class DeliveryDestDialog {

    private DeliveryDestDialog() {}

    public static void show(final Activity act, final Transaction queueRow, final Customer customer) {
        if (act == null || queueRow == null || customer == null) return;
        if (!Transaction.TYPE_JUAL.equals(queueRow.getType())) return;
        final List<Customer.Location> locations = customer.getLocations();
        if (locations == null || locations.size() <= 1) return;   // tak ada pilihan lain

        final DatabaseHelper dbh = DatabaseHelper.getInstance(act);
        final SyncSettings cfg = new SyncSettings(new SettingsDao(dbh));
        if (!cfg.isEnrolled()) {
            toast(act, "Perangkat belum terhubung ke server — ubah lokasi perlu koneksi.");
            return;
        }

        // Baris antrian ringkas tak membawa item/tanggal lengkap → muat penuh (cermin DeliveryEditDialog),
        // items-nya dikirim APA ADANYA (tak diedit di sini) supaya endpoint bersama tak mengubah pesanan.
        final TransactionDao dao = new TransactionDao(dbh);
        final Transaction full = dao.getById(queueRow.getId());
        final Transaction t = full != null ? full : queueRow;
        if (t.getItems() == null || t.getItems().isEmpty()) {
            toast(act, "Transaksi tanpa rincian item — ubah lewat Dashboard.");
            return;
        }

        final int current = selectedIndex(t, locations);
        final String[] labels = new String[locations.size()];
        for (int i = 0; i < locations.size(); i++) {
            Customer.Location l = locations.get(i);
            labels[i] = (i == 0 ? "⭐ " : "") + safe(l.name)
                    + (l.lat != 0 || l.lng != 0
                        ? String.format(Locale.US, "  (%.5f, %.5f)", l.lat, l.lng)
                        : "  (koordinat belum diisi)");
        }
        final int[] choice = {current};

        new AlertDialog.Builder(act)
                .setTitle("Ubah Lokasi Pengiriman — " + safe(queueRow.getCustomerName()))
                .setSingleChoiceItems(labels, current, (d, w) -> choice[0] = w)
                .setPositiveButton("Pilih", (d, w) -> {
                    if (choice[0] == current) {
                        toast(act, "Lokasi tak berubah.");
                        return;
                    }
                    confirmAndSubmit(act, dbh, cfg, t, locations.get(choice[0]));
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    /** Lokasi yang sedang dipakai order ini: cocokkan nama dengan {@code delivery_dest_name}
     *  (persis, tanpa memandang huruf besar/kecil) — else lokasi UTAMA (indeks 0, dipakai implisit
     *  saat order belum pernah menyetel tujuan eksplisit). */
    private static int selectedIndex(Transaction t, List<Customer.Location> locations) {
        String dest = t.getDeliveryDestName();
        if (dest != null && !dest.trim().isEmpty()) {
            for (int i = 0; i < locations.size(); i++) {
                Customer.Location l = locations.get(i);
                if (l.name != null && l.name.trim().equalsIgnoreCase(dest.trim())) return i;
            }
        }
        return 0;
    }

    private static void confirmAndSubmit(Activity act, DatabaseHelper dbh, SyncSettings cfg,
                                         Transaction t, Customer.Location dest) {
        new AlertDialog.Builder(act)
                .setTitle("Ubah Lokasi Pengiriman?")
                .setMessage("Kirim order “" + safe(t.getCustomerName()) + "” ke “"
                        + safe(dest.name) + "”? Staf pengiriman akan diarahkan ke lokasi baru ini.")
                .setPositiveButton("Ya, Ubah", (d, w) -> submit(act, dbh, cfg, t, dest))
                .setNegativeButton("Batal", null)
                .show();
    }

    private static void submit(final Activity act, final DatabaseHelper dbh, final SyncSettings cfg,
                               final Transaction t, final Customer.Location dest) {
        final AlertDialog progress = new AlertDialog.Builder(act)
                .setMessage("Mengubah lokasi pengiriman…")
                .setCancelable(false)
                .show();
        new Thread(() -> {
            String errMsg = null;
            String okMsg = null;
            try {
                final TransactionDao dao = new TransactionDao(dbh);
                String trxUuid = dao.getSyncUuidById(t.getId());
                if (trxUuid == null || trxUuid.isEmpty()) {
                    SyncScheduler.syncNow(act.getApplicationContext());
                    errMsg = "Transaksi belum tersinkron ke server. Coba lagi sebentar.";
                } else {
                    final SettingsDao sdao = new SettingsDao(dbh);
                    final long uid = sdao.getCurrentUserId();
                    final String uname = sdao.getCurrentUserName();

                    int kembaliNow = 0;
                    if (t.getCustomerId() > 0 && t.getTanggal() != null && !t.getTanggal().isEmpty()) {
                        kembaliNow = dao.getReturnedGalonForSale(t.getCustomerId(), t.getTanggal());
                    }

                    JSONObject body = new JSONObject();
                    body.put("transaction_uuid", trxUuid);
                    if (t.getEditedAt() != null && !t.getEditedAt().isEmpty()) {
                        body.put("base_edited_at", t.getEditedAt());
                    }
                    // Item APA ADANYA — endpoint edit membaca ini sebagai "pesanan hasil akhir", jadi
                    // baris yang tak diubah wajib disertakan utuh, bukan dikosongkan.
                    JSONArray arr = new JSONArray();
                    for (TransactionItem it : t.getItems()) {
                        JSONObject o = new JSONObject();
                        o.put("name", it.productName != null && !it.productName.isEmpty() ? it.productName : "Galon");
                        o.put("qty", it.jumlah);
                        o.put("price", it.hargaPerGalon);
                        if (it.productId > 0) o.put("pid", String.valueOf(it.productId));
                        arr.put(o);
                    }
                    body.put("items", arr);
                    body.put("jual_return_qty", Math.max(0, kembaliNow));
                    // Kunci delivery_dest_* SELALU disertakan di sini (beda dari "Ubah Pesanan" yang
                    // tak pernah mengirimnya) — server memakai array_key_exists untuk membedakan
                    // "tak disentuh" dari "diubah" (lihat docblock kelas).
                    body.put("delivery_dest_name", dest.name != null ? dest.name : "");
                    body.put("delivery_dest_lat", dest.lat);
                    body.put("delivery_dest_lng", dest.lng);
                    if (uname != null && !uname.isEmpty()) body.put("requester_name", uname);
                    if (uid > 0) {
                        String reqUuid = new UserDao(dbh).getSyncUuidById(uid);
                        if (reqUuid != null && !reqUuid.isEmpty()) body.put("requester_staff_uuid", reqUuid);
                    }
                    if (t.getCustomerName() != null) body.put("customer_name", t.getCustomerName());

                    JSONObject r = new SyncApi(cfg).proposeTrxEdit(body);
                    boolean applied = r.optBoolean("applied", false);
                    okMsg = r.optString("message", applied
                            ? "Lokasi pengiriman diperbarui & tersinkron ke semua perangkat."
                            : "Pengajuan ubah lokasi dikirim untuk persetujuan.");
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
                try { if (progress.isShowing()) progress.dismiss(); } catch (Exception ignored) { }
                if (act.isFinishing() || act.isDestroyed()) return;
                Toast.makeText(act, fOk != null ? fOk : fErr, Toast.LENGTH_LONG).show();
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

    private static String safe(String s) {
        return s != null && !s.isEmpty() ? s : "Pelanggan";
    }

    private static void toast(Activity act, String msg) {
        Toast.makeText(act, msg, Toast.LENGTH_LONG).show();
    }
}
