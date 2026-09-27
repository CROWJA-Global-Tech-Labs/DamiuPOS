package com.crowja.damiupos.sync;

import static com.crowja.damiupos.sync.OrderChatSendPolicy.Outcome.FAILED;
import static com.crowja.damiupos.sync.OrderChatSendPolicy.Outcome.RETRY;
import static com.crowja.damiupos.sync.OrderChatSendPolicy.Outcome.RETRY_SLOW;
import static com.crowja.damiupos.sync.OrderChatSendPolicy.Outcome.SENT;
import static com.crowja.damiupos.sync.OrderChatSendPolicy.Outcome.UNKNOWN;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Status transitions of a "💬 Chat Pesanan" staff send (spec §6.3 / §10). */
public class OrderChatSendPolicyTest {

    private static OrderChatSendPolicy.Outcome first(int status, String code, boolean newKey) {
        return OrderChatSendPolicy.decide(status, false, false, false, code, newKey, true);
    }

    private static OrderChatSendPolicy.Outcome retry(int status, String code, boolean newKey) {
        return OrderChatSendPolicy.decide(status, false, false, false, code, newKey, false);
    }

    @Test public void okWithMessageIsSent() {
        assertEquals(SENT, OrderChatSendPolicy.decide(200, true, false, true, null, false, true));
        assertEquals(SENT, OrderChatSendPolicy.decide(200, true, false, true, null, false, false));
    }

    @Test public void pending202RetriesWithSameKey() {
        assertEquals(RETRY, OrderChatSendPolicy.decide(202, true, true, false, null, false, true));
    }

    @Test public void incomplete2xxIsNeverTreatedAsSent() {
        // empty body / {} / ok:false / ok:true without message
        assertEquals(RETRY, OrderChatSendPolicy.decide(200, false, false, false, "bad_response", false, true));
        assertEquals(RETRY, OrderChatSendPolicy.decide(200, true, false, false, null, false, true));
        assertEquals(RETRY, OrderChatSendPolicy.decide(200, false, false, true, "bad_response", false, false));
    }

    @Test public void noResponseAnd5xxRetry() {
        assertEquals(RETRY, first(0, "unreachable", false));
        assertEquals(RETRY, first(500, "http_500", false));
        assertEquals(RETRY, retry(503, "http_503", false));
    }

    @Test public void sendUnknownIsUnknown() {
        assertEquals(UNKNOWN, first(409, "send_unknown", false));
        assertEquals(UNKNOWN, retry(409, "send_unknown", false));
    }

    @Test public void firstAttemptRejectionsAreFinal() {
        assertEquals(FAILED, first(422, "account_offline", false));
        assertEquals(FAILED, first(403, "marketing_readonly", false));
        assertEquals(FAILED, first(429, "rate_limited", false));
        assertEquals(FAILED, first(409, "idempotency_key_reused", false));
    }

    @Test public void newKeyRequiredIsFinalEvenOnRetry() {
        assertEquals(FAILED, retry(422, "send_failed", true));
        assertEquals(FAILED, retry(422, "quota_exhausted", true));
    }

    @Test public void rateLimitOnRetryKeepsSameKey() {
        // The earlier attempt may already have reached the bridge (202): never burn the key.
        assertEquals(RETRY_SLOW, retry(429, "rate_limited", false));
    }

    @Test public void otherRejectionOnRetryIsUnknownNotFailed() {
        assertEquals(UNKNOWN, retry(422, "account_offline", false));
        assertEquals(UNKNOWN, retry(403, "role_readonly", false));
        assertEquals(UNKNOWN, retry(404, "not_found", false));
    }

    @Test public void retryBackoffStartsAt5sAndCapsAt30s() {
        long[] expect = {5000, 10000, 20000, 30000, 30000, 30000};
        for (int i = 0; i < expect.length; i++) {
            assertEquals("retry " + (i + 1), expect[i], OrderChatSendPolicy.retryDelayMs(i + 1));
        }
        assertEquals(5000, OrderChatSendPolicy.retryDelayMs(0));
    }

    @Test public void rateLimitDelayHonoursRetryAfterWithinBounds() {
        assertEquals(20000, OrderChatSendPolicy.rateLimitDelayMs(0));
        assertEquals(45000, OrderChatSendPolicy.rateLimitDelayMs(45000));
        assertEquals(60000, OrderChatSendPolicy.rateLimitDelayMs(600000));
    }
}
