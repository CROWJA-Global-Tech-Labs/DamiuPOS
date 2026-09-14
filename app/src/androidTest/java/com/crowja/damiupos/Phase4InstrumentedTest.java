package com.crowja.damiupos;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.crowja.damiupos.sync.MqttManager;
import com.crowja.damiupos.sync.SyncApi;
import com.crowja.damiupos.sync.SyncEngine;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

@RunWith(AndroidJUnit4.class)
public class Phase4InstrumentedTest {

    private static final String BASE = "http://10.0.2.2:8002";
    private static final String ENROLL_KEY = "demo-pusat-key";
    private static final String PUBLIC_BROKER = "tcp://broker.hivemq.com:1883";

    private Context ctx() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    /** Publish a newer version on the server → the app's REST check detects + stores it. */
    @Test
    public void version_publish_is_detected_by_the_app() throws Exception {
        Context ctx = ctx();
        SyncEngine engine = new SyncEngine(ctx);
        engine.settings().clear();

        SyncEngine.Result en = engine.enroll(BASE, ENROLL_KEY, "p4-itest");
        assertTrue("enroll: " + en.error, en.ok);

        int newCode = BuildConfig.VERSION_CODE + 50;
        OkHttpClient http = new OkHttpClient();
        JSONObject pub = new JSONObject()
                .put("version_code", newCode).put("version_name", "9.9.9")
                .put("changelog", "Uji pembaruan");
        Request req = new Request.Builder()
                .url(BASE + "/api/version/publish")
                .header("Authorization", "Bearer " + engine.settings().getToken())
                .header("Accept", "application/json")
                .post(RequestBody.create(pub.toString(), MediaType.parse("application/json")))
                .build();
        try (Response r = http.newCall(req).execute()) {
            assertTrue("publish HTTP " + r.code(), r.isSuccessful());
        }

        JSONObject manifest = new SyncApi(engine.settings()).version(BASE);
        assertTrue(manifest.optBoolean("available"));
        assertEquals(newCode, manifest.optInt("version_code"));

        MqttManager.handleVersion(ctx, engine.settings(), manifest);
        assertEquals(newCode, engine.settings().getLatestVersionCode());
        assertTrue("latest must exceed installed build",
                engine.settings().getLatestVersionCode() > BuildConfig.VERSION_CODE);
    }

    /** Prove the MQTT client works on-device: connect, subscribe, publish, receive. */
    @Test
    public void mqtt_roundtrip_over_public_broker() throws Exception {
        String topic = "damiupos/itest/" + UUID.randomUUID();
        String rand = UUID.randomUUID().toString().substring(0, 8);

        final String[] received = {null};
        CountDownLatch latch = new CountDownLatch(1);

        MqttClient sub = new MqttClient(PUBLIC_BROKER, "damiu-sub-" + rand, new MemoryPersistence());
        sub.setCallback(new MqttCallback() {
            @Override public void connectionLost(Throwable cause) {}
            @Override public void messageArrived(String t, MqttMessage m) {
                received[0] = new String(m.getPayload());
                latch.countDown();
            }
            @Override public void deliveryComplete(IMqttDeliveryToken token) {}
        });
        sub.connect();
        sub.subscribe(topic, 1);

        MqttClient pub = new MqttClient(PUBLIC_BROKER, "damiu-pub-" + rand, new MemoryPersistence());
        pub.connect();
        pub.publish(topic, new MqttMessage("halo-staff".getBytes()));

        assertTrue("no MQTT message within 12s", latch.await(12, TimeUnit.SECONDS));
        assertEquals("halo-staff", received[0]);

        sub.disconnect();
        pub.disconnect();
    }
}
