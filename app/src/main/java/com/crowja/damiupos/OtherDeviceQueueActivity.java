package com.crowja.damiupos;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.RecyclerView;

import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.SettingsDao;
import com.crowja.damiupos.sync.SyncApi;
import com.crowja.damiupos.sync.SyncScheduler;
import com.crowja.damiupos.sync.SyncSettings;
import com.google.android.material.button.MaterialButton;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * "Lihat Antrian Perangkat Lain" — antrian AKTIF milik SATU perangkat lain di cabang yang sama,
 * diambil on-demand dari server (transaksi device-isolated: HP ini tak pernah menyimpan baris
 * perangkat lain secara lokal).
 *
 * <p>Selain melihat beban rekan, kurir bisa: melihat JARAK order itu dari posisinya sendiri,
 * NAVIGASI ke titik antarnya, dan MENGAMBIL ALIH order (butuh konfirmasi dua ketukan). Ambil alih
 * memindahkan rute order ke perangkat ini di server; perangkat asal mengetahuinya lewat deteksi
 * pull yang sudah ada (notifikasi "⚠ Order Dipindahkan").</p>
 */
public class OtherDeviceQueueActivity extends AppCompatActivity {

    public static final String EXTRA_DEVICE_UUID = "device_uuid";
    public static final String EXTRA_DEVICE_NAME = "device_name";

    private static final SimpleDateFormat SDF_PARSE =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

    private RecyclerView rv;
    private TextView tvEmpty;
    private View progress;
    private final List<JSONObject> data = new ArrayList<>();
    private Adapter adapter;
    /** Perangkat yang antriannya sedang dilihat — dipakai ulang saat memuat ulang setelah ambil alih. */
    private String peekDeviceUuid;
    private String peekDeviceName;
    /** Posisi kurir INI (GPS terakhir). NaN = belum ada fix → baris jarak tak ditampilkan. */
    private double myLat = Double.NaN, myLng = Double.NaN;

    // Badge umur pesanan live per detik — cermin PERSIS DeliveryQueueActivity.tick/ticker (Antrian
    // Saya sendiri). Layar ini dulunya menampilkan "45 mnt lalu" statis (dihitung sekali saat data
    // dimuat) dengan alasan "sekilas lihat" — permintaan berikutnya membalik itu: badge berkedip
    // hijau/kuning/merah, sama pentingnya di sini karena kurir memutuskan Ambil Alih dari umurnya.
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
        setContentView(R.layout.activity_other_device_queue);

        String deviceUuid = getIntent().getStringExtra(EXTRA_DEVICE_UUID);
        String deviceName = getIntent().getStringExtra(EXTRA_DEVICE_NAME);
        peekDeviceUuid = deviceUuid;
        peekDeviceName = deviceName;
        if (deviceUuid == null || deviceUuid.isEmpty()) {
            finish();
            return;
        }

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        toolbar.setTitle("Antrian: " + (deviceName != null && !deviceName.isEmpty() ? deviceName : "Perangkat"));

        rv = findViewById(R.id.rv);
        tvEmpty = findViewById(R.id.tvEmpty);
        progress = findViewById(R.id.progress);
        rv.setLayoutManager(new androidx.recyclerview.widget.LinearLayoutManager(this));
        adapter = new Adapter();
        rv.setAdapter(adapter);

        load(deviceUuid);

        // Jarak dihitung dari posisi kurir SEKARANG. Non-blok: daftar tampil lebih dulu, baris
        // jarak menyusul begitu GPS memberi fix (pola yang sama dengan mode "Jarak" di antrian
        // sendiri). Tanpa izin/fix, jarak sekadar tidak ditampilkan.
        LocationService.lastLocation(this, loc -> {
            if (loc == null || isFinishing() || isDestroyed()) return;
            myLat = loc.getLatitude();
            myLng = loc.getLongitude();
            adapter.notifyDataSetChanged();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        tick.postDelayed(ticker, 1000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        tick.removeCallbacks(ticker);
    }

    private void load(String deviceUuid) {
        progress.setVisibility(View.VISIBLE);
        tvEmpty.setVisibility(View.GONE);
        SyncSettings cfg = new SyncSettings(new SettingsDao(DatabaseHelper.getInstance(this)));
        new Thread(() -> {
            JSONArray queue = null;
            String error = null;
            try {
                JSONObject res = new SyncApi(cfg).deviceQueue(deviceUuid);
                queue = res.optJSONArray("queue");
            } catch (Exception e) {
                error = "Gagal memuat antrian. Pastikan perangkat online lalu coba lagi.";
            }
            final JSONArray finalQueue = queue;
            final String finalError = error;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                progress.setVisibility(View.GONE);
                if (finalError != null) {
                    Toast.makeText(this, finalError, Toast.LENGTH_LONG).show();
                    tvEmpty.setText(finalError);
                    tvEmpty.setVisibility(View.VISIBLE);
                    return;
                }
                data.clear();
                if (finalQueue != null) {
                    for (int i = 0; i < finalQueue.length(); i++) {
                        JSONObject o = finalQueue.optJSONObject(i);
                        if (o != null) data.add(o);
                    }
                }
                adapter.notifyDataSetChanged();
                tvEmpty.setVisibility(data.isEmpty() ? View.VISIBLE : View.GONE);
            });
        }).start();
    }

    /** Milidetik sejak order masuk antrian; 0 kalau tidak bisa di-parse. Cermin PERSIS
     *  DeliveryQueueActivity.elapsedMillis (sumbernya JSON queued_at, bukan kolom lokal). */
    private static long elapsedMillis(String queuedAt) {
        if (queuedAt == null || queuedAt.length() < 19) return 0;
        try {
            java.util.Date d = SDF_PARSE.parse(queuedAt.substring(0, 19));
            if (d == null) return 0;
            return Math.max(0, System.currentTimeMillis() - d.getTime());
        } catch (Exception e) {
            return 0;
        }
    }

    /** Format ringkas badge timer umur pesanan — cermin PERSIS DeliveryQueueActivity.formatElapsedBadge:
     *  &lt;1 jam "mm:ss", 1–24 jam "hh:mm:ss", &gt;24 jam "N hari hh:mm". */
    private static String formatElapsedBadge(long ms) {
        long s = Math.max(0, ms) / 1000;
        long days = s / 86400;
        if (days > 0) {
            long h = (s % 86400) / 3600, m = (s % 3600) / 60;
            return days + " hari " + String.format(Locale.US, "%02d:%02d", h, m);
        }
        long h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        if (h > 0) return String.format(Locale.US, "%02d:%02d:%02d", h, m, sec);
        return String.format(Locale.US, "%02d:%02d", m, sec);
    }

    /** Ambang peringatan lama menunggu — sama dengan Antrian Saya & dashboard web. */
    private static final long QUEUE_WARN_MS = 60L * 60 * 1000;       // 1 jam → kuning berkedip
    private static final long QUEUE_LATE_MS = 2L * 60 * 60 * 1000;   // 2 jam → merah berkedip lebih cepat

    /** Warnai + kedipkan badge umur pesanan: &lt;1 jam HIJAU diam, ≥1 jam KUNING berkedip, ≥2 jam
     *  MERAH berkedip lebih cepat. Cermin PERSIS DeliveryQueueActivity.applyQueueTimerState (beda
     *  hanya warna netral: hijau di sini vs abu-abu di Antrian Saya — layar ini tak punya makna
     *  "baru masuk, belum perlu perhatian" netral yang sama, jadi hijau eksplisit lebih jelas). */
    private static void applyQueueTimerState(TextView tv, long elapsedMs) {
        final int level = elapsedMs >= QUEUE_LATE_MS ? 2 : (elapsedMs >= QUEUE_WARN_MS ? 1 : 0);
        Object prev = tv.getTag(R.id.tvQueued);
        boolean changed = !(prev instanceof Integer) || (Integer) prev != level;
        if (changed) {
            tv.setTag(R.id.tvQueued, level);
            tv.setBackgroundResource(level == 2 ? R.drawable.bg_pending_badge
                    : level == 1 ? R.drawable.bg_queue_timer_warn
                    : R.drawable.bg_queue_timer_ok_green);
        }
        if (level == 0) {
            tv.clearAnimation();
            tv.setAlpha(1f);
            return;
        }
        if (!changed && tv.getAnimation() != null) return;   // sudah berkedip pada tingkat yang sama
        android.view.animation.AlphaAnimation blink =
                new android.view.animation.AlphaAnimation(1f, level == 2 ? 0.2f : 0.35f);
        blink.setDuration(level == 2 ? 350 : 650);
        blink.setRepeatMode(android.view.animation.Animation.REVERSE);
        blink.setRepeatCount(android.view.animation.Animation.INFINITE);
        tv.startAnimation(blink);
    }

    private static String safe(String s) { return s == null ? "" : s; }

    /** Nilai JSON opsional yang bisa datang sebagai literal "null" dari server. */
    private static String str(JSONObject o, String key) {
        String v = o.optString(key, "");
        return v == null || v.equals("null") ? "" : v;
    }

    // ------------------------------------------------------- Jarak & navigasi

    /** Koordinat EFEKTIF order (server sudah mengirim tujuan "Kirim ke" bila ada, else pelanggan). */
    private static double lat(JSONObject q) { return q.optDouble("latitude", 0); }

    private static double lng(JSONObject q) { return q.optDouble("longitude", 0); }

    private static boolean hasGeo(JSONObject q) { return lat(q) != 0 || lng(q) != 0; }

    /** Jarak garis-lurus (km) dari posisi kurir ke order; NaN bila GPS/koordinat order tak ada. */
    private double distanceKmTo(JSONObject q) {
        if (Double.isNaN(myLat) || Double.isNaN(myLng) || !hasGeo(q)) return Double.NaN;
        return haversineKm(myLat, myLng, lat(q), lng(q));
    }

    private static double haversineKm(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 6371.0 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private static String formatKm(double km) {
        if (km < 1) return Math.round(km * 1000) + " m";
        return String.format(Locale.US, "%.1f km", km).replace('.', ',');
    }

    /** Buka navigasi ke titik antar order (Google Maps, fallback peta web / pencarian alamat). */
    private void navigateTo(JSONObject q) {
        Intent i;
        if (hasGeo(q)) {
            Uri nav = Uri.parse("google.navigation:q=" + lat(q) + "," + lng(q));
            i = new Intent(Intent.ACTION_VIEW, nav).setPackage("com.google.android.apps.maps");
            if (i.resolveActivity(getPackageManager()) == null) {
                i = new Intent(Intent.ACTION_VIEW, Uri.parse(
                        "https://www.google.com/maps?q=" + lat(q) + "," + lng(q)));
            }
        } else {
            String addr = str(q, "address");
            if (addr.isEmpty()) {
                Toast.makeText(this, "Lokasi pelanggan belum diset", Toast.LENGTH_SHORT).show();
                return;
            }
            i = new Intent(Intent.ACTION_VIEW, Uri.parse(
                    "https://www.google.com/maps/search/?api=1&query=" + Uri.encode(addr)));
        }
        try {
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "Tidak ada aplikasi peta", Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------------- Ambil alih order

    /**
     * Gerbang AMBIL ALIH: popup peringatan (⚠) yang tombol lanjutnya harus diketuk DUA KALI —
     * memindahkan order rekan adalah keputusan yang mengubah pembagian kerja orang lain, jadi tak
     * boleh terjadi karena salah sentuh. Order yang sedang DIKERJAKAN rekan diberi peringatan
     * tambahan (kurirnya mungkin sudah di jalan).
     */
    private void confirmTakeOver(JSONObject q) {
        final String uuid = str(q, "uuid");   // identitas sinkron transaksi (bukan id lokal server)
        final String name = safe(q.optString("name", "Pelanggan"));
        boolean running = q.optBoolean("in_progress", false);

        StringBuilder msg = new StringBuilder();
        msg.append("Order \"").append(name).append("\" akan DIPINDAHKAN dari perangkat ")
                .append(peekDeviceName != null && !peekDeviceName.isEmpty() ? "\"" + peekDeviceName + "\"" : "lain")
                .append(" ke perangkat Anda.\n\n");
        if (running) {
            msg.append("⚠️ Order ini SEDANG DIKERJAKAN kurir tersebut — pastikan sudah ada kesepakatan "
                    + "sebelum mengambilnya.\n\n");
        }
        msg.append("Perangkat asal akan diberi tahu bahwa order ini dipindahkan.\n\n"
                + "Ketuk \"Ambil Alih\" dua kali untuk memastikan.");

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setTitle("⚠️ Ambil Alih Pengiriman?")
                .setCancelable(false)
                .setMessage(msg.toString())
                .setPositiveButton("Ambil Alih", null)
                .setNegativeButton("Batal", null)
                .create();

        dialog.setOnShowListener(d -> {
            final Button pos = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            final int[] clicks = {0};
            pos.setOnClickListener(v -> {
                // Ketukan PERTAMA hanya menegaskan; ketukan KEDUA yang benar-benar memindahkan.
                if (++clicks[0] < 2) {
                    pos.setText("Ketuk sekali lagi");
                    return;
                }
                pos.setEnabled(false);
                pos.setText("Memindahkan…");
                dialog.setCancelable(false);
                Button neg = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
                if (neg != null) neg.setEnabled(false);
                takeOver(dialog, pos, neg, uuid, name, str(q, "routed_uuid"));
            });
        });
        dialog.show();
    }

    /** Kirim klaim ke server; sukses → notifikasi lokal + muat ulang daftar rekan + sinkron. */
    private void takeOver(final AlertDialog dialog, final Button pos, final Button neg,
                          final String trxUuid, final String custName, final String routedUuid) {
        final SyncSettings cfg = new SyncSettings(new SettingsDao(DatabaseHelper.getInstance(this)));
        if (!cfg.isEnrolled()) {
            Toast.makeText(this, "Perangkat belum terhubung ke server.", Toast.LENGTH_LONG).show();
            dialog.dismiss();
            return;
        }
        if (trxUuid == null || trxUuid.isEmpty()) {
            Toast.makeText(this, "Order ini belum punya identitas server. Coba muat ulang.", Toast.LENGTH_LONG).show();
            dialog.dismiss();
            return;
        }
        new Thread(() -> {
            String okMsg = null, errMsg = null;
            try {
                JSONObject body = new JSONObject();
                body.put("transaction_uuid", trxUuid);
                // Pemilik order MENURUT layar ini — penjaga basi di server: bila di server sudah
                // berpindah, klaim ditolak (409) alih-alih menimpa keputusan yang lebih baru.
                body.put("expected_device_uuid", routedUuid != null ? routedUuid : "");
                JSONObject r = new SyncApi(cfg).claimDelivery(body);
                okMsg = r.optString("message", "Order diambil alih ke perangkat ini.");
            } catch (SyncApi.SyncException se) {
                errMsg = extractMessage(se.body);
                if (errMsg == null) errMsg = "Gagal mengambil alih (kode " + se.code + ").";
            } catch (Exception e) {
                errMsg = "Gagal mengambil alih — periksa koneksi internet.";
            }
            final String fOk = okMsg, fErr = errMsg;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (fOk != null) {
                    Toast.makeText(this, fOk, Toast.LENGTH_LONG).show();
                    // Notifikasi di perangkat TUJUAN (perangkat ini). Perangkat ASAL mendapat
                    // notifikasinya sendiri saat sinkron berikutnya — SyncEngine mendeteksi order
                    // yang keluar dari antriannya ("⚠ Order Dipindahkan").
                    try {
                        com.crowja.damiupos.sync.OnlineNotifier.postNotif(getApplicationContext(),
                                "📥 Order Diambil Alih",
                                "Order \"" + custName + "\" dipindahkan ke perangkat ini dan masuk antrian Anda.",
                                7950);
                    } catch (Throwable ignored) {}
                    dialog.dismiss();
                    // Tarik order-nya ke HP ini sekarang, lalu segarkan daftar antrian rekan.
                    SyncScheduler.syncNow(getApplicationContext());
                    if (peekDeviceUuid != null) load(peekDeviceUuid);
                } else {
                    Toast.makeText(this, fErr, Toast.LENGTH_LONG).show();
                    pos.setEnabled(true);
                    pos.setText("Ambil Alih");
                    dialog.setCancelable(true);
                    if (neg != null) neg.setEnabled(true);
                    // Daftar kemungkinan sudah basi (order berpindah/selesai) → muat ulang.
                    if (peekDeviceUuid != null) load(peekDeviceUuid);
                }
            });
        }).start();
    }

    /** Pesan ramah dari body JSON error server ({"message":"..."}); null bila gagal dibaca. */
    private static String extractMessage(String body) {
        if (body == null || body.isEmpty()) return null;
        try {
            return new JSONObject(body).optString("message", null);
        } catch (Exception e) {
            return null;
        }
    }

    private class Adapter extends RecyclerView.Adapter<Adapter.VH> {
        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_other_device_queue, parent, false);
            return new VH(v);
        }

        /** Perbarui HANYA badge umur pesanan pada baris yang sedang TERLIHAT — dipanggil tiap detik
         *  oleh ticker, bukan notifyDataSetChanged() (itu akan mereset scroll & re-inflate semua
         *  baris tiap detik). Cermin PERSIS DeliveryQueueActivity.Adapter.refreshTimers. */
        void refreshTimers() {
            for (int i = 0; i < rv.getChildCount(); i++) {
                View child = rv.getChildAt(i);
                RecyclerView.ViewHolder vh = rv.getChildViewHolder(child);
                int pos = vh.getAdapterPosition();
                if (pos >= 0 && pos < data.size() && vh instanceof VH) {
                    long ms = elapsedMillis(data.get(pos).optString("queued_at", null));
                    ((VH) vh).tvQueued.setText("⏱ " + formatElapsedBadge(ms));
                    applyQueueTimerState(((VH) vh).tvQueued, ms);
                }
            }
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            JSONObject q = data.get(position);
            boolean custPriority = q.optBoolean("is_priority", false);
            boolean orderPriority = q.optBoolean("order_priority", false);
            h.tvCustomer.setText((custPriority ? "⭐ " : "") + safe(q.optString("name", "Pelanggan")));

            String phone = q.optString("phone", "");
            if (!phone.isEmpty() && !phone.equals("null")) {
                h.tvPhone.setText(phone);
                h.tvPhone.setVisibility(View.VISIBLE);
            } else {
                h.tvPhone.setVisibility(View.GONE);
            }

            int galon = q.optInt("galon", 0);
            double total = q.optDouble("total", 0);
            h.tvOrder.setText(galon + " galon · Rp "
                    + String.format(Locale.US, "%,.0f", total).replace(',', '.'));

            String items = q.optString("items", "");
            if (!items.isEmpty() && !items.equals("null")) {
                h.tvItems.setText(items);
                h.tvItems.setVisibility(View.VISIBLE);
            } else {
                h.tvItems.setVisibility(View.GONE);
            }

            boolean pickupOnly = q.optBoolean("pickup_only", false);
            String destName = q.optString("dest_name", "");
            String address = q.optString("address", "");
            StringBuilder addr = new StringBuilder();
            if (!destName.isEmpty() && !destName.equals("null")) {
                addr.append(pickupOnly ? "🪣 Ambil di: " : "📍 Kirim ke: ").append(destName);
            } else if (!address.isEmpty() && !address.equals("null")) {
                addr.append("📍 ").append(address);
            }
            if (addr.length() > 0) {
                h.tvAddress.setText(addr.toString());
                h.tvAddress.setVisibility(View.VISIBLE);
            } else {
                h.tvAddress.setVisibility(View.GONE);
            }

            if (orderPriority) {
                String why = q.optString("order_priority_reason", "");
                h.tvPriority.setText("⚡ PRIORITAS" + (why.isEmpty() || why.equals("null") ? "" : ": " + why));
                h.tvPriority.setVisibility(View.VISIBLE);
            } else {
                h.tvPriority.setVisibility(View.GONE);
            }

            // "Pesanan Terbuka" (lelang) belum diklaim — staf perangkat mana pun boleh mengambilnya
            // via tombol Ambil Alih yang sama.
            if (q.optBoolean("open_dispatch", false)) {
                h.tvOpenDispatch.setText("🎲 PESANAN TERBUKA");
                h.tvOpenDispatch.setVisibility(View.VISIBLE);
            } else {
                h.tvOpenDispatch.setVisibility(View.GONE);
            }

            long queuedMs = elapsedMillis(q.optString("queued_at", null));
            h.tvQueued.setText("⏱ " + formatElapsedBadge(queuedMs));
            applyQueueTimerState(h.tvQueued, queuedMs);

            // Jarak dari posisi kurir ini ke titik antar order.
            double km = distanceKmTo(q);
            if (!Double.isNaN(km)) {
                h.tvDistance.setText("📏 " + formatKm(km) + " dari posisi Anda");
                h.tvDistance.setVisibility(View.VISIBLE);
            } else {
                h.tvDistance.setVisibility(View.GONE);
            }

            // Catatan order di kaki kartu — sumbernya 'note' dari server (Reports::shapeQueueRow);
            // baris ini milik perangkat LAIN, jadi tak pernah ada salinan lokalnya di HP ini.
            // Penyaringnya SATU dengan ketiga tab Antrian Delivery, supaya catatan yang sama tak
            // pernah tampil beda antar layar.
            DeliveryQueueActivity.bindOrderNote(h.tvOrderNote, str(q, "note"));

            h.btnNavigasi.setOnClickListener(v -> navigateTo(q));
            h.btnAmbilAlih.setOnClickListener(v -> confirmTakeOver(q));
        }

        @Override
        public int getItemCount() { return data.size(); }

        class VH extends RecyclerView.ViewHolder {
            TextView tvCustomer, tvPhone, tvOrder, tvItems, tvAddress, tvPriority, tvOpenDispatch, tvQueued, tvDistance, tvOrderNote;
            MaterialButton btnNavigasi, btnAmbilAlih;

            VH(View v) {
                super(v);
                tvOrderNote = v.findViewById(R.id.tvOrderNote);
                tvCustomer = v.findViewById(R.id.tvCustomer);
                tvPhone = v.findViewById(R.id.tvPhone);
                tvOrder = v.findViewById(R.id.tvOrder);
                tvItems = v.findViewById(R.id.tvItems);
                tvAddress = v.findViewById(R.id.tvAddress);
                tvPriority = v.findViewById(R.id.tvPriority);
                tvOpenDispatch = v.findViewById(R.id.tvOpenDispatch);
                tvQueued = v.findViewById(R.id.tvQueued);
                tvDistance = v.findViewById(R.id.tvDistance);
                btnNavigasi = v.findViewById(R.id.btnNavigasi);
                btnAmbilAlih = v.findViewById(R.id.btnAmbilAlih);
            }
        }
    }
}
