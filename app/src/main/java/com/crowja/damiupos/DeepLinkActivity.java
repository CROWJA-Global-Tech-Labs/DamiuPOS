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
            openOrder(trxId);
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
