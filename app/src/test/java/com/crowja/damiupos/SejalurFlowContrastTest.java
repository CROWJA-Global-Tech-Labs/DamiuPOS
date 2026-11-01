package com.crowja.damiupos;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 🛣️ Kirim Sejalur — kontras teks pemilih titik terjauh (WCAG ≥ 4,5:1), termasuk baris yang TIDAK bisa
 * dipilih (tanpa lokasi / terjadwal). Dulu kartunya diberi alpha 0,6 sehingga alasan "⚠ tanpa lokasi"
 * turun ke ±3:1; kini latar abu-abu dengan teks penuh. Konstanta SejalurFlow adalah compile-time
 * constant, jadi tes ini tak memuat kelas Android apa pun.
 */
public class SejalurFlowContrastTest {

    private static final int PICKER_ROOT_BG = 0xFFF5F5F5;

    private static double channel(int c) {
        double s = c / 255.0;
        return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
    }

    private static double luminance(int argb) {
        return 0.2126 * channel((argb >> 16) & 0xFF) + 0.7152 * channel((argb >> 8) & 0xFF) + 0.0722 * channel(argb & 0xFF);
    }

    static double contrast(int fg, int bg) {
        double a = luminance(fg), b = luminance(bg);
        return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
    }

    /** Warna fg dengan alpha di atas bg (cara View.setAlpha menggambar kartu). */
    private static int blend(int fg, int bg, double alpha) {
        int r = (int) Math.round(((fg >> 16) & 0xFF) * alpha + ((bg >> 16) & 0xFF) * (1 - alpha));
        int g = (int) Math.round(((fg >> 8) & 0xFF) * alpha + ((bg >> 8) & 0xFF) * (1 - alpha));
        int b = (int) Math.round((fg & 0xFF) * alpha + (bg & 0xFF) * (1 - alpha));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static void assertReadable(String what, int fg, int bg) {
        double c = contrast(fg, bg);
        assertTrue(what + " kontras " + String.format("%.2f", c) + ":1 < 4,5:1", c >= 4.5);
    }

    @Test
    public void blockedRowTextStaysReadableOnGreyCard() {
        int bg = SejalurFlow.C_CARD_BLOCKED_BG;
        assertReadable("alasan ⚠ (C_DANGER)", SejalurFlow.C_DANGER, bg);
        assertReadable("nama (C_TEXT)", SejalurFlow.C_TEXT, bg);
        assertReadable("area (C_TEXT2)", SejalurFlow.C_TEXT2, bg);
        assertReadable("produk (C_MUTED)", SejalurFlow.C_MUTED, bg);
    }

    @Test
    public void normalRowTextReadableOnWhiteCard() {
        int bg = SejalurFlow.C_CARD_BG;
        assertReadable("nama", SejalurFlow.C_TEXT, bg);
        assertReadable("area", SejalurFlow.C_TEXT2, bg);
        assertReadable("produk", SejalurFlow.C_MUTED, bg);
        assertReadable("peringatan", SejalurFlow.C_DANGER, bg);
        assertReadable("ringkasan OK", SejalurFlow.C_OK, bg);
    }

    @Test
    public void fadingTheWholeCardWouldFailWhichIsWhyItIsNotDone() {
        // Bukti regresi: alpha 0,6 di atas latar pemilih membuat teks alasan & produk tak terbaca.
        int card = blend(SejalurFlow.C_CARD_BG, PICKER_ROOT_BG, 0.6);
        assertTrue(contrast(blend(SejalurFlow.C_DANGER, PICKER_ROOT_BG, 0.6), card) < 4.5);
        assertTrue(contrast(blend(SejalurFlow.C_MUTED, PICKER_ROOT_BG, 0.6), card) < 4.5);
    }
}
