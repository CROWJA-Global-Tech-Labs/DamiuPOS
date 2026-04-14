package com.damiu.pos.model;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** One line item within a transaction (product + qty + price). */
public class TransactionItem {
    public long productId;
    public String productName;
    public int jumlah;
    public double hargaPerGalon;

    public TransactionItem() {}

    public TransactionItem(long productId, String productName, int jumlah, double hargaPerGalon) {
        this.productId = productId;
        this.productName = productName;
        this.jumlah = jumlah;
        this.hargaPerGalon = hargaPerGalon;
    }

    public double getSubtotal() {
        return jumlah * hargaPerGalon;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("pid", productId);
        o.put("name", productName != null ? productName : "");
        o.put("qty", jumlah);
        o.put("price", hargaPerGalon);
        return o;
    }

    public static TransactionItem fromJson(JSONObject o) {
        TransactionItem it = new TransactionItem();
        it.productId = o.optLong("pid", 0);
        it.productName = o.optString("name", "");
        it.jumlah = o.optInt("qty", 0);
        it.hargaPerGalon = o.optDouble("price", 0);
        return it;
    }

    public static String listToJson(List<TransactionItem> items) {
        if (items == null || items.isEmpty()) return null;
        try {
            JSONArray arr = new JSONArray();
            for (TransactionItem it : items) arr.put(it.toJson());
            return arr.toString();
        } catch (JSONException e) {
            return null;
        }
    }

    public static List<TransactionItem> listFromJson(String json) {
        List<TransactionItem> list = new ArrayList<>();
        if (json == null || json.isEmpty()) return list;
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                list.add(fromJson(arr.getJSONObject(i)));
            }
        } catch (JSONException ignored) {}
        return list;
    }
}
