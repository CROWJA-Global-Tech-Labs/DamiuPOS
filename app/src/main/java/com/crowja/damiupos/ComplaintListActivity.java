package com.crowja.damiupos;

import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.SettingsDao;
import com.crowja.damiupos.db.TransactionDao;
import com.crowja.damiupos.model.Transaction;
import com.crowja.damiupos.model.TransactionItem;
import com.crowja.damiupos.util.SejalurPlanLogic;
import com.crowja.damiupos.util.Ts;
import com.google.android.material.button.MaterialButton;

import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 😠 Daftar komplain pesanan untuk perangkat ini, dihalaman PER HARI (hari komplain, waktu lokal). Komplain AKTIF =
 * pesanan dikomplain yang BELUM diselesaikan (PENDING/Tertunda); SELESAI = pesanannya sudah diselesaikan. Jumlah aktif
 * = badge menu Komplain ({@link TransactionDao#countActiveComplaints}).
 *
 * <p>Tiap kartu: 🧾 Preview pesanan, ➕ Tambah ke rit berjalan, 🛣️ Kirim Sejalur ke order ini, 💬 Chat WA (aplikasi
 * WhatsApp). Ketuk kartu → log komplain (bisa dibalas).</p>
 */
public class ComplaintListActivity extends AppCompatActivity {

    private static final int C_TEXT = 0xFF212121;
    private static final int C_MUTED = 0xFF616161;
    private static final int C_RED = 0xFFB71C1C;
    private static final int C_GREEN = 0xFF1B5E20;
    private static final int C_BLUE = 0xFF1565C0;

    private TransactionDao dao;
    private LinearLayout box;
    private TextView summary;
    private TextView dayLabel;
    private TextView otherDays;
    private MaterialButton btnOlder;
    private MaterialButton btnNewer;
    private final NumberFormat nf = NumberFormat.getInstance(new Locale("id", "ID"));

    /** Pencarian (nama / struk / produk / telepon / perangkat). Terisi = daftar datar semua hari, tanpa pager. */
    private String query = "";
    /** Admin: lihat komplain SEMUA perangkat cabang (data server), bukan hanya perangkat ini. */
    private boolean isAdmin;
    private boolean remoteMode;
    private boolean remoteLoading;
    private MaterialButton btnLocal;
    private MaterialButton btnRemote;
    private final List<Transaction> remoteAll = new ArrayList<>();
    /** Baris dari server: uuid transaksi, uuid pelanggan, nama perangkat pemegang (kunci = identitas objek). */
    private final java.util.IdentityHashMap<Transaction, String[]> remoteMeta = new java.util.IdentityHashMap<>();
    private final Map<String, Integer> slugColors = new java.util.HashMap<>();
    /** uuid transaksi yang pelanggannya SUDAH order lagi hari ini (jawaban server); orderedKnown = jawaban sudah ada. */
    private final java.util.Set<String> orderedToday = new java.util.HashSet<>();
    private boolean orderedKnown;
    private boolean orderedLoading;
    private MaterialButton btnClear;
    /** Komplain SELESAI yang dibuka manual (kunci = uuid transaksi); yang selesai default diringkas. */
    private final java.util.Set<String> expanded = new java.util.HashSet<>();

    /** Hari (yyyy-MM-dd lokal) → komplain hari itu; hari terbaru lebih dulu. */
    private final LinkedHashMap<String, List<Transaction>> byDay = new LinkedHashMap<>();
    private List<String> days = new ArrayList<>();
    private String currentDay;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("😠 Komplain & Masalah");
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        dao = new TransactionDao(DatabaseHelper.getInstance(this));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFF5F5F5);

        summary = new TextView(this);
        summary.setTextSize(14f);
        summary.setTypeface(null, Typeface.BOLD);
        summary.setPadding(dp(16), dp(10), dp(16), dp(2));
        root.addView(summary);

        com.crowja.damiupos.model.User me = new com.crowja.damiupos.db.UserDao(DatabaseHelper.getInstance(this))
                .getById(new SettingsDao(DatabaseHelper.getInstance(this)).getCurrentUserId());
        isAdmin = me != null && me.isAdmin();
        if (isAdmin) {
            LinearLayout scope = new LinearLayout(this);
            scope.setOrientation(LinearLayout.HORIZONTAL);
            scope.setPadding(dp(12), dp(2), dp(12), dp(2));
            btnLocal = smallButton("📱 Perangkat ini");
            btnRemote = smallButton("🌐 Semua perangkat");
            btnClear = smallButton("🧹 Bersihkan");
            btnClear.setTextColor(C_RED);
            scope.addView(btnLocal, new LinearLayout.LayoutParams(0, -2, 1f));
            scope.addView(btnRemote, new LinearLayout.LayoutParams(0, -2, 1f));
            scope.addView(btnClear, new LinearLayout.LayoutParams(0, -2, 1f));
            btnClear.setOnClickListener(v -> chooseClear());
            root.addView(scope);
            btnLocal.setOnClickListener(v -> setRemote(false));
            btnRemote.setOnClickListener(v -> setRemote(true));
            styleScope();
        }

        LinearLayout kinds = new LinearLayout(this);
        kinds.setOrientation(LinearLayout.HORIZONTAL);
        kinds.setPadding(dp(12), dp(2), dp(12), dp(2));
        final String[] kindNames = {"Semua", "😠 Komplain", "⚠️ Masalah"};
        final MaterialButton[] kindBtns = new MaterialButton[3];
        for (int i = 0; i < 3; i++) {
            final int k = i;
            kindBtns[i] = smallButton(kindNames[i]);
            kindBtns[i].setOnClickListener(v -> {
                kindFilter = k;
                for (int j = 0; j < 3; j++) kindBtns[j].setBackgroundTintList(android.content.res.ColorStateList.valueOf(j == k ? 0xFFBBDEFB : 0x00FFFFFF));
                load();
                render();
            });
            kinds.addView(kindBtns[i], new LinearLayout.LayoutParams(0, -2, 1f));
        }
        kindBtns[0].setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFFBBDEFB));
        root.addView(kinds);

        final EditText et = new EditText(this);
        et.setHint("🔎 Cari nama / struk / produk / telepon…");
        et.setSingleLine(true);
        et.setTextColor(C_TEXT);
        et.setHintTextColor(C_MUTED);
        et.setPadding(dp(14), dp(10), dp(14), dp(10));
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(-1, -2);
        elp.setMargins(dp(12), dp(4), dp(12), dp(4));
        et.setLayoutParams(elp);
        android.graphics.drawable.GradientDrawable ebg = new android.graphics.drawable.GradientDrawable();
        ebg.setColor(0xFFFFFFFF);
        ebg.setCornerRadius(dp(10));
        ebg.setStroke(dp(1), 0xFFBDBDBD);
        et.setBackground(ebg);
        et.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { }
            public void afterTextChanged(Editable s) {
                query = s.toString().trim().toLowerCase(Locale.ROOT);
                load();
                render();
            }
        });
        root.addView(et);

        otherDays = new TextView(this);
        otherDays.setTextSize(13f);
        otherDays.setTextColor(C_RED);
        otherDays.setTypeface(null, Typeface.BOLD);
        otherDays.setPadding(dp(16), dp(2), dp(16), dp(4));
        otherDays.setVisibility(View.GONE);
        root.addView(otherDays);

        // Pager hari: ◀ lebih lama · tanggal · lebih baru ▶
        LinearLayout pager = new LinearLayout(this);
        pager.setOrientation(LinearLayout.HORIZONTAL);
        pager.setGravity(Gravity.CENTER_VERTICAL);
        pager.setPadding(dp(8), dp(4), dp(8), dp(4));
        btnOlder = smallButton("◀");
        btnNewer = smallButton("▶");
        dayLabel = new TextView(this);
        dayLabel.setGravity(Gravity.CENTER);
        dayLabel.setTextColor(C_TEXT);
        dayLabel.setTextSize(15f);
        dayLabel.setTypeface(null, Typeface.BOLD);
        pager.addView(btnOlder, new LinearLayout.LayoutParams(dp(56), -2));
        pager.addView(dayLabel, new LinearLayout.LayoutParams(0, -2, 1f));
        pager.addView(btnNewer, new LinearLayout.LayoutParams(dp(56), -2));
        root.addView(pager);
        btnOlder.setOnClickListener(v -> step(+1));
        btnNewer.setOnClickListener(v -> step(-1));

        ScrollView sv = new ScrollView(this);
        box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(4), dp(12), dp(16));
        sv.addView(box);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
        render();
        if (remoteMode) loadRemote();
        fetchOrderedToday();
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    private MaterialButton smallButton(String text) {
        MaterialButton b = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(C_BLUE);
        b.setInsetTop(0);
        b.setInsetBottom(0);
        return b;
    }

    // ------------------------------------------------------------------ data & halaman

    /** Filter jenis: 0 semua · 1 komplain · 2 masalah (void menunggu, tertunda, data pelanggan bermasalah). */
    private int kindFilter = 0;

    private static boolean isComplaintOrder(Transaction t) {
        return t.getComplainedAt() != null && !t.getComplainedAt().trim().isEmpty();
    }

    /** Aktif: komplain yang belum ditangani, ATAU masalah terbuka (selama kondisinya masih ada). */
    static boolean isActive(Transaction t) {
        if (t.getProblemNote() != null && !t.getProblemNote().isEmpty()) return true;
        return isComplaintOrder(t) && TransactionDao.isComplaintActive(t);
    }

    static String activityAt(Transaction t) {
        if (isComplaintOrder(t)) return TransactionDao.complaintActivityAt(t);
        String at = t.getDeliveryTertundaAt();
        return at != null && !at.trim().isEmpty() ? at : t.getDeliveryQueuedAt();
    }

    private List<Transaction> localSource() {
        List<Transaction> out = new ArrayList<>();
        java.util.Map<Long, Transaction> byId = new java.util.HashMap<>();
        if (kindFilter != 2) {
            for (Transaction t : dao.getComplaintOrders(600)) {
                out.add(t);
                byId.put(t.getId(), t);
            }
        }
        if (kindFilter != 1) {
            for (Transaction p : dao.getProblemOrders(600)) {
                Transaction same = byId.get(p.getId());
                if (same != null) same.setProblemNote(p.getProblemNote());   // satu kartu: komplain + masalahnya
                else out.add(p);
            }
        } else {
            // Filter "Komplain": catatan masalah tak ikut ditampilkan sebagai aktif.
            for (Transaction t : out) t.setProblemNote(null);
        }
        return out;
    }

    private void load() {
        byDay.clear();
        Map<String, List<Transaction>> tmp = new LinkedHashMap<>();
        loadProducts();
        List<Transaction> source = remoteMode ? remoteAll : localSource();
        for (Transaction t : source) {
            if (!query.isEmpty() && !matches(t)) continue;
            String local = Ts.local(activityAt(t));
            String day = local.length() >= 10 ? local.substring(0, 10) : "—";
            List<Transaction> l = tmp.get(day);
            if (l == null) tmp.put(day, l = new ArrayList<>());
            l.add(t);
        }
        days = new ArrayList<>(tmp.keySet());
        Collections.sort(days, Collections.reverseOrder());   // hari terbaru dulu
        for (String d : days) {
            List<Transaction> l = tmp.get(d);
            // Aktif dulu, lalu yang lebih baru.
            Collections.sort(l, (a, b) -> {
                boolean aa = isActive(a);
                boolean bb = isActive(b);
                if (aa != bb) return aa ? -1 : 1;
                return Ts.local(activityAt(b)).compareTo(Ts.local(activityAt(a)));
            });
            byDay.put(d, l);
        }
        if (currentDay == null || !byDay.containsKey(currentDay)) currentDay = days.isEmpty() ? null : days.get(0);
    }

    private void step(int delta) {
        if (currentDay == null) return;
        int i = days.indexOf(currentDay) + delta;
        if (i < 0 || i >= days.size()) return;
        currentDay = days.get(i);
        render();
    }

    private static String dayText(String day) {
        try {
            SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            Date d = in.parse(day);
            if (d == null) return day;
            String today = in.format(new Date());
            String t = new SimpleDateFormat("EEEE, d MMM yyyy", new Locale("id", "ID")).format(d);
            return day.equals(today) ? "Hari ini · " + t : t;
        } catch (Exception e) {
            return day;
        }
    }

    private void render() {
        box.removeAllViews();
        int totalActive = 0;
        int totalAll = 0;
        for (List<Transaction> l : byDay.values()) {
            for (Transaction t : l) {
                totalAll++;
                if (isActive(t)) totalActive++;
            }
        }
        summary.setText((remoteMode ? "🌐 Semua perangkat · " : "") + totalActive + " aktif · " + (totalAll - totalActive)
                + " selesai (semua hari)" + (query.isEmpty() ? "" : " · hasil pencarian"));
        summary.setTextColor(totalActive > 0 ? C_RED : C_GREEN);

        if (!query.isEmpty()) {
            // Pencarian: semua hari sekaligus (tanpa pager), dikelompokkan per tanggal.
            dayLabel.setText("Hasil pencarian: " + totalAll);
            btnOlder.setEnabled(false);
            btnNewer.setEnabled(false);
            otherDays.setVisibility(View.GONE);
            if (totalAll == 0) {
                TextView none = new TextView(this);
                none.setText(remoteLoading ? "Memuat…" : "Tidak ada komplain yang cocok.");
                none.setTextColor(C_MUTED);
                none.setGravity(Gravity.CENTER);
                none.setPadding(dp(24), dp(40), dp(24), dp(24));
                box.addView(none);
                return;
            }
            for (String d : days) {
                box.addView(header("📅 " + dayText(d)));
                for (Transaction t : byDay.get(d)) box.addView(card(t, isActive(t)));
            }
            return;
        }

        if (currentDay == null) {
            dayLabel.setText("—");
            btnOlder.setEnabled(false);
            btnNewer.setEnabled(false);
            otherDays.setVisibility(View.GONE);
            TextView empty = new TextView(this);
            empty.setText(remoteLoading ? "Memuat komplain semua perangkat…"
                    : (remoteMode ? "Belum ada komplain pesanan di cabang ini." : "Belum ada komplain pesanan di perangkat ini."));
            empty.setTextColor(C_MUTED);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(24), dp(48), dp(24), dp(24));
            box.addView(empty);
            return;
        }

        List<Transaction> list = byDay.get(currentDay);
        int dayActive = 0;
        for (Transaction t : list) if (isActive(t)) dayActive++;
        int idx = days.indexOf(currentDay);
        dayLabel.setText(dayText(currentDay) + "\n" + list.size() + " komplain" + (dayActive > 0 ? " · " + dayActive + " aktif" : ""));
        btnOlder.setEnabled(idx < days.size() - 1);
        btnNewer.setEnabled(idx > 0);

        // Komplain aktif di hari LAIN: jangan sampai terlewat — ketuk untuk melompat ke harinya.
        String jump = null;
        int otherActive = 0;
        for (String d : days) {
            if (d.equals(currentDay)) continue;
            int a = 0;
            for (Transaction t : byDay.get(d)) if (isActive(t)) a++;
            if (a > 0) {
                otherActive += a;
                if (jump == null) jump = d;   // hari terbaru yang punya komplain aktif
            }
        }
        if (jump != null) {
            final String target = jump;
            otherDays.setText("⏳ " + otherActive + " komplain aktif di hari lain — ketuk untuk melihat");
            otherDays.setVisibility(View.VISIBLE);
            otherDays.setOnClickListener(v -> {
                currentDay = target;
                render();
            });
        } else {
            otherDays.setVisibility(View.GONE);
        }

        boolean headedActive = false;
        boolean headedResolved = false;
        for (Transaction t : list) {
            boolean act = isActive(t);
            if (act && !headedActive) {
                box.addView(header("⏳ Aktif — perlu ditangani"));
                headedActive = true;
            }
            if (!act && !headedResolved) {
                box.addView(header("✅ Selesai"));
                headedResolved = true;
            }
            box.addView(card(t, act));
        }
    }

    // ------------------------------------------------------------------ admin: semua perangkat + pencarian

    private void styleScope() {
        if (btnLocal == null) return;
        btnLocal.setAlpha(remoteMode ? 0.55f : 1f);
        btnRemote.setAlpha(remoteMode ? 1f : 0.55f);
        btnLocal.setTypeface(null, remoteMode ? Typeface.NORMAL : Typeface.BOLD);
        btnRemote.setTypeface(null, remoteMode ? Typeface.BOLD : Typeface.NORMAL);
    }

    private void setRemote(boolean on) {
        if (remoteMode == on) return;
        remoteMode = on;
        currentDay = null;
        styleScope();
        load();
        render();
        if (on) loadRemote();
    }

    /** Cocok dengan kata kunci: nama, struk, telepon, perangkat, atau slug/nama produk. */
    private boolean matches(Transaction t) {
        StringBuilder sb = new StringBuilder();
        sb.append(t.getCustomerName()).append(' ').append(t.getCustomerPhone()).append(' ').append(t.getReceiptNo());
        String[] rm = remoteMeta.get(t);
        if (rm != null && rm[2] != null) sb.append(' ').append(rm[2]);
        if (t.getItems() != null) {
            for (TransactionItem it : t.getItems()) {
                if (it == null) continue;
                sb.append(' ').append(it.productName);
                com.crowja.damiupos.model.Product p = productsById.get(it.productId);
                if (p != null && p.getSlug() != null) sb.append(' ').append(p.getSlug());
            }
        }
        return sb.toString().toLowerCase(Locale.ROOT).contains(query);
    }

    /** Muat komplain SEMUA perangkat cabang dari server (khusus Admin). */
    private void loadRemote() {
        if (remoteLoading) return;
        final com.crowja.damiupos.sync.SyncSettings cfg = new com.crowja.damiupos.sync.SyncSettings(new SettingsDao(DatabaseHelper.getInstance(this)));
        if (!cfg.isEnrolled()) {
            Toast.makeText(this, "Perangkat belum terhubung ke server.", Toast.LENGTH_SHORT).show();
            return;
        }
        remoteLoading = true;
        final String staffUuid = com.crowja.damiupos.sync.LocationReporter.currentStaffUuid(this);
        new Thread(() -> {
            org.json.JSONArray arr = null;
            String err = null;
            try {
                org.json.JSONObject res = new com.crowja.damiupos.sync.SyncApi(cfg).adminComplaints(staffUuid);
                arr = res.optJSONArray("complaints");
            } catch (com.crowja.damiupos.sync.SyncApi.SyncException se) {
                String m = se.serverMessage();
                err = m != null && !m.isEmpty() ? m : "Gagal memuat komplain (kode " + se.code + ").";
            } catch (Exception e) {
                err = "Gagal memuat komplain — periksa koneksi internet.";
            }
            final org.json.JSONArray fArr = arr;
            final String fErr = err;
            runOnUiThread(() -> {
                remoteLoading = false;
                if (isFinishing() || isDestroyed()) return;
                if (fErr != null) {
                    Toast.makeText(this, fErr, Toast.LENGTH_LONG).show();
                } else {
                    parseRemote(fArr);
                }
                load();
                render();
                fetchOrderedToday();
            });
        }).start();
    }

    private void parseRemote(org.json.JSONArray arr) {
        remoteAll.clear();
        remoteMeta.clear();
        for (int i = 0; arr != null && i < arr.length(); i++) {
            org.json.JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            Transaction t = new Transaction();
            t.setId(-1L);
            t.setCustomerName(o.optString("customer_name", "Pelanggan"));
            t.setCustomerPhone(nz(o.optString("customer_phone", "")));
            t.setCustomerAddress(nz(o.optString("customer_address", "")));
            t.setReceiptNo(nz(o.optString("receipt_no", "")));
            t.setTotalHarga(o.optDouble("total", 0d));
            t.setJumlahGalon(o.optInt("galon", 0));
            t.setPaymentMethod(nz(o.optString("payment_method", "")));
            t.setDeliveryStatus(nz(o.optString("delivery_status", "")));
            t.setDeliveryDoneAt(nz(o.optString("delivery_done_at", "")));
            t.setTanggal(nz(o.optString("tanggal", "")));
            t.setComplainedAt(nz(o.optString("complained_at", "")));
            t.setComplaintLastInboundAt(nz(o.optString("complaint_last_inbound_at", "")));
            t.setComplaintResolvedAt(nz(o.optString("complaint_resolved_at", "")));
            t.setDeliveryDestName(nz(o.optString("dest_name", "")));
            t.setCatatan(nz(o.optString("note", "")));
            List<TransactionItem> items = new ArrayList<>();
            org.json.JSONArray ia = o.optJSONArray("items");
            for (int k = 0; ia != null && k < ia.length(); k++) {
                org.json.JSONObject it = ia.optJSONObject(k);
                if (it == null) continue;
                String slug = it.optString("slug", "");
                items.add(new TransactionItem(0L, slug, (int) Math.round(it.optDouble("qty", 0)), 0d));
                String color = it.optString("color", "");
                if (!slug.isEmpty() && !color.isEmpty() && !"null".equals(color)) {
                    try {
                        slugColors.put(slug.toUpperCase(Locale.ROOT), android.graphics.Color.parseColor(color));
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
            t.setItems(items);
            remoteMeta.put(t, new String[]{o.optString("uuid", ""), nz(o.optString("customer_uuid", "")), nz(o.optString("device_name", ""))});
            remoteAll.add(t);
        }
    }

    private static String nz(String s) {
        return s == null || "null".equals(s) ? "" : s;
    }

    /** Nama pelanggan diketuk → detail pelanggan (kalau pelanggannya belum ada di HP ini, diberi tahu). */
    private void openCustomer(Transaction t) {
        IssueReminder.markSeen(this, t);
        long id = t.getCustomerId();
        String[] rm = remoteMeta.get(t);
        if (rm != null) {
            com.crowja.damiupos.model.Customer c = rm[1].isEmpty() ? null
                    : new com.crowja.damiupos.db.CustomerDao(DatabaseHelper.getInstance(this)).getBySyncUuid(rm[1]);
            id = c != null ? c.getId() : -1L;
        }
        if (id <= 0L) {
            Toast.makeText(this, "Pelanggan ini belum ada di HP ini.", Toast.LENGTH_SHORT).show();
            return;
        }
        startActivity(new Intent(this, CustomerDetailActivity.class).putExtra("customer_id", id));
    }

    /** Detail ringkas order dari perangkat lain (tak ada di antrean HP ini). */
    private void showRemoteDetail(Transaction t) {
        StringBuilder sb = new StringBuilder();
        sb.append("👤 ").append(t.getCustomerName()).append('\n');
        if (t.getCustomerPhone() != null && !t.getCustomerPhone().isEmpty()) sb.append("📞 ").append(t.getCustomerPhone()).append('\n');
        if (t.getCustomerAddress() != null && !t.getCustomerAddress().isEmpty()) sb.append("📍 ").append(t.getCustomerAddress()).append('\n');
        if (t.getDeliveryDestName() != null && !t.getDeliveryDestName().isEmpty()) sb.append("🚩 Kirim ke: ").append(t.getDeliveryDestName()).append('\n');
        String[] rm = remoteMeta.get(t);
        if (rm != null && !rm[2].isEmpty()) sb.append("📱 Perangkat: ").append(rm[2]).append('\n');
        if (t.getReceiptNo() != null && !t.getReceiptNo().isEmpty()) sb.append("🧾 ").append(t.getReceiptNo()).append('\n');
        sb.append("\nPesanan:\n").append(itemsText(t)).append('\n');
        sb.append("Total: Rp ").append(nf.format(Math.round(t.getTotalHarga()))).append('\n');
        if (t.getPaymentMethod() != null && !t.getPaymentMethod().isEmpty()) sb.append("Pembayaran: ").append(t.getPaymentMethod()).append('\n');
        sb.append("\nStatus: ").append(t.getDeliveryStatus() == null || t.getDeliveryStatus().isEmpty() ? "-" : t.getDeliveryStatus()).append('\n');
        String done = Ts.local(t.getDeliveryDoneAt());
        if (!done.isEmpty()) sb.append("Selesai: ").append(done).append('\n');
        sb.append("Komplain: ").append(Ts.local(t.getComplainedAt())).append('\n');
        String last = Ts.local(t.getComplaintLastInboundAt());
        if (!last.isEmpty()) sb.append("Pesan terakhir pelanggan: ").append(last).append('\n');
        if (t.getCatatan() != null && !t.getCatatan().trim().isEmpty()) sb.append("\nCatatan:\n").append(t.getCatatan().trim());
        new AlertDialog.Builder(this).setTitle("Detail Order — " + t.getCustomerName()).setMessage(sb.toString())
                .setPositiveButton("Tutup", null)
                .setNeutralButton("💬 Log Komplain", (d, w) -> openComplaintChat(t))
                .show();
    }

    private TextView header(String s) {
        TextView h = new TextView(this);
        h.setText(s);
        h.setTextColor(C_TEXT);
        h.setTextSize(13f);
        h.setTypeface(null, Typeface.BOLD);
        h.setPadding(dp(4), dp(12), dp(4), dp(4));
        return h;
    }

    // ------------------------------------------------------------------ kartu

    private final Map<Long, com.crowja.damiupos.model.Product> productsById = new java.util.HashMap<>();
    private final Map<String, com.crowja.damiupos.model.Product> productsByName = new java.util.HashMap<>();

    private void loadProducts() {
        productsById.clear();
        productsByName.clear();
        for (com.crowja.damiupos.model.Product p : new com.crowja.damiupos.db.ProductDao(DatabaseHelper.getInstance(this)).getAll()) {
            productsById.put(p.getId(), p);
            if (p.getName() != null) productsByName.put(p.getName().trim().toLowerCase(Locale.ROOT), p);
        }
    }

    /** Baris badge slug produk berwarna ("MIN ×2") — sama dengan kartu transaksi. */
    private View slugBadges(Transaction t) {
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(6), 0, dp(2));
        List<TransactionItem> items = t.getItems();
        if (items == null || items.isEmpty()) {
            items = new ArrayList<>();
            items.add(new TransactionItem(0L, null, t.getJumlahGalon(), 0d));
        }
        int[] palette = {0xFF1976D2, 0xFF388E3C, 0xFFE64A19, 0xFF7B1FA2, 0xFF00838F, 0xFFC2185B, 0xFFF9A825, 0xFF5D4037};
        for (TransactionItem it : items) {
            if (it == null) continue;
            com.crowja.damiupos.model.Product p = productsById.get(it.productId);
            if (p == null && it.productName != null) p = productsByName.get(it.productName.trim().toLowerCase(Locale.ROOT));
            String label = p != null && p.getSlug() != null && !p.getSlug().trim().isEmpty() ? p.getSlug().trim()
                    : (it.productName != null && !it.productName.isEmpty() ? it.productName : "Galon");
            int bg = palette[((it.productName == null ? "" : it.productName).hashCode() & 0x7fffffff) % palette.length];
            try {
                if (p != null && p.getColor() != null && !p.getColor().isEmpty()) bg = android.graphics.Color.parseColor(p.getColor());
            } catch (IllegalArgumentException ignored) {
            }
            Integer remoteColor = slugColors.get(label.toUpperCase(Locale.ROOT));
            if (p == null && remoteColor != null) bg = remoteColor;
            double lum = (0.299 * android.graphics.Color.red(bg) + 0.587 * android.graphics.Color.green(bg)
                    + 0.114 * android.graphics.Color.blue(bg)) / 255.0;
            TextView chip = new TextView(this);
            chip.setText(label + " ×" + it.jumlah);
            chip.setTextSize(12f);
            chip.setTypeface(null, Typeface.BOLD);
            chip.setTextColor(lum > 0.65 ? 0xFF1A1A1A : 0xFFFFFFFF);
            chip.setPadding(dp(8), dp(3), dp(8), dp(3));
            android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
            gd.setCornerRadius(dp(10));
            gd.setColor(bg);
            chip.setBackground(gd);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMarginEnd(dp(6));
            row.addView(chip, lp);
        }
        hs.addView(row);
        return hs;
    }

    private String itemsText(Transaction t) {
        StringBuilder items = new StringBuilder();
        if (t.getItems() != null) {
            for (TransactionItem it : t.getItems()) {
                if (it == null) continue;
                if (items.length() > 0) items.append(", ");
                items.append(it.productName != null ? it.productName : "Produk").append(" ×").append(it.jumlah);
            }
        }
        if (items.length() == 0) items.append(t.getJumlahGalon()).append(" galon");
        return items.toString();
    }

    private View card(final Transaction t, final boolean active) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(14), dp(12), dp(14), dp(10));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(active ? 0xFFFFF3F3 : 0xFFF1F8E9);   // aktif: merah muda · selesai: hijau muda
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(active ? 2 : 1), active ? C_RED : 0xFF81C784);
        c.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(4), 0, dp(4));
        c.setLayoutParams(lp);

        TextView name = new TextView(this);
        final boolean unseen = !remoteMode && IssueReminder.isUnseen(this, t);
        name.setText((unseen ? "🆕 " : "") + (isComplaintOrder(t) ? "😠 " : "⚠️ ") + (t.getCustomerName() != null && !t.getCustomerName().isEmpty() ? t.getCustomerName() : "Pelanggan"));
        name.setTextColor(C_BLUE);
        name.setTextSize(16f);
        name.setTypeface(null, Typeface.BOLD);
        name.setPaintFlags(name.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
        name.setOnClickListener(v -> openCustomer(t));   // → detail pelanggan
        // Komplain SELESAI diringkas (nama + badge produk); aktif selalu penuh — komplain yang terpicu lagi otomatis membesar.
        final String key = uuidOf(t) == null ? "" : uuidOf(t);
        final boolean collapsed = !active && !expanded.contains(key);
        LinearLayout nameRow = new LinearLayout(this);
        nameRow.setOrientation(LinearLayout.HORIZONTAL);
        nameRow.setGravity(Gravity.CENTER_VERTICAL);
        nameRow.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
        if (!active) {
            TextView chev = new TextView(this);
            chev.setText(collapsed ? "▾" : "▴");
            chev.setTextSize(20f);
            chev.setTextColor(C_GREEN);
            chev.setPadding(dp(12), 0, dp(4), 0);
            chev.setOnClickListener(v -> toggleExpand(key));
            nameRow.addView(chev);
        }
        c.addView(nameRow);
        final String[] rm = remoteMeta.get(t);
        if (!collapsed && rm != null && rm[2] != null && !rm[2].isEmpty()) {
            TextView dev = new TextView(this);
            dev.setText("📱 " + rm[2]);
            dev.setTextColor(C_MUTED);
            dev.setTextSize(12f);
            c.addView(dev);
        }

        c.addView(slugBadges(t));
        if (collapsed) {
            c.setOnClickListener(v -> toggleExpand(key));   // ketuk kartu ringkas untuk membuka
            return c;
        }
        String time = Ts.hm(activityAt(t));
        TextView meta = new TextView(this);
        meta.setText("Rp " + nf.format(Math.round(t.getTotalHarga()))
                + (t.getReceiptNo() != null && !t.getReceiptNo().isEmpty() ? " · 🧾 " + t.getReceiptNo() : "")
                + (time.isEmpty() ? "" : "\n🕒 " + (isComplaintOrder(t) ? "Komplain terakhir" : "Sejak") + " jam " + time));
        meta.setTextColor(C_MUTED);
        meta.setTextSize(13f);
        meta.setPadding(0, dp(4), 0, dp(4));
        c.addView(meta);

        final boolean pendingOrder = Transaction.DELIVERY_PENDING.equals(t.getDeliveryStatus())
                || Transaction.DELIVERY_TERTUNDA.equals(t.getDeliveryStatus());
        if (orderedKnown) {
            boolean ordered = orderedToday.contains(uuidOf(t));
            TextView oi = new TextView(this);
            oi.setText(ordered ? "🛒 Sudah order hari ini" : "⚪ Belum order hari ini");
            oi.setTextColor(ordered ? C_GREEN : C_MUTED);
            oi.setTextSize(13f);
            oi.setTypeface(null, Typeface.BOLD);
            oi.setPadding(0, 0, 0, dp(2));
            c.addView(oi);
        }
        if (t.getProblemNote() != null && !t.getProblemNote().isEmpty()) {
            TextView pn = new TextView(this);
            pn.setText(t.getProblemNote());
            pn.setTextColor(0xFFE65100);
            pn.setTextSize(13f);
            pn.setTypeface(null, Typeface.BOLD);
            pn.setPadding(0, dp(2), 0, dp(4));
            c.addView(pn);
        }
        if (active && isComplaintOrder(t) && TransactionDao.isComplaintActive(t)) {
            TextView status = new TextView(this);
            status.setText(pendingOrder ? "⏳ Aktif — selesaikan pesanan ini untuk menutup komplain"
                    : "🔁 Aktif — pelanggan komplain setelah pesanan selesai");
            status.setTextColor(C_RED);
            status.setTextSize(13f);
            status.setTypeface(null, Typeface.BOLD);
            c.addView(status);
        }

        // Aksi: 2 × 2 tombol.
        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setPadding(0, dp(8), 0, 0);
        row1.addView(actionButton("🔍 Detail", true, v -> openOrderDetail(t)), weight());
        row1.addView(actionButton("💬 Chat", true, v -> chooseChat(t)), weight());
        c.addView(row1);
        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        boolean pending = Transaction.DELIVERY_PENDING.equals(t.getDeliveryStatus());
        if (rm == null) {   // rit & sejalur hanya untuk order di antrean perangkat ini
            row2.addView(actionButton("➕ Tambah ke Rit", pending, v -> addToRit(t)), weight());
            row2.addView(actionButton("🛣️ Kirim Sejalur", pending, v -> sejalur(t)), weight());
            c.addView(row2);
        }
        if (active && isComplaintOrder(t) && TransactionDao.isComplaintActive(t)) {
            LinearLayout row3 = new LinearLayout(this);
            row3.setOrientation(LinearLayout.HORIZONTAL);
            row3.addView(actionButton("✅ Tandai Ditangani", true, v -> resolve(t)), weight());
            c.addView(row3);
        }

        c.setOnClickListener(v -> openComplaintChat(t));
        return c;
    }

    private void toggleExpand(String key) {
        if (!expanded.remove(key)) expanded.add(key);
        render();
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1f);
        p.setMargins(dp(2), 0, dp(2), 0);
        return p;
    }

    private MaterialButton actionButton(String text, boolean enabled, View.OnClickListener l) {
        MaterialButton b = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(12f);
        b.setTextColor(C_BLUE);
        b.setInsetTop(0);
        b.setInsetBottom(0);
        b.setMaxLines(1);
        b.setEnabled(enabled);
        b.setAlpha(enabled ? 1f : 0.45f);
        b.setOnClickListener(l);
        return b;
    }

    // ------------------------------------------------------------------ aksi

    /** Tanya server pelanggan mana yang sudah order lagi hari ini (lintas perangkat); gagal = indikator tak tampil. */
    private void fetchOrderedToday() {
        if (orderedLoading) return;
        final com.crowja.damiupos.sync.SyncSettings cfg = new com.crowja.damiupos.sync.SyncSettings(new SettingsDao(DatabaseHelper.getInstance(this)));
        if (!cfg.isEnrolled()) return;
        final org.json.JSONArray rows = new org.json.JSONArray();
        com.crowja.damiupos.db.CustomerDao cd = new com.crowja.damiupos.db.CustomerDao(DatabaseHelper.getInstance(this));
        List<Transaction> source = new ArrayList<>();
        for (List<Transaction> l : byDay.values()) source.addAll(l);
        for (Transaction t : source) {
            String[] rm = remoteMeta.get(t);
            String tu = uuidOf(t);
            String cu = rm != null ? rm[1] : (t.getCustomerId() > 0 ? cd.getSyncUuidById(t.getCustomerId()) : null);
            if (tu == null || tu.isEmpty() || rows.length() >= 600) continue;
            try {
                rows.put(new org.json.JSONObject().put("t", tu).put("c", cu == null ? "" : cu));
            } catch (Exception ignored) {
            }
        }
        if (rows.length() == 0) return;
        orderedLoading = true;
        new Thread(() -> {
            java.util.Set<String> got = null;
            try {
                org.json.JSONObject res = new com.crowja.damiupos.sync.SyncApi(cfg).complaintsOrderedToday(rows);
                org.json.JSONArray a = res.optJSONArray("ordered");
                got = new java.util.HashSet<>();
                for (int i = 0; a != null && i < a.length(); i++) got.add(a.optString(i));
            } catch (Exception ignored) {
            }
            final java.util.Set<String> fGot = got;
            runOnUiThread(() -> {
                orderedLoading = false;
                if (isFinishing() || isDestroyed() || fGot == null) return;
                orderedToday.clear();
                orderedToday.addAll(fGot);
                orderedKnown = true;
                render();
            });
        }).start();
    }

    // ------------------------------------------------------------------ admin: bersihkan komplain

    private void chooseClear() {
        final String[] opts = {"Hari ini", "Pilih tanggal…", "Rentang tanggal…", "Semua waktu"};
        new AlertDialog.Builder(this).setTitle("🧹 Bersihkan komplain aktif")
                .setItems(opts, (d, which) -> {
                    SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
                    if (which == 0) {
                        String today = f.format(new Date());
                        confirmClear("range", today, today, "hari ini");
                    } else if (which == 1) {
                        pickDate("Pilih tanggal", ymd -> confirmClear("range", ymd, ymd, ymd));
                    } else if (which == 2) {
                        pickDate("Dari tanggal", from -> pickDate("Sampai tanggal", to -> {
                            if (to.compareTo(from) < 0) {
                                Toast.makeText(this, "Tanggal akhir sebelum tanggal awal.", Toast.LENGTH_SHORT).show();
                                return;
                            }
                            confirmClear("range", from, to, from + " s/d " + to);
                        }));
                    } else {
                        confirmClear("all", null, null, "SEMUA waktu");
                    }
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    private interface DatePicked { void on(String ymd); }

    private void pickDate(String title, final DatePicked cb) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        android.app.DatePickerDialog dlg = new android.app.DatePickerDialog(this, (view, y, m, d) ->
                cb.on(String.format(Locale.US, "%04d-%02d-%02d", y, m + 1, d)),
                c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH), c.get(java.util.Calendar.DAY_OF_MONTH));
        dlg.setTitle(title);
        dlg.show();
    }

    /** Dua langkah: hitung dulu (pratinjau), lalu minta konfirmasi sebelum membersihkan. */
    private void confirmClear(final String scope, final String from, final String to, final String label) {
        final com.crowja.damiupos.sync.SyncSettings cfg = new com.crowja.damiupos.sync.SyncSettings(new SettingsDao(DatabaseHelper.getInstance(this)));
        if (!cfg.isEnrolled()) {
            Toast.makeText(this, "Perangkat belum terhubung ke server.", Toast.LENGTH_SHORT).show();
            return;
        }
        final String staffUuid = com.crowja.damiupos.sync.LocationReporter.currentStaffUuid(this);
        callClear(cfg, scope, from, to, true, staffUuid, count -> {
            if (count <= 0) {
                Toast.makeText(this, "Tidak ada komplain aktif untuk " + label + ".", Toast.LENGTH_LONG).show();
                return;
            }
            new AlertDialog.Builder(this)
                    .setTitle("Bersihkan " + count + " komplain?")
                    .setMessage(count + " komplain aktif (" + label + ") ditandai ditangani di SEMUA perangkat. "
                            + "Komplain tidak dihapus dan aktif lagi bila pelanggan menulis lagi.")
                    .setPositiveButton("Ya, bersihkan", (d, w) -> callClear(cfg, scope, from, to, false, staffUuid, n -> {
                        Toast.makeText(this, "🧹 " + n + " komplain dibersihkan.", Toast.LENGTH_LONG).show();
                        com.crowja.damiupos.sync.SyncScheduler.syncNow(getApplicationContext());
                        if (remoteMode) loadRemote();
                        load();
                        render();
                    }))
                    .setNegativeButton("Batal", null)
                    .show();
        });
    }

    private interface CountCb { void on(int count); }

    private void callClear(final com.crowja.damiupos.sync.SyncSettings cfg, final String scope, final String from, final String to,
                           final boolean preview, final String staffUuid, final CountCb cb) {
        new Thread(() -> {
            int count = -1;
            String err = null;
            try {
                count = new com.crowja.damiupos.sync.SyncApi(cfg).adminComplaintsClear(scope, from, to, preview, staffUuid).optInt("count", 0);
            } catch (com.crowja.damiupos.sync.SyncApi.SyncException se) {
                String m = se.serverMessage();
                err = m != null && !m.isEmpty() ? m : "Gagal (kode " + se.code + ").";
            } catch (Exception e) {
                err = "Gagal — periksa koneksi internet.";
            }
            final int fc = count;
            final String fe = err;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (fe != null) Toast.makeText(this, fe, Toast.LENGTH_LONG).show();
                else cb.on(fc);
            });
        }).start();
    }

    private String uuidOf(Transaction t) {
        String[] rm = remoteMeta.get(t);
        return rm != null ? rm[0] : dao.getSyncUuidById(t.getId());
    }

    /** ✅ Komplain pada pesanan yang sudah selesai ditandai ditangani; aktif lagi bila pelanggan menulis lagi. */
    private void resolve(final Transaction t) {
        final String uuid = uuidOf(t);
        if (uuid == null || uuid.isEmpty()) {
            Toast.makeText(this, "Transaksi ini belum tersinkron ke server", Toast.LENGTH_SHORT).show();
            return;
        }
        final com.crowja.damiupos.sync.SyncSettings cfg = new com.crowja.damiupos.sync.SyncSettings(new SettingsDao(DatabaseHelper.getInstance(this)));
        if (!cfg.isEnrolled()) {
            Toast.makeText(this, "Perangkat belum terhubung ke server.", Toast.LENGTH_SHORT).show();
            return;
        }
        final String staffUuid = com.crowja.damiupos.sync.LocationReporter.currentStaffUuid(this);
        new Thread(() -> {
            String err = null;
            try {
                new com.crowja.damiupos.sync.SyncApi(cfg).complaintResolve(uuid, staffUuid);
            } catch (com.crowja.damiupos.sync.SyncApi.SyncException se) {
                String m = se.serverMessage();
                err = m != null && !m.isEmpty() ? m : "Gagal menandai (kode " + se.code + ").";
            } catch (Exception e) {
                err = "Gagal menandai — periksa koneksi internet.";
            }
            final String fErr = err;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (fErr != null) {
                    Toast.makeText(this, fErr, Toast.LENGTH_LONG).show();
                    return;
                }
                SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
                iso.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
                if (remoteMeta.get(t) == null) dao.setComplaintResolvedLocal(t.getId(), iso.format(new Date()));
                Toast.makeText(this, "✅ Komplain ditandai ditangani", Toast.LENGTH_SHORT).show();
                if (remoteMeta.get(t) != null) {
                    t.setComplaintResolvedAt(iso.format(new Date()));   // cermin langsung; server tetap sumber kebenaran
                    loadRemote();
                }
                load();
                render();
            });
        }).start();
    }

    private void openComplaintChat(Transaction t) {
        IssueReminder.markSeen(this, t);
        String uuid = uuidOf(t);
        if (uuid == null || uuid.isEmpty()) {
            Toast.makeText(this, "Transaksi ini belum tersinkron ke server", Toast.LENGTH_SHORT).show();
            return;
        }
        startActivity(new Intent(this, ChatLogActivity.class)
                .putExtra(ChatLogActivity.EXTRA_MODE, ChatLogActivity.MODE_ORDER)   // area kosong kartu → Chat Pesanan
                .putExtra(ChatLogActivity.EXTRA_TRANSACTION_UUID, uuid)
                .putExtra(ChatLogActivity.EXTRA_CUSTOMER_NAME, t.getCustomerName() != null ? t.getCustomerName() : ""));
    }

    /** 💬 Chat: pilih Chat Pesanan (chat WA order lewat server, bisa dibalas) atau Chat WA (aplikasi WhatsApp). */
    private void chooseChat(final Transaction t) {
        IssueReminder.markSeen(this, t);
        // Baris pilihan: "Chat WA" memakai ikon resmi WhatsApp.
        final String[] labels = {"Chat Pesanan", "Chat WA"};
        android.widget.ArrayAdapter<String> ad = new android.widget.ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, labels) {
            @Override
            public View getView(int position, View convertView, android.view.ViewGroup parent) {
                TextView tv = (TextView) super.getView(position, convertView, parent);
                tv.setTextSize(16f);
                tv.setCompoundDrawablePadding(dp(12));
                if (position == 1) {
                    android.graphics.drawable.Drawable ic = androidx.core.content.ContextCompat.getDrawable(ComplaintListActivity.this, R.drawable.ic_whatsapp);
                    if (ic != null) {
                        ic = ic.mutate();
                        ic.setBounds(0, 0, dp(26), dp(26));
                        tv.setCompoundDrawables(ic, null, null, null);
                    }
                    tv.setText("Chat WA");
                } else {
                    tv.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0);
                    tv.setText("💬 Chat Pesanan");
                }
                return tv;
            }
        };
        new AlertDialog.Builder(this)
                .setTitle("💬 Chat — " + (t.getCustomerName() != null ? t.getCustomerName() : "Pelanggan"))
                .setAdapter(ad, (d, which) -> {
                    if (which == 0) {
                        String uuid = uuidOf(t);
                        if (uuid == null || uuid.isEmpty()) {
                            Toast.makeText(this, "Transaksi ini belum tersinkron ke server", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        startActivity(new Intent(this, ChatLogActivity.class)
                                .putExtra(ChatLogActivity.EXTRA_MODE, ChatLogActivity.MODE_ORDER)
                                .putExtra(ChatLogActivity.EXTRA_TRANSACTION_UUID, uuid)
                                .putExtra(ChatLogActivity.EXTRA_CUSTOMER_NAME, t.getCustomerName() != null ? t.getCustomerName() : ""));
                    } else {
                        DeliveryQueueActivity.openWhatsApp(this, t.getCustomerPhone(), "");
                    }
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    /** Detail Order yang SAMA dengan Antrian Delivery (dialog lengkap: Ubah, Alihkan, Chat Pesanan, Lihat Chat Komplain, …). */
    private void openOrderDetail(Transaction t) {
        IssueReminder.markSeen(this, t);
        if (remoteMeta.get(t) != null) {
            showRemoteDetail(t);   // order perangkat lain: tak ada di antrean HP ini
            return;
        }
        startActivity(new Intent(this, DeliveryQueueActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(DeliveryQueueActivity.EXTRA_FOCUS_TRX_ID, t.getId()));
    }

    /** ➕ Titipkan order ini ke rit yang sedang berjalan (diserap layar Antrian Delivery begitu terbuka). */
    private void addToRit(Transaction t) {
        SettingsDao settings = new SettingsDao(DatabaseHelper.getInstance(this));
        if (settings.getDeliveryRunningTrxIds().isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("Belum ada rit berjalan")
                    .setMessage("Mulai rit dulu dari Antrian Saya (▶), lalu tambahkan komplain ini ke rit. "
                            + "Atau pakai 🛣️ Kirim Sejalur untuk menyusun rit baru.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }
        String uuid = dao.getSyncUuidById(t.getId());
        if (uuid == null || uuid.isEmpty()) {
            Toast.makeText(this, "Order ini belum tersinkron ke server — coba lagi sebentar.", Toast.LENGTH_SHORT).show();
            return;
        }
        settings.addDeliveryRunPendingUuids(Collections.singletonList(uuid));
        Toast.makeText(this, "➕ " + (t.getCustomerName() != null ? t.getCustomerName() : "Order")
                + " ditambahkan ke rit berjalan.", Toast.LENGTH_LONG).show();
        startActivity(new Intent(this, DeliveryQueueActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
    }

    /** 🛣️ Buka Kirim Sejalur dengan order ini sebagai titik terjauh. */
    private void sejalur(Transaction t) {
        String uuid = dao.getSyncUuidById(t.getId());
        if (uuid == null || uuid.isEmpty()) {
            Toast.makeText(this, "Order ini belum tersinkron ke server — coba lagi sebentar.", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent i = new Intent(this, DeliveryQueueActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        i.putExtra(DeliveryQueueActivity.EXTRA_SEJALUR_TARGET, uuid);
        i.putExtra(DeliveryQueueActivity.EXTRA_SEJALUR_NAME, t.getCustomerName() == null ? "" : t.getCustomerName());
        i.putExtra(DeliveryQueueActivity.EXTRA_SEJALUR_GALON, t.getJumlahGalon());
        i.putExtra(DeliveryQueueActivity.EXTRA_SEJALUR_PICKUP, false);
        i.putExtra(DeliveryQueueActivity.EXTRA_SEJALUR_LAT, t.getCustomerLat());
        i.putExtra(DeliveryQueueActivity.EXTRA_SEJALUR_LNG, t.getCustomerLng());
        i.putExtra(DeliveryQueueActivity.EXTRA_SEJALUR_AGE_MIN, SejalurPlanLogic.AGE_UNKNOWN);
        i.putExtra(DeliveryQueueActivity.EXTRA_SEJALUR_SOURCE, SejalurPlanLogic.SRC_MINE);
        i.putExtra(DeliveryQueueActivity.EXTRA_SEJALUR_DEVICE, "");
        startActivity(i);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
