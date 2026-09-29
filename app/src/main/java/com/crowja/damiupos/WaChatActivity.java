package com.crowja.damiupos;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.crowja.damiupos.sync.WaInboxApi;
import com.crowja.damiupos.util.BitmapUtils;
import com.crowja.damiupos.util.Ts;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Satu percakapan WhatsApp (tampilan ala WhatsApp): bubble masuk/keluar, kutipan, gambar, voice note
 * (putar), dokumen, reaksi emoji, panel emoji, lampiran, rekam &amp; kirim voice note, balas-kutip.
 * Ketuk-tahan bubble → Balas / Reaksi / Salin. Di-poll tiap {@link #POLL_MS} selama layar aktif;
 * membuka layar / pesan masuk baru menandai chat dibaca di server ({@link WaInboxApi#markRead}).
 */
public class WaChatActivity extends AppCompatActivity {

    public static final String EXTRA_ACCOUNT = "wa_account";
    public static final String EXTRA_JID = "wa_jid";
    public static final String EXTRA_TITLE = "wa_title";
    public static final String EXTRA_PHONE = "wa_phone";
    public static final String EXTRA_COMPLAINT = "wa_complaint";

    private static final long POLL_MS = 6000L;
    private static final long ATTACH_MAX = 3_000_000L;
    private static final int REQ_PICK = 7401;
    private static final int REQ_MIC = 7402;
    private static final String[] QUICK_REACTIONS = {"👍", "❤️", "😂", "😮", "😢", "🙏"};
    private static final String[] EMOJIS = ("😀 😃 😄 😁 😆 😅 😂 🤣 🙂 😉 😊 😇 🥰 😍 😘 😋 😎 🤔 😐 😴 😢 😭 😡 😱 🙏 👍 👎 👏 🙌 💪 👋 ✌️ "
            + "👌 🤝 ❤️ 💙 💚 💛 🔥 ✨ 🎉 ✅ ❌ ⚠️ 💯 💧 🚚 📦 🏠 📍 📞 💰 🧾 ⏰ 🙇 😅 🤗 😥 😔 😞").split(" ");

    private WaInboxApi api;
    private String account, jid, title;
    private RecyclerView rv;
    private MsgAdapter adapter;
    private EditText etMessage;
    private TextView btnSend, btnAttach, btnEmoji, tvStatus, tvReply;
    private View replyBar;
    private GridView emojiPanel;
    private ProgressBarHolder progress;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable poll = this::pollNow;
    private boolean resumed, polling, firstLoad = true;
    private String lastSig = "";
    private long lastReadInbound;

    private final List<Msg> server = new ArrayList<>();
    private final List<Msg> pending = new ArrayList<>();
    private Msg replyTo;

    // rekam voice note
    private MediaRecorder recorder;
    private File recFile;
    private String recMime;
    private long recStart;
    private final Runnable recTick = new Runnable() {
        @Override public void run() {
            if (recorder == null) return;
            long s = (System.currentTimeMillis() - recStart) / 1000;
            etMessage.setHint("⏺ Merekam " + s / 60 + ":" + String.format(Locale.US, "%02d", s % 60) + " — ketuk ➤ untuk kirim");
            handler.postDelayed(this, 500);
        }
    };

    // putar voice note
    private MediaPlayer player;
    private String playingId;
    private final Map<String, File> mediaCache = new HashMap<>();

    static final class ProgressBarHolder {
        final View v;
        ProgressBarHolder(View v) { this.v = v; }
    }

    static final class Msg {
        String id, direction, type, text, sender, quotedId, quotedText, mime, ts, localStatus;
        boolean hasMedia, deleted, edited;
        int seconds;
        List<String> reactions = new ArrayList<>();
        boolean out() { return "out".equals(direction); }
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_wa_chat);
        api = new WaInboxApi(this);
        account = getIntent().getStringExtra(EXTRA_ACCOUNT);
        jid = getIntent().getStringExtra(EXTRA_JID);
        title = getIntent().getStringExtra(EXTRA_TITLE);
        if (account == null || jid == null) { finish(); return; }

        Toolbar tb = findViewById(R.id.toolbar);
        setSupportActionBar(tb);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayShowTitleEnabled(false);
        tb.setTitle(title);
        String phone = getIntent().getStringExtra(EXTRA_PHONE);
        tb.setSubtitle((phone != null && !phone.isEmpty() ? "+" + phone + " · " : "") + "akun " + account);
        tb.setNavigationOnClickListener(v -> finish());
        findViewById(R.id.tvComplaintBanner).setVisibility(
                getIntent().getBooleanExtra(EXTRA_COMPLAINT, false) ? View.VISIBLE : View.GONE);

        tvStatus = findViewById(R.id.tvStatus);
        progress = new ProgressBarHolder(findViewById(R.id.progress));
        rv = findViewById(R.id.rvMessages);
        LinearLayoutManager lm = new LinearLayoutManager(this);
        lm.setStackFromEnd(true);
        rv.setLayoutManager(lm);
        adapter = new MsgAdapter();
        rv.setAdapter(adapter);

        etMessage = findViewById(R.id.etMessage);
        btnSend = findViewById(R.id.btnSend);
        btnAttach = findViewById(R.id.btnAttach);
        btnEmoji = findViewById(R.id.btnEmoji);
        replyBar = findViewById(R.id.replyBar);
        tvReply = findViewById(R.id.tvReply);
        emojiPanel = findViewById(R.id.emojiPanel);

        emojiPanel.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, EMOJIS) {
            @NonNull @Override
            public View getView(int pos, View cv, @NonNull ViewGroup parent) {
                TextView t = (TextView) super.getView(pos, cv, parent);
                t.setText(EMOJIS[pos]);
                t.setTextSize(24);
                t.setGravity(Gravity.CENTER);
                return t;
            }
        });
        emojiPanel.setOnItemClickListener((p, v, pos, id) -> {
            int s = Math.max(0, etMessage.getSelectionStart());
            etMessage.getText().insert(s, EMOJIS[pos]);
        });
        btnEmoji.setOnClickListener(v -> emojiPanel.setVisibility(emojiPanel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        etMessage.setOnClickListener(v -> emojiPanel.setVisibility(View.GONE));
        etMessage.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int c, int d) {}
            @Override public void onTextChanged(CharSequence s, int a, int c, int d) {}
            @Override public void afterTextChanged(Editable s) { updateSendIcon(); }
        });
        btnSend.setOnClickListener(v -> onSendClicked());
        btnAttach.setOnClickListener(v -> {
            if (recorder != null) cancelRecording(); else pickFile();
        });
        findViewById(R.id.btnReplyCancel).setOnClickListener(v -> setReply(null));
        updateSendIcon();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        pollNow();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        handler.removeCallbacks(poll);
        if (recorder != null) cancelRecording();
        stopPlayback();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopPlayback();
    }

    // ------------------------------------------------------------------------------- polling

    private void pollNow() {
        handler.removeCallbacks(poll);
        if (polling || !resumed) return;
        polling = true;
        if (firstLoad) progress.v.setVisibility(View.VISIBLE);
        new Thread(() -> {
            WaInboxApi.Res r = api.messages(account, jid, 200);
            runOnUiThread(() -> {
                polling = false;
                progress.v.setVisibility(View.GONE);
                if (isFinishing() || isDestroyed()) return;
                if (r.ok()) {
                    showStatus(null);
                    applyMessages(r.body.optJSONArray("messages"));
                } else if (r.status == 404) {
                    // chat belum ada di cache Bridge — kosong, bukan galat
                    if (firstLoad) applyMessages(new JSONArray());
                } else {
                    showStatus(r.error.isEmpty() ? "Gagal memuat pesan." : r.error);
                }
                firstLoad = false;
                if (resumed) handler.postDelayed(poll, POLL_MS);
            });
        }, "wa-chat-poll").start();
    }

    private void applyMessages(JSONArray arr) {
        List<Msg> list = new ArrayList<>();
        StringBuilder sig = new StringBuilder();
        long newestIn = 0;
        for (int i = 0; arr != null && i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            Msg m = new Msg();
            m.id = o.optString("id");
            m.direction = o.optString("direction");
            m.type = o.optString("type", "text");
            m.text = o.optString("text");
            m.sender = o.isNull("sender_name") ? null : o.optString("sender_name", null);
            m.quotedId = o.isNull("quoted_id") ? null : o.optString("quoted_id", null);
            m.quotedText = o.isNull("quoted_text") ? null : o.optString("quoted_text", null);
            m.hasMedia = o.optBoolean("has_media");
            m.mime = o.isNull("media_mimetype") ? null : o.optString("media_mimetype", null);
            m.seconds = o.optInt("media_seconds");
            m.deleted = o.optBoolean("deleted");
            m.edited = o.optBoolean("edited");
            m.ts = o.optString("timestamp");
            JSONArray rs = o.optJSONArray("reactions");
            for (int j = 0; rs != null && j < rs.length(); j++) {
                Object x = rs.opt(j);
                String e = x instanceof JSONObject ? ((JSONObject) x).optString("emoji", ((JSONObject) x).optString("text")) : String.valueOf(x);
                if (e != null && !e.isEmpty() && !"null".equals(e)) m.reactions.add(e);
            }
            list.add(m);
            sig.append(m.id).append(m.reactions.size()).append(m.deleted).append(m.edited).append(';');
            if (!m.out()) newestIn = Math.max(newestIn, Ts.millis(m.ts) == Long.MAX_VALUE ? 0 : Ts.millis(m.ts));
        }
        if (newestIn > lastReadInbound) {
            lastReadInbound = newestIn;
            final String a = account, j = jid;
            new Thread(() -> api.markRead(a, j), "wa-chat-read").start();
        }
        // pesan lokal yang sudah muncul di server (id klien tak sama → buang yang berhasil saat kirim)
        String s = sig.toString();
        if (s.equals(lastSig)) return;
        lastSig = s;
        boolean nearBottom = isNearBottom();
        server.clear();
        server.addAll(list);
        redraw(nearBottom || firstLoad);
    }

    private boolean isNearBottom() {
        LinearLayoutManager lm = (LinearLayoutManager) rv.getLayoutManager();
        return lm == null || adapter.getItemCount() == 0
                || lm.findLastVisibleItemPosition() >= adapter.getItemCount() - 3;
    }

    private void redraw(boolean scroll) {
        List<Msg> all = new ArrayList<>(server);
        all.addAll(pending);
        adapter.set(all);
        if (scroll && !all.isEmpty()) rv.scrollToPosition(all.size() - 1);
    }

    private void showStatus(String s) {
        tvStatus.setVisibility(s == null ? View.GONE : View.VISIBLE);
        if (s != null) tvStatus.setText(s);
    }

    // --------------------------------------------------------------------------------- kirim

    private void updateSendIcon() {
        boolean hasText = etMessage.getText().toString().trim().length() > 0;
        btnSend.setText(recorder != null || hasText ? "➤" : "🎤");
        btnAttach.setText(recorder != null ? "🗑" : "📎");
    }

    private void onSendClicked() {
        if (recorder != null) { finishRecordingAndSend(); return; }
        String text = etMessage.getText().toString().trim();
        if (text.isEmpty()) { startRecording(); return; }
        etMessage.setText("");
        emojiPanel.setVisibility(View.GONE);
        sendMessage(text, null, null, null, "text");
    }

    /** @param mediaFile lampiran (null = teks); {@code kind} untuk bubble sementara. */
    private void sendMessage(String text, File mediaFile, String mime, String fileName, String kind) {
        final Msg quoted = replyTo;
        setReply(null);
        final String key = UUID.randomUUID().toString().replace("-", "");
        final Msg local = new Msg();
        local.id = "local:" + key;
        local.direction = "out";
        local.type = kind;
        local.text = text;
        local.hasMedia = mediaFile != null;
        local.mime = mime;
        local.localStatus = "⏳";
        local.ts = "";
        if (quoted != null) { local.quotedId = quoted.id; local.quotedText = quoted.text; }
        pending.add(local);
        redraw(true);
        new Thread(() -> {
            String b64 = null;
            if (mediaFile != null) {
                try { b64 = android.util.Base64.encodeToString(readAll(mediaFile), android.util.Base64.NO_WRAP); }
                catch (Exception ignored) {}
            }
            WaInboxApi.Res r = (mediaFile != null && b64 == null)
                    ? null
                    : api.send(account, jid, text, quoted != null ? quoted.id : null, key, b64, mime, fileName);
            runOnUiThread(() -> {
                pending.remove(local);
                if (r != null && r.ok()) {
                    lastSig = "";   // paksa gambar ulang dari server
                    pollNowSoon();
                } else {
                    String why = r == null ? "Lampiran tidak terbaca." : r.error;
                    if (r != null && r.body != null && r.body.optJSONObject("error") != null) {
                        String m = r.body.optJSONObject("error").optString("message", "");
                        if (!m.isEmpty()) why = m;
                    }
                    Toast.makeText(this, "Gagal mengirim" + (why != null && !why.isEmpty() ? ": " + why : ""),
                            Toast.LENGTH_LONG).show();
                    if (mediaFile == null && etMessage.getText().length() == 0) etMessage.setText(text);
                    redraw(false);
                }
            });
        }, "wa-chat-send").start();
    }

    private void pollNowSoon() {
        handler.removeCallbacks(poll);
        polling = false;
        pollNow();
    }

    private void setReply(Msg m) {
        replyTo = m;
        replyBar.setVisibility(m == null ? View.GONE : View.VISIBLE);
        if (m != null) tvReply.setText(WaInboxActivity.previewOf(m.type, m.text));
    }

    // ------------------------------------------------------------------------------ lampiran

    private void pickFile() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*", "audio/*", "application/pdf",
                "application/*", "text/*"});
        startActivityForResult(Intent.createChooser(i, "Pilih lampiran"), REQ_PICK);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PICK || res != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        new Thread(() -> {
            try {
                String name = "lampiran";
                try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
                    if (c != null && c.moveToFirst()) {
                        int ix = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                        if (ix >= 0) name = c.getString(ix);
                    }
                }
                String mime = getContentResolver().getType(uri);
                if (mime == null) mime = "application/octet-stream";
                File tmp = new File(getCacheDir(), "wa_att_" + System.currentTimeMillis());
                try (InputStream in = getContentResolver().openInputStream(uri); OutputStream os = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[16 * 1024];
                    int n;
                    while (in != null && (n = in.read(buf)) > 0) os.write(buf, 0, n);
                }
                File send = tmp;
                String fmime = mime, fname = name;
                if (mime.startsWith("image/")) {
                    File out = new File(getCacheDir(), "wa_att_" + System.currentTimeMillis() + ".jpg");
                    if (BitmapUtils.compressForUpload(tmp.getPath(), out, 1600, 82)) {
                        send = out;
                        fmime = "image/jpeg";
                        fname = name.replaceAll("\\.[A-Za-z0-9]+$", "") + ".jpg";
                    }
                }
                if (send.length() > ATTACH_MAX) {
                    runOnUiThread(() -> Toast.makeText(this, "Lampiran terlalu besar (maks 3 MB).", Toast.LENGTH_LONG).show());
                    return;
                }
                final File f = send;
                final String fm = fmime, fn = fname;
                runOnUiThread(() -> confirmAttachment(f, fm, fn));
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Lampiran tidak bisa dibaca.", Toast.LENGTH_LONG).show());
            }
        }, "wa-chat-attach").start();
    }

    private void confirmAttachment(File f, String mime, String name) {
        EditText caption = new EditText(this);
        caption.setHint("Keterangan (opsional)");
        caption.setText(etMessage.getText());
        new AlertDialog.Builder(this).setTitle("Kirim " + name + "?").setView(caption)
                .setPositiveButton("Kirim", (d, w) -> {
                    etMessage.setText("");
                    String kind = mime.startsWith("image/") ? "image" : mime.startsWith("video/") ? "video"
                            : mime.startsWith("audio/") ? "audio" : "document";
                    sendMessage(caption.getText().toString().trim(), f, mime, name, kind);
                })
                .setNegativeButton("Batal", null).show();
    }

    private static byte[] readAll(File f) throws Exception {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream((int) Math.min(f.length(), Integer.MAX_VALUE));
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }

    // --------------------------------------------------------------------------- voice note

    private void startRecording() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        try {
            boolean ogg = Build.VERSION.SDK_INT >= 29;
            recFile = new File(getCacheDir(), "wa_vn_" + System.currentTimeMillis() + (ogg ? ".ogg" : ".m4a"));
            recMime = ogg ? "audio/ogg; codecs=opus" : "audio/mp4";
            recorder = new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            if (ogg) {
                recorder.setOutputFormat(MediaRecorder.OutputFormat.OGG);
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS);
            } else {
                recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            }
            recorder.setAudioSamplingRate(ogg ? 48000 : 44100);
            recorder.setAudioEncodingBitRate(ogg ? 32000 : 64000);
            recorder.setOutputFile(recFile.getPath());
            recorder.prepare();
            recorder.start();
            recStart = System.currentTimeMillis();
            handler.post(recTick);
            updateSendIcon();
        } catch (Exception e) {
            releaseRecorder();
            Toast.makeText(this, "Tidak bisa merekam suara.", Toast.LENGTH_LONG).show();
        }
    }

    private void finishRecordingAndSend() {
        long ms = System.currentTimeMillis() - recStart;
        File f = recFile;
        String mime = recMime;
        boolean okStop = true;
        try { recorder.stop(); } catch (Exception e) { okStop = false; }
        releaseRecorder();
        if (!okStop || ms < 700 || f == null || f.length() == 0) {
            Toast.makeText(this, "Rekaman terlalu singkat.", Toast.LENGTH_SHORT).show();
            return;
        }
        sendMessage("", f, mime, f.getName(), "audio");
    }

    private void cancelRecording() {
        try { recorder.stop(); } catch (Exception ignored) {}
        File f = recFile;
        releaseRecorder();
        if (f != null) //noinspection ResultOfMethodCallIgnored
            f.delete();
    }

    private void releaseRecorder() {
        handler.removeCallbacks(recTick);
        try { if (recorder != null) recorder.release(); } catch (Exception ignored) {}
        recorder = null;
        etMessage.setHint("Ketik pesan");
        updateSendIcon();
    }

    @Override
    public void onRequestPermissionsResult(int req, @NonNull String[] perms, @NonNull int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        if (req == REQ_MIC && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) startRecording();
        else if (req == REQ_MIC) Toast.makeText(this, "Izin mikrofon diperlukan untuk voice note.", Toast.LENGTH_LONG).show();
    }

    // -------------------------------------------------------------------------- media tampil

    private File mediaFile(Msg m) {
        String ext = m.mime == null ? "bin" : m.mime.contains("ogg") ? "ogg" : m.mime.contains("mp4") ? "m4a"
                : m.mime.contains("jpeg") ? "jpg" : m.mime.contains("png") ? "png" : m.mime.contains("pdf") ? "pdf"
                : m.mime.contains("webp") ? "webp" : "bin";
        File dir = getExternalFilesDir("wa_media");
        if (dir == null) dir = getCacheDir();
        return new File(dir, m.id.replaceAll("[^A-Za-z0-9_-]", "_") + "." + ext);
    }

    /** Pastikan lampiran ada di disk, lalu {@code done} (UI thread) dengan berkas atau null. */
    private void withMedia(Msg m, java.util.function.Consumer<File> done) {
        File cached = mediaCache.get(m.id);
        if (cached != null && cached.exists()) { done.accept(cached); return; }
        File f = mediaFile(m);
        if (f.exists() && f.length() > 0) { mediaCache.put(m.id, f); done.accept(f); return; }
        new Thread(() -> {
            String err = api.downloadMedia(account, m.id, m.mime, f);
            runOnUiThread(() -> {
                if (err != null) {
                    Toast.makeText(this, err, Toast.LENGTH_LONG).show();
                    done.accept(null);
                } else {
                    mediaCache.put(m.id, f);
                    done.accept(f);
                }
            });
        }, "wa-chat-media").start();
    }

    private void openMedia(Msg m) {
        withMedia(m, f -> {
            if (f == null) return;
            if (m.mime != null && m.mime.startsWith("audio/")) { togglePlayback(m, f); return; }
            try {
                Uri u = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", f);
                startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(u,
                        m.mime != null ? m.mime : "*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
            } catch (Exception e) {
                Toast.makeText(this, "Tidak ada aplikasi untuk membuka lampiran ini.", Toast.LENGTH_LONG).show();
            }
        });
    }

    private void togglePlayback(Msg m, File f) {
        boolean same = m.id.equals(playingId);
        stopPlayback();
        if (same) return;
        try {
            player = new MediaPlayer();
            player.setDataSource(f.getPath());
            player.setOnCompletionListener(mp -> { stopPlayback(); adapter.notifyDataSetChanged(); });
            player.prepare();
            player.start();
            playingId = m.id;
        } catch (Exception e) {
            stopPlayback();
            Toast.makeText(this, "Voice note tidak bisa diputar.", Toast.LENGTH_LONG).show();
        }
        adapter.notifyDataSetChanged();
    }

    private void stopPlayback() {
        try { if (player != null) player.release(); } catch (Exception ignored) {}
        player = null;
        playingId = null;
    }

    // ----------------------------------------------------------------------- aksi pada bubble

    private void showActions(Msg m) {
        if (m.id.startsWith("local:")) return;
        String[] items = {"↩️ Balas", "😊 Beri reaksi", "📋 Salin teks"};
        new AlertDialog.Builder(this).setItems(items, (d, which) -> {
            if (which == 0) { setReply(m); etMessage.requestFocus(); }
            else if (which == 1) showReactionPicker(m);
            else {
                ((ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE))
                        .setPrimaryClip(ClipData.newPlainText("pesan", m.text));
                Toast.makeText(this, "Disalin.", Toast.LENGTH_SHORT).show();
            }
        }).show();
    }

    private void showReactionPicker(Msg m) {
        String[] all = new String[QUICK_REACTIONS.length + 1];
        System.arraycopy(QUICK_REACTIONS, 0, all, 0, QUICK_REACTIONS.length);
        all[QUICK_REACTIONS.length] = "✖ Cabut reaksi";
        new AlertDialog.Builder(this).setTitle("Reaksi").setItems(all, (d, which) -> {
            String emoji = which < QUICK_REACTIONS.length ? QUICK_REACTIONS[which] : "";
            new Thread(() -> {
                WaInboxApi.Res r = api.react(account, jid, m.id, emoji, m.out());
                runOnUiThread(() -> {
                    if (r.ok()) { lastSig = ""; pollNowSoon(); }
                    else {
                        String msg = r.status == 501 ? "Bridge belum mendukung reaksi."
                                : (r.error.isEmpty() ? "Reaksi gagal." : r.error);
                        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                    }
                });
            }, "wa-chat-react").start();
        }).show();
    }

    // -------------------------------------------------------------------------------- adapter

    private final class MsgAdapter extends RecyclerView.Adapter<MsgAdapter.VH> {
        private List<Msg> data = new ArrayList<>();
        private final SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.US);
        private final SimpleDateFormat day = new SimpleDateFormat("dd MMM yyyy", new Locale("id", "ID"));

        void set(List<Msg> d) { data = d; notifyDataSetChanged(); }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup p, int t) {
            return new VH(LayoutInflater.from(p.getContext()).inflate(R.layout.item_wa_message, p, false));
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int i) {
            Msg m = data.get(i);
            boolean out = m.out();
            float dp = getResources().getDisplayMetrics().density;

            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) h.bubble.getLayoutParams();
            lp.gravity = out ? Gravity.END : Gravity.START;
            lp.setMarginStart((int) ((out ? 48 : 6) * dp));
            lp.setMarginEnd((int) ((out ? 6 : 48) * dp));
            h.bubble.setLayoutParams(lp);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(10 * dp);
            bg.setColor(Color.parseColor(out ? "#DCF8C6" : "#FFFFFF"));
            h.bubble.setBackground(bg);

            boolean showSender = !out && m.sender != null && !m.sender.isEmpty();
            h.sender.setVisibility(showSender ? View.VISIBLE : View.GONE);
            if (showSender) h.sender.setText(m.sender);
            boolean hasQuote = m.quotedText != null && !m.quotedText.isEmpty();
            h.quote.setVisibility(hasQuote ? View.VISIBLE : View.GONE);
            if (hasQuote) h.quote.setText(m.quotedText);

            h.image.setVisibility(View.GONE);
            h.attach.setVisibility(View.GONE);
            h.image.setOnClickListener(null);
            h.attach.setOnClickListener(null);
            if (m.hasMedia) {
                String mime = m.mime == null ? "" : m.mime;
                if (m.type.equals("image") || m.type.equals("sticker") || mime.startsWith("image/")) {
                    h.image.setVisibility(View.VISIBLE);
                    h.image.setImageDrawable(null);
                    h.image.setTag(m.id);
                    if (!m.id.startsWith("local:")) {
                        withMediaQuiet(m, f -> {
                            if (f == null || !m.id.equals(h.image.getTag())) return;
                            Bitmap bm = decode(f);
                            if (bm != null) h.image.setImageBitmap(bm);
                        });
                    }
                    h.image.setOnClickListener(v -> openMedia(m));
                } else {
                    h.attach.setVisibility(View.VISIBLE);
                    boolean audio = mime.startsWith("audio/") || m.type.equals("audio") || m.type.equals("ptt");
                    boolean playing = m.id.equals(playingId);
                    String len = m.seconds > 0 ? " " + m.seconds / 60 + ":" + String.format(Locale.US, "%02d", m.seconds % 60) : "";
                    h.attach.setText(audio ? (playing ? "⏸ Pesan suara" : "▶ Pesan suara") + len
                            : (m.type.equals("video") ? "🎥 Video — ketuk untuk membuka" : "📄 Lampiran — ketuk untuk membuka"));
                    if (!m.id.startsWith("local:")) h.attach.setOnClickListener(v -> openMedia(m));
                }
            }

            String text = m.deleted ? "🚫 Pesan ini telah dihapus" : m.text;
            boolean textEmpty = text == null || text.isEmpty();
            h.text.setVisibility(textEmpty ? View.GONE : View.VISIBLE);
            h.text.setText(text);
            h.text.setTypeface(null, m.deleted ? android.graphics.Typeface.ITALIC : android.graphics.Typeface.NORMAL);

            h.reactions.setVisibility(m.reactions.isEmpty() ? View.GONE : View.VISIBLE);
            if (!m.reactions.isEmpty()) h.reactions.setText(android.text.TextUtils.join("", m.reactions));

            long ms = Ts.millis(m.ts);
            String time = m.localStatus != null ? m.localStatus : ms == Long.MAX_VALUE ? "" : hm.format(new Date(ms));
            h.meta.setText((m.edited ? "diedit · " : "") + time);

            h.bubble.setOnLongClickListener(v -> { showActions(m); return true; });

            // pemisah tanggal → ditampilkan di atas pesan pertama tiap hari lewat prefix meta bubble pertama
            if (i == 0 || dayOf(data.get(i - 1)) != dayOf(m)) {
                if (ms != Long.MAX_VALUE) h.meta.setText(day.format(new Date(ms)) + " · " + h.meta.getText());
            }
        }

        private long dayOf(Msg m) {
            long ms = Ts.millis(m.ts);
            return ms == Long.MAX_VALUE ? -1 : ms / 86_400_000L;
        }

        @Override public int getItemCount() { return data.size(); }

        final class VH extends RecyclerView.ViewHolder {
            final View bubble;
            final TextView sender, quote, attach, text, reactions, meta;
            final ImageView image;

            VH(View v) {
                super(v);
                bubble = v.findViewById(R.id.bubble);
                sender = v.findViewById(R.id.tvSender);
                quote = v.findViewById(R.id.tvQuote);
                image = v.findViewById(R.id.ivMedia);
                attach = v.findViewById(R.id.tvAttachment);
                text = v.findViewById(R.id.tvText);
                reactions = v.findViewById(R.id.tvReactions);
                meta = v.findViewById(R.id.tvMeta);
            }
        }
    }

    /** Seperti {@link #withMedia} tapi tanpa toast galat (dipakai memuat gambar di daftar). */
    private void withMediaQuiet(Msg m, java.util.function.Consumer<File> done) {
        File cached = mediaCache.get(m.id);
        if (cached != null && cached.exists()) { done.accept(cached); return; }
        File f = mediaFile(m);
        if (f.exists() && f.length() > 0) { mediaCache.put(m.id, f); done.accept(f); return; }
        new Thread(() -> {
            String err = api.downloadMedia(account, m.id, m.mime, f);
            runOnUiThread(() -> {
                if (err == null) mediaCache.put(m.id, f);
                done.accept(err == null ? f : null);
            });
        }, "wa-chat-img").start();
    }

    private static Bitmap decode(File f) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        int s = 1;
        while (o.outWidth / s > 800 || o.outHeight / s > 800) s *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = s;
        return BitmapFactory.decodeFile(f.getPath(), o);
    }
}
