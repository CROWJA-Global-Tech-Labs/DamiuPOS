package com.crowja.damiupos.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.crowja.damiupos.R;

import java.util.ArrayList;
import java.util.List;

/**
 * RecyclerView adapter untuk memilih kontak telepon yang ingin di-import
 * sebagai pelanggan. Setiap item bisa di-check/uncheck; kontak yang sudah
 * terdaftar di database ditandai dengan badge dan secara default tidak
 * dipilih.
 */
public class ContactPickerAdapter extends RecyclerView.Adapter<ContactPickerAdapter.ViewHolder> {

    public static class ContactEntry {
        public final String name;
        public final String phone;
        public final boolean alreadyImported;
        public boolean selected;

        public ContactEntry(String name, String phone, boolean alreadyImported) {
            this.name = name;
            this.phone = phone;
            this.alreadyImported = alreadyImported;
            this.selected = !alreadyImported;
        }
    }

    public interface OnSelectionChangedListener {
        void onSelectionChanged(int selectedCount, int totalCount);
    }

    private final List<ContactEntry> all = new ArrayList<>();
    private final List<ContactEntry> visible = new ArrayList<>();
    private OnSelectionChangedListener listener;
    private String currentKeyword = "";
    private boolean hideSynced = false;

    public void setListener(OnSelectionChangedListener listener) {
        this.listener = listener;
    }

    public void setData(List<ContactEntry> entries) {
        all.clear();
        all.addAll(entries);
        applyFilter();
        notifySelectionChanged();
    }

    /** Filter visible list by name or phone substring (case-insensitive). */
    public void filter(String keyword) {
        currentKeyword = keyword == null ? "" : keyword.trim();
        applyFilter();
    }

    /** When true, kontak yang sudah terdaftar di DB akan disembunyikan. */
    public void setHideSynced(boolean hide) {
        if (this.hideSynced == hide) return;
        this.hideSynced = hide;
        applyFilter();
    }

    private void applyFilter() {
        visible.clear();
        String kw = currentKeyword.toLowerCase();
        for (ContactEntry e : all) {
            if (hideSynced && e.alreadyImported) continue;
            if (!kw.isEmpty()) {
                boolean match = (e.name != null && e.name.toLowerCase().contains(kw))
                        || (e.phone != null && e.phone.toLowerCase().contains(kw));
                if (!match) continue;
            }
            visible.add(e);
        }
        notifyDataSetChanged();
    }

    /** Set selected state for every visible entry. Only entries not yet imported are affected. */
    public void selectAllVisible(boolean selected) {
        for (ContactEntry e : visible) {
            if (!e.alreadyImported) {
                e.selected = selected;
            }
        }
        notifyDataSetChanged();
        notifySelectionChanged();
    }

    public List<ContactEntry> getSelected() {
        List<ContactEntry> out = new ArrayList<>();
        for (ContactEntry e : all) {
            if (e.selected && !e.alreadyImported) out.add(e);
        }
        return out;
    }

    public int getSelectedCount() {
        int count = 0;
        for (ContactEntry e : all) {
            if (e.selected && !e.alreadyImported) count++;
        }
        return count;
    }

    public int getTotalSelectableCount() {
        int count = 0;
        for (ContactEntry e : all) {
            if (!e.alreadyImported) count++;
        }
        return count;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_contact_picker, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        holder.bind(visible.get(position));
    }

    @Override
    public int getItemCount() {
        return visible.size();
    }

    private void notifySelectionChanged() {
        if (listener != null) {
            listener.onSelectionChanged(getSelectedCount(), getTotalSelectableCount());
        }
    }

    class ViewHolder extends RecyclerView.ViewHolder {
        final CheckBox cbSelected;
        final TextView tvInitial, tvContactName, tvContactPhone, tvAlreadyImported;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            cbSelected = itemView.findViewById(R.id.cbSelected);
            tvInitial = itemView.findViewById(R.id.tvInitial);
            tvContactName = itemView.findViewById(R.id.tvContactName);
            tvContactPhone = itemView.findViewById(R.id.tvContactPhone);
            tvAlreadyImported = itemView.findViewById(R.id.tvAlreadyImported);

            itemView.setOnClickListener(v -> {
                int pos = getAdapterPosition();
                if (pos == RecyclerView.NO_POSITION) return;
                ContactEntry e = visible.get(pos);
                if (e.alreadyImported) return;
                e.selected = !e.selected;
                notifyItemChanged(pos);
                notifySelectionChanged();
            });
        }

        void bind(ContactEntry entry) {
            cbSelected.setChecked(entry.selected && !entry.alreadyImported);
            cbSelected.setEnabled(!entry.alreadyImported);
            itemView.setAlpha(entry.alreadyImported ? 0.55f : 1f);

            String name = entry.name != null ? entry.name : "";
            tvInitial.setText(!name.isEmpty()
                    ? String.valueOf(name.charAt(0)).toUpperCase() : "?");
            tvContactName.setText(name);
            tvContactPhone.setText(entry.phone != null ? entry.phone : "-");
            tvAlreadyImported.setVisibility(entry.alreadyImported ? View.VISIBLE : View.GONE);
        }
    }
}
