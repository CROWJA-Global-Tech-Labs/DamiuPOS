package com.crowja.damiupos.util;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Logika murni Modus Ider (tanpa Android): peringkat pelanggan TERDEKAT dari posisi sekarang,
 * format jarak, dan badge "terakhir beli". Dipisah dari {@code IderModeActivity} supaya bisa diuji
 * unit dan dihitung ulang tiap fix GPS tanpa menyentuh database.
 *
 * <p>Satu {@link Person} = satu ORANG (grup dedup lintas perangkat) dengan SEMUA titik lokasinya
 * (multi-lokasi). Jarak seseorang = jarak titik TERDEKAT-nya, jadi pelanggan dengan rumah + toko
 * muncul sekali saja, di urutan titik yang paling dekat.
 */
public final class IderRanking {

    private IderRanking() {}

    /** Batas kartu di daftar atas. */
    public static final int MAX_LIST = 10;

    /** Satu titik lokasi milik seseorang. {@code ownerId} = salinan pelanggan (id lokal) pemilik titik ini. */
    public static final class Spot {
        public final String name;
        public final double lat;
        public final double lng;
        public final long ownerId;
        /** Foto lokasi (URL server atau path lokal), null = tak ada foto. */
        public final String photo;

        public Spot(String name, double lat, double lng, long ownerId) {
            this(name, lat, lng, ownerId, null);
        }

        public Spot(String name, double lat, double lng, long ownerId, String photo) {
            this.name = name;
            this.lat = lat;
            this.lng = lng;
            this.ownerId = ownerId;
            this.photo = photo;
        }
    }

    /** Satu orang + semua titiknya. {@code lastOrderMillis} = Long.MIN_VALUE bila belum pernah beli. */
    public static final class Person {
        public final String key;
        public final long customerId;
        public final String name;
        public final long lastOrderMillis;
        public final List<Spot> spots;
        /** Prediksi order kembali "yyyy-MM-dd" (model Follow Up), null = belum terukur. */
        public final String reorderDay;

        public Person(String key, long customerId, String name, long lastOrderMillis, List<Spot> spots) {
            this(key, customerId, name, lastOrderMillis, spots, null);
        }

        public Person(String key, long customerId, String name, long lastOrderMillis, List<Spot> spots,
                      String reorderDay) {
            this.key = key;
            this.customerId = customerId;
            this.name = name;
            this.lastOrderMillis = lastOrderMillis;
            this.spots = spots;
            this.reorderDay = reorderDay;
        }
    }

    /** Hasil: orang + titik terdekatnya + jarak (meter). {@code spotIndex} = indeks titik itu di {@code person.spots}. */
    public static final class Ranked {
        public final Person person;
        public final int spotIndex;
        public final double meters;

        Ranked(Person person, int spotIndex, double meters) {
            this.person = person;
            this.spotIndex = spotIndex;
            this.meters = meters;
        }

        public Spot spot() {
            return person.spots.get(spotIndex);
        }
    }

    /** Satu titik untuk pin peta + jaraknya (semua titik, bukan per orang). */
    public static final class SpotHit {
        public final Person person;
        public final int spotIndex;
        public final double meters;

        SpotHit(Person person, int spotIndex, double meters) {
            this.person = person;
            this.spotIndex = spotIndex;
            this.meters = meters;
        }
    }

    /** Jarak lingkaran besar (meter). */
    public static double haversineMeters(double aLat, double aLng, double bLat, double bLng) {
        double r = 6371000.0;
        double dLat = Math.toRadians(bLat - aLat);
        double dLng = Math.toRadians(bLng - aLng);
        double s = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(aLat)) * Math.cos(Math.toRadians(bLat))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * r * Math.asin(Math.min(1.0, Math.sqrt(s)));
    }

    /**
     * Maksimal {@code limit} orang terdekat dari (lat,lng), urut naik jaraknya. Seri jarak → nama,
     * supaya urutan tak "berkedip" saat dua pelanggan sama jauhnya. Orang tanpa titik dilewati.
     */
    public static List<Ranked> nearest(List<Person> people, double lat, double lng, int limit) {
        return nearest(people, lat, lng, limit, 0);
    }

    /** Seperti {@link #nearest(List, double, double, int)}, hanya yang titik terdekatnya ≤ {@code maxMeters} (0 = tanpa batas). */
    public static List<Ranked> nearest(List<Person> people, double lat, double lng, int limit, double maxMeters) {
        List<Ranked> all = new ArrayList<>(people.size());
        for (Person p : people) {
            int best = -1;
            double bestM = Double.MAX_VALUE;
            for (int i = 0; i < p.spots.size(); i++) {
                Spot s = p.spots.get(i);
                double m = haversineMeters(lat, lng, s.lat, s.lng);
                if (m < bestM) {
                    bestM = m;
                    best = i;
                }
            }
            if (best >= 0 && (maxMeters <= 0 || bestM <= maxMeters)) all.add(new Ranked(p, best, bestM));
        }
        Collections.sort(all, (a, b) -> {
            int c = Double.compare(a.meters, b.meters);
            if (c != 0) return c;
            String an = a.person.name == null ? "" : a.person.name;
            String bn = b.person.name == null ? "" : b.person.name;
            return an.compareToIgnoreCase(bn);
        });
        return all.size() > limit ? new ArrayList<>(all.subList(0, limit)) : all;
    }

    /** Maksimal {@code limit} TITIK terdekat (untuk pin peta), urut naik jaraknya. */
    public static List<SpotHit> nearestSpots(List<Person> people, double lat, double lng, int limit) {
        List<SpotHit> all = new ArrayList<>();
        for (Person p : people) {
            for (int i = 0; i < p.spots.size(); i++) {
                Spot s = p.spots.get(i);
                all.add(new SpotHit(p, i, haversineMeters(lat, lng, s.lat, s.lng)));
            }
        }
        Collections.sort(all, (a, b) -> Double.compare(a.meters, b.meters));
        return all.size() > limit ? new ArrayList<>(all.subList(0, limit)) : all;
    }

    /** "35 m", "120 m", "1,2 km", "15 km" (desimal koma ala Indonesia). */
    public static String formatDistance(double meters) {
        if (meters < 1000) {
            long m = Math.round(meters);
            if (m >= 100) m = Math.round(meters / 10.0) * 10;   // 123 m → 120 m: angka tak menari tiap langkah
            if (m >= 1000) return "1 km";                       // 995..999 m dibulatkan ke 1000
            return m + " m";
        }
        double km = meters / 1000.0;
        if (km < 10) {
            long tenths = Math.round(km * 10);
            if (tenths >= 100) return "10 km";                  // 9,95..9,99 km dibulatkan ke 10
            return (tenths / 10) + "," + (tenths % 10) + " km";
        }
        return Math.round(km) + " km";
    }

    /** Tingkat badge terakhir beli — menentukan warna. */
    public enum BuyLevel { NEVER, FRESH, WARN, STALE }

    /** Badge "terakhir beli": teks + tingkat. */
    public static final class BuyBadge {
        public final String text;
        public final BuyLevel level;

        BuyBadge(String text, BuyLevel level) {
            this.text = text;
            this.level = level;
        }
    }

    private static final String[] MONTHS = {"Jan", "Feb", "Mar", "Apr", "Mei", "Jun",
            "Jul", "Agu", "Sep", "Okt", "Nov", "Des"};

    /**
     * Badge terakhir beli berdasarkan selisih HARI KALENDER (zona {@code tz}): order kemarin malam
     * tetap "kemarin" walau baru 3 jam berlalu. ≤7 hari = segar, 8–30 = mulai lama, >30 = lama sekali.
     */
    public static BuyBadge lastBuyBadge(long lastOrderMillis, long nowMillis, TimeZone tz) {
        if (lastOrderMillis == Long.MIN_VALUE || lastOrderMillis <= 0) {
            return new BuyBadge("🆕 Belum pernah beli", BuyLevel.NEVER);
        }
        long days = Math.max(0, calendarDaysBetween(lastOrderMillis, nowMillis, tz));
        Calendar c = Calendar.getInstance(tz, Locale.US);
        c.setTimeInMillis(lastOrderMillis);
        String date = c.get(Calendar.DAY_OF_MONTH) + " " + MONTHS[c.get(Calendar.MONTH)];
        String text;
        if (days == 0) {
            text = "🛒 Beli hari ini";
        } else if (days == 1) {
            text = "🛒 Beli kemarin";
        } else if (days <= 60) {
            text = "🛒 Beli " + days + " hari lalu · " + date;
        } else {
            text = "🛒 Beli " + (days / 30) + " bulan lalu · " + date;
        }
        BuyLevel level = days <= 7 ? BuyLevel.FRESH : (days <= 30 ? BuyLevel.WARN : BuyLevel.STALE);
        return new BuyBadge(text, level);
    }

    /**
     * Badge PREDIKSI ORDER KEMBALI (hari "yyyy-MM-dd" dari model Follow Up) — status cermin web
     * (FollowUpDue::forecastStatus, SOON_DAYS=3): lewat = jatuh tempo (merah), ≤3 hari = segera
     * (oranye), selebihnya terjadwal (hijau). null bila belum ada prediksi (sekali beli / tanpa data).
     */
    public static BuyBadge reorderBadge(String reorderDay, long nowMillis, TimeZone tz) {
        if (reorderDay == null || reorderDay.length() < 10) return null;
        Calendar c = Calendar.getInstance(tz, Locale.US);
        c.clear();
        try {
            c.set(Integer.parseInt(reorderDay.substring(0, 4)), Integer.parseInt(reorderDay.substring(5, 7)) - 1,
                    Integer.parseInt(reorderDay.substring(8, 10)), 12, 0, 0);
        } catch (NumberFormatException e) {
            return null;
        }
        long days = calendarDaysBetween(nowMillis, c.getTimeInMillis(), tz);
        String date = c.get(Calendar.DAY_OF_MONTH) + " " + MONTHS[c.get(Calendar.MONTH)];
        if (days < 0) return new BuyBadge("⏰ Jatuh tempo order · lewat " + (-days) + " hari", BuyLevel.STALE);
        if (days == 0) return new BuyBadge("⌛ Prediksi order lagi hari ini", BuyLevel.WARN);
        if (days == 1) return new BuyBadge("⌛ Prediksi order lagi besok", BuyLevel.WARN);
        if (days <= 3) return new BuyBadge("⌛ Prediksi order lagi " + days + " hari · " + date, BuyLevel.WARN);
        return new BuyBadge("📅 Prediksi order lagi " + days + " hari · " + date, BuyLevel.FRESH);
    }

    /** Selisih hari kalender (b − a) di zona {@code tz}, bebas efek jam/DST. */
    static long calendarDaysBetween(long aMillis, long bMillis, TimeZone tz) {
        return dayNumber(bMillis, tz) - dayNumber(aMillis, tz);
    }

    private static long dayNumber(long millis, TimeZone tz) {
        long local = millis + tz.getOffset(millis);
        return Math.floorDiv(local, 86_400_000L);
    }
}
