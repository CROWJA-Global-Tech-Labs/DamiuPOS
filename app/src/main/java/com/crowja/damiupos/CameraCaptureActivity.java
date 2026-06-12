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

    private static final int REQ_CAMERA = 901;

    private PreviewView previewView;
    private TextView tvCountdown;
    private LifecycleCameraController controller;
    private boolean capturing = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera_capture);

        previewView = findViewById(R.id.previewView);
        tvCountdown = findViewById(R.id.tvCountdown);
        TextView tvLabel = findViewById(R.id.tvLabel);
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
            startCamera();
        } else {
            // Minta yang belum diberi. Lokasi opsional (untuk peta PiP).
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
            // Kamera wajib; lokasi opsional (peta PiP best-effort).
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED) {
                startCamera();
            } else {
                finishNoPhoto(); // tanpa kamera → lanjut tanpa foto
            }
        }
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

    private void finishWithPhoto(String path) {
        Intent data = new Intent();
        data.putExtra(EXTRA_PHOTO_PATH, path);
        setResult(RESULT_OK, data);
        finish();
    }

    private void finishNoPhoto() {
        setResult(RESULT_CANCELED);
        finish();
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

            Location loc = getLastLocation();
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

    /** Ambil 1 tile OSM yang memuat koordinat + gambar marker merah di titiknya. */
    private Bitmap fetchMapBitmap(double lat, double lng, int z) {
        HttpURLConnection conn = null;
        try {
            double n = Math.pow(2, z);
            double xt = (lng + 180.0) / 360.0 * n;
            double latRad = Math.toRadians(lat);
            double yt = (1 - Math.log(Math.tan(latRad) + 1 / Math.cos(latRad)) / Math.PI) / 2 * n;
            int xtile = (int) Math.floor(xt);
            int ytile = (int) Math.floor(yt);

            URL url = new URL("https://tile.openstreetmap.org/" + z + "/" + xtile + "/" + ytile + ".png");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestProperty("User-Agent", "DAMIU-POS/1.0 (attendance)");
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
