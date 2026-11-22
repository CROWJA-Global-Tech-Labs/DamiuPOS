package com.crowja.damiupos.model;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Gerbang "Wajib Foto Lokasi": yang dihitung foto LOKASI TUJUAN, bukan sembarang foto pelanggan. */
public class CustomerDestPhotoTest {

    private static Customer.Location loc(String name, String... photos) {
        Customer.Location l = new Customer.Location();
        l.name = name;
        l.photos = new ArrayList<>(Arrays.asList(photos));
        l.photo = photos.length > 0 ? photos[0] : null;
        return l;
    }

    private static Customer customer(String housePhoto, Customer.Location... locs) {
        Customer c = new Customer();
        c.setPhotoUrl(housePhoto);
        List<Customer.Location> list = new ArrayList<>(Arrays.asList(locs));
        c.setLocations(list);
        return c;
    }

    @Test public void newLocationWithoutPhotoNeedsOneEvenWhenTheHouseHasAPhoto() {
        // Kasus Ibu Sit Rohmat: "Kediaman" berfoto, "Rumah Krikilan" baru tanpa foto.
        Customer c = customer("https://x/rumah.jpg", loc("Kediaman"), loc("Rumah Krikilan"));
        assertFalse(c.hasPhotoForDest("Rumah Krikilan"));
        assertFalse(c.hasPhotoForDest("  rumah krikilan "));
    }

    @Test public void housePhotoCountsForThePrimaryLocationOnly() {
        Customer c = customer("https://x/rumah.jpg", loc("Kediaman"), loc("Warung"));
        assertTrue(c.hasPhotoForDest("Kediaman"));
        assertFalse(c.hasPhotoForDest("Warung"));
    }

    @Test public void locationOwnPhotoCounts() {
        Customer c = customer(null, loc("Kediaman"), loc("Warung", "https://x/warung.jpg"));
        assertTrue(c.hasPhotoForDest("Warung"));
        assertFalse(c.hasPhotoForDest("Kediaman"));
    }

    @Test public void blankOrUnknownDestinationFallsBackToAnyPhoto() {
        Customer c = customer(null, loc("Kediaman"), loc("Warung", "https://x/warung.jpg"));
        assertTrue(c.hasPhotoForDest(null));
        assertTrue(c.hasPhotoForDest("Kantor"));
        assertFalse(customer(null, loc("Kediaman")).hasPhotoForDest(""));
    }

    @Test public void nullStringPhotoIsNotAPhoto() {
        Customer c = customer("null", loc("Kediaman", "null"));
        assertFalse(c.hasPhotoForDest("Kediaman"));
    }
}
