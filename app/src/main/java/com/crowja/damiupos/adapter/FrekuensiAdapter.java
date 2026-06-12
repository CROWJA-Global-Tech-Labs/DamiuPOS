package com.crowja.damiupos.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.crowja.damiupos.R;
import com.crowja.damiupos.model.Customer;

import java.util.ArrayList;
import java.util.List;

public class FrekuensiAdapter extends RecyclerView.Adapter<FrekuensiAdapter.ViewHolder> {

    public static final int MODE_FREKUENSI = 0;
    public static final int MODE_GALON = 1;

    public interface OnItemClickListener { void onClick(Customer c); }

    private List<Customer> customers = new ArrayList<>();
    private int maxCount = 1;
    private int mode = MODE_FREKUENSI;
    private OnItemClickListener listener;

    public FrekuensiAdapter() {}

    public FrekuensiAdapter(int mode) {
        this.mode = mode;
    }

    public void setOnItemClickListener(OnItemClickListener l) { this.listener = l; }

    private int valueOf(Customer c) {
        return mode == MODE_GALON ? c.getGalonKeluar() : c.getTotalTransaksi();
    }

    public void setData(List<Customer> customers) {
        this.customers = customers;
        maxCount = 1;
        for (Customer c : customers) {
            int v = valueOf(c);
            if (v > maxCount) maxCount = v;
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_frekuensi, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Customer c = customers.get(position);
        int value = valueOf(c);
        holder.tvRank.setText(String.valueOf(position + 1));
        holder.tvName.setText(c.getName());
        holder.tvCount.setText(mode == MODE_GALON ? (value + " galon") : (value + "x"));

        // Set bar width proportionally
        holder.viewBar.post(() -> {
            FrameLayout parent = (FrameLayout) holder.viewBar.getParent();
            int parentWidth = parent.getWidth();
            float ratio = (float) value / maxCount;
            ViewGroup.LayoutParams lp = holder.viewBar.getLayoutParams();
            lp.width = (int) (parentWidth * ratio);
            holder.viewBar.setLayoutParams(lp);
        });

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onClick(c);
        });
    }

    @Override
    public int getItemCount() {
        return customers.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvRank, tvName, tvCount;
        View viewBar;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvRank = itemView.findViewById(R.id.tvRank);
            tvName = itemView.findViewById(R.id.tvName);
            tvCount = itemView.findViewById(R.id.tvCount);
            viewBar = itemView.findViewById(R.id.viewBar);
        }
    }
}
