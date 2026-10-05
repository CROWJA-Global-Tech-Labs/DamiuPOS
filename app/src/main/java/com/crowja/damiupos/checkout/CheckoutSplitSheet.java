package com.crowja.damiupos.checkout;

import android.app.Activity;
import android.graphics.Color;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import com.crowja.damiupos.R;
import com.crowja.damiupos.model.Product;
import com.crowja.damiupos.model.Transaction;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButtonToggleGroup;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 🧺 Lembar "Bagi ke beberapa lokasi" di Transaksi Baru: satu blok per lokasi pelanggan (berkoordinat)
 * — jumlah & harga per jenis air, ongkos kirim, galon kembali, perangkat pengantar, catatan lokasi —
 * dengan subtotal per lokasi dan total gabungan di footer.
 *
 * <p>Lembar mengedit SALINAN bagian ({@link CheckoutPart#copy()}); hanya "Simpan pembagian" (minimal 2
 * lokasi bergalon) yang menyerahkannya ke {@link Listener#onApply}. Batal / tombol kembali = tak ada
 * yang berubah. Semua aturan hitung ada di {@link CheckoutPart} (murni, teruji JVM) — kelas ini hanya
 * merangkai tampilan.
 */
public final class CheckoutSplitSheet {

    public interface Listener {
        /** Pembagian disimpan (salinan bagian, termasuk lokasi 0 galon — pemanggil memfilter aktif). */
        void onApply(List<CheckoutPart> parts);

        /** "Matikan pembagian" — kembali ke order satu lokasi. */
        void onDisable();
    }

    /** Konteks lembar dari Transaksi Baru. */
    public static final class Config {
        /** Jenis air dalam urutan form (sumber nama, warna & id). */
        public List<Product> products = new ArrayList<>();
        /** Pilihan perangkat paralel dengan {@link #deviceLabels}; indeks 0 = null ("Perangkat ini").
         *  null = role ini tak boleh menugaskan → picker disembunyikan. */
        public List<String> deviceUuids;
        public List<String> deviceLabels;
        /** Sentinel "Pesanan Terbuka (Lelang)" di daftar perangkat (bukan uuid sungguhan). */
        public String openDispatchUuid;
        /** STAF & MARKETING: mengalihkan ke perangkat/staf lain minta konfirmasi — SEKALI per checkout. */
        public boolean confirmOtherDevice;
        /** Holder bersama "sudah dikonfirmasi" (milik Activity, bertahan saat lembar dibuka ulang). */
        public boolean[] otherDeviceConfirmed = {false};
        /** Kepemilikan "Di Pinjam" → tampilkan galon kembali per lokasi. */
        public boolean showKembali;
        /** Galon DIPINJAM pelanggan — plafon default galon kembali. */
        public int held;
        /** Kepemilikan "Di Beli" + harga botol (ikut dihitung di subtotal tiap lokasi). */
        public boolean beli;
        public double hargaBotol;
        /** Ongkir default Pengaturan — prefill saat operator memilih Per Botol di lokasi tanpa tarif. */
        public double defaultOngkir;
        /** Mode bagi lokasi sudah aktif → tampilkan "Matikan pembagian". */
        public boolean modeOn;
    }

    private final Activity activity;
    private final List<CheckoutPart> parts;
    private final Config cfg;
    private final Listener listener;
    private final NumberFormat nf = NumberFormat.getInstance(new Locale("id", "ID"));
    private final List<PartView> views = new ArrayList<>();
    private BottomSheetDialog dialog;
    private TextView tvGrandTotal, tvCount, tvHint;

    /** Satu blok lokasi yang sedang tampil. */
    private static final class PartView {
        CheckoutPart part;
        TextView tvSubtotal, tvWarn;
        EditText etOngkir, etKembali;
        View layoutOngkirValue;
        boolean syncing;   // perubahan teks programatik — jangan dianggap ketikan operator
    }

    private CheckoutSplitSheet(Activity activity, List<CheckoutPart> source, Config cfg, Listener listener) {
        this.activity = activity;
        this.cfg = cfg != null ? cfg : new Config();
        this.listener = listener;
        this.parts = new ArrayList<>();
        if (source != null) for (CheckoutPart p : source) this.parts.add(p.copy());
    }

    /** Tampilkan lembar untuk {@code parts} (disalin; aslinya tak disentuh sampai onApply). */
    public static void show(Activity activity, List<CheckoutPart> parts, Config cfg, Listener listener) {
        new CheckoutSplitSheet(activity, parts, cfg, listener).show();
    }

    private void show() {
        dialog = new BottomSheetDialog(activity);
        View root = LayoutInflater.from(activity).inflate(R.layout.dialog_checkout_split, null, false);
        dialog.setContentView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        // Tinggi penuh & tak bisa diseret: isi lembar panjang (beberapa lokasi × jenis air) dan
        // menggulir di dalam ScrollView — seret ke bawah tak sengaja tak boleh menutupnya.
        dialog.setOnShowListener(d -> {
            FrameLayout sheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (sheet == null) return;
            ViewGroup.LayoutParams lp = sheet.getLayoutParams();
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
            sheet.setLayoutParams(lp);
            BottomSheetBehavior<FrameLayout> b = BottomSheetBehavior.from(sheet);
            b.setSkipCollapsed(true);
            b.setDraggable(false);
            b.setState(BottomSheetBehavior.STATE_EXPANDED);
        });

        tvGrandTotal = root.findViewById(R.id.tvSplitGrandTotal);
        tvCount = root.findViewById(R.id.tvSplitCount);
        tvHint = root.findViewById(R.id.tvSplitHint);
        LinearLayout llParts = root.findViewById(R.id.llSplitParts);
        for (CheckoutPart p : parts) llParts.addView(buildPartView(llParts, p));

        View btnDisable = root.findViewById(R.id.btnSplitDisable);
        btnDisable.setVisibility(cfg.modeOn ? View.VISIBLE : View.GONE);
        btnDisable.setOnClickListener(v -> {
            dialog.dismiss();
            if (listener != null) listener.onDisable();
        });
        root.findViewById(R.id.btnSplitCancel).setOnClickListener(v -> dialog.dismiss());
        root.findViewById(R.id.btnSplitApply).setOnClickListener(v -> {
            if (CheckoutPart.active(parts).size() < CheckoutConstants.MIN_LEGS) {
                tvHint.setVisibility(View.VISIBLE);
                tvHint.setTextColor(Color.parseColor("#C62828"));
                ScrollView sv = root.findViewById(R.id.svSplitParts);
                if (sv != null) sv.smoothScrollTo(0, 0);
                return;
            }
            dialog.dismiss();
            if (listener != null) listener.onApply(parts);
        });

        recomputeKembaliDefaults();
        refreshAll();
        dialog.show();
    }

    // ------------------------------------------------------------------ satu blok lokasi

    private View buildPartView(ViewGroup parent, CheckoutPart part) {
        View card = LayoutInflater.from(activity).inflate(R.layout.item_checkout_part, parent, false);
        PartView pv = new PartView();
        pv.part = part;
        views.add(pv);

        TextView tvTitle = card.findViewById(R.id.tvPartTitle);
        tvTitle.setText("📍 " + part.name() + (part.locIndex == 0 ? " (utama)" : ""));
        TextView tvBadge = card.findViewById(R.id.tvPartOngkirBadge);
        double rate = CheckoutPart.locationRate(part.loc);
        if (rate > 0) {
            tvBadge.setText("🚚 Rp " + nf.format(Math.round(rate)) + "/galon");
            tvBadge.setVisibility(View.VISIBLE);
        } else if (part.wajib) {
            tvBadge.setText("🚚 Wajib Ongkir");
            tvBadge.setVisibility(View.VISIBLE);
        } else {
            tvBadge.setVisibility(View.GONE);
        }
        TextView tvCoord = card.findViewById(R.id.tvPartCoord);
        tvCoord.setText(part.loc != null
                ? String.format(Locale.US, "%.5f, %.5f", part.loc.lat, part.loc.lng) : "");

        // Jenis air: harga per pcs (harga khusus lokasi ini) + stepper jumlah.
        LinearLayout llProducts = card.findViewById(R.id.llPartProducts);
        for (Product p : cfg.products) llProducts.addView(buildProductRow(llProducts, pv, p));

        // Ongkos kirim per lokasi.
        pv.layoutOngkirValue = card.findViewById(R.id.layoutPartOngkirValue);
        pv.etOngkir = card.findViewById(R.id.etPartOngkir);
        MaterialButtonToggleGroup toggle = card.findViewById(R.id.togglePartOngkir);
        toggle.check(Transaction.ONGKIR_PER_GALON.equals(part.ongkirType) ? R.id.btnPartOngkirPerGalon
                : Transaction.ONGKIR_BORONGAN.equals(part.ongkirType) ? R.id.btnPartOngkirBorongan
                : R.id.btnPartOngkirNone);
        syncOngkirField(pv);
        toggle.addOnButtonCheckedListener((g, id, checked) -> {
            if (!checked) return;
            String type = id == R.id.btnPartOngkirPerGalon ? Transaction.ONGKIR_PER_GALON
                    : id == R.id.btnPartOngkirBorongan ? Transaction.ONGKIR_BORONGAN
                    : Transaction.ONGKIR_NONE;
            if (type.equals(part.ongkirType)) return;
            part.ongkirType = type;
            part.ongkirEdited = false;
            applyOngkirDefault(part);
            syncOngkirField(pv);
            refreshAll();
        });
        pv.etOngkir.addTextChangedListener(new SimpleWatcher(() -> {
            if (pv.syncing) return;
            part.ongkirRate = parseDouble(pv.etOngkir, 0);
            part.ongkirEdited = true;
            refreshAll();
        }));

        // Galon kembali (hanya "Di Pinjam").
        View layoutKembali = card.findViewById(R.id.layoutPartKembali);
        layoutKembali.setVisibility(cfg.showKembali ? View.VISIBLE : View.GONE);
        pv.etKembali = card.findViewById(R.id.etPartKembali);
        setText(pv, pv.etKembali, String.valueOf(part.kembali));
        pv.etKembali.addTextChangedListener(new SimpleWatcher(() -> {
            if (pv.syncing) return;
            part.kembali = Math.max(0, parseInt(pv.etKembali, 0));
            part.kembaliEdited = true;
            recomputeKembaliDefaults();
            refreshAll();
        }));
        card.findViewById(R.id.btnPartKembaliMinus).setOnClickListener(v -> {
            int k = parseInt(pv.etKembali, 0);
            if (k > 0) pv.etKembali.setText(String.valueOf(k - 1));
        });
        card.findViewById(R.id.btnPartKembaliPlus).setOnClickListener(v ->
                pv.etKembali.setText(String.valueOf(parseInt(pv.etKembali, 0) + 1)));

        // Perangkat pengantar.
        View layoutDevice = card.findViewById(R.id.layoutPartDevice);
        Spinner spinner = card.findViewById(R.id.spinnerPartDevice);
        if (cfg.deviceUuids != null && cfg.deviceLabels != null && cfg.deviceUuids.size() > 1) {
            layoutDevice.setVisibility(View.VISIBLE);
            bindDeviceSpinner(spinner, part);
        } else {
            layoutDevice.setVisibility(View.GONE);
            part.assignedDeviceUuid = null;
        }

        // Catatan khusus lokasi ini.
        EditText etNote = card.findViewById(R.id.etPartNote);
        etNote.setText(part.note != null ? part.note : "");
        etNote.addTextChangedListener(new SimpleWatcher(() ->
                part.note = etNote.getText() != null ? etNote.getText().toString() : ""));

        pv.tvWarn = card.findViewById(R.id.tvPartWarn);
        pv.tvSubtotal = card.findViewById(R.id.tvPartSubtotal);
        return card;
    }

    private View buildProductRow(ViewGroup parent, PartView pv, Product p) {
        View row = LayoutInflater.from(activity).inflate(R.layout.item_checkout_part_product, parent, false);
        CheckoutPart part = pv.part;
        TextView tvDot = row.findViewById(R.id.tvPpDot);
        try {
            tvDot.setTextColor(p.getColor() != null && !p.getColor().isEmpty()
                    ? Color.parseColor(p.getColor()) : Color.parseColor("#1565C0"));
        } catch (Exception e) {
            tvDot.setTextColor(Color.parseColor("#1565C0"));
        }
        ((TextView) row.findViewById(R.id.tvPpName)).setText(p.getName());
        EditText etPrice = row.findViewById(R.id.etPpPrice);
        EditText etQty = row.findViewById(R.id.etPpQty);
        // Nilai awal SEBELUM watcher dipasang supaya tak memicu hitung ulang saat inisialisasi.
        etPrice.setText(String.valueOf((long) part.price(p.getId())));
        etQty.setText(String.valueOf(part.qty(p.getId())));
        etPrice.addTextChangedListener(new SimpleWatcher(() -> {
            part.setPrice(p.getId(), parseDouble(etPrice, 0));
            onQtyOrPriceChanged(pv);
        }));
        etQty.addTextChangedListener(new SimpleWatcher(() -> {
            part.setQty(p.getId(), parseInt(etQty, 0));
            onQtyOrPriceChanged(pv);
        }));
        row.findViewById(R.id.btnPpMinus).setOnClickListener(v -> {
            int q = parseInt(etQty, 0);
            if (q > 0) etQty.setText(String.valueOf(q - 1));
        });
        row.findViewById(R.id.btnPpPlus).setOnClickListener(v ->
                etQty.setText(String.valueOf(parseInt(etQty, 0) + 1)));
        return row;
    }

    /** Jumlah/harga berubah → borongan default ikut galon (bila belum diketik), kembali default dibagi ulang. */
    private void onQtyOrPriceChanged(PartView pv) {
        if (Transaction.ONGKIR_BORONGAN.equals(pv.part.ongkirType) && !pv.part.ongkirEdited) {
            applyOngkirDefault(pv.part);
            syncOngkirField(pv);
        }
        recomputeKembaliDefaults();
        refreshAll();
    }

    /**
     * Nominal ongkir default saat mode diganti — cermin updateOngkirUI/applyBoronganDefault order
     * tunggal: Per Botol = tarif lokasi (tanpa tarif → default Pengaturan), Borongan = galon × tarif itu.
     */
    private void applyOngkirDefault(CheckoutPart part) {
        double rate = CheckoutPart.locationRate(part.loc);
        double perGalon = rate > 0 ? rate : Math.max(0, cfg.defaultOngkir);
        if (Transaction.ONGKIR_PER_GALON.equals(part.ongkirType)) {
            part.ongkirRate = perGalon;
        } else if (Transaction.ONGKIR_BORONGAN.equals(part.ongkirType)) {
            part.ongkirRate = perGalon * part.galon();
        }
    }

    private void syncOngkirField(PartView pv) {
        boolean none = Transaction.ONGKIR_NONE.equals(pv.part.ongkirType);
        pv.layoutOngkirValue.setVisibility(none ? View.GONE : View.VISIBLE);
        if (!none) {
            long v = Math.round(Math.max(0, pv.part.ongkirRate));
            setText(pv, pv.etOngkir, v > 0 ? String.valueOf(v) : "");
        }
    }

    /** Galon kembali default (belum diketik operator) dibagi rakus dari sisa galon dipinjam. */
    private void recomputeKembaliDefaults() {
        if (!cfg.showKembali) return;
        int heldLeft = Math.max(0, cfg.held);
        List<PartView> auto = new ArrayList<>();
        for (PartView pv : views) {
            if (pv.part.kembaliEdited) heldLeft -= Math.max(0, pv.part.kembali);
            else auto.add(pv);
        }
        int[] galons = new int[auto.size()];
        for (int i = 0; i < auto.size(); i++) galons[i] = auto.get(i).part.galon();
        int[] def = CheckoutPart.defaultKembali(galons, Math.max(0, heldLeft));
        for (int i = 0; i < auto.size(); i++) {
            PartView pv = auto.get(i);
            pv.part.kembali = def[i];
            if (pv.etKembali != null && parseInt(pv.etKembali, -1) != def[i]) {
                setText(pv, pv.etKembali, String.valueOf(def[i]));
            }
        }
    }

    private void bindDeviceSpinner(Spinner spinner, CheckoutPart part) {
        ArrayAdapter<String> ad = new ArrayAdapter<>(activity,
                android.R.layout.simple_spinner_item, cfg.deviceLabels);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(ad);
        int pos = part.assignedDeviceUuid != null ? cfg.deviceUuids.indexOf(part.assignedDeviceUuid) : 0;
        if (pos < 0) {   // perangkat lama tak ada lagi di roster → kembali ke perangkat ini
            pos = 0;
            part.assignedDeviceUuid = null;
        }
        spinner.setSelection(pos, false);
        final int[] prev = {pos};
        final boolean[] touched = {false};
        final boolean[] suppress = {false};
        spinner.setOnTouchListener((v, ev) -> {
            touched[0] = true;
            return false;
        });
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int position, long id) {
                if (suppress[0]) {
                    suppress[0] = false;
                    prev[0] = position;
                    return;
                }
                boolean user = touched[0];
                touched[0] = false;
                String uuid = position > 0 && position < cfg.deviceUuids.size() ? cfg.deviceUuids.get(position) : null;
                boolean openDispatch = uuid != null && uuid.equals(cfg.openDispatchUuid);
                if (user && openDispatch) {
                    new AlertDialog.Builder(activity)
                            .setTitle("🎲 Pesanan Terbuka (Lelang)")
                            .setMessage("Lokasi \"" + part.name() + "\" tidak diarahkan ke satu perangkat — staf "
                                    + "mana pun yang mencentang \"Antrian Perangkat Lain\" boleh mengklaimnya lewat "
                                    + "\"Ambil Alih\".")
                            .setPositiveButton("Mengerti", null)
                            .show();
                } else if (user && uuid != null && cfg.confirmOtherDevice && !cfg.otherDeviceConfirmed[0]) {
                    // STAF/MARKETING: kredit galon & komisi lokasi ini pindah ke staf lain — tanya SEKALI
                    // per checkout (pilihan berikutnya di lokasi lain tak ditanya ulang).
                    final int from = prev[0];
                    new AlertDialog.Builder(activity)
                            .setTitle("Alihkan Penugasan ke Staf Lain?")
                            .setMessage("Lokasi \"" + part.name() + "\" akan DITUGASKAN ke \""
                                    + cfg.deviceLabels.get(position) + "\" — bukan kamu sendiri.\n\n"
                                    + "Kredit galon & komisi lokasi ini akan masuk ke sana, bukan ke kamu. Lanjutkan?")
                            .setCancelable(false)
                            .setPositiveButton("YA", (d, w) -> {
                                cfg.otherDeviceConfirmed[0] = true;
                                part.assignedDeviceUuid = uuid;
                                prev[0] = position;
                            })
                            .setNegativeButton("BATAL", (d, w) -> {
                                suppress[0] = true;
                                spinner.setSelection(from);
                            })
                            .show();
                    return;
                }
                part.assignedDeviceUuid = uuid;
                prev[0] = position;
            }

            @Override public void onNothingSelected(AdapterView<?> p) {}
        });
    }

    // ------------------------------------------------------------------ ringkasan

    private void refreshAll() {
        int galon = 0, active = 0;
        for (PartView pv : views) {
            CheckoutPart p = pv.part;
            if (pv.tvSubtotal != null) {
                if (p.isActive()) {
                    pv.tvSubtotal.setText(p.galon() + " galon · Subtotal Rp "
                            + nf.format(Math.round(p.total(cfg.beli, cfg.hargaBotol))));
                    pv.tvSubtotal.setTextColor(ContextCompat.getColor(activity, R.color.primary));
                } else {
                    pv.tvSubtotal.setText("Tidak ikut (0 galon)");
                    pv.tvSubtotal.setTextColor(ContextCompat.getColor(activity, R.color.text_secondary));
                }
            }
            if (pv.tvWarn != null) {
                String warn = null;
                if (p.isActive() && p.ongkirMissing()) {
                    warn = "⚠️ Lokasi ini wajib ongkir, tetapi bagian ini tanpa ongkir (Rp 0).";
                } else if (p.isActive() && p.ongkirUnexpected()) {
                    warn = "⚠️ Lokasi ini bebas ongkir, tetapi bagian ini ditagih ongkir Rp "
                            + nf.format(Math.round(p.ongkirTotal())) + ".";
                }
                pv.tvWarn.setText(warn != null ? warn : "");
                pv.tvWarn.setVisibility(warn != null ? View.VISIBLE : View.GONE);
            }
            if (p.isActive()) {
                active++;
                galon += p.galon();
            }
        }
        if (tvGrandTotal != null) {
            tvGrandTotal.setText("Rp " + nf.format(Math.round(CheckoutPart.grandTotal(parts, cfg.beli, cfg.hargaBotol))));
        }
        if (tvCount != null) tvCount.setText("Total · " + active + " lokasi · " + galon + " galon");
        if (tvHint != null) {
            boolean few = active < CheckoutConstants.MIN_LEGS;
            tvHint.setText("Isi galon minimal di " + CheckoutConstants.MIN_LEGS
                    + " lokasi — untuk satu lokasi saja, pakai order biasa.");
            tvHint.setTextColor(Color.parseColor("#B45309"));
            tvHint.setVisibility(few ? View.VISIBLE : View.GONE);
        }
    }

    // ------------------------------------------------------------------ util

    private static void setText(PartView pv, EditText et, String text) {
        pv.syncing = true;
        try {
            et.setText(text);
        } finally {
            pv.syncing = false;
        }
    }

    private static int parseInt(EditText et, int def) {
        String s = et.getText() != null ? et.getText().toString().trim() : "";
        if (s.isEmpty()) return def;
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static double parseDouble(EditText et, double def) {
        String s = et.getText() != null ? et.getText().toString().trim() : "";
        if (s.isEmpty()) return def;
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** TextWatcher satu-aksi (afterTextChanged). */
    private static final class SimpleWatcher implements TextWatcher {
        private final Runnable onChange;

        SimpleWatcher(Runnable onChange) {
            this.onChange = onChange;
        }

        @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}

        @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}

        @Override public void afterTextChanged(Editable s) {
            onChange.run();
        }
    }
}
