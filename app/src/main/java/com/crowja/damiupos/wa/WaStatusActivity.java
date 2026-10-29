package com.crowja.damiupos.wa;

import android.os.Bundle;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

/** Status WhatsApp — cermin halaman web (sedang dibangun). */
public class WaStatusActivity extends AppCompatActivity {

    private String staffUuid;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        staffUuid = WaAdmin.gate(this);
        if (staffUuid == null) return;
        setTitle("Status WhatsApp");
        TextView tv = new TextView(this);
        tv.setText("Status WhatsApp");
        setContentView(tv);
    }
}
