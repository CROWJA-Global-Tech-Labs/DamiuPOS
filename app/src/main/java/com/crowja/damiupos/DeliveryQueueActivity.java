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
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;
import com.crowja.damiupos.util.CameraIntents;

public class DeliveryQueueActivity extends AppCompatActivity {
   private static final SimpleDateFormat SDF_PARSE;
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
   private final BroadcastReceiver syncedReceiver = new BroadcastReceiver() {
      public void onReceive(Context context, Intent intent) {
         if (!DeliveryQueueActivity.this.selectionMode) {
            DeliveryQueueActivity.this.loadData();
         }

      }
   };
   private boolean contactPermAsked = false;
   private static final int REQ_PERM_CONTACTS = 7401;
   private long pendingProofTrxId = -1L;
   private boolean pendingProofRevoke = false;
   private String pendingProofPath;
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
      if (savedInstanceState != null) {
         this.pendingProofTrxId = savedInstanceState.getLong("proof_trx_id", -1L);
         this.pendingProofRevoke = savedInstanceState.getBoolean("proof_revoke", false);
         this.pendingProofPath = savedInstanceState.getString("proof_path");
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
         fabNavigasiRit.setOnClickListener((v) -> this.navigasiRitAktif());
      }

      this.barRute = this.findViewById(id.barRute);
      this.tvSelCount = (TextView)this.findViewById(id.tvSelCount);
      this.btnJalankanBanyak = (MaterialButton)this.findViewById(id.btnJalankanBanyak);
      this.btnJalankanBanyak.setOnClickListener((v) -> this.runSelectedTogether());
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
         strategyAll.setOnClickListener((v) -> this.showFullStrategy());
      }

      View btnJalankanRit = this.findViewById(id.btnJalankanRit);
      if (btnJalankanRit != null) {
         btnJalankanRit.setOnClickListener((v) -> this.runStrategyTrip());
      }

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
      } else if (item.getItemId() == id.action_toggle_strategy) {
         this.toggleStrategyHidden();
         return true;
      } else {
         return super.onOptionsItemSelected(item);
      }
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

      (new AlertDialog.Builder(this)).setTitle("Jalankan " + stops.size() + " order sekaligus?").setMessage("Semua order ini ditandai SEDANG DIANTAR sebagai satu rit. Antar satu per satu, tandai ✓ Selesai pada tiap kartu." + (skippedOpen > 0 ? "\n\n" + skippedOpen + " Pesanan Terbuka dilewati (klaim dulu satu per satu)." : "") + "\n" + names).setPositiveButton("Jalankan", (d, w) -> {
         this.exitSelectionMode();
         this.doStartRuns(stops);
      }).setNegativeButton("Batal", (DialogInterface.OnClickListener)null).show();
   }

   private void updateSelectionUi() {
      int n = this.selectedIds.size();
      this.tvSelCount.setText(n + " dipilih");
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
         anim.addUpdateListener((a) -> borderFinal.setAlpha((Integer) a.getAnimatedValue()));
         anim.start();
         this.guidedMapBorderAnim = anim;
      }
   }

   protected void onResume() {
      super.onResume();
      SettingsDao lateCfg = new SettingsDao(DatabaseHelper.getInstance(this));
      lateMs = (long)lateCfg.getDeliveryMaxAgeMinutes() * 60000L;
      this.revokeLateCredit = lateCfg.isRevokeCreditLateEnabled();
      this.loadData();
      this.loadOtherDevices();
      this.tick.postDelayed(this.ticker, 1000L);
      this.tick.postDelayed(this.strategyResortTicker, 60000L);
      if (this.guidedMode) {
         this.tick.postDelayed(this.guidedRitRefillTicker, 30000L);
         VersionUpdater.maybePrompt(this);
         VersionUpdater.maybePromptBlocked(this);
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
      this.tick.removeCallbacks(this.ticker);
      this.tick.removeCallbacks(this.strategyResortTicker);
      this.tick.removeCallbacks(this.guidedRitRefillTicker);

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
               this.applyFilteredLists(sortByDistance(base, this.myLat, this.myLng));
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
            } catch (Exception var6) {
               failed = true;
            }

            final JSONArray queueF = queue;
            final boolean failedF = failed;
            this.runOnUiThread(() -> {
               if (!this.isFinishing() && !this.isDestroyed()) {
                  this.otherDevicesAdapter.setData(queueF);
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
         } else {
            tv.setText("\ud83d\udcdd " + note);
            tv.setVisibility(View.VISIBLE);
         }
      }
   }

   private void bindOtherDeviceChips(LinearLayout box, String itemsCsv) {
      String[] parts = itemsCsv != null && !itemsCsv.trim().isEmpty() ? itemsCsv.split(",\\s*") : new String[0];
      int shown = 0;

      for(String part : parts) {
         String raw = part.trim();
         if (!raw.isEmpty()) {
            Matcher m = ITEM_LABEL_PATTERN.matcher(raw);
            String name = m.matches() ? m.group(1).trim() : raw;
            String qty = m.matches() ? m.group(2) : null;
            Product p = (Product)this.productByName.get(normProductName(name));
            String slug = p != null ? p.getSlug() : null;
            String label = slug != null && !slug.trim().isEmpty() ? slug.trim() : (name.length() <= 10 ? name : name.substring(0, 10).trim() + "…");
            int bg = -6511697;
            if (p != null && p.getColor() != null && !p.getColor().trim().isEmpty()) {
               try {
                  bg = Color.parseColor(p.getColor().trim());
               } catch (IllegalArgumentException var18) {
                  bg = TransactionAdapter.paletteColor(name);
               }
            } else if (p == null) {
               bg = TransactionAdapter.paletteColor(name);
            }

            TextView chip = this.chipAt(box, shown);
            chip.setText(qty != null ? label + " ×" + qty : label);
            chip.setTextColor(chipTextColor(bg));
            ((GradientDrawable)chip.getBackground()).setColor(bg);
            chip.setVisibility(0);
            ++shown;
         }
      }

      for(int i = shown; i < box.getChildCount(); ++i) {
         box.getChildAt(i).setVisibility(8);
      }

      box.setVisibility(shown > 0 ? 0 : 8);
   }

   private void showOtherDeviceMoreMenu(View anchor, JSONObject q) {
      PopupMenu menu = new PopupMenu(this, anchor);
      double lat = q.optDouble("latitude", (double)0.0F);
      double lng = q.optDouble("longitude", (double)0.0F);
      String jarakSuffix = Double.isNaN(this.otherLat) || Double.isNaN(this.otherLng) || lat == (double)0.0F && lng == (double)0.0F ? "" : " (" + formatJarak(haversineKmOtherDevices(this.otherLat, this.otherLng, lat, lng)) + ")";
      menu.getMenu().add(0, 1, 0, "\ud83d\udd0d Preview" + jarakSuffix);
      menu.getMenu().add(0, 2, 1, "\ud83d\udd52 Jadwalkan Ulang");
      menu.setOnMenuItemClickListener((item) -> {
         switch (item.getItemId()) {
            case 1:
               this.showOtherDevicePreview(q);
               return true;
            case 2:
               this.showPostponeSchedulePickerOther(q);
               return true;
            default:
               return false;
         }
      });
      menu.show();
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
            Date d = SDF_PARSE.parse(queuedAt.substring(0, 19));
            if (d == null) {
               return "";
            } else {
               long m = Math.max(0L, System.currentTimeMillis() - d.getTime()) / 60000L;
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

   private void confirmTakeOverOtherDevices(JSONObject q) {
      String uuid = q.optString("uuid", "");
      String name = q.optString("name", "Pelanggan");
      String fromDevice = q.optString("device_group_label", "");
      boolean running = q.optBoolean("in_progress", false);
      StringBuilder msg = new StringBuilder();
      msg.append("Order \"").append(name).append("\" akan DIPINDAHKAN dari perangkat ").append(!fromDevice.isEmpty() && !fromDevice.equals("null") ? "\"" + fromDevice + "\"" : "lain").append(" ke perangkat Anda.\n\n");
      if (running) {
         msg.append("⚠️ Order ini SEDANG DIKERJAKAN kurir tersebut — pastikan sudah ada kesepakatan sebelum mengambilnya.\n\n");
      }

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
      (new AlertDialog.Builder(this)).setTitle("Detail Order \u2014 " + name).setMessage(this.otherDeviceOrderDetailText(q)).setPositiveButton("\ud83d\udce5 Ambil Alih", (d, w) -> this.confirmTakeOverOtherDevices(q)).setNeutralButton("\ud83d\udd0d Preview", (d, w) -> this.showOtherDevicePreview(q)).setNegativeButton("Kembali", (DialogInterface.OnClickListener)null).show();
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
      String adminArea = c != null ? c.getAdminArea() : "";
      String areaSuffix = !adminArea.isEmpty() ? " (" + adminArea + ")" : "";
      String custAddress = c != null && c.getAddress() != null ? c.getAddress().trim() : "";
      String address = !destLocName.trim().isEmpty() ? "Kirim Ke: " + destLocName.trim() + areaSuffix : (!custAddress.isEmpty() ? custAddress + areaSuffix : "");

      // Foto lokasi: nama lokasi cocok persis (kalau ada) menang atas foto default pelanggan -
      // cermin showQueuePreview di antrean sendiri.
      String destPhotoUrl = null;
      if (c != null && !destLocName.trim().isEmpty() && c.getLocations() != null) {
         for (Customer.Location l : c.getLocations()) {
            if (destLocName.trim().equalsIgnoreCase(safe(l.name)) && l.photo != null && !l.photo.trim().isEmpty()) {
               destPhotoUrl = l.photo.trim();
               break;
            }
         }
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
      d.photoLocalPath = destPhotoUrl == null && c != null ? c.getPhotoPath() : null;
      d.photoUrl = destPhotoUrl != null ? destPhotoUrl : (c != null ? c.getPhotoUrl() : null);
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
      String itemsStr = q.optString("items", "");
      if (!itemsStr.isEmpty() && !itemsStr.equals("null")) {
         for (String part : itemsStr.split(",")) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            int xi = p.lastIndexOf('×');
            String namedQty = xi > 0 ? p.substring(0, xi).trim() : p;
            String num = xi > 0 ? p.substring(xi + 1).trim() : "";
            int sp = namedQty.lastIndexOf(' ');
            String name = sp > 0 ? namedQty.substring(0, sp).trim() : namedQty;
            Product prod = this.productByName.get(normProductName(name));
            String slug = prod != null ? prod.getSlug() : null;
            String label = slug != null && !slug.trim().isEmpty() ? slug.trim()
                  : (name.length() <= 10 ? name : name.substring(0, 10).trim() + "…");
            int bg = TransactionAdapter.paletteColor(name);
            if (prod != null && prod.getColor() != null && !prod.getColor().trim().isEmpty()) {
               try {
                  bg = Color.parseColor(prod.getColor().trim());
               } catch (IllegalArgumentException ignored) {
               }
            }
            chips.add(new Chip(label + (num.isEmpty() ? "" : " ×" + num), bg));
         }
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
               ((TextView)this.findViewById(id.tvStrategyTitle)).setText("\ud83d\ude9a Strategi Pengiriman — Rit 1 dari " + trips.size());
               String muat = "Muat " + first.galon + " galon" + (maxLoad > 0 ? " dari " + maxLoad : "") + " · " + first.stops.size() + " pemberhentian";
               if (maxLoad <= 0) {
                  muat = muat + "\n⚠️ Muatan Max belum diatur admin — seluruh antrean dianggap satu rit.";
               } else if (first.overflow) {
                  muat = muat + "\n⚠️ Order ini sendiri melebihi muatan max.";
               }

               ((TextView)this.findViewById(id.tvStrategySummary)).setText(muat);
               ((TextView)this.findViewById(id.tvStrategyStops)).setText(this.tripLines(first, 4));
               card.setVisibility(this.strategyHidden ? 8 : 0);
               this.applyStrategyCollapsed();
               if (this.strategyHidden) {
                  this.stopStrategyBlink();
               } else {
                  this.startStrategyBlink(card);
               }
            }
         }
      }
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

            sb.append("▶ RIT ").append(t.n).append(" — ").append(t.galon).append(" galon").append(maxLoad > 0 ? " / " + maxLoad : "").append(t.overflow ? "  ⚠️ melebihi muatan" : "").append('\n').append(this.tripLines(t, 0));
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
         anim.addUpdateListener((a) -> card.setStrokeColor((Integer)a.getAnimatedValue()));
         anim.start();
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
            for(Long id : gone) {
               this.dao.stopDelivery(id);
            }

            this.runningIds.removeAll(gone);
            this.persistRunning();
            SyncScheduler.syncNow(this.getApplicationContext());
         }
      }

      List<Transaction> ordered = this.buildStrategyOrderedList(list);
      this.adapter.setData(ordered);
      this.renderStrategy(ordered);
      this.applyRunModeChrome(list);
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

   private void bindProductChips(LinearLayout box, Transaction t) {
      List<TransactionItem> items = t.getItems();
      if (items != null && !items.isEmpty()) {
         int shown = 0;
         int hidden = 0;

         for(TransactionItem it : items) {
            if (it != null && it.jumlah > 0) {
               if (shown >= 4) {
                  ++hidden;
               } else {
                  TextView chip = this.chipAt(box, shown);
                  int bg = this.chipColor(it);
                  chip.setText(this.chipLabel(it) + " ×" + it.jumlah);
                  chip.setTextColor(chipTextColor(bg));
                  ((GradientDrawable)chip.getBackground()).setColor(bg);
                  chip.setVisibility(0);
                  ++shown;
               }
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
   private TextView makeDetailBadge(String label, int bg) {
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

   private void requireSelfOrderStrukThen(List<Transaction> taken) {
      if (taken != null) {
         Transaction target = null;
         LinkedHashSet<Long> sent = (new SettingsDao(DatabaseHelper.getInstance(this))).getSelfOrderStrukSentIds();

         for(Transaction t : taken) {
            if (t != null && t.isSelfOrder() && !sent.contains(t.getId())) {
               target = t;
               break;
            }
         }

         if (target != null) {
            AlertDialog dlg = (new AlertDialog.Builder(this)).setIcon(17301545).setCancelable(false).setTitle("\ud83d\udce4 WAJIB: Kirim Struk WA").setMessage("\"" + safe(target.getCustomerName()) + "\" memesan SENDIRI lewat Order Online, jadi pesanannya kita prioritaskan.\n\nKirim struk WhatsApp ke pelanggan sekarang supaya ia tahu pesanannya sedang diantar.").setPositiveButton("BUKA WHATSAPP", (DialogInterface.OnClickListener)null).setNeutralButton("SUDAH SAYA KIRIM", (DialogInterface.OnClickListener)null).create();
            final Transaction targetF = target;
            dlg.setOnShowListener((d) -> {
               dlg.getButton(-1).setOnClickListener((v) -> this.startActivity((new Intent(this, ReceiptActivity.class)).putExtra("transaction_id", targetF.getId()).putExtra("defer_customer_send", true)));
               dlg.getButton(-3).setOnClickListener((v) -> {
                  SettingsDao sd = new SettingsDao(DatabaseHelper.getInstance(this));
                  LinkedHashSet<Long> ids = sd.getSelfOrderStrukSentIds();
                  ids.add(targetF.getId());
                  sd.setSelfOrderStrukSentIds(ids);
                  dlg.dismiss();
                  this.requireSelfOrderStrukThen(taken);
               });
            });
            dlg.show();
         }
      }
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

               (new AlertDialog.Builder(this)).setIcon(17301659).setTitle("Muatan masih longgar").setMessage("Order ini hanya " + load + " galon dari muatan " + maxLoad + " galon. Berangkat sekarang berarti bolak-balik ke cabang lagi untuk sisa antrean.\n\nStrategi Pengiriman menyarankan " + rec.size() + " order sekaligus (" + recGalon + " dari " + maxLoad + " galon):\n" + this.numberedNames(rec)).setPositiveButton("▶ Jalankan " + rec.size() + " order", (d, w) -> this.doStartRuns(rec)).setNeutralButton("Pilih sendiri", (d, w) -> {
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

   private void runStrategyTrip() {
      if (this.strategyTrips != null && !this.strategyTrips.isEmpty()) {
         List<Transaction> stops = new ArrayList();
         int skippedOpen = 0;

         for(Transaction t : ((DeliveryPlanner.Trip)this.strategyTrips.get(0)).stops) {
            if (t != null) {
               if (t.isOpenDispatch()) {
                  ++skippedOpen;
               } else {
                  stops.add(t);
               }
            }
         }

         if (stops.isEmpty()) {
            Toast.makeText(this, skippedOpen > 0 ? "Rit ini isinya Pesanan Terbuka — klaim dulu satu per satu." : "Rit ini kosong.", 1).show();
         } else {
            (new AlertDialog.Builder(this)).setTitle("Jalankan Rit 1 (" + stops.size() + " order)?").setMessage(this.numberedNames(stops) + "\n\nSemua ditandai SEDANG DIANTAR sebagai satu rit. Antar satu per satu, tandai ✓ Selesai pada tiap kartu." + (skippedOpen > 0 ? "\n\n" + skippedOpen + " Pesanan Terbuka dilewati (klaim dulu satu per satu)." : "")).setPositiveButton("Jalankan", (d, w) -> this.doStartRuns(stops)).setNegativeButton("Batal", (DialogInterface.OnClickListener)null).show();
         }
      } else {
         Toast.makeText(this, "Strategi belum tersedia.", 0).show();
      }
   }

   private void doStartRun(Transaction t) {
      this.doStartRuns(Collections.singletonList(t));
   }

   private void doStartRuns(List<Transaction> list) {
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
         this.requireSelfOrderStrukThen(list);
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

   private void showRunningMoreMenu(View anchor, Transaction t, String jarakLabel) {
      PopupMenu menu = new PopupMenu(this, anchor);
      menu.getMenu().add(0, 1, 0, "\u2190 Kembali");
      menu.getMenu().add(0, 2, 1, jarakLabel != null ? "\ud83d\udd0d " + jarakLabel + " \u00b7 Preview" : "\ud83d\udd0d Preview");
      menu.getMenu().add(0, 3, 2, "\ud83d\udcac Chat WA");
      menu.setOnMenuItemClickListener((item) -> {
         switch (item.getItemId()) {
            case 1:
               this.confirmStopRun();
               return true;
            case 2:
               this.showQueuePreview(t);
               return true;
            case 3:
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
      menu.setOnMenuItemClickListener((item) -> {
         switch (item.getItemId()) {
            case 1:
               this.promptMarkPriority(t);
               return true;
            case 2:
               this.showQueuePreview(t);
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
      for(Long id : this.runningIds) {
         this.dao.stopDelivery(id);
      }

      this.clearRunning();
      this.adapter.notifyDataSetChanged();
      this.renderStrategy(this.adapter.data);
      this.applyRunModeChrome(this.adapter.data);
      SyncScheduler.syncNow(this.getApplicationContext());
   }

   private List<Transaction> runStops() {
      List<Transaction> out = new ArrayList();
      if (!this.isRunning()) {
         return out;
      } else {
         Map<Long, Transaction> byId = new HashMap<>();

         for(Transaction t : this.adapter.data) {
            byId.put(t.getId(), t);
         }

         for(Long id : this.runningIds) {
            Transaction t = (Transaction)byId.get(id);
            if (t != null) {
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

      this.updateGuidedProductBadges(stops);
      Transaction run = stops.isEmpty() ? null : (Transaction)stops.get(0);
      View cardStrategy = this.findViewById(id.cardStrategy);
      if (cardStrategy != null && run != null) {
         cardStrategy.setVisibility(8);
      }

      if (this.tabs != null) {
         this.tabs.setVisibility(run != null || this.guidedMode ? 8 : 0);
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

      OtherQueueRow(JSONObject json, Transaction trx, String badgeName, String note, String meta,
            boolean hasOngkir, String adminAreaText, String itemsCsv, long elapsedMs, double distKm) {
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
      String deviceLabel = o.optString("device_group_label", "");
      if (!deviceLabel.isEmpty()) meta.append("📱 ").append(deviceLabel);
      meta.append(meta.length() > 0 ? " · " : "").append(o.optInt("galon", 0)).append(" galon");
      double ongkir = o.optDouble("ongkir", 0.0);
      String adminArea = c != null ? c.getAdminArea() : "";
      return new OtherQueueRow(o, null, badgeName, o.optString("note", ""), meta.toString(),
            ongkir > 0.0, !adminArea.isEmpty() ? "📍 " + adminArea : "", o.optString("items", ""),
            elapsedMs, distKm);
   }

   private OtherQueueRow rowFromTransaction(Transaction t) {
      String badgeName = (t.isOpenDispatch() ? "🎲 " : "") + (t.isCustomerPriority() ? "⭐ " : "")
            + (t.isCustomerDataIncomplete() ? "❗ " : "") + safe(t.getCustomerName());
      long elapsedMs = elapsedMillis(t.getDeliveryQueuedAt());
      double distKm = distOrInf(t, this.myLat, this.myLng);
      String meta = t.getJumlahGalon() + " galon · Rp " + formatRupiah(t.getTotalHarga());
      Customer c = t.getCustomerId() > 0 ? this.customerDao.getById(t.getCustomerId()) : null;
      String adminArea = c != null ? c.getAdminArea() : "";
      return new OtherQueueRow(null, t, badgeName, t.getCatatan(), meta, t.getOngkir() > 0.0,
            adminArea.isEmpty() ? "" : "📍 " + adminArea, null, elapsedMs, distKm);
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

      OtherQueueRowAdapter(Dialog hostDialog) {
         this.hostDialog = hostDialog;
      }

      void setData(List<OtherQueueRow> rows, boolean byDistance) {
         this.data = rows != null ? new ArrayList<>(rows) : new ArrayList<>();
         this.sort(byDistance);
      }

      void sort(boolean byDistance) {
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
         if (row.itemsCsv != null) {
            DeliveryQueueActivity.this.bindOtherDeviceChips(h.productChips, row.itemsCsv);
         } else {
            DeliveryQueueActivity.this.bindProductChips(h.productChips, row.trx);
         }
         if (!row.adminAreaText.isEmpty()) {
            h.tvAdminArea.setText(row.adminAreaText);
            h.tvAdminArea.setVisibility(View.VISIBLE);
         } else {
            h.tvAdminArea.setVisibility(View.GONE);
         }
         h.btnMore.setVisibility(View.GONE);
         h.btnTakeOver.setText("📥");
         h.btnTakeOver.setOnClickListener((v) -> {
            this.hostDialog.dismiss();
            DeliveryQueueActivity.this.claimOtherQueueRow(row);
         });
         h.itemView.setOnClickListener((v) -> DeliveryQueueActivity.this.openOtherQueueRowDetail(row));
      }

      public int getItemCount() {
         return this.data.size();
      }

      class VH extends RecyclerView.ViewHolder {
         TextView tvCustomer, tvElapsed, tvMeta, tvOngkir, tvAdminArea, tvOrderNote;
         LinearLayout productChips;
         MaterialButton btnMore, btnTakeOver;

         VH(View v) {
            super(v);
            this.tvCustomer = v.findViewById(id.tvCustomer);
            this.tvElapsed = v.findViewById(id.tvElapsed);
            this.tvMeta = v.findViewById(id.tvMeta);
            this.tvOngkir = v.findViewById(id.tvOngkir);
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

   private static List<Transaction> sortByDistance(List<Transaction> base, double olat, double olng) {
      List<Transaction> out = new ArrayList(base);
      Collections.sort(out, (a, b) -> {
         if (isPickupOnly(a) != isPickupOnly(b)) {
            return isPickupOnly(a) ? 1 : -1;
         } else if (isLate(a) != isLate(b)) {
            // SLA yang sudah jebol mengalahkan preferensi: pesanan TERLAMBAT naik di atas pelanggan
            // prioritas. Bila keduanya terlambat, kunci berikutnya (prioritas lalu FIFO) yang
            // memutuskan, jadi pelanggan bintang tak dirugikan. Kunci yang SAMA disisipkan di posisi
            // yang SAMA pada ORDER BY SQL (TransactionDao.getDeliveryQueue) -- kalau hanya salah satu
            // diubah, urutan kartu melompat begitu fix GPS pertama datang.
            return isLate(a) ? -1 : 1;
         } else if (a.isCustomerPriority() != b.isCustomerPriority()) {
            return a.isCustomerPriority() ? -1 : 1;
         } else if (a.isOrderPriority() != b.isOrderPriority()) {
            return a.isOrderPriority() ? -1 : 1;
         } else {
            int byCost = Double.compare(DeliveryPlanner.costKm(distOrInf(a, olat, olng), a), DeliveryPlanner.costKm(distOrInf(b, olat, olng), b));
            return byCost != 0 ? byCost : Double.compare(DeliveryPlanner.waitedHours(b), DeliveryPlanner.waitedHours(a));
         }
      });
      return out;
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
         return km < (double)1.0F ? Math.round(km * (double)1000.0F) + " m" : String.format(Locale.US, "%.1f km", km);
      }
   }

   private void openMapsNavigation(double lat, double lng) {
      Uri nav = Uri.parse("google.navigation:q=" + lat + "," + lng);
      Intent i = (new Intent("android.intent.action.VIEW", nav)).setPackage("com.google.android.apps.maps");
      if (i.resolveActivity(this.getPackageManager()) == null) {
         i = new Intent("android.intent.action.VIEW", Uri.parse("https://www.google.com/maps?q=" + lat + "," + lng));
      }

      try {
         this.startActivity(i);
      } catch (Exception var8) {
         Toast.makeText(this, "Tidak ada aplikasi peta", 0).show();
      }

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
            this.openWhatsApp(phone, this.composeTrackMessage(t, link));
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
      this.showLateAlarmDialog(late);
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
         b.setNeutralButton("Ubah…", (d, w) -> this.showAdjustDialog(t, revokeCredit));
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
               this.doComplete(fresh != null ? fresh : t, revokeCredit);
            }
         });
      });
      dlg.show();
   }

   private String rp(double v) {
      return "Rp " + NumberFormat.getNumberInstance(new Locale("in", "ID")).format(Math.round(v));
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

      int kembali = this.dao.getReturnedGalonForSale(t.getCustomerId(), t.getTanggal());
      sb.append("Galon kembali: ").append(kembali).append("\n");
      String pay = t.getPaymentMethodLabel();
      boolean bon = t.getCatatan() != null && t.getCatatan().contains("[CASH BON]");
      sb.append("Pembayaran: ").append(bon ? "Cash Bon (hutang)" : (pay.isEmpty() ? "—" : pay)).append("\n");
      sb.append("Total: ").append(this.rp(t.getTotalHarga()));
      sb.append(this.refundSummaryText(t));
      sb.append(this.debtSummaryText(t));
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
    */
   private String debtSummaryText(Transaction t) {
      if (t == null || t.getCustomerId() <= 0L) {
         return "";
      } else {
         String trxUuid = (new TransactionDao(DatabaseHelper.getInstance(this))).getSyncUuidById(t.getId());
         double prior = (new CustomerDebtDao(DatabaseHelper.getInstance(this)))
               .balanceExcludingTransaction(t.getCustomerId(), trxUuid);
         return prior <= (double)0.0F
               ? ""
               : "\nHutang sebelumnya: " + this.rp(prior)
                     + "\nTotal harus diterima: " + this.rp(t.getTotalHarga() + prior);
      }
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
         String trxUuid = (new TransactionDao(DatabaseHelper.getInstance(this))).getSyncUuidById(t.getId());
         double used = (new CustomerRefundDao(DatabaseHelper.getInstance(this))).usedForTransaction(trxUuid);
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
      double priorDebt = t.getCustomerId() > 0L ? debtDaoAdj.balanceExcludingTransaction(t.getCustomerId(), (new TransactionDao(DatabaseHelper.getInstance(this))).getSyncUuidById(t.getId())) : (double)0.0F;
      if (existingDebt > (double)0.0F) {
         TextView debtNow = new TextView(this);
         debtNow.setText("\ud83e\uddfe Hutang pelanggan saat ini: " + this.rp(existingDebt));
         debtNow.setTextColor(-4645860);
         debtNow.setPadding(0, 0, 0, gap);
         box.addView(debtNow);
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
      SettingsDao sd = new SettingsDao(DatabaseHelper.getInstance(this));
      // CASH BON apa pun alasannya: galon diserahkan tapi uangnya TIDAK masuk. Fotonya WAJIB apa pun
      // setelan cabang — inilah SATU-SATUNYA bukti bahwa galonnya benar-benar sampai, dan tanpa itu
      // klaim "sudah diantar tapi belum dibayar" tak bisa dipertanggungjawabkan.
      boolean required = sd.isDeliveryProofRequired() || this.isCashBon(t);
      if (!required) {
         this.askOptionalProof(t, revokeCredit);
      } else if (this.isCashBon(t)) {
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
    * Order BIASA (bukan Cash Bon, cabang tak mewajibkan): TAWARKAN foto bukti, jangan memaksa.
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
         Toast.makeText(this, "Tidak ada aplikasi kamera — order diselesaikan tanpa foto bukti.", 1).show();
         Transaction t = this.findQueueTrx(trxId);
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

   }

   /**
    * CASH BON → konfirmasi ke pelanggan lewat WA BESERTA FOTO buktinya: pesanan sudah diantar tapi
    * belum dibayar. Pelanggan yang tak menyaksikan pengiriman tidak tahu galonnya sudah datang;
    * tanpa pemberitahuan, tagihan yang muncul di pengiriman berikutnya terasa datang entah dari mana.
    *
    * <p>Dipanggil dari {@link #finishComplete} TEPAT setelah foto bukti tersimpan, dan langsung
    * membuka WhatsApp tanpa dialog perantara — alur "foto lalu kirim" jadi satu tarikan napas.
    * Foto yang barusan diambil ikut sebagai lampiran; bila jalur penyelesaian ini tak memotret
    * (mis. perangkat tanpa aplikasi kamera), {@link WaShare} otomatis jatuh ke pesan teks saja.</p>
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
            Toast.makeText(this, "Membuka WhatsApp — kirim konfirmasi + foto ke pelanggan.", 1).show();
            WaShare.sendPhotoWithCaption(this, safe(t.getCustomerName()), phone, proofPath,
                  this.composeUnpaidNotice(t));
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
            .append("Pesanan air minum Anda sudah kami ANTAR hari ini, namun pembayarannya belum ")
            .append("kami terima.");
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

      sb.append("\nTagihan pengiriman ini: Rp").append(nf.format(Math.round(t.getTotalHarga())));
      double sisa = t.getCustomerId() > 0L
            ? (new CustomerDebtDao(DatabaseHelper.getInstance(this))).balanceFor(t.getCustomerId())
            : (double)0.0F;
      if (sisa > (double)0.0F) {
         sb.append("\n*Total tagihan Anda saat ini: Rp").append(nf.format(Math.round(sisa))).append("*");
      }

      sb.append("\n\nPembayaran bisa diselesaikan saat pengiriman berikutnya. Terima kasih 🙏");
      return sb.toString();
   }

   private void openWhatsApp(String phone, String msg) {
      String normalized = phone.replaceAll("[^0-9]", "");
      if (normalized.startsWith("0")) {
         normalized = "62" + normalized.substring(1);
      } else if (!normalized.startsWith("62")) {
         normalized = "62" + normalized;
      }

      try {
         Intent i = new Intent("android.intent.action.VIEW", Uri.parse("https://wa.me/" + normalized + "?text=" + Uri.encode(msg)));

         try {
            this.getPackageManager().getPackageInfo("com.whatsapp", 0);
            i.setPackage("com.whatsapp");
         } catch (Exception var8) {
            try {
               this.getPackageManager().getPackageInfo("com.whatsapp.w4b", 0);
               i.setPackage("com.whatsapp.w4b");
            } catch (Exception var7) {
            }
         }

         this.startActivity(i);
      } catch (Exception var9) {
         Toast.makeText(this, "Tidak dapat membuka WhatsApp", 0).show();
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

   private void navigasiRitAktif() {
      List<Transaction> stops = this.runningGeoStops();
      if (stops.isEmpty()) {
         Toast.makeText(this, "Order yang sedang diantar belum punya titik koordinat", 1).show();
      } else {
         if (stops.size() > 10) {
            stops = new ArrayList(stops.subList(0, 10));
            Toast.makeText(this, "Dibatasi 10 tujuan (batas Google Maps)", 0).show();
         }

         List<Transaction> finalStops = stops;
         StringBuilder sb = new StringBuilder();

         for(int i = 0; i < finalStops.size(); ++i) {
            sb.append(i + 1).append(". ").append(routeStopLabel((Transaction)finalStops.get(i))).append('\n');
         }

         (new AlertDialog.Builder(this)).setTitle("\ud83d\udccd Urutan Rit (" + finalStops.size() + " tujuan)").setMessage(sb.toString().trim()).setPositiveButton("Buka Google Maps", (d, w) -> this.openMapsRoute(finalStops)).setNegativeButton("Batal", (DialogInterface.OnClickListener)null).show();
      }
   }

   private static String routeStopLabel(Transaction t) {
      String name = safe(t.getCustomerName());
      String dest = t.getDeliveryDestName();
      return dest != null && !dest.trim().isEmpty() ? name + " (" + dest.trim() + ")" : name;
   }

   private void openMapsRoute(List<Transaction> stops) {
      try {
         this.startActivity(new Intent("android.intent.action.VIEW", Uri.parse(buildMapsDirUrl(stops))));
      } catch (Exception var3) {
         Toast.makeText(this, "Tidak ada aplikasi peta", 0).show();
         return;
      }

      Toast.makeText(this, "Rute " + stops.size() + " tujuan dibuka", 0).show();
      this.exitSelectionMode();
   }

   private static String buildMapsDirUrl(List<Transaction> stops) {
      Transaction dest = (Transaction)stops.get(stops.size() - 1);
      StringBuilder url = new StringBuilder("https://www.google.com/maps/dir/?api=1&travelmode=driving");
      url.append("&destination=").append(coord(dest));
      if (stops.size() > 1) {
         StringBuilder wp = new StringBuilder();

         for(int i = 0; i < stops.size() - 1; ++i) {
            if (i > 0) {
               wp.append('|');
            }

            wp.append(coord((Transaction)stops.get(i)));
         }

         url.append("&waypoints=").append(Uri.encode(wp.toString()));
      }

      return url.toString();
   }

   private static String coord(Transaction t) {
      return effectiveLat(t) + "," + effectiveLng(t);
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
      if (t.getCustomerPhone() != null && !t.getCustomerPhone().trim().isEmpty()) {
         sb.append("\ud83d\udcde ").append(t.getCustomerPhone().trim()).append('\n');
      }

      if (t.getCustomerAddress() != null && !t.getCustomerAddress().trim().isEmpty()) {
         sb.append("\ud83d\udccd ").append(t.getCustomerAddress().trim()).append('\n');
      }

      if (t.getDeliveryDestName() != null && !t.getDeliveryDestName().trim().isEmpty()) {
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

      if (actionsGrid.getChildCount() > 0) {
         body.addView(actionsGrid);
      }

      dialog.show();
   }

   private void showUbahMenu(Transaction t) {
      List<String> labels = new ArrayList();
      List<Runnable> actions = new ArrayList();
      if ("JUAL".equals(t.getType())) {
         labels.add("✏️ Ubah Pesanan (persetujuan)");
         actions.add((Runnable)() -> DeliveryEditDialog.show(this, t));
         labels.add("\ud83d\udcb3 Ubah Metode Pembayaran");
         actions.add((Runnable)() -> this.showEditPaymentMethod(t));
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

   private static final String[] RESCHEDULE_REASON_PRESETS = new String[]{"Pesanan overload", "Cuaca buruk", "Kecelakaan", "Lainnya (jelaskan)"};

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
            } else {
               pos.setEnabled(false);
               pos.setText("Menjadwalkan…");
               dialog.setCancelable(false);
               Button neg = dialog.getButton(-2);
               if (neg != null) {
                  neg.setEnabled(false);
               }

               this.doPostpone(dialog, pos, neg, t, resume, reason.getText().toString().trim());
            }
         });
      });
      dialog.show();
   }

   private void doPostpone(AlertDialog dialog, Button pos, Button neg, Transaction t, Calendar resume, String reason) {
      SyncSettings cfg = this.syncCfg();
      String trxUuid = (new TransactionDao(DatabaseHelper.getInstance(this))).getSyncUuidById(t.getId());
      if (trxUuid != null && !trxUuid.isEmpty()) {
         SimpleDateFormat isoFmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
         String resumeIso = isoFmt.format(resume.getTime());
         (new Thread(() -> {
            String okMsg = null;
            String errMsg = null;

            try {
               JSONObject body = new JSONObject();
               body.put("transaction_uuid", trxUuid);
               body.put("resume_at", resumeIso);
               if (reason != null && !reason.isEmpty()) {
                  body.put("reason", reason);
               }
               JSONObject r = (new SyncApi(cfg)).postponeDelivery(body);
               okMsg = r.optString("message", "Order ditunda.");
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
            } else {
               pos.setEnabled(false);
               pos.setText("Menjadwalkan…");
               dialog.setCancelable(false);
               Button neg = dialog.getButton(-2);
               if (neg != null) {
                  neg.setEnabled(false);
               }

               this.doPostponeOther(dialog, pos, neg, q, resume, reason.getText().toString().trim());
            }
         });
      });
      dialog.show();
   }

   private void doPostponeOther(AlertDialog dialog, Button pos, Button neg, JSONObject q, Calendar resume, String reason) {
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

            try {
               JSONObject body = new JSONObject();
               body.put("transaction_uuid", trxUuid);
               body.put("resume_at", resumeIso);
               if (reason != null && !reason.isEmpty()) {
                  body.put("reason", reason);
               }
               JSONObject r = (new SyncApi(cfg)).postponeDelivery(body);
               okMsg = r.optString("message", "Order ditunda.");
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
            this.runOnUiThread(() -> {
               if (!this.isFinishing() && !this.isDestroyed()) {
                  if (okMsgF != null) {
                     Toast.makeText(this, okMsgF, 1).show();
                     dialog.dismiss();
                     SyncScheduler.syncNow(this.getApplicationContext());
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
         try {
            Date d = SDF_PARSE.parse(queuedAt.substring(0, 19));
            return d == null ? queuedAt.substring(0, 19) : (new SimpleDateFormat("dd/MM/yyyy HH:mm:ss", new Locale("id", "ID"))).format(d);
         } catch (Exception var2) {
            return queuedAt.substring(0, 19);
         }
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

   private static String formatDuration(long ms) {
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
      final String label;
      final int bg;
      Chip(String label, int bg) { this.label = label; this.bg = bg; }
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
      String adminArea = c != null ? c.getAdminArea() : "";
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
      d.address = destLoc != null ? "Kirim Ke: " + safe(destLoc.name) + areaSuffix
            : (t.getCustomerAddress() != null && !t.getCustomerAddress().trim().isEmpty()
                  ? t.getCustomerAddress().trim() + areaSuffix : "");
      d.detailChips = this.buildQueueDetailChips(t);
      String extra = (this.refundSummaryText(t) + this.debtSummaryText(t)).trim();
      d.detailExtra = extra.isEmpty() ? null : extra;
      d.photoLocalPath = destLoc != null && destLoc.photo != null && !destLoc.photo.trim().isEmpty()
            ? null   // foto lokasi tersimpan sbg URL server (lihat photoUrl), bukan path lokal
            : (c != null ? c.getPhotoPath() : null);
      d.photoUrl = destLoc != null && destLoc.photo != null && !destLoc.photo.trim().isEmpty()
            ? destLoc.photo.trim() : (c != null ? c.getPhotoUrl() : null);
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
      List<TransactionItem> items = t.getItems();
      if (items != null) {
         for (TransactionItem it : items) {
            if (it != null && it.jumlah > 0) {
               chips.add(new Chip(this.chipLabel(it) + " ×" + it.jumlah, this.chipColor(it)));
            }
         }
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

   /** Badge produk gabungan (SEMUA order berjalan, bukan cuma order berikutnya) di header layar
    *  guided — kurir langsung tahu apa saja yang perlu dimuat/dibawa untuk seluruh rit, bukan
    *  hanya satu order yang sedang di-preview. Dipanggil dari applyRunModeChrome tiap kali daftar
    *  berjalan berubah. */
   private void updateGuidedProductBadges(List<Transaction> stops) {
      if (this.guidedProductBadgesScroll == null || this.guidedProductBadges == null) return;
      if (!this.guidedMode || stops == null || stops.isEmpty()) {
         this.guidedProductBadgesScroll.setVisibility(View.GONE);
         return;
      }
      java.util.LinkedHashMap<String, Integer> qtyByLabel = new java.util.LinkedHashMap<>();
      java.util.LinkedHashMap<String, Integer> colorByLabel = new java.util.LinkedHashMap<>();
      for (Transaction t : stops) {
         if (t == null) continue;
         List<TransactionItem> items = t.getItems();
         if (items == null) continue;
         for (TransactionItem it : items) {
            if (it == null || it.jumlah <= 0) continue;
            String label = this.chipLabel(it);
            qtyByLabel.merge(label, it.jumlah, Integer::sum);
            colorByLabel.putIfAbsent(label, this.chipColor(it));
         }
      }
      if (qtyByLabel.isEmpty()) {
         this.guidedProductBadgesScroll.setVisibility(View.GONE);
         return;
      }
      this.guidedProductBadges.removeAllViews();
      for (Map.Entry<String, Integer> e : qtyByLabel.entrySet()) {
         this.guidedProductBadges.addView(this.makeDetailBadge(e.getKey() + " ×" + e.getValue(),
               colorByLabel.getOrDefault(e.getKey(), -10193781)));
      }
      this.guidedProductBadgesScroll.setVisibility(View.VISIBLE);
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
      List<TransactionItem> items = stop.getItems();
      if (items != null) {
         for (TransactionItem it : items) {
            if (it == null || it.jumlah <= 0) continue;
            row.addView(this.makeDetailBadge(this.chipLabel(it) + " ×" + it.jumlah, this.chipColor(it)));
            any = true;
         }
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
         fabNav.setOnClickListener((v) -> this.openMapsNavigation(d.destLat, d.destLng));
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
         builder.setNeutralButton("🧭 Navigasi", (dlg, w) -> this.openMapsNavigation(d.destLat, d.destLng));
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

   private void updateClaimSelectUi(boolean open) {
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
         if (!uuid.isEmpty() && this.claimSelectedOther.contains(uuid)) {
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

         for(JSONObject o : this.rawData) {
            if (q.isEmpty()) {
               this.data.add(o);
            } else {
               String name = this.str(o, "name").toLowerCase(Locale.US);
               String phone = this.str(o, "phone").toLowerCase(Locale.US);
               if (name.contains(q) || phone.contains(q)) {
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
         long elapsedMs = DeliveryQueueActivity.elapsedMillis(q.optString("queued_at", (String)null));
         DeliveryQueueActivity.bindElapsedBadge(h.tvElapsed, elapsedMs);
         StringBuilder meta = new StringBuilder();
         String dev = this.str(q, "device_group_label");
         if (!dev.isEmpty()) {
            meta.append("\ud83d\udcf1 ").append(dev);
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
         h.tvMeta.setText(meta.toString());
         double ongkir = q.optDouble("ongkir", (double)0.0F);
         h.tvOngkir.setVisibility(ongkir > (double)0.0F ? 0 : 8);
         DeliveryQueueActivity.this.bindOtherDeviceChips(h.productChips, this.str(q, "items"));
         String adminArea = c != null ? c.getAdminArea() : "";
         if (!adminArea.isEmpty()) {
            h.tvAdminArea.setText("\ud83d\udccd " + adminArea + (jarakOther != null ? " (" + jarakOther + ")" : ""));
            h.tvAdminArea.setVisibility(0);
         } else {
            h.tvAdminArea.setVisibility(8);
         }

         h.btnMore.setOnClickListener((v) -> DeliveryQueueActivity.this.showOtherDeviceMoreMenu(v, q));
         h.btnTakeOver.setOnClickListener((v) -> DeliveryQueueActivity.this.showOtherDeviceOrderDetail(q));

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
               DeliveryQueueActivity.this.toggleClaimOther(trxUuid);
               this.notifyItemChanged(h.getBindingAdapterPosition());
            } else {
               DeliveryQueueActivity.this.showOtherDeviceOrderDetail(q);
            }
         });
         h.itemView.setOnLongClickListener((v) -> {
            if (DeliveryQueueActivity.this.claimSelectOther || trxUuid.isEmpty()) {
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
         TextView tvAdminArea;
         TextView tvOrderNote;
         LinearLayout productChips;
         MaterialButton btnMore;
         MaterialButton btnTakeOver;

         VH(View v) {
            super(v);
            this.tvCustomer = (TextView)v.findViewById(id.tvCustomer);
            this.tvElapsed = (TextView)v.findViewById(id.tvElapsed);
            this.tvMeta = (TextView)v.findViewById(id.tvMeta);
            this.tvOngkir = (TextView)v.findViewById(id.tvOngkir);
            this.tvAdminArea = (TextView)v.findViewById(id.tvAdminArea);
            this.tvOrderNote = (TextView)v.findViewById(id.tvOrderNote);
            this.productChips = (LinearLayout)v.findViewById(id.productChips);
            this.btnMore = (MaterialButton)v.findViewById(id.btnMore);
            this.btnTakeOver = (MaterialButton)v.findViewById(id.btnTakeOver);
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
         if (q.isEmpty()) {
            return base;
         }
         List<Transaction> filtered = new ArrayList<>();
         for (Transaction t : base) {
            String name = safe(t.getCustomerName()).toLowerCase(Locale.US);
            String phone = t.getCustomerPhone() != null ? t.getCustomerPhone().toLowerCase(Locale.US) : "";
            if (name.contains(q) || phone.contains(q)) filtered.add(t);
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
         h.tvCustomer.setText(voidPending ? DeliveryQueueActivity.safe(t.getCustomerName()) : (t.isOpenDispatch() ? "\ud83c\udfb2 " : "") + (t.isSelfOrder() ? "\ud83d\udecd️ " : "") + (t.isCustomerPriority() ? "⭐ " : "") + (t.isCustomerDataIncomplete() ? "❗ " : "") + DeliveryQueueActivity.safe(t.getCustomerName()));
         int flags = h.tvCustomer.getPaintFlags();
         h.tvCustomer.setPaintFlags(voidPending ? flags | 16 : flags & -17);
         if (voidPending) {
            h.tvPriorityBig.setVisibility(8);
            h.tvPriorityBig.clearAnimation();
            h.tvPriorityBig.setAlpha(1.0F);
            h.tvNoFoto.setVisibility(8);
            h.tvNoLokasi.setVisibility(8);
            h.tvMeta.setText("");
            h.tvMeta.setVisibility(8);
            h.tvVoidTotal.setText(t.getJumlahGalon() + " galon · Rp " + DeliveryQueueActivity.formatRupiah(t.getTotalHarga()));
            h.tvVoidTotal.setVisibility(0);
            DeliveryQueueActivity.this.bindProductChips(h.productChips, t);
            h.tvOngkir.setVisibility(t.getOngkir() > (double)0.0F ? 0 : 8);
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
            h.tvMeta.setText(meta.toString());
            DeliveryQueueActivity.this.bindProductChips(h.productChips, t);
            h.tvOngkir.setVisibility(t.getOngkir() > (double)0.0F ? 0 : 8);
            Customer cust = t.getCustomerId() > 0L ? DeliveryQueueActivity.this.customerDao.getById(t.getCustomerId()) : null;
            if (cust != null && cust.hasOpenIssue()) {
               String note = cust.getIssueNote();
               h.tvIssue.setText("\ud83d\udea9 Bermasalah" + (note != null && !note.trim().isEmpty() ? ": " + note.trim() : ""));
               h.tvIssue.setVisibility(0);
            } else {
               h.tvIssue.setVisibility(8);
            }

            String adminArea = cust != null ? cust.getAdminArea() : "";
            if (!adminArea.isEmpty()) {
               h.tvAdminArea.setText("\ud83d\udccd " + adminArea + (jarak != null ? " (" + jarak + ")" : ""));
               h.tvAdminArea.setVisibility(0);
            } else {
               h.tvAdminArea.setVisibility(8);
            }

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
            anim.addUpdateListener((a) -> h.card.setStrokeColor((Integer)a.getAnimatedValue()));
            anim.start();
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

         for(Transaction t : this.rawData) {
            if (q.isEmpty()) {
               this.data.add(t);
            } else {
               String name = DeliveryQueueActivity.safe(t.getCustomerName()).toLowerCase(Locale.US);
               String phone = t.getCustomerPhone() != null ? t.getCustomerPhone().toLowerCase(Locale.US) : "";
               if (name.contains(q) || phone.contains(q)) {
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
         h.tvCustomer.setText((t.isOrderPriority() ? "⚡ " : "") + (t.isCustomerPriority() ? "⭐ " : "") + (t.isCustomerDataIncomplete() ? "❗ " : "") + DeliveryQueueActivity.safe(t.getCustomerName()));
         StringBuilder meta = new StringBuilder();
         String phone = t.getCustomerPhone();
         if (phone != null && !phone.trim().isEmpty()) {
            meta.append("\ud83d\udcde ").append(phone.trim());
         }

         String jarak = DeliveryQueueActivity.this.myLat == (double)0.0F && DeliveryQueueActivity.this.myLng == (double)0.0F ? null : DeliveryQueueActivity.formatJarak(DeliveryQueueActivity.distOrInf(t, DeliveryQueueActivity.this.myLat, DeliveryQueueActivity.this.myLng));
         meta.append(meta.length() > 0 ? " · " : "").append(t.getJumlahGalon()).append(" galon");
         h.tvMeta.setText(meta.toString());
         h.tvOngkir.setVisibility(t.getOngkir() > (double)0.0F ? 0 : 8);
         DeliveryQueueActivity.this.bindProductChips(h.productChips, t);
         Customer custAA = t.getCustomerId() > 0L ? DeliveryQueueActivity.this.customerDao.getById(t.getCustomerId()) : null;
         String adminArea = custAA != null ? custAA.getAdminArea() : "";
         if (!adminArea.isEmpty()) {
            h.tvAdminArea.setText("\ud83d\udccd " + adminArea + (jarak != null ? " (" + jarak + ")" : ""));
            h.tvAdminArea.setVisibility(0);
         } else {
            h.tvAdminArea.setVisibility(8);
         }

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
            menu.setOnMenuItemClickListener((item) -> {
               switch (item.getItemId()) {
                  case 1:
                     DeliveryQueueActivity.this.showQueuePreview(t);
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
            anim.addUpdateListener((a) -> h.card.setStrokeColor((Integer)a.getAnimatedValue()));
            anim.start();
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
      }

      class VH extends RecyclerView.ViewHolder {
         MaterialCardView card;
         TextView tvCustomer;
         TextView tvMeta;
         TextView tvOngkir;
         TextView tvAdminArea;
         TextView tvElapsed;
         TextView tvOrderNote;
         MaterialButton btnClaim;
         MaterialButton btnMore;
         LinearLayout productChips;
         ValueAnimator priorityBlink;
         int blinkColor;
         final int defaultStrokeColor;

         VH(View v) {
            super(v);
            this.card = (MaterialCardView)v.findViewById(id.card);
            this.tvCustomer = (TextView)v.findViewById(id.tvCustomer);
            this.tvMeta = (TextView)v.findViewById(id.tvMeta);
            this.tvOngkir = (TextView)v.findViewById(id.tvOngkir);
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
