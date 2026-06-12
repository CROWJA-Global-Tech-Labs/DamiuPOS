# Tambahkan Iklan (AdMob) dan Versi Berbayar Berlangganan Bulanan untuk Google Play Store

## Ringkasan
Persiapkan DAMIU POS untuk dirilis ke Google Play Store dengan dua model monetisasi:
1. **Versi Gratis** — didanai oleh iklan (Google AdMob).
2. **Versi Pro — Berlangganan Bulanan** — subscription recurring yang menghilangkan iklan dan membuka fitur tambahan. Model ini dipilih untuk menghasilkan recurring revenue yang lebih stabil dibanding one-time purchase.

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

### B. Subscription Bulanan (Versi Pro)
- [ ] Integrasi **Google Play Billing Library** (`com.android.billingclient:billing`) dengan dukungan subscription (`ProductType.SUBS`)
- [ ] Produk subscription yang dijual:
  - `damiu_pro_monthly` — **Rp 25.000 / bulan** (TBD, disesuaikan market)
  - Opsional `damiu_pro_yearly` — Rp 250.000 / tahun (diskon 2 bulan) sebagai opsi hemat
  - **Free trial 7 hari** untuk first-time subscriber (via Play Console base plan)
- [ ] Setup base plan + offer di Google Play Console (monthly base plan, yearly base plan, free trial offer)
- [ ] Fitur yang di-unlock selama subscription aktif:
  - Hilangkan semua iklan
  - Backup otomatis ke Google Drive
  - Export laporan ke PDF
  - Multi-device sync (opsional, roadmap lanjutan)
  - Batas pelanggan & transaksi tidak terbatas (jika versi gratis di-cap)
- [ ] Lifecycle subscription:
  - Query `queryPurchasesAsync(SUBS)` saat app start untuk verifikasi status aktif
  - Handle state: `SUBSCRIBED`, `IN_GRACE_PERIOD`, `ON_HOLD`, `PAUSED`, `EXPIRED`, `CANCELED`
  - Tampilkan banner reminder saat masuk grace period / on hold
  - Saat EXPIRED → otomatis revert ke mode gratis + tampilkan iklan lagi
- [ ] Acknowledge purchase dalam 3 hari (wajib Google, jika tidak purchase akan di-refund otomatis)
- [ ] Simpan status subscription di `SettingsDao` (cache) + selalu re-verify saat online
- [ ] Halaman **"Upgrade ke Pro"**:
  - Daftar benefit (iklan hilang, backup, PDF export, dst.)
  - Pilihan plan: Bulanan / Tahunan dengan highlight "Hemat 2 bulan"
  - Tombol "Mulai Trial 7 Hari Gratis" → launch billing flow
  - Link ke "Kelola langganan" (buka Play Store subscription page) untuk user yang sudah subscribe
- [ ] Handle user flow: cancel, resubscribe, upgrade/downgrade plan (monthly ↔ yearly) via `SubscriptionUpdateParams`
- [ ] (Rekomendasi) Server-side receipt verification via Google Play Developer API untuk anti-piracy — bisa jadi follow-up issue

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
- [ ] User bisa subscribe bulanan/tahunan dan iklan langsung hilang setelah subscription aktif
- [ ] Free trial 7 hari berjalan dengan benar tanpa ter-charge
- [ ] Saat subscription dibatalkan / expired → iklan kembali muncul otomatis
- [ ] Grace period & on-hold ditangani dengan UX yang jelas (banner + reminder)
- [ ] Restore subscription berfungsi di device baru (login akun Google yang sama)
- [ ] Lolos review Google Play Store (subscription disclosure, cancel instructions di listing wajib ada)

## Catatan Teknis
- Branch kerja: `feat/ads-and-playstore-purchase`
- Library yang akan ditambahkan kemungkinan akan menambah ukuran APK ±2–3 MB — perlu dievaluasi
- Pertimbangkan split AAB per ABI untuk mengurangi ukuran download
