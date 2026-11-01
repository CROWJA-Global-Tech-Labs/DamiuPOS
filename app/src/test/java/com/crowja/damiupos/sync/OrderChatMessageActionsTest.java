package com.crowja.damiupos.sync;

import static com.crowja.damiupos.sync.OrderChatMessageActions.Action.DELETE;
import static com.crowja.damiupos.sync.OrderChatMessageActions.Action.EDIT;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

/** Which long-press actions a "💬 Chat Pesanan" bubble offers, and the edit/delete dialog rules. */
public class OrderChatMessageActionsTest {

    private static final long NOW = 1_000_000L;
    private static final long NONE = Long.MAX_VALUE;

    @Test public void serverFlagsDecideForAConfirmedOutgoingMessage() {
        assertEquals(Arrays.asList(EDIT, DELETE),
                OrderChatMessageActions.offer(true, false, false, "W1", true, NOW + 1, true, NOW + 1, NOW));
        assertEquals(Collections.singletonList(DELETE),
                OrderChatMessageActions.offer(true, false, false, "W1", false, NONE, true, NONE, NOW));
        assertEquals(Collections.emptyList(),
                OrderChatMessageActions.offer(true, false, false, "W1", false, NONE, false, NONE, NOW));
    }

    @Test public void staleFlagsExpireOnTheClientClock() {
        assertEquals(Collections.singletonList(DELETE),
                OrderChatMessageActions.offer(true, false, false, "W1", true, NOW, true, NOW + 60_000L, NOW));
        assertEquals(Collections.emptyList(),
                OrderChatMessageActions.offer(true, false, false, "W1", true, NOW - 1, true, NOW - 1, NOW));
    }

    @Test public void inboundOptimisticDeletedOrUnconfirmedOfferNothing() {
        assertEquals(Collections.emptyList(),
                OrderChatMessageActions.offer(false, false, false, "W1", true, NONE, true, NONE, NOW));
        assertEquals(Collections.emptyList(),
                OrderChatMessageActions.offer(true, true, false, "W1", true, NONE, true, NONE, NOW));
        assertEquals(Collections.emptyList(),
                OrderChatMessageActions.offer(true, false, true, "W1", true, NONE, true, NONE, NOW));
        assertEquals(Collections.emptyList(),
                OrderChatMessageActions.offer(true, false, false, null, true, NONE, true, NONE, NOW));
        assertEquals(Collections.emptyList(),
                OrderChatMessageActions.offer(true, false, false, "", true, NONE, true, NONE, NOW));
    }

    @Test public void saveNeedsNonEmptyChangedTextWithinTheLimit() {
        assertFalse(OrderChatMessageActions.canSave("halo", "halo"));
        assertFalse(OrderChatMessageActions.canSave("halo", "  halo  "));
        assertFalse(OrderChatMessageActions.canSave("halo", "   "));
        assertFalse(OrderChatMessageActions.canSave("halo", null));
        assertTrue(OrderChatMessageActions.canSave("halo", "halo kak"));
        assertTrue(OrderChatMessageActions.canSave(null, "baru"));
        char[] big = new char[OrderChatMessageActions.TEXT_MAX + 1];
        Arrays.fill(big, 'a');
        assertFalse(OrderChatMessageActions.canSave("halo", new String(big)));
    }

    @Test public void deleteNeedsASecondTapInsideTheWindow() {
        assertFalse(OrderChatMessageActions.deleteConfirmed(0L, NOW));
        assertTrue(OrderChatMessageActions.deleteConfirmed(NOW - 500L, NOW));
        assertTrue(OrderChatMessageActions.deleteConfirmed(NOW - OrderChatMessageActions.DELETE_ARM_WINDOW_MS, NOW));
        assertFalse(OrderChatMessageActions.deleteConfirmed(NOW - OrderChatMessageActions.DELETE_ARM_WINDOW_MS - 1, NOW));
        assertFalse(OrderChatMessageActions.deleteConfirmed(NOW + 10L, NOW));
    }
}
