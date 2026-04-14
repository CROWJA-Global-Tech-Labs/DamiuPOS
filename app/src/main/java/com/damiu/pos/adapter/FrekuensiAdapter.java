package com.damiu.pos.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.damiu.pos.R;
import com.damiu.pos.model.Customer;

import java.util.ArrayList;
import java.util.List;

public class FrekuensiAdapter extends RecyclerView.Adapter<FrekuensiAdapter.ViewHolder> {

    private List<Customer> customers = new ArrayList<>();
    private int maxCount = 1;

    public void setData(List<Customer> customers) {
        this.customers = customers;
        maxCount = 1;
        for (Customer c : customers) {
            if (c.getTotalTransaksi() > maxCount) {
                maxCount = c.getTotalTransaksi();
            }
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
        holder.tvRank.setText(String.valueOf(position + 1));
        holder.tvName.setText(c.getName());
        holder.tvCount.setText(c.getTotalTransaksi() + "x");

        // Set bar width proportionally
        holder.viewBar.post(() -> {
            FrameLayout parent = (FrameLayout) holder.viewBar.getParent();
            int parentWidth = parent.getWidth();
            float ratio = (float) c.getTotalTransaksi() / maxCount;
            ViewGroup.LayoutParams lp = holder.viewBar.getLayoutParams();
            lp.width = (int) (parentWidth * ratio);
            holder.viewBar.setLayoutParams(lp);
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
