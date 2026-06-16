package com.crowja.damiupos;

import android.app.DatePickerDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.Calendar;

import com.crowja.damiupos.db.AttendanceDao;
import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.SettingsDao;
import com.crowja.damiupos.db.UserDao;
import com.crowja.damiupos.model.Attendance;
import com.crowja.damiupos.model.User;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputEditText;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Kelola pengguna aplikasi (kasir/operator) untuk fitur multi user & absensi.
 * Tambah/edit/hapus user, lihat riwayat absensi per user.
 */
public class UserListActivity extends AppCompatActivity {

    private UserDao userDao;
    private AttendanceDao attendanceDao;
    private SettingsDao settingsDao;
    private UserAdapter adapter;
    private TextView tvEmpty;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_user_list);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        DatabaseHelper dbHelper = DatabaseHelper.getInstance(this);
        userDao = new UserDao(dbHelper);
        attendanceDao = new AttendanceDao(dbHelper);
        settingsDao = new SettingsDao(dbHelper);

        tvEmpty = findViewById(R.id.tvEmpty);
        RecyclerView rv = findViewById(R.id.rvUsers);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new UserAdapter();
        rv.setAdapter(adapter);

        findViewById(R.id.fabAddUser).setOnClickListener(v -> showUserForm(null));
        findViewById(R.id.btnExportRekap).setOnClickListener(v -> showRecapExportDialog());
        findViewById(R.id.btnPengaturanAbsensi).setOnClickListener(v ->
                startActivity(new Intent(this, AttendanceSettingsActivity.class)));
    }

    private final SimpleDateFormat sdfDay = new SimpleDateFormat("yyyy-MM-dd", Locale.US);

    /** Dialog pilih rentang tanggal → export rekap absensi XLSX terenkripsi. */
    private void showRecapExportDialog() {
        String[] period = AttendanceRecap.monthlyPeriod(settingsDao.getPayrollCutoffDay());
        final String[] range = {period[0], period[1]};
        float d = getResources().getDisplayMetrics().density;

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * d);
        col.setPadding(pad, (int) (8 * d), pad, 0);

        TextView lbl = new TextView(this);
        lbl.setText("Pilih rentang tanggal rekap:");
        col.addView(lbl);

        final Button btnFrom = new Button(this);
        final Button btnTo = new Button(this);
        btnFrom.setAllCaps(false);
        btnTo.setAllCaps(false);
        btnFrom.setText("Dari: " + range[0]);
        btnTo.setText("Sampai: " + range[1]);
        col.addView(btnFrom);
        col.addView(btnTo);

        btnFrom.setOnClickListener(v -> pickDate(range[0], picked -> {
            range[0] = picked;
            btnFrom.setText("Dari: " + picked);
        }));
        btnTo.setOnClickListener(v -> pickDate(range[1], picked -> {
            range[1] = picked;
            btnTo.setText("Sampai: " + picked);
        }));

        new AlertDialog.Builder(this)
                .setTitle("Export Rekap Absensi")
                .setView(col)
                .setPositiveButton("Export", (dlg, w) -> exportRecap(range[0], range[1]))
                .setNegativeButton("Batal", null)
                .show();
    }

    private interface OnDate { void onPick(String yyyyMMdd); }

    private void pickDate(String current, OnDate cb) {
        Calendar c = Calendar.getInstance();
        try {
            Date d = sdfDay.parse(current);
            if (d != null) c.setTime(d);
        } catch (Exception ignored) {}
        new DatePickerDialog(this, (view, y, m, day) -> {
            Calendar s = Calendar.getInstance();
            s.set(y, m, day);
            cb.onPick(sdfDay.format(s.getTime()));
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show();
    }

    private void exportRecap(String start, String end) {
        if (start.compareTo(end) > 0) {
            Toast.makeText(this, "Tanggal mulai harus sebelum/sama dengan tanggal akhir",
                    Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, "Membuat rekap…", Toast.LENGTH_SHORT).show();
        // Bisa berat (kumpulkan foto + zip) → jalankan di background.
        final String s = start, e = end;
        new Thread(() -> {
            File f = AttendanceRecap.exportRecapZip(this, DatabaseHelper.getInstance(this),
                    s, e, settingsDao.getDailyNormalHours());
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (f == null) {
                    Toast.makeText(this, "Gagal membuat rekap (tidak ada data / error)",
                            Toast.LENGTH_LONG).show();
                    return;
                }
                String sizeMb = String.format(Locale.US, "%.1f MB", f.length() / 1048576.0);
                new AlertDialog.Builder(this)
                        .setTitle("Rekap dibuat ✓")
                        .setMessage("File ZIP (data XLSX + foto, " + sizeMb + "):\n" + f.getName()
                                + "\n\nBagikan sekarang?")
                        .setPositiveButton("Bagikan", (d, w) -> shareRecap(f))
                        .setNegativeButton("Tutup", null)
                        .show();
            });
        }, "recap-export").start();
    }

    /** Bagikan file rekap ZIP (data + foto) lewat chooser sistem. */
    private void shareRecap(File f) {
        try {
            Uri uri = FileProvider.getUriForFile(this,
                    getPackageName() + ".fileprovider", f);
            String mime = "application/zip";
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType(mime);
            i.putExtra(Intent.EXTRA_STREAM, uri);
            i.setClipData(new android.content.ClipData(
                    "Rekap Absensi", new String[]{mime},
                    new android.content.ClipData.Item(uri)));
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, "Bagikan Rekap Absensi"));
        } catch (Exception e) {
            Toast.makeText(this, "Gagal membagikan file: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        List<User> users = userDao.getAll();
        adapter.setData(users);
        tvEmpty.setVisibility(users.isEmpty() ? View.VISIBLE : View.GONE);
    }

    /** Form tambah/edit user. {@code existing} null = tambah baru. */
    private void showUserForm(User existing) {
        View content = LayoutInflater.from(this).inflate(R.layout.dialog_user_form, null, false);
        TextInputEditText etName = content.findViewById(R.id.etUserName);
        TextInputEditText etPin = content.findViewById(R.id.etUserPin);
        RadioGroup rgRole = content.findViewById(R.id.rgUserRole);
        RadioButton rbAdmin = content.findViewById(R.id.rbRoleAdmin);
        RadioButton rbStaf = content.findViewById(R.id.rbRoleStaf);
        RadioButton rbViewer = content.findViewById(R.id.rbRoleViewer);
        RadioButton rbMarketing = content.findViewById(R.id.rbRoleMarketing);
        SwitchMaterial swActive = content.findViewById(R.id.swUserActive);

        if (existing != null) {
            etName.setText(existing.getName());
            etPin.setText(existing.getPin());
            if (existing.isAdmin()) rbAdmin.setChecked(true);
            else if (existing.isViewer()) rbViewer.setChecked(true);
            else if (existing.isMarketing()) rbMarketing.setChecked(true);
            else rbStaf.setChecked(true);
            swActive.setChecked(existing.isActive());
        }

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(existing == null ? "Tambah Pengguna" : "Edit Pengguna")
                .setView(content)
                .setPositiveButton(existing == null ? "Tambah" : "Simpan", null)
                .setNegativeButton("Batal", null)
                .create();

        if (existing != null) {
            dialog.setButton(AlertDialog.BUTTON_NEUTRAL, "Hapus",
                    (d, w) -> confirmDelete(existing));
        }

        dialog.setOnShowListener(dlg -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String name = etName.getText() != null ? etName.getText().toString().trim() : "";
                    String pin = etPin.getText() != null ? etPin.getText().toString().trim() : "";
                    if (name.isEmpty()) { etName.setError("Nama wajib diisi"); return; }
                    if (pin.length() < 4) { etPin.setError("PIN minimal 4 digit"); return; }

                    String role = rbAdmin.isChecked() ? User.ROLE_ADMIN
                            : rbViewer.isChecked() ? User.ROLE_VIEWER
                            : rbMarketing.isChecked() ? User.ROLE_MARKETING
                            : User.ROLE_STAF;
                    boolean active = swActive.isChecked();
                    if (existing == null) {
                        User u = new User();
                        u.setName(name);
                        u.setPin(pin);
                        u.setRole(role);
                        u.setActive(active);
                        userDao.insert(u);
                    } else {
                        existing.setName(name);
                        existing.setPin(pin);
                        existing.setRole(role);
                        existing.setActive(active);
                        userDao.update(existing);
                    }
                    dialog.dismiss();
                    refresh();
                }));
        dialog.show();
    }

    private void confirmDelete(User u) {
        new AlertDialog.Builder(this)
                .setTitle("Hapus pengguna?")
                .setMessage("Hapus \"" + u.getName() + "\" beserta riwayat absensinya? "
                        + "Tindakan ini tidak bisa dibatalkan.")
                .setPositiveButton("Hapus", (d, w) -> {
                    // Cegah hapus user yang sedang login.
                    if (settingsDao.getCurrentUserId() == u.getId()) {
                        settingsDao.clearCurrentUser();
                    }
                    userDao.delete(u.getId());
                    refresh();
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    /** Riwayat absensi 50 event terakhir untuk satu user. */
    private void showHistory(User u) {
        List<Attendance> events = attendanceDao.getEventsByUser(u.getId(), 50);
        SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        SimpleDateFormat out = new SimpleDateFormat("EEE, dd MMM • HH:mm", new Locale("id", "ID"));

        StringBuilder sb = new StringBuilder();
        if (events.isEmpty()) {
            sb.append("Belum ada riwayat absensi.");
        } else {
            for (Attendance a : events) {
                String label;
                switch (a.getEvent()) {
                    case Attendance.EVENT_IN:    label = "🟢 Clock In"; break;
                    case Attendance.EVENT_BREAK: label = "⏸ Istirahat"; break;
                    case Attendance.EVENT_OUT:   label = "🔴 Pulang"; break;
                    default: label = a.getEvent();
                }
                String when = a.getTs();
                try {
                    Date d = in.parse(a.getTs());
                    if (d != null) when = out.format(d);
                } catch (Exception ignored) {}
                sb.append(label).append("\n    ").append(when).append("\n");
            }
        }

        new AlertDialog.Builder(this)
                .setTitle("Riwayat — " + u.getName())
                .setMessage(sb.toString())
                .setPositiveButton("Tutup", null)
                .show();
    }

    // ------------------------------------------------------------------ adapter

    private class UserAdapter extends RecyclerView.Adapter<UserAdapter.VH> {
        private List<User> data = new java.util.ArrayList<>();

        void setData(List<User> list) { this.data = list; notifyDataSetChanged(); }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_user, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            User u = data.get(position);
            h.tvName.setText(u.getName());
            String name = u.getName();
            h.tvInitial.setText(name != null && !name.isEmpty()
                    ? String.valueOf(name.charAt(0)).toUpperCase(Locale.getDefault()) : "?");

            String role = u.isAdmin() ? "Admin" : u.isViewer() ? "Viewer"
                    : u.isMarketing() ? "Marketing" : "Staf";
            if (!u.isActive()) role += " • Nonaktif";
            if (settingsDao.getCurrentUserId() == u.getId()) role += " • Sedang login";
            h.tvRole.setText(role);

            h.itemView.setOnClickListener(v -> showUserForm(u));
            h.btnHistory.setOnClickListener(v -> showHistory(u));
            h.btnSalary.setOnClickListener(v -> {
                Intent i = new Intent(UserListActivity.this, SalaryConfigActivity.class);
                i.putExtra(SalaryConfigActivity.EXTRA_USER_ID, u.getId());
                i.putExtra(SalaryConfigActivity.EXTRA_USER_NAME, u.getName());
                startActivity(i);
            });
        }

        @Override
        public int getItemCount() { return data.size(); }

        class VH extends RecyclerView.ViewHolder {
            final TextView tvName, tvRole, tvInitial;
            final android.widget.ImageButton btnHistory, btnSalary;
            VH(@NonNull View v) {
                super(v);
                tvName = v.findViewById(R.id.tvUserName);
                tvRole = v.findViewById(R.id.tvUserRole);
                tvInitial = v.findViewById(R.id.tvUserInitial);
                btnHistory = v.findViewById(R.id.btnHistory);
                btnSalary = v.findViewById(R.id.btnSalary);
            }
        }
    }
}
