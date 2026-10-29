package com.crowja.damiupos.wa;

import android.os.Bundle;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

/** Akun WhatsApp — cermin halaman web (sedang dibangun). */
public class WaAccountsActivity extends AppCompatActivity {

    private String staffUuid;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        staffUuid = WaAdmin.gate(this);
        if (staffUuid == null) return;
        setTitle("Akun WhatsApp");
        TextView tv = new TextView(this);
        tv.setText("Akun WhatsApp");
        setContentView(tv);
    }
}
