package com.crowja.damiupos;

import android.animation.ValueAnimator;
import android.app.DatePickerDialog;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.app.TimePickerDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
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
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AlphaAnimation;
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
import com.crowja.damiupos.sync.SyncApi;
import com.crowja.damiupos.sync.SyncScheduler;
import com.crowja.damiupos.sync.SyncSettings;
import com.crowja.damiupos.util.BitmapUtils;
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
   private static final int SORT_OPEN_DISTANCE = 0;
   private static final int SORT_OPEN_GALON = 1;
   private int sortOpenMode = 0;
   private List<DeliveryPlanner.Trip> strategyTrips;
   private ValueAnimator strategyBlink;
   private boolean strategyCollapsed = false;
   private final LinkedHashSet<Long> runningIds = new LinkedHashSet<>();
   private double myLat = (double)0.0F;
   private double myLng = (double)0.0F;
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
      this.productDao = new ProductDao(DatabaseHelper.getInstance(this));
      this.rv = (RecyclerView)this.findViewById(id.rv);
      this.tvEmpty = (TextView)this.findViewById(id.tvEmpty);
      this.tvSummary = (TextView)this.findViewById(id.tvSummary);
      this.adapter = new QueueAdapter();
      this.rv.setLayoutManager(new LinearLayoutManager(this));
      this.rv.setHasFixedSize(true);
      this.rv.setAdapter(this.adapter);
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
      sortGroupOther.check(this.sortOtherMode == 0 ? id.sortOtherJarak : id.sortOtherGalon);
      sortGroupOther.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
         if (isChecked) {
            int newMode = checkedId == id.sortOtherJarak ? 0 : 1;
            if (newMode != this.sortOtherMode) {
               this.sortOtherMode = newMode;
               this.otherDevicesAdapter.applyFilterSort();
            }
         }
      });
      this.rvOpenDispatch = (RecyclerView)this.findViewById(id.rvOpenDispatch);
      this.tvOpenDispatchEmpty = (TextView)this.findViewById(id.tvOpenDispatchEmpty);
      this.rvOpenDispatch.setLayoutManager(new LinearLayoutManager(this));
      this.openDispatchAdapter = new OpenDispatchAdapter();
      this.rvOpenDispatch.setAdapter(this.openDispatchAdapter);
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
      sortGroupOpen.check(this.sortOpenMode == 0 ? id.sortOpenJarak : id.sortOpenGalon);
      sortGroupOpen.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
         if (isChecked) {
            int newMode = checkedId == id.sortOpenJarak ? 0 : 1;
            if (newMode != this.sortOpenMode) {
               this.sortOpenMode = newMode;
               this.openDispatchAdapter.applyFilterSort();
            }
         }
      });
      SettingsDao sdao = new SettingsDao(DatabaseHelper.getInstance(this));
      this.strategyCollapsed = sdao.isDeliveryStrategyCollapsed();
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
      this.getMenuInflater().inflate(R.menu.menu_delivery_queue, menu);
      return super.onCreateOptionsMenu(menu);
   }

   public boolean onPrepareOptionsMenu(Menu menu) {
      MenuItem it = menu.findItem(id.action_report_obstacle);
      if (it != null) {
         it.setVisible(this.activeTab == 0);
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
      if (this.selectionMode) {
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

   protected void onResume() {
      super.onResume();
      this.loadData();
      this.loadOtherDevices();
      this.tick.postDelayed(this.ticker, 1000L);
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

   private void updateOpenEmptyState() {
      if (this.tvOpenDispatchEmpty != null && this.rvOpenDispatch != null && this.openDispatchAdapter != null) {
         boolean empty = this.openDispatchAdapter.getItemCount() == 0;
         this.tvOpenDispatchEmpty.setText(this.searchOpenQuery.isEmpty() ? "\ud83c\udfb2 Tidak ada Pesanan Terbuka saat ini" : "Tidak ditemukan");
         this.tvOpenDispatchEmpty.setVisibility(empty ? 0 : 8);
         this.rvOpenDispatch.setVisibility(empty ? 8 : 0);
      }
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
      menu.getMenu().add(0, 1, 0, "\ud83d\udccd Preview Peta" + jarakSuffix);
      menu.getMenu().add(0, 2, 1, "\ud83d\uddbc Foto");
      menu.getMenu().add(0, 3, 2, "\ud83d\udd52 Jadwalkan Ulang");
      menu.setOnMenuItemClickListener((item) -> {
         switch (item.getItemId()) {
            case 1:
               this.showOtherDeviceMapPreview(q);
               return true;
            case 2:
               this.showOtherDevicePhotoPreview(q);
               return true;
            case 3:
               this.showPostponeSchedulePickerOther(q);
               return true;
            default:
               return false;
         }
      });
      menu.show();
   }

   private void showOtherDevicePhotoPreview(JSONObject q) {
      String custUuid = q.optString("customer_uuid", "");
      Customer c = !custUuid.isEmpty() && !custUuid.equals("null") ? this.customerDao.getBySyncUuid(custUuid) : null;
      String destName = q.optString("dest_name", "");
      String destPhotoUrl = null;
      if (c != null && !destName.isEmpty() && !destName.equals("null") && c.getLocations() != null) {
         for(Customer.Location l : c.getLocations()) {
            if (destName.trim().equalsIgnoreCase(safe(l.name)) && l.photo != null && !l.photo.trim().isEmpty()) {
               destPhotoUrl = l.photo.trim();
               break;
            }
         }
      }

      if (destPhotoUrl != null || c != null && c.hasPhoto()) {
         String path = destPhotoUrl == null && c != null ? c.getPhotoPath() : null;
         if (path != null && !path.isEmpty() && (new File(path)).exists()) {
            this.showFullScreenPhotoQueue(path);
         } else {
            String url = destPhotoUrl != null ? destPhotoUrl : (c != null ? c.getPhotoUrl() : null);
            if (url != null && !url.isEmpty()) {
               ProgressDialog progress = ProgressDialog.show(this, (CharSequence)null, "Memuat foto…", true, false);
               String name = "otherdev_" + (c != null ? c.getId() : 0L) + "_" + Integer.toHexString(url.hashCode()) + ".jpg";
               (new Thread(() -> {
                  File f = BitmapUtils.downloadToCache(this.getApplicationContext(), url, name);
                  this.runOnUiThread(() -> {
                     progress.dismiss();
                     if (!this.isFinishing() && !this.isDestroyed()) {
                        if (f == null) {
                           Toast.makeText(this, "Gagal memuat foto.", 0).show();
                        } else {
                           this.showFullScreenPhotoQueue(f.getAbsolutePath());
                        }
                     }
                  });
               })).start();
            } else {
               Toast.makeText(this, "Foto rumah belum ada.", 0).show();
            }
         }
      } else {
         Toast.makeText(this, "Foto rumah belum ada.", 0).show();
      }
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

   private void showOtherDeviceOrderDetail(JSONObject q) {
      String name = q.optString("name", "Pelanggan");
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

      sb.append(q.optInt("galon", 0)).append(" galon · Rp ").append(String.format(Locale.US, "%,.0f", q.optDouble("total", (double)0.0F)).replace(',', '.')).append('\n');
      String items = q.optString("items", "");
      if (!items.isEmpty() && !items.equals("null")) {
         sb.append(items).append('\n');
      }

      if (q.optBoolean("order_priority", false)) {
         String why = q.optString("order_priority_reason", "");
         sb.append("\n⚡ PRIORITAS").append(!why.isEmpty() && !why.equals("null") ? ": " + why : "").append('\n');
      }

      String ago = queuedAgoOtherDevices(q.optString("queued_at", (String)null));
      if (!ago.isEmpty()) {
         sb.append('\n').append(ago);
      }

      (new AlertDialog.Builder(this)).setTitle("Detail Order — " + name).setMessage(sb.toString()).setPositiveButton("\ud83d\udce5 Ambil Alih", (d, w) -> this.confirmTakeOverOtherDevices(q)).setNegativeButton("Kembali", (DialogInterface.OnClickListener)null).show();
   }

   private void showOtherDeviceMapPreview(JSONObject q) {
      SyncSettings cfg = this.syncCfg();
      if (!cfg.isEnrolled()) {
         Toast.makeText(this, "Perangkat belum terhubung ke server.", 0).show();
      } else {
         ProgressDialog progress = ProgressDialog.show(this, (CharSequence)null, "Memuat peta…", true, false);
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
                     Toast.makeText(this, "Gagal memuat peta — periksa koneksi internet.", 1).show();
                  } else {
                     this.renderOtherDeviceMapPreview(dataF, q);
                  }
               }
            });
         })).start();
      }
   }

   private void renderOtherDeviceMapPreview(JSONObject mapData, JSONObject q) {
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
      boolean hasDevPos = false;
      if (positions != null && !devUuid.isEmpty()) {
         for(int i = 0; i < positions.length(); ++i) {
            JSONObject p = positions.optJSONObject(i);
            if (p != null && devUuid.equals(p.optString("device_uuid", ""))) {
               devLat = p.optDouble("lat", (double)0.0F);
               devLng = p.optDouble("lng", (double)0.0F);
               hasDevPos = devLat != (double)0.0F || devLng != (double)0.0F;
               break;
            }
         }
      }

      if (!hasDest && !hasDevPos) {
         Toast.makeText(this, "Belum ada data koordinat untuk order/perangkat ini.", 1).show();
      } else {
         WebView webView = new WebView(this);
         LiveDeviceOverlay[] overlayRef = new LiveDeviceOverlay[1];
         WebSettings ws = webView.getSettings();
         ws.setJavaScriptEnabled(true);
         ws.setDomStorageEnabled(true);
         ws.setUserAgentString(MapTiles.userAgent());
         int sizeDp = Math.round(320.0F * this.getResources().getDisplayMetrics().density);
         webView.setLayoutParams(new LinearLayout.LayoutParams(-1, sizeDp));
         webView.setBackgroundColor(-1);
         String html = buildMiniMapHtml(hasDevPos, devLat, devLng, devName, hasDest, destLat, destLng, destName);
         LinearLayout content = new LinearLayout(this);
         content.setOrientation(1);
         content.addView(webView);
         String custUuid = q.optString("customer_uuid", "");
         Customer custFooter = !custUuid.isEmpty() && !custUuid.equals("null") ? this.customerDao.getBySyncUuid(custUuid) : null;
         String destLocName = strJson(q, "dest_name");
         String adminArea = custFooter != null ? custFooter.getAdminArea() : "";
         String areaSuffix = !adminArea.isEmpty() ? " (" + adminArea + ")" : "";
         String custAddress = custFooter != null && custFooter.getAddress() != null ? custFooter.getAddress().trim() : "";
         String address = !destLocName.trim().isEmpty() ? "Kirim Ke: " + destLocName.trim() + areaSuffix : (!custAddress.isEmpty() ? custAddress + areaSuffix : "");
         if (!address.isEmpty()) {
            TextView tvDialogAddress = new TextView(this);
            tvDialogAddress.setText("\ud83d\udccd " + address);
            tvDialogAddress.setTextSize(13.0F);
            int padDp = Math.round(16.0F * this.getResources().getDisplayMetrics().density);
            int padTopDp = Math.round(8.0F * this.getResources().getDisplayMetrics().density);
            tvDialogAddress.setPadding(padDp, padTopDp, padDp, 0);
            content.addView(tvDialogAddress);
         }

         AlertDialog.Builder builder = (new AlertDialog.Builder(this)).setTitle("\ud83d\uddfa️ Preview Peta").setView(content).setPositiveButton("Tutup", (DialogInterface.OnClickListener)null);
         if (hasDest) {
            final double destLatF = destLat;
            final double destLngF = destLng;
            builder.setNeutralButton("\ud83e\udded Navigasi", (dlg, w) -> this.openMapsNavigation(destLatF, destLngF));
         }

         AlertDialog dialog = builder.create();
         dialog.setOnShowListener((d) -> {
            webView.setLayoutParams(new LinearLayout.LayoutParams(-1, sizeDp));
            dialog.getWindow().setLayout(-1, -2);
            webView.post(() -> {
               webView.onResume();
               webView.setWebViewClient(new WebViewClient() {
                  public void onPageFinished(WebView view, String url) {
                     overlayRef[0] = new LiveDeviceOverlay(DeliveryQueueActivity.this, webView);
                     overlayRef[0].start();
                  }
               });
               webView.loadDataWithBaseURL("https://unpkg.com", html, "text/html", "UTF-8", (String)null);
            });
         });
         dialog.setOnDismissListener((d) -> {
            if (overlayRef[0] != null) {
               overlayRef[0].stop();
            }

            webView.stopLoading();
            webView.destroy();
         });
         dialog.show();
      }
   }

   private static String buildMiniMapHtml(boolean hasDev, double devLat, double devLng, String devName, boolean hasDest, double destLat, double destLng, String destName) {
      double centerLat = hasDest ? destLat : devLat;
      double centerLng = hasDest ? destLng : devLng;
      return "<!DOCTYPE html>\n<html><head>\n<meta name='viewport' content='width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no'>\n<link rel='stylesheet' href='https://unpkg.com/leaflet@1.9.4/dist/leaflet.css'/>\n<script src='https://unpkg.com/leaflet@1.9.4/dist/leaflet.js'></script>\n<style>html,body{margin:0;padding:0;height:100%;}#map{width:100%;height:100%;}\n.pin{position:relative;width:26px;height:33px;}.pin svg{position:absolute;top:0;left:0;}\n.pin .em{position:absolute;top:2px;left:0;width:26px;text-align:center;font-size:13px;line-height:18px;}\n.pin.blink{animation:pinblink 1s infinite;}\n@keyframes pinblink{0%,100%{opacity:1;transform:scale(1);}50%{opacity:.55;transform:scale(1.18);}}\n</style>\n</head><body>\n<div id='map'></div>\n<script>\nfunction escHtml(s){return String(s==null?'':s).replace(/[&<>\"']/g,function(c){return {'&':'&amp;','<':'&lt;','>':'&gt;','\"':'&quot;',\"'\":'&#39;'}[c];});}\nfunction pinIcon(color,emoji,blink){\n  var svg='<svg width=\"26\" height=\"33\" viewBox=\"0 0 26 33\" xmlns=\"http://www.w3.org/2000/svg\">'+\n    '<path d=\"M13 0C5.8 0 0 5.8 0 13c0 8.7 13 20 13 20s13-11.3 13-20C26 5.8 20.2 0 13 0z\" fill=\"'+color+'\"/>'+\n    '<circle cx=\"13\" cy=\"12\" r=\"9\" fill=\"#fff\"/></svg>';\n  return L.divIcon({className:'',html:'<div class=\"pin'+(blink?' blink':'')+'\">'+svg+'<div class=\"em\">'+emoji+'</div></div>',iconSize:[26,33],iconAnchor:[13,33],popupAnchor:[0,-30]});\n}\nvar map = L.map('map',{zoomControl:true}).setView([" + centerLat + "," + centerLng + "], 14);\nL.tileLayer('" + "https://server.arcgisonline.com/ArcGIS/rest/services/World_Street_Map/MapServer/tile/{z}/{y}/{x}" + "',{subdomains:'" + "" + "',maxZoom:19,attribution:'" + "Tiles &copy; Esri &mdash; Source: Esri, HERE, Garmin, USGS, NGA, NOAA" + "'}).addTo(map);\nvar pts=[];\n" + (hasDev ? "L.marker([" + devLat + "," + devLng + "],{icon:pinIcon('#F9A825','\ud83c\udfcd️',false)}).addTo(map).bindPopup(escHtml('" + escJs(devName) + "'));\npts.push([" + devLat + "," + devLng + "]);\n" : "") + (hasDest ? "L.marker([" + destLat + "," + destLng + "],{icon:pinIcon('#C62828','\ud83d\udccd',true)}).addTo(map).bindPopup(escHtml('" + escJs(destName) + "'));\npts.push([" + destLat + "," + destLng + "]);\n" : "") + (hasDev && hasDest ? "L.polyline(pts,{color:'#F9A825',weight:3,opacity:.85,dashArray:'6,6'}).addTo(map);\n" : "") + "function fit(){ map.invalidateSize();\n  if(pts.length>1){ map.fitBounds(pts,{padding:[36,36],maxZoom:16}); }\n  else if(pts.length===1){ map.setView(pts[0],16); } }\nwindow.addEventListener('load',function(){ fit(); setTimeout(fit,250); setTimeout(fit,800); });\nsetTimeout(fit,120);\n</script></body></html>";
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
               card.setVisibility(0);
               this.applyStrategyCollapsed();
               this.startStrategyBlink(card);
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

      List<Transaction> ordered = new ArrayList(list);
      Collections.sort(ordered, (a, b) -> {
         boolean av = a.hasPendingVoidRequest();
         boolean bv = b.hasPendingVoidRequest();
         return av == bv ? 0 : (av ? 1 : -1);
      });
      this.adapter.setData(ordered);
      this.renderStrategy(ordered);
      this.applyRunModeChrome(list);
      this.tvEmpty.setVisibility(list.isEmpty() ? 0 : 8);
      this.rv.setVisibility(list.isEmpty() ? 8 : 0);
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

   private void confirmStopRun() {
      List<Transaction> stops = this.runStops();
      String what = stops.size() > 1 ? stops.size() + " pengiriman rit ini BELUM ditandai Selesai." : "Pengiriman untuk \"" + (stops.isEmpty() ? "Order ini" : safe(((Transaction)stops.get(0)).getCustomerName())) + "\" BELUM ditandai Selesai.";
      (new AlertDialog.Builder(this)).setIcon(17301543).setTitle("Ganti pengiriman?").setMessage(what + "\n\nKembali ke daftar antrean dan memilih pengiriman lain?").setPositiveButton("Ya, ganti", (d, w) -> this.stopRun()).setNegativeButton("Tidak", (DialogInterface.OnClickListener)null).show();
   }

   private void showRunningMoreMenu(View anchor, Transaction t, String jarakLabel) {
      PopupMenu menu = new PopupMenu(this, anchor);
      menu.getMenu().add(0, 1, 0, "← Kembali");
      menu.getMenu().add(0, 2, 1, "\ud83d\uddbc Foto");
      menu.getMenu().add(0, 3, 2, jarakLabel != null ? "\ud83d\udccd " + jarakLabel + " · Preview Peta" : "\ud83d\udccd Preview Peta");
      menu.getMenu().add(0, 4, 3, "\ud83d\udcac Chat WA");
      menu.setOnMenuItemClickListener((item) -> {
         switch (item.getItemId()) {
            case 1:
               this.confirmStopRun();
               return true;
            case 2:
               this.showQueuePhotoPreview(t);
               return true;
            case 3:
               this.showQueueMapPreview(t);
               return true;
            case 4:
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
      menu.getMenu().add(0, 1, 0, t.isOrderPriority() ? "⚡ Sudah Prioritas — ubah alasan" : "⚡ Jadikan Prioritas");
      menu.getMenu().add(0, 2, 1, "\ud83d\uddbc Foto");
      menu.getMenu().add(0, 3, 2, jarakLabel != null ? "\ud83d\udccd " + jarakLabel + " · Preview Peta" : "\ud83d\udccd Preview Peta");
      menu.setOnMenuItemClickListener((item) -> {
         switch (item.getItemId()) {
            case 1:
               this.promptMarkPriority(t);
               return true;
            case 2:
               this.showQueuePhotoPreview(t);
               return true;
            case 3:
               this.showQueueMapPreview(t);
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
         this.tvSummary.setText(n + " order menunggu diproses");
      }

      Transaction run = stops.isEmpty() ? null : (Transaction)stops.get(0);
      View cardStrategy = this.findViewById(id.cardStrategy);
      if (cardStrategy != null && run != null) {
         cardStrategy.setVisibility(8);
      }

      if (this.tabs != null) {
         this.tabs.setVisibility(run != null ? 8 : 0);
      }

      View fabNavigasiRit = this.findViewById(id.fabNavigasiRit);
      if (fabNavigasiRit != null) {
         fabNavigasiRit.setVisibility(run != null ? 0 : 8);
      }

   }

   private static boolean isPickupOnly(Transaction t) {
      return "KEMBALI".equals(t.getType());
   }

   private static List<Transaction> sortByDistance(List<Transaction> base, double olat, double olng) {
      List<Transaction> out = new ArrayList(base);
      Collections.sort(out, (a, b) -> {
         if (isPickupOnly(a) != isPickupOnly(b)) {
            return isPickupOnly(a) ? 1 : -1;
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

   private void showIssueBlockThenComplete(Transaction t, Customer c) {
      StringBuilder cats = new StringBuilder();

      for(String label : c.issueLabelList()) {
         cats.append("• ").append(label).append("\n");
      }

      String note = c.getIssueNote() != null && !c.getIssueNote().isEmpty() ? "\nCatatan pelapor: " + c.getIssueNote() + "\n" : "";
      this.playIncompleteAlertSound();
      (new AlertDialog.Builder(this)).setIcon(17301543).setTitle("⚠️ Pelanggan Bermasalah — Perbaiki Dulu").setMessage("Detail pelanggan \"" + c.getName() + "\" ditandai BERMASALAH dan belum diperbaiki:\n\n" + cats + note + "\nPerbaiki dulu, lalu tandai \"sudah diperbaiki\" (butuh persetujuan owner) sebelum menyelesaikan delivery ini.").setPositiveButton("Sudah Diperbaiki…", (d, w) -> IssueResolveDialog.show(this, c, "delivery", () -> this.doComplete(t, false))).setNeutralButton("Perbaiki Data", (d, w) -> this.startActivity((new Intent(this, CustomerFormActivity.class)).putExtra("customer_id", c.getId()))).setNegativeButton("Batal", (DialogInterface.OnClickListener)null).show();
   }

   private void complete(Transaction t, boolean revokeCredit) {
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

      AlertDialog.Builder b = (new AlertDialog.Builder(this)).setTitle(isPickup ? "Tandai Selesai (Pickup)" : "Tandai Selesai").setMessage(msg).setPositiveButton("Selesai", (d, w) -> this.doComplete(t, revokeCredit)).setNegativeButton("Batal", (DialogInterface.OnClickListener)null);
      if (!isPickup) {
         b.setNeutralButton("Ubah…", (d, w) -> this.showAdjustDialog(t, revokeCredit));
      } else if (allocatable) {
         b.setNeutralButton("Ubah Alokasi", (d, w) -> AllocationDialog.show(this, t, "delivery"));
      }

      b.show();
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
      return sb.toString();
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

         double paid;
         try {
            paid = Double.parseDouble(paidIn.getText().toString().trim());
         } catch (Exception var21) {
            paid = due;
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
            if (!"0".equals(paidIn.getText().toString().trim())) {
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
                  return;
               }

               if (val <= (double)0.0F && !bon.isChecked()) {
                  bon.setChecked(true);
               }
            }
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
         if (sd.isDeliveryProofOptional()) {
            (new AlertDialog.Builder(this)).setTitle("Foto Bukti Pengiriman?").setMessage("Ambil foto galon yang sudah diantar sebagai bukti. Boleh dilewati.").setPositiveButton("\ud83d\udcf7 Ambil Foto", (d, w) -> this.requestProofPhoto(t, revokeCredit)).setNegativeButton("Lewati", (d, w) -> this.finishComplete(t, revokeCredit)).setOnCancelListener((d) -> this.finishComplete(t, revokeCredit)).show();
         } else {
            this.finishComplete(t, revokeCredit);
         }
      } else {
         this.requestProofPhoto(t, revokeCredit);
      }
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
      Intent intent = new Intent("android.media.action.IMAGE_CAPTURE");
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
      boolean notifyUnpaid = this.isCashBon(t);
      this.dao.markDelivered(t.getId(), revokeCredit);
      if (this.runningIds.remove(t.getId())) {
         this.persistRunning();
      }

      SyncScheduler.syncNow(this.getApplicationContext());
      this.loadData();
      Toast.makeText(this, "Order selesai • " + formatDuration(ms), 0).show();
      if (notifyUnpaid) {
         this.requireUnpaidNoticeWa(t, proofPath);
      }

   }

   /**
    * CASH BON → konfirmasi ke pelanggan lewat WA BESERTA FOTO buktinya: pesanan sudah diantar tapi
    * belum dibayar. Pelanggan yang tak menyaksikan pengiriman tidak tahu galonnya sudah datang;
    * tanpa pemberitahuan, tagihan yang muncul di pengiriman berikutnya terasa datang entah dari mana.
    *
    * <p>Tombol POSITIF sengaja TIDAK menutup dialog — kurir harus kembali dan menekan "SUDAH SAYA
    * KIRIM" supaya langkahnya tercatat (pola {@link #requireSelfOrderStrukThen}). Yang tercatat
    * adalah KONFIRMASI kurir, bukan bukti terkirim: memaksakan bukti nyata menuntut layanan
    * Aksesibilitas hidup di setiap HP, dan bila mati kurir jadi tak bisa menyelesaikan order.</p>
    */
   private void requireUnpaidNoticeWa(Transaction t, String proofPath) {
      if (t != null) {
         String phone = t.getCustomerPhone();
         if (WaShare.hasUsablePhone(phone)) {
            String msg = this.composeUnpaidNotice(t);
            String name = safe(t.getCustomerName());
            AlertDialog dlg = (new AlertDialog.Builder(this))
                  .setIcon(17301545)
                  .setCancelable(false)
                  .setTitle("📤 WAJIB: Konfirmasi ke Pelanggan")
                  .setMessage("Galon untuk \"" + name + "\" sudah diantar tapi uangnya belum diterima.\n\n"
                        + "Kirim konfirmasi WhatsApp beserta FOTO buktinya supaya pelanggan tahu "
                        + "pesanannya sudah sampai dan masih ada tagihan yang belum dibayar.")
                  .setPositiveButton("BUKA WHATSAPP", (DialogInterface.OnClickListener)null)
                  .setNeutralButton("SUDAH SAYA KIRIM", (DialogInterface.OnClickListener)null)
                  .create();
            dlg.setOnShowListener((d) -> {
               dlg.getButton(-1).setOnClickListener((v) ->
                     WaShare.sendPhotoWithCaption(this, name, phone, proofPath, msg));
               dlg.getButton(-3).setOnClickListener((v) -> dlg.dismiss());
            });
            dlg.show();
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
      AlertDialog dialog = (new AlertDialog.Builder(this)).setIcon(17301543).setTitle("Jadwalkan Ulang Order Ini?").setCancelable(false).setMessage("Order \"" + safe(t.getCustomerName()) + "\" akan keluar dari antrian aktif dan kembali otomatis pada:\n\n" + resumeLabel + "\n\nTanggal transaksinya ikut pindah ke jadwal ini.\n\nKetuk \"Jadwalkan\" dua kali untuk memastikan.").setPositiveButton("Jadwalkan", (DialogInterface.OnClickListener)null).setNegativeButton("Batal", (DialogInterface.OnClickListener)null).create();
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

               this.doPostpone(dialog, pos, neg, t, resume);
            }
         });
      });
      dialog.show();
   }

   private void doPostpone(AlertDialog dialog, Button pos, Button neg, Transaction t, Calendar resume) {
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
      AlertDialog dialog = (new AlertDialog.Builder(this)).setIcon(17301543).setTitle("Jadwalkan Ulang Order Ini?").setCancelable(false).setMessage("Order \"" + custName + "\" (antrian perangkat lain) akan keluar dari antrian aktif dan kembali otomatis pada:\n\n" + resumeLabel + "\n\nTanggal transaksinya ikut pindah ke jadwal ini.\n\nKetuk \"Jadwalkan\" dua kali untuk memastikan.").setPositiveButton("Jadwalkan", (DialogInterface.OnClickListener)null).setNegativeButton("Batal", (DialogInterface.OnClickListener)null).create();
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

               this.doPostponeOther(dialog, pos, neg, q, resume);
            }
         });
      });
      dialog.show();
   }

   private void doPostponeOther(AlertDialog dialog, Button pos, Button neg, JSONObject q, Calendar resume) {
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
      if (queuedAt != null && queuedAt.length() >= 19) {
         try {
            Date d = SDF_PARSE.parse(queuedAt.substring(0, 19));
            if (d == null) {
               return 0L;
            } else {
               long ms = System.currentTimeMillis() - d.getTime();
               return Math.max(0L, ms);
            }
         } catch (Exception var4) {
            return 0L;
         }
      } else {
         return 0L;
      }
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
      long s = Math.max(0L, ms) / 1000L;
      long days = s / 86400L;
      if (days > 0L) {
         long h = s % 86400L / 3600L;
         long m = s % 3600L / 60L;
         return days + " hari " + String.format(Locale.US, "%02d:%02d", h, m);
      } else {
         long h = s / 3600L;
         long m = s % 3600L / 60L;
         long sec = s % 60L;
         return h > 0L ? String.format(Locale.US, "%02d:%02d:%02d", h, m, sec) : String.format(Locale.US, "%02d:%02d", m, sec);
      }
   }

   private static void applyQueueTimerState(TextView tv, long elapsedMs) {
      int level = elapsedMs >= 7200000L ? 2 : (elapsedMs >= 3600000L ? 1 : 0);
      Object prev = tv.getTag(id.tvElapsed);
      boolean changed = !(prev instanceof Integer) || (Integer)prev != level;
      if (changed) {
         tv.setTag(id.tvElapsed, level);
         tv.setBackgroundResource(level == 2 ? drawable.bg_pending_badge : (level == 1 ? drawable.bg_queue_timer_warn : drawable.bg_queue_timer_ok));
      }

      if (level == 0) {
         tv.clearAnimation();
         tv.setAlpha(1.0F);
      } else if (changed || tv.getAnimation() == null) {
         AlphaAnimation blink = new AlphaAnimation(1.0F, level == 2 ? 0.2F : 0.35F);
         blink.setDuration(level == 2 ? 350L : 650L);
         blink.setRepeatMode(2);
         blink.setRepeatCount(-1);
         tv.startAnimation(blink);
      }
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

   private void showQueueMapPreview(Transaction t) {
      double lat = effectiveLat(t);
      double lng = effectiveLng(t);
      if (lat == (double)0.0F && lng == (double)0.0F) {
         Toast.makeText(this, "Belum ada koordinat untuk order ini.", 0).show();
      } else {
         WebView webView = new WebView(this);
         LiveDeviceOverlay[] overlayRef = new LiveDeviceOverlay[1];
         WebSettings ws = webView.getSettings();
         ws.setJavaScriptEnabled(true);
         ws.setDomStorageEnabled(true);
         ws.setUserAgentString(MapTiles.userAgent());
         int sizeDp = Math.round(320.0F * this.getResources().getDisplayMetrics().density);
         webView.setLayoutParams(new LinearLayout.LayoutParams(-1, sizeDp));
         webView.setBackgroundColor(-1);
         boolean hasDev = this.myLat != (double)0.0F || this.myLng != (double)0.0F;
         String html = buildMiniMapHtml(hasDev, this.myLat, this.myLng, "Posisi Anda", true, lat, lng, safe(t.getCustomerName()));
         LinearLayout content = new LinearLayout(this);
         content.setOrientation(1);
         content.addView(webView);
         Customer.Location destLoc = this.resolveOrderLocation(t);
         Customer custForArea = t.getCustomerId() > 0L ? this.customerDao.getByIdMerged(t.getCustomerId()) : null;
         String adminArea = custForArea != null ? custForArea.getAdminArea() : "";
         String areaSuffix = !adminArea.isEmpty() ? " (" + adminArea + ")" : "";
         String address = destLoc != null ? "Kirim Ke: " + safe(destLoc.name) + areaSuffix : (t.getCustomerAddress() != null && !t.getCustomerAddress().trim().isEmpty() ? t.getCustomerAddress().trim() + areaSuffix : "");
         if (!address.isEmpty()) {
            TextView tvDialogAddress = new TextView(this);
            tvDialogAddress.setText("\ud83d\udccd " + address);
            tvDialogAddress.setTextSize(13.0F);
            int padDp = Math.round(16.0F * this.getResources().getDisplayMetrics().density);
            int padTopDp = Math.round(8.0F * this.getResources().getDisplayMetrics().density);
            tvDialogAddress.setPadding(padDp, padTopDp, padDp, 0);
            content.addView(tvDialogAddress);
         }

         AlertDialog dialog = (new AlertDialog.Builder(this)).setTitle("\ud83d\udccd Preview Peta").setView(content).setPositiveButton("Tutup", (DialogInterface.OnClickListener)null).setNeutralButton("\ud83e\udded Navigasi", (dlg, w) -> this.openMapsNavigation(lat, lng)).create();
         dialog.setOnShowListener((d) -> {
            webView.setLayoutParams(new LinearLayout.LayoutParams(-1, sizeDp));
            dialog.getWindow().setLayout(-1, -2);
            webView.post(() -> {
               webView.onResume();
               webView.setWebViewClient(new WebViewClient() {
                  public void onPageFinished(WebView view, String url) {
                     overlayRef[0] = new LiveDeviceOverlay(DeliveryQueueActivity.this, webView);
                     overlayRef[0].start();
                  }
               });
               webView.loadDataWithBaseURL("https://unpkg.com", html, "text/html", "UTF-8", (String)null);
            });
         });
         dialog.setOnDismissListener((d) -> {
            if (overlayRef[0] != null) {
               overlayRef[0].stop();
            }

            webView.stopLoading();
            webView.destroy();
         });
         dialog.show();
      }
   }

   private void showQueuePhotoPreview(Transaction t) {
      Customer c = this.customerDao.getByIdMerged(t.getCustomerId());
      Customer.Location destLoc = this.resolveOrderLocation(t);
      String destPhotoUrl = destLoc != null && destLoc.photo != null && !destLoc.photo.trim().isEmpty() ? destLoc.photo.trim() : null;
      if (destPhotoUrl != null || c != null && c.hasPhoto()) {
         String path = destPhotoUrl == null && c != null ? c.getPhotoPath() : null;
         if (path != null && !path.isEmpty() && (new File(path)).exists()) {
            this.showFullScreenPhotoQueue(path);
         } else {
            String url = destPhotoUrl != null ? destPhotoUrl : (c != null ? c.getPhotoUrl() : null);
            if (url != null && !url.isEmpty()) {
               ProgressDialog progress = ProgressDialog.show(this, (CharSequence)null, "Memuat foto…", true, false);
               String name = "queue_" + t.getCustomerId() + "_" + Integer.toHexString(url.hashCode()) + ".jpg";
               (new Thread(() -> {
                  File f = BitmapUtils.downloadToCache(this.getApplicationContext(), url, name);
                  this.runOnUiThread(() -> {
                     progress.dismiss();
                     if (!this.isFinishing() && !this.isDestroyed()) {
                        if (f == null) {
                           Toast.makeText(this, "Gagal memuat foto.", 0).show();
                        } else {
                           this.showFullScreenPhotoQueue(f.getAbsolutePath());
                        }
                     }
                  });
               })).start();
            } else {
               Toast.makeText(this, "Foto rumah belum ada.", 0).show();
            }
         }
      } else {
         Toast.makeText(this, "Foto rumah belum ada.", 0).show();
      }
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
         } else if (!Double.isNaN(DeliveryQueueActivity.this.otherLat) && !Double.isNaN(DeliveryQueueActivity.this.otherLng)) {
            this.data.sort((a, b) -> Double.compare(this.distanceKmOf(a), this.distanceKmOf(b)));
         }

         this.notifyDataSetChanged();
         DeliveryQueueActivity.this.updateOtherEmptyState();
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
         h.tvElapsed.setText("⏱ " + DeliveryQueueActivity.formatElapsedBadge(elapsedMs));
         DeliveryQueueActivity.applyQueueTimerState(h.tvElapsed, elapsedMs);
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
               ((VH)vh).tvElapsed.setText("⏱ " + DeliveryQueueActivity.formatElapsedBadge(ms));
               DeliveryQueueActivity.applyQueueTimerState(((VH)vh).tvElapsed, ms);
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
      private List<Transaction> data;

      private QueueAdapter() {
         super();
         this.data = new ArrayList<>();
      }

      void setData(List<Transaction> list) {
         this.data = list != null ? list : new ArrayList<>();
         this.notifyDataSetChanged();
      }

      private List<Transaction> shown() {
         if (!DeliveryQueueActivity.this.selectionMode && DeliveryQueueActivity.this.isRunning()) {
            List<Transaction> run = DeliveryQueueActivity.this.runStops();
            return run.isEmpty() ? this.data : run;
         } else {
            return this.data;
         }
      }

      void refreshTimers() {
         List<Transaction> vis = this.shown();

         for(int i = 0; i < DeliveryQueueActivity.this.rv.getChildCount(); ++i) {
            View child = DeliveryQueueActivity.this.rv.getChildAt(i);
            RecyclerView.ViewHolder vh = DeliveryQueueActivity.this.rv.getChildViewHolder(child);
            int pos = vh.getAdapterPosition();
            if (pos >= 0 && pos < vis.size() && vh instanceof VH) {
               long ms = DeliveryQueueActivity.elapsedMillis(((Transaction)vis.get(pos)).getDeliveryQueuedAt());
               ((VH)vh).tvElapsed.setText("⏱ " + DeliveryQueueActivity.formatElapsedBadge(ms));
               DeliveryQueueActivity.applyQueueTimerState(((VH)vh).tvElapsed, ms);
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
            h.tvElapsed.setText("⏱ " + DeliveryQueueActivity.formatElapsedBadge(elapsedMs));
            DeliveryQueueActivity.applyQueueTimerState(h.tvElapsed, elapsedMs);
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

            int blinkHi = t.isCustomerDataIncomplete() ? -1096636 : (t.isOpenDispatch() ? -10262799 : (!t.isCustomerPriority() && !t.isOrderPriority() ? 0 : -19712));
            boolean isSelectedNow = DeliveryQueueActivity.this.selectionMode && DeliveryQueueActivity.this.selectedIds.contains(t.getId());
            if (isSelectedNow) {
               this.stopBlink(h);
               h.card.setStrokeWidth(DeliveryQueueActivity.this.dp(3.0F));
               h.card.setStrokeColor(DeliveryQueueActivity.this.getResources().getColor(color.primary));
            } else if (blinkHi != 0) {
               h.card.setStrokeWidth(DeliveryQueueActivity.this.dp(2.0F));
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
         } else if (DeliveryQueueActivity.this.myLat != (double)0.0F || DeliveryQueueActivity.this.myLng != (double)0.0F) {
            this.data.sort((a, b) -> Double.compare(DeliveryQueueActivity.distOrInf(a, DeliveryQueueActivity.this.myLat, DeliveryQueueActivity.this.myLng), DeliveryQueueActivity.distOrInf(b, DeliveryQueueActivity.this.myLat, DeliveryQueueActivity.this.myLng)));
         }

         this.notifyDataSetChanged();
         DeliveryQueueActivity.this.updateOpenEmptyState();
      }

      void refreshTimers() {
         for(int i = 0; i < DeliveryQueueActivity.this.rvOpenDispatch.getChildCount(); ++i) {
            View child = DeliveryQueueActivity.this.rvOpenDispatch.getChildAt(i);
            RecyclerView.ViewHolder vh = DeliveryQueueActivity.this.rvOpenDispatch.getChildViewHolder(child);
            int pos = vh.getAdapterPosition();
            if (pos >= 0 && pos < this.data.size() && vh instanceof VH) {
               long ms = DeliveryQueueActivity.elapsedMillis(((Transaction)this.data.get(pos)).getDeliveryQueuedAt());
               ((VH)vh).tvElapsed.setText("⏱ " + DeliveryQueueActivity.formatElapsedBadge(ms));
               DeliveryQueueActivity.applyQueueTimerState(((VH)vh).tvElapsed, ms);
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

         if (t.isOrderPriority()) {
            this.startPriorityBlink(h);
         } else {
            this.stopPriorityBlink(h);
         }

         long ms = DeliveryQueueActivity.elapsedMillis(t.getDeliveryQueuedAt());
         h.tvElapsed.setText("⏱ " + DeliveryQueueActivity.formatElapsedBadge(ms));
         DeliveryQueueActivity.applyQueueTimerState(h.tvElapsed, ms);
         String jarakSuffix = jarak != null ? " (" + jarak + ")" : "";
         h.btnMore.setOnClickListener((v) -> {
            PopupMenu menu = new PopupMenu(DeliveryQueueActivity.this, v);
            menu.getMenu().add(0, 1, 0, "\ud83d\udccd Preview Peta" + jarakSuffix);
            menu.getMenu().add(0, 2, 1, "\ud83d\uddbc Foto");
            menu.setOnMenuItemClickListener((item) -> {
               switch (item.getItemId()) {
                  case 1:
                     DeliveryQueueActivity.this.showQueueMapPreview(t);
                     return true;
                  case 2:
                     DeliveryQueueActivity.this.showQueuePhotoPreview(t);
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
      }

      public int getItemCount() {
         return this.data.size();
      }

      private void startPriorityBlink(VH h) {
         if (h.priorityBlink == null || !h.priorityBlink.isStarted()) {
            ValueAnimator anim = ValueAnimator.ofArgb(new int[]{-19712, 587182848});
            anim.setDuration(700L);
            anim.setRepeatMode(2);
            anim.setRepeatCount(-1);
            anim.addUpdateListener((a) -> h.card.setStrokeColor((Integer)a.getAnimatedValue()));
            anim.start();
            h.priorityBlink = anim;
         }
      }

      private void stopPriorityBlink(VH h) {
         if (h.priorityBlink != null) {
            h.priorityBlink.cancel();
            h.priorityBlink = null;
         }

         h.card.setStrokeColor(h.defaultStrokeColor);
      }

      public void onViewRecycled(@NonNull VH h) {
         super.onViewRecycled(h);
         this.stopPriorityBlink(h);
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
