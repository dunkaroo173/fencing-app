package io.github.dunkaroo173.uflfencing;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

class NativeMatchOverlayPainter {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private volatile JSONObject match;

    void setMatch(JSONObject match) {
        this.match = match;
    }

    void drawTransparent(Canvas canvas, int width, int height) {
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
        JSONObject snapshot = match;
        if (snapshot == null) return;
        drawHud(canvas, width, height, snapshot);
        drawLatestAction(canvas, width, height, snapshot);
    }

    private void drawHud(Canvas canvas, int width, int height, JSONObject match) {
        float margin = width * 0.035f;
        float top = Math.max(18f, height * 0.03f);
        float barY = top + 44f;
        float barH = Math.max(8f, height * 0.012f);
        float halfW = width * 0.36f;
        float center = width / 2f;
        int scoreL = match.optInt("scoreL", 0);
        int scoreR = match.optInt("scoreR", 0);
        int hpL = Math.max(0, match.optInt("hpL", 100));
        int hpR = Math.max(0, match.optInt("hpR", 100));
        String nameL = match.optString("nameL", "LEFT");
        String nameR = match.optString("nameR", "RIGHT");

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb(185, 6, 8, 12));
        canvas.drawRect(0, 0, width, Math.max(92f, height * 0.14f), paint);

        paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        paint.setTextSize(Math.max(22f, width * 0.022f));
        paint.setTextAlign(Paint.Align.LEFT);
        paint.setColor(Color.rgb(235, 240, 255));
        canvas.drawText(nameL, margin, top + 24f, paint);
        paint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(nameR, width - margin, top + 24f, paint);

        drawBar(canvas, margin, barY, halfW, barH, hpL / 100f, Color.rgb(0, 204, 255));
        drawBar(canvas, width - margin - halfW, barY, halfW, barH, hpR / 100f, Color.rgb(255, 52, 16));

        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        paint.setTextSize(Math.max(32f, width * 0.034f));
        paint.setColor(Color.rgb(0, 204, 255));
        canvas.drawText(String.valueOf(scoreL), center - width * 0.055f, top + 30f, paint);
        paint.setColor(Color.rgb(255, 52, 16));
        canvas.drawText(String.valueOf(scoreR), center + width * 0.055f, top + 30f, paint);

        paint.setTextSize(Math.max(24f, width * 0.026f));
        paint.setColor(Color.rgb(255, 210, 78));
        canvas.drawText(formatTimer(match.optInt("timerSec", 0)), center, top + 18f, paint);
        paint.setTextSize(Math.max(12f, width * 0.012f));
        paint.setColor(Color.rgb(150, 158, 180));
        canvas.drawText("P" + Math.max(1, match.optInt("period", 1)), center, top + 48f, paint);
    }

    private void drawBar(Canvas canvas, float x, float y, float w, float h, float pct, int color) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb(135, 15, 18, 26));
        canvas.drawRoundRect(new RectF(x, y, x + w, y + h), h / 2f, h / 2f, paint);
        paint.setColor(color);
        canvas.drawRoundRect(new RectF(x, y, x + Math.max(h, w * Math.max(0f, Math.min(1f, pct))), y + h), h / 2f, h / 2f, paint);
    }

    private void drawLatestAction(Canvas canvas, int width, int height, JSONObject match) {
        JSONArray events = match.optJSONArray("events");
        if (events == null || events.length() == 0) return;
        double bout = currentBoutElapsed(match);
        JSONObject latest = null;
        double latestTime = -1;
        for (int i = 0; i < events.length(); i++) {
            JSONObject ev = events.optJSONObject(i);
            if (ev == null) continue;
            double t = eventBoutTime(match, ev);
            if (t <= bout + 0.25 && t >= latestTime) {
                latest = ev;
                latestTime = t;
            }
        }
        if (latest == null || bout - latestTime > 3.0) return;

        boolean right = "R".equals(latest.optString("side"));
        int accent = right ? Color.rgb(255, 52, 16) : Color.rgb(0, 204, 255);
        String sideLabel = right ? "RIGHT ACTION" : "LEFT ACTION";
        String action = latest.optString("label", "ACTION").toUpperCase(Locale.US);
        String result = latest.optBoolean("isHit", false) ? "TOUCH" : "OFF TARGET";

        float boxW = Math.min(width * 0.68f, 760f);
        float boxH = Math.max(112f, height * 0.14f);
        float left = (width - boxW) / 2f;
        float top = height * 0.64f;
        RectF box = new RectF(left, top, left + boxW, top + boxH);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb(210, 8, 10, 16));
        canvas.drawRoundRect(box, 18f, 18f, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(4f, width * 0.004f));
        paint.setColor(accent);
        canvas.drawRoundRect(box, 18f, 18f, paint);

        paint.setStyle(Paint.Style.FILL);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        paint.setTextSize(Math.max(16f, width * 0.016f));
        paint.setColor(accent);
        canvas.drawText(sideLabel, width / 2f, top + boxH * 0.28f, paint);
        paint.setTextSize(Math.max(28f, width * 0.034f));
        paint.setColor(Color.WHITE);
        canvas.drawText(action, width / 2f, top + boxH * 0.58f, paint);
        paint.setTextSize(Math.max(18f, width * 0.02f));
        paint.setColor(Color.rgb(255, 218, 96));
        canvas.drawText(result, width / 2f, top + boxH * 0.82f, paint);
    }

    private static double currentBoutElapsed(JSONObject match) {
        double periodDuration = Math.max(0, match.optDouble("periodDuration", 0));
        int period = Math.max(1, match.optInt("period", 1));
        return (period - 1) * periodDuration + Math.max(0, periodDuration - match.optDouble("timerSec", 0));
    }

    private static double eventBoutTime(JSONObject match, JSONObject ev) {
        double periodDuration = Math.max(0, match.optDouble("periodDuration", 0));
        int period = Math.max(1, ev.optInt("period", 1));
        return (period - 1) * periodDuration + Math.max(0, ev.optDouble("ts", 0));
    }

    private static String formatTimer(int totalSeconds) {
        int s = Math.max(0, totalSeconds);
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }
}
