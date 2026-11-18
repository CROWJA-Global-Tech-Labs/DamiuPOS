package com.crowja.damiupos.wa;

import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.VideoView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.crowja.damiupos.R;
import com.google.android.material.button.MaterialButton;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 📺 Status tayang — cermin bagian "Status tayang sekarang" di web /wa-status: Status WhatsApp yang MASIH TAYANG
 * (≤ 24 jam) di tiap akun/perangkat cabang, termasuk yang diposting langsung dari HP. Ketuk kartu = penampil layar
 * penuh ala reels (ketuk kiri/kanan, video diputar sampai habis), dan 🗑 menarik Status itu dari WhatsApp
 * (hapus untuk semua penonton). Data dari {@code GET /api/admin/wa/status/live}; media lewat URL bertanda tangan.
 */
public class WaStatusLiveActivity extends AppCompatActivity {

    private static final long SLIDE_MS = 7000;

    private String staffUuid;
    private LinearLayout list;
    private TextView summary;
    private boolean loading;
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        staffUuid = WaAdmin.gate(this);
        if (staffUuid == null) return;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(WaStatusUi.PAGE_BG);
        Toolbar tb = new Toolbar(this);
        tb.setBackgroundColor(WaStatusUi.PRIMARY);
        tb.setTitle("📺 Status tayang");
        tb.setTitleTextColor(Color.WHITE);
        tb.setNavigationIcon(R.drawable.ic_arrow_back);
        root.addView(tb, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, WaStatusUi.dp(this, 56)));
        setSupportActionBar(tb);
        tb.setNavigationOnClickListener(v -> finish());

        summary = WaStatusUi.text(this, "Memuat…", 12, WaStatusUi.TEXT_2, false);
        int p = WaStatusUi.dp(this, 16);
        summary.setPadding(p, WaStatusUi.dp(this, 10), p, WaStatusUi.dp(this, 4));
        root.addView(summary);

        ScrollView sv = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(WaStatusUi.dp(this, 12), 0, WaStatusUi.dp(this, 12), WaStatusUi.dp(this, 24));
        sv.addView(list);
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        load();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 1, 0, "🔄 Muat ulang").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == 1) {
            load();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void load() {
        if (loading) return;
        loading = true;
        summary.setText("Memuat Status yang sedang tayang…");
        new Thread(() -> {
            JSONObject r = null;
            String err = null;
            try {
                r = WaAdmin.api(this).adminWaGet("status/live", null, staffUuid);
            } catch (Exception e) {
                err = WaAdmin.errorMessage(e);
            }
            final JSONObject fr = r;
            final String fe = err;
            runOnUiThread(() -> {
                loading = false;
                if (isFinishing() || isDestroyed()) return;
                if (fr == null) {
                    summary.setText(fe + " Ketuk 🔄 untuk mencoba lagi.");
                    return;
                }
                render(fr);
            });
        }).start();
    }

    private void render(JSONObject r) {
        list.removeAllViews();
        JSONArray accounts = r.optJSONArray("accounts");
        int total = r.optInt("total");
        summary.setText(total == 0 ? "Tidak ada Status yang sedang tayang." : total + " Status sedang tayang · ketuk untuk melihat.");
        if (accounts == null || accounts.length() == 0) {
            list.addView(WaStatusUi.text(this, "Belum ada akun WhatsApp cabang yang tertaut ke Bridge.", 13, WaStatusUi.TEXT_2, false));
            return;
        }
        for (int i = 0; i < accounts.length(); i++) {
            JSONObject a = accounts.optJSONObject(i);
            if (a == null) continue;
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            int pad = WaStatusUi.dp(this, 12);
            box.setPadding(pad, pad, pad, pad);
            box.setBackground(WaStatusUi.box(0xFFFFFFFF, 0, 12, this));
            JSONArray st = a.optJSONArray("statuses");
            int n = st != null ? st.length() : 0;
            String dev = a.optString("device", "");
            box.addView(WaStatusUi.text(this, "📱 " + a.optString("account") + (dev.isEmpty() ? "" : " · " + dev)
                    + (a.optBoolean("ok") ? " · " + n + " tayang" : ""), 14, WaStatusUi.TEXT, true));
            if (!a.optBoolean("ok")) {
                box.addView(WaStatusUi.text(this, "✗ " + a.optString("error", "Gagal membaca Status."), 13, WaStatusUi.RED, false),
                        WaStatusUi.full(WaStatusUi.dp(this, 6)));
            } else if (n == 0) {
                box.addView(WaStatusUi.text(this, "Tidak ada Status yang sedang tayang.", 13, WaStatusUi.TEXT_2, false),
                        WaStatusUi.full(WaStatusUi.dp(this, 6)));
            } else {
                List<JSONObject> items = new ArrayList<>();
                for (int k = 0; k < n; k++) {
                    JSONObject s = st.optJSONObject(k);
                    if (s != null) items.add(s);
                }
                HorizontalScrollView hs = new HorizontalScrollView(this);
                hs.setHorizontalScrollBarEnabled(false);
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                for (int k = 0; k < items.size(); k++) {
                    final int idx = k;
                    View tile = tile(items.get(k));
                    tile.setOnClickListener(v -> openReel(a, items, idx));
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WaStatusUi.dp(this, 96), WaStatusUi.dp(this, 170));
                    lp.rightMargin = WaStatusUi.dp(this, 8);
                    row.addView(tile, lp);
                }
                hs.addView(row);
                box.addView(hs, WaStatusUi.full(WaStatusUi.dp(this, 8)));
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = WaStatusUi.dp(this, 10);
            list.addView(box, lp);
        }
    }

    /** Kartu 9:16: teks di atas warna latarnya, atau thumbnail gambar/video. */
    private View tile(JSONObject s) {
        FrameLayout f = new FrameLayout(this);
        boolean text = "text".equals(s.optString("type"));
        f.setBackground(WaStatusUi.box(text ? parseColor(s.optString("background"), 0xFF128C7E) : 0xFF0F172A, 0, 10, this));
        f.setClipToOutline(true);
        if (text) {
            TextView t = WaStatusUi.text(this, s.optString("text"), 11, Color.WHITE, false);
            t.setGravity(Gravity.CENTER);
            t.setMaxLines(8);
            int p = WaStatusUi.dp(this, 6);
            t.setPadding(p, p, p, p);
            f.addView(t, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            Bitmap thumb = decodeThumb(WaStatusUi.str(s, "thumb"));
            if (thumb != null) iv.setImageBitmap(thumb);
            String media = WaStatusUi.str(s, "media_url");
            if ("image".equals(s.optString("type")) && media != null) WaStatusUi.thumb(iv, media, WaStatusUi.dp(this, 170));
            f.addView(iv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            if ("video".equals(s.optString("type"))) {
                TextView play = WaStatusUi.text(this, "▶", 28, Color.WHITE, true);
                f.addView(play, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
            }
        }
        String foot = WaStatusUi.str(s, "posted_label");
        if (foot != null) {
            TextView t = WaStatusUi.text(this, foot + ("phone".equals(s.optString("source")) ? " · HP" : "")
                    + (s.isNull("views") ? "" : "\n👁 " + s.optInt("views")), 10, Color.WHITE, false);
            t.setBackgroundColor(0x99000000);
            t.setPadding(WaStatusUi.dp(this, 4), WaStatusUi.dp(this, 2), WaStatusUi.dp(this, 4), WaStatusUi.dp(this, 2));
            f.addView(t, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        }
        f.setContentDescription("Status " + s.optString("type") + " " + s.optString("text"));
        return f;
    }

    // ------------------------------------------------------------------ penampil reels

    private void openReel(JSONObject account, List<JSONObject> items, int start) {
        new Reel(account, items, start).show();
    }

    /** Penampil layar penuh: satu Status per layar, ketuk kiri/kanan, otomatis lanjut, 🗑 tarik Status. */
    private class Reel {
        final JSONObject account;
        final List<JSONObject> items;
        int idx;
        final Dialog d;
        final FrameLayout stage;
        final TextView who, meta, caption, count;
        final LinearLayout bars;
        final MaterialButton del, views;
        @Nullable VideoView video;
        final Runnable next = () -> go(1);

        Reel(JSONObject account, List<JSONObject> items, int start) {
            this.account = account;
            this.items = new ArrayList<>(items);
            this.idx = start;
            d = new Dialog(WaStatusLiveActivity.this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
            FrameLayout root = new FrameLayout(WaStatusLiveActivity.this);
            root.setBackgroundColor(Color.BLACK);

            stage = new FrameLayout(WaStatusLiveActivity.this);
            FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            sp.topMargin = WaStatusUi.dp(WaStatusLiveActivity.this, 64);
            sp.bottomMargin = WaStatusUi.dp(WaStatusLiveActivity.this, 110);
            root.addView(stage, sp);

            // Zona ketuk kiri/kanan (di atas stage, di bawah kepala & kaki).
            LinearLayout taps = new LinearLayout(WaStatusLiveActivity.this);
            View left = new View(WaStatusLiveActivity.this);
            View mid = new View(WaStatusLiveActivity.this);
            View right = new View(WaStatusLiveActivity.this);
            left.setOnClickListener(v -> go(-1));
            right.setOnClickListener(v -> go(1));
            left.setContentDescription("Status sebelumnya");
            right.setContentDescription("Status berikutnya");
            taps.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            taps.addView(mid, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 2f));
            taps.addView(right, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            root.addView(taps, sp);

            LinearLayout head = new LinearLayout(WaStatusLiveActivity.this);
            head.setOrientation(LinearLayout.VERTICAL);
            int p = WaStatusUi.dp(WaStatusLiveActivity.this, 12);
            head.setPadding(p, p, p, 0);
            bars = new LinearLayout(WaStatusLiveActivity.this);
            head.addView(bars, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, WaStatusUi.dp(WaStatusLiveActivity.this, 3)));
            LinearLayout line = new LinearLayout(WaStatusLiveActivity.this);
            line.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout texts = new LinearLayout(WaStatusLiveActivity.this);
            texts.setOrientation(LinearLayout.VERTICAL);
            who = WaStatusUi.text(WaStatusLiveActivity.this, "", 14, Color.WHITE, true);
            meta = WaStatusUi.text(WaStatusLiveActivity.this, "", 12, 0xFFCFD8DC, false);
            texts.addView(who);
            texts.addView(meta);
            line.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView close = WaStatusUi.text(WaStatusLiveActivity.this, "✕", 24, Color.WHITE, false);
            close.setPadding(p, 0, 0, 0);
            close.setContentDescription("Tutup");
            close.setOnClickListener(v -> d.dismiss());
            line.addView(close);
            head.addView(line, WaStatusUi.full(WaStatusUi.dp(WaStatusLiveActivity.this, 6)));
            root.addView(head, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

            LinearLayout foot = new LinearLayout(WaStatusLiveActivity.this);
            foot.setOrientation(LinearLayout.VERTICAL);
            foot.setPadding(p, 0, p, p);
            caption = WaStatusUi.text(WaStatusLiveActivity.this, "", 14, Color.WHITE, false);
            caption.setMaxLines(3);
            foot.addView(caption);
            LinearLayout actions = new LinearLayout(WaStatusLiveActivity.this);
            actions.setGravity(Gravity.CENTER_VERTICAL);
            count = WaStatusUi.text(WaStatusLiveActivity.this, "", 12, 0xFFB0BEC5, false);
            actions.addView(count, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            views = new MaterialButton(WaStatusLiveActivity.this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
            views.setAllCaps(false);
            views.setTextColor(Color.WHITE);
            views.setStrokeColor(android.content.res.ColorStateList.valueOf(0x99FFFFFF));
            views.setOnClickListener(v -> showViewers());
            LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            vlp.rightMargin = WaStatusUi.dp(WaStatusLiveActivity.this, 8);
            actions.addView(views, vlp);
            del = new MaterialButton(WaStatusLiveActivity.this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
            del.setText("🗑 Hapus dari WhatsApp");
            del.setAllCaps(false);
            del.setTextColor(0xFFFF8A80);
            del.setStrokeColor(android.content.res.ColorStateList.valueOf(0xFFFF8A80));
            del.setOnClickListener(v -> askDelete());
            actions.addView(del);
            foot.addView(actions, WaStatusUi.full(WaStatusUi.dp(WaStatusLiveActivity.this, 6)));
            root.addView(foot, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

            d.setContentView(root);
            d.setOnDismissListener(x -> {
                main.removeCallbacks(next);
                stage.removeAllViews();
            });
        }

        void show() {
            d.show();
            render();
        }

        void go(int delta) {
            int n = idx + delta;
            if (n < 0) return;
            if (n >= items.size()) {
                d.dismiss();
                return;
            }
            idx = n;
            render();
        }

        void render() {
            main.removeCallbacks(next);
            stage.removeAllViews();
            JSONObject s = items.get(idx);
            bars.removeAllViews();
            for (int i = 0; i < items.size(); i++) {
                View b = new View(WaStatusLiveActivity.this);
                b.setBackgroundColor(i <= idx ? Color.WHITE : 0x55FFFFFF);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
                lp.rightMargin = WaStatusUi.dp(WaStatusLiveActivity.this, 3);
                bars.addView(b, lp);
            }
            String dev = account.optString("device", "");
            who.setText("📱 " + account.optString("account") + (dev.isEmpty() ? "" : " · " + dev));
            meta.setText(WaStatusRules.liveMeta(WaStatusUi.str(s, "posted_label"), WaStatusUi.str(s, "expires_label"),
                    WaStatusUi.str(s, "post_name"), s.optString("source"), s.isNull("audience") ? 0 : s.optInt("audience")));
            count.setText((idx + 1) + " / " + items.size());
            views.setText(WaStatusRules.viewsButton(s.isNull("views") ? null : s.optInt("views")));
            video = null;
            String type = s.optString("type");
            caption.setText("text".equals(type) ? "" : s.optString("text"));
            FrameLayout.LayoutParams full = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER);
            String media = WaStatusUi.str(s, "media_url");
            if ("text".equals(type)) {
                TextView t = WaStatusUi.text(WaStatusLiveActivity.this, s.optString("text"), 22, Color.WHITE, false);
                t.setGravity(Gravity.CENTER);
                int p = WaStatusUi.dp(WaStatusLiveActivity.this, 24);
                t.setPadding(p, p, p, p);
                t.setBackgroundColor(parseColor(s.optString("background"), 0xFF128C7E));
                stage.addView(t, full);
                main.postDelayed(next, SLIDE_MS);
            } else if ("video".equals(type) && media != null) {
                VideoView v = new VideoView(WaStatusLiveActivity.this);
                video = v;
                v.setVideoURI(Uri.parse(media));
                v.setOnPreparedListener(mp -> v.start());
                v.setOnCompletionListener(mp -> go(1));
                v.setOnErrorListener((mp, what, extra) -> {
                    caption.setText("Video tidak bisa diputar. " + s.optString("text"));
                    return true;
                });
                stage.addView(v, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
            } else {
                ImageView iv = new ImageView(WaStatusLiveActivity.this);
                iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                Bitmap thumb = decodeThumb(WaStatusUi.str(s, "thumb"));
                if (thumb != null) iv.setImageBitmap(thumb);
                if (media != null) WaStatusUi.thumb(iv, media, WaStatusUi.dp(WaStatusLiveActivity.this, 720));
                stage.addView(iv, full);
                main.postDelayed(next, SLIDE_MS);
            }
        }

        /** 👁 Penonton: siapa saja yang sudah melihat Status ini, dicocokkan ke pelanggan. Status dijeda selama dibuka. */
        void showViewers() {
            main.removeCallbacks(next);
            if (video != null) video.pause();
            JSONObject s = items.get(idx);
            AlertDialog dlg = new AlertDialog.Builder(WaStatusLiveActivity.this)
                    .setTitle("👁 Penonton")
                    .setMessage("Memuat…")
                    .setPositiveButton("Tutup", null)
                    .setOnDismissListener(x -> resume())
                    .show();
            new Thread(() -> {
                JSONObject r = null;
                String err = null;
                try {
                    java.util.Map<String, String> q = new java.util.HashMap<>();
                    q.put("account", account.optString("account"));
                    q.put("id", s.optString("id"));
                    r = WaAdmin.api(WaStatusLiveActivity.this).adminWaGet("status/live/viewers", q, staffUuid);
                } catch (Exception e) {
                    err = WaAdmin.errorMessage(e);
                }
                final JSONObject fr = r;
                final String fe = err;
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed() || !dlg.isShowing()) return;
                    if (fr == null || !fr.optBoolean("ok")) {
                        dlg.setMessage(fr != null ? WaAdmin.message(fr, "Gagal memuat penonton.") : fe);
                        return;
                    }
                    JSONArray vs = fr.optJSONArray("viewers");
                    int n = vs != null ? vs.length() : 0;
                    dlg.setTitle(WaStatusRules.viewersTitle(fr.optInt("count"), fr.optInt("customers")));
                    if (n == 0) {
                        dlg.setMessage("Belum ada yang melihat Status ini (dihitung sejak Bridge mencatat penonton).");
                        return;
                    }
                    dlg.dismiss();
                    String[] lines = new String[n];
                    for (int i = 0; i < n; i++) {
                        JSONObject v = vs.optJSONObject(i);
                        if (v == null) { lines[i] = ""; continue; }
                        lines[i] = WaStatusRules.viewerLine(v.optString("name"), WaStatusUi.str(v, "phone"),
                                WaStatusUi.str(v, "at_label"), !v.isNull("customer_id"), WaStatusUi.str(v, "wa_name"));
                    }
                    main.removeCallbacks(next);
                    if (video != null) video.pause();
                    new AlertDialog.Builder(WaStatusLiveActivity.this)
                            .setTitle(WaStatusRules.viewersTitle(fr.optInt("count"), fr.optInt("customers")))
                            .setItems(lines, null)
                            .setPositiveButton("Tutup", null)
                            .setOnDismissListener(x -> resume())
                            .show();
                });
            }).start();
        }

        /** Lanjutkan Status setelah dialog penonton ditutup. */
        void resume() {
            if (!d.isShowing()) return;
            main.removeCallbacks(next);
            if (video != null) video.start();
            else main.postDelayed(next, SLIDE_MS);
        }

        void askDelete() {
            main.removeCallbacks(next);
            JSONObject s = items.get(idx);
            new AlertDialog.Builder(WaStatusLiveActivity.this)
                    .setTitle("Hapus Status ini?")
                    .setMessage(WaStatusRules.liveDeleteConfirm(account.optString("account")))
                    .setPositiveButton("Hapus", (x, w) -> delete(s))
                    .setNegativeButton("Batal", (x, w) -> render())
                    .setOnCancelListener(x -> render())
                    .show();
        }

        void delete(JSONObject s) {
            del.setEnabled(false);
            del.setText("Menghapus…");
            new Thread(() -> {
                JSONObject r = null;
                String err = null;
                try {
                    JSONObject b = new JSONObject();
                    b.put("account", account.optString("account"));
                    b.put("id", s.optString("id"));
                    r = WaAdmin.api(WaStatusLiveActivity.this).adminWaPost("status/live/delete", b, staffUuid);
                } catch (Exception e) {
                    err = WaAdmin.errorMessage(e);
                }
                final String msg = r != null ? WaAdmin.message(r, "Status dihapus.") : err;
                final boolean ok = r != null && r.optBoolean("ok");
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    del.setEnabled(true);
                    del.setText("🗑 Hapus dari WhatsApp");
                    new AlertDialog.Builder(WaStatusLiveActivity.this).setMessage(msg).setPositiveButton("OK", null).show();
                    if (!ok) {
                        render();
                        return;
                    }
                    items.remove(idx);
                    load();
                    if (items.isEmpty()) {
                        d.dismiss();
                        return;
                    }
                    if (idx >= items.size()) idx = items.size() - 1;
                    render();
                });
            }).start();
        }
    }

    // ------------------------------------------------------------------ util

    @Nullable
    private static Bitmap decodeThumb(@Nullable String b64) {
        if (b64 == null || b64.isEmpty()) return null;
        try {
            byte[] bytes = Base64.decode(b64, Base64.DEFAULT);
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    private static int parseColor(@Nullable String hex, int fallback) {
        try {
            return hex != null && hex.matches("#[0-9A-Fa-f]{6}") ? Color.parseColor(hex) : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }
}
