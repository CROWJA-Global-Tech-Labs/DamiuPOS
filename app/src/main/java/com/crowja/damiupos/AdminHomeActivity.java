package com.crowja.damiupos;

import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import com.crowja.damiupos.util.GojekUi;

/**
 * Beranda "Tampilan Baru (gaya Gojek)" khusus sesi admin. STUB fondasi — implementasi penuh menyusul.
 */
public class AdminHomeActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!GojekUi.isEnabled(this)) {
            startActivity(new Intent(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            finish();
        }
    }
}
