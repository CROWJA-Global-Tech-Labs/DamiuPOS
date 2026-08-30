package com.crowja.damiupos.model;

public class Customer {

    /** Nama default lokasi pelanggan (dipakai lazy synthesis & baris kosong di form). */
    public static final String DEFAULT_LOCATION_NAME = "Kediaman";

    /** Satu lokasi bernama milik pelanggan (multi-lokasi, entri pertama = utama).
     *  wajibOngkir kini hidup PER LOKASI — flag legacy tingkat pelanggan hanya mirror
     *  dari lokasi utama untuk kompatibilitas. */
    public static class Location {
        /** Id stabil lokasi (cermin web) — kunci nama berkas koleksi foto supaya tetap menempel
         *  walau baris ditambah/dihapus/diurut ulang. Baris baru di HP membangkitkan id acak
         *  sendiri (lihat CustomerFormActivity.addLocationRow); null hanya untuk baris legacy
         *  yang belum pernah tersimpan sebagai locations JSON. */
        public String id;
        public String name;          // mis. "Kediaman", "Kantor"
        public double lat;
        public double lng;
        public boolean wajibOngkir;
        /** Foto UTAMA lokasi (URL server, kadang path lokal) = photos.get(0). null = pakai foto rumah
         *  pelanggan untuk lokasi utama. Di-round-trip apa adanya saat push supaya foto per-lokasi
         *  web tidak terhapus. Dipertahankan sebagai cermin tunggal untuk pembaca lama. */
        public String photo;

        /** Koleksi foto lokasi (maks 5) — bisa diedit di HP (Edit Pelanggan, pelanggan tersimpan)
         *  maupun web; keduanya menulis field JSON yang sama. {@code photo} di atas selalu =
         *  photos.get(0). */
        public java.util.List<String> photos = new java.util.ArrayList<>();

        public Location() {}

        public Location(String name, double lat, double lng, boolean wajibOngkir) {
            this.name = name;
            this.lat = lat;
            this.lng = lng;
            this.wajibOngkir = wajibOngkir;
        }
    }

    private long id;
    private String name;
    private String phone;
    private String address;
    /** Instruksi pengiriman tetap (mis. "titip satpam") — otomatis ditambahkan ke catatan SETIAP
     *  transaksi baru pelanggan ini dibuat (web & HP). Diedit di form Tambah/Edit Pelanggan. */
    private String orderNote;
    private String photoPath;   // path foto rumah
    private double latitude;    // koordinat GPS
    private double longitude;
    private String createdAt;
    // Desa/Kecamatan hasil reverse-geocode koordinat (server-authoritative, pull-only). Dusun
    // sengaja tak ada — bukan level administratif resmi Indonesia, tak ada sumber data yang andal.
    private String desa;
    private String kecamatan;

    // Calculated fields (not stored in DB)
    private int galonKeluar;   // total galon JUAL PINJAM (basis saldo pinjam)
    private int galonKembali;  // total galon yang dikembalikan
    private int totalTransaksi;
    private int galonTotalOrdered; // total galon dari SEMUA transaksi JUAL (semua ownership)
    private String firstOrderDate; // tanggal transaksi JUAL pertama (untuk hitung gl/hr)
    // Follow Up: perkiraan pelanggan seharusnya order lagi (yyyy-MM-dd) + rate konsumsi galon/hari,
    // diisi CustomerDao.getFollowUpCandidates dari data konsumsi. null / 0 = tak terukur.
    private String followUpReorderDay;
    private double followUpRate;

    // Reseller
    private boolean isReseller;
    private boolean wajibOngkir; // 1 = Transaksi Baru default Ongkos Kirim = Per Galon
    private String resellerSince;   // komisi dihitung dari JUAL setelah tanggal ini
    private int komisiGalon;        // calculated: total galon JUAL sejak jadi reseller
    private boolean komisiAddToPrice = true; // default ON: komisi ditambahkan ke harga jual
    private String linkedResellerUuid;       // uuid reseller rujukan pelanggan ini (auto-afiliasi di Transaksi Baru)
    private int galonPinjamAdjust;           // koreksi manual saldo galon dipinjam (offset dari web, disinkron)
    private boolean isMine = true;           // true = milik perangkat ini (origin sendiri); filter "Hanya Pelanggan Saya"
    private java.util.Map<String, Double> productPrices; // harga khusus per produk { product_uuid: harga }

    /** Follow Up: catatan opsional (di-set saat ditambahkan manual dari web). Disinkron. */
    private String followupNote;

    /** Follow Up: kapan terakhir di-follow-up (WA) — untuk urutan "Follow-up terakhir". Disinkron. */
    private String lastFollowupAt;

    /** Follow Up: timestamp penandaan MANUAL dari dashboard (NULL = otomatis). Disinkron. */
    private String followupManualAt;

    /** "Sudah Order Ulang": timestamp serah-terima marketing (NULL = belum). Disinkron. */
    private String handedOverAt;

    /** "Tandai Bermasalah": kategori masalah detail dipisah koma (phone,coordinate,photo,address,other). Disinkron. */
    private String issueFlags;
    /** Catatan bebas pelapor masalah. Disinkron. */
    private String issueNote;
    /** Kapan ditandai bermasalah (ISO), atau null. Disinkron. */
    private String issueReportedAt;
    /** Nama pelapor masalah. Disinkron. */
    private String issueReportedBy;
    /** Kapan ditandai "sudah diperbaiki" (ISO). Menyetel ini (bukan meng-null-kan laporan) yang menutup masalah. Disinkron. */
    private String issueResolvedAt;

    /** Pelanggan Prioritas: kapan ditandai (ISO), alasan, penanda, kapan dibatalkan (superseding). Disinkron. */
    private String priorityAt;
    private String priorityReason;
    private String priorityBy;
    private String priorityClearedAt;
    /** "Kunjungi Urgent" — lihat DatabaseHelper.COL_VISIT_URGENT_AT. Dua-timestamp bersuperseding;
     *  jangan diganti boolean (peng-null-an tak pernah tersinkron dari HP). */
    private String visitUrgentAt;
    private String visitUrgentBy;
    private String visitUrgentDoneAt;

    public Customer() {}

    public Customer(String name, String phone, String address) {
        this.name = name;
        this.phone = phone;
        this.address = address;
    }

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }

    public String getOrderNote() { return orderNote; }
    public void setOrderNote(String orderNote) { this.orderNote = orderNote; }

    public String getPhotoPath() { return photoPath; }
    public void setPhotoPath(String photoPath) { this.photoPath = photoPath; }

    private String photoUrl;   // URL foto rumah di server (baris sinkron dari perangkat lain/web)
    public String getPhotoUrl() { return photoUrl; }
    public void setPhotoUrl(String photoUrl) { this.photoUrl = photoUrl; }

    /** Punya foto rumah? true bila ada file lokal ATAU sudah terunggah ke server (photo_url). */
    public boolean hasPhoto() {
        return (photoPath != null && !photoPath.isEmpty())
                || (photoUrl != null && !photoUrl.isEmpty());
    }

    /** Punya koordinat lokasi yang sudah ditandai? */
    public boolean hasCoordinates() { return latitude != 0 || longitude != 0; }

    /** Data pelanggan belum lengkap untuk delivery: foto rumah ATAU koordinat kosong.
     *  Cermin sisi "tidak lengkap" dari server {@code CreditGuard::customerRevocable}. */
    public boolean isIncomplete() { return !hasPhoto() || !hasCoordinates(); }

    public double getLatitude() { return latitude; }
    public void setLatitude(double latitude) { this.latitude = latitude; }

    public double getLongitude() { return longitude; }
    public void setLongitude(double longitude) { this.longitude = longitude; }

    public String getDesa() { return desa; }
    public void setDesa(String v) { this.desa = v; }
    public String getKecamatan() { return kecamatan; }
    public void setKecamatan(String v) { this.kecamatan = v; }

    /** "Desa, Kec. X" — segmen kosong dibuang; "" bila keduanya belum di-geocode. */
    public String getAdminArea() {
        StringBuilder sb = new StringBuilder();
        if (desa != null && !desa.trim().isEmpty()) sb.append(desa.trim());
        if (kecamatan != null && !kecamatan.trim().isEmpty()) { if (sb.length() > 0) sb.append(", "); sb.append(kecamatan.trim()); }
        return sb.toString();
    }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    public String getFollowUpReorderDay() { return followUpReorderDay; }
    public void setFollowUpReorderDay(String v) { this.followUpReorderDay = v; }
    public double getFollowUpRate() { return followUpRate; }
    public void setFollowUpRate(double v) { this.followUpRate = v; }

    public String getFollowupNote() { return followupNote; }
    public void setFollowupNote(String followupNote) { this.followupNote = followupNote; }

    public String getLastFollowupAt() { return lastFollowupAt; }
    public void setLastFollowupAt(String v) { this.lastFollowupAt = v; }

    public String getFollowupManualAt() { return followupManualAt; }
    public void setFollowupManualAt(String v) { this.followupManualAt = v; }

    /** Timestamp "Sudah Order Ulang" (serah-terima marketing), atau null. */
    public String getHandedOverAt() { return handedOverAt; }
    public void setHandedOverAt(String v) { this.handedOverAt = v; }
    public boolean isHandedOver() { return handedOverAt != null && !handedOverAt.isEmpty(); }

    public String getIssueFlags() { return issueFlags; }
    public void setIssueFlags(String v) { this.issueFlags = v; }
    public String getIssueNote() { return issueNote; }
    public void setIssueNote(String v) { this.issueNote = v; }
    public String getIssueReportedAt() { return issueReportedAt; }
    public void setIssueReportedAt(String v) { this.issueReportedAt = v; }
    public String getIssueReportedBy() { return issueReportedBy; }
    public void setIssueReportedBy(String v) { this.issueReportedBy = v; }
    public String getIssueResolvedAt() { return issueResolvedAt; }
    public void setIssueResolvedAt(String v) { this.issueResolvedAt = v; }

    /**
     * Ada masalah AKTIF? Ditandai (issueReportedAt) dan belum diberesi — resolve menang hanya bila
     * issueResolvedAt TIDAK lebih lama dari laporan. Penandaan ulang (issueReportedAt lebih baru)
     * kembali membuka masalah. Cermin Customer::hasOpenIssue di web.
     *
     * <p>Bentuk stempelnya BISA CAMPUR: baris asal-web tiba sebagai ISO-UTC ("…T…Z") sedangkan HP
     * menulis waktu lokal ("yyyy-MM-dd HH:mm:ss"). Membandingkannya sebagai TEKS membuat ' ' (0x20)
     * kalah dari 'T' (0x54), jadi penyelesaian yang ditulis HP atas laporan dari web selalu terbaca
     * "lebih tua" → masalahnya tak pernah dianggap beres dan tombol Selesai di Antrian Delivery
     * terkunci selamanya. Bandingkan NILAI-nya, sama seperti isPriority()/needsUrgentVisit(). */
    public boolean hasOpenIssue() {
        if (issueReportedAt == null || issueReportedAt.isEmpty()) {
            return false;
        }
        if (issueResolvedAt == null || issueResolvedAt.isEmpty()) {
            return true;
        }
        return com.crowja.damiupos.util.Ts.millisOrMin(issueResolvedAt)
                < com.crowja.damiupos.util.Ts.millisOrMin(issueReportedAt);
    }

    /** Kategori masalah aktif sebagai daftar kunci bersih (["phone","coordinate",...]). */
    public java.util.List<String> issueFlagList() {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (!hasOpenIssue() || issueFlags == null || issueFlags.trim().isEmpty()) {
            return out;
        }
        for (String k : issueFlags.split(",")) {
            String key = k.trim();
            if (!key.isEmpty() && !out.contains(key)) {
                out.add(key);
            }
        }
        return out;
    }

    /** Label Indonesia kategori masalah aktif (cermin web Customer::issueLabelList & UI HP). */
    public java.util.List<String> issueLabelList() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String key : issueFlagList()) {
            out.add(issueLabelFor(key));
        }
        return out;
    }

    /** Label Indonesia untuk satu kunci kategori; kunci tak dikenal → kunci apa adanya. */
    public static String issueLabelFor(String key) {
        switch (key != null ? key : "") {
            case "phone": return "Nomor HP tidak sesuai";
            case "coordinate": return "Koordinat/lokasi tidak sesuai";
            case "photo": return "Foto tidak sesuai";
            case "address": return "Alamat tidak lengkap/keliru";
            case "other": return "Lainnya";
            default: return key != null ? key : "";
        }
    }

    public String getPriorityAt() { return priorityAt; }
    public void setPriorityAt(String v) { this.priorityAt = v; }
    public String getPriorityReason() { return priorityReason; }
    public void setPriorityReason(String v) { this.priorityReason = v; }
    public String getPriorityBy() { return priorityBy; }
    public void setPriorityBy(String v) { this.priorityBy = v; }
    public String getPriorityClearedAt() { return priorityClearedAt; }
    public void setPriorityClearedAt(String v) { this.priorityClearedAt = v; }

    /** Pelanggan PRIORITAS aktif? Ditandai & belum dibatalkan (cancel tidak lebih baru dari
     *  penandaan; penandaan ulang membuka lagi). Cermin Customer::isPriority di web. */
    public boolean isPriority() {
        if (priorityAt == null || priorityAt.isEmpty()) {
            return false;
        }
        if (priorityClearedAt == null || priorityClearedAt.isEmpty()) {
            return true;
        }
        // Bentuk stempel bisa campur (server ISO-UTC vs tulisan HP lokal) → bandingkan nilainya,
        // bukan teksnya. Lihat com.crowja.damiupos.util.Ts.
        return com.crowja.damiupos.util.Ts.millisOrMin(priorityClearedAt)
                < com.crowja.damiupos.util.Ts.millisOrMin(priorityAt);
    }

    public String getVisitUrgentAt() { return visitUrgentAt; }
    public void setVisitUrgentAt(String v) { this.visitUrgentAt = v; }

    public String getVisitUrgentBy() { return visitUrgentBy; }
    public void setVisitUrgentBy(String v) { this.visitUrgentBy = v; }

    public String getVisitUrgentDoneAt() { return visitUrgentDoneAt; }
    public void setVisitUrgentDoneAt(String v) { this.visitUrgentDoneAt = v; }

    /** Masih menunggu "Kunjungi Urgent"? Bentuknya sama persis dengan {@link #isPriority()}:
     *  ditandai & belum diselesaikan (penyelesaian tidak lebih baru dari penandaan; menandai ulang
     *  membuka lagi). Cermin Customer::needsUrgentVisit di web. */
    public boolean needsUrgentVisit() {
        if (visitUrgentAt == null || visitUrgentAt.isEmpty()) {
            return false;
        }
        if (visitUrgentDoneAt == null || visitUrgentDoneAt.isEmpty()) {
            return true;
        }
        return com.crowja.damiupos.util.Ts.millisOrMin(visitUrgentDoneAt)
                < com.crowja.damiupos.util.Ts.millisOrMin(visitUrgentAt);
    }

    public int getGalonKeluar() { return galonKeluar; }
    public void setGalonKeluar(int galonKeluar) { this.galonKeluar = galonKeluar; }

    public int getGalonKembali() { return galonKembali; }
    public void setGalonKembali(int galonKembali) { this.galonKembali = galonKembali; }

    public int getTotalTransaksi() { return totalTransaksi; }
    public void setTotalTransaksi(int totalTransaksi) { this.totalTransaksi = totalTransaksi; }

    /**
     * "Galon Dipinjam" = galon FISIK yang sedang ada di konsumen = saldo berjalan floor-0 dari server
     * (srvHeld, {@see com.crowja.damiupos.model.Customer#srvHeld} — lintas perangkat) + koreksi manual.
     *
     * <p>BUKAN lagi Σkeluar−Σkembali: itu keliru jadi 0 untuk pelanggan yang MENUKAR galon (kembalikan
     * kosong di hari yang sama dengan ambil isi) karena KEMBALI pertama mengembalikan galon yang
     * keluarnya belum pernah tercatat. Server menghitung saldo berjalan yang dipagari di 0 (tak bisa
     * kembalikan lebih dari yang dipegang) → angka benar-benar mencerminkan batch yang dipegang.
     * Untuk baris merged, {@code applyMergedAggregates} menaruh jumlah srvHeld lintas grup di sini
     * (via {@link #heldFinalized}, lihat catatan di bawah).</p>
     *
     * <p>Kecuali: pelanggan yang server-nya BELUM PERNAH mencatat transaksi apa pun untuknya
     * (srvTrx==0 — baru didaftarkan & transaksi pertamanya belum sempat push/pull) memakai floor-0
     * LOKAL sederhana ({@see #heldBeforeAdjust}) supaya struk/detail transaksi pertama pelanggan baru
     * tak menunjukkan 0 sambil menunggu sinkronisasi.</p>
     *
     * <p><b>Gerbang srvTrx==0 TIDAK sepenuhnya rapat</b> — server menghitung srvTrx via
     * excludingTertunda() (Σ transaksi TANPA yang masih TERTUNDA/diparkir), jadi seorang pelanggan yang
     * SELURUH histori transaksinya (lintas perangkat) masih TERTUNDA tetap punya srvTrx==0 walau
     * perangkat lain sudah benar-benar mencatat order untuknya — jendela ini bisa sepanjang jadwal
     * tunda itu sendiri (jam–hari), bukan sekadar "sampai sinkron berikutnya". Jalur lokal juga TIDAK
     * mengecualikan TERTUNDA (beda dari server), jadi dalam jendela itu angkanya bisa condong lebih
     * tinggi dari floor-0 sebenarnya untuk galon yang belum benar-benar diserahkan. Risiko diterima:
     * sempit (perlu histori 100% tertunda), swa-pulih begitu satu order mana pun ter-pull, dan jauh
     * lebih jarang daripada bug lama (selalu 0 untuk pelanggan baru) yang digantikannya.</p>
     */
    public int getSaldoGalon() {
        return heldBeforeAdjust() + galonPinjamAdjust;
    }

    /** Sudah di-merge {@code CustomerDao.applyMergedAggregates} — srvHeld di objek ini SUDAH jumlah
     *  floor-then-sum yang benar per anggota grup; jangan dihitung ulang dari galonKeluar/galonKembali/
     *  srvTrx (field itu ditimpa jadi TOTAL grup demi tujuan LAIN — bukan bahan floor-0 lagi). */
    private boolean heldFinalized;

    public boolean isHeldFinalized() { return heldFinalized; }
    public void setHeldFinalized(boolean v) { this.heldFinalized = v; }

    /**
     * {@link #getSaldoGalon()} tanpa koreksi manual — dipakai {@code CustomerDao.applyMergedAggregates}
     * yang menjumlah galonPinjamAdjust seluruh grup secara terpisah (menjumlah versi TERMASUK adjust
     * di sana akan menghitung adjust dua kali).
     */
    public int heldBeforeAdjust() {
        if (heldFinalized) return srvHeld;
        return srvTrx == 0 ? Math.max(0, galonKeluar - galonKembali) : srvHeld;
    }

    /** Koreksi manual "Galon Dipinjam" (offset) — disetel dari web, disinkron. */
    public int getGalonPinjamAdjust() { return galonPinjamAdjust; }
    public void setGalonPinjamAdjust(int v) { this.galonPinjamAdjust = v; }

    /** true = pelanggan milik perangkat ini (origin sendiri). Basis filter "Hanya Pelanggan Saya". */
    public boolean isMine() { return isMine; }
    public void setMine(boolean v) { this.isMine = v; }

    // --- Agregat lintas-perangkat dari server (pull-only) + label perangkat asal ---
    // Untuk salinan MILIK perangkat ini dipakai agregat LOKAL (lebih segar); salinan perangkat
    // lain (is_mine=0) pakai agregat server ini. Daftar menjumlahkannya per grup dedup.
    private int srvTrx, srvOrdered, srvBorrowed, srvKembali;
    /** "Galon Dipinjam" = saldo berjalan floor-0 (galon FISIK di konsumen) menurut SERVER, lintas
     *  perangkat — pull-only. Menggantikan srvBorrowed−srvKembali yang keliru 0 untuk pelanggan tukar. */
    private int srvHeld;
    private String srvFirstJual;
    private String originLabel;                       // label perangkat asal baris ini (nama device / "Web")
    private java.util.List<String> originLabels;      // gabungan label seluruh salinan (untuk tag daftar)

    public int getSrvTrx() { return srvTrx; }
    public void setSrvTrx(int v) { this.srvTrx = v; }
    public int getSrvOrdered() { return srvOrdered; }
    public void setSrvOrdered(int v) { this.srvOrdered = v; }
    public int getSrvBorrowed() { return srvBorrowed; }
    public void setSrvBorrowed(int v) { this.srvBorrowed = v; }
    public int getSrvKembali() { return srvKembali; }
    public void setSrvKembali(int v) { this.srvKembali = v; }
    public int getSrvHeld() { return srvHeld; }
    public void setSrvHeld(int v) { this.srvHeld = v; }
    public String getSrvFirstJual() { return srvFirstJual; }
    public void setSrvFirstJual(String v) { this.srvFirstJual = v; }
    public String getOriginLabel() { return originLabel; }
    public void setOriginLabel(String v) { this.originLabel = v; }

    // ---- Daftar Kunjungan (marketing) ----
    /** Order TERAKHIR lintas perangkat (agg server, pull-only); null = belum ada / belum pull. */
    private String srvLastJual;
    /** Order terakhir menurut transaksi LOKAL perangkat ini (kolom query local_last_jual). */
    private String localLastJual;
    /** "Sudah Dikunjungi" — LOKAL perangkat ini (tidak disinkron). Null = belum. */
    private String visitedAt;
    /** "Didaftarkan oleh" (created_by_name, v51) — nama operator pencatat. */
    private String createdByName;

    public String getSrvLastJual() { return srvLastJual; }
    public void setSrvLastJual(String v) { this.srvLastJual = v; }
    public String getLocalLastJual() { return localLastJual; }
    public void setLocalLastJual(String v) { this.localLastJual = v; }
    public String getVisitedAt() { return visitedAt; }
    public void setVisitedAt(String v) { this.visitedAt = v; }
    public boolean isVisited() { return visitedAt != null && !visitedAt.isEmpty(); }
    public String getCreatedByName() { return createdByName; }
    public void setCreatedByName(String v) { this.createdByName = v; }

    /** Saldo komisi reseller menurut SERVER (lintas semua perangkat) — pull-only. */
    private double srvSaldo;
    public double getSrvSaldo() { return srvSaldo; }
    public void setSrvSaldo(double v) { this.srvSaldo = v; }

    /** Galon promosi gratis (JUAL Rp 0 di hari daftar) menurut SERVER, lintas perangkat —
     *  pull-only. Fallback "Tarik Galon Promosi" bila akuisisinya tercatat di perangkat lain. */
    private int srvPromoGalon;
    public int getSrvPromoGalon() { return srvPromoGalon; }
    public void setSrvPromoGalon(int v) { this.srvPromoGalon = v; }

    /** Galon promosi yang SUDAH DITARIK (KEMBALI bermarker) menurut SERVER, lintas perangkat —
     *  pull-only. Pasangan srvPromoGalon supaya perangkat lain tak menarik ulang galon yang sama. */
    private int srvPromoPulled;
    public int getSrvPromoPulled() { return srvPromoPulled; }
    public void setSrvPromoPulled(int v) { this.srvPromoPulled = v; }
    public java.util.List<String> getOriginLabels() { return originLabels; }
    public void setOriginLabels(java.util.List<String> v) { this.originLabels = v; }

    // Nama tampilan gabungan bila orang yang SAMA punya nama berbeda antar-perangkat, mis.
    // "Hanny Taman Sentosa / Bp Okky". Diisi saat dedup (applyMergedAggregates); null → pakai name.
    private String displayName;
    private int mergedNameCount = 1;   // jumlah nama unik dalam grup dedup (>1 = nama beda antar-perangkat)
    /** Nama untuk DITAMPILKAN (gabungan lintas-perangkat bila ada); fallback ke name asli. */
    public String getDisplayName() {
        return (displayName == null || displayName.isEmpty()) ? name : displayName;
    }
    public void setDisplayName(String v) { this.displayName = v; }
    public int getMergedNameCount() { return mergedNameCount; }
    public void setMergedNameCount(int v) { this.mergedNameCount = v; }

    /**
     * Agregat efektif per baris = MAX(lokal, server). Server (srv_*) adalah total lintas-SEMUA
     * perangkat untuk uuid ini (dihitung ulang tiap pull); lokal hanya lebih segar untuk transaksi
     * yang dibuat DI perangkat ini di antara dua pull.
     *
     * <p>Dulu memakai {@code isMine ? lokal : server}. Itu keliru untuk pola marketing→depot:
     * pelanggan DIDAFTARKAN di HP marketing (is_mine=true) tetapi ORDER-nya dilayani/di-input di
     * perangkat DEPOT (transaksi terisolasi per-perangkat, tak ikut sinkron). Akibatnya HP marketing
     * memakai hitungan LOKAL (0/kurang) dan mengabaikan agregat server yang berisi penjualan depot →
     * "Total Galon 0" padahal sudah pernah order. MAX memperbaiki keduanya: penjualan lokal yang baru
     * (server belum ke-pull) → lokal menang; penjualan di perangkat lain → server menang. Cermin pola
     * "merged max(local,srv)" pada galon promosi. */
    public int effectiveTrx()      { return Math.max(totalTransaksi, srvTrx); }
    public int effectiveOrdered()  { return Math.max(galonTotalOrdered, srvOrdered); }
    public int effectiveBorrowed() { return Math.max(galonKeluar, srvBorrowed); }
    public int effectiveKembali()  { return Math.max(galonKembali, srvKembali); }

    /** Tanggal JUAL pertama efektif = yang PALING AWAL antara lokal & server (abaikan null/kosong). */
    public String effectiveFirstJual() {
        String local = firstOrderDate, srv = srvFirstJual;
        boolean lb = local == null || local.isEmpty();
        boolean sb = srv == null || srv.isEmpty();
        if (lb) return sb ? null : srv;
        if (sb) return local;
        return local.compareTo(srv) <= 0 ? local : srv;   // ISO → string compare = kronologis
    }

    public int getGalonTotalOrdered() { return galonTotalOrdered; }
    public void setGalonTotalOrdered(int v) { this.galonTotalOrdered = v; }

    public String getFirstOrderDate() { return firstOrderDate; }
    public void setFirstOrderDate(String v) { this.firstOrderDate = v; }

    /** SimpleDateFormat mahal & tak thread-safe → satu per thread, dipakai ulang (getKonsumsiPerHari
     *  dipanggil sekali per bind kartu pelanggan saat scroll). */
    private static final ThreadLocal<java.text.SimpleDateFormat> DT_FMT =
            new ThreadLocal<java.text.SimpleDateFormat>() {
                @Override protected java.text.SimpleDateFormat initialValue() {
                    return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US);
                }
            };
    private static final java.util.TimeZone UTC_TZ = java.util.TimeZone.getTimeZone("UTC");

    /**
     * Konsumsi galon per hari = total galon di-order / jumlah hari sejak order
     * pertama (minimal 1 hari supaya tidak bagi nol). 0 kalau belum pernah order.
     */
    public double getKonsumsiPerHari() {
        if (galonTotalOrdered <= 0 || firstOrderDate == null || firstOrderDate.isEmpty()) {
            return 0;
        }
        try {
            // tanggal bisa LOKAL ("yyyy-MM-dd HH:mm:ss") atau ISO UTC tersinkron
            // ("yyyy-MM-ddTHH:mm:ss[.ffffff]Z"/offset). Normalkan dulu — kalau tidak, baris ISO
            // gagal di-parse (huruf 'T' ≠ spasi) → balik 0 → kartu salah tampil "<0.1 gl/hr".
            String s = firstOrderDate.trim();
            boolean utc = s.endsWith("Z");
            String core = (utc ? s.substring(0, s.length() - 1) : s).replace('T', ' ');
            int dot = core.indexOf('.');
            if (dot > 0) core = core.substring(0, dot);          // buang pecahan detik
            if (core.length() > 19) core = core.substring(0, 19); // buang ekor offset (mis. +07:00)
            core = core.trim();
            java.text.SimpleDateFormat sdf = DT_FMT.get();
            sdf.setTimeZone(utc ? UTC_TZ : java.util.TimeZone.getDefault());
            java.util.Date first = sdf.parse(core);
            if (first == null) return 0;
            long diffMs = System.currentTimeMillis() - first.getTime();
            double days = diffMs / (1000.0 * 60 * 60 * 24);
            if (days < 1) days = 1; // pelanggan baru order hari ini → anggap 1 hari
            return galonTotalOrdered / days;
        } catch (Exception e) {
            return 0;
        }
    }

    public boolean isReseller() { return isReseller; }
    public void setReseller(boolean v) { this.isReseller = v; }

    /** "Bonus Beli N Gratis 1" diaktifkan — DI-SET DI WEB saja, HP hanya membaca (tak ada UI
     *  untuk mengubahnya di sini, jadi setter tak pernah dipanggil dari layar edit lokal). */
    private boolean bonusEnabled;
    public boolean isBonusEnabled() { return bonusEnabled; }
    public void setBonusEnabled(boolean v) { this.bonusEnabled = v; }

    public boolean isWajibOngkir() { return wajibOngkir; }
    public void setWajibOngkir(boolean v) { this.wajibOngkir = v; }

    public String getResellerSince() { return resellerSince; }
    public void setResellerSince(String v) { this.resellerSince = v; }

    /** Total galon JUAL sejak jadi reseller (basis perhitungan komisi). */
    public int getKomisiGalon() { return komisiGalon; }
    public void setKomisiGalon(int v) { this.komisiGalon = v; }

    /** True = komisi reseller ini ditambahkan ke harga air minum saat transaksi. */
    public boolean isKomisiAddToPrice() { return komisiAddToPrice; }
    public void setKomisiAddToPrice(boolean v) { this.komisiAddToPrice = v; }

    public String getLinkedResellerUuid() { return linkedResellerUuid; }
    public void setLinkedResellerUuid(String v) { this.linkedResellerUuid = v; }

    /** Override penugasan perangkat (di-set web; HP baca) — MENGALAHKAN wilayah otomatis. Kosong/null
     *  = ikut wilayah. Dipakai default perangkat Transaksi Baru + filter "Wilayah Saya". */
    private String assignedDeviceUuid;
    public String getAssignedDeviceUuid() { return assignedDeviceUuid; }
    public void setAssignedDeviceUuid(String v) { this.assignedDeviceUuid = v; }

    /** Harga khusus per produk { product_uuid: harga } (null/kosong = ikut harga produk standar). */
    public java.util.Map<String, Double> getProductPrices() { return productPrices; }
    public void setProductPrices(java.util.Map<String, Double> v) { this.productPrices = v; }

    /** Harga khusus untuk satu produk (by uuid), atau null = pakai harga produk standar. */
    public Double getPriceFor(String productUuid) {
        return (productPrices != null && productUuid != null) ? productPrices.get(productUuid) : null;
    }

    /** Multi-lokasi bernama (terurut, entri pertama = utama). Null/kosong = hanya koordinat
     *  legacy — DAO melakukan lazy synthesis "Kediaman" saat baca, jadi setelah lewat DAO
     *  daftar ini terisi bila pelanggan punya koordinat. */
    private java.util.List<Location> locations;

    public java.util.List<Location> getLocations() { return locations; }
    public void setLocations(java.util.List<Location> v) { this.locations = v; }

    /** Lokasi utama (entri pertama), atau null bila tidak ada lokasi. */
    public Location getPrimaryLocation() {
        return (locations != null && !locations.isEmpty()) ? locations.get(0) : null;
    }

    /** Punya SETIDAKNYA SATU lokasi bertanda wajib ongkir? (wajibOngkir hidup per-lokasi; flag
     *  skalar hanya dipakai untuk baris legacy yang belum punya daftar lokasi sama sekali). */
    public boolean hasWajibOngkirLocation() {
        if (locations != null && !locations.isEmpty()) {
            for (Location l : locations) {
                if (l != null && l.wajibOngkir) return true;
            }
            return false;
        }
        return wajibOngkir;   // legacy: belum ada daftar lokasi → pakai mirror tingkat pelanggan
    }

    /** Ada salinan LAIN (lintas perangkat) dalam grup dedup yang punya lokasi wajib ongkir.
     *  Display-only: diisi CustomerDao.applyMergedAggregates, tak pernah ditulis ke DB. */
    private boolean wajibOngkirMerged;

    public void setWajibOngkirMerged(boolean v) { this.wajibOngkirMerged = v; }

    /** Badge "ONGKIR" pada kartu: lokasi wajib ongkir milik sendiri ATAU milik salinan lain. */
    public boolean isWajibOngkirEffective() {
        return hasWajibOngkirLocation() || wajibOngkirMerged;
    }

    /** Daftar NOMOR HP (terurut, entri pertama = utama). Satu orang bisa punya beberapa nomor setelah
     *  "Gabung Pelanggan" (survivor menyerap semua nomor). Null = baris legacy → pakai getPhonesOrDefault
     *  yang sintesis [phone] dari skalar. */
    private java.util.List<String> phones;

    public java.util.List<String> getPhones() { return phones; }
    public void setPhones(java.util.List<String> v) { this.phones = v; }

    /** Daftar nomor bersih, utama dulu (sintesis [phone] bila `phones` kosong/null — back-compat). */
    public java.util.List<String> getPhonesOrDefault() {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (phones != null) {
            for (String p : phones) {
                if (p != null && !p.trim().isEmpty()) out.add(p.trim());
            }
        }
        if (!out.isEmpty()) return out;
        if (phone != null && !phone.trim().isEmpty()) out.add(phone.trim());
        return out;
    }
}
