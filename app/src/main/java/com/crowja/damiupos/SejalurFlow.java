package com.crowja.damiupos;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.SettingsDao;
import com.crowja.damiupos.db.TransactionDao;
import com.crowja.damiupos.sync.SyncApi;
import com.crowja.damiupos.sync.SyncScheduler;
import com.crowja.damiupos.sync.SyncSettings;
import com.crowja.damiupos.util.SejalurPlanLogic;
import com.crowja.damiupos.util.SejalurPlanLogic.Badge;
import com.crowja.damiupos.util.SejalurPlanLogic.PlanRow;
import com.crowja.damiupos.util.SejalurPlanLogic.Target;
import com.crowja.damiupos.util.SejalurPlanLogic.WidenOption;
import com.crowja.damiupos.util.Ts;
import com.google.android.material.button.MaterialButton;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 🛣️ Kirim Sejalur — alur layar di Antrian Delivery (logika murninya di {@link SejalurPlanLogic}).
 *
 * <ol>
 *   <li>{@link #showPicker()}: pilih titik TERJAUH dari Antrian Saya / Pesanan Terbuka / perangkat lain
 *       (umur ⏱ + jarak 📍 dari depot, urut Terjauh/Terlama, cari, atau "🗺 Pilih dari Peta");</li>
 *   <li>{@link #askGalon(Target)}: bawa berapa galon (bawaan = kapasitas perangkat) → {@code /sejalur/plan};</li>
 *   <li>pratinjau rencana (centang, alternatif "tidak muat", muatan dihitung ulang langsung) →
 *       {@code /sejalur/start}; kurang muatan → "↔ Perlebar jalur" (rencana ulang dengan koridor lebih
 *       lebar, centang kurir dibawa);</li>
 *   <li>{@link Host#sejalurApplyStart} menjalankan rit di HP (urutan koridor server, titik terjauh
 *       terakhir) lalu dialog hasil.</li>
 * </ol>
 */
final class SejalurFlow {

   /** Yang dibutuhkan alur ini dari DeliveryQueueActivity (data antrean & rit tinggal di sana). */
   interface Host {
      /** Baris pemilih; {@code others} null = antrean perangkat lain yang sudah dimuat tab Perangkat Lain. */
      List<Target> sejalurTargets(@Nullable JSONArray others);

      /** Posisi HP terakhir {lat,lng} atau null. */
      @Nullable
      double[] sejalurMyFix();

      /** Pesanan Terbuka di layar: uuid → id lokal (dibaca di UI thread sebelum start). */
      Map<String, Long> sejalurOpenDispatchIds();

      /** Alasan Kirim Sejalur tak boleh dimulai sekarang (rit berjalan, rencana lain menunggu, …); null = boleh. */
      @Nullable
      String sejalurBlock();

      /**
       * Start sukses di server, rencana sudah tersimpan ({@link #persistStart}) → muat ulang layar supaya
       * rit berjalan. @return {mulai sekarang, menunggu sinkron}
       */
      int[] sejalurApplyStart(List<String> ritUuids);

      /** Buka Peta Delivery dalam mode pilih titik terjauh. */
      void sejalurOpenMap();

      /** Kapsul produk berwarna dari baris server ({@code item_slugs} + jenis galon), gaya kartu antrean. */
      List<Badge> sejalurBadges(JSONObject row);

      /** Kapsul gaya kartu antrean (DeliveryQueueActivity.makeDetailBadge — teks gelap/putih menurut luminans). */
      TextView sejalurBadgeView(CharSequence label, int bg);
   }

   // Palet layar ini (selalu terang). Paket-privat supaya kontrasnya diuji JUnit (SejalurFlowContrastTest).
   static final int C_TEXT = 0xFF212121;
   static final int C_TEXT2 = 0xFF424242;
   static final int C_MUTED = 0xFF616161;
   static final int C_PRIMARY = 0xFF1565C0;
   static final int C_PRIMARY_DARK = 0xFF0D47A1;
   static final int C_DANGER = 0xFFB71C1C;
   static final int C_OK = 0xFF1B5E20;
   /** Latar kartu pemilih yang biasa / yang TIDAK bisa dipilih (abu-abu — teks tetap penuh, tanpa alpha). */
   static final int C_CARD_BG = 0xFFFFFFFF;
   static final int C_CARD_BLOCKED_BG = 0xFFEEEEEE;
   /** Lencana "↔ melebar" baris dari jalur yang diperlebar. */
   static final int C_WIDE_FG = 0xFFBF360C;
   static final int C_WIDE_BG = 0xFFFBE9E7;

   private final AppCompatActivity act;
   private final Host host;
   private Dialog picker;
   private String from = "dari depot";

   SejalurFlow(AppCompatActivity act, Host host) {
      this.act = act;
      this.host = host;
   }

   private SyncSettings cfg() {
      return new SyncSettings(new SettingsDao(DatabaseHelper.getInstance(this.act)));
   }

   /** "dari depot" bila titik cabang diatur; selainnya jarak diukur dari posisi HP (SejalurPlanLogic.origin). */
   private String fromLabel() {
      try {
         JSONObject o = new JSONObject(this.cfg().getBranchCenter());
         if (SejalurPlanLogic.isGeo(o.optDouble("lat", 0.0), o.optDouble("lng", 0.0))) return "dari depot";
      } catch (Exception ignored) {
      }
      return "dari posisi Anda";
   }

   private int dp(float v) {
      return Math.round(v * this.act.getResources().getDisplayMetrics().density);
   }

   private boolean gone() {
      return this.act.isFinishing() || this.act.isDestroyed();
   }

   // ================================================================== 1. pemilih titik terjauh

   void showPicker() {
      if (this.gone()) return;
      if (this.picker != null && this.picker.isShowing()) return;
      this.from = this.fromLabel();
      final Dialog dialog = new Dialog(this.act, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
      this.picker = dialog;
      LinearLayout root = new LinearLayout(this.act);
      root.setOrientation(LinearLayout.VERTICAL);
      root.setBackgroundColor(0xFFF5F5F5);

      LinearLayout header = new LinearLayout(this.act);
      header.setOrientation(LinearLayout.HORIZONTAL);
      header.setGravity(Gravity.CENTER_VERTICAL);
      header.setBackgroundColor(C_PRIMARY);
      header.setPadding(this.dp(4f), this.dp(10f), this.dp(16f), this.dp(10f));
      ImageButton back = new ImageButton(this.act);
      back.setImageResource(R.drawable.ic_arrow_back);
      back.setBackgroundColor(0);
      back.setColorFilter(-1);
      back.setContentDescription("Kembali");
      back.setOnClickListener((v) -> dialog.dismiss());
      header.addView(back);
      TextView title = new TextView(this.act);
      title.setText("🛣️ Kirim Sejalur — Pilih Titik Terjauh");
      title.setTextColor(-1);
      title.setTextSize(16f);
      title.setTypeface(null, Typeface.BOLD);
      title.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
      header.addView(title);
      root.addView(header);

      TextView intro = new TextView(this.act);
      intro.setText("Pilih order yang jadi titik pengiriman TERJAUH. Sistem lalu mengisi muatan dengan antrean "
            + "yang satu jalan dari depot ke titik itu.");
      intro.setTextColor(C_TEXT2);
      intro.setTextSize(13f);
      intro.setPadding(this.dp(14f), this.dp(10f), this.dp(14f), this.dp(2f));
      root.addView(intro);

      final EditText search = new EditText(this.act);
      search.setHint("🔎 Cari nama / area / produk / perangkat…");
      search.setSingleLine(true);
      search.setTextColor(C_TEXT);
      search.setHintTextColor(C_MUTED);
      search.setBackground(this.rounded(-1, 0xFFBDBDBD, 10f));
      search.setPadding(this.dp(14f), this.dp(10f), this.dp(14f), this.dp(10f));
      LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(-1, -2);
      sLp.setMargins(this.dp(12f), this.dp(8f), this.dp(12f), this.dp(4f));
      search.setLayoutParams(sLp);
      root.addView(search);

      LinearLayout sortRow = new LinearLayout(this.act);
      sortRow.setOrientation(LinearLayout.HORIZONTAL);
      sortRow.setPadding(this.dp(12f), this.dp(2f), this.dp(12f), this.dp(2f));
      final MaterialButton btnFar = this.toggleButton("📍 Terjauh");
      final MaterialButton btnOld = this.toggleButton("⏱ Terlama");
      final MaterialButton btnMap = this.toggleButton("🗺 Pilih dari Peta");
      LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(0, -2, 1f);
      bLp.setMarginEnd(this.dp(6f));
      btnFar.setLayoutParams(bLp);
      LinearLayout.LayoutParams bLp2 = new LinearLayout.LayoutParams(0, -2, 1f);
      bLp2.setMarginEnd(this.dp(6f));
      btnOld.setLayoutParams(bLp2);
      btnMap.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.3f));
      sortRow.addView(btnFar);
      sortRow.addView(btnOld);
      sortRow.addView(btnMap);
      root.addView(sortRow);

      // Saring jenis order: 🛒 Penjualan / ↩ Ambil galon kosong (minimal satu tercentang — melepas yang
      // terakhir menukar ke yang lain, SejalurPlanLogic.kindToggle).
      LinearLayout kindRow = new LinearLayout(this.act);
      kindRow.setOrientation(LinearLayout.HORIZONTAL);
      kindRow.setPadding(this.dp(8f), 0, this.dp(12f), 0);
      final CheckBox cbSales = this.kindCheck("🛒 Penjualan");
      final CheckBox cbPick = this.kindCheck("↩ Ambil galon");
      kindRow.addView(cbSales);
      kindRow.addView(cbPick);
      root.addView(kindRow);

      final TextView note = new TextView(this.act);
      note.setTextColor(C_MUTED);
      note.setTextSize(12f);
      note.setPadding(this.dp(14f), this.dp(2f), this.dp(14f), this.dp(4f));
      root.addView(note);

      RecyclerView rv = new RecyclerView(this.act);
      rv.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 1f));
      rv.setLayoutManager(new LinearLayoutManager(this.act));
      rv.setPadding(this.dp(10f), this.dp(2f), this.dp(10f), this.dp(16f));
      rv.setClipToPadding(false);
      root.addView(rv);

      final TextView empty = new TextView(this.act);
      empty.setText("Tidak ada antrean yang bisa dijadikan titik terjauh.");
      empty.setTextColor(C_MUTED);
      empty.setTextSize(14f);
      empty.setGravity(Gravity.CENTER);
      empty.setPadding(this.dp(24f), this.dp(32f), this.dp(24f), this.dp(24f));
      empty.setVisibility(View.GONE);
      root.addView(empty);

      final List<Target> all = new ArrayList<>(this.host.sejalurTargets(null));
      final boolean[] byAge = {false};
      final boolean[] kind = {true, true};
      final boolean[] othersLoading = {true};
      final TargetAdapter adapter = new TargetAdapter((t) -> {
         if (!t.selectable()) {
            Toast.makeText(this.act, t.blockReason(), 0).show();
            return;
         }
         dialog.dismiss();
         this.askGalon(t);
      });
      rv.setAdapter(adapter);

      final Runnable render = () -> {
         List<Target> shown = SejalurPlanLogic.filterSort(all, search.getText().toString(), byAge[0], kind[0], kind[1]);
         adapter.setData(shown);
         int ok = SejalurPlanLogic.selectableCount(shown);
         String kindTxt = SejalurPlanLogic.kindLabel(kind[0], kind[1]);
         empty.setText(all.isEmpty() ? "Tidak ada antrean yang bisa dijadikan titik terjauh."
               : "Tidak ada antrean yang cocok dengan pencarian / saringan.");
         note.setText(ok + " order bisa dipilih · " + (kindTxt.isEmpty() ? "" : kindTxt + " · ") + (byAge[0] ? "diurutkan dari yang PALING LAMA menunggu"
               : "diurutkan dari yang PALING JAUH " + this.from) + (othersLoading[0] ? " · memuat antrean perangkat lain…" : ""));
         empty.setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
         rv.setVisibility(shown.isEmpty() ? View.GONE : View.VISIBLE);
         this.markToggle(btnFar, !byAge[0]);
         this.markToggle(btnOld, byAge[0]);
      };
      this.markToggle(btnMap, false);
      btnFar.setOnClickListener((v) -> {
         byAge[0] = false;
         render.run();
      });
      btnOld.setOnClickListener((v) -> {
         byAge[0] = true;
         render.run();
      });
      btnMap.setOnClickListener((v) -> {
         dialog.dismiss();
         this.host.sejalurOpenMap();
      });
      final boolean[] syncing = {false};
      CompoundButton.OnCheckedChangeListener onKind = (b, on) -> {
         if (syncing[0]) return;
         boolean[] k = SejalurPlanLogic.kindToggle(cbSales.isChecked(), cbPick.isChecked(), b == cbSales);
         kind[0] = k[0];
         kind[1] = k[1];
         syncing[0] = true;
         cbSales.setChecked(k[0]);
         cbPick.setChecked(k[1]);
         syncing[0] = false;
         render.run();
      };
      cbSales.setOnCheckedChangeListener(onKind);
      cbPick.setOnCheckedChangeListener(onKind);
      search.addTextChangedListener(new TextWatcher() {
         public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
         public void onTextChanged(CharSequence s, int a, int b, int c) { }
         public void afterTextChanged(Editable s) { render.run(); }
      });
      render.run();
      dialog.setContentView(root);
      dialog.setOnDismissListener((d) -> {
         if (this.picker == dialog) this.picker = null;
      });
      dialog.show();

      // Antrean perangkat lain disegarkan dari server (tab Perangkat Lain bisa basi / belum pernah dibuka).
      final SyncSettings cfg = this.cfg();
      new Thread(() -> {
         JSONArray q = null;
         try {
            q = (new SyncApi(cfg)).devicesQueueAll().optJSONArray("queue");
         } catch (Exception ignored) {
         }
         final JSONArray fq = q;
         this.act.runOnUiThread(() -> {
            if (this.gone() || !dialog.isShowing()) return;
            othersLoading[0] = false;
            if (fq != null) {
               all.clear();
               all.addAll(this.host.sejalurTargets(fq));
            }
            render.run();
            if (fq == null) note.append(" · antrean perangkat lain gagal dimuat (periksa koneksi)");
         });
      }).start();
   }

   private MaterialButton toggleButton(String text) {
      MaterialButton b = new MaterialButton(this.act);
      b.setText(text);
      b.setAllCaps(false);
      b.setTextSize(13f);
      b.setInsetTop(0);
      b.setInsetBottom(0);
      b.setMinHeight(this.dp(44f));
      b.setPadding(this.dp(6f), 0, this.dp(6f), 0);
      b.setStrokeWidth(this.dp(1f));
      b.setStrokeColor(ColorStateList.valueOf(C_PRIMARY));
      return b;
   }

   /** Kotak centang saringan jenis order pemilih (tinggi sentuh ≥ 48 dp). */
   private CheckBox kindCheck(String text) {
      CheckBox cb = new CheckBox(this.act);
      cb.setText(text);
      cb.setTextColor(C_TEXT);
      cb.setTextSize(14f);
      cb.setChecked(true);
      cb.setMinHeight(this.dp(48f));
      cb.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
      return cb;
   }

   /** Baris kapsul produk (+ lencana "↔ melebar"); {@code null} bila tak ada apa pun. */
   @Nullable
   private LinearLayout badgeRow(List<Badge> badges, boolean widened) {
      LinearLayout row = new LinearLayout(this.act);
      row.setOrientation(LinearLayout.HORIZONTAL);
      row.setGravity(Gravity.CENTER_VERTICAL);
      if (widened) row.addView(this.chip("↔ melebar", C_WIDE_FG, C_WIDE_BG));
      if (badges != null) {
         for (Badge b : badges) {
            if (b != null && b.text != null && b.text.length() > 0) row.addView(this.host.sejalurBadgeView(b.text, b.bg));
         }
      }
      return row.getChildCount() > 0 ? row : null;
   }

   /** Aktif = isi biru teks putih (5,8:1); tak aktif = putih teks biru (5,8:1). */
   private void markToggle(MaterialButton b, boolean on) {
      b.setBackgroundTintList(ColorStateList.valueOf(on ? C_PRIMARY : -1));
      b.setTextColor(on ? -1 : C_PRIMARY);
   }

   private GradientDrawable rounded(int fill, int stroke, float radiusDp) {
      GradientDrawable g = new GradientDrawable();
      g.setColor(fill);
      g.setCornerRadius(this.dp(radiusDp));
      if (stroke != 0) g.setStroke(this.dp(1f), stroke);
      return g;
   }

   private TextView chip(String text, int fg, int bg) {
      TextView tv = new TextView(this.act);
      tv.setText(text);
      tv.setTextColor(fg);
      tv.setTextSize(12f);
      tv.setTypeface(null, Typeface.BOLD);
      tv.setBackground(this.rounded(bg, 0, 10f));
      tv.setPadding(this.dp(8f), this.dp(3f), this.dp(8f), this.dp(3f));
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
      lp.setMarginEnd(this.dp(6f));
      tv.setLayoutParams(lp);
      return tv;
   }

   /** Lencana sumber — teks gelap di latar pucat, kontras ≥ 7:1. */
   private TextView sourceChip(String source, String deviceLabel) {
      if (SejalurPlanLogic.SRC_OPEN.equals(source)) return this.chip("🎲 Terbuka", 0xFF4527A0, 0xFFEDE7F6);
      if (SejalurPlanLogic.SRC_OTHER.equals(source)) {
         String d = deviceLabel == null || deviceLabel.trim().isEmpty() ? "Perangkat lain" : deviceLabel.trim();
         return this.chip("📱 " + d, 0xFF37474F, 0xFFECEFF1);
      }
      return this.chip("Saya", C_PRIMARY_DARK, 0xFFE3F2FD);
   }

   private static String galonText(int galon, boolean pickupOnly) {
      return pickupOnly ? "↩ Ambil galon kosong" : "🫙 " + galon + " galon";
   }

   private interface OnPick {
      void pick(Target t);
   }

   /** Daftar pemilih titik terjauh — baris dirakit di kode (tanpa layout XML baru). */
   private final class TargetAdapter extends RecyclerView.Adapter<TargetAdapter.VH> {
      private final OnPick onPick;
      private List<Target> data = new ArrayList<>();

      TargetAdapter(OnPick onPick) {
         this.onPick = onPick;
      }

      void setData(List<Target> rows) {
         this.data = rows != null ? rows : new ArrayList<>();
         this.notifyDataSetChanged();
      }

      @NonNull
      public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
         return new VH(SejalurFlow.this.buildRow());
      }

      public void onBindViewHolder(@NonNull VH h, int position) {
         Target t = this.data.get(position);
         SejalurFlow.this.bindRow(h.itemView, t);
         h.itemView.setOnClickListener((v) -> this.onPick.pick(t));
      }

      public int getItemCount() {
         return this.data.size();
      }

      final class VH extends RecyclerView.ViewHolder {
         VH(View v) {
            super(v);
         }
      }
   }

   private static final int TAG_NAME = 1, TAG_SOURCE = 2, TAG_AREA = 3, TAG_AGE = 4, TAG_DIST = 5, TAG_GALON = 6,
         TAG_ITEMS = 7, TAG_BLOCK = 8, TAG_BADGES = 9;

   private View buildRow() {
      LinearLayout card = new LinearLayout(this.act);
      card.setOrientation(LinearLayout.VERTICAL);
      card.setBackground(this.rounded(C_CARD_BG, 0xFFE0E0E0, 12f));
      card.setPadding(this.dp(12f), this.dp(10f), this.dp(12f), this.dp(10f));
      RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(-1, -2);
      lp.setMargins(this.dp(2f), this.dp(4f), this.dp(2f), this.dp(4f));
      card.setLayoutParams(lp);

      LinearLayout top = new LinearLayout(this.act);
      top.setOrientation(LinearLayout.HORIZONTAL);
      top.setGravity(Gravity.CENTER_VERTICAL);
      TextView name = new TextView(this.act);
      name.setTextColor(C_TEXT);
      name.setTextSize(15f);
      name.setTypeface(null, Typeface.BOLD);
      name.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
      name.setTag(TAG_NAME);
      top.addView(name);
      LinearLayout srcBox = new LinearLayout(this.act);
      srcBox.setTag(TAG_SOURCE);
      top.addView(srcBox);
      card.addView(top);

      TextView area = new TextView(this.act);
      area.setTextColor(C_TEXT2);
      area.setTextSize(13f);
      area.setPadding(0, this.dp(2f), 0, 0);
      area.setTag(TAG_AREA);
      card.addView(area);

      LinearLayout chips = new LinearLayout(this.act);
      chips.setOrientation(LinearLayout.HORIZONTAL);
      chips.setGravity(Gravity.CENTER_VERTICAL);
      chips.setPadding(0, this.dp(6f), 0, 0);
      TextView age = new TextView(this.act);
      age.setTextColor(-1);
      age.setTextSize(12f);
      age.setTypeface(null, Typeface.BOLD);
      age.setPadding(this.dp(8f), this.dp(3f), this.dp(8f), this.dp(3f));
      LinearLayout.LayoutParams aLp = new LinearLayout.LayoutParams(-2, -2);
      aLp.setMarginEnd(this.dp(6f));
      age.setLayoutParams(aLp);
      age.setTag(TAG_AGE);
      chips.addView(age);
      TextView dist = this.chip("", C_PRIMARY_DARK, 0xFFE3F2FD);
      dist.setTag(TAG_DIST);
      chips.addView(dist);
      TextView galon = this.chip("", 0xFF1B5E20, 0xFFE8F5E9);
      galon.setTag(TAG_GALON);
      chips.addView(galon);
      card.addView(chips);

      // Kapsul produk berwarna (bukan HorizontalScrollView: kartu harus tetap bisa diketuk di area ini).
      LinearLayout badges = new LinearLayout(this.act);
      badges.setOrientation(LinearLayout.HORIZONTAL);
      badges.setGravity(Gravity.CENTER_VERTICAL);
      badges.setPadding(0, this.dp(6f), 0, 0);
      badges.setTag(TAG_BADGES);
      card.addView(badges);

      TextView items = new TextView(this.act);
      items.setTextColor(C_MUTED);
      items.setTextSize(12f);
      items.setPadding(0, this.dp(4f), 0, 0);
      items.setTag(TAG_ITEMS);
      card.addView(items);

      TextView block = new TextView(this.act);
      block.setTextColor(C_DANGER);
      block.setTextSize(12f);
      block.setTypeface(null, Typeface.BOLD);
      block.setPadding(0, this.dp(4f), 0, 0);
      block.setTag(TAG_BLOCK);
      card.addView(block);
      return card;
   }

   private void bindRow(View card, Target t) {
      ((TextView) card.findViewWithTag(TAG_NAME)).setText(t.name);
      LinearLayout srcBox = card.findViewWithTag(TAG_SOURCE);
      srcBox.removeAllViews();
      TextView src = this.sourceChip(t.source, t.deviceLabel);
      ((LinearLayout.LayoutParams) src.getLayoutParams()).setMarginEnd(0);
      srcBox.addView(src);
      TextView area = card.findViewWithTag(TAG_AREA);
      area.setText(t.area == null || t.area.isEmpty() ? "" : "📍 " + t.area);
      area.setVisibility(t.area == null || t.area.isEmpty() ? View.GONE : View.VISIBLE);
      TextView age = card.findViewWithTag(TAG_AGE);
      DeliveryQueueActivity.bindElapsedBadge(age, t.elapsedMs);
      TextView dist = card.findViewWithTag(TAG_DIST);
      String km = SejalurPlanLogic.kmText(t.distKm);
      dist.setText(km.isEmpty() ? "📍 jarak ?" : "📍 " + km + " " + this.from);
      dist.setVisibility(t.hasGeo() ? View.VISIBLE : View.GONE);
      ((TextView) card.findViewWithTag(TAG_GALON)).setText(galonText(t.galon, t.pickupOnly));
      LinearLayout badges = card.findViewWithTag(TAG_BADGES);
      badges.removeAllViews();
      for (Badge b : t.badges) {
         if (b != null && b.text != null && b.text.length() > 0) badges.addView(this.host.sejalurBadgeView(b.text, b.bg));
      }
      badges.setVisibility(badges.getChildCount() > 0 ? View.VISIBLE : View.GONE);
      TextView items = card.findViewWithTag(TAG_ITEMS);
      boolean plain = badges.getChildCount() == 0 && t.items != null && !t.items.isEmpty();
      items.setText(plain ? t.items : "");
      items.setVisibility(plain ? View.VISIBLE : View.GONE);
      TextView block = card.findViewWithTag(TAG_BLOCK);
      String why = t.blockReason();
      block.setText(why.isEmpty() ? "" : "⚠ " + why);
      block.setVisibility(why.isEmpty() ? View.GONE : View.VISIBLE);
      // Baris yang tak bisa dipilih: latar abu-abu, teks TETAP penuh (alpha kartu menurunkan alasan ⚠ di
      // bawah 4,5:1 — justru teks itu yang harus terbaca). Kartu didaur ulang → selalu set keduanya.
      card.setAlpha(1.0F);
      card.setBackground(why.isEmpty() ? this.rounded(C_CARD_BG, 0xFFE0E0E0, 12f)
            : this.rounded(C_CARD_BLOCKED_BG, 0xFFBDBDBD, 12f));
   }

   // ================================================================== 2. galon yang dibawa

   void askGalon(final Target t) {
      if (this.gone() || t == null) return;
      if (!t.selectable()) {
         Toast.makeText(this.act, t.name + ": " + t.blockReason(), 1).show();
         return;
      }
      int pad = this.dp(20f);
      LinearLayout box = new LinearLayout(this.act);
      box.setOrientation(LinearLayout.VERTICAL);
      box.setPadding(pad, this.dp(8f), pad, 0);

      TextView head = new TextView(this.act);
      head.setText("🏁 " + t.name);
      head.setTextColor(C_TEXT);
      head.setTextSize(16f);
      head.setTypeface(null, Typeface.BOLD);
      box.addView(head);
      StringBuilder meta = new StringBuilder();
      long ageMin = t.elapsedMs / 60000L;
      String ageTxt = SejalurPlanLogic.ageText(t.elapsedMs < 0L && !t.scheduled() ? 0L : ageMin);
      if (!ageTxt.isEmpty()) meta.append("⏱ ").append(ageTxt);
      String km = SejalurPlanLogic.kmText(t.distKm);
      if (!km.isEmpty()) meta.append(meta.length() > 0 ? " · " : "").append("📍 ").append(km).append(' ').append(this.fromLabel());
      meta.append(meta.length() > 0 ? " · " : "").append(galonText(t.galon, t.pickupOnly));
      TextView tvMeta = new TextView(this.act);
      tvMeta.setText(meta.toString());
      tvMeta.setTextColor(C_TEXT2);
      tvMeta.setTextSize(13f);
      tvMeta.setPadding(0, this.dp(2f), 0, 0);
      box.addView(tvMeta);
      LinearLayout headBadges = this.badgeRow(t.badges, false);
      if (headBadges != null) {
         headBadges.setPadding(0, this.dp(6f), 0, this.dp(2f));
         HorizontalScrollView hs = new HorizontalScrollView(this.act);
         hs.setHorizontalScrollBarEnabled(false);
         hs.addView(headBadges);
         box.addView(hs);
      } else if (t.items != null && !t.items.isEmpty()) {
         box.addView(this.smallNote(t.items, C_MUTED));
      }
      if (t.area != null && !t.area.isEmpty()) {
         TextView tvArea = new TextView(this.act);
         tvArea.setText("📍 " + t.area);
         tvArea.setTextColor(C_MUTED);
         tvArea.setTextSize(12f);
         box.addView(tvArea);
      }

      TextView lbl = new TextView(this.act);
      lbl.setText("Bawa berapa galon?");
      lbl.setTextColor(C_TEXT);
      lbl.setTypeface(null, Typeface.BOLD);
      lbl.setPadding(0, this.dp(12f), 0, 0);
      box.addView(lbl);
      final EditText etGal = new EditText(this.act);
      etGal.setInputType(InputType.TYPE_CLASS_NUMBER);
      etGal.setTextColor(C_TEXT);
      etGal.setHint("mis. 40");
      etGal.setHintTextColor(C_MUTED);
      int maxLoad = this.cfg().getMaxLoad();
      if (maxLoad > 0) etGal.setText(String.valueOf(maxLoad));
      box.addView(etGal);
      TextView capNote = new TextView(this.act);
      capNote.setText(maxLoad > 0 ? "Kapasitas perangkat: " + maxLoad + " galon (boleh diubah)."
            : "Kapasitas perangkat belum diatur di dashboard — isi jumlah galon yang dibawa.");
      capNote.setTextColor(C_MUTED);
      capNote.setTextSize(12f);
      box.addView(capNote);

      final CheckBox cbPickup = new CheckBox(this.act);
      cbPickup.setText("Ikut ambil galon kosong (KEMBALI) yang sejalur");
      cbPickup.setTextColor(C_TEXT);
      cbPickup.setChecked(true);
      box.addView(cbPickup);
      final CheckBox cbOther = new CheckBox(this.act);
      cbOther.setText("Ikut antrean perangkat lain");
      cbOther.setTextColor(C_TEXT);
      cbOther.setChecked(true);
      box.addView(cbOther);

      ScrollView sv = new ScrollView(this.act);
      sv.addView(box);
      final AlertDialog dlg = (new AlertDialog.Builder(this.act))
            .setTitle("🛣️ Kirim Sejalur")
            .setView(sv)
            .setPositiveButton("🔎 Cari Order Sejalur", (DialogInterface.OnClickListener) null)
            .setNeutralButton("Ganti Titik", (DialogInterface.OnClickListener) null)
            .setNegativeButton("Batal", (DialogInterface.OnClickListener) null)
            .create();
      dlg.setOnShowListener((x) -> {
         final Button pos = dlg.getButton(AlertDialog.BUTTON_POSITIVE);
         dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener((v) -> {
            dlg.dismiss();
            this.showPicker();
         });
         pos.setOnClickListener((v) -> {
            int galon;
            try {
               galon = Integer.parseInt(etGal.getText().toString().trim());
            } catch (NumberFormatException e) {
               galon = 0;
            }
            if (galon <= 0) {
               etGal.setError("Isi jumlah galon");
               etGal.requestFocus();
               return;
            }
            if (galon > 1000) {
               etGal.setError("Maksimal 1000 galon");
               etGal.requestFocus();
               return;
            }
            this.requestPlan(new PlanReq(t, galon, cbPickup.isChecked(), cbOther.isChecked(), 0), dlg,
                  Collections.singletonList(pos), null, null);
         });
      });
      dlg.show();
   }

   private void putFix(JSONObject body) throws Exception {
      double[] fix = this.host.sejalurMyFix();
      if (fix != null && SejalurPlanLogic.isGeo(fix[0], fix[1])) {
         body.put("lat", fix[0]);
         body.put("lng", fix[1]);
      }
   }

   /** Satu permintaan /sejalur/plan — disimpan supaya "↔ Perlebar jalur" mengulang dengan isian yang sama. */
   private static final class PlanReq {
      final Target t;
      final int galon;
      final boolean includePickup;
      final boolean includeOther;
      final int widen;

      PlanReq(Target t, int galon, boolean includePickup, boolean includeOther, int widen) {
         this.t = t;
         this.galon = galon;
         this.includePickup = includePickup;
         this.includeOther = includeOther;
         this.widen = widen;
      }

      PlanReq withWiden(int w) {
         return new PlanReq(this.t, this.galon, this.includePickup, this.includeOther, w);
      }
   }

   /** Permintaan rencana sedang berjalan — tekan beruntun (Cari / Perlebar jalur) diabaikan. */
   private boolean planning;

   /**
    * @param prev        dialog yang ditutup begitu rencana datang (dialog galon / pratinjau lama)
    * @param lock        tombol yang dinonaktifkan selama permintaan (dinyalakan lagi bila gagal)
    * @param prevShown   uuid yang tampil di pratinjau lama (null = pratinjau pertama) — centang kurir dibawa
    * @param prevChecked uuid yang tercentang di pratinjau lama
    */
   private void requestPlan(final PlanReq req, final Dialog prev, final List<Button> lock,
                            @Nullable final Set<String> prevShown, @Nullable final Set<String> prevChecked) {
      final SyncSettings cfg = this.cfg();
      if (!cfg.isEnrolled()) {
         Toast.makeText(this.act, "Perangkat belum terhubung ke server.", 1).show();
         return;
      }
      if (this.planning) return;
      this.planning = true;
      for (Button b : lock) b.setEnabled(false);
      final android.app.ProgressDialog wait = new android.app.ProgressDialog(this.act);
      wait.setMessage(req.widen > 0 ? "Memperlebar jalur…" : "Mencari order yang sejalur…");
      wait.setCancelable(false);
      wait.show();
      new Thread(() -> {
         JSONObject plan = null;
         String err = null;
         try {
            JSONObject body = new JSONObject();
            body.put("target_uuid", req.t.uuid);
            body.put("max_galon", req.galon);
            body.put("include_pickup", req.includePickup);
            body.put("include_other", req.includeOther);
            if (req.widen > 0) body.put("widen", req.widen);
            this.putFix(body);
            plan = (new SyncApi(cfg)).sejalurPlan(body);
         } catch (SyncApi.SyncException se) {
            String m = se.serverMessage();
            err = m != null ? m : "Gagal menyusun rencana (kode " + se.code + ").";
         } catch (Exception e) {
            err = "Gagal menyusun rencana — periksa koneksi internet.";
         }
         final JSONObject fp = plan;
         final String fErr = err;
         this.act.runOnUiThread(() -> {
            try { wait.dismiss(); } catch (Exception ignored) {}
            this.planning = false;
            if (this.gone()) return;
            for (Button b : lock) b.setEnabled(true);
            if (fp == null) {
               (new AlertDialog.Builder(this.act)).setTitle("🛣️ Kirim Sejalur").setMessage(fErr)
                     .setPositiveButton("OK", (DialogInterface.OnClickListener) null).show();
               return;
            }
            try { prev.dismiss(); } catch (Exception ignored) {}
            this.showPreview(req, fp, prevShown, prevChecked);
         });
      }).start();
   }

   // ================================================================== 3. pratinjau rencana

   private PlanRow planRow(JSONObject o, boolean alternate, long nowMs) {
      PlanRow r = new PlanRow();
      r.uuid = o.optString("uuid", "");
      r.name = str(o, "name", "Pelanggan");
      r.source = str(o, "source", SejalurPlanLogic.SRC_MINE);
      r.deviceLabel = str(o, "device_label", "");
      r.items = str(o, "items", "");
      r.galon = o.optInt("galon", 0);
      r.pickupOnly = o.optBoolean("pickup_only", false);
      r.priority = o.optBoolean("is_priority", false) || o.optInt("order_priority_level", 0) > 0;
      r.isTarget = o.optBoolean("is_target", false);
      r.alternate = alternate;
      r.kmFromOrigin = o.optDouble("km_from_origin", 0.0);
      r.detourKm = o.optDouble("detour_km", 0.0);
      if (Double.isNaN(r.kmFromOrigin)) r.kmFromOrigin = 0.0;
      if (Double.isNaN(r.detourKm)) r.detourKm = 0.0;
      r.ageMin = SejalurPlanLogic.ageMinutes(o.has("age_minutes") ? o.optLong("age_minutes", -1L) : -1L,
            Ts.millis(str(o, "queued_at", "")), nowMs);
      r.widenLevel = Math.max(0, o.optInt("widen_level", 0));
      try {
         List<Badge> b = this.host.sejalurBadges(o);
         if (b != null) r.badges = b;
      } catch (Exception ignored) {
      }
      return r;
   }

   /** optString yang menganggap JSON null / "null" sebagai kosong. */
   private static String str(JSONObject o, String key, String fallback) {
      if (o == null || o.isNull(key)) return fallback;
      String v = o.optString(key, fallback);
      return v == null || "null".equals(v) ? fallback : v;
   }

   private List<PlanRow> planRows(@Nullable JSONArray arr, boolean alternate) {
      List<PlanRow> out = new ArrayList<>();
      long now = System.currentTimeMillis();
      if (arr == null) return out;
      for (int i = 0; i < arr.length(); i++) {
         JSONObject o = arr.optJSONObject(i);
         if (o == null || o.optString("uuid", "").isEmpty()) continue;
         out.add(this.planRow(o, alternate, now));
      }
      return out;
   }

   private static List<WidenOption> widenOptions(@Nullable JSONArray arr) {
      List<WidenOption> out = new ArrayList<>();
      for (int i = 0; arr != null && i < arr.length(); i++) {
         JSONObject o = arr.optJSONObject(i);
         if (o == null) continue;
         out.add(new WidenOption(o.optInt("level", 0), o.optDouble("detour_max_km", Double.NaN), o.optInt("orders", 0),
               o.optInt("galon", 0), o.optInt("added_orders", 0), o.optInt("added_galon", 0)));
      }
      return out;
   }

   private CharSequence planRowText(int no, PlanRow r) {
      SpannableStringBuilder sb = new SpannableStringBuilder();
      String head = (no > 0 ? no + ". " : "") + (r.isTarget ? "🏁 " : "") + (r.priority ? "⚡ " : "") + r.name;
      sb.append(head);
      sb.setSpan(new StyleSpan(Typeface.BOLD), 0, head.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
      StringBuilder meta = new StringBuilder();
      meta.append(galonText(r.galon, r.pickupOnly));
      if (SejalurPlanLogic.SRC_OPEN.equals(r.source)) meta.append(" · 🎲 Terbuka");
      else if (SejalurPlanLogic.SRC_OTHER.equals(r.source)) meta.append(" · 📱 ").append(r.deviceLabel.isEmpty() ? "Perangkat lain" : r.deviceLabel);
      else meta.append(" · Saya");
      String age = SejalurPlanLogic.ageText(r.ageMin);
      if (!age.isEmpty()) meta.append(" · ⏱ ").append(age);
      if (r.isTarget) {
         meta.append("\n🏁 Titik terjauh — selalu ikut, diantar paling akhir");
         String km = SejalurPlanLogic.kmText(r.kmFromOrigin);
         if (!km.isEmpty() && r.kmFromOrigin > 0) meta.append(" · ").append(km);
      } else if (r.detourKm >= 0.05) {
         meta.append(" · +").append(SejalurPlanLogic.kmText(r.detourKm)).append(" memutar");
      } else {
         meta.append(" · searah");
      }
      // Produk = kapsul berwarna di bawah kotak (planRowView); teks hanya bila server tak mengirim item_slugs.
      if (!r.items.isEmpty() && r.badges.isEmpty()) meta.append('\n').append(r.items);
      sb.append('\n').append(meta);
      return sb;
   }

   private CheckBox planCheck(CharSequence text) {
      CheckBox cb = new CheckBox(this.act);
      cb.setText(text);
      cb.setTextColor(C_TEXT);
      cb.setTextSize(13f);
      cb.setPadding(this.dp(4f), this.dp(6f), 0, this.dp(6f));
      return cb;
   }

   /** Kotak centang baris rencana + kapsul produk / "↔ melebar" di bawahnya (sejajar teks kotak). */
   private View planRowView(CheckBox cb, PlanRow r) {
      LinearLayout badges = this.badgeRow(r.badges, r.widenLevel > 0);
      if (badges == null) return cb;
      LinearLayout wrap = new LinearLayout(this.act);
      wrap.setOrientation(LinearLayout.VERTICAL);
      wrap.addView(cb);
      HorizontalScrollView hs = new HorizontalScrollView(this.act);
      hs.setHorizontalScrollBarEnabled(false);
      hs.setPadding(this.dp(36f), 0, 0, this.dp(6f));
      hs.addView(badges);
      wrap.addView(hs);
      return wrap;
   }

   private TextView sectionHeader(String text) {
      TextView tv = new TextView(this.act);
      tv.setText(text);
      tv.setTextColor(C_TEXT);
      tv.setTypeface(null, Typeface.BOLD);
      tv.setTextSize(13f);
      tv.setPadding(0, this.dp(12f), 0, this.dp(2f));
      return tv;
   }

   private TextView smallNote(String text, int color) {
      TextView tv = new TextView(this.act);
      tv.setText(text);
      tv.setTextColor(color);
      tv.setTextSize(12f);
      tv.setPadding(0, this.dp(2f), 0, this.dp(2f));
      return tv;
   }

   /** Tombol "↔ Perlebar jalur" — yang utama terisi (putih di biru tua 8,6:1), lainnya garis tepi (8,6:1). */
   private MaterialButton widenButton(String text, boolean primary) {
      MaterialButton b = new MaterialButton(this.act);
      b.setText(text);
      b.setAllCaps(false);
      b.setTextSize(primary ? 14f : 13f);
      b.setMinHeight(this.dp(48f));
      b.setInsetTop(0);
      b.setInsetBottom(0);
      b.setStrokeWidth(this.dp(1f));
      b.setStrokeColor(ColorStateList.valueOf(C_PRIMARY_DARK));
      b.setBackgroundTintList(ColorStateList.valueOf(primary ? C_PRIMARY_DARK : -1));
      b.setTextColor(primary ? -1 : C_PRIMARY_DARK);
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
      lp.setMargins(0, this.dp(6f), 0, 0);
      b.setLayoutParams(lp);
      return b;
   }

   /**
    * @param prevShown   uuid pratinjau sebelumnya (Perlebar jalur) — null = pratinjau pertama
    * @param prevChecked centang kurir di pratinjau sebelumnya ({@link SejalurPlanLogic#carryChecks})
    */
   private void showPreview(final PlanReq req, JSONObject plan, @Nullable Set<String> prevShown, @Nullable Set<String> prevChecked) {
      if (this.gone()) return;
      final Target t = req.t;
      final List<PlanRow> main = this.planRows(plan.optJSONArray("orders"), false);
      final List<PlanRow> alts = this.planRows(plan.optJSONArray("alternates"), true);
      // Titik terjauh = baris is_target (server), dipaksa PALING AKHIR; tak ada sama sekali → dari pilihan kurir.
      PlanRow target = null;
      for (PlanRow r : main) {
         if (r.isTarget || r.uuid.equals(t.uuid)) {
            target = r;
            break;
         }
      }
      if (target == null) {
         target = new PlanRow();
         target.uuid = t.uuid;
         target.name = t.name;
         target.galon = t.galon;
         target.pickupOnly = t.pickupOnly;
         target.source = t.source;
         target.deviceLabel = t.deviceLabel;
         target.ageMin = t.elapsedMs / 60000L;
         target.badges = t.badges;
      } else {
         main.remove(target);
      }
      target.isTarget = true;
      target.widenLevel = 0;
      main.add(target);
      final String targetUuid = target.uuid;
      for (int i = alts.size() - 1; i >= 0; i--) {
         if (alts.get(i).uuid.equals(targetUuid)) alts.remove(i);
      }

      final int capacity = plan.optInt("max_galon", req.galon);
      final String method = str(plan, "distance_method", "straight_line");
      final double routeKm = plan.optDouble("route_km", target.kmFromOrigin);
      final int widen = Math.max(0, plan.optInt("widen", req.widen));
      final String widenState = plan.has("widen") ? SejalurPlanLogic.widenStateText(widen, plan.optDouble("detour_max_km", Double.NaN)) : "";
      final boolean overCap = plan.optBoolean("over_capacity", false);
      final List<WidenOption> choices = SejalurPlanLogic.widenChoices(widenOptions(plan.optJSONArray("widen_options")), widen,
            plan.optInt("galon", 0), capacity, overCap);
      final List<PlanRow> everything = new ArrayList<>(main);
      everything.addAll(alts);
      // Pratinjau pertama: semua baris rencana tercentang. Sesudah Perlebar jalur: centang/lepas kurir dibawa,
      // baris baru tercentang (SejalurPlanLogic.carryChecks).
      final Set<String> checked = SejalurPlanLogic.carryChecks(main, alts, prevShown, prevChecked);

      int pad = this.dp(18f);
      LinearLayout box = new LinearLayout(this.act);
      box.setOrientation(LinearLayout.VERTICAL);
      box.setPadding(pad, this.dp(6f), pad, this.dp(6f));
      final TextView summary = new TextView(this.act);
      summary.setTextSize(15f);
      summary.setTypeface(null, Typeface.BOLD);
      box.addView(summary);
      // Batas 25 order per Kirim Sejalur: kotak yang belum dicentang dikunci begitu penuh — tak ada order
      // tercentang yang diam-diam terpotong saat start (ringkasan = yang benar-benar dikirim).
      final TextView capWarn = this.smallNote("⚠ Maksimal " + SejalurPlanLogic.MAX_STOPS
            + " order per Kirim Sejalur — lepas centang salah satu untuk menukar.", C_DANGER);
      capWarn.setTypeface(null, Typeface.BOLD);
      capWarn.setVisibility(View.GONE);
      box.addView(capWarn);
      final List<CheckBox> optional = new ArrayList<>();
      JSONObject origin = plan.optJSONObject("origin");
      String src = origin != null ? str(origin, "source", "") : "";
      box.addView(this.smallNote("Dihitung dari " + ("gps".equals(src) ? "posisi HP (lokasi depot belum diatur)"
            : ("last_ping".equals(src) ? "posisi HP terakhir (lokasi depot belum diatur)" : "depot cabang"))
            + ("driving".equals(method) ? " lewat jalan." : " — garis lurus (rute jalan tak tersedia)."), C_MUTED));
      if (overCap) {
         box.addView(this.smallNote("⚠ Titik terjauh sendiri sudah melebihi muatan — tidak ada order lain yang ditambahkan.", C_DANGER));
      }

      // ↔ Perlebar jalur: hanya saat muatan rencana belum penuh dan server menemukan order di koridor yang lebih
      // lebar. Pilihan pertama = langkah berikutnya (tombol utama); tingkat yang lebih lebar menyusul.
      final AlertDialog[] dlgRef = new AlertDialog[1];
      final Button[] actRef = new Button[2];   // {Mulai, Batal}
      final List<Button> widenBtns = new ArrayList<>();
      if (!choices.isEmpty()) {
         box.addView(this.smallNote(main.size() <= 1
               ? "Belum ada order lain di jalur ini — perlebar jalur untuk mencari order di sekitar rute."
               : "Muatan belum penuh — perlebar jalur untuk menambah order di sekitar rute.", C_TEXT2));
         for (int i = 0; i < choices.size(); i++) {
            final WidenOption o = choices.get(i);
            MaterialButton b = this.widenButton(SejalurPlanLogic.widenButtonText(o), i == 0);
            b.setOnClickListener((v) -> {
               Button pos = actRef[0];
               if (dlgRef[0] == null || this.planning || (pos != null && !pos.isEnabled())) return;
               List<Button> lock = new ArrayList<>(widenBtns);
               for (Button x : actRef) if (x != null) lock.add(x);
               Set<String> shown = new HashSet<>();
               for (PlanRow r : everything) shown.add(r.uuid);
               this.requestPlan(req.withWiden(o.level), dlgRef[0], lock, shown, new HashSet<>(checked));
            });
            widenBtns.add(b);
            box.addView(b);
         }
      }

      box.addView(this.sectionHeader("Urutan rute (dekat → jauh, titik terjauh terakhir)"));
      final Runnable recompute = () -> {
         int g = SejalurPlanLogic.checkedGalon(everything, checked);
         int n = SejalurPlanLogic.checkedCount(everything, checked);
         boolean over = capacity > 0 && g > capacity;
         summary.setText(SejalurPlanLogic.summaryLine(n, g, capacity, routeKm, method)
               + (widenState.isEmpty() ? "" : "\n" + widenState)
               + (over ? "\n⚠ Melebihi muatan " + (g - capacity) + " galon" : ""));
         summary.setTextColor(over ? C_DANGER : C_OK);
         boolean full = !SejalurPlanLogic.canCheckMore(everything, checked);
         boolean anyLocked = false;
         for (CheckBox o : optional) {
            boolean lock = full && !o.isChecked();
            o.setEnabled(!lock);
            o.setTextColor(lock ? C_MUTED : C_TEXT);
            anyLocked |= lock;
         }
         capWarn.setVisibility(anyLocked ? View.VISIBLE : View.GONE);
      };
      for (int i = 0; i < main.size(); i++) {
         final PlanRow r = main.get(i);
         CheckBox cb = this.planCheck(this.planRowText(i + 1, r));
         if (r.isTarget) {
            // Titik terjauh selalu ikut — tak bisa dilepas (tetap berwarna normal, bukan abu-abu nonaktif).
            cb.setChecked(true);
            cb.setClickable(false);
            cb.setFocusable(false);
         } else {
            cb.setChecked(checked.contains(r.uuid));
            cb.setOnCheckedChangeListener((b, on) -> {
               if (on) checked.add(r.uuid); else checked.remove(r.uuid);
               recompute.run();
            });
            optional.add(cb);
         }
         box.addView(this.planRowView(cb, r));
      }
      if (main.size() == 1 && alts.isEmpty()) {
         box.addView(this.smallNote("Tidak ada antrean lain yang searah — rit hanya ke titik terjauh.", C_MUTED));
      }
      if (!alts.isEmpty()) {
         box.addView(this.sectionHeader("Sejalur tapi tidak muat (" + alts.size() + ")"));
         box.addView(this.smallNote("Centang untuk ikut dibawa / menukar — total galon dihitung ulang.", C_MUTED));
         for (final PlanRow r : alts) {
            CheckBox cb = this.planCheck(this.planRowText(0, r));
            cb.setChecked(checked.contains(r.uuid));
            cb.setOnCheckedChangeListener((b, on) -> {
               if (on) checked.add(r.uuid); else checked.remove(r.uuid);
               recompute.run();
            });
            optional.add(cb);
            box.addView(this.planRowView(cb, r));
         }
      }
      recompute.run();

      ScrollView sv = new ScrollView(this.act);
      sv.addView(box);
      final AlertDialog dlg = (new AlertDialog.Builder(this.act))
            .setTitle("🛣️ Rencana Kirim Sejalur")
            .setView(sv)
            .setPositiveButton("▶ Mulai Kirim Sejalur", (DialogInterface.OnClickListener) null)
            .setNegativeButton("Batal", (DialogInterface.OnClickListener) null)
            .create();
      dlgRef[0] = dlg;
      dlg.setOnShowListener((x) -> {
         final Button pos = dlg.getButton(AlertDialog.BUTTON_POSITIVE);
         final Button neg = dlg.getButton(AlertDialog.BUTTON_NEGATIVE);
         actRef[0] = pos;
         actRef[1] = neg;
         pos.setOnClickListener((v) -> {
            if (!pos.isEnabled() || this.planning) return;
            List<String> over = SejalurPlanLogic.startOverflow(main, alts, checked, targetUuid);
            if (!over.isEmpty()) {
               Toast.makeText(this.act, "Maksimal " + SejalurPlanLogic.MAX_STOPS + " order per Kirim Sejalur — lepas centang "
                     + over.size() + " order dulu.", 1).show();
               return;
            }
            // Dicek ulang tepat sebelum start: selama pratinjau terbuka rit bisa sudah berjalan (rencana lain
            // tersinkron) — start baru akan menimpa rencana itu / menggabung ke rit yang tak berhubungan.
            String busy = this.host.sejalurBlock();
            if (busy != null) {
               dlg.dismiss();
               Toast.makeText(this.act, busy, 1).show();
               return;
            }
            List<String> uuids = SejalurPlanLogic.startUuids(main, alts, checked, targetUuid);
            Map<String, String> names = new LinkedHashMap<>();
            for (PlanRow r : everything) names.put(r.uuid, r.name);
            Map<String, Long> openIds = this.host.sejalurOpenDispatchIds();
            pos.setEnabled(false);
            neg.setEnabled(false);
            for (Button b : widenBtns) b.setEnabled(false);
            pos.setText("Memulai…");
            dlg.setCancelable(false);
            this.requestStart(dlg, pos, neg, widenBtns, targetUuid, names.get(targetUuid), uuids, openIds, widen);
         });
      });
      dlg.show();
   }

   // ================================================================== 4. mulai + hasil

   /**
    * Start 200 → simpan di THREAD KERJA, sebelum lompat ke UI: klaim lokal Pesanan Terbuka yang ikut rit,
    * rencana yang menunggu (urutan rit server, titik terjauh terakhir), lalu sinkron. Server sudah
    * mengambil alih order & menulis delivery_seq — bila layar dibuat ulang (rotasi) selama permintaan,
    * layar baru tetap menjalankan rit dari rencana ini di onResume (applyList → absorbSejalurPlan).
    */
   static void persistStart(Context ctx, SyncSettings cfg, String targetUuid, List<String> rit, boolean targetHeld,
                            @Nullable Map<String, Long> openDispatchIds) {
      if (rit == null || rit.isEmpty()) return;
      DatabaseHelper db = DatabaseHelper.getInstance(ctx);
      TransactionDao dao = new TransactionDao(db);
      String me = cfg.getDeviceUuid();
      for (String u : rit) {
         Long id = openDispatchIds != null ? openDispatchIds.get(u) : null;
         if (id != null && id > 0L) dao.markClaimedLocally(id, me);
      }
      (new SettingsDao(db)).setSejalurPending(SejalurPlanLogic.encodePending(
            new SejalurPlanLogic.Pending(System.currentTimeMillis(), targetHeld ? targetUuid : "", rit)));
      SyncScheduler.syncNow(ctx.getApplicationContext());
   }

   private void requestStart(AlertDialog dlg, Button pos, Button neg, List<Button> also, String targetUuid, String targetName,
                             List<String> uuids, Map<String, Long> openDispatchIds, int widen) {
      final SyncSettings cfg = this.cfg();
      final Context app = this.act.getApplicationContext();
      new Thread(() -> {
         JSONObject ok = null;
         String err = null;
         JSONArray errSkipped = null;
         boolean retry = true;
         List<String> rit = new ArrayList<>();
         boolean held = false;
         try {
            JSONObject body = new JSONObject();
            body.put("target_uuid", targetUuid);
            body.put("uuids", new JSONArray(uuids));
            if (widen > 0) body.put("widen", widen);   // hanya dicatat server (start tak menilai ulang koridor)
            this.putFix(body);
            ok = (new SyncApi(cfg)).sejalurStart(body);
            JSONArray ra = ok.optJSONArray("rit_uuids");
            if (ra != null) {
               for (int i = 0; i < ra.length(); i++) {
                  String u = ra.optString(i, "");
                  if (!u.isEmpty() && !"null".equals(u) && !rit.contains(u)) rit.add(u);
               }
            }
            held = ok.optBoolean("target_held", rit.contains(targetUuid));
            try {
               persistStart(app, cfg, targetUuid, rit, held, openDispatchIds);
            } catch (Exception ignored) {
            }
         } catch (SyncApi.SyncException se) {
            String m = se.serverMessage();
            err = m != null ? m : "Gagal memulai Kirim Sejalur (kode " + se.code + ").";
            try {
               errSkipped = (new JSONObject(se.body)).optJSONArray("skipped");
            } catch (Exception ignored) {
            }
            retry = se.code == 429 || se.code >= 500;
         } catch (Exception e) {
            err = "Gagal memulai — periksa koneksi internet.";
         }
         final JSONObject fOk = ok;
         final String fErr = err;
         final JSONArray fSkipped = errSkipped;
         final boolean fRetry = retry;
         final List<String> fRit = rit;
         final boolean fHeld = held;
         this.act.runOnUiThread(() -> {
            if (this.gone()) {
               // Layar sudah dibuat ulang: rencana tersimpan (persistStart), layar baru menjalankan rit-nya.
               if (fOk != null) Toast.makeText(app, "🛣️ " + str(fOk, "message", "Kirim Sejalur dimulai."), 1).show();
               return;
            }
            if (fOk == null) {
               if (fRetry) {
                  pos.setEnabled(true);
                  neg.setEnabled(true);
                  for (Button x : also) x.setEnabled(true);
                  pos.setText("▶ Mulai Kirim Sejalur");
                  dlg.setCancelable(true);
                  Toast.makeText(this.act, fErr, 1).show();
               } else {
                  dlg.dismiss();
                  (new AlertDialog.Builder(this.act)).setIcon(17301543).setTitle("⛔ Kirim Sejalur Gagal")
                        .setMessage(fErr + skippedText(fSkipped))
                        .setPositiveButton("OK", (DialogInterface.OnClickListener) null).show();
               }
               return;
            }
            dlg.dismiss();
            int[] r = fRit.isEmpty() ? new int[]{0, 0} : this.host.sejalurApplyStart(fRit);
            this.showResult(fOk, fRit.size(), r[1], fHeld, targetName);
         });
      }).start();
   }

   private static String skippedText(@Nullable JSONArray skipped) {
      if (skipped == null || skipped.length() == 0) return "";
      StringBuilder sb = new StringBuilder("\n\nDilewati:");
      for (int i = 0; i < skipped.length(); i++) {
         JSONObject s = skipped.optJSONObject(i);
         if (s == null) continue;
         sb.append("\n• ").append(str(s, "name", "Order")).append(" — ").append(str(s, "reason", "tidak bisa diambil"));
      }
      return sb.toString();
   }

   private void showResult(JSONObject resp, int ritCount, int waiting, boolean targetHeld, @Nullable String targetName) {
      if (this.gone()) return;
      JSONArray skipped = resp.optJSONArray("skipped");
      boolean partial = !targetHeld || (skipped != null && skipped.length() > 0);
      StringBuilder msg = new StringBuilder();
      String m = str(resp, "message", "");
      if (!m.isEmpty()) msg.append(m).append("\n\n");
      msg.append("🚚 ").append(ritCount).append(" order · ").append(resp.optInt("galon", 0)).append(" galon masuk rit");
      if (targetHeld) msg.append(", titik terjauh diantar paling akhir");
      msg.append('.');
      if (waiting > 0) {
         msg.append("\n⏳ ").append(waiting).append(" order dari perangkat lain masuk rit otomatis begitu tersinkron ke HP ini.");
      }
      if (!targetHeld) {
         msg.append("\n\n⚠️ Titik terjauh").append(targetName != null && !targetName.isEmpty() ? " (" + targetName + ")" : "")
               .append(" TIDAK berhasil diambil — rit berjalan tanpa titik itu.");
      }
      msg.append(skippedText(skipped));
      (new AlertDialog.Builder(this.act))
            .setTitle(partial ? "⚠️ Kirim Sejalur Dimulai Sebagian" : "✅ Kirim Sejalur Dimulai")
            .setMessage(msg.toString())
            .setPositiveButton("OK", (DialogInterface.OnClickListener) null)
            .show();
   }
}
