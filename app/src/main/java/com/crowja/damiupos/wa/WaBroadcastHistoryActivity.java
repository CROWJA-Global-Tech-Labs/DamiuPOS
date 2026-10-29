package com.crowja.damiupos.wa;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.crowja.damiupos.R;
import com.crowja.damiupos.sync.SyncApi;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 🕒 Riwayat Broadcast (Admin) — cermin /wa-broadcast/history: satu baris per klik "Kirim" (termasuk blast
 * 📢 Masalah dari HP kurir), 20 per halaman, terbaru dulu; ketuk → {@link WaBroadcastDetailActivity}.
 */
public class WaBroadcastHistoryActivity extends AppCompatActivity {

    private static final int MENU_RELOAD = 1;

    private String staffUuid;
    private SyncApi api;
    private SwipeRefreshLayout swipe;
    private View boxState;
    private TextView tvState, tvMore;
    private View btnRetry;
    private final List<JSONObject> rows = new ArrayList<>();
    private final Adapter adapter = new Adapter();
    private int page = 0, lastPage = 1;
    private boolean loading;
    /** Naik tiap muat ulang dari awal — balasan halaman lama yang telat dibuang. */
    private int generation;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        staffUuid = WaAdmin.gate(this);
        if (staffUuid == null) return;
        api = WaAdmin.api(this);
        setContentView(R.layout.activity_wa_broadcast_history);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        swipe = findViewById(R.id.swipe);
        boxState = findViewById(R.id.boxState);
        tvState = findViewById(R.id.tvState);
        tvMore = findViewById(R.id.tvMore);
        btnRetry = findViewById(R.id.btnRetry);
        RecyclerView rv = findViewById(R.id.rvLogs);
        LinearLayoutManager lm = new LinearLayoutManager(this);
        rv.setLayoutManager(lm);
        rv.setAdapter(adapter);
        rv.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView r, int dx, int dy) {
                if (dy > 0 && !loading && page < lastPage && lm.findLastVisibleItemPosition() >= rows.size() - 3) {
                    load(false);
                }
            }
        });
        swipe.setColorSchemeColors(0xFF1565C0);
        swipe.setOnRefreshListener(() -> load(true));
        btnRetry.setOnClickListener(v -> load(true));
        load(true);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, MENU_RELOAD, 0, "🔄 Muat ulang").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == MENU_RELOAD) {
            load(true);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void load(boolean reset) {
        if (loading && !reset) return;
        final int gen = reset ? ++generation : generation;
        final int want = reset ? 1 : page + 1;
        loading = true;
        if (reset) {
            swipe.setRefreshing(true);
            tvMore.setVisibility(View.GONE);
            if (rows.isEmpty()) showState("Memuat…", false);
        } else {
            tvMore.setVisibility(View.VISIBLE);
        }
        new Thread(() -> {
            JSONObject r = null;
            String err = null;
            try {
                Map<String, String> q = new HashMap<>();
                q.put("page", String.valueOf(want));
                r = api.adminWaGet("broadcast/history", q, staffUuid);
            } catch (Exception e) {
                err = WaAdmin.errorMessage(e);
            }
            final JSONObject fr = r;
            final String fe = err;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || gen != generation) return;
                loading = false;
                swipe.setRefreshing(false);
                tvMore.setVisibility(View.GONE);
                if (fr == null) {
                    if (rows.isEmpty()) showState(fe, true);
                    else Toast.makeText(this, fe, Toast.LENGTH_LONG).show();
                    return;
                }
                if (reset) rows.clear();
                JSONArray arr = fr.optJSONArray("logs");
                for (int i = 0; arr != null && i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o != null) rows.add(o);
                }
                page = fr.optInt("page", want);
                lastPage = Math.max(1, fr.optInt("last_page", 1));
                adapter.notifyDataSetChanged();
                if (rows.isEmpty()) showState("📣\nBelum ada broadcast yang dikirim.", false);
                else boxState.setVisibility(View.GONE);
            });
        }).start();
    }

    private void showState(String text, boolean retry) {
        boxState.setVisibility(View.VISIBLE);
        tvState.setText(text);
        btnRetry.setVisibility(retry ? View.VISIBLE : View.GONE);
    }

    @Nullable
    static String str(JSONObject o, String k) {
        if (o == null || o.isNull(k)) return null;
        String s = o.optString(k, "").trim();
        return s.isEmpty() || "null".equals(s) ? null : s;
    }

    private final class Adapter extends RecyclerView.Adapter<Holder> {
        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_wa_broadcast_history, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            JSONObject o = rows.get(position);
            String aud = str(o, "audience_label");
            h.tvAudience.setText(aud != null ? aud : String.valueOf(str(o, "audience")));
            String time = str(o, "created_at_label");
            String who = str(o, "creator");
            h.tvMeta.setText("🕒 " + (time != null ? time : "—") + (who != null ? "   👤 " + who : ""));
            String msg = str(o, "message_preview");
            h.tvMessage.setText(msg != null ? msg : "");
            int sent = o.optInt("sent"), skipped = o.optInt("skipped"), total = o.optInt("total");
            h.tvCounts.setText("✅ " + sent + " terkirim" + (skipped > 0 ? "  ·  ⚠️ " + skipped + " dilewati" : "")
                    + "  ·  dari " + total + " pelanggan");
            final long id = o.optLong("id");
            h.itemView.setOnClickListener(v -> startActivity(new Intent(WaBroadcastHistoryActivity.this,
                    WaBroadcastDetailActivity.class).putExtra(WaBroadcastDetailActivity.EXTRA_ID, id)));
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }
    }

    private static final class Holder extends RecyclerView.ViewHolder {
        final TextView tvAudience, tvMeta, tvMessage, tvCounts;

        Holder(View v) {
            super(v);
            tvAudience = v.findViewById(R.id.tvAudience);
            tvMeta = v.findViewById(R.id.tvMeta);
            tvMessage = v.findViewById(R.id.tvMessage);
            tvCounts = v.findViewById(R.id.tvCounts);
        }
    }
}
