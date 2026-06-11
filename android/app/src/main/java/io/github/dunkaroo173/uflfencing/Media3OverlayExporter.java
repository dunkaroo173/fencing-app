package io.github.dunkaroo173.uflfencing;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import androidx.annotation.OptIn;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.CanvasOverlay;
import androidx.media3.effect.OverlayEffect;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.EditedMediaItemSequence;
import androidx.media3.transformer.Effects;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.ProgressHolder;
import androidx.media3.transformer.Transformer;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;

@OptIn(markerClass = UnstableApi.class)
class Media3OverlayExporter {
    private final Context context;
    private final NativeOverlayExporter.Callback callback;

    Media3OverlayExporter(Context context, NativeOverlayExporter.Callback callback) {
        this.context = context;
        this.callback = callback;
    }

    NativeOverlayExporter.Result export(JSONObject payload) throws Exception {
        JSONArray segments = payload.optJSONArray("segments");
        boolean hasSegments = segments != null && segments.length() > 0;
        Uri sourceUri = hasSegments ? null : Uri.parse(payload.getString("sourceUri"));
        JSONObject match = payload.getJSONObject("match");
        String filenameBase = safeFilePart(payload.optString("filenameBase", "match-overlay"));
        double timeOffset = payload.optDouble("timeOffset", 0.0);
        boolean useRecordingPauses = payload.optBoolean("useRecordingPauses", false);
        long fallbackDurationMs = hasSegments ? segmentsDurationMs(segments) : Math.max(0L, payload.optLong("durationMs", 0L));

        File outDir = new File(context.getCacheDir(), "native-video-exports");
        if (!outDir.exists()) outDir.mkdirs();
        File output = new File(outDir, filenameBase + "-media3-" + System.currentTimeMillis() + ".mp4");

        HandlerThread thread = new HandlerThread("UFLMedia3OverlayExport");
        thread.start();
        Handler handler = new Handler(thread.getLooper());
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<NativeOverlayExporter.Result> resultRef = new AtomicReference<>();
        AtomicReference<Exception> errorRef = new AtomicReference<>();
        AtomicReference<Transformer> transformerRef = new AtomicReference<>();

        callback.onProgress(0.02, "Preparing Media3 overlay export");
        handler.post(() -> {
            try {
                FencingCanvasOverlay overlay = hasSegments
                        ? new FencingCanvasOverlay(match, segments)
                        : new FencingCanvasOverlay(match, timeOffset, useRecordingPauses);
                Effects effects = new Effects(
                        Collections.emptyList(),
                        Collections.singletonList(new OverlayEffect(Collections.singletonList(overlay)))
                );
                EditedMediaItem edited = hasSegments ? null : new EditedMediaItem.Builder(MediaItem.fromUri(sourceUri))
                        .setEffects(effects)
                        .build();
                Composition composition = null;
                if (hasSegments) {
                    List<EditedMediaItem> items = new ArrayList<>();
                    for (int i = 0; i < segments.length(); i++) {
                        JSONObject segment = segments.optJSONObject(i);
                        if (segment == null || segment.optString("uri", "").isEmpty()) continue;
                        items.add(new EditedMediaItem.Builder(MediaItem.fromUri(Uri.parse(segment.optString("uri")))).build());
                    }
                    if (items.isEmpty()) throw new IllegalArgumentException("No recording segments available for export");
                    EditedMediaItemSequence sequence = new EditedMediaItemSequence.Builder(items).build();
                    composition = new Composition.Builder(sequence).setEffects(effects).build();
                }
                Transformer transformer = new Transformer.Builder(context)
                        .setLooper(thread.getLooper())
                        .setVideoMimeType(MimeTypes.VIDEO_H264)
                        .addListener(new Transformer.Listener() {
                            @Override
                            public void onCompleted(Composition composition, ExportResult exportResult) {
                                long durationMs = exportResult.durationMs > 0 ? exportResult.durationMs : fallbackDurationMs;
                                resultRef.set(new NativeOverlayExporter.Result(output, output.getName(), durationMs));
                                callback.onProgress(1.0, "Media3 export complete");
                                done.countDown();
                            }

                            @Override
                            public void onError(Composition composition, ExportResult exportResult, ExportException exportException) {
                                errorRef.set(exportException);
                                done.countDown();
                            }
                        })
                        .build();
                transformerRef.set(transformer);
                scheduleProgress(handler, transformer, done);
                if (composition != null) transformer.start(composition, output.getAbsolutePath());
                else transformer.start(edited, output.getAbsolutePath());
            } catch (Exception e) {
                errorRef.set(e);
                done.countDown();
            }
        });

        try {
            done.await();
            Exception error = errorRef.get();
            if (error != null) {
                if (output.exists()) output.delete();
                throw error;
            }
            NativeOverlayExporter.Result result = resultRef.get();
            if (result == null || !output.exists() || output.length() == 0) {
                throw new IllegalStateException("Media3 export did not produce a video");
            }
            return result;
        } finally {
            Transformer transformer = transformerRef.get();
            if (transformer != null && done.getCount() > 0) {
                handler.post(transformer::cancel);
            }
            thread.quitSafely();
        }
    }

    private void scheduleProgress(Handler handler, Transformer transformer, CountDownLatch done) {
        ProgressHolder holder = new ProgressHolder();
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (done.getCount() == 0) return;
                int state = transformer.getProgress(holder);
                if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                    callback.onProgress(Math.max(0.03, Math.min(0.98, holder.progress / 100.0)), "Rendering Media3 overlay");
                } else {
                    callback.onProgress(0.05, "Rendering Media3 overlay");
                }
                handler.postDelayed(this, 500);
            }
        }, 500);
    }

    private static String safeFilePart(String value) {
        String s = value == null ? "" : value.replaceAll("[^A-Za-z0-9._-]+", "-").replaceAll("^-+|-+$", "");
        return s.isEmpty() ? "match-overlay" : s;
    }

    private static long segmentsDurationMs(JSONArray segments) {
        long total = 0L;
        if (segments == null) return 0L;
        for (int i = 0; i < segments.length(); i++) {
            JSONObject segment = segments.optJSONObject(i);
            if (segment != null) total += Math.max(0L, segment.optLong("durationMs", 0L));
        }
        return total;
    }

    private static class FencingCanvasOverlay extends CanvasOverlay {
        private final JSONObject match;
        private final JSONArray segments;
        private final double timeOffset;
        private final boolean useRecordingPauses;
        private static final double PILL_SECONDS = 2.5;
        private static final double PILL_FADE_START = 2.0;

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        FencingCanvasOverlay(JSONObject match, double timeOffset, boolean useRecordingPauses) {
            super(true);
            this.match = match;
            this.segments = null;
            this.timeOffset = timeOffset;
            this.useRecordingPauses = useRecordingPauses;
        }

        FencingCanvasOverlay(JSONObject match, JSONArray segments) {
            super(true);
            this.match = match;
            this.segments = segments;
            this.timeOffset = 0.0;
            this.useRecordingPauses = true;
        }

        @Override
        public void onDraw(Canvas canvas, long presentationTimeUs) {
            int width = canvas.getWidth();
            int height = canvas.getHeight();
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
            double videoTime = presentationTimeUs / 1_000_000.0;
            double boutTime = videoTimeToBoutTime(videoTime);
            JSONObject state = replayStateAt(boutTime);
            drawHud(canvas, width, height, state);
            JSONObject event = visibleEvent(boutTime);
            if (event != null) drawActionPill(canvas, width, height, event, boutTime - overlayEventTime(event));
        }

        private void drawHud(Canvas canvas, int width, int height, JSONObject state) {
            float hudH = height * 0.11f;
            float pad = width * 0.018f;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb(205, 2, 4, 10));
            canvas.drawRect(0, 0, width, hudH, paint);

            paint.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD));
            paint.setTextSize(Math.max(18, height * 0.037f));
            paint.setColor(Color.WHITE);
            paint.setTextAlign(Paint.Align.LEFT);
            canvas.drawText(shortName(state.optString("nameL", "LEFT")), pad, hudH * 0.42f, paint);
            paint.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText(shortName(state.optString("nameR", "RIGHT")), width - pad, hudH * 0.42f, paint);

            float barY = hudH * 0.65f;
            float barH = Math.max(5f, height * 0.008f);
            float barW = width * 0.34f;
            paint.setColor(Color.argb(45, 255, 255, 255));
            canvas.drawRoundRect(new RectF(pad, barY, pad + barW, barY + barH), barH / 2f, barH / 2f, paint);
            canvas.drawRoundRect(new RectF(width - pad - barW, barY, width - pad, barY + barH), barH / 2f, barH / 2f, paint);
            paint.setColor(Color.rgb(0, 199, 255));
            canvas.drawRoundRect(new RectF(pad, barY, pad + barW * hpRatio(state, "L"), barY + barH), barH / 2f, barH / 2f, paint);
            paint.setColor(Color.rgb(230, 38, 0));
            float rightFill = barW * hpRatio(state, "R");
            canvas.drawRoundRect(new RectF(width - pad - rightFill, barY, width - pad, barY + barH), barH / 2f, barH / 2f, paint);

            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(Math.max(28, height * 0.066f));
            paint.setColor(Color.rgb(0, 199, 255));
            canvas.drawText(String.valueOf(state.optInt("scoreL", 0)), width * 0.44f, hudH * 0.56f, paint);
            paint.setColor(Color.rgb(230, 38, 0));
            canvas.drawText(String.valueOf(state.optInt("scoreR", 0)), width * 0.56f, hudH * 0.56f, paint);
            paint.setTextSize(Math.max(24, height * 0.052f));
            paint.setColor(state.optInt("timerSec", 0) <= 30 ? Color.rgb(230, 38, 0) : Color.rgb(245, 200, 66));
            canvas.drawText(fmtTime(state.optInt("timerSec", 0)), width / 2f, hudH * 0.38f, paint);
            paint.setTextSize(Math.max(12, height * 0.024f));
            paint.setColor(Color.argb(150, 255, 255, 255));
            canvas.drawText("P" + state.optInt("period", 1), width / 2f, hudH * 0.75f, paint);
        }

        private void drawActionPill(Canvas canvas, int width, int height, JSONObject event, double elapsed) {
            float alpha = elapsed < PILL_FADE_START ? 1f
                    : Math.max(0f, (float) (1.0 - (elapsed - PILL_FADE_START) / (PILL_SECONDS - PILL_FADE_START)));
            if (alpha <= 0f) return;
            int sideColor = sideColor(event.optString("side", "C"));
            String sideName = "L".equals(event.optString("side")) ? "LEFT" : "R".equals(event.optString("side")) ? "RIGHT" : "REFEREE";
            String action = event.optString("label", "ACTION").toUpperCase(Locale.US);
            String result = event.optBoolean("isHit", false) ? "TOUCH" : "OFF TARGET";
            String review = reviewBadge(event);
            float titleSize = Math.max(19, height * 0.036f);
            float resultSize = Math.max(11, height * 0.019f);
            float pillW = Math.min(width - 48, Math.max(width * 0.48f, titleSize * Math.max(10, action.length() * 0.62f)));
            float pillH = Math.max(review != null ? 110 : 92, titleSize * (review != null ? 3.6f : 3.0f));
            float x = width / 2f - pillW / 2f;
            float y = height * 0.78f - pillH / 2f;

            paint.setAlpha((int) (alpha * 255));
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb(238, 2, 4, 10));
            canvas.drawRoundRect(new RectF(x, y, x + pillW, y + pillH), 14, 14, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(3);
            paint.setColor(sideColor);
            canvas.drawRoundRect(new RectF(x + 2, y + 2, x + pillW - 2, y + pillH - 2), 14, 14, paint);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawRoundRect(new RectF(x, y, x + 8, y + pillH), 8, 8, paint);

            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD));
            paint.setTextSize(Math.max(10, height * 0.016f));
            paint.setColor(sideColor);
            canvas.drawText(sideName + " ACTION", width / 2f, y + pillH * (review != null ? 0.20f : 0.25f), paint);
            paint.setTextSize(titleSize);
            paint.setColor(Color.WHITE);
            canvas.drawText(action, width / 2f, y + pillH * (review != null ? 0.46f : 0.54f), paint);
            paint.setTextSize(resultSize);
            paint.setColor(event.optBoolean("isHit", false) ? sideColor : Color.rgb(255, 206, 84));
            canvas.drawText(result, width / 2f, y + pillH * (review != null ? 0.68f : 0.82f), paint);
            if (review != null) {
                paint.setTextSize(Math.max(10, height * 0.016f));
                paint.setColor(Color.argb(190, 255, 255, 255));
                canvas.drawText(review, width / 2f, y + pillH * 0.88f, paint);
            }
            paint.setAlpha(255);
        }

        // The referee's review outcome is secondary context on the fencer's
        // action, not an action of its own.
        private static String reviewBadge(JSONObject event) {
            String status = event.optString("reviewStatus", "");
            if ("confirmed".equals(status)) return "VIDEO REVIEW - KEPT";
            if ("corrected".equals(status) || "overturned".equals(status)) return "VIDEO REVIEW - CORRECTED";
            return null;
        }

        private JSONObject visibleEvent(double boutTime) {
            JSONArray events = match.optJSONArray("events");
            if (events == null) return null;
            JSONObject visible = null;
            for (int i = 0; i < events.length(); i++) {
                JSONObject event = events.optJSONObject(i);
                if (event == null) continue;
                // Video-review outcome events (referee, actionId 3) render as a
                // badge on the fencer's pill, never as their own pill.
                if (event.optInt("actionId", 0) == 3) continue;
                double t = overlayEventTime(event);
                if (boutTime >= t && boutTime < t + PILL_SECONDS) visible = event;
            }
            return visible;
        }

        private JSONObject replayStateAt(double boutTime) {
            int periodDuration = Math.max(1, match.optInt("periodDuration", 180));
            int totalPeriods = Math.max(1, match.optInt("totalPeriods", 1));
            int period = Math.min(totalPeriods, Math.max(1, (int) Math.floor(boutTime / periodDuration) + 1));
            double periodElapsed = Math.max(0, boutTime - (period - 1) * periodDuration);
            JSONObject state = new JSONObject();
            try {
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
                        JSONObject ev = events.optJSONObject(i);
                        if (ev == null || overlayEventTime(ev) > boutTime) continue;
                        applyEventToState(ev, state);
                    }
                }
            } catch (Exception ignored) {}
            return state;
        }

        private void applyEventToState(JSONObject event, JSONObject state) throws Exception {
            if (event.optBoolean("isHit", false)) {
                if ("L".equals(event.optString("side"))) {
                    state.put("scoreL", state.optInt("scoreL") + 1);
                    state.put("hpR", Math.max(0, state.optInt("hpR") - 5));
                }
                if ("R".equals(event.optString("side"))) {
                    state.put("scoreR", state.optInt("scoreR") + 1);
                    state.put("hpL", Math.max(0, state.optInt("hpL") - 5));
                }
            }
            int actionId = event.optInt("actionId", 0);
            if (actionId == 191) {
                state.put("scoreR", state.optInt("scoreR") + 1);
                state.put("hpL", Math.max(0, state.optInt("hpL") - 5));
            }
            if (actionId == 291) {
                state.put("scoreL", state.optInt("scoreL") + 1);
                state.put("hpR", Math.max(0, state.optInt("hpR") - 5));
            }
        }

        private double videoTimeToBoutTime(double videoTime) {
            if (segments != null && segments.length() > 0) {
                double cursor = 0.0;
                JSONObject fallback = null;
                double fallbackCursor = 0.0;
                for (int i = 0; i < segments.length(); i++) {
                    JSONObject segment = segments.optJSONObject(i);
                    if (segment == null) continue;
                    double duration = Math.max(0.0, segment.optDouble("durationMs", 0.0) / 1000.0);
                    fallback = segment;
                    fallbackCursor = cursor;
                    if (duration <= 0.0 || videoTime <= cursor + duration || i == segments.length() - 1) {
                        return segmentVideoTimeToBoutTime(
                                Math.max(0.0, videoTime - cursor),
                                segment.optDouble("startBoutTs", 0.0),
                                segment.optJSONArray("pauses")
                        );
                    }
                    cursor += duration;
                }
                if (fallback != null) {
                    return segmentVideoTimeToBoutTime(
                            Math.max(0.0, videoTime - fallbackCursor),
                            fallback.optDouble("startBoutTs", 0.0),
                            fallback.optJSONArray("pauses")
                    );
                }
            }

            return segmentVideoTimeToBoutTime(
                    videoTime,
                    timeOffset,
                    useRecordingPauses ? match.optJSONArray("recordingPauses") : null
            );
        }

        private double segmentVideoTimeToBoutTime(double videoTime, double offset, JSONArray pauses) {
            double boutTime = videoTime + offset;
            if (pauses == null) return boutTime;
            double pausedTotal = 0;
            for (int i = 0; i < pauses.length(); i++) {
                JSONObject pause = pauses.optJSONObject(i);
                if (pause == null) continue;
                double start = pause.optDouble("startVideo", Double.NaN);
                double end = pause.optDouble("endVideo", Double.NaN);
                if (!Double.isFinite(start) || !Double.isFinite(end) || end <= start) continue;
                if (videoTime <= start) break;
                double freeze = Math.max(pause.optDouble("boutTime", start + offset - pausedTotal), start + offset - pausedTotal);
                if (videoTime < end) return freeze;
                pausedTotal += end - start;
            }
            return boutTime - pausedTotal;
        }

        private double overlayEventTime(JSONObject event) {
            int period = Math.max(1, event.optInt("period", 1));
            int periodDuration = Math.max(1, match.optInt("periodDuration", 180));
            return (period - 1) * periodDuration + event.optDouble("ts", 0);
        }

        private static float hpRatio(JSONObject state, String side) {
            int hp = state.optInt("L".equals(side) ? "hpL" : "hpR", 100);
            return Math.max(0f, Math.min(1f, hp / 100f));
        }

        private static int sideColor(String side) {
            if ("L".equals(side)) return Color.rgb(0, 199, 255);
            if ("R".equals(side)) return Color.rgb(230, 38, 0);
            return Color.rgb(245, 200, 66);
        }

        private static String shortName(String name) {
            if (name == null) return "";
            String trimmed = name.trim();
            return trimmed.length() <= 12 ? trimmed : trimmed.substring(0, 12);
        }

        private static String fmtTime(int seconds) {
            int s = Math.max(0, seconds);
            return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
        }
    }
}
