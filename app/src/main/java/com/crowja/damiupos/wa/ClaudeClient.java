package com.crowja.damiupos.wa;

import com.crowja.damiupos.model.Product;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * HTTP client untuk DAMIU POS Claude proxy.
 *
 * <p>Bukan call langsung ke api.anthropic.com — request dirouting lewat
 * proxy server di {@link #PROXY_URL} yang authenticate ke Claude pakai
 * OAuth Max plan (lihat {@code claude-proxy/server.js}). Keuntungan:
 * <ul>
 *   <li>Tidak butuh API key Anthropic (Max plan langganan reuse)</li>
 *   <li>Prompt building dilakukan server-side (centralized, bisa di-update tanpa
 *       harus push update APK)</li>
 *   <li>Bearer token shared secret saja, bukan API key Anthropic — limit
 *       blast radius kalau token bocor</li>
 * </ul>
 *
 * <p>Klien hanya kirim inputs mentah, proxy yang build prompt &amp; call Claude.
 */
public class ClaudeClient {

    /** Proxy endpoint. Hardcoded — kalau ganti VPS, edit + rebuild APK. */
    private static final String PROXY_URL =
            "https://202-155-157-70.domainesia.io/parse-order";

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 30_000;

    /**
     * Sinkronus — caller WAJIB panggil dari background thread.
     *
     * @param bearerToken token dari Settings (KEY_CLAUDE_API_KEY) — dipakai
     *                    untuk auth ke proxy, bukan ke Anthropic.
     * @return ParsedOrder dengan confidence 0 jika gagal/error.
     * @throws ClaudeException kalau token salah / proxy down / response invalid
     */
    public static ParsedOrder parse(String bearerToken, String senderName, String senderPhone,
                                    String message, List<Product> products,
                                    List<String> registeredCustomerSummaries)
            throws ClaudeException {
        if (bearerToken == null || bearerToken.trim().isEmpty()) {
            throw new ClaudeException("Bearer token belum di-set");
        }
        try {
            JSONObject body = buildRequestBody(senderName, senderPhone, message,
                    products, registeredCustomerSummaries);
            String json = post(bearerToken.trim(), body);
            return ParsedOrder.fromJson(json);
        } catch (IOException e) {
            throw new ClaudeException("Network error: " + e.getMessage());
        } catch (JSONException e) {
            throw new ClaudeException("Response proxy tidak valid: " + e.getMessage());
        }
    }

    /**
     * Build payload sesuai kontrak {@code POST /parse-order}:
     * <pre>
     * {
     *   "senderName": string,
     *   "senderPhone": string,
     *   "message": string,
     *   "products": [{"name": string, "hargaJual": number}, ...],
     *   "customers": ["Nama - 0812...", ...]
     * }
     * </pre>
     */
    private static JSONObject buildRequestBody(String senderName, String senderPhone,
                                               String message, List<Product> products,
                                               List<String> customers) throws JSONException {
        JSONArray productsArr = new JSONArray();
        if (products != null) {
            for (Product p : products) {
                productsArr.put(new JSONObject()
                        .put("name", p.getName() != null ? p.getName() : "")
                        .put("hargaJual", p.getHargaJual()));
            }
        }
        JSONArray customersArr = new JSONArray();
        if (customers != null) {
            for (String cs : customers) customersArr.put(cs != null ? cs : "");
        }
        return new JSONObject()
                .put("senderName", senderName != null ? senderName : "")
                .put("senderPhone", senderPhone != null ? senderPhone : "")
                .put("message", message != null ? message : "")
                .put("products", productsArr)
                .put("customers", customersArr);
    }

    /** POST ke proxy {@link #PROXY_URL}. Return body JSON apa adanya. */
    private static String post(String bearerToken, JSONObject body)
            throws IOException, JSONException {
        URL url = new URL(PROXY_URL);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        conn.setRequestProperty("Authorization", "Bearer " + bearerToken);
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setDoOutput(true);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }

        int code = conn.getResponseCode();
        InputStream stream = (code >= 200 && code < 300)
                ? conn.getInputStream() : conn.getErrorStream();
        StringBuilder sb = new StringBuilder();
        if (stream != null) {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append('\n');
            }
        }
        if (code < 200 || code >= 300) {
            // Map common proxy errors ke pesan yg lebih readable di OrderParseService
            String tag;
            if (code == 401) tag = "HTTP 401 unauthorized";
            else if (code == 400) tag = "HTTP 400 invalid_request";
            else if (code == 502) tag = "HTTP 502 bad_gateway";
            else tag = "HTTP " + code;
            throw new IOException(tag + ": " + sb.toString().trim());
        }
        return sb.toString();
    }

    public static class ClaudeException extends Exception {
        public ClaudeException(String msg) { super(msg); }
    }
}
