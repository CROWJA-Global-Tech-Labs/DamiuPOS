package com.crowja.damiupos.wa;

import android.app.Notification;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.SettingsDao;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Mendengarkan semua notifikasi sistem dan memfilter notifikasi WhatsApp,
 * lalu meneruskan teks pesan ke {@link OrderParseService} untuk dideteksi
 * sebagai pesanan.
 *
 * <p>WAJIB di-enable manual oleh user: Settings → Notifications →
 * Special access → Notification access → centang DAMIU POS.
 *
 * <p>Sumber notifikasi yang dimonitor:
 * <ul>
 *     <li>com.whatsapp — WhatsApp utama</li>
 *     <li>com.whatsapp.w4b — WhatsApp Business</li>
 * </ul>
 *
 * <p>Anti-duplikat sederhana: setiap (sender + isi) dicache; kalau dalam
 * waktu singkat datang notifikasi identik (mis. WA refresh notif), di-skip.
 */
public class WaListenerService extends NotificationListenerService {

    private static final String TAG = "WaListenerService";

    public static final String PKG_WHATSAPP = "com.whatsapp";
    public static final String PKG_WHATSAPP_BUSINESS = "com.whatsapp.w4b";

    /** Broadcast action saat ada order baru tersimpan. */
    public static final String ACTION_NEW_ORDER = "com.crowja.damiupos.NEW_ORDER";
    public static final String EXTRA_ORDER_ID = "order_id";

    /** TTL anti-duplikat (ms). */
    private static final long DEDUP_WINDOW_MS = 5_000L;

    private final Map<String, Long> recentSeen = new HashMap<>();

    /** Pattern "Sender: message" — heuristik untuk group chat. */
    private static final Pattern GROUP_TEXT_PATTERN =
            Pattern.compile("^[^:\\n]{1,40}:\\s.+", Pattern.DOTALL);

    /** Channel ID WA yang BUKAN pesan chat (skip semuanya). */
    private static final String[] NON_CHAT_CHANNEL_HINTS = {
            "call", "voip", "video", "status", "update", "media", "backup",
            "broadcast", "silent_voip"
    };

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) return;
        String pkg = sbn.getPackageName();
        if (!PKG_WHATSAPP.equals(pkg) && !PKG_WHATSAPP_BUSINESS.equals(pkg)) {
            return;
        }

        // Skip kalau fitur belum di-enable user
        SettingsDao settings = new SettingsDao(DatabaseHelper.getInstance(getApplicationContext()));
        if (!settings.isWaAutoDetectEnabled()) return;

        Notification n = sbn.getNotification();
        if (n == null) return;
        Bundle extras = n.extras;
        if (extras == null) return;

        // ── FILTER 1: skip notif non-chat (call, status, broadcast, dll.)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            String channelId = n.getChannelId();
            if (channelId != null) {
                String lower = channelId.toLowerCase();
                for (String hint : NON_CHAT_CHANNEL_HINTS) {
                    if (lower.contains(hint)) {
                        Log.d(TAG, "Skip non-chat channel: " + channelId);
                        return;
                    }
                }
            }
        }
        // Category check — calls biasanya CATEGORY_CALL
        String category = n.category;
        if (Notification.CATEGORY_CALL.equals(category)
                || Notification.CATEGORY_PROGRESS.equals(category)
                || Notification.CATEGORY_STATUS.equals(category)
                || Notification.CATEGORY_TRANSPORT.equals(category)
                || Notification.CATEGORY_SERVICE.equals(category)) {
            Log.d(TAG, "Skip category: " + category);
            return;
        }

        // ── FILTER 2: skip kalau ini group chat
        if (isGroupConversation(n, extras)) {
            Log.d(TAG, "Skip group chat notif");
            return;
        }

        // WA notif (personal chat):
        //   EXTRA_TITLE = nama pengirim
        //   EXTRA_TEXT  = isi pesan terbaru
        //   MessagingStyle.getMessages() = SEMUA pesan tertunda di chat
        //                                  (kalau ada multi-message bundling)
        CharSequence title = extras.getCharSequence(Notification.EXTRA_TITLE);
        if (title == null) return;
        String sender = title.toString().trim();
        if (sender.isEmpty()) return;

        // Coba ambil semua pesan tertunda dari MessagingStyle.
        // Saat pelanggan kirim pesan beruntun ("bro" → "mau pesan" → "ro 5"),
        // WA bundling ke notif yg sama dgn semua message di MessagingStyle.
        String message = extractMergedMessage(n, extras);
        if (message == null || message.isEmpty()) return;

        // Skip notifikasi summary ("3 pesan baru dari 2 chat") yang biasanya
        // tidak punya isi pesan substantif.
        if (message.matches("(?i).*\\d+\\s*(new\\s*)?messages?.*from.*")) return;
        if (message.matches("(?i).*\\d+\\s*pesan\\s*baru.*")) return;
        // "Ringkasan" notifikasi (channel "Other notifications") yang
        // muncul saat panggilan, dll.
        if ("WhatsApp".equalsIgnoreCase(sender) || "WhatsApp Business".equalsIgnoreCase(sender)) {
            return;
        }
        // ── FILTER 3 (heuristik terakhir): kalau body teks berformat
        // "Nama: pesan" sementara title (sender) BUKAN nama itu, kemungkinan
        // ini grup yang lolos dari MessagingStyle check (mis. di Android
        // versi lama yg tidak set EXTRA_IS_GROUP_CONVERSATION).
        if (GROUP_TEXT_PATTERN.matcher(message).matches()) {
            String prefix = message.split(":\\s", 2)[0].trim();
            // Kalau prefix beda dari sender (sender = nama grup, prefix = sender),
            // → ini group chat. Skip.
            if (!prefix.equalsIgnoreCase(sender) && prefix.length() < 40) {
                Log.d(TAG, "Skip — '" + prefix + ":' prefix suggests group");
                return;
            }
        }

        // Anti-duplikat
        String dedupKey = sender + "|" + message;
        long now = System.currentTimeMillis();
        Long lastSeen = recentSeen.get(dedupKey);
        if (lastSeen != null && (now - lastSeen) < DEDUP_WINDOW_MS) return;
        recentSeen.put(dedupKey, now);
        // Bersihkan entry lama supaya map tidak tumbuh tanpa batas
        recentSeen.entrySet().removeIf(e -> (now - e.getValue()) > 60_000L);

        Log.d(TAG, "WA notif from='" + sender + "' msg='" + message + "'");

        OrderParseService.handleIncoming(getApplicationContext(),
                sender, extractPhone(sender), message,
                new OrderParseService.Listener() {
                    @Override public void onOrderStored(com.crowja.damiupos.model.OrderInbox stored) {
                        // Broadcast UI update apa pun verdict-nya — supaya
                        // user tetap bisa lihat di Inbox kalau ingin review
                        // (terutama di mode "Pakai AI" yg simpan semua hasil).
                        Intent broadcast = new Intent(ACTION_NEW_ORDER);
                        broadcast.setPackage(getPackageName());
                        broadcast.putExtra(EXTRA_ORDER_ID, stored.getId());
                        sendBroadcast(broadcast);
                        // Sound + alert HANYA kalau parser yakin ini pesanan.
                        // Pesan promo / chat biasa / "is_order=false" tidak
                        // boleh memicu nada dering.
                        ParsedOrder parsed =
                                ParsedOrder.fromJson(stored.getParsedJson());
                        if (parsed.isOrder) {
                            OrderAlertService.start(getApplicationContext());
                        } else {
                            Log.d(TAG, "Skip sound — parsed.isOrder=false");
                        }
                    }
                    @Override public void onError(String message) {
                        Log.w(TAG, "Parser error: " + message);
                    }
                });
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        // No-op
    }

    /**
     * Ambil semua pesan tertunda dari MessagingStyle WA. Kalau pelanggan
     * kirim multi-message ("bro", "mau pesan", "ro 5"), WA bundle ke 1
     * notif → MessagingStyle berisi list semua message → kita gabung pakai
     * newline.
     *
     * <p>Kalau MessagingStyle tidak tersedia (Android lama / format lain),
     * fallback ke {@code EXTRA_TEXT} (single message terbaru).
     */
    private static String extractMergedMessage(Notification n, Bundle extras) {
        try {
            NotificationCompat.MessagingStyle style =
                    NotificationCompat.MessagingStyle
                            .extractMessagingStyleFromNotification(n);
            if (style != null) {
                java.util.List<NotificationCompat.MessagingStyle.Message> messages =
                        style.getMessages();
                if (messages != null && !messages.isEmpty()) {
                    StringBuilder sb = new StringBuilder();
                    for (NotificationCompat.MessagingStyle.Message m : messages) {
                        CharSequence t = m.getText();
                        if (t == null) continue;
                        String line = t.toString().trim();
                        if (line.isEmpty()) continue;
                        if (sb.length() > 0) sb.append('\n');
                        sb.append(line);
                    }
                    if (sb.length() > 0) return sb.toString();
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "MessagingStyle parse error: " + e.getMessage());
        }
        // Fallback: single text only
        CharSequence text = extras.getCharSequence(Notification.EXTRA_TEXT);
        return text != null ? text.toString().trim() : null;
    }

    /**
     * Best-effort: kalau title notif WA berupa nomor (bukan nama kontak
     * tersimpan), normalize ke string angka.
     */
    private static String extractPhone(String sender) {
        if (sender == null) return "";
        String digits = sender.replaceAll("[^0-9+]", "");
        // Heuristik: kalau setelah strip masih ada minimal 8 digit, anggap
        // ini nomor; kalau tidak, sender adalah nama kontak.
        return digits.length() >= 8 ? digits : "";
    }

    /**
     * Deteksi apakah notifikasi WA ini berasal dari group chat.
     * Cek beberapa sinyal berurutan:
     * <ol>
     *   <li>{@code Notification.EXTRA_IS_GROUP_CONVERSATION} — boolean
     *       flag resmi (Android 9+).</li>
     *   <li>{@code MessagingStyle.isGroupConversation()} — diset oleh
     *       app yg pakai MessagingStyle template.</li>
     *   <li>{@code MessagingStyle.getConversationTitle()} — non-null untuk
     *       group, null untuk personal chat.</li>
     * </ol>
     * Default false kalau tidak ada sinyal jelas.
     */
    private static boolean isGroupConversation(Notification n, Bundle extras) {
        // Sinyal #1: explicit group flag
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (extras.containsKey(Notification.EXTRA_IS_GROUP_CONVERSATION)) {
                if (extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false)) {
                    return true;
                }
            }
        }
        // Sinyal #2 & #3: MessagingStyle metadata
        try {
            NotificationCompat.MessagingStyle style =
                    NotificationCompat.MessagingStyle
                            .extractMessagingStyleFromNotification(n);
            if (style != null) {
                if (style.isGroupConversation()) return true;
                CharSequence title = style.getConversationTitle();
                if (title != null && !title.toString().trim().isEmpty()) {
                    // WA set conversation title hanya untuk group chat
                    return true;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "MessagingStyle parse error: " + e.getMessage());
        }
        return false;
    }
}
