package com.crowja.damiupos.wa;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.crowja.damiupos.R;
import com.google.android.material.chip.ChipGroup;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 🗓️ Broadcast Terjadwal — daftar broadcast WA terjadwal cabang (cermin /wa-schedules web): yang berjalan /
 * terjadwal dulu, lalu draf, lalu selesai/batal. Ketuk → detail; "Buat Broadcast" → form. Data selalu dari
 * server ({@code /api/admin/wa/schedules}, staf Admin aktif), 20 per halaman (gulir untuk memuat berikutnya).
 */
public class WaSchedulesActivity extends AppCompatActivity {

    private String staffUuid;
    private SwipeRefreshLayout swipe;
    private TextView tvSummary, tvEmpty;
    private final List<JSONObject> items = new ArrayList<>();
    private final Adapter adapter = new Adapter();
    private int page = 0;
    private boolean hasMore, loading, resumedOnce;
    private int total;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        staffUuid = WaAdmin.gate(this);
        if (staffUuid == null) return;
        setContentView(R.layout.activity_wa_schedules);
        Toolbar tb = findViewById(R.id.toolbar);
        setSupportActionBar(tb);
        tb.setNavigationOnClickListener(v -> finish());

        tvSummary = findViewById(R.id.tvSummary);
        tvEmpty = findViewById(R.id.tvEmpty);
        swipe = findViewById(R.id.swipe);
        swipe.setOnRefreshListener(() -> load(true));
        RecyclerView rv = findViewById(R.id.rvSchedules);
        LinearLayoutManager lm = new LinearLayoutManager(this);
        rv.setLayoutManager(lm);
        rv.setAdapter(adapter);
        rv.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView r, int dx, int dy) {
                if (dy > 0 && hasMore && !loading && lm.findLastVisibleItemPosition() >= items.size() - 3) load(false);
            }
        });
        findViewById(R.id.fabAdd).setOnClickListener(v -> startActivity(new Intent(this, WaScheduleFormActivity.class)));
        load(true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Kembali dari detail/form: muat ulang supaya status & hitungan terkini.
        if (resumedOnce && staffUuid != null) load(true);
        resumedOnce = true;
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

    private void load(boolean reset) {
        if (loading) return;
        loading = true;
        final int want = reset ? 1 : page + 1;
        if (reset) {
            swipe.setRefreshing(true);
            if (items.isEmpty()) tvSummary.setText("Memuat…");
        }
        Map<String, String> q = new HashMap<>();
        q.put("page", String.valueOf(want));
        WaScheduleUi.call(this, () -> WaAdmin.api(this).adminWaGet("schedules", q, staffUuid), r -> {
            loading = false;
            swipe.setRefreshing(false);
            if (want == 1) items.clear();
            JSONArray arr = r.optJSONArray("items");
            for (int i = 0; arr != null && i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) items.add(o);
            }
            page = r.optInt("page", want);
            hasMore = r.optBoolean("has_more");
            total = r.optInt("total", items.size());
            adapter.notifyDataSetChanged();
            tvEmpty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
            tvEmpty.setText("Belum ada broadcast terjadwal.\nKetuk \"Buat Broadcast\" untuk membuat.");
            tvSummary.setText(total + " broadcast · zona waktu " + r.optString("timezone", "Asia/Jakarta")
                    + (hasMore ? " · gulir untuk memuat lagi" : ""));
        }, (msg, code) -> {
            loading = false;
            swipe.setRefreshing(false);
            tvSummary.setText(msg);
            if (items.isEmpty()) {
                tvEmpty.setVisibility(View.VISIBLE);
                tvEmpty.setText(msg + "\nTarik ke bawah untuk mencoba lagi.");
            }
        });
    }

    // ------------------------------------------------------------------ daftar

    private final class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LinearLayout card = new LinearLayout(parent.getContext());
            card.setOrientation(LinearLayout.VERTICAL);
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            int m = WaScheduleUi.dp(parent.getContext(), 10);
            lp.setMargins(m, m / 2, m, m / 2);
            card.setLayoutParams(lp);
            return new RecyclerView.ViewHolder(card) { };
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int pos) {
            bindRow((LinearLayout) h.itemView, items.get(pos));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }

    private void bindRow(LinearLayout card, JSONObject b) {
        card.removeAllViews();
        int p = WaScheduleUi.dp(this, 12);
        card.setPadding(p, p, p, p);
        card.setBackground(WaScheduleUi.round(this, 0xFFFFFFFF, 12, 0));
        card.setOnClickListener(v -> startActivity(new Intent(this, WaScheduleDetailActivity.class)
                .putExtra(WaScheduleDetailActivity.EXTRA_ID, b.optLong("id"))));

        ChipGroup meta = new ChipGroup(this);
        meta.setChipSpacingHorizontal(WaScheduleUi.dp(this, 6));
        meta.setChipSpacingVertical(WaScheduleUi.dp(this, 4));
        int[] sc = WaScheduleUi.statusColors(b.optString("status"));
        meta.addView(WaScheduleUi.pill(this, b.optString("status_label", b.optString("status")), sc[0], sc[1]));
        String sendAt = WaScheduleUi.str(b, "send_at");
        if (sendAt != null) meta.addView(WaScheduleUi.text(this, "🕒 " + WaScheduleText.display(sendAt), 12, WaScheduleUi.TEXT2, false));
        String rep = WaScheduleUi.str(b, "repeat_label");
        if (rep != null) {
            int runs = b.optInt("run_count");
            meta.addView(WaScheduleUi.text(this, "🔁 " + rep + (runs > 0 ? " · " + runs + "× jalan" : ""), 12, WaScheduleUi.BLUE_TXT, false));
        }
        int autoN = b.optInt("auto_area_count");
        if (autoN > 0) meta.addView(WaScheduleUi.text(this, "🔄 " + autoN + " wilayah otomatis", 12, WaScheduleUi.GREEN_TXT, false));
        String creator = WaScheduleUi.str(b, "creator");
        if (creator != null) meta.addView(WaScheduleUi.text(this, "👤 " + creator, 12, WaScheduleUi.TEXT2, false));
        card.addView(meta);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, WaScheduleUi.dp(this, 6), 0, 0);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView name = WaScheduleUi.text(this, b.optString("name"), 15, WaScheduleUi.TEXT, true);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(name);
        TextView msg = WaScheduleUi.text(this, b.optString("message_preview"), 13, WaScheduleUi.TEXT2, false);
        msg.setMaxLines(2);
        msg.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(msg);
        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        JSONObject c = b.optJSONObject("counts");
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.END);
        right.setPadding(WaScheduleUi.dp(this, 8), 0, 0, 0);
        int sent = c != null ? c.optInt("sent") : 0, failed = c != null ? c.optInt("failed") : 0;
        int pending = c != null ? c.optInt("pending") : 0, tot = c != null ? c.optInt("total") : 0;
        TextView s1 = WaScheduleUi.text(this, sent + " terkirim", 12, WaScheduleUi.GREEN_TXT, true);
        s1.setGravity(Gravity.END);
        right.addView(s1);
        if (failed > 0) right.addView(endText(failed + " gagal", WaScheduleUi.RED_TXT));
        if (pending > 0) right.addView(endText(pending + " menunggu", WaScheduleUi.AMBER_TXT));
        right.addView(endText("dari " + tot + " penerima", WaScheduleUi.TEXT2));
        row.addView(right);
        card.addView(row);
    }

    private TextView endText(String s, int color) {
        TextView t = WaScheduleUi.text(this, s, 12, color, false);
        t.setGravity(Gravity.END);
        return t;
    }
}
