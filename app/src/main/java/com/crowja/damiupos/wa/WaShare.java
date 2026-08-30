package com.crowja.damiupos.wa;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Kirim pesan (opsional + FOTO) ke nomor pelanggan lewat WhatsApp — SATU jalur untuk seluruh
 * aplikasi.
 *
 * <p>Kelas ini lahir dari pemadatan logika yang sudah teruji di lapangan pada
 * {@code DeliveryObstacleActivity}: menembak WhatsApp dengan foto + caption ke nomor TERTENTU
 * ternyata penuh jebakan yang hanya ketahuan di perangkat nyata, dan menyalinnya ulang di setiap
 * layar berarti menyalin ulang bug-nya juga. Yang ditangani di sini:</p>
 *
 * <ul>
 *   <li><b>setComponent, bukan sekadar setPackage.</b> Samsung Freecess diam-diam membatalkan
 *       {@code startActivity} berbasis setPackage ke WhatsApp yang sedang beku — tanpa exception,
 *       sehingga tombolnya tampak mati begitu saja.</li>
 *   <li><b>Alias share ditembak lebih dulu.</b> WhatsApp mengganti nama kelas share antar rilis
 *       (2.26: {@code …contact.ui.picker.ExternalShareAlias}; lama: {@code …contact.picker
 *       .ContactPicker}). {@code resolveActivity} untuk sebuah activity-alias mengembalikan
 *       targetActivity-nya — yaitu layar "Kirim ke…" yang MENGABAIKAN jid — jadi resolveActivity
 *       sengaja ditaruh PALING BELAKANG sebagai jaring pengaman.</li>
 *   <li><b>Kontak disimpan dulu.</b> WhatsApp hanya menghormati {@code jid} untuk nomor yang
 *       TERSIMPAN di kontak HP ({@see WaContactEnsure}); tanpa itu ia jatuh ke pemilih kontak.</li>
 *   <li><b>Tanpa foto → wa.me.</b> Jalur teks-saja membuka chat pelanggan langsung, jadi tak ada
 *       gunanya memaksa jalur share.</li>
 * </ul>
 *
 * <p>SENGAJA tidak memanggil {@code WaAutoSendService.arm()}: verifikasi tujuannya membaca kotak
 * ketik chat dan hanya sahih untuk pesan TEKS. Pada layar pratinjau media verifikasi itu runtuh,
 * sehingga klik-otomatis bisa mengirim foto ke chat ORANG LAIN.</p>
 */
public final class WaShare {

    private WaShare() {}

    /**
     * Kirim {@code text} (jadi caption bila ada foto) ke nomor pelanggan. Otomatis jatuh ke jalur
     * teks-saja bila fotonya tak ada/gagal dibaca atau nomornya tak bisa dijadikan jid.
     *
     * @param photoPath path file foto lokal; boleh null/kosong → teks saja
     * @return true bila WhatsApp (atau chooser) berhasil dibuka
     */
    public static boolean sendPhotoWithCaption(Activity act, String customerName, String phone,
                                               String photoPath, String text) {
        String jid = waJid(phone);
        File photo = (photoPath == null || photoPath.isEmpty()) ? null : new File(photoPath);
        Uri uri = null;
        if (photo != null && photo.exists() && photo.length() > 0) {
            try {
                uri = FileProvider.getUriForFile(act,
                        act.getApplicationContext().getPackageName() + ".fileprovider", photo);
            } catch (Exception ignored) {
                uri = null;
            }
        }
        if (uri == null || jid == null) {
            return openTextOnly(act, phone, text);
        }

        // jid hanya dihormati untuk nomor yang tersimpan di kontak → simpan dulu (sekali per orang).
        WaContactEnsure.ensure(act, customerName, phone);

        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("image/jpeg");
        send.putExtra(Intent.EXTRA_STREAM, uri);
        send.putExtra(Intent.EXTRA_TEXT, text);       // jadi caption foto
        send.putExtra("jid", jid);                    // ← lompati pemilih kontak, buka chat ini
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        String wa = pickWaPackage(act);
        if (wa != null) {
            List<ComponentName> candidates = new ArrayList<>();
            for (String cls : new String[]{
                    "com.whatsapp.contact.ui.picker.ExternalShareAlias",
                    "com.whatsapp.contact.picker.ContactPicker"}) {
                try {
                    ComponentName cn = new ComponentName(wa, cls);
                    act.getPackageManager().getActivityInfo(cn, 0);
                    candidates.add(cn);
                } catch (Exception ignored) {
                    // versi WhatsApp ini tak punya kelas itu → lewati
                }
            }
            Intent probe = new Intent(Intent.ACTION_SEND).setType("image/jpeg").setPackage(wa);
            ResolveInfo info = act.getPackageManager().resolveActivity(probe, 0);
            if (info != null && info.activityInfo != null) {
                ComponentName cn = new ComponentName(info.activityInfo.packageName, info.activityInfo.name);
                if (!candidates.contains(cn)) candidates.add(cn);
            }
            for (ComponentName target : candidates) {
                Intent direct = new Intent(send).setComponent(target);
                direct.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    act.startActivity(direct);
                    return true;
                } catch (Exception ignored) {
                    // komponen ada tapi menolak (mis. tak exported) → coba kandidat berikutnya
                }
            }
            send.setPackage(wa);
            send.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                act.startActivity(send);
                return true;
            } catch (Exception ignored) {
                send.setPackage(null);
            }
        }
        try {
            act.startActivity(Intent.createChooser(send, "Kirim ke pelanggan"));
            return true;
        } catch (Exception e) {
            Toast.makeText(act, "Tidak dapat membuka WhatsApp", Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    /** Buka chat pelanggan dgn pesan siap-kirim (tanpa lampiran) lewat wa.me. */
    public static boolean openTextOnly(Activity act, String phone, String text) {
        String d = digits(phone);
        if (d.startsWith("0")) d = "62" + d.substring(1);
        else if (d.startsWith("8")) d = "62" + d;
        try {
            Intent i = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://wa.me/" + d + "?text=" + Uri.encode(text)));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            act.startActivity(i);
            return true;
        } catch (Exception e) {
            Toast.makeText(act, "Tidak dapat membuka WhatsApp", Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    /** Nomor bisa dipakai WhatsApp? (minimal 9 digit) */
    public static boolean hasUsablePhone(String phone) {
        return digits(phone).length() >= 9;
    }

    public static String pickWaPackage(Activity act) {
        PackageManager pm = act.getPackageManager();
        try { pm.getPackageInfo("com.whatsapp", 0); return "com.whatsapp"; } catch (Exception ignored) {}
        try { pm.getPackageInfo("com.whatsapp.w4b", 0); return "com.whatsapp.w4b"; } catch (Exception ignored) {}
        return null;
    }

    /** Nomor → JID "62XXXXXXXXXX@s.whatsapp.net"; null bila terlalu pendek. */
    public static String waJid(String phone) {
        String d = digits(phone);
        if (d.startsWith("0")) d = "62" + d.substring(1);
        else if (d.startsWith("8")) d = "62" + d;
        return d.length() >= 9 ? d + "@s.whatsapp.net" : null;
    }

    private static String digits(String s) {
        return s == null ? "" : s.replaceAll("[^0-9]", "");
    }
}
