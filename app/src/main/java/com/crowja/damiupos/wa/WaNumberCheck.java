package com.crowja.damiupos.wa;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.crowja.damiupos.db.CustomerDao;
import com.crowja.damiupos.db.SettingsDao;
import com.crowja.damiupos.sync.SyncScheduler;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Cek nomor pelanggan ke WhatsApp lalu TANDAI BERMASALAH bila nomornya tak terdaftar.
 *
 * <p>Dijalankan setelah pelanggan disimpan (tambah maupun edit) di HP yang mendaftarkannya — lihat
 * {@link WaGateway#check}. Simpan TIDAK pernah diblokir: cek Opsi A butuh WhatsApp muncul sesaat
 * dan bisa memakan puluhan detik, jadi memblokir tombol Simpan akan menghentikan pendaftaran
 * pelanggan di lapangan setiap kali sinyal/Aksesibilitas bermasalah. Pelanggan tersimpan dulu,
 * bendera ⚠️ menyusul — dan bendera itu tersinkron ke dashboard lewat kolom {@code issue_*} biasa.</p>
 *
 * <p><b>Hanya vonis positif yang menandai.</b> Cuma {@link WaGateway#NO_WHATSAPP} (dialog "nomor
 * tidak sah" benar-benar terlihat) yang menghasilkan bendera. Aksesibilitas mati, slot sibuk,
 * WhatsApp tak terpasang, atau kedaluwarsa = TAK KONKLUSIF → pelanggan dibiarkan bersih. Menuduh
 * nomor yang sebenarnya sah jauh lebih merugikan daripada melewatkan satu nomor mati.</p>
 */
public final class WaNumberCheck {

    private WaNumberCheck() {}

    /** Kategori issue_flags yang dipakai (katalog bersama web: Customer::ISSUE_LABELS). */
    private static final String FLAG_PHONE = "phone";

    /** Satu antrean berurutan: WhatsApp cuma bisa satu chat di depan pada satu waktu, dan tiap cek
     *  menahan slot {@link WaAutoSendService}. Paralel = saling membatalkan. */
    private static final ExecutorService POOL = Executors.newSingleThreadExecutor();

    /**
     * Cek semua {@code phones} milik satu pelanggan di latar belakang; bila ada yang terbukti tak
     * punya WhatsApp, tandai pelanggan bermasalah (kategori "phone") lalu dorong sinkron.
     *
     * @param customerId id lokal pelanggan yang baru disimpan
     * @param phones     nomor yang perlu diperiksa (sudah ternormalisasi); kosong → no-op
     */
    public static void checkAndFlag(Context context, long customerId, List<String> phones) {
        if (customerId <= 0 || phones == null || phones.isEmpty()) return;
        final Context ctx = context.getApplicationContext();
        final List<String> targets = new ArrayList<>(phones);

        POOL.execute(() -> {
            List<String> dead = new ArrayList<>();
            for (String phone : targets) {
                if (phone == null || phone.trim().isEmpty()) continue;
                String verdict;
                try {
                    verdict = WaGateway.check(ctx, phone);
                } catch (Throwable t) {
                    continue;   // gangguan di sisi kita bukan vonis atas nomor pelanggan
                }
                if (WaGateway.NO_WHATSAPP.equals(verdict)) dead.add(phone);
            }
            if (dead.isEmpty()) return;

            String note = "Nomor tidak terdaftar di WhatsApp: " + android.text.TextUtils.join(", ", dead)
                    + " (hasil cek otomatis saat pelanggan disimpan).";
            try {
                com.crowja.damiupos.db.DatabaseHelper helper =
                        com.crowja.damiupos.db.DatabaseHelper.getInstance(ctx);
                CustomerDao dao = new CustomerDao(helper);
                String reporter = new SettingsDao(helper).getCurrentUserName();
                dao.markProblematic(customerId, FLAG_PHONE, note,
                        reporter != null && !reporter.trim().isEmpty() ? reporter : "Cek WhatsApp otomatis");
                SyncScheduler.syncNow(ctx);
            } catch (Throwable ignored) {
                return;
            }

            new Handler(Looper.getMainLooper()).post(() ->
                    Toast.makeText(ctx, "⚠️ " + android.text.TextUtils.join(", ", dead)
                            + " tidak punya WhatsApp — pelanggan ditandai bermasalah.",
                            Toast.LENGTH_LONG).show());
        });
    }
}
