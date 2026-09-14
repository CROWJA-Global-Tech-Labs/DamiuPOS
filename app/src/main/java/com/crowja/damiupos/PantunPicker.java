package com.crowja.damiupos;

import com.crowja.damiupos.db.SettingsDao;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Memilih SATU pantun follow-up, dari paket yang tersimpan LOKAL — jadi tetap jalan tanpa sinyal
 * (semua pengirim follow-up di HP menyusun tautan wa.me luring).
 *
 * <p>PEMAKAIANNYA BERURUTAN & MELINGKAR: tiap kiriman memajukan kursor satu langkah, jadi paket
 * disusuri 0,1,2,… sampai habis lalu kembali ke awal. Seluruh isi paket kebagian tepat sekali per
 * putaran. Ini menggantikan pemilihan acak-hash yang lama, yang memakai sebagian pantun berkali-kali
 * dan menyisakan sebagian lain tak pernah terkirim sama sekali.
 *
 * <p>Kursornya MILIK PERANGKAT INI dan tidak disinkronkan — lihat
 * {@link SettingsDao#KEY_PANTUN_CURSOR} untuk alasannya (penghitung naik + sinkron LWW = kenaikan
 * bisa saling menimpa). Sisi web memutar rotasinya sendiri dari {@code customers.followup_count}:
 * di sana satu halaman merender banyak baris sekaligus sehingga tiap baris butuh posisinya sendiri,
 * sedangkan di HP pengiriman selalu satu per satu sehingga satu kursor global sudah cukup — dan
 * justru memberi cakupan yang sempurna. Konsekuensinya pantun untuk pelanggan yang sama BOLEH
 * berbeda antara HP dan dashboard; itu memang tak jadi soal, sebab satu pesan hanya dikirim dari
 * satu sisi saja.
 *
 * <p>Urutan paket ini SENGAJA tak diacak: korpusnya dibangkitkan bergiliran antarkelas pantun, jadi
 * nomor yang berdampingan datang dari kelas berbeda — menyusurinya berurutan sudah terasa bervariasi.
 */
public final class PantunPicker {

    private PantunPicker() {}

    /**
     * Nama merek: setelan pantun dulu, lalu nama depot. Rantai ini cermin sisi web (yang masih punya
     * lapis ketiga "nama cabang" — HP tak menyimpan nama cabang, jadi berhenti di depot_name).
     * Kosong = fitur pantun dimatikan oleh pemanggil.
     */
    public static String brand(SettingsDao s) {
        if (s == null) return "";
        String b = s.getPantunBrand();
        if (b == null || b.trim().isEmpty()) b = s.getDepotName();
        return b == null ? "" : b.trim();
    }

    /** Daftar pantun dari paket tersimpan (token {brand} masih mentah). Kosong bila belum diunduh. */
    public static String[] pool(SettingsDao s) {
        if (s == null) return new String[0];
        String raw = s.getPantunPack();
        if (raw == null || raw.trim().isEmpty()) return new String[0];
        try {
            JSONArray arr = new JSONObject(raw).optJSONArray("items");
            if (arr == null) return new String[0];
            String[] out = new String[arr.length()];
            for (int i = 0; i < arr.length(); i++) out[i] = arr.optString(i, "");
            return out;
        } catch (Exception e) {
            return new String[0];   // paket rusak → diam-diam kembali ke template biasa
        }
    }

    /**
     * Posisi ke-{@code seq} dalam putaran. Dipisah jadi fungsi sendiri supaya pelingkarannya bisa
     * diuji langsung, dan supaya bentuknya sepadan dengan {@code PantunPicker::indexFor} di web.
     */
    public static int indexFor(long seq, int poolCount) {
        if (poolCount < 1) return 0;
        return (int) (Math.max(0L, seq) % poolCount);
    }

    /**
     * Bisakah pantun dipakai sekarang? Untuk PEMERIKSAAN saja (mis. memberi tahu staf bahwa
     * pesannya akan terkirim tanpa pantun). TIDAK memajukan kursor — memakai {@link #takeNext}
     * untuk mengintip akan membuat satu pantun terlewat.
     */
    public static boolean isReady(SettingsDao s) {
        return !brand(s).isEmpty() && pool(s).length > 0;
    }

    /**
     * Ambil pantun BERIKUTNYA dalam putaran, lalu majukan kursor. Kembalikan NULL bila fitur tak
     * bisa dipakai — paket kosong ATAU merek belum ketahuan; merek kosong sengaja dianggap GAGAL,
     * sebab pantun yang tak menyebut nama siapa pun lebih buruk daripada tak ada pantun, jadi
     * pemanggil kembali ke template biasa.
     *
     * <p>Panggil TEPAT SEKALI per pesan yang benar-benar dikirim. Kursor hanya maju kalau pantunnya
     * memang jadi dipakai — kalau merek/paket belum siap, tak ada yang terbuang.
     */
    public static String takeNext(SettingsDao s, String dayWord) {
        String brand = brand(s);
        if (brand.isEmpty()) return null;
        String[] pool = pool(s);
        if (pool.length == 0) return null;

        String body = pool[indexFor(s.nextPantunCursor(), pool.length)];
        if (body == null || body.trim().isEmpty()) return null;

        return PantunText.render(body, brand, dayWord);
    }
}
