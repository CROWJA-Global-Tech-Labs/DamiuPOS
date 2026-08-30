package com.crowja.damiupos;

/**
 * Sumber tunggal konfigurasi basemap untuk semua peta dalam aplikasi.
 *
 * <p>Server tile OpenStreetMap standar ({@code tile.openstreetmap.org})
 * <b>melarang</b> pemakaian oleh aplikasi yang didistribusikan dan akan
 * mengembalikan tile "not following the tile usage policy".
 *
 * <p>Sempat memakai basemap CARTO Voyager, tapi CARTO MENGHENTIKAN basemap gratis
 * tanpa-kunci di {@code basemaps.cartocdn.com} (2026-08) — tiap tile sejak itu
 * menyembur watermark "API KEY REQUIRED" alih-alih peta sungguhan, di web maupun
 * di sini. Diganti <b>ESRI World Street Map</b>: tetap gratis, tanpa pendaftaran,
 * tanpa kunci, dan atribusinya cukup satu baris teks (lihat {@link #ATTRIBUTION}).
 * Cermin sisi web — kalau CARTO/ESRI berubah kebijakan lagi, ubah keduanya bersamaan.
 *
 * <p>Selain itu setiap WebView/HTTP yang mengambil tile harus memakai
 * {@link #userAgent()} sebagai User-Agent: default User-Agent WebView Android
 * kerap masuk daftar blokir penyedia tile.
 */
public final class MapTiles {

    private MapTiles() {}

    /** Template tile untuk Leaflet ({z}/{y}/{x} — urutan path ESRI, BUKAN {z}/{x}/{y} seperti
     *  OSM/CARTO. Leaflet mengganti tiap placeholder berdasarkan namanya, bukan posisinya, jadi
     *  urutan literal di URL ini aman mengikuti skema ESRI apa adanya). */
    public static final String LEAFLET_URL =
            "https://server.arcgisonline.com/ArcGIS/rest/services/World_Street_Map/MapServer/tile/{z}/{y}/{x}";

    /** ESRI melayani dari satu host (bukan CDN bersubdomain seperti CARTO) — tak ada {s} untuk
     *  diisi. Dipertahankan sebagai string kosong (bukan dihapus) supaya pemanggil lama yang masih
     *  menyisipkan {@code subdomains:'...'} ke opsi Leaflet tidak perlu diubah satu per satu;
     *  Leaflet mengabaikan opsi itu kalau URL-nya tak memuat {s}. */
    public static final String SUBDOMAINS = "";

    /** Atribusi wajib (HTML) — ditampilkan di pojok peta. */
    public static final String ATTRIBUTION =
            "Tiles &copy; Esri &mdash; Source: Esri, HERE, Garmin, USGS, NGA, NOAA";

    /**
     * CSS agar basemap tampil LEBIH CERAH. ESRI World Street Map aslinya bernuansa krem-kusam;
     * di layar HP yang dipakai di luar ruangan (kurir, siang hari) jalan & label jadi kurang
     * kontras terhadap pin berwarna.
     *
     * <p>Sengaja filter CSS pada tile, BUKAN ganti penyedia peta: mengganti penyedia menyeret
     * masalah atribusi, kuota, dan urutan {z}/{y}/{x} yang berbeda (lihat catatan {@link #LEAFLET_URL}).
     * Filter hanya menyentuh tampilan, jadi aman dan bisa dibalik satu baris.</p>
     *
     * <p>Dipasang HANYA pada tile — bukan pada {@code #map} — supaya pin, garis, dan popup TIDAK
     * ikut terfilter (memfilter seluruh peta akan memucatkan warna identitas perangkat, yang justru
     * jadi penanda utama di layar ini).</p>
     */
    public static final String BRIGHT_TILE_CSS =
            ".leaflet-tile{filter:brightness(1.07) saturate(1.18) contrast(1.04);}";

    /** User-Agent pengenal aplikasi (dipasang ke WebView & koneksi HTTP tile). */
    public static String userAgent() {
        return "DamiuPOS/" + BuildConfig.VERSION_NAME + " (Android; water-depot POS app)";
    }

    /** URL satu tile ESRI untuk fetch native, mis. stempel lokasi pada foto selfie absensi. */
    public static String tileUrl(int z, int x, int y) {
        return "https://server.arcgisonline.com/ArcGIS/rest/services/World_Street_Map/MapServer/tile/"
                + z + "/" + y + "/" + x;
    }
}
