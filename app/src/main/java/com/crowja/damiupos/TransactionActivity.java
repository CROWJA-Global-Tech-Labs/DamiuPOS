package com.crowja.damiupos;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.crowja.damiupos.adapter.CustomerAdapter;
import com.crowja.damiupos.db.CustomerDao;
import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.ProductDao;
import com.crowja.damiupos.db.SettingsDao;
import com.crowja.damiupos.db.TransactionDao;
import com.crowja.damiupos.paywall.PaywallDialogFragment;
import com.crowja.damiupos.model.Customer;
import com.crowja.damiupos.model.Product;
import com.crowja.damiupos.model.Transaction;
import com.crowja.damiupos.model.TransactionItem;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class TransactionActivity extends AppCompatActivity {

    /** Intent extra: ParsedOrder JSON (dari OrderInbox). Kalau di-set,
     *  items hasil parser di-prefill ke form transaksi. */
    public static final String EXTRA_INBOX_PARSED_JSON = "inbox_parsed_json";

    /** Intent extra: nama pengirim WA dari OrderInbox. Kalau customer_id
     *  tidak di-set tapi sender_name ada → auto-open customer picker.
     *  Juga di-propagate ke ReceiptActivity supaya share dialog bisa
     *  lookup contacts dgn nama WA (yg mungkin beda dari nama DB). */
    public static final String EXTRA_INBOX_SENDER_NAME = "inbox_sender_name";

    /** Intent extra: nomor pengirim WA dari OrderInbox (yg sudah di-correct
     *  user di confirm-dialog Balas, kalau ada). Override DB customer phone. */
    public static final String EXTRA_INBOX_SENDER_PHONE = "inbox_sender_phone";

    /** Cached inbox sender name dari intent — di-pass ke ReceiptActivity. */
    private String forwardedInboxSenderName;

    private MaterialButtonToggleGroup toggleType, toggleOngkirMode, toggleOwnership, toggleReturnMode;
    private TextView tvSelectedCustomer, tvTotalHarga, tvEmptyItems;
    private TextInputEditText etJumlahKembali, etOngkir, etCatatan;
    private TextInputEditText etHargaGantiRugi, etJumlahRusak;
    private TextInputEditText etHargaBotol;
    private TextInputEditText etJumlahBotolJual, etHargaBotolJual;
    private TextInputLayout tilOngkir, tilJumlahKembali, tilHargaBotol;
    private View ongkirModeContainer, cardCustomer, cardItems, cardOwnership, gantiRugiContainer, returnModeContainer;
    private View cardJualBotol;
    private LinearLayout llItems;

    private boolean syncingKembali = false;
    private boolean userEditedKembali = false;
    private boolean syncingOngkir = false;
    private boolean userEditedOngkir = false;

    private CustomerDao customerDao;
    private ProductDao productDao;
    private TransactionDao transactionDao;
    private SettingsDao settingsDao;

    private long selectedCustomerId = -1;
    private String selectedCustomerName = "";
    private String selectedCustomerPhone = "";

    // Tanggal+waktu transaksi terpilih (yyyy-MM-dd HH:mm:ss). Default = sekarang.
    // Berlaku untuk Jual Air, Galon Kembali, dan Jual Botol.
    private String selectedTrxDate;
    private com.google.android.material.button.MaterialButton btnTanggalTrx;
    private final SimpleDateFormat trxDbFmt =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
    private final SimpleDateFormat trxDisplayFmt =
            new SimpleDateFormat("d MMM yyyy, HH:mm", new Locale("id", "ID"));

    private final List<TransactionItem> items = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_transaction);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        DatabaseHelper dbHelper = DatabaseHelper.getInstance(this);
        customerDao = new CustomerDao(dbHelper);
        productDao = new ProductDao(dbHelper);
        transactionDao = new TransactionDao(dbHelper);
        settingsDao = new SettingsDao(dbHelper);

        toggleType = findViewById(R.id.toggleType);
        tvSelectedCustomer = findViewById(R.id.tvSelectedCustomer);

        // Tanggal transaksi — default sekarang
        btnTanggalTrx = findViewById(R.id.btnTanggalTrx);
        selectedTrxDate = trxDbFmt.format(new Date());
        updateTanggalTrxButton();
        btnTanggalTrx.setOnClickListener(v -> showTrxDateTimePicker());

        tvTotalHarga = findViewById(R.id.tvTotalHarga);
        etJumlahKembali = findViewById(R.id.etJumlahKembali);
        etOngkir = findViewById(R.id.etOngkir);
        etCatatan = findViewById(R.id.etCatatan);
        tilOngkir = findViewById(R.id.tilOngkir);
        tilJumlahKembali = findViewById(R.id.tilJumlahKembali);
        toggleOngkirMode = findViewById(R.id.toggleOngkirMode);
        ongkirModeContainer = findViewById(R.id.ongkirModeContainer);
        cardCustomer = findViewById(R.id.cardCustomer);
        cardItems = findViewById(R.id.cardItems);
        llItems = findViewById(R.id.llItems);
        tvEmptyItems = findViewById(R.id.tvEmptyItems);
        gantiRugiContainer = findViewById(R.id.gantiRugiContainer);
        returnModeContainer = findViewById(R.id.returnModeContainer);
        toggleReturnMode = findViewById(R.id.toggleReturnMode);
        etHargaGantiRugi = findViewById(R.id.etHargaGantiRugi);
        etJumlahRusak = findViewById(R.id.etJumlahRusak);
        cardOwnership = findViewById(R.id.cardOwnership);
        toggleOwnership = findViewById(R.id.toggleOwnership);
        tilHargaBotol = findViewById(R.id.tilHargaBotol);
        etHargaBotol = findViewById(R.id.etHargaBotol);
        cardJualBotol = findViewById(R.id.cardJualBotol);
        etJumlahBotolJual = findViewById(R.id.etJumlahBotolJual);
        etHargaBotolJual = findViewById(R.id.etHargaBotolJual);

        // Prefill ganti rugi price from settings (default 35.000)
        etHargaGantiRugi.setText(String.valueOf((long) settingsDao.getHargaBotolGalon()));

        // Prefill harga botol dari settings
        etHargaBotol.setText(String.valueOf((long) settingsDao.getHargaBotolGalon()));

        // Prefill harga jual botol kosong dari settings
        etHargaBotolJual.setText(String.valueOf((long) settingsDao.getHargaBotolGalon()));

        // Restore last ownership state
        String lastOwnership = settingsDao.getLastGalonOwnership();
        if (Transaction.OWNERSHIP_BELI.equals(lastOwnership)) {
            toggleOwnership.check(R.id.btnOwnershipBeli);
        } else {
            toggleOwnership.check(R.id.btnOwnershipPinjam);
        }

        toggleOwnership.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (isChecked) {
                updateOwnershipUI();
                updateTotal();
            }
        });

        toggleOngkirMode.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (isChecked) updateOngkirUI();
        });

        toggleReturnMode.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (isChecked) {
                updateReturnModeUI();
                updateTotal();
            }
        });

        // Pre-select type from intent
        String type = getIntent().getStringExtra("type");
        if (Transaction.TYPE_KEMBALI.equals(type)) {
            toggleType.check(R.id.btnTypeKembali);
        } else if ("JUAL_BOTOL".equals(type)) {
            toggleType.check(R.id.btnTypeJualBotol);
        }

        long preCustomerId = getIntent().getLongExtra("customer_id", -1);
        if (preCustomerId != -1) {
            Customer c = customerDao.getById(preCustomerId);
            if (c != null) {
                selectedCustomerId = c.getId();
                selectedCustomerName = c.getName();
                selectedCustomerPhone = c.getPhone();
                tvSelectedCustomer.setText(c.getName());
            }
        }
        // Kalau dari Inbox & user sudah correct nomor di Balas confirm-dialog,
        // override DB phone dengan nomor yg di-confirm itu. Supaya struk + share
        // pakai nomor yg benar2 sesuai chat WA pelanggan.
        String inboxPhone = getIntent().getStringExtra(EXTRA_INBOX_SENDER_PHONE);
        if (inboxPhone != null && !inboxPhone.trim().isEmpty()) {
            selectedCustomerPhone = inboxPhone.trim();
        }
        // Default untuk Umum: jumlah kembali = 0, ownership = Beli
        // (override XML default "1" dan last-saved ownership)
        if (isUmumCustomer()) {
            syncingKembali = true;
            etJumlahKembali.setText("0");
            syncingKembali = false;
            toggleOwnership.check(R.id.btnOwnershipBeli);
        }
        // Sinkronkan visibility tombol ownership dengan tipe pelanggan
        // (Umum: Beli + Bawa Sendiri; Regular: Pinjam + Beli)
        updateOwnershipButtonsForCustomer();

        toggleType.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (isChecked) updateTypeUI();
        });

        cardCustomer.setOnClickListener(v -> showCustomerPicker());
        findViewById(R.id.btnAddItem).setOnClickListener(v -> showItemDialog(null, -1));

        TextWatcher calcWatcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) { updateTotal(); }
        };
        etOngkir.addTextChangedListener(calcWatcher);
        etHargaGantiRugi.addTextChangedListener(calcWatcher);
        etJumlahKembali.addTextChangedListener(calcWatcher);
        etJumlahRusak.addTextChangedListener(calcWatcher);
        etHargaBotol.addTextChangedListener(calcWatcher);
        etJumlahBotolJual.addTextChangedListener(calcWatcher);
        etHargaBotolJual.addTextChangedListener(calcWatcher);

        etOngkir.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                if (!syncingOngkir) userEditedOngkir = true;
            }
        });

        etJumlahKembali.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                if (!syncingKembali) userEditedKembali = true;
            }
        });

        findViewById(R.id.btnSimpan).setOnClickListener(v -> trySave());

        // Prefill items dari ParsedOrder kalau intent extra di-set
        // (caller: OrderInboxActivity.approve())
        String parsedJson = getIntent().getStringExtra(EXTRA_INBOX_PARSED_JSON);
        if (parsedJson != null && !parsedJson.isEmpty()) {
            prefillItemsFromInbox(parsedJson);
        }

        // Auto-open customer picker kalau dari Inbox tapi customer belum
        // ter-pilih (auto-match parser gagal). Berikan toast hint dgn
        // nama sender supaya user tahu siapa yg harus dipilih.
        String inboxSender = getIntent().getStringExtra(EXTRA_INBOX_SENDER_NAME);
        forwardedInboxSenderName = inboxSender; // simpan untuk dipass ke Receipt
        if (selectedCustomerId == -1 && inboxSender != null && !inboxSender.isEmpty()) {
            // Tampilkan label sementara di card pelanggan supaya user tahu
            // ini dari WA tapi belum match
            tvSelectedCustomer.setText("WA: " + inboxSender + " — pilih pelanggan");
            Toast.makeText(this, "Pilih pelanggan untuk pesanan dari " + inboxSender,
                    Toast.LENGTH_LONG).show();
            // Open picker setelah layout settle (post ke main handler)
            tvSelectedCustomer.post(this::showCustomerPicker);
        }

        refreshItemsView();
        updateTypeUI();
        updateOngkirUI();
        // Auto-fill "Jumlah Botol Galon Kembali" berdasarkan total qty items
        // (default behavior — pelanggan setor botol kosong sejumlah botol
        // baru yg dibawa). Untuk Umum/Beli/Botol Sendiri: di-skip via
        // updateOwnershipUI yg hide field-nya.
        syncKembaliToTotalJumlah();
        updateTotal();
    }

    /**
     * Tambahkan items hasil parser AI/regex dari OrderInbox ke form
     * transaksi. Match nama produk parser ke produk DB:
     * <ol>
     *   <li>Exact match (case-insensitive)</li>
     *   <li>Substring match (mis. "RO" → produk "Aqua RO 19L")</li>
     * </ol>
     * Item yang tidak match produk DB di-skip — user bisa tambah manual.
     */
    private void prefillItemsFromInbox(String parsedJson) {
        android.util.Log.d("TrxPrefill", "JSON: " + parsedJson);
        com.crowja.damiupos.wa.ParsedOrder parsed =
                com.crowja.damiupos.wa.ParsedOrder.fromJson(parsedJson);
        android.util.Log.d("TrxPrefill", "items=" + parsed.items.size()
                + " type=" + parsed.type);
        if (parsed.items.isEmpty()) return;

        List<Product> all = productDao.getAll();
        android.util.Log.d("TrxPrefill", "DB products=" + all.size());

        int matched = 0;
        int placeholder = 0;
        for (com.crowja.damiupos.wa.ParsedOrder.Item it : parsed.items) {
            if (it.qty <= 0 || it.product == null || it.product.isEmpty()) continue;
            Product match = findProductForLabel(it.product, all);
            if (match != null) {
                android.util.Log.d("TrxPrefill", "Match '" + it.product
                        + "' → DB '" + match.getName() + "' qty=" + it.qty);
                items.add(new TransactionItem(match.getId(), match.getName(),
                        it.qty, match.getHargaJual()));
                matched++;
            } else {
                // Tidak ada match di DB → tambahkan sebagai placeholder
                // dengan productId=0 + harga 0. User bisa tap item untuk
                // edit & pilih produk DB yang benar.
                android.util.Log.d("TrxPrefill", "Placeholder for '"
                        + it.product + "' qty=" + it.qty
                        + " (tidak match produk DB)");
                items.add(new TransactionItem(0L, it.product, it.qty, 0));
                placeholder++;
            }
        }
        android.util.Log.d("TrxPrefill", "Done: matched=" + matched
                + " placeholder=" + placeholder + " total=" + items.size());

        if (!items.isEmpty()) userEditedKembali = false;

        if (placeholder > 0) {
            String msg = all.isEmpty()
                    ? "Belum ada Jenis Air di DB — tap item untuk pilih produk + isi harga"
                    : placeholder + " item belum match produk DB — tap item untuk edit produk & harga";
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Cari produk DB yang cocok dengan label parsed.
     * Strategi: exact (case-insensitive) → substring keduanya arah.
     */
    private static Product findProductForLabel(String label, List<Product> all) {
        if (label == null) return null;
        String needle = label.trim().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) return null;
        // 1. Exact match
        for (Product p : all) {
            if (p.getName() != null && p.getName().equalsIgnoreCase(label)) {
                return p;
            }
        }
        // 2. Produk DB nama-nya contains label (mis. label "RO" → "Aqua RO 19L")
        for (Product p : all) {
            if (p.getName() != null
                    && p.getName().toLowerCase(Locale.ROOT).contains(needle)) {
                return p;
            }
        }
        // 3. Sebaliknya: label contains nama produk DB
        for (Product p : all) {
            if (p.getName() == null) continue;
            String pn = p.getName().toLowerCase(Locale.ROOT);
            if (!pn.isEmpty() && needle.contains(pn)) return p;
        }
        return null;
    }

    private void updateTypeUI() {
        boolean isJual = isJualSelected();
        boolean isJualBotol = isJualBotolSelected();
        if (isJualBotol) {
            // Mode: Jual Botol Galon Kosong saja (tanpa air)
            cardJualBotol.setVisibility(View.VISIBLE);
            cardItems.setVisibility(View.GONE);
            cardOwnership.setVisibility(View.GONE);
            tilJumlahKembali.setVisibility(View.GONE);
            tilOngkir.setVisibility(View.GONE);
            ongkirModeContainer.setVisibility(View.GONE);
            returnModeContainer.setVisibility(View.GONE);
            gantiRugiContainer.setVisibility(View.GONE);
        } else if (isJual) {
            cardJualBotol.setVisibility(View.GONE);
            cardItems.setVisibility(View.VISIBLE);
            tilJumlahKembali.setVisibility(View.VISIBLE);
            tilJumlahKembali.setHint("Jumlah Botol Galon Kembali");
            ongkirModeContainer.setVisibility(View.VISIBLE);
            returnModeContainer.setVisibility(View.GONE);
            gantiRugiContainer.setVisibility(View.GONE);
            cardOwnership.setVisibility(View.VISIBLE);
            updateOngkirUI();
            updateOwnershipUI();
        } else {
            cardJualBotol.setVisibility(View.GONE);
            cardItems.setVisibility(View.GONE);
            tilJumlahKembali.setVisibility(View.VISIBLE);
            tilJumlahKembali.setHint("Jumlah Botol Galon Kembali");
            tilOngkir.setVisibility(View.GONE);
            ongkirModeContainer.setVisibility(View.GONE);
            returnModeContainer.setVisibility(View.VISIBLE);
            cardOwnership.setVisibility(View.GONE);
            updateReturnModeUI();
        }
        updateTotal();
    }

    private boolean isGantiRugiSelected() {
        return toggleReturnMode.getCheckedButtonId() == R.id.btnReturnGantiRugi;
    }

    private void updateReturnModeUI() {
        gantiRugiContainer.setVisibility(isGantiRugiSelected() ? View.VISIBLE : View.GONE);
    }

    private boolean isBeliSelected() {
        return toggleOwnership.getCheckedButtonId() == R.id.btnOwnershipBeli;
    }

    private boolean isBawaSendiriSelected() {
        return toggleOwnership.getCheckedButtonId() == R.id.btnOwnershipBawaSendiri;
    }

    /** Resolve current toggle into the canonical OWNERSHIP_* constant. */
    private String getSelectedOwnership() {
        int id = toggleOwnership.getCheckedButtonId();
        if (id == R.id.btnOwnershipBeli) return Transaction.OWNERSHIP_BELI;
        if (id == R.id.btnOwnershipBawaSendiri) return Transaction.OWNERSHIP_BAWA_SENDIRI;
        return Transaction.OWNERSHIP_PINJAM;
    }

    private void updateOwnershipUI() {
        // Harga botol input hanya relevan kalau pelanggan beli botolnya;
        // pinjam atau botol sendiri tidak ditagih harga botol.
        tilHargaBotol.setVisibility(isBeliSelected() ? View.VISIBLE : View.GONE);
        // "Jumlah Botol Galon Kembali" hanya relevan untuk mode PINJAM —
        //   - PINJAM: pelanggan harus kembalikan botol → field perlu
        //   - BELI:   pelanggan beli botol langsung → tidak ada yg dikembalikan
        //   - BOTOL SENDIRI: pelanggan pakai botol sendiri → tidak ada yg dikembalikan
        if (isJualSelected()) {
            boolean hideKembali = !isPinjamSelected();
            tilJumlahKembali.setVisibility(hideKembali ? View.GONE : View.VISIBLE);
            if (hideKembali) {
                syncingKembali = true;
                etJumlahKembali.setText("0");
                syncingKembali = false;
                userEditedKembali = false;
            }
        }
    }

    /** True kalau toggle ownership saat ini = "Di Pinjam". */
    private boolean isPinjamSelected() {
        return toggleOwnership.getCheckedButtonId() == R.id.btnOwnershipPinjam;
    }

    /**
     * Tampilkan SEMUA opsi ownership untuk semua tipe pelanggan:
     * "Di Pinjam" / "Di Beli" / "Botol Sendiri". Default ownership untuk
     * Umum tetap di-set ke "Di Beli" (di {@code applySelectedCustomer}),
     * tapi user bebas pilih ketiganya kapan saja.
     */
    private void updateOwnershipButtonsForCustomer() {
        View btnPinjam = findViewById(R.id.btnOwnershipPinjam);
        View btnBawaSendiri = findViewById(R.id.btnOwnershipBawaSendiri);
        btnPinjam.setVisibility(View.VISIBLE);
        btnBawaSendiri.setVisibility(View.VISIBLE);
    }

    private boolean isJualSelected() {
        return toggleType.getCheckedButtonId() == R.id.btnTypeJual;
    }

    private boolean isJualBotolSelected() {
        return toggleType.getCheckedButtonId() == R.id.btnTypeJualBotol;
    }

    private String getSelectedOngkirType() {
        int id = toggleOngkirMode.getCheckedButtonId();
        if (id == R.id.btnOngkirNone) return Transaction.ONGKIR_NONE;
        if (id == R.id.btnOngkirBorongan) return Transaction.ONGKIR_BORONGAN;
        return Transaction.ONGKIR_PER_GALON;
    }

    private void updateOngkirUI() {
        String mode = getSelectedOngkirType();
        if (Transaction.ONGKIR_NONE.equals(mode)) {
            tilOngkir.setVisibility(View.GONE);
            userEditedOngkir = false;
        } else {
            tilOngkir.setVisibility(View.VISIBLE);
            if (Transaction.ONGKIR_BORONGAN.equals(mode)) {
                tilOngkir.setHint("Ongkos Kirim Borongan");
                userEditedOngkir = false;
                applyBoronganDefault();
            } else {
                tilOngkir.setHint("Ongkos Kirim / Botol Galon");
                userEditedOngkir = false;
                double defaultOngkir = settingsDao.getDefaultOngkir();
                syncingOngkir = true;
                etOngkir.setText(defaultOngkir > 0
                        ? String.valueOf((long) defaultOngkir) : "");
                syncingOngkir = false;
            }
        }
        updateTotal();
    }

    private int getTotalJumlah() {
        int sum = 0;
        for (TransactionItem it : items) sum += it.jumlah;
        return sum;
    }

    private void applyBoronganDefault() {
        double defaultOngkir = settingsDao.getDefaultOngkir();
        double borongan = getTotalJumlah() * defaultOngkir;
        syncingOngkir = true;
        etOngkir.setText(borongan > 0 ? String.valueOf((long) borongan) : "");
        syncingOngkir = false;
    }

    private void updateTotal() {
        if (isJualBotolSelected()) {
            int qty = parseIntOr(etJumlahBotolJual, 0);
            double harga = parseDoubleOr(etHargaBotolJual, 0);
            double total = qty * harga;
            NumberFormat nfb = NumberFormat.getInstance(new Locale("id", "ID"));
            tvTotalHarga.setText("Rp " + nfb.format(total));
            return;
        }
        if (!isJualSelected()) {
            // KEMBALI mode
            NumberFormat nfK = NumberFormat.getInstance(new Locale("id", "ID"));
            if (!isGantiRugiSelected()) {
                // Dikembalikan: tidak ada biaya, total selalu 0
                tvTotalHarga.setText("Rp 0");
                return;
            }
            // Ganti rugi: total = jumlah_rusak * harga ganti rugi
            double harga = parseDoubleOr(etHargaGantiRugi, 0);
            int jumlahKembali = parseIntOr(etJumlahKembali, 0);
            int jumlahRusak = parseIntOr(etJumlahRusak, -1);
            if (jumlahRusak < 0) jumlahRusak = jumlahKembali;
            if (jumlahRusak > jumlahKembali) jumlahRusak = jumlahKembali;
            double total = harga * jumlahRusak;
            tvTotalHarga.setText("Rp " + nfK.format(total));
            return;
        }
        double subtotal = 0;
        for (TransactionItem it : items) subtotal += it.getSubtotal();

        double ongkir = 0;
        String ongkirStr = etOngkir.getText() != null
                ? etOngkir.getText().toString().trim() : "";
        if (!ongkirStr.isEmpty()) {
            try { ongkir = Double.parseDouble(ongkirStr); } catch (NumberFormatException ignored) {}
        }
        String mode = getSelectedOngkirType();
        double total;
        if (Transaction.ONGKIR_BORONGAN.equals(mode)) {
            total = subtotal + ongkir;
        } else if (Transaction.ONGKIR_NONE.equals(mode)) {
            total = subtotal;
        } else {
            total = subtotal + ongkir * getTotalJumlah();
        }
        // Tambahkan harga botol × jumlah jika pelanggan membeli botol galon
        if (isBeliSelected()) {
            double hargaBotol = parseDoubleOr(etHargaBotol, 0);
            total += hargaBotol * getTotalJumlah();
        }
        NumberFormat nf = NumberFormat.getInstance(new Locale("id", "ID"));
        tvTotalHarga.setText("Rp " + nf.format(total));
    }

    // --- Items UI ---

    private void refreshItemsView() {
        llItems.removeAllViews();
        NumberFormat nf = NumberFormat.getInstance(new Locale("id", "ID"));
        for (int i = 0; i < items.size(); i++) {
            final int idx = i;
            TransactionItem it = items.get(i);
            View row = LayoutInflater.from(this).inflate(R.layout.item_trx_line, llItems, false);
            TextView tvName = row.findViewById(R.id.tvItemName);
            TextView tvCalc = row.findViewById(R.id.tvItemCalc);
            TextView tvSub = row.findViewById(R.id.tvItemSubtotal);
            ImageButton btnDel = row.findViewById(R.id.btnItemDelete);
            tvName.setText(it.productName);
            tvCalc.setText(it.jumlah + " x Rp " + nf.format(it.hargaPerGalon));
            tvSub.setText("Rp " + nf.format(it.getSubtotal()));
            row.setOnClickListener(v -> showItemDialog(items.get(idx), idx));
            btnDel.setOnClickListener(v -> {
                items.remove(idx);
                refreshItemsView();
                syncKembaliToTotalJumlah();
                if (Transaction.ONGKIR_BORONGAN.equals(getSelectedOngkirType()) && !userEditedOngkir) {
                    applyBoronganDefault();
                }
                updateTotal();
            });
            llItems.addView(row);
        }
        tvEmptyItems.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void syncKembaliToTotalJumlah() {
        if (!userEditedKembali) {
            syncingKembali = true;
            // Pelanggan "Umum" (walk-in) jarang punya botol galon untuk
            // ditukar — default-nya 0 botol kembali, bukan auto-match
            // dengan jumlah jual.
            etJumlahKembali.setText(isUmumCustomer()
                    ? "0"
                    : String.valueOf(getTotalJumlah()));
            syncingKembali = false;
        }
    }

    private boolean isUmumCustomer() {
        return CustomerDao.UMUM_NAME.equals(selectedCustomerName);
    }

    private void showItemDialog(TransactionItem editing, int editIdx) {
        List<Product> products = productDao.getAll();
        if (products.isEmpty()) {
            Toast.makeText(this,
                    "Belum ada jenis air. Tambahkan di menu Jenis Air Minum.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_item_trx, null);
        TextView tvProduct = dialogView.findViewById(R.id.tvProduct);
        EditText etQty = dialogView.findViewById(R.id.etQty);
        EditText etPrice = dialogView.findViewById(R.id.etPrice);

        final long[] chosenPid = {0};
        final String[] chosenName = {""};

        if (editing != null) {
            chosenPid[0] = editing.productId;
            chosenName[0] = editing.productName;
            tvProduct.setText(editing.productName);
            etQty.setText(String.valueOf(editing.jumlah));
            etPrice.setText(String.valueOf((long) editing.hargaPerGalon));
        } else {
            etQty.setText("1");
        }

        tvProduct.setOnClickListener(v -> {
            CharSequence[] names = new CharSequence[products.size()];
            NumberFormat nf = NumberFormat.getInstance(new Locale("id", "ID"));
            for (int i = 0; i < products.size(); i++) {
                Product p = products.get(i);
                int color;
                try {
                    color = p.getColor() != null && !p.getColor().isEmpty()
                            ? Color.parseColor(p.getColor())
                            : Color.parseColor("#1565C0");
                } catch (Exception e) {
                    color = Color.parseColor("#1565C0");
                }
                SpannableStringBuilder sb = new SpannableStringBuilder();
                int dotStart = sb.length();
                sb.append("\u25CF  ");
                sb.setSpan(new ForegroundColorSpan(color), dotStart, dotStart + 1,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new RelativeSizeSpan(1.4f), dotStart, dotStart + 1,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                int nameStart = sb.length();
                sb.append(p.getName());
                sb.setSpan(new ForegroundColorSpan(color), nameStart, sb.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new StyleSpan(android.graphics.Typeface.BOLD),
                        nameStart, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.append("  — Rp ").append(nf.format(p.getHargaJual()));
                names[i] = sb;
            }
            new AlertDialog.Builder(this)
                    .setTitle("Pilih Jenis Air")
                    .setItems(names, (d, w) -> {
                        Product p = products.get(w);
                        chosenPid[0] = p.getId();
                        chosenName[0] = p.getName();
                        tvProduct.setText(p.getName());
                        if (etPrice.getText().toString().trim().isEmpty()) {
                            etPrice.setText(String.valueOf((long) p.getHargaJual()));
                        }
                    })
                    .show();
        });

        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(editing == null ? "Tambah Item" : "Edit Item")
                .setView(dialogView)
                .setPositiveButton(editing == null ? "Tambah" : "Simpan", null)
                .setNegativeButton("Batal", null);
        AlertDialog dialog = b.create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    if (chosenPid[0] <= 0) {
                        Toast.makeText(this, "Pilih jenis air", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    int qty;
                    double price;
                    try {
                        qty = Integer.parseInt(etQty.getText().toString().trim());
                        price = Double.parseDouble(etPrice.getText().toString().trim());
                    } catch (NumberFormatException e) {
                        Toast.makeText(this, "Angka tidak valid", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (qty <= 0) {
                        Toast.makeText(this, "Jumlah harus > 0", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    TransactionItem newItem = new TransactionItem(
                            chosenPid[0], chosenName[0], qty, price);
                    if (editIdx >= 0) {
                        items.set(editIdx, newItem);
                    } else {
                        items.add(newItem);
                    }
                    refreshItemsView();
                    syncKembaliToTotalJumlah();
                    if (Transaction.ONGKIR_BORONGAN.equals(getSelectedOngkirType())
                            && !userEditedOngkir) {
                        applyBoronganDefault();
                    }
                    updateTotal();
                    dialog.dismiss();
                }));
        dialog.show();
    }

    private static final int REQUEST_NEW_CUSTOMER = 5101;

    private void showCustomerPicker() {
        View dialogView = LayoutInflater.from(this)
                .inflate(R.layout.dialog_select_customer, null);
        RecyclerView rvCustomers = dialogView.findViewById(R.id.rvCustomers);
        EditText etSearch = dialogView.findViewById(R.id.etSearchCustomer);
        View btnPickUmum = dialogView.findViewById(R.id.btnPickUmum);
        View btnPickNew = dialogView.findViewById(R.id.btnPickNew);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.pilih_pelanggan)
                .setView(dialogView)
                .setNegativeButton(R.string.batal, null)
                .create();

        // Mode KEMBALI: hanya pelanggan yang sudah pernah pinjam botol
        // yang masuk akal untuk mengembalikan. Sembunyikan tombol cepat
        // "Pelanggan Umum" dan "Pelanggan Baru" — paksa user pilih dari
        // daftar pelanggan eksisting.
        if (!isJualSelected() && !isJualBotolSelected()) {
            View quickPickRow = dialogView.findViewById(R.id.quickPickRow);
            View quickPickDivider = dialogView.findViewById(R.id.quickPickDivider);
            quickPickRow.setVisibility(View.GONE);
            quickPickDivider.setVisibility(View.GONE);
        }

        CustomerAdapter adapter = new CustomerAdapter(customer -> {
            applySelectedCustomer(customer);
            dialog.dismiss();
        });
        rvCustomers.setLayoutManager(new LinearLayoutManager(this));
        rvCustomers.setAdapter(adapter);
        adapter.setData(customerDao.getAll());

        btnPickUmum.setOnClickListener(v -> {
            Customer umum = customerDao.getOrCreateUmum();
            if (umum != null) applySelectedCustomer(umum);
            dialog.dismiss();
        });

        btnPickNew.setOnClickListener(v -> {
            // Buka form pelanggan baru. Setelah simpan, onActivityResult
            // akan auto-pick pelanggan yang baru saja dibuat.
            dialog.dismiss();
            startActivityForResult(
                    new Intent(this, CustomerFormActivity.class),
                    REQUEST_NEW_CUSTOMER);
        });

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                String k = s.toString().trim();
                adapter.setData(k.isEmpty() ? customerDao.getAll() : customerDao.search(k));
            }
        });
        dialog.show();
    }

    /**
     * Sentralkan semua perubahan UI yang harus terjadi setelah user
     * memilih pelanggan (regular, Umum, atau pelanggan yang baru dibuat).
     */
    private void applySelectedCustomer(Customer c) {
        selectedCustomerId = c.getId();
        selectedCustomerName = c.getName();
        selectedCustomerPhone = c.getPhone() != null ? c.getPhone() : "";
        tvSelectedCustomer.setText(c.getName());
        userEditedKembali = false;
        // Pelanggan Umum → ownership default = Beli (Umum tidak pinjam botol)
        if (isUmumCustomer()) {
            toggleOwnership.check(R.id.btnOwnershipBeli);
        }
        syncKembaliToTotalJumlah();
        updateOwnershipButtonsForCustomer();
        updateOwnershipUI();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_NEW_CUSTOMER && resultCode == RESULT_OK && data != null) {
            long newId = data.getLongExtra(CustomerFormActivity.EXTRA_NEW_CUSTOMER_ID, -1);
            if (newId > 0) {
                Customer c = customerDao.getById(newId);
                if (c != null) applySelectedCustomer(c);
            }
        }
    }

    /**
     * Cek Free tier monthly limit ({@link BuildConfig#FREE_MAX_TRX_PER_MONTH})
     * sebelum simpan. Pro lewat tanpa cek. Hit limit → paywall, kalau unlock
     * via rewarded ad, save() langsung diretry.
     */
    private void trySave() {
        SettingsDao settingsCheck = new SettingsDao(DatabaseHelper.getInstance(this));
        if (settingsCheck.isProActive()) {
            save();
            return;
        }
        int monthly = transactionDao.countThisMonth();
        if (monthly < BuildConfig.FREE_MAX_TRX_PER_MONTH) {
            save();
            return;
        }
        String reason = "Pengguna Gratis dibatasi " + BuildConfig.FREE_MAX_TRX_PER_MONTH
                + " transaksi/bulan. Upgrade Pro untuk transaksi tanpa batas.";
        PaywallDialogFragment.show(getSupportFragmentManager(), reason, this::save);
    }

    private void save() {
        if (selectedCustomerId == -1) {
            Toast.makeText(this, "Pilih pelanggan terlebih dahulu", Toast.LENGTH_SHORT).show();
            return;
        }

        if (isJualBotolSelected()) {
            saveJualBotol();
            return;
        }

        boolean isJual = isJualSelected();
        int totalJumlah = 0;
        double subtotal = 0;
        double ongkir = 0;
        double totalHarga = 0;
        String ongkirType = Transaction.ONGKIR_NONE;

        if (isJual) {
            if (items.isEmpty()) {
                Toast.makeText(this, "Tambahkan minimal 1 item", Toast.LENGTH_SHORT).show();
                return;
            }
            for (TransactionItem it : items) {
                totalJumlah += it.jumlah;
                subtotal += it.getSubtotal();
            }
            ongkirType = getSelectedOngkirType();
            if (!Transaction.ONGKIR_NONE.equals(ongkirType)) {
                String s = etOngkir.getText() != null ? etOngkir.getText().toString().trim() : "";
                if (!s.isEmpty()) {
                    try { ongkir = Double.parseDouble(s); } catch (NumberFormatException ignored) {}
                }
            }
            if (Transaction.ONGKIR_BORONGAN.equals(ongkirType)) {
                totalHarga = subtotal + ongkir;
            } else if (Transaction.ONGKIR_NONE.equals(ongkirType)) {
                totalHarga = subtotal;
            } else {
                totalHarga = subtotal + ongkir * totalJumlah;
            }
            // Tambahkan harga botol × jumlah jika pelanggan membeli botol galon
            if (isBeliSelected()) {
                double hargaBotol = parseDoubleOr(etHargaBotol, 0);
                if (hargaBotol <= 0) {
                    etHargaBotol.setError("Harga botol harus > 0");
                    return;
                }
                totalHarga += hargaBotol * totalJumlah;
            }
        } else {
            String qtyStr = etJumlahKembali.getText() != null
                    ? etJumlahKembali.getText().toString().trim() : "";
            if (qtyStr.isEmpty()) {
                etJumlahKembali.setError("Wajib diisi");
                return;
            }
            try {
                totalJumlah = Integer.parseInt(qtyStr);
            } catch (NumberFormatException e) {
                etJumlahKembali.setError("Angka tidak valid");
                return;
            }
            if (totalJumlah <= 0) {
                etJumlahKembali.setError("Harus > 0");
                return;
            }
            // Ganti rugi galon rusak — hanya dihitung jika mode "Ganti Rugi"
            if (isGantiRugiSelected()) {
                double hargaGR = parseDoubleOr(etHargaGantiRugi, 0);
                if (hargaGR <= 0) {
                    etHargaGantiRugi.setError("Harga ganti rugi harus > 0");
                    return;
                }
                int rusak = parseIntOr(etJumlahRusak, -1);
                if (rusak < 0) rusak = totalJumlah;
                if (rusak <= 0) {
                    etJumlahRusak.setError("Jumlah botol galon rusak harus > 0");
                    return;
                }
                if (rusak > totalJumlah) {
                    etJumlahRusak.setError("Tidak boleh > jumlah kembali");
                    return;
                }
                totalHarga = hargaGR * rusak;
            }
        }

        int jumlahKembali = 0;
        if (isJual) {
            String k = etJumlahKembali.getText() != null ? etJumlahKembali.getText().toString().trim() : "";
            if (!k.isEmpty()) {
                try {
                    jumlahKembali = Integer.parseInt(k);
                } catch (NumberFormatException e) {
                    etJumlahKembali.setError("Angka tidak valid");
                    return;
                }
            }
            if (jumlahKembali < 0) {
                etJumlahKembali.setError("Tidak boleh negatif");
                return;
            }
        }

        // Confirmation dialog
        NumberFormat nf = NumberFormat.getInstance(new Locale("id", "ID"));
        SpannableStringBuilder msg = new SpannableStringBuilder();
        int colorKeluar = Color.parseColor("#D32F2F");
        int colorKembali = Color.parseColor("#2E7D32");
        if (isJual) {
            if (isBeliSelected()) {
                appendStyled(msg, "Botol Galon Dibeli: " + totalJumlah + " botol\n",
                        Color.parseColor("#1565C0"), true, 1.15f);
            } else if (isBawaSendiriSelected()) {
                appendStyled(msg, "Botol Galon Bawa Sendiri: " + totalJumlah + " botol\n",
                        Color.parseColor("#1565C0"), true, 1.15f);
            } else {
                appendStyled(msg, "Botol Galon Keluar (Pinjam): " + totalJumlah + " botol galon\n", colorKeluar, true, 1.15f);
            }
            appendStyled(msg, "Botol Galon Kembali: " + jumlahKembali + " botol galon\n\n", colorKembali, true, 1.15f);
        } else {
            appendStyled(msg, "Botol Galon Kembali: " + totalJumlah + " botol galon\n", colorKembali, true, 1.15f);
            if (totalHarga > 0) {
                double hargaGR = parseDoubleOr(etHargaGantiRugi, 0);
                int rusak = parseIntOr(etJumlahRusak, -1);
                if (rusak < 0) rusak = totalJumlah;
                appendStyled(msg, "Botol Galon Rusak: " + rusak + " × Rp " + nf.format(hargaGR) + "\n",
                        colorKeluar, true, 1.05f);
                appendStyled(msg, "Ganti Rugi: Rp " + nf.format(totalHarga) + "\n\n",
                        Color.parseColor("#1565C0"), true, 1.1f);
            } else {
                msg.append("\n");
            }
        }
        msg.append("Pelanggan: ").append(selectedCustomerName).append("\n");
        if (isJual) {
            for (TransactionItem it : items) {
                msg.append("• ").append(it.productName).append(": ")
                        .append(String.valueOf(it.jumlah)).append(" x Rp ")
                        .append(nf.format(it.hargaPerGalon)).append("\n");
            }
            if (Transaction.ONGKIR_PER_GALON.equals(ongkirType) && ongkir > 0) {
                msg.append("Ongkir/botol galon: Rp ").append(nf.format(ongkir)).append("\n");
            } else if (Transaction.ONGKIR_BORONGAN.equals(ongkirType) && ongkir > 0) {
                msg.append("Ongkir borongan: Rp ").append(nf.format(ongkir)).append("\n");
            } else {
                msg.append("Ongkir: -\n");
            }
            if (isBeliSelected()) {
                double hb = parseDoubleOr(etHargaBotol, 0);
                msg.append("Beli Botol: ").append(String.valueOf(totalJumlah))
                        .append(" × Rp ").append(nf.format(hb)).append("\n");
            }
            msg.append("\n");
            appendStyled(msg, "Total: Rp " + nf.format(totalHarga),
                    Color.parseColor("#1565C0"), true, 1.1f);
        }

        final int fTotalJumlah = totalJumlah;
        final int fJumlahKembali = jumlahKembali;
        final double fOngkir = ongkir;
        final double fTotal = totalHarga;
        final boolean fIsJual = isJual;
        final String fOngkirType = ongkirType;
        final String fOwnership = isJual
                ? getSelectedOwnership() : Transaction.OWNERSHIP_PINJAM;
        final double fHargaBotol = (isJual && isBeliSelected())
                ? parseDoubleOr(etHargaBotol, 0) : 0;

        new AlertDialog.Builder(this)
                .setTitle("Konfirmasi Transaksi")
                .setMessage(msg)
                .setPositiveButton("Simpan", (d, w) -> doSave(fIsJual, fTotalJumlah,
                        fOngkir, fTotal, fJumlahKembali, fOngkirType,
                        fOwnership, fHargaBotol))
                .setNegativeButton("Batal", null)
                .show();
    }

    /**
     * Save transaksi "Jual Botol Galon Saja" — pelanggan beli botol kosong
     * tanpa air. Disimpan sebagai JUAL dengan items kosong, ownership=BELI,
     * harga_botol = harga jual, total_harga = qty * harga_botol.
     */
    private void saveJualBotol() {
        int qty = parseIntOr(etJumlahBotolJual, 0);
        double harga = parseDoubleOr(etHargaBotolJual, 0);
        if (qty <= 0) {
            etJumlahBotolJual.setError("Jumlah harus > 0");
            return;
        }
        if (harga <= 0) {
            etHargaBotolJual.setError("Harga harus > 0");
            return;
        }
        double total = qty * harga;

        NumberFormat nf = NumberFormat.getInstance(new Locale("id", "ID"));
        SpannableStringBuilder msg = new SpannableStringBuilder();
        appendStyled(msg, "Jual Botol Galon Kosong\n",
                Color.parseColor("#1565C0"), true, 1.15f);
        msg.append("Pelanggan: ").append(selectedCustomerName).append("\n");
        msg.append("Jumlah botol: ").append(String.valueOf(qty)).append("\n");
        msg.append("Harga / botol: Rp ").append(nf.format(harga)).append("\n\n");
        appendStyled(msg, "Total: Rp " + nf.format(total),
                Color.parseColor("#1565C0"), true, 1.1f);

        final int fQty = qty;
        final double fHarga = harga;
        final double fTotal = total;
        new AlertDialog.Builder(this)
                .setTitle("Konfirmasi Jual Botol")
                .setMessage(msg)
                .setPositiveButton("Simpan", (d, w) -> doSaveJualBotol(fQty, fHarga, fTotal))
                .setNegativeButton("Batal", null)
                .show();
    }

    private void updateTanggalTrxButton() {
        if (btnTanggalTrx == null) return;
        try {
            Date d = trxDbFmt.parse(selectedTrxDate);
            btnTanggalTrx.setText("Tanggal: "
                    + (d != null ? trxDisplayFmt.format(d) : "Sekarang"));
        } catch (Exception e) {
            btnTanggalTrx.setText("Tanggal: Sekarang");
        }
    }

    /** Date picker → time picker, simpan ke selectedTrxDate. */
    private void showTrxDateTimePicker() {
        Calendar cal = Calendar.getInstance();
        try {
            Date d = trxDbFmt.parse(selectedTrxDate);
            if (d != null) cal.setTime(d);
        } catch (Exception ignored) {}
        new android.app.DatePickerDialog(this, (dpView, year, month, day) -> {
            new android.app.TimePickerDialog(this, (tpView, hour, minute) -> {
                Calendar picked = Calendar.getInstance();
                picked.set(year, month, day, hour, minute, 0);
                selectedTrxDate = trxDbFmt.format(picked.getTime());
                updateTanggalTrxButton();
            }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).show();
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show();
    }

    private void doSaveJualBotol(int qty, double hargaBotol, double total) {
        Transaction trx = new Transaction();
        trx.setCustomerId(selectedCustomerId);
        trx.setType(Transaction.TYPE_JUAL);
        trx.setJumlahGalon(qty);
        trx.setOngkir(0);
        trx.setOngkirType(Transaction.ONGKIR_NONE);
        trx.setTotalHarga(total);
        trx.setGalonOwnership(Transaction.OWNERSHIP_BELI);
        trx.setHargaBotolGalon(hargaBotol);
        trx.setHargaPerGalon(0);
        if (selectedTrxDate != null) trx.setTanggal(selectedTrxDate);
        // items intentionally kosong — penanda transaksi botol-only
        String catatan = etCatatan.getText() != null ? etCatatan.getText().toString().trim() : "";
        String marker = "[JUAL BOTOL KOSONG]";
        catatan = catatan.isEmpty() ? marker : (marker + " " + catatan);
        trx.setCatatan(catatan);
        transactionDao.insert(trx);

        // Buka struk
        Intent r = new Intent(this, ReceiptActivity.class);
        r.putExtra(ReceiptActivity.EXTRA_CUSTOMER_NAME, selectedCustomerName);
        r.putExtra(ReceiptActivity.EXTRA_CUSTOMER_PHONE, selectedCustomerPhone);
        if (forwardedInboxSenderName != null) {
            r.putExtra(ReceiptActivity.EXTRA_INBOX_SENDER_NAME, forwardedInboxSenderName);
        }
        r.putExtra(ReceiptActivity.EXTRA_PRODUCT_NAME, "Botol Galon Kosong");
        r.putExtra(ReceiptActivity.EXTRA_JUMLAH, qty);
        r.putExtra(ReceiptActivity.EXTRA_HARGA_PER_GALON, hargaBotol);
        r.putExtra(ReceiptActivity.EXTRA_ONGKIR, 0.0);
        r.putExtra(ReceiptActivity.EXTRA_ONGKIR_TYPE, Transaction.ONGKIR_NONE);
        r.putExtra(ReceiptActivity.EXTRA_TOTAL_HARGA, total);
        r.putExtra(ReceiptActivity.EXTRA_CATATAN, catatan);
        startActivity(r);
        finish();
    }

    private double parseDoubleOr(TextInputEditText et, double def) {
        if (et == null || et.getText() == null) return def;
        String s = et.getText().toString().trim();
        if (s.isEmpty()) return def;
        try { return Double.parseDouble(s); } catch (NumberFormatException e) { return def; }
    }

    private int parseIntOr(TextInputEditText et, int def) {
        if (et == null || et.getText() == null) return def;
        String s = et.getText().toString().trim();
        if (s.isEmpty()) return def;
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return def; }
    }

    private void appendStyled(SpannableStringBuilder sb, String text,
                              int color, boolean bold, float sizeMul) {
        int start = sb.length();
        sb.append(text);
        int end = sb.length();
        sb.setSpan(new ForegroundColorSpan(color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (bold) {
            sb.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), start, end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (sizeMul != 1f) {
            sb.setSpan(new RelativeSizeSpan(sizeMul), start, end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    private void doSave(boolean isJual, int totalJumlah, double ongkir,
                        double totalHarga, int jumlahKembali, String ongkirType,
                        String ownership, double hargaBotol) {
        // Persist last ownership selection for next transaction
        if (isJual && ownership != null) {
            settingsDao.setLastGalonOwnership(ownership);
        }
        if (isJual && jumlahKembali > 0) {
            Transaction kembali = new Transaction();
            kembali.setCustomerId(selectedCustomerId);
            kembali.setType(Transaction.TYPE_KEMBALI);
            kembali.setJumlahGalon(jumlahKembali);
            kembali.setCatatan("Tukar botol galon");
            if (selectedTrxDate != null) kembali.setTanggal(selectedTrxDate);
            transactionDao.insert(kembali);
        }

        Transaction trx = new Transaction();
        trx.setCustomerId(selectedCustomerId);
        trx.setType(isJual ? Transaction.TYPE_JUAL : Transaction.TYPE_KEMBALI);
        trx.setJumlahGalon(totalJumlah);
        trx.setOngkir(ongkir);
        trx.setOngkirType(ongkirType);
        if (selectedTrxDate != null) trx.setTanggal(selectedTrxDate);
        trx.setTotalHarga(totalHarga);
        if (isJual) {
            trx.setGalonOwnership(ownership);
            trx.setHargaBotolGalon(hargaBotol);
        }
        double hargaGR = 0;
        int rusak = 0;
        if (isJual) {
            trx.setItems(items);
            // Backward-compat: put first item's product_id and price as primary
            if (!items.isEmpty()) {
                TransactionItem first = items.get(0);
                trx.setProductId(first.productId);
                trx.setHargaPerGalon(first.hargaPerGalon);
            }
        } else if (totalHarga > 0) {
            // Ganti rugi KEMBALI: compute & store per-galon price based on rusak count
            hargaGR = parseDoubleOr(etHargaGantiRugi, 0);
            rusak = parseIntOr(etJumlahRusak, -1);
            if (rusak < 0) rusak = totalJumlah;
            trx.setHargaPerGalon(hargaGR);
        }
        String catatan = etCatatan.getText() != null ? etCatatan.getText().toString().trim() : "";
        // For ganti rugi, prepend a marker into catatan so receipt can detect on re-view
        if (!isJual && totalHarga > 0) {
            String marker = "[GANTI RUGI " + rusak + " galon rusak]";
            catatan = catatan.isEmpty() ? marker : (marker + " " + catatan);
        }
        if (!catatan.isEmpty()) trx.setCatatan(catatan);
        transactionDao.insert(trx);

        if (isJual) {
            Intent r = new Intent(this, ReceiptActivity.class);
            r.putExtra(ReceiptActivity.EXTRA_CUSTOMER_NAME, selectedCustomerName);
            r.putExtra(ReceiptActivity.EXTRA_CUSTOMER_PHONE, selectedCustomerPhone);
            if (forwardedInboxSenderName != null) {
                r.putExtra(ReceiptActivity.EXTRA_INBOX_SENDER_NAME, forwardedInboxSenderName);
            }
            r.putExtra(ReceiptActivity.EXTRA_ITEMS_JSON,
                    TransactionItem.listToJson(items));
            r.putExtra(ReceiptActivity.EXTRA_ONGKIR, ongkir);
            r.putExtra(ReceiptActivity.EXTRA_ONGKIR_TYPE, ongkirType);
            r.putExtra(ReceiptActivity.EXTRA_TOTAL_HARGA, totalHarga);
            String catatanStr = etCatatan.getText() != null ? etCatatan.getText().toString().trim() : "";
            r.putExtra(ReceiptActivity.EXTRA_CATATAN, catatanStr);

            if (settingsDao.isPointsEnabled()) {
                double ppa = settingsDao.getPointsPerAmount();
                int reward = settingsDao.getPointsRewardThreshold();
                // Exclude biaya beli botol galon dari dasar perhitungan poin
                double thisTrxBasis = totalHarga - (hargaBotol * totalJumlah);
                if (thisTrxBasis < 0) thisTrxBasis = 0;
                int pointsThisTrx = (int) Math.floor(thisTrxBasis / ppa);
                // Lifetime dihitung dari penjualan saja, mengurangi porsi harga botol.
                // Transaksi yang sudah tersimpan akan dihitung ulang via SQL,
                // sehingga kita tambahkan dasar poin transaksi ini secara manual.
                double lifetimePrev = transactionDao.getTotalJualPointsBasisByCustomer(selectedCustomerId);
                double lifetime = lifetimePrev + thisTrxBasis;
                int totalPoints = (int) Math.floor(lifetime / ppa);
                int pointsBefore = (int) Math.floor(lifetimePrev / ppa);
                int rewardsBefore = reward > 0 ? pointsBefore / reward : 0;
                int rewardsAfter = reward > 0 ? totalPoints / reward : 0;
                boolean rewardUnlocked = rewardsAfter > rewardsBefore;
                r.putExtra(ReceiptActivity.EXTRA_POINTS_ENABLED, true);
                r.putExtra(ReceiptActivity.EXTRA_POINTS_EARNED, pointsThisTrx);
                r.putExtra(ReceiptActivity.EXTRA_POINTS_TOTAL, totalPoints);
                r.putExtra(ReceiptActivity.EXTRA_POINTS_REWARD, reward);
                r.putExtra(ReceiptActivity.EXTRA_REWARD_UNLOCKED, rewardUnlocked);
                r.putExtra(ReceiptActivity.EXTRA_REWARDS_NEW_COUNT, rewardsAfter - rewardsBefore);
            }
            startActivity(r);
            finish();
        } else {
            if (totalHarga > 0) {
                Intent r = new Intent(this, ReceiptActivity.class);
                r.putExtra(ReceiptActivity.EXTRA_CUSTOMER_NAME, selectedCustomerName);
                r.putExtra(ReceiptActivity.EXTRA_CUSTOMER_PHONE, selectedCustomerPhone);
                if (forwardedInboxSenderName != null) {
                    r.putExtra(ReceiptActivity.EXTRA_INBOX_SENDER_NAME, forwardedInboxSenderName);
                }
                r.putExtra(ReceiptActivity.EXTRA_PRODUCT_NAME, "Ganti Rugi Botol Galon Rusak");
                r.putExtra(ReceiptActivity.EXTRA_JUMLAH, rusak);
                r.putExtra(ReceiptActivity.EXTRA_HARGA_PER_GALON, hargaGR);
                r.putExtra(ReceiptActivity.EXTRA_ONGKIR, 0.0);
                r.putExtra(ReceiptActivity.EXTRA_ONGKIR_TYPE, Transaction.ONGKIR_NONE);
                r.putExtra(ReceiptActivity.EXTRA_TOTAL_HARGA, totalHarga);
                r.putExtra(ReceiptActivity.EXTRA_IS_GANTI_RUGI, true);
                r.putExtra(ReceiptActivity.EXTRA_GANTI_RUGI_KEMBALI, totalJumlah);
                String catatanStr = etCatatan.getText() != null ? etCatatan.getText().toString().trim() : "";
                r.putExtra(ReceiptActivity.EXTRA_CATATAN, catatanStr);
                startActivity(r);
                finish();
            } else {
                Toast.makeText(this, "Berhasil: " + totalJumlah + " botol galon kembali dari "
                        + selectedCustomerName, Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }
}
