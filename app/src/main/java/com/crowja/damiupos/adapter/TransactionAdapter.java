package com.crowja.damiupos.adapter;

import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.crowja.damiupos.R;
import com.crowja.damiupos.model.Transaction;
import com.crowja.damiupos.model.TransactionItem;
import com.crowja.damiupos.util.Ts;

import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

public class TransactionAdapter extends RecyclerView.Adapter<TransactionAdapter.ViewHolder> {

    public interface OnItemClickListener { void onClick(Transaction trx); }
    public interface OnItemLongClickListener { void onLongClick(Transaction trx); }

    // Hoisted supaya tidak alokasi/lookup per-bind (main-thread, aman dibagi).
    private static final NumberFormat NF = NumberFormat.getInstance(new Locale("id", "ID"));
    private static final int ORANGE = android.graphics.Color.parseColor("#E65100");

    // Palet warna titik item — warna stabil per nama produk (hash → indeks palet).
    private static final int[] DOT_PALETTE = {
            0xFF1976D2, 0xFF388E3C, 0xFFE64A19, 0xFF7B1FA2,
            0xFF00838F, 0xFFC2185B, 0xFFF9A825, 0xFF5D4037,
    };
    // Format tampilan tanggal kartu transaksi (waktu lokal perangkat).
    private static final SimpleDateFormat OUT_DATE_FMT =
            new SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.US);
    // Parser input dipakai ulang (hanya di-bind pada UI thread) — hindari alokasi tiap baris scroll.
    private static final SimpleDateFormat IN_DATE_FMT =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

    private List<Transaction> transactions = new ArrayList<>();
    private boolean showCustomerName = true;
    // Baris "⏱ Waktu Pengiriman" per transaksi — dibiarkan MATI secara default (perlu opt-in
    // eksplisit dari layar pemanggil) supaya adapter yang sama tetap netral role di
    // MainActivity/TransactionListActivity; hanya CustomerDetailActivity yang menyalakannya, dan
    // hanya untuk role admin/marketing/spv (lihat CustomerDetailActivity untuk alasannya).
    private boolean showDeliveryMetric = false;
    private OnItemClickListener onItemClickListener;
    private OnItemLongClickListener onItemLongClickListener;
    private int colorPrimary = 0, colorGreen = 0; // di-resolve sekali

    public void setOnItemClickListener(OnItemClickListener l) { this.onItemClickListener = l; }
    public void setOnItemLongClickListener(OnItemLongClickListener l) { this.onItemLongClickListener = l; }
    public void setShowDeliveryMetric(boolean v) { this.showDeliveryMetric = v; }

    public TransactionAdapter() {}

    public TransactionAdapter(boolean showCustomerName) {
        this.showCustomerName = showCustomerName;
    }

    // id produk lokal → produk (slug + warna), dimuat sekali per setData supaya bind tetap ringan.
    private java.util.Map<Long, com.crowja.damiupos.model.Product> productsById = new java.util.HashMap<>();
    private java.util.Map<String, com.crowja.damiupos.model.Product> productsByName = new java.util.HashMap<>();

    public void setProducts(List<com.crowja.damiupos.model.Product> products) {
        productsById = new java.util.HashMap<>();
        productsByName = new java.util.HashMap<>();
        if (products == null) return;
        for (com.crowja.damiupos.model.Product p : products) {
            productsById.put(p.getId(), p);
            if (p.getName() != null) productsByName.put(p.getName().trim().toLowerCase(Locale.ROOT), p);
        }
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

    /** "● ● ●  N produk" — satu titik berwarna per produk (warna stabil per nama produk),
     *  diikuti jumlah produk. Mengganti daftar nama produk yang panjang. Bekerja sama baik
     *  untuk transaksi yang dibuat di perangkat maupun di web dashboard (keduanya menyimpan
     *  items_json yang sama; pid opsional saat parse). */
    private static CharSequence buildItemDots(Transaction trx) {
        List<TransactionItem> items = trx.getItems();
        SpannableStringBuilder sb = new SpannableStringBuilder();
        int count;
        if (items != null && !items.isEmpty()) {
            for (TransactionItem it : items) appendDot(sb, it != null ? it.productName : null);
            count = items.size();
        } else {
            appendDot(sb, trx.getProductName());
            count = 1;
        }
        sb.append(count + " produk");
        return sb;
    }

    /** "ProductA x2, ProductB x1" — rincian produk & qty per baris, untuk ditampilkan di
     *  bawah {@link #buildItemDots} yang hanya titik warna + jumlah produk. Fallback ke
     *  produk tunggal + total galon saat transaksi tidak punya items_json (skema lama). */
    private static String buildItemsSummary(Transaction trx) {
        List<TransactionItem> items = trx.getItems();
        StringBuilder sb = new StringBuilder();
        if (items != null && !items.isEmpty()) {
            for (TransactionItem it : items) {
                if (it == null) continue;
                if (sb.length() > 0) sb.append(", ");
                String name = it.productName != null && !it.productName.isEmpty() ? it.productName : "Produk";
                sb.append(name).append(" x").append(it.jumlah);
            }
        } else {
            String name = trx.getProductName() != null && !trx.getProductName().isEmpty()
                    ? trx.getProductName() : "Produk";
            sb.append(name).append(" x").append(trx.getJumlahGalon());
        }
        return sb.toString();
    }

    private static void appendDot(SpannableStringBuilder sb, String productName) {
        int start = sb.length();
        sb.append("●"); // ●
        sb.setSpan(new ForegroundColorSpan(dotColor(productName)),
                start, sb.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.append("  ");     // jarak antar titik / sebelum "N item"
    }

    /** Warna stabil per NAMA produk dari palet — dipakai bersama layar lain (mis. chip produk di
     *  Antrian Delivery) supaya satu produk tampil warna yang sama di seluruh aplikasi saat master
     *  produknya tak ketemu (nama beku di items_json sudah diganti/digabung setelah order dibuat). */
    public static int paletteColor(String productName) {
        return dotColor(productName);
    }

    private static int dotColor(String name) {
        if (name == null) name = "";
        return DOT_PALETTE[(name.hashCode() & 0x7fffffff) % DOT_PALETTE.length];
    }

    /** Format tanggal transaksi → "dd/MM/yyyy HH:mm:ss" (waktu lokal perangkat).
     *  Menerima "yyyy-MM-dd HH:mm:ss" (lokal) atau ISO UTC "…THH:mm:ss[.ffffff]Z". */
    private static String formatTanggal(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.isEmpty()) return "";
        try {
            boolean utc = s.endsWith("Z");
            String core = utc ? s.substring(0, s.length() - 1) : s;
            int dot = core.indexOf('.');
            if (dot > 0) core = core.substring(0, dot);   // buang pecahan detik
            core = core.replace('T', ' ').trim();
            IN_DATE_FMT.setTimeZone(utc ? TimeZone.getTimeZone("UTC") : TimeZone.getDefault());
            Date d = IN_DATE_FMT.parse(core);
            return d != null ? OUT_DATE_FMT.format(d) : raw;
        } catch (Exception e) {
            return raw;   // format tak dikenal → tampilkan apa adanya
        }
    }

    /**
     * Detik proses (delivery_queued_at → delivery_done_at) untuk SATU transaksi JUAL yang sudah
     * selesai diantar; -1 bila bukan kandidat (KEMBALI, belum DONE, atau stempelnya hilang).
     *
     * <p>Sengaja hanya dua stempel ini — sama dengan {@code Reports::deliveryStatsByStaff} di web —
     * BUKAN delivery_started_at: kolom itu flag "sedang berjalan sekarang" yang ditulis ulang tiap
     * kurir menekan ▶/■ (lihat migration 2026_08_05_090000), jadi tak aman dijadikan bagian durasi
     * historis. queued→done tetap benar walau tak pernah dijalankan lewat ▶.</p>
     */
    public static long deliverySeconds(Transaction trx) {
        if (!Transaction.TYPE_JUAL.equals(trx.getType())
                || !Transaction.DELIVERY_DONE.equals(trx.getDeliveryStatus())) {
            return -1;
        }
        long q = Ts.millis(trx.getDeliveryQueuedAt());
        long d = Ts.millis(trx.getDeliveryDoneAt());
        if (q == Long.MAX_VALUE || d == Long.MAX_VALUE || d < q) return -1;
        return (d - q) / 1000L;
    }

    /** "17 mnt 36 dtk" / "2 jam 5 mnt" — cermin DeliveryQueueActivity#formatDuration (detik, bukan ms). */
    public static String formatDeliverySeconds(long secs) {
        long h = secs / 3600L;
        long m = secs % 3600L / 60L;
        long s = secs % 60L;
        if (h > 0L) return h + " jam " + m + " mnt";
        return m > 0L ? m + " mnt " + s + " dtk" : s + " dtk";
    }

    class ViewHolder extends RecyclerView.ViewHolder {
        View cardTransaction;
        android.view.View chipsItemsView;
        TextView tvDoneBy;
        TextView tvTypeIcon, tvType, tvCustomerName, tvComplaintBadge, tvProductItems, tvDate, tvDeliveryTime, tvGalonCount, tvHarga;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            cardTransaction = itemView.findViewById(R.id.cardTransaction);
            tvTypeIcon = itemView.findViewById(R.id.tvTypeIcon);
            tvType = itemView.findViewById(R.id.tvType);
            tvComplaintBadge = itemView.findViewById(R.id.tvComplaintBadge);
            tvCustomerName = itemView.findViewById(R.id.tvCustomerName);
            tvProductItems = itemView.findViewById(R.id.tvProductItems);
            tvDate = itemView.findViewById(R.id.tvDate);
            tvDeliveryTime = itemView.findViewById(R.id.tvDeliveryTime);
            chipsItemsView = itemView.findViewById(R.id.chipsItems);
            tvDoneBy = itemView.findViewById(R.id.tvDoneBy);
            tvGalonCount = itemView.findViewById(R.id.tvGalonCount);
            tvHarga = itemView.findViewById(R.id.tvHarga);
        }

        private void bindChips(Transaction trx) {
            com.google.android.material.chip.ChipGroup g = (com.google.android.material.chip.ChipGroup) chipsItemsView;
            g.removeAllViews();
            List<TransactionItem> items = trx.getItems();
            List<TransactionItem> src = new ArrayList<>();
            if (items != null && !items.isEmpty()) {
                src.addAll(items);
            } else {
                TransactionItem one = new TransactionItem(0L, trx.getProductName(), trx.getJumlahGalon(), 0d);
                src.add(one);
            }
            float d = itemView.getResources().getDisplayMetrics().density;
            for (TransactionItem it : src) {
                if (it == null) continue;
                com.crowja.damiupos.model.Product p = productsById.get(it.productId);
                if (p == null && it.productName != null) {
                    p = productsByName.get(it.productName.trim().toLowerCase(Locale.ROOT));
                }
                String label = p != null && p.getSlug() != null && !p.getSlug().trim().isEmpty()
                        ? p.getSlug().trim() : (it.productName != null && !it.productName.isEmpty() ? it.productName : "Produk");
                int bg = dotColor(it.productName);
                try {
                    if (p != null && p.getColor() != null && !p.getColor().isEmpty()) bg = android.graphics.Color.parseColor(p.getColor());
                } catch (IllegalArgumentException ignored) { }
                double lum = (0.299 * android.graphics.Color.red(bg) + 0.587 * android.graphics.Color.green(bg)
                        + 0.114 * android.graphics.Color.blue(bg)) / 255.0;
                int fg = lum > 0.65 ? 0xFF1A1A1A : 0xFFFFFFFF;
                TextView chip = new TextView(itemView.getContext());
                chip.setText(label + " ×" + it.jumlah);
                chip.setTextColor(fg);
                chip.setTextSize(12f);
                chip.setTypeface(chip.getTypeface(), android.graphics.Typeface.BOLD);
                chip.setPadding(Math.round(8 * d), Math.round(3 * d), Math.round(8 * d), Math.round(3 * d));
                android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
                gd.setCornerRadius(10 * d);
                gd.setColor(bg);
                chip.setBackground(gd);
                g.addView(chip);
            }
            g.setVisibility(g.getChildCount() > 0 ? View.VISIBLE : View.GONE);
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
                // Ganti daftar nama produk \u2192 titik berwarna per item + "N item".
                tvType.setText(trx.getItems() != null && trx.getItems().size() > 1
                        ? trx.getItems().size() + " produk" : "Jual");
                tvType.setTextColor(colorPrimary);
                tvGalonCount.setTextColor(colorPrimary);
                tvProductItems.setVisibility(View.GONE);
                bindChips(trx);
            } else {
                tvProductItems.setVisibility(View.GONE);
                chipsItemsView.setVisibility(View.GONE);
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
                // Potong nama pelanggan: maks 20 karakter pertama + "…".
                String cn = trx.getCustomerName();
                if (cn.length() > 20) cn = cn.substring(0, 20) + "...";
                tvCustomerName.setText("- " + cn);
                tvCustomerName.setVisibility(View.VISIBLE);
            } else {
                tvCustomerName.setVisibility(View.GONE);
            }
            tvComplaintBadge.setVisibility(trx.isComplained() ? View.VISIBLE : View.GONE);

            // Pakai waktu efektif (edited_at, konsisten) — selaras dengan urutan list &
            // monoton, sementara tanggal lama hasil sinkron bisa ter-skew tz.
            String dateLine = formatTanggal(trx.getEffectiveTime());   // dd/MM/yyyy HH:mm:ss (waktu lokal)
            String pay = isJual ? trx.getPaymentMethodLabel() : "";
            if (pay != null && !pay.isEmpty()) dateLine += "  ·  " + pay;
            tvDate.setText(dateLine);
            tvGalonCount.setText(trx.getJumlahGalon() + " galon");

            // Siapa yang menyelesaikan (semua peran); lama selesai hanya untuk peran yang boleh (lihat bawah).
            String doneBy = trx.getCompletedByName();
            boolean done = isJual && Transaction.DELIVERY_DONE.equals(trx.getDeliveryStatus());
            if (done && doneBy != null && !doneBy.trim().isEmpty()) {
                tvDoneBy.setText("✅ Selesai oleh " + doneBy.trim());
                tvDoneBy.setVisibility(View.VISIBLE);
            } else {
                tvDoneBy.setVisibility(View.GONE);
            }

            if (showDeliveryMetric) {
                long secs = deliverySeconds(trx);
                if (secs >= 0) {
                    tvDeliveryTime.setText("⏱ " + formatDeliverySeconds(secs));
                    tvDeliveryTime.setVisibility(View.VISIBLE);
                } else {
                    tvDeliveryTime.setVisibility(View.GONE);
                }
            } else {
                tvDeliveryTime.setVisibility(View.GONE);
            }

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
