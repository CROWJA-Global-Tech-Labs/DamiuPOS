package com.crowja.damiupos.util;

import android.content.Context;

import androidx.annotation.ColorInt;
import androidx.core.content.ContextCompat;

import com.crowja.damiupos.R;
import com.google.android.material.color.MaterialColors;

/**
 * Warna merek dibaca dari TEMA context (bukan @color langsung), supaya layar lama ikut
 * "tampilan baru" admin (ThemeOverlay.DAMIUPOS.Gojek). Di Theme.DAMIUPOS colorPrimary = @color/primary
 * (#1565C0) dan colorPrimaryDark = @color/primary_dark (#0D47A1), jadi untuk non-admin hasilnya
 * identik dengan kode lama.
 *
 * <p>Context WAJIB activity (atau view/ContextThemeWrapper di atasnya) — context aplikasi/service tak
 * membawa overlay. Jangan dipakai untuk keluaran ke pelanggan (struk, PDF, gambar WA) atau notifikasi:
 * di sana warna biru harus tetap.</p>
 */
public final class ThemeColors {

    private ThemeColors() {}

    /** ?attr/colorPrimary (non-admin: #1565C0). */
    @ColorInt
    public static int primary(Context themed) {
        return MaterialColors.getColor(themed, androidx.appcompat.R.attr.colorPrimary,
                ContextCompat.getColor(themed, R.color.primary));
    }

    /** ?attr/colorPrimaryDark (non-admin: #0D47A1). */
    @ColorInt
    public static int primaryDark(Context themed) {
        return MaterialColors.getColor(themed, androidx.appcompat.R.attr.colorPrimaryDark,
                ContextCompat.getColor(themed, R.color.primary_dark));
    }
}
