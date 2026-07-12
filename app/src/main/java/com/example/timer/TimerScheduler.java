package com.example.timer;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/**
 * Central place for starting, cancelling and persisting the single countdown
 * timer. Unlike a wall-clock alarm, a timer counts down from "now" for a chosen
 * number of minutes, so what we persist and schedule is an <em>absolute</em>
 * trigger time ({@code now + minutes}). Persisting it lets {@link BootReceiver}
 * re-arm a still-running timer after a reboot (AlarmManager forgets everything
 * across reboots).
 */
public final class TimerScheduler {

    private static final String PREFS = "timer_prefs";
    private static final String KEY_MINUTES = "minutes";
    private static final String KEY_RUNNING = "running";
    private static final String KEY_TRIGGER_AT = "trigger_at";

    /** 1 when the fire is the single automatic re-ring, 0 for the real trigger. */
    static final String EXTRA_RERUN = "timer_rerun";

    private static final int REQUEST_CODE = 1000;
    private static final int RERUN_REQUEST_CODE = 2000;

    /** How long the timer rings before it gives up if left unconfirmed (ms). */
    public static final long RING_TIMEOUT_MS = 60_000L;
    /** Silent gap before the single automatic re-ring (ms). */
    private static final long RERUN_DELAY_MS = 60_000L;

    private static final int DEFAULT_MINUTES = 5;

    private TimerScheduler() {
    }

    // --- Persistence -------------------------------------------------------

    /** Last minutes value the user picked, so the UI restores it next time. */
    public static int getMinutes(Context context) {
        return prefs(context).getInt(KEY_MINUTES, DEFAULT_MINUTES);
    }

    public static void setMinutes(Context context, int minutes) {
        prefs(context).edit().putInt(KEY_MINUTES, minutes).apply();
    }

    public static boolean isRunning(Context context) {
        return prefs(context).getBoolean(KEY_RUNNING, false);
    }

    /** Absolute wall-clock time (ms) the running timer is due, or 0 if none. */
    public static long getTriggerAt(Context context) {
        return prefs(context).getLong(KEY_TRIGGER_AT, 0L);
    }

    // --- Scheduling --------------------------------------------------------

    /**
     * Starts a countdown of {@code minutes} from now: persists it and arms an
     * exact alarm for {@code now + minutes}. Replaces any timer already running.
     */
    public static void start(Context context, int minutes) {
        long triggerAt = System.currentTimeMillis() + minutes * 60_000L;
        prefs(context).edit()
                .putInt(KEY_MINUTES, minutes)
                .putBoolean(KEY_RUNNING, true)
                .putLong(KEY_TRIGGER_AT, triggerAt)
                .apply();

        AlarmManager alarmManager = context.getSystemService(AlarmManager.class);
        AlarmManager.AlarmClockInfo info =
                new AlarmManager.AlarmClockInfo(triggerAt, showIntent(context));
        // setAlarmClock survives Doze and surfaces the timer in the status bar.
        alarmManager.setAlarmClock(info, timerPendingIntent(context));
    }

    /**
     * Cancels the timer completely: clears the OS alarm, any pending re-ring and
     * the running flag. Used by the Stop button and by "Stop" on the ring screen.
     */
    public static void stop(Context context) {
        prefs(context).edit()
                .putBoolean(KEY_RUNNING, false)
                .putLong(KEY_TRIGGER_AT, 0L)
                .apply();
        AlarmManager alarmManager = context.getSystemService(AlarmManager.class);
        alarmManager.cancel(timerPendingIntent(context));
        alarmManager.cancel(rerunPendingIntent(context));
    }

    /**
     * Called after the timer fires. The countdown is one-shot, so it turns
     * itself off — but the (independent) re-ring may still be armed by the
     * service if the ring was left unconfirmed.
     */
    public static void onFired(Context context) {
        prefs(context).edit()
                .putBoolean(KEY_RUNNING, false)
                .putLong(KEY_TRIGGER_AT, 0L)
                .apply();
    }

    /**
     * Arms the single automatic re-ring one minute from now. Called when a ring
     * is left unconfirmed so the timer gets one more chance to alert the user.
     */
    public static void scheduleRerun(Context context) {
        long triggerAt = System.currentTimeMillis() + RERUN_DELAY_MS;
        AlarmManager alarmManager = context.getSystemService(AlarmManager.class);
        AlarmManager.AlarmClockInfo info =
                new AlarmManager.AlarmClockInfo(triggerAt, showIntent(context));
        alarmManager.setAlarmClock(info, rerunPendingIntent(context));
    }

    /** Cancels a pending automatic re-ring, if any (e.g. when confirmed). */
    public static void cancelRerun(Context context) {
        context.getSystemService(AlarmManager.class)
                .cancel(rerunPendingIntent(context));
    }

    /**
     * Re-arms a still-running timer after a reboot. If its trigger time has
     * already passed while the device was off, {@code setAlarmClock} fires it
     * immediately, which is the right behaviour for a missed timer.
     */
    public static void rescheduleIfRunning(Context context) {
        if (!isRunning(context)) {
            return;
        }
        long triggerAt = getTriggerAt(context);
        AlarmManager alarmManager = context.getSystemService(AlarmManager.class);
        AlarmManager.AlarmClockInfo info =
                new AlarmManager.AlarmClockInfo(triggerAt, showIntent(context));
        alarmManager.setAlarmClock(info, timerPendingIntent(context));
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static PendingIntent timerPendingIntent(Context context) {
        Intent intent = new Intent(context, TimerReceiver.class);
        return PendingIntent.getBroadcast(
                context, REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** PendingIntent for the automatic re-ring; carries EXTRA_RERUN = 1. */
    private static PendingIntent rerunPendingIntent(Context context) {
        Intent intent = new Intent(context, TimerReceiver.class);
        intent.putExtra(EXTRA_RERUN, 1);
        return PendingIntent.getBroadcast(
                context, RERUN_REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** PendingIntent used by the system "alarm" icon to open the app. */
    private static PendingIntent showIntent(Context context) {
        Intent intent = new Intent(context, MainActivity.class);
        return PendingIntent.getActivity(
                context, REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
