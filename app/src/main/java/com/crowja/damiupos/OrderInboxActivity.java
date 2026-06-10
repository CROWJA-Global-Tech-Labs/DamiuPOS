package com.crowja.damiupos;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.ContactsContract;
import androidx.core.content.ContextCompat;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.crowja.damiupos.db.CustomerDao;
import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.OrderInboxDao;
import com.crowja.damiupos.db.SettingsDao;
import com.crowja.damiupos.model.Customer;
import com.crowja.damiupos.model.OrderInbox;
import com.crowja.damiupos.wa.OrderAlertService;
import com.crowja.damiupos.wa.ParsedOrder;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

import java.util.ArrayList;
import java.util.List;

/**
 * Daftar pesanan yang dideteksi dari notifikasi WhatsApp.
 * User bisa "Buat Transaksi" (akan launch TransactionActivity dengan
 * customer + item pre-fill best-effort) atau "Tolak" (delete row).
 */
public class OrderInboxActivity extends AppCompatActivity {

    /** Set true di intent extra → buka langsung view Arsip. */
    public static final String EXTRA_SHOW_ARCHIVE = "show_archive";

    private RecyclerView rv;
    private TextView tvEmpty;
    private OrderInboxDao dao;
    private SettingsDao settingsDao;
    private CustomerDao customerDao;
    private final List<OrderInbox> data = new ArrayList<>();
    private final InboxAdapter adapter = new InboxAdapter();
    /** Toggle: false = inbox aktif, true = view arsip. */
    private boolean showArchive = false;
    private Toolbar toolbar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_order_inbox);
        toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        // Mode awal: kalau caller pass EXTRA_SHOW_ARCHIVE=true, mulai
        // di view arsip; default: inbox aktif.
        showArchive = getIntent() != null
                && getIntent().getBooleanExtra(EXTRA_SHOW_ARCHIVE, false);

        DatabaseHelper db = DatabaseHelper.getInstance(this);
        dao = new OrderInboxDao(db);
        settingsDao = new SettingsDao(db);
        customerDao = new CustomerDao(db);
        rv = findViewById(R.id.rvInbox);
        tvEmpty = findViewById(R.id.tvEmpty);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(adapter);

        reload();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Auto-arsip item APPROVED/REJECTED yang lebih lama dari threshold
        // user (default 24 jam). Dilakukan saat buka Inbox supaya item
        // lama tidak menumpuk di inbox aktif.
        int hours = settingsDao.getWaAutoArchiveHours();
        if (hours > 0) {
            int n = dao.autoArchiveOld(hours);
            if (n > 0) {
                Toast.makeText(this, n + " pesanan lama dipindah ke arsip",
                        Toast.LENGTH_SHORT).show();
            }
        }
        reload();
    }

    private void reload() {
        data.clear();
        if (showArchive) {
            // View arsip
            data.addAll(dao.getArchived());
        } else {
            // Inbox aktif: PENDING dulu, lalu APPROVED/REJECTED yang belum
            // diarsipkan
            data.addAll(dao.getPending());
            for (OrderInbox o : dao.getActive()) {
                if (!OrderInbox.STATUS_PENDING.equals(o.getStatus())) data.add(o);
            }
        }
        adapter.notifyDataSetChanged();
        tvEmpty.setVisibility(data.isEmpty() ? View.VISIBLE : View.GONE);
        if (data.isEmpty()) {
            tvEmpty.setText(showArchive
                    ? "Arsip kosong."
                    : "Belum ada pesanan masuk dari WhatsApp.\n\nPastikan fitur ini sudah di-enable di Pengaturan dan Notification Access aktif.");
        }
        // Update title supaya jelas mode mana
        if (toolbar != null) {
            toolbar.setTitle(showArchive ? "Arsip Pesanan WA" : "Pesanan dari WhatsApp");
        }
    }

    // Tidak ada menu di toolbar lagi — entry point Arsip dipindah ke
    // Settings → "Arsip Pesanan WhatsApp". Inbox activity ini akan
    // tampil dalam mode 'active' atau 'archive' tergantung intent extra
    // yang dikirim caller.

    /**
     * Pindahkan item ke arsip (tidak benar2 dihapus, masih bisa
     * dilihat di view "Arsip"). Hanya boleh untuk item APPROVED/REJECTED.
     */
    private void archive(OrderInbox o) {
        dao.updateStatus(o.getId(), OrderInbox.STATUS_ARCHIVED, o.getTrxId());
        reload();
        Toast.makeText(this, "Dipindah ke arsip", Toast.LENGTH_SHORT).show();
    }

    /** Hapus permanen dari DB — hanya dari view Arsip. */
    private void confirmDeletePermanent(OrderInbox o) {
        new AlertDialog.Builder(this)
                .setTitle("Hapus Permanen")
                .setMessage("Hapus pesanan dari " + safe(o.getSenderName())
                        + " secara permanen? Tindakan ini tidak bisa dibatalkan.")
                .setPositiveButton("Hapus", (d, w) -> {
                    dao.delete(o.getId());
                    reload();
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    /**
     * "Balas" — buka chat WhatsApp pelanggan dengan template balasan
     * sudah ter-isi di kolom input WA.
     *
     * <p>WA notifikasi TIDAK memberikan nomor pengirim — hanya display name.
     * Kalau kontak "My Dj" di HP user punya 3 nomor berbeda, kita tidak tahu
     * WA ini dari nomor yang mana. Maka: kumpulkan SEMUA kandidat dari semua
     * sumber, dedup canonical, lalu tampilkan picker kalau >1 kandidat.
     * User wajib pilih manual yang sesuai dengan chat WA yang dibalas.
     */
    private void reply(OrderInbox o) {
        String template = settingsDao.getWaReplyTemplate();

        // Selalu siapkan clipboard sebagai backup
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("Balasan", template));
        }

        // Kumpulkan SEMUA kandidat nomor dari semua tier
        java.util.List<String> candidates = collectAllPhoneCandidates(o);

        if (candidates.isEmpty()) {
            // Tidak ada kandidat apa pun → minta user pilih pelanggan manual
            promptPickCustomerForReply(o);
            return;
        }
        if (candidates.size() == 1) {
            // Hanya 1 kandidat → confirm dialog langsung (user masih bisa
            // edit nomor kalau misalnya salah, lihat confirmPhoneAndLaunch)
            confirmPhoneAndLaunch(o, candidates.get(0), template);
            return;
        }
        // >1 kandidat (mis. kontak "My Dj" punya 3 nomor) → tampilkan picker.
        // User pilih yang benar untuk balas chat WA yang dimaksud.
        pickContactPhone(o, template, candidates);
    }

    /**
     * Kumpulkan semua nomor kandidat untuk sender ini dari:
     * <ol>
     *     <li>{@code sender_phone} di OrderInbox</li>
     *     <li>Phone pelanggan di DB DAMIU POS via {@code customer_id}</li>
     *     <li>SEMUA nomor kontak Android yg match nama pengirim</li>
     * </ol>
     * Dedup pakai canonical digit form supaya nomor yg sama dlm format
     * berbeda tidak muncul dobel (mis. "0812..." dan "+62 812...").
     */
    private java.util.List<String> collectAllPhoneCandidates(OrderInbox o) {
        java.util.LinkedHashMap<String, String> byNormalized =
                new java.util.LinkedHashMap<>();

        // Tier 1: sender_phone di inbox
        addCandidate(byNormalized, o.getSenderPhone());

        // Tier 2: phone pelanggan via customer_id
        if (o.getCustomerId() > 0 && customerDao != null) {
            Customer c = customerDao.getById(o.getCustomerId());
            if (c != null) addCandidate(byNormalized, c.getPhone());
        }

        // Tier 3: SEMUA nomor dari Android Contacts yg match nama sender
        for (String p : lookupContactPhonesByName(o.getSenderName())) {
            addCandidate(byNormalized, p);
        }

        return new java.util.ArrayList<>(byNormalized.values());
    }

    /** Tambahkan phone ke map kalau canonical-digit form-nya belum ada. */
    private static void addCandidate(java.util.LinkedHashMap<String, String> map,
                                     String phone) {
        if (phone == null || phone.trim().isEmpty()) return;
        String norm = phone.replaceAll("[^0-9]", "");
        if (norm.startsWith("0")) norm = "62" + norm.substring(1);
        else if (!norm.startsWith("62")) norm = "62" + norm;
        if (norm.length() < 8) return;
        if (!map.containsKey(norm)) map.put(norm, phone.trim());
    }

    /**
     * Tampilkan konfirmasi dengan field editable. User bisa:
     * <ul>
     *     <li>Edit nomor langsung di field (kalau hasil resolve salah)</li>
     *     <li>Lanjut buka chat dgn nomor yang sudah benar</li>
     *     <li>Pilih pelanggan lain dari DB (kalau ingin re-link customer_id)</li>
     *     <li>Batal</li>
     * </ul>
     * Setelah user edit + lanjut, nomor disimpan ke {@code sender_phone}
     * supaya next time tier 1 langsung return nomor yang benar.
     */
    private void confirmPhoneAndLaunch(OrderInbox o, String phone, String template) {
        // Layout: header + EditText pre-filled
        android.widget.LinearLayout wrap = new android.widget.LinearLayout(this);
        wrap.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        wrap.setPadding(pad, pad, pad, 0);

        TextView header = new TextView(this);
        header.setText("Pengirim: " + safe(o.getSenderName())
                + "\nPeriksa & edit nomor jika salah:");
        header.setTextSize(13);
        header.setTextColor(getResources().getColor(R.color.text_secondary));
        wrap.addView(header);

        final android.widget.EditText etPhone = new android.widget.EditText(this);
        etPhone.setText(formatPhoneDisplay(phone));
        etPhone.setInputType(android.text.InputType.TYPE_CLASS_PHONE);
        etPhone.setHint("Contoh: 0812-3456-7890");
        wrap.addView(etPhone, new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(this)
                .setTitle("Konfirmasi Nomor Balasan")
                .setView(wrap)
                .setPositiveButton("Buka Chat WA", (d, w) -> {
                    String userPhone = etPhone.getText() != null
                            ? etPhone.getText().toString().trim() : "";
                    if (userPhone.isEmpty()) {
                        Toast.makeText(this, "Nomor tidak boleh kosong",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    String normalized = normalizePhoneE164(userPhone);
                    if (normalized.length() < 10) {
                        Toast.makeText(this, "Nomor terlalu pendek",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    // Persist nomor yg di-edit user ke sender_phone supaya
                    // next time tap Balas pada inbox yg sama, langsung pakai
                    // ini (tier 1) tanpa perlu input ulang.
                    saveSenderPhone(o, normalized);
                    launchWaChat(o, normalized, template);
                })
                .setNeutralButton("Pilih Pelanggan Lain",
                        (d, w) -> promptPickCustomerForReply(o))
                .setNegativeButton("Batal", null)
                .show();
    }

    /** Simpan sender_phone yg di-correct user ke DB inbox row. */
    private void saveSenderPhone(OrderInbox o, String phone) {
        android.database.sqlite.SQLiteDatabase db =
                DatabaseHelper.getInstance(this).getWritableDatabase();
        android.content.ContentValues v = new android.content.ContentValues();
        v.put(com.crowja.damiupos.db.DatabaseHelper.COL_INBOX_SENDER_PHONE, phone);
        db.update(com.crowja.damiupos.db.DatabaseHelper.TABLE_ORDER_INBOX, v,
                com.crowja.damiupos.db.DatabaseHelper.COL_INBOX_ID + "=?",
                new String[]{String.valueOf(o.getId())});
        o.setSenderPhone(phone);
    }

    private void launchWaChat(OrderInbox o, String normalizedPhone, String template) {
        String url = "https://wa.me/" + normalizedPhone
                + "?text=" + Uri.encode(template);
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        String pkg = pickInstalledWaPackage();
        if (pkg != null) i.setPackage(pkg);
        try {
            startActivity(i);
            markReplied(o);
        } catch (Exception e) {
            Toast.makeText(this, "Gagal buka chat WA: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    /** Format nomor untuk display: kelompokkan supaya mudah dibaca user. */
    private static String formatPhoneDisplay(String phone) {
        String d = phone.replaceAll("[^0-9]", "");
        if (d.startsWith("0")) d = "62" + d.substring(1);
        else if (!d.startsWith("62")) d = "62" + d;
        // +62 812-3456-7890 style
        if (d.length() < 4) return "+" + d;
        StringBuilder sb = new StringBuilder("+").append(d, 0, 2);
        for (int i = 2; i < d.length(); ) {
            int chunk = (i == 2) ? 3 : 4;
            int end = Math.min(d.length(), i + chunk);
            sb.append(' ').append(d, i, end);
            i = end;
        }
        return sb.toString();
    }

    /**
     * Tampilkan dialog pilih pelanggan dari DB DAMIU POS. Setelah user pilih,
     * link inbox row ke customer (set customer_id) lalu retry reply.
     * Kalau pelanggan terpilih tidak punya nomor, fallback ke WA main + clipboard.
     */
    private void promptPickCustomerForReply(OrderInbox o) {
        java.util.List<Customer> customers = customerDao.getAll();
        if (customers.isEmpty()) {
            // Tidak ada pelanggan sama sekali → fallback langsung
            openWhatsAppMainFallback();
            markReplied(o);
            return;
        }
        CharSequence[] labels = new CharSequence[customers.size()];
        for (int i = 0; i < customers.size(); i++) {
            Customer c = customers.get(i);
            String phone = c.getPhone() != null && !c.getPhone().isEmpty()
                    ? "  •  " + c.getPhone() : "  (tanpa no HP)";
            labels[i] = c.getName() + phone;
        }
        new AlertDialog.Builder(this)
                .setTitle("Pilih Pelanggan untuk \"" + safe(o.getSenderName()) + "\"")
                .setItems(labels, (d, which) -> {
                    Customer picked = customers.get(which);
                    // Persist link supaya next time auto-resolve via tier 2
                    linkCustomerToInbox(o, picked.getId());
                    if (picked.getPhone() == null || picked.getPhone().isEmpty()) {
                        openWhatsAppMainFallback();
                        markReplied(o);
                        Toast.makeText(this,
                                "Pelanggan dipilih tapi tidak punya no HP — balasan disalin",
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    // Retry reply dengan customer_id baru di-set
                    o.setCustomerId(picked.getId());
                    reply(o);
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    /** Update customer_id pada inbox row di DB. */
    private void linkCustomerToInbox(OrderInbox o, long customerId) {
        // OrderInboxDao tidak punya updateCustomerId — pakai SQL langsung
        android.database.sqlite.SQLiteDatabase db =
                DatabaseHelper.getInstance(this).getWritableDatabase();
        android.content.ContentValues v = new android.content.ContentValues();
        v.put(com.crowja.damiupos.db.DatabaseHelper.COL_INBOX_CUSTOMER_ID, customerId);
        db.update(com.crowja.damiupos.db.DatabaseHelper.TABLE_ORDER_INBOX, v,
                com.crowja.damiupos.db.DatabaseHelper.COL_INBOX_ID + "=?",
                new String[]{String.valueOf(o.getId())});
        o.setCustomerId(customerId);
    }

    /** Buka WA main app + toast paste manual. Last-resort fallback. */
    private void openWhatsAppMainFallback() {
        Intent main = null;
        String pkg = pickInstalledWaPackage();
        if (pkg != null) {
            main = getPackageManager().getLaunchIntentForPackage(pkg);
        }
        if (main != null) {
            try {
                startActivity(main);
            } catch (Exception ignored) {}
        } else {
            Toast.makeText(this,
                    "WhatsApp tidak terinstal. Balasan disalin ke clipboard.",
                    Toast.LENGTH_LONG).show();
        }
    }

    /** Tandai item sudah dibalas → unlock tombol "Buat Trx + Selesai". */
    private void markReplied(OrderInbox o) {
        dao.setReplied(o.getId());
        o.setReplied(true);
        // Refresh list supaya tombol approve jadi enabled
        adapter.notifyDataSetChanged();
    }

    /**
     * Sumber nomor untuk balas (urutan prioritas):
     * <ol>
     *     <li>{@code sender_phone} di OrderInbox (kalau parser ekstrak nomor
     *         dari title notif WA — biasanya hanya kalau title berupa nomor)</li>
     *     <li>Phone pelanggan di DB DAMIU POS via {@code customer_id}
     *         yang ter-match di parse time</li>
     *     <li>Android Contacts lookup pakai nama pengirim — banyak kasus
     *         pelanggan tidak terdaftar di DAMIU POS tapi ada di kontak HP.
     *         Kalau kontak punya >1 nomor, return null — caller akan handle
     *         picker via {@link #pickContactPhone}.</li>
     * </ol>
     */
    private String resolveSenderPhone(OrderInbox o) {
        if (o.getSenderPhone() != null && !o.getSenderPhone().isEmpty()) {
            return o.getSenderPhone();
        }
        if (o.getCustomerId() > 0 && customerDao != null) {
            Customer c = customerDao.getById(o.getCustomerId());
            if (c != null && c.getPhone() != null && !c.getPhone().isEmpty()) {
                return c.getPhone();
            }
        }
        // Tier 3 di-handle terpisah karena bisa ada multi-phone. Cek di reply().
        return null;
    }

    /**
     * Cari semua nomor di Android Contacts berdasarkan display name.
     * Return list (mungkin kosong, mungkin > 1 kalau kontak punya beberapa
     * nomor seperti home/mobile/work).
     *
     * <p>Butuh permission READ_CONTACTS.
     */
    private java.util.List<String> lookupContactPhonesByName(String name) {
        java.util.List<String> phones = new java.util.ArrayList<>();
        if (name == null || name.isEmpty()) return phones;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
                != PackageManager.PERMISSION_GRANTED) {
            android.util.Log.w("ContactPhones", "READ_CONTACTS not granted");
            return phones;
        }
        // 1. Exact match dulu
        java.util.List<String> exact = queryContactPhonesAll(name, false);
        android.util.Log.d("ContactPhones", "exact match for '" + name
                + "' → " + exact.size() + " rows: " + exact);
        phones.addAll(exact);
        // 2. Kalau belum dapat, fuzzy match (handle suffix di nama)
        if (phones.isEmpty()) {
            java.util.List<String> fuzzy = queryContactPhonesAll(name, true);
            android.util.Log.d("ContactPhones", "fuzzy match for '" + name
                    + "' → " + fuzzy.size() + " rows: " + fuzzy);
            phones.addAll(fuzzy);
        }
        // Dedup berdasarkan digit-only canonical (mis. "+62812" dan "0812"
        // bisa jadi nomor yg sama meski string beda)
        java.util.LinkedHashMap<String, String> byNormalized =
                new java.util.LinkedHashMap<>();
        for (String p : phones) {
            String norm = p.replaceAll("[^0-9]", "");
            if (norm.startsWith("0")) norm = "62" + norm.substring(1);
            else if (!norm.startsWith("62")) norm = "62" + norm;
            if (norm.length() < 8) continue;
            // Keep first-seen format (sortir TYPE_MOBILE dulu — paling relevan)
            if (!byNormalized.containsKey(norm)) {
                byNormalized.put(norm, p.trim());
            }
        }
        java.util.List<String> deduped = new java.util.ArrayList<>(byNormalized.values());
        android.util.Log.d("ContactPhones", "After dedup: " + deduped.size()
                + " unique phones: " + deduped);
        return deduped;
    }

    /**
     * Pendekatan 2-step yang lebih reliable:
     * <ol>
     *     <li>Cari semua CONTACT_ID yang display name-nya match (exact / fuzzy)</li>
     *     <li>Query SEMUA nomor di tabel Phone WHERE CONTACT_ID IN (...)</li>
     * </ol>
     * Lebih akurat dari query langsung pakai DISPLAY_NAME di tabel Phone,
     * karena beberapa data row punya display name yg sedikit berbeda
     * (mis. karena merge multi-account contact di Samsung).
     */
    private java.util.List<String> queryContactPhonesAll(String name, boolean fuzzy) {
        java.util.List<String> out = new java.util.ArrayList<>();
        // Step 1: cari CONTACT_ID yang match
        java.util.List<Long> contactIds = findContactIdsByName(name, fuzzy);
        android.util.Log.d("ContactPhones", "Step 1 contactIds for '" + name
                + "' (fuzzy=" + fuzzy + "): " + contactIds);
        if (contactIds.isEmpty()) return out;

        // Step 2: query semua phone untuk contact_id tsb
        StringBuilder placeholders = new StringBuilder();
        String[] idArgs = new String[contactIds.size()];
        for (int i = 0; i < contactIds.size(); i++) {
            if (i > 0) placeholders.append(',');
            placeholders.append('?');
            idArgs[i] = String.valueOf(contactIds.get(i));
        }
        Cursor c = null;
        try {
            c = getContentResolver().query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    new String[]{
                            ContactsContract.CommonDataKinds.Phone.NUMBER,
                            ContactsContract.CommonDataKinds.Phone.TYPE
                    },
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID
                            + " IN (" + placeholders + ")",
                    idArgs,
                    // MOBILE (2) duluan, lainnya menyusul
                    "CASE " + ContactsContract.CommonDataKinds.Phone.TYPE
                            + " WHEN 2 THEN 0 ELSE 1 END ASC");
            while (c != null && c.moveToNext()) {
                String number = c.getString(0);
                if (number != null && !number.trim().isEmpty()) {
                    out.add(number.trim());
                }
            }
        } catch (Exception e) {
            android.util.Log.w("ContactPhones", "Phone query error: " + e.getMessage());
        } finally {
            if (c != null) c.close();
        }
        android.util.Log.d("ContactPhones", "Step 2 raw phones: " + out);
        return out;
    }

    /** Cari CONTACT_ID berdasarkan display name (exact atau fuzzy). */
    private java.util.List<Long> findContactIdsByName(String name, boolean fuzzy) {
        java.util.List<Long> ids = new java.util.ArrayList<>();
        String selection;
        String[] args;
        if (fuzzy) {
            selection = ContactsContract.Contacts.DISPLAY_NAME + " LIKE ?";
            args = new String[]{name + "%"};
        } else {
            selection = ContactsContract.Contacts.DISPLAY_NAME + " = ?";
            args = new String[]{name};
        }
        Cursor c = null;
        try {
            c = getContentResolver().query(
                    ContactsContract.Contacts.CONTENT_URI,
                    new String[]{ContactsContract.Contacts._ID},
                    selection, args, null);
            while (c != null && c.moveToNext()) {
                ids.add(c.getLong(0));
            }
        } catch (Exception e) {
            android.util.Log.w("ContactPhones", "Contact ID lookup error: " + e.getMessage());
        } finally {
            if (c != null) c.close();
        }
        return ids;
    }

    /**
     * Tampilkan dialog pilih nomor saat kontak punya beberapa nomor
     * (mis. home + mobile + work). User tap salah satu → continue ke
     * confirm dialog dengan nomor itu.
     */
    private void pickContactPhone(OrderInbox o, String template,
                                  java.util.List<String> phones) {
        String[] arr = phones.toArray(new String[0]);
        new AlertDialog.Builder(this)
                .setTitle("Kontak punya beberapa nomor")
                .setItems(arr, (d, which) ->
                        confirmPhoneAndLaunch(o, arr[which], template))
                .setNegativeButton("Batal", null)
                .show();
    }

    /** Format E.164 ringan: digit saja, awalan 0 → 62, kalau belum 62 prefix. */
    private static String normalizePhoneE164(String phone) {
        String d = phone.replaceAll("[^0-9]", "");
        if (d.startsWith("0")) d = "62" + d.substring(1);
        else if (!d.startsWith("62")) d = "62" + d;
        return d;
    }

    /** Cari package WhatsApp / WA Business yang terinstal. Null kalau tidak ada. */
    private String pickInstalledWaPackage() {
        for (String pkg : new String[]{"com.whatsapp", "com.whatsapp.w4b"}) {
            try {
                getPackageManager().getPackageInfo(pkg, 0);
                return pkg;
            } catch (Exception ignored) {}
        }
        return null;
    }

    /**
     * "Buat Trx + Selesai" — launch TransactionActivity dengan customer +
     * type pre-fill, lalu mark inbox sebagai SELESAI (approved). Banner +
     * sound stop kalau ini pesanan terakhir.
     */
    private void approve(OrderInbox o) {
        // Gate: user wajib klik "Balas" dulu sebelum boleh buat transaksi.
        // Mencegah kasus user lupa konfirmasi pelanggan dan langsung
        // proses pesanan tanpa konfirmasi balasan.
        if (!o.isReplied()) {
            Toast.makeText(this, "Balas terlebih dahulu", Toast.LENGTH_LONG).show();
            return;
        }
        ParsedOrder parsed = ParsedOrder.fromJson(o.getParsedJson());
        Intent intent = new Intent(this, TransactionActivity.class);
        if (o.getCustomerId() > 0) {
            intent.putExtra("customer_id", o.getCustomerId());
        }
        if (ParsedOrder.TYPE_KEMBALI.equals(parsed.type)) {
            intent.putExtra("type", "KEMBALI");
        } else if (ParsedOrder.TYPE_JUAL_BOTOL.equals(parsed.type)) {
            intent.putExtra("type", "JUAL_BOTOL");
        }
        // Auto-fill items hasil parser → TransactionActivity akan match
        // nama produk ke DB & populate items list otomatis.
        intent.putExtra(TransactionActivity.EXTRA_INBOX_PARSED_JSON,
                o.getParsedJson());
        // Pass sender name supaya TransactionActivity bisa auto-open
        // customer picker kalau customer_id belum di-set di inbox (parser
        // gagal auto-match). User tinggal pilih pelanggan yg sesuai.
        intent.putExtra(TransactionActivity.EXTRA_INBOX_SENDER_NAME,
                o.getSenderName());
        // Pass sender_phone — kalau user sudah correct nomor via Balas
        // confirm-dialog (saved ke inbox.sender_phone), TransactionActivity
        // pakai nomor itu sebagai override DB customer phone.
        if (o.getSenderPhone() != null && !o.getSenderPhone().isEmpty()) {
            intent.putExtra(TransactionActivity.EXTRA_INBOX_SENDER_PHONE,
                    o.getSenderPhone());
        }
        dao.updateStatus(o.getId(), OrderInbox.STATUS_APPROVED, 0);
        OrderAlertService.refresh(this); // service akan stopSelf kalau pending=0
        startActivity(intent);
        Toast.makeText(this, "Pelanggan & item terisi — periksa lalu Simpan",
                Toast.LENGTH_SHORT).show();
    }

    /**
     * "Selesai" — mark sebagai SELESAI tanpa membuka form transaksi.
     * Berguna kalau transaksi sudah dilakukan offline / di luar app.
     */
    private void markDone(OrderInbox o) {
        dao.updateStatus(o.getId(), OrderInbox.STATUS_APPROVED, 0);
        OrderAlertService.refresh(this);
        reload();
        Toast.makeText(this, "Pesanan ditandai selesai", Toast.LENGTH_SHORT).show();
    }

    /**
     * "Tolak" — mark sebagai REJECTED (bukan delete, supaya tetap tersimpan
     * sebagai history kalau user mau review nanti).
     */
    private void confirmReject(OrderInbox o) {
        new AlertDialog.Builder(this)
                .setTitle("Tolak Pesanan")
                .setMessage("Tandai pesanan dari " + safe(o.getSenderName())
                        + " sebagai ditolak? (tidak akan dihapus, hanya di-flag)")
                .setPositiveButton("Tolak", (d, w) -> {
                    dao.updateStatus(o.getId(), OrderInbox.STATUS_REJECTED, 0);
                    OrderAlertService.refresh(this);
                    reload();
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    private static String safe(String s) { return s != null ? s : "(tidak diketahui)"; }

    // ============================================================
    // Adapter
    // ============================================================
    private class InboxAdapter extends RecyclerView.Adapter<InboxAdapter.VH> {

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_order_inbox, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            OrderInbox o = data.get(pos);
            ParsedOrder parsed = ParsedOrder.fromJson(o.getParsedJson());

            h.tvSender.setText(safe(o.getSenderName()));
            h.tvRaw.setText("\"" + safe(o.getRawMessage()) + "\"");

            // Summary line: "JUAL — 3× Aqua + 2× Le Minerale [URGENT]"
            StringBuilder sum = new StringBuilder();
            if (parsed.isOrder) {
                sum.append(parsed.type != null ? parsed.type : "?")
                        .append(" — ").append(parsed.shortSummary());
                if (parsed.urgent) sum.append("  ⚡");
            } else {
                sum.append("(bukan pesanan)");
            }
            h.tvSummary.setText(sum.toString());

            // Meta: parser badge + waktu
            String parserLabel = OrderInbox.PARSER_CLAUDE.equals(o.getParserUsed())
                    ? "AI" : "regex";
            h.tvMeta.setText("Parser: " + parserLabel
                    + "  •  Confidence: " + String.format(java.util.Locale.US, "%.0f%%", parsed.confidence * 100)
                    + "  •  " + safe(o.getReceivedAt()));

            // Status badge + tombol aksi yang tampil bergantung status
            String status = o.getStatus();
            if (OrderInbox.STATUS_APPROVED.equals(status)) {
                h.tvBadge.setText("SELESAI");
                h.tvBadge.setBackgroundColor(Color.parseColor("#2E7D32"));
                h.tvBadge.setVisibility(View.VISIBLE);
                h.actionsRow.setVisibility(View.GONE);
                h.btnArchive.setVisibility(View.VISIBLE);
                h.btnArchive.setText("Arsipkan");
                h.btnArchive.setOnClickListener(v -> archive(o));
            } else if (OrderInbox.STATUS_REJECTED.equals(status)) {
                h.tvBadge.setText("DITOLAK");
                h.tvBadge.setBackgroundColor(Color.parseColor("#9E9E9E"));
                h.tvBadge.setVisibility(View.VISIBLE);
                h.actionsRow.setVisibility(View.GONE);
                h.btnArchive.setVisibility(View.VISIBLE);
                h.btnArchive.setText("Arsipkan");
                h.btnArchive.setOnClickListener(v -> archive(o));
            } else if (OrderInbox.STATUS_ARCHIVED.equals(status)) {
                h.tvBadge.setText("ARSIP");
                h.tvBadge.setBackgroundColor(Color.parseColor("#616161"));
                h.tvBadge.setVisibility(View.VISIBLE);
                h.actionsRow.setVisibility(View.GONE);
                h.btnArchive.setVisibility(View.VISIBLE);
                h.btnArchive.setText("Hapus Permanen");
                h.btnArchive.setOnClickListener(v -> confirmDeletePermanent(o));
            } else {
                // PENDING
                h.tvBadge.setText("BARU");
                h.tvBadge.setBackgroundColor(Color.parseColor("#FF6F00"));
                h.tvBadge.setVisibility(View.VISIBLE);
                h.actionsRow.setVisibility(View.VISIBLE);
                h.btnArchive.setVisibility(View.GONE);
            }

            // Visual feedback: kalau sudah dibalas, "Balas" outlined dengan checkmark
            // dan "Buat Trx + Selesai" jadi accent (terang). Kalau belum, approve
            // dim sebagai hint bahwa harus balas dulu.
            if (o.isReplied()) {
                h.btnReply.setText("Sudah dibalas ✓");
                h.btnReply.setAlpha(0.7f);
                h.btnApprove.setAlpha(1f);
            } else {
                h.btnReply.setText("Balas");
                h.btnReply.setAlpha(1f);
                h.btnApprove.setAlpha(0.5f); // dim — hint bahwa belum di-balas
            }

            h.btnReply.setOnClickListener(v -> reply(o));
            h.btnApprove.setOnClickListener(v -> approve(o));
            h.btnDone.setOnClickListener(v -> markDone(o));
            h.btnReject.setOnClickListener(v -> confirmReject(o));
        }

        @Override
        public int getItemCount() { return data.size(); }

        class VH extends RecyclerView.ViewHolder {
            TextView tvSender, tvSummary, tvRaw, tvMeta, tvBadge;
            View actionsRow;
            MaterialButton btnReply, btnApprove, btnDone, btnReject, btnArchive;
            VH(View v) {
                super(v);
                tvSender = v.findViewById(R.id.tvSender);
                tvSummary = v.findViewById(R.id.tvSummary);
                tvRaw = v.findViewById(R.id.tvRaw);
                tvMeta = v.findViewById(R.id.tvMeta);
                tvBadge = v.findViewById(R.id.tvBadge);
                actionsRow = v.findViewById(R.id.actionsRow);
                btnReply = v.findViewById(R.id.btnReply);
                btnApprove = v.findViewById(R.id.btnApprove);
                btnDone = v.findViewById(R.id.btnDone);
                btnReject = v.findViewById(R.id.btnReject);
                btnArchive = v.findViewById(R.id.btnArchive);
            }
        }
    }
}
