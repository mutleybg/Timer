package com.example.timer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Re-arms a still-running timer after the device reboots. */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            TimerScheduler.rescheduleIfRunning(context);
        }
    }
}
