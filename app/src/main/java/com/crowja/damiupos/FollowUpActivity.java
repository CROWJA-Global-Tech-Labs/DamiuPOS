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
import com.crowja.damiupos.db.UserDao;
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

    private RecyclerView rv;
    private TextView tvEmpty, tvSummary;
    private FollowUpAdapter adapter;
    private CustomerDao customerDao;
    private SettingsDao settingsDao;
    private int thresholdDays;

    /** Daftar follow-up terkini (di-refresh tiap onResume) — dipakai tombol Peta. */
    private List<Customer> currentList = new ArrayList<>();

    /** Urutan daftar: kunci (false = pembelian terakhir, true = follow-up terakhir) × arah
     *  (false = asc: terlama/belum-pernah dulu — default; true = desc: terbaru dulu). */
    private boolean sortByFollowUp = false;
    private boolean sortDesc = false;

    /** Filter "Perangkat": null = SEMUA asal perangkat (default, sama seperti tanpa filter) —
     *  hanya diisi begitu staf mencentang sebagian saja di dialog {@link #showOriginFilterDialog}. */
    private java.util.Set<String> selectedOrigins = null;

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
        List<Customer> list = customerDao.getFollowUpCandidates(thresholdDays, selectedOrigins);
        // Default (beli + asc) = urutan DAO; kombinasi lain di-sort ulang di sini.
        if (sortByFollowUp) sortByLastFollowUp(list, sortDesc);
        else if (sortDesc) sortByLastPurchase(list, true);
        currentList = list;
        adapter.setData(list);
        // Kemunculan pakai mana yang LEBIH LAMA: N hari fixed ATAU perkiraan galon habis (rate
        // konsumsi galon/hari tiap pelanggan) — jadi hindari wording "lebih dari N hari" yang keliru.
        tvSummary.setText(list.size() + " pelanggan sudah waktunya di-follow up"
                + " (lewat " + thresholdDays + " hari atau perkiraan galonnya habis)");
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
        menu.add(0, 2, 1, "Urutkan")
                .setIcon(android.R.drawable.ic_menu_sort_by_size)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        menu.add(0, 3, 2, "Perangkat")
                .setIcon(android.R.drawable.ic_menu_manage)
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
        if (item.getItemId() == 2) {
            showSortDialog();
            return true;
        }
        if (item.getItemId() == 3) {
            showOriginFilterDialog();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /** Filter "Perangkat": daftar SEMUA pelanggan (branch-luas), dgn checkbox per asal perangkat
     *  — DEFAULT SEMUA TERCENTANG (perilaku sama dengan tanpa filter). Uncheck sebagian untuk
     *  mempersempit; mencentang ulang semuanya kembali ke null (tanpa filter). */
    private void showOriginFilterDialog() {
        List<String> origins = customerDao.getDistinctCustomerOrigins();
        if (origins.isEmpty()) {
            Toast.makeText(this, "Belum ada data asal perangkat pelanggan.", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] labels = origins.toArray(new String[0]);
        boolean[] checked = new boolean[labels.length];
        for (int i = 0; i < labels.length; i++) {
            checked[i] = selectedOrigins == null || selectedOrigins.contains(labels[i]);
        }
        new AlertDialog.Builder(this)
                .setTitle("Tampilkan Pelanggan dari Perangkat")
                .setMultiChoiceItems(labels, checked, (d, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton("Terapkan", (d, w) -> {
                    java.util.Set<String> picked = new java.util.HashSet<>();
                    for (int i = 0; i < labels.length; i++) if (checked[i]) picked.add(labels[i]);
                    // Semua tercentang = sama dengan tanpa filter → simpan null (query lebih ringan,
                    // dan daftar perangkat baru yang muncul belakangan otomatis ikut tampil).
                    selectedOrigins = picked.size() >= labels.length ? null : picked;
                    loadData();
                })
                .setNeutralButton("Pilih Semua", (d, w) -> {
                    selectedOrigins = null;
                    loadData();
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    /** Pilih urutan daftar: kunci (pembelian / follow-up terakhir) × arah (asc / desc). */
    private void showSortDialog() {
        String[] options = {
                "Pembelian terakhir — terlama dulu",
                "Pembelian terakhir — terbaru dulu",
                "Follow-up terakhir — belum pernah / terlama dulu",
                "Follow-up terakhir — terbaru dulu",
        };
        int checked = (sortByFollowUp ? 2 : 0) + (sortDesc ? 1 : 0);
        new AlertDialog.Builder(this)
                .setTitle("Urutkan berdasarkan")
                .setSingleChoiceItems(options, checked, (d, which) -> {
                    sortByFollowUp = which >= 2;
                    sortDesc = (which % 2) == 1;
                    d.dismiss();
                    loadData();
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    /**
     * Urut "Follow-up terakhir": entri MANUAL tetap terpin teratas (terbaru dulu — sama seperti
     * default), lalu asc = BELUM PERNAH di-follow-up dulu lalu follow-up paling lama (urutan kerja
     * "siapa yang paling perlu dihubungi"); desc = kebalikannya (terbaru dulu, belum-pernah paling
     * bawah). Cermin sort ?sort=fu&dir=... di web.
     */
    private static void sortByLastFollowUp(List<Customer> list, boolean desc) {
        final int mul = desc ? -1 : 1;
        java.util.Collections.sort(list, (a, b) -> {
            boolean am = a.getFollowupManualAt() != null;
            boolean bm = b.getFollowupManualAt() != null;
            if (am != bm) return am ? -1 : 1;
            if (am) return compareNullable(b.getFollowupManualAt(), a.getFollowupManualAt());
            String af = a.getLastFollowupAt(), bf = b.getLastFollowupAt();
            if ((af == null) != (bf == null)) return (af == null ? -1 : 1) * mul;
            return compareNullable(af, bf) * mul;   // timestamp ISO → perbandingan string = kronologis
        });
    }

    /** Urut "Pembelian terakhir" arah desc (terbaru dulu); manual tetap terpin. Asc = urutan DAO. */
    private static void sortByLastPurchase(List<Customer> list, boolean desc) {
        final int mul = desc ? -1 : 1;
        java.util.Collections.sort(list, (a, b) -> {
            boolean am = a.getFollowupManualAt() != null;
            boolean bm = b.getFollowupManualAt() != null;
            if (am != bm) return am ? -1 : 1;
            if (am) return compareNullable(b.getFollowupManualAt(), a.getFollowupManualAt());
            // getCreatedAt di kandidat follow-up = timestamp pembelian terakhir (overloaded, lihat DAO).
            return compareNullable(a.getCreatedAt(), b.getCreatedAt()) * mul;
        });
    }

    private static int compareNullable(String a, String b) {
        if (a == null && b == null) return 0;
        if (a == null) return -1;
        if (b == null) return 1;
        return a.compareTo(b);
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
            // Baris info: "Terakhir beli", perkiraan order lagi (dari konsumsi galon/hari), + catatan.
            StringBuilder info = new StringBuilder("Terakhir beli: " + formatDate(c.getCreatedAt()));
            String reorder = c.getFollowUpReorderDay();
            if (reorder != null && !reorder.isEmpty()) {
                info.append("\n🔮 Perkiraan order lagi: ").append(formatDate(reorder));
                if (c.getFollowUpRate() > 0) {
                    info.append(" (±").append(fmtRate(c.getFollowUpRate())).append(" gln/hari)");
                }
                long over = daysSince(reorder);   // sudah lewat berapa hari dari perkiraan
                if (over > 0) info.append(" · lewat ").append(over).append(" hari");
            }
            String note = c.getFollowupNote();
            if (note != null && !note.trim().isEmpty()) {
                info.append("\n📝 ").append(note.trim());
            }
            h.tvLastPurchase.setMaxLines(4);
            h.tvLastPurchase.setText(info.toString());
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
        // "Kunjungi Urgent" hanya untuk ADMIN — cermin web, di mana penandaan dilakukan dari
        // dashboard. Staf lapangan MENGERJAKAN daftarnya, bukan mengisinya sendiri.
        final boolean canFlag = UserDao.isCurrentUserAdmin(this);
        final boolean flagged = c.needsUrgentVisit();

        java.util.List<CharSequence> items = new java.util.ArrayList<>(java.util.Arrays.asList(
                "Kirim Pesan Follow Up (WhatsApp)",
                "Navigasi (Google Maps)",
                "Buat Transaksi",
                "Lihat Detail Pelanggan"));
        if (canFlag) {
            items.add(flagged ? "🚩 Batal Kunjungi Urgent" : "🚩 Tandai Kunjungi Urgent");
        }

        new AlertDialog.Builder(this)
                .setTitle(c.getName() != null ? c.getName() : "Pelanggan")
                // Tak ada lagi item "+ Pantun" terpisah: pantun kini jadi ISI BAWAAN follow-up
                // (lihat WhatsAppFollowUp.open), jadi item pertama sudah pantun dengan sendirinya.
                .setItems(items.toArray(new CharSequence[0]), (dialog, which) -> {
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
                    } else if (which == 3) {
                        Intent i = new Intent(this, CustomerDetailActivity.class);
                        i.putExtra("customer_id", c.getId());
                        startActivity(i);
                    } else {
                        toggleVisitUrgent(c, flagged);
                    }
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    /** Tandai / lepas "Kunjungi Urgent" (admin). Tersinkron — muncul di Daftar Kunjungan Urgent. */
    private void toggleVisitUrgent(Customer c, boolean flagged) {
        new Thread(() -> {
            if (flagged) {
                customerDao.markVisitUrgentDone(c.getId(), "Dibatalkan admin dari Follow Up");
            } else {
                String by = settingsDao.getCurrentUserName();
                customerDao.markVisitUrgent(c.getId(), by != null && !by.trim().isEmpty() ? by.trim() : null);
            }
            com.crowja.damiupos.sync.SyncScheduler.syncNow(getApplicationContext());
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                Toast.makeText(this, flagged
                                ? "Tanda kunjungan urgent dilepas"
                                : "🚩 Masuk Daftar Kunjungan Urgent",
                        Toast.LENGTH_SHORT).show();
                loadData();
            });
        }).start();
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
            try {   // ts bisa "yyyy-MM-dd" saja (mis. perkiraan order lagi) → parse tanggal.
                Date d2 = SDF_PARSE_DATE.parse(ts.substring(0, Math.min(10, ts.length())));
                return d2 != null ? SDF_OUT_DATE.format(d2) : ts;
            } catch (Exception ignored) {
                return ts.length() >= 10 ? ts.substring(0, 10) : ts;
            }
        }
    }

    /** Rate galon/hari: 1 desimal, buang ".0" (mis. "3", "2.5"). */
    private static String fmtRate(double rate) {
        return rate == Math.floor(rate)
                ? String.valueOf((long) rate)
                : String.format(Locale.US, "%.1f", rate);
    }

    /** Follow-up WA. Isinya PANTUN secara bawaan; kalau paket pantun belum terunduh atau Nama Merek
     *  belum diatur di Konfigurasi, WhatsAppFollowUp diam-diam memakai template biasa — staf diberi
     *  tahu sekali supaya tak mengira fiturnya rusak. */
    private void openWhatsAppForFollowUp(Customer c) {
        // isReady(), BUKAN mengambil pantunnya: mengambil di sini akan memajukan kursor rotasi
        // sehingga satu pantun terlewat tanpa pernah terkirim.
        if (!PantunPicker.isReady(settingsDao)) {
            Toast.makeText(this,
                    "Pantun belum siap (paket belum tersinkron / Nama Merek belum diatur) — dikirim tanpa pantun.",
                    Toast.LENGTH_LONG).show();
        }
        WhatsAppFollowUp.open(this, c, settingsDao, customerDao);
    }
}
