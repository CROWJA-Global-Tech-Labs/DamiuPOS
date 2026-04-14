package com.damiu.pos;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.damiu.pos.db.CustomerDao;
import com.damiu.pos.db.DatabaseHelper;
import com.damiu.pos.db.SettingsDao;
import com.damiu.pos.model.Customer;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class FollowUpActivity extends AppCompatActivity {

    private RecyclerView rv;
    private TextView tvEmpty, tvSummary;
    private FollowUpAdapter adapter;
    private CustomerDao customerDao;
    private SettingsDao settingsDao;
    private int thresholdDays;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_followup);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        DatabaseHelper dbHelper = DatabaseHelper.getInstance(this);
        customerDao = new CustomerDao(dbHelper);
        settingsDao = new SettingsDao(dbHelper);
        thresholdDays = settingsDao.getFollowupDays();

        rv = findViewById(R.id.rv);
        tvEmpty = findViewById(R.id.tvEmpty);
        tvSummary = findViewById(R.id.tvSummary);

        adapter = new FollowUpAdapter();
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(adapter);
    }

    @Override
    protected void onResume() {
        super.onResume();
        thresholdDays = settingsDao.getFollowupDays();
        List<Customer> list = customerDao.getFollowUpCandidates(thresholdDays);
        adapter.setData(list);
        tvSummary.setText(list.size() + " pelanggan belum bertransaksi lebih dari " + thresholdDays + " hari");
        tvEmpty.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
        rv.setVisibility(list.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private class FollowUpAdapter extends RecyclerView.Adapter<FollowUpAdapter.VH> {
        private List<Customer> data = new java.util.ArrayList<>();

        void setData(List<Customer> list) { this.data = list; notifyDataSetChanged(); }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_followup, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            Customer c = data.get(position);
            h.tvName.setText(c.getName());
            h.tvPhone.setText(c.getPhone() != null && !c.getPhone().isEmpty() ? c.getPhone() : "-");

            long days = daysSince(c.getCreatedAt()); // overloaded: last purchase
            h.tvDays.setText(String.valueOf(days));
            h.tvLastPurchase.setText("Terakhir beli: " + formatDate(c.getCreatedAt()));
            int saldo = c.getSaldoGalon();
            h.tvGalon.setText(saldo + " galon");
            h.tvGalon.setVisibility(saldo > 0 ? View.VISIBLE : View.GONE);

            boolean hasPhone = c.getPhone() != null && !c.getPhone().isEmpty();
            h.btnCall.setVisibility(hasPhone ? View.VISIBLE : View.GONE);
            h.btnCall.setOnClickListener(v -> openWhatsApp(c.getPhone()));

            h.itemView.setOnClickListener(v -> {
                Intent i = new Intent(FollowUpActivity.this, CustomerDetailActivity.class);
                i.putExtra("customer_id", c.getId());
                startActivity(i);
            });
        }

        @Override
        public int getItemCount() { return data.size(); }

        class VH extends RecyclerView.ViewHolder {
            TextView tvName, tvPhone, tvDays, tvGalon, tvLastPurchase;
            ImageButton btnCall;
            VH(View v) {
                super(v);
                tvName = v.findViewById(R.id.tvName);
                tvPhone = v.findViewById(R.id.tvPhone);
                tvDays = v.findViewById(R.id.tvDays);
                tvGalon = v.findViewById(R.id.tvGalon);
                tvLastPurchase = v.findViewById(R.id.tvLastPurchase);
                btnCall = v.findViewById(R.id.btnCall);
            }
        }
    }

    private long daysSince(String ts) {
        if (ts == null || ts.isEmpty()) return 0;
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
            Date d = sdf.parse(ts);
            if (d == null) return 0;
            long diff = System.currentTimeMillis() - d.getTime();
            return Math.max(0, diff / (1000L * 60 * 60 * 24));
        } catch (Exception e) {
            try {
                SimpleDateFormat sdf2 = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
                Date d2 = sdf2.parse(ts.substring(0, Math.min(10, ts.length())));
                if (d2 == null) return 0;
                long diff = System.currentTimeMillis() - d2.getTime();
                return Math.max(0, diff / (1000L * 60 * 60 * 24));
            } catch (Exception ignored) { return 0; }
        }
    }

    private String formatDate(String ts) {
        if (ts == null || ts.isEmpty()) return "-";
        try {
            SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
            SimpleDateFormat out = new SimpleDateFormat("dd MMM yyyy", new Locale("id", "ID"));
            Date d = in.parse(ts);
            return d != null ? out.format(d) : ts;
        } catch (Exception e) {
            return ts.length() >= 10 ? ts.substring(0, 10) : ts;
        }
    }

    private void openWhatsApp(String phone) {
        if (phone == null || phone.isEmpty()) return;
        String normalized = phone.replaceAll("[^0-9]", "");
        if (normalized.startsWith("0")) normalized = "62" + normalized.substring(1);
        else if (!normalized.startsWith("62")) normalized = "62" + normalized;
        try {
            Intent i = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://wa.me/" + normalized +
                            "?text=" + Uri.encode("Halo, ini pesan dari depot kami. Apakah air minum Anda sudah habis? Kami siap mengantarkan pesanan Anda 🙂")));
            try {
                getPackageManager().getPackageInfo("com.whatsapp", 0);
                i.setPackage("com.whatsapp");
            } catch (Exception ignored) {
                try {
                    getPackageManager().getPackageInfo("com.whatsapp.w4b", 0);
                    i.setPackage("com.whatsapp.w4b");
                } catch (Exception ignored2) {}
            }
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "Tidak dapat membuka WhatsApp", Toast.LENGTH_SHORT).show();
        }
    }
}
