package com.crowja.damiupos.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.crowja.damiupos.R;

import java.util.ArrayList;
import java.util.List;

public class StockHistoryAdapter extends RecyclerView.Adapter<StockHistoryAdapter.ViewHolder> {

    private List<String[]> items = new ArrayList<>();
    private final Listener listener;

    public interface Listener {
        void onItemLongClick(long id, int position);
        void onPhotoClick(String photoPath);
    }

    /** Backwards-compatible single-method listener */
    public interface OnItemLongClickListener {
        void onItemLongClick(long id, int position);
    }

    public StockHistoryAdapter(Listener listener) {
        this.listener = listener;
    }

    public StockHistoryAdapter(OnItemLongClickListener legacy) {
        this.listener = new Listener() {
            @Override public void onItemLongClick(long id, int position) { legacy.onItemLongClick(id, position); }
            @Override public void onPhotoClick(String photoPath) {}
        };
    }

    public void setData(List<String[]> items) {
        this.items = items;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_stock_history, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        holder.bind(items.get(position));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvStockJumlah, tvStockCatatan, tvStockTanggal;
        ImageView ivStockPhoto;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvStockJumlah = itemView.findViewById(R.id.tvStockJumlah);
            tvStockCatatan = itemView.findViewById(R.id.tvStockCatatan);
            tvStockTanggal = itemView.findViewById(R.id.tvStockTanggal);
            ivStockPhoto = itemView.findViewById(R.id.ivStockPhoto);

            itemView.setOnLongClickListener(v -> {
                int pos = getAdapterPosition();
                if (pos != RecyclerView.NO_POSITION && listener != null) {
                    long id = Long.parseLong(items.get(pos)[0]);
                    listener.onItemLongClick(id, pos);
                    return true;
                }
                return false;
            });
        }

        void bind(String[] item) {
            tvStockJumlah.setText("+" + item[1] + " galon");

            String catatan = item[2];
            if (catatan != null && !catatan.isEmpty()) {
                tvStockCatatan.setText(catatan);
                tvStockCatatan.setVisibility(View.VISIBLE);
            } else {
                tvStockCatatan.setVisibility(View.GONE);
            }

            String tanggal = item[3];
            if (tanggal != null && tanggal.length() >= 10) {
                tvStockTanggal.setText(tanggal.substring(0, 10));
            } else {
                tvStockTanggal.setText(tanggal);
            }

            String photoPath = item.length > 4 ? item[4] : null;
            if (ivStockPhoto != null) {
                if (photoPath != null && !photoPath.isEmpty() && new java.io.File(photoPath).exists()) {
                    ivStockPhoto.setVisibility(View.VISIBLE);
                    ivStockPhoto.setOnClickListener(v -> {
                        if (listener != null) listener.onPhotoClick(photoPath);
                    });
                } else {
                    ivStockPhoto.setVisibility(View.GONE);
                    ivStockPhoto.setOnClickListener(null);
                }
            }
        }
    }
}
