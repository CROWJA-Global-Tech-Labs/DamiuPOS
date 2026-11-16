package com.crowja.damiupos.wa;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class WaStatusVideoRulesTest {

    private static final long MB = 1024L * 1024;

    @Test
    public void sixtySecondsPlusRoundingIsOneStatus() {
        assertFalse(WaStatusVideoRules.tooLong(60_000));
        assertFalse(WaStatusVideoRules.tooLong(60_900));   // durasi kontainer sering lebih sedikit dari 60,0
        assertTrue(WaStatusVideoRules.tooLong(61_500));
        assertTrue(WaStatusVideoRules.tooLong(135_000));
    }

    @Test
    public void smallMp4ThatFitsIsUploadedAsIs() {
        assertFalse(WaStatusVideoRules.needsTranscode(8 * MB, 30_000, 1280, 720, "video/mp4"));
        assertFalse(WaStatusVideoRules.needsTranscode(16 * MB, 60_000, 720, 1280, "video/mp4"));
    }

    @Test
    public void bigHighResOtherFormatOrLongVideosAreTranscoded() {
        assertTrue("terlalu besar", WaStatusVideoRules.needsTranscode(16 * MB + 1, 30_000, 1280, 720, "video/mp4"));
        assertTrue("1080p", WaStatusVideoRules.needsTranscode(5 * MB, 10_000, 1920, 1080, "video/mp4"));
        assertTrue("1080p potret", WaStatusVideoRules.needsTranscode(5 * MB, 10_000, 1080, 1920, "video/mp4"));
        assertTrue("3gp", WaStatusVideoRules.needsTranscode(2 * MB, 10_000, 640, 480, "video/3gpp"));
        assertTrue("mime tak diketahui", WaStatusVideoRules.needsTranscode(2 * MB, 10_000, 640, 480, null));
        assertTrue("lebih dari 60 detik", WaStatusVideoRules.needsTranscode(2 * MB, 90_000, 640, 480, "video/mp4"));
    }

    @Test
    public void bitrateKeepsAFullMinuteUnderTheLimitAndIsClamped() {
        int br = WaStatusVideoRules.videoBitrate(60_000);
        long bytes = (br + 128_000L) * 60 / 8;
        assertTrue("60 dtk ≤ 16 MB: " + bytes, bytes <= WaStatusVideoRules.MAX_BYTES);
        assertTrue("tak terlalu rendah: " + br, br >= 1_500_000);
        assertEquals("video panjang dihitung sebagai 60 dtk (dipotong)", br, WaStatusVideoRules.videoBitrate(600_000));
        assertEquals("video pendek dibatasi atas", 3_000_000, WaStatusVideoRules.videoBitrate(5_000));
        assertEquals("durasi 0 tak membagi nol", 3_000_000, WaStatusVideoRules.videoBitrate(0));
    }

    @Test
    public void finalCheckExplainsWhatIsWrong() {
        assertNull(WaStatusVideoRules.finalCheck(9 * MB, 45_000));
        assertEquals("Video 1:15 — Status WhatsApp maksimal 60 detik.", WaStatusVideoRules.finalCheck(9 * MB, 75_000));
        assertEquals("Video 17,0 MB — maksimal 16,0 MB. Pilih video yang lebih pendek.",
                WaStatusVideoRules.finalCheck(17 * MB, 45_000));
        assertEquals("Video kosong atau tak bisa dibaca.", WaStatusVideoRules.finalCheck(0, 45_000));
    }

    @Test
    public void clockFormatsMinutesAndSeconds() {
        assertEquals("0:09", WaStatusVideoRules.clock(9_400));
        assertEquals("1:00", WaStatusVideoRules.clock(59_600));
        assertEquals("2:15", WaStatusVideoRules.clock(135_000));
    }
}
