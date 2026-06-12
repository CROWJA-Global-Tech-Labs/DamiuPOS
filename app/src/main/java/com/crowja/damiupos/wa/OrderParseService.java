package com.crowja.damiupos.wa;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.crowja.damiupos.db.CustomerDao;
import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.OrderInboxDao;
import com.crowja.damiupos.db.ProductDao;
import com.crowja.damiupos.db.SettingsDao;
import com.crowja.damiupos.model.Customer;
import com.crowja.damiupos.model.OrderInbox;
import com.crowja.damiupos.model.Product;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Orkestrasi parser pesan WA berdasarkan mode di {@link SettingsDao#getWaParseMode()}:
 * <ul>
 *   <li>{@code default} — regex saja. Kalau gagal, pesan tetap masuk inbox tapi
 *       di-flag {@code is_order=false} sehingga user bisa decide manual.</li>
 *   <li>{@code ai} — langsung Claude.</li>
 *   <li>{@code hybrid} — regex dulu (cepat & gratis); kalau confidence rendah
 *       atau tidak yakin, fallback ke Claude.</li>
 * </ul>
 *
 * <p>Setelah parse selesai, hasil disimpan ke {@code order_inbox} (status
 * PENDING) dan caller di-notify via {@link Listener#onOrderStored(OrderInbox)}.
 *
 * <p>Semua kerja DB & HTTP dijalankan di {@link #EXEC} (single thread executor)
 * supaya request berurutan dan tidak race-condition.
 */
public class OrderParseService {

    private static final String TAG = "OrderParseService";
    /**
     * Di mode HYBRID, regex baru dipercaya tanpa AI kalau confidence
     * ≥ ini. Threshold cukup tinggi (0.85) supaya hanya match yang
     * "intent + produk DB exact + qty explicit" yang bypass AI; sisanya
     * tetap diverifikasi Claude.
     */
    private static final double HYBRID_REGEX_MIN_CONF = 0.85;

    /**
     * Confidence di bawah ini dianggap bukan pesanan — tidak trigger
     * suara/notifikasi alert. Di mode default/hybrid juga tidak disimpan
     * ke inbox (skip total). Di mode "Pakai AI" tetap disimpan supaya
     * user bisa review verdict AI.
     */
    private static final double MIN_ORDER_CONFIDENCE = 0.4;

    /**
     * Window untuk merge pesan dari sender yg sama. Kalau pelanggan kirim
     * "bro" → "mau pesan" → "ro 5" dalam 5 menit, semuanya digabung ke
     * satu inbox row, bukan 3 row terpisah.
     */
    private static final int MERGE_WINDOW_SECONDS = 300;

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public interface Listener {
        void onOrderStored(OrderInbox stored);
        void onError(String message);
    }

    /** Async parse + simpan. Listener dipanggil di main thread. */
    public static void handleIncoming(Context appCtx, String senderName, String senderPhone,
                                      String message, Listener listener) {
        EXEC.execute(() -> {
            try {
                OrderInbox stored = handleSync(appCtx, senderName, senderPhone, message);
                if (listener != null && stored != null) {
                    MAIN.post(() -> listener.onOrderStored(stored));
                }
            } catch (Exception e) {
                Log.e(TAG, "handleIncoming error", e);
                if (listener != null) {
                    MAIN.post(() -> listener.onError(e.getMessage()));
                }
            }
        });
    }

    /** Synchronous (test-friendly). Boleh dipanggil dari background thread. */
    public static OrderInbox handleSync(Context appCtx, String senderName,
                                        String senderPhone, String message) {
        if (message == null || message.trim().isEmpty()) return null;

        DatabaseHelper db = DatabaseHelper.getInstance(appCtx);
        SettingsDao settings = new SettingsDao(db);
        ProductDao productDao = new ProductDao(db);
        CustomerDao customerDao = new CustomerDao(db);
        OrderInboxDao inboxDao = new OrderInboxDao(db);

        // ─── STRIP PREFIX yang sudah pernah di-handle.
        // WA notif sering re-bundle pesan lama + baru kalau user belum
        // buka chat WA-nya, padahal user sudah mark APPROVED/REJECTED di
        // DAMIU POS. Tanpa strip → kita simpan pesan duplikat.
        OrderInbox lastFromSender = inboxDao.findLatestFromSender(senderName);
        if (lastFromSender != null
                && !OrderInbox.STATUS_PENDING.equals(lastFromSender.getStatus())) {
            String oldRaw = lastFromSender.getRawMessage();
            if (oldRaw != null && !oldRaw.isEmpty() && message.startsWith(oldRaw)) {
                String remaining = message.substring(oldRaw.length())
                        .replaceFirst("^\\s+", "");
                if (remaining.isEmpty()) {
                    Log.d(TAG, "Pesan persis sama dgn inbox terakhir yg sudah handled — skip");
                    return null;
                }
                Log.d(TAG, "Strip prefix history (status=" + lastFromSender.getStatus()
                        + ") → '" + remaining + "'");
                message = remaining;
            }
        }

        List<Product> products = productDao.getAll();
        String mode = settings.getWaParseMode();
        Log.d(TAG, "Parsing message='" + message + "' mode=" + mode
                + " sender=" + senderName + " products=" + products.size());

        // SELALU jalankan regex sebagai baseline — gratis, instant, jadi
        // safety net kalau AI gagal/disabled di semua mode.
        ParsedOrder regexResult = WaParseHelper.parse(message, products);
        Log.d(TAG, "Regex result: isOrder=" + regexResult.isOrder
                + " conf=" + regexResult.confidence
                + " type=" + regexResult.type
                + " items=" + regexResult.items.size());

        ParsedOrder parsed;
        String parserUsed;

        if (SettingsDao.PARSE_MODE_DEFAULT.equals(mode)) {
            parsed = regexResult;
            parserUsed = OrderInbox.PARSER_REGEX;
        } else if (SettingsDao.PARSE_MODE_AI.equals(mode)) {
            // AI-only: jalankan Claude & TRUST verdict-nya (termasuk
            // is_order=false). Hanya fallback ke regex kalau AI benar-benar
            // gagal (no api key / network / quota).
            ParsedOrder ai = tryClaude(settings, customerDao,
                    senderName, senderPhone, message, products);
            if (ai != null) {
                parsed = ai;
                parserUsed = OrderInbox.PARSER_CLAUDE;
            } else if (regexResult.isOrder) {
                Log.d(TAG, "AI tidak tersedia — fallback ke regex");
                parsed = regexResult;
                parserUsed = OrderInbox.PARSER_REGEX;
            } else {
                parsed = new ParsedOrder();
                parserUsed = OrderInbox.PARSER_CLAUDE;
            }
        } else {
            // HYBRID
            if (regexResult.isOrder && regexResult.confidence >= HYBRID_REGEX_MIN_CONF) {
                parsed = regexResult;
                parserUsed = OrderInbox.PARSER_REGEX;
            } else {
                ParsedOrder ai = tryClaude(settings, customerDao,
                        senderName, senderPhone, message, products);
                if (ai != null && ai.isOrder) {
                    parsed = ai;
                    parserUsed = OrderInbox.PARSER_CLAUDE;
                } else if (regexResult.isOrder) {
                    Log.d(TAG, "AI failed/no-order — fallback ke regex");
                    parsed = regexResult;
                    parserUsed = OrderInbox.PARSER_REGEX;
                } else if (ai != null) {
                    parsed = ai;
                    parserUsed = OrderInbox.PARSER_CLAUDE;
                } else {
                    parsed = regexResult; // empty
                    parserUsed = OrderInbox.PARSER_REGEX;
                }
            }
        }

        // Confidence rendah → override jadi "bukan pesanan" supaya tidak
        // trigger suara/alert. Konsisten di semua mode parser.
        if (parsed.isOrder && parsed.confidence < MIN_ORDER_CONFIDENCE) {
            Log.d(TAG, "Confidence " + parsed.confidence + " < "
                    + MIN_ORDER_CONFIDENCE + " — override is_order=false");
            parsed.isOrder = false;
        }

        Log.d(TAG, "Final parsed: isOrder=" + parsed.isOrder
                + " conf=" + parsed.confidence + " parser=" + parserUsed);

        // Skip simpan kalau parser yakin ini bukan order DAN bukan mode AI
        // (mode AI selalu simpan supaya user bisa lihat & verifikasi)
        if (!parsed.isOrder && !SettingsDao.PARSE_MODE_AI.equals(mode)) {
            Log.d(TAG, "Bukan order — tidak disimpan ke inbox");
            return null;
        }

        // ─── MERGE: kalau sender yg sama sudah punya inbox PENDING dlm
        // window 5 menit, UPDATE row tsb (jangan insert baru). Berguna saat
        // pelanggan kirim multi-message: "bro" → "mau pesan" → "ro 5 ya".
        OrderInbox existing = inboxDao.findRecentPendingFromSender(
                senderName, MERGE_WINDOW_SECONDS);
        if (existing != null) {
            // Kalau isi pesan persis sama dengan yg sudah tersimpan, no-op
            // (anti-duplikat: WA suka re-fire notif yg sama).
            if (message.equals(existing.getRawMessage())) {
                Log.d(TAG, "Merge skip — pesan duplikat dari sender sama");
                return null;
            }
            inboxDao.updateContent(existing.getId(), message,
                    parsed.toJson(), parserUsed);
            existing.setRawMessage(message);
            existing.setParsedJson(parsed.toJson());
            existing.setParserUsed(parserUsed);
            existing.setReplied(false); // ada pesan baru → user perlu re-konfirmasi
            Log.d(TAG, "Merged ke inbox id=" + existing.getId()
                    + " (sender '" + senderName + "')");
            return existing;
        }

        // ─── INSERT baru
        OrderInbox row = new OrderInbox();
        row.setSenderName(senderName);
        row.setSenderPhone(senderPhone);
        row.setRawMessage(message);
        row.setParsedJson(parsed.toJson());
        row.setParserUsed(parserUsed);
        row.setStatus(OrderInbox.STATUS_PENDING);
        // Best-effort match customer berdasarkan nomor telepon
        long customerId = matchCustomerByPhone(customerDao, senderPhone);
        if (customerId == 0) {
            customerId = matchCustomerByName(customerDao, senderName);
        }
        row.setCustomerId(customerId);

        long id = inboxDao.insert(row);
        if (id <= 0) return null;
        row.setId(id);
        return row;
    }

    private static ParsedOrder tryClaude(SettingsDao settings, CustomerDao customerDao,
                                         String senderName, String senderPhone,
                                         String message, List<Product> products) {
        String token = settings.getClaudeApiKey();
        if (token == null || token.trim().isEmpty()) {
            Log.w(TAG, "Claude proxy token kosong — skip AI call (set di Settings → Auto-Detect WhatsApp)");
            settings.setLastClaudeError("Bearer token belum di-set");
            return null;
        }
        Log.d(TAG, "Claude proxy token OK (" + token.length() + " chars)");
        try {
            // Beri AI sample 30 pelanggan agar bisa match nama/nomor
            List<Customer> all = customerDao.getAll();
            List<String> summaries = new ArrayList<>();
            int max = Math.min(30, all.size());
            for (int i = 0; i < max; i++) {
                Customer c = all.get(i);
                summaries.add(c.getName() + " - " + (c.getPhone() != null ? c.getPhone() : ""));
            }
            Log.d(TAG, "Calling Claude proxy with " + products.size()
                    + " products, " + summaries.size() + " customers");
            ParsedOrder result = ClaudeClient.parse(token, senderName, senderPhone,
                    message, products, summaries);
            Log.d(TAG, "Claude OK: isOrder=" + result.isOrder
                    + " conf=" + result.confidence
                    + " items=" + result.items.size());
            settings.setLastClaudeError(""); // success → clear error flag
            return result;
        } catch (Exception e) {
            String errMsg = e.getMessage() != null ? e.getMessage() : "Unknown error";
            // Map error → pesan readable di Settings UI. Errors di sini
            // bisa berasal dari proxy itu sendiri (401 token salah, 502 SDK
            // gagal call upstream) atau dari Anthropic via proxy.
            String shortErr;
            if (errMsg.contains("HTTP 401")) {
                shortErr = "Bearer token salah / dicabut admin";
            } else if (errMsg.contains("HTTP 400")) {
                shortErr = "Request invalid (cek format payload)";
            } else if (errMsg.contains("HTTP 502") || errMsg.contains("bad_gateway")
                    || errMsg.contains("claude_error")) {
                shortErr = "Proxy gagal call Claude — kemungkinan Max plan logout / quota habis";
            } else if (errMsg.contains("HTTP 429") || errMsg.contains("rate_limit")) {
                shortErr = "Rate limit Claude — tunggu sebentar";
            } else if (errMsg.contains("HTTP 529") || errMsg.contains("overloaded")) {
                shortErr = "Server Claude sedang overloaded — coba lagi nanti";
            } else if (errMsg.contains("UnknownHostException") || errMsg.contains("timeout")
                    || errMsg.contains("SocketTimeout")) {
                shortErr = "Tidak ada internet / proxy unreachable";
            } else if (errMsg.length() > 120) {
                shortErr = errMsg.substring(0, 120) + "...";
            } else {
                shortErr = errMsg;
            }
            settings.setLastClaudeError(shortErr);
            Log.w(TAG, "Claude error: " + e.getMessage(), e);
            return null;
        }
    }

    private static long matchCustomerByPhone(CustomerDao dao, String phone) {
        if (phone == null || phone.isEmpty()) return 0;
        List<Customer> matches = dao.search(phone);
        return matches.isEmpty() ? 0 : matches.get(0).getId();
    }

    /**
     * Match by name dengan fallback bertingkat:
     * <ol>
     *     <li>Search exact / substring (current behavior dao.search)</li>
     *     <li>Strip karakter non-alphanumerik (emoji, simbol) → search ulang</li>
     *     <li>Hanya pakai kata pertama (mis. "Pak Budi 🌟" → "Pak")</li>
     * </ol>
     * Berguna karena WA notif title sering berisi emoji/suffix yang tidak
     * ada di nama pelanggan DB.
     */
    private static long matchCustomerByName(CustomerDao dao, String name) {
        if (name == null || name.isEmpty()) return 0;
        // 1. Search apa adanya
        List<Customer> matches = dao.search(name);
        if (!matches.isEmpty()) return matches.get(0).getId();

        // 2. Bersihkan emoji + simbol, coba ulang
        String cleaned = name.replaceAll("[^\\p{L}\\p{N}\\s]", " ")
                .replaceAll("\\s+", " ").trim();
        if (!cleaned.isEmpty() && !cleaned.equalsIgnoreCase(name)) {
            matches = dao.search(cleaned);
            if (!matches.isEmpty()) return matches.get(0).getId();
        }

        // 3. Coba per kata (mulai dari kata terpanjang — biasanya itu nama
        //    distingtif, mis. "Pak Budi" → "Budi" lebih unik dari "Pak")
        String[] tokens = cleaned.isEmpty() ? new String[0] : cleaned.split("\\s+");
        // Sort by length desc
        java.util.Arrays.sort(tokens, (a, b) -> Integer.compare(b.length(), a.length()));
        for (String token : tokens) {
            if (token.length() < 3) continue; // skip "Pa", "Bu", dll yg ambigu
            matches = dao.search(token);
            if (!matches.isEmpty()) {
                Log.d(TAG, "Customer matched via token '" + token + "' from name '" + name + "'");
                return matches.get(0).getId();
            }
        }
        return 0;
    }
}
