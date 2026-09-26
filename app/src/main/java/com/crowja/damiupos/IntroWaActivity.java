package com.crowja.damiupos;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
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

import com.crowja.damiupos.db.CustomerDao;
import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.SettingsDao;
import com.crowja.damiupos.sync.SyncApi;
import com.crowja.damiupos.sync.SyncSettings;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Menu "WA Perkenalan" untuk perangkat yang dicentang <b>Petugas WA Perkenalan</b> di dashboard:
 * antrean pelanggan promosi yang belum pernah disapa pesan perkenalan, satu ketuk = WhatsApp
 * terbuka dengan pesannya siap kirim.
 *
 * <p>Layar ini memuat kohort LENGKAP ({@link CustomerDao#getPromoIntroAll()}) lalu memisahnya:
 * yang belum disapa di atas, yang sudah disapa di bawah dengan kartu pendek. Angka LENCANA di
 * dashboard tetap memakai {@link CustomerDao#getPromoIntroPending()} — hanya yang BELUM — disaring
 * penugasan wilayah perangkat ini ({@link IntroWaDuty#filterPending}), penyaring yang sama dipakai
 * di sini. Jadi lencana selalu sama dengan jumlah kartu di kelompok ATAS layar ini, tidak ikut
 * membengkak oleh kelompok "sudah dikirim". Penyusunan pesan diserahkan ke server
 * ({@code /api/customers/{uuid}/intro-wa}), sama seperti jalur lama di Pelanggan Promosi: pelanggan
 * promo bisa diakuisisi perangkat lain, jadi harga efektif dan templatenya harus datang dari sisi
 * yang melihat seluruh cabang. Server pula yang menyetel stempel {@code promo_intro_wa_sent_at},
 * jadi antrean ini surut sendiri begitu perangkat mana pun mengirim.
 */
public class IntroWaActivity extends AppCompatActivity {

    private static final int REQ_CONTACTS = 71;

    private CustomerDao customerDao;
    private SettingsDao settingsDao;
    private SwipeRefreshLayout swipe;
    private TextView tvSummary, tvEmpty;
    private Adapter adapter;

    /** Baris yang dirender: campuran header + kartu, sudah berurut (belum di atas, sudah di bawah). */
    private final List<Object> rows = new ArrayList<>();
    /** Hanya yang BELUM disapa — dipakai ringkasan & harus sama dengan angka lencana dashboard. */
    private int pendingCount;
    /** Baris yang menunggu hasil permintaan izin kontak (lihat {@link #send}). */
    private CustomerDao.IntroPendingRow pendingRow;
    private boolean contactsAsked;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_intro_wa);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        DatabaseHelper db = DatabaseHelper.getInstance(this);
        customerDao = new CustomerDao(db);
        settingsDao = new SettingsDao(db);

        tvSummary = findViewById(R.id.tvSummary);
        tvEmpty = findViewById(R.id.tvEmpty);
        swipe = findViewById(R.id.swipe);
        swipe.setOnRefreshListener(() -> {
            // Tarik data server dulu: stempel "sudah dikirim" milik perangkat lain baru terlihat
            // setelah pull, jadi menyegarkan daftar tanpa sync hanya menampilkan antrean basi.
            com.crowja.damiupos.sync.SyncScheduler.syncNow(getApplicationContext());
            reload();
            swipe.setRefreshing(false);
        });

        RecyclerView rv = findViewById(R.id.rvIntro);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new Adapter();
        rv.setAdapter(adapter);

        reload();
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();   // kembali dari WhatsApp: baris yang barusan dikirim hilang dari antrean
        // Antrean ini milik SATU CABANG, bukan satu perangkat: web dan HP lain juga menyapa
        // pelanggan yang sama. Data lokal hanya sesegar pull terakhir, jadi layar ini menarik
        // data begitu dibuka lalu memuat ulang sendiri saat sync mendarat — tanpa itu petugas
        // bekerja dari daftar basi dan menyapa orang yang sudah disapa rekannya.
        android.content.IntentFilter f = new android.content.IntentFilter(
                com.crowja.damiupos.sync.SyncEngine.ACTION_SYNCED);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(syncedReceiver, f, android.content.Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(syncedReceiver, f);
        }
        com.crowja.damiupos.sync.SyncScheduler.syncNow(getApplicationContext());
    }

    @Override
    protected void onPause() {
        super.onPause();
        try { unregisterReceiver(syncedReceiver); } catch (Throwable ignored) {}
    }

    /** Sync membawa stempel "sudah dikirim" dari web/perangkat lain → antrean menyusut sendiri. */
    private final android.content.BroadcastReceiver syncedReceiver = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(android.content.Context ctx, Intent intent) {
            if (!isFinishing() && !isDestroyed()) reload();
        }
    };

    /**
     * Susun ulang daftar: yang BELUM disapa di atas (kartu penuh, bisa diketuk untuk mengirim),
     * lalu pemisah, lalu yang SUDAH disapa di bawah dengan kartu pendek.
     *
     * <p>Status "sudah/belum" dibaca dari {@code promo_intro_wa_sent_at} — kolom SERVER yang sama
     * persis dengan kolom "WA Perkenalan" pada laporan Pelanggan Promosi di web. Stempelnya ikut
     * turun pada pull biasa, jadi pelanggan yang disapa lewat dashboard atau HP rekan langsung
     * pindah ke kelompok bawah di sini tanpa perlu jalur sinkron tersendiri.
     */
    private void reload() {
        rows.clear();
        pendingCount = 0;
        List<CustomerDao.IntroPendingRow> sent = new ArrayList<>();
        try {
            SyncSettings cfg = new SyncSettings(settingsDao);
            for (CustomerDao.IntroPendingRow r : IntroWaDuty.filterPending(customerDao.getPromoIntroAll(), cfg)) {
                if (r.introSentAt == null || r.introSentAt.isEmpty()) {
                    rows.add(r);          // kelompok atas, sudah terurut dari query (terlama dulu)
                    pendingCount++;
                } else {
                    sent.add(r);
                }
            }
        } catch (Exception ignored) {}
        if (!sent.isEmpty()) {
            rows.add("✅ Sudah dikirim (" + sent.size() + ")");
            rows.addAll(sent);
        }
        adapter.notifyDataSetChanged();

        tvEmpty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
        if (pendingCount == 0) {
            tvSummary.setText(sent.isEmpty()
                    ? "Belum ada pelanggan promosi di wilayah tugas Anda."
                    : "Semua pelanggan promosi di wilayah tugas Anda sudah disapa.");
        } else {
            tvSummary.setText(pendingCount + " pelanggan menunggu disapa - urut dari yang paling lama.");
        }
    }

    /**
     * Susun pesan di server lalu buka WhatsApp. Nomor disimpan ke kontak dulu (sekali, dedup di
     * {@link com.crowja.damiupos.wa.WaContactEnsure}) karena WhatsApp hanya memperlakukan chat
     * sepenuhnya untuk nomor tersimpan. Izin kontak ditolak bukan penghalang: menyapa pelanggan
     * tetap jalan, penyimpanan kontaknya saja yang dilewati.
     */
    private void send(CustomerDao.IntroPendingRow r) {
        if (r.uuid == null || r.uuid.isEmpty()) {
            Toast.makeText(this, "Pelanggan belum tersinkron, coba lagi nanti", Toast.LENGTH_SHORT).show();
            return;
        }
        SyncSettings cfg = new SyncSettings(settingsDao);
        if (!cfg.isEnrolled()) {
            Toast.makeText(this, "Perangkat belum terhubung ke server", Toast.LENGTH_SHORT).show();
            return;
        }
        if (r.phone != null && !r.phone.trim().isEmpty()) {
            if (com.crowja.damiupos.wa.WaContactEnsure.canWrite(this)) {
                try { com.crowja.damiupos.wa.WaContactEnsure.ensure(this, r.name, r.phone); }
                catch (Throwable ignored) {}
            } else if (!contactsAsked && pendingRow == null) {
                pendingRow = r;
                contactsAsked = true;
                androidx.core.app.ActivityCompat.requestPermissions(this,
                        new String[]{android.Manifest.permission.WRITE_CONTACTS,
                                android.Manifest.permission.READ_CONTACTS},
                        REQ_CONTACTS);
                return;   // dilanjutkan dari onRequestPermissionsResult
            }
        }
        new Thread(() -> {
            JSONObject res;
            try {
                res = new SyncApi(cfg).introWa(r.uuid, r.promoDay);
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this,
                        "Gagal menyiapkan pesan WA Perkenalan", Toast.LENGTH_SHORT).show());
                return;
            }
            String text = res.optString("text", "");
            String link = res.isNull("link") ? null : res.optString("link", null);
            // Server menjawab kapan pelanggan ini terakhir disapa (null = belum pernah). Ini
            // pemeriksaan TERAKHIR sebelum WhatsApp terbuka, dan satu-satunya yang melihat keadaan
            // server saat ini — daftar di layar hanya sesegar pull terakhir, jadi baris bisa saja
            // baru saja disapa lewat web atau HP rekan beberapa detik lalu.
            String sentAt = res.isNull("already_sent_at") ? null : res.optString("already_sent_at", null);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (sentAt != null && !sentAt.isEmpty()) {
                    new androidx.appcompat.app.AlertDialog.Builder(this)
                            .setTitle("Sudah Pernah Disapa")
                            .setMessage(r.name + " sudah dikirimi WA Perkenalan pada "
                                    + prettyStamp(sentAt) + " (lewat web atau perangkat lain).\n\n"
                                    + "Kirim ulang?")
                            .setNegativeButton("Batal", (d, w) -> reload())
                            .setPositiveButton("Kirim Ulang", (d, w) -> {
                                sendIntro(r, text, link);
                                reload();
                            })
                            .show();
                    return;
                }
                sendIntro(r, text, link);
            });
        }).start();
    }

    /** Kirim WA Perkenalan lewat WA Bridge server dulu (akun dipilih server sesuai prioritas);
     *  Bridge gagal → buka WhatsApp di HP ini seperti dulu ({@link #openWa}). */
    private void sendIntro(CustomerDao.IntroPendingRow r, String text, String link) {
        com.crowja.damiupos.wa.WaBridgeSend.sendOrFallback(this,
                com.crowja.damiupos.wa.WaBridgeSend.Msg.to(r.phone, text)
                        .type("intro").proactive().customerUuid(r.uuid),
                this::reload,
                () -> openWa(r.phone, text, link));
    }

    /** "2026-08-30T14:05:00+07:00" → "30 Agt 2026, 14:05"; kembalikan apa adanya bila tak terbaca. */
    private static String prettyStamp(String iso) {
        try {
            String s = iso.length() >= 16 ? iso.substring(0, 16) : iso;   // yyyy-MM-ddTHH:mm
            java.text.SimpleDateFormat in = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm", java.util.Locale.US);
            java.text.SimpleDateFormat out = new java.text.SimpleDateFormat("d MMM yyyy, HH:mm",
                    new java.util.Locale("id", "ID"));
            return out.format(in.parse(s));
        } catch (Exception e) {
            return iso;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CONTACTS && pendingRow != null) {
            CustomerDao.IntroPendingRow r = pendingRow;
            pendingRow = null;
            send(r);   // izin ditolak pun lanjut: canWrite() false, simpan kontak dilewati
        }
    }

    /** Pakai {@code link} dari server bila ada (nomor + teks sudah ter-encode di sana), else
     *  susun wa.me sendiri dari nomor lokal. */
    private void openWa(String phone, String text, String link) {
        Uri uri;
        if (link != null && !link.isEmpty()) {
            uri = Uri.parse(link);
        } else if (phone != null && !phone.trim().isEmpty()) {
            String n = phone.trim().replaceAll("[^0-9]", "");
            if (n.startsWith("0")) n = "62" + n.substring(1);
            else if (!n.startsWith("62")) n = "62" + n;
            uri = Uri.parse("https://wa.me/" + n + "?text=" + Uri.encode(text));
        } else {
            Toast.makeText(this, "Pelanggan ini belum punya nomor HP", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (Exception e) {
            Toast.makeText(this, "WhatsApp tidak ditemukan", Toast.LENGTH_SHORT).show();
        }
    }

    /** "menunggu N hari" dari tanggal promo (atau tanggal daftar) - alasan urutan daftar. */
    private static String waitingLabel(CustomerDao.IntroPendingRow r) {
        String day = r.promoDay != null ? r.promoDay
                : (r.createdAt != null && r.createdAt.length() >= 10 ? r.createdAt.substring(0, 10) : null);
        if (day == null) return "";
        try {
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
            long ms = System.currentTimeMillis() - f.parse(day).getTime();
            long days = ms / (24L * 60 * 60 * 1000);
            if (days <= 0) return "⏳ terdaftar hari ini";
            return "⏳ menunggu " + days + " hari";
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Tiga jenis baris: kartu "belum disapa", pemisah, dan kartu pendek "sudah disapa".
     *
     * <p>Satu RecyclerView dengan tiga tipe, bukan dua daftar terpisah, supaya keduanya menggulir
     * sebagai satu halaman — kelompok "sudah" memang harus berada DI BAWAH kelompok "belum",
     * bukan di tab lain yang menyembunyikannya.
     */
    private class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        private static final int TYPE_PENDING = 0;
        private static final int TYPE_HEADER = 1;
        private static final int TYPE_SENT = 2;

        class VH extends RecyclerView.ViewHolder {
            final TextView tvName, tvPhone, tvWaiting;
            VH(View v) {
                super(v);
                tvName = v.findViewById(R.id.tvName);
                tvPhone = v.findViewById(R.id.tvPhone);
                tvWaiting = v.findViewById(R.id.tvWaiting);
            }
        }

        class SentVH extends RecyclerView.ViewHolder {
            final TextView tvName, tvSentAt;
            SentVH(View v) {
                super(v);
                tvName = v.findViewById(R.id.tvName);
                tvSentAt = v.findViewById(R.id.tvSentAt);
            }
        }

        class HeaderVH extends RecyclerView.ViewHolder {
            final TextView tvHeader;
            HeaderVH(View v) {
                super(v);
                tvHeader = (TextView) v;
            }
        }

        @Override
        public int getItemViewType(int position) {
            Object o = rows.get(position);
            if (o instanceof String) {
                return TYPE_HEADER;
            }
            CustomerDao.IntroPendingRow r = (CustomerDao.IntroPendingRow) o;
            return (r.introSentAt == null || r.introSentAt.isEmpty()) ? TYPE_PENDING : TYPE_SENT;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inf = LayoutInflater.from(parent.getContext());
            if (viewType == TYPE_HEADER) {
                return new HeaderVH(inf.inflate(R.layout.item_intro_wa_header, parent, false));
            }
            if (viewType == TYPE_SENT) {
                return new SentVH(inf.inflate(R.layout.item_intro_wa_sent, parent, false));
            }
            return new VH(inf.inflate(R.layout.item_intro_wa, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int pos) {
            Object o = rows.get(pos);
            if (holder instanceof HeaderVH) {
                ((HeaderVH) holder).tvHeader.setText((String) o);
                return;
            }
            CustomerDao.IntroPendingRow r = (CustomerDao.IntroPendingRow) o;
            String name = r.name != null && !r.name.isEmpty() ? r.name : "(tanpa nama)";

            if (holder instanceof SentVH) {
                SentVH h = (SentVH) holder;
                h.tvName.setText(name);
                h.tvSentAt.setText(prettyStamp(r.introSentAt));
                // Tetap bisa diketuk: kadang perlu mengirim ulang (nomor salah, chat terhapus).
                h.itemView.setOnClickListener(v -> confirmResend(r));
                return;
            }

            VH h = (VH) holder;
            h.tvName.setText(name);
            boolean hasPhone = r.phone != null && !r.phone.trim().isEmpty();
            h.tvPhone.setText(hasPhone ? "📱 " + r.phone : "📱 belum ada nomor");
            String wait = waitingLabel(r);
            h.tvWaiting.setText(wait);
            h.tvWaiting.setVisibility(wait.isEmpty() ? View.GONE : View.VISIBLE);
            h.itemView.setOnClickListener(v -> send(r));
        }

        @Override
        public int getItemCount() { return rows.size(); }
    }

    /** Kirim ULANG ke pelanggan yang sudah pernah disapa — selalu lewat konfirmasi dulu. */
    private void confirmResend(CustomerDao.IntroPendingRow r) {
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Kirim Ulang?")
                .setMessage((r.name != null ? r.name : "Pelanggan") + " sudah dikirimi WA Perkenalan pada "
                        + prettyStamp(r.introSentAt) + ".\n\nKirim ulang?")
                .setNegativeButton("Batal", null)
                .setPositiveButton("Kirim Ulang", (d, w) -> send(r))
                .show();
    }
}
