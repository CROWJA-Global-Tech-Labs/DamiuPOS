package com.crowja.damiupos.adapter;

import android.text.util.Linkify;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.crowja.damiupos.R;
import com.crowja.damiupos.model.ChatMessage;

import java.util.ArrayList;
import java.util.List;

/** WhatsApp-style chat bubble list for a stored complaint conversation log (badge 😠 "Komplain").
 *  Read-only -- this app never composes/sends a message from here. See ChatLogActivity. */
public class ChatMessageAdapter extends RecyclerView.Adapter<ChatMessageAdapter.ViewHolder> {

    public interface OnMediaClickListener { void onMediaClick(ChatMessage m); }

    private static final int TYPE_IN = 0;
    private static final int TYPE_OUT = 1;

    private final List<ChatMessage> messages = new ArrayList<>();
    private OnMediaClickListener onMediaClickListener;

    public void setData(List<ChatMessage> data) {
        messages.clear();
        if (data != null) messages.addAll(data);
        notifyDataSetChanged();
    }

    public void setOnMediaClickListener(OnMediaClickListener l) {
        this.onMediaClickListener = l;
    }

    @Override
    public int getItemViewType(int position) {
        return messages.get(position).isOutbound() ? TYPE_OUT : TYPE_IN;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        int layout = viewType == TYPE_OUT ? R.layout.item_chat_bubble_out : R.layout.item_chat_bubble_in;
        View v = LayoutInflater.from(parent.getContext()).inflate(layout, parent, false);
        return new ViewHolder(v, viewType);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder h, int position) {
        ChatMessage m = messages.get(position);

        if (h.tvSender != null) {
            if (m.senderName != null && !m.senderName.isEmpty()) {
                h.tvSender.setVisibility(View.VISIBLE);
                h.tvSender.setText(m.senderName);
            } else {
                h.tvSender.setVisibility(View.GONE);
            }
        }

        boolean hasMedia = m.mediaUrl != null && !m.mediaUrl.isEmpty();
        if (hasMedia && m.hasImage()) {
            h.imgMedia.setVisibility(View.VISIBLE);
            h.tvMediaLink.setVisibility(View.GONE);
            com.crowja.damiupos.util.BitmapUtils.loadIntoView(h.imgMedia, m.mediaUrl, m.waMessageId);
            h.imgMedia.setOnClickListener(v -> {
                if (onMediaClickListener != null) onMediaClickListener.onMediaClick(m);
            });
        } else if (hasMedia) {
            h.imgMedia.setVisibility(View.GONE);
            h.tvMediaLink.setVisibility(View.VISIBLE);
            h.tvMediaLink.setText("📎 Lampiran (" + safe(m.type) + ")");
            h.tvMediaLink.setOnClickListener(v -> {
                if (onMediaClickListener != null) onMediaClickListener.onMediaClick(m);
            });
        } else {
            h.imgMedia.setVisibility(View.GONE);
            h.tvMediaLink.setVisibility(View.GONE);
        }

        if (m.text != null && !m.text.isEmpty()) {
            h.tvText.setVisibility(View.VISIBLE);
            h.tvText.setText(m.text);
            Linkify.addLinks(h.tvText, Linkify.WEB_URLS);
        } else {
            h.tvText.setVisibility(View.GONE);
        }

        h.tvTime.setText(shortTime(m.waTimestamp));
    }

    @Override
    public int getItemCount() {
        return messages.size();
    }

    private static String safe(String s) { return s != null ? s : ""; }

    /** "dd/MM HH:mm" LOKAL dari stempel bentuk apa pun (lihat Ts.local untuk konversi UTC↔lokal). */
    private static String shortTime(String waTimestamp) {
        String local = com.crowja.damiupos.util.Ts.local(waTimestamp);   // "yyyy-MM-dd HH:mm:ss" atau ""
        if (local.length() < 16) return "";
        return local.substring(8, 10) + "/" + local.substring(5, 7) + " " + local.substring(11, 16);
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvSender, tvText, tvTime, tvMediaLink;
        ImageView imgMedia;

        ViewHolder(@NonNull View itemView, int viewType) {
            super(itemView);
            tvSender = itemView.findViewById(R.id.tvSender);   // null on the "out" layout, guarded above
            tvText = itemView.findViewById(R.id.tvText);
            tvTime = itemView.findViewById(R.id.tvTime);
            tvMediaLink = itemView.findViewById(R.id.tvMediaLink);
            imgMedia = itemView.findViewById(R.id.imgMedia);
        }
    }
}
