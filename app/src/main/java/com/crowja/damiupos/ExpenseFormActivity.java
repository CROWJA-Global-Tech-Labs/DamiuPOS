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
import android.view.View;
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

    private EditText etName, etAmount, etNote, etLiter, etPcs;
    private ImageView ivFoto;
    private com.google.android.material.button.MaterialButtonToggleGroup toggleKategori;
    private View tilName, tilLiter, tilPcs;
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
        etLiter = findViewById(R.id.etLiter);
        etPcs = findViewById(R.id.etPcs);
        ivFoto = findViewById(R.id.ivFotoStruk);
        toggleKategori = findViewById(R.id.toggleKategori);
        tilName = findViewById(R.id.tilName);
        tilLiter = findViewById(R.id.tilLiter);
        tilPcs = findViewById(R.id.tilPcs);
        // Input yang relevan tergantung kategori: Air Baku→liter, Segel/Tutup→pcs,
        // Umum→nama. Nama disembunyikan untuk kategori non-Umum.
        toggleKategori.addOnButtonCheckedListener((g, id, checked) -> {
            if (checked) applyCategoryUi(id);
        });

        // Prefill kalau edit mode
        editingId = getIntent().getLongExtra(EXTRA_EXPENSE_ID, 0);
        if (editingId > 0) {
            toolbar.setTitle("Edit Pengeluaran");
            Expense e = expenseDao.getById(editingId);
            if (e != null) {
                etName.setText(e.getName());
                etAmount.setText(String.valueOf(Math.round(e.getAmount())));
                etNote.setText(e.getNote() != null ? e.getNote() : "");
                if (e.isAirBaku()) {
                    toggleKategori.check(R.id.btnKatAirBaku);
                    if (e.getLiters() > 0) etLiter.setText(trimLiters(e.getLiters()));
                } else if (e.isSegel()) {
                    toggleKategori.check(R.id.btnKatSegel);
                    if (e.getPcs() > 0) etPcs.setText(String.valueOf(e.getPcs()));
                } else if (e.isTutup()) {
                    toggleKategori.check(R.id.btnKatTutup);
                    if (e.getPcs() > 0) etPcs.setText(String.valueOf(e.getPcs()));
                } else {
                    toggleKategori.check(R.id.btnKatUmum);
                }
                if (e.getPhotoPath() != null && !e.getPhotoPath().isEmpty()) {
                    currentPhotoPath = e.getPhotoPath();
                    ivFoto.setImageBitmap(loadRotatedBitmap(currentPhotoPath));
                }
            }
        }
        // Sinkronkan tampilan dengan kategori terpilih (default: Air Baku).
        applyCategoryUi(toggleKategori.getCheckedButtonId());

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

    /** Atur visibilitas input nama/liter/pcs sesuai kategori terpilih. */
    private void applyCategoryUi(int checkedId) {
        boolean airBaku = checkedId == R.id.btnKatAirBaku;
        boolean pcsCat = checkedId == R.id.btnKatSegel || checkedId == R.id.btnKatTutup;
        boolean umum = checkedId == R.id.btnKatUmum;
        tilName.setVisibility(umum ? View.VISIBLE : View.GONE);
        tilLiter.setVisibility(airBaku ? View.VISIBLE : View.GONE);
        tilPcs.setVisibility(pcsCat ? View.VISIBLE : View.GONE);
    }

    private String categoryFromToggle() {
        int id = toggleKategori.getCheckedButtonId();
        if (id == R.id.btnKatAirBaku) return Expense.CAT_AIR_BAKU;
        if (id == R.id.btnKatSegel) return Expense.CAT_SEGEL;
        if (id == R.id.btnKatTutup) return Expense.CAT_TUTUP;
        return Expense.CAT_UMUM;
    }

    /** Nama otomatis untuk kategori non-Umum (pakai label kategori). */
    private static String autoName(String category) {
        Expense tmp = new Expense();
        tmp.setCategory(category);
        return tmp.getCategoryLabel();
    }

    private void save() {
        String category = categoryFromToggle();
        boolean isUmum = Expense.CAT_UMUM.equals(category);
        boolean isAirBaku = Expense.CAT_AIR_BAKU.equals(category);
        boolean isPcsCat = Expense.CAT_SEGEL.equals(category) || Expense.CAT_TUTUP.equals(category);

        // Nama: manual hanya untuk Umum; kategori lain pakai label kategori.
        String name;
        if (isUmum) {
            name = etName.getText().toString().trim();
            if (TextUtils.isEmpty(name)) {
                Toast.makeText(this, "Nama pengeluaran wajib diisi", Toast.LENGTH_SHORT).show();
                etName.requestFocus();
                return;
            }
        } else {
            name = autoName(category);
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

        double liters = 0;
        int pcs = 0;
        if (isAirBaku) {
            String ls = etLiter.getText() != null ? etLiter.getText().toString().trim() : "";
            try {
                liters = ls.isEmpty() ? 0 : Double.parseDouble(ls);
            } catch (NumberFormatException ex) {
                liters = 0;
            }
            if (liters <= 0) {
                etLiter.setError("Isi jumlah liter air baku");
                etLiter.requestFocus();
                return;
            }
        } else if (isPcsCat) {
            String ps = etPcs.getText() != null ? etPcs.getText().toString().trim() : "";
            try {
                pcs = ps.isEmpty() ? 0 : Integer.parseInt(ps);
            } catch (NumberFormatException ex) {
                pcs = 0;
            }
            if (pcs <= 0) {
                etPcs.setError("Isi jumlah pcs");
                etPcs.requestFocus();
                return;
            }
        }

        Expense e = editingId > 0 ? expenseDao.getById(editingId) : new Expense();
        if (e == null) e = new Expense();
        e.setName(name);
        e.setAmount(amount);
        e.setPhotoPath(currentPhotoPath);
        e.setNote(etNote.getText().toString().trim());
        e.setCategory(category);
        e.setLiters(liters);
        e.setPcs(pcs);

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

    /** Tampilkan liter tanpa ".0" kalau bulat. */
    private static String trimLiters(double v) {
        return v == Math.floor(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    /** Preview foto hemat memori (downsample + RGB_565 + EXIF). */
    private Bitmap loadRotatedBitmap(String photoPath) {
        return com.crowja.damiupos.util.BitmapUtils.decodeSampled(photoPath, 1024, 1024);
    }
}
