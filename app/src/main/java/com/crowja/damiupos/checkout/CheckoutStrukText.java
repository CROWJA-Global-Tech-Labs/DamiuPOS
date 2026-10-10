package com.crowja.damiupos.checkout;

import com.crowja.damiupos.model.Transaction;
import com.crowja.damiupos.model.TransactionItem;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 🧺 Teks CHECKOUT MULTI-LOKASI untuk struk (WA) & antrean — murni (tanpa Android), diuji di JVM.
 *
 * <p>Satu checkout = N leg JUAL, satu per lokasi tujuan. Struk gabungan menampilkan tiap leg sebagai
 * bagian sendiri ("📍 *Rumah* — #RCPT-A" + baris produk/ongkir PERSIS seperti struk order tunggal +
 * Subtotal), lalu SATU *Total* gabungan dan baris "Bayar per lokasi: Rumah Rp8.000 · Kedai Rp15.000".
 * Keputusan owner: satu metode bayar untuk semua leg, tapi tiap leg DITAGIH DI PINTUNYA SENDIRI untuk
 * subtotalnya sendiri — tak ada stempel "dibayar untuk semua lokasi". Leg HUTANG / sudah LUNAS tak
 * ditagih di pintunya, jadi tak ikut baris "Bayar per lokasi" ({@link Leg#collectsAtDoor}). Cermin
 * {@code App\Support\StrukWa::composeCheckout} di web; ubah keduanya bersamaan.
 *
 * <p>{@link #rincianItems} juga dipakai struk order TUNGGAL (ReceiptActivity.composeTextStruk), jadi
 * baris produk/ongkir satu leg tak mungkin menyimpang dari struk order biasa ke lokasi yang sama.
 */
public final class CheckoutStrukText {

    private CheckoutStrukText() {}

    /** Pemisah segmen catatan ("catatan checkout · catatan leg") — sama dengan web. */
    private static final String NOTE_SEP = " · ";

    /** Satu leg (= satu lokasi) siap cetak. Diisi ReceiptActivity dari baris transaksi lokal. */
    public static final class Leg {
        public long id;
        public String uuid;
        public int seq;
        public String destName;
        public String receiptNo;
        /** Baris produk (items_json); kosong → jatuh ke produk tunggal lama di bawah. */
        public List<TransactionItem> items = new ArrayList<>();
        public String productName;
        public int jumlah;
        public double hargaPerGalon;
        public double ongkir;
        public String ongkirType;
        public double total;
        /** Kepemilikan BELI → harga botol ikut total (bukan ongkir). */
        public boolean beliBotol;
        public double hargaBotol;
        /** Token lacak terisi = order ini DIANTAR (dasar "Gratis Ongkir"). */
        public String token;
        public int kembali;
        public String paymentMethod;
        public boolean paymentConfirmed;
        /** Bagian leg ini yang dibayar dari saldo refund / saldo komisi (buku besar / penanda leg). */
        public double refundUsed;
        public double saldoUsed;
        /** Pelunasan hutang LAMA yang bertaut leg ini (dibayar di pintu leg ini). */
        public double debtPaid;
        /** Catatan layak-pelanggan (ReceiptActivity.customerNote) & catatan mentah (deteksi penanda). */
        public String note;
        public String rawCatatan;
        public String deliveryStatus;
        public String tertundaResumeAt;

        public boolean isDelivery() {
            return token != null && !token.trim().isEmpty();
        }

        /** Nama lokasi untuk ditampilkan: "Kirim ke" leg ini, atau "Lokasi k" bila kosong. */
        public String label() {
            String d = destName != null ? destName.trim() : "";
            if (d.isEmpty() || "null".equalsIgnoreCase(d)) return "Lokasi " + seq;
            return d;
        }

        /** Uang yang ditagih di pintu leg ini: subtotal − potongan saldo, + pelunasan hutang lama. */
        public double doorAmount() {
            return Math.max(0, total - refundUsed - saldoUsed) + debtPaid;
        }

        /**
         * Kurir menagih uang di pintu leg ini? TIDAK untuk leg HUTANG (cash bon — tak ada uang
         * diserahkan) dan leg yang sudah LUNAS (bukti bayar QRIS/transfer dikonfirmasi). Cermin filter
         * "Bayar per lokasi" di StrukWa::rincianCheckout — struk dari HP & dari server harus sama.
         */
        public boolean collectsAtDoor() {
            return !Transaction.PAY_HUTANG.equalsIgnoreCase(paymentMethod) && !paymentConfirmed;
        }
    }

    /** Bagian sekali-per-checkout di bawah rincian — disusun ReceiptActivity dari buku besar & setelan. */
    public static final class Footer {
        /** "Pembelian Sebelumnya KODE-…" / "Hutang Sebelumnya" — label baris pelunasan hutang lama. */
        public String debtOriginLabel;
        /** Baris info bayar (link QRIS / rekening transfer) siap tempel, dipisah "\n"; null/"" = tak ada. */
        public String payInfo;
        /** Dibuat otomatis oleh "Pesanan Terjadwal" (web). */
        public boolean terjadwal;
        /** Sisa hutang pelanggan LIVE (sudah termasuk hutang segar leg checkout ini). */
        public double sisaHutang;
        /** ID transaksi asal hutang ("A, B"); null/"" = tak ditampilkan. */
        public String sisaHutangOrigins;
    }

    public static NumberFormat rupiah() {
        return NumberFormat.getInstance(new Locale("id", "ID"));
    }

    /** "Rp8.000" — format struk WA (tanpa spasi, dibulatkan). */
    static String rp(NumberFormat nf, double v) {
        return "Rp" + nf.format(Math.round(v));
    }

    /**
     * Badge leg di kartu antrean: "🧺 Lokasi 1/2". Cukup dari seq/size baris INI (HP kurir sering
     * hanya memegang satu leg); "" bila bukan leg sah. Nama lokasi sudah ada di baris 📍 kartu.
     */
    public static String legBadge(int seq, int size) {
        if (size < CheckoutConstants.MIN_LEGS || size > CheckoutConstants.MAX_LEGS || seq < 1 || seq > size) {
            return "";
        }
        return CheckoutConstants.BADGE_ICON + " Lokasi " + seq + "/" + size;
    }

    /**
     * Pengingat penagihan untuk popup Selesai/Detail kurir: "🧺 Lokasi 2/2 · Kedai — tagih HANYA
     * pesanan lokasi ini; lokasi lain dibayar di tempatnya sendiri." "" bila bukan leg sah.
     */
    public static String collectHint(int seq, int size, String destName) {
        return collectHint(seq, size, destName, null, false);
    }

    /**
     * {@link #collectHint(int, int, String)} yang tahu status bayar leg: leg HUTANG / sudah LUNAS tak
     * ditagih apa pun untuk pesanannya ("tagih HANYA pesanan lokasi ini" menyesatkan di sana) — tapi
     * pesanan lokasi lain tetap TIDAK ditagih di pintu ini.
     */
    public static String collectHint(int seq, int size, String destName, String paymentMethod,
                                     boolean paymentConfirmed) {
        String badge = legBadge(seq, size);
        if (badge.isEmpty()) return "";
        String d = destName != null ? destName.trim() : "";
        if ("null".equalsIgnoreCase(d)) d = "";
        String head = badge + (d.isEmpty() ? "" : " · " + d);
        if (paymentConfirmed) {
            return head + " — pesanan lokasi ini sudah LUNAS, jangan ditagih lagi; jangan tagih pesanan lokasi lain di sini.";
        }
        if (Transaction.PAY_HUTANG.equalsIgnoreCase(paymentMethod)) {
            return head + " — pesanan lokasi ini dicatat HUTANG (tak ditagih sekarang); jangan tagih pesanan lokasi lain di sini.";
        }
        return head + " — tagih HANYA pesanan lokasi ini; lokasi lain dibayar di tempatnya sendiri.";
    }

    /**
     * Label bayar yang dilebur ke baris *Total*: " (Bayar Tunai)" / " (LUNAS via Tunai)". null bila
     * metode kosong. Format PERSIS struk order tunggal (kapital huruf pertama kode metode).
     */
    public static String payLabel(String pay, boolean confirmed) {
        if (pay == null || pay.isEmpty()) return null;
        // Ke pelanggan kata "hutang" diganti "Sisa Pembayaran" (keputusan owner 2026-10-10) —
        // cermin StrukWa::payLabel di web.
        if ("HUTANG".equalsIgnoreCase(pay.trim())) return confirmed ? " (LUNAS)" : " (Sisa Pembayaran)";
        String payCap = pay.substring(0, 1).toUpperCase(Locale.ROOT)
                + pay.substring(1).toLowerCase(Locale.ROOT);
        return confirmed ? " (LUNAS via " + payCap + ")" : " (Bayar " + payCap + ")";
    }

    /** Semua leg sepakat soal metode bayar + status lunasnya? (Leg tunggal selalu sepakat.) */
    public static boolean samePayment(List<Leg> legs) {
        if (legs == null || legs.isEmpty()) return true;
        Leg first = legs.get(0);
        String p0 = first.paymentMethod != null ? first.paymentMethod : "";
        for (Leg l : legs) {
            String p = l.paymentMethod != null ? l.paymentMethod : "";
            if (!p.equalsIgnoreCase(p0) || l.paymentConfirmed != first.paymentConfirmed) return false;
        }
        return true;
    }

    /**
     * Baris produk + ongkir SATU order (diawali "\n" per baris) — dipakai struk order tunggal DAN tiap
     * bagian leg struk gabungan. Angka ongkir DITURUNKAN dari total yang benar-benar dibayar (cermin
     * PERSIS StrukWa::rincian + Transaction::strukExtras di web): sisa total yang tak dijelaskan baris
     * produk & botol dikembalikan ke ongkir, dan "Gratis Ongkir" hanya diklaim untuk order DIANTAR yang
     * seluruh rupiahnya sudah dijelaskan baris cetak.
     */
    public static String rincianItems(List<TransactionItem> items, String fallbackName, int fallbackJumlah,
                                      double fallbackHarga, double ongkir, String ongkirType, double total,
                                      boolean beliBotol, double hargaBotol, boolean delivery, NumberFormat nf) {
        StringBuilder sb = new StringBuilder();
        int totalGalon = 0;
        double itemsSum = 0;   // untuk rekonsiliasi ongkir dari total yang dibayar
        if (items != null && !items.isEmpty()) {
            for (TransactionItem it : items) {
                if (it.jumlah <= 0) continue;
                totalGalon += it.jumlah;
                itemsSum += it.jumlah * it.hargaPerGalon;
                sb.append("\n").append(it.productName != null ? it.productName : "Produk")
                        .append(" ").append(it.jumlah)
                        .append(" x Rp").append(nf.format(Math.round(it.hargaPerGalon)))
                        .append(" = Rp").append(nf.format(Math.round(it.jumlah * it.hargaPerGalon)));
            }
        } else {
            // Transaksi lama tanpa items_json → satu baris dari produk tunggal.
            if (fallbackJumlah > 0) {
                totalGalon = fallbackJumlah;
                itemsSum = fallbackJumlah * fallbackHarga;
                sb.append("\n").append(fallbackName != null ? fallbackName : "Air Minum")
                        .append(" ").append(fallbackJumlah)
                        .append(" x Rp").append(nf.format(Math.round(fallbackHarga)))
                        .append(" = Rp").append(nf.format(Math.round(fallbackJumlah * fallbackHarga)));
            }
        }
        double botol = beliBotol ? hargaBotol * totalGalon : 0;
        double colOngkir = ongkir <= 0 ? 0
                : ("per_galon".equals(ongkirType) ? ongkir * totalGalon : ongkir);
        double unexplained = total - itemsSum - botol - colOngkir;
        double paidOngkir = unexplained > 0.5 ? colOngkir + unexplained : colOngkir;
        if (paidOngkir > 0.5 && ongkir > 0 && totalGalon > 0
                && Math.abs(ongkir * totalGalon - paidOngkir) < 1) {
            sb.append("\nOngkir Per Galon Rp").append(nf.format(Math.round(ongkir)))
                    .append(" x ").append(totalGalon)
                    .append(" = Rp").append(nf.format(Math.round(paidOngkir)));
        } else if (paidOngkir > 0.5) {
            sb.append("\nOngkir = Rp").append(nf.format(Math.round(paidOngkir)));
        } else if (delivery && itemsSum > 0 && Math.abs(total - itemsSum - botol) <= 0.5) {
            // Diantar & seluruh rupiah sudah dijelaskan baris cetak → ongkirnya memang digratiskan.
            sb.append("\nGratis Ongkir");
        }
        return sb.toString();
    }

    /** Baris produk + ongkir satu leg — {@link #rincianItems} dengan data leg-nya sendiri. */
    public static String rincianItems(Leg l, NumberFormat nf) {
        return rincianItems(l.items, l.productName, l.jumlah, l.hargaPerGalon, l.ongkir, l.ongkirType,
                l.total, l.beliBotol, l.hargaBotol, l.isDelivery(), nf);
    }

    /**
     * Leg yang uangnya DITAGIH di pintunya ({@link Leg#collectsAtDoor}), urut seperti masukan — isi
     * "Bayar per lokasi" di teks WA, kotak kartu gambar & struk printer. Kosong bila semua leg HUTANG
     * / sudah LUNAS.
     */
    public static List<Leg> doorLegs(List<Leg> legs) {
        List<Leg> out = new ArrayList<>();
        if (legs == null) return out;
        for (Leg l : legs) {
            if (l != null && l.collectsAtDoor()) out.add(l);
        }
        return out;
    }

    /**
     * "Bayar per lokasi: Rumah Rp8.000 · Kedai Rp15.000" — "" untuk kurang dari 2 leg, atau bila tak
     * ada leg yang ditagih di pintunya. Leg HUTANG / sudah LUNAS tak ikut (TUNAI + HUTANG →
     * "Bayar per lokasi: Rumah Rp8.000" saja), persis StrukWa::rincianCheckout.
     */
    public static String perLocationLine(List<Leg> legs, NumberFormat nf) {
        if (legs == null || legs.size() < 2) return "";
        List<Leg> door = doorLegs(legs);
        if (door.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("Bayar per lokasi: ");
        for (int i = 0; i < door.size(); i++) {
            if (i > 0) sb.append(NOTE_SEP);
            Leg l = door.get(i);
            sb.append(l.label()).append(" ").append(rp(nf, l.doorAmount()));
        }
        return sb.toString();
    }

    /** Hasil {@link #splitNotes}: catatan bersama (sekali) + sisa catatan khusus tiap leg. */
    public static final class NoteSplit {
        public final String common;
        public final List<String> perLeg;

        NoteSplit(String common, List<String> perLeg) {
            this.common = common;
            this.perLeg = perLeg;
        }
    }

    /**
     * Pisahkan catatan leg menjadi bagian BERSAMA (dicetak sekali di bawah *Total*) dan bagian KHUSUS
     * tiap leg (dicetak di bagian 📍 leg itu). Catatan leg berbentuk "catatan checkout · catatan leg"
     * (web) atau sama persis di semua leg (HP). Segmen " · " terdepan yang sama di SEMUA leg = bersama;
     * sisanya milik leg masing-masing — instruksi "titip satpam Kedai" tak boleh hilang hanya karena
     * catatan dicetak dari leg utama saja (critique receipts #1).
     */
    public static NoteSplit splitNotes(List<String> notes) {
        List<String> perLeg = new ArrayList<>();
        if (notes == null || notes.isEmpty()) return new NoteSplit("", perLeg);
        List<List<String>> segs = new ArrayList<>();
        for (String n : notes) segs.add(segments(n));
        int common = Integer.MAX_VALUE;
        for (List<String> s : segs) common = Math.min(common, s.size());
        int k = 0;
        outer:
        for (; k < common; k++) {
            String ref = segs.get(0).get(k);
            for (List<String> s : segs) {
                if (!s.get(k).equalsIgnoreCase(ref)) break outer;
            }
        }
        String commonText = String.join(NOTE_SEP, segs.get(0).subList(0, k));
        for (List<String> s : segs) perLeg.add(String.join(NOTE_SEP, s.subList(k, s.size())));
        return new NoteSplit(commonText, perLeg);
    }

    private static List<String> segments(String note) {
        List<String> out = new ArrayList<>();
        if (note == null) return out;
        // Spasi di sekitar "·" OPSIONAL — sama dengan preg_split('/\s*·\s*/u') di StrukWa, supaya catatan
        // "antar pagi·titip satpam" dipecah identik di struk HP dan struk server.
        for (String p : note.split("\\s*·\\s*")) {
            String t = p.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    /**
     * Struk TEKS gabungan (pesan WA) — satu bagian 📍 per leg lalu ringkasan sekali-per-checkout:
     * <pre>
     * 🧾 *Rincian Pembelian* (2 lokasi)
     *
     * 📍 *Rumah* — #RCPT-A
     * Galon 1 x Rp6.000 = Rp6.000
     * Ongkir Per Galon Rp2.000 x 1 = Rp2.000
     * Subtotal: Rp8.000
     *
     * 📍 *Kedai* — #RCPT-B
     * …
     * Subtotal: Rp15.000
     *
     * *Total: Rp23.000* (Bayar Tunai)
     * Bayar per lokasi: Rumah Rp8.000 · Kedai Rp15.000
     * </pre>
     * Urutan sesudah *Total* sama dengan struk order tunggal: info bayar (QRIS/transfer), penanda
     * terjadwal, catatan, lalu Sisa Hutang. Leg yang metode bayarnya BERBEDA (diubah per leg) diberi
     * label sendiri di baris Subtotal-nya dan *Total* tak dilabeli satu metode.
     *
     * @param legs         leg yang ADA di HP ini, urut seq (minimal 1)
     * @param checkoutSize jumlah leg checkout (checkout_size); lebih besar dari legs.size() = sebagian
     *                     leg tak tersimpan di HP ini → judul "(2 dari 3 lokasi)"
     */
    public static String compose(List<Leg> legs, int checkoutSize, Footer f) {
        if (legs == null || legs.isEmpty()) return "";
        if (f == null) f = new Footer();
        NumberFormat nf = rupiah();
        int n = legs.size();
        int size = Math.max(checkoutSize, n);
        StringBuilder sb = new StringBuilder("🧾 *Rincian Pembelian* (")
                .append(n < size ? n + " dari " + size : String.valueOf(n)).append(" lokasi)");

        boolean same = samePayment(legs);
        List<String> notes = new ArrayList<>();
        for (Leg l : legs) notes.add(l.note);
        NoteSplit split = splitNotes(notes);

        double sumTotal = 0, sumRefund = 0, sumSaldo = 0, sumDebt = 0;
        for (int i = 0; i < n; i++) {
            Leg l = legs.get(i);
            sumTotal += l.total;
            sumRefund += l.refundUsed;
            sumSaldo += l.saldoUsed;
            sumDebt += l.debtPaid;
            sb.append("\n\n📍 *").append(l.label()).append("*");
            if (l.receiptNo != null && !l.receiptNo.trim().isEmpty()) {
                sb.append(" — #").append(l.receiptNo.trim());
            }
            sb.append(rincianItems(l, nf));
            String legNote = split.perLeg.get(i);
            if (!legNote.isEmpty()) sb.append("\n📝 ").append(legNote);
            String legPay = same ? null : payLabel(l.paymentMethod, l.paymentConfirmed);
            sb.append("\nSubtotal: ").append(rp(nf, l.total)).append(legPay != null ? legPay : "");
        }

        String pay = same ? payLabel(legs.get(0).paymentMethod, legs.get(0).paymentConfirmed) : null;
        sb.append("\n");
        // Pelunasan hutang LAMA (sekali, dibayar di pintu leg utama) — baris tagihan tersendiri yang
        // dilebur ke *Total*, persis struk order tunggal.
        if (sumDebt > 0) {
            String ket = f.debtOriginLabel != null && !f.debtOriginLabel.isEmpty()
                    ? f.debtOriginLabel : "Sisa Pembayaran Sebelumnya";
            sb.append("\nSisa Pembayaran (").append(ket).append("): ").append(rp(nf, sumDebt));
        }
        double grand = sumTotal + sumDebt;
        boolean deducted = sumRefund > 0 || sumSaldo > 0;
        sb.append("\n*Total: ").append(rp(nf, grand)).append("*")
                .append(deducted || sumDebt > 0 ? "" : (pay != null ? pay : ""));
        if (deducted) {
            if (sumSaldo > 0) sb.append("\nDipotong saldo komisi: -").append(rp(nf, sumSaldo));
            if (sumRefund > 0) sb.append("\nDipotong saldo refund: -").append(rp(nf, sumRefund));
            sb.append("\n*Sisa dibayar: ").append(rp(nf, Math.max(0, grand - sumRefund - sumSaldo))).append("*")
                    .append(sumDebt > 0 ? "" : (pay != null ? pay : ""));
        }
        String perLoc = perLocationLine(legs, nf);
        if (!perLoc.isEmpty()) sb.append("\n").append(perLoc);
        if (f.payInfo != null && !f.payInfo.trim().isEmpty()) sb.append("\n").append(f.payInfo.trim());
        if (f.terjadwal) sb.append("\n📅 [TERJADWAL]");
        if (!split.common.isEmpty()) sb.append("\n📝 Catatan: ").append(split.common);
        if (f.sisaHutang > 0) {
            sb.append("\n\n🧾 *Sisa Pembayaran Anda saat ini: ").append(rp(nf, f.sisaHutang)).append("*");
            if (f.sisaHutangOrigins != null && !f.sisaHutangOrigins.isEmpty()) {
                sb.append("\n(dari transaksi ").append(f.sisaHutangOrigins).append(")");
            }
        }
        return sb.toString();
    }

    /**
     * Baris pengenal satu leg pada struk order TUNGGAL yang dibuka di HP yang hanya memegang leg ini
     * (HP kurir): "📍 *Kedai* · Lokasi 2/2 (lokasi lain dibayar terpisah)". "" bila bukan leg sah.
     */
    public static String loneLegLine(int seq, int size, String destName) {
        if (legBadge(seq, size).isEmpty()) return "";
        String d = destName != null ? destName.trim() : "";
        if ("null".equalsIgnoreCase(d)) d = "";
        return "📍 " + (d.isEmpty() ? "" : "*" + d + "* · ") + "Lokasi " + seq + "/" + size
                + " (lokasi lain dibayar terpisah)";
    }

    // ------------------------------------------------------------- pintu tagih hutang lama

    /**
     * PINTU TAGIH hutang LAMA sebuah checkout, dilihat dari satu leg ({@link #oldDebtDoor}). Hutang lama
     * pelanggan ditagih di SATU pintu saja; pintu lain menagih 0 (hanya subtotal lokasinya) tapi tetap
     * boleh MENERIMA pelunasan sukarela s/d hutang lama sebenarnya — persis DeliveryFinalize di web.
     */
    public static final class DebtDoor {
        /** checkout_seq pintu tagih. */
        public final int seq;
        public final int size;
        /** Nama lokasi pintu tagih; null bila leg itu tak ada di HP ini (dirutekan ke perangkat lain / di-void). */
        public final String dest;
        /** true = leg yang ditanyakan sendiri yang menagih hutang lama (juga untuk order tunggal). */
        public final boolean here;
        /**
         * true = keputusan pintu didukung data lokal (pintu ini sendiri, atau leg pintu tagih ADA di HP
         * ini dan masih PENDING/TERTUNDA). false = leg pintu tagih TAK ADA di HP ini dan hanya
         * DIANGGAP terbuka — bisa saja sudah di-void (baris lokal dihapus keras, tombstone tanpa
         * checkout_uuid/seq) atau sudah Selesai di perangkat lain, sedangkan server lalu memindahkan
         * pintu tagihnya ke leg berikutnya. Teks ke kurir wajib bersyarat ({@link #where}).
         */
        public final boolean known;

        DebtDoor(int seq, int size, String dest, boolean here, boolean known) {
            this.seq = seq;
            this.size = size;
            this.dest = dest;
            this.here = here;
            this.known = known;
        }

        /** "Rumah (lokasi 1/2)" — atau "lokasi 1/2" bila nama lokasinya tak diketahui di HP ini. */
        public String label() {
            String d = dest != null ? dest.trim() : "";
            if ("null".equalsIgnoreCase(d)) d = "";
            String pos = "lokasi " + seq + "/" + size;
            return d.isEmpty() ? pos : d + " (" + pos + ")";
        }

        /**
         * "ditagih di Rumah (lokasi 1/2)" — atau "mungkin ditagih di lokasi 1/2" bila leg pintu tagih
         * tak ada di HP ini ({@link #known} false): kurir tak boleh diberi tahu dengan pasti untuk
         * menunjuk lokasi yang mungkin sudah selesai/batal.
         */
        public String where() {
            return (known ? "ditagih di " : "mungkin ditagih di ") + label();
        }
    }

    /**
     * Baris ringkas popup Detail/Selesai kurir di pintu yang BUKAN pintu tagih hutang lama:
     * "Hutang lama Rp 5.000 ditagih di Rumah (lokasi 1/2)". Bila leg pintu tagih tak ada di HP ini,
     * kalimatnya bersyarat dan menyebut statusnya tak diketahui.
     *
     * @param oldDebtRp jumlah hutang lama yang sudah diformat pemanggil (format rupiah popupnya)
     */
    public static String oldDebtElsewhereLine(DebtDoor door, String oldDebtRp) {
        String s = "Hutang lama " + oldDebtRp + " " + door.where();
        return door.known ? s : s + " (pesanan lokasi itu tak ada di HP ini — statusnya tak diketahui)";
    }

    /**
     * Instruksi popup Selesai di pintu yang BUKAN pintu tagih hutang lama. Pintu tagih diketahui →
     * "jangan ditagih di sini". Tak diketahui (leg itu tak ada di HP ini) → tetap tak ditagih
     * otomatis (tak pernah dua pintu menagih), tapi kurir diberi tahu lokasi itu bisa sudah
     * selesai/batal, jadi pelunasan yang tetap dibayarkan pelanggan dicatat di sini.
     */
    public static String oldDebtElsewhereInstruction(DebtDoor door, String oldDebtRp) {
        String head = "Hutang lama " + oldDebtRp + " " + door.where();
        if (door.known) {
            return head + " — jangan ditagih di sini. Bila pelanggan tetap membayarnya, ketik total uang"
                    + " yang diterima; kelebihannya dicatat sebagai pelunasan hutang lama.";
        }
        return head + ", tapi pesanan lokasi itu tak ada di HP ini — bisa sudah selesai atau dibatalkan."
                + " Jangan ditagih di sini kecuali Anda yakin lokasi itu tidak menagihnya (tanya"
                + " pelanggan/admin). Bila pelanggan membayarnya di sini, ketik total uang yang diterima;"
                + " kelebihannya dicatat sebagai pelunasan hutang lama.";
    }

    /**
     * Cermin {@code DebtBalance::oldDebtDoor} (web): pintu tagih = leg HIDUP ber-seq terkecil yang
     * pengirimannya masih TERBUKA (PENDING/TERTUNDA) di bawah leg ini; bila tak ada → leg ini sendiri.
     * Order tunggal / trio tak sah → selalu di sini.
     *
     * <p><b>HP hanya memegang sebagian leg</b> (pull terisolasi per perangkat; HP kurir sering hanya leg
     * ini, dan tak ada kolom server "old_debt_door" yang tersinkron). Leg ber-seq lebih kecil yang TAK ADA
     * di {@code localLegs} dianggap MASIH TERBUKA — jadi tanpa leg saudara, pintu tagihnya leg seq 1.
     * Arah amannya: paling buruk hutang lama tak ditagih di pintu ini (pelunasan sukarela tetap diterima),
     * TIDAK pernah ditagih dua kali di dua pintu oleh dua kurir.</p>
     *
     * <p>Anggapan itu bisa SALAH: leg sebelumnya yang di-void (baris lokal dihapus keras, tombstone tanpa
     * trio checkout) atau sudah Selesai di perangkat lain tak terlihat di sini, padahal server lalu
     * menjadikan leg ini pintu tagihnya. Hasilnya {@link DebtDoor#known} = false, dan teks ke kurir
     * ({@link #oldDebtElsewhereLine} / {@link #oldDebtElsewhereInstruction}) bersyarat, bukan perintah
     * pasti menunjuk lokasi yang mungkin sudah tutup.</p>
     *
     * @param localLegs leg checkout yang ADA di HP ini (seq, destName, deliveryStatus terisi); boleh
     *                  memuat leg ini sendiri; null = tak ada
     */
    public static DebtDoor oldDebtDoor(int thisSeq, int size, String thisDest, List<Leg> localLegs) {
        if (legBadge(thisSeq, size).isEmpty()) return new DebtDoor(thisSeq, size, thisDest, true, true);
        for (int s = 1; s < thisSeq; s++) {
            Leg known = null;
            if (localLegs != null) {
                for (Leg l : localLegs) {
                    if (l != null && l.seq == s) { known = l; break; }
                }
            }
            // Tak dikenal → anggap terbuka (arah aman), tapi tandai sebagai anggapan.
            if (known == null) return new DebtDoor(s, size, null, false, false);
            if (Transaction.DELIVERY_PENDING.equals(known.deliveryStatus)
                    || Transaction.DELIVERY_TERTUNDA.equals(known.deliveryStatus)) {
                return new DebtDoor(s, size, known.destName, false, true);
            }
            // Selesai / tanpa antrean → bukan pintu tagih lagi; lanjut ke leg berikutnya.
        }
        return new DebtDoor(thisSeq, size, thisDest, true, true);
    }
}
