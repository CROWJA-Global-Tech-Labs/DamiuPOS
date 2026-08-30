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
import com.crowja.damiupos.sync.SyncApi;
import com.crowja.damiupos.sync.SyncSettings;
import com.crowja.damiupos.paywall.PaywallDialogFragment;
import com.crowja.damiupos.model.Customer;
import com.crowja.damiupos.model.Product;
import com.crowja.damiupos.model.Transaction;
import com.crowja.damiupos.model.TransactionItem;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.card.MaterialCardView;
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

    /** Intent extra: id baris OrderInbox (Pesanan Terjadwal) sumber transaksi ini. Bila di-set,
     *  inbox ditandai SELESAI (APPROVED) TEPAT saat transaksi JUAL benar-benar tersimpan — jadi
     *  flag selesai mencerminkan "sudah dibuatkan transaksi", bukan sekadar tombol ditekan. */
    public static final String EXTRA_INBOX_ID = "inbox_id";

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
    /** Ketidakcocokan ongkir sudah dikonfirmasi staf (2 klik) → jangan tanya lagi untuk isian ini.
     *  Direset begitu pelanggan / mode ongkir / nominalnya berubah (lihat resetOngkirAck). */
    private boolean ongkirAcknowledged = false;
    private TextView tvSelectedCustomer, tvTotalHarga, tvEmptyItems;
    private TextView tvSelectedReseller, btnClearReseller, tvSelectedTrxDate;
    private TextInputEditText etJumlahKembali, etOngkir, etCatatan;
    private TextInputEditText etHargaGantiRugi, etJumlahRusak;
    private TextInputEditText etHargaBotol;
    private TextInputEditText etJumlahBotolJual, etHargaBotolJual;
    private TextInputLayout tilOngkir, tilJumlahKembali, tilHargaBotol;
    private View ongkirModeContainer, cardCustomer, cardItems, cardOwnership, gantiRugiContainer, returnModeContainer;
    private View cardJualBotol, cardDate, cardReseller, cardKembali;
    // "Perangkat yang ditugaskan" (staf/marketing/SPV): picker perangkat penangan transaksi (default =
    // perangkat ini). assignUuids paralel dengan item spinner; indeks 0 = null (perangkat sendiri).
    private View cardAssignDevice;
    private android.widget.Spinner spinnerAssignDevice;
    private boolean assignDeviceEligible;
    // STAF & MARKETING: default penugasan = DIRI SENDIRI (tak auto-route ke wilayah); mengalihkan ke
    // perangkat/staf LAIN lewat aksi manual minta konfirmasi YA/BATAL (agar sadar kredit galon/komisi
    // berpindah) — keduanya kurir/penjual perorangan yang membuat order untuk diri sendiri sehari-hari,
    // jadi butuh perlindungan dari salah-pindah kredit tanpa sadar. SPV TIDAK ikut flag ini: peran
    // dispatch/koordinasi SPV memang butuh auto-route wilayah tanpa gerbang konfirmasi (lihat bawah).
    private boolean assignDefaultSelf;
    private boolean assignUserTouched;       // spinner disentuh user (bukan set programatik) → picu konfirmasi
    private int assignPrevPos;               // posisi terakhir yang menetap (utk revert saat BATAL)
    private boolean suppressAssignConfirm;   // cegah re-entry saat revert programatik
    private final java.util.List<String> assignUuids = new java.util.ArrayList<>();
    /** "Pesanan Terbuka" (lelang): item TERAKHIR picker penugasan — sentinel yang SAMA dengan
     *  dropdown web (App\Http\Controllers\Web\TransactionController::OPEN_DISPATCH_TARGET), BUKAN
     *  uuid perangkat sungguhan. Server (SyncController::applyRow) mencegatnya sebelum blok
     *  terjemahan rute biasa dan menstempel delivery_open_dispatch_at alih-alih merutekan. */
    private static final String OPEN_DISPATCH_TARGET = "__OPEN_DISPATCH__";
    // Koordinat efektif pelanggan terpilih (lokasi tujuan bila ada, else koordinat dasar) → dipakai
    // memilih perangkat wilayah default pada picker penugasan.
    private double selectedWLat, selectedWLng;
    // Override penugasan perangkat pelanggan terpilih (di-set web) — di-cache saat pilih pelanggan
    // (via baca MERGED, jadi override yang menempel di salinan lintas-perangkat ikut terbaca) supaya
    // selectWilayahDefaultDevice tak perlu query ulang tiap dipanggil (termasuk saat roster refresh).
    private String selectedAssignOverride;
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
        final MaterialCardView row;
        final TextView tvLastBought;
        ProductEntry(Product p, EditText q, EditText pr, MaterialCardView row, TextView tvLastBought) {
            this.product = p; this.etQty = q; this.etPrice = pr;
            this.row = row; this.tvLastBought = tvLastBought;
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
    /** Galon DIPINJAM pelanggan terpilih (getSaldoGalon) — basis default Galon Kembali = min(jual,
     *  dipinjam) + peringatan bila kembali melebihi. 0 = walk-in / tak ada. */
    private int selectedHeld = 0;
    /** Klik Simpan saat kembali > dipinjam: perlu 2x (peringatan berkedip muncul 2x) baru lanjut. */
    private int returnOverWarnCount = 0;
    /** Harga khusus per produk pelanggan terpilih { product_uuid: harga } (null = harga produk standar). */
    private java.util.Map<String, Double> selectedCustomerPrices = null;

    // --- Multi-lokasi pelanggan → "Kirim ke" (lokasi tujuan pengiriman) ---
    /** Daftar lokasi pelanggan terpilih (entri pertama = utama; kosong = tanpa lokasi). */
    private java.util.List<Customer.Location> selectedLocations = new ArrayList<>();
    /** Nama lokasi → jumlah transaksi JUAL yang dikirim ke sana (badge "Kirim ke"); diisi ulang
     *  tiap pelanggan berganti. Lihat {@link #deliveryCountFor}. */
    private java.util.Map<String, Integer> selectedLocationCounts = new java.util.HashMap<>();
    /** Lokasi tujuan pengiriman TERPILIH — dipersist ke transaksi JUAL (delivery_dest_*). */
    private String selectedDestName;
    private double selectedDestLat, selectedDestLng;
    /** Indeks lokasi tujuan terpilih dalam selectedLocations (0 = utama) — untuk fallback foto rumah. */
    private int selectedDestIndex = 0;
    /** Foto rumah pelanggan terpilih (fallback preview lokasi utama saat lokasi belum punya foto sendiri). */
    private String custPhotoPath, custPhotoUrl;
    private View cardKirimKe;
    private TextView tvSelectedKirimKe, tvKirimKeCoord, tvKirimKeLabel, tvKirimKeGanti, tvKirimKeCount,
            tvLastOrderLine;
    private android.widget.ImageView ivKirimKeThumb;
    /** Pelanggan terakhir yang sudah diberi info multi-lokasi — popup sekali per pelanggan. */
    private long lastLocInfoCustomerId = -1;

    // --- Gunakan Saldo Komisi (muncul saat pelanggan = reseller, mode JUAL) ---
    private View cardUseSaldo, layoutSaldoPotong, layoutSaldoBreakdown;
    private com.google.android.material.checkbox.MaterialCheckBox cbUseSaldo;
    // --- Potong Saldo Refund (semua pelanggan) ---
    private com.google.android.material.card.MaterialCardView cardUseRefund;
    private com.google.android.material.checkbox.MaterialCheckBox cbUseRefund;
    private TextView tvSaldoRefund;
    private View layoutRefundPotong;
    private com.google.android.material.textfield.TextInputEditText etRefundDipotong;
    private View layoutRefundBreakdown;
    private TextView tvDipotongRefund;
    private double refundSaldo;   // saldo refund pelanggan terpilih
    private TextView tvSaldoKomisi, tvSisaBayar, tvDipotongSaldo;
    private TextInputEditText etSaldoDipotong;
    private double resellerSaldo = 0;   // saldo komisi pelanggan reseller terpilih
    private double lastJualTotal = 0;   // total JUAL terkini (untuk hitung sisa bayar)

    // --- ⚡ Tandai Prioritas (order langsung MAUPUN Pesanan Tertunda) ---
    private View cardPrioritas;
    private com.google.android.material.checkbox.MaterialCheckBox cbPrioritas;
    private View layoutPriorityReason;
    private TextInputEditText etPriorityReason;

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

    // Pesanan Tertunda: diminta via tombol ⏸ (bukan Simpan biasa) — trySave()/doSave() membaca flag
    // ini untuk menandai delivery_status=TERTUNDA + jadwal lanjut, cermin web (App\Support\TertundaSchedule).
    private android.view.View btnTertunda;
    private boolean tertundaRequested = false;
    private String tertundaResumeAtDb;   // format trxDbFmt — dipakai juga sbg selectedTrxDate (tanggal ikut jadwal)

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
        tvKirimKeCoord = findViewById(R.id.tvKirimKeCoord);
        tvKirimKeLabel = findViewById(R.id.tvKirimKeLabel);
        tvKirimKeGanti = findViewById(R.id.tvKirimKeGanti);
        tvKirimKeCount = findViewById(R.id.tvKirimKeCount);
        tvLastOrderLine = findViewById(R.id.tvLastOrderLine);
        ivKirimKeThumb = findViewById(R.id.ivKirimKeThumb);
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
                maybeWarnDuplicateOrderToday(c);
                // WAJIB juga di jalur preset ini — bukan cuma di applySelectedCustomer. Jalur inilah
                // yang dipakai hampir semua pintu masuk "Transaksi Baru untuk pelanggan X" (Follow
                // Up, Detail Pelanggan, Inbox, bubble WA, deep-link). Tanpa ini serverGuard tetap
                // null selamanya di jalur tersebut → popup "sudah ada di antrian" TAK PERNAH muncul
                // dan gerbang "sudah order hari ini" jatuh ke data lokal yang terisolasi
                // per-perangkat — persis kasus lintas-perangkat yang jadi alasan fitur ini ada.
                highlightLastPurchasedItems(c);
                fetchOrderInsightsAsync(c);
            }
        }
        // Kalau dari Inbox & user sudah correct nomor di Balas confirm-dialog,
        // override DB phone dengan nomor yg di-confirm itu. Supaya struk + share
        // pakai nomor yg benar2 sesuai chat WA pelanggan.
        String inboxPhone = getIntent().getStringExtra(EXTRA_INBOX_SENDER_PHONE);
        if (inboxPhone != null && !inboxPhone.trim().isEmpty()) {
            selectedCustomerPhone = inboxPhone.trim();
        }
        // Default untuk Umum: jumlah kembali = 0, ownership = Botol Sendiri
        // (override XML default "1" dan last-saved ownership; galon keluar/masuk
        // tidak dicatat untuk Pelanggan Umum)
        if (isUmumCustomer()) {
            syncingKembali = true;
            etJumlahKembali.setText("0");
            syncingKembali = false;
            toggleOwnership.check(R.id.btnOwnershipBawaSendiri);
        }
        // Sinkronkan visibility opsi ownership + tipe dengan pelanggan terpilih
        // (Umum: tanpa "Di Pinjam" & tanpa tipe "Galon Kembali")
        updateOwnershipButtonsForCustomer();

        toggleType.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            updateTypeUI();
            // Gerbang anti-order-ganda hanya berlaku untuk JUAL, dan dijalankan saat pelanggan
            // DIPILIH — kalau jenisnya baru diubah ke JUAL SETELAH memilih pelanggan, keduanya
            // sudah terlewat. Jalankan ulang di sini (anti-spam menjaga agar tak dobel bila tadi
            // memang sudah tampil). Web tak punya masalah ini: gerbangnya dicek saat submit.
            if (checkedId == R.id.btnTypeJual && selectedCustomerId > 0) {
                Customer sel = customerDao.getById(selectedCustomerId);
                if (sel != null) maybeWarnDuplicateOrderToday(sel);
                maybeWarnOpenQueueOrder();
            }
        });

        cardCustomer.setOnClickListener(v -> showCustomerPicker());

        // Reseller afiliasi (opsional) — hanya relevan untuk transaksi JUAL.
        cardReseller = findViewById(R.id.cardReseller);
        tvSelectedReseller = findViewById(R.id.tvSelectedReseller);
        btnClearReseller = findViewById(R.id.btnClearReseller);
        cardReseller.setOnClickListener(v -> showResellerPicker());
        setupAssignDeviceCard();   // "Perangkat yang ditugaskan" (staf/marketing/SPV)
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
        setupUseRefundCard();
        setupPrioritasCard();

        etOngkir.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                if (!syncingOngkir) {
                    userEditedOngkir = true;
                    ongkirAcknowledged = false;   // nominal diubah → konfirmasi lama gugur
                }
            }
        });

        etJumlahKembali.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                if (!syncingKembali) {
                    userEditedKembali = true;
                    returnOverWarnCount = 0;   // nilai diedit → reset gerbang peringatan
                }
            }
        });

        // Simpan biasa SELALU membatalkan niat Tertunda dari percobaan sebelumnya yang gagal
        // validasi (mis. "Pilih pelanggan dulu") — tombol terakhir yang ditekan yang menang.
        findViewById(R.id.btnSimpan).setOnClickListener(v -> {
            tertundaRequested = false;
            trySave();
        });
        btnTertunda = findViewById(R.id.btnTertunda);
        btnTertunda.setOnClickListener(v -> showTertundaScheduleDialog());

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
                R.id.cardAssignDevice,
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
        // ⚡ Tandai Prioritas: JUAL air saja (cermin doSave() yang hanya menstempelnya untuk isJual).
        // Reset centang saat disembunyikan — checkbox TETAP hidup di memori walau View-nya GONE, jadi
        // tanpa ini centangan dari mode JUAL bisa "menempel" senyap ke KEMBALI/Jual Botol berikutnya.
        if (cardPrioritas != null) {
            cardPrioritas.setVisibility(isJual ? View.VISIBLE : View.GONE);
            if (!isJual && cbPrioritas != null && cbPrioritas.isChecked()) {
                cbPrioritas.setChecked(false);
            }
        }
        updateKirimKeCard();     // "Kirim ke" hanya untuk JUAL & pelanggan multi-lokasi
        refreshUseSaldoCard();   // "Gunakan Saldo Komisi" hanya untuk JUAL
        // "Perangkat yang ditugaskan" hanya untuk JUAL air & role staf/marketing/SPV (bukan pencairan komisi).
        if (cardAssignDevice != null) {
            cardAssignDevice.setVisibility(isJual && assignDeviceEligible && !payoutMode ? View.VISIBLE : View.GONE);
        }
        // Pesanan Tertunda: JUAL air saja (KEMBALI/Jual Botol tak diantrikan di HP sama sekali —
        // tak ada antrian aktif untuk "ditunda"), bukan mode payout/promosi (akuisisi di lokasi).
        if (btnTertunda != null) {
            btnTertunda.setVisibility(isJual && !payoutMode && !promosiMode ? View.VISIBLE : View.GONE);
        }
        updateTotal();
    }

    /** "Perangkat yang ditugaskan" (staf/marketing/SPV): siapkan picker perangkat penangan. Item 0 =
     *  "Perangkat ini" (tanpa penugasan). Hanya untuk role staf/marketing/SPV; visibilitas kartu
     *  diatur updateTypeUI (JUAL saja). */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private void setupAssignDeviceCard() {
        cardAssignDevice = findViewById(R.id.cardAssignDevice);
        spinnerAssignDevice = findViewById(R.id.spinnerAssignDevice);
        if (cardAssignDevice == null || spinnerAssignDevice == null) return;

        com.crowja.damiupos.model.User cur = settingsDao.getCurrentUserId() > 0
                ? new com.crowja.damiupos.db.UserDao(DatabaseHelper.getInstance(this)).getById(settingsDao.getCurrentUserId())
                : null;
        // ADMIN ikut boleh menargetkan perangkat penangan (permintaan owner 2026-08-27): dia yang
        // membagi pesanan dari kantor. Staf tetap dipertahankan — kemampuan lama, tak ada yang minta
        // dicabut.
        assignDeviceEligible = cur != null
                && (cur.isAdmin() || cur.isMarketing() || cur.isSpv() || cur.isStaf());
        // assignDefaultSelf = perlu KONFIRMASI saat mengalihkan ke staf lain, karena kredit galon /
        // komisi ikut berpindah dan role ini biasanya mencatat untuk dirinya sendiri. Admin & SPV
        // SENGAJA tidak masuk: bagi mereka menugaskan ke perangkat lain justru pekerjaan normal,
        // jadi popup konfirmasi tiap kali cuma jadi gangguan.
        assignDefaultSelf = cur != null && (cur.isMarketing() || cur.isStaf());
        if (!assignDeviceEligible) {
            cardAssignDevice.setVisibility(View.GONE);
            return;
        }
        populateAssignSpinner();
        // Visibilitas awal (updateTypeUI awal berjalan sebelum eligibility diset saat onCreate).
        cardAssignDevice.setVisibility(isJualSelected() && !payoutMode ? View.VISIBLE : View.GONE);

        // Konfirmasi alih-penugasan (MARKETING): sentuhan menandai AKSI USER; setSelection programatik
        // (populate / roster refresh) TIDAK. Saat user memilih perangkat/staf LAIN (pos>0) → popup YA/BATAL.
        assignPrevPos = spinnerAssignDevice.getSelectedItemPosition();
        spinnerAssignDevice.setOnTouchListener((v, ev) -> { assignUserTouched = true; return false; });
        spinnerAssignDevice.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id) {
                if (suppressAssignConfirm) { suppressAssignConfirm = false; assignPrevPos = pos; return; }
                boolean isOpenDispatch = pos > 0 && pos < assignUuids.size()
                        && OPEN_DISPATCH_TARGET.equals(assignUuids.get(pos));
                if (assignUserTouched && isOpenDispatch) {
                    // Murni informasional (bukan "alih ke staf lain" — tak ada staf spesifik di sini).
                    assignUserTouched = false;
                    assignPrevPos = pos;
                    showOpenDispatchExplainer();
                } else if (assignUserTouched && assignDefaultSelf && pos > 0) {
                    assignUserTouched = false;
                    confirmAssignToOtherStaff(pos);
                } else {
                    assignUserTouched = false;
                    assignPrevPos = pos;
                }
            }

            @Override public void onNothingSelected(android.widget.AdapterView<?> p) {}
        });

        refreshAssignRosterAsync();   // segarkan roster dari server (best-effort) untuk first-run
    }

    /** Konfirmasi MARKETING mengalihkan penugasan transaksi ke perangkat/staf LAIN (bukan diri sendiri):
     *  YA menetapkan pilihan; BATAL mengembalikan ke pilihan semula — agar kredit galon/komisi tidak
     *  berpindah tanpa disadari. */
    private void confirmAssignToOtherStaff(final int pos) {
        final int fromPos = assignPrevPos;
        String staffLabel = String.valueOf(spinnerAssignDevice.getItemAtPosition(pos));
        new AlertDialog.Builder(this)
                .setTitle("Alihkan Penugasan ke Staf Lain?")
                .setMessage("Transaksi ini akan DITUGASKAN ke \"" + staffLabel + "\" — bukan kamu sendiri.\n\n"
                        + "Kredit galon & komisi transaksi ini akan masuk ke sana, bukan ke kamu. Lanjutkan?")
                .setCancelable(false)
                .setPositiveButton("YA", (d, w) -> assignPrevPos = pos)
                .setNegativeButton("BATAL", (d, w) -> {
                    suppressAssignConfirm = true;
                    spinnerAssignDevice.setSelection(fromPos);   // kembalikan ke pilihan semula
                })
                .show();
    }

    /** Penjelasan "Pesanan Terbuka" (lelang) — tampil begitu operator MEMILIH opsi ini di picker
     *  penugasan. Murni informasional; operator boleh mengganti pilihan lagi kapan saja sebelum simpan. */
    private void showOpenDispatchExplainer() {
        new AlertDialog.Builder(this)
                .setTitle("🎲 Pesanan Terbuka (Lelang)")
                .setMessage("Order ini tidak diarahkan ke satu perangkat. Semua staf yang mencentang "
                        + "\"Antrian Perangkat Lain\" di HP-nya akan melihatnya berkedip di antrian delivery "
                        + "dengan stopwatch berjalan.\n\n"
                        + "Staf mana pun boleh menekan \"Ambil Alih\" untuk mengklaimnya — begitu diklaim, "
                        + "order langsung hilang dari layar staf lain dan masuk antrian staf yang mengklaim.")
                .setPositiveButton("Mengerti", null)
                .show();
    }

    /** Isi spinner dari roster cache (/api/me). Pertahankan pilihan uuid saat ini bila masih ada
     *  (dipanggil ulang setelah refresh async). Perangkat sendiri tak diulang (sudah = "Perangkat ini"). */
    private void populateAssignSpinner() {
        if (spinnerAssignDevice == null) return;
        assignUserTouched = false;   // setSelection di bawah = programatik, jangan picu konfirmasi
        SyncSettings cfg = new SyncSettings(settingsDao);
        String myUuid = cfg.getDeviceUuid();
        String prev = selectedAssignUuid();   // pertahankan pilihan lintas repopulate

        assignUuids.clear();
        java.util.List<String> labels = new java.util.ArrayList<>();
        assignUuids.add(null);                 // item 0 = perangkat ini (tanpa penugasan)
        labels.add("Perangkat ini");
        try {
            org.json.JSONArray arr = new org.json.JSONArray(cfg.getDeviceRoster());
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject d = arr.optJSONObject(i);
                if (d == null) continue;
                String uuid = d.optString("uuid", "");
                String name = d.optString("name", "Perangkat");
                if (uuid.isEmpty() || uuid.equals(myUuid)) continue;
                assignUuids.add(uuid);
                labels.add(name);
            }
        } catch (Exception ignored) {}
        // "Pesanan Terbuka" (lelang): TANPA perangkat tujuan spesifik — staf perangkat mana pun
        // (yang mencentang "Antrian Perangkat Lain") boleh mengklaimnya via "Ambil Alih".
        assignUuids.add(OPEN_DISPATCH_TARGET);
        labels.add("🎲 Pesanan Terbuka (Lelang)");

        android.widget.ArrayAdapter<String> ad = new android.widget.ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, labels);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerAssignDevice.setAdapter(ad);
        int keep = prev != null ? assignUuids.indexOf(prev) : 0;
        spinnerAssignDevice.setSelection(keep >= 0 ? keep : 0);
    }

    /** Default picker ke perangkat WILAYAH pelanggan terpilih. Hanya bila picker masih di posisi 0
     *  (belum dipilih manual) → hormati pilihan manual. Diam bila tanpa config/koordinat, atau
     *  wilayah = perangkat ini (default "Perangkat ini" sudah benar). */
    private void selectWilayahDefaultDevice() {
        if (!assignDeviceEligible || spinnerAssignDevice == null) return;
        // STAF & MARKETING: default penugasan = DIRI SENDIRI — jangan auto-route ke perangkat
        // wilayah/override. Mereka memilih perangkat/staf lain secara SADAR (lewat picker +
        // konfirmasi), bukan otomatis. SPV tetap auto-route (peran dispatch/koordinasi tim).
        if (assignDefaultSelf) return;
        if (spinnerAssignDevice.getSelectedItemPosition() != 0) return;   // sudah dipilih manual
        SyncSettings cfg = new SyncSettings(settingsDao);
        // Override penugasan pelanggan (di-set web) MENGALAHKAN wilayah; kalau kosong, pakai wilayah
        // dari koordinat. Override berlaku walau pelanggan belum berkoordinat. Nilainya sudah di-cache
        // (MERGED) saat pelanggan dipilih — tak perlu query ulang di sini.
        String override = selectedAssignOverride;
        String dev;
        if (override != null && !override.trim().isEmpty()) {
            dev = override.trim();
        } else {
            if (selectedWLat == 0 && selectedWLng == 0) return;   // tanpa koordinat & tanpa override
            dev = Wilayah.deviceForCoord(cfg.getBranchCenter(), cfg.getWilayahZones(), selectedWLat, selectedWLng);
        }
        if (dev == null || dev.equals(cfg.getDeviceUuid())) return;
        int idx = assignUuids.indexOf(dev);
        if (idx > 0) spinnerAssignDevice.setSelection(idx);
    }

    /** uuid perangkat yang sedang dipilih di picker, atau null (= "Perangkat ini"). */
    private String selectedAssignUuid() {
        if (spinnerAssignDevice == null) return null;
        int pos = spinnerAssignDevice.getSelectedItemPosition();
        return pos > 0 && pos < assignUuids.size() ? assignUuids.get(pos) : null;
    }

    /** Segarkan roster perangkat cabang dari /api/me di background (best-effort), lalu isi ulang
     *  spinner — supaya perangkat cabang yang baru enroll ikut muncul tanpa menunggu heartbeat. */
    private void refreshAssignRosterAsync() {
        final SyncSettings cfg = new SyncSettings(settingsDao);
        if (!cfg.isEnabled()) return;
        new Thread(() -> {
            try {
                org.json.JSONObject r = new com.crowja.damiupos.sync.SyncApi(cfg).me();
                org.json.JSONArray devices = r.optJSONArray("devices");
                // Wilayah: cache pusat cabang + sektor agar default perangkat mengikuti wilayah pelanggan.
                org.json.JSONObject bc = r.optJSONObject("branch_center");
                cfg.setBranchCenter(bc != null ? bc.toString() : "");
                org.json.JSONArray qz = r.optJSONArray("wilayah_zones");
                cfg.setWilayahZones(qz != null ? qz.toString() : "");
                if (devices != null) {
                    cfg.setDeviceRoster(devices.toString());
                    runOnUiThread(() -> { populateAssignSpinner(); selectWilayahDefaultDevice(); });
                }
            } catch (Throwable ignored) {}
        }).start();
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

    /** Pengaman Umum: "Di Pinjam" tidak pernah lolos untuk Pelanggan Umum (galon keluar/masuk
     *  tidak dicatat) — dipetakan ke "Botol Sendiri". Pelanggan lain lewat apa adanya. */
    private String umumSafeOwnership(String ownership) {
        return isUmumCustomer() && Transaction.OWNERSHIP_PINJAM.equals(ownership)
                ? Transaction.OWNERSHIP_BAWA_SENDIRI : ownership;
    }

    /**
     * Sinkronkan opsi ownership & tipe transaksi dengan pelanggan terpilih.
     *
     * <p>Pelanggan UMUM: galon keluar/masuk TIDAK dicatat — "Di Pinjam" disembunyikan
     * (pindah paksa ke "Botol Sendiri" bila sedang terpilih) dan tipe "Galon Kembali"
     * disembunyikan (pindah paksa ke "Jual Air" bila sedang terpilih). Tersisa
     * "Di Beli"/"Botol Sendiri" + "Jual Botol" — penjualan murni tanpa pencatatan
     * galon beredar. Pelanggan terdaftar: semua opsi tampil.
     */
    private void updateOwnershipButtonsForCustomer() {
        View btnPinjam = findViewById(R.id.btnOwnershipPinjam);
        View btnBawaSendiri = findViewById(R.id.btnOwnershipBawaSendiri);
        View btnKembaliType = findViewById(R.id.btnTypeKembali);
        boolean umum = isUmumCustomer();
        if (umum) {
            if (isPinjamSelected()) toggleOwnership.check(R.id.btnOwnershipBawaSendiri);
            if (toggleType.getCheckedButtonId() == R.id.btnTypeKembali) {
                toggleType.check(R.id.btnTypeJual);
            }
        }
        btnPinjam.setVisibility(umum ? View.GONE : View.VISIBLE);
        btnBawaSendiri.setVisibility(View.VISIBLE);
        if (btnKembaliType != null) {
            btnKembaliType.setVisibility(umum ? View.GONE : View.VISIBLE);
        }
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
        // Mode ongkir berubah → total ongkir berubah, konfirmasi ketidakcocokan yang lama gugur.
        ongkirAcknowledged = false;
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

    // --- ⚡ Tandai Prioritas ---

    private void setupPrioritasCard() {
        cardPrioritas = findViewById(R.id.cardPrioritas);
        cbPrioritas = findViewById(R.id.cbPrioritas);
        layoutPriorityReason = findViewById(R.id.layoutPriorityReason);
        etPriorityReason = findViewById(R.id.etPriorityReason);
        if (cbPrioritas == null) return;
        cbPrioritas.setOnCheckedChangeListener((b, checked) ->
                layoutPriorityReason.setVisibility(checked ? View.VISIBLE : View.GONE));
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
        // Kartu "Potong Saldo Refund": SEMUA pelanggan (mode JUAL) yang punya saldo refund.
        // Auto-tercentang — refund memang dimaksudkan langsung memotong pembelian berikutnya;
        // operator tetap bisa melepasnya bila pelanggan minta saldonya disimpan dulu.
        refundSaldo = 0;
        if (cardUseRefund != null) {
            if (!payoutMode && isJualSelected() && selectedCustomerId > 0) {
                refundSaldo = new com.crowja.damiupos.db.CustomerRefundDao(DatabaseHelper.getInstance(this))
                        .balanceFor(selectedCustomerId);
            }
            if (refundSaldo > 0) {
                NumberFormat nfr = NumberFormat.getInstance(new Locale("id", "ID"));
                tvSaldoRefund.setText("Saldo refund tersedia: Rp " + nfr.format(Math.round(refundSaldo))
                        + " — otomatis dipotong dari tagihan ini.");
                cardUseRefund.setVisibility(View.VISIBLE);
                if (cbUseRefund != null && !cbUseRefund.isChecked()) cbUseRefund.setChecked(true);
            } else {
                if (cbUseRefund != null) cbUseRefund.setChecked(false);
                if (etRefundDipotong != null) etRefundDipotong.setText("");
                cardUseRefund.setVisibility(View.GONE);
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

    /** Kartu "Potong Saldo Refund" — cermin kartu saldo komisi, tapi berlaku untuk SEMUA
     *  pelanggan dan tercentang otomatis saat pelanggan bersaldo dipilih. */
    private void setupUseRefundCard() {
        cardUseRefund = findViewById(R.id.cardUseRefund);
        cbUseRefund = findViewById(R.id.cbUseRefund);
        tvSaldoRefund = findViewById(R.id.tvSaldoRefund);
        layoutRefundPotong = findViewById(R.id.layoutRefundPotong);
        etRefundDipotong = findViewById(R.id.etRefundDipotong);
        layoutRefundBreakdown = findViewById(R.id.layoutRefundBreakdown);
        tvDipotongRefund = findViewById(R.id.tvDipotongRefund);
        if (cbUseRefund == null) return;

        cbUseRefund.setOnCheckedChangeListener((b, checked) -> {
            layoutRefundPotong.setVisibility(checked ? View.VISIBLE : View.GONE);
            if (checked && etRefundDipotong.length() == 0) {
                long prefill = Math.max(0, (long) Math.round(Math.min(lastJualTotal, refundSaldo)));
                etRefundDipotong.setText(String.valueOf(prefill));
            }
            updateSisaBayar();
        });
        etRefundDipotong.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { updateSisaBayar(); }
        });
    }

    /** Rupiah yang benar-benar dipotong dari saldo REFUND — SISA total sesudah saldo komisi,
     *  supaya dua potongan tak pernah menjumlah melebihi tagihan. */
    private double refundDipotong() {
        if (cbUseRefund == null || !cbUseRefund.isChecked()) return 0;
        double v = parseDoubleOr(etRefundDipotong, 0);
        if (v < 0) v = 0;
        if (v > refundSaldo) v = refundSaldo;
        double sisa = Math.max(0, lastJualTotal - saldoDipotong());
        if (v > sisa) v = sisa;
        return v;
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
        // Rincian potongan (saldo komisi &/atau saldo refund) ditampilkan di DALAM kartu Total.
        boolean pakaiSaldo = cbUseSaldo != null && cbUseSaldo.isChecked();
        double dipotong = pakaiSaldo ? saldoDipotong() : 0;
        double dipotongRefund = refundDipotong();
        double sisa = Math.max(0, lastJualTotal - dipotong - dipotongRefund);
        NumberFormat nf = NumberFormat.getInstance(new Locale("id", "ID"));
        if (tvDipotongSaldo != null) tvDipotongSaldo.setText("− Rp " + nf.format(Math.round(dipotong)));
        if (tvDipotongRefund != null) tvDipotongRefund.setText("− Rp " + nf.format(Math.round(dipotongRefund)));
        if (tvSisaBayar != null) tvSisaBayar.setText("Rp " + nf.format(Math.round(sisa)));
        if (layoutRefundBreakdown != null) {
            layoutRefundBreakdown.setVisibility(dipotongRefund > 0 ? View.VISIBLE : View.GONE);
        }
        layoutSaldoBreakdown.setVisibility((pakaiSaldo || dipotongRefund > 0) ? View.VISIBLE : View.GONE);
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
            MaterialCardView row = (MaterialCardView) LayoutInflater.from(this)
                    .inflate(R.layout.item_product_entry, llItems, false);
            TextView tvDot = row.findViewById(R.id.tvItemDot);
            TextView tvName = row.findViewById(R.id.tvItemName);
            TextView tvLastBought = row.findViewById(R.id.tvItemLastBought);
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
            productEntries.add(new ProductEntry(p, etQty, etPrice, row, tvLastBought));
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
            // Default = min(galon jual, galon DIPINJAM pelanggan) — tak pernah default melebihi stok
            // dipinjam. Umum/promosi/walk-in → 0. Tetap editable (userEditedKembali).
            int def = (isUmumCustomer() || promosiMode || selectedCustomerId <= 0)
                    ? 0
                    : Math.min(getTotalJumlah(), Math.max(0, selectedHeld));
            etJumlahKembali.setText(String.valueOf(def));
            syncingKembali = false;
        }
    }

    private boolean isUmumCustomer() {
        return CustomerDao.UMUM_NAME.equals(selectedCustomerName);
    }

    /** Kedipkan view (peringatan tegas) beberapa kali via alpha animation. */
    private void blinkView(android.view.View v) {
        if (v == null) return;
        android.view.animation.AlphaAnimation a = new android.view.animation.AlphaAnimation(1f, 0.15f);
        a.setDuration(280);
        a.setRepeatCount(5);
        a.setRepeatMode(android.view.animation.Animation.REVERSE);
        v.startAnimation(a);
    }

    /** Kedipkan view TERUS-MENERUS (badge "Terakhir dibeli" / "Nx dikirim") — beda dari
     *  {@link #blinkView} yang berhenti sendiri, dipakai untuk sorotan yang harus tetap
     *  menyala selama badge-nya tampil. Panggil {@code v.clearAnimation()} sebelum GONE-kan
     *  view supaya animasinya tak tetap "berjalan" di latar begitu view disembunyikan lalu
     *  dipakai ulang untuk pelanggan lain. */
    private void blinkViewForever(android.view.View v) {
        if (v == null) return;
        android.view.animation.AlphaAnimation a = new android.view.animation.AlphaAnimation(1f, 0.3f);
        a.setDuration(500);
        a.setRepeatCount(android.view.animation.Animation.INFINITE);
        a.setRepeatMode(android.view.animation.Animation.REVERSE);
        v.startAnimation(a);
    }

    /**
     * Kedipkan BORDER kartu baris "↩ Terakhir dibeli" — kuning, TERUS-MENERUS, selama badge-nya
     * tampil. Beda dari {@link #blinkViewForever}: itu meng-alpha-fade SELURUH isi view (cocok utk
     * badge kecil), tapi dipakai untuk seluruh baris item akan membuat teks & input harga/jumlah
     * ikut pudar berulang — mengganggu saat staf coba mengetik. Hanya BORDER-nya yang berkedip
     * (isi baris tetap 100% terbaca), cermin pola kedip ⚡ prioritas kartu antrian delivery.
     */
    private void startLastBoughtBlink(MaterialCardView card) {
        stopLastBoughtBlink(card);
        android.animation.ValueAnimator anim = android.animation.ValueAnimator.ofArgb(
                Color.TRANSPARENT, Color.parseColor("#FBC02D"));
        anim.setDuration(600);
        anim.setRepeatMode(android.animation.ValueAnimator.REVERSE);
        anim.setRepeatCount(android.animation.ValueAnimator.INFINITE);
        anim.addUpdateListener(a -> card.setStrokeColor((int) a.getAnimatedValue()));
        anim.start();
        // Animator disimpan sbg tag (kunci = id child tetap yang dipunyai kartu ini) supaya bisa
        // dibatalkan saat pelanggan ganti (productEntries dibangun ulang, tapi baris lama bisa saja
        // masih berkedip di latar bila tak dihentikan lebih dulu — animator TAK berhenti sendiri
        // seperti View.clearAnimation() pada AlphaAnimation).
        card.setTag(R.id.tvItemLastBought, anim);
    }

    private void stopLastBoughtBlink(MaterialCardView card) {
        Object tag = card.getTag(R.id.tvItemLastBought);
        if (tag instanceof android.animation.ValueAnimator) {
            ((android.animation.ValueAnimator) tag).cancel();
            card.setTag(R.id.tvItemLastBought, null);
        }
        card.setStrokeColor(Color.TRANSPARENT);
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
            dialog.dismiss();
            // Baris "Umum" bisa juga muncul di daftar/hasil cari → tetap lewati gerbang konfirmasi.
            if (CustomerDao.UMUM_NAME.equals(customer.getName())) {
                confirmUmumSelection(customer);
            } else {
                applySelectedCustomer(customer);
            }
        });
        rvCustomers.setLayoutManager(new LinearLayoutManager(this));
        rvCustomers.setAdapter(adapter);
        // Kartu pemilih SAMA dengan daftar Pelanggan: dedup lintas-perangkat + agregat gabungan
        // (total galon / transaksi / konsumsi) + tag foto/koordinat & perangkat asal.
        adapter.setData(CustomerDao.dedupeForDisplay(customerDao.getAll()));

        btnPickUmum.setOnClickListener(v -> {
            dialog.dismiss();
            Customer umum = customerDao.getOrCreateUmum();
            if (umum != null) confirmUmumSelection(umum);
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
     * Gerbang TEGAS sebelum "Pelanggan Umum" benar-benar terpilih: staf harus MEMASTIKAN
     * konsumen datang sendiri ke kios DAN memakai galon miliknya sendiri — karena untuk
     * Pelanggan Umum galon keluar/masuk TIDAK dicatat (opsi "Di Pinjam" & Galon Kembali
     * dinonaktifkan). Batal → kembali ke pemilih pelanggan.
     */
    private void confirmUmumSelection(Customer umum) {
        playIncompleteAlertSound();   // penekanan: alarm nyaring, sama dengan warning data pelanggan
        new AlertDialog.Builder(this)
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setTitle("⚠️ PELANGGAN UMUM — PASTIKAN!")
                .setCancelable(false)
                .setMessage("Sebelum lanjut, PASTIKAN dua hal ini:\n\n"
                        + "1️⃣  Konsumen DATANG SENDIRI ke kios/depot (bukan pesan-antar).\n\n"
                        + "2️⃣  Konsumen memakai GALON MILIKNYA SENDIRI.\n\n"
                        + "Untuk Pelanggan Umum, galon keluar/masuk TIDAK dicatat — opsi "
                        + "\"Di Pinjam\" dan Galon Kembali dinonaktifkan.\n\n"
                        + "Jika konsumen meminjam botol depot atau minta diantar, daftarkan "
                        + "sebagai pelanggan lewat \"Pelanggan Baru\".")
                .setPositiveButton("YA, SAYA PASTIKAN", (d, w) -> applySelectedCustomer(umum))
                .setNegativeButton("BATAL", (d, w) -> showCustomerPicker())
                .show();
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
        // Pelanggan Umum → ownership default = Botol Sendiri (konsumen memakai galon
        // miliknya sendiri; galon keluar/masuk tidak dicatat untuk Umum)
        if (isUmumCustomer()) {
            toggleOwnership.check(R.id.btnOwnershipBawaSendiri);
        }
        syncKembaliToTotalJumlah();
        updateOwnershipButtonsForCustomer();
        updateOwnershipUI();
        refreshUseSaldoCard();   // pelanggan baru terpilih → cek apakah reseller
        // Ganti pelanggan → vonis server milik pelanggan LAMA tak berlaku lagi. Wajib dibuang di
        // sini, kalau tidak gerbang di bawah (dan gerbang sebelum simpan) memakai jawaban basi.
        serverGuard = null;
        serverGuardCustomerId = -1;
        lastOpenQueueWarnOrderId = -1;
        lastDuplicateWarnCustomerId = -1;
        maybeWarnIncompleteCustomer(c);
        maybeWarnPendingGift(c);
        maybeWarnDuplicateOrderToday(c);
        highlightLastPurchasedItems(c);
        fetchOrderInsightsAsync(c);
    }

    /**
     * Kedipkan BORDER kuning baris "Item Penjualan" (terus-menerus, selama tampil) untuk produk
     * yang ada di pembelian (JUAL) TERAKHIR pelanggan ini + tempel badge "↩ Terakhir dibeli" di
     * samping nama produknya — cermin fitur web (lihat [[project_damiupos_last_item_blink]]).
     * Sumber LOKAL (instan, aman offline); {@link #fetchOrderInsightsAsync} menimpanya dengan data
     * SE-CABANG begitu server membalas — lihat doc-nya untuk alasannya (sync per-perangkat terisolasi).
     */
    private void highlightLastPurchasedItems(Customer c) {
        if (c == null || c.getId() <= 0) {
            applyLastPurchasedNames(java.util.Collections.emptyList());
            return;
        }
        applyLastPurchasedNames(transactionDao.getLastJualProductNames(c.getId()));
    }

    /**
     * Terapkan daftar nama produk "terakhir dibeli" ke baris Item Penjualan — dipanggil dari sumber
     * LOKAL ({@link #highlightLastPurchasedItems}) maupun balasan SERVER ({@link #fetchOrderInsightsAsync}),
     * jadi idempoten & aman dipanggil ulang (reset dulu tiap kali). Dicocokkan lewat NAMA produk
     * (trim + case-insensitive), bukan pid: pid lokal-perangkat, dan produk bisa direname sejak
     * transaksi lama disimpan.
     */
    private void applyLastPurchasedNames(java.util.List<String> lastNames) {
        for (ProductEntry pe : productEntries) {
            if (pe.tvLastBought != null) {
                pe.tvLastBought.clearAnimation();
                pe.tvLastBought.setVisibility(View.GONE);
            }
            stopLastBoughtBlink(pe.row);
        }
        if (lastNames.isEmpty()) return;
        java.util.Set<String> normalized = new java.util.HashSet<>();
        for (String n : lastNames) normalized.add(n.trim().toLowerCase(java.util.Locale.ROOT));
        for (ProductEntry pe : productEntries) {
            String pname = pe.product.getName();
            if (pname == null || !normalized.contains(pname.trim().toLowerCase(java.util.Locale.ROOT))) continue;
            startLastBoughtBlink(pe.row);   // border kartu berkedip kuning TERUS-MENERUS
            if (pe.tvLastBought != null) {
                pe.tvLastBought.setVisibility(View.VISIBLE);
                blinkViewForever(pe.tvLastBought);   // badge-nya sendiri tetap berkedip selama tampil
            }
        }
    }

    /**
     * Segarkan "↩ Terakhir dibeli" + badge "Kirim ke" dari SERVER (SE-CABANG) begitu balasannya
     * tiba — query lokal ({@link #highlightLastPurchasedItems}, {@link #applyCustomerLocations})
     * sering KOSONG untuk pelanggan yang biasa order lewat HP staf LAIN: sync transaksi per-perangkat
     * SENGAJA terisolasi (SyncEngine — HP hanya menyimpan transaksi buatannya sendiri + dari web),
     * beda dari fitur web yang membaca langsung dari DB server. Lokal dipakai lebih dulu sebagai
     * tampilan instan (aman offline); balasan ini MENIMPA — tapi HANYA bila pelanggan terpilih belum
     * berganti sejak permintaan ini dikirim (jaga balapan ganti-pelanggan cepat).
     */
    private void fetchOrderInsightsAsync(Customer c) {
        if (c == null || c.getId() <= 0) return;
        com.crowja.damiupos.sync.SyncSettings cfg = new com.crowja.damiupos.sync.SyncSettings(settingsDao);
        if (!cfg.isEnrolled()) return;
        final long targetCustomerId = c.getId();
        final String uuid = customerDao.getSyncUuidById(targetCustomerId);
        if (uuid == null || uuid.isEmpty()) return;
        new Thread(() -> {
            org.json.JSONObject res;
            try {
                res = new com.crowja.damiupos.sync.SyncApi(cfg).orderInsights(uuid);
            } catch (Exception ignored) {
                return;   // best-effort — biarkan hasil lokal (bila ada) tetap tampil
            }
            final java.util.List<String> lastItems = new java.util.ArrayList<>();
            org.json.JSONArray arr = res.optJSONArray("last_jual_items");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    String n = arr.optString(i, null);
                    if (n != null && !n.isEmpty()) lastItems.add(n);
                }
            }
            final java.util.Map<String, Integer> counts = new java.util.HashMap<>();
            org.json.JSONObject dc = res.optJSONObject("delivery_counts");
            if (dc != null) {
                java.util.Iterator<String> keys = dc.keys();
                while (keys.hasNext()) {
                    String k = keys.next();
                    counts.put(k, dc.optInt(k, 0));
                }
            }
            // isNull() dulu, BUKAN optString(key, null) langsung: org.json bawaan Android mengubah
            // nilai JSON null jadi string literal "null" lewat optString, bukan Java null — customer
            // tanpa order terakhir (last_order_line: null di server) akan salah menampilkan teks "null".
            final String lastOrderLine = res.isNull("last_order_line") ? null : res.optString("last_order_line");

            // Vonis anti-order-ganda se-cabang (lihat OrderGuardVerdict). Kunci ini tak ada pada
            // server lama → verdict tetap null dan HP jatuh ke cek lokal seperti sebelumnya.
            final OrderGuardVerdict verdict = new OrderGuardVerdict();
            org.json.JSONObject ot = res.optJSONObject("ordered_today");
            if (ot != null) {
                verdict.todayStatus = ot.isNull("delivery_status") ? null : ot.optString("delivery_status");
                verdict.todayTanggal = ot.isNull("tanggal") ? null : ot.optString("tanggal");
                verdict.todayResumesToday = ot.optBoolean("resumes_today", false);
            }
            org.json.JSONObject oo = res.optJSONObject("open_order");
            if (oo != null) {
                verdict.openOrderId = oo.optLong("id", -1);
                verdict.openStatus = oo.isNull("delivery_status") ? null : oo.optString("delivery_status");
                verdict.openResumeAt = oo.isNull("resume_at") ? null : oo.optString("resume_at");
                verdict.openResumesToday = oo.optBoolean("resumes_today", false);
            }

            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (selectedCustomerId != targetCustomerId) return;   // pelanggan sudah berganti
                serverGuard = verdict;
                serverGuardCustomerId = targetCustomerId;
                // Server melihat lebih banyak daripada DB lokal → jalankan ulang kedua gerbang
                // dengan data yang sudah lengkap (cek lokal saat memilih tadi bisa saja bersih).
                maybeWarnOpenQueueOrder();
                // Cek lokal saat memilih tadi bisa saja bersih (order dibuat perangkat lain) —
                // jalankan ulang dengan vonis se-cabang. Anti-spam menjaga agar tak dobel.
                Customer again = customerDao.getById(targetCustomerId);
                if (again != null) maybeWarnDuplicateOrderToday(again);
                applyLastPurchasedNames(lastItems);
                selectedLocationCounts = counts;
                updateKirimKeCard();
                if (tvLastOrderLine != null) {
                    if (lastOrderLine != null && !lastOrderLine.isEmpty()) {
                        tvLastOrderLine.setText(lastOrderLine);
                        tvLastOrderLine.setVisibility(View.VISIBLE);
                    } else {
                        tvLastOrderLine.setVisibility(View.GONE);
                    }
                }
            });
        }).start();
    }

    /**
     * Muat multi-lokasi pelanggan terpilih: dest default = lokasi UTAMA (entri pertama),
     * {@code selectedWajibOngkir} mengikuti flag lokasi TERPILIH — fallback flag pelanggan
     * legacy bila tanpa lokasi. Dipanggil dari {@code applySelectedCustomer} DAN jalur
     * preset {@code customer_id}; pergantian lokasi lewat picker "Kirim ke" me-re-derive
     * flag lagi ({@link #showKirimKePicker}).
     */
    private void applyCustomerLocations(Customer c) {
        selectedHeld = c != null ? Math.max(0, c.getSaldoGalon()) : 0;   // galon dipinjam pelanggan
        returnOverWarnCount = 0;                                          // ganti pelanggan → reset gerbang
        selectedLocations = (c != null && c.getLocations() != null)
                ? new ArrayList<>(c.getLocations()) : new ArrayList<>();
        // Instan (lokal, aman offline) — fetchOrderInsightsAsync (dipanggil dari applySelectedCustomer)
        // menimpanya dengan hitungan SE-CABANG begitu server membalas.
        selectedLocationCounts = c != null && c.getId() > 0
                ? transactionDao.countJualByDeliveryDestName(c.getId()) : new java.util.HashMap<>();
        // Tanpa sumber lokal (server-only) — sembunyikan dulu, biar tak menampilkan info pelanggan
        // SEBELUMNYA sesaat sebelum fetchOrderInsightsAsync membalas untuk pelanggan yang baru ini.
        if (tvLastOrderLine != null) tvLastOrderLine.setVisibility(View.GONE);
        selectedDestIndex = 0;
        // Foto rumah pelanggan = fallback preview lokasi UTAMA (cermin locationsOrDefault web).
        custPhotoPath = c != null ? c.getPhotoPath() : null;
        custPhotoUrl = c != null ? c.getPhotoUrl() : null;
        Customer.Location primary = selectedLocations.isEmpty() ? null : selectedLocations.get(0);
        if (primary != null) {
            selectedDestName = primary.name;
            selectedDestLat = primary.lat;
            selectedDestLng = primary.lng;
            selectedWajibOngkir = primary.wajibOngkir;
            ongkirAcknowledged = false;   // pelanggan/lokasi lain → status berbeda, tanya lagi
        } else {
            selectedDestName = null;
            selectedDestLat = 0;
            selectedDestLng = 0;
            selectedWajibOngkir = c != null && c.isWajibOngkir();
            ongkirAcknowledged = false;
        }
        updateKirimKeCard();
        // Koordinat efektif untuk wilayah: lokasi tujuan terpilih bila ada, else koordinat dasar
        // pelanggan → default perangkat penugasan mengikuti wilayah pelanggan.
        selectedWLat = (selectedDestLat != 0 || selectedDestLng != 0) ? selectedDestLat : (c != null ? c.getLatitude() : 0);
        selectedWLng = (selectedDestLat != 0 || selectedDestLng != 0) ? selectedDestLng : (c != null ? c.getLongitude() : 0);
        // Cache override penugasan sekali di sini — pakai varian MERGED supaya override yang tersimpan
        // di salinan lintas-perangkat orang yang sama ikut terbaca (bukan hanya salinan wakil).
        Customer mergedSel = selectedCustomerId > 0 ? customerDao.getByIdMerged(selectedCustomerId) : null;
        selectedAssignOverride = mergedSel != null ? mergedSel.getAssignedDeviceUuid() : null;
        selectWilayahDefaultDevice();
        maybeShowMultiLocationInfo(c);
    }

    /**
     * Berapa kali orderan JUAL sudah dikirim ke lokasi {@code idx} dalam {@link #selectedLocations}
     * (dari {@link #selectedLocationCounts}, diisi {@code applyCustomerLocations}). Lokasi UTAMA
     * (indeks 0) mewarisi transaksi lama tanpa nama tujuan tersimpan — sebelum multi-lokasi ada,
     * semua pengiriman memang ke sana.
     */
    private int deliveryCountFor(int idx) {
        if (idx < 0 || idx >= selectedLocations.size()) return 0;
        String name = selectedLocations.get(idx).name;
        String key = name != null ? name : "";
        Integer named = selectedLocationCounts.get(key);
        int n = named != null ? named : 0;
        if (idx == 0 && !key.isEmpty()) {
            Integer unnamed = selectedLocationCounts.get(com.crowja.damiupos.db.TransactionDao.UNNAMED_DEST_KEY);
            if (unnamed != null) n += unnamed;
        }
        return n;
    }

    /** Kartu "Kirim ke" tampil HANYA saat JUAL & pelanggan punya >1 lokasi
     *  (disembunyikan di mode payout/promosi). Label = nama lokasi terpilih. */
    private void updateKirimKeCard() {
        if (cardKirimKe == null) return;
        // Tampil untuk JUAL bila pelanggan punya >=1 lokasi berkoordinat. Single-lokasi = preview
        // saja (tak ada yang bisa dipilih); multi = preview + picker "Ganti". Disembunyikan di
        // mode payout/promosi.
        boolean multi = selectedLocations.size() > 1;
        boolean show = !payoutMode && !promosiMode
                && isJualSelected() && selectedLocations.size() >= 1;
        cardKirimKe.setVisibility(show ? View.VISIBLE : View.GONE);
        if (tvSelectedKirimKe != null) {
            tvSelectedKirimKe.setText(selectedDestName != null && !selectedDestName.isEmpty()
                    ? selectedDestName : "—");
        }
        if (tvKirimKeCount != null) {
            int cnt = deliveryCountFor(selectedDestIndex);
            tvKirimKeCount.clearAnimation();
            if (cnt > 0) {
                tvKirimKeCount.setText(cnt + "×");
                tvKirimKeCount.setVisibility(View.VISIBLE);
                blinkViewForever(tvKirimKeCount);
            } else {
                tvKirimKeCount.setVisibility(View.GONE);
            }
        }
        if (!show) return;
        // Single = "Lokasi Pengiriman" (preview murni, klik mati); multi = "Kirim ke" + "Ganti ›".
        if (tvKirimKeLabel != null) tvKirimKeLabel.setText(multi ? "Kirim ke" : "Lokasi Pengiriman");
        if (tvKirimKeGanti != null) tvKirimKeGanti.setVisibility(multi ? View.VISIBLE : View.GONE);
        cardKirimKe.setClickable(multi);
        // Preview lokasi tujuan terpilih: koordinat + foto (foto lokasi sendiri, atau foto rumah
        // pelanggan untuk lokasi utama). Tap thumbnail → foto layar penuh.
        Customer.Location sel = (selectedDestIndex >= 0 && selectedDestIndex < selectedLocations.size())
                ? selectedLocations.get(selectedDestIndex) : null;
        if (tvKirimKeCoord != null) {
            boolean hasCoord = selectedDestLat != 0 || selectedDestLng != 0;
            tvKirimKeCoord.setText(hasCoord
                    ? String.format(java.util.Locale.US, "%.5f, %.5f", selectedDestLat, selectedDestLng)
                    : "Koordinat belum diisi");
        }
        if (ivKirimKeThumb != null) {
            loadLocationThumb(ivKirimKeThumb, locationPhotoSource(sel, selectedDestIndex == 0), true);
        }
    }

    /** Info SEKALI per pelanggan (per sesi layar): pelanggan multi-lokasi → ingatkan staf
     *  memastikan lokasi pengiriman lewat kartu "Kirim ke". */
    private void maybeShowMultiLocationInfo(Customer c) {
        if (c == null || payoutMode || promosiMode) return;
        if (selectedLocations.size() <= 1 || !isJualSelected()) return;
        if (c.getId() == lastLocInfoCustomerId) return;
        lastLocInfoCustomerId = c.getId();
        // Nama lokasi UTAMA (default) yang sungguh dipakai — selectedDestName sudah diisi dari
        // selectedLocations.get(0).name oleh applyCustomerLocations() sebelum method ini dipanggil.
        String defaultLocName = selectedDestName != null && !selectedDestName.isEmpty()
                ? selectedDestName : "lokasi utama";
        new AlertDialog.Builder(this)
                .setTitle("📍 Pelanggan Multi-Lokasi")
                .setMessage("Pelanggan \"" + c.getName() + "\" punya " + selectedLocations.size()
                        + " lokasi. Pastikan lokasi pengiriman pada kartu \"Kirim ke\" sudah "
                        + "benar (default: " + defaultLocName + ").")
                .setPositiveButton("OK", null)
                .show();
    }

    /** Picker lokasi tujuan pengiriman. Tiap pergantian me-RE-DERIVE selectedWajibOngkir
     *  dari lokasi terpilih + re-apply default Ongkos Kirim Per Galon bila wajib. */
    private void showKirimKePicker() {
        if (selectedLocations.size() <= 1) return;
        // Picker kaya: tiap baris menampilkan thumbnail foto + nama + koordinat + badge Wajib Ongkir,
        // jadi staf bisa MENGINTIP foto & lokasi sambil memilih (bukan cuma daftar nama).
        final android.widget.ListView lv = new android.widget.ListView(this);
        lv.setAdapter(new KirimKeAdapter());
        lv.setDivider(null);
        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("Kirim ke")
                .setView(lv)
                .setNegativeButton("Batal", null)
                .create();
        lv.setOnItemClickListener((parent, view, pos, id) -> {
            selectDestLocation(pos);
            dlg.dismiss();
        });
        dlg.show();
    }

    /** Terapkan lokasi tujuan terpilih (indeks di selectedLocations) + re-derive flag Wajib Ongkir
     *  dari lokasi terpilih (guard "Tanpa" ongkir ikut lokasi baru) + refresh kartu preview. */
    private void selectDestLocation(int idx) {
        if (idx < 0 || idx >= selectedLocations.size()) return;
        Customer.Location l = selectedLocations.get(idx);
        selectedDestIndex = idx;
        selectedDestName = l.name;
        selectedDestLat = l.lat;
        selectedDestLng = l.lng;
        selectedWajibOngkir = l.wajibOngkir;
        ongkirAcknowledged = false;   // ganti lokasi tujuan → status wajib ongkirnya ikut ganti
        if (isJualSelected() && l.wajibOngkir) {
            toggleOngkirMode.check(R.id.btnOngkirPerGalon);
        }
        updateKirimKeCard();
    }

    /** Sumber foto preview untuk sebuah lokasi: foto lokasi sendiri bila ada; untuk lokasi UTAMA
     *  yang belum punya foto, jatuh ke foto rumah pelanggan (path lokal → URL server). null = tak ada. */
    private String locationPhotoSource(Customer.Location l, boolean isPrimary) {
        if (l != null && l.photo != null && !l.photo.trim().isEmpty()) return l.photo.trim();
        if (isPrimary) {
            // Foto rumah: pakai file lokal HANYA bila benar-benar ada; kalau hilang (storage dipangkas
            // / DB di-import dari perangkat lain, photo_url terisi tapi file lokal tak ada) jatuh ke URL
            // server — cermin CustomerDetailActivity.loadPhoto, supaya thumbnail tidak jadi placeholder abu.
            if (custPhotoPath != null && !custPhotoPath.isEmpty()
                    && new java.io.File(custPhotoPath).exists()) {
                return custPhotoPath;
            }
            if (custPhotoUrl != null && !custPhotoUrl.isEmpty()) return custPhotoUrl;
        }
        return null;
    }

    /** Muat thumbnail lokasi ke ImageView. File lokal langsung; URL http diunduh ke cache di
     *  background. Tag view = sumber, jadi set async diabaikan bila view sudah dipakai ulang untuk
     *  sumber lain (aman untuk daur-ulang baris ListView). allowZoom → tap membuka foto layar penuh. */
    private void loadLocationThumb(final android.widget.ImageView iv, final String source,
            final boolean allowZoom) {
        if (iv == null) return;
        iv.setTag(source);
        iv.setImageResource(android.R.drawable.ic_menu_gallery);
        iv.setOnClickListener(null);
        if (source == null || source.isEmpty()) return;
        final boolean isUrl = source.startsWith("http://") || source.startsWith("https://");
        if (!isUrl) {
            java.io.File f = new java.io.File(source);
            if (f.exists()) {
                try {
                    iv.setImageBitmap(com.crowja.damiupos.util.BitmapUtils
                            .decodeSampled(source, 256, 256));
                    if (allowZoom) iv.setOnClickListener(v -> showFullScreenLocationPhoto(source));
                } catch (Exception ignored) { }
            }
            return;
        }
        final String name = "loc_" + Integer.toHexString(source.hashCode()) + ".jpg";
        new Thread(() -> {
            final java.io.File f = com.crowja.damiupos.util.BitmapUtils.downloadToCache(
                    getApplicationContext(), source, name);
            final android.graphics.Bitmap b = f != null
                    ? com.crowja.damiupos.util.BitmapUtils.decodeSampled(f.getAbsolutePath(), 256, 256)
                    : null;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || b == null) return;
                if (!source.equals(iv.getTag())) return;   // view dipakai ulang → abaikan
                iv.setImageBitmap(b);
                if (allowZoom) iv.setOnClickListener(v -> showFullScreenLocationPhoto(f.getAbsolutePath()));
            });
        }).start();
    }

    /** Foto lokasi layar penuh (dialog hitam, tap untuk tutup) — mirror CustomerDetailActivity. */
    private void showFullScreenLocationPhoto(String path) {
        android.graphics.Bitmap bmp = com.crowja.damiupos.util.BitmapUtils.decodeForScreen(this, path);
        if (bmp == null) {
            Toast.makeText(this, "Foto tidak dapat dimuat", Toast.LENGTH_SHORT).show();
            return;
        }
        android.widget.ImageView iv = new android.widget.ImageView(this);
        iv.setImageBitmap(bmp);
        iv.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        iv.setBackgroundColor(android.graphics.Color.BLACK);
        iv.setAdjustViewBounds(true);
        final android.app.Dialog dialog = new android.app.Dialog(this,
                android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        dialog.setContentView(iv, new android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT));
        iv.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }

    /** Adapter picker "Kirim ke": baris = thumbnail foto + nama (+ "utama") + koordinat + badge. */
    private class KirimKeAdapter extends android.widget.BaseAdapter {
        @Override public int getCount() { return selectedLocations.size(); }

        @Override public Object getItem(int i) { return selectedLocations.get(i); }

        @Override public long getItemId(int i) { return i; }

        @Override public View getView(int i, View convertView, android.view.ViewGroup parent) {
            View row = convertView != null ? convertView
                    : getLayoutInflater().inflate(R.layout.item_kirim_ke_location, parent, false);
            Customer.Location l = selectedLocations.get(i);
            android.widget.ImageView thumb = row.findViewById(R.id.ivLocThumb);
            TextView nm = row.findViewById(R.id.tvLocName);
            TextView coord = row.findViewById(R.id.tvLocCoord);
            TextView badge = row.findViewById(R.id.tvLocBadge);
            TextView count = row.findViewById(R.id.tvLocCount);
            String name = (l.name == null || l.name.isEmpty()) ? Customer.DEFAULT_LOCATION_NAME : l.name;
            nm.setText(name + (i == 0 ? " (utama)" : ""));
            boolean hasCoord = l.lat != 0 || l.lng != 0;
            coord.setText(hasCoord
                    ? String.format(java.util.Locale.US, "%.5f, %.5f", l.lat, l.lng)
                    : "Koordinat belum diisi");
            badge.setVisibility(l.wajibOngkir ? View.VISIBLE : View.GONE);
            int cnt = deliveryCountFor(i);
            if (count != null) {
                count.clearAnimation();
                if (cnt > 0) {
                    count.setText(cnt + "× dikirim");
                    count.setVisibility(View.VISIBLE);
                    blinkViewForever(count);
                } else {
                    count.setVisibility(View.GONE);
                }
            }
            loadLocationThumb(thumb, locationPhotoSource(l, i == 0), false);
            return row;
        }
    }

    /** ID pelanggan terakhir yang sudah diperingatkan — supaya popup tidak spam
     *  saat pelanggan yang sama dipilih ulang dalam satu sesi transaksi. */
    private long lastIncompleteWarnCustomerId = -1;

    /** ID pelanggan terakhir yang gift-nya sudah diberitahukan (anti-spam popup gift). */
    private long lastGiftWarnCustomerId = -1;

    /** Gerbang konfirmasi 2x-ketuk "sudah order hari ini": ID pelanggan yang sedang
     *  "diarmed" (ketukan pertama sudah terjadi) + waktunya, supaya ketukan kedua
     *  harus datang cukup cepat setelah yang pertama (bukan tak sengaja lama kemudian). */
    private long pendingDuplicateOrderConfirmCustomerId = -1;
    private long pendingDuplicateOrderConfirmArmedAt = 0;
    private static final long DUPLICATE_ORDER_CONFIRM_WINDOW_MS = 5000;

    /**
     * Vonis anti-order-ganda SE-CABANG dari server (order-insights). WAJIB dari server: tabel
     * transaksi di HP terisolasi per-perangkat, jadi order yang dibuat kurir lain atau lewat
     * dashboard TAK PERNAH punya baris lokal di sini — justru kasus yang paling ingin dicegah.
     * null = belum/ tak ada jawaban (offline) → hanya cek lokal yang berlaku.
     */
    private OrderGuardVerdict serverGuard = null;
    /** Pelanggan yang {@link #serverGuard} ini miliki — jaga dari balasan basi saat ganti pelanggan. */
    private long serverGuardCustomerId = -1;
    /** Order antrian yang popup-nya sudah ditampilkan (anti-spam saat pelanggan dipilih ulang). */
    private long lastOpenQueueWarnOrderId = -1;
    /** Pelanggan yang popup "sudah order hari ini"-nya sudah tampil. Gerbang ini dijalankan DUA kali
     *  (cek lokal saat memilih, lalu ulang saat vonis server tiba) — tanpa ini popupnya dobel. */
    private long lastDuplicateWarnCustomerId = -1;

    /** Vonis anti-order-ganda untuk satu pelanggan (lihat App\Support\DuplicateOrderGuard di web). */
    private static final class OrderGuardVerdict {
        String todayStatus;        // DONE/PENDING/TERTUNDA/DIRECT, null = belum order hari ini
        String todayTanggal;       // "Y-m-d H:i:s"
        boolean todayResumesToday;
        long openOrderId = -1;     // -1 = tak ada order yang belum selesai
        String openStatus;
        String openResumeAt;
        boolean openResumesToday;
    }

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

    /**
     * Gerbang anti-order-ganda: kalau pelanggan ini SUDAH punya transaksi JUAL hari ini
     * (baik yang sudah SELESAI maupun yang masih tertunda/dalam antrean delivery), staf
     * harus KETUK "Lanjutkan" DUA KALI (dalam {@link #DUPLICATE_ORDER_CONFIRM_WINDOW_MS})
     * sebelum boleh membuat transaksi kedua untuk pelanggan yang sama — mencegah order
     * ganda tak sengaja. Pelanggan Umum (walk-in, tanpa histori per-pelanggan) dilewati.
     */
    /**
     * Popup "⚠️ Pelanggan Sudah Ada di Antrian" — cermin popup web dengan nama yang sama. Muncul
     * saat pelanggan terpilih masih punya order PENDING/TERTUNDA yang BELUM diselesaikan, APA PUN
     * tanggalnya (beda dari gerbang "sudah order hari ini" yang dibatasi hari ini).
     *
     * <p>Sengaja TIDAK memblokir: operator kadang memang harus membuat order kedua. Tujuannya
     * memastikan ini bukan order ganda — sama seperti di web, tombol keduanya hanya menutup.
     * Sumber datanya vonis server ({@link #serverGuard}); tak ada padanan lokal karena antrian HP
     * hanya memuat PENDING milik perangkat ini sendiri (TERTUNDA pun tak masuk).</p>
     */
    private void maybeWarnOpenQueueOrder() {
        if (serverGuard == null || serverGuardCustomerId != selectedCustomerId) return;
        if (serverGuard.openOrderId <= 0 || isUmumCustomer()) return;
        // HANYA untuk penjualan — cermin gerbang web (fType === 'JUAL'). Galon Kembali / Jual Botol
        // untuk pelanggan yang tadi pagi order justru hal yang WAJAR, bukan indikasi order ganda.
        if (!isJualSelected()) return;
        // Anti-spam: satu order cukup diperingatkan sekali per sesi transaksi.
        if (lastOpenQueueWarnOrderId == serverGuard.openOrderId) return;
        lastOpenQueueWarnOrderId = serverGuard.openOrderId;

        String detail;
        if (Transaction.DELIVERY_TERTUNDA.equals(serverGuard.openStatus)) {
            detail = serverGuard.openResumesToday
                    ? "Order itu DITUNDA dan dijadwalkan lanjut HARI INI"
                    : "Order itu DITUNDA"
                        + (serverGuard.openResumeAt != null && serverGuard.openResumeAt.length() >= 16
                            ? ", dijadwalkan lanjut " + serverGuard.openResumeAt.substring(0, 16)
                            : "");
        } else {
            detail = "Order itu masih DALAM ANTREAN delivery";
        }

        new AlertDialog.Builder(this)
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setTitle("⚠️ Pelanggan Sudah Ada di Antrian")
                .setMessage("\"" + selectedCustomerName + "\" sudah punya order yang belum "
                        + "diselesaikan.\n\n" + detail + ".\n\n"
                        + "Pastikan ini bukan order ganda — order sebelumnya belum diselesaikan.")
                .setPositiveButton("Tutup, Lanjutkan Buat Order", null)
                .setNegativeButton("Buka Antrian", (d, w) ->
                        startActivity(new android.content.Intent(this, DeliveryQueueActivity.class)))
                .show();
    }

    private void maybeWarnDuplicateOrderToday(Customer c) {
        if (c == null || c.getId() <= 0 || isUmumCustomer()) return;
        if (!isJualSelected()) return;   // lihat alasan di maybeWarnOpenQueueOrder()
        com.crowja.damiupos.db.TransactionDao.TodayOrderInfo info =
                transactionDao.getTodayOrderInfo(c.getId());
        // Vonis server MENDAHULUI baris lokal: transaksi HP terisolasi per-perangkat, jadi order
        // yang dibuat kurir lain / dashboard tak punya baris lokal sama sekali. Bila server sudah
        // menjawab untuk pelanggan INI, pakai jawabannya (termasuk saat lokal kosong).
        if (serverGuard != null && serverGuardCustomerId == c.getId()) {
            info = serverGuard.todayStatus == null ? null
                    : new com.crowja.damiupos.db.TransactionDao.TodayOrderInfo(
                            serverGuard.todayTanggal, serverGuard.todayStatus);
        }
        if (info == null) return;
        if (lastDuplicateWarnCustomerId == c.getId()) return;   // sudah diperingatkan sesi ini
        lastDuplicateWarnCustomerId = c.getId();
        // Pelanggan lain terpilih sejak ketukan pertama → lupakan arming lama.
        if (pendingDuplicateOrderConfirmCustomerId != c.getId()) {
            pendingDuplicateOrderConfirmCustomerId = -1;
        }

        String statusLabel;
        if (Transaction.DELIVERY_DONE.equals(info.deliveryStatus)) {
            statusLabel = "sudah SELESAI diantar";
        } else if (Transaction.DELIVERY_PENDING.equals(info.deliveryStatus)) {
            statusLabel = "masih DALAM ANTREAN delivery";
        } else if ("TERTUNDA".equals(info.deliveryStatus)) {
            statusLabel = "berstatus TERTUNDA";
        } else {
            statusLabel = "sudah tercatat";
        }
        String jam = "";
        if (info.tanggal != null && info.tanggal.length() >= 16) {
            jam = " pukul " + info.tanggal.substring(11, 16);
        }

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setTitle("⚠️ Pelanggan Sudah Order Hari Ini")
                .setCancelable(false)
                .setMessage("\"" + c.getName() + "\" sudah membuat transaksi JUAL hari ini" + jam
                        + " (" + statusLabel + ").\n\n"
                        + "Yakin ingin membuat transaksi BARU lagi untuk pelanggan yang sama? "
                        + "Ketuk \"Lanjutkan\" dua kali untuk memastikan.")
                .setNegativeButton("BATAL", (d, w) -> {
                    pendingDuplicateOrderConfirmCustomerId = -1;
                    showCustomerPicker();
                })
                .setPositiveButton("LANJUTKAN", null)
                .create();
        dialog.setOnShowListener(d -> {
            android.widget.Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            positive.setOnClickListener(v -> {
                long now = System.currentTimeMillis();
                boolean armed = pendingDuplicateOrderConfirmCustomerId == c.getId()
                        && (now - pendingDuplicateOrderConfirmArmedAt) <= DUPLICATE_ORDER_CONFIRM_WINDOW_MS;
                if (armed) {
                    pendingDuplicateOrderConfirmCustomerId = -1;
                    dialog.dismiss();
                } else {
                    pendingDuplicateOrderConfirmCustomerId = c.getId();
                    pendingDuplicateOrderConfirmArmedAt = now;
                    positive.setText("KETUK SEKALI LAGI");
                }
            });
        });
        dialog.show();
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
                // Pelanggan baru yang dinamai persis "Umum" = bucket walk-in → tetap lewat
                // gerbang konfirmasi TEGAS (jangan lolos lewat jalur Pelanggan Baru).
                if (c != null && CustomerDao.UMUM_NAME.equals(c.getName())) confirmUmumSelection(c);
                else if (c != null) applySelectedCustomer(c);
            }
        }
    }

    /**
     * Cek Free tier monthly limit ({@link BuildConfig#FREE_MAX_TRX_PER_MONTH})
     * sebelum simpan. Pro lewat tanpa cek. Hit limit → paywall, kalau unlock
     * via rewarded ad, save() langsung diretry.
     */
    /**
     * Total ongkir sesuai isian form SAAT INI — cermin perhitungan di updateTotal()/doSave():
     * Per Galon = nominal × jumlah galon, Borongan = nominal apa adanya, Tanpa = 0.
     */
    private double currentOngkirTotal() {
        String mode = getSelectedOngkirType();
        if (Transaction.ONGKIR_NONE.equals(mode)) return 0;
        double v = 0;
        String s = etOngkir.getText() != null ? etOngkir.getText().toString().trim() : "";
        if (!s.isEmpty()) {
            try { v = Double.parseDouble(s); } catch (NumberFormatException ignored) {}
        }
        if (Transaction.ONGKIR_BORONGAN.equals(mode)) return v;
        int galon = 0;
        for (TransactionItem it : items) galon += it.jumlah;
        return v * Math.max(1, galon);
    }

    /**
     * Gerbang "ongkir tidak cocok dengan status pelanggan", diperiksa SAAT TRANSAKSI DIBUAT.
     *
     * <p>Popup di toggle ongkir hanya menangkap satu jalan masuk (staf menekan "Tanpa"). Ketidak-
     * cocokan juga terbentuk lewat jalan lain: pelanggan dipilih SETELAH mode diatur, nominal ongkir
     * dikosongkan manual saat mode masih Per Galon, atau transaksi dibuka dari pintasan. Karena itu
     * pemeriksaannya dipasang di jalur simpan — sekali, untuk semua jalan masuk. Dua arah: wajib
     * ongkir tapi Rp 0, dan bebas ongkir tapi ditagih.</p>
     *
     * <p>Tombol lanjut sengaja butuh DUA klik agar keputusannya sadar, bukan refleks menutup popup.</p>
     *
     * @return true bila boleh lanjut; false bila popup dibuka (lanjut lewat {@code onProceed}).
     */
    private boolean ongkirGate(Runnable onProceed) {
        if (ongkirAcknowledged) return true;
        if (!isJualSelected()) return true;              // JUAL_BOTOL & KEMBALI tak punya ongkir
        if (selectedCustomerId == -1) return true;       // belum ada pelanggan → belum ada statusnya
        // Promosi GRATIS memang pemberian tanpa ongkir — bukan ketidakcocokan (lihat applyPromosiPricing).
        if (promosiMode && isPromosiGratis()) return true;

        final double total = currentOngkirTotal();
        final boolean missing = selectedWajibOngkir && total <= 0;
        final boolean unexpected = !selectedWajibOngkir && total > 0;
        if (!missing && !unexpected) return true;

        NumberFormat nf = NumberFormat.getInstance(new Locale("id", "ID"));
        String msg = missing
                ? "Pelanggan ini ditandai \"Wajib Ongkir\", tetapi transaksi ini TANPA ONGKIR (Rp 0)."
                : "Pelanggan ini TIDAK ditandai Wajib Ongkir, tetapi transaksi ini dikenakan ongkir Rp "
                        + nf.format(Math.round(total)) + ".";
        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle(missing ? "⚠️ Pelanggan Wajib Ongkir" : "⚠️ Pelanggan Bebas Ongkir")
                .setMessage(msg + "\n\nPerbaiki dulu, atau tekan \"Tetap lanjutkan\" DUA KALI "
                        + "untuk melanjutkan dengan keputusan Anda.")
                .setNegativeButton("Perbaiki dulu", null)
                .setPositiveButton("Tetap lanjutkan", null)   // listener dipasang manual di bawah
                .create();
        // Listener dipasang SETELAH show(): listener bawaan selalu menutup dialog pada klik pertama,
        // padahal klik pertama di sini hanya menegaskan.
        dlg.setOnShowListener(d -> {
            final int[] clicks = {0};
            final android.widget.Button go = dlg.getButton(AlertDialog.BUTTON_POSITIVE);
            go.setOnClickListener(v -> {
                if (++clicks[0] < 2) {
                    go.setText("Klik sekali lagi untuk lanjut");
                    return;
                }
                ongkirAcknowledged = true;
                dlg.dismiss();
                onProceed.run();
            });
        });
        dlg.show();
        return false;
    }

    private void trySave() {
        // Ongkir tak cocok dgn status pelanggan → popup; simpan diteruskan dari dalam popup.
        if (!ongkirGate(this::trySave)) return;

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

        // Pengaman Pelanggan UMUM (UI sudah menyembunyikan opsinya — ini lapis kedua):
        // galon keluar/masuk tidak dicatat → tipe "Galon Kembali" diblok dan jumlah
        // galon kembali pada JUAL dipaksa 0.
        if (isUmumCustomer()) {
            if (!isJual) {
                new AlertDialog.Builder(this)
                        .setIcon(android.R.drawable.ic_dialog_alert)
                        .setTitle("⚠️ Pelanggan Umum")
                        .setMessage("Transaksi \"Galon Kembali\" tidak tersedia untuk Pelanggan "
                                + "Umum — galon keluar/masuk tidak dicatat. Pilih pelanggan terdaftar.")
                        .setPositiveButton("OK", null)
                        .show();
                return;
            }
            jumlahKembali = 0;
        }

        // Galon kembali melebihi galon DIPINJAM pelanggan → peringatan TEGAS berkedip (field kolom
        // berkedip + toast). Muncul 2x sebagai konfirmasi, lalu tetap boleh lanjut (staf bisa menimpa,
        // mis. konsumen mengembalikan botol lama ekstra). Hanya JUAL pelanggan terdaftar (bukan Umum).
        if (isJual && selectedCustomerId > 0 && !isUmumCustomer() && jumlahKembali > selectedHeld) {
            returnOverWarnCount++;
            blinkView(etJumlahKembali);
            Toast.makeText(this, "❗ Galon kembali (" + jumlahKembali + ") melebihi galon dipinjam "
                    + "pelanggan (" + selectedHeld + "). Periksa lagi — tekan Simpan sekali lagi untuk tetap lanjut.",
                    Toast.LENGTH_LONG).show();
            if (returnOverWarnCount < 2) return;
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
                ? umumSafeOwnership(getSelectedOwnership()) : Transaction.OWNERSHIP_PINJAM;
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
                            // Punya hutang lama? Tawarkan sekalian menagihnya SEBELUM simpan, supaya
                            // baris pelunasannya bisa ditautkan ke transaksi ini.
                            askSettleDebt(method, () -> doSave(fIsJual, fTotalJumlah, fOngkir, fTotal,
                                    fJumlahKembali, fOngkirType, fOwnership, fHargaBotol));
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

    /** Field: nominal pelunasan hutang lama yang disetujui operator sebelum simpan (0 = tidak ada). */
    private double pendingDebtPay;

    /**
     * Tawarkan "sekalian lunasi hutang" saat pelanggan terpilih masih punya sisa hutang. Nominalnya
     * UANG TAMBAHAN yang diterima — tagihan transaksi ini TIDAK berubah, jadi omzet tak tergerus.
     * Dilewati bila transaksi ini sendiri dibayar HUTANG (melunasi hutang pada nota yang justru
     * menambah hutang tidak masuk akal). Nominal tetap dipagari sisa hutang di CustomerDebtDao.
     */
    private void askSettleDebt(String method, Runnable next) {
        pendingDebtPay = 0;
        double sisa = (selectedCustomerId > 0 && !Transaction.PAY_HUTANG.equals(method))
                ? new com.crowja.damiupos.db.CustomerDebtDao(DatabaseHelper.getInstance(this))
                        .balanceFor(selectedCustomerId)
                : 0;
        if (sisa <= 0) {
            next.run();
            return;
        }

        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        final android.widget.EditText nominal = new android.widget.EditText(this);
        nominal.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        nominal.setText(String.valueOf((long) Math.round(sisa)));
        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(nominal);

        new AlertDialog.Builder(this)
                .setTitle("Sekalian Lunasi Hutang?")
                .setMessage(selectedCustomerName + " masih punya hutang Rp "
                        + java.text.NumberFormat.getNumberInstance(new java.util.Locale("in", "ID"))
                                .format(Math.round(sisa))
                        + ". Tagihan transaksi ini tidak berubah — nominal di bawah adalah uang tambahan.")
                .setView(box)
                .setNegativeButton("Nanti saja", (d, w) -> next.run())
                .setPositiveButton("Tagih", (d, w) -> {
                    try {
                        pendingDebtPay = Double.parseDouble(nominal.getText().toString().trim());
                    } catch (NumberFormatException ex) {
                        pendingDebtPay = 0;
                    }
                    next.run();
                })
                .setOnCancelListener(d -> next.run())
                .show();
    }

    /** Callback metode pembayaran. */
    private interface OnPayment { void onPick(String method); }

    /** Dialog pilih metode pembayaran: Tunai / QRIS / Transfer / Hutang. */
    private void showPaymentPicker(OnPayment cb) {
        final String[] labels = {"Tunai", "QRIS", "Transfer", "Hutang (bayar nanti)"};
        final String[] values = {Transaction.PAY_TUNAI, Transaction.PAY_QRIS,
                Transaction.PAY_TRANSFER, Transaction.PAY_HUTANG};
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

    /**
     * "⏸ Pesanan Tertunda" — popup jadwal lanjut otomatis dengan preset (+3 jam / Besok jam buka /
     * Lusa jam buka), cermin popup web (App\Support\TertundaSchedule + trxScheduleQuick di
     * transactions/create.blade.php). Konfirmasi → set tertundaRequested + geser selectedTrxDate ke
     * jadwal ini (tanggal transaksi ikut jadwal, BUKAN hari dibuat) → lanjut ke trySave() biasa.
     */
    private void showTertundaScheduleDialog() {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_tertunda_schedule, null);
        TextView tvAt = dialogView.findViewById(R.id.tvTertundaAt);
        com.google.android.material.chip.Chip chip3h = dialogView.findViewById(R.id.chipTertunda3h);
        com.google.android.material.chip.Chip chipTomorrow = dialogView.findViewById(R.id.chipTertundaTomorrow);
        com.google.android.material.chip.Chip chip15 = dialogView.findViewById(R.id.chipTertunda15);
        com.google.android.material.chip.Chip chipDay2 = dialogView.findViewById(R.id.chipTertundaDay2);

        String openTime = settingsDao.getOpsOpenTime();
        int openH = 8, openM = 0;
        try {
            String[] p = openTime.split(":");
            openH = Integer.parseInt(p[0].trim());
            openM = Integer.parseInt(p[1].trim());
        } catch (Exception ignored) {}
        final int fOpenH = openH, fOpenM = openM;
        chipTomorrow.setText("Besok " + openTime);
        chipDay2.setText("Lusa " + openTime);

        // Default = besok jam buka cabang (cermin TertundaSchedule::defaultResumeAt di web).
        final Calendar[] holder = new Calendar[1];
        Calendar def = Calendar.getInstance();
        def.add(Calendar.DATE, 1);
        def.set(Calendar.HOUR_OF_DAY, fOpenH);
        def.set(Calendar.MINUTE, fOpenM);
        def.set(Calendar.SECOND, 0);
        holder[0] = def;

        Runnable refreshLabel = () -> tvAt.setText(trxDateOnlyFmt.format(holder[0].getTime())
                + "   " + trxTimeOnlyFmt.format(holder[0].getTime()));
        refreshLabel.run();

        chip3h.setOnClickListener(v -> {
            Calendar c = Calendar.getInstance();
            c.add(Calendar.HOUR_OF_DAY, 3);
            holder[0] = c;
            refreshLabel.run();
        });
        chipTomorrow.setOnClickListener(v -> {
            Calendar c = Calendar.getInstance();
            c.add(Calendar.DATE, 1);
            c.set(Calendar.HOUR_OF_DAY, fOpenH);
            c.set(Calendar.MINUTE, fOpenM);
            c.set(Calendar.SECOND, 0);
            holder[0] = c;
            refreshLabel.run();
        });
        chip15.setOnClickListener(v -> {
            Calendar c = Calendar.getInstance();
            c.add(Calendar.DATE, 1);
            c.set(Calendar.HOUR_OF_DAY, 15);
            c.set(Calendar.MINUTE, 0);
            c.set(Calendar.SECOND, 0);
            holder[0] = c;
            refreshLabel.run();
        });
        chipDay2.setOnClickListener(v -> {
            Calendar c = Calendar.getInstance();
            c.add(Calendar.DATE, 2);
            c.set(Calendar.HOUR_OF_DAY, fOpenH);
            c.set(Calendar.MINUTE, fOpenM);
            c.set(Calendar.SECOND, 0);
            holder[0] = c;
            refreshLabel.run();
        });
        tvAt.setOnClickListener(v -> {
            Calendar cur = holder[0];
            new android.app.DatePickerDialog(this, (dpView, year, month, day) -> {
                new android.app.TimePickerDialog(this, (tpView, hour, minute) -> {
                    Calendar picked = Calendar.getInstance();
                    picked.set(year, month, day, hour, minute, 0);
                    holder[0] = picked;
                    refreshLabel.run();
                }, cur.get(Calendar.HOUR_OF_DAY), cur.get(Calendar.MINUTE), true).show();
            }, cur.get(Calendar.YEAR), cur.get(Calendar.MONTH), cur.get(Calendar.DAY_OF_MONTH)).show();
        });

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("⏸ Tunda Pesanan")
                .setView(dialogView)
                .setNegativeButton("Batal", null)
                .setPositiveButton("Lanjutkan", null)   // override di bawah agar validasi tak menutup dialog
                .create();
        dlg.setOnShowListener(di -> dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            Calendar resume = holder[0];
            if (resume.getTimeInMillis() <= System.currentTimeMillis()) {
                Toast.makeText(this, "Jadwal lanjut harus di masa depan", Toast.LENGTH_SHORT).show();
                return;
            }
            tertundaRequested = true;
            tertundaResumeAtDb = trxDbFmt.format(resume.getTime());
            selectedTrxDate = tertundaResumeAtDb;   // tanggal transaksi ikut jadwal (cermin web)
            updateTanggalTrxButton();
            dlg.dismiss();
            trySave();
        }));
        dlg.show();
    }

    /** "KIRIM {REL}, {HARI TANGGAL} pukul {HH:mm} WIB" — cermin TertundaSchedule::noteLabel di web,
     *  ditempel ke Catatan transaksi supaya jadwal kirim terbaca langsung tanpa buka detail. */
    private String tertundaNoteLabel(Date resume) {
        Calendar today = Calendar.getInstance();
        today.set(Calendar.HOUR_OF_DAY, 0); today.set(Calendar.MINUTE, 0);
        today.set(Calendar.SECOND, 0); today.set(Calendar.MILLISECOND, 0);
        Calendar resumeDay = Calendar.getInstance();
        resumeDay.setTime(resume);
        resumeDay.set(Calendar.HOUR_OF_DAY, 0); resumeDay.set(Calendar.MINUTE, 0);
        resumeDay.set(Calendar.SECOND, 0); resumeDay.set(Calendar.MILLISECOND, 0);
        long diffDays = Math.round((resumeDay.getTimeInMillis() - today.getTimeInMillis()) / 86400000.0);
        String rel;
        if (diffDays < 0) {
            long n = -diffDays;
            rel = n == 1 ? "KEMARIN" : (n + " HARI LALU");
        } else if (diffDays == 0) {
            rel = "HARI INI";
        } else if (diffDays == 1) {
            rel = "BESOK";
        } else if (diffDays == 2) {
            rel = "LUSA";
        } else {
            rel = diffDays + " HARI LAGI";
        }
        String date = new SimpleDateFormat("EEEE d MMMM yyyy", new Locale("id", "ID"))
                .format(resume).toUpperCase(new Locale("id", "ID"));
        String time = new SimpleDateFormat("HH:mm", Locale.US).format(resume);
        return "KIRIM " + rel + ", " + date + " pukul " + time + " WIB";
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

    /**
     * Simpan nomor WA pelanggan ke KONTAK HP begitu transaksinya tersimpan, bila belum ada.
     *
     * <p>Gunanya untuk WhatsApp: extra "jid" (yang membuat WA membuka chat pelanggan LANGSUNG,
     * tanpa layar "Kirim ke…") hanya dihormati untuk nomor yang tersimpan sebagai kontak — diuji
     * langsung di perangkat, lihat {@link com.crowja.damiupos.wa.WaContactEnsure}. Dengan menyimpan
     * di sini, semua fitur kirim-WA berikutnya (struk, Follow Up, Laporan Kendala Pengiriman) sudah
     * menemukan pelanggan itu di kontak dan bisa membuka chat-nya seketika.
     *
     * <p>Di THREAD LATAR: ini query + tulis ke ContentProvider kontak, bukan pekerjaan UI thread.
     * Sepenuhnya best-effort — tanpa izin kontak atau bila gagal, transaksinya TIDAK terpengaruh.
     * Nomor yang sudah ada tak pernah digandakan (PhoneLookup mencocokkan 08…/+62…/62…).
     */
    private void ensureCustomerSavedInContacts() {
        final String name = selectedCustomerName;
        final String phone = selectedCustomerPhone;
        if (phone == null || phone.trim().isEmpty()) return;
        final android.content.Context app = getApplicationContext();
        new Thread(() -> {
            try {
                com.crowja.damiupos.wa.WaContactEnsure.ensure(app, name, phone);
            } catch (Throwable ignored) {
                // kontak cuma pelengkap; jangan pernah menjatuhkan alur transaksi
            }
        }).start();
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
        // Catatan Order pelanggan — lihat penjelasan di doSave().
        if (selectedCustomerId > 0) {
            Customer noteCust = customerDao.getById(selectedCustomerId);
            String orderNote = noteCust != null && noteCust.getOrderNote() != null
                    ? noteCust.getOrderNote().trim() : "";
            if (!orderNote.isEmpty()) catatan += "\n[CATATAN ORDER] " + orderNote;
        }
        trx.setCatatan(catatan);
        long newBotolTrxId = transactionDao.insert(trx);
        ensureCustomerSavedInContacts();
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
        if (savedBotolTrx != null && savedBotolTrx.getReceiptNo() != null) {
            r.putExtra(ReceiptActivity.EXTRA_RECEIPT_NO, savedBotolTrx.getReceiptNo());
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
        // Persist last ownership selection for next transaction. Umum dilewati: ownership-nya
        // DIPAKSA "Botol Sendiri" (bukan pilihan staf) dan restore di onCreate hanya mengenal
        // BELI/PINJAM — mempersistnya akan menimpa preferensi asli staf dengan PINJAM.
        if (isJual && ownership != null && !isUmumCustomer()) {
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
            // "Perangkat yang ditugaskan" (staf/marketing/SPV): null saat "Perangkat ini" → tanpa penugasan.
            if (assignDeviceEligible) {
                trx.setAssignedDeviceUuid(selectedAssignUuid());
            }
        }
        double hargaGR = 0;
        int rusak = 0;
        // 🎁 "Bonus Beli N Gratis 1" (cermin App\Support\ProductBonus di web, tapi dihitung dari
        // riwayat transaksi PERANGKAT INI saja — {@see ProductBonusDao}): untuk tiap jenis produk
        // TERBAYAR pada order ini, hitung akumulasi galon terbayar (termasuk baris ini) dikurangi
        // yang SUDAH pernah diberikan; sisanya (bila > 0) ditambahkan sebagai baris gratis. Ledger-
        // nya baru dicatat SETELAH transaksi ini benar-benar tersimpan (butuh _id-nya) — lihat bawah.
        // Reuse TransactionItem sebagai pasangan (productName, qty) — productId/hargaPerGalon diabaikan.
        List<TransactionItem> productBonusGranted = new ArrayList<>();
        if (isJual) {
            List<TransactionItem> itemsToSave = new ArrayList<>(items);
            if (selectedCustomerId > 0) {
                Customer bonusCust = customerDao.getById(selectedCustomerId);
                if (bonusCust != null && bonusCust.isBonusEnabled()) {
                    int threshold = settingsDao.getProductBonusThreshold();
                    com.crowja.damiupos.db.ProductBonusDao pbDao = new com.crowja.damiupos.db.ProductBonusDao(DatabaseHelper.getInstance(this));
                    java.util.Map<String, Integer> paidThisTrx = new java.util.LinkedHashMap<>();
                    for (TransactionItem it : items) {
                        if (it.hargaPerGalon > 0 && it.productName != null && !it.productName.isEmpty()) {
                            paidThisTrx.merge(it.productName, it.jumlah, Integer::sum);
                        }
                    }
                    for (java.util.Map.Entry<String, Integer> e : paidThisTrx.entrySet()) {
                        String productName = e.getKey();
                        int lifetimePaid = pbDao.lifetimePaidQty(selectedCustomerId, productName) + e.getValue();
                        int alreadyGranted = pbDao.alreadyGranted(selectedCustomerId, productName);
                        int owed = (lifetimePaid / threshold) - alreadyGranted;
                        if (owed > 0) {
                            itemsToSave.add(new TransactionItem(0, productName + " (Bonus)", owed, 0));
                            productBonusGranted.add(new TransactionItem(0, productName, owed, 0));
                        }
                    }
                }
            }
            trx.setItems(itemsToSave);
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
        // Saldo REFUND dipakai membayar order ini. Seperti saldo komisi, TOTAL TIDAK dikurangi:
        // refund adalah cara BAYAR, bukan diskon — kalau total dipotong, omzet & bonus penjualan
        // ikut mengecil dan refund terhitung dua kali.
        double refundUsed = isJual ? refundDipotong() : 0;
        if (refundUsed > 0) {
            NumberFormat nfr = NumberFormat.getInstance(new Locale("id", "ID"));
            String marker = "[REFUND Rp " + nfr.format(Math.round(refundUsed)) + "]";
            catatan = catatan.isEmpty() ? marker : (marker + " " + catatan);
        }
        // Catatan Order pelanggan (instruksi pengiriman tetap, mis. "titip satpam") — otomatis
        // ditambahkan ke SETIAP transaksi baru pelanggan ini, cermin TransactionController::
        // storePending di web. Dibaca langsung dari DB (bukan objek pelanggan di picker, yang bisa
        // basi bila baru diedit) sesaat sebelum simpan.
        if (selectedCustomerId > 0) {
            Customer noteCust = customerDao.getById(selectedCustomerId);
            String orderNote = noteCust != null && noteCust.getOrderNote() != null
                    ? noteCust.getOrderNote().trim() : "";
            if (!orderNote.isEmpty()) {
                String tag = "[CATATAN ORDER] " + orderNote;
                catatan = catatan.isEmpty() ? tag : (catatan + "\n" + tag);
            }
        }
        // Pesanan Tertunda: tandai delivery_status + jadwal lanjut (TransactionDao.insert() yang
        // menulis kolom-kolomnya) + tempel label jadwal ke Catatan — cermin App\Support\
        // TertundaSchedule::noteLabel di web (StrukWa/WA hanya menampilkan teks setelah "Catatan:",
        // jadi label ini WAJIB lewat sini, bukan ditempel belakangan).
        if (isJual && tertundaRequested && tertundaResumeAtDb != null) {
            try {
                Date resumeDate = trxDbFmt.parse(tertundaResumeAtDb);
                if (resumeDate != null) {
                    trx.setDeliveryStatus(Transaction.DELIVERY_TERTUNDA);
                    trx.setDeliveryTertundaResumeAt(DatabaseHelper.isoFrom(resumeDate.getTime()));
                    String label = tertundaNoteLabel(resumeDate);
                    catatan = catatan.isEmpty() ? label : (catatan + "\n" + label);
                }
            } catch (Exception ignored) {}
        }
        if (!catatan.isEmpty()) trx.setCatatan(catatan);
        // ⚡ Tandai Prioritas: berlaku untuk order langsung MAUPUN Tertunda (TransactionDao.insert()
        // yang menstempel orderPriorityAt/By sebenarnya) — cermin DeliveryController::prioritize.
        if (isJual && cbPrioritas != null && cbPrioritas.isChecked()) {
            trx.setPriorityRequested(true);
            if (etPriorityReason != null) {
                String reason = etPriorityReason.getText() != null
                        ? etPriorityReason.getText().toString().trim() : "";
                if (!reason.isEmpty()) trx.setOrderPriorityReason(reason);
            }
        }
        // 🔁 Order kembali PERTAMA: pelanggan ini sudah PERSIS 1 transaksi (effectiveTrx — max lokal
        // vs cross-device, cermin project_damiupos_effective_agg_max) sebelum order ini → pembelian
        // ke-2 mereka, pertama kalinya balik lagi. Ditandai prioritas OTOMATIS (cermin
        // TransactionController::store di web) KECUALI staf sudah mencentang "Tandai Prioritas"
        // sendiri di atas (alasan manual staf menang, tidak ditimpa). TransactionDao.insert() sendiri
        // yang menggerbang penulisan kolomnya ke baris yang benar-benar masuk antrian delivery.
        boolean isFirstRepeatOrder = false;
        if (isJual && selectedCustomerId > 0) {
            Customer repeatCust = customerDao.getById(selectedCustomerId);
            isFirstRepeatOrder = repeatCust != null && repeatCust.effectiveTrx() == 1;
        }
        if (isFirstRepeatOrder && !trx.isPriorityRequested()) {
            trx.setPriorityRequested(true);
            trx.setOrderPriorityReason("Order kembali pertama — otomatis diprioritaskan");
        }
        long newTrxId = transactionDao.insert(trx);
        ensureCustomerSavedInContacts();
        // Catat buku besar bonus SETELAH baris ini benar-benar tersimpan (butuh _id-nya) — item
        // gratisnya sendiri sudah ikut items_json lewat trx.setItems(itemsToSave) di atas.
        if (!productBonusGranted.isEmpty()) {
            com.crowja.damiupos.db.ProductBonusDao pbDao = new com.crowja.damiupos.db.ProductBonusDao(DatabaseHelper.getInstance(this));
            for (TransactionItem g : productBonusGranted) {
                pbDao.insert(selectedCustomerId, g.productName, g.jumlah, newTrxId);
            }
        }
        // Pesanan Terjadwal: tandai inbox SELESAI TEPAT saat transaksi tersimpan (bukan saat tombol
        // ditekan) → flag selesai = "sudah dibuatkan transaksi". Batal simpan = inbox tetap PENDING.
        long inboxId = getIntent().getLongExtra(EXTRA_INBOX_ID, -1);
        if (inboxId > 0) {
            new com.crowja.damiupos.db.OrderInboxDao(DatabaseHelper.getInstance(this))
                    .updateStatus(inboxId, com.crowja.damiupos.model.OrderInbox.STATUS_APPROVED, newTrxId);
            com.crowja.damiupos.wa.OrderAlertService.refresh(this);
        }
        // Saldo refund terpakai → tulis baris 'usage' di buku besar (dibatasi ulang oleh DAO),
        // ditautkan ke uuid transaksi ini supaya jejaknya jelas di web & perangkat lain.
        if (refundUsed > 0) {
            new com.crowja.damiupos.db.CustomerRefundDao(DatabaseHelper.getInstance(this))
                    .spend(selectedCustomerId, refundUsed, transactionDao.getSyncUuidById(newTrxId),
                            settingsDao.getCurrentUserName());
        }

        // Dibayar HUTANG → catat piutangnya di buku besar. Penjualannya TETAP tercatat penuh
        // (galonnya sudah keluar, jadi omzet & bonus dihitung seperti biasa); yang belum masuk
        // hanyalah UANGNYA. total_harga SENGAJA tak dikurangi — sama seperti refund, mengurangi
        // total akan menyusutkan omzet sekaligus menghitung hutangnya dua kali.
        if (isJual && Transaction.PAY_HUTANG.equals(pendingPayment)
                && selectedCustomerId > 0 && trx.getTotalHarga() > 0) {
            new com.crowja.damiupos.db.CustomerDebtDao(DatabaseHelper.getInstance(this))
                    .charge(selectedCustomerId, trx.getTotalHarga(), "Penjualan dibayar nanti",
                            transactionDao.getSyncUuidById(newTrxId), settingsDao.getCurrentUserName());
        }

        // Pelunasan hutang lama yang disetujui di dialog → baris 'payment' (dipagari ulang oleh DAO),
        // ditautkan ke uuid transaksi ini supaya jejaknya jelas di web & perangkat lain.
        if (isJual && pendingDebtPay > 0 && selectedCustomerId > 0) {
            double paidDebt = new com.crowja.damiupos.db.CustomerDebtDao(DatabaseHelper.getInstance(this))
                    .pay(selectedCustomerId, pendingDebtPay, transactionDao.getSyncUuidById(newTrxId),
                            settingsDao.getCurrentUserName(), "Dilunasi saat transaksi");
            if (paidDebt > 0) {
                // Penanda yang SAMA dengan yang ditulis web (TransactionController) -- ia dibaca
                // manusia di Detail Transaksi dan disaring dari struk pelanggan. Tanpa ini, nota
                // yang sama tampak beda tergantung dicatat lewat HP atau dashboard.
                String bayarMarker = "[BAYAR HUTANG Rp "
                        + java.text.NumberFormat.getNumberInstance(new java.util.Locale("in", "ID"))
                                .format(Math.round(paidDebt)) + "]";
                String catatanKini = trx.getCatatan() != null ? trx.getCatatan() : "";
                if (!catatanKini.contains("[BAYAR HUTANG")) {
                    transactionDao.updateCatatan(newTrxId,
                            (catatanKini.isEmpty() ? "" : catatanKini + "\n") + bayarMarker);
                }
                Toast.makeText(this, "Pelunasan hutang dicatat: Rp "
                        + java.text.NumberFormat.getNumberInstance(new java.util.Locale("in", "ID"))
                                .format(Math.round(paidDebt)), Toast.LENGTH_LONG).show();
            }
            pendingDebtPay = 0;
        }

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
            // Gift PRODUK belum diklaim: ditawarkan lewat popup di layar struk, yang butuh _id
            // transaksi ini untuk menuliskan baris gift-nya kembali.
            r.putExtra(ReceiptActivity.EXTRA_GIFT_TRX_ID, newTrxId);
            // Token link lacak → dibawa ke struk supaya tombol "Kirim Struk + Tracking" bisa
            // mengirim link pantau pengiriman ke pelanggan.
            com.crowja.damiupos.model.Transaction savedTrx = transactionDao.getById(newTrxId);
            if (savedTrx != null && savedTrx.getDeliveryToken() != null) {
                r.putExtra(ReceiptActivity.EXTRA_DELIVERY_TOKEN, savedTrx.getDeliveryToken());
            }
            if (savedTrx != null && savedTrx.getReceiptNo() != null) {
                r.putExtra(ReceiptActivity.EXTRA_RECEIPT_NO, savedTrx.getReceiptNo());
            }
            // 🔁 Order kembali pertama — 'prioritized' dibaca dari BARIS TERSIMPAN (bukan niat), sama
            // seperti delivery token di atas: TransactionDao.insert() menggerbang penulisan kolom
            // prioritas ke baris yang benar-benar masuk antrian, jadi ini mencerminkan hasil sungguhan.
            if (isFirstRepeatOrder) {
                r.putExtra(ReceiptActivity.EXTRA_FIRST_REPEAT_ORDER, true);
                r.putExtra(ReceiptActivity.EXTRA_FIRST_REPEAT_PRIORITIZED,
                        savedTrx != null && savedTrx.getOrderPriorityAt() != null);
            }
            // 🎁 Bonus "Beli N Gratis 1" terpenuhi — popup di layar struk (cermin popup web).
            if (!productBonusGranted.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (TransactionItem g : productBonusGranted) {
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(g.jumlah).append("× ").append(g.productName);
                }
                r.putExtra(ReceiptActivity.EXTRA_PRODUCT_BONUS_GRANTED, sb.toString());
            }
            if (forwardedInboxSenderName != null) {
                r.putExtra(ReceiptActivity.EXTRA_INBOX_SENDER_NAME, forwardedInboxSenderName);
            }
            r.putExtra(ReceiptActivity.EXTRA_ITEMS_JSON,
                    TransactionItem.listToJson(items));
            r.putExtra(ReceiptActivity.EXTRA_JUMLAH_KEMBALI, jumlahKembali);
            r.putExtra(ReceiptActivity.EXTRA_ONGKIR, ongkir);
            r.putExtra(ReceiptActivity.EXTRA_ONGKIR_TYPE, ongkirType);
            // Kepemilikan + harga botol WAJIB ikut: tanpa keduanya composeTextStruk menghitung
            // biaya botol = 0, lalu SISA total yang tak terjelaskan dilempar ke baris "Ongkir" —
            // pelanggan yang tidak diantar menerima struk bertuliskan ongkir puluhan ribu.
            // Struk yang dibuka ulang dari Riwayat sudah benar karena hydrateFromTransaction
            // mengisi kedua extra ini; jalur langsung setelah simpan yang tertinggal.
            r.putExtra(ReceiptActivity.EXTRA_OWNERSHIP, ownership);
            r.putExtra(ReceiptActivity.EXTRA_HARGA_BOTOL, hargaBotol);
            r.putExtra(ReceiptActivity.EXTRA_TOTAL_HARGA, totalHarga);
            String catatanStr = etCatatan.getText() != null ? etCatatan.getText().toString().trim() : "";
            r.putExtra(ReceiptActivity.EXTRA_CATATAN, catatanStr);
            if (pendingPayment != null) r.putExtra(ReceiptActivity.EXTRA_PAYMENT_METHOD, pendingPayment);
            // Pencairan komisi: bagian yang dibayar dari saldo komisi + sisa saldo, untuk struk.
            if (saldoUsed > 0) {
                r.putExtra(ReceiptActivity.EXTRA_SALDO_DIPOTONG, saldoUsed);
                r.putExtra(ReceiptActivity.EXTRA_SALDO_AFTER, Math.max(0, resellerSaldo - saldoUsed));
            }
            // Potongan SALDO REFUND → struk (foto & teks WA) menampilkan "Dari saldo refund" +
            // "Sisa dibayar". Nilainya yang BENAR-BENAR tercatat di buku besar (sudah di-cap DAO).
            if (refundUsed > 0) {
                r.putExtra(ReceiptActivity.EXTRA_REFUND_DIPOTONG, refundUsed);
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
