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
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

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
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Workflow tunggal "Ambil Foto &amp; Koordinat" untuk pelanggan (tambah/edit): preview kamera
 * belakang live, dengan info koordinat + mini-map di pojok kiri bawah yang ter-update mengikuti GPS.
 *
 * <p>Tombol shutter HANYA muncul saat akurasi GPS ≤ {@link #MAX_ACCURACY_M} meter — sampai itu
 * tercapai, ditampilkan info akurasi saat ini. Hasil: path foto (dengan overlay peta + koordinat
 * tertempel) BESERTA lat/lng/akurasi dikembalikan ke pemanggil.
 */
public class PhotoCoordinateActivity extends AppCompatActivity {

    public static final String EXTRA_PHOTO_PATH = "photo_path";
    public static final String EXTRA_LAT = "lat";
    public static final String EXTRA_LNG = "lng";
    public static final String EXTRA_ACCURACY = "accuracy";

    /** Akurasi maksimal (meter) sebelum shutter diaktifkan. */
    private static final float MAX_ACCURACY_M = 10f;
    private static final int REQ_PERMS = 911;

    private PreviewView previewView;
    private ImageView ivMiniMap, btnShutter;
    private ProgressBar mapSpinner;
    private TextView tvCoord, tvAccuracy, tvGpsHint;

    private LifecycleCameraController controller;
    private FusedLocationProviderClient fused;
    private LocationCallback locCallback;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private volatile Location lastLocation;
    private boolean capturing = false;
    private boolean shutterReady = false;

    // Mini-map: throttle fetch (network) + hanya bila titik bergeser cukup jauh.
    private long lastMapFetchMs = 0;
    private double mapShownLat = Double.NaN, mapShownLng = Double.NaN;
    private volatile boolean fetchingMap = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_photo_coordinate);

        previewView = findViewById(R.id.previewView);
        ivMiniMap = findViewById(R.id.ivMiniMap);
        mapSpinner = findViewById(R.id.mapSpinner);
        btnShutter = findViewById(R.id.btnShutter);
        tvCoord = findViewById(R.id.tvCoord);
        tvAccuracy = findViewById(R.id.tvAccuracy);
        tvGpsHint = findViewById(R.id.tvGpsHint);

        findViewById(R.id.btnClose).setOnClickListener(v -> { setResult(RESULT_CANCELED); finish(); });
        btnShutter.setOnClickListener(v -> capture());

        fused = LocationServices.getFusedLocationProviderClient(this);
        ensurePermissionsThenStart();
    }

    private void ensurePermissionsThenStart() {
        boolean cam = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
        boolean loc = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        if (cam && loc) {
            startCamera();
            startLocationUpdates();
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION}, REQ_PERMS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_PERMS) return;
        boolean cam = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
        boolean loc = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        if (cam && loc) {
            startCamera();
            startLocationUpdates();
        } else {
            new AlertDialog.Builder(this)
                    .setTitle("Izin diperlukan")
                    .setMessage("Kamera dan lokasi (GPS) wajib untuk mengambil foto + koordinat pelanggan.")
                    .setCancelable(false)
                    .setPositiveButton("Tutup", (d, w) -> { setResult(RESULT_CANCELED); finish(); })
                    .show();
        }
    }

    private void startCamera() {
        try {
            controller = new LifecycleCameraController(this);
            controller.setEnabledUseCases(CameraController.IMAGE_CAPTURE);
            controller.setImageCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY);
            controller.setCameraSelector(CameraSelector.DEFAULT_BACK_CAMERA);   // foto rumah/lokasi
            controller.bindToLifecycle(this);
            previewView.setController(controller);
        } catch (Throwable t) {
            android.util.Log.e("PhotoCoord", "startCamera failed", t);
            Toast.makeText(this, "Kamera tidak tersedia", Toast.LENGTH_SHORT).show();
            setResult(RESULT_CANCELED);
            finish();
        }
    }

    @SuppressWarnings("MissingPermission")
    private void startLocationUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) return;
        LocationRequest req = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
                .setMinUpdateIntervalMillis(500L)
                .build();
        locCallback = new LocationCallback() {
            @Override public void onLocationResult(@NonNull LocationResult result) {
                Location loc = result.getLastLocation();
                if (loc != null) onLocation(loc);
            }
        };
        try {
            fused.requestLocationUpdates(req, locCallback, Looper.getMainLooper());
        } catch (Throwable t) {
            android.util.Log.e("PhotoCoord", "requestLocationUpdates failed", t);
        }
    }

    /** Location baru → perbarui teks koordinat/akurasi, gate shutter, refresh mini-map (throttled). */
    private void onLocation(Location loc) {
        lastLocation = loc;
        double lat = loc.getLatitude(), lng = loc.getLongitude();
        float acc = loc.hasAccuracy() ? loc.getAccuracy() : Float.MAX_VALUE;

        tvCoord.setText(String.format(Locale.US, "%.6f, %.6f", lat, lng));
        boolean ok = acc <= MAX_ACCURACY_M;
        tvAccuracy.setText("Akurasi: " + (acc == Float.MAX_VALUE ? "—" : Math.round(acc) + " m"));
        tvAccuracy.setTextColor(ok ? Color.parseColor("#66BB6A") : Color.parseColor("#FFD54F"));

        setShutterReady(ok, acc);
        maybeRefreshMiniMap(lat, lng);
    }

    /** Shutter hanya terlihat saat akurasi cukup; sebelum itu tampilkan info kebutuhan akurasi. */
    private void setShutterReady(boolean ready, float acc) {
        if (ready == shutterReady) {
            if (!ready) updateWaitingHint(acc);
            return;
        }
        shutterReady = ready;
        if (ready) {
            btnShutter.setVisibility(View.VISIBLE);
            tvGpsHint.setText("✓ Akurasi cukup — silakan ambil foto");
            tvGpsHint.setBackgroundColor(Color.parseColor("#B300695C"));
        } else {
            btnShutter.setVisibility(View.GONE);
            updateWaitingHint(acc);
        }
    }

    private void updateWaitingHint(float acc) {
        String now = acc == Float.MAX_VALUE ? "mencari…" : Math.round(acc) + " m";
        tvGpsHint.setText("Menunggu akurasi GPS (saat ini " + now + ", butuh ≤ "
                + (int) MAX_ACCURACY_M + " m). Pastikan berada di luar ruangan.");
        tvGpsHint.setBackgroundColor(Color.parseColor("#99000000"));
    }

    /** Fetch tile basemap yang memuat titik (throttle: ≥3s DAN titik bergeser >~8m) → mini-map live. */
    private void maybeRefreshMiniMap(double lat, double lng) {
        long now = System.currentTimeMillis();
        boolean firstTime = Double.isNaN(mapShownLat);
        boolean moved = !firstTime && distMeters(lat, lng, mapShownLat, mapShownLng) > 8;
        if (fetchingMap) return;
        if (!firstTime && !moved && now - lastMapFetchMs < 3000) return;
        lastMapFetchMs = now;
        fetchingMap = true;
        new Thread(() -> {
            Bitmap map = fetchMapBitmap(lat, lng, 17);
            runOnUiThread(() -> {
                fetchingMap = false;
                if (isFinishing()) return;
                if (map != null) {
                    ivMiniMap.setImageBitmap(map);
                    mapSpinner.setVisibility(View.GONE);
                    mapShownLat = lat; mapShownLng = lng;
                }
            });
        }, "minimap-fetch").start();
    }

    private void capture() {
        if (capturing || controller == null) return;
        Location loc = lastLocation;
        if (loc == null || !loc.hasAccuracy() || loc.getAccuracy() > MAX_ACCURACY_M) {
            Toast.makeText(this, "Akurasi GPS belum cukup", Toast.LENGTH_SHORT).show();
            return;
        }
        capturing = true;
        btnShutter.setEnabled(false);
        tvGpsHint.setText("Menyimpan foto…");

        File dir = new File(getExternalFilesDir(null), "customer_photos");
        if (!dir.exists()) dir.mkdirs();
        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        final File photoFile = new File(dir, "cust_" + ts + ".jpg");
        final double lat = loc.getLatitude(), lng = loc.getLongitude();
        final float acc = loc.getAccuracy();

        ImageCapture.OutputFileOptions opts =
                new ImageCapture.OutputFileOptions.Builder(photoFile).build();
        controller.takePicture(opts, ContextCompat.getMainExecutor(this),
                new ImageCapture.OnImageSavedCallback() {
                    @Override public void onImageSaved(@NonNull ImageCapture.OutputFileResults r) {
                        new Thread(() -> {
                            try { composeOverlay(photoFile, lat, lng, acc); } catch (Throwable ignored) {}
                            runOnUiThread(() -> finishWith(photoFile.getAbsolutePath(), lat, lng, acc));
                        }, "cust-photo-compose").start();
                    }
                    @Override public void onError(@NonNull ImageCaptureException e) {
                        capturing = false;
                        btnShutter.setEnabled(true);
                        Toast.makeText(PhotoCoordinateActivity.this, "Gagal mengambil foto", Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private void finishWith(String path, double lat, double lng, float acc) {
        Intent data = new Intent();
        data.putExtra(EXTRA_PHOTO_PATH, path);
        data.putExtra(EXTRA_LAT, lat);
        data.putExtra(EXTRA_LNG, lng);
        data.putExtra(EXTRA_ACCURACY, acc);
        setResult(RESULT_OK, data);
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (fused != null && locCallback != null) {
            try { fused.removeLocationUpdates(locCallback); } catch (Throwable ignored) {}
        }
        handler.removeCallbacksAndMessages(null);
    }

    // -------------------------------------------------------- overlay + tile

    /** Tempel mini-map PiP + koordinat + waktu ke foto (permanen), lalu tulis geotag EXIF. */
    private void composeOverlay(File photoFile, double lat, double lng, float acc) {
        Bitmap base = decodeUpright(photoFile);
        if (base == null) return;
        try {
            Canvas canvas = new Canvas(base);
            int w = base.getWidth(), h = base.getHeight();

            Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
            tp.setColor(Color.WHITE);
            tp.setTextSize(w * 0.032f);
            tp.setShadowLayer(4f, 1f, 1f, Color.BLACK);
            String stamp = new SimpleDateFormat("dd/MM/yyyy HH:mm", new Locale("id", "ID")).format(new Date());
            canvas.drawText(stamp, w * 0.03f, h - w * 0.03f, tp);

            Bitmap map = fetchMapBitmap(lat, lng, 17);
            int pip = (int) (w * 0.34f);
            int margin = (int) (w * 0.03f);
            int border = Math.max(2, (int) (w * 0.006f));
            int left = w - pip - margin, top = h - pip - margin;
            Rect dst = new Rect(left, top, left + pip, top + pip);

            Paint bg = new Paint();
            bg.setColor(Color.WHITE);
            canvas.drawRect(left - border, top - border, left + pip + border, top + pip + border, bg);
            if (map != null) {
                canvas.drawBitmap(map, null, dst, new Paint(Paint.FILTER_BITMAP_FLAG));
                map.recycle();
            } else {
                Paint gray = new Paint(); gray.setColor(Color.LTGRAY);
                canvas.drawRect(dst, gray);
            }
            Paint cp = new Paint(Paint.ANTI_ALIAS_FLAG);
            cp.setColor(Color.WHITE);
            cp.setTextSize(w * 0.024f);
            cp.setShadowLayer(3f, 1f, 1f, Color.BLACK);
            canvas.drawText(String.format(Locale.US, "%.5f, %.5f (±%dm)", lat, lng, Math.round(acc)),
                    left, top - border - w * 0.012f, cp);

            try (FileOutputStream out = new FileOutputStream(photoFile)) {
                base.compress(Bitmap.CompressFormat.JPEG, 88, out);
            }
            try {
                androidx.exifinterface.media.ExifInterface exif =
                        new androidx.exifinterface.media.ExifInterface(photoFile.getAbsolutePath());
                exif.setLatLong(lat, lng);
                exif.saveAttributes();
            } catch (Throwable ignored) {}
        } catch (Throwable ignored) {
        } finally {
            base.recycle();
        }
    }

    private Bitmap decodeUpright(File f) {
        try {
            Bitmap bmp = BitmapFactory.decodeFile(f.getAbsolutePath());
            if (bmp == null) return null;
            int orientation = new androidx.exifinterface.media.ExifInterface(f.getAbsolutePath())
                    .getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
                            androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL);
            int rot = 0;
            switch (orientation) {
                case androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90: rot = 90; break;
                case androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180: rot = 180; break;
                case androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270: rot = 270; break;
            }
            Bitmap up;
            if (rot != 0) {
                Matrix m = new Matrix(); m.postRotate(rot);
                up = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
                if (up != bmp) bmp.recycle();
            } else { up = bmp; }
            int longSide = Math.max(up.getWidth(), up.getHeight());
            if (longSide > 1280) {
                float fr = 1280f / longSide;
                Bitmap sc = Bitmap.createScaledBitmap(up, Math.round(up.getWidth() * fr),
                        Math.round(up.getHeight() * fr), true);
                if (sc != up) up.recycle();
                up = sc;
            }
            if (!up.isMutable()) {
                Bitmap c = up.copy(Bitmap.Config.ARGB_8888, true);
                up.recycle(); up = c;
            }
            return up;
        } catch (Throwable t) { return null; }
    }

    private static double distMeters(double la1, double lo1, double la2, double lo2) {
        double dLat = Math.toRadians(la2 - la1), dLon = Math.toRadians(lo2 - lo1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(la1)) * Math.cos(Math.toRadians(la2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 6371000 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /** Satu tile basemap yang memuat koordinat + marker merah di titiknya. */
    private Bitmap fetchMapBitmap(double lat, double lng, int z) {
        HttpURLConnection conn = null;
        try {
            double n = Math.pow(2, z);
            double xt = (lng + 180.0) / 360.0 * n;
            double latRad = Math.toRadians(lat);
            double yt = (1 - Math.log(Math.tan(latRad) + 1 / Math.cos(latRad)) / Math.PI) / 2 * n;
            int xtile = (int) Math.floor(xt), ytile = (int) Math.floor(yt);

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
                float px = (float) ((xt - xtile) * 256), py = (float) ((yt - ytile) * 256);
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
