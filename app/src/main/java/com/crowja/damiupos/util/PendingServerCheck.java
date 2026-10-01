package com.crowja.damiupos.util;

/**
 * Mesin status murni (tanpa Android) untuk SATU pemeriksaan server yang sedang berjalan di form
 * pelanggan ("Memeriksa nomor di server…"): hasil jaringan datang di thread lain beberapa detik
 * kemudian, padahal staf bisa membatalkan (Back / ketuk di luar dialog) atau layar berputar dan
 * activity dibuat ulang. Hasil yang datang SETELAH dibatalkan/dihancurkan harus DIABAIKAN — tak boleh
 * menyentuh view lama, dan tak boleh diam-diam menyimpan.
 *
 * <p>Pemakaian: {@link #begin()} saat dialog tampil → token; thread jaringan selesai →
 * {@link #complete(int)} (true = masih berlaku, proses hasilnya); {@link #cancel()} dari
 * onCancel dialog / onDestroy. Batal = menggugurkan percobaan simpan ini (staf mengetuk Simpan lagi).
 * Dipakai dari main thread saja (semua panggilan terjadi di UI thread) — sengaja tanpa sinkronisasi.
 */
public final class PendingServerCheck {

    private boolean active = false;
    private int generation = 0;

    /** Mulai pemeriksaan baru; membatalkan yang masih berjalan. Token dipakai {@link #complete(int)}. */
    public int begin() {
        generation++;
        active = true;
        return generation;
    }

    /** Batalkan pemeriksaan yang berjalan. True bila memang ada yang dibatalkan (bukan no-op). */
    public boolean cancel() {
        if (!active) return false;
        active = false;
        generation++;   // token lama jadi basi → hasil yang masih di jalan diabaikan
        return true;
    }

    /** Hasil tiba. True hanya bila token masih yang berlaku dan belum dibatalkan; sekali pakai. */
    public boolean complete(int token) {
        if (!active || token != generation) return false;
        active = false;
        return true;
    }

    public boolean isActive() {
        return active;
    }
}
