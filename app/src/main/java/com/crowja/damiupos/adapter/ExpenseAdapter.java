package com.crowja.damiupos.adapter;

import android.graphics.BitmapFactory;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.crowja.damiupos.R;
import com.crowja.damiupos.model.Expense;

import java.io.File;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class ExpenseAdapter extends RecyclerView.Adapter<ExpenseAdapter.VH> {

    public interface OnExpenseClickListener {
        void onExpenseClick(Expense expense);
    }

    private List<Expense> items = new ArrayList<>();
    private final OnExpenseClickListener listener;

    private static final NumberFormat NF = NumberFormat.getInstance(new Locale("id", "ID"));

    public ExpenseAdapter(OnExpenseClickListener listener) {
        this.listener = listener;
    }

    public void setItems(List<Expense> list) {
        this.items = list != null ? list : new ArrayList<>();
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_expense, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Expense e = items.get(position);
        h.tvName.setText(e.getName() != null ? e.getName() : "(tanpa nama)");
        h.tvAmount.setText("- Rp " + NF.format(Math.round(e.getAmount())));

        // Thumbnail foto (kalau ada) — ter-cache, RGB_565, sampling ke ukuran kecil.
        // Tint HANYA untuk ikon placeholder; wajib di-clear saat foto asli dipasang
        // (tint SRC_IN mewarnai bitmap → thumbnail tampil abu-abu solid). ViewHolder
        // di-recycle, jadi KEDUA cabang harus set tint secara eksplisit.
        android.graphics.Bitmap thumb = e.getPhotoPath() != null && !e.getPhotoPath().isEmpty()
                ? com.crowja.damiupos.util.BitmapUtils.cachedThumb(e.getPhotoPath(), 160, 160)
                : null;
        if (thumb != null) {
            h.ivThumb.setImageTintList(null);
            h.ivThumb.setImageBitmap(thumb);
        } else {
            h.ivThumb.setImageTintList(android.content.res.ColorStateList.valueOf(
                    androidx.core.content.ContextCompat.getColor(
                            h.itemView.getContext(), R.color.grey_medium)));
            h.ivThumb.setImageResource(android.R.drawable.ic_menu_gallery);
        }

        String dateLine = formatDate(e.getCreatedAt());
        if (e.isAirBaku()) {
            dateLine += "  ·  Air Baku";
            if (e.getLiters() > 0) dateLine += " " + NF.format(Math.round(e.getLiters())) + " L";
        } else if (e.usesPcs()) {
            dateLine += "  ·  " + e.getCategoryLabel();
            if (e.getPcs() > 0) dateLine += " " + NF.format(e.getPcs()) + " pcs";
        }
        h.tvDate.setText(dateLine);

        h.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onExpenseClick(e);
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    // Dipakai ulang (bind hanya di UI thread) — hindari 2 alokasi SimpleDateFormat tiap baris scroll.
    private static final SimpleDateFormat EXP_IN_FMT =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
    private static final SimpleDateFormat EXP_OUT_FMT =
            new SimpleDateFormat("d MMM yyyy, HH:mm", new Locale("id", "ID"));

    private static String formatDate(String iso) {
        if (iso == null || iso.isEmpty()) return "";
        try {
            // SQLite default format: "yyyy-MM-dd HH:mm:ss"
            Date d = EXP_IN_FMT.parse(iso);
            if (d == null) return iso;
            return EXP_OUT_FMT.format(d);
        } catch (Throwable t) {
            return iso;
        }
    }

    static class VH extends RecyclerView.ViewHolder {
        final ImageView ivThumb;
        final TextView tvName, tvAmount, tvDate;

        VH(@NonNull View v) {
            super(v);
            ivThumb = v.findViewById(R.id.ivThumb);
            tvName = v.findViewById(R.id.tvName);
            tvAmount = v.findViewById(R.id.tvAmount);
            tvDate = v.findViewById(R.id.tvDate);
        }
    }
}
