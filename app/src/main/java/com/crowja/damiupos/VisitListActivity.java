package com.crowja.damiupos;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.crowja.damiupos.db.CustomerDao;
import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.SettingsDao;
import com.crowja.damiupos.db.UserDao;
import com.crowja.damiupos.model.Customer;
import com.crowja.damiupos.model.Transaction;
import com.google.android.material.button.MaterialButtonToggleGroup;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Daftar Kunjungan (permintaan staf marketing): pedoman kunjungan lapangan satu per satu —
 * pelanggan diurut dari yang PALING LAMA tidak order (yang belum pernah order dinilai dari
 * tanggal daftarnya), supaya pelanggan "tidak efektif"/lama tak order dikunjungi lebih dulu.
 *
 * <p>"Order terakhir" digabung LINTAS PERANGKAT per orang (grup dedup): MAX dari transaksi
 * lokal, srv_last_jual (agregat server, karena transaksi antar perangkat terisolasi), dan
 * handed_over_at ("Sudah Order Ulang" diperlakukan seperti order sungguhan — cermin web).
 * Tanda "Sudah Dikunjungi" disimpan LOKAL di perangkat ini saja (pedoman pribadi staf).
 */
public class VisitListActivity extends AppCompatActivity {

    /** Satu ORANG (grup dedup) pada daftar kunjungan. */
    private static class Entry {
        Customer rep;                 // wakil tampilan (salinan milik perangkat bila ada)
        long lastOrderMillis = Long.MIN_VALUE;   // MIN_VALUE = belum pernah order
        long sortMillis;              // lastOrder, atau tanggal daftar untuk yang belum pernah
        String visitedAt;             // MAX visited_at seluruh salinan (null = belum)
        boolean mine;                 // ada salinan created_by_name == staf yang login
        int trxSum, galonSum;         // agregat efektif dijumlah lintas salinan (cermin daftar pelanggan)
        final java.util.List<Long> visitedRowIds = new ArrayList<>();   // semua salinan bertanda (utk batal)

        long daysSince(long now) {
            return lastOrderMillis == Long.MIN_VALUE ? Long.MAX_VALUE
                    : Math.max(0, (now - lastOrderMillis) / 86400000L);
        }
    }

    private CustomerDao customerDao;
    private SettingsDao settingsDao;

    private TextView tvSummary, tvEmpty;
    private EditText etSearch;
    private CheckBox cbSaya, cbHideVisited;
    private MaterialButtonToggleGroup toggleThreshold;
    private VisitAdapter adapter;

    private final List<Entry> allEntries = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_visit_list);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        DatabaseHelper dbh = DatabaseHelper.getInstance(this);
        customerDao = new CustomerDao(dbh);
        settingsDao = new SettingsDao(dbh);

        tvSummary = findViewById(R.id.tvSummary);
        tvEmpty = findViewById(R.id.tvEmpty);
        etSearch = findViewById(R.id.etSearch);
        cbSaya = findViewById(R.id.cbSaya);
        cbHideVisited = findViewById(R.id.cbHideVisited);
        toggleThreshold = findViewById(R.id.toggleThreshold);

        RecyclerView rv = findViewById(R.id.rvVisits);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new VisitAdapter();
        rv.setAdapter(adapter);

        // Default: marketing biasanya fokus ke pelanggan yang DIA daftarkan sendiri.
        cbSaya.setChecked(UserDao.isCurrentUserMarketing(this));
        cbSaya.setOnCheckedChangeListener((b, c) -> refreshList());
        cbHideVisited.setOnCheckedChangeListener((b, c) -> refreshList());
        toggleThreshold.addOnButtonCheckedListener((g, id, checked) -> { if (checked) refreshList(); });
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { refreshList(); }
        });
        toggleThreshold.check(R.id.btnThAll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 1, 0, "Hapus Semua Tanda Kunjungan");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == 1) {
            new AlertDialog.Builder(this)
                    .setTitle("Mulai Putaran Baru?")
                    .setMessage("Hapus SEMUA tanda \"Sudah Dikunjungi\" di perangkat ini? "
                            + "Daftar kembali bersih untuk putaran kunjungan berikutnya.")
                    .setPositiveButton("YA, HAPUS SEMUA", (d, w) -> {
                        int n = customerDao.clearAllVisited();
                        Toast.makeText(this, n + " tanda kunjungan dihapus", Toast.LENGTH_SHORT).show();
                        reload();
                    })
                    .setNegativeButton("Batal", null)
                    .show();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /** Data siap? Sebelum muat pertama selesai, tampilkan "Memuat…" (bukan daftar kosong). */
    private boolean loaded = false;
    /** Nomor generasi muat — thread lama yang selesai belakangan tak boleh menimpa hasil baru. */
    private final java.util.concurrent.atomic.AtomicInteger loadGen = new java.util.concurrent.atomic.AtomicInteger();

    /** Muat ulang dari DB di background (bisa ribuan pelanggan di HP lama), lalu render. */
    private void reload() {
        final int gen = loadGen.incrementAndGet();
        new Thread(() -> {
            List<Customer> rows = customerDao.getVisitCandidates();
            String meName = settingsDao.getCurrentUserName();
            final String me = meName != null ? meName.trim() : "";

            // Gabungkan per ORANG (grup dedup): order terakhir = MAX(lokal, server, serah-terima)
            // seluruh salinan; tanda dikunjungi = MAX visited_at; wakil = salinan milik perangkat.
            Map<String, Entry> byKey = new LinkedHashMap<>();
            for (Customer c : rows) {
                String key = CustomerDao.dedupKey(c);
                Entry e = byKey.get(key);
                if (e == null) {
                    e = new Entry();
                    e.rep = c;
                    byKey.put(key, e);
                } else if (!e.rep.isMine() && c.isMine()) {
                    e.rep = c;   // utamakan salinan milik perangkat ini (data lokal lebih segar)
                }
                long last = Math.max(CustomerDao.parseMillisOrMin(c.getLocalLastJual()),
                        Math.max(CustomerDao.parseMillisOrMin(c.getSrvLastJual()),
                                CustomerDao.parseMillisOrMin(c.getHandedOverAt())));
                if (last > e.lastOrderMillis) e.lastOrderMillis = last;
                e.trxSum += c.effectiveTrx();
                e.galonSum += c.effectiveOrdered();
                if (c.getVisitedAt() != null && !c.getVisitedAt().isEmpty()) {
                    e.visitedRowIds.add(c.getId());
                    if (e.visitedAt == null || c.getVisitedAt().compareTo(e.visitedAt) > 0) {
                        e.visitedAt = c.getVisitedAt();
                    }
                }
                // "Pelanggan Saya": pencatat = staf login; baris lama tanpa created_by_name
                // (pra-v51) jatuh ke is_mine (milik perangkat) supaya tak hilang dari daftar.
                if (!me.isEmpty() && c.getCreatedByName() != null
                        && me.equalsIgnoreCase(c.getCreatedByName().trim())) {
                    e.mine = true;
                } else if ((c.getCreatedByName() == null || c.getCreatedByName().isEmpty()) && c.isMine()) {
                    e.mine = true;
                }
            }
            for (Entry e : byKey.values()) {
                // Belum pernah order → nilai urut = tanggal daftar (yang terdahulu dikunjungi dulu).
                e.sortMillis = e.lastOrderMillis != Long.MIN_VALUE
                        ? e.lastOrderMillis
                        : CustomerDao.parseMillisOrMin(e.rep.getCreatedAt());
            }

            List<Entry> merged = new ArrayList<>(byKey.values());
            // Yang belum dikunjungi dulu; lalu aktivitas paling lama (paling kecil) lebih dulu.
            merged.sort(Comparator
                    .comparing((Entry e) -> e.visitedAt != null)
                    .thenComparingLong(e -> e.sortMillis));

            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (gen != loadGen.get()) return;   // sudah ada muat yang lebih baru
                loaded = true;
                allEntries.clear();
                allEntries.addAll(merged);
                refreshList();
            });
        }).start();
    }

    /** Terapkan filter (ambang hari / belum pernah / pelanggan saya / cari / sembunyikan dikunjungi). */
    private void refreshList() {
        if (!loaded) return;   // muat pertama belum selesai → biarkan "Memuat…" (jangan flash kosong)
        long now = System.currentTimeMillis();
        int checked = toggleThreshold.getCheckedButtonId();
        int minDays = checked == R.id.btnTh7 ? 7 : checked == R.id.btnTh30 ? 30
                : checked == R.id.btnTh60 ? 60 : 0;
        boolean neverOnly = checked == R.id.btnThNever;
        String q = etSearch.getText() != null
                ? etSearch.getText().toString().trim().toLowerCase(Locale.ROOT) : "";

        List<Entry> out = new ArrayList<>();
        int visitedCount = 0;   // progres putaran: dihitung SEBELUM filter sembunyikan-dikunjungi
        for (Entry e : allEntries) {
            boolean never = e.lastOrderMillis == Long.MIN_VALUE;
            if (neverOnly && !never) continue;
            // Ambang hari: yang belum pernah order dianggap paling lama (selalu lolos ambang).
            if (minDays > 0 && !never && e.daysSince(now) < minDays) continue;
            if (cbSaya.isChecked() && !e.mine) continue;
            if (!q.isEmpty()) {
                Customer c = e.rep;
                String hay = ((c.getName() != null ? c.getName() : "") + " "
                        + (c.getPhone() != null ? c.getPhone() : "") + " "
                        + (c.getAddress() != null ? c.getAddress() : "")).toLowerCase(Locale.ROOT);
                if (!hay.contains(q)) continue;
            }
            if (e.visitedAt != null) visitedCount++;
            if (cbHideVisited.isChecked() && e.visitedAt != null) continue;
            out.add(e);
        }

        adapter.setData(out);
        tvEmpty.setVisibility(out.isEmpty() ? View.VISIBLE : View.GONE);
        tvSummary.setText(out.size() + " pelanggan dalam daftar kunjungan · "
                + visitedCount + " sudah dikunjungi. Urut dari yang paling lama tidak order.");
    }

    // ----------------------------------------------------------------- aksi per pelanggan

    /** Pin berlabel di Google Maps (pola sama dengan Follow Up). */
    private void openInMaps(Customer c) {
        double lat = c.getLatitude(), lng = c.getLongitude();
        if (lat == 0 && lng == 0) {
            String addr = c.getAddress();
            if (addr == null || addr.trim().isEmpty()) {
                Toast.makeText(this, "Lokasi pelanggan belum diset", Toast.LENGTH_SHORT).show();
                return;
            }
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(
                        "https://www.google.com/maps/search/?api=1&query=" + Uri.encode(addr))));
            } catch (Exception e) {
                Toast.makeText(this, "Tidak ada aplikasi peta", Toast.LENGTH_SHORT).show();
            }
            return;
        }
        String label = c.getName() != null ? c.getName() : "Pelanggan";
        Intent gmaps = new Intent(Intent.ACTION_VIEW, Uri.parse(
                "geo:" + lat + "," + lng + "?q=" + lat + "," + lng + "(" + Uri.encode(label) + ")"));
        gmaps.setPackage("com.google.android.apps.maps");
        try {
            if (gmaps.resolveActivity(getPackageManager()) != null) startActivity(gmaps);
            else startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(
                    "https://www.google.com/maps/search/?api=1&query=" + lat + "," + lng)));
        } catch (Exception e) {
            Toast.makeText(this, "Tidak ada aplikasi peta", Toast.LENGTH_SHORT).show();
        }
    }

    private void toggleVisited(Entry e) {
        boolean visited = e.visitedAt != null;
        if (visited) {
            // Batalkan di SEMUA salinan bertanda (grup dedup) — satu klik benar-benar bersih.
            for (long id : e.visitedRowIds) customerDao.setVisited(id, false);
        } else {
            customerDao.setVisited(e.rep.getId(), true);
        }
        Toast.makeText(this, !visited
                ? "✔ " + safe(e.rep.getName()) + " ditandai sudah dikunjungi"
                : "Tanda kunjungan " + safe(e.rep.getName()) + " dibatalkan", Toast.LENGTH_SHORT).show();
        reload();
    }

    /**
     * Kirim WA follow-up dari daftar kunjungan. Template WA memakai {@code createdAt} sebagai
     * "terakhir beli" (konvensi layar Follow Up yang meng-overload kolom itu) — sedangkan baris
     * kita membawa tanggal DAFTAR asli, jadi tanpa penyesuaian pesan WA menyebut tanggal daftar
     * sebagai tanggal beli terakhir. Salin dulu tanggal order gabungan (lintas perangkat) ke rep.
     */
    private void openWa(Entry e) {
        Customer c = e.rep;
        String prevCreatedAt = c.getCreatedAt();
        if (e.lastOrderMillis != Long.MIN_VALUE) {
            c.setCreatedAt(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                    .format(new Date(e.lastOrderMillis)));
        }
        try {
            // Stempel follow-up hanya untuk salinan MILIK perangkat ini — syncUpdate pada salinan
            // perangkat lain men-dirty barisnya dan push LWW bisa menimpa edit pemiliknya.
            WhatsAppFollowUp.open(this, c, settingsDao, customerDao, c.isMine());
        } finally {
            c.setCreatedAt(prevCreatedAt);   // pulihkan tanggal daftar (dipakai baris "terdaftar …")
        }
    }

    private void showActions(Entry e) {
        Customer c = e.rep;
        boolean visited = e.visitedAt != null;
        String[] items = {
                visited ? "↩ Batalkan Tanda Dikunjungi" : "✔ Tandai Sudah Dikunjungi",
                "🧭 Buka di Peta",
                "💬 Kirim Pesan WhatsApp",
                "🧾 Buat Transaksi",
                "📥 Tarik Galon Promosi",
                "👤 Lihat Detail Pelanggan",
        };
        new AlertDialog.Builder(this)
                .setTitle(safe(c.getName()))
                .setItems(items, (d, which) -> {
                    if (which == 0) toggleVisited(e);
                    else if (which == 1) openInMaps(c);
                    else if (which == 2) openWa(e);
                    else if (which == 3) startActivity(new Intent(this, TransactionActivity.class)
                            .putExtra("type", Transaction.TYPE_JUAL)
                            .putExtra("customer_id", c.getId()));
                    else if (which == 4) TarikPromosi.show(this, c.getId(), this::reload);
                    else startActivity(new Intent(this, CustomerDetailActivity.class)
                            .putExtra("customer_id", c.getId()));
                })
                .setNegativeButton("Tutup", null)
                .show();
    }

    private static String safe(String s) { return s != null ? s : "-"; }

    // ----------------------------------------------------------------- adapter

    private class VisitAdapter extends RecyclerView.Adapter<VisitAdapter.VH> {

        private final List<Entry> data = new ArrayList<>();
        private final SimpleDateFormat outFmt = new SimpleDateFormat("d MMM yyyy", new Locale("id", "ID"));
        private final SimpleDateFormat visitFmt = new SimpleDateFormat("d MMM HH:mm", new Locale("id", "ID"));

        void setData(List<Entry> list) {
            data.clear();
            data.addAll(list);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_visit_customer, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            Entry e = data.get(pos);
            Customer c = e.rep;
            long now = System.currentTimeMillis();
            boolean never = e.lastOrderMillis == Long.MIN_VALUE;

            String name = c.getDisplayName();
            h.tvInitial.setText(name != null && !name.isEmpty()
                    ? name.substring(0, 1).toUpperCase(Locale.ROOT) : "?");
            h.tvName.setText(safe(name));

            StringBuilder sub = new StringBuilder();
            if (c.getPhone() != null && !c.getPhone().trim().isEmpty()) sub.append("📞 ").append(c.getPhone().trim());
            if (c.getAddress() != null && !c.getAddress().trim().isEmpty()) {
                if (sub.length() > 0) sub.append("  ·  ");
                sub.append("📍 ").append(c.getAddress().trim());
            }
            h.tvSub.setText(sub.length() > 0 ? sub : "Tanpa kontak/alamat");

            if (never) {
                h.tvDays.setText("BELUM");
                h.tvDays.setTextColor(0xFFB71C1C);
                h.tvDaysLabel.setText("pernah order");
            } else {
                long days = e.daysSince(now);
                h.tvDays.setText(String.valueOf(days));
                h.tvDays.setTextColor(days >= 30 ? 0xFFB71C1C : days >= 7 ? 0xFFE65100 : 0xFF2E7D32);
                h.tvDaysLabel.setText("hari tanpa order");
            }

            StringBuilder info = new StringBuilder();
            if (never) {
                info.append("Belum pernah order");
                if (c.getCreatedAt() != null && !c.getCreatedAt().isEmpty()) {
                    long reg = CustomerDao.parseMillisOrMin(c.getCreatedAt());
                    if (reg != Long.MIN_VALUE) {
                        info.append(" · terdaftar ").append(outFmt.format(new Date(reg)));
                    }
                }
            } else {
                info.append("Terakhir order: ").append(outFmt.format(new Date(e.lastOrderMillis)));
                // Agregat dijumlah lintas salinan (cermin daftar pelanggan) — bukan hanya wakil.
                if (e.trxSum > 0) info.append(" · ").append(e.trxSum).append(" trx · ")
                        .append(e.galonSum).append(" galon");
            }
            if (c.getCreatedByName() != null && !c.getCreatedByName().isEmpty()) {
                info.append(" · 👤 ").append(c.getCreatedByName());
            }
            h.tvInfo.setText(info);

            if (e.visitedAt != null) {
                long v = CustomerDao.parseMillisOrMin(e.visitedAt);
                h.tvVisited.setVisibility(View.VISIBLE);
                h.tvVisited.setText("✔ Sudah dikunjungi"
                        + (v != Long.MIN_VALUE ? " · " + visitFmt.format(new Date(v)) : ""));
                h.btnVisit.setText("↩ Batal");
            } else {
                h.tvVisited.setVisibility(View.GONE);
                h.btnVisit.setText("✔ Dikunjungi");
            }

            h.itemView.setAlpha(e.visitedAt != null ? 0.55f : 1f);
            h.btnNavigate.setOnClickListener(v -> openInMaps(c));
            h.btnWa.setOnClickListener(v -> openWa(e));
            h.btnVisit.setOnClickListener(v -> toggleVisited(e));
            h.itemView.setOnClickListener(v -> showActions(e));
            h.itemView.setOnLongClickListener(v -> {
                startActivity(new Intent(VisitListActivity.this, CustomerDetailActivity.class)
                        .putExtra("customer_id", c.getId()));
                return true;
            });
        }

        @Override
        public int getItemCount() { return data.size(); }

        class VH extends RecyclerView.ViewHolder {
            final TextView tvInitial, tvName, tvSub, tvDays, tvDaysLabel, tvInfo, tvVisited;
            final View btnNavigate, btnWa;
            final android.widget.Button btnVisit;

            VH(View v) {
                super(v);
                tvInitial = v.findViewById(R.id.tvInitial);
                tvName = v.findViewById(R.id.tvName);
                tvSub = v.findViewById(R.id.tvSub);
                tvDays = v.findViewById(R.id.tvDays);
                tvDaysLabel = v.findViewById(R.id.tvDaysLabel);
                tvInfo = v.findViewById(R.id.tvInfo);
                tvVisited = v.findViewById(R.id.tvVisited);
                btnNavigate = v.findViewById(R.id.btnNavigate);
                btnWa = v.findViewById(R.id.btnWa);
                btnVisit = v.findViewById(R.id.btnVisit);
            }
        }
    }
}
