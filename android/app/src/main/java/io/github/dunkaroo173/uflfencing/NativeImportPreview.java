package io.github.dunkaroo173.uflfencing;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import java.util.Locale;

class NativeImportPreview extends TextureView implements TextureView.SurfaceTextureListener {
    interface Callback {
        void onEnded();
        void onError(String message);
        void onProgress(long positionMs, long durationMs, boolean playing);
    }

    private static final String TAG = "UFLImportPreview";
    private static final long PROGRESS_INTERVAL_MS = 250L;

    private MediaPlayer player;
    private Surface surface;
    private Uri pendingUri;
    private boolean playWhenReady;
    private int videoWidth;
    private int videoHeight;
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
        if (isAvailable()) {
            preparePlayer();
        }
    }

    void play() {
        playWhenReady = true;
        if (player != null) {
            player.start();
            applySpeed();
        } else if (pendingUri != null && isAvailable()) {
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
        if (surface != null) {
            surface.release();
            surface = null;
        }
    }

    private void init() {
        setSurfaceTextureListener(this);
    }

    private void preparePlayer() {
        if (pendingUri == null || !isAvailable()) return;
        try {
            if (surface == null) {
                surface = new Surface(getSurfaceTexture());
            }
            player = new MediaPlayer();
            player.setSurface(surface);
            player.setDataSource(getContext(), pendingUri);
            player.setVolume(0f, 0f);
            player.setOnPreparedListener(mp -> {
                videoWidth = mp.getVideoWidth();
                videoHeight = mp.getVideoHeight();
                updateTransform();
                seekToMs(0);
                if (playWhenReady) {
                    mp.start();
                    applySpeed();
                }
                progressHandler.removeCallbacks(progressTicker);
                progressHandler.post(progressTicker);
            });
            player.setOnVideoSizeChangedListener((mp, width, height) -> {
                videoWidth = width;
                videoHeight = height;
                updateTransform();
            });
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
        videoWidth = 0;
        videoHeight = 0;
        setTransform(null);
    }

    private void updateTransform() {
        int viewWidth = getWidth();
        int viewHeight = getHeight();
        if (viewWidth <= 0 || viewHeight <= 0 || videoWidth <= 0 || videoHeight <= 0) return;

        float scale = Math.max(viewWidth / (float) videoWidth, viewHeight / (float) videoHeight);
        float scaledWidth = videoWidth * scale;
        float scaledHeight = videoHeight * scale;

        Matrix matrix = new Matrix();
        matrix.setScale(scaledWidth / viewWidth, scaledHeight / viewHeight, viewWidth / 2f, viewHeight / 2f);
        setTransform(matrix);
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int width, int height) {
        surface = new Surface(surfaceTexture);
        preparePlayer();
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int width, int height) {
        updateTransform();
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
        releasePlayer();
        if (surface != null) {
            surface.release();
            surface = null;
        }
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {}
}
