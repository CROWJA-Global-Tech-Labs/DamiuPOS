package com.crowja.damiupos;

/**
 * Sumber tunggal konfigurasi basemap untuk semua peta dalam aplikasi.
 *
 * <p>Server tile OpenStreetMap standar ({@code tile.openstreetmap.org})
 * <b>melarang</b> pemakaian oleh aplikasi yang didistribusikan dan akan
 * mengembalikan tile "not following the tile usage policy". Karena itu kita
 * memakai basemap <b>CARTO Voyager</b> via CDN — gratis dan boleh dipakai
 * aplikasi untuk volume wajar selama mencantumkan atribusi
 * (lihat {@link #ATTRIBUTION}).
 *
 * <p>Selain itu setiap WebView/HTTP yang mengambil tile harus memakai
 * {@link #userAgent()} sebagai User-Agent: default User-Agent WebView Android
 * kerap masuk daftar blokir penyedia tile.
 */
public final class MapTiles {

    private MapTiles() {}

    /** Template tile untuk Leaflet ({s}/{z}/{x}/{y}). */
    public static final String LEAFLET_URL =
            "https://{s}.basemaps.cartocdn.com/rastertiles/voyager/{z}/{x}/{y}.png";

    /** Subdomain CDN CARTO yang dipakai pada placeholder {s}. */
    public static final String SUBDOMAINS = "abcd";

    /** Atribusi wajib (HTML) — ditampilkan di pojok peta. */
    public static final String ATTRIBUTION = "&copy; OpenStreetMap &copy; CARTO";

    /** User-Agent pengenal aplikasi (dipasang ke WebView & koneksi HTTP tile). */
    public static String userAgent() {
        return "DamiuPOS/" + BuildConfig.VERSION_NAME + " (Android; water-depot POS app)";
    }

    /** URL satu tile CARTO (subdomain tetap) untuk fetch native, mis. stempel
     *  lokasi pada foto selfie absensi. */
    public static String tileUrl(int z, int x, int y) {
        return "https://a.basemaps.cartocdn.com/rastertiles/voyager/"
                + z + "/" + x + "/" + y + ".png";
    }
}
