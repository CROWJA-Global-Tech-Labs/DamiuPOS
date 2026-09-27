package com.crowja.damiupos.adapter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.crowja.damiupos.model.ChatMessage;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** List logic behind the "💬 Chat Pesanan" bubbles (spec §10 ChatMessageAdapter). */
public class ChatMessageListTest {

    private static ChatMessage row(long rowId, String waId, String ts, String dir, String text) {
        ChatMessage m = new ChatMessage();
        m.rowId = rowId;
        m.waMessageId = waId;
        m.waTimestamp = ts;
        m.direction = dir;
        m.text = text;
        return m;
    }

    private static ChatMessage optimistic(String key, String text) {
        return ChatMessage.optimistic(key, text, null, "RAFI", "2026-09-27 08:10:00");
    }

    private static void assertUniqueIds(List<ChatMessage> list) {
        Set<Long> ids = new HashSet<>();
        for (ChatMessage m : list) assertTrue("duplicate stable id", ids.add(ChatMessageList.stableId(m)));
    }

    @Test public void optimisticBubbleIsReplacedByItsServerRowViaClientKey() {
        List<ChatMessage> cur = ChatMessageList.withAdded(
                Collections.singletonList(row(1, "IN1", "2026-09-27T01:00:00Z", "in", "pesan 2 galon")),
                optimistic("K1", "baik kak"));
        ChatMessage own = row(2, "OUT1", "2026-09-27T01:05:00Z", "out", "baik kak");
        own.clientKey = "K1";
        own.sentByStaff = "RAFI";

        List<ChatMessage> next = ChatMessageList.merge(cur, Collections.singletonList(own));

        assertNotNull(next);
        assertEquals(2, next.size());
        assertSame(own, next.get(1));
        assertFalse(next.get(1).isOptimistic());
        assertUniqueIds(next);
    }

    @Test public void bridgeEchoThenOwnRowLeavesExactlyOneBubble() {
        List<ChatMessage> cur = ChatMessageList.withAdded(new ArrayList<>(), optimistic("K1", "oke"));
        // 1) the bridge echo arrives first, no client_key yet
        ChatMessage echo = row(5, "OUT9", "2026-09-27T01:05:00Z", "out", "oke");
        cur = ChatMessageList.merge(cur, Collections.singletonList(echo));
        assertNotNull(cur);
        assertEquals(2, cur.size());
        // 2) then the send's own row (same wa id, now with the client_key)
        ChatMessage own = row(5, "OUT9", "2026-09-27T01:05:00Z", "out", "oke");
        own.clientKey = "K1";
        List<ChatMessage> next = ChatMessageList.merge(cur, Collections.singletonList(own));

        assertNotNull(next);
        assertEquals(1, next.size());
        assertEquals("K1", next.get(0).clientKey);
        assertFalse(next.get(0).isOptimistic());
        assertUniqueIds(next);
    }

    @Test public void resetKeepsFailedAndUnknownBubblesButDropsStaleServerRows() {
        ChatMessage failed = optimistic("KF", "gagal");
        failed.localStatus = ChatMessage.STATUS_FAILED;
        ChatMessage unknown = optimistic("KU", "entah");
        unknown.localStatus = ChatMessage.STATUS_UNKNOWN;
        List<ChatMessage> cur = new ArrayList<>(Arrays.asList(
                row(1, "A", "2026-09-27T01:00:00Z", "in", "a"),
                row(2, "B", "2026-09-27T01:01:00Z", "in", "b (outside the new window)"),
                failed, unknown));

        List<ChatMessage> next = ChatMessageList.replaceAll(cur,
                Collections.singletonList(row(1, "A", "2026-09-27T01:00:00Z", "in", "a")));

        assertEquals(3, next.size());
        assertEquals("A", next.get(0).waMessageId);
        assertSame(failed, next.get(1));
        assertSame(unknown, next.get(2));
        assertUniqueIds(next);
    }

    @Test public void unchangedReDeliveryIsANoOp() {
        List<ChatMessage> cur = Arrays.asList(
                row(1, "A", "2026-09-27T01:00:00Z", "in", "a"),
                row(2, "B", "2026-09-27T01:01:00Z", "out", "b"));
        List<ChatMessage> again = Arrays.asList(
                row(1, "A", "2026-09-27T01:00:00Z", "in", "a"),
                row(2, "B", "2026-09-27T01:01:00Z", "out", "b"));
        assertNull(ChatMessageList.merge(cur, again));

        ChatMessage edited = row(2, "B", "2026-09-27T01:01:00Z", "out", "b2");
        edited.edited = true;
        List<ChatMessage> next = ChatMessageList.merge(cur, Collections.singletonList(edited));
        assertNotNull(next);
        assertSame(edited, next.get(1));
    }

    @Test public void duplicateIdsNeverSurvive() {
        ChatMessage a = row(1, "A", "2026-09-27T01:00:00Z", "out", "x");
        a.clientKey = "K";
        ChatMessage b = row(2, "B", "2026-09-27T01:01:00Z", "out", "y");
        b.clientKey = "K";   // server attached the same key to a second row → falls back to its wa id
        ChatMessage c = row(3, "C", "2026-09-27T01:02:00Z", "in", "z");
        ChatMessage dupC = row(3, "C", "2026-09-27T01:02:00Z", "in", "z");   // same message twice → dropped

        List<ChatMessage> next = ChatMessageList.replaceAll(new ArrayList<>(), Arrays.asList(a, b, c, dupC));

        assertEquals(3, next.size());
        assertUniqueIds(next);
    }

    @Test public void clientKeyMatchNeverOverwritesADifferentServerMessage() {
        ChatMessage a = row(1, "A", "2026-09-27T01:00:00Z", "out", "x");
        a.clientKey = "K";
        ChatMessage b = row(2, "B", "2026-09-27T01:01:00Z", "out", "y");
        b.clientKey = "K";

        List<ChatMessage> next = ChatMessageList.merge(Collections.singletonList(a), Collections.singletonList(b));

        assertNotNull(next);
        assertEquals(2, next.size());
        assertUniqueIds(next);
    }

    @Test public void sortsByTimestampThenRowIdWithOptimisticLast() {
        ChatMessage opt = optimistic("K", "z");
        List<ChatMessage> next = ChatMessageList.replaceAll(Collections.singletonList(opt), Arrays.asList(
                row(3, "C", "2026-09-27T01:02:00Z", "in", "c"),
                row(2, "B", "2026-09-27T01:00:00Z", "in", "b"),
                row(1, "A", "2026-09-27T01:00:00Z", "in", "a")));

        assertEquals("A", next.get(0).waMessageId);
        assertEquals("B", next.get(1).waMessageId);
        assertEquals("C", next.get(2).waMessageId);
        assertSame(opt, next.get(3));
    }

    @Test public void mediaFailureIsCarriedOnlyForTheSameUrl() {
        ChatMessage old = row(1, "A", "2026-09-27T01:00:00Z", "in", null);
        old.mediaUrl = "/api/chat-media/t/A?expires=1&signature=x";
        old.localMediaFailed = true;
        ChatMessage same = row(1, "A", "2026-09-27T01:00:00Z", "in", null);
        same.mediaUrl = old.mediaUrl;
        ChatMessage fresh = row(1, "A", "2026-09-27T01:00:00Z", "in", null);
        fresh.mediaUrl = "/api/chat-media/t/A?expires=2&signature=y";

        ChatMessageList.carryLocal(old, same);
        ChatMessageList.carryLocal(old, fresh);

        assertTrue(same.localMediaFailed);
        assertFalse(fresh.localMediaFailed);
    }
}
