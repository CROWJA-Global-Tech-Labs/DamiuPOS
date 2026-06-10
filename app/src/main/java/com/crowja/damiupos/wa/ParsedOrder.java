package com.crowja.damiupos.wa;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Hasil parser pesan WA. Dipakai sebagai DTO antara
 * {@link OrderParseService} → {@link com.crowja.damiupos.db.OrderInboxDao}.
 *
 * <p>Diserialisasi ke JSON saat disimpan di kolom
 * {@code order_inbox.parsed_json} supaya UI inbox bisa render
 * tanpa kehilangan struktur.
 */
public class ParsedOrder {

    public static final String TYPE_JUAL = "JUAL";
    public static final String TYPE_KEMBALI = "KEMBALI";
    public static final String TYPE_JUAL_BOTOL = "JUAL_BOTOL";

    public boolean isOrder;
    public String type;                   // JUAL / KEMBALI / JUAL_BOTOL
    public List<Item> items = new ArrayList<>();
    public boolean urgent;
    public String notes;
    public double confidence;             // 0..1

    public static class Item {
        public String product;            // best-guess nama produk (mungkin tidak persis match DB)
        public int qty;
        public Item() {}
        public Item(String p, int q) { this.product = p; this.qty = q; }
    }

    public String toJson() {
        try {
            JSONObject root = new JSONObject();
            root.put("is_order", isOrder);
            root.put("type", type != null ? type : "");
            root.put("urgent", urgent);
            root.put("notes", notes != null ? notes : "");
            root.put("confidence", confidence);
            JSONArray arr = new JSONArray();
            for (Item it : items) {
                JSONObject o = new JSONObject();
                o.put("product", it.product != null ? it.product : "");
                o.put("qty", it.qty);
                arr.put(o);
            }
            root.put("items", arr);
            return root.toString();
        } catch (JSONException e) {
            return "{}";
        }
    }

    public static ParsedOrder fromJson(String json) {
        ParsedOrder p = new ParsedOrder();
        if (json == null || json.isEmpty()) return p;
        try {
            JSONObject root = new JSONObject(json);
            p.isOrder = root.optBoolean("is_order", false);
            p.type = root.optString("type", "");
            p.urgent = root.optBoolean("urgent", false);
            p.notes = root.optString("notes", "");
            p.confidence = root.optDouble("confidence", 0.0);
            JSONArray arr = root.optJSONArray("items");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    p.items.add(new Item(
                            o.optString("product", ""),
                            o.optInt("qty", 0)));
                }
            }
        } catch (JSONException ignored) {}
        return p;
    }

    /** Ringkasan singkat untuk subtitle/notif. Mis: "3× Aqua + 2× Le Minerale". */
    public String shortSummary() {
        if (items.isEmpty()) return notes != null ? notes : "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(" + ");
            sb.append(items.get(i).qty).append("× ").append(items.get(i).product);
        }
        return sb.toString();
    }
}
