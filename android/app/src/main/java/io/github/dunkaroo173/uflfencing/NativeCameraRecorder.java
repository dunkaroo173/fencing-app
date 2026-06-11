package io.github.dunkaroo173.uflfencing;

import android.Manifest;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.util.Log;
import android.view.View;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.FileOutputOptions;
import androidx.camera.video.PendingRecording;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.File;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

class NativeCameraRecorder {
    interface Callback {
        void onReady();
        void onStarted();
        void onStopped(Uri uri, String displayName, long durationMs);
        void onError(String message);
    }

    private static final int TARGET_BITRATE = 6_000_000;
    private static final String TAG = "UFLNativeCamera";

    private final MainActivity activity;
    private final PreviewView previewView;
    private final Callback callback;
    private final ExecutorService cameraExecutor = Executors.newSingleThreadExecutor();

    private ProcessCameraProvider cameraProvider;
    private VideoCapture<Recorder> videoCapture;
    private Recording recording;
    private String activeDisplayName;
    private File activeOutputFile;
    private Uri activeOutputUri;

    NativeCameraRecorder(MainActivity activity, PreviewView previewView, Callback callback) {
        this.activity = activity;
        this.previewView = previewView;
        this.callback = callback;
    }

    void prepare(String matchJson) {
        Log.i(TAG, "prepare");
        activity.runOnUiThread(() -> previewView.setVisibility(View.VISIBLE));
        if (videoCapture != null) {
            Log.i(TAG, "prepare already bound");
            callback.onReady();
            return;
        }
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(activity);
        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                bindUseCases();
                Log.i(TAG, "prepare ready");
                callback.onReady();
            } catch (Exception e) {
                Log.e(TAG, "prepare failed", e);
                callback.onError(errorMessage(e));
            }
        }, ContextCompat.getMainExecutor(activity));
    }

    void start(String filenameBase, String matchJson) {
        Log.i(TAG, "start");
        if (recording != null) return;
        if (videoCapture == null) {
            Log.w(TAG, "start before ready");
            callback.onError("Native camera is not ready");
            return;
        }
        try {
            applyTargetRotation();
            activeDisplayName = safeFilePart(filenameBase, "ufl-recording") + ".mp4";
            File dir = new File(activity.getCacheDir(), "native-recording-segments");
            if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("Could not create recording cache");
            activeOutputFile = new File(dir, safeFilePart(filenameBase, "ufl-recording") + "-" + System.currentTimeMillis() + ".mp4");
            activeOutputUri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".fileprovider", activeOutputFile);
            FileOutputOptions outputOptions = new FileOutputOptions.Builder(activeOutputFile).build();

            PendingRecording pending = videoCapture.getOutput().prepareRecording(activity, outputOptions);
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                pending = pending.withAudioEnabled();
            }
            recording = pending.start(cameraExecutor, this::handleRecordEvent);
            Log.i(TAG, "recording started");
        } catch (Exception e) {
            Log.e(TAG, "start failed", e);
            callback.onError(errorMessage(e));
        }
    }

    void stop(String matchJson) {
        Log.i(TAG, "stop");
        Recording active = recording;
        if (active != null) {
            active.stop();
            recording = null;
        } else {
            activity.runOnUiThread(() -> previewView.setVisibility(View.GONE));
        }
    }

    void clearPreview() {
        if (recording != null) return;
        activity.runOnUiThread(() -> previewView.setVisibility(View.GONE));
    }

    void shutdown() {
        try {
            if (recording != null) recording.stop();
        } catch (Exception ignored) {}
        recording = null;
        if (cameraProvider != null) {
            try { cameraProvider.unbindAll(); } catch (Exception ignored) {}
        }
        cameraExecutor.shutdownNow();
    }

    private void bindUseCases() {
        Log.i(TAG, "bindUseCases");
        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        Recorder recorder = new Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HD, androidx.camera.video.FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
                .setTargetVideoEncodingBitRate(TARGET_BITRATE)
                .build();
        videoCapture = VideoCapture.withOutput(recorder);

        cameraProvider.unbindAll();
        cameraProvider.bindToLifecycle(activity, CameraSelector.DEFAULT_BACK_CAMERA, preview, videoCapture);
        applyTargetRotation();
        Log.i(TAG, "bindUseCases complete");
    }

    // CameraX captures the display rotation when the use case is created; if
    // the app was launched while the phone was held portrait (before the
    // landscape lock settled), every recording that session gets tagged
    // portrait. Re-read the settled display rotation before each recording.
    private void applyTargetRotation() {
        try {
            android.view.Display display = previewView.getDisplay();
            if (display != null && videoCapture != null) {
                videoCapture.setTargetRotation(display.getRotation());
            }
        } catch (Exception e) {
            Log.w(TAG, "setTargetRotation failed", e);
        }
    }

    private void handleRecordEvent(VideoRecordEvent event) {
        if (!(event instanceof VideoRecordEvent.Status)) {
            Log.i(TAG, "record event " + event.getClass().getSimpleName());
        }
        if (event instanceof VideoRecordEvent.Start) {
            callback.onStarted();
        }
        if (event instanceof VideoRecordEvent.Finalize) {
            VideoRecordEvent.Finalize finalize = (VideoRecordEvent.Finalize) event;
            activity.runOnUiThread(() -> previewView.setVisibility(View.GONE));
            if (finalize.hasError()) {
                Log.e(TAG, "recording finalized with error " + finalize.getError(), finalize.getCause());
                callback.onError("Native recording failed: " + finalize.getError());
                return;
            }
            Uri uri = activeOutputUri;
            long durationMs = Math.max(0L, event.getRecordingStats().getRecordedDurationNanos() / 1_000_000L);
            callback.onStopped(uri, activeDisplayName, durationMs);
            activeOutputFile = null;
            activeOutputUri = null;
        }
    }

    private static String safeFilePart(String value, String fallback) {
        String s = value == null ? "" : value.replaceAll("[^A-Za-z0-9._-]+", "-").replaceAll("^-+|-+$", "");
        return s.isEmpty() ? fallback : s;
    }

    private static String errorMessage(Throwable e) {
        if (e == null) return "Native camera error";
        String msg = e.getMessage();
        return msg == null || msg.isEmpty()
                ? String.format(Locale.US, "%s", e)
                : msg;
    }
}
