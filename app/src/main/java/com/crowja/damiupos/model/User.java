package com.crowja.damiupos.model;

/** Pengguna aplikasi (multi user): kasir/operator depot dengan PIN login. */
public class User {

    public static final String ROLE_ADMIN = "admin";
    public static final String ROLE_STAF = "staf";
    /** SPV: turunan Staf (absen + transaksi sama seperti staf) PLUS kelola reseller penuh
     *  (tambah/edit/atur komisi/cairkan). Staf biasa hanya boleh MELIHAT reseller. */
    public static final String ROLE_SPV = "spv";
    /** Viewer: bisa lihat-lihat, TAPI tidak bisa buat transaksi & tidak diabsen.
     *  Diaktif/nonaktifkan admin, punya PIN seperti user lain. */
    public static final String ROLE_VIEWER = "viewer";
    /** Marketing: staf pemasaran. Membuat transaksi jual galon (opsi gratis/berbayar) &
     *  mengakuisisi pelanggan baru (poin promosi). Absensi dihitung fixed-day tanpa lembur. */
    public static final String ROLE_MARKETING = "marketing";

    /** Nama & PIN admin default yang dibuat otomatis saat fitur multi user
     *  diaktifkan — supaya owner selalu punya akses admin. Sebaiknya diganti. */
    public static final String DEFAULT_ADMIN_NAME = "Admin";
    public static final String DEFAULT_ADMIN_PIN = "00000";

    private long id;
    private String name;
    private String pin;
    private String role = ROLE_STAF;
    private boolean active = true;
    private String createdAt;

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getPin() { return pin; }
    public void setPin(String pin) { this.pin = pin; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public boolean isAdmin() { return ROLE_ADMIN.equals(role); }
    public boolean isStaf() { return ROLE_STAF.equals(role); }
    public boolean isSpv() { return ROLE_SPV.equals(role); }
    public boolean isViewer() { return ROLE_VIEWER.equals(role); }
    public boolean isMarketing() { return ROLE_MARKETING.equals(role); }

    /** Diabsen (clock in/out): Staf, SPV, Marketing. Admin & Viewer tidak.
     *  (Marketing diabsen tapi gajinya dihitung fixed-day tanpa lembur — lihat payroll.) */
    public boolean tracksAttendance() { return isStaf() || isSpv() || isMarketing(); }

    /** Transaksi penjualan: Admin, Staf, SPV, Marketing. Viewer tidak. */
    public boolean canCreateTransaction() { return isAdmin() || isStaf() || isSpv() || isMarketing(); }

    /** Kelola reseller (tambah/edit/atur komisi/cairkan): Admin & SPV. Staf/Marketing/Viewer hanya lihat. */
    public boolean canManageReseller() { return isAdmin() || isSpv(); }

    /** Boleh menandai transaksi GRATIS (promosi): Admin & Marketing. */
    public boolean canGiveFree() { return isAdmin() || isMarketing(); }

    /** Boleh menandai pelanggan "Sudah Order Ulang" (serah-terima ke perangkat lain):
     *  Admin & Marketing — mengikuti pola {@link #canGiveFree()}. */
    public boolean canHandoffCustomer() { return isAdmin() || isMarketing(); }

    /** Hapus transaksi: HANYA Admin. Karyawan non-admin (Staf/SPV/Marketing/Viewer)
     *  tidak boleh menghapus riwayat penjualan — mencegah manipulasi omzet/poin dari HP.
     *  Mode single-user (belum/tanpa login, uid<=0) tetap boleh — itu owner (di-handle pemanggil). */
    public boolean canDeleteTransaction() { return isAdmin(); }

    /** Edit TERBATAS transaksi (staf operator di konter): hanya metode pembayaran (JUAL) & jumlah
     *  galon pada transaksi KEMBALI — memperbaiki salah input tanpa bisa mengubah nominal/omzet.
     *  Staf, SPV, Admin (operasional konter); BUKAN Marketing (tak menangani transaksi) / Viewer. */
    public boolean canEditTransactionLimited() { return isStaf() || isSpv() || isAdmin(); }

    /** @deprecated pakai {@link #canGiveFree()} — dipertahankan untuk pemanggil lama. */
    public boolean canPromote() { return canGiveFree(); }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    /** Dipakai Spinner di layar login. */
    @Override
    public String toString() { return name != null ? name : "?"; }
}
