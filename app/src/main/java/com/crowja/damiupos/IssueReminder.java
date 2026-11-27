package com.crowja.damiupos;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.SettingsDao;
import com.crowja.damiupos.db.TransactionDao;
import com.crowja.damiupos.model.Transaction;
import com.crowja.damiupos.sync.ComplaintAlert;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Pengingat menu Masalah yang BELUM DIBACA. Tiap item aktif (komplain / masalah terbuka / janji ke tim) yang belum pernah
 * dibuka petugas memunculkan popup + sirene khusus: segera saat item baru terlihat, lalu diulang tiap 10 menit sampai
 * item itu dibuka (kartu diketuk, Detail/Chat/pelanggan dibuka, atau notifikasinya mengantar ke chat komplain).
 * Komplain yang terpicu lagi (pesan baru) atau masalah yang berubah menjadi item "baru" lagi karena kuncinya berubah.
 */
public final class IssueReminder {

    public static final long INTERVAL_MS = 10L * 60L * 1000L;
    private static final int NOTIF_ID = 7860;
    private static final String K_SEEN = "issue_seen_keys";
    private static final String K_ALERTED = "issue_alerted_keys";
    private static final String K_LAST = "issue_alert_last_at";
    private static final int KEEP = 400;

    private IssueReminder() {}

    /** Kunci identitas "versi" item: berubah bila komplain terpicu lagi atau masalahnya berganti. */
    public static String keyOf(Transaction t) {
        String note = t.getProblemNote();
        return t.getId() + "|" + ComplaintListActivity.activityAt(t) + "|" + (note == null ? 0 : note.hashCode());
    }

    private static Set<String> load(SettingsDao s, String k) {
        Set<String> out = new LinkedHashSet<>();
        String v = s.get(k, "");
        if (v != null && !v.isEmpty()) {
            for (String p : v.split(",")) if (!p.isEmpty()) out.add(p);
        }
        return out;
    }

    private static void save(SettingsDao s, String k, Set<String> keys) {
        List<String> l = new ArrayList<>(keys);
        if (l.size() > KEEP) l = l.subList(l.size() - KEEP, l.size());
        s.set(k, android.text.TextUtils.join(",", l));
    }

    public static boolean isUnseen(Context ctx, Transaction t) {
        if (ComplaintListActivity.activityAt(t) == null) return false;
        return !load(new SettingsDao(DatabaseHelper.getInstance(ctx)), K_SEEN).contains(keyOf(t));
    }

    public static void markSeen(Context ctx, Transaction t) {
        if (t == null) return;
        SettingsDao s = new SettingsDao(DatabaseHelper.getInstance(ctx));
        Set<String> seen = load(s, K_SEEN);
        if (seen.add(keyOf(t))) save(s, K_SEEN, seen);
    }

    /** Dibuka lewat notifikasi / chat komplain: tandai semua item aktif order ber-uuid ini sebagai sudah dibaca. */
    public static void markSeenUuid(Context ctx, String uuid) {
        if (uuid == null || uuid.isEmpty()) return;
        try {
            TransactionDao dao = new TransactionDao(DatabaseHelper.getInstance(ctx));
            for (Transaction t : dao.getActiveIssues()) {
                if (uuid.equals(dao.getSyncUuidById(t.getId()))) markSeen(ctx, t);
            }
        } catch (Throwable ignored) {
        }
    }

    private static final String K_ESC = "issue_escalations_cache";
    private static long lastRefreshAt = 0L;

    /** Eskalasi AI aktif perangkat ini dari cache terakhir: uuid pesanan -> ringkasan. */
    public static java.util.Map<String, String> escalations(Context ctx) {
        return escalations(new SettingsDao(DatabaseHelper.getInstance(ctx)));
    }

    public static java.util.Map<String, String> escalations(SettingsDao sd) {
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        try {
            String v = sd.get(K_ESC, "");
            if (v == null || v.isEmpty()) return out;
            org.json.JSONArray a = new org.json.JSONArray(v);
            for (int i = 0; i < a.length(); i++) {
                org.json.JSONObject o = a.optJSONObject(i);
                if (o != null && !o.optString("uuid").isEmpty()) out.put(o.optString("uuid"), o.optString("summary", ""));
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /**
     * Eskalasi Agen AI ke GRUP STAF (🤝 [DIJANJIKAN KE TIM], koreksi galon, pembayaran, …) dari /api/escalations — bukan
     * per pesanan seperti {@link #K_ESC}: bisa tanpa pesanan (lead), ditandai selesai sendiri. Cache = yang boleh dilihat
     * pengguna ini (Admin/SPV/Marketing: semua HP; lainnya: HP ini + yang tanpa pemegang).
     */
    private static final String K_STAFF_ESC = "staff_escalations_cache";

    public static List<org.json.JSONObject> staffEscalations(SettingsDao sd) {
        List<org.json.JSONObject> out = new ArrayList<>();
        try {
            String v = sd.get(K_STAFF_ESC, "");
            if (v == null || v.isEmpty()) return out;
            org.json.JSONArray a = new org.json.JSONArray(v);
            for (int i = 0; i < a.length(); i++) {
                org.json.JSONObject o = a.optJSONObject(i);
                if (o != null && !o.optString("uuid").isEmpty()) out.add(o);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    public static boolean isEscalationOpen(org.json.JSONObject o) {
        String r = o.optString("resolved_at", "");
        return r.isEmpty() || "null".equals(r);
    }

    /** Untuk HP ini saja (tanpa "Semua perangkat"): pesanannya dipegang HP ini, atau tak berpemegang. */
    public static boolean isEscalationForThisDevice(org.json.JSONObject o) {
        return o.optBoolean("mine", false) || o.optBoolean("shared", true);
    }

    /** Jumlah eskalasi grup staf yang belum selesai (ikut badge menu Masalah). */
    public static int openStaffEscalationCount(SettingsDao sd) {
        int n = 0;
        for (org.json.JSONObject o : staffEscalations(sd)) if (isEscalationOpen(o)) n++;
        return n;
    }

    private static String escKey(String uuid) {
        return "esc:" + uuid;
    }

    public static boolean isEscalationUnseen(Context ctx, String uuid) {
        return !load(new SettingsDao(DatabaseHelper.getInstance(ctx)), K_SEEN).contains(escKey(uuid));
    }

    public static void markEscalationSeen(Context ctx, String uuid) {
        if (uuid == null || uuid.isEmpty()) return;
        SettingsDao s = new SettingsDao(DatabaseHelper.getInstance(ctx));
        Set<String> seen = load(s, K_SEEN);
        if (seen.add(escKey(uuid))) save(s, K_SEEN, seen);
    }

    /** Cermin lokal "ditandai selesai" sampai penyegaran berikutnya dari server. */
    public static void markEscalationResolved(Context ctx, String uuid, String byName) {
        try {
            SettingsDao sd = new SettingsDao(DatabaseHelper.getInstance(ctx));
            org.json.JSONArray a = new org.json.JSONArray();
            for (org.json.JSONObject o : staffEscalations(sd)) {
                if (uuid.equals(o.optString("uuid"))) {
                    o.put("resolved_at", new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(new java.util.Date()));
                    o.put("resolved_by_name", byName == null ? "" : byName);
                }
                a.put(o);
            }
            sd.set(K_STAFF_ESC, a.toString());
        } catch (Throwable ignored) {
        }
    }

    /** Ambil eskalasi AI dari server dan simpan (panggil dari thread latar). true bila berhasil. */
    public static boolean refreshSync(Context ctx) {
        com.crowja.damiupos.sync.SyncSettings cfg;
        try {
            cfg = new com.crowja.damiupos.sync.SyncSettings(new SettingsDao(DatabaseHelper.getInstance(ctx)));
            if (!cfg.isEnrolled()) return false;
        } catch (Throwable t) {
            return false;
        }
        boolean ok = false;
        try {
            org.json.JSONArray arr = new com.crowja.damiupos.sync.SyncApi(cfg).issues().optJSONArray("issues");
            if (arr != null) {
                new SettingsDao(DatabaseHelper.getInstance(ctx)).set(K_ESC, arr.toString());
                ok = true;
            }
        } catch (Throwable ignored) {
        }
        try {   // server lama tanpa /api/escalations → cache dibiarkan
            String staff = com.crowja.damiupos.sync.LocationReporter.currentStaffUuid(ctx);
            org.json.JSONArray esc = new com.crowja.damiupos.sync.SyncApi(cfg).escalations(true, staff).optJSONArray("escalations");
            if (esc != null) new SettingsDao(DatabaseHelper.getInstance(ctx)).set(K_STAFF_ESC, esc.toString());
        } catch (Throwable ignored) {
        }
        if (ok) lastRefreshAt = System.currentTimeMillis();
        return ok;
    }

    /** Segarkan di thread latar (dibatasi 1x/menit kecuali force), lalu jalankan onDone di thread itu. */
    public static void refreshAsync(final Context ctx, boolean force, final Runnable onDone) {
        if (!force && System.currentTimeMillis() - lastRefreshAt < 60_000L) {
            if (onDone != null) onDone.run();
            return;
        }
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            refreshSync(app);
            check(app);
            if (onDone != null) onDone.run();
        }).start();
    }

    /** Hapus satu eskalasi dari cache (setelah ditandai ditangani). */
    public static void dropEscalation(Context ctx, String uuid) {
        try {
            java.util.Map<String, String> m = escalations(ctx);
            m.remove(uuid);
            org.json.JSONArray a = new org.json.JSONArray();
            for (java.util.Map.Entry<String, String> e : m.entrySet()) {
                a.put(new org.json.JSONObject().put("uuid", e.getKey()).put("summary", e.getValue()));
            }
            new SettingsDao(DatabaseHelper.getInstance(ctx)).set(K_ESC, a.toString());
        } catch (Throwable ignored) {
        }
    }

    /** Periksa & bunyikan bila perlu; dijadwalkan ulang tiap 10 menit. Aman dipanggil dari mana saja / sering. */
    public static void check(Context ctx) {
        final Context app = ctx.getApplicationContext();
        try {
            SettingsDao s = new SettingsDao(DatabaseHelper.getInstance(app));
            TransactionDao dao = new TransactionDao(DatabaseHelper.getInstance(app));
            Set<String> seen = load(s, K_SEEN);
            List<Transaction> unseen = new ArrayList<>();
            for (Transaction t : dao.getActiveIssues()) if (!seen.contains(keyOf(t))) unseen.add(t);
            List<org.json.JSONObject> unseenEsc = new ArrayList<>();
            for (org.json.JSONObject o : staffEscalations(s)) {
                if (isEscalationOpen(o) && !seen.contains(escKey(o.optString("uuid")))) unseenEsc.add(o);
            }
            schedule(app);
            if (unseen.isEmpty() && unseenEsc.isEmpty()) {
                androidx.core.app.NotificationManagerCompat.from(app).cancel(NOTIF_ID);
                return;
            }
            Set<String> alerted = load(s, K_ALERTED);
            boolean fresh = false;
            for (Transaction t : unseen) if (!alerted.contains(keyOf(t))) fresh = true;
            for (org.json.JSONObject o : unseenEsc) if (!alerted.contains(escKey(o.optString("uuid")))) fresh = true;
            long last = 0L;
            try { last = Long.parseLong(s.get(K_LAST, "0")); } catch (NumberFormatException ignored) {}
            long now = System.currentTimeMillis();
            if (!fresh && now - last < INTERVAL_MS - 30_000L) return;   // belum waktunya mengulang

            StringBuilder body = new StringBuilder();
            int shown = 0;
            final int total = unseen.size() + unseenEsc.size();
            for (org.json.JSONObject o : unseenEsc) {
                if (shown++ >= 4) break;
                String who = o.optString("customer_name", "");
                if (who.isEmpty() || "null".equals(who)) who = "Pelanggan";
                body.append("• ").append(who).append(" — ").append(o.optString("icon", "🤝")).append(' ')
                        .append(o.optString("kind", "DIJANJIKAN KE TIM")).append('\n');
            }
            for (Transaction t : unseen) {
                if (shown++ >= 4) break;
                String who = t.getCustomerName() != null && !t.getCustomerName().isEmpty() ? t.getCustomerName() : "Pelanggan";
                String what = t.getProblemNote() != null ? t.getProblemNote().split("\n")[0] : "😠 Komplain pelanggan";
                body.append("• ").append(who).append(" — ").append(what).append('\n');
            }
            if (total > 4) body.append("…dan ").append(total - 4).append(" lainnya");
            for (Transaction t : unseen) alerted.add(keyOf(t));
            for (org.json.JSONObject o : unseenEsc) alerted.add(escKey(o.optString("uuid")));
            save(s, K_ALERTED, alerted);
            s.set(K_LAST, String.valueOf(now));
            androidx.core.app.NotificationManagerCompat.from(app).cancel(NOTIF_ID);   // bunyi ulang sirene
            ComplaintAlert.deliverTo(app, new Intent(app, ComplaintListActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    "🚨 " + total + " Masalah belum dibaca", body.toString().trim(), NOTIF_ID);
        } catch (Throwable ignored) {
        }
    }

    /** Alarm 10 menit berikutnya (menggantikan yang lama). */
    public static void schedule(Context ctx) {
        try {
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            PendingIntent pi = PendingIntent.getBroadcast(ctx, NOTIF_ID,
                    new Intent(ctx, Receiver.class).setPackage(ctx.getPackageName()),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + INTERVAL_MS, pi);
        } catch (Throwable ignored) {
        }
    }

    public static final class Receiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            check(context);
        }
    }
}
