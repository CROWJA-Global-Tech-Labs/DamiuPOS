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
 * Kompres satu potongan video Status di HP dengan Media3 Transformer: H.264 + AAC dalam MP4, sisi terpanjang = long_side
 * server, bitrate dari {@link WaStatusVideoRules#videoBitrate} (≤ max_bytes server per potongan).
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

    /** Kompres satu potongan [startMs, endMs) ke sisi terpanjang {@code longSide} px dengan bitrate video {@code bitrate}. */
    static WaStatusVideoPrep start(Context ctx, Uri uri, long startMs, long endMs, int longSide, int bitrate, File out, Callback cb) {
        WaStatusVideoPrep p = new WaStatusVideoPrep(ctx.getApplicationContext(), bitrate, cb, out);
        MediaItem mi = new MediaItem.Builder().setUri(uri)
                .setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(startMs).setEndPositionMs(endMs).build())
                .build();
        List<Effect> fx = new ArrayList<>();
        fx.add(Presentation.createForWidthAndHeight(longSide, longSide, Presentation.LAYOUT_SCALE_TO_FIT));
        EditedMediaItem item = new EditedMediaItem.Builder(mi).setEffects(new Effects(ImmutableList.of(), fx)).build();
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
