package com.damiu.pos.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.damiu.pos.R;
import com.damiu.pos.model.Transaction;
import com.damiu.pos.model.TransactionItem;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class TransactionAdapter extends RecyclerView.Adapter<TransactionAdapter.ViewHolder> {

    public interface OnItemClickListener { void onClick(Transaction trx); }

    private List<Transaction> transactions = new ArrayList<>();
    private boolean showCustomerName = true;
    private OnItemClickListener onItemClickListener;

    public void setOnItemClickListener(OnItemClickListener l) { this.onItemClickListener = l; }

    public TransactionAdapter() {}

    public TransactionAdapter(boolean showCustomerName) {
        this.showCustomerName = showCustomerName;
    }

    public void setData(List<Transaction> transactions) {
        this.transactions = transactions;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_transaction, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        holder.bind(transactions.get(position));
    }

    @Override
    public int getItemCount() {
        return transactions.size();
    }

    class ViewHolder extends RecyclerView.ViewHolder {
        View cardTransaction;
        TextView tvTypeIcon, tvType, tvCustomerName, tvDate, tvGalonCount, tvHarga;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            cardTransaction = itemView.findViewById(R.id.cardTransaction);
            tvTypeIcon = itemView.findViewById(R.id.tvTypeIcon);
            tvType = itemView.findViewById(R.id.tvType);
            tvCustomerName = itemView.findViewById(R.id.tvCustomerName);
            tvDate = itemView.findViewById(R.id.tvDate);
            tvGalonCount = itemView.findViewById(R.id.tvGalonCount);
            tvHarga = itemView.findViewById(R.id.tvHarga);
        }

        void bind(Transaction trx) {
            boolean isJual = Transaction.TYPE_JUAL.equals(trx.getType());

            if (isJual) {
                tvTypeIcon.setBackgroundResource(R.drawable.bg_type_jual);
                tvTypeIcon.setText("\u2191"); // arrow up
                String typeLabel = "Jual";
                List<TransactionItem> items = trx.getItems();
                if (items != null && items.size() > 1) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < items.size(); i++) {
                        if (i > 0) sb.append(" + ");
                        sb.append(items.get(i).productName);
                    }
                    typeLabel = sb.toString();
                } else if (items != null && items.size() == 1) {
                    typeLabel = items.get(0).productName;
                } else if (trx.getProductName() != null && !trx.getProductName().isEmpty()) {
                    typeLabel = trx.getProductName();
                }
                tvType.setText(typeLabel);
                tvType.setTextColor(itemView.getContext().getResources().getColor(R.color.primary));
                tvGalonCount.setTextColor(itemView.getContext().getResources().getColor(R.color.primary));
            } else {
                boolean isGantiRugi = trx.getTotalHarga() > 0
                        || (trx.getCatatan() != null && trx.getCatatan().contains("[GANTI RUGI"));
                if (isGantiRugi) {
                    tvTypeIcon.setBackgroundResource(R.drawable.bg_type_kembali);
                    tvTypeIcon.setText("\u26A0"); // warning sign
                    tvType.setText("Kembali — Ganti Rugi");
                    int orange = android.graphics.Color.parseColor("#E65100");
                    tvType.setTextColor(orange);
                    tvGalonCount.setTextColor(orange);
                } else {
                    tvTypeIcon.setBackgroundResource(R.drawable.bg_type_kembali);
                    tvTypeIcon.setText("\u2193"); // arrow down
                    tvType.setText("Kembali");
                    tvType.setTextColor(itemView.getContext().getResources().getColor(R.color.green));
                    tvGalonCount.setTextColor(itemView.getContext().getResources().getColor(R.color.green));
                }
            }

            if (showCustomerName && trx.getCustomerName() != null) {
                tvCustomerName.setText("- " + trx.getCustomerName());
                tvCustomerName.setVisibility(View.VISIBLE);
            } else {
                tvCustomerName.setVisibility(View.GONE);
            }

            tvDate.setText(trx.getTanggal() != null ? trx.getTanggal() : "");
            tvGalonCount.setText(trx.getJumlahGalon() + " galon");

            if (isJual || trx.getTotalHarga() > 0) {
                NumberFormat nf = NumberFormat.getInstance(new Locale("id", "ID"));
                tvHarga.setText("Rp " + nf.format(trx.getTotalHarga()));
                tvHarga.setVisibility(View.VISIBLE);
            } else {
                tvHarga.setVisibility(View.GONE);
            }

            itemView.setOnClickListener(v -> {
                if (onItemClickListener != null) onItemClickListener.onClick(trx);
            });
        }
    }
}
