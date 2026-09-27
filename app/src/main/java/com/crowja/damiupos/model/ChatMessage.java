package com.crowja.damiupos.model;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * One WhatsApp message in a stored complaint conversation log (badge 😠 "Komplain" on an order) --
 * fetched on-demand via SyncApi.complaintLog(), never synced as a DB column (see
 * DatabaseHelper.COL_COMPLAINED_AT's comment for why). Read-only mirror of the server's
 * transaction_complaint_messages row; this app never writes one.
 */
public class ChatMessage {
    public String waMessageId;
    public String direction;   // "in" | "out"
    public String waAccount;
    public String senderName;
    public String type;        // text|image|video|audio|document|sticker|location
    public String text;
    public String mediaUrl;
    public String mediaMimetype;
    public String waTimestamp;

    public boolean isOutbound() {
        return "out".equals(direction);
    }

    public boolean hasImage() {
        return mediaUrl != null && mediaMimetype != null && mediaMimetype.startsWith("image/");
    }

    public static ChatMessage fromJson(JSONObject o) {
        ChatMessage m = new ChatMessage();
        m.waMessageId = o.optString("wa_message_id", null);
        m.direction = o.optString("direction", null);
        m.waAccount = o.optString("wa_account", null);
        m.senderName = o.optString("sender_name", null);
        m.type = o.optString("type", null);
        m.text = o.isNull("text") ? null : o.optString("text", null);
        m.mediaUrl = o.isNull("media_url") ? null : o.optString("media_url", null);
        m.mediaMimetype = o.isNull("media_mimetype") ? null : o.optString("media_mimetype", null);
        m.waTimestamp = o.isNull("wa_timestamp") ? null : o.optString("wa_timestamp", null);
        return m;
    }

    public static List<ChatMessage> listFromJson(JSONArray arr) {
        List<ChatMessage> list = new ArrayList<>();
        if (arr == null) return list;
        for (int i = 0; i < arr.length(); i++) {
            list.add(fromJson(arr.optJSONObject(i)));
        }
        return list;
    }
}
