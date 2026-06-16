package com.crowja.damiupos.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.crowja.damiupos.R;
import com.crowja.damiupos.model.Transaction;
import com.crowja.damiupos.model.TransactionItem;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class TransactionAdapter extends RecyclerView.Adapter<TransactionAdapter.ViewHolder> {

    public interface OnItemClickListener { void onClick(Transaction trx); }
    public interface OnItemLongClickListener { void onLongClick(Transaction trx); }

    // Hoisted supaya tidak alokasi/lookup per-bind (main-thread, aman dibagi).
    private static final NumberFormat NF = NumberFormat.getInstance(new Locale("id", "ID"));
    private static final int ORANGE = android.graphics.Color.parseColor("#E65100");

    private List<Transaction> transactions = new ArrayList<>();
    private boolean showCustomerName = true;
    private OnItemClickListener onItemClickListener;
    private OnItemLongClickListener onItemLongClickListener;
    private int colorPrimary = 0, colorGreen = 0; // di-resolve sekali

    public void setOnItemClickListener(OnItemClickListener l) { this.onItemClickListener = l; }
    public void setOnItemLongClickListener(OnItemLongClickListener l) { this.onItemLongClickListener = l; }

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
            if (colorPrimary == 0) {
                colorPrimary = itemView.getContext().getResources().getColor(R.color.primary);
                colorGreen = itemView.getContext().getResources().getColor(R.color.green);
            }
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
                tvType.setTextColor(colorPrimary);
                tvGalonCount.setTextColor(colorPrimary);
            } else {
                boolean isGantiRugi = trx.getTotalHarga() > 0
                        || (trx.getCatatan() != null && trx.getCatatan().contains("[GANTI RUGI"));
                if (isGantiRugi) {
                    tvTypeIcon.setBackgroundResource(R.drawable.bg_type_kembali);
                    tvTypeIcon.setText("\u26A0"); // warning sign
                    tvType.setText("Kembali — Ganti Rugi");
                    tvType.setTextColor(ORANGE);
                    tvGalonCount.setTextColor(ORANGE);
                } else {
                    tvTypeIcon.setBackgroundResource(R.drawable.bg_type_kembali);
                    tvTypeIcon.setText("\u2193"); // arrow down
                    tvType.setText("Kembali");
                    tvType.setTextColor(colorGreen);
                    tvGalonCount.setTextColor(colorGreen);
                }
            }

            if (showCustomerName && trx.getCustomerName() != null) {
                tvCustomerName.setText("- " + trx.getCustomerName());
                tvCustomerName.setVisibility(View.VISIBLE);
            } else {
                tvCustomerName.setVisibility(View.GONE);
            }

            String dateLine = trx.getTanggal() != null ? trx.getTanggal() : "";
            String pay = isJual ? trx.getPaymentMethodLabel() : "";
            if (pay != null && !pay.isEmpty()) dateLine += "  ·  " + pay;
            tvDate.setText(dateLine);
            tvGalonCount.setText(trx.getJumlahGalon() + " galon");

            if (isJual || trx.getTotalHarga() > 0) {
                tvHarga.setText("Rp " + NF.format(trx.getTotalHarga()));
                tvHarga.setVisibility(View.VISIBLE);
            } else {
                tvHarga.setVisibility(View.GONE);
            }

            itemView.setOnClickListener(v -> {
                if (onItemClickListener != null) onItemClickListener.onClick(trx);
            });
            itemView.setOnLongClickListener(v -> {
                if (onItemLongClickListener != null) {
                    onItemLongClickListener.onLongClick(trx);
                    return true;
                }
                return false;
            });
        }
    }
}
