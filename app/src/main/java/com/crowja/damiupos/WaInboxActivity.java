package com.crowja.damiupos;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.crowja.damiupos.sync.WaInboxApi;
import com.crowja.damiupos.sync.WaInboxCache;
import com.crowja.damiupos.util.Ts;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * "💬 Chat WhatsApp" (Admin / Marketing / SPV): inbox semua akun WA cabang lewat FREZ WA Bridge.
 * Chip akun (dengan badge pesan baru) → daftar percakapan (jendela waktu default 3 hari, bisa 7/30
 * hari / semua waktu / kustom) → {@link WaChatActivity}. Percakapan dari pelanggan yang order-nya
 * ditandai komplain diberi label 😠 dan bisa difilter. Data dari {@link WaInboxApi}; di-poll tiap
 * {@link #POLL_MS} HANYA selama layar aktif.
 */
public class WaInboxActivity extends AppCompatActivity {

    private static final long POLL_MS = 20_000L;
    private static final String[] PERIODS = {"3 hari terakhir (bawaan)", "7 hari terakhir", "30 hari terakhir",
            "Semua waktu", "Kustom…"};
    private static final int[] PERIOD_DAYS = {3, 7, 30, 0, -1};

    private WaInboxApi api;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable poll = this::refresh;
    private boolean resumed;
    private boolean loading;
    private boolean prefetched;

    private LinearLayout accountChips;
    private RecyclerView rv;
    private ProgressBar progress;
    private TextView tvEmpty, tvStatus;
    private ConvAdapter adapter;

    private final List<JSONObject> accounts = new ArrayList<>();
    private final List<Conv> convs = new ArrayList<>();
    private String account;
    private int days = 3;
    private boolean complaintOnly;
    private String query = "";
    /** Naik tiap ganti akun/jendela — jawaban lama (gen beda) dibuang. */
    private int gen;

    static final class Conv {
        String jid, title, phone, last, lastType, lastDir, lastAt;
        int unread;
        boolean complaint;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_wa_inbox);
        api = new WaInboxApi(this);

        Toolbar tb = findViewById(R.id.toolbar);
        setSupportActionBar(tb);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayShowTitleEnabled(false);
        tb.setTitle("💬 Chat WhatsApp");
        tb.setNavigationOnClickListener(v -> finish());

        accountChips = findViewById(R.id.accountChips);
        rv = findViewById(R.id.rvConversations);
        progress = findViewById(R.id.progress);
        tvEmpty = findViewById(R.id.tvEmpty);
        tvStatus = findViewById(R.id.tvStatus);
        adapter = new ConvAdapter();
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(adapter);

        Spinner sp = findViewById(R.id.spPeriod);
        sp.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, PERIODS));
        sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            private boolean first = true;

            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (first) { first = false; return; }
                if (PERIOD_DAYS[pos] == -1) { askCustomDays(sp); return; }
                days = PERIOD_DAYS[pos];
                reloadConversations();
            }

            @Override public void onNothingSelected(AdapterView<?> p) {}
        });

        CheckBox cb = findViewById(R.id.cbComplaintOnly);
        cb.setOnCheckedChangeListener((v, on) -> { complaintOnly = on; render(); });
        // Buka layar → isi dulu dari cache (akun + percakapan terakhir), jaringan menyusul di onResume.
        try {
            String ca = WaInboxCache.get(this, WaInboxCache.accountsKey());
            if (ca != null) {
                JSONArray arr = new JSONArray(ca);
                for (int i = 0; i < arr.length(); i++) accounts.add(arr.optJSONObject(i));
                if (account == null && arr.length() > 0) account = arr.optJSONObject(0).optString("account");
                buildChips();
                showCachedConversations();
                render();
            }
        } catch (Exception ignored) {
            // cache rusak — abaikan, layar terisi dari jaringan
        }
        ((EditText) findViewById(R.id.etSearch)).addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int c, int d) {}
            @Override public void onTextChanged(CharSequence s, int a, int c, int d) {}
            @Override public void afterTextChanged(Editable s) { query = s.toString().trim().toLowerCase(Locale.ROOT); render(); }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        refresh();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        handler.removeCallbacks(poll);
    }

    private void askCustomDays(Spinner sp) {
        EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setHint("Jumlah hari, mis. 14");
        new AlertDialog.Builder(this).setTitle("Jendela waktu kustom").setView(et)
                .setPositiveButton("Terapkan", (d, w) -> {
                    int n = 0;
                    try { n = Integer.parseInt(et.getText().toString().trim()); } catch (Exception ignored) {}
                    if (n > 0) {
                        days = Math.min(n, 3650);
                        PERIODS[4] = "Kustom: " + days + " hari";
                        sp.setSelection(4);
                        ((ArrayAdapter<?>) sp.getAdapter()).notifyDataSetChanged();
                        reloadConversations();
                    }
                })
                .setNegativeButton("Batal", null).show();
    }

    // ------------------------------------------------------------------------------------ data

    private void refresh() { refresh(true); }

    /**
     * @param withAccounts false = hanya percakapan akun terpilih (ganti akun / jendela waktu): daftar
     *                     akun + badge tak perlu diambil ulang tiap kali. Hasil disimpan di
     *                     {@link WaInboxCache}; sesudah muatan pertama, akun lain diambil diam-diam
     *                     ({@link #prefetchOthers}) supaya berpindah akun langsung terisi.
     */
    private void refresh(boolean withAccounts) {
        handler.removeCallbacks(poll);
        if (loading || !resumed) return;
        loading = true;
        final int g = gen;
        final String acc = account;
        final int d = days;
        if (convs.isEmpty()) progress.setVisibility(View.VISIBLE);
        final android.content.Context app = getApplicationContext();
        new Thread(() -> {
            WaInboxApi.Res ra = withAccounts ? api.accounts() : null;
            WaInboxApi.Res rc = null;
            String pick = acc;
            if (ra != null && ra.ok()) {
                JSONArray arr = ra.body.optJSONArray("accounts");
                if (arr != null) WaInboxCache.put(app, WaInboxCache.accountsKey(), arr.toString());
                if (pick == null && arr != null && arr.length() > 0) pick = arr.optJSONObject(0).optString("account");
            }
            boolean accountsOk = ra == null || ra.ok();
            if (accountsOk && pick != null) {
                rc = api.conversations(pick, d);
                if (rc.ok()) {
                    JSONArray carr = rc.body.optJSONArray("conversations");
                    if (carr != null) WaInboxCache.put(app, WaInboxCache.convKey(pick, d), carr.toString());
                }
            }
            final String fpick = pick;
            final WaInboxApi.Res fc = rc;
            final WaInboxApi.Res fa = ra;
            runOnUiThread(() -> {
                loading = false;
                progress.setVisibility(View.GONE);
                if (isFinishing() || isDestroyed()) return;
                if (fa != null && !fa.ok()) {
                    showStatus(fa.error.isEmpty() ? "Gagal memuat akun WA." : fa.error);
                } else {
                    if (fa != null) {
                        accounts.clear();
                        JSONArray arr = fa.body.optJSONArray("accounts");
                        for (int i = 0; arr != null && i < arr.length(); i++) accounts.add(arr.optJSONObject(i));
                    }
                    if (account == null) account = fpick;
                    buildChips();
                    if (g == gen && fc != null) {
                        if (fc.ok()) { showStatus(null); applyConversations(fc.body.optJSONArray("conversations")); }
                        else showStatus(fc.error.isEmpty() ? "Gagal memuat percakapan." : fc.error);
                    } else if (fpick == null) {
                        showStatus("Belum ada akun WhatsApp yang tertaut ke cabang ini.");
                    }
                    if (fa != null && fa.ok() && !prefetched) { prefetched = true; prefetchOthers(); }
                }
                render();
                if (resumed) handler.postDelayed(poll, POLL_MS);
            });
        }, "wa-inbox-load").start();
    }

    /** Ambil percakapan akun-akun lain (satu per satu, di latar) ke cache — ganti akun jadi instan. */
    private void prefetchOthers() {
        final android.content.Context app = getApplicationContext();
        final int d = days;
        final List<String> names = new ArrayList<>();
        for (JSONObject a : accounts) {
            String n = a.optString("account");
            if (!n.equalsIgnoreCase(account)) names.add(n);
        }
        new Thread(() -> {
            for (String n : names) {
                WaInboxApi.Res r = api.conversations(n, d);
                if (r.ok()) {
                    JSONArray carr = r.body.optJSONArray("conversations");
                    if (carr != null) WaInboxCache.put(app, WaInboxCache.convKey(n, d), carr.toString());
                }
            }
        }, "wa-inbox-prefetch").start();
    }

    /** Isi layar dari cache (bila ada) TANPA jaringan. True bila ada yang ditampilkan. */
    private boolean showCachedConversations() {
        if (account == null) return false;
        String cached = WaInboxCache.get(this, WaInboxCache.convKey(account, days));
        if (cached == null) return false;
        try {
            applyConversations(new JSONArray(cached));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void reloadConversations() {
        gen++;
        convs.clear();
        prefetched = false;
        // Stale-while-revalidate: cache dulu (kalau ada), jaringan menyusul di latar.
        boolean hadCache = showCachedConversations();
        render();
        loading = false;
        refresh(false);
        if (!hadCache) progress.setVisibility(View.VISIBLE);
    }

    private void applyConversations(JSONArray arr) {
        convs.clear();
        for (int i = 0; arr != null && i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            Conv c = new Conv();
            c.jid = o.optString("jid");
            c.phone = o.optString("phone");
            String cust = o.isNull("customer_name") ? "" : o.optString("customer_name", "");
            String nm = o.isNull("name") ? "" : o.optString("name", "");
            c.title = !cust.isEmpty() ? cust : !nm.isEmpty() ? nm : c.phone;
            JSONObject lm = o.optJSONObject("last_message");
            if (lm != null) {
                c.last = lm.optString("text");
                c.lastType = lm.optString("type", "text");
                c.lastDir = lm.optString("direction");
            }
            c.lastAt = o.optString("last_at");
            c.unread = o.optInt("unread");
            c.complaint = o.optBoolean("complaint");
            convs.add(c);
        }
    }

    private void showStatus(String s) {
        tvStatus.setVisibility(s == null ? View.GONE : View.VISIBLE);
        if (s != null) tvStatus.setText(s);
    }

    // ------------------------------------------------------------------------------------- UI

    private void buildChips() {
        accountChips.removeAllViews();
        float dp = getResources().getDisplayMetrics().density;
        for (JSONObject a : accounts) {
            final String name = a.optString("account");
            boolean sel = name.equalsIgnoreCase(account);
            int unread = a.optInt("unread");
            LinearLayout chip = new LinearLayout(this);
            chip.setOrientation(LinearLayout.HORIZONTAL);
            chip.setGravity(android.view.Gravity.CENTER_VERTICAL);
            chip.setPadding((int) (12 * dp), (int) (6 * dp), (int) (12 * dp), (int) (6 * dp));
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(20 * dp);
            bg.setColor(sel ? Color.parseColor("#075E54") : Color.WHITE);
            bg.setStroke((int) dp, Color.parseColor("#075E54"));
            chip.setBackground(bg);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd((int) (6 * dp));
            chip.setLayoutParams(lp);

            TextView tv = new TextView(this);
            String st = a.isNull("status") ? "" : a.optString("status", "");
            String label = a.optString("label", name);
            tv.setText(("open".equals(st) || st.isEmpty() ? "" : "⚠ ") + label);
            tv.setTextColor(sel ? Color.WHITE : Color.parseColor("#075E54"));
            tv.setTypeface(null, Typeface.BOLD);
            tv.setTextSize(13);
            chip.addView(tv);

            if (unread > 0) {
                TextView badge = new TextView(this);
                badge.setText(unread > 99 ? "99+" : String.valueOf(unread));
                badge.setTextColor(Color.WHITE);
                badge.setTextSize(11);
                badge.setTypeface(null, Typeface.BOLD);
                badge.setGravity(android.view.Gravity.CENTER);
                badge.setMinWidth((int) (20 * dp));
                badge.setPadding((int) (6 * dp), 0, (int) (6 * dp), 0);
                badge.setBackgroundResource(R.drawable.bg_wa_unread);
                LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                bl.setMarginStart((int) (6 * dp));
                badge.setLayoutParams(bl);
                chip.addView(badge);
            }
            chip.setOnClickListener(v -> {
                if (name.equalsIgnoreCase(account)) return;
                account = name;
                buildChips();
                reloadConversations();
            });
            accountChips.addView(chip);
        }
    }

    private void render() {
        List<Conv> shown = new ArrayList<>();
        for (Conv c : convs) {
            if (complaintOnly && !c.complaint) continue;
            if (!query.isEmpty() && !(c.title.toLowerCase(Locale.ROOT).contains(query) || c.phone.contains(query))) continue;
            shown.add(c);
        }
        adapter.set(shown);
        boolean empty = shown.isEmpty() && progress.getVisibility() != View.VISIBLE;
        tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        tvEmpty.setText(complaintOnly ? "Tidak ada percakapan komplain pada jendela waktu ini."
                : "Tidak ada percakapan pada jendela waktu ini.");
    }

    static String shortTime(String iso) {
        long ms = Ts.millis(iso);
        if (ms == Long.MAX_VALUE) return "";
        boolean today = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date(ms))
                .equals(new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date()));
        return new SimpleDateFormat(today ? "HH:mm" : "dd/MM/yy", Locale.US).format(new Date(ms));
    }

    static String previewOf(String type, String text) {
        String t = text == null ? "" : text;
        switch (type == null ? "text" : type) {
            case "image": return "📷 " + (t.isEmpty() ? "Foto" : t);
            case "video": return "🎥 " + (t.isEmpty() ? "Video" : t);
            case "audio": case "ptt": return "🎤 Pesan suara";
            case "document": return "📄 " + (t.isEmpty() ? "Dokumen" : t);
            case "sticker": return "Stiker";
            default: return t;
        }
    }

    private final class ConvAdapter extends RecyclerView.Adapter<ConvAdapter.VH> {
        private List<Conv> data = new ArrayList<>();

        void set(List<Conv> d) { data = d; notifyDataSetChanged(); }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup p, int t) {
            return new VH(LayoutInflater.from(p.getContext()).inflate(R.layout.item_wa_conversation, p, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int i) {
            Conv c = data.get(i);
            h.name.setText(c.title);
            h.avatar.setText(c.title.isEmpty() ? "?" : c.title.substring(0, 1).toUpperCase(Locale.ROOT));
            com.crowja.damiupos.util.WaAvatarLoader.into(WaInboxActivity.this, account, c.jid, h.photo);
            h.time.setText(shortTime(c.lastAt));
            h.last.setText(("out".equals(c.lastDir) ? "Anda: " : "") + previewOf(c.lastType, c.last));
            h.complaint.setVisibility(c.complaint ? View.VISIBLE : View.GONE);
            h.unread.setVisibility(c.unread > 0 ? View.VISIBLE : View.GONE);
            h.unread.setText(c.unread > 99 ? "99+" : String.valueOf(c.unread));
            h.name.setTextColor(c.unread > 0 ? Color.BLACK : Color.parseColor("#111B21"));
            h.itemView.setOnClickListener(v -> startActivity(new Intent(WaInboxActivity.this, WaChatActivity.class)
                    .putExtra(WaChatActivity.EXTRA_ACCOUNT, account)
                    .putExtra(WaChatActivity.EXTRA_JID, c.jid)
                    .putExtra(WaChatActivity.EXTRA_TITLE, c.title)
                    .putExtra(WaChatActivity.EXTRA_PHONE, c.phone)
                    .putExtra(WaChatActivity.EXTRA_COMPLAINT, c.complaint)));
        }

        @Override public int getItemCount() { return data.size(); }

        final class VH extends RecyclerView.ViewHolder {
            final TextView avatar, name, time, last, complaint, unread;
            final android.widget.ImageView photo;

            VH(View v) {
                super(v);
                avatar = v.findViewById(R.id.tvAvatar);
                photo = v.findViewById(R.id.ivAvatar);
                com.crowja.damiupos.util.WaAvatarLoader.makeRound(photo);
                name = v.findViewById(R.id.tvName);
                time = v.findViewById(R.id.tvTime);
                last = v.findViewById(R.id.tvLast);
                complaint = v.findViewById(R.id.tvComplaint);
                unread = v.findViewById(R.id.tvUnread);
            }
        }
    }
}
