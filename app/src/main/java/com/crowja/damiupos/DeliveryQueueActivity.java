package com.crowja.damiupos;

import android.Manifest;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.DatePickerDialog;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.app.TimePickerDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Build.VERSION;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.TextUtils.TruncateAt;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.AlphaAnimation;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ImageView.ScaleType;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.crowja.damiupos.R.color;
import com.crowja.damiupos.R.drawable;
import com.crowja.damiupos.R.id;
import com.crowja.damiupos.R.layout;
import com.crowja.damiupos.R.menu;
import com.crowja.damiupos.adapter.TransactionAdapter;
import com.crowja.damiupos.checkout.CheckoutStrukText;
import com.crowja.damiupos.db.CustomerDao;
import com.crowja.damiupos.db.CustomerDebtDao;
import com.crowja.damiupos.db.CustomerRefundDao;
import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.ProductDao;
import com.crowja.damiupos.db.SettingsDao;
import com.crowja.damiupos.db.TransactionDao;
import com.crowja.damiupos.db.UserDao;
import com.crowja.damiupos.map.LiveDeviceOverlay;
import com.crowja.damiupos.model.Customer;
import com.crowja.damiupos.model.Product;
import com.crowja.damiupos.model.Transaction;
import com.crowja.damiupos.model.TransactionItem;
import com.crowja.damiupos.model.User;
import com.crowja.damiupos.sync.LocationReporter;
import com.crowja.damiupos.sync.SyncApi;
import com.crowja.damiupos.sync.SyncScheduler;
import com.crowja.damiupos.sync.SyncSettings;
import com.crowja.damiupos.sync.VersionUpdater;
import com.crowja.damiupos.util.BitmapUtils;
import com.crowja.damiupos.util.CompassArrowView;
import com.crowja.damiupos.util.DeliveryNavLogic;
import com.crowja.damiupos.util.DeliveryProofPolicy;
import com.crowja.damiupos.util.TertundaRows;
import com.crowja.damiupos.util.Ts;
import com.crowja.damiupos.wa.WaContactEnsure;
import com.crowja.damiupos.wa.WaShare;
import com.google.android.material.badge.BadgeDrawable;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.textfield.TextInputEditText;
import java.io.File;
import java.io.IOException;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;
import com.crowja.damiupos.util.CameraIntents;

public class DeliveryQueueActivity extends AppCompatActivity {
   /** _id lokal order yang dibuka begitu antrean tampil — dikirim DeepLinkActivity untuk link
    *  pesanan damiupos://pesanan?trx=<uuid> (grup eskalasi) maupun link lacak publik
    *  https://order.airfrez.com/tracking/{token} (dipakai ulang staff dari komplain/chat WA).
    *  Berlaku untuk order berjalan & tertunda. */
   public static final String EXTRA_FOCUS_TRX_ID = "focus_trx_id";
   /** Dari panel navigasi "✅ Selesai": jalankan alur penyelesaian order ini, lalu lanjut ke titik berikutnya. */
   public static final String EXTRA_COMPLETE_TRX_ID = "complete_trx_id";
   /** Navigasi PER TITIK (wizard) sedang dipakai: tiap Selesai → susun ulang sisa rute → navigasi titik berikutnya. */
   private boolean wizardActive = false;
   private boolean wizardAdvancePending = false;
   private long pendingCompleteTrxId = -1L;
   /** Order yang Selesai-nya dipicu panel navigasi — penyelesaiannya melanjutkan ke titik berikutnya. */
   private long wizardCompleteId = -1L;
   private static final String KEY_WIZARD_SKIPS = "wizard_skip_ids";
   /** "▶ Jalankan & Navigasi" rit berikutnya: begitu rit mulai, langsung navigasi per titik (berlaku 2 mnt). */
   private long navAfterStartAt = 0L;
   private boolean awaitingNextRit = false;
   private android.app.ProgressDialog wizardWait;
   private static final SimpleDateFormat SDF_PARSE;
   private long pendingFocusTrxId = -1L;
   private TransactionDao dao;
   private CustomerDao customerDao;
   private ProductDao productDao;
   private Map<String, Product> productByName = new HashMap<>();
   private RecyclerView rv;
   private TextView tvEmpty;
   private TextView tvSummary;
   private QueueAdapter adapter;
   private boolean selectionMode = false;
   private final LinkedHashSet<Long> selectedIds = new LinkedHashSet<>();
   // Mode pilih-banyak "Ambil Alih" untuk Tab 2 (Perangkat Lain) & Tab 3 (Pesanan Terbuka).
   // Dipisah per tab, bukan satu himpunan bersama: keduanya memakai kunci yang berbeda (uuid
   // transaksi dari server vs id lokal) DAN aturan klaim yang berbeda (order perangkat lain harus
   // menyebut pemilik saat ini sebagai syarat, Pesanan Terbuka tidak bertuan).
   private boolean claimSelectOther, claimSelectOpen;
   private final LinkedHashSet<String> claimSelectedOther = new LinkedHashSet<>();
   private final LinkedHashSet<Long> claimSelectedOpen = new LinkedHashSet<>();
   private View barClaimOther, barClaimOpen;
   private TextView tvClaimOtherCount, tvClaimOpenCount, tvOtherSummary, tvOpenSummary;
   private View barRute;
   private TextView tvSelCount;
   private MaterialButton btnJalankanBanyak;
   private MaterialButton btnSelAksi;
   private CheckBox cbSelectAll;
   private boolean syncingSelectAll = false;
   private static final int MAX_ROUTE_STOPS = 10;
   private TabLayout tabs;
   private View tab1Container;
   private View tab2Container;
   private View tab3Container;
   private static final int TAB_MINE = 0;
   private static final int TAB_OTHER = 1;
   private static final int TAB_OPEN = 2;
   private int activeTab = 0;
   private RecyclerView rvOtherDevices;
   private TextView tvOtherDevicesEmpty;
   private OtherDevicesAdapter otherDevicesAdapter;
   private TextInputEditText etSearchOther;
   private String searchOtherQuery = "";
   private static final int SORT_OTHER_DISTANCE = 0;
   private static final int SORT_OTHER_GALON = 1;
   private int sortOtherMode = 0;
   private double otherLat = Double.NaN;
   private double otherLng = Double.NaN;
   private RecyclerView rvOpenDispatch;
   private TextView tvOpenDispatchEmpty;
   private OpenDispatchAdapter openDispatchAdapter;
   private TextInputEditText etSearchOpen;
   private String searchOpenQuery = "";
   private TextInputEditText etSearchMine;
   private String searchMineQuery = "";
   // Filter "pernah dikomplain" (badge 😠) -- independen per tab, cermin searchMineQuery/searchOpenQuery.
   private boolean complaintOnlyMine = false;
   private boolean complaintOnlyOpen = false;
   private boolean complaintOnlyOther = false;
   // Filter jenis order per tab: 0 = semua, 1 = Penjualan saja (JUAL), 2 = Ambil Galon saja (KEMBALI/ambil).
   private static final int KIND_ALL = 0, KIND_JUAL = 1, KIND_AMBIL = 2;
   private int kindMine = KIND_ALL, kindOther = KIND_ALL, kindOpen = KIND_ALL;
   // Filter PRODUK per tab (badge slug, boleh banyak; kosong = semua produk).
   private final java.util.Set<String> slugMine = new java.util.HashSet<>(), slugOther = new java.util.HashSet<>(),
         slugOpen = new java.util.HashSet<>();
   private static final int SORT_OPEN_DISTANCE = 0;
   private static final int SORT_OPEN_GALON = 1;
   private static final int SORT_OPEN_AGE = 2;
   private int sortOpenMode = 0;
   private List<DeliveryPlanner.Trip> strategyTrips;
   private ValueAnimator strategyBlink;
   private boolean strategyCollapsed = false;
   /** Kartu Strategi Pengiriman disembunyikan SELURUHNYA lewat menu overflow — beda dari
    *  strategyCollapsed (hanya melipat isinya). Lihat SettingsDao#isDeliveryStrategyHidden. */
   private boolean strategyHidden = false;
   /** 🧭 Kartu strategi bisa digeser: halaman 0 = Strategi Pengiriman, 1..N = Trip Khusus dari server. */
   private int strategyPage = 0;
   private final List<JSONObject> tripPages = new ArrayList<>();
   private boolean tripsLoading = false;
   private final LinkedHashSet<Long> runningIds = new LinkedHashSet<>();
   private double myLat = (double)0.0F;
   private double myLng = (double)0.0F;

   // ---------------------------------------------------------------- Guided Delivery (perangkat kurir "terpandu")
   // Perangkat yang ditandai admin (SyncSettings#isGuidedDeliveryDevice) memakai layar ini sebagai
   // satu-satunya layar kerja penuh: tab & toolbar disembunyikan, tiap order berikutnya langsung
   // ditampilkan sebagai panel Preview INLINE (bukan dialog) di guidedPanel, dan kurir tak pernah
   // perlu menavigasi menu sendiri. Lihat enterGuidedChrome/renderGuidedInline/updateGuidedPanel.
   private boolean guidedMode = false;
   private boolean guidedDoneScreenShown = false;
   private android.widget.FrameLayout guidedMapBox;
   private ValueAnimator guidedMapBorderAnim;
   private TextView guidedElapsedBadge;
   private LinearLayout guidedPanel;
   private LinearLayout guidedProductBadges;
   private View guidedProductBadgesScroll;
   /** Badge rincian produk order TERPILIH di bar mode-pilih (di atas "N dipilih"). */
   private LinearLayout selBadges;
   private View selBadgesScroll;
   private WebView guidedInlineWebView;
   private LiveDeviceOverlay guidedInlineOverlay;
   private AlertDialog guidedRitOffer;
   // Auto Zoom peta guided: ON secara default; tombol di pojok peta membiarkan kurir mematikannya
   // sementara (mis. sedang menelusuri peta manual) tanpa kehilangan setelan tiap kali preview ganti.
   private boolean guidedAutoZoom = true;
   // Id order terakhir yang preview-nya sudah ditampilkan otomatis — updateGuidedPanel() memakainya
   // supaya tidak menampilkan ulang preview yang SAMA berulang kali tiap tick.
   private long guidedPreviewShownForId = -1L;
   // Order yang kurir pilih "Ikutkan Rit Selanjutnya" saat ditawari refill rit berjalan (lihat
   // showGuidedRitRefillOffer) — jangan ditawarkan LAGI selama sesi rit ini masih jalan.
   private final LinkedHashSet<Long> guidedRitRefillDeclinedIds = new LinkedHashSet<>();
   private final Runnable guidedRitRefillTicker = new Runnable() {
      public void run() {
         DeliveryQueueActivity.this.checkGuidedRitRefill();
         if (!DeliveryQueueActivity.this.isFinishing() && !DeliveryQueueActivity.this.isDestroyed()) {
            VersionUpdater.maybePrompt(DeliveryQueueActivity.this);
            VersionUpdater.maybePromptBlocked(DeliveryQueueActivity.this);
         }
         DeliveryQueueActivity.this.tick.postDelayed(this, 30000L);
      }
   };
   // Menata ulang "Antrian Saya" berdasar rute (buildStrategyOrderedList) tiap menit — posisi kurir
   // berubah sambil jalan, jadi urutan rute optimal ikut berubah walau tak ada order baru/selesai.
   private final Runnable strategyResortTicker = new Runnable() {
      public void run() {
         DeliveryQueueActivity.this.reapplyStrategyOrder();
         DeliveryQueueActivity.this.tick.postDelayed(this, 60000L);
      }
   };

   // ---------------------------------------------------------------- Preview: kompas live "arah ke tujuan"
   // Kode permintaan izin lokasi khusus panel kompas (7404) - TERPISAH dari myLat/myLng di atas,
   // yang cuma sekali-ambil saat antrean dimuat (loadData/LocationService.lastLocation) untuk
   // mengurutkan kartu; kompas butuh posisi yang benar-benar BERJALAN selama dialog Preview terbuka.
   private static final int REQ_COMPASS_LOCATION = 7404;
   private SensorManager compassSensorManager;
   private Sensor compassRotationSensor;
   private Sensor compassAccelSensor, compassMagnetSensor;
   private LocationManager compassLocationManager;
   private LocationListener compassLocationListener;
   private SensorEventListener compassSensorListener;
   private float compassAzimuthDeg = Float.NaN;      // heading perangkat, 0=Utara
   private double compassMyLat = Double.NaN, compassMyLng = Double.NaN;
   private double compassDestLat, compassDestLng;    // tujuan aktif selama panel Preview terbuka
   private CompassArrowView compassArrow;
   private TextView tvCompassLabel, tvCompassDist;
   // Peta Preview yang sedang terbuka (null bila tak ada) - dituju oleh pushMyPosToMap() setiap
   // fix GPS baru datang, supaya pin "Posisi Anda" & zoom peta ikut hidup bersama kompas.
   private WebView previewMapWebView;

   private final Handler tick = new Handler(Looper.getMainLooper());
   private final Runnable ticker = new Runnable() {
      public void run() {
         DeliveryQueueActivity.this.adapter.refreshTimers();
         if (DeliveryQueueActivity.this.openDispatchAdapter != null) {
            DeliveryQueueActivity.this.openDispatchAdapter.refreshTimers();
         }

         if (DeliveryQueueActivity.this.otherDevicesAdapter != null) {
            DeliveryQueueActivity.this.otherDevicesAdapter.refreshTimers();
         }

         DeliveryQueueActivity.this.maybeRaiseLateAlarm();
         DeliveryQueueActivity.this.updateGuidedElapsedBadge();
         DeliveryQueueActivity.this.tick.postDelayed(this, 1000L);
      }
   };
   /** Daftar "Antrean Saya" / "Antrean Lain" di dialog antrean penuh mode terpandu — adapter
    *  TERPISAH dari adapter utama; dipegang lemah hanya supaya {@link #destAreaRefresh} ikut
    *  mem-bind ulang kartunya selama dialog itu terbuka. */
   private java.lang.ref.WeakReference<QueueAdapter> guidedMyQueueAdapterRef;
   private java.lang.ref.WeakReference<OtherQueueRowAdapter> guidedOtherRowsAdapterRef;
   /** Wilayah lokasi non-utama baru tiba (DestAreaCache) → bind ulang baris 📍 semua kartu antrean
    *  yang sedang tampil supaya "Kedai" menjadi "Kedai · Desa, Kec. X" tanpa menunggu refresh. */
   private final Runnable destAreaRefresh = () -> {
      if (this.isFinishing() || this.isDestroyed()) return;
      if (this.adapter != null) this.adapter.notifyDataSetChanged();
      if (this.openDispatchAdapter != null) this.openDispatchAdapter.notifyDataSetChanged();
      if (this.otherDevicesAdapter != null) this.otherDevicesAdapter.notifyDataSetChanged();
      QueueAdapter gm = this.guidedMyQueueAdapterRef != null ? this.guidedMyQueueAdapterRef.get() : null;
      if (gm != null) gm.notifyDataSetChanged();
      OtherQueueRowAdapter go = this.guidedOtherRowsAdapterRef != null ? this.guidedOtherRowsAdapterRef.get() : null;
      if (go != null && go.hostDialog != null && go.hostDialog.isShowing()) {
         // Baris "Antrean Lain" menyimpan teks 📍 jadi → susun ulang (urutan Umur/Jarak dipertahankan).
         go.setData(this.buildOtherQueueRows(), go.byDistance);
      }
   };
   private final BroadcastReceiver syncedReceiver = new BroadcastReceiver() {
      public void onReceive(Context context, Intent intent) {
         if (DeliveryQueueActivity.this.applyGuidedToggle()) return;
         if (!DeliveryQueueActivity.this.selectionMode) {
            DeliveryQueueActivity.this.loadData();
         }
         DeliveryQueueActivity.this.loadTrips();
         if (DeliveryQueueActivity.this.awaitingNextRit) DeliveryQueueActivity.this.offerNextRit();

      }
   };
   private boolean contactPermAsked = false;
   private static final int REQ_PERM_CONTACTS = 7401;
   private long pendingProofTrxId = -1L;
   private boolean pendingProofRevoke = false;
   private String pendingProofPath;
   /** Foto bukti jadwal ulang (sudah dikompres) untuk dialog Jadwalkan Ulang yang sedang terbuka. */
   private String postponePhotoPath;
   /** File tujuan kamera yang sedang berjalan untuk foto jadwal ulang. */
   private String pendingPostponeCameraPath;
   private android.widget.ImageView postponePhotoThumb;
   private TextView postponePhotoStatus;
   private static final int REQ_PROOF_CAMERA = 7402;
   private static final int REQ_PERM_PROOF_CAMERA = 7403;
   private static final Pattern ITEM_LABEL_PATTERN;
   private static final int MAX_CHIPS = 4;
   private static final long QUEUE_WARN_MS = 3600000L;
   private static final long QUEUE_LATE_MS = 7200000L;
   /**
    * Batas umur "TERLAMBAT" dalam ms (setelan cabang delivery_max_age_minutes); 0 = fitur mati.
    * Dibaca SEKALI di onResume -- jangan menyentuh SettingsDao dari dalam ticker 1 detik. Static
    * karena pembacanya (sortByDistance, isLate, bindElapsedBadge) memang statik dan layar antrean
    * hanya pernah ada satu di layar.
    */
   private static long lateMs = 0L;
   /** Cermin lokal setelan revoke_credit_late_delivery -- HANYA untuk kalimat peringatan di HP. */
   private boolean revokeLateCredit = false;
   /** Id order yang sudah pernah membunyikan alarm; alarm hanya SEKALI untuk tiap order. Urutan
    *  penyisipan dipertahankan supaya pemangkasan di {@link #rememberAlarmedLate} membuang yang
    *  paling lama, bukan sembarang id. */
   private final Set<Long> alarmedLateIds = new LinkedHashSet();
   /** Kunci LOKAL (bukan SHAREABLE_KEYS) tempat {@link #alarmedLateIds} bertahan antar sesi —
    *  tanpa ini, menutup lalu membuka layar antrean membunyikan alarm yang sama lagi. */
   private static final String KEY_ALARMED_LATE_IDS = "late_alarm_shown_ids";
   /** Batas id tersimpan. Order lama tak pernah kembali terlambat, jadi yang tertua boleh dibuang;
    *  batasnya cuma menjaga baris setelan ini tak tumbuh selamanya. */
   private static final int ALARMED_LATE_IDS_MAX = 300;
   private long lateAlarmSnoozeUntilMs = 0L;
   private AlertDialog lateDialog;
   private static final long LATE_ALARM_COOLDOWN_MS = 600000L;
   private static final long LATE_ALARM_SNOOZE_MS = 900000L;

   public DeliveryQueueActivity() {
      super();
   }

   protected void onCreate(Bundle savedInstanceState) {
      super.onCreate(savedInstanceState);
      this.setContentView(layout.activity_delivery_queue);
      DestAreaCache.init(this);
      // Hanya pada peluncuran pertama: rotasi/rekreasi tak boleh membuka ulang popup pesanan.
      if (savedInstanceState == null && this.getIntent() != null) {
         this.pendingFocusTrxId = this.getIntent().getLongExtra(EXTRA_FOCUS_TRX_ID, -1L);
         this.pendingCompleteTrxId = this.getIntent().getLongExtra(EXTRA_COMPLETE_TRX_ID, -1L);
      }
      if (savedInstanceState != null) {
         this.pendingProofTrxId = savedInstanceState.getLong("proof_trx_id", -1L);
         this.pendingProofRevoke = savedInstanceState.getBoolean("proof_revoke", false);
         this.pendingProofPath = savedInstanceState.getString("proof_path");
         this.wizardActive = savedInstanceState.getBoolean("wizard_active", false);
         this.wizardCompleteId = savedInstanceState.getLong("wizard_complete_id", -1L);
      }

      Toolbar toolbar = (Toolbar)this.findViewById(id.toolbar);
      this.setSupportActionBar(toolbar);
      toolbar.setNavigationOnClickListener((v) -> this.finish());
      this.dao = new TransactionDao(DatabaseHelper.getInstance(this));
      this.customerDao = new CustomerDao(DatabaseHelper.getInstance(this));
      // Guided Delivery diputuskan SEBELUM view lain dibaca: enterGuidedChrome menyembunyikan
      // toolbar & mengunci layar tetap menyala, jadi harus terjadi sedini mungkin di onCreate.
      this.guidedMode = this.syncCfg().isGuidedDeliveryDevice();
      if (this.guidedMode) {
         this.enterGuidedChrome();
         VersionUpdater.checkAndPrompt(this);
      }
      this.restoreAlarmedLate();
      this.productDao = new ProductDao(DatabaseHelper.getInstance(this));
      this.rv = (RecyclerView)this.findViewById(id.rv);
      this.tvEmpty = (TextView)this.findViewById(id.tvEmpty);
      this.tvSummary = (TextView)this.findViewById(id.tvSummary);
      this.guidedPanel = (LinearLayout)this.findViewById(id.guidedPanel);
      this.guidedProductBadgesScroll = this.findViewById(id.guidedProductBadgesScroll);
      this.guidedProductBadges = (LinearLayout)this.findViewById(id.guidedProductBadges);
      this.adapter = new QueueAdapter();
      this.rv.setLayoutManager(new LinearLayoutManager(this));
      this.rv.setHasFixedSize(true);
      this.rv.setAdapter(this.adapter);
      this.etSearchMine = (TextInputEditText)this.findViewById(id.etSearchMine);
      if (this.guidedMode) {
         // Kotak cari & keterangan tab tak berguna di layar terpandu (kurir tak pernah melihat
         // daftar mentah) — disembunyikan, digantikan fabGuidedQueue untuk yang sesekali perlu
         // menengok seluruh antrean.
         View searchParent = (View)this.etSearchMine.getParent();
         if (searchParent != null) searchParent.setVisibility(View.GONE);
         View caption = this.findViewById(id.tvSummaryCaption);
         if (caption != null) caption.setVisibility(View.GONE);
         View fab = this.findViewById(id.fabGuidedQueue);
         if (fab != null) {
            fab.setVisibility(View.VISIBLE);
            fab.setOnClickListener((v) -> this.showGuidedFullQueueDialog());
         }
      }
      this.etSearchMine.addTextChangedListener(new TextWatcher() {
         public void beforeTextChanged(CharSequence s, int a, int b, int c) {
         }

         public void onTextChanged(CharSequence s, int a, int b, int c) {
            DeliveryQueueActivity.this.searchMineQuery = s == null ? "" : s.toString().trim();
            DeliveryQueueActivity.this.adapter.applyFilter();
         }

         public void afterTextChanged(Editable s) {
         }
      });
      android.widget.CheckBox cbKomplainMine = this.findViewById(id.cbKomplainMine);
      this.addKindFilter(cbKomplainMine, (k) -> {
         this.kindMine = k;
         this.adapter.applyFilter();
      }, this.slugMine, () -> this.adapter.applyFilter());
      if (cbKomplainMine != null) {
         cbKomplainMine.setOnCheckedChangeListener((b, checked) -> {
            this.complaintOnlyMine = checked;
            this.adapter.applyFilter();
         });
      }
      (new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(0, 0) {
         public boolean isLongPressDragEnabled() {
            return DeliveryQueueActivity.this.isRunning();
         }

         public int getMovementFlags(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder vh) {
            return !DeliveryQueueActivity.this.isRunning() ? 0 : makeMovementFlags(3, 0);
         }

         public boolean onMove(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder vh, @NonNull RecyclerView.ViewHolder target) {
            int from = vh.getBindingAdapterPosition();
            int to = target.getBindingAdapterPosition();
            if (from != -1 && to != -1) {
               DeliveryQueueActivity.this.moveRunningStop(from, to);
               DeliveryQueueActivity.this.adapter.notifyItemMoved(from, to);
               return true;
            } else {
               return false;
            }
         }

         public void onSwiped(@NonNull RecyclerView.ViewHolder vh, int direction) {
         }
      })).attachToRecyclerView(this.rv);
      View fabNavigasiRit = this.findViewById(id.fabNavigasiRit);
      if (fabNavigasiRit != null) {
         fabNavigasiRit.setOnClickListener((v) -> this.startWizardNav());
      }

      this.barRute = this.findViewById(id.barRute);
      this.tvSelCount = (TextView)this.findViewById(id.tvSelCount);
      this.selBadgesScroll = this.findViewById(id.selBadgesScroll);
      this.selBadges = (LinearLayout)this.findViewById(id.selBadges);
      this.btnJalankanBanyak = (MaterialButton)this.findViewById(id.btnJalankanBanyak);
      this.btnJalankanBanyak.setOnClickListener((v) -> this.runSelectedTogether());
      this.btnSelAksi = (MaterialButton)this.findViewById(id.btnSelAksi);
      if (this.btnSelAksi != null) {
         this.btnSelAksi.setOnClickListener((v) -> this.showSelectionActions(v));
      }
      this.cbSelectAll = (CheckBox)this.findViewById(id.cbSelectAll);
      this.cbSelectAll.setOnCheckedChangeListener((b, checked) -> {
         if (!this.syncingSelectAll) {
            this.selectedIds.clear();
            if (checked) {
               for(Transaction t : this.adapter.data) {
                  this.selectedIds.add(t.getId());
               }
            }

            this.updateSelectionUi();
            this.adapter.notifyDataSetChanged();
         }
      });
      this.tabs = (TabLayout)this.findViewById(id.tabs);
      this.tab1Container = this.findViewById(id.tab1Container);
      this.tab2Container = this.findViewById(id.tab2Container);
      this.tab3Container = this.findViewById(id.tab3Container);
      this.tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
         public void onTabSelected(TabLayout.Tab tab) {
            DeliveryQueueActivity.this.switchTab(tab.getPosition());
         }

         public void onTabUnselected(TabLayout.Tab tab) {
         }

         public void onTabReselected(TabLayout.Tab tab) {
         }
      });
      this.rvOtherDevices = (RecyclerView)this.findViewById(id.rvOtherDevices);
      this.tvOtherDevicesEmpty = (TextView)this.findViewById(id.tvOtherDevicesEmpty);
      this.rvOtherDevices.setLayoutManager(new LinearLayoutManager(this));
      this.otherDevicesAdapter = new OtherDevicesAdapter();
      this.rvOtherDevices.setAdapter(this.otherDevicesAdapter);
      this.etSearchOther = (TextInputEditText)this.findViewById(id.etSearchOther);
      this.etSearchOther.addTextChangedListener(new TextWatcher() {
         public void beforeTextChanged(CharSequence s, int a, int b, int c) {
         }

         public void onTextChanged(CharSequence s, int a, int b, int c) {
            DeliveryQueueActivity.this.searchOtherQuery = s == null ? "" : s.toString().trim();
            DeliveryQueueActivity.this.otherDevicesAdapter.applyFilterSort();
         }

         public void afterTextChanged(Editable s) {
         }
      });
      MaterialButtonToggleGroup sortGroupOther = (MaterialButtonToggleGroup)this.findViewById(id.sortGroupOther);
      sortGroupOther.check(this.sortOtherMode == 1 ? id.sortOtherGalon : (this.sortOtherMode == 2 ? id.sortOtherAge : id.sortOtherJarak));
      sortGroupOther.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
         if (isChecked) {
            int newMode = checkedId == id.sortOtherGalon ? 1 : (checkedId == id.sortOtherAge ? 2 : 0);
            if (newMode != this.sortOtherMode) {
               this.sortOtherMode = newMode;
               this.otherDevicesAdapter.applyFilterSort();
            }
         }
      });
      this.tvOtherSummary = (TextView)this.findViewById(id.tvOtherSummary);
      this.barClaimOther = this.findViewById(id.barClaimOther);
      this.tvClaimOtherCount = (TextView)this.findViewById(id.tvClaimOtherCount);
      this.findViewById(id.btnClaimOtherCancel).setOnClickListener((v) -> this.exitClaimSelect(false));
      this.findViewById(id.btnClaimOtherAksi).setOnClickListener((v) -> this.showOtherSelectionActions(v));
      this.findViewById(id.btnClaimOtherGo).setOnClickListener((v) -> this.confirmBulkClaimOther());

      this.rvOpenDispatch = (RecyclerView)this.findViewById(id.rvOpenDispatch);
      this.tvOpenDispatchEmpty = (TextView)this.findViewById(id.tvOpenDispatchEmpty);
      this.rvOpenDispatch.setLayoutManager(new LinearLayoutManager(this));
      this.openDispatchAdapter = new OpenDispatchAdapter();
      this.rvOpenDispatch.setAdapter(this.openDispatchAdapter);
      this.tvOpenSummary = (TextView)this.findViewById(id.tvOpenSummary);
      this.barClaimOpen = this.findViewById(id.barClaimOpen);
      this.tvClaimOpenCount = (TextView)this.findViewById(id.tvClaimOpenCount);
      this.findViewById(id.btnClaimOpenCancel).setOnClickListener((v) -> this.exitClaimSelect(true));
      this.cbClaimAllOther = this.addSelectAllBox(this.tvClaimOtherCount, false);
      this.cbClaimAllOpen = this.addSelectAllBox(this.tvClaimOpenCount, true);
      this.findViewById(id.btnClaimOpenGo).setOnClickListener((v) -> this.confirmBulkClaimOpen());

      this.etSearchOpen = (TextInputEditText)this.findViewById(id.etSearchOpen);
      this.etSearchOpen.addTextChangedListener(new TextWatcher() {
         public void beforeTextChanged(CharSequence s, int a, int b, int c) {
         }

         public void onTextChanged(CharSequence s, int a, int b, int c) {
            DeliveryQueueActivity.this.searchOpenQuery = s == null ? "" : s.toString().trim();
            DeliveryQueueActivity.this.openDispatchAdapter.applyFilterSort();
         }

         public void afterTextChanged(Editable s) {
         }
      });
      android.widget.CheckBox cbKomplainOpen = this.findViewById(id.cbKomplainOpen);
      this.addKindFilter(cbKomplainOpen, (k) -> {
         this.kindOpen = k;
         this.openDispatchAdapter.applyFilterSort();
      }, this.slugOpen, () -> this.openDispatchAdapter.applyFilterSort());
      View searchOtherBox = this.findViewById(id.etSearchOther);
      while (searchOtherBox != null && !(searchOtherBox instanceof com.google.android.material.textfield.TextInputLayout)
            && searchOtherBox.getParent() instanceof View) {
         searchOtherBox = (View) searchOtherBox.getParent();
      }
      // Tab Perangkat Lain: baris filter yang SAMA dengan tab lain — "😠 Komplain saja" dibuat di sini
      // (tab ini tak punya checkbox di layout) lalu ikut dipindah ke baris filter oleh addKindFilter.
      android.widget.CheckBox cbKomplainOther = null;
      if (searchOtherBox != null && searchOtherBox.getParent() instanceof android.view.ViewGroup) {
         android.view.ViewGroup op = (android.view.ViewGroup) searchOtherBox.getParent();
         cbKomplainOther = new com.google.android.material.checkbox.MaterialCheckBox(this);
         cbKomplainOther.setText("😠 Komplain saja");
         cbKomplainOther.setTextSize(11f);
         cbKomplainOther.setMinWidth(0);
         cbKomplainOther.setMinimumWidth(0);
         cbKomplainOther.setOnCheckedChangeListener((b, checked) -> {
            this.complaintOnlyOther = checked;
            this.otherDevicesAdapter.applyFilterSort();
         });
         op.addView(cbKomplainOther, op.indexOfChild(searchOtherBox) + 1);
      }
      this.addKindFilter(cbKomplainOther != null ? cbKomplainOther : searchOtherBox, (k) -> {
         this.kindOther = k;
         this.otherDevicesAdapter.applyFilterSort();
      }, this.slugOther, () -> this.otherDevicesAdapter.applyFilterSort());
      if (cbKomplainOpen != null) {
         cbKomplainOpen.setOnCheckedChangeListener((b, checked) -> {
            this.complaintOnlyOpen = checked;
            this.openDispatchAdapter.applyFilterSort();
         });
      }
      MaterialButtonToggleGroup sortGroupOpen = (MaterialButtonToggleGroup)this.findViewById(id.sortGroupOpen);
      sortGroupOpen.check(this.sortOpenMode == 1 ? id.sortOpenGalon : (this.sortOpenMode == 2 ? id.sortOpenAge : id.sortOpenJarak));
      sortGroupOpen.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
         if (isChecked) {
            int newMode = checkedId == id.sortOpenGalon ? 1 : (checkedId == id.sortOpenAge ? 2 : 0);
            if (newMode != this.sortOpenMode) {
               this.sortOpenMode = newMode;
               this.openDispatchAdapter.applyFilterSort();
            }
         }
      });
      SettingsDao sdao = new SettingsDao(DatabaseHelper.getInstance(this));
      this.strategyCollapsed = sdao.isDeliveryStrategyCollapsed();
      this.strategyHidden = sdao.isDeliveryStrategyHidden();
      this.runningIds.clear();
      this.runningIds.addAll(sdao.getDeliveryRunningTrxIds());
      View strategyHeader = this.findViewById(id.strategyHeader);
      if (strategyHeader != null) {
         strategyHeader.setOnClickListener((v) -> this.toggleStrategyCollapsed());
      }

      View strategyAll = this.findViewById(id.tvStrategyAll);
      if (strategyAll != null) {
         strategyAll.setOnClickListener((v) -> {
            if (this.strategyPage > 0) this.showFullTripKhusus();
            else this.showFullStrategy();
         });
      }

      View btnJalankanRit = this.findViewById(id.btnJalankanRit);
      if (btnJalankanRit != null) {
         btnJalankanRit.setOnClickListener((v) -> {
            if (this.strategyPage > 0) this.confirmStartTripKhusus();
            else this.runStrategyTrip();
         });
      }

      View btnRefreshRit = this.findViewById(id.btnRefreshRit);
      if (btnRefreshRit != null) {
         btnRefreshRit.setOnClickListener((v) -> this.refreshRit(v, null));
      }
      this.setupStrategySwipe();

   }

   // ------------------------------------------------------------------ Refresh RIT (rute terpendek)

   /** Fix GPS sesegar ini dianggap "posisi sekarang" tanpa menunggu fix baru. */
   private static final long RIT_FIX_FRESH_MS = 2 * 60 * 1000L;
   /** Batas tunggu fix GPS baru sebelum tetap mengirim (server lalu memakai ping terakhir / cabang). */
   private static final long RIT_FIX_TIMEOUT_MS = 8000L;

   /**
    * 🔄 Refresh RIT: ambil posisi HP sekarang → server menyusun ulang SELURUH antrean jadi urutan antar
    * terpendek menurut jarak TEMPUH (OSRM), termasuk balik ke cabang tiap rit, lalu menulisnya sebagai
    * urutan (delivery_seq). Urutan baru tiba lewat sinkron → kartu Strategi & daftar ikut berubah.
    */
   /** 🔄 di header saat rit berjalan: susun ulang HANYA pemberhentian rit ini dari posisi sekarang. */
   private void refreshRunRoute(View btn) {
      List<String> uuids = new ArrayList<>();
      for (Transaction t : this.runStops()) {
         String u = this.dao.getSyncUuidById(t.getId());
         if (u != null && !u.isEmpty()) uuids.add(u);
      }
      if (uuids.size() < 2) {
         Toast.makeText(this, uuids.isEmpty() ? "Order rit ini belum tersinkron ke server." : "Rit ini hanya satu pemberhentian.", 0).show();
         return;
      }
      this.refreshRit(btn, uuids);
   }

   /** @param onlyUuids null = seluruh antrean (Refresh RIT); isi = hanya pemberhentian rit berjalan. */
   private void refreshRit(View btn, @Nullable List<String> onlyUuids) {
      SyncSettings cfg = this.syncCfg();
      if (!cfg.isEnrolled()) {
         Toast.makeText(this, "Perangkat belum terhubung ke server.", 1).show();
         return;
      }
      btn.setEnabled(false);
      Toast.makeText(this, "Menghitung rute terpendek dari posisi Anda…", 0).show();
      this.currentFixForRit((loc) -> this.sendRefreshRit(btn, cfg, loc, onlyUuids));
   }

   /** Posisi sekarang: fix terakhir yang masih segar, else tunggu satu fix baru (maks 8 dtk), else null. */
   @SuppressLint("MissingPermission")
   private void currentFixForRit(java.util.function.Consumer<Location> done) {
      boolean granted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == 0
              || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == 0;
      LocationManager lm = (LocationManager) this.getSystemService(LOCATION_SERVICE);
      if (!granted || lm == null) {
         done.accept(null);
         return;
      }
      Location best = null;
      for (String p : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER}) {
         try {
            Location l = lm.getLastKnownLocation(p);
            if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
         } catch (Exception ignored) {}
      }
      if (best != null && System.currentTimeMillis() - best.getTime() <= RIT_FIX_FRESH_MS) {
         done.accept(best);
         return;
      }
      final Location stale = best;
      final boolean[] finished = {false};
      final Handler h = new Handler(Looper.getMainLooper());
      final LocationListener[] holder = new LocationListener[1];
      holder[0] = new LocationListener() {
         @Override public void onLocationChanged(@NonNull Location location) {
            if (finished[0]) return;
            finished[0] = true;
            try { lm.removeUpdates(this); } catch (Exception ignored) {}
            done.accept(location);
         }
         @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
         @Override public void onProviderEnabled(@NonNull String provider) {}
         @Override public void onProviderDisabled(@NonNull String provider) {}
      };
      try {
         if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, holder[0], Looper.getMainLooper());
         }
         if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 0L, 0f, holder[0], Looper.getMainLooper());
         }
      } catch (Exception ignored) {}
      h.postDelayed(() -> {
         if (finished[0]) return;
         finished[0] = true;
         try { lm.removeUpdates(holder[0]); } catch (Exception ignored) {}
         // Fix lama (≤ 30 mnt) masih lebih baik daripada titik cabang.
         done.accept(stale != null && System.currentTimeMillis() - stale.getTime() <= 30 * 60 * 1000L ? stale : null);
      }, RIT_FIX_TIMEOUT_MS);
   }

   private void sendRefreshRit(View btn, SyncSettings cfg, @Nullable Location loc, @Nullable List<String> onlyUuids) {
      (new Thread(() -> {
         String okMsg = null;
         String errMsg = null;
         try {
            JSONObject body = new JSONObject();
            if (loc != null) {
               body.put("lat", loc.getLatitude());
               body.put("lng", loc.getLongitude());
            }
            if (onlyUuids != null) body.put("uuids", new JSONArray(onlyUuids));
            JSONObject r = (new SyncApi(cfg)).optimizeRoute(body);
            okMsg = r.optString("message", "Rute disusun ulang.");
         } catch (SyncApi.SyncException se) {
            try { errMsg = (new JSONObject(se.body)).optString("message", null); } catch (Exception ignored) {}
            if (errMsg == null) errMsg = "Gagal menyusun rute (kode " + se.code + ").";
         } catch (Exception e) {
            errMsg = "Gagal menyusun rute — periksa koneksi internet.";
         }
         final String fOk = okMsg;
         final String fErr = errMsg;
         this.runOnUiThread(() -> {
            if (this.isFinishing() || this.isDestroyed()) return;
            btn.setEnabled(true);
            if (fOk != null) {
               // Pesan (jumlah rit, km, hemat berapa) cukup panjang untuk toast → dialog singkat.
               (new AlertDialog.Builder(this)).setTitle("🔄 Rit Diperbarui").setMessage(fOk)
                       .setPositiveButton("OK", (DialogInterface.OnClickListener) null).show();
               // Urutan baru (delivery_seq) tiba lewat pull → syncedReceiver memuat ulang daftar.
               SyncScheduler.syncNow(this.getApplicationContext());
            } else {
               Toast.makeText(this, fErr, 1).show();
            }
         });
      })).start();
   }

   private void switchTab(int idx) {
      if (this.selectionMode) {
         this.exitSelectionMode();
      }

      this.activeTab = idx;
      this.tab1Container.setVisibility(idx == 0 ? 0 : 8);
      this.tab2Container.setVisibility(idx == 1 ? 0 : 8);
      this.tab3Container.setVisibility(idx == 2 ? 0 : 8);
      if (idx == 1) {
         this.loadOtherDevices();
      }

      this.invalidateOptionsMenu();
   }

   private void toggleStrategyCollapsed() {
      this.strategyCollapsed = !this.strategyCollapsed;
      (new SettingsDao(DatabaseHelper.getInstance(this))).setDeliveryStrategyCollapsed(this.strategyCollapsed);
      this.applyStrategyCollapsed();
   }

   /** Menu overflow "Sembunyikan/Tampilkan Strategi Pengiriman" — kartu HILANG SELURUHNYA (judul
    *  ikut), beda dari toggleStrategyCollapsed yang cuma melipat isinya. Re-render langsung dari
    *  daftar antrean yang sudah dimuat, bukan query DB baru — kartu ini murni derivasi tampilan. */
   private void toggleStrategyHidden() {
      this.strategyHidden = !this.strategyHidden;
      (new SettingsDao(DatabaseHelper.getInstance(this))).setDeliveryStrategyHidden(this.strategyHidden);
      Toast.makeText(this, this.strategyHidden ? "Strategi Pengiriman disembunyikan" : "Strategi Pengiriman ditampilkan", 0).show();
      if (this.adapter != null) {
         this.renderStrategy(this.adapter.data);
      }
   }

   private void applyStrategyCollapsed() {
      View body = this.findViewById(id.strategyBody);
      if (body != null) {
         body.setVisibility(this.strategyCollapsed ? 8 : 0);
      }

      TextView chev = (TextView)this.findViewById(id.tvStrategyChevron);
      if (chev != null) {
         chev.setText(this.strategyCollapsed ? "▸" : "▾");
      }

   }

   public boolean onCreateOptionsMenu(Menu menu) {
      // Tak ada menu overflow di layar terpandu — semua aksi kurir sudah ada di panel/tombol.
      if (this.guidedMode) return false;
      this.getMenuInflater().inflate(R.menu.menu_delivery_queue, menu);
      return super.onCreateOptionsMenu(menu);
   }

   public boolean onPrepareOptionsMenu(Menu menu) {
      MenuItem it = menu.findItem(id.action_report_obstacle);
      if (it != null) {
         it.setVisible(this.activeTab == 0);
      }

      MenuItem outside = menu.findItem(id.action_release_outside_wilayah);
      if (outside != null) {
         outside.setVisible(this.activeTab == 0);
      }

      MenuItem takeWil = menu.findItem(id.action_take_wilayah);
      if (takeWil != null) {
         takeWil.setVisible(this.activeTab == 0);
      }

      MenuItem toggleStrategy = menu.findItem(id.action_toggle_strategy);
      if (toggleStrategy != null) {
         toggleStrategy.setVisible(false);
      }

      return super.onPrepareOptionsMenu(menu);
   }

   public boolean onOptionsItemSelected(MenuItem item) {
      if (item.getItemId() == id.action_view_other_device) {
         this.pickOtherDeviceThenView();
         return true;
      } else if (item.getItemId() == id.action_delivery_map) {
         this.startActivity(new Intent(this, DeliveryMapActivity.class));
         return true;
      } else if (item.getItemId() == id.action_report_obstacle) {
         this.openObstacleReport();
         return true;
      } else if (item.getItemId() == id.action_delivery_history) {
         this.startActivity(new Intent(this, DeliveryHistoryActivity.class));
         return true;
      } else if (item.getItemId() == id.action_release_outside_wilayah) {
         this.releaseOutsideWilayah();
         return true;
      } else if (item.getItemId() == id.action_take_wilayah) {
         this.showTakeWilayahDialog();
         return true;
      } else if (item.getItemId() == id.action_pending_queue) {
         this.showTertundaQueueDialog();
         return true;
      } else if (item.getItemId() == id.action_toggle_strategy) {
         this.toggleStrategyHidden();
         return true;
      } else {
         return super.onOptionsItemSelected(item);
      }
   }

   /**
    * 📥 Ambil Alih Sesuai Wilayah: daftar wilayah cabang (NAMA + perangkat penugasan + jumlah order di
    * perangkat lain / Pesanan Terbuka); kurir mencentang satu atau BANYAK wilayah lalu semua ordernya
    * diambil alih ke Antrean Saya (server: DeliveryWilayahTake). Penjualan default dicentang, Ambil
    * Galon Saja tidak — sama dengan dialog ▶.
    */
   /** Dialog Ambil Antrean dibuka dari "➕ Tambah ke Rit": hasilnya (termasuk Antrian Saya yang belum ikut rit)
    *  langsung dimasukkan ke rit berjalan. */
   private boolean takeForRun = false;

   private void showTakeWilayahDialog() {
      SyncSettings cfg = this.syncCfg();
      if (!cfg.isEnrolled()) {
         Toast.makeText(this, "Perangkat belum terhubung ke server.", Toast.LENGTH_LONG).show();
         return;
      }
      if (!cfg.isDeliveryDevice()) {
         Toast.makeText(this, "Hanya perangkat delivery yang bisa mengambil alih order.", Toast.LENGTH_LONG).show();
         return;
      }
      android.app.ProgressDialog wait = new android.app.ProgressDialog(this);
      wait.setMessage("Memuat wilayah…");
      wait.setCancelable(false);
      wait.show();
      (new Thread(() -> {
         JSONArray zones = null;
         String err = null;
         try {
            zones = (new SyncApi(cfg)).deliveryWilayah().optJSONArray("wilayah");
         } catch (SyncApi.SyncException se) {
            err = se.serverMessage();
         } catch (Exception e) {
            err = "Gagal memuat wilayah — periksa koneksi internet.";
         }
         final JSONArray fz = zones;
         final String fe = err;
         this.runOnUiThread(() -> {
            try { wait.dismiss(); } catch (Exception ignored) {}
            if (this.isFinishing() || this.isDestroyed()) return;
            if (fz == null) {
               Toast.makeText(this, fe != null && !fe.isEmpty() ? fe : "Gagal memuat wilayah.", Toast.LENGTH_LONG).show();
               return;
            }
            this.renderTakeQueueDialog(fz);
         });
      })).start();
   }

   /**
    * 📥 Ambil Antrean — dua opsi: (1) SESUAI WILAYAH SAYA (area primer + sekunder) + "ambil berapa galon";
    * (2) CUSTOM: jarak perjalanan darat dari posisi HP (default 2 km) dan/atau wilayah perangkat terpilih
    * (dipaksa walau bukan wilayah saya, boleh banyak) + "ambil berapa galon". Server: DeliveryTakeQueue.
    */
   private void renderTakeQueueDialog(JSONArray zones) {
      int pad = this.dp(20f);
      LinearLayout box = new LinearLayout(this);
      box.setOrientation(LinearLayout.VERTICAL);
      box.setPadding(pad, this.dp(8f), pad, 0);
      final android.widget.RadioButton rbMine = new android.widget.RadioButton(this);
      rbMine.setText("🧭 Sesuai wilayah saya (area utama + tambahan)");
      rbMine.setId(View.generateViewId());
      final android.widget.RadioButton rbCustom = new android.widget.RadioButton(this);
      rbCustom.setText("⚙️ Custom (jarak / wilayah perangkat)");
      rbCustom.setId(View.generateViewId());
      android.widget.RadioGroup rg = new android.widget.RadioGroup(this);
      final android.widget.RadioButton rbOpen = new android.widget.RadioButton(this);
      rbOpen.setText("📢 Pesanan Terbuka");
      rbOpen.setId(View.generateViewId());
      rg.addView(rbMine);
      rg.addView(rbCustom);
      rg.addView(rbOpen);
      final android.widget.RadioButton rbOldest = new android.widget.RadioButton(this);
      rbOldest.setText("⏳ Umur Tertua (semua wilayah + Pesanan Terbuka)");
      rbOldest.setId(View.generateViewId());
      rg.addView(rbOldest);
      box.addView(rg);
      // Umur Tertua: zona yang disapu — bawaan SEMUA tercentang (Pesanan Terbuka selalu ikut).
      final LinearLayout oldest = new LinearLayout(this);
      oldest.setOrientation(LinearLayout.VERTICAL);
      oldest.setPadding(this.dp(16f), 0, 0, 0);
      oldest.setVisibility(View.GONE);
      final List<android.widget.CheckBox> oldBoxes = new ArrayList<>();
      final List<Integer> oldIdx = new ArrayList<>();
      for (int i = 0; i < zones.length(); i++) {
         JSONObject z = zones.optJSONObject(i);
         if (z == null) continue;
         String dev = strJson(z, "device_name");
         android.widget.CheckBox cb = new android.widget.CheckBox(this);
         cb.setText("🧭 " + safe(z.optString("label")) + " · 📱 " + (dev != null && !dev.isEmpty() ? dev : "tanpa perangkat"));
         cb.setChecked(true);
         oldest.addView(cb);
         oldBoxes.add(cb);
         oldIdx.add(z.optInt("index", i));
      }
      box.addView(oldest);

      final LinearLayout custom = new LinearLayout(this);
      custom.setOrientation(LinearLayout.VERTICAL);
      custom.setPadding(this.dp(16f), 0, 0, 0);
      TextView lblKm = new TextView(this);
      lblKm.setText("Jarak perjalanan darat dari posisi saya (km, kosongkan = tanpa batas):");
      custom.addView(lblKm);
      final EditText etKm = new EditText(this);
      etKm.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
      etKm.setText("2");
      custom.addView(etKm);
      final List<android.widget.CheckBox> zoneBoxes = new ArrayList<>();
      final List<Integer> zoneIdx = new ArrayList<>();
      if (zones.length() > 0) {
         TextView lblZ = new TextView(this);
         lblZ.setText("Wilayah perangkat (dipaksa ambil walau bukan wilayah saya):");
         lblZ.setPadding(0, this.dp(8f), 0, 0);
         custom.addView(lblZ);
      }
      for (int i = 0; i < zones.length(); i++) {
         JSONObject z = zones.optJSONObject(i);
         if (z == null) continue;
         String dev = strJson(z, "device_name");
         android.widget.CheckBox cb = new android.widget.CheckBox(this);
         cb.setText("🧭 " + safe(z.optString("label")) + " · 📱 " + (dev != null && !dev.isEmpty() ? dev : "tanpa perangkat")
               + " · " + (z.optInt("other") + z.optInt("open")) + " order (" + z.optInt("galon") + " gal)");
         custom.addView(cb);
         zoneBoxes.add(cb);
         zoneIdx.add(z.optInt("index", i));
      }
      box.addView(custom);

      TextView lblGal = new TextView(this);
      lblGal.setText("Ambil berapa galon? (kosongkan = semua)");
      lblGal.setPadding(0, this.dp(10f), 0, 0);
      box.addView(lblGal);
      final EditText etGal = new EditText(this);
      etGal.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
      int maxLoad = this.syncCfg().getMaxLoad();
      if (maxLoad > 0) etGal.setText(String.valueOf(maxLoad));
      box.addView(etGal);
      final android.widget.CheckBox cbSales = new android.widget.CheckBox(this);
      cbSales.setText("🛒 Penjualan");
      cbSales.setChecked(true);
      final android.widget.CheckBox cbPickup = new android.widget.CheckBox(this);
      cbPickup.setText("↩ Ambil Galon Saja");
      box.addView(cbSales);
      // Produk (slug) yang mau diambil — bawaan SEMUA terpilih; hanya berlaku untuk penjualan.
      final java.util.Set<String> takeSlugs = new java.util.HashSet<>();
      final java.util.Set<String> allSlugs = new java.util.HashSet<>();
      android.widget.HorizontalScrollView slugRow = this.buildSlugChipRow(takeSlugs, () -> { }, true);
      if (slugRow != null) {
         allSlugs.addAll(takeSlugs);
         box.addView(slugRow);
         cbSales.setOnCheckedChangeListener((b, on) -> slugRow.setVisibility(on ? View.VISIBLE : View.GONE));
      }
      box.addView(cbPickup);

      rg.setOnCheckedChangeListener((g, id) -> {
         custom.setVisibility(id == rbCustom.getId() ? View.VISIBLE : View.GONE);
         oldest.setVisibility(id == rbOldest.getId() ? View.VISIBLE : View.GONE);
      });
      rbMine.setChecked(true);
      custom.setVisibility(View.GONE);

      ScrollView sv = new ScrollView(this);
      sv.addView(box);
      final boolean forRun = this.takeForRun && this.isRunning();
      this.takeForRun = false;
      if (forRun) {
         // Sisa muatan rit berjalan sebagai batas galon bawaan.
         int left = maxLoad > 0 ? Math.max(0, maxLoad - this.runLoadGalon()) : 0;
         etGal.setText(left > 0 ? String.valueOf(left) : "");
         rbMine.setText("🧭 Sesuai wilayah saya + Antrian Saya yang belum ikut rit");
      }
      AlertDialog dlg = (new AlertDialog.Builder(this)).setTitle(forRun ? "➕ Tambah ke Rit Berjalan" : "📥 Ambil Antrean").setView(sv)
            .setPositiveButton("Ambil", (DialogInterface.OnClickListener) null)
            .setNegativeButton("Batal", (DialogInterface.OnClickListener) null).create();
      dlg.setOnShowListener(di -> dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
         if (!cbSales.isChecked() && !cbPickup.isChecked()) {
            Toast.makeText(this, "Pilih minimal satu jenis.", Toast.LENGTH_SHORT).show();
            return;
         }
         try {
            JSONObject body = new JSONObject();
            boolean isCustom = rbCustom.isChecked();
            body.put("mode", isCustom ? "custom" : (rbOpen.isChecked() ? "open" : (rbOldest.isChecked() ? "oldest" : "mine")));
            if (rbOldest.isChecked()) {
               JSONArray oz = new JSONArray();
               for (int i = 0; i < oldBoxes.size(); i++) if (oldBoxes.get(i).isChecked()) oz.put(oldIdx.get(i));
               if (!oldBoxes.isEmpty() && oz.length() == 0) {
                  Toast.makeText(this, "Centang minimal satu wilayah.", Toast.LENGTH_SHORT).show();
                  return;
               }
               if (oz.length() < oldBoxes.size()) body.put("zones", oz);
            }
            if (cbSales.isChecked() && takeSlugs.isEmpty() && !allSlugs.isEmpty()) {
               Toast.makeText(this, "Pilih minimal satu produk.", Toast.LENGTH_SHORT).show();
               return;
            }
            if (takeSlugs.size() < allSlugs.size()) body.put("slugs", new JSONArray(takeSlugs));
            String g = etGal.getText().toString().trim();
            if (!g.isEmpty()) body.put("max_galon", Integer.parseInt(g));
            body.put("include_sales", cbSales.isChecked());
            body.put("include_pickup", cbPickup.isChecked());
            if (forRun) body.put("include_mine", true);
            body.put("_for_run", forRun);
            double km = 0;
            if (isCustom) {
               JSONArray zs = new JSONArray();
               for (int i = 0; i < zoneBoxes.size(); i++) if (zoneBoxes.get(i).isChecked()) zs.put(zoneIdx.get(i));
               body.put("zones", zs);
               String k = etKm.getText().toString().trim().replace(',', '.');
               km = k.isEmpty() ? 0 : Double.parseDouble(k);
               if (km > 0) body.put("max_km", km);
               if (km <= 0 && zs.length() == 0) {
                  Toast.makeText(this, "Isi jarak atau pilih minimal satu wilayah.", Toast.LENGTH_SHORT).show();
                  return;
               }
            }
            dlg.dismiss();
            if (km > 0) {
               Toast.makeText(this, "Mengambil lokasi HP…", Toast.LENGTH_SHORT).show();
               LocationService.lastLocation(this, (loc) -> {
                  if (loc == null) {
                     Toast.makeText(this, "Lokasi HP belum didapat — nyalakan GPS lalu coba lagi.", Toast.LENGTH_LONG).show();
                     return;
                  }
                  try {
                     body.put("lat", loc.getLatitude());
                     body.put("lng", loc.getLongitude());
                  } catch (Exception ignored) {
                  }
                  this.runTakeQueue(body);
               });
            } else {
               this.runTakeQueue(body);
            }
         } catch (Exception e) {
            Toast.makeText(this, "Isian tidak valid.", Toast.LENGTH_SHORT).show();
         }
      }));
      dlg.show();
   }

   /** Total galon (non ambil-saja) di rit yang sedang berjalan. */
   private int runLoadGalon() {
      int g = 0;
      for (Transaction t : this.runStops()) if (t != null && !DeliveryPlanner.isPickupOnly(t)) g += Math.max(0, t.getJumlahGalon());
      return g;
   }

   private void runTakeQueue(JSONObject body) {
      final boolean forRun = body.optBoolean("_for_run", false);
      body.remove("_for_run");
      SyncSettings cfg = this.syncCfg();
      android.app.ProgressDialog wait = new android.app.ProgressDialog(this);
      wait.setMessage("Mengambil antrean…");
      wait.setCancelable(false);
      wait.show();
      (new Thread(() -> {
         String msg;
         boolean ok = false;
         List<String> uuids = new ArrayList<>();
         try {
            JSONObject r = (new SyncApi(cfg)).takeQueue(body);
            ok = true;
            msg = r.optString("message", "Antrean diambil.");
            JSONArray us = r.optJSONArray("uuids");
            for (int i = 0; us != null && i < us.length(); i++) if (!us.optString(i).isEmpty()) uuids.add(us.optString(i));
         } catch (SyncApi.SyncException se) {
            String m = se.serverMessage();
            msg = m != null && !m.isEmpty() ? m : "Gagal mengambil antrean (kode " + se.code + ").";
         } catch (Exception e) {
            msg = "Gagal mengambil antrean — periksa koneksi internet.";
         }
         final String fm = msg;
         final boolean fok = ok;
         this.runOnUiThread(() -> {
            try { wait.dismiss(); } catch (Exception ignored) {}
            if (this.isFinishing() || this.isDestroyed()) return;
            Toast.makeText(this, fm, Toast.LENGTH_LONG).show();
            // Tambah ke Rit: order diserap ke rit berjalan begitu barisnya ada di HP (pola pending-uuid).
            if (fok && forRun && !uuids.isEmpty()) {
               (new SettingsDao(DatabaseHelper.getInstance(this))).addDeliveryRunPendingUuids(uuids);
            }
            if (fok) com.crowja.damiupos.sync.SyncScheduler.syncNow(this.getApplicationContext());
            this.loadData();
            this.loadOtherDevices();
            this.loadTrips();
         });
      })).start();
   }

   private void renderTakeWilayahDialog(JSONArray zones) {
      int pad = this.dp(20f);
      LinearLayout box = new LinearLayout(this);
      box.setOrientation(LinearLayout.VERTICAL);
      box.setPadding(pad, this.dp(8f), pad, 0);
      TextView hint = new TextView(this);
      hint.setText("Centang satu atau beberapa wilayah. Order wilayah itu di perangkat lain & Pesanan Terbuka diambil alih ke Antrean Saya.");
      hint.setTextSize(12f);
      box.addView(hint);
      final List<android.widget.CheckBox> boxes = new ArrayList<>();
      final List<Integer> indexes = new ArrayList<>();
      for (int i = 0; i < zones.length(); i++) {
         JSONObject z = zones.optJSONObject(i);
         if (z == null) continue;
         int other = z.optInt("other"), open = z.optInt("open"), mine = z.optInt("mine");
         String dev = strJson(z, "device_name");
         StringBuilder sb = new StringBuilder();
         sb.append("🧭 ").append(safe(z.optString("label"))).append(NL_BREAK);
         sb.append("📱 ").append(dev != null && !dev.isEmpty() ? dev : "tanpa penugasan perangkat").append(" · ");
         sb.append(other + open).append(" bisa diambil (").append(other).append(" HP lain, ").append(open).append(" terbuka");
         sb.append(", ").append(z.optInt("galon")).append(" gal) · ").append(mine).append(" sudah milik saya");
         android.widget.CheckBox cb = new android.widget.CheckBox(this);
         cb.setText(sb.toString());
         cb.setEnabled(other + open > 0);
         box.addView(cb);
         boxes.add(cb);
         indexes.add(z.optInt("index", i));
      }
      TextView kindLbl = new TextView(this);
      kindLbl.setText("Jenis order:");
      kindLbl.setPadding(0, this.dp(10f), 0, 0);
      box.addView(kindLbl);
      final android.widget.CheckBox cbSales = new android.widget.CheckBox(this);
      cbSales.setText("🛒 Penjualan");
      cbSales.setChecked(true);
      final android.widget.CheckBox cbPickup = new android.widget.CheckBox(this);
      cbPickup.setText("↩ Ambil Galon Saja");
      box.addView(cbSales);
      box.addView(cbPickup);
      ScrollView sv = new ScrollView(this);
      sv.addView(box);
      AlertDialog dlg = (new AlertDialog.Builder(this)).setTitle("📥 Ambil Alih Sesuai Wilayah").setView(sv)
            .setPositiveButton("Ambil Alih", (DialogInterface.OnClickListener) null)
            .setNegativeButton("Batal", (DialogInterface.OnClickListener) null).create();
      dlg.setOnShowListener(di -> dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
         JSONArray picked = new JSONArray();
         for (int i = 0; i < boxes.size(); i++) if (boxes.get(i).isChecked()) picked.put(indexes.get(i));
         if (picked.length() == 0) {
            Toast.makeText(this, "Pilih minimal satu wilayah.", Toast.LENGTH_SHORT).show();
            return;
         }
         if (!cbSales.isChecked() && !cbPickup.isChecked()) {
            Toast.makeText(this, "Pilih minimal satu jenis.", Toast.LENGTH_SHORT).show();
            return;
         }
         dlg.dismiss();
         this.runTakeWilayah(picked, cbSales.isChecked(), cbPickup.isChecked());
      }));
      dlg.show();
   }

   private static final String NL_BREAK = "\n";

   private void runTakeWilayah(JSONArray picked, boolean sales, boolean pickup) {
      SyncSettings cfg = this.syncCfg();
      android.app.ProgressDialog wait = new android.app.ProgressDialog(this);
      wait.setMessage("Mengambil alih order wilayah…");
      wait.setCancelable(false);
      wait.show();
      (new Thread(() -> {
         String msg;
         boolean ok = false;
         try {
            JSONObject r = (new SyncApi(cfg)).takeWilayah(picked, sales, pickup);
            ok = true;
            msg = r.optString("message", "Order diambil alih.");
         } catch (SyncApi.SyncException se) {
            String m = se.serverMessage();
            msg = m != null && !m.isEmpty() ? m : "Gagal mengambil alih (kode " + se.code + ").";
         } catch (Exception e) {
            msg = "Gagal mengambil alih — periksa koneksi internet.";
         }
         final String fm = msg;
         final boolean fok = ok;
         this.runOnUiThread(() -> {
            try { wait.dismiss(); } catch (Exception ignored) {}
            if (this.isFinishing() || this.isDestroyed()) return;
            Toast.makeText(this, fm, Toast.LENGTH_LONG).show();
            if (fok) com.crowja.damiupos.sync.SyncScheduler.syncNow(this.getApplicationContext());
            this.loadData();
            this.loadOtherDevices();
            this.loadTrips();
         });
      })).start();
   }

   private void openObstacleReport() {
      List<Transaction> scope = this.isRunning() ? this.runStops() : this.adapter.data;
      if (scope != null && !scope.isEmpty()) {
         long[] ids = new long[scope.size()];

         for(int i = 0; i < scope.size(); ++i) {
            ids[i] = ((Transaction)scope.get(i)).getId();
         }

         this.startActivity((new Intent(this, DeliveryObstacleActivity.class)).putExtra("trx_ids", ids));
      } else {
         Toast.makeText(this, "Antrean kosong — tak ada pengiriman yang terkendala.", 0).show();
      }
   }

   private void pickOtherDeviceThenView() {
      SyncSettings cfg = this.syncCfg();
      String myUuid = cfg.getDeviceUuid();
      List<String> uuids = new ArrayList();
      List<String> labels = new ArrayList();

      try {
         JSONArray arr = new JSONArray(cfg.getDeviceRoster());

         for(int i = 0; i < arr.length(); ++i) {
            JSONObject d = arr.optJSONObject(i);
            if (d != null) {
               String uuid = d.optString("uuid", "");
               String name = d.optString("name", "Perangkat");
               if (!uuid.isEmpty() && !uuid.equals(myUuid)) {
                  uuids.add(uuid);
                  labels.add(name);
               }
            }
         }
      } catch (Exception var10) {
      }

      if (uuids.isEmpty()) {
         Toast.makeText(this, "Tidak ada perangkat lain di cabang ini", 0).show();
      } else {
         String[] items = (String[])labels.toArray(new String[0]);
         (new AlertDialog.Builder(this)).setTitle("Lihat Antrian Perangkat Lain").setItems(items, (dx, which) -> {
            Intent i = (new Intent(this, OtherDeviceQueueActivity.class)).putExtra("device_uuid", (String)uuids.get(which)).putExtra("device_name", (String)labels.get(which));
            this.startActivity(i);
         }).setNegativeButton("Batal", (DialogInterface.OnClickListener)null).show();
      }
   }

   public void onBackPressed() {
      // Kurir terpandu tak boleh keluar dari layar ini lewat tombol Kembali — satu-satunya jalan
      // keluar yang sah adalah Selesai—Pulang (confirmGuidedLogout), yang absen pulang sekalian.
      if (this.guidedMode) {
         Toast.makeText(this, "Guided Delivery aktif — matikan dari dashboard untuk keluar.", 0).show();
         return;
      }
      // Mode pilih-banyak Tab 2/3 keluar lebih dulu, sama seperti mode-pilih Tab 1 — kalau tidak,
      // bar aksinya tertinggal di layar sementara pilihannya sudah tak terlihat lagi.
      if (this.claimSelectOther) {
         this.exitClaimSelect(false);
      } else if (this.claimSelectOpen) {
         this.exitClaimSelect(true);
      } else if (this.selectionMode) {
         this.exitSelectionMode();
      } else if (this.isRunning()) {
         this.confirmStopRun();
      } else {
         super.onBackPressed();
      }
   }

   private void enterSelectionMode() {
      if (this.isRunning()) {
         Toast.makeText(this, "Order sedang berjalan — tandai Selesai atau Kembali dulu", 0).show();
      } else {
         if (this.activeTab != 0) {
            this.tabs.selectTab(this.tabs.getTabAt(0));
         }

         if (this.adapter.getItemCount() == 0) {
            Toast.makeText(this, "Antrian kosong", 0).show();
         } else {
            this.selectionMode = true;
            this.selectedIds.clear();
            this.barRute.setVisibility(0);
            this.updateSelectionUi();
            this.adapter.notifyDataSetChanged();
            Toast.makeText(this, "Pilih order → Rute (peta) atau Jalankan (satu rit)", 0).show();
         }
      }
   }

   // ------------------------------------------------------------------ Aksi massal order terpilih

   /** Order Antrian Saya yang sedang dicentang (urutan tampilan). */
   private List<Transaction> selectedTrxs() {
      List<Transaction> out = new ArrayList<>();
      if (this.adapter == null || this.adapter.data == null) return out;
      for (Transaction t : this.adapter.data) {
         if (this.selectedIds.contains(t.getId())) out.add(t);
      }
      return out;
   }

   /**
    * 🔓 Lepas semua order Antrean Saya yang tujuannya masuk wilayah perangkat LAIN (Wilayah.deviceForCoord
    * pada koordinat tujuan, fallback koordinat pelanggan) → Pesanan Terbuka, lewat jalur Lepas massal yang
    * sama ({@link #runBulkQueueAction}). Order tanpa koordinat, di wilayah tanpa penugasan, atau yang
    * sedang dijalankan di rit TIDAK ikut — hanya yang pasti milik wilayah perangkat lain.
    */
   private void releaseOutsideWilayah() {
      SyncSettings cfg = this.syncCfg();
      String myUuid = cfg.getDeviceUuid();
      String center = cfg.getBranchCenter();
      String zones = cfg.getWilayahZones();
      if (Wilayah.parseCenter(center) == null || Wilayah.parseZones(zones) == null) {
         Toast.makeText(this, "Wilayah cabang belum diatur / belum tersinkron.", Toast.LENGTH_LONG).show();
         return;
      }
      List<Transaction> scope = this.adapter != null && this.adapter.data != null ? this.adapter.data : new ArrayList<>();
      java.util.Set<Long> running = new java.util.HashSet<>();
      if (this.isRunning()) for (Transaction t : this.runStops()) running.add(t.getId());
      java.util.Map<String, String> names = new java.util.HashMap<>();
      try {
         JSONArray arr = new JSONArray(cfg.getDeviceRoster());
         for (int i = 0; i < arr.length(); ++i) {
            JSONObject d = arr.optJSONObject(i);
            if (d != null) names.put(d.optString("uuid", ""), d.optString("name", "Perangkat"));
         }
      } catch (Exception ignored) {
      }
      List<Transaction> picked = new ArrayList<>();
      StringBuilder list = new StringBuilder();
      for (Transaction t : scope) {
         if (running.contains(t.getId()) || !"PENDING".equals(t.getDeliveryStatus())) continue;
         double lat = t.getDeliveryDestLat(), lng = t.getDeliveryDestLng();
         if (lat == 0 && lng == 0) { lat = t.getCustomerLat(); lng = t.getCustomerLng(); }
         if (lat == 0 && lng == 0) continue;
         String owner = Wilayah.deviceForCoord(center, zones, lat, lng);
         if (owner == null || owner.equals(myUuid)) continue;
         picked.add(t);
         String on = names.get(owner);
         list.append(picked.size()).append(". ").append(safe(t.getCustomerName()))
               .append(" → wilayah ").append(on != null ? on : "perangkat lain").append("\n");
      }
      if (picked.isEmpty()) {
         Toast.makeText(this, "Semua order di Antrean Saya sudah di wilayah perangkat ini.", Toast.LENGTH_LONG).show();
         return;
      }
      (new AlertDialog.Builder(this)).setTitle("Lepas " + picked.size() + " order luar wilayah?")
            .setMessage("Order berikut tujuannya di wilayah perangkat lain. Semua akan keluar dari antrian ini dan menjadi PESANAN TERBUKA — perangkat mana pun bisa mengklaimnya.\n\n" + list)
            .setPositiveButton("Lepas Semua", (d, w) -> this.runBulkQueueAction(picked, null, null))
            .setNegativeButton("Batal", (DialogInterface.OnClickListener) null).show();
   }

   private void showSelectionActions(View anchor) {
      List<Transaction> picked = this.selectedTrxs();
      if (picked.isEmpty()) return;
      PopupMenu menu = new PopupMenu(this, anchor);
      menu.getMenu().add(0, 1, 0, "\ud83d\udd13 Lepas " + picked.size() + " order (jadi Pesanan Terbuka)");
      menu.getMenu().add(0, 2, 1, "\ud83d\udcf2 Alihkan " + picked.size() + " order ke perangkat lain");
      menu.getMenu().add(0, 3, 2, "\ud83e\udded Alihkan " + picked.size() + " order sesuai wilayahnya");
      menu.setOnMenuItemClickListener((item) -> {
         if (item.getItemId() == 1) {
            (new AlertDialog.Builder(this)).setTitle("Lepas " + picked.size() + " order?")
                  .setMessage("Order terpilih keluar dari antrian perangkat ini dan menjadi PESANAN TERBUKA — perangkat mana pun bisa mengklaimnya.\n\n" + this.numberedNames(picked))
                  .setPositiveButton("Lepas Semua", (d, w) -> this.runBulkQueueAction(picked, null, null))
                  .setNegativeButton("Batal", (DialogInterface.OnClickListener) null).show();
            return true;
         }
         if (item.getItemId() == 2) {
            this.pickBulkRouteTarget(picked);
            return true;
         }
         if (item.getItemId() == 3) {
            // Server memilih tujuan per order = perangkat pemilik wilayahnya ("__wilayah__"); order di
            // wilayah sendiri / tanpa koordinat ditolak per order & dilaporkan.
            (new AlertDialog.Builder(this)).setTitle("Alihkan " + picked.size() + " order sesuai wilayah?")
                  .setMessage("Tiap order dikirim ke perangkat pemilik wilayahnya. Order yang memang di wilayah perangkat ini atau belum punya koordinat tetap di sini.\n\n" + this.numberedNames(picked))
                  .setPositiveButton("Alihkan", (d, w) -> this.runBulkQueueAction(picked, "__wilayah__", "wilayah masing-masing"))
                  .setNegativeButton("Batal", (DialogInterface.OnClickListener) null).show();
            return true;
         }
         return false;
      });
      menu.show();
   }

   /** Pilih perangkat tujuan dari roster (sama dengan "Kirim ke Perangkat Lain" satu order). */
   private void pickBulkRouteTarget(List<Transaction> picked) {
      SyncSettings cfg = this.syncCfg();
      String myUuid = cfg.getDeviceUuid();
      List<String[]> others = new ArrayList<>();
      try {
         JSONArray arr = new JSONArray(cfg.getDeviceRoster());
         for (int i = 0; i < arr.length(); ++i) {
            JSONObject d = arr.optJSONObject(i);
            if (d == null) continue;
            String uuid = d.optString("uuid", "");
            if (!uuid.isEmpty() && !uuid.equals(myUuid)) others.add(new String[]{uuid, d.optString("name", "Perangkat")});
         }
      } catch (Exception ignored) {
      }
      if (others.isEmpty()) {
         Toast.makeText(this, "Belum ada perangkat delivery lain di roster. Coba sinkron dulu.", 1).show();
         return;
      }
      String[] names = new String[others.size()];
      for (int i = 0; i < others.size(); ++i) names[i] = others.get(i)[1];
      (new AlertDialog.Builder(this)).setTitle("Alihkan " + picked.size() + " order ke perangkat mana?")
            .setItems(names, (dx, which) -> {
               String[] target = others.get(which);
               (new AlertDialog.Builder(this)).setTitle("Alihkan ke \"" + target[1] + "\"?")
                     .setMessage(picked.size() + " order dipindahkan ke antrian \"" + target[1] + "\". Kredit galon staf mengikuti perangkat tujuan.\n\n" + this.numberedNames(picked))
                     .setPositiveButton("Alihkan Semua", (d, w) -> this.runBulkQueueAction(picked, target[0], target[1]))
                     .setNegativeButton("Batal", (DialogInterface.OnClickListener) null).show();
            })
            .setNegativeButton("Batal", (DialogInterface.OnClickListener) null).show();
   }

   /**
    * Jalankan Lepas (targetUuid null) atau Alihkan (targetUuid terisi) untuk tiap order terpilih,
    * satu per satu lewat endpoint yang SAMA dengan aksi satu order (/api/delivery/open & /route) —
    * aturan & penolakan server tetap berlaku per order. Server membatasi 20 aksi/menit per perangkat:
    * begitu kena batas (429), sisanya dilaporkan belum diproses (tak dipaksa).
    */
   private void runBulkQueueAction(List<Transaction> picked, @Nullable String targetUuid, @Nullable String targetName) {
      SyncSettings cfg = this.syncCfg();
      if (!cfg.isEnrolled()) {
         Toast.makeText(this, "Perangkat belum terhubung ke server.", 1).show();
         return;
      }
      boolean route = targetUuid != null;
      android.app.ProgressDialog wait = new android.app.ProgressDialog(this);
      wait.setMessage((route ? "Mengalihkan " : "Melepas ") + picked.size() + " order…");
      wait.setCancelable(false);
      wait.show();
      (new Thread(() -> {
         int ok = 0;
         List<String> fails = new ArrayList<>();
         boolean limited = false;
         for (Transaction t : picked) {
            String name = safe(t.getCustomerName());
            if (limited) {
               fails.add(name + ": belum diproses (batas 20 aksi/menit)");
               continue;
            }
            String uuid = this.dao.getSyncUuidById(t.getId());
            if (uuid == null || uuid.isEmpty()) {
               fails.add(name + ": belum tersinkron ke server");
               continue;
            }
            try {
               JSONObject body = new JSONObject();
               body.put("transaction_uuid", uuid);
               if (route) {
                  body.put("target_device_uuid", targetUuid);
                  (new SyncApi(cfg)).routeDelivery(body);
               } else {
                  (new SyncApi(cfg)).openDispatch(body);
               }
               ok++;
            } catch (SyncApi.SyncException se) {
               if (se.code == 429) {
                  limited = true;
                  fails.add(name + ": belum diproses (batas 20 aksi/menit)");
               } else {
                  String m = extractRouteErrorMessage(se.body);
                  fails.add(name + ": " + (m != null ? m : "ditolak (kode " + se.code + ")"));
               }
            } catch (Exception e) {
               fails.add(name + ": gagal — periksa koneksi");
            }
         }
         final int okF = ok;
         this.runOnUiThread(() -> {
            if (this.isFinishing() || this.isDestroyed()) return;
            try { wait.dismiss(); } catch (Exception ignored) {}
            StringBuilder sb = new StringBuilder(okF + " order " + (route ? "dialihkan ke \"" + targetName + "\"" : "dilepas jadi Pesanan Terbuka") + ".");
            if (!fails.isEmpty()) {
               sb.append("\n\nTidak diproses (").append(fails.size()).append("):");
               for (String f : fails) sb.append("\n• ").append(f);
            }
            (new AlertDialog.Builder(this)).setTitle(route ? "Alihkan Massal" : "Lepas Massal").setMessage(sb.toString())
                  .setPositiveButton("OK", (DialogInterface.OnClickListener) null).show();
            this.exitSelectionMode();
            SyncScheduler.syncNow(this.getApplicationContext());
            this.loadData();
         });
      })).start();
   }

   private void exitSelectionMode() {
      this.selectionMode = false;
      this.selectedIds.clear();
      this.barRute.setVisibility(8);
      this.adapter.notifyDataSetChanged();
   }

   private void toggleSelected(Transaction t) {
      if (this.selectedIds.contains(t.getId())) {
         this.selectedIds.remove(t.getId());
      } else {
         this.selectedIds.add(t.getId());
      }

      this.updateSelectionUi();
   }

   private void runSelectedTogether() {
      List<Transaction> picked = new ArrayList();
      int skippedOpen = 0;

      for(Transaction t : this.adapter.data) {
         if (this.selectedIds.contains(t.getId())) {
            if (t.isOpenDispatch()) {
               ++skippedOpen;
            } else {
               picked.add(t);
            }
         }
      }

      if (picked.isEmpty()) {
         Toast.makeText(this, skippedOpen > 0 ? "Pesanan Terbuka harus diklaim satu per satu dulu." : "Belum ada order yang dipilih.", 0).show();
      } else if (!this.maybeSuggestFillRit(picked, skippedOpen)) {
         this.confirmRunSelected(picked, skippedOpen);
      }
   }

   private boolean maybeSuggestFillRit(List<Transaction> picked, int skippedOpen) {
      int maxLoad = this.syncCfg().getMaxLoad();
      if (maxLoad <= 0) {
         return false;
      } else {
         int load = 0;

         for(Transaction s : picked) {
            load += isPickupOnly(s) ? 0 : Math.max(0, s.getJumlahGalon());
         }

         if (load >= maxLoad) {
            return false;
         } else {
            List<Transaction> extra = this.extraToFillRit(picked, maxLoad, load);
            if (extra.isEmpty()) {
               return false;
            } else {
               int extraGalon = 0;

               for(Transaction s : extra) {
                  extraGalon += isPickupOnly(s) ? 0 : Math.max(0, s.getJumlahGalon());
               }

               List<Transaction> combined = new ArrayList(picked);
               combined.addAll(extra);
               AlertDialog dialog = (new AlertDialog.Builder(this)).setIcon(17301659).setTitle("Muatan rit masih longgar").setMessage(picked.size() + " order terpilih (" + load + " dari " + maxLoad + " galon). Masih ada antrean lain yang muat ditambahkan agar rit lebih efisien:\n\n" + this.numberedNames(extra) + "\n\n(+" + extraGalon + " galon)").setPositiveButton("Tambahkan " + extra.size() + " & Jalankan", (d, w) -> {
                  this.exitSelectionMode();
                  this.doStartRuns(combined);
               }).setNegativeButton("Tetap Jalankan (" + picked.size() + ")", (DialogInterface.OnClickListener)null).create();
               dialog.setOnShowListener((d) -> {
                  Button neg = dialog.getButton(-2);
                  int[] clicks = new int[]{0};
                  neg.setOnClickListener((v) -> {
                     if (++clicks[0] < 2) {
                        neg.setText("Ketuk sekali lagi");
                     } else {
                        dialog.dismiss();
                        this.confirmRunSelected(picked, skippedOpen);
                     }
                  });
               });
               dialog.show();
               return true;
            }
         }
      }
   }

   private List<Transaction> extraToFillRit(List<Transaction> picked, int maxLoad, int startLoad) {
      Set<Long> pickedIds = new HashSet();

      for(Transaction s : picked) {
         pickedIds.add(s.getId());
      }

      List<Transaction> ordered = new ArrayList();
      if (this.strategyTrips != null) {
         for(DeliveryPlanner.Trip trip : this.strategyTrips) {
            ordered.addAll(trip.stops);
         }
      }

      if (ordered.isEmpty()) {
         ordered = this.adapter.data;
      }

      List<Transaction> out = new ArrayList();
      int load = startLoad;

      for(Transaction o : ordered) {
         if (o != null && !pickedIds.contains(o.getId()) && !o.isOpenDispatch()) {
            int l = isPickupOnly(o) ? 0 : Math.max(0, o.getJumlahGalon());
            if (load + l <= maxLoad) {
               out.add(o);
               load += l;
            }
         }
      }

      return out;
   }

   private void confirmRunSelected(List<Transaction> picked, int skippedOpen) {
      List<Transaction> stops = picked;
      StringBuilder names = new StringBuilder();

      for(int i = 0; i < stops.size(); ++i) {
         Transaction s = (Transaction)stops.get(i);
         names.append('\n').append(i + 1).append(". ").append(safe(s.getCustomerName())).append(" (").append(s.getJumlahGalon()).append(" galon)");
      }

      (new AlertDialog.Builder(this)).setTitle("Jalankan " + stops.size() + " order sekaligus?").setView(this.stopsDialogView("Semua order ini ditandai SEDANG DIANTAR sebagai satu rit. Antar satu per satu, tandai ✓ Selesai pada tiap kartu." + (skippedOpen > 0 ? "\n\n" + skippedOpen + " Pesanan Terbuka dilewati (klaim dulu satu per satu)." : ""), stops, null)).setPositiveButton("Jalankan", (d, w) -> {
         this.exitSelectionMode();
         this.doStartRuns(stops);
      }).setNegativeButton("Batal", (DialogInterface.OnClickListener)null).show();
   }

   private void updateSelectionUi() {
      int n = this.selectedIds.size();
      this.tvSelCount.setText(n + " dipilih");
      this.fillProductBadges(this.selBadgesScroll, this.selBadges, this.selectedTrxs());
      if (this.btnSelAksi != null) {
         this.btnSelAksi.setEnabled(n > 0);
      }
      if (this.btnJalankanBanyak != null) {
         this.btnJalankanBanyak.setEnabled(n > 0);
         this.btnJalankanBanyak.setText(n > 0 ? "(" + n + ")" : "");
      }

      if (this.cbSelectAll != null) {
         int total = this.adapter.getItemCount();
         boolean all = total > 0 && n == total;
         if (this.cbSelectAll.isChecked() != all) {
            this.syncingSelectAll = true;
            this.cbSelectAll.setChecked(all);
            this.syncingSelectAll = false;
         }
      }

   }

   /** Dipanggil SEDINI mungkin di onCreate saat guidedMode — layar tetap menyala (kurir sering
    *  menaruh HP di dudukan motor tanpa disentuh lama) & status bar/navigation bar disembunyikan
    *  supaya panel Preview benar-benar penuh layar, tanpa toolbar. */
   private void enterGuidedChrome() {
      this.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
      this.getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                  | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                  | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN);
      View toolbar = this.findViewById(id.toolbar);
      if (toolbar != null) toolbar.setVisibility(View.GONE);
   }

   /** Lawan renderGuidedInline/showGuidedDoneScreen — melepas semua yang dipasang panel guided
    *  SEBELUMNYA (overlay peta, WebView, animasi border) sebelum panel diisi ulang, supaya tak ada
    *  WebView atau LiveDeviceOverlay yang bocor tiap kali order berjalan berikutnya berganti. */
   private void teardownGuidedInlinePreview() {
      if (this.guidedInlineOverlay != null) {
         this.guidedInlineOverlay.stop();
         this.guidedInlineOverlay = null;
      }
      if (this.guidedInlineWebView != null) {
         this.guidedInlineWebView.stopLoading();
         this.guidedInlineWebView.destroy();
         this.guidedInlineWebView = null;
      }
      this.previewMapWebView = null;
      this.guidedElapsedBadge = null;
      if (this.guidedMapBorderAnim != null) {
         this.guidedMapBorderAnim.cancel();
         this.guidedMapBorderAnim = null;
      }
      if (this.guidedMapBox != null) {
         this.guidedMapBox.setForeground(null);
         this.guidedMapBox = null;
      }
      this.stopCompass();
   }

   /** Dipanggil tiap detik dari {@link #ticker} saat guided — refresh badge nama+durasi di pojok
    *  peta, dan nyalakan border peta berkedip begitu order sudah TERLAMBAT (lihat bindElapsedBadge,
    *  yang menyimpan tingkat keterlambatan di tag view — 0=normal, 1=warn, 2=late). */
   private void updateGuidedElapsedBadge() {
      if (!this.guidedMode || this.guidedElapsedBadge == null) return;
      Transaction running = this.runningTransaction();
      if (running == null) return;
      bindElapsedBadge(this.guidedElapsedBadge, elapsedMillis(running.getDeliveryQueuedAt()));
      Object tag = this.guidedElapsedBadge.getTag(id.tvElapsed);
      this.applyGuidedMapBorderState(tag instanceof Integer ? (Integer) tag : 0);
   }

   private void applyGuidedMapBorderState(int level) {
      if (this.guidedMapBox == null) return;
      if (level < 2) {
         if (this.guidedMapBorderAnim != null) {
            this.guidedMapBorderAnim.cancel();
            this.guidedMapBorderAnim = null;
         }
         this.guidedMapBox.setForeground(null);
         return;
      }
      GradientDrawable border = (GradientDrawable) this.guidedMapBox.getForeground();
      if (border == null) {
         border = new GradientDrawable();
         border.setShape(GradientDrawable.RECTANGLE);
         border.setStroke(this.dp(5f), -2937041);
         this.guidedMapBox.setForeground(border);
      }
      if (this.guidedMapBorderAnim == null || !this.guidedMapBorderAnim.isStarted()) {
         final GradientDrawable borderFinal = border;
         ValueAnimator anim = ValueAnimator.ofInt(255, 60);
         anim.setDuration(250L);
         anim.setRepeatMode(ValueAnimator.REVERSE);
         anim.setRepeatCount(ValueAnimator.INFINITE);
         anim.addUpdateListener(com.crowja.damiupos.util.LowPowerBlink.stepped(3, (a) -> borderFinal.setAlpha((Integer) a.getAnimatedValue())));
         com.crowja.damiupos.util.LowPowerBlink.track(anim).start();
         this.guidedMapBorderAnim = anim;
      }
   }

   /**
    * Toggle 🧭 Guided Delivery berubah dari dashboard SAAT layar ini terbuka (guidedMode cuma dibaca
    * di onCreate). Aktif → recreate() supaya masuk mode terpandu; dimatikan → kembali ke beranda
    * (MainActivity sudah di-finish saat mengalihkan ke sini). true = layar ini sedang ditutup.
    */
   private boolean applyGuidedToggle() {
      if (this.isFinishing() || this.syncCfg().isGuidedDeliveryDevice() == this.guidedMode) return false;
      if (this.guidedMode) {
         this.startActivity(new Intent(this, MainActivity.class));
         this.finish();
      } else {
         this.recreate();
      }
      return true;
   }

   protected void onResume() {
      super.onResume();
      if (this.applyGuidedToggle()) return;
      SettingsDao lateCfg = new SettingsDao(DatabaseHelper.getInstance(this));
      lateMs = (long)lateCfg.getDeliveryMaxAgeMinutes() * 60000L;
      this.revokeLateCredit = lateCfg.isRevokeCreditLateEnabled();
      DestAreaCache.addListener(this.destAreaRefresh);
      com.crowja.damiupos.util.LowPowerBlink.resumeAll();
      this.loadData();
      this.openPendingFocus();
      this.openPendingComplete();
      this.loadOtherDevices();
      this.loadTrips();
      this.tick.postDelayed(this.ticker, 1000L);
      this.tick.postDelayed(this.strategyResortTicker, 60000L);
      this.tick.postDelayed(this.runWatchdog, 20000L);
      if (this.guidedMode) {
         this.tick.postDelayed(this.guidedRitRefillTicker, 30000L);
         VersionUpdater.maybePrompt(this);
         // Layar ini MENGGANTIKAN beranda di mode terpandu: jalankan juga kewajiban beranda (gerbang
         // versi, sinkron, service polling ±60 dtk) — tanpanya perintah dashboard, termasuk
         // mematikan Guided Delivery lagi, tak pernah sampai.
         com.crowja.damiupos.util.ForegroundDuties.onResume(this);
      }
      IntentFilter f = new IntentFilter("com.crowja.damiupos.action.SYNCED");
      if (VERSION.SDK_INT >= 33) {
         this.registerReceiver(this.syncedReceiver, f, 4);
      } else {
         this.registerReceiver(this.syncedReceiver, f);
      }

   }

   protected void onPause() {
      super.onPause();
      DestAreaCache.removeListener(this.destAreaRefresh);
      // Layar tak tampil → hentikan semua kedip (dulu terus berjalan di latar = HP panas).
      com.crowja.damiupos.util.LowPowerBlink.pauseAll();
      this.tick.removeCallbacks(this.ticker);
      this.tick.removeCallbacks(this.strategyResortTicker);
      this.tick.removeCallbacks(this.guidedRitRefillTicker);
      this.tick.removeCallbacks(this.runWatchdog);

      try {
         this.unregisterReceiver(this.syncedReceiver);
      } catch (Exception var2) {
      }

   }

   private void ensureContactsForMine(List<Transaction> mine) {
      if (mine != null && !mine.isEmpty()) {
         if (!WaContactEnsure.canWrite(this)) {
            if (!this.contactPermAsked) {
               this.contactPermAsked = true;
               ActivityCompat.requestPermissions(this, new String[]{"android.permission.READ_CONTACTS", "android.permission.WRITE_CONTACTS"}, 7401);
            }

         } else {
            List<String[]> people = new ArrayList();

            for(Transaction t : mine) {
               String name = t.getCustomerName();
               if (name == null || !name.trim().equalsIgnoreCase("Umum")) {
                  people.add(new String[]{name, t.getCustomerPhone()});
               }
            }

            WaContactEnsure.ensureAllAsync(this, people);
         }
      }
   }

   public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
      super.onRequestPermissionsResult(requestCode, permissions, grantResults);
      if (requestCode == 7401 && WaContactEnsure.canWrite(this)) {
         this.loadData();
      }

      if (requestCode == REQ_POSTPONE_CAMERA_PERM) {
         if (grantResults.length > 0 && grantResults[0] == 0) {
            this.launchPostponeCamera();
         } else {
            Toast.makeText(this, "Izin kamera ditolak — foto kendaraan tidak bisa diambil.", 1).show();
         }
      }

      if (requestCode == 7403) {
         long trxId = this.pendingProofTrxId;
         boolean revoke = this.pendingProofRevoke;
         if (trxId <= 0L) {
            return;
         }

         if (grantResults.length > 0 && grantResults[0] == 0) {
            this.launchProofCamera(trxId, revoke);
         } else {
            this.pendingProofTrxId = -1L;
            Toast.makeText(this, "Izin kamera diperlukan untuk foto bukti — order belum ditandai Selesai.", 1).show();
         }
      }

   }

   protected void onSaveInstanceState(@NonNull Bundle out) {
      super.onSaveInstanceState(out);
      out.putLong("proof_trx_id", this.pendingProofTrxId);
      out.putBoolean("proof_revoke", this.pendingProofRevoke);
      out.putString("proof_path", this.pendingProofPath);
      out.putBoolean("wizard_active", this.wizardActive);
      out.putLong("wizard_complete_id", this.wizardCompleteId);
   }

   private void loadData() {
      this.refreshProductIndex();
      List<Transaction> base = this.dao.getDeliveryQueue();
      this.applyFilteredLists(base);
      if (!base.isEmpty()) {
         LocationService.lastLocation(this, (loc) -> {
            if (loc != null) {
               this.myLat = loc.getLatitude();
               this.myLng = loc.getLongitude();
               // Urutan TETAP urutan web (DAO); GPS hanya mengisi label jarak di kartu.
               this.applyFilteredLists(base);
            }
         });
      }

   }

   private void applyFilteredLists(List<Transaction> base) {
      List<Transaction> mine = new ArrayList();
      List<Transaction> open = new ArrayList();

      for(Transaction t : base) {
         (t.isOpenDispatch() ? open : mine).add(t);
      }

      this.applyList(mine);
      this.applyOpenDispatchList(open);
      this.ensureContactsForMine(mine);
   }

   private void applyOpenDispatchList(List<Transaction> list) {
      if (this.openDispatchAdapter != null) {
         this.openDispatchAdapter.setData(list);
         this.setTabCount(2, this.openDispatchAdapter.rawCount());
      }
   }

   /** Cermin updateOpenEmptyState, untuk kolom cari di Antrean Saya (tab 0). */
   private void updateMineEmptyState() {
      if (this.tvEmpty != null && this.rv != null && this.adapter != null) {
         boolean empty = this.adapter.getItemCount() == 0;
         this.tvEmpty.setText(this.searchMineQuery.isEmpty() ? "Antrian delivery kosong 🎉" : "Tidak ditemukan");
         this.tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
         this.rv.setVisibility(empty ? View.GONE : View.VISIBLE);
      }
   }

   private void updateOpenEmptyState() {
      if (this.tvOpenDispatchEmpty != null && this.rvOpenDispatch != null && this.openDispatchAdapter != null) {
         boolean empty = this.openDispatchAdapter.getItemCount() == 0;
         this.tvOpenDispatchEmpty.setText(this.searchOpenQuery.isEmpty() ? "\ud83c\udfb2 Tidak ada Pesanan Terbuka saat ini" : "Tidak ditemukan");
         this.tvOpenDispatchEmpty.setVisibility(empty ? 0 : 8);
         this.rvOpenDispatch.setVisibility(empty ? 8 : 0);
      }
   }

   /** Total galon sebuah daftar order lokal (Antrian Saya / Pesanan Terbuka). */
   private static int totalGalonOf(List<Transaction> list) {
      int n = 0;
      if (list != null) {
         for (Transaction t : list) {
            n += Math.max(0, t.getJumlahGalon());
         }
      }
      return n;
   }

   /** " · 34 galon" — dikosongkan bila nol supaya tak menambah derau pada antrian kosong. */
   private static String galonSuffix(int galon) {
      return galon > 0 ? "  ·  " + galon + " galon" : "";
   }

   /**
    * Baris ringkasan Tab 2 & 3.
    *
    * <p>Angkanya sengaja mengikuti yang SEDANG TAMPIL, bukan seluruh data mentah — supaya kurir
    * bisa memverifikasinya dengan menghitung kartu di layar. Saat pencarian menyaring daftar,
    * pembaginya ikut ditulis ("8 dari 12 order") sehingga selisih dengan angka lencana tab, yang
    * memang menghitung data mentah, tidak terbaca sebagai ketidakcocokan.
    */
   private void setTabSummary(TextView tv, int shown, int raw, int galon) {
      if (tv == null) {
         return;
      }
      if (raw <= 0) {
         tv.setVisibility(8);
         return;
      }
      tv.setVisibility(0);
      String head = shown < raw ? (shown + " dari " + raw + " order") : (raw + " order");
      tv.setText(head + galonSuffix(galon));
   }

   private void setTabCount(int index, int count) {
      if (this.tabs != null) {
         TabLayout.Tab tab = this.tabs.getTabAt(index);
         if (tab != null) {
            if (count <= 0) {
               tab.removeBadge();
            } else {
               BadgeDrawable b = tab.getOrCreateBadge();
               b.setVisible(true);
               b.setNumber(count);
            }
         }
      }
   }

   private void loadOtherDevices() {
      SyncSettings cfg = this.syncCfg();
      if (!cfg.isEnrolled()) {
         this.tvOtherDevicesEmpty.setText("Perangkat belum terhubung ke server.");
         this.tvOtherDevicesEmpty.setVisibility(0);
         this.rvOtherDevices.setVisibility(8);
         this.setTabCount(1, 0);
      } else {
         (new Thread(() -> {
            JSONArray queue = null;
            boolean failed = false;

            try {
               JSONObject res = (new SyncApi(cfg)).devicesQueueAll();
               queue = res.optJSONArray("queue");
               JSONObject ts = res.optJSONObject("trip_slugs");
               if (ts != null) {
                  this.tripSlugs.clear();
                  for (java.util.Iterator<String> it = ts.keys(); it.hasNext(); ) {
                     String k = it.next();
                     String v = ts.optString(k, "");
                     if (!v.isEmpty()) this.tripSlugs.put(k, v);
                  }
               }
               JSONObject wm = res.optJSONObject("wilayah");
               if (wm != null) {
                  this.orderWilayah.clear();
                  for (java.util.Iterator<String> it = wm.keys(); it.hasNext(); ) {
                     String k = it.next();
                     this.orderWilayah.put(k, wm.optString(k, ""));
                  }
               }
               JSONArray wo = res.optJSONArray("wilayah_options");
               if (wo != null) {
                  List<String[]> opts = new ArrayList<>();
                  for (int i = 0; i < wo.length(); i++) {
                     JSONObject o = wo.optJSONObject(i);
                     if (o != null) opts.add(new String[]{o.optString("uuid"), o.optString("label")});
                  }
                  this.wilayahOptions = opts;
               }
               JSONArray to = res.optJSONArray("trip_options");
               if (to != null) {
                  List<String> opts = new ArrayList<>();
                  for (int i = 0; i < to.length(); i++) opts.add(to.optString(i));
                  this.tripOptions = opts;
               }
            } catch (Exception var6) {
               failed = true;
            }

            final JSONArray queueF = queue;
            final boolean failedF = failed;
            this.runOnUiThread(() -> {
               if (!this.isFinishing() && !this.isDestroyed()) {
                  this.otherDevicesAdapter.setData(queueF);
                  // Slug trip baru tiba → kartu Antrian Saya & Pesanan Terbuka ikut memasang badgenya.
                  if (this.adapter != null) this.adapter.notifyDataSetChanged();
                  if (this.openDispatchAdapter != null) this.openDispatchAdapter.notifyDataSetChanged();
                  this.setTabCount(1, this.otherDevicesAdapter.rawCount());
                  if (failedF && this.activeTab == 1) {
                     this.tvOtherDevicesEmpty.setText("Gagal memuat — periksa koneksi lalu coba lagi.");
                     this.tvOtherDevicesEmpty.setVisibility(0);
                     this.rvOtherDevices.setVisibility(8);
                  }

               }
            });
         })).start();
         LocationService.lastLocation(this, (loc) -> {
            if (loc != null && !this.isFinishing() && !this.isDestroyed()) {
               this.otherLat = loc.getLatitude();
               this.otherLng = loc.getLongitude();
               this.otherDevicesAdapter.applyFilterSort();
            }
         });
      }
   }

   /**
    * Sematkan CATATAN ORDER di kaki kartu antrian \u2014 dipakai KETIGA tab (Antrian Saya, Perangkat
    * Lain, Pesanan Terbuka) supaya catatan tampil sama persis di mana pun kartu itu muncul.
    *
    * <p>Kolom {@code catatan} dipakai ganda di aplikasi ini: catatan manusia SEKALIGUS penanda
    * pembukuan internal ("[PENCAIRAN KOMISI]", "[PROMOSI]", \u2026), dan order buatan WEB menyimpan
    * blob audit panjang (nomor HP, pembuat, salinan rincian struk) dengan catatan asli menempel di
    * ekornya. Menampilkannya mentah akan memenuhi kartu dengan teks yang bukan untuk dibaca kurir,
    * jadi disaring lewat {@link ReceiptActivity#customerNote} \u2014 SATU penyaring yang sama dengan
    * struk WA, supaya catatan yang dilihat kurir dan yang diterima pelanggan tak pernah beda.</p>
    *
    * <p>Tab 2 menerima catatan dari server ({@code note} pada {@code Reports::shapeQueueRow}) yang
    * SUDAH menyaring blob web di sisi sana; menyaringnya lagi di sini tidak merusak apa pun
    * (penyaring tak menemukan apa-apa lagi untuk dibuang) dan menjaga Tab 2 tetap aman kalau suatu
    * saat server mengirim catatan mentah.</p>
    *
    * <p>Kosong \u2192 View GONE, bukan teks kosong: kartu tanpa catatan harus setinggi sebelum fitur ini
    * ada, kalau tidak seluruh daftar jadi lebih renggang tanpa alasan.</p>
    */
   static void bindOrderNote(TextView tv, String rawCatatan) {
      if (tv != null) {
         String note = ReceiptActivity.customerNote(rawCatatan);
         if (note.isEmpty()) {
            tv.setVisibility(View.GONE);
            tv.setOnClickListener(null);
            tv.setClickable(false);
         } else {
            tv.setText("\ud83d\udcdd " + note);
            tv.setVisibility(View.VISIBLE);
            // Kartu memotong catatan panjang ("\u2026") \u2014 ketuk untuk membaca seluruhnya, per poin.
            tv.setOnClickListener((v) -> showOrderNoteDialog(v.getContext(), rawCatatan));
         }
      }
   }

   /**
    * Label lokasi kartu antrean. Desa/kecamatan pelanggan hanya milik KOORDINAT UTAMA-nya (hasil
    * geocode server); lokasi lain (Kantor, Warung, …) tak punya data wilayah. Order yang dikirim ke
    * lokasi LAIN (tujuan berjarak > 100 m dari lokasi utama) karena itu menampilkan nama lokasi
    * tujuannya, bukan wilayah lokasi utama yang keliru. Jarak di kartu sudah dihitung dari tujuan.
    *
    * <p>Pelanggan dengan LEBIH DARI SATU lokasi: nama lokasi tujuan (delivery_dest_name, atau lokasi
    * utama bila kosong) selalu diawalkan sebelum wilayahnya — "Kediaman · Pandeyan, Kec. Ngemplak"
    * (tujuan = lokasi utama, wilayah dari server) atau "Kedai · Kartasura, Kec. Kartasura" (lokasi
    * lain, wilayah dari {@link DestAreaCache}; "Kedai" saja selama wilayahnya belum tiba / tak ada
    * datanya). Pelanggan satu lokasi: wilayah saja.</p>
    *
    * <p>Tujuan milik RESELLER ("Reseller: Kediaman", dipilih reseller dari link pemesanannya) SELALU
    * diberi label "Dikirim ke lokasi reseller: …" walau dekat lokasi utama afiliasi — nama di kartu
    * tetap si afiliasi, jadi tanpa label ini kurir mengetuk pintu rumah yang salah.</p>
    */
   static String areaLabel(Customer c, double destLat, double destLng, String destName) {
      String dn = cleanLabel(destName);
      if (Transaction.isResellerDestName(dn)) {
         return Transaction.resellerDestLabel(dn);
      }
      String area = c != null ? cleanLabel(c.getAdminArea()) : "";
      boolean destGeo = destLat != 0.0 || destLng != 0.0;
      boolean primaryGeo = c != null && (c.getLatitude() != 0.0 || c.getLongitude() != 0.0);
      boolean elsewhere = destGeo && (!primaryGeo
            || haversineKm(c.getLatitude(), c.getLongitude(), destLat, destLng) > 0.1);
      // Pelanggan MULTI-LOKASI: nama lokasi tujuan (Kediaman/Kedai/…) diawalkan supaya kurir tahu
      // pintu MANA yang dituju, lalu wilayah lokasi ITU: milik server untuk lokasi UTAMA, dari
      // DestAreaCache untuk lokasi lain — tak pernah wilayah utama yang keliru untuk "Kedai".
      List<Customer.Location> locs = c != null ? c.getLocations() : null;
      if (locationCount(locs) > 1) {
         Customer.Location primary = c.getPrimaryLocation();
         String primaryName = primary != null ? cleanLabel(primary.name) : "";
         String locName = dn;
         if (locName.isEmpty()) {
            if (destGeo) {
               // Order lama tanpa nama tujuan: cocokkan koordinatnya ke salah satu lokasi (<= 100 m).
               for (Customer.Location l : locs) {
                  if (l != null && (l.lat != 0.0 || l.lng != 0.0)
                        && haversineKm(l.lat, l.lng, destLat, destLng) <= 0.1) {
                     locName = cleanLabel(l.name);
                     break;
                  }
               }
            } else {
               locName = primaryName;   // tanpa tujuan = diantar ke lokasi utama
            }
         }
         if (!locName.isEmpty()) {
            boolean atPrimary = destGeo ? !elsewhere : locName.equalsIgnoreCase(primaryName);
            String where = atPrimary ? area : "";
            if (where.isEmpty()) {
               // Lokasi LAIN (atau utama yang belum di-geocode server): wilayahnya dari
               // DestAreaCache — titik tujuan order, atau titik lokasi bernama itu bila order tak
               // membawa koordinat. Belum diketahui → nama lokasi saja dulu; kartu di-bind ulang
               // begitu wilayahnya tiba.
               double aLat = destGeo ? destLat : 0.0;
               double aLng = destGeo ? destLng : 0.0;
               if (!destGeo) {
                  Customer.Location byName = atPrimary ? primary : null;
                  if (byName == null) {
                     for (Customer.Location l : locs) {
                        if (l != null && locName.equalsIgnoreCase(cleanLabel(l.name))) {
                           byName = l;
                           break;
                        }
                     }
                  }
                  if (byName != null) {
                     aLat = byName.lat;
                     aLng = byName.lng;
                  }
               }
               String cached = DestAreaCache.lookup(aLat, aLng);
               if (cached != null) where = cached;
            }
            return where.isEmpty() ? locName : locName + " · " + where;
         }
      }
      if (elsewhere) {
         return "Kirim ke: " + (dn.isEmpty() ? "lokasi lain" : dn);
      }
      return area;
   }

   /**
    * Wilayah TITIK TUJUAN untuk label "Kirim Ke: … (wilayah)". admin_area pelanggan hanya milik
    * lokasi UTAMA — dulu dipakai untuk semua tujuan, sehingga order ke "Cabang Gagaksipat" tertulis
    * "(Klodran)". Tujuan di lokasi utama (≤ 100 m) → admin_area; lokasi lain → DestAreaCache; belum
    * diketahui → "" (tanpa wilayah lebih baik daripada wilayah yang salah).
    */
   static String destAreaFor(Customer c, double lat, double lng) {
      if (c == null) return "";
      String area = cleanLabel(c.getAdminArea());
      boolean primaryGeo = c.getLatitude() != 0.0 || c.getLongitude() != 0.0;
      if (lat == 0.0 && lng == 0.0) return area;
      if (primaryGeo && haversineKm(c.getLatitude(), c.getLongitude(), lat, lng) <= 0.1) return area;
      String cached = DestAreaCache.lookup(lat, lng);
      return cached != null ? cleanLabel(cached) : "";
   }

   static String areaLabel(Customer c, Transaction t) {
      return areaLabel(c, t.getDeliveryDestLat(), t.getDeliveryDestLng(), t.getDeliveryDestName());
   }

   /** Jumlah lokasi bernama pelanggan (entri null diabaikan). */
   private static int locationCount(List<Customer.Location> locs) {
      if (locs == null) return 0;
      int n = 0;
      for (Customer.Location l : locs) {
         if (l != null) n++;
      }
      return n;
   }

   /** Trim + literal "null" (jebakan org.json optString) → "". */
   private static String cleanLabel(String s) {
      if (s == null) return "";
      String v = s.trim();
      return "null".equalsIgnoreCase(v) ? "" : v;
   }

   /**
    * Wilayah ("Desa, Kec. X") titik lokasi NON-utama pelanggan multi-lokasi. Server hanya menyimpan
    * desa/kecamatan untuk KOORDINAT UTAMA (GeocodeCustomers) dan Customer.Location tak punya field
    * wilayah, jadi tanpa ini order ke "Kedai" hanya bisa berlabel "Kedai". HP bertanya ke
    * GET /api/geocode/reverse (App\Support\ReverseGeocode di server — sumber & format yang sama
    * dengan desa/kecamatan pelanggan) SEKALI per titik lalu menyimpannya permanen di
    * SharedPreferences. Kunci = koordinat dibulatkan 4 desimal (~11 m): lokasi yang digeser memicu
    * tanya ulang dengan sendirinya.
    *
    * <p>{@link #lookup} tak pernah memblok UI: belum diketahui → null + diantre di SATU thread latar
    * (jeda >= 1,1 dtk antar panggilan — hormat batas 1 req/dtk Nominatim di server). Begitu wilayah
    * baru tiba, listener terdaftar (layar antrean yang sedang tampil) dipanggil di main thread untuk
    * bind ulang kartunya. Gagal jaringan / belum terdaftar → coba lagi paling cepat 5 menit kemudian;
    * "tak ada data wilayah" (404) diingat 7 hari.</p>
    */
   static final class DestAreaCache {
      private static final String PREFS = "dest_area_cache";
      private static final long NEG_TTL_MS = 7L * 24L * 3600_000L;
      private static final long RETRY_MS = 5L * 60_000L;
      private static final long GAP_MS = 1100L;
      private static final java.util.concurrent.ConcurrentHashMap<String, String> MEM =
            new java.util.concurrent.ConcurrentHashMap<>();
      private static final java.util.concurrent.ConcurrentHashMap<String, Long> FAILED_AT =
            new java.util.concurrent.ConcurrentHashMap<>();
      private static final java.util.Set<String> INFLIGHT =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
      private static final java.util.concurrent.CopyOnWriteArraySet<Runnable> LISTENERS =
            new java.util.concurrent.CopyOnWriteArraySet<>();
      private static final java.util.concurrent.ExecutorService EXEC =
            java.util.concurrent.Executors.newSingleThreadExecutor();
      private static final Handler MAIN = new Handler(Looper.getMainLooper());
      private static final Runnable FIRE = () -> {
         for (Runnable r : LISTENERS) r.run();
      };
      private static volatile Context appCtx;
      /** Waktu panggilan server terakhir — hanya disentuh thread EXEC. */
      private static long lastCallAt;

      private DestAreaCache() { }

      /** Dipanggil onCreate layar antrean; memuat cache tersimpan ke memori di latar. */
      static void init(Context ctx) {
         if (appCtx != null || ctx == null) return;
         final Context app = ctx.getApplicationContext();
         appCtx = app;
         EXEC.execute(() -> {
            try {
               boolean any = false;
               for (java.util.Map.Entry<String, ?> e : prefs(app).getAll().entrySet()) {
                  Object v = e.getValue();
                  if (v instanceof String && ((String) v).startsWith("+")) {
                     String area = ((String) v).substring(1);
                     MEM.putIfAbsent(e.getKey(), area);
                     any |= !area.isEmpty();
                  }
               }
               if (any) fire();
            } catch (Exception ignored) {
            }
         });
      }

      static void addListener(Runnable r) {
         if (r != null) LISTENERS.add(r);
      }

      static void removeListener(Runnable r) {
         if (r != null) LISTENERS.remove(r);
      }

      /** Wilayah titik ini; "" = server tak punya data wilayahnya; null = belum diketahui (sedang
       *  / akan diambil di latar, listener dipanggil setibanya). */
      static String lookup(double lat, double lng) {
         if (lat == 0.0 && lng == 0.0) return "";
         final String k = String.format(Locale.US, "%.4f,%.4f", lat, lng);
         String v = MEM.get(k);
         if (v != null) return v;
         final Context ctx = appCtx;
         if (ctx == null) return null;
         Long failed = FAILED_AT.get(k);
         if (failed != null && System.currentTimeMillis() - failed < RETRY_MS) return null;
         if (INFLIGHT.add(k)) {
            try {
               EXEC.execute(() -> fetch(ctx, k, lat, lng));
            } catch (Exception e) {
               INFLIGHT.remove(k);
            }
         }
         return null;
      }

      private static android.content.SharedPreferences prefs(Context ctx) {
         return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
      }

      private static void fire() {
         MAIN.removeCallbacks(FIRE);
         MAIN.postDelayed(FIRE, 400L);
      }

      private static void fetch(Context ctx, String k, double lat, double lng) {
         boolean resolved = false;
         try {
            String known = MEM.get(k);   // init() bisa saja baru memuatnya
            if (known != null) {
               resolved = !known.isEmpty();
               return;
            }
            android.content.SharedPreferences sp = prefs(ctx);
            String stored = sp.getString(k, null);
            if (stored != null && stored.startsWith("+")) {
               String area = stored.substring(1);
               MEM.put(k, area);
               resolved = !area.isEmpty();
               return;
            }
            if (stored != null && stored.startsWith("-")) {
               long at = 0L;
               try {
                  at = Long.parseLong(stored.substring(1));
               } catch (NumberFormatException ignored) {
               }
               if (System.currentTimeMillis() - at < NEG_TTL_MS) {
                  MEM.put(k, "");
                  return;
               }
            }
            SyncSettings cfg = new SyncSettings(new SettingsDao(DatabaseHelper.getInstance(ctx)));
            okhttp3.HttpUrl base = cfg.isEnrolled()
                  ? okhttp3.HttpUrl.parse(cfg.getBaseUrl() + "/api/geocode/reverse") : null;
            if (base == null) {
               FAILED_AT.put(k, System.currentTimeMillis());
               return;
            }
            long wait = lastCallAt + GAP_MS - System.currentTimeMillis();
            if (wait > 0L) Thread.sleep(wait);
            lastCallAt = System.currentTimeMillis();
            okhttp3.Request req = new okhttp3.Request.Builder()
                  .url(base.newBuilder()
                        .addQueryParameter("lat", String.valueOf(lat))
                        .addQueryParameter("lng", String.valueOf(lng))
                        .build())
                  .header("Accept", "application/json")
                  .header("Authorization", "Bearer " + cfg.getToken())
                  .get()
                  .build();
            try (okhttp3.Response r = com.crowja.damiupos.sync.Http.SHARED.newCall(req).execute()) {
               if (r.code() == 404) {
                  // Titik tanpa data administratif (atau server lama tanpa rute ini): ingat 7 hari.
                  MEM.put(k, "");
                  sp.edit().putString(k, "-" + System.currentTimeMillis()).apply();
                  return;
               }
               if (!r.isSuccessful()) {
                  FAILED_AT.put(k, System.currentTimeMillis());
                  return;
               }
               String body = r.body() != null ? r.body().string() : "";
               JSONObject o = new JSONObject(body.isEmpty() ? "{}" : body);
               // Format = Customer.getAdminArea(): "Desa, Kec. X" (kecamatan sudah berprefiks dari server).
               String desa = cleanLabel(o.optString("desa", ""));
               String kec = cleanLabel(o.optString("kecamatan", ""));
               String area = desa.isEmpty() ? kec : (kec.isEmpty() ? desa : desa + ", " + kec);
               if (area.isEmpty()) area = cleanLabel(o.optString("formatted", ""));
               MEM.put(k, area);
               sp.edit().putString(k, "+" + area).apply();
               resolved = !area.isEmpty();
            }
         } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            FAILED_AT.put(k, System.currentTimeMillis());
         } catch (Exception e) {
            FAILED_AT.put(k, System.currentTimeMillis());
         } finally {
            INFLIGHT.remove(k);
            if (resolved) fire();
         }
      }
   }

   /**
    * Baris "📍" kartu antrean — diletakkan TEPAT DI BAWAH nama pelanggan, teks hitam pekat
    * (@color/text_strong): "📍 Kedai · Kartasura (1,2 km)", "📍 Kartasura (850 m)", atau
    * "📍 1,2 km" bila wilayah belum di-geocode. "" bila tak ada apa pun untuk ditampilkan.
    * Dipakai SEMUA kartu antrean (Antrian Saya, Perangkat Lain, Pesanan Terbuka, Antrean Lain
    * mode terpandu, OtherDeviceQueueActivity) supaya formatnya satu.
    */
   static String queueLocationLine(String place, String jarak) {
      String p = cleanLabel(place);
      String j = cleanLabel(jarak);
      if (p.isEmpty() && j.isEmpty()) return "";
      if (p.isEmpty()) return "📍 " + j;
      return "📍 " + p + (j.isEmpty() ? "" : " (" + j + ")");
   }

   /** Baris "📍" untuk order JSON server (tab Perangkat Lain dsb.): tujuan hanya dianggap bila
    *  dest_name terisi — tanpa itu koordinat baris = koordinat utama pelanggan versi server. */
   static String queueLocationLine(Customer c, JSONObject o, String jarak) {
      String dn = cleanLabel(o.optString("dest_name", ""));
      String place = dn.isEmpty()
            ? areaLabel(c, 0.0, 0.0, "")
            : areaLabel(c, o.optDouble("latitude", 0.0), o.optDouble("longitude", 0.0), dn);
      return queueLocationLine(place, jarak);
   }

   static void bindLocationLine(TextView tv, String text) {
      if (tv == null) return;
      if (text == null || text.isEmpty()) {
         tv.setVisibility(View.GONE);
      } else {
         tv.setText(text);
         tv.setVisibility(View.VISIBLE);
      }
   }

   /** " · 🧾 KODE-DDMMYYHHMM-XXXXX" untuk baris meta kartu antrean — ID transaksi yang sama dengan di
    *  struk pelanggan & dashboard. Kosong bila order tak punya nomor (bukan JUAL / baris lama). */
   /**
    * " · 🕐 Sen 20/09 08:15" — kapan pesanan ini ASLINYA dibuat (ordered_at: nilai server
    * created_at, atau waktu insert lokal sebelum tersinkron). Sengaja BUKAN tanggal, yang ikut
    * digeser ke jadwal lanjut saat pesanan ditunda. Cermin "🕐 Dipesan …" di kartu antrean web.
    */
   /** " · 💬 ZAKY" — akun WA tempat agen menerima pesanan (source_wa); kosong bila bukan order agen.
    *  Cermin "💬 Diterima via …" di kartu antrean web. */
   static String sourceWaSuffix(String sourceWa) {
      if (sourceWa == null) return "";
      String v = sourceWa.trim();
      return v.isEmpty() || "null".equals(v) ? "" : " · 💬 " + v;
   }

   static String orderedSuffix(Transaction t) {
      String v = t.getOrderedAt();
      if ((v == null || v.isEmpty()) && !Transaction.DELIVERY_TERTUNDA.equals(t.getDeliveryStatus())) {
         v = t.getTanggal();   // baris lama belum membawa ordered_at; tanggal belum pernah digeser
      }
      return orderedSuffix(v);
   }

   /** Versi mentah: menerima waktu lokal "yyyy-MM-dd HH:mm:ss…" ATAU ISO UTC "…T…Z" (JSON server). */
   static String orderedSuffix(String orderedAt) {
      if (orderedAt == null || orderedAt.length() < 19 || "null".equals(orderedAt)) return "";
      try {
         Date d;
         if (orderedAt.charAt(10) == 'T') {
            SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            if (orderedAt.endsWith("Z")) iso.setTimeZone(TimeZone.getTimeZone("UTC"));
            d = iso.parse(orderedAt.substring(0, 19));
         } else {
            d = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).parse(orderedAt.substring(0, 19));
         }
         return d == null ? "" : " · 🕐 " + new SimpleDateFormat("EEE dd/MM HH:mm", new Locale("id", "ID")).format(d);
      } catch (Exception e) {
         return "";
      }
   }

   static String receiptSuffix(String receiptNo) {
      if (receiptNo == null) return "";
      String r = receiptNo.trim();
      return r.isEmpty() || "null".equals(r) ? "" : " · 🧾 " + r;
   }

   /**
    * " · 🧺 Lokasi 1/2" untuk baris meta kartu antrean — order ini satu leg dari checkout
    * multi-lokasi (satu pesanan, beberapa lokasi tujuan, tiap lokasi dibayar di pintunya sendiri).
    * Cukup dari kolom baris INI: HP kurir sering hanya memegang leg ini. Nama lokasinya sudah ada di
    * baris 📍. Order biasa → "" (kartu persis seperti dulu).
    */
   static String checkoutLegSuffix(Transaction t) {
      if (t == null || !t.isCheckoutLeg()) return "";
      String b = CheckoutStrukText.legBadge(t.getCheckoutSeq(), t.getCheckoutSize());
      return b.isEmpty() ? "" : " · " + b;
   }

   /** Versi baris JSON server (tab Perangkat Lain / Antrean Lain): blok "checkout" {seq, size} dari
    *  Reports::shapeQueueRow, atau kolom datar checkout_seq/checkout_size. Server lama → "". */
   static String checkoutLegSuffix(JSONObject o) {
      if (o == null) return "";
      JSONObject co = o.optJSONObject("checkout");
      int seq = co != null ? co.optInt("seq", 0) : o.optInt("checkout_seq", 0);
      int size = co != null ? co.optInt("size", 0) : o.optInt("checkout_size", 0);
      String b = CheckoutStrukText.legBadge(seq, size);
      return b.isEmpty() ? "" : " · " + b;
   }

   /** Versi nilai mentah — baris server Antrean Tertunda yang sudah diurai util.TertundaRows. */
   static String checkoutLegSuffix(int seq, int size) {
      String b = CheckoutStrukText.legBadge(seq, size);
      return b.isEmpty() ? "" : " · " + b;
   }

   /** Pengingat penagihan leg checkout untuk popup Selesai / Detail Order ("" untuk order biasa).
    *  Leg HUTANG / sudah LUNAS diberi kalimat sendiri — tak ada yang ditagih untuk pesanan itu. */
   static String checkoutCollectHint(Transaction t) {
      if (t == null || !t.isCheckoutLeg()) return "";
      return CheckoutStrukText.collectHint(t.getCheckoutSeq(), t.getCheckoutSize(), t.getDeliveryDestName(),
            t.getPaymentMethod(), t.isPaymentConfirmed());
   }

   /** checkout_uuid leg ini untuk pengecualian hutang segar leg saudara; null untuk order biasa. */
   private static String legCheckoutUuid(Transaction t) {
      return t != null && t.isCheckoutLeg() ? t.getCheckoutUuid() : null;
   }

   /** Popup catatan pengiriman lengkap, satu poin per baris. */
   static void showOrderNoteDialog(android.content.Context ctx, String rawCatatan) {
      List<String> lines = orderNoteLines(rawCatatan);
      if (lines.isEmpty()) return;
      StringBuilder sb = new StringBuilder();
      for (String l : lines) {
         if (sb.length() > 0) sb.append("\n\n");
         sb.append("\u2022 ").append(l);
      }
      final String text = sb.toString();
      AlertDialog dlg = (new AlertDialog.Builder(ctx)).setTitle("\ud83d\udcdd Catatan Pengiriman").setMessage(text)
            .setPositiveButton("Tutup", (DialogInterface.OnClickListener) null)
            .setNeutralButton("Salin", (d, w) -> {
               android.content.ClipboardManager cm = (android.content.ClipboardManager) ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
               if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("Catatan", text));
               Toast.makeText(ctx, "Catatan disalin", Toast.LENGTH_SHORT).show();
            }).show();
      // Link di catatan (mis. lokasi Google Maps kiriman pelanggan) bisa langsung diketuk.
      com.crowja.damiupos.util.NoteLinks.apply((TextView) dlg.findViewById(android.R.id.message));
   }

   /**
    * Pecah catatan jadi poin. Sumber pemisah, berurutan:
    *  1. baris baru ASLI (dipertahankan {@link ReceiptActivity#customerNoteKeepLines}), " \u00b7 ", "; ", " | ";
    *  2. catatan FREZ AI Agent ditulis sambung tanpa pemisah ("\u2026oleh FREZ AI Agent KIRIM HARI INI, KAMIS
    *     \u2026 08:00 WIB HARUS KIRIM SEBELUM JAM 11 \u2026") \u2014 poin baru dimulai (a) sebelum deret \u22653 kata
    *     HURUF BESAR yang mengikuti kata berhuruf kecil (deret 2 kata seperti "FREZ AI" = nama, tak
    *     dipecah), dan (b) setelah "WIB/WITA/WIT" bila disusul kata HURUF BESAR.
    */
   static List<String> orderNoteLines(String rawCatatan) {
      List<String> out = new ArrayList<>();
      String s = ReceiptActivity.customerNoteKeepLines(rawCatatan);
      for (String chunk : s.split("\\r?\\n|\\s+\u00b7\\s+|\\s*;\\s+|\\s+\\|\\s+")) {
         String[] tok = chunk.trim().split("[ \\t]+");
         StringBuilder cur = new StringBuilder();
         for (int i = 0; i < tok.length; i++) {
            if (tok[i].isEmpty()) continue;
            boolean breakHere = false;
            if (i > 0 && cur.length() > 0) {
               boolean prevLower = !tok[i - 1].equals(tok[i - 1].toUpperCase(Locale.ROOT));
               boolean prevTz = tok[i - 1].replaceAll("[.,]+$", "").matches("(?i)WIB|WITA|WIT");
               if (prevTz && isCapsWord(tok[i])) {
                  breakHere = true;
               } else if (prevLower && isCapsWord(tok[i])) {
                  int run = 0;
                  for (int j = i; j < tok.length && run < 3; j++) {
                     if (isCapsWord(tok[j])) run++;
                     else if (!tok[j].matches("[\\d.,:/]+")) break;
                  }
                  breakHere = run >= 3;
               }
            }
            if (breakHere) {
               addNoteLine(out, cur.toString());
               cur.setLength(0);
            }
            if (cur.length() > 0) cur.append(' ');
            cur.append(tok[i]);
         }
         addNoteLine(out, cur.toString());
      }
      return out;
   }

   /** Kata HURUF BESAR "sungguhan" (\u22652 huruf, tanpa huruf kecil), tanda baca di ekor diabaikan. */
   private static boolean isCapsWord(String w) {
      String core = w.replaceAll("[^\\p{L}]", "");
      return core.length() >= 2 && core.equals(core.toUpperCase(Locale.ROOT));
   }

   private static void addNoteLine(List<String> out, String line) {
      String l = line.replaceAll("^[\\s\u00b7\\-\u2013\u2014,;:]+", "").replaceAll("[\\s\u00b7\\-\u2013\u2014,;:]+$", "").trim();
      if (!l.isEmpty()) out.add(l);
   }

   /** Badge "\u21a9 AMBIL SAJA" \u2014 order KEMBALI (galon kosong dijemput kurir, tak ada barang diantar),
    *  dipasang di kaki kartu antrian yang sama dengan {@link #bindOrderNote}, di KEEMPAT
    *  adapter yang memakai item_other_device_compact.xml. */
   static void bindPickupOnlyBadge(TextView tv, boolean pickupOnly) {
      if (tv != null) {
         tv.setVisibility(pickupOnly ? View.VISIBLE : View.GONE);
      }
   }

   /**
    * Item order DIGABUNG per kapsul (label = slug produk): order promo menyimpan produk yang SAMA
    * sebagai dua baris (1 berbayar + 1 gratis), jadi tanpa ini kartu menampilkan "BIO ×1  BIO ×1"
    * alih-alih "BIO ×2". Urutan kemunculan pertama dipertahankan. Nilai = {qty, warna}.
    */
   private LinkedHashMap<String, int[]> mergedChipItems(List<TransactionItem> items) {
      LinkedHashMap<String, int[]> out = new LinkedHashMap<>();
      if (items == null) return out;
      for (TransactionItem it : items) {
         if (it == null || it.jumlah <= 0) continue;
         String label = this.chipLabel(it);
         int[] v = out.get(label);
         if (v == null) out.put(label, new int[]{it.jumlah, this.chipColor(it)});
         else v[0] += it.jumlah;
      }
      return out;
   }

   /**
    * Versi {@link #mergedChipItems} untuk label teks server ("Nama 1×, Nama 1×" — antrean perangkat
    * lain / Preview). Nilai = {qty (-1 = tanpa angka), warna}; label tanpa angka tak digabung.
    */
   private LinkedHashMap<String, int[]> mergedCsvChips(String itemsCsv) {
      LinkedHashMap<String, int[]> out = new LinkedHashMap<>();
      String[] parts = itemsCsv != null && !itemsCsv.trim().isEmpty() && !"null".equals(itemsCsv) ? itemsCsv.split(",\\s*") : new String[0];
      for (String part : parts) {
         String raw = part.trim();
         if (raw.isEmpty()) continue;
         Matcher m = ITEM_LABEL_PATTERN.matcher(raw);
         boolean hasQty = m.matches();
         String name = hasQty ? m.group(1).trim() : raw;
         int qty = hasQty ? Integer.parseInt(m.group(2)) : -1;
         Product p = (Product)this.productByName.get(normProductName(name));
         String slug = p != null ? p.getSlug() : null;
         String label = slug != null && !slug.trim().isEmpty() ? slug.trim() : (name.length() <= 10 ? name : name.substring(0, 10).trim() + "…");
         int bg = -6511697;
         if (p != null && p.getColor() != null && !p.getColor().trim().isEmpty()) {
            try {
               bg = Color.parseColor(p.getColor().trim());
            } catch (IllegalArgumentException ignored) {
               bg = TransactionAdapter.paletteColor(name);
            }
         } else if (p == null) {
            bg = TransactionAdapter.paletteColor(name);
         }
         int[] v = hasQty ? out.get(label) : null;
         if (v != null && v[0] >= 0) v[0] += qty;
         else out.put(hasQty ? label : label + "\u0000" + out.size(), new int[]{qty, bg});
      }
      return out;
   }

   /** Label tampilan kapsul dari entri {@link #mergedCsvChips} (buang penanda unik label tanpa angka). */
   private static String csvChipText(Map.Entry<String, int[]> e) {
      String k = e.getKey();
      int z = k.indexOf('\u0000');
      String label = z >= 0 ? k.substring(0, z) : k;
      return e.getValue()[0] >= 0 ? label + " ×" + e.getValue()[0] : label;
   }

   // ------------------------------------------------------------------ 🚰 jenis galon di kapsul slug

   /** Jenis galon order DIASUMSIKAN (sumber "default": pelanggan tak menjawab) → tag bergaris tepi + "?". */
   private static boolean galonAssumed(String source) {
      return GalonTypeUi.SOURCE_DEFAULT.equals(source);
   }

   /**
    * Kapsul produk dipecah/ditandai per JENIS GALON ({@link GalonTypeUi}): baris {label, jenis ("" = tak
    * diketahui / ambil-saja), qty (-1 = tanpa angka), warna}. CAMPUR ber-rincian → satu baris per (produk,
    * jenis); tanpa rincian → jenis CAMPUR. Satu sumber untuk kartu 3 tab, baris rit/trip, total muatan, Preview.
    */
   private List<Object[]> typedRows(LinkedHashMap<String, int[]> merged, boolean csv, String type, Object spec, boolean pickup) {
      List<Object[]> out = new ArrayList<>();
      boolean typed = !pickup && GalonTypeUi.known(type);
      Map<String, LinkedHashMap<String, Integer>> bySlug = typed && GalonTypeUi.CAMPUR.equals(type)
            ? GalonTypeUi.specBySlug(spec) : new java.util.HashMap<>();
      for (Map.Entry<String, int[]> e : merged.entrySet()) {
         String key = e.getKey();
         int z = csv ? key.indexOf('\u0000') : -1;
         String label = z >= 0 ? key.substring(0, z) : key;
         int qty = e.getValue()[0];
         int bg = e.getValue()[1];
         LinkedHashMap<String, Integer> rows = bySlug.get(label.trim().toUpperCase(Locale.US));
         if (rows != null && !rows.isEmpty()) {
            for (Map.Entry<String, Integer> r : rows.entrySet()) out.add(new Object[]{label, r.getKey(), r.getValue(), bg});
         } else {
            out.add(new Object[]{label, typed ? type : "", qty, bg});
         }
      }
      return out;
   }

   private List<Object[]> typedRows(Transaction t) {
      return this.typedRows(this.mergedChipItems(t.getItems()), false, t.getGalonType(), t.getGalonSpec(),
            DeliveryPlanner.isPickupOnly(t));
   }

   private List<Object[]> typedRows(JSONObject o) {
      return this.typedRows(this.mergedCsvChips(o.optString("items", "")), true, strJson(o, "galon_type"),
            o.opt("galon_spec"), o.optBoolean("pickup_only", false));
   }

   /** Teks kapsul satu baris {@link #typedRows}: "MIN ×2" + tag jenis. */
   private static CharSequence typedChipText(Object[] r, boolean assumed) {
      String label = (String) r[0];
      String type = (String) r[1];
      int qty = (Integer) r[2];
      String base = qty >= 0 ? label + " ×" + qty : label;
      return type.isEmpty() ? base : GalonTypeUi.tagged(base, type, assumed, chipTextColor((Integer) r[3]));
   }

   /** Kunci total muatan per (slug, jenis) — {@link #typedTotalText} memecahnya lagi. */
   private static String typedKey(String label, String type) {
      return type == null || type.isEmpty() ? label : label + '\u0001' + type;
   }

   private static CharSequence typedTotalText(String key, int qty, int bg) {
      int z = key.indexOf('\u0001');
      String base = (z >= 0 ? key.substring(0, z) : key) + " ×" + qty;
      return z >= 0 ? GalonTypeUi.tagged(base, key.substring(z + 1), false, chipTextColor(bg)) : base;
   }

   private static String typedTotalPlain(String key, int qty) {
      int z = key.indexOf('\u0001');
      String base = (z >= 0 ? key.substring(0, z) : key) + " ×" + qty;
      return z >= 0 ? base + " " + GalonTypeUi.label(key.substring(z + 1)) : base;
   }

   private void bindOtherDeviceChips(LinearLayout box, String itemsCsv, JSONObject json) {
      this.bindOtherDeviceChips(box, itemsCsv, json, true);
      this.appendTripChip(box, json != null ? json.optString("uuid", "") : null);
   }

   private void bindOtherDeviceChips(LinearLayout box, String itemsCsv) {
      this.bindOtherDeviceChips(box, itemsCsv, null, false);
   }

   /** Kapsul produk kartu perangkat lain — ber-tag jenis galon bila {@code json} (baris server) ada. */
   private void bindOtherDeviceChips(LinearLayout box, String itemsCsv, JSONObject json, boolean typed) {
      int shown = 0;
      List<Object[]> rows = typed && json != null
            ? this.typedRows(this.mergedCsvChips(itemsCsv), true, strJson(json, "galon_type"), json.opt("galon_spec"),
                  json.optBoolean("pickup_only", false))
            : this.typedRows(this.mergedCsvChips(itemsCsv), true, null, null, true);
      boolean assumed = json != null && galonAssumed(strJson(json, "galon_type_source"));

      for (Object[] r : rows) {
         int bg = (Integer) r[3];
         TextView chip = this.chipAt(box, shown);
         chip.setText(typedChipText(r, assumed));
         // Kapsul ber-tag jenis tak boleh di-ellipsis: ellipsis + span pil membuat tag menimpa angka qty.
         chip.setEllipsize(chip.getText() instanceof android.text.Spanned ? null : TruncateAt.END);
         chip.setTextColor(chipTextColor(bg));
         ((GradientDrawable)chip.getBackground()).setColor(bg);
         chip.setVisibility(0);
         ++shown;
      }

      for(int i = shown; i < box.getChildCount(); ++i) {
         box.getChildAt(i).setVisibility(8);
      }

      box.setVisibility(shown > 0 ? 0 : 8);
   }

   private void showOtherDeviceMoreMenu(View anchor, JSONObject q) {
      PopupMenu menu = new PopupMenu(this, anchor);
      menu.getMenu().add(0, 1, 0, "\ud83d\udc64 Detail Pelanggan");
      menu.getMenu().add(0, 2, 1, "\ud83d\udd52 Jadwalkan Ulang");
      if (q.optBoolean("chat_session", false)) {
         menu.getMenu().add(0, 3, 2, "\ud83d\udcac Chat Pesanan");
      }
      menu.setOnMenuItemClickListener((item) -> {
         switch (item.getItemId()) {
            case 1:
               this.openOtherDeviceCustomerDetail(q);
               return true;
            case 2:
               this.showPostponeSchedulePickerOther(q);
               return true;
            case 3:
               this.openOrderChatUuid(q.optString("uuid", ""), q.optString("name", ""));
               return true;
            case 4:
               this.showForwardOtherPicker(q);
               return true;
            case 5:
               this.showGalonTypeDialog(q);
               return true;
            default:
               return false;
         }
      });
      menu.getMenu().add(0, 4, 3, "📤 Kirim ke Perangkat Lain");
      if (!q.optBoolean("pickup_only", false)) menu.getMenu().add(0, 5, 4, "🚰 Jenis Galon");
      menu.show();
   }

   /** Pemegang order di baris tab Perangkat Lain (routed_uuid, jatuh ke device_group_uuid). */
   private static String otherHolderUuid(JSONObject q) {
      String h = q.optString("routed_uuid", "");
      if (h.isEmpty() || "null".equals(h)) h = q.optString("device_group_uuid", "");
      return "null".equals(h) ? "" : h;
   }

   /** "📤 Kirim ke Perangkat Lain" dari tab Perangkat Lain: teruskan order yang DIPEGANG HP lain ke HP
    *  ketiga. Server (/api/delivery/route + from_device_uuid) menolak bila pemegangnya sudah berubah
    *  atau order sedang diantar dalam rit. */
   private void showForwardOtherPicker(JSONObject q) {
      SyncSettings cfg = this.syncCfg();
      String myUuid = cfg.getDeviceUuid();
      String holder = otherHolderUuid(q);
      List<String[]> others = new ArrayList<>();
      try {
         JSONArray arr = new JSONArray(cfg.getDeviceRoster());
         for (int i = 0; i < arr.length(); ++i) {
            JSONObject d = arr.optJSONObject(i);
            if (d == null) continue;
            String uuid = d.optString("uuid", "");
            if (!uuid.isEmpty() && !uuid.equals(myUuid) && !uuid.equals(holder)) others.add(new String[]{uuid, d.optString("name", "Perangkat")});
         }
      } catch (Exception ignored) {
      }
      if (others.isEmpty()) {
         Toast.makeText(this, "Belum ada perangkat delivery lain di roster. Coba sinkron dulu.", 1).show();
         return;
      }
      String[] names = new String[others.size()];
      for (int i = 0; i < others.size(); ++i) names[i] = others.get(i)[1];
      String cust = q.optString("name", "");
      (new AlertDialog.Builder(this)).setTitle("Kirim \"" + cust + "\" ke perangkat mana?")
            .setItems(names, (dx, which) -> {
               String[] tg = others.get(which);
               (new AlertDialog.Builder(this)).setTitle("Kirim ke \"" + tg[1] + "\"?")
                     .setMessage("Order \"" + cust + "\" dipindahkan ke antrian \"" + tg[1] + "\". Perangkat pemegang sekarang akan kehilangan order ini.")
                     .setPositiveButton("Kirim", (d2, w2) -> this.forwardOtherOrder(q, holder, tg[0], tg[1]))
                     .setNegativeButton("Batal", (DialogInterface.OnClickListener) null).show();
            })
            .setNegativeButton("Batal", (DialogInterface.OnClickListener) null).show();
   }

   private void forwardOtherOrder(JSONObject q, String holder, String targetUuid, String targetName) {
      SyncSettings cfg = this.syncCfg();
      String trxUuid = q.optString("uuid", "");
      if (trxUuid.isEmpty()) {
         Toast.makeText(this, "Order ini belum punya identitas server. Coba muat ulang.", 1).show();
         return;
      }
      (new Thread(() -> {
         String msg;
         try {
            JSONObject body = new JSONObject();
            body.put("transaction_uuid", trxUuid);
            body.put("target_device_uuid", targetUuid);
            body.put("from_device_uuid", holder);
            JSONObject r = (new SyncApi(cfg)).routeDelivery(body);
            msg = r.optString("message", "Order dikirim ke perangkat " + targetName + ".");
         } catch (SyncApi.SyncException se) {
            msg = extractRouteErrorMessage(se.body);
            if (msg == null) msg = "Gagal mengirim (kode " + se.code + ").";
         } catch (Exception e) {
            msg = "Gagal mengirim — periksa koneksi internet.";
         }
         final String m = msg;
         this.runOnUiThread(() -> {
            if (this.isFinishing() || this.isDestroyed()) return;
            Toast.makeText(this, m, 1).show();
            SyncScheduler.syncNow(this.getApplicationContext());
            this.loadData();
         });
      })).start();
   }

   /** "\ud83d\udc64 Detail Pelanggan" di menu \u22ee kartu Antrean Perangkat Lain \u2014 pelanggan tersinkron
    *  branch-wide (lihat CustomerDao), jadi customer_uuid order itu SELALU punya salinan lokal
    *  di perangkat ini untuk dibuka lewat CustomerDetailActivity yang sudah ada. */
   private void openOtherDeviceCustomerDetail(JSONObject q) {
      String custUuid = q.optString("customer_uuid", "");
      Customer c = !custUuid.isEmpty() && !custUuid.equals("null") ? this.customerDao.getBySyncUuid(custUuid) : null;
      if (c == null) {
         Toast.makeText(this, "Data pelanggan tidak ditemukan di perangkat ini.", Toast.LENGTH_SHORT).show();
         return;
      }
      this.startActivity((new Intent(this, CustomerDetailActivity.class)).putExtra("customer_id", c.getId()));
   }

   private void updateOtherEmptyState() {
      if (this.tvOtherDevicesEmpty != null && this.rvOtherDevices != null && this.otherDevicesAdapter != null) {
         boolean empty = this.otherDevicesAdapter.getItemCount() == 0;
         this.tvOtherDevicesEmpty.setText(this.searchOtherQuery.isEmpty() ? "Tidak ada antrian di perangkat lain" : "Tidak ditemukan");
         this.tvOtherDevicesEmpty.setVisibility(empty ? 0 : 8);
         this.rvOtherDevices.setVisibility(empty ? 8 : 0);
      }
   }

   private static String queuedAgoOtherDevices(String queuedAt) {
      if (queuedAt != null && queuedAt.length() >= 19) {
         try {
            long ms = com.crowja.damiupos.util.Ts.millis(queuedAt);   // tahan ISO-UTC & waktu lokal
            if (ms == Long.MAX_VALUE) {
               return "";
            } else {
               long m = Math.max(0L, System.currentTimeMillis() - ms) / 60000L;
               long h = m / 60L;
               return h > 0L ? "⏱ " + h + " jam " + m % 60L + " mnt lalu" : "⏱ " + m + " mnt lalu";
            }
         } catch (Exception var6) {
            return "";
         }
      } else {
         return "";
      }
   }

   private static double haversineKmOtherDevices(double lat1, double lng1, double lat2, double lng2) {
      double dLat = Math.toRadians(lat2 - lat1);
      double dLng = Math.toRadians(lng2 - lng1);
      double a = Math.sin(dLat / (double)2.0F) * Math.sin(dLat / (double)2.0F) + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLng / (double)2.0F) * Math.sin(dLng / (double)2.0F);
      return (double)12742.0F * Math.atan2(Math.sqrt(a), Math.sqrt((double)1.0F - a));
   }

   /** Order perangkat lain yang SEDANG diantar kurirnya (▶ Jalankan, kunci server {@code in_progress})
    *  — tak boleh diambil alih. Server menolaknya juga (DeliveryClaimController), ini supaya HP tak
    *  menawarkan tombolnya sama sekali. */
   private static boolean isBeingDelivered(JSONObject q) {
      return q != null && q.optBoolean("in_progress", false);
   }

   private void toastBeingDelivered(JSONObject q) {
      String dev = q.optString("device_group_label", "");
      String by = !dev.isEmpty() && !"null".equals(dev) ? " oleh " + dev : "";
      Toast.makeText(this, "🚚 Sedang diantar" + by + " — tidak bisa diambil alih.", Toast.LENGTH_LONG).show();
   }

   private void confirmTakeOverOtherDevices(JSONObject q) {
      if (isBeingDelivered(q)) {
         this.toastBeingDelivered(q);
         return;
      }
      String uuid = q.optString("uuid", "");
      String name = q.optString("name", "Pelanggan");
      String fromDevice = q.optString("device_group_label", "");
      StringBuilder msg = new StringBuilder();
      msg.append("Order \"").append(name).append("\" akan DIPINDAHKAN dari perangkat ").append(!fromDevice.isEmpty() && !fromDevice.equals("null") ? "\"" + fromDevice + "\"" : "lain").append(" ke perangkat Anda.\n\n");
      msg.append("Perangkat asal akan diberi tahu bahwa order ini dipindahkan.\n\nKetuk \"Ambil Alih\" dua kali untuk memastikan.");
      AlertDialog dialog = (new AlertDialog.Builder(this)).setIcon(17301543).setTitle("⚠️ Ambil Alih Pengiriman?").setCancelable(false).setMessage(msg.toString()).setPositiveButton("Ambil Alih", (DialogInterface.OnClickListener)null).setNegativeButton("Batal", (DialogInterface.OnClickListener)null).create();
      dialog.setOnShowListener((d) -> {
         Button pos = dialog.getButton(-1);
         int[] clicks = new int[]{0};
         pos.setOnClickListener((v) -> {
            if (++clicks[0] < 2) {
               pos.setText("Ketuk sekali lagi");
            } else {
               pos.setEnabled(false);
               pos.setText("Memindahkan…");
               dialog.setCancelable(false);
               Button neg = dialog.getButton(-2);
               if (neg != null) {
                  neg.setEnabled(false);
               }

               this.takeOverOtherDevices(dialog, pos, neg, uuid, name, strJson(q, "routed_uuid"));
            }
         });
      });
      dialog.show();
   }

   private void takeOverOtherDevices(AlertDialog dialog, Button pos, Button neg, String trxUuid, String custName, String routedUuid) {
      SyncSettings cfg = this.syncCfg();
      if (!cfg.isEnrolled()) {
         Toast.makeText(this, "Perangkat belum terhubung ke server.", 1).show();
         dialog.dismiss();
      } else if (trxUuid != null && !trxUuid.isEmpty()) {
         (new Thread(() -> {
            String okMsg = null;
            String errMsg = null;

            try {
               JSONObject body = new JSONObject();
               body.put("transaction_uuid", trxUuid);
               body.put("expected_device_uuid", routedUuid != null ? routedUuid : "");
               JSONObject r = (new SyncApi(cfg)).claimDelivery(body);
               okMsg = r.optString("message", "Order diambil alih ke perangkat ini.");
            } catch (SyncApi.SyncException var12) {
               SyncApi.SyncException se = var12;

               try {
                  errMsg = (new JSONObject(se.body)).optString("message", (String)null);
               } catch (Exception var11) {
               }

               if (errMsg == null) {
                  errMsg = "Gagal mengambil alih (kode " + var12.code + ").";
               }
            } catch (Exception var13) {
               errMsg = "Gagal mengambil alih — periksa koneksi internet.";
            }

            final String okMsgF = okMsg;
            final String errMsgF = errMsg;
            this.runOnUiThread(() -> {
               if (!this.isFinishing() && !this.isDestroyed()) {
                  if (okMsgF != null) {
                     Toast.makeText(this, okMsgF, 1).show();
                     dialog.dismiss();
                     SyncScheduler.syncNow(this.getApplicationContext());
                     if (this.activeTab == 1) {
                        this.loadOtherDevices();
                     }
                  } else {
                     Toast.makeText(this, errMsgF, 1).show();
                     pos.setEnabled(true);
                     pos.setText("Ambil Alih");
                     dialog.setCancelable(true);
                     if (neg != null) {
                        neg.setEnabled(true);
                     }

                     if (this.activeTab == 1) {
                        this.loadOtherDevices();
                     }
                  }

               }
            });
         })).start();
      } else {
         Toast.makeText(this, "Order ini belum punya identitas server. Coba muat ulang.", 1).show();
         dialog.dismiss();
      }
   }

   /**
    * Ringkasan order antrean PERANGKAT LAIN dari JSONObject ringkas server (bukan Transaction lokal
    * - beda dari {@link #orderDetailText}) - dipakai dialog "Detail Order" berdiri sendiri DAN
    * kartu "Detail Transaksi" di panel Preview gabungan ({@link #showOtherDevicePreview}), supaya
    * keduanya tak bisa saling menyimpang.
    */
   private String otherDeviceOrderDetailText(JSONObject q) {
      StringBuilder sb = new StringBuilder();
      String phone = q.optString("phone", "");
      if (!phone.isEmpty() && !phone.equals("null")) {
         sb.append("\ud83d\udcde ").append(phone).append('\n');
      }

      String dev = q.optString("device_group_label", "");
      if (!dev.isEmpty() && !dev.equals("null")) {
         sb.append("\ud83d\udcf1 Perangkat asal: ").append(dev).append('\n');
      }

      boolean pickupOnly = q.optBoolean("pickup_only", false);
      String destName = q.optString("dest_name", "");
      String address = q.optString("address", "");
      if (!destName.isEmpty() && !destName.equals("null")) {
         sb.append(pickupOnly ? "\ud83e\udea3 Ambil di: " : "\ud83d\udccd Kirim ke: ").append(destName).append('\n');
      } else if (!address.isEmpty() && !address.equals("null")) {
         sb.append("\ud83d\udccd ").append(address).append('\n');
      }

      if (sb.length() > 0) {
         sb.append('\n');
      }

      sb.append(q.optInt("galon", 0)).append(" galon \u00b7 Rp ").append(String.format(Locale.US, "%,.0f", q.optDouble("total", (double)0.0F)).replace(',', '.')).append('\n');
      String items = q.optString("items", "");
      if (!items.isEmpty() && !items.equals("null")) {
         sb.append(items).append('\n');
      }

      if (q.optBoolean("order_priority", false)) {
         String why = q.optString("order_priority_reason", "");
         sb.append("\n\u26a1 PRIORITAS").append(!why.isEmpty() && !why.equals("null") ? ": " + why : "").append('\n');
      }

      String ago = queuedAgoOtherDevices(q.optString("queued_at", (String)null));
      if (!ago.isEmpty()) {
         sb.append('\n').append(ago);
      }
      return sb.toString();
   }

   private void showOtherDeviceOrderDetail(JSONObject q) {
      String name = q.optString("name", "Pelanggan");
      boolean delivering = isBeingDelivered(q);
      String text = this.otherDeviceOrderDetailText(q);
      AlertDialog.Builder b = (new AlertDialog.Builder(this)).setTitle("Detail Order \u2014 " + name)
            .setMessage(delivering ? "\ud83d\ude9a SEDANG DIANTAR \u2014 tidak bisa diambil alih.\n\n" + text : text)
            .setNeutralButton("\ud83d\udd0d Preview", (d, w) -> this.showOtherDevicePreview(q))
            .setNegativeButton("Kembali", (DialogInterface.OnClickListener)null);
      if (!delivering) {
         b.setPositiveButton("\ud83d\udce5 Ambil Alih", (d, w) -> this.confirmTakeOverOtherDevices(q));
      }
      b.show();
   }

   private void showOtherDevicePreview(JSONObject q) {
      SyncSettings cfg = this.syncCfg();
      if (!cfg.isEnrolled()) {
         Toast.makeText(this, "Perangkat belum terhubung ke server.", 0).show();
      } else {
         ProgressDialog progress = ProgressDialog.show(this, (CharSequence)null, "Memuat peta\u2026", true, false);
         (new Thread(() -> {
            JSONObject data = null;

            try {
               data = (new SyncApi(cfg)).deliveryMap();
            } catch (Exception var6) {
            }

            final JSONObject dataF = data;
            this.runOnUiThread(() -> {
               if (!this.isFinishing() && !this.isDestroyed()) {
                  progress.dismiss();
                  if (dataF == null) {
                     Toast.makeText(this, "Gagal memuat peta \u2014 periksa koneksi internet.", 1).show();
                  } else {
                     this.buildOtherDevicePreview(dataF, q);
                  }
               }
            });
         })).start();
      }
   }

   /**
    * Menyusun {@link PreviewData} dari data "peta antrean perangkat lain" (posisi tujuan +
    * posisi kurir pemegang order) DAN dari JSONObject ringkas order (foto lokasi + detail
    * transaksi) - dua sumber sekaligus karena satu-satunya endpoint peta tak membawa foto/detail,
    * dan JSON antrean ringkas tak membawa koordinat perangkat pemegangnya.
    */
   private void buildOtherDevicePreview(JSONObject mapData, JSONObject q) {
      String uuid = q.optString("uuid", "");
      String devUuid = q.optString("device_group_uuid", "");
      JSONArray queue = mapData.optJSONArray("queue");
      JSONArray positions = mapData.optJSONArray("positions");
      double destLat = (double)0.0F;
      double destLng = (double)0.0F;
      String destName = q.optString("name", "Tujuan Order");
      boolean hasDest = false;
      if (queue != null) {
         for(int i = 0; i < queue.length(); ++i) {
            JSONObject p = queue.optJSONObject(i);
            if (p != null && uuid.equals(p.optString("uuid", ""))) {
               destLat = p.optDouble("lat", (double)0.0F);
               destLng = p.optDouble("lng", (double)0.0F);
               hasDest = destLat != (double)0.0F || destLng != (double)0.0F;
               break;
            }
         }
      }

      if (!hasDest) {
         destLat = q.optDouble("latitude", (double)0.0F);
         destLng = q.optDouble("longitude", (double)0.0F);
         hasDest = destLat != (double)0.0F || destLng != (double)0.0F;
      }

      double devLat = (double)0.0F;
      double devLng = (double)0.0F;
      String devName = q.optString("device_group_label", "Perangkat");
      String devVehicle = null, devColor = null;
      String devPinUuid = "";
      boolean hasDevPos = false;
      if (positions != null && !devUuid.isEmpty()) {
         for(int i = 0; i < positions.length(); ++i) {
            JSONObject p = positions.optJSONObject(i);
            if (p != null && devUuid.equals(p.optString("device_uuid", ""))) {
               devLat = p.optDouble("lat", (double)0.0F);
               devLng = p.optDouble("lng", (double)0.0F);
               hasDevPos = devLat != (double)0.0F || devLng != (double)0.0F;
               // Identitas kendaraan kurir itu — sama persis dengan pin posisi live-nya di peta lain
               // manapun (App\Support\DeviceIcon::vehicleFor, {@see LiveDeviceOverlay}).
               devVehicle = p.optString("vehicle", "");
               devColor = p.optString("color", "");
               devPinUuid = p.optString("device_uuid", "");
               break;
            }
         }
      }

      if (!hasDest && !hasDevPos) {
         Toast.makeText(this, "Belum ada data koordinat untuk order/perangkat ini.", 1).show();
         return;
      }

      String custUuid = q.optString("customer_uuid", "");
      Customer c = !custUuid.isEmpty() && !custUuid.equals("null") ? this.customerDao.getBySyncUuid(custUuid) : null;
      String destLocName = strJson(q, "dest_name");
      String adminArea = hasDest ? destAreaFor(c, destLat, destLng) : (c != null ? cleanLabel(c.getAdminArea()) : "");
      String areaSuffix = !adminArea.isEmpty() ? " (" + adminArea + ")" : "";
      String custAddress = c != null && c.getAddress() != null ? c.getAddress().trim() : "";
      // Tujuan RESELLER: label khusus & TANPA foto rumah afiliasi sebagai cadangan — cermin
      // showQueuePreview di antrean sendiri.
      boolean resellerDest = Transaction.isResellerDestName(destLocName);
      String address = resellerDest ? "🤝 " + Transaction.resellerDestLabel(destLocName)
            : !destLocName.trim().isEmpty() ? "Kirim Ke: " + destLocName.trim() + areaSuffix : (!custAddress.isEmpty() ? custAddress + areaSuffix : "");
      Customer photoFallback = resellerDest ? null : c;

      // Foto lokasi: nama lokasi cocok persis (kalau ada) menang atas foto default pelanggan -
      // cermin showQueuePreview di antrean sendiri.
      String destPhotoUrl = null;
      Customer.Location destMatch = null;
      if (c != null && !resellerDest && !destLocName.trim().isEmpty() && c.getLocations() != null) {
         for (Customer.Location l : c.getLocations()) {
            if (destLocName.trim().equalsIgnoreCase(safe(l.name))) {
               destMatch = l;
               if (l.photo != null && !l.photo.trim().isEmpty()) destPhotoUrl = l.photo.trim();
               break;
            }
         }
      }
      // Tujuan = lokasi LAIN (bukan lokasi utama) → foto rumah pelanggan itu foto lokasi utama, BUKAN
      // tujuan ini. Jangan dipakai sebagai cadangan (dulu "Stand minum ZzZ" menampilkan foto kedai utama).
      if (destPhotoUrl == null && c != null && !destLocName.trim().isEmpty() && !isPrimaryLocation(c, destMatch, destLocName)) {
         photoFallback = null;
      }

      PreviewData d = new PreviewData();
      d.title = safe(q.optString("name", "Pelanggan"));
      d.hasDev = hasDevPos;
      d.devLat = devLat;
      d.devLng = devLng;
      d.devName = devName;
      d.devVehicle = devVehicle;
      d.devColor = devColor;
      d.devUuid = devPinUuid;
      // Pesanan Terbuka (belum ditugaskan) -> tak ada gunanya menyorot devUuid (kalau toh ada
      // posisi tersisa di peta, itu cuma perangkat terakhir yang PERNAH memegangnya) -> kedipkan
      // posisi saya sendiri, bukan pin konteks.
      d.blinkAssigned = !q.optBoolean("open_dispatch", false);
      d.hasDest = hasDest;
      d.destLat = destLat;
      d.destLng = destLng;
      d.destName = destName;
      d.address = address;
      d.detailChips = this.buildOtherDeviceDetailChips(q);
      d.detailExtra = this.otherDevicePreviewExtra(q);
      d.photoLocalPath = destPhotoUrl == null && photoFallback != null ? photoFallback.getPhotoPath() : null;
      d.photoUrl = destPhotoUrl != null ? destPhotoUrl : (photoFallback != null ? photoFallback.getPhotoUrl() : null);
      this.showPreviewDialog(d);
   }

   /**
    * Badge "Detail Transaksi" untuk antrean PERANGKAT LAIN — dari {@code items} yang server sudah
    * rakit jadi teks ringkas ("Nama Qty×", App\Support\Reports::orderItemsLabel), bukan
    * TransactionItem penuh (transaksi device-isolated di lapisan sync, HP ini tak memegang
    * barisnya). Slug+warna tetap dicoba lewat {@code productByName} lokal (cocok by NAMA — cukup
    * untuk mayoritas produk, meski nama panjang bisa terpotong oleh Str::limit di server).
    *
    * <p>Tanpa "Kembali"/metode bayar: {@code q} memang tak membawa keduanya (lihat
    * {@link #otherDeviceOrderDetailText} — tak pernah ada baris itu di sana juga).</p>
    */
   private List<Chip> buildOtherDeviceDetailChips(JSONObject q) {
      List<Chip> chips = new ArrayList<>();
      boolean assumed = galonAssumed(strJson(q, "galon_type_source"));
      for (Object[] r : this.typedRows(q)) {
         chips.add(new Chip(typedChipText(r, assumed), (Integer) r[3]));
      }
      chips.add(new Chip("TOTAL " + this.rp(q.optDouble("total", (double) 0.0F)), 0xFF0369A1));
      return chips;
   }

   /** Sisa "Detail Order" antrean perangkat lain yang tak cocok jadi badge — prioritas + lama antre. */
   private String otherDevicePreviewExtra(JSONObject q) {
      StringBuilder sb = new StringBuilder();
      if (q.optBoolean("order_priority", false)) {
         String why = q.optString("order_priority_reason", "");
         sb.append("⚡ PRIORITAS").append(!why.isEmpty() && !why.equals("null") ? ": " + why : "");
      }
      String ago = queuedAgoOtherDevices(q.optString("queued_at", (String) null));
      if (!ago.isEmpty()) {
         if (sb.length() > 0) sb.append('\n');
         sb.append(ago);
      }
      return sb.length() > 0 ? sb.toString() : null;
   }

   /**
    * @param hasDev/devLat/devLng/devName/devVehicle/devColor pin KONTEKS opsional - kurir lain yang
    *        memegang order ini (antrean perangkat lain); kosong untuk antrean sendiri.
    * @param myVehicle/myColor identitas kendaraan PERANGKAT INI (App\Support\DeviceIcon, dicache dari
    *        /api/me) - dipakai pin "Posisi Anda" yang HANYA muncul lewat {@code updateMyPos(lat,lng)},
    *        dipanggil dari kompas setiap fix GPS baru datang (lihat pushMyPosToMap). Tanpa fix belum
    *        ada pin sama sekali - peta tak berbohong soal posisi yang belum benar-benar diketahui.
    */
   /**
    * @param blinkAssigned Pin yang KEDIP: perangkat yang DITUGASKAN (context/devVehicle) bila order
    *        ini punya penanggung jawab, atau posisi SAYA bila order ini Pesanan Terbuka (belum
    *        ada yang ditugaskan — tak masuk akal menyoroti perangkat yang tak ditugaskan). Hanya
    *        benar-benar berlaku bila hasDev juga true; tanpa pin konteks, posisi saya yang kedip
    *        apa pun nilai parameter ini (tak ada yang lain untuk disorot).
    */
   private static String buildMiniMapHtml(boolean hasDev, double devLat, double devLng, String devName, String devVehicle, String devColor, boolean hasDest, double destLat, double destLng, String destName, String myVehicle, String myColor, boolean blinkAssigned, JSONArray otherPending, boolean autoZoom) {
      double centerLat = hasDest ? destLat : devLat;
      double centerLng = hasDest ? destLng : devLng;
      boolean devBlinks = hasDev && blinkAssigned;
      String devVehicleSafe = devVehicle != null && !devVehicle.isEmpty() ? devVehicle : "\ud83c\udfcd\ufe0f";
      String devColorSafe = devColor != null && !devColor.isEmpty() ? devColor : "#F9A825";
      String myVehicleSafe = myVehicle != null && !myVehicle.isEmpty() ? myVehicle : "\ud83d\udef5";
      String myColorSafe = myColor != null && !myColor.isEmpty() ? myColor : "#0369A1";
      StringBuilder js = new StringBuilder();
      js.append("<!DOCTYPE html>\n<html><head>\n");
      js.append("<meta name='viewport' content='width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no'>\n");
      js.append("<link rel='stylesheet' href='https://unpkg.com/leaflet@1.9.4/dist/leaflet.css'/>\n");
      js.append("<script src='https://unpkg.com/leaflet@1.9.4/dist/leaflet.js'></script>\n");
      js.append("<style>html,body{margin:0;padding:0;height:100%;}#map{width:100%;height:100%;}\n");
      js.append(".pin{position:relative;width:26px;height:33px;}.pin svg{position:absolute;top:0;left:0;}\n");
      js.append(".pin .em{position:absolute;top:2px;left:0;width:26px;text-align:center;font-size:13px;line-height:18px;}\n");
      js.append(".pin.blink{animation:pinblink 1s infinite;}\n");
      js.append("@keyframes pinblink{0%,100%{opacity:1;transform:scale(1);}50%{opacity:.55;transform:scale(1.18);}}\n");
      // .vpin: pin KENDARAAN (lingkaran + emoji) - cermin gaya .ldevpin milik LiveDeviceOverlay,
      // supaya identitas perangkat terlihat SAMA di peta manapun di app ini.
      js.append(".vpin{width:28px;height:28px;border-radius:50%;display:flex;align-items:center;justify-content:center;font-size:15px;line-height:1;border:2px solid #fff;box-shadow:0 1px 4px rgba(0,0,0,.45);}\n");
      js.append(".vpin.blink{animation:pinblink 1s infinite;}\n");
      js.append("</style>\n</head><body>\n<div id='map'></div>\n<script>\n");
      js.append("function escHtml(s){return String(s==null?'':s).replace(/[&<>\"']/g,function(c){return {'&':'&amp;','<':'&lt;','>':'&gt;','\"':'&quot;',\"'\":'&#39;'}[c];});}\n");
      js.append("function pinIcon(color,emoji,blink){\n");
      js.append("  var svg='<svg width=\"26\" height=\"33\" viewBox=\"0 0 26 33\" xmlns=\"http://www.w3.org/2000/svg\">'+\n");
      js.append("    '<path d=\"M13 0C5.8 0 0 5.8 0 13c0 8.7 13 20 13 20s13-11.3 13-20C26 5.8 20.2 0 13 0z\" fill=\"'+color+'\"/>'+\n");
      js.append("    '<circle cx=\"13\" cy=\"12\" r=\"9\" fill=\"#fff\"/></svg>';\n");
      js.append("  return L.divIcon({className:'',html:'<div class=\"pin'+(blink?' blink':'')+'\">'+svg+'<div class=\"em\">'+emoji+'</div></div>',iconSize:[26,33],iconAnchor:[13,33],popupAnchor:[0,-30]});\n");
      js.append("}\n");
      js.append("function vehicleIcon(color,vehicle,blink){\n");
      js.append("  var html='<div class=\"vpin'+(blink?' blink':'')+'\" style=\"background:'+color+'\">'+vehicle+'</div>';\n");
      js.append("  return L.divIcon({className:'',html:html,iconSize:[28,28],iconAnchor:[14,14],popupAnchor:[0,-16]});\n");
      js.append("}\n");
      js.append("var map = L.map('map',{zoomControl:true}).setView([").append(centerLat).append(",").append(centerLng).append("], 14);\n");
      js.append("L.tileLayer('https://server.arcgisonline.com/ArcGIS/rest/services/World_Street_Map/MapServer/tile/{z}/{y}/{x}',{maxZoom:19,attribution:'Tiles &copy; Esri &mdash; Source: Esri, HERE, Garmin, USGS, NGA, NOAA'}).addTo(map);\n");
      // fixedPts: titik-titik TETAP (konteks + tujuan) - TIDAK termasuk posisi saya, yang
      // ditambahkan live oleh updateMyPos() supaya zoom ikut menyesuaikan tiap kali jaraknya berubah.
      js.append("var fixedPts=[];\n");
      if (hasDev) {
         js.append("L.marker([").append(devLat).append(",").append(devLng)
               .append("],{icon:vehicleIcon('").append(devColorSafe).append("','").append(devVehicleSafe).append("',").append(devBlinks).append(")})")
               .append(".addTo(map).bindPopup(escHtml('").append(escJs(devName)).append("'));\n");
         js.append("fixedPts.push([").append(devLat).append(",").append(devLng).append("]);\n");
      }
      if (hasDest) {
         js.append("L.marker([").append(destLat).append(",").append(destLng)
               .append("],{icon:pinIcon('#C62828','\ud83d\udccd',true)})")
               .append(".addTo(map).bindPopup(escHtml('").append(escJs(destName)).append("'));\n");
         js.append("fixedPts.push([").append(destLat).append(",").append(destLng).append("]);\n");
      }
      if (hasDev && hasDest) {
         js.append("L.polyline(fixedPts,{color:'").append(devColorSafe).append("',weight:3,opacity:.85,dashArray:'6,6'}).addTo(map);\n");
      }
      // Order LAIN yang masih menunggu (perangkat lain, belum dikerjakan) - pin 📦 kuning, TANPA
      // kedip, hanya untuk konteks sekeliling; ketuk memanggil GuidedMapBridge.showOtherDetail lewat
      // jembatan JS "Android" (lihat renderGuidedInline) supaya kurir bisa intip detailnya di tempat.
      js.append("var otherPending=").append(otherPending == null ? "[]" : otherPending.toString()).append(";\n");
      js.append("otherPending.forEach(function(p){\n");
      js.append("  if(!p.latitude&&!p.longitude) return;\n");
      js.append("  var m=L.marker([p.latitude,p.longitude],{icon:pinIcon('#F59E0B','📦',false)});\n");
      js.append("  m.on('click',function(){ if(window.Android&&Android.showOtherDetail){ Android.showOtherDetail(String(p.uuid||'')); } });\n");
      js.append("  m.addTo(map);\n");
      js.append("});\n");
      js.append("var myMarker=null;\n");
      js.append("function currentPts(){ var pts=fixedPts.slice(); if(myMarker) pts.push(myMarker.getLatLng()); return pts; }\n");
      js.append("function fit(){ map.invalidateSize();\n");
      js.append("  var pts=currentPts();\n");
      // fitBounds memilih zoom sendiri agar semua titik pas dalam bingkai - makin dekat jaraknya,
      // makin dekat zoom-nya, TANPA perlu logika zoom manual. Inilah "zoom otomatis berdasarkan
      // jarak" yang diminta: setiap kali posisi saya berubah, pts berubah, dan fit() dipanggil lagi.
      js.append("  if(pts.length>1){ map.fitBounds(pts,{padding:[36,36],maxZoom:16}); }\n");
      js.append("  else if(pts.length===1){ map.setView(pts[0],16); } }\n");
      // autoZoomEnabled: guided menawarkan tombol "Auto Zoom" (window.setAutoZoom) untuk mematikannya
      // sementara — begitu kurir sendiri men-drag/zoom peta, fit() otomatis DIAM 10 detik supaya
      // tak "merebut kembali" peta dari tangan kurir yang sedang menelusurinya sendiri.
      js.append("var autoZoomEnabled=").append(autoZoom).append(";\n");
      js.append("var lastUserInteract=0;\n");
      js.append("map.on('dragstart zoomstart',function(){ lastUserInteract=Date.now(); });\n");
      js.append("map.on('dragend zoomend',function(){ lastUserInteract=Date.now(); });\n");
      js.append("function maybeFit(){ if(autoZoomEnabled && Date.now()-lastUserInteract>=10000){ fit(); } }\n");
      js.append("window.setAutoZoom=function(on){ autoZoomEnabled=!!on; if(autoZoomEnabled){ lastUserInteract=0; } };\n");
      js.append("window.updateMyPos=function(lat,lng){\n");
      js.append("  var ll=[lat,lng];\n");
      js.append("  if(myMarker){ myMarker.setLatLng(ll); }\n");
      // Pin "Posisi Anda" kedip HANYA bila pin konteks (perangkat yang ditugaskan) TIDAK kedip —
      // satu pin kedip pada satu waktu, supaya mata langsung tertuju ke yang paling relevan:
      // perangkat yang ditugaskan bila ada, atau posisi saya sendiri bila order ini belum bertuan.
      js.append("  else { myMarker=L.marker(ll,{icon:vehicleIcon('").append(myColorSafe).append("','").append(myVehicleSafe).append("',").append(!devBlinks).append(")}).addTo(map).bindPopup('Posisi Anda'); }\n");
      js.append("  maybeFit();\n");
      js.append("};\n");
      js.append("window.addEventListener('load',function(){ fit(); setTimeout(fit,250); setTimeout(fit,800); });\n");
      js.append("setTimeout(fit,120);\n");
      js.append("setInterval(maybeFit,2000);\n");
      js.append("</script></body></html>");
      return js.toString();
   }

   private static String escJs(String s) {
      return s == null ? "" : s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ");
   }

   private static boolean kindMatches(int kind, boolean pickupOnly) {
      return kind == KIND_ALL || (kind == KIND_JUAL ? !pickupOnly : pickupOnly);
   }

   /**
    * Baris checkbox "🛒 Penjualan saja" / "↩ Ambil galon saja" (saling eksklusif) — disisipkan tepat
    * setelah {@code anchor} (checkbox Komplain / kotak cari) di induknya. Antrean selama ini campur
    * antara order jual dan order ambil galon (KEMBALI).
    */
   private void addKindFilter(View anchor, java.util.function.IntConsumer onChange, java.util.Set<String> slugSel, Runnable onSlug) {
      this.addKindFilter(anchor, onChange);
      if (anchor == null || !(anchor.getParent() instanceof View)) return;
      // Baris filter kind sudah disisipkan sebagai HorizontalScrollView; baris badge produk tepat di bawahnya.
      View kindScroll = anchor.getParent() instanceof LinearLayout && anchor.getParent().getParent() instanceof android.widget.HorizontalScrollView
            ? (View) anchor.getParent().getParent() : null;
      android.view.ViewGroup parent;
      int at;
      if (kindScroll != null && kindScroll.getParent() instanceof android.view.ViewGroup) {
         parent = (android.view.ViewGroup) kindScroll.getParent();
         at = parent.indexOfChild(kindScroll) + 1;
      } else {
         parent = (android.view.ViewGroup) anchor.getParent();
         at = parent.indexOfChild(anchor) + 2;
      }
      android.widget.HorizontalScrollView sc = this.buildSlugChipRow(slugSel, onSlug, false, true);
      LinearLayout row = new LinearLayout(this);
      row.setOrientation(LinearLayout.HORIZONTAL);
      row.setGravity(android.view.Gravity.CENTER_VERTICAL);
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
      lp.setMarginStart(this.dp(12f));
      row.setLayoutParams(lp);
      if (sc != null) row.addView(sc, new LinearLayout.LayoutParams(0, -2, 1f));
      // Dropdown centang "🧭 Wilayah" & "🗓 Trip" — kosong = semua (data dari /api/devices/queue-all).
      final java.util.Set<String> wil = new java.util.HashSet<>();
      final java.util.Set<String> trip = new java.util.HashSet<>();
      this.wilayahSel.put(slugSel, wil);
      this.tripSel.put(slugSel, trip);
      row.addView(this.tagDropdown("🧭 Wilayah", wil, true, onSlug));
      row.addView(this.tagDropdown("🗓 Trip", trip, false, onSlug));
      parent.addView(row, Math.min(at, parent.getChildCount()));
   }

   /** Pilihan filter wilayah/trip per tab (kunci = set slug tab itu). */
   private final java.util.Map<java.util.Set<String>, java.util.Set<String>> wilayahSel = new java.util.IdentityHashMap<>();
   private final java.util.Map<java.util.Set<String>, java.util.Set<String>> tripSel = new java.util.IdentityHashMap<>();
   /** uuid order → uuid perangkat pemilik wilayah ("" = tanpa wilayah); pilihan dropdown. */
   private final java.util.Map<String, String> orderWilayah = new java.util.concurrent.ConcurrentHashMap<>();
   private volatile List<String[]> wilayahOptions = new ArrayList<>();
   private volatile List<String> tripOptions = new ArrayList<>();
   private static final String TAG_NONE = "__none__";

   private TextView tagDropdown(String label, java.util.Set<String> sel, boolean wilayah, Runnable onChange) {
      TextView b = this.makeDetailBadge(label + " ▾", 0xFF475569);
      b.setTextSize(13f);
      b.setPadding(this.dp(10f), this.dp(5f), this.dp(10f), this.dp(5f));
      final Runnable paint = () -> {
         int col = sel.isEmpty() ? 0xFF475569 : 0xFF6D28D9;
         GradientDrawable g = new GradientDrawable();
         g.setCornerRadius(this.dp(12f));
         g.setColor(sel.isEmpty() ? 0x00000000 : col);
         g.setStroke(this.dp(1.5f), col);
         b.setBackground(g);
         b.setTextColor(sel.isEmpty() ? col : chipTextColor(col));
         b.setText(label + (sel.isEmpty() ? "" : " (" + sel.size() + ")") + " ▾");
      };
      paint.run();
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
      lp.setMarginStart(this.dp(6f));
      b.setLayoutParams(lp);
      b.setOnClickListener(v -> {
         List<String> keys = new ArrayList<>();
         List<String> names = new ArrayList<>();
         if (wilayah) {
            for (String[] o : this.wilayahOptions) { keys.add(o[0]); names.add(o[1]); }
            keys.add(TAG_NONE); names.add("(tanpa wilayah)");
         } else {
            for (String t : this.tripOptions) { keys.add(t); names.add(t); }
            keys.add(TAG_NONE); names.add("(bukan trip)");
         }
         if (keys.size() <= 1) {
            Toast.makeText(this, wilayah ? "Data wilayah belum dimuat — buka tab Perangkat Lain / tunggu sebentar." : "Belum ada order di wilayah Trip Khusus.", 0).show();
            return;
         }
         boolean[] on = new boolean[keys.size()];
         for (int i = 0; i < keys.size(); i++) on[i] = sel.contains(keys.get(i));
         (new AlertDialog.Builder(this)).setTitle(label + " (kosong = semua)")
               .setMultiChoiceItems(names.toArray(new String[0]), on, (d, i, checked) -> on[i] = checked)
               .setPositiveButton("Terapkan", (d, w) -> {
                  sel.clear();
                  for (int i = 0; i < keys.size(); i++) if (on[i]) sel.add(keys.get(i));
                  paint.run();
                  onChange.run();
               })
               .setNeutralButton("Semua", (d, w) -> { sel.clear(); paint.run(); onChange.run(); })
               .setNegativeButton("Batal", (DialogInterface.OnClickListener) null).show();
      });
      return b;
   }

   /** Lolos filter wilayah & trip tab ini? (set kosong = semua). */
   private boolean tagMatches(java.util.Set<String> slugSel, String uuid) {
      java.util.Set<String> w = this.wilayahSel.get(slugSel);
      java.util.Set<String> t = this.tripSel.get(slugSel);
      String u = uuid != null ? uuid : "";
      if (w != null && !w.isEmpty()) {
         String o = this.orderWilayah.get(u);
         if (!w.contains(o == null || o.isEmpty() ? TAG_NONE : o)) return false;
      }
      if (t != null && !t.isEmpty()) {
         String s = this.tripSlugs.get(u);
         if (!t.contains(s == null || s.isEmpty() ? TAG_NONE : s)) return false;
      }
      return true;
   }

   private boolean tagFiltersEmpty(java.util.Set<String> slugSel) {
      java.util.Set<String> w = this.wilayahSel.get(slugSel);
      java.util.Set<String> t = this.tripSel.get(slugSel);
      return (w == null || w.isEmpty()) && (t == null || t.isEmpty());
   }

   private String trxUuid(Transaction t) {
      String u = t.getSyncUuid();
      if ((u == null || u.isEmpty()) && t.getId() > 0) u = this.dao.getSyncUuidById(t.getId());
      return u;
   }

   /** Deretan chip produk per slug (warna produk; terisi = terpilih). {@code preselectAll} = semua
    *  langsung terpilih (dialog Ambil Antrean). */
   private android.widget.HorizontalScrollView buildSlugChipRow(java.util.Set<String> slugSel, Runnable onSlug, boolean preselectAll) {
      return this.buildSlugChipRow(slugSel, onSlug, preselectAll, false);
   }

   /** Pilihan jenis galon per produk di filter tiap tab (kunci = set slug tab itu): slug → jenis (kosong = semua). */
   private final java.util.Map<java.util.Set<String>, java.util.Map<String, java.util.Set<String>>> slugTypeSel = new java.util.IdentityHashMap<>();
   /** Kunci "Belum diketahui" (order tanpa jenis galon) di filter jenis. */
   private static final String TYPE_UNKNOWN = "?";

   private java.util.Set<String> slugTypes(java.util.Set<String> slugSel, String key) {
      java.util.Map<String, java.util.Set<String>> m = this.slugTypeSel.get(slugSel);
      if (m == null) this.slugTypeSel.put(slugSel, m = new java.util.HashMap<>());
      java.util.Set<String> s = m.get(key);
      if (s == null) m.put(key, s = new java.util.LinkedHashSet<>());
      return s;
   }

   /** Ketuk kapsul produk di filter → pilih jenis galon untuk produk itu (KRN/NON-KRN/AQUA/BEBAS/CAMPUR/?). */
   private void showSlugTypeMenu(String slug, String key, java.util.Set<String> slugSel, Runnable paint, Runnable onSlug) {
      String[] keys = {GalonTypeUi.KERAN, GalonTypeUi.NON_KERAN, GalonTypeUi.AQUA, GalonTypeUi.BEBAS, GalonTypeUi.CAMPUR, TYPE_UNKNOWN};
      String[] labels = {"🚰 KRN — Keran", "⭕ NON-KRN — Non-keran", "💧 AQUA", "✳️ BEBAS", "🔀 CAMPUR", "❔ Belum diketahui"};
      java.util.Set<String> types = this.slugTypes(slugSel, key);
      boolean picked = slugSel.contains(key);
      boolean[] on = new boolean[keys.length];
      for (int i = 0; i < keys.length; i++) on[i] = picked && types.contains(keys[i]);
      (new AlertDialog.Builder(this)).setTitle(slug + " — jenis galon (kosong = semua)")
            .setMultiChoiceItems(labels, on, (d, i, checked) -> on[i] = checked)
            .setPositiveButton("Terapkan", (d, w) -> {
               types.clear();
               for (int i = 0; i < keys.length; i++) if (on[i]) types.add(keys[i]);
               slugSel.add(key);
               paint.run();
               onSlug.run();
            })
            .setNeutralButton("Semua jenis", (d, w) -> {
               types.clear();
               slugSel.add(key);
               paint.run();
               onSlug.run();
            })
            .setNegativeButton(picked ? "Hapus filter" : "Batal", (d, w) -> {
               if (slugSel.remove(key)) {
                  types.clear();
                  paint.run();
                  onSlug.run();
               }
            })
            .show();
   }

   /** @param typeMenu true = kapsul membuka pilihan jenis galon (filter tab); false = toggle biasa (Ambil Antrean). */
   private android.widget.HorizontalScrollView buildSlugChipRow(java.util.Set<String> slugSel, Runnable onSlug, boolean preselectAll,
                                                                boolean typeMenu) {
      List<Product> products = new ProductDao(DatabaseHelper.getInstance(this)).getAll();
      LinearLayout row = new LinearLayout(this);
      row.setOrientation(LinearLayout.HORIZONTAL);
      row.setPadding(0, this.dp(2f), 0, this.dp(4f));
      java.util.Set<String> seen = new java.util.HashSet<>();
      for (Product pr : products) {
         String slug = pr.getSlug() != null ? pr.getSlug().trim() : "";
         if (slug.isEmpty() || !seen.add(slug.toUpperCase(Locale.US))) continue;
         int color = 0xFF1565C0;
         try {
            if (pr.getColor() != null && !pr.getColor().trim().isEmpty()) color = Color.parseColor(pr.getColor().trim());
         } catch (IllegalArgumentException ignored) {
         }
         final int col = color;
         final String key = slug.toUpperCase(Locale.US);
         if (preselectAll) slugSel.add(key);
         TextView chip = this.makeDetailBadge(slug, col);
         chip.setTextSize(13f);
         chip.setPadding(this.dp(12f), this.dp(5f), this.dp(12f), this.dp(5f));
         final Runnable paint = () -> {
            boolean on = slugSel.contains(key);
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(this.dp(12f));
            g.setColor(on ? col : 0x00000000);
            g.setStroke(this.dp(1.5f), col);
            chip.setBackground(g);
            chip.setTextColor(on ? chipTextColor(col) : col);
            String suffix = "";
            if (typeMenu && on) {
               StringBuilder tg = new StringBuilder();
               for (String ty : this.slugTypes(slugSel, key)) {
                  if (tg.length() > 0) tg.append('+');
                  tg.append(TYPE_UNKNOWN.equals(ty) ? "?" : GalonTypeUi.tag(ty));
               }
               if (tg.length() > 0) suffix = " · " + tg;
            }
            chip.setText((on ? "✓ " : "") + slug + suffix + (typeMenu ? " ▾" : ""));
         };
         paint.run();
         chip.setOnClickListener(v -> {
            if (typeMenu) {
               this.showSlugTypeMenu(slug, key, slugSel, paint, onSlug);
               return;
            }
            if (!slugSel.remove(key)) slugSel.add(key);
            paint.run();
            onSlug.run();
         });
         row.addView(chip);
      }
      if (row.getChildCount() == 0) return null;
      android.widget.HorizontalScrollView sc = new android.widget.HorizontalScrollView(this);
      sc.setHorizontalScrollBarEnabled(false);
      sc.addView(row);
      return sc;
   }

   /** Order punya salah satu produk terpilih? (kosong = lolos semua). */
   private boolean slugMatches(java.util.Set<String> sel, Transaction t) {
      if (sel.isEmpty()) return true;
      return this.typedRowsMatch(sel, this.typedRows(t));
   }

   /** Ada baris (produk, jenis galon) yang lolos filter? Produk terpilih tanpa pilihan jenis = semua jenis. */
   private boolean typedRowsMatch(java.util.Set<String> sel, List<Object[]> rows) {
      java.util.Map<String, java.util.Set<String>> tsel = this.slugTypeSel.get(sel);
      for (Object[] r : rows) {
         String k = ((String) r[0]).trim().toUpperCase(Locale.US);
         if (!sel.contains(k)) continue;
         java.util.Set<String> want = tsel != null ? tsel.get(k) : null;
         if (want == null || want.isEmpty()) return true;
         String ty = (String) r[1];
         if (want.contains(ty.isEmpty() ? TYPE_UNKNOWN : ty)) return true;
      }
      return false;
   }

   /** Ada produk terpilih yang dibatasi jenis galonnya? */
   private boolean slugTypeFiltered(java.util.Set<String> sel) {
      java.util.Map<String, java.util.Set<String>> tsel = this.slugTypeSel.get(sel);
      if (tsel == null) return false;
      for (String k : sel) {
         java.util.Set<String> w = tsel.get(k);
         if (w != null && !w.isEmpty()) return true;
      }
      return false;
   }

   private boolean slugMatches(java.util.Set<String> sel, JSONObject o) {
      if (sel.isEmpty()) return true;
      // Dibatasi jenis galon → cocokkan per (produk, jenis) dari kapsul ber-tag baris server.
      if (this.slugTypeFiltered(sel)) return this.typedRowsMatch(sel, this.typedRows(o));
      JSONArray arr = o.optJSONArray("item_slugs");
      for (int i = 0; arr != null && i < arr.length(); i++) {
         JSONObject it = arr.optJSONObject(i);
         String sl = it != null ? it.optString("slug", "") : "";
         if (!sl.isEmpty() && sel.contains(sl.trim().toUpperCase(Locale.US))) return true;
      }
      for (String k : this.mergedCsvChips(o.optString("items", "")).keySet()) {
         int z = k.indexOf('\u0000');
         String label = (z >= 0 ? k.substring(0, z) : k).trim().toUpperCase(Locale.US);
         if (sel.contains(label)) return true;
      }
      return false;
   }

   private void addKindFilter(View anchor, java.util.function.IntConsumer onChange) {
      if (anchor == null || !(anchor.getParent() instanceof android.view.ViewGroup)) return;
      android.view.ViewGroup parent = (android.view.ViewGroup) anchor.getParent();
      int at = parent.indexOfChild(anchor) + 1;
      LinearLayout row = new LinearLayout(this);
      row.setOrientation(LinearLayout.HORIZONTAL);
      row.setGravity(android.view.Gravity.CENTER_VERTICAL);
      // Checkbox "😠 Komplain saja" ikut dipindah ke baris yang SAMA — satu baris filter, digeser
      // ke samping bila layar sempit.
      if (anchor instanceof android.widget.CheckBox) {
         at = parent.indexOfChild(anchor);
         parent.removeView(anchor);
         anchor.setLayoutParams(new LinearLayout.LayoutParams(-2, -2));
         anchor.setPadding(this.dp(2f), 0, this.dp(10f), 0);
         row.addView(anchor);
      }
      com.google.android.material.checkbox.MaterialCheckBox jual = new com.google.android.material.checkbox.MaterialCheckBox(this);
      com.google.android.material.checkbox.MaterialCheckBox ambil = new com.google.android.material.checkbox.MaterialCheckBox(this);
      jual.setText("🛒 Penjualan saja");
      ambil.setText("↩ Ambil galon saja");
      for (com.google.android.material.checkbox.MaterialCheckBox cb : new com.google.android.material.checkbox.MaterialCheckBox[]{jual, ambil}) {
         cb.setTextSize(11f);
         cb.setMinWidth(0);
         cb.setMinimumWidth(0);
         cb.setPadding(this.dp(2f), 0, this.dp(10f), 0);
         row.addView(cb);
      }
      final boolean[] busy = {false};
      jual.setOnCheckedChangeListener((b, checked) -> {
         if (busy[0]) return;
         busy[0] = true;
         if (checked) ambil.setChecked(false);
         busy[0] = false;
         onChange.accept(checked ? KIND_JUAL : (ambil.isChecked() ? KIND_AMBIL : KIND_ALL));
      });
      ambil.setOnCheckedChangeListener((b, checked) -> {
         if (busy[0]) return;
         busy[0] = true;
         if (checked) jual.setChecked(false);
         busy[0] = false;
         onChange.accept(checked ? KIND_AMBIL : (jual.isChecked() ? KIND_JUAL : KIND_ALL));
      });
      android.widget.HorizontalScrollView scroll = new android.widget.HorizontalScrollView(this);
      scroll.setHorizontalScrollBarEnabled(false);
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
      lp.setMarginStart(this.dp(12f));
      scroll.setLayoutParams(lp);
      scroll.addView(row);
      parent.addView(scroll, at);
   }

   private void renderStrategy(List<Transaction> list) {
      MaterialCardView card = (MaterialCardView)this.findViewById(id.cardStrategy);
      if (card != null) {
         List<Transaction> planned = new ArrayList();
         if (list != null) {
            for(Transaction t : list) {
               if (!t.hasPendingVoidRequest()) {
                  planned.add(t);
               }
            }
         }

         // 🧭 Halaman Trip Khusus (geser kiri/kanan). Antrean Saya kosong tapi ada trip → langsung trip.
         boolean tripsAvailable = !this.guidedMode && !this.tripPages.isEmpty();
         if (this.strategyPage > this.tripPages.size()) this.strategyPage = this.tripPages.size();
         if (planned.isEmpty() && tripsAvailable && this.strategyPage == 0) this.strategyPage = 1;
         if (!tripsAvailable) this.strategyPage = 0;
         if (this.strategyPage > 0) {
            this.renderTripKhususPage(card);
            return;
         }
         this.restoreStrategyChrome();

         if (planned.isEmpty()) {
            card.setVisibility(8);
            this.stopStrategyBlink();
         } else {
            SyncSettings cfg = this.syncCfg();
            int maxLoad = cfg.getMaxLoad();
            double[] depot = branchCenter(cfg);
            List<DeliveryPlanner.Trip> trips = DeliveryPlanner.plan(planned, maxLoad, depot[0], depot[1], 0);
            if (trips.isEmpty()) {
               card.setVisibility(8);
               this.stopStrategyBlink();
            } else {
               this.strategyTrips = trips;
               // Kurir terpandu tak pernah menekan "Jalankan" sendiri — begitu ada rit dan belum
               // ada yang berjalan, rit pertama langsung dimulai otomatis.
               if (this.guidedMode && !this.isRunning()) {
                  this.autoStartGuidedTrip(trips.get(0));
               }
               DeliveryPlanner.Trip first = (DeliveryPlanner.Trip)trips.get(0);
               ((TextView)this.findViewById(id.tvStrategyTitle)).setText("\ud83d\ude9a Strategi Pengiriman — Rit 1 dari " + trips.size()
                     + (tripsAvailable ? "  · 1/" + (this.tripPages.size() + 1) + " ⇆" : ""));
               String muat = "Muat " + first.galon + " galon" + (maxLoad > 0 ? " dari " + maxLoad : "") + " · " + first.stops.size() + " pemberhentian";
               if (maxLoad <= 0) {
                  muat = muat + "\n⚠️ Muatan Max belum diatur admin — seluruh antrean dianggap satu rit.";
               } else if (first.overflow) {
                  muat = muat + "\n⚠️ Order ini sendiri melebihi muatan max.";
               }

               ((TextView)this.findViewById(id.tvStrategySummary)).setText(muat);
               this.bindStrategyItemBadges(first);
               this.bindStrategyStops(first, 4);
               // Rit sedang berjalan → kartu strategi tak relevan (applyRunMode menyembunyikannya;
               // jangan dimunculkan lagi oleh render ulang dari sinkron / Trip Khusus).
               boolean hideCard = this.strategyHidden || this.isRunning();
               card.setVisibility(hideCard ? 8 : 0);
               this.applyStrategyCollapsed();
               if (hideCard) {
                  this.stopStrategyBlink();
               } else {
                  this.startStrategyBlink(card);
               }
            }
         }
      }
   }

   // ------------------------------------------------------------------ 🧭 Trip Khusus (kartu geser)

   /** Geser kiri/kanan pada kartu strategi = pindah halaman Strategi ⇄ Trip Khusus. Klik biasa tetap jalan. */
   private void setupStrategySwipe() {
      final int minDx = this.dp(60f);
      final int slop = android.view.ViewConfiguration.get(this).getScaledTouchSlop();
      // Kartu IKUT jari saat digeser (seperti kartu sungguhan) + efek NYALA: tepi searah geseran
      // berpendar & kartu terangkat. Lepas melewati ambang → pindah halaman; kurang → kembali memantul.
      final float[] down = new float[2];
      final boolean[] dragging = {false};
      View.OnTouchListener l = (v, ev) -> {
         if (this.guidedMode || this.tripPages.isEmpty()) return false;
         View content = this.strategyContent();
         if (content == null) return false;
         switch (ev.getActionMasked()) {
            case android.view.MotionEvent.ACTION_DOWN:
               down[0] = ev.getRawX();
               down[1] = ev.getRawY();
               dragging[0] = false;
               return false;
            case android.view.MotionEvent.ACTION_MOVE: {
               float dx = ev.getRawX() - down[0];
               float dy = ev.getRawY() - down[1];
               if (!dragging[0]) {
                  if (Math.abs(dx) < slop || Math.abs(dx) < Math.abs(dy) * 1.5f) return false;
                  dragging[0] = true;
                  content.animate().cancel();
                  v.cancelLongPress();
                  v.setPressed(false);
                  if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(true);
               }
               int dir = dx < 0 ? 1 : -1;
               int next = this.strategyPage + dir;
               boolean edge = next < 0 || next > this.tripPages.size();
               // Ujung halaman: tarikan "karet" (lebih berat), tanda tak ada halaman lagi.
               content.setTranslationX(dx * (edge ? 0.25f : 0.85f));
               float w = Math.max(1f, content.getWidth());
               float p = Math.min(1f, Math.abs(dx) / (w * 0.5f));
               this.applySwipeGlow(content, p, dx < 0, edge);
               return true;
            }
            case android.view.MotionEvent.ACTION_UP:
            case android.view.MotionEvent.ACTION_CANCEL: {
               if (!dragging[0]) return false;
               dragging[0] = false;
               float dx = ev.getRawX() - down[0];
               this.applySwipeGlow(content, 0f, dx < 0, false);
               boolean flipped = ev.getActionMasked() == android.view.MotionEvent.ACTION_UP
                     && Math.abs(dx) >= minDx && this.canFlipStrategy(dx < 0 ? 1 : -1)
                     && this.flipStrategyPage(dx < 0 ? 1 : -1);
               if (!flipped) {
                  content.animate().translationX(0f).alpha(1f).setDuration(260)
                        .setInterpolator(new android.view.animation.OvershootInterpolator(1.6f)).start();
               }
               return true;
            }
            default:
               return dragging[0];
         }
      };
      for (int vid : new int[]{id.cardStrategy, id.strategyHeader, id.strategyBody, id.strategyStopsList, id.tvStrategySummary}) {
         View v = this.findViewById(vid);
         if (v != null) v.setOnTouchListener(l);
      }
      // Baris badge (HorizontalScrollView) menelan ACTION_DOWN, jadi geseran di atasnya tak pernah sampai
      // ke kartu. Geser halaman dari baris ini juga — KECUALI barisnya sendiri masih bisa digulir ke arah
      // jari (badge terlalu banyak): gulir badge dulu, baru di ujungnya kartu ikut berpindah halaman.
      View badgeScroll = this.findViewById(id.strategyItemBadgesScroll);
      if (badgeScroll != null) badgeScroll.setOnTouchListener((v, ev) -> {
         if (ev.getActionMasked() == android.view.MotionEvent.ACTION_MOVE && !dragging[0]) {
            float dx = ev.getRawX() - down[0];
            if (dx != 0f && v.canScrollHorizontally(dx < 0 ? 1 : -1)) return false;
         }
         return l.onTouch(v, ev);
      });
   }

   private boolean canFlipStrategy(int delta) {
      int next = this.strategyPage + delta;
      return !this.guidedMode && !this.tripPages.isEmpty() && next >= 0 && next <= this.tripPages.size();
   }

   /**
    * Efek nyala saat kartu digeser: pendar biru dari tepi searah geseran (kuat = jauh), kartu
    * sedikit terangkat. {@code p} 0..1; 0 = padam (pendar memudar sendiri). Di ujung halaman pendar
    * berwarna jingga — tanda tak bisa digeser lagi.
    */
   private void applySwipeGlow(View content, float p, boolean towardLeft, boolean edge) {
      MaterialCardView card = (MaterialCardView) this.findViewById(id.cardStrategy);
      if (card == null) return;
      if (p <= 0f) {
         android.graphics.drawable.Drawable fg = content.getForeground();
         if (fg != null) {
            ValueAnimator fade = ValueAnimator.ofInt(fg.getAlpha(), 0);
            fade.setDuration(220);
            fade.addUpdateListener((a) -> fg.setAlpha((Integer) a.getAnimatedValue()));
            fade.addListener(new android.animation.AnimatorListenerAdapter() {
               @Override
               public void onAnimationEnd(android.animation.Animator animation) {
                  content.setForeground(null);
               }
            });
            fade.start();
         }
         card.animate().translationZ(0f).scaleX(1f).scaleY(1f).setDuration(220).start();
         return;
      }
      int glow = edge ? 0xFFF59E0B : 0xFF38BDF8;
      android.graphics.drawable.GradientDrawable g = content.getForeground() instanceof android.graphics.drawable.GradientDrawable
            ? (android.graphics.drawable.GradientDrawable) content.getForeground() : null;
      if (g == null) {
         g = new android.graphics.drawable.GradientDrawable();
         g.setCornerRadius(this.dp(12f));
         content.setForeground(g);
      }
      // Pendar dari tepi DEPAN (searah geseran) memudar ke tengah kartu.
      g.setOrientation(towardLeft ? android.graphics.drawable.GradientDrawable.Orientation.RIGHT_LEFT
            : android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT);
      g.setColors(new int[]{(glow & 0x00FFFFFF) | 0x66000000, (glow & 0x00FFFFFF) | 0x14000000, 0x00000000});
      g.setAlpha(Math.round(255 * Math.min(1f, 0.35f + p)));
      card.setTranslationZ(this.dp(6f) * p);
      float sc = 1f + 0.015f * p;
      card.setScaleX(sc);
      card.setScaleY(sc);
   }

   private boolean flipStrategyPage(int delta) {
      if (this.guidedMode || this.tripPages.isEmpty()) return false;
      int pages = this.tripPages.size() + 1;
      int next = this.strategyPage + delta;
      View content = this.strategyContent();
      if (next < 0 || next >= pages) {
         // Ujung halaman: pantulan kecil — tanda tak ada halaman lagi ke arah itu.
         if (content != null) {
            content.animate().cancel();
            content.animate().translationX(-delta * this.dp(24f)).setDuration(90)
                  .withEndAction(() -> content.animate().translationX(0f).setDuration(160)
                        .setInterpolator(new android.view.animation.OvershootInterpolator(2f)).start())
                  .start();
         }
         return true;
      }
      this.strategyPage = next;
      // Baris badge dipakai bersama semua halaman: halaman baru selalu mulai dari badge pertama.
      View badgeScroll = this.findViewById(id.strategyItemBadgesScroll);
      if (badgeScroll != null) badgeScroll.scrollTo(0, 0);
      if (content == null || content.getWidth() == 0) {
         this.renderStrategy(this.adapter != null ? this.adapter.data : null);
         return true;
      }
      // Geser keluar searah jari + memudar, ganti isi, lalu masuk dari sisi seberang.
      float w = content.getWidth();
      content.animate().cancel();
      content.animate().translationX(-delta * w * 0.6f).alpha(0f).setDuration(140)
            .setInterpolator(new android.view.animation.AccelerateInterpolator())
            .withEndAction(() -> {
               if (this.isFinishing() || this.isDestroyed()) return;
               this.renderStrategy(this.adapter != null ? this.adapter.data : null);
               content.setTranslationX(delta * w * 0.6f);
               content.animate().translationX(0f).alpha(1f).setDuration(220)
                     .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
            }).start();
      return true;
   }

   /** Isi kartu strategi (anak pertama MaterialCardView) — yang dianimasikan, bingkai kartu diam. */
   private View strategyContent() {
      View card = this.findViewById(id.cardStrategy);
      return card instanceof android.view.ViewGroup && ((android.view.ViewGroup) card).getChildCount() > 0
            ? ((android.view.ViewGroup) card).getChildAt(0) : null;
   }

   /** Ambil daftar Trip Khusus berjalan dari server (latar). Gagal/offline → halaman trip tak tampil. */
   private void loadTrips() {
      SyncSettings cfg = this.syncCfg();
      if (this.guidedMode || !cfg.isEnrolled() || this.tripsLoading) return;
      this.tripsLoading = true;
      new Thread(() -> {
         List<JSONObject> out = null;
         try {
            JSONArray arr = new SyncApi(cfg).deliveryTrips().optJSONArray("trips");
            out = new ArrayList<>();
            if (arr != null) {
               for (int i = 0; i < arr.length(); i++) {
                  JSONObject t = arr.optJSONObject(i);
                  JSONArray orders = t != null ? t.optJSONArray("orders") : null;
                  if (orders != null && orders.length() > 0) out.add(t);
               }
            }
         } catch (Exception ignored) {
            // server lama (404) / offline — kartu tetap Strategi Pengiriman saja
         }
         final List<JSONObject> res = out;
         this.runOnUiThread(() -> {
            this.tripsLoading = false;
            if (this.isFinishing() || this.isDestroyed() || res == null) return;
            long cur = this.strategyPage > 0 && this.strategyPage <= this.tripPages.size()
                  ? this.tripPages.get(this.strategyPage - 1).optLong("id") : -1L;
            this.tripPages.clear();
            this.tripPages.addAll(res);
            // Tetap di trip yang sama bila masih ada; trip hilang → kembali ke Strategi.
            if (cur > 0) {
               int idx = 0;
               for (int i = 0; i < res.size(); i++) if (res.get(i).optLong("id") == cur) idx = i + 1;
               this.strategyPage = idx;
            }
            this.renderStrategy(this.adapter != null ? this.adapter.data : null);
         });
      }).start();
   }

   /** Kembalikan elemen kartu yang diubah halaman trip (dipanggil saat halaman 0 dirender). */
   private void restoreStrategyChrome() {
      View refresh = this.findViewById(id.btnRefreshRit);
      if (refresh != null) refresh.setVisibility(View.VISIBLE);
      View sumView = this.findViewById(id.tvStrategySummary);
      if (sumView != null) sumView.setVisibility(View.VISIBLE);   // halaman trip bisa menyembunyikannya
      TextView all = (TextView) this.findViewById(id.tvStrategyAll);
      if (all != null) all.setText("Ketuk untuk lihat seluruh rit →");
   }

   private JSONObject currentTripPage() {
      int i = this.strategyPage - 1;
      return i >= 0 && i < this.tripPages.size() ? this.tripPages.get(i) : null;
   }

   private static String tripSourceLabel(JSONObject o) {
      String src = o.optString("source", "");
      if ("mine".equals(src)) return "Saya";
      if ("open".equals(src)) return "Terbuka";
      String dev = strJson(o, "device_label");
      return dev != null && !dev.isEmpty() ? dev : "HP lain";
   }

   private static int tripSourceColor(JSONObject o) {
      String src = o.optString("source", "");
      if ("mine".equals(src)) return 0xFF15803D;
      if ("open".equals(src)) return 0xFFB45309;
      return 0xFF475569;
   }

   private void renderTripKhususPage(MaterialCardView card) {
      JSONObject t = this.currentTripPage();
      if (t == null) {
         this.strategyPage = 0;
         this.renderStrategy(this.adapter != null ? this.adapter.data : null);
         return;
      }
      int pages = this.tripPages.size() + 1;
      ((TextView) this.findViewById(id.tvStrategyTitle)).setText("🧭 Trip Khusus — " + safe(t.optString("name", "Trip"))
            + "  · " + (this.strategyPage + 1) + "/" + pages + " ⇆");
      String when = t.optBoolean("runs_today") ? "✅ Jadwal hari ini"
            : (strJson(t, "next_date") != null && !strJson(t, "next_date").isEmpty() ? "📅 Jadwal berikutnya " + strJson(t, "next_date") : "");
      String days = strJson(t, "days_label");
      StringBuilder sum = new StringBuilder();
      if (days != null && !days.isEmpty()) sum.append("Hari antar: ").append(days);
      if (!when.isEmpty()) sum.append(sum.length() > 0 ? " · " : "").append(when);
      TextView tvSum = (TextView) this.findViewById(id.tvStrategySummary);
      tvSum.setText(sum.toString());
      tvSum.setVisibility(sum.length() > 0 ? View.VISIBLE : View.GONE);
      // Ganti baris hitungan "N galon · Saya · HP lain · Terbuka" + keterangan ▶ dengan TOTAL produk
      // yang harus dimuat untuk trip ini ("MIN ×13  BIO ×2") — baris badge yang sama dgn halaman Strategi.
      JSONArray tripOrders = this.tripOrdersOfKind(t.optJSONArray("orders"));
      this.bindTripTotalBadges(tripOrders);
      this.appendTripKindBadge();
      View refresh = this.findViewById(id.btnRefreshRit);
      if (refresh != null) refresh.setVisibility(View.GONE);
      TextView all = (TextView) this.findViewById(id.tvStrategyAll);
      if (all != null) all.setText("Ketuk untuk lihat seluruh order trip →");
      this.bindTripOrders(tripOrders, 4);
      boolean hideCard = this.strategyHidden || this.isRunning();
      card.setVisibility(hideCard ? View.GONE : View.VISIBLE);
      this.applyStrategyCollapsed();
      if (hideCard) this.stopStrategyBlink();
      else this.startStrategyBlink(card);
   }

   /**
    * Total produk satu Trip Khusus, digabung per slug ("MIN ×13  BIO ×2"). Sumber utama
    * {@code item_slugs} server (kanonik ProductCanon, sudah menggabung baris promo); APK baru di server
    * lama jatuh ke teks {@code items}. Order tanpa rincian produk (hanya "N galon") dijumlah ke satu
    * badge abu "Galon ×N" supaya total tetap utuh; AMBIL GALON SAJA dilewati (tak dimuat berangkat).
    */
   private LinkedHashMap<String, int[]> tripTotalItems(JSONArray orders) {
      LinkedHashMap<String, int[]> total = new LinkedHashMap<>();
      int unknownGalon = 0;
      for (int i = 0; orders != null && i < orders.length(); i++) {
         JSONObject o = orders.optJSONObject(i);
         if (o == null || o.optBoolean("pickup_only")) continue;
         boolean counted = false;
         // Dipecah per jenis galon ("MIN ×15 [KERAN]" + "MIN ×3 [NON-KERAN]") — yang harus dimuat berangkat.
         String gt = strJson(o, "galon_type");
         boolean typed = GalonTypeUi.known(gt);
         Map<String, LinkedHashMap<String, Integer>> spec = typed && GalonTypeUi.CAMPUR.equals(gt)
               ? GalonTypeUi.specBySlug(o.opt("galon_spec")) : new java.util.HashMap<>();
         JSONArray slugs = o.optJSONArray("item_slugs");
         for (int k = 0; slugs != null && k < slugs.length(); k++) {
            JSONObject it = slugs.optJSONObject(k);
            String slug = it != null ? strJson(it, "slug") : null;
            int qty = it != null ? (int) Math.round(it.optDouble("qty", 0)) : 0;
            if (slug == null || slug.trim().isEmpty() || qty <= 0) continue;
            int color = this.slugColor(slug.trim(), strJson(it, "color"));
            LinkedHashMap<String, Integer> rows = spec.get(slug.trim().toUpperCase(Locale.US));
            if (rows != null && !rows.isEmpty()) {
               for (Map.Entry<String, Integer> r : rows.entrySet()) addTripChip(total, typedKey(slug.trim(), r.getKey()), r.getValue(), color);
            } else {
               addTripChip(total, typedKey(slug.trim(), typed ? gt : ""), qty, color);
            }
            counted = true;
         }
         if (!counted) {
            for (Object[] r : this.typedRows(o)) {
               if ((Integer) r[2] <= 0) continue;   // label tanpa angka ("1 galon") = produk tak diketahui
               addTripChip(total, typedKey((String) r[0], (String) r[1]), (Integer) r[2], (Integer) r[3]);
               counted = true;
            }
         }
         if (!counted) unknownGalon += Math.max(0, o.optInt("galon"));
      }
      if (unknownGalon > 0) addTripChip(total, "Galon", unknownGalon, 0xFF64748B);
      return total;
   }

   private static void addTripChip(LinkedHashMap<String, int[]> total, String label, int qty, int color) {
      int[] v = total.get(label);
      if (v == null) total.put(label, new int[]{qty, color});
      else v[0] += qty;
   }

   /** Warna kapsul slug: warna server bila sah, else warna produk lokal ber-slug sama, else palet. */
   private int slugColor(String slug, @Nullable String hex) {
      if (hex != null && !hex.trim().isEmpty()) {
         try {
            return Color.parseColor(hex.trim());
         } catch (IllegalArgumentException ignored) {
         }
      }
      for (Product p : this.productByName.values()) {
         if (p != null && slug.equalsIgnoreCase(p.getSlug() != null ? p.getSlug().trim() : "")
               && p.getColor() != null && !p.getColor().trim().isEmpty()) {
            try {
               return Color.parseColor(p.getColor().trim());
            } catch (IllegalArgumentException ignored) {
            }
         }
      }
      return TransactionAdapter.paletteColor(slug);
   }

   /** Jenis order trip yang ditampilkan & dijalankan ▶ — badge dropdown di kartu trip (bawaan Penjualan saja). */
   private boolean tripKindSales = true;
   private boolean tripKindPickup = false;

   private JSONArray tripOrdersOfKind(JSONArray orders) {
      JSONArray out = new JSONArray();
      for (int i = 0; orders != null && i < orders.length(); i++) {
         JSONObject o = orders.optJSONObject(i);
         if (o == null) continue;
         if (o.optBoolean("pickup_only") ? this.tripKindPickup : this.tripKindSales) out.put(o);
      }
      return out;
   }

   private void appendTripKindBadge() {
      LinearLayout row = (LinearLayout) this.findViewById(id.strategyItemBadges);
      View scroll = this.findViewById(id.strategyItemBadgesScroll);
      if (row == null || scroll == null) return;
      String label = this.tripKindSales && this.tripKindPickup ? "🛒 Penjualan + ↩ Ambil Galon"
            : (this.tripKindPickup ? "↩ Ambil Galon" : "🛒 Penjualan");
      TextView b = this.makeDetailBadge(label + " ▾", 0xFF6D28D9);
      b.setOnClickListener(v -> {
         boolean[] on = new boolean[]{this.tripKindSales, this.tripKindPickup};
         (new AlertDialog.Builder(this)).setTitle("Jenis order trip")
               .setMultiChoiceItems(new String[]{"🛒 Penjualan", "↩ Ambil Galon Saja"}, on, (d, i, chk) -> on[i] = chk)
               .setPositiveButton("Terapkan", (d, w) -> {
                  if (!on[0] && !on[1]) {
                     Toast.makeText(this, "Pilih minimal satu jenis.", Toast.LENGTH_SHORT).show();
                     return;
                  }
                  this.tripKindSales = on[0];
                  this.tripKindPickup = on[1];
                  MaterialCardView card = (MaterialCardView) this.findViewById(id.cardStrategy);
                  if (card != null) this.renderTripKhususPage(card);
               })
               .setNegativeButton("Batal", (DialogInterface.OnClickListener) null).show();
      });
      row.addView(b);
      scroll.setVisibility(View.VISIBLE);
   }

   private void bindTripTotalBadges(JSONArray orders) {
      View scroll = this.findViewById(id.strategyItemBadgesScroll);
      LinearLayout row = (LinearLayout) this.findViewById(id.strategyItemBadges);
      if (scroll == null || row == null) return;
      LinkedHashMap<String, int[]> items = this.tripTotalItems(orders);
      row.removeAllViews();
      for (Map.Entry<String, int[]> e : items.entrySet()) {
         row.addView(this.makeDetailBadge(typedTotalText(e.getKey(), e.getValue()[0], e.getValue()[1]), e.getValue()[1]));
      }
      scroll.setVisibility(items.isEmpty() ? View.GONE : View.VISIBLE);
   }

   private void bindTripOrders(JSONArray orders, int max) {
      LinearLayout list = (LinearLayout) this.findViewById(id.strategyStopsList);
      TextView more = (TextView) this.findViewById(id.tvStrategyStops);
      if (list == null) return;
      list.removeAllViews();
      int total = orders != null ? orders.length() : 0;
      int n = 0;
      int textColor = ContextCompat.getColor(this, R.color.text_primary);
      for (int i = 0; i < total; i++) {
         if (max > 0 && n >= max) break;
         JSONObject o = orders.optJSONObject(i);
         if (o == null) continue;
         list.addView(this.tripOrderRow(o, n + 1, textColor));
         n++;
      }
      if (more != null) {
         int rest = total - n;
         more.setText(rest > 0 ? "+" + rest + " order lagi…" : "");
         more.setVisibility(rest > 0 ? View.VISIBLE : View.GONE);
      }
   }

   private View tripOrderRow(JSONObject o, int no, int textColor) {
      LinearLayout row = new LinearLayout(this);
      row.setOrientation(LinearLayout.HORIZONTAL);
      row.setGravity(android.view.Gravity.CENTER_VERTICAL);
      row.setPadding(0, this.dp(2f), 0, this.dp(2f));
      TextView name = new TextView(this);
      name.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
      name.setText(no + ". " + (o.optBoolean("is_priority") ? "⚡ " : "") + safe(strJson(o, "name")));
      name.setTextColor(textColor);
      name.setTextSize(13f);
      name.setMaxLines(1);
      name.setEllipsize(TruncateAt.END);
      row.addView(name);
      row.addView(this.makeDetailBadge(tripSourceLabel(o), tripSourceColor(o)));
      // Badge produk ×jumlah (seperti kartu Strategi); tanpa rincian item → total galon.
      List<Object[]> chips = this.typedRows(o);
      if (chips.isEmpty()) {
         row.addView(this.makeDetailBadge(o.optInt("galon") + " gal", 0xFF0369A1));
      }
      boolean assumed = galonAssumed(strJson(o, "galon_type_source"));
      for (Object[] r : chips) {
         row.addView(this.makeDetailBadge(typedChipText(r, assumed), (Integer) r[3]));
      }
      return row;
   }

   /** Daftar lengkap order satu trip (dialog). */
   private void showFullTripKhusus() {
      JSONObject t = this.currentTripPage();
      if (t == null) return;
      JSONArray orders = t.optJSONArray("orders");
      LinearLayout box = new LinearLayout(this);
      box.setOrientation(LinearLayout.VERTICAL);
      int pad = this.dp(20f);
      box.setPadding(pad, this.dp(8f), pad, 0);
      int textColor = ContextCompat.getColor(this, R.color.text_primary);
      for (int i = 0; orders != null && i < orders.length(); i++) {
         JSONObject o = orders.optJSONObject(i);
         if (o != null) box.addView(this.tripOrderRow(o, i + 1, textColor));
      }
      ScrollView sv = new ScrollView(this);
      sv.addView(box);
      (new AlertDialog.Builder(this)).setTitle("🧭 " + safe(t.optString("name", "Trip")) + " (" + (orders != null ? orders.length() : 0) + " order)")
            .setView(sv).setPositiveButton("Tutup", (DialogInterface.OnClickListener) null)
            .setNeutralButton("▶ Mulai Trip", (d, w) -> this.confirmStartTripKhusus()).show();
   }

   /** ▶ pada halaman trip: konfirmasi dua ketuk → server melepas & mengambil alih sekaligus. */
   private void confirmStartTripKhusus() {
      JSONObject t0 = this.currentTripPage();
      if (t0 == null) return;
      // Dialog MUATAN: jumlah galon per produk & jenis (bawaan semua), Ambil Galon Saja mengikuti badge jenis kartu trip.
      JSONArray orders = t0.optJSONArray("orders");
      List<Object[]> cands = new ArrayList<>();
      for (int i = 0; orders != null && i < orders.length(); i++) {
         JSONObject o = orders.optJSONObject(i);
         if (o != null) cands.add(new Object[]{o, this.typedRows(o), o.optBoolean("pickup_only", false)});
      }
      this.askRunLoad("▶ Mulai Trip " + safe(t0.optString("name", "Trip")), cands, this.tripKindPickup,
            picked -> {
               boolean[] kinds = this.setTripPicked(picked);
               this.confirmStartTripKhusus(kinds[0], kinds[1]);
            },
            "➕ Ke Antrean", picked -> {
               boolean[] kinds = this.setTripPicked(picked);
               this.askTripAddOrReplace(kinds[0], kinds[1]);
            });
   }

   /** Simpan uuid order hasil dialog muatan → {adaPenjualan, adaAmbilSaja}. */
   private boolean[] setTripPicked(List<Object> picked) {
      java.util.Set<String> uuids = new java.util.LinkedHashSet<>();
      boolean[] kinds = {false, false};
      for (Object o : picked) {
         JSONObject j = (JSONObject) o;
         uuids.add(j.optString("uuid"));
         if (j.optBoolean("pickup_only", false)) kinds[1] = true;
         else kinds[0] = true;
      }
      this.tripPickedUuids = uuids;
      return kinds;
   }

   /**
    * "➕ Ke Antrean": TAMBAHKAN = order trip terpilih diambil alih ke Antrean Saya, antrean yang ada tetap
    * (server keep_queue); GANTIKAN = alur Mulai Trip biasa (order di luar trip dilepas ke Pesanan Terbuka).
    */
   private void askTripAddOrReplace(boolean includeSales, boolean includePickup) {
      JSONObject t = this.currentTripPage();
      if (t == null) return;
      SyncSettings cfg = this.syncCfg();
      if (!cfg.isEnrolled() || !cfg.isDeliveryDevice()) {
         this.confirmStartTripKhusus(includeSales, includePickup);   // pesan gerbangnya ada di sana
         return;
      }
      int n = this.tripPickedUuids != null ? this.tripPickedUuids.size() : 0;
      (new AlertDialog.Builder(this)).setTitle("➕ " + n + " order ke Antrean Saya")
            .setMessage("• TAMBAHKAN — order terpilih masuk ke Antrean Saya, antrean yang sudah ada tetap."
                  + "\n• GANTIKAN — order terpilih masuk, order Antrean Saya di luar trip dilepas menjadi Pesanan Terbuka (yang sedang diantar tidak dilepas).")
            .setPositiveButton("➕ Tambahkan", (d, w) -> {
               android.app.ProgressDialog wait = new android.app.ProgressDialog(this);
               wait.setMessage("Menambahkan ke antrean…");
               wait.setCancelable(false);
               wait.show();
               this.runStartTripKhusus(wait, t.optLong("id"), includeSales, includePickup, true);
            })
            .setNeutralButton("🔁 Gantikan", (d, w) -> this.confirmStartTripKhusus(includeSales, includePickup))
            .setNegativeButton("Batal", (DialogInterface.OnClickListener) null).show();
   }

   /** uuid order trip hasil dialog muatan (null = semua order trip berjenis terpilih). */
   private java.util.Set<String> tripPickedUuids = null;

   private void confirmStartTripKhusus(boolean includeSales, boolean includePickup) {
      JSONObject t = this.currentTripPage();
      if (t == null) return;
      SyncSettings cfg = this.syncCfg();
      if (!cfg.isEnrolled()) {
         Toast.makeText(this, "Perangkat belum terhubung ke server.", Toast.LENGTH_LONG).show();
         return;
      }
      if (!cfg.isDeliveryDevice()) {
         Toast.makeText(this, "Hanya perangkat delivery yang bisa menjalankan trip.", Toast.LENGTH_LONG).show();
         return;
      }
      JSONArray orders = t.optJSONArray("orders");
      java.util.Set<String> inTrip = new java.util.HashSet<>();
      int take = 0;
      for (int i = 0; orders != null && i < orders.length(); i++) {
         JSONObject o = orders.optJSONObject(i);
         if (o == null) continue;
         inTrip.add(o.optString("uuid"));
         boolean kindOk = o.optBoolean("pickup_only") ? includePickup : includeSales;
         boolean pickedOk = this.tripPickedUuids == null || this.tripPickedUuids.contains(o.optString("uuid"));
         if (kindOk && pickedOk && !"mine".equals(o.optString("source"))) take++;
      }
      int release = 0;
      if (this.adapter != null && this.adapter.data != null) {
         for (Transaction q : this.adapter.data) {
            if (q == null || q.isOpenDispatch() || this.runningIds.contains(q.getId())) continue;
            String u = this.dao.getSyncUuidById(q.getId());
            if (u == null || !inTrip.contains(u)) release++;
         }
      }
      String name = safe(t.optString("name", "Trip"));
      String msg = "• Ambil alih " + take + " order (perangkat lain & Pesanan Terbuka) ke Antrean Saya."
            + "\n• Lepas sekitar " + release + " order Antrean Saya di luar trip menjadi Pesanan Terbuka (yang sedang diantar tidak dilepas)."
            + "\n\nKetuk \"Mulai\" dua kali untuk memastikan.";
      AlertDialog dialog = (new AlertDialog.Builder(this)).setTitle("Mulai Trip " + name + "?").setMessage(msg)
            .setPositiveButton("Mulai", (DialogInterface.OnClickListener) null)
            .setNegativeButton("Batal", (DialogInterface.OnClickListener) null).create();
      dialog.setOnShowListener((di) -> {
         Button pos = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
         Button neg = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
         final boolean[] armed = {false};
         pos.setOnClickListener((v) -> {
            if (!armed[0]) {
               armed[0] = true;
               pos.setText("Ketuk sekali lagi");
               return;
            }
            pos.setEnabled(false);
            neg.setEnabled(false);
            pos.setText("Memproses…");
            this.runStartTripKhusus(dialog, t.optLong("id"), includeSales, includePickup);
         });
      });
      dialog.show();
   }

   private void runStartTripKhusus(android.app.Dialog dialog, long ruleId, boolean includeSales, boolean includePickup) {
      this.runStartTripKhusus(dialog, ruleId, includeSales, includePickup, false);
   }

   private void runStartTripKhusus(android.app.Dialog dialog, long ruleId, boolean includeSales, boolean includePickup, boolean keepQueue) {
      SyncSettings cfg = this.syncCfg();
      new Thread(() -> {
         String msg;
         boolean ok = false;
         try {
            JSONObject r = new SyncApi(cfg).startDeliveryTrip(ruleId, includeSales, includePickup, this.tripPickedUuids, keepQueue);
            ok = true;
            msg = r.optString("message", "Trip dimulai.");
         } catch (SyncApi.SyncException se) {
            String m = se.serverMessage();
            msg = m != null && !m.isEmpty() ? m : "Gagal memulai trip (kode " + se.code + ").";
         } catch (Exception e) {
            msg = "Gagal memulai trip — periksa koneksi internet.";
         }
         final String fMsg = msg;
         final boolean fOk = ok;
         this.runOnUiThread(() -> {
            if (this.isFinishing() || this.isDestroyed()) return;
            try { dialog.dismiss(); } catch (Exception ignored) {}
            Toast.makeText(this, fMsg, Toast.LENGTH_LONG).show();
            if (fOk) {
               // Order trip masuk lewat tarikan sinkron; kartu kembali ke Strategi (rit dari antrean baru).
               this.strategyPage = 0;
               com.crowja.damiupos.sync.SyncScheduler.syncNow(this.getApplicationContext());
            }
            this.loadData();
            this.loadOtherDevices();
            this.loadTrips();
         });
      }).start();
   }

   /**
    * Total produk yang DIMUAT untuk satu rit, digabung per kapsul (label slug) — order "ambil galon
    * saja" (KEMBALI) tak ikut: tak ada barang yang dibawa berangkat untuk mereka.
    */
   private LinkedHashMap<String, int[]> tripLoadItems(DeliveryPlanner.Trip trip) {
      LinkedHashMap<String, int[]> total = new LinkedHashMap<>();
      for (Transaction s : trip.stops) {
         if (DeliveryPlanner.isPickupOnly(s)) continue;
         for (Object[] r : this.typedRows(s)) {
            String key = typedKey((String) r[0], (String) r[1]);
            int[] v = total.get(key);
            if (v == null) total.put(key, new int[]{(Integer) r[2], (Integer) r[3]});
            else v[0] += (Integer) r[2];
         }
      }
      return total;
   }

   /** Baris badge rincian item rit berikutnya di kartu Strategi Pengiriman ("MIN ×10  BIO ×2"). */
   private void bindStrategyItemBadges(DeliveryPlanner.Trip trip) {
      View scroll = this.findViewById(id.strategyItemBadgesScroll);
      LinearLayout row = (LinearLayout) this.findViewById(id.strategyItemBadges);
      if (scroll == null || row == null) return;
      LinkedHashMap<String, int[]> items = this.tripLoadItems(trip);
      row.removeAllViews();
      for (Map.Entry<String, int[]> e : items.entrySet()) {
         row.addView(this.makeDetailBadge(typedTotalText(e.getKey(), e.getValue()[0], e.getValue()[1]), e.getValue()[1]));
      }
      scroll.setVisibility(items.isEmpty() ? View.GONE : View.VISIBLE);
   }

   /**
    * Pemberhentian rit di kartu Strategi: "1. ⚡ Nama" + badge produk per pelanggan (slug, kapsul
    * yang sama dengan kartu antrean) menggantikan teks "· 1 gal". Order tanpa rincian item jatuh ke
    * badge "N gal"; order ambil-saja ke badge "↩ ambil N gal". Sisanya diringkas "+N lagi…".
    */
   private void bindStrategyStops(DeliveryPlanner.Trip trip, int max) {
      LinearLayout list = (LinearLayout) this.findViewById(id.strategyStopsList);
      TextView more = (TextView) this.findViewById(id.tvStrategyStops);
      if (list == null) {
         if (more != null) more.setText(this.tripLines(trip, max));
         return;
      }
      list.removeAllViews();
      int textColor = ContextCompat.getColor(this, R.color.text_primary);
      int n = 0;
      for (Transaction s : trip.stops) {
         if (max > 0 && n >= max) break;
         LinearLayout row = new LinearLayout(this);
         row.setOrientation(LinearLayout.HORIZONTAL);
         row.setGravity(android.view.Gravity.CENTER_VERTICAL);
         row.setPadding(0, this.dp(2f), 0, this.dp(2f));

         TextView name = new TextView(this);
         name.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
         name.setText((n + 1) + ". " + (DeliveryPlanner.isPriority(s) ? "⚡ " : "") + safe(s.getCustomerName())
               + (DeliveryPlanner.hasGeo(s) ? "" : " 📍❌"));
         name.setTextColor(textColor);
         name.setTextSize(13f);
         name.setMaxLines(1);
         name.setEllipsize(TruncateAt.END);
         row.addView(name);

         LinearLayout badges = new LinearLayout(this);
         badges.setOrientation(LinearLayout.HORIZONTAL);
         if (DeliveryPlanner.isPickupOnly(s)) {
            badges.addView(this.makeDetailBadge("↩ ambil " + s.getJumlahGalon() + " gal", 0xFF64748B));
         } else {
            List<Object[]> items = this.typedRows(s);
            if (items.isEmpty()) {
               badges.addView(this.makeDetailBadge(s.getJumlahGalon() + " gal", 0xFF64748B));
            }
            boolean assumed = galonAssumed(s.getGalonTypeSource());
            for (Object[] r : items) {
               badges.addView(this.makeDetailBadge(typedChipText(r, assumed), (Integer) r[3]));
            }
         }
         row.addView(badges);
         list.addView(row);
         n++;
      }
      if (more != null) {
         int rest = trip.stops.size() - n;
         more.setText(rest > 0 ? "+" + rest + " pemberhentian lagi…" : "");
         more.setVisibility(rest > 0 ? View.VISIBLE : View.GONE);
      }
   }

   /** Teks "Muat: MIN ×10 · BIO ×2" untuk daftar seluruh rit. */
   private String tripLoadText(DeliveryPlanner.Trip trip) {
      StringBuilder sb = new StringBuilder();
      for (Map.Entry<String, int[]> e : this.tripLoadItems(trip).entrySet()) {
         if (sb.length() > 0) sb.append(" · ");
         sb.append(typedTotalPlain(e.getKey(), e.getValue()[0]));
      }
      return sb.toString();
   }

   private String tripLines(DeliveryPlanner.Trip t, int max) {
      StringBuilder sb = new StringBuilder();
      int n = 0;

      for(Transaction s : t.stops) {
         if (max > 0 && n >= max) {
            sb.append("\n+").append(t.stops.size() - n).append(" pemberhentian lagi…");
            break;
         }

         if (n > 0) {
            sb.append('\n');
         }

         sb.append(n + 1).append(". ").append(DeliveryPlanner.isPriority(s) ? "⚡ " : "").append(safe(s.getCustomerName())).append(DeliveryPlanner.hasGeo(s) ? "" : " \ud83d\udccd❌").append(" · ").append(DeliveryPlanner.isPickupOnly(s) ? "↩ ambil " : "").append(s.getJumlahGalon()).append(" gal");
         ++n;
      }

      return sb.toString();
   }

   private void showFullStrategy() {
      if (this.strategyTrips != null && !this.strategyTrips.isEmpty()) {
         StringBuilder sb = new StringBuilder();
         int maxLoad = this.syncCfg().getMaxLoad();

         for(DeliveryPlanner.Trip t : this.strategyTrips) {
            if (sb.length() > 0) {
               sb.append("\n\n↩ Balik ke cabang, muat galon lagi…\n\n");
            }

            String load = this.tripLoadText(t);
            sb.append("▶ RIT ").append(t.n).append(" — ").append(t.galon).append(" galon").append(maxLoad > 0 ? " / " + maxLoad : "").append(t.overflow ? "  ⚠️ melebihi muatan" : "").append('\n');
            if (!load.isEmpty()) sb.append("📦 Muat: ").append(load).append('\n');
            sb.append(this.tripLines(t, 0));
         }

         (new AlertDialog.Builder(this)).setTitle("Strategi Pengiriman (" + this.strategyTrips.size() + " rit)").setMessage(sb.toString()).setPositiveButton("Tutup", (DialogInterface.OnClickListener)null).show();
      }
   }

   private SyncSettings syncCfg() {
      return new SyncSettings(new SettingsDao(DatabaseHelper.getInstance(this)));
   }

   private static double[] branchCenter(SyncSettings cfg) {
      try {
         JSONObject o = new JSONObject(cfg.getBranchCenter());
         return new double[]{o.optDouble("lat", (double)0.0F), o.optDouble("lng", (double)0.0F)};
      } catch (Exception var2) {
         return new double[]{(double)0.0F, (double)0.0F};
      }
   }

   private void startStrategyBlink(MaterialCardView card) {
      if (this.strategyBlink == null || !this.strategyBlink.isStarted()) {
         ValueAnimator anim = ValueAnimator.ofArgb(new int[]{-19712, 587182848});
         anim.setDuration(700L);
         anim.setRepeatMode(2);
         anim.setRepeatCount(-1);
         anim.addUpdateListener(com.crowja.damiupos.util.LowPowerBlink.stepped(4, (a) -> card.setStrokeColor((Integer)a.getAnimatedValue())));
         com.crowja.damiupos.util.LowPowerBlink.track(anim).start();
         this.strategyBlink = anim;
      }
   }

   private void stopStrategyBlink() {
      if (this.strategyBlink != null) {
         this.strategyBlink.cancel();
         this.strategyBlink = null;
      }

   }

   private void applyList(List<Transaction> list) {
      if (this.isRunning()) {
         HashSet<Long> alive = new HashSet();

         for(Transaction t : list) {
            alive.add(t.getId());
         }

         List<Long> gone = new ArrayList();

         for(Long id : this.runningIds) {
            if (!alive.contains(id)) {
               gone.add(id);
            }
         }

         if (!gone.isEmpty()) {
            // Dulu dibuang DIAM-DIAM: kurir tetap mengantar order yang di tengah jalan dijadwalkan
            // ulang / dibatalkan / dipindah pihak lain (kasus Heni 2026-10-17). Sekarang alarm + alasan.
            StringBuilder why = new StringBuilder();
            for(Long id : gone) {
               String[] r = this.dao.runGoneReason(id);
               if (r[1] != null) why.append("• ").append(r[0]).append(" — ").append(r[1]).append('\n');
               this.dao.stopDelivery(id);
            }

            this.runningIds.removeAll(gone);
            this.persistRunning();
            SyncScheduler.syncNow(this.getApplicationContext());
            if (why.length() > 0) this.showRunCancelledAlarm(why.toString());
         }
      }

      boolean absorbed = this.absorbPendingRunAdds(list);
      List<Transaction> ordered = this.buildStrategyOrderedList(list);
      this.adapter.setData(ordered);
      this.renderStrategy(ordered);
      this.applyRunModeChrome(list);
      if (absorbed) {
         this.autoRerouteAfterAdd();
      }
      this.setTabCount(0, list.size());
      if (this.selectionMode) {
         if (list.isEmpty()) {
            this.exitSelectionMode();
         } else {
            HashSet<Long> alive = new HashSet();

            for(Transaction t : list) {
               alive.add(t.getId());
            }

            this.selectedIds.retainAll(alive);
            this.updateSelectionUi();
         }
      }

   }

   /**
    * Urutan "Antrian Saya" untuk kartu — BUKAN cuma void-pending di akhir seperti dulu, tapi
    * mengikuti urutan rute optimal ({@link DeliveryPlanner#plan}) yang sama dengan kartu Strategi
    * Pengiriman, supaya urutan yang kurir LIHAT selalu cocok dengan urutan yang disarankan sistem.
    * Order dengan permintaan void tertunda selalu diletakkan di akhir (tak ikut dihitung rute).
    */
   private List<Transaction> buildStrategyOrderedList(List<Transaction> list) {
      if (list == null || list.isEmpty()) return new ArrayList<>();
      List<Transaction> planned = new ArrayList<>();
      List<Transaction> voidPending = new ArrayList<>();
      for (Transaction t : list) {
         (t.hasPendingVoidRequest() ? voidPending : planned).add(t);
      }
      List<Transaction> ordered = new ArrayList<>(list.size());
      if (!planned.isEmpty()) {
         SyncSettings cfg = this.syncCfg();
         int maxLoad = cfg.getMaxLoad();
         double[] depot = branchCenter(cfg);
         for (DeliveryPlanner.Trip trip : DeliveryPlanner.plan(planned, maxLoad, depot[0], depot[1], 0)) {
            ordered.addAll(trip.stops);
         }
      }
      ordered.addAll(voidPending);
      return ordered;
   }

   /** Dipanggil dari {@link #strategyResortTicker} tiap menit — susun ulang urutan rute TANPA
    *  memuat ulang data dari DB, karena hanya posisi kurir yang berubah, bukan isi antreannya. */
   private void reapplyStrategyOrder() {
      if (this.isFinishing() || this.isDestroyed() || this.adapter == null || this.adapter.data == null
            || this.adapter.data.isEmpty()) {
         return;
      }
      this.applyList(new ArrayList<>(this.adapter.data));
   }

   private void refreshProductIndex() {
      Map<String, Product> map = new HashMap();

      try {
         for(Product p : this.productDao.getAll()) {
            String k = normProductName(p.getName());
            if (!k.isEmpty()) {
               map.put(k, p);
            }
         }
      } catch (Exception var5) {
      }

      this.productByName = map;
   }

   private static String normProductName(String s) {
      return s == null ? "" : s.trim().replaceAll("\\s+", " ").toLowerCase(Locale.getDefault());
   }

   private String chipLabel(TransactionItem it) {
      Product p = (Product)this.productByName.get(normProductName(it.productName));
      String slug = p != null ? p.getSlug() : null;
      if (slug != null && !slug.trim().isEmpty()) {
         return slug.trim();
      } else {
         String nm = it.productName != null ? it.productName.trim() : "";
         if (nm.isEmpty()) {
            return "Galon";
         } else {
            return nm.length() <= 10 ? nm : nm.substring(0, 10).trim() + "…";
         }
      }
   }

   private int chipColor(TransactionItem it) {
      Product p = (Product)this.productByName.get(normProductName(it.productName));
      if (p != null && p.getColor() != null && !p.getColor().trim().isEmpty()) {
         try {
            return Color.parseColor(p.getColor().trim());
         } catch (IllegalArgumentException var4) {
         }
      }

      return TransactionAdapter.paletteColor(it.productName);
   }

   private static int chipTextColor(int bg) {
      double lum = (0.299 * (double)Color.red(bg) + 0.587 * (double)Color.green(bg) + 0.114 * (double)Color.blue(bg)) / (double)255.0F;
      return lum > 0.65 ? -14735049 : -1;
   }

   /** uuid order → slug trip ("TRIP-KAM") se-cabang, dari /api/devices/queue-all (trip_slugs). */
   private final java.util.Map<String, String> tripSlugs = new java.util.concurrent.ConcurrentHashMap<>();
   private static final int TRIP_CHIP_BG = 0xFF6D28D9;

   /** Tambahkan badge slug trip di belakang chip produk (bila order di wilayah Aturan Trip). */
   private void appendTripChip(LinearLayout box, String uuid) {
      String slug = uuid != null ? this.tripSlugs.get(uuid) : null;
      if (slug == null || slug.isEmpty()) return;
      int shown = 0;
      for (int i = 0; i < box.getChildCount(); i++) if (box.getChildAt(i).getVisibility() == View.VISIBLE) shown = i + 1;
      TextView chip = this.chipAt(box, shown);
      chip.setText("🗓 " + slug);
      chip.setTextColor(chipTextColor(TRIP_CHIP_BG));
      ((GradientDrawable) chip.getBackground()).setColor(TRIP_CHIP_BG);
      chip.setVisibility(View.VISIBLE);
      box.setVisibility(View.VISIBLE);
   }

   private void bindProductChips(LinearLayout box, Transaction t) {
      this.bindProductChipsBase(box, t);
      if (this.tripSlugs.isEmpty()) return;
      String u = t.getSyncUuid();
      if ((u == null || u.isEmpty()) && t.getId() > 0) u = this.dao.getSyncUuidById(t.getId());
      this.appendTripChip(box, u);
   }

   private void bindProductChipsBase(LinearLayout box, Transaction t) {
      List<TransactionItem> items = t.getItems();
      if (items != null && !items.isEmpty()) {
         int shown = 0;
         int hidden = 0;

         boolean assumed = galonAssumed(t.getGalonTypeSource());
         for (Object[] r : this.typedRows(t)) {
            if (shown >= 4) {
               ++hidden;
            } else {
               TextView chip = this.chipAt(box, shown);
               int bg = (Integer) r[3];
               chip.setText(typedChipText(r, assumed));
               chip.setEllipsize(chip.getText() instanceof android.text.Spanned ? null : TruncateAt.END);
               chip.setTextColor(chipTextColor(bg));
               ((GradientDrawable)chip.getBackground()).setColor(bg);
               chip.setVisibility(0);
               ++shown;
            }
         }

         if (hidden > 0) {
            TextView more = this.chipAt(box, shown);
            int bg = -6511697;
            more.setText("+" + hidden);
            more.setTextColor(chipTextColor(bg));
            ((GradientDrawable)more.getBackground()).setColor(bg);
            more.setVisibility(0);
            ++shown;
         }

         for(int i = shown; i < box.getChildCount(); ++i) {
            box.getChildAt(i).setVisibility(8);
         }

         box.setVisibility(shown > 0 ? 0 : 8);
      } else {
         box.setVisibility(8);
      }
   }

   /** Badge "Detail Transaksi" Preview — cermin PERSIS chipAt() (kapsul produk kartu Antrean
    *  Delivery), bukan Material Chip: satu-satunya cara dua badge yang mestinya kembar ("MIN ×1" di
    *  kartu, "MIN ×1" di Preview) benar-benar terlihat identik ukurannya. */
   private TextView makeDetailBadge(CharSequence label, int bg) {
      TextView chip = new TextView(this);
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
      lp.setMarginEnd(this.dp(6f));
      chip.setLayoutParams(lp);
      chip.setTextSize(11f);
      chip.setTypeface(chip.getTypeface(), 1);
      chip.setPadding(this.dp(8f), this.dp(3f), this.dp(8f), this.dp(3f));
      chip.setMaxLines(1);
      chip.setEllipsize(TruncateAt.END);
      GradientDrawable bgDrawable = new GradientDrawable();
      bgDrawable.setShape(0);
      bgDrawable.setCornerRadius(this.dp(10f));
      bgDrawable.setColor(bg);
      chip.setBackground(bgDrawable);
      chip.setText(label);
      chip.setTextColor(chipTextColor(bg));
      if (label instanceof android.text.Spanned) chip.setEllipsize(null);   // tag jenis galon — lihat typedRows
      return chip;
   }

   private TextView chipAt(LinearLayout box, int i) {
      if (i < box.getChildCount()) {
         return (TextView)box.getChildAt(i);
      } else {
         TextView chip = new TextView(this);
         LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
         lp.setMarginEnd(this.dp(6.0F));
         chip.setLayoutParams(lp);
         chip.setTextSize(11.0F);
         chip.setTypeface(chip.getTypeface(), 1);
         chip.setPadding(this.dp(8.0F), this.dp(3.0F), this.dp(8.0F), this.dp(3.0F));
         chip.setMaxLines(1);
         chip.setEllipsize(TruncateAt.END);
         GradientDrawable bg = new GradientDrawable();
         bg.setShape(0);
         bg.setCornerRadius((float)this.dp(10.0F));
         chip.setBackground(bg);
         box.addView(chip);
         return chip;
      }
   }

   private boolean isRunning() {
      return !this.runningIds.isEmpty();
   }

   private void persistRunning() {
      (new SettingsDao(DatabaseHelper.getInstance(this))).setDeliveryRunningTrxIds(this.runningIds);
   }

   private void clearRunning() {
      this.runningIds.clear();
      this.persistRunning();
   }

   private void startRun(Transaction t) {
      if (t != null) {
         if (t.isOpenDispatch()) {
            this.claimOpenDispatchThenRun(t);
         } else if (!this.maybeBlockUnderfilledRit(t)) {
            this.doStartRun(t);
         }
      }
   }

   private boolean maybeBlockUnderfilledRit(Transaction t) {
      int maxLoad = this.syncCfg().getMaxLoad();
      if (maxLoad <= 0) {
         return false;
      } else if (this.isRunning()) {
         return false;
      } else {
         int load = isPickupOnly(t) ? 0 : Math.max(0, t.getJumlahGalon());
         if (load >= maxLoad) {
            return false;
         } else {
            List<Transaction> rec = this.recommendRit(t, maxLoad);
            if (rec.size() <= 1) {
               return false;
            } else {
               int recGalon = 0;

               for(Transaction s : rec) {
                  recGalon += isPickupOnly(s) ? 0 : Math.max(0, s.getJumlahGalon());
               }

               (new AlertDialog.Builder(this)).setIcon(17301659).setTitle("Muatan masih longgar").setView(this.stopsDialogView("Order ini hanya " + load + " galon dari muatan " + maxLoad + " galon. Berangkat sekarang berarti bolak-balik ke cabang lagi untuk sisa antrean.\n\nStrategi Pengiriman menyarankan " + rec.size() + " order sekaligus (" + recGalon + " dari " + maxLoad + " galon):", rec, null)).setPositiveButton("▶ Jalankan " + rec.size() + " order", (d, w) -> this.doStartRuns(rec)).setNeutralButton("Pilih sendiri", (d, w) -> {
                  this.enterSelectionMode();
                  if (this.selectionMode) {
                     this.toggleSelected(t);
                     this.adapter.notifyDataSetChanged();
                  }

               }).setNegativeButton("Tetap 1 order", (d, w) -> this.doStartRun(t)).show();
               return true;
            }
         }
      }
   }

   private List<Transaction> recommendRit(Transaction seed, int maxLoad) {
      List<Transaction> out = new ArrayList();
      out.add(seed);
      int load = isPickupOnly(seed) ? 0 : Math.max(0, seed.getJumlahGalon());
      List<Transaction> ordered = new ArrayList();
      if (this.strategyTrips != null) {
         for(DeliveryPlanner.Trip trip : this.strategyTrips) {
            ordered.addAll(trip.stops);
         }
      }

      if (ordered.isEmpty()) {
         ordered = this.adapter.data;
      }

      for(Transaction o : ordered) {
         if (o != null && o.getId() != seed.getId() && !o.isOpenDispatch()) {
            int l = isPickupOnly(o) ? 0 : Math.max(0, o.getJumlahGalon());
            if (load + l <= maxLoad) {
               out.add(o);
               load += l;
            }
         }
      }

      return out;
   }

   /**
    * Isi dialog konfirmasi jalan rit: teks pembuka, lalu satu baris per order — "1. ⚡ Nama (2 galon)"
    * diikuti badge produk (slug, digabung per produk) — lalu teks penutup. Menggantikan daftar teks
    * polos supaya kurir langsung melihat APA yang dibawa ke tiap pelanggan.
    */
   private View stopsDialogView(String before, List<Transaction> stops, String after) {
      int textColor = ContextCompat.getColor(this, R.color.text_primary);
      LinearLayout root = new LinearLayout(this);
      root.setOrientation(LinearLayout.VERTICAL);
      int pad = this.dp(20.0F);
      root.setPadding(pad, this.dp(8.0F), pad, 0);
      if (before != null && !before.isEmpty()) {
         TextView tv = new TextView(this);
         tv.setText(before);
         tv.setTextSize(14.0F);
         tv.setTextColor(textColor);
         tv.setPadding(0, 0, 0, this.dp(8.0F));
         root.addView(tv);
      }
      for (int i = 0; i < stops.size(); ++i) {
         Transaction t = stops.get(i);
         LinearLayout row = new LinearLayout(this);
         row.setOrientation(LinearLayout.HORIZONTAL);
         row.setGravity(android.view.Gravity.CENTER_VERTICAL);
         row.setPadding(0, this.dp(3.0F), 0, this.dp(3.0F));
         TextView name = new TextView(this);
         name.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.0F));
         name.setText((i + 1) + ". " + (DeliveryPlanner.isPriority(t) ? "⚡ " : "") + safe(t.getCustomerName())
               + " (" + (isPickupOnly(t) ? "↩ ambil " : "") + t.getJumlahGalon() + " galon)");
         name.setTextSize(14.0F);
         name.setTextColor(textColor);
         row.addView(name);
         LinearLayout badges = new LinearLayout(this);
         badges.setOrientation(LinearLayout.HORIZONTAL);
         badges.setPadding(this.dp(6.0F), 0, 0, 0);
         if (!isPickupOnly(t)) {
            boolean assumed = galonAssumed(t.getGalonTypeSource());
            for (Object[] r : this.typedRows(t)) {
               badges.addView(this.makeDetailBadge(typedChipText(r, assumed), (Integer) r[3]));
            }
         }
         row.addView(badges);
         root.addView(row);
      }
      if (after != null && !after.isEmpty()) {
         TextView tv = new TextView(this);
         tv.setText(after);
         tv.setTextSize(14.0F);
         tv.setTextColor(textColor);
         tv.setPadding(0, this.dp(8.0F), 0, 0);
         root.addView(tv);
      }
      ScrollView scroll = new ScrollView(this);
      scroll.addView(root);
      return scroll;
   }

   private String numberedNames(List<Transaction> stops) {
      StringBuilder sb = new StringBuilder();

      for(int i = 0; i < stops.size(); ++i) {
         Transaction s = (Transaction)stops.get(i);
         if (i > 0) {
            sb.append('\n');
         }

         sb.append(i + 1).append(". ").append(DeliveryPlanner.isPriority(s) ? "⚡ " : "").append(safe(s.getCustomerName())).append(" · ").append(isPickupOnly(s) ? "↩ ambil " : "").append(s.getJumlahGalon()).append(" gal");
      }

      return sb.toString();
   }

   /** Dialog ▶: jenis order yang dieksekusi — Penjualan (default dicentang) / Ambil Galon Saja (default tidak). */
   private interface KindChoice { void chosen(boolean sales, boolean pickup); }

   private void askRunKinds(String title, KindChoice then) {
      int pad = this.dp(20f);
      LinearLayout box = new LinearLayout(this);
      box.setOrientation(LinearLayout.VERTICAL);
      box.setPadding(pad, this.dp(8f), pad, 0);
      android.widget.CheckBox cbSales = new android.widget.CheckBox(this);
      cbSales.setText("🛒 Penjualan");
      cbSales.setChecked(true);
      android.widget.CheckBox cbPickup = new android.widget.CheckBox(this);
      cbPickup.setText("↩ Ambil Galon Saja");
      cbPickup.setChecked(false);
      box.addView(cbSales);
      box.addView(cbPickup);
      AlertDialog dlg = (new AlertDialog.Builder(this)).setTitle(title).setMessage("Jenis order yang dijalankan:").setView(box)
            .setPositiveButton("Lanjut", (DialogInterface.OnClickListener) null)
            .setNegativeButton("Batal", (DialogInterface.OnClickListener) null).create();
      dlg.setOnShowListener(di -> dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
         if (!cbSales.isChecked() && !cbPickup.isChecked()) {
            Toast.makeText(this, "Pilih minimal satu jenis.", 0).show();
            return;
         }
         dlg.dismiss();
         then.chosen(cbSales.isChecked(), cbPickup.isChecked());
      }));
      dlg.show();
   }

   private void runStrategyTrip() {
      if (this.strategyTrips == null || this.strategyTrips.isEmpty()) {
         Toast.makeText(this, "Strategi belum tersedia.", 0).show();
         return;
      }
      List<Object[]> cands = new ArrayList<>();
      int skippedOpen = 0;
      for (Transaction t : ((DeliveryPlanner.Trip) this.strategyTrips.get(0)).stops) {
         if (t == null) continue;
         if (t.isOpenDispatch()) {
            ++skippedOpen;
            continue;
         }
         cands.add(new Object[]{t, this.typedRows(t), DeliveryPlanner.isPickupOnly(t)});
      }
      final int open = skippedOpen;
      this.askRunLoad("▶ Jalankan Rit 1", cands, false, picked -> {
         List<Transaction> stops = new ArrayList<>();
         for (Object o : picked) stops.add((Transaction) o);
         this.confirmRunStops(stops, open);
      });
   }

   /**
    * Dialog ▶ MUATAN: jumlah galon yang dibawa per produk & jenis galon ("MIN ×23 KRN"), bawaan = semua di antrean,
    * bisa diubah; plus centang "Ambil Galon Saja". Order diambil berurutan (urutan rit/trip) selama SEMUA barisnya
    * masih muat di sisa jumlah — yang tak muat dilewati utuh (tak pernah dipecah). Order tanpa rincian ikut.
    *
    * @param cands {order, typedRows, pickupOnly}
    */
   private void askRunLoad(String title, List<Object[]> cands, boolean pickupDefault, java.util.function.Consumer<List<Object>> then) {
      this.askRunLoad(title, cands, pickupDefault, then, null, null);
   }

   /** @param neutralLabel tombol kiri bawah opsional (mis. "➕ Ke Antrean") — menerima order terpilih yang sama. */
   private void askRunLoad(String title, List<Object[]> cands, boolean pickupDefault, java.util.function.Consumer<List<Object>> then,
                           @Nullable String neutralLabel, @Nullable java.util.function.Consumer<List<Object>> onNeutral) {
      LinkedHashMap<String, int[]> total = new LinkedHashMap<>();
      int pickups = 0;
      for (Object[] c : cands) {
         if ((Boolean) c[2]) {
            pickups++;
            continue;
         }
         @SuppressWarnings("unchecked") List<Object[]> rows = (List<Object[]>) c[1];
         for (Object[] r : rows) {
            int q = (Integer) r[2];
            if (q <= 0) continue;
            String key = typedKey((String) r[0], (String) r[1]);
            int[] v = total.get(key);
            if (v == null) total.put(key, new int[]{q, (Integer) r[3]});
            else v[0] += q;
         }
      }
      int pad = this.dp(20f);
      LinearLayout box = new LinearLayout(this);
      box.setOrientation(LinearLayout.VERTICAL);
      box.setPadding(pad, this.dp(8f), pad, 0);
      TextView hint = new TextView(this);
      hint.setText("Jumlah galon yang dibawa (bawaan: semua di antrean). Kurangi bila muatan tak cukup — order yang tak muat dilewati.");
      hint.setTextSize(13f);
      box.addView(hint);
      LinkedHashMap<String, EditText> inputs = new LinkedHashMap<>();
      for (Map.Entry<String, int[]> e : total.entrySet()) {
         LinearLayout row = new LinearLayout(this);
         row.setOrientation(LinearLayout.HORIZONTAL);
         row.setGravity(android.view.Gravity.CENTER_VERTICAL);
         row.setPadding(0, this.dp(6f), 0, 0);
         TextView chip = this.makeDetailBadge(typedTotalText(e.getKey(), e.getValue()[0], e.getValue()[1]), e.getValue()[1]);
         chip.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
         row.addView(chip);
         EditText et = new EditText(this);
         et.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
         et.setText(String.valueOf(e.getValue()[0]));
         et.setSelectAllOnFocus(true);
         et.setMinEms(3);
         row.addView(et);
         box.addView(row);
         inputs.put(e.getKey(), et);
      }
      android.widget.CheckBox cbPickup = new android.widget.CheckBox(this);
      cbPickup.setText("↩ Ambil Galon Saja (" + pickups + " order)");
      cbPickup.setChecked(pickupDefault && pickups > 0);
      cbPickup.setEnabled(pickups > 0);
      box.addView(cbPickup);
      ScrollView sv = new ScrollView(this);
      sv.addView(box);
      AlertDialog.Builder bld = (new AlertDialog.Builder(this)).setTitle(title).setView(sv)
            .setPositiveButton("Lanjut", (DialogInterface.OnClickListener) null)
            .setNegativeButton("Batal", (DialogInterface.OnClickListener) null);
      if (neutralLabel != null && onNeutral != null) bld.setNeutralButton(neutralLabel, (DialogInterface.OnClickListener) null);
      AlertDialog dlg = bld.create();
      java.util.function.Consumer<java.util.function.Consumer<List<Object>>> pick = dest -> {
         Map<String, Integer> budget = new HashMap<>();
         for (Map.Entry<String, EditText> e : inputs.entrySet()) {
            int n;
            try {
               String s = e.getValue().getText().toString().trim();
               n = s.isEmpty() ? 0 : Integer.parseInt(s);
            } catch (NumberFormatException ex) {
               n = -1;
            }
            if (n < 0) {
               e.getValue().setError("Angka tidak valid");
               return;
            }
            budget.put(e.getKey(), n);
         }
         List<Object> picked = new ArrayList<>();
         for (Object[] c : cands) {
            if ((Boolean) c[2]) {
               if (cbPickup.isChecked()) picked.add(c[0]);
               continue;
            }
            @SuppressWarnings("unchecked") List<Object[]> rows = (List<Object[]>) c[1];
            Map<String, Integer> need = new HashMap<>();
            for (Object[] r : rows) {
               int q = (Integer) r[2];
               if (q > 0) need.merge(typedKey((String) r[0], (String) r[1]), q, Integer::sum);
            }
            boolean fits = true;
            for (Map.Entry<String, Integer> n : need.entrySet()) {
               Integer left = budget.get(n.getKey());
               if (left == null || left < n.getValue()) {
                  fits = false;
                  break;
               }
            }
            if (!fits) continue;
            for (Map.Entry<String, Integer> n : need.entrySet()) budget.put(n.getKey(), budget.get(n.getKey()) - n.getValue());
            picked.add(c[0]);
         }
         if (picked.isEmpty()) {
            Toast.makeText(this, "Tak ada order yang muat dengan jumlah ini.", Toast.LENGTH_SHORT).show();
            return;
         }
         dlg.dismiss();
         dest.accept(picked);
      };
      dlg.setOnShowListener(di -> {
         dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> pick.accept(then));
         Button nb = dlg.getButton(AlertDialog.BUTTON_NEUTRAL);
         if (nb != null && onNeutral != null) nb.setOnClickListener(v -> pick.accept(onNeutral));
      });
      dlg.show();
   }

   /** Konfirmasi ▶ Jalankan untuk order hasil dialog muatan. */
   private void confirmRunStops(List<Transaction> stops, int skippedOpen) {
      (new AlertDialog.Builder(this)).setTitle("Jalankan Rit 1 (" + stops.size() + " order)?")
            .setView(this.stopsDialogView(null, stops, "Semua ditandai SEDANG DIANTAR sebagai satu rit. Antar satu per satu, tandai ✓ Selesai pada tiap kartu."
                  + (skippedOpen > 0 ? "\n\n" + skippedOpen + " Pesanan Terbuka dilewati (klaim dulu satu per satu)." : "")))
            .setPositiveButton("Jalankan", (d, w) -> this.doStartRuns(stops))
            .setNegativeButton("Batal", (DialogInterface.OnClickListener) null).show();
   }

   private void runStrategyTrip(boolean includeSales, boolean includePickup) {
      if (this.strategyTrips != null && !this.strategyTrips.isEmpty()) {
         List<Transaction> stops = new ArrayList();
         int skippedOpen = 0;

         for(Transaction t : ((DeliveryPlanner.Trip)this.strategyTrips.get(0)).stops) {
            if (t != null) {
               if (t.isOpenDispatch()) {
                  ++skippedOpen;
               } else if (DeliveryPlanner.isPickupOnly(t) ? includePickup : includeSales) {
                  stops.add(t);
               }
            }
         }

         if (stops.isEmpty()) {
            Toast.makeText(this, skippedOpen > 0 ? "Rit ini isinya Pesanan Terbuka — klaim dulu satu per satu." : "Tak ada order jenis terpilih di rit ini.", 1).show();
         } else {
            (new AlertDialog.Builder(this)).setTitle("Jalankan Rit 1 (" + stops.size() + " order)?").setView(this.stopsDialogView(null, stops, "Semua ditandai SEDANG DIANTAR sebagai satu rit. Antar satu per satu, tandai ✓ Selesai pada tiap kartu." + (skippedOpen > 0 ? "\n\n" + skippedOpen + " Pesanan Terbuka dilewati (klaim dulu satu per satu)." : ""))).setPositiveButton("Jalankan", (d, w) -> this.doStartRuns(stops)).setNegativeButton("Batal", (DialogInterface.OnClickListener)null).show();
         }
      } else {
         Toast.makeText(this, "Strategi belum tersedia.", 0).show();
      }
   }

   private void doStartRun(Transaction t) {
      this.doStartRuns(Collections.singletonList(t));
   }

   /**
    * Penjaga rit berjalan: tiap 60 dtk (layar tampil) tanya server status order rit. Yang tak boleh
    * diantar lagi (dijadwalkan ulang / batal / dipindah / selesai pihak lain) dikeluarkan dari rit +
    * alarm — tanpa menunggu pull sinkron berikutnya.
    */
   private final Runnable runWatchdog = new Runnable() {
      public void run() {
         DeliveryQueueActivity.this.checkRunningOrders();
         DeliveryQueueActivity.this.tick.postDelayed(this, 60000L);
      }
   };

   private void checkRunningOrders() {
      if (!this.isRunning()) return;
      SyncSettings cfg = this.syncCfg();
      if (!cfg.isEnrolled()) return;
      final Map<String, Long> byUuid = new LinkedHashMap<>();
      for (Long id : new ArrayList<>(this.runningIds)) {
         String u = this.dao.getSyncUuidById(id);
         if (u != null && !u.isEmpty()) byUuid.put(u, id);
      }
      if (byUuid.isEmpty()) return;
      (new Thread(() -> {
         JSONObject orders;
         try {
            orders = (new SyncApi(cfg)).checkDelivery(new JSONArray(byUuid.keySet())).optJSONObject("orders");
         } catch (Throwable e) {
            return;   // tanpa sinyal: coba lagi putaran berikutnya
         }
         if (orders == null) return;
         final JSONObject fo = orders;
         this.runOnUiThread(() -> {
            if (this.isFinishing() || this.isDestroyed()) return;
            StringBuilder why = new StringBuilder();
            boolean changed = false;
            for (Map.Entry<String, Long> e : byUuid.entrySet()) {
               JSONObject v = fo.optJSONObject(e.getKey());
               if (v == null || v.optBoolean("deliverable", true) || !this.runningIds.contains(e.getValue())) continue;
               Transaction t = this.findInList(e.getValue());
               why.append("• ").append(t != null ? safe(t.getCustomerName()) : "Order").append(" — ").append(v.optString("message")).append('\n');
               this.dao.stopDelivery(e.getValue());
               this.runningIds.remove(e.getValue());
               changed = true;
            }
            if (!changed) return;
            this.persistRunning();
            SyncScheduler.syncNow(this.getApplicationContext());
            this.loadData();
            this.showRunCancelledAlarm(why.toString());
         });
      })).start();
   }

   private Transaction findInList(long id) {
      if (this.adapter != null && this.adapter.data != null) {
         for (Transaction t : this.adapter.data) if (t != null && t.getId() == id) return t;
      }
      return null;
   }

   /** Alarm + popup: order dikeluarkan dari rit berjalan karena berubah di pihak lain. */
   private void showRunCancelledAlarm(String lines) {
      if (this.isFinishing() || this.isDestroyed()) return;
      IncompleteCustomerDialog.playAlarm(this);
      (new AlertDialog.Builder(this)).setCancelable(false).setIcon(17301543)
            .setTitle("⛔ JANGAN DIANTAR — Order Dikeluarkan dari Rit")
            .setMessage(lines + "\nOrder di atas sudah dikeluarkan dari rit Anda. Jangan diantar; lanjutkan pemberhentian lain.")
            .setPositiveButton("Mengerti", (DialogInterface.OnClickListener) null).show();
   }

   /**
    * ▶ Jalankan: tanyakan dulu ke server apakah tiap order MASIH boleh diantar (pull bisa telat —
    * order bisa saja baru dijadwalkan ulang / dibatalkan / dipindah). Yang tak boleh dikeluarkan +
    * alarm; sisanya jalan. Tanpa sinyal / server gagal → jalan seperti biasa (tak menghalangi kerja).
    */
   private void doStartRuns(List<Transaction> list) {
      if (list == null || list.isEmpty()) return;
      SyncSettings cfg = this.syncCfg();
      final Map<String, Transaction> byUuid = new LinkedHashMap<>();
      for (Transaction t : list) {
         String u = t != null ? this.dao.getSyncUuidById(t.getId()) : null;
         if (u != null && !u.isEmpty()) byUuid.put(u, t);
      }
      if (!cfg.isEnrolled() || byUuid.isEmpty()) {
         this.doStartRunsNow(list);
         return;
      }
      android.app.ProgressDialog wait = new android.app.ProgressDialog(this);
      wait.setMessage("Memeriksa status order…");
      wait.setCancelable(false);
      wait.show();
      (new Thread(() -> {
         JSONObject orders = null;
         try {
            orders = (new SyncApi(cfg)).checkDelivery(new JSONArray(byUuid.keySet())).optJSONObject("orders");
         } catch (Throwable ignored) {
         }
         final JSONObject fo = orders;
         this.runOnUiThread(() -> {
            try { wait.dismiss(); } catch (Exception ignored) {}
            if (this.isFinishing() || this.isDestroyed()) return;
            if (fo == null) {
               this.doStartRunsNow(list);
               return;
            }
            List<Transaction> ok = new ArrayList<>();
            StringBuilder bad = new StringBuilder();
            for (Transaction t : list) {
               String u = t != null ? this.dao.getSyncUuidById(t.getId()) : null;
               JSONObject v = u != null ? fo.optJSONObject(u) : null;
               if (v == null || v.optBoolean("deliverable", true)) {
                  if (t != null) ok.add(t);
               } else {
                  bad.append("• ").append(safe(t.getCustomerName())).append(" — ").append(v.optString("message")).append('\n');
               }
            }
            if (bad.length() == 0) {
               this.doStartRunsNow(list);
               return;
            }
            SyncScheduler.syncNow(this.getApplicationContext());   // tarik status terbarunya ke HP
            IncompleteCustomerDialog.playAlarm(this);
            AlertDialog.Builder b = (new AlertDialog.Builder(this)).setCancelable(false).setIcon(17301543)
                  .setTitle("⛔ Order Ini TIDAK Boleh Diantar")
                  .setMessage(bad + (ok.isEmpty() ? "" : "\nSisanya " + ok.size() + " order masih boleh dijalankan."));
            if (ok.isEmpty()) {
               b.setPositiveButton("Mengerti", (d, w) -> this.navAfterStartAt = 0L);
            } else {
               b.setPositiveButton("Jalankan " + ok.size() + " order", (d, w) -> this.doStartRunsNow(ok))
                     .setNegativeButton("Batal", (d, w) -> this.navAfterStartAt = 0L);
            }
            b.show();
         });
      })).start();
   }

   private void doStartRunsNow(List<Transaction> list) {
      if (list != null && !list.isEmpty()) {
         for(Transaction t : list) {
            if (t != null) {
               this.dao.startDelivery(t.getId());
               this.runningIds.add(t.getId());
            }
         }

         this.persistRunning();
         this.adapter.notifyDataSetChanged();
         this.renderStrategy(this.adapter.data);
         this.applyRunModeChrome(this.adapter.data);
         this.rv.scrollToPosition(0);
         SyncScheduler.syncNow(this.getApplicationContext());
         Toast.makeText(this, list.size() == 1 ? "Mengantar: " + safe(((Transaction)list.get(0)).getCustomerName()) : "Mengantar " + list.size() + " order sekaligus", 0).show();
         // Order mandiri: struk WA dikirim server lewat Bridge (WaAutoSend) — tak ada lagi langkah wajib kirim struk di HP.
         // ▶ Jalankan rit = langsung susun rute terpendek (terdekat dulu) dari posisi HP — sama dengan
         // menekan "Urutkan Rute Terpendek". Jeda singkat supaya stempel jalan ikut tersinkron dulu.
         if (System.currentTimeMillis() - this.navAfterStartAt < 60000L) {
            // "▶ Jalankan & Navigasi" (rit berikutnya di navigasi per titik): susun rute terpendek lalu
            // navigasi titik pertama — DITUNGGU sampai layar bebas, supaya dialog wajib (mis. "Kirim
            // Struk WA" pesanan mandiri) tak tertimbun Maps.
            this.navAfterStartAt = 0L;
            this.wizardActive = true;
            this.wizardAdvancePending = true;
            this.tryWizardAdvance();
         } else if (list.size() > 1) {
            this.rv.postDelayed(() -> {
               if (!this.isFinishing() && !this.isDestroyed()) this.autoRerouteAfterAdd();
            }, 2000L);
         }
      }
   }

   /** Mulai rit pertama Strategi Pengiriman otomatis untuk kurir terpandu (lihat renderStrategy) —
    *  Pesanan Terbuka dilewati karena itu perlu diklaim satu per satu, bukan langsung dijalankan. */
   private void autoStartGuidedTrip(DeliveryPlanner.Trip trip) {
      List<Transaction> stops = new ArrayList<>();
      for (Transaction t : trip.stops) {
         if (t != null && !t.isOpenDispatch()) stops.add(t);
      }
      if (stops.isEmpty()) return;
      this.guidedRitRefillDeclinedIds.clear();
      this.doStartRuns(stops);
   }

   private void confirmStopRun() {
      List<Transaction> stops = this.runStops();
      String what = stops.size() > 1 ? stops.size() + " pengiriman rit ini BELUM ditandai Selesai." : "Pengiriman untuk \"" + (stops.isEmpty() ? "Order ini" : safe(((Transaction)stops.get(0)).getCustomerName())) + "\" BELUM ditandai Selesai.";
      (new AlertDialog.Builder(this)).setIcon(17301543).setTitle("Ganti pengiriman?").setMessage(what + "\n\nKembali ke daftar antrean dan memilih pengiriman lain?").setPositiveButton("Ya, ganti", (d, w) -> this.stopRun()).setNegativeButton("Tidak", (DialogInterface.OnClickListener)null).show();
   }

   /** Judul item menu dengan ikon WhatsApp resmi di depannya — PopupMenu bawaan tak menggambar
    *  ikon item (setForceShowIcon baru ada di API 29), jadi ikonnya disisipkan sebagai ImageSpan. */
   private CharSequence waMenuTitle(String label) {
      android.graphics.drawable.Drawable icon = ContextCompat.getDrawable(this, drawable.ic_whatsapp);
      if (icon == null) {
         return "💬 " + label;
      }
      int size = this.dp(20.0F);
      icon.setBounds(0, 0, size, size);
      android.text.SpannableString s = new android.text.SpannableString("  " + label);
      s.setSpan(new android.text.style.ImageSpan(icon, android.text.style.DynamicDrawableSpan.ALIGN_BOTTOM),
            0, 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
      return s;
   }

   private void showRunningMoreMenu(View anchor, Transaction t, String jarakLabel) {
      PopupMenu menu = new PopupMenu(this, anchor);
      menu.getMenu().add(0, 1, 0, "\u2190 Kembali");
      menu.getMenu().add(0, 2, 1, jarakLabel != null ? "\ud83d\udd0d " + jarakLabel + " \u00b7 Preview" : "\ud83d\udd0d Preview");
      if (WaShare.hasUsablePhone(t.getCustomerPhone())) {
         menu.getMenu().add(0, 3, 2, this.waMenuTitle("Chat WA"));
      }
      if ("JUAL".equals(t.getType())) {
         menu.getMenu().add(0, 4, 3, "\ud83d\udcac Chat Pesanan");
      }
      menu.getMenu().add(0, 5, 4, "\ud83d\udccd Kirim Link Lacak");
      if ("JUAL".equals(t.getType())) {
         menu.getMenu().add(0, 6, 5, "🚰 Jenis Galon");
      }
      menu.setOnMenuItemClickListener((item) -> {
         switch (item.getItemId()) {
            case 6:
               this.showGalonTypeDialog(t);
               return true;
            case 1:
               this.confirmStopRun();
               return true;
            case 2:
               this.showQueuePreview(t);
               return true;
            case 3:
               openWhatsApp(this, t.getCustomerPhone(), "");
               return true;
            case 4:
               this.openOrderChat(t);
               return true;
            case 5:
               this.sendTrackLink(t);
               return true;
            default:
               return false;
         }
      });
      menu.show();
   }

   private void showCompactMoreMenu(View anchor, Transaction t, String jarakLabel) {
      PopupMenu menu = new PopupMenu(this, anchor);
      menu.getMenu().add(0, 1, 0, t.isOrderPriority() ? "\u26a1 Sudah Prioritas \u2014 ubah alasan" : "\u26a1 Jadikan Prioritas");
      menu.getMenu().add(0, 2, 1, jarakLabel != null ? "\ud83d\udd0d " + jarakLabel + " \u00b7 Preview" : "\ud83d\udd0d Preview");
      if (WaShare.hasUsablePhone(t.getCustomerPhone())) {
         menu.getMenu().add(0, 3, 2, this.waMenuTitle("Chat WA Konsumen"));
      }
      if ("JUAL".equals(t.getType())) {
         menu.getMenu().add(0, 4, 3, "\ud83d\udcac Chat Pesanan");
      }
      if ("JUAL".equals(t.getType())) {
         menu.getMenu().add(0, 5, 4, "🚰 Jenis Galon");
      }
      menu.setOnMenuItemClickListener((item) -> {
         switch (item.getItemId()) {
            case 5:
               this.showGalonTypeDialog(t);
               return true;
            case 1:
               this.promptMarkPriority(t);
               return true;
            case 2:
               this.showQueuePreview(t);
               return true;
            case 3:
               openWhatsApp(this, t.getCustomerPhone(), "");
               return true;
            case 4:
               this.openOrderChat(t);
               return true;
            default:
               return false;
         }
      });
      menu.show();
   }

   private void promptMarkPriority(Transaction t) {
      SyncSettings cfg = this.syncCfg();
      if (!cfg.isEnrolled()) {
         Toast.makeText(this, "Perangkat belum terhubung ke server.", 1).show();
      } else {
         String trxUuid = this.dao.getSyncUuidById(t.getId());
         if (trxUuid != null && !trxUuid.isEmpty()) {
            EditText input = new EditText(this);
            input.setHint("Alasan (opsional)");
            int pad = this.dp(20.0F);
            input.setPadding(pad, pad / 2, pad, 0);
            (new AlertDialog.Builder(this)).setTitle("⚡ Jadikan Prioritas").setMessage("Tandai pengiriman \"" + safe(t.getCustomerName()) + "\" sebagai prioritas — naik di antrean web & semua HP.").setView(input).setPositiveButton("Tandai", (d, w) -> this.doMarkPriority(t, trxUuid, input.getText().toString().trim())).setNegativeButton("Batal", (DialogInterface.OnClickListener)null).show();
         } else {
            Toast.makeText(this, "Order ini belum tersinkron ke server. Coba lagi sebentar.", 1).show();
         }
      }
   }

   private void doMarkPriority(Transaction t, String trxUuid, String reason) {
      SyncSettings cfg = this.syncCfg();
      SettingsDao sdao = new SettingsDao(DatabaseHelper.getInstance(this));
      String requesterName = sdao.getCurrentUserName();
      (new Thread(() -> {
         String okMsg = null;
         String errMsg = null;

         try {
            JSONObject body = new JSONObject();
            body.put("transaction_uuid", trxUuid);
            if (!reason.isEmpty()) {
               body.put("reason", reason);
            }

            if (requesterName != null && !requesterName.isEmpty()) {
               body.put("requester_name", requesterName);
            }

            JSONObject r = (new SyncApi(cfg)).markPriority(body);
            okMsg = r.optString("message", "Order ditandai ⚡ PRIORITAS.");
         } catch (SyncApi.SyncException var10) {
            SyncApi.SyncException se = var10;

            try {
               errMsg = (new JSONObject(se.body)).optString("message", (String)null);
            } catch (Exception var9) {
            }

            if (errMsg == null) {
               errMsg = "Gagal menandai prioritas (kode " + var10.code + ").";
            }
         } catch (Exception var11) {
            errMsg = "Gagal menandai prioritas — periksa koneksi internet.";
         }

         final String okMsgF = okMsg;
         final String errMsgF = errMsg;
         this.runOnUiThread(() -> {
            if (!this.isFinishing() && !this.isDestroyed()) {
               if (okMsgF != null) {
                  Toast.makeText(this, okMsgF, 1).show();
                  SyncScheduler.syncNow(this.getApplicationContext());
                  this.loadData();
               } else {
                  Toast.makeText(this, errMsgF, 1).show();
               }

            }
         });
      })).start();
   }

   private void stopRun() {
      this.wizardActive = false;
      this.wizardCompleteId = -1L;
      for(Long id : this.runningIds) {
         this.dao.stopDelivery(id);
      }

      this.clearRunning();
      this.adapter.notifyDataSetChanged();
      this.renderStrategy(this.adapter.data);
      this.applyRunModeChrome(this.adapter.data);
      SyncScheduler.syncNow(this.getApplicationContext());
   }

   // ============================ ➕ Tambah ke rit berjalan ============================

   /**
    * Order titipan "masukkan ke rit" (diambil alih dari perangkat lain / Pesanan Terbuka / Peta
    * Delivery) yang barisnya SUDAH ada di Antrian Saya → ikut rit berjalan. Id lokal order hasil
    * klaim baru ada setelah sinkron, jadi titipan disimpan per uuid dan diserap di sini tiap daftar
    * dimuat ulang. Rit sudah selesai → titipan dibuang (tak ada rit untuk diikuti).
    */
   private boolean absorbPendingRunAdds(List<Transaction> list) {
      SettingsDao sdao = new SettingsDao(DatabaseHelper.getInstance(this));
      LinkedHashSet<String> pending = sdao.getDeliveryRunPendingUuids();
      if (pending.isEmpty()) return false;
      if (!this.isRunning()) {
         sdao.removeDeliveryRunPendingUuids(pending);
         return false;
      }
      List<String> done = new ArrayList<>();
      int added = 0;
      for (Transaction t : list) {
         if (t == null) continue;
         String u = this.dao.getSyncUuidById(t.getId());
         if (u == null || !pending.contains(u)) continue;
         if (!this.runningIds.contains(t.getId())) {
            this.dao.startDelivery(t.getId());
            this.runningIds.add(t.getId());
            added++;
         }
         done.add(u);
      }
      if (done.isEmpty()) return false;
      sdao.removeDeliveryRunPendingUuids(done);
      if (added == 0) return false;
      this.persistRunning();
      SyncScheduler.syncNow(this.getApplicationContext());
      Toast.makeText(this, added + " order masuk rit berjalan.", 0).show();
      return true;
   }

   /** Setelah order ditambahkan ke rit berjalan: susun ulang rute rit (jalur darat) dari posisi HP. */
   private void autoRerouteAfterAdd() {
      View b = this.findViewById(id.btnRunReroute);
      if (b != null && b.isEnabled() && this.runStops().size() > 1) {
         this.refreshRunRoute(b);
      }
   }

   /** "Nama · 2 galon · MIN×2 OXY×1" — label baris pemilih Tambah ke Rit. */
   private String runPickLabel(Transaction t) {
      StringBuilder sb = new StringBuilder(safe(t.getCustomerName()));
      sb.append(" · ").append(t.getJumlahGalon()).append(" galon");
      List<TransactionItem> items = t.getItems();
      if (items != null) {
         LinkedHashMap<String, Integer> q = new LinkedHashMap<>();
         for (TransactionItem it : items) {
            if (it != null && it.jumlah > 0) q.merge(this.chipLabel(it), it.jumlah, Integer::sum);
         }
         for (Map.Entry<String, Integer> e : q.entrySet()) {
            sb.append(" · ").append(e.getKey()).append("×").append(e.getValue());
         }
      }
      return sb.toString();
   }

   private String runPickLabel(JSONObject o) {
      StringBuilder sb = new StringBuilder(o.optString("name", "Pelanggan"));
      sb.append(" · ").append(o.optInt("galon", 0)).append(" galon");
      for (Map.Entry<String, int[]> e : this.mergedCsvChips(o.optString("items", "")).entrySet()) {
         String label = e.getKey();
         int cut = label.indexOf('\u0000');
         if (cut >= 0) label = label.substring(0, cut);
         sb.append(" · ").append(label);
         if (e.getValue()[0] > 0) sb.append("×").append(e.getValue()[0]);
      }
      String dev = o.optString("device_group_label", "");
      if (!dev.isEmpty() && !"null".equals(dev)) sb.append("  📱 ").append(dev);
      return sb.toString();
   }

   /**
    * ➕ Tambah ke Rit: pilih order untuk ikut rit yang SEDANG berjalan — dari Antrian Saya yang
    * belum ikut rit (mis. pesanan baru yang masuk selama rit), antrean perangkat lain, atau
    * Pesanan Terbuka. Order milik sendiri langsung masuk; milik lain diambil alih dulu lewat
    * endpoint klaim yang sama, lalu ikut rit begitu barisnya tersinkron (absorbPendingRunAdds).
    */
   private void showAddToRunPicker() {
      if (!this.isRunning()) {
         Toast.makeText(this, "Tidak ada rit yang sedang berjalan.", 0).show();
         return;
      }
      int pad = this.dp(16.0F);
      LinearLayout box = new LinearLayout(this);
      box.setOrientation(LinearLayout.VERTICAL);
      box.setPadding(pad, this.dp(8.0F), pad, 0);
      android.widget.ScrollView sv = new android.widget.ScrollView(this);
      sv.addView(box);

      final LinkedHashMap<CheckBox, Transaction> mineBoxes = new LinkedHashMap<>();
      final LinkedHashMap<CheckBox, String[]> otherBoxes = new LinkedHashMap<>();
      final HashMap<String, Long> openLocalIds = new HashMap<>();
      final HashSet<String> seen = new HashSet<>();

      box.addView(this.pickerHeader("📋 Antrian Saya (belum ikut rit)"));
      for (Transaction t : this.adapter.data) {
         if (t == null || this.runningIds.contains(t.getId())) continue;
         String u = this.dao.getSyncUuidById(t.getId());
         if (u != null) seen.add(u);
         CheckBox cb = new CheckBox(this);
         cb.setText(runPickLabel(t));
         box.addView(cb);
         mineBoxes.put(cb, t);
      }
      if (mineBoxes.isEmpty()) box.addView(this.pickerNote("Semua order Antrian Saya sudah ikut rit."));

      box.addView(this.pickerHeader("📱 Perangkat Lain & 🎲 Pesanan Terbuka"));
      if (this.openDispatchAdapter != null) {
         for (Transaction t : this.openDispatchAdapter.rawData) {
            String u = this.dao.getSyncUuidById(t.getId());
            if (u == null || u.isEmpty() || !seen.add(u)) continue;
            CheckBox cb = new CheckBox(this);
            cb.setText("🎲 " + runPickLabel(t));
            box.addView(cb);
            otherBoxes.put(cb, new String[]{u, ""});
            openLocalIds.put(u, t.getId());
         }
      }
      final TextView loading = this.pickerNote("Memuat antrean perangkat lain…");
      box.addView(loading);

      final AlertDialog dlg = (new AlertDialog.Builder(this))
            .setTitle("➕ Tambah ke Rit Berjalan")
            .setView(sv)
            .setPositiveButton("Tambahkan", (DialogInterface.OnClickListener) null)
            .setNegativeButton("Batal", (DialogInterface.OnClickListener) null)
            .create();
      dlg.setOnShowListener((x) -> dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener((v) -> {
         List<Transaction> mine = new ArrayList<>();
         for (Map.Entry<CheckBox, Transaction> e : mineBoxes.entrySet()) {
            if (e.getKey().isChecked()) mine.add(e.getValue());
         }
         LinkedHashMap<String, String> others = new LinkedHashMap<>();
         for (Map.Entry<CheckBox, String[]> e : otherBoxes.entrySet()) {
            if (e.getKey().isChecked()) others.put(e.getValue()[0], e.getValue()[1]);
         }
         if (mine.isEmpty() && others.isEmpty()) {
            Toast.makeText(this, "Belum ada order yang dipilih.", 0).show();
            return;
         }
         dlg.dismiss();
         this.addToRunningRit(mine, others, openLocalIds);
      }));
      dlg.show();

      SyncSettings cfg = this.syncCfg();
      if (!cfg.isEnrolled()) {
         loading.setText("Perangkat belum terhubung ke server.");
         return;
      }
      (new Thread(() -> {
         JSONArray queue = null;
         try {
            queue = (new SyncApi(cfg)).devicesQueueAll().optJSONArray("queue");
         } catch (Exception ignored) {}
         final JSONArray fq = queue;
         this.runOnUiThread(() -> {
            if (this.isFinishing() || this.isDestroyed() || !dlg.isShowing()) return;
            if (fq == null) {
               loading.setText("Gagal memuat antrean perangkat lain — periksa koneksi.");
               return;
            }
            box.removeView(loading);
            int n = 0;
            for (int i = 0; i < fq.length(); i++) {
               JSONObject o = fq.optJSONObject(i);
               if (o == null) continue;
               String u = o.optString("uuid", "");
               // Sedang diantar kurir lain → jangan ditawarkan (dua kurir di jalan yang sama).
               if (u.isEmpty() || o.optBoolean("in_progress", false) || !seen.add(u)) continue;
               CheckBox cb = new CheckBox(this);
               cb.setText(runPickLabel(o));
               box.addView(cb);
               otherBoxes.put(cb, new String[]{u, o.optString("routed_uuid", "")});
               n++;
            }
            if (n == 0 && otherBoxes.isEmpty()) {
               box.addView(this.pickerNote("Tidak ada order di perangkat lain / Pesanan Terbuka."));
            }
         });
      })).start();
   }

   private TextView pickerHeader(String text) {
      TextView tv = new TextView(this);
      tv.setText(text);
      tv.setTypeface(null, android.graphics.Typeface.BOLD);
      tv.setTextSize(13.0F);
      tv.setPadding(0, this.dp(10.0F), 0, this.dp(2.0F));
      return tv;
   }

   private TextView pickerNote(String text) {
      TextView tv = new TextView(this);
      tv.setText(text);
      tv.setTextSize(12.0F);
      tv.setAlpha(0.7F);
      tv.setPadding(0, this.dp(2.0F), 0, this.dp(4.0F));
      return tv;
   }

   /**
    * Masukkan order terpilih ke rit berjalan. Milik sendiri: langsung (startDelivery + runningIds).
    * Milik lain / Pesanan Terbuka: klaim satu per satu (penjaga basi sama dengan Ambil Alih), yang
    * berhasil dititipkan per uuid lalu diserap absorbPendingRunAdds begitu tersinkron.
    */
   private void addToRunningRit(List<Transaction> mine, LinkedHashMap<String, String> others,
                                HashMap<String, Long> openLocalIds) {
      if (!mine.isEmpty()) {
         for (Transaction t : mine) {
            this.dao.startDelivery(t.getId());
            this.runningIds.add(t.getId());
         }
         this.persistRunning();
         this.adapter.notifyDataSetChanged();
         this.renderStrategy(this.adapter.data);
         this.applyRunModeChrome(this.adapter.data);
         SyncScheduler.syncNow(this.getApplicationContext());
      }
      if (others.isEmpty()) {
         Toast.makeText(this, mine.size() + " order masuk rit berjalan.", 0).show();
         this.autoRerouteAfterAdd();
         return;
      }
      SyncSettings cfg = this.syncCfg();
      android.app.ProgressDialog wait = new android.app.ProgressDialog(this);
      wait.setMessage("Mengambil alih " + others.size() + " order…");
      wait.setCancelable(false);
      wait.show();
      (new Thread(() -> {
         List<String> ok = new ArrayList<>();
         int fail = 0;
         for (Map.Entry<String, String> e : others.entrySet()) {
            try {
               JSONObject body = new JSONObject();
               body.put("transaction_uuid", e.getKey());
               body.put("expected_device_uuid", e.getValue() != null ? e.getValue() : "");
               (new SyncApi(cfg)).claimDelivery(body);
               ok.add(e.getKey());
            } catch (Exception ex) {
               fail++;
            }
         }
         final int failF = fail;
         this.runOnUiThread(() -> {
            try { wait.dismiss(); } catch (Throwable ignored) {}
            if (this.isFinishing() || this.isDestroyed()) return;
            if (!ok.isEmpty()) {
               (new SettingsDao(DatabaseHelper.getInstance(this))).addDeliveryRunPendingUuids(ok);
               // Pesanan Terbuka sudah ada di HP → tandai milik sendiri sekarang supaya langsung
               // terserap ke rit tanpa menunggu tarikan sinkron.
               for (String u : ok) {
                  Long localId = openLocalIds.get(u);
                  if (localId != null) this.dao.markClaimedLocally(localId, cfg.getDeviceUuid());
               }
            }
            Toast.makeText(this, (mine.size() + ok.size()) + " order ditambahkan ke rit"
                  + (failF > 0 ? " · " + failF + " gagal diambil (mungkin sudah diambil rekan)" : "."), 1).show();
            SyncScheduler.syncNow(this.getApplicationContext());
            this.loadData();
            if (ok.isEmpty() && !mine.isEmpty()) this.autoRerouteAfterAdd();
         });
      })).start();
   }

   private List<Transaction> runStops() {
      List<Transaction> out = new ArrayList();
      if (!this.isRunning()) {
         return out;
      } else {
         // Urutan pemberhentian = urutan RENCANA terkini (adapter.data sudah diurutkan
         // DeliveryPlanner menurut delivery_seq), BUKAN urutan saat order mulai dijalankan — supaya
         // "Urutkan Rute Terpendek" / Refresh RIT (jalur jalan darat dari server) langsung mengubah
         // urutan antar rit yang sedang berjalan, termasuk "Selanjutnya" di mode terpandu.
         for(Transaction t : this.adapter.data) {
            if (this.runningIds.contains(t.getId())) {
               out.add(t);
            }
         }

         return out;
      }
   }

   private void moveRunningStop(int from, int to) {
      List<Transaction> stops = this.runStops();
      if (from >= 0 && to >= 0 && from < stops.size() && to < stops.size() && from != to) {
         Transaction moved = (Transaction)stops.remove(from);
         stops.add(to, moved);
         List<Long> reordered = new ArrayList(stops.size());

         for(Transaction t : stops) {
            reordered.add(t.getId());
         }

         this.runningIds.clear();
         this.runningIds.addAll(reordered);
         this.persistRunning();
      }
   }

   private Transaction runningTransaction() {
      List<Transaction> stops = this.runStops();
      return stops.isEmpty() ? null : (Transaction)stops.get(0);
   }

   private void applyRunModeChrome(List<Transaction> list) {
      int n = list != null ? list.size() : 0;
      List<Transaction> stops = this.runStops();
      if (stops.size() == 1) {
         this.tvSummary.setText("\ud83d\ude9a Mengantar: " + safe(((Transaction)stops.get(0)).getCustomerName()) + "  ·  " + Math.max(0, n - 1) + " order menunggu");
      } else if (stops.size() > 1) {
         this.tvSummary.setText("\ud83d\ude9a Mengantar " + stops.size() + " order (1 rit)  ·  " + Math.max(0, n - stops.size()) + " order menunggu");
      } else {
         this.tvSummary.setText(n + " order menunggu diproses" + galonSuffix(totalGalonOf(list)));
      }
      // Ringkasan "N order menunggu" redundan dengan badge tab — header biru hanya tampil saat rit berjalan
      // (Mengantar…, badge muatan, Tambah ke Rit) atau di mode terpandu.
      View hdr = (View) this.tvSummary.getParent();
      if (hdr != null) hdr.setVisibility(!stops.isEmpty() || this.guidedMode ? View.VISIBLE : View.GONE);

      this.updateGuidedProductBadges(stops);
      Transaction run = stops.isEmpty() ? null : (Transaction)stops.get(0);
      // Selama rit berjalan layar TIDAK boleh mati/terkunci sendiri — kurir melirik HP di motor
      // (pemberhentian berikutnya, badge muatan) tanpa sempat membuka kunci. Flag jendela, bukan
      // wake lock: otomatis lepas begitu layar ini tak di depan, dan dilepas lagi saat rit selesai.
      if (run != null) {
         this.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
      } else {
         this.getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
      }
      View cardStrategy = this.findViewById(id.cardStrategy);
      if (cardStrategy != null && run != null) {
         cardStrategy.setVisibility(8);
      }

      if (this.tabs != null) {
         this.tabs.setVisibility(run != null || this.guidedMode ? 8 : 0);
      }

      View btnRunReroute = this.findViewById(id.btnRunReroute);
      if (btnRunReroute != null) {
         btnRunReroute.setVisibility(stops.size() > 1 ? 0 : 8);
         btnRunReroute.setOnClickListener((v) -> this.refreshRunRoute(v));
      }

      View btnRunAdd = this.findViewById(id.btnRunAdd);
      if (btnRunAdd != null) {
         btnRunAdd.setVisibility(run != null ? 0 : 8);
         btnRunAdd.setOnClickListener((v) -> {
            this.takeForRun = true;
            this.showTakeWilayahDialog();
         });
      }

      View fabNavigasiRit = this.findViewById(id.fabNavigasiRit);
      if (fabNavigasiRit != null) {
         fabNavigasiRit.setVisibility(run == null || this.guidedMode ? 8 : 0);
      }

      if (this.guidedMode) {
         // Layar terpandu tak pernah menampilkan daftar kartu mentah — rv/tvEmpty digantikan
         // sepenuhnya oleh guidedPanel (lihat updateGuidedPanel).
         if (this.rv != null) this.rv.setVisibility(View.GONE);
         if (this.tvEmpty != null) this.tvEmpty.setVisibility(View.GONE);
         this.updateGuidedPanel(n, stops);
      }
   }

   /** Panel utama guided: order berjalan berikutnya jadi Preview inline, atau layar "Selesai" bila
    *  antrean kosong. Dipanggil dari applyRunModeChrome tiap kali daftar berjalan berubah. */
   private void updateGuidedPanel(int total, List<Transaction> stops) {
      if (this.isFinishing() || this.isDestroyed()) return;
      if (total == 0) {
         this.guidedPreviewShownForId = -1L;
         if (this.guidedDoneScreenShown) return;
         this.guidedDoneScreenShown = true;
         this.showGuidedDoneScreen();
         return;
      }
      this.guidedDoneScreenShown = false;
      if (stops.isEmpty()) return;
      Transaction next = stops.get(0);
      // Preview hanya dirender ULANG saat order BERIKUTNYA benar-benar berganti — bukan tiap
      // applyRunModeChrome (dipanggil sangat sering), kalau tidak peta & kompas dimuat ulang terus.
      if (next.getId() != this.guidedPreviewShownForId) {
         this.guidedPreviewShownForId = next.getId();
         this.showQueuePreview(next);
      }
   }

   /** Layar "🎉 antrean kosong" guided: ringkasan hari ini + kartu Status Pencapaian + jalan
    *  keluar (Trx Baru jual air minum eceran, atau Selesai—Pulang lewat confirmGuidedLogout). */
   private void showGuidedDoneScreen() {
      if (this.guidedPanel == null || this.isFinishing() || this.isDestroyed()) return;
      this.teardownGuidedInlinePreview();
      this.guidedPanel.removeAllViews();
      this.guidedPanel.setVisibility(View.VISIBLE);
      ScrollView scroll = new ScrollView(this);
      LinearLayout content = new LinearLayout(this);
      content.setOrientation(LinearLayout.VERTICAL);
      content.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
      content.setPadding(this.dp(24f), this.dp(32f), this.dp(24f), this.dp(32f));
      scroll.addView(content);
      this.guidedPanel.addView(scroll);

      TextView title = new TextView(this);
      title.setText("🎉 Antrean kosong — semua order sudah diantar.");
      title.setTextSize(17f);
      title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
      title.setTextColor(0xFF0F5132);
      title.setGravity(android.view.Gravity.CENTER);
      content.addView(title);

      final TextView tvStats = new TextView(this);
      tvStats.setText("Memuat statistik hari ini…");
      tvStats.setTextSize(14f);
      tvStats.setTextColor(0xFFB80A97);
      tvStats.setGravity(android.view.Gravity.CENTER);
      LinearLayout.LayoutParams statsLp = new LinearLayout.LayoutParams(-2, -2);
      statsLp.topMargin = this.dp(16f);
      tvStats.setLayoutParams(statsLp);
      content.addView(tvStats);

      final TextView tvNames = new TextView(this);
      tvNames.setTextSize(13f);
      tvNames.setTextColor(-10193781);
      tvNames.setGravity(android.view.Gravity.CENTER);
      LinearLayout.LayoutParams namesLp = new LinearLayout.LayoutParams(-2, -2);
      namesLp.topMargin = this.dp(4f);
      tvNames.setLayoutParams(namesLp);
      content.addView(tvNames);

      final android.widget.HorizontalScrollView chipsScroll = new android.widget.HorizontalScrollView(this);
      chipsScroll.setHorizontalScrollBarEnabled(false);
      final LinearLayout chipsRow = new LinearLayout(this);
      chipsRow.setOrientation(LinearLayout.HORIZONTAL);
      chipsScroll.addView(chipsRow);
      LinearLayout.LayoutParams chipsLp = new LinearLayout.LayoutParams(-2, -2);
      chipsLp.topMargin = this.dp(10f);
      chipsLp.bottomMargin = this.dp(24f);
      chipsScroll.setLayoutParams(chipsLp);
      chipsScroll.setVisibility(View.GONE);
      content.addView(chipsScroll);

      LinearLayout achievementCard = new LinearLayout(this);
      achievementCard.setOrientation(LinearLayout.VERTICAL);
      achievementCard.setPadding(this.dp(14f), this.dp(12f), this.dp(14f), this.dp(12f));
      GradientDrawable achBg = new GradientDrawable();
      achBg.setShape(GradientDrawable.RECTANGLE);
      achBg.setCornerRadius(this.dp(10f));
      achBg.setColor(-920071);
      achievementCard.setBackground(achBg);
      LinearLayout.LayoutParams achLp = new LinearLayout.LayoutParams(-1, -2);
      achLp.topMargin = this.dp(4f);
      achLp.bottomMargin = this.dp(20f);
      achievementCard.setLayoutParams(achLp);
      achievementCard.setVisibility(View.GONE);
      content.addView(achievementCard);
      this.loadGuidedAchievementCard(achievementCard);

      MaterialButton btnTrxBaru = new MaterialButton(this);
      btnTrxBaru.setText("➕ Trx Baru");
      btnTrxBaru.setAllCaps(false);
      btnTrxBaru.setBackgroundTintList(android.content.res.ColorStateList.valueOf(-16553567));
      LinearLayout.LayoutParams trxLp = new LinearLayout.LayoutParams(-2, -2);
      trxLp.bottomMargin = this.dp(10f);
      btnTrxBaru.setLayoutParams(trxLp);
      btnTrxBaru.setOnClickListener((v) -> this.launchGuidedJualAirMinum());
      content.addView(btnTrxBaru);

      MaterialButton btnLogout = new MaterialButton(this);
      btnLogout.setText("🚪 Selesai — Pulang");
      btnLogout.setAllCaps(false);
      btnLogout.setOnClickListener((v) -> this.confirmGuidedLogout());
      content.addView(btnLogout);

      final TransactionDao dao = this.dao;
      new Thread(() -> {
         List<Transaction> history = dao.getDeliveryHistory(500);
         String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
         LinkedHashSet<String> names = new LinkedHashSet<>();
         java.util.LinkedHashMap<String, Integer> qtyByLabel = new java.util.LinkedHashMap<>();
         java.util.LinkedHashMap<String, Integer> colorByLabel = new java.util.LinkedHashMap<>();
         int orders = 0, galon = 0, timedCount = 0;
         long timedSeconds = 0;
         for (Transaction t : history) {
            String local = Ts.local(t.getDeliveryDoneAt());
            if (local.length() < 10 || !local.substring(0, 10).equals(today)) continue;
            orders++;
            galon += Math.max(0, t.getJumlahGalon());
            names.add(safe(t.getCustomerName()));
            long secs = TransactionAdapter.deliverySeconds(t);
            if (secs >= 0) {
               timedSeconds += secs;
               timedCount++;
            }
            List<TransactionItem> items = t.getItems();
            if (items != null) {
               for (TransactionItem it : items) {
                  if (it == null || it.jumlah <= 0) continue;
                  String label = this.chipLabel(it);
                  qtyByLabel.merge(label, it.jumlah, Integer::sum);
                  colorByLabel.putIfAbsent(label, this.chipColor(it));
               }
            }
         }
         String stats = orders == 0 ? "Belum ada pengiriman yang diselesaikan hari ini."
               : orders + " order · " + galon + " galon sudah diantar hari ini"
                     + (timedCount > 0 ? " · rata-rata " + TransactionAdapter.formatDeliverySeconds(timedSeconds / timedCount) : "");
         String namesLine = names.isEmpty() ? "" : "Kepada: " + android.text.TextUtils.join(", ", names);
         this.runOnUiThread(() -> {
            if (this.isFinishing() || this.isDestroyed()) return;
            tvStats.setText(stats);
            tvNames.setText(namesLine);
            if (qtyByLabel.isEmpty()) return;
            chipsRow.removeAllViews();
            for (Map.Entry<String, Integer> e : qtyByLabel.entrySet()) {
               chipsRow.addView(this.makeDetailBadge(e.getKey() + " ×" + e.getValue(),
                     colorByLabel.getOrDefault(e.getKey(), -10193781)));
            }
            chipsScroll.setVisibility(View.VISIBLE);
         });
      }).start();
   }

   /** Kartu "📊 Status Pencapaian" (target penjualan periode berjalan/lalu + galon pekan
    *  ini/lalu) — dihitung server-side (SyncApi#salesTarget) supaya HP tak perlu tahu aturan target
    *  sama sekali. Dimuat async karena butuh panggilan jaringan. */
   private void loadGuidedAchievementCard(LinearLayout card) {
      SyncSettings cfg = this.syncCfg();
      String staffUuid = LocationReporter.currentStaffUuid(this);
      if (!cfg.isEnrolled() || staffUuid == null || staffUuid.isEmpty()) return;
      new Thread(() -> {
         try {
            JSONObject data = new SyncApi(cfg).salesTarget(staffUuid);
            this.runOnUiThread(() -> {
               if (this.isFinishing() || this.isDestroyed()) return;
               this.renderGuidedAchievementCard(card, data);
            });
         } catch (Exception ignored) {
         }
      }).start();
   }

   private void renderGuidedAchievementCard(LinearLayout card, JSONObject data) {
      card.removeAllViews();
      TextView title = new TextView(this);
      title.setText("📊 Status Pencapaian");
      title.setTextSize(13f);
      title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
      title.setTextColor(-13418155);
      card.addView(title);
      boolean any = false;
      if (data.optBoolean("target_enabled", false)) {
         JSONObject current = data.optJSONObject("current");
         JSONObject previous = data.optJSONObject("previous");
         if (current != null) {
            card.addView(this.achievementLine("🎯 Target bulan cut-off ini", current));
            any = true;
         }
         if (previous != null) {
            card.addView(this.achievementLine("🎯 Target bulan cut-off lalu", previous));
            any = true;
         }
      }
      JSONObject week = data.optJSONObject("week");
      JSONObject weekPrev = data.optJSONObject("week_prev");
      if (week != null) {
         card.addView(this.achievementGalonLine("📦 Galon pekan ini", week.optInt("galon", 0)));
         any = true;
      }
      if (weekPrev != null) {
         card.addView(this.achievementGalonLine("📦 Galon pekan lalu", weekPrev.optInt("galon", 0)));
         any = true;
      }
      card.setVisibility(any ? View.VISIBLE : View.GONE);
   }

   private TextView achievementLine(String label, JSONObject o) {
      int target = o.optInt("target", 0);
      int actual = o.optInt("actual", 0);
      double pct = o.optDouble("pct", 0.0);
      boolean achieved = o.optBoolean("achieved", false);
      String suffix = target <= 0 ? "" : (achieved ? " ✅ Tercapai" : " ⏳ Belum tercapai");
      TextView tv = new TextView(this);
      tv.setText(label + ": " + actual + "/" + target + " galon (" + String.format(Locale.US, "%.0f", pct) + "%)" + suffix);
      tv.setTextSize(13f);
      tv.setTextColor(target > 0 && achieved ? -15368131 : -12102295);
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
      lp.topMargin = this.dp(4f);
      tv.setLayoutParams(lp);
      return tv;
   }

   private TextView achievementGalonLine(String label, int galon) {
      TextView tv = new TextView(this);
      tv.setText(label + ": " + galon + " galon");
      tv.setTextSize(13f);
      tv.setTextColor(-12102295);
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
      lp.topMargin = this.dp(4f);
      tv.setLayoutParams(lp);
      return tv;
   }

   private void confirmGuidedLogout() {
      (new AlertDialog.Builder(this)).setTitle("Selesai — Pulang?")
            .setMessage("Antrean sudah kosong. Kamu akan absen pulang dan keluar dari perangkat ini.")
            .setPositiveButton("Ya, Pulang", (d, w) -> this.launchGuidedClockOut())
            .setNegativeButton("Batal", (DialogInterface.OnClickListener)null).show();
   }

   private void launchGuidedClockOut() {
      Intent i = new Intent(this, MainActivity.class);
      i.putExtra(MainActivity.EXTRA_AUTO_CLOCKOUT, true);
      i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
      this.startActivity(i);
      this.finish();
   }

   private void launchGuidedJualAirMinum() {
      Intent i = new Intent(this, TransactionActivity.class);
      i.putExtra("type", "JUAL");
      this.startActivity(i);
   }

   /** Dialog layar-penuh "lihat semua antrean" untuk kurir terpandu (fabGuidedQueue) — dua tab
    *  dipilih lewat popup menu di judul: Antrean Saya (daftar biasa, bisa dicari) atau Antrean Lain
    *  (gabungan order perangkat lain + Pesanan Terbuka, bisa diambil alih langsung dari sini). */
   private void showGuidedFullQueueDialog() {
      if (this.isFinishing() || this.isDestroyed()) return;
      final Dialog dialog = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
      LinearLayout root = new LinearLayout(this);
      root.setOrientation(LinearLayout.VERTICAL);
      root.setBackgroundColor(this.getResources().getColor(color.grey_light));

      LinearLayout header = new LinearLayout(this);
      header.setOrientation(LinearLayout.HORIZONTAL);
      header.setGravity(android.view.Gravity.CENTER_VERTICAL);
      header.setBackgroundColor(this.getResources().getColor(color.primary));
      header.setPadding(this.dp(16f), this.dp(14f), this.dp(4f), this.dp(14f));
      final TextView title = new TextView(this);
      title.setTextColor(-1);
      title.setTextSize(16f);
      title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
      title.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
      header.addView(title);
      android.widget.ImageButton btnClose = new android.widget.ImageButton(this);
      btnClose.setImageResource(drawable.ic_arrow_back);
      btnClose.setBackgroundColor(0);
      btnClose.setColorFilter(-1);
      btnClose.setOnClickListener((v) -> dialog.dismiss());
      header.addView(btnClose);
      root.addView(header);

      final android.widget.FrameLayout body = new android.widget.FrameLayout(this);
      body.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 1f));
      root.addView(body);

      final boolean[] showingOther = {false};
      final Runnable[] renderBody = new Runnable[1];
      renderBody[0] = () -> {
         body.removeAllViews();
         if (!showingOther[0]) {
            title.setText("Antrean Saya ▾");
            body.addView(this.buildGuidedMyQueueBody());
         } else {
            title.setText("Antrean Lain ▾");
            body.addView(this.buildGuidedOtherQueueBody(dialog));
         }
      };
      title.setOnClickListener((v) -> {
         PopupMenu menu = new PopupMenu(this, title);
         menu.getMenu().add(0, 0, 0, "Antrean Saya");
         menu.getMenu().add(0, 1, 1, "Antrean Lain");
         menu.setOnMenuItemClickListener((item) -> {
            showingOther[0] = item.getItemId() == 1;
            renderBody[0].run();
            return true;
         });
         menu.show();
      });
      renderBody[0].run();
      dialog.setContentView(root);
      dialog.show();
   }

   /** Tab "Antrean Saya" dalam showGuidedFullQueueDialog — daftar QueueAdapter BIASA tapi TERPISAH
    *  dari adapter utama (this.adapter tetap menampilkan hanya rit berjalan), dipaksa menampilkan
    *  SELURUH antrean via setForceShowAll + kotak cari sendiri via setOverrideQuery. */
   private View buildGuidedMyQueueBody() {
      LinearLayout root = new LinearLayout(this);
      root.setOrientation(LinearLayout.VERTICAL);
      EditText search = new EditText(this);
      search.setHint("Cari nama / no. WA…");
      search.setSingleLine(true);
      search.setBackgroundColor(-1);
      search.setPadding(this.dp(14f), this.dp(10f), this.dp(14f), this.dp(10f));
      LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(-1, -2);
      searchLp.setMargins(this.dp(12f), this.dp(10f), this.dp(12f), this.dp(4f));
      search.setLayoutParams(searchLp);
      root.addView(search);

      RecyclerView rv = new RecyclerView(this);
      rv.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 1f));
      rv.setLayoutManager(new LinearLayoutManager(this));
      rv.setPadding(this.dp(12f), this.dp(4f), this.dp(12f), this.dp(12f));
      rv.setClipToPadding(false);
      QueueAdapter listAdapter = new QueueAdapter();
      listAdapter.setForceShowAll(true);
      listAdapter.setData(this.adapter.data);
      rv.setAdapter(listAdapter);
      root.addView(rv);
      this.guidedMyQueueAdapterRef = new java.lang.ref.WeakReference<>(listAdapter);

      search.addTextChangedListener(new TextWatcher() {
         public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
         public void afterTextChanged(Editable s) { }
         public void onTextChanged(CharSequence s, int a, int b, int c) {
            listAdapter.setOverrideQuery(s == null ? "" : s.toString());
         }
      });
      return root;
   }

   /** Tab "Antrean Lain" dalam showGuidedFullQueueDialog — gabungan order perangkat lain (Tab 2) +
    *  Pesanan Terbuka (Tab 3) jadi SATU daftar (buildOtherQueueRows), diurutkan Umur/Jarak, dan bisa
    *  diambil alih langsung dari sini tanpa berpindah tab lagi. */
   private View buildGuidedOtherQueueBody(Dialog hostDialog) {
      LinearLayout root = new LinearLayout(this);
      root.setOrientation(LinearLayout.VERTICAL);

      LinearLayout sortRow = new LinearLayout(this);
      sortRow.setOrientation(LinearLayout.HORIZONTAL);
      sortRow.setPadding(this.dp(12f), this.dp(10f), this.dp(12f), this.dp(6f));
      sortRow.setBackgroundColor(-1);
      final MaterialButton btnAge = new MaterialButton(this);
      btnAge.setText("⏱ Umur");
      btnAge.setAllCaps(false);
      LinearLayout.LayoutParams ageLp = new LinearLayout.LayoutParams(0, -2, 1f);
      ageLp.setMarginEnd(this.dp(6f));
      btnAge.setLayoutParams(ageLp);
      sortRow.addView(btnAge);
      final MaterialButton btnDist = new MaterialButton(this);
      btnDist.setText("📍 Jarak");
      btnDist.setAllCaps(false);
      btnDist.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
      sortRow.addView(btnDist);
      root.addView(sortRow);

      RecyclerView rv = new RecyclerView(this);
      rv.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 1f));
      rv.setLayoutManager(new LinearLayoutManager(this));
      rv.setPadding(this.dp(12f), this.dp(8f), this.dp(12f), this.dp(12f));
      rv.setClipToPadding(false);
      root.addView(rv);

      TextView empty = new TextView(this);
      empty.setText("Tidak ada antrean lain saat ini");
      empty.setTextColor(-7035976);
      empty.setTextSize(14f);
      empty.setGravity(android.view.Gravity.CENTER);
      empty.setPadding(this.dp(24f), this.dp(32f), this.dp(24f), this.dp(24f));
      empty.setVisibility(View.GONE);
      root.addView(empty);

      final OtherQueueRowAdapter listAdapter = new OtherQueueRowAdapter(hostDialog);
      rv.setAdapter(listAdapter);
      this.guidedOtherRowsAdapterRef = new java.lang.ref.WeakReference<>(listAdapter);
      List<OtherQueueRow> rows = this.buildOtherQueueRows();
      listAdapter.setData(rows, false);
      empty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
      rv.setVisibility(rows.isEmpty() ? View.GONE : View.VISIBLE);

      Runnable markAge = () -> {
         btnAge.setBackgroundTintList(android.content.res.ColorStateList.valueOf(this.getResources().getColor(color.primary)));
         btnDist.setBackgroundTintList(android.content.res.ColorStateList.valueOf(-5194043));
      };
      Runnable markDist = () -> {
         btnDist.setBackgroundTintList(android.content.res.ColorStateList.valueOf(this.getResources().getColor(color.primary)));
         btnAge.setBackgroundTintList(android.content.res.ColorStateList.valueOf(-5194043));
      };
      markAge.run();
      btnAge.setOnClickListener((v) -> {
         markAge.run();
         listAdapter.sort(false);
      });
      btnDist.setOnClickListener((v) -> {
         markDist.run();
         listAdapter.sort(true);
      });
      return root;
   }

   /** Satu baris "Antrean Lain" gabungan — sumbernya bisa order perangkat lain (json != null, dari
    *  OtherDevicesAdapter.rawData) ATAU Pesanan Terbuka lokal (trx != null, dari
    *  OpenDispatchAdapter.rawData); persis satu dari keduanya yang terisi. */
   private static class OtherQueueRow {
      final JSONObject json;
      final Transaction trx;
      final String badgeName;
      final String note;
      final String meta;
      final boolean hasOngkir;
      final String adminAreaText;
      final String itemsCsv;
      final long elapsedMs;
      final double distKm;
      final boolean pickupOnly;

      OtherQueueRow(JSONObject json, Transaction trx, String badgeName, String note, String meta,
            boolean hasOngkir, String adminAreaText, String itemsCsv, long elapsedMs, double distKm,
            boolean pickupOnly) {
         this.json = json;
         this.trx = trx;
         this.badgeName = badgeName;
         this.note = note;
         this.meta = meta;
         this.hasOngkir = hasOngkir;
         this.adminAreaText = adminAreaText;
         this.itemsCsv = itemsCsv;
         this.elapsedMs = elapsedMs;
         this.distKm = distKm;
         this.pickupOnly = pickupOnly;
      }
   }

   /** Order perangkat lain yang MASIH menunggu (belum in_progress & punya koordinat) — dipakai
    *  sebagai pin 📦 tambahan di peta guided (lihat buildMiniMapHtml) supaya kurir tahu ada order
    *  lain di sekitarnya sebelum memutuskan mengambil rute mana. */
   private JSONArray buildOtherPendingForMap() {
      JSONArray out = new JSONArray();
      if (this.otherDevicesAdapter != null) {
         for (JSONObject o : this.otherDevicesAdapter.rawData) {
            if (o.optBoolean("in_progress", false)) continue;
            double lat = o.optDouble("latitude", 0.0);
            double lng = o.optDouble("longitude", 0.0);
            if (lat != 0.0 || lng != 0.0) out.put(o);
         }
      }
      return out;
   }

   /** Dicari lewat GuidedMapBridge ketika kurir mengetuk pin 📦 order lain di peta. */
   private JSONObject findOtherDeviceOrderByUuid(String uuid) {
      if (this.otherDevicesAdapter != null && uuid != null && !uuid.isEmpty()) {
         for (JSONObject o : this.otherDevicesAdapter.rawData) {
            if (uuid.equals(o.optString("uuid", ""))) return o;
         }
      }
      return null;
   }

   /** Jembatan JS "Android" di WebView peta guided (renderGuidedInline) — satu-satunya cara peta
    *  (JavaScript, tak bisa memanggil method Activity langsung) meminta detail order lain diketuk. */
   private class GuidedMapBridge {
      @JavascriptInterface
      public void showOtherDetail(String uuid) {
         DeliveryQueueActivity.this.runOnUiThread(() -> {
            if (DeliveryQueueActivity.this.isFinishing() || DeliveryQueueActivity.this.isDestroyed()) return;
            JSONObject o = DeliveryQueueActivity.this.findOtherDeviceOrderByUuid(uuid);
            if (o != null) DeliveryQueueActivity.this.showOtherDeviceOrderDetail(o);
         });
      }
   }

   private List<OtherQueueRow> buildOtherQueueRows() {
      List<OtherQueueRow> out = new ArrayList<>();
      HashSet<String> seen = new HashSet<>();
      if (this.otherDevicesAdapter != null) {
         for (JSONObject o : this.otherDevicesAdapter.rawData) {
            String uuid = o.optString("uuid", "");
            if (uuid.isEmpty() || seen.add(uuid)) out.add(this.rowFromJson(o));
         }
      }
      if (this.openDispatchAdapter != null) {
         for (Transaction t : this.openDispatchAdapter.rawData) {
            String trxUuid = this.dao.getSyncUuidById(t.getId());
            if (trxUuid == null || trxUuid.isEmpty() || seen.add(trxUuid)) out.add(this.rowFromTransaction(t));
         }
      }
      return out;
   }

   private OtherQueueRow rowFromJson(JSONObject o) {
      boolean campaignPriority = o.optBoolean(DatabaseHelper.COL_CAMP_PRIORITY, false);
      boolean orderPriority = o.optBoolean("order_priority", false);
      String customerUuid = o.optString("customer_uuid", "");
      Customer c = customerUuid.isEmpty() ? null : this.customerDao.getBySyncUuid(customerUuid);
      String badgeName = (orderPriority ? "⚡ " : "") + (campaignPriority ? "⭐ " : "")
            + (c != null && c.isIncomplete() ? "❗ " : "") + o.optString("name", "");
      long elapsedMs = elapsedMillis(o.optString("queued_at", null));
      double lat = o.optDouble("latitude", 0.0);
      double lng = o.optDouble("longitude", 0.0);
      double distKm = (lat == 0.0 && lng == 0.0) ? Double.MAX_VALUE : this.distKmFromMe(lat, lng);
      StringBuilder meta = new StringBuilder();
      if (isBeingDelivered(o)) meta.append("🚚 Sedang diantar");
      String deviceLabel = o.optString("device_group_label", "");
      if (!deviceLabel.isEmpty()) meta.append(meta.length() > 0 ? " · " : "").append("📱 ").append(deviceLabel);
      meta.append(meta.length() > 0 ? " · " : "").append(o.optInt("galon", 0)).append(" galon");
      meta.append(checkoutLegSuffix(o));
      meta.append(receiptSuffix(o.optString("receipt_no", "")));
      meta.append(orderedSuffix(o.optString("ordered_at", "")));
      meta.append(sourceWaSuffix(o.optString("source_wa", "")));
      double ongkir = o.optDouble("ongkir", 0.0);
      String locationLine = queueLocationLine(c, o, formatJarak(distKm));
      return new OtherQueueRow(o, null, badgeName, o.optString("note", ""), meta.toString(),
            ongkir > 0.0, locationLine, o.optString("items", ""),
            elapsedMs, distKm, o.optBoolean("pickup_only", false));
   }

   private OtherQueueRow rowFromTransaction(Transaction t) {
      String badgeName = (t.isOpenDispatch() ? "🎲 " : "") + (t.isCustomerPriority() ? "⭐ " : "")
            + (t.isCustomerDataIncomplete() ? "❗ " : "") + (t.isComplained() ? "😠 " : "") + safe(t.getCustomerName());
      long elapsedMs = elapsedMillis(t.getDeliveryQueuedAt());
      // distKmFromMe (bukan distOrInf): tanpa fix GPS (myLat/myLng masih 0,0 — izin ditolak, belum
      // ada lastLocation) jarak = MAX_VALUE → formatJarak null → baris 📍 tanpa jarak, bukan
      // "12.000 km" yang diukur dari titik 0,0. Cermin rowFromJson & QueueAdapter/OpenDispatchAdapter.
      double tLat = effectiveLat(t);
      double tLng = effectiveLng(t);
      double distKm = (tLat == 0.0 && tLng == 0.0) ? Double.MAX_VALUE : this.distKmFromMe(tLat, tLng);
      String meta = t.getJumlahGalon() + " galon · Rp " + formatRupiah(t.getTotalHarga()) + checkoutLegSuffix(t) + receiptSuffix(t.getReceiptNo()) + orderedSuffix(t) + sourceWaSuffix(t.getSourceWa());
      Customer c = t.getCustomerId() > 0 ? this.customerDao.getById(t.getCustomerId()) : null;
      String locationLine = queueLocationLine(areaLabel(c, t), formatJarak(distKm));
      return new OtherQueueRow(null, t, badgeName, t.getCatatan(), meta, t.getOngkir() > 0.0,
            locationLine, null, elapsedMs, distKm, isPickupOnly(t));
   }

   private double distKmFromMe(double lat, double lng) {
      if (this.myLat == 0.0 && this.myLng == 0.0) return Double.MAX_VALUE;
      return haversineKm(this.myLat, this.myLng, lat, lng);
   }

   private void claimOtherQueueRow(OtherQueueRow row) {
      if (row.json != null) {
         this.confirmTakeOverOtherDevices(row.json);
      } else if (row.trx != null) {
         this.confirmAmbilAlih(row.trx);
      }
   }

   private void openOtherQueueRowDetail(OtherQueueRow row) {
      if (row.json != null) {
         this.showOtherDeviceOrderDetail(row.json);
      } else if (row.trx != null) {
         this.showOrderDetail(row.trx);
      }
   }

   /** Adapter "Antrean Lain" gabungan dalam showGuidedFullQueueDialog — daur ulang layout
    *  item_other_device_compact.xml persis seperti OtherDevicesAdapter, tapi sumber datanya sudah
    *  digabung (OtherQueueRow) sehingga satu daftar mencakup dua sumber sekaligus. */
   private class OtherQueueRowAdapter extends RecyclerView.Adapter<OtherQueueRowAdapter.VH> {
      private final Dialog hostDialog;
      private List<OtherQueueRow> data = new ArrayList<>();
      /** Urutan aktif (tombol Umur/Jarak) — dipertahankan saat baris disusun ulang. */
      private boolean byDistance = false;

      OtherQueueRowAdapter(Dialog hostDialog) {
         this.hostDialog = hostDialog;
      }

      void setData(List<OtherQueueRow> rows, boolean byDistance) {
         this.data = rows != null ? new ArrayList<>(rows) : new ArrayList<>();
         this.sort(byDistance);
      }

      void sort(boolean byDistance) {
         this.byDistance = byDistance;
         if (byDistance) {
            this.data.sort((a, b) -> Double.compare(a.distKm, b.distKm));
         } else {
            this.data.sort((a, b) -> Long.compare(b.elapsedMs, a.elapsedMs));
         }
         this.notifyDataSetChanged();
      }

      @NonNull
      public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
         View v = LayoutInflater.from(parent.getContext()).inflate(layout.item_other_device_compact, parent, false);
         return new VH(v);
      }

      public void onBindViewHolder(@NonNull VH h, int position) {
         OtherQueueRow row = this.data.get(position);
         DeliveryQueueActivity.bindOrderNote(h.tvOrderNote, row.note);
         h.tvCustomer.setText(row.badgeName);
         DeliveryQueueActivity.bindElapsedBadge(h.tvElapsed, row.elapsedMs);
         h.tvMeta.setText(row.meta);
         h.tvOngkir.setVisibility(row.hasOngkir ? View.VISIBLE : View.GONE);
         DeliveryQueueActivity.bindPickupOnlyBadge(h.tvPickupOnly, row.pickupOnly);
         if (row.itemsCsv != null) {
            DeliveryQueueActivity.this.bindOtherDeviceChips(h.productChips, row.itemsCsv, row.json);
         } else {
            DeliveryQueueActivity.this.bindProductChips(h.productChips, row.trx);
         }
         DeliveryQueueActivity.bindLocationLine(h.tvAdminArea, row.adminAreaText);
         h.btnMore.setVisibility(View.GONE);
         boolean delivering = row.json != null && DeliveryQueueActivity.isBeingDelivered(row.json);
         h.btnTakeOver.setText(delivering ? "🚚" : "📥");
         h.btnTakeOver.setAlpha(delivering ? 0.5F : 1.0F);
         h.btnTakeOver.setOnClickListener((v) -> {
            if (delivering) {
               DeliveryQueueActivity.this.toastBeingDelivered(row.json);
               return;
            }
            this.hostDialog.dismiss();
            DeliveryQueueActivity.this.claimOtherQueueRow(row);
         });
         h.itemView.setOnClickListener((v) -> DeliveryQueueActivity.this.openOtherQueueRowDetail(row));
      }

      public int getItemCount() {
         return this.data.size();
      }

      class VH extends RecyclerView.ViewHolder {
         TextView tvCustomer, tvElapsed, tvMeta, tvOngkir, tvPickupOnly, tvAdminArea, tvOrderNote;
         LinearLayout productChips;
         MaterialButton btnMore, btnTakeOver;

         VH(View v) {
            super(v);
            this.tvCustomer = v.findViewById(id.tvCustomer);
            this.tvElapsed = v.findViewById(id.tvElapsed);
            this.tvMeta = v.findViewById(id.tvMeta);
            this.tvOngkir = v.findViewById(id.tvOngkir);
            this.tvPickupOnly = v.findViewById(id.tvPickupOnly);
            this.tvAdminArea = v.findViewById(id.tvAdminArea);
            this.tvOrderNote = v.findViewById(id.tvOrderNote);
            this.productChips = v.findViewById(id.productChips);
            this.btnMore = v.findViewById(id.btnMore);
            this.btnTakeOver = v.findViewById(id.btnTakeOver);
         }
      }
   }

   /** Tiap 30 detik (guidedRitRefillTicker) selama rit berjalan: cek apakah order BARU sudah masuk
    *  antrean dan rit yang sedang jalan masih cukup muatannya untuk menampungnya sekalian — kalau
    *  ya, tawarkan lewat showGuidedRitRefillOffer alih-alih kurir harus kembali lagi nanti. */
   private void checkGuidedRitRefill() {
      if (!this.guidedMode || this.isFinishing() || this.isDestroyed() || !this.isRunning()) return;
      int maxLoad = this.syncCfg().getMaxLoad();
      if (maxLoad <= 0) return;
      int loaded = 0;
      for (Transaction t : this.runStops()) {
         loaded += isPickupOnly(t) ? 0 : Math.max(0, t.getJumlahGalon());
      }
      if (loaded >= maxLoad) return;
      final int loadedFinal = loaded;
      final int maxLoadFinal = maxLoad;
      final TransactionDao dao = this.dao;
      new Thread(() -> {
         List<Transaction> queue = dao.getDeliveryQueue();
         List<Transaction> fits = new ArrayList<>();
         int running = loadedFinal;
         for (Transaction t : queue) {
            if (t == null || t.isOpenDispatch() || this.runningIds.contains(t.getId())
                  || this.guidedRitRefillDeclinedIds.contains(t.getId())) {
               continue;
            }
            int next = running + (isPickupOnly(t) ? 0 : Math.max(0, t.getJumlahGalon()));
            if (next <= maxLoadFinal) {
               fits.add(t);
               running = next;
            }
         }
         this.runOnUiThread(() -> {
            if (this.isFinishing() || this.isDestroyed() || fits.isEmpty()) return;
            if (this.guidedRitOffer == null || !this.guidedRitOffer.isShowing()) {
               this.showGuidedRitRefillOffer(fits);
            }
         });
      }).start();
   }

   private void showGuidedRitRefillOffer(List<Transaction> fits) {
      if (this.isFinishing() || this.isDestroyed()) return;
      this.guidedRitOffer = (new AlertDialog.Builder(this)).setIcon(android.R.drawable.ic_dialog_alert)
            .setCancelable(false).setTitle("❗ Antrean Baru Masuk")
            .setMessage(fits.size() + " order baru masuk antrean dan rit ini masih muat:\n\n"
                  + this.numberedNames(fits) + "\n\nTambahkan ke rit yang sedang berjalan sekarang?")
            .setPositiveButton("YA", (d, w) -> this.acceptGuidedRitRefill(fits))
            .setNegativeButton("Ikutkan Rit Selanjutnya", (d, w) -> {
               for (Transaction t : fits) this.guidedRitRefillDeclinedIds.add(t.getId());
            }).show();
   }

   private void acceptGuidedRitRefill(List<Transaction> fits) {
      for (Transaction t : fits) {
         this.dao.startDelivery(t.getId());
         this.runningIds.add(t.getId());
      }
      this.persistRunning();
      this.adapter.notifyDataSetChanged();
      this.renderStrategy(this.adapter.data);
      this.applyRunModeChrome(this.adapter.data);
      SyncScheduler.syncNow(this.getApplicationContext());
   }

   private static boolean isPickupOnly(Transaction t) {
      return "KEMBALI".equals(t.getType());
   }

   private static double distOrInf(Transaction t, double olat, double olng) {
      double lat = effectiveLat(t);
      double lng = effectiveLng(t);
      return lat == (double)0.0F && lng == (double)0.0F ? Double.MAX_VALUE : haversineKm(olat, olng, lat, lng);
   }

   private static String formatJarak(double km) {
      if (km == Double.MAX_VALUE) {
         return null;
      } else {
         // Koma desimal Indonesia ("1,2 km") — sama dengan tab Perangkat Lain & OtherDeviceQueueActivity,
         // supaya baris 📍 di semua kartu antrean berformat satu. Hanya untuk tampilan (tak diparse).
         return km < (double)1.0F ? Math.round(km * (double)1000.0F) + " m" : String.format(Locale.US, "%.1f km", km).replace('.', ',');
      }
   }

   private void openMapsNavigation(double lat, double lng) {
      NavigationPipActivity.startMaps(this, NavigationPipActivity.singleNavIntent(this, lat, lng, false));
   }

   private String trackLinkOrToast(Transaction t) {
      SyncSettings cfg = new SyncSettings(new SettingsDao(DatabaseHelper.getInstance(this)));
      if (!cfg.isEnrolled()) {
         Toast.makeText(this, "Perangkat belum terhubung ke server (provisioning)", 1).show();
         return null;
      } else {
         String token = t.getDeliveryToken();
         if (token != null && !token.isEmpty()) {
            return cfg.getTrackBaseUrl() + "/tracking/" + token;
         } else {
            Toast.makeText(this, "Order ini belum memiliki link lacak (dibuat sebelum fitur aktif)", 1).show();
            return null;
         }
      }
   }

   private String composeTrackMessage(Transaction t, String link) {
      return "Assalamualaikum, Pelanggan Yth. Saat ini kami sedang mengirimkan pesananmu. Terima kasih!\n\nPantau live lokasi kurir di sini:\n" + link;
   }

   private void sendTrackLink(Transaction t) {
      String link = this.trackLinkOrToast(t);
      if (link != null) {
         String phone = t.getCustomerPhone();
         if (phone != null && !phone.trim().isEmpty()) {
            // WA Bridge server dulu (akun dipilih server sesuai prioritas); gagal → buka wa.me.
            String msg = this.composeTrackMessage(t, link);
            com.crowja.damiupos.wa.WaBridgeSend.sendOrFallback(this,
                  com.crowja.damiupos.wa.WaBridgeSend.Msg.to(phone, msg).type("diantar")
                        .customerId(t.getCustomerId()).transactionId(t.getId()),
                  null,
                  () -> this.openWhatsApp(phone, msg));
         } else {
            Toast.makeText(this, "Pelanggan belum memiliki nomor WhatsApp", 0).show();
         }
      }
   }

   private void maybeWarnIncompleteThenComplete(Transaction t) {
      Customer c = t.getCustomerId() > 0L ? this.customerDao.getById(t.getCustomerId()) : null;
      if (c != null && !"Umum".equals(c.getName())) {
         if (c.hasOpenIssue() && (new SettingsDao(DatabaseHelper.getInstance(this))).isBlockDeliveryOnIssueEnabled()) {
            this.showIssueBlockThenComplete(t, c);
         } else {
            boolean noPhoto = !c.hasPhoto();
            boolean noCoord = !c.hasCoordinates();
            if (!noPhoto && !noCoord) {
               this.complete(t, false);
            } else {
               boolean revoke = (new SettingsDao(DatabaseHelper.getInstance(this))).isRevokeCreditIncompleteEnabled();
               if (revoke) {
                  this.showIncompleteCreditGateThenComplete(t, c, noPhoto, noCoord);
               } else {
                  StringBuilder missing = new StringBuilder();
                  if (noPhoto) {
                     missing.append("• Belum ada FOTO rumah\n");
                  }

                  if (noCoord) {
                     missing.append("• KOORDINAT lokasi belum ditandai\n");
                  }

                  this.playIncompleteAlertSound();
                  (new AlertDialog.Builder(this)).setIcon(17301543).setTitle("⚠️ Lengkapi Data Pelanggan!").setMessage("Pelanggan \"" + c.getName() + "\" belum lengkap:\n\n" + missing + "\nSegera FOTO rumah dan/atau TANDAI koordinat pelanggan ini sebelum meninggalkan lokasi.").setPositiveButton("LENGKAPI SEKARANG", (d, w) -> this.startActivity((new Intent(this, CustomerFormActivity.class)).putExtra("customer_id", c.getId()))).setNegativeButton("NANTI", (d, w) -> this.complete(t, false)).show();
               }
            }
         }
      } else {
         this.complete(t, false);
      }
   }

   private void showIncompleteCreditGateThenComplete(Transaction t, Customer c, boolean noPhoto, boolean noCoord) {
      StringBuilder missing = new StringBuilder();
      if (noPhoto) {
         missing.append("• Belum ada FOTO rumah\n");
      }

      if (noCoord) {
         missing.append("• KOORDINAT lokasi belum ditandai\n");
      }

      this.playIncompleteAlertSound();
      (new AlertDialog.Builder(this)).setIcon(17301543).setTitle("⚠️ Data Belum Lengkap — Amankan Kredit").setMessage("Pelanggan \"" + c.getName() + "\" belum lengkap:\n\n" + missing + "\nAgar KREDIT GALON kamu atas order ini TIDAK dicabut: lengkapi datanya sekarang, ATAU ajukan \"sudah diperbaiki + catatan\" untuk disetujui owner.").setPositiveButton("Sudah Diperbaiki…", (d, w) -> IssueResolveDialog.show(this, c, "delivery_incomplete", () -> this.doComplete(t, true))).setNeutralButton("Lengkapi Data", (d, w) -> this.startActivity((new Intent(this, CustomerFormActivity.class)).putExtra("customer_id", c.getId()))).setNegativeButton("Batal", (DialogInterface.OnClickListener)null).show();
   }

   private void playIncompleteAlertSound() {
      try {
         Uri uri = RingtoneManager.getDefaultUri(4);
         if (uri == null) {
            uri = RingtoneManager.getDefaultUri(2);
         }

         if (uri == null) {
            return;
         }

         MediaPlayer mp = new MediaPlayer();
         mp.setAudioAttributes((new AudioAttributes.Builder()).setUsage(4).setContentType(4).build());
         mp.setDataSource(this, uri);
         mp.prepare();
         mp.start();
         (new Handler(this.getMainLooper())).postDelayed(() -> {
            try {
               mp.stop();
            } catch (Exception var3) {
            }

            try {
               mp.release();
            } catch (Exception var2) {
            }

         }, 4000L);
      } catch (Exception var3) {
      }

   }

   /**
    * Pindai antrean untuk pesanan yang sudah melewati batas umur cabang, lalu bunyikan alarm +
    * popup keras SEKALI SAJA untuk tiap order. Dipanggil dari ticker 1 detik, jadi semua pembacaan
    * setelan sudah dipindahkan ke onResume.
    *
    * <p>Anti-spam tiga lapis: (a) hanya id yang BELUM pernah membunyikan yang memicu — dan daftar
    * id itu BERTAHAN antar sesi ({@link #KEY_ALARMED_LATE_IDS}), jadi order yang sama tak
    * membunyikan alarm lagi tiap kali layar antrean dibuka ulang, (b) jeda 10 menit antar bunyi dan
    * 15 menit bila kurir menekan "TUNDA", (c) satu dialog per layar. Yang dibungkam HANYA bunyi +
    * popup -- kedip merah, badge sirene, dan posisi puncak antrean tetap jalan, jadi pesanan gawat
    * tetap terlihat sepanjang ia masih di antrean.
    */
   private void maybeRaiseLateAlarm() {
      if (lateMs <= 0L || this.selectionMode || this.claimSelectOpen || this.claimSelectOther) {
         return;
      }

      List<Transaction> late = new ArrayList();
      this.collectLate(this.adapter != null ? this.adapter.shown() : null, late);
      this.collectLate(this.openDispatchAdapter != null ? this.openDispatchAdapter.data : null, late);
      if (late.isEmpty()) {
         return;
      }

      boolean fresh = false;

      for(Transaction t : late) {
         if (!this.alarmedLateIds.contains(t.getId())) {
            fresh = true;
            break;
         }
      }

      long now = System.currentTimeMillis();
      if (!fresh || now < this.lateAlarmSnoozeUntilMs) {
         return;
      }

      for(Transaction t : late) {
         this.alarmedLateIds.add(t.getId());
      }

      this.rememberAlarmedLate();
      this.lateAlarmSnoozeUntilMs = now + LATE_ALARM_COOLDOWN_MS;
      // Popup kini opsional (Pengaturan → "Popup Pesanan Terlambat", bawaan MATI); tanda merah di kartu tetap.
      if ("1".equals((new SettingsDao(DatabaseHelper.getInstance(this))).get(K_LATE_ALARM_POPUP, "0"))) {
         this.showLateAlarmDialog(late);
      }
   }

   /** Baca kembali id yang sudah pernah membunyikan alarm. Id yang tak terbaca dilewati diam-diam:
    *  gagal memuat daftar ini paling buruk cuma membunyikan alarm sekali lagi, tak pantas
    *  menggagalkan pembukaan layar antrean. */
   private void restoreAlarmedLate() {
      String raw = (new SettingsDao(DatabaseHelper.getInstance(this))).get(KEY_ALARMED_LATE_IDS, "");
      if (raw != null && !raw.isEmpty()) {
         for(String part : raw.split(",")) {
            try {
               this.alarmedLateIds.add(Long.valueOf(part.trim()));
            } catch (Exception var6) {
            }
         }

      }
   }

   private void rememberAlarmedLate() {
      // Buang yang tertua dulu supaya daftarnya tak tumbuh selamanya (LinkedHashSet = urut sisip).
      while(this.alarmedLateIds.size() > ALARMED_LATE_IDS_MAX) {
         java.util.Iterator<Long> it = this.alarmedLateIds.iterator();
         it.next();
         it.remove();
      }

      StringBuilder sb = new StringBuilder();

      for(Long id : this.alarmedLateIds) {
         if (sb.length() > 0) {
            sb.append(',');
         }

         sb.append(id);
      }

      (new SettingsDao(DatabaseHelper.getInstance(this))).set(KEY_ALARMED_LATE_IDS, sb.toString());
   }

   private void collectLate(List<Transaction> src, List<Transaction> out) {
      if (src != null) {
         for(Transaction t : src) {
            // Order yang VOID-nya sedang diajukan tak bisa dikirim lagi -- membunyikan alarm untuknya
            // hanya melatih kurir mengabaikan alarm.
            if (t != null && t.getVoidRequestPendingAt() == null && isLate(t)) {
               out.add(t);
            }
         }

      }
   }

   /** SettingsDao: "1" = tampilkan popup Pesanan Terlambat (lokal per HP, bawaan "0"). */
   public static final String K_LATE_ALARM_POPUP = "late_alarm_popup_enabled";

   private void showLateAlarmDialog(List<Transaction> late) {
      if (!this.isFinishing() && !this.isDestroyed() && (this.lateDialog == null || !this.lateDialog.isShowing())) {
         List<Transaction> sorted = new ArrayList(late);
         Collections.sort(sorted, (a, b) -> Long.compare(elapsedMillis(b.getDeliveryQueuedAt()), elapsedMillis(a.getDeliveryQueuedAt())));
         StringBuilder sb = new StringBuilder();
         sb.append(sorted.size()).append(" pesanan sudah melewati batas ").append(formatDuration(lateMs)).append(" di antrean:\n\n");

         for(int i = 0; i < sorted.size() && i < 5; ++i) {
            Transaction t = (Transaction)sorted.get(i);
            sb.append("• ").append(safe(t.getCustomerName())).append(" — ⏱ ").append(formatDuration(elapsedMillis(t.getDeliveryQueuedAt()))).append("\n");
         }

         if (sorted.size() > 5) {
            sb.append("…dan ").append(sorted.size() - 5).append(" lainnya\n");
         }

         sb.append("\nSegera kirim, atau tandai TERTUNDA bila memang belum bisa diantar hari ini.");
         if (this.revokeLateCredit) {
            sb.append("\n\nPOIN GALON TIDAK akan dikreditkan untuk pesanan yang diselesaikan setelah lewat batas.");
         }

         Transaction oldest = (Transaction)sorted.get(0);
         this.playIncompleteAlertSound();
         // Sengaja TIDAK setCancelable(false): layar ini dibuka kurir yang mungkin sedang di jalan,
         // gerbang keras yang tak bisa ditutup di situ berbahaya. Gerbang keras hanya untuk keputusan
         // (pelanggan Umum, order ganda), bukan untuk pengingat.
         this.lateDialog = (new AlertDialog.Builder(this)).setIcon(17301543).setTitle("🚨 PESANAN TERLAMBAT — SEGERA KIRIM!").setMessage(sb.toString()).setPositiveButton("KIRIM SEKARANG", (d, w) -> this.scrollToOrder(oldest)).setNegativeButton("TUNDA & BERITAHU PELANGGAN", (d, w) -> this.snoozeLateAlarmAndNotify(oldest)).create();
         this.lateDialog.setOnDismissListener((d) -> this.lateDialog = null);
         this.lateDialog.show();
      }
   }

   /** "TUNDA & BERITAHU PELANGGAN" pada alarm terlambat: snooze alarmnya (sama seperti dulu) DAN
    *  buka WA ke pelanggan supaya kurir tinggal kirim, bukan menunda diam-diam tanpa pelanggan tahu. */
   private void snoozeLateAlarmAndNotify(Transaction t) {
      this.lateAlarmSnoozeUntilMs = System.currentTimeMillis() + LATE_ALARM_SNOOZE_MS;
      String phone = t != null ? t.getCustomerPhone() : null;
      if (phone == null || phone.trim().isEmpty()) {
         Toast.makeText(this, "Pesanan ditunda 15 menit — pelanggan tak punya nomor WA tersimpan", Toast.LENGTH_LONG).show();
         return;
      }
      String msg = "Assalamualaikum, Pelanggan Yth.\n\nMohon maaf, pesanan air minum Anda "
            + "sedikit tertunda dari perkiraan. Kami akan segera mengantarnya. Terima kasih atas kesabarannya 🙏";

      // WA Bridge server DULU (akun dipilih server sesuai prioritas; jenis "apology" = WA
      // permintaan maaf order terlambat); gagal → jalur HP lama di bawah.
      com.crowja.damiupos.wa.WaBridgeSend.sendOrFallback(this,
            com.crowja.damiupos.wa.WaBridgeSend.Msg.to(phone, msg).type("apology").proactive()
                  .customerId(t.getCustomerId()).transactionId(t.getId()),
            null,
            () -> this.sendLateNoticeViaPhone(phone, msg));
   }

   /** Jalur HP lama "TUNDA & BERITAHU PELANGGAN" — dipakai saat kirim lewat WA Bridge gagal. */
   private void sendLateNoticeViaPhone(String phone, String msg) {
      // Dashboard bisa meminta auto-kirim langsung (tanpa membuka intent WhatsApp ke staf lebih
      // dulu). Gagal/tak terkonfirmasi → jatuh ke intent manual lama seperti sebelum flag ini ada.
      SettingsDao autoSettings = new SettingsDao(DatabaseHelper.getInstance(this));
      if (autoSettings.isAutoSendOrderHoldWa()) {
         new Thread(() -> {
            String outcome = com.crowja.damiupos.wa.WaGateway.send(getApplicationContext(), phone, msg);
            if (!com.crowja.damiupos.wa.WaGateway.SENT.equals(outcome)) {
               runOnUiThread(() -> this.openWhatsApp(phone, msg));
            }
         }).start();
         return;
      }
      this.openWhatsApp(phone, msg);
   }

   private void scrollToOrder(Transaction t) {
      if (this.adapter != null && t != null) {
         List<Transaction> shown = this.adapter.shown();

         for(int i = 0; i < shown.size(); ++i) {
            if (((Transaction)shown.get(i)).getId() == t.getId()) {
               this.rv.smoothScrollToPosition(i);
               return;
            }
         }

      }
   }

   private void showIssueBlockThenComplete(Transaction t, Customer c) {
      StringBuilder cats = new StringBuilder();

      for(String label : c.issueLabelList()) {
         cats.append("• ").append(label).append("\n");
      }

      String note = c.getIssueNote() != null && !c.getIssueNote().isEmpty() ? "\nCatatan pelapor: " + c.getIssueNote() + "\n" : "";
      this.playIncompleteAlertSound();
      (new AlertDialog.Builder(this)).setIcon(17301543).setTitle("⚠️ Pelanggan Bermasalah — Perbaiki Dulu").setMessage("Detail pelanggan \"" + c.getName() + "\" ditandai BERMASALAH dan belum diperbaiki:\n\n" + cats + note + "\nPerbaiki dulu, lalu tandai \"sudah diperbaiki\" (butuh persetujuan owner) sebelum menyelesaikan delivery ini.").setPositiveButton("Sudah Diperbaiki…", (d, w) -> IssueResolveDialog.show(this, c, "delivery", () -> this.doComplete(t, false))).setNeutralButton("Perbaiki Data", (d, w) -> this.startActivity((new Intent(this, CustomerFormActivity.class)).putExtra("customer_id", c.getId()))).setNegativeButton("Batal", (DialogInterface.OnClickListener)null).show();
   }

   /**
    * Gerbang informatif sebelum konfirmasi Selesai: bila cabang mengaktifkan pencabutan poin untuk
    * pesanan terlambat DAN pesanan ini memang sudah lewat batas, beri tahu kurir dulu. HP TIDAK
    * menulis kolom apa pun di sini -- server yang memutuskan saat laporan dibaca
    * (App\Support\LateDeliveryGuard), supaya ambangnya tak pernah terduplikasi di dua repo.
    */
   private void complete(Transaction t, boolean revokeCredit) {
      if (this.revokeLateCredit && isLate(t)) {
         long lateBy = elapsedMillis(t.getDeliveryQueuedAt());
         this.playIncompleteAlertSound();
         (new AlertDialog.Builder(this)).setIcon(17301543).setTitle("⚠️ Order Terlambat — Poin Tidak Dikreditkan").setMessage("Order \"" + safe(t.getCustomerName()) + "\" sudah menunggu " + formatDuration(lateBy) + " di antrean, melewati batas " + formatDuration(lateMs) + " yang ditetapkan depot.\n\nBila diselesaikan sekarang, POIN GALON order ini tidak akan dikreditkan ke siapa pun.").setPositiveButton("SELESAIKAN TANPA POIN", (d, w) -> this.completeConfirm(t, revokeCredit)).setNegativeButton("Batal", (DialogInterface.OnClickListener)null).show();
      } else {
         this.completeConfirm(t, revokeCredit);
      }
   }

   private void completeConfirm(Transaction t, boolean revokeCredit) {
      long ms = elapsedMillis(t.getDeliveryQueuedAt());
      boolean isPickup = "KEMBALI".equals(t.getType());
      String msg = (isPickup ? "Galon kembali dari \"" + safe(t.getCustomerName()) + "\" sudah diambil (pickup)?" : "Order \"" + safe(t.getCustomerName()) + "\" sudah selesai diantar?") + "\n\nLama proses: " + formatDuration(ms);
      if (!isPickup) {
         msg = msg + "\n\n" + this.orderDetailText(t);
      }

      boolean allocatable = "JUAL".equals(t.getType()) && t.getJumlahGalon() >= 1;
      if (allocatable) {
         String staffName = (new SettingsDao(DatabaseHelper.getInstance(this))).getCurrentUserName();
         msg = msg + "\n\nAlokasi Poin: " + (staffName != null && !staffName.isEmpty() ? staffName : "kamu") + " (" + t.getJumlahGalon() + " galon)";
      }

      AlertDialog.Builder b = (new AlertDialog.Builder(this)).setTitle(isPickup ? "Tandai Selesai (Pickup)" : "Tandai Selesai").setMessage(msg).setPositiveButton("Selesai", (d, w) -> this.confirmReturnedGalonThenComplete(t, revokeCredit)).setNegativeButton("Batal", (DialogInterface.OnClickListener)null);
      if (!isPickup) {
         // "Ubah…" = dialog ✏️ Ubah Pesanan yang sama dengan menu kartu (produk, harga, ongkir, galon kembali,
         // alasan). Setelah tersimpan, data dimuat ulang lalu konfirmasi Selesai dibuka lagi dengan isi terbaru.
         b.setNeutralButton("Ubah…", (d, w) -> DeliveryEditDialog.show(this, t, () -> {
            this.loadData();
            Transaction fresh = this.dao.getById(t.getId());
            Transaction row = null;
            if (this.adapter != null && this.adapter.data != null) {
               for (Transaction q : this.adapter.data) if (q != null && q.getId() == t.getId()) row = q;
            }
            Transaction use = row != null ? row : fresh;
            if (use != null && Transaction.DELIVERY_PENDING.equals(use.getDeliveryStatus() != null ? use.getDeliveryStatus() : Transaction.DELIVERY_PENDING)) {
               this.completeConfirm(use, revokeCredit);
            }
         }));
      } else if (allocatable) {
         b.setNeutralButton("Ubah Alokasi", (d, w) -> AllocationDialog.show(this, t, "delivery"));
      }

      b.show();
   }

   /**
    * Gerbang terakhir sebelum order ditandai Selesai: KONFIRMASI GALON KEMBALI. Nilai awalnya =
    * yang sudah tercatat saat transaksi dibuat (baris KEMBALI berpasangan), jadi kurir yang tak
    * menemukan perubahan cukup mengetuk dua kali tanpa mengetik apa pun.
    *
    * <p>Sengaja DUA KETUKAN: angka ini menggerakkan dua buku sekaligus — "Galon Dipinjam" milik
    * pelanggan dan stok fisik depot. Salahnya tidak kelihatan hari itu juga, baru ketahuan
    * berhari-hari kemudian ketika saldo pelanggan dipersoalkan, saat sudah tak ada yang ingat
    * berapa galon yang sebenarnya diterima. Ketukan kedua juga menahan kebiasaan "Selesai" beruntun
    * yang membuat isian default lolos tanpa pernah benar-benar dibaca.
    *
    * <p>Pickup (transaksi KEMBALI) dilewati: galon yang dibawa pulang ADALAH transaksi itu sendiri,
    * jadi menanyakannya lagi di sini justru menyiratkan ada angka kedua yang tidak pernah ada.
    */
   private void confirmReturnedGalonThenComplete(Transaction t, boolean revokeCredit) {
      if (!"JUAL".equals(t.getType())) {
         this.doComplete(t, revokeCredit);
         return;
      }

      int seeded = this.dao.getReturnedGalonForSale(t.getCustomerId(), t.getTanggal());
      float density = this.getResources().getDisplayMetrics().density;
      int pad = (int)(20.0F * density);
      int gap = (int)(8.0F * density);

      EditText qtyIn = new EditText(this);
      qtyIn.setInputType(2);
      qtyIn.setText(String.valueOf(seeded));
      qtyIn.setSelection(qtyIn.getText().length());
      qtyIn.setHint("Jumlah galon kosong diterima");

      TextView hint = new TextView(this);
      hint.setText("Terisi otomatis dari transaksi saat dibuat. Ubah bila galon yang benar-benar "
            + "diterima berbeda.");
      hint.setTextSize(12.0F);
      hint.setTextColor(-8355712);
      hint.setPadding(0, gap, 0, 0);

      LinearLayout box = new LinearLayout(this);
      box.setOrientation(1);
      box.setPadding(pad, gap, pad, 0);
      box.addView(qtyIn);
      box.addView(hint);

      AlertDialog dlg = (new AlertDialog.Builder(this))
            .setIcon(17301543)
            .setTitle("❗ Konfirmasi Galon Kembali")
            .setMessage("Berapa galon KOSONG yang diterima dari \"" + safe(t.getCustomerName()) + "\"?")
            .setView(box)
            .setPositiveButton("KONFIRMASI", (DialogInterface.OnClickListener)null)
            .setNegativeButton("Batal", (DialogInterface.OnClickListener)null)
            .create();

      // Ketukan PERTAMA cuma mengokang tombolnya, ketukan KEDUA yang menyelesaikan. Listener
      // dipasang lewat setOnShowListener karena listener bawaan setPositiveButton selalu menutup
      // dialog, sehingga ketukan pertama akan langsung meloloskan order.
      dlg.setOnShowListener((shown) -> {
         android.widget.Button ok = dlg.getButton(-1);
         boolean[] armed = new boolean[1];
         // Mengubah angkanya membatalkan kokangan: yang dikonfirmasi harus angka yang benar-benar
         // dilihat kurir pada ketukan terakhir, bukan angka sebelum ia mengetik ulang.
         qtyIn.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            public void afterTextChanged(Editable e) {
               if (armed[0]) {
                  armed[0] = false;
                  ok.setText("KONFIRMASI");
               }

            }
         });
         ok.setOnClickListener((btn) -> {
            int qty;
            try {
               qty = Integer.parseInt(qtyIn.getText().toString().trim());
            } catch (Exception var6) {
               qtyIn.setError("Angka tidak valid");
               return;
            }

            if (qty < 0) {
               qtyIn.setError("Tidak boleh minus");
            } else if (!armed[0]) {
               armed[0] = true;
               ok.setText("YAKIN? KETUK LAGI");
            } else {
               // Hanya ditulis bila memang berubah — menulis ulang angka yang sama tetap membuat
               // baris KEMBALI ikut terkirim lagi sebagai "diedit" ke dashboard tanpa sebab.
               if (qty != seeded) {
                  this.dao.applyReturnedGalon(t.getId(), qty);
               }

               dlg.dismiss();
               Transaction fresh = this.dao.getById(t.getId());
               this.askPaymentThenComplete(fresh != null ? fresh : t, qty, revokeCredit);
            }
         });
      });
      dlg.show();
   }

   private static final String[] KASBON_REASON_PRESETS = {
         TransactionDao.CASH_BON_REASON_AWAY, TransactionDao.CASH_BON_REASON_UNPAID};

   /**
    * Langkah sesudah galon kembali: NOMINAL DITERIMA (bawaan = tagihan pesanan) atau KASBON
    * (bayar di pesanan berikutnya). Dilewati bila order sudah LUNAS (bukti bayar dikonfirmasi agen),
    * sudah Cash Bon / HUTANG, atau tagihannya Rp0. Kasbon ditulis lewat applyDeliveryAdjustment
    * (penanda [CASH BON] + alasan) → doComplete mewajibkan foto → finishComplete mengirim WA
    * pemberitahuan sopan beserta fotonya ke pelanggan.
    */
   private void askPaymentThenComplete(Transaction t, int returnQty, boolean revokeCredit) {
      double total = t.getTotalHarga();
      String pm = t.getPaymentMethod() != null ? t.getPaymentMethod().trim() : "";
      if (total <= 0 || t.isPaymentConfirmed() || this.isCashBon(t) || "HUTANG".equalsIgnoreCase(pm)) {
         this.doComplete(t, revokeCredit);
         return;
      }
      float dn = this.getResources().getDisplayMetrics().density;
      int pad = (int)(20.0F * dn);
      int gap = (int)(8.0F * dn);
      LinearLayout box = new LinearLayout(this);
      box.setOrientation(1);
      box.setPadding(pad, gap, pad, 0);

      TextView lbl = new TextView(this);
      lbl.setText("Nominal diterima (Rp)");
      lbl.setTextSize(13.0F);
      box.addView(lbl);
      EditText amt = new EditText(this);
      amt.setInputType(2);
      amt.setText(String.valueOf(Math.round(total)));
      amt.setSelectAllOnFocus(true);
      box.addView(amt);

      android.widget.CheckBox kasbon = new android.widget.CheckBox(this);
      kasbon.setText("Kasbon — dibayar di pesanan berikutnya");
      kasbon.setPadding(0, gap, 0, gap);
      box.addView(kasbon);

      LinearLayout kb = new LinearLayout(this);
      kb.setOrientation(1);
      kb.setVisibility(View.GONE);
      EditText reason = new EditText(this);
      reason.setHint("Alasan kasbon (pilih atau ketik)");
      reason.setText(TransactionDao.CASH_BON_REASON_AWAY);
      kb.addView(DeliveryVoidDialog.buildQuickReasonRow(this, reason, KASBON_REASON_PRESETS));
      kb.addView(reason);
      TextView kh = new TextView(this);
      kh.setText("📷 Kasbon WAJIB foto bukti. Pelanggan akan dikabari lewat WA beserta fotonya.");
      kh.setTextSize(12.0F);
      kh.setTextColor(-8355712);
      kb.addView(kh);
      box.addView(kb);
      kasbon.setOnCheckedChangeListener((b, on) -> {
         kb.setVisibility(on ? View.VISIBLE : View.GONE);
         amt.setEnabled(!on);
      });

      ScrollView sv = new ScrollView(this);
      sv.addView(box);
      AlertDialog dlg = (new AlertDialog.Builder(this))
            .setTitle("💰 Pembayaran — " + safe(t.getCustomerName()))
            .setMessage("Tagihan pesanan: " + this.rp(total) + (pm.isEmpty() ? "" : " · " + pm))
            .setView(sv)
            .setPositiveButton("SELESAI", (DialogInterface.OnClickListener)null)
            .setNegativeButton("Batal", (DialogInterface.OnClickListener)null)
            .create();
      dlg.setOnShowListener((shown) -> dlg.getButton(-1).setOnClickListener((btn) -> {
         boolean isBon = kasbon.isChecked();
         Double paid = null;
         String why = null;
         if (isBon) {
            why = reason.getText().toString().trim();
            if (why.isEmpty()) {
               reason.setError("Isi alasannya");
               return;
            }
            boolean preset = java.util.Arrays.asList(KASBON_REASON_PRESETS).contains(why);
            if (!preset) why = TransactionDao.CASH_BON_REASON_OTHER + " — " + why;
         } else {
            try {
               paid = Double.parseDouble(amt.getText().toString().trim());
            } catch (Exception e) {
               amt.setError("Isi nominal (0 = centang Kasbon)");
               return;
            }
            if (paid < 0) {
               amt.setError("Tidak boleh minus");
               return;
            }
            if (paid <= 0) {
               kasbon.setChecked(true);
               return;
            }
         }
         // Nominal sama dengan tagihan & bukan kasbon → tak ada yang perlu dicatat ulang.
         if (isBon || Math.round(paid) != Math.round(total)) {
            String by = (new SettingsDao(DatabaseHelper.getInstance(this))).getCurrentUserName();
            this.dao.applyDeliveryAdjustment(t.getId(), null, returnQty, pm, isBon, paid, why, by);
         }
         dlg.dismiss();
         Transaction fresh = this.dao.getById(t.getId());
         this.doComplete(fresh != null ? fresh : t, revokeCredit);
      }));
      dlg.show();
   }

   private String rp(double v) {
      return "Rp " + NumberFormat.getNumberInstance(new Locale("in", "ID")).format(Math.round(v));
   }

   /** Hasil "🚰 Ubah Jenis Galon" dari server: jenis, sumber, rincian (JSON / null). */
   private interface GalonTypeSaved {
      void saved(String type, String source, String specJson);
   }

   /** 🚰 Jenis Galon — order antrean SENDIRI / Pesanan Terbuka (baris lokal). */
   private void showGalonTypeDialog(Transaction t) {
      String uuid = this.dao.getSyncUuidById(t.getId());
      if (uuid == null || uuid.isEmpty()) {
         Toast.makeText(this, "Order ini belum tersinkron ke server — coba lagi sebentar.", Toast.LENGTH_SHORT).show();
         return;
      }
      LinkedHashMap<String, Integer> slugQty = new LinkedHashMap<>();
      for (Map.Entry<String, int[]> e : this.mergedChipItems(t.getItems()).entrySet()) slugQty.put(e.getKey(), e.getValue()[0]);
      this.galonTypeDialog(safe(t.getCustomerName()), uuid, t.getGalonType(), t.getGalonSpec(), slugQty, (type, source, spec) -> {
         this.dao.applyGalonTypeLocal(t.getId(), type, source, spec);
         this.loadData();
      });
   }

   /** 🚰 Jenis Galon — kartu tab Perangkat Lain (baris server). */
   private void showGalonTypeDialog(JSONObject q) {
      String uuid = q.optString("uuid", "");
      if (uuid.isEmpty()) return;
      LinkedHashMap<String, Integer> slugQty = new LinkedHashMap<>();
      for (Map.Entry<String, int[]> e : this.mergedCsvChips(q.optString("items", "")).entrySet()) {
         if (e.getValue()[0] > 0) slugQty.put(e.getKey(), e.getValue()[0]);
      }
      Object spec = q.opt("galon_spec");
      this.galonTypeDialog(safe(strJson(q, "name")), uuid, strJson(q, "galon_type"), spec != null ? spec.toString() : null, slugQty,
            (type, source, s) -> this.loadOtherDevices());
   }

   /**
    * Pilih Keran / Non-keran / Aqua / Bebas, atau "Campur" → jumlah per produk per jenis. Disimpan di server
    * (POST /api/transactions/{uuid}/galon-type — sumber "staff": catatan yang diedit belakangan tak menimpanya)
    * dan tercatat di Jurnal Pesanan; {@code done} mencerminkan hasilnya ke tampilan.
    */
   private void galonTypeDialog(String name, String uuid, String current, String currentSpec,
                                LinkedHashMap<String, Integer> slugQty, GalonTypeSaved done) {
      String[] keys = {GalonTypeUi.KERAN, GalonTypeUi.NON_KERAN, GalonTypeUi.AQUA, GalonTypeUi.BEBAS, GalonTypeUi.CAMPUR};
      String[] labels = {"🚰 Keran", "⭕ Non-keran", "💧 Aqua", "✳️ Bebas (yang mana saja)", "🔀 Campur — atur per produk…"};
      int checked = -1;
      for (int i = 0; i < keys.length; i++) if (keys[i].equals(current)) checked = i;
      (new AlertDialog.Builder(this)).setTitle("🚰 Jenis Galon — " + name)
            .setSingleChoiceItems(labels, checked, (d, which) -> {
               d.dismiss();
               if (GalonTypeUi.CAMPUR.equals(keys[which])) {
                  this.galonMixDialog(name, uuid, currentSpec, slugQty, done);
               } else {
                  this.saveGalonType(uuid, keys[which], null, done);
               }
            })
            .setNegativeButton("Batal", (DialogInterface.OnClickListener) null).show();
   }

   /** Campur: per produk, jumlah Keran / Non-keran / Aqua — total per produk wajib = qty pesanan. */
   private void galonMixDialog(String name, String uuid, String currentSpec, LinkedHashMap<String, Integer> slugQty, GalonTypeSaved done) {
      if (slugQty.isEmpty()) {
         this.saveGalonType(uuid, GalonTypeUi.CAMPUR, null, done);
         return;
      }
      String[] types = {GalonTypeUi.KERAN, GalonTypeUi.NON_KERAN, GalonTypeUi.AQUA};
      Map<String, LinkedHashMap<String, Integer>> cur = GalonTypeUi.specBySlug(currentSpec);
      LinearLayout box = new LinearLayout(this);
      box.setOrientation(LinearLayout.VERTICAL);
      int pad = this.dp(20f);
      box.setPadding(pad, this.dp(8f), pad, 0);
      List<Object[]> inputs = new ArrayList<>();   // {slug, qty, EditText[3]}
      for (Map.Entry<String, Integer> e : slugQty.entrySet()) {
         TextView head = new TextView(this);
         head.setText(e.getKey() + " ×" + e.getValue());
         head.setTypeface(head.getTypeface(), android.graphics.Typeface.BOLD);
         head.setPadding(0, this.dp(8f), 0, 0);
         box.addView(head);
         LinearLayout row = new LinearLayout(this);
         row.setOrientation(LinearLayout.HORIZONTAL);
         EditText[] ets = new EditText[types.length];
         LinkedHashMap<String, Integer> have = cur.get(e.getKey().trim().toUpperCase(Locale.US));
         for (int i = 0; i < types.length; i++) {
            EditText et = new EditText(this);
            et.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
            et.setHint(GalonTypeUi.label(types[i]));
            Integer v = have != null ? have.get(types[i]) : null;
            if (v != null) et.setText(String.valueOf(v));
            else if (have == null && i == 0) et.setText(String.valueOf(e.getValue()));
            row.addView(et, new LinearLayout.LayoutParams(0, -2, 1f));
            ets[i] = et;
         }
         box.addView(row);
         TextView legend = new TextView(this);
         legend.setText("Keran · Non-keran · Aqua");
         legend.setTextSize(11f);
         box.addView(legend);
         inputs.add(new Object[]{e.getKey(), e.getValue(), ets});
      }
      ScrollView sv = new ScrollView(this);
      sv.addView(box);
      AlertDialog dlg = (new AlertDialog.Builder(this)).setTitle("🔀 Campur — " + name).setView(sv)
            .setPositiveButton("Simpan", (DialogInterface.OnClickListener) null)
            .setNegativeButton("Batal", (DialogInterface.OnClickListener) null).create();
      dlg.setOnShowListener(x -> dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
         JSONArray spec = new JSONArray();
         java.util.Set<String> used = new java.util.HashSet<>();
         for (Object[] in : inputs) {
            String slug = (String) in[0];
            int want = (Integer) in[1];
            EditText[] ets = (EditText[]) in[2];
            int sum = 0;
            for (int i = 0; i < types.length; i++) {
               int n;
               try {
                  n = ets[i].getText().toString().trim().isEmpty() ? 0 : Integer.parseInt(ets[i].getText().toString().trim());
               } catch (NumberFormatException ex) {
                  n = -1;
               }
               if (n < 0) {
                  ets[i].setError("Angka tidak valid");
                  return;
               }
               if (n > 0) {
                  try {
                     spec.put(new JSONObject().put("slug", slug).put("qty", n).put("type", types[i]));
                  } catch (Exception ignored) {
                  }
                  used.add(types[i]);
               }
               sum += n;
            }
            if (sum != want) {
               ets[0].setError(slug + ": jumlah " + sum + " ≠ " + want + " galon");
               return;
            }
         }
         dlg.dismiss();
         // Ternyata hanya satu jenis → simpan sebagai jenis tunggal itu, bukan "Campur".
         if (used.size() == 1) this.saveGalonType(uuid, used.iterator().next(), null, done);
         else this.saveGalonType(uuid, GalonTypeUi.CAMPUR, spec, done);
      }));
      dlg.show();
   }

   private void saveGalonType(String uuid, String type, JSONArray spec, GalonTypeSaved done) {
      SyncSettings cfg = this.syncCfg();
      (new Thread(() -> {
         String msg;
         JSONObject res = null;
         try {
            JSONObject body = new JSONObject().put("galon_type", type);
            if (spec != null) body.put("galon_spec", spec);
            res = (new SyncApi(cfg)).setGalonType(uuid, body);
            String warn = strJson(res, "warning");
            msg = warn != null && !warn.isEmpty() ? warn : "🚰 Jenis galon: " + GalonTypeUi.label(strJson(res, "galon_type"));
         } catch (SyncApi.SyncException se) {
            msg = extractRouteErrorMessage(se.body);
            if (msg == null) msg = "Gagal menyimpan (kode " + se.code + ").";
         } catch (Exception e) {
            msg = "Gagal menyimpan — periksa koneksi internet.";
         }
         final String m = msg;
         final JSONObject r = res;
         this.runOnUiThread(() -> {
            if (this.isFinishing() || this.isDestroyed()) return;
            Toast.makeText(this, m, Toast.LENGTH_LONG).show();
            if (r != null && r.optBoolean("ok", false)) {
               Object sp = r.opt("galon_spec");
               done.saved(strJson(r, "galon_type"), strJson(r, "galon_type_source"),
                     sp instanceof JSONArray ? sp.toString() : null);
            }
         });
      })).start();
   }

   private String orderDetailText(Transaction t) {
      StringBuilder sb = new StringBuilder();
      List<TransactionItem> items = t.getItems();
      if (items != null && !items.isEmpty()) {
         for(TransactionItem it : items) {
            if (it != null && it.jumlah > 0) {
               sb.append("• ").append(it.productName).append(" × ").append(it.jumlah).append("  ").append(this.rp(it.getSubtotal())).append("\n");
            }
         }
      } else if (t.getJumlahGalon() > 0) {
         sb.append("• ").append(t.getJumlahGalon()).append(" galon\n");
      }

      String jenis = DeliveryPlanner.isPickupOnly(t) ? "" : GalonTypeUi.summary(t.getGalonType(), t.getGalonTypeSource(), t.getGalonSpec());
      if (!jenis.isEmpty()) sb.append(GalonTypeUi.emoji(t.getGalonType())).append(" Jenis galon: ").append(jenis).append("\n");
      int kembali = this.dao.getReturnedGalonForSale(t.getCustomerId(), t.getTanggal());
      sb.append("Galon kembali: ").append(kembali).append("\n");
      String pay = t.getPaymentMethodLabel();
      boolean bon = t.getCatatan() != null && t.getCatatan().contains("[CASH BON]");
      sb.append("Pembayaran: ").append(bon ? "Cash Bon (hutang)" : (pay.isEmpty() ? "—" : pay)).append("\n");
      sb.append("Total: ").append(this.rp(t.getTotalHarga()));
      sb.append(this.refundSummaryText(t));
      sb.append(this.debtSummaryText(t));
      // 🧺 Leg checkout multi-lokasi: yang ditagih di pintu ini HANYA pesanan lokasi ini.
      String legHint = checkoutCollectHint(t);
      if (!legHint.isEmpty()) sb.append("\n\n").append(legHint);
      return sb.toString();
   }

   /**
    * Baris HUTANG untuk popup "Tandai Selesai" & "Detail Order": piutang lama pelanggan ini plus
    * TAGIHAN DI PINTU (penjualan ini + piutang lama) — angka yang sebenarnya harus diterima kurir.
    *
    * <p>Tanpa ini kurir hanya melihat "Total: Rp 9.000" lalu menerima uang segitu, padahal
    * pelanggannya masih menunggak: piutangnya tak pernah tertagih justru pada satu-satunya momen
    * kurir berhadapan langsung dengan orangnya. Angkanya memakai perhitungan yang SAMA dengan
    * popup Ubah & penyelesaian order ({@code balanceExcludingTransaction}), jadi ketiganya tak
    * mungkin menyebut jumlah berbeda.</p>
    *
    * <p>Piutang milik transaksi INI SENDIRI dikecualikan — order yang memang dibuat berstatus
    * HUTANG akan terhitung dua kali kalau ikut dijumlahkan. Tak ada tunggakan → '' (dialognya tetap
    * ringkas untuk mayoritas order yang lunas).</p>
    *
    * <p>🧺 Leg checkout multi-lokasi: HUTANG SEGAR leg saudaranya (checkout_uuid sama di
    * customer_debts, tabel branch-wide) juga BUKAN hutang lama — tiap lokasi ditagih di pintunya
    * sendiri. Tak bergantung pada transaksi saudara ada di HP ini. Hutang LAMA-nya pun hanya ditagih
    * di satu pintu ({@code TransactionDao.oldDebtDoor}); di pintu lain baris ini menyebut di mana
    * ditagihnya dan "Total harus diterima" = penjualan lokasi ini saja.</p>
    */
   private String debtSummaryText(Transaction t) {
      PriorDebt pd = priorDebtFor(DatabaseHelper.getInstance(this), t);
      double prior = pd.amount;
      if (prior <= (double)0.0F) {
         return "";
      }
      // 🧺 Hutang lama ditagih di SATU pintu (DebtBalance::priorForDoor). Pintu lain: tagihannya
      // hanya penjualan lokasi ini — kurir diberi tahu di mana hutang lamanya ditagih.
      if (!pd.here()) {
         // Leg pintu tagih tak ada di HP ini (dirutekan ke HP lain / di-void) → kalimat bersyarat.
         return "\n" + CheckoutStrukText.oldDebtElsewhereLine(pd.door, this.rp(prior))
               + "\nTotal harus diterima: " + this.rp(t.getTotalHarga());
      }
      return "\nHutang sebelumnya: " + this.rp(prior)
            + "\nTotal harus diterima: " + this.rp(t.getTotalHarga() + prior);
   }

   /**
    * Hutang LAMA pelanggan untuk pintu sebuah order + pintu tagihnya — SATU sumber untuk popup
    * antrean ({@link #debtSummaryText}) dan jendela melayang navigasi (NavigationPipActivity), jadi
    * keduanya tak mungkin menyebut angka berbeda.
    */
   static final class PriorDebt {
      static final PriorDebt NONE = new PriorDebt(0.0, null);

      /** Hutang lama (> 0), atau 0 bila tak ada. */
      final double amount;
      /** Pintu tagih leg checkout; null = order tunggal (selalu ditagih di sini). */
      final CheckoutStrukText.DebtDoor door;

      PriorDebt(double amount, CheckoutStrukText.DebtDoor door) {
         this.amount = amount;
         this.door = door;
      }

      /** Hutang lama ditagih di pintu INI. */
      boolean here() {
         return this.door == null || this.door.here;
      }
   }

   /** Lihat {@link PriorDebt}. Aman dipanggil dari thread latar (hanya baca DB). */
   static PriorDebt priorDebtFor(DatabaseHelper db, Transaction t) {
      if (t == null || t.getCustomerId() <= 0L) return PriorDebt.NONE;
      TransactionDao trxDao = new TransactionDao(db);
      String trxUuid = trxDao.getSyncUuidById(t.getId());
      CustomerDebtDao debtDao = new CustomerDebtDao(db);
      String co = legCheckoutUuid(t);
      double prior = co != null
            ? debtDao.balanceExcludingTransaction(t.getCustomerId(), trxUuid, co)
            : debtDao.balanceExcludingTransaction(t.getCustomerId(), trxUuid);
      if (prior <= 0.0) return PriorDebt.NONE;
      return new PriorDebt(prior, co != null ? trxDao.oldDebtDoor(t) : null);
   }

   /**
    * Saldo refund yang memotong order ini (Σ baris 'usage' buku besar customer_refunds) — SATU
    * sumber untuk {@link #refundSummaryText} dan jendela melayang navigasi. Aman dari thread latar.
    */
   static double refundUsedFor(DatabaseHelper db, Transaction t) {
      if (t == null || t.getId() <= 0L) return 0.0;
      String trxUuid = (new TransactionDao(db)).getSyncUuidById(t.getId());
      return (new CustomerRefundDao(db)).usedForTransaction(trxUuid);
   }

   /**
    * Baris "Dari saldo refund" + "Total setelah refund" untuk popup "Tandai Selesai" & "Detail
    * Order": order ini dibayar SEBAGIAN dari saldo refund pelanggan, jadi Total harga jual sendirian
    * membesar-besarkan apa yang sebenarnya masih harus diterima kurir.
    *
    * <p>Dibaca dari buku besar {@code customer_refunds} lewat uuid transaksi — sama dengan yang
    * dipakai struk (ReceiptActivity) — BUKAN dari penanda pada catatan, karena penanda bisa hilang
    * bila catatan diedit sedangkan baris buku besar adalah uangnya sendiri.</p>
    *
    * <p>Tak ada pemakaian refund → '' (dialognya tetap ringkas untuk mayoritas order tunai biasa).</p>
    */
   private String refundSummaryText(Transaction t) {
      if (t == null || t.getId() <= 0L) {
         return "";
      } else {
         double used = refundUsedFor(DatabaseHelper.getInstance(this), t);
         return used <= (double)0.0F
               ? ""
               : "\nDari saldo refund: " + this.rp(used)
                     + "\nTotal setelah refund: " + this.rp(Math.max((double)0.0F, t.getTotalHarga() - used));
      }
   }

   private void showAdjustDialog(Transaction t, boolean revokeCredit) {
      List<Product> products = (new ProductDao(DatabaseHelper.getInstance(this))).getAll();
      List<TransactionItem> seed = t.getItems() != null ? new ArrayList(t.getItems()) : new ArrayList();
      if (seed.isEmpty() && t.getJumlahGalon() > 0) {
         seed.add(new TransactionItem(0L, products.isEmpty() ? "Galon" : ((Product)products.get(0)).getName(), t.getJumlahGalon(), t.getHargaPerGalon()));
      }

      int pad = (int)(16.0F * this.getResources().getDisplayMetrics().density);
      int gap = (int)(8.0F * this.getResources().getDisplayMetrics().density);
      LinearLayout box = new LinearLayout(this);
      box.setOrientation(1);
      box.setPadding(pad, gap, pad, 0);
      List<Spinner> nameIn = new ArrayList();
      List<EditText> qtyIn = new ArrayList();
      List<EditText> priceIn = new ArrayList();
      List<String> productNames = new ArrayList();

      for(Product p : products) {
         productNames.add(p.getName());
      }

      for(TransactionItem it : seed) {
         LinearLayout row = new LinearLayout(this);
         row.setOrientation(0);
         List<String> opts = new ArrayList(productNames);
         if (it.productName != null && !opts.contains(it.productName)) {
            opts.add(0, it.productName);
         }

         Spinner sp = new Spinner(this);
         sp.setAdapter(new ArrayAdapter(this, 17367049, opts));
         int idx = opts.indexOf(it.productName);
         if (idx >= 0) {
            sp.setSelection(idx);
         }

         EditText q = new EditText(this);
         q.setInputType(2);
         q.setText(String.valueOf(it.jumlah));
         q.setWidth((int)(56.0F * this.getResources().getDisplayMetrics().density));
         EditText pr = new EditText(this);
         pr.setInputType(2);
         pr.setText(String.valueOf(Math.round(it.hargaPerGalon)));
         pr.setWidth((int)(88.0F * this.getResources().getDisplayMetrics().density));
         LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1.0F);
         row.addView(sp, lp);
         row.addView(q);
         row.addView(pr);
         box.addView(row);
         nameIn.add(sp);
         qtyIn.add(q);
         priceIn.add(pr);
      }

      TextView lblRet = new TextView(this);
      lblRet.setText("Galon kembali");
      lblRet.setPadding(0, gap, 0, 0);
      EditText retIn = new EditText(this);
      retIn.setInputType(2);
      retIn.setText(String.valueOf(this.dao.getReturnedGalonForSale(t.getCustomerId(), t.getTanggal())));
      TextView lblPay = new TextView(this);
      lblPay.setText("Pembayaran");
      lblPay.setPadding(0, gap, 0, 0);
      String[] payValues = new String[]{"TUNAI", "QRIS", "TRANSFER", "HUTANG"};
      Spinner paySp = new Spinner(this);
      paySp.setAdapter(new ArrayAdapter(this, 17367049, new String[]{"Tunai", "QRIS", "Transfer", "Hutang (bayar nanti)"}));

      for(int i = 0; i < payValues.length; ++i) {
         if (payValues[i].equals(t.getPaymentMethod())) {
            paySp.setSelection(i);
         }
      }

      CustomerDebtDao debtDaoAdj = new CustomerDebtDao(DatabaseHelper.getInstance(this));
      double existingDebt = t.getCustomerId() > 0L ? debtDaoAdj.balanceFor(t.getCustomerId()) : (double)0.0F;
      String adjTrxUuid = (new TransactionDao(DatabaseHelper.getInstance(this))).getSyncUuidById(t.getId());
      // 🧺 Leg checkout: hutang segar leg saudara (checkout_uuid sama) BUKAN hutang lama di pintu ini —
      // dikecualikan dari tagihan, lewat customer_debts.checkout_uuid (tak butuh transaksi saudaranya).
      String adjCheckout = legCheckoutUuid(t);
      // oldDebt = hutang LAMA sebenarnya (batas pelunasan yang boleh diterima di pintu ini);
      // priorDebt = yang DITAGIH di pintu ini. Leg checkout yang bukan pintu tagih hutang lama
      // (DebtBalance::priorForDoor) → 0: hanya penjualan lokasi ini. Order tunggal: keduanya sama.
      double oldDebt = t.getCustomerId() <= 0L ? (double)0.0F
            : (adjCheckout != null
               ? debtDaoAdj.balanceExcludingTransaction(t.getCustomerId(), adjTrxUuid, adjCheckout)
               : debtDaoAdj.balanceExcludingTransaction(t.getCustomerId(), adjTrxUuid));
      CheckoutStrukText.DebtDoor debtDoor = adjCheckout != null ? this.dao.oldDebtDoor(t) : null;
      boolean debtElsewhere = debtDoor != null && !debtDoor.here;
      double priorDebt = debtElsewhere ? (double)0.0F : oldDebt;
      String legHint = checkoutCollectHint(t);
      if (!legHint.isEmpty()) {
         TextView tvLeg = new TextView(this);
         tvLeg.setText(legHint);
         tvLeg.setTextColor(-10395295);
         tvLeg.setPadding(0, 0, 0, gap);
         box.addView(tvLeg);
      }
      if (existingDebt > (double)0.0F) {
         TextView debtNow = new TextView(this);
         // Angka hutang pelanggan sesungguhnya (termasuk tagihan lokasi lain pesanan ini) — tapi yang
         // DITAGIH di pintu ini hanya penjualan lokasi ini + hutang lama (lihat "Jumlah Dibayar").
         double siblingFresh = adjCheckout != null && t.getCustomerId() > 0L
               ? Math.max(0.0, debtDaoAdj.balanceExcludingTransaction(t.getCustomerId(), adjTrxUuid, null) - oldDebt)
               : 0.0;
         debtNow.setText("\ud83e\uddfe Hutang pelanggan saat ini: " + this.rp(existingDebt)
               + (siblingFresh > 0.0 ? "\n(termasuk " + this.rp(siblingFresh)
                     + " tagihan lokasi lain pesanan ini — ditagih di lokasinya sendiri)" : ""));
         debtNow.setTextColor(-4645860);
         debtNow.setPadding(0, 0, 0, gap);
         box.addView(debtNow);
      }
      if (debtElsewhere && oldDebt > (double)0.0F) {
         // 🧺 Bukan pintu tagih hutang lama: JANGAN ditagih di sini (kurir pintu tagih yang menagihnya),
         // tapi uang lebih yang tetap diserahkan pelanggan dicatat sebagai pelunasan s/d hutang lama.
         // Leg pintu tagih tak ada di HP ini (debtDoor.known false: dirutekan ke HP lain, di-void, atau
         // sudah Selesai di sana) → instruksi bersyarat, bukan perintah pasti ke lokasi yang mungkin tutup.
         TextView tvDoor = new TextView(this);
         tvDoor.setText(CheckoutStrukText.oldDebtElsewhereInstruction(debtDoor, this.rp(oldDebt)));
         tvDoor.setTextColor(-10395295);
         tvDoor.setPadding(0, 0, 0, gap);
         box.addView(tvDoor);
      }

      TextView lblPaid = new TextView(this);
      lblPaid.setText("Jumlah Dibayar (Rp)");
      lblPaid.setPadding(0, gap, 0, 0);
      EditText paidIn = new EditText(this);
      paidIn.setInputType(8194);
      TextView owedHint = new TextView(this);
      owedHint.setTextColor(-4645860);
      owedHint.setVisibility(8);
      owedHint.setPadding(0, (int)(4.0F * this.getResources().getDisplayMetrics().density), 0, 0);
      final Runnable[] updateHintHolder = new Runnable[1];
      Runnable updateHint = () -> {
         double itemTotal = (double)0.0F;

         for(int i = 0; i < qtyIn.size(); ++i) {
            double qv;
            try {
               qv = Double.parseDouble(((EditText)qtyIn.get(i)).getText().toString().trim());
            } catch (Exception var24) {
               qv = (double)0.0F;
            }

            double pv;
            try {
               pv = Double.parseDouble(((EditText)priceIn.get(i)).getText().toString().trim());
            } catch (Exception var23) {
               pv = (double)0.0F;
            }

            itemTotal += qv * pv;
         }

         int galonNow = 0;

         for(EditText q : qtyIn) {
            try {
               galonNow += (int)Double.parseDouble(q.getText().toString().trim());
            } catch (Exception var22) {
            }
         }

         double ongkirNow = "per_galon".equals(t.getOngkirType()) ? t.getOngkir() * (double)galonNow : t.getOngkir();
         double liveTotal = itemTotal + ongkirNow;
         double due = liveTotal + priorDebt;

         // KOSONG = tak ada uang diterima → tampilkan peringatan hutangnya, JANGAN diam-diam
         // dianggap lunas. Teks yang tak terbaca TAPI tidak kosong (mis. baru mengetik ".")
         // tetap jatuh ke `due` supaya peringatannya tak berkedip di tengah pengetikan.
         String paidRaw = paidIn.getText().toString().trim();
         double paid;
         if (paidRaw.isEmpty()) {
            paid = (double)0.0F;
         } else {
            try {
               paid = Double.parseDouble(paidRaw);
            } catch (Exception var21) {
               paid = due;
            }
         }

         double owed = Math.max((double)0.0F, due - paid);
         if (owed > (double)0.0F) {
            owedHint.setText(priorDebt > (double)0.0F ? "⚠ Tagihan " + this.rp(due) + " (penjualan " + this.rp(liveTotal) + " + hutang lama " + this.rp(priorDebt) + ") — sisa " + this.rp(owed) + " akan tercatat sebagai hutang pelanggan." : "⚠ Sisa " + this.rp(owed) + " akan tercatat sebagai hutang pelanggan.");
            owedHint.setTextColor(-4645860);
            owedHint.setVisibility(0);
         } else if (priorDebt > (double)0.0F) {
            owedHint.setText("✅ Hutang lama " + this.rp(priorDebt) + " ikut LUNAS dengan pembayaran ini.");
            owedHint.setTextColor(-15368131);
            owedHint.setVisibility(0);
         } else if (debtElsewhere && oldDebt > (double)0.0F && paid > liveTotal) {
            // 🧺 Pelunasan SUKARELA di pintu yang bukan pintu tagih — dibatasi hutang lama sebenarnya,
            // sama dengan TransactionDao.applyDeliveryAdjustment ($oldDebtCap di web).
            double toOld = Math.min(paid - liveTotal, oldDebt);
            owedHint.setText("✅ " + this.rp(toOld) + " dicatat sebagai pelunasan hutang lama"
                  + (toOld >= oldDebt ? " (LUNAS)." : " (sisa " + this.rp(oldDebt - toOld) + " "
                        + debtDoor.where() + ")."));
            owedHint.setTextColor(-15368131);
            owedHint.setVisibility(0);
         } else {
            owedHint.setVisibility(8);
         }

      };
      updateHintHolder[0] = updateHint;
      paidIn.setText(String.valueOf(Math.round(t.getTotalHarga() + priorDebt)));
      TextWatcher watcher = new TextWatcher() {
         public void beforeTextChanged(CharSequence s, int a, int b, int c) {
         }

         public void onTextChanged(CharSequence s, int a, int b, int c) {
         }

         public void afterTextChanged(Editable s) {
            updateHintHolder[0].run();
         }
      };
      paidIn.addTextChangedListener(watcher);

      for(EditText q : qtyIn) {
         q.addTextChangedListener(watcher);
      }

      for(EditText p : priceIn) {
         p.addTextChangedListener(watcher);
      }

      CheckBox bon = new CheckBox(this);
      bon.setText("Cash Bon — uang belum diterima sama sekali");
      bon.setPadding(0, gap, 0, 0);
      Spinner bonReason = new Spinner(this);
      // Alasan memakai konstanta bersama: string-nya disimpan apa adanya di catatan lalu dibaca
      // ulang (web & HP), jadi tak boleh menyimpang walau satu huruf.
      String[] bonReasons = new String[]{
            TransactionDao.CASH_BON_REASON_AWAY,
            TransactionDao.CASH_BON_REASON_UNPAID,
            TransactionDao.CASH_BON_REASON_OTHER};
      bonReason.setAdapter(new ArrayAdapter(this, 17367049, bonReasons));
      // Default "Konsumen tidak ada di tempat": kasus Cash Bon yang paling sering terjadi di
      // lapangan, jadi kurir tak perlu mengubah apa pun untuk kasus yang biasa.
      bonReason.setSelection(0);
      bonReason.setVisibility(View.GONE);
      // "Lainnya" WAJIB dijelaskan — tanpa penjelasan, alasannya tak berarti apa-apa saat ditinjau
      // di dashboard nanti.
      EditText bonOther = new EditText(this);
      bonOther.setHint("Jelaskan alasannya");
      bonOther.setInputType(1 | 16384);
      bonOther.setMaxLines(2);
      bonOther.setVisibility(View.GONE);
      bonReason.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
         public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
            bonOther.setVisibility(TransactionDao.CASH_BON_REASON_OTHER.equals(bonReasons[pos])
                  ? View.VISIBLE : View.GONE);
         }

         public void onNothingSelected(AdapterView<?> parent) {
         }
      });
      bon.setOnCheckedChangeListener((v, checked) -> {
         bonReason.setVisibility(checked ? View.VISIBLE : View.GONE);
         bonOther.setVisibility(checked
               && TransactionDao.CASH_BON_REASON_OTHER.equals(String.valueOf(bonReason.getSelectedItem()))
               ? View.VISIBLE : View.GONE);
         if (checked) {
            // Simpan nominal sebelumnya untuk dipulihkan bila Cash Bon dilepas lagi — KECUALI "0"
            // dan KOSONG: memulihkan salah satunya hanya akan memicu Cash Bon menyala lagi.
            String before = paidIn.getText().toString().trim();
            if (!"0".equals(before) && !before.isEmpty()) {
               paidIn.setTag(paidIn.getText().toString());
            }

            paidIn.setText("0");
            paidIn.setEnabled(false);
         } else {
            paidIn.setEnabled(true);
            Object prev = paidIn.getTag();
            paidIn.setText(prev != null ? String.valueOf(prev) : String.valueOf(Math.round(t.getTotalHarga() + priorDebt)));
         }

      });
      // "Jumlah Dibayar" = 0 berarti tak ada uang diterima sama sekali — itu PERSIS definisi Cash
      // Bon, jadi centangnya ikut menyala sendiri (kebalikan dari tautan di atas). Tanpa ini kurir
      // bisa mengetik 0 lalu menyelesaikan order tanpa alasan & tanpa foto bukti.
      paidIn.addTextChangedListener(new TextWatcher() {
         public void beforeTextChanged(CharSequence s, int a, int b, int c) {
         }

         public void onTextChanged(CharSequence s, int a, int b, int c) {
         }

         public void afterTextChanged(Editable e) {
            if (paidIn.isEnabled()) {
               double val;
               try {
                  val = Double.parseDouble(e.toString().trim());
               } catch (Exception var4) {
                  return;   // kosong/tak terbaca ditangani saat fokus lepas, lihat di bawah
               }

               if (val <= (double)0.0F && !bon.isChecked()) {
                  bon.setChecked(true);
               }
            }
         }
      });
      // KOSONG juga berarti tak ada uang diterima → Cash Bon. Sengaja dinilai saat FOKUS LEPAS,
      // bukan di setiap ketikan: menghapus isian untuk mengetik ulang nominal (pilih-semua lalu
      // hapus) melewati keadaan kosong sesaat, dan mencentang Cash Bon di detik itu akan MENGUNCI
      // kolomnya (Cash Bon menyetel 0 + disable) sehingga kurir tak bisa melanjutkan mengetik.
      paidIn.setOnFocusChangeListener((v, hasFocus) -> {
         if (!hasFocus && paidIn.isEnabled() && paidIn.getText().toString().trim().isEmpty()
               && !bon.isChecked()) {
            bon.setChecked(true);
         }

      });
      box.addView(lblRet);
      box.addView(retIn);
      box.addView(lblPay);
      box.addView(paySp);
      box.addView(lblPaid);
      box.addView(paidIn);
      box.addView(owedHint);
      box.addView(bon);
      box.addView(bonReason);
      box.addView(bonOther);
      updateHint.run();
      ScrollView scroll = new ScrollView(this);
      scroll.addView(box);
      // Tombol Simpan dipasang lewat setOnShowListener: listener bawaan setPositiveButton SELALU
      // menutup dialog, sehingga `return` pada validasi di bawah ("produk kosong", "Lainnya belum
      // dijelaskan") tetap membuang seluruh isian kurir alih-alih mempertahankannya.
      AlertDialog adjustDlg = (new AlertDialog.Builder(this)).setTitle("Ubah Detail Order").setView(scroll).setNegativeButton("Batal", (DialogInterface.OnClickListener)null).setPositiveButton("Simpan & Selesai", (DialogInterface.OnClickListener)null).create();
      adjustDlg.setOnShowListener((shown) -> adjustDlg.getButton(-1).setOnClickListener((btn) -> {
         List<TransactionItem> out = new ArrayList();

         for(int i = 0; i < nameIn.size(); ++i) {
            int qty;
            try {
               qty = Integer.parseInt(((EditText)qtyIn.get(i)).getText().toString().trim());
            } catch (NumberFormatException var29) {
               qty = 0;
            }

            double price;
            try {
               price = Double.parseDouble(((EditText)priceIn.get(i)).getText().toString().trim());
            } catch (NumberFormatException var28) {
               price = (double)0.0F;
            }

            if (qty > 0) {
               String nm = String.valueOf(((Spinner)nameIn.get(i)).getSelectedItem());
               long pid = 0L;

               for(Product p : products) {
                  if (p.getName().equals(nm)) {
                     pid = p.getId();
                     break;
                  }
               }

               out.add(new TransactionItem(pid, nm, qty, price));
            }
         }

         if (out.isEmpty()) {
            Toast.makeText(this, "Isi minimal satu produk dengan jumlah > 0", 1).show();
         } else {
            int ret;
            try {
               ret = Integer.parseInt(retIn.getText().toString().trim());
            } catch (NumberFormatException var27) {
               ret = 0;
            }

            // Jaring terakhir: menekan Simpan tanpa melepas fokus dari kolom kosong tak pernah
            // memicu listener fokus di atas, jadi keadaan "kosong = tak ada uang diterima"
            // ditegakkan sekali lagi di sini. setChecked berjalan sinkron, jadi isBon & isi kolom
            // di bawah sudah mencerminkan Cash Bon.
            if (paidIn.isEnabled() && paidIn.getText().toString().trim().isEmpty() && !bon.isChecked()) {
               bon.setChecked(true);
            }

            boolean isBon = bon.isChecked();

            Double paidAmount;
            try {
               paidAmount = isBon ? (double)0.0F : Double.parseDouble(paidIn.getText().toString().trim());
            } catch (NumberFormatException var26) {
               paidAmount = null;
            }

            String by = (new SettingsDao(DatabaseHelper.getInstance(this))).getCurrentUserName();
            // Alasan Cash Bon: "Lainnya" diganti penjelasan bebasnya (wajib diisi) supaya catatan yang
         // tersimpan menerangkan sesuatu, bukan cuma kata "Lainnya".
         String bonReasonText = null;
         if (isBon) {
            bonReasonText = bonReasons[bonReason.getSelectedItemPosition()];
            if (TransactionDao.CASH_BON_REASON_OTHER.equals(bonReasonText)) {
               String penjelasan = bonOther.getText().toString().trim();
               if (penjelasan.isEmpty()) {
                  bonOther.setError("Jelaskan alasannya");
                  bonOther.requestFocus();
                  return;
               }

               bonReasonText = TransactionDao.CASH_BON_REASON_OTHER + " — " + penjelasan;
            }
         }

            this.dao.applyDeliveryAdjustment(t.getId(), out, ret, payValues[paySp.getSelectedItemPosition()], isBon, paidAmount, bonReasonText, by);
            adjustDlg.dismiss();
            Transaction fresh = this.dao.getById(t.getId());
            this.doComplete(fresh != null ? fresh : t, revokeCredit);
         }
      }));
      adjustDlg.show();
   }

   private void doComplete(Transaction t, boolean revokeCredit) {
      // CASH BON apa pun alasannya: galon diserahkan tapi uangnya TIDAK masuk. Fotonya WAJIB apa pun
      // setelan cabang — inilah SATU-SATUNYA bukti bahwa galonnya benar-benar sampai, dan tanpa itu
      // klaim "sudah diantar tapi belum dibayar" tak bisa dipertanggungjawabkan.
      // PESANAN RESELLER (flag server delivery_proof_required ATAU penanda [ORDER RESELLER]) juga
      // WAJIB apa pun setelan cabang: reseller memantau pesanan afiliasinya dari halaman link-nya,
      // dan foto inilah satu-satunya bukti yang ia lihat bahwa galonnya sampai.
      DeliveryProofPolicy.Reason reason = this.proofReason(t);
      if (!DeliveryProofPolicy.isRequired(reason)) {
         this.askOptionalProof(t, revokeCredit);
      } else if (reason == DeliveryProofPolicy.Reason.RESELLER) {
         // Pengantar dulu, baru kamera — alasannya sama dengan Cash Bon di bawah. Tak bisa ditutup
         // (back/ketuk luar): satu-satunya jalan keluar selain memotret adalah "Batal", yang
         // membiarkan order tetap di antrean.
         (new AlertDialog.Builder(this))
               .setCancelable(false)
               .setTitle("📷 WAJIB: Foto Bukti — Pesanan Reseller")
               .setMessage("Order ini milik pelanggan RESELLER (dipesan reseller atau pelanggan "
                     + "terafiliasi reseller), jadi fotonya WAJIB.\n\n"
                     + "Foto galon yang sudah diserahkan (atau rumah/lokasi tujuan) sebagai bukti "
                     + "barangnya benar-benar sampai. Foto ini tampil di web & halaman reseller, "
                     + "lalu dikirim lewat WA ke reseller dan pelanggan.")
               .setPositiveButton("AMBIL FOTO", (d, w) -> this.requestProofPhoto(t, revokeCredit))
               .setNegativeButton("Batal", (d, w) ->
                     Toast.makeText(this, "Order belum ditandai Selesai — foto bukti wajib untuk pesanan reseller.", 1).show())
               .show();
      } else if (reason == DeliveryProofPolicy.Reason.CASH_BON) {
         // CASH BON: jelaskan DULU kenapa fotonya wajib, baru buka kamera. Tanpa pengantar ini
         // kamera muncul tiba-tiba tepat setelah menekan Simpan — kurir tak tahu apa yang harus
         // difoto, lalu memotret asal atau menekan batal (yang membatalkan penyelesaian order).
         (new AlertDialog.Builder(this))
               .setCancelable(false)
               .setTitle("📷 WAJIB: Foto Bukti Pengiriman")
               .setMessage("Uang tidak diterima pada order ini, jadi fotonya WAJIB.\n\n"
                     + "Foto galon yang sudah diserahkan (atau rumah pelanggan) sebagai bukti "
                     + "barangnya benar-benar sampai. Foto ini tersimpan di server dan menjadi "
                     + "bukti bila tagihannya dipertanyakan nanti.")
               .setPositiveButton("AMBIL FOTO", (d, w) -> this.requestProofPhoto(t, revokeCredit))
               .setNegativeButton("Batal", (d, w) ->
                     Toast.makeText(this, "Order belum ditandai Selesai — foto bukti wajib untuk Cash Bon.", 1).show())
               .show();
      } else {
         this.requestProofPhoto(t, revokeCredit);
      }
   }

   /**
    * Kenapa (dan apakah) foto bukti wajib untuk order ini — {@link DeliveryProofPolicy}. Dinilai dari
    * baris SEGAR di DB, bukan hanya objek {@code t} dalam memori: {@code t} kerap hasil loadData()
    * yang dirender SEBELUM pull terakhir membawa flag delivery_proof_required / penanda
    * [ORDER RESELLER] (atau sebelum applyDeliveryAdjustment menulis [CASH BON]) — jebakan yang sama
    * dengan yang dijelaskan di {@link #finishComplete}. Keduanya digabung OR: wajib di salah satu =
    * wajib. {@code t} sendiri tetap dipakai untuk langkah berikutnya (ia membawa delivery_queued_at
    * untuk durasi; baris getById tidak).
    */
   /** Pelanggan terafiliasi reseller (linked_reseller_uuid) → foto bukti WAJIB: reseller melihatnya di halaman
    *  link-nya & server mengirim WA selesai + foto ke reseller (ResellerSelesaiWa) dan pelanggan. */
   private boolean isAffiliatedCustomer(Transaction t) {
      if (t == null || t.getCustomerId() <= 0) return false;
      try {
         Customer c = this.customerDao.getById(t.getCustomerId());
         String r = c != null ? c.getLinkedResellerUuid() : null;
         return r != null && !r.trim().isEmpty() && !"null".equals(r.trim());
      } catch (Exception e) {
         return false;
      }
   }

   private DeliveryProofPolicy.Reason proofReason(Transaction t) {
      Transaction fresh = t != null ? this.dao.getById(t.getId()) : null;
      boolean cashBon = this.isCashBon(t) || this.isCashBon(fresh);
      boolean orderRequired = (t != null && t.requiresDeliveryProof())
            || (fresh != null && fresh.requiresDeliveryProof())
            || this.isAffiliatedCustomer(t);
      SettingsDao sd = new SettingsDao(DatabaseHelper.getInstance(this));
      // Perangkat INI ditandai "📷 Wajib Foto Bukti Selesai" di dashboard (/api/me) — di atas
      // setelan cabang yang berlaku untuk semua HP.
      return DeliveryProofPolicy.reason(sd.isDeliveryProofRequired(), this.syncCfg().isDeviceProofRequired(),
            cashBon, orderRequired);
   }

   /**
    * Order BIASA (bukan Cash Bon, bukan pesanan reseller, cabang tak mewajibkan): TAWARKAN foto
    * bukti, jangan memaksa.
    *
    * <p>Tombol default "TIDAK" berhitung mundur 10 detik lalu menekan dirinya sendiri. Alasannya
    * praktis: pertanyaannya muncul tepat di tengah alur menyelesaikan order, dan kurir yang sedang
    * mengemudi/mengangkat galon tak boleh terhalang dialog yang menunggu selamanya. Diam = tidak
    * memotret, yaitu pilihan yang AMAN — order tetap selesai, tak ada data yang hilang.</p>
    *
    * <p>Hitungannya ditampilkan pada label tombol ("TIDAK (7)") supaya kurir tahu ada tenggat dan
    * bisa menekan "Ambil Foto" sebelum waktunya habis. Timer dibatalkan pada dismiss apa pun —
    * ditekan tombolnya, di-back, atau ditutup dari luar — jadi tak ada callback yang menembak
    * setelah dialognya hilang.</p>
    */
   private void askOptionalProof(Transaction t, boolean revokeCredit) {
      AlertDialog dlg = (new AlertDialog.Builder(this))
            .setTitle("Foto Bukti Pengiriman?")
            .setMessage("Ambil foto galon yang sudah diantar sebagai bukti?\n\n"
                  + "Boleh dilewati — bila didiamkan, otomatis TIDAK setelah 10 detik.")
            .setPositiveButton("📷 Ambil Foto", (d, w) -> this.requestProofPhoto(t, revokeCredit))
            .setNegativeButton("TIDAK", (d, w) -> this.finishComplete(t, revokeCredit))
            .setOnCancelListener((d) -> this.finishComplete(t, revokeCredit))
            .create();
      dlg.show();

      android.widget.Button no = dlg.getButton(-2);
      android.os.CountDownTimer timer = new android.os.CountDownTimer(10000L, 250L) {
         public void onTick(long msLeft) {
            if (no != null) {
               no.setText("TIDAK (" + (msLeft / 1000L + 1L) + ")");
            }
         }

         public void onFinish() {
            // dismiss() TIDAK memicu OnCancelListener, jadi penyelesaiannya dipanggil di sini —
            // tepat sekali, tak ada jalur yang menghitungnya dua kali.
            if (dlg.isShowing()) {
               dlg.dismiss();
               DeliveryQueueActivity.this.finishComplete(t, revokeCredit);
            }
         }
      };
      dlg.setOnDismissListener((d) -> timer.cancel());
      timer.start();
   }

   private void requestProofPhoto(Transaction t, boolean revokeCredit) {
      if (ContextCompat.checkSelfPermission(this, "android.permission.CAMERA") != 0) {
         this.pendingProofTrxId = t.getId();
         this.pendingProofRevoke = revokeCredit;
         ActivityCompat.requestPermissions(this, new String[]{"android.permission.CAMERA"}, 7403);
      } else {
         this.launchProofCamera(t.getId(), revokeCredit);
      }
   }

   private void launchProofCamera(long trxId, boolean revokeCredit) {
      Intent intent = CameraIntents.preferBackCamera(new Intent("android.media.action.IMAGE_CAPTURE"));
      if (intent.resolveActivity(this.getPackageManager()) == null) {
         Transaction t = this.findQueueTrx(trxId);
         // Foto WAJIB (Cash Bon / pesanan reseller / setelan cabang) → TAK ada jalan pintas. Dulu
         // order tetap diselesaikan tanpa foto di sini, jadi "wajib" bisa dilewati cukup dengan HP
         // tanpa aplikasi kamera. Hanya foto opsional yang masih boleh dilewati seperti sebelumnya.
         if (DeliveryProofPolicy.isRequired(this.proofReason(t != null ? t : this.dao.getById(trxId)))) {
            this.pendingProofTrxId = -1L;
            Toast.makeText(this, "Tidak ada aplikasi kamera — foto bukti WAJIB untuk order ini, jadi "
                  + "order belum ditandai Selesai. Aktifkan aplikasi kamera lalu coba lagi.", 1).show();
            return;
         }

         Toast.makeText(this, "Tidak ada aplikasi kamera — order diselesaikan tanpa foto bukti.", 1).show();
         if (t != null) {
            this.finishComplete(t, revokeCredit);
         }

      } else {
         File photoFile;
         try {
            String ts = (new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)).format(new Date());
            File dir = this.getExternalFilesDir(Environment.DIRECTORY_PICTURES);
            photoFile = File.createTempFile("BUKTI_" + ts, ".jpg", dir);
         } catch (IOException e) {
            Toast.makeText(this, "Gagal membuat file foto: " + e.getMessage(), 0).show();
            return;
         }

         this.pendingProofTrxId = trxId;
         this.pendingProofRevoke = revokeCredit;
         this.pendingProofPath = photoFile.getAbsolutePath();
         Uri photoURI = FileProvider.getUriForFile(this, this.getApplicationContext().getPackageName() + ".fileprovider", photoFile);
         intent.putExtra("output", photoURI);
         this.startActivityForResult(intent, 7402);
      }
   }

   protected void onNewIntent(Intent intent) {
      super.onNewIntent(intent);
      this.setIntent(intent);
      long id = intent != null ? intent.getLongExtra(EXTRA_FOCUS_TRX_ID, -1L) : -1L;
      if (id > 0L) {
         this.pendingFocusTrxId = id;   // dibuka di onResume berikutnya, setelah loadData
      }
      long done = intent != null ? intent.getLongExtra(EXTRA_COMPLETE_TRX_ID, -1L) : -1L;
      if (done > 0L) this.pendingCompleteTrxId = done;
   }

   /**
    * Buka order dari deep link pesanan: dicari di Antrean Saya, Pesanan Terbuka, lalu Antrean
    * Tertunda — lalu tampilkan popup detailnya (showOrderDetail, sama dgn klik kartu). Order yang
    * tak ada di antrean mana pun (sudah selesai/dibatalkan, atau milik perangkat lain yang belum
    * tertarik ke HP ini) cukup diberi tahu lewat Toast.
    */
   private void openPendingFocus() {
      long id = this.pendingFocusTrxId;
      if (id <= 0L) return;
      this.pendingFocusTrxId = -1L;

      Transaction t = this.findQueueTrx(id);
      if (t == null && this.openDispatchAdapter != null) {
         t = findById(this.openDispatchAdapter.rawData, id);
      }
      if (t == null) {
         t = findById(this.dao.getTertundaQueue(), id);
      }

      if (t != null) {
         this.scrollToOrder(t);
         this.showOrderDetail(t);
      } else {
         Toast.makeText(this, "Pesanan ini tidak ada di antrean perangkat ini — mungkin sudah selesai, "
               + "dibatalkan, atau dipegang perangkat lain.", Toast.LENGTH_LONG).show();
      }
   }

   private static Transaction findById(List<Transaction> list, long id) {
      if (list != null) {
         for (Transaction t : list) {
            if (t.getId() == id) return t;
         }
      }
      return null;
   }

   private Transaction findQueueTrx(long trxId) {
      for(Transaction t : this.adapter.data) {
         if (t.getId() == trxId) {
            return t;
         }
      }

      return null;
   }

   protected void onActivityResult(int requestCode, int resultCode, Intent data) {
      super.onActivityResult(requestCode, resultCode, data);
      if (requestCode == REQ_POSTPONE_PHOTO) {
         this.onPostponePhotoResult(resultCode);
         return;
      }
      if (requestCode == 7402) {
         long trxId = this.pendingProofTrxId;
         boolean revoke = this.pendingProofRevoke;
         String path = this.pendingProofPath;
         this.pendingProofTrxId = -1L;
         this.pendingProofPath = null;
         if (resultCode == -1 && path != null && (new File(path)).exists() && (new File(path)).length() != 0L) {
            Transaction t = this.findQueueTrx(trxId);
            if (t == null) {
               Toast.makeText(this, "Order sudah tidak di antrian.", 0).show();
            } else {
               this.dao.setDeliveryProofPath(trxId, path);
               this.finishComplete(t, revoke, path);
            }
         } else {
            if (path != null) {
               try {
                  (new File(path)).delete();
               } catch (Exception var9) {
               }
            }

            Toast.makeText(this, "Foto bukti wajib — order belum ditandai Selesai.", 1).show();
         }
      }
   }

   /**
    * Order ini diselesaikan sebagai CASH BON (uang tidak diterima sama sekali)? Dibaca dari CATATAN
    * — penanda ditulis {@code TransactionDao.applyDeliveryAdjustment} — bukan dari state dialog,
    * jadi berlaku seragam untuk SEMUA jalur yang bermuara ke {@link #doComplete}, termasuk order
    * yang penandanya sudah tercatat pada percobaan sebelumnya.
    */
   private boolean isCashBon(Transaction t) {
      String note = t != null && t.getCatatan() != null ? t.getCatatan() : "";
      return note.contains(TransactionDao.CASH_BON_MARKER);
   }

   private void finishComplete(Transaction t, boolean revokeCredit) {
      this.finishComplete(t, revokeCredit, (String)null);
   }

   /** @param proofPath foto bukti yang barusan diambil (null bila jalur ini tak memotret) —
    *                   dilampirkan ke WA konfirmasi pengiriman saat Cash Bon. */
   private void finishComplete(Transaction t, boolean revokeCredit, String proofPath) {
      long ms = elapsedMillis(t.getDeliveryQueuedAt());
      // Baca ULANG dari DB sebelum menilai Cash Bon. `t` sering datang dari findQueueTrx(), yaitu
      // objek DALAM MEMORI hasil loadData() — catatannya berumur SEBELUM applyDeliveryAdjustment
      // menuliskan penanda [CASH BON] beberapa detik lalu. Memakainya apa adanya membuat
      // isCashBon() selalu false pada jalur kamera, sehingga foto sudah terambil & terunggah tapi
      // WA konfirmasinya tak pernah muncul — persis bug yang dilaporkan.
      Transaction fresh = this.dao.getById(t.getId());
      if (fresh != null) {
         t = fresh;
      }

      boolean notifyUnpaid = this.isCashBon(t);
      this.dao.markDelivered(t.getId(), revokeCredit);
      if (this.runningIds.remove(t.getId())) {
         this.persistRunning();
      }

      SyncScheduler.syncNow(this.getApplicationContext());
      this.loadData();
      Toast.makeText(this, "Order selesai • " + formatDuration(ms), 0).show();
      if (notifyUnpaid) {
         this.sendCashBonNoticeWa(t, proofPath);
      }
      boolean fromPanel = t.getId() == this.wizardCompleteId;
      if (fromPanel) this.wizardCompleteId = -1L;
      forgetWizardSkip(this, t.getId());
      if (!this.guidedMode && this.wizardActive && (fromPanel || NavigationPipActivity.isLive())) {
         // Navigasi per titik: lanjut ke titik berikutnya begitu layar ini kembali bebas (dialog/WA
         // penyelesaian yang mungkin masih menyusul ditunggu lewat fokus jendela).
         this.wizardAdvancePending = true;
         this.tryWizardAdvance();
      }

   }

   /**
    * CASH BON → konfirmasi ke pelanggan lewat WA BESERTA FOTO buktinya: pesanan sudah diantar tapi
    * belum dibayar. Pelanggan yang tak menyaksikan pengiriman tidak tahu galonnya sudah datang;
    * tanpa pemberitahuan, tagihan yang muncul di pengiriman berikutnya terasa datang entah dari mana.
    *
    * <p>Dipanggil dari {@link #finishComplete} TEPAT setelah foto bukti tersimpan, dan langsung
    * membuka WhatsApp tanpa dialog perantara — alur "foto lalu kirim" jadi satu tarikan napas.
    * Foto yang barusan diambil ikut sebagai lampiran; bila jalur penyelesaian ini tak membawa foto
    * (proofPath null), {@link WaShare} otomatis jatuh ke pesan teks saja. Perangkat tanpa aplikasi
    * kamera tak lagi bisa menyelesaikan Cash Bon tanpa foto (lihat {@link #launchProofCamera}).</p>
    */
   private void sendCashBonNoticeWa(Transaction t, String proofPath) {
      if (t != null) {
         String phone = t.getCustomerPhone();
         if (WaShare.hasUsablePhone(phone)) {
            // LANGSUNG buka WhatsApp dengan foto + caption sudah terisi — tanpa dialog antara.
            // Kurir baru saja memotret bukti untuk order yang uangnya tak diterima; menyisipkan
            // layar "mau kirim?" di situ hanya menambah satu ketukan pada langkah yang memang
            // wajib, dan setiap ketukan tambahan di pintu pelanggan adalah langkah yang mudah
            // terlewat. Pengirimannya sendiri tetap ditekan kurir di dalam WhatsApp: klik-otomatis
            // pada layar pratinjau media tidak bisa diverifikasi tujuannya ({@see WaShare}), jadi
            // memaksakannya berisiko mengirim foto ke chat orang lain.
            // WA Bridge server DULU: foto bukti + konfirmasi terkirim langsung dari akun yang
            // dipilih server sesuai prioritas — kurir tak perlu membuka WhatsApp sama sekali.
            // Gagal → jalur lama (WhatsApp HP dengan foto + caption terisi).
            String notice = this.composeUnpaidNotice(t);
            String custName = safe(t.getCustomerName());
            com.crowja.damiupos.wa.WaBridgeSend.sendOrFallback(this,
                  com.crowja.damiupos.wa.WaBridgeSend.Msg.to(phone, notice).type("struk")
                        .customerId(t.getCustomerId()).transactionId(t.getId())
                        .media(proofPath, "image/jpeg", "bukti-antar.jpg"),
                  null,
                  () -> {
                     Toast.makeText(this, "Membuka WhatsApp — kirim konfirmasi + foto ke pelanggan.", 1).show();
                     WaShare.sendPhotoWithCaption(this, custName, phone, proofPath, notice);
                  });
         } else {
            Toast.makeText(this, "Pelanggan belum punya nomor WA — konfirmasi pengiriman tak bisa dikirim.", 1).show();
         }
      }
   }

   /**
    * Isi konfirmasi pengiriman untuk order Cash Bon: apa yang diantar, galon kosong yang diambil,
    * dan tagihan yang masih terbuka. Memakai ID transaksi struk & sisa hutang hidup dari buku besar,
    * jadi angkanya sama dengan yang dilihat pelanggan di struk maupun di dashboard.
    */
   private String composeUnpaidNotice(Transaction t) {
      NumberFormat nf = NumberFormat.getNumberInstance(new Locale("in", "ID"));
      StringBuilder sb = new StringBuilder();
      sb.append("Assalamualaikum, Pelanggan Yth.\n\n")
            .append("Pesanan air minum Anda sudah kami antarkan hari ini. Terlampir foto galon yang ")
            .append("kami letakkan sebagai konfirmasi.");
      String rno = t.getReceiptNo();
      if (rno != null && !rno.isEmpty()) {
         sb.append("\n\n🧾 ").append(rno);
      }

      sb.append("\nGalon diantar: ").append(t.getJumlahGalon()).append(" galon");
      // Galon KOSONG yang kami terima kembali. Nol pun DISEBUTKAN eksplisit: pelanggan yang tak
      // menyaksikan pengiriman tak tahu apakah galon kosongnya terambil, dan angka "galon dipinjam"
      // miliknya justru BERTAMBAH ketika tak ada yang dikembalikan — itu yang paling perlu jelas.
      int kembali = this.dao.getReturnedGalonForSale(t.getCustomerId(), t.getTanggal());
      sb.append("\nGalon kosong kami terima: ").append(kembali).append(" galon");
      if (kembali <= 0) {
         sb.append("\n(belum ada galon kosong yang kami terima pada pengiriman ini)");
      }

      sb.append("\nNominal pesanan ini: Rp").append(nf.format(Math.round(t.getTotalHarga())));
      double sisa = t.getCustomerId() > 0L
            ? (new CustomerDebtDao(DatabaseHelper.getInstance(this))).balanceFor(t.getCustomerId())
            : (double)0.0F;
      if (sisa > (double)0.0F) {
         sb.append("\nTotal yang bisa dibayarkan nanti: Rp").append(nf.format(Math.round(sisa)));
      }

      sb.append("\n\nPembayarannya bisa sekalian di pesanan berikutnya, tidak perlu repot. ")
            .append("Terima kasih atas kepercayaannya 🙏");
      return sb.toString();
   }

   private void openWhatsApp(String phone, String msg) {
      openWhatsApp(this, phone, msg);
   }

   /** Buka chat WA (WhatsApp → WA Business → apa pun yang menangani wa.me). Dipakai juga oleh
    *  kartu antrean perangkat lain (tab Perangkat Lain & OtherDeviceQueueActivity). */
   static void openWhatsApp(android.content.Context ctx, String phone, String msg) {
      if (phone == null || phone.replaceAll("[^0-9]", "").isEmpty()) {
         Toast.makeText(ctx, "Pelanggan ini tidak punya nomor WA.", Toast.LENGTH_SHORT).show();
         return;
      }
      String normalized = phone.replaceAll("[^0-9]", "");
      if (normalized.startsWith("0")) {
         normalized = "62" + normalized.substring(1);
      } else if (!normalized.startsWith("62")) {
         normalized = "62" + normalized;
      }

      final Uri uri = Uri.parse("https://wa.me/" + normalized + "?text=" + Uri.encode(msg == null ? "" : msg));
      // HP dengan LEBIH DARI SATU WhatsApp (WhatsApp + WhatsApp Business) → tanya mau lewat yang
      // mana; nomor pengirim ke pelanggan bisa beda, jadi jangan dipilihkan diam-diam.
      final List<String[]> apps = new ArrayList<>();
      for (String[] a : new String[][]{{"com.whatsapp", "WhatsApp"}, {"com.whatsapp.w4b", "WhatsApp Business"}}) {
         try {
            ctx.getPackageManager().getPackageInfo(a[0], 0);
            apps.add(a);
         } catch (Exception ignored) {
         }
      }
      if (apps.size() > 1 && ctx instanceof android.app.Activity) {
         String[] labels = new String[apps.size()];
         for (int k = 0; k < apps.size(); k++) labels[k] = apps.get(k)[1];
         (new AlertDialog.Builder(ctx)).setTitle("Kirim lewat WhatsApp yang mana?")
               .setItems(labels, (d, which) -> launchWa(ctx, uri, apps.get(which)[0]))
               .setNegativeButton("Batal", (DialogInterface.OnClickListener) null).show();
         return;
      }
      launchWa(ctx, uri, apps.isEmpty() ? null : apps.get(0)[0]);
   }

   private static void launchWa(android.content.Context ctx, Uri uri, String pkg) {
      try {
         Intent i = new Intent("android.intent.action.VIEW", uri);
         if (pkg != null) i.setPackage(pkg);
         ctx.startActivity(i);
      } catch (Exception e) {
         Toast.makeText(ctx, "Tidak dapat membuka WhatsApp", 0).show();
      }
   }

   private List<Transaction> selectedGeoStops() {
      List<Transaction> geo = new ArrayList();

      for(Transaction t : this.adapter.data) {
         if (this.selectedIds.contains(t.getId()) && hasGeo(t)) {
            geo.add(t);
         }
      }

      return geo;
   }

   private List<Transaction> runningGeoStops() {
      List<Transaction> geo = new ArrayList();

      for(Transaction t : this.runStops()) {
         if (hasGeo(t)) {
            geo.add(t);
         }
      }

      return geo;
   }

   private static double effectiveLat(Transaction t) {
      return t.getDeliveryDestLat() == (double)0.0F && t.getDeliveryDestLng() == (double)0.0F ? t.getCustomerLat() : t.getDeliveryDestLat();
   }

   private static double effectiveLng(Transaction t) {
      return t.getDeliveryDestLat() == (double)0.0F && t.getDeliveryDestLng() == (double)0.0F ? t.getCustomerLng() : t.getDeliveryDestLng();
   }

   private static boolean hasGeo(Transaction t) {
      return effectiveLat(t) != (double)0.0F || effectiveLng(t) != (double)0.0F;
   }

   // ------------------------------------------------------------------ 🧭 Navigasi PER TITIK (wizard)

   /**
    * 🧭 Navigasi rit = PER TITIK (owner 2026-10-18). Sisa perhentian rit disusun ulang dulu di server
    * (rute jalan terpendek dari GPS HP, tingkat prioritas web ⭐ › level ×N tetap mutlak — data prioritas
    * TERBARU server, jadi kenaikan prioritas dari web langsung berlaku), lalu Maps diarahkan ke SATU
    * titik: yang pertama. Panel navigasi punya ✅ Selesai / ⏭ Lewati; tiap Selesai mengulang langkah ini.
    * Tanpa sinyal → navigasi titik pertama urutan yang ada sekarang.
    */
   private void startWizardNav() {
      if (!this.isRunning()) {
         Toast.makeText(this, "Belum ada rit yang berjalan", Toast.LENGTH_SHORT).show();
         return;
      }
      this.wizardActive = true;
      List<Transaction> geo = this.runningGeoStops();
      if (geo.isEmpty()) {
         Toast.makeText(this, "Order yang sedang diantar belum punya titik koordinat", Toast.LENGTH_LONG).show();
         return;
      }
      SyncSettings cfg = this.syncCfg();
      List<String> uuids = new ArrayList<>();
      for (Transaction t : geo) {
         String u = this.dao.getSyncUuidById(t.getId());
         if (u != null && !u.isEmpty()) uuids.add(u);
      }
      if (geo.size() < 2 || !cfg.isEnrolled() || uuids.size() < 2) {
         this.navigateWizard(null);
         return;
      }
      this.showWizardWait("Menghitung rute terpendek dari posisi Anda…");
      this.currentFixForRit((loc) -> (new Thread(() -> {
         JSONArray order = null;
         try {
            JSONObject body = new JSONObject();
            if (loc != null) {
               body.put("lat", loc.getLatitude());
               body.put("lng", loc.getLongitude());
            }
            body.put("uuids", new JSONArray(uuids));
            order = (new SyncApi(cfg)).optimizeRoute(body).optJSONArray("order");
         } catch (Throwable ignored) {
         }
         final JSONArray fo = order;
         this.runOnUiThread(() -> {
            this.dismissWizardWait();
            if (this.isFinishing() || this.isDestroyed()) return;
            List<String> serverOrder = null;
            if (fo != null) {
               serverOrder = new ArrayList<>();
               for (int i = 0; i < fo.length(); i++) {
                  this.dao.cacheDeliverySeq(fo.optString(i), i + 1);
                  serverOrder.add(fo.optString(i));
               }
               this.loadData();   // kartu & Strategi ikut urutan rute baru
            }
            this.navigateWizard(serverOrder);
         });
      })).start());
   }

   /**
    * Maps ke titik PERTAMA sisa rit; panel navigasi memantau semua titik rit. Urutan = urutan SERVER
    * (data prioritas server terkini) bila ada, else urutan rit lokal; titik yang di-"⏭ Lewati" di
    * belakang.
    */
   private void navigateWizard(@Nullable List<String> serverOrder) {
      List<Transaction> geo = this.runningGeoStops();
      if (geo.isEmpty()) {
         this.wizardAdvance();
         return;
      }
      if (serverOrder != null && !serverOrder.isEmpty()) {
         final Map<Long, Integer> rank = new HashMap<>();
         for (Transaction t : geo) {
            int r = serverOrder.indexOf(this.dao.getSyncUuidById(t.getId()));
            rank.put(t.getId(), r < 0 ? Integer.MAX_VALUE : r);
         }
         Collections.sort(geo, (a, b) -> Integer.compare(rank.get(a.getId()), rank.get(b.getId())));
      }
      final java.util.Set<Long> skipped = wizardSkips(this);
      if (!skipped.isEmpty()) {
         List<Transaction> head = new ArrayList<>(), tail = new ArrayList<>();
         for (Transaction t : geo) (skipped.contains(t.getId()) ? tail : head).add(t);
         geo = head;
         geo.addAll(tail);
      }
      long[] ritIds = new long[geo.size()];
      for (int i = 0; i < geo.size(); ++i) ritIds[i] = geo.get(i).getId();
      List<Transaction> first = new ArrayList<>();
      first.add(geo.get(0));
      this.openMapsRoute(first, ritIds);
      Toast.makeText(this, "🧭 Titik 1/" + geo.size() + ": " + safe(geo.get(0).getCustomerName()), Toast.LENGTH_LONG).show();
   }

   /** Order dari panel navigasi "✅ Selesai": jalankan alur penyelesaian yang SAMA dengan tombol ✓ kartu. */
   private void openPendingComplete() {
      long id = this.pendingCompleteTrxId;
      if (id <= 0L) return;
      this.pendingCompleteTrxId = -1L;
      boolean inRit = this.runningIds.contains(id);
      if (inRit && !this.guidedMode) {
         this.wizardActive = true;
         this.wizardCompleteId = id;
      }
      Transaction t = this.findQueueTrx(id);
      if (t != null) {
         this.maybeWarnIncompleteThenComplete(t);
      } else if (inRit && !this.guidedMode) {
         Toast.makeText(this, "Order itu sudah tidak ada di antrean — lanjut ke titik berikutnya.", Toast.LENGTH_LONG).show();
         this.wizardAdvancePending = true;
         this.tryWizardAdvance();
      } else {
         Toast.makeText(this, "Order itu sudah tidak ada di antrean.", Toast.LENGTH_LONG).show();
      }
   }

   @Override
   public void onWindowFocusChanged(boolean hasFocus) {
      super.onWindowFocusChanged(hasFocus);
      if (hasFocus) this.tryWizardAdvance();
   }

   /** Lanjut hanya saat layar ini benar-benar bebas (tak ada dialog penyelesaian / WA yang menyusul). */
   private void tryWizardAdvance() {
      if (!this.wizardAdvancePending) return;
      this.tick.postDelayed(() -> {
         if (!this.wizardAdvancePending || this.isFinishing() || this.isDestroyed() || !this.hasWindowFocus()) return;
         this.wizardAdvancePending = false;
         this.wizardAdvance();
      }, 900L);
   }

   private void wizardAdvance() {
      if (!this.isRunning() || this.runStops().isEmpty()) {
         this.ritFinishedWizard();
         return;
      }
      if (this.runningGeoStops().isEmpty()) {
         Toast.makeText(this, "Sisa perhentian rit belum punya titik peta — antar manual dari daftar.", Toast.LENGTH_LONG).show();
         return;
      }
      this.startWizardNav();
   }

   /**
    * Rit habis: tarik data TERBARU dulu (prioritas yang dinaikkan di web selama rit berjalan — mis.
    * pelanggan D komplain lalu jadi teratas), baru tawarkan rit berikutnya yang disusun ulang.
    */
   private void ritFinishedWizard() {
      clearWizardSkips(this);
      if (this.guidedMode) return;   // mode terpandu punya alur rit otomatis sendiri
      this.awaitingNextRit = true;
      this.showWizardWait("✅ Rit selesai — memuat prioritas terbaru…");
      SyncScheduler.syncNow(this.getApplicationContext());
      this.tick.postDelayed(() -> {
         if (this.awaitingNextRit) this.offerNextRit();
      }, 15000L);
   }

   private void offerNextRit() {
      if (!this.awaitingNextRit || this.isFinishing() || this.isDestroyed()) return;
      this.awaitingNextRit = false;
      this.dismissWizardWait();
      this.loadData();
      if (this.isRunning()) return;
      // strategyTrips bisa basi (renderStrategy tak mengosongkannya saat antrean habis) → hanya order
      // yang SUNGGUH masih di Antrian Saya; tanpa ini rit terakhir menawarkan order yang baru diantar.
      java.util.Set<Long> alive = new java.util.HashSet<>();
      if (this.adapter != null && this.adapter.data != null) {
         for (Transaction t : this.adapter.data) if (t != null) alive.add(t.getId());
      }
      List<Transaction> stops = new ArrayList<>();
      if (this.strategyTrips != null && !this.strategyTrips.isEmpty()) {
         for (Transaction t : ((DeliveryPlanner.Trip) this.strategyTrips.get(0)).stops) {
            if (t != null && !t.isOpenDispatch() && alive.contains(t.getId())) stops.add(t);
         }
      }
      if (stops.isEmpty()) {
         this.wizardActive = false;
         (new AlertDialog.Builder(this)).setTitle("🎉 Rit Selesai")
               .setMessage("Semua perhentian rit sudah diantar dan tak ada antrean lain untuk rit berikutnya.")
               .setPositiveButton("OK", (DialogInterface.OnClickListener) null).show();
         return;
      }
      (new AlertDialog.Builder(this)).setCancelable(false).setTitle("✅ Rit Selesai — Rit Berikutnya (" + stops.size() + " order)")
            .setView(this.stopsDialogView(null, stops, "Disusun ulang dengan prioritas TERBARU dari web. Muat galonnya, lalu jalankan — navigasi langsung ke titik pertama."))
            .setPositiveButton("▶ Jalankan & Navigasi", (d, w) -> {
               this.navAfterStartAt = System.currentTimeMillis();
               this.doStartRuns(stops);
            })
            .setNegativeButton("Nanti", (d, w) -> {
               this.wizardActive = false;
               this.navAfterStartAt = 0L;
            })
            .show();
   }

   /** "⏭ Lewati" di panel navigasi: diingat supaya penyusunan ulang berikutnya menaruhnya di belakang. */
   static void rememberWizardSkip(Context ctx, long id) {
      java.util.Set<Long> s = wizardSkips(ctx);
      s.add(id);
      saveWizardSkips(ctx, s);
   }

   static void forgetWizardSkip(Context ctx, long id) {
      java.util.Set<Long> s = wizardSkips(ctx);
      if (s.remove(id)) saveWizardSkips(ctx, s);
   }

   static void clearWizardSkips(Context ctx) {
      saveWizardSkips(ctx, new java.util.LinkedHashSet<>());
   }

   static java.util.Set<Long> wizardSkips(Context ctx) {
      java.util.Set<Long> out = new java.util.LinkedHashSet<>();
      try {
         String raw = new SettingsDao(DatabaseHelper.getInstance(ctx)).get(KEY_WIZARD_SKIPS, "");
         for (String p : raw.split(",")) if (!p.trim().isEmpty()) out.add(Long.parseLong(p.trim()));
      } catch (Exception ignored) {
      }
      return out;
   }

   private static void saveWizardSkips(Context ctx, java.util.Set<Long> ids) {
      StringBuilder sb = new StringBuilder();
      for (Long id : ids) sb.append(sb.length() > 0 ? "," : "").append(id);
      try {
         new SettingsDao(DatabaseHelper.getInstance(ctx)).set(KEY_WIZARD_SKIPS, sb.toString());
      } catch (Exception ignored) {
      }
   }

   private void showWizardWait(String msg) {
      this.dismissWizardWait();
      try {
         this.wizardWait = new android.app.ProgressDialog(this);
         this.wizardWait.setMessage(msg);
         this.wizardWait.setCancelable(true);
         this.wizardWait.show();
      } catch (Exception ignored) {
      }
   }

   private void dismissWizardWait() {
      try {
         if (this.wizardWait != null && this.wizardWait.isShowing()) this.wizardWait.dismiss();
      } catch (Exception ignored) {
      }
      this.wizardWait = null;
   }

   /**
    * 🧭 Navigasi rit: LANGSUNG membuka Google Maps dengan rute rit berjalan (tanpa dialog konfirmasi
    * lagi) — titik = perhentian rit bertitik peta menurut urutan rit (rencana terkini, termasuk hasil
    * "Urutkan Rute Terpendek"), maks 10 (batas Google Maps), tujuan = titik terakhir. DAMIU POS lalu
    * mengecil jadi jendela melayang (NavigationPipActivity) berisi perhentian aktif. PiP tak bisa
    * dipakai → Maps dibuka langsung seperti dulu + petunjuk sekali.
    */
   private void navigasiRitAktif() {
      List<Transaction> stops = this.runningGeoStops();
      if (stops.isEmpty()) {
         Toast.makeText(this, "Order yang sedang diantar belum punya titik koordinat", Toast.LENGTH_LONG).show();
         return;
      }

      // SEMUA perhentian bertitik dipantau jendela melayang; Maps hanya menerima 10 pertama.
      long[] ritIds = new long[stops.size()];
      for (int i = 0; i < stops.size(); ++i) {
         ritIds[i] = stops.get(i).getId();
      }
      List<Transaction> route = stops;
      if (stops.size() > DeliveryNavLogic.MAX_MAPS_STOPS) {
         route = DeliveryNavLogic.capRoute(stops);
         Toast.makeText(this, "Dibatasi 10 tujuan (batas Google Maps)", Toast.LENGTH_SHORT).show();
      }
      this.openMapsRoute(route, ritIds);
   }

   private void openMapsRoute(List<Transaction> stops, long[] ritIds) {
      String url = buildMapsDirUrl(stops);
      if (url == null) return;
      boolean floating = NavigationPipActivity.launchRit(this, ritIds, url);
      if (!floating && !NavigationPipActivity.startMaps(this, NavigationPipActivity.routeIntent(this, url, false))) {
         return;   // "Tidak ada aplikasi peta" sudah ditampilkan
      }

      Toast.makeText(this, "Rute " + stops.size() + " tujuan dibuka", Toast.LENGTH_SHORT).show();
      if (!floating) NavigationPipActivity.showPipHintOnce(this);
      this.exitSelectionMode();
   }

   /** URL rute Maps (urutan = urutan {@code stops}, maks 10) + dir_action=navigate — lihat DeliveryNavLogic. */
   private static String buildMapsDirUrl(List<Transaction> stops) {
      List<double[]> pts = new ArrayList<>(stops.size());
      for (Transaction t : stops) {
         pts.add(new double[]{effectiveLat(t), effectiveLng(t)});
      }
      return DeliveryNavLogic.buildDirUrl(pts, true);
   }

   /**
    * "🧭 Navigasi" satu order (Preview / FAB panel mode terpandu). Order antrean SENDIRI → jendela
    * melayang berisi ringkasan order itu di atas Maps; selain itu (antrean perangkat lain, atau PiP
    * tak bisa dipakai) → Maps langsung seperti dulu.
    */
   private void navigateToOrder(long trxId, double lat, double lng) {
      if (trxId > 0L && NavigationPipActivity.launchSingle(this, trxId, lat, lng)) return;
      this.openMapsNavigation(lat, lng);
      if (trxId > 0L) NavigationPipActivity.showPipHintOnce(this);
   }

   private static double haversineKm(double lat1, double lng1, double lat2, double lng2) {
      double r = (double)6371.0F;
      double dLat = Math.toRadians(lat2 - lat1);
      double dLng = Math.toRadians(lng2 - lng1);
      double a = Math.sin(dLat / (double)2.0F) * Math.sin(dLat / (double)2.0F) + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLng / (double)2.0F) * Math.sin(dLng / (double)2.0F);
      return r * (double)2.0F * Math.atan2(Math.sqrt(a), Math.sqrt((double)1.0F - a));
   }

   private void showOrderDetail(Transaction t) {
      StringBuilder sb = new StringBuilder();
      if (t.getSourceWa() != null && !t.getSourceWa().trim().isEmpty() && !"null".equals(t.getSourceWa().trim())) {
         sb.append("\ud83d\udcac Diterima via ").append(t.getSourceWa().trim()).append('\n');
      }
      if (t.getCustomerPhone() != null && !t.getCustomerPhone().trim().isEmpty()) {
         sb.append("\ud83d\udcde ").append(t.getCustomerPhone().trim()).append('\n');
      }

      if (t.getCustomerAddress() != null && !t.getCustomerAddress().trim().isEmpty()) {
         sb.append("\ud83d\udccd ").append(t.getCustomerAddress().trim()).append('\n');
      }

      if (t.isResellerDestination()) {
         // Diantar ke rumah RESELLER, bukan afiliasi — alamat 📍 di atas milik afiliasi (pelanggan
         // order ini), jadi tujuan sebenarnya harus terbaca jelas.
         sb.append("🤝 ").append(Transaction.resellerDestLabel(t.getDeliveryDestName())).append('\n');
      } else if (t.getDeliveryDestName() != null && !t.getDeliveryDestName().trim().isEmpty()) {
         sb.append("\ud83d\udccd Kirim ke: ").append(t.getDeliveryDestName().trim()).append('\n');
      }

      if (sb.length() > 0) {
         sb.append('\n');
      }

      sb.append("Pesanan:\n");
      List<TransactionItem> items = t.getItems();
      if (items != null && !items.isEmpty()) {
         for(TransactionItem it : items) {
            String nm = it.productName != null && !it.productName.isEmpty() ? it.productName : "Galon";
            sb.append("• ").append(nm).append("  ").append(it.jumlah).append(" galon");
            if (it.hargaPerGalon > (double)0.0F) {
               sb.append(" × Rp ").append(formatRupiah(it.hargaPerGalon)).append(" = Rp ").append(formatRupiah(it.getSubtotal()));
            }

            sb.append('\n');
         }
      } else {
         sb.append("• ").append(t.getJumlahGalon()).append(" galon\n");
      }

      sb.append('\n');
      if (t.getOngkir() > (double)0.0F) {
         sb.append("Ongkir: Rp ").append(formatRupiah(t.getOngkir())).append('\n');
      }

      sb.append("Total: Rp ").append(formatRupiah(t.getTotalHarga())).append('\n');
      String pay = t.getPaymentMethodLabel();
      if (pay != null && !pay.isEmpty()) {
         sb.append("Pembayaran: ").append(pay).append('\n');
      }

      // Dari saldo refund + Total setelah refund — sama persis dengan yang tampil di popup Tandai Selesai.
      String refundLines = this.refundSummaryText(t);
      if (!refundLines.isEmpty()) {
         sb.append(refundLines.startsWith("\n") ? refundLines.substring(1) : refundLines).append('\n');
      }

      // Hutang lama + tagihan di pintu — sama persis dengan yang tampil di popup Tandai Selesai.
      String debtLines = this.debtSummaryText(t);
      if (!debtLines.isEmpty()) {
         sb.append(debtLines.startsWith("\n") ? debtLines.substring(1) : debtLines).append('\n');
      }

      // 🧺 Leg checkout multi-lokasi: tagih HANYA pesanan lokasi ini (sama dengan popup Tandai Selesai).
      String legHint = checkoutCollectHint(t);
      if (!legHint.isEmpty()) {
         sb.append(legHint).append('\n');
      }

      String detailNote = displayNote(t.getCatatan());
      if (detailNote != null) {
         sb.append("\nCatatan: ").append(detailNote).append('\n');
      }

      sb.append("\nMasuk antrian: ").append(formatQueued(t.getDeliveryQueuedAt()));
      sb.append("\nMenunggu: ").append(formatDuration(elapsedMillis(t.getDeliveryQueuedAt())));
      int padH = Math.round(20.0F * this.getResources().getDisplayMetrics().density);
      LinearLayout body = new LinearLayout(this);
      body.setOrientation(1);
      body.setPadding(padH, Math.round(8.0F * this.getResources().getDisplayMetrics().density), padH, 0);
      TextView msg = new TextView(this);
      msg.setText(sb.toString());
      msg.setTextSize(14.0F);
      com.crowja.damiupos.util.NoteLinks.apply(msg);   // link di Catatan bisa diketuk
      body.addView(msg);
      if (t.wasManuallyEdited()) {
         TextView tvEdited = new TextView(this);
         tvEdited.setText("✏️ SUDAH DIUBAH — " + formatQueued(t.getLastManualEditAt()));
         tvEdited.setTextSize(13.0F);
         tvEdited.setTextColor(-1086464);
         tvEdited.setPadding(0, Math.round(8.0F * this.getResources().getDisplayMetrics().density), 0, 0);
         body.addView(tvEdited);
      }

      if (t.hasPendingVoidRequest()) {
         TextView tvVoided = new TextView(this);
         tvVoided.setText("\ud83d\uddd1️ VOID DIAJUKAN — menunggu persetujuan (" + formatQueued(t.getVoidRequestPendingAt()) + ")");
         tvVoided.setTextSize(13.0F);
         tvVoided.setTextColor(-3790808);
         tvVoided.setPadding(0, Math.round(8.0F * this.getResources().getDisplayMetrics().density), 0, 0);
         body.addView(tvVoided);
      }

      if (t.isComplained()) {
         TextView tvComplained = new TextView(this);
         tvComplained.setText("KOMPLAIN: pelanggan pernah komplain soal order ini via WhatsApp -- ketuk \"Lihat Chat Komplain\" di bawah");
         tvComplained.setTextSize(13.0F);
         tvComplained.setTextColor(-1086464);
         tvComplained.setPadding(0, Math.round(8.0F * this.getResources().getDisplayMetrics().density), 0, 0);
         body.addView(tvComplained);
      }

      ScrollView scroll = new ScrollView(this);
      scroll.addView(body);
      boolean voidPending = t.hasPendingVoidRequest();
      AlertDialog.Builder dialogBuilder = (new AlertDialog.Builder(this)).setTitle("Detail Order — " + safe(t.getCustomerName())).setView(scroll).setPositiveButton("Tutup", (DialogInterface.OnClickListener)null).setNeutralButton("Buka Struk", (d, w) -> {
         Intent r = new Intent(this, ReceiptActivity.class);
         r.putExtra("transaction_id", t.getId());
         this.startActivity(r);
      });
      if (!voidPending) {
         dialogBuilder.setNegativeButton("Alokasi Poin", (d, w) -> AllocationDialog.show(this, t, "delivery"));
      }

      AlertDialog dialog = dialogBuilder.create();
      GridLayout actionsGrid = new GridLayout(this);
      actionsGrid.setColumnCount(2);
      actionsGrid.setPadding(0, Math.round(8.0F * this.getResources().getDisplayMetrics().density), 0, 0);
      Button btnPreview = new Button(this);
      btnPreview.setText("🔍 Preview");
      btnPreview.setAllCaps(false);
      btnPreview.setOnClickListener((v) -> {
         dialog.dismiss();
         this.showQueuePreview(t);
      });
      this.addGridAction(actionsGrid, btnPreview);
      if (this.currentUserCanRequestTrxChange() && !voidPending) {
         Button btnUbah = new Button(this);
         btnUbah.setText("✏️ Ubah");
         btnUbah.setAllCaps(false);
         btnUbah.setOnClickListener((v) -> {
            dialog.dismiss();
            this.showUbahMenu(t);
         });
         this.addGridAction(actionsGrid, btnUbah);
      }

      if (!voidPending && "PENDING".equals(t.getDeliveryStatus())) {
         Button btnAlihkan = new Button(this);
         btnAlihkan.setText("\ud83d\udd00 Alihkan");
         btnAlihkan.setAllCaps(false);
         btnAlihkan.setOnClickListener((v) -> {
            dialog.dismiss();
            this.showAlihkanMenu(t);
         });
         this.addGridAction(actionsGrid, btnAlihkan);
      }

      if ("JUAL".equals(t.getType())) {
         Button btnChatPesanan = new Button(this);
         btnChatPesanan.setText("💬 Chat Pesanan");
         btnChatPesanan.setAllCaps(false);
         btnChatPesanan.setOnClickListener((v) -> {
            dialog.dismiss();
            this.openOrderChat(t);
         });
         this.addGridAction(actionsGrid, btnChatPesanan);
      }

      if (t.isComplained()) {
         Button btnChatKomplain = new Button(this);
         btnChatKomplain.setText("Lihat Chat Komplain");
         btnChatKomplain.setAllCaps(false);
         btnChatKomplain.setOnClickListener((v) -> {
            dialog.dismiss();
            this.openChatLog(t);
         });
         this.addGridAction(actionsGrid, btnChatKomplain);
      }

      if (actionsGrid.getChildCount() > 0) {
         body.addView(actionsGrid);
      }

      dialog.show();
   }

   /** Buka viewer log percakapan WA komplain (badge KOMPLAIN) untuk order ini. */
   private void openChatLog(Transaction t) {
      String uuid = this.dao.getSyncUuidById(t.getId());
      if (uuid == null || uuid.isEmpty()) {
         Toast.makeText(this, "Transaksi ini belum tersinkron ke server", Toast.LENGTH_SHORT).show();
         return;
      }
      Intent i = new Intent(this, ChatLogActivity.class);
      i.putExtra(ChatLogActivity.EXTRA_TRANSACTION_UUID, uuid);
      i.putExtra(ChatLogActivity.EXTRA_CUSTOMER_NAME, safe(t.getCustomerName()));
      this.startActivity(i);
   }

   /** "💬 Chat Pesanan" — chat WA order agen AI (+ balas lewat Bridge) untuk order ini. */
   private void openOrderChat(Transaction t) {
      String uuid = this.dao.getSyncUuidById(t.getId());
      if (uuid == null || uuid.isEmpty()) {
         Toast.makeText(this, "Transaksi ini belum tersinkron ke server", Toast.LENGTH_SHORT).show();
         return;
      }
      this.openOrderChatUuid(uuid, safe(t.getCustomerName()));
   }

   /** Sama dengan openOrderChat, untuk kartu perangkat lain (hanya punya uuid dari JSON server). */
   private void openOrderChatUuid(String uuid, String customerName) {
      if (uuid == null || uuid.isEmpty()) {
         Toast.makeText(this, "Transaksi ini belum tersinkron ke server", Toast.LENGTH_SHORT).show();
         return;
      }
      Intent i = new Intent(this, ChatLogActivity.class);
      i.putExtra(ChatLogActivity.EXTRA_MODE, ChatLogActivity.MODE_ORDER);
      i.putExtra(ChatLogActivity.EXTRA_TRANSACTION_UUID, uuid);
      i.putExtra(ChatLogActivity.EXTRA_CUSTOMER_NAME, customerName);
      this.startActivity(i);
   }

   /** "⏸ Antrean Tertunda" — SEMUA order TERTUNDA se-cabang. Pull transaksi per-perangkat, jadi DB
    *  lokal hanya memegang sebagian (tunda milik HP lain tak pernah tertarik ke sini — dulu layar ini
    *  menampilkan 1 order sementara web 13): saat terhubung, daftar diambil dari server
    *  ({@code GET /api/delivery/tertunda}, himpunan sama dengan web) lalu digabung dengan baris lokal
    *  (aturan di util.TertundaRows); gagal → baris lokal saja + banner luring, tak pernah dikosongkan.
    *  Tiap kartu bisa dibuka detailnya, dijadwalkan ulang, dilanjutkan sekarang, atau (perangkat
    *  delivery) dilanjutkan &amp; diambil ke HP ini. */
   private void showTertundaQueueDialog() {
      if (this.isFinishing() || this.isDestroyed()) return;
      final Dialog dialog = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
      LinearLayout root = new LinearLayout(this);
      root.setOrientation(LinearLayout.VERTICAL);
      root.setBackgroundColor(this.getResources().getColor(color.grey_light));

      LinearLayout header = new LinearLayout(this);
      header.setOrientation(LinearLayout.HORIZONTAL);
      header.setGravity(android.view.Gravity.CENTER_VERTICAL);
      header.setBackgroundColor(this.getResources().getColor(color.primary));
      header.setPadding(this.dp(16f), this.dp(14f), this.dp(4f), this.dp(14f));
      TextView title = new TextView(this);
      title.setText("⏸ Antrean Tertunda");
      title.setTextColor(-1);
      title.setTextSize(16f);
      title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
      title.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
      header.addView(title);
      android.widget.ImageButton btnClose = new android.widget.ImageButton(this);
      btnClose.setImageResource(drawable.ic_arrow_back);
      btnClose.setBackgroundColor(0);
      btnClose.setColorFilter(-1);
      btnClose.setOnClickListener((v) -> dialog.dismiss());
      header.addView(btnClose);
      root.addView(header);

      ScrollView scroll = new ScrollView(this);
      scroll.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 1f));
      LinearLayout list = new LinearLayout(this);
      list.setOrientation(LinearLayout.VERTICAL);
      int pad = this.dp(12f);
      list.setPadding(pad, pad, pad, pad);
      scroll.addView(list);
      root.addView(scroll);

      dialog.setContentView(root);
      dialog.show();
      this.reloadTertundaQueue(list, title, dialog);
   }

   /** Sumber daftar Antrean Tertunda yang sedang tampil (menentukan banner). */
   private static final int TERTUNDA_SRC_LOCAL = 0;     // perangkat tak terhubung server: lokal = semuanya
   private static final int TERTUNDA_SRC_LOADING = 1;   // baris lokal dulu, daftar server sedang dimuat
   private static final int TERTUNDA_SRC_SERVER = 2;    // gabungan server (otoritatif) + lokal belum terdorong
   private static final int TERTUNDA_SRC_OFFLINE = 3;   // server gagal → lokal saja

   /** Naik tiap muat ulang Antrean Tertunda — jawaban dari muat ulang yang sudah tersusul (mis. fetch
    *  lambat sebelum aksi) dibuang, supaya daftar lama tak menimpa yang baru. */
   private int tertundaReloadGen = 0;

   /** uuid order Antrean Tertunda yang SUDAH diubah lewat server di sesi Activity ini (aksi 2xx atau
    *  galat basi 404/409/422). Salinan lokalnya basi sampai pull menyusul (async), jadi aksinya tak
    *  boleh lagi lewat DAO lokal — dorongan seluruh baris ber-edited_at=sekarang menang
    *  last-write-wins dan bisa membalikkan order yang baru dilanjutkan jadi TERTUNDA lagi
    *  (TertundaRows.writePath). Hanya diubah di main thread. */
   private final Set<String> tertundaServerTouched = new HashSet<>();
   /** Bagian dari {@link #tertundaServerTouched} yang dinyatakan server SUDAH lepas dari TERTUNDA
    *  (dilanjutkan / tak ada lagi) — disembunyikan dari daftar luring/memuat sampai daftar server
    *  mencantumkannya lagi. Hanya diubah di main thread. */
   private final Set<String> tertundaReleased = new HashSet<>();

   private void reloadTertundaQueue(LinearLayout list, TextView title, Dialog dialog) {
      if (this.isFinishing() || this.isDestroyed() || !dialog.isShowing()) return;   // aksi selesai setelah dialog ditutup
      final int gen = ++this.tertundaReloadGen;
      final SyncSettings cfg = this.syncCfg();
      final boolean enrolled = cfg.isEnrolled();
      // Muat ulang setelah aksi saat daftar server sudah tampil: biarkan daftar itu sampai jawaban
      // server baru tiba (tanpa kedip 13 → 1 → 12 lewat daftar lokal yang hanya sebagian).
      final boolean keepShown = enrolled && Integer.valueOf(TERTUNDA_SRC_SERVER).equals(list.getTag());
      final Set<String> released = new HashSet<>(this.tertundaReleased);   // salinan untuk thread latar
      new Thread(() -> {
         TransactionDao tdao = new TransactionDao(DatabaseHelper.getInstance(this));
         List<TertundaRows.Local<Transaction>> locals = new ArrayList<>();
         for (Transaction t : tdao.getTertundaQueue()) {
            locals.add(new TertundaRows.Local<>(t, t.getSyncUuid(), !t.isSyncPending()));
         }
         // Baris lokal LANGSUNG tampil — tak menunggu jaringan.
         if (!keepShown) {
            List<TertundaRows.Merged<Transaction>> localRows = TertundaRows.merge(null, locals, null, released);
            this.runOnUiThread(() -> this.renderTertundaQueue(list, title, dialog, gen, localRows,
                  enrolled ? TERTUNDA_SRC_LOADING : TERTUNDA_SRC_LOCAL));
         }
         if (!enrolled) return;

         TertundaRows.Result res;
         try {
            res = TertundaRows.parse((new SyncApi(cfg)).deliveryTertunda());
         } catch (Exception e) {
            res = TertundaRows.unavailable();
         }
         final TertundaRows.Result resF = res;
         // Salinan lokal yang belum ikut berstatus TERTUNDA (pull belum tiba) tetap dipasang lewat
         // uuid supaya ketuk kartu membuka detail lokal lengkap. Baris server sendiri TAK PERNAH
         // diberi _id (id di JSON = id server → jalur ber-_id akan mengenai baris lokal lain).
         List<TertundaRows.Merged<Transaction>> merged = TertundaRows.merge(res, locals, (uuid) -> {
            try {
               long id = tdao.getIdBySyncUuid(uuid);
               return id > 0L ? tdao.getById(id) : null;
            } catch (Exception e) {
               return null;
            }
         }, released);
         this.runOnUiThread(() -> {
            // Hanya jawaban TERBARU yang boleh mengoreksi daftar "sudah dilepas" (yang lama bisa
            // diambil sebelum aksi selesai). Order yang dicantumkan lagi = ditunda ulang di tempat lain.
            if (gen == this.tertundaReloadGen) TertundaRows.forgetReleasedListedBy(this.tertundaReleased, resF);
            this.renderTertundaQueue(list, title, dialog, gen, merged,
                  resF.ok ? TERTUNDA_SRC_SERVER : TERTUNDA_SRC_OFFLINE);
         });
      }).start();
   }

   private void renderTertundaQueue(LinearLayout list, TextView title, Dialog dialog, int gen,
         List<TertundaRows.Merged<Transaction>> rows, int source) {
      if (this.isFinishing() || this.isDestroyed() || !dialog.isShowing() || gen != this.tertundaReloadGen) return;
      list.removeAllViews();
      list.setTag(source);
      title.setText("⏸ Antrean Tertunda (" + rows.size() + ")");
      Runnable reload = () -> this.reloadTertundaQueue(list, title, dialog);
      if (source == TERTUNDA_SRC_LOADING) {
         list.addView(this.tertundaBanner("⏳ Memuat pesanan tertunda se-cabang dari server…", false, null));
      } else if (source == TERTUNDA_SRC_OFFLINE) {
         // Daftar lokal hanya SEBAGIAN — katakan terang-terangan supaya tak dikira cuma ini yang tertunda.
         list.addView(this.tertundaBanner("Offline — hanya pesanan di HP ini\nKetuk untuk memuat ulang.", true, reload));
      }

      if (rows.isEmpty()) {
         TextView empty = new TextView(this);
         empty.setText("Tidak ada pesanan tertunda saat ini");
         empty.setTextColor(-7035976);
         empty.setTextSize(14f);
         empty.setGravity(android.view.Gravity.CENTER);
         empty.setPadding(0, this.dp(32f), 0, this.dp(32f));
         list.addView(empty);
         return;
      }

      boolean viewer = this.currentUserIsViewer();
      boolean offline = source == TERTUNDA_SRC_OFFLINE;
      for (TertundaRows.Merged<Transaction> m : rows) {
         list.addView(this.buildTertundaRow(m, viewer, offline, reload));
      }
   }

   private TextView tertundaBanner(String text, boolean warn, Runnable onTap) {
      TextView tv = new TextView(this);
      tv.setText(text);
      tv.setTextSize(13f);
      tv.setTextColor(warn ? Color.parseColor("#E65100") : -7035976);
      if (warn) tv.setBackgroundColor(Color.parseColor("#FFF3E0"));
      int p = this.dp(10f);
      tv.setPadding(p, p, p, p);
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
      lp.bottomMargin = this.dp(10f);
      tv.setLayoutParams(lp);
      if (onTap != null) tv.setOnClickListener((v) -> onTap.run());
      return tv;
   }

   /** Satu kartu Antrean Tertunda. Data tampil dari server bila ada (otoritatif), else dari baris
    *  lokal. Jalur ber-_id (detail lokal, DAO luring) HANYA lewat {@code m.local} — baris server-saja
    *  memakai uuid. */
   private View buildTertundaRow(TertundaRows.Merged<Transaction> m, boolean viewer, boolean offline, Runnable reload) {
      final TertundaRows.Row s = m.server;
      final Transaction t = m.local;
      LinearLayout card = new LinearLayout(this);
      card.setOrientation(LinearLayout.VERTICAL);
      card.setBackgroundColor(-1);
      int padH = this.dp(14f);
      card.setPadding(padH, this.dp(10f), padH, this.dp(10f));
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
      lp.bottomMargin = this.dp(10f);
      card.setLayoutParams(lp);

      final String custName = s != null ? s.name : safe(t.getCustomerName());
      TextView tvName = new TextView(this);
      tvName.setText(custName);
      tvName.setTextSize(15f);
      tvName.setTypeface(tvName.getTypeface(), android.graphics.Typeface.BOLD);
      card.addView(tvName);

      TextView tvMeta = new TextView(this);
      tvMeta.setText(s != null
            ? s.galon + " galon · Rp " + formatRupiah(s.total) + checkoutLegSuffix(s.checkoutSeq, s.checkoutSize) + receiptSuffix(s.receiptNo) + orderedSuffix(s.orderedAt) + sourceWaSuffix(s.sourceWa)
            : t.getJumlahGalon() + " galon · Rp " + formatRupiah(t.getTotalHarga()) + checkoutLegSuffix(t) + receiptSuffix(t.getReceiptNo()) + orderedSuffix(t) + sourceWaSuffix(t.getSourceWa()));
      tvMeta.setTextSize(13f);
      tvMeta.setTextColor(-10395295);
      card.addView(tvMeta);

      // 📱 Pemegang order — kartu harus bisa bilang order ini milik HP lain (dulu tak ada sama sekali).
      // Pesanan Terbuka yang dijeda / order web tanpa rute diberi label sendiri, bukan nama HP asal.
      StringBuilder owner = new StringBuilder();
      if (s != null) {
         owner.append(s.ownerLabel());
         if (s.inProgress) owner.append(owner.length() > 0 ? " · " : "").append("🚚 Sedang diantar");
         if (s.voidPending) owner.append(owner.length() > 0 ? " · " : "").append("🗑️ Void diajukan");
      } else if (this.tertundaServerTouched.contains(m.uuid())) {
         // Server sudah mengubahnya di sesi ini (daftar ini fallback luring) — salinan lokal basi.
         owner.append("⏳ Sudah diubah lewat server — menunggu sinkron");
      } else if (t.isSyncPending()) {
         owner.append("⏳ Belum tersinkron ke server");
      }
      if (owner.length() > 0) {
         TextView tvOwner = new TextView(this);
         tvOwner.setText(owner.toString());
         tvOwner.setTextSize(13f);
         tvOwner.setTextColor(-10395295);
         card.addView(tvOwner);
      }

      TextView tvResume = new TextView(this);
      // Ts.local: jadwal tarikan server berbentuk ISO-UTC — formatQueued saja menampilkannya mentah
      // (huruf 'T', selisih 7 jam).
      tvResume.setText("🕒 Lanjut: " + formatTertundaTime(s != null ? s.resumeAtLocal : t.getDeliveryTertundaResumeAt()));
      tvResume.setTextSize(13f);
      tvResume.setTextColor(-3790808);
      LinearLayout.LayoutParams resumeLp = new LinearLayout.LayoutParams(-2, -2);
      resumeLp.topMargin = this.dp(4f);
      tvResume.setLayoutParams(resumeLp);
      card.addView(tvResume);

      String reason = s != null ? s.tertundaReason : t.getDeliveryTertundaReason();
      if (reason != null && !reason.trim().isEmpty() && !"null".equals(reason.trim())) {
         TextView tvReason = new TextView(this);
         tvReason.setText("📝 " + reason.trim());
         tvReason.setTextSize(13f);
         tvReason.setTextColor(-10395295);
         card.addView(tvReason);
      }

      if (!viewer) {
         GridLayout actions = new GridLayout(this);
         actions.setColumnCount(2);
         actions.setPadding(0, this.dp(8f), 0, 0);
         Button btnReschedule = new Button(this);
         btnReschedule.setText("🕒 Jadwalkan Ulang");
         btnReschedule.setAllCaps(false);
         btnReschedule.setOnClickListener((v) -> this.showTertundaReschedulePicker(m, custName, offline, reload));
         this.addGridAction(actions, btnReschedule);
         Button btnResume = new Button(this);
         btnResume.setText("▶ Lanjutkan");
         btnResume.setAllCaps(false);
         btnResume.setOnClickListener((v) -> this.confirmResumeTertunda(m, custName, offline, reload));
         this.addGridAction(actions, btnResume);
         if (this.canClaimTertunda(s)) {
            Button btnClaim = new Button(this);
            btnClaim.setText("📥 Lanjutkan & Ambil");
            btnClaim.setAllCaps(false);
            btnClaim.setOnClickListener((v) -> this.confirmResumeClaimTertunda(s, reload));
            this.addGridAction(actions, btnClaim);
         }
         card.addView(actions);
      }

      card.setOnClickListener((v) -> {
         if (t != null) {
            this.showOrderDetail(t);
         } else if (s != null) {
            this.showTertundaRemoteDetail(s);
         }
      });
      return card;
   }

   /** Jadwal tertunda bentuk apa pun (lokal tulisan HP / ISO-UTC tarikan server) → teks lokal. */
   private static String formatTertundaTime(String any) {
      String local = Ts.local(any);
      return local.isEmpty() ? "-" : formatQueued(local);
   }

   /** Peran Viewer hanya melihat — aksi Antrean Tertunda disembunyikan. Tanpa login (uid&lt;=0) =
    *  mode satu pengguna (owner) → bukan viewer. */
   private boolean currentUserIsViewer() {
      long uid = (new SettingsDao(DatabaseHelper.getInstance(this))).getCurrentUserId();
      if (uid <= 0L) return false;
      User u = (new UserDao(DatabaseHelper.getInstance(this))).getById(uid);
      return u != null && u.isViewer();
   }

   /** Jalur tulis aksi satu kartu (TertundaRows.writePath): endpoint server ber-prasyarat status
    *  bila terhubung &amp; baris dikenal server — dari daftar server, salinan lokal yang sudah terdorong
    *  (selagi daftar server masih dimuat), atau order yang SUDAH diubah lewat server di sesi ini
    *  (walau daftar yang tampil luring). Luring / baris lokal belum terdorong → DAO lokal lama. */
   private TertundaRows.WritePath tertundaWritePath(TertundaRows.Merged<Transaction> m, boolean offline) {
      String uuid = m.uuid();
      return TertundaRows.writePath(this.syncCfg().isEnrolled(), offline, uuid, m.server != null, m.local != null,
            m.local != null && m.local.isSyncPending(), this.tertundaServerTouched.contains(uuid));
   }

   /** "📥 Lanjutkan &amp; Ambil" — syaratnya di TertundaRows.canClaim. */
   private boolean canClaimTertunda(TertundaRows.Row s) {
      SyncSettings cfg = this.syncCfg();
      return TertundaRows.canClaim(s, cfg.isEnrolled(), cfg.isDeliveryDevice(), cfg.getDeviceUuid());
   }

   /** Catat jawaban server atas satu order (main thread): server sudah mengubahnya → salinan lokal
    *  basi; {@code released} = server menyatakannya sudah lepas dari TERTUNDA. */
   private void markTertundaServerTouched(String uuid, boolean released) {
      if (uuid == null || uuid.isEmpty()) return;
      this.tertundaServerTouched.add(uuid);
      if (released) this.tertundaReleased.add(uuid);
   }

   /** Setelah order keluar dari TERTUNDA lewat server: tarik keadaan baru, segarkan Antrian Saya DAN
    *  Perangkat Lain — order yang dilanjutkan pindah ke salah satunya, dan menutup Dialog tak memicu
    *  onResume (pola takeOverOtherDevices / doPostponeOther / runBulkClaim). */
   private void afterTertundaServerChange() {
      SyncScheduler.syncNow(this.getApplicationContext());
      this.loadData();
      this.loadOtherDevices();
   }

   /** Detail order tertunda yang TIDAK ada di HP ini — dari baris server saja (gaya Detail Order
    *  Perangkat Lain + baris tertunda). Tombolnya hanya yang cukup dengan uuid/JSON: 🔍 Preview,
    *  👤 Detail Pelanggan (pelanggan tersinkron se-cabang), 💬 Chat Pesanan — sengaja tanpa tombol
    *  ber-_id (struk, alokasi, ubah/void). */
   private void showTertundaRemoteDetail(TertundaRows.Row s) {
      JSONObject q;
      String text;
      try {
         q = new JSONObject(s.json);
         q.remove("queued_at");   // umur antrean lama tak bermakna untuk order yang sedang ditunda
         text = this.otherDeviceOrderDetailText(q).trim();
      } catch (Exception e) {
         q = null;
         text = s.galon + " galon · Rp " + formatRupiah(s.total);
      }
      StringBuilder sb = new StringBuilder(text);
      sb.append("\n\n⏸ TERTUNDA");
      if (s.mine || s.openDispatch) sb.append(" · ").append(s.ownerLabel());
      sb.append("\n🕒 Lanjut: ").append(formatTertundaTime(s.resumeAtLocal));
      if (!s.dueAtLocal.isEmpty() && !s.dueAtLocal.equals(s.resumeAtLocal)) {
         sb.append("\n⏰ Kembali ke antrean otomatis: ").append(formatTertundaTime(s.dueAtLocal));
      }
      if (!s.tertundaAtLocal.isEmpty()) sb.append("\n⏸ Ditunda sejak: ").append(formatTertundaTime(s.tertundaAtLocal));
      if (!s.tertundaReason.isEmpty()) sb.append("\n📝 Alasan: ").append(s.tertundaReason);
      if (!s.tertundaPhotoUrl.isEmpty()) sb.append("\n📷 Ada foto alasan (lihat di dashboard)");
      if (!s.note.isEmpty()) sb.append("\n🗒️ Catatan: ").append(s.note);
      if (!s.receiptNo.isEmpty()) sb.append("\n🧾 ").append(s.receiptNo);
      if (!s.paymentMethod.isEmpty()) sb.append("\n💳 ").append(s.paymentMethod);
      if (s.voidPending) sb.append("\n🗑️ Permintaan void sedang diajukan");

      int padH = this.dp(20f);
      LinearLayout body = new LinearLayout(this);
      body.setOrientation(LinearLayout.VERTICAL);
      body.setPadding(padH, this.dp(8f), padH, 0);
      TextView msg = new TextView(this);
      msg.setText(sb.toString());
      msg.setTextSize(14f);
      com.crowja.damiupos.util.NoteLinks.apply(msg);   // link di Catatan bisa diketuk
      body.addView(msg);
      ScrollView scroll = new ScrollView(this);
      scroll.addView(body);
      AlertDialog dialog = (new AlertDialog.Builder(this)).setTitle("Detail Order — " + s.name)
            .setView(scroll)
            .setPositiveButton("Tutup", (DialogInterface.OnClickListener) null)
            .create();

      GridLayout actionsGrid = new GridLayout(this);
      actionsGrid.setColumnCount(2);
      actionsGrid.setPadding(0, this.dp(8f), 0, 0);
      final JSONObject qF = q;
      if (qF != null) {
         // Preview Perangkat Lain: koordinat dari baris ini bila peta tak memuatnya (kebanyakan
         // order tertunda memang tak ada di peta — hanya Pesanan Terbuka yang dijeda).
         Button btnPreview = new Button(this);
         btnPreview.setText("🔍 Preview");
         btnPreview.setAllCaps(false);
         btnPreview.setOnClickListener((v) -> {
            dialog.dismiss();
            this.showOtherDevicePreview(qF);
         });
         this.addGridAction(actionsGrid, btnPreview);
         if (!s.customerUuid.isEmpty()) {
            Button btnCustomer = new Button(this);
            btnCustomer.setText("👤 Detail Pelanggan");
            btnCustomer.setAllCaps(false);
            btnCustomer.setOnClickListener((v) -> {
               dialog.dismiss();
               this.openOtherDeviceCustomerDetail(qF);
            });
            this.addGridAction(actionsGrid, btnCustomer);
         }
      }
      if (s.chatSession && !s.uuid.isEmpty()) {
         Button btnChat = new Button(this);
         btnChat.setText("💬 Chat Pesanan");
         btnChat.setAllCaps(false);
         btnChat.setOnClickListener((v) -> {
            dialog.dismiss();
            this.openOrderChatUuid(s.uuid, s.name);
         });
         this.addGridAction(actionsGrid, btnChat);
      }
      if (actionsGrid.getChildCount() > 0) {
         body.addView(actionsGrid);
      }
      dialog.show();
   }

   /** Jadwal ulang order yang SUDAH tertunda. Jalur server → {@code POST
    *  /api/delivery/tertunda/reschedule} (sama dengan web: tanggal transaksi ikut pindah, pelanggan
    *  diberi tahu WA, alasan &amp; foto lama dipertahankan karena kunci reason sengaja tak dikirim).
    *  Jalur lokal (luring / baris lokal belum terdorong) → hanya delivery_tertunda_resume_at,
    *  terdorong lewat sinkron biasa (syncUpdate). Pilihannya di {@link #tertundaWritePath}. */
   private void showTertundaReschedulePicker(TertundaRows.Merged<Transaction> m, String custName, boolean offline, Runnable onDone) {
      Calendar cal = Calendar.getInstance();
      cal.add(5, 1);
      cal.set(11, 8);
      cal.set(12, 0);
      DatePickerDialog datePicker = new DatePickerDialog(this, (dp, year, month, day) -> {
         cal.set(year, month, day);
         (new TimePickerDialog(this, (tp, hour, minute) -> {
            cal.set(11, hour);
            cal.set(12, minute);
            cal.set(13, 0);
            if (cal.getTimeInMillis() <= System.currentTimeMillis()) {
               Toast.makeText(this, "Jadwal lanjut harus di masa depan", Toast.LENGTH_SHORT).show();
               return;
            }

            SimpleDateFormat trxDbFmtLocal = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
            String resumeAt = trxDbFmtLocal.format(cal.getTime());
            TertundaRows.WritePath path = this.tertundaWritePath(m, offline);
            if (path == TertundaRows.WritePath.SERVER) {
               this.doRescheduleTertundaRemote(m.uuid(), custName, resumeAt, onDone);
            } else if (path == TertundaRows.WritePath.LOCAL) {
               (new TransactionDao(DatabaseHelper.getInstance(this))).rescheduleTertunda(m.local.getId(), resumeAt);
               SyncScheduler.syncNow(this.getApplicationContext());
               Toast.makeText(this, "Jadwal lanjut diubah.", Toast.LENGTH_SHORT).show();
               if (onDone != null) onDone.run();
            } else {
               Toast.makeText(this, "Perangkat belum terhubung ke server.", Toast.LENGTH_SHORT).show();
            }
         }, cal.get(11), cal.get(12), true)).show();
      }, cal.get(1), cal.get(2), cal.get(5));
      datePicker.getDatePicker().setMinDate(System.currentTimeMillis() - 1000L);
      datePicker.show();
   }

   private void doRescheduleTertundaRemote(String trxUuid, String custName, String resumeAt, Runnable onDone) {
      SyncSettings cfg = this.syncCfg();
      Toast.makeText(this, "Menjadwalkan ulang…", Toast.LENGTH_SHORT).show();
      (new Thread(() -> {
         String okMsg = null;
         String errMsg = null;
         JSONObject wa = null;
         int errCode = 0;

         try {
            JSONObject body = new JSONObject();
            body.put("transaction_uuid", trxUuid);
            body.put("resume_at", resumeAt);   // tanpa "reason" → server mempertahankan alasan lama
            JSONObject r = (new SyncApi(cfg)).rescheduleTertunda(body);
            okMsg = r.optString("message", "Jadwal lanjut diubah.");
            wa = r.optJSONObject("wa");
         } catch (SyncApi.SyncException se) {
            errCode = se.code;
            errMsg = se.serverMessage();
            if (errMsg == null) {
               errMsg = "Gagal menjadwalkan ulang (kode " + se.code + ").";
            }
         } catch (Exception e) {
            errMsg = "Gagal menjadwalkan ulang — periksa koneksi internet.";
         }

         final String okMsgF = okMsg;
         final String errMsgF = errMsg;
         final JSONObject waF = wa;
         final boolean staleF = TertundaRows.isStaleCode(errCode);
         this.runOnUiThread(() -> {
            if (this.isFinishing() || this.isDestroyed()) return;
            if (okMsgF != null) {
               // Masih TERTUNDA (jadwal baru) — tapi salinan lokal kini basi: jangan lagi lewat DAO.
               this.markTertundaServerTouched(trxUuid, false);
               Toast.makeText(this, okMsgF, 1).show();
               SyncScheduler.syncNow(this.getApplicationContext());
               this.afterPostponeWa(custName, trxUuid, waF, null);
               if (onDone != null) onDone.run();
            } else {
               Toast.makeText(this, errMsgF, 1).show();
               if (staleF) {
                  // Sudah tak tertunda / tak ada lagi di server (dilanjutkan di tempat lain).
                  this.markTertundaServerTouched(trxUuid, true);
                  this.afterTertundaServerChange();
                  if (onDone != null) onDone.run();
               }
            }
         });
      })).start();
   }

   /** "▶ Lanjutkan" — kembalikan order TERTUNDA ke antrian aktif SEKARANG, di luar jadwal lanjut
    *  otomatisnya. Jalur server → {@code POST /api/delivery/tertunda/resume} (efek samping sama dengan
    *  web; tujuan dipilih server — bila perangkat pemegangnya, termasuk HP ini sendiri, belum ada staf
    *  absen, order dibuka sebagai Pesanan Terbuka supaya tak terdampar). Jalur lokal (luring / baris
    *  lokal belum terdorong) → delivery_status → PENDING, terdorong via sinkron biasa. */
   private void confirmResumeTertunda(TertundaRows.Merged<Transaction> m, String custName, boolean offline, Runnable onDone) {
      final TertundaRows.WritePath path = this.tertundaWritePath(m, offline);
      if (path == TertundaRows.WritePath.NONE) {
         Toast.makeText(this, "Perangkat belum terhubung ke server.", Toast.LENGTH_SHORT).show();
         return;
      }
      final boolean viaServer = path == TertundaRows.WritePath.SERVER;
      StringBuilder msg = new StringBuilder();
      msg.append("Order \"").append(custName).append("\" akan langsung kembali ke antrian aktif sekarang.");
      if (viaServer) {
         // Server merutekan SEMUA baris, termasuk milik HP ini — jangan menjanjikan tujuan yang belum pasti.
         msg.append("\n\n").append(TertundaRows.resumeDestinationHint(m.server));
         if (this.canClaimTertunda(m.server)) {
            msg.append("\n\nUntuk mengantarnya sendiri pakai \"📥 Lanjutkan & Ambil\".");
         }
      } else {
         msg.append("\n\nLuring — perubahan dikirim ke server saat sinkron berikutnya.");
      }
      msg.append("\n\nKetuk \"Lanjutkan\" dua kali untuk memastikan.");
      this.confirmTertundaTwoTap("▶ Lanjutkan Pesanan Ini?", msg.toString(), "Lanjutkan", "Melanjutkan…", (dialog, pos, neg) -> {
         if (viaServer) {
            final String trxUuid = m.uuid();
            this.runTertundaResume(dialog, pos, neg, trxUuid, onDone);
         } else {
            (new TransactionDao(DatabaseHelper.getInstance(this))).resumeTertunda(m.local.getId());
            SyncScheduler.syncNow(this.getApplicationContext());
            this.loadData();
            Toast.makeText(this, "Order dikembalikan ke antrian aktif.", Toast.LENGTH_SHORT).show();
            dialog.dismiss();
            if (onDone != null) onDone.run();
         }
      });
   }

   /** "📥 Lanjutkan &amp; Ambil" — lanjutkan SEKARANG lalu pindahkan ke antrean perangkat INI, dalam DUA
    *  langkah: {@code POST /api/delivery/tertunda/resume} (semantik &amp; efek samping web: struk email
    *  yang ditahan, agregat pelanggan, notifikasi, perutean bila pemegang kosong) LALU klaim biasa
    *  {@code POST /api/delivery/claim} atas order yang kini PENDING. BUKAN claim resume=true sekali jalan
    *  (DeliveryMapActivity.doClaim): jalur server itu melewatkan semua efek samping tadi — struk email
    *  order itu (atau gabungan checkout bila ini leg terakhir) tak pernah terkirim — dan bila klaimnya
    *  gagal, order tertinggal dilanjutkan TANPA perutean. */
   private void confirmResumeClaimTertunda(TertundaRows.Row s, Runnable onDone) {
      String owner = s.openDispatch ? "🎲 Pesanan Terbuka" : s.mine ? "HP ini"
            : (s.deviceGroupLabel.isEmpty() ? "perangkat lain" : "\"" + s.deviceGroupLabel + "\"");
      StringBuilder msg = new StringBuilder();
      msg.append("Order \"").append(s.name).append("\" sedang DITUNDA (pemegang: ").append(owner)
            .append("). Lanjutkan sekarang lalu pindahkan ke antrian perangkat ini.\n\n");
      if (!s.items.isEmpty()) {
         msg.append("Penjualan: ").append(s.items).append('\n');
      }
      msg.append("Total: Rp ").append(formatRupiah(s.total)).append("\n\n");
      msg.append("Ketuk \"Lanjutkan & Ambil\" dua kali untuk memastikan.");
      this.confirmTertundaTwoTap("📥 Lanjutkan & Ambil?", msg.toString(), "Lanjutkan & Ambil", "Memindahkan…",
            (dialog, pos, neg) -> this.runTertundaResumeClaim(dialog, pos, neg, s.uuid, onDone));
   }

   /** Aksi yang dijalankan setelah konfirmasi dua ketuk; wajib menutup dialog ATAU mengaktifkan
    *  ulang tombolnya. */
   private interface TertundaConfirmed {
      void run(AlertDialog dialog, Button pos, Button neg);
   }

   /** Konfirmasi dua ketuk (pola Ambil Alih / DeliveryMapActivity.confirmClaim). */
   private void confirmTertundaTwoTap(String title, String msg, String posLabel, String busyLabel, TertundaConfirmed action) {
      AlertDialog dialog = (new AlertDialog.Builder(this)).setIcon(17301543).setTitle(title).setCancelable(false).setMessage(msg).setPositiveButton(posLabel, (DialogInterface.OnClickListener)null).setNegativeButton("Batal", (DialogInterface.OnClickListener)null).create();
      dialog.setOnShowListener((d) -> {
         Button pos = dialog.getButton(-1);
         int[] clicks = new int[]{0};
         pos.setOnClickListener((v) -> {
            if (++clicks[0] < 2) {
               pos.setText("Ketuk sekali lagi");
            } else {
               pos.setEnabled(false);
               pos.setText(busyLabel);
               dialog.setCancelable(false);
               Button neg = dialog.getButton(-2);
               if (neg != null) {
                  neg.setEnabled(false);
               }

               action.run(dialog, pos, neg);
            }
         });
      });
      dialog.show();
   }

   /** Kembalikan tombol dialog konfirmasi supaya bisa dicoba lagi (galat yang layak diulang). */
   private static void rearmTertundaConfirm(AlertDialog dialog, Button pos, Button neg, String posLabel) {
      pos.setEnabled(true);
      pos.setText(posLabel);
      dialog.setCancelable(true);
      if (neg != null) {
         neg.setEnabled(true);
      }
   }

   /** "▶ Lanjutkan" lewat server. 2xx / 404 / 422 → order sudah tak tertunda (dilanjutkan di sini atau
    *  di tempat lain): tutup, catat, segarkan. Galat lain → bisa dicoba lagi. */
   private void runTertundaResume(AlertDialog dialog, Button pos, Button neg, String trxUuid, Runnable onDone) {
      SyncSettings cfg = this.syncCfg();
      (new Thread(() -> {
         String okMsg = null;
         String errMsg = null;
         int errCode = 0;

         try {
            JSONObject body = new JSONObject();
            body.put("transaction_uuid", trxUuid);
            String m = TertundaRows.parseResume((new SyncApi(cfg)).resumeTertunda(body).toString()).message;
            okMsg = m.isEmpty() ? "Order dikembalikan ke antrian aktif." : m;
         } catch (SyncApi.SyncException se) {
            errCode = se.code;
            errMsg = se.serverMessage();
            if (errMsg == null) {
               errMsg = "Gagal melanjutkan (kode " + se.code + ").";
            }
         } catch (Exception e) {
            errMsg = "Gagal melanjutkan — periksa koneksi internet.";
         }

         final String okMsgF = okMsg;
         final String errMsgF = errMsg;
         final boolean staleF = TertundaRows.isStaleCode(errCode);
         this.runOnUiThread(() -> {
            if (this.isFinishing() || this.isDestroyed()) return;
            if (okMsgF != null || staleF) {
               Toast.makeText(this, okMsgF != null ? okMsgF : errMsgF, 1).show();
               dialog.dismiss();
               this.markTertundaServerTouched(trxUuid, true);
               this.afterTertundaServerChange();
               if (onDone != null) onDone.run();
            } else {
               Toast.makeText(this, errMsgF, 1).show();
               rearmTertundaConfirm(dialog, pos, neg, "Lanjutkan");
            }
         });
      })).start();
   }

   /** "📥 Lanjutkan &amp; Ambil" dua langkah (lihat {@link #confirmResumeClaimTertunda}). Langkah 2 hanya
    *  setelah langkah 1 berhasil; expected_device_uuid = tujuan yang DILAPORKAN resume
    *  (TertundaRows.claimExpectedAfterResume — dilewati bila order sudah kembali ke antrean HP ini).
    *  Klaim gagal → order TETAP sudah dilanjutkan (dengan perutean server); dilaporkan apa adanya,
    *  tanpa coba-ulang (resume kedua pasti 422). */
   private void runTertundaResumeClaim(AlertDialog dialog, Button pos, Button neg, String trxUuid, Runnable onDone) {
      SyncSettings cfg = this.syncCfg();
      (new Thread(() -> {
         SyncApi api = new SyncApi(cfg);
         TertundaRows.ResumeOutcome resumed = null;
         String resumeErr = null;
         int resumeCode = 0;
         try {
            JSONObject body = new JSONObject();
            body.put("transaction_uuid", trxUuid);
            resumed = TertundaRows.parseResume(api.resumeTertunda(body).toString());
         } catch (SyncApi.SyncException se) {
            resumeCode = se.code;
            resumeErr = se.serverMessage();
            if (resumeErr == null) {
               resumeErr = "Gagal melanjutkan (kode " + se.code + ").";
            }
         } catch (Exception e) {
            resumeErr = "Gagal melanjutkan — periksa koneksi internet.";
         }

         boolean claimed = false;
         boolean alreadyHere = false;
         String claimErr = null;
         if (resumed != null) {
            String expected = TertundaRows.claimExpectedAfterResume(resumed, cfg.getDeviceUuid());
            if (expected == null) {
               alreadyHere = true;
            } else {
               try {
                  JSONObject body = new JSONObject();
                  body.put("transaction_uuid", trxUuid);
                  body.put("expected_device_uuid", expected);
                  api.claimDelivery(body);
                  claimed = true;
               } catch (SyncApi.SyncException se) {
                  claimErr = se.serverMessage();
                  if (claimErr == null) {
                     claimErr = "kode " + se.code;
                  }
               } catch (Exception e) {
                  claimErr = "periksa koneksi internet";
               }
            }
         }

         final TertundaRows.ResumeOutcome resumedF = resumed;
         final String resumeErrF = resumeErr;
         final boolean resumeStaleF = TertundaRows.isStaleCode(resumeCode);
         final boolean claimedF = claimed;
         final boolean alreadyHereF = alreadyHere;
         final String claimErrF = claimErr;
         this.runOnUiThread(() -> {
            if (this.isFinishing() || this.isDestroyed()) return;
            if (resumedF == null) {
               Toast.makeText(this, resumeErrF, 1).show();
               if (resumeStaleF) {
                  // Sudah tak tertunda / tak ada (dilanjutkan di tempat lain) — tutup & segarkan.
                  dialog.dismiss();
                  this.markTertundaServerTouched(trxUuid, true);
                  this.afterTertundaServerChange();
                  if (onDone != null) onDone.run();
               } else {
                  rearmTertundaConfirm(dialog, pos, neg, "Lanjutkan & Ambil");
               }
               return;
            }
            String msg;
            if (claimedF) {
               msg = "Order dilanjutkan & diambil ke perangkat ini.";
            } else if (alreadyHereF) {
               msg = "Order dilanjutkan — sudah di antrian perangkat ini.";
            } else {
               msg = (resumedF.message.isEmpty() ? "Order sudah dilanjutkan." : resumedF.message)
                     + "\nTetapi gagal diambil ke perangkat ini: " + claimErrF;
            }
            Toast.makeText(this, msg, 1).show();
            dialog.dismiss();
            this.markTertundaServerTouched(trxUuid, true);
            this.afterTertundaServerChange();
            if (onDone != null) onDone.run();
         });
      })).start();
   }

   private void showUbahMenu(Transaction t) {
      List<String> labels = new ArrayList();
      List<Runnable> actions = new ArrayList();
      if ("JUAL".equals(t.getType())) {
         labels.add("✏️ Ubah Pesanan (persetujuan)");
         actions.add((Runnable)() -> DeliveryEditDialog.show(this, t));
         labels.add("\ud83d\udcb3 Ubah Metode Pembayaran");
         actions.add((Runnable)() -> this.showEditPaymentMethod(t));
         // Hanya tampil bila pelanggan punya >1 lokasi tersimpan — pelanggan satu-lokasi tak ada
         // yang bisa dipilih (cermin gerbang kartu "Kirim ke" di Transaksi Baru).
         Customer custForDest = t.getCustomerId() > 0L ? this.customerDao.getById(t.getCustomerId()) : null;
         if (custForDest != null && custForDest.getLocations() != null && custForDest.getLocations().size() > 1) {
            labels.add("\ud83d\udccd Ubah Lokasi Pengiriman (persetujuan)");
            actions.add((Runnable)() -> DeliveryDestDialog.show(this, t, custForDest));
         }
      }

      labels.add("\ud83d\uddd1️ Void Pesanan Ini (persetujuan)");
      actions.add((Runnable)() -> DeliveryVoidDialog.show(this, t));
      (new AlertDialog.Builder(this)).setTitle("Ubah — " + safe(t.getCustomerName())).setItems((CharSequence[])labels.toArray(new String[0]), (d, which) -> ((Runnable)actions.get(which)).run()).setNegativeButton("Batal", (DialogInterface.OnClickListener)null).show();
   }

   private void showAlihkanMenu(Transaction t) {
      String[] labels = new String[]{"\ud83d\udce4 Kirim ke Perangkat Lain", "\ud83d\udd13 Lepas (Pesanan Terbuka)", "\ud83d\udd52 Jadwalkan Ulang"};
      (new AlertDialog.Builder(this)).setTitle("Alihkan — " + safe(t.getCustomerName())).setItems(labels, (d, which) -> {
         if (which == 0) {
            this.showRouteDevicePicker(t);
         } else if (which == 1) {
            this.confirmOpenDispatch(t);
         } else {
            this.showPostponeSchedulePicker(t);
         }

      }).setNegativeButton("Batal", (DialogInterface.OnClickListener)null).show();
   }

   private static final String[] RESCHEDULE_REASON_PRESETS = new String[]{"Pesanan overload", "Cuaca buruk", "Kecelakaan", "Kendaraan bermasalah", "Lainnya (jelaskan)"};
   private static final int REQ_POSTPONE_PHOTO = 7406;
   private static final int REQ_POSTPONE_CAMERA_PERM = 7407;

   /** Alasan "Kendaraan bermasalah" WAJIB berfoto — bukti untuk pelanggan (ikut WA) & dashboard. */
   static boolean needsVehiclePhoto(String reason) {
      return reason != null && reason.toLowerCase(Locale.ROOT).contains("kendaraan bermasalah");
   }

   /**
    * Baris "📷 Ambil Foto Kendaraan" di dialog Jadwalkan Ulang — muncul hanya saat alasannya
    * "Kendaraan bermasalah" (diketik atau lewat tombol preset). Foto dikompres ~1280px lalu dikirim
    * bersama permintaan tunda; server melampirkannya ke WA pemberitahuan pelanggan.
    */
   private View buildPostponePhotoRow(EditText reason) {
      this.postponePhotoPath = null;
      LinearLayout row = new LinearLayout(this);
      row.setOrientation(LinearLayout.VERTICAL);
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
      lp.topMargin = this.dp(8.0F);
      row.setLayoutParams(lp);
      row.setVisibility(View.GONE);

      com.google.android.material.button.MaterialButton btn = new com.google.android.material.button.MaterialButton(this);
      btn.setText("📷 Ambil Foto Kendaraan (wajib)");
      btn.setAllCaps(false);
      btn.setOnClickListener((v) -> this.launchPostponeCamera());
      row.addView(btn);

      android.widget.ImageView thumb = new android.widget.ImageView(this);
      LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, this.dp(140.0F));
      tlp.topMargin = this.dp(6.0F);
      thumb.setLayoutParams(tlp);
      thumb.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
      thumb.setVisibility(View.GONE);
      row.addView(thumb);

      TextView status = new TextView(this);
      status.setTextSize(12.0F);
      status.setText("Foto akan dikirim ke pelanggan bersama pemberitahuan jadwal ulang.");
      row.addView(status);

      this.postponePhotoThumb = thumb;
      this.postponePhotoStatus = status;
      reason.addTextChangedListener(new TextWatcher() {
         public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
         public void onTextChanged(CharSequence s, int a, int b, int c) {}
         public void afterTextChanged(Editable s) {
            row.setVisibility(needsVehiclePhoto(s.toString()) ? View.VISIBLE : View.GONE);
         }
      });
      return row;
   }

   private void launchPostponeCamera() {
      if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
         ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, REQ_POSTPONE_CAMERA_PERM);
         return;
      }
      Intent intent = CameraIntents.preferBackCamera(new Intent("android.media.action.IMAGE_CAPTURE"));
      if (intent.resolveActivity(this.getPackageManager()) == null) {
         Toast.makeText(this, "Tidak ada aplikasi kamera.", 0).show();
         return;
      }
      File photoFile;
      try {
         String ts = (new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)).format(new Date());
         photoFile = File.createTempFile("TUNDA_" + ts, ".jpg", this.getExternalFilesDir(Environment.DIRECTORY_PICTURES));
      } catch (IOException e) {
         Toast.makeText(this, "Gagal membuat file foto: " + e.getMessage(), 0).show();
         return;
      }
      this.pendingPostponeCameraPath = photoFile.getAbsolutePath();
      Uri uri = FileProvider.getUriForFile(this, this.getApplicationContext().getPackageName() + ".fileprovider", photoFile);
      intent.putExtra("output", uri);
      this.startActivityForResult(intent, REQ_POSTPONE_PHOTO);
   }

   /** Hasil kamera foto jadwal ulang → kompres, simpan path, tampilkan pratinjau di dialog. */
   private void onPostponePhotoResult(int resultCode) {
      String raw = this.pendingPostponeCameraPath;
      this.pendingPostponeCameraPath = null;
      if (resultCode != -1 || raw == null || !(new File(raw)).exists() || (new File(raw)).length() == 0L) {
         if (raw != null) (new File(raw)).delete();
         return;
      }
      File out = new File(raw.replace(".jpg", "_c.jpg"));
      String path = com.crowja.damiupos.util.BitmapUtils.compressForUpload(raw, out, 1280, 80) ? out.getAbsolutePath() : raw;
      if (!path.equals(raw)) (new File(raw)).delete();
      this.postponePhotoPath = path;
      if (this.postponePhotoThumb != null) {
         this.postponePhotoThumb.setImageBitmap(com.crowja.damiupos.util.BitmapUtils.decodeSampled(path, 600, 600));
         this.postponePhotoThumb.setVisibility(View.VISIBLE);
      }
      if (this.postponePhotoStatus != null) {
         this.postponePhotoStatus.setText("✓ Foto terlampir — ketuk tombol di atas untuk mengulang.");
      }
   }

   /** Lampirkan foto (JPEG) ke body tunda sebagai base64. Gagal baca → tunda tetap jalan tanpa foto. */
   private static void putPostponePhoto(JSONObject body, String photoPath) {
      if (photoPath == null) return;
      try {
         byte[] bytes = java.nio.file.Files.readAllBytes(new File(photoPath).toPath());
         body.put("photo_base64", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP));
      } catch (Exception ignored) {
      }
   }

   /**
    * Tindak lanjut WA setelah tunda berhasil. Server mencoba mengirim lewat FREZ WA Bridge dan
    * melaporkan hasilnya ({@code wa.status}). Bila pelanggan TIDAK sampai diberi tahu (Bridge menolak,
    * belum tersambung, jeda antar-pesan, atau auto-kirim nonaktif) → HP mencoba sekali lagi lewat
    * WA Bridge ({@link com.crowja.damiupos.wa.WaBridgeSend}, urutan prioritas akun umum), dan baru
    * bila itu pun gagal HP mengirim sendiri: WhatsApp
    * dibuka langsung ke chat pelanggan dengan teks yang SAMA (+ foto bila ada). Pola sama dengan WA
    * Cash Bon — pengiriman foto tetap ditekan kurir (klik-otomatis di pratinjau media tak aman).
    * {@code wa == null} = server lama, tak ada tindak lanjut (perilaku sebelumnya).
    */
   private void afterPostponeWa(String custName, String trxUuid, JSONObject wa, String photoPath) {
      if (wa == null) return;
      String status = wa.optString("status", "");
      if ("sent".equals(status) || "queued".equals(status)) {
         Toast.makeText(this, "📨 Pelanggan diberi tahu lewat WA.", 0).show();
         return;
      }
      String text = strJson(wa, "text");
      String phone = strJson(wa, "phone");
      if (text.isEmpty() || !WaShare.hasUsablePhone(phone)) return;   // no_phone / tanpa pelanggan
      String why = "rejected".equals(status) ? "Bridge WA menolak pesan ini"
            : ("deferred".equals(status) ? "Bridge WA sedang jeda antar pesan"
            : ("disabled".equals(status) ? "Kirim otomatis WA jadwal ulang nonaktif"
            : "Bridge WA belum tersambung"));
      // Coba lagi lewat WA Bridge dengan urutan prioritas akun umum (2 arah → akun perangkat →
      // akun pengirim cabang → cadangan) sebelum jatuh ke WhatsApp HP ini.
      com.crowja.damiupos.wa.WaBridgeSend.sendOrFallback(this,
            com.crowja.damiupos.wa.WaBridgeSend.Msg.to(phone, text).type("reschedule").proactive()
                  .transactionUuid(trxUuid).media(photoPath, "image/jpeg", "jadwal-ulang.jpg"),
            null,
            () -> {
               Toast.makeText(this, why + " — kirim pemberitahuan dari WhatsApp HP ini.", 1).show();
               WaShare.sendPhotoWithCaption(this, custName, phone, photoPath, text);
            });
   }

   private void showPostponeSchedulePicker(Transaction t) {
      Calendar cal = Calendar.getInstance();
      cal.add(5, 1);
      cal.set(11, 8);
      cal.set(12, 0);
      DatePickerDialog datePicker = new DatePickerDialog(this, (dp, year, month, day) -> {
         cal.set(year, month, day);
         (new TimePickerDialog(this, (tp, hour, minute) -> {
            cal.set(11, hour);
            cal.set(12, minute);
            cal.set(13, 0);
            this.confirmPostpone(t, cal);
         }, cal.get(11), cal.get(12), true)).show();
      }, cal.get(1), cal.get(2), cal.get(5));
      datePicker.getDatePicker().setMinDate(System.currentTimeMillis() - 1000L);
      datePicker.show();
   }

   private void confirmPostpone(Transaction t, Calendar resume) {
      SimpleDateFormat fmt = new SimpleDateFormat("EEEE, d MMM yyyy HH:mm", new Locale("id", "ID"));
      String resumeLabel = fmt.format(resume.getTime());

      LinearLayout root = new LinearLayout(this);
      root.setOrientation(1);
      int pad = this.dp(16.0F);
      root.setPadding(pad, this.dp(8.0F), pad, this.dp(4.0F));

      TextView hint = new TextView(this);
      hint.setText("Order \"" + safe(t.getCustomerName()) + "\" akan keluar dari antrian aktif dan kembali otomatis pada:\n\n" + resumeLabel + "\n\nTanggal transaksinya ikut pindah ke jadwal ini.");
      hint.setTextSize(13.0F);
      root.addView(hint);

      EditText reason = new EditText(this);
      reason.setHint("Alasan menjadwalkan ulang (opsional)…");
      reason.setInputType(147457);
      reason.setMinLines(2);
      LinearLayout.LayoutParams reasonLp = new LinearLayout.LayoutParams(-1, -2);
      reasonLp.topMargin = this.dp(10.0F);
      reason.setLayoutParams(reasonLp);
      root.addView(reason);
      root.addView(DeliveryVoidDialog.buildQuickReasonRow(this, reason, RESCHEDULE_REASON_PRESETS));
      root.addView(this.buildPostponePhotoRow(reason));

      TextView confirmHint = new TextView(this);
      confirmHint.setText("Ketuk \"Jadwalkan\" dua kali untuk memastikan.");
      confirmHint.setTextSize(12.0F);
      LinearLayout.LayoutParams confirmLp = new LinearLayout.LayoutParams(-1, -2);
      confirmLp.topMargin = this.dp(10.0F);
      confirmHint.setLayoutParams(confirmLp);
      root.addView(confirmHint);

      ScrollView scroll = new ScrollView(this);
      scroll.addView(root);

      AlertDialog dialog = (new AlertDialog.Builder(this)).setIcon(17301543).setTitle("Jadwalkan Ulang Order Ini?").setCancelable(false).setView(scroll).setPositiveButton("Jadwalkan", (DialogInterface.OnClickListener)null).setNegativeButton("Batal", (DialogInterface.OnClickListener)null).create();
      dialog.setOnShowListener((d) -> {
         Button pos = dialog.getButton(-1);
         int[] clicks = new int[]{0};
         pos.setOnClickListener((v) -> {
            if (++clicks[0] < 2) {
               pos.setText("Ketuk sekali lagi");
            } else if (needsVehiclePhoto(reason.getText().toString()) && this.postponePhotoPath == null) {
               clicks[0] = 1;
               Toast.makeText(this, "Foto kendaraan wajib untuk alasan \"Kendaraan bermasalah\" — ketuk 📷 Ambil Foto.", 1).show();
            } else {
               pos.setEnabled(false);
               pos.setText("Menjadwalkan…");
               dialog.setCancelable(false);
               Button neg = dialog.getButton(-2);
               if (neg != null) {
                  neg.setEnabled(false);
               }

               this.doPostpone(dialog, pos, neg, t, resume, reason.getText().toString().trim(), this.postponePhotoPath);
            }
         });
      });
      dialog.show();
   }

   private void doPostpone(AlertDialog dialog, Button pos, Button neg, Transaction t, Calendar resume, String reason, String photoPath) {
      SyncSettings cfg = this.syncCfg();
      String trxUuid = (new TransactionDao(DatabaseHelper.getInstance(this))).getSyncUuidById(t.getId());
      if (trxUuid != null && !trxUuid.isEmpty()) {
         SimpleDateFormat isoFmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
         String resumeIso = isoFmt.format(resume.getTime());
         (new Thread(() -> {
            String okMsg = null;
            String errMsg = null;
            JSONObject wa = null;

            try {
               JSONObject body = new JSONObject();
               body.put("transaction_uuid", trxUuid);
               body.put("resume_at", resumeIso);
               if (reason != null && !reason.isEmpty()) {
                  body.put("reason", reason);
               }
               putPostponePhoto(body, photoPath);
               JSONObject r = (new SyncApi(cfg)).postponeDelivery(body);
               okMsg = r.optString("message", "Order ditunda.");
               wa = r.optJSONObject("wa");
            } catch (SyncApi.SyncException se) {
               errMsg = extractRouteErrorMessage(se.body);
               if (errMsg == null) {
                  errMsg = "Gagal menjadwalkan ulang (kode " + se.code + ").";
               }
            } catch (Exception var12) {
               errMsg = "Gagal menjadwalkan ulang — periksa koneksi internet.";
            }

            final String okMsgF = okMsg;
            final String errMsgF = errMsg;
            final JSONObject waF = wa;
            this.runOnUiThread(() -> {
               if (!this.isFinishing() && !this.isDestroyed()) {
                  if (okMsgF != null) {
                     Toast.makeText(this, okMsgF, 1).show();
                     dialog.dismiss();
                     SyncScheduler.syncNow(this.getApplicationContext());
                     this.afterPostponeWa(safe(t.getCustomerName()), trxUuid, waF, photoPath);
                     this.loadData();
                  } else {
                     Toast.makeText(this, errMsgF, 1).show();
                     pos.setEnabled(true);
                     pos.setText("Jadwalkan");
                     if (neg != null) {
                        neg.setEnabled(true);
                     }

                     dialog.setCancelable(true);
                  }

               }
            });
         })).start();
      } else {
         Toast.makeText(this, "Order ini belum punya identitas server. Coba muat ulang.", 1).show();
         dialog.dismiss();
      }
   }

   private void showPostponeSchedulePickerOther(JSONObject q) {
      Calendar cal = Calendar.getInstance();
      cal.add(5, 1);
      cal.set(11, 8);
      cal.set(12, 0);
      DatePickerDialog datePicker = new DatePickerDialog(this, (dp, year, month, day) -> {
         cal.set(year, month, day);
         (new TimePickerDialog(this, (tp, hour, minute) -> {
            cal.set(11, hour);
            cal.set(12, minute);
            cal.set(13, 0);
            this.confirmPostponeOther(q, cal);
         }, cal.get(11), cal.get(12), true)).show();
      }, cal.get(1), cal.get(2), cal.get(5));
      datePicker.getDatePicker().setMinDate(System.currentTimeMillis() - 1000L);
      datePicker.show();
   }

   private void confirmPostponeOther(JSONObject q, Calendar resume) {
      SimpleDateFormat fmt = new SimpleDateFormat("EEEE, d MMM yyyy HH:mm", new Locale("id", "ID"));
      String resumeLabel = fmt.format(resume.getTime());
      String custName = safe(strJson(q, "name"));

      LinearLayout root = new LinearLayout(this);
      root.setOrientation(1);
      int pad = this.dp(16.0F);
      root.setPadding(pad, this.dp(8.0F), pad, this.dp(4.0F));

      TextView hint = new TextView(this);
      hint.setText("Order \"" + custName + "\" (antrian perangkat lain) akan keluar dari antrian aktif dan kembali otomatis pada:\n\n" + resumeLabel + "\n\nTanggal transaksinya ikut pindah ke jadwal ini.");
      hint.setTextSize(13.0F);
      root.addView(hint);

      EditText reason = new EditText(this);
      reason.setHint("Alasan menjadwalkan ulang (opsional)…");
      reason.setInputType(147457);
      reason.setMinLines(2);
      LinearLayout.LayoutParams reasonLp = new LinearLayout.LayoutParams(-1, -2);
      reasonLp.topMargin = this.dp(10.0F);
      reason.setLayoutParams(reasonLp);
      root.addView(reason);
      root.addView(DeliveryVoidDialog.buildQuickReasonRow(this, reason, RESCHEDULE_REASON_PRESETS));
      root.addView(this.buildPostponePhotoRow(reason));

      TextView confirmHint = new TextView(this);
      confirmHint.setText("Ketuk \"Jadwalkan\" dua kali untuk memastikan.");
      confirmHint.setTextSize(12.0F);
      LinearLayout.LayoutParams confirmLp = new LinearLayout.LayoutParams(-1, -2);
      confirmLp.topMargin = this.dp(10.0F);
      confirmHint.setLayoutParams(confirmLp);
      root.addView(confirmHint);

      ScrollView scroll = new ScrollView(this);
      scroll.addView(root);

      AlertDialog dialog = (new AlertDialog.Builder(this)).setIcon(17301543).setTitle("Jadwalkan Ulang Order Ini?").setCancelable(false).setView(scroll).setPositiveButton("Jadwalkan", (DialogInterface.OnClickListener)null).setNegativeButton("Batal", (DialogInterface.OnClickListener)null).create();
      dialog.setOnShowListener((d) -> {
         Button pos = dialog.getButton(-1);
         int[] clicks = new int[]{0};
         pos.setOnClickListener((v) -> {
            if (++clicks[0] < 2) {
               pos.setText("Ketuk sekali lagi");
            } else if (needsVehiclePhoto(reason.getText().toString()) && this.postponePhotoPath == null) {
               clicks[0] = 1;
               Toast.makeText(this, "Foto kendaraan wajib untuk alasan \"Kendaraan bermasalah\" — ketuk 📷 Ambil Foto.", 1).show();
            } else {
               pos.setEnabled(false);
               pos.setText("Menjadwalkan…");
               dialog.setCancelable(false);
               Button neg = dialog.getButton(-2);
               if (neg != null) {
                  neg.setEnabled(false);
               }

               this.doPostponeOther(dialog, pos, neg, q, resume, reason.getText().toString().trim(), this.postponePhotoPath);
            }
         });
      });
      dialog.show();
   }

   private void doPostponeOther(AlertDialog dialog, Button pos, Button neg, JSONObject q, Calendar resume, String reason, String photoPath) {
      String trxUuid = strJson(q, "uuid");
      if (trxUuid.isEmpty()) {
         Toast.makeText(this, "Order ini belum punya identitas server. Coba muat ulang.", 1).show();
         dialog.dismiss();
      } else {
         SyncSettings cfg = this.syncCfg();
         SimpleDateFormat isoFmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
         String resumeIso = isoFmt.format(resume.getTime());
         (new Thread(() -> {
            String okMsg = null;
            String errMsg = null;
            JSONObject wa = null;

            try {
               JSONObject body = new JSONObject();
               body.put("transaction_uuid", trxUuid);
               body.put("resume_at", resumeIso);
               if (reason != null && !reason.isEmpty()) {
                  body.put("reason", reason);
               }
               putPostponePhoto(body, photoPath);
               JSONObject r = (new SyncApi(cfg)).postponeDelivery(body);
               okMsg = r.optString("message", "Order ditunda.");
               wa = r.optJSONObject("wa");
            } catch (SyncApi.SyncException se) {
               errMsg = extractRouteErrorMessage(se.body);
               if (errMsg == null) {
                  errMsg = "Gagal menjadwalkan ulang (kode " + se.code + ").";
               }
            } catch (Exception var12) {
               errMsg = "Gagal menjadwalkan ulang — periksa koneksi internet.";
            }

            final String okMsgF = okMsg;
            final String errMsgF = errMsg;
            final JSONObject waF = wa;
            this.runOnUiThread(() -> {
               if (!this.isFinishing() && !this.isDestroyed()) {
                  if (okMsgF != null) {
                     Toast.makeText(this, okMsgF, 1).show();
                     dialog.dismiss();
                     SyncScheduler.syncNow(this.getApplicationContext());
                     this.afterPostponeWa(safe(strJson(q, "name")), trxUuid, waF, photoPath);
                     this.loadOtherDevices();
                  } else {
                     Toast.makeText(this, errMsgF, 1).show();
                     pos.setEnabled(true);
                     pos.setText("Jadwalkan");
                     if (neg != null) {
                        neg.setEnabled(true);
                     }

                     dialog.setCancelable(true);
                  }

               }
            });
         })).start();
      }
   }

   private void addGridAction(GridLayout grid, Button btn) {
      int col = grid.getChildCount() % 2;
      int row = grid.getChildCount() / 2;
      GridLayout.LayoutParams lp = new GridLayout.LayoutParams(GridLayout.spec(row, 1), GridLayout.spec(col, 1, 1.0F));
      lp.width = 0;
      lp.height = -2;
      int m = Math.round(2.0F * this.getResources().getDisplayMetrics().density);
      lp.setMargins(m, m, m, m);
      btn.setLayoutParams(lp);
      grid.addView(btn);
   }

   private void showEditPaymentMethod(Transaction t) {
      String[] methods = new String[]{"TUNAI", "QRIS", "TRANSFER"};
      String[] labels = new String[]{"Tunai", "QRIS", "Transfer"};
      int sel = 0;

      for(int i = 0; i < methods.length; ++i) {
         if (methods[i].equals(t.getPaymentMethod())) {
            sel = i;
            break;
         }
      }

      int[] choice = new int[]{sel};
      (new AlertDialog.Builder(this)).setTitle("Ubah Metode Pembayaran").setSingleChoiceItems(labels, sel, (d, w) -> choice[0] = w).setPositiveButton("Simpan", (d, w) -> {
         this.dao.updatePaymentMethod(t.getId(), methods[choice[0]]);
         SyncScheduler.syncNow(this.getApplicationContext());
         Toast.makeText(this, "Metode pembayaran diperbarui", 0).show();
         this.loadData();
      }).setNegativeButton("Batal", (DialogInterface.OnClickListener)null).show();
   }

   private void showRouteDevicePicker(Transaction t) {
      SyncSettings cfg = this.syncCfg();
      if (!cfg.isEnrolled()) {
         Toast.makeText(this, "Perangkat belum terhubung ke server.", 1).show();
      } else {
         String myUuid = cfg.getDeviceUuid();
         List<String[]> others = new ArrayList();

         try {
            JSONArray arr = new JSONArray(cfg.getDeviceRoster());

            for(int i = 0; i < arr.length(); ++i) {
               JSONObject d = arr.optJSONObject(i);
               if (d != null) {
                  String uuid = d.optString("uuid", "");
                  if (!uuid.isEmpty() && !uuid.equals(myUuid)) {
                     others.add(new String[]{uuid, d.optString("name", "Perangkat")});
                  }
               }
            }
         } catch (Exception var9) {
         }

         if (others.isEmpty()) {
            Toast.makeText(this, "Belum ada perangkat delivery lain di roster. Coba sinkron dulu.", 1).show();
         } else {
            String[] names = new String[others.size()];

            for(int i = 0; i < others.size(); ++i) {
               names[i] = ((String[])others.get(i))[1];
            }

            (new AlertDialog.Builder(this)).setTitle("Kirim ke Perangkat Mana?").setItems(names, (dx, which) -> this.confirmRouteDelivery(t, ((String[])others.get(which))[0], ((String[])others.get(which))[1])).setNegativeButton("Batal", (DialogInterface.OnClickListener)null).show();
         }
      }
   }

   private void confirmRouteDelivery(Transaction t, String targetUuid, String targetName) {
      AlertDialog dialog = (new AlertDialog.Builder(this)).setIcon(17301543).setTitle("Kirim Order ke \"" + targetName + "\"?").setCancelable(false).setMessage("Order \"" + safe(t.getCustomerName()) + "\" akan dipindahkan dari antrian perangkat ini ke perangkat \"" + targetName + "\". Kredit galon staf akan mengikuti perangkat tujuan.\n\nKetuk \"Kirim\" dua kali untuk memastikan.").setPositiveButton("Kirim", (DialogInterface.OnClickListener)null).setNegativeButton("Batal", (DialogInterface.OnClickListener)null).create();
      dialog.setOnShowListener((d) -> {
         Button pos = dialog.getButton(-1);
         int[] clicks = new int[]{0};
         pos.setOnClickListener((v) -> {
            if (++clicks[0] < 2) {
               pos.setText("Ketuk sekali lagi");
            } else {
               pos.setEnabled(false);
               pos.setText("Mengirim…");
               dialog.setCancelable(false);
               Button neg = dialog.getButton(-2);
               if (neg != null) {
                  neg.setEnabled(false);
               }

               this.doRouteDelivery(dialog, pos, neg, t, targetUuid, targetName);
            }
         });
      });
      dialog.show();
   }

   private void doRouteDelivery(AlertDialog dialog, Button pos, Button neg, Transaction t, String targetUuid, String targetName) {
      SyncSettings cfg = this.syncCfg();
      String trxUuid = (new TransactionDao(DatabaseHelper.getInstance(this))).getSyncUuidById(t.getId());
      if (trxUuid != null && !trxUuid.isEmpty()) {
         (new Thread(() -> {
            String okMsg = null;
            String errMsg = null;

            try {
               JSONObject body = new JSONObject();
               body.put("transaction_uuid", trxUuid);
               body.put("target_device_uuid", targetUuid);
               JSONObject r = (new SyncApi(cfg)).routeDelivery(body);
               okMsg = r.optString("message", "Order dikirim ke perangkat " + targetName + ".");
            } catch (SyncApi.SyncException se) {
               errMsg = extractRouteErrorMessage(se.body);
               if (errMsg == null) {
                  errMsg = "Gagal mengirim (kode " + se.code + ").";
               }
            } catch (Exception var13) {
               errMsg = "Gagal mengirim — periksa koneksi internet.";
            }

            final String okMsgF = okMsg;
            final String errMsgF = errMsg;
            this.runOnUiThread(() -> {
               if (!this.isFinishing() && !this.isDestroyed()) {
                  if (okMsgF != null) {
                     Toast.makeText(this, okMsgF, 1).show();
                     dialog.dismiss();
                     SyncScheduler.syncNow(this.getApplicationContext());
                     this.loadData();
                  } else {
                     Toast.makeText(this, errMsgF, 1).show();
                     pos.setEnabled(true);
                     pos.setText("Kirim");
                     if (neg != null) {
                        neg.setEnabled(true);
                     }

                     dialog.setCancelable(true);
                  }

               }
            });
         })).start();
      } else {
         Toast.makeText(this, "Order ini belum punya identitas server. Coba muat ulang.", 1).show();
         dialog.dismiss();
      }
   }

   private void confirmOpenDispatch(Transaction t) {
      AlertDialog dialog = (new AlertDialog.Builder(this)).setIcon(17301543).setTitle("Lepas Order Ini?").setCancelable(false).setMessage("Order \"" + safe(t.getCustomerName()) + "\" akan dilepas dari antrian perangkat ini dan menjadi PESANAN TERBUKA — perangkat delivery mana pun di cabang bisa mengklaimnya. Kredit galon staf ikut dilepas, mengikuti perangkat yang nanti mengklaim.\n\nKetuk \"Lepas\" dua kali untuk memastikan.").setPositiveButton("Lepas", (DialogInterface.OnClickListener)null).setNegativeButton("Batal", (DialogInterface.OnClickListener)null).create();
      dialog.setOnShowListener((d) -> {
         Button pos = dialog.getButton(-1);
         int[] clicks = new int[]{0};
         pos.setOnClickListener((v) -> {
            if (++clicks[0] < 2) {
               pos.setText("Ketuk sekali lagi");
            } else {
               pos.setEnabled(false);
               pos.setText("Melepas…");
               dialog.setCancelable(false);
               Button neg = dialog.getButton(-2);
               if (neg != null) {
                  neg.setEnabled(false);
               }

               this.doOpenDispatch(dialog, pos, neg, t);
            }
         });
      });
      dialog.show();
   }

   private void doOpenDispatch(AlertDialog dialog, Button pos, Button neg, Transaction t) {
      SyncSettings cfg = this.syncCfg();
      String trxUuid = (new TransactionDao(DatabaseHelper.getInstance(this))).getSyncUuidById(t.getId());
      if (trxUuid != null && !trxUuid.isEmpty()) {
         (new Thread(() -> {
            String okMsg = null;
            String errMsg = null;

            try {
               JSONObject body = new JSONObject();
               body.put("transaction_uuid", trxUuid);
               JSONObject r = (new SyncApi(cfg)).openDispatch(body);
               okMsg = r.optString("message", "Order dijadikan Pesanan Terbuka.");
            } catch (SyncApi.SyncException se) {
               errMsg = extractRouteErrorMessage(se.body);
               if (errMsg == null) {
                  errMsg = "Gagal melepas (kode " + se.code + ").";
               }
            } catch (Exception var11) {
               errMsg = "Gagal melepas — periksa koneksi internet.";
            }

            final String okMsgF = okMsg;
            final String errMsgF = errMsg;
            this.runOnUiThread(() -> {
               if (!this.isFinishing() && !this.isDestroyed()) {
                  if (okMsgF != null) {
                     Toast.makeText(this, okMsgF, 1).show();
                     dialog.dismiss();
                     SyncScheduler.syncNow(this.getApplicationContext());
                     this.loadData();
                  } else {
                     Toast.makeText(this, errMsgF, 1).show();
                     pos.setEnabled(true);
                     pos.setText("Lepas");
                     if (neg != null) {
                        neg.setEnabled(true);
                     }

                     dialog.setCancelable(true);
                  }

               }
            });
         })).start();
      } else {
         Toast.makeText(this, "Order ini belum punya identitas server. Coba muat ulang.", 1).show();
         dialog.dismiss();
      }
   }

   private boolean currentUserCanRequestTrxChange() {
      long uid = (new SettingsDao(DatabaseHelper.getInstance(this))).getCurrentUserId();
      if (uid <= 0L) {
         return true;
      } else {
         User u = (new UserDao(DatabaseHelper.getInstance(this))).getById(uid);
         return u == null || u.canEditTransactionLimited();
      }
   }

   private static String safe(String s) {
      return s != null && !s.isEmpty() ? s : "Umum";
   }

   private static String strJson(JSONObject o, String key) {
      String v = o.optString(key, "");
      return v != null && !v.equals("null") ? v : "";
   }

   private static String extractRouteErrorMessage(String body) {
      if (body != null && !body.isEmpty()) {
         try {
            return (new JSONObject(body)).optString("message", (String)null);
         } catch (Exception var2) {
            return null;
         }
      } else {
         return null;
      }
   }

   private static String displayNote(String catatan) {
      if (catatan == null) {
         return null;
      } else {
         String c = catatan.trim();
         if (c.isEmpty()) {
            return null;
         } else if (!c.contains("dibuat di Web")) {
            return c;
         } else {
            Matcher m = Pattern.compile("(?:^|\\n)Catatan:\\s*(.+?)\\s*$", 32).matcher(c);
            if (m.find()) {
               String free = m.group(1).replaceAll("\\[[^\\]]*\\]", "").trim();
               return free.isEmpty() ? null : free;
            } else {
               return null;
            }
         }
      }
   }

   private static String formatQueued(String queuedAt) {
      if (queuedAt != null && queuedAt.length() >= 19) {
         // Ts.millis, bukan SDF_PARSE: stempel asal-server berbentuk ISO-UTC ("2026-10-17T07:05:37Z",
         // mis. last_manual_edit_at) — dulu gagal diurai lalu tampil mentah dengan jam UTC.
         long ms = com.crowja.damiupos.util.Ts.millis(queuedAt);
         return ms == Long.MAX_VALUE ? queuedAt.substring(0, 19)
               : (new SimpleDateFormat("dd/MM/yyyy HH:mm:ss", new Locale("id", "ID"))).format(new Date(ms));
      } else {
         return "-";
      }
   }

   private static int dayBucket(String tanggal) {
      if (tanggal != null && !tanggal.trim().isEmpty()) {
         try {
            String s = tanggal.trim();
            boolean utc = s.endsWith("Z");
            String core = utc ? s.substring(0, s.length() - 1) : s;
            int dot = core.indexOf(46);
            if (dot > 0) {
               core = core.substring(0, dot);
            }

            core = core.replace('T', ' ').trim();
            if (core.length() < 10) {
               return 0;
            } else {
               SimpleDateFormat parser = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
               parser.setTimeZone(utc ? TimeZone.getTimeZone("UTC") : TimeZone.getDefault());
               Date d = parser.parse(core.length() >= 19 ? core.substring(0, 19) : core + " 00:00:00");
               if (d == null) {
                  return 0;
               } else {
                  SimpleDateFormat dayFmt = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
                  String orderDay = dayFmt.format(d);
                  String today = dayFmt.format(new Date());
                  return orderDay.compareTo(today) < 0 ? -1 : (orderDay.compareTo(today) > 0 ? 1 : 0);
               }
            }
         } catch (Exception var10) {
            return 0;
         }
      } else {
         return 0;
      }
   }

   private static long elapsedMillis(String queuedAt) {
      // Ts.millis = satu-satunya pengurai yang tahan CAMPURAN bentuk. Kolom delivery_queued_at
      // berisi waktu LOKAL bila HP yang menulisnya, tapi ISO-UTC ("...Z") bila barisnya datang dari
      // server (tab "Perangkat Lain"). SDF_PARSE lama tak menyetel zona waktu, jadi baris asal-server
      // meleset 7 jam dan seluruh kartunya langsung terbaca "sudah tua".
      long t = Ts.millis(queuedAt);
      if (t == Long.MAX_VALUE) {
         return 0L;   // tak terurai -> anggap baru masuk, jangan memicu alarm palsu
      }

      // TIDAK di-clamp ke 0: Pesanan Terjadwal menulis delivery_queued_at di MASA DEPAN (jam buka
      // cabang / jadwal yang diminta pelanggan), jadi selisihnya sengaja NEGATIF — itulah hitung
      // mundurnya. bindElapsedBadge/applyQueueTimerState membaca tanda negatif ini untuk menampilkan
      // badge "terjadwal" (🗓️, biru) alih-alih badge umur antrean biasa.
      return System.currentTimeMillis() - t;
   }

   static String formatDuration(long ms) {
      long s = ms / 1000L;
      long h = s / 3600L;
      long m = s % 3600L / 60L;
      long sec = s % 60L;
      if (h > 0L) {
         return h + " jam " + m + " mnt";
      } else {
         return m > 0L ? m + " mnt " + sec + " dtk" : sec + " dtk";
      }
   }

   private static String formatElapsedBadge(long ms) {
      // Negatif = hitung mundur Pesanan Terjadwal (lihat elapsedMillis) -- nilainya diabsolutkan untuk
      // diformat lalu diberi awalan "-" supaya badge terbaca "-01:30" (masih 1,5 jam lagi), bukan
      // durasi negatif yang membingungkan.
      boolean future = ms < 0L;
      long s = Math.abs(ms) / 1000L;
      long days = s / 86400L;
      String formatted;
      if (days > 0L) {
         long h = s % 86400L / 3600L;
         long m = s % 3600L / 60L;
         formatted = days + " hari " + String.format(Locale.US, "%02d:%02d", h, m);
      } else {
         long h = s / 3600L;
         long m = s % 3600L / 60L;
         long sec = s % 60L;
         formatted = h > 0L ? String.format(Locale.US, "%02d:%02d:%02d", h, m, sec) : String.format(Locale.US, "%02d:%02d", m, sec);
      }
      return future ? "-" + formatted : formatted;
   }

   /**
    * Warnai + kedipkan badge umur. Tiga tingkat: >= 1 jam KUNING, >= 2 jam MERAH, dan >= batas umur
    * cabang (setelan delivery_max_age_minutes, diteruskan lewat {@code lateMs}) MERAH TUA berkedip
    * paling cepat = TERLAMBAT. {@code lateMs <= 0} mematikan tingkat ketiga. Ambang 1 & 2 jam TIDAK
    * ikut jadi setelan -- staf sudah hafal keduanya; ini murni tingkat tambahan di atasnya.
    */
   private static void applyQueueTimerState(TextView tv, long elapsedMs, long lateMs) {
      // level -1 = Pesanan Terjadwal (elapsedMs negatif, lihat elapsedMillis) -- masih hitung mundur
      // ke jam yang diminta, jadi BUKAN "sudah menunggu" dan tak boleh ikut ambang kuning/merah/telat.
      int level = elapsedMs < 0L ? -1
            : (lateMs > 0L && elapsedMs >= lateMs ? 3 : (elapsedMs >= QUEUE_LATE_MS ? 2 : (elapsedMs >= QUEUE_WARN_MS ? 1 : 0)));
      Object prev = tv.getTag(id.tvElapsed);
      boolean changed = !(prev instanceof Integer) || (Integer)prev != level;
      if (changed) {
         tv.setTag(id.tvElapsed, level);
         tv.setBackgroundResource(level == 3 ? drawable.bg_queue_timer_danger : (level == 2 ? drawable.bg_pending_badge : (level == 1 ? drawable.bg_queue_timer_warn : (level == -1 ? drawable.bg_queue_timer_scheduled : drawable.bg_queue_timer_ok))));
      }

      if (level <= 0) {
         tv.clearAnimation();
         tv.setAlpha(1.0F);
      } else if (changed || tv.getAnimation() == null) {
         AlphaAnimation blink = new AlphaAnimation(1.0F, level >= 3 ? 0.15F : (level == 2 ? 0.2F : 0.35F));
         blink.setDuration(level >= 3 ? 250L : (level == 2 ? 350L : 650L));
         blink.setRepeatMode(2);
         blink.setRepeatCount(-1);
         tv.startAnimation(blink);
      }
   }

   /**
    * Satu titik untuk teks + warna badge umur, dipakai KEENAM tempat yang menulis badge (3 adapter x
    * bind + refreshTimers). Prefiks jam-pasir ditempel di sini, bukan di formatter -- pesanan yang
    * sudah melewati batas umur memakai sirene supaya beda di ekor mata.
    */
   private static void bindElapsedBadge(TextView tv, long elapsedMs) {
      boolean late = lateMs > 0L && elapsedMs >= lateMs;
      // \ud83d\uddd3\ufe0f Pesanan Terjadwal (masih hitung mundur, elapsedMs negatif) menang atas \ud83d\udea8/\u23f1 biasa --
      // lihat elapsedMillis & applyQueueTimerState.
      String prefix = elapsedMs < 0L ? "\ud83d\uddd3\ufe0f " : (late ? "\ud83d\udea8 " : "\u23f1 ");
      tv.setText(prefix + formatElapsedBadge(elapsedMs));
      applyQueueTimerState(tv, elapsedMs, lateMs);
   }

   /**
    * Pesanan ini sudah melewati batas umur antrean cabang?
    *
    * <p>Turunan MURNI dari delivery_queued_at + setelan, TIDAK pernah ditulis ke kolom prioritas:
    * SyncController membuang push kolom delivery_priority_* untuk baris yang sudah ada (anti-resurrect),
    * jadi stempel lokal akan tampak jalan lalu lenyap pada pull berikutnya. Umur juga sudah bisa
    * dihitung dari kolom yang ADA -- menyimpannya berarti menduplikasi kebenaran.
    */
   private static boolean isLate(Transaction t) {
      if (t == null || lateMs <= 0L) {
         return false;
      }

      return elapsedMillis(t.getDeliveryQueuedAt()) >= lateMs;
   }

   private Customer.Location resolveOrderLocation(Transaction t) {
      String destName = t.getDeliveryDestName();
      if (t.isResellerDestination()) {
         return this.resolveResellerDestLocation(t);
      }
      if (destName != null && !destName.trim().isEmpty()) {
         Customer c = t.getCustomerId() > 0L ? this.customerDao.getByIdMerged(t.getCustomerId()) : null;
         if (c != null && c.getLocations() != null) {
            for(Customer.Location l : c.getLocations()) {
               if (destName.trim().equalsIgnoreCase(safe(l.name))) {
                  return l;
               }
            }

            return null;
         } else {
            return null;
         }
      } else {
         return null;
      }
   }

   /**
    * Lokasi RESELLER yang dipilih sebagai tujuan ("Reseller: Kediaman" → lokasi "Kediaman" milik
    * reseller order ini). null bila salinan reseller belum ada di HP ini atau namanya tak cocok —
    * preview lalu tampil TANPA foto, bukan jatuh ke foto rumah afiliasi (itu rumah yang salah).
    */
   private Customer.Location resolveResellerDestLocation(Transaction t) {
      String name = Transaction.resellerDestLocationName(t.getDeliveryDestName());
      Customer r = t.getResellerId() > 0L ? this.customerDao.getByIdMerged(t.getResellerId()) : null;
      if (name.isEmpty() || r == null || r.getLocations() == null) {
         return null;
      }
      for (Customer.Location l : r.getLocations()) {
         if (l.name != null && name.equalsIgnoreCase(l.name.trim())) {
            return l;
         }
      }
      return null;
   }

   /**
    * Data satu panel "Preview" gabungan (Peta atas, Kompas, Foto bawah, Detail Transaksi) -
    * dipakai BAIK dari antrean sendiri ({@link #showQueuePreview}) MAUPUN antrean perangkat lain
    * ({@link #showOtherDevicePreview}); dua entry point itu mengambil datanya dari sumber yang
    * beda (Transaction lokal vs JSONObject ringkas dari server) tapi merendernya lewat satu
    * builder yang sama ({@link #showPreviewDialog}) supaya keduanya tak bisa saling menyimpang.
    */
   private static class PreviewData {
      String title;
      // Pin "konteks" di peta — kurir LAIN yang sedang memegang order ini (antrean perangkat lain);
      // KOSONG untuk antrean sendiri, karena posisi kita sendiri sudah ditangani pin LIVE ("Posisi
      // Anda") yang digambar terpisah lewat kompas — dua pin di titik yang sama cuma membingungkan.
      boolean hasDev;
      double devLat, devLng;
      String devName;
      // Uuid perangkat konteks -- diteruskan ke LiveDeviceOverlay.excluding() supaya lapisan pin
      // live TIDAK menggambar ulang kurir yang pin-nya sudah kita gambar sendiri di sini.
      String devUuid;
      String devVehicle, devColor;   // identitas kendaraan kurir itu (App\Support\DeviceIcon)
      // Pin mana yang KEDIP: true = pin konteks (perangkat yang DITUGASKAN); false = posisi saya
      // sendiri (order ini belum bertuan/Pesanan Terbuka). Tak berlaku sama sekali bila !hasDev —
      // lihat buildMiniMapHtml.
      boolean blinkAssigned;
      boolean hasDest;
      double destLat, destLng;
      String destName;
      String address;
      // "Detail Transaksi" ringkas: badge slug+warna (produk, Kembali, metode bayar, TOTAL) - baris
      // panjang "• FREZMIN Air Mineral 19L × 1" lama diringkas jadi "[MIN ×1]" yang sekali lirik
      // sudah kebaca, cermin kapsul produk yang sudah dipakai di kartu antrean (bindProductChips).
      List<Chip> detailChips;
      // Sisanya yang TIDAK cocok jadi badge (hutang/refund/prioritas/lama antre) - teks kecil di
      // bawah baris badge; null/"" -> tak ada baris tambahan.
      String detailExtra;
      String photoLocalPath; // dicoba lebih dulu (sudah ada di disk, tanpa unduh)
      String photoUrl;       // fallback bila photoLocalPath kosong/tak ada filenya
      String note;  // catatan transaksi (tombol Telepon & baris ini HANYA dirender saat guided)
      String phone;
      // guided: true -> buildPreviewContent dirender INLINE penuh layar (renderGuidedInline),
      // bukan sebagai AlertDialog (showPreviewDialog) — lihat showQueuePreview.
      boolean guided;
      long guidedTrxId;
   }

   /** Satu badge "Detail Transaksi" — label siap-tampil + warna latar (teks dihitung kontras). */
   private static class Chip {
      final CharSequence label;
      final int bg;
      Chip(CharSequence label, int bg) { this.label = label; this.bg = bg; }
   }

   /** Lokasi tujuan = lokasi UTAMA pelanggan (entri pertama; foto rumah pelanggan = foto lokasi ini)? */
   private static boolean isPrimaryLocation(Customer c, Customer.Location loc, String name) {
      java.util.List<Customer.Location> ls = c != null ? c.getLocations() : null;
      if (ls == null || ls.isEmpty()) return true;   // tanpa multi-lokasi → hanya ada satu lokasi
      Customer.Location first = ls.get(0);
      if (loc != null) return loc == first || (Math.abs(loc.lat - first.lat) < 1e-6 && Math.abs(loc.lng - first.lng) < 1e-6);
      return name != null && name.trim().equalsIgnoreCase(first.name != null ? first.name.trim() : "");
   }

   private void showQueuePreview(Transaction t) {
      double lat = effectiveLat(t);
      double lng = effectiveLng(t);
      if (lat == (double)0.0F && lng == (double)0.0F) {
         Toast.makeText(this, "Belum ada koordinat untuk order ini.", 0).show();
         return;
      }
      Customer c = this.customerDao.getByIdMerged(t.getCustomerId());
      Customer.Location destLoc = this.resolveOrderLocation(t);
      String adminArea = destAreaFor(c, lat, lng);
      String areaSuffix = !adminArea.isEmpty() ? " (" + adminArea + ")" : "";

      PreviewData d = new PreviewData();
      d.title = safe(t.getCustomerName());
      // Antrean SENDIRI: tak ada kurir "konteks" lain untuk digambar — posisi kita sendiri
      // ditangani pin LIVE (lihat komentar PreviewData.hasDev).
      d.hasDev = false;
      d.hasDest = true;
      d.destLat = lat;
      d.destLng = lng;
      d.destName = d.title;
      // Tujuan RESELLER ("Reseller: Kediaman"): alamat & foto rumah AFILIASI bukan tujuannya — jangan
      // dipakai sebagai cadangan, nanti kurir mencocokkan rumah yang salah dengan pin di peta.
      boolean resellerDest = t.isResellerDestination();
      Customer photoFallback = resellerDest ? null : c;
      // Tujuan lokasi LAIN (bukan utama) tanpa foto sendiri → jangan pakai foto rumah (= foto lokasi utama).
      if (destLoc != null && c != null && !isPrimaryLocation(c, destLoc, destLoc.name)) photoFallback = null;
      d.address = resellerDest ? "🤝 " + Transaction.resellerDestLabel(t.getDeliveryDestName())
            : destLoc != null ? "Kirim Ke: " + safe(destLoc.name) + areaSuffix
            : (t.getCustomerAddress() != null && !t.getCustomerAddress().trim().isEmpty()
                  ? t.getCustomerAddress().trim() + areaSuffix : "");
      d.detailChips = this.buildQueueDetailChips(t);
      String legHint = checkoutCollectHint(t);   // 🧺 leg checkout: tagih hanya lokasi ini
      String extra = (this.refundSummaryText(t) + this.debtSummaryText(t)
            + (legHint.isEmpty() ? "" : "\n" + legHint)).trim();
      d.detailExtra = extra.isEmpty() ? null : extra;
      d.photoLocalPath = destLoc != null && destLoc.photo != null && !destLoc.photo.trim().isEmpty()
            ? null   // foto lokasi tersimpan sbg URL server (lihat photoUrl), bukan path lokal
            : (photoFallback != null ? photoFallback.getPhotoPath() : null);
      d.photoUrl = destLoc != null && destLoc.photo != null && !destLoc.photo.trim().isEmpty()
            ? destLoc.photo.trim() : (photoFallback != null ? photoFallback.getPhotoUrl() : null);
      d.guided = this.guidedMode;
      d.guidedTrxId = t.getId();
      d.note = ReceiptActivity.customerNote(t.getCatatan());
      d.phone = t.getCustomerPhone();
      if (d.guided) {
         this.renderGuidedInline(d);
      } else {
         this.showPreviewDialog(d);
      }
   }

   /**
    * Badge "Detail Transaksi" untuk antrean SENDIRI — produk (slug+warna, {@link #chipLabel}/
    * {@link #chipColor}, sama persis dengan kapsul kartu antrean {@link #bindProductChips}) lalu
    * Kembali, metode bayar, dan Total. Hutang/refund SENGAJA tidak ikut jadi badge (angkanya bisa
    * panjang & butuh konteks kalimat) — tetap teks di {@code PreviewData.detailExtra}.
    */
   private List<Chip> buildQueueDetailChips(Transaction t) {
      List<Chip> chips = new ArrayList<>();
      boolean assumed = galonAssumed(t.getGalonTypeSource());
      for (Object[] r : this.typedRows(t)) {
         chips.add(new Chip(typedChipText(r, assumed), (Integer) r[3]));
      }
      int kembali = this.dao.getReturnedGalonForSale(t.getCustomerId(), t.getTanggal());
      chips.add(new Chip("↩ " + kembali + " gln", 0xFF64748B));
      // Metode bayar DIGABUNG ke dalam badge TOTAL, bukan badge sendiri — "berapa" dan "lewat apa"
      // adalah satu pertanyaan yang sama bagi kurir ("apa yang harus kuterima"), jadi satu badge.
      boolean bon = t.getCatatan() != null && t.getCatatan().contains("[CASH BON]");
      String pay = t.getPaymentMethodLabel();
      String payLabel = bon ? "CASH BON" : (pay != null && !pay.isEmpty() ? pay.toUpperCase(Locale.US) : null);
      int totalBg = bon ? 0xFFDC2626 : 0xFF0369A1;
      chips.add(new Chip("TOTAL " + this.rp(t.getTotalHarga()) + (payLabel != null ? " · " + payLabel : ""), totalBg));
      return chips;
   }

   /** Badge produk gabungan (SEMUA order berjalan, bukan cuma order berikutnya) di header selama
    *  rit berjalan — mode terpandu MAUPUN daftar biasa — plus badge TOTAL rupiah seluruh rit, supaya
    *  kurir langsung tahu apa yang dibawa & berapa yang harus terkumpul. Dipanggil dari
    *  applyRunModeChrome tiap kali daftar berjalan berubah; hilang begitu tak ada rit berjalan. */
   private void updateGuidedProductBadges(List<Transaction> stops) {
      this.fillProductBadges(this.guidedProductBadgesScroll, this.guidedProductBadges, stops);
   }

   /** Isi {@code row} dengan badge slug produk gabungan {@code stops} + badge TOTAL rupiah;
    *  {@code scroll} disembunyikan bila tak ada yang ditampilkan. Dipakai header rit berjalan dan
    *  bar mode-pilih (rincian order terpilih). */
   private void fillProductBadges(View scroll, LinearLayout row, List<Transaction> stops) {
      if (scroll == null || row == null) return;
      if (stops == null || stops.isEmpty()) {
         scroll.setVisibility(View.GONE);
         return;
      }
      java.util.LinkedHashMap<String, Integer> qtyByLabel = new java.util.LinkedHashMap<>();
      java.util.LinkedHashMap<String, Integer> colorByLabel = new java.util.LinkedHashMap<>();
      for (Transaction t : stops) {
         if (t == null) continue;
         // Dipecah per jenis galon ("MIN ×10 [KERAN]" + "MIN ×3 [NON-KERAN]") — yang dimuat berangkat.
         for (Object[] r : this.typedRows(t)) {
            if ((Integer) r[2] <= 0) continue;
            String key = typedKey((String) r[0], (String) r[1]);
            qtyByLabel.merge(key, (Integer) r[2], Integer::sum);
            colorByLabel.putIfAbsent(key, (Integer) r[3]);
         }
      }
      double total = 0;
      for (Transaction t : stops) {
         if (t != null) total += t.getTotalHarga();
      }
      if (qtyByLabel.isEmpty() && total <= 0) {
         scroll.setVisibility(View.GONE);
         return;
      }
      row.removeAllViews();
      for (Map.Entry<String, Integer> e : qtyByLabel.entrySet()) {
         int bg = colorByLabel.getOrDefault(e.getKey(), -10193781);
         row.addView(this.makeDetailBadge(typedTotalText(e.getKey(), e.getValue(), bg), bg));
      }
      // Warna sama dengan badge TOTAL di Preview/detail order (buildQueueDetailChips).
      row.addView(this.makeDetailBadge("TOTAL " + this.rp(total), 0xFF0369A1));
      scroll.setVisibility(View.VISIBLE);
   }

   /** Kartu "Selanjutnya" di bar aksi guided — intip order SETELAH yang sedang di-preview, supaya
    *  kurir tahu ke mana berikutnya tanpa harus menandai Selesai dulu. {@code stop == null} berarti
    *  order ini yang terakhir di rit berjalan. */
   private View buildGuidedNextStopCell(Transaction stop, LinearLayout.LayoutParams lp) {
      LinearLayout cell = new LinearLayout(this);
      cell.setOrientation(LinearLayout.VERTICAL);
      cell.setLayoutParams(lp);
      cell.setPadding(this.dp(8f), this.dp(6f), this.dp(8f), this.dp(6f));
      GradientDrawable bg = new GradientDrawable();
      bg.setShape(GradientDrawable.RECTANGLE);
      bg.setCornerRadius(this.dp(8f));
      bg.setColor(-920071);
      cell.setBackground(bg);
      TextView label = new TextView(this);
      label.setText("Selanjutnya");
      label.setTextSize(10f);
      label.setTypeface(label.getTypeface(), android.graphics.Typeface.BOLD);
      label.setTextColor(-10193781);
      cell.addView(label);
      if (stop == null) {
         TextView tvLast = new TextView(this);
         tvLast.setText("🏁 Order terakhir di rit ini");
         tvLast.setTextSize(11f);
         tvLast.setTextColor(-7035976);
         tvLast.setMaxLines(2);
         tvLast.setPadding(0, this.dp(4f), 0, 0);
         cell.addView(tvLast);
         return cell;
      }
      android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
      hs.setHorizontalScrollBarEnabled(false);
      LinearLayout row = new LinearLayout(this);
      row.setOrientation(LinearLayout.HORIZONTAL);
      row.setPadding(0, this.dp(4f), 0, 0);
      boolean any = false;
      boolean assumed = galonAssumed(stop.getGalonTypeSource());
      for (Object[] r : this.typedRows(stop)) {
         row.addView(this.makeDetailBadge(typedChipText(r, assumed), (Integer) r[3]));
         any = true;
      }
      if (!any) {
         TextView tvName = new TextView(this);
         tvName.setText(safe(stop.getCustomerName()));
         tvName.setTextSize(11f);
         tvName.setMaxLines(1);
         tvName.setEllipsize(TruncateAt.END);
         row.addView(tvName);
      }
      hs.addView(row);
      cell.addView(hs);
      return cell;
   }

   // ---------------------------------------------------------------- Preview: kompas "arah ke tujuan"

   /** 8 arah mata angin dalam Bahasa Indonesia dari bearing absolut (0=Utara, searah jarum jam). */
   private static String cardinalLabel(double bearingDeg) {
      String[] labels = {"Utara", "Timur Laut", "Timur", "Tenggara", "Selatan", "Barat Daya", "Barat", "Barat Laut"};
      int idx = (int) Math.round(((bearingDeg % 360) + 360) % 360 / 45.0) % 8;
      return labels[idx];
   }

   /**
    * Nyalakan panel kompas: GPS live (LocationManager, cermin pola CustomerMapActivity - GPS lalu
    * NETWORK, 2 detik/3 meter) + heading perangkat (SensorManager, ROTATION_VECTOR - sensor fusion
    * yang MEMANFAATKAN giroskop untuk meredam derau, fallback ACCELEROMETER+MAGNETIC_FIELD di
    * perangkat tanpa rotation-vector). Jarum panel dihitung ULANG setiap fix/tick datang lewat
    * {@link #updateCompassUi()} - arah relatif terhadap KE MANA PERANGKAT MENGHADAP SEKARANG, bukan
    * bearing absolut, supaya benar-benar terasa "aktif" seperti diminta (mirip Google Lens/Live
    * View) tanpa perlu kamera menyala (sudah dikonfirmasi ke pengguna - kompas panel, bukan AR kamera).
    *
    * <p>Selalu dipanggil dari {@code dialog.setOnShowListener}, selalu dimatikan lewat
    * {@link #stopCompass()} di {@code setOnDismissListener} - sensor+GPS yang lupa dimatikan adalah
    * kebocoran baterai diam-diam begitu dialog ditutup.</p>
    */
   @SuppressLint("MissingPermission")
   private void startCompass(double destLat, double destLng) {
      this.compassDestLat = destLat;
      this.compassDestLng = destLng;
      this.compassMyLat = Double.NaN;
      this.compassMyLng = Double.NaN;
      this.compassAzimuthDeg = Float.NaN;
      this.updateCompassUi();

      if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
         if (this.tvCompassLabel != null) {
            this.tvCompassLabel.setText("Aktifkan izin lokasi untuk arah & jarak");
         }
         ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQ_COMPASS_LOCATION);
         return;
      }

      if (this.compassLocationManager == null) {
         this.compassLocationManager = (LocationManager) this.getSystemService(Context.LOCATION_SERVICE);
      }
      if (this.compassLocationManager != null && this.compassLocationListener == null) {
         this.compassLocationListener = new LocationListener() {
            public void onLocationChanged(@NonNull Location loc) {
               DeliveryQueueActivity.this.compassMyLat = loc.getLatitude();
               DeliveryQueueActivity.this.compassMyLng = loc.getLongitude();
               DeliveryQueueActivity.this.updateCompassUi();
               DeliveryQueueActivity.this.pushMyPosToMap();
            }
            public void onStatusChanged(String provider, int status, Bundle extras) {}
            public void onProviderEnabled(@NonNull String provider) {}
            public void onProviderDisabled(@NonNull String provider) {}
         };
         try {
            if (this.compassLocationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
               this.compassLocationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 3f, this.compassLocationListener);
            }
            if (this.compassLocationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
               this.compassLocationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000L, 3f, this.compassLocationListener);
            }
            Location last = this.compassLocationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (last == null) {
               last = this.compassLocationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            }
            if (last != null) {
               this.compassMyLat = last.getLatitude();
               this.compassMyLng = last.getLongitude();
               this.pushMyPosToMap();
            }
         } catch (Exception ignored) {
         }
      }

      if (this.compassSensorManager == null) {
         this.compassSensorManager = (SensorManager) this.getSystemService(Context.SENSOR_SERVICE);
      }
      if (this.compassSensorManager != null) {
         this.compassRotationSensor = this.compassSensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
         if (this.compassRotationSensor == null) {
            this.compassAccelSensor = this.compassSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            this.compassMagnetSensor = this.compassSensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
         }
      }
      boolean hasCompass = this.compassRotationSensor != null || (this.compassAccelSensor != null && this.compassMagnetSensor != null);
      if (hasCompass && this.compassSensorListener == null) {
         final float[] rotVec = new float[5];
         final float[] gravity = new float[3];
         final float[] geomagnetic = new float[3];
         final boolean[] haveGravity = {false};
         final boolean[] haveGeomag = {false};
         this.compassSensorListener = new SensorEventListener() {
            public void onSensorChanged(SensorEvent e) {
               float[] rotMatrix = new float[9];
               boolean ok;
               if (e.sensor.getType() == Sensor.TYPE_ROTATION_VECTOR) {
                  System.arraycopy(e.values, 0, rotVec, 0, Math.min(e.values.length, rotVec.length));
                  SensorManager.getRotationMatrixFromVector(rotMatrix, rotVec);
                  ok = true;
               } else {
                  if (e.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
                     System.arraycopy(e.values, 0, gravity, 0, 3);
                     haveGravity[0] = true;
                  } else if (e.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) {
                     System.arraycopy(e.values, 0, geomagnetic, 0, 3);
                     haveGeomag[0] = true;
                  }
                  ok = haveGravity[0] && haveGeomag[0] && SensorManager.getRotationMatrix(rotMatrix, new float[9], gravity, geomagnetic);
               }
               if (!ok) return;

               // Kompensasi rotasi layar: getOrientation() mengasumsikan orientasi ALAMI perangkat;
               // tanpa remap, heading meleset 90/180 derajat tepat saat kurir memutar HP ke landscape.
               int rotation = DeliveryQueueActivity.this.getWindowManager().getDefaultDisplay().getRotation();
               int axisX = SensorManager.AXIS_X, axisY = SensorManager.AXIS_Y;
               if (rotation == Surface.ROTATION_90) { axisX = SensorManager.AXIS_Y; axisY = SensorManager.AXIS_MINUS_X; }
               else if (rotation == Surface.ROTATION_180) { axisX = SensorManager.AXIS_MINUS_X; axisY = SensorManager.AXIS_MINUS_Y; }
               else if (rotation == Surface.ROTATION_270) { axisX = SensorManager.AXIS_MINUS_Y; axisY = SensorManager.AXIS_X; }
               float[] remapped = new float[9];
               SensorManager.remapCoordinateSystem(rotMatrix, axisX, axisY, remapped);
               float[] orientation = new float[3];
               SensorManager.getOrientation(remapped, orientation);
               DeliveryQueueActivity.this.compassAzimuthDeg = (float) ((Math.toDegrees(orientation[0]) + 360) % 360);
               DeliveryQueueActivity.this.updateCompassUi();
            }
            public void onAccuracyChanged(Sensor sensor, int accuracy) {}
         };
         if (this.compassRotationSensor != null) {
            this.compassSensorManager.registerListener(this.compassSensorListener, this.compassRotationSensor, SensorManager.SENSOR_DELAY_GAME);
         } else {
            this.compassSensorManager.registerListener(this.compassSensorListener, this.compassAccelSensor, SensorManager.SENSOR_DELAY_GAME);
            this.compassSensorManager.registerListener(this.compassSensorListener, this.compassMagnetSensor, SensorManager.SENSOR_DELAY_GAME);
         }
      } else if (!hasCompass && this.tvCompassLabel != null) {
         this.tvCompassLabel.setText("Kompas tidak tersedia di perangkat ini");
      }
   }

   private void stopCompass() {
      if (this.compassSensorManager != null && this.compassSensorListener != null) {
         try { this.compassSensorManager.unregisterListener(this.compassSensorListener); } catch (Exception ignored) {}
      }
      if (this.compassLocationManager != null && this.compassLocationListener != null) {
         try { this.compassLocationManager.removeUpdates(this.compassLocationListener); } catch (Exception ignored) {}
      }
      this.compassSensorListener = null;
      this.compassLocationListener = null;
      this.compassRotationSensor = null;
      this.compassAccelSensor = null;
      this.compassMagnetSensor = null;
      this.compassArrow = null;
      this.tvCompassLabel = null;
      this.tvCompassDist = null;
   }

   /** Dipanggil dari tiap fix GPS baru & tiap tick sensor - jarum + label kompas selalu terkini. */
   private void updateCompassUi() {
      if (this.compassArrow == null || this.tvCompassDist == null || this.tvCompassLabel == null) {
         return;   // dialog sudah ditutup (race: callback sensor/GPS terakhir tiba setelah stopCompass)
      }
      if (Double.isNaN(this.compassMyLat) || Double.isNaN(this.compassMyLng)) {
         this.tvCompassDist.setText("--");
         CharSequence cur = this.tvCompassLabel.getText();
         if (!"Kompas tidak tersedia di perangkat ini".contentEquals(cur)
               && !"Aktifkan izin lokasi untuk arah & jarak".contentEquals(cur)) {
            this.tvCompassLabel.setText("Mencari posisi GPS...");
         }
         this.compassArrow.clearHeading();
         return;
      }

      double bearing = Wilayah.bearing(this.compassMyLat, this.compassMyLng, this.compassDestLat, this.compassDestLng);
      double km = haversineKm(this.compassMyLat, this.compassMyLng, this.compassDestLat, this.compassDestLng);
      String jarak = formatJarak(km);
      this.tvCompassDist.setText(jarak != null ? jarak : "--");

      if (!Float.isNaN(this.compassAzimuthDeg)) {
         float relative = (float) (((bearing - this.compassAzimuthDeg) % 360 + 360) % 360);
         this.tvCompassLabel.setText("\u2197 " + cardinalLabel(bearing));
         this.compassArrow.pointTo(relative);
      } else {
         this.tvCompassLabel.setText("\u2197 " + cardinalLabel(bearing) + " (kompas tak tersedia)");
         this.compassArrow.clearHeading();
      }
   }

   /** Ikon &amp; warna kendaraan PERANGKAT INI (App\Support\DeviceIcon, dicache dari /api/me) \u2014
    *  identitas yang sama dipakai pin posisi live perangkat ini di peta manapun di app ini. */
   private String myVehicleEmoji() {
      String v = this.syncCfg().getDeviceVehicle();
      return v != null && !v.isEmpty() ? v : "\ud83d\udef5";
   }

   private String myVehicleColor() {
      String c = this.syncCfg().getDeviceColor();
      return c != null && !c.isEmpty() ? c : "#0369A1";
   }

   /**
    * Dorong posisi TERKINI (compassMyLat/Lng, sudah diperbarui pemanggil) ke peta Preview yang
    * sedang terbuka lewat {@code window.updateMyPos(lat,lng)} yang didefinisikan
    * {@link #buildMiniMapHtml} \u2014 pin "Posisi Anda" bergerak & peta zoom in/out mengikuti jarak
    * TERKINI ke tujuan, persis diminta ("tampilan active"), tanpa memuat ulang peta dari nol.
    *
    * <p>Aman dipanggil kapan pun \u2014 no-op bila dialog Preview sedang tak terbuka (previewMapWebView
    * null) atau belum ada fix GPS sama sekali (compassMyLat/Lng masih NaN).</p>
    */
   private void pushMyPosToMap() {
      if (this.previewMapWebView == null || Double.isNaN(this.compassMyLat) || Double.isNaN(this.compassMyLng)) {
         return;
      }
      this.previewMapWebView.evaluateJavascript(
            "window.updateMyPos && window.updateMyPos(" + this.compassMyLat + "," + this.compassMyLng + ");", null);
   }

   /**
    * Chip "panduan arah" yang MELAYANG di pojok kanan bawah foto lokasi.
    *
    * <p>Dulu kompas ini menempati barisnya sendiri di atas peta, dan itu memakan ~90dp tinggi dialog
    * hanya untuk dua baris teks — peta jadi sempit padahal petalah yang paling dibaca. Sebagai
    * overlay, informasinya tetap ada tanpa memakan ruang vertikal sama sekali.
    *
    * <p>Latarnya kepingan PUTIH membulat, bukan scrim gelap: {@link CompassArrowView} menggambar
    * jarumnya dengan biru tua (#0369A1), yang di atas scrim gelap nyaris tak terbaca. Putih pekat
    * 95% menjaga kontrasnya sama persis seperti saat kompas masih berdiri di latar dialog, di atas
    * foto seterang atau segelap apa pun.
    */
   private View buildCompassOverlay() {
      LinearLayout chip = new LinearLayout(this);
      chip.setOrientation(LinearLayout.HORIZONTAL);
      chip.setGravity(android.view.Gravity.CENTER_VERTICAL);
      chip.setPadding(this.dp(8f), this.dp(6f), this.dp(10f), this.dp(6f));

      android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
      bg.setColor(0xF2FFFFFF);
      bg.setCornerRadius(this.dp(12f));
      bg.setStroke(this.dp(1f), 0x33000000);
      chip.setBackground(bg);
      chip.setElevation(this.dp(4f));

      CompassArrowView arrow = new CompassArrowView(this);
      chip.addView(arrow, new LinearLayout.LayoutParams(this.dp(40f), this.dp(40f)));

      LinearLayout texts = new LinearLayout(this);
      texts.setOrientation(LinearLayout.VERTICAL);
      LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
      tp.leftMargin = this.dp(8f);

      TextView tvLabel = new TextView(this);
      tvLabel.setText("Mencari posisi GPS...");
      tvLabel.setTextSize(11f);
      tvLabel.setTextColor(0xFF334155);
      tvLabel.setMaxLines(1);
      tvLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);

      TextView tvDist = new TextView(this);
      tvDist.setText("--");
      tvDist.setTextSize(18f);
      tvDist.setTypeface(tvDist.getTypeface(), android.graphics.Typeface.BOLD);
      tvDist.setTextColor(0xFF0369A1);

      texts.addView(tvLabel);
      texts.addView(tvDist);
      chip.addView(texts, tp);

      android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.Gravity.BOTTOM | android.view.Gravity.END);
      lp.rightMargin = this.dp(8f);
      lp.bottomMargin = this.dp(8f);
      chip.setLayoutParams(lp);

      // Referensi yang dipakai pembaruan kompas berkala (updateCompass) — sama seperti sebelumnya,
      // hanya tempat tampilnya yang berpindah.
      this.compassArrow = arrow;
      this.tvCompassLabel = tvLabel;
      this.tvCompassDist = tvDist;

      return chip;
   }

   /**
    * Panel "Preview" gabungan: Kompas (arah+jarak live) di atas, lalu Peta, lalu Foto lokasi, lalu
    * Detail Transaksi - menggantikan dua dialog terpisah "Preview Peta" & "Foto" yang dulu berdiri
    * sendiri-sendiri (lihat {@link #showQueuePreview} & {@code showOtherDevicePreview}).
    */
   /**
    * Badan visual panel Preview (Peta, alamat, Foto+Kompas, Detail Transaksi) - dipakai BAIK oleh
    * showPreviewDialog (dialog kompak, guided=false) MAUPUN renderGuidedInline (penuh layar,
    * guided=true), lewat SATU builder yang sama supaya keduanya tak bisa saling menyimpang. Saat
    * guided, panel dapat elemen ekstra yang tak muat/tak perlu di dialog kompak: FAB Navigasi &amp;
    * tombol WA mengambang di peta, badge nama+durasi di pojok kiri-atas peta, tombol Auto Zoom, dan
    * baris catatan transaksi di atas foto.
    * @param webViewOut  keluaran: WebView peta yang baru dibuat (dipakai pemanggil utk memuat html)
    */
   private LinearLayout buildPreviewContent(final PreviewData d, final WebView[] webViewOut, boolean guided) {
      this.guidedElapsedBadge = null;
      this.guidedMapBox = null;
      LinearLayout content = new LinearLayout(this);
      content.setOrientation(LinearLayout.VERTICAL);

      // ---- Peta ----
      WebView webView = new WebView(this);
      WebSettings ws = webView.getSettings();
      ws.setJavaScriptEnabled(true);
      ws.setDomStorageEnabled(true);
      ws.setUserAgentString(MapTiles.userAgent());
      int mapSizeDp = this.dp(340f);
      webView.setLayoutParams(new android.widget.FrameLayout.LayoutParams(-1, -1));
      webView.setBackgroundColor(-1);
      // Sentuhan di dalam peta menggeser peta, BUKAN men-scroll dialog - tanpa ini ScrollView luar
      // "mencuri" gestur pan/zoom Leaflet begitu jarinya bergerak sedikit vertikal.
      webView.setOnTouchListener((v, ev) -> {
         switch (ev.getAction()) {
            case android.view.MotionEvent.ACTION_DOWN:
               v.getParent().requestDisallowInterceptTouchEvent(true);
               break;
            case android.view.MotionEvent.ACTION_UP:
            case android.view.MotionEvent.ACTION_CANCEL:
               v.getParent().requestDisallowInterceptTouchEvent(false);
               break;
         }
         return false;
      });
      webViewOut[0] = webView;

      android.widget.FrameLayout mapBox = new android.widget.FrameLayout(this);
      mapBox.setLayoutParams(new LinearLayout.LayoutParams(-1, mapSizeDp));
      mapBox.addView(webView);
      if (guided) this.guidedMapBox = mapBox;

      if (guided && d.hasDest) {
         com.google.android.material.floatingactionbutton.FloatingActionButton fabNav =
               new com.google.android.material.floatingactionbutton.FloatingActionButton(this);
         fabNav.setImageResource(android.R.drawable.ic_menu_directions);
         fabNav.setSize(com.google.android.material.floatingactionbutton.FloatingActionButton.SIZE_MINI);
         android.widget.FrameLayout.LayoutParams fabLp = new android.widget.FrameLayout.LayoutParams(-2, -2);
         fabLp.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.END;
         fabLp.setMargins(0, 0, this.dp(12f), this.dp(12f));
         fabNav.setLayoutParams(fabLp);
         fabNav.setOnClickListener((v) -> this.navigateToOrder(d.guidedTrxId, d.destLat, d.destLng));
         mapBox.addView(fabNav);
      }
      if (guided && d.phone != null && !d.phone.trim().isEmpty()) {
         // Tombol WA mengambang: kurir terpandu tak punya layar Detail Order selalu terbuka untuk
         // menghubungi pelanggan, jadi diletakkan di peta yang SELALU terlihat.
         TextView btnWa = new TextView(this);
         btnWa.setText("💬");
         btnWa.setTextSize(18f);
         btnWa.setGravity(android.view.Gravity.CENTER);
         GradientDrawable waBg = new GradientDrawable();
         waBg.setShape(GradientDrawable.OVAL);
         waBg.setColor(-14298266);
         btnWa.setBackground(waBg);
         btnWa.setElevation(this.dp(4f));
         android.widget.FrameLayout.LayoutParams waLp = new android.widget.FrameLayout.LayoutParams(this.dp(40f), this.dp(40f));
         waLp.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.END;
         waLp.setMargins(0, 0, this.dp(12f), this.dp(64f));
         btnWa.setLayoutParams(waLp);
         String phone = d.phone;
         btnWa.setOnClickListener((v) -> this.openWhatsApp(phone, ""));
         mapBox.addView(btnWa);
      }
      if (guided) {
         // Badge nama + durasi antre pojok kiri-atas peta - pengganti judul dialog, yang tak ada
         // di panel inline. this.guidedElapsedBadge diperbarui tiap detik oleh updateGuidedElapsedBadge.
         LinearLayout badgeRow = new LinearLayout(this);
         badgeRow.setOrientation(LinearLayout.HORIZONTAL);
         badgeRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
         android.widget.FrameLayout.LayoutParams badgeRowLp = new android.widget.FrameLayout.LayoutParams(-2, -2);
         badgeRowLp.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
         badgeRowLp.setMargins(0, this.dp(8f), this.dp(8f), 0);
         badgeRow.setLayoutParams(badgeRowLp);
         TextView tvElapsed = new TextView(this);
         tvElapsed.setTextSize(12f);
         tvElapsed.setTextColor(-1);
         tvElapsed.setTypeface(tvElapsed.getTypeface(), android.graphics.Typeface.BOLD);
         tvElapsed.setPadding(this.dp(8f), this.dp(3f), this.dp(8f), this.dp(3f));
         badgeRow.addView(tvElapsed);
         this.guidedElapsedBadge = tvElapsed;
         TextView tvTitle = new TextView(this);
         tvTitle.setText(safe(d.title));
         tvTitle.setTextSize(12f);
         tvTitle.setTextColor(-1);
         tvTitle.setMaxLines(1);
         tvTitle.setEllipsize(TruncateAt.END);
         tvTitle.setTypeface(tvTitle.getTypeface(), android.graphics.Typeface.BOLD);
         tvTitle.setPadding(this.dp(8f), this.dp(3f), this.dp(8f), this.dp(3f));
         GradientDrawable titleBg = new GradientDrawable();
         titleBg.setShape(GradientDrawable.RECTANGLE);
         titleBg.setCornerRadius(this.dp(10f));
         titleBg.setColor(-15374912);
         tvTitle.setBackground(titleBg);
         LinearLayout.LayoutParams tvTitleLp = new LinearLayout.LayoutParams(-2, -2);
         tvTitleLp.setMarginStart(this.dp(6f));
         tvTitle.setLayoutParams(tvTitleLp);
         badgeRow.addView(tvTitle);
         mapBox.addView(badgeRow);

         // Tombol Auto Zoom pojok kanan-atas peta.
         final TextView tvZoom = new TextView(this);
         tvZoom.setTextSize(11f);
         tvZoom.setTextColor(-1);
         tvZoom.setTypeface(tvZoom.getTypeface(), android.graphics.Typeface.BOLD);
         tvZoom.setPadding(this.dp(8f), this.dp(3f), this.dp(8f), this.dp(3f));
         final GradientDrawable zoomBg = new GradientDrawable();
         zoomBg.setShape(GradientDrawable.RECTANGLE);
         zoomBg.setCornerRadius(this.dp(10f));
         android.widget.FrameLayout.LayoutParams tvZoomLp = new android.widget.FrameLayout.LayoutParams(-2, -2);
         tvZoomLp.gravity = android.view.Gravity.TOP | android.view.Gravity.END;
         tvZoomLp.setMargins(0, this.dp(40f), this.dp(8f), 0);
         tvZoom.setLayoutParams(tvZoomLp);
         final Runnable refreshZoomLabel = () -> {
            tvZoom.setText(this.guidedAutoZoom ? "🎯 Auto Zoom" : "🚫 Auto Zoom");
            zoomBg.setColor(this.guidedAutoZoom ? -15374912 : -10193781);
            tvZoom.setBackground(zoomBg);
         };
         refreshZoomLabel.run();
         tvZoom.setOnClickListener((v) -> {
            this.guidedAutoZoom = !this.guidedAutoZoom;
            refreshZoomLabel.run();
            WebView wv = webViewOut[0];
            if (wv != null) wv.evaluateJavascript("window.setAutoZoom&&window.setAutoZoom(" + this.guidedAutoZoom + ");", null);
         });
         mapBox.addView(tvZoom);
      }
      content.addView(mapBox);
      if (d.address != null && !d.address.isEmpty()) {
         TextView tvAddress = new TextView(this);
         tvAddress.setText("📍 " + d.address);
         tvAddress.setTextSize(13.0F);
         tvAddress.setPadding(this.dp(16f), this.dp(8f), this.dp(16f), 0);
         content.addView(tvAddress);
      }

      // ---- Foto lokasi ----
      content.addView(this.sectionHeader("Foto Lokasi"));
      // Foto + panduan arah dalam SATU wadah: kompasnya melayang di pojok kanan bawah foto, bukan
      // memakan barisnya sendiri di atas peta. Itulah yang membuat petanya bisa jauh lebih lega.
      android.widget.FrameLayout photoBox = new android.widget.FrameLayout(this);
      int photoSizeDp = this.dp(180f);
      photoBox.setLayoutParams(new LinearLayout.LayoutParams(-1, photoSizeDp));
      ImageView photoView = new ImageView(this);
      photoView.setLayoutParams(new android.widget.FrameLayout.LayoutParams(-1, -1));
      photoView.setScaleType(ScaleType.CENTER_CROP);
      photoView.setBackgroundColor(0xFFF1F5F9);
      photoBox.addView(photoView);
      if (d.hasDest) {
         photoBox.addView(this.buildCompassOverlay());
      }
      if (guided && d.note != null && !d.note.trim().isEmpty()) {
         // Catatan transaksi TERTEMPEL di atas foto — panel inline tak punya baris tersendiri
         // untuk ini, dan kurir perlu melihatnya SEBELUM mengetuk apa pun (pesan "titip di pos
         // satpam", dsb).
         TextView tvNote = new TextView(this);
         tvNote.setText(d.note.trim());
         tvNote.setTextSize(12f);
         tvNote.setTextColor(-1);
         tvNote.setTypeface(tvNote.getTypeface(), android.graphics.Typeface.BOLD);
         tvNote.setMaxLines(2);
         tvNote.setEllipsize(TruncateAt.END);
         tvNote.setGravity(android.view.Gravity.CENTER);
         tvNote.setPadding(this.dp(10f), this.dp(4f), this.dp(10f), this.dp(4f));
         GradientDrawable noteBg = new GradientDrawable();
         noteBg.setShape(GradientDrawable.RECTANGLE);
         noteBg.setCornerRadius(this.dp(10f));
         noteBg.setColor(0xCC000000);
         tvNote.setBackground(noteBg);
         android.widget.FrameLayout.LayoutParams noteLp = new android.widget.FrameLayout.LayoutParams(-2, -2);
         noteLp.gravity = android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL;
         noteLp.setMargins(this.dp(24f), this.dp(8f), this.dp(24f), 0);
         tvNote.setLayoutParams(noteLp);
         photoBox.addView(tvNote);
      }
      content.addView(photoBox);
      TextView tvNoPhoto = new TextView(this);
      tvNoPhoto.setText("Foto rumah belum ada.");
      tvNoPhoto.setTextSize(13f);
      tvNoPhoto.setTextColor(0xFF94A3B8);
      tvNoPhoto.setPadding(this.dp(16f), this.dp(8f), this.dp(16f), 0);
      tvNoPhoto.setVisibility(View.GONE);
      content.addView(tvNoPhoto);
      this.resolvePreviewPhoto(d, photoView, tvNoPhoto, photoBox, d.hasDest);

      // ---- Detail Transaksi: badge slug+warna, bukan lagi baris teks panjang ----
      boolean hasChips = d.detailChips != null && !d.detailChips.isEmpty();
      boolean hasExtra = d.detailExtra != null && !d.detailExtra.trim().isEmpty();
      if ((hasChips || hasExtra) && !guided) {
         content.addView(this.sectionHeader("Detail Transaksi"));
      }
      if (hasChips) {
         // Badge PERSIS gaya kapsul produk kartu Antrean Delivery (lihat chipAt) — bukan Material
         // Chip lagi: target-sentuh minimumnya tak pernah benar-benar bisa dikecilkan sampai
         // seukuran "MIN ×1" di kartu, jadi dua badge yang mestinya kembar terlihat beda ukuran.
         // Barisnya TIDAK melipat (cermin productChips di kartu, yang juga satu baris) — kalau
         // melebihi lebar dialog, digulir ke samping lewat HorizontalScrollView, bukan dipotong.
         android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
         hs.setHorizontalScrollBarEnabled(false);
         LinearLayout row = new LinearLayout(this);
         row.setOrientation(LinearLayout.HORIZONTAL);
         row.setPadding(this.dp(16f), this.dp(4f), this.dp(16f), hasExtra ? this.dp(4f) : this.dp(10f));
         for (Chip c : d.detailChips) {
            row.addView(this.makeDetailBadge(c.label, c.bg));
         }
         hs.addView(row);
         content.addView(hs);
      }
      if (hasExtra) {
         TextView tvExtra = new TextView(this);
         tvExtra.setText(d.detailExtra.trim());
         tvExtra.setTextSize(13f);
         tvExtra.setTextColor(0xFF64748B);
         tvExtra.setPadding(this.dp(16f), this.dp(6f), this.dp(16f), this.dp(14f));
         content.addView(tvExtra);
      }
      return content;
   }

   private void showPreviewDialog(final PreviewData d) {
      ScrollView scroll = new ScrollView(this);
      WebView[] webViewOut = new WebView[1];
      scroll.addView(this.buildPreviewContent(d, webViewOut, false));
      final WebView webView = webViewOut[0];
      final LiveDeviceOverlay[] overlayRef = new LiveDeviceOverlay[1];
      final String html = buildMiniMapHtml(d.hasDev, d.devLat, d.devLng, safe(d.devName), d.devVehicle, d.devColor,
            d.hasDest, d.destLat, d.destLng, safe(d.destName), this.myVehicleEmoji(), this.myVehicleColor(),
            d.blinkAssigned, null, true);

      AlertDialog.Builder builder = (new AlertDialog.Builder(this)).setTitle("🔍 Preview — " + safe(d.title))
            .setView(scroll).setPositiveButton("Tutup", (DialogInterface.OnClickListener) null);
      if (d.hasDest) {
         builder.setNeutralButton("🧭 Navigasi", (dlg, w) -> this.navigateToOrder(d.guidedTrxId, d.destLat, d.destLng));
      }

      AlertDialog dialog = builder.create();
      dialog.setOnShowListener((dlg) -> {
         dialog.getWindow().setLayout(-1, -2);
         webView.post(() -> {
            webView.onResume();
            webView.setWebViewClient(new WebViewClient() {
               public void onPageFinished(WebView view, String url) {
                  overlayRef[0] = new LiveDeviceOverlay(DeliveryQueueActivity.this, webView)
                        .excluding(d.hasDev ? d.devUuid : null);
                  overlayRef[0].start();
                  // Peta baru selesai memuat -> updateMyPos() baru sekarang benar-benar ada di
                  // halamannya. Dorong posisi TERAKHIR yang sudah diketahui kompas (kalau ada fix
                  // yang datang lebih cepat dari load peta ini) supaya pin "Posisi Anda" langsung
                  // muncul, bukan menunggu fix GPS BERIKUTNYA.
                  DeliveryQueueActivity.this.previewMapWebView = webView;
                  DeliveryQueueActivity.this.pushMyPosToMap();
               }
            });
            webView.loadDataWithBaseURL("https://unpkg.com", html, "text/html", "UTF-8", (String) null);
         });
         if (d.hasDest) {
            this.startCompass(d.destLat, d.destLng);
         }
      });
      dialog.setOnDismissListener((dlg) -> {
         if (overlayRef[0] != null) {
            overlayRef[0].stop();
         }
         webView.stopLoading();
         webView.destroy();
         this.previewMapWebView = null;
         this.stopCompass();
      });
      dialog.show();
   }

   /**
    * Versi PENUH LAYAR showPreviewDialog untuk kurir terpandu — dirender langsung ke dalam
    * guidedPanel (bukan AlertDialog), dilengkapi bar aksi (Detail Order/Selesai, "Selanjutnya"/Trx
    * Baru) di bawah panel Preview. Dipanggil dari updateGuidedPanel setiap kali order berjalan
    * berikutnya berganti.
    */
   private void renderGuidedInline(final PreviewData d) {
      if (this.guidedPanel == null || this.isFinishing() || this.isDestroyed()) return;
      this.teardownGuidedInlinePreview();
      this.guidedPanel.removeAllViews();
      this.guidedPanel.setOrientation(LinearLayout.VERTICAL);
      this.guidedPanel.setVisibility(View.VISIBLE);

      WebView[] webViewOut = new WebView[1];
      final LinearLayout content = this.buildPreviewContent(d, webViewOut, true);
      ScrollView scroll = new ScrollView(this);
      scroll.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 1f));
      scroll.addView(content);
      this.guidedPanel.addView(scroll);
      this.updateGuidedElapsedBadge();

      final LinearLayout actionBar = new LinearLayout(this);
      actionBar.setOrientation(LinearLayout.VERTICAL);
      actionBar.setPadding(this.dp(12f), this.dp(8f), this.dp(12f), this.dp(12f));
      actionBar.setBackgroundColor(-1);
      actionBar.setElevation(this.dp(8f));

      LinearLayout row1 = new LinearLayout(this);
      row1.setOrientation(LinearLayout.HORIZONTAL);
      actionBar.addView(row1);
      final long trxId = d.guidedTrxId;
      MaterialButton btnDetail = new MaterialButton(this);
      btnDetail.setText("📋 Detail Order");
      btnDetail.setAllCaps(false);
      btnDetail.setBackgroundTintList(android.content.res.ColorStateList.valueOf(-10193781));
      LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, -2, 1f);
      lp1.setMarginEnd(this.dp(4f));
      lp1.bottomMargin = this.dp(8f);
      btnDetail.setLayoutParams(lp1);
      btnDetail.setOnClickListener((v) -> {
         Transaction t = this.findQueueTrx(trxId);
         if (t != null) this.showOrderDetail(t);
      });
      row1.addView(btnDetail);
      MaterialButton btnDone = new MaterialButton(this);
      btnDone.setText("✅ Selesai");
      btnDone.setAllCaps(false);
      LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, -2, 1f);
      lp2.setMarginStart(this.dp(4f));
      lp2.bottomMargin = this.dp(8f);
      btnDone.setLayoutParams(lp2);
      btnDone.setOnClickListener((v) -> {
         Transaction t = this.findQueueTrx(trxId);
         if (t != null) this.maybeWarnIncompleteThenComplete(t);
      });
      row1.addView(btnDone);

      LinearLayout row2 = new LinearLayout(this);
      row2.setOrientation(LinearLayout.HORIZONTAL);
      actionBar.addView(row2);
      List<Transaction> stops = this.runStops();
      Transaction nextStop = stops.size() > 1 ? stops.get(1) : null;
      LinearLayout.LayoutParams lp3 = new LinearLayout.LayoutParams(0, -2, 1f);
      lp3.setMarginEnd(this.dp(4f));
      row2.addView(this.buildGuidedNextStopCell(nextStop, lp3));
      MaterialButton btnNewTrx = new MaterialButton(this);
      btnNewTrx.setText("➕ Trx Baru");
      btnNewTrx.setAllCaps(false);
      btnNewTrx.setBackgroundTintList(android.content.res.ColorStateList.valueOf(-16553567));
      LinearLayout.LayoutParams lp4 = new LinearLayout.LayoutParams(0, -2, 1f);
      lp4.setMarginStart(this.dp(4f));
      btnNewTrx.setLayoutParams(lp4);
      btnNewTrx.setOnClickListener((v) -> this.launchGuidedJualAirMinum());
      row2.addView(btnNewTrx);
      this.guidedPanel.addView(actionBar);

      final WebView webView = webViewOut[0];
      final String html = buildMiniMapHtml(d.hasDev, d.devLat, d.devLng, safe(d.devName), d.devVehicle, d.devColor,
            d.hasDest, d.destLat, d.destLng, safe(d.destName), this.myVehicleEmoji(), this.myVehicleColor(),
            d.blinkAssigned, this.buildOtherPendingForMap(), this.guidedAutoZoom);
      this.guidedInlineWebView = webView;
      webView.addJavascriptInterface(new GuidedMapBridge(), "Android");
      webView.post(() -> {
         webView.onResume();
         webView.setWebViewClient(new WebViewClient() {
            public void onPageFinished(WebView view, String url) {
               DeliveryQueueActivity.this.guidedInlineOverlay = new LiveDeviceOverlay(DeliveryQueueActivity.this, webView)
                     .excluding(d.hasDev ? d.devUuid : null);
               DeliveryQueueActivity.this.guidedInlineOverlay.start();
               DeliveryQueueActivity.this.previewMapWebView = webView;
               DeliveryQueueActivity.this.pushMyPosToMap();
            }
         });
         webView.loadDataWithBaseURL("https://unpkg.com", html, "text/html", "UTF-8", null);
      });
      if (d.hasDest) this.startCompass(d.destLat, d.destLng);

      // Peta dilebarkan mengisi sisa tinggi layar SETELAH tata letak final diketahui (bar aksi &
      // baris alamat/badge produk punya tinggi variabel) — bukan angka tetap, supaya di layar kecil
      // tetap proporsional dan di layar besar peta tak menyisakan area kosong percuma.
      final android.widget.FrameLayout mapBoxRef = (android.widget.FrameLayout) content.getChildAt(0);
      this.guidedPanel.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
         public void onGlobalLayout() {
            DeliveryQueueActivity.this.guidedPanel.getViewTreeObserver().removeOnGlobalLayoutListener(this);
            int height = DeliveryQueueActivity.this.guidedPanel.getHeight() - actionBar.getHeight()
                  - (content.getHeight() - mapBoxRef.getHeight());
            if (height > mapBoxRef.getHeight()) {
               ViewGroup.LayoutParams lp = mapBoxRef.getLayoutParams();
               lp.height = height;
               mapBoxRef.setLayoutParams(lp);
            }
         }
      });
   }
   private TextView sectionHeader(String text) {
      TextView tv = new TextView(this);
      tv.setText(text);
      tv.setTextSize(12f);
      tv.setTypeface(tv.getTypeface(), android.graphics.Typeface.BOLD);
      tv.setTextColor(0xFF64748B);
      tv.setPadding(this.dp(16f), this.dp(14f), this.dp(16f), this.dp(4f));
      return tv;
   }

   /**
    * Isi ImageView foto lokasi TANPA memblokir dialog: lokal -> decode langsung (cepat); URL server
    * -> unduh di thread lain lalu tempel begitu selesai. Beda dengan dulu (Toast + dialog terpisah
    * yang menunggu unduhan sebelum tampil), sekarang Preview-nya sudah terbuka duluan dan fotonya
    * menyusul - konsisten dengan Peta+Kompas yang juga tampil seketika.
    */
   /**
    * @param photoBox    wadah foto + overlay kompas
    * @param keepForCompass true bila chip arah menempel di wadah itu — wadahnya TIDAK boleh
    *                       disembunyikan walau fotonya tak ada, kalau tidak panduan arahnya ikut
    *                       hilang. Tingginya dikecilkan supaya tak menyisakan kotak abu-abu besar.
    */
   private void resolvePreviewPhoto(PreviewData d, ImageView photoView, TextView tvNoPhoto,
         android.widget.FrameLayout photoBox, boolean keepForCompass) {
      Runnable hidePhoto = () -> {
         photoView.setVisibility(View.GONE);
         tvNoPhoto.setVisibility(View.VISIBLE);
         if (keepForCompass) {
            ViewGroup.LayoutParams lp = photoBox.getLayoutParams();
            lp.height = this.dp(64f);
            photoBox.setLayoutParams(lp);
         } else {
            photoBox.setVisibility(View.GONE);
         }
      };
      String local = d.photoLocalPath;
      if (local != null && !local.isEmpty() && (new File(local)).exists()) {
         Bitmap bmp = BitmapUtils.decodeForScreen(this, local);
         if (bmp != null) {
            photoView.setImageBitmap(bmp);
            photoView.setOnClickListener((v) -> this.showFullScreenPhotoQueue(local));
            return;
         }
      }
      String url = d.photoUrl;
      if (url == null || url.isEmpty()) {
         hidePhoto.run();
         return;
      }
      String name = "preview_" + Integer.toHexString(String.valueOf(d.destName).hashCode()) + "_" + Integer.toHexString(url.hashCode()) + ".jpg";
      (new Thread(() -> {
         File f = BitmapUtils.downloadToCache(this.getApplicationContext(), url, name);
         this.runOnUiThread(() -> {
            if (this.isFinishing() || this.isDestroyed()) return;
            if (f == null) {
               hidePhoto.run();
            } else {
               Bitmap bmp = BitmapUtils.decodeForScreen(this, f.getAbsolutePath());
               if (bmp != null) {
                  photoView.setImageBitmap(bmp);
                  String path = f.getAbsolutePath();
                  photoView.setOnClickListener((v) -> this.showFullScreenPhotoQueue(path));
               } else {
                  hidePhoto.run();   // gambar rusak/tak bisa di-decode: jalur ketiga, mudah terlewat
               }
            }
         });
      })).start();
   }

   private void showFullScreenPhotoQueue(String path) {
      Bitmap bmp = BitmapUtils.decodeForScreen(this, path);
      if (bmp == null) {
         Toast.makeText(this, "Foto tidak dapat dimuat", 0).show();
      } else {
         ImageView iv = new ImageView(this);
         iv.setImageBitmap(bmp);
         iv.setScaleType(ScaleType.FIT_CENTER);
         iv.setBackgroundColor(-16777216);
         iv.setAdjustViewBounds(true);
         Dialog dialog = new Dialog(this, 16973834);
         dialog.setContentView(iv, new ViewGroup.LayoutParams(-1, -1));
         iv.setOnClickListener((v) -> dialog.dismiss());
         dialog.show();
      }
   }

   private void claimOpenDispatchThenRun(Transaction t) {
      this.claimOpenDispatch(t, true);
   }

   private void claimOpenDispatchOnly(Transaction t) {
      this.claimOpenDispatch(t, false);
   }

   // ============================ Ambil Alih massal (Tab 2 & Tab 3) ============================

   /**
    * Masuk mode pilih-banyak. Dipicu tekan-tahan sebuah kartu, konvensi yang sama dengan
    * mode-pilih rute di Tab 1 supaya kurir tak perlu mempelajari dua gestur berbeda.
    *
    * @param open true = Tab 3 (Pesanan Terbuka), false = Tab 2 (Perangkat Lain)
    */
   private void enterClaimSelect(boolean open) {
      if (!this.syncCfg().isEnrolled()) {
         Toast.makeText(this, "Perangkat belum terhubung ke server.", 1).show();
         return;
      }
      if (open) {
         this.claimSelectOpen = true;
         this.claimSelectedOpen.clear();
      } else {
         this.claimSelectOther = true;
         this.claimSelectedOther.clear();
      }
      this.updateClaimSelectUi(open);
      Toast.makeText(this, "Pilih order → Ambil Alih sekaligus", 0).show();
   }

   private void exitClaimSelect(boolean open) {
      if (open) {
         this.claimSelectOpen = false;
         this.claimSelectedOpen.clear();
         if (this.openDispatchAdapter != null) {
            this.openDispatchAdapter.notifyDataSetChanged();
         }
      } else {
         this.claimSelectOther = false;
         this.claimSelectedOther.clear();
         if (this.otherDevicesAdapter != null) {
            this.otherDevicesAdapter.notifyDataSetChanged();
         }
      }
      this.updateClaimSelectUi(open);
   }

   private android.widget.CheckBox cbClaimAllOther, cbClaimAllOpen;
   private boolean syncingClaimAll;

   /** Kotak "Semua" di bar mode-pilih Perangkat Lain / Pesanan Terbuka (cermin bar Antrian Saya):
    *  centang = pilih semua kartu yang TAMPIL (hasil filter), lepas = kosongkan pilihan. */
   private android.widget.CheckBox addSelectAllBox(TextView countLabel, boolean open) {
      if (countLabel == null || !(countLabel.getParent() instanceof LinearLayout)) return null;
      LinearLayout bar = (LinearLayout) countLabel.getParent();
      android.widget.CheckBox cb = new android.widget.CheckBox(this);
      cb.setText("Semua");
      bar.addView(cb, bar.indexOfChild(countLabel));
      cb.setOnCheckedChangeListener((b, on) -> {
         if (this.syncingClaimAll) return;
         if (open) {
            this.claimSelectedOpen.clear();
            if (on && this.openDispatchAdapter != null) for (Transaction t : this.openDispatchAdapter.data) this.claimSelectedOpen.add(t.getId());
            if (this.openDispatchAdapter != null) this.openDispatchAdapter.notifyDataSetChanged();
         } else {
            this.claimSelectedOther.clear();
            if (on && this.otherDevicesAdapter != null) {
               for (JSONObject o : this.otherDevicesAdapter.data) {
                  String u = o.optString("uuid", "");
                  if (!u.isEmpty() && !isBeingDelivered(o)) this.claimSelectedOther.add(u);
               }
            }
            if (this.otherDevicesAdapter != null) this.otherDevicesAdapter.notifyDataSetChanged();
         }
         this.updateClaimSelectUi(open);
      });
      return cb;
   }

   private void updateClaimSelectUi(boolean open) {
      android.widget.CheckBox all = open ? this.cbClaimAllOpen : this.cbClaimAllOther;
      if (all != null) {
         int total = open ? (this.openDispatchAdapter != null ? this.openDispatchAdapter.data.size() : 0)
               : (this.otherDevicesAdapter != null ? this.otherDevicesAdapter.data.size() : 0);
         int sel = open ? this.claimSelectedOpen.size() : this.claimSelectedOther.size();
         this.syncingClaimAll = true;
         all.setChecked(total > 0 && sel >= total);
         this.syncingClaimAll = false;
      }
      View bar = open ? this.barClaimOpen : this.barClaimOther;
      TextView label = open ? this.tvClaimOpenCount : this.tvClaimOtherCount;
      boolean on = open ? this.claimSelectOpen : this.claimSelectOther;
      int n = open ? this.claimSelectedOpen.size() : this.claimSelectedOther.size();
      if (bar != null) {
         bar.setVisibility(on ? 0 : 8);
      }
      if (label != null) {
         label.setText(n + " dipilih");
      }
   }

   /** Ketuk kartu saat mode-pilih aktif → masuk/keluar pilihan. */
   private void toggleClaimOther(String trxUuid) {
      if (trxUuid == null || trxUuid.isEmpty()) {
         return;
      }
      if (!this.claimSelectedOther.remove(trxUuid)) {
         this.claimSelectedOther.add(trxUuid);
      }
      this.updateClaimSelectUi(false);
   }

   private void toggleClaimOpen(long id) {
      if (!this.claimSelectedOpen.remove(id)) {
         this.claimSelectedOpen.add(id);
      }
      this.updateClaimSelectUi(true);
   }

   private void confirmBulkClaimOther() {
      // Snapshot pemilik SAAT INI per order: klaim memakainya sebagai syarat optimistic-locking,
      // jadi order yang keburu berpindah ditolak server alih-alih direbut diam-diam.
      java.util.LinkedHashMap<String, String> picked = new java.util.LinkedHashMap<>();
      for (JSONObject o : this.otherDevicesAdapter.data) {
         String uuid = o.optString("uuid", "");
         // Yang keburu dijalankan kurirnya setelah dipilih (daftar disegarkan) tak ikut diklaim.
         if (!uuid.isEmpty() && this.claimSelectedOther.contains(uuid) && !isBeingDelivered(o)) {
            picked.put(uuid, o.optString("routed_uuid", ""));
         }
      }
      if (picked.isEmpty()) {
         Toast.makeText(this, "Belum ada order yang dipilih.", 0).show();
         return;
      }
      (new AlertDialog.Builder(this))
            .setTitle("Ambil alih " + picked.size() + " order?")
            .setMessage("Semua order ini pindah ke Antrian Saya. Kurir yang memegangnya sekarang "
                  + "akan kehilangan order tersebut dari antriannya.")
            .setPositiveButton("Ambil Alih", (d, w) -> this.runBulkClaim(picked, false))
            .setNegativeButton("Batal", (DialogInterface.OnClickListener)null)
            .show();
   }

   /** "Aksi ⋮" mode-pilih tab Perangkat Lain — cermin Aksi massal Antrian Saya: Alihkan ke perangkat
    *  lain / sesuai wilayah. Tiap order lewat /api/delivery/route + from_device_uuid (pemegang yang
    *  dilihat), jadi order yang keburu pindah / sedang diantar ditolak server. */
   private void showOtherSelectionActions(View anchor) {
      java.util.LinkedHashMap<String, String[]> picked = new java.util.LinkedHashMap<>();
      for (JSONObject o : this.otherDevicesAdapter.data) {
         String uuid = o.optString("uuid", "");
         if (!uuid.isEmpty() && this.claimSelectedOther.contains(uuid) && !isBeingDelivered(o)) {
            picked.put(uuid, new String[]{otherHolderUuid(o), o.optString("name", "")});
         }
      }
      if (picked.isEmpty()) {
         Toast.makeText(this, "Belum ada order yang dipilih.", 0).show();
         return;
      }
      PopupMenu menu = new PopupMenu(this, anchor);
      menu.getMenu().add(0, 1, 0, "📲 Alihkan " + picked.size() + " order ke perangkat lain");
      menu.getMenu().add(0, 2, 1, "🧭 Alihkan " + picked.size() + " order sesuai wilayahnya");
      menu.setOnMenuItemClickListener((item) -> {
         if (item.getItemId() == 2) {
            (new AlertDialog.Builder(this)).setTitle("Alihkan " + picked.size() + " order sesuai wilayah?")
                  .setMessage("Tiap order dikirim ke perangkat pemilik wilayahnya.")
                  .setPositiveButton("Alihkan", (d, w) -> this.runBulkForwardOther(picked, "__wilayah__", "wilayah masing-masing"))
                  .setNegativeButton("Batal", (DialogInterface.OnClickListener) null).show();
            return true;
         }
         SyncSettings cfg = this.syncCfg();
         String myUuid = cfg.getDeviceUuid();
         List<String[]> devs = new ArrayList<>();
         try {
            JSONArray arr = new JSONArray(cfg.getDeviceRoster());
            for (int i = 0; i < arr.length(); ++i) {
               JSONObject d = arr.optJSONObject(i);
               if (d == null) continue;
               String u = d.optString("uuid", "");
               if (!u.isEmpty() && !u.equals(myUuid)) devs.add(new String[]{u, d.optString("name", "Perangkat")});
            }
         } catch (Exception ignored) {
         }
         if (devs.isEmpty()) {
            Toast.makeText(this, "Belum ada perangkat delivery lain di roster. Coba sinkron dulu.", 1).show();
            return true;
         }
         String[] names = new String[devs.size()];
         for (int i = 0; i < devs.size(); ++i) names[i] = devs.get(i)[1];
         (new AlertDialog.Builder(this)).setTitle("Alihkan " + picked.size() + " order ke perangkat mana?")
               .setItems(names, (dx, which) -> {
                  String[] tg = devs.get(which);
                  (new AlertDialog.Builder(this)).setTitle("Alihkan ke \"" + tg[1] + "\"?")
                        .setMessage(picked.size() + " order dipindahkan ke antrian \"" + tg[1] + "\".")
                        .setPositiveButton("Alihkan Semua", (d, w) -> this.runBulkForwardOther(picked, tg[0], tg[1]))
                        .setNegativeButton("Batal", (DialogInterface.OnClickListener) null).show();
               })
               .setNegativeButton("Batal", (DialogInterface.OnClickListener) null).show();
         return true;
      });
      menu.show();
   }

   private void runBulkForwardOther(java.util.LinkedHashMap<String, String[]> picked, String targetUuid, String targetName) {
      SyncSettings cfg = this.syncCfg();
      android.app.ProgressDialog wait = new android.app.ProgressDialog(this);
      wait.setMessage("Mengalihkan " + picked.size() + " order…");
      wait.setCancelable(false);
      wait.show();
      (new Thread(() -> {
         int ok = 0;
         StringBuilder fail = new StringBuilder();
         for (Map.Entry<String, String[]> e : picked.entrySet()) {
            String holder = e.getValue()[0];
            if (holder.equals(targetUuid)) continue;
            try {
               JSONObject body = new JSONObject();
               body.put("transaction_uuid", e.getKey());
               body.put("target_device_uuid", targetUuid);
               body.put("from_device_uuid", holder);
               (new SyncApi(cfg)).routeDelivery(body);
               ok++;
            } catch (SyncApi.SyncException se) {
               String m = extractRouteErrorMessage(se.body);
               fail.append("\n• ").append(e.getValue()[1]).append(": ").append(m != null ? m : "kode " + se.code);
            } catch (Exception ex) {
               fail.append("\n• ").append(e.getValue()[1]).append(": gagal koneksi");
            }
         }
         final int okF = ok;
         final String failF = fail.toString();
         this.runOnUiThread(() -> {
            if (this.isFinishing() || this.isDestroyed()) return;
            wait.dismiss();
            this.exitClaimSelect(false);
            SyncScheduler.syncNow(this.getApplicationContext());
            this.loadData();
            String msg = okF + " order dialihkan ke " + targetName + "." + (failF.isEmpty() ? "" : "\n\nGagal:" + failF);
            (new AlertDialog.Builder(this)).setTitle("Alihkan Massal").setMessage(msg)
                  .setPositiveButton("OK", (DialogInterface.OnClickListener) null).show();
         });
      })).start();
   }

   private void confirmBulkClaimOpen() {
      java.util.LinkedHashMap<String, String> picked = new java.util.LinkedHashMap<>();
      for (Transaction t : this.openDispatchAdapter.data) {
         if (this.claimSelectedOpen.contains(t.getId())) {
            String uuid = this.dao.getSyncUuidById(t.getId());
            if (uuid != null && !uuid.isEmpty()) {
               // Pesanan Terbuka tak bertuan → syarat pemilik dikirim KOSONG, sama dengan klaim
               // satuan; server menolak bila ternyata sudah ada yang mengambil duluan.
               picked.put(uuid, "");
            }
         }
      }
      if (picked.isEmpty()) {
         Toast.makeText(this, "Belum ada order yang dipilih.", 0).show();
         return;
      }
      (new AlertDialog.Builder(this))
            .setTitle("Ambil alih " + picked.size() + " Pesanan Terbuka?")
            .setMessage("Semua order ini masuk Antrian Saya. Perangkat lain tidak bisa mengambilnya "
                  + "lagi setelah ini.")
            .setPositiveButton("Ambil Alih", (d, w) -> this.runBulkClaim(picked, true))
            .setNegativeButton("Batal", (DialogInterface.OnClickListener)null)
            .show();
   }

   /**
    * Klaim beberapa order berurutan lewat endpoint klaim yang SAMA dengan klaim satuan.
    *
    * <p>Sengaja satu per satu, bukan satu panggilan borongan: servernya memakai UPDATE bersyarat
    * per order supaya order yang keburu diambil rekan ditolak. Menggabungkannya jadi satu
    * transaksi borongan akan memaksa pilihan "semua atau tidak sama sekali" — padahal yang benar
    * di lapangan adalah mengambil yang masih bisa diambil, lalu melaporkan sisanya.
    *
    * @param picked uuid transaksi → uuid pemilik yang diharapkan ("" = tak bertuan)
    */
   private void runBulkClaim(java.util.LinkedHashMap<String, String> picked, boolean open) {
      SyncSettings cfg = this.syncCfg();
      android.app.ProgressDialog wait = new android.app.ProgressDialog(this);
      wait.setMessage("Mengambil alih " + picked.size() + " order...");
      wait.setCancelable(false);
      wait.show();

      (new Thread(() -> {
         int ok = 0;
         int fail = 0;
         for (java.util.Map.Entry<String, String> e : picked.entrySet()) {
            try {
               JSONObject body = new JSONObject();
               body.put("transaction_uuid", e.getKey());
               body.put("expected_device_uuid", e.getValue() != null ? e.getValue() : "");
               (new SyncApi(cfg)).claimDelivery(body);
               ok++;
            } catch (Exception ex) {
               fail++;
            }
         }
         final int okF = ok;
         final int failF = fail;
         this.runOnUiThread(() -> {
            try { wait.dismiss(); } catch (Throwable ignored) {}
            if (this.isFinishing() || this.isDestroyed()) {
               return;
            }
            this.exitClaimSelect(open);
            String msg = okF + " order masuk Antrian Saya"
                  + (failF > 0 ? " \u00b7 " + failF + " gagal (mungkin sudah diambil rekan)" : ".");
            Toast.makeText(this, msg, 1).show();
            com.crowja.damiupos.sync.SyncScheduler.syncNow(this.getApplicationContext());
            this.loadOtherDevices();
            this.loadData();
         });
      })).start();
   }

   private void confirmAmbilAlih(Transaction t) {
      (new AlertDialog.Builder(this)).setTitle("Ambil alih pesanan ini?").setMessage("\"" + safe(t.getCustomerName()) + "\" akan masuk Antrian Saya. Perangkat lain tidak bisa mengambilnya lagi setelah ini.").setPositiveButton("Ambil Alih", (d, w) -> this.claimOpenDispatchOnly(t)).setNegativeButton("Batal", (DialogInterface.OnClickListener)null).show();
   }

   private void claimOpenDispatch(Transaction t, boolean thenRun) {
      SyncSettings cfg = this.syncCfg();
      if (!cfg.isEnrolled()) {
         Toast.makeText(this, "Perangkat belum terhubung ke server.", 1).show();
      } else {
         String trxUuid = this.dao.getSyncUuidById(t.getId());
         if (trxUuid != null && !trxUuid.isEmpty()) {
            (new Thread(() -> {
               String okMsg = null;
               String errMsg = null;

               try {
                  JSONObject body = new JSONObject();
                  body.put("transaction_uuid", trxUuid);
                  body.put("expected_device_uuid", "");
                  JSONObject r = (new SyncApi(cfg)).claimDelivery(body);
                  okMsg = r.optString("message", thenRun ? "Order diklaim ke perangkat ini." : "Order diambil alih — masuk Antrian Saya.");
               } catch (SyncApi.SyncException var10) {
                  SyncApi.SyncException se = var10;

                  try {
                     errMsg = (new JSONObject(se.body)).optString("message", (String)null);
                  } catch (Exception var9) {
                  }

                  if (errMsg == null) {
                     errMsg = "Gagal mengklaim (kode " + var10.code + ").";
                  }
               } catch (Exception var11) {
                  errMsg = "Gagal mengklaim — periksa koneksi internet.";
               }

               final String okMsgF = okMsg;
               final String errMsgF = errMsg;
               this.runOnUiThread(() -> {
                  if (!this.isFinishing() && !this.isDestroyed()) {
                     if (okMsgF != null) {
                        Toast.makeText(this, okMsgF, 0).show();
                        this.dao.markClaimedLocally(t.getId(), cfg.getDeviceUuid());
                        SyncScheduler.syncNow(this.getApplicationContext());
                        if (this.tabs != null) {
                           this.tabs.selectTab(this.tabs.getTabAt(0));
                        }

                        this.loadData();
                        if (thenRun) {
                           this.doStartRun(t);
                        }
                     } else {
                        Toast.makeText(this, errMsgF, 1).show();
                        this.loadData();
                     }

                  }
               });
            })).start();
         } else {
            Toast.makeText(this, "Order ini belum punya identitas server. Coba muat ulang.", 1).show();
         }
      }
   }

   private static String orderSummary(Transaction t) {
      return orderSummary(t, true);
   }

   private static String orderSummary(Transaction t, boolean includeOngkir) {
      if ("KEMBALI".equals(t.getType())) {
         return "↩ Pickup • " + t.getJumlahGalon() + " galon kembali";
      } else if (!includeOngkir) {
         return t.getJumlahGalon() + " galon: Rp " + formatRupiah(t.getTotalHarga());
      } else {
         StringBuilder sb = new StringBuilder();
         sb.append(t.getJumlahGalon()).append(" galon");
         sb.append(" • Rp ").append(formatRupiah(t.getTotalHarga()));
         if (t.getOngkir() > (double)0.0F) {
            sb.append(" (ongkir Rp ").append(formatRupiah(t.getOngkir())).append(")");
         }

         return sb.toString();
      }
   }

   private static String formatRupiah(double v) {
      return String.format(Locale.US, "%,d", (long)v).replace(',', '.');
   }

   private int dp(float v) {
      return Math.round(v * this.getResources().getDisplayMetrics().density);
   }

   private void blinkViewForever(View v) {
      if (v != null) {
         AlphaAnimation a = new AlphaAnimation(1.0F, 0.3F);
         a.setDuration(500L);
         a.setRepeatCount(-1);
         a.setRepeatMode(2);
         v.startAnimation(a);
      }
   }

   /** Baris "⚡ PRIORITAS: <alasan>" berkedip di bawah nama — SAMA dengan kartu Antrian Saya, dipakai
    *  juga kartu Perangkat Lain & Pesanan Terbuka supaya kurir tahu KENAPA order itu didahulukan. */
   private void bindPriorityLine(TextView tv, boolean priority, String why) {
      if (tv == null) return;
      String w = why == null ? "" : why.trim();
      if (priority) {
         tv.setText("⚡ PRIORITAS" + (w.isEmpty() || "null".equals(w) ? "" : ": " + w));
         tv.setVisibility(View.VISIBLE);
         this.blinkViewForever(tv);
      } else {
         tv.setVisibility(View.GONE);
         tv.clearAnimation();
         tv.setAlpha(1.0F);
      }
   }

   static {
      SDF_PARSE = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
      ITEM_LABEL_PATTERN = Pattern.compile("^(.*?)\\s+(\\d+)×$");
   }

   private class OtherDevicesAdapter extends RecyclerView.Adapter<OtherDevicesAdapter.VH> {
      private final List<JSONObject> rawData;
      private final List<JSONObject> data;

      private OtherDevicesAdapter() {
         super();
         this.rawData = new ArrayList<>();
         this.data = new ArrayList<>();
      }

      void setData(JSONArray arr) {
         this.rawData.clear();
         if (arr != null) {
            for(int i = 0; i < arr.length(); ++i) {
               JSONObject o = arr.optJSONObject(i);
               if (o != null && !o.optBoolean("open_dispatch", false)) {
                  this.rawData.add(o);
               }
            }
         }

         this.applyFilterSort();
      }

      int rawCount() {
         return this.rawData.size();
      }

      void applyFilterSort() {
         this.data.clear();
         String q = DeliveryQueueActivity.this.searchOtherQuery.toLowerCase(Locale.US);
         int kind = DeliveryQueueActivity.this.kindOther;

         boolean complaintOnly = DeliveryQueueActivity.this.complaintOnlyOther;
         for(JSONObject o : this.rawData) {
            if (!kindMatches(kind, o.optBoolean("pickup_only", false))) continue;
            if (!DeliveryQueueActivity.this.slugMatches(DeliveryQueueActivity.this.slugOther, o)) continue;
            if (!DeliveryQueueActivity.this.tagMatches(DeliveryQueueActivity.this.slugOther, o.optString("uuid", ""))) continue;
            if (complaintOnly && !o.optBoolean("complained", false)) continue;
            if (q.isEmpty()) {
               this.data.add(o);
            } else {
               String name = this.str(o, "name").toLowerCase(Locale.US);
               String phone = this.str(o, "phone").toLowerCase(Locale.US);
               if (name.contains(q) || phone.contains(q) || CustomerDao.phoneMatches(phone, q)
                     || this.str(o, "note").toLowerCase(Locale.US).contains(q)) {
                  this.data.add(o);
               }
            }
         }

         if (DeliveryQueueActivity.this.sortOtherMode == 1) {
            this.data.sort((a, b) -> Integer.compare(b.optInt("galon", 0), a.optInt("galon", 0)));
         } else if (DeliveryQueueActivity.this.sortOtherMode == 2) {
            // Menunggu PALING LAMA duluan — sama seperti badge umur ⏱ tiap kartu.
            this.data.sort((a, b) -> Long.compare(Ts.millisOrMin(this.str(a, "queued_at")), Ts.millisOrMin(this.str(b, "queued_at"))));
         } else if (!Double.isNaN(DeliveryQueueActivity.this.otherLat) && !Double.isNaN(DeliveryQueueActivity.this.otherLng)) {
            this.data.sort((a, b) -> Double.compare(this.distanceKmOf(a), this.distanceKmOf(b)));
         }

         this.notifyDataSetChanged();
         DeliveryQueueActivity.this.updateOtherEmptyState();
         int galon = 0;
         for (JSONObject o : this.data) {
            galon += Math.max(0, o.optInt("galon", 0));
         }
         DeliveryQueueActivity.this.setTabSummary(DeliveryQueueActivity.this.tvOtherSummary,
               this.data.size(), this.rawData.size(), galon);
      }

      private double distanceKmOf(JSONObject o) {
         double lat = o.optDouble("latitude", (double)0.0F);
         double lng = o.optDouble("longitude", (double)0.0F);
         return lat == (double)0.0F && lng == (double)0.0F ? Double.MAX_VALUE : DeliveryQueueActivity.haversineKmOtherDevices(DeliveryQueueActivity.this.otherLat, DeliveryQueueActivity.this.otherLng, lat, lng);
      }

      private String str(JSONObject o, String key) {
         String v = o.optString(key, "");
         return v != null && !v.equals("null") ? v : "";
      }

      @NonNull
      public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
         View v = LayoutInflater.from(parent.getContext()).inflate(layout.item_other_device_compact, parent, false);
         return new VH(v);
      }

      public void onBindViewHolder(@NonNull VH h, int position) {
         JSONObject q = (JSONObject)this.data.get(position);
         DeliveryQueueActivity.bindOrderNote(h.tvOrderNote, this.str(q, "note"));
         boolean custPriority = q.optBoolean("is_priority", false);
         boolean orderPriority = q.optBoolean("order_priority", false);
         String custUuid = this.str(q, "customer_uuid");
         Customer c = custUuid.isEmpty() ? null : DeliveryQueueActivity.this.customerDao.getBySyncUuid(custUuid);
         boolean incomplete = c != null && c.isIncomplete();
         String badge = (orderPriority ? "⚡ " : "") + (custPriority ? "⭐ " : "") + (incomplete ? "❗ " : "");
         h.tvCustomer.setText(badge + this.str(q, "name"));
         DeliveryQueueActivity.this.bindPriorityLine(h.tvPriorityBig, orderPriority, this.str(q, "order_priority_reason"));
         long elapsedMs = DeliveryQueueActivity.elapsedMillis(q.optString("queued_at", (String)null));
         DeliveryQueueActivity.bindElapsedBadge(h.tvElapsed, elapsedMs);
         boolean delivering = DeliveryQueueActivity.isBeingDelivered(q);
         StringBuilder meta = new StringBuilder();
         if (delivering) {
            meta.append("\ud83d\ude9a Sedang diantar");
         }
         String dev = this.str(q, "device_group_label");
         if (!dev.isEmpty()) {
            meta.append(meta.length() > 0 ? " \u00b7 " : "").append("\ud83d\udcf1 ").append(dev);
         }

         String phone = this.str(q, "phone");
         if (!phone.isEmpty()) {
            meta.append(meta.length() > 0 ? " · " : "").append("\ud83d\udcde ").append(phone);
         }

         double lat = q.optDouble("latitude", (double)0.0F);
         double lng = q.optDouble("longitude", (double)0.0F);
         String jarakOther = null;
         if (!Double.isNaN(DeliveryQueueActivity.this.otherLat) && !Double.isNaN(DeliveryQueueActivity.this.otherLng) && (lat != (double)0.0F || lng != (double)0.0F)) {
            double km = DeliveryQueueActivity.haversineKmOtherDevices(DeliveryQueueActivity.this.otherLat, DeliveryQueueActivity.this.otherLng, lat, lng);
            jarakOther = km < (double)1.0F ? Math.round(km * (double)1000.0F) + " m" : String.format(Locale.US, "%.1f km", km).replace('.', ',');
         }

         int galon = q.optInt("galon", 0);
         meta.append(meta.length() > 0 ? " · " : "").append(galon).append(" galon");
         meta.append(DeliveryQueueActivity.checkoutLegSuffix(q));
         meta.append(DeliveryQueueActivity.receiptSuffix(this.str(q, "receipt_no")));
         h.tvMeta.setText(meta.toString());
         double ongkir = q.optDouble("ongkir", (double)0.0F);
         h.tvOngkir.setVisibility(ongkir > (double)0.0F ? 0 : 8);
         DeliveryQueueActivity.bindPickupOnlyBadge(h.tvPickupOnly, q.optBoolean("pickup_only", false));
         DeliveryQueueActivity.this.bindOtherDeviceChips(h.productChips, this.str(q, "items"), q);
         DeliveryQueueActivity.bindLocationLine(h.tvAdminArea, DeliveryQueueActivity.queueLocationLine(c, q, jarakOther));

         h.btnMore.setOnClickListener((v) -> DeliveryQueueActivity.this.showOtherDeviceMoreMenu(v, q));
         // Sedang diantar → 📥 diganti 🚚 redup; detail yang terbuka tak punya tombol Ambil Alih.
         h.btnTakeOver.setText(delivering ? "🚚" : "📥");
         h.btnTakeOver.setAlpha(delivering ? 0.5F : 1.0F);
         h.btnTakeOver.setOnClickListener((v) -> DeliveryQueueActivity.this.showOtherDeviceOrderDetail(q));
         if (h.btnWaChat != null) {
            String waPhone = q.optString("phone", "");
            boolean hasPhone = !waPhone.isEmpty() && !"null".equals(waPhone);
            h.btnWaChat.setVisibility(hasPhone ? View.VISIBLE : View.GONE);
            h.btnWaChat.setOnClickListener((v) -> DeliveryQueueActivity.openWhatsApp(v.getContext(), waPhone, ""));
         }

         // Mode pilih-banyak: tekan-tahan untuk masuk, lalu ketuk untuk menandai. Tombol per-kartu
         // disembunyikan selama memilih supaya tak ada dua cara bertindak pada kartu yang sama.
         final String trxUuid = this.str(q, "uuid");
         boolean picking = DeliveryQueueActivity.this.claimSelectOther;
         boolean picked = picking && DeliveryQueueActivity.this.claimSelectedOther.contains(trxUuid);
         h.btnMore.setVisibility(picking ? 8 : 0);
         h.btnTakeOver.setVisibility(picking ? 8 : 0);
         // VH tab ini tak menyimpan rujukan kartu (beda dari adapter Tab 3); akarnya memang
         // MaterialCardView, jadi diambil dari itemView saat dibutuhkan saja.
         if (h.itemView instanceof com.google.android.material.card.MaterialCardView) {
            com.google.android.material.card.MaterialCardView cardView =
                  (com.google.android.material.card.MaterialCardView) h.itemView;
            cardView.setStrokeWidth(DeliveryQueueActivity.this.dp(picked ? 3.0F : 1.0F));
            if (picked) {
               cardView.setStrokeColor(DeliveryQueueActivity.this.getResources().getColor(color.primary));
            }
         }
         h.itemView.setOnClickListener((v) -> {
            if (DeliveryQueueActivity.this.claimSelectOther) {
               if (delivering) {
                  DeliveryQueueActivity.this.toastBeingDelivered(q);
                  return;
               }
               DeliveryQueueActivity.this.toggleClaimOther(trxUuid);
               this.notifyItemChanged(h.getBindingAdapterPosition());
            } else {
               DeliveryQueueActivity.this.showOtherDeviceOrderDetail(q);
            }
         });
         h.itemView.setOnLongClickListener((v) -> {
            if (DeliveryQueueActivity.this.claimSelectOther || trxUuid.isEmpty() || delivering) {
               return false;
            }
            DeliveryQueueActivity.this.enterClaimSelect(false);
            if (!DeliveryQueueActivity.this.claimSelectOther) {
               return false;   // belum terhubung server → jangan kunci kartunya
            }
            DeliveryQueueActivity.this.toggleClaimOther(trxUuid);
            this.notifyDataSetChanged();
            return true;
         });
      }

      public int getItemCount() {
         return this.data.size();
      }

      void refreshTimers() {
         for(int i = 0; i < DeliveryQueueActivity.this.rvOtherDevices.getChildCount(); ++i) {
            View child = DeliveryQueueActivity.this.rvOtherDevices.getChildAt(i);
            RecyclerView.ViewHolder vh = DeliveryQueueActivity.this.rvOtherDevices.getChildViewHolder(child);
            int pos = vh.getAdapterPosition();
            if (pos >= 0 && pos < this.data.size() && vh instanceof VH) {
               long ms = DeliveryQueueActivity.elapsedMillis(((JSONObject)this.data.get(pos)).optString("queued_at", (String)null));
               DeliveryQueueActivity.bindElapsedBadge(((VH)vh).tvElapsed, ms);
            }
         }

      }

      class VH extends RecyclerView.ViewHolder {
         TextView tvCustomer;
         TextView tvElapsed;
         TextView tvMeta;
         TextView tvOngkir;
         TextView tvPickupOnly;
         TextView tvAdminArea;
         TextView tvOrderNote;
         LinearLayout productChips;
         MaterialButton btnMore;
         MaterialButton btnTakeOver;
         MaterialButton btnWaChat;
         TextView tvPriorityBig;

         VH(View v) {
            super(v);
            this.tvPriorityBig = (TextView)v.findViewById(id.tvPriorityBig);
            this.tvCustomer = (TextView)v.findViewById(id.tvCustomer);
            this.tvElapsed = (TextView)v.findViewById(id.tvElapsed);
            this.tvMeta = (TextView)v.findViewById(id.tvMeta);
            this.tvOngkir = (TextView)v.findViewById(id.tvOngkir);
            this.tvPickupOnly = (TextView)v.findViewById(id.tvPickupOnly);
            this.tvAdminArea = (TextView)v.findViewById(id.tvAdminArea);
            this.tvOrderNote = (TextView)v.findViewById(id.tvOrderNote);
            this.productChips = (LinearLayout)v.findViewById(id.productChips);
            this.btnMore = (MaterialButton)v.findViewById(id.btnMore);
            this.btnTakeOver = (MaterialButton)v.findViewById(id.btnTakeOver);
            this.btnWaChat = (MaterialButton)v.findViewById(id.btnWaChat);
         }
      }
   }

   private class QueueAdapter extends RecyclerView.Adapter<QueueAdapter.VH> {
      // SENGAJA tetap daftar UTUH (bukan hasil saring pencarian) — dipakai luas di luar kelas ini
      // (aksi massal, jalankan-rit, kartu Strategi, dsb; lihat mis. runStops/applyRunModeChrome)
      // yang semuanya berasumsi "this.adapter.data" = seluruh Antrean Saya. Menyaringnya di sini
      // akan diam-diam menyusutkan cakupan aksi-aksi itu tiap kali kotak cari sedang terisi.
      // Pencarian HANYA menyaring apa yang dirender, lewat shown() di bawah.
      private List<Transaction> data;
      // Dipakai HANYA oleh instance TERPISAH di buildGuidedMyQueueBody (dialog "lihat semua
      // antrean" kurir terpandu) — forceShowAll melewati filter "sedang berjalan" di bawah supaya
      // dialog itu tetap menampilkan SELURUH antrean walau satu rit sedang jalan di layar utama,
      // dan overrideQuery memakai kotak cari MILIK dialog itu sendiri, bukan searchMineQuery global.
      private boolean forceShowAll = false;
      private String overrideQuery = "";

      private QueueAdapter() {
         super();
         this.data = new ArrayList<>();
      }

      void setData(List<Transaction> list) {
         this.data = list != null ? list : new ArrayList<>();
         this.notifyDataSetChanged();
         DeliveryQueueActivity.this.updateMineEmptyState();
      }

      /** Dipanggil dari kotak cari — data mentahnya tak berubah, cuma render ulang apa yang lolos
       *  saring (via shown()). */
      void applyFilter() {
         this.notifyDataSetChanged();
         DeliveryQueueActivity.this.updateMineEmptyState();
      }

      void setForceShowAll(boolean forceShowAll) {
         this.forceShowAll = forceShowAll;
      }

      void setOverrideQuery(String query) {
         this.overrideQuery = query == null ? "" : query.trim();
         this.applyFilter();
      }

      private List<Transaction> shown() {
         List<Transaction> base;
         String q;
         if (this.forceShowAll) {
            base = this.data;
            q = this.overrideQuery.toLowerCase(Locale.US);
         } else {
            if (!DeliveryQueueActivity.this.selectionMode && DeliveryQueueActivity.this.isRunning()) {
               List<Transaction> run = DeliveryQueueActivity.this.runStops();
               base = run.isEmpty() ? this.data : run;
            } else {
               base = this.data;
            }
            q = DeliveryQueueActivity.this.searchMineQuery.toLowerCase(Locale.US);
         }
         boolean complaintOnly = DeliveryQueueActivity.this.complaintOnlyMine;
         int kind = DeliveryQueueActivity.this.kindMine;
         if (q.isEmpty() && !complaintOnly && kind == KIND_ALL && DeliveryQueueActivity.this.slugMine.isEmpty()
               && DeliveryQueueActivity.this.tagFiltersEmpty(DeliveryQueueActivity.this.slugMine)) {
            return base;
         }
         List<Transaction> filtered = new ArrayList<>();
         for (Transaction t : base) {
            if (complaintOnly && !t.isComplained()) continue;
            if (!kindMatches(kind, DeliveryPlanner.isPickupOnly(t))) continue;
            if (!DeliveryQueueActivity.this.slugMatches(DeliveryQueueActivity.this.slugMine, t)) continue;
            if (!DeliveryQueueActivity.this.tagFiltersEmpty(DeliveryQueueActivity.this.slugMine)
                  && !DeliveryQueueActivity.this.tagMatches(DeliveryQueueActivity.this.slugMine, DeliveryQueueActivity.this.trxUuid(t))) continue;
            String name = safe(t.getCustomerName()).toLowerCase(Locale.US);
            String phone = t.getCustomerPhone() != null ? t.getCustomerPhone().toLowerCase(Locale.US) : "";
            if (q.isEmpty() || name.contains(q) || phone.contains(q) || CustomerDao.phoneMatches(phone, q)
                  || safe(t.getCatatan()).toLowerCase(Locale.US).contains(q)) filtered.add(t);
         }
         return filtered;
      }

      void refreshTimers() {
         List<Transaction> vis = this.shown();

         for(int i = 0; i < DeliveryQueueActivity.this.rv.getChildCount(); ++i) {
            View child = DeliveryQueueActivity.this.rv.getChildAt(i);
            RecyclerView.ViewHolder vh = DeliveryQueueActivity.this.rv.getChildViewHolder(child);
            int pos = vh.getAdapterPosition();
            if (pos >= 0 && pos < vis.size() && vh instanceof VH) {
               long ms = DeliveryQueueActivity.elapsedMillis(((Transaction)vis.get(pos)).getDeliveryQueuedAt());
               DeliveryQueueActivity.bindElapsedBadge(((VH)vh).tvElapsed, ms);
            }
         }

      }

      @NonNull
      public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
         View v = LayoutInflater.from(parent.getContext()).inflate(layout.item_other_device_compact, parent, false);
         return new VH(v);
      }

      public void onBindViewHolder(@NonNull VH h, int position) {
         Transaction t = (Transaction)this.shown().get(position);
         boolean full = DeliveryQueueActivity.this.runningIds.contains(t.getId()) && !DeliveryQueueActivity.this.selectionMode;
         h.tvCustomer.setTextSize(17.0F);
         h.tvCustomer.setMaxLines(2);
         h.tvCustomer.setEllipsize(TruncateAt.END);
         boolean voidPending = t.hasPendingVoidRequest();
         DeliveryQueueActivity.bindOrderNote(h.tvOrderNote, voidPending ? null : t.getCatatan());
         // "🤝 📷" = dipesan reseller untuk afiliasinya + foto bukti Selesai WAJIB — kurir tahu sejak
         // di kartu bahwa order ini tak bisa diselesaikan tanpa memotret (📷 saja = flag server tanpa penanda).
         h.tvCustomer.setText(voidPending ? DeliveryQueueActivity.safe(t.getCustomerName()) : (t.isOpenDispatch() ? "\ud83c\udfb2 " : "") + (t.isSelfOrder() ? "\ud83d\udecd️ " : "") + (t.isResellerOrder() ? "🤝 " : "") + (t.requiresDeliveryProof() ? "📷 " : "") + (t.isCustomerPriority() ? "⭐ " : "") + (t.isCustomerDataIncomplete() ? "❗ " : "") + (t.isComplained() ? "\ud83d\ude20 " : "") + DeliveryQueueActivity.safe(t.getCustomerName()));
         int flags = h.tvCustomer.getPaintFlags();
         h.tvCustomer.setPaintFlags(voidPending ? flags | 16 : flags & -17);
         if (voidPending) {
            h.tvPriorityBig.setVisibility(8);
            h.tvPriorityBig.clearAnimation();
            h.tvPriorityBig.setAlpha(1.0F);
            h.tvNoFoto.setVisibility(8);
            h.tvNoLokasi.setVisibility(8);
            // Kartu VOID DIAJUKAN sengaja minimal (meta/umur juga disembunyikan) — dan holder daur
            // ulang tak boleh membawa baris 📍 milik kartu sebelumnya.
            h.tvAdminArea.setVisibility(8);
            h.tvMeta.setText("");
            h.tvMeta.setVisibility(8);
            h.tvVoidTotal.setText(t.getJumlahGalon() + " galon · Rp " + DeliveryQueueActivity.formatRupiah(t.getTotalHarga()));
            h.tvVoidTotal.setVisibility(0);
            DeliveryQueueActivity.this.bindProductChips(h.productChips, t);
            h.tvOngkir.setVisibility(t.getOngkir() > (double)0.0F ? 0 : 8);
            h.tvPickupOnly.setVisibility(8);
            h.tvIssue.setVisibility(8);
            h.tvElapsed.setVisibility(8);
            this.stopBlink(h);
            h.card.setCardBackgroundColor(Color.parseColor("#EEEEEE"));
            h.card.setStrokeColor(Color.parseColor("#BDBDBD"));
            h.card.setStrokeWidth(DeliveryQueueActivity.this.dp(1.0F));
            h.btnMore.setVisibility(8);
            h.btnTakeOver.setVisibility(8);
            h.itemView.setOnClickListener((v) -> DeliveryQueueActivity.this.showOrderDetail(t));
            h.itemView.setOnLongClickListener((v) -> false);
         } else {
            h.tvElapsed.setVisibility(0);
            h.tvMeta.setVisibility(0);
            h.tvVoidTotal.setVisibility(8);
            if (t.isOrderPriority()) {
               String why = t.getOrderPriorityReason();
               h.tvPriorityBig.setText("⚡ PRIORITAS" + (why != null && !why.trim().isEmpty() ? ": " + why.trim() : ""));
               h.tvPriorityBig.setVisibility(0);
               DeliveryQueueActivity.this.blinkViewForever(h.tvPriorityBig);
            } else {
               h.tvPriorityBig.setVisibility(8);
               h.tvPriorityBig.clearAnimation();
               h.tvPriorityBig.setAlpha(1.0F);
            }

            h.tvNoFoto.setVisibility(t.isCustomerNoPhoto() ? 0 : 8);
            h.tvNoLokasi.setVisibility(t.isCustomerNoCoord() ? 0 : 8);
            StringBuilder meta = new StringBuilder();
            String jarak = DeliveryQueueActivity.this.myLat == (double)0.0F && DeliveryQueueActivity.this.myLng == (double)0.0F ? null : DeliveryQueueActivity.formatJarak(DeliveryQueueActivity.distOrInf(t, DeliveryQueueActivity.this.myLat, DeliveryQueueActivity.this.myLng));
            h.jarakLabel = jarak;
            meta.append(t.getJumlahGalon()).append(" galon").append(t.wasManuallyEdited() ? " ✏️" : "").append(" · Rp ").append(DeliveryQueueActivity.formatRupiah(t.getTotalHarga()));
            meta.append(DeliveryQueueActivity.checkoutLegSuffix(t));
            meta.append(DeliveryQueueActivity.receiptSuffix(t.getReceiptNo()));
            meta.append(DeliveryQueueActivity.orderedSuffix(t) + sourceWaSuffix(t.getSourceWa()));
            h.tvMeta.setText(meta.toString());
            DeliveryQueueActivity.this.bindProductChips(h.productChips, t);
            h.tvOngkir.setVisibility(t.getOngkir() > (double)0.0F ? 0 : 8);
            DeliveryQueueActivity.bindPickupOnlyBadge(h.tvPickupOnly, DeliveryQueueActivity.isPickupOnly(t));
            Customer cust = t.getCustomerId() > 0L ? DeliveryQueueActivity.this.customerDao.getById(t.getCustomerId()) : null;
            if (cust != null && cust.hasOpenIssue()) {
               String note = cust.getIssueNote();
               h.tvIssue.setText("\ud83d\udea9 Bermasalah" + (note != null && !note.trim().isEmpty() ? ": " + note.trim() : ""));
               h.tvIssue.setVisibility(0);
            } else {
               h.tvIssue.setVisibility(8);
            }

            DeliveryQueueActivity.bindLocationLine(h.tvAdminArea,
                  DeliveryQueueActivity.queueLocationLine(DeliveryQueueActivity.areaLabel(cust, t), jarak));

            long elapsedMs = DeliveryQueueActivity.elapsedMillis(t.getDeliveryQueuedAt());
            DeliveryQueueActivity.bindElapsedBadge(h.tvElapsed, elapsedMs);
            int bucket = DeliveryQueueActivity.dayBucket(t.getTanggal());
            if (bucket < 0) {
               h.card.setCardBackgroundColor(Color.parseColor("#FFEBEE"));
               h.card.setStrokeColor(Color.parseColor("#E57373"));
            } else if (bucket > 0) {
               h.card.setCardBackgroundColor(Color.parseColor("#E3F2FD"));
               h.card.setStrokeColor(Color.parseColor("#64B5F6"));
            } else {
               h.card.setCardBackgroundColor(-1);
               h.card.setStrokeColor(0);
            }

            // TERLAMBAT menang atas semua sebab kedip lain (data tak lengkap / pesanan terbuka /
            // prioritas) dan memakai stroke lebih tebal, supaya satu kartu gawat tetap terbaca walau
            // kartu di sekitarnya juga berkedip.
            boolean lateNow = DeliveryQueueActivity.isLate(t);
            int blinkHi = lateNow ? 0xFFD32F2F : (t.isCustomerDataIncomplete() ? -1096636 : (t.isOpenDispatch() ? -10262799 : (!t.isCustomerPriority() && !t.isOrderPriority() ? 0 : -19712)));
            boolean isSelectedNow = DeliveryQueueActivity.this.selectionMode && DeliveryQueueActivity.this.selectedIds.contains(t.getId());
            if (isSelectedNow) {
               this.stopBlink(h);
               h.card.setStrokeWidth(DeliveryQueueActivity.this.dp(3.0F));
               h.card.setStrokeColor(DeliveryQueueActivity.this.getResources().getColor(color.primary));
            } else if (blinkHi != 0) {
               h.card.setStrokeWidth(DeliveryQueueActivity.this.dp(lateNow ? 3.0F : 2.0F));
               this.applyBlink(h, blinkHi);
            } else {
               this.stopBlink(h);
               h.card.setStrokeWidth(DeliveryQueueActivity.this.dp(1.0F));
            }

            if (DeliveryQueueActivity.this.selectionMode) {
               h.btnMore.setVisibility(8);
               h.btnTakeOver.setVisibility(8);
            } else {
               h.btnMore.setVisibility(0);
               h.btnTakeOver.setVisibility(0);
               if (full) {
                  h.btnMore.setOnClickListener((v) -> DeliveryQueueActivity.this.showRunningMoreMenu(v, t, h.jarakLabel));
                  h.btnTakeOver.setText("✓");
                  h.btnTakeOver.setContentDescription("Selesai");
                  h.btnTakeOver.setOnClickListener((v) -> DeliveryQueueActivity.this.maybeWarnIncompleteThenComplete(t));
               } else {
                  h.btnMore.setOnClickListener((v) -> DeliveryQueueActivity.this.showCompactMoreMenu(v, t, h.jarakLabel));
                  h.btnTakeOver.setText("▶");
                  h.btnTakeOver.setContentDescription("Jalankan");
                  h.btnTakeOver.setOnClickListener((v) -> DeliveryQueueActivity.this.startRun(t));
               }
            }

            if (DeliveryQueueActivity.this.selectionMode) {
               h.itemView.setOnClickListener((v) -> {
                  DeliveryQueueActivity.this.toggleSelected(t);
                  this.notifyItemChanged(h.getBindingAdapterPosition());
               });
            } else {
               h.itemView.setOnClickListener((v) -> DeliveryQueueActivity.this.showOrderDetail(t));
            }

            h.itemView.setOnLongClickListener((v) -> {
               if (!DeliveryQueueActivity.this.selectionMode && !DeliveryQueueActivity.this.isRunning()) {
                  DeliveryQueueActivity.this.enterSelectionMode();
                  if (!DeliveryQueueActivity.this.selectionMode) {
                     return false;
                  } else {
                     DeliveryQueueActivity.this.toggleSelected(t);
                     this.notifyDataSetChanged();
                     return true;
                  }
               } else {
                  return false;
               }
            });
         }
      }

      public int getItemCount() {
         return this.shown().size();
      }

      public void onViewRecycled(@NonNull VH h) {
         super.onViewRecycled(h);
         this.stopBlink(h);
         h.tvPriorityBig.clearAnimation();
         h.tvPriorityBig.setAlpha(1.0F);
      }

      private void applyBlink(VH h, int colorHi) {
         if (h.blinkColor != colorHi || h.blinkAnim == null || !h.blinkAnim.isStarted()) {
            this.stopBlink(h);
            int colorLo = colorHi & 16777215 | 570425344;
            ValueAnimator anim = ValueAnimator.ofArgb(new int[]{colorHi, colorLo});
            anim.setDuration(600L);
            anim.setRepeatMode(2);
            anim.setRepeatCount(-1);
            anim.addUpdateListener(com.crowja.damiupos.util.LowPowerBlink.stepped(4, (a) -> h.card.setStrokeColor((Integer)a.getAnimatedValue())));
            com.crowja.damiupos.util.LowPowerBlink.track(anim).start();
            h.blinkAnim = anim;
            h.blinkColor = colorHi;
         }
      }

      private void stopBlink(VH h) {
         if (h.blinkAnim != null) {
            h.blinkAnim.cancel();
            h.blinkAnim = null;
         }

         h.blinkColor = 0;
      }

      class VH extends RecyclerView.ViewHolder {
         MaterialCardView card;
         ValueAnimator blinkAnim;
         int blinkColor;
         TextView tvCustomer;
         TextView tvPriorityBig;
         TextView tvMeta;
         TextView tvNoFoto;
         TextView tvNoLokasi;
         TextView tvOngkir;
         TextView tvPickupOnly;
         TextView tvVoidTotal;
         TextView tvIssue;
         TextView tvAdminArea;
         TextView tvElapsed;
         TextView tvOrderNote;
         MaterialButton btnMore;
         MaterialButton btnTakeOver;
         LinearLayout productChips;
         String jarakLabel;

         VH(View v) {
            super(v);
            this.card = (MaterialCardView)v.findViewById(id.card);
            this.tvCustomer = (TextView)v.findViewById(id.tvCustomer);
            this.tvPriorityBig = (TextView)v.findViewById(id.tvPriorityBig);
            this.tvMeta = (TextView)v.findViewById(id.tvMeta);
            this.tvNoFoto = (TextView)v.findViewById(id.tvNoFoto);
            this.tvNoLokasi = (TextView)v.findViewById(id.tvNoLokasi);
            this.tvOngkir = (TextView)v.findViewById(id.tvOngkir);
            this.tvPickupOnly = (TextView)v.findViewById(id.tvPickupOnly);
            this.tvVoidTotal = (TextView)v.findViewById(id.tvVoidTotal);
            this.tvIssue = (TextView)v.findViewById(id.tvIssue);
            this.tvAdminArea = (TextView)v.findViewById(id.tvAdminArea);
            this.tvOrderNote = (TextView)v.findViewById(id.tvOrderNote);
            this.tvElapsed = (TextView)v.findViewById(id.tvElapsed);
            this.btnMore = (MaterialButton)v.findViewById(id.btnMore);
            this.btnTakeOver = (MaterialButton)v.findViewById(id.btnTakeOver);
            this.productChips = (LinearLayout)v.findViewById(id.productChips);
         }
      }
   }

   private class OpenDispatchAdapter extends RecyclerView.Adapter<OpenDispatchAdapter.VH> {
      private List<Transaction> rawData;
      private final List<Transaction> data;

      private OpenDispatchAdapter() {
         super();
         this.rawData = new ArrayList<>();
         this.data = new ArrayList<>();
      }

      void setData(List<Transaction> list) {
         this.rawData = list != null ? list : new ArrayList<>();
         this.applyFilterSort();
      }

      int rawCount() {
         return this.rawData.size();
      }

      void applyFilterSort() {
         this.data.clear();
         String q = DeliveryQueueActivity.this.searchOpenQuery.toLowerCase(Locale.US);
         boolean complaintOnly = DeliveryQueueActivity.this.complaintOnlyOpen;
         int kind = DeliveryQueueActivity.this.kindOpen;

         for(Transaction t : this.rawData) {
            if (complaintOnly && !t.isComplained()) continue;
            if (!kindMatches(kind, DeliveryPlanner.isPickupOnly(t))) continue;
            if (!DeliveryQueueActivity.this.slugMatches(DeliveryQueueActivity.this.slugOpen, t)) continue;
            if (!DeliveryQueueActivity.this.tagFiltersEmpty(DeliveryQueueActivity.this.slugOpen)
                  && !DeliveryQueueActivity.this.tagMatches(DeliveryQueueActivity.this.slugOpen, DeliveryQueueActivity.this.trxUuid(t))) continue;
            if (q.isEmpty()) {
               this.data.add(t);
            } else {
               String name = DeliveryQueueActivity.safe(t.getCustomerName()).toLowerCase(Locale.US);
               String phone = t.getCustomerPhone() != null ? t.getCustomerPhone().toLowerCase(Locale.US) : "";
               if (name.contains(q) || phone.contains(q) || CustomerDao.phoneMatches(phone, q)
                     || DeliveryQueueActivity.safe(t.getCatatan()).toLowerCase(Locale.US).contains(q)) {
                  this.data.add(t);
               }
            }
         }

         if (DeliveryQueueActivity.this.sortOpenMode == 1) {
            this.data.sort((a, b) -> Integer.compare(b.getJumlahGalon(), a.getJumlahGalon()));
         } else if (DeliveryQueueActivity.this.sortOpenMode == 2) {
            // Menunggu PALING LAMA duluan — sama seperti badge umur ⏱ tiap kartu.
            this.data.sort((a, b) -> Long.compare(elapsedMillis(b.getDeliveryQueuedAt()), elapsedMillis(a.getDeliveryQueuedAt())));
         } else if (DeliveryQueueActivity.this.myLat != (double)0.0F || DeliveryQueueActivity.this.myLng != (double)0.0F) {
            this.data.sort((a, b) -> Double.compare(DeliveryQueueActivity.distOrInf(a, DeliveryQueueActivity.this.myLat, DeliveryQueueActivity.this.myLng), DeliveryQueueActivity.distOrInf(b, DeliveryQueueActivity.this.myLat, DeliveryQueueActivity.this.myLng)));
         }

         this.notifyDataSetChanged();
         DeliveryQueueActivity.this.updateOpenEmptyState();
         DeliveryQueueActivity.this.setTabSummary(DeliveryQueueActivity.this.tvOpenSummary,
               this.data.size(), this.rawData.size(), DeliveryQueueActivity.totalGalonOf(this.data));
      }

      void refreshTimers() {
         for(int i = 0; i < DeliveryQueueActivity.this.rvOpenDispatch.getChildCount(); ++i) {
            View child = DeliveryQueueActivity.this.rvOpenDispatch.getChildAt(i);
            RecyclerView.ViewHolder vh = DeliveryQueueActivity.this.rvOpenDispatch.getChildViewHolder(child);
            int pos = vh.getAdapterPosition();
            if (pos >= 0 && pos < this.data.size() && vh instanceof VH) {
               long ms = DeliveryQueueActivity.elapsedMillis(((Transaction)this.data.get(pos)).getDeliveryQueuedAt());
               DeliveryQueueActivity.bindElapsedBadge(((VH)vh).tvElapsed, ms);
            }
         }

      }

      @NonNull
      public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
         View v = LayoutInflater.from(parent.getContext()).inflate(layout.item_other_device_compact, parent, false);
         return new VH(v);
      }

      public void onBindViewHolder(@NonNull VH h, int position) {
         Transaction t = (Transaction)this.data.get(position);
         DeliveryQueueActivity.bindOrderNote(h.tvOrderNote, t.getCatatan());
         h.tvCustomer.setText((t.isOrderPriority() ? "⚡ " : "") + (t.isCustomerPriority() ? "⭐ " : "") + (t.isCustomerDataIncomplete() ? "❗ " : "") + (t.isComplained() ? "😠 " : "") + DeliveryQueueActivity.safe(t.getCustomerName()));
         DeliveryQueueActivity.this.bindPriorityLine(h.tvPriorityBig, t.isOrderPriority(), t.getOrderPriorityReason());
         StringBuilder meta = new StringBuilder();
         String phone = t.getCustomerPhone();
         if (phone != null && !phone.trim().isEmpty()) {
            meta.append("\ud83d\udcde ").append(phone.trim());
         }

         String jarak = DeliveryQueueActivity.this.myLat == (double)0.0F && DeliveryQueueActivity.this.myLng == (double)0.0F ? null : DeliveryQueueActivity.formatJarak(DeliveryQueueActivity.distOrInf(t, DeliveryQueueActivity.this.myLat, DeliveryQueueActivity.this.myLng));
         meta.append(meta.length() > 0 ? " · " : "").append(t.getJumlahGalon()).append(" galon");
         meta.append(DeliveryQueueActivity.checkoutLegSuffix(t));
         meta.append(DeliveryQueueActivity.receiptSuffix(t.getReceiptNo()));
         meta.append(DeliveryQueueActivity.orderedSuffix(t) + sourceWaSuffix(t.getSourceWa()));
         h.tvMeta.setText(meta.toString());
         h.tvOngkir.setVisibility(t.getOngkir() > (double)0.0F ? 0 : 8);
         DeliveryQueueActivity.bindPickupOnlyBadge(h.tvPickupOnly, DeliveryQueueActivity.isPickupOnly(t));
         DeliveryQueueActivity.this.bindProductChips(h.productChips, t);
         Customer custAA = t.getCustomerId() > 0L ? DeliveryQueueActivity.this.customerDao.getById(t.getCustomerId()) : null;
         DeliveryQueueActivity.bindLocationLine(h.tvAdminArea,
               DeliveryQueueActivity.queueLocationLine(DeliveryQueueActivity.areaLabel(custAA, t), jarak));

         boolean lateOpen = DeliveryQueueActivity.isLate(t);
         if (lateOpen) {
            this.startCardBlink(h, 0xFFD32F2F);
         } else if (t.isOrderPriority()) {
            this.startCardBlink(h, -19712);
         } else {
            this.stopCardBlink(h);
         }

         long ms = DeliveryQueueActivity.elapsedMillis(t.getDeliveryQueuedAt());
         DeliveryQueueActivity.bindElapsedBadge(h.tvElapsed, ms);
         String jarakSuffix = jarak != null ? " (" + jarak + ")" : "";
         h.btnMore.setOnClickListener((v) -> {
            PopupMenu menu = new PopupMenu(DeliveryQueueActivity.this, v);
            menu.getMenu().add(0, 1, 0, "\ud83d\udd0d Preview" + jarakSuffix);
            if ("JUAL".equals(t.getType())) {
               menu.getMenu().add(0, 2, 1, "\ud83d\udcac Chat Pesanan");
               menu.getMenu().add(0, 3, 2, "🚰 Jenis Galon");
            }
            menu.setOnMenuItemClickListener((item) -> {
               switch (item.getItemId()) {
                  case 3:
                     DeliveryQueueActivity.this.showGalonTypeDialog(t);
                     return true;
                  case 1:
                     DeliveryQueueActivity.this.showQueuePreview(t);
                     return true;
                  case 2:
                     DeliveryQueueActivity.this.openOrderChat(t);
                     return true;
                  default:
                     return false;
               }
            });
            menu.show();
         });
         h.btnClaim.setText("\ud83d\ude4b");
         h.btnClaim.setContentDescription("Ambil Alih");
         h.btnClaim.setOnClickListener((v) -> DeliveryQueueActivity.this.confirmAmbilAlih(t));

         // Mode pilih-banyak — perilaku & tampilannya sama persis dengan Tab 2.
         boolean pickingOpen = DeliveryQueueActivity.this.claimSelectOpen;
         boolean pickedOpen = pickingOpen && DeliveryQueueActivity.this.claimSelectedOpen.contains(t.getId());
         h.btnMore.setVisibility(pickingOpen ? 8 : 0);
         h.btnClaim.setVisibility(pickingOpen ? 8 : 0);
         h.card.setStrokeWidth(DeliveryQueueActivity.this.dp(pickedOpen || lateOpen ? 3.0F : 1.0F));
         if (pickedOpen) {
            h.card.setStrokeColor(DeliveryQueueActivity.this.getResources().getColor(color.primary));
         }
         h.itemView.setOnClickListener((v) -> {
            if (DeliveryQueueActivity.this.claimSelectOpen) {
               DeliveryQueueActivity.this.toggleClaimOpen(t.getId());
               this.notifyItemChanged(h.getBindingAdapterPosition());
            } else {
               DeliveryQueueActivity.this.showOrderDetail(t);
            }
         });
         h.itemView.setOnLongClickListener((v) -> {
            if (DeliveryQueueActivity.this.claimSelectOpen) {
               return false;
            }
            DeliveryQueueActivity.this.enterClaimSelect(true);
            if (!DeliveryQueueActivity.this.claimSelectOpen) {
               return false;
            }
            DeliveryQueueActivity.this.toggleClaimOpen(t.getId());
            this.notifyDataSetChanged();
            return true;
         });
      }

      public int getItemCount() {
         return this.data.size();
      }

      /** Kedip border kartu; warnanya jadi parameter agar "terlambat" (merah) dan "prioritas"
       *  (oranye) berbagi satu mekanisme, bukan menambah mekanisme kedip kelima di layar ini. */
      private void startCardBlink(VH h, int colorHi) {
         if (h.priorityBlink == null || !h.priorityBlink.isStarted() || h.blinkColor != colorHi) {
            this.stopCardBlink(h);
            ValueAnimator anim = ValueAnimator.ofArgb(new int[]{colorHi, colorHi & 16777215 | 570425344});
            anim.setDuration(700L);
            anim.setRepeatMode(2);
            anim.setRepeatCount(-1);
            anim.addUpdateListener(com.crowja.damiupos.util.LowPowerBlink.stepped(4, (a) -> h.card.setStrokeColor((Integer)a.getAnimatedValue())));
            com.crowja.damiupos.util.LowPowerBlink.track(anim).start();
            h.priorityBlink = anim;
            h.blinkColor = colorHi;
         }
      }

      private void stopCardBlink(VH h) {
         if (h.priorityBlink != null) {
            h.priorityBlink.cancel();
            h.priorityBlink = null;
         }

         h.blinkColor = 0;
         h.card.setStrokeColor(h.defaultStrokeColor);
      }

      public void onViewRecycled(@NonNull VH h) {
         super.onViewRecycled(h);
         this.stopCardBlink(h);
         if (h.tvPriorityBig != null) {
            h.tvPriorityBig.clearAnimation();
            h.tvPriorityBig.setAlpha(1.0F);
         }
      }

      class VH extends RecyclerView.ViewHolder {
         MaterialCardView card;
         TextView tvCustomer;
         TextView tvMeta;
         TextView tvOngkir;
         TextView tvPickupOnly;
         TextView tvAdminArea;
         TextView tvElapsed;
         TextView tvOrderNote;
         MaterialButton btnClaim;
         MaterialButton btnMore;
         LinearLayout productChips;
         TextView tvPriorityBig;
         ValueAnimator priorityBlink;
         int blinkColor;
         final int defaultStrokeColor;

         VH(View v) {
            super(v);
            this.tvPriorityBig = (TextView)v.findViewById(id.tvPriorityBig);
            this.card = (MaterialCardView)v.findViewById(id.card);
            this.tvCustomer = (TextView)v.findViewById(id.tvCustomer);
            this.tvMeta = (TextView)v.findViewById(id.tvMeta);
            this.tvOngkir = (TextView)v.findViewById(id.tvOngkir);
            this.tvPickupOnly = (TextView)v.findViewById(id.tvPickupOnly);
            this.tvAdminArea = (TextView)v.findViewById(id.tvAdminArea);
            this.tvOrderNote = (TextView)v.findViewById(id.tvOrderNote);
            this.tvElapsed = (TextView)v.findViewById(id.tvElapsed);
            this.btnClaim = (MaterialButton)v.findViewById(id.btnTakeOver);
            this.btnMore = (MaterialButton)v.findViewById(id.btnMore);
            this.productChips = (LinearLayout)v.findViewById(id.productChips);
            this.defaultStrokeColor = this.card.getStrokeColor();
         }
      }
   }
}
