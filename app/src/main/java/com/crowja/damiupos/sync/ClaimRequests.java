package com.crowja.damiupos.sync;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.crowja.damiupos.DamiuApplication;
import com.crowja.damiupos.util.ClaimRequestRules;
import com.crowja.damiupos.util.ClaimRequestRules.Entry;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Penyimpanan lokal permintaan "Ambil Alih" KELUAR yang butuh persetujuan pemilik antrean:
 * {@code trxUuid → {id, pemilik, status, expires_at}}. Ditulis setiap kali klaim dijawab 409
 * {@code needs_approval} (terpusat di {@link SyncApi#claimDelivery}, jadi SEMUA jalur klaim ikut),
 * lalu disegarkan dari {@code outgoing} poll {@code GET /api/delivery/claim-requests}
 * ({@link com.crowja.damiupos.util.ClaimApprovalPrompter}).
 *
 * <p>Dibaca kartu order perangkat lain untuk mengunci tombol Ambil + menampilkan baris status
 * (aturannya di {@link ClaimRequestRules}). Cache di memori, dicerminkan ke SharedPreferences
 * supaya kunci 1 jam tetap berlaku walau app dibuka ulang.</p>
 */
public final class ClaimRequests {

    private ClaimRequests() {}

    private static final String PREFS = "claim_requests";
    private static final String KEY_OUTGOING = "outgoing";
    /** Entri hasil jawaban klaim yang lebih muda dari ini tak dihapus walau belum ada di poll. */
    private static final long RECORD_GRACE_MS = 90_000L;

    private static final Object LOCK = new Object();
    private static Map<String, Entry> cache;   // null = belum dimuat
    private static final CopyOnWriteArrayList<Runnable> LISTENERS = new CopyOnWriteArrayList<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    // ------------------------------------------------------------------ baca

    /** Permintaan yang masih dalam jendelanya untuk order ini; null bila tidak ada. */
    @Nullable
    public static Entry get(@Nullable String trxUuid) {
        if (trxUuid == null || trxUuid.isEmpty()) return null;
        synchronized (LOCK) {
            Map<String, Entry> m = map();
            Entry e = m.get(trxUuid);
            if (e != null && ClaimRequestRules.isStale(e, System.currentTimeMillis())) {
                m.remove(trxUuid);
                save();
                return null;
            }
            return e;
        }
    }

    /** Tombol Ambil order ini sedang dikunci karena permintaan persetujuan? */
    public static boolean blocks(@Nullable String trxUuid) {
        return ClaimRequestRules.blocksClaim(get(trxUuid), System.currentTimeMillis());
    }

    /** Baris status kecil untuk kartu; null bila tak ada. */
    @Nullable
    public static String cardLine(@Nullable String trxUuid) {
        return ClaimRequestRules.cardLine(get(trxUuid), System.currentTimeMillis());
    }

    /** Toast saat kurir menekan Ambil pada order yang masih terkunci. */
    public static String blockedToast(@Nullable String trxUuid) {
        return ClaimRequestRules.blockedToast(get(trxUuid), System.currentTimeMillis());
    }

    // ------------------------------------------------------------------ tulis

    /** Jawaban klaim ini "butuh persetujuan pemilik" (409 {@code needs_approval})? */
    public static boolean isNeedsApproval(@Nullable Throwable t) {
        if (!(t instanceof SyncApi.SyncException)) return false;
        SyncApi.SyncException se = (SyncApi.SyncException) t;
        if (se.code != 409 || se.body == null) return false;
        try {
            return new JSONObject(se.body).optBoolean("needs_approval", false);
        } catch (Exception e) {
            return false;
        }
    }

    /** Dipanggil {@link SyncApi#claimDelivery} saat klaim gagal — catat bila butuh persetujuan. */
    static void noteClaimError(@Nullable String trxUuid, Throwable t) {
        if (trxUuid == null || trxUuid.isEmpty() || !isNeedsApproval(t)) return;
        try {
            JSONObject j = new JSONObject(((SyncApi.SyncException) t).body);
            long now = System.currentTimeMillis();
            long exp = ClaimRequestRules.parseServerTime(str(j, "expires_at"), now + ClaimRequestRules.WINDOW_MS);
            Entry e = new Entry(trxUuid, j.optLong("request_id", 0L), str(j, "owner_device_name"),
                    ClaimRequestRules.PENDING, exp, now);
            synchronized (LOCK) {
                map().put(trxUuid, e);
                save();
            }
            fireChanged();
        } catch (Exception ignored) {
            // jangan pernah menggagalkan jalur klaim karena pencatatan lokal
        }
    }

    /**
     * Segarkan dari {@code outgoing} poll. Status yang berubah dari MENUNGGU → disetujui/ditolak
     * diberitahukan lewat toast (dan sinkron segera bila disetujui supaya order-nya tertarik).
     */
    public static void mergeOutgoing(Context ctx, @Nullable JSONArray outgoing) {
        if (outgoing == null) return;
        long now = System.currentTimeMillis();
        List<String> toasts = new ArrayList<>();
        boolean approved = false;
        boolean changed = false;
        // Server bisa memuat >1 baris untuk order yang sama (minta ulang setelah kedaluwarsa):
        // pegang yang terbaru (id terbesar) per order.
        Map<String, JSONObject> latest = new LinkedHashMap<>();
        for (int i = 0; i < outgoing.length(); i++) {
            JSONObject o = outgoing.optJSONObject(i);
            if (o == null) continue;
            String uuid = str(o, "transaction_uuid");
            if (uuid.isEmpty()) continue;
            JSONObject prev = latest.get(uuid);
            if (prev == null || o.optLong("id", 0L) >= prev.optLong("id", 0L)) latest.put(uuid, o);
        }
        synchronized (LOCK) {
            Map<String, Entry> m = map();
            Set<String> seen = new HashSet<>(latest.keySet());
            for (Map.Entry<String, JSONObject> row : latest.entrySet()) {
                String uuid = row.getKey();
                JSONObject o = row.getValue();
                long exp = ClaimRequestRules.parseServerTime(str(o, "expires_at"), 0L);
                String status = ClaimRequestRules.normStatus(str(o, "status"));
                String owner = str(o, "owner_device_name");
                long id = o.optLong("id", 0L);
                Entry old = m.get(uuid);
                Entry next;
                if (old == null) {
                    next = new Entry(uuid, id, owner, status,
                            exp > 0 ? exp : now + ClaimRequestRules.WINDOW_MS, 0L);
                } else {
                    Entry upd = old.withStatus(status, owner, exp);
                    next = new Entry(uuid, id != 0 ? id : old.requestId, upd.ownerName, upd.status,
                            upd.expiresAtMs, old.recordedAtMs);
                }
                if (ClaimRequestRules.isStale(next, now)) {
                    if (old != null) { m.remove(uuid); changed = true; }
                    continue;
                }
                if (old == null || !old.status.equals(next.status) || old.expiresAtMs != next.expiresAtMs
                        || !old.ownerName.equals(next.ownerName)) {
                    changed = true;
                }
                if (old != null && ClaimRequestRules.PENDING.equals(old.status)
                        && !ClaimRequestRules.PENDING.equals(next.status)) {
                    String who = next.ownerName.isEmpty() ? "Perangkat pemilik" : next.ownerName;
                    if (ClaimRequestRules.APPROVED.equals(next.status)) {
                        approved = true;
                        toasts.add("✅ " + who + " menyetujui permintaan ambil alih — order dipindahkan ke perangkat ini.");
                    } else if (ClaimRequestRules.REJECTED.equals(next.status)) {
                        toasts.add("❌ " + who + " menolak permintaan ambil alih.");
                    } else if (ClaimRequestRules.FAILED.equals(next.status)) {
                        toasts.add("⚠ Permintaan ambil alih ke " + who + " gagal — order sudah tak bisa dipindahkan.");
                    }
                }
                m.put(uuid, next);
            }
            // Tak lagi dikenal server (atau lewat jendela) → buang; kecuali baru saja dicatat dari
            // jawaban klaim (poll bisa berangkat sebelum permintaannya tersimpan di server).
            Iterator<Map.Entry<String, Entry>> it = m.entrySet().iterator();
            while (it.hasNext()) {
                Entry e = it.next().getValue();
                boolean fresh = now - e.recordedAtMs < RECORD_GRACE_MS;
                if (ClaimRequestRules.isStale(e, now) || (!seen.contains(e.trxUuid) && !fresh)) {
                    it.remove();
                    changed = true;
                }
            }
            if (changed) save();
        }
        if (changed) fireChanged();
        if (!toasts.isEmpty() || approved) {
            final Context app = ctx.getApplicationContext();
            final boolean syncF = approved;
            MAIN.post(() -> {
                for (String t : toasts) Toast.makeText(app, t, Toast.LENGTH_LONG).show();
                if (syncF) SyncScheduler.syncNow(app);
            });
        }
    }

    // ------------------------------------------------------------------ pendengar

    /** Dipanggil di thread UTAMA setiap kali isi penyimpanan berubah (kartu → bind ulang). */
    public static void addListener(Runnable r) {
        if (r != null && !LISTENERS.contains(r)) LISTENERS.add(r);
    }

    public static void removeListener(Runnable r) {
        LISTENERS.remove(r);
    }

    private static void fireChanged() {
        MAIN.post(() -> {
            for (Runnable r : LISTENERS) {
                try { r.run(); } catch (Throwable ignored) {}
            }
        });
    }

    // ------------------------------------------------------------------ simpan / muat

    /** Panggil di dalam {@code synchronized (LOCK)}. */
    private static Map<String, Entry> map() {
        if (cache != null) return cache;
        cache = new LinkedHashMap<>();
        SharedPreferences p = prefs();
        if (p == null) return cache;
        try {
            JSONObject root = new JSONObject(p.getString(KEY_OUTGOING, "{}"));
            long now = System.currentTimeMillis();
            Iterator<String> keys = root.keys();
            while (keys.hasNext()) {
                String uuid = keys.next();
                JSONObject o = root.optJSONObject(uuid);
                if (o == null) continue;
                Entry e = new Entry(uuid, o.optLong("id", 0L), o.optString("owner", ""),
                        o.optString("status", ClaimRequestRules.PENDING), o.optLong("exp", 0L),
                        o.optLong("rec", 0L));
                if (!ClaimRequestRules.isStale(e, now)) cache.put(uuid, e);
            }
        } catch (Exception ignored) {
        }
        return cache;
    }

    /** Panggil di dalam {@code synchronized (LOCK)}. */
    private static void save() {
        SharedPreferences p = prefs();
        if (p == null || cache == null) return;
        try {
            JSONObject root = new JSONObject();
            for (Entry e : cache.values()) {
                JSONObject o = new JSONObject();
                o.put("id", e.requestId);
                o.put("owner", e.ownerName);
                o.put("status", e.status);
                o.put("exp", e.expiresAtMs);
                o.put("rec", e.recordedAtMs);
                root.put(e.trxUuid, o);
            }
            p.edit().putString(KEY_OUTGOING, root.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    @Nullable
    private static SharedPreferences prefs() {
        DamiuApplication app = DamiuApplication.get();
        return app != null ? app.getSharedPreferences(PREFS, Context.MODE_PRIVATE) : null;
    }

    private static String str(JSONObject o, String key) {
        String v = o.optString(key, "");
        return v == null || "null".equals(v) ? "" : v;
    }
}
