package com.crowja.damiupos.pengisian;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

/**
 * Rencana isi galon (Karyawan Pengisian Day-time) — salinan bertipe dari JSON server
 * ({@code GET /api/fill/plan}, dirakit App\Support\FillPlan). HP TIDAK menghitung angka dasar apa
 * pun; kelas ini hanya membaca JSON dengan toleran (field hilang/null/angka-berupa-string tidak
 * boleh membuat layar pekerja crash) dan jadi pegangan bagi {@link FillOverlay} & {@link FillText}.
 *
 * <p>Sengaja Java murni + Gson (bukan org.json) supaya bisa diuji di JVM tanpa emulator.
 * Field publik non-final hanya supaya {@link FillOverlay} bisa menyalin-lalu-menyesuaikan; anggap
 * objek hasil {@link #parse} hanya-baca.
 */
public final class FillPlan {

    public static final String STATUS_NEED = "need";
    public static final String STATUS_MET = "met";
    public static final String STATUS_IDLE = "idle";

    public static final String KIND_FILL = "FILL";
    public static final String KIND_COUNT = "COUNT";

    public static final class Product {
        public String uuid = "";
        public String name = "";
        public String slug = "";
        public String color = "";
        public int backlog, orders, spare, target, ready, toFill, surplus, filledToday;
        public boolean needsCount;
        /** Waktu hitung-stok terakhir (app tz) atau null. */
        public String countedAt;

        public Product copy() {
            Product p = new Product();
            p.uuid = uuid; p.name = name; p.slug = slug; p.color = color;
            p.backlog = backlog; p.orders = orders; p.spare = spare; p.target = target;
            p.ready = ready; p.toFill = toFill; p.surplus = surplus; p.filledToday = filledToday;
            p.needsCount = needsCount; p.countedAt = countedAt;
            return p;
        }
    }

    public static final class Recent {
        public String uuid = "";
        public String kind = "";
        public String productUuid = "";
        public String productName = "";
        public int qty;
        public String loggedAt = "";
        public String staffUuid = "";
        public String staffName = "";
        /** Sesi hitung stok (semua baris COUNT satu sesi berbagi ini); "" untuk FILL. Target batal sesi. */
        public String batchUuid = "";
        public boolean voidable;

        public Recent copy() {
            Recent r = new Recent();
            r.uuid = uuid; r.kind = kind; r.productUuid = productUuid; r.productName = productName;
            r.qty = qty; r.loggedAt = loggedAt; r.staffUuid = staffUuid; r.staffName = staffName;
            r.batchUuid = batchUuid;
            r.voidable = voidable;
            return r;
        }
    }

    public static final class Totals {
        public int backlog, spare, target, ready, toFill, filledToday, orders;
    }

    public String rev = "";
    public String serverTime = "";
    public int spare;
    public String status = STATUS_IDLE;
    public boolean needsCount;
    public List<Product> products = new ArrayList<>();
    public Totals totals = new Totals();
    public int inTransitPcs, inTransitOrders;
    public int unmappedPcs, unmappedOrders;
    public List<Recent> recent = new ArrayList<>();

    /** Salinan dalam (produk & recent disalin) — bahan {@link FillOverlay}. */
    public FillPlan copy() {
        FillPlan c = new FillPlan();
        c.rev = rev; c.serverTime = serverTime; c.spare = spare; c.status = status;
        c.needsCount = needsCount;
        for (Product p : products) c.products.add(p.copy());
        c.totals = new Totals();
        c.totals.backlog = totals.backlog; c.totals.spare = totals.spare;
        c.totals.target = totals.target; c.totals.ready = totals.ready;
        c.totals.toFill = totals.toFill; c.totals.filledToday = totals.filledToday;
        c.totals.orders = totals.orders;
        c.inTransitPcs = inTransitPcs; c.inTransitOrders = inTransitOrders;
        c.unmappedPcs = unmappedPcs; c.unmappedOrders = unmappedOrders;
        for (Recent r : recent) c.recent.add(r.copy());
        return c;
    }

    /** @throws IllegalArgumentException bila teks bukan objek JSON. */
    public static FillPlan parse(String json) {
        JsonElement root;
        try {
            root = JsonParser.parseString(json == null ? "" : json);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Rencana bukan JSON yang valid", e);
        }
        if (root == null || !root.isJsonObject()) {
            throw new IllegalArgumentException("Rencana bukan objek JSON");
        }
        return parse(root.getAsJsonObject());
    }

    public static FillPlan parse(JsonObject o) {
        FillPlan p = new FillPlan();
        p.rev = str(o, "rev", "");
        p.serverTime = str(o, "server_time", "");
        p.spare = num(o, "spare", 0);
        p.status = str(o, "status", STATUS_IDLE);
        p.needsCount = bool(o, "needs_count", false);

        JsonArray prods = arr(o, "products");
        if (prods != null) {
            for (JsonElement e : prods) {
                if (!e.isJsonObject()) continue;
                JsonObject po = e.getAsJsonObject();
                Product pr = new Product();
                pr.uuid = str(po, "product_uuid", "");
                pr.name = str(po, "name", "");
                pr.slug = str(po, "slug", "");
                pr.color = str(po, "color", "");
                pr.backlog = num(po, "backlog", 0);
                pr.orders = num(po, "orders", 0);
                pr.spare = num(po, "spare", 0);
                pr.target = num(po, "target", 0);
                pr.ready = num(po, "ready", 0);
                pr.toFill = num(po, "to_fill", 0);
                pr.surplus = num(po, "surplus", 0);
                pr.filledToday = num(po, "filled_today", 0);
                pr.needsCount = bool(po, "needs_count", false);
                pr.countedAt = strOrNull(po, "counted_at");
                if (pr.uuid.isEmpty()) continue;   // tanpa uuid tak bisa dicatat — lewati
                p.products.add(pr);
            }
        }

        JsonObject t = obj(o, "totals");
        if (t != null) {
            p.totals.backlog = num(t, "backlog", 0);
            p.totals.spare = num(t, "spare", 0);
            p.totals.target = num(t, "target", 0);
            p.totals.ready = num(t, "ready", 0);
            p.totals.toFill = num(t, "to_fill", 0);
            p.totals.filledToday = num(t, "filled_today", 0);
            p.totals.orders = num(t, "orders", 0);
        }
        JsonObject it = obj(o, "in_transit");
        if (it != null) {
            p.inTransitPcs = num(it, "pcs", 0);
            p.inTransitOrders = num(it, "orders", 0);
        }
        JsonObject um = obj(o, "unmapped");
        if (um != null) {
            p.unmappedPcs = num(um, "pcs", 0);
            p.unmappedOrders = num(um, "orders", 0);
        }

        JsonArray rec = arr(o, "recent");
        if (rec != null) {
            for (JsonElement e : rec) {
                if (!e.isJsonObject()) continue;
                JsonObject ro = e.getAsJsonObject();
                Recent r = new Recent();
                r.uuid = str(ro, "uuid", "");
                r.kind = str(ro, "kind", "");
                r.productUuid = str(ro, "product_uuid", "");
                r.productName = str(ro, "product_name", "");
                r.qty = num(ro, "qty", 0);
                r.loggedAt = str(ro, "logged_at", "");
                r.staffUuid = str(ro, "staff_uuid", "");
                r.staffName = str(ro, "staff_name", "");
                r.batchUuid = str(ro, "batch_uuid", "");
                r.voidable = bool(ro, "voidable", false);
                if (r.uuid.isEmpty()) continue;
                p.recent.add(r);
            }
        }
        return p;
    }

    /** Produk menurut uuid, atau null. */
    public Product product(String uuid) {
        if (uuid == null) return null;
        for (Product p : products) if (uuid.equals(p.uuid)) return p;
        return null;
    }

    // ------------------------------------------------------------ JSON helpers (toleran)

    private static JsonElement get(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return (e == null || e.isJsonNull()) ? null : e;
    }

    static String str(JsonObject o, String k, String def) {
        JsonElement e = get(o, k);
        if (e == null || !e.isJsonPrimitive()) return def;
        return e.getAsString();
    }

    static String strOrNull(JsonObject o, String k) {
        JsonElement e = get(o, k);
        if (e == null || !e.isJsonPrimitive()) return null;
        String s = e.getAsString();
        return s.isEmpty() ? null : s;
    }

    /** Angka bulat; menerima 12, 12.0, "12" (server sudah membulatkan pcs dengan ceil sekali). */
    static int num(JsonObject o, String k, int def) {
        JsonElement e = get(o, k);
        if (e == null || !e.isJsonPrimitive()) return def;
        try {
            return (int) Math.round(e.getAsDouble());
        } catch (RuntimeException ex) {
            return def;
        }
    }

    static long lng(JsonObject o, String k, long def) {
        JsonElement e = get(o, k);
        if (e == null || !e.isJsonPrimitive()) return def;
        try {
            return Math.round(e.getAsDouble());
        } catch (RuntimeException ex) {
            return def;
        }
    }

    static boolean bool(JsonObject o, String k, boolean def) {
        JsonElement e = get(o, k);
        if (e == null || !e.isJsonPrimitive()) return def;
        if (e.getAsJsonPrimitive().isBoolean()) return e.getAsBoolean();
        String s = e.getAsString();
        return "1".equals(s) || "true".equalsIgnoreCase(s);
    }

    private static JsonObject obj(JsonObject o, String k) {
        JsonElement e = get(o, k);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    private static JsonArray arr(JsonObject o, String k) {
        JsonElement e = get(o, k);
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : null;
    }
}
