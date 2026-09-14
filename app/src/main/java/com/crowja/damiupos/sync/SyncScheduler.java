package com.crowja.damiupos.sync;

import android.content.Context;

import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

/** Schedules background sync: a periodic safety net + an immediate "sync now". */
public class SyncScheduler {

    private static final String PERIODIC = "damiu_sync_periodic";
    private static final String ONESHOT  = "damiu_sync_now";

    private static Constraints connected() {
        return new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
    }

    /** Periodic sync (~15 min) while enrolled. Idempotent. */
    public static void schedulePeriodic(Context ctx) {
        PeriodicWorkRequest req = new PeriodicWorkRequest.Builder(
                SyncWorker.class, 15, TimeUnit.MINUTES)
                .setConstraints(connected())
                .build();
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req);
    }

    /** Kick an immediate sync (e.g. after a local change or manual button). */
    public static void syncNow(Context ctx) {
        OneTimeWorkRequest req = new OneTimeWorkRequest.Builder(SyncWorker.class)
                .setConstraints(connected())
                .build();
        WorkManager.getInstance(ctx).enqueueUniqueWork(
                ONESHOT, ExistingWorkPolicy.REPLACE, req);
    }

    public static void cancelAll(Context ctx) {
        WorkManager.getInstance(ctx).cancelUniqueWork(PERIODIC);
    }
}
