package com.example.timer;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.ViewParent;
import android.widget.NumberPicker;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;

import com.example.timer.databinding.ActivityMainBinding;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.Locale;

/**
 * The main (and only) screen: pick a number of minutes, then Start a countdown.
 * Start and Stop are mutually exclusive — exactly one is enabled at a time. The
 * timer is armed through {@link TimerScheduler} so it fires (and rings) even if
 * the app is closed. This screen also handles the two permission gates modern
 * Android imposes: notifications (Android 13+) and exact-alarm scheduling
 * (Android 12+), and shows a one-time reliability hint on the first start.
 */
public class MainActivity extends BaseActivity {

    /** Minutes the user can pick: 1..60. */
    private static final int MIN_MINUTES = 1;
    private static final int MAX_MINUTES = 60;

    /** Prefs for one-off UI state (kept separate from the timer data). */
    private static final String UI_PREFS = "ui_prefs";
    private static final String KEY_RELIABILITY_HINT_SHOWN = "reliability_hint_shown";

    /** How often the running-countdown label is refreshed (ms). */
    private static final long REFRESH_INTERVAL_MS = 1_000L;

    private ActivityMainBinding binding;
    private final Handler handler = new Handler(Looper.getMainLooper());

    /** Refreshes the countdown label every second while the timer runs. */
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (refreshRunningState()) {
                handler.postDelayed(this, REFRESH_INTERVAL_MS);
            }
        }
    };

    private final ActivityResultLauncher<String> notificationPermission =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (!granted) {
                    Toast.makeText(this, R.string.notifications_denied, Toast.LENGTH_LONG).show();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        setupPicker();
        binding.startButton.setOnClickListener(v -> onStartClicked());
        binding.stopButton.setOnClickListener(v -> onStopClicked());

        maybeRequestNotificationPermission();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Re-sync from storage: if the timer fired (and turned itself off) while
        // we were away, reflect that in the buttons and status label.
        binding.minutesPicker.setValue(TimerScheduler.getMinutes(this));
        syncUiToState();
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(ticker);
    }

    private void setupPicker() {
        binding.minutesPicker.setMinValue(MIN_MINUTES);
        binding.minutesPicker.setMaxValue(MAX_MINUTES);
        binding.minutesPicker.setFormatter(value -> String.format(Locale.US, "%02d", value));
        binding.minutesPicker.setWrapSelectorWheel(true);
        binding.minutesPicker.setValue(TimerScheduler.getMinutes(this));
        // Inside the ScrollView, make a drag on the picker change its value
        // instead of scrolling the page.
        keepScrollFromStealing(binding.minutesPicker);
    }

    /** The Start button: enforce the exact-alarm gate, then arm the timer. */
    private void onStartClicked() {
        if (!canScheduleExactAlarms()) {
            requestExactAlarmPermission();
            return;
        }
        int minutes = binding.minutesPicker.getValue();
        TimerScheduler.setMinutes(this, minutes);
        TimerScheduler.start(this, minutes);
        maybeShowReliabilityHint();
        syncUiToState();
    }

    /** The Stop button: cancel the timer and stop any ringing in progress. */
    private void onStopClicked() {
        TimerScheduler.stop(this);
        // If the timer is ringing right now, tell the service to stop too.
        Intent stop = new Intent(this, TimerService.class);
        stop.setAction(TimerService.ACTION_DISMISS);
        startService(stop);
        syncUiToState();
    }

    /** Brings the whole UI in line with whether a timer is currently running. */
    private void syncUiToState() {
        boolean running = TimerScheduler.isRunning(this);
        binding.startButton.setEnabled(!running);
        binding.stopButton.setEnabled(running);
        binding.minutesPicker.setEnabled(!running);

        handler.removeCallbacks(ticker);
        if (running) {
            handler.post(ticker);
        } else {
            binding.stateLabel.setText(R.string.state_off);
        }
    }

    /**
     * Updates the countdown label to the time left. Returns true while the timer
     * is still running (so the ticker keeps going), false once it has finished —
     * in which case it also flips the UI back to the idle, Start-enabled state.
     */
    private boolean refreshRunningState() {
        if (!TimerScheduler.isRunning(this)) {
            syncUiToState();
            return false;
        }
        long remaining = TimerScheduler.getTriggerAt(this) - System.currentTimeMillis();
        if (remaining < 0) {
            remaining = 0;
        }
        long totalSeconds = (remaining + 999) / 1000;
        int minutes = (int) (totalSeconds / 60);
        int seconds = (int) (totalSeconds % 60);
        binding.stateLabel.setText(
                getString(R.string.state_running, minutes, seconds));
        return true;
    }

    /**
     * The first time a timer is started, explain that some phones (Xiaomi,
     * realme, ...) kill background apps and need Autostart / battery exemptions
     * for the timer to fire reliably. Shown once; offers to open the app settings.
     */
    private void maybeShowReliabilityHint() {
        SharedPreferences prefs = getSharedPreferences(UI_PREFS, MODE_PRIVATE);
        if (prefs.getBoolean(KEY_RELIABILITY_HINT_SHOWN, false)) {
            return;
        }
        prefs.edit().putBoolean(KEY_RELIABILITY_HINT_SHOWN, true).apply();

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.hint_title)
                .setMessage(R.string.hint_message)
                .setPositiveButton(R.string.hint_open_settings, (dialog, which) -> openAppSettings())
                .setNegativeButton(R.string.hint_dismiss, null)
                .show();
    }

    /** Opens this app's system settings screen (Autostart / battery / permissions). */
    private void openAppSettings() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName()));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
        } catch (Exception ignored) {
            // No settings activity to handle it; nothing else we can do.
        }
    }

    private boolean canScheduleExactAlarms() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true;
        }
        AlarmManager alarmManager = getSystemService(AlarmManager.class);
        return alarmManager.canScheduleExactAlarms();
    }

    private void requestExactAlarmPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Toast.makeText(this, R.string.grant_exact_alarm, Toast.LENGTH_LONG).show();
            Intent intent = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        }
    }

    private void maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS);
        }
    }

    /**
     * Stops the ScrollView from intercepting vertical drags on the picker.
     * Returns false so the picker still handles the touch itself.
     */
    @SuppressLint("ClickableViewAccessibility")
    private static void keepScrollFromStealing(NumberPicker picker) {
        picker.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                ViewParent parent = v.getParent();
                if (parent != null) {
                    parent.requestDisallowInterceptTouchEvent(true);
                }
            }
            return false;
        });
    }
}
