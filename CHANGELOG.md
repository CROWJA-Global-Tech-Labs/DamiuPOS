# DAMIU POS — Changelog & Fitur

Daftar fitur yang sudah diimplementasikan, dari yang terbaru.
Brand UI: _by FREZ Tech & Innovation Labs_. Paket/identifier tetap `com.crowja.damiupos`.

> Versi in-app dari dokumen ini tersedia di **Tentang → Changelog & Fitur**
> (sumber: `app/src/main/res/raw/changelog.md`).

## Versi 1.2.10 — Transaksi Pending & Penyempurnaan

### Sinkronisasi Cloud diperluas
- Sinkron online kini mencakup **pesanan WA (order_inbox)**, **konfigurasi gaji per staf (salary_configs)**, dan **pengaturan depot (app_settings)** — otomatis menyebar ke semua perangkat cabang.
- Data sensitif (token, sandi SMTP/Claude, sesi login) **tidak pernah** disinkronkan — hanya konfigurasi bisnis yang aman (allowlist). salary_config memakai `sync_uuid` = uuid staf (1:1, tanpa duplikat lintas perangkat).

### Akun Marketing & Promosi (BARU)
- **Peran "Marketing"**: jenis akun baru untuk staf pemasaran (login PIN, tanpa absensi).
- **Promosi Galon Gratis**: akun Marketing (dan Admin) bisa memberi galon GRATIS ke pelanggan baru lewat alur mirip Transaksi Baru — pilih/tambah pelanggan, tentukan jumlah galon, simpan. Tercatat sebagai transaksi Rp 0 berpenanda `[PROMOSI]`.
- Akun Marketing fokus ke Promosi: tombol **🎁 Promosi Galon Gratis** di dashboard; tidak bisa membuat transaksi penjualan biasa.

### Transaksi Pending (BARU)
- **Catat transaksi pending**: di layar Jual Air Minum ada tombol **"Simpan sebagai Pending"** — dipakai saat pesanan belum bisa dieksekusi (mis. staf hendak pulang). Menyimpan pelanggan + ringkasan pesanan + catatan.
- **Pill berkedip**: tombol **"Jual Air Minum"** menampilkan badge merah berkedip berisi jumlah transaksi pending.
- **Eksekusi cepat**: ketuk badge → daftar **Transaksi Pending** → ketuk satu item → layar Transaksi Baru terbuka terisi otomatis. Setelah transaksi disimpan, pending terhapus sendiri.
- **Notifikasi saat masuk kerja**: ketika staf clock in kembali, muncul notifikasi Android + popup berisi jumlah transaksi pending.
- **Pengingat saat Pulang**: popup tanda seru saat menekan Pulang — mengingatkan membuat transaksi pending untuk rencana besok agar tidak lupa.
- **Akses**: lewat badge berkedip di tombol Jual Air Minum, indikator **PENDING (N)** di pojok kanan atas layar Transaksi Baru, atau popup saat clock in.
- **Laporan shift**: rincian transaksi pending kini ikut tercetak di laporan shift yang dikirim ke admin.
- Teknis: tabel DB baru `pending_transactions` (skema v25).

### Peta & Lokasi
- **Tombol "Chat Follow-up"** pada tiap pin pelanggan di Peta Follow Up — langsung membuka WhatsApp dengan pesan follow-up siap kirim.
- **Perbaikan peta**: basemap pindah ke CARTO Voyager + User-Agent pengenal aplikasi, sehingga peta tidak lagi terblokir ("tile usage policy") dan tile selalu tampil (Detail Pelanggan, Peta Follow Up, pemilih lokasi, stempel selfie absensi).

### Slip Gaji & Absensi
- **Lembur** dihitung dan dicetak di slip gaji (hanya bila staf benar-benar lembur).
- **Satuan "Rp"** ditampilkan di depan nominal pada slip gaji XLSX.
- **Jam kerja per periode cut-off**: pemenuhan jam kerja & notifikasi dihitung berbasis periode cut-off (bukan harian), lengkap dengan popup apresiasi di aplikasi.
- Pengaturan **"Hari kerja / pekan"** (1–7) menggantikan "hari ideal per bulan"; jam ideal periode otomatis menyesuaikan panjang periode.

### Dashboard
- Judul **"Pendapatan Hari Ini"** & **"Galon Terjual Hari Ini"** dipindah ke bawah kartu (rata bawah) agar tidak terpotong.
- Label **TRX / GLN / CUST** kini di samping ikon; tombol **"Report & Grafik"** dibuat dua baris.
- Harga **rata-rata per galon** dihitung dari berbagai jenis air minum saja (tanpa ongkir/botol).

## Versi 1.2.x — Multi-user, Absensi & Laporan

### Multi-user & Absensi
- Login multi-user dengan **PIN**; peran **Admin / Staf / Viewer** dengan pembatasan akses.
- **Clock in / Istirahat / Pulang** dengan **selfie wajah** otomatis saat clock in & pulang.
- **Peringatan lokasi depot** sebelum selfie clock in/out bila berada jauh dari depot.
- **Pengingat jam kerja**: notifikasi bersuara saat target jam kerja terpenuhi.
- Pengaturan **Karyawan** untuk admin (kelola pegawai, riwayat & rekap absensi).

### Laporan & Rekap
- **Laporan shift** otomatis terkirim ke email admin saat Pulang (teks + foto clock in & pulang).
- Rincian laporan shift: air minum per jenis, metode pembayaran, pengeluaran, follow-up.
- **Rekap absensi** bulanan (cut-off) dalam ZIP (XLSX + foto) + **rekap pekanan PDF**.
- **Slip gaji** per periode cut-off (XLSX dalam ZIP ber-password PIN admin + PDF).
- Antrian kirim + **retry** otomatis bila gagal terkirim di lapangan.

### Transaksi
- **Metode pembayaran**: Tunai / QRIS / Transfer.
- Layar **Transaksi Baru** dirapikan: semua jenis air sebagai baris entri, kartu reseller, tanggal transaksi.
- **Komisi reseller**: opsi "Tambahkan Komisi ke Harga Air Minum"; pencairan komisi tercatat sebagai pengeluaran.

### Pelanggan & Follow Up
- Daftar **Follow Up** pelanggan + **Peta** semua pelanggan + posisi live (ikon pengendara).
- Aksi cepat kartu, swipe untuk mengeluarkan dari follow up, urutan & badge reseller.
- Template pesan follow-up yang bisa diatur.

## Fitur Inti
- Manajemen pelanggan, produk air minum, stok galon, dan pengeluaran.
- Pesanan masuk dari **WhatsApp** (inbox + konversi ke transaksi).
- Struk transaksi + berbagi via WhatsApp.
- Analisa & prediksi penjualan (PDF), ekspor data, dan backup/restore database.
