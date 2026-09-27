package com.crowja.damiupos.model;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * One WhatsApp message shown by ChatLogActivity -- either a stored complaint conversation log
 * (badge 😠 "Komplain" on an order, SyncApi.complaintLog) or the "💬 Chat Pesanan" slice of an AI
 * agent order (SyncApi.orderChat). Fetched on-demand, never synced as a DB column (see
 * DatabaseHelper.COL_COMPLAINED_AT's comment for why).
 *
 * <p>The order-chat JSON is a SUPERSET of the complaint JSON: every extra field below is read with
 * a default, so complaint logs keep parsing unchanged. The {@code local*} fields never come from the
 * server -- they belong to an optimistic bubble this device is still sending.</p>
 */
public class ChatMessage {
    /** Optimistic bubble: request in flight. */
    public static final String STATUS_SENDING = "SENDING";
    /** Server answered 202 / no response -- retrying with the SAME client_key. */
    public static final String STATUS_PENDING = "PENDING";
    /** Rejected for good (422/403/429) -- a retry needs a NEW client_key. */
    public static final String STATUS_FAILED = "FAILED";
    /** Outcome unknown (409 send_unknown / retries exhausted) -- may or may not have been sent. */
    public static final String STATUS_UNKNOWN = "UNKNOWN";

    public long rowId;
    public String waMessageId;
    public String direction;   // "in" | "out"
    public String waAccount;
    public String senderName;
    public String type;        // text|image|video|audio|document|sticker|location
    public String text;
    public String quotedText;
    public boolean hasMedia;
    public String mediaUrl;
    public String mediaMimetype;
    public String waTimestamp;
    public boolean deleted;
    public boolean edited;
    /** Display name of the staff member who sent this from the app/dashboard (outbound only). */
    public String sentByStaff;
    /** Idempotency key of the staff send -- lets an optimistic bubble be replaced by the server row. */
    public String clientKey;

    /** Local only: null (server row) | SENDING | PENDING | FAILED | UNKNOWN. */
    public String localStatus;
    /** Local only: human-readable reason for FAILED/UNKNOWN. */
    public String localError;
    /** Local only: compressed image this device is sending (shown until the server copy has a URL). */
    public String localImagePath;
    /** Local only: the media URL answered 404 (bridge evicted it) -- show "tidak tersedia lagi". */
    public boolean localMediaGone;

    public boolean isOutbound() {
        return "out".equals(direction);
    }

    public boolean hasImage() {
        return mediaUrl != null && mediaMimetype != null && mediaMimetype.startsWith("image/");
    }

    /** Still an optimistic bubble (not yet confirmed by a server row). */
    public boolean isOptimistic() {
        return localStatus != null;
    }

    public static ChatMessage fromJson(JSONObject o) {
        ChatMessage m = new ChatMessage();
        m.rowId = o.optLong("row_id", 0L);
        m.waMessageId = o.isNull("wa_message_id") ? null : o.optString("wa_message_id", null);
        m.direction = o.optString("direction", null);
        m.waAccount = o.optString("wa_account", null);
        m.senderName = o.isNull("sender_name") ? null : o.optString("sender_name", null);
        m.type = o.optString("type", null);
        m.text = o.isNull("text") ? null : o.optString("text", null);
        m.quotedText = o.isNull("quoted_text") ? null : o.optString("quoted_text", null);
        m.mediaUrl = o.isNull("media_url") ? null : o.optString("media_url", null);
        m.mediaMimetype = o.isNull("media_mimetype") ? null : o.optString("media_mimetype", null);
        m.hasMedia = o.optBoolean("has_media", m.mediaUrl != null && !m.mediaUrl.isEmpty());
        m.waTimestamp = o.isNull("wa_timestamp") ? null : o.optString("wa_timestamp", null);
        m.deleted = o.optBoolean("deleted", false);
        m.edited = o.optBoolean("edited", false);
        m.sentByStaff = o.isNull("sent_by_staff") ? null : o.optString("sent_by_staff", null);
        m.clientKey = o.isNull("client_key") ? null : o.optString("client_key", null);
        if (m.clientKey != null && m.clientKey.isEmpty()) m.clientKey = null;
        if (m.waMessageId != null && m.waMessageId.isEmpty()) m.waMessageId = null;
        return m;
    }

    public static List<ChatMessage> listFromJson(JSONArray arr) {
        List<ChatMessage> list = new ArrayList<>();
        if (arr == null) return list;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null) list.add(fromJson(o));
        }
        return list;
    }

    /** Optimistic outbound bubble for a staff send that has not been confirmed yet. */
    public static ChatMessage optimistic(String clientKey, String text, String localImagePath,
                                         String staffName, String localNow) {
        ChatMessage m = new ChatMessage();
        m.clientKey = clientKey;
        m.direction = "out";
        m.type = localImagePath != null ? "image" : "text";
        m.text = text;
        m.hasMedia = localImagePath != null;
        m.mediaMimetype = localImagePath != null ? "image/jpeg" : null;
        m.localImagePath = localImagePath;
        m.sentByStaff = staffName;
        m.waTimestamp = localNow;
        m.localStatus = STATUS_SENDING;
        return m;
    }
}
