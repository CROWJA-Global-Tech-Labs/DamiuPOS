package com.crowja.damiupos.pengisian;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Menempelkan operasi yang MASIH di kotak keluar ({@link FillOutbox}) ke rencana terakhir dari
 * server, supaya layar langsung mencerminkan ketukan pekerja tanpa menunggu kirim+poll berikutnya
 * (dan tanpa perlu ketuk dua kali). Hanya menyesuaikan angka yang PASTI akibat ketukan itu
 * ({@code ready}, {@code to_fill}, {@code surplus}, {@code filled_today}); backlog/target/cadangan
 * tetap milik server. Begitu server menerima operasi dan rencana segar datang, operasi itu keluar
 * dari kotak dan overlay-nya hilang sendiri.
 *
 * <p>Aturan (urut sesuai ketukan):
 * <ul>
 *   <li>FILL: {@code ready += qty}, {@code filled_today += qty}. Dilewati bila uuid-nya sudah ada
 *       di {@code recent} server (balasan kirim hilang di jalan → server sudah punya, jangan
 *       hitung dua kali).</li>
 *   <li>COUNT: {@code ready = qty} (ABSOLUT — menimpa isi sebelumnya), {@code needs_count=false}.</li>
 *   <li>VOID baris FILL yang ada di {@code recent}: {@code ready -= qty} (dasar 0), {@code
 *       filled_today -= qty}, barisnya disembunyikan. VOID baris COUNT (atau batch_uuid sesinya:
 *       semua barisnya): hanya disembunyikan — angka ready sebelum hitung tak diketahui HP, server
 *       yang menghitung ulang.</li>
 * </ul>
 * Urutan produk TIDAK diubah: kartu tak boleh loncat di bawah jari pekerja; server mengurutkan
 * ulang pada rencana berikutnya.
 */
public final class FillOverlay {

    private FillOverlay() {}

    /** Kembalikan rencana yang sudah ditempeli {@code ops}; {@code base} tak diubah. */
    public static FillPlan apply(FillPlan base, List<FillOutbox.Op> ops) {
        if (base == null) return null;
        if (ops == null || ops.isEmpty()) return base;

        FillPlan plan = base.copy();
        Map<String, FillPlan.Product> byUuid = new HashMap<>();
        for (FillPlan.Product p : plan.products) byUuid.put(p.uuid, p);
        Set<String> serverRecentUuids = new HashSet<>();
        for (FillPlan.Recent r : base.recent) serverRecentUuids.add(r.uuid);

        Set<String> clearedCount = new HashSet<>();
        boolean touched = false;

        for (FillOutbox.Op op : ops) {
            if (FillOutbox.TYPE_FILL.equals(op.type)) {
                if (serverRecentUuids.contains(op.uuid)) continue;
                FillPlan.Product p = byUuid.get(op.productUuid);
                if (p == null || op.qty <= 0) continue;
                p.ready += op.qty;
                p.filledToday += op.qty;
                touched = true;
            } else if (FillOutbox.TYPE_COUNT.equals(op.type)) {
                for (FillOutbox.Count c : op.counts) {
                    FillPlan.Product p = byUuid.get(c.productUuid);
                    if (p == null) continue;
                    p.ready = Math.max(0, c.qty);
                    p.needsCount = false;
                    clearedCount.add(p.uuid);
                    touched = true;
                }
            } else if (FillOutbox.TYPE_VOID.equals(op.type)) {
                Iterator<FillPlan.Recent> it = plan.recent.iterator();
                while (it.hasNext()) {
                    FillPlan.Recent r = it.next();
                    // Target = uuid baris, atau batch_uuid sesi hitung (batal seluruh sesi).
                    boolean hit = r.uuid.equals(op.targetUuid)
                            || (!r.batchUuid.isEmpty() && r.batchUuid.equals(op.targetUuid));
                    if (!hit) continue;
                    if (FillPlan.KIND_FILL.equals(r.kind)) {
                        FillPlan.Product p = byUuid.get(r.productUuid);
                        if (p != null) {
                            p.ready = Math.max(0, p.ready - r.qty);
                            p.filledToday = Math.max(0, p.filledToday - r.qty);
                        }
                    }
                    it.remove();
                    touched = true;
                }
            }
        }
        if (!touched) return base;

        int ready = 0, toFill = 0, filled = 0;
        boolean anyCount = false;
        for (FillPlan.Product p : plan.products) {
            p.toFill = Math.max(0, p.target - p.ready);
            p.surplus = Math.max(0, p.ready - p.target);
            ready += p.ready;
            toFill += p.toFill;
            filled += p.filledToday;
            if ((p.backlog > 0 || p.ready > 0) && p.needsCount) anyCount = true;
        }
        plan.totals.ready = ready;
        plan.totals.toFill = toFill;
        plan.totals.filledToday = filled;
        plan.needsCount = anyCount;
        plan.status = toFill > 0 ? FillPlan.STATUS_NEED
                : plan.totals.backlog > 0 ? FillPlan.STATUS_MET : FillPlan.STATUS_IDLE;
        return plan;
    }

    /**
     * Bunyi/getar diberikan bila total "perlu diisi" NAIK antara dua render (pesanan baru masuk,
     * atau penjualan konter menghabiskan stok rak). Render pertama ({@code prev == null}) tak
     * membunyikan apa pun. Dibandingkan pada tampilan ber-overlay, jadi ketukan pekerja sendiri
     * (yang hanya menurunkan angka) atau batal miliknya sendiri tidak memicu bunyi.
     */
    public static boolean toFillIncreased(Integer prevToFill, int nowToFill) {
        return prevToFill != null && nowToFill > prevToFill;
    }
}
