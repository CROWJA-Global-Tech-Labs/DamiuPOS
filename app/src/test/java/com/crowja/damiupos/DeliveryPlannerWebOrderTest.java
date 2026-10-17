package com.crowja.damiupos;

import static org.junit.Assert.assertEquals;

import com.crowja.damiupos.model.Transaction;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Kelompok ⚡/⭐ di Strategi Pengiriman HP mengikuti urutan antrean WEB (⭐ › level ×N › FIFO),
 * bukan jarak atau urutan Refresh RIT — kasus nyata ADHI 2026-10-15: web ×7, ×3, ×3, ×1 tapi HP
 * menampilkan kebalikannya karena "Mami" (×1) paling dekat & delivery_seq-nya 1.
 */
public class DeliveryPlannerWebOrderTest {

    private static final double DEPOT_LAT = -7.5600, DEPOT_LNG = 110.8200;

    private static Transaction order(String name, int level, int seq, String queuedAt, double km, int galon) {
        Transaction t = new Transaction();
        t.setType(Transaction.TYPE_JUAL);
        t.setCustomerName(name);
        t.setJumlahGalon(galon);
        t.setDeliveryQueuedAt(queuedAt);
        t.setDeliverySeq(seq);
        t.setPriorityLevel(level);
        if (level > 0) t.setOrderPriorityAt("2026-10-15T01:00:00Z");
        t.setCustomerLat(DEPOT_LAT + km / 111.0);   // ±1 km per 1/111 derajat lintang
        t.setCustomerLng(DEPOT_LNG);
        return t;
    }

    private static List<String> names(List<Transaction> stops) {
        List<String> out = new ArrayList<>();
        for (Transaction t : stops) out.add(t.getCustomerName());
        return out;
    }

    @Test public void levelsFollowWebAndSameLevelFollowsRoute() {
        List<Transaction> q = Arrays.asList(
                order("Mami", 1, 1, "2026-10-15 09:01:29", 0.8, 1),
                order("Ibu Darti", 3, 2, "2026-10-15 08:00:06", 1.5, 1),
                order("Es teh puri", 3, 3, "2026-10-15 08:00:05", 3.0, 1),
                order("Es teh Nusantara", 7, 4, "2026-10-15 08:00:05", 4.0, 2),
                order("Mas Mansyur", 0, 0, "2026-10-15 08:59:12", 0.5, 4));

        List<DeliveryPlanner.Trip> trips = DeliveryPlanner.plan(q, 5, DEPOT_LAT, DEPOT_LNG, DeliveryPlanner.MODE_NEAREST);

        // ×7 › ×3 › ×1; dua order ×3 mengikuti urutan rute (seq 2 lalu 3), bukan jam pesan.
        assertEquals(Arrays.asList("Es teh Nusantara", "Ibu Darti", "Es teh puri", "Mami"), names(trips.get(0).stops));
        assertEquals(Arrays.asList("Mas Mansyur"), names(trips.get(1).stops));
    }

    @Test public void starCustomerStaysAboveLevel() {
        Transaction star = order("Bintang", 0, 0, "2026-10-15 10:00:00", 2.0, 1);
        star.setCustomerPriority(true);
        List<Transaction> q = Arrays.asList(order("Level 9", 9, 0, "2026-10-15 07:00:00", 1.0, 1), star);

        List<DeliveryPlanner.Trip> trips = DeliveryPlanner.plan(q, 0, DEPOT_LAT, DEPOT_LNG, DeliveryPlanner.MODE_NEAREST);

        assertEquals(Arrays.asList("Bintang", "Level 9"), names(trips.get(0).stops));
    }
}
