package com.crowja.damiupos;

import android.annotation.SuppressLint;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.text.InputType;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.PermissionRequest;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.SettingsDao;
import com.crowja.damiupos.db.UserDao;
import com.crowja.damiupos.model.User;
import com.crowja.damiupos.sync.SyncApi;
import com.crowja.damiupos.sync.SyncSettings;

import org.json.JSONObject;

/**
 * "Dashboard Web" untuk Admin: SELURUH dashboard web (dashboard.airfrez.com) dibuka di dalam
 * aplikasi, jadi semua yang bisa dilakukan di web — pengguna, produk, HR & slip gaji, kampanye,
 * pengaturan, laporan, void langsung, dst. — bisa dilakukan dari HP tanpa mengetik email/kata sandi.
 *
 * <p>Alur: Admin mengetik PIN-nya (diverifikasi lokal seperti login) → HP meminta link masuk sekali
 * pakai ke server ({@code POST /api/dashboard/handoff}, token perangkat + X-Staff-Uuid + SHA-256
 * PIN; server mencocokkan PIN dengan pin_hash bila web pernah mengaturnya) → WebView membuka link
 * itu dan mendapat sesi dashboard atas nama akun otomatis milik staf ini (terkunci ke cabang
 * perangkat). PIN cukup SEKALI per sesi login HP (SettingsDao.rememberAdminWebSession): pembukaan
 * berikutnya dan handoff ulang saat sesi web habis (/login) memakai hash yang sama tanpa bertanya;
 * logout/ganti user atau penolakan 403 dari server menghapusnya.
 *
 * <p>Yang diurus supaya halaman web bekerja seperti di browser: unggah file/foto (input file),
 * izin lokasi (peta pelanggan), unduhan (PDF struk/slip/laporan, XLSX), link eksternal
 * (WhatsApp, telepon, Maps, deep-link damiupos://) dibuka aplikasi yang sesuai, tombol Back =
 * mundur riwayat halaman.
 */
public class AdminWebActivity extends AppCompatActivity {

    /** Path tujuan di dashboard (mis. "/customers"); kosong = beranda. */
    public static final String EXTRA_TO = "to";

    private static final int RC_FILE = 4101;
    private static final int RC_LOCATION = 4102;

    private WebView webView;
    private ProgressBar progress;
    private TextView tvStatus;

    private SyncSettings cfg;
    private SettingsDao settingsDao;
    private UserDao userDao;
    private long uid;
    private String staffUuid = "";
    private String baseHost = "";
    private String to = "";

    private ValueCallback<Uri[]> fileCallback;
    private String geoOrigin;
    private GeolocationPermissions.Callback geoCallback;

    /** Satu permintaan handoff pada satu waktu; dipakai juga untuk mencegah loop /login → PIN → /login. */
    private boolean handoffBusy = false;

    /** SHA-256 PIN yang sudah cocok di sesi login ini (dari SettingsDao) — handoff ulang tanpa tanya PIN. */
    @Nullable
    private String trustedPinSha;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_admin_web);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        webView = findViewById(R.id.webView);
        progress = findViewById(R.id.progress);
        tvStatus = findViewById(R.id.tvStatus);

        DatabaseHelper db = DatabaseHelper.getInstance(this);
        settingsDao = new SettingsDao(db);
        userDao = new UserDao(db);
        cfg = new SyncSettings(settingsDao);
        to = getIntent().getStringExtra(EXTRA_TO);
        if (to == null) to = "";

        if (!cfg.isEnrolled()) {
            Toast.makeText(this, "Perangkat belum terhubung ke server (Pengaturan → Sinkronisasi).", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        uid = settingsDao.getCurrentUserId();
        User me = uid > 0 ? userDao.getById(uid) : null;
        if (me == null || !me.isAdmin()) {
            Toast.makeText(this, "Dashboard Web hanya untuk Admin.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        try {
            baseHost = Uri.parse(cfg.getBaseUrl()).getHost();
        } catch (Exception ignored) {
        }
        if (baseHost == null) baseHost = "";

        setupWebView();
        // PIN cukup sekali per sesi login HP: bila sudah pernah cocok untuk user ini, langsung handoff.
        String[] saved = settingsDao.getAdminWebSession(uid);
        if (saved != null) {
            staffUuid = saved[0];
            trustedPinSha = saved[1];
            requestHandoff(trustedPinSha);
        } else {
            askPin("Masukkan PIN Admin untuk membuka Dashboard Web.");
        }
    }

    // ---------------------------------------------------------------- handoff (PIN → link masuk)

    private void askPin(String message) {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        input.setHint("PIN Admin");
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(pad, pad / 2, pad, 0);
        wrap.addView(input);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Dashboard Web")
                .setMessage(message + "\n\nPIN login salah satu akun Admin di HP ini (sama seperti membuka Pengaturan).")
                .setView(wrap)
                .setCancelable(false)
                .setPositiveButton("Masuk", null)
                .setNegativeButton("Batal", (d, w) -> finish())
                .create();
        dialog.setOnShowListener(dlg ->
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                    String pin = input.getText().toString().trim();
                    // PIN admin mana pun yang aktif di HP ini (aturan gerbang Pengaturan) — identitas
                    // yang dikirim ke server adalah admin yang PIN-nya cocok, bukan sekadar user yang
                    // sedang login (lihat UserDao.findActiveAdminByPin: HP bisa punya "Admin" hantu).
                    User admin = userDao.findActiveAdminByPin(pin, uid);
                    if (admin == null) {
                        input.setError("PIN tidak cocok dengan admin mana pun di HP ini");
                        return;
                    }
                    String su = userDao.getSyncUuidById(admin.getId());
                    if (su == null || su.isEmpty()) {
                        input.setError("Akun \"" + admin.getName() + "\" belum tersinkron dari server — tunggu sinkronisasi lalu coba lagi");
                        return;
                    }
                    staffUuid = su;
                    trustedPinSha = User.sha256Hex(pin);
                    settingsDao.rememberAdminWebSession(uid, staffUuid, trustedPinSha);
                    dialog.dismiss();
                    requestHandoff(trustedPinSha);
                }));
        dialog.show();
    }

    private void requestHandoff(String pinSha256) {
        if (handoffBusy) return;
        handoffBusy = true;
        showStatus("Menyiapkan sesi dashboard…");
        new Thread(() -> {
            String url = null;
            String err = null;
            try {
                JSONObject r = new SyncApi(cfg).dashboardHandoff(staffUuid, pinSha256, to);
                url = r.optString("url", "");
                if (url.isEmpty()) err = "Server tidak mengirim link masuk.";
            } catch (SyncApi.SyncException e) {
                String m = e.serverMessage();
                err = m != null ? m : "Gagal menghubungi server (" + e.code + ").";
                if (e.code == 403) {
                    // PIN diubah dari web / peran dicabut: PIN tersimpan tak berlaku lagi.
                    settingsDao.clearAdminWebSession();
                    trustedPinSha = null;
                }
            } catch (Exception e) {
                err = "Tidak bisa menghubungi server. Periksa koneksi internet.";
            }
            final String fUrl = url, fErr = err;
            runOnUiThread(() -> {
                handoffBusy = false;
                if (isFinishing() || isDestroyed()) return;
                if (fErr != null) {
                    hideStatus();
                    new AlertDialog.Builder(this)
                            .setTitle("Dashboard Web")
                            .setMessage(fErr)
                            .setCancelable(false)
                            .setPositiveButton("Coba Lagi", (d, w) -> askPin("Masukkan PIN Admin untuk mencoba lagi."))
                            .setNegativeButton("Tutup", (d, w) -> finish())
                            .show();
                    return;
                }
                // Hanya path tujuan sekali — setelah masuk, navigasi berikutnya milik pengguna.
                to = "";
                webView.loadUrl(fUrl);
            });
        }, "dashboard-handoff").start();
    }

    private void showStatus(String s) {
        tvStatus.setText(s);
        tvStatus.setVisibility(View.VISIBLE);
        progress.setVisibility(View.VISIBLE);
        progress.setIndeterminate(true);
    }

    private void hideStatus() {
        tvStatus.setVisibility(View.GONE);
        progress.setIndeterminate(false);
        progress.setVisibility(View.GONE);
    }

    // ---------------------------------------------------------------- WebView

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setGeolocationEnabled(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setUserAgentString(s.getUserAgentString() + " DAMIUPOS-App/" + BuildConfig.VERSION_NAME);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleUrl(request.getUrl());
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                hideStatus();
                progress.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progress.setVisibility(View.GONE);
                String t = view.getTitle();
                if (getSupportActionBar() != null) {
                    getSupportActionBar().setTitle(t != null && !t.isEmpty() ? t : "Dashboard Web");
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    progress.setVisibility(View.GONE);
                    Toast.makeText(AdminWebActivity.this,
                            "Halaman gagal dimuat. Periksa koneksi internet lalu tarik menu ⟳ Muat Ulang.",
                            Toast.LENGTH_LONG).show();
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progress.setIndeterminate(false);
                progress.setProgress(newProgress);
                progress.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                try {
                    Intent i = params.createIntent();
                    startActivityForResult(i, RC_FILE);
                } catch (ActivityNotFoundException e) {
                    fileCallback = null;
                    Toast.makeText(AdminWebActivity.this, "Tidak ada aplikasi pemilih file.", Toast.LENGTH_SHORT).show();
                    return false;
                }
                return true;
            }

            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                if (ContextCompat.checkSelfPermission(AdminWebActivity.this,
                        android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    callback.invoke(origin, true, false);
                    return;
                }
                geoOrigin = origin;
                geoCallback = callback;
                ActivityCompat.requestPermissions(AdminWebActivity.this,
                        new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION}, RC_LOCATION);
            }

            @Override
            public void onPermissionRequest(PermissionRequest request) {
                // Kamera/mikrofon dari halaman (getUserMedia) hanya bila aplikasi sudah punya izinnya;
                // sisanya ditolak diam-diam — dashboard memakai input file untuk foto, bukan getUserMedia.
                boolean cam = ContextCompat.checkSelfPermission(AdminWebActivity.this,
                        android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
                java.util.List<String> grant = new java.util.ArrayList<>();
                for (String r : request.getResources()) {
                    if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r) && cam) grant.add(r);
                }
                if (grant.isEmpty()) request.deny();
                else request.grant(grant.toArray(new String[0]));
            }
        });

        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) -> {
            if (url == null) return;
            if (url.startsWith("blob:") || url.startsWith("data:")) {
                Toast.makeText(this, "Unduhan jenis ini belum didukung di sini — buka lewat menu ⋮ Buka di Browser.", Toast.LENGTH_LONG).show();
                return;
            }
            try {
                String name = URLUtil.guessFileName(url, contentDisposition, mimeType);
                DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
                req.setMimeType(mimeType);
                req.addRequestHeader("User-Agent", userAgent);
                String cookies = CookieManager.getInstance().getCookie(url);
                if (cookies != null) req.addRequestHeader("Cookie", cookies);   // sesi dashboard
                req.setTitle(name);
                req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
                DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                if (dm != null) {
                    dm.enqueue(req);
                    Toast.makeText(this, "Mengunduh " + name + "…", Toast.LENGTH_SHORT).show();
                }
            } catch (Exception e) {
                Toast.makeText(this, "Gagal mengunduh: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
    }

    /**
     * Semua navigasi lewat sini. Host dashboard → tetap di WebView, KECUALI /login (sesi habis →
     * minta PIN & handoff ulang). Host lain → browser. Skema non-web (whatsapp:, tel:, mailto:,
     * geo:, intent:, damiupos://) → aplikasi yang sesuai.
     */
    private boolean handleUrl(Uri u) {
        if (u == null) return false;
        String scheme = u.getScheme() != null ? u.getScheme().toLowerCase() : "";
        if ("http".equals(scheme) || "https".equals(scheme)) {
            String host = u.getHost() != null ? u.getHost().toLowerCase() : "";
            if (host.equals(baseHost.toLowerCase())) {
                String path = u.getPath() != null ? u.getPath() : "";
                if ("/login".equals(path) || "/login/".equals(path)) {
                    if (!handoffBusy) {
                        if (trustedPinSha != null) {
                            requestHandoff(trustedPinSha);   // sesi web habis: masuk lagi tanpa tanya PIN
                        } else {
                            askPin("Sesi dashboard berakhir. Masukkan PIN Admin untuk masuk lagi.");
                        }
                    }
                    return true;
                }
                return false;
            }
            // wa.me / api.whatsapp.com / maps / situs lain → di luar aplikasi.
            openExternal(new Intent(Intent.ACTION_VIEW, u));
            return true;
        }
        if ("intent".equals(scheme)) {
            try {
                Intent i = Intent.parseUri(u.toString(), Intent.URI_INTENT_SCHEME);
                if (i.resolveActivity(getPackageManager()) != null) {
                    startActivity(i);
                } else {
                    String fallback = i.getStringExtra("browser_fallback_url");
                    if (fallback != null) webView.loadUrl(fallback);
                }
            } catch (Exception e) {
                Toast.makeText(this, "Tidak bisa membuka tautan ini.", Toast.LENGTH_SHORT).show();
            }
            return true;
        }
        if ("javascript".equals(scheme) || "about".equals(scheme) || "blob".equals(scheme) || "data".equals(scheme)) {
            return false;
        }
        openExternal(new Intent(Intent.ACTION_VIEW, u));
        return true;
    }

    private void openExternal(Intent i) {
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Tidak ada aplikasi untuk membuka tautan ini.", Toast.LENGTH_SHORT).show();
        }
    }

    // ---------------------------------------------------------------- hasil (file picker, izin)

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        if (requestCode == RC_FILE) {
            if (fileCallback == null) return;
            Uri[] result = null;
            if (resultCode == RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    int n = data.getClipData().getItemCount();
                    result = new Uri[n];
                    for (int i = 0; i < n; i++) result[i] = data.getClipData().getItemAt(i).getUri();
                } else if (data.getData() != null) {
                    result = new Uri[]{data.getData()};
                }
            }
            fileCallback.onReceiveValue(result);
            fileCallback = null;
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == RC_LOCATION && geoCallback != null) {
            boolean ok = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            geoCallback.invoke(geoOrigin, ok, false);
            geoCallback = null;
            geoOrigin = null;
        }
    }

    // ---------------------------------------------------------------- menu & back

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_admin_web, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_reload) {
            webView.reload();
            return true;
        }
        if (id == R.id.action_home) {
            webView.loadUrl(cfg.getBaseUrl() + "/");
            return true;
        }
        if (id == R.id.action_open_browser) {
            String url = webView.getUrl();
            if (url == null || url.isEmpty()) url = cfg.getBaseUrl();
            openExternal(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
