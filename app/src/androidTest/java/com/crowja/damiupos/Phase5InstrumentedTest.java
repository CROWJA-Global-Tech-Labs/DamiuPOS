package com.crowja.damiupos;

import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.crowja.damiupos.sync.LocationReporter;
import com.crowja.damiupos.sync.SyncEngine;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.UUID;

@RunWith(AndroidJUnit4.class)
public class Phase5InstrumentedTest {

    private static final String BASE = "http://10.0.2.2:8002";
    private static final String ENROLL_KEY = "demo-pusat-key";

    private Context ctx() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    /** A location fix posted by the device reaches the server (POST /api/location/ping). */
    @Test
    public void location_ping_reaches_server() {
        Context ctx = ctx();
        SyncEngine engine = new SyncEngine(ctx);
        engine.settings().clear();

        SyncEngine.Result en = engine.enroll(BASE, ENROLL_KEY, "p5-itest");
        assertTrue("enroll: " + en.error, en.ok);

        String staffUuid = UUID.randomUUID().toString();
        boolean ok = LocationReporter.report(ctx, staffUuid,
                -7.5505, 110.8310, 8.0f, 1.2f, 90.0f, 77);
        assertTrue("server should accept the location ping", ok);
    }
}
