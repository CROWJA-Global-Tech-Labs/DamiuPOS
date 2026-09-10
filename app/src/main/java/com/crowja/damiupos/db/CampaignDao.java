package com.crowja.damiupos.db;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.json.JSONArray;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Kampanye pelanggan (Phase 2, device side). Campaigns are pulled from the web (read-only here);
 * a {@link #ensureDelivery delivery} is minted on this device the first time a campaign is shared
 * with a customer (alongside the struk) and then synced up.
 *
 * The campaign keeps being RE-ATTACHED to the same customer's struk on every sale until it is
 * "fulfilled". The fulfillment rule depends on the campaign TYPE (see {@link #isDeliveryFulfilled}):
 * INFO/KUISIONER stop when the customer FILLS the questionnaire (responded_at) OR gives a 👍👎 reaction
 * (reacted_at) — a mere click doesn't count; a BULLETIN stops per its {@code struk_stop} rule — either
 * once CLICKED (clicked_at) or after just ONE send; ORDER (link pesan-ulang mandiri) is NEVER
 * fulfilled — the same link keeps re-attaching forever.
 *
 * ORDER + belum layak ({@code min_repeat_orders} > pelanggan punya JUAL berbayar, cermin
 * {@code App\Models\Campaign::customerIsEligible} di server): kartu promosi "Pesan lagi" TIDAK
 * disematkan ({@link Pending#trackingOnly}) — tapi delivery/link-nya SENDIRI tetap dibuat &
 * dilampirkan sebagai baris lacak netral (lihat ReceiptActivity.appendActiveCampaigns), sebab
 * halaman yang sama juga jadi rumah pelacakan pengiriman + riwayat pesanan (menggantikan link lacak
 * standalone) — pelanggan yang belum layak pesan-ulang tetap butuh itu. Gerbang FORM pesan-ulang di
 * halamannya sendiri (server-side) tetap berlaku juga, independen dari flag ini.
 *
 * Removing the campaign on the web also stops it (deactivate sets is_active=0 on pull;
 * delete → tombstone removes the local row, so it drops out of
 * {@link #activeForCustomer}). Each re-attach reuses the SAME (campaign, customer) delivery token, so
 * it's one delivery per customer — even across the branch's devices (deliveries are branch-wide,
 * pulled & respected here).
 */
public class CampaignDao {

    private static final String TOKEN_CHARS =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final int TOKEN_LENGTH = 10;   // matches the server's Str::random(10)
    private static final SecureRandom RNG = new SecureRandom();

    private final DatabaseHelper dbHelper;

    // CampaignDao has no singleton — every caller does "new CampaignDao(...)" fresh, so the old
    // "synchronized" modifier on ensureDelivery() locked on THIS instance only and never actually
    // excluded two independently-constructed DAOs from racing the same check-then-insert. Hygiene,
    // not the confirmed trigger for the duplicate-kampanye bug (see SyncEngine.SYNC_LOCK) — but a
    // real static lock costs nothing and closes the same class of race if this DAO is ever called
    // from more than one place at once.
    private static final Object DELIVERY_LOCK = new Object();

    public CampaignDao(DatabaseHelper dbHelper) {
        this.dbHelper = dbHelper;
    }

    /** One sharable campaign: the local id (for the delivery ref) + the text shown in the WA caption.
     *  {@code strukInline}: INFO campaign that embeds {@code strukText} directly in the struk — no
     *  link needed (see {@link #ensureDelivery}, which callers should skip for these). {@code isOrder}:
     *  ORDER campaign (link pesan-ulang mandiri) — caller picks a different caption line for it.
     *  {@code trackingOnly}: ORDER campaign the customer isn't yet eligible for (min_repeat_orders) —
     *  caller should render a neutral tracking-only line instead of the "Pesan lagi" promo card,
     *  but still attach the link (same page also hosts live delivery tracking + order history). */
    public static final class Pending {
        public final long campaignLocalId;
        public final String title;
        public final String bodyText;
        public final boolean strukInline;
        public final String strukText;
        public final boolean isOrder;
        public final boolean trackingOnly;
        Pending(long campaignLocalId, String title, String bodyText, boolean strukInline, String strukText,
                boolean isOrder, boolean trackingOnly) {
            this.campaignLocalId = campaignLocalId;
            this.title = title;
            this.bodyText = bodyText;
            this.strukInline = strukInline;
            this.strukText = strukText;
            this.isOrder = isOrder;
            this.trackingOnly = trackingOnly;
        }
    }

    /**
     * Active campaigns that target {@code deviceUuid} and are NOT yet fulfilled for this customer —
     * i.e. the customer hasn't RESPONDED/REACTED yet (a mere click doesn't count) — so they should be
     * (re)appended to this sale's struk. A deactivated or web-deleted campaign drops out (deactivate →
     * is_active=0 on pull; delete → tombstone removes the local row), so it stops being shared too.
     */
    public List<Pending> activeForCustomer(String deviceUuid, long customerLocalId) {
        return activeForCustomer(deviceUuid, customerLocalId, null);
    }

    /**
     * Sama, tapi jadwal kampanye dinilai terhadap {@code onDate} ("yyyy-MM-dd") — dipakai struk
     * order TERTUNDA: galonnya baru sampai pada tanggal LANJUT, jadi kampanye berjadwal harus
     * dinilai di tanggal itu, bukan hari struk disusun. null = hari ini. Cermin
     * Transaction::strukScheduleDate() + StrukWa::activeCampaignAttachments di web.
     */
    public List<Pending> activeForCustomer(String deviceUuid, long customerLocalId, String onDate) {
        List<Pending> out = new ArrayList<>();
        if (deviceUuid == null || deviceUuid.isEmpty() || customerLocalId <= 0) return out;
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        int paidJualCount = paidJualCount(db, customerLocalId);
        // Urutan = PRIORITAS dulu (is_priority=1 selalu di depan), lalu sort_order (diatur di
        // halaman Kampanye web "Atur urutan") — SAMA dgn struk WA/PDF di server
        // (StrukWa::activeCampaignAttachments) supaya struk yang dicetak/dikirim dari HP tak pernah
        // drift urutannya dari yang dikonfigurasi admin.
        Cursor c = db.query(DatabaseHelper.TABLE_CAMPAIGNS,
                new String[]{DatabaseHelper.COL_ID, DatabaseHelper.COL_CAMP_TITLE,
                        DatabaseHelper.COL_CAMP_BODY, DatabaseHelper.COL_CAMP_TARGETS,
                        DatabaseHelper.COL_CAMP_STRUK_INLINE, DatabaseHelper.COL_CAMP_STRUK_TEXT,
                        DatabaseHelper.COL_CAMP_TYPE, DatabaseHelper.COL_CAMP_MIN_REPEAT,
                        DatabaseHelper.COL_CAMP_STARTS_AT, DatabaseHelper.COL_CAMP_ENDS_AT,
                        DatabaseHelper.COL_CAMP_SCHEDULE_DAYS},
                DatabaseHelper.COL_CAMP_ACTIVE + "=1", null, null, null,
                DatabaseHelper.COL_CAMP_PRIORITY + " DESC, "
                        + DatabaseHelper.COL_CAMP_SORT_ORDER + " ASC, " + DatabaseHelper.COL_ID + " ASC");
        try {
            while (c.moveToNext()) {
                long id = c.getLong(0);
                String targets = c.getString(3);
                if (!targetsInclude(targets, deviceUuid)) continue;
                // Penjadwalan (centang "Jadwalkan" di web): di luar rentang/harinya → jangan
                // disematkan ke struk. Cermin Campaign::scheduleAllowsNow() server.
                if (!scheduleAllows(c.getString(8), c.getString(9), c.getString(10), onDate)) continue;
                boolean isOrder = "ORDER".equals(c.getString(6));
                if (isDeliveryFulfilled(db, id, customerLocalId)) continue;   // sudah mengisi/bereaksi → stop
                int minRepeat = c.isNull(7) ? 0 : c.getInt(7);
                boolean trackingOnly = isOrder && minRepeat > 0 && paidJualCount < minRepeat;
                out.add(new Pending(id, c.getString(1), c.getString(2), c.getInt(4) == 1, c.getString(5),
                        isOrder, trackingOnly));
            }
        } finally {
            c.close();
        }
        return out;
    }

    /**
     * Jadwal kampanye mengizinkan tampil HARI INI (tanggal lokal perangkat — praktiknya Asia/Jakarta,
     * sama dgn APP_TIMEZONE server)? Rentang tanggal "Y-m-d" INKLUSIF dua sisi, digabung AND dengan
     * CSV hari ISO (1=Sen…7=Min). Semua null/kosong = selalu boleh. Cermin
     * Campaign::scheduleAllowsNow() di server — dua-duanya dievaluasi saat struk DISUSUN.
     */
    static boolean scheduleAllows(String startsAt, String endsAt, String scheduleDays) {
        return scheduleAllows(startsAt, endsAt, scheduleDays, null);
    }

    /** @param onDate "yyyy-MM-dd" yang dinilai; null = hari ini. */
    static boolean scheduleAllows(String startsAt, String endsAt, String scheduleDays, String onDate) {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        if (onDate != null && onDate.length() >= 10) {
            try {
                java.util.Date d = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                        .parse(onDate.substring(0, 10));
                if (d != null) cal.setTime(d);
            } catch (Exception ignored) {}
        }
        String today = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                .format(cal.getTime());
        // Perbandingan string aman: "Y-m-d" terurut leksikografis = terurut kronologis.
        if (startsAt != null && !startsAt.isEmpty() && today.compareTo(startsAt) < 0) return false;
        if (endsAt != null && !endsAt.isEmpty() && today.compareTo(endsAt) > 0) return false;
        if (scheduleDays == null || scheduleDays.trim().isEmpty()) return true;
        // Calendar: SUNDAY=1..SATURDAY=7 → ISO 1=Sen..7=Min. WAJIB dari kalender yang SAMA dengan
        // `today` di atas — kalau tidak, tanggalnya jadwal-lanjut tapi harinya hari ini.
        int dow = cal.get(java.util.Calendar.DAY_OF_WEEK);
        int iso = dow == java.util.Calendar.SUNDAY ? 7 : dow - 1;
        for (String part : scheduleDays.split(",")) {
            try {
                if (Integer.parseInt(part.trim()) == iso) return true;
            } catch (NumberFormatException ignored) {}
        }
        return false;
    }

    /** JUAL berbayar pelanggan ini, SE-CABANG — bukan hitungan lokal: sync transaksi per-perangkat
     *  SENGAJA terisolasi (lihat SyncEngine), jadi pelanggan yang biasa order lewat HP staf lain akan
     *  selalu terlihat di bawah ambang oleh hitungan lokal walau sebenarnya sudah layak. Pakai agregat
     *  server yang sudah disinkronkan (srv_paid_jual_count) — cermin App\Models\Campaign::
     *  customerIsEligible di server persis, dasar gerbang kelayakan ORDER di atas. */
    private static int paidJualCount(SQLiteDatabase db, long customerLocalId) {
        Cursor c = db.query(DatabaseHelper.TABLE_CUSTOMERS,
                new String[]{DatabaseHelper.COL_SRV_PAID_JUAL_COUNT},
                DatabaseHelper.COL_ID + "=?", new String[]{String.valueOf(customerLocalId)},
                null, null, null);
        try {
            return c.moveToFirst() ? c.getInt(0) : 0;
        } finally {
            c.close();
        }
    }

    /** Does {@code target_devices} (a JSON array of device uuids) contain this device? */
    private static boolean targetsInclude(String targetsJson, String deviceUuid) {
        if (targetsJson == null || targetsJson.isEmpty()) return false;
        try {
            JSONArray arr = new JSONArray(targetsJson);
            for (int i = 0; i < arr.length(); i++) {
                if (deviceUuid.equals(arr.optString(i, null))) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /**
     * Is this campaign FULFILLED for this customer (→ stop re-attaching to the struk)? The rule depends
     * on the campaign TYPE:
     *   • BULLETIN + struk_stop = ONCE  → fulfilled once a delivery EXISTS (i.e. sent at least once);
     *   • BULLETIN + struk_stop = CLICK (or null) → fulfilled once the customer CLICKED ({@code clicked_at});
     *   • INFO / KUISIONER → fulfilled when the customer ENGAGED: FILLED the questionnaire
     *     ({@code responded_at}) OR gave a 👍👎 reaction ({@code reacted_at}) — a mere click is NOT enough.
     * All stamps are web-set and synced down.
     */
    private static boolean isDeliveryFulfilled(SQLiteDatabase db, long campaignLocalId, long customerLocalId) {
        // Jenis + kriteria berhenti kampanye (untuk memilih aturan pemenuhan).
        String type = null, stop = null;
        boolean strukInline = false;
        Cursor cc = db.query(DatabaseHelper.TABLE_CAMPAIGNS,
                new String[]{DatabaseHelper.COL_CAMP_TYPE, DatabaseHelper.COL_CAMP_STRUK_STOP,
                        DatabaseHelper.COL_CAMP_STRUK_INLINE},
                DatabaseHelper.COL_ID + "=?", new String[]{String.valueOf(campaignLocalId)},
                null, null, null, "1");
        try {
            if (cc.moveToFirst()) { type = cc.getString(0); stop = cc.getString(1); strukInline = cc.getInt(2) == 1; }
        } finally {
            cc.close();
        }

        // ORDER: link pesan-ulang mandiri PERSISTEN — TAK PERNAH "terpenuhi" oleh klik/pengisian
        // seperti jenis lain; link yang SAMA terus dilampirkan ke struk berikutnya.
        if ("ORDER".equals(type)) return false;

        // Baris delivery untuk pasangan (kampanye, pelanggan) — bisa belum ada.
        Cursor c = db.query(DatabaseHelper.TABLE_CAMPAIGN_DELIVERIES,
                new String[]{DatabaseHelper.COL_CD_CLICKED_AT, DatabaseHelper.COL_CD_RESPONDED_AT,
                        DatabaseHelper.COL_CD_REACTED_AT},
                DatabaseHelper.COL_CD_CAMPAIGN_ID + "=? AND " + DatabaseHelper.COL_CD_CUSTOMER_ID + "=?",
                new String[]{String.valueOf(campaignLocalId), String.valueOf(customerLocalId)},
                null, null, DatabaseHelper.COL_CD_SENT_AT + " DESC", "1");
        try {
            boolean hasDelivery = c.moveToFirst();
            if ("BULLETIN".equals(type)) {
                // ONCE: cukup 1× kirim → berhenti begitu delivery ada. CLICK/null: berhenti saat diklik.
                if ("ONCE".equals(stop)) return hasDelivery;
                return hasDelivery && notEmpty(c.getString(0));   // clicked_at
            }
            if ("INFO".equals(type) && strukInline) {
                // Sematkan di struk: tak ada link untuk diklik/diisi. ONCE → berhenti begitu terkirim
                // sekali; selain itu (tak diset) → terus dilampirkan tiap struk sampai dinonaktifkan.
                return "ONCE".equals(stop) && hasDelivery;
            }
            // INFO / KUISIONER: berhenti saat mengisi/bereaksi (klik saja tak cukup).
            return hasDelivery && (notEmpty(c.getString(1)) || notEmpty(c.getString(2)));
        } finally {
            c.close();
        }
    }

    private static boolean notEmpty(String s) {
        return s != null && !s.isEmpty();
    }

    /** Token of an existing (not-yet-fulfilled) delivery for this pair, or null — to re-attach the SAME link. */
    /** URL path segment ("fast/{slug}" bila kampanye ORDER sudah punya slug dari server — pulled
     *  down setelah delivery tersinkron naik; sebelum itu, atau untuk jenis lain, "c/{token}"). */
    private static String existingPath(SQLiteDatabase db, long campaignLocalId, long customerLocalId) {
        Cursor c = db.query(DatabaseHelper.TABLE_CAMPAIGN_DELIVERIES,
                new String[]{DatabaseHelper.COL_CD_TOKEN, DatabaseHelper.COL_CD_SLUG},
                DatabaseHelper.COL_CD_CAMPAIGN_ID + "=? AND " + DatabaseHelper.COL_CD_CUSTOMER_ID + "=?",
                new String[]{String.valueOf(campaignLocalId), String.valueOf(customerLocalId)},
                null, null, DatabaseHelper.COL_CD_SENT_AT + " DESC", "1");
        try {
            if (!c.moveToFirst()) return null;
            String token = c.getString(0);
            if (token == null || token.isEmpty()) return null;
            String slug = c.getString(1);
            return (slug != null && !slug.isEmpty()) ? ("fast/" + slug) : ("c/" + token);
        } finally {
            c.close();
        }
    }

    /**
     * Get-or-create the sharable delivery for (campaign, customer) and return its public URL PATH
     * ("c/{token}" or, once the server has minted one, "fast/{slug}" — see {@link #existingPath}) to
     * attach to the struk. Keeps re-attaching the SAME link on every sale until the customer
     * RESPONDS or REACTS (a click alone doesn't count):
     *   • already RESPONDED/REACTED → returns {@code null} (fulfilled, stop sharing);
     *   • an unfulfilled delivery exists → returns its existing path (re-attach the same link);
     *   • none yet → mints a new token + a dirty "sent" delivery (pushed next sync so the link resolves;
     *     the slug, if any, only exists server-side and arrives on a later pull).
     * Reusing the token means one delivery per customer (no duplicates), and a delivery another phone
     * created/whose response synced down is respected here too.
     */
    public String ensureDelivery(long campaignLocalId, long customerLocalId) {
      synchronized (DELIVERY_LOCK) {
        // Static lock: closes the check-then-insert race for THIS device — two callers hitting the
        // same (campaign, customer) pair no longer both see "no existing row" and both insert. This
        // is a LOCAL-only guard (can't prevent a genuinely different device independently minting its
        // own delivery for the same pair before either has synced); the server enforces the real
        // cross-device invariant via a unique index + dedup-on-push (see SyncController::applyRow).
        if (campaignLocalId <= 0 || customerLocalId <= 0) return null;
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        if (isDeliveryFulfilled(db, campaignLocalId, customerLocalId)) return null;   // sudah mengisi/bereaksi
        String existing = existingPath(db, campaignLocalId, customerLocalId);
        if (existing != null) return existing;                                       // lampirkan ulang link sama
        String token = newToken();
        String now = DatabaseHelper.nowIso();
        ContentValues v = new ContentValues();
        v.put(DatabaseHelper.COL_CD_CAMPAIGN_ID, campaignLocalId);
        v.put(DatabaseHelper.COL_CD_CUSTOMER_ID, customerLocalId);
        v.put(DatabaseHelper.COL_CD_TOKEN, token);
        v.put(DatabaseHelper.COL_CD_SENT_AT, now);
        v.put(DatabaseHelper.COL_SYNC_UUID, UUID.randomUUID().toString());
        v.put(DatabaseHelper.COL_EDITED_AT, now);
        v.put(DatabaseHelper.COL_SYNCED, 0);   // dirty → pushed next sync
        long id = db.insert(DatabaseHelper.TABLE_CAMPAIGN_DELIVERIES, null, v);
        return id > 0 ? ("c/" + token) : null;
      }
    }

    private static String newToken() {
        StringBuilder sb = new StringBuilder(TOKEN_LENGTH);
        for (int i = 0; i < TOKEN_LENGTH; i++) {
            sb.append(TOKEN_CHARS.charAt(RNG.nextInt(TOKEN_CHARS.length())));
        }
        return sb.toString();
    }
}
