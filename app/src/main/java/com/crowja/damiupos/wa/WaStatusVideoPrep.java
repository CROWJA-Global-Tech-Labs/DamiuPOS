package com.crowja.damiupos.wa;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.OptIn;
import androidx.media3.common.Effect;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.Presentation;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.DefaultEncoderFactory;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.Effects;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.ProgressHolder;
import androidx.media3.transformer.Transformer;
import androidx.media3.transformer.VideoEncoderSettings;

import com.google.common.collect.ImmutableList;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Kompres video Status di HP dengan Media3 Transformer: H.264 + AAC dalam MP4, sisi terpanjang ≤ 1280 px, bitrate
 * dari {@link WaStatusVideoRules#videoBitrate} (hasil ±14 MB), dan — bila diminta — hanya 60 detik pertama.
 * Dipanggil dari thread UTAMA (Transformer butuh Looper); callback juga di thread utama.
 */
@OptIn(markerClass = UnstableApi.class)
final class WaStatusVideoPrep {

    interface Callback {
        void progress(int percent);

        void done(File out);

        void failed(String message);
    }

    private final Transformer transformer;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Callback cb;
    private boolean finished;

    private WaStatusVideoPrep(Context ctx, int bitrate, Callback cb, File out) {
        this.cb = cb;
        transformer = new Transformer.Builder(ctx)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .setEncoderFactory(new DefaultEncoderFactory.Builder(ctx)
                        .setRequestedVideoEncoderSettings(new VideoEncoderSettings.Builder().setBitrate(bitrate).build())
                        .setEnableFallback(true)
                        .build())
                .addListener(new Transformer.Listener() {
                    @Override
                    public void onCompleted(Composition composition, ExportResult result) {
                        finish();
                        cb.done(out);
                    }

                    @Override
                    public void onError(Composition composition, ExportResult result, ExportException e) {
                        finish();
                        out.delete();
                        cb.failed("Video tak bisa dikompres di HP ini (" + e.getErrorCodeName() + ").");
                    }
                })
                .build();
    }

    /**
     * Mulai kompres. {@code clipMs} > 0 = potong sampai milidetik itu; {@code resize} = perkecil ke ≤ 1280 px.
     * Kembalikan objeknya supaya bisa dibatalkan ({@link #cancel}).
     */
    static WaStatusVideoPrep start(Context ctx, Uri uri, long clipMs, boolean resize, long durationMs, File out, Callback cb) {
        WaStatusVideoPrep p = new WaStatusVideoPrep(ctx.getApplicationContext(),
                WaStatusVideoRules.videoBitrate(clipMs > 0 ? clipMs : durationMs), cb, out);
        MediaItem.Builder mi = new MediaItem.Builder().setUri(uri);
        if (clipMs > 0) {
            mi.setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder().setEndPositionMs(clipMs).build());
        }
        List<Effect> fx = new ArrayList<>();
        if (resize) {
            fx.add(Presentation.createForWidthAndHeight(WaStatusVideoRules.MAX_SIDE, WaStatusVideoRules.MAX_SIDE,
                    Presentation.LAYOUT_SCALE_TO_FIT));
        }
        EditedMediaItem item = new EditedMediaItem.Builder(mi.build())
                .setEffects(new Effects(ImmutableList.of(), fx))
                .build();
        p.transformer.start(item, out.getAbsolutePath());
        p.poll();
        return p;
    }

    void cancel() {
        if (finished) return;
        finish();
        transformer.cancel();
    }

    private void finish() {
        finished = true;
        main.removeCallbacksAndMessages(null);
    }

    private void poll() {
        if (finished) return;
        ProgressHolder h = new ProgressHolder();
        if (transformer.getProgress(h) == Transformer.PROGRESS_STATE_AVAILABLE) cb.progress(h.progress);
        main.postDelayed(this::poll, 500);
    }
}
