# DAMIU POS - Aplikasi Point of Sale Depot Air Minum Isi Ulang

Aplikasi POS (Point of Sale) berbasis Android yang dirancang khusus untuk usaha **Depot Air Minum Isi Ulang (DAMIU)**. Aplikasi ini membantu pemilik depot mengelola penjualan, memantau galon yang dipinjamkan ke pelanggan, serta melacak seluruh histori transaksi secara detail.

## Fitur Utama

### Dashboard
- Ringkasan pendapatan hari ini
- Jumlah galon terjual hari ini
- Total transaksi hari ini
- Total galon yang masih beredar di pelanggan
- Total pelanggan terdaftar
- Daftar 10 transaksi terakhir
- Akses cepat ke semua menu utama

### Manajemen Pelanggan
- **CRUD lengkap** — Tambah, lihat, edit, dan hapus data pelanggan
- **Pencarian** — Cari pelanggan berdasarkan nama, telepon, atau alamat
- **Foto rumah** — Ambil foto lokasi rumah pelanggan langsung dari kamera
- **Koordinat GPS** — Simpan titik lokasi pelanggan secara otomatis
- **Saldo galon** — Tampilkan jumlah galon yang masih berada di pelanggan
- **Chat WhatsApp** — Buka WhatsApp langsung ke nomor pelanggan (otomatis format ke +62)

### Transaksi
- **Jual Galon** — Catat penjualan galon ke pelanggan (galon keluar)
- **Galon Kembali** — Catat pengembalian galon dari pelanggan (galon masuk)
- Pilih pelanggan dari daftar dengan fitur pencarian
- Input jumlah galon dan harga per galon
- Kalkulasi total harga otomatis
- Kolom catatan opsional untuk setiap transaksi

### Tracking Galon
- Saldo galon per pelanggan = total galon keluar − total galon kembali
- Total galon beredar di semua pelanggan ditampilkan di dashboard
- Histori lengkap keluar-masuk galon di halaman detail pelanggan

### Laporan & Grafik
- **Grafik penjualan bulanan** — Tren galon terjual 6 bulan terakhir
- **Grafik penjualan harian** — Detail penjualan per hari di bulan berjalan
- **Top 10 pelanggan** — Pelanggan dengan pembelian galon terbanyak
- **Frekuensi pembelian** — Ranking pelanggan berdasarkan jumlah transaksi
- Semua grafik menggunakan custom `SimpleBarChart` (tanpa library eksternal)

### Export Penjualan
- Pilihan periode:
  - **Hari Ini**
  - **Pekan Ini**
  - **Bulan Ini**
  - **Custom Range** — Pilih tanggal awal & akhir via DatePicker
- Preview ringkasan sebelum export (total transaksi, galon, pendapatan)
- Export ke file **CSV** (kompatibel Excel dengan BOM UTF-8)
- **Bagikan** langsung via WhatsApp, Email, Google Drive, dll

## Tech Stack

| Komponen | Teknologi |
|----------|-----------|
| Bahasa | Java |
| Database | SQLite (lokal) |
| UI | Material Design (Material Components) |
| Min SDK | Android 7.0 (API 24) |
| Target SDK | Android 14 (API 34) |
| Build System | Gradle 8.13 + AGP 8.13.2 |
| Arsitektur | Activity + DAO Pattern |

### Dependencies
```groovy
androidx.appcompat:appcompat:1.6.1
com.google.android.material:material:1.11.0
androidx.constraintlayout:constraintlayout:2.1.4
androidx.recyclerview:recyclerview:1.3.2
androidx.cardview:cardview:1.0.0
```
> Tidak ada library pihak ketiga tambahan — semua fitur (termasuk grafik) dibangun dari komponen Android native.

## Struktur Project

```
app/src/main/
├── AndroidManifest.xml
├── java/com/damiu/pos/
│   ├── MainActivity.java              # Dashboard utama
│   ├── CustomerListActivity.java      # Daftar pelanggan + pencarian
│   ├── CustomerFormActivity.java      # Tambah/edit pelanggan + foto + GPS
│   ├── CustomerDetailActivity.java    # Detail pelanggan + histori + WhatsApp
│   ├── TransactionActivity.java       # Form transaksi jual/kembali
│   ├── ReportActivity.java            # Laporan & grafik penjualan
│   ├── ExportActivity.java            # Export CSV dengan pilihan periode
│   ├── adapter/
│   │   ├── CustomerAdapter.java       # RecyclerView adapter daftar pelanggan
│   │   ├── TransactionAdapter.java    # RecyclerView adapter daftar transaksi
│   │   └── FrekuensiAdapter.java      # Adapter ranking frekuensi pembelian
│   ├── db/
│   │   ├── DatabaseHelper.java        # SQLite schema & singleton
│   │   ├── CustomerDao.java           # CRUD + query pelanggan
│   │   └── TransactionDao.java        # CRUD + statistik + query transaksi
│   ├── model/
│   │   ├── Customer.java              # Model pelanggan
│   │   └── Transaction.java           # Model transaksi
│   └── view/
│       └── SimpleBarChart.java        # Custom bar chart view
└── res/
    ├── drawable/                       # Shape drawables (circle, badges)
    ├── layout/                         # 11 layout XML files
    ├── values/                         # Colors, strings, themes
    └── xml/                            # FileProvider paths
```

## Database Schema

### Tabel `customers`
| Kolom | Tipe | Keterangan |
|-------|------|------------|
| `_id` | INTEGER | Primary key, auto increment |
| `name` | TEXT | Nama pelanggan (wajib) |
| `phone` | TEXT | Nomor telepon |
| `address` | TEXT | Alamat |
| `photo_path` | TEXT | Path foto rumah pelanggan |
| `latitude` | REAL | Koordinat latitude GPS |
| `longitude` | REAL | Koordinat longitude GPS |
| `created_at` | TEXT | Tanggal dibuat (otomatis) |

### Tabel `transactions`
| Kolom | Tipe | Keterangan |
|-------|------|------------|
| `_id` | INTEGER | Primary key, auto increment |
| `customer_id` | INTEGER | Foreign key ke `customers._id` (CASCADE delete) |
| `type` | TEXT | `JUAL` (galon keluar) atau `KEMBALI` (galon masuk) |
| `jumlah_galon` | INTEGER | Jumlah galon dalam transaksi |
| `harga_per_galon` | REAL | Harga per galon (untuk tipe JUAL) |
| `total_harga` | REAL | Total harga transaksi |
| `tanggal` | TEXT | Tanggal & waktu transaksi (otomatis) |
| `catatan` | TEXT | Catatan tambahan (opsional) |

## Permissions

| Permission | Kegunaan |
|------------|----------|
| `CAMERA` | Mengambil foto rumah pelanggan |
| `ACCESS_FINE_LOCATION` | Mendapatkan koordinat GPS lokasi pelanggan |
| `ACCESS_COARSE_LOCATION` | Fallback lokasi via network provider |

Semua permission bersifat **runtime** — diminta saat dibutuhkan dan aplikasi tetap berfungsi jika ditolak.

## Cara Build & Install

### Prasyarat
- [Android Studio](https://developer.android.com/studio) (versi terbaru)
- JDK 8 atau lebih baru
- Android SDK API 34

### Langkah
1. Clone repository:
   ```bash
   git clone https://github.com/Hantechno-Indonesia/DamiuPOS.git
   ```
2. Buka project di Android Studio
3. Tunggu Gradle sync selesai
4. Hubungkan perangkat Android atau jalankan emulator
5. Klik **Run** atau tekan `Shift + F10`

### Build APK
```bash
./gradlew assembleRelease
```
APK akan tersedia di `app/build/outputs/apk/release/`.

## Alur Penggunaan

```
┌─────────────────────────────────────────────────────┐
│                    DASHBOARD                         │
│  Pendapatan | Galon Terjual | Transaksi | Beredar   │
├──────────┬──────────┬───────────┬───────────────────┤
│ Jual     │ Galon    │ Daftar    │ Laporan  │ Export │
│ Galon    │ Kembali  │ Pelanggan │ & Grafik │ CSV    │
└────┬─────┴────┬─────┴─────┬─────┴────┬─────┴───┬───┘
     │          │           │          │         │
     ▼          ▼           ▼          ▼         ▼
  Pilih      Pilih      CRUD      Grafik     Pilih
  Pelanggan  Pelanggan  Pelanggan  Bulanan   Periode
  → Jumlah   → Jumlah   + Foto    Harian    → Preview
  → Harga    → Simpan   + GPS     Top 10    → CSV
  → Simpan              + WA      Frekuensi → Share
```

## Lisensi

Hak cipta dilindungi. Aplikasi ini dikembangkan untuk penggunaan internal Depot Air Minum.

---

Dikembangkan oleh [Hantechno Indonesia](https://github.com/Hantechno-Indonesia)
