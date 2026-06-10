package com.crowja.damiupos;

import android.Manifest;
import android.app.DatePickerDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.content.ContentProviderOperation;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.ContactsContract;
import android.provider.MediaStore;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.exifinterface.media.ExifInterface;

import com.crowja.damiupos.db.CustomerDao;
import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.model.Customer;
import com.google.android.material.textfield.TextInputEditText;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

public class CustomerFormActivity extends AppCompatActivity {

    /** Caller bisa baca id pelanggan baru lewat data.getLongExtra(EXTRA_NEW_CUSTOMER_ID, -1) */
    public static final String EXTRA_NEW_CUSTOMER_ID = "new_customer_id";

    private static final int REQUEST_CAMERA = 100;
    private static final int REQUEST_PERMISSION_CAMERA = 101;
    private static final int REQUEST_PERMISSION_LOCATION = 102;
    private static final int REQUEST_PICK_MAP = 103;
    private static final int REQUEST_PERMISSION_CONTACTS = 104;

    /** Id pelanggan yang baru di-insert, dipakai untuk setResult */
    private long lastInsertedId = -1;

    private String pendingContactName;
    private String pendingContactPhone;

    private TextInputEditText etNama, etTelepon, etAlamat;
    private ImageView ivFotoRumah;
    private TextView tvKoordinat;
    private CustomerDao customerDao;
    private long editId = -1;

    private String currentPhotoPath;
    private double latitude = 0;
    private double longitude = 0;

    private com.google.android.material.checkbox.MaterialCheckBox cbReseller;
    /** resellerSince existing dari DB (kalau edit) — dipertahankan kalau tetap reseller. */
    private String existingResellerSince;

    private String selectedCreatedAt; // tanggal daftar terpilih (yyyy-MM-dd HH:mm:ss)
    private final SimpleDateFormat dbDateFmt =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
    private final SimpleDateFormat displayDateFmt =
            new SimpleDateFormat("d MMM yyyy", new Locale("id", "ID"));
    private com.google.android.material.button.MaterialButton btnTanggalDaftar, btnBukaMaps;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_customer_form);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        customerDao = new CustomerDao(DatabaseHelper.getInstance(this));

        etNama = findViewById(R.id.etNama);
        etTelepon = findViewById(R.id.etTelepon);
        etAlamat = findViewById(R.id.etAlamat);
        ivFotoRumah = findViewById(R.id.ivFotoRumah);
        tvKoordinat = findViewById(R.id.tvKoordinat);

        // Check if editing existing customer
        editId = getIntent().getLongExtra("customer_id", -1);
        if (editId != -1) {
            toolbar.setTitle(R.string.edit_pelanggan);
            Customer customer = customerDao.getById(editId);
            if (customer != null) {
                etNama.setText(customer.getName());
                etTelepon.setText(customer.getPhone());
                etAlamat.setText(customer.getAddress());
                if (customer.getCreatedAt() != null && !customer.getCreatedAt().isEmpty()) {
                    selectedCreatedAt = customer.getCreatedAt();
                }
                // Reseller state existing
                if (cbReseller == null) cbReseller = findViewById(R.id.cbReseller);
                cbReseller.setChecked(customer.isReseller());
                existingResellerSince = customer.getResellerSince();

                if (customer.getPhotoPath() != null && !customer.getPhotoPath().isEmpty()) {
                    currentPhotoPath = customer.getPhotoPath();
                    File photoFile = new File(currentPhotoPath);
                    if (photoFile.exists()) {
                        ivFotoRumah.setImageBitmap(loadRotatedBitmap(currentPhotoPath));
                    }
                }

                if (customer.getLatitude() != 0 || customer.getLongitude() != 0) {
                    latitude = customer.getLatitude();
                    longitude = customer.getLongitude();
                    tvKoordinat.setText(String.format(Locale.US, "%.6f, %.6f", latitude, longitude));
                }
            }
        }

        findViewById(R.id.btnFoto).setOnClickListener(v -> takePhoto());
        findViewById(R.id.btnLokasi).setOnClickListener(v -> getLocation());
        findViewById(R.id.btnPilihPeta).setOnClickListener(v -> pickFromMap());
        findViewById(R.id.btnSimpan).setOnClickListener(v -> save());

        // --- Tanggal Daftar (default hari ini) ---
        btnTanggalDaftar = findViewById(R.id.btnTanggalDaftar);
        if (selectedCreatedAt == null || selectedCreatedAt.isEmpty()) {
            selectedCreatedAt = dbDateFmt.format(new Date());
        }
        updateTanggalButton();
        btnTanggalDaftar.setOnClickListener(v -> showDatePicker());

        // --- Buka di Google Maps (intent) ---
        btnBukaMaps = findViewById(R.id.btnBukaMaps);
        btnBukaMaps.setOnClickListener(v -> openInGoogleMaps());

        if (cbReseller == null) cbReseller = findViewById(R.id.cbReseller);

        updateKoordinatDisplay();
    }

    private void takePhoto() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA}, REQUEST_PERMISSION_CAMERA);
            return;
        }

        Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        if (takePictureIntent.resolveActivity(getPackageManager()) != null) {
            File photoFile = null;
            try {
                photoFile = createImageFile();
            } catch (IOException e) {
                Toast.makeText(this, "Gagal membuat file foto", Toast.LENGTH_SHORT).show();
                return;
            }

            if (photoFile != null) {
                Uri photoURI = FileProvider.getUriForFile(this,
                        getApplicationContext().getPackageName() + ".fileprovider",
                        photoFile);
                takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, photoURI);
                startActivityForResult(takePictureIntent, REQUEST_CAMERA);
            }
        }
    }

    private File createImageFile() throws IOException {
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        String imageFileName = "RUMAH_" + timeStamp;
        File storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        File image = File.createTempFile(imageFileName, ".jpg", storageDir);
        currentPhotoPath = image.getAbsolutePath();
        return image;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CAMERA && resultCode == RESULT_OK) {
            if (currentPhotoPath != null) {
                ivFotoRumah.setImageBitmap(loadRotatedBitmap(currentPhotoPath));
            }
        } else if (requestCode == REQUEST_PICK_MAP && resultCode == RESULT_OK && data != null) {
            latitude = data.getDoubleExtra(MapPickerActivity.EXTRA_LATITUDE, 0);
            longitude = data.getDoubleExtra(MapPickerActivity.EXTRA_LONGITUDE, 0);
            updateKoordinatDisplay();
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
        } catch (IOException e) {
            // Ignore EXIF errors, return original bitmap
        }
        return bitmap;
    }

    private void updateKoordinatDisplay() {
        if (latitude != 0 || longitude != 0) {
            tvKoordinat.setText(String.format(Locale.US, "%.6f, %.6f", latitude, longitude));
            if (btnBukaMaps != null) btnBukaMaps.setVisibility(android.view.View.VISIBLE);
        } else {
            tvKoordinat.setText("Belum ada koordinat");
            if (btnBukaMaps != null) btnBukaMaps.setVisibility(android.view.View.GONE);
        }
    }

    private void updateTanggalButton() {
        if (btnTanggalDaftar == null) return;
        try {
            Date d = dbDateFmt.parse(selectedCreatedAt);
            btnTanggalDaftar.setText("Tanggal Daftar: "
                    + (d != null ? displayDateFmt.format(d) : "Hari ini"));
        } catch (Exception e) {
            btnTanggalDaftar.setText("Tanggal Daftar: Hari ini");
        }
    }

    private void showDatePicker() {
        Calendar cal = Calendar.getInstance();
        try {
            Date d = dbDateFmt.parse(selectedCreatedAt);
            if (d != null) cal.setTime(d);
        } catch (Exception ignored) {}
        new DatePickerDialog(this, (view, year, month, dayOfMonth) -> {
            Calendar picked = Calendar.getInstance();
            picked.set(Calendar.YEAR, year);
            picked.set(Calendar.MONTH, month);
            picked.set(Calendar.DAY_OF_MONTH, dayOfMonth);
            selectedCreatedAt = dbDateFmt.format(picked.getTime());
            updateTanggalButton();
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show();
    }

    /** Buka lokasi pelanggan di aplikasi Google Maps (intent). */
    private void openInGoogleMaps() {
        if (latitude == 0 && longitude == 0) {
            Toast.makeText(this, "Belum ada koordinat", Toast.LENGTH_SHORT).show();
            return;
        }
        String label = etNama.getText() != null ? etNama.getText().toString().trim() : "";
        String uri = "geo:" + latitude + "," + longitude + "?q=" + latitude + "," + longitude
                + (label.isEmpty() ? "" : "(" + Uri.encode(label) + ")");
        Intent gmaps = new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
        gmaps.setPackage("com.google.android.apps.maps");
        if (gmaps.resolveActivity(getPackageManager()) != null) {
            startActivity(gmaps);
        } else {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://www.google.com/maps/search/?api=1&query="
                            + latitude + "," + longitude)));
        }
    }

    private void pickFromMap() {
        Intent intent = new Intent(this, MapPickerActivity.class);
        if (latitude != 0 || longitude != 0) {
            intent.putExtra(MapPickerActivity.EXTRA_LATITUDE, latitude);
            intent.putExtra(MapPickerActivity.EXTRA_LONGITUDE, longitude);
        }
        startActivityForResult(intent, REQUEST_PICK_MAP);
    }

    private void getLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQUEST_PERMISSION_LOCATION);
            return;
        }

        LocationManager locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (locationManager == null) {
            Toast.makeText(this, "Location service tidak tersedia", Toast.LENGTH_SHORT).show();
            return;
        }

        // Try GPS first, fallback to network
        String provider = LocationManager.GPS_PROVIDER;
        if (!locationManager.isProviderEnabled(provider)) {
            provider = LocationManager.NETWORK_PROVIDER;
        }

        try {
            tvKoordinat.setText("Mencari lokasi...");
            locationManager.requestSingleUpdate(provider, new LocationListener() {
                @Override
                public void onLocationChanged(@NonNull Location location) {
                    latitude = location.getLatitude();
                    longitude = location.getLongitude();
                    tvKoordinat.setText(String.format(Locale.US, "%.6f, %.6f", latitude, longitude));
                }

                @Override public void onProviderEnabled(@NonNull String p) {}
                @Override public void onProviderDisabled(@NonNull String p) {}
            }, null);

            // Also try last known location as immediate fallback
            Location lastKnown = locationManager.getLastKnownLocation(provider);
            if (lastKnown != null && latitude == 0) {
                latitude = lastKnown.getLatitude();
                longitude = lastKnown.getLongitude();
                tvKoordinat.setText(String.format(Locale.US, "%.6f, %.6f", latitude, longitude));
            }
        } catch (SecurityException e) {
            Toast.makeText(this, "Permission lokasi ditolak", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            if (requestCode == REQUEST_PERMISSION_CAMERA) {
                takePhoto();
            } else if (requestCode == REQUEST_PERMISSION_LOCATION) {
                getLocation();
            } else if (requestCode == REQUEST_PERMISSION_CONTACTS) {
                if (pendingContactName != null && pendingContactPhone != null) {
                    writeContact(pendingContactName, pendingContactPhone);
                    pendingContactName = null;
                    pendingContactPhone = null;
                }
            }
        } else {
            if (requestCode == REQUEST_PERMISSION_CONTACTS) {
                Toast.makeText(this, "Izin kontak ditolak, tidak disimpan ke HP",
                        Toast.LENGTH_SHORT).show();
                finish();
            } else {
                Toast.makeText(this, "Permission ditolak", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void save() {
        String nama = etNama.getText() != null ? etNama.getText().toString().trim() : "";
        String telepon = etTelepon.getText() != null ? etTelepon.getText().toString().trim() : "";
        String alamat = etAlamat.getText() != null ? etAlamat.getText().toString().trim() : "";

        if (nama.isEmpty()) {
            etNama.setError("Nama wajib diisi");
            etNama.requestFocus();
            return;
        }

        Customer customer = new Customer(nama, telepon, alamat);
        customer.setPhotoPath(currentPhotoPath);
        customer.setLatitude(latitude);
        customer.setLongitude(longitude);
        customer.setCreatedAt(selectedCreatedAt);

        // Reseller: kalau dicentang & belum pernah jadi reseller → set since=now.
        // Kalau sudah pernah (edit), pertahankan tanggal lama supaya komisi
        // historis tidak berubah.
        boolean isReseller = cbReseller != null && cbReseller.isChecked();
        customer.setReseller(isReseller);
        if (isReseller) {
            customer.setResellerSince(
                    existingResellerSince != null && !existingResellerSince.isEmpty()
                            ? existingResellerSince
                            : dbDateFmt.format(new Date()));
        } else {
            customer.setResellerSince(existingResellerSince); // simpan utk histori
        }

        if (editId != -1) {
            customer.setId(editId);
            customerDao.update(customer);
            Toast.makeText(this, "Pelanggan berhasil diupdate", Toast.LENGTH_SHORT).show();
            finish();
        } else {
            lastInsertedId = customerDao.insert(customer);
            // Caller (mis. TransactionActivity) bisa langsung auto-pick
            // pelanggan yang baru dibuat tanpa harus search ulang.
            if (lastInsertedId > 0) {
                setResult(RESULT_OK, new Intent()
                        .putExtra(EXTRA_NEW_CUSTOMER_ID, lastInsertedId));
            }
            Toast.makeText(this, "Pelanggan berhasil ditambahkan", Toast.LENGTH_SHORT).show();
            // Auto-save to phone contacts (new customers only, with phone number)
            if (!telepon.isEmpty()) {
                saveToContacts(nama, telepon);
                // finish() handled inside contact-save flow
            } else {
                finish();
            }
        }
    }

    private void saveToContacts(String name, String phone) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_CONTACTS)
                != PackageManager.PERMISSION_GRANTED) {
            pendingContactName = name;
            pendingContactPhone = phone;
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.WRITE_CONTACTS},
                    REQUEST_PERMISSION_CONTACTS);
            return;
        }
        writeContact(name, phone);
    }

    private void writeContact(String name, String phone) {
        try {
            ArrayList<ContentProviderOperation> ops = new ArrayList<>();
            ops.add(ContentProviderOperation
                    .newInsert(ContactsContract.RawContacts.CONTENT_URI)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                    .build());
            ops.add(ContentProviderOperation
                    .newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(ContactsContract.Data.MIMETYPE,
                            ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
                    .build());
            ops.add(ContentProviderOperation
                    .newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(ContactsContract.Data.MIMETYPE,
                            ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phone)
                    .withValue(ContactsContract.CommonDataKinds.Phone.TYPE,
                            ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                    .build());
            getContentResolver().applyBatch(ContactsContract.AUTHORITY, ops);
            Toast.makeText(this, "Disimpan ke kontak HP", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Gagal simpan ke kontak: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
        finish();
    }
}
