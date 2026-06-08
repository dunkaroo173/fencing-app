package io.github.dunkaroo173.uflfencing;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.net.Uri;
import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

public class NativeOverlayExporter {
    interface Callback {
        void onProgress(double progress, String message);
    }

    static class Result {
        final File file;
        final String displayName;
        final long durationMs;

        Result(File file, String displayName, long durationMs) {
            this.file = file;
            this.displayName = displayName;
            this.durationMs = durationMs;
        }
    }

    private static final String MIME_AVC = "video/avc";
    private static final int OUTPUT_MAX_EDGE = 960;
    private static final int OUTPUT_FPS = 24;
    private static final int OUTPUT_BITRATE = 8_000_000;
    private static final int YUV_LAYOUT_PLANAR = 1;
    private static final int YUV_LAYOUT_SEMIPLANAR_UV = 2;

    private final Context context;
    private final Callback callback;

    NativeOverlayExporter(Context context, Callback callback) {
        this.context = context;
        this.callback = callback;
    }

    Result export(JSONObject payload) throws Exception {
        Uri sourceUri = Uri.parse(payload.getString("sourceUri"));
        JSONObject match = payload.getJSONObject("match");
        String sourceType = payload.optString("sourceType", "native");
        String filenameBase = safeFilePart(payload.optString("filenameBase", "match-overlay"));
        double timeOffset = payload.optDouble("timeOffset", 0.0);
        boolean useRecordingPauses = payload.optBoolean("useRecordingPauses", false);
        long fallbackDurationMs = Math.max(0L, payload.optLong("durationMs", 0L));

        File outDir = new File(context.getCacheDir(), "native-video-exports");
        if (!outDir.exists()) outDir.mkdirs();
        File output = new File(outDir, filenameBase + "-" + System.currentTimeMillis() + ".mp4");

        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        MediaMuxer muxer = null;
        MediaCodec encoder = null;
        MediaExtractor audioExtractor = null;
        Bitmap composed = null;
        try {
            retriever.setDataSource(context, sourceUri);
            long durationMs = longMeta(retriever, MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (durationMs <= 0) durationMs = videoDurationMsFromExtractor(sourceUri);
            if (durationMs <= 0 && fallbackDurationMs > 0) durationMs = fallbackDurationMs;
            int srcW = intMeta(retriever, MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH, 1280);
            int srcH = intMeta(retriever, MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT, 720);
            int[] dims = outputDimensions(srcW, srcH);
            int width = dims[0];
            int height = dims[1];
            if (durationMs <= 0) throw new IllegalArgumentException("Could not read source video duration");

            callback.onProgress(0.02, "Preparing encoder");
            muxer = new MediaMuxer(output.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            audioExtractor = createAudioExtractor(sourceUri);
            int audioTrackIndex = -1;
            MediaFormat audioFormat = null;
            if (audioExtractor != null) {
                try {
                    audioFormat = audioExtractor.getTrackFormat(selectedTrackIndex(audioExtractor));
                    audioTrackIndex = muxer.addTrack(audioFormat);
                } catch (Exception ignored) {
                    audioExtractor.release();
                    audioExtractor = null;
                    audioTrackIndex = -1;
                }
            }

            EncoderConfig config = encoderConfig();
            MediaFormat videoFormat = MediaFormat.createVideoFormat(MIME_AVC, width, height);
            videoFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, config.colorFormat);
            videoFormat.setInteger(MediaFormat.KEY_BIT_RATE, OUTPUT_BITRATE);
            videoFormat.setInteger(MediaFormat.KEY_FRAME_RATE, OUTPUT_FPS);
            videoFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

            encoder = MediaCodec.createByCodecName(config.codecName);
            encoder.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoder.start();

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int videoTrackIndex = -1;
            boolean muxerStarted = false;
            int frameCount = Math.max(1, (int) Math.ceil(durationMs / 1000.0 * OUTPUT_FPS));
            long frameDurationUs = 1_000_000L / OUTPUT_FPS;
            byte[] yuv = new byte[width * height * 3 / 2];
            int[] argb = new int[width * height];
            List<Long> sampleTimesUs = videoSampleTimesUs(sourceUri);
            int sampleIndex = 0;
            long lastRetrievedSourceUs = Long.MIN_VALUE;
            Bitmap heldSource = null;
            composed = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(composed);

            for (int frame = 0; frame < frameCount; frame++) {
                long ptsUs = frame * frameDurationUs;
                long sourceTimeUs = sourceTimeForOutputFrame(sampleTimesUs, sampleIndex, ptsUs);
                if (!sampleTimesUs.isEmpty()) {
                    while (sampleIndex + 1 < sampleTimesUs.size() && sampleTimesUs.get(sampleIndex + 1) <= ptsUs) {
                        sampleIndex++;
                    }
                }
                if (sourceTimeUs != lastRetrievedSourceUs) {
                    Bitmap next = retrieveFrame(retriever, sourceTimeUs);
                    if (next != null) {
                        if (heldSource != null) heldSource.recycle();
                        heldSource = next;
                    }
                    lastRetrievedSourceUs = sourceTimeUs;
                }

                drawSource(canvas, heldSource, width, height);
                drawOverlay(canvas, width, height, ptsUs / 1_000_000.0, timeOffset, useRecordingPauses, match);
                composed.getPixels(argb, 0, width, 0, 0, width, height);
                argbToYuv(argb, yuv, width, height, config.yuvLayout);

                int inputIndex = encoder.dequeueInputBuffer(20_000);
                if (inputIndex >= 0) {
                    ByteBuffer input = encoder.getInputBuffer(inputIndex);
                    if (input != null) {
                        input.clear();
                        input.put(yuv);
                    }
                    encoder.queueInputBuffer(inputIndex, 0, yuv.length, ptsUs, 0);
                }

                DrainResult drained = drainEncoder(encoder, muxer, info, videoTrackIndex, muxerStarted, false);
                videoTrackIndex = drained.videoTrackIndex;
                muxerStarted = drained.muxerStarted;
                if (audioTrackIndex >= 0 && muxerStarted && audioExtractor != null) {
                    copyAudioOnce(audioExtractor, muxer, audioTrackIndex);
                    audioExtractor.release();
                    audioExtractor = null;
                }

                if (frame % OUTPUT_FPS == 0) {
                    callback.onProgress(Math.min(0.98, (double) frame / frameCount), "Rendering " + sourceType + " overlay");
                }
            }
            if (heldSource != null) {
                heldSource.recycle();
                heldSource = null;
            }
            composed.recycle();
            composed = null;

            int inputIndex = encoder.dequeueInputBuffer(20_000);
            if (inputIndex >= 0) {
                encoder.queueInputBuffer(inputIndex, 0, 0, frameCount * frameDurationUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            }
            DrainResult drained = drainEncoder(encoder, muxer, info, videoTrackIndex, muxerStarted, true);
            videoTrackIndex = drained.videoTrackIndex;
            muxerStarted = drained.muxerStarted;
            if (audioTrackIndex >= 0 && muxerStarted && audioExtractor != null) {
                copyAudioOnce(audioExtractor, muxer, audioTrackIndex);
            }

            callback.onProgress(1.0, "Native export complete");
            return new Result(output, output.getName(), durationMs);
        } finally {
            if (composed != null) {
                try { composed.recycle(); } catch (Exception ignored) {}
            }
            try { retriever.release(); } catch (Exception ignored) {}
            if (audioExtractor != null) {
                try { audioExtractor.release(); } catch (Exception ignored) {}
            }
            if (encoder != null) {
                try { encoder.stop(); } catch (Exception ignored) {}
                try { encoder.release(); } catch (Exception ignored) {}
            }
            if (muxer != null) {
                try { muxer.stop(); } catch (Exception ignored) {}
                try { muxer.release(); } catch (Exception ignored) {}
            }
        }
    }

    private DrainResult drainEncoder(MediaCodec encoder, MediaMuxer muxer, MediaCodec.BufferInfo info,
                                     int videoTrackIndex, boolean muxerStarted, boolean end) {
        while (true) {
            int outputIndex = encoder.dequeueOutputBuffer(info, end ? 20_000 : 0);
            if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                return new DrainResult(videoTrackIndex, muxerStarted);
            }
            if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                videoTrackIndex = muxer.addTrack(encoder.getOutputFormat());
                muxer.start();
                muxerStarted = true;
                continue;
            }
            if (outputIndex >= 0) {
                ByteBuffer output = encoder.getOutputBuffer(outputIndex);
                if (output != null && muxerStarted && info.size > 0) {
                    output.position(info.offset);
                    output.limit(info.offset + info.size);
                    muxer.writeSampleData(videoTrackIndex, output, info);
                }
                boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                encoder.releaseOutputBuffer(outputIndex, false);
                if (eos) return new DrainResult(videoTrackIndex, muxerStarted);
            }
        }
    }

    private void copyAudioOnce(MediaExtractor extractor, MediaMuxer muxer, int audioTrackIndex) {
        ByteBuffer buffer = ByteBuffer.allocate(1024 * 1024);
        MediaCodec.BufferInfo audioInfo = new MediaCodec.BufferInfo();
        while (true) {
            int size = extractor.readSampleData(buffer, 0);
            if (size < 0) break;
            audioInfo.set(0, size, extractor.getSampleTime(), extractor.getSampleFlags());
            muxer.writeSampleData(audioTrackIndex, buffer, audioInfo);
            extractor.advance();
        }
    }

    private MediaExtractor createAudioExtractor(Uri uri) {
        try {
            MediaExtractor extractor = new MediaExtractor();
            extractor.setDataSource(context, uri, null);
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    extractor.selectTrack(i);
                    return extractor;
                }
            }
            extractor.release();
        } catch (Exception ignored) {}
        return null;
    }

    private int selectedTrackIndex(MediaExtractor extractor) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) return i;
        }
        return 0;
    }

    private void drawSource(Canvas canvas, Bitmap source, int width, int height) {
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        canvas.drawColor(Color.BLACK);
        if (source == null) return;
        float scale = Math.max(width / (float) source.getWidth(), height / (float) source.getHeight());
        float dw = source.getWidth() * scale;
        float dh = source.getHeight() * scale;
        RectF dst = new RectF((width - dw) / 2f, (height - dh) / 2f, (width + dw) / 2f, (height + dh) / 2f);
        canvas.drawBitmap(source, null, dst, paint);
    }

    private void drawOverlay(Canvas canvas, int width, int height, double videoTime, double timeOffset,
                             boolean useRecordingPauses, JSONObject match) throws Exception {
        double boutTime = videoTimeToBoutTime(videoTime, timeOffset, useRecordingPauses, match);
        JSONObject state = replayStateAt(boutTime, match);
        drawHud(canvas, width, height, state);
        JSONObject ev = visibleEvent(boutTime, match);
        if (ev != null) drawActionPill(canvas, width, height, ev, boutTime - overlayEventTime(ev, match));
    }

    private void drawHud(Canvas canvas, int w, int h, JSONObject state) throws Exception {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float hudH = h * 0.11f;
        float pad = w * 0.018f;
        p.setColor(Color.argb(205, 2, 4, 10));
        canvas.drawRect(0, 0, w, hudH, p);

        p.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD));
        p.setTextSize(Math.max(18, h * 0.037f));
        p.setColor(Color.WHITE);
        p.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(shortName(state.optString("nameL", "LEFT")), pad, hudH * 0.42f, p);
        p.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(shortName(state.optString("nameR", "RIGHT")), w - pad, hudH * 0.42f, p);

        float barY = hudH * 0.65f;
        float barH = Math.max(5f, h * 0.008f);
        float barW = w * 0.34f;
        p.setStyle(Paint.Style.FILL);
        p.setColor(Color.argb(45, 255, 255, 255));
        canvas.drawRoundRect(new RectF(pad, barY, pad + barW, barY + barH), barH / 2f, barH / 2f, p);
        canvas.drawRoundRect(new RectF(w - pad - barW, barY, w - pad, barY + barH), barH / 2f, barH / 2f, p);
        p.setColor(Color.rgb(0, 199, 255));
        canvas.drawRoundRect(new RectF(pad, barY, pad + barW * hpRatio(state, "L"), barY + barH), barH / 2f, barH / 2f, p);
        p.setColor(Color.rgb(230, 38, 0));
        float rightFill = barW * hpRatio(state, "R");
        canvas.drawRoundRect(new RectF(w - pad - rightFill, barY, w - pad, barY + barH), barH / 2f, barH / 2f, p);

        p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(Math.max(28, h * 0.066f));
        p.setColor(Color.rgb(0, 199, 255));
        canvas.drawText(String.valueOf(state.optInt("scoreL", 0)), w * 0.44f, hudH * 0.56f, p);
        p.setColor(Color.rgb(230, 38, 0));
        canvas.drawText(String.valueOf(state.optInt("scoreR", 0)), w * 0.56f, hudH * 0.56f, p);
        p.setTextSize(Math.max(24, h * 0.052f));
        p.setColor(state.optInt("timerSec", 0) <= 30 ? Color.rgb(230, 38, 0) : Color.rgb(245, 200, 66));
        canvas.drawText(fmtTime(state.optInt("timerSec", 0)), w / 2f, hudH * 0.38f, p);
        p.setTextSize(Math.max(12, h * 0.024f));
        p.setColor(Color.argb(150, 255, 255, 255));
        canvas.drawText("P" + state.optInt("period", 1), w / 2f, hudH * 0.75f, p);
    }

    private void drawActionPill(Canvas canvas, int w, int h, JSONObject ev, double elapsed) {
        float alpha = elapsed < 2.4 ? 1f : Math.max(0f, (float) (1.0 - (elapsed - 2.4) / 0.6));
        if (alpha <= 0f) return;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        int sideColor = sideColor(ev.optString("side", "C"));
        String sideName = "L".equals(ev.optString("side")) ? "LEFT" : "R".equals(ev.optString("side")) ? "RIGHT" : "REFEREE";
        String action = ev.optString("label", "ACTION").toUpperCase(Locale.US);
        String result = ev.optBoolean("isHit", false) ? "TOUCH" : "OFF TARGET";
        float titleSize = Math.max(19, h * 0.036f);
        float metaSize = Math.max(10, h * 0.016f);
        float resultSize = Math.max(11, h * 0.019f);
        float pillW = Math.min(w - 48, Math.max(w * 0.48f, titleSize * Math.max(10, action.length() * 0.62f)));
        float pillH = Math.max(92, titleSize * 3.0f);
        float x = w / 2f - pillW / 2f;
        float y = h * 0.78f - pillH / 2f;

        p.setAlpha((int) (alpha * 255));
        p.setColor(Color.argb(238, 2, 4, 10));
        canvas.drawRoundRect(new RectF(x, y, x + pillW, y + pillH), 14, 14, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(3);
        p.setColor(sideColor);
        canvas.drawRoundRect(new RectF(x + 2, y + 2, x + pillW - 2, y + pillH - 2), 14, 14, p);
        p.setStyle(Paint.Style.FILL);
        canvas.drawRoundRect(new RectF(x, y, x + 8, y + pillH), 8, 8, p);

        p.setTextAlign(Paint.Align.CENTER);
        p.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD));
        p.setTextSize(metaSize);
        p.setColor(sideColor);
        canvas.drawText(sideName + " ACTION", w / 2f, y + pillH * 0.25f, p);

        p.setTextSize(titleSize);
        p.setColor(Color.WHITE);
        canvas.drawText(action, w / 2f, y + pillH * 0.54f, p);

        p.setTextSize(resultSize);
        int resultColor = ev.optBoolean("isHit", false) ? sideColor : Color.rgb(255, 206, 84);
        float badgeW = Math.max(p.measureText(result) + 28, pillW * 0.20f);
        float badgeH = Math.max(22, resultSize * 1.65f);
        float bx = w / 2f - badgeW / 2f;
        float by = y + pillH * 0.72f;
        p.setColor(resultColor);
        canvas.drawRoundRect(new RectF(bx, by, bx + badgeW, by + badgeH), badgeH / 2f, badgeH / 2f, p);
        p.setColor(ev.optBoolean("isHit", false) ? Color.rgb(2, 4, 10) : Color.rgb(34, 22, 0));
        canvas.drawText(result, w / 2f, by + badgeH * 0.66f, p);
        p.setAlpha(255);
    }

    private JSONObject visibleEvent(double boutTime, JSONObject match) throws Exception {
        JSONArray events = match.optJSONArray("events");
        if (events == null) return null;
        JSONObject visible = null;
        for (int i = 0; i < events.length(); i++) {
            JSONObject ev = events.getJSONObject(i);
            double t = overlayEventTime(ev, match);
            if (boutTime >= t && boutTime < t + 3.0) visible = ev;
        }
        return visible;
    }

    private JSONObject replayStateAt(double boutTime, JSONObject match) throws Exception {
        int periodDuration = Math.max(1, match.optInt("periodDuration", 180));
        int totalPeriods = Math.max(1, match.optInt("totalPeriods", 1));
        int period = Math.min(totalPeriods, Math.max(1, (int) Math.floor(boutTime / periodDuration) + 1));
        double periodElapsed = Math.max(0, boutTime - (period - 1) * periodDuration);
        JSONObject state = new JSONObject();
        state.put("nameL", match.optString("nameL", "LEFT"));
        state.put("nameR", match.optString("nameR", "RIGHT"));
        state.put("scoreL", 0);
        state.put("scoreR", 0);
        state.put("hpL", 100);
        state.put("hpR", 100);
        state.put("timerSec", Math.max(0, periodDuration - (int) Math.floor(periodElapsed)));
        state.put("period", period);
        JSONArray events = match.optJSONArray("events");
        if (events != null) {
            for (int i = 0; i < events.length(); i++) {
                JSONObject ev = events.getJSONObject(i);
                if (overlayEventTime(ev, match) > boutTime) continue;
                if (ev.optBoolean("isHit", false)) {
                    if ("L".equals(ev.optString("side"))) {
                        state.put("scoreL", state.optInt("scoreL") + 1);
                        state.put("hpR", Math.max(0, state.optInt("hpR") - 5));
                    }
                    if ("R".equals(ev.optString("side"))) {
                        state.put("scoreR", state.optInt("scoreR") + 1);
                        state.put("hpL", Math.max(0, state.optInt("hpL") - 5));
                    }
                }
                int actionId = ev.optInt("actionId", 0);
                if (actionId == 191) {
                    state.put("scoreR", state.optInt("scoreR") + 1);
                    state.put("hpL", Math.max(0, state.optInt("hpL") - 5));
                }
                if (actionId == 291) {
                    state.put("scoreL", state.optInt("scoreL") + 1);
                    state.put("hpR", Math.max(0, state.optInt("hpR") - 5));
                }
            }
        }
        return state;
    }

    private double videoTimeToBoutTime(double videoTime, double timeOffset, boolean useRecordingPauses, JSONObject match) {
        double boutTime = videoTime + timeOffset;
        if (!useRecordingPauses) return boutTime;
        JSONArray pauses = match.optJSONArray("recordingPauses");
        if (pauses == null) return boutTime;
        double pausedTotal = 0;
        for (int i = 0; i < pauses.length(); i++) {
            JSONObject p = pauses.optJSONObject(i);
            if (p == null) continue;
            double start = p.optDouble("startVideo", Double.NaN);
            double end = p.optDouble("endVideo", Double.NaN);
            if (!Double.isFinite(start) || !Double.isFinite(end) || end <= start) continue;
            if (videoTime <= start) break;
            double freeze = Math.max(p.optDouble("boutTime", start + timeOffset - pausedTotal), start + timeOffset - pausedTotal);
            if (videoTime < end) return freeze;
            pausedTotal += end - start;
        }
        return boutTime - pausedTotal;
    }

    private double overlayEventTime(JSONObject ev, JSONObject match) {
        int period = Math.max(1, ev.optInt("period", 1));
        int periodDuration = Math.max(1, match.optInt("periodDuration", 180));
        return (period - 1) * periodDuration + ev.optDouble("ts", 0);
    }

    private static EncoderConfig encoderConfig() throws Exception {
        MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
        for (MediaCodecInfo info : list.getCodecInfos()) {
            if (!info.isEncoder()) continue;
            for (String type : info.getSupportedTypes()) {
                if (!MIME_AVC.equalsIgnoreCase(type)) continue;
                MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(type);
                if (supportsColor(caps, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)) {
                    return new EncoderConfig(info.getName(), MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar, YUV_LAYOUT_SEMIPLANAR_UV);
                }
                if (supportsColor(caps, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar)) {
                    return new EncoderConfig(info.getName(), MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar, YUV_LAYOUT_PLANAR);
                }
                for (int color : caps.colorFormats) {
                    if (color == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible) {
                        return new EncoderConfig(info.getName(), color, YUV_LAYOUT_SEMIPLANAR_UV);
                    }
                }
            }
        }
        throw new IllegalStateException("No H.264 YUV encoder found");
    }

    private static boolean supportsColor(MediaCodecInfo.CodecCapabilities caps, int target) {
        for (int color : caps.colorFormats) {
            if (color == target) return true;
        }
        return false;
    }

    private static void argbToYuv(int[] argb, byte[] yuv, int width, int height, int yuvLayout) {
        int frameSize = width * height;
        boolean semiPlanar = yuvLayout == YUV_LAYOUT_SEMIPLANAR_UV;
        int yIndex = 0;
        int uIndex = frameSize;
        int vIndex = semiPlanar ? frameSize + 1 : frameSize + frameSize / 4;
        for (int j = 0; j < height; j++) {
            for (int i = 0; i < width; i++) {
                int c = argb[j * width + i];
                int r = (c >> 16) & 0xff;
                int g = (c >> 8) & 0xff;
                int b = c & 0xff;
                int y = clamp(((66 * r + 129 * g + 25 * b + 128) >> 8) + 16);
                int u = clamp(((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128);
                int v = clamp(((112 * r - 94 * g - 18 * b + 128) >> 8) + 128);
                yuv[yIndex++] = (byte) y;
                if ((j & 1) == 0 && (i & 1) == 0) {
                    if (semiPlanar) {
                        yuv[uIndex] = (byte) u;
                        yuv[uIndex + 1] = (byte) v;
                        uIndex += 2;
                    } else {
                        yuv[uIndex++] = (byte) u;
                        yuv[vIndex++] = (byte) v;
                    }
                }
            }
        }
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    private static int sideColor(String side) {
        if ("L".equals(side)) return Color.rgb(0, 199, 255);
        if ("R".equals(side)) return Color.rgb(230, 38, 0);
        return Color.rgb(245, 200, 66);
    }

    private static String fmtTime(int sec) {
        int s = Math.max(0, sec);
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    private static int intMeta(MediaMetadataRetriever retriever, int key, int fallback) {
        try {
            String value = retriever.extractMetadata(key);
            return value == null ? fallback : Integer.parseInt(value);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static long longMeta(MediaMetadataRetriever retriever, int key) {
        try {
            String value = retriever.extractMetadata(key);
            return value == null ? 0L : Long.parseLong(value);
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private long videoDurationMsFromExtractor(Uri uri) {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(context, uri, null);
            int videoTrack = -1;
            MediaFormat videoFormat = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    videoTrack = i;
                    videoFormat = format;
                    break;
                }
            }
            if (videoTrack < 0 || videoFormat == null) return 0L;
            if (videoFormat.containsKey(MediaFormat.KEY_DURATION)) {
                long formatDurationUs = videoFormat.getLong(MediaFormat.KEY_DURATION);
                if (formatDurationUs > 0) return Math.max(1L, formatDurationUs / 1000L);
            }

            extractor.selectTrack(videoTrack);
            ByteBuffer buffer = ByteBuffer.allocate(4 * 1024 * 1024);
            long lastSampleUs = -1L;
            long previousSampleUs = -1L;
            long frameDeltaTotalUs = 0L;
            int frameDeltaCount = 0;
            while (extractor.readSampleData(buffer, 0) >= 0) {
                long sampleUs = extractor.getSampleTime();
                if (sampleUs >= 0) {
                    long deltaUs = previousSampleUs >= 0 ? sampleUs - previousSampleUs : 0L;
                    if (deltaUs > 0 && deltaUs <= 1_000_000L) {
                        frameDeltaTotalUs += deltaUs;
                        frameDeltaCount++;
                    }
                    previousSampleUs = sampleUs;
                    lastSampleUs = Math.max(lastSampleUs, sampleUs);
                }
                extractor.advance();
                buffer.clear();
            }
            long frameDeltaUs = frameDeltaCount > 0 ? frameDeltaTotalUs / frameDeltaCount : 1_000_000L / OUTPUT_FPS;
            return lastSampleUs < 0 ? 0L : Math.max(1L, (lastSampleUs + frameDeltaUs) / 1000L);
        } catch (Exception ignored) {
            return 0L;
        } finally {
            try { extractor.release(); } catch (Exception ignored) {}
        }
    }

    private List<Long> videoSampleTimesUs(Uri uri) {
        List<Long> times = new ArrayList<>();
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(context, uri, null);
            int videoTrack = -1;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    videoTrack = i;
                    break;
                }
            }
            if (videoTrack < 0) return times;
            extractor.selectTrack(videoTrack);
            ByteBuffer buffer = ByteBuffer.allocate(256 * 1024);
            while (extractor.readSampleData(buffer, 0) >= 0) {
                long sampleUs = extractor.getSampleTime();
                if (sampleUs >= 0) times.add(sampleUs);
                extractor.advance();
                buffer.clear();
            }
        } catch (Exception ignored) {
            times.clear();
        } finally {
            try { extractor.release(); } catch (Exception ignored) {}
        }
        return times;
    }

    private static long sourceTimeForOutputFrame(List<Long> sampleTimesUs, int sampleIndex, long ptsUs) {
        if (sampleTimesUs.isEmpty()) return ptsUs;
        int index = Math.max(0, Math.min(sampleIndex, sampleTimesUs.size() - 1));
        while (index + 1 < sampleTimesUs.size() && sampleTimesUs.get(index + 1) <= ptsUs) {
            index++;
        }
        long selected = sampleTimesUs.get(index);
        if (selected > ptsUs && index > 0) return sampleTimesUs.get(index - 1);
        return selected;
    }

    private static Bitmap retrieveFrame(MediaMetadataRetriever retriever, long timeUs) {
        try {
            Bitmap frame = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST);
            if (frame != null) return frame;
        } catch (Exception ignored) {}
        try {
            Bitmap frame = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (frame != null) return frame;
        } catch (Exception ignored) {}
        try {
            return retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_PREVIOUS_SYNC);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String shortName(String name) {
        if (name == null) return "";
        String trimmed = name.trim();
        return trimmed.length() <= 12 ? trimmed : trimmed.substring(0, 12);
    }

    private static float hpRatio(JSONObject state, String side) {
        int hp = state.optInt("L".equals(side) ? "hpL" : "hpR", 100);
        return Math.max(0f, Math.min(1f, hp / 100f));
    }

    private static int[] outputDimensions(int srcW, int srcH) {
        int w = Math.max(2, srcW);
        int h = Math.max(2, srcH);
        float scale = Math.min(1f, OUTPUT_MAX_EDGE / (float) Math.max(w, h));
        w = Math.max(2, Math.round(w * scale));
        h = Math.max(2, Math.round(h * scale));
        w = alignUp(w, 16);
        h = alignUp(h, 16);
        return new int[] {w, h};
    }

    private static int alignUp(int value, int multiple) {
        return Math.max(multiple, ((value + multiple - 1) / multiple) * multiple);
    }

    private static String safeFilePart(String value) {
        String s = value == null ? "" : value.replaceAll("[^A-Za-z0-9._-]+", "-").replaceAll("^-+|-+$", "");
        return s.isEmpty() ? "match-overlay" : s;
    }

    private static class EncoderConfig {
        final String codecName;
        final int colorFormat;
        final int yuvLayout;
        EncoderConfig(String codecName, int colorFormat, int yuvLayout) {
            this.codecName = codecName;
            this.colorFormat = colorFormat;
            this.yuvLayout = yuvLayout;
        }
    }

    private static class DrainResult {
        final int videoTrackIndex;
        final boolean muxerStarted;
        DrainResult(int videoTrackIndex, boolean muxerStarted) {
            this.videoTrackIndex = videoTrackIndex;
            this.muxerStarted = muxerStarted;
        }
    }
}
