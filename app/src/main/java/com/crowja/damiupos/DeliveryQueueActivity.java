package com.crowja.damiupos;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.TransactionDao;
import com.crowja.damiupos.model.Transaction;
import com.google.android.material.button.MaterialButton;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Antrian Delivery — order JUAL yang sudah dibuat dan menunggu diproses/diantar.
 * Tiap item: pelanggan, ringkasan order, navigasi peta, dan timer real-time sejak
 * order dibuat. Tombol "Selesai" menandai order beres → mencatat lama proses
 * (durasi = waktu selesai − waktu antri) yang lalu tersinkron ke dashboard.
 */
public class DeliveryQueueActivity extends AppCompatActivity {

    private static final SimpleDateFormat SDF_PARSE =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

    private TransactionDao dao;
    private RecyclerView rv;
    private TextView tvEmpty, tvSummary;
    private QueueAdapter adapter;

    private final Handler tick = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            adapter.refreshTimers();
            tick.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_delivery_queue);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        dao = new TransactionDao(DatabaseHelper.getInstance(this));
        rv = findViewById(R.id.rv);
        tvEmpty = findViewById(R.id.tvEmpty);
        tvSummary = findViewById(R.id.tvSummary);

        adapter = new QueueAdapter();
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(adapter);
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadData();
        tick.postDelayed(ticker, 1000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        tick.removeCallbacks(ticker);
    }

    private void loadData() {
        List<Transaction> list = dao.getDeliveryQueue();
        adapter.setData(list);
        tvSummary.setText(list.size() + " order menunggu diproses");
        tvEmpty.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
        rv.setVisibility(list.isEmpty() ? View.GONE : View.VISIBLE);
    }

    /** Buka navigasi peta ke lokasi pelanggan (koordinat → fallback alamat). */
    private void navigate(Transaction t) {
        Intent i;
        if (t.getCustomerLat() != 0 || t.getCustomerLng() != 0) {
            Uri nav = Uri.parse("google.navigation:q=" + t.getCustomerLat() + "," + t.getCustomerLng());
            i = new Intent(Intent.ACTION_VIEW, nav).setPackage("com.google.android.apps.maps");
            if (i.resolveActivity(getPackageManager()) == null) {
                i = new Intent(Intent.ACTION_VIEW, Uri.parse(
                        "https://www.google.com/maps?q=" + t.getCustomerLat() + "," + t.getCustomerLng()));
            }
        } else if (t.getCustomerAddress() != null && !t.getCustomerAddress().trim().isEmpty()) {
            i = new Intent(Intent.ACTION_VIEW, Uri.parse(
                    "https://www.google.com/maps/search/?api=1&query=" + Uri.encode(t.getCustomerAddress())));
        } else {
            Toast.makeText(this, "Lokasi pelanggan belum diset", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "Tidak ada aplikasi peta", Toast.LENGTH_SHORT).show();
        }
    }

    /** Konfirmasi → tandai Selesai → sinkron → reload. */
    private void complete(Transaction t) {
        long ms = elapsedMillis(t.getDeliveryQueuedAt());
        new AlertDialog.Builder(this)
                .setTitle("Tandai Selesai")
                .setMessage("Order \"" + safe(t.getCustomerName()) + "\" sudah selesai diproses?\n\n"
                        + "Lama proses: " + formatDuration(ms))
                .setPositiveButton("Selesai", (d, w) -> {
                    dao.markDelivered(t.getId());
                    com.crowja.damiupos.sync.SyncScheduler.syncNow(getApplicationContext());
                    loadData();
                    Toast.makeText(this, "Order selesai • " + formatDuration(ms), Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    // ----------------------------------------------------------------- helpers

    private static String safe(String s) {
        return s != null && !s.isEmpty() ? s : "Umum";
    }

    /** Milidetik sejak order masuk antrian; 0 kalau tidak bisa di-parse. */
    private static long elapsedMillis(String queuedAt) {
        if (queuedAt == null || queuedAt.length() < 19) return 0;
        try {
            java.util.Date d = SDF_PARSE.parse(queuedAt.substring(0, 19));
            if (d == null) return 0;
            long ms = System.currentTimeMillis() - d.getTime();
            return Math.max(0, ms);
        } catch (Exception e) {
            return 0;
        }
    }

    /** Format durasi ringkas: "45 dtk" / "12 mnt 30 dtk" / "1 jam 5 mnt". */
    private static String formatDuration(long ms) {
        long s = ms / 1000;
        long h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        if (h > 0) return h + " jam " + m + " mnt";
        if (m > 0) return m + " mnt " + sec + " dtk";
        return sec + " dtk";
    }

    private class QueueAdapter extends RecyclerView.Adapter<QueueAdapter.VH> {
        private List<Transaction> data = new ArrayList<>();

        void setData(List<Transaction> list) {
            this.data = list != null ? list : new ArrayList<>();
            notifyDataSetChanged();
        }

        /** Update hanya teks timer pada baris yang terlihat (tanpa rebind penuh). */
        void refreshTimers() {
            for (int i = 0; i < rv.getChildCount(); i++) {
                View child = rv.getChildAt(i);
                RecyclerView.ViewHolder vh = rv.getChildViewHolder(child);
                int pos = vh.getAdapterPosition();
                if (pos >= 0 && pos < data.size() && vh instanceof VH) {
                    ((VH) vh).tvElapsed.setText("⏱ "
                            + formatDuration(elapsedMillis(data.get(pos).getDeliveryQueuedAt())));
                }
            }
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_delivery_queue, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            Transaction t = data.get(position);
            h.tvCustomer.setText(safe(t.getCustomerName()));

            if (t.getCustomerPhone() != null && !t.getCustomerPhone().trim().isEmpty()) {
                h.tvPhone.setText(t.getCustomerPhone());
                h.tvPhone.setVisibility(View.VISIBLE);
            } else {
                h.tvPhone.setVisibility(View.GONE);
            }

            h.tvOrder.setText(orderSummary(t));

            if (t.getCatatan() != null && !t.getCatatan().trim().isEmpty()) {
                h.tvNote.setText("Catatan: " + t.getCatatan().trim());
                h.tvNote.setVisibility(View.VISIBLE);
            } else {
                h.tvNote.setVisibility(View.GONE);
            }

            if (t.getCustomerAddress() != null && !t.getCustomerAddress().trim().isEmpty()) {
                h.tvAddress.setText("📍 " + t.getCustomerAddress().trim());
                h.tvAddress.setVisibility(View.VISIBLE);
            } else {
                h.tvAddress.setVisibility(View.GONE);
            }

            h.tvElapsed.setText("⏱ " + formatDuration(elapsedMillis(t.getDeliveryQueuedAt())));

            h.btnNavigasi.setOnClickListener(v -> navigate(t));
            h.btnSelesai.setOnClickListener(v -> complete(t));
        }

        @Override
        public int getItemCount() { return data.size(); }

        class VH extends RecyclerView.ViewHolder {
            TextView tvCustomer, tvPhone, tvOrder, tvNote, tvAddress, tvElapsed;
            MaterialButton btnNavigasi, btnSelesai;
            VH(View v) {
                super(v);
                tvCustomer = v.findViewById(R.id.tvCustomer);
                tvPhone = v.findViewById(R.id.tvPhone);
                tvOrder = v.findViewById(R.id.tvOrder);
                tvNote = v.findViewById(R.id.tvNote);
                tvAddress = v.findViewById(R.id.tvAddress);
                tvElapsed = v.findViewById(R.id.tvElapsed);
                btnNavigasi = v.findViewById(R.id.btnNavigasi);
                btnSelesai = v.findViewById(R.id.btnSelesai);
            }
        }
    }

    /** Ringkasan order: jumlah galon + total (+ ongkir kalau ada). */
    private static String orderSummary(Transaction t) {
        StringBuilder sb = new StringBuilder();
        sb.append(t.getJumlahGalon()).append(" galon");
        sb.append(" • Rp ").append(formatRupiah(t.getTotalHarga()));
        if (t.getOngkir() > 0) sb.append(" (ongkir Rp ").append(formatRupiah(t.getOngkir())).append(")");
        return sb.toString();
    }

    private static String formatRupiah(double v) {
        return String.format(Locale.US, "%,d", (long) v).replace(',', '.');
    }
}
