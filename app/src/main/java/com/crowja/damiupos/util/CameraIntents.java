package com.crowja.damiupos.util;

import android.content.Intent;

/**
 * Preferensi kamera untuk layar yang memakai KAMERA SISTEM ({@code MediaStore.ACTION_IMAGE_CAPTURE})
 * — foto rumah/lokasi pelanggan, nota pengeluaran, stok galon, kendala pengiriman, dan bukti selesai
 * pengiriman. Semuanya memotret OBJEK DI DEPAN petugas, jadi harus memakai kamera BELAKANG dengan
 * bidang selebar mungkin.
 *
 * <p><b>Batas yang jujur harus diketahui:</b> intent {@code ACTION_IMAGE_CAPTURE} menyerahkan kendali
 * penuh ke aplikasi kamera bawaan perangkat. Android TIDAK punya extra RESMI untuk memilih lensa
 * maupun menyetel zoom lewat intent ini. Extra di bawah adalah konvensi tak-resmi yang DIHORMATI
 * SEBAGIAN OEM (Samsung, Xiaomi, beberapa AOSP) dan DIABAIKAN oleh yang lain — jadi ini upaya
 * terbaik, bukan jaminan. Zoom sama sekali tak bisa dipaksa lewat jalur ini: aplikasi kamera
 * mengingat zoom terakhir penggunanya sendiri.
 *
 * <p>Kalau suatu layar BENAR-BENAR wajib kamera belakang + zoom paling lebar, satu-satunya cara yang
 * pasti adalah memotret DI DALAM aplikasi memakai CameraX — persis yang dilakukan
 * {@code PhotoCoordinateActivity} (back camera + {@code setLinearZoom(0f)}).
 *
 * <p>Absensi ({@code CameraCaptureActivity}) SENGAJA tidak memakai kelas ini: selfie absensi memang
 * harus kamera DEPAN.
 */
public final class CameraIntents {

    private CameraIntents() {}

    /**
     * Sematkan preferensi "kamera belakang" pada intent kamera sistem. Aman dipanggil untuk intent
     * apa pun: extra yang tak dikenal aplikasi kamera hanya diabaikan, tak pernah menggagalkan
     * pengambilan foto.
     */
    public static Intent preferBackCamera(Intent intent) {
        if (intent == null) return null;
        // Konvensi Samsung/AOSP: 0 = belakang, 1 = depan.
        intent.putExtra("android.intent.extras.CAMERA_FACING", 0);
        // Konvensi lama sebagian OEM (dibaca sebagai boolean maupun int di perangkat berbeda).
        intent.putExtra("android.intent.extras.LENS_FACING_BACK", 1);
        intent.putExtra("android.intent.extra.USE_FRONT_CAMERA", false);
        return intent;
    }
}
