package com.crowja.damiupos.wa;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Auto-menekan tombol "Kirim" WhatsApp untuk dua alur:
 * <ol>
 *   <li><b>Bagikan struk</b> — ReceiptActivity memanggil {@link #arm()} tepat sebelum melempar
 *       gambar struk ke chat pelanggan (alur lama, dimulai staf dari depan layar).</li>
 *   <li><b>Gateway WhatsApp</b> — {@link WaGateway} memanggil {@link #acquire} atas perintah
 *       dashboard, lalu menunggu hasilnya.</li>
 * </ol>
 *
 * <p>Privasi & keamanan: layanan ini DIBATASI ke paket WhatsApp saja (lihat
 * res/xml/wa_autosend_service.xml {@code android:packageNames}) — tidak pernah membaca layar aplikasi
 * lain. Ia hanya aktif selama ada permintaan di {@linkplain Req slot} dan langsung nonaktif setelah
 * satu kali klik.</p>
 *
 * <p><b>Satu slot, bukan flag bebas.</b> Dulu status "armed" berupa dua static longgar sehingga dua
 * alur bisa saling menimpa (satu pesan hilang diam-diam) dan status ter-arm yang menggantung bisa
 * menekan Kirim di chat MANA PUN yang kebetulan dibuka staf. Sekarang hanya ada SATU permintaan
 * aktif, tiap permintaan punya hasil akhir yang bisa ditunggu, dan permintaan gateway TERIKAT pada
 * isi pesannya (lihat {@link #targetVerified}) sehingga mustahil terkirim ke chat yang salah.</p>
 */
public class WaAutoSendService extends AccessibilityService {

    private static final long ARM_WINDOW_MS = 20_000L;
    private static final String PREFS = "damiu_wa";
    private static final String KEY_ENABLED = "autosend_enabled";

    /** Tombol melayang "+ Trx" ({@link TrxBubble}) menumpang layanan ini — satu izin
     *  Aksesibilitas untuk dua fitur. Instance statis dipakai {@link #refreshBubble()}
     *  agar toggle di Pengaturan langsung memunculkan/menyembunyikan bubble. */
    private static volatile WaAutoSendService instance;
    private TrxBubble bubble;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        bubble = new TrxBubble(this);
        bubble.refresh();
    }

    @Override
    public void onDestroy() {
        if (bubble != null) bubble.detach();
        instance = null;
        // Layanan mati selagi ada permintaan menggantung → bebaskan penunggunya, jangan biarkan
        // WaGateway menunggu sampai batas waktu untuk sesuatu yang tak mungkin lagi terjadi.
        Req r = pending;
        if (r != null) finish(r, "service_stopped");
        super.onDestroy();
    }

    /** Dipanggil SettingsActivity setelah toggle bubble diubah. No-op bila layanan belum aktif. */
    public static void refreshBubble() {
        WaAutoSendService s = instance;
        if (s != null && s.bubble != null) s.bubble.refresh();
    }

    /**
     * Konteks layanan ini bila sedang hidup, else null.
     *
     * <p>Dipakai {@link WaGateway} untuk membuka WhatsApp: AccessibilityService di-bind oleh sistem,
     * jadi peluncuran Activity darinya LOLOS pembatasan Background Activity Launch (Android 10+) —
     * sedangkan {@code startActivity} dari konteks aplikasi biasa (WorkManager / LocationService)
     * ditahan sistem DIAM-DIAM tanpa melempar exception. {@link TrxBubble#launch} sudah memakai
     * jalur yang sama di produksi.</p>
     */
    public static Context serviceContext() {
        return instance;
    }

    // --- Slot permintaan auto-kirim (maksimal satu aktif) ---------------------------------------

    private static final Object SLOT = new Object();
    private static volatile Req pending;

    /** Satu permintaan auto-kirim beserta hasil akhirnya. */
    public static final class Req {
        /** Nomor tujuan 62… (gateway), atau null = "chat mana pun" (alur struk lama). */
        final String phone;
        /** Pesan yang harus sudah terisi di kotak ketik WA, atau null = tak diverifikasi. */
        final String text;
        /** CEK-SAJA: cuma menyimpulkan nomor punya WhatsApp atau tidak — TIDAK PERNAH menekan
         *  Kirim. Dipakai {@link WaGateway#check} sebelum menyimpan nomor pelanggan; menekan Kirim
         *  di sini akan mengirim pesan kosong/asal ke orang yang tak pernah kita hubungi. */
        final boolean checkOnly;
        final long deadline;
        final CountDownLatch done = new CountDownLatch(1);
        volatile String outcome;
        /** True begitu WhatsApp benar-benar diluncurkan untuk permintaan ini — sejak saat itu ada
         *  chat tujuan yang TERBUKA & TERISI pesan kita di layar, sehingga permintaan lain tak boleh
         *  menimpanya dengan mode "chat mana pun" (bisa menekan Kirim di chat gateway ini). */
        volatile boolean launched;

        Req(String phone, String text, long deadline, boolean checkOnly) {
            this.phone = phone;
            this.text = text;
            this.deadline = deadline;
            this.checkOnly = checkOnly;
        }

        /**
         * Tunggu sampai Kirim benar-benar ditekan atau permintaan kedaluwarsa, lalu kembalikan
         * hasilnya ({@code sent} / {@code expired} / {@code cancelled} / …). HANYA dari thread
         * background (pemanggilnya adalah poller sinkronisasi, bukan UI).
         *
         * <p>Batas waktu dijaga oleh penunggu ini — BUKAN oleh datangnya event aksesibilitas.
         * Penting: bila peluncuran WhatsApp ditahan sistem, tak ada event apa pun yang datang;
         * tanpa ini slot akan menggantung ter-arm dan bisa menekan Kirim di chat lain.</p>
         */
        public String await() {
            long wait = deadline - System.currentTimeMillis() + 500L;
            try {
                if (!done.await(Math.max(0L, wait), TimeUnit.MILLISECONDS)) {
                    finish(this, "expired");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                finish(this, "interrupted");
            }
            return outcome != null ? outcome : "expired";
        }
    }

    /**
     * Ambil slot auto-kirim untuk gateway. Bila alur lain (mis. bagikan struk) masih berjalan,
     * menunggu sampai {@code waitMs}; null bila tetap sibuk — pemanggil lalu melapor gagal alih-alih
     * menimpa permintaan berjalan (dulu: dua pesan bertabrakan, satu hilang diam-diam).
     */
    static Req acquire(String phone, String text, long waitMs) {
        return acquire(phone, text, waitMs, false);
    }

    /** Sama seperti {@link #acquire(String, String, long)}, tapi bisa meminta slot CEK-SAJA
     *  ({@link Req#checkOnly}) yang tak pernah menekan Kirim. */
    static Req acquire(String phone, String text, long waitMs, boolean checkOnly) {
        long until = System.currentTimeMillis() + waitMs;
        synchronized (SLOT) {
            while (true) {
                Req cur = pending;
                if (cur == null) break;
                long now = System.currentTimeMillis();
                // Permintaan lama yang sudah lewat tenggat DIBEBASKAN di sini. Penting untuk alur
                // struk ({@link #arm()}): tak ada thread yang menunggunya, jadi bila WhatsApp tak
                // memunculkan event lagi setelah tenggat, slot akan terkunci selamanya dan semua
                // pengiriman gateway berikutnya gagal.
                if (now > cur.deadline) {
                    if (cur.outcome == null) cur.outcome = "expired";
                    cur.done.countDown();
                    pending = null;
                    break;
                }
                long left = until - now;
                if (left <= 0) return null;
                try {
                    // Bangun paling lambat saat permintaan berjalan kedaluwarsa, walau tak ada notify.
                    SLOT.wait(Math.min(left, Math.max(50L, cur.deadline - now)));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            Req r = new Req(phone, text, System.currentTimeMillis() + ARM_WINDOW_MS, checkOnly);
            pending = r;
            return r;
        }
    }

    /** Tutup sebuah permintaan dengan hasil akhir & bebaskan slot (idempoten). */
    private static void finish(Req r, String outcome) {
        if (r == null) return;
        synchronized (SLOT) {
            if (r.outcome == null) r.outcome = outcome;
            if (pending == r) pending = null;
            SLOT.notifyAll();
        }
        r.done.countDown();
    }

    /** Batalkan permintaan gateway (mis. peluncuran WhatsApp gagal). */
    static void cancel(Req r) {
        finish(r, "cancelled");
    }

    /** Tandai WhatsApp sudah diluncurkan untuk permintaan ini (lihat {@link Req#launched}). */
    static void markLaunched(Req r) {
        if (r != null) r.launched = true;
    }

    /**
     * Dipanggil ReceiptActivity tepat sebelum melempar struk ke WhatsApp — alur lama: "chat mana
     * pun", satu klik, 20 detik. Aksi yang dimulai staf dari depan layar MENDAHULUI permintaan
     * gateway yang sedang menunggu (gateway melapor gagal dengan jujur, tak diam-diam hilang).
     */
    public static void arm() {
        synchronized (SLOT) {
            Req old = pending;
            // JANGAN menimpa permintaan gateway yang WhatsApp-nya sudah terbuka: chat tujuannya
            // sedang tampil dengan pesan gateway terisi, dan permintaan struk berlaku untuk "chat
            // mana pun" (tanpa verifikasi) — menimpanya bisa menekan Kirim di chat gateway itu,
            // mengirim pesan ke pelanggan yang salah. Lebih baik lewati auto-kirim struk sekali ini
            // (staf menekan Kirim sendiri, seperti saat izin Aksesibilitas belum aktif).
            if (old != null && old.phone != null && old.launched
                    && System.currentTimeMillis() <= old.deadline) {
                return;
            }
            if (old != null) {
                if (old.outcome == null) old.outcome = "preempted";
                old.done.countDown();
            }
            pending = new Req(null, null, System.currentTimeMillis() + ARM_WINDOW_MS, false);
            SLOT.notifyAll();
        }
    }

    public static void disarm() {
        finish(pending, "cancelled");
    }

    // --- Toggle (lokal per-perangkat; akses Aksesibilitas memang spesifik perangkat) ---

    public static boolean isEnabled(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false);
    }

    public static void setEnabled(Context c, boolean v) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, v).apply();
    }

    /** True bila layanan ini sudah diaktifkan user di Pengaturan Aksesibilitas Android. */
    public static boolean isAccessibilityGranted(Context c) {
        String flat = Settings.Secure.getString(c.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(flat)) return false;
        String me = new ComponentName(c, WaAutoSendService.class).flattenToString();
        for (String part : flat.split(":")) {
            if (part.equalsIgnoreCase(me)) return true;
        }
        return false;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Idle cepat: tidak melakukan apa-apa kecuali sedang ada permintaan aktif.
        Req r = pending;
        if (r == null) return;
        if (System.currentTimeMillis() > r.deadline) {
            finish(r, "expired");
            return;
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        CharSequence pkg = root.getPackageName();
        if (pkg == null) return;
        String p = pkg.toString();
        if (!p.equals("com.whatsapp") && !p.equals("com.whatsapp.w4b")) return;

        // CEK-SAJA: simpulkan lalu keluar — jangan pernah mencari (apalagi menekan) tombol Kirim.
        if (r.checkOnly) {
            String verdict = checkVerdict(root, p);
            if (verdict == null) return;   // belum konklusif — tunggu event berikutnya / tenggat
            finish(r, verdict);
            // Kembalikan layar ke aplikasi staf: kita yang memunculkan WhatsApp, kita pula yang
            // menutupnya. Tanpa ini staf ditinggal di chat orang asing yang tak pernah ia buka.
            try { performGlobalAction(GLOBAL_ACTION_BACK); } catch (Throwable ignored) {}
            return;
        }

        // Permintaan gateway: WAJIB terbukti ini chat & pesan yang benar sebelum menekan Kirim.
        if (r.phone != null && !targetVerified(root, p, r)) return;

        AccessibilityNodeInfo send = findSendButton(root, p);
        if (send == null) return;

        // Naik ke ancestor yang clickable bila node-nya sendiri tidak clickable.
        AccessibilityNodeInfo target = send;
        while (target != null && !target.isClickable()) {
            target = target.getParent();
        }
        if (target == null) return;

        // KLAIM slot tepat sebelum mengklik. Penelusuran node di atas memakan waktu (beberapa
        // round-trip binder); dalam jeda itu permintaan bisa sudah kedaluwarsa di thread poller,
        // dibatalkan, atau digantikan. Tanpa klaim ini kita bisa menekan Kirim untuk permintaan yang
        // SUDAH dilaporkan gagal ke dashboard → pelanggan menerima pesan dua kali (sekali dari klik
        // basi ini, sekali dari notifikasi "ketuk untuk kirim").
        synchronized (SLOT) {
            if (pending != r || System.currentTimeMillis() > r.deadline || r.outcome != null) {
                return;   // bukan milik kita lagi — jangan sentuh WhatsApp
            }
            pending = null;   // klaim eksklusif; tak ada yang bisa mengambil alih di tengah klik
            SLOT.notifyAll();
        }

        if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            finish(r, "sent");   // satu kali kirim per permintaan
            return;
        }
        // Klik gagal (node basi / WA sedang transisi): kembalikan slot supaya event berikutnya
        // mencoba lagi selama masih dalam tenggat.
        synchronized (SLOT) {
            if (pending == null && r.outcome == null && System.currentTimeMillis() <= r.deadline) {
                pending = r;
                SLOT.notifyAll();
                return;
            }
        }
        finish(r, "click_failed");
    }

    /**
     * Pastikan layar WA yang sedang di depan benar-benar tujuan permintaan gateway.
     *
     * <p>Kunci utama = <b>isi kotak ketik</b> ({@code :id/entry}) harus sama dengan pesan yang kita
     * titipkan lewat deep-link {@code wa.me}. Ini mengikat klik pada PESAN, bukan sekadar pada nama
     * kontak — chat lain yang kebetulan dibuka staf tak pernah berisi teks itu, jadi mustahil
     * terkirimi. Bila kotak ketik tak terbaca (id WhatsApp berubah antar versi), jatuh ke pencocokan
     * digit judul chat. Bila keduanya tak bisa diverifikasi kita MEMILIH TIDAK MENGKLIK: gagal
     * terkirim jauh lebih murah daripada pesan nyasar ke pelanggan lain.</p>
     */
    private boolean targetVerified(AccessibilityNodeInfo root, String pkg, Req r) {
        String entry = firstText(root, pkg + ":id/entry");
        if (entry != null && r.text != null) {
            return norm(entry).equals(norm(r.text));
        }
        String title = firstText(root, pkg + ":id/conversation_contact_name");
        String digits = title != null ? title.replaceAll("\\D+", "") : "";
        if (digits.length() >= 8 && r.phone != null) {
            // Bandingkan ekor nomor: judul chat bisa berformat "+62 812-…" atau tanpa kode negara.
            String tail = r.phone.substring(Math.max(0, r.phone.length() - 9));
            return digits.endsWith(tail);
        }
        return false;
    }

    /**
     * Kalimat yang dipakai WhatsApp saat nomor pada deep-link tidak terdaftar. Dicocokkan
     * sebagai POTONGAN teks (huruf kecil) karena redaksinya berbeda antar versi & bahasa.
     */
    private static final String[] INVALID_MARKERS = {
        "url is invalid", "shared via url", "tautan tidak valid", "tautan tidak sah",
        "melalui tautan tidak", "lewat tautan tidak",
        "is not on whatsapp", "isn't on whatsapp",
        "tidak ada di whatsapp", "tidak terdaftar di whatsapp", "bukan pengguna whatsapp",
    };

    /**
     * Simpulkan apakah nomor tujuan punya WhatsApp dari layar yang sedang tampil:
     * {@code has_whatsapp} / {@code no_whatsapp}, atau null bila BELUM bisa disimpulkan.
     *
     * <p><b>Sinyal positif diperiksa lebih dulu, dan itu disengaja.</b> Chat sungguhan yang terbuka
     * pasti punya kotak ketik ({@code :id/entry}); dialog "nomor tidak sah" muncul di layar yang
     * TIDAK punya kotak ketik. Dengan urutan ini, riwayat chat yang kebetulan memuat kata "tidak
     * valid" tak bisa disalahartikan sebagai nomor mati.</p>
     *
     * <p>Null (tak konklusif) sengaja dibiarkan berakhir sebagai {@code expired} — pelanggan HANYA
     * ditandai bermasalah bila dialog nomor-tidak-sah benar-benar TERLIHAT, tak pernah karena
     * tebakan.</p>
     */
    private String checkVerdict(AccessibilityNodeInfo root, String pkg) {
        if (hasNode(root, pkg + ":id/entry")) return WaGateway.HAS_WHATSAPP;
        if (containsMarker(root, INVALID_MARKERS)) return WaGateway.NO_WHATSAPP;
        return null;
    }

    /** Ada node terlihat untuk sebuah view-id? (beda dari {@link #firstText}: kotak ketik yang
     *  masih KOSONG tetap terhitung ada). */
    private boolean hasNode(AccessibilityNodeInfo root, String viewId) {
        List<AccessibilityNodeInfo> ns = root.findAccessibilityNodeInfosByViewId(viewId);
        if (ns == null) return false;
        for (AccessibilityNodeInfo n : ns) {
            if (n != null && n.isVisibleToUser()) return true;
        }
        return false;
    }

    /** Ada teks di layar yang memuat salah satu penanda? */
    private static boolean containsMarker(AccessibilityNodeInfo node, String[] markers) {
        if (node == null) return false;
        CharSequence t = node.getText();
        if (t != null) {
            String s = t.toString().toLowerCase(java.util.Locale.US);
            for (String m : markers) {
                if (s.contains(m)) return true;
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            if (containsMarker(node.getChild(i), markers)) return true;
        }
        return false;
    }

    /** Teks pertama yang terlihat untuk sebuah view-id, atau null. */
    private String firstText(AccessibilityNodeInfo root, String viewId) {
        List<AccessibilityNodeInfo> ns = root.findAccessibilityNodeInfosByViewId(viewId);
        if (ns == null) return null;
        for (AccessibilityNodeInfo n : ns) {
            if (n != null && n.isVisibleToUser() && n.getText() != null) {
                return n.getText().toString();
            }
        }
        return null;
    }

    /** Samakan spasi/baris baru supaya perbandingan teks tak gagal karena beda perenderan. */
    private static String norm(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }

    /** Tombol Kirim: utamakan view-id ":id/send" (layar pratinjau media / percakapan), lalu fallback
     *  ke content-description "Send"/"Kirim". */
    private AccessibilityNodeInfo findSendButton(AccessibilityNodeInfo root, String pkg) {
        List<AccessibilityNodeInfo> byId = root.findAccessibilityNodeInfosByViewId(pkg + ":id/send");
        if (byId != null) {
            for (AccessibilityNodeInfo n : byId) {
                if (n != null && n.isVisibleToUser()) return n;
            }
        }
        return findByDesc(root);
    }

    private AccessibilityNodeInfo findByDesc(AccessibilityNodeInfo node) {
        if (node == null) return null;
        CharSequence cd = node.getContentDescription();
        if (cd != null) {
            String s = cd.toString().trim();
            if ((s.equalsIgnoreCase("Send") || s.equalsIgnoreCase("Kirim")) && node.isVisibleToUser()) {
                return node;
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = findByDesc(node.getChild(i));
            if (found != null) return found;
        }
        return null;
    }

    @Override
    public void onInterrupt() { }
}
