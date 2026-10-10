package com.crowja.damiupos.util;

import android.text.method.LinkMovementMethod;
import android.text.util.Linkify;
import android.widget.TextView;

/**
 * Link URL di catatan pesanan (mis. "Lokasi dari pelanggan: https://maps.app.goo.gl/…") jadi bisa
 * diketuk — membuka Google Maps / browser. Hanya URL web: nomor HP & angka lain di catatan
 * sengaja tidak di-link supaya teks Rp/galon tak berubah jadi tautan telepon.
 */
public final class NoteLinks {

    private NoteLinks() {}

    /** Pasang tautan pada teks yang SUDAH di-set di {@code tv}; diam bila tak ada URL. */
    public static void apply(TextView tv) {
        if (tv == null) return;
        try {
            if (Linkify.addLinks(tv, Linkify.WEB_URLS)) {
                tv.setMovementMethod(LinkMovementMethod.getInstance());
            }
        } catch (RuntimeException ignored) {
            // teks tetap tampil apa adanya
        }
    }
}
