package com.crowja.damiupos;

import android.app.DatePickerDialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.crowja.damiupos.db.CustomerDao;
import com.crowja.damiupos.db.DatabaseHelper;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.textfield.TextInputEditText;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * "Pelanggan Promosi" — daftar pelanggan baru yang didaftarkan + diberi galon GRATIS di perangkat
 * ini (akuisisi promo): kapan promosinya, dan kapan PEMBELIAN pertamanya (konversi — biasanya
 * terjadi di perangkat depot, terlihat lewat agregat server {@code srv_first_paid} hasil sync).
 * Statistik kohort + preset/rentang tanggal + pencarian + filter status + pengurutan. Cermin
 * mobile dari halaman web "Pelanggan Promosi".
 */
public class PromoCustomersActivity extends AppCompatActivity {

    private CustomerDao customerDao;
    private final List<CustomerDao.PromoRow> cohort = new ArrayList<>();   // hasil query rentang tanggal
    private final List<CustomerDao.PromoRow> shown = new ArrayList<>();    // setelah cari+filter+sort
    private PromoAdapter adapter;

    private TextView tvStatTotal, tvStatConverted, tvStatRate, tvStatDays, tvEmpty;
    private TextInputEditText etSearch;
    private MaterialButtonToggleGroup togglePreset, toggleStatus;

    private String startDay, endDay;      // "yyyy-MM-dd"; null = tanpa batas (preset "Semua")
    private int sortMode = 0;             // indeks SORT_LABELS
    private int lastPresetId;             // preset non-kustom terakhir — dipulihkan bila picker dibatalkan

    private static final String[] SORT_LABELS = {
            "Promo terbaru", "Promo terlama", "Order ulang terbaru", "Konversi tercepat", "Nama A–Z"};

    private final SimpleDateFormat dayFmt = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
    private final SimpleDateFormat displayFmt = new SimpleDateFormat("d MMM yyyy", new Locale("id", "ID"));

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_promo_customers);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        customerDao = new CustomerDao(DatabaseHelper.getInstance(this));

        tvStatTotal = findViewById(R.id.tvStatTotal);
        tvStatConverted = findViewById(R.id.tvStatConverted);
        tvStatRate = findViewById(R.id.tvStatRate);
        tvStatDays = findViewById(R.id.tvStatDays);
        tvEmpty = findViewById(R.id.tvEmpty);
        etSearch = findViewById(R.id.etSearch);
        togglePreset = findViewById(R.id.togglePreset);
        toggleStatus = findViewById(R.id.toggleStatus);

        RecyclerView rv = findViewById(R.id.rv);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setHasFixedSize(true);
        adapter = new PromoAdapter();
        rv.setAdapter(adapter);

        togglePreset.addOnButtonCheckedListener((g, id, checked) -> {
            if (!checked) return;
            if (id == R.id.btnPresetCustom) return;   // dialog dibuka oleh click listener di bawah
            lastPresetId = id;
            applyPreset(id);
            reloadCohort();
        });
        // "Kustom…" lewat OnClick (bukan toggle): singleSelection + selectionRequired tidak
        // memancarkan event saat tombol yang SUDAH terpilih diketuk lagi — tanpa ini dialog
        // rentang tak bisa dibuka ulang. Click listener selalu terpanggil.
        findViewById(R.id.btnPresetCustom).setOnClickListener(v -> pickCustomRange());
        toggleStatus.addOnButtonCheckedListener((g, id, checked) -> { if (checked) refreshList(); });
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { refreshList(); }
        });

        toggleStatus.check(R.id.btnStatusAll);
        togglePreset.check(R.id.btnPresetMonth);   // default = bulan ini (memicu reloadCohort)
    }

    @Override
    protected void onResume() {
        super.onResume();
        reloadCohort();   // data bisa berubah (sync/transaksi baru) saat activity di background
    }

    /** Aksi per pelanggan promosi: tarik galon promosinya (yang tak konversi) atau buka detail. */
    private void showRowActions(CustomerDao.PromoRow r) {
        String[] items = {
                "📥 Tarik Galon Promosi",
                "👤 Lihat Detail Pelanggan",
        };
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(r.name != null ? r.name : "Pelanggan")
                .setItems(items, (d, which) -> {
                    if (which == 0) TarikPromosi.show(this, r.id, this::reloadCohort);
                    else startActivity(new Intent(this, CustomerDetailActivity.class)
                            .putExtra("customer_id", r.id));
                })
                .setNegativeButton("Tutup", null)
                .show();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 1, 0, "Urutkan").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == 1) {
            new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Urutkan")
                    .setSingleChoiceItems(SORT_LABELS, sortMode, (d, which) -> {
                        sortMode = which;
                        d.dismiss();
                        refreshList();
                    })
                    .show();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /** Terjemahkan preset terpilih → [startDay, endDay]. */
    private void applyPreset(int id) {
        Calendar cal = Calendar.getInstance();
        String today = dayFmt.format(cal.getTime());
        if (id == R.id.btnPresetToday) {
            startDay = today; endDay = today;
        } else if (id == R.id.btnPresetWeek) {
            cal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY);
            // Locale Sunday-first (id_ID/en_US): saat ini hari MINGGU, set(MONDAY) melompat ke
            // Senin BESOK (minggu kalender yang sama) → rentang terbalik & daftar selalu kosong
            // tiap Minggu. Mundurkan seminggu bila hasilnya melewati hari ini.
            if (dayFmt.format(cal.getTime()).compareTo(today) > 0) {
                cal.add(Calendar.DAY_OF_YEAR, -7);
            }
            startDay = dayFmt.format(cal.getTime()); endDay = today;
        } else if (id == R.id.btnPresetMonth) {
            cal.set(Calendar.DAY_OF_MONTH, 1);
            startDay = dayFmt.format(cal.getTime()); endDay = today;
        } else if (id == R.id.btnPreset3m) {
            cal.add(Calendar.DAY_OF_YEAR, -90);
            startDay = dayFmt.format(cal.getTime()); endDay = today;
        } else if (id == R.id.btnPresetAll) {
            startDay = null; endDay = null;
        }
    }

    /** "Kustom…": pilih tanggal awal lalu akhir lewat dua DatePicker beruntun. Dibatalkan di
     *  tengah → kembalikan centang ke preset non-kustom terakhir (rentang lama tetap berlaku). */
    private void pickCustomRange() {
        Calendar cal = Calendar.getInstance();
        DatePickerDialog first = new DatePickerDialog(this, (v, y, m, d) -> {
            Calendar s = Calendar.getInstance();
            s.set(y, m, d);
            String picked = dayFmt.format(s.getTime());
            DatePickerDialog second = new DatePickerDialog(this, (v2, y2, m2, d2) -> {
                Calendar e = Calendar.getInstance();
                e.set(y2, m2, d2);
                startDay = picked;
                endDay = dayFmt.format(e.getTime());
                if (startDay.compareTo(endDay) > 0) { String t = startDay; startDay = endDay; endDay = t; }
                reloadCohort();
            }, y, m, d);
            second.setTitle("Sampai tanggal");
            second.setOnCancelListener(dlg -> revertToLastPreset());
            second.show();
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH));
        first.setTitle("Dari tanggal");
        first.setOnCancelListener(dlg -> revertToLastPreset());
        first.show();
    }

    private void revertToLastPreset() {
        if (lastPresetId != 0) togglePreset.check(lastPresetId);
    }

    /** Query ulang kohort dari DB untuk rentang tanggal aktif, lalu refresh statistik + daftar.
     *  Pencarian & filter status diterapkan di memori ({@link #refreshList}) supaya statistik
     *  selalu menggambarkan SELURUH kohort rentang (selaras halaman web). */
    private void reloadCohort() {
        cohort.clear();
        cohort.addAll(customerDao.getPromoCustomers(startDay, endDay, null));

        int total = cohort.size(), converted = 0;
        long daySum = 0;
        for (CustomerDao.PromoRow r : cohort) {
            if (r.repeatDay != null) { converted++; daySum += r.days; }
        }
        tvStatTotal.setText(String.valueOf(total));
        tvStatConverted.setText(String.valueOf(converted));
        tvStatRate.setText(total > 0 ? Math.round(converted * 100f / total) + "%" : "—");
        tvStatDays.setText(converted > 0
                ? String.format(new Locale("id", "ID"), "%.1f", daySum / (float) converted) : "—");

        refreshList();
    }

    /** Terapkan pencarian + filter status + urutan di memori → tampilkan. */
    private void refreshList() {
        String q = etSearch.getText() != null
                ? etSearch.getText().toString().trim().toLowerCase(Locale.ROOT) : "";
        int statusId = toggleStatus.getCheckedButtonId();

        shown.clear();
        for (CustomerDao.PromoRow r : cohort) {
            if (statusId == R.id.btnStatusConverted && r.repeatDay == null) continue;
            if (statusId == R.id.btnStatusPending && r.repeatDay != null) continue;
            if (!q.isEmpty()) {
                String name = r.name != null ? r.name.toLowerCase(Locale.ROOT) : "";
                String phone = r.phone != null ? r.phone : "";
                if (!name.contains(q) && !phone.contains(q)) continue;
            }
            shown.add(r);
        }

        Collections.sort(shown, (a, b) -> {
            switch (sortMode) {
                case 1: return cmp(a.promoDay, b.promoDay);                       // promo terlama
                case 2: return cmp(nvl(b.repeatDay), nvl(a.repeatDay));           // order ulang terbaru
                case 3:                                                            // konversi tercepat
                    int da = a.repeatDay != null ? a.days : Integer.MAX_VALUE;
                    int dbv = b.repeatDay != null ? b.days : Integer.MAX_VALUE;
                    return Integer.compare(da, dbv);
                case 4: return nvl(a.name).compareToIgnoreCase(nvl(b.name));      // nama A–Z
                default: return cmp(b.promoDay, a.promoDay);                       // promo terbaru
            }
        });

        adapter.notifyDataSetChanged();
        tvEmpty.setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private static int cmp(String a, String b) { return nvl(a).compareTo(nvl(b)); }

    private static String nvl(String s) { return s != null ? s : ""; }

    private String displayDay(String day) {
        try {
            return displayFmt.format(dayFmt.parse(day));
        } catch (ParseException | NullPointerException e) {
            return day != null ? day : "—";
        }
    }

    // ------------------------------------------------------------------ adapter

    private class PromoAdapter extends RecyclerView.Adapter<PromoAdapter.VH> {

        class VH extends RecyclerView.ViewHolder {
            final TextView tvName, tvPhone, tvPromo, tvRepeat, tvStatusChip;

            VH(View v) {
                super(v);
                tvName = v.findViewById(R.id.tvName);
                tvPhone = v.findViewById(R.id.tvPhone);
                tvPromo = v.findViewById(R.id.tvPromo);
                tvRepeat = v.findViewById(R.id.tvRepeat);
                tvStatusChip = v.findViewById(R.id.tvStatusChip);
                v.setOnClickListener(view -> {
                    int pos = getBindingAdapterPosition();
                    if (pos == RecyclerView.NO_POSITION) return;
                    showRowActions(shown.get(pos));
                });
            }
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_promo_customer, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            CustomerDao.PromoRow r = shown.get(position);
            h.tvName.setText(r.name != null ? r.name : "—");
            h.tvPhone.setText(r.phone != null && !r.phone.isEmpty() ? r.phone : "");
            h.tvPhone.setVisibility(r.phone != null && !r.phone.isEmpty() ? View.VISIBLE : View.GONE);
            h.tvPromo.setText("🎁 Diberi promosi: " + displayDay(r.promoDay)
                    + " · " + r.galon + " galon gratis");
            if (r.repeatDay != null) {
                h.tvRepeat.setText("🔁 Pembelian pertama: " + displayDay(r.repeatDay)
                        + " (" + r.days + " hari setelah promo)");
                h.tvRepeat.setTextColor(0xFF2E7D32);   // hijau
                h.tvStatusChip.setText("🔁 Order Ulang");
                h.tvStatusChip.setTextColor(0xFF2E7D32);
                h.tvStatusChip.setBackgroundColor(0x1A2E7D32);
            } else {
                h.tvRepeat.setText("⏳ Belum order ulang");
                h.tvRepeat.setTextColor(0xFFB26A00);   // amber
                h.tvStatusChip.setText("⏳ Belum");
                h.tvStatusChip.setTextColor(0xFFB26A00);
                h.tvStatusChip.setBackgroundColor(0x1AB26A00);
            }
        }

        @Override
        public int getItemCount() { return shown.size(); }
    }
}
