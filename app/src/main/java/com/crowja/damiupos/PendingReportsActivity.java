package com.crowja.damiupos;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.button.MaterialButton;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Layar admin: daftar laporan (shift / rekap bulanan / rekap pekanan) yang
 * GAGAL terkirim. Tiap baris bisa di-"Resend"; ada juga "Resend Semua".
 * Laporan ini juga otomatis dicoba ulang setiap kali ada user login.
 */
public class PendingReportsActivity extends AppCompatActivity {

    private View llReports;
    private TextView tvEmpty;
    private MaterialButton btnResendAll;

    private final SimpleDateFormat inFmt =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
    private final SimpleDateFormat outFmt =
            new SimpleDateFormat("d MMM yyyy, HH:mm", new Locale("id", "ID"));

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pending_reports);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        llReports = findViewById(R.id.llReports);
        tvEmpty = findViewById(R.id.tvEmpty);
        btnResendAll = findViewById(R.id.btnResendAll);

        btnResendAll.setOnClickListener(v -> {
            btnResendAll.setEnabled(false);
            btnResendAll.setText("Mengirim ulang…");
            ShiftEmailSender.resendAllAsync(this, (sent, remaining) -> {
                Toast.makeText(this, sent + " terkirim, " + remaining + " masih gagal",
                        Toast.LENGTH_LONG).show();
                load();
            });
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    private void load() {
        android.widget.LinearLayout container = (android.widget.LinearLayout) llReports;
        container.removeAllViews();
        List<ShiftEmailSender.PendingInfo> items = ShiftEmailSender.listPending(this);

        boolean empty = items.isEmpty();
        tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        btnResendAll.setEnabled(!empty);
        btnResendAll.setText("Resend Semua");

        LayoutInflater inf = LayoutInflater.from(this);
        for (ShiftEmailSender.PendingInfo p : items) {
            View row = inf.inflate(R.layout.item_pending_report, container, false);
            ((TextView) row.findViewById(R.id.tvType)).setText(p.type);
            ((TextView) row.findViewById(R.id.tvSubject)).setText(p.subject);
            ((TextView) row.findViewById(R.id.tvDate)).setText(formatDate(p.createdAt));
            MaterialButton btn = row.findViewById(R.id.btnResend);
            final String fileName = p.fileName;
            btn.setOnClickListener(v -> {
                btn.setEnabled(false);
                btn.setText("…");
                ShiftEmailSender.resendOneAsync(this, fileName, (success, error) -> {
                    Toast.makeText(this,
                            success ? "Laporan terkirim" : "Masih gagal — coba lagi nanti",
                            Toast.LENGTH_SHORT).show();
                    load();
                });
            });
            container.addView(row);
        }
    }

    private String formatDate(String iso) {
        if (iso == null || iso.isEmpty()) return "";
        try {
            Date d = inFmt.parse(iso);
            return d != null ? outFmt.format(d) : iso;
        } catch (Exception e) {
            return iso;
        }
    }
}
