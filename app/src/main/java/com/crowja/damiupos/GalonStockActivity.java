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
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.exifinterface.media.ExifInterface;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.crowja.damiupos.adapter.StockHistoryAdapter;
import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.GalonStockDao;
import com.google.android.material.textfield.TextInputEditText;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class GalonStockActivity extends AppCompatActivity {

    private static final int REQUEST_CAMERA = 500;
    private static final int REQUEST_PERMISSION_CAMERA = 501;

    private TextView tvStokTersedia, tvStokMasuk, tvGalonKeluar, tvGalonKembali;
    private TextView tvEmptyHistory, tvFormTitle;
    private TextInputEditText etJumlahStok, etCatatanStok;
    private RecyclerView rvStockHistory;
    private ImageView ivStrukPreview;
    private ImageButton btnRemoveStruk;
    private com.google.android.material.button.MaterialButton btnTambahStok;
    private View layoutEditBanner;
    private StockHistoryAdapter adapter;
    private GalonStockDao galonStockDao;

    private String currentPhotoPath; // pending capture path
    /** Id entry stok yang sedang di-edit. 0 = mode tambah baru. */
    private long editingId = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_galon_stock);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        galonStockDao = new GalonStockDao(DatabaseHelper.getInstance(this));

        tvStokTersedia = findViewById(R.id.tvStokTersedia);
        tvStokMasuk = findViewById(R.id.tvStokMasuk);
        tvGalonKeluar = findViewById(R.id.tvGalonKeluar);
        tvGalonKembali = findViewById(R.id.tvGalonKembali);
        tvEmptyHistory = findViewById(R.id.tvEmptyHistory);
        etJumlahStok = findViewById(R.id.etJumlahStok);
        etCatatanStok = findViewById(R.id.etCatatanStok);
        rvStockHistory = findViewById(R.id.rvStockHistory);
        ivStrukPreview = findViewById(R.id.ivStrukPreview);
        btnRemoveStruk = findViewById(R.id.btnRemoveStruk);
        tvFormTitle = findViewById(R.id.tvFormTitle);
        layoutEditBanner = findViewById(R.id.layoutEditBanner);
        btnTambahStok = findViewById(R.id.btnTambahStok);

        adapter = new StockHistoryAdapter(new StockHistoryAdapter.Listener() {
            @Override public void onItemLongClick(long id, int position) { showEditOrDeleteChooser(id); }
            @Override public void onPhotoClick(String photoPath) { showPhoto(photoPath); }
        });
        rvStockHistory.setLayoutManager(new LinearLayoutManager(this));
        rvStockHistory.setAdapter(adapter);

        btnTambahStok.setOnClickListener(v -> saveStock());
        findViewById(R.id.btnFotoStruk).setOnClickListener(v -> takePhoto());
        findViewById(R.id.btnBatalEdit).setOnClickListener(v -> exitEditMode(true));
        btnRemoveStruk.setOnClickListener(v -> clearPendingPhoto());
        ivStrukPreview.setOnClickListener(v -> {
            if (currentPhotoPath != null) showPhoto(currentPhotoPath);
        });

        refreshData();
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
            Toast.makeText(this, "Tidak ada aplikasi kamera", Toast.LENGTH_SHORT).show();
            return;
        }
        File photoFile;
        try {
            photoFile = createImageFile();
        } catch (IOException e) {
            Toast.makeText(this, "Gagal membuat file foto", Toast.LENGTH_SHORT).show();
            return;
        }
        Uri photoURI = FileProvider.getUriForFile(this,
                getApplicationContext().getPackageName() + ".fileprovider", photoFile);
        intent.putExtra(MediaStore.EXTRA_OUTPUT, photoURI);
        startActivityForResult(intent, REQUEST_CAMERA);
    }

    private File createImageFile() throws IOException {
        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        File image = File.createTempFile("STRUK_" + ts, ".jpg", storageDir);
        currentPhotoPath = image.getAbsolutePath();
        return image;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSION_CAMERA) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                takePhoto();
            } else {
                Toast.makeText(this, "Izin kamera ditolak", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CAMERA && resultCode == RESULT_OK && currentPhotoPath != null) {
            Bitmap bmp = loadRotated(currentPhotoPath);
            if (bmp != null) {
                ivStrukPreview.setImageBitmap(bmp);
                ivStrukPreview.setVisibility(View.VISIBLE);
                btnRemoveStruk.setVisibility(View.VISIBLE);
            }
        }
    }

    /** Foto struk hemat memori (downsample + RGB_565 + EXIF). */
    private Bitmap loadRotated(String path) {
        return com.crowja.damiupos.util.BitmapUtils.decodeSampled(path, 1280, 1280);
    }

    private void clearPendingPhoto() {
        if (currentPhotoPath != null) {
            try { new File(currentPhotoPath).delete(); } catch (Exception ignored) {}
            currentPhotoPath = null;
        }
        ivStrukPreview.setImageDrawable(null);
        ivStrukPreview.setVisibility(View.GONE);
        btnRemoveStruk.setVisibility(View.GONE);
    }

    private void showPhoto(String path) {
        if (path == null || path.isEmpty()) return;
        File f = new File(path);
        if (!f.exists()) {
            Toast.makeText(this, "Foto tidak ditemukan", Toast.LENGTH_SHORT).show();
            return;
        }
        Bitmap bmp = loadRotated(path);
        if (bmp == null) return;
        ImageView iv = new ImageView(this);
        iv.setAdjustViewBounds(true);
        iv.setImageBitmap(bmp);
        new AlertDialog.Builder(this)
                .setView(iv)
                .setPositiveButton("Tutup", null)
                .show();
    }

    private void saveStock() {
        String jumlahStr = etJumlahStok.getText() != null ? etJumlahStok.getText().toString().trim() : "";
        String catatan = etCatatanStok.getText() != null ? etCatatanStok.getText().toString().trim() : "";

        if (jumlahStr.isEmpty()) {
            etJumlahStok.setError("Jumlah wajib diisi");
            etJumlahStok.requestFocus();
            return;
        }
        int jumlah;
        try {
            jumlah = Integer.parseInt(jumlahStr);
            if (jumlah <= 0) {
                etJumlahStok.setError("Jumlah harus lebih dari 0");
                etJumlahStok.requestFocus();
                return;
            }
        } catch (NumberFormatException e) {
            etJumlahStok.setError("Format tidak valid");
            etJumlahStok.requestFocus();
            return;
        }

        String catNullable = catatan.isEmpty() ? null : catatan;
        if (editingId > 0) {
            galonStockDao.updateStock(editingId, jumlah, catNullable, currentPhotoPath);
            Toast.makeText(this, "Stok berhasil diupdate", Toast.LENGTH_SHORT).show();
            exitEditMode(false);
        } else {
            galonStockDao.addStock(jumlah, catNullable, currentPhotoPath);
            Toast.makeText(this, "Stok berhasil ditambahkan", Toast.LENGTH_SHORT).show();
        }

        etJumlahStok.setText("");
        etCatatanStok.setText("");
        currentPhotoPath = null;
        ivStrukPreview.setImageDrawable(null);
        ivStrukPreview.setVisibility(View.GONE);
        btnRemoveStruk.setVisibility(View.GONE);
        refreshData();
    }

    /** Long-press chooser: tampilkan dialog dengan opsi Edit / Hapus. */
    private void showEditOrDeleteChooser(long id) {
        new AlertDialog.Builder(this)
                .setTitle("Aksi Stok")
                .setItems(new CharSequence[]{"Edit", "Hapus"}, (dialog, which) -> {
                    if (which == 0) enterEditMode(id);
                    else confirmDeleteStock(id);
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    /** Switch form ke edit mode: prefill fields + show banner + ganti label tombol. */
    private void enterEditMode(long id) {
        String[] row = galonStockDao.getStockById(id);
        if (row == null) {
            Toast.makeText(this, "Data stok tidak ditemukan", Toast.LENGTH_SHORT).show();
            return;
        }
        editingId = id;
        etJumlahStok.setText(row[1]);
        etCatatanStok.setText(row[2] != null ? row[2] : "");

        // Photo preview kalau ada
        String photoPath = row[4];
        if (photoPath != null && !photoPath.isEmpty() && new File(photoPath).exists()) {
            currentPhotoPath = photoPath;
            Bitmap bmp = loadRotated(photoPath);
            if (bmp != null) {
                ivStrukPreview.setImageBitmap(bmp);
                ivStrukPreview.setVisibility(View.VISIBLE);
                btnRemoveStruk.setVisibility(View.VISIBLE);
            }
        } else {
            currentPhotoPath = null;
            ivStrukPreview.setImageDrawable(null);
            ivStrukPreview.setVisibility(View.GONE);
            btnRemoveStruk.setVisibility(View.GONE);
        }

        tvFormTitle.setText("Edit Stok Galon");
        btnTambahStok.setText("Simpan");
        layoutEditBanner.setVisibility(View.VISIBLE);
        etJumlahStok.requestFocus();
    }

    /** Keluar dari edit mode. {@code resetFields} = true kalau Batal manual. */
    private void exitEditMode(boolean resetFields) {
        editingId = 0;
        tvFormTitle.setText("Tambah Stok Galon");
        btnTambahStok.setText("Tambah");
        layoutEditBanner.setVisibility(View.GONE);
        if (resetFields) {
            etJumlahStok.setText("");
            etCatatanStok.setText("");
            currentPhotoPath = null;
            ivStrukPreview.setImageDrawable(null);
            ivStrukPreview.setVisibility(View.GONE);
            btnRemoveStruk.setVisibility(View.GONE);
        }
    }

    private void confirmDeleteStock(long id) {
        new AlertDialog.Builder(this)
                .setTitle("Hapus Stok")
                .setMessage("Yakin ingin menghapus data stok ini?")
                .setPositiveButton("Hapus", (dialog, which) -> {
                    galonStockDao.deleteStock(id);
                    Toast.makeText(this, "Data stok dihapus", Toast.LENGTH_SHORT).show();
                    if (editingId == id) exitEditMode(true);
                    refreshData();
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    private void refreshData() {
        int stokMasuk = galonStockDao.getTotalStokMasuk();
        int galonKeluar = galonStockDao.getTotalGalonKeluar();
        int galonKembali = galonStockDao.getTotalGalonKembali();
        int stokTersedia = stokMasuk - galonKeluar + galonKembali;

        tvStokTersedia.setText(String.valueOf(stokTersedia));
        tvStokMasuk.setText(String.valueOf(stokMasuk));
        tvGalonKeluar.setText(String.valueOf(galonKeluar));
        tvGalonKembali.setText(String.valueOf(galonKembali));

        if (stokTersedia <= 0) tvStokTersedia.setTextColor(getResources().getColor(R.color.red));
        else if (stokTersedia < 10) tvStokTersedia.setTextColor(getResources().getColor(R.color.accent));
        else tvStokTersedia.setTextColor(getResources().getColor(R.color.primary));

        List<String[]> history = galonStockDao.getStockHistory();
        adapter.setData(history);

        if (history.isEmpty()) {
            tvEmptyHistory.setVisibility(TextView.VISIBLE);
            rvStockHistory.setVisibility(RecyclerView.GONE);
        } else {
            tvEmptyHistory.setVisibility(TextView.GONE);
            rvStockHistory.setVisibility(RecyclerView.VISIBLE);
        }
    }
}
