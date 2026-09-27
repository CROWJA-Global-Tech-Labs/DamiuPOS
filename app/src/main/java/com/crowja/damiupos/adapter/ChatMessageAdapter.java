package com.crowja.damiupos.adapter;

import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.util.Linkify;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.crowja.damiupos.R;
import com.crowja.damiupos.model.ChatMessage;
import com.crowja.damiupos.util.BitmapUtils;

import java.util.ArrayList;
import java.util.List;

/** WhatsApp-style chat bubble list. Two users (see ChatLogActivity):
 *  <ul>
 *    <li>complaint log (badge 😠 "Komplain") -- read-only, whole list via {@link #setData};</li>
 *    <li>"💬 Chat Pesanan" -- polled incrementally ({@link #replaceAll} on a reset, {@link #merge}
 *        otherwise) plus optimistic bubbles for staff sends ({@link #addOptimistic},
 *        {@link #setLocalStatus}).</li>
 *  </ul>
 *  The list logic (matching by {@code waMessageId} / {@code clientKey}, sort by (wa_timestamp,
 *  row_id) with optimistic bubbles last, unique stable ids) lives in {@link ChatMessageList}. */
public class ChatMessageAdapter extends RecyclerView.Adapter<ChatMessageAdapter.ViewHolder> {

    public interface OnMediaClickListener { void onMediaClick(ChatMessage m); }
    /** Tap on an optimistic bubble whose send FAILED / has an UNKNOWN outcome. */
    public interface OnStatusClickListener { void onStatusClick(ChatMessage m); }
    /** An image did not load: HTTP status (403 = signed URL expired, 404 = media gone), or 0 when
     *  there was no response (timeout / IO) or the file could not be decoded. */
    public interface OnMediaErrorListener { void onMediaHttpError(ChatMessage m, int httpCode); }

    private static final int TYPE_IN = 0;
    private static final int TYPE_OUT = 1;
    private static final long MEDIA_429_RETRY_MS = 15000L;

    private final List<ChatMessage> messages = new ArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private OnMediaClickListener onMediaClickListener;
    private OnStatusClickListener onStatusClickListener;
    private OnMediaErrorListener onMediaErrorListener;

    public ChatMessageAdapter() {
        setHasStableIds(true);
    }

    public void setData(List<ChatMessage> data) {
        messages.clear();
        if (data != null) messages.addAll(data);
        ChatMessageList.dedupeIds(messages);
        notifyDataSetChanged();
    }

    public void setOnMediaClickListener(OnMediaClickListener l) {
        this.onMediaClickListener = l;
    }

    public void setOnStatusClickListener(OnStatusClickListener l) {
        this.onStatusClickListener = l;
    }

    public void setOnMediaErrorListener(OnMediaErrorListener l) {
        this.onMediaErrorListener = l;
    }

    /** Full (reset) response: the server list replaces everything, except optimistic bubbles whose
     *  clientKey is not in it yet (their send is still in flight / failed / unknown). */
    public void replaceAll(List<ChatMessage> data) {
        apply(ChatMessageList.replaceAll(messages, data));
    }

    /** Incremental response (or a send's returned message): update in place by waMessageId, else
     *  by clientKey (replacing the optimistic bubble); unknown rows are appended. A re-delivery that
     *  changes nothing is a no-op (no sort, no DiffUtil). */
    public void merge(List<ChatMessage> incoming) {
        List<ChatMessage> next = ChatMessageList.merge(messages, incoming);
        if (next != null) apply(next);
    }

    public void addOptimistic(ChatMessage m) {
        if (m == null) return;
        apply(ChatMessageList.withAdded(messages, m));
    }

    /** Update the local status line of the optimistic bubble {@code clientKey}; false if absent. */
    public boolean setLocalStatus(String clientKey, String status, String error) {
        if (clientKey == null) return false;
        for (int i = 0; i < messages.size(); i++) {
            ChatMessage m = messages.get(i);
            if (clientKey.equals(m.clientKey) && m.isOptimistic()) {
                m.localStatus = status;
                m.localError = error;
                notifyItemChanged(i);
                return true;
            }
        }
        return false;
    }

    /** Is the send {@code clientKey} still an optimistic bubble (no server row has replaced it)? */
    public boolean hasOptimistic(String clientKey) {
        if (clientKey == null) return false;
        for (ChatMessage m : messages) {
            if (clientKey.equals(m.clientKey) && m.isOptimistic()) return true;
        }
        return false;
    }

    /** Remove an optimistic bubble (it is being re-sent under a NEW client key). */
    public void removeOptimistic(String clientKey) {
        if (clientKey == null) return;
        for (int i = 0; i < messages.size(); i++) {
            ChatMessage m = messages.get(i);
            if (clientKey.equals(m.clientKey) && m.isOptimistic()) {
                messages.remove(i);
                notifyItemRemoved(i);
                return;
            }
        }
    }

    public ChatMessage getItem(int position) {
        return position >= 0 && position < messages.size() ? messages.get(position) : null;
    }

    /** {@code next} is already sorted with unique ids (ChatMessageList). */
    private void apply(List<ChatMessage> next) {
        final List<ChatMessage> old = new ArrayList<>(messages);
        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override public int getOldListSize() { return old.size(); }
            @Override public int getNewListSize() { return next.size(); }
            @Override public boolean areItemsTheSame(int o, int n) {
                return ChatMessageList.stableId(old.get(o)) == ChatMessageList.stableId(next.get(n));
            }
            @Override public boolean areContentsTheSame(int o, int n) {
                return ChatMessageList.sameContent(old.get(o), next.get(n));
            }
        });
        messages.clear();
        messages.addAll(next);
        diff.dispatchUpdatesTo(this);
    }

    private void notifyRow(ChatMessage m) {
        int idx = messages.indexOf(m);
        if (idx >= 0) notifyItemChanged(idx);
    }

    /** An image did not load: 404/410 = gone for good; anything else (403 expired signature, 429
     *  media throttle shared by the depot's Wi-Fi, 5xx, timeout = 0) = "ketuk untuk coba lagi", and a
     *  429 is retried once automatically after {@link #MEDIA_429_RETRY_MS}. */
    private void onImageError(ChatMessage m, int code) {
        if (code == 404 || code == 410) {
            m.localMediaGone = true;
        } else {
            m.localMediaFailed = true;
            if (code == 429 && m.localMediaAutoRetries < 1) {
                m.localMediaAutoRetries++;
                mainHandler.postDelayed(() -> {
                    // The row object may have been replaced by a re-delivery (flag carried over).
                    for (int i = 0; i < messages.size(); i++) {
                        ChatMessage cur = messages.get(i);
                        if (cur == m || (m.waMessageId != null && m.waMessageId.equals(cur.waMessageId)
                                && java.util.Objects.equals(m.mediaUrl, cur.mediaUrl))) {
                            if (cur.localMediaFailed) {
                                cur.localMediaFailed = false;
                                notifyItemChanged(i);
                            }
                            return;
                        }
                    }
                }, MEDIA_429_RETRY_MS);
            }
        }
        notifyRow(m);
        if (onMediaErrorListener != null) onMediaErrorListener.onMediaHttpError(m, code);
    }

    @Override
    public long getItemId(int position) {
        return ChatMessageList.stableId(messages.get(position));
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

        if (h.tvStaff != null) {
            if (m.isOutbound() && m.sentByStaff != null && !m.sentByStaff.isEmpty()) {
                h.tvStaff.setVisibility(View.VISIBLE);
                h.tvStaff.setText("✍️ " + m.sentByStaff);
            } else {
                h.tvStaff.setVisibility(View.GONE);
            }
        }

        if (h.tvQuote != null) {
            if (!m.deleted && m.quotedText != null && !m.quotedText.trim().isEmpty()) {
                h.tvQuote.setVisibility(View.VISIBLE);
                h.tvQuote.setText(m.quotedText.trim());
            } else {
                h.tvQuote.setVisibility(View.GONE);
            }
        }

        bindMedia(h, m);

        if (m.deleted) {
            h.tvText.setVisibility(View.VISIBLE);
            h.tvText.setText("🚫 Pesan dihapus");
            h.tvText.setTypeface(null, Typeface.ITALIC);
            h.tvText.setTextColor(ContextCompat.getColor(h.itemView.getContext(), R.color.text_secondary));
        } else if (m.text != null && !m.text.isEmpty()) {
            h.tvText.setVisibility(View.VISIBLE);
            h.tvText.setTypeface(null, Typeface.NORMAL);
            h.tvText.setTextColor(ContextCompat.getColor(h.itemView.getContext(), R.color.text_primary));
            h.tvText.setText(m.edited ? m.text + "  (diedit)" : m.text);
            Linkify.addLinks(h.tvText, Linkify.WEB_URLS);
        } else if (m.edited) {
            h.tvText.setVisibility(View.VISIBLE);
            h.tvText.setTypeface(null, Typeface.ITALIC);
            h.tvText.setTextColor(ContextCompat.getColor(h.itemView.getContext(), R.color.text_secondary));
            h.tvText.setText("(diedit)");
        } else {
            h.tvText.setVisibility(View.GONE);
        }

        h.tvTime.setText(shortTime(m.waTimestamp));
        bindStatus(h, m);
    }

    private void bindMedia(ViewHolder h, ChatMessage m) {
        boolean hasUrl = m.mediaUrl != null && !m.mediaUrl.isEmpty() && !m.localMediaGone;
        if (m.deleted) {
            h.imgMedia.setTag(null);
            h.imgMedia.setVisibility(View.GONE);
            h.tvMediaLink.setVisibility(View.GONE);
        } else if (hasUrl && m.hasImage() && m.localMediaFailed) {
            h.imgMedia.setTag(null);
            h.imgMedia.setVisibility(View.GONE);
            h.tvMediaLink.setVisibility(View.VISIBLE);
            h.tvMediaLink.setText("📎 Gagal memuat lampiran — ketuk untuk coba lagi");
            final ChatMessage bound = m;
            h.tvMediaLink.setOnClickListener(v -> {
                bound.localMediaFailed = false;
                notifyRow(bound);
            });
        } else if (hasUrl && m.hasImage()) {
            h.imgMedia.setVisibility(View.VISIBLE);
            h.tvMediaLink.setVisibility(View.GONE);
            String cacheKey = m.waMessageId != null ? m.waMessageId : "ck_" + m.clientKey;
            final ChatMessage bound = m;
            BitmapUtils.loadIntoView(h.imgMedia, m.mediaUrl, cacheKey, BitmapUtils.CHAT_MEDIA_DIR,
                    code -> onImageError(bound, code));
            h.imgMedia.setOnClickListener(v -> {
                if (onMediaClickListener != null) onMediaClickListener.onMediaClick(m);
            });
        } else if (m.localImagePath != null && (m.isOptimistic() || !hasUrl)) {
            // Our own just-sent image: show the local compressed copy until the server has a URL
            // (decoded off the UI thread on a cache miss).
            h.imgMedia.setVisibility(View.VISIBLE);
            h.tvMediaLink.setVisibility(View.GONE);
            BitmapUtils.loadLocalIntoView(h.imgMedia, m.localImagePath, 400, 400);
            h.imgMedia.setOnClickListener(null);
        } else if (hasUrl) {
            h.imgMedia.setTag(null);
            h.imgMedia.setVisibility(View.GONE);
            h.tvMediaLink.setVisibility(View.VISIBLE);
            h.tvMediaLink.setText("📎 Lampiran (" + safe(m.type) + ")");
            h.tvMediaLink.setOnClickListener(v -> {
                if (onMediaClickListener != null) onMediaClickListener.onMediaClick(m);
            });
        } else if (m.hasMedia || m.localMediaGone) {
            h.imgMedia.setTag(null);
            h.imgMedia.setVisibility(View.GONE);
            h.tvMediaLink.setVisibility(View.VISIBLE);
            h.tvMediaLink.setText("📎 Lampiran tidak tersedia lagi");
            h.tvMediaLink.setOnClickListener(null);
            h.tvMediaLink.setClickable(false);
        } else {
            h.imgMedia.setTag(null);
            h.imgMedia.setVisibility(View.GONE);
            h.tvMediaLink.setVisibility(View.GONE);
        }
    }

    private void bindStatus(ViewHolder h, ChatMessage m) {
        String status = m.localStatus;
        boolean tappable = ChatMessage.STATUS_FAILED.equals(status) || ChatMessage.STATUS_UNKNOWN.equals(status);
        if (h.tvStatus != null) {
            if (status == null) {
                h.tvStatus.setVisibility(View.GONE);
            } else {
                h.tvStatus.setVisibility(View.VISIBLE);
                int color;
                String label;
                if (ChatMessage.STATUS_PENDING.equals(status)) {
                    label = "⏳ menunggu";
                    color = 0xFF616161;
                } else if (ChatMessage.STATUS_FAILED.equals(status)) {
                    String err = m.localError != null && !m.localError.isEmpty() ? m.localError : "ditolak server";
                    label = "❗ gagal: " + err + " — ketuk untuk kirim ulang";
                    color = 0xFFC62828;
                } else if (ChatMessage.STATUS_UNKNOWN.equals(status)) {
                    label = "❓ status tidak diketahui — ketuk";
                    color = 0xFFB34700;
                } else {
                    label = "mengirim…";
                    color = 0xFF616161;
                }
                h.tvStatus.setText(label);
                h.tvStatus.setTextColor(color);
            }
        }
        if (tappable) {
            h.itemView.setOnClickListener(v -> {
                if (onStatusClickListener != null) onStatusClickListener.onStatusClick(m);
            });
        } else {
            h.itemView.setOnClickListener(null);
            h.itemView.setClickable(false);
        }
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
        TextView tvSender, tvStaff, tvQuote, tvText, tvTime, tvMediaLink, tvStatus;
        ImageView imgMedia;

        ViewHolder(@NonNull View itemView, int viewType) {
            super(itemView);
            tvSender = itemView.findViewById(R.id.tvSender);   // null on the "out" layout, guarded above
            tvStaff = itemView.findViewById(R.id.tvStaff);     // null on the "in" layout
            tvQuote = itemView.findViewById(R.id.tvQuote);
            tvText = itemView.findViewById(R.id.tvText);
            tvTime = itemView.findViewById(R.id.tvTime);
            tvMediaLink = itemView.findViewById(R.id.tvMediaLink);
            tvStatus = itemView.findViewById(R.id.tvStatus);   // null on the "in" layout
            imgMedia = itemView.findViewById(R.id.imgMedia);
        }
    }
}
