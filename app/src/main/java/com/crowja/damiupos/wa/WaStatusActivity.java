package com.crowja.damiupos.wa;

import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.crowja.damiupos.R;
import com.google.android.material.button.MaterialButton;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 🟢 Status WhatsApp (Admin) — cermin halaman web /wa-status: daftar postingan Status (yang terbuka dulu, lalu
 * riwayat), hasil per akun, dan tombol yang sama dengan web: ✏️ Ubah, Posting sekarang, Batalkan, 🔒 Tutup order
 * hari ini, Coba lagi yang gagal, Hapus. Semua lewat {@code /api/admin/wa/status*} (staf Admin aktif cabang ini);
 * aksi yang menyentuh banyak penonton / merusak selalu dikonfirmasi dulu.
 */
public class WaStatusActivity extends AppCompatActivity {

    private static final int REQ_EDIT = 41;

    private String staffUuid;
    private TextView tvSummary, tvEmpty, tvFlash;
    private final List<JSONObject> rows = new ArrayList<>();
    private int page = 0, lastPage = 1, total = 0;
    private boolean loading;
    private final Adapter adapter = new Adapter();
    @Nullable private AlertDialog progress;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        staffUuid = WaAdmin.gate(this);
        if (staffUuid == null) return;
        setContentView(R.layout.activity_wa_status);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        tvSummary = findViewById(R.id.tvSummary);
        tvEmpty = findViewById(R.id.tvEmpty);
        tvFlash = findViewById(R.id.tvFlash);
        tvFlash.setOnClickListener(v -> v.setVisibility(View.GONE));
        RecyclerView rv = findViewById(R.id.rvStatus);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(adapter);
        findViewById(R.id.fabAdd).setOnClickListener(v ->
                startActivityForResult(new Intent(this, WaStatusEditActivity.class), REQ_EDIT));
        load(true);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 1, 0, "🔄 Muat ulang").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == 1) {
            load(true);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_EDIT) return;
        String msg = data != null ? data.getStringExtra(WaStatusEditActivity.EXTRA_MESSAGE) : null;
        if (msg != null) flash(msg);
        load(true);
    }

    @Override
    protected void onDestroy() {
        if (progress != null) progress.dismiss();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ data

    private void load(boolean reset) {
        if (loading) return;
        loading = true;
        final int want = reset ? 1 : page + 1;
        if (reset) tvSummary.setText("Memuat…");
        adapter.notifyDataSetChanged();
        new Thread(() -> {
            JSONObject r = null;
            String err = null;
            try {
                Map<String, String> q = new HashMap<>();
                q.put("page", String.valueOf(want));
                r = WaAdmin.api(this).adminWaGet("status", q, staffUuid);
            } catch (Exception e) {
                err = WaAdmin.errorMessage(e);
            }
            final JSONObject fr = r;
            final String fe = err;
            runOnUiThread(() -> {
                loading = false;
                if (isFinishing() || isDestroyed()) return;
                if (fr == null) {
                    tvSummary.setText(fe + " Ketuk ⋮ → Muat ulang.");
                    if (rows.isEmpty()) {
                        tvEmpty.setText("Gagal memuat daftar Status.\n" + fe);
                        tvEmpty.setVisibility(View.VISIBLE);
                    }
                    adapter.notifyDataSetChanged();
                    return;
                }
                if (want == 1) rows.clear();
                JSONArray arr = fr.optJSONArray("posts");
                for (int i = 0; arr != null && i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o != null) rows.add(o);
                }
                page = fr.optInt("page", want);
                lastPage = Math.max(1, fr.optInt("last_page", 1));
                total = fr.optInt("total", rows.size());
                tvSummary.setText(total == 0 ? "Belum ada postingan."
                        : total + " postingan · Status tampil ke kontak pribadi yang pernah berinteraksi dengan akun itu (grup tidak ikut).");
                tvEmpty.setText("Belum ada postingan Status.\nKetuk \"Buat Status\" untuk memasang Status pertama.");
                tvEmpty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
                adapter.notifyDataSetChanged();
            });
        }).start();
    }

    private void flash(String msg) {
        tvFlash.setText(msg + "  ✕");
        tvFlash.setVisibility(View.VISIBLE);
    }

    // ------------------------------------------------------------------ aksi

    private void confirm(String title, String message, String yes, Runnable go) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(message)
                .setPositiveButton(yes, (d, w) -> go.run())
                .setNegativeButton("Batal", null)
                .show();
    }

    /** POST aksi satu tombol lalu muat ulang daftar; pesan server tampil di banner. */
    private void act(long id, String action, @Nullable JSONObject body, String busyText) {
        showProgress(busyText);
        new Thread(() -> {
            JSONObject r = null;
            String err = null;
            try {
                r = WaAdmin.api(this).adminWaPost("status/" + id + "/" + action, body != null ? body : new JSONObject(), staffUuid);
            } catch (Exception e) {
                err = WaAdmin.errorMessage(e);
            }
            final String msg = r != null ? WaAdmin.message(r, "Selesai.") : err;
            runOnUiThread(() -> {
                hideProgress();
                if (isFinishing() || isDestroyed()) return;
                flash(msg);
                load(true);
            });
        }).start();
    }

    private void showProgress(String text) {
        hideProgress();
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        int p = WaStatusUi.dp(this, 20);
        l.setPadding(p, p, p, p);
        l.addView(new ProgressBar(this));
        TextView t = WaStatusUi.text(this, text, 15, WaStatusUi.TEXT, false);
        t.setPadding(WaStatusUi.dp(this, 16), 0, 0, 0);
        l.addView(t);
        progress = new AlertDialog.Builder(this).setView(l).setCancelable(false).show();
    }

    private void hideProgress() {
        if (progress != null) {
            progress.dismiss();
            progress = null;
        }
    }

    private void askPost(JSONObject p) {
        int n = p.optJSONArray("accounts") != null ? p.optJSONArray("accounts").length() : 0;
        confirm("Posting sekarang", WaStatusRules.postNowConfirm(p.optBoolean("post_will_close"), p.optBoolean("closes_orders"), n),
                "Posting", () -> act(p.optLong("id"), "post", null, "Memposting Status…"));
    }

    private void askCancel(JSONObject p) {
        confirm("Batalkan Status", "Batalkan Status \"" + p.optString("name") + "\"? Status ini tidak akan diposting.",
                "Ya, batalkan", () -> act(p.optLong("id"), "cancel", null, "Membatalkan…"));
    }

    private void askRetry(JSONObject p) {
        confirm("Coba lagi yang gagal", p.optBoolean("retry_will_close")
                        ? "Bila berhasil tayang, order hari ini ditutup. Coba lagi sekarang?"
                        : "Posting ulang Status ini ke akun yang gagal sekarang?",
                "Coba lagi", () -> act(p.optLong("id"), "retry", null, "Memposting ulang…"));
    }

    private void askDelete(JSONObject p) {
        confirm("Hapus Status", "Hapus \"" + p.optString("name") + "\" dari riwayat? Status yang sudah tayang di WhatsApp tidak ikut terhapus.",
                "Hapus", () -> act(p.optLong("id"), "delete", null, "Menghapus…"));
    }

    /** Satu dialog: konfirmasi + catatan untuk pelanggan (tampil di halaman order & ke agen AI). Batal = tak ada yang ditutup. */
    private void askCloseOrders(JSONObject p) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        int pad = WaStatusUi.dp(this, 20);
        l.setPadding(pad, WaStatusUi.dp(this, 8), pad, 0);
        l.addView(WaStatusUi.text(this, "Order baru dijadwalkan untuk besok; status kembali OPEN otomatis pada jam Open Order besok "
                + "(bisa dibuka lagi di halaman Status Order).\n\nCatatan untuk pelanggan (opsional, maks 120 huruf — tampil di "
                + "halaman order & ke agen AI):", 14, WaStatusUi.TEXT, false));
        EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        et.setFilters(new InputFilter[]{new InputFilter.LengthFilter(120)});
        et.setTextColor(WaStatusUi.TEXT);
        et.setHintTextColor(0xFF555555);
        et.setHint("mis. Hari ini tutup lebih awal, order dikirim besok");
        et.setText(p.optString("close_note", ""));
        l.addView(et, WaStatusUi.full(WaStatusUi.dp(this, 8)));
        new AlertDialog.Builder(this).setTitle("🔒 Tutup order HARI INI karena Status ini?")
                .setView(l)
                .setPositiveButton("Tutup order", (d, w) -> {
                    JSONObject b = new JSONObject();
                    try {
                        b.put("note", et.getText().toString().trim());
                    } catch (Exception ignored) {
                        // tak terjadi
                    }
                    act(p.optLong("id"), "close-orders", b, "Menutup order hari ini…");
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    // ------------------------------------------------------------------ tampilan

    private View card(JSONObject p) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        int pad = WaStatusUi.dp(this, 14);
        c.setPadding(pad, pad, pad, pad);
        c.setBackground(WaStatusUi.box(0xFFFFFFFF, 0, 12, this));

        WaStatusFlow meta = new WaStatusFlow(this, WaStatusUi.dp(this, 8), WaStatusUi.dp(this, 4));
        String status = p.optString("status");
        int[] col = WaStatusUi.statusColors(status);
        addChip(meta, WaStatusUi.badge(this, p.optString("status_label"), col[0], col[1]));
        addChip(meta, WaStatusUi.text(this, WaStatusUi.sourceIcon(p.optString("source_type")) + " " + p.optString("source_label"),
                12, WaStatusUi.TEXT_2, false));
        String sched = WaStatusUi.str(p, "scheduled_label");
        String posted = WaStatusUi.str(p, "posted_label");
        if (sched != null) addChip(meta, WaStatusUi.text(this, "🕒 " + sched, 12, WaStatusUi.TEXT_2, false));
        else if (posted != null) addChip(meta, WaStatusUi.text(this, "✓ " + posted, 12, WaStatusUi.TEXT_2, false));
        if (p.optInt("repeat_days") > 0) {
            int runs = p.optInt("run_count");
            addChip(meta, WaStatusUi.text(this, "🔁 tiap " + p.optInt("repeat_days") + " hari" + (runs > 0 ? " · " + runs + "× tayang" : ""),
                    12, WaStatusUi.BLUE, false));
        }
        String lastRun = WaStatusUi.str(p, "last_run_label");
        if (lastRun != null) addChip(meta, WaStatusUi.text(this, "terakhir: " + lastRun, 12, WaStatusUi.AMBER, false));
        if (p.optBoolean("closes_orders")) addChip(meta, WaStatusUi.badge(this, "🔒 Tutup order", WaStatusUi.RED, WaStatusUi.RED_BG));
        if (p.optBoolean("closing_now")) addChip(meta, WaStatusUi.badge(this, "🔒 Order hari ini DITUTUP", 0xFFFFFFFF, WaStatusUi.RED_SOLID));
        String creator = WaStatusUi.str(p, "creator");
        if (creator != null) addChip(meta, WaStatusUi.text(this, "👤 " + creator, 12, WaStatusUi.TEXT_2, false));
        c.addView(meta);

        TextView name = WaStatusUi.text(this, p.optString("name"), 15, WaStatusUi.TEXT, true);
        c.addView(name, WaStatusUi.full(WaStatusUi.dp(this, 6)));
        String title = WaStatusUi.str(p, "title");
        if (title != null) c.addView(WaStatusUi.text(this, title, 13, WaStatusUi.TEXT_2, false));

        JSONArray accs = p.optJSONArray("accounts");
        for (int i = 0; accs != null && i < accs.length(); i++) {
            JSONObject a = accs.optJSONObject(i);
            if (a == null) continue;
            String dev = a.optString("device", "");
            StringBuilder sb = new StringBuilder("📱 ").append(a.optString("account"));
            if (!dev.isEmpty()) sb.append(" · ").append(dev);
            String state = a.optString("state");
            int color;
            if ("ok".equals(state)) {
                sb.append("  ✓ terposting");
                if (!a.isNull("audience")) sb.append(" · ").append(a.optInt("audience")).append(" penonton");
                if (!a.isNull("excluded")) sb.append(" · ").append(a.optInt("excluded")).append(" dikecualikan (berhenti langganan)");
                color = WaStatusUi.GREEN;
            } else if ("failed".equals(state)) {
                sb.append("  ✗ ").append(a.optString("error", "gagal"));
                color = WaStatusUi.RED;
            } else {
                sb.append("  — menunggu");
                color = WaStatusUi.TEXT_2;
            }
            c.addView(WaStatusUi.text(this, sb.toString(), 13, color, false), WaStatusUi.full(WaStatusUi.dp(this, 3)));
        }

        WaStatusFlow btns = new WaStatusFlow(this, WaStatusUi.dp(this, 8), WaStatusUi.dp(this, 6));
        if (p.optBoolean("can_edit")) {
            addButton(btns, "✏️ Ubah", WaStatusUi.TEXT, v -> {
                Intent i = new Intent(this, WaStatusEditActivity.class);
                i.putExtra(WaStatusEditActivity.EXTRA_ID, p.optLong("id"));
                startActivityForResult(i, REQ_EDIT);
            });
        }
        if (p.optBoolean("can_post")) addButton(btns, "Posting sekarang", WaStatusUi.GREEN, v -> askPost(p));
        if (p.optBoolean("can_cancel")) addButton(btns, "Batalkan", WaStatusUi.TEXT_2, v -> askCancel(p));
        if (p.optBoolean("can_close_orders")) addButton(btns, "🔒 Tutup order hari ini", WaStatusUi.RED, v -> askCloseOrders(p));
        if (p.optBoolean("can_retry")) addButton(btns, "Coba lagi yang gagal", WaStatusUi.AMBER, v -> askRetry(p));
        if (p.optBoolean("can_delete")) addButton(btns, "Hapus", WaStatusUi.TEXT_2, v -> askDelete(p));
        if ("posting".equals(status)) {
            btns.addView(WaStatusUi.text(this, "Sedang diposting — muat ulang sebentar lagi.", 12, WaStatusUi.AMBER, false));
        }
        c.addView(btns, WaStatusUi.full(WaStatusUi.dp(this, 8)));
        return c;
    }

    private void addChip(WaStatusFlow f, View v) {
        f.addView(v, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void addButton(WaStatusFlow f, String label, int color, View.OnClickListener l) {
        MaterialButton b = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setTextColor(color);
        b.setStrokeColor(android.content.res.ColorStateList.valueOf(0xFF90A4AE));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setInsetTop(0);
        b.setInsetBottom(0);
        b.setPadding(WaStatusUi.dp(this, 12), 0, WaStatusUi.dp(this, 12), 0);
        b.setOnClickListener(l);
        f.addView(b, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, WaStatusUi.dp(this, 40)));
    }

    private class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private static final int ROW = 0, MORE = 1;

        @Override
        public int getItemViewType(int position) {
            return position < rows.size() ? ROW : MORE;
        }

        @Override
        public int getItemCount() {
            return rows.size() + (page < lastPage && !rows.isEmpty() ? 1 : 0);
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LinearLayout holder = new LinearLayout(parent.getContext());
            holder.setOrientation(LinearLayout.VERTICAL);
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = WaStatusUi.dp(WaStatusActivity.this, 10);
            holder.setLayoutParams(lp);
            return new RecyclerView.ViewHolder(holder) { };
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int position) {
            LinearLayout holder = (LinearLayout) h.itemView;
            holder.removeAllViews();
            if (getItemViewType(position) == ROW) {
                holder.addView(card(rows.get(position)));
                return;
            }
            MaterialButton more = new MaterialButton(WaStatusActivity.this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
            more.setAllCaps(false);
            more.setTextColor(WaStatusUi.BLUE);
            more.setText(loading ? "Memuat…" : "Muat lebih banyak (" + rows.size() + " dari " + total + ")");
            more.setTypeface(more.getTypeface(), Typeface.BOLD);
            more.setEnabled(!loading);
            more.setOnClickListener(v -> load(false));
            holder.addView(more);
        }
    }
}
