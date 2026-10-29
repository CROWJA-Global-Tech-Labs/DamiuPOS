package com.crowja.damiupos.wa;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
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
import java.util.List;

/**
 * Detail satu Broadcast Terjadwal (cermin halaman web wa.schedules.show): status, waktu berikutnya, pengulangan,
 * isi pesan, wilayah otomatis (+ jumlah pelanggan kini), aksi ✏️ Ubah / Batalkan / 🗑️ Hapus (dua ketukan), dan
 * daftar penerima dengan status kirim per pelanggan.
 */
public class WaScheduleDetailActivity extends AppCompatActivity {

    static final String EXTRA_ID = "wa_schedule_id";

    private String staffUuid;
    private long id;
    private SwipeRefreshLayout swipe;
    private TextView tvState;
    private Toolbar toolbar;
    private JSONObject data;
    private final List<JSONObject> recipients = new ArrayList<>();
    private final Adapter adapter = new Adapter();
    private boolean loading, resumedOnce, busy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        staffUuid = WaAdmin.gate(this);
        if (staffUuid == null) return;
        id = getIntent().getLongExtra(EXTRA_ID, 0);
        if (id <= 0) {
            finish();
            return;
        }
        setContentView(R.layout.activity_wa_schedule_detail);
        toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        tvState = findViewById(R.id.tvState);
        swipe = findViewById(R.id.swipe);
        swipe.setOnRefreshListener(this::load);
        RecyclerView rv = findViewById(R.id.rvDetail);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(adapter);
        load();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (resumedOnce && staffUuid != null && id > 0) load();   // kembali dari form Ubah
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
            load();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void load() {
        if (loading) return;
        loading = true;
        swipe.setRefreshing(true);
        WaScheduleUi.call(this, () -> WaAdmin.api(this).adminWaGet("schedules/" + id, null, staffUuid), r -> {
            loading = false;
            swipe.setRefreshing(false);
            data = r;
            recipients.clear();
            JSONArray arr = r.optJSONArray("recipients");
            for (int i = 0; arr != null && i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) recipients.add(o);
            }
            JSONObject b = r.optJSONObject("broadcast");
            if (b != null) toolbar.setTitle(b.optString("name", "Detail Broadcast"));
            tvState.setVisibility(View.GONE);
            adapter.notifyDataSetChanged();
        }, (msg, code) -> {
            loading = false;
            swipe.setRefreshing(false);
            if (code == 404) {
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                finish();
                return;
            }
            if (data == null) {
                tvState.setVisibility(View.VISIBLE);
                tvState.setText(msg + "\nTarik ke bawah untuk mencoba lagi.");
            } else {
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
            }
        });
    }

    // ------------------------------------------------------------------ aksi

    private void confirmCancel() {
        new AlertDialog.Builder(this)
                .setTitle("Batalkan broadcast?")
                .setMessage("Penerima yang belum terkirim tidak akan dikirimi. Pengulangan (bila rutin) juga berhenti.")
                .setPositiveButton("Ya, batalkan", (d, w) -> post("cancel"))
                .setNegativeButton("Tidak", null)
                .show();
    }

    private void confirmDelete() {
        new AlertDialog.Builder(this)
                .setTitle("Hapus broadcast?")
                .setMessage("Broadcast ini beserta daftar penerima & status kirimnya dihapus permanen.")
                .setPositiveButton("Hapus", (d, w) -> post("delete"))
                .setNegativeButton("Batal", null)
                .show();
    }

    private void post(String action) {
        if (busy) return;
        busy = true;
        swipe.setRefreshing(true);
        WaScheduleUi.call(this, () -> WaAdmin.api(this).adminWaPost("schedules/" + id + "/" + action, new JSONObject(), staffUuid), r -> {
            busy = false;
            swipe.setRefreshing(false);
            Toast.makeText(this, WaAdmin.message(r, "Selesai."), Toast.LENGTH_LONG).show();
            if ("delete".equals(action)) finish();
            else load();
        }, (msg, code) -> {
            busy = false;
            swipe.setRefreshing(false);
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
            load();
        });
    }

    // ------------------------------------------------------------------ tampilan

    private final class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @Override
        public int getItemViewType(int position) {
            return position == 0 ? 0 : 1;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LinearLayout box = new LinearLayout(parent.getContext());
            box.setOrientation(LinearLayout.VERTICAL);
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            int m = WaScheduleUi.dp(parent.getContext(), 10);
            lp.setMargins(m, viewType == 0 ? m : 0, m, viewType == 0 ? m : 1);
            box.setLayoutParams(lp);
            return new RecyclerView.ViewHolder(box) { };
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int pos) {
            if (pos == 0) bindHeader((LinearLayout) h.itemView);
            else bindRecipient((LinearLayout) h.itemView, recipients.get(pos - 1));
        }

        @Override
        public int getItemCount() {
            return data == null ? 0 : 1 + recipients.size();
        }
    }

    private void bindHeader(LinearLayout box) {
        box.removeAllViews();
        int p = WaScheduleUi.dp(this, 14);
        box.setPadding(p, p, p, p);
        box.setBackground(WaScheduleUi.round(this, 0xFFFFFFFF, 12, 0));
        JSONObject b = data.optJSONObject("broadcast");
        JSONObject c = data.optJSONObject("counts");
        if (b == null) return;

        ChipGroup meta = new ChipGroup(this);
        meta.setChipSpacingHorizontal(WaScheduleUi.dp(this, 6));
        int[] sc = WaScheduleUi.statusColors(b.optString("status"));
        meta.addView(WaScheduleUi.pill(this, b.optString("status_label"), sc[0], sc[1]));
        String sendAt = WaScheduleUi.str(b, "send_at");
        if (sendAt != null) meta.addView(WaScheduleUi.text(this, "berikutnya " + WaScheduleText.display(sendAt), 12, WaScheduleUi.TEXT2, false));
        box.addView(meta);

        String rep = WaScheduleUi.str(b, "repeat_label");
        if (rep != null) {
            StringBuilder s = new StringBuilder("🔁 ").append(rep);
            int runs = b.optInt("run_count");
            if (runs > 0) {
                s.append(" · sudah ").append(runs).append("× jalan");
                String last = WaScheduleUi.str(b, "last_run_at");
                if (last != null) s.append(" (terakhir ").append(WaScheduleText.display(last)).append(": ")
                        .append(b.optInt("last_sent")).append(" terkirim, ").append(b.optInt("last_failed")).append(" gagal)");
            }
            TextView t = WaScheduleUi.text(this, s.toString(), 13, WaScheduleUi.BLUE_TXT, false);
            t.setPadding(0, WaScheduleUi.dp(this, 6), 0, 0);
            box.addView(t);
        }
        if (c != null) {
            TextView t = WaScheduleUi.text(this, c.optInt("sent") + " terkirim, " + c.optInt("pending") + " menunggu, "
                    + c.optInt("failed") + " gagal dari " + c.optInt("total") + " penerima", 13, WaScheduleUi.TEXT, true);
            t.setPadding(0, WaScheduleUi.dp(this, 6), 0, 0);
            box.addView(t);
        }
        String creator = WaScheduleUi.str(b, "creator");
        if (creator != null) box.addView(WaScheduleUi.text(this, "👤 Dibuat oleh " + creator, 12, WaScheduleUi.TEXT2, false));

        TextView lbl = WaScheduleUi.text(this, "Isi pesan", 12, WaScheduleUi.TEXT2, true);
        lbl.setPadding(0, WaScheduleUi.dp(this, 10), 0, WaScheduleUi.dp(this, 2));
        box.addView(lbl);
        TextView msg = WaScheduleUi.text(this, b.optString("message"), 14, WaScheduleUi.TEXT, false);
        msg.setTextIsSelectable(true);
        int mp = WaScheduleUi.dp(this, 10);
        msg.setPadding(mp, mp, mp, mp);
        msg.setBackground(WaScheduleUi.round(this, 0xFFF5F5F5, 8, 0xFFCFD8DC));
        box.addView(msg);

        JSONArray areas = b.optJSONArray("auto_areas");
        if (areas != null && areas.length() > 0) {
            TextView at = WaScheduleUi.text(this, "🔄 Wilayah otomatis", 13, WaScheduleUi.GREEN_TXT, true);
            at.setPadding(0, WaScheduleUi.dp(this, 10), 0, WaScheduleUi.dp(this, 4));
            box.addView(at);
            ChipGroup cg = new ChipGroup(this);
            cg.setChipSpacingHorizontal(WaScheduleUi.dp(this, 6));
            cg.setChipSpacingVertical(WaScheduleUi.dp(this, 4));
            for (int i = 0; i < areas.length(); i++) {
                JSONObject a = areas.optJSONObject(i);
                if (a == null) continue;
                cg.addView(WaScheduleUi.pill(this, a.optString("label") + " · " + a.optInt("count") + " pelanggan",
                        WaScheduleUi.GREEN_BG, WaScheduleUi.GREEN_TXT));
            }
            box.addView(cg);
            box.addView(WaScheduleUi.text(this, "Pelanggan baru di wilayah ini ikut jadi penerima otomatis (dicek tiap menit).",
                    12, WaScheduleUi.TEXT2, false));
        }

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, WaScheduleUi.dp(this, 12), 0, 0);
        if (b.optBoolean("editable")) {
            Button e = WaScheduleUi.outline(this, "✏️ Ubah", WaScheduleUi.PRIMARY);
            e.setOnClickListener(v -> startActivity(new Intent(this, WaScheduleFormActivity.class)
                    .putExtra(WaScheduleFormActivity.EXTRA_ID, id)));
            actions.addView(e, weight());
        }
        if (b.optBoolean("can_cancel")) {
            Button x = WaScheduleUi.outline(this, "Batalkan", WaScheduleUi.AMBER_TXT);
            x.setOnClickListener(v -> confirmCancel());
            actions.addView(x, weight());
        }
        if (b.optBoolean("can_delete")) {
            Button d = WaScheduleUi.outline(this, "🗑️ Hapus", WaScheduleUi.RED_TXT);
            d.setOnClickListener(v -> confirmDelete());
            actions.addView(d, weight());
        }
        if (actions.getChildCount() > 0) box.addView(actions);

        TextView rt = WaScheduleUi.text(this, "Penerima (" + recipients.size() + ")", 13, WaScheduleUi.TEXT, true);
        rt.setPadding(0, WaScheduleUi.dp(this, 12), 0, 0);
        box.addView(rt);
        if (recipients.isEmpty()) box.addView(WaScheduleUi.text(this, "Belum ada penerima.", 13, WaScheduleUi.TEXT2, false));
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMarginEnd(WaScheduleUi.dp(this, 6));
        return lp;
    }

    private void bindRecipient(LinearLayout box, JSONObject r) {
        box.removeAllViews();
        box.setOrientation(LinearLayout.HORIZONTAL);
        int p = WaScheduleUi.dp(this, 12);
        box.setPadding(p, WaScheduleUi.dp(this, 8), p, WaScheduleUi.dp(this, 8));
        box.setBackgroundColor(0xFFFFFFFF);
        box.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        String name = WaScheduleUi.str(r, "name");
        TextView n = WaScheduleUi.text(this, (name != null ? name : "(pelanggan terhapus)")
                + ("auto".equals(r.optString("source")) ? "  🔄 otomatis" : ""), 14, WaScheduleUi.TEXT, true);
        n.setMaxLines(1);
        n.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(n);
        String phone = WaScheduleUi.str(r, "phone");
        String desa = WaScheduleUi.str(r, "desa"), kec = WaScheduleUi.str(r, "kecamatan");
        col.addView(WaScheduleUi.text(this, (phone != null ? phone : "tanpa nomor") + (desa != null ? " · " + desa : "")
                + (kec != null ? ", " + kec : ""), 12, WaScheduleUi.TEXT2, false));
        box.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        String status = r.optString("status");
        String err = WaScheduleUi.str(r, "error_label");
        TextView st;
        if ("sent".equals(status)) {
            String acc = WaScheduleUi.str(r, "bridge_account");
            st = WaScheduleUi.text(this, "✓ terkirim" + (acc != null ? " · " + acc : ""), 12, WaScheduleUi.GREEN_TXT, true);
        } else if ("failed".equals(status)) {
            st = WaScheduleUi.text(this, "✗ " + (err != null ? err : "gagal"), 12, WaScheduleUi.RED_TXT, true);
        } else {
            st = WaScheduleUi.text(this, "— menunggu" + (err != null ? " (" + err + ")" : ""), 12, WaScheduleUi.TEXT2, false);
        }
        st.setGravity(Gravity.END);
        st.setMaxWidth(WaScheduleUi.dp(this, 150));
        box.addView(st);
    }
}
