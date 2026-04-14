package com.damiu.pos;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.BitmapFactory;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
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

import com.damiu.pos.db.CustomerDao;
import com.damiu.pos.db.DatabaseHelper;
import com.damiu.pos.model.Customer;
import com.google.android.material.textfield.TextInputEditText;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class CustomerFormActivity extends AppCompatActivity {

    private static final int REQUEST_CAMERA = 100;
    private static final int REQUEST_PERMISSION_CAMERA = 101;
    private static final int REQUEST_PERMISSION_LOCATION = 102;
    private static final int REQUEST_PICK_MAP = 103;

    private TextInputEditText etNama, etTelepon, etAlamat;
    private ImageView ivFotoRumah;
    private TextView tvKoordinat;
    private CustomerDao customerDao;
    private long editId = -1;

    private String currentPhotoPath;
    private double latitude = 0;
    private double longitude = 0;

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

                if (customer.getPhotoPath() != null && !customer.getPhotoPath().isEmpty()) {
                    currentPhotoPath = customer.getPhotoPath();
                    File photoFile = new File(currentPhotoPath);
                    if (photoFile.exists()) {
                        ivFotoRumah.setImageBitmap(BitmapFactory.decodeFile(currentPhotoPath));
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
                ivFotoRumah.setImageBitmap(BitmapFactory.decodeFile(currentPhotoPath));
            }
        }
    }

    private void pickFromMap() {
        // Open Google Maps for location picking
        // If we already have coordinates, center on them; otherwise use a default location
        Uri mapUri;
        if (latitude != 0 || longitude != 0) {
            mapUri = Uri.parse("geo:" + latitude + "," + longitude + "?q=" + latitude + "," + longitude);
        } else {
            // Default: Indonesia center
            mapUri = Uri.parse("geo:-6.2,106.8?q=-6.2,106.8");
        }

        Intent mapIntent = new Intent(Intent.ACTION_VIEW, mapUri);
        mapIntent.setPackage("com.google.android.apps.maps");

        if (mapIntent.resolveActivity(getPackageManager()) != null) {
            // Google Maps can't return a picked location via ACTION_VIEW directly,
            // so we use a place picker approach with ACTION_PICK
            try {
                Intent pickIntent = new Intent(Intent.ACTION_PICK);
                pickIntent.setType("vnd.android.cursor.item/contact");
                // Fallback: just open Maps and let user copy coords, but instead
                // we'll use a simple dialog approach
                showMapPickerDialog();
            } catch (Exception e) {
                showMapPickerDialog();
            }
        } else {
            showMapPickerDialog();
        }
    }

    private void showMapPickerDialog() {
        // Show a dialog where user can either enter coordinates manually
        // or open Google Maps to view/get coordinates
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this);
        builder.setTitle("Pilih Koordinat");

        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, pad);

        TextInputEditText etLat = new TextInputEditText(this);
        etLat.setHint("Latitude (misal: -6.200000)");
        etLat.setInputType(android.text.InputType.TYPE_CLASS_NUMBER |
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL |
                android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);
        if (latitude != 0) etLat.setText(String.valueOf(latitude));
        layout.addView(etLat);

        TextInputEditText etLng = new TextInputEditText(this);
        etLng.setHint("Longitude (misal: 106.800000)");
        etLng.setInputType(android.text.InputType.TYPE_CLASS_NUMBER |
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL |
                android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);
        if (longitude != 0) etLng.setText(String.valueOf(longitude));

        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (8 * getResources().getDisplayMetrics().density);
        etLng.setLayoutParams(lp);
        layout.addView(etLng);

        // Button to open Google Maps
        com.google.android.material.button.MaterialButton btnOpenMap =
                new com.google.android.material.button.MaterialButton(this,
                        null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        btnOpenMap.setText("Buka Google Maps");
        btnOpenMap.setAllCaps(false);
        android.widget.LinearLayout.LayoutParams mapBtnLp = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        mapBtnLp.topMargin = (int) (12 * getResources().getDisplayMetrics().density);
        btnOpenMap.setLayoutParams(mapBtnLp);
        layout.addView(btnOpenMap);

        builder.setView(layout);

        builder.setPositiveButton("Simpan", (dialog, which) -> {
            try {
                String latStr = etLat.getText() != null ? etLat.getText().toString().trim() : "";
                String lngStr = etLng.getText() != null ? etLng.getText().toString().trim() : "";
                if (!latStr.isEmpty() && !lngStr.isEmpty()) {
                    latitude = Double.parseDouble(latStr);
                    longitude = Double.parseDouble(lngStr);
                    tvKoordinat.setText(String.format(Locale.US, "%.6f, %.6f", latitude, longitude));
                }
            } catch (NumberFormatException e) {
                Toast.makeText(this, "Format koordinat tidak valid", Toast.LENGTH_SHORT).show();
            }
        });
        builder.setNegativeButton("Batal", null);

        android.app.AlertDialog dlg = builder.create();

        btnOpenMap.setOnClickListener(v -> {
            double lat = latitude != 0 ? latitude : -6.2;
            double lng = longitude != 0 ? longitude : 106.8;
            Uri gmmUri = Uri.parse("geo:" + lat + "," + lng + "?q=" + lat + "," + lng);
            Intent mapIntent = new Intent(Intent.ACTION_VIEW, gmmUri);
            mapIntent.setPackage("com.google.android.apps.maps");
            try {
                startActivity(mapIntent);
            } catch (Exception e) {
                // Fallback to browser
                Intent browserIntent = new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://maps.google.com/?q=" + lat + "," + lng));
                startActivity(browserIntent);
            }
        });

        dlg.show();
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
            }
        } else {
            Toast.makeText(this, "Permission ditolak", Toast.LENGTH_SHORT).show();
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

        if (editId != -1) {
            customer.setId(editId);
            customerDao.update(customer);
            Toast.makeText(this, "Pelanggan berhasil diupdate", Toast.LENGTH_SHORT).show();
        } else {
            customerDao.insert(customer);
            Toast.makeText(this, "Pelanggan berhasil ditambahkan", Toast.LENGTH_SHORT).show();
        }

        finish();
    }
}
