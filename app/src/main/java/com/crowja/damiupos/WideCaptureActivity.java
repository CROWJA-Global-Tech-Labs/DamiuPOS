package com.crowja.damiupos;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ZoomState;
import androidx.camera.view.CameraController;
import androidx.camera.view.LifecycleCameraController;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.util.Locale;

/**
 * Kamera DALAM aplikasi untuk foto tempat pelanggan (Tambah/Edit Pelanggan: foto rumah & foto per
 * lokasi). Menggantikan kamera sistem ({@code ACTION_IMAGE_CAPTURE}) karena lewat intent itu Android
 * tak punya cara resmi memilih lensa atau zoom — aplikasi kamera bawaan mengingat zoom & lensa
 * terakhir penggunanya (lihat {@link com.crowja.damiupos.util.CameraIntents}).
 *
 * <p>Di sini pasti: kamera BELAKANG, zoom PALING KECIL ({@code setLinearZoom(0)} = minZoomRatio).
 * Pada HP yang membuka lensa ultra-wide ke aplikasi lewat kamera logis (mis. banyak Samsung/Pixel),
 * minZoomRatio &lt; 1× (0.5×–0.6×) dan CameraX otomatis berpindah ke lensa WIDE; HP lain berhenti di
 * 1× (bidang terlebar yang tersedia). Pinch-zoom tetap bisa dipakai petugas.
 *
 * <p>Kontrak sama dengan kamera sistem: pemanggil memberi path file tujuan ({@link #EXTRA_OUTPUT_PATH});
 * sukses → {@code RESULT_OK} dan fotonya sudah tertulis di path itu.
 */
public class WideCaptureActivity extends AppCompatActivity {

    public static final String EXTRA_OUTPUT_PATH = "output_path";
    private static final int REQ_CAMERA = 9101;

    private PreviewView previewView;
    private LifecycleCameraController controller;
    private TextView btnShutter;
    private TextView tvZoom;
    private boolean capturing;

    public static Intent intent(Context ctx, File output) {
        return new Intent(ctx, WideCaptureActivity.class).putExtra(EXTRA_OUTPUT_PATH, output.getAbsolutePath());
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildLayout());
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CAMERA) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera();
            } else {
                Toast.makeText(this, "Izin kamera ditolak.", Toast.LENGTH_SHORT).show();
                setResult(RESULT_CANCELED);
                finish();
            }
        }
    }

    private View buildLayout() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        previewView = new PreviewView(this);
        root.addView(previewView, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(0x99000000);
        int pad = dp(16);
        bar.setPadding(pad, pad, pad, pad);

        TextView cancel = new TextView(this);
        cancel.setText("Batal");
        cancel.setTextColor(Color.WHITE);
        cancel.setTextSize(16f);
        cancel.setPadding(dp(8), dp(8), dp(8), dp(8));
        cancel.setOnClickListener(v -> { setResult(RESULT_CANCELED); finish(); });
        bar.addView(cancel, new LinearLayout.LayoutParams(0, -2, 1f));

        btnShutter = new TextView(this);
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setColor(Color.WHITE);
        ring.setStroke(dp(4), 0xFF90CAF9);
        btnShutter.setBackground(ring);
        btnShutter.setContentDescription("Ambil foto");
        btnShutter.setOnClickListener(v -> takePhoto());
        bar.addView(btnShutter, new LinearLayout.LayoutParams(dp(72), dp(72)));

        tvZoom = new TextView(this);
        tvZoom.setTextColor(Color.WHITE);
        tvZoom.setTextSize(15f);
        tvZoom.setGravity(Gravity.END);
        tvZoom.setText("—");
        // Ketuk label zoom → kembali ke bidang terlebar (setelah petugas sempat pinch-zoom).
        tvZoom.setOnClickListener(v -> { if (controller != null) controller.setLinearZoom(0f); });
        bar.addView(tvZoom, new LinearLayout.LayoutParams(0, -2, 1f));

        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.gravity = Gravity.BOTTOM;
        root.addView(bar, blp);

        TextView hint = new TextView(this);
        hint.setText("Foto tempat pelanggan — ambil dari jalan, sertakan patokan (pagar, warna rumah, tetangga).");
        hint.setTextColor(Color.WHITE);
        hint.setTextSize(13f);
        hint.setBackgroundColor(0x99000000);
        hint.setPadding(pad, dp(12), pad, dp(12));
        root.addView(hint, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
        return root;
    }

    private void startCamera() {
        try {
            controller = new LifecycleCameraController(this);
            controller.setEnabledUseCases(CameraController.IMAGE_CAPTURE);
            controller.setImageCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY);
            controller.setCameraSelector(CameraSelector.DEFAULT_BACK_CAMERA);
            controller.bindToLifecycle(this);
            previewView.setController(controller);
            // Zoom terkecil begitu kamera terikat (ZoomState baru terisi setelah bind — setLinearZoom
            // sebelum itu diam-diam tak berefek; pola sama dgn PhotoCoordinateActivity). Setelah itu
            // observer tetap hidup hanya untuk memperbarui label, tak lagi menyetel zoom.
            final boolean[] applied = {false};
            controller.getZoomState().observe(this, (ZoomState state) -> {
                if (state == null) return;
                if (!applied[0]) {
                    applied[0] = true;
                    try { controller.setLinearZoom(0f); } catch (Throwable ignored) {}
                }
                float r = state.getZoomRatio();
                boolean wide = state.getMinZoomRatio() < 0.99f;
                tvZoom.setText(String.format(Locale.US, "%.1f×", r) + (wide && r < 0.99f ? " · wide" : ""));
            });
        } catch (Throwable t) {
            Toast.makeText(this, "Kamera tidak tersedia", Toast.LENGTH_SHORT).show();
            setResult(RESULT_CANCELED);
            finish();
        }
    }

    private void takePhoto() {
        if (controller == null || capturing) return;
        String path = getIntent().getStringExtra(EXTRA_OUTPUT_PATH);
        if (path == null) {
            setResult(RESULT_CANCELED);
            finish();
            return;
        }
        capturing = true;
        btnShutter.setEnabled(false);
        File out = new File(path);
        ImageCapture.OutputFileOptions opts = new ImageCapture.OutputFileOptions.Builder(out).build();
        controller.takePicture(opts, ContextCompat.getMainExecutor(this), new ImageCapture.OnImageSavedCallback() {
            @Override public void onImageSaved(@NonNull ImageCapture.OutputFileResults r) {
                setResult(RESULT_OK, new Intent().putExtra(EXTRA_OUTPUT_PATH, path));
                finish();
            }

            @Override public void onError(@NonNull ImageCaptureException e) {
                capturing = false;
                btnShutter.setEnabled(true);
                Toast.makeText(WideCaptureActivity.this, "Gagal mengambil foto", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
