package de.noisebleed.focus;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Persistenter Timer-Zustand, geteilt zwischen {@link MainActivity} und {@link TimerService}.
 *
 * <p>Bewusst in den Prefs und nicht nur im Speicher, damit ein Neustart der Activity oder des
 * Service (z. B. nachdem das System den Prozess beendet hat) den laufenden Timer wiederfindet.
 */
public final class TimerState {

    public enum Phase { IDLE, RUNNING, RINGING }

    private static final String PREFS = "focus_timer";
    private static final String KEY_SET_MINUTES = "set_minutes";
    private static final String KEY_END_TIME = "end_time";
    private static final String KEY_TOTAL_MS = "total_ms";
    private static final String KEY_RINGING = "ringing";

    public static final int DEFAULT_MINUTES = 25;

    private TimerState() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static int getSetMinutes(Context context) {
        return prefs(context).getInt(KEY_SET_MINUTES, DEFAULT_MINUTES);
    }

    public static void setSetMinutes(Context context, int minutes) {
        prefs(context).edit().putInt(KEY_SET_MINUTES, minutes).apply();
    }

    public static Phase getPhase(Context context) {
        SharedPreferences p = prefs(context);
        if (p.getBoolean(KEY_RINGING, false)) {
            return Phase.RINGING;
        }
        return p.getLong(KEY_END_TIME, 0L) > 0L ? Phase.RUNNING : Phase.IDLE;
    }

    public static long getEndTime(Context context) {
        return prefs(context).getLong(KEY_END_TIME, 0L);
    }

    public static long getTotalMs(Context context) {
        return prefs(context).getLong(KEY_TOTAL_MS, 0L);
    }

    public static long getRemainingMs(Context context) {
        long end = getEndTime(context);
        return end <= 0L ? 0L : Math.max(0L, end - System.currentTimeMillis());
    }

    static void markRunning(Context context, long endTime, long totalMs) {
        prefs(context).edit()
                .putLong(KEY_END_TIME, endTime)
                .putLong(KEY_TOTAL_MS, totalMs)
                .putBoolean(KEY_RINGING, false)
                .apply();
    }

    static void markRinging(Context context) {
        prefs(context).edit()
                .putLong(KEY_END_TIME, 0L)
                .putBoolean(KEY_RINGING, true)
                .apply();
    }

    static void markIdle(Context context) {
        prefs(context).edit()
                .putLong(KEY_END_TIME, 0L)
                .putBoolean(KEY_RINGING, false)
                .apply();
    }
}
