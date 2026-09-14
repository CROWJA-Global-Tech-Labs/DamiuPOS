package com.crowja.damiupos.wa;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;
import android.widget.Toast;

import com.crowja.damiupos.CustomerFormActivity;
import com.crowja.damiupos.TransactionActivity;
import com.crowja.damiupos.db.CustomerDao;
import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.model.Customer;

import java.util.List;

/**
 * Tombol melayang "+ Trx" — overlay yang SELALU tampil di atas layar (ditumpangkan pada
 * {@link WaAutoSendService} sehingga cukup SATU izin Aksesibilitas untuk auto-kirim struk
 * sekaligus tombol ini; toggle terpisah di Pengaturan).
 *
 * <p>Perilaku tap:
 * <ul>
 *   <li><b>Di layar chat WhatsApp</b> — baca judul chat (kontak tersimpan = NAMA; belum
 *       tersimpan = NOMOR telp), cocokkan ke pelanggan lokal (nomor kanonik dulu, lalu nama),
 *       dan buka Transaksi Baru dengan pelanggan itu terpilih. Tidak ketemu → buka form
 *       Tambah Pelanggan dengan nomor/nama terisi.</li>
 *   <li><b>Di DAMIU POS sendiri</b> — tap diabaikan (agar tak menumpuk layar / mengganggu input
 *       saat sedang mengetik transaksi).</li>
 *   <li><b>Di layar lain</b> — buka Transaksi Baru biasa (pintasan umum).</li>
 * </ul>
 *
 * <p>Cakupan baca: {@code android:packageNames} pada konfigurasi hanya membatasi pengiriman EVENT
 * (dipakai auto-kirim struk), BUKAN {@link AccessibilityService#getRootInActiveWindow()}. Jadi saat
 * ditap, bubble memeriksa PAKET aplikasi yang sedang di depan; isi layar hanya dibaca bila itu
 * WhatsApp, dan hanya JUDUL chat-nya (nama/nomor) — bukan isi percakapan, bukan aplikasi lain.</p>
 *
 * <p>Bubble bisa digeser (drag) dan posisinya diingat. Tap vs drag dibedakan lewat ambang
 * geser {@link #dragSlopPx}.</p>
 */
public class TrxBubble {

    /** Prefs yang sama dgn WaAutoSendService — semua toggle asisten WA satu tempat. */
    private static final String PREFS = "damiu_wa";
    private static final String KEY_ENABLED = "trx_bubble_enabled";
    private static final String KEY_X = "trx_bubble_x";
    private static final String KEY_Y = "trx_bubble_y";

    public static boolean isEnabled(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false);
    }

    public static void setEnabled(Context c, boolean v) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, v).apply();
    }

    private final AccessibilityService service;
    private final WindowManager wm;
    private TextView view;
    private WindowManager.LayoutParams lp;
    private final int dragSlopPx;

    TrxBubble(AccessibilityService service) {
        this.service = service;
        this.wm = (WindowManager) service.getSystemService(Context.WINDOW_SERVICE);
        this.dragSlopPx = dp(6);
    }

    /** Tampilkan/sembunyikan sesuai toggle Pengaturan — dipanggil saat service connect dan
     *  tiap toggle diubah ({@link WaAutoSendService#refreshBubble()}). */
    void refresh() {
        if (isEnabled(service)) show(); else hide();
    }

    void detach() {
        hide();
    }

    private void show() {
        if (view != null || wm == null) return;
        TextView tv = new TextView(service);
        tv.setText("+ Trx");
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(14);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setPadding(dp(14), dp(9), dp(14), dp(9));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF1565C0);          // biru brand — kontras di atas WA yang hijau
        bg.setCornerRadius(dp(24));
        tv.setBackground(bg);
        tv.setElevation(dp(4));

        SharedPreferences p = service.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int screenW = service.getResources().getDisplayMetrics().widthPixels;
        int screenH = service.getResources().getDisplayMetrics().heightPixels;
        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        // Clamp ke layar SAAT INI: posisi tersimpan (px) bisa terdampar di luar layar setelah rotasi
        // atau pindah HP resolusi lain → WindowManager tak auto-clamp, bubble jadi hilang & tak bisa
        // ditap. Batasi ke [0, layar − ukuran perkiraan] supaya selalu terlihat.
        lp.x = clamp(p.getInt(KEY_X, screenW - dp(88)), 0, screenW - dp(72));
        lp.y = clamp(p.getInt(KEY_Y, (int) (screenH * 0.55f)), 0, screenH - dp(56));
        tv.setOnTouchListener(new DragTapListener());
        try {
            wm.addView(tv, lp);
            view = tv;
        } catch (Exception ignored) {
            view = null;   // overlay ditolak sistem — jangan crash service
        }
    }

    private void hide() {
        if (view == null) return;
        try { wm.removeView(view); } catch (Exception ignored) { }
        view = null;
    }

    /** Drag memindahkan bubble (posisi diingat); lepas tanpa geser berarti = tap. */
    private class DragTapListener implements View.OnTouchListener {
        private int startX, startY;
        private float downRawX, downRawY;
        private boolean dragged;

        @Override public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startX = lp.x; startY = lp.y;
                    downRawX = e.getRawX(); downRawY = e.getRawY();
                    dragged = false;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    int dx = (int) (e.getRawX() - downRawX), dy = (int) (e.getRawY() - downRawY);
                    if (Math.abs(dx) > dragSlopPx || Math.abs(dy) > dragSlopPx) dragged = true;
                    if (dragged) {
                        // Tahan dalam layar → tak bisa digeser keluar (sliver 1px / hilang di tepi).
                        int maxX = service.getResources().getDisplayMetrics().widthPixels - v.getWidth();
                        int maxY = service.getResources().getDisplayMetrics().heightPixels - v.getHeight();
                        lp.x = clamp(startX + dx, 0, maxX);
                        lp.y = clamp(startY + dy, 0, maxY);
                        try { wm.updateViewLayout(view, lp); } catch (Exception ignored) { }
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    if (dragged) {
                        service.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                                .putInt(KEY_X, lp.x).putInt(KEY_Y, lp.y).apply();
                    } else {
                        v.performClick();
                        onTap();
                    }
                    return true;
            }
            return false;
        }
    }

    private void onTap() {
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        String pkg = (root != null && root.getPackageName() != null)
                ? root.getPackageName().toString() : null;

        // DAMIU POS sendiri di depan (mis. sedang mengetik transaksi) → abaikan tap: jangan menumpuk
        // layar / mengganggu input. (Bubble melayang di atas SEMUA app termasuk app kita.)
        if (service.getPackageName().equals(pkg)) return;

        boolean isWa = "com.whatsapp".equals(pkg) || "com.whatsapp.w4b".equals(pkg);
        String title = isWa ? waChatTitle(root, pkg) : null;

        // Bukan layar percakapan? Coba layar DETAIL KONTAK WA ("Info kontak" menampilkan nomor HP
        // asli — kunci paling akurat, bahkan untuk kontak tersimpan yang judul chat-nya cuma nama).
        String contactPhone = null;
        String contactName = null;
        if (isWa && (title == null || title.trim().isEmpty()) && root != null) {
            contactPhone = waContactInfoPhone(root, pkg);
            if (contactPhone != null) {
                contactName = waContactInfoName(root, pkg);
                title = contactPhone;
            }
        }
        if (title == null || title.trim().isEmpty()) {
            // Bukan layar chat/kontak WA (layar lain / daftar chat / root sempat null) → pintasan Transaksi Baru.
            launch(new Intent(service, TransactionActivity.class));
            return;
        }
        title = title.trim();

        CustomerDao dao = new CustomerDao(DatabaseHelper.getInstance(service));
        if (looksLikePhone(title)) {
            Customer c = dao.findByPhoneCanonical(title);   // nomor = kunci unik → satu orang
            if (c != null) openTrxFor(c);
            else openNewCustomer(title, true, contactName);   // dari Info Kontak → nama ikut terisi
            return;
        }

        // Nama bisa cocok ke BEBERAPA orang berbeda (nama sama, nomor beda) di DB branch-wide.
        // Kelompokkan per identitas (dedupKey); tiap orang diwakili salinan is_mine (lalu yg bernomor).
        java.util.LinkedHashMap<String, Customer> people = new java.util.LinkedHashMap<>();
        for (Customer cand : dao.findAllByNameNormalized(title)) {
            String k = CustomerDao.dedupKey(cand);
            Customer cur = people.get(k);
            if (cur == null || preferOver(cand, cur)) people.put(k, cand);
        }
        if (people.isEmpty()) {
            openNewCustomer(title, false);
        } else if (people.size() == 1) {
            openTrxFor(people.values().iterator().next());
        } else {
            // Ambigu: >1 orang berbeda bernama sama → jangan menebak (galon/Sisa Pinjam bisa nyasar
            // ke orang lain). Buka Transaksi Baru dgn hint nama → picker terbuka, staf pilih yg benar.
            Toast.makeText(service, "Beberapa pelanggan bernama \"" + title + "\" — pilih yang benar.",
                    Toast.LENGTH_LONG).show();
            launch(new Intent(service, TransactionActivity.class)
                    .putExtra(TransactionActivity.EXTRA_INBOX_SENDER_NAME, title));
        }
    }

    /** Wakil per-orang: utamakan salinan MILIK perangkat ini (transaksi lokal menempel di situ),
     *  lalu yang punya nomor telp. Selaras aturan wakil dedupeForDisplay. */
    private static boolean preferOver(Customer cand, Customer cur) {
        if (cand.isMine() != cur.isMine()) return cand.isMine();
        boolean candPhone = cand.getPhone() != null && !cand.getPhone().trim().isEmpty();
        boolean curPhone = cur.getPhone() != null && !cur.getPhone().trim().isEmpty();
        return candPhone && !curPhone;
    }

    private void openTrxFor(Customer c) {
        boolean hasPhone = c.getPhone() != null && !c.getPhone().trim().isEmpty();
        // Nomor ditampilkan di toast → staf bisa menyadari bila ternyata orang yang keliru.
        Toast.makeText(service, "Transaksi untuk " + c.getName()
                + (hasPhone ? " (" + c.getPhone() + ")" : ""), Toast.LENGTH_SHORT).show();
        launch(new Intent(service, TransactionActivity.class).putExtra("customer_id", c.getId()));
    }

    private void openNewCustomer(String title, boolean isPhone) {
        openNewCustomer(title, isPhone, null);
    }

    /** {@code extraName}: nama kontak dari layar Info Kontak WA (bila ada) — ikut prefill
     *  supaya form pelanggan baru tinggal disimpan. */
    private void openNewCustomer(String title, boolean isPhone, String extraName) {
        Toast.makeText(service, "Pelanggan \"" + title + "\" belum terdaftar — lengkapi lalu simpan.",
                Toast.LENGTH_LONG).show();
        Intent i = new Intent(service, CustomerFormActivity.class);
        if (isPhone) i.putExtra(CustomerFormActivity.EXTRA_PREFILL_PHONE, toLocalPhone(title));
        else i.putExtra(CustomerFormActivity.EXTRA_PREFILL_NAME, title);
        if (extraName != null && !extraName.trim().isEmpty()
                && !looksLikePhone(extraName)) {
            i.putExtra(CustomerFormActivity.EXTRA_PREFILL_NAME, extraName.trim());
        }
        launch(i);
    }

    /** Judul toolbar chat WA (nama kontak / nomor). Null bila bukan layar percakapan. */
    private String waChatTitle(AccessibilityNodeInfo root, String pkg) {
        List<AccessibilityNodeInfo> ns =
                root.findAccessibilityNodeInfosByViewId(pkg + ":id/conversation_contact_name");
        if (ns != null) {
            for (AccessibilityNodeInfo n : ns) {
                if (n != null && n.getText() != null && n.isVisibleToUser()) {
                    return n.getText().toString();
                }
            }
        }
        return null;
    }

    /**
     * Nomor HP dari layar DETAIL KONTAK WA ("Info kontak" / profil bisnis — menampilkan nomor
     * asli). Coba view-id resmi dulu; id WA berubah antar versi, jadi fallback = pindai teks
     * generik TAPI hanya bila layar terverifikasi layar kontak (ada label "Seluler"/"Mobile"/dst)
     * — tanpa jangkar itu, daftar chat dengan judul bernomor bisa salah tertangkap.
     * Null bila bukan layar kontak / tak ada nomor.
     */
    private String waContactInfoPhone(AccessibilityNodeInfo root, String pkg) {
        for (String id : new String[]{pkg + ":id/title_tv", pkg + ":id/contact_info_phone",
                pkg + ":id/phone_number", pkg + ":id/contact_subtitle"}) {
            List<AccessibilityNodeInfo> ns = root.findAccessibilityNodeInfosByViewId(id);
            if (ns == null) continue;
            for (AccessibilityNodeInfo n : ns) {
                if (n != null && n.getText() != null && n.isVisibleToUser()
                        && looksLikePhone(n.getText().toString())
                        && phoneDigitsOk(n.getText().toString())) {
                    return n.getText().toString().trim();
                }
            }
        }
        if (!hasContactInfoMarker(root, 0)) return null;
        return scanForPhone(root, 0);
    }

    /** Nama kontak pada layar Info Kontak (id resmi; null bila tak ketemu — nomor tetap dipakai). */
    private String waContactInfoName(AccessibilityNodeInfo root, String pkg) {
        for (String id : new String[]{pkg + ":id/contact_title", pkg + ":id/business_title"}) {
            List<AccessibilityNodeInfo> ns = root.findAccessibilityNodeInfosByViewId(id);
            if (ns == null) continue;
            for (AccessibilityNodeInfo n : ns) {
                if (n != null && n.getText() != null && n.isVisibleToUser()) {
                    String s = n.getText().toString().trim();
                    if (!s.isEmpty() && !looksLikePhone(s)) return s;
                }
            }
        }
        return null;
    }

    /** Label tipe nomor pada layar Info Kontak (ID/EN) — jangkar bahwa ini benar layar kontak. */
    private static boolean hasContactInfoMarker(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 14) return false;
        CharSequence t = node.getText();
        if (t != null) {
            String s = t.toString().trim().toLowerCase(java.util.Locale.ROOT);
            if (s.equals("seluler") || s.equals("ponsel") || s.equals("telepon")
                    || s.equals("mobile") || s.equals("phone") || s.equals("whatsapp")) {
                return true;
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            if (hasContactInfoMarker(node.getChild(i), depth + 1)) return true;
        }
        return false;
    }

    /** Pindai teks tampak pertama yang berbentuk nomor telp Indonesia. */
    private static String scanForPhone(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 14) return null;
        CharSequence t = node.getText();
        if (t != null && node.isVisibleToUser()) {
            String s = t.toString().trim();
            if (looksLikePhone(s) && phoneDigitsOk(s)) return s;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            String r = scanForPhone(node.getChild(i), depth + 1);
            if (r != null) return r;
        }
        return null;
    }

    /** ≥9 digit dan berawalan nomor Indonesia (0 / 62 / 8) — menolak tanggal "2026-07-17" dkk. */
    private static boolean phoneDigitsOk(String s) {
        String d = s == null ? "" : s.replaceAll("\\D+", "");
        if (d.length() < 9) return false;
        return d.startsWith("0") || d.startsWith("62") || d.startsWith("8");
    }

    /** Judul chat berupa nomor telp? (WA menampilkan "+62 812-3456-7890" untuk kontak belum
     *  tersimpan.) Hanya digit/+/pemisah umum dan ≥ 8 digit. */
    static boolean looksLikePhone(String s) {
        if (s == null || !s.matches("[+0-9 ()\\[\\].\\-–]+")) return false;
        return s.replaceAll("\\D+", "").length() >= 8;
    }

    /** "+62 812-…" → "0812…" — format lokal yang lolos guard form pelanggan baru (08, digit, ≤13). */
    static String toLocalPhone(String s) {
        String d = s == null ? "" : s.replaceAll("\\D+", "");
        if (d.startsWith("62")) return "0" + d.substring(2);
        if (d.startsWith("8")) return "0" + d;
        return d;
    }

    private void launch(Intent i) {
        // NEW_TASK saja (tanpa CLEAR_TOP): instans baru Transaksi/Form DITUMPUK di atas back-stack
        // (activity standard → extras tetap sampai ke onCreate). Draft yang sedang dibuka TIDAK
        // dihancurkan — input lama aman & bisa dikembalikan lewat Back. (CLEAR_TOP dulu memang
        // menerima extras, tapi ikut menghapus draft transaksi yang belum disimpan.)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            service.startActivity(i);
        } catch (Exception e) {
            // Sebagian skin OEM (mis. MIUI) mensyaratkan izin "pop-up latar belakang" → beri jejak
            // + arahan, jangan diam-diam gagal (staf hanya lihat toast "Transaksi untuk…" tanpa layar).
            android.util.Log.w("TrxBubble", "startActivity ditolak sistem", e);
            Toast.makeText(service,
                    "Tidak bisa membuka DAMIU POS — aktifkan izin autostart / pop-up latar belakang di pengaturan HP.",
                    Toast.LENGTH_LONG).show();
        }
    }

    private int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(v, Math.max(lo, hi)));
    }

    private int dp(float v) {
        return Math.round(v * service.getResources().getDisplayMetrics().density);
    }
}
