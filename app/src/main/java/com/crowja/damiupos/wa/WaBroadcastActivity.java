package com.crowja.damiupos.wa;

import android.os.Bundle;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

/** Broadcast WhatsApp — cermin halaman web (sedang dibangun). */
public class WaBroadcastActivity extends AppCompatActivity {

    private String staffUuid;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        staffUuid = WaAdmin.gate(this);
        if (staffUuid == null) return;
        setTitle("Broadcast WhatsApp");
        TextView tv = new TextView(this);
        tv.setText("Broadcast WhatsApp");
        setContentView(tv);
    }
}
