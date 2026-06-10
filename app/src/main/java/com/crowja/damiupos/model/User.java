package com.crowja.damiupos.model;

/** Pengguna aplikasi (multi user): kasir/operator depot dengan PIN login. */
public class User {

    public static final String ROLE_ADMIN = "admin";
    public static final String ROLE_STAF = "staf";

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

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    /** Dipakai Spinner di layar login. */
    @Override
    public String toString() { return name != null ? name : "?"; }
}
