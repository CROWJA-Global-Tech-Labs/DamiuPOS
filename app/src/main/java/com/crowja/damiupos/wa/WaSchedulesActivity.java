package com.crowja.damiupos.wa;

import android.os.Bundle;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

/** Broadcast Terjadwal — cermin halaman web (sedang dibangun). */
public class WaSchedulesActivity extends AppCompatActivity {

    private String staffUuid;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        staffUuid = WaAdmin.gate(this);
        if (staffUuid == null) return;
        setTitle("Broadcast Terjadwal");
        TextView tv = new TextView(this);
        tv.setText("Broadcast Terjadwal");
        setContentView(tv);
    }
}
