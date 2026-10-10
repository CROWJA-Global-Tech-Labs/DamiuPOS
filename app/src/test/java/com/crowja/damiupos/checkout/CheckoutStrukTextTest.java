package com.crowja.damiupos.checkout;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.crowja.damiupos.model.Transaction;
import com.crowja.damiupos.model.TransactionItem;

import org.junit.Test;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Struk gabungan checkout multi-lokasi (teks WA) + badge/pengingat antrean. Keputusan owner: satu
 * metode bayar untuk semua lokasi, tapi tiap lokasi dibayar DI PINTUNYA SENDIRI → struk mencetak
 * "Bayar per lokasi: Rumah Rp8.000 · Kedai Rp15.000" plus total gabungan, tanpa stempel "dibayar
 * untuk semua lokasi". Baris produk/ongkir satu leg = struk order tunggal ke lokasi yang sama.
 */
public class CheckoutStrukTextTest {

    private static final NumberFormat NF = CheckoutStrukText.rupiah();

    private static CheckoutStrukText.Leg leg(int seq, String dest, String receipt, int qty, double price,
                                             double ongkirPerGalon, String pay) {
        CheckoutStrukText.Leg l = new CheckoutStrukText.Leg();
        l.seq = seq;
        l.destName = dest;
        l.receiptNo = receipt;
        l.items = new ArrayList<>(Collections.singletonList(new TransactionItem(1, "Galon", qty, price)));
        l.jumlah = qty;
        l.hargaPerGalon = price;
        l.ongkir = ongkirPerGalon;
        l.ongkirType = "per_galon";
        l.total = qty * price + qty * ongkirPerGalon;
        l.token = "tok" + seq;
        l.paymentMethod = pay;
        l.note = "";
        return l;
    }

    // ------------------------------------------------------------------ baris produk = order tunggal

    @Test public void rincianItemsMatchesSingleOrderFormat() {
        List<TransactionItem> items = Arrays.asList(new TransactionItem(1, "Galon 19L", 2, 6000));
        assertEquals("\nGalon 19L 2 x Rp6.000 = Rp12.000\nOngkir Per Galon Rp2.000 x 2 = Rp4.000",
                CheckoutStrukText.rincianItems(items, null, 0, 0, 2000, "per_galon", 16000,
                        false, 0, true, NF));
    }

    @Test public void rincianItemsWebFoldedOngkirAndFreeDelivery() {
        List<TransactionItem> items = Arrays.asList(new TransactionItem(1, "Galon", 1, 6000));
        // Order web: ongkir dilebur ke total tanpa kolom → sisa total jadi "Ongkir = …".
        assertEquals("\nGalon 1 x Rp6.000 = Rp6.000\nOngkir = Rp3.000",
                CheckoutStrukText.rincianItems(items, null, 0, 0, 0, "none", 9000, false, 0, true, NF));
        // Diantar & seluruh rupiah dijelaskan baris produk → "Gratis Ongkir".
        assertEquals("\nGalon 1 x Rp6.000 = Rp6.000\nGratis Ongkir",
                CheckoutStrukText.rincianItems(items, null, 0, 0, 0, "none", 6000, false, 0, true, NF));
        // Tak diantar (tanpa token) → tak ada klaim gratis ongkir.
        assertEquals("\nGalon 1 x Rp6.000 = Rp6.000",
                CheckoutStrukText.rincianItems(items, null, 0, 0, 0, "none", 6000, false, 0, false, NF));
        // Beli botol BUKAN ongkir.
        assertEquals("\nGalon 1 x Rp6.000 = Rp6.000\nGratis Ongkir",
                CheckoutStrukText.rincianItems(items, null, 0, 0, 0, "none", 46000, true, 40000, true, NF));
    }

    @Test public void rincianItemsLegacySingleProductFallback() {
        assertEquals("\nAir Minum 3 x Rp5.000 = Rp15.000",
                CheckoutStrukText.rincianItems(null, null, 3, 5000, 0, "none", 15000, false, 0, false, NF));
        assertEquals("", CheckoutStrukText.rincianItems(new ArrayList<>(), "X", 0, 5000, 0, "none", 0,
                false, 0, false, NF));
    }

    // ------------------------------------------------------------------ struk gabungan

    @Test public void composeTwoLegsPrintsSectionsGrandTotalAndPerLocationPayment() {
        List<CheckoutStrukText.Leg> legs = Arrays.asList(
                leg(1, "Rumah", "DMU-0510261200-AAAAA", 1, 6000, 2000, "TUNAI"),
                leg(2, "Kedai", "DMU-0510261200-BBBBB", 2, 6000, 1500, "TUNAI"));
        String text = CheckoutStrukText.compose(legs, 2, new CheckoutStrukText.Footer());
        assertEquals("🧾 *Rincian Pembelian* (2 lokasi)"
                + "\n\n📍 *Rumah* — #DMU-0510261200-AAAAA"
                + "\nGalon 1 x Rp6.000 = Rp6.000"
                + "\nOngkir Per Galon Rp2.000 x 1 = Rp2.000"
                + "\nSubtotal: Rp8.000"
                + "\n\n📍 *Kedai* — #DMU-0510261200-BBBBB"
                + "\nGalon 2 x Rp6.000 = Rp12.000"
                + "\nOngkir Per Galon Rp1.500 x 2 = Rp3.000"
                + "\nSubtotal: Rp15.000"
                + "\n"
                + "\n*Total: Rp23.000* (Bayar Tunai)"
                + "\nBayar per lokasi: Rumah Rp8.000 · Kedai Rp15.000", text);
        assertFalse("tak ada stempel dibayar untuk semua lokasi", text.toLowerCase().contains("semua lokasi"));
    }

    @Test public void perLocationAmountsAreNetOfDeductionsPlusOldDebtAtThatDoor() {
        CheckoutStrukText.Leg a = leg(1, "Rumah", "A", 1, 6000, 2000, "TUNAI");
        CheckoutStrukText.Leg b = leg(2, "Kedai", "B", 2, 6000, 1500, "TUNAI");
        a.debtPaid = 10000;      // "Sekalian Lunasi Hutang" — ditagih di pintu leg utama
        b.refundUsed = 5000;     // saldo refund dialokasikan ke leg 2
        CheckoutStrukText.Footer f = new CheckoutStrukText.Footer();
        f.debtOriginLabel = "Pembelian Sebelumnya DMU-OLD";
        String text = CheckoutStrukText.compose(Arrays.asList(a, b), 2, f);
        assertTrue(text, text.contains("\nSisa Pembayaran (Pembelian Sebelumnya DMU-OLD): Rp10.000"));
        // Ada potongan / pelunasan hutang → label bayar tak dilebur ke *Total* (sama dengan order tunggal).
        assertTrue(text, text.contains("\n*Total: Rp33.000*\n"));
        assertTrue(text, text.contains("\nDipotong saldo refund: -Rp5.000"));
        assertTrue(text, text.contains("\n*Sisa dibayar: Rp28.000*\n"));
        assertTrue(text, text.contains("\nBayar per lokasi: Rumah Rp18.000 · Kedai Rp10.000"));
        assertEquals(18000, a.doorAmount(), 0.001);
        assertEquals(10000, b.doorAmount(), 0.001);
    }

    @Test public void mixedPaymentLabelsEachLegAndNotTheTotal() {
        CheckoutStrukText.Leg a = leg(1, "Rumah", "A", 1, 6000, 0, "TUNAI");
        CheckoutStrukText.Leg b = leg(2, "Kedai", "B", 1, 6000, 0, "QRIS");
        assertFalse(CheckoutStrukText.samePayment(Arrays.asList(a, b)));
        String text = CheckoutStrukText.compose(Arrays.asList(a, b), 2, null);
        assertTrue(text, text.contains("\nSubtotal: Rp6.000 (Bayar Tunai)"));
        assertTrue(text, text.contains("\nSubtotal: Rp6.000 (Bayar Qris)"));
        assertTrue(text, text.contains("\n*Total: Rp12.000*\n"));
    }

    @Test public void confirmedPaymentReadsLunas() {
        CheckoutStrukText.Leg a = leg(1, "Rumah", "A", 1, 6000, 0, "HUTANG");
        CheckoutStrukText.Leg b = leg(2, "Kedai", "B", 1, 6000, 0, "HUTANG");
        a.paymentConfirmed = true;
        b.paymentConfirmed = true;
        String text = CheckoutStrukText.compose(Arrays.asList(a, b), 2, null);
        assertTrue(text, text.contains("*Total: Rp12.000* (LUNAS)"));
        // Sudah lunas → tak ada yang ditagih di pintu mana pun.
        assertFalse(text, text.contains("Bayar per lokasi"));
    }

    // ------------------------------------------------------------------ "Bayar per lokasi" = StrukWa

    @Test public void allHutangCheckoutHasNoPerLocationLine() {
        CheckoutStrukText.Leg a = leg(1, "Rumah", "A", 1, 6000, 2000, "HUTANG");
        CheckoutStrukText.Leg b = leg(2, "Kedai", "B", 2, 8000, 2000, "HUTANG");
        String text = CheckoutStrukText.compose(Arrays.asList(a, b), 2, null);
        assertTrue(text, text.contains("\n*Total: Rp28.000* (Sisa Pembayaran)"));
        assertFalse(text, text.contains("Bayar per lokasi"));
        assertEquals("", CheckoutStrukText.perLocationLine(Arrays.asList(a, b), NF));
        assertTrue(CheckoutStrukText.doorLegs(Arrays.asList(a, b)).isEmpty());
    }

    @Test public void mixedCashAndHutangListsOnlyTheCashLeg() {
        // Cermin CheckoutStrukTest::test_disagreeing_legs_get_per_leg_payment_labels di web.
        CheckoutStrukText.Leg a = leg(1, "Rumah", "A", 1, 6000, 2000, "TUNAI");
        CheckoutStrukText.Leg b = leg(2, "Kedai", "B", 2, 8000, 2000, "HUTANG");
        String text = CheckoutStrukText.compose(Arrays.asList(a, b), 2, null);
        assertTrue(text, text.contains("\nBayar per lokasi: Rumah Rp8.000"));
        assertFalse(text, text.contains("Kedai Rp20.000"));
        assertEquals("Bayar per lokasi: Rumah Rp8.000", CheckoutStrukText.perLocationLine(Arrays.asList(a, b), NF));
        assertEquals(Collections.singletonList(a), CheckoutStrukText.doorLegs(Arrays.asList(a, b)));
    }

    @Test public void confirmedQrisCheckoutHasNoPerLocationLine() {
        // Bukti bayar QRIS dikonfirmasi menstempel SEMUA leg saudara (critique #2b) → LUNAS.
        CheckoutStrukText.Leg a = leg(1, "Rumah", "A", 1, 6000, 2000, "QRIS");
        CheckoutStrukText.Leg b = leg(2, "Kedai", "B", 2, 6000, 1500, "QRIS");
        a.paymentConfirmed = true;
        b.paymentConfirmed = true;
        String text = CheckoutStrukText.compose(Arrays.asList(a, b), 2, null);
        assertTrue(text, text.endsWith("\n*Total: Rp23.000* (LUNAS via Qris)"));
        assertFalse(text, text.contains("Bayar per lokasi"));

        // Hanya satu leg yang lunas → leg lain tetap ditagih di pintunya.
        b.paymentConfirmed = false;
        assertEquals("Bayar per lokasi: Kedai Rp15.000", CheckoutStrukText.perLocationLine(Arrays.asList(a, b), NF));
    }

    @Test public void collectsAtDoor() {
        CheckoutStrukText.Leg l = leg(1, "Rumah", "A", 1, 6000, 0, "TUNAI");
        assertTrue(l.collectsAtDoor());
        l.paymentMethod = null;          // metode kosong (order lama) tetap ditagih, sama dengan web
        assertTrue(l.collectsAtDoor());
        l.paymentMethod = "hutang";
        assertFalse(l.collectsAtDoor());
        l.paymentMethod = "TRANSFER";
        l.paymentConfirmed = true;
        assertFalse(l.collectsAtDoor());
    }

    @Test public void onlyLocalLegsShowPartialHeader() {
        List<CheckoutStrukText.Leg> legs = Arrays.asList(
                leg(1, "Rumah", "A", 1, 6000, 0, "TUNAI"),
                leg(3, "Gudang", "C", 1, 6000, 0, "TUNAI"));
        String text = CheckoutStrukText.compose(legs, 3, null);
        assertTrue(text, text.startsWith("🧾 *Rincian Pembelian* (2 dari 3 lokasi)"));
    }

    @Test public void blankDestinationFallsBackToLocationNumber() {
        CheckoutStrukText.Leg a = leg(1, "", null, 1, 6000, 0, "TUNAI");
        CheckoutStrukText.Leg b = leg(2, "null", "", 1, 6000, 0, "TUNAI");
        String text = CheckoutStrukText.compose(Arrays.asList(a, b), 2, null);
        assertTrue(text, text.contains("\n\n📍 *Lokasi 1*\nGalon"));
        assertTrue(text, text.contains("\n\n📍 *Lokasi 2*\nGalon"));
        assertTrue(text, text.contains("Bayar per lokasi: Lokasi 1 Rp6.000 · Lokasi 2 Rp6.000"));
    }

    @Test public void footerOrderMatchesSingleReceipt() {
        CheckoutStrukText.Leg a = leg(1, "Rumah", "A", 1, 6000, 0, "QRIS");
        CheckoutStrukText.Leg b = leg(2, "Kedai", "B", 1, 6000, 0, "QRIS");
        a.note = "antar pagi";
        b.note = "antar pagi";
        CheckoutStrukText.Footer f = new CheckoutStrukText.Footer();
        f.payInfo = "Unduh gambar QRIS: https://airfrez.com/solo-qris";
        f.terjadwal = true;
        f.sisaHutang = 12000;
        f.sisaHutangOrigins = "DMU-X";
        String text = CheckoutStrukText.compose(Arrays.asList(a, b), 2, f);
        assertTrue(text, text.endsWith("\n*Total: Rp12.000* (Bayar Qris)"
                + "\nBayar per lokasi: Rumah Rp6.000 · Kedai Rp6.000"
                + "\nUnduh gambar QRIS: https://airfrez.com/solo-qris"
                + "\n📅 [TERJADWAL]"
                + "\n📝 Catatan: antar pagi"
                + "\n\n🧾 *Sisa Pembayaran Anda saat ini: Rp12.000*"
                + "\n(dari transaksi DMU-X)"));
        // Catatan yang sama di semua leg dicetak SEKALI, tidak di tiap bagian lokasi.
        assertEquals(text.indexOf("antar pagi"), text.lastIndexOf("antar pagi"));
    }

    @Test public void legSpecificNotesStayInsideTheirSection() {
        CheckoutStrukText.Leg a = leg(1, "Rumah", "A", 1, 6000, 0, "TUNAI");
        CheckoutStrukText.Leg b = leg(2, "Kedai", "B", 1, 6000, 0, "TUNAI");
        a.note = "antar pagi";
        b.note = "antar pagi · titip satpam Kedai";
        String text = CheckoutStrukText.compose(Arrays.asList(a, b), 2, null);
        assertTrue(text, text.contains("📍 *Kedai* — #B\nGalon 1 x Rp6.000 = Rp6.000\nGratis Ongkir"
                + "\n📝 titip satpam Kedai\nSubtotal: Rp6.000"));
        assertTrue(text, text.endsWith("\n📝 Catatan: antar pagi"));
    }

    @Test public void splitNotes() {
        CheckoutStrukText.NoteSplit s = CheckoutStrukText.splitNotes(Arrays.asList("a · b", "a · c", "a"));
        assertEquals("a", s.common);
        assertEquals(Arrays.asList("b", "c", ""), s.perLeg);

        s = CheckoutStrukText.splitNotes(Arrays.asList("x", ""));
        assertEquals("", s.common);
        assertEquals(Arrays.asList("x", ""), s.perLeg);

        s = CheckoutStrukText.splitNotes(Arrays.asList(null, null));
        assertEquals("", s.common);
        assertEquals(Arrays.asList("", ""), s.perLeg);

        s = CheckoutStrukText.splitNotes(Arrays.asList("Antar Pagi", "antar pagi"));
        assertEquals("Antar Pagi", s.common);
    }

    @Test public void splitNotesSpacesAroundDotAreOptionalLikeServer() {
        // StrukWa: preg_split('/\s*·\s*/u') — "·" tanpa spasi tetap pemisah, segmen kosong dibuang.
        CheckoutStrukText.NoteSplit s = CheckoutStrukText.splitNotes(
                Arrays.asList("antar pagi·titip satpam", "antar pagi ·  lantai 2", "antar pagi· ·"));
        assertEquals("antar pagi", s.common);
        assertEquals(Arrays.asList("titip satpam", "lantai 2", ""), s.perLeg);
    }

    // ------------------------------------------------------------------ pintu tagih hutang lama

    private static CheckoutStrukText.Leg doorLeg(int seq, String dest, String status) {
        CheckoutStrukText.Leg l = new CheckoutStrukText.Leg();
        l.seq = seq;
        l.destName = dest;
        l.deliveryStatus = status;
        return l;
    }

    @Test public void oldDebtDoorIsLowestOpenLegBeforeThisOne() {
        java.util.List<CheckoutStrukText.Leg> legs = Arrays.asList(
                doorLeg(1, "Rumah", Transaction.DELIVERY_PENDING),
                doorLeg(2, "Kedai", Transaction.DELIVERY_PENDING),
                doorLeg(3, "Gudang", Transaction.DELIVERY_PENDING));
        CheckoutStrukText.DebtDoor d1 = CheckoutStrukText.oldDebtDoor(1, 3, "Rumah", legs);
        assertTrue(d1.here);
        assertEquals(1, d1.seq);

        CheckoutStrukText.DebtDoor d2 = CheckoutStrukText.oldDebtDoor(2, 3, "Kedai", legs);
        assertFalse(d2.here);
        assertEquals(1, d2.seq);
        assertEquals("Rumah (lokasi 1/3)", d2.label());

        // TERTUNDA masih terbuka → tetap pintu tagih.
        legs = Arrays.asList(doorLeg(1, "Rumah", Transaction.DELIVERY_TERTUNDA), doorLeg(2, "Kedai", null));
        assertFalse(CheckoutStrukText.oldDebtDoor(2, 2, "Kedai", legs).here);
    }

    @Test public void oldDebtDoorMovesOnWhenEarlierLegClosed() {
        // Leg 1 sudah Selesai (pelanggan menolak membayar di sana) → hutang lama ditagih di pintu berikutnya.
        java.util.List<CheckoutStrukText.Leg> legs = Arrays.asList(
                doorLeg(1, "Rumah", Transaction.DELIVERY_DONE),
                doorLeg(2, "Kedai", Transaction.DELIVERY_PENDING),
                doorLeg(3, "Gudang", Transaction.DELIVERY_PENDING));
        assertTrue(CheckoutStrukText.oldDebtDoor(2, 3, "Kedai", legs).here);
        CheckoutStrukText.DebtDoor d3 = CheckoutStrukText.oldDebtDoor(3, 3, "Gudang", legs);
        assertFalse(d3.here);
        assertEquals(2, d3.seq);
        assertEquals("Kedai (lokasi 2/3)", d3.label());

        // Semua leg sebelumnya selesai → pintu ini (leg yang sudah DONE ditekan Selesai ulang pun sama).
        legs = Arrays.asList(doorLeg(1, "Rumah", Transaction.DELIVERY_DONE), doorLeg(2, "Kedai", Transaction.DELIVERY_DONE));
        assertTrue(CheckoutStrukText.oldDebtDoor(2, 2, "Kedai", legs).here);
        // Status null (bukan antrean) = tertutup, seperti in_array(null, [PENDING, TERTUNDA]) di web.
        legs = Arrays.asList(doorLeg(1, "Rumah", null));
        assertTrue(CheckoutStrukText.oldDebtDoor(2, 2, "Kedai", legs).here);
    }

    @Test public void oldDebtDoorUnknownEarlierLegCountsAsOpen() {
        // HP kurir hanya memegang leg ini → leg seq 1 dianggap pintu tagih (tak pernah dua pintu menagih).
        CheckoutStrukText.DebtDoor d = CheckoutStrukText.oldDebtDoor(2, 2, "Kedai",
                Collections.singletonList(doorLeg(2, "Kedai", Transaction.DELIVERY_PENDING)));
        assertFalse(d.here);
        assertEquals(1, d.seq);
        assertNull(d.dest);
        assertEquals("lokasi 1/2", d.label());
        assertFalse(CheckoutStrukText.oldDebtDoor(2, 2, "Kedai", null).here);

        // Leg 1 diketahui selesai, leg 2 tak ada di HP → leg 2 (tak dikenal) yang dianggap pintu tagih.
        d = CheckoutStrukText.oldDebtDoor(3, 3, "Gudang",
                Collections.singletonList(doorLeg(1, "Rumah", Transaction.DELIVERY_DONE)));
        assertFalse(d.here);
        assertEquals(2, d.seq);
        assertEquals("lokasi 2/3", d.label());

        // Leg 1 sendiri selalu pintu tagih bila tak ada leg sebelum dia — dengan atau tanpa saudara.
        assertTrue(CheckoutStrukText.oldDebtDoor(1, 2, "Rumah", null).here);
    }

    @Test public void oldDebtDoorKnownFlag() {
        // Pintu ini sendiri / order tunggal → pasti.
        assertTrue(CheckoutStrukText.oldDebtDoor(1, 2, "Rumah", null).known);
        assertTrue(CheckoutStrukText.oldDebtDoor(0, 0, null, null).known);
        // Leg pintu tagih ADA di HP ini & masih terbuka → pasti.
        assertTrue(CheckoutStrukText.oldDebtDoor(2, 2, "Kedai",
                Collections.singletonList(doorLeg(1, "Rumah", Transaction.DELIVERY_PENDING))).known);
        // Leg sebelumnya Selesai di HP ini → pintu ini, pasti.
        assertTrue(CheckoutStrukText.oldDebtDoor(2, 2, "Kedai",
                Collections.singletonList(doorLeg(1, "Rumah", Transaction.DELIVERY_DONE))).known);
        // Leg 1 tak ada di HP ini (di-void → dihapus keras, atau dirutekan/Selesai di HP lain) → anggapan.
        CheckoutStrukText.DebtDoor d = CheckoutStrukText.oldDebtDoor(2, 2, "Kedai",
                Collections.singletonList(doorLeg(2, "Kedai", Transaction.DELIVERY_PENDING)));
        assertFalse(d.here);      // arah aman tetap: tak ditagih otomatis di sini
        assertFalse(d.known);
    }

    @Test public void oldDebtElsewhereTextIsConditionalWhenDoorUnknown() {
        CheckoutStrukText.DebtDoor known = CheckoutStrukText.oldDebtDoor(2, 2, "Kedai",
                Collections.singletonList(doorLeg(1, "Rumah", Transaction.DELIVERY_PENDING)));
        assertEquals("ditagih di Rumah (lokasi 1/2)", known.where());
        assertEquals("Hutang lama Rp 5.000 ditagih di Rumah (lokasi 1/2)",
                CheckoutStrukText.oldDebtElsewhereLine(known, "Rp 5.000"));
        String ki = CheckoutStrukText.oldDebtElsewhereInstruction(known, "Rp 5.000");
        assertTrue(ki.startsWith("Hutang lama Rp 5.000 ditagih di Rumah (lokasi 1/2) — jangan ditagih di sini."));
        assertTrue(ki.contains("kelebihannya dicatat sebagai pelunasan hutang lama"));

        CheckoutStrukText.DebtDoor unknown = CheckoutStrukText.oldDebtDoor(2, 2, "Kedai", null);
        assertEquals("mungkin ditagih di lokasi 1/2", unknown.where());
        String ul = CheckoutStrukText.oldDebtElsewhereLine(unknown, "Rp 5.000");
        assertTrue(ul.startsWith("Hutang lama Rp 5.000 mungkin ditagih di lokasi 1/2"));
        assertTrue(ul.contains("tak ada di HP ini"));
        String ui = CheckoutStrukText.oldDebtElsewhereInstruction(unknown, "Rp 5.000");
        // Tak boleh perintah pasti menunjuk lokasi yang mungkin sudah tutup/batal.
        assertFalse(ui.contains(" ditagih di lokasi 1/2 — jangan ditagih di sini"));
        assertTrue(ui.contains("bisa sudah selesai atau dibatalkan"));
        assertTrue(ui.contains("kelebihannya dicatat sebagai pelunasan hutang lama"));
    }

    @Test public void oldDebtDoorSingleOrderIsAlwaysHere() {
        assertTrue(CheckoutStrukText.oldDebtDoor(0, 0, null, null).here);
        assertTrue(CheckoutStrukText.oldDebtDoor(1, 1, "Rumah", null).here);     // bukan checkout
        assertTrue(CheckoutStrukText.oldDebtDoor(3, 2, "Rumah", null).here);     // trio tak sah
    }

    // ------------------------------------------------------------------ antrean / leg tunggal

    @Test public void legBadgeAndHints() {
        assertEquals("🧺 Lokasi 1/2", CheckoutStrukText.legBadge(1, 2));
        assertEquals("🧺 Lokasi 10/10", CheckoutStrukText.legBadge(10, 10));
        assertEquals("", CheckoutStrukText.legBadge(0, 0));     // order biasa
        assertEquals("", CheckoutStrukText.legBadge(1, 1));     // bukan checkout
        assertEquals("", CheckoutStrukText.legBadge(3, 2));
        assertEquals("", CheckoutStrukText.legBadge(1, 11));

        assertEquals("🧺 Lokasi 2/2 · Kedai — tagih HANYA pesanan lokasi ini; lokasi lain dibayar di tempatnya sendiri.",
                CheckoutStrukText.collectHint(2, 2, " Kedai "));
        assertEquals("🧺 Lokasi 1/3 — tagih HANYA pesanan lokasi ini; lokasi lain dibayar di tempatnya sendiri.",
                CheckoutStrukText.collectHint(1, 3, "null"));
        assertEquals("", CheckoutStrukText.collectHint(0, 0, "Rumah"));
        // Status bayar leg: TUNAI = kalimat biasa; HUTANG / LUNAS = tak ada yang ditagih untuk pesanan ini.
        assertEquals("🧺 Lokasi 2/2 · Kedai — tagih HANYA pesanan lokasi ini; lokasi lain dibayar di tempatnya sendiri.",
                CheckoutStrukText.collectHint(2, 2, "Kedai", "TUNAI", false));
        assertEquals("🧺 Lokasi 2/2 · Kedai — pesanan lokasi ini dicatat HUTANG (tak ditagih sekarang); jangan tagih pesanan lokasi lain di sini.",
                CheckoutStrukText.collectHint(2, 2, "Kedai", "HUTANG", false));
        assertEquals("🧺 Lokasi 1/2 — pesanan lokasi ini sudah LUNAS, jangan ditagih lagi; jangan tagih pesanan lokasi lain di sini.",
                CheckoutStrukText.collectHint(1, 2, null, "QRIS", true));
        assertEquals("", CheckoutStrukText.collectHint(0, 0, "Rumah", "HUTANG", false));

        assertEquals("📍 *Kedai* · Lokasi 2/2 (lokasi lain dibayar terpisah)",
                CheckoutStrukText.loneLegLine(2, 2, "Kedai"));
        assertEquals("📍 Lokasi 1/2 (lokasi lain dibayar terpisah)", CheckoutStrukText.loneLegLine(1, 2, null));
        assertEquals("", CheckoutStrukText.loneLegLine(0, 0, "Kedai"));
    }

    @Test public void payLabelMatchesSingleReceipt() {
        assertEquals(" (Bayar Tunai)", CheckoutStrukText.payLabel("TUNAI", false));
        assertEquals(" (LUNAS via Transfer)", CheckoutStrukText.payLabel("TRANSFER", true));
        assertEquals(" (Sisa Pembayaran)", CheckoutStrukText.payLabel("HUTANG", false));
        assertEquals(" (LUNAS)", CheckoutStrukText.payLabel("HUTANG", true));
        assertEquals(null, CheckoutStrukText.payLabel("", false));
        assertEquals(null, CheckoutStrukText.payLabel(null, true));
    }
}
