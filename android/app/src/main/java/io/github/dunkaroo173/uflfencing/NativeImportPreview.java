package io.github.dunkaroo173.uflfencing;

import android.content.Context;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import java.util.Locale;

// SurfaceView, not TextureView: the WebView's transparent areas only reveal
// content rendered BELOW the window (same mechanism as the camera's
// PreviewView). A TextureView sibling under the WebView is not composited.
class NativeImportPreview extends SurfaceView implements SurfaceHolder.Callback {
    interface Callback {
        void onEnded();
        void onError(String message);
        void onProgress(long positionMs, long durationMs, boolean playing);
    }

    private static final String TAG = "UFLImportPreview";
    private static final long PROGRESS_INTERVAL_MS = 250L;

    private MediaPlayer player;
    private boolean surfaceReady;
    private Uri pendingUri;
    private boolean playWhenReady;
    private Callback callback;
    private double playbackSpeed = 1.0;
    private final Handler progressHandler = new Handler(Looper.getMainLooper());
    private final Runnable progressTicker = new Runnable() {
        @Override
        public void run() {
            MediaPlayer active = player;
            if (active != null && callback != null) {
                try {
                    callback.onProgress(active.getCurrentPosition(), active.getDuration(), active.isPlaying());
                } catch (Exception ignored) {}
            }
            if (active != null) progressHandler.postDelayed(this, PROGRESS_INTERVAL_MS);
        }
    };

    NativeImportPreview(Context context) {
        super(context);
        init();
    }

    NativeImportPreview(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    void setCallback(Callback callback) {
        this.callback = callback;
    }

    void setVideoUri(Uri uri) {
        pendingUri = uri;
        playWhenReady = false;
        releasePlayer();
        if (surfaceReady) {
            preparePlayer();
        }
    }

    void play() {
        playWhenReady = true;
        if (player != null) {
            player.start();
            applySpeed();
        } else if (pendingUri != null && surfaceReady) {
            preparePlayer();
        }
    }

    void pause() {
        playWhenReady = false;
        if (player != null && player.isPlaying()) {
            player.pause();
        }
    }

    void seekToMs(int millis) {
        if (player == null) return;
        long target = Math.max(0, millis);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            player.seekTo(target, MediaPlayer.SEEK_CLOSEST);
        } else {
            player.seekTo((int) target);
        }
    }

    void setSpeed(double speed) {
        playbackSpeed = speed <= 0 ? 1.0 : speed;
        // Setting PlaybackParams on a paused MediaPlayer starts playback;
        // only apply while playing and re-apply on the next play().
        if (player != null && player.isPlaying()) applySpeed();
    }

    private void applySpeed() {
        if (player == null) return;
        try {
            player.setPlaybackParams(player.getPlaybackParams().setSpeed((float) playbackSpeed));
        } catch (Exception e) {
            Log.w(TAG, "setSpeed failed", e);
        }
    }

    void clear() {
        pendingUri = null;
        playWhenReady = false;
        releasePlayer();
        setVisibility(GONE);
    }

    void release() {
        clear();
    }

    private void init() {
        getHolder().addCallback(this);
        setZOrderOnTop(false);
    }

    private void preparePlayer() {
        if (pendingUri == null || !surfaceReady) return;
        try {
            player = new MediaPlayer();
            player.setDisplay(getHolder());
            player.setDataSource(getContext(), pendingUri);
            player.setVolume(0f, 0f);
            player.setOnPreparedListener(mp -> {
                fitToVideo(mp.getVideoWidth(), mp.getVideoHeight());
                seekToMs(0);
                if (playWhenReady) {
                    mp.start();
                    applySpeed();
                }
                progressHandler.removeCallbacks(progressTicker);
                progressHandler.post(progressTicker);
            });
            player.setOnVideoSizeChangedListener((mp, width, height) -> fitToVideo(width, height));
            player.setOnCompletionListener(mp -> {
                playWhenReady = false;
                if (callback != null) callback.onEnded();
            });
            player.setOnErrorListener((mp, what, extra) -> {
                String message = String.format(Locale.US, "Import preview failed: %d/%d", what, extra);
                Log.e(TAG, message);
                if (callback != null) callback.onError(message);
                return true;
            });
            player.prepareAsync();
        } catch (Exception e) {
            Log.e(TAG, "prepare failed", e);
            if (callback != null) callback.onError(e.getMessage() == null ? "Import preview failed" : e.getMessage());
        }
    }

    private void releasePlayer() {
        progressHandler.removeCallbacks(progressTicker);
        if (player != null) {
            try {
                player.reset();
                player.release();
            } catch (Exception ignored) {}
            player = null;
        }
    }

    // SurfaceView cannot transform its content: letterbox by sizing the view
    // to the video's aspect ratio, centered in the parent.
    private void fitToVideo(int videoWidth, int videoHeight) {
        ViewGroup parent = (ViewGroup) getParent();
        if (parent == null || videoWidth <= 0 || videoHeight <= 0) return;
        int parentWidth = parent.getWidth();
        int parentHeight = parent.getHeight();
        if (parentWidth <= 0 || parentHeight <= 0) return;
        float scale = Math.min(parentWidth / (float) videoWidth, parentHeight / (float) videoHeight);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                Math.round(videoWidth * scale),
                Math.round(videoHeight * scale),
                Gravity.CENTER);
        setLayoutParams(params);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        surfaceReady = true;
        if (player != null) {
            player.setDisplay(holder);
        } else {
            preparePlayer();
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceReady = false;
        if (player != null) {
            try {
                player.setDisplay(null);
            } catch (Exception ignored) {}
        }
    }
}
