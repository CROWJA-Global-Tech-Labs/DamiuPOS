package com.crowja.damiupos.model;

/** Satu event absensi: IN (clock in), BREAK (mulai istirahat), OUT (clock out). */
public class Attendance {

    public static final String EVENT_IN = "IN";
    public static final String EVENT_BREAK = "BREAK";
    public static final String EVENT_OUT = "OUT";

    private long id;
    private long userId;
    private String event;
    private String ts; // "yyyy-MM-dd HH:mm:ss"
    private String photoPath; // selfie wajah saat IN/OUT (nullable)
    private boolean outOfRadius;   // absen di luar area geofence cabang
    private int distanceM = -1;    // jarak dari pusat geofence (m); -1 = tak terukur
    private String radiusReason;   // alasan wajib saat absen di luar area (nullable)

    public Attendance() {}

    public Attendance(long userId, String event, String ts) {
        this.userId = userId;
        this.event = event;
        this.ts = ts;
    }

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }

    public long getUserId() { return userId; }
    public void setUserId(long userId) { this.userId = userId; }

    public String getEvent() { return event; }
    public void setEvent(String event) { this.event = event; }

    public String getTs() { return ts; }
    public void setTs(String ts) { this.ts = ts; }

    public String getPhotoPath() { return photoPath; }
    public void setPhotoPath(String photoPath) { this.photoPath = photoPath; }

    public boolean isOutOfRadius() { return outOfRadius; }
    public void setOutOfRadius(boolean outOfRadius) { this.outOfRadius = outOfRadius; }

    public int getDistanceM() { return distanceM; }
    public void setDistanceM(int distanceM) { this.distanceM = distanceM; }

    public String getRadiusReason() { return radiusReason; }
    public void setRadiusReason(String radiusReason) { this.radiusReason = radiusReason; }
}
