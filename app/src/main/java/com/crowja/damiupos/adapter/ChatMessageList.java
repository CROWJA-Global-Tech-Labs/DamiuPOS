package com.crowja.damiupos.adapter;

import com.crowja.damiupos.model.ChatMessage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Pure list logic of {@link ChatMessageAdapter} (no views, no Android) -- unit-tested on the JVM
 *  (src/test). Every method returns a NEW list: sorted by (wa_timestamp, row_id) with optimistic
 *  bubbles last, and with unique stable ids. Rows are matched by {@code waMessageId}, else by
 *  {@code clientKey} (a server row replaces the optimistic bubble of the same send). */
final class ChatMessageList {

    private ChatMessageList() {}

    /** Full (reset) response: the server list replaces everything, except optimistic bubbles whose
     *  clientKey is not in it yet (their send is still in flight / failed / unknown). */
    static List<ChatMessage> replaceAll(List<ChatMessage> current, List<ChatMessage> data) {
        List<ChatMessage> next = new ArrayList<>();
        Map<String, ChatMessage> byKey = new HashMap<>();
        Map<String, ChatMessage> byWa = new HashMap<>();
        if (data != null) {
            for (ChatMessage m : data) {
                if (m == null) continue;
                next.add(m);
                if (m.clientKey != null) byKey.put(m.clientKey, m);
                if (m.waMessageId != null) byWa.put(m.waMessageId, m);
            }
        }
        for (ChatMessage old : current) {
            ChatMessage match = old.clientKey != null ? byKey.get(old.clientKey) : null;
            if (match == null && old.waMessageId != null) match = byWa.get(old.waMessageId);
            if (match != null) {
                carryLocal(old, match);
            } else if (old.isOptimistic()) {
                next.add(old);
            }
        }
        sort(next);
        dedupeIds(next);
        return next;
    }

    /** Incremental response (or a send's returned message): update in place by waMessageId, else
     *  by clientKey (replacing the optimistic bubble); unknown rows are appended. Returns null when
     *  nothing changed -- the server re-delivers rows touched in the last 2 s on every poll, and those
     *  must not cost a full sort + DiffUtil on the UI thread. */
    static List<ChatMessage> merge(List<ChatMessage> current, List<ChatMessage> incoming) {
        if (incoming == null || incoming.isEmpty()) return null;
        List<ChatMessage> next = new ArrayList<>(current);
        Map<String, Integer> byWa = new HashMap<>();
        Map<String, Integer> byKey = new HashMap<>();
        for (int i = 0; i < next.size(); i++) {
            ChatMessage m = next.get(i);
            if (m.waMessageId != null && !byWa.containsKey(m.waMessageId)) byWa.put(m.waMessageId, i);
            if (m.clientKey != null && !byKey.containsKey(m.clientKey)) byKey.put(m.clientKey, i);
        }
        boolean changed = false;
        boolean holes = false;
        for (ChatMessage in : incoming) {
            if (in == null) continue;
            Integer iw = in.waMessageId != null ? byWa.get(in.waMessageId) : null;
            Integer ik = in.clientKey != null ? byKey.get(in.clientKey) : null;
            // A clientKey match only stands for the SAME message: never let it overwrite a server row
            // that already carries a different waMessageId.
            if (ik != null && !sameSend(next.get(ik), in)) ik = null;
            if (iw == null && ik == null) {
                next.add(in);
                int at = next.size() - 1;
                if (in.waMessageId != null) byWa.put(in.waMessageId, at);
                if (in.clientKey != null) byKey.put(in.clientKey, at);
                changed = true;
                continue;
            }
            int at = iw != null ? iw : ik;
            ChatMessage cur = next.get(at);
            carryLocal(cur, in);
            if (iw != null && ik != null && !ik.equals(iw)) {
                // The bridge echo landed first (no client_key yet) and now the send's own row
                // arrived: drop the leftover optimistic bubble of the same send.
                carryLocal(next.get(ik), in);
                next.set(ik, null);
                holes = true;
                changed = true;
            }
            if (!sameContent(cur, in)) {
                next.set(at, in);
                changed = true;
            }
            ChatMessage placed = next.get(at);
            if (placed.waMessageId != null) byWa.put(placed.waMessageId, at);
            if (placed.clientKey != null) byKey.put(placed.clientKey, at);
        }
        if (!changed) return null;
        if (holes) {
            for (Iterator<ChatMessage> it = next.iterator(); it.hasNext(); ) {
                if (it.next() == null) it.remove();
            }
        }
        sort(next);
        dedupeIds(next);
        return next;
    }

    static List<ChatMessage> withAdded(List<ChatMessage> current, ChatMessage m) {
        List<ChatMessage> next = new ArrayList<>(current);
        next.add(m);
        sort(next);
        dedupeIds(next);
        return next;
    }

    /** May {@code in} (matched by clientKey) replace {@code cur}? Only if they are the same send:
     *  cur is still optimistic, or either side has no waMessageId yet, or both ids agree. */
    private static boolean sameSend(ChatMessage cur, ChatMessage in) {
        if (cur == null) return false;
        if (cur.isOptimistic() || cur.waMessageId == null || in.waMessageId == null) return true;
        return cur.waMessageId.equals(in.waMessageId);
    }

    /** (wa_timestamp, row_id) ascending; optimistic bubbles last in insertion order (stable sort). */
    static void sort(List<ChatMessage> list) {
        Collections.sort(list, (a, b) -> {
            boolean oa = a.isOptimistic();
            boolean ob = b.isOptimistic();
            if (oa != ob) return oa ? 1 : -1;
            if (oa) return 0;
            int c = Long.compare(a.sortMillis(), b.sortMillis());
            return c != 0 ? c : Long.compare(a.rowId, b.rowId);
        });
    }

    /** Stable ids MUST be unique (RecyclerView throws on a clash during change animations). A server
     *  row sharing its clientKey with an earlier row falls back to its waMessageId id; a row that
     *  still clashes (same waMessageId = the same message twice) is dropped. */
    static void dedupeIds(List<ChatMessage> list) {
        Set<Long> seen = new HashSet<>();
        for (Iterator<ChatMessage> it = list.iterator(); it.hasNext(); ) {
            ChatMessage m = it.next();
            if (m == null) {
                it.remove();
                continue;
            }
            if (seen.add(stableId(m))) continue;
            if (m.clientKey != null && m.waMessageId != null && !m.isOptimistic()) {
                m.clientKey = null;
                if (seen.add(stableId(m))) continue;
            }
            it.remove();
        }
    }

    /** Keep the local compressed image of our own send until the server copy has a usable URL, and
     *  the per-URL media failure state while the URL is unchanged (a re-delivered row must not
     *  re-download a URL that already answered 404 / failed; a NEW signed URL starts clean). */
    static void carryLocal(ChatMessage from, ChatMessage to) {
        if (from == null || to == null || from == to) return;
        if (to.localImagePath == null) to.localImagePath = from.localImagePath;
        if (Objects.equals(from.mediaUrl, to.mediaUrl)) {
            if (from.localMediaGone) to.localMediaGone = true;
            if (from.localMediaFailed) to.localMediaFailed = true;
            to.localMediaAutoRetries = Math.max(to.localMediaAutoRetries, from.localMediaAutoRetries);
        }
    }

    static boolean sameContent(ChatMessage a, ChatMessage b) {
        if (a == b) return true;
        return a.rowId == b.rowId
                && a.hasMedia == b.hasMedia && a.deleted == b.deleted && a.edited == b.edited
                && a.localMediaGone == b.localMediaGone
                && a.localMediaFailed == b.localMediaFailed
                && Objects.equals(a.waMessageId, b.waMessageId)
                && Objects.equals(a.direction, b.direction)
                && Objects.equals(a.senderName, b.senderName)
                && Objects.equals(a.type, b.type)
                && Objects.equals(a.text, b.text)
                && Objects.equals(a.quotedText, b.quotedText)
                && Objects.equals(a.mediaUrl, b.mediaUrl)
                && Objects.equals(a.mediaMimetype, b.mediaMimetype)
                && Objects.equals(a.waTimestamp, b.waTimestamp)
                && Objects.equals(a.sentByStaff, b.sentByStaff)
                && Objects.equals(a.clientKey, b.clientKey)
                && Objects.equals(a.localStatus, b.localStatus)
                && Objects.equals(a.localError, b.localError)
                && Objects.equals(a.localImagePath, b.localImagePath);
    }

    /** Stable id from clientKey (survives optimistic → server row), else waMessageId. */
    static long stableId(ChatMessage m) {
        String key = m.clientKey != null ? "k:" + m.clientKey
                : m.waMessageId != null ? "w:" + m.waMessageId
                : null;
        if (key == null) return System.identityHashCode(m);
        long h = 0xcbf29ce484222325L;   // FNV-1a 64-bit
        for (int i = 0; i < key.length(); i++) {
            h ^= key.charAt(i);
            h *= 0x100000001b3L;
        }
        return h;
    }
}
