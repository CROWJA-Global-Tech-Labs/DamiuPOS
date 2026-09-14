package com.crowja.damiupos;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.crowja.damiupos.db.CustomerDao;
import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.model.Customer;
import com.crowja.damiupos.sync.SyncApi;
import com.crowja.damiupos.sync.SyncEngine;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * End-to-end check of the offline-first sync client against the local Laravel server.
 * Run on an emulator with the dev server up at the host (reachable as 10.0.2.2:8002):
 *   gradlew connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4.class)
public class SyncEngineInstrumentedTest {

    private static final String BASE = "http://10.0.2.2:8002";
    private static final String ENROLL_KEY = "demo-pusat-key";

    private Context ctx() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    @Test
    public void enroll_push_then_server_has_customer() throws Exception {
        Context ctx = ctx();

        SyncEngine engine = new SyncEngine(ctx);
        engine.settings().clear();

        // 1) Enroll the device against branch PUSAT.
        SyncEngine.Result en = engine.enroll(BASE, ENROLL_KEY, "emulator-itest");
        assertTrue("enroll failed: " + en.error, en.ok);
        assertEquals("PUSAT", engine.settings().getBranchCode());

        // 2) Create a customer locally (routed through the sync-aware DAO).
        String unique = "ITEST-" + System.currentTimeMillis();
        CustomerDao cdao = new CustomerDao(DatabaseHelper.getInstance(ctx));
        Customer c = new Customer();
        c.setName(unique);
        c.setPhone("0812000111");
        long localId = cdao.insert(c);
        assertTrue("local insert failed", localId > 0);

        // 3) Sync (push dirty + pull deltas).
        SyncEngine.Result r = engine.sync();
        assertTrue("sync failed: " + r.error, r.ok);
        assertTrue("expected at least 1 pushed row", r.pushed >= 1);

        // 4) Prove it reached the SERVER: pull customers from a zero cursor and find it.
        SyncApi api = new SyncApi(engine.settings());
        JSONObject body = new JSONObject();
        body.put("entities", new JSONArray().put("customers"));
        body.put("cursors", new JSONObject().put("customers", ""));
        JSONObject resp = api.pull(body);

        JSONArray custs = resp.getJSONObject("entities").getJSONArray("customers");
        boolean found = false;
        for (int i = 0; i < custs.length(); i++) {
            if (unique.equals(custs.getJSONObject(i).optString("name"))) {
                found = true;
                break;
            }
        }
        assertTrue("server should contain the pushed customer " + unique, found);
    }
}
