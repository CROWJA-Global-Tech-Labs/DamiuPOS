package com.crowja.damiupos;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.exifinterface.media.ExifInterface;

import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.ExpenseDao;
import com.crowja.damiupos.model.Expense;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Form tambah/edit pengeluaran:
 * <ul>
 *     <li>Nama pengeluaran (wajib)</li>
 *     <li>Nominal Rupiah (wajib, > 0)</li>
 *     <li>Foto struk via kamera atau galeri (opsional) — disimpan di
 *         {@code getExternalFilesDir(Pictures)/expense_*.jpg}</li>
 *     <li>Catatan multiline (opsional, max 500 char)</li>
 * </ul>
 *
 * <p>Re-pakai pola FileProvider + camera dari {@code CustomerFormActivity}.
 */
public class ExpenseFormActivity extends AppCompatActivity {

    public static final String EXTRA_EXPENSE_ID = "expense_id";

    private static final int REQUEST_CAMERA = 4201;
    private static final int REQUEST_PICK_IMAGE = 4202;
    private static final int REQUEST_PERMISSION_CAMERA = 4203;

    private EditText etName, etAmount, etNote;
    private ImageView ivFoto;
    private ExpenseDao expenseDao;
    private String currentPhotoPath;
    private long editingId = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_expense_form);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        expenseDao = new ExpenseDao(DatabaseHelper.getInstance(this));

        etName = findViewById(R.id.etName);
        etAmount = findViewById(R.id.etAmount);
        etNote = findViewById(R.id.etNote);
        ivFoto = findViewById(R.id.ivFotoStruk);

        // Prefill kalau edit mode
        editingId = getIntent().getLongExtra(EXTRA_EXPENSE_ID, 0);
        if (editingId > 0) {
            toolbar.setTitle("Edit Pengeluaran");
            Expense e = expenseDao.getById(editingId);
            if (e != null) {
                etName.setText(e.getName());
                etAmount.setText(String.valueOf(Math.round(e.getAmount())));
                etNote.setText(e.getNote() != null ? e.getNote() : "");
                if (e.getPhotoPath() != null && !e.getPhotoPath().isEmpty()) {
                    currentPhotoPath = e.getPhotoPath();
                    ivFoto.setImageBitmap(loadRotatedBitmap(currentPhotoPath));
                }
            }
        }

        findViewById(R.id.btnFoto).setOnClickListener(v -> takePhoto());
        findViewById(R.id.btnPickGallery).setOnClickListener(v -> pickFromGallery());
        findViewById(R.id.btnSimpan).setOnClickListener(v -> save());
    }

    private void takePhoto() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA}, REQUEST_PERMISSION_CAMERA);
            return;
        }
        Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        if (intent.resolveActivity(getPackageManager()) == null) {
            Toast.makeText(this, "Tidak ada app kamera", Toast.LENGTH_SHORT).show();
            return;
        }
        File photoFile;
        try {
            photoFile = createImageFile();
        } catch (IOException e) {
            Toast.makeText(this, "Gagal membuat file foto: " + e.getMessage(),
                    Toast.LENGTH_SHORT).show();
            return;
        }
        Uri photoURI = FileProvider.getUriForFile(this,
                getApplicationContext().getPackageName() + ".fileprovider",
                photoFile);
        intent.putExtra(MediaStore.EXTRA_OUTPUT, photoURI);
        startActivityForResult(intent, REQUEST_CAMERA);
    }

    private void pickFromGallery() {
        Intent intent = new Intent(Intent.ACTION_PICK,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        intent.setType("image/*");
        try {
            startActivityForResult(intent, REQUEST_PICK_IMAGE);
        } catch (Exception ex) {
            Toast.makeText(this, "Tidak dapat membuka galeri", Toast.LENGTH_SHORT).show();
        }
    }

    private File createImageFile() throws IOException {
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        String fileName = "EXPENSE_" + timeStamp;
        File storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        File image = File.createTempFile(fileName, ".jpg", storageDir);
        currentPhotoPath = image.getAbsolutePath();
        return image;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSION_CAMERA
                && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            takePhoto();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK) return;

        if (requestCode == REQUEST_CAMERA) {
            if (currentPhotoPath != null) {
                ivFoto.setImageBitmap(loadRotatedBitmap(currentPhotoPath));
            }
        } else if (requestCode == REQUEST_PICK_IMAGE && data != null) {
            Uri uri = data.getData();
            if (uri == null) return;
            try {
                File dest = createImageFile();
                copyUriToFile(uri, dest);
                ivFoto.setImageBitmap(loadRotatedBitmap(currentPhotoPath));
            } catch (Exception ex) {
                Toast.makeText(this, "Gagal salin foto: " + ex.getMessage(),
                        Toast.LENGTH_SHORT).show();
                currentPhotoPath = null;
            }
        }
    }

    private void copyUriToFile(Uri src, File dst) throws IOException {
        try (InputStream in = getContentResolver().openInputStream(src);
             OutputStream out = new FileOutputStream(dst)) {
            if (in == null) throw new IOException("Tidak dapat baca foto");
            byte[] buf = new byte[8192];
            int read;
            while ((read = in.read(buf)) > 0) out.write(buf, 0, read);
        }
    }

    private void save() {
        String name = etName.getText().toString().trim();
        if (TextUtils.isEmpty(name)) {
            Toast.makeText(this, "Nama pengeluaran wajib diisi", Toast.LENGTH_SHORT).show();
            etName.requestFocus();
            return;
        }
        String amountStr = etAmount.getText().toString().trim();
        double amount;
        try {
            amount = amountStr.isEmpty() ? 0 : Double.parseDouble(amountStr);
        } catch (NumberFormatException e) {
            Toast.makeText(this, "Nominal tidak valid", Toast.LENGTH_SHORT).show();
            return;
        }
        if (amount <= 0) {
            Toast.makeText(this, "Nominal harus lebih dari 0", Toast.LENGTH_SHORT).show();
            etAmount.requestFocus();
            return;
        }

        Expense e = editingId > 0 ? expenseDao.getById(editingId) : new Expense();
        if (e == null) e = new Expense();
        e.setName(name);
        e.setAmount(amount);
        e.setPhotoPath(currentPhotoPath);
        e.setNote(etNote.getText().toString().trim());

        if (editingId > 0) {
            expenseDao.update(e);
            Toast.makeText(this, "Pengeluaran diupdate", Toast.LENGTH_SHORT).show();
        } else {
            long id = expenseDao.insert(e);
            if (id > 0) {
                Toast.makeText(this, "Pengeluaran tersimpan", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Gagal menyimpan", Toast.LENGTH_SHORT).show();
                return;
            }
        }
        finish();
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
