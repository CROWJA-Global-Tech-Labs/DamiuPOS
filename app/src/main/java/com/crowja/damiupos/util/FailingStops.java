package com.crowja.damiupos.util;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Order yang sedang dilaporkan gagal oleh kurir HP ini. Selama permintaannya berjalan, sinkron bisa
 * mendarat lebih dulu dan membawa status TERTUNDA: itu bukan "order hilang dari rit" untuk kurirnya.
 */
public final class FailingStops {
    private static final Set<Long> IDS = ConcurrentHashMap.newKeySet();

    private FailingStops() {}

    public static void add(long id) { IDS.add(id); }

    public static void remove(long id) { IDS.remove(id); }

    public static boolean contains(long id) { return IDS.contains(id); }
}
