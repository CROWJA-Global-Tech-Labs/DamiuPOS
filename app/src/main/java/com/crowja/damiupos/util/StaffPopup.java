package com.crowja.damiupos.util;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.SettingsDao;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * 📢 POPUP LOGIN/LOGOUT STAF (web "Popup Login Staf" / HP Admin → Popup Login Staf). Daftar pesan untuk perangkat ini
 * datang dari /api/me {@code staff_popups} (disimpan {@link SettingsDao#KEY_STAFF_POPUPS}). {@link SettingsDao#setCurrentUser}
 * menandai peristiwa login/logout ({@link SettingsDao#KEY_STAFF_POPUP_PENDING}); layar berikutnya yang tampil
 * (DamiuApplication.onActivityResumed) memanggil {@link #showPending} dan menampilkan pesan yang cocok dengan peristiwa
 * itu dan hari ini. Tombol Tutup baru aktif setelah staf mencentang "Saya mengerti".
 */
public final class StaffPopup {
    public static final String LOGIN = "login";
    public static final String LOGOUT = "logout";

    private static boolean showing;

    private StaffPopup() {
    }

    /** Logika murni (diuji unit): pesan ini tampil untuk {@code event} pada hari {@code today} (0 = Minggu … 6 = Sabtu)? */
    public static boolean matches(boolean onLogin, boolean onLogout, @Nullable int[] days, String event, int today) {
        boolean ev = LOGIN.equals(event) ? onLogin : LOGOUT.equals(event) && onLogout;
        if (!ev) return false;
        if (days == null || days.length == 0) return true;
        for (int d : days) if (d == today) return true;
        return false;
    }

    /** Peristiwa sesi dari {@link SettingsDao#setCurrentUser}: id lama → id baru. */
    public static String eventFor(long oldId, long newId) {
        if (newId > 0 && newId != oldId) return LOGIN;     // login (atau ganti user)
        if (newId <= 0 && oldId > 0) return LOGOUT;        // logout / istirahat / sesi dilepas
        return null;
    }

    /** Tampilkan popup yang tertunda (bila ada) di layar ini. Aman dipanggil di setiap onResume. */
    public static void showPending(Activity a) {
        if (showing || a == null || a.isFinishing()) return;
        SettingsDao dao;
        String event;
        try {
            dao = new SettingsDao(DatabaseHelper.getInstance(a.getApplicationContext()));
            event = dao.get(SettingsDao.KEY_STAFF_POPUP_PENDING, "");
        } catch (Exception e) {
            return;
        }
        if (event == null || event.isEmpty()) return;
        dao.set(SettingsDao.KEY_STAFF_POPUP_PENDING, "");
        List<JSONObject> due = due(dao.get(SettingsDao.KEY_STAFF_POPUPS, "[]"), event);
        if (!due.isEmpty()) show(a, due, 0, event);
    }

    private static List<JSONObject> due(String json, String event) {
        List<JSONObject> out = new ArrayList<>();
        int today = Calendar.getInstance().get(Calendar.DAY_OF_WEEK) - 1;   // Calendar: 1 = Minggu
        try {
            JSONArray arr = new JSONArray(json == null || json.isEmpty() ? "[]" : json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null || !o.optBoolean("is_active", true)) continue;
                JSONArray d = o.optJSONArray("days");
                int[] days = new int[d != null ? d.length() : 0];
                for (int k = 0; k < days.length; k++) days[k] = d.optInt(k, -1);
                if (matches(o.optBoolean("on_login"), o.optBoolean("on_logout"), days, event, today)) out.add(o);
            }
        } catch (Exception ignored) {
            // data rusak → tak ada popup
        }
        return out;
    }

    private static void show(Activity a, List<JSONObject> list, int idx, String event) {
        if (idx >= list.size() || a.isFinishing() || a.isDestroyed()) {
            showing = false;
            return;
        }
        showing = true;
        JSONObject m = list.get(idx);
        float dp = a.getResources().getDisplayMetrics().density;
        int pad = (int) (20 * dp);

        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, (int) (8 * dp), pad, 0);
        TextView body = new TextView(a);
        body.setText(m.optString("body", ""));
        body.setTextSize(16);
        body.setTextIsSelectable(true);
        ScrollView sv = new ScrollView(a);
        sv.addView(body);
        box.addView(sv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        CheckBox ok = new CheckBox(a);
        ok.setText("Saya mengerti");
        ok.setTextSize(16);
        ok.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (12 * dp);
        box.addView(ok, lp);

        String head = LOGIN.equals(event) ? "📢 " : "👋 ";
        String count = list.size() > 1 ? " (" + (idx + 1) + "/" + list.size() + ")" : "";
        AlertDialog dlg = new AlertDialog.Builder(a)
                .setTitle(head + m.optString("title", "Pesan") + count)
                .setView(box)
                .setCancelable(false)
                .setPositiveButton(idx + 1 < list.size() ? "Lanjut" : "Tutup", (d, w) -> show(a, list, idx + 1, event))
                .create();
        dlg.setCanceledOnTouchOutside(false);
        dlg.setOnShowListener(d -> {
            Button btn = dlg.getButton(AlertDialog.BUTTON_POSITIVE);
            btn.setEnabled(false);
            ok.setOnCheckedChangeListener((b, on) -> btn.setEnabled(on));
        });
        try {
            dlg.show();
        } catch (Exception e) {
            showing = false;
        }
    }
}
