# Tambahkan Iklan (AdMob) dan Versi Berbayar untuk Google Play Store

## Ringkasan
Persiapkan DAMIU POS untuk dirilis ke Google Play Store dengan dua model monetisasi:
1. **Versi Gratis** — didanai oleh iklan (Google AdMob).
2. **Versi Berbayar / In-App Purchase** — menghilangkan iklan dan membuka fitur tambahan.

## Latar Belakang
Saat ini aplikasi hanya didistribusikan sebagai APK debug untuk penggunaan internal. Untuk menjangkau pasar yang lebih luas (pemilik depot air minum di seluruh Indonesia), aplikasi perlu dipublikasikan ke Google Play Store dengan strategi monetisasi yang jelas.

## Scope

### A. Integrasi Google AdMob (Versi Gratis)
- [ ] Tambahkan dependency `play-services-ads` di `app/build.gradle`
- [ ] Daftarkan aplikasi di [AdMob Console](https://admob.google.com) dan dapatkan App ID + Ad Unit IDs
- [ ] Konfigurasi `AndroidManifest.xml`:
  - Tambahkan `<meta-data>` untuk `com.google.android.gms.ads.APPLICATION_ID`
  - Permission `INTERNET` sudah ada
- [ ] Inisialisasi `MobileAds.initialize()` di `Application` class
- [ ] Format iklan yang direkomendasikan:
  - **Banner** di bawah Dashboard (MainActivity) — non-intrusive
  - **Interstitial** saat menutup struk (ReceiptActivity) — 1x setiap 3–5 transaksi untuk menjaga UX
  - **App Open** sesekali saat app di-resume (opsional, perlu evaluasi UX)
- [ ] Hormati kebijakan AdMob: tidak ada iklan di dialog, tidak menutup tombol aksi utama
- [ ] Taruh Ad Unit IDs di `BuildConfig` lewat `gradle.properties` agar tidak ter-commit

### B. In-App Purchase / Versi Berbayar (Remove Ads + Pro)
- [ ] Integrasi **Google Play Billing Library** (`com.android.billingclient:billing`)
- [ ] Produk yang dijual:
  - `damiu_pro_lifetime` — satu kali bayar, seumur hidup (Rp 99.000 – 149.000, TBD)
  - Opsional: `damiu_pro_monthly` subscription (Rp 15.000/bulan)
- [ ] Fitur yang di-unlock di versi Pro:
  - Hilangkan semua iklan
  - (Opsional) Backup otomatis ke Google Drive
  - (Opsional) Export laporan ke PDF
  - (Opsional) Multi-device sync
  - Batas pelanggan & transaksi tidak terbatas (jika versi gratis di-cap)
- [ ] Simpan status Pro di `SettingsDao` dengan verifikasi purchase token
- [ ] Halaman "Upgrade ke Pro" dengan daftar benefit + tombol beli
- [ ] Handle refund / cancellation via `BillingClient.queryPurchasesAsync` saat app start

### C. Persiapan Rilis Play Store
- [ ] Generate **keystore release** dan simpan aman (JANGAN commit)
- [ ] Konfigurasi signing config di `app/build.gradle` lewat `gradle.properties` lokal
- [ ] Ubah `applicationId` jika perlu (saat ini `com.damiu.pos`)
- [ ] Versi `versionCode` / `versionName` strategi (semver)
- [ ] Siapkan asset Play Store:
  - Feature graphic 1024×500
  - Icon 512×512
  - Screenshot minimal 2 (HP + tablet)
  - Short & full description (ID + EN)
  - Privacy Policy URL (wajib untuk aplikasi dengan iklan & IAP)
- [ ] Isi Data Safety form (kami menyimpan data pelanggan lokal, READ_CONTACTS, LOCATION)
- [ ] Target API ≥ 34 (sudah terpenuhi)
- [ ] Build AAB (`./gradlew bundleRelease`) bukan APK untuk Play Store

### D. Privacy & Compliance
- [ ] Tulis **Privacy Policy** (hosting via GitHub Pages) yang mencakup:
  - Data yang dikumpulkan AdMob (Advertising ID)
  - Data kontak yang diimpor (tetap di device)
  - Lokasi GPS (tetap di device)
- [ ] Tambahkan dialog consent GDPR/UMP untuk pengguna EEA (wajib AdMob)
- [ ] Tambahkan opsi "Hapus semua data" di Pengaturan (wajib Play Store)

## Acceptance Criteria
- [ ] Build debug tetap berjalan tanpa iklan (gunakan flavor `debug` vs `release` atau test ad unit IDs)
- [ ] Versi release menampilkan iklan dengan benar
- [ ] User bisa membeli Pro dan iklan langsung hilang setelah pembayaran berhasil
- [ ] Restore purchase berfungsi di device baru
- [ ] Lolos review Google Play Store

## Catatan Teknis
- Branch kerja: `feat/ads-and-playstore-purchase`
- Library yang akan ditambahkan kemungkinan akan menambah ukuran APK ±2–3 MB — perlu dievaluasi
- Pertimbangkan split AAB per ABI untuk mengurangi ukuran download
