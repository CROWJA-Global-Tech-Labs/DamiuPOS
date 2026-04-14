package com.damiu.pos.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.damiu.pos.R;

import java.util.ArrayList;
import java.util.List;

public class StockHistoryAdapter extends RecyclerView.Adapter<StockHistoryAdapter.ViewHolder> {

    private List<String[]> items = new ArrayList<>();
    private OnItemLongClickListener longClickListener;

    public interface OnItemLongClickListener {
        void onItemLongClick(long id, int position);
    }

    public StockHistoryAdapter(OnItemLongClickListener listener) {
        this.longClickListener = listener;
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

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvStockJumlah = itemView.findViewById(R.id.tvStockJumlah);
            tvStockCatatan = itemView.findViewById(R.id.tvStockCatatan);
            tvStockTanggal = itemView.findViewById(R.id.tvStockTanggal);

            itemView.setOnLongClickListener(v -> {
                int pos = getAdapterPosition();
                if (pos != RecyclerView.NO_POSITION && longClickListener != null) {
                    long id = Long.parseLong(items.get(pos)[0]);
                    longClickListener.onItemLongClick(id, pos);
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
        }
    }
}
