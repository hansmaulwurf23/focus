package de.noisebleed.focus.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;

/**
 * Kreisring zum Einstellen und Anzeigen der Konzentrationsdauer.
 *
 * <p>Ein voller Ring entspricht einer Stunde, ein Minutenschritt also 6°. Der Ring wird bei 12 Uhr
 * begonnen und im Uhrzeigersinn aufgezogen. Beim Ziehen wird der Winkel aufsummiert (statt absolut
 * übernommen), damit ein Überfahren von 12 Uhr bei 0 bzw. 60 Minuten klemmt und nicht umschlägt.
 */
public class RingTimerView extends View {

    public interface OnMinutesChangeListener {
        void onMinutesChanged(int minutes);
    }

    public enum Mode { SET, RUN, DONE }

    private static final int MAX_MINUTES = 60;
    private static final float DEG_PER_MINUTE = 360f / MAX_MINUTES;
    private static final float START_ANGLE = -90f;

    private static final int COLOR_TRACK = 0xFF1C222D;
    private static final int COLOR_GHOST = 0xFF34302B;
    private static final int COLOR_TICK = 0xFF2C3441;
    private static final int COLOR_TICK_MAJOR = 0xFF4A566B;
    private static final int COLOR_ARC_START = 0xFFF9711F;
    private static final int COLOR_ARC_END = 0xFFFFE3A8;

    /**
     * Unter dieser Segmentlänge wird der Verlauf nicht voll ausgereizt — auf einem Stummel von
     * wenigen Grad würde die ganze Spanne sonst wie zwei zusammengeklebte Farben wirken.
     */
    private static final float MIN_GRADIENT_SPAN_DEG = 30f;
    private static final int COLOR_TEXT = 0xFFF2F4F8;
    private static final int COLOR_LABEL = 0xFF8A93A3;

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ghostPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tickMajorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knobPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knobCorePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint timePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF arcRect = new RectF();
    private final Paint.FontMetrics fontMetrics = new Paint.FontMetrics();

    private Mode mode = Mode.SET;
    private int minutes = 25;
    private long remainingMs;
    private long totalMs;

    private float ringWidth;
    private float cx, cy, radius;
    private int gradientKey = Integer.MIN_VALUE;

    private boolean dragging;
    private float dragSweep;
    private float lastTouchAngle;

    @Nullable
    private OnMinutesChangeListener listener;

    public RingTimerView(Context context) {
        this(context, null);
    }

    public RingTimerView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        ringWidth = dp(26);

        for (Paint p : new Paint[]{trackPaint, ghostPaint, glowPaint, arcPaint}) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(ringWidth);
        }
        trackPaint.setColor(COLOR_TRACK);
        ghostPaint.setColor(COLOR_GHOST);
        arcPaint.setStrokeCap(Paint.Cap.ROUND);
        ghostPaint.setStrokeCap(Paint.Cap.ROUND);
        glowPaint.setStrokeCap(Paint.Cap.ROUND);
        glowPaint.setColor(COLOR_ARC_START);
        glowPaint.setAlpha(38);
        glowPaint.setStrokeWidth(ringWidth * 1.9f);

        tickPaint.setColor(COLOR_TICK);
        tickPaint.setStrokeWidth(dp(1.5f));
        tickPaint.setStrokeCap(Paint.Cap.ROUND);
        tickMajorPaint.setColor(COLOR_TICK_MAJOR);
        tickMajorPaint.setStrokeWidth(dp(2f));
        tickMajorPaint.setStrokeCap(Paint.Cap.ROUND);

        knobPaint.setColor(Color.WHITE);
        knobCorePaint.setColor(COLOR_ARC_END);

        timePaint.setColor(COLOR_TEXT);
        timePaint.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        timePaint.setTextAlign(Paint.Align.CENTER);

        labelPaint.setColor(COLOR_LABEL);
        labelPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        labelPaint.setTextAlign(Paint.Align.CENTER);
    }

    public void setOnMinutesChangeListener(@Nullable OnMinutesChangeListener l) {
        this.listener = l;
    }

    /** Einstellmodus: der Ring ist bedienbar und zeigt die gewählten Minuten. */
    public void setMinutes(int value) {
        int clamped = Math.max(0, Math.min(MAX_MINUTES, value));
        boolean changed = mode != Mode.SET || clamped != minutes;
        mode = Mode.SET;
        minutes = clamped;
        dragSweep = clamped * DEG_PER_MINUTE;
        if (changed) {
            updateContentDescription();
            invalidate();
        }
    }

    public int getMinutes() {
        return minutes;
    }

    /** Laufmodus: der Ring schrumpft mit der Restzeit, Bedienung ist gesperrt. */
    public void setRunning(long remaining, long total) {
        mode = Mode.RUN;
        remainingMs = Math.max(0, remaining);
        totalMs = Math.max(0, total);
        dragging = false;
        updateContentDescription();
        invalidate();
    }

    /** Der Timer ist abgelaufen und der Alarm klingelt. */
    public void setFinished(long total) {
        mode = Mode.DONE;
        remainingMs = 0;
        totalMs = Math.max(0, total);
        dragging = false;
        updateContentDescription();
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        cx = w / 2f;
        cy = h / 2f;
        float available = Math.min(w, h) / 2f;
        ringWidth = Math.max(dp(16), Math.min(dp(30), available * 0.13f));
        for (Paint p : new Paint[]{trackPaint, ghostPaint, arcPaint}) {
            p.setStrokeWidth(ringWidth);
        }
        glowPaint.setStrokeWidth(ringWidth * 1.9f);
        radius = available - ringWidth * 1.1f;
        arcRect.set(cx - radius, cy - radius, cx + radius, cy + radius);
        gradientKey = Integer.MIN_VALUE;
    }

    /**
     * Legt den Farbverlauf über das tatsächliche Segment statt über den ganzen Kreis, damit auch
     * ein kurzer Bogen die volle Spanne zeigt. Wird nur bei geänderter Gradzahl neu gebaut.
     *
     * <p>Vor dem Segmentanfang liegt ein Puffer in der Startfarbe: die runde Kappe ragt gegen den
     * Uhrzeigersinn über 12 Uhr hinaus, und dort läge sonst schon das Ende des Verlaufs — der helle
     * Fleck links der 12.
     */
    private void ensureGradient(float sweep) {
        int key = Math.round(sweep);
        if (key == gradientKey) {
            return;
        }
        gradientKey = key;

        float padDeg = (float) Math.toDegrees(Math.atan2(ringWidth / 2f, radius)) + 1.5f;
        float span = Math.max(MIN_GRADIENT_SPAN_DEG, sweep);
        float padStop = padDeg / 360f;
        float endStop = Math.min(1f, (padDeg + span) / 360f);

        int[] colors;
        float[] stops;
        if (endStop >= 1f) {
            colors = new int[]{COLOR_ARC_START, COLOR_ARC_START, COLOR_ARC_END};
            stops = new float[]{0f, padStop, 1f};
        } else {
            colors = new int[]{COLOR_ARC_START, COLOR_ARC_START, COLOR_ARC_END, COLOR_ARC_END};
            stops = new float[]{0f, padStop, endStop, 1f};
        }

        Matrix rotate = new Matrix();
        rotate.setRotate(START_ANGLE - padDeg, cx, cy);
        SweepGradient gradient = new SweepGradient(cx, cy, colors, stops);
        gradient.setLocalMatrix(rotate);
        arcPaint.setShader(gradient);
        // Der Schein darunter folgt derselben Farbe; setAlpha moduliert den Shader weiterhin.
        glowPaint.setShader(gradient);
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        if (radius <= 0) {
            return;
        }
        canvas.drawCircle(cx, cy, radius, trackPaint);
        drawTicks(canvas);

        float sweep = currentSweep();
        if (mode == Mode.RUN && totalMs > 0) {
            // Die ursprünglich eingestellte Dauer bleibt als gedämpfter Ring stehen.
            canvas.drawArc(arcRect, START_ANGLE, sweepForMs(totalMs), false, ghostPaint);
        }
        if (sweep > 0.01f) {
            ensureGradient(sweep);
            canvas.drawArc(arcRect, START_ANGLE, sweep, false, glowPaint);
            canvas.drawArc(arcRect, START_ANGLE, sweep, false, arcPaint);
        }
        if (mode == Mode.SET && minutes > 0) {
            drawKnob(canvas, sweep);
        }
        drawCenter(canvas);
    }

    private void drawTicks(Canvas canvas) {
        float outer = radius - ringWidth / 2f - dp(7);
        for (int i = 0; i < MAX_MINUTES; i++) {
            boolean major = i % 5 == 0;
            float len = major ? dp(11) : dp(5);
            double rad = Math.toRadians(START_ANGLE + i * DEG_PER_MINUTE);
            float sin = (float) Math.sin(rad);
            float cos = (float) Math.cos(rad);
            canvas.drawLine(cx + cos * outer, cy + sin * outer,
                    cx + cos * (outer - len), cy + sin * (outer - len),
                    major ? tickMajorPaint : tickPaint);
        }
    }

    private void drawKnob(Canvas canvas, float sweep) {
        double rad = Math.toRadians(START_ANGLE + sweep);
        float kx = cx + (float) Math.cos(rad) * radius;
        float ky = cy + (float) Math.sin(rad) * radius;
        float r = ringWidth * (dragging ? 0.56f : 0.44f);
        canvas.drawCircle(kx, ky, r, knobPaint);
        canvas.drawCircle(kx, ky, r * 0.45f, knobCorePaint);
    }

    private void drawCenter(Canvas canvas) {
        String time;
        String label;
        switch (mode) {
            case RUN:
                time = formatClock(remainingMs);
                label = (totalMs / 60000L) + " Min";
                break;
            case DONE:
                time = "0:00";
                label = "fertig";
                break;
            case SET:
            default:
                time = String.valueOf(minutes);
                label = minutes == 1 ? "Minute" : "Minuten";
                break;
        }

        float maxWidth = radius * 1.35f;
        timePaint.setTextSize(mode == Mode.SET ? radius * 0.85f : radius * 0.62f);
        float width = timePaint.measureText(time);
        if (width > maxWidth) {
            timePaint.setTextSize(timePaint.getTextSize() * maxWidth / width);
        }
        labelPaint.setTextSize(Math.max(dp(11), radius * 0.11f));
        labelPaint.setLetterSpacing(0.14f);

        timePaint.getFontMetrics(fontMetrics);
        float digitsHeight = -fontMetrics.ascent + fontMetrics.descent;
        float labelGap = dp(6);
        float labelHeight = labelPaint.getTextSize();
        float blockTop = cy - (digitsHeight + labelGap + labelHeight) / 2f;

        canvas.drawText(time, cx, blockTop - fontMetrics.ascent, timePaint);
        canvas.drawText(label.toUpperCase(Locale.GERMAN), cx,
                blockTop + digitsHeight + labelGap + labelHeight * 0.8f, labelPaint);
    }

    private float currentSweep() {
        switch (mode) {
            case RUN:
                return sweepForMs(remainingMs);
            case DONE:
                return 0f;
            case SET:
            default:
                return minutes * DEG_PER_MINUTE;
        }
    }

    private float sweepForMs(long ms) {
        return Math.min(360f, ms / 3_600_000f * 360f);
    }

    private static String formatClock(long ms) {
        long totalSeconds = (ms + 999) / 1000; // aufrunden, damit 25:00 beim Start wirklich steht
        return String.format(Locale.GERMAN, "%d:%02d", totalSeconds / 60, totalSeconds % 60);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (mode != Mode.SET || !isEnabled()) {
            return false;
        }
        float x = event.getX();
        float y = event.getY();

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                float distance = (float) Math.hypot(x - cx, y - cy);
                if (distance < radius * 0.5f || distance > radius + ringWidth * 1.6f) {
                    return false; // Mitte und Außenbereich bleiben unempfindlich
                }
                dragging = true;
                if (getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                lastTouchAngle = angleOf(x, y);
                dragSweep = lastTouchAngle;
                applyDragSweep();
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (!dragging) {
                    return false;
                }
                float angle = angleOf(x, y);
                float delta = angle - lastTouchAngle;
                if (delta > 180f) {
                    delta -= 360f;
                } else if (delta < -180f) {
                    delta += 360f;
                }
                lastTouchAngle = angle;
                dragSweep = Math.max(0f, Math.min(360f, dragSweep + delta));
                applyDragSweep();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (!dragging) {
                    return false;
                }
                dragging = false;
                dragSweep = minutes * DEG_PER_MINUTE;
                invalidate();
                return true;
            }
            default:
                return false;
        }
    }

    private void applyDragSweep() {
        int value = Math.round(dragSweep / DEG_PER_MINUTE);
        if (value != minutes) {
            minutes = value;
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            updateContentDescription();
            if (listener != null) {
                listener.onMinutesChanged(minutes);
            }
        }
        invalidate();
    }

    /** Winkel des Punktes gegenüber 12 Uhr, im Uhrzeigersinn, 0..360. */
    private float angleOf(float x, float y) {
        double deg = Math.toDegrees(Math.atan2(x - cx, cy - y));
        return (float) ((deg + 360d) % 360d);
    }

    private void updateContentDescription() {
        switch (mode) {
            case RUN:
                setContentDescription("Timer läuft, " + formatClock(remainingMs) + " verbleibend");
                break;
            case DONE:
                setContentDescription("Timer abgelaufen");
                break;
            default:
                setContentDescription("Dauer " + minutes + " Minuten, zum Ändern ziehen");
                break;
        }
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
