package com.crowja.damiupos.wa;

import android.graphics.drawable.GradientDrawable;
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
import java.util.List;

/**
 * 📣 Detail Broadcast (Admin) — cermin /wa-broadcast/history/{id}: audiens · waktu · pengirim, isi pesan,
 * total / terkirim / dilewati, lalu status PER pelanggan (label galat sama dengan web) + akun WA yang dipakai
 * dan caranya dipilih (menangani order / sudah 2 arah / round-robin).
 */
public class WaBroadcastDetailActivity extends AppCompatActivity {

    public static final String EXTRA_ID = "wa_broadcast_log_id";
    private static final int MENU_RELOAD = 1;

    private String staffUuid;
    private SyncApi api;
    private long logId;
    private SwipeRefreshLayout swipe;
    private View boxState, btnRetry;
    private TextView tvState;
    @Nullable private JSONObject log;
    private final List<JSONObject> results = new ArrayList<>();
    private final Adapter adapter = new Adapter();
    private boolean loading;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        staffUuid = WaAdmin.gate(this);
        if (staffUuid == null) return;
        logId = getIntent().getLongExtra(EXTRA_ID, 0);
        if (logId <= 0) {
            finish();
            return;
        }
        api = WaAdmin.api(this);
        setContentView(R.layout.activity_wa_broadcast_detail);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        swipe = findViewById(R.id.swipe);
        boxState = findViewById(R.id.boxState);
        tvState = findViewById(R.id.tvState);
        btnRetry = findViewById(R.id.btnRetry);
        RecyclerView rv = findViewById(R.id.rvResults);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(adapter);
        swipe.setColorSchemeColors(0xFF1565C0);
        swipe.setOnRefreshListener(this::load);
        btnRetry.setOnClickListener(v -> load());
        load();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, MENU_RELOAD, 0, "🔄 Muat ulang").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == MENU_RELOAD) {
            load();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void load() {
        if (loading) {
            swipe.setRefreshing(false);
            return;
        }
        loading = true;
        if (log == null) {
            boxState.setVisibility(View.VISIBLE);
            tvState.setText("Memuat…");
            btnRetry.setVisibility(View.GONE);
        } else {
            swipe.setRefreshing(true);
        }
        new Thread(() -> {
            JSONObject r = null;
            String err = null;
            try {
                r = api.adminWaGet("broadcast/history/" + logId, null, staffUuid);
            } catch (Exception e) {
                err = WaAdmin.errorMessage(e);
            }
            final JSONObject fr = r;
            final String fe = err;
            runOnUiThread(() -> {
                loading = false;
                if (isFinishing() || isDestroyed()) return;
                swipe.setRefreshing(false);
                JSONObject l = fr != null ? fr.optJSONObject("log") : null;
                if (l == null) {
                    String m = fe != null ? fe : "Riwayat broadcast tidak ditemukan.";
                    if (log == null) {
                        tvState.setText(m);
                        btnRetry.setVisibility(View.VISIBLE);
                    } else {
                        Toast.makeText(this, m, Toast.LENGTH_LONG).show();
                    }
                    return;
                }
                log = l;
                results.clear();
                JSONArray arr = l.optJSONArray("results");
                for (int i = 0; arr != null && i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o != null) results.add(o);
                }
                boxState.setVisibility(View.GONE);
                adapter.notifyDataSetChanged();
            });
        }).start();
    }

    private static String s(JSONObject o, String k, String fallback) {
        String v = WaBroadcastHistoryActivity.str(o, k);
        return v != null ? v : fallback;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static final int TYPE_HEADER = 0, TYPE_ROW = 1;

    private final class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @Override
        public int getItemViewType(int position) {
            return position == 0 ? TYPE_HEADER : TYPE_ROW;
        }

        @Override
        public int getItemCount() {
            return log == null ? 0 : results.size() + 1;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inf = LayoutInflater.from(parent.getContext());
            if (viewType == TYPE_HEADER) {
                return new HeaderHolder(inf.inflate(R.layout.item_wa_broadcast_detail_header, parent, false));
            }
            return new RowHolder(inf.inflate(R.layout.item_wa_broadcast_result, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder vh, int position) {
            if (log == null) return;
            if (vh instanceof HeaderHolder) {
                HeaderHolder h = (HeaderHolder) vh;
                h.tvAudience.setText(s(log, "audience_label", s(log, "audience", "—")));
                String who = WaBroadcastHistoryActivity.str(log, "creator");
                h.tvMeta.setText("🕒 " + s(log, "created_at_label", "—") + (who != null ? "   👤 " + who : ""));
                h.tvMessage.setText(s(log, "message", ""));
                h.tvTotal.setText(String.valueOf(log.optInt("total")));
                h.tvSent.setText(String.valueOf(log.optInt("sent")));
                h.tvSkipped.setText(String.valueOf(log.optInt("skipped")));
                h.tvResultsTitle.setText(results.isEmpty() ? "Tidak ada rincian pelanggan." : "Pelanggan (" + results.size() + ")");
                return;
            }
            RowHolder h = (RowHolder) vh;
            JSONObject r = results.get(position - 1);
            h.tvName.setText(s(r, "name", "Tanpa nama"));
            h.tvPhone.setText(s(r, "phone", "—"));
            boolean ok = r.optBoolean("ok");
            String status = s(r, "status_label", ok ? "Terkirim" : "Gagal");
            h.tvStatus.setText(ok ? "✓ " + status : "✕ " + status);
            // Hijau #1B5E20 di atas #E8F5E9 (9,9:1) / merah #B71C1C di atas #FFEBEE (5,8:1).
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(12));
            bg.setColor(ok ? 0xFFE8F5E9 : 0xFFFFEBEE);
            bg.setStroke(dp(1), ok ? 0xFFA5D6A7 : 0xFFEF9A9A);
            h.tvStatus.setBackground(bg);
            h.tvStatus.setTextColor(ok ? 0xFF1B5E20 : 0xFFB71C1C);
            String acc = WaBroadcastHistoryActivity.str(r, "bridge_account");
            String via = WaBroadcastHistoryActivity.str(r, "via_label");
            h.tvAccount.setText("Akun WA: " + (acc != null ? acc + (via != null ? " (" + via + ")" : "") : "—"));
        }
    }

    private static final class HeaderHolder extends RecyclerView.ViewHolder {
        final TextView tvAudience, tvMeta, tvMessage, tvTotal, tvSent, tvSkipped, tvResultsTitle;

        HeaderHolder(View v) {
            super(v);
            tvAudience = v.findViewById(R.id.tvAudience);
            tvMeta = v.findViewById(R.id.tvMeta);
            tvMessage = v.findViewById(R.id.tvMessage);
            tvTotal = v.findViewById(R.id.tvTotal);
            tvSent = v.findViewById(R.id.tvSent);
            tvSkipped = v.findViewById(R.id.tvSkipped);
            tvResultsTitle = v.findViewById(R.id.tvResultsTitle);
        }
    }

    private static final class RowHolder extends RecyclerView.ViewHolder {
        final TextView tvName, tvPhone, tvStatus, tvAccount;

        RowHolder(View v) {
            super(v);
            tvName = v.findViewById(R.id.tvName);
            tvPhone = v.findViewById(R.id.tvPhone);
            tvStatus = v.findViewById(R.id.tvStatus);
            tvAccount = v.findViewById(R.id.tvAccount);
        }
    }
}
