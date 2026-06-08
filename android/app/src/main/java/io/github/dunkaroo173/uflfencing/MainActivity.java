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
    private WebView webView;
    private AndroidVideoBridge videoBridge;
    private NativeImportPreview nativeImportPreview;
    private PreviewView nativeCameraPreview;
    private NativeCameraRecorder nativeCameraRecorder;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout root = new FrameLayout(this);
        webView = new WebView(this);
        webView.setBackgroundColor(Color.TRANSPARENT);
        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        nativeImportPreview = new NativeImportPreview(this);
        nativeImportPreview.setVisibility(android.view.View.GONE);
        nativeImportPreview.setAlpha(0.42f);
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
        setContentView(root);

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

    void updateNativeRecordingOverlay(String matchJson) {
        if (nativeCameraRecorder != null) nativeCameraRecorder.updateOverlay(matchJson);
    }

    void clearNativeRecordingPreview() {
        if (nativeCameraRecorder != null) nativeCameraRecorder.clearPreview();
    }


    private void loadApp() {
        webView.loadUrl("file:///android_asset/public/index.html");
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
    public void onRequestPermissionsResult(int code, @NonNull String[] perms,
                                           @NonNull int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        loadApp();
    }

    @Override
    protected void onDestroy() {
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
