package com.crowja.damiupos.pengisian;

import com.crowja.damiupos.db.SettingsDao;

import java.util.List;

/**
 * Penyimpanan LOKAL layar Pengisian di tabel pengaturan: kotak keluar tulis + cache rencana
 * terakhir yang sukses (untuk banner "offline – data lama").
 *
 * <p>Kedua kunci sengaja TIDAK ada di {@code SettingsDao.SHAREABLE_KEYS}: {@code set()} lalu
 * menandainya sudah-tersinkron sehingga tak pernah didorong ke server, dan isinya milik perangkat
 * ini saja. Semua akses melewati satu kunci monitor karena kotak keluar disentuh dua thread
 * (ketukan di UI, pengiriman di thread latar) dan baca-ubah-tulis teks JSON tak boleh saling
 * menimpa.
 */
public final class PengisianStore {

    static final String KEY_OUTBOX = "pengisian_outbox_json";
    static final String KEY_PLAN_CACHE = "pengisian_plan_cache_json";

    /** Pintu baca/tulis teks kunci-nilai (SettingsDao di aplikasi, peta di memori pada uji JVM). */
    public interface Backing {
        String get(String key, String defaultValue);

        void set(String key, String value);
    }

    private static final Object LOCK = new Object();

    private final Backing settings;

    public PengisianStore(final SettingsDao dao) {
        this(new Backing() {
            @Override
            public String get(String key, String defaultValue) {
                return dao.get(key, defaultValue);
            }

            @Override
            public void set(String key, String value) {
                dao.set(key, value);
            }
        });
    }

    public PengisianStore(Backing settings) {
        this.settings = settings;
    }

    // ------------------------------------------------------------------- kotak keluar

    public void enqueue(FillOutbox.Op op) {
        synchronized (LOCK) {
            FillOutbox box = FillOutbox.fromJson(settings.get(KEY_OUTBOX, ""));
            box.add(op);
            settings.set(KEY_OUTBOX, box.toJson());
        }
    }

    /** SEMUA operasi di HP ini, termasuk milik staf lain yang belum terkirim. */
    public List<FillOutbox.Op> pending() {
        synchronized (LOCK) {
            return FillOutbox.fromJson(settings.get(KEY_OUTBOX, "")).ops();
        }
    }

    /**
     * Operasi milik {@code staffUuid} saja — inilah yang boleh dijadikan overlay & penghitung
     * "belum terkirim" di layar staf itu. Operasi staf lain tak boleh menggeser angka pekerja yang
     * sedang login (hitung stok absolut milik orang lain akan mengunci "siap di rak").
     */
    public List<FillOutbox.Op> pendingFor(String staffUuid) {
        return FillOutbox.ownedBy(pending(), staffUuid);
    }

    /** Jumlah operasi yang tertahan milik STAF LAIN (catatan kecil di layar, tak ikut overlay). */
    public int foreignCount(String staffUuid) {
        return pending().size() - pendingFor(staffUuid).size();
    }

    public void remove(String opUuid) {
        synchronized (LOCK) {
            FillOutbox box = FillOutbox.fromJson(settings.get(KEY_OUTBOX, ""));
            if (box.remove(opUuid)) settings.set(KEY_OUTBOX, box.toJson());
        }
    }

    /**
     * Buang operasi yang tertahan lebih dari 24 jam ({@link FillOutbox#EXPIRE_AFTER_MS}); operasi
     * staf lain yang tak pernah login lagi di HP ini tak boleh menumpuk selamanya, dan catatan
     * kemarin tak boleh tiba-tiba tercap hari ini. @return jumlah yang dibuang.
     */
    public int pruneExpired(long nowWallMs, long nowElapsedMs) {
        synchronized (LOCK) {
            FillOutbox box = FillOutbox.fromJson(settings.get(KEY_OUTBOX, ""));
            int n = box.removeExpired(nowWallMs, nowElapsedMs);
            if (n > 0) settings.set(KEY_OUTBOX, box.toJson());
            return n;
        }
    }

    // ------------------------------------------------------------------- cache rencana

    public void saveCache(String planJson, long nowMs) {
        synchronized (LOCK) {
            settings.set(KEY_PLAN_CACHE, FillText.encodeCache(planJson, nowMs));
        }
    }

    /** @return {savedAtMs (Long), planJson (String)} atau null. */
    public Object[] loadCache() {
        synchronized (LOCK) {
            return FillText.decodeCache(settings.get(KEY_PLAN_CACHE, ""));
        }
    }
}
