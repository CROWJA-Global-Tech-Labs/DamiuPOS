package com.crowja.damiupos.wa;

import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.exifinterface.media.ExifInterface;

import com.crowja.damiupos.R;
import com.crowja.damiupos.sync.SyncApi;
import com.google.android.material.button.MaterialButton;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Form tambah / ubah Status WhatsApp (Admin) — cermin form web /wa-status/create &amp; /edit: nama, perangkat/akun
 * yang memposting (+ status koneksi Bridge), sumber konten (artikel, artikel bergilir, teks + emoji, teks bergilir,
 * gambar, koleksi foto bergilir), warna latar &amp; gaya huruf, penonton, 🔒 menutup order hari ini, dan jadwal
 * (posting sekarang / jadwalkan / draf; Status yang sudah diproses: Simpan saja / posting ulang / jadwalkan ulang /
 * jadikan draf) + pengulangan. Foto dipilih lewat ACTION_GET_CONTENT (tanpa izin storage), diunggah satu per satu
 * ke {@code status/photos}, lalu tokennya disertakan saat menyimpan. Aturan sebenarnya di server.
 */
public class WaStatusEditActivity extends AppCompatActivity {

    public static final String EXTRA_ID = "status_id";
    public static final String EXTRA_MESSAGE = "message";

    private static final int REQ_IMAGE = 51;
    private static final int REQ_PHOTOS = 52;
    private static final long MAX_UPLOAD = 7_500_000L;   // server: maks 8 MB per foto
    private static final int MAX_SIDE = 2560;
    private static final int MAX_PHOTOS = 20;
    private static final String[] EMOJIS = {"😀", "😊", "😍", "🥰", "😎", "🤩", "🙏", "👍", "👏", "🔥", "💧", "🚰", "💦",
            "🌊", "🧊", "✨", "🎉", "🎁", "🛵", "🚚", "📦", "⭐", "❤️", "💙", "✅", "📢", "🕒", "📍", "🛒", "💰", "🌞", "🌙"};

    private String staffUuid;
    private long postId;
    private JSONObject form = new JSONObject();
    private JSONObject post = new JSONObject();
    private boolean processed;

    private TextView tvState;
    private ScrollView scroll;
    private LinearLayout box;
    private MaterialButton btnSave;
    @Nullable private AlertDialog progress;

    private EditText etName, etBody, etAudience, etRepeat;
    private final LinkedHashMap<String, CheckBox> accountBoxes = new LinkedHashMap<>();
    private final Map<Integer, String> sourceIds = new HashMap<>();
    private final Map<Integer, String> whenIds = new HashMap<>();
    private RadioGroup rgSource, rgWhen;
    private Spinner spArticle, spBg, spFont;
    private final List<Long> articleIds = new ArrayList<>();
    private final List<String> articleCaptions = new ArrayList<>();
    private final List<String> bgValues = new ArrayList<>();
    private final List<String> fontValues = new ArrayList<>();
    private TextView tvArticlePreview, tvBodyLabel, tvCount, tvCloseHint, tvRepeatLock, tvSchedule, tvKeepNote;
    private CheckBox cbCloses;
    private View secArticle, secArticleCycle, secImage, secImageCycle, secBody, secTextCycle, secStyle, secSchedule;
    private LinearLayout textList;
    private final List<EditText> texts = new ArrayList<>();
    @Nullable private EditText lastText;
    private ImageView ivImage;
    private WaStatusFlow photoGrid;
    private TextView tvPhotoCount;

    @Nullable private String scheduledAt;
    @Nullable private Picked newImage;
    private final List<Picked> newPhotos = new ArrayList<>();
    private final Set<String> removePaths = new HashSet<>();
    /** Berkas lokal → token titipan server (dipakai ulang bila simpan pertama ditolak validasi). */
    private final Map<String, String> tokens = new HashMap<>();
    private boolean closeTouched, closeAuto, suppressClose, saving;

    private static final class Picked {
        final File file;
        final String mime;

        Picked(File file, String mime) {
            this.file = file;
            this.mime = mime;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        staffUuid = WaAdmin.gate(this);
        if (staffUuid == null) return;
        postId = getIntent().getLongExtra(EXTRA_ID, 0);
        setContentView(R.layout.activity_wa_status_edit);
        Toolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle(postId > 0 ? "✏️ Ubah Status" : "➕ Status WhatsApp Baru");
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        tvState = findViewById(R.id.tvState);
        scroll = findViewById(R.id.scroll);
        box = findViewById(R.id.formBox);
        btnSave = findViewById(R.id.btnSave);
        btnSave.setOnClickListener(v -> submit());
        tvState.setOnClickListener(v -> {
            if (scroll.getVisibility() != View.VISIBLE) load();
        });
        cleanCache();
        load();
    }

    @Override
    protected void onDestroy() {
        hideProgress();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ muat

    private void load() {
        tvState.setText("Memuat…");
        tvState.setVisibility(View.VISIBLE);
        scroll.setVisibility(View.GONE);
        btnSave.setVisibility(View.GONE);
        new Thread(() -> {
            JSONObject r = null;
            Exception err = null;
            try {
                r = WaAdmin.api(this).adminWaGet(postId > 0 ? "status/" + postId : "status/form", null, staffUuid);
            } catch (Exception e) {
                err = e;
            }
            final JSONObject fr = r;
            final Exception fe = err;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (fr == null) {
                    String msg = WaAdmin.errorMessage(fe);
                    int code = fe instanceof SyncApi.SyncException ? ((SyncApi.SyncException) fe).code : 0;
                    if (code == 404 || code == 409) {
                        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                        finish();
                        return;
                    }
                    tvState.setText(msg + "\n\nKetuk untuk mencoba lagi.");
                    return;
                }
                form = fr.optJSONObject("form") != null ? fr.optJSONObject("form") : new JSONObject();
                post = fr.optJSONObject("post") != null ? fr.optJSONObject("post") : new JSONObject();
                processed = post.optBoolean("processed");
                newImage = null;
                newPhotos.clear();
                removePaths.clear();
                build();
                tvState.setVisibility(View.GONE);
                scroll.setVisibility(View.VISIBLE);
                btnSave.setVisibility(View.VISIBLE);
            });
        }).start();
    }

    // ------------------------------------------------------------------ bangun form

    private void build() {
        box.removeAllViews();
        accountBoxes.clear();
        sourceIds.clear();
        whenIds.clear();
        texts.clear();
        lastText = null;
        closeAuto = false;
        int gap = dp(14);

        if (processed) {
            int ok = post.optInt("ok_count");
            String posted = WaStatusUi.str(post, "posted_label");
            String head = "Status ini " + post.optString("state_text") + (posted != null ? " — " + posted : "")
                    + (ok > 0 ? " · tayang di " + ok + " akun" : "");
            String body = ok > 0
                    ? "WhatsApp tidak bisa menyunting Status yang sudah tayang. Simpan saja memperbarui catatan di sini (perangkat, jadwal & "
                    + "hasil tayang tetap). Posting ulang sekarang / Jadwalkan ulang memasang Status BARU berisi perubahan ini dan "
                    + "menggantikan riwayat tayang di daftar — Status lama tetap tampil di WhatsApp sampai kedaluwarsa (24 jam sejak tayang)."
                    : "Simpan saja memperbarui catatan di sini tanpa memposting. Posting ulang sekarang / Jadwalkan ulang memasang Status "
                    + "berisi perubahan ini.";
            box.addView(WaStatusUi.note(this, head + "\n\n" + body, WaStatusUi.AMBER, WaStatusUi.AMBER_BG), WaStatusUi.full(0));
        }
        if (!form.optBoolean("bridge_ok")) {
            box.addView(WaStatusUi.note(this, "FREZ WA Bridge tidak terjangkau saat ini — status koneksi akun tak bisa ditampilkan "
                    + "dan posting akan gagal sampai Bridge pulih.", WaStatusUi.AMBER, WaStatusUi.AMBER_BG), WaStatusUi.full(gap / 2));
        }

        // Nama
        label("Nama postingan", gap);
        etName = input("mis. Promo Akhir Pekan", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, 120);
        etName.setText(post.optString("name", ""));
        box.addView(etName);

        // Perangkat yang memposting
        label("Perangkat yang memposting", gap);
        JSONArray accs = form.optJSONArray("accounts");
        JSONArray chosen = post.optJSONArray("accounts");
        if (accs == null || accs.length() == 0) {
            box.addView(WaStatusUi.text(this, "Belum ada akun WhatsApp tertaut — tambahkan dulu di menu Akun WhatsApp.", 13,
                    WaStatusUi.AMBER, false));
        }
        for (int i = 0; accs != null && i < accs.length(); i++) {
            JSONObject a = accs.optJSONObject(i);
            if (a == null) continue;
            String acc = a.optString("account");
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(6), dp(2), dp(10), dp(2));
            row.setBackground(WaStatusUi.box(0xFFFFFFFF, 0xFFCFD8DC, 10, this));
            CheckBox cb = new CheckBox(this);
            String dev = WaStatusUi.str(a, "device");
            cb.setText("📱 " + a.optString("label", acc) + "\n" + acc + (dev != null ? " · " + dev : ""));
            cb.setTextColor(WaStatusUi.TEXT);
            cb.setTextSize(14);
            cb.setChecked(contains(chosen, acc));
            row.addView(cb, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            String conn = a.optString("connection");
            TextView st = WaStatusUi.text(this, "open".equals(conn) ? "● terhubung" : "closed".equals(conn) ? "○ tidak terhubung"
                    : "○ status tak diketahui", 12, "open".equals(conn) ? WaStatusUi.GREEN : WaStatusUi.TEXT_2, false);
            row.addView(st);
            box.addView(row, WaStatusUi.full(dp(6)));
            accountBoxes.put(acc, cb);
        }

        // Sumber konten
        label("Sumber konten", gap);
        rgSource = new RadioGroup(this);
        JSONArray sources = form.optJSONArray("sources");
        String src = post.optString("source_type", "text");
        for (int i = 0; sources != null && i < sources.length(); i++) {
            JSONObject s = sources.optJSONObject(i);
            if (s == null) continue;
            String key = s.optString("key");
            RadioButton rb = radio(WaStatusUi.sourceIcon(key) + " " + ("text".equals(key) ? "Teks + emoji" : s.optString("label")));
            rgSource.addView(rb);
            sourceIds.put(rb.getId(), key);
            if (key.equals(src)) rb.setChecked(true);
        }
        box.addView(rgSource);

        buildArticle(gap);
        buildArticleCycle(gap);
        buildImage(gap);
        buildImageCycle(gap);
        buildBody(gap);
        buildTextCycle(gap);
        buildStyle(gap);

        // Penonton
        label("Pengaturan penonton (opsional)", gap);
        box.addView(WaStatusUi.text(this, "Hanya kontak yang aktif dalam … hari terakhir (kosong = semua chat pribadi)", 12,
                WaStatusUi.TEXT_2, false));
        etAudience = input("mis. 30", InputType.TYPE_CLASS_NUMBER, 4);
        if (!post.isNull("audience_days")) etAudience.setText(String.valueOf(post.optInt("audience_days")));
        box.addView(etAudience, new LinearLayout.LayoutParams(dp(140), ViewGroup.LayoutParams.WRAP_CONTENT));

        buildCloses(gap);
        buildSchedule(gap);

        rgSource.setOnCheckedChangeListener((g, id) -> {
            sync();
            suggestClose();
        });
        rgWhen.setOnCheckedChangeListener((g, id) -> sync());
        closeTouched = cbCloses.isChecked() || post.optBoolean("exists");
        TextWatcher suggest = watcher(this::suggestClose);
        etName.addTextChangedListener(suggest);
        sync();
        syncClose();
        updateCount();
    }

    private void buildArticle(int gap) {
        LinearLayout sec = section(gap);
        secArticle = sec;
        sec.addView(WaStatusUi.text(this, "Artikel", 14, WaStatusUi.TEXT, true));
        articleIds.clear();
        articleCaptions.clear();
        List<String> titles = new ArrayList<>();
        titles.add("— pilih artikel terbit —");
        articleIds.add(0L);
        articleCaptions.add("");
        JSONArray arts = form.optJSONArray("articles");
        long sel = post.optLong("landing_article_id", 0);
        int selIdx = 0;
        for (int i = 0; arts != null && i < arts.length(); i++) {
            JSONObject a = arts.optJSONObject(i);
            if (a == null) continue;
            titles.add(a.optString("title"));
            articleIds.add(a.optLong("id"));
            articleCaptions.add(a.optString("caption", ""));
            if (a.optLong("id") == sel) selIdx = titles.size() - 1;
        }
        spArticle = spinner(titles, selIdx);
        sec.addView(spArticle, WaStatusUi.full(dp(4)));
        sec.addView(WaStatusUi.text(this, "Status berisi gambar sampul artikel (bila ada), judul, paragraf pembuka, dan tautan baca.",
                12, WaStatusUi.TEXT_2, false), WaStatusUi.full(dp(4)));
        if (titles.size() == 1) {
            sec.addView(WaStatusUi.text(this, "Belum ada artikel terbit di cabang ini.", 12, WaStatusUi.AMBER, false));
        }
        tvArticlePreview = WaStatusUi.note(this, "", WaStatusUi.GREEN, WaStatusUi.GREEN_BG);
        sec.addView(tvArticlePreview, WaStatusUi.full(dp(6)));
        spArticle.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) { sync(); }
            @Override public void onNothingSelected(AdapterView<?> p) { }
        });
    }

    private void buildArticleCycle(int gap) {
        LinearLayout sec = section(gap);
        secArticleCycle = sec;
        JSONObject stock = form.optJSONObject("stock");
        StringBuilder sb = new StringBuilder();
        sb.append("Artikel bergilir: tiap kali Status ini tayang, sistem memilih artikel berikutnya dari stok (urut dari yang paling ")
                .append("lama dibuat; semua artikel cabang ini ikut digilir) lalu mengulang dari awal bila semua sudah kebagian.\n\n")
                .append("Stok saat ini: ").append(stock != null ? stock.optInt("published") : 0).append(" terbit + ")
                .append(stock != null ? stock.optInt("draft") : 0).append(" draf. Artikel yang masih draf diterbitkan otomatis tepat ")
                .append("sebelum diposting.\n\nIsi Status: gambar sampul (bila ada), judul, paragraf pembuka, dan tautan baca. Agar ")
                .append("benar-benar bergilir, isi \"Ulangi tiap … hari\" di bagian Jadwal.");
        if (post.optBoolean("exists") && "article_cycle".equals(post.optString("source_type")) && post.optInt("run_count") > 0) {
            sb.append("\n\nSudah tayang ").append(post.optInt("run_count")).append("×");
            String t = WaStatusUi.str(post, "title");
            if (t != null) sb.append(" · terakhir: ").append(t);
            sb.append(".");
        }
        sec.addView(WaStatusUi.note(this, sb.toString(), WaStatusUi.BLUE, WaStatusUi.BLUE_BG));
    }

    private void buildImage(int gap) {
        LinearLayout sec = section(gap);
        secImage = sec;
        sec.addView(WaStatusUi.text(this, "Gambar", 14, WaStatusUi.TEXT, true));
        ivImage = new ImageView(this);
        ivImage.setAdjustViewBounds(true);
        ivImage.setScaleType(ImageView.ScaleType.FIT_START);
        ivImage.setBackground(WaStatusUi.box(0xFFF5F5F5, 0xFFCFD8DC, 8, this));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(160), dp(160));
        lp.topMargin = dp(6);
        sec.addView(ivImage, lp);
        String url = WaStatusUi.str(post, "image_url");
        if (url != null) {
            WaStatusUi.thumb(ivImage, url, dp(160));
            sec.addView(WaStatusUi.text(this, "Pilih berkas baru hanya bila ingin mengganti gambar.", 12, WaStatusUi.TEXT_2, false),
                    WaStatusUi.full(dp(4)));
        } else {
            ivImage.setVisibility(View.GONE);
        }
        MaterialButton pick = outlined("🖼️ Pilih gambar");
        pick.setOnClickListener(v -> pickImages(false));
        sec.addView(pick, WaStatusUi.full(dp(6)));
        sec.addView(WaStatusUi.text(this, "JPG / PNG / WebP, maksimal 8 MB. Teks di bawah menjadi keterangan gambar (opsional).",
                12, WaStatusUi.TEXT_2, false));
    }

    private void buildImageCycle(int gap) {
        LinearLayout sec = section(gap);
        secImageCycle = sec;
        sec.addView(WaStatusUi.text(this, "Koleksi foto (bergilir)", 14, WaStatusUi.TEXT, true));
        sec.addView(WaStatusUi.note(this, "Tiap kali Status ini tayang, foto berikutnya dari koleksi dipasang (urut seperti diunggah), "
                + "lalu kembali ke foto pertama. Agar benar-benar bergilir, isi \"Ulangi tiap … hari\" di bagian Jadwal. Keterangan di "
                + "bawah dipakai untuk semua foto.", WaStatusUi.BLUE, WaStatusUi.BLUE_BG), WaStatusUi.full(dp(6)));
        photoGrid = new WaStatusFlow(this, dp(8), dp(8));
        sec.addView(photoGrid, WaStatusUi.full(dp(8)));
        tvPhotoCount = WaStatusUi.text(this, "", 12, WaStatusUi.TEXT_2, false);
        sec.addView(tvPhotoCount, WaStatusUi.full(dp(4)));
        MaterialButton add = outlined("➕ Tambah foto");
        add.setOnClickListener(v -> pickImages(true));
        sec.addView(add, WaStatusUi.full(dp(6)));
        sec.addView(WaStatusUi.text(this, "JPG / PNG / WebP, maksimal 8 MB per foto, hingga 20 foto. Pilih beberapa sekaligus. "
                + "Centang \"hapus\" untuk membuang foto lama dari koleksi.", 12, WaStatusUi.TEXT_2, false));
        renderPhotos();
    }

    private void renderPhotos() {
        photoGrid.removeAllViews();
        JSONArray existing = post.optJSONArray("image_paths");
        for (int i = 0; existing != null && i < existing.length(); i++) {
            JSONObject o = existing.optJSONObject(i);
            if (o == null) continue;
            String path = o.optString("path");
            LinearLayout cell = photoCell(o.optString("url"));
            CheckBox del = new CheckBox(this);
            del.setText("hapus");
            del.setTextColor(WaStatusUi.TEXT);
            del.setTextSize(12);
            del.setChecked(removePaths.contains(path));
            del.setOnCheckedChangeListener((b, on) -> {
                if (on) removePaths.add(path);
                else removePaths.remove(path);
                updatePhotoCount();
            });
            cell.addView(del);
            photoGrid.addView(cell, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        for (Picked p : new ArrayList<>(newPhotos)) {
            LinearLayout cell = photoCell(p.file.getAbsolutePath());
            TextView x = WaStatusUi.text(this, "✕ batal (baru)", 12, WaStatusUi.RED, true);
            x.setPadding(dp(4), dp(8), dp(4), dp(8));
            x.setOnClickListener(v -> {
                newPhotos.remove(p);
                renderPhotos();
            });
            cell.addView(x);
            photoGrid.addView(cell, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        updatePhotoCount();
    }

    private LinearLayout photoCell(String src) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setBackground(WaStatusUi.box(0xFFF5F5F5, 0xFFCFD8DC, 8, this));
        cell.addView(iv, new LinearLayout.LayoutParams(dp(88), dp(88)));
        WaStatusUi.thumb(iv, src, dp(88));
        return cell;
    }

    private int photoTotal() {
        JSONArray existing = post.optJSONArray("image_paths");
        int kept = 0;
        for (int i = 0; existing != null && i < existing.length(); i++) {
            JSONObject o = existing.optJSONObject(i);
            if (o != null && !removePaths.contains(o.optString("path"))) kept++;
        }
        return kept + newPhotos.size();
    }

    private void updatePhotoCount() {
        if (tvPhotoCount == null) return;
        int n = photoTotal();
        tvPhotoCount.setText(n == 0 ? "Belum ada foto di koleksi." : n + " foto di koleksi" + (n > MAX_PHOTOS ? " — maksimal 20!" : "."));
        tvPhotoCount.setTextColor(n > MAX_PHOTOS ? WaStatusUi.RED : WaStatusUi.TEXT_2);
    }

    private void buildBody(int gap) {
        LinearLayout sec = section(gap);
        secBody = sec;
        tvBodyLabel = WaStatusUi.text(this, "Teks Status", 14, WaStatusUi.TEXT, true);
        sec.addView(tvBodyLabel);
        etBody = input("Tulis isi Status… emoji boleh 🙂", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, 0);
        etBody.setMinLines(4);
        etBody.setGravity(Gravity.TOP | Gravity.START);
        etBody.setText(post.optString("body", ""));
        sec.addView(etBody, WaStatusUi.full(dp(4)));
        sec.addView(emojiBar(e -> insert(etBody, e)), WaStatusUi.full(dp(4)));
        tvCount = WaStatusUi.text(this, "", 12, WaStatusUi.TEXT_2, false);
        tvCount.setGravity(Gravity.END);
        sec.addView(tvCount, WaStatusUi.full(dp(2)));
        etBody.addTextChangedListener(watcher(() -> {
            updateCount();
            suggestClose();
        }));
    }

    private void buildTextCycle(int gap) {
        LinearLayout sec = section(gap);
        secTextCycle = sec;
        sec.addView(WaStatusUi.text(this, "Daftar teks (bergilir)", 14, WaStatusUi.TEXT, true));
        sec.addView(WaStatusUi.note(this, "Tiap kali Status ini tayang, teks berikutnya dari daftar dipakai, lalu kembali ke teks "
                + "pertama. Emoji boleh. Agar benar-benar bergilir, isi \"Ulangi tiap … hari\" di bagian Jadwal. Warna latar & gaya "
                + "huruf di bawah berlaku untuk semua teks.", WaStatusUi.BLUE, WaStatusUi.BLUE_BG), WaStatusUi.full(dp(6)));
        textList = new LinearLayout(this);
        textList.setOrientation(LinearLayout.VERTICAL);
        sec.addView(textList, WaStatusUi.full(dp(6)));
        JSONArray saved = post.optJSONArray("texts");
        if (saved == null || saved.length() == 0) addText("");
        for (int i = 0; saved != null && i < saved.length(); i++) addText(saved.optString(i, ""));
        MaterialButton add = outlined("➕ Tambah teks");
        add.setOnClickListener(v -> {
            if (texts.size() >= 30) {
                Toast.makeText(this, "Maksimal 30 teks.", Toast.LENGTH_SHORT).show();
                return;
            }
            addText("").requestFocus();
        });
        sec.addView(add, WaStatusUi.full(dp(6)));
        sec.addView(emojiBar(e -> {
            if (lastText != null) insert(lastText, e);
        }), WaStatusUi.full(dp(4)));
        sec.addView(WaStatusUi.text(this, "Ketuk emoji untuk menyisipkannya ke teks yang sedang diketik. Maks. 30 teks, masing-masing "
                + "700 karakter (emoji dihitung 2).", 12, WaStatusUi.TEXT_2, false));
    }

    private EditText addText(String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.TOP);
        EditText et = input("Teks " + (texts.size() + 1) + "…", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, 0);
        et.setMinLines(3);
        et.setGravity(Gravity.TOP | Gravity.START);
        et.setText(value);
        row.addView(et, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);
        side.setGravity(Gravity.END);
        TextView cnt = WaStatusUi.text(this, "", 11, WaStatusUi.TEXT_2, false);
        TextView x = WaStatusUi.text(this, "✕", 18, WaStatusUi.TEXT_2, true);
        x.setPadding(dp(10), dp(4), dp(6), dp(4));
        x.setContentDescription("Hapus teks");
        side.addView(x);
        side.addView(cnt);
        row.addView(side);
        Runnable count = () -> {
            int n = WaStatusRules.units(et.getText().toString());
            cnt.setText(n + "/" + WaStatusRules.MAX_TEXT);
            cnt.setTextColor(n > WaStatusRules.MAX_TEXT ? WaStatusUi.RED : WaStatusUi.TEXT_2);
        };
        count.run();
        et.addTextChangedListener(watcher(() -> {
            count.run();
            suggestClose();
        }));
        et.setOnFocusChangeListener((v, has) -> {
            if (has) lastText = et;
        });
        x.setOnClickListener(v -> {
            textList.removeView(row);
            texts.remove(et);
            if (lastText == et) lastText = texts.isEmpty() ? null : texts.get(texts.size() - 1);
            suggestClose();
        });
        textList.addView(row, WaStatusUi.full(dp(4)));
        texts.add(et);
        lastText = et;
        return et;
    }

    private void buildStyle(int gap) {
        LinearLayout sec = section(gap);
        secStyle = sec;
        sec.addView(WaStatusUi.text(this, "Warna latar", 14, WaStatusUi.TEXT, true));
        bgValues.clear();
        List<String> bgLabels = new ArrayList<>();
        bgValues.add("random");
        bgLabels.add("🎲 Acak tiap tayang");
        bgValues.add("cycle");
        bgLabels.add("🔄 Bergilir tiap tayang");
        JSONArray bgs = form.optJSONArray("backgrounds");
        for (int i = 0; bgs != null && i < bgs.length(); i++) {
            JSONObject b = bgs.optJSONObject(i);
            if (b == null) continue;
            bgValues.add(b.optString("hex"));
            bgLabels.add(b.optString("name") + " (" + b.optString("hex") + ")");
        }
        String bgSel = WaStatusUi.str(post, "background_color");
        int bgIdx = Math.max(0, bgSel != null ? bgValues.indexOf(bgSel) : 0);   // tanpa pilihan = opsi pertama (seperti web)
        spBg = spinner(bgLabels, bgIdx);
        sec.addView(spBg, WaStatusUi.full(dp(4)));

        sec.addView(WaStatusUi.text(this, "Gaya huruf", 14, WaStatusUi.TEXT, true), WaStatusUi.full(dp(10)));
        fontValues.clear();
        List<String> fontLabels = new ArrayList<>();
        fontValues.add("random");
        fontLabels.add("🎲 Acak tiap tayang");
        fontValues.add("cycle");
        fontLabels.add("🔄 Bergilir tiap tayang");
        for (int i = 0; i <= 7; i++) {
            fontValues.add(String.valueOf(i));
            fontLabels.add("Gaya " + (i + 1));
        }
        String fontSel = post.optString("font", "0");
        spFont = spinner(fontLabels, Math.max(0, fontValues.indexOf(fontSel)));
        sec.addView(spFont, WaStatusUi.full(dp(4)));
    }

    private void buildCloses(int gap) {
        LinearLayout sec = new LinearLayout(this);
        sec.setOrientation(LinearLayout.VERTICAL);
        sec.setBackground(WaStatusUi.box(WaStatusUi.RED_BG, 0xFFEF9A9A, 10, this));
        sec.setPadding(dp(8), dp(8), dp(10), dp(10));
        cbCloses = new CheckBox(this);
        cbCloses.setText("🔒 Postingan ini menutup order hari ini");
        cbCloses.setTextColor(WaStatusUi.TEXT);
        cbCloses.setTextSize(14);
        cbCloses.setTypeface(cbCloses.getTypeface(), android.graphics.Typeface.BOLD);
        cbCloses.setChecked(post.optBoolean("closes_orders"));
        sec.addView(cbCloses);
        sec.addView(WaStatusUi.text(this, "Begitu Status tayang di akun pertama, penerimaan order hari itu ditutup lebih awal — agen AI & "
                + "halaman order menawarkan kirim besok — sampai jam Open Order berikutnya (" + form.optString("open_time", "-") + "). "
                + "Gagal tayang di semua akun = order BELUM ditutup (Anda diberi notifikasi). Tidak bisa diulang.", 12, WaStatusUi.TEXT, false));
        tvCloseHint = WaStatusUi.text(this, "Dicentang otomatis karena teks menyebut tutup/libur — hapus centang bila ini BUKAN "
                + "pengumuman tutup.", 12, WaStatusUi.AMBER, true);
        sec.addView(tvCloseHint, WaStatusUi.full(dp(4)));
        box.addView(sec, WaStatusUi.full(gap));
        cbCloses.setOnCheckedChangeListener((b, on) -> {
            if (!suppressClose) {
                closeTouched = true;
                closeAuto = false;
            }
            syncClose();
        });
    }

    private void buildSchedule(int gap) {
        label("Jadwal", gap);
        rgWhen = new RadioGroup(this);
        JSONArray whens = form.optJSONArray("whens");
        String when = post.optString("when", "now");
        for (int i = 0; whens != null && i < whens.length(); i++) {
            JSONObject w = whens.optJSONObject(i);
            if (w == null) continue;
            RadioButton rb = radio(w.optString("label"));
            rgWhen.addView(rb);
            whenIds.put(rb.getId(), w.optString("key"));
            if (w.optString("key").equals(when)) rb.setChecked(true);
        }
        box.addView(rgWhen);

        String keep = "Perangkat & pengulangan tidak berubah. Isi baru dipakai saat diposting ulang (juga saat \"Coba lagi yang gagal\", "
                + "kecuali sumber bergilir yang mengulang isi putaran ini).";
        if (form.optBoolean("posted_today") && form.optBoolean("can_close_today") && !post.optBoolean("closes_orders")
                && post.isNull("repeat_days")) {
            keep += " Mencentang 🔒 menutup order di atas lalu menyimpan = order hari ini langsung ditutup (Status ini tayang hari ini).";
        }
        tvKeepNote = WaStatusUi.text(this, keep, 12, WaStatusUi.TEXT_2, false);
        box.addView(tvKeepNote, WaStatusUi.full(dp(6)));

        LinearLayout sched = new LinearLayout(this);
        sched.setOrientation(LinearLayout.VERTICAL);
        secSchedule = sched;
        scheduledAt = WaStatusUi.str(post, "scheduled_at");
        MaterialButton pick = outlined("📅 Pilih tanggal & jam");
        pick.setOnClickListener(v -> pickSchedule());
        sched.addView(pick, WaStatusUi.full(dp(6)));
        tvSchedule = WaStatusUi.text(this, "", 14, WaStatusUi.TEXT, true);
        sched.addView(tvSchedule, WaStatusUi.full(dp(4)));
        sched.addView(WaStatusUi.text(this, "zona waktu " + form.optString("timezone", "Asia/Jakarta"), 12, WaStatusUi.TEXT_2, false));
        box.addView(sched, WaStatusUi.full(0));
        showSchedule();

        box.addView(WaStatusUi.text(this, "🔁 Ulangi tiap … hari (kosong = sekali saja)", 13, WaStatusUi.TEXT, false), WaStatusUi.full(dp(12)));
        etRepeat = input("mis. 7", InputType.TYPE_CLASS_NUMBER, 2);
        if (!post.isNull("repeat_days")) etRepeat.setText(String.valueOf(post.optInt("repeat_days")));
        box.addView(etRepeat, new LinearLayout.LayoutParams(dp(140), ViewGroup.LayoutParams.WRAP_CONTENT));
        box.addView(WaStatusUi.text(this, "Jam yang sama; berlaku untuk semua sumber konten.", 12, WaStatusUi.TEXT_2, false));
        tvRepeatLock = WaStatusUi.text(this, "", 12, WaStatusUi.AMBER, true);
        box.addView(tvRepeatLock, WaStatusUi.full(dp(4)));
    }

    // ------------------------------------------------------------------ sinkron tampilan

    private String source() {
        String s = sourceIds.get(rgSource.getCheckedRadioButtonId());
        return s != null ? s : "text";
    }

    private String when() {
        String w = whenIds.get(rgWhen.getCheckedRadioButtonId());
        return w != null ? w : (processed ? "keep" : "now");
    }

    private void sync() {
        String src = source();
        String when = when();
        WaStatusUi.hide(secArticle, !"article".equals(src));
        WaStatusUi.hide(secArticleCycle, !"article_cycle".equals(src));
        WaStatusUi.hide(secImage, !"image".equals(src));
        WaStatusUi.hide(secImageCycle, !"image_cycle".equals(src));
        WaStatusUi.hide(secBody, !("text".equals(src) || "image".equals(src) || "image_cycle".equals(src)));
        tvBodyLabel.setText("text".equals(src) ? "Teks Status" : "Keterangan (opsional)");
        WaStatusUi.hide(secTextCycle, !"text_cycle".equals(src));
        WaStatusUi.hide(secStyle, !("text".equals(src) || "text_cycle".equals(src)));
        WaStatusUi.hide(secSchedule, !"schedule".equals(when));
        WaStatusUi.hide(tvKeepNote, !"keep".equals(when));
        btnSave.setText(WaStatusRules.saveLabel(when, processed));
        if (processed) {
            keepLock(when);
            syncClose();
        }
        int pos = spArticle.getSelectedItemPosition();
        String cap = pos > 0 && pos < articleCaptions.size() ? articleCaptions.get(pos) : "";
        tvArticlePreview.setText(cap);
        WaStatusUi.hide(tvArticlePreview, !"article".equals(src) || cap.isEmpty());
    }

    /** "Simpan saja": perangkat & pengulangan tak berubah — dikunci ke nilai tersimpan (server pun mengabaikannya). */
    private void keepLock(String when) {
        boolean keep = "keep".equals(when);
        JSONArray saved = post.optJSONArray("saved_accounts");
        for (Map.Entry<String, CheckBox> e : accountBoxes.entrySet()) {
            if (keep) e.getValue().setChecked(contains(saved, e.getKey()));
            e.getValue().setEnabled(!keep);
        }
        if (keep) etRepeat.setText(savedRepeat());
    }

    private String savedRepeat() {
        return post.isNull("repeat_days") ? "" : String.valueOf(post.optInt("repeat_days"));
    }

    private void syncClose() {
        if (tvCloseHint == null || etRepeat == null) return;
        boolean on = cbCloses.isChecked();
        WaStatusUi.hide(tvCloseHint, !(closeAuto && on));
        boolean keepNow = processed && "keep".equals(when());
        etRepeat.setEnabled(!(on || keepNow));   // nonaktif = tak dikirim → pengulangan kosong
        WaStatusUi.hide(tvRepeatLock, !on);
        tvRepeatLock.setText(keepNow && !savedRepeat().isEmpty()
                ? "🔒 Status berulang tidak bisa dijadikan penutupan lewat \"Simpan saja\" — pilih \"Jadwalkan ulang\" lalu kosongkan "
                + "pengulangan, atau pakai tombol 🔒 Tutup order hari ini di daftar."
                : "🔒 Status penutupan tidak boleh berulang — pengulangan dimatikan.");
    }

    /**
     * Kata kunci tutup/libur hanya MENYARANKAN centang pada Status BARU selama operator belum memilih sendiri, dan tak
     * pernah bila "Ulangi tiap … hari" sudah diisi (centang membuang pengulangan).
     */
    private void suggestClose() {
        if (closeTouched || cbCloses == null || etRepeat == null) return;
        boolean hit = WaStatusRules.suggestsClose(closeText()) && etRepeat.getText().toString().trim().isEmpty();
        if (hit != closeAuto) {
            closeAuto = hit;
            suppressClose = true;
            cbCloses.setChecked(hit);
            suppressClose = false;
            syncClose();
        }
    }

    private String closeText() {
        String src = source();
        StringBuilder t = new StringBuilder(etName.getText().toString());
        if ("text".equals(src) || "image".equals(src) || "image_cycle".equals(src)) t.append(' ').append(etBody.getText());
        if ("text_cycle".equals(src)) {
            for (EditText e : texts) t.append(' ').append(e.getText());
        }
        return t.toString();
    }

    private void updateCount() {
        if (tvCount == null) return;
        int n = WaStatusRules.units(etBody.getText().toString());
        int max = form.optInt("max_text", WaStatusRules.MAX_TEXT);
        tvCount.setText(n + "/" + max + " (emoji dihitung 2)");
        tvCount.setTextColor(n > max ? WaStatusUi.RED : WaStatusUi.TEXT_2);
    }

    private void showSchedule() {
        if (scheduledAt == null) {
            tvSchedule.setText("Belum dipilih.");
            return;
        }
        try {
            SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
            SimpleDateFormat out = new SimpleDateFormat("EEEE, d MMM yyyy · HH:mm", new Locale("id", "ID"));
            tvSchedule.setText("🕒 " + out.format(in.parse(scheduledAt)));
        } catch (Exception e) {
            tvSchedule.setText("🕒 " + scheduledAt);
        }
    }

    private void pickSchedule() {
        Calendar c = Calendar.getInstance();
        try {
            if (scheduledAt != null) c.setTime(new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).parse(scheduledAt));
            else c.add(Calendar.HOUR_OF_DAY, 1);
        } catch (Exception ignored) {
            // tanggal rusak → mulai dari sekarang
        }
        new DatePickerDialog(this, (dp, y, m, d) -> new TimePickerDialog(this, (tp, h, min) -> {
            scheduledAt = String.format(Locale.US, "%04d-%02d-%02d %02d:%02d", y, m + 1, d, h, min);
            showSchedule();
        }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), true).show(),
                c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show();
    }

    // ------------------------------------------------------------------ foto

    private void pickImages(boolean multiple) {
        // ACTION_GET_CONTENT (bukan ACTION_PICK): andal di semua versi Android & tanpa izin storage.
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("image/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/jpeg", "image/png", "image/webp"});
        if (multiple) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(Intent.createChooser(i, multiple ? "Pilih foto koleksi" : "Pilih gambar"),
                    multiple ? REQ_PHOTOS : REQ_IMAGE);
        } catch (Exception e) {
            Toast.makeText(this, "Tidak dapat membuka galeri.", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || (requestCode != REQ_IMAGE && requestCode != REQ_PHOTOS)) return;
        if (ivImage == null || photoGrid == null) {
            Toast.makeText(this, "Form belum siap — pilih ulang fotonya sebentar lagi.", Toast.LENGTH_LONG).show();
            return;
        }
        List<Uri> uris = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                if (clip.getItemAt(i).getUri() != null) uris.add(clip.getItemAt(i).getUri());
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (uris.isEmpty()) return;
        final boolean single = requestCode == REQ_IMAGE;
        final int room = single ? 1 : MAX_PHOTOS - photoTotal();
        if (room <= 0) {
            Toast.makeText(this, "Koleksi maksimal 20 foto.", Toast.LENGTH_LONG).show();
            return;
        }
        showProgress("Menyiapkan foto…");
        new Thread(() -> {
            List<Picked> got = new ArrayList<>();
            String err = null;
            for (Uri u : uris) {
                if (got.size() >= room) {
                    err = "Hanya " + room + " foto yang ditambahkan — koleksi maksimal 20 foto.";
                    break;
                }
                try {
                    got.add(prepare(u));
                } catch (Exception e) {
                    err = e.getMessage() != null ? e.getMessage() : "Foto tak bisa dibaca.";
                }
            }
            final String fe = err;
            runOnUiThread(() -> {
                hideProgress();
                if (isFinishing() || isDestroyed()) return;
                if (fe != null) Toast.makeText(this, fe, Toast.LENGTH_LONG).show();
                if (got.isEmpty()) return;
                if (single) {
                    newImage = got.get(0);
                    ivImage.setVisibility(View.VISIBLE);
                    ivImage.setImageDrawable(null);
                    WaStatusUi.thumb(ivImage, newImage.file.getAbsolutePath(), dp(160));
                } else {
                    newPhotos.addAll(got);
                    renderPhotos();
                }
            });
        }).start();
    }

    /** Salin foto ke cache; terlalu besar / format lain → diperkecil ke JPEG (≤ 2560 px, ≤ 7,5 MB, orientasi EXIF diterapkan). */
    private Picked prepare(Uri uri) throws IOException {
        File dir = cacheDir();
        String mime = getContentResolver().getType(uri);
        File raw = new File(dir, "raw_" + System.nanoTime());
        try (InputStream in = getContentResolver().openInputStream(uri); OutputStream out = new FileOutputStream(raw)) {
            if (in == null) throw new IOException("Foto tak bisa dibaca.");
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(raw.getPath(), o);
        if (o.outWidth <= 0 || o.outHeight <= 0) {
            raw.delete();
            throw new IOException("Berkas yang dipilih bukan gambar.");
        }
        if (mime == null) mime = o.outMimeType;
        boolean supported = "image/jpeg".equals(mime) || "image/png".equals(mime) || "image/webp".equals(mime);
        if (supported && raw.length() <= MAX_UPLOAD && Math.max(o.outWidth, o.outHeight) <= 4096) {
            File f = new File(dir, "st_" + System.nanoTime() + ("image/png".equals(mime) ? ".png" : "image/webp".equals(mime) ? ".webp" : ".jpg"));
            if (!raw.renameTo(f)) f = raw;
            return new Picked(f, mime);
        }
        int sample = 1;
        while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2;
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = sample;
        Bitmap bm = BitmapFactory.decodeFile(raw.getPath(), d);
        if (bm == null) {
            raw.delete();
            throw new IOException("Gambar tak bisa dibaca.");
        }
        Matrix m = new Matrix();
        float scale = Math.min(1f, (float) MAX_SIDE / Math.max(bm.getWidth(), bm.getHeight()));
        if (scale < 1f) m.postScale(scale, scale);
        int rot = exifRotation(raw);
        if (rot != 0) m.postRotate(rot);
        if (!m.isIdentity()) {
            Bitmap b2 = Bitmap.createBitmap(bm, 0, 0, bm.getWidth(), bm.getHeight(), m, true);
            if (b2 != bm) bm.recycle();
            bm = b2;
        }
        File out = new File(dir, "st_" + System.nanoTime() + ".jpg");
        int q = 88;
        do {
            try (FileOutputStream fo = new FileOutputStream(out)) {
                bm.compress(Bitmap.CompressFormat.JPEG, q, fo);
            }
            q -= 12;
        } while (out.length() > MAX_UPLOAD && q >= 52);
        bm.recycle();
        raw.delete();
        return new Picked(out, "image/jpeg");
    }

    private static int exifRotation(File f) {
        try {
            int o = new ExifInterface(f.getPath()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            return o == ExifInterface.ORIENTATION_ROTATE_90 ? 90 : o == ExifInterface.ORIENTATION_ROTATE_180 ? 180
                    : o == ExifInterface.ORIENTATION_ROTATE_270 ? 270 : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private File cacheDir() {
        File dir = new File(getCacheDir(), "wa-status");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    /** Foto titipan lokal yang lebih tua dari sehari dibuang (form ditinggal). */
    private void cleanCache() {
        File[] files = cacheDir().listFiles();
        long cutoff = System.currentTimeMillis() - 24L * 3600_000L;
        for (int i = 0; files != null && i < files.length; i++) {
            if (files[i].lastModified() < cutoff) files[i].delete();
        }
    }

    // ------------------------------------------------------------------ simpan

    private void submit() {
        if (saving) return;
        String when = when();
        String src = source();
        if (etName.getText().toString().trim().isEmpty()) {
            alert("Periksa isian", "Isi nama postingan.");
            return;
        }
        int accounts = 0;
        for (CheckBox cb : accountBoxes.values()) if (cb.isChecked()) accounts++;
        if (!"keep".equals(when) && accounts == 0) {
            alert("Periksa isian", "Pilih setidaknya satu perangkat/akun WhatsApp.");
            return;
        }
        if ("schedule".equals(when) && scheduledAt == null) {
            alert("Periksa isian", "Isi jadwal posting (📅 Pilih tanggal & jam).");
            return;
        }
        if ("image_cycle".equals(src) && photoTotal() > MAX_PHOTOS) {
            alert("Periksa isian", "Koleksi maksimal 20 foto.");
            return;
        }
        String msg = WaStatusRules.submitConfirm(when, processed, post.optBoolean("was_live"), cbCloses.isChecked(),
                post.optBoolean("closes_orders"), form.optBoolean("posted_today"), form.optBoolean("can_close_today"),
                savedRepeat().isEmpty(), accounts);
        if (msg == null) {
            save();
            return;
        }
        new AlertDialog.Builder(this).setTitle(WaStatusRules.saveLabel(when, processed)).setMessage(msg)
                .setPositiveButton("Lanjutkan", (d, w) -> save())
                .setNegativeButton("Batal", null)
                .show();
    }

    private void save() {
        final JSONObject body;
        final List<Picked> uploads = new ArrayList<>();
        final String src = source();
        final String when = when();
        try {
            body = body(src, when);
        } catch (Exception e) {
            alert("Gagal", "Isian tak bisa disiapkan.");
            return;
        }
        if ("image".equals(src) && newImage != null) uploads.add(newImage);
        if ("image_cycle".equals(src)) uploads.addAll(newPhotos);
        saving = true;
        btnSave.setEnabled(false);
        showProgress(uploads.isEmpty() ? savingText(when) : "Mengunggah foto…");
        new Thread(() -> {
            JSONObject r = null;
            Exception err = null;
            try {
                SyncApi api = WaAdmin.api(this);
                JSONArray staged = new JSONArray();
                for (int i = 0; i < uploads.size(); i++) {
                    Picked p = uploads.get(i);
                    String key = p.file.getAbsolutePath();
                    String token = tokens.get(key);
                    if (token == null) {
                        final int n = i + 1;
                        runOnUiThread(() -> setProgressText("Mengunggah foto " + n + "/" + uploads.size() + "…"));
                        JSONObject up = api.adminWaUpload("status/photos", new HashMap<>(), "image", p.file, p.mime, staffUuid);
                        token = up.optString("token", "");
                        tokens.put(key, token);
                    }
                    staged.put(token);
                }
                if ("image".equals(src) && staged.length() > 0) body.put("staged_image", staged.getString(0));
                if ("image_cycle".equals(src)) body.put("staged_images", staged);
                runOnUiThread(() -> setProgressText(savingText(when)));
                r = api.adminWaPost(postId > 0 ? "status/" + postId + "/update" : "status", body, staffUuid);
            } catch (Exception e) {
                err = e;
            }
            final JSONObject fr = r;
            final Exception fe = err;
            runOnUiThread(() -> {
                hideProgress();
                saving = false;
                btnSave.setEnabled(true);
                if (isFinishing() || isDestroyed()) return;
                if (fr != null) {
                    Intent res = new Intent();
                    res.putExtra(EXTRA_MESSAGE, WaAdmin.message(fr, "Status disimpan."));
                    setResult(RESULT_OK, res);
                    finish();
                    return;
                }
                failed(fe);
            });
        }).start();
    }

    private void failed(Exception e) {
        String msg = WaAdmin.errorMessage(e);
        if (!(e instanceof SyncApi.SyncException)) {
            tokens.clear();
            alert("Gagal menyimpan", msg + "\n\nBila koneksi terputus saat menyimpan, periksa daftar Status dulu sebelum mencoba lagi.");
            return;
        }
        SyncApi.SyncException se = (SyncApi.SyncException) e;
        if (se.code == 422) {
            if (msg.contains("kedaluwarsa")) tokens.clear();
            alert("Periksa isian", msg);
            return;
        }
        tokens.clear();
        String state = "";
        try {
            state = new JSONObject(se.body).optString("state", "");
        } catch (Exception ignored) {
            // bukan JSON
        }
        if (se.code == 409 && "stale".equals(state)) {
            new AlertDialog.Builder(this).setTitle("Status berubah").setMessage(msg)
                    .setPositiveButton("Muat ulang form", (d, w) -> load())
                    .setCancelable(false).show();
            return;
        }
        if (se.code == 409 || se.code == 404) {
            new AlertDialog.Builder(this).setTitle("Tidak disimpan").setMessage(msg)
                    .setPositiveButton("OK", (d, w) -> finish())
                    .setCancelable(false).show();
            return;
        }
        alert("Gagal menyimpan", msg);
    }

    private String savingText(String when) {
        return "now".equals(when) ? "Memposting Status…" : "Menyimpan…";
    }

    private JSONObject body(String src, String when) throws Exception {
        JSONObject b = new JSONObject();
        b.put("name", etName.getText().toString().trim());
        b.put("source_type", src);
        b.put("when", when);
        String fs = WaStatusUi.str(post, "form_state");
        if (fs != null) b.put("form_state", fs);
        JSONArray acc = new JSONArray();
        for (Map.Entry<String, CheckBox> e : accountBoxes.entrySet()) if (e.getValue().isChecked()) acc.put(e.getKey());
        b.put("accounts", acc);
        if ("text".equals(src) || "image".equals(src) || "image_cycle".equals(src)) b.put("body", etBody.getText().toString());
        if ("text_cycle".equals(src)) {
            JSONArray t = new JSONArray();
            for (EditText e : texts) t.put(e.getText().toString());
            b.put("texts", t);
        }
        if ("article".equals(src)) {
            int pos = spArticle.getSelectedItemPosition();
            long id = pos > 0 && pos < articleIds.size() ? articleIds.get(pos) : 0;
            b.put("landing_article_id", id > 0 ? id : JSONObject.NULL);
        }
        if ("text".equals(src) || "text_cycle".equals(src)) {
            b.put("background_color", bgValues.get(Math.max(0, spBg.getSelectedItemPosition())));
            b.put("font", fontValues.get(Math.max(0, spFont.getSelectedItemPosition())));
        }
        b.put("audience_days", intOrNull(etAudience));
        b.put("repeat_days", etRepeat.isEnabled() ? intOrNull(etRepeat) : JSONObject.NULL);
        b.put("closes_orders", cbCloses.isChecked());
        if ("schedule".equals(when)) b.put("scheduled_at", scheduledAt);
        if ("image_cycle".equals(src)) {
            JSONArray rm = new JSONArray();
            for (String p : removePaths) rm.put(p);
            b.put("remove_images", rm);
        }
        return b;
    }

    private static Object intOrNull(EditText et) {
        String s = et.getText().toString().trim();
        if (s.isEmpty()) return JSONObject.NULL;
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return s;   // server menolak dengan pesan yang jelas
        }
    }

    // ------------------------------------------------------------------ pembantu UI

    private int dp(int v) {
        return WaStatusUi.dp(this, v);
    }

    private void label(String s, int top) {
        box.addView(WaStatusUi.text(this, s, 14, WaStatusUi.TEXT, true), WaStatusUi.full(top));
    }

    private LinearLayout section(int top) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        box.addView(l, WaStatusUi.full(top));
        return l;
    }

    private EditText input(String hint, int type, int maxLen) {
        EditText et = new EditText(this);
        et.setInputType(type);
        et.setHint(hint);
        et.setTextColor(WaStatusUi.TEXT);
        et.setHintTextColor(0xFF555555);
        et.setTextSize(15);
        if (maxLen > 0) et.setFilters(new InputFilter[]{new InputFilter.LengthFilter(maxLen)});
        return et;
    }

    private RadioButton radio(String label) {
        RadioButton rb = new RadioButton(this);
        rb.setId(View.generateViewId());
        rb.setText(label);
        rb.setTextColor(WaStatusUi.TEXT);
        rb.setTextSize(14);
        return rb;
    }

    private Spinner spinner(List<String> items, int sel) {
        Spinner sp = new Spinner(this);
        ArrayAdapter<String> ad = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, items);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(ad);
        sp.setBackground(WaStatusUi.box(0xFFFFFFFF, 0xFF90A4AE, 8, this));
        sp.setSelection(Math.min(Math.max(0, sel), items.size() - 1));
        return sp;
    }

    private MaterialButton outlined(String label) {
        MaterialButton b = new MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(WaStatusUi.BLUE);
        b.setStrokeColor(android.content.res.ColorStateList.valueOf(0xFF90A4AE));
        return b;
    }

    private interface OnEmoji {
        void pick(String e);
    }

    private View emojiBar(OnEmoji on) {
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (String e : EMOJIS) {
            TextView t = WaStatusUi.text(this, e, 22, WaStatusUi.TEXT, false);
            t.setPadding(dp(6), dp(4), dp(6), dp(4));
            t.setContentDescription("Sisipkan " + e);
            t.setOnClickListener(v -> on.pick(e));
            row.addView(t);
        }
        hs.addView(row);
        return hs;
    }

    private static void insert(EditText et, String s) {
        int a = Math.max(0, et.getSelectionStart());
        int b = Math.max(0, et.getSelectionEnd());
        et.getText().replace(Math.min(a, b), Math.max(a, b), s);
        et.requestFocus();
    }

    private static TextWatcher watcher(Runnable r) {
        return new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { r.run(); }
        };
    }

    private static boolean contains(@Nullable JSONArray arr, String v) {
        for (int i = 0; arr != null && i < arr.length(); i++) {
            if (v.equals(arr.optString(i))) return true;
        }
        return false;
    }

    private void alert(String title, String msg) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(msg).setPositiveButton("OK", null).show();
    }

    @Nullable private TextView progressText;

    private void showProgress(String text) {
        hideProgress();
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        int p = dp(20);
        l.setPadding(p, p, p, p);
        l.addView(new ProgressBar(this));
        progressText = WaStatusUi.text(this, text, 15, WaStatusUi.TEXT, false);
        progressText.setPadding(dp(16), 0, 0, 0);
        l.addView(progressText);
        progress = new AlertDialog.Builder(this).setView(l).setCancelable(false).show();
    }

    private void setProgressText(String s) {
        if (progressText != null) progressText.setText(s);
    }

    private void hideProgress() {
        if (progress != null) {
            progress.dismiss();
            progress = null;
        }
        progressText = null;
    }
}
