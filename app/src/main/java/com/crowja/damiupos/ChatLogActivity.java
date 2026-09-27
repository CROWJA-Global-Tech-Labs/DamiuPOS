package com.crowja.damiupos;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.crowja.damiupos.adapter.ChatMessageAdapter;
import com.crowja.damiupos.db.DatabaseHelper;
import com.crowja.damiupos.db.SettingsDao;
import com.crowja.damiupos.model.ChatMessage;
import com.crowja.damiupos.sync.SyncApi;
import com.crowja.damiupos.sync.SyncSettings;

import java.util.List;

/**
 * Viewer log percakapan WhatsApp komplain (badge 😠 "Komplain") untuk SATU order — bubble chat
 * read-only, diambil on-demand dari server (lihat SyncApi.complaintLog). Diluncurkan dari
 * TransactionListActivity/DeliveryQueueActivity via long-press menu "Lihat Chat Komplain".
 *
 * <p>Activity klasik biasa (bukan Fragment/Navigation Component/Compose) -- app ini tak punya
 * infrastruktur itu di mana pun, jadi tak diperkenalkan hanya untuk satu layar ini.</p>
 */
public class ChatLogActivity extends AppCompatActivity {

    public static final String EXTRA_TRANSACTION_UUID = "extra_transaction_uuid";
    public static final String EXTRA_CUSTOMER_NAME = "extra_customer_name";

    private RecyclerView rvMessages;
    private ProgressBar progressBar;
    private TextView tvEmpty;
    private ChatMessageAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat_log);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        String customerName = getIntent().getStringExtra(EXTRA_CUSTOMER_NAME);
        if (customerName != null && !customerName.isEmpty()) {
            toolbar.setSubtitle(customerName);
        }

        rvMessages = findViewById(R.id.rvMessages);
        progressBar = findViewById(R.id.progressBar);
        tvEmpty = findViewById(R.id.tvEmpty);

        adapter = new ChatMessageAdapter();
        adapter.setOnMediaClickListener(this::openMedia);
        rvMessages.setLayoutManager(new LinearLayoutManager(this));
        rvMessages.setAdapter(adapter);

        String transactionUuid = getIntent().getStringExtra(EXTRA_TRANSACTION_UUID);
        if (transactionUuid == null || transactionUuid.isEmpty()) {
            showEmpty("Transaksi tidak valid.");
            return;
        }
        loadMessages(transactionUuid);
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
}
