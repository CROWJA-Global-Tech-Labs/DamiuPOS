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
import android.view.Menu;
import android.view.MenuItem;
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

    /** Intent extra: id reseller yang langsung di-set sebagai reseller afiliasi
     *  (caller: Detail Reseller → "Buat Transaksi Baru"). */
    public static final String EXTRA_RESELLER_ID = "preset_reseller_id";

    /** Mode "Pencairan Komisi": reuse layar Transaksi Baru sebagai form payout.
     *  Caller: Detail Reseller → "Cairkan Komisi". */
    public static final String EXTRA_KOMISI_PAYOUT = "komisi_payout";
    public static final String EXTRA_KOMISI_SALDO = "komisi_saldo";
    public static final String EXTRA_RESELLER_NAME = "reseller_name";

    /** Mode "Input Promosi Galon": pelanggan baru terkunci + toggle Gratis/Berbayar.
     *  Caller: CustomerFormActivity (alur promosi). */
    public static final String EXTRA_PROMOSI = "promosi_mode";
    public static final String EXTRA_PROMOSI_CUSTOMER_ID = "promosi_customer_id";
    /** Penanda catatan untuk promosi GRATIS (membedakan dari penjualan biasa). */
    public static final String PROMO_MARKER = "[PROMOSI]";

    /** Cached inbox sender name dari intent — di-pass ke ReceiptActivity. */
    private String forwardedInboxSenderName;

    private MaterialButtonToggleGroup toggleType, toggleOngkirMode, toggleOwnership, toggleReturnMode;
    /** Pelanggan preset (dibuka dari Detail Pelanggan via extra customer_id) berstatus Wajib Ongkir
     *  → default Ongkos Kirim Per Galon, diterapkan SETELAH view ongkir siap (setelah updateOngkirUI). */
    private boolean presetWajibOngkir;
    /** Status Wajib Ongkir pelanggan yang SEDANG terpilih — dipakai guard di toggle ongkir:
     *  memilih "Tanpa" (gratis ongkir) ditolak dengan popup lalu dikembalikan ke Per Galon. */
    private boolean selectedWajibOngkir = false;
    private TextView tvSelectedCustomer, tvTotalHarga, tvEmptyItems;
    private TextView tvSelectedReseller, btnClearReseller, tvSelectedTrxDate;
    private TextInputEditText etJumlahKembali, etOngkir, etCatatan;
    private TextInputEditText etHargaGantiRugi, etJumlahRusak;
    private TextInputEditText etHargaBotol;
    private TextInputEditText etJumlahBotolJual, etHargaBotolJual;
    private TextInputLayout tilOngkir, tilJumlahKembali, tilHargaBotol;
    private View ongkirModeContainer, cardCustomer, cardItems, cardOwnership, gantiRugiContainer, returnModeContainer;
    private View cardJualBotol, cardDate, cardReseller, cardKembali;
    private LinearLayout llItems;

    // --- Mode Input Promosi Galon ---
    private boolean promosiMode = false;
    private MaterialButtonToggleGroup toggleGratisBerbayar;

    // --- Mode Pencairan Komisi (payout) ---
    private boolean payoutMode = false;
    private double payoutSaldo = 0;
    private MaterialButtonToggleGroup togglePayoutType;
    private TextInputEditText etPayoutNominal, etPayoutGalonQty, etPayoutNilai, etPayoutNote;
    private View tilPayoutNominal, layoutPayoutAir;
    private TextView tvPayoutTotal;

    /** Reseller afiliasi terpilih (0 = tidak ada). */
    private long selectedResellerId = 0;
    private String selectedResellerName = "";
    /** Reseller terpilih pakai "Tambahkan Komisi ke Harga Air Minum". */
    private boolean selectedResellerAddToPrice = false;
    /** Override komisi per produk untuk reseller terpilih (fallback rate global). */
    private java.util.Map<Long, Double> selectedResellerRates = new java.util.HashMap<>();
    /** Afiliasi sedang di-set OTOMATIS ke pelanggan reseller itu sendiri (beli untuk dirinya). */
    private boolean autoAffiliateActive = false;

    /** Satu baris entri produk — semua produk DB di-render sekaligus supaya
     *  user mengatur jumlah & harga tanpa menambah item satu per satu. */
    private static class ProductEntry {
        final Product product;
        final EditText etQty;
        final EditText etPrice;
        ProductEntry(Product p, EditText q, EditText pr) {
            this.product = p; this.etQty = q; this.etPrice = pr;
        }
    }
    private final List<ProductEntry> productEntries = new ArrayList<>();

    private boolean syncingKembali = false;
    private boolean userEditedKembali = false;
    private boolean syncingOngkir = false;
    private boolean userEditedOngkir = false;

    private CustomerDao customerDao;
    private ProductDao productDao;
    private TransactionDao transactionDao;
    private SettingsDao settingsDao;
    private com.crowja.damiupos.db.ResellerRateDao resellerRateDao;

    private long selectedCustomerId = -1;
    private String selectedCustomerName = "";
    private String selectedCustomerPhone = "";
    /** Harga khusus per produk pelanggan terpilih { product_uuid: harga } (null = harga produk standar). */
    private java.util.Map<String, Double> selectedCustomerPrices = null;

    // --- Multi-lokasi pelanggan → "Kirim ke" (lokasi tujuan pengiriman) ---
    /** Daftar lokasi pelanggan terpilih (entri pertama = utama; kosong = tanpa lokasi). */
    private java.util.List<Customer.Location> selectedLocations = new ArrayList<>();
    /** Lokasi tujuan pengiriman TERPILIH — dipersist ke transaksi JUAL (delivery_dest_*). */
    private String selectedDestName;
    private double selectedDestLat, selectedDestLng;
    private View cardKirimKe;
    private TextView tvSelectedKirimKe;
    /** Pelanggan terakhir yang sudah diberi info multi-lokasi — popup sekali per pelanggan. */
    private long lastLocInfoCustomerId = -1;

    // --- Gunakan Saldo Komisi (muncul saat pelanggan = reseller, mode JUAL) ---
    private View cardUseSaldo, layoutSaldoPotong, layoutSaldoBreakdown;
    private com.google.android.material.checkbox.MaterialCheckBox cbUseSaldo;
    private TextView tvSaldoKomisi, tvSisaBayar, tvDipotongSaldo;
    private TextInputEditText etSaldoDipotong;
    private double resellerSaldo = 0;   // saldo komisi pelanggan reseller terpilih
    private double lastJualTotal = 0;   // total JUAL terkini (untuk hitung sisa bayar)

    // Tanggal+waktu transaksi terpilih (yyyy-MM-dd HH:mm:ss). Default = sekarang.
    // Berlaku untuk Jual Air, Galon Kembali, dan Jual Botol.
    private String selectedTrxDate;
    private final SimpleDateFormat trxDbFmt =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
    // Card tanggal sempit (30% lebar) → tampil 2 baris: tanggal lalu jam.
    private final SimpleDateFormat trxDateOnlyFmt =
            new SimpleDateFormat("d MMM yyyy", new Locale("id", "ID"));
    private final SimpleDateFormat trxTimeOnlyFmt =
            new SimpleDateFormat("HH:mm", new Locale("id", "ID"));

    private final List<TransactionItem> items = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Akun Viewer tidak boleh membuat transaksi — blok dari SEMUA entry
        // point (dashboard, detail pelanggan, detail reseller, inbox WA).
        DatabaseHelper dbGuard = DatabaseHelper.getInstance(this);
        SettingsDao sGuard = new SettingsDao(dbGuard);
        // Gate absensi: multiuser aktif tapi belum ada yang clock-in (mis. flag multiuser baru
        // tersinkron dari dashboard tanpa login) → paksa login dulu. Mencegah transaksi "tanpa
        // staf" (delivery_staff_id=0) sekaligus memastikan ada absensi masuk. Cermin MainActivity.
        if (sGuard.isMultiUserEnabled() && sGuard.getCurrentUserId() <= 0) {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }
        if (sGuard.isMultiUserEnabled() && sGuard.getCurrentUserId() > 0) {
            com.crowja.damiupos.model.User cur =
                    new com.crowja.damiupos.db.UserDao(dbGuard).getById(sGuard.getCurrentUserId());
            if (cur != null && !cur.canCreateTransaction()) {
                Toast.makeText(this, cur.isMarketing()
                                ? "Akun Marketing hanya dapat melakukan Promosi"
                                : "Akun Viewer tidak dapat membuat transaksi",
                        Toast.LENGTH_LONG).show();
                finish();
                return;
            }
        }

        setContentView(R.layout.activity_transaction);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        DatabaseHelper dbHelper = DatabaseHelper.getInstance(this);
        customerDao = new CustomerDao(dbHelper);
        productDao = new ProductDao(dbHelper);
        transactionDao = new TransactionDao(dbHelper);
        settingsDao = new SettingsDao(dbHelper);
        resellerRateDao = new com.crowja.damiupos.db.ResellerRateDao(dbHelper);

        toggleType = findViewById(R.id.toggleType);
        tvSelectedCustomer = findViewById(R.id.tvSelectedCustomer);

        // Tanggal transaksi — default sekarang (card di samping pilih pelanggan)
        cardDate = findViewById(R.id.cardDate);
        tvSelectedTrxDate = findViewById(R.id.tvSelectedTrxDate);
        selectedTrxDate = trxDbFmt.format(new Date());
        updateTanggalTrxButton();
        cardDate.setOnClickListener(v -> showTrxDateTimePicker());

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
        cardKembali = findViewById(R.id.cardKembali);
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

        // "Kirim ke" (multi-lokasi): kartu pilih lokasi tujuan pengiriman — tampil hanya
        // saat JUAL & pelanggan punya >1 lokasi (updateKirimKeCard).
        cardKirimKe = findViewById(R.id.cardKirimKe);
        tvSelectedKirimKe = findViewById(R.id.tvSelectedKirimKe);
        cardKirimKe.setOnClickListener(v -> showKirimKePicker());

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
            if (!isChecked) return;
            // Pelanggan "Wajib Ongkir": opsi "Tanpa" (gratis ongkir) ditolak — beri tahu staf
            // lewat popup, lalu kembalikan pilihan ke Per Galon. Borongan tetap boleh (berbayar).
            // Dikecualikan saat Promosi GRATIS: pemberian gratis di lokasi memang tanpa ongkir,
            // jadi "Tanpa" boleh meng-override status Wajib Ongkir (lihat applyPromosiPricing).
            if (checkedId == R.id.btnOngkirNone && selectedWajibOngkir && isJualSelected()
                    && !(promosiMode && isPromosiGratis())) {
                new AlertDialog.Builder(this)
                        .setTitle("⚠️ Wajib Ongkir")
                        .setMessage("Pelanggan ini ditandai \"Wajib Ongkir\" — ongkos kirim tidak "
                                + "boleh gratis. Pilihan dikembalikan ke Per Galon.")
                        .setPositiveButton("OK", null)
                        .setOnDismissListener(d -> toggleOngkirMode.check(R.id.btnOngkirPerGalon))
                        .show();
                return;
            }
            updateOngkirUI();
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
                selectedCustomerPrices = c.getProductPrices();
                // Multi-lokasi: dest default = lokasi utama; selectedWajibOngkir mengikuti
                // flag lokasi TERPILIH (guard "Tanpa" ongkir aktif juga di jalur preset).
                applyCustomerLocations(c);
                presetWajibOngkir = selectedWajibOngkir;
                tvSelectedCustomer.setText(c.getName());
                maybeWarnIncompleteCustomer(c);
                maybeWarnPendingGift(c);
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

        // Reseller afiliasi (opsional) — hanya relevan untuk transaksi JUAL.
        cardReseller = findViewById(R.id.cardReseller);
        tvSelectedReseller = findViewById(R.id.tvSelectedReseller);
        btnClearReseller = findViewById(R.id.btnClearReseller);
        cardReseller.setOnClickListener(v -> showResellerPicker());
        btnClearReseller.setOnClickListener(v -> {
            selectedResellerId = 0;
            selectedResellerName = "";
            selectedResellerAddToPrice = false;
            selectedResellerRates = new java.util.HashMap<>();
            autoAffiliateActive = false;
            applyResellerPricing(); // kembalikan harga ke normal
            updateResellerLabel();
        });
        updateResellerLabel();

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

        setupUseSaldoCard();

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

        // Render semua jenis air sebagai baris entri (jumlah + harga per pcs).
        buildProductEntries();
        // Terapkan harga air khusus pelanggan (bila Transaksi Baru dibuka dengan pelanggan terpilih).
        applyResellerPricing();

        // Pre-set reseller afiliasi kalau dibuka dari Detail Reseller.
        prefillResellerFromIntent();

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

        onEntriesChanged();
        updateTypeUI();
        updateOngkirUI();
        // Pelanggan preset Wajib Ongkir → default Per Galon (setelah view ongkir siap, override default XML).
        // Hanya untuk JUAL (ongkir tak relevan di KEMBALI).
        if (isJualSelected() && presetWajibOngkir) toggleOngkirMode.check(R.id.btnOngkirPerGalon);
        // Auto-fill "Jumlah Botol Galon Kembali" berdasarkan total qty items
        // (default behavior — pelanggan setor botol kosong sejumlah botol
        // baru yg dibawa). Untuk Umum/Beli/Botol Sendiri: di-skip via
        // updateOwnershipUI yg hide field-nya.
        syncKembaliToTotalJumlah();
        updateTotal();
        refreshUseSaldoCard();   // tampilkan kartu saldo kalau pelanggan prefilled = reseller

        // Mode Pencairan Komisi: setelah semua setup jual selesai, alihkan layar ini
        // menjadi form payout (sembunyikan kartu jual, tampilkan kartu pencairan).
        maybeEnterPayoutMode();
        // Mode Input Promosi Galon: pelanggan baru terkunci + toggle Gratis/Berbayar.
        maybeEnterPromosiMode();
    }

    /** Aktifkan mode Promosi kalau dibuka dengan EXTRA_PROMOSI (dari alur Input Promosi Galon). */
    private void maybeEnterPromosiMode() {
        if (!getIntent().getBooleanExtra(EXTRA_PROMOSI, false)) return;
        long custId = getIntent().getLongExtra(EXTRA_PROMOSI_CUSTOMER_ID, 0);
        Customer c = custId > 0 ? customerDao.getById(custId) : null;
        if (c == null) { Toast.makeText(this, "Pelanggan tidak valid", Toast.LENGTH_SHORT).show(); finish(); return; }
        promosiMode = true;

        // Pelanggan BARU terpilih & dikunci (tak bisa diganti — ini akuisisi pelanggan baru).
        selectedCustomerId = c.getId();
        selectedCustomerName = c.getName();
        selectedCustomerPhone = c.getPhone();
        selectedCustomerPrices = c.getProductPrices();
        tvSelectedCustomer.setText(c.getName()
                + (c.getPhone() != null && !c.getPhone().isEmpty() ? " · " + c.getPhone() : ""));
        maybeWarnIncompleteCustomer(c);   // akuisisi baru: foto + koordinat wajib lengkap
        maybeWarnPendingGift(c);
        cardCustomer.setOnClickListener(null);
        cardCustomer.setClickable(false);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("Input Promosi Galon — " + selectedCustomerName);
        }

        // Toggle Gratis/Berbayar (default Gratis). Gratis → semua harga 0; Berbayar → harga normal.
        View promosiCard = findViewById(R.id.cardPromosiMode);
        if (promosiCard != null) promosiCard.setVisibility(View.VISIBLE);
        toggleGratisBerbayar = findViewById(R.id.toggleGratisBerbayar);
        if (toggleGratisBerbayar != null) {
            toggleGratisBerbayar.addOnButtonCheckedListener((g, id, checked) -> { if (checked) applyPromosiPricing(); });
            if (toggleGratisBerbayar.getCheckedButtonId() == View.NO_ID) {
                toggleGratisBerbayar.check(R.id.btnPromosiGratis);
            }
        }
        applyResellerPricing();   // pastikan harga pelanggan/standar dulu
        applyPromosiPricing();
        // Pelanggan promosi = pelanggan baru → tidak punya galon depot untuk ditukar;
        // pastikan default "Botol Galon Kembali" 0 meskipun qty sudah ter-prefill.
        syncKembaliToTotalJumlah();
        // Promosi = akuisisi di lokasi (bukan kiriman kurir) → kartu "Kirim ke" disembunyikan.
        updateKirimKeCard();
    }

    /** True kalau toggle promosi di posisi Gratis (default). */
    private boolean isPromosiGratis() {
        return toggleGratisBerbayar == null
                || toggleGratisBerbayar.getCheckedButtonId() != R.id.btnPromosiBerbayar;
    }

    /** Gratis → kunci semua harga ke 0; Berbayar → kembalikan harga normal & bisa diedit. */
    private void applyPromosiPricing() {
        boolean gratis = isPromosiGratis();
        if (!gratis) applyResellerPricing();   // restore harga pelanggan/standar
        for (ProductEntry pe : productEntries) {
            if (gratis) {
                pe.etPrice.setText("0");
                pe.etPrice.setEnabled(false);
            } else {
                pe.etPrice.setEnabled(true);
            }
        }
        // Promo GRATIS = pemberian langsung di lokasi (kartu "Kirim ke" disembunyikan) → ongkir ikut
        // digratiskan: paksa "Tanpa" agar total benar-benar Rp 0, meski pelanggan Wajib Ongkir
        // (guard di toggle ongkir mengecualikan mode ini). Berbayar → ongkir mengikuti pilihan normal.
        if (gratis && toggleOngkirMode != null
                && toggleOngkirMode.getCheckedButtonId() != R.id.btnOngkirNone) {
            toggleOngkirMode.check(R.id.btnOngkirNone);   // memicu updateOngkirUI() → ongkir 0
        }
        updateTotal();
    }

    /** Aktifkan mode payout kalau dibuka dengan EXTRA_KOMISI_PAYOUT. */
    private void maybeEnterPayoutMode() {
        if (!getIntent().getBooleanExtra(EXTRA_KOMISI_PAYOUT, false)) return;
        long resellerId = getIntent().getLongExtra(EXTRA_RESELLER_ID, 0);
        if (resellerId <= 0) { Toast.makeText(this, "Reseller tidak valid", Toast.LENGTH_SHORT).show(); finish(); return; }
        payoutMode = true;
        payoutSaldo = getIntent().getDoubleExtra(EXTRA_KOMISI_SALDO, 0);

        // Reseller = penerima pencairan (dipakai sbg "pelanggan" transaksi AIR).
        Customer reseller = customerDao.getById(resellerId);
        selectedCustomerId = resellerId;
        selectedCustomerName = reseller != null ? reseller.getName()
                : getIntent().getStringExtra(EXTRA_RESELLER_NAME);
        if (selectedCustomerName == null) selectedCustomerName = "Reseller";
        selectedCustomerPhone = reseller != null ? reseller.getPhone() : null;
        selectedResellerId = 0; // bukan transaksi afiliasi

        // Sembunyikan semua kartu jual; tampilkan kartu pencairan.
        int[] hide = {R.id.cardType, R.id.rowCustomerDate, R.id.cardReseller, R.id.cardKirimKe,
                R.id.cardItems, R.id.cardJualBotol, R.id.cardOwnership, R.id.cardKembali,
                R.id.cardDetails, R.id.cardUseSaldo, R.id.cardTotal};
        for (int id : hide) { View v = findViewById(id); if (v != null) v.setVisibility(View.GONE); }
        findViewById(R.id.cardPayout).setVisibility(View.VISIBLE);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("Pencairan Komisi — " + selectedCustomerName);
        }

        TextView tvReseller = findViewById(R.id.tvPayoutReseller);
        TextView tvSaldo = findViewById(R.id.tvPayoutSaldo);
        togglePayoutType = findViewById(R.id.togglePayoutType);
        tilPayoutNominal = findViewById(R.id.tilPayoutNominal);
        etPayoutNominal = findViewById(R.id.etPayoutNominal);
        layoutPayoutAir = findViewById(R.id.layoutPayoutAir);
        etPayoutGalonQty = findViewById(R.id.etPayoutGalonQty);
        etPayoutNilai = findViewById(R.id.etPayoutNilai);
        tvPayoutTotal = findViewById(R.id.tvPayoutTotal);
        etPayoutNote = findViewById(R.id.etPayoutNote);

        NumberFormat nf = NumberFormat.getInstance(new Locale("id", "ID"));
        tvReseller.setText("Cairkan untuk: " + selectedCustomerName);
        tvSaldo.setText("Saldo tersedia: Rp " + nf.format(Math.round(payoutSaldo)));

        // Prefill nilai/galon dari harga jual produk pertama (kalau ada).
        List<Product> products = productDao.getAll();
        if (!products.isEmpty()) etPayoutNilai.setText(String.valueOf(Math.round(products.get(0).getHargaJual())));

        com.google.android.material.button.MaterialButton btnSimpan = findViewById(R.id.btnSimpan);
        btnSimpan.setText("Cairkan");

        togglePayoutType.addOnButtonCheckedListener((g, checkedId, isChecked) -> {
            if (!isChecked) return;
            if (checkedId == R.id.btnPayoutAir) {
                // "Air Minum": pencairan diproses lewat layar Transaksi Baru (pilih produk asli),
                // jadi kosongkan + sembunyikan form galon & kartu total di panel ini, dan ubah
                // tombol jadi "Cairkan Sebagai Air Minum" (navigate, bukan simpan langsung).
                etPayoutGalonQty.setText("");
                etPayoutNilai.setText("");
                tvPayoutTotal.setText("");
                layoutPayoutAir.setVisibility(View.GONE);
                tilPayoutNominal.setVisibility(View.GONE);
                btnSimpan.setText("Cairkan Sebagai Air Minum");
            } else {
                layoutPayoutAir.setVisibility(View.GONE);
                tilPayoutNominal.setVisibility(View.VISIBLE);
                btnSimpan.setText("Cairkan");
            }
        });

        TextWatcher airWatcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                int qty = parseIntOr(etPayoutGalonQty, 0);
                double nilai = parseDoubleOr(etPayoutNilai, 0);
                tvPayoutTotal.setText("Total dipotong: Rp " + nf.format(Math.round(qty * nilai)));
            }
        };
        etPayoutGalonQty.addTextChangedListener(airWatcher);
        etPayoutNilai.addTextChangedListener(airWatcher);
        airWatcher.afterTextChanged(null);

        btnSimpan.setOnClickListener(v -> {
            if (togglePayoutType.getCheckedButtonId() == R.id.btnPayoutAir) {
                // Cairkan sebagai air minum → buka Transaksi Baru dengan reseller sebagai pelanggan
                // (nama pelanggan sudah terisi). Pencairan UANG tetap disimpan langsung di sini.
                startActivity(new Intent(this, TransactionActivity.class)
                        .putExtra("customer_id", selectedCustomerId));
                finish();
            } else {
                trySavePayout();
            }
        });
    }

    /** Simpan pencairan komisi (hybrid): AIR = transaksi galon (gratis, potong stok lewat
     *  riwayat jual), UANG = pengeluaran depot. Keduanya catat reseller_withdrawal + buka struk. */
    private void trySavePayout() {
        if (payoutSaldo <= 0) { Toast.makeText(this, "Saldo komisi belum ada untuk dicairkan", Toast.LENGTH_SHORT).show(); return; }
        NumberFormat nf = NumberFormat.getInstance(new Locale("id", "ID"));
        boolean air = togglePayoutType.getCheckedButtonId() == R.id.btnPayoutAir;
        String note = etPayoutNote.getText() != null ? etPayoutNote.getText().toString().trim() : "";
        com.crowja.damiupos.db.ResellerWithdrawalDao wdDao =
                new com.crowja.damiupos.db.ResellerWithdrawalDao(DatabaseHelper.getInstance(this));

        Intent r = new Intent(this, ReceiptActivity.class);
        r.putExtra(ReceiptActivity.EXTRA_IS_KOMISI_PAYOUT, true);
        r.putExtra(ReceiptActivity.EXTRA_RESELLER_NAME, selectedCustomerName);
        r.putExtra(ReceiptActivity.EXTRA_CUSTOMER_NAME, selectedCustomerName);
        r.putExtra(ReceiptActivity.EXTRA_CUSTOMER_PHONE, selectedCustomerPhone);
        r.putExtra(ReceiptActivity.EXTRA_CUSTOMER_ID, selectedCustomerId);
        r.putExtra(ReceiptActivity.EXTRA_SALDO_BEFORE, payoutSaldo);

        if (air) {
            int qty = parseIntOr(etPayoutGalonQty, 0);
            double nilai = parseDoubleOr(etPayoutNilai, 0);
            double total = qty * nilai;
            if (qty <= 0) { etPayoutGalonQty.setError("Minimal 1 galon"); return; }
            if (nilai <= 0) { etPayoutNilai.setError("Nilai per galon wajib diisi"); return; }
            if (total > payoutSaldo) { etPayoutGalonQty.setError("Melebihi saldo (Rp " + nf.format(Math.round(payoutSaldo)) + ")"); return; }

            // Transaksi galon (gratis, dibayar dari komisi) — masuk riwayat, ditandai marker.
            Transaction trx = new Transaction();
            trx.setCustomerId(selectedCustomerId);
            trx.setType(Transaction.TYPE_JUAL);
            trx.setJumlahGalon(qty);
            trx.setHargaPerGalon(0);
            trx.setTotalHarga(0);
            trx.setOngkir(0);
            trx.setOngkirType(Transaction.ONGKIR_NONE);
            trx.setGalonOwnership(Transaction.OWNERSHIP_BAWA_SENDIRI); // tak ada botol keluar
            if (selectedTrxDate != null) trx.setTanggal(selectedTrxDate);
            List<Product> products = productDao.getAll();
            if (!products.isEmpty()) {
                Product p = products.get(0);
                List<TransactionItem> its = new ArrayList<>();
                its.add(new TransactionItem(p.getId(), p.getName(), qty, 0));
                trx.setItems(its);
                trx.setProductId(p.getId());
            }
            String catatan = "[PENCAIRAN KOMISI] " + qty + " galon (nilai Rp " + nf.format(Math.round(total)) + ")";
            if (!note.isEmpty()) catatan += " — " + note;
            trx.setCatatan(catatan);
            transactionDao.insert(trx);
            wdDao.insert(selectedCustomerId, com.crowja.damiupos.db.ResellerWithdrawalDao.TYPE_AIR, qty, total, note, 0);
            com.crowja.damiupos.sync.SyncScheduler.syncNow(getApplicationContext());

            r.putExtra(ReceiptActivity.EXTRA_PAYOUT_TYPE, "AIR");
            r.putExtra(ReceiptActivity.EXTRA_PAYOUT_GALON, qty);
            r.putExtra(ReceiptActivity.EXTRA_PAYOUT_NILAI, nilai);
            r.putExtra(ReceiptActivity.EXTRA_PAYOUT_AMOUNT, total);
            r.putExtra(ReceiptActivity.EXTRA_SALDO_AFTER, payoutSaldo - total);
            Toast.makeText(this, "Dicairkan " + qty + " galon air (Rp " + nf.format(Math.round(total)) + ")", Toast.LENGTH_LONG).show();
        } else {
            double nominal = parseDoubleOr(etPayoutNominal, 0);
            if (nominal <= 0) { etPayoutNominal.setError("Nominal wajib diisi"); return; }
            if (nominal > payoutSaldo) { etPayoutNominal.setError("Melebihi saldo (Rp " + nf.format(Math.round(payoutSaldo)) + ")"); return; }

            // Pencairan tunai = pengeluaran depot + reseller_withdrawal.
            String expName = "Komisi Reseller" + (selectedCustomerName != null && !selectedCustomerName.isEmpty() ? " - " + selectedCustomerName : "");
            String expNote = "[PENCAIRAN KOMISI] Pencairan komisi reseller (uang tunai)" + (!note.isEmpty() ? " — " + note : "");
            long expId = new com.crowja.damiupos.db.ExpenseDao(DatabaseHelper.getInstance(this))
                    .insert(new com.crowja.damiupos.model.Expense(expName, nominal, null, expNote));
            wdDao.insert(selectedCustomerId, com.crowja.damiupos.db.ResellerWithdrawalDao.TYPE_UANG, 0, nominal, note, expId);
            com.crowja.damiupos.sync.SyncScheduler.syncNow(getApplicationContext());

            r.putExtra(ReceiptActivity.EXTRA_PAYOUT_TYPE, "UANG");
            r.putExtra(ReceiptActivity.EXTRA_PAYOUT_AMOUNT, nominal);
            r.putExtra(ReceiptActivity.EXTRA_SALDO_AFTER, payoutSaldo - nominal);
            Toast.makeText(this, "Dicairkan Rp " + nf.format(Math.round(nominal)), Toast.LENGTH_LONG).show();
        }
        startActivity(r);
        finish();
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
        com.crowja.damiupos.wa.ParsedOrder parsed =
                com.crowja.damiupos.wa.ParsedOrder.fromJson(parsedJson);
        if (parsed.items.isEmpty()) return;

        List<Product> all = productDao.getAll();
        int matched = 0;
        int unmatched = 0;
        for (com.crowja.damiupos.wa.ParsedOrder.Item it : parsed.items) {
            if (it.qty <= 0 || it.product == null || it.product.isEmpty()) continue;
            Product match = findProductForLabel(it.product, all);
            // Set jumlah pada baris entri produk yang cocok. Harga dibiarkan
            // pakai default produk (sudah ter-prefill saat build).
            if (match != null && applyQtyToEntry(match.getId(), it.qty)) {
                matched++;
            } else {
                unmatched++;
            }
        }

        if (matched > 0) userEditedKembali = false;

        if (unmatched > 0) {
            String msg = all.isEmpty()
                    ? "Belum ada Jenis Air di DB — atur item manual setelah menambah produk."
                    : unmatched + " item dari pesanan tidak cocok dengan Jenis Air — atur jumlahnya manual.";
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        }
    }

    /** Set jumlah (pcs) pada baris entri produk dengan id tertentu. */
    private boolean applyQtyToEntry(long productId, int qty) {
        for (ProductEntry pe : productEntries) {
            if (pe.product.getId() == productId) {
                pe.etQty.setText(String.valueOf(qty));
                return true;
            }
        }
        return false;
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
            cardKembali.setVisibility(View.GONE);
            tilOngkir.setVisibility(View.GONE);
            ongkirModeContainer.setVisibility(View.GONE);
            returnModeContainer.setVisibility(View.GONE);
            gantiRugiContainer.setVisibility(View.GONE);
        } else if (isJual) {
            cardJualBotol.setVisibility(View.GONE);
            cardItems.setVisibility(View.VISIBLE);
            cardKembali.setVisibility(View.VISIBLE);
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
            cardKembali.setVisibility(View.VISIBLE);
            tilJumlahKembali.setHint("Jumlah Botol Galon Kembali");
            tilOngkir.setVisibility(View.GONE);
            ongkirModeContainer.setVisibility(View.GONE);
            returnModeContainer.setVisibility(View.VISIBLE);
            cardOwnership.setVisibility(View.GONE);
            updateReturnModeUI();
        }
        // Reseller afiliasi hanya relevan untuk transaksi JUAL air.
        if (cardReseller != null) {
            cardReseller.setVisibility(isJual ? View.VISIBLE : View.GONE);
        }
        updateKirimKeCard();     // "Kirim ke" hanya untuk JUAL & pelanggan multi-lokasi
        refreshUseSaldoCard();   // "Gunakan Saldo Komisi" hanya untuk JUAL
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
            cardKembali.setVisibility(hideKembali ? View.GONE : View.VISIBLE);
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
        lastJualTotal = total;
        updateSisaBayar();
    }

    // --- Gunakan Saldo Komisi (pelanggan reseller) ---

    private void setupUseSaldoCard() {
        cardUseSaldo = findViewById(R.id.cardUseSaldo);
        cbUseSaldo = findViewById(R.id.cbUseSaldo);
        tvSaldoKomisi = findViewById(R.id.tvSaldoKomisi);
        layoutSaldoPotong = findViewById(R.id.layoutSaldoPotong);
        etSaldoDipotong = findViewById(R.id.etSaldoDipotong);
        tvSisaBayar = findViewById(R.id.tvSisaBayar);
        layoutSaldoBreakdown = findViewById(R.id.layoutSaldoBreakdown);
        tvDipotongSaldo = findViewById(R.id.tvDipotongSaldo);

        cbUseSaldo.setOnCheckedChangeListener((b, checked) -> {
            layoutSaldoPotong.setVisibility(checked ? View.VISIBLE : View.GONE);
            if (checked && etSaldoDipotong.length() == 0) {
                // Prefill: potong secukupnya untuk menutup total (dibatasi saldo).
                long prefill = Math.max(0, (long) Math.round(Math.min(lastJualTotal, resellerSaldo)));
                etSaldoDipotong.setText(String.valueOf(prefill));
            }
            updateSisaBayar();
        });
        etSaldoDipotong.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { updateSisaBayar(); }
        });
    }

    /** Hitung saldo komisi pelanggan terpilih & tampilkan kartu (hanya JUAL + reseller). */
    private void refreshUseSaldoCard() {
        if (cardUseSaldo == null) return;
        boolean custIsReseller = false;
        resellerSaldo = 0;
        Customer c = selectedCustomerId > 0 ? customerDao.getById(selectedCustomerId) : null;
        if (c != null && c.isReseller()) {
            custIsReseller = true;
            // Saldo gabungan server-otoritatif (lintas perangkat, = dashboard); fallback lokal offline.
            boolean online = new com.crowja.damiupos.sync.SyncSettings(settingsDao).isEnrolled();
            resellerSaldo = Math.max(0, customerDao.mergedResellerSaldo(selectedCustomerId, online));
        }
        // Reseller beli untuk DIRINYA sendiri → afiliasi otomatis = dirinya: harga reseller (komisi
        // ke harga bila aktif) berlaku, dan komisi masuk ke saldonya (reseller_id = pelanggan ini).
        // Selektor "Reseller Afiliasi" disembunyikan; pelanggan non-reseller → selektor tampil normal.
        if (cardReseller != null) {
            boolean showAfil = !payoutMode && isJualSelected() && !custIsReseller;
            cardReseller.setVisibility(showAfil ? View.VISIBLE : View.GONE);
            if (custIsReseller) {
                // Reseller beli untuk dirinya → afiliasi = dirinya sendiri.
                if (selectedResellerId != selectedCustomerId) {
                    applyAutoAffiliate(c);
                }
            } else {
                // Pelanggan biasa: kalau punya TAUTAN reseller → auto-pilih reseller rujukannya sbg afiliasi.
                long linkedId = (c != null && c.getLinkedResellerUuid() != null && !c.getLinkedResellerUuid().isEmpty())
                        ? customerDao.getIdBySyncUuid(c.getLinkedResellerUuid()) : -1;
                Customer linked = linkedId > 0 ? customerDao.getById(linkedId) : null;
                if (linked != null && linked.isReseller()) {
                    if (selectedResellerId != linkedId) applyAutoAffiliate(linked);
                } else if (autoAffiliateActive) {
                    // Ganti ke pelanggan tanpa reseller & tanpa tautan → batalkan auto-afiliasi (jangan timpa pilihan manual).
                    selectedResellerId = 0;
                    selectedResellerName = "";
                    selectedResellerAddToPrice = false;
                    selectedResellerRates = new java.util.HashMap<>();
                    autoAffiliateActive = false;
                    applyResellerPricing();
                    updateResellerLabel();
                }
            }
        }
        // Kartu "Gunakan Saldo Komisi": pelanggan reseller (mode JUAL) dengan saldo > 0.
        boolean eligibleSaldo = !payoutMode && isJualSelected() && custIsReseller && resellerSaldo > 0;
        if (eligibleSaldo) {
            NumberFormat nf = NumberFormat.getInstance(new Locale("id", "ID"));
            tvSaldoKomisi.setText("Saldo komisi tersedia: Rp " + nf.format(Math.round(resellerSaldo)));
            cardUseSaldo.setVisibility(View.VISIBLE);
        } else {
            if (cbUseSaldo != null) cbUseSaldo.setChecked(false);
            cardUseSaldo.setVisibility(View.GONE);
        }
        updateSisaBayar();
    }

    /** Jadikan reseller r afiliasi OTOMATIS (self-afiliasi / tautan reseller pelanggan): harga
     *  reseller berlaku & komisi masuk ke saldonya. Ditandai autoAffiliateActive → bisa dibalik. */
    private void applyAutoAffiliate(Customer r) {
        selectedResellerId = r.getId();
        selectedResellerName = r.getName();
        selectedResellerAddToPrice = r.isKomisiAddToPrice();
        selectedResellerRates = resellerRateDao.getRates(r.getId());
        autoAffiliateActive = true;
        applyResellerPricing();
        updateResellerLabel();
    }

    /** Rupiah yang benar-benar dipotong dari saldo komisi (dibatasi saldo & total order). */
    private double saldoDipotong() {
        if (cbUseSaldo == null || !cbUseSaldo.isChecked()) return 0;
        double v = parseDoubleOr(etSaldoDipotong, 0);
        if (v < 0) v = 0;
        if (v > resellerSaldo) v = resellerSaldo;
        if (v > lastJualTotal) v = lastJualTotal;
        return v;
    }

    private void updateSisaBayar() {
        if (layoutSaldoBreakdown == null) return;
        // Rincian potong saldo komisi ditampilkan di DALAM kartu Total (bukan di kartu saldo).
        if (cbUseSaldo != null && cbUseSaldo.isChecked()) {
            double dipotong = saldoDipotong();
            double sisa = Math.max(0, lastJualTotal - dipotong);
            NumberFormat nf = NumberFormat.getInstance(new Locale("id", "ID"));
            tvDipotongSaldo.setText("− Rp " + nf.format(Math.round(dipotong)));
            tvSisaBayar.setText("Rp " + nf.format(Math.round(sisa)));
            layoutSaldoBreakdown.setVisibility(View.VISIBLE);
        } else {
            layoutSaldoBreakdown.setVisibility(View.GONE);
        }
    }

    // --- Items UI ---

    /**
     * Render satu baris entri untuk SETIAP jenis air di DB (nama + harga per
     * pcs + stepper jumlah −/+). Harga di-prefill dari harga jual produk;
     * jumlah default 0. Perubahan apa pun → {@link #onEntriesChanged()}.
     */
    private void buildProductEntries() {
        llItems.removeAllViews();
        productEntries.clear();
        List<Product> products = productDao.getAll();
        for (Product p : products) {
            View row = LayoutInflater.from(this)
                    .inflate(R.layout.item_product_entry, llItems, false);
            TextView tvDot = row.findViewById(R.id.tvItemDot);
            TextView tvName = row.findViewById(R.id.tvItemName);
            final EditText etQty = row.findViewById(R.id.etItemQty);
            final EditText etPrice = row.findViewById(R.id.etItemPrice);
            View btnMinus = row.findViewById(R.id.btnItemMinus);
            View btnPlus = row.findViewById(R.id.btnItemPlus);

            tvName.setText(p.getName());
            try {
                tvDot.setTextColor(p.getColor() != null && !p.getColor().isEmpty()
                        ? Color.parseColor(p.getColor()) : Color.parseColor("#1565C0"));
            } catch (Exception e) {
                tvDot.setTextColor(Color.parseColor("#1565C0"));
            }
            // Set nilai awal SEBELUM pasang watcher supaya tidak memicu
            // onEntriesChanged saat inisialisasi.
            etQty.setText("0");
            etPrice.setText(String.valueOf((long) p.getHargaJual()));

            TextWatcher w = new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void afterTextChanged(Editable s) { onEntriesChanged(); }
            };
            etQty.addTextChangedListener(w);
            etPrice.addTextChangedListener(w);

            btnMinus.setOnClickListener(v -> {
                int q = parseIntOr(etQty, 0);
                if (q > 0) etQty.setText(String.valueOf(q - 1));
            });
            btnPlus.setOnClickListener(v -> etQty.setText(String.valueOf(parseIntOr(etQty, 0) + 1)));

            llItems.addView(row);
            productEntries.add(new ProductEntry(p, etQty, etPrice));
        }
        tvEmptyItems.setVisibility(products.isEmpty() ? View.VISIBLE : View.GONE);

        // Peringatan "Kasus B": jenis galon ganda (nama dobel) akibat upgrade dari versi
        // lama — katalog lokal ber-uuid beda dari katalog web, jadi sinkron menampilkannya
        // dobel. Rapikan (hapus duplikat) dari dashboard web.
        TextView tvDup = findViewById(R.id.tvDupJenisWarning);
        if (tvDup != null) {
            java.util.List<String> dups = productDao.getDuplicateJenisNames();
            if (!dups.isEmpty()) {
                tvDup.setText("⚠ Jenis galon ganda terdeteksi: "
                        + android.text.TextUtils.join(", ", dups)
                        + ".\nPilih dengan teliti, lalu rapikan (hapus duplikat) dari dashboard web.");
                tvDup.setVisibility(View.VISIBLE);
            } else {
                tvDup.setVisibility(View.GONE);
            }
        }
    }

    /**
     * Bangun ulang {@link #items} dari semua baris entri dengan jumlah > 0,
     * lalu sinkronkan jumlah kembali / ongkir borongan / total.
     */
    private void onEntriesChanged() {
        items.clear();
        for (ProductEntry pe : productEntries) {
            int qty = parseIntOr(pe.etQty, 0);
            if (qty <= 0) continue;
            double price = parseDoubleOr(pe.etPrice, 0);
            items.add(new TransactionItem(pe.product.getId(), pe.product.getName(), qty, price));
        }
        syncKembaliToTotalJumlah();
        if (Transaction.ONGKIR_BORONGAN.equals(getSelectedOngkirType()) && !userEditedOngkir) {
            applyBoronganDefault();
        }
        updateTotal();
    }

    private void syncKembaliToTotalJumlah() {
        if (!userEditedKembali) {
            syncingKembali = true;
            // Pelanggan "Umum" (walk-in) jarang punya botol galon untuk ditukar, dan
            // Input Promosi Galon selalu untuk PELANGGAN BARU (belum pernah pegang galon
            // depot) — keduanya default 0 botol kembali, bukan auto-match jumlah jual.
            etJumlahKembali.setText(isUmumCustomer() || promosiMode
                    ? "0"
                    : String.valueOf(getTotalJumlah()));
            syncingKembali = false;
        }
    }

    private boolean isUmumCustomer() {
        return CustomerDao.UMUM_NAME.equals(selectedCustomerName);
    }

    /** Update label card reseller afiliasi + visibility tombol "Hapus". */
    private void updateResellerLabel() {
        if (tvSelectedReseller == null) return;
        if (selectedResellerId > 0 && selectedResellerName != null
                && !selectedResellerName.isEmpty()) {
            tvSelectedReseller.setText(selectedResellerName
                    + (selectedResellerAddToPrice ? "  ·  komisi masuk ke harga" : ""));
            btnClearReseller.setVisibility(View.VISIBLE);
        } else {
            tvSelectedReseller.setText("Tidak ada");
            btnClearReseller.setVisibility(View.GONE);
        }
    }

    /**
     * Dialog pilih reseller afiliasi untuk transaksi ini. Reseller terpilih
     * mendapat komisi atas transaksi JUAL ini (lihat ResellerKomisiCalculator).
     */
    private void showResellerPicker() {
        // Dedup salinan lintas-perangkat (nomor sama) → satu reseller per orang di picker.
        List<Customer> resellers = CustomerDao.dedupeByIdentity(customerDao.getResellers());
        if (resellers.isEmpty()) {
            Toast.makeText(this,
                    "Belum ada reseller. Tandai pelanggan sebagai reseller di menu Reseller.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        final CharSequence[] names = new CharSequence[resellers.size()];
        for (int i = 0; i < resellers.size(); i++) {
            Customer r = resellers.get(i);
            String phone = r.getPhone() != null && !r.getPhone().isEmpty()
                    ? "  -  " + r.getPhone() : "";
            names[i] = r.getName() + phone;
        }
        new AlertDialog.Builder(this)
                .setTitle("Pilih Reseller Afiliasi")
                .setItems(names, (d, w) -> {
                    Customer r = resellers.get(w);
                    selectedResellerId = r.getId();
                    selectedResellerName = r.getName();
                    selectedResellerAddToPrice = r.isKomisiAddToPrice();
                    selectedResellerRates = resellerRateDao.getRates(r.getId());
                    autoAffiliateActive = false;   // pilihan afiliasi manual, bukan self-afiliasi otomatis
                    applyResellerPricing();
                    updateResellerLabel();
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    /**
     * Set harga tiap baris produk sesuai reseller terpilih. Kalau reseller pakai
     * "Tambahkan Komisi ke Harga", harga = harga jual + komisi (override per
     * produk dari {@link com.crowja.damiupos.db.ResellerRateDao}, fallback rate
     * global {@link SettingsDao#getResellerKomisi()}). Tanpa flag / tanpa reseller
     * → kembali ke harga jual normal. Menimpa harga yang sempat diubah manual.
     */
    private void applyResellerPricing() {
        double globalRate = settingsDao.getResellerKomisi();
        for (ProductEntry pe : productEntries) {
            // Harga dasar = harga khusus produk untuk pelanggan ini bila diset, selain itu harga jual produk.
            Double custOverride = (selectedCustomerPrices != null)
                    ? selectedCustomerPrices.get(pe.product.getUuid()) : null;
            double price = (custOverride != null) ? custOverride : pe.product.getHargaJual();
            if (selectedResellerAddToPrice && selectedResellerId > 0) {
                Double override = selectedResellerRates.get(pe.product.getId());
                double rate = override != null ? override : globalRate;
                price += rate;
            }
            pe.etPrice.setText(String.valueOf((long) price));
        }
        // setText memicu watcher → onEntriesChanged → updateTotal.
    }

    /**
     * Kalau dibuka dari Detail Reseller (EXTRA_RESELLER_ID), langsung set reseller
     * afiliasi terpilih + terapkan harga (kalau reseller pakai komisi-ke-harga).
     */
    private void prefillResellerFromIntent() {
        long preResellerId = getIntent().getLongExtra(EXTRA_RESELLER_ID, 0);
        if (preResellerId <= 0) return;
        Customer r = customerDao.getById(preResellerId);
        if (r == null || !r.isReseller()) return;
        selectedResellerId = r.getId();
        selectedResellerName = r.getName();
        selectedResellerAddToPrice = r.isKomisiAddToPrice();
        selectedResellerRates = resellerRateDao.getRates(r.getId());
        autoAffiliateActive = false;   // afiliasi eksplisit dari intent, bukan self-afiliasi otomatis
        applyResellerPricing();
        updateResellerLabel();
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
        // Kartu pemilih SAMA dengan daftar Pelanggan: dedup lintas-perangkat + agregat gabungan
        // (total galon / transaksi / konsumsi) + tag foto/koordinat & perangkat asal.
        adapter.setData(CustomerDao.dedupeForDisplay(customerDao.getAll()));

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
                adapter.setData(CustomerDao.dedupeForDisplay(
                        k.isEmpty() ? customerDao.getAll() : customerDao.search(k)));
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
        selectedCustomerPrices = c.getProductPrices();    // harga khusus per produk (null = harga produk)
        applyResellerPricing();                            // harga item ikut harga khusus produk (+ komisi bila ada)
        // Multi-lokasi: dest default = lokasi UTAMA; selectedWajibOngkir mengikuti flag lokasi
        // TERPILIH (fallback flag pelanggan legacy). Lokasi wajib-ongkir → default Ongkos Kirim
        // = Per Galon (opsi "Tanpa" dijaga popup di listener toggle). Cek tombol memicu listener
        // → updateOngkirUI() yang mengisi nominal ongkir default. Hanya untuk JUAL.
        applyCustomerLocations(c);
        if (isJualSelected() && selectedWajibOngkir) toggleOngkirMode.check(R.id.btnOngkirPerGalon);
        tvSelectedCustomer.setText(c.getName());
        userEditedKembali = false;
        // Pelanggan Umum → ownership default = Beli (Umum tidak pinjam botol)
        if (isUmumCustomer()) {
            toggleOwnership.check(R.id.btnOwnershipBeli);
        }
        syncKembaliToTotalJumlah();
        updateOwnershipButtonsForCustomer();
        updateOwnershipUI();
        refreshUseSaldoCard();   // pelanggan baru terpilih → cek apakah reseller
        maybeWarnIncompleteCustomer(c);
        maybeWarnPendingGift(c);
    }

    /**
     * Muat multi-lokasi pelanggan terpilih: dest default = lokasi UTAMA (entri pertama),
     * {@code selectedWajibOngkir} mengikuti flag lokasi TERPILIH — fallback flag pelanggan
     * legacy bila tanpa lokasi. Dipanggil dari {@code applySelectedCustomer} DAN jalur
     * preset {@code customer_id}; pergantian lokasi lewat picker "Kirim ke" me-re-derive
     * flag lagi ({@link #showKirimKePicker}).
     */
    private void applyCustomerLocations(Customer c) {
        selectedLocations = (c != null && c.getLocations() != null)
                ? new ArrayList<>(c.getLocations()) : new ArrayList<>();
        Customer.Location primary = selectedLocations.isEmpty() ? null : selectedLocations.get(0);
        if (primary != null) {
            selectedDestName = primary.name;
            selectedDestLat = primary.lat;
            selectedDestLng = primary.lng;
            selectedWajibOngkir = primary.wajibOngkir;
        } else {
            selectedDestName = null;
            selectedDestLat = 0;
            selectedDestLng = 0;
            selectedWajibOngkir = c != null && c.isWajibOngkir();
        }
        updateKirimKeCard();
        maybeShowMultiLocationInfo(c);
    }

    /** Kartu "Kirim ke" tampil HANYA saat JUAL & pelanggan punya >1 lokasi
     *  (disembunyikan di mode payout/promosi). Label = nama lokasi terpilih. */
    private void updateKirimKeCard() {
        if (cardKirimKe == null) return;
        boolean show = !payoutMode && !promosiMode
                && isJualSelected() && selectedLocations.size() > 1;
        cardKirimKe.setVisibility(show ? View.VISIBLE : View.GONE);
        if (tvSelectedKirimKe != null) {
            tvSelectedKirimKe.setText(selectedDestName != null && !selectedDestName.isEmpty()
                    ? selectedDestName : "—");
        }
    }

    /** Info SEKALI per pelanggan (per sesi layar): pelanggan multi-lokasi → ingatkan staf
     *  memastikan lokasi pengiriman lewat kartu "Kirim ke". */
    private void maybeShowMultiLocationInfo(Customer c) {
        if (c == null || payoutMode || promosiMode) return;
        if (selectedLocations.size() <= 1 || !isJualSelected()) return;
        if (c.getId() == lastLocInfoCustomerId) return;
        lastLocInfoCustomerId = c.getId();
        new AlertDialog.Builder(this)
                .setTitle("📍 Pelanggan Multi-Lokasi")
                .setMessage("Pelanggan \"" + c.getName() + "\" punya " + selectedLocations.size()
                        + " lokasi. Pastikan lokasi pengiriman pada kartu \"Kirim ke\" sudah "
                        + "benar (default: lokasi utama).")
                .setPositiveButton("OK", null)
                .show();
    }

    /** Picker lokasi tujuan pengiriman. Tiap pergantian me-RE-DERIVE selectedWajibOngkir
     *  dari lokasi terpilih + re-apply default Ongkos Kirim Per Galon bila wajib. */
    private void showKirimKePicker() {
        if (selectedLocations.size() <= 1) return;
        final CharSequence[] opts = new CharSequence[selectedLocations.size()];
        for (int i = 0; i < selectedLocations.size(); i++) {
            Customer.Location l = selectedLocations.get(i);
            String nm = (l.name == null || l.name.isEmpty())
                    ? Customer.DEFAULT_LOCATION_NAME : l.name;
            opts[i] = nm + (i == 0 ? " (utama)" : "") + (l.wajibOngkir ? " · Wajib Ongkir" : "");
        }
        new AlertDialog.Builder(this)
                .setTitle("Kirim ke")
                .setItems(opts, (d, w) -> {
                    Customer.Location l = selectedLocations.get(w);
                    selectedDestName = l.name;
                    selectedDestLat = l.lat;
                    selectedDestLng = l.lng;
                    // Re-derive flag wajib ongkir dari lokasi TERPILIH pada SETIAP pergantian —
                    // guard "Tanpa" ongkir langsung mengikuti lokasi baru.
                    selectedWajibOngkir = l.wajibOngkir;
                    if (isJualSelected() && l.wajibOngkir) {
                        toggleOngkirMode.check(R.id.btnOngkirPerGalon);
                    }
                    updateKirimKeCard();
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    /** ID pelanggan terakhir yang sudah diperingatkan — supaya popup tidak spam
     *  saat pelanggan yang sama dipilih ulang dalam satu sesi transaksi. */
    private long lastIncompleteWarnCustomerId = -1;

    /** ID pelanggan terakhir yang gift-nya sudah diberitahukan (anti-spam popup gift). */
    private long lastGiftWarnCustomerId = -1;

    /**
     * Popup informasi (🎁) saat pelanggan terpilih punya GIFT pending: karyawan diingatkan
     * untuk MEMBERIKAN hadiahnya saat transaksi ini. Gift ditarik dari web (branch-wide) dan
     * di-klaim otomatis oleh transaksi JUAL saat disimpan (lihat TransactionDao.insert). Bukan
     * alarm — ini kabar baik; cukup dialog informatif. Pelanggan Umum/walk-in dilewati.
     */
    private void maybeWarnPendingGift(Customer c) {
        if (c == null || c.getId() <= 0 || isUmumCustomer()) return;
        if (c.getId() == lastGiftWarnCustomerId) return;
        java.util.List<com.crowja.damiupos.db.CustomerGiftDao.Gift> gifts =
                new com.crowja.damiupos.db.CustomerGiftDao(DatabaseHelper.getInstance(this))
                        .pendingForCustomer(c.getId());
        if (gifts.isEmpty()) { lastGiftWarnCustomerId = -1; return; }
        lastGiftWarnCustomerId = c.getId();

        StringBuilder sb = new StringBuilder();
        for (com.crowja.damiupos.db.CustomerGiftDao.Gift g : gifts) {
            sb.append("• ").append(g.label());
            if (g.reason != null && !g.reason.isEmpty()) sb.append(" — ").append(g.reason);
            sb.append('\n');
        }
        new AlertDialog.Builder(this)
                .setIcon(android.R.drawable.ic_dialog_info)
                .setTitle("🎁 Pelanggan Dapat Gift!")
                .setMessage("Berikan hadiah berikut kepada \"" + c.getName() + "\" saat transaksi ini:\n\n"
                        + sb + "\nGift akan otomatis tercatat diberikan saat transaksi JUAL disimpan.")
                .setPositiveButton("OK", null)
                .show();
    }

    /**
     * Popup perintah (⚠ + suara nyaring) saat pelanggan yang order BELUM punya foto
     * rumah dan/atau koordinat: karyawan diminta melengkapinya di tempat, supaya
     * dashboard (peta persebaran + foto rumah) tidak bolong. Pelanggan Umum
     * (walk-in) dilewati. "Punya foto" = file lokal ATAU photo_url server, jadi
     * pelanggan hasil sinkron perangkat lain tidak salah diperingatkan.
     */
    private void maybeWarnIncompleteCustomer(Customer c) {
        if (c == null || isUmumCustomer()) return;
        boolean noPhoto = !c.hasPhoto();
        boolean noCoord = !c.hasCoordinates();
        if (!noPhoto && !noCoord) { lastIncompleteWarnCustomerId = -1; return; }
        if (c.getId() == lastIncompleteWarnCustomerId) return;
        lastIncompleteWarnCustomerId = c.getId();

        StringBuilder missing = new StringBuilder();
        if (noPhoto) missing.append("• Belum ada FOTO rumah\n");
        if (noCoord) missing.append("• KOORDINAT lokasi belum ditandai\n");

        playIncompleteAlertSound();
        new AlertDialog.Builder(this)
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setTitle("⚠️ Lengkapi Data Pelanggan!")
                .setMessage("Pelanggan \"" + c.getName() + "\" belum lengkap:\n\n"
                        + missing
                        + "\nSegera FOTO rumah dan/atau TANDAI koordinat pelanggan ini "
                        + "sebelum meninggalkan lokasi.")
                .setPositiveButton("LENGKAPI SEKARANG", (d, w) ->
                        startActivity(new Intent(this, CustomerFormActivity.class)
                                .putExtra("customer_id", c.getId())))
                .setNegativeButton("NANTI", null)
                .show();
    }

    /** Nada alarm default (stream ALARM = nyaring, tidak ikut volume media);
     *  dibatasi 4 detik untuk nada alarm yang panjang/looping. */
    private void playIncompleteAlertSound() {
        try {
            android.net.Uri uri = android.media.RingtoneManager.getDefaultUri(
                    android.media.RingtoneManager.TYPE_ALARM);
            if (uri == null) {
                uri = android.media.RingtoneManager.getDefaultUri(
                        android.media.RingtoneManager.TYPE_NOTIFICATION);
            }
            if (uri == null) return;
            final android.media.MediaPlayer mp = new android.media.MediaPlayer();
            mp.setAudioAttributes(new android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            mp.setDataSource(this, uri);
            mp.prepare();
            mp.start();
            new android.os.Handler(getMainLooper()).postDelayed(() -> {
                try { mp.stop(); } catch (Exception ignored) {}
                try { mp.release(); } catch (Exception ignored) {}
            }, 4000);
        } catch (Exception ignored) {
            // Suara hanya penekanan — popup tetap tampil walau audio gagal.
        }
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

    /** Popup "pelanggan promosi kembali order" — dijalankan lalu meneruskan ke struk (onContinue). */
    private void showRepeatPromoDialog(String custName, Runnable onContinue) {
        String name = (custName != null && !custName.isEmpty()) ? custName : "Pelanggan";
        new AlertDialog.Builder(this)
                .setTitle("🎉 Pelanggan Promosi Kembali Order!")
                .setMessage("\"" + name + "\" dulu diakuisisi lewat promosi galon GRATIS, dan sekarang "
                        + "KEMBALI order. Promosi berhasil jadi pelanggan berulang — berikan pelayanan "
                        + "terbaik agar terus setia! 🙌")
                .setCancelable(false)
                .setPositiveButton("Lanjut", (d, w) -> onContinue.run())
                .show();
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
            // Pencairan komisi: tampilkan rincian potongan dari saldo komisi reseller.
            double saldoPotong = saldoDipotong();
            if (saldoPotong > 0) {
                appendStyled(msg, "\nDibayar dari Saldo Komisi: − Rp "
                                + nf.format(Math.round(saldoPotong)),
                        Color.parseColor("#2E7D32"), true, 1.0f);
                double sisaBayar = Math.max(0, totalHarga - saldoPotong);
                appendStyled(msg, "\nSisa Dibayar: Rp " + nf.format(Math.round(sisaBayar)),
                        Color.parseColor("#1565C0"), true, 1.05f);
            }
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
                .setPositiveButton(fIsJual ? "Lanjut" : "Simpan", (d, w) -> {
                    // Promosi GRATIS dengan total Rp 0 → tidak ada yang dibayar, jadi dialog
                    // metode pembayaran dilewati. (Gratis + ongkir berbayar → total > 0 →
                    // metode pembayaran tetap ditanya, untuk pencatatan ongkirnya.)
                    boolean gratisPromo = promosiMode && isPromosiGratis() && fTotal <= 0;
                    if (fIsJual && !gratisPromo) {
                        // JUAL: wajib pilih metode pembayaran dulu.
                        showPaymentPicker(method -> {
                            pendingPayment = method;
                            doSave(fIsJual, fTotalJumlah, fOngkir, fTotal, fJumlahKembali,
                                    fOngkirType, fOwnership, fHargaBotol);
                        });
                    } else {
                        pendingPayment = null; // KEMBALI / promosi gratis: tidak ada pembayaran
                        doSave(fIsJual, fTotalJumlah, fOngkir, fTotal, fJumlahKembali,
                                fOngkirType, fOwnership, fHargaBotol);
                    }
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    /** Field: metode pembayaran yang dipilih sebelum simpan transaksi JUAL. */
    private String pendingPayment;

    /** Callback metode pembayaran. */
    private interface OnPayment { void onPick(String method); }

    /** Dialog pilih metode pembayaran: Tunai / QRIS / Transfer. */
    private void showPaymentPicker(OnPayment cb) {
        final String[] labels = {"Tunai", "QRIS", "Transfer"};
        final String[] values = {Transaction.PAY_TUNAI, Transaction.PAY_QRIS,
                Transaction.PAY_TRANSFER};
        new AlertDialog.Builder(this)
                .setTitle("Metode Pembayaran")
                .setItems(labels, (d, which) -> cb.onPick(values[which]))
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
                .setPositiveButton("Lanjut", (d, w) -> showPaymentPicker(method -> {
                    pendingPayment = method;
                    doSaveJualBotol(fQty, fHarga, fTotal);
                }))
                .setNegativeButton("Batal", null)
                .show();
    }

    private void updateTanggalTrxButton() {
        if (tvSelectedTrxDate == null) return;
        try {
            Date d = trxDbFmt.parse(selectedTrxDate);
            if (d != null) {
                // 2 baris (card sempit): tanggal di atas, jam di bawah.
                tvSelectedTrxDate.setText(trxDateOnlyFmt.format(d)
                        + "\n" + trxTimeOnlyFmt.format(d));
            } else {
                tvSelectedTrxDate.setText("Sekarang");
            }
        } catch (Exception e) {
            tvSelectedTrxDate.setText("Sekarang");
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
        trx.setPaymentMethod(pendingPayment);
        if (selectedTrxDate != null) trx.setTanggal(selectedTrxDate);
        // Jual botol juga masuk Antrian Delivery (TYPE_JUAL) → bawa lokasi tujuan terpilih.
        if (selectedDestLat != 0 || selectedDestLng != 0) {
            trx.setDeliveryDestName(selectedDestName);
            trx.setDeliveryDestLat(selectedDestLat);
            trx.setDeliveryDestLng(selectedDestLng);
        }
        // items intentionally kosong — penanda transaksi botol-only
        String catatan = etCatatan.getText() != null ? etCatatan.getText().toString().trim() : "";
        String marker = "[JUAL BOTOL KOSONG]";
        catatan = catatan.isEmpty() ? marker : (marker + " " + catatan);
        trx.setCatatan(catatan);
        long newBotolTrxId = transactionDao.insert(trx);
        // JUAL masuk Antrian Delivery → dorong segera ke dashboard (jangan tunggu poll).
        com.crowja.damiupos.sync.SyncScheduler.syncNow(getApplicationContext());

        // Buka struk
        Intent r = new Intent(this, ReceiptActivity.class);
        r.putExtra(ReceiptActivity.EXTRA_CUSTOMER_NAME, selectedCustomerName);
        r.putExtra(ReceiptActivity.EXTRA_CUSTOMER_PHONE, selectedCustomerPhone);
        r.putExtra(ReceiptActivity.EXTRA_CUSTOMER_ID, selectedCustomerId);
        com.crowja.damiupos.model.Transaction savedBotolTrx = transactionDao.getById(newBotolTrxId);
        if (savedBotolTrx != null && savedBotolTrx.getDeliveryToken() != null) {
            r.putExtra(ReceiptActivity.EXTRA_DELIVERY_TOKEN, savedBotolTrx.getDeliveryToken());
        }
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
        if (pendingPayment != null) r.putExtra(ReceiptActivity.EXTRA_PAYMENT_METHOD, pendingPayment);
        // JUAL masuk Antrian Delivery → struk dikirim ke pelanggan saat order Selesai.
        r.putExtra(ReceiptActivity.EXTRA_DEFER_CUSTOMER_SEND, true);
        startActivity(r);
        finish();
    }

    private double parseDoubleOr(EditText et, double def) {
        if (et == null || et.getText() == null) return def;
        String s = et.getText().toString().trim();
        if (s.isEmpty()) return def;
        try { return Double.parseDouble(s); } catch (NumberFormatException e) { return def; }
    }

    private int parseIntOr(EditText et, int def) {
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

        // Promosi GRATIS = pemberian gratis di lokasi → SELALU Rp 0, apa pun status Wajib Ongkir
        // pelanggan. Invarian ditegakkan di TITIK SIMPAN (bukan hanya lewat toggle ongkir di UI,
        // yang bisa bocor karena urutan init) supaya tak ada ongkir yang menyusup ke riwayat
        // ("Rp 1.000" palsu). Semua harga item sudah dipaksa 0 (applyPromosiPricing) → total = 0
        // langsung (BUKAN total − ongkir: untuk ongkir Per Galon, `ongkir` adalah tarif/galon
        // sedangkan total memuat tarif×jumlah, jadi pengurangan satu unit menyisakan sisa saat
        // qty ≥ 2). Server juga menjaga ini lewat penanda [PROMOSI] (guardPromoOngkir).
        if (isJual && promosiMode && isPromosiGratis()) {
            totalHarga = 0;
            ongkir = 0;
            ongkirType = Transaction.ONGKIR_NONE;
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
            trx.setPaymentMethod(pendingPayment); // metode bayar dipilih sebelum simpan
            trx.setResellerId(selectedResellerId); // reseller afiliasi (0 = tidak ada)
            // Lokasi tujuan pengiriman terpilih ("Kirim ke") — navigasi delivery memakai
            // koordinat ini (fallback koordinat pelanggan bila 0/kosong).
            if (selectedDestLat != 0 || selectedDestLng != 0) {
                trx.setDeliveryDestName(selectedDestName);
                trx.setDeliveryDestLat(selectedDestLat);
                trx.setDeliveryDestLng(selectedDestLng);
            }
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
        // Promosi GRATIS: tandai supaya bisa dibedakan dari penjualan biasa (poin promosi tetap
        // dihitung server dari pelanggan baru, marker hanya penanda). Berbayar = penjualan normal.
        if (promosiMode && isPromosiGratis()) {
            catatan = catatan.isEmpty() ? PROMO_MARKER : (PROMO_MARKER + " " + catatan);
        }
        // For ganti rugi, prepend a marker into catatan so receipt can detect on re-view
        if (!isJual && totalHarga > 0) {
            String marker = "[GANTI RUGI " + rusak + " galon rusak]";
            catatan = catatan.isEmpty() ? marker : (marker + " " + catatan);
        }
        // Pelanggan reseller membayar sebagian/seluruh order dari saldo komisinya.
        double saldoUsed = isJual ? saldoDipotong() : 0;
        if (saldoUsed > 0) {
            NumberFormat nfm = NumberFormat.getInstance(new Locale("id", "ID"));
            String marker = "[SALDO KOMISI Rp " + nfm.format(Math.round(saldoUsed)) + "]";
            catatan = catatan.isEmpty() ? marker : (marker + " " + catatan);
        }
        if (!catatan.isEmpty()) trx.setCatatan(catatan);
        long newTrxId = transactionDao.insert(trx);
        // Saldo komisi terpakai → catat pencairan (TYPE_UANG, tanpa expense depot)
        // sehingga saldo komisi reseller berkurang sesuai yang dipotong.
        if (saldoUsed > 0) {
            new com.crowja.damiupos.db.ResellerWithdrawalDao(DatabaseHelper.getInstance(this))
                    .insert(selectedCustomerId, com.crowja.damiupos.db.ResellerWithdrawalDao.TYPE_UANG,
                            0, saldoUsed, "Bayar transaksi pakai saldo komisi", 0);
        }
        // Transaksi baru (JUAL masuk Antrian Delivery) → dorong segera ke dashboard
        // tanpa menunggu polling ~60 detik, supaya antrean delivery real-time.
        com.crowja.damiupos.sync.SyncScheduler.syncNow(getApplicationContext());

        if (isJual) {
            Intent r = new Intent(this, ReceiptActivity.class);
            r.putExtra(ReceiptActivity.EXTRA_CUSTOMER_NAME, selectedCustomerName);
            r.putExtra(ReceiptActivity.EXTRA_CUSTOMER_PHONE, selectedCustomerPhone);
            r.putExtra(ReceiptActivity.EXTRA_CUSTOMER_ID, selectedCustomerId);
            // Gift yang baru di-klaim transaksi ini (TransactionDao.insert) → tampil di struk.
            r.putExtra(ReceiptActivity.EXTRA_GIFT_TRX_UUID, transactionDao.getSyncUuidById(newTrxId));
            // Token link lacak → dibawa ke struk supaya tombol "Kirim Struk + Tracking" bisa
            // mengirim link pantau pengiriman ke pelanggan.
            com.crowja.damiupos.model.Transaction savedTrx = transactionDao.getById(newTrxId);
            if (savedTrx != null && savedTrx.getDeliveryToken() != null) {
                r.putExtra(ReceiptActivity.EXTRA_DELIVERY_TOKEN, savedTrx.getDeliveryToken());
            }
            if (forwardedInboxSenderName != null) {
                r.putExtra(ReceiptActivity.EXTRA_INBOX_SENDER_NAME, forwardedInboxSenderName);
            }
            r.putExtra(ReceiptActivity.EXTRA_ITEMS_JSON,
                    TransactionItem.listToJson(items));
            r.putExtra(ReceiptActivity.EXTRA_JUMLAH_KEMBALI, jumlahKembali);
            r.putExtra(ReceiptActivity.EXTRA_ONGKIR, ongkir);
            r.putExtra(ReceiptActivity.EXTRA_ONGKIR_TYPE, ongkirType);
            r.putExtra(ReceiptActivity.EXTRA_TOTAL_HARGA, totalHarga);
            String catatanStr = etCatatan.getText() != null ? etCatatan.getText().toString().trim() : "";
            r.putExtra(ReceiptActivity.EXTRA_CATATAN, catatanStr);
            if (pendingPayment != null) r.putExtra(ReceiptActivity.EXTRA_PAYMENT_METHOD, pendingPayment);
            // Pencairan komisi: bagian yang dibayar dari saldo komisi + sisa saldo, untuk struk.
            if (saldoUsed > 0) {
                r.putExtra(ReceiptActivity.EXTRA_SALDO_DIPOTONG, saldoUsed);
                r.putExtra(ReceiptActivity.EXTRA_SALDO_AFTER, Math.max(0, resellerSaldo - saldoUsed));
            }

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
            // JUAL masuk Antrian Delivery → struk dikirim ke pelanggan saat order Selesai.
            r.putExtra(ReceiptActivity.EXTRA_DEFER_CUSTOMER_SEND, true);
            // Popup konversi promosi: pelanggan yang DULU diakuisisi lewat galon GRATIS kini KEMBALI
            // order (sinyal promo berhasil, selaras kolom "Order Ulang" di web Promosi Marketing).
            // Tampilkan dulu, baru lanjut ke struk. Detektor pakai data lokal (offline-friendly).
            final Intent receiptIntent = r;
            String trxTanggal = savedTrx != null ? savedTrx.getTanggal() : null;
            if (transactionDao.isRepeatFromFreePromo(selectedCustomerId, trxTanggal)) {
                showRepeatPromoDialog(selectedCustomerName, () -> { startActivity(receiptIntent); finish(); });
            } else {
                startActivity(receiptIntent);
                finish();
            }
        } else {
            if (totalHarga > 0) {
                Intent r = new Intent(this, ReceiptActivity.class);
                r.putExtra(ReceiptActivity.EXTRA_CUSTOMER_NAME, selectedCustomerName);
                r.putExtra(ReceiptActivity.EXTRA_CUSTOMER_PHONE, selectedCustomerPhone);
                r.putExtra(ReceiptActivity.EXTRA_CUSTOMER_ID, selectedCustomerId);
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
