package com.crowja.damiupos;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.crowja.damiupos.adapter.ChatMessageAdapter;
import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.SettingsDao;
import com.crowja.damiupos.model.ChatMessage;
import com.crowja.damiupos.sync.OrderChatOutbox;
import com.crowja.damiupos.sync.SyncApi;
import com.crowja.damiupos.sync.SyncSettings;
import com.crowja.damiupos.util.BitmapUtils;
import com.crowja.damiupos.util.Ts;

import org.json.JSONObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Viewer chat WhatsApp untuk SATU order, dua mode ({@link #EXTRA_MODE}):
 * <ul>
 *   <li>{@link #MODE_COMPLAINT} (bawaan) — log percakapan komplain (badge 😠 "Komplain"), bubble
 *       read-only, diambil sekali on-demand dari server (SyncApi.complaintLog). Diluncurkan dari
 *       TransactionListActivity/DeliveryQueueActivity via menu "Lihat Chat Komplain".</li>
 *   <li>{@link #MODE_ORDER} — "💬 Chat Pesanan": potongan chat WA order agen AI (SyncApi.orderChat),
 *       di-poll tiap 6 detik HANYA selama layar aktif (cursor/rev inkremental), plus kotak balas yang
 *       mengirim lewat FREZ WA Bridge pada akun + chat yang SAMA dengan asal order. Tiap kirim punya
 *       client_key sendiri (kunci idempoten server) dan dijalankan {@link OrderChatOutbox} (tingkat
 *       proses: tetap berjalan setelah Back / putar layar, gelembungnya muncul lagi saat chat dibuka
 *       ulang) — 202/timeout diulang dengan kunci SAMA, gagal final atau "status tak diketahui"
 *       dikirim ulang dengan kunci BARU (aturannya: {@link com.crowja.damiupos.sync.OrderChatSendPolicy}).</li>
 * </ul>
 *
 * <p>Activity klasik biasa (bukan Fragment/Navigation Component/Compose) -- app ini tak punya
 * infrastruktur itu di mana pun, jadi tak diperkenalkan hanya untuk satu layar ini.</p>
 */
public class ChatLogActivity extends AppCompatActivity {

    public static final String EXTRA_TRANSACTION_UUID = "extra_transaction_uuid";
    public static final String EXTRA_CUSTOMER_NAME = "extra_customer_name";
    /** {@link #MODE_COMPLAINT} (bawaan, perilaku lama) | {@link #MODE_ORDER}. */
    public static final String EXTRA_MODE = "extra_mode";
    public static final String MODE_COMPLAINT = "complaint";
    public static final String MODE_ORDER = "order";

    private static final long POLL_MS = 6000L;
    private static final long BACKOFF_FIRST_MS = 12000L;
    private static final long BACKOFF_MAX_MS = 60000L;
    private static final long SEND_MEDIA_MAX = OrderChatOutbox.SEND_MEDIA_MAX;
    private static final int IMAGE_MAX_DIM = 1280;
    private static final String STATE_ATTACH_PATH = "order_chat_attach_path";
    private static final long MEDIA_RELOAD_MIN_GAP_MS = 60000L;
    private static final int REQ_PICK_IMAGE = 7301;
    private static final int MENU_AGENT = 1;
    private static final int MENU_RELOAD = 2;

    private RecyclerView rvMessages;
    private ProgressBar progressBar;
    private TextView tvEmpty;
    private ChatMessageAdapter adapter;
    private Toolbar toolbar;

    // ---------------------------------------------------------------- mode order (Chat Pesanan)
    private boolean orderMode;
    private String transactionUuid;
    private String customerNameExtra;
    private SyncSettings cfg;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable pollRunnable = this::pollNow;
    private boolean resumed;
    private boolean pollInFlight;
    private boolean pollStopped;
    /** Naik tiap kali muat-ulang penuh diminta — respons poll lama (gen beda) dibuang. */
    private int pollGen;
    private String cursor;
    private int windowRev;
    private int failStreak;
    private boolean sessionLoaded;
    private boolean purged;
    private boolean canSend;
    private String sendBlockCode;
    private String sendBlockReason;
    private String sendKind = "reply";
    private String agentPausedUntil;
    private boolean agentBusy;
    /** Isi menu terakhir yang dibangun — lihat {@link #refreshMenuIfChanged()}. */
    private String menuStateKey;
    private long lastMediaReloadAt;
    private String attachPath;
    private boolean attachPreparing;
    private OrderChatOutbox outbox;
    private final OrderChatOutbox.Listener outboxListener = this::onOutboxUpdate;
    /** Satu dialog konfirmasi pada satu waktu: ketuk ganda "Kirim" (mode proaktif) atau gelembung
     *  gagal tak boleh membuka dua dialog — masing-masing akan mengirim dengan client_key sendiri
     *  dan pelanggan menerima pesan dobel. */
    private boolean confirmShowing;

    private TextView tvLiveBanner;
    private TextView tvAgentBanner;
    private TextView tvComposeBlocked;
    private View composeBar;
    private View attachPreview;
    private ImageView imgAttachPreview;
    private TextView btnAttach;
    private EditText etMessage;
    private Button btnSend;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat_log);

        toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> {
            if (!confirmExitIfUnsent()) finish();
        });
        orderMode = MODE_ORDER.equals(getIntent().getStringExtra(EXTRA_MODE));
        String customerName = getIntent().getStringExtra(EXTRA_CUSTOMER_NAME);
        customerNameExtra = customerName;
        if (orderMode) {
            toolbar.setTitle("💬 Chat Pesanan");
        }
        if (customerName != null && !customerName.isEmpty()) {
            toolbar.setSubtitle(customerName);
        }

        rvMessages = findViewById(R.id.rvMessages);
        progressBar = findViewById(R.id.progressBar);
        tvEmpty = findViewById(R.id.tvEmpty);

        adapter = new ChatMessageAdapter();
        adapter.setOnMediaClickListener(this::openMedia);
        LinearLayoutManager lm = new LinearLayoutManager(this);
        if (orderMode) lm.setStackFromEnd(true);
        rvMessages.setLayoutManager(lm);
        rvMessages.setAdapter(adapter);

        String transactionUuid = getIntent().getStringExtra(EXTRA_TRANSACTION_UUID);
        if (transactionUuid == null || transactionUuid.isEmpty()) {
            showEmpty("Transaksi tidak valid.");
            pollStopped = true;
            return;
        }
        if (orderMode) {
            setupOrderMode(transactionUuid);
            restoreAttachment(savedInstanceState);
        } else {
            loadMessages(transactionUuid);
        }
    }

    @Override
    public void onBackPressed() {
        if (confirmExitIfUnsent()) return;
        super.onBackPressed();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        // Putar layar membuat ulang Activity: lampiran yang sudah dipilih tapi belum dikirim ikut
        // dibawa (teks EditText disimpan otomatis; kiriman yang berjalan ada di OrderChatOutbox).
        if (attachPath != null) outState.putString(STATE_ATTACH_PATH, attachPath);
    }

    private void restoreAttachment(@Nullable Bundle state) {
        String p = state != null ? state.getString(STATE_ATTACH_PATH) : null;
        if (p == null || imgAttachPreview == null || !new File(p).exists()) return;
        attachPath = p;
        BitmapUtils.loadLocalIntoView(imgAttachPreview, p, 160, 160);
        if (attachPreview != null) attachPreview.setVisibility(View.VISIBLE);
    }

    /**
     * Keluar dari Chat Pesanan selagi ada kiriman staf yang belum beres. Yang masih dikirim tetap
     * berjalan di latar belakang (OrderChatOutbox) → cukup diberi tahu. Yang GAGAL / status TAK
     * DIKETAHUI butuh keputusan staf → tanya dulu (gelembungnya tetap tampil saat chat dibuka lagi).
     *
     * @return true bila dialog konfirmasi ditampilkan (jangan tutup dulu)
     */
    private boolean confirmExitIfUnsent() {
        if (!orderMode || outbox == null || transactionUuid == null) return false;
        int unsent = 0;
        int inFlight = 0;
        for (OrderChatOutbox.Entry e : outbox.entries(transactionUuid)) {
            if (e.isInFlightState()) inFlight++; else unsent++;
        }
        if (unsent > 0) {
            String more = inFlight > 0 ? " " + inFlight + " pesan lain masih dikirim di latar belakang." : "";
            showConfirm("Pesan belum terkirim",
                    unsent + " pesan gagal / belum pasti terkirim ke pelanggan." + more
                            + "\n\nTetap keluar? Pesan itu tetap tampil saat chat ini dibuka lagi.",
                    "Keluar", this::finish, null, null);
            return true;
        }
        if (inFlight > 0) {
            Toast.makeText(this, "Pesan masih dikirim di latar belakang — buka lagi chat ini untuk melihat statusnya.",
                    Toast.LENGTH_LONG).show();
        }
        return false;
    }

    /** Dialog konfirmasi tunggal (lihat {@link #confirmShowing}); diam bila sudah ada yang terbuka. */
    private void showConfirm(String title, String message, String positive, Runnable onPositive,
                             @Nullable String neutral, @Nullable Runnable onNeutral) {
        if (confirmShowing || isFinishing()) return;
        confirmShowing = true;
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(positive, (d, w) -> onPositive.run())
                .setNegativeButton("Batal", null)
                .setOnDismissListener(d -> confirmShowing = false);
        if (neutral != null && onNeutral != null) b.setNeutralButton(neutral, (d, w) -> onNeutral.run());
        b.show();
    }

    private void openMedia(ChatMessage m) {
        if (m.mediaUrl == null || m.mediaUrl.isEmpty()) return;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(m.mediaUrl)));
        } catch (Exception e) {
            Toast.makeText(this, "Tidak ada aplikasi untuk membuka lampiran ini", Toast.LENGTH_SHORT).show();
        }
    }

    private void loadMessages(String transactionUuid) {
        SyncSettings cfg = new SyncSettings(new SettingsDao(DatabaseHelper.getInstance(this)));
        if (!cfg.isEnrolled()) {
            showEmpty("Perangkat belum terhubung ke server.");
            return;
        }

        new Thread(() -> {
            List<ChatMessage> result = null;
            String error = null;
            try {
                org.json.JSONObject res = new SyncApi(cfg).complaintLog(transactionUuid);
                result = ChatMessage.listFromJson(res != null ? res.optJSONArray("messages") : null);
            } catch (Exception e) {
                error = e.getMessage();
            }
            final List<ChatMessage> finalResult = result;
            final String finalError = error;
            runOnUiThread(() -> {
                if (finalError != null) {
                    showEmpty("Gagal memuat log percakapan: " + finalError);
                    return;
                }
                if (finalResult == null || finalResult.isEmpty()) {
                    showEmpty("Belum ada pesan tersimpan.");
                    return;
                }
                progressBar.setVisibility(View.GONE);
                rvMessages.setVisibility(View.VISIBLE);
                adapter.setData(finalResult);
                rvMessages.scrollToPosition(finalResult.size() - 1);
            });
        }).start();
    }

    private void showEmpty(String message) {
        progressBar.setVisibility(View.GONE);
        rvMessages.setVisibility(View.GONE);
        tvEmpty.setVisibility(View.VISIBLE);
        tvEmpty.setText(message);
    }

    // ================================================================ MODE ORDER (Chat Pesanan)

    private void setupOrderMode(String trxUuid) {
        this.transactionUuid = trxUuid;
        cfg = new SyncSettings(new SettingsDao(DatabaseHelper.getInstance(this)));
        if (!cfg.isEnrolled()) {
            showEmpty("Perangkat belum terhubung ke server.");
            pollStopped = true;
            return;
        }
        tvLiveBanner = findViewById(R.id.tvLiveBanner);
        tvAgentBanner = findViewById(R.id.tvAgentBanner);
        tvComposeBlocked = findViewById(R.id.tvComposeBlocked);
        composeBar = findViewById(R.id.composeBar);
        attachPreview = findViewById(R.id.attachPreview);
        imgAttachPreview = findViewById(R.id.imgAttachPreview);
        btnAttach = findViewById(R.id.btnAttach);
        etMessage = findViewById(R.id.etMessage);
        btnSend = findViewById(R.id.btnSend);

        adapter.setOnStatusClickListener(this::onBubbleStatusClick);
        adapter.setOnMediaErrorListener(this::onMediaHttpError);
        btnSend.setOnClickListener(v -> onSendClicked());
        btnAttach.setOnClickListener(v -> pickImage());
        findViewById(R.id.btnAttachRemove).setOnClickListener(v -> clearAttachment(true));
        cleanupOldFiles();

        // Kiriman staf yang belum beres untuk order ini (dari layar sebelumnya / sebelum putar layar /
        // sebelum proses mati) tampil lagi sebagai gelembung optimis; statusnya terus diperbarui.
        outbox = OrderChatOutbox.get(getApplicationContext());
        outbox.setListener(trxUuid, outboxListener);
        for (OrderChatOutbox.Entry e : outbox.entries(trxUuid)) {
            ChatMessage m = ChatMessage.optimistic(e.clientKey, e.text.isEmpty() ? null : e.text,
                    e.imagePath, e.staffName, e.localTime);
            m.localStatus = e.status;
            m.localError = e.error;
            adapter.addOptimistic(m);
        }
        // Poll pertama (tanpa cursor/rev) dimulai di onResume.
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (orderMode && !pollStopped) schedulePoll(0);
    }

    @Override
    protected void onPause() {
        resumed = false;
        handler.removeCallbacks(pollRunnable);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        // Hanya pendengarnya yang dilepas — ulangan kirim tetap berjalan di OrderChatOutbox.
        if (outbox != null && transactionUuid != null) outbox.removeListener(transactionUuid, outboxListener);
        super.onDestroy();
    }

    private void schedulePoll(long delayMs) {
        handler.removeCallbacks(pollRunnable);
        if (!resumed || pollStopped || isFinishing()) return;
        handler.postDelayed(pollRunnable, Math.max(0L, delayMs));
    }

    /** Satu GET /chat; tak pernah tumpang-tindih (pollInFlight), jadwal berikutnya setelah jawab. */
    private void pollNow() {
        if (!resumed || pollStopped || pollInFlight || cfg == null) return;
        pollInFlight = true;
        final int gen = pollGen;
        final String c = cursor;
        final int r = windowRev;
        final SyncSettings s = cfg;
        new Thread(() -> {
            SyncApi.OrderChatResult res = new SyncApi(s).orderChat(transactionUuid, c, r);
            runOnUiThread(() -> onPollResult(gen, res));
        }, "order-chat-poll").start();
    }

    private void onPollResult(int gen, SyncApi.OrderChatResult res) {
        pollInFlight = false;
        if (isFinishing() || isDestroyed()) return;
        if (gen != pollGen) {
            // Muat-ulang penuh diminta selagi poll lama berjalan — buang hasil lama, tarik penuh.
            schedulePoll(0);
            return;
        }
        if (!res.isOk()) {
            if (res.status == 404 && "no_session".equals(res.errorCode)) {
                stopOrderChat("Chat pesanan belum tersedia");
                return;
            }
            if (res.status == 404) {
                stopOrderChat(res.errorMessage != null ? res.errorMessage : "Transaksi tidak ditemukan di server.");
                return;
            }
            if (res.status == 401 || res.status == 403) {
                // Token perangkat dicabut / tak berhak — mengulang tiap 60 detik tak akan menolong.
                stopOrderChat(res.status == 401
                        ? "Perangkat tidak lagi terotorisasi — hubungkan ulang di Pengaturan."
                        : "Akses chat pesanan ditolak server (HTTP 403).");
                return;
            }
            failStreak++;
            long delay = Math.min(BACKOFF_MAX_MS, BACKOFF_FIRST_MS << Math.min(failStreak - 1, 3));
            String why = res.errorMessage != null ? res.errorMessage : "koneksi bermasalah";
            if (!sessionLoaded) {
                showEmpty("Gagal memuat chat pesanan: " + why + "\nMencoba lagi…");
            } else {
                showLiveBanner("⚠️ Gagal memperbarui chat: " + why + " — mencoba lagi…");
            }
            schedulePoll(delay);
            return;
        }
        failStreak = 0;
        JSONObject body = res.body;
        JSONObject session = body.optJSONObject("session");
        List<ChatMessage> msgs = ChatMessage.listFromJson(body.optJSONArray("messages"));
        absolutize(msgs);

        boolean wasAtBottom = !sessionLoaded || isAtBottom();
        if (body.optBoolean("reset", false)) {
            adapter.replaceAll(msgs);
        } else {
            adapter.merge(msgs);
        }
        reconcileOutbox();
        String nextCursor = optStr(body, "cursor");
        if (nextCursor != null) cursor = nextCursor;
        if (session != null) {
            windowRev = session.optInt("window_rev", windowRev);
            applySession(session);
        }
        sessionLoaded = true;
        progressBar.setVisibility(View.GONE);
        rvMessages.setVisibility(View.VISIBLE);
        updateEmptyState();
        if (wasAtBottom) scrollToBottom();

        if (purged) {
            // Riwayat sudah dihapus retensi — tak ada lagi yang bisa berubah. pollStopped diset
            // SEBELUM menu dibangun ulang, supaya "🔄 Muat ulang" (yang tak akan berbuat apa-apa) hilang.
            pollStopped = true;
            handler.removeCallbacks(pollRunnable);
            updateComposer();
            refreshMenuIfChanged();
            return;
        }
        refreshMenuIfChanged();
        schedulePoll(body.optBoolean("has_more", false) ? 0 : POLL_MS);
    }

    private void applySession(JSONObject s) {
        purged = s.optBoolean("purged", false);
        canSend = s.optBoolean("can_send", false) && !purged;
        sendBlockCode = optStr(s, "send_block_code");
        sendBlockReason = optStr(s, "send_block_reason");
        String kind = optStr(s, "send_kind");
        sendKind = kind != null ? kind : "reply";
        agentPausedUntil = optStr(s, "agent_paused_until");

        String cust = optStr(s, "customer_name");
        if (cust == null || cust.isEmpty()) cust = customerNameExtra != null ? customerNameExtra : "";
        String acct = optStr(s, "wa_account");
        String sub = cust + (acct != null && !acct.isEmpty() ? (cust.isEmpty() ? "" : " · ") + "via " + acct : "");
        toolbar.setSubtitle(sub.isEmpty() ? null : sub);

        if (!s.optBoolean("live", true)) {
            String err = optStr(s, "live_error");
            showLiveBanner("⚠️ " + (err != null ? err : "Live tertunda — menampilkan salinan terakhir"));
        } else {
            tvLiveBanner.setVisibility(View.GONE);
        }
        updateAgentBanner();
        updateComposer();
    }

    private void showLiveBanner(String text) {
        if (tvLiveBanner == null) return;
        tvLiveBanner.setText(text);
        tvLiveBanner.setVisibility(View.VISIBLE);
    }

    /** Server hanya mengirim agent_paused_until untuk jeda yang masih berlaku (paused_until &gt; now
     *  server) dan poll 6 detik menyegarkannya — jangan dibandingkan lagi dengan jam HP: HP yang
     *  jamnya kecepetan tak akan pernah melihat banner & tak bisa "Serahkan ke AI". */
    private boolean isAgentPaused() {
        return agentPausedUntil != null && !agentPausedUntil.isEmpty();
    }

    private void updateAgentBanner() {
        if (tvAgentBanner == null) return;
        if (isAgentPaused()) {
            tvAgentBanner.setText("🤖 AI dijeda sampai " + Ts.hm(agentPausedUntil) + " — staf menangani chat ini");
            tvAgentBanner.setVisibility(View.VISIBLE);
        } else {
            tvAgentBanner.setVisibility(View.GONE);
        }
    }

    private void updateComposer() {
        if (composeBar == null) return;
        if (purged || pollStopped) {
            composeBar.setVisibility(View.GONE);
            attachPreview.setVisibility(View.GONE);
            tvComposeBlocked.setVisibility(View.GONE);
            return;
        }
        composeBar.setVisibility(View.VISIBLE);
        attachPreview.setVisibility(attachPath != null ? View.VISIBLE : View.GONE);
        etMessage.setEnabled(canSend);
        btnSend.setEnabled(canSend);
        btnAttach.setEnabled(canSend && !attachPreparing);
        btnAttach.setAlpha(canSend ? 1f : 0.4f);
        if (canSend) {
            tvComposeBlocked.setVisibility(View.GONE);
        } else {
            tvComposeBlocked.setText(sendBlockReason != null && !sendBlockReason.isEmpty()
                    ? sendBlockReason : "Balasan dari perangkat ini sedang tidak tersedia.");
            tvComposeBlocked.setVisibility(View.VISIBLE);
        }
    }

    private void updateEmptyState() {
        if (purged) {
            tvEmpty.setText("Riwayat chat sudah dihapus");
            tvEmpty.setVisibility(View.VISIBLE);
        } else if (adapter.getItemCount() == 0) {
            tvEmpty.setText("Belum ada pesan di chat pesanan ini.");
            tvEmpty.setVisibility(View.VISIBLE);
        } else {
            tvEmpty.setVisibility(View.GONE);
        }
    }

    private void stopOrderChat(String message) {
        pollStopped = true;
        sessionLoaded = false;
        handler.removeCallbacks(pollRunnable);
        showEmpty(message);
        if (tvLiveBanner != null) tvLiveBanner.setVisibility(View.GONE);
        if (tvAgentBanner != null) tvAgentBanner.setVisibility(View.GONE);
        updateComposer();
        refreshMenuIfChanged();
    }

    /** Muat ulang PENUH (tanpa cursor/rev) — mis. URL media bertanda tangan kedaluwarsa (403). */
    private void requestFullReload() {
        cursor = null;
        windowRev = 0;
        pollGen++;
        if (!pollInFlight) schedulePoll(0);
    }

    private void onMediaHttpError(ChatMessage m, int code) {
        if (code != 403) return;
        long now = System.currentTimeMillis();
        if (now - lastMediaReloadAt < MEDIA_RELOAD_MIN_GAP_MS) return;
        lastMediaReloadAt = now;
        requestFullReload();
    }

    /** media_url relatif ("/api/chat-media/…") → gabung dengan alamat server, sama seperti SyncApi. */
    private void absolutize(List<ChatMessage> list) {
        String base = cfg != null ? cfg.getBaseUrl() : "";
        for (ChatMessage m : list) {
            if (m.mediaUrl != null && m.mediaUrl.startsWith("/")) m.mediaUrl = base + m.mediaUrl;
        }
    }

    private boolean isAtBottom() {
        return !rvMessages.canScrollVertically(1);
    }

    private void scrollToBottom() {
        int n = adapter.getItemCount();
        if (n > 0) rvMessages.scrollToPosition(n - 1);
    }

    // ---------------------------------------------------------------- menu: ambil alih / serahkan

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        if (!orderMode) return super.onCreateOptionsMenu(menu);
        if (sessionLoaded && !purged && !pollStopped && canToggleAgent()) {
            menu.add(0, MENU_AGENT, 0, isAgentPaused() ? "🤖 Serahkan ke AI" : "✋ Ambil alih dari AI")
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        }
        if (sessionLoaded && !pollStopped) {
            menu.add(0, MENU_RELOAD, 1, "🔄 Muat ulang").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (orderMode && item.getItemId() == MENU_AGENT) {
            toggleAgent(!isAgentPaused());
            return true;
        }
        if (orderMode && item.getItemId() == MENU_RELOAD) {
            requestFullReload();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /** Perangkat marketing / akun read-only tak boleh mengubah jeda AI (server juga menolak, 403).
     *  agent_not_pause_aware: agen AI yang berjalan belum menghormati jeda — "Ambil alih" hanya akan
     *  memasang banner "AI dijeda" sementara AI tetap membalas pelanggan, jadi disembunyikan. */
    private boolean canToggleAgent() {
        return !"marketing_readonly".equals(sendBlockCode)
                && !"role_readonly".equals(sendBlockCode)
                && !"purged".equals(sendBlockCode)
                && !"agent_not_pause_aware".equals(sendBlockCode);
    }

    private void toggleAgent(boolean paused) {
        if (agentBusy || cfg == null) return;
        agentBusy = true;
        final SyncSettings s = cfg;
        new Thread(() -> {
            SyncApi.OrderChatResult res = new SyncApi(s).orderChatAgent(transactionUuid, paused);
            runOnUiThread(() -> {
                agentBusy = false;
                if (isFinishing() || isDestroyed()) return;
                if (res.isOk()) {
                    applyPauseFrom(res.body);
                    Toast.makeText(this, paused ? "AI dijeda — staf menangani chat ini"
                            : "Chat diserahkan kembali ke AI", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, res.errorMessage != null ? res.errorMessage
                            : "Gagal mengubah status AI", Toast.LENGTH_LONG).show();
                }
            });
        }, "order-chat-agent").start();
    }

    private void applyPauseFrom(@Nullable JSONObject body) {
        if (body == null || !body.has("agent_paused_until")) return;
        agentPausedUntil = optStr(body, "agent_paused_until");
        updateAgentBanner();
        refreshMenuIfChanged();
    }

    /** Bangun ulang menu HANYA bila isinya berubah: invalidate tiap poll (6 detik) akan menutup
     *  menu overflow yang sedang dibuka pengguna sebelum ia sempat memilih. */
    private void refreshMenuIfChanged() {
        String key = sessionLoaded + "|" + purged + "|" + pollStopped + "|" + canToggleAgent()
                + "|" + isAgentPaused();
        if (key.equals(menuStateKey)) return;
        menuStateKey = key;
        invalidateOptionsMenu();
    }

    // ---------------------------------------------------------------- kirim

    private void onSendClicked() {
        if (!sessionLoaded) return;
        if (attachPreparing) {
            Toast.makeText(this, "Gambar masih disiapkan…", Toast.LENGTH_SHORT).show();
            return;
        }
        String text = etMessage.getText().toString().trim();
        String img = attachPath;
        if (text.isEmpty() && img == null) {
            Toast.makeText(this, "Tulis pesan atau lampirkan gambar dulu", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!canSend) {
            Toast.makeText(this, sendBlockReason != null ? sendBlockReason : "Tidak bisa mengirim saat ini",
                    Toast.LENGTH_LONG).show();
            return;
        }
        requestSend(text, img, () -> {
            etMessage.setText("");
            clearAttachment(false);   // berkasnya dipakai gelembung optimis, jangan dihapus
        });
    }

    private static final String PROACTIVE_NOTE = "Pelanggan belum membalas >24 jam: pesan ini terhitung proaktif.";

    /** Konfirmasi dulu bila pelanggan belum membalas >24 jam (pesan proaktif), lalu kirim. Dialognya
     *  tunggal ({@link #showConfirm}) — ketuk ganda "Kirim" tak bisa mengirim dua kali. */
    private void requestSend(String text, @Nullable String imagePath, @Nullable Runnable onConfirmed) {
        Runnable go = () -> {
            if (onConfirmed != null) onConfirmed.run();
            startSend(text, imagePath);
        };
        if ("proactive".equals(sendKind)) {
            showConfirm("Kirim pesan proaktif?", PROACTIVE_NOTE + " Kirim?", "Kirim", go, null, null);
        } else {
            go.run();
        }
    }

    /** Kirim baru (client_key BARU) lewat OrderChatOutbox + gelembung optimis "mengirim…". */
    private void startSend(String text, @Nullable String imagePath) {
        if (outbox == null || transactionUuid == null) return;
        OrderChatOutbox.Entry e = outbox.enqueue(transactionUuid, text, imagePath, staffName(), nowLocal());
        adapter.addOptimistic(ChatMessage.optimistic(e.clientKey, e.text.isEmpty() ? null : e.text,
                e.imagePath, e.staffName, e.localTime));
        updateEmptyState();
        scrollToBottom();
    }

    /** Kabar dari OrderChatOutbox (UI thread) untuk satu kiriman order ini. */
    private void onOutboxUpdate(OrderChatOutbox.Entry e, @Nullable JSONObject body) {
        if (isFinishing() || isDestroyed()) return;
        if (body != null) applyPauseFrom(body);
        if (e.status != null) {
            adapter.setLocalStatus(e.clientKey, e.status, e.error);
            return;
        }
        // Terkirim: baris server (selalu ada — lihat OrderChatSendPolicy.SENT) menggantikan gelembungnya.
        JSONObject msg = body != null ? body.optJSONObject("message") : null;
        if (msg == null) return;
        ChatMessage m = ChatMessage.fromJson(msg);
        if (m.clientKey == null) m.clientKey = e.clientKey;
        if (m.direction == null || m.direction.isEmpty()) m.direction = "out";
        absolutize(Collections.singletonList(m));
        boolean wasAtBottom = isAtBottom();
        adapter.merge(Collections.singletonList(m));
        if (wasAtBottom) scrollToBottom();
    }

    /** Baris server sebuah kiriman tiba lewat poll (tertaut client_key) → gelembung optimisnya sudah
     *  diganti adapter; keluarkan dari antrean supaya tak diulang lagi. */
    private void reconcileOutbox() {
        if (outbox == null || transactionUuid == null) return;
        for (OrderChatOutbox.Entry e : outbox.entries(transactionUuid)) {
            if (!adapter.hasOptimistic(e.clientKey)) outbox.discard(e.clientKey);
        }
    }

    /** Ketuk gelembung ❗ gagal / ❓ status tak diketahui: kirim ulang sebagai pesan BARU (kunci
     *  baru) atau hapus dari layar (gelembungnya kini bertahan lintas layar, jadi harus bisa dibuang). */
    private void onBubbleStatusClick(ChatMessage m) {
        if (m == null || m.clientKey == null) return;
        boolean unknown = ChatMessage.STATUS_UNKNOWN.equals(m.localStatus);
        boolean failed = ChatMessage.STATUS_FAILED.equals(m.localStatus);
        if (!unknown && !failed) return;
        final String oldKey = m.clientKey;
        final String text = m.text != null ? m.text : "";
        final String img = m.localImagePath;
        Runnable discard = () -> {
            if (outbox != null) outbox.discard(oldKey);
            adapter.removeOptimistic(oldKey);
            updateEmptyState();
        };
        String title = unknown ? "Status tidak diketahui" : "Pesan gagal terkirim";
        String why = unknown ? "Pesan ini mungkin sudah terkirim — cek chat dulu."
                : (m.localError != null && !m.localError.isEmpty() ? m.localError : "Ditolak server.");
        if (!canSend) {
            String block = sendBlockReason != null ? sendBlockReason : "Tidak bisa mengirim saat ini.";
            showConfirm(title, why + "\n\n" + block + "\n\nHapus pesan ini dari layar?", "Hapus", discard, null, null);
            return;
        }
        Runnable resend = () -> {
            discard.run();
            startSend(text, img);
        };
        String proactive = "proactive".equals(sendKind) ? "\n\n" + PROACTIVE_NOTE : "";
        showConfirm(title, why + "\n\nKirim ulang sebagai pesan baru?" + proactive, "Kirim ulang", resend,
                "Hapus", discard);
    }

    // ---------------------------------------------------------------- lampiran gambar

    private void pickImage() {
        if (!canSend || attachPreparing) return;
        // ACTION_GET_CONTENT (bukan ACTION_PICK): andal di semua versi Android & tanpa izin storage.
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(Intent.createChooser(intent, "Pilih gambar"), REQ_PICK_IMAGE);
        } catch (Exception ex) {
            Toast.makeText(this, "Tidak dapat membuka galeri", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_IMAGE || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        attachPreparing = true;
        updateComposer();
        final android.content.Context app = getApplicationContext();
        new Thread(() -> {
            String path = null;
            try {
                File dir = OrderChatOutbox.attachDir(app);
                long ts = System.currentTimeMillis();
                File raw = new File(dir, "raw_" + ts + ".tmp");
                File out = new File(dir, "IMG_" + ts + ".jpg");
                if (BitmapUtils.copyUriToFile(app, uri, raw)) {
                    // Sama seperti unggahan lain: JPEG, sisi terpanjang ≤ 1280 px; turunkan mutu bila > 4 MB.
                    int[] qualities = {85, 70, 55};
                    for (int q : qualities) {
                        if (BitmapUtils.compressForUpload(raw.getAbsolutePath(), out, IMAGE_MAX_DIM, q)
                                && out.length() > 0 && out.length() <= SEND_MEDIA_MAX) {
                            path = out.getAbsolutePath();
                            break;
                        }
                    }
                }
                //noinspection ResultOfMethodCallIgnored
                raw.delete();
                if (path == null) //noinspection ResultOfMethodCallIgnored
                    out.delete();
            } catch (Exception ignored) {
                path = null;
            }
            final String fPath = path;
            runOnUiThread(() -> {
                attachPreparing = false;
                if (isFinishing() || isDestroyed()) return;
                if (fPath == null) {
                    Toast.makeText(this, "Gagal membaca gambar", Toast.LENGTH_SHORT).show();
                } else {
                    clearAttachment(true);
                    attachPath = fPath;
                    BitmapUtils.loadLocalIntoView(imgAttachPreview, fPath, 160, 160);
                }
                updateComposer();
            });
        }, "order-chat-attach").start();
    }

    private void clearAttachment(boolean deleteFile) {
        if (deleteFile && attachPath != null) {
            //noinspection ResultOfMethodCallIgnored
            new File(attachPath).delete();
        }
        attachPath = null;
        if (imgAttachPreview != null) {
            imgAttachPreview.setTag(null);
            imgAttachPreview.setImageBitmap(null);
        }
        if (attachPreview != null) attachPreview.setVisibility(View.GONE);
    }

    /** Pangkas lampiran lama & salinan media pelanggan (OrderChatOutbox.pruneFiles; juga dijalankan
     *  tiap siklus SyncWorker). */
    private void cleanupOldFiles() {
        final android.content.Context app = getApplicationContext();
        new Thread(() -> OrderChatOutbox.pruneFiles(app), "order-chat-cleanup").start();
    }

    // ---------------------------------------------------------------- util

    private String staffName() {
        String n = new SettingsDao(DatabaseHelper.getInstance(this)).getCurrentUserName();
        return n != null ? n.trim() : "";
    }

    private static String nowLocal() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
    }

    @Nullable
    private static String optStr(JSONObject o, String key) {
        if (o == null || !o.has(key) || o.isNull(key)) return null;
        String v = o.optString(key, null);
        return v != null && !v.isEmpty() ? v : null;
    }
}
