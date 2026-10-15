package com.crowja.damiupos.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.crowja.damiupos.checkout.CheckoutPart;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

/**
 * Badge ongkir lokasi di Detail Pelanggan, popup peta dan tag "ONGKIR" kartu daftar mengikuti TARIF lokasi.
 *
 * 15/10/2026 (Subakri, didaftarkan agen WA): lokasi "Rumah cat biru" bertarif Rp1.000/galon di server, pesanannya benar
 * Rp7.000 + ongkir Rp1.000, tapi Detail Pelanggan menampilkan "✅ Gratis Ongkir" -- badge hanya membaca flag lama
 * wajib_ongkir yang dihapus server sejak 24/09 (tarif = satu-satunya sumber, "tarif 0/null berarti gratis").
 */
public class CustomerLocationOngkirBadgeTest {

    private static Customer.Location loc(Double ongkir, boolean wajibFlag) {
        Customer.Location l = new Customer.Location("Rumah cat biru", -7.5171507, 110.7985948, wajibFlag);
        l.ongkir = ongkir;
        return l;
    }

    @Test
    public void subakri_bertarifTanpaFlag_bukanGratis() {
        Customer.Location l = loc(1000.0, false);
        assertEquals(1000.0, l.rate(), 0.0001);
        assertTrue(l.chargesOngkir());
        assertEquals("🚚 Ongkir Rp 1.000/galon", l.ongkirBadgeText());
    }

    @Test
    public void tarifDanFlag_tetapTarif() {
        assertEquals("🚚 Ongkir Rp 2.000/galon", loc(2000.0, true).ongkirBadgeText());
    }

    @Test
    public void tanpaTarifTanpaFlag_gratis() {
        for (Double none : Arrays.asList(null, 0.0, Double.NaN, -500.0)) {
            Customer.Location l = loc(none, false);
            assertEquals(0.0, l.rate(), 0.0);
            assertFalse(l.chargesOngkir());
            assertEquals("✅ Gratis Ongkir", l.ongkirBadgeText());
        }
    }

    @Test
    public void flagLamaTanpaTarif_wajibOngkir() {
        Customer.Location l = loc(null, true);
        assertTrue(l.chargesOngkir());
        assertEquals("🚚 Wajib Ongkir", l.ongkirBadgeText());
    }

    @Test
    public void tarifPecahan_dibulatkan() {
        assertEquals("🚚 Ongkir Rp 1.500/galon", loc(1499.6, false).ongkirBadgeText());
    }

    @Test
    public void tagOngkirKartuDaftar_ikutTarif() {
        Customer c = new Customer();
        c.setLocations(new ArrayList<>(Collections.singletonList(loc(1000.0, false))));
        assertTrue(c.hasWajibOngkirLocation());
        assertTrue(c.isWajibOngkirEffective());

        Customer gratis = new Customer();
        gratis.setLocations(new ArrayList<>(Arrays.asList(loc(null, false), loc(0.0, false))));
        assertFalse(gratis.hasWajibOngkirLocation());

        Customer salahSatu = new Customer();
        salahSatu.setLocations(new ArrayList<>(Arrays.asList(loc(null, false), loc(1000.0, false))));
        assertTrue(salahSatu.hasWajibOngkirLocation());
    }

    @Test
    public void legacyTanpaDaftarLokasi_pakaiFlagPelanggan() {
        Customer c = new Customer();
        c.setWajibOngkir(true);
        assertTrue(c.hasWajibOngkirLocation());
        Customer d = new Customer();
        assertFalse(d.hasWajibOngkirLocation());
    }

    @Test
    public void checkoutMemakaiTarifYangSama() {
        assertEquals(1000.0, CheckoutPart.locationRate(loc(1000.0, false)), 0.0001);
        assertEquals(0.0, CheckoutPart.locationRate(loc(Double.NaN, true)), 0.0);
        assertEquals(0.0, CheckoutPart.locationRate(null), 0.0);
        assertTrue(CheckoutPart.isWajib(loc(1000.0, false)));
        assertFalse(CheckoutPart.isWajib(loc(null, true)));
    }
}
