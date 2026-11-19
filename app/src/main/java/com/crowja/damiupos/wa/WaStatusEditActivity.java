package com.crowja.damiupos.wa;

import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.Manifest;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
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
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
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
import java.util.UUID;

/**
 * Form tambah / ubah Status WhatsApp (Admin) — cermin form web /wa-status/create &amp; /edit: nama, perangkat/akun
 * yang memposting (+ status koneksi Bridge), sumber konten (artikel, artikel bergilir, teks berlatar warna, teks bergilir,
 * gambar, video, koleksi foto bergilir), warna latar &amp; gaya huruf (+ pratinjau), penonton, 🔒 menutup order hari ini, dan jadwal
 * (posting sekarang / jadwalkan / draf; Status yang sudah diproses: Simpan saja / posting ulang / jadwalkan ulang /
 * jadikan draf) + pengulangan. Foto dipilih lewat ACTION_GET_CONTENT (tanpa izin storage), diunggah satu per satu
 * ke {@code status/photos}, lalu tokennya disertakan saat menyimpan. Video (galeri / rekam) dikompres di HP bila perlu
 * ({@link WaStatusVideoPrep}; batas dari {@code form.video}: long_side, max_bytes, potongan ≤ 30 detik, maks. 10) lalu
 * tiap potongan dititipkan ke {@code status/videos} dan tokennya dikirim berurutan di {@code staged_videos[]}. Aturan sebenarnya di server.
 */
public class WaStatusEditActivity extends AppCompatActivity {

    public static final String EXTRA_ID = "status_id";
    public static final String EXTRA_MESSAGE = "message";

    private static final int REQ_IMAGE = 51;
    private static final int REQ_PHOTOS = 52;
    private static final int REQ_VIDEO = 53;
    private static final int REQ_VIDEO_REC = 54;
    private static final int REQ_CAMERA_PERM = 55;
    /**
     * Foto di atas ini (atau di atas {@link #MAX_SIDE} px) dikompres ulang ke JPEG. Server menerima ≤ 8 MB, tapi foto
     * kamera 5-8 MB tak memberi apa-apa di Status (WhatsApp memperkecilnya lagi) dan dulu membuat kiriman ke Bridge
     * kena 413 (badan base64 melewati batas nginx).
     */
    private static final long MAX_UPLOAD = 1_500_000L;
    private static final int MAX_SIDE = 2048;
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
    private View secVideo;
    private ImageView ivVideo;
    private TextView tvVideoInfo, tvTextPreview;
    /** Potongan video baru (sudah dikompres) berurutan; kosong = tak mengganti video. */
    private final List<Picked> newVideo = new ArrayList<>();
    @Nullable private WaStatusVideoPrep prep;
    /** Ukuran keluaran kompres video terpilih (rasio asli) — {@link WaStatusVideoRules#outputSize}. */
    private int[] outSize = {720, 1280};
    /** Kategori artikel bergilir ({@code cycle_categories[]}), urut seperti dari server. */
    private final Map<String, CheckBox> categoryBoxes = new LinkedHashMap<>();
    private WaStatusVideoRules vrules = WaStatusVideoRules.from(null);
    private WaStatusFlow photoGrid;
    private TextView tvPhotoCount;
    /** Koleksi video bergilir ({@code video_cycle}): tiap video satu Status (≤ satu potongan), ditambahkan di belakang. */
    private View secVideoCycle;
    private WaStatusFlow videoGrid;
    private TextView tvVideoCount;
    private final List<Picked> newCycleVideos = new ArrayList<>();
    private final Set<String> removeVideoPaths = new HashSet<>();
    /** Video yang sedang dipilih/dikompres untuk koleksi (bukan sumber "Video"). */
    private boolean pickingCycle;
    private static final int MAX_CYCLE_VIDEOS = 20;

    @Nullable private String scheduledAt;
    @Nullable private Picked newImage;
    private final List<Picked> newPhotos = new ArrayList<>();
    private final Set<String> removePaths = new HashSet<>();
    /** Berkas lokal → token titipan server (dipakai ulang bila simpan pertama ditolak validasi / tak terjawab). */
    private final Map<String, String> tokens = new HashMap<>();
    /**
     * Kunci idempotensi form ini (dibuat tiap form dimuat). "Posting sekarang" bisa lebih lama dari batas tunggu HP; bila
     * balasan hilang lalu Simpan diketuk lagi, server menjawab hasil simpanan pertama — Status tak terposting dua kali.
     */
    private String clientKey = UUID.randomUUID().toString();
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
        if (prep != null) prep.cancel();
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
                newVideo.clear();
                newPhotos.clear();
                removePaths.clear();
                newCycleVideos.clear();
                removeVideoPaths.clear();
                clientKey = UUID.randomUUID().toString();   // form baru (mis. dimuat ulang sesudah "Status berubah")
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
            RadioButton rb = radio(WaStatusUi.sourceIcon(key) + " " + ("text".equals(key) ? "Teks (latar warna)" : s.optString("label")));
            rgSource.addView(rb);
            sourceIds.put(rb.getId(), key);
            if (key.equals(src)) rb.setChecked(true);
        }
        box.addView(rgSource);

        buildArticle(gap);
        buildArticleCycle(gap);
        buildImage(gap);
        buildVideo(gap);
        buildImageCycle(gap);
        buildVideoCycle(gap);
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
        categoryBoxes.clear();
        JSONArray cats = form.optJSONArray("article_categories");
        if (cats == null || cats.length() == 0) return;
        Set<String> chosen = new HashSet<>();
        JSONArray saved = post.optJSONArray("cycle_categories");
        for (int i = 0; saved != null && i < saved.length(); i++) chosen.add(saved.optString(i));
        sec.addView(WaStatusUi.text(this, "Kategori yang digilir (kosong = semua kategori)", 14, WaStatusUi.TEXT, true),
                WaStatusUi.full(dp(8)));
        WaStatusFlow flow = new WaStatusFlow(this, dp(8), dp(2));
        for (int i = 0; i < cats.length(); i++) {
            JSONObject o = cats.optJSONObject(i);
            String name = o != null ? o.optString("name", o.optString("category", "")) : cats.optString(i, "");
            if (name.trim().isEmpty() || categoryBoxes.containsKey(name)) continue;
            CheckBox cb = new CheckBox(this);
            cb.setText(name);
            cb.setTextColor(WaStatusUi.TEXT);
            cb.setChecked(chosen.contains(name));
            categoryBoxes.put(name, cb);
            flow.addView(cb);
        }
        sec.addView(flow, WaStatusUi.full(dp(2)));
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

    private void buildVideo(int gap) {
        LinearLayout sec = section(gap);
        secVideo = sec;
        sec.addView(WaStatusUi.text(this, "Video", 14, WaStatusUi.TEXT, true));
        ivVideo = new ImageView(this);
        ivVideo.setAdjustViewBounds(true);
        ivVideo.setScaleType(ImageView.ScaleType.FIT_START);
        ivVideo.setBackground(WaStatusUi.box(0xFF263238, 0xFFCFD8DC, 8, this));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(160), dp(160));
        lp.topMargin = dp(6);
        sec.addView(ivVideo, lp);
        ivVideo.setVisibility(View.GONE);
        vrules = WaStatusVideoRules.from(form.optJSONObject("video"));
        int savedParts = savedVideoParts();
        boolean saved = savedParts > 0;
        tvVideoInfo = WaStatusUi.text(this, saved ? "🎬 Video tersimpan (" + savedParts + " potongan) — pilih berkas baru hanya bila "
                + "ingin mengganti video."
                : "Belum ada video.", 13, saved ? WaStatusUi.GREEN : WaStatusUi.TEXT_2, saved);
        sec.addView(tvVideoInfo, WaStatusUi.full(dp(4)));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        MaterialButton pick = outlined("🎬 Pilih video");
        pick.setOnClickListener(v -> {
            pickingCycle = false;
            pickVideo();
        });
        row.addView(pick, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        MaterialButton rec = outlined("📹 Rekam");
        rec.setOnClickListener(v -> {
            pickingCycle = false;
            recordVideo();
        });
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        rlp.leftMargin = dp(8);
        row.addView(rec, rlp);
        sec.addView(row, WaStatusUi.full(dp(6)));
        sec.addView(WaStatusUi.text(this, vrules.hint(), 12, WaStatusUi.TEXT_2, false));
    }

    /** Jumlah potongan video yang sudah tersimpan di server ({@code post.video_parts[]}). */
    private int savedVideoParts() {
        if (!"video".equals(post.optString("source_type"))) return 0;
        JSONArray a = post.optJSONArray("video_parts");
        return a != null ? a.length() : 0;
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

    private void buildVideoCycle(int gap) {
        LinearLayout sec = section(gap);
        secVideoCycle = sec;
        sec.addView(WaStatusUi.text(this, "Koleksi video (bergilir)", 14, WaStatusUi.TEXT, true));
        sec.addView(WaStatusUi.note(this, "Tiap kali Status ini tayang, video berikutnya dari koleksi dipasang (urut seperti diunggah), "
                + "lalu kembali ke video pertama. Tiap video maksimal " + vrules.segmentSeconds + " detik (satu Status). Agar benar-benar "
                + "bergilir, isi \"Ulangi tiap … hari\" di bagian Jadwal. Keterangan di bawah dipakai untuk semua video.",
                WaStatusUi.BLUE, WaStatusUi.BLUE_BG), WaStatusUi.full(dp(6)));
        videoGrid = new WaStatusFlow(this, dp(8), dp(8));
        sec.addView(videoGrid, WaStatusUi.full(dp(8)));
        tvVideoCount = WaStatusUi.text(this, "", 12, WaStatusUi.TEXT_2, false);
        sec.addView(tvVideoCount, WaStatusUi.full(dp(4)));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        MaterialButton add = outlined("➕ Tambah video");
        add.setOnClickListener(v -> {
            pickingCycle = true;
            pickVideo();
        });
        row.addView(add, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        MaterialButton rec = outlined("📹 Rekam");
        rec.setOnClickListener(v -> {
            pickingCycle = true;
            recordVideo();
        });
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        rlp.leftMargin = dp(8);
        row.addView(rec, rlp);
        sec.addView(row, WaStatusUi.full(dp(6)));
        sec.addView(WaStatusUi.text(this, "Tambah satu per satu, hingga " + MAX_CYCLE_VIDEOS + " video. Dikompres di HP seperti Status "
                + "video biasa. Centang \"hapus\" untuk membuang video lama dari koleksi.", 12, WaStatusUi.TEXT_2, false));
        renderCycleVideos();
    }

    /** Video koleksi yang tersimpan di server ({@code post.image_paths[]} untuk sumber video_cycle). */
    @Nullable
    private JSONArray savedCycleVideos() {
        return "video_cycle".equals(post.optString("source_type")) ? post.optJSONArray("image_paths") : null;
    }

    private void renderCycleVideos() {
        videoGrid.removeAllViews();
        JSONArray existing = savedCycleVideos();
        int n = 0;
        for (int i = 0; existing != null && i < existing.length(); i++) {
            JSONObject o = existing.optJSONObject(i);
            if (o == null) continue;
            String path = o.optString("path");
            LinearLayout cell = videoCell(o.optString("url"), "#" + (++n));
            CheckBox del = new CheckBox(this);
            del.setText("hapus");
            del.setTextColor(WaStatusUi.TEXT);
            del.setTextSize(12);
            del.setChecked(removeVideoPaths.contains(path));
            del.setOnCheckedChangeListener((b, on) -> {
                if (on) removeVideoPaths.add(path);
                else removeVideoPaths.remove(path);
                updateVideoCount();
            });
            cell.addView(del);
            videoGrid.addView(cell, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        for (Picked p : new ArrayList<>(newCycleVideos)) {
            LinearLayout cell = videoCell(p.file.getAbsolutePath(), "baru");
            TextView x = WaStatusUi.text(this, "✕ batal", 12, WaStatusUi.RED, true);
            x.setPadding(dp(4), dp(8), dp(4), dp(8));
            x.setOnClickListener(v -> {
                newCycleVideos.remove(p);
                tokens.remove(p.file.getAbsolutePath());
                p.file.delete();
                renderCycleVideos();
            });
            cell.addView(x);
            videoGrid.addView(cell, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        updateVideoCount();
    }

    /** Sel koleksi video: cuplikan bingkai pertama (berkas lokal atau URL server) + label. */
    private LinearLayout videoCell(String src, String caption) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setBackground(WaStatusUi.box(0xFF263238, 0xFFCFD8DC, 8, this));
        cell.addView(iv, new LinearLayout.LayoutParams(dp(88), dp(88)));
        cell.addView(WaStatusUi.text(this, "🎬 " + caption, 12, WaStatusUi.TEXT_2, false));
        new Thread(() -> {
            Bitmap frame = null;
            MediaMetadataRetriever r = new MediaMetadataRetriever();
            try {
                if (src.startsWith("http://") || src.startsWith("https://")) r.setDataSource(src, new HashMap<>());
                else r.setDataSource(src);
                frame = r.getFrameAtTime(500_000);
            } catch (Exception | OutOfMemoryError ignored) {
                // tanpa cuplikan
            } finally {
                try {
                    r.release();
                } catch (Exception ignored) {
                    // sudah dilepas
                }
            }
            final Bitmap fb = frame;
            runOnUiThread(() -> {
                if (fb != null && !isDestroyed()) iv.setImageBitmap(fb);
            });
        }).start();
        return cell;
    }

    private int cycleVideoTotal() {
        JSONArray existing = savedCycleVideos();
        int kept = 0;
        for (int i = 0; existing != null && i < existing.length(); i++) {
            JSONObject o = existing.optJSONObject(i);
            if (o != null && !removeVideoPaths.contains(o.optString("path"))) kept++;
        }
        return kept + newCycleVideos.size();
    }

    private void updateVideoCount() {
        if (tvVideoCount == null) return;
        int n = cycleVideoTotal();
        tvVideoCount.setText(n == 0 ? "Belum ada video di koleksi." : n + " video di koleksi"
                + (n > MAX_CYCLE_VIDEOS ? " — maksimal " + MAX_CYCLE_VIDEOS + "!" : "."));
        tvVideoCount.setTextColor(n > MAX_CYCLE_VIDEOS ? WaStatusUi.RED : WaStatusUi.TEXT_2);
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
            updatePreview();
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
            updatePreview();
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
        // Pratinjau Status TEKS WhatsApp: teks putih di atas warna latar terpilih (acak/bergilir → warna pertama).
        sec.addView(WaStatusUi.text(this, "Pratinjau Status teks", 12, WaStatusUi.TEXT_2, false));
        tvTextPreview = new TextView(this);
        tvTextPreview.setTextColor(0xFFFFFFFF);
        tvTextPreview.setTextSize(18);
        tvTextPreview.setGravity(Gravity.CENTER);
        tvTextPreview.setMinHeight(dp(120));
        tvTextPreview.setPadding(dp(16), dp(20), dp(16), dp(20));
        sec.addView(tvTextPreview, WaStatusUi.full(dp(4)));
        sec.addView(WaStatusUi.text(this, "Warna latar", 14, WaStatusUi.TEXT, true), WaStatusUi.full(dp(10)));
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
        spBg.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) { updatePreview(); }
            @Override public void onNothingSelected(AdapterView<?> p) { }
        });

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

    /** Sumber yang memakai kotak teks tunggal: isi Status teks, atau keterangan gambar / video / koleksi foto. */
    private static boolean hasBody(String src) {
        return "text".equals(src) || "image".equals(src) || "image_cycle".equals(src) || "video".equals(src)
                || "video_cycle".equals(src);
    }

    private void updatePreview() {
        if (tvTextPreview == null || spBg == null || etBody == null) return;
        int pos = Math.max(0, spBg.getSelectedItemPosition());
        String hex = pos < bgValues.size() ? bgValues.get(pos) : "";
        if (!hex.startsWith("#")) hex = bgValues.size() > 2 ? bgValues.get(2) : "#128C7E";   // acak/bergilir → warna pertama
        int bg;
        try {
            bg = Color.parseColor(hex);
        } catch (IllegalArgumentException e) {
            bg = 0xFF128C7E;
        }
        tvTextPreview.setBackground(WaStatusUi.box(bg, bg, 10, this));
        String t = "text_cycle".equals(source()) ? (texts.isEmpty() ? "" : texts.get(0).getText().toString())
                : etBody.getText().toString();
        tvTextPreview.setText(t.trim().isEmpty() ? "Tulis teks Status…" : t.trim());
        tvTextPreview.setAlpha(t.trim().isEmpty() ? 0.6f : 1f);
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
        WaStatusUi.hide(secVideo, !"video".equals(src));
        WaStatusUi.hide(secImageCycle, !"image_cycle".equals(src));
        WaStatusUi.hide(secVideoCycle, !"video_cycle".equals(src));
        WaStatusUi.hide(secBody, !hasBody(src));
        tvBodyLabel.setText("text".equals(src) ? "Teks Status" : "Keterangan (opsional)");
        WaStatusUi.hide(secTextCycle, !"text_cycle".equals(src));
        WaStatusUi.hide(secStyle, !("text".equals(src) || "text_cycle".equals(src)));
        updatePreview();
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
        if (hasBody(src)) t.append(' ').append(etBody.getText());
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
        if ((requestCode == REQ_VIDEO || requestCode == REQ_VIDEO_REC) && resultCode == RESULT_OK) {
            Uri u = data != null ? data.getData() : null;
            if (u == null) {
                Toast.makeText(this, "Video tak diterima dari aplikasi kamera/galeri — coba pilih dari galeri.", Toast.LENGTH_LONG).show();
                return;
            }
            if (secVideo == null || secVideoCycle == null) {
                Toast.makeText(this, "Form belum siap — pilih ulang videonya sebentar lagi.", Toast.LENGTH_LONG).show();
                return;
            }
            handleVideo(u);
            return;
        }
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
                } catch (OutOfMemoryError e) {
                    // Error (bukan Exception): tanpa ini thread latar ini mematikan aplikasi di HP bermemori kecil.
                    err = "Foto terlalu besar untuk diproses di HP ini — pilih foto lain.";
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

    /** Salin foto ke cache; di atas 1,5 MB / 2048 px / format lain → JPEG (≤ 2048 px, ≤ 1,5 MB, orientasi EXIF diterapkan). */
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
        if (supported && raw.length() <= MAX_UPLOAD && Math.max(o.outWidth, o.outHeight) <= MAX_SIDE) {
            File f = new File(dir, "st_" + System.nanoTime() + ("image/png".equals(mime) ? ".png" : "image/webp".equals(mime) ? ".webp" : ".jpg"));
            if (!raw.renameTo(f)) f = raw;
            return new Picked(f, mime);
        }
        int sample = 1;
        while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2;
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = sample;
        Bitmap bm;
        try {
            bm = BitmapFactory.decodeFile(raw.getPath(), d);
        } catch (OutOfMemoryError oom) {
            d.inSampleSize = sample * 2;   // HP bermemori kecil: separuh resolusi (masih ≥ 1280 px sisi panjang)
            bm = BitmapFactory.decodeFile(raw.getPath(), d);
        }
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
        if (bm.hasAlpha()) {   // PNG/WebP transparan → JPEG: latar putih, bukan hitam
            Bitmap flat = Bitmap.createBitmap(bm.getWidth(), bm.getHeight(), Bitmap.Config.ARGB_8888);
            flat.eraseColor(0xFFFFFFFF);
            new android.graphics.Canvas(flat).drawBitmap(bm, 0, 0, null);
            bm.recycle();
            bm = flat;
        }
        File out = new File(dir, "st_" + System.nanoTime() + ".jpg");
        int q = 85;
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

    // ------------------------------------------------------------------ video

    private void pickVideo() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("video/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(Intent.createChooser(i, "Pilih video"), REQ_VIDEO);
        } catch (Exception e) {
            Toast.makeText(this, "Tidak dapat membuka galeri.", Toast.LENGTH_SHORT).show();
        }
    }

    private void recordVideo() {
        // Aplikasi ini mendeklarasikan izin CAMERA (selfie absen) → ACTION_VIDEO_CAPTURE wajib izinnya sudah diberikan.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, REQ_CAMERA_PERM);
            return;
        }
        Intent i = new Intent(MediaStore.ACTION_VIDEO_CAPTURE);
        i.putExtra(MediaStore.EXTRA_DURATION_LIMIT, (int) ((pickingCycle ? vrules.segmentMs() : vrules.maxTotalMs()) / 1000));
        try {
            startActivityForResult(i, REQ_VIDEO_REC);
        } catch (Exception e) {
            Toast.makeText(this, "Tidak dapat membuka kamera.", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQ_CAMERA_PERM) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) recordVideo();
        else Toast.makeText(this, "Izin kamera diperlukan untuk merekam video.", Toast.LENGTH_LONG).show();
    }

    private static final class VideoMeta {
        long durationMs, bytes = -1;
        int width, height, rotation;
        String mime;
        boolean hasVideo;
    }

    private VideoMeta probe(Uri uri) {
        VideoMeta m = new VideoMeta();
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(this, uri);
            m.hasVideo = "yes".equals(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO));
            m.durationMs = parseLong(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
            m.width = (int) parseLong(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            m.height = (int) parseLong(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            m.rotation = (int) parseLong(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION));
            m.mime = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE);
        } catch (Exception e) {
            m.hasVideo = false;
        } finally {
            try {
                r.release();
            } catch (Exception ignored) {
                // sudah dilepas
            }
        }
        String type = getContentResolver().getType(uri);
        if (type != null && type.startsWith("video/")) m.mime = type;
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) m.bytes = c.getLong(0);
        } catch (Exception ignored) {
            // ukuran tak diketahui → dianggap perlu dikompres
        }
        if (m.bytes < 0 && "file".equals(uri.getScheme()) && uri.getPath() != null) m.bytes = new File(uri.getPath()).length();
        return m;
    }

    private static long parseLong(@Nullable String s) {
        try {
            return s == null ? 0 : Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void handleVideo(Uri uri) {
        showProgress("Membaca video…");
        new Thread(() -> {
            VideoMeta m = probe(uri);
            runOnUiThread(() -> {
                hideProgress();
                if (isFinishing() || isDestroyed()) return;
                if (!m.hasVideo || m.durationMs <= 0) {
                    alert("Video tak bisa dibaca", "Berkas yang dipilih bukan video yang bisa diputar HP ini.");
                    return;
                }
                // Sudah MP4 kecil, pendek, dan ≤ 1080×1920 → dipakai apa adanya (satu potongan, kualitas asli).
                if (vrules.usableAsIs(m.mime, m.bytes, m.durationMs, m.width, m.height)) {
                    copyVideo(uri, m.durationMs);
                    return;
                }
                List<long[]> plan = vrules.plan(m.durationMs);
                if (pickingCycle && plan.size() > 1) {
                    alert("Video terlalu panjang", "Video ini " + WaStatusVideoRules.clock(m.durationMs) + ". Tiap video koleksi maksimal "
                            + vrules.segmentSeconds + " detik (satu Status). Potong dulu di galeri, atau pakai sumber \"Video\" yang "
                            + "memotongnya jadi beberapa Status.");
                    return;
                }
                if (vrules.tooLong(m.durationMs)) {
                    new AlertDialog.Builder(this).setTitle("Video terlalu panjang")
                            .setMessage("Video ini " + WaStatusVideoRules.clock(m.durationMs) + ". Maksimal " + vrules.maxParts
                                    + " potongan × " + vrules.segmentSeconds + " detik (" + WaStatusVideoRules.clock(vrules.maxTotalMs())
                                    + ").\n\nPakai " + WaStatusVideoRules.clock(vrules.maxTotalMs()) + " pertama saja? (Atau potong dulu "
                                    + "di galeri, lalu pilih lagi.)")
                            .setPositiveButton("Pakai bagian awal", (d, w) -> transcodeParts(uri, plan, 0, new ArrayList<>()))
                            .setNegativeButton("Batal", null)
                            .show();
                    return;
                }
                outSize = vrules.outputSize(m.width, m.height, m.rotation);
                transcodeParts(uri, plan, 0, new ArrayList<>());
            });
        }).start();
    }

    /** Kompres potongan ke-{@code idx} dari {@code plan}, lalu lanjut ke berikutnya; selesai → {@link #setVideo}. */
    private void transcodeParts(Uri uri, List<long[]> plan, int idx, List<Picked> done) {
        if (idx >= plan.size()) {
            hideProgress();
            long total = plan.isEmpty() ? 0 : plan.get(plan.size() - 1)[1];
            deliverVideo(done, total);
            return;
        }
        long[] seg = plan.get(idx);
        int count = plan.size();
        String label = count > 1 ? "Mengompres potongan " + (idx + 1) + "/" + count + "… " : "Mengompres video… ";
        File out = new File(cacheDir(), "vid_" + System.nanoTime() + ".mp4");
        showProgress(label + "0%");
        try {
            prep = WaStatusVideoPrep.start(this, uri, seg[0], seg[1], outSize[0], outSize[1], vrules.videoBitrate(seg[1] - seg[0]), out,
                    new WaStatusVideoPrep.Callback() {
                        @Override
                        public void progress(int percent) {
                            setProgressText(label + percent + "%");
                        }

                        @Override
                        public void done(File f) {
                            prep = null;
                            if (isFinishing() || isDestroyed()) return;
                            String bad = vrules.partCheck(idx + 1, count, f.length(), seg[1] - seg[0]);
                            if (bad != null) {
                                hideProgress();
                                f.delete();
                                for (Picked p : done) p.file.delete();
                                alert("Video belum bisa dipakai", bad + "\n\nPilih video yang lebih sederhana / pendek.");
                                return;
                            }
                            done.add(new Picked(f, "video/mp4"));
                            transcodeParts(uri, plan, idx + 1, done);
                        }

                        @Override
                        public void failed(String message) {
                            prep = null;
                            hideProgress();
                            for (Picked p : done) p.file.delete();
                            if (isFinishing() || isDestroyed()) return;
                            alert("Video gagal dikompres", message + "\n\nPotong / perkecil dulu videonya, lalu pilih lagi.");
                        }
                    });
        } catch (Exception e) {
            prep = null;
            hideProgress();
            for (Picked p : done) p.file.delete();
            alert("Video gagal dikompres", "Video tak bisa dikompres di HP ini.");
        }
    }

    /** Video yang sudah memenuhi batas: disalin apa adanya ke cache (kualitas asli). */
    private void copyVideo(Uri uri, long durationMs) {
        showProgress("Menyiapkan video…");
        new Thread(() -> {
            File f = new File(cacheDir(), "vid_" + System.nanoTime() + ".mp4");
            String err = null;
            try (InputStream in = getContentResolver().openInputStream(uri); OutputStream out = new FileOutputStream(f)) {
                if (in == null) throw new IOException("Video tak bisa dibaca.");
                byte[] buf = new byte[65536];
                long total = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > vrules.maxBytes) throw new IOException(vrules.partCheck(1, 1, total, durationMs));
                    out.write(buf, 0, n);
                }
            } catch (Exception e) {
                err = e.getMessage() != null ? e.getMessage() : "Video tak bisa dibaca.";
            }
            final String fe = err != null ? err : vrules.partCheck(1, 1, f.length(), durationMs);
            runOnUiThread(() -> {
                hideProgress();
                if (isFinishing() || isDestroyed()) return;
                if (fe != null) {
                    f.delete();
                    alert("Video belum bisa dipakai", fe);
                    return;
                }
                List<Picked> one = new ArrayList<>();
                one.add(new Picked(f, "video/mp4"));
                deliverVideo(one, durationMs);
            });
        }).start();
    }

    /** Hasil pilih/kompres: masuk koleksi video bergilir, atau mengganti video sumber "Video". */
    private void deliverVideo(List<Picked> parts, long durationMs) {
        if (!pickingCycle) {
            setVideo(parts, durationMs);
            return;
        }
        newCycleVideos.addAll(parts);
        renderCycleVideos();
        if (cycleVideoTotal() > MAX_CYCLE_VIDEOS) alert("Koleksi penuh", "Koleksi maksimal " + MAX_CYCLE_VIDEOS + " video — hapus beberapa dulu.");
    }

    private void setVideo(List<Picked> parts, long durationMs) {
        for (Picked p : newVideo) {
            tokens.remove(p.file.getAbsolutePath());
            p.file.delete();
        }
        newVideo.clear();
        newVideo.addAll(parts);
        long bytes = 0;
        for (Picked p : parts) bytes += p.file.length();
        tvVideoInfo.setText("🎬 " + WaStatusVideoRules.clock(durationMs) + " · " + parts.size() + " potongan · "
                + WaStatusVideoRules.kb(bytes) + " — siap diunggah saat disimpan.");
        tvVideoInfo.setTextColor(WaStatusUi.GREEN);
        ivVideo.setImageDrawable(null);
        ivVideo.setVisibility(View.VISIBLE);
        if (parts.isEmpty()) return;
        File f = parts.get(0).file;
        new Thread(() -> {
            Bitmap frame = null;
            MediaMetadataRetriever r = new MediaMetadataRetriever();
            try {
                r.setDataSource(f.getAbsolutePath());
                frame = r.getFrameAtTime(0);
            } catch (Exception ignored) {
                // tanpa cuplikan
            } finally {
                try {
                    r.release();
                } catch (Exception ignored) {
                    // sudah dilepas
                }
            }
            final Bitmap fb = frame;
            runOnUiThread(() -> {
                if (fb != null && !isDestroyed() && !newVideo.isEmpty() && newVideo.get(0).file.equals(f)) ivVideo.setImageBitmap(fb);
            });
        }).start();
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
        if ("video".equals(src) && newVideo.isEmpty() && savedVideoParts() == 0) {
            alert("Periksa isian", "Pilih atau rekam video untuk Status.");
            return;
        }
        if ("video_cycle".equals(src) && cycleVideoTotal() == 0) {
            alert("Periksa isian", "Tambahkan minimal satu video ke koleksi.");
            return;
        }
        if ("video_cycle".equals(src) && cycleVideoTotal() > MAX_CYCLE_VIDEOS) {
            alert("Periksa isian", "Koleksi maksimal " + MAX_CYCLE_VIDEOS + " video.");
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
        if ("video".equals(src)) uploads.addAll(newVideo);
        if ("video_cycle".equals(src)) uploads.addAll(newCycleVideos);
        final boolean video = "video".equals(src) || "video_cycle".equals(src);
        saving = true;
        btnSave.setEnabled(false);
        showProgress(uploads.isEmpty() ? savingText(when) : video ? "Mengunggah video…" : "Mengunggah foto…");
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
                        runOnUiThread(() -> setProgressText(video ? "Mengunggah video " + n + "/" + uploads.size() + " ("
                                + WaStatusVideoRules.kb(p.file.length()) + ")…"
                                : "Mengunggah foto " + n + "/" + uploads.size() + "…"));
                        JSONObject up = video
                                ? api.adminWaUpload("status/videos", new HashMap<>(), "video", p.file, p.mime, staffUuid)
                                : api.adminWaUpload("status/photos", new HashMap<>(), "image", p.file, p.mime, staffUuid);
                        token = up.optString("token", "");
                        tokens.put(key, token);
                    }
                    staged.put(token);
                }
                if ("image".equals(src) && staged.length() > 0) body.put("staged_image", staged.getString(0));
                if (video && staged.length() > 0) body.put("staged_videos", staged);   // urutan = urutan tayang
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
            // Tak ada jawaban (putus / server masih memposting lebih lama dari batas tunggu HP): simpanan tadi mungkin sudah
            // masuk. Kirim ulang aman — kunci form yang sama membuat server menjawab hasil simpanan itu, bukan memposting
            // lagi; token foto disimpan karena kiriman ulang tak memakainya dua kali.
            alert("Belum ada jawaban server", msg + "\n\nSimpanan tadi mungkin sudah masuk (posting bisa butuh waktu). Ketuk "
                    + "simpan lagi untuk melihat hasilnya — Status tidak akan dibuat atau diposting dua kali.");
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
        b.put("client_key", clientKey);
        b.put("name", etName.getText().toString().trim());
        b.put("source_type", src);
        b.put("when", when);
        String fs = WaStatusUi.str(post, "form_state");
        if (fs != null) b.put("form_state", fs);
        JSONArray acc = new JSONArray();
        for (Map.Entry<String, CheckBox> e : accountBoxes.entrySet()) if (e.getValue().isChecked()) acc.put(e.getKey());
        b.put("accounts", acc);
        if (hasBody(src)) b.put("body", etBody.getText().toString());
        if ("text_cycle".equals(src)) {
            JSONArray t = new JSONArray();
            for (EditText e : texts) t.put(e.getText().toString());
            b.put("texts", t);
        }
        if ("article_cycle".equals(src)) {
            JSONArray cats = new JSONArray();
            for (Map.Entry<String, CheckBox> e : categoryBoxes.entrySet()) if (e.getValue().isChecked()) cats.put(e.getKey());
            b.put("cycle_categories", cats);
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
        if ("image_cycle".equals(src) || "video_cycle".equals(src)) {
            JSONArray rm = new JSONArray();
            for (String p : "video_cycle".equals(src) ? removeVideoPaths : removePaths) rm.put(p);
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
