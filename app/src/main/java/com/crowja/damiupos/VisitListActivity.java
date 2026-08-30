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
import android.widget.LinearLayout;
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
 * Daftar Kunjungan URGENT (staf marketing / admin): pelanggan yang DITANDAI "Kunjungi Urgent"
 * dari Follow Up — baik di web maupun di HP (admin) — dan belum dikunjungi.
 *
 * <p>Dulu daftar ini berisi SEMUA pelanggan diurut dari yang paling lama tidak order. Itu diganti:
 * isinya sekarang murni daftar bertanda, karena penentuan siapa yang perlu didatangi dipindah ke
 * Follow Up (di mana riwayat order & catatan pelanggannya kelihatan lengkap).
 *
 * <p>Tandanya TERSINKRON dua arah lewat kolom visit_urgent_*: web menandai, HP menyelesaikan.
 * Karena itu "Sudah Dikunjungi" di sini bukan lagi catatan lokal — ia menyetel visit_urgent_done_at
 * sehingga pelanggan hilang dari daftar ini DAN dari daftar di perangkat/web lain. Bentuk
 * dua-timestamp-nya (bukan boolean) dijelaskan di DatabaseHelper.COL_VISIT_URGENT_AT.
 *
 * <p>"Order terakhir" tetap digabung LINTAS PERANGKAT per orang (grup dedup): MAX dari transaksi
 * lokal, srv_last_jual (agregat server, karena transaksi antar perangkat terisolasi), dan
 * handed_over_at ("Sudah Order Ulang" diperlakukan seperti order sungguhan — cermin web).
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
        /** SEMUA salinan orang ini di perangkat ini. Menyelesaikan kunjungan harus menyentuh
         *  semuanya: tanda urgent bisa mendarat di salinan mana pun lewat sinkron, dan menutup
         *  satu salinan saja menyisakan yang lain tetap "menunggu" → orangnya muncul lagi. */
        final java.util.List<Long> allRowIds = new ArrayList<>();

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

        setTitle("Daftar Kunjungan Urgent");

        // Default: marketing biasanya fokus ke pelanggan yang DIA daftarkan sendiri.
        cbSaya.setChecked(UserDao.isCurrentUserMarketing(this));
        cbSaya.setOnCheckedChangeListener((b, c) -> refreshList());
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { refreshList(); }
        });

        // Ambang "sudah berapa lama tak order" dan "sembunyikan yang sudah dikunjungi" tidak lagi
        // punya arti: daftar ini SELALU berisi tepat yang bertanda dan belum dikunjungi. Kontrolnya
        // disembunyikan, bukan dihapus dari layout, supaya diff-nya kecil dan gampang dikembalikan
        // kalau daftar "paling lama tak order" suatu saat dihidupkan lagi sebagai tab terpisah.
        toggleThreshold.setVisibility(View.GONE);
        cbHideVisited.setVisibility(View.GONE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    // Menu "Hapus Semua Tanda Kunjungan" DIHAPUS bersama daftar lamanya: tanda kunjungan sekarang
    // tersinkron (menyelesaikan satu kunjungan mengabari web + perangkat lain), jadi menghapus
    // massal secara lokal justru akan menutup pekerjaan orang lain tanpa jejak.

    /** Data siap? Sebelum muat pertama selesai, tampilkan "Memuat…" (bukan daftar kosong). */
    private boolean loaded = false;
    /** Nomor generasi muat — thread lama yang selesai belakangan tak boleh menimpa hasil baru. */
    private final java.util.concurrent.atomic.AtomicInteger loadGen = new java.util.concurrent.atomic.AtomicInteger();

    /** Muat ulang dari DB di background (bisa ribuan pelanggan di HP lama), lalu render. */
    private void reload() {
        final int gen = loadGen.incrementAndGet();
        new Thread(() -> {
            // HANYA yang bertanda "Kunjungi Urgent" dan belum dikunjungi (definisi tunggal ada di
            // CustomerDao.WHERE_VISIT_URGENT — dipakai bersama oleh daftar ini dan badge).
            List<Customer> rows = customerDao.getVisitUrgent();
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
                e.allRowIds.add(c.getId());
                long last = Math.max(CustomerDao.parseMillisOrMin(c.getLocalLastJual()),
                        Math.max(CustomerDao.parseMillisOrMin(c.getSrvLastJual()),
                                CustomerDao.parseMillisOrMin(c.getHandedOverAt())));
                if (last > e.lastOrderMillis) e.lastOrderMillis = last;
                e.trxSum += c.effectiveTrx();
                e.galonSum += c.effectiveOrdered();
                if (c.getVisitedAt() != null && !c.getVisitedAt().isEmpty()) {
                    e.visitedRowIds.add(c.getId());
                    // Stempel campur (ISO-UTC dari server vs lokal dari HP) → bandingkan nilai.
                    if (e.visitedAt == null
                            || com.crowja.damiupos.util.Ts.after(c.getVisitedAt(), e.visitedAt)) {
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
            // Semua isi daftar ini sama-sama "belum dikunjungi", jadi yang membedakan tinggal
            // urgensinya: paling lama tak order didatangi lebih dulu.
            merged.sort(Comparator.comparingLong(e -> e.sortMillis));

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

    /** Terapkan filter yang masih relevan untuk daftar urgent: "pelanggan saya" + pencarian. */
    private void refreshList() {
        if (!loaded) return;   // muat pertama belum selesai → biarkan "Memuat…" (jangan flash kosong)
        String q = etSearch.getText() != null
                ? etSearch.getText().toString().trim().toLowerCase(Locale.ROOT) : "";

        List<Entry> out = new ArrayList<>();
        for (Entry e : allEntries) {
            if (cbSaya.isChecked() && !e.mine) continue;
            if (!q.isEmpty()) {
                Customer c = e.rep;
                String hay = ((c.getName() != null ? c.getName() : "") + " "
                        + (c.getPhone() != null ? c.getPhone() : "") + " "
                        + (c.getAddress() != null ? c.getAddress() : "")).toLowerCase(Locale.ROOT);
                if (!hay.contains(q)) continue;
            }
            out.add(e);
        }

        adapter.setData(out);
        tvEmpty.setVisibility(out.isEmpty() ? View.VISIBLE : View.GONE);
        // allEntries.size() (bukan out.size()) supaya angkanya tetap sepadan dengan badge di menu
        // utama saat staf sedang menyaring "Pelanggan Saya" atau mengetik pencarian.
        int total = allEntries.size();
        tvSummary.setText(out.size() == total
                ? total + " pelanggan menunggu kunjungan urgent. Paling lama tak order didahulukan."
                : out.size() + " dari " + total + " pelanggan menunggu kunjungan urgent.");
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

    /**
     * Selesaikan kunjungan urgent. Ditulis ke SEMUA salinan orang ini (grup dedup), bukan cuma ke
     * baris wakil: tandanya bisa datang dari web ke salinan mana pun, dan menyelesaikan satu salinan
     * saja akan menyisakan salinan lain tetap "menunggu" sehingga pelanggannya muncul lagi.
     *
     * <p>Tersinkron — begitu ter-push, pelanggan ini hilang juga dari daftar di perangkat lain dan
     * tombolnya di Follow Up web kembali jadi "Kunjungi Urgent".
     */
    private void markUrgentVisitDone(Entry e) {
        final EditText etReason = new EditText(this);
        etReason.setHint("Alasan / hasil kunjungan (wajib)");
        etReason.setMinLines(2);
        etReason.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        LinearLayout wrap = new LinearLayout(this);
        int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        wrap.setPadding(pad, 0, pad, 0);
        wrap.addView(etReason, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("Sudah dikunjungi?")
                .setMessage(safe(e.rep.getName()) + " dikeluarkan dari Daftar Kunjungan Urgent. "
                        + "Alasannya tercatat di Riwayat Perubahan pelanggan dan dikabarkan ke web.")
                .setView(wrap)
                .setPositiveButton("YA, SUDAH", null)   // di-override agar tak menutup saat kosong
                .setNegativeButton("Batal", null)
                .create();
        dlg.setOnShowListener(d -> dlg.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String reason = etReason.getText() != null
                            ? etReason.getText().toString().trim() : "";
                    // Wajib: tanpa alasan, "kenapa ini hilang dari daftar?" tak terjawab di web —
                    // dan justru itu yang membuat riwayatnya berguna.
                    if (reason.isEmpty()) { etReason.setError("Alasan wajib diisi"); return; }
                    dlg.dismiss();
                    new Thread(() -> {
                        for (long id : e.allRowIds) customerDao.markVisitUrgentDone(id, reason);
                        com.crowja.damiupos.sync.SyncScheduler.syncNow(getApplicationContext());
                        runOnUiThread(() -> {
                            if (isFinishing() || isDestroyed()) return;
                            Toast.makeText(this, "✔ " + safe(e.rep.getName()) + " sudah dikunjungi",
                                    Toast.LENGTH_SHORT).show();
                            reload();
                        });
                    }).start();
                }));
        dlg.show();
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
        String[] items = {
                "✔ Tandai Sudah Dikunjungi",
                TarikGalon.menuLabel(this),
                "🧭 Buka di Peta",
                "💬 Kirim Pesan WhatsApp",
                "🧾 Buat Transaksi",
                "👤 Lihat Detail Pelanggan",
        };
        new AlertDialog.Builder(this)
                .setTitle(safe(c.getName()))
                .setItems(items, (d, which) -> {
                    if (which == 0) markUrgentVisitDone(e);
                    else if (which == 1) TarikGalon.show(this, c.getId(), this::reload);
                    else if (which == 2) openInMaps(c);
                    else if (which == 3) openWa(e);
                    else if (which == 4) startActivity(new Intent(this, TransactionActivity.class)
                            .putExtra("type", Transaction.TYPE_JUAL)
                            .putExtra("customer_id", c.getId()));
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

            // Baris di daftar ini SELALU "belum dikunjungi", jadi slot ini dipakai menerangkan
            // ASAL tandanya (siapa & kapan) — itu yang membantu staf memutuskan urutan datang.
            long flagged = CustomerDao.parseMillisOrMin(c.getVisitUrgentAt());
            StringBuilder urg = new StringBuilder("🚩 Ditandai");
            if (flagged != Long.MIN_VALUE) urg.append(' ').append(visitFmt.format(new Date(flagged)));
            if (c.getVisitUrgentBy() != null && !c.getVisitUrgentBy().trim().isEmpty()) {
                urg.append(" · ").append(c.getVisitUrgentBy().trim());
            }
            h.tvVisited.setVisibility(View.VISIBLE);
            h.tvVisited.setText(urg);

            // Tombol Peta/WA/Dikunjungi tak ada lagi di kartu (duplikat dialog aksi) — kartu
            // ditekan untuk membuka dialognya, tekan-lama tetap langsung ke Detail Pelanggan.
            h.itemView.setAlpha(1f);
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

            VH(View v) {
                super(v);
                tvInitial = v.findViewById(R.id.tvInitial);
                tvName = v.findViewById(R.id.tvName);
                tvSub = v.findViewById(R.id.tvSub);
                tvDays = v.findViewById(R.id.tvDays);
                tvDaysLabel = v.findViewById(R.id.tvDaysLabel);
                tvInfo = v.findViewById(R.id.tvInfo);
                tvVisited = v.findViewById(R.id.tvVisited);
            }
        }
    }
}
