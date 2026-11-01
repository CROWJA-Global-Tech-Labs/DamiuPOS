package com.crowja.damiupos.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * "✓ Selesai" dari tab Perangkat Lain / Pesanan Terbuka (owner 2026-11-01: "Tambahkan action selesai
 * pada antrean perangkat lain dan terbuka"). Order-nya DIAMBIL ALIH dulu ke HP ini (endpoint klaim yang
 * sama), lalu diselesaikan lewat alur ✓ Selesai Antrian Saya — tak ada jalan pintas yang melewati
 * foto bukti / pembayaran / galon kembali.
 *
 * <p>Logika murni (tanpa Android / org.json) supaya bisa diuji JVM: kapan aksinya ditawarkan, dan
 * titipan "uuid@epochMs" untuk order perangkat lain yang baru ada di HP ini setelah sinkron.
 */
public final class RemoteCompleteLogic {

    private RemoteCompleteLogic() {}

    /** Kunci SettingsDao titipan "selesaikan begitu barisnya tersinkron". */
    public static final String SETTINGS_KEY = "dlv_complete_pending_uuids";

    /** Titipan kedaluwarsa setelah 10 menit — klaim sukses biasanya tersinkron dalam hitungan detik. */
    public static final long PENDING_TTL_MS = 10L * 60L * 1000L;

    public enum Offer {
        /** Tak ditawarkan sama sekali (belum terhubung / bukan perangkat delivery / Viewer / tanpa uuid). */
        HIDDEN,
        /** Tampil redup: kurir lain sedang mengantarnya. */
        DISABLED_IN_PROGRESS,
        /** Tampil redup: Ambil Alih sedang menunggu persetujuan pemilik antrean. */
        DISABLED_AWAITING_APPROVAL,
        ENABLED
    }

    /**
     * Gerbangnya sama dengan Ambil Alih (terhubung, perangkat delivery, ClaimRequests.blocks, sedang
     * diantar) ditambah peran: Viewer hanya melihat.
     */
    public static Offer offer(boolean enrolled, boolean deliveryDevice, boolean viewer, boolean hasUuid,
                              boolean inProgress, boolean claimBlocked) {
        if (!enrolled || !deliveryDevice || viewer || !hasUuid) return Offer.HIDDEN;
        if (inProgress) return Offer.DISABLED_IN_PROGRESS;
        if (claimBlocked) return Offer.DISABLED_AWAITING_APPROVAL;
        return Offer.ENABLED;
    }

    /** Label menu sesuai {@link #offer}; null = jangan tampilkan. */
    public static String menuLabel(Offer o) {
        switch (o) {
            case ENABLED: return "✓ Selesai";
            case DISABLED_IN_PROGRESS: return "✓ Selesai (sedang diantar kurir lain)";
            case DISABLED_AWAITING_APPROVAL: return "✓ Selesai (menunggu persetujuan)";
            default: return null;
        }
    }

    /** Pesan konfirmasi sebelum klaim + selesai. */
    public static String confirmMessage(String customer, String fromDevice) {
        String name = customer == null || customer.trim().isEmpty() ? "Pelanggan" : customer.trim();
        String dev = fromDevice == null || fromDevice.trim().isEmpty() || "null".equals(fromDevice.trim())
                ? "perangkat lain" : "\"" + fromDevice.trim() + "\"";
        return "Order \"" + name + "\" akan diambil alih ke HP ini dulu (dari " + dev
                + "), lalu dicatat selesai seperti biasa.";
    }

    /** Isi titipan yang sudah diurai. */
    public static final class Pending {
        /** uuid → epochMs saat dititipkan (masih berlaku). */
        public final Map<String, Long> alive = new LinkedHashMap<>();
        /** uuid yang kedaluwarsa tanpa pernah masuk antrean HP ini. */
        public final List<String> expired = new ArrayList<>();
    }

    public static Pending parse(String raw, long now) {
        Pending p = new Pending();
        if (raw == null || raw.isEmpty()) return p;
        for (String part : raw.split(",")) {
            int at = part.lastIndexOf('@');
            if (at <= 0) continue;
            String u = part.substring(0, at).trim();
            if (u.isEmpty()) continue;
            long ts;
            try {
                ts = Long.parseLong(part.substring(at + 1).trim());
            } catch (NumberFormatException e) {
                continue;
            }
            if (now - ts <= PENDING_TTL_MS && ts <= now + PENDING_TTL_MS) {
                p.alive.put(u, ts);
            } else if (!p.expired.contains(u)) {
                p.expired.add(u);
            }
        }
        for (String u : p.alive.keySet()) p.expired.remove(u);
        return p;
    }

    /** Titipan baru (menimpa stempel uuid yang sama); entri kedaluwarsa ikut dibuang. */
    public static String add(String raw, String uuid, long now) {
        Pending p = parse(raw, now);
        if (uuid != null && !uuid.trim().isEmpty()) {
            p.alive.remove(uuid.trim());
            p.alive.put(uuid.trim(), now);
        }
        return encode(p.alive);
    }

    /** Buang uuid tertentu + semua yang kedaluwarsa. */
    public static String remove(String raw, Set<String> uuids, long now) {
        Pending p = parse(raw, now);
        if (uuids != null) {
            for (String u : uuids) p.alive.remove(u);
        }
        return encode(p.alive);
    }

    /**
     * Perbarui stempel uuid yang SUDAH tiba di Antrian Saya tapi masih menunggu giliran dialog Selesai
     * (diproses satu per satu) supaya tak kedaluwarsa selama antre. Hanya stempel yang sudah lewat
     * separuh TTL yang ditulis ulang — teksnya tak berubah tiap refresh.
     */
    public static String touch(String raw, Set<String> uuids, long now) {
        Pending p = parse(raw, now);
        if (uuids != null) {
            for (String u : uuids) {
                Long ts = p.alive.get(u);
                if (ts != null && now - ts > PENDING_TTL_MS / 2) p.alive.put(u, now);
            }
        }
        return encode(p.alive);
    }

    /**
     * Boleh membuka dialog Selesai titipan berikutnya? Hanya saat layar benar-benar bebas: tampil &amp;
     * fokus (tak ada dialog lain di atasnya), tak ada penyelesaian / klaim / langkah wizard / gerbang
     * data / pickup langsung yang sedang berjalan.
     */
    public static boolean canOpenNext(boolean resumed, boolean hasFocus, boolean completePending, boolean claimBusy,
                                      boolean wizardAdvancePending, boolean otherFlowPending) {
        return resumed && hasFocus && !completePending && !claimBusy && !wizardAdvancePending && !otherFlowPending;
    }

    /** uuid titipan yang barisnya kini ada di Antrian Saya (urutan titipan). */
    public static List<String> arrived(Pending p, Set<String> myQueueUuids) {
        List<String> out = new ArrayList<>();
        if (p == null || myQueueUuids == null) return out;
        for (String u : p.alive.keySet()) {
            if (myQueueUuids.contains(u)) out.add(u);
        }
        return out;
    }

    public static Set<String> setOf(List<String> l) {
        return new LinkedHashSet<>(l);
    }

    private static String encode(Map<String, Long> alive) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Long> e : alive.entrySet()) {
            if (sb.length() > 0) sb.append(',');
            sb.append(e.getKey()).append('@').append(e.getValue());
        }
        return sb.toString();
    }
}
