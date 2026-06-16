package com.crowja.damiupos.sync;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

/** Runs a sync cycle in the background (WorkManager). Retries on failure. */
public class SyncWorker extends Worker {

    public SyncWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();
        SyncEngine engine = new SyncEngine(ctx);
        if (!engine.settings().isEnabled() || !engine.settings().isEnrolled()) {
            return Result.success(); // sync off / not enrolled — nothing to do
        }
        SyncEngine.Result r = engine.sync();
        // REST online tick: live config (/me), app-version check, admin broadcasts.
        // Replaces the former MQTT subscriptions — runs each periodic + "sync now".
        OnlineTasks.tick(ctx);
        return r.ok ? Result.success() : Result.retry();
    }
}
