package com.crowja.damiupos;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
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
import com.crowja.damiupos.model.TransactionItem;
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

    // --- Mode pilih untuk "Buat Rute" Google Maps ---
    private boolean selectionMode = false;
    private final java.util.LinkedHashSet<Long> selectedIds = new java.util.LinkedHashSet<>();
    private View barRute;
    private TextView tvSelCount;
    private MaterialButton btnBuatRute;
    /** Batas titik pada URL Google Maps konsumen (destinasi + 9 waypoint). */
    private static final int MAX_ROUTE_STOPS = 10;

    private final Handler tick = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            adapter.refreshTimers();
            tick.postDelayed(this, 1000);
        }
    };

    /** Saat sinkron membawa data baru (mis. order di-route ke HP ini via "Efisiensikan Delivery" di
     *  web), muat ulang antrian supaya order pindah masuk/keluar tanpa keluar-masuk layar. Tidak saat
     *  mode pilih rute (agar seleksi tak terganggu). */
    private final BroadcastReceiver syncedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!selectionMode) loadData();
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
        rv.setHasFixedSize(true);
        rv.setAdapter(adapter);

        barRute = findViewById(R.id.barRute);
        tvSelCount = findViewById(R.id.tvSelCount);
        btnBuatRute = findViewById(R.id.btnBuatRute);
        btnBuatRute.setOnClickListener(v -> askPriorityThenRoute());
    }

    @Override
    public boolean onCreateOptionsMenu(android.view.Menu menu) {
        getMenuInflater().inflate(R.menu.menu_delivery_queue, menu);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(android.view.MenuItem item) {
        if (item.getItemId() == R.id.action_route_select) {
            if (selectionMode) exitSelectionMode();
            else enterSelectionMode();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onBackPressed() {
        if (selectionMode) { exitSelectionMode(); return; }
        super.onBackPressed();
    }

    private void enterSelectionMode() {
        if (adapter.getItemCount() == 0) {
            Toast.makeText(this, "Antrian kosong", Toast.LENGTH_SHORT).show();
            return;
        }
        selectionMode = true;
        selectedIds.clear();
        barRute.setVisibility(View.VISIBLE);
        updateSelectionUi();
        adapter.notifyDataSetChanged();
        Toast.makeText(this, "Pilih order lalu tekan Buat Rute", Toast.LENGTH_SHORT).show();
    }

    private void exitSelectionMode() {
        selectionMode = false;
        selectedIds.clear();
        barRute.setVisibility(View.GONE);
        adapter.notifyDataSetChanged();
    }

    private void toggleSelected(Transaction t) {
        if (selectedIds.contains(t.getId())) selectedIds.remove(t.getId());
        else selectedIds.add(t.getId());
        updateSelectionUi();
    }

    private void updateSelectionUi() {
        int n = selectedIds.size();
        tvSelCount.setText(n + " dipilih");
        btnBuatRute.setEnabled(n > 0);
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadData();
        tick.postDelayed(ticker, 1000);
        IntentFilter f = new IntentFilter(com.crowja.damiupos.sync.SyncEngine.ACTION_SYNCED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(syncedReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(syncedReceiver, f);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        tick.removeCallbacks(ticker);
        try { unregisterReceiver(syncedReceiver); } catch (Exception ignored) {}
    }

    private void loadData() {
        List<Transaction> list = dao.getDeliveryQueue();
        adapter.setData(list);
        tvSummary.setText(list.size() + " order menunggu diproses");
        tvEmpty.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
        rv.setVisibility(list.isEmpty() ? View.GONE : View.VISIBLE);
    }

    /** Buka navigasi peta ke lokasi TUJUAN order — lokasi terpilih "Kirim ke" bila ada,
     *  fallback koordinat pelanggan → fallback alamat. */
    private void navigate(Transaction t) {
        Intent i;
        double lat = effectiveLat(t), lng = effectiveLng(t);
        if (lat != 0 || lng != 0) {
            Uri nav = Uri.parse("google.navigation:q=" + lat + "," + lng);
            i = new Intent(Intent.ACTION_VIEW, nav).setPackage("com.google.android.apps.maps");
            if (i.resolveActivity(getPackageManager()) == null) {
                i = new Intent(Intent.ACTION_VIEW, Uri.parse(
                        "https://www.google.com/maps?q=" + lat + "," + lng));
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

    /**
     * Bangun link lacak publik pelanggan {trackBase}/tracking/{token} untuk order ini,
     * atau null (sambil menampilkan alasan) kalau belum bisa — belum terhubung ke server,
     * atau order dibuat sebelum fitur link lacak aktif (tanpa token). Domain link lacak
     * = order.airfrez.com (subdomain pelanggan), TERPISAH dari host API/dashboard.
     */
    private String trackLinkOrToast(Transaction t) {
        com.crowja.damiupos.sync.SyncSettings cfg = new com.crowja.damiupos.sync.SyncSettings(
                new com.crowja.damiupos.db.SettingsDao(DatabaseHelper.getInstance(this)));
        if (!cfg.isEnrolled()) {
            Toast.makeText(this, "Perangkat belum terhubung ke server (provisioning)",
                    Toast.LENGTH_LONG).show();
            return null;
        }
        String token = t.getDeliveryToken();
        if (token == null || token.isEmpty()) {
            Toast.makeText(this, "Order ini belum memiliki link lacak (dibuat sebelum fitur aktif)",
                    Toast.LENGTH_LONG).show();
            return null;
        }
        return cfg.getTrackBaseUrl() + "/tracking/" + token;
    }

    /** Salin link lacak pengiriman ke clipboard (untuk ditempel di mana saja). */
    private void copyTrackLink(Transaction t) {
        String link = trackLinkOrToast(t);
        if (link == null) return;
        android.content.ClipboardManager cb =
                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cb == null) {
            Toast.makeText(this, "Clipboard tidak tersedia", Toast.LENGTH_SHORT).show();
            return;
        }
        // Salin pesan "Kirim Link" lengkap (sapaan + link lacak), bukan hanya URL —
        // siap tempel ke chat mana pun.
        cb.setPrimaryClip(android.content.ClipData.newPlainText("Kirim Link", composeTrackMessage(t, link)));
        Toast.makeText(this, "Teks Kirim Link disalin ke clipboard", Toast.LENGTH_SHORT).show();
    }

    /** Pesan "Kirim Link" yang dibagikan ke pelanggan (sapaan + link lacak live). */
    private String composeTrackMessage(Transaction t, String link) {
        return "Halo " + safe(t.getCustomerName()) + ", pesanan air minum Anda sedang kami proses. "
                + "Pantau progres pengiriman & lokasi kurir secara langsung di sini:\n" + link;
    }

    /**
     * Kirim link lacak pengiriman (live) ke pelanggan via WhatsApp. Pelanggan
     * membuka {base}/track/{token} untuk memantau progres + lokasi kurir.
     */
    private void sendTrackLink(Transaction t) {
        String link = trackLinkOrToast(t);
        if (link == null) return;
        String phone = t.getCustomerPhone();
        if (phone == null || phone.trim().isEmpty()) {
            Toast.makeText(this, "Pelanggan belum memiliki nomor WhatsApp", Toast.LENGTH_SHORT).show();
            return;
        }
        String normalized = phone.replaceAll("[^0-9]", "");
        if (normalized.startsWith("0")) normalized = "62" + normalized.substring(1);
        else if (!normalized.startsWith("62")) normalized = "62" + normalized;

        String msg = composeTrackMessage(t, link);
        try {
            Intent i = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://wa.me/" + normalized + "?text=" + Uri.encode(msg)));
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

    /** Konfirmasi → tandai Selesai → sinkron. Struk tidak otomatis dibuka/dikirim; bila perlu,
     *  staf bisa membukanya manual dari detail order (tap order → "Buka Struk").
     *  KEMBALI = tugas pickup galon kosong (bukan antar), jadi teksnya menyesuaikan. */
    private void complete(Transaction t) {
        long ms = elapsedMillis(t.getDeliveryQueuedAt());
        boolean isPickup = Transaction.TYPE_KEMBALI.equals(t.getType());
        new AlertDialog.Builder(this)
                .setTitle(isPickup ? "Tandai Selesai (Pickup)" : "Tandai Selesai")
                .setMessage((isPickup
                        ? "Galon kembali dari \"" + safe(t.getCustomerName()) + "\" sudah diambil (pickup)?"
                        : "Order \"" + safe(t.getCustomerName()) + "\" sudah selesai diantar?")
                        + "\n\nLama proses: " + formatDuration(ms))
                .setPositiveButton("Selesai", (d, w) -> {
                    dao.markDelivered(t.getId());
                    com.crowja.damiupos.sync.SyncScheduler.syncNow(getApplicationContext());
                    loadData();
                    Toast.makeText(this, "Order selesai • " + formatDuration(ms), Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    // ---------------------------------------------------------- Buat Rute Google Maps

    /** Order terpilih dalam URUTAN ANTRIAN (queued_at ASC = urutan list) + punya koordinat. */
    private List<Transaction> selectedGeoStops() {
        List<Transaction> geo = new ArrayList<>();
        for (Transaction t : adapter.data) {
            if (selectedIds.contains(t.getId()) && hasGeo(t)) geo.add(t);
        }
        return geo;
    }

    /** Lat EFEKTIF tujuan order: lokasi terpilih "Kirim ke" (delivery_dest_*) menang bila
     *  non-nol; fallback koordinat pelanggan. (0,0) = tidak ada koordinat. */
    private static double effectiveLat(Transaction t) {
        return (t.getDeliveryDestLat() != 0 || t.getDeliveryDestLng() != 0)
                ? t.getDeliveryDestLat() : t.getCustomerLat();
    }

    /** Lng EFEKTIF tujuan order — lihat {@link #effectiveLat}. */
    private static double effectiveLng(Transaction t) {
        return (t.getDeliveryDestLat() != 0 || t.getDeliveryDestLng() != 0)
                ? t.getDeliveryDestLng() : t.getCustomerLng();
    }

    private static boolean hasGeo(Transaction t) {
        return effectiveLat(t) != 0 || effectiveLng(t) != 0;
    }

    /** Tanya prioritas rute → bangun & buka Google Maps. */
    private void askPriorityThenRoute() {
        int total = selectedIds.size();
        int withGeo = selectedGeoStops().size();
        if (withGeo == 0) {
            Toast.makeText(this, "Order terpilih belum punya titik koordinat — set lokasi pelanggan dulu",
                    Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Prioritas Rute")
                .setItems(new CharSequence[]{
                        "Efisiensi rute terpendek",
                        "Pesanan yang masuk dahulu"
                }, (d, which) -> {
                    boolean shortest = (which == 0);
                    if (shortest) {
                        // Anchor = lokasi kurir saat ini (async); fallback = order pertama.
                        LocationService.lastLocation(this, loc -> buildAndOpenRoute(true, loc));
                    } else {
                        buildAndOpenRoute(false, null);
                    }
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    /** {@code shortest} → urut nearest-neighbor dari {@code anchor} (atau order pertama bila null);
     *  selain itu → urutan antrian (FIFO). Origin URL dikosongkan = lokasi kurir saat link dibuka. */
    private void buildAndOpenRoute(boolean shortest, android.location.Location anchor) {
        int totalSelected = selectedIds.size();
        List<Transaction> stops = selectedGeoStops();
        int skippedNoGeo = totalSelected - stops.size();
        if (stops.isEmpty()) {
            Toast.makeText(this, "Tidak ada order dengan koordinat", Toast.LENGTH_LONG).show();
            return;
        }

        if (shortest) {
            double alat, alng;
            if (anchor != null) { alat = anchor.getLatitude(); alng = anchor.getLongitude(); }
            else { alat = effectiveLat(stops.get(0)); alng = effectiveLng(stops.get(0)); }
            stops = orderNearestNeighbor(stops, alat, alng);
        }

        boolean capped = false;
        if (stops.size() > MAX_ROUTE_STOPS) {
            stops = new ArrayList<>(stops.subList(0, MAX_ROUTE_STOPS));
            capped = true;
        }

        String url = buildMapsDirUrl(stops);
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(this, "Tidak ada aplikasi peta", Toast.LENGTH_SHORT).show();
            return;
        }
        StringBuilder note = new StringBuilder("Rute " + stops.size() + " tujuan dibuka");
        if (skippedNoGeo > 0) note.append(" · ").append(skippedNoGeo).append(" tanpa koordinat dilewati");
        if (capped) note.append(" · dibatasi ").append(MAX_ROUTE_STOPS).append(" tujuan (batas Google Maps)");
        Toast.makeText(this, note.toString(), Toast.LENGTH_LONG).show();
        exitSelectionMode();
    }

    /** Greedy nearest-neighbor: dari anchor, tiap langkah pilih tujuan terdekat berikutnya. */
    private static List<Transaction> orderNearestNeighbor(List<Transaction> stops, double lat, double lng) {
        List<Transaction> remaining = new ArrayList<>(stops);
        List<Transaction> route = new ArrayList<>(stops.size());
        double clat = lat, clng = lng;
        while (!remaining.isEmpty()) {
            int best = 0;
            double bestD = Double.MAX_VALUE;
            for (int i = 0; i < remaining.size(); i++) {
                double dd = haversineKm(clat, clng,
                        effectiveLat(remaining.get(i)), effectiveLng(remaining.get(i)));
                if (dd < bestD) { bestD = dd; best = i; }
            }
            Transaction next = remaining.remove(best);
            route.add(next);
            clat = effectiveLat(next);
            clng = effectiveLng(next);
        }
        return route;
    }

    /** URL arah Google Maps lintas-platform: origin dikosongkan (lokasi kurir), waypoint =
     *  semua kecuali terakhir, destination = terakhir. Order dijaga urutannya oleh Google. */
    private static String buildMapsDirUrl(List<Transaction> stops) {
        Transaction dest = stops.get(stops.size() - 1);
        StringBuilder url = new StringBuilder("https://www.google.com/maps/dir/?api=1&travelmode=driving");
        url.append("&destination=").append(coord(dest));
        if (stops.size() > 1) {
            StringBuilder wp = new StringBuilder();
            for (int i = 0; i < stops.size() - 1; i++) {
                if (i > 0) wp.append('|');
                wp.append(coord(stops.get(i)));
            }
            url.append("&waypoints=").append(Uri.encode(wp.toString()));
        }
        return url.toString();
    }

    private static String coord(Transaction t) {
        return effectiveLat(t) + "," + effectiveLng(t);
    }

    /** Jarak haversine (km) untuk urutan nearest-neighbor. */
    private static double haversineKm(double lat1, double lng1, double lat2, double lng2) {
        double r = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /** Tampilkan detail order (popup) saat item antrian diketuk. */
    private void showOrderDetail(Transaction t) {
        StringBuilder sb = new StringBuilder();
        if (t.getCustomerPhone() != null && !t.getCustomerPhone().trim().isEmpty())
            sb.append("📞 ").append(t.getCustomerPhone().trim()).append('\n');
        if (t.getCustomerAddress() != null && !t.getCustomerAddress().trim().isEmpty())
            sb.append("📍 ").append(t.getCustomerAddress().trim()).append('\n');
        // Lokasi tujuan terpilih (pelanggan multi-lokasi) — kurir tahu ke titik mana antar.
        if (t.getDeliveryDestName() != null && !t.getDeliveryDestName().trim().isEmpty())
            sb.append("📍 Kirim ke: ").append(t.getDeliveryDestName().trim()).append('\n');
        if (sb.length() > 0) sb.append('\n');

        sb.append("Pesanan:\n");
        List<TransactionItem> items = t.getItems();
        if (items != null && !items.isEmpty()) {
            for (TransactionItem it : items) {
                String nm = (it.productName != null && !it.productName.isEmpty()) ? it.productName : "Galon";
                sb.append("• ").append(nm).append("  ").append(it.jumlah).append(" galon");
                if (it.hargaPerGalon > 0) {
                    sb.append(" × Rp ").append(formatRupiah(it.hargaPerGalon))
                      .append(" = Rp ").append(formatRupiah(it.getSubtotal()));
                }
                sb.append('\n');
            }
        } else {
            sb.append("• ").append(t.getJumlahGalon()).append(" galon\n");
        }
        sb.append('\n');

        if (t.getOngkir() > 0) sb.append("Ongkir: Rp ").append(formatRupiah(t.getOngkir())).append('\n');
        sb.append("Total: Rp ").append(formatRupiah(t.getTotalHarga())).append('\n');
        String pay = t.getPaymentMethodLabel();
        if (pay != null && !pay.isEmpty()) sb.append("Pembayaran: ").append(pay).append('\n');

        String detailNote = displayNote(t.getCatatan());
        if (detailNote != null)
            sb.append("\nCatatan: ").append(detailNote).append('\n');

        sb.append("\nMasuk antrian: ").append(formatQueued(t.getDeliveryQueuedAt()));
        sb.append("\nMenunggu: ").append(formatDuration(elapsedMillis(t.getDeliveryQueuedAt())));

        new AlertDialog.Builder(this)
                .setTitle("Detail Order — " + safe(t.getCustomerName()))
                .setMessage(sb.toString())
                .setPositiveButton("Tutup", null)
                .setNeutralButton("Buka Struk", (d, w) -> {
                    Intent r = new Intent(this, ReceiptActivity.class);
                    r.putExtra(ReceiptActivity.EXTRA_TRANSACTION_ID, t.getId());
                    startActivity(r);
                })
                // Usulkan pembagian galon order ini ke karyawan (butuh persetujuan email).
                .setNegativeButton("Alokasi Galon", (d, w) -> AllocationDialog.show(this, t, "delivery"))
                .show();
    }

    // ----------------------------------------------------------------- helpers

    private static String safe(String s) {
        return s != null && !s.isEmpty() ? s : "Umum";
    }

    /**
     * Catatan untuk tampilan antrian. Order WEB menyimpan blob komposit (pelanggan/item/ongkir/total)
     * yang REDUNDAN dengan ringkasan kartu; untuk order web (penanda "dibuat di Web") ambil HANYA
     * catatan bebas operator (baris "Catatan: …"), buang sisanya → null bila tak ada. Order HP/lokal →
     * catatan apa adanya. Samakan dengan server (Reports::deliveryQueue).
     */
    private static String displayNote(String catatan) {
        if (catatan == null) return null;
        String c = catatan.trim();
        if (c.isEmpty()) return null;
        if (!c.contains("dibuat di Web")) return c;   // order HP/lokal → catatan apa adanya
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?:^|\\n)Catatan:\\s*(.+?)\\s*$", java.util.regex.Pattern.DOTALL)
                .matcher(c);
        if (m.find()) {
            String free = m.group(1).replaceAll("\\[[^\\]]*\\]", "").trim();
            return free.isEmpty() ? null : free;
        }
        return null;
    }

    /** Format waktu masuk antrian → "dd/MM/yyyy HH:mm:ss" (queuedAt = waktu lokal). */
    private static String formatQueued(String queuedAt) {
        if (queuedAt == null || queuedAt.length() < 19) return "-";
        try {
            java.util.Date d = SDF_PARSE.parse(queuedAt.substring(0, 19));
            if (d == null) return queuedAt.substring(0, 19);
            return new SimpleDateFormat("dd/MM/yyyy HH:mm:ss", new Locale("id", "ID")).format(d);
        } catch (Exception e) {
            return queuedAt.substring(0, 19);
        }
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

            String cardNote = displayNote(t.getCatatan());
            if (cardNote != null) {
                h.tvNote.setText("Catatan: " + cardNote);
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
            h.btnLacak.setOnClickListener(v -> sendTrackLink(t));
            h.btnSalinLink.setOnClickListener(v -> copyTrackLink(t));

            // Mode pilih: checkbox tampil, tombol aksi disembunyikan, ketuk kartu = pilih/batal.
            // Mode normal: checkbox gone, ketuk kartu = detail order.
            h.cbSelect.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
            h.cbSelect.setChecked(selectionMode && selectedIds.contains(t.getId()));
            h.actionsNav.setVisibility(selectionMode ? View.GONE : View.VISIBLE);
            h.actionsLink.setVisibility(selectionMode ? View.GONE : View.VISIBLE);
            if (selectionMode) {
                h.itemView.setOnClickListener(v -> {
                    toggleSelected(t);
                    h.cbSelect.setChecked(selectedIds.contains(t.getId()));
                });
            } else {
                h.itemView.setOnClickListener(v -> showOrderDetail(t));
            }
        }

        @Override
        public int getItemCount() { return data.size(); }

        class VH extends RecyclerView.ViewHolder {
            TextView tvCustomer, tvPhone, tvOrder, tvNote, tvAddress, tvElapsed;
            MaterialButton btnNavigasi, btnSelesai, btnLacak, btnSalinLink;
            CheckBox cbSelect;
            View actionsNav, actionsLink;
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
                btnLacak = v.findViewById(R.id.btnLacak);
                btnSalinLink = v.findViewById(R.id.btnSalinLink);
                cbSelect = v.findViewById(R.id.cbSelect);
                actionsNav = v.findViewById(R.id.actionsNav);
                actionsLink = v.findViewById(R.id.actionsLink);
            }
        }
    }

    /** Ringkasan order: untuk KEMBALI = tugas PICKUP galon (tanpa nominal, retur tak ditagih);
     *  untuk JUAL = jumlah galon + total (+ ongkir kalau ada). */
    private static String orderSummary(Transaction t) {
        if (Transaction.TYPE_KEMBALI.equals(t.getType())) {
            return "↩ Pickup • " + t.getJumlahGalon() + " galon kembali";
        }
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
