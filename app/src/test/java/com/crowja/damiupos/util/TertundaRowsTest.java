package com.crowja.damiupos.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.crowja.damiupos.util.TertundaRows.Local;
import com.crowja.damiupos.util.TertundaRows.Merged;
import com.crowja.damiupos.util.TertundaRows.Result;
import com.crowja.damiupos.util.TertundaRows.Row;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Penguraian GET /api/delivery/tertunda + aturan gabung lokal/server Antrean Tertunda (tanpa Android). */
public class TertundaRowsTest {

    /** Baris RAFI persis kasus produksi: asal HP lain, belum dirutekan, bukan Pesanan Terbuka. */
    private static final String RAFI_ROW = "{"
            + "\"id\":4812,\"uuid\":\"u-rafi\",\"receipt_no\":\"DMU-1210260800-AB12C\","
            + "\"routed_uuid\":null,\"device_group_uuid\":\"dev-rafi\",\"device_group_label\":\"RAFI\","
            + "\"customer_uuid\":\"c-1\",\"name\":\"Bu Sari\",\"phone\":\"0812\",\"address\":\"Jl. Mawar\","
            + "\"latitude\":-7.0012,\"longitude\":\"110.41\",\"dest_name\":\"Rumah\","
            + "\"checkout\":{\"uuid\":\"co-1\",\"seq\":2,\"size\":3,\"dest\":\"Rumah\"},"
            + "\"galon\":4,\"total\":28000.0,\"ongkir\":2000,\"items\":\"4× Isi Ulang\",\"note\":\"pagar hijau\","
            + "\"ordered_at\":\"2026-10-10T01:00:00.000000Z\",\"source_wa\":\"ZAKY\",\"queued_at\":\"2026-10-10 08:00:00\","
            + "\"tertunda_at\":\"2026-10-10T02:00:00.000000Z\",\"tertunda_reason\":\"Pelanggan tidak di rumah\","
            + "\"tertunda_photo_url\":\"https://x/p.jpg\",\"resume_at\":\"2026-10-12T05:00:00.000000Z\","
            + "\"pickup_only\":false,\"open_dispatch\":false,\"chat_session\":true,\"in_progress\":false,"
            + "\"mine\":false,\"type\":\"JUAL\",\"payment_method\":\"QRIS\",\"void_pending\":false,"
            + "\"resume_at_local\":\"2026-10-12 12:00:00\",\"tertunda_at_local\":\"2026-10-10 09:00:00\","
            + "\"due_at_local\":\"2026-10-12 08:00:00\""
            + "}";

    private static String body(String... rows) {
        return "{\"tertunda\":[" + String.join(",", rows) + "],\"count\":" + rows.length
                + ",\"server_time\":\"2026-10-11 07:00:00\"}";
    }

    private static String row(String uuid) {
        return "{\"uuid\":\"" + uuid + "\",\"name\":\"N-" + uuid + "\",\"galon\":1}";
    }

    private static Local<String> local(String item, String uuid, boolean synced) {
        return new Local<>(item, uuid, synced);
    }

    private static List<String> serverUuids(List<Merged<String>> m) {
        List<String> out = new ArrayList<>();
        for (Merged<String> x : m) out.add(x.server != null ? x.server.uuid : "L:" + x.local);
        return out;
    }

    // ---------------------------------------------------------------- parse

    @Test public void parsesTheFullRowShape() {
        Result r = TertundaRows.parse(body(RAFI_ROW));
        assertTrue(r.ok);
        assertEquals(1, r.rows.size());
        assertEquals(1, r.count);
        assertEquals("2026-10-11 07:00:00", r.serverTime);
        Row x = r.rows.get(0);
        assertEquals("u-rafi", x.uuid);
        assertEquals("Bu Sari", x.name);
        assertEquals("DMU-1210260800-AB12C", x.receiptNo);
        assertEquals("", x.routedUuid);   // JSON null → ""
        assertEquals("dev-rafi", x.deviceGroupUuid);
        assertEquals("RAFI", x.deviceGroupLabel);
        assertEquals("c-1", x.customerUuid);
        assertEquals(4, x.galon);
        assertEquals(28000.0, x.total, 0.001);
        assertEquals(2000.0, x.ongkir, 0.001);
        assertEquals(2, x.checkoutSeq);
        assertEquals(3, x.checkoutSize);
        assertEquals(-7.0012, x.latitude, 1e-9);
        assertEquals(110.41, x.longitude, 1e-9);   // angka sebagai string tetap terbaca
        assertEquals("2026-10-12 12:00:00", x.resumeAtLocal);   // *_local diutamakan
        assertEquals("2026-10-10 09:00:00", x.tertundaAtLocal);
        assertEquals("2026-10-12 08:00:00", x.dueAtLocal);
        assertEquals("Pelanggan tidak di rumah", x.tertundaReason);
        assertEquals("https://x/p.jpg", x.tertundaPhotoUrl);
        assertEquals("4× Isi Ulang", x.items);
        assertEquals("pagar hijau", x.note);
        assertEquals("ZAKY", x.sourceWa);
        assertEquals("JUAL", x.type);
        assertEquals("QRIS", x.paymentMethod);
        assertEquals("Rumah", x.destName);
        assertTrue(x.chatSession);
        assertFalse(x.mine);
        assertFalse(x.inProgress);
        assertFalse(x.voidPending);
        assertFalse(x.pickupOnly);
        assertTrue(x.json.contains("\"uuid\":\"u-rafi\""));
    }

    @Test public void toleratesMissingNullAndOddlyTypedKeys() {
        Result r = TertundaRows.parse(body("{\"uuid\":\"u1\",\"name\":null,\"galon\":\"3\",\"total\":\"abc\","
                + "\"checkout\":null,\"checkout_seq\":1,\"checkout_size\":2,\"receipt_no\":\"null\","
                + "\"mine\":1,\"void_pending\":\"true\",\"in_progress\":\"0\",\"type\":\"KEMBALI\","
                + "\"latitude\":null,\"items\":[\"x\"],\"resume_at\":\"2026-10-12 08:00:00\"}"));
        assertTrue(r.ok);
        Row x = r.rows.get(0);
        assertEquals("Umum", x.name);              // nama kosong → "Umum" seperti server
        assertEquals(3, x.galon);
        assertEquals(0.0, x.total, 0.0);           // bukan angka → 0, bukan crash
        assertEquals(1, x.checkoutSeq);            // kolom datar dipakai bila blok checkout absen
        assertEquals(2, x.checkoutSize);
        assertEquals("", x.receiptNo);             // literal "null" → kosong
        assertTrue(x.mine);
        assertTrue(x.voidPending);
        assertFalse(x.inProgress);
        assertTrue(x.pickupOnly);                  // type KEMBALI = jemput, walau pickup_only absen
        assertEquals(0.0, x.latitude, 0.0);
        assertEquals("", x.items);                 // bukan primitif → kosong
        // resume_at_local absen → resume_at mentah dinormalkan lewat Ts (bentuk lokal tetap apa adanya)
        assertEquals("2026-10-12 08:00:00", x.resumeAtLocal);
        assertEquals("", x.dueAtLocal);
        assertEquals("", x.tertundaReason);
        assertEquals("", x.deviceGroupLabel);
    }

    @Test public void isoUtcResumeFallbackIsConvertedToLocalNotTruncated() {
        Result r = TertundaRows.parse(body("{\"uuid\":\"u1\",\"resume_at\":\"2026-10-12T01:00:00.000000Z\"}"));
        assertEquals(Ts.local("2026-10-12T01:00:00.000000Z"), r.rows.get(0).resumeAtLocal);
        assertFalse(r.rows.get(0).resumeAtLocal.contains("T"));
    }

    @Test public void unusableBodiesAreUnavailableNotEmpty() {
        for (String b : Arrays.asList(null, "", "   ", "garbage{", "[]", "{}", "{\"tertunda\":null}",
                "{\"tertunda\":{\"a\":1}}", "{\"message\":\"Unauthenticated.\"}", "\"str\"")) {
            Result r = TertundaRows.parse(b);
            assertFalse("body=" + b, r.ok);
            assertTrue(r.rows.isEmpty());
        }
    }

    @Test public void emptyListIsAValidAnswer() {
        Result r = TertundaRows.parse("{\"tertunda\":[],\"count\":0}");
        assertTrue(r.ok);
        assertTrue(r.rows.isEmpty());
        assertEquals("", r.serverTime);
    }

    @Test public void skipsNonObjectElementsAndDuplicateUuids() {
        Result r = TertundaRows.parse("{\"tertunda\":[1,null,\"x\"," + row("a") + "," + row("a") + "," + row("b") + "]}");
        assertTrue(r.ok);
        assertEquals(2, r.rows.size());
        assertEquals("a", r.rows.get(0).uuid);
        assertEquals("b", r.rows.get(1).uuid);
        assertEquals(2, r.count);   // count absen → jumlah baris
    }

    @Test public void mineAndRoutedToNeedsBothFlags() {
        Row routedMine = TertundaRows.parse(body("{\"uuid\":\"a\",\"mine\":true,\"routed_uuid\":\"me\"}")).rows.get(0);
        Row originMine = TertundaRows.parse(body("{\"uuid\":\"a\",\"mine\":true,\"routed_uuid\":null}")).rows.get(0);
        Row other = TertundaRows.parse(body("{\"uuid\":\"a\",\"mine\":false,\"routed_uuid\":\"me\"}")).rows.get(0);
        assertTrue(routedMine.mineAndRoutedTo("me"));
        assertFalse(routedMine.mineAndRoutedTo(""));
        assertFalse(routedMine.mineAndRoutedTo(null));
        assertFalse(originMine.mineAndRoutedTo("me"));
        assertFalse(other.mineAndRoutedTo("me"));
    }

    // ---------------------------------------------------------------- merge

    @Test public void serverSuccessShowsEveryServerRowInServerOrder() {
        // Kasus produksi: HP ini hanya memegang 1 dari 3 order tertunda cabang.
        Result s = TertundaRows.parse(body(row("r1"), row("mine1"), row("r2")));
        List<Merged<String>> m = TertundaRows.merge(s,
                Arrays.asList(local("LOCAL-mine1", "mine1", true)), null);
        assertEquals(Arrays.asList("r1", "mine1", "r2"), serverUuids(m));
        assertNull(m.get(0).local);
        assertTrue(m.get(0).isRemoteOnly());
        assertEquals("LOCAL-mine1", m.get(1).local);   // baris lokal terpasang lewat uuid
        assertFalse(m.get(1).isRemoteOnly());
        assertEquals("mine1", m.get(1).uuid());
        assertNull(m.get(2).local);
    }

    @Test public void syncedLocalRowsTheServerNoLongerListsAreHidden() {
        // Sudah dilanjutkan di web/HP lain; salinan lokal masih TERTUNDA sampai pull tiba.
        Result s = TertundaRows.parse(body(row("a")));
        List<Merged<String>> m = TertundaRows.merge(s, Arrays.asList(
                local("A", "a", true), local("STALE", "stale", true)), null);
        assertEquals(Arrays.asList("a"), serverUuids(m));
    }

    @Test public void unsyncedLocalRowsMissingFromServerAreKept() {
        Result s = TertundaRows.parse(body(row("a")));
        List<Merged<String>> m = TertundaRows.merge(s, Arrays.asList(
                local("A", "a", false),               // dikenal server → tetap satu kartu, data server
                local("OFFLINE", "new-1", false),     // ditunda luring, belum terdorong
                local("NOUUID", "", false),           // baris lama tanpa uuid, belum terdorong
                local("NOUUID-SYNCED", null, true)),  // tanpa uuid & sudah terdorong → disembunyikan
                null);
        assertEquals(Arrays.asList("a", "L:OFFLINE", "L:NOUUID"), serverUuids(m));
        assertEquals("A", m.get(0).local);
        assertNull(m.get(1).server);
        assertEquals("new-1", m.get(1).uuid());
        assertEquals("", m.get(2).uuid());
    }

    @Test public void serverFailureFallsBackToAllLocalRows() {
        List<Local<String>> locals = Arrays.asList(local("A", "a", true), local("B", "b", false),
                local("A-DUP", "a", true));
        for (Result s : Arrays.asList(null, TertundaRows.unavailable(), TertundaRows.parse("oops"))) {
            List<Merged<String>> m = TertundaRows.merge(s, locals, uuid -> "LOOKUP");
            assertEquals(2, m.size());   // uuid ganda dibuang, tak ada yang disembunyikan
            assertEquals("A", m.get(0).local);
            assertEquals("B", m.get(1).local);
            assertNull(m.get(0).server);
            assertNull(m.get(1).server);
        }
    }

    @Test public void lookupIsOnlyAskedForServerUuidsMissingLocally() {
        Result s = TertundaRows.parse(body(row("here"), row("stale-pending"), row("remote")));
        final List<String> asked = new ArrayList<>();
        List<Merged<String>> m = TertundaRows.merge(s, Arrays.asList(local("HERE", "here", true)), uuid -> {
            asked.add(uuid);
            return "stale-pending".equals(uuid) ? "LOCAL-COPY" : null;
        });
        assertEquals(Arrays.asList("stale-pending", "remote"), asked);
        assertEquals("HERE", m.get(0).local);
        assertEquals("LOCAL-COPY", m.get(1).local);
        assertNull(m.get(2).local);
    }

    @Test public void remoteRowsNeverBorrowALocalItemWithoutMatchingUuid() {
        // Id server (4812) tak boleh dipakai mencari baris lokal: pasangan hanya lewat uuid.
        Result s = TertundaRows.parse(body(RAFI_ROW));
        String localItem = "LOCAL-4812";
        List<Merged<String>> m = TertundaRows.merge(s, Arrays.asList(local(localItem, "other-uuid", true)), null);
        assertEquals(1, m.size());
        assertNull(m.get(0).local);
        assertEquals("u-rafi", m.get(0).uuid());
    }

    @Test public void serverRowWithoutUuidIsShownButNeverMatched() {
        Result s = TertundaRows.parse(body("{\"name\":\"X\"}"));
        List<Merged<String>> m = TertundaRows.merge(s, Arrays.asList(local("L", "", false)), uuid -> "NO");
        assertEquals(2, m.size());
        assertNull(m.get(0).local);
        assertEquals("", m.get(0).uuid());
        assertSame("L", m.get(1).local);
    }

    // ---------------------------------------------------------------- "sudah dilepas" server (sesi ini)

    @Test public void releasedRowsAreHiddenFromTheOfflineFallbackOnly() {
        // Baru dilanjutkan lewat server; muat ulang sesudahnya gagal → salinan lokal (masih TERTUNDA,
        // synced=1) tak boleh muncul lagi dengan aksi DAO lokal.
        List<Local<String>> locals = Arrays.asList(local("A", "a", true), local("B", "b", true),
                local("NOUUID", "", false));
        Set<String> released = new HashSet<>(Collections.singletonList("a"));
        List<Merged<String>> off = TertundaRows.merge(TertundaRows.unavailable(), locals, null, released);
        assertEquals(Arrays.asList("L:B", "L:NOUUID"), serverUuids(off));
        List<Merged<String>> loading = TertundaRows.merge(null, locals, null, released);
        assertEquals(Arrays.asList("L:B", "L:NOUUID"), serverUuids(loading));

        // Server menjawab → daftarnya yang berlaku (ditunda ulang di tempat lain = tampil lagi).
        List<Merged<String>> on = TertundaRows.merge(TertundaRows.parse(body(row("a"))), locals, null, released);
        assertEquals(Arrays.asList("a", "L:NOUUID"), serverUuids(on));
        assertEquals("A", on.get(0).local);
    }

    @Test public void releasedIsForgottenOnlyForUuidsTheServerListsAgain() {
        Set<String> released = new HashSet<>(Arrays.asList("a", "b"));
        TertundaRows.forgetReleasedListedBy(released, TertundaRows.unavailable());
        assertEquals(new HashSet<>(Arrays.asList("a", "b")), released);   // luring → tak ada bukti
        TertundaRows.forgetReleasedListedBy(released, TertundaRows.parse(body(row("b"), row("c"))));
        assertEquals(new HashSet<>(Collections.singletonList("a")), released);
        TertundaRows.forgetReleasedListedBy(null, TertundaRows.parse(body(row("b"))));   // tak crash
    }

    // ---------------------------------------------------------------- jalur tulis + tombol

    @Test public void writePathPrefersTheServerWheneverTheRowIsKnownThere() {
        // daftar server tampil
        assertEquals(TertundaRows.WritePath.SERVER, TertundaRows.writePath(true, false, "u", true, false, false, false));
        assertEquals(TertundaRows.WritePath.SERVER, TertundaRows.writePath(true, false, "u", true, true, true, false));
        // daftar server masih dimuat: salinan lokal sudah terdorong → server; belum terdorong → lokal
        assertEquals(TertundaRows.WritePath.SERVER, TertundaRows.writePath(true, false, "u", false, true, false, false));
        assertEquals(TertundaRows.WritePath.LOCAL, TertundaRows.writePath(true, false, "u", false, true, true, false));
        // luring
        assertEquals(TertundaRows.WritePath.LOCAL, TertundaRows.writePath(true, true, "u", false, true, false, false));
        // tak terhubung server sama sekali / baris tanpa uuid
        assertEquals(TertundaRows.WritePath.LOCAL, TertundaRows.writePath(false, false, "u", false, true, false, false));
        assertEquals(TertundaRows.WritePath.LOCAL, TertundaRows.writePath(true, false, "", false, true, false, false));
        assertEquals(TertundaRows.WritePath.NONE, TertundaRows.writePath(false, false, "u", true, false, false, false));
        assertEquals(TertundaRows.WritePath.NONE, TertundaRows.writePath(true, true, " ", false, false, false, true));
    }

    @Test public void rowsTheServerAlreadyChangedNeverFallBackToTheLocalDao() {
        // Ulasan: aksi server sukses lalu muat ulang gagal → daftar luring, salinan lokal TERTUNDA
        // synced=1. DAO lokal akan mendorong seluruh baris basi (edited_at=sekarang, menang LWW) dan
        // membalikkan order yang baru dilanjutkan. Jalurnya tetap server (gagal jaringan = aman).
        assertEquals(TertundaRows.WritePath.SERVER, TertundaRows.writePath(true, true, "u", false, true, false, true));
        assertEquals(TertundaRows.WritePath.SERVER, TertundaRows.writePath(true, true, "u", false, true, true, true));
        // …tanpa koneksi server sama sekali sentuhan server mustahil — jalur lama tetap.
        assertEquals(TertundaRows.WritePath.LOCAL, TertundaRows.writePath(false, true, "u", false, true, false, true));
    }

    @Test public void canClaimOnlyOnEnrolledDeliveryDevicesAndNotForRowsAlreadyRoutedHere() {
        Row other = TertundaRows.parse(body("{\"uuid\":\"a\",\"mine\":false,\"routed_uuid\":null}")).rows.get(0);
        Row originMine = TertundaRows.parse(body("{\"uuid\":\"a\",\"mine\":true,\"routed_uuid\":null}")).rows.get(0);
        Row routedMine = TertundaRows.parse(body("{\"uuid\":\"a\",\"mine\":true,\"routed_uuid\":\"me\"}")).rows.get(0);
        Row running = TertundaRows.parse(body("{\"uuid\":\"a\",\"in_progress\":true}")).rows.get(0);
        Row noUuid = TertundaRows.parse(body("{\"name\":\"x\"}")).rows.get(0);
        assertTrue(TertundaRows.canClaim(other, true, true, "me"));
        assertTrue(TertundaRows.canClaim(originMine, true, true, "me"));   // bisa saja dibuka bila HP ini kosong
        assertFalse(TertundaRows.canClaim(routedMine, true, true, "me"));
        assertFalse(TertundaRows.canClaim(running, true, true, "me"));
        assertFalse(TertundaRows.canClaim(noUuid, true, true, "me"));
        assertFalse(TertundaRows.canClaim(other, true, false, "me"));       // HP kasir/marketing → server 422
        assertFalse(TertundaRows.canClaim(other, false, true, "me"));
        assertFalse(TertundaRows.canClaim(null, true, true, "me"));
    }

    @Test public void staleCodesAreTheOnesThatMeanTheOrderChangedElsewhere() {
        assertTrue(TertundaRows.isStaleCode(404));
        assertTrue(TertundaRows.isStaleCode(409));
        assertTrue(TertundaRows.isStaleCode(422));
        assertFalse(TertundaRows.isStaleCode(0));     // jaringan
        assertFalse(TertundaRows.isStaleCode(429));   // batas laju → coba lagi
        assertFalse(TertundaRows.isStaleCode(500));
    }

    // ---------------------------------------------------------------- label & kalimat konfirmasi

    @Test public void ownerLabelNamesOpenDispatchWebAndMineRows() {
        assertEquals("🎲 Pesanan Terbuka", TertundaRows.parse(body(
                "{\"uuid\":\"a\",\"open_dispatch\":true,\"device_group_uuid\":\"d\",\"device_group_label\":\"RAFI\"}"))
                .rows.get(0).ownerLabel());
        assertEquals("📱 HP ini", TertundaRows.parse(body("{\"uuid\":\"a\",\"mine\":true,\"device_group_label\":\"HP Saya\"}"))
                .rows.get(0).ownerLabel());
        Row web = TertundaRows.parse(body("{\"uuid\":\"a\",\"device_group_uuid\":\"web\",\"device_group_label\":\"Web Dashboard\"}")).rows.get(0);
        assertTrue(web.unassigned());
        assertEquals("🌐 Web Dashboard", web.ownerLabel());
        Row none = TertundaRows.parse(body("{\"uuid\":\"a\",\"device_group_uuid\":\"__none__\",\"device_group_label\":\"Belum Ditugaskan\"}")).rows.get(0);
        assertTrue(none.unassigned());
        Row rafi = TertundaRows.parse(body(RAFI_ROW)).rows.get(0);
        assertFalse(rafi.unassigned());
        assertFalse(rafi.openDispatch);
        assertEquals("📱 RAFI", rafi.ownerLabel());
    }

    @Test public void resumeHintNeverPromisesADestinationTheServerMayNotPick() {
        // Baris HP ini juga dirutekan server: HP ini tanpa staf absen → dibuka.
        String mine = TertundaRows.resumeDestinationHint(TertundaRows.parse(body(
                "{\"uuid\":\"a\",\"mine\":true,\"device_group_uuid\":\"me\",\"device_group_label\":\"HP Saya\"}")).rows.get(0));
        assertTrue(mine, mine.contains("HP ini"));
        assertTrue(mine, mine.contains("Pesanan Terbuka"));
        // Order web tanpa rute: BUKAN "ke antrean Web Dashboard".
        String web = TertundaRows.resumeDestinationHint(TertundaRows.parse(body(
                "{\"uuid\":\"a\",\"device_group_uuid\":\"web\",\"device_group_label\":\"Web Dashboard\"}")).rows.get(0));
        assertFalse(web, web.contains("Web Dashboard"));
        assertTrue(web, web.contains("wilayah"));
        // Pesanan Terbuka yang dijeda tetap terbuka, bukan ke HP asalnya.
        String open = TertundaRows.resumeDestinationHint(TertundaRows.parse(body(
                "{\"uuid\":\"a\",\"open_dispatch\":true,\"device_group_label\":\"RAFI\"}")).rows.get(0));
        assertFalse(open, open.contains("RAFI"));
        assertTrue(open, open.contains("Pesanan Terbuka"));
        String rafi = TertundaRows.resumeDestinationHint(TertundaRows.parse(body(RAFI_ROW)).rows.get(0));
        assertTrue(rafi, rafi.contains("\"RAFI\""));
        assertTrue(rafi, rafi.contains("Pesanan Terbuka"));
        assertTrue(TertundaRows.resumeDestinationHint(null).contains("Pesanan Terbuka"));
    }

    // ---------------------------------------------------------------- "📥 Lanjutkan & Ambil" dua langkah

    @Test public void claimStepUsesTheDestinationTheResumeReported() {
        TertundaRows.ResumeOutcome open = TertundaRows.parseResume(
                "{\"ok\":true,\"message\":\"Terbuka\",\"routed_device\":null,\"open_dispatch\":true}");
        TertundaRows.ResumeOutcome rafi = TertundaRows.parseResume(
                "{\"ok\":true,\"message\":\"ke RAFI\",\"routed_device\":{\"uuid\":\"dev-rafi\",\"name\":\"RAFI\"},\"open_dispatch\":false}");
        TertundaRows.ResumeOutcome me = TertundaRows.parseResume(
                "{\"ok\":true,\"routed_device\":{\"uuid\":\"me\",\"name\":\"HP Saya\"},\"open_dispatch\":false}");
        assertEquals("Terbuka", open.message);
        assertTrue(open.openDispatch);
        assertEquals("", TertundaRows.claimExpectedAfterResume(open, "me"));
        assertEquals("dev-rafi", rafi.routedUuid);
        assertEquals("RAFI", rafi.routedName);
        assertEquals("dev-rafi", TertundaRows.claimExpectedAfterResume(rafi, "me"));
        assertNull("sudah di antrean HP ini → klaim dilewati (server: 'sudah di antrian Anda')",
                TertundaRows.claimExpectedAfterResume(me, "me"));
        assertEquals("me", TertundaRows.claimExpectedAfterResume(me, ""));
    }

    @Test public void unreadableResumeBodyStillMeansResumedWithUnknownDestination() {
        for (String b : Arrays.asList(null, "", "oops", "[]", "{\"routed_device\":\"x\"}")) {
            TertundaRows.ResumeOutcome r = TertundaRows.parseResume(b);
            assertEquals("", r.message);
            assertEquals("", r.routedUuid);
            assertFalse(r.openDispatch);
            assertEquals("body=" + b, "", TertundaRows.claimExpectedAfterResume(r, "me"));
        }
        assertEquals("", TertundaRows.claimExpectedAfterResume(null, "me"));
    }
}
