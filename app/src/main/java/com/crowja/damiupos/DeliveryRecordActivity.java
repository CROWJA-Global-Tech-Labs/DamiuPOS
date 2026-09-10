package com.crowja.damiupos;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.SettingsDao;
import com.crowja.damiupos.sync.SyncApi;
import com.crowja.damiupos.sync.SyncSettings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 🏁 "Rekor Pengiriman": hari &amp; bulan TERBAIK tiap perangkat, berikut capaian yang sedang
 * berjalan.
 *
 * <p>Semua angkanya dihitung SERVER ({@code /api/delivery/record} → App\Support\DeliveryRecord) —
 * perakit yang sama persis dengan kartu di halaman Delivery web. Dua alasan: transaksi
 * device-isolated di lapisan sync, jadi sebuah HP tak mungkin tahu rekor rekannya; dan proyek ini
 * punya sejarah panjang bug "angka beda antar halaman" karena tiap permukaan menulis querynya
 * sendiri. Layar ini TIDAK menghitung apa pun — kalau butuh angka lain, ubah DeliveryRecord.
 *
 * <p>Satuannya ORDER SELESAI, bukan galon, dan sumbunya waktu PENYELESAIAN. Itu sebabnya angkanya
 * memang tidak akan cocok dengan kartu penjualan mana pun; catatan di layar mengatakannya terus
 * terang alih-alih membiarkan orang menyimpulkan sendiri bahwa ada yang salah.
 */
public class DeliveryRecordActivity extends AppCompatActivity {

    /** Rekor bergerak sepanjang hari saat kurir menyelesaikan order — segarkan berkala. */
    private static final long AUTO_REFRESH_MS = 30_000L;

    private SwipeRefreshLayout swipe;
    private TextView tvBranchToday, tvBranchMeta, tvEmpty;
    private Adapter adapter;
    private SyncSettings cfg;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable autoRefresh = new Runnable() {
        @Override
        public void run() {
            load(false);
            handler.postDelayed(this, AUTO_REFRESH_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_delivery_record);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        cfg = new SyncSettings(new SettingsDao(DatabaseHelper.getInstance(this)));
        tvBranchToday = findViewById(R.id.tvBranchToday);
        tvBranchMeta = findViewById(R.id.tvBranchMeta);
        tvEmpty = findViewById(R.id.tvEmpty);

        swipe = findViewById(R.id.swipe);
        swipe.setOnRefreshListener(() -> load(true));

        RecyclerView rv = findViewById(R.id.rvDevices);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new Adapter();
        rv.setAdapter(adapter);

        load(true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.postDelayed(autoRefresh, AUTO_REFRESH_MS);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(autoRefresh);
    }

    /**
     * @param manual true = dipicu pengguna (buka layar / tarik-segarkan) → kegagalan DILAPORKAN.
     *               false = penyegaran otomatis → kegagalan DITELAN: sinyal putus sesaat tak boleh
     *               memuntahkan toast tiap 30 detik ke wajah kurir yang sedang di jalan.
     */
    private void load(boolean manual) {
        if (!cfg.isEnrolled()) {
            tvEmpty.setText("Perangkat belum terhubung ke server.");
            tvEmpty.setVisibility(View.VISIBLE);
            swipe.setRefreshing(false);
            return;
        }
        if (manual) swipe.setRefreshing(true);

        new Thread(() -> {
            JSONObject res = null;
            try {
                res = new SyncApi(cfg).deliveryRecord();
            } catch (Exception ignored) {
            }
            final JSONObject out = res;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                swipe.setRefreshing(false);
                if (out == null) {
                    if (manual) {
                        Toast.makeText(this, "Gagal memuat rekor pengiriman", Toast.LENGTH_SHORT).show();
                    }
                    return;
                }
                render(out);
            });
        }).start();
    }

    private void render(JSONObject res) {
        JSONObject bt = res.optJSONObject("branch_total");
        if (bt != null) {
            JSONObject today = bt.optJSONObject("today");
            JSONObject bestDay = bt.isNull("best_day") ? null : bt.optJSONObject("best_day");
            int todayN = today != null ? today.optInt("orders", 0) : 0;
            tvBranchToday.setText(num(todayN) + " order");

            StringBuilder meta = new StringBuilder();
            if (bestDay != null) {
                int best = bestDay.optInt("orders", 0);
                meta.append("Rekor harian ").append(num(best))
                        .append(" (").append(prettyDay(bestDay.optString("date", ""))).append(')');
                if (todayN > best) {
                    meta.append("\n🏆 Hari ini REKOR BARU se-cabang!");
                } else {
                    meta.append("\nKurang ").append(num(best - todayN + 1)).append(" order lagi untuk memecahkannya.");
                }
            } else {
                // null berarti "belum ada pembanding", bukan nol — jangan tampilkan "rekor 0".
                meta.append("Belum ada hari pembanding.");
            }
            JSONObject month = bt.optJSONObject("this_month");
            JSONObject bestMonth = bt.isNull("best_month") ? null : bt.optJSONObject("best_month");
            if (month != null) {
                meta.append("\nBulan ini ").append(num(month.optInt("orders", 0))).append(" order");
                if (bestMonth != null) {
                    meta.append(" · rekor bulanan ").append(num(bestMonth.optInt("orders", 0)))
                            .append(" (").append(prettyMonth(bestMonth.optString("month", ""))).append(')');
                }
            }
            tvBranchMeta.setText(meta.toString());
        }

        List<JSONObject> rows = new ArrayList<>();
        JSONArray arr = res.optJSONArray("devices");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) rows.add(o);
            }
        }
        adapter.setData(rows, res.isNull("self_device_uuid") ? null : res.optString("self_device_uuid", null));
        tvEmpty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
    }

    /** Ribuan bergaya Indonesia (titik). */
    private static String num(long v) {
        return String.format(new Locale("id", "ID"), "%,d", v).replace(',', '.');
    }

    private static String prettyDay(String ymd) {
        try {
            return new SimpleDateFormat("d MMM yyyy", new Locale("id", "ID"))
                    .format(new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(ymd));
        } catch (Exception e) {
            return ymd;
        }
    }

    private static String prettyMonth(String ym) {
        try {
            return new SimpleDateFormat("MMM yyyy", new Locale("id", "ID"))
                    .format(new SimpleDateFormat("yyyy-MM", Locale.US).parse(ym));
        } catch (Exception e) {
            return ym;
        }
    }

    private class Adapter extends RecyclerView.Adapter<Adapter.VH> {

        private final List<JSONObject> rows = new ArrayList<>();
        private String selfUuid;

        void setData(List<JSONObject> data, String self) {
            rows.clear();
            rows.addAll(data);
            selfUuid = self;
            notifyDataSetChanged();
        }

        class VH extends RecyclerView.ViewHolder {
            final TextView tvName, tvBadge, tvToday, tvBestDay, tvMonth, tvBestMonth, tvMeta;
            final View barProgress;

            VH(View v) {
                super(v);
                tvName = v.findViewById(R.id.tvName);
                tvBadge = v.findViewById(R.id.tvBadge);
                tvToday = v.findViewById(R.id.tvToday);
                tvBestDay = v.findViewById(R.id.tvBestDay);
                tvMonth = v.findViewById(R.id.tvMonth);
                tvBestMonth = v.findViewById(R.id.tvBestMonth);
                tvMeta = v.findViewById(R.id.tvMeta);
                barProgress = v.findViewById(R.id.barProgress);
            }
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_delivery_record, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            JSONObject r = rows.get(pos);
            String uuid = r.isNull("device_uuid") ? null : r.optString("device_uuid", null);
            boolean mine = selfUuid != null && selfUuid.equals(uuid);
            h.tvName.setText(r.optString("icon", "📱") + " " + r.optString("label", "Perangkat")
                    + (mine ? "  (perangkat ini)" : ""));

            JSONObject today = r.optJSONObject("today");
            JSONObject month = r.optJSONObject("this_month");
            int todayN = today != null ? today.optInt("orders", 0) : 0;
            int monthN = month != null ? month.optInt("orders", 0) : 0;
            h.tvToday.setText(num(todayN));
            h.tvMonth.setText(num(monthN));

            // best_day/best_month bisa NULL untuk perangkat baru — null berarti "belum ada
            // pembanding", bukan nol. Ditulis apa adanya alih-alih memamerkan "rekor 0".
            JSONObject bd = r.isNull("best_day") ? null : r.optJSONObject("best_day");
            JSONObject bm = r.isNull("best_month") ? null : r.optJSONObject("best_month");
            h.tvBestDay.setText(bd != null
                    ? "Rekor " + num(bd.optInt("orders", 0)) + " · " + prettyDay(bd.optString("date", ""))
                    : "Belum ada rekor harian");
            h.tvBestMonth.setText(bm != null
                    ? "Rekor " + num(bm.optInt("orders", 0)) + " · " + prettyMonth(bm.optString("month", ""))
                    : "Belum ada rekor bulanan");

            boolean recordToday = r.optBoolean("is_record_today", false);
            h.tvBadge.setVisibility(recordToday || r.optBoolean("is_record_month", false)
                    ? View.VISIBLE : View.GONE);

            StringBuilder meta = new StringBuilder();
            meta.append(num(today != null ? today.optInt("galon", 0) : 0)).append(" galon diantar hari ini");
            if (!r.isNull("to_beat_today")) {
                meta.append(" · kurang ").append(num(r.optInt("to_beat_today", 0))).append(" order lagi");
            }
            h.tvMeta.setText(meta.toString());

            // Lebar bar = hari ini terhadap rekor harian. Diset di post() karena lebar induknya
            // belum diketahui saat bind — pola yang sama dipakai layar Pencapaian Penjualan.
            final int bestN = bd != null ? bd.optInt("orders", 0) : 0;
            final int cur = todayN;
            h.barProgress.post(() -> {
                View parent = (View) h.barProgress.getParent();
                int full = parent != null ? parent.getWidth() : 0;
                if (full <= 0) return;
                double ratio = bestN > 0 ? Math.min(1.0, cur / (double) bestN) : (cur > 0 ? 1.0 : 0);
                ViewGroup.LayoutParams lp = h.barProgress.getLayoutParams();
                lp.width = (int) Math.round(full * ratio);
                h.barProgress.setLayoutParams(lp);
            });
        }

        @Override
        public int getItemCount() { return rows.size(); }
    }
}
