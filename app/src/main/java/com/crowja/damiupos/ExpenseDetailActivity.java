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
        if (e.getPhotoPath() != null && !e.getPhotoPath().isEmpty()
                && new File(e.getPhotoPath()).exists()) {
            iv.setImageBitmap(loadRotatedBitmap(e.getPhotoPath()));
            iv.setVisibility(View.VISIBLE);
            tvNoPhoto.setVisibility(View.GONE);
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
            SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
            Date d = in.parse(iso);
            if (d == null) return iso;
            SimpleDateFormat out = new SimpleDateFormat(
                    "EEEE, d MMMM yyyy 'pukul' HH:mm",
                    new Locale("id", "ID"));
            return out.format(d);
        } catch (Throwable t) {
            return iso;
        }
    }

    private Bitmap loadRotatedBitmap(String photoPath) {
        Bitmap bitmap = BitmapFactory.decodeFile(photoPath);
        if (bitmap == null) return null;
        try {
            ExifInterface exif = new ExifInterface(photoPath);
            int orientation = exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            int rotation = 0;
            switch (orientation) {
                case ExifInterface.ORIENTATION_ROTATE_90: rotation = 90; break;
                case ExifInterface.ORIENTATION_ROTATE_180: rotation = 180; break;
                case ExifInterface.ORIENTATION_ROTATE_270: rotation = 270; break;
            }
            if (rotation != 0) {
                Matrix matrix = new Matrix();
                matrix.postRotate(rotation);
                Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0,
                        bitmap.getWidth(), bitmap.getHeight(), matrix, true);
                bitmap.recycle();
                return rotated;
            }
        } catch (IOException ignored) {}
        return bitmap;
    }
}
