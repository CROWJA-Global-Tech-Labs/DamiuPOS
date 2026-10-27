package com.crowja.damiupos.util;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.Toast;

import com.crowja.damiupos.DamiuApplication;
import com.crowja.damiupos.DeepLinkActivity;
import com.crowja.damiupos.LoginActivity;
import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.SettingsDao;
import com.crowja.damiupos.sync.ClaimRequests;
import com.crowja.damiupos.sync.SyncApi;
import com.crowja.damiupos.sync.SyncScheduler;
import com.crowja.damiupos.sync.SyncSettings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Popup persetujuan "Ambil Alih" di perangkat PEMILIK antrean. Selama app di depan (dan perangkat
 * terhubung server), poll {@code GET /api/delivery/claim-requests} tiap ±25 dtk; tiap permintaan
 * {@code incoming} yang menunggu ditampilkan sebagai SATU dialog di activity mana pun yang sedang
 * tampil — bertumpuk, yang terbaru paling atas. Tombol ✅ Setujui / ❌ Tolak → POST di thread latar.
 *
 * <p>Dialog yang activity-nya sudah tak di depan dipindahkan ke activity baru saat ia tampil;
 * dialog yang permintaannya sudah tak ada di {@code incoming} (kedaluwarsa / diputuskan di tempat
 * lain) ditutup sendiri. Poll yang sama juga menyegarkan {@code outgoing} (status permintaan yang
 * DIKIRIM perangkat ini) ke {@link ClaimRequests}.</p>
 *
 * <p>Semua state di sini hanya disentuh di thread UTAMA (jaringan di thread latar).</p>
 */
public final class ClaimApprovalPrompter {

    private ClaimApprovalPrompter() {}

    private static final long POLL_MS = 25_000L;
    /** Server lama (endpoint belum ada → 404/405): jangan ditanya terus. */
    private static final long BACKOFF_MS = 30L * 60_000L;

    private static final Handler H = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean IN_FLIGHT = new AtomicBoolean(false);

    private static int started = 0;
    private static long backoffUntil = 0L;

    /** Permintaan masuk yang MASIH menunggu menurut poll terakhir (id → baris), urut id. */
    private static final TreeMap<Long, JSONObject> pending = new TreeMap<>();
    /** Dialog yang sedang tampil per id permintaan. */
    private static final Map<Long, Shown> showing = new HashMap<>();
    /** Sedang/sudah diputuskan di HP ini — jangan dimunculkan lagi walau poll masih memuatnya. */
    private static final Set<Long> decided = new HashSet<>();

    private static final class Shown {
        final AlertDialog dialog;
        final WeakReference<Activity> activity;
        Shown(AlertDialog d, Activity a) { this.dialog = d; this.activity = new WeakReference<>(a); }
    }

    private static final Runnable TICK = new Runnable() {
        @Override public void run() {
            if (started <= 0) return;
            poll();
            H.postDelayed(this, POLL_MS);
        }
    };

    // ------------------------------------------------------------ kait siklus hidup (DamiuApplication)

    public static void onActivityStarted() {
        if (++started == 1) {
            H.removeCallbacks(TICK);
            H.postDelayed(TICK, 1500L);   // beri layar pertama waktu menggambar dulu
        }
    }

    public static void onActivityStopped() {
        if (--started <= 0) {
            started = 0;
            H.removeCallbacks(TICK);
        }
    }

    /** Layar baru di depan → pindahkan dialog yang masih menunggu ke sana. */
    public static void onActivityResumed(Activity a) {
        H.postDelayed(ClaimApprovalPrompter::attachAll, 300L);
    }

    public static void onActivityDestroyed(Activity a) {
        Iterator<Map.Entry<Long, Shown>> it = showing.entrySet().iterator();
        while (it.hasNext()) {
            Shown s = it.next().getValue();
            Activity owner = s.activity.get();
            if (owner == null || owner == a) {
                safeDismiss(s.dialog);
                it.remove();
            }
        }
    }

    // ------------------------------------------------------------ poll

    private static void poll() {
        final DamiuApplication app = DamiuApplication.get();
        if (app == null || System.currentTimeMillis() < backoffUntil) return;
        if (!IN_FLIGHT.compareAndSet(false, true)) return;
        new Thread(() -> {
            try {
                SyncSettings cfg = new SyncSettings(new SettingsDao(DatabaseHelper.getInstance(app)));
                if (!cfg.isEnrolled()) return;
                JSONObject r = new SyncApi(cfg).claimRequests();
                ClaimRequests.mergeOutgoing(app, r.optJSONArray("outgoing"));
                final JSONArray incoming = r.optJSONArray("incoming");
                H.post(() -> applyIncoming(incoming));
            } catch (SyncApi.SyncException se) {
                if (se.code == 404 || se.code == 405) {
                    H.post(() -> backoffUntil = System.currentTimeMillis() + BACKOFF_MS);
                }
            } catch (Throwable ignored) {
                // jaringan putus dsb. → coba lagi tick berikutnya
            } finally {
                IN_FLIGHT.set(false);
            }
        }, "claim-requests-poll").start();
    }

    private static void applyIncoming(JSONArray incoming) {
        pending.clear();
        if (incoming != null) {
            for (int i = 0; i < incoming.length(); i++) {
                JSONObject o = incoming.optJSONObject(i);
                if (o == null) continue;
                long id = o.optLong("id", 0L);
                if (id > 0) pending.put(id, o);
            }
        }
        // Sudah tak menunggu (kedaluwarsa / diputuskan di web atau HP lain) → tutup dialognya.
        Iterator<Map.Entry<Long, Shown>> it = showing.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, Shown> e = it.next();
            if (!pending.containsKey(e.getKey()) && !decided.contains(e.getKey())) {
                safeDismiss(e.getValue().dialog);
                it.remove();
            }
        }
        decided.retainAll(pending.keySet());
        attachAll();
    }

    /** Pastikan tiap permintaan menunggu punya dialog di activity yang SEDANG tampil. */
    private static void attachAll() {
        DamiuApplication app = DamiuApplication.get();
        Activity a = app != null ? app.getCurrentActivity() : null;
        if (a == null || a.isFinishing() || a.isDestroyed() || !canHost(a)) return;
        // Urut id naik → yang terbaru ditampilkan TERAKHIR = paling atas tumpukan.
        for (Map.Entry<Long, JSONObject> e : pending.entrySet()) {
            long id = e.getKey();
            if (decided.contains(id)) continue;
            Shown s = showing.get(id);
            if (s != null) {
                if (s.activity.get() == a && s.dialog.isShowing()) continue;
                safeDismiss(s.dialog);
                showing.remove(id);
            }
            try {
                showing.put(id, new Shown(show(a, id, e.getValue()), a));
            } catch (Throwable ignored) {
                // window activity belum siap → dicoba lagi di poll/resume berikutnya
            }
        }
    }

    /** Layar yang tak pantas ditumpangi popup: transit deep-link, login staf, mode PiP. */
    private static boolean canHost(Activity a) {
        if (a instanceof DeepLinkActivity || a instanceof LoginActivity) return false;
        try {
            if (a.isInPictureInPictureMode()) return false;
        } catch (Throwable ignored) {}
        return true;
    }

    // ------------------------------------------------------------ dialog

    private static AlertDialog show(Activity a, long id, JSONObject q) {
        String requester = str(q, "requester_device_name");
        if (requester.isEmpty()) requester = "Perangkat lain";
        final AlertDialog dialog = new AlertDialog.Builder(a)
                .setTitle("📥 " + requester + " minta ambil alih pesanan")
                .setMessage(message(q, requester))
                .setCancelable(false)
                .setPositiveButton("✅ Setujui", null)
                .setNegativeButton("❌ Tolak", null)
                .create();
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnShowListener(d -> {
            final Button pos = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            final Button neg = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
            pos.setOnClickListener(v -> decide(dialog, pos, neg, id, true));
            neg.setOnClickListener(v -> decide(dialog, pos, neg, id, false));
        });
        dialog.show();
        return dialog;
    }

    private static String message(JSONObject q, String requester) {
        StringBuilder sb = new StringBuilder();
        sb.append(requester).append(" meminta persetujuan untuk mengambil alih pesanan dari antrean perangkat ini.\n\n");
        String cust = str(q, "customer_name");
        sb.append("👤 ").append(cust.isEmpty() ? "Pelanggan" : cust).append('\n');
        String addr = str(q, "address");
        if (!addr.isEmpty()) sb.append("🏠 ").append(addr).append('\n');
        JSONArray items = q.optJSONArray("items");
        if (items != null && items.length() > 0) {
            sb.append('\n');
            for (int i = 0; i < items.length(); i++) {
                JSONObject it = items.optJSONObject(i);
                if (it == null) continue;
                String name = str(it, "name");
                double qty = it.optDouble("qty", 0.0);
                sb.append("• ").append(name.isEmpty() ? "Produk" : name).append(" × ").append(fmtQty(qty)).append('\n');
            }
        }
        int galon = q.optInt("galon", 0);
        double total = q.optDouble("total", 0.0);
        List<String> tail = new ArrayList<>();
        if (galon > 0) tail.add(galon + " galon");
        if (total > 0) tail.add("Rp " + String.format(Locale.US, "%,.0f", total).replace(',', '.'));
        if (!tail.isEmpty()) sb.append('\n').append(android.text.TextUtils.join(" · ", tail)).append('\n');
        long now = System.currentTimeMillis();
        long exp = ClaimRequestRules.parseServerTime(str(q, "expires_at"), 0L);
        if (exp > 0) {
            sb.append("\n⏱ Berlaku s/d ").append(ClaimRequestRules.hm(exp))
                    .append(" (").append(ClaimRequestRules.timeLeft(exp, now)).append(')');
        }
        sb.append("\n\nSetujui = order dipindahkan ke ").append(requester).append('.');
        return sb.toString();
    }

    private static void decide(AlertDialog dialog, Button pos, Button neg, long id, boolean approve) {
        final DamiuApplication app = DamiuApplication.get();
        if (app == null) return;
        decided.add(id);
        pos.setEnabled(false);
        neg.setEnabled(false);
        (approve ? pos : neg).setText("Memproses…");
        new Thread(() -> {
            String msg;
            boolean done;   // permintaan sudah tak menunggu (berhasil ATAU ditolak server 422)
            boolean ok = false;
            try {
                SyncSettings cfg = new SyncSettings(new SettingsDao(DatabaseHelper.getInstance(app)));
                SyncApi api = new SyncApi(cfg);
                JSONObject r = approve ? api.approveClaimRequest(id) : api.rejectClaimRequest(id);
                msg = r.optString("message", "");
                if (msg.isEmpty()) msg = approve ? "✅ Disetujui — order dipindahkan." : "❌ Permintaan ditolak.";
                done = true;
                ok = true;
            } catch (SyncApi.SyncException se) {
                msg = se.serverMessage();
                done = se.code == 422 || se.code == 404 || se.code == 409;
                if (msg == null) msg = (approve ? "Gagal menyetujui" : "Gagal menolak") + " (kode " + se.code + ").";
            } catch (Throwable t) {
                msg = (approve ? "Gagal menyetujui" : "Gagal menolak") + " — periksa koneksi internet.";
                done = false;
            }
            final String msgF = msg;
            final boolean doneF = done;
            final boolean syncF = ok && approve;
            H.post(() -> {
                Toast.makeText(app, msgF, Toast.LENGTH_LONG).show();
                if (doneF) {
                    pending.remove(id);
                    Shown s = showing.remove(id);
                    safeDismiss(s != null ? s.dialog : dialog);
                    if (s != null && s.dialog != dialog) safeDismiss(dialog);
                    // Order keluar dari antrean ini → tarik perubahan sekarang.
                    if (syncF) SyncScheduler.syncNow(app);
                } else {
                    decided.remove(id);   // boleh dicoba lagi
                    try {
                        pos.setEnabled(true);
                        neg.setEnabled(true);
                        pos.setText("✅ Setujui");
                        neg.setText("❌ Tolak");
                    } catch (Throwable ignored) {}
                }
            });
        }, "claim-request-decide").start();
    }

    // ------------------------------------------------------------ util

    private static void safeDismiss(AlertDialog d) {
        if (d == null) return;
        try { if (d.isShowing()) d.dismiss(); } catch (Throwable ignored) {}
    }

    private static String fmtQty(double q) {
        if (q == Math.rint(q)) return String.valueOf((long) q);
        return String.format(Locale.US, "%.2f", q).replace('.', ',');
    }

    private static String str(JSONObject o, String key) {
        String v = o.optString(key, "");
        return v == null || "null".equals(v) ? "" : v;
    }
}
