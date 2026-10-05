package com.crowja.damiupos.util;

import java.io.UnsupportedEncodingException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Keputusan murni (tanpa Android) fitur NAVIGASI PENGIRIMAN + jendela melayang (Picture-in-Picture)
 * — dipakai {@code DeliveryQueueActivity} (tombol 🧭 Navigasi rit) dan {@code NavigationPipActivity}
 * (ringkasan perhentian aktif di atas Google Maps), dipisah supaya bisa diuji di JVM:
 * <ul>
 *   <li>URL rute Google Maps (dir_action=navigate, batas 10 titik, koordinat desimal polos);</li>
 *   <li>perhentian AKTIF = perhentian rit pertama (urutan rit) yang masih menunggu diantar;</li>
 *   <li>teks uang "Rp 45.000" + label tagihan (Tagih / Lunas / Hutang tidak ditagih), dengan
 *       potongan saldo refund & hutang lama di pintu ({@link DoorMoney}).</li>
 * </ul>
 */
public final class DeliveryNavLogic {

    private DeliveryNavLogic() {
    }

    /** Batas titik rute Google Maps: 1 tujuan + 9 waypoint (sama dgn batas lama navigasi rit). */
    public static final int MAX_MAPS_STOPS = 10;

    public static final String MAPS_PACKAGE = "com.google.android.apps.maps";

    private static final String DIR_BASE = "https://www.google.com/maps/dir/?api=1&travelmode=driving";

    // ---------------------------------------------------------------- koordinat & URL rute

    /** Ada titik peta? (0,0 = belum ada koordinat — konvensi seluruh aplikasi.) */
    public static boolean hasGeo(double lat, double lng) {
        return lat != 0.0 || lng != 0.0;
    }

    /**
     * Satu angka koordinat dalam desimal POLOS, maks 7 digit (≈1 cm), tanpa nol ekor. Bukan
     * {@code Double.toString}: titik dekat khatulistiwa (Pontianak!) jadi "5.0E-4" — notasi ilmiah
     * yang tak dipahami Google Maps.
     */
    public static String coordNum(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return "0";
        BigDecimal d = new BigDecimal(v).setScale(7, RoundingMode.HALF_UP);
        // Nol ditangani sendiri: BigDecimal Android (Harmony) membiarkan skala nol apa adanya pada
        // stripTrailingZeros → "0.0000000".
        if (d.signum() == 0) return "0";
        return d.stripTrailingZeros().toPlainString();
    }

    /** "lat,lng" siap pakai di URL Maps. */
    public static String coord(double lat, double lng) {
        return coordNum(lat) + "," + coordNum(lng);
    }

    /** Maks {@link #MAX_MAPS_STOPS} titik pertama (urutan dipertahankan) — salinan baru. */
    public static <T> List<T> capRoute(List<T> stops) {
        List<T> out = new ArrayList<>();
        if (stops == null) return out;
        for (T s : stops) {
            if (out.size() >= MAX_MAPS_STOPS) break;
            out.add(s);
        }
        return out;
    }

    /**
     * URL rute Google Maps untuk {@code stops} ({lat, lng}) sesuai URUTAN rit: titik TERAKHIR =
     * destination, sisanya waypoints (dipisah "|", di-encode). Dipangkas ke {@link #MAX_MAPS_STOPS}.
     * {@code navigate} = tambah {@code dir_action=navigate} supaya aplikasi Maps langsung memulai
     * navigasi belokan-demi-belokan, bukan sekadar pratinjau rute. null bila tak ada titik.
     */
    public static String buildDirUrl(List<double[]> stops, boolean navigate) {
        List<double[]> route = capRoute(stops);
        if (route.isEmpty()) return null;
        double[] dest = route.get(route.size() - 1);
        StringBuilder url = new StringBuilder(DIR_BASE);
        if (navigate) url.append("&dir_action=navigate");
        url.append("&destination=").append(coord(dest[0], dest[1]));
        if (route.size() > 1) {
            StringBuilder wp = new StringBuilder();
            for (int i = 0; i < route.size() - 1; i++) {
                if (i > 0) wp.append('|');
                wp.append(coord(route.get(i)[0], route.get(i)[1]));
            }
            url.append("&waypoints=").append(encode(wp.toString()));
        }
        return url.toString();
    }

    /** Navigasi satu tujuan langsung di aplikasi Maps (skema google.navigation). */
    public static String navigationUri(double lat, double lng) {
        return "google.navigation:q=" + coord(lat, lng);
    }

    /** Cadangan tanpa aplikasi Maps: pin di peta web. */
    public static String webPinUrl(double lat, double lng) {
        return "https://www.google.com/maps?q=" + coord(lat, lng);
    }

    private static String encode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return s;   // UTF-8 selalu ada
        }
    }

    // ---------------------------------------------------------------- perhentian aktif

    /** Keadaan satu perhentian rit saat jendela melayang menyegarkan diri. */
    public enum StopState {
        /** Masih menunggu diantar (ada di Antrian Saya, masih anggota rit, punya titik peta). */
        PENDING,
        /** Sudah ditandai Selesai. */
        DONE,
        /** Keluar dari rit tanpa selesai (dihentikan, ditunda, dibatalkan, dipindah perangkat). */
        GONE
    }

    /** Indeks perhentian AKTIF = PENDING pertama menurut urutan rit; -1 bila tak ada lagi. */
    public static int activeIndex(List<StopState> states) {
        if (states == null) return -1;
        for (int i = 0; i < states.size(); i++) {
            if (states.get(i) == StopState.PENDING) return i;
        }
        return -1;
    }

    /** Indeks PENDING berikutnya SETELAH {@code from}; -1 bila tak ada. */
    public static int nextPendingIndex(List<StopState> states, int from) {
        if (states == null) return -1;
        for (int i = Math.max(0, from + 1); i < states.size(); i++) {
            if (states.get(i) == StopState.PENDING) return i;
        }
        return -1;
    }

    public static int count(List<StopState> states, StopState which) {
        int n = 0;
        if (states != null) {
            for (StopState s : states) {
                if (s == which) n++;
            }
        }
        return n;
    }

    /** N pada "Perhentian k/N": perhentian yang masih/pernah benar-benar diantar (GONE tak dihitung). */
    public static int trackedCount(List<StopState> states) {
        return count(states, StopState.PENDING) + count(states, StopState.DONE);
    }

    /**
     * k pada "Perhentian k/N" = jumlah yang sudah Selesai + 1 — kemajuan rit, bukan posisi di
     * daftar, jadi tetap benar walau kurir menyelesaikan perhentian tak berurutan. 0 bila tak ada
     * perhentian aktif.
     */
    public static int progressNumber(List<StopState> states) {
        if (activeIndex(states) < 0) return 0;
        return Math.min(count(states, StopState.DONE) + 1, trackedCount(states));
    }

    /** Semua perhentian habis DAN minimal satu benar-benar Selesai → "Rit selesai ✓". */
    public static boolean isRitFinished(List<StopState> states) {
        return activeIndex(states) < 0 && count(states, StopState.DONE) > 0;
    }

    // ---------------------------------------------------------------- uang & tagihan

    /** "Rp 45.000" — titik pemisah ribuan, dibulatkan ke rupiah terdekat. */
    public static String rupiah(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) v = 0;
        long n = Math.round(v);
        String digits = String.format(Locale.US, "%,d", Math.abs(n)).replace(',', '.');
        return (n < 0 ? "-Rp " : "Rp ") + digits;
    }

    /** Apa yang dilakukan kurir soal uang di pintu ini. */
    public enum PayKind {
        /** Tagih total pesanan (Tunai / QRIS / Transfer yang belum dikonfirmasi). */
        COLLECT,
        /** Sudah dibayar/dikonfirmasi — jangan ditagih lagi. */
        PAID,
        /** Dicatat HUTANG — tidak ditagih sekarang. */
        HUTANG,
        /** Ditandai CASH BON (uang tak diterima) — tidak ditagih. */
        CASH_BON,
        /** Total Rp 0 (gratis promo / ambil galon saja). */
        FREE
    }

    /**
     * @param paymentMethod     kode metode (TUNAI/QRIS/TRANSFER/HUTANG), boleh null
     * @param paymentConfirmed  payment_confirmed_at terisi (QRIS/Transfer dikonfirmasi, hutang dilunasi)
     * @param cashBon           catatan berpenanda [CASH BON]
     */
    public static PayKind payKind(double total, String paymentMethod, boolean paymentConfirmed, boolean cashBon) {
        if (paymentConfirmed) return PayKind.PAID;
        if (cashBon) return PayKind.CASH_BON;
        if ("HUTANG".equalsIgnoreCase(paymentMethod != null ? paymentMethod.trim() : "")) return PayKind.HUTANG;
        if (Math.round(total) <= 0) return PayKind.FREE;
        return PayKind.COLLECT;
    }

    /**
     * Uang di PINTU sebuah order — angka yang sama dengan popup Detail/Preview antrean
     * ("Dari saldo refund" / "Total setelah refund" / "Hutang sebelumnya"), bukan total_harga kotor
     * saja: saldo refund yang sudah memotong order ini TIDAK ditagih lagi, hutang LAMA yang ditagih di
     * pintu ini ikut ditagih.
     */
    public static final class DoorMoney {
        /** total_harga (kotor). */
        public final double total;
        /** Saldo refund yang memotong order ini (buku besar customer_refunds). */
        public final double refundUsed;
        /** Hutang LAMA pelanggan (di luar order ini & hutang segar leg saudara checkout). */
        public final double oldDebt;
        /** Hutang lama ditagih di pintu INI (order tunggal: selalu; leg checkout: hanya pintu tagihnya). */
        public final boolean debtHere;

        public DoorMoney(double total, double refundUsed, double oldDebt, boolean debtHere) {
            this.total = finite(total);
            this.refundUsed = Math.max(0, finite(refundUsed));
            this.oldDebt = Math.max(0, finite(oldDebt));
            this.debtHere = debtHere;
        }

        /** Tanpa potongan refund & tanpa hutang lama. */
        public static DoorMoney plain(double total) {
            return new DoorMoney(total, 0, 0, true);
        }

        /** Penjualan setelah dipotong saldo refund (≥ 0) — "Total setelah refund" antrean / "Sisa dibayar" struk. */
        public double saleAfterRefund() {
            return Math.max(0, total - refundUsed);
        }

        /** Hutang lama yang DITAGIH di pintu ini (pintu lain leg checkout → 0). */
        public double debtDue() {
            return debtHere ? oldDebt : 0;
        }

        private static double finite(double v) {
            return Double.isNaN(v) || Double.isInfinite(v) ? 0 : v;
        }
    }

    /**
     * Rupiah yang DITAGIH kurir di pintu ini: penjualan setelah refund (hanya bila memang ditagih
     * sekarang) + hutang lama pintu ini. HUTANG / CASH BON → 0: pelanggan tak membayar sekarang.
     */
    public static double dueAtDoor(PayKind kind, DoorMoney m) {
        switch (kind) {
            case COLLECT:
                return m.saleAfterRefund() + m.debtDue();
            case PAID:
            case FREE:
                return m.debtDue();
            case HUTANG:
            case CASH_BON:
            default:
                return 0;
        }
    }

    /**
     * Jenis untuk WARNA baris uang: tagihan yang habis tertutup saldo refund tampil sebagai lunas,
     * order lunas/gratis yang masih membawa hutang lama tampil sebagai tagihan.
     */
    public static PayKind displayKind(PayKind kind, DoorMoney m) {
        long due = Math.round(dueAtDoor(kind, m));
        if (kind == PayKind.COLLECT && due <= 0) return PayKind.PAID;
        if ((kind == PayKind.PAID || kind == PayKind.FREE) && due > 0) return PayKind.COLLECT;
        return kind;
    }

    /**
     * Baris uang ringkas (jendela melayang): "Rp 45.000 · Tunai", "Lunas · Rp 45.000",
     * "Hutang (tidak ditagih)", "Cash Bon (tidak ditagih)", "Tanpa tagihan".
     * @param methodLabel label ramah metode ("Tunai"/"QRIS"/…), boleh kosong
     */
    public static String payLine(PayKind kind, double total, String methodLabel) {
        return payLine(kind, DoorMoney.plain(total), methodLabel);
    }

    /**
     * Sama, dengan potongan saldo refund & hutang lama: angkanya = yang DITAGIH di pintu
     * ({@link #dueAtDoor}); rinciannya di {@link #payHint(PayKind, DoorMoney)}.
     */
    public static String payLine(PayKind kind, DoorMoney money, String methodLabel) {
        String m = methodLabel != null ? methodLabel.trim() : "";
        double due = dueAtDoor(kind, money);
        switch (kind) {
            case PAID:
                return Math.round(due) > 0 ? rupiah(due) + " · hutang lama" : "Lunas · " + rupiah(money.total);
            case HUTANG:
                return "Hutang (tidak ditagih)";
            case CASH_BON:
                return "Cash Bon (tidak ditagih)";
            case FREE:
                return Math.round(due) > 0 ? rupiah(due) + " · hutang lama" : "Tanpa tagihan";
            case COLLECT:
            default:
                if (Math.round(due) <= 0) return "Lunas (saldo refund)";
                return rupiah(due) + (m.isEmpty() ? "" : " · " + m);
        }
    }

    /** Kalimat penjelas di layar penuh ("" bila tak perlu). */
    public static String payHint(PayKind kind, double total) {
        return payHint(kind, DoorMoney.plain(total));
    }

    /**
     * Sama, plus rincian bila angkanya bukan total_harga polos: "Total pesanan Rp 45.000 − saldo
     * refund Rp 20.000" dan/atau "+ hutang lama Rp 15.000" — supaya cocok dengan popup Tandai Selesai.
     */
    public static String payHint(PayKind kind, DoorMoney money) {
        double due = dueAtDoor(kind, money);
        double debt = money.debtDue();
        boolean refunded = Math.round(money.refundUsed) > 0;
        switch (kind) {
            case COLLECT: {
                if (Math.round(due) <= 0) {
                    return "Sudah tertutup saldo refund " + rupiah(Math.min(money.refundUsed, money.total))
                            + " — jangan ditagih.";
                }
                StringBuilder sb = new StringBuilder("Tagih ").append(rupiah(due)).append(" dari pelanggan.");
                if (refunded) {
                    sb.append("\nTotal pesanan ").append(rupiah(money.total))
                            .append(" − saldo refund ").append(rupiah(Math.min(money.refundUsed, money.total)));
                }
                if (Math.round(debt) > 0) {
                    sb.append("\n+ hutang lama ").append(rupiah(debt));
                }
                return sb.toString();
            }
            case PAID:
                return Math.round(debt) > 0
                        ? "Pesanan sudah dibayar. Tagih hutang lama " + rupiah(debt) + "."
                        : "Sudah dibayar — jangan ditagih lagi.";
            case HUTANG:
            case CASH_BON:
                return rupiah(money.saleAfterRefund()) + " dicatat hutang pelanggan — jangan ditagih sekarang.";
            case FREE:
            default:
                return Math.round(debt) > 0 ? "Tagih hutang lama " + rupiah(debt) + "." : "";
        }
    }

    // ---------------------------------------------------------------- produk

    /**
     * Ringkasan produk "MIN ×2 · RO ×1": label yang sama dijumlahkan, urutan kemunculan pertama
     * dipertahankan, qty ≤ 0 / label kosong dilewati. "" bila tak ada.
     */
    public static String productSummary(List<String> labels, List<Integer> qtys) {
        if (labels == null || qtys == null) return "";
        Map<String, Integer> merged = new LinkedHashMap<>();
        for (int i = 0; i < labels.size() && i < qtys.size(); i++) {
            String l = labels.get(i) != null ? labels.get(i).trim() : "";
            Integer q = qtys.get(i);
            if (l.isEmpty() || q == null || q <= 0) continue;
            Integer prev = merged.get(l);
            merged.put(l, prev == null ? q : prev + q);
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : merged.entrySet()) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(e.getKey()).append(" ×").append(e.getValue());
        }
        return sb.toString();
    }
}
