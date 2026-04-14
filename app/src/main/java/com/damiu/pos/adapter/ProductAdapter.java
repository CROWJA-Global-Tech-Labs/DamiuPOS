package com.damiu.pos.adapter;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.damiu.pos.R;
import com.damiu.pos.model.Product;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class ProductAdapter extends RecyclerView.Adapter<ProductAdapter.ViewHolder> {

    private List<Product> products = new ArrayList<>();
    private OnProductClickListener listener;
    private final NumberFormat nf = NumberFormat.getInstance(new Locale("id", "ID"));

    public interface OnProductClickListener {
        void onProductClick(Product product);
    }

    public ProductAdapter(OnProductClickListener listener) {
        this.listener = listener;
    }

    public void setData(List<Product> products) {
        this.products = products;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_product, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        holder.bind(products.get(position));
    }

    @Override
    public int getItemCount() {
        return products.size();
    }

    class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvIcon, tvName, tvHargaJual, tvHargaModal, tvProfit;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvIcon = itemView.findViewById(R.id.tvIcon);
            tvName = itemView.findViewById(R.id.tvName);
            tvHargaJual = itemView.findViewById(R.id.tvHargaJual);
            tvHargaModal = itemView.findViewById(R.id.tvHargaModal);
            tvProfit = itemView.findViewById(R.id.tvProfit);

            itemView.setOnClickListener(v -> {
                int pos = getAdapterPosition();
                if (pos != RecyclerView.NO_POSITION && listener != null) {
                    listener.onProductClick(products.get(pos));
                }
            });
        }

        void bind(Product product) {
            tvName.setText(product.getName());
            tvHargaJual.setText("Rp " + nf.format(product.getHargaJual()));
            tvHargaModal.setText("Rp " + nf.format(product.getHargaModal()));
            tvProfit.setText("Profit Rp " + nf.format(product.getProfit()));

            String color = product.getColor();
            if (color != null && !color.isEmpty()) {
                GradientDrawable bg = new GradientDrawable();
                bg.setShape(GradientDrawable.OVAL);
                bg.setColor(Color.parseColor(color));
                tvIcon.setBackground(bg);
            }
        }
    }
}
