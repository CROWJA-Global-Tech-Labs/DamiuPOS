package com.crowja.damiupos;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.crowja.damiupos.adapter.TransactionAdapter;
import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.TransactionDao;
import com.crowja.damiupos.model.Transaction;
import com.crowja.damiupos.util.Ts;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 📜 "Riwayat Pengiriman": order yang sudah ditandai Selesai, terbaru dulu, dikelompokkan per hari.
 *
 * <p>Sumbernya DB LOKAL ({@link TransactionDao#getDeliveryHistory}), bukan server. Transaksi
 * sengaja terisolasi per-perangkat di lapisan sync, jadi isinya memang hanya pengiriman perangkat
 * INI. Batasan itu dicetak di layar apa adanya — sama seperti catatan cakupan di Detail Pelanggan —
 * supaya tak ada yang menyimpulkan rekannya "hilang" dari catatan.
 *
 * <p>Durasi memakai {@link TransactionAdapter#deliverySeconds} yang sudah ada, bukan hitungan baru:
 * angka durasi di dua layar tak boleh berbeda. Waktu ditampilkan lewat {@link Ts} karena kolom
 * delivery_done_at bisa berisi waktu lokal (tulisan HP) maupun ISO-UTC (tarikan server).
 *
 * <p>Berbeda dari 🏁 {@link DeliveryRecordActivity} yang menampilkan REKOR (hari/bulan terbaik,
 * dihitung server): layar ini daftar kejadiannya.
 */
public class DeliveryHistoryActivity extends AppCompatActivity {

    /** Batas baris yang dimuat. Depot lama bisa punya puluhan ribu order selesai; memuat semuanya
     *  hanya memperlambat pembukaan tanpa ada yang benar-benar menggulir sejauh itu. */
    private static final int LIMIT = 500;

    private SwipeRefreshLayout swipe;
    private TextView tvEmpty, tvSummary;
    private Adapter adapter;
    private TransactionDao dao;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_delivery_history);

        Toolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        dao = new TransactionDao(DatabaseHelper.getInstance(this));
        tvEmpty = findViewById(R.id.tvEmpty);
        tvSummary = findViewById(R.id.tvSummary);
        swipe = findViewById(R.id.swipe);
        swipe.setOnRefreshListener(this::load);

        RecyclerView rv = findViewById(R.id.rvHistory);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setHasFixedSize(true);
        adapter = new Adapter();
        rv.setAdapter(adapter);

        load();
    }

    /** Muat di thread latar: daftar riwayat bisa ratusan baris + JOIN pelanggan. */
    private void load() {
        new Thread(() -> {
            final List<Transaction> rows = dao.getDeliveryHistory(LIMIT);
            final List<Row> out = group(rows);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                swipe.setRefreshing(false);
                adapter.setData(out);
                tvEmpty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);

                int galon = 0;
                for (Transaction t : rows) {
                    galon += t.getJumlahGalon();
                }
                tvSummary.setText(rows.isEmpty()
                        ? "Belum ada pengiriman yang diselesaikan di perangkat ini."
                        : rows.size() + " pengiriman selesai · " + galon + " galon"
                                + (rows.size() >= LIMIT ? " (" + LIMIT + " terbaru)" : ""));
            });
        }).start();
    }

    /** Sisipkan baris HEADER tiap kali tanggalnya berganti — daftar panjang jadi mudah dipindai. */
    private List<Row> group(List<Transaction> rows) {
        List<Row> out = new ArrayList<>();
        String lastDay = null;
        for (Transaction t : rows) {
            String local = Ts.local(t.getDeliveryDoneAt());
            String day = local.length() >= 10 ? local.substring(0, 10) : "";
            if (!day.equals(lastDay)) {
                out.add(Row.header(dayLabel(day)));
                lastDay = day;
            }
            out.add(Row.item(t));
        }

        return out;
    }

    /** "Hari ini" / "Kemarin" / "Sen, 01 Sep 2026" — tanggal absolut tetap ditulis untuk yang lain. */
    private String dayLabel(String ymd) {
        if (ymd.isEmpty()) {
            return "Tanpa tanggal";
        }
        SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        try {
            Date d = in.parse(ymd);
            String today = in.format(new Date());
            String yest = in.format(new Date(System.currentTimeMillis() - 86400000L));
            if (ymd.equals(today)) {
                return "Hari ini";
            }
            if (ymd.equals(yest)) {
                return "Kemarin";
            }

            return new SimpleDateFormat("EEE, dd MMM yyyy", new Locale("id", "ID")).format(d);
        } catch (Exception e) {
            return ymd;
        }
    }

    private static String rp(double v) {
        return "Rp " + String.format(Locale.US, "%,.0f", v).replace(',', '.');
    }

    /** Satu baris daftar: header tanggal ATAU satu pengiriman. */
    private static final class Row {
        final String header;
        final Transaction trx;

        private Row(String header, Transaction trx) {
            this.header = header;
            this.trx = trx;
        }

        static Row header(String s) {
            return new Row(s, null);
        }

        static Row item(Transaction t) {
            return new Row(null, t);
        }

        boolean isHeader() {
            return trx == null;
        }
    }

    private class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        private static final int T_HEADER = 0;
        private static final int T_ITEM = 1;

        private final List<Row> data = new ArrayList<>();

        void setData(List<Row> rows) {
            data.clear();
            data.addAll(rows);
            notifyDataSetChanged();
        }

        @Override
        public int getItemViewType(int position) {
            return data.get(position).isHeader() ? T_HEADER : T_ITEM;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inf = LayoutInflater.from(parent.getContext());
            if (viewType == T_HEADER) {
                return new HeaderVH(inf.inflate(R.layout.item_delivery_history_header, parent, false));
            }

            return new VH(inf.inflate(R.layout.item_delivery_history, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            Row r = data.get(position);
            if (r.isHeader()) {
                ((HeaderVH) holder).tvDay.setText(r.header);

                return;
            }

            Transaction t = r.trx;
            VH h = (VH) holder;
            String name = t.getCustomerName();
            h.tvName.setText(name == null || name.trim().isEmpty() ? "(tanpa nama)" : name);

            String jam = Ts.hm(t.getDeliveryDoneAt());
            h.tvTime.setText(jam.isEmpty() ? "—" : jam);

            // Galon + nilai transaksi: dua angka yang paling sering dicari saat menelusuri riwayat.
            String galon = t.getJumlahGalon() + " galon";
            h.tvGalon.setText(Transaction.TYPE_KEMBALI.equals(t.getType())
                    ? galon + " (ambil galon)" : galon);
            h.tvTotal.setText(rp(t.getTotalHarga()));

            long secs = TransactionAdapter.deliverySeconds(t);
            h.tvDuration.setText(secs < 0 ? "" : "⏱ " + TransactionAdapter.formatDeliverySeconds(secs));
            h.tvDuration.setVisibility(secs < 0 ? View.GONE : View.VISIBLE);

            String by = t.getCompletedByName();
            h.tvBy.setText(by == null || by.trim().isEmpty() ? "" : "Diselesaikan oleh " + by.trim());
            h.tvBy.setVisibility(by == null || by.trim().isEmpty() ? View.GONE : View.VISIBLE);

            boolean hasProof = notEmpty(t.getProofPath()) || notEmpty(t.getProofUrl());
            h.tvProof.setVisibility(hasProof ? View.VISIBLE : View.GONE);

            // Ketuk baris → buka lokasi pelanggan di peta, aksi paling berguna dari sebuah riwayat.
            boolean hasCoord = t.getCustomerLat() != 0 || t.getCustomerLng() != 0;
            h.itemView.setOnClickListener(hasCoord ? v -> openMap(t) : null);
            h.itemView.setClickable(hasCoord);
        }

        @Override
        public int getItemCount() {
            return data.size();
        }
    }

    private static boolean notEmpty(String s) {
        return s != null && !s.trim().isEmpty();
    }

    private void openMap(Transaction t) {
        String label = t.getCustomerName() == null ? "Pelanggan" : t.getCustomerName();
        Uri u = Uri.parse("geo:" + t.getCustomerLat() + "," + t.getCustomerLng()
                + "?q=" + t.getCustomerLat() + "," + t.getCustomerLng()
                + "(" + Uri.encode(label) + ")");
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, u));
        } catch (Exception ignored) {
            // Tak ada aplikasi peta terpasang — diamkan, riwayatnya tetap terbaca.
        }
    }

    static class HeaderVH extends RecyclerView.ViewHolder {
        final TextView tvDay;

        HeaderVH(View v) {
            super(v);
            tvDay = v.findViewById(R.id.tvDay);
        }
    }

    static class VH extends RecyclerView.ViewHolder {
        final TextView tvName, tvTime, tvGalon, tvTotal, tvDuration, tvBy, tvProof;

        VH(View v) {
            super(v);
            tvName = v.findViewById(R.id.tvName);
            tvTime = v.findViewById(R.id.tvTime);
            tvGalon = v.findViewById(R.id.tvGalon);
            tvTotal = v.findViewById(R.id.tvTotal);
            tvDuration = v.findViewById(R.id.tvDuration);
            tvBy = v.findViewById(R.id.tvBy);
            tvProof = v.findViewById(R.id.tvProof);
        }
    }
}
