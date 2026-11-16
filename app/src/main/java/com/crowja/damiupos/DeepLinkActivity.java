package com.crowja.damiupos;

import android.app.Activity;
import android.app.TaskStackBuilder;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import com.crowja.damiupos.db.CustomerDao;
import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.TransactionDao;
import com.crowja.damiupos.model.Transaction;

import java.util.List;

/**
 * Menangani deep link aplikasi (tanpa UI — langsung route + finish):
 * <ul>
 *   <li>{@code damiupos://transaksi?customer=<uuid>} — dari halaman publik pelanggan: buka form
 *       "Transaksi Baru" atas nama pelanggan tersebut (JUAL).</li>
 *   <li>{@code damiupos://pesanan?trx=<uuid>} — link pesanan (server {@code /pesanan/{uuid}}, dipakai
 *       operator/agen untuk menyebut pesanan di grup eskalasi): buka pesanan itu di Antrian Delivery,
 *       berjalan maupun tertunda.</li>
 *   <li>{@code https://order.airfrez.com/tracking/<token>} — link lacak publik yang sama yang dikirim
 *       ke pelanggan (lihat DeliveryQueueActivity#trackLinkOrToast): saat staff menekannya balik, mis.
 *       dari komplain/chat WA yang menempel link ini, buka pesanan terkait di Antrian Delivery sama
 *       seperti link pesanan di atas.</li>
 * </ul>
 * UUID/token di link diresolve ke _id lokal (sync_uuid atau delivery_token).
 */
public class DeepLinkActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Pengisian (Day-time) tak menangani pesanan/transaksi: link apa pun mendarat di layar
        // panduan isi galon, bukan Antrian Delivery / Transaksi Baru (deep link melewati MainActivity).
        if (com.crowja.damiupos.db.UserDao.isCurrentUserPengisian(this)) {
            startActivity(new Intent(this, PengisianActivity.class));
            finish();
            return;
        }

        Uri data = getIntent() != null ? getIntent().getData() : null;
        String trackingToken = trackingToken(data);
        if (trackingToken != null) {
            long trxId = new TransactionDao(DatabaseHelper.getInstance(this)).getIdByDeliveryToken(trackingToken);
            openOrder(trxId);
        } else if (data != null && "pesanan".equalsIgnoreCase(data.getHost())) {
            long trxId = -1;
            String uuid = data.getQueryParameter("trx");
            if (uuid != null && !uuid.isEmpty()) {
                trxId = new TransactionDao(DatabaseHelper.getInstance(this)).getIdBySyncUuid(uuid);
            }
            openPesanan(trxId, uuid);
        } else {
            openNewTransaction(data != null ? data.getQueryParameter("customer") : null);
        }
        finish();
    }

    private void openNewTransaction(String uuid) {
        long customerId = -1;
        if (uuid != null && !uuid.isEmpty()) {
            customerId = new CustomerDao(DatabaseHelper.getInstance(this)).getIdBySyncUuid(uuid);
        }

        if (customerId > 0) {
            // Buka Transaksi Baru dgn MainActivity sebagai induk (tombol Back → beranda).
            Intent home = new Intent(this, MainActivity.class);
            Intent trx = new Intent(this, TransactionActivity.class);
            trx.putExtra("customer_id", customerId);
            trx.putExtra("type", Transaction.TYPE_JUAL);
            TaskStackBuilder.create(this).addNextIntent(home).addNextIntent(trx).startActivities();
        } else {
            Toast.makeText(this,
                    "Pelanggan tidak ditemukan di perangkat ini. Pastikan aplikasi sudah tersinkron.",
                    Toast.LENGTH_LONG).show();
            openHome();
        }
    }

    /**
     * Link /pesanan/{uuid} dari grup eskalasi (mis. [KOMPLAIN]). Pesanan sering milik HP LAIN (transaksi
     * tak dibagi antar-HP) atau sudah selesai — dulu keduanya berakhir "Pesanan tidak ditemukan" lalu
     * kembali ke beranda. Sekarang:
     * <ul><li>ada di HP ini &amp; masih berjalan → Antrian Delivery (fokus ke pesanan),</li>
     * <li>ada di HP ini &amp; sudah selesai → struknya,</li>
     * <li>tidak ada di HP ini → Chat Pesanan (dimuat dari server per uuid; detail + chat WA pelanggan).</li></ul>
     */
    private void openPesanan(long trxId, String uuid) {
        Transaction t = null;
        if (trxId > 0) {
            try { t = new TransactionDao(DatabaseHelper.getInstance(this)).getById(trxId); } catch (Throwable ignored) { }
        }
        boolean done = t != null && t.getDeliveryDoneAt() != null && !t.getDeliveryDoneAt().trim().isEmpty();
        if (t != null && !done) {
            openOrder(trxId);
            return;
        }
        Intent home = new Intent(this, MainActivity.class);
        if (t != null) {
            Intent rc = new Intent(this, ReceiptActivity.class).putExtra(ReceiptActivity.EXTRA_TRANSACTION_ID, trxId);
            TaskStackBuilder.create(this).addNextIntent(home).addNextIntent(rc).startActivities();
            return;
        }
        if (uuid != null && uuid.length() >= 30) {
            Intent chat = new Intent(this, ChatLogActivity.class)
                    .putExtra(ChatLogActivity.EXTRA_MODE, ChatLogActivity.MODE_ORDER)
                    .putExtra(ChatLogActivity.EXTRA_TRANSACTION_UUID, uuid)
                    .putExtra(ChatLogActivity.EXTRA_CUSTOMER_NAME, "");
            TaskStackBuilder.create(this).addNextIntent(home).addNextIntent(chat).startActivities();
            return;
        }
        openOrder(-1);
    }

    private void openOrder(long trxId) {
        if (trxId > 0) {
            // Antrian Delivery dgn MainActivity sebagai induk; pesanannya dibuka di sana
            // (DeliveryQueueActivity.EXTRA_FOCUS_TRX_ID).
            Intent home = new Intent(this, MainActivity.class);
            Intent queue = new Intent(this, DeliveryQueueActivity.class);
            queue.putExtra(DeliveryQueueActivity.EXTRA_FOCUS_TRX_ID, trxId);
            TaskStackBuilder.create(this).addNextIntent(home).addNextIntent(queue).startActivities();
        } else {
            Toast.makeText(this,
                    "Pesanan tidak ditemukan di perangkat ini. Pastikan aplikasi sudah tersinkron.",
                    Toast.LENGTH_LONG).show();
            openHome();
        }
    }

    private void openHome() {
        Intent home = new Intent(this, MainActivity.class);
        home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(home);
    }

    /** Ekstrak {@code <token>} dari path {@code /tracking/<token>}, atau null bila URI tidak
     *  cocok bentuk itu (mis. link damiupos://transaksi atau damiupos://pesanan). */
    private static String trackingToken(Uri data) {
        if (data == null) return null;
        List<String> segs = data.getPathSegments();
        if (segs != null && segs.size() >= 2 && "tracking".equals(segs.get(0))) {
            String token = segs.get(1);
            return token != null && !token.isEmpty() ? token : null;
        }
        return null;
    }
}
