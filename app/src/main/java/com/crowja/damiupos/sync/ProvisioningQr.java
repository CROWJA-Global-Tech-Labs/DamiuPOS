package com.crowja.damiupos.sync;

import android.net.Uri;

import org.json.JSONObject;

/**
 * Isi QR provisioning ({"url":…,"code":…}) yang sudah DIPERIKSA.
 *
 * <p>KEAMANAN: QR adalah masukan dari luar — siapa pun bisa mencetak satu dan menempelkannya di
 * dinding depot atau mengirimkannya ke karyawan baru ("scan ini untuk hubungkan HP"). Sebelumnya
 * `url` dari QR langsung dipakai menyambung TANPA ditampilkan dan tanpa konfirmasi, lalu
 * {@code engine.sync()} mendorong isi basis data HP ke sana. Satu gambar QR = seluruh data cabang
 * (pelanggan, transaksi, absensi) pindah ke server penyerang, dan HP itu seterusnya menerima
 * perintah dari dia.</p>
 *
 * <p>Kelas ini tidak memutuskan apa pun sendiri: ia menormalkan URL, menolak skema non-HTTPS, dan
 * memberi tahu pemanggil apakah tujuannya server BAWAAN yang dikenal. Kalau bukan, pemanggil WAJIB
 * meminta konfirmasi pengguna sambil MENAMPILKAN host tujuannya.</p>
 */
public class ProvisioningQr {

    public final String url;      // sudah dinormalkan; "" bila QR tak membawa url
    public final String code;
    public final boolean trustedHost;   // true = host bawaan yang dikenal

    private ProvisioningQr(String url, String code, boolean trustedHost) {
        this.url = url;
        this.code = code;
        this.trustedHost = trustedHost;
    }

    /**
     * Baca QR. QR berupa teks polos dianggap KODE saja (tanpa url) — itu jalur aman, server tetap
     * yang sudah tersetel. Mengembalikan null bila QR membawa url yang tidak layak (bukan HTTPS,
     * host kosong) — pemanggil harus menampilkan galat, bukan diam-diam melanjutkan.
     */
    public static ProvisioningQr parse(String contents) {
        String url = "", code = "";
        try {
            JSONObject j = new JSONObject(contents);
            url = j.optString("url", "").trim();
            code = j.optString("code", "").trim();
        } catch (org.json.JSONException ignored) {
            // bukan JSON → seluruh teks adalah kodenya
        }
        if (code.isEmpty()) code = contents == null ? "" : contents.trim();

        if (url.isEmpty()) {
            return new ProvisioningQr("", code, true);   // server tak diubah → tak ada yang perlu dikonfirmasi
        }

        // Buang garis miring di ujung supaya perbandingan host konsisten.
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);

        Uri u = Uri.parse(url);
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase();
        String host = u.getHost() == null ? "" : u.getHost().toLowerCase();
        // HTTPS saja: QR ber-http:// akan mengirim token & data cabang dalam keadaan terbuka.
        if (!"https".equals(scheme) || host.isEmpty()) {
            return null;
        }

        return new ProvisioningQr(url, code, host.equals(defaultHost()));
    }

    /** Host server bawaan aplikasi. */
    public static String defaultHost() {
        Uri d = Uri.parse(SyncSettings.DEFAULT_BASE_URL);
        return d.getHost() == null ? "" : d.getHost().toLowerCase();
    }

    /** Host tujuan untuk ditampilkan ke pengguna saat meminta konfirmasi. */
    public String host() {
        Uri u = Uri.parse(url);
        return u.getHost() == null ? url : u.getHost();
    }
}
