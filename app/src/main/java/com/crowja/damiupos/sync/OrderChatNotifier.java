package com.crowja.damiupos.sync;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.crowja.damiupos.ChatLogActivity;
import com.crowja.damiupos.R;
import com.crowja.damiupos.model.ChatMessage;
import com.crowja.damiupos.util.Ts;

import org.json.JSONObject;

import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Notifikasi "💬 Chat Pesanan": pelanggan membalas di chat WA sebuah order. Server mengantrekan
 * perintah {@code order_chat_reply} ke perangkat pemegang order (+ perangkat yang baru membalas di
 * chat itu) saat pesan masuk baru tiba lewat webhook WA Bridge / sweep; {@link OnlineTasks} menjalankannya
 * di sini lalu melaporkan status ack-nya.
 *
 * <ul>
 *   <li>Satu notifikasi per transaksi (tag {@code order_chat:<uuid>}), diperbarui di tempat. Selama
 *       notifikasinya belum dibuka/digeser, jumlah pesan dijumlahkan ("(+N pesan)"): {@code count}
 *       tiap perintah = pesan masuk BARU sejak perintah sebelumnya (delta, bukan total berjalan). Delta
 *       itu bisa memuat pesan yang tertahan debounce server dan sempat dilihat staf di chat — yang sudah
 *       terlihat dikurangkan ({@link #unseenCount}).</li>
 *   <li>Ketuk → {@link ChatLogActivity} mode order untuk transaksi itu (gerbang login multi-user ada
 *       di sana). Notifikasinya TIDAK auto-cancel: ia tetap di baki sampai chat-nya benar-benar tampil
 *       (ChatLogActivity.onResume → {@link #cancel}) — ketukan yang tertahan gerbang login (istirahat,
 *       clock-out, "Pulangkan") tak menghilangkan balasannya.</li>
 *   <li>Chat itu sedang terbuka di layar → tanpa notifikasi, langsung poll
 *       ({@link ChatLogActivity#pollIfShowing}). Pemeriksaan itu dan {@code notify()} berjalan dalam
 *       SATU runnable main thread — onResume (yang menghapus notifikasi) tak bisa menyelip di antaranya.
 *       Balasannya dicatat "menunggu terlihat": bila layar berhenti tampil / poll-nya gagal sebelum
 *       pesan itu terlihat ({@link #markSeen}), notifikasinya dipasang saat itu ({@link #flushUndisplayed})
 *       — perintahnya sudah di-ack final, server tak mengirimnya lagi.</li>
 *   <li>Pesan yang sudah TERLIHAT di chat ({@link #markSeen}: dasar daftar tampil di layar) atau sudah
 *       pernah dinotifikasi (perintah dobel: webhook at-least-once, webhook + sweep) tidak dinotifikasi
 *       lagi — dibandingkan lewat {@code at} payload = wa_timestamp pesan masuk terbaru yang dihitung.</li>
 *   <li>Multi-user aktif tapi tak ada staf yang login → tanpa notifikasi (ack {@link #ACK_NO_USER}).</li>
 * </ul>
 * Izin POST_NOTIFICATIONS (Android 13+) diminta MainActivity seperti notifikasi lain.
 */
public final class OrderChatNotifier {

    public static final String CHANNEL_ID = "order_chat";

    /** Status ack perintah (device_commands.status, maks 40 karakter). */
    public static final String ACK_NOTIFIED = "notified";
    public static final String ACK_CHAT_OPEN = "chat_open";
    /** Pesan s.d. {@code at} sudah tampil di chat / sudah dinotifikasi (perintah dobel). */
    public static final String ACK_ALREADY_SEEN = "already_seen";
    /** Multi-user aktif tapi tak ada staf yang login (logout / clock-out / istirahat / "Pulangkan"). */
    public static final String ACK_NO_USER = "no_user";
    public static final String ACK_NOTIF_DISABLED = "notif_disabled";
    public static final String ACK_CHANNEL_DISABLED = "channel_disabled";
    public static final String ACK_INVALID = "invalid_payload";
    /** {@link #onCustomerReply} melempar — OnlineTasks tetap meng-ack perintahnya dengan status ini. */
    public static final String ACK_ERROR = "error";

    private static final int NOTIF_ID = 7930;
    private static final String TAG_PREFIX = "order_chat:";
    private static final String EXTRA_COUNT = "com.crowja.damiupos.order_chat_count";
    private static final String EXTRA_AT = "com.crowja.damiupos.order_chat_at";
    private static final String ACTION_OPEN = "com.crowja.damiupos.action.OPEN_ORDER_CHAT";
    /** Batas menunggu main thread memeriksa layar chat + memasang notifikasi. */
    private static final long MAIN_THREAD_TIMEOUT_MS = 2000L;
    /** NotificationManagerService memasang notifikasi secara asinkron: getActiveNotifications() tepat
     *  sesudah notify() bisa belum memuatnya (dua perintah trx yang sama dalam satu batch). Selama
     *  selang ini jumlah yang diingat proses yang dipakai; sesudahnya daftar sistem (yang juga tahu
     *  notifikasi sudah digeser / diketuk) jadi sumber kebenaran. */
    static final long ACTIVE_LIST_LAG_MS = 10_000L;
    /** Ingatan trx yang tak tersentuh selama ini dibuang ({@link #prune}). Perintah hidup ≤ 30 mnt di
     *  server dan hanya mengumumkan pesan ≤ 15 mnt (NOTIFY_MAX_AGE) + simpanan debounce ≤ 30 mnt:
     *  sesudah 2 jam tanpa perintah / poll, tak ada perintah yang masih bisa merujuk ingatan lamanya. */
    static final long STATE_IDLE_MS = 2 * 60 * 60 * 1000L;
    /** Batas keras jumlah trx yang diingat (LRU): proses app ini hidup berminggu-minggu (LocationService). */
    static final int MAX_TRX = 256;
    /** Pesan masuk terlihat yang diingat per trx untuk {@link #unseenCount} (terlama dibuang). */
    static final int SEEN_CAP = 128;

    /** Ingatan proses per trx, urut akses (LRU). Dijaga oleh kelas. */
    private static final Map<String, TrxState> states = new LinkedHashMap<String, TrxState>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, TrxState> eldest) {
            return size() > MAX_TRX;
        }
    };

    private OrderChatNotifier() {}

    /** Ingatan proses untuk satu transaksi. Dijaga oleh kelas (synchronized OrderChatNotifier.class). */
    static final class TrxState {
        /** wa_timestamp (epoch ms) pesan masuk terbaru yang sudah dinotifikasi / terlihat di chat. */
        long handledUpTo;
        /** {@code at} perintah terbaru yang sudah diproses perangkat ini (apa pun hasilnya): pesan yang
         *  dihitung {@code count} perintah berikutnya lebih baru dari ini. 0 = tak diketahui. */
        long announcedUpTo;
        /** Pesan masuk yang sudah terlihat di chat: kunci pesan → wa_timestamp (ms); urut masuk, ≤ {@link #SEEN_CAP}. */
        final LinkedHashMap<String, Long> seen = new LinkedHashMap<>();
        /** Jumlah di notifikasi yang terakhir dipasang proses ini, dan kapan (elapsedRealtime; 0 = tak ada). */
        int unreadCount;
        long unreadPostedAt;
        /** Perintah yang dijawab {@link #ACK_CHAT_OPEN} tapi pesannya belum terlihat ({@link #flushUndisplayed}). */
        @Nullable Reply pending;
        /** {@link #announcedUpTo} sebelum perintah pending pertama — batas bawah hitungannya. */
        long pendingLower;
        /** elapsedRealtime terakhir disentuh. */
        long touchedAt;
    }

    static final class Reply {
        final String trx;
        @Nullable final String name;
        @Nullable final String acct;
        @Nullable final String preview;
        final int count;
        /** Epoch ms payload {@code at}; 0 = tak ada / tak terbaca (tanpa pemeriksaan dobel). */
        final long at;

        Reply(String trx, @Nullable String name, @Nullable String acct, @Nullable String preview,
              int count, long at) {
            this.trx = trx;
            this.name = name;
            this.acct = acct;
            this.preview = preview;
            this.count = count;
            this.at = at;
        }
    }

    /**
     * Jalankan satu perintah {@code order_chat_reply}. Payload server:
     * {@code {transaction_uuid, customer_name, text_preview, count, wa_account, at}}.
     *
     * @return status ack untuk {@code POST /api/commands/ack}
     */
    public static String onCustomerReply(Context ctx, @Nullable JSONObject p) {
        String trx = p != null ? str(p, "transaction_uuid") : null;
        if (trx == null) return ACK_INVALID;
        final Context app = ctx.getApplicationContext();
        // Tanpa staf login, layar chatnya tertutup gerbang login dan balasan tak punya nama staf —
        // notifikasi hanya memamerkan pesan pelanggan di HP yang tak dipakai. Dashboard tetap diberi tahu.
        if (ChatLogActivity.loginRequired(app)) return ACK_NO_USER;
        ensureChannel(app);
        final Reply r = new Reply(trx, str(p, "customer_name"), str(p, "wa_account"),
                str(p, "text_preview"), p.optInt("count", 1), atMillis(str(p, "at")));
        return deliverOnMainThread(app, r);
    }

    /** Hapus notifikasi chat transaksi ini (dipanggil ChatLogActivity saat chat-nya tampil). */
    public static void cancel(Context ctx, @Nullable String trxUuid) {
        if (trxUuid == null || trxUuid.isEmpty()) return;
        synchronized (OrderChatNotifier.class) {
            TrxState st = states.get(trxUuid);
            if (st != null) {
                st.unreadCount = 0;
                st.unreadPostedAt = 0L;
            }
        }
        try {
            NotificationManagerCompat.from(ctx).cancel(TAG_PREFIX + trxUuid, NOTIF_ID);
        } catch (Throwable ignored) {}
    }

    /**
     * Pesan {@code shown} chat trx ini baru saja TERLIHAT oleh staf (main thread; layar tampil dan
     * dasar daftarnya di layar — pesan yang dimuat selagi daftar digulir ke atas baru dilaporkan setelah
     * staf menggulir ke dasar). Perintah {@code order_chat_reply} untuk pesan itu tiba belakangan (poller
     * perintah 60 dtk vs poll chat 6 dtk) — ia tak boleh memasang notifikasi untuk pesan yang sudah
     * dibaca. Notifikasi yang sempat dipasang selagi chat ini terbuka (poll gagal, main thread macet)
     * dan kini seluruhnya terlihat ikut dihapus. {@code shown} kosong tetap berarti "poll terbaru terlihat".
     */
    public static void markSeen(Context ctx, @Nullable String trxUuid, @Nullable List<ChatMessage> shown) {
        if (trxUuid == null || trxUuid.isEmpty()) return;
        synchronized (OrderChatNotifier.class) {
            TrxState st = stateLocked(trxUuid, SystemClock.elapsedRealtime());
            if (!applySeen(st, shown)) return;
            Bundle active = activeExtras(ctx, TAG_PREFIX + trxUuid);
            long at = active != null ? active.getLong(EXTRA_AT, 0L) : 0L;
            if (at > 0L && at <= st.handledUpTo) cancel(ctx, trxUuid);
        }
    }

    /**
     * Layar chat trx ini berhenti tampil (onPause) atau poll-nya gagal, padahal ada balasan yang tadi
     * dijawab {@link #ACK_CHAT_OPEN} (tanpa notifikasi) dan belum terlihat → pasang notifikasinya
     * sekarang. Perintahnya sudah di-ack final, jadi server tak akan mengirimnya lagi. Main thread.
     */
    public static void flushUndisplayed(Context ctx, @Nullable String trxUuid) {
        if (trxUuid == null || trxUuid.isEmpty()) return;
        Reply r;
        long lower;
        synchronized (OrderChatNotifier.class) {
            TrxState st = states.get(trxUuid);
            if (st == null || st.pending == null) return;
            r = st.pending;
            lower = st.pendingLower;
            st.pending = null;
        }
        Context app = ctx.getApplicationContext();
        try {
            if (ChatLogActivity.loginRequired(app)) return;
            ensureChannel(app);
            post(app, r, lower);
        } catch (Throwable ignored) {
            // best-effort — jangan sampai menggagalkan onPause
        }
    }

    /** Channel "Chat Pesanan" (prioritas tinggi). Aman dipanggil berulang. */
    public static void ensureChannel(Context ctx) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null || nm.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Chat Pesanan",
                NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("Balasan pelanggan di chat WhatsApp pesanan");
        nm.createNotificationChannel(ch);
    }

    // ---------------------------------------------------------------- logika murni (dites JVM)

    static String title(@Nullable String customerName) {
        String n = customerName != null ? customerName.trim() : "";
        return "💬 " + (n.isEmpty() ? "Pelanggan" : n);
    }

    /** Pratinjau pesan terakhir + " (+N pesan)" untuk pesan lain yang belum dibaca. */
    static String body(@Nullable String preview, int count) {
        String t = preview != null ? preview.trim() : "";
        if (t.isEmpty()) t = "Pesan baru dari pelanggan";
        return count > 1 ? t + " (+" + (count - 1) + " pesan)" : t;
    }

    /** Jumlah pesan belum dibaca: yang masih tertera di notifikasi aktif + kiriman baru (min. 1). */
    static int addCount(int active, int added) {
        long sum = (long) Math.max(0, active) + Math.max(1, added);
        return (int) Math.min(sum, 999L);
    }

    /**
     * Jumlah yang sedang tertera di notifikasi trx ini. {@code active} = dari daftar sistem;
     * {@code remembered} = yang dipasang proses ini {@code rememberedAt} (elapsedRealtime, 0 = tak ada).
     * Baru saja dipasang → daftar sistem mungkin belum memuatnya, pakai yang diingat.
     */
    static int unreadBase(int remembered, long rememberedAt, long now, int active) {
        long age = now - rememberedAt;
        if (rememberedAt > 0L && age >= 0L && age < ACTIVE_LIST_LAG_MS) return Math.max(remembered, active);
        return Math.max(0, active);
    }

    /** Pesan s.d. {@code at} sudah dinotifikasi / sudah tampil di chat? {@code at} 0 = tak diketahui. */
    static boolean alreadyHandled(long at, long handled) {
        return at > 0L && handled > 0L && at <= handled;
    }

    /**
     * Pesan baru untuk notifikasi. {@code count} perintah = pesan masuk yang dihitung server sejak
     * perintah sebelumnya (termasuk yang tertahan debounce 60 dtk), rentangnya ({@code lowerExclusive},
     * {@code at}]. Yang di rentang itu sudah terlihat di chat (staf membuka chat di sela debounce)
     * dikurangkan; pesan {@code at} sendiri belum terlihat, jadi minimal 1. Batas bawah tak diketahui
     * (0) → tanpa pengurangan: pesan terlihat yang lebih tua (mis. percakapan pemesanan) bukan bagian
     * hitungan server.
     */
    static int unseenCount(int count, long lowerExclusive, long at, @Nullable Collection<Long> seenAts) {
        int n = Math.max(1, count);
        if (lowerExclusive <= 0L || at <= lowerExclusive || seenAts == null) return n;
        int shown = 0;
        for (Long t : seenAts) {
            if (t != null && t > lowerExclusive && t <= at) shown++;
        }
        return Math.max(1, n - shown);
    }

    /** wa_timestamp (epoch ms) pesan MASUK terbaru yang berasal dari server; 0 bila tak ada. */
    public static long newestInboundMillis(@Nullable List<ChatMessage> list) {
        long max = 0L;
        if (list == null) return max;
        for (ChatMessage m : list) {
            long t = inboundMillis(m);
            if (t > max) max = t;
        }
        return max;
    }

    /**
     * Catat {@code shown} sebagai terlihat: majukan handledUpTo, ingat pesannya untuk
     * {@link #unseenCount}, lepaskan balasan pending yang kini tercakup.
     *
     * @return true bila handledUpTo maju
     */
    static boolean applySeen(TrxState st, @Nullable List<ChatMessage> shown) {
        long before = st.handledUpTo;
        if (shown != null) {
            for (ChatMessage m : shown) {
                long t = inboundMillis(m);
                if (t <= 0L) continue;
                if (t > st.handledUpTo) st.handledUpTo = t;
                // Pesan terhapus tak dihitung server (notifyInbound melewatinya).
                if (!m.deleted) rememberSeenRow(st, seenKey(m, t), t);
            }
        }
        Reply p = st.pending;
        if (p != null && (p.at <= 0L || p.at <= st.handledUpTo)) st.pending = null;
        return st.handledUpTo > before;
    }

    /** Gabung perintah yang dijawab {@link #ACK_CHAT_OPEN} ke balasan yang menunggu terlihat. */
    static void addPending(TrxState st, Reply r, long lower) {
        Reply p = st.pending;
        if (p == null) {
            st.pending = r;
            st.pendingLower = lower;
            return;
        }
        Reply newer = r.at >= p.at ? r : p;
        Reply older = newer == r ? p : r;
        st.pending = new Reply(r.trx,
                newer.name != null ? newer.name : older.name,
                newer.acct != null ? newer.acct : older.acct,
                newer.preview != null ? newer.preview : older.preview,
                Math.max(1, p.count) + Math.max(1, r.count), Math.max(p.at, r.at));
        st.pendingLower = Math.min(st.pendingLower, lower);
    }

    /** Buang ingatan yang tak mungkin lagi memengaruhi keputusan (app hidup berminggu-minggu). */
    static void prune(Map<String, TrxState> m, long now) {
        for (Iterator<TrxState> it = m.values().iterator(); it.hasNext(); ) {
            TrxState st = it.next();
            // unreadBase mengabaikan jumlah yang diingat setelah ACTIVE_LIST_LAG_MS (daftar sistem jadi acuan).
            if (st.unreadPostedAt > 0L && now - st.unreadPostedAt >= ACTIVE_LIST_LAG_MS) {
                st.unreadCount = 0;
                st.unreadPostedAt = 0L;
            }
            // Balasan yang menunggu terlihat dilepas markSeen / onPause layar chat-nya — jangan dibuang.
            if (st.pending == null && now - st.touchedAt >= STATE_IDLE_MS) it.remove();
        }
    }

    private static long inboundMillis(@Nullable ChatMessage m) {
        if (m == null || !"in".equals(m.direction) || m.isOptimistic()) return 0L;
        long t = m.sortMillis();
        return t == Long.MAX_VALUE || t <= 0L ? 0L : t;
    }

    private static String seenKey(ChatMessage m, long t) {
        if (m.waMessageId != null && !m.waMessageId.isEmpty()) return "w:" + m.waMessageId;
        if (m.rowId > 0L) return "r:" + m.rowId;
        return "t:" + t;
    }

    private static void rememberSeenRow(TrxState st, String key, long t) {
        if (st.seen.containsKey(key)) return;
        st.seen.put(key, t);
        Iterator<String> it = st.seen.keySet().iterator();
        while (st.seen.size() > SEEN_CAP && it.hasNext()) {
            it.next();
            it.remove();
        }
    }

    // ---------------------------------------------------------------- internal

    /**
     * Periksa layar chat + pasang notifikasi dalam SATU runnable main thread (tempat onResume/onPause
     * berjalan), jadi jawabannya tak berpacu dengan pengguna yang baru membuka / menutup chat itu.
     * Main thread macet &gt; {@link #MAIN_THREAD_TIMEOUT_MS} dan runnable-nya belum mulai → dipasang
     * dari thread ini tanpa cek layar (dobel lebih baik daripada balasan terlewat); runnable yang
     * sudah telanjur mulai ditunggu sampai selesai, supaya tak pernah terpasang dua kali.
     */
    private static String deliverOnMainThread(Context app, Reply r) {
        if (Looper.myLooper() == Looper.getMainLooper()) return deliver(app, r, true);
        final AtomicBoolean claimed = new AtomicBoolean();
        FutureTask<String> task = new FutureTask<>(
                () -> claimed.compareAndSet(false, true) ? deliver(app, r, true) : null);
        new Handler(Looper.getMainLooper()).post(task);
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    return task.get(MAIN_THREAD_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    if (claimed.compareAndSet(false, true)) return deliver(app, r, false);
                    // Sudah berjalan di main thread — tunggu hasilnya (putaran berikutnya).
                } catch (InterruptedException e) {
                    interrupted = true;
                    if (claimed.compareAndSet(false, true)) return deliver(app, r, false);
                }
            }
        } catch (ExecutionException e) {
            Throwable c = e.getCause();
            if (c instanceof RuntimeException) throw (RuntimeException) c;
            if (c instanceof Error) throw (Error) c;
            throw new RuntimeException(c);
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private static String deliver(Context ctx, Reply r, boolean onMainThread) {
        long lower;
        synchronized (OrderChatNotifier.class) {
            long now = SystemClock.elapsedRealtime();
            prune(states, now);
            TrxState st = stateLocked(r.trx, now);
            lower = st.announcedUpTo;
            if (r.at > st.announcedUpTo) st.announcedUpTo = r.at;
            if (alreadyHandled(r.at, st.handledUpTo)) return ACK_ALREADY_SEEN;
        }
        if (onMainThread && ChatLogActivity.pollIfShowing(r.trx)) {
            // Belum tentu terlihat (daftar digulir ke atas, poll gagal, layar ditutup sebelum poll-nya
            // kembali): ingat sampai markSeen mencakupnya, atau pasang notifikasinya di flushUndisplayed.
            synchronized (OrderChatNotifier.class) {
                addPending(stateLocked(r.trx, SystemClock.elapsedRealtime()), r, lower);
            }
            return ACK_CHAT_OPEN;
        }
        return post(ctx, r, lower);
    }

    /** Pasang / perbarui notifikasi trx ini, kecuali pesannya sudah dinotifikasi / terlihat. */
    private static String post(Context ctx, Reply r, long lower) {
        synchronized (OrderChatNotifier.class) {
            long now = SystemClock.elapsedRealtime();
            TrxState st = stateLocked(r.trx, now);
            String tag = TAG_PREFIX + r.trx;
            Bundle active = activeExtras(ctx, tag);
            long handledAt = Math.max(st.handledUpTo,
                    active != null ? active.getLong(EXTRA_AT, 0L) : 0L);   // cadangan setelah proses mati
            if (alreadyHandled(r.at, handledAt)) return ACK_ALREADY_SEEN;

            int added = unseenCount(r.count, lower, r.at, st.seen.values());
            int total = addCount(unreadBase(st.unreadCount, st.unreadPostedAt, now,
                    active != null ? active.getInt(EXTRA_COUNT, 0) : 0), added);
            String body = body(r.preview, total);

            // Action unik per trx: dua PendingIntent tak saling timpa walau hash request code-nya bentrok.
            // SINGLE_TOP: chat di puncak tumpukan dipakai lagi (onNewIntent); layar kedua untuk trx yang
            // sudah terbuka lebih bawah ditangani ChatLogActivity (layar lama ditutup).
            Intent open = new Intent(ctx, ChatLogActivity.class)
                    .setAction(ACTION_OPEN + "/" + r.trx)
                    .putExtra(ChatLogActivity.EXTRA_MODE, ChatLogActivity.MODE_ORDER)
                    .putExtra(ChatLogActivity.EXTRA_TRANSACTION_UUID, r.trx)
                    .putExtra(ChatLogActivity.EXTRA_CUSTOMER_NAME, r.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent pi = PendingIntent.getActivity(ctx, r.trx.hashCode(), open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            Bundle extras = new Bundle();
            extras.putInt(EXTRA_COUNT, total);
            extras.putLong(EXTRA_AT, Math.max(handledAt, r.at));
            // Tanpa setAutoCancel: ChatLogActivity menghapusnya saat chat-nya benar-benar tampil. Ketukan
            // yang berakhir di gerbang login tak boleh menghilangkan balasannya dari baki.
            NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_inbox)
                    .setContentTitle(title(r.name))
                    .setContentText(body)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                    .setNumber(total)
                    .setContentIntent(pi)
                    .addExtras(extras);
            if (r.acct != null) b.setSubText("via " + r.acct);
            if (r.at > 0L) b.setWhen(r.at).setShowWhen(true);

            String status = deliveryStatus(ctx);
            try {
                NotificationManagerCompat.from(ctx).notify(tag, NOTIF_ID, b.build());
            } catch (SecurityException e) {
                return ACK_NOTIF_DISABLED;   // Android 13+ tanpa izin POST_NOTIFICATIONS
            }
            if (r.at > st.handledUpTo) st.handledUpTo = r.at;
            if (ACK_NOTIFIED.equals(status)) {
                st.unreadCount = total;
                st.unreadPostedAt = now;
            } else {
                st.unreadCount = 0;   // tak tampil → jangan jadi dasar penjumlahan berikutnya
                st.unreadPostedAt = 0L;
            }
            return status;
        }
    }

    private static TrxState stateLocked(String trx, long now) {
        TrxState st = states.get(trx);
        if (st == null) {
            st = new TrxState();
            states.put(trx, st);
        }
        st.touchedAt = now;
        return st;
    }

    /** Extras notifikasi trx ini yang masih tampil (null bila sudah dibuka/digeser/tak ada). */
    @Nullable
    private static Bundle activeExtras(Context ctx, String tag) {
        try {
            NotificationManager nm = ctx.getSystemService(NotificationManager.class);
            if (nm == null) return null;
            for (StatusBarNotification s : nm.getActiveNotifications()) {
                if (s.getId() == NOTIF_ID && tag.equals(s.getTag())) return s.getNotification().extras;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** Ack jujur untuk dashboard: notifikasi dipasang tapi tak akan terlihat bila dimatikan. */
    private static String deliveryStatus(Context ctx) {
        try {
            if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) return ACK_NOTIF_DISABLED;
            NotificationManager nm = ctx.getSystemService(NotificationManager.class);
            NotificationChannel ch = nm != null ? nm.getNotificationChannel(CHANNEL_ID) : null;
            if (ch != null && ch.getImportance() == NotificationManager.IMPORTANCE_NONE) {
                return ACK_CHANNEL_DISABLED;
            }
        } catch (Throwable ignored) {}
        return ACK_NOTIFIED;
    }

    /** Payload {@code at} → epoch ms; 0 bila tak ada / tak terbaca. */
    private static long atMillis(@Nullable String at) {
        long v = at != null ? Ts.millis(at) : Long.MAX_VALUE;
        return v == Long.MAX_VALUE || v <= 0L ? 0L : v;
    }

    /** Nilai string payload; null bila tak ada / JSON null / kosong (optString mengubah null jadi "null"). */
    @Nullable
    private static String str(JSONObject o, String key) {
        if (o == null || !o.has(key) || o.isNull(key)) return null;
        String v = o.optString(key, "").trim();
        return v.isEmpty() ? null : v;
    }
}
