package com.crowja.damiupos.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.crowja.damiupos.model.ChatMessage;
import com.crowja.damiupos.util.Ts;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Text, unread count and per-order memory of the "Chat Pesanan" customer-reply notification (phase 2 §C1). */
public class OrderChatNotifierTest {

    @Test public void titleUsesCustomerNameOrFallback() {
        assertEquals("💬 Bu Sari", OrderChatNotifier.title("  Bu Sari "));
        assertEquals("💬 Pelanggan", OrderChatNotifier.title(""));
        assertEquals("💬 Pelanggan", OrderChatNotifier.title(null));
    }

    @Test public void singleMessageShowsPreviewOnly() {
        assertEquals("Galonnya sudah sampai?", OrderChatNotifier.body("Galonnya sudah sampai?", 1));
        assertEquals("Galonnya sudah sampai?", OrderChatNotifier.body("Galonnya sudah sampai?", 0));
    }

    @Test public void moreMessagesAppendPlusN() {
        assertEquals("Oke kak (+2 pesan)", OrderChatNotifier.body("Oke kak", 3));
    }

    @Test public void emptyPreviewFallsBack() {
        assertEquals("Pesan baru dari pelanggan", OrderChatNotifier.body("  ", 1));
        assertEquals("Pesan baru dari pelanggan (+1 pesan)", OrderChatNotifier.body(null, 2));
    }

    @Test public void countAccumulatesWhileNotificationIsUnread() {
        assertEquals(1, OrderChatNotifier.addCount(0, 1));
        assertEquals(5, OrderChatNotifier.addCount(2, 3));
        // missing / bogus count from the server still counts as one new message
        assertEquals(3, OrderChatNotifier.addCount(2, 0));
        assertEquals(1, OrderChatNotifier.addCount(-4, -1));
        assertEquals(999, OrderChatNotifier.addCount(998, 50));
    }

    @Test public void justPostedCountIsUsedUntilTheSystemListCatchesUp() {
        long lag = OrderChatNotifier.ACTIVE_LIST_LAG_MS;
        // posted 50 ms ago, getActiveNotifications() does not list it yet
        assertEquals(3, OrderChatNotifier.unreadBase(3, 10_000L, 10_050L, 0));
        assertEquals(3, OrderChatNotifier.unreadBase(3, 10_000L, 10_050L, 3));
        // later the system list is the truth (swiped / tapped → 0)
        assertEquals(0, OrderChatNotifier.unreadBase(3, 10_000L, 10_000L + lag, 0));
        assertEquals(3, OrderChatNotifier.unreadBase(3, 10_000L, 10_000L + lag, 3));
        // nothing remembered (e.g. after process death) → extras of the active notification
        assertEquals(2, OrderChatNotifier.unreadBase(0, 0L, 5_000L, 2));
        // clock went backwards / bogus → system list
        assertEquals(1, OrderChatNotifier.unreadBase(3, 10_000L, 9_000L, 1));
    }

    @Test public void messagesUpToTheHandledTimestampAreNotNotifiedAgain() {
        assertTrue(OrderChatNotifier.alreadyHandled(1_000L, 1_000L));   // duplicate command
        assertTrue(OrderChatNotifier.alreadyHandled(900L, 1_000L));     // already shown in the chat
        assertFalse(OrderChatNotifier.alreadyHandled(1_001L, 1_000L));  // newer message
        assertFalse(OrderChatNotifier.alreadyHandled(0L, 1_000L));      // no `at` → always notify
        assertFalse(OrderChatNotifier.alreadyHandled(1_000L, 0L));      // nothing handled yet
    }

    @Test public void newestInboundIgnoresOutboundOptimisticAndUndatedRows() {
        ChatMessage in1 = msg("in", "2026-09-27T01:02:03Z");
        ChatMessage in2 = msg("in", "2026-09-27T01:05:00.000000Z");
        ChatMessage out = msg("out", "2026-09-27T02:00:00Z");
        ChatMessage undated = msg("in", null);
        ChatMessage optimistic = msg("in", "2026-09-27T03:00:00Z");
        optimistic.localStatus = ChatMessage.STATUS_SENDING;
        long expected = Ts.millis("2026-09-27T01:05:00Z");
        assertEquals(expected, OrderChatNotifier.newestInboundMillis(
                Arrays.asList(in1, out, undated, in2, optimistic, null)));
        assertEquals(0L, OrderChatNotifier.newestInboundMillis(Arrays.asList(out, undated)));
        assertEquals(0L, OrderChatNotifier.newestInboundMillis(null));
    }

    @Test public void debouncedCountSubtractsMessagesAlreadySeenInTheChat() {
        // M1 notified (t1). M2 held by the 60 s gate, then seen in the chat. M3 flushed with count=2.
        long t1 = 1_000L, t2 = 2_000L, t3 = 3_000L;
        assertEquals(1, OrderChatNotifier.unseenCount(2, t1, t3, Arrays.asList(t1, t2)));
        // nothing seen in (t1, t3] → the full delta
        assertEquals(2, OrderChatNotifier.unseenCount(2, t1, t3, Collections.singletonList(t1)));
        // the newest message is never seen here (else alreadyHandled) → at least 1
        assertEquals(1, OrderChatNotifier.unseenCount(1, t1, t3, Arrays.asList(t2, 2_500L)));
        // lower bound unknown (first command, process restarted) → no subtraction: older seen rows
        // (the ordering conversation) are not part of the server's count
        assertEquals(3, OrderChatNotifier.unseenCount(3, 0L, t3, Arrays.asList(t1, t2)));
        assertEquals(2, OrderChatNotifier.unseenCount(2, t1, t3, null));
        assertEquals(1, OrderChatNotifier.unseenCount(0, t1, t3, Collections.emptyList()));
    }

    @Test public void onlySeenRowsAdvanceHandledAndResolveThePendingReply() {
        OrderChatNotifier.TrxState st = new OrderChatNotifier.TrxState();
        OrderChatNotifier.addPending(st, reply(1, "2026-09-27T01:05:00Z"), 0L);
        ChatMessage in1 = msg("in", "2026-09-27T01:02:03Z");
        in1.waMessageId = "A";
        ChatMessage out = msg("out", "2026-09-27T01:10:00Z");
        assertTrue(OrderChatNotifier.applySeen(st, Arrays.asList(in1, out)));
        assertEquals(Ts.millis("2026-09-27T01:02:03Z"), st.handledUpTo);
        assertTrue(st.pending != null);   // reply at 01:05 not visible yet
        // the same rows again (full reload) → nothing new
        assertFalse(OrderChatNotifier.applySeen(st, Collections.singletonList(in1)));
        assertEquals(1, st.seen.size());

        ChatMessage in2 = msg("in", "2026-09-27T01:05:00Z");
        in2.waMessageId = "B";
        ChatMessage gone = msg("in", "2026-09-27T01:04:00Z");
        gone.waMessageId = "C";
        gone.deleted = true;   // deleted rows are not counted by the server
        assertTrue(OrderChatNotifier.applySeen(st, Arrays.asList(gone, in2)));
        assertEquals(null, st.pending);
        assertEquals(2, st.seen.size());
    }

    @Test public void pendingReplyWithoutTimestampResolvesOnTheNextVisiblePoll() {
        OrderChatNotifier.TrxState st = new OrderChatNotifier.TrxState();
        OrderChatNotifier.addPending(st, new OrderChatNotifier.Reply("trx", null, null, null, 1, 0L), 0L);
        assertFalse(OrderChatNotifier.applySeen(st, Collections.emptyList()));
        assertEquals(null, st.pending);
    }

    @Test public void pendingRepliesMergeCountsAndKeepTheOldestLowerBound() {
        OrderChatNotifier.TrxState st = new OrderChatNotifier.TrxState();
        OrderChatNotifier.addPending(st, new OrderChatNotifier.Reply("trx", "Sari", "CS1", "halo", 2, 5_000L), 1_000L);
        OrderChatNotifier.addPending(st, new OrderChatNotifier.Reply("trx", null, null, "oke", 1, 6_000L), 5_000L);
        assertEquals(3, st.pending.count);
        assertEquals(6_000L, st.pending.at);
        assertEquals("oke", st.pending.preview);
        assertEquals("Sari", st.pending.name);
        assertEquals("CS1", st.pending.acct);
        assertEquals(1_000L, st.pendingLower);
    }

    @Test public void seenRowsAreCapped() {
        OrderChatNotifier.TrxState st = new OrderChatNotifier.TrxState();
        List<ChatMessage> rows = new ArrayList<>();
        for (int i = 0; i < OrderChatNotifier.SEEN_CAP + 20; i++) {
            ChatMessage m = msg("in", String.format(Locale.US, "2026-09-27T02:%02d:%02dZ", i / 60, i % 60));
            m.waMessageId = "m" + i;
            rows.add(m);
        }
        OrderChatNotifier.applySeen(st, rows);
        assertEquals(OrderChatNotifier.SEEN_CAP, st.seen.size());
        assertFalse(st.seen.containsKey("w:m0"));   // oldest dropped
        assertTrue(st.seen.containsKey("w:m" + (OrderChatNotifier.SEEN_CAP + 19)));
    }

    @Test public void pruneDropsIdleTrxAndExpiredUnreadMemory() {
        long now = 10 * OrderChatNotifier.STATE_IDLE_MS;
        Map<String, OrderChatNotifier.TrxState> m = new LinkedHashMap<>();
        OrderChatNotifier.TrxState idle = new OrderChatNotifier.TrxState();
        idle.touchedAt = now - OrderChatNotifier.STATE_IDLE_MS;
        idle.handledUpTo = 123L;
        OrderChatNotifier.TrxState waiting = new OrderChatNotifier.TrxState();
        waiting.touchedAt = idle.touchedAt;
        OrderChatNotifier.addPending(waiting, reply(1, "2026-09-27T01:05:00Z"), 0L);
        OrderChatNotifier.TrxState swiped = new OrderChatNotifier.TrxState();
        swiped.touchedAt = now - 1_000L;
        swiped.unreadCount = 4;
        swiped.unreadPostedAt = now - OrderChatNotifier.ACTIVE_LIST_LAG_MS;
        OrderChatNotifier.TrxState fresh = new OrderChatNotifier.TrxState();
        fresh.touchedAt = now;
        fresh.unreadCount = 2;
        fresh.unreadPostedAt = now - 50L;
        m.put("idle", idle);
        m.put("waiting", waiting);
        m.put("swiped", swiped);
        m.put("fresh", fresh);

        OrderChatNotifier.prune(m, now);

        assertFalse(m.containsKey("idle"));
        assertTrue(m.containsKey("waiting"));   // a reply still waiting to be shown is kept
        assertEquals(0, m.get("swiped").unreadCount);
        assertEquals(0L, m.get("swiped").unreadPostedAt);
        assertEquals(2, m.get("fresh").unreadCount);
    }

    private static OrderChatNotifier.Reply reply(int count, String at) {
        return new OrderChatNotifier.Reply("trx", "Bu Sari", null, "halo", count, Ts.millis(at));
    }

    private static ChatMessage msg(String direction, String ts) {
        ChatMessage m = new ChatMessage();
        m.direction = direction;
        m.waTimestamp = ts;
        return m;
    }
}
