package io.github.dunkaroo173.uflfencing;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.net.Uri;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import java.util.Locale;

class NativeImportPreview extends TextureView implements TextureView.SurfaceTextureListener {
    interface Callback {
        void onEnded();
        void onError(String message);
    }

    private static final String TAG = "UFLImportPreview";

    private MediaPlayer player;
    private Surface surface;
    private Uri pendingUri;
    private boolean playWhenReady;
    private int videoWidth;
    private int videoHeight;
    private Callback callback;

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
        if (player != null) {
            player.seekTo(Math.max(0, millis));
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
                if (playWhenReady) mp.start();
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
