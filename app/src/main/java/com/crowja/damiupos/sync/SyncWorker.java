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
        SyncEngine engine = new SyncEngine(getApplicationContext());
        if (!engine.settings().isEnabled() || !engine.settings().isEnrolled()) {
            return Result.success(); // sync off / not enrolled — nothing to do
        }
        SyncEngine.Result r = engine.sync();
        return r.ok ? Result.success() : Result.retry();
    }
}
