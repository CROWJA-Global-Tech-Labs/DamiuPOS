package com.crowja.damiupos;

import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.crowja.damiupos.db.CustomerDao;
import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.SettingsDao;
import com.crowja.damiupos.model.Customer;
import com.crowja.damiupos.model.Transaction;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class FollowUpActivity extends AppCompatActivity {

    // Formatter di-hoist (dipakai per-item di bind) — main-thread, aman dibagi.
    private static final SimpleDateFormat SDF_PARSE_FULL =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
    private static final SimpleDateFormat SDF_PARSE_DATE =
            new SimpleDateFormat("yyyy-MM-dd", Locale.US);
    private static final SimpleDateFormat SDF_OUT_DATE =
            new SimpleDateFormat("dd MMM yyyy", new Locale("id", "ID"));
    private static final SimpleDateFormat SDF_OUT_DAY =
            new SimpleDateFormat("EEEE", new Locale("id", "ID"));

    private RecyclerView rv;
    private TextView tvEmpty, tvSummary;
    private FollowUpAdapter adapter;
    private CustomerDao customerDao;
    private SettingsDao settingsDao;
    private int thresholdDays;

    /** Daftar follow-up terkini (di-refresh tiap onResume) — dipakai tombol Peta. */
    private List<Customer> currentList = new ArrayList<>();

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
        rv.setHasFixedSize(true);
        rv.setAdapter(adapter);

        attachSwipeToRemove();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadData();
    }

    private void loadData() {
        thresholdDays = settingsDao.getFollowupDays();
        List<Customer> list = customerDao.getFollowUpCandidates(thresholdDays);
        currentList = list;
        adapter.setData(list);
        tvSummary.setText(list.size() + " pelanggan belum bertransaksi lebih dari " + thresholdDays + " hari");
        tvEmpty.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
        rv.setVisibility(list.isEmpty() ? View.GONE : View.VISIBLE);
        invalidateOptionsMenu(); // enable/disable tombol Peta sesuai ketersediaan koordinat
    }

    /**
     * Swipe card ke kiri → munculkan background merah "Hapus" → lepas →
     * dialog konfirmasi + alasan (wajib). Kalau batal, card dikembalikan.
     */
    private void attachSwipeToRemove() {
        final Paint bgPaint = new Paint();
        bgPaint.setColor(Color.parseColor("#E53935")); // merah
        final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(14 * getResources().getDisplayMetrics().scaledDensity);
        textPaint.setFakeBoldText(true);
        textPaint.setTextAlign(Paint.Align.RIGHT);

        ItemTouchHelper.SimpleCallback cb = new ItemTouchHelper.SimpleCallback(
                0, ItemTouchHelper.LEFT) {
            @Override
            public boolean onMove(@NonNull RecyclerView rv,
                                  @NonNull RecyclerView.ViewHolder vh,
                                  @NonNull RecyclerView.ViewHolder target) {
                return false;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder vh, int direction) {
                int pos = vh.getBindingAdapterPosition();
                if (pos == RecyclerView.NO_POSITION) return;
                Customer c = adapter.getItem(pos);
                if (c != null) showRemoveDialog(c, pos);
            }

            @Override
            public void onChildDraw(@NonNull Canvas canvas, @NonNull RecyclerView rv,
                                    @NonNull RecyclerView.ViewHolder vh,
                                    float dX, float dY, int actionState, boolean isActive) {
                View item = vh.itemView;
                if (dX < 0) {
                    // Background merah di area yang tersingkap saat swipe kiri.
                    canvas.drawRect(item.getRight() + dX, item.getTop(),
                            item.getRight(), item.getBottom(), bgPaint);
                    float cy = item.getTop() + item.getHeight() / 2f
                            + textPaint.getTextSize() / 3f;
                    canvas.drawText("HAPUS  ›",
                            item.getRight() - 32f, cy, textPaint);
                }
                super.onChildDraw(canvas, rv, vh, dX, dY, actionState, isActive);
            }
        };
        new ItemTouchHelper(cb).attachToRecyclerView(rv);
    }

    /** Konfirmasi remove dari follow-up + alasan wajib. */
    private void showRemoveDialog(Customer c, int position) {
        final EditText input = new EditText(this);
        input.setHint("Alasan (wajib): mis. pindah rumah, langganan tempat lain");
        input.setMinLines(2);
        input.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(pad, pad / 2, pad, 0);
        wrap.addView(input);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Hapus dari Follow Up")
                .setMessage("Keluarkan \"" + (c.getName() != null ? c.getName() : "pelanggan")
                        + "\" dari daftar follow up? Akan muncul lagi otomatis kalau "
                        + "pelanggan membeli lagi.")
                .setView(wrap)
                // Batal → kembalikan card yang ter-swipe.
                .setNegativeButton("Batal", (d, w) -> adapter.notifyItemChanged(position))
                .setPositiveButton("Hapus", null)
                .setOnCancelListener(d -> adapter.notifyItemChanged(position))
                .create();

        dialog.setOnShowListener(dlg ->
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                    String reason = input.getText().toString().trim();
                    if (reason.isEmpty()) {
                        input.setError("Alasan wajib diisi");
                        return;
                    }
                    customerDao.excludeFromFollowUp(c.getId(), reason);
                    dialog.dismiss();
                    Toast.makeText(this,
                            c.getName() + " dikeluarkan dari follow up",
                            Toast.LENGTH_SHORT).show();
                    loadData();
                }));
        dialog.show();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 1, 0, "Peta")
                .setIcon(android.R.drawable.ic_dialog_map)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == 1) {
            // Langsung buka peta OSM dengan semua pin pelanggan follow-up
            // + posisi live device. Navigasi per pelanggan ada di card.
            startActivity(new Intent(this, FollowUpMapActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /** Buka Google Maps dengan pin pada koordinat pelanggan, label = nama. */
    private void openCustomerInMaps(Customer c) {
        double lat = c.getLatitude();
        double lng = c.getLongitude();
        if (lat == 0 && lng == 0) {
            Toast.makeText(this,
                    "Pelanggan belum punya koordinat lokasi. Atur lewat form pelanggan.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        String label = c.getName() != null ? c.getName() : "Pelanggan";
        // geo: URI dengan query berlabel → pin bernama di Google Maps.
        String uri = "geo:" + lat + "," + lng + "?q=" + lat + "," + lng
                + "(" + Uri.encode(label) + ")";
        Intent gmaps = new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
        gmaps.setPackage("com.google.android.apps.maps");
        if (gmaps.resolveActivity(getPackageManager()) != null) {
            startActivity(gmaps);
        } else {
            // Fallback ke browser kalau app Google Maps tidak terpasang.
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://www.google.com/maps/search/?api=1&query="
                            + lat + "," + lng)));
        }
    }

    private class FollowUpAdapter extends RecyclerView.Adapter<FollowUpAdapter.VH> {
        private List<Customer> data = new java.util.ArrayList<>();

        void setData(List<Customer> list) { this.data = list; notifyDataSetChanged(); }

        Customer getItem(int pos) {
            return pos >= 0 && pos < data.size() ? data.get(pos) : null;
        }

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

            // Avatar: foto pelanggan kalau ada, fallback inisial nama.
            String name = c.getName();
            h.tvInitial.setText(name != null && !name.isEmpty()
                    ? String.valueOf(name.charAt(0)).toUpperCase(Locale.getDefault()) : "?");
            String photo = c.getPhotoPath();
            android.graphics.Bitmap bmp = null;
            if (photo != null && !photo.isEmpty() && new java.io.File(photo).exists()) {
                bmp = loadAvatarBitmap(photo);
            }
            if (bmp != null) {
                h.ivAvatar.setImageBitmap(bmp);
                h.ivAvatar.setVisibility(View.VISIBLE);
                h.tvInitial.setVisibility(View.INVISIBLE);
            } else {
                h.ivAvatar.setVisibility(View.GONE);
                h.tvInitial.setVisibility(View.VISIBLE);
            }

            long days = daysSince(c.getCreatedAt()); // overloaded: last purchase
            h.tvDays.setText(String.valueOf(days));
            h.tvLastPurchase.setText("Terakhir beli: " + formatDate(c.getCreatedAt()));
            int saldo = c.getSaldoGalon();
            h.tvGalon.setText(saldo + " galon");
            h.tvGalon.setVisibility(saldo > 0 ? View.VISIBLE : View.GONE);

            // 2 tombol aksi: amplop = kirim pesan follow-up, panah = navigasi.
            h.btnMessage.setOnClickListener(v -> openWhatsAppForFollowUp(c));
            h.btnNavigate.setOnClickListener(v -> openCustomerInMaps(c));

            // Tap card (di luar tombol) → dialog pilih aksi.
            h.itemView.setOnClickListener(v -> showActionDialog(c));

            // Long-press tetap menuju detail pelanggan.
            h.itemView.setOnLongClickListener(v -> {
                Intent i = new Intent(FollowUpActivity.this, CustomerDetailActivity.class);
                i.putExtra("customer_id", c.getId());
                startActivity(i);
                return true;
            });
        }

        @Override
        public int getItemCount() { return data.size(); }

        class VH extends RecyclerView.ViewHolder {
            TextView tvName, tvPhone, tvDays, tvGalon, tvLastPurchase, tvInitial;
            android.widget.ImageView ivAvatar;
            ImageButton btnMessage, btnNavigate;
            VH(View v) {
                super(v);
                tvName = v.findViewById(R.id.tvName);
                tvPhone = v.findViewById(R.id.tvPhone);
                tvDays = v.findViewById(R.id.tvDays);
                tvGalon = v.findViewById(R.id.tvGalon);
                tvLastPurchase = v.findViewById(R.id.tvLastPurchase);
                tvInitial = v.findViewById(R.id.tvInitial);
                ivAvatar = v.findViewById(R.id.ivAvatar);
                btnMessage = v.findViewById(R.id.btnMessage);
                btnNavigate = v.findViewById(R.id.btnNavigate);
            }
        }
    }

    /** Dialog pilihan aksi saat card pelanggan ditekan (bukan tombolnya). */
    private void showActionDialog(Customer c) {
        new AlertDialog.Builder(this)
                .setTitle(c.getName() != null ? c.getName() : "Pelanggan")
                .setItems(new CharSequence[]{
                        "Kirim Pesan Follow Up (WhatsApp)",
                        "Navigasi (Google Maps)",
                        "Buat Transaksi",
                        "Lihat Detail Pelanggan"
                }, (dialog, which) -> {
                    if (which == 0) {
                        openWhatsAppForFollowUp(c);
                    } else if (which == 1) {
                        openCustomerInMaps(c);
                    } else if (which == 2) {
                        // Buat transaksi JUAL dengan pelanggan ini terpilih.
                        Intent i = new Intent(this, TransactionActivity.class);
                        i.putExtra("type", Transaction.TYPE_JUAL);
                        i.putExtra("customer_id", c.getId());
                        startActivity(i);
                    } else {
                        Intent i = new Intent(this, CustomerDetailActivity.class);
                        i.putExtra("customer_id", c.getId());
                        startActivity(i);
                    }
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    /**
     * Avatar foto pelanggan: thumbnail ter-cache (RGB_565 + sampling + EXIF) agar
     * scroll tidak men-decode ulang. Null kalau gagal (fallback ke inisial).
     */
    private android.graphics.Bitmap loadAvatarBitmap(String path) {
        return com.crowja.damiupos.util.BitmapUtils.cachedThumb(path, 160, 160);
    }

    private long daysSince(String ts) {
        if (ts == null || ts.isEmpty()) return 0;
        try {
            Date d = SDF_PARSE_FULL.parse(ts);
            if (d == null) return 0;
            long diff = System.currentTimeMillis() - d.getTime();
            return Math.max(0, diff / (1000L * 60 * 60 * 24));
        } catch (Exception e) {
            try {
                Date d2 = SDF_PARSE_DATE.parse(ts.substring(0, Math.min(10, ts.length())));
                if (d2 == null) return 0;
                long diff = System.currentTimeMillis() - d2.getTime();
                return Math.max(0, diff / (1000L * 60 * 60 * 24));
            } catch (Exception ignored) { return 0; }
        }
    }

    private String formatDate(String ts) {
        if (ts == null || ts.isEmpty()) return "-";
        try {
            Date d = SDF_PARSE_FULL.parse(ts);
            return d != null ? SDF_OUT_DATE.format(d) : ts;
        } catch (Exception e) {
            return ts.length() >= 10 ? ts.substring(0, 10) : ts;
        }
    }

    private void openWhatsAppForFollowUp(Customer c) {
        String phone = c != null ? c.getPhone() : null;
        if (phone == null || phone.isEmpty()) {
            Toast.makeText(this, "Pelanggan belum memiliki nomor WhatsApp", Toast.LENGTH_SHORT).show();
            return;
        }
        String normalized = phone.replaceAll("[^0-9]", "");
        if (normalized.startsWith("0")) normalized = "62" + normalized.substring(1);
        else if (!normalized.startsWith("62")) normalized = "62" + normalized;

        String lastTs = c.getCreatedAt();
        String hari = formatDayName(lastTs);
        String tanggal = formatDate(lastTs);
        long days = daysSince(lastTs);

        // Template configurable dari Pengaturan. Placeholder:
        //   {nama} {hari} {tanggal} {hari_lalu}
        String template = settingsDao.getFollowUpTemplate();
        String msg = template
                .replace("{nama}", c.getName() != null ? c.getName() : "")
                .replace("{hari}", hari)
                .replace("{tanggal}", tanggal)
                .replace("{hari_lalu}", String.valueOf(days));

        try {
            Intent i = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://wa.me/" + normalized +
                            "?text=" + Uri.encode(msg)));
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
            // Catat: pelanggan ini di-follow-up hari ini (untuk laporan harian).
            customerDao.markFollowedUp(c.getId());
        } catch (Exception e) {
            Toast.makeText(this, "Tidak dapat membuka WhatsApp", Toast.LENGTH_SHORT).show();
        }
    }

    /** Nama hari dalam Bahasa Indonesia (Senin, Selasa, dst.) */
    private String formatDayName(String ts) {
        if (ts == null || ts.isEmpty()) return "-";
        try {
            Date d = SDF_PARSE_FULL.parse(ts);
            return d != null ? SDF_OUT_DAY.format(d) : "-";
        } catch (Exception e) {
            try {
                Date d2 = SDF_PARSE_DATE.parse(ts.substring(0, Math.min(10, ts.length())));
                return d2 != null ? SDF_OUT_DAY.format(d2) : "-";
            } catch (Exception ignored) { return "-"; }
        }
    }
}
