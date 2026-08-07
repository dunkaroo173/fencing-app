package io.github.dunkaroo173.uflfencing;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.util.Log;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import androidx.core.content.FileProvider;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

public class AndroidVideoBridge {
    private static final String TAG = "UFLAndroidVideo";
    private final MainActivity activity;
    private final WebView webView;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Map<String, FileOutputStream> sessions = new ConcurrentHashMap<>();
    private final Map<String, File> sessionFiles = new ConcurrentHashMap<>();
    // Source path -> MediaStore uri, so re-exporting or re-saving the same file
    // does not create duplicate gallery entries.
    private final Map<String, String> savedToMediaStore = new ConcurrentHashMap<>();

    AndroidVideoBridge(MainActivity activity, WebView webView) {
        this.activity = activity;
        this.webView = webView;
    }

    @JavascriptInterface
    public void selectVideo() {
        activity.runOnUiThread(activity::pickVideo);
    }

    @JavascriptInterface
    public void debugLog(String message) {
        Log.i(TAG, message == null ? "" : message);
    }

    @JavascriptInterface
    public String beginRecordedVideo(String mimeType, String filename) {
        try {
            String sessionId = UUID.randomUUID().toString();
            File dir = new File(activity.getCacheDir(), "native-video-sources");
            if (!dir.exists()) dir.mkdirs();
            String ext = mimeType != null && mimeType.contains("mp4") ? ".mp4" : ".webm";
            File file = new File(dir, safeFilePart(filename, "recorded") + "-" + sessionId + ext);
            sessions.put(sessionId, new FileOutputStream(file));
            sessionFiles.put(sessionId, file);
            return sessionId;
        } catch (Exception e) {
            emitError("recorded", e);
            return "";
        }
    }

    @JavascriptInterface
    public void appendRecordedVideoChunk(String sessionId, String base64Chunk) {
        try {
            FileOutputStream out = sessions.get(sessionId);
            if (out == null) throw new IllegalStateException("Unknown recording session");
            byte[] bytes = Base64.decode(base64Chunk, Base64.DEFAULT);
            out.write(bytes);
        } catch (Exception e) {
            emitError("recorded", e);
        }
    }

    @JavascriptInterface
    public String finishRecordedVideo(String sessionId) {
        try {
            FileOutputStream out = sessions.remove(sessionId);
            if (out != null) {
                out.flush();
                out.close();
            }
            File file = sessionFiles.get(sessionId);
            if (file == null) throw new IllegalStateException("Recording session did not produce a file");
            return Uri.fromFile(file).toString();
        } catch (Exception e) {
            emitError("recorded", e);
            return "";
        }
    }

    @JavascriptInterface
    public void exportOverlay(String payloadJson) {
        executor.execute(() -> {
            String errorSourceType = "native";
            try {
                JSONObject payload = new JSONObject(payloadJson);
                final String sourceType = payload.optString("sourceType", "native");
                errorSourceType = sourceType;
                emitProgress(sourceType, 0, "Preparing native export");
                NativeOverlayExporter.Callback callback = new NativeOverlayExporter.Callback() {
                    @Override
                    public void onProgress(double progress, String message) {
                        emitProgress(sourceType, progress, message);
                    }
                };
                NativeOverlayExporter.Result result;
                int segmentCount = payload.optJSONArray("segments") == null ? 0 : payload.optJSONArray("segments").length();
                boolean hasSegments = segmentCount > 0;
                if ("imported".equals(sourceType) || hasSegments) {
                    try {
                        result = new Media3OverlayExporter(activity, callback).export(payload);
                    } catch (Exception media3Error) {
                        if (hasSegments) {
                            // The compatibility exporter cannot stitch segments;
                            // surface an actionable error instead of falling back.
                            throw new Exception("Stitched export of " + segmentCount + " recording segments failed: "
                                    + (media3Error.getMessage() == null ? media3Error.toString() : media3Error.getMessage()), media3Error);
                        }
                        emitProgress(sourceType, 0.03, "Media3 export failed; using compatibility exporter");
                        result = new NativeOverlayExporter(activity, callback).export(payload);
                    }
                } else {
                    result = new NativeOverlayExporter(activity, callback).export(payload);
                }
                emitComplete(sourceType, result);
            } catch (Exception e) {
                emitError(errorSourceType, e);
            }
        });
    }

    @JavascriptInterface
    public void saveRecordedSegment(String sourceUri, String displayName, long durationMs) {
        executor.execute(() -> {
            try {
                Uri uri = Uri.parse(sourceUri);
                File file = fileFromUri(uri);
                if (file == null || !file.exists()) throw new IllegalArgumentException("Recording segment is not available");
                Uri savedUri = saveToMediaStoreOnce(file, displayName == null ? file.getName() : displayName);
                JSONObject payload = new JSONObject();
                payload.put("sourceType", "recorded");
                payload.put("uri", sourceUri);
                if (savedUri != null) payload.put("savedUri", savedUri.toString());
                payload.put("displayName", displayName == null ? file.getName() : displayName);
                payload.put("durationMs", Math.max(0L, durationMs));
                emit("window.onAndroidExportComplete", payload);
            } catch (Exception e) {
                emitError("recorded", e);
            }
        });
    }

    @JavascriptInterface
    public void prepareNativeRecording(String matchJson) {
        activity.runOnUiThread(() -> activity.prepareNativeRecording(matchJson));
    }

    @JavascriptInterface
    public void startNativeRecording(String filenameBase, String matchJson) {
        activity.runOnUiThread(() -> activity.startNativeRecording(filenameBase, matchJson));
    }

    @JavascriptInterface
    public void stopNativeRecording(String matchJson) {
        activity.runOnUiThread(() -> activity.stopNativeRecording(matchJson));
    }

    @JavascriptInterface
    public void shareExport(String outputUri, String displayName) {
        try {
            Log.i(TAG, "shareExport uri=" + outputUri + " displayName=" + displayName);
            Uri uri = Uri.parse(outputUri);
            android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_SEND);
            intent.setType("video/mp4");
            intent.putExtra(android.content.Intent.EXTRA_STREAM, uri);
            intent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(android.content.Intent.createChooser(intent, displayName == null ? "Share overlay video" : displayName));
        } catch (Exception e) {
            Log.e(TAG, "shareExport failed", e);
            emitError("share", e);
        }
    }

    @JavascriptInterface
    public void playImportedPreview() {
        activity.playNativePreview();
    }

    @JavascriptInterface
    public void pauseImportedPreview() {
        activity.pauseNativePreview();
    }

    @JavascriptInterface
    public void seekImportedPreview(double seconds) {
        activity.seekNativePreview(seconds);
    }

    @JavascriptInterface
    public void setImportedPreviewSpeed(double speed) {
        activity.setNativePreviewSpeed(speed);
    }

    void onImportedPreviewProgress(long positionMs, long durationMs, boolean playing) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("positionMs", positionMs);
            payload.put("durationMs", durationMs);
            payload.put("playing", playing);
            emit("window.onAndroidImportedPreviewProgress", payload);
        } catch (Exception ignored) {}
    }

    @JavascriptInterface
    public void clearImportedPreview() {
        activity.clearNativePreview();
    }

    @JavascriptInterface
    public void clearNativeRecordingPreview() {
        activity.runOnUiThread(activity::clearNativeRecordingPreview);
    }

    @JavascriptInterface
    public void startVideoReview(String payloadJson) {
        activity.showVideoReview(payloadJson);
    }

    @JavascriptInterface
    public void closeVideoReview() {
        activity.hideVideoReview();
    }

    void onVideoPicked(Uri uri) {
        executor.execute(() -> {
            try {
                JSONObject payload = NativeVideoMetadata.forUri(activity, uri);
                payload.put("uri", uri.toString());
                activity.setNativePreviewUri(uri);
                emit("window.onAndroidVideoSelected", payload);
            } catch (Exception e) {
                emitError("imported", e);
            }
        });
    }

    void onVideoPickCanceled() {
        emit("window.onAndroidVideoPickCanceled", new JSONObject());
    }

    void onNativeRecordingReady() {
        try {
            JSONObject payload = new JSONObject();
            payload.put("ready", true);
            emit("window.onAndroidRecordingReady", payload);
        } catch (Exception ignored) {}
    }

    void onNativeRecordingStarted() {
        try {
            JSONObject payload = new JSONObject();
            payload.put("recording", true);
            emit("window.onAndroidRecordingStarted", payload);
        } catch (Exception ignored) {}
    }

    void onNativeRecordingStopped(Uri uri, String displayName, long durationMs) {
        try {
            Log.i(TAG, "native recording stopped uri=" + uri + " displayName=" + displayName + " durationMs=" + durationMs);
            JSONObject payload = new JSONObject();
            payload.put("sourceType", "recorded");
            payload.put("uri", uri.toString());
            payload.put("savedUri", uri.toString());
            payload.put("displayName", displayName == null ? "ufl-recording.mp4" : displayName);
            payload.put("durationMs", durationMs);
            emit("window.onAndroidRecordingStopped", payload);
        } catch (Exception e) {
            emitError("recorded", e);
        }
    }

    void onNativeRecordingError(String message) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("sourceType", "recorded");
            payload.put("message", message == null ? "Native recording failed" : message);
            emit("window.onAndroidRecordingError", payload);
        } catch (Exception ignored) {}
    }

    void onVideoReviewKeep(String eventId, int eventIndex, double chosenTimeSec, double clipStartSec, double clipEndSec, double playbackRate) {
        emitVideoReviewDecision("window.onAndroidVideoReviewKeep", eventId, eventIndex, chosenTimeSec, clipStartSec, clipEndSec, playbackRate);
    }

    void onVideoReviewEdit(String eventId, int eventIndex, double chosenTimeSec, double clipStartSec, double clipEndSec, double playbackRate) {
        emitVideoReviewDecision("window.onAndroidVideoReviewEdit", eventId, eventIndex, chosenTimeSec, clipStartSec, clipEndSec, playbackRate);
    }

    void onVideoReviewNavigate(String eventId, int eventIndex, int direction) {
        try {
            JSONObject payload = new JSONObject();
            if (eventId != null && !eventId.isEmpty()) payload.put("eventId", eventId);
            payload.put("eventIndex", eventIndex);
            payload.put("direction", direction);
            emit("window.onAndroidVideoReviewNavigate", payload);
        } catch (Exception ignored) {}
    }

    void onVideoReviewClose() {
        emit("window.onAndroidVideoReviewClose", new JSONObject());
    }

    void onVideoReviewError(String message) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("message", message == null ? "Video review failed" : message);
            emit("window.onAndroidVideoReviewError", payload);
        } catch (Exception ignored) {}
    }

    void shutdown() {
        executor.shutdownNow();
        for (FileOutputStream out : sessions.values()) {
            try { out.close(); } catch (Exception ignored) {}
        }
        sessions.clear();
    }

    private void emitProgress(String sourceType, double progress, String message) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("sourceType", sourceType);
            payload.put("progress", progress);
            payload.put("message", message);
            emit("window.onAndroidExportProgress", payload);
        } catch (Exception ignored) {}
    }

    private void emitVideoReviewDecision(String functionName, String eventId, int eventIndex, double chosenTimeSec,
                                         double clipStartSec, double clipEndSec, double playbackRate) {
        try {
            JSONObject payload = new JSONObject();
            if (eventId != null && !eventId.isEmpty()) payload.put("eventId", eventId);
            payload.put("eventIndex", eventIndex);
            payload.put("chosenTime", chosenTimeSec);
            payload.put("clipStart", clipStartSec);
            payload.put("clipEnd", clipEndSec);
            payload.put("playbackRate", playbackRate);
            emit(functionName, payload);
        } catch (Exception ignored) {}
    }

    private void emitComplete(String sourceType, NativeOverlayExporter.Result result) throws Exception {
        JSONObject payload = new JSONObject();
        Uri shareUri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".fileprovider", result.file);
        Uri savedUri = saveToMediaStoreOnce(result.file, result.displayName);
        payload.put("sourceType", sourceType);
        payload.put("uri", shareUri.toString());
        if (savedUri != null) payload.put("savedUri", savedUri.toString());
        payload.put("displayName", result.displayName);
        payload.put("durationMs", result.durationMs);
        emit("window.onAndroidExportComplete", payload);
    }

    private void emitError(String sourceType, Exception e) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("sourceType", sourceType);
            payload.put("message", e.getMessage() == null ? e.toString() : e.getMessage());
            emit("window.onAndroidExportError", payload);
        } catch (Exception ignored) {}
    }

    private void emit(String functionName, JSONObject payload) {
        String js = functionName + "(" + JSONObject.quote(payload.toString()) + ")";
        activity.runOnUiThread(() -> webView.evaluateJavascript(js, null));
    }

    private static String safeFilePart(String value, String fallback) {
        String s = value == null ? "" : value.replaceAll("[^A-Za-z0-9._-]+", "-").replaceAll("^-+|-+$", "");
        return s.isEmpty() ? fallback : s;
    }

    private File fileFromUri(Uri uri) {
        if (uri == null) return null;
        if ("file".equals(uri.getScheme())) return new File(uri.getPath());
        if ("content".equals(uri.getScheme()) && activity.getPackageName().concat(".fileprovider").equals(uri.getAuthority())) {
            String path = uri.getPath();
            if (path == null) return null;
            int slash = path.indexOf('/', 1);
            if (slash < 0 || slash + 1 >= path.length()) return null;
            String relative = Uri.decode(path.substring(slash + 1));
            return new File(activity.getCacheDir(), relative);
        }
        return null;
    }

    private Uri saveToMediaStoreOnce(File file, String displayName) throws Exception {
        String key = file.getAbsolutePath();
        String cached = savedToMediaStore.get(key);
        if (cached != null) return Uri.parse(cached);
        Uri savedUri = saveToMediaStore(file, displayName);
        if (savedUri != null) savedToMediaStore.put(key, savedUri.toString());
        return savedUri;
    }

    private Uri saveToMediaStore(File file, String displayName) throws Exception {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null;

        ContentResolver resolver = activity.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Video.Media.DISPLAY_NAME, safeFilePart(displayName, "ufl-overlay.mp4"));
        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        values.put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/UFL Fencing");
        values.put(MediaStore.Video.Media.IS_PENDING, 1);

        Uri uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IllegalStateException("Could not create media output");

        try (FileInputStream in = new FileInputStream(file);
             OutputStream out = resolver.openOutputStream(uri)) {
            if (out == null) throw new IllegalStateException("Could not open media output");
            byte[] buffer = new byte[256 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        } catch (Exception e) {
            resolver.delete(uri, null, null);
            throw e;
        }

        values.clear();
        values.put(MediaStore.Video.Media.IS_PENDING, 0);
        resolver.update(uri, values, null, null);

        // The transformer carries the SOURCE video's creation_time into the
        // output container, and the media scanner dates the gallery entry from
        // it - an export of Monday's footage lands in Monday's camera roll.
        // Re-stamp after publishing (the scanner re-extracts on IS_PENDING=0).
        values.clear();
        values.put(MediaStore.Video.Media.DATE_TAKEN, System.currentTimeMillis());
        resolver.update(uri, values, null, null);
        return uri;
    }
}
