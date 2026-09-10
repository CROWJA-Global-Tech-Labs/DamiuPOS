package com.crowja.damiupos;

import android.net.Uri;

import androidx.core.content.FileProvider;

import java.util.Locale;

/**
 * FileProvider kustom yang memaksa MIME type benar berdasarkan ekstensi file.
 *
 * <p>FileProvider default menurunkan MIME dari {@code MimeTypeMap}; di sebagian
 * perangkat (mis. Samsung) ".xlsx" tidak ter-mapping → balik
 * {@code application/octet-stream}, sehingga WhatsApp menolak ("file yang Anda
 * pilih bukan dokumen"). Override ini memastikan dokumen (xlsx/pdf/zip) dikenali
 * sebagai dokumen oleh aplikasi penerima.
 */
public class DamiuFileProvider extends FileProvider {

    @Override
    public String getType(Uri uri) {
        String seg = uri != null ? uri.getLastPathSegment() : null;
        if (seg != null) {
            String n = seg.toLowerCase(Locale.US);
            if (n.endsWith(".xlsx"))
                return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            if (n.endsWith(".xls")) return "application/vnd.ms-excel";
            if (n.endsWith(".pdf")) return "application/pdf";
            if (n.endsWith(".zip")) return "application/zip";
            if (n.endsWith(".png")) return "image/png";
            if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        }
        return super.getType(uri);
    }
}
