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

/**
 * Menangani deep link aplikasi (tanpa UI — langsung route + finish):
 * <ul>
 *   <li>{@code damiupos://transaksi?customer=<uuid>} — dari halaman publik pelanggan: buka form
 *       "Transaksi Baru" atas nama pelanggan tersebut (JUAL).</li>
 *   <li>{@code damiupos://pesanan?trx=<uuid>} — link pesanan (server {@code /pesanan/{uuid}}, dipakai
 *       operator/agen untuk menyebut pesanan di grup eskalasi): buka pesanan itu di Antrian Delivery,
 *       berjalan maupun tertunda.</li>
 * </ul>
 * UUID di link = sync_uuid server; di HP ini di-resolve ke _id lokal.
 */
public class DeepLinkActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Uri data = getIntent() != null ? getIntent().getData() : null;
        if (data != null && "pesanan".equalsIgnoreCase(data.getHost())) {
            openOrder(data.getQueryParameter("trx"));
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

    private void openOrder(String uuid) {
        long trxId = -1;
        if (uuid != null && !uuid.isEmpty()) {
            trxId = new TransactionDao(DatabaseHelper.getInstance(this)).getIdBySyncUuid(uuid);
        }

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
}
