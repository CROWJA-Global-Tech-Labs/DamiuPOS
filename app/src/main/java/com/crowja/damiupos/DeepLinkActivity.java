package com.crowja.damiupos;

import android.app.Activity;
import android.app.TaskStackBuilder;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import com.crowja.damiupos.db.CustomerDao;
import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.model.Transaction;

/**
 * Menangani deep link dari halaman publik pelanggan: {@code damiupos://transaksi?customer=<uuid>}.
 * Membuka form "Transaksi Baru" atas nama pelanggan tersebut (JUAL). UUID di link = customer_uuid
 * server; di HP ini di-resolve ke _id lokal. Tanpa UI (langsung route + finish).
 */
public class DeepLinkActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String uuid = null;
        Uri data = getIntent() != null ? getIntent().getData() : null;
        if (data != null) {
            uuid = data.getQueryParameter("customer");
        }

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
            Intent home = new Intent(this, MainActivity.class);
            home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(home);
        }
        finish();
    }
}
