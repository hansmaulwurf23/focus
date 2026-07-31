package de.noisebleed.focus;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Menu;
import android.view.MenuItem;
import android.view.WindowManager;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.snackbar.Snackbar;

import de.noisebleed.focus.widget.RingTimerView;

public class MainActivity extends AppCompatActivity {

    /** Feiner Takt, damit das Kreissegment flüssig schrumpft (Text bleibt sekundengenau). */
    private static final long TICK_MS = 200L;

    private RingTimerView ring;
    private FloatingActionButton actionButton;

    private final Handler ticker = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (TimerState.getPhase(MainActivity.this) != TimerState.Phase.RUNNING) {
                syncUi();
                return;
            }
            long remaining = TimerState.getRemainingMs(MainActivity.this);
            ring.setRunning(remaining, TimerState.getTotalMs(MainActivity.this));
            ticker.postDelayed(this, TICK_MS);
        }
    };

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            syncUi();
        }
    };

    private final ActivityResultLauncher<String> notificationPermission =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (!granted) {
                    Snackbar.make(ring, R.string.hint_no_notifications, Snackbar.LENGTH_LONG).show();
                }
                startTimer();
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        ring = findViewById(R.id.ring);
        actionButton = findViewById(R.id.action_button);

        ring.setOnMinutesChangeListener(minutes -> {
            TimerState.setSetMinutes(this, minutes);
            updateActionButton(TimerState.Phase.IDLE);
        });
        actionButton.setOnClickListener(v -> onActionClicked());
    }

    @Override
    protected void onResume() {
        super.onResume();
        ContextCompat.registerReceiver(this, stateReceiver,
                new IntentFilter(TimerService.ACTION_STATE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED);
        syncUi();
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(stateReceiver);
        ticker.removeCallbacks(tick);
    }

    // ---------------------------------------------------------------- UI

    private void syncUi() {
        ticker.removeCallbacks(tick);
        TimerState.Phase phase = TimerState.getPhase(this);
        switch (phase) {
            case RUNNING:
                ring.setEnabled(false);
                ring.setRunning(TimerState.getRemainingMs(this), TimerState.getTotalMs(this));
                ticker.postDelayed(tick, TICK_MS);
                break;
            case RINGING:
                ring.setEnabled(false);
                ring.setFinished(TimerState.getTotalMs(this));
                break;
            case IDLE:
            default:
                ring.setEnabled(true);
                ring.setMinutes(TimerState.getSetMinutes(this));
                break;
        }
        keepScreenOn(phase != TimerState.Phase.IDLE);
        updateActionButton(phase);
        invalidateOptionsMenu();
    }

    private void updateActionButton(TimerState.Phase phase) {
        switch (phase) {
            case RUNNING:
                actionButton.setImageResource(R.drawable.ic_stop);
                actionButton.setContentDescription(getString(R.string.action_cancel));
                actionButton.setEnabled(true);
                break;
            case RINGING:
                actionButton.setImageResource(R.drawable.ic_stop);
                actionButton.setContentDescription(getString(R.string.action_stop));
                actionButton.setEnabled(true);
                break;
            case IDLE:
            default:
                actionButton.setImageResource(R.drawable.ic_play);
                actionButton.setContentDescription(getString(R.string.action_start));
                actionButton.setEnabled(ring.getMinutes() > 0);
                actionButton.setAlpha(ring.getMinutes() > 0 ? 1f : 0.4f);
                return;
        }
        actionButton.setAlpha(1f);
    }

    /**
     * Solange der Timer läuft, soll das Display an bleiben. Mehr als dieses Flag geht nicht, ohne
     * die Bildschirmsperre zu umgehen — es wirkt nur, während diese Activity im Vordergrund ist.
     */
    private void keepScreenOn(boolean on) {
        if (on) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    private void onActionClicked() {
        switch (TimerState.getPhase(this)) {
            case RUNNING:
                TimerService.send(this, TimerService.ACTION_CANCEL);
                break;
            case RINGING:
                TimerService.send(this, TimerService.ACTION_DISMISS);
                break;
            case IDLE:
            default:
                if (ring.getMinutes() <= 0) {
                    Snackbar.make(ring, R.string.hint_pick_duration, Snackbar.LENGTH_SHORT).show();
                    return;
                }
                ensureNotificationPermissionThenStart();
                break;
        }
    }

    private void ensureNotificationPermissionThenStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS);
            return;
        }
        startTimer();
    }

    private void startTimer() {
        int minutes = TimerState.getSetMinutes(this);
        if (minutes <= 0) {
            return;
        }
        TimerService.start(this, minutes);
        syncUi();
    }

    // ---------------------------------------------------------------- Menü

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int minutes = shortcutMinutes(item.getItemId());
        if (minutes <= 0) {
            return super.onOptionsItemSelected(item);
        }
        if (TimerState.getPhase(this) != TimerState.Phase.IDLE) {
            Snackbar.make(ring, R.string.hint_timer_active, Snackbar.LENGTH_SHORT).show();
            return true;
        }
        TimerState.setSetMinutes(this, minutes);
        ring.setMinutes(minutes);
        updateActionButton(TimerState.Phase.IDLE);
        return true;
    }

    private int shortcutMinutes(int itemId) {
        if (itemId == R.id.shortcut_60) return 60;
        if (itemId == R.id.shortcut_45) return 45;
        if (itemId == R.id.shortcut_30) return 30;
        if (itemId == R.id.shortcut_20) return 20;
        if (itemId == R.id.shortcut_15) return 15;
        if (itemId == R.id.shortcut_10) return 10;
        if (itemId == R.id.shortcut_5) return 5;
        return 0;
    }
}
