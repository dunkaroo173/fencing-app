package io.github.dunkaroo173.uflfencing;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.ViewGroup;
import android.webkit.ConsoleMessage;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import android.widget.FrameLayout;
import org.json.JSONObject;

public class MainActivity extends AppCompatActivity {

    private static final int PERM_CODE = 1;
    private static final int PICK_VIDEO_CODE = 2;
    private static final String TAG = "UFLMainActivity";
    public static final String EXTRA_NATIVE_REVIEW_URI = "io.github.dunkaroo173.uflfencing.NATIVE_REVIEW_URI";
    public static final String EXTRA_NATIVE_REVIEW_MATCH = "io.github.dunkaroo173.uflfencing.NATIVE_REVIEW_MATCH";
    public static final String EXTRA_NATIVE_REVIEW_EVENT_INDEX = "io.github.dunkaroo173.uflfencing.NATIVE_REVIEW_EVENT_INDEX";
    private WebView webView;
    private AndroidVideoBridge videoBridge;
    private NativeImportPreview nativeImportPreview;
    private PreviewView nativeCameraPreview;
    private NativeCameraRecorder nativeCameraRecorder;
    private NativeVideoReviewView nativeVideoReviewView;
    private boolean nativeReviewOnly;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Match tool with live recording: if the screen sleeps, the activity
        // pauses and CameraX kills the recording mid-bout. Keep the screen on.
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        FrameLayout root = new FrameLayout(this);
        webView = new WebView(this);
        webView.setBackgroundColor(Color.TRANSPARENT);
        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        nativeImportPreview = new NativeImportPreview(this);
        nativeImportPreview.setVisibility(android.view.View.GONE);
        nativeImportPreview.setAlpha(1.0f);
        nativeImportPreview.setClickable(false);
        nativeImportPreview.setFocusable(false);
        nativeImportPreview.setCallback(new NativeImportPreview.Callback() {
            @Override
            public void onEnded() {
                runOnUiThread(() -> webView.evaluateJavascript("window.onAndroidImportedPreviewEnded&&window.onAndroidImportedPreviewEnded()", null));
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> webView.evaluateJavascript(
                        "window.onAndroidImportedPreviewError&&window.onAndroidImportedPreviewError(" + JSONObject.quote(message) + ")",
                        null));
            }

            @Override
            public void onProgress(long positionMs, long durationMs, boolean playing) {
                if (videoBridge != null) videoBridge.onImportedPreviewProgress(positionMs, durationMs, playing);
            }
        });
        root.addView(nativeImportPreview, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        nativeCameraPreview = new PreviewView(this);
        nativeCameraPreview.setVisibility(android.view.View.GONE);
        nativeCameraPreview.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        nativeCameraPreview.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        nativeCameraPreview.setAlpha(0.30f);
        nativeCameraPreview.setClickable(false);
        nativeCameraPreview.setFocusable(false);
        root.addView(nativeCameraPreview, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        nativeVideoReviewView = new NativeVideoReviewView(this);
        nativeVideoReviewView.setCallback(new NativeVideoReviewView.Callback() {
            @Override
            public void onKeep(String eventId, int eventIndex, double chosenTimeSec, double clipStartSec, double clipEndSec, double playbackRate) {
                if (nativeReviewOnly) finish();
                else if (videoBridge != null) videoBridge.onVideoReviewKeep(eventId, eventIndex, chosenTimeSec, clipStartSec, clipEndSec, playbackRate);
            }

            @Override
            public void onEdit(String eventId, int eventIndex, double chosenTimeSec, double clipStartSec, double clipEndSec, double playbackRate) {
                if (nativeReviewOnly) Log.i(TAG, "native review edit requested");
                else if (videoBridge != null) videoBridge.onVideoReviewEdit(eventId, eventIndex, chosenTimeSec, clipStartSec, clipEndSec, playbackRate);
            }

            @Override
            public void onNavigate(String eventId, int eventIndex, int direction) {
                if (nativeReviewOnly) Log.i(TAG, "native review navigation requested");
                else if (videoBridge != null) videoBridge.onVideoReviewNavigate(eventId, eventIndex, direction);
            }

            @Override
            public void onClose() {
                if (nativeReviewOnly) finish();
                else if (videoBridge != null) videoBridge.onVideoReviewClose();
            }

            @Override
            public void onError(String message) {
                if (nativeReviewOnly) Log.e(TAG, "native review error: " + message);
                else if (videoBridge != null) videoBridge.onVideoReviewError(message);
            }
        });
        root.addView(nativeVideoReviewView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        setContentView(root);

        if (maybeLaunchNativeReview(getIntent())) {
            return;
        }

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        videoBridge = new AndroidVideoBridge(this, webView);
        nativeCameraRecorder = new NativeCameraRecorder(this, nativeCameraPreview, new NativeCameraRecorder.Callback() {
            @Override
            public void onReady() {
                videoBridge.onNativeRecordingReady();
            }

            @Override
            public void onStarted() {
                videoBridge.onNativeRecordingStarted();
            }

            @Override
            public void onStopped(Uri uri, String displayName, long durationMs) {
                videoBridge.onNativeRecordingStopped(uri, displayName, durationMs);
            }

            @Override
            public void onError(String message) {
                videoBridge.onNativeRecordingError(message);
            }
        });
        webView.addJavascriptInterface(videoBridge, "AndroidVideo");
        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
                Log.i(TAG, "console: " + consoleMessage.message());
                return true;
            }

            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                request.grant(request.getResources());
            }
        });

        String[] perms = {Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO};
        boolean needRequest = false;
        for (String p : perms) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                needRequest = true;
                break;
            }
        }
        if (needRequest) {
            ActivityCompat.requestPermissions(this, perms, PERM_CODE);
        } else {
            loadApp();
        }
    }

    void pickVideo() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("video/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, PICK_VIDEO_CODE);
    }

    void setNativePreviewUri(Uri uri) {
        runOnUiThread(() -> {
            Log.i(TAG, "setNativePreviewUri uri=" + uri);
            if (nativeCameraRecorder != null) nativeCameraRecorder.clearPreview();
            nativeImportPreview.setVideoUri(uri);
            nativeImportPreview.setVisibility(android.view.View.VISIBLE);
        });
    }

    void playNativePreview() {
        runOnUiThread(() -> {
            if (nativeImportPreview.getVisibility() == android.view.View.VISIBLE) {
                nativeImportPreview.play();
            }
        });
    }

    void pauseNativePreview() {
        runOnUiThread(() -> nativeImportPreview.pause());
    }

    void seekNativePreview(double seconds) {
        runOnUiThread(() -> nativeImportPreview.seekToMs(Math.max(0, (int) Math.round(seconds * 1000.0))));
    }

    void setNativePreviewSpeed(double speed) {
        runOnUiThread(() -> nativeImportPreview.setSpeed(speed));
    }

    void clearNativePreview() {
        runOnUiThread(() -> {
            nativeImportPreview.clear();
        });
    }

    void prepareNativeRecording(String matchJson) {
        if (nativeCameraRecorder != null) nativeCameraRecorder.prepare(matchJson);
    }

    void startNativeRecording(String filenameBase, String matchJson) {
        if (nativeCameraRecorder != null) nativeCameraRecorder.start(filenameBase, matchJson);
    }

    void stopNativeRecording(String matchJson) {
        if (nativeCameraRecorder != null) nativeCameraRecorder.stop(matchJson);
    }

    void clearNativeRecordingPreview() {
        if (nativeCameraRecorder != null) nativeCameraRecorder.clearPreview();
    }

    void showVideoReview(String payloadJson) {
        runOnUiThread(() -> {
            try {
                pauseNativePreview();
                if (nativeCameraRecorder != null) nativeCameraRecorder.clearPreview();
                nativeVideoReviewView.show(new JSONObject(payloadJson));
            } catch (Exception e) {
                if (videoBridge != null) videoBridge.onVideoReviewError(e.getMessage() == null ? e.toString() : e.getMessage());
            }
        });
    }

    void hideVideoReview() {
        runOnUiThread(() -> nativeVideoReviewView.hide());
    }


    private void loadApp() {
        webView.loadUrl("file:///android_asset/public/index.html");
    }

    private boolean maybeLaunchNativeReview(Intent intent) {
        Uri uri = reviewUriFromIntent(intent);
        if (uri == null) return false;
        nativeReviewOnly = true;
        webView.setVisibility(android.view.View.GONE);
        nativeImportPreview.setVisibility(android.view.View.GONE);
        nativeCameraPreview.setVisibility(android.view.View.GONE);
        getWindow().getDecorView().post(() -> {
            try {
                JSONObject payload = nativeReviewPayload(intent, uri);
                nativeVideoReviewView.show(payload);
            } catch (Exception e) {
                Log.e(TAG, "Could not launch native review", e);
            }
        });
        return true;
    }

    private Uri reviewUriFromIntent(Intent intent) {
        if (intent == null) return null;
        String extraUri = intent.getStringExtra(EXTRA_NATIVE_REVIEW_URI);
        if (extraUri != null && !extraUri.isEmpty()) return Uri.parse(extraUri);
        Uri data = intent.getData();
        if (data == null) return null;
        String type = intent.getType();
        if (Intent.ACTION_VIEW.equals(intent.getAction()) && (type == null || type.startsWith("video/"))) {
            return data;
        }
        return null;
    }

    private JSONObject nativeReviewPayload(Intent intent, Uri uri) throws Exception {
        JSONObject metadata = NativeVideoMetadata.forUri(this, uri);
        long durationMs = Math.max(0L, metadata.optLong("durationMs", 0L));
        double durationSec = durationMs / 1000.0;
        int eventIndex = intent == null ? 0 : Math.max(0, intent.getIntExtra(EXTRA_NATIVE_REVIEW_EVENT_INDEX, 0));
        JSONObject match = null;
        if (intent != null) {
            String matchJson = intent.getStringExtra(EXTRA_NATIVE_REVIEW_MATCH);
            if (matchJson != null && !matchJson.isEmpty()) {
                match = new JSONObject(matchJson);
            }
        }
        if (match == null) {
            match = sampleReviewMatch(durationSec);
        }
        double eventVideoTime = Math.min(Math.max(5.0, durationSec > 0 ? durationSec * 0.5 : 5.0), Math.max(5.0, durationSec - 1.0));
        JSONObject payload = new JSONObject();
        payload.put("sourceUri", uri.toString());
        payload.put("sourceType", "native-review");
        payload.put("eventIndex", eventIndex);
        payload.put("eventVideoTime", eventVideoTime);
        payload.put("clipStart", Math.max(0.0, eventVideoTime - 5.0));
        payload.put("clipEnd", durationSec > 0 ? Math.min(durationSec, eventVideoTime + 2.0) : eventVideoTime + 2.0);
        payload.put("durationSec", durationSec);
        payload.put("playbackRate", 0.5);
        payload.put("showReviewOverlay", false);
        payload.put("match", match);
        payload.put("title", "Native Video Review");
        return payload;
    }

    private JSONObject sampleReviewMatch(double durationSec) throws Exception {
        double ts = Math.min(Math.max(5.0, durationSec > 0 ? durationSec * 0.5 : 5.0), 170.0);
        JSONObject match = new JSONObject();
        match.put("nameL", "LEFT");
        match.put("nameR", "RIGHT");
        match.put("periodDuration", 180);
        match.put("period", 1);
        match.put("timerSec", Math.max(0, 180 - (int) Math.floor(ts)));
        match.put("scoreL", 0);
        match.put("scoreR", 1);
        match.put("hpL", 90);
        match.put("hpR", 100);
        org.json.JSONArray events = new org.json.JSONArray();
        JSONObject event = new JSONObject();
        event.put("ts", ts);
        event.put("period", 1);
        event.put("side", "R");
        event.put("actionId", 200);
        event.put("label", "Simple Attack");
        event.put("emoji", "A");
        event.put("isHit", true);
        event.put("reviewed", false);
        event.put("reviewStatus", "pending");
        events.put(event);
        match.put("events", events);
        return match;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_VIDEO_CODE && resultCode == Activity.RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                try {
                    final int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    getContentResolver().takePersistableUriPermission(uri, flags & Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Exception ignored) {
                    // Some providers do not offer persistable grants; the current grant is still usable.
                }
                videoBridge.onVideoPicked(uri);
            }
        } else if (requestCode == PICK_VIDEO_CODE && videoBridge != null) {
            videoBridge.onVideoPickCanceled();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        maybeLaunchNativeReview(intent);
    }

    @Override
    public void onRequestPermissionsResult(int code, @NonNull String[] perms,
                                           @NonNull int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        loadApp();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // The camera can die while the WebView is paused (doze, backgrounding)
        // and its error callback is never processed - the web layer then keeps
        // the transparent-preview chrome over nothing (a pure black screen).
        // Push the actual recording state so it can resync.
        if (webView != null && nativeCameraRecorder != null && !nativeReviewOnly) {
            boolean recordingActive = nativeCameraRecorder.isRecording();
            webView.evaluateJavascript(
                    "window.onAndroidRecordingStateSync&&window.onAndroidRecordingStateSync(" + recordingActive + ")",
                    null);
        }
    }

    @Override
    protected void onDestroy() {
        if (nativeVideoReviewView != null) nativeVideoReviewView.release();
        if (nativeImportPreview != null) nativeImportPreview.release();
        if (nativeCameraRecorder != null) nativeCameraRecorder.shutdown();
        if (videoBridge != null) videoBridge.shutdown();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
