package com.crowja.damiupos;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.PendingTransactionDao;
import com.crowja.damiupos.model.PendingTransaction;
import com.crowja.damiupos.model.Transaction;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Daftar "Transaksi Pending" — pesanan yang dicatat staf untuk dieksekusi
 * nanti. Ketuk satu item → buka layar Transaksi Baru terisi (pelanggan +
 * catatan); setelah transaksi disimpan, baris pending dihapus otomatis.
 */
public class PendingTransactionListActivity extends AppCompatActivity {

    private static final SimpleDateFormat SDF_PARSE =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
    private static final SimpleDateFormat SDF_OUT =
            new SimpleDateFormat("d MMM yyyy, HH:mm", new Locale("id", "ID"));

    private PendingTransactionDao dao;
    private RecyclerView rv;
    private TextView tvEmpty, tvSummary;
    private PendingAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pending_transaction_list);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        dao = new PendingTransactionDao(DatabaseHelper.getInstance(this));

        rv = findViewById(R.id.rv);
        tvEmpty = findViewById(R.id.tvEmpty);
        tvSummary = findViewById(R.id.tvSummary);

        adapter = new PendingAdapter();
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setHasFixedSize(true);
        rv.setAdapter(adapter);
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadData();
    }

    private void loadData() {
        List<PendingTransaction> list = dao.getAllPending();
        adapter.setData(list);
        tvSummary.setText(list.size() + " transaksi pending menunggu dieksekusi");
        tvEmpty.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
        rv.setVisibility(list.isEmpty() ? View.GONE : View.VISIBLE);
    }

    /** Buka Transaksi Baru terisi untuk mengeksekusi pending ini. */
    private void execute(PendingTransaction p) {
        Intent i = new Intent(this, TransactionActivity.class);
        i.putExtra("type", Transaction.TYPE_JUAL);
        if (p.getCustomerId() > 0) i.putExtra("customer_id", p.getCustomerId());
        i.putExtra(TransactionActivity.EXTRA_PENDING_ID, p.getId());
        if (p.getNote() != null) i.putExtra(TransactionActivity.EXTRA_PENDING_NOTE, p.getNote());
        startActivity(i);
    }

    private void confirmDelete(PendingTransaction p) {
        new AlertDialog.Builder(this)
                .setTitle("Hapus Transaksi Pending")
                .setMessage("Hapus pending \"" + safe(p.getCustomerName()) + "\" dari daftar?")
                .setPositiveButton("Hapus", (d, w) -> {
                    dao.delete(p.getId());
                    loadData();
                    Toast.makeText(this, "Transaksi pending dihapus", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    private static String safe(String s) {
        return s != null && !s.isEmpty() ? s : "Umum";
    }

    private static String formatWhen(String ts) {
        if (ts == null || ts.isEmpty()) return "";
        try {
            Date d = SDF_PARSE.parse(ts);
            return d != null ? SDF_OUT.format(d) : ts;
        } catch (Exception e) {
            return ts;
        }
    }

    private class PendingAdapter extends RecyclerView.Adapter<PendingAdapter.VH> {
        private List<PendingTransaction> data = new ArrayList<>();

        void setData(List<PendingTransaction> list) {
            this.data = list != null ? list : new ArrayList<>();
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_pending_transaction, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            PendingTransaction p = data.get(position);
            h.tvCustomer.setText(safe(p.getCustomerName()));

            String note = p.getNote();
            if (note != null && !note.trim().isEmpty()) {
                h.tvNote.setText(note.trim());
                h.tvNote.setVisibility(View.VISIBLE);
            } else {
                h.tvNote.setVisibility(View.GONE);
            }

            StringBuilder meta = new StringBuilder(formatWhen(p.getCreatedAt()));
            if (p.getCreatedByName() != null && !p.getCreatedByName().isEmpty()) {
                if (meta.length() > 0) meta.append(" • ");
                meta.append("oleh ").append(p.getCreatedByName());
            }
            h.tvMeta.setText(meta.toString());

            h.itemView.setOnClickListener(v -> execute(p));
            h.btnDelete.setOnClickListener(v -> confirmDelete(p));
        }

        @Override
        public int getItemCount() { return data.size(); }

        class VH extends RecyclerView.ViewHolder {
            TextView tvCustomer, tvNote, tvMeta;
            ImageButton btnDelete;
            VH(View v) {
                super(v);
                tvCustomer = v.findViewById(R.id.tvCustomer);
                tvNote = v.findViewById(R.id.tvNote);
                tvMeta = v.findViewById(R.id.tvMeta);
                btnDelete = v.findViewById(R.id.btnDelete);
            }
        }
    }
}
