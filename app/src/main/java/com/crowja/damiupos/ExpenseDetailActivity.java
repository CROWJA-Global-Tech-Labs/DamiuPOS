package com.crowja.damiupos;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.exifinterface.media.ExifInterface;

import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.ExpenseDao;
import com.crowja.damiupos.model.Expense;

import java.io.File;
import java.io.IOException;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * View detail satu pengeluaran: name, nominal, tanggal, full-size foto, note.
 * Aksi: Edit (buka form pre-filled) / Hapus (konfirmasi).
 */
public class ExpenseDetailActivity extends AppCompatActivity {

    public static final String EXTRA_EXPENSE_ID = "expense_id";

    private ExpenseDao expenseDao;
    private long expenseId;

    private static final NumberFormat NF = NumberFormat.getInstance(new Locale("id", "ID"));

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_expense_detail);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        expenseDao = new ExpenseDao(DatabaseHelper.getInstance(this));
        expenseId = getIntent().getLongExtra(EXTRA_EXPENSE_ID, 0);
        if (expenseId <= 0) {
            Toast.makeText(this, "ID pengeluaran tidak valid", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        findViewById(R.id.btnEdit).setOnClickListener(v ->
                startActivity(new Intent(this, ExpenseFormActivity.class)
                        .putExtra(ExpenseFormActivity.EXTRA_EXPENSE_ID, expenseId)));

        findViewById(R.id.btnHapus).setOnClickListener(v -> confirmDelete());
    }

    @Override
    protected void onResume() {
        super.onResume();
        bindExpense();
    }

    private void bindExpense() {
        Expense e = expenseDao.getById(expenseId);
        if (e == null) {
            Toast.makeText(this, "Pengeluaran tidak ditemukan (mungkin sudah dihapus)",
                    Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        ((TextView) findViewById(R.id.tvName)).setText(e.getName());
        ((TextView) findViewById(R.id.tvAmount)).setText(
                "- Rp " + NF.format(Math.round(e.getAmount())));
        ((TextView) findViewById(R.id.tvDate)).setText(formatDate(e.getCreatedAt()));

        ImageView iv = findViewById(R.id.ivFoto);
        TextView tvNoPhoto = findViewById(R.id.tvNoPhoto);
        // Decode dulu; baru tampilkan. Kalau decode gagal (null) JANGAN biarkan ImageView
        // kosong "menggantung" — tampilkan placeholder "tidak ada foto" yang jelas.
        Bitmap photo = (e.getPhotoPath() != null && !e.getPhotoPath().isEmpty()
                && new File(e.getPhotoPath()).exists())
                ? loadRotatedBitmap(e.getPhotoPath()) : null;
        if (photo != null) {
            // Clear tint placeholder — tanpa ini bitmap ikut diwarnai (SRC_IN) dan
            // foto struk tampil sebagai kotak abu-abu solid.
            iv.setImageTintList(null);
            iv.setImageBitmap(photo);
            iv.setVisibility(View.VISIBLE);
            tvNoPhoto.setVisibility(View.GONE);
        } else if (e.getPhotoUrl() != null && !e.getPhotoUrl().isEmpty()) {
            // Baris hasil sync (dari web/HP lain): tak ada file lokal, tampilkan dari photo_url.
            loadPhotoFromUrl(iv, tvNoPhoto, e.getId(), e.getPhotoUrl());
        } else {
            iv.setVisibility(View.GONE);
            tvNoPhoto.setVisibility(View.VISIBLE);
        }

        TextView tvNote = findViewById(R.id.tvNote);
        if (e.getNote() != null && !e.getNote().trim().isEmpty()) {
            tvNote.setText(e.getNote());
        } else {
            tvNote.setText("(Tidak ada catatan)");
            tvNote.setTextColor(getResources().getColor(R.color.text_secondary));
        }
    }

    private void confirmDelete() {
        new AlertDialog.Builder(this)
                .setTitle("Hapus pengeluaran?")
                .setMessage("Pengeluaran ini akan dihapus permanen. Foto struk juga akan dihapus dari penyimpanan.")
                .setNegativeButton("Batal", null)
                .setPositiveButton("Hapus", (d, w) -> {
                    Expense e = expenseDao.getById(expenseId);
                    if (e != null && e.getPhotoPath() != null) {
                        // Best-effort: hapus file foto biar tidak nyampah
                        try { new File(e.getPhotoPath()).delete(); } catch (Exception ignored) {}
                    }
                    int n = expenseDao.delete(expenseId);
                    Toast.makeText(this,
                            n > 0 ? "Pengeluaran dihapus" : "Gagal menghapus",
                            Toast.LENGTH_SHORT).show();
                    finish();
                })
                .show();
    }

    private static String formatDate(String iso) {
        if (iso == null || iso.isEmpty()) return "";
        try {
            Date d;
            if (iso.contains("T")) {
                // Baris hasil sync: ISO UTC "2026-06-29T02:24:40.000000Z" → parse UTC, tampil lokal.
                String s = iso.replace("Z", "");
                int dot = s.indexOf('.');
                if (dot > 0) s = s.substring(0, dot);   // buang mikrodetik
                SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
                in.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
                d = in.parse(s);
            } else {
                // Dibuat lokal: wall-clock "yyyy-MM-dd HH:mm:ss".
                d = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).parse(iso);
            }
            if (d == null) return iso;
            SimpleDateFormat out = new SimpleDateFormat(
                    "EEEE, d MMMM yyyy 'pukul' HH:mm",
                    new Locale("id", "ID"));   // tz lokal device → instan UTC dikonversi ke lokal
            return out.format(d);
        } catch (Throwable t) {
            return iso;
        }
    }

    /** Preview foto hemat memori (downsample + RGB_565 + EXIF). */
    private Bitmap loadRotatedBitmap(String photoPath) {
        return com.crowja.damiupos.util.BitmapUtils.decodeSampled(photoPath, 1024, 1024);
    }

    /** Foto struk untuk baris hasil sync (hanya photo_url): unduh ke cache di background lalu tampilkan. */
    private void loadPhotoFromUrl(ImageView iv, TextView tvNoPhoto, long expenseId, String url) {
        iv.setVisibility(View.VISIBLE);          // tampilkan placeholder selama mengunduh
        // Placeholder galeri diberi tint abu-abu via kode (bukan app:tint di XML — itu ikut
        // mewarnai foto asli jadi abu-abu solid); di-clear lagi saat foto tiba di bawah.
        iv.setImageTintList(android.content.res.ColorStateList.valueOf(
                getResources().getColor(R.color.grey_medium)));
        iv.setImageResource(android.R.drawable.ic_menu_gallery);
        tvNoPhoto.setVisibility(View.GONE);
        // Nama cache memuat hash URL (URL ber-?v=… berubah saat foto diganti → unduh ulang otomatis).
        final String name = "expense_" + expenseId + "_" + Integer.toHexString(url.hashCode()) + ".jpg";
        new Thread(() -> {
            java.io.File f = com.crowja.damiupos.util.BitmapUtils.downloadToCache(
                    getApplicationContext(), url, name);
            Bitmap b = f != null
                    ? com.crowja.damiupos.util.BitmapUtils.decodeSampled(f.getAbsolutePath(), 1024, 1024)
                    : null;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (b != null) {
                    iv.setImageTintList(null);   // clear tint placeholder → foto tampil asli
                    iv.setImageBitmap(b);
                    iv.setVisibility(View.VISIBLE);
                    tvNoPhoto.setVisibility(View.GONE);
                } else {
                    iv.setVisibility(View.GONE);
                    tvNoPhoto.setVisibility(View.VISIBLE);
                }
            });
        }).start();
    }
}
