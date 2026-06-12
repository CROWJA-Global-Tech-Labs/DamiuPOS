package com.crowja.damiupos;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.crowja.damiupos.db.CustomerDao;
import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.ProductDao;
import com.crowja.damiupos.db.SettingsDao;
import com.crowja.damiupos.db.TransactionDao;
import com.crowja.damiupos.demo.DemoDataHelper;
import com.crowja.damiupos.model.Customer;
import com.crowja.damiupos.model.Product;
import com.crowja.damiupos.model.Transaction;


/**
 * Debug-only helper: seed demo data + complete wizard in one shot.
 * Dipanggil via {@code adb shell am start -n com.crowja.damiupos/.DebugSeedActivity}
 * untuk menyiapkan app supaya screenshot Play Store terlihat realistis.
 *
 * <p>Tidak ada di build release (sourceSet debug saja).
 */
public class DebugSeedActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        DatabaseHelper dbHelper = DatabaseHelper.getInstance(this);
        SettingsDao settings = new SettingsDao(dbHelper);
        CustomerDao customerDao = new CustomerDao(dbHelper);
        ProductDao productDao = new ProductDao(dbHelper);
        TransactionDao trxDao = new TransactionDao(dbHelper);

        // --- 1. Depot info + wizard flag ---
        settings.setDepotName("Depot Air Minum Tirta Segar");
        settings.setDepotAddress("Jl. Melati No. 12, Boyolali");
        settings.setDepotPhone("081234567890");
        settings.setDefaultOngkir(2000);
        settings.setFollowupDays(5);
        settings.setStockAlert(30);
        settings.setHargaBotolGalon(35000);
        settings.setPointsEnabled(true);
        settings.setPointsPerAmount(10000);
        settings.setPointsRewardThreshold(100);
        settings.setWizardCompleted(true);

        // --- 2. Products ---
        long pAir = productDao.insert(makeProduct("Galon Air Mineral", 6000));
        productDao.insert(makeProduct("Galon RO", 7000));
        productDao.insert(makeProduct("Galon Alkali", 10000));

        // --- 3. Customers ---
        long[] customerIds = new long[DEMO_CUSTOMERS.length];
        for (int i = 0; i < DEMO_CUSTOMERS.length; i++) {
            String[] row = DEMO_CUSTOMERS[i];
            Customer c = new Customer(row[0], row[1], row[2]);
            customerIds[i] = customerDao.insert(c);
        }

        // --- 4. Transactions (hari ini) ---
        // Buat beberapa transaksi JUAL + KEMBALI supaya dashboard + list punya isi
        seedJual(trxDao, customerIds[0], pAir, 3, 6000);
        seedJual(trxDao, customerIds[1], pAir, 2, 6000);
        seedJual(trxDao, customerIds[2], pAir, 5, 6000);
        seedJual(trxDao, customerIds[3], pAir, 1, 6000);
        seedJual(trxDao, customerIds[4], pAir, 4, 6000);
        seedKembali(trxDao, customerIds[0], pAir, 3);
        seedKembali(trxDao, customerIds[2], pAir, 2);

        // --- 5. Follow-up + Peta demo data ---
        // DemoDataHelper seeds 6 customers WITH koordinat Boyolali + transaksi
        // JUAL 8–70 hari lalu → semuanya jadi follow-up candidate dan muncul
        // sebagai pin di Peta Follow Up. Ini yang dibutuhkan untuk test peta.
        int[] demo = new DemoDataHelper(dbHelper).generateDetailed();

        Toast.makeText(this,
                "Demo data seeded ✓  (peta: " + demo[0] + " pelanggan, "
                        + demo[1] + " transaksi)",
                Toast.LENGTH_LONG).show();

        // Langsung masuk MainActivity
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }

    private Product makeProduct(String name, double price) {
        return new Product(name, price, price * 0.6);
    }

    private void seedJual(TransactionDao dao, long customerId, long productId,
                          int qty, double price) {
        Transaction t = new Transaction();
        t.setCustomerId(customerId);
        t.setProductId(productId);
        t.setType(Transaction.TYPE_JUAL);
        t.setJumlahGalon(qty);
        t.setHargaPerGalon(price);
        t.setTotalHarga(qty * price);
        t.setOngkir(0);
        t.setOngkirType(Transaction.ONGKIR_NONE);
        t.setGalonOwnership(Transaction.OWNERSHIP_PINJAM);
        dao.insert(t);
    }

    private void seedKembali(TransactionDao dao, long customerId, long productId, int qty) {
        Transaction t = new Transaction();
        t.setCustomerId(customerId);
        t.setProductId(productId);
        t.setType(Transaction.TYPE_KEMBALI);
        t.setJumlahGalon(qty);
        t.setHargaPerGalon(0);
        t.setTotalHarga(0);
        t.setOngkirType(Transaction.ONGKIR_NONE);
        t.setGalonOwnership(Transaction.OWNERSHIP_PINJAM);
        dao.insert(t);
    }

    private static final String[][] DEMO_CUSTOMERS = new String[][]{
            {"Ibu Sari Wulandari", "081234567001", "Jl. Mawar No. 15, Boyolali"},
            {"Pak Budi Santoso",   "081234567002", "Jl. Kenanga No. 8, Boyolali"},
            {"Warung Bu Tini",     "081234567003", "Jl. Pasar Baru No. 22, Boyolali"},
            {"Rumah Makan Sederhana", "081234567004", "Jl. Raya Solo Km 5, Boyolali"},
            {"Ibu Retno Dewi",     "081234567005", "Jl. Anggrek No. 3, Boyolali"},
            {"Pak Hendra Wijaya",  "081234567006", "Jl. Cendrawasih No. 10, Boyolali"},
            {"Kos Putri Melati",   "081234567007", "Jl. Kampus No. 7, Boyolali"},
            {"Kantor PT Maju Jaya","081234567008", "Jl. Industri No. 45, Boyolali"},
    };
}
