package com.crowja.damiupos.wa;

import com.crowja.damiupos.model.Product;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser regex untuk pesan WhatsApp pelanggan depot air minum.
 *
 * <p>Strategi:
 * <ol>
 *     <li>Tokenize pesan: cari semua angka (qty) + match produk
 *         (dari DB & alias generik) berdasarkan posisi karakter.</li>
 *     <li>Walk token kiri→kanan dan pasangkan qty dengan produk
 *         yang berdekatan (mis. "mineral 5 ro 8" → 5×mineral + 8×ro).</li>
 *     <li>Confidence dihitung dari kelengkapan sinyal — semakin lengkap
 *         (intent + produk + qty eksplisit) semakin tinggi.</li>
 * </ol>
 *
 * <p>Pesan ambigu (cuma "pesan", "ro") dapat confidence rendah supaya
 * {@link OrderParseService} mode hybrid fallback ke Claude AI.
 */
public class WaParseHelper {

    /** Kata kerja pemesanan. */
    private static final Pattern HINT_ORDER = Pattern.compile(
            "\\b(pesan|kirim|antar|tolong|order|minta|mau|butuh|mohon)\\b",
            Pattern.CASE_INSENSITIVE);

    /** Botol galon kosong saja (tanpa air). */
    private static final Pattern HINT_BOTOL_KOSONG = Pattern.compile(
            "(botol\\s*kosong|kosongan|jual\\s*botol|botol\\s*aja|botol\\s*saja)",
            Pattern.CASE_INSENSITIVE);

    /** Pengembalian botol galon. */
    private static final Pattern HINT_KEMBALI = Pattern.compile(
            "\\b(kembali|tukar|return|balik(in)?|ambil)\\b",
            Pattern.CASE_INSENSITIVE);

    /** Penanda urgent. */
    private static final Pattern HINT_URGENT = Pattern.compile(
            "\\b(urgent|cepetan|cepat|sekarang|segera|asap|mendesak|buru[\\-\\s]?buru)\\b",
            Pattern.CASE_INSENSITIVE);

    /** Angka standalone (bukan bagian dari nomor telepon). */
    private static final Pattern ANY_QTY = Pattern.compile(
            "(?<![\\d])(\\d{1,3})(?![\\d])");

    /** Alias produk yang lazim dipakai pelanggan, lowercase. */
    private static final String[] PRODUCT_ALIASES = {
            "ro", "reverse osmosis",
            "mineral", "air mineral",
            "galon", "gallon",
            "aqua", "le minerale", "pristine",
            "isi ulang"
    };

    /** Token dengan posisi karakter — qty atau product reference. */
    private static class Tok {
        int pos;
        boolean isQty;
        int qty;
        String productLabel;
    }

    /**
     * @param message Isi pesan WA
     * @param products Daftar produk dari DB (untuk match nama produk)
     * @return ParsedOrder. Confidence rendah → caller (mode hybrid) fallback ke AI.
     */
    public static ParsedOrder parse(String message, List<Product> products) {
        ParsedOrder out = new ParsedOrder();
        if (message == null || message.trim().isEmpty()) return out;
        String text = message.trim();
        String lower = text.toLowerCase(Locale.ROOT);

        boolean hasOrderKeyword = HINT_ORDER.matcher(lower).find();

        // Tokenisasi — kumpulkan semua angka & produk dgn posisinya
        List<Tok> tokens = tokenize(text, lower, products);
        boolean hasProduct = false;
        boolean hasQty = false;
        boolean dbProductMatched = false;
        for (Tok t : tokens) {
            if (t.isQty) hasQty = true;
            else {
                hasProduct = true;
                if (productExistsInDb(t.productLabel, products)) {
                    dbProductMatched = true;
                }
            }
        }

        // Tidak ada sinyal sama sekali → bukan pesanan
        if (!hasOrderKeyword && !hasProduct) {
            return out;
        }

        // Tipe transaksi
        if (HINT_BOTOL_KOSONG.matcher(lower).find()) {
            out.type = ParsedOrder.TYPE_JUAL_BOTOL;
        } else if (HINT_KEMBALI.matcher(lower).find()) {
            out.type = ParsedOrder.TYPE_KEMBALI;
        } else {
            out.type = ParsedOrder.TYPE_JUAL;
        }

        // Pasangkan qty + produk berdekatan
        out.items = pairTokens(tokens, out.type);

        // Kalau tokens kosong tapi ada keyword (mis. "pesan" saja),
        // emit default item 1× Galon biar approve flow tetap berguna.
        if (out.items.isEmpty()) {
            String defaultLabel = ParsedOrder.TYPE_JUAL_BOTOL.equals(out.type)
                    ? "Botol Galon Kosong" : "Galon";
            out.items.add(new ParsedOrder.Item(defaultLabel, 1));
        }

        out.urgent = HINT_URGENT.matcher(lower).find();
        out.notes = "";
        out.isOrder = true;

        // Confidence scoring:
        //   intent + produk DB + qty explicit       → 0.9 (regex handle sendiri)
        //   intent + produk DB                      → 0.85 (regex handle sendiri)
        //   intent + alias generik + qty explicit   → 0.78
        //   intent + produk apa pun                 → 0.7
        //   intent saja                             → 0.35 (let AI take it)
        //   produk saja                             → 0.5 (let AI take it)
        if (hasOrderKeyword && dbProductMatched && hasQty) out.confidence = 0.9;
        else if (hasOrderKeyword && dbProductMatched) out.confidence = 0.85;
        else if (hasOrderKeyword && hasProduct && hasQty) out.confidence = 0.78;
        else if (hasOrderKeyword && hasProduct) out.confidence = 0.7;
        else if (hasProduct) out.confidence = 0.5;
        else out.confidence = 0.35;
        // Multi-item: confidence sedikit naik karena lebih spesifik
        if (out.items.size() > 1) {
            out.confidence = Math.min(0.95, out.confidence + 0.05);
        }
        return out;
    }

    /**
     * Kumpulkan semua qty + produk reference di pesan beserta posisi
     * karakter, sorted by position.
     *
     * <p>Untuk match produk:
     * <ol>
     *     <li>Coba match nama produk DB sebagai substring lengkap
     *         (mis. DB "Le Minerale 19L" match teks "le minerale 19l").</li>
     *     <li>Kalau tidak match, coba per-kata signifikan dari nama produk
     *         dengan word boundary (mis. DB "Aqua 19L" → kata "aqua" match
     *         pesan "pesan aqua 5"). Skip kata pendek/generik.</li>
     *     <li>Terakhir coba alias generik ("galon", "ro", "mineral", dll.)
     *         untuk istilah yang sering dipakai pelanggan tapi mungkin
     *         tidak ada di nama produk DB.</li>
     * </ol>
     *
     * <p>Dedup posisi yang overlap supaya satu kata di pesan hanya
     * jadi satu token produk.
     */
    private static List<Tok> tokenize(String text, String lower, List<Product> products) {
        List<Tok> tokens = new ArrayList<>();

        // Qty
        Matcher numM = ANY_QTY.matcher(text);
        while (numM.find()) {
            try {
                int n = Integer.parseInt(numM.group(1));
                if (n >= 1 && n <= 999) {
                    Tok t = new Tok();
                    t.pos = numM.start();
                    t.isQty = true;
                    t.qty = n;
                    tokens.add(t);
                }
            } catch (NumberFormatException ignored) {}
        }

        // DB products — proses dalam 2 pass dengan urutan TERPANJANG dulu.
        //
        // Tujuan: kalau ada produk "MINERAL" + "BIO MINERAL" di DB dan user
        // tulis "mineral", kita HARUS match ke MINERAL exact, bukan BIO
        // MINERAL via per-token fallback. Solusi: proses BIO MINERAL dulu
        // (Strategy 1 = cari "bio mineral" full substring → tidak ketemu)
        // baru MINERAL (Strategy 1 = cari "mineral" → ketemu, claim posisi).
        // Kemudian Strategy 2 untuk produk yang belum match (BIO MINERAL)
        // akan skip karena posisi sudah dipakai.
        if (products != null && !products.isEmpty()) {
            java.util.List<Product> sortedProducts = new ArrayList<>(products);
            // Sort by name length DESC — longer names processed first
            Collections.sort(sortedProducts, (a, b) -> {
                int la = a.getName() != null ? a.getName().length() : 0;
                int lb = b.getName() != null ? b.getName().length() : 0;
                return Integer.compare(lb, la);
            });

            // ─── PASS 1: Strategi 1 (substring full match) untuk semua produk
            java.util.List<Product> unmatchedInPass1 = new ArrayList<>();
            for (Product p : sortedProducts) {
                if (p.getName() == null) continue;
                String pn = p.getName().toLowerCase(Locale.ROOT).trim();
                if (pn.isEmpty()) continue;
                boolean fullMatched = false;
                int from = 0;
                while (from < lower.length()) {
                    int idx = lower.indexOf(pn, from);
                    if (idx < 0) break;
                    if (!hasProductTokenAt(tokens, idx, pn.length())) {
                        Tok t = new Tok();
                        t.pos = idx;
                        t.productLabel = p.getName();
                        tokens.add(t);
                        fullMatched = true;
                    }
                    from = idx + pn.length();
                }
                if (!fullMatched) unmatchedInPass1.add(p);
            }

            // ─── PASS 2: Strategi 2 (per-kata) HANYA untuk produk yang
            //     belum match di Pass 1. Skip token kalau posisinya sudah
            //     dipakai produk lain (lebih spesifik).
            for (Product p : unmatchedInPass1) {
                String pn = p.getName().toLowerCase(Locale.ROOT).trim();
                for (String word : pn.split("\\s+")) {
                    if (word.length() < 3) continue;
                    if (isStopWord(word)) continue;
                    Pattern wb = Pattern.compile(
                            "\\b" + Pattern.quote(word) + "\\b",
                            Pattern.CASE_INSENSITIVE);
                    Matcher m = wb.matcher(text);
                    while (m.find()) {
                        int pos = m.start();
                        if (hasProductTokenAt(tokens, pos, word.length())) continue;
                        Tok t = new Tok();
                        t.pos = pos;
                        t.productLabel = p.getName();
                        tokens.add(t);
                    }
                }
            }
        }

        // Strategi 3: alias generik — terakhir, hanya kalau belum ada
        // token produk pada posisi yg sama
        for (String alias : PRODUCT_ALIASES) {
            Pattern wb = Pattern.compile(
                    "\\b" + Pattern.quote(alias) + "\\b",
                    Pattern.CASE_INSENSITIVE);
            Matcher am = wb.matcher(text);
            while (am.find()) {
                int p = am.start();
                if (hasProductTokenAt(tokens, p, alias.length())) continue;
                Tok t = new Tok();
                t.pos = p;
                t.productLabel = aliasToProductLabel(alias, products);
                tokens.add(t);
            }
        }

        Collections.sort(tokens, (a, b) -> Integer.compare(a.pos, b.pos));
        return tokens;
    }

    /** Kata umum yang tidak boleh dijadikan kunci match produk. */
    private static boolean isStopWord(String word) {
        if (word == null) return true;
        switch (word.toLowerCase(Locale.ROOT)) {
            case "air": case "minum": case "isi": case "ulang":
            case "liter": case "ltr":
            case "untuk": case "dari": case "yang":
                return true;
            default:
                return false;
        }
    }

    /**
     * Walk tokens kiri→kanan, pasangkan qty dengan produk terdekat
     * (sebelum/sesudah). Kalau tidak ada qty untuk produk → default 1.
     */
    private static List<ParsedOrder.Item> pairTokens(List<Tok> tokens, String type) {
        List<ParsedOrder.Item> items = new ArrayList<>();
        Integer pendingQty = null;
        String pendingProduct = null;

        for (Tok t : tokens) {
            if (t.isQty) {
                if (pendingProduct != null) {
                    items.add(new ParsedOrder.Item(pendingProduct, t.qty));
                    pendingProduct = null;
                } else {
                    // Kalau sudah ada qty pending dan ketemu qty lain,
                    // overwrite (contoh: "5 6 galon" → pakai qty terakhir).
                    pendingQty = t.qty;
                }
            } else {
                // Token produk
                if (pendingQty != null) {
                    items.add(new ParsedOrder.Item(t.productLabel, pendingQty));
                    pendingQty = null;
                } else if (pendingProduct != null) {
                    // Dua produk berturut-turut tanpa qty → emit yg pertama dgn qty=1
                    items.add(new ParsedOrder.Item(pendingProduct, 1));
                    pendingProduct = t.productLabel;
                } else {
                    pendingProduct = t.productLabel;
                }
            }
        }
        // Sisa pending
        if (pendingProduct != null) {
            items.add(new ParsedOrder.Item(pendingProduct, 1));
        } else if (pendingQty != null) {
            // Qty tanpa produk: emit dengan label generik
            String label = ParsedOrder.TYPE_JUAL_BOTOL.equals(type)
                    ? "Botol Galon Kosong" : "Galon";
            items.add(new ParsedOrder.Item(label, pendingQty));
        }
        return items;
    }

    /** Cek apakah sudah ada token produk di rentang [pos..pos+len]. */
    private static boolean hasProductTokenAt(List<Tok> existing, int pos, int len) {
        for (Tok t : existing) {
            if (t.isQty) continue;
            if (Math.abs(t.pos - pos) < Math.max(len, 3)) return true;
        }
        return false;
    }

    /** Resolve alias generik ke nama produk DB (kalau ada), atau alias capitalized. */
    private static String aliasToProductLabel(String alias, List<Product> products) {
        if (products != null) {
            for (Product p : products) {
                if (p.getName() != null
                        && p.getName().toLowerCase(Locale.ROOT).contains(alias)) {
                    return p.getName();
                }
            }
        }
        return capitalize(alias);
    }

    private static boolean productExistsInDb(String label, List<Product> products) {
        if (label == null || products == null) return false;
        for (Product p : products) {
            if (p.getName() != null && p.getName().equalsIgnoreCase(label)) return true;
        }
        return false;
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        if (s.length() <= 3) return s.toUpperCase(Locale.ROOT); // mis. "RO"
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
