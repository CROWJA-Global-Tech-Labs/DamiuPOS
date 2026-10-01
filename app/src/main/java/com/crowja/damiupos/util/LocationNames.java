package com.crowja.damiupos.util;

import com.crowja.damiupos.model.Customer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Aturan murni (tanpa Android) "nama lokasi UNIK per pelanggan" — cermin PERSIS
 * {@code App\Support\LocationNames} di server. Pemicunya: pelanggan dengan DUA lokasi bernama
 * "Kediaman" tak bisa dipilih lokasi keduanya oleh agen WA, karena pesanan dicocokkan ke lokasi
 * tersimpan lewat NAMA.
 *
 * <p>Dua nama dianggap SAMA bila sama setelah di-trim, spasi beruntun dirapatkan jadi satu, dan
 * huruf besar/kecil diabaikan ({@link Locale#ROOT}) — "Kediaman", " kediaman " dan "KEDIAMAN"
 * bentrok. Nama kosong dan kembaran berikutnya jatuh ke nama bebas berikutnya: nama itu sendiri,
 * lalu "Nama 2", "Nama 3", … (kosong → "Kediaman", "Kediaman 2", …). Akhiran angka selalu
 * ditambahkan ke nama UTUH, sama seperti server: kembaran "Kediaman 2" → "Kediaman 2 2" — backfill,
 * gerbang sinkron, dan HP harus menghasilkan nama yang sama untuk daftar yang sama.
 *
 * <p>Dipakai DAO (baca &amp; tulis — {@link #unique}, cermin {@code Customer::locationsOrDefault} dan
 * gerbang {@code Customer::saving}) dan form pelanggan (blokir simpan + saran nama). Dipisah dari
 * {@code CustomerFormActivity} supaya bisa diuji di JVM.
 */
public final class LocationNames {

    private LocationNames() {
    }

    /** Spasi apa pun, termasuk NBSP & spasi Unicode lain (papan ketik HP kadang menyisipkannya).
     *  Kelas eksplisit, bukan {@code \s} saja: di Android (ICU) {@code \s} sudah Unicode, di JVM
     *  unit test tidak — kelas eksplisit membuat keduanya sama. */
    private static final Pattern SPACES =
            Pattern.compile("[\\s\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]+");

    /** Nama dirapikan untuk tampilan: trim + spasi beruntun dirapatkan, huruf dipertahankan. */
    public static String clean(String name) {
        if (name == null) return "";
        return SPACES.matcher(name).replaceAll(" ").trim();
    }

    /** Kunci pembanding: {@link #clean} lalu huruf kecil (Locale.ROOT). "" = nama kosong. */
    public static String key(String name) {
        return clean(name).toLowerCase(Locale.ROOT);
    }

    public static boolean isBlank(String name) {
        return key(name).isEmpty();
    }

    /** Dua nama (tak kosong) dianggap sama menurut aturan unik. */
    public static boolean same(String a, String b) {
        String ka = key(a);
        return !ka.isEmpty() && ka.equals(key(b));
    }

    /** Nama bebas berikutnya untuk {@code base}: base sendiri bila belum dipakai, lalu "base 2",
     *  "base 3", … Kosong → {@link Customer#DEFAULT_LOCATION_NAME}. Cermin server {@code nextFree}. */
    public static String nextFree(String base, Collection<String> taken) {
        return nextFreeByKey(base, keysOf(taken));
    }

    /** Nama default lokasi kosong berikutnya: "Kediaman", "Kediaman 2", … */
    public static String nextDefault(Collection<String> taken) {
        return nextFree(Customer.DEFAULT_LOCATION_NAME, taken);
    }

    /** Saran pengganti untuk nama yang bentrok — sama dengan yang dipilih server/backfill untuk
     *  kembaran itu: "Kediaman" → "Kediaman 2", "Kediaman 2" → "Kediaman 2 2" (angka ditambahkan ke
     *  nama UTUH, bukan melanjutkan angkanya). */
    public static String suggest(String name, Collection<String> taken) {
        return nextFreeByKey(clean(name), keysOf(taken));
    }

    /**
     * Indeks nama yang BENTROK dengan nama di indeks sebelumnya (nama pertama tetap "pemilik",
     * kemunculan berikutnya yang ditandai). Nama kosong tak pernah dihitung bentrok — nama kosong
     * selalu jatuh ke nama default bebas berikutnya ({@link #unique}).
     */
    public static List<Integer> duplicateIndexes(List<String> names) {
        List<Integer> out = new ArrayList<>();
        if (names == null) return out;
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < names.size(); i++) {
            String k = key(names.get(i));
            if (k.isEmpty()) continue;
            if (!seen.add(k)) out.add(i);
        }
        return out;
    }

    /**
     * Versi UNIK seluruh daftar (urutan &amp; panjang sama) — port PERSIS {@code LocationNames::unique}
     * di server. Lintasan 1: nama TERISI yang muncul PERTAMA tetap (hanya di-trim). Lintasan 2,
     * urut daftar: nama kosong dan kembaran berikutnya diberi {@code nextFree(clean(nama))} — nama
     * bebas berikutnya yang tak pernah bentrok dengan nama lain di daftar, termasuk yang letaknya
     * LEBIH BAWAH (["Kediaman", "Kediaman", "Kediaman 2"] → kembaran kedua jadi "Kediaman 3"). Nama
     * terisi diutamakan di atas nama kosong (kosong = "terserah", jadi ia yang mengalah). Idempoten.
     * Pemanggil hanya menyerahkan nama lokasi yang berkoordinat (cermin {@code isUsable} server).
     */
    public static List<String> unique(List<String> names) {
        List<String> out = new ArrayList<>();
        if (names == null) return out;
        Set<String> taken = new HashSet<>();
        boolean[] keep = new boolean[names.size()];
        for (int i = 0; i < names.size(); i++) {
            String k = key(names.get(i));
            if (k.isEmpty() || taken.contains(k)) continue;
            taken.add(k);
            keep[i] = true;
        }
        for (int i = 0; i < names.size(); i++) {
            String n = names.get(i);
            if (keep[i]) {
                out.add(n.trim());
                continue;
            }
            String picked = nextFreeByKey(clean(n), taken);
            taken.add(key(picked));
            out.add(picked);
        }
        return out;
    }

    // ------------------------------------------------------------------ internal

    private static Set<String> keysOf(Collection<String> names) {
        Set<String> keys = new HashSet<>();
        if (names != null) {
            for (String n : names) {
                String k = key(n);
                if (!k.isEmpty()) keys.add(k);
            }
        }
        return keys;
    }

    /** {@link #nextFree} atas kunci ({@link #key}) yang sudah dipakai. */
    private static String nextFreeByKey(String base, Set<String> takenKeys) {
        String b = clean(base);
        if (b.isEmpty()) b = Customer.DEFAULT_LOCATION_NAME;
        if (!takenKeys.contains(key(b))) return b;
        for (int n = 2; ; n++) {
            String candidate = b + " " + n;
            if (!takenKeys.contains(key(candidate))) return candidate;
        }
    }
}
