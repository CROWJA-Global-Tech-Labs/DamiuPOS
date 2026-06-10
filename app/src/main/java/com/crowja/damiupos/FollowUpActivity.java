package com.crowja.damiupos;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
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

import com.crowja.damiupos.db.CustomerDao;
import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.SettingsDao;
import com.crowja.damiupos.model.Customer;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
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
        rv.setAdapter(adapter);
    }

    @Override
    protected void onResume() {
        super.onResume();
        thresholdDays = settingsDao.getFollowupDays();
        List<Customer> list = customerDao.getFollowUpCandidates(thresholdDays);
        currentList = list;
        adapter.setData(list);
        tvSummary.setText(list.size() + " pelanggan belum bertransaksi lebih dari " + thresholdDays + " hari");
        tvEmpty.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
        rv.setVisibility(list.isEmpty() ? View.GONE : View.VISIBLE);
        invalidateOptionsMenu(); // enable/disable tombol Peta sesuai ketersediaan koordinat
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
            showPetaModeDialog();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /**
     * Tombol "Peta" → dialog pilihan tampilan:
     *   1. Lihat Semua di Peta — buka {@link FollowUpMapActivity} (OSM), semua
     *      pin pelanggan follow-up dalam satu layar.
     *   2. Per Pelanggan — picker → buka Google Maps intent (1 pin bernama).
     */
    private void showPetaModeDialog() {
        // Cek dulu apakah ada pelanggan dengan koordinat.
        int withCoords = 0;
        for (Customer c : currentList) {
            if (c.getLatitude() != 0 || c.getLongitude() != 0) withCoords++;
        }
        if (withCoords == 0) {
            Toast.makeText(this,
                    "Belum ada pelanggan follow-up yang punya koordinat lokasi",
                    Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Tampilkan Peta")
                .setItems(new CharSequence[]{
                        "Lihat Semua di Peta (" + withCoords + " pelanggan)",
                        "Per Pelanggan (Google Maps)"
                }, (dialog, which) -> {
                    if (which == 0) {
                        startActivity(new Intent(this, FollowUpMapActivity.class));
                    } else {
                        showPetaPicker();
                    }
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    /**
     * Picker per-pelanggan: tiap pelanggan ditampilkan sebagai card di dalam
     * container scrollable (RecyclerView). Tap card → buka Google Maps dengan
     * pin bernama pelanggan tsb (1 pin per buka).
     */
    private void showPetaPicker() {
        List<Customer> withCoords = new ArrayList<>();
        for (Customer c : currentList) {
            if (c.getLatitude() != 0 || c.getLongitude() != 0) withCoords.add(c);
        }
        if (withCoords.isEmpty()) {
            Toast.makeText(this,
                    "Belum ada pelanggan follow-up yang punya koordinat lokasi",
                    Toast.LENGTH_LONG).show();
            return;
        }

        View content = LayoutInflater.from(this)
                .inflate(R.layout.dialog_peta_picker, null, false);
        TextView title = content.findViewById(R.id.tvPickerTitle);
        title.setText("Buka Peta Pelanggan (" + withCoords.size() + ")");

        RecyclerView rvPicker = content.findViewById(R.id.rvPetaPicker);
        rvPicker.setLayoutManager(new LinearLayoutManager(this));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(content)
                .setNegativeButton("Batal", null)
                .create();

        rvPicker.setAdapter(new PetaPickerAdapter(withCoords, c -> {
            dialog.dismiss();
            openCustomerInMaps(c);
        }));

        // Cap tinggi RecyclerView ke ~55% layar supaya list panjang tetap muat
        // & internal-scroll (RecyclerView abaikan android:maxHeight, jadi set
        // di code setelah ukuran layar diketahui).
        int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.55f);
        rvPicker.post(() -> {
            if (rvPicker.getHeight() > maxH) {
                ViewGroup.LayoutParams lp = rvPicker.getLayoutParams();
                lp.height = maxH;
                rvPicker.setLayoutParams(lp);
            }
        });

        dialog.show();
    }

    /** Adapter card untuk dialog Peta picker. */
    private static class PetaPickerAdapter
            extends RecyclerView.Adapter<PetaPickerAdapter.VH> {
        interface OnPick { void pick(Customer c); }
        private final List<Customer> items;
        private final OnPick onPick;

        PetaPickerAdapter(List<Customer> items, OnPick onPick) {
            this.items = items;
            this.onPick = onPick;
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_peta_picker, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            Customer c = items.get(position);
            h.tvName.setText(c.getName());
            String addr = c.getAddress() != null && !c.getAddress().isEmpty()
                    ? c.getAddress() : "(tanpa alamat)";
            h.tvAddress.setText(addr);
            h.itemView.setOnClickListener(v -> onPick.pick(c));
        }

        @Override
        public int getItemCount() { return items.size(); }

        static class VH extends RecyclerView.ViewHolder {
            final TextView tvName, tvAddress;
            VH(@NonNull View v) {
                super(v);
                tvName = v.findViewById(R.id.tvPickerName);
                tvAddress = v.findViewById(R.id.tvPickerAddress);
            }
        }
    }

    /** Buka Google Maps dengan pin pada koordinat pelanggan, label = nama. */
    private void openCustomerInMaps(Customer c) {
        double lat = c.getLatitude();
        double lng = c.getLongitude();
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

            boolean hasPhone = c.getPhone() != null && !c.getPhone().isEmpty();
            h.btnCall.setVisibility(hasPhone ? View.VISIBLE : View.GONE);
            h.btnCall.setOnClickListener(v -> openWhatsAppForFollowUp(c));

            // Klik baris pelanggan langsung buka WhatsApp dengan template follow-up.
            // Kalau no HP kosong, fallback ke detail pelanggan.
            h.itemView.setOnClickListener(v -> {
                if (hasPhone) {
                    openWhatsAppForFollowUp(c);
                } else {
                    Intent i = new Intent(FollowUpActivity.this, CustomerDetailActivity.class);
                    i.putExtra("customer_id", c.getId());
                    startActivity(i);
                }
            });

            // Long-press tetap menuju detail pelanggan untuk yang punya HP.
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
            ImageButton btnCall;
            VH(View v) {
                super(v);
                tvName = v.findViewById(R.id.tvName);
                tvPhone = v.findViewById(R.id.tvPhone);
                tvDays = v.findViewById(R.id.tvDays);
                tvGalon = v.findViewById(R.id.tvGalon);
                tvLastPurchase = v.findViewById(R.id.tvLastPurchase);
                tvInitial = v.findViewById(R.id.tvInitial);
                ivAvatar = v.findViewById(R.id.ivAvatar);
                btnCall = v.findViewById(R.id.btnCall);
            }
        }
    }

    /**
     * Load foto pelanggan untuk avatar: downsample (inSampleSize) supaya hemat
     * memory di list, + koreksi rotasi EXIF supaya tidak miring. Return null
     * kalau gagal (caller fallback ke inisial).
     */
    private android.graphics.Bitmap loadAvatarBitmap(String path) {
        try {
            android.graphics.BitmapFactory.Options opts =
                    new android.graphics.BitmapFactory.Options();
            opts.inSampleSize = 4; // avatar kecil, tidak perlu full-res
            android.graphics.Bitmap bmp =
                    android.graphics.BitmapFactory.decodeFile(path, opts);
            if (bmp == null) return null;
            int orientation = new androidx.exifinterface.media.ExifInterface(path)
                    .getAttributeInt(
                            androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
                            androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL);
            int rot = 0;
            switch (orientation) {
                case androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90: rot = 90; break;
                case androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180: rot = 180; break;
                case androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270: rot = 270; break;
            }
            if (rot != 0) {
                android.graphics.Matrix m = new android.graphics.Matrix();
                m.postRotate(rot);
                android.graphics.Bitmap rotated = android.graphics.Bitmap.createBitmap(
                        bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
                if (rotated != bmp) bmp.recycle();
                return rotated;
            }
            return bmp;
        } catch (Throwable t) {
            return null;
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
        } catch (Exception e) {
            Toast.makeText(this, "Tidak dapat membuka WhatsApp", Toast.LENGTH_SHORT).show();
        }
    }

    /** Nama hari dalam Bahasa Indonesia (Senin, Selasa, dst.) */
    private String formatDayName(String ts) {
        if (ts == null || ts.isEmpty()) return "-";
        try {
            SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
            Date d = in.parse(ts);
            if (d == null) return "-";
            SimpleDateFormat out = new SimpleDateFormat("EEEE", new Locale("id", "ID"));
            return out.format(d);
        } catch (Exception e) {
            try {
                SimpleDateFormat in2 = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
                Date d2 = in2.parse(ts.substring(0, Math.min(10, ts.length())));
                if (d2 == null) return "-";
                SimpleDateFormat out = new SimpleDateFormat("EEEE", new Locale("id", "ID"));
                return out.format(d2);
            } catch (Exception ignored) { return "-"; }
        }
    }
}
