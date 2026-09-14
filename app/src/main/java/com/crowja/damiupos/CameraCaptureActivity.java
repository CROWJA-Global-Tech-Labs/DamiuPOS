package com.crowja.damiupos;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.view.CameraController;
import androidx.camera.view.LifecycleCameraController;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.tasks.CancellationTokenSource;

import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.SettingsDao;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Capture selfie wajah otomatis (kamera depan) dengan countdown 5 detik, 720p.
 * Setelah jepret, tempel peta koordinat picture-in-picture di pojok kanan bawah
 * (tile OSM + marker). Hasil path foto dikembalikan via {@link #EXTRA_PHOTO_PATH}.
 *
 * <p>Kegagalan (izin kamera ditolak / tidak ada kamera) tidak fatal — activity
 * menutup dengan RESULT_CANCELED dan pemanggil lanjut tanpa foto.
 */
public class CameraCaptureActivity extends AppCompatActivity {

    public static final String EXTRA_LABEL = "label";       // teks judul (Clock In/Pulang)
    public static final String EXTRA_PHOTO_PATH = "photo_path";
    /** true = user menekan "Batal" di peringatan lokasi (batalkan absensi),
     *  beda dari kamera gagal/izin ditolak (yang tetap lanjut tanpa foto). */
    public static final String EXTRA_USER_CANCELLED = "user_cancelled";
    /** true = titik selfie berada DI LUAR area absensi (geofence) cabang. */
    public static final String EXTRA_OUT_OF_RADIUS = "out_of_radius";
    /** Jarak (meter, dibulatkan) dari pusat area absensi; -1 = tak terukur. */
    public static final String EXTRA_DISTANCE_M = "distance_m";
    /** Alasan yang diketik staf saat absen di luar area — disimpan sebagai bukti. */
    public static final String EXTRA_RADIUS_REASON = "radius_reason";

    private static final int REQ_CAMERA = 901;

    private PreviewView previewView;
    private TextView tvCountdown;
    private TextView tvLabel;
    private LifecycleCameraController controller;
    private boolean capturing = false;

    /** Lokasi GPS WAJIB untuk geotag foto absensi (EXIF + overlay peta). */
    private Location selfieLocation;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private CancellationTokenSource locCancel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera_capture);

        previewView = findViewById(R.id.previewView);
        tvCountdown = findViewById(R.id.tvCountdown);
        tvLabel = findViewById(R.id.tvLabel);
        String label = getIntent().getStringExtra(EXTRA_LABEL);
        tvLabel.setText("Foto Wajah" + (label != null ? " — " + label : ""));

        // Peringatan lokasi sebelum selfie absensi: pastikan berada di
        // lingkungan depot dulu. OK → lanjut kamera + countdown; Batal → batal.
        showLocationWarningThenStart();
    }

    /** Tampilkan peringatan agar berada di lingkungan depot sebelum foto. */
    private void showLocationWarningThenStart() {
        String depot = "";
        try {
            depot = new SettingsDao(DatabaseHelper.getInstance(this)).getDepotName();
        } catch (Throwable ignored) {}
        String tempat = (depot != null && !depot.isEmpty()) ? depot : "depot";
        new AlertDialog.Builder(this)
                .setTitle("Sebelum Ambil Foto")
                .setMessage("Pastikan Anda berada di lingkungan " + tempat
                        + " sebelum mengambil foto wajah. Lanjutkan?")
                .setCancelable(false)
                .setPositiveButton("Ya, Lanjut", (d, w) -> startCameraFlow())
                .setNegativeButton("Batal", (d, w) -> finishUserCancelled())
                .show();
    }

    /** Batal eksplisit oleh user (tombol Batal di peringatan lokasi) → absensi
     *  TIDAK dilanjutkan (beda dari finishNoPhoto yang tetap lanjut tanpa foto). */
    private void finishUserCancelled() {
        Intent data = new Intent();
        data.putExtra(EXTRA_USER_CANCELLED, true);
        setResult(RESULT_CANCELED, data);
        finish();
    }

    /** Cek izin kamera/lokasi lalu mulai kamera (dipanggil setelah peringatan OK). */
    private void startCameraFlow() {
        boolean cam = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
        boolean loc = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        if (cam && loc) {
            acquireLocationThenStart();
        } else {
            // Kamera DAN lokasi sama-sama wajib (foto absensi harus ber-geotag).
            java.util.List<String> req = new java.util.ArrayList<>();
            if (!cam) req.add(Manifest.permission.CAMERA);
            if (!loc) req.add(Manifest.permission.ACCESS_FINE_LOCATION);
            ActivityCompat.requestPermissions(this, req.toArray(new String[0]), REQ_CAMERA);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CAMERA) {
            boolean cam = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED;
            boolean loc = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED
                    || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED;
            if (!cam) {
                finishNoPhoto(); // tanpa kamera → tidak ada selfie (absensi lanjut tanpa foto)
            } else if (!loc) {
                // Lokasi WAJIB untuk geotag — tanpa izin lokasi, foto absensi tidak diizinkan.
                blockNoLocation("Izin lokasi diperlukan untuk foto absensi ber-geotag.");
            } else {
                acquireLocationThenStart();
            }
        }
    }

    /** Dapatkan fix GPS dulu (WAJIB) lalu mulai kamera. Tanpa lokasi → blok + opsi coba lagi. */
    private void acquireLocationThenStart() {
        tvCountdown.setText("📍");
        if (tvLabel != null) tvLabel.setText("Mendapatkan lokasi GPS…");
        requestFreshLocation(loc -> {
            if (isFinishing()) return;
            if (loc != null) {
                selfieLocation = loc;
                String label = getIntent().getStringExtra(EXTRA_LABEL);
                if (tvLabel != null) tvLabel.setText("Foto Wajah" + (label != null ? " — " + label : ""));
                startCamera();
            } else {
                blockNoLocation("Lokasi GPS belum didapat. Pastikan GPS aktif & sinyal cukup (idealnya di luar ruangan).");
            }
        });
    }

    /** Minta 1 fix lokasi terbaru (akurasi tinggi) dengan timeout 12 dtk, fallback last-known. */
    @SuppressWarnings("MissingPermission")
    private void requestFreshLocation(java.util.function.Consumer<Location> cb) {
        boolean granted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        if (!granted) { cb.accept(null); return; }
        final boolean[] done = {false};
        locCancel = new CancellationTokenSource();
        final Runnable timeout = () -> {
            if (done[0]) return;
            done[0] = true;
            try { locCancel.cancel(); } catch (Throwable ignored) {}
            cb.accept(getLastLocation());   // fallback ke last-known
        };
        handler.postDelayed(timeout, 12000);
        try {
            FusedLocationProviderClient fused = LocationServices.getFusedLocationProviderClient(this);
            fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, locCancel.getToken())
                    .addOnSuccessListener(loc -> {
                        if (done[0]) return;
                        done[0] = true;
                        handler.removeCallbacks(timeout);
                        cb.accept(loc != null ? loc : getLastLocation());
                    })
                    .addOnFailureListener(e -> {
                        if (done[0]) return;
                        done[0] = true;
                        handler.removeCallbacks(timeout);
                        cb.accept(getLastLocation());
                    });
        } catch (Throwable t) {
            if (done[0]) return;
            done[0] = true;
            handler.removeCallbacks(timeout);
            cb.accept(getLastLocation());
        }
    }

    /** Lokasi wajib tidak terpenuhi → tawarkan Coba Lagi atau Batal (absensi dibatalkan). */
    private void blockNoLocation(String why) {
        if (isFinishing()) return;
        tvCountdown.setText("");
        new AlertDialog.Builder(this)
                .setTitle("Lokasi Wajib")
                .setMessage(why + "\n\nFoto absensi harus memuat lokasi.")
                .setCancelable(false)
                .setPositiveButton("Coba Lagi", (d, w) -> startCameraFlow())
                .setNegativeButton("Batal", (d, w) -> finishUserCancelled())
                .show();
    }

    private void startCamera() {
        try {
            controller = new LifecycleCameraController(this);
            controller.setEnabledUseCases(CameraController.IMAGE_CAPTURE);
            controller.setImageCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY);
            // Kamera depan. Selector cukup di-set (disimpan & dipakai saat bind);
            // JANGAN panggil hasCamera() di sini — throw "Camera not initialized"
            // karena init CameraX masih async.
            controller.setCameraSelector(CameraSelector.DEFAULT_FRONT_CAMERA);
            controller.bindToLifecycle(this);
            previewView.setController(controller);
            startCountdown();
        } catch (Throwable t) {
            android.util.Log.e("SelfieCam", "startCamera failed", t);
            finishNoPhoto();
        }
    }

    private void startCountdown() {
        new CountDownTimer(5000, 1000) {
            @Override public void onTick(long ms) {
                tvCountdown.setText(String.valueOf((int) Math.ceil(ms / 1000.0)));
            }
            @Override public void onFinish() {
                tvCountdown.setText("📸");
                capture();
            }
        }.start();
    }

    private void capture() {
        if (capturing || controller == null) return;
        capturing = true;
        File dir = new File(getExternalFilesDir(null), "attendance");
        if (!dir.exists()) dir.mkdirs();
        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        final File photoFile = new File(dir, "selfie_" + ts + ".jpg");

        ImageCapture.OutputFileOptions opts =
                new ImageCapture.OutputFileOptions.Builder(photoFile).build();
        controller.takePicture(opts, ContextCompat.getMainExecutor(this),
                new ImageCapture.OnImageSavedCallback() {
                    @Override
                    public void onImageSaved(@NonNull ImageCapture.OutputFileResults r) {
                        // Komposit peta PiP di background (network + image).
                        new Thread(() -> {
                            try {
                                composeMapOverlay(photoFile);
                            } catch (Throwable ignored) {}
                            runOnUiThread(() -> finishWithPhoto(photoFile.getAbsolutePath()));
                        }, "selfie-compose").start();
                    }

                    @Override
                    public void onError(@NonNull ImageCaptureException e) {
                        finishNoPhoto();
                    }
                });
    }

    /**
     * Foto selesai → tegakkan area absensi. Di luar radius, staf WAJIB mengetik alasan sebelum
     * absennya tercatat; alasan + jaraknya ikut kembali ke pemanggil dan tersimpan sebaris dengan
     * event absensi (jadi bukti untuk laporan &amp; penggajian).
     *
     * <p>Pusat/radius memakai kunci geofence yang SAMA dengan dashboard (App\Support\Reports::
     * geofence) sehingga vonis di HP dan vonis di slip gaji tidak pernah berbeda. Geofence belum
     * diatur, atau lokasi tak terukur → lanjut seperti biasa: fitur ini menagih alasan, bukan
     * memblokir absensi.
     */
    private void finishWithPhoto(String path) {
        double[] center = null;
        double radius = 0;
        try {
            SettingsDao sd = new SettingsDao(DatabaseHelper.getInstance(this));
            center = sd.getGeofenceCenter();
            radius = sd.getGeofenceRadius();
        } catch (Throwable ignored) {}

        Location loc = selfieLocation != null ? selfieLocation : getLastLocation();
        if (center == null || loc == null) {
            deliverPhoto(path, false, -1, null);
            return;
        }

        float[] out = new float[1];
        Location.distanceBetween(center[0], center[1], loc.getLatitude(), loc.getLongitude(), out);
        int distance = Math.round(out[0]);
        if (distance <= radius) {
            deliverPhoto(path, false, distance, null);
            return;
        }
        askOutOfRadiusReason(path, distance, (int) Math.round(radius));
    }

    /** Popup WAJIB-isi: absen di luar area → alasan tidak boleh kosong, tak bisa ditutup di luar. */
    private void askOutOfRadiusReason(String path, int distance, int radius) {
        if (isFinishing()) {
            deliverPhoto(path, true, distance, null);
            return;
        }
        final EditText input = new EditText(this);
        input.setHint("Contoh: antar pesanan ke pelanggan, motor mogok di jalan…");
        input.setMinLines(2);
        input.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        int pad = Math.round(getResources().getDisplayMetrics().density * 20);
        FrameLayout box = new FrameLayout(this);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(input);

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("Absen di Luar Area")
                .setMessage("Anda berada ± " + distance + " m dari titik absensi (batas "
                        + radius + " m).\n\nTuliskan alasan absen di luar area. "
                        + "Alasan ini tersimpan di server sebagai bukti untuk laporan & penggajian.")
                .setView(box)
                .setCancelable(false)
                .setPositiveButton("Simpan Alasan", null)   // di-override supaya dialog tak menutup saat kosong
                .create();
        dlg.show();
        // Validasi tanpa menutup dialog: alasan kosong = tidak ada bukti, jadi tombolnya menolak.
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String reason = input.getText() != null ? input.getText().toString().trim() : "";
            if (reason.length() < 5) {
                input.setError("Alasan wajib diisi (minimal 5 huruf)");
                return;
            }
            dlg.dismiss();
            deliverPhoto(path, true, distance, reason);
        });
    }

    private void deliverPhoto(String path, boolean outOfRadius, int distance, String reason) {
        Intent data = new Intent();
        data.putExtra(EXTRA_PHOTO_PATH, path);
        data.putExtra(EXTRA_OUT_OF_RADIUS, outOfRadius);
        data.putExtra(EXTRA_DISTANCE_M, distance);
        if (reason != null) data.putExtra(EXTRA_RADIUS_REASON, reason);
        setResult(RESULT_OK, data);
        finish();
    }

    private void finishNoPhoto() {
        setResult(RESULT_CANCELED);
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { if (locCancel != null) locCancel.cancel(); } catch (Throwable ignored) {}
        handler.removeCallbacksAndMessages(null);
    }

    // -------------------------------------------------------- map PiP overlay

    /** Tempel peta koordinat (PiP) di pojok kanan bawah foto. */
    private void composeMapOverlay(File photoFile) {
        Bitmap base = decodeUpright(photoFile);
        if (base == null) return;
        try {
            Canvas canvas = new Canvas(base);
            int w = base.getWidth(), h = base.getHeight();

            // Watermark waktu di kiri bawah.
            Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
            tp.setColor(Color.WHITE);
            tp.setTextSize(w * 0.035f);
            tp.setShadowLayer(4f, 1f, 1f, Color.BLACK);
            String stamp = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss",
                    new Locale("id", "ID")).format(new Date());
            canvas.drawText(stamp, w * 0.03f, h - w * 0.03f, tp);

            Location loc = selfieLocation != null ? selfieLocation : getLastLocation();
            if (loc != null) {
                Bitmap map = fetchMapBitmap(loc.getLatitude(), loc.getLongitude(), 16);
                int pip = (int) (w * 0.34f);
                int margin = (int) (w * 0.03f);
                int border = Math.max(2, (int) (w * 0.006f));
                int left = w - pip - margin, top = h - pip - margin;
                Rect dst = new Rect(left, top, left + pip, top + pip);

                Paint bg = new Paint();
                bg.setColor(Color.WHITE);
                canvas.drawRect(left - border, top - border,
                        left + pip + border, top + pip + border, bg);

                if (map != null) {
                    Paint ip = new Paint(Paint.FILTER_BITMAP_FLAG);
                    canvas.drawBitmap(map, null, dst, ip);
                    map.recycle();
                } else {
                    Paint gray = new Paint();
                    gray.setColor(Color.LTGRAY);
                    canvas.drawRect(dst, gray);
                }
                // Label koordinat di bawah PiP.
                Paint cp = new Paint(Paint.ANTI_ALIAS_FLAG);
                cp.setColor(Color.WHITE);
                cp.setTextSize(w * 0.026f);
                cp.setShadowLayer(3f, 1f, 1f, Color.BLACK);
                String coord = String.format(Locale.US, "%.5f, %.5f",
                        loc.getLatitude(), loc.getLongitude());
                canvas.drawText(coord, left, top - border - w * 0.012f, cp);
            }

            try (FileOutputStream out = new FileOutputStream(photoFile)) {
                base.compress(Bitmap.CompressFormat.JPEG, 88, out);
            }
            // Tulis geotag GPS ke EXIF (tag lokasi WAJIB). compress() di atas menghapus EXIF,
            // jadi ditulis SETELAH file final tersimpan agar foto benar-benar ber-geotag.
            Location geo = selfieLocation != null ? selfieLocation : getLastLocation();
            if (geo != null) {
                try {
                    androidx.exifinterface.media.ExifInterface exif =
                            new androidx.exifinterface.media.ExifInterface(photoFile.getAbsolutePath());
                    exif.setLatLong(geo.getLatitude(), geo.getLongitude());
                    exif.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_GPS_DATESTAMP,
                            new SimpleDateFormat("yyyy:MM:dd", Locale.US).format(new Date()));
                    exif.saveAttributes();
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {
        } finally {
            base.recycle();
        }
    }

    /** Decode foto + koreksi rotasi EXIF → bitmap mutable upright. */
    private Bitmap decodeUpright(File f) {
        try {
            BitmapFactory.Options opt = new BitmapFactory.Options();
            Bitmap bmp = BitmapFactory.decodeFile(f.getAbsolutePath(), opt);
            if (bmp == null) return null;
            int orientation = new androidx.exifinterface.media.ExifInterface(f.getAbsolutePath())
                    .getAttributeInt(
                            androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
                            androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL);
            int rot = 0;
            switch (orientation) {
                case androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90: rot = 90; break;
                case androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180: rot = 180; break;
                case androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270: rot = 270; break;
            }
            Bitmap upright;
            if (rot != 0) {
                Matrix m = new Matrix();
                m.postRotate(rot);
                upright = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
                if (upright != bmp) bmp.recycle();
            } else {
                upright = bmp;
            }
            // Activity ini dikunci portrait → selfie yang benar SELALU portrait. Sebagian perangkat
            // menulis EXIF orientation dengan benar (setelah koreksi di atas hasilnya sudah portrait);
            // sebagian lain (quirk CameraX kamera depan) menyimpan piksel landscape dgn EXIF NORMAL.
            // Dulu di sini ada rotasi -90° TANPA SYARAT untuk quirk itu — yang justru MEMBALIK foto
            // portrait yang sudah benar di perangkat ber-EXIF benar ("banyak selfie tidak potrait").
            // Kini rotasi hanya diterapkan BILA hasilnya masih landscape; foto portrait dibiarkan.
            if (upright.getWidth() > upright.getHeight()) {
                Matrix ccw = new Matrix();
                ccw.postRotate(-90);
                Bitmap rotated = Bitmap.createBitmap(
                        upright, 0, 0, upright.getWidth(), upright.getHeight(), ccw, true);
                if (rotated != upright) upright.recycle();
                upright = rotated;
            }
            // Clamp ke 720p (sisi terpanjang ≤ 1280 px).
            int longSide = Math.max(upright.getWidth(), upright.getHeight());
            if (longSide > 1280) {
                float fr = 1280f / longSide;
                Bitmap scaled = Bitmap.createScaledBitmap(upright,
                        Math.round(upright.getWidth() * fr),
                        Math.round(upright.getHeight() * fr), true);
                if (scaled != upright) upright.recycle();
                upright = scaled;
            }
            // Pastikan mutable untuk Canvas.
            if (!upright.isMutable()) {
                Bitmap copy = upright.copy(Bitmap.Config.ARGB_8888, true);
                upright.recycle();
                upright = copy;
            }
            return upright;
        } catch (Throwable t) {
            return null;
        }
    }

    @SuppressWarnings("MissingPermission")
    private Location getLastLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            return null;
        }
        try {
            LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) return null;
            Location l = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (l == null) l = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            return l;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Ambil 1 tile basemap yang memuat koordinat + gambar marker merah di titiknya. */
    private Bitmap fetchMapBitmap(double lat, double lng, int z) {
        HttpURLConnection conn = null;
        try {
            double n = Math.pow(2, z);
            double xt = (lng + 180.0) / 360.0 * n;
            double latRad = Math.toRadians(lat);
            double yt = (1 - Math.log(Math.tan(latRad) + 1 / Math.cos(latRad)) / Math.PI) / 2 * n;
            int xtile = (int) Math.floor(xt);
            int ytile = (int) Math.floor(yt);

            URL url = new URL(MapTiles.tileUrl(z, xtile, ytile));
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestProperty("User-Agent", MapTiles.userAgent());
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            try (InputStream in = conn.getInputStream()) {
                Bitmap tile = BitmapFactory.decodeStream(in);
                if (tile == null) return null;
                Bitmap m = tile.copy(Bitmap.Config.ARGB_8888, true);
                tile.recycle();
                Canvas c = new Canvas(m);
                float px = (float) ((xt - xtile) * 256);
                float py = (float) ((yt - ytile) * 256);
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                p.setColor(Color.parseColor("#E53935"));
                c.drawCircle(px, py, 9f, p);
                p.setColor(Color.WHITE);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(3f);
                c.drawCircle(px, py, 9f, p);
                return m;
            }
        } catch (Throwable t) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
