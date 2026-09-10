package com.crowja.damiupos.wa;

import android.Manifest;
import android.content.ContentProviderOperation;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;

import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Pastikan sebuah nomor pelanggan ADA di kontak HP, supaya WhatsApp mau membuka chat-nya langsung.
 *
 * <p>KENAPA INI PERLU (diuji langsung di perangkat, SM-S928B + WhatsApp 2.26.32.83):
 * extra {@code "jid"} pada intent share HANYA dihormati WhatsApp bila nomornya TERSIMPAN sebagai
 * kontak di HP. Nomor yang belum tersimpan diabaikan dan WhatsApp jatuh ke layar "Kirim ke…",
 * memaksa staf memilih kontak satu per satu. Bukti uji:
 * <pre>
 *   085876302644 (tersimpan "Frez Ngemplak") → Conversation + MediaComposer, penerima terisi
 *   085800430303 (belum tersimpan)           → "Kirim ke…" (ExternalShareAlias)
 *   085800430303 SESUDAH disimpan            → Conversation + MediaComposer, penerima terisi
 * </pre>
 * Kontak yang baru disisipkan langsung dikenali WhatsApp — TIDAK perlu menunggu sinkronisasi.
 *
 * <p>Nomor yang SUDAH ada (dalam bentuk apa pun: 08…, +62…, 62…) tak pernah digandakan: pencarian
 * memakai PhoneLookup, yang mencocokkan nomor secara longgar persis seperti aplikasi Telepon.
 */
public final class WaContactEnsure {

    private WaContactEnsure() {}

    /** Izin MENULIS kontak — satu-satunya yang benar-benar wajib untuk menyimpan. */
    public static boolean canWrite(Context ctx) {
        return ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_CONTACTS)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** Izin MEMBACA kontak — hanya dipakai untuk cek duplikat; tanpa ini penyimpanan tetap jalan. */
    public static boolean canRead(Context ctx) {
        return ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** Punya izin baca DAN tulis (dipakai pemanggil yang ingin memastikan cek duplikat aktif). */
    public static boolean hasPermission(Context ctx) {
        return canRead(ctx) && canWrite(ctx);
    }

    /**
     * Nomor yang sudah dipastikan ADA di kontak selama sesi ini (sudah tersimpan sebelumnya maupun
     * baru disimpan). Antrean delivery disegarkan sangat sering (tiap sinkron, tiap kembali ke
     * layar); tanpa ingatan ini tiap penyegaran menembakkan PhoneLookup untuk SETIAP order lagi.
     */
    private static final Set<String> SESSION_DONE = Collections.synchronizedSet(new HashSet<>());

    /** Kunci nomor tahan-format: 08…, +62…, dan 62… memetakan ke kunci yang SAMA. */
    private static String key(String phone) {
        String d = phone == null ? "" : phone.replaceAll("\\D+", "");
        if (d.startsWith("0")) {
            d = "62" + d.substring(1);
        }

        return d;
    }

    /**
     * Simpan SEKUMPULAN pelanggan ke kontak (yang belum ada saja) di THREAD LATAR — dipakai layar
     * Antrian Delivery untuk setiap order yang menjadi tanggung jawab perangkat ini, dari mana pun
     * asalnya: dibuat sendiri, diambil alih dari perangkat lain, diklaim dari Pesanan Terbuka, atau
     * dikirimkan perangkat lain. Dengan begitu tombol WA mana pun nanti (struk, Follow Up, Kendala
     * Pengiriman) langsung membuka chat pelanggan, bukan layar "Kirim ke…".
     *
     * <p>Sepenuhnya best-effort dan senyap: tanpa izin tulis kontak, fungsi ini tak melakukan apa pun.
     *
     * @param people pasangan {nama, nomor}; entri tanpa nomor diabaikan
     */
    public static void ensureAllAsync(Context ctx, List<String[]> people) {
        if (people == null || people.isEmpty() || !canWrite(ctx)) {
            return;
        }

        final List<String[]> todo = new ArrayList<>();
        for (String[] p : people) {
            if (p == null || p.length < 2 || p[1] == null || p[1].trim().isEmpty()) {
                continue;
            }
            String k = key(p[1]);
            // Nomor tak masuk akal (kosong / terlalu pendek untuk nomor Indonesia) jangan dijadikan
            // kontak — hanya mengotori buku telepon staf tanpa pernah bisa dipakai WhatsApp.
            if (k.length() < 9 || SESSION_DONE.contains(k)) {
                continue;
            }
            todo.add(new String[]{p[0], p[1]});
        }
        if (todo.isEmpty()) {
            return;
        }

        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            for (String[] p : todo) {
                try {
                    ensure(app, p[0], p[1]);
                } catch (Throwable ignored) {
                    // satu nomor gagal tak boleh menghentikan sisanya
                }
            }
        }).start();
    }

    /** Nomor ini sudah ada di kontak HP? false juga bila izin BACA tak ada (tak bisa memastikan). */
    public static boolean exists(Context ctx, String phone) {
        if (phone == null || phone.trim().isEmpty()) return false;
        if (!canRead(ctx)) return false;
        Uri uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(phone.trim()));
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(uri,
                    new String[]{ContactsContract.PhoneLookup._ID}, null, null, null);
            return c != null && c.moveToFirst();
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) c.close();
        }
    }

    /**
     * Simpan nomor ke kontak bila belum ada.
     *
     * @return true bila nomornya kini ADA di kontak (sudah ada sebelumnya, atau berhasil disimpan)
     */
    public static boolean ensure(Context ctx, String displayName, String phone) {
        if (phone == null || phone.trim().isEmpty()) return false;
        // Menulis WAJIB; membaca opsional. Tanpa izin BACA, cek duplikat dilewati dan nomor tetap
        // disimpan — lebih baik ada kontak kembar daripada fitur diam-diam tak berfungsi.
        if (!canWrite(ctx)) return false;
        if (exists(ctx, phone)) {
            SESSION_DONE.add(key(phone));

            return true;
        }

        String name = (displayName == null || displayName.trim().isEmpty())
                ? phone.trim() : displayName.trim();

        ArrayList<ContentProviderOperation> ops = new ArrayList<>();
        // account_name/type NULL = kontak lokal perangkat (tak ikut ter-upload ke akun Google mana pun).
        ops.add(ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                .build());
        ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE,
                        ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
                .build());
        ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE,
                        ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phone.trim())
                .withValue(ContactsContract.CommonDataKinds.Phone.TYPE,
                        ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                .build());

        try {
            ContentResolver cr = ctx.getContentResolver();
            cr.applyBatch(ContactsContract.AUTHORITY, ops);
            SESSION_DONE.add(key(phone));

            return true;
        } catch (Exception e) {
            return false;   // gagal simpan → pemanggil tetap lanjut, cuma lewat "Kirim ke…"
        }
    }
}
