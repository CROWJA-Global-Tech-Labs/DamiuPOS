package com.crowja.damiupos.wa;

import static com.crowja.damiupos.wa.WaAccountsUi.BLUE_FG;
import static com.crowja.damiupos.wa.WaAccountsUi.CARD_STROKE;
import static com.crowja.damiupos.wa.WaAccountsUi.GREEN_FG;
import static com.crowja.damiupos.wa.WaAccountsUi.RED_FG;
import static com.crowja.damiupos.wa.WaAccountsUi.TEXT;
import static com.crowja.damiupos.wa.WaAccountsUi.TEXT_2;
import static com.crowja.damiupos.wa.WaAccountsUi.nz;
import static com.crowja.damiupos.wa.WaAccountsUi.str;

import android.os.Bundle;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.crowja.damiupos.R;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * 🧪 Console Test satu akun WhatsApp — cermin popup "Console Test" di web, dalam bentuk layar chat:
 * pesan MASUK terbaru akun ini (gelembung putih, kiri; pengirim LID yang sudah dipetakan tampil sebagai nama
 * pelanggan) dan pesan uji yang dikirim dari layar ini (gelembung hijau, kanan). Kirim selalu
 * {@code kind=reply} lewat {@code POST /api/admin/wa/accounts/console/send}; inbox lewat
 * {@code GET …/console/inbox} (server men-cache 5 detik). Ketuk pesan masuk → nomornya jadi tujuan balasan.
 */
public class WaAccountsConsoleActivity extends AppCompatActivity {

    static final String EXTRA_ACCOUNT = "wa_account";
    static final String EXTRA_LABEL = "wa_label";

    private static final int IN = 0, OUT = 1;
    private static final Locale ID = new Locale("id", "ID");

    private String staffUuid;
    private String account;
    private RecyclerView rv;
    private ProgressBar pb;
    private TextView tvState;
    private EditText etTo, etText;
    private Button btnSend;
    private boolean loading, sending;
    private final List<JSONObject> inbox = new ArrayList<>();
    /** Pesan uji yang dikirim dari layar ini (hanya sesi ini — Bridge tak mengembalikan pesan keluar di inbox). */
    private final List<JSONObject> sent = new ArrayList<>();
    private final List<JSONObject> rows = new ArrayList<>();
    private final Adapter adapter = new Adapter();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        staffUuid = WaAdmin.gate(this);
        if (staffUuid == null) return;
        account = getIntent().getStringExtra(EXTRA_ACCOUNT);
        if (account == null || account.isEmpty()) {
            Toast.makeText(this, "Akun WhatsApp tidak dikenal.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        setContentView(R.layout.activity_wa_accounts_console);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        String label = getIntent().getStringExtra(EXTRA_LABEL);
        toolbar.setSubtitle((label != null ? label + " · " : "") + account);

        rv = findViewById(R.id.rvChat);
        LinearLayoutManager lm = new LinearLayoutManager(this);
        lm.setStackFromEnd(true);
        rv.setLayoutManager(lm);
        rv.setAdapter(adapter);
        pb = findViewById(R.id.pbLoading);
        tvState = findViewById(R.id.tvState);
        tvState.setOnClickListener(v -> loadInbox());
        etTo = findViewById(R.id.etTo);
        etText = findViewById(R.id.etText);
        btnSend = findViewById(R.id.btnSend);
        btnSend.setOnClickListener(v -> send());
        loadInbox();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 1, 0, "🔄 Muat inbox").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == 1) {
            loadInbox();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // ------------------------------------------------------------------ data

    private void loadInbox() {
        if (loading) return;
        loading = true;
        pb.setVisibility(View.VISIBLE);
        tvState.setVisibility(View.GONE);
        Map<String, String> q = new HashMap<>();
        q.put("account", account);
        WaAccountsUi.call(this, () -> WaAdmin.api(this).adminWaGet("accounts/console/inbox", q, staffUuid), r -> {
            loading = false;
            pb.setVisibility(View.GONE);
            inbox.clear();
            JSONArray arr = r.optJSONArray("messages");
            for (int i = 0; arr != null && i < arr.length(); i++) {
                JSONObject m = arr.optJSONObject(i);
                if (m != null) inbox.add(m);
            }
            render(inbox.isEmpty() && sent.isEmpty() ? "Belum ada pesan masuk." : null, false);
        }, msg -> {
            loading = false;
            pb.setVisibility(View.GONE);
            render("✕ " + msg + "\n\nKetuk untuk coba lagi.", true);
        });
    }

    /** Gabung inbox + pesan terkirim, urut waktu naik (terbaru di bawah, seperti aplikasi chat). */
    private void render(@Nullable String state, boolean error) {
        rows.clear();
        rows.addAll(inbox);
        rows.addAll(sent);
        Collections.sort(rows, (a, b) -> Long.compare(millis(a), millis(b)));
        adapter.notifyDataSetChanged();
        if (!rows.isEmpty()) rv.scrollToPosition(rows.size() - 1);
        if (state != null && rows.isEmpty()) {
            // Kosong / galat tanpa pesan apa pun → keadaan di tengah layar (galat: ketuk untuk coba lagi).
            tvState.setText(state);
            tvState.setTextColor(error ? RED_FG : TEXT_2);
            tvState.setVisibility(View.VISIBLE);
        } else {
            tvState.setVisibility(View.GONE);
            if (state != null && error) Toast.makeText(this, state, Toast.LENGTH_LONG).show();
        }
    }

    private void send() {
        if (sending) return;
        final String to = etTo.getText().toString().trim();
        final String text = etText.getText().toString();
        if (to.isEmpty() || text.trim().isEmpty()) {
            Toast.makeText(this, "Isi nomor tujuan dan isi pesan.", Toast.LENGTH_SHORT).show();
            return;
        }
        JSONObject b = new JSONObject();
        try {
            b.put("account", account);
            b.put("to", to);
            b.put("text", text);
        } catch (Exception ignored) {
        }
        sending = true;
        btnSend.setEnabled(false);
        btnSend.setText("Mengirim…");
        WaAccountsUi.call(this, () -> WaAdmin.api(this).adminWaPost("accounts/console/send", b, staffUuid), r -> {
            sending = false;
            btnSend.setEnabled(true);
            btnSend.setText("Kirim");
            sent.add(outgoing(to, text, true, null));
            etText.setText("");
            render(null, false);
        }, msg -> {
            sending = false;
            btnSend.setEnabled(true);
            btnSend.setText("Kirim");
            sent.add(outgoing(to, text, false, msg));
            render(null, false);
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        });
    }

    private static JSONObject outgoing(String to, String text, boolean ok, @Nullable String error) {
        JSONObject o = new JSONObject();
        try {
            o.put("_out", true);
            o.put("to", to);
            o.put("text", text);
            o.put("ok", ok);
            o.put("error", error != null ? error : JSONObject.NULL);
            o.put("_ms", System.currentTimeMillis());
        } catch (Exception ignored) {
        }
        return o;
    }

    /** Ketuk pesan masuk → balas nomornya. Pengirim LID (nomor tersamar) tak bisa dijadikan tujuan. */
    private void replyTo(JSONObject m) {
        String phone = nz(str(m, "phone"));
        if (phone.contains("@lid")) {
            Toast.makeText(this, "Pengirim ini memakai LID WhatsApp (nomor asli tersamar) — nomornya tidak bisa diisi otomatis.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        String digits = phone.replaceAll("\\D+", "");
        if (digits.isEmpty()) return;
        etTo.setText(digits);
        etText.requestFocus();
        Toast.makeText(this, "Balas ke " + digits, Toast.LENGTH_SHORT).show();
    }

    private static long millis(JSONObject m) {
        if (m.has("_ms")) return m.optLong("_ms");
        String ts = str(m, "timestamp");
        if (ts == null || ts.length() < 19) return 0;
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            Date d = f.parse(ts.substring(0, 19));
            return d != null ? d.getTime() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private static String when(long ms) {
        if (ms <= 0) return "";
        return new SimpleDateFormat("d MMM HH:mm", ID).format(new Date(ms));
    }

    // ------------------------------------------------------------------ gelembung

    private class Adapter extends RecyclerView.Adapter<Adapter.VH> {
        class VH extends RecyclerView.ViewHolder {
            final LinearLayout bubble;

            VH(FrameLayout v, LinearLayout bubble) {
                super(v);
                this.bubble = bubble;
            }
        }

        @Override
        public int getItemViewType(int position) {
            return rows.get(position).optBoolean("_out") ? OUT : IN;
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            AppCompatActivity c = WaAccountsConsoleActivity.this;
            FrameLayout row = new FrameLayout(c);
            row.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            int v = WaAccountsUi.dp(c, 3);
            row.setPadding(0, v, 0, v);
            LinearLayout bubble = new LinearLayout(c);
            bubble.setOrientation(LinearLayout.VERTICAL);
            int p = WaAccountsUi.dp(c, 9);
            bubble.setPadding(p, WaAccountsUi.dp(c, 6), p, WaAccountsUi.dp(c, 6));
            boolean out = viewType == OUT;
            bubble.setBackground(WaAccountsUi.rounded(c, out ? 0xFFDCF8C6 : 0xFFFFFFFF, out ? 0 : CARD_STROKE, 10));
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    out ? Gravity.END : Gravity.START);
            if (out) lp.leftMargin = WaAccountsUi.dp(c, 48);
            else lp.rightMargin = WaAccountsUi.dp(c, 48);
            row.addView(bubble, lp);
            return new VH(row, bubble);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            final JSONObject m = rows.get(position);
            final AppCompatActivity c = WaAccountsConsoleActivity.this;
            LinearLayout b = h.bubble;
            b.removeAllViews();
            boolean out = m.optBoolean("_out");
            if (out) {
                b.addView(WaAccountsUi.text(c, "Ke " + nz(str(m, "to")), 11.5f, TEXT_2, true));
                b.addView(WaAccountsUi.text(c, nz(str(m, "text")), 15f, TEXT, false));
                boolean ok = m.optBoolean("ok");
                String status = ok ? "✓ Terkirim · " + when(millis(m)) : "✕ Gagal: " + nz(str(m, "error"));
                // #1B5E20 / #B71C1C di atas #DCF8C6 ≥ 5:1
                TextView st = WaAccountsUi.text(c, status, 11.5f, ok ? GREEN_FG : RED_FG, false);
                st.setGravity(Gravity.END);
                b.addView(st);
                h.itemView.setOnClickListener(null);
                return;
            }
            // Siapa: nama pelanggan (LID terpetakan) → senderName → pushName → nomor (sama dengan web).
            String who = str(m, "customer_name");
            if (who == null) who = str(m, "senderName");
            if (who == null) who = str(m, "pushName");
            if (who == null) who = str(m, "phone");
            if (who == null) who = "—";
            b.addView(WaAccountsUi.text(c, who, 12.5f, BLUE_FG, true));
            String phone = str(m, "phone");
            if (str(m, "customer_name") != null && phone != null) {
                b.addView(WaAccountsUi.text(c, "LID: " + phone, 11f, TEXT_2, false));
            } else if (phone != null && !phone.equals(who)) {
                b.addView(WaAccountsUi.text(c, phone, 11f, TEXT_2, false));
            }
            String text = str(m, "text");
            if (text == null) text = m.has("media") && !m.isNull("media") ? "[media]" : "";
            b.addView(WaAccountsUi.text(c, text, 15f, TEXT, false));
            TextView t = WaAccountsUi.text(c, when(millis(m)), 11f, TEXT_2, false);
            t.setGravity(Gravity.END);
            b.addView(t);
            h.itemView.setOnClickListener(v -> replyTo(m));
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }
    }
}
