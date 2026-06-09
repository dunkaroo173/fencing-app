package io.github.dunkaroo173.uflfencing;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

class NativeVideoReviewView extends FrameLayout {
    interface Callback {
        void onKeep(int eventIndex, double chosenTimeSec, double clipStartSec, double clipEndSec, double playbackRate);
        void onEdit(int eventIndex, double chosenTimeSec, double clipStartSec, double clipEndSec, double playbackRate);
        void onNavigate(int eventIndex, int direction);
        void onClose();
        void onError(String message);
    }

    private static final long TICK_MS = 120L;
    private static final double DEFAULT_LEAD_SEC = 5.0;
    private static final double DEFAULT_TAIL_SEC = 2.0;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final PlayerView playerView;
    private final TextView titleView;
    private final TextView leftScoreView;
    private final TextView timerView;
    private final TextView rightScoreView;
    private final TextView actionSideView;
    private final TextView actionLabelView;
    private final TextView actionResultView;
    private final TextView timeView;
    private final TextView speedView;
    private final SeekBar scrubber;
    private final Button playButton;
    private final Button speed025Button;
    private final Button speed05Button;
    private final Button speed1Button;

    private ExoPlayer player;
    private Callback callback;
    private int eventIndex = -1;
    private double eventTimeSec;
    private double clipStartSec;
    private double clipEndSec;
    private double playbackRate = 0.5;
    private boolean userScrubbing;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            syncUi();
            if (player != null && player.isPlaying() && player.getCurrentPosition() >= secondsToMs(clipEndSec)) {
                player.pause();
                player.seekTo(secondsToMs(clipStartSec));
            }
            handler.postDelayed(this, TICK_MS);
        }
    };

    NativeVideoReviewView(Context context) {
        this(context, null);
    }

    NativeVideoReviewView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setBackgroundColor(Color.BLACK);
        setVisibility(GONE);

        playerView = new PlayerView(context);
        playerView.setUseController(false);
        addView(playerView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        LinearLayout scoreStrip = new LinearLayout(context);
        scoreStrip.setOrientation(LinearLayout.HORIZONTAL);
        scoreStrip.setGravity(Gravity.CENTER);
        scoreStrip.setPadding(dp(18), dp(10), dp(18), dp(10));
        scoreStrip.setBackgroundColor(Color.argb(220, 0, 0, 0));
        leftScoreView = label(context, 18, Color.rgb(0, 199, 255));
        leftScoreView.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
        leftScoreView.setTypeface(Typeface.DEFAULT_BOLD);
        timerView = label(context, 22, Color.rgb(255, 210, 80));
        timerView.setGravity(Gravity.CENTER);
        timerView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        rightScoreView = label(context, 18, Color.rgb(255, 58, 24));
        rightScoreView.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        rightScoreView.setTypeface(Typeface.DEFAULT_BOLD);
        scoreStrip.addView(leftScoreView, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        scoreStrip.addView(timerView, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        scoreStrip.addView(rightScoreView, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        addView(scoreStrip, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP));

        LinearLayout top = new LinearLayout(context);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setPadding(dp(14), dp(8), dp(14), dp(8));
        top.setBackgroundColor(Color.argb(210, 0, 0, 0));
        titleView = label(context, 18, Color.WHITE);
        timeView = label(context, 13, Color.rgb(210, 216, 232));
        top.addView(titleView);
        top.addView(timeView);
        LayoutParams topParams = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP);
        topParams.topMargin = dp(50);
        addView(top, topParams);

        LinearLayout actionCard = new LinearLayout(context);
        actionCard.setOrientation(LinearLayout.VERTICAL);
        actionCard.setGravity(Gravity.CENTER);
        actionCard.setPadding(dp(28), dp(18), dp(28), dp(18));
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(Color.argb(205, 0, 0, 0));
        cardBg.setCornerRadius(dp(14));
        cardBg.setStroke(dp(3), Color.rgb(255, 210, 80));
        actionCard.setBackground(cardBg);
        actionSideView = label(context, 13, Color.rgb(255, 210, 80));
        actionSideView.setGravity(Gravity.CENTER);
        actionSideView.setTypeface(Typeface.DEFAULT_BOLD);
        actionLabelView = label(context, 30, Color.WHITE);
        actionLabelView.setGravity(Gravity.CENTER);
        actionLabelView.setTypeface(Typeface.DEFAULT_BOLD);
        actionResultView = label(context, 17, Color.rgb(255, 210, 80));
        actionResultView.setGravity(Gravity.CENTER);
        actionResultView.setTypeface(Typeface.DEFAULT_BOLD);
        actionCard.addView(actionSideView, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        actionCard.addView(actionLabelView, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        actionCard.addView(actionResultView, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        LayoutParams actionParams = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        actionParams.leftMargin = dp(18);
        actionParams.rightMargin = dp(18);
        addView(actionCard, actionParams);

        LinearLayout bottom = new LinearLayout(context);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setPadding(dp(14), dp(8), dp(14), dp(10));
        bottom.setBackgroundColor(Color.argb(225, 0, 0, 0));

        scrubber = new SeekBar(context);
        scrubber.setMax(1000);
        bottom.addView(scrubber, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(34)));

        LinearLayout controls = new LinearLayout(context);
        controls.setGravity(Gravity.CENTER);
        controls.setOrientation(LinearLayout.HORIZONTAL);

        Button back2 = button(context, "-2s");
        Button back1 = button(context, "-1s");
        Button fwd1 = button(context, "+1s");
        Button fwd2 = button(context, "+2s");
        playButton = button(context, "PLAY");
        Button replay = button(context, "REPLAY");
        Button jumpAction = button(context, "ACTION");
        speed025Button = button(context, "0.25x");
        speed05Button = button(context, "0.5x");
        speed1Button = button(context, "1x");
        speedView = label(context, 12, Color.rgb(245, 200, 66));

        controls.addView(back2);
        controls.addView(back1);
        controls.addView(playButton);
        controls.addView(replay);
        controls.addView(jumpAction);
        controls.addView(fwd1);
        controls.addView(fwd2);
        controls.addView(speed025Button);
        controls.addView(speed05Button);
        controls.addView(speed1Button);
        controls.addView(speedView);
        bottom.addView(controls);

        LinearLayout decisions = new LinearLayout(context);
        decisions.setGravity(Gravity.CENTER);
        decisions.setOrientation(LinearLayout.HORIZONTAL);
        Button previous = button(context, "PREV");
        Button keep = button(context, "KEEP");
        Button edit = button(context, "EDIT");
        Button next = button(context, "NEXT");
        Button close = button(context, "CLOSE");
        decisions.addView(previous);
        decisions.addView(keep);
        decisions.addView(edit);
        decisions.addView(next);
        decisions.addView(close);
        bottom.addView(decisions);

        LayoutParams bottomParams = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        addView(bottom, bottomParams);

        scrubber.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser || player == null) return;
                double target = clipStartSec + ((clipEndSec - clipStartSec) * (progress / 1000.0));
                player.seekTo(secondsToMs(target));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                userScrubbing = true;
                if (player != null) player.pause();
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                userScrubbing = false;
                syncUi();
            }
        });

        playButton.setOnClickListener(v -> togglePlay());
        replay.setOnClickListener(v -> replay());
        jumpAction.setOnClickListener(v -> jumpToAction());
        back2.setOnClickListener(v -> shift(-2));
        back1.setOnClickListener(v -> shift(-1));
        fwd1.setOnClickListener(v -> shift(1));
        fwd2.setOnClickListener(v -> shift(2));
        speed025Button.setOnClickListener(v -> setPlaybackRate(0.25));
        speed05Button.setOnClickListener(v -> setPlaybackRate(0.5));
        speed1Button.setOnClickListener(v -> setPlaybackRate(1.0));
        previous.setOnClickListener(v -> {
            if (callback != null) callback.onNavigate(eventIndex, -1);
        });
        next.setOnClickListener(v -> {
            if (callback != null) callback.onNavigate(eventIndex, 1);
        });
        keep.setOnClickListener(v -> {
            if (callback != null) callback.onKeep(eventIndex, currentSec(), clipStartSec, clipEndSec, playbackRate);
        });
        edit.setOnClickListener(v -> {
            if (callback != null) callback.onEdit(eventIndex, currentSec(), clipStartSec, clipEndSec, playbackRate);
        });
        close.setOnClickListener(v -> {
            hide();
            if (callback != null) callback.onClose();
        });
    }

    void setCallback(Callback callback) {
        this.callback = callback;
    }

    void show(JSONObject payload) {
        try {
            String uri = payload.getString("sourceUri");
            eventIndex = payload.optInt("eventIndex", -1);
            eventTimeSec = Math.max(0.0, payload.optDouble("eventVideoTime", 0.0));
            double durationSec = payload.optDouble("durationSec", 0.0);
            clipStartSec = Math.max(0.0, payload.optDouble("clipStart", eventTimeSec - DEFAULT_LEAD_SEC));
            clipEndSec = payload.optDouble("clipEnd", eventTimeSec + DEFAULT_TAIL_SEC);
            if (durationSec > 0) clipEndSec = Math.min(durationSec, clipEndSec);
            clipEndSec = Math.max(clipStartSec + 0.5, clipEndSec);
            playbackRate = payload.optDouble("playbackRate", 0.5);

            applyReviewContext(payload);

            if (player == null) {
                player = new ExoPlayer.Builder(getContext()).build();
                playerView.setPlayer(player);
                player.addListener(new Player.Listener() {
                    @Override
                    public void onPlayerError(androidx.media3.common.PlaybackException error) {
                        if (callback != null) callback.onError(error.getMessage() == null ? error.toString() : error.getMessage());
                    }
                });
            }
            player.setMediaItem(MediaItem.fromUri(Uri.parse(uri)));
            player.prepare();
            player.setPlaybackParameters(new PlaybackParameters((float) playbackRate));
            player.seekTo(secondsToMs(clipStartSec));
            setVisibility(VISIBLE);
            updateSpeedButtons();
            handler.removeCallbacks(ticker);
            handler.post(ticker);
        } catch (Exception e) {
            if (callback != null) callback.onError(e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    void hide() {
        handler.removeCallbacks(ticker);
        if (player != null) player.pause();
        setVisibility(GONE);
    }

    void release() {
        handler.removeCallbacks(ticker);
        if (player != null) {
            player.release();
            player = null;
        }
    }

    private void togglePlay() {
        if (player == null) return;
        if (player.isPlaying()) {
            player.pause();
        } else {
            long pos = player.getCurrentPosition();
            if (pos < secondsToMs(clipStartSec) || pos >= secondsToMs(clipEndSec)) {
                player.seekTo(secondsToMs(clipStartSec));
            }
            player.play();
        }
        syncUi();
    }

    private void replay() {
        if (player == null) return;
        player.seekTo(secondsToMs(clipStartSec));
        player.play();
    }

    private void jumpToAction() {
        if (player == null) return;
        player.seekTo(secondsToMs(clamp(eventTimeSec, clipStartSec, clipEndSec)));
        syncUi();
    }

    private void shift(double deltaSec) {
        if (player == null) return;
        double target = clamp(currentSec() + deltaSec, clipStartSec, clipEndSec);
        player.seekTo(secondsToMs(target));
        syncUi();
    }

    private void setPlaybackRate(double rate) {
        playbackRate = rate;
        if (player != null) {
            player.setPlaybackParameters(new PlaybackParameters((float) rate));
        }
        updateSpeedButtons();
    }

    private void syncUi() {
        if (player == null) return;
        double current = currentSec();
        if (!userScrubbing) {
            double span = Math.max(0.001, clipEndSec - clipStartSec);
            int progress = (int) Math.round(clamp((current - clipStartSec) / span, 0, 1) * 1000);
            scrubber.setProgress(progress);
        }
        playButton.setText(player.isPlaying() ? "PAUSE" : "PLAY");
        timeView.setText(String.format(
                java.util.Locale.US,
                "Event %.2fs | Review %.2fs - %.2fs | Current %.2fs",
                eventTimeSec,
                clipStartSec,
                clipEndSec,
                current));
    }

    private void updateSpeedButtons() {
        speedView.setText(String.format(Locale.US, " %.2fx", playbackRate));
        speed025Button.setTextColor(playbackRate == 0.25 ? Color.rgb(245, 200, 66) : Color.WHITE);
        speed05Button.setTextColor(playbackRate == 0.5 ? Color.rgb(245, 200, 66) : Color.WHITE);
        speed1Button.setTextColor(playbackRate == 1.0 ? Color.rgb(245, 200, 66) : Color.WHITE);
    }

    private void applyReviewContext(JSONObject payload) {
        JSONObject match = payload.optJSONObject("match");
        JSONObject event = eventAt(match, eventIndex);
        if (match != null && event != null) {
            ReviewState state = reviewStateAt(match, event);
            String nameL = match.optString("nameL", "LEFT");
            String nameR = match.optString("nameR", "RIGHT");
            String side = event.optString("side", payload.optString("side", ""));
            String sideName = "R".equals(side) ? nameR : "L".equals(side) ? nameL : "REF";
            String result = event.optBoolean("isHit", false) ? "HIT" : "C".equals(side) ? "NO TOUCH" : "OFF TARGET";
            String action = event.optString("label", "Review").toUpperCase(Locale.US);
            String status = event.optString("reviewStatus", event.optBoolean("reviewed", false) ? "confirmed" : "pending").toUpperCase(Locale.US);
            titleView.setText(String.format(Locale.US, "#%d %s - %s", eventIndex + 1, action, status));
            leftScoreView.setText(String.format(Locale.US, "%s %d", nameL, state.scoreL));
            timerView.setText(String.format(Locale.US, "%s  P%d", formatTimer(state.timerSec), state.period));
            rightScoreView.setText(String.format(Locale.US, "%d %s", state.scoreR, nameR));
            actionSideView.setText(String.format(Locale.US, "%s ACTION", sideName).toUpperCase(Locale.US));
            actionLabelView.setText(action);
            actionResultView.setText(result);
            actionSideView.setTextColor("R".equals(side) ? Color.rgb(255, 58, 24) : Color.rgb(0, 199, 255));
            return;
        }

        titleView.setText(payload.optString("title", "Video Review"));
        leftScoreView.setText(payload.optString("leftSummary", "LEFT 0"));
        timerView.setText(payload.optString("timerText", "--:--"));
        rightScoreView.setText(payload.optString("rightSummary", "0 RIGHT"));
        actionSideView.setText(payload.optString("actionSideLabel", "ACTION"));
        actionLabelView.setText(payload.optString("actionLabel", "REVIEW"));
        actionResultView.setText(payload.optString("resultLabel", ""));
        int accent = "R".equals(payload.optString("side", "")) ? Color.rgb(255, 58, 24) : Color.rgb(0, 199, 255);
        actionSideView.setTextColor(accent);
    }

    private JSONObject eventAt(JSONObject match, int index) {
        if (match == null || index < 0) return null;
        JSONArray events = match.optJSONArray("events");
        if (events == null || index >= events.length()) return null;
        return events.optJSONObject(index);
    }

    private ReviewState reviewStateAt(JSONObject match, JSONObject targetEvent) {
        ReviewState state = new ReviewState();
        String nameL = match.optString("nameL", "LEFT");
        String nameR = match.optString("nameR", "RIGHT");
        double periodDuration = Math.max(1.0, match.optDouble("periodDuration", 180.0));
        double targetTime = eventBoutTime(match, targetEvent);
        state.period = Math.max(1, (int) Math.floor(targetTime / periodDuration) + 1);
        double periodElapsed = Math.max(0.0, targetTime - (state.period - 1) * periodDuration);
        state.timerSec = (int) Math.max(0, periodDuration - Math.floor(periodElapsed));

        JSONArray events = match.optJSONArray("events");
        if (events != null) {
            for (int i = 0; i < events.length(); i++) {
                JSONObject ev = events.optJSONObject(i);
                if (ev == null || eventBoutTime(match, ev) > targetTime + 0.001) continue;
                applyScore(state, ev);
            }
        }
        if (nameL.length() == 0 || nameR.length() == 0) return state;
        return state;
    }

    private void applyScore(ReviewState state, JSONObject ev) {
        String side = ev.optString("side", "");
        int actionId = ev.optInt("actionId", 0);
        if (ev.optBoolean("isHit", false)) {
            if ("L".equals(side)) state.scoreL++;
            if ("R".equals(side)) state.scoreR++;
        }
        if (actionId == 191) state.scoreR++;
        if (actionId == 291) state.scoreL++;
    }

    private double eventBoutTime(JSONObject match, JSONObject ev) {
        double periodDuration = Math.max(0.0, match.optDouble("periodDuration", 0.0));
        int period = Math.max(1, ev.optInt("period", 1));
        return (period - 1) * periodDuration + Math.max(0.0, ev.optDouble("ts", 0.0));
    }

    private static String formatTimer(int totalSeconds) {
        int seconds = Math.max(0, totalSeconds);
        return String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60);
    }

    private static class ReviewState {
        int scoreL = 0;
        int scoreR = 0;
        int timerSec = 0;
        int period = 1;
    }

    private double currentSec() {
        return player == null ? clipStartSec : player.getCurrentPosition() / 1000.0;
    }

    private static long secondsToMs(double seconds) {
        return Math.max(0L, Math.round(seconds * 1000.0));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private TextView label(Context context, int sp, int color) {
        TextView view = new TextView(context);
        view.setTextColor(color);
        view.setTextSize(sp);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setPadding(dp(4), dp(2), dp(4), dp(2));
        return view;
    }

    private Button button(Context context, String text) {
        Button button = new Button(context);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(12);
        button.setAllCaps(false);
        button.setPadding(dp(8), dp(4), dp(8), dp(4));
        button.setMinWidth(dp(64));
        return button;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
