package com.crowja.damiupos.wa;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.crowja.damiupos.BuildConfig;
import com.crowja.damiupos.model.OrderInbox;

/**
 * Test helper: terima broadcast dengan extras (sender, msg) lalu inject
 * ke {@link OrderParseService} seolah-olah datang dari WhatsApp.
 *
 * <p>Hanya aktif di debug build sebagai safeguard supaya app rilis tidak
 * bisa di-trigger dari adb oleh siapa pun.
 *
 * <p>Pakai dari adb:
 * <pre>
 * adb shell am broadcast -a com.crowja.damiupos.TEST_PARSE \
 *     -n com.crowja.damiupos/.wa.WaTestInjectionReceiver \
 *     --es sender "Pak Budi" \
 *     --es msg "pesan mineral 5"
 * </pre>
 *
 * Lalu lihat hasil di logcat:
 * <pre>
 * adb logcat -d -s WaTestInjection:D OrderParseService:D ClaudeClient:W
 * </pre>
 */
public class WaTestInjectionReceiver extends BroadcastReceiver {

    public static final String ACTION = "com.crowja.damiupos.TEST_PARSE";
    private static final String TAG = "WaTestInjection";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) return;
        if (!BuildConfig.DEBUG) {
            Log.w(TAG, "Refused: not a debug build");
            return;
        }
        String sender = intent.getStringExtra("sender");
        String phone = intent.getStringExtra("phone");
        String msg = intent.getStringExtra("msg");
        if (sender == null || sender.isEmpty()) sender = "Test User";
        if (phone == null) phone = "";
        if (msg == null || msg.trim().isEmpty()) {
            Log.w(TAG, "Refused: empty msg");
            return;
        }
        Log.i(TAG, "Injecting: from='" + sender + "' phone='" + phone
                + "' msg='" + msg + "'");
        Context appCtx = ctx.getApplicationContext();
        OrderParseService.handleIncoming(appCtx,
                sender, phone, msg, new OrderParseService.Listener() {
                    @Override public void onOrderStored(OrderInbox stored) {
                        Log.i(TAG, "Stored: id=" + stored.getId()
                                + " parser=" + stored.getParserUsed()
                                + " json=" + stored.getParsedJson());
                        // Test inject juga harus trigger sound loop service
                        OrderAlertService.start(appCtx);
                    }
                    @Override public void onError(String m) {
                        Log.e(TAG, "Parser error: " + m);
                    }
                });
    }
}
