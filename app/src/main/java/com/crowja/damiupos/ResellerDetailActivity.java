package com.crowja.damiupos;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.crowja.damiupos.db.CustomerDao;
import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.ExpenseDao;
import com.crowja.damiupos.db.ProductDao;
import com.crowja.damiupos.db.ResellerKomisiCalculator;
import com.crowja.damiupos.db.ResellerRateDao;
import com.crowja.damiupos.db.ResellerWithdrawalDao;
import com.crowja.damiupos.db.SettingsDao;
import com.crowja.damiupos.model.Customer;
import com.crowja.damiupos.model.Expense;
import com.crowja.damiupos.model.Product;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.io.File;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Detail pendapatan satu reseller: total komisi, sudah dicairkan, saldo,
 * riwayat pencairan + tombol "Cairkan Komisi" (air minum atau uang).
 */
public class ResellerDetailActivity extends AppCompatActivity {

    public static final String EXTRA_CUSTOMER_ID = "customer_id";

    private long customerId;
    private CustomerDao customerDao;
    private ResellerWithdrawalDao wdDao;
    private ExpenseDao expenseDao;
    private SettingsDao settingsDao;
    private ProductDao productDao;
    private ResellerRateDao rateDao;
    private DatabaseHelper dbHelper;

    private double saldo; // saldo komisi terkini (untuk validasi cairkan)
    private String resellerName = ""; // untuk label expense pencairan
    private Customer currentReseller; // reseller yang sedang dilihat (untuk PDF rekap)
    private com.google.android.material.checkbox.MaterialCheckBox cbKomisiKeHarga;
    private boolean bindingCb = false; // suppress listener saat set state dari DB

    private static final NumberFormat NF = NumberFormat.getInstance(new Locale("id", "ID"));

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reseller_detail);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        customerId = getIntent().getLongExtra(EXTRA_CUSTOMER_ID, -1);
        if (customerId <= 0) {
            Toast.makeText(this, "Reseller tidak valid", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        dbHelper = DatabaseHelper.getInstance(this);
        customerDao = new CustomerDao(dbHelper);
        wdDao = new ResellerWithdrawalDao(dbHelper);
        expenseDao = new ExpenseDao(dbHelper);
        settingsDao = new SettingsDao(dbHelper);
        productDao = new ProductDao(dbHelper);
        rateDao = new ResellerRateDao(dbHelper);

        RecyclerView rv = findViewById(R.id.rvWithdrawals);
        rv.setLayoutManager(new LinearLayoutManager(this));
        RecyclerView rvKomisi = findViewById(R.id.rvKomisi);
        rvKomisi.setLayoutManager(new LinearLayoutManager(this));

        // Kelola reseller (Pencairan komisi + Atur Komisi): hanya Admin/SPV. Staf/Marketing/Viewer
        // hanya boleh MELIHAT detail → tombol pengubah disembunyikan.
        boolean canManage = canManageReseller();
        android.view.View btnCair = findViewById(R.id.btnCairkan);
        android.view.View btnAtur = findViewById(R.id.btnAturKomisi);
        if (canManage) {
            // Pencairan komisi: reuse layar Transaksi Baru dalam mode payout (bukan dialog).
            btnCair.setOnClickListener(v -> {
                if (saldo <= 0) {
                    Toast.makeText(this, "Saldo komisi belum ada untuk dicairkan", Toast.LENGTH_SHORT).show();
                    return;
                }
                startActivity(new Intent(this, TransactionActivity.class)
                        .putExtra(TransactionActivity.EXTRA_KOMISI_PAYOUT, true)
                        .putExtra(TransactionActivity.EXTRA_RESELLER_ID, customerId)
                        .putExtra(TransactionActivity.EXTRA_KOMISI_SALDO, saldo)
                        .putExtra(TransactionActivity.EXTRA_RESELLER_NAME, resellerName));
            });
            btnAtur.setOnClickListener(v -> showAturKomisiDialog());
        } else {
            btnCair.setVisibility(android.view.View.GONE);
            btnAtur.setVisibility(android.view.View.GONE);
        }

        // Buat transaksi baru dengan reseller ini sudah ter-set sebagai afiliasi.
        findViewById(R.id.btnBuatTransaksi).setOnClickListener(v ->
                startActivity(new Intent(this, TransactionActivity.class)
                        .putExtra(TransactionActivity.EXTRA_RESELLER_ID, customerId)));

        // Kirim rekap pendapatan (PDF, 30 hari terakhir).
        findViewById(R.id.btnKirimRekap).setOnClickListener(v -> kirimRekapPdf());

        // Checkbox "Tambahkan Komisi ke Harga Jual" — simpan langsung ke DB.
        cbKomisiKeHarga = findViewById(R.id.cbKomisiKeHarga);
        cbKomisiKeHarga.setOnCheckedChangeListener((b, checked) -> {
            if (bindingCb) return;
            customerDao.setKomisiAddToPrice(customerId, checked);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        // Cari reseller ini (dengan komisi_galon) dari query agregat.
        List<Customer> allResellers = customerDao.getResellers();
        Customer me = null;
        for (Customer c : allResellers) {
            if (c.getId() == customerId) { me = c; break; }
        }
        if (me == null) {
            // Sudah bukan reseller / terhapus
            Toast.makeText(this, "Pelanggan ini bukan reseller lagi", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        // Grup dedup orang ini (salinan lintas-perangkat, kunci sama dengan web) — saldo
        // OTORITATIF = Σ srv_saldo salinan (angka ResellerSaldo dashboard, lintas SEMUA
        // perangkat). Kalkulator lokal hanya melihat transaksi perangkat ini, jadi dipakai
        // untuk rincian info + fallback offline saja.
        String key = com.crowja.damiupos.db.CustomerDao.dedupKey(me);
        double saldoSrv = 0, earned = 0, withdrawn = 0, deposits = 0;
        int totalGalon = 0;
        ResellerKomisiCalculator.Result res = null;   // rincian per-transaksi utk salinan INI
        for (Customer c : allResellers) {
            if (!com.crowja.damiupos.db.CustomerDao.dedupKey(c).equals(key)) continue;
            saldoSrv += c.getSrvSaldo();
            ResellerKomisiCalculator.Result r = ResellerKomisiCalculator.hitung(dbHelper, c);
            if (c.getId() == customerId) res = r;
            earned += r.totalKomisi;
            for (ResellerKomisiCalculator.Entry e : r.entries) totalGalon += e.totalGalon;
            // Pisahkan pencairan (amount > 0) dari "tambah saldo" dashboard (amount < 0 = kredit).
            for (ResellerWithdrawalDao.Withdrawal w : wdDao.getByCustomer(c.getId())) {
                if (w.amount < 0) deposits += -w.amount;
                else withdrawn += w.amount;
            }
        }
        if (res == null) res = ResellerKomisiCalculator.hitung(dbHelper, me);
        boolean online = new com.crowja.damiupos.sync.SyncSettings(settingsDao).isEnrolled();
        saldo = online ? saldoSrv : (earned + deposits - withdrawn);

        currentReseller = me;
        resellerName = me.getName() != null ? me.getName() : "";
        // Sinkronkan checkbox komisi-ke-harga dengan state DB (tanpa trigger listener).
        bindingCb = true;
        cbKomisiKeHarga.setChecked(me.isKomisiAddToPrice());
        bindingCb = false;
        ((TextView) findViewById(R.id.tvResellerName)).setText(me.getName());
        ((TextView) findViewById(R.id.tvResellerSince)).setText(
                "Reseller sejak: " + formatDate(me.getResellerSince()));
        ((TextView) findViewById(R.id.tvTotalKomisi)).setText("Rp " + NF.format(Math.round(earned)));
        ((TextView) findViewById(R.id.tvDicairkan)).setText("Rp " + NF.format(Math.round(withdrawn)));
        ((TextView) findViewById(R.id.tvSaldo)).setText("Rp " + NF.format(Math.round(saldo)));
        TextView tvTambahSaldo = findViewById(R.id.tvTambahSaldo);
        if (deposits > 0) {
            tvTambahSaldo.setText("➕ Tambah saldo (dari dashboard): Rp " + NF.format(Math.round(deposits)));
            tvTambahSaldo.setVisibility(View.VISIBLE);
        } else {
            tvTambahSaldo.setVisibility(View.GONE);
        }
        ((TextView) findViewById(R.id.tvKomisiInfo)).setText(
                totalGalon + " galon terjual sejak jadi reseller  ·  default Rp "
                        + NF.format(Math.round(settingsDao.getResellerKomisi()))
                        + "/galon, bisa diatur per jenis air");

        // Riwayat pendapatan komisi per transaksi
        RecyclerView rvKomisi = findViewById(R.id.rvKomisi);
        rvKomisi.setAdapter(new KomisiAdapter(res.entries));
        findViewById(R.id.tvEmptyKomisi).setVisibility(
                res.entries.isEmpty() ? View.VISIBLE : View.GONE);
        rvKomisi.setVisibility(res.entries.isEmpty() ? View.GONE : View.VISIBLE);

        // Riwayat pencairan/top-up salinan INI (baris riwayat tetap per-copy seperti semula).
        List<ResellerWithdrawalDao.Withdrawal> wds = wdDao.getByCustomer(customerId);
        RecyclerView rv = findViewById(R.id.rvWithdrawals);
        rv.setAdapter(new WdAdapter(wds));
        findViewById(R.id.tvEmptyWd).setVisibility(wds.isEmpty() ? View.VISIBLE : View.GONE);
        rv.setVisibility(wds.isEmpty() ? View.GONE : View.VISIBLE);
    }

    // =======================================================================
    // Atur komisi per jenis air minum
    // =======================================================================

    /** Admin/SPV boleh kelola reseller; selain itu lihat-saja. Single-user (tanpa login) = penuh. */
    private boolean canManageReseller() {
        long uid = settingsDao.getCurrentUserId();
        if (uid <= 0) {
            return true;
        }
        com.crowja.damiupos.model.User u = new com.crowja.damiupos.db.UserDao(dbHelper).getById(uid);
        return u == null || u.canManageReseller();
    }

    private void showAturKomisiDialog() {
        List<Product> products = productDao.getAll();
        if (products.isEmpty()) {
            Toast.makeText(this, "Belum ada jenis air minum terdaftar",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        double globalRate = settingsDao.getResellerKomisi();
        java.util.Map<Long, Double> rates = rateDao.getRates(customerId);

        // Build form programmatically: 1 baris input per produk.
        android.widget.LinearLayout container = new android.widget.LinearLayout(this);
        container.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        container.setPadding(pad, pad / 2, pad, 0);

        TextView info = new TextView(this);
        info.setText("Kosongkan untuk pakai komisi default (Rp "
                + NF.format(Math.round(globalRate)) + "/galon).");
        info.setTextSize(12f);
        container.addView(info);

        List<TextInputEditText> inputs = new java.util.ArrayList<>();
        for (Product p : products) {
            TextInputLayout til = new TextInputLayout(this);
            til.setHint(p.getName() + " (Rp/galon)");
            TextInputEditText et = new TextInputEditText(til.getContext());
            et.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
            Double existing = rates.get(p.getId());
            if (existing != null) et.setText(String.valueOf(Math.round(existing)));
            til.addView(et);
            android.widget.LinearLayout.LayoutParams lp =
                    new android.widget.LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = pad / 2;
            container.addView(til, lp);
            inputs.add(et);
        }

        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(container);

        new AlertDialog.Builder(this)
                .setTitle("Komisi per Jenis Air")
                .setView(scroll)
                .setNegativeButton("Batal", null)
                .setPositiveButton("Simpan", (d, w) -> {
                    for (int i = 0; i < products.size(); i++) {
                        Product p = products.get(i);
                        String s = inputs.get(i).getText() != null
                                ? inputs.get(i).getText().toString().trim() : "";
                        if (s.isEmpty()) {
                            rateDao.deleteRate(customerId, p.getId()); // pakai default
                        } else {
                            try {
                                rateDao.setRate(customerId, p.getId(), Double.parseDouble(s));
                            } catch (NumberFormatException ignored) {}
                        }
                    }
                    Toast.makeText(this, "Komisi tersimpan", Toast.LENGTH_SHORT).show();
                    refresh();
                })
                .show();
    }

    // =======================================================================
    // Cairkan dialog
    // =======================================================================

    private void showCairkanDialog() {
        if (saldo <= 0) {
            Toast.makeText(this, "Saldo komisi belum ada untuk dicairkan",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        View content = LayoutInflater.from(this).inflate(R.layout.dialog_cairkan, null, false);
        TextView tvSaldoInfo = content.findViewById(R.id.tvSaldoInfo);
        MaterialButtonToggleGroup toggle = content.findViewById(R.id.toggleWdType);
        TextInputLayout tilNominal = content.findViewById(R.id.tilNominal);
        TextInputEditText etNominal = content.findViewById(R.id.etNominal);
        View layoutAir = content.findViewById(R.id.layoutAir);
        TextInputEditText etGalonQty = content.findViewById(R.id.etGalonQty);
        TextInputEditText etNilaiPerGalon = content.findViewById(R.id.etNilaiPerGalon);
        TextView tvTotalAir = content.findViewById(R.id.tvTotalAir);
        TextInputEditText etNote = content.findViewById(R.id.etWdNote);

        tvSaldoInfo.setText("Saldo tersedia: Rp " + NF.format(Math.round(saldo)));

        // Prefill nilai/galon dengan harga jual produk pertama (kalau ada).
        double defaultNilai = 0;
        List<Product> products = productDao.getAll();
        if (!products.isEmpty()) defaultNilai = products.get(0).getHargaJual();
        etNilaiPerGalon.setText(String.valueOf(Math.round(defaultNilai)));

        // Toggle UANG <-> AIR
        toggle.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            boolean air = checkedId == R.id.btnTypeAir;
            layoutAir.setVisibility(air ? View.VISIBLE : View.GONE);
            tilNominal.setVisibility(air ? View.GONE : View.VISIBLE);
        });

        // Live total untuk AIR
        TextWatcher airWatcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                int qty = parseIntSafe(etGalonQty, 0);
                double nilai = parseDoubleSafe(etNilaiPerGalon, 0);
                tvTotalAir.setText("Total dipotong: Rp " + NF.format(Math.round(qty * nilai)));
            }
        };
        etGalonQty.addTextChangedListener(airWatcher);
        etNilaiPerGalon.addTextChangedListener(airWatcher);
        airWatcher.afterTextChanged(null);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(content)
                .setPositiveButton("Cairkan", null) // di-set ulang agar bisa cegah dismiss saat invalid
                .setNegativeButton("Batal", null)
                .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            boolean air = toggle.getCheckedButtonId() == R.id.btnTypeAir;
            String note = etNote.getText() != null ? etNote.getText().toString().trim() : "";

            if (air) {
                int qty = parseIntSafe(etGalonQty, 0);
                double nilai = parseDoubleSafe(etNilaiPerGalon, 0);
                double total = qty * nilai;
                if (qty <= 0) { etGalonQty.setError("Minimal 1 galon"); return; }
                if (nilai <= 0) { etNilaiPerGalon.setError("Nilai per galon wajib diisi"); return; }
                if (total > saldo) {
                    etGalonQty.setError("Melebihi saldo (Rp " + NF.format(Math.round(saldo)) + ")");
                    return;
                }
                long expIdAir = recordKomisiExpense(total, ResellerWithdrawalDao.TYPE_AIR, qty, note);
                wdDao.insert(customerId, ResellerWithdrawalDao.TYPE_AIR, qty, total, note, expIdAir);
                Toast.makeText(this, "Dicairkan " + qty + " galon air minum "
                        + "(Rp " + NF.format(Math.round(total)) + ")", Toast.LENGTH_LONG).show();
            } else {
                double nominal = parseDoubleSafe(etNominal, 0);
                if (nominal <= 0) { etNominal.setError("Nominal wajib diisi"); return; }
                if (nominal > saldo) {
                    etNominal.setError("Melebihi saldo (Rp " + NF.format(Math.round(saldo)) + ")");
                    return;
                }
                long expIdUang = recordKomisiExpense(nominal, ResellerWithdrawalDao.TYPE_UANG, 0, note);
                wdDao.insert(customerId, ResellerWithdrawalDao.TYPE_UANG, 0, nominal, note, expIdUang);
                Toast.makeText(this, "Dicairkan Rp " + NF.format(Math.round(nominal)),
                        Toast.LENGTH_LONG).show();
            }
            dialog.dismiss();
            refresh();
        });
    }

    /**
     * Catat pencairan komisi reseller sebagai pengeluaran (pengeluaran) depot.
     * Return id expense yang dibuat (di-link ke pencairan supaya bisa ikut
     * terhapus kalau pencairan dihapus).
     */
    private long recordKomisiExpense(double amount, String type, int galonQty, String note) {
        String name = "Komisi Reseller"
                + (resellerName != null && !resellerName.isEmpty() ? " - " + resellerName : "");
        StringBuilder n = new StringBuilder("Pencairan komisi reseller");
        if (ResellerWithdrawalDao.TYPE_AIR.equals(type)) {
            n.append(" (air: ").append(galonQty).append(" galon)");
        } else {
            n.append(" (uang tunai)");
        }
        if (note != null && !note.isEmpty()) n.append(" — ").append(note);
        return expenseDao.insert(new Expense(name, amount, null, n.toString()));
    }

    /**
     * Buat PDF rekap pendapatan reseller (komisi + riwayat 30 hari terakhir) di
     * background, lalu buka share sheet untuk dikirim (mis. WhatsApp ke reseller).
     */
    private void kirimRekapPdf() {
        final Customer r = currentReseller;
        if (r == null) {
            Toast.makeText(this, "Data reseller belum siap", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, "Menyiapkan rekap PDF…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            File pdf = ResellerRecapPdf.build(getApplicationContext(), dbHelper, r, 30);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (pdf != null && pdf.exists()) sharePdf(pdf, r);
                else Toast.makeText(this, "Gagal membuat rekap PDF", Toast.LENGTH_LONG).show();
            });
        }, "reseller-recap-pdf").start();
    }

    private void sharePdf(File file, Customer r) {
        try {
            Uri uri = FileProvider.getUriForFile(this,
                    getApplicationContext().getPackageName() + ".fileprovider", file);
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("application/pdf");
            share.putExtra(Intent.EXTRA_STREAM, uri);
            share.putExtra(Intent.EXTRA_SUBJECT, "Rekap Pendapatan Reseller - "
                    + (r.getName() != null ? r.getName() : ""));
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(share, "Kirim Rekap Pendapatan"));
        } catch (Throwable t) {
            Toast.makeText(this, "Gagal membagikan PDF", Toast.LENGTH_LONG).show();
        }
    }

    private static int parseIntSafe(TextInputEditText et, int def) {
        if (et == null || et.getText() == null) return def;
        try { return Integer.parseInt(et.getText().toString().trim()); }
        catch (NumberFormatException e) { return def; }
    }

    private static double parseDoubleSafe(TextInputEditText et, double def) {
        if (et == null || et.getText() == null) return def;
        try { return Double.parseDouble(et.getText().toString().trim()); }
        catch (NumberFormatException e) { return def; }
    }

    private static String formatDate(String iso) {
        if (iso == null || iso.isEmpty()) return "-";
        try {
            SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
            Date d = in.parse(iso.length() >= 19 ? iso.substring(0, 19) : iso);
            if (d == null) return iso;
            return new SimpleDateFormat("d MMM yyyy", new Locale("id", "ID")).format(d);
        } catch (Exception e) {
            return iso.length() >= 10 ? iso.substring(0, 10) : iso;
        }
    }

    // =======================================================================
    // Riwayat pendapatan komisi adapter
    // =======================================================================

    private class KomisiAdapter extends RecyclerView.Adapter<KomisiAdapter.VH> {
        private final List<ResellerKomisiCalculator.Entry> data;

        KomisiAdapter(List<ResellerKomisiCalculator.Entry> data) { this.data = data; }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_komisi, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            ResellerKomisiCalculator.Entry e = data.get(position);
            h.tvDetail.setText(e.detail);
            h.tvDate.setText(formatDate(e.tanggal));
            h.tvAmount.setText("+ Rp " + NF.format(Math.round(e.komisi)));
        }

        @Override
        public int getItemCount() { return data.size(); }

        class VH extends RecyclerView.ViewHolder {
            TextView tvDetail, tvDate, tvAmount;
            VH(View v) {
                super(v);
                tvDetail = v.findViewById(R.id.tvKomisiDetail);
                tvDate = v.findViewById(R.id.tvKomisiDate);
                tvAmount = v.findViewById(R.id.tvKomisiAmount);
            }
        }
    }

    // =======================================================================
    // Riwayat pencairan adapter
    // =======================================================================

    private class WdAdapter extends RecyclerView.Adapter<WdAdapter.VH> {
        private final List<ResellerWithdrawalDao.Withdrawal> data;

        WdAdapter(List<ResellerWithdrawalDao.Withdrawal> data) { this.data = data; }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_withdrawal, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            ResellerWithdrawalDao.Withdrawal w = data.get(position);
            boolean deposit = w.amount < 0;   // negatif = tambah saldo (dikirim admin via dashboard)
            boolean air = ResellerWithdrawalDao.TYPE_AIR.equals(w.type);
            double absAmount = Math.abs(w.amount);
            if (deposit) {
                h.tvIcon.setText("➕");
                h.tvTitle.setText("Tambah saldo");
            } else {
                h.tvIcon.setText(air ? "💧" : "💵");
                h.tvTitle.setText(air
                        ? "Air minum — " + w.galonQty + " galon"
                        : "Uang tunai");
            }
            String meta = formatDate(w.createdAt);
            if (w.note != null && !w.note.isEmpty()) meta += "  ·  " + w.note;
            h.tvMeta.setText(meta);
            h.tvAmount.setText((deposit ? "+ Rp " : "- Rp ") + NF.format(Math.round(absAmount)));

            // Long-press → hapus baris (koreksi salah input)
            h.itemView.setOnLongClickListener(v -> {
                new AlertDialog.Builder(ResellerDetailActivity.this)
                        .setTitle(deposit ? "Hapus tambah saldo?" : "Hapus pencairan?")
                        .setMessage((deposit
                                ? "Saldo akan berkurang sebesar Rp "
                                : "Saldo komisi akan dikembalikan sebesar Rp ")
                                + NF.format(Math.round(absAmount)) + ".")
                        .setPositiveButton("Hapus", (d, which) -> {
                            wdDao.delete(w.id);
                            // Hapus juga expense (pengeluaran) terkait pencairan ini.
                            if (w.expenseId > 0) expenseDao.delete(w.expenseId);
                            refresh();
                        })
                        .setNegativeButton("Batal", null)
                        .show();
                return true;
            });
        }

        @Override
        public int getItemCount() { return data.size(); }

        class VH extends RecyclerView.ViewHolder {
            TextView tvIcon, tvTitle, tvMeta, tvAmount;
            VH(View v) {
                super(v);
                tvIcon = v.findViewById(R.id.tvWdIcon);
                tvTitle = v.findViewById(R.id.tvWdTitle);
                tvMeta = v.findViewById(R.id.tvWdMeta);
                tvAmount = v.findViewById(R.id.tvWdAmount);
            }
        }
    }
}
