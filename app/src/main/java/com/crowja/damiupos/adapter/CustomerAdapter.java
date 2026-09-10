package com.crowja.damiupos.adapter;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.crowja.damiupos.R;
import com.crowja.damiupos.model.Customer;
import com.google.android.material.card.MaterialCardView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class CustomerAdapter extends RecyclerView.Adapter<CustomerAdapter.ViewHolder> {

    private List<Customer> customers = new ArrayList<>();
    private OnCustomerClickListener listener;

    /** Multi-select state — opt-in lewat setOnSelectionChangedListener. */
    private final Set<Long> selectedIds = new HashSet<>();
    private boolean selectionMode = false;
    private OnSelectionChangedListener selectionListener;

    public interface OnCustomerClickListener {
        void onCustomerClick(Customer customer);
    }

    /** Activity meng-implement ini supaya bisa update toolbar / ActionMode. */
    public interface OnSelectionChangedListener {
        /** Dipanggil tiap kali set selection berubah (entry, exit, toggle). */
        void onSelectionChanged(int selectedCount);
    }

    public CustomerAdapter(OnCustomerClickListener listener) {
        this.listener = listener;
    }

    public void setData(List<Customer> customers) {
        this.customers = customers;
        // Bersihkan selection yg id-nya tidak lagi ada di list
        selectedIds.retainAll(getAllIds());
        notifyDataSetChanged();
        if (selectionListener != null) {
            selectionListener.onSelectionChanged(selectedIds.size());
        }
    }

    /** Aktifkan multi-select. Pass {@code null} untuk disable (default). */
    public void setOnSelectionChangedListener(OnSelectionChangedListener l) {
        this.selectionListener = l;
    }

    public Set<Long> getSelectedIds() {
        return new HashSet<>(selectedIds);
    }

    public boolean isSelectionMode() { return selectionMode; }

    /** Keluar selection mode + clear semua. */
    public void clearSelection() {
        if (!selectionMode && selectedIds.isEmpty()) return;
        selectionMode = false;
        selectedIds.clear();
        notifyDataSetChanged();
        if (selectionListener != null) selectionListener.onSelectionChanged(0);
    }

    /** Centang semua item yang sedang tampil. */
    public void selectAll() {
        if (!selectionMode) selectionMode = true;
        for (Customer c : customers) selectedIds.add(c.getId());
        notifyDataSetChanged();
        if (selectionListener != null) {
            selectionListener.onSelectionChanged(selectedIds.size());
        }
    }

    private Set<Long> getAllIds() {
        Set<Long> ids = new HashSet<>();
        for (Customer c : customers) ids.add(c.getId());
        return ids;
    }

    private void toggleSelection(long id) {
        if (selectedIds.contains(id)) {
            selectedIds.remove(id);
            if (selectedIds.isEmpty()) {
                // Auto-exit selection mode kalau tidak ada item terpilih
                selectionMode = false;
            }
        } else {
            selectedIds.add(id);
        }
        notifyDataSetChanged();
        if (selectionListener != null) {
            selectionListener.onSelectionChanged(selectedIds.size());
        }
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_customer, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Customer customer = customers.get(position);
        holder.bind(customer);
    }

    @Override
    public int getItemCount() {
        return customers.size();
    }

    class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvInitial, tvName, tvPhone, tvSaldoGalon, tvStats, tvResellerBadge, tvAfiliasiBadge, tvOngkirBadge, tvOrigin, tvMediaStatus;
        MaterialCardView card;
        int defaultStrokeColor;
        int defaultStrokeWidth;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            card = (MaterialCardView) itemView;
            tvInitial = itemView.findViewById(R.id.tvInitial);
            tvName = itemView.findViewById(R.id.tvName);
            tvPhone = itemView.findViewById(R.id.tvPhone);
            tvSaldoGalon = itemView.findViewById(R.id.tvSaldoGalon);
            tvStats = itemView.findViewById(R.id.tvStats);
            tvResellerBadge = itemView.findViewById(R.id.tvResellerBadge);
            tvAfiliasiBadge = itemView.findViewById(R.id.tvAfiliasiBadge);
            tvOngkirBadge = itemView.findViewById(R.id.tvOngkirBadge);
            tvOrigin = itemView.findViewById(R.id.tvOrigin);
            tvMediaStatus = itemView.findViewById(R.id.tvMediaStatus);
            defaultStrokeColor = card.getStrokeColor();
            defaultStrokeWidth = card.getStrokeWidth();

            itemView.setOnClickListener(v -> {
                int pos = getAdapterPosition();
                if (pos == RecyclerView.NO_POSITION) return;
                Customer c = customers.get(pos);
                if (selectionMode) {
                    toggleSelection(c.getId());
                } else if (listener != null) {
                    listener.onCustomerClick(c);
                }
            });

            itemView.setOnLongClickListener(v -> {
                // Long-press hanya berfungsi kalau host activity sudah enable
                // multi-select via setOnSelectionChangedListener.
                if (selectionListener == null) return false;
                int pos = getAdapterPosition();
                if (pos == RecyclerView.NO_POSITION) return false;
                Customer c = customers.get(pos);
                if (!selectionMode) selectionMode = true;
                toggleSelection(c.getId());
                return true;
            });
        }

        void bind(Customer customer) {
            String name = customer.getName();
            tvInitial.setText(name != null && !name.isEmpty()
                    ? String.valueOf(name.charAt(0)).toUpperCase() : "?");
            // Nama gabungan bila orang yang sama bernama beda antar-perangkat ("NAMA1 / NAMA2");
            // fallback ke nama asli untuk daftar non-dedup. Inisial avatar tetap dari nama wakil.
            // Pelanggan prioritas: prefiks ⭐ pada nama (diutamakan di antrian delivery & follow up).
            tvName.setText((customer.isPriority() ? "⭐ " : "") + customer.getDisplayName());
            tvPhone.setText(customer.getPhone() != null ? customer.getPhone() : "-");
            // Angka galon BESAR = TOTAL galon yang diambil pelanggan (akuisisi/pembelian), bukan
            // hanya saldo pinjam (yang 0 untuk galon yang dibeli, mis. reseller) — supaya "jumlah
            // galon" akuisisi selalu terlihat. Saldo pinjam pindah ke baris stats.
            int totalOrdered = customer.getGalonTotalOrdered();
            int trxCount = customer.getTotalTransaksi();
            int pinjam = customer.getSaldoGalon();
            tvSaldoGalon.setText(String.valueOf(totalOrdered));

            // Penanda reseller pada card pelanggan.
            if (tvResellerBadge != null) {
                tvResellerBadge.setVisibility(customer.isReseller() ? View.VISIBLE : View.GONE);
            }
            // Penanda "TERAFILIASI" — pelanggan tertaut ke reseller (linked_reseller_uuid terisi;
            // sudah diangkat ke wakil lintas salinan oleh applyMergedAggregates).
            if (tvAfiliasiBadge != null) {
                String linked = customer.getLinkedResellerUuid();
                tvAfiliasiBadge.setVisibility(linked != null && !linked.isEmpty() ? View.VISIBLE : View.GONE);
            }
            // Penanda "ONGKIR" — pelanggan punya setidaknya satu lokasi wajib ongkir (termasuk yang
            // tercatat di salinan perangkat lain; diangkat oleh applyMergedAggregates).
            if (tvOngkirBadge != null) {
                tvOngkirBadge.setVisibility(customer.isWajibOngkirEffective() ? View.VISIBLE : View.GONE);
            }

            // Stats: jumlah transaksi · sisa pinjam (bila ada) · konsumsi gl/hr (agregat gabungan
            // lintas-perangkat, jadi sama seperti dashboard web). Selalu tampil.
            if (totalOrdered > 0 || trxCount > 0) {
                double perDay = customer.getKonsumsiPerHari();
                String konsumsiStr = perDay >= 0.1
                        ? String.format(java.util.Locale.US, "%.1f gl/hr", perDay)
                        : "<0.1 gl/hr";
                tvStats.setText(trxCount + "x transaksi"
                        + (pinjam > 0 ? "  ·  pinjam " + pinjam : "")
                        + "  ·  " + konsumsiStr);
            } else {
                tvStats.setText("Belum ada order");
            }
            tvStats.setVisibility(View.VISIBLE);

            // Tag kelengkapan: sudah ada FOTO rumah? sudah ada KOORDINAT? (hijau=ada, merah=belum).
            // hasPhoto = file lokal ATAU photo_url server (pelanggan dari perangkat lain tetap benar).
            if (tvMediaStatus != null) {
                boolean hasPhoto = customer.hasPhoto();
                boolean hasCoord = customer.hasCoordinates();
                int green = 0xFF2E7D32, red = 0xFFC62828;
                android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
                int a = sb.length();
                sb.append(hasPhoto ? "📷 Foto ✓" : "📷 Tanpa foto ✕");
                sb.setSpan(new android.text.style.ForegroundColorSpan(hasPhoto ? green : red),
                        a, sb.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.append("    ");
                int b = sb.length();
                sb.append(hasCoord ? "📍 Lokasi ✓" : "📍 Tanpa lokasi ✕");
                sb.setSpan(new android.text.style.ForegroundColorSpan(hasCoord ? green : red),
                        b, sb.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                tvMediaStatus.setText(sb);
            }

            // Tag perangkat asal (gabungan lintas salinan) — mis. "📱 SPPG + Web".
            java.util.List<String> origins = customer.getOriginLabels();
            if (tvOrigin != null) {
                if (origins != null && !origins.isEmpty()) {
                    tvOrigin.setText("📱 " + android.text.TextUtils.join(" + ", origins));
                    tvOrigin.setVisibility(View.VISIBLE);
                } else {
                    tvOrigin.setVisibility(View.GONE);
                }
            }

            // Visual selection state — stroke biru tebal kalau terpilih. Kalau tidak terpilih tapi
            // pelanggan BERMASALAH (issue aktif), latar kartu jadi merah muda + garis merah supaya
            // langsung terlihat perlu diperbaiki.
            boolean selected = selectionMode && selectedIds.contains(customer.getId());
            if (selected) {
                card.setStrokeColor(Color.parseColor("#1565C0"));
                card.setStrokeWidth((int) (3 * itemView.getResources()
                        .getDisplayMetrics().density));
                card.setCardBackgroundColor(Color.parseColor("#E3F2FD"));
            } else if (customer.hasOpenIssue()) {
                card.setStrokeColor(Color.parseColor("#EF5350"));
                card.setStrokeWidth((int) (1.5f * itemView.getResources()
                        .getDisplayMetrics().density));
                card.setCardBackgroundColor(Color.parseColor("#FDECEA"));
            } else {
                card.setStrokeColor(defaultStrokeColor);
                card.setStrokeWidth(defaultStrokeWidth);
                card.setCardBackgroundColor(Color.WHITE);
            }
        }
    }
}
