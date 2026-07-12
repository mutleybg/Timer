package com.example.timer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.core.content.ContextCompat;

/**
 * Fired by AlarmManager when the timer is due. Marks the timer as finished (for
 * the real trigger) and starts {@link TimerService} to do the ringing.
 */
public class TimerReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        boolean rerun = intent.getIntExtra(TimerScheduler.EXTRA_RERUN, 0) == 1;

        // Only the original trigger turns the timer off. The automatic re-ring
        // must leave the stored state alone.
        if (!rerun) {
            TimerScheduler.onFired(context);
        }

        // Ring via a foreground service. It surfaces the ring screen through a
        // full-screen-intent notification, which works even when the app has
        // been closed and the device is locked — unlike a startActivity() from
        // this background broadcast, which modern Android blocks.
        Intent svc = new Intent(context, TimerService.class);
        svc.setAction(TimerService.ACTION_START);
        svc.putExtra(TimerScheduler.EXTRA_RERUN, rerun ? 1 : 0);
        ContextCompat.startForegroundService(context, svc);
    }
}
