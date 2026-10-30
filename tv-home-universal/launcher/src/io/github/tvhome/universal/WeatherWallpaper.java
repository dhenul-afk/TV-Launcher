package io.github.tvhome.universal;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.view.View;


import java.util.Calendar;
import java.util.Random;

/**
 * Weather-driven live wallpaper: a gradient sky, a slow sun or moon, drifting clouds, rain and an
 * optional lightning flash. Designed for a TV: one view, no allocations while drawing, a fixed
 * 20 fps budget, and a static frame whenever animations are switched off.
 * Colours come from {@link WeatherModel}; the underlying photo wallpaper stays visible when this
 * view is hidden or the wallpaper is disabled.
 */
final class WeatherWallpaper extends View {

    private static final long FRAME_MS = 50;   // 20 fps
    private static final int STAR_COUNT = 90;
    private static final int RAIN_COUNT = 70;
    private static final int SNOW_COUNT = 55;
    private static final int CLOUD_COUNT = 5;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable frame = new Runnable() {
        @Override public void run() {
            if (!animating) return;
            elapsed = System.currentTimeMillis();
            invalidate();
            handler.postDelayed(this, FRAME_MS);
        }
    };

    private final Paint skyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bodyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cloudPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dropPaint = new Paint();
    private final Paint flashPaint = new Paint();
    private final Path cloudPath = new Path();
    private final Random random = new Random();

    private float[] starX, starY, starPhase;
    private float[] dropX, dropY, dropSpeed, dropLength;
    private float[] snowX, snowY, snowSpeed, snowDrift;
    private float[] cloudOffset, cloudScale, cloudAlpha;
    private boolean built;

    private String category = WeatherModel.CLEAR;
    private boolean thunder;
    private boolean animating;
    private boolean visible = true;
    private long elapsed;
    private long nextFlashAt;
    private long flashUntil;
    private int lastTop = Color.TRANSPARENT, lastBottom = Color.TRANSPARENT;
    private int widthPx = 1, heightPx = 1;

    WeatherWallpaper(Context context) {
        super(context);
        skyPaint.setStyle(Paint.Style.FILL);
        bodyPaint.setStyle(Paint.Style.FILL);
        glowPaint.setStyle(Paint.Style.FILL);
        cloudPaint.setStyle(Paint.Style.FILL);
        dropPaint.setStyle(Paint.Style.STROKE);
        dropPaint.setStrokeCap(Paint.Cap.ROUND);
        flashPaint.setColor(Color.WHITE);
        setClickable(false);
        setFocusable(false);
    }

    void setCondition(String condition, boolean thunderCondition) {
        boolean changed = !category.equals(condition) || thunder != thunderCondition;
        category = condition == null ? WeatherModel.CLEAR : condition;
        thunder = thunderCondition;
        if (changed) {
            lastTop = Color.TRANSPARENT;
            invalidate();
        }
    }

    void setAnimationsEnabled(boolean enabled) {
        animating = enabled && visible;
        if (animating) {
            elapsed = System.currentTimeMillis();
            handler.removeCallbacks(frame);
            handler.postDelayed(frame, FRAME_MS);
        } else {
            handler.removeCallbacks(frame);
            invalidate();
        }
    }

    /** Hides the view (photo wallpaper stays) while remembering the animation preference. */
    void setShow(boolean show) {
        visible = show;
        setVisibility(show ? VISIBLE : GONE);
        setAnimationsEnabled(show && WeatherStore.animations(getContext()));
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (getVisibility() == VISIBLE && WeatherStore.animations(getContext())) setAnimationsEnabled(true);
    }

    @Override protected void onDetachedFromWindow() {
        handler.removeCallbacks(frame);
        super.onDetachedFromWindow();
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        widthPx = Math.max(1, w);
        heightPx = Math.max(1, h);
        build();
    }

    private void build() {
        starX = new float[STAR_COUNT];
        starY = new float[STAR_COUNT];
        starPhase = new float[STAR_COUNT];
        for (int i = 0; i < STAR_COUNT; i++) {
            starX[i] = random.nextFloat() * widthPx;
            starY[i] = random.nextFloat() * heightPx * 0.62f;
            starPhase[i] = random.nextFloat() * 1000f;
        }
        dropX = new float[RAIN_COUNT];
        dropY = new float[RAIN_COUNT];
        dropSpeed = new float[RAIN_COUNT];
        dropLength = new float[RAIN_COUNT];
        for (int i = 0; i < RAIN_COUNT; i++) resetDrop(i, true);
        snowX = new float[SNOW_COUNT];
        snowY = new float[SNOW_COUNT];
        snowSpeed = new float[SNOW_COUNT];
        snowDrift = new float[SNOW_COUNT];
        for (int i = 0; i < SNOW_COUNT; i++) {
            snowX[i] = random.nextFloat() * widthPx;
            snowY[i] = random.nextFloat() * heightPx;
            snowSpeed[i] = 0.6f + random.nextFloat() * 1.1f;
            snowDrift[i] = (random.nextFloat() - 0.5f) * 0.8f;
        }
        cloudOffset = new float[CLOUD_COUNT];
        cloudScale = new float[CLOUD_COUNT];
        cloudAlpha = new float[CLOUD_COUNT];
        for (int i = 0; i < CLOUD_COUNT; i++) {
            cloudOffset[i] = random.nextFloat() * 1.4f - 0.2f;
            cloudScale[i] = 0.6f + random.nextFloat() * 0.9f;
            cloudAlpha[i] = 0.10f + random.nextFloat() * 0.16f;
        }
        built = true;
    }

    private void resetDrop(int index, boolean anywhere) {
        dropX[index] = random.nextFloat() * (widthPx + 120f) - 60f;
        dropY[index] = anywhere ? random.nextFloat() * heightPx : -20f - random.nextFloat() * 80f;
        dropSpeed[index] = 9f + random.nextFloat() * 13f;
        dropLength[index] = 14f + random.nextFloat() * 26f;
    }

    private int localHour() {
        return Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
    }

    private float localHourFloat() {
        Calendar now = Calendar.getInstance();
        return now.get(Calendar.HOUR_OF_DAY) + now.get(Calendar.MINUTE) / 60f;
    }

    @Override protected void onDraw(Canvas canvas) {
        if (!built || widthPx <= 1 || heightPx <= 1) return;
        if (!animating) elapsed = System.currentTimeMillis();
        float seconds = (elapsed % 600_000L) / 1000f; // slow loops, no drift
        int hour = localHour();

        int top = WeatherModel.skyTop(category, hour);
        int bottom = WeatherModel.skyBottom(category, hour);
        if (top != lastTop || bottom != lastBottom) {
            lastTop = top;
            lastBottom = bottom;
            skyPaint.setShader(new LinearGradient(0, 0, 0, heightPx, top, bottom, Shader.TileMode.CLAMP));
        }
        canvas.drawRect(0, 0, widthPx, heightPx, skyPaint);

        if (WeatherModel.isNight(hour) && (WeatherModel.CLEAR.equals(category)
                || WeatherModel.PARTLY.equals(category))) drawStars(canvas, seconds);

        drawOrb(canvas, hour);

        if (!WeatherModel.FOG.equals(category)) drawClouds(canvas, seconds, hour);
        else drawFog(canvas, hour);

        if (WeatherModel.RAIN.equals(category)) drawRain(canvas, seconds, false);
        else if (WeatherModel.SNOW.equals(category)) drawRain(canvas, seconds, true);

        if (thunder) drawLightning(canvas, seconds);
    }

    private void drawStars(Canvas canvas, float seconds) {
        bodyPaint.setColor(Color.WHITE);
        for (int i = 0; i < STAR_COUNT; i++) {
            float twinkle = 0.35f + 0.65f * (0.5f + 0.5f * (float) Math.sin(seconds * 1.4f + starPhase[i]));
            bodyPaint.setAlpha(Math.round(190 * twinkle));
            float radius = 1.1f + (i % 3) * 0.5f;
            canvas.drawCircle(starX[i], starY[i], radius, bodyPaint);
        }
        bodyPaint.setAlpha(255);
    }

    private void drawOrb(Canvas canvas, int hour) {
        float progress = WeatherModel.sunProgress(localHourFloat());
        float height = WeatherModel.sunHeight(progress);
        float x = widthPx * (0.08f + 0.84f * progress);
        float y = heightPx * (0.72f - 0.52f * height);
        float radius = Math.max(26f, Math.min(widthPx, heightPx) * 0.045f);
        boolean night = WeatherModel.isNight(hour);
        int core = night ? 0xFFF3F4F8 : 0xFFFFF3C4;
        int glow = night ? 0x33FFFFFF : 0x55FFD98A;

        glowPaint.setShader(new RadialGradient(x, y, radius * 4.5f,
                new int[]{glow, Color.TRANSPARENT}, new float[]{0f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawCircle(x, y, radius * 4.5f, glowPaint);
        bodyPaint.setColor(core);
        canvas.drawCircle(x, y, radius, bodyPaint);
        if (night) {                       // simple moon shading
            bodyPaint.setColor(0x00000000);
            bodyPaint.setAlpha(70);
            canvas.drawCircle(x + radius * 0.45f, y - radius * 0.30f, radius * 0.85f, bodyPaint);
            bodyPaint.setAlpha(255);
        }
    }

    private void drawClouds(Canvas canvas, float seconds, int hour) {
        boolean rain = WeatherModel.RAIN.equals(category) || WeatherModel.THUNDER.equals(category);
        float speed = rain ? 9f : (WeatherModel.CLOUDY.equals(category) ? 5f : 3f);
        int baseColor = rain ? 0xFF4A5464 : (WeatherModel.CLOUDY.equals(category) ? 0xFFB9C2CE : 0xFFE4EAF2);
        int tint = WeatherModel.isNight(hour) ? 0xFF5A6272 : baseColor;
        cloudPaint.setColor(tint);
        for (int i = 0; i < CLOUD_COUNT; i++) {
            if (animating) {
                cloudOffset[i] += (speed * cloudScale[i]) / (widthPx * 12f);
                if (cloudOffset[i] > 1.25f) cloudOffset[i] = -0.35f;
            }
            float cx = cloudOffset[i] * widthPx;
            float cy = heightPx * (0.10f + (i % 3) * 0.10f);
            float unit = widthPx * 0.075f * cloudScale[i];
            cloudPaint.setAlpha(Math.round(255 * Math.min(0.55f, cloudAlpha[i]) * (rain ? 1.4f : 1f)));
            cloudPath.reset();
            cloudPath.addCircle(cx, cy, unit, Path.Direction.CW);
            cloudPath.addCircle(cx + unit * 0.9f, cy + unit * 0.18f, unit * 0.78f, Path.Direction.CW);
            cloudPath.addCircle(cx - unit * 0.9f, cy + unit * 0.22f, unit * 0.70f, Path.Direction.CW);
            cloudPath.addCircle(cx + unit * 0.35f, cy - unit * 0.45f, unit * 0.66f, Path.Direction.CW);
            canvas.drawPath(cloudPath, cloudPaint);
        }
        cloudPaint.setAlpha(255);
    }

    private void drawFog(Canvas canvas, int hour) {
        int color = WeatherModel.isNight(hour) ? 0x555A6070 : 0x66C3C8D2;
        for (int i = 0; i < 6; i++) {
            float y = heightPx * (0.25f + i * 0.12f);
            cloudPaint.setColor(color);
            cloudPaint.setAlpha(Math.round(70 - i * 6));
            canvas.drawRect(0, y, widthPx, y + heightPx * 0.16f, cloudPaint);
        }
        cloudPaint.setAlpha(255);
    }

    private void drawRain(Canvas canvas, float seconds, boolean snow) {
        if (snow) {
            bodyPaint.setColor(Color.WHITE);
            for (int i = 0; i < SNOW_COUNT; i++) {
                if (animating) {
                    snowY[i] += snowSpeed[i];
                    snowX[i] += snowDrift[i] + (float) Math.sin(seconds + i) * 0.25f;
                    if (snowY[i] > heightPx) { snowY[i] = -6f; snowX[i] = random.nextFloat() * widthPx; }
                }
                bodyPaint.setAlpha(160 + (i % 5) * 15);
                canvas.drawCircle(snowX[i], snowY[i], 1.6f + (i % 3) * 0.7f, bodyPaint);
            }
            bodyPaint.setAlpha(255);
            return;
        }
        dropPaint.setColor(0xB3C9DDF0);
        dropPaint.setStrokeWidth(1.6f);
        for (int i = 0; i < RAIN_COUNT; i++) {
            if (animating) {
                dropY[i] += dropSpeed[i];
                dropX[i] += dropSpeed[i] * 0.14f;
                if (dropY[i] > heightPx + 30f) resetDrop(i, false);
            }
            canvas.drawLine(dropX[i], dropY[i], dropX[i] - dropLength[i] * 0.14f,
                    dropY[i] - dropLength[i], dropPaint);
        }
    }

    private void drawLightning(Canvas canvas, float seconds) {
        if (!animating) return;
        long now = System.currentTimeMillis();
        if (nextFlashAt == 0) nextFlashAt = now + 4000 + random.nextInt(6000);
        if (now >= nextFlashAt) {
            flashUntil = now + 260;
            nextFlashAt = now + 6000 + random.nextInt(9000);
        }
        if (now < flashUntil) {
            float remaining = (flashUntil - now) / 260f;
            double pulse = Math.abs(Math.sin(seconds * 40f));
            flashPaint.setAlpha((int) Math.round(150 * remaining * pulse));
            canvas.drawRect(0, 0, widthPx, heightPx, flashPaint);
        }
    }
}
