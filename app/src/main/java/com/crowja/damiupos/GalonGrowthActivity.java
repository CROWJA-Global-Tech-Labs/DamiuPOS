package com.crowja.damiupos;

import android.app.DatePickerDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.crowja.damiupos.db.CustomerDao;
import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.SettingsDao;
import com.crowja.damiupos.sync.LocationReporter;
import com.crowja.damiupos.sync.SyncApi;
import com.crowja.damiupos.sync.SyncSettings;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.NumberFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * "Pertumbuhan Galon Beredar" native, cermin halaman web reports/galon_growth. Semua angka dihitung
 * server ({@code /api/admin/galon-growth}) karena HP tidak memegang transaksi se-cabang.
 */
public class GalonGrowthActivity extends AppCompatActivity {

    private static final String[][] PRESETS = {
            {"today", "Hari ini"},
            {"yesterday", "Kemarin"},
            {"week", "Pekan ini"},
            {"week_prev", "Pekan lalu"},
            {"month", "Bulan ini"},
            {"last_month", "Bulan lalu"},
            {"last_3m", "3 Bulan Terakhir"},
            {"all", "Sepanjang waktu"},
    };
    private static final int CHART_MAX_DAYS = 62;

    private static final int BRAND = 0xFF1565C0;
    private static final int SLATE_800 = 0xFF1E293B;
    private static final int SLATE_700 = 0xFF334155;
    private static final int SLATE_500 = 0xFF475569;
    private static final int SLATE_300 = 0xFFCBD5E1;
    private static final int SLATE_50 = 0xFFF8FAFC;
    private static final int EMERALD = 0xFF047857;
    private static final int EMERALD_BAR = 0xFF10B981;
    private static final int EMERALD_BG = 0xFFD1FAE5;
    private static final int BLUE = 0xFF1D4ED8;
    private static final int BLUE_BAR = 0xFF3B82F6;
    private static final int BLUE_BG = 0xFFDBEAFE;

    private final NumberFormat fmt = NumberFormat.getIntegerInstance(new Locale("id", "ID"));
    private final SimpleDateFormat isoDay = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
    private final SimpleDateFormat longDay = new SimpleDateFormat("EEE, dd MMM yyyy", new Locale("id", "ID"));
    private final SimpleDateFormat shortDay = new SimpleDateFormat("dd/MM", Locale.US);

    private SwipeRefreshLayout swipe;
    private final Adapter adapter = new Adapter();
    private final List<Object> rows = new ArrayList<>();
    private LinearLayout header;
    private TextView tvHeading, tvRange;
    private MaterialButton btnMonth, btnStart, btnEnd, btnApply;
    private ChipGroup chips;
    private LinearLayout summary, chartHolder;

    private String preset = "month";
    private String month;
    private String activeStart, activeEnd;
    private String draftStart, draftEnd;
    private boolean useCustom = false;
    private final List<String[]> monthOptions = new ArrayList<>();
    private int loadSeq = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_galon_growth);
        androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        buildHeader();

        RecyclerView rv = findViewById(R.id.rv);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(adapter);
        rv.setItemAnimator(null);   // header satu instance; animasi ganti butuh dua holder sekaligus
        swipe = findViewById(R.id.swipe);
        swipe.setOnRefreshListener(this::load);

        rows.add(header);
        adapter.notifyDataSetChanged();
        load();
    }

    // ------------------------------------------------------------------ header + filter

    private void buildHeader() {
        header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(12), dp(12), dp(12), dp(4));
        header.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        tvHeading = text("📈 Pertumbuhan Galon Beredar", 18, SLATE_800, true);
        header.addView(tvHeading);
        TextView desc = text("", 12, SLATE_500, false);
        SpannableStringBuilder sb = new SpannableStringBuilder("Penambahan galon di tangan konsumen per hari, dipilah sebabnya: ");
        appendBold(sb, "pelanggan baru", EMERALD);
        sb.append(" (pinjaman galon pertamanya) vs ");
        appendBold(sb, "pelanggan lama", BLUE);
        sb.append(" yang menambah pinjaman. Dihitung dari buku besar yang sama dengan \"Galon Dipinjam\" di detail pelanggan.");
        desc.setText(sb);
        desc.setPadding(0, dp(2), 0, dp(4));
        header.addView(desc);
        TextView link = text("Transaksi →", 14, BRAND, true);
        link.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        link.setMinHeight(dp(40));
        link.setOnClickListener(v -> startActivity(new Intent(this, TransactionListActivity.class)));
        header.addView(link, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout monthRow = new LinearLayout(this);
        monthRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView monthLabel = text("Pilih Bulan", 14, SLATE_500, false);
        monthLabel.setPadding(0, 0, dp(8), 0);
        monthRow.addView(monthLabel);
        btnMonth = outlined("— periode —");
        btnMonth.setOnClickListener(v -> pickMonth());
        monthRow.addView(btnMonth, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(monthRow);

        chips = new ChipGroup(this);
        chips.setChipSpacingHorizontal(dp(6));
        chips.setChipSpacingVertical(0);
        int[][] states = {{android.R.attr.state_checked}, {}};
        for (String[] p : PRESETS) {
            Chip c = new Chip(this);
            c.setText(p[1]);
            c.setTag(p[0]);
            c.setCheckable(true);
            c.setCheckedIconVisible(false);
            c.setChipBackgroundColor(new ColorStateList(states, new int[]{BRAND, Color.WHITE}));
            c.setTextColor(new ColorStateList(states, new int[]{Color.WHITE, SLATE_700}));
            c.setChipStrokeColor(new ColorStateList(states, new int[]{BRAND, SLATE_300}));
            c.setChipStrokeWidth(dp(1));
            c.setOnClickListener(v -> {
                preset = (String) v.getTag();
                month = null;
                useCustom = false;
                load();
            });
            chips.addView(c);
        }
        header.addView(chips);

        LinearLayout rangeRow = new LinearLayout(this);
        rangeRow.setGravity(Gravity.CENTER_VERTICAL);
        btnStart = outlined("Mulai");
        btnStart.setOnClickListener(v -> pickDate(true));
        rangeRow.addView(btnStart, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView sd = text("s/d", 13, SLATE_500, false);
        sd.setPadding(dp(6), 0, dp(6), 0);
        rangeRow.addView(sd);
        btnEnd = outlined("Akhir");
        btnEnd.setOnClickListener(v -> pickDate(false));
        rangeRow.addView(btnEnd, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(rangeRow);

        btnApply = new MaterialButton(this);
        btnApply.setText("Tampilkan");
        btnApply.setAllCaps(false);
        btnApply.setTextColor(Color.WHITE);
        btnApply.setBackgroundTintList(ColorStateList.valueOf(BRAND));
        btnApply.setOnClickListener(v -> {
            if (draftStart == null || draftEnd == null) return;
            useCustom = true;
            preset = "custom";
            month = null;
            load();
        });
        header.addView(btnApply, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        tvRange = text("", 12, SLATE_500, false);
        tvRange.setPadding(0, dp(4), 0, dp(10));
        header.addView(tvRange);

        summary = new LinearLayout(this);
        summary.setOrientation(LinearLayout.VERTICAL);
        header.addView(summary);
        chartHolder = new LinearLayout(this);
        chartHolder.setOrientation(LinearLayout.VERTICAL);
        header.addView(chartHolder);
    }

    private void syncFilterUi() {
        for (int i = 0; i < chips.getChildCount(); i++) {
            Chip c = (Chip) chips.getChildAt(i);
            c.setChecked(c.getTag().equals(preset));
        }
        String monthLabel = "— periode —";
        for (String[] o : monthOptions) {
            if (o[0].equals(month)) monthLabel = o[1];
        }
        btnMonth.setText(monthLabel);
        setOutlineActive(btnMonth, "month_pick".equals(preset));
        btnStart.setText(draftStart != null ? draftStart : "Mulai");
        btnEnd.setText(draftEnd != null ? draftEnd : "Akhir");
        btnApply.setStrokeColor(ColorStateList.valueOf(0x661565C0));
        btnApply.setStrokeWidth("custom".equals(preset) ? dp(2) : 0);
    }

    private void pickMonth() {
        if (monthOptions.isEmpty()) {
            Toast.makeText(this, "Daftar periode belum termuat. Tarik untuk menyegarkan.", Toast.LENGTH_SHORT).show();
            return;
        }
        final CharSequence[] labels = new CharSequence[monthOptions.size() + 1];
        labels[0] = "— periode —";
        int checked = 0;
        for (int i = 0; i < monthOptions.size(); i++) {
            labels[i + 1] = monthOptions.get(i)[1];
            if (monthOptions.get(i)[0].equals(month)) checked = i + 1;
        }
        new AlertDialog.Builder(this)
                .setTitle("Pilih Bulan")
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    d.dismiss();
                    month = which == 0 ? null : monthOptions.get(which - 1)[0];
                    useCustom = false;
                    preset = which == 0 ? "month" : "month_pick";
                    load();
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    private void pickDate(boolean start) {
        Calendar c = Calendar.getInstance();
        Date current = parse(start ? draftStart : draftEnd);
        if (current != null) c.setTime(current);
        new DatePickerDialog(this, (view, y, m, d) -> {
            String picked = String.format(Locale.US, "%04d-%02d-%02d", y, m + 1, d);
            if (start) draftStart = picked;
            else draftEnd = picked;
            syncFilterUi();
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show();
    }

    // ------------------------------------------------------------------ data

    private void load() {
        swipe.setRefreshing(true);
        syncFilterUi();
        final int seq = ++loadSeq;
        final String p = useCustom ? null : ("month_pick".equals(preset) ? null : preset);
        final String s = useCustom ? draftStart : null;
        final String e = useCustom ? draftEnd : null;
        final String m = month;
        final SyncSettings cfg = new SyncSettings(new SettingsDao(DatabaseHelper.getInstance(this)));
        final String staffUuid = LocationReporter.currentStaffUuid(this);
        new Thread(() -> {
            JSONObject res = null;
            String error = null;
            try {
                res = new SyncApi(cfg).galonGrowth(p, s, e, m, staffUuid);
            } catch (SyncApi.SyncException se) {
                error = serverMessage(se);
            } catch (Exception ex) {
                error = "Gagal memuat. Periksa koneksi.";
            }
            final JSONObject resF = res;
            final String errorF = error;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || seq != loadSeq) return;
                swipe.setRefreshing(false);
                if (resF != null) render(resF);
                else Toast.makeText(this, errorF, Toast.LENGTH_LONG).show();
            });
        }).start();
    }

    private String serverMessage(SyncApi.SyncException se) {
        try {
            String m = new JSONObject(se.body).optString("message", "");
            if (!m.isEmpty()) return m;
        } catch (Exception ignored) {
        }
        return "Ditolak server (kode " + se.code + ").";
    }

    private void render(JSONObject r) {
        activeStart = r.optString("start");
        activeEnd = r.optString("end");
        preset = r.optString("preset", preset);
        month = r.isNull("month") ? null : r.optString("month");
        draftStart = activeStart;
        draftEnd = activeEnd;

        monthOptions.clear();
        JSONArray opts = r.optJSONArray("month_options");
        for (int i = 0; opts != null && i < opts.length(); i++) {
            JSONObject o = opts.optJSONObject(i);
            monthOptions.add(new String[]{o.optString("value"), o.optString("label")});
        }

        String branch = r.isNull("branch") ? "" : r.optString("branch");
        SpannableStringBuilder heading = new SpannableStringBuilder("📈 Pertumbuhan Galon Beredar");
        if (!branch.isEmpty()) {
            int at = heading.length();
            heading.append(" · ").append(branch);
            heading.setSpan(new ForegroundColorSpan(SLATE_500), at, heading.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        tvHeading.setText(heading);
        tvRange.setText(boldRange());
        syncFilterUi();

        renderSummary(r.optJSONObject("totals"));
        renderChart(r.optJSONArray("days"));

        rows.clear();
        rows.add(header);
        JSONArray events = r.optJSONArray("events");
        if (events == null || events.length() == 0) {
            rows.add(new Empty());
        } else {
            addEventRows(events);
        }
        rows.add(new Footer());
        adapter.notifyDataSetChanged();
    }

    private CharSequence boldRange() {
        SpannableStringBuilder sb = new SpannableStringBuilder("Rentang aktif: ");
        appendBold(sb, longDate(activeStart), SLATE_500);
        sb.append(" — ");
        appendBold(sb, longDate(activeEnd), SLATE_500);
        return sb;
    }

    private void addEventRows(JSONArray events) {
        int i = 0;
        while (i < events.length()) {
            String date = events.optJSONObject(i).optString("date");
            DayHead head = new DayHead();
            head.date = date;
            rows.add(head);
            Event last = null;
            for (; i < events.length() && date.equals(events.optJSONObject(i).optString("date")); i++) {
                JSONObject o = events.optJSONObject(i);
                Event e = new Event();
                e.name = o.optString("name");
                e.uuid = o.isNull("customer_uuid") ? null : o.optString("customer_uuid");
                e.delta = o.optInt("delta");
                e.saldo = o.optInt("saldo");
                e.baru = "baru".equals(o.optString("cause"));
                if (e.baru) head.baru += e.delta;
                else head.lama += e.delta;
                rows.add(e);
                last = e;
            }
            if (last != null) last.lastInDay = true;
        }
    }

    // ------------------------------------------------------------------ ringkasan + grafik

    private void renderSummary(JSONObject t) {
        summary.removeAllViews();
        if (t == null) return;
        LinearLayout row1 = cardRow();
        row1.addView(statCard("Total Penambahan", "+" + fmt.format(t.optInt("total")), SLATE_800, null));
        row1.addView(statCard("🆕 Dari Pelanggan Baru", "+" + fmt.format(t.optInt("galon_baru")), EMERALD,
                fmt.format(t.optInt("orang_baru")) + " pelanggan"));
        summary.addView(row1);
        LinearLayout row2 = cardRow();
        row2.addView(statCard("➕ Pelanggan Lama Menambah", "+" + fmt.format(t.optInt("galon_lama")), BLUE,
                fmt.format(t.optInt("orang_lama")) + " pelanggan"));
        row2.addView(statCard("↩ Galon Kembali (netto turun)", "−" + fmt.format(t.optInt("turun")), SLATE_500, null));
        summary.addView(row2);
    }

    private LinearLayout cardRow() {
        LinearLayout row = new LinearLayout(this);
        row.setBaselineAligned(false);
        return row;
    }

    private View statCard(String label, String value, int color, String sub) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded(Color.WHITE, 12, 12));
        card.setPadding(dp(12), dp(12), dp(12), dp(12));
        card.setElevation(dp(1));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        lp.setMargins(dp(3), dp(3), dp(3), dp(3));
        card.setLayoutParams(lp);
        card.addView(text(label, 12, SLATE_500, false));
        SpannableStringBuilder sb = new SpannableStringBuilder(value);
        sb.setSpan(new StyleSpan(Typeface.BOLD), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new android.text.style.AbsoluteSizeSpan(22, true), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new ForegroundColorSpan(color), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.append(" galon");
        TextView tv = text("", 13, SLATE_500, false);
        tv.setText(sb);
        card.addView(tv);
        if (sub != null) card.addView(text(sub, 12, SLATE_500, false));
        return card;
    }

    private void renderChart(JSONArray days) {
        chartHolder.removeAllViews();
        int max = 0;
        int count = days != null ? days.length() : 0;
        for (int i = 0; i < count; i++) {
            JSONObject d = days.optJSONObject(i);
            max = Math.max(max, d.optInt("baru") + d.optInt("lama"));
        }
        if (max <= 0) return;
        if (count > CHART_MAX_DAYS) {
            TextView note = text("Rentang > 62 hari — grafik harian disembunyikan, ringkasan & rincian tetap lengkap.",
                    12, SLATE_500, false);
            note.setPadding(dp(3), dp(10), dp(3), dp(4));
            chartHolder.addView(note);
            return;
        }

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded(Color.WHITE, 12, 12));
        card.setPadding(dp(12), dp(12), dp(12), dp(12));
        card.setElevation(dp(1));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.setMargins(dp(3), dp(10), dp(3), dp(3));

        card.addView(text("Penambahan per hari", 12, SLATE_500, true));
        LinearLayout legend = new LinearLayout(this);
        legend.setPadding(0, dp(4), 0, dp(10));
        legend.setGravity(Gravity.CENTER_VERTICAL);
        addLegend(legend, EMERALD_BAR, "pelanggan baru");
        addLegend(legend, BLUE_BAR, "pelanggan lama");
        card.addView(legend);

        final TextView detail = text("Ketuk batang untuk melihat rinciannya.", 12, SLATE_500, false);

        LinearLayout bars = new LinearLayout(this);
        bars.setGravity(Gravity.BOTTOM);
        int barArea = dp(120);
        for (int i = 0; i < count; i++) {
            JSONObject d = days.optJSONObject(i);
            final String date = d.optString("date");
            final int baru = d.optInt("baru");
            final int lama = d.optInt("lama");
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
            col.setPadding(dp(2), 0, dp(2), 0);

            LinearLayout stack = new LinearLayout(this);
            stack.setOrientation(LinearLayout.VERTICAL);
            stack.setGravity(Gravity.BOTTOM);
            int hBaru = Math.round(baru * (float) barArea / max);
            int hLama = Math.round(lama * (float) barArea / max);
            if (hBaru > 0) stack.addView(bar(EMERALD_BAR, hBaru, true));
            if (hLama > 0) stack.addView(bar(BLUE_BAR, hLama, hBaru == 0));
            col.addView(stack, new LinearLayout.LayoutParams(dp(22), barArea));

            TextView label = text(shortDate(date), 10, SLATE_500, false);
            label.setPadding(0, dp(4), 0, 0);
            label.setSingleLine(true);
            col.addView(label);

            col.setOnClickListener(v -> detail.setText(longDate(date) + " — +" + fmt.format(baru + lama)
                    + " galon (" + fmt.format(baru) + " baru · " + fmt.format(lama) + " lama)"));
            bars.addView(col, new LinearLayout.LayoutParams(dp(32), ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.addView(bars);
        card.addView(scroll);
        detail.setPadding(0, dp(8), 0, 0);
        card.addView(detail);
        chartHolder.addView(card, cardLp);
    }

    private View bar(int color, int height, boolean roundTop) {
        View v = new View(this);
        v.setBackground(rounded(color, roundTop ? 4 : 0, 0));
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height));
        return v;
    }

    private void addLegend(LinearLayout row, int color, String label) {
        View dot = new View(this);
        dot.setBackground(rounded(color, 2, 2));
        row.addView(dot, new LinearLayout.LayoutParams(dp(10), dp(10)));
        TextView tv = text(label, 11, SLATE_500, false);
        tv.setPadding(dp(4), 0, dp(12), 0);
        row.addView(tv);
    }

    // ------------------------------------------------------------------ baris daftar

    private static final class DayHead { String date; int baru; int lama; }
    private static final class Event { String name; String uuid; int delta; int saldo; boolean baru; boolean lastInDay; }
    private static final class Empty { }
    private static final class Footer { }

    private final class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @Override
        public int getItemViewType(int position) {
            Object o = rows.get(position);
            if (o == header) return 0;
            if (o instanceof DayHead) return 1;
            if (o instanceof Event) return 2;
            if (o instanceof Empty) return 3;
            return 4;
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            View v;
            if (type == 0) {
                v = header;
            } else if (type == 2) {
                v = new EventRow();
            } else {
                TextView tv = new TextView(GalonGrowthActivity.this);
                v = tv;
            }
            if (type != 0 && v.getLayoutParams() == null) {
                v.setLayoutParams(rowParams(0, 0));
            }
            return new RecyclerView.ViewHolder(v) { };
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int position) {
            Object o = rows.get(position);
            if (o instanceof DayHead) bindDayHead((TextView) h.itemView, (DayHead) o);
            else if (o instanceof Event) ((EventRow) h.itemView).bind((Event) o);
            else if (o instanceof Empty) bindEmpty((TextView) h.itemView);
            else if (o instanceof Footer) bindFooter((TextView) h.itemView);
        }
    }

    private RecyclerView.LayoutParams rowParams(int top, int bottom) {
        RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(15), top, dp(15), bottom);
        return lp;
    }

    private void bindDayHead(TextView tv, DayHead d) {
        tv.setLayoutParams(rowParams(dp(12), 0));
        tv.setBackground(rounded(SLATE_50, 12, 0));
        tv.setPadding(dp(14), dp(10), dp(14), dp(10));
        SpannableStringBuilder sb = new SpannableStringBuilder();
        appendBold(sb, longDate(d.date), SLATE_700);
        sb.append("\n");
        int at = sb.length();
        sb.append("+").append(fmt.format(d.baru + d.lama)).append(" galon");
        sb.setSpan(new ForegroundColorSpan(SLATE_500), at, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (d.baru > 0) appendBold(sb, " · " + fmt.format(d.baru) + " baru", EMERALD);
        if (d.lama > 0) appendBold(sb, " · " + fmt.format(d.lama) + " lama", BLUE);
        tv.setTextSize(13);
        tv.setText(sb);
    }

    private void bindEmpty(TextView tv) {
        tv.setLayoutParams(rowParams(dp(12), 0));
        tv.setBackground(rounded(Color.WHITE, 12, 12));
        tv.setPadding(dp(16), dp(28), dp(16), dp(28));
        tv.setGravity(Gravity.CENTER);
        tv.setTextColor(SLATE_500);
        tv.setTextSize(14);
        tv.setText("💧\nTidak ada penambahan galon beredar pada rentang ini.");
    }

    private void bindFooter(TextView tv) {
        tv.setLayoutParams(rowParams(dp(16), 0));
        tv.setTextColor(SLATE_500);
        tv.setTextSize(11);
        tv.setText("Penambahan = kenaikan NETTO saldo \"Galon Dipinjam\" seorang pelanggan pada hari itu (tukar galon — "
                + "kembalikan lalu ambil lagi dalam jumlah sama — tidak dihitung). \"Pelanggan baru\" = hari pertama "
                + "pelanggan tsb meminjam galon. Pesanan TERTUNDA dan koreksi manual saldo tidak termasuk; "
                + "bucket \"Umum\" (walk-in) tidak dicatat galonnya.");
    }

    private final class EventRow extends LinearLayout {
        private final TextView badge, name, delta, saldo;

        EventRow() {
            super(GalonGrowthActivity.this);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(dp(14), dp(8), dp(14), dp(8));
            LinearLayout left = new LinearLayout(getContext());
            left.setOrientation(VERTICAL);
            badge = text("", 11, EMERALD, true);
            badge.setPadding(dp(8), dp(2), dp(8), dp(2));
            left.addView(badge, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            name = text("", 14, SLATE_700, false);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setPadding(0, dp(4), 0, 0);
            left.addView(name);
            addView(left, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            LinearLayout right = new LinearLayout(getContext());
            right.setOrientation(VERTICAL);
            right.setGravity(Gravity.END);
            right.setPadding(dp(8), 0, 0, 0);
            delta = text("", 15, EMERALD, true);
            delta.setGravity(Gravity.END);
            right.addView(delta, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            saldo = text("", 12, SLATE_500, false);
            saldo.setGravity(Gravity.END);
            right.addView(saldo, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            addView(right);
        }

        void bind(Event e) {
            RecyclerView.LayoutParams lp = rowParams(0, 0);
            setLayoutParams(lp);
            setBackground(rounded(Color.WHITE, 0, e.lastInDay ? 12 : 0));
            int color = e.baru ? EMERALD : BLUE;
            badge.setText(e.baru ? "🆕 Pelanggan Baru" : "➕ Tambah Pinjaman");
            badge.setTextColor(color);
            badge.setBackground(rounded(e.baru ? EMERALD_BG : BLUE_BG, 10, 10));
            name.setText(e.name);
            delta.setText("+" + fmt.format(e.delta));
            delta.setTextColor(color);
            saldo.setText("galon · saldo " + fmt.format(e.saldo));
            if (e.uuid != null) {
                name.setTextColor(BRAND);
                setOnClickListener(v -> openCustomer(e.uuid));
            } else {
                name.setTextColor(SLATE_700);
                setOnClickListener(null);
                setClickable(false);
            }
        }
    }

    private void openCustomer(String uuid) {
        long id = new CustomerDao(DatabaseHelper.getInstance(this)).getIdBySyncUuid(uuid);
        if (id <= 0) {
            Toast.makeText(this, "Data pelanggan ini belum ada di HP. Sinkronkan dulu.", Toast.LENGTH_LONG).show();
            return;
        }
        startActivity(new Intent(this, CustomerDetailActivity.class).putExtra("customer_id", id));
    }

    // ------------------------------------------------------------------ pembantu

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(sp);
        tv.setTextColor(color);
        if (bold) tv.setTypeface(tv.getTypeface(), Typeface.BOLD);
        return tv;
    }

    private MaterialButton outlined(String label) {
        MaterialButton b = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        b.setText(label);
        b.setAllCaps(false);
        b.setMaxLines(1);
        b.setEllipsize(TextUtils.TruncateAt.END);
        b.setTextColor(SLATE_700);
        setOutlineActive(b, false);
        return b;
    }

    private void setOutlineActive(MaterialButton b, boolean active) {
        b.setStrokeColor(ColorStateList.valueOf(active ? BRAND : SLATE_300));
        b.setStrokeWidth(dp(active ? 2 : 1));
    }

    private void appendBold(SpannableStringBuilder sb, String s, int color) {
        int at = sb.length();
        sb.append(s);
        sb.setSpan(new StyleSpan(Typeface.BOLD), at, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new ForegroundColorSpan(color), at, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    /** @param top radius sudut atas (dp), @param bottom radius sudut bawah (dp) */
    private GradientDrawable rounded(int color, int top, int bottom) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        float t = dp(top);
        float b = dp(bottom);
        g.setCornerRadii(new float[]{t, t, t, t, b, b, b, b});
        return g;
    }

    private Date parse(String day) {
        if (day == null) return null;
        try {
            return isoDay.parse(day);
        } catch (ParseException e) {
            return null;
        }
    }

    private String longDate(String day) {
        Date d = parse(day);
        return d != null ? longDay.format(d) : String.valueOf(day);
    }

    private String shortDate(String day) {
        Date d = parse(day);
        return d != null ? shortDay.format(d) : String.valueOf(day);
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
