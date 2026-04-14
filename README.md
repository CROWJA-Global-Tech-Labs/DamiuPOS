# DAMIU POS - Aplikasi Point of Sale Depot Air Minum

Aplikasi POS (Point of Sale) berbasis Android yang dirancang khusus untuk usaha **Depot Air Minum Isi Ulang (DAMIU)**. Aplikasi ini membantu pemilik depot mengelola penjualan, memantau galon yang dipinjamkan ke pelanggan, melacak stok galon, serta memberikan peringatan otomatis untuk pelanggan yang sudah lama tidak melakukan pembelian.

> © **Depot Air Minum FREZ** — Aplikasi ini dikembangkan khusus untuk operasional Depot Air Minum FREZ.

## Fitur Utama

### Wizard Setup Awal
Saat pertama kali dibuka, aplikasi akan memandu pengguna melalui 6 langkah setup:
1. **Selamat Datang**
2. **Info Depot** — nama, alamat, dan no. HP (akan dicetak di struk)
3. **Harga & Ongkir** — default ongkir per galon + harga botol galon kosong (untuk ganti rugi / pembelian botol)
4. **Sistem Poin** — aktif/non-aktif dan konfigurasi poin loyalitas
5. **Peringatan Otomatis** — ambang batas Follow Up pelanggan (hari) & peringatan stok galon
6. **Impor Pelanggan** — opsional, impor dari kontak telepon

### Dashboard
- Ringkasan pendapatan, galon terjual, dan jumlah transaksi hari ini
- Total galon yang masih beredar di pelanggan + total pelanggan terdaftar
- Daftar transaksi terakhir
- **Tombol Jual Air Minum & Galon Kembali** sebagai aksi utama
- Menu grid berwarna: Pelanggan, Follow Up, Stok Galon, Produk, Laporan, Export, Pengaturan
- **Badge angka di pojok tombol Stok Galon & Follow Up** — menampilkan jumlah stok tersedia / kandidat follow up secara real-time
- **Indikator berkedip** pada menu Follow Up / Stok Galon saat ambang batas terlewati
- **Brand header** dengan ikon tetes air dan subtitle "Point-of-Sales Khusus Depot Air Minum"

### Manajemen Pelanggan
- CRUD lengkap + pencarian (nama / telepon / alamat)
- **Urutkan** berdasarkan nama atau galon terbanyak
- **Foto rumah** dari kamera (dapat di-tap untuk tampilan fullscreen)
- **Peta Leaflet/OpenStreetMap** di halaman detail + tombol Navigasi (membuka Google Maps)
- **Map picker** dengan GPS & pencarian alamat saat menambah pelanggan
- **Chat WhatsApp** otomatis dengan format +62
- **Impor dari kontak** HP (deduplikasi berdasarkan nomor telepon)

### Transaksi
- **Jual Air Minum** (botol galon keluar) dengan kalkulasi total harga + ongkir otomatis
- **Botol Galon Kembali** (botol galon masuk) — pengurangan saldo galon di pelanggan + opsi **ganti rugi** untuk botol rusak
- Pilih produk, jumlah botol galon, harga/galon, ongkir (per botol / flat / total)
- **Opsi kepemilikan botol galon: Pinjam / Beli**
  - **Pinjam** (default): botol tetap tercatat di akuisisi pelanggan
  - **Beli**: tampil input harga botol; biaya beli ditambahkan ke total dan **tidak** menambah saldo galon di pelanggan
  - Pilihan terakhir disimpan otomatis untuk transaksi berikutnya
- Histori lengkap per pelanggan — **klik item untuk menampilkan struk** yang bisa di-share
- **Daftar Transaksi**: ringkasan total mencakup penjualan + nilai ganti rugi (KEMBALI bernilai)
- Catatan opsional per transaksi

### Struk Penjualan
- Struk berisi info depot (nama/alamat/no. HP dari pengaturan)
- Detail pelanggan, item, total, ongkir, poin diperoleh
- **Share via WhatsApp / Email / sebagai foto PNG**

### Stok Galon
- Pantau stok galon kosong & isi di depot
- Histori penambahan / pengurangan stok
- **Peringatan berkedip** di dashboard jika stok ≤ ambang batas

### Follow Up Pelanggan
- Daftar pelanggan yang belum bertransaksi dalam N hari terakhir (N dari Pengaturan)
- **Menu berkedip** di dashboard saat ada kandidat
- Tombol WhatsApp cepat per pelanggan

### Sistem Poin Loyalitas
- Opsional — diatur dari Wizard / Pengaturan
- Konfigurasi: nilai transaksi per 1 poin + ambang poin untuk hadiah
- Otomatis terakumulasi pada setiap transaksi JUAL
- **Biaya pembelian botol galon dan ganti rugi tidak dihitung sebagai basis poin** — hanya nilai air murni yang berkontribusi pada loyalitas

### Pengaturan Tambahan
- **Harga Botol Galon Kosong** (default Rp 35.000) — dipakai sebagai harga ganti rugi botol rusak dan saat pelanggan membeli botol baru

### Laporan & Grafik
- Grafik penjualan 6 bulan terakhir & harian bulan berjalan
- Top 10 pelanggan + ranking frekuensi pembelian
- Semua grafik dibangun dengan custom `SimpleBarChart` (tanpa library eksternal)

### Export Data
- Periode: Hari Ini, Pekan Ini, Bulan Ini, atau Custom Range
- Preview ringkasan sebelum export
- **CSV UTF-8 (BOM)** kompatibel Excel — bisa langsung dibagikan

## Tech Stack

| Komponen | Teknologi |
|----------|-----------|
| Bahasa | Java |
| Database | SQLite (lokal, tanpa cloud) |
| UI | Material Design 3 Components |
| Peta | Leaflet.js + OpenStreetMap (via WebView) |
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
androidx.gridlayout:gridlayout:1.0.0
androidx.exifinterface:exifinterface:1.3.7
androidx.webkit:webkit:1.8.0
```

## Permissions

| Permission | Kegunaan |
|------------|----------|
| `CAMERA` | Ambil foto rumah pelanggan |
| `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` | Koordinat GPS pelanggan |
| `READ_CONTACTS` / `WRITE_CONTACTS` | Impor daftar kontak sebagai pelanggan |
| `INTERNET` | Memuat tile peta (Leaflet/OpenStreetMap) |

Semua permission bersifat **runtime** — diminta saat dibutuhkan dan aplikasi tetap berfungsi jika ditolak.

## Cara Build & Install

### Prasyarat
- [Android Studio](https://developer.android.com/studio) (versi terbaru)
- JDK 17 (disarankan)
- Android SDK API 34

### Build APK (Debug — untuk tester)
```bash
./gradlew assembleDebug
```
APK akan tersedia di `app/build/outputs/apk/debug/app-debug.apk`.

### Build APK (Release)
```bash
./gradlew assembleRelease
```
APK akan tersedia di `app/build/outputs/apk/release/`.

## Lisensi

Hak cipta dilindungi. Aplikasi ini dikembangkan untuk penggunaan internal Depot Air Minum FREZ.

---

© **Depot Air Minum FREZ**
