package de.noisebleed.focus;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.provider.Settings;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

/**
 * Hält den laufenden Countdown als Foreground-Service (Benachrichtigung mit Restzeit) und spielt am
 * Ende den Alarm ab.
 *
 * <p>Die eigentliche Auslösung übernimmt der {@link AlarmManager} per {@code setAlarmClock} — das
 * feuert auch im Doze punktgenau und selbst dann, wenn das System den Prozess zwischenzeitlich
 * beendet hat.
 */
public class TimerService extends Service {

    private static final String TAG = "TimerService";

    public static final String ACTION_START = "de.noisebleed.focus.action.START";
    public static final String ACTION_CANCEL = "de.noisebleed.focus.action.CANCEL";
    public static final String ACTION_FINISH = "de.noisebleed.focus.action.FINISH";
    public static final String ACTION_DISMISS = "de.noisebleed.focus.action.DISMISS";

    /** Broadcast an die UI, sobald sich die Phase ändert. */
    public static final String ACTION_STATE_CHANGED = "de.noisebleed.focus.STATE_CHANGED";

    public static final String EXTRA_MINUTES = "minutes";

    private static final String CHANNEL_RUNNING = "timer_running";
    private static final String CHANNEL_DONE = "timer_done";
    private static final int NOTIFICATION_ID = 1;
    private static final int REQ_ALARM = 10;
    private static final int REQ_SHOW = 11;
    private static final int REQ_CANCEL = 12;
    private static final int REQ_DISMISS = 13;

    /** Nach dieser Zeit hört der Alarm von selbst auf zu klingeln. */
    private static final long RING_TIMEOUT_MS = 120_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    @Nullable
    private MediaPlayer player;
    @Nullable
    private Vibrator vibrator;
    private final Runnable ringTimeout = this::onRingTimeout;

    public static void start(Context context, int minutes) {
        Intent intent = new Intent(context, TimerService.class)
                .setAction(ACTION_START)
                .putExtra(EXTRA_MINUTES, minutes);
        ContextCompat.startForegroundService(context, intent);
    }

    public static void send(Context context, String action) {
        ContextCompat.startForegroundService(context,
                new Intent(context, TimerService.class).setAction(action));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannels();
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (action == null) {
            // Neustart durch das System: aus dem gespeicherten Zustand wiederherstellen.
            restore();
            return START_STICKY;
        }
        switch (action) {
            case ACTION_START:
                int minutes = intent.getIntExtra(EXTRA_MINUTES, TimerState.DEFAULT_MINUTES);
                startTimer(minutes);
                break;
            case ACTION_FINISH:
                finishTimer();
                break;
            case ACTION_CANCEL:
            case ACTION_DISMISS:
                stopEverything();
                break;
            default:
                break;
        }
        return START_STICKY;
    }

    // ---------------------------------------------------------------- Ablauf

    private void startTimer(int minutes) {
        long total = minutes * 60_000L;
        long endTime = System.currentTimeMillis() + total;
        TimerState.markRunning(this, endTime, total);
        stopRinging();
        startForeground(NOTIFICATION_ID, buildRunningNotification(endTime, minutes));
        scheduleAlarm(endTime);
        broadcastState();
    }

    private void restore() {
        TimerState.Phase phase = TimerState.getPhase(this);
        if (phase == TimerState.Phase.RUNNING) {
            long endTime = TimerState.getEndTime(this);
            int minutes = (int) (TimerState.getTotalMs(this) / 60_000L);
            startForeground(NOTIFICATION_ID, buildRunningNotification(endTime, minutes));
            scheduleAlarm(endTime);
        } else {
            stopSelf();
        }
    }

    private void finishTimer() {
        long total = TimerState.getTotalMs(this);
        TimerState.markRinging(this);
        startForeground(NOTIFICATION_ID, buildDoneNotification(total, true));
        startRinging();
        handler.removeCallbacks(ringTimeout);
        handler.postDelayed(ringTimeout, RING_TIMEOUT_MS);
        broadcastState();
    }

    /** Alarm klingelt zu lange: Ton aus, aber die Benachrichtigung bleibt lesbar stehen. */
    private void onRingTimeout() {
        long total = TimerState.getTotalMs(this);
        stopRinging();
        TimerState.markIdle(this);
        stopForeground(STOP_FOREGROUND_DETACH);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildDoneNotification(total, false));
        }
        broadcastState();
        stopSelf();
    }

    private void stopEverything() {
        handler.removeCallbacks(ringTimeout);
        cancelAlarm();
        stopRinging();
        TimerState.markIdle(this);
        stopForeground(STOP_FOREGROUND_REMOVE);
        broadcastState();
        stopSelf();
    }

    private void broadcastState() {
        sendBroadcast(new Intent(ACTION_STATE_CHANGED).setPackage(getPackageName()));
    }

    // ---------------------------------------------------------------- Alarm

    private PendingIntent alarmIntent() {
        return PendingIntent.getForegroundService(this, REQ_ALARM,
                new Intent(this, TimerService.class).setAction(ACTION_FINISH),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void scheduleAlarm(long endTime) {
        AlarmManager am = getSystemService(AlarmManager.class);
        if (am == null) {
            return;
        }
        PendingIntent operation = alarmIntent();
        try {
            am.setAlarmClock(new AlarmManager.AlarmClockInfo(endTime, showIntent()), operation);
        } catch (SecurityException e) {
            // Exakte Alarme wurden entzogen (Android 12/13) — auf die nächstbeste Stufe zurückfallen.
            Log.w(TAG, "setAlarmClock nicht erlaubt, Fallback auf setExactAndAllowWhileIdle", e);
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endTime, operation);
            } catch (SecurityException e2) {
                am.set(AlarmManager.RTC_WAKEUP, endTime, operation);
            }
        }
    }

    private void cancelAlarm() {
        AlarmManager am = getSystemService(AlarmManager.class);
        if (am != null) {
            am.cancel(alarmIntent());
        }
    }

    // ---------------------------------------------------------------- Ton

    private void startRinging() {
        Uri uri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM);
        if (uri == null) {
            uri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_NOTIFICATION);
        }
        if (uri == null) {
            uri = Settings.System.DEFAULT_ALARM_ALERT_URI;
        }
        try {
            MediaPlayer mp = new MediaPlayer();
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            mp.setDataSource(this, uri);
            mp.setLooping(true);
            mp.setWakeMode(this, android.os.PowerManager.PARTIAL_WAKE_LOCK);
            mp.prepare();
            mp.start();
            player = mp;
        } catch (Exception e) {
            Log.e(TAG, "Alarmton konnte nicht abgespielt werden", e);
        }
        startVibrating();
    }

    private void startVibrating() {
        Vibrator v = vibrator();
        if (v == null || !v.hasVibrator()) {
            return;
        }
        long[] pattern = {0L, 600L, 500L};
        v.vibrate(VibrationEffect.createWaveform(pattern, 0),
                new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build());
    }

    private void stopRinging() {
        if (player != null) {
            try {
                player.stop();
            } catch (IllegalStateException ignored) {
                // schon gestoppt
            }
            player.release();
            player = null;
        }
        Vibrator v = vibrator();
        if (v != null) {
            v.cancel();
        }
    }

    @Nullable
    private Vibrator vibrator() {
        if (vibrator == null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                VibratorManager vm = getSystemService(VibratorManager.class);
                vibrator = vm != null ? vm.getDefaultVibrator() : null;
            } else {
                vibrator = getSystemService(Vibrator.class);
            }
        }
        return vibrator;
    }

    // ---------------------------------------------------------------- Benachrichtigungen

    private void createChannels() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) {
            return;
        }
        NotificationChannel running = new NotificationChannel(CHANNEL_RUNNING,
                getString(R.string.channel_running), NotificationManager.IMPORTANCE_LOW);
        running.setDescription(getString(R.string.channel_running_desc));
        running.setShowBadge(false);
        running.setSound(null, null);
        nm.createNotificationChannel(running);

        // Ton und Vibration macht der Service selbst (Alarm-Kanal, in Dauerschleife).
        NotificationChannel done = new NotificationChannel(CHANNEL_DONE,
                getString(R.string.channel_done), NotificationManager.IMPORTANCE_HIGH);
        done.setDescription(getString(R.string.channel_done_desc));
        done.setSound(null, null);
        done.enableVibration(false);
        nm.createNotificationChannel(done);
    }

    private PendingIntent showIntent() {
        return PendingIntent.getActivity(this, REQ_SHOW,
                new Intent(this, MainActivity.class)
                        .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private PendingIntent servicePendingIntent(int requestCode, String action) {
        return PendingIntent.getForegroundService(this, requestCode,
                new Intent(this, TimerService.class).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private Notification buildRunningNotification(long endTime, int minutes) {
        return new NotificationCompat.Builder(this, CHANNEL_RUNNING)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.notif_running_title))
                .setContentText(getString(R.string.notif_running_text, minutes))
                .setWhen(endTime)
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .setContentIntent(showIntent())
                .addAction(R.drawable.ic_stop, getString(R.string.action_cancel),
                        servicePendingIntent(REQ_CANCEL, ACTION_CANCEL))
                .build();
    }

    private Notification buildDoneNotification(long totalMs, boolean ringing) {
        int minutes = (int) (totalMs / 60_000L);
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_DONE)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.notif_done_title))
                .setContentText(getString(R.string.notif_done_text, minutes))
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .setContentIntent(showIntent());
        if (ringing) {
            b.setOngoing(true)
                    .setAutoCancel(false)
                    .addAction(R.drawable.ic_stop, getString(R.string.action_stop),
                            servicePendingIntent(REQ_DISMISS, ACTION_DISMISS))
                    .setDeleteIntent(servicePendingIntent(REQ_DISMISS, ACTION_DISMISS));
        } else {
            b.setAutoCancel(true);
        }
        return b.build();
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(ringTimeout);
        stopRinging();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
