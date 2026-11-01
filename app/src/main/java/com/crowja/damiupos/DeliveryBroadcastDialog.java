package com.crowja.damiupos;

import android.app.Activity;
import android.content.DialogInterface;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.SettingsDao;
import com.crowja.damiupos.sync.SyncApi;
import com.crowja.damiupos.sync.SyncSettings;
import com.crowja.damiupos.util.BroadcastScope;
import com.crowja.damiupos.util.BroadcastScope.Item;
import com.google.android.material.checkbox.MaterialCheckBox;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * "📢 Broadcast WA Masalah" dari Antrian Delivery — KETIGA tab: order Antrian Saya, Perangkat Lain dan
 * Pesanan Terbuka (pemilih sumber; bawaan = tab yang sedang dibuka), atau PERSIS order terpilih dari
 * "Aksi ⋮" mode-pilih Perangkat Lain / Pesanan Terbuka. Centang = dikirimi; kapsul slug produk + jenis
 * galon, filter produk & jenis galon (kapsul ▾ yang sama dengan filter tab), pesan ber-{nama} dengan
 * preset masalah. Kirim lewat server/Bridge ({@code POST /api/delivery/broadcast-masalah}) — server
 * menerima uuid order mana pun se-cabang (maks 300), satu pesan per pelanggan, akun WA perangkat yang
 * menangani order diutamakan, kirim ulang pesan sama tak dobel. Logika murni: {@link BroadcastScope}.
 */
final class DeliveryBroadcastDialog {

    /** Jembatan ke helper kartu {@link DeliveryQueueActivity} (data tab, kapsul produk, filter). */
    interface Host {
        /** Order satu sumber ({@link BroadcastScope#MINE}/{@code OTHER}/{@code OPEN}); yang tak bisa dikirimi
         *  (tanpa uuid / tanpa pelanggan) sudah dibuang. {@link Item#chips} = baris kapsul siap gambar. */
        List<Item> broadcastItems(int source);

        /** Antrean Perangkat Lain di HP berasal dari muat server yang BERHASIL (boleh kosong). */
        boolean otherQueueLoaded();

        /** Muat ulang antrean Perangkat Lain; {@code done(ok)} dipanggil di UI thread. */
        void reloadOtherQueue(Consumer<Boolean> done);

        /** Deret kapsul filter produk ▾ (pilih jenis galon) — null bila tak ada produk. */
        HorizontalScrollView productFilterRow(Set<String> sel, Runnable onChange);

        /** Pilihan jenis galon per produk untuk {@code sel} (boleh null). */
        Map<String, Set<String>> productTypeFilter(Set<String> sel);

        /** Dialog ditutup → lepaskan state filter {@code sel}. */
        void releaseProductFilter(Set<String> sel);

        /** Kapsul produk ber-tag jenis galon order {@code it}. */
        void addProductChips(LinearLayout into, Item it);

        TextView badge(CharSequence label, int bg);
    }

    static final String[][] PRESETS = {
            {"⏰ Terlambat", "Halo {nama}, mohon maaf pengiriman galon Anda hari ini terlambat karena ada kendala di jalan. Pesanan tetap kami antar secepatnya. Terima kasih atas kesabarannya 🙏"},
            {"🚰 Stok habis", "Halo {nama}, mohon maaf stok galon sedang kosong/terbatas sehingga pesanan Anda tertunda. Kami kabari lagi begitu siap diantar. Terima kasih 🙏"},
            {"🌧️ Cuaca", "Halo {nama}, mohon maaf karena hujan/cuaca buruk pengiriman galon Anda sedikit tertunda. Pesanan tetap kami antar. Terima kasih atas pengertiannya 🙏"},
            {"🛵 Armada", "Halo {nama}, mohon maaf kendaraan pengiriman kami sedang bermasalah sehingga pesanan Anda tertunda. Kami usahakan secepatnya. Terima kasih 🙏"},
    };

    private static final String[] SOURCE_LABELS = {"📱 Antrian Saya", "👥 Perangkat Lain", "🎲 Pesanan Terbuka"};
    /** Latar badge — semuanya gelap sehingga teks putih (chipTextColor) ≥ 7:1. */
    private static final int BG_SLATE = 0xFF475569;
    static final int BG_OTHER = 0xFF1E40AF;
    static final int BG_OPEN = 0xFF92400E;

    private final Activity act;
    private final Host host;
    private final int activeTab;
    private final Collection<String> onlyUuids;
    private final Runnable onSent;

    private List<Item> items;
    private final Set<Integer> sources = new HashSet<>();
    private final Set<String> sel = new LinkedHashSet<>();
    private final Set<String> unchecked = new HashSet<>();
    /** uuid yang pernah ada di daftar dialog ini — baris yang datang belakangan dikenali lewat ini. */
    private final Set<String> known = new HashSet<>();
    private final Map<String, View> rowByUuid = new HashMap<>();
    private final Map<String, CheckBox> boxByUuid = new HashMap<>();
    private final List<View> mineBadges = new ArrayList<>();
    /** Wadah baris per sumber (urutan MINE, OTHER, OPEN); diisi saat sumbernya pertama kali dicentang. */
    private final LinearLayout[] groups = new LinearLayout[BroadcastScope.SOURCE_COUNT];
    private final Set<Integer> built = new HashSet<>();

    private AlertDialog dlg;
    private LinearLayout list;
    private TextView count, capWarn, empty, statusText;
    private View statusRow, spinner;
    private final CheckBox[] srcBoxes = new CheckBox[BroadcastScope.SOURCE_COUNT];
    private boolean loadingOther, loadFailed, otherLoaded, syncing, confirming, sent;
    /** Aksi massal terakhir = "☐ Kosongkan" → baris yang tiba sesudahnya ikut tak tercentang. */
    private boolean clearedAll;

    private DeliveryBroadcastDialog(Activity act, Host host, int activeTab, Collection<String> onlyUuids, Runnable onSent) {
        this.act = act;
        this.host = host;
        this.activeTab = activeTab;
        this.onlyUuids = onlyUuids;
        this.onSent = onSent;
    }

    /**
     * @param activeTab tab yang sedang dibuka (0 Antrian Saya, 1 Perangkat Lain, 2 Pesanan Terbuka) → sumber bawaan
     * @param onlyUuids null = semua sumber dengan pemilih; selain itu PERSIS order ini (mode pilih), semua tercentang
     * @param onSent    dipanggil sesudah server menjawab (mis. keluar dari mode pilih); boleh null
     */
    static void show(Activity act, Host host, int activeTab, Collection<String> onlyUuids, Runnable onSent) {
        if (act == null || host == null) return;
        if (!cfg(act).isEnrolled()) {
            toast(act, "Perangkat belum terhubung ke server.");
            return;
        }
        new DeliveryBroadcastDialog(act, host, activeTab, onlyUuids, onSent).open();
    }

    private boolean scoped() {
        return this.onlyUuids != null;
    }

    private List<Item> collect() {
        List<Item> all = BroadcastScope.merge(this.host.broadcastItems(BroadcastScope.MINE),
                this.host.broadcastItems(BroadcastScope.OTHER), this.host.broadcastItems(BroadcastScope.OPEN));
        return this.scoped() ? BroadcastScope.restrict(all, this.onlyUuids) : all;
    }

    private void open() {
        this.items = this.collect();
        // Mode pilih berasal dari daftar tab yang sudah ada → tak perlu memuat apa pun.
        this.otherLoaded = this.scoped() || this.host.otherQueueLoaded();
        BroadcastScope.markNew(this.items, this.known, this.unchecked, true);
        if (this.items.isEmpty() && this.otherLoaded) {
            toast(this.act, this.scoped()
                    ? "Order terpilih belum tersinkron atau tanpa pelanggan — tak ada yang bisa dikirimi."
                    : "Antrean kosong (atau belum tersinkron) — tak ada pelanggan untuk dikirimi.");
            return;
        }
        if (this.scoped()) {
            for (int s = 0; s < BroadcastScope.SOURCE_COUNT; s++) this.sources.add(s);
        } else {
            this.sources.addAll(BroadcastScope.defaultSources(this.activeTab));
        }

        int pad = dp(16);
        int textColor = ContextCompat.getColor(this.act, R.color.text_primary);
        int secondary = ContextCompat.getColor(this.act, R.color.text_secondary);
        LinearLayout box = new LinearLayout(this.act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, dp(8), pad, 0);

        // ---- pesan + preset
        EditText msg = new EditText(this.act);
        msg.setText(PRESETS[0][1]);
        msg.setMinLines(3);
        msg.setMaxLines(6);
        msg.setTextSize(14f);
        msg.setGravity(Gravity.TOP | Gravity.START);
        LinearLayout presets = new LinearLayout(this.act);
        presets.setOrientation(LinearLayout.HORIZONTAL);
        for (String[] p : PRESETS) {
            TextView chip = this.host.badge(p[0], BG_SLATE);
            chip.setTextSize(12f);
            chip.setPadding(dp(10), dp(4), dp(10), dp(4));
            presets.addView(this.touchTarget(chip, v -> msg.setText(p[1])));
        }
        box.addView(hscroll(this.act, presets));
        box.addView(msg);
        TextView hint = new TextView(this.act);
        hint.setText("{nama} diganti nama pelanggan. Dikirim lewat WA Bridge, satu pesan per pelanggan.");
        hint.setTextSize(11f);
        hint.setTextColor(secondary);
        box.addView(hint);

        // ---- sumber order
        if (!this.scoped()) {
            TextView sl = new TextView(this.act);
            sl.setText("Kirim ke order di:");
            sl.setTextSize(12f);
            sl.setTextColor(textColor);
            sl.setPadding(0, dp(10), 0, 0);
            box.addView(sl);
            // Bertumpuk (bukan satu baris geser): di HP 360dp ketiga sumber harus terlihat semua,
            // termasuk saat yang tercentang hanya "Pesanan Terbuka".
            LinearLayout srcCol = new LinearLayout(this.act);
            srcCol.setOrientation(LinearLayout.VERTICAL);
            for (int s = 0; s < BroadcastScope.SOURCE_COUNT; s++) {
                final int src = s;
                MaterialCheckBox cb = new MaterialCheckBox(this.act);
                cb.setMinHeight(dp(48));
                cb.setMinimumHeight(dp(48));
                cb.setTextSize(13f);
                cb.setTextColor(textColor);
                cb.setPadding(dp(2), 0, dp(12), 0);
                cb.setChecked(this.sources.contains(src));
                cb.setOnCheckedChangeListener((b, on) -> {
                    if (on) this.sources.add(src);
                    else this.sources.remove(src);
                    this.maybeLoadOther();
                    this.refilter();
                });
                this.srcBoxes[s] = cb;
                srcCol.addView(cb, new LinearLayout.LayoutParams(-1, -2));
            }
            box.addView(srcCol);
        } else {
            TextView sc = new TextView(this.act);
            int skipped = Math.max(0, new HashSet<>(this.onlyUuids).size() - this.items.size());
            sc.setText("🎯 " + this.items.size() + " order terpilih dari " + SOURCE_LABELS[BroadcastScope.defaultSource(this.activeTab)]
                    + (skipped > 0 ? " · " + skipped + " dilewati (belum tersinkron / tanpa pelanggan)" : ""));
            sc.setTextSize(12f);
            sc.setTextColor(textColor);
            sc.setPadding(0, dp(10), 0, 0);
            box.addView(sc);
        }

        // ---- status muat Perangkat Lain
        LinearLayout st = new LinearLayout(this.act);
        st.setOrientation(LinearLayout.HORIZONTAL);
        st.setGravity(Gravity.CENTER_VERTICAL);
        st.setMinimumHeight(dp(48));
        ProgressBar pb = new ProgressBar(this.act, null, android.R.attr.progressBarStyleSmall);
        LinearLayout.LayoutParams pbl = new LinearLayout.LayoutParams(dp(20), dp(20));
        pbl.setMarginEnd(dp(8));
        pb.setLayoutParams(pbl);
        st.addView(pb);
        TextView stt = new TextView(this.act);
        stt.setTextSize(13f);
        stt.setTextColor(textColor);
        st.addView(stt);
        st.setVisibility(View.GONE);
        st.setOnClickListener(v -> {
            if (this.loadFailed && !this.loadingOther) this.loadOther();
        });
        this.statusRow = st;
        this.spinner = pb;
        this.statusText = stt;
        box.addView(st);

        // ---- filter produk & jenis galon (kosong = semua)
        TextView fl = new TextView(this.act);
        fl.setText("Filter produk & jenis galon (ketuk kapsul ▾):");
        fl.setTextSize(12f);
        fl.setTextColor(textColor);
        fl.setPadding(0, dp(6), 0, 0);
        box.addView(fl);
        HorizontalScrollView chips = this.host.productFilterRow(this.sel, this::refilter);
        if (chips != null) {
            this.enlargeChipTargets(chips);
            box.addView(chips);
        }

        LinearLayout allRow = new LinearLayout(this.act);
        allRow.setOrientation(LinearLayout.HORIZONTAL);
        allRow.setGravity(Gravity.CENTER_VERTICAL);
        this.count = new TextView(this.act);
        this.count.setTextSize(13f);
        this.count.setTypeface(null, Typeface.BOLD);
        this.count.setTextColor(textColor);
        this.count.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        allRow.addView(this.count);
        TextView all = this.linkButton("☑ Semua");
        TextView none = this.linkButton("☐ Kosongkan");
        all.setOnClickListener(v -> this.setVisibleChecked(true));
        none.setOnClickListener(v -> this.setVisibleChecked(false));
        allRow.addView(all);
        allRow.addView(none);
        box.addView(allRow);

        this.capWarn = new TextView(this.act);
        this.capWarn.setTextSize(12f);
        this.capWarn.setTextColor(ContextCompat.getColor(this.act, R.color.red));
        this.capWarn.setVisibility(View.GONE);
        box.addView(this.capWarn);

        this.list = new LinearLayout(this.act);
        this.list.setOrientation(LinearLayout.VERTICAL);
        box.addView(this.list);
        this.empty = new TextView(this.act);
        this.empty.setText("Tak ada order di sumber/filter terpilih.");
        this.empty.setTextSize(13f);
        this.empty.setTextColor(secondary);
        this.empty.setPadding(0, dp(8), 0, dp(8));
        box.addView(this.empty);

        this.renderRows();
        this.refilter();

        ScrollView sv = new ScrollView(this.act);
        sv.addView(box);
        this.dlg = (new AlertDialog.Builder(this.act)).setTitle("📢 Broadcast WA Masalah").setView(sv)
                .setPositiveButton("Kirim", (DialogInterface.OnClickListener) null)
                .setNegativeButton("Batal", (DialogInterface.OnClickListener) null).create();
        this.dlg.setOnShowListener(di -> this.dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> this.confirmSend(msg)));
        this.dlg.setOnDismissListener(di -> this.host.releaseProductFilter(this.sel));
        this.dlg.show();
        this.maybeLoadOther();
    }

    /** Kapsul kecil dibungkus bidang sentuh ≥ 48dp (tampilan kapsul tetap; TalkBack membaca teks kapsul). */
    private View touchTarget(View chip, View.OnClickListener l) {
        FrameLayout f = new FrameLayout(this.act);
        f.setLayoutParams(new LinearLayout.LayoutParams(-2, -2));
        f.setMinimumHeight(dp(48));
        f.setPadding(0, 0, dp(6), 0);
        chip.setLayoutParams(new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_VERTICAL));
        f.addView(chip);
        f.setClickable(true);
        f.setFocusable(true);
        f.setOnClickListener(l);
        return f;
    }

    /** Kapsul filter produk dari kartu tab (±30dp) → tiap kapsul dibungkus bidang sentuh 48dp; ketukan diteruskan. */
    private void enlargeChipTargets(HorizontalScrollView hsv) {
        if (hsv.getChildCount() == 0 || !(hsv.getChildAt(0) instanceof LinearLayout)) return;
        LinearLayout row = (LinearLayout) hsv.getChildAt(0);
        for (int i = 0; i < row.getChildCount(); i++) {
            View chip = row.getChildAt(i);
            if (!chip.hasOnClickListeners()) continue;
            row.removeViewAt(i);
            chip.setClickable(false);   // sentuhan jatuh ke pembungkus; listener kapsul tetap dipakai lewat performClick
            row.addView(this.touchTarget(chip, v -> chip.performClick()), i);
        }
    }

    private TextView linkButton(String text) {
        TextView t = new TextView(this.act);
        t.setText(text);
        t.setTextSize(14f);
        t.setTextColor(ContextCompat.getColor(this.act, R.color.primary));
        t.setGravity(Gravity.CENTER);
        t.setMinHeight(dp(48));
        t.setMinimumHeight(dp(48));
        t.setPadding(dp(10), 0, dp(10), 0);
        t.setClickable(true);
        t.setFocusable(true);
        return t;
    }

    /** Susun ulang wadah per sumber; baris digambar malas oleh {@link #ensureRows()} (sumber yang dicentang saja). */
    private void renderRows() {
        this.list.removeAllViews();
        this.rowByUuid.clear();
        this.boxByUuid.clear();
        this.mineBadges.clear();
        this.built.clear();
        for (int s = 0; s < BroadcastScope.SOURCE_COUNT; s++) {
            LinearLayout g = new LinearLayout(this.act);
            g.setOrientation(LinearLayout.VERTICAL);
            this.groups[s] = g;
            this.list.addView(g);
        }
        this.ensureRows();
    }

    /** Baris sumber yang dicentang tapi belum digambar — dari Antrian Saya tak perlu membangun ratusan baris
     *  Perangkat Lain di UI thread kecuali sumber itu memang dipilih. */
    private void ensureRows() {
        if (this.list == null) return;
        Set<Integer> todo = new HashSet<>(this.sources);
        todo.removeAll(this.built);
        if (todo.isEmpty()) return;
        int textColor = ContextCompat.getColor(this.act, R.color.text_primary);
        for (Item it : this.items) {
            if (!todo.contains(it.source) || it.source < 0 || it.source >= BroadcastScope.SOURCE_COUNT) continue;
            LinearLayout row = new LinearLayout(this.act);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(0, dp(2), 0, dp(4));
            CheckBox cb = new CheckBox(this.act);
            cb.setChecked(!this.unchecked.contains(it.uuid));
            cb.setText(it.label);
            cb.setTextColor(textColor);
            cb.setTextSize(14f);
            cb.setMinHeight(dp(48));
            cb.setMinimumHeight(dp(48));
            cb.setOnCheckedChangeListener((b, c) -> {
                if (c) this.unchecked.remove(it.uuid);
                else this.unchecked.add(it.uuid);
                if (!this.syncing) this.recount();
            });
            row.addView(cb);
            LinearLayout badges = new LinearLayout(this.act);
            badges.setOrientation(LinearLayout.HORIZONTAL);
            badges.setPadding(dp(32), 0, 0, 0);
            if (it.source == BroadcastScope.MINE) {
                TextView b = this.host.badge("📱 Saya", BG_SLATE);
                this.mineBadges.add(b);
                badges.addView(b);
            } else if (!it.badge.isEmpty()) {
                badges.addView(this.host.badge(it.badge, it.source == BroadcastScope.OPEN ? BG_OPEN : BG_OTHER));
            }
            this.host.addProductChips(badges, it);
            if (badges.getChildCount() > 0) row.addView(hscroll(this.act, badges));
            this.rowByUuid.put(it.uuid, row);
            this.boxByUuid.put(it.uuid, cb);
            this.groups[it.source].addView(row);
        }
        this.built.addAll(todo);
    }

    private void refilter() {
        if (this.list == null) return;
        this.ensureRows();
        Map<String, Set<String>> typeSel = this.host.productTypeFilter(this.sel);
        int shown = 0;
        for (Item it : this.items) {
            View row = this.rowByUuid.get(it.uuid);
            boolean vis = BroadcastScope.visible(it, this.sources, this.sel, typeSel);
            if (vis) shown++;
            if (row != null) row.setVisibility(vis ? View.VISIBLE : View.GONE);
        }
        // Badge "📱 Saya" hanya berguna bila sumber dicampur.
        int mix = this.sources.size() > 1 ? View.VISIBLE : View.GONE;
        for (View b : this.mineBadges) b.setVisibility(mix);
        boolean otherOn = this.sources.contains(BroadcastScope.OTHER);
        this.empty.setVisibility(shown == 0 && !(this.loadingOther && otherOn) ? View.VISIBLE : View.GONE);
        // Status muat/gagal Perangkat Lain hanya relevan selama sumber itu dicentang.
        if (this.statusRow != null) {
            this.statusRow.setVisibility(otherOn && (this.loadingOther || this.loadFailed) ? View.VISIBLE : View.GONE);
        }
        this.updateSourceLabels();
        this.recount();
    }

    private List<Item> currentPicked() {
        return BroadcastScope.picked(this.items, this.sources, this.sel, this.host.productTypeFilter(this.sel), this.unchecked);
    }

    private void recount() {
        if (this.count == null) return;
        List<Item> p = this.currentPicked();
        this.count.setText(BroadcastScope.summary(p.size(), BroadcastScope.customerCount(p)));
        String w = BroadcastScope.capWarning(p.size());
        this.capWarn.setText(w != null ? w : "");
        this.capWarn.setVisibility(w != null ? View.VISIBLE : View.GONE);
    }

    /** ☑ Semua = centang baris yang TAMPIL (hasil filter); ☐ Kosongkan = lepas semua, termasuk baris yang
     *  baru tiba sesudahnya (antrean Perangkat Lain yang masih dimuat) sampai ☑ Semua ditekan lagi. */
    private void setVisibleChecked(boolean on) {
        Map<String, Set<String>> typeSel = this.host.productTypeFilter(this.sel);
        this.clearedAll = !on;
        this.syncing = true;
        for (Item it : this.items) {
            if (on && !BroadcastScope.visible(it, this.sources, this.sel, typeSel)) continue;
            CheckBox cb = this.boxByUuid.get(it.uuid);
            if (on) this.unchecked.remove(it.uuid);
            else this.unchecked.add(it.uuid);
            if (cb != null) cb.setChecked(on);
        }
        this.syncing = false;
        this.recount();
    }

    private void updateSourceLabels() {
        int[] n = BroadcastScope.countBySource(this.items);
        for (int s = 0; s < BroadcastScope.SOURCE_COUNT; s++) {
            CheckBox cb = this.srcBoxes[s];
            if (cb == null) continue;
            boolean other = s == BroadcastScope.OTHER;
            String c = BroadcastScope.sourceCountText(n[s], other && this.loadingOther, !other || this.otherLoaded);
            cb.setText(c.isEmpty() ? SOURCE_LABELS[s] : SOURCE_LABELS[s] + " (" + c + ")");
        }
    }

    /** Muat antrean Perangkat Lain hanya bila sumber itu dicentang dan datanya belum ada di HP. */
    private void maybeLoadOther() {
        if (this.dlg == null || this.statusRow == null) return;
        if (!BroadcastScope.needsOtherLoad(this.scoped(), this.otherLoaded, this.loadingOther, this.sources)) return;
        if (this.host.otherQueueLoaded()) {
            // Layar antrean keburu memuatnya selama dialog terbuka → pakai itu, tanpa panggilan jaringan.
            this.otherLoaded = true;
            this.loadFailed = false;
            this.reloadItems();
            return;
        }
        this.loadOther();
    }

    /** Susun ulang daftar dari data tab terbaru; baris baru ikut keadaan aksi massal terakhir. */
    private void reloadItems() {
        this.items = this.collect();
        BroadcastScope.markNew(this.items, this.known, this.unchecked, !this.clearedAll);
        this.renderRows();
    }

    /** Antrean Perangkat Lain belum ada → muat dulu (indikator), lalu daftar disusun ulang. */
    private void loadOther() {
        this.loadingOther = true;
        this.loadFailed = false;
        this.spinner.setVisibility(View.VISIBLE);
        this.statusText.setText("Memuat antrean perangkat lain…");
        this.refilter();
        this.host.reloadOtherQueue(ok -> {
            this.loadingOther = false;
            if (this.dlg == null || !this.dlg.isShowing()) return;
            if (Boolean.TRUE.equals(ok)) {
                this.otherLoaded = true;
                // Baris yang baru tiba ikut keadaan aksi massal terakhir (tak tercentang sesudah Kosongkan).
                this.reloadItems();
            } else {
                this.loadFailed = true;
                this.spinner.setVisibility(View.GONE);
                this.statusText.setText("⚠️ Gagal memuat antrean perangkat lain — ketuk untuk coba lagi.");
            }
            this.refilter();
        });
    }

    private void confirmSend(EditText msg) {
        if (this.confirming || this.sent) return;
        String text = msg.getText().toString().trim();
        if (text.isEmpty()) {
            msg.setError("Tulis pesannya dulu");
            return;
        }
        List<Item> picked = this.currentPicked();
        if (picked.isEmpty()) {
            Toast.makeText(this.act, "Belum ada order yang dicentang.", Toast.LENGTH_SHORT).show();
            return;
        }
        String cap = BroadcastScope.capWarning(picked.size());
        if (cap != null) {
            Toast.makeText(this.act, cap, Toast.LENGTH_LONG).show();
            return;
        }
        List<String> uuids = BroadcastScope.uuids(picked);
        this.confirming = true;
        AlertDialog c = (new AlertDialog.Builder(this.act))
                .setTitle("Kirim ke " + BroadcastScope.customerCount(picked) + " pelanggan?")
                .setMessage(text + "\n\n" + uuids.size() + " order · " + BroadcastScope.sourceBreakdown(picked))
                .setPositiveButton("📢 Kirim", (d2, w2) -> {
                    if (this.sent) return;
                    this.sent = true;
                    this.dlg.dismiss();
                    send(this.act, text, uuids, this.onSent);
                })
                .setNegativeButton("Batal", (DialogInterface.OnClickListener) null).create();
        c.setOnDismissListener(d -> this.confirming = false);
        c.show();
    }

    /** POST /api/delivery/broadcast-masalah; hasil (pesan server + daftar gagal) ditampilkan apa adanya. */
    static void send(Activity act, String text, List<String> uuids, Runnable onSent) {
        SyncSettings cfg = cfg(act);
        android.app.ProgressDialog wait = new android.app.ProgressDialog(act);
        wait.setMessage("Mengirim broadcast…");
        wait.setCancelable(false);
        wait.show();
        new Thread(() -> {
            String m;
            boolean answered = false;
            StringBuilder failed = new StringBuilder();
            try {
                JSONObject r = new SyncApi(cfg).broadcastMasalah(text, uuids);
                answered = true;
                m = r.optString("message", "Terkirim.");
                JSONArray f = r.optJSONArray("failed");
                for (int i = 0; f != null && i < f.length(); i++) {
                    JSONObject x = f.optJSONObject(i);
                    if (x != null) failed.append("• ").append(x.optString("name", "?")).append(" — ").append(x.optString("error", "gagal")).append('\n');
                }
            } catch (SyncApi.SyncException se) {
                String sm = se.serverMessage();
                m = sm != null && !sm.isEmpty() ? sm : "Gagal mengirim broadcast (kode " + se.code + ").";
            } catch (Exception e) {
                m = "Gagal mengirim broadcast — periksa koneksi internet.";
            }
            final String fm = m;
            final boolean ok = answered;
            act.runOnUiThread(() -> {
                try { wait.dismiss(); } catch (Exception ignored) {}
                if (act.isFinishing() || act.isDestroyed()) return;
                if (ok && onSent != null) onSent.run();
                if (failed.length() == 0) {
                    Toast.makeText(act, fm, Toast.LENGTH_LONG).show();
                } else {
                    (new AlertDialog.Builder(act)).setTitle("📢 Broadcast WA Masalah").setMessage(fm + "\n\nTidak terkirim:\n" + failed)
                            .setPositiveButton("OK", (DialogInterface.OnClickListener) null).show();
                }
            });
        }).start();
    }

    private static HorizontalScrollView hscroll(Activity act, View child) {
        HorizontalScrollView s = new HorizontalScrollView(act);
        s.setHorizontalScrollBarEnabled(false);
        s.addView(child);
        return s;
    }

    private static SyncSettings cfg(Activity act) {
        return new SyncSettings(new SettingsDao(DatabaseHelper.getInstance(act)));
    }

    private int dp(float v) {
        return Math.round(v * this.act.getResources().getDisplayMetrics().density);
    }

    private static void toast(Activity act, String m) {
        Toast.makeText(act, m, Toast.LENGTH_LONG).show();
    }
}
