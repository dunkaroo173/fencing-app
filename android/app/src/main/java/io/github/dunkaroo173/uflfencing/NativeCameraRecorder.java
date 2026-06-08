package io.github.dunkaroo173.uflfencing;

import android.Manifest;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.MediaStore;
import android.view.View;
import androidx.camera.core.CameraEffect;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.Preview;
import androidx.camera.core.UseCaseGroup;
import androidx.camera.effects.OverlayEffect;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.MediaStoreOutputOptions;
import androidx.camera.video.PendingRecording;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

class NativeCameraRecorder {
    interface Callback {
        void onReady();
        void onStarted();
        void onStopped(Uri uri, String displayName, long durationMs);
        void onError(String message);
    }

    private static final int TARGET_BITRATE = 6_000_000;

    private final MainActivity activity;
    private final PreviewView previewView;
    private final Callback callback;
    private final ExecutorService cameraExecutor = Executors.newSingleThreadExecutor();
    private final HandlerThread overlayThread = new HandlerThread("UFLNativeOverlay");
    private final NativeMatchOverlayPainter painter = new NativeMatchOverlayPainter();

    private ProcessCameraProvider cameraProvider;
    private VideoCapture<Recorder> videoCapture;
    private OverlayEffect overlayEffect;
    private Recording recording;
    private String activeDisplayName;

    NativeCameraRecorder(MainActivity activity, PreviewView previewView, Callback callback) {
        this.activity = activity;
        this.previewView = previewView;
        this.callback = callback;
        overlayThread.start();
    }

    void prepare(String matchJson) {
        updateOverlay(matchJson);
        activity.runOnUiThread(() -> previewView.setVisibility(View.VISIBLE));
        if (videoCapture != null) {
            callback.onReady();
            return;
        }
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(activity);
        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                bindUseCases();
                callback.onReady();
            } catch (Exception e) {
                callback.onError(errorMessage(e));
            }
        }, ContextCompat.getMainExecutor(activity));
    }

    void start(String filenameBase, String matchJson) {
        updateOverlay(matchJson);
        if (recording != null) return;
        if (videoCapture == null) {
            callback.onError("Native camera is not ready");
            return;
        }
        try {
            activeDisplayName = safeFilePart(filenameBase, "ufl-recording") + ".mp4";
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME, activeDisplayName);
            values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/UFL Fencing");
            }

            MediaStoreOutputOptions outputOptions = new MediaStoreOutputOptions.Builder(
                    activity.getContentResolver(),
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            ).setContentValues(values).build();

            PendingRecording pending = videoCapture.getOutput().prepareRecording(activity, outputOptions);
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                pending = pending.withAudioEnabled();
            }
            recording = pending.start(cameraExecutor, this::handleRecordEvent);
            callback.onStarted();
        } catch (Exception e) {
            callback.onError(errorMessage(e));
        }
    }

    void stop(String matchJson) {
        updateOverlay(matchJson);
        Recording active = recording;
        if (active != null) {
            active.stop();
            recording = null;
        } else {
            activity.runOnUiThread(() -> previewView.setVisibility(View.GONE));
        }
    }

    void updateOverlay(String matchJson) {
        try {
            if (matchJson == null || matchJson.isEmpty() || "null".equals(matchJson)) return;
            painter.setMatch(new JSONObject(matchJson));
        } catch (Exception ignored) {}
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
        if (overlayEffect != null) {
            try { overlayEffect.close(); } catch (Exception ignored) {}
        }
        overlayThread.quitSafely();
        cameraExecutor.shutdownNow();
    }

    private void bindUseCases() {
        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        Recorder recorder = new Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HD, androidx.camera.video.FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
                .setTargetVideoEncodingBitRate(TARGET_BITRATE)
                .build();
        videoCapture = VideoCapture.withOutput(recorder);

        overlayEffect = new OverlayEffect(
                CameraEffect.VIDEO_CAPTURE,
                3,
                new Handler(overlayThread.getLooper()),
                throwable -> callback.onError(errorMessage(throwable))
        );
        overlayEffect.setOnDrawListener(frame -> {
            painter.drawTransparent(frame.getOverlayCanvas(), frame.getSize().getWidth(), frame.getSize().getHeight());
            return true;
        });

        UseCaseGroup group = new UseCaseGroup.Builder()
                .addUseCase(preview)
                .addUseCase(videoCapture)
                .addEffect(overlayEffect)
                .build();

        cameraProvider.unbindAll();
        cameraProvider.bindToLifecycle(activity, CameraSelector.DEFAULT_BACK_CAMERA, group);
    }

    private void handleRecordEvent(VideoRecordEvent event) {
        if (event instanceof VideoRecordEvent.Finalize) {
            VideoRecordEvent.Finalize finalize = (VideoRecordEvent.Finalize) event;
            activity.runOnUiThread(() -> previewView.setVisibility(View.GONE));
            if (finalize.hasError()) {
                callback.onError("Native recording failed: " + finalize.getError());
                return;
            }
            Uri uri = finalize.getOutputResults().getOutputUri();
            long durationMs = Math.max(0L, event.getRecordingStats().getRecordedDurationNanos() / 1_000_000L);
            callback.onStopped(uri, activeDisplayName, durationMs);
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
