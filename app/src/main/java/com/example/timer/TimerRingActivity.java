package com.example.timer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowManager;

import com.example.timer.databinding.ActivityRingBinding;

/**
 * Full-screen ring UI, launched by {@link TimerService}'s full-screen-intent
 * notification. Declared in the manifest with showWhenLocked/turnScreenOn so it
 * appears over the lock screen. It does not play the sound itself — the service
 * owns the ring lifecycle; this screen only shows "Stop" (which tells the
 * service to stop) and closes itself when the service says the timer ended.
 */
public class TimerRingActivity extends BaseActivity {

    /** Broadcast from {@link TimerService} telling this screen to close. */
    static final String ACTION_FINISH = "com.example.timer.action.FINISH_RING";

    private final BroadcastReceiver finishReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            finish();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Keep the screen on while ringing so it never times out on its own —
        // that way an actual screen-off means the user pressed the power button,
        // which the service listens for to stop the timer.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        ActivityRingBinding binding = ActivityRingBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        binding.dismissButton.setOnClickListener(v -> dismiss());

        IntentFilter filter = new IntentFilter(ACTION_FINISH);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(finishReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(finishReceiver, filter);
        }
    }

    /** Stop the timer: hand off to the service, which cancels any re-ring. */
    private void dismiss() {
        Intent stop = new Intent(this, TimerService.class);
        stop.setAction(TimerService.ACTION_DISMISS);
        startService(stop);
        finish();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(finishReceiver);
        super.onDestroy();
    }
}
