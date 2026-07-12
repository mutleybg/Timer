package com.example.timer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.io.IOException;

/**
 * Foreground service that actually rings the timer. It is started by
 * {@link TimerReceiver} when the timer is due, so it sounds even if the app has
 * been swiped away / closed.
 *
 * <p>It shows a high-priority notification carrying a <em>full-screen intent</em>
 * to {@link TimerRingActivity}; the system launches that over the lock screen —
 * the sanctioned way to surface a timer UI from the background, where a plain
 * {@code startActivity()} is blocked on modern Android. The service — not the
 * activity — owns the ring sound, the one-minute auto-timeout and the single
 * automatic re-ring, so the timer behaves correctly whether or not the ring
 * screen is actually on top.
 */
public class TimerService extends Service {

    static final String ACTION_START = "com.example.timer.action.START_RING";
    static final String ACTION_DISMISS = "com.example.timer.action.DISMISS_RING";

    private static final String CHANNEL_ID = "timer_ring";
    private static final int NOTIFICATION_ID = 3000;

    private MediaPlayer mediaPlayer;
    private final Handler timeoutHandler = new Handler(Looper.getMainLooper());
    private final Runnable ringTimeout = this::onRingTimeout;

    /**
     * A Bulgarian-locale view of this context for looking up user-facing strings.
     * A Service doesn't go through {@link BaseActivity}, so without this its
     * {@code getString} would follow the device locale instead of the app's
     * forced Bulgarian (see {@link LocaleHelper}).
     */
    private Context strings;

    /** True when this is the automatic re-ring (so it must not schedule another). */
    private boolean rerun;

    /**
     * Stops the timer when the screen turns off, i.e. the user pressed the power
     * button while it was ringing. Apps can't observe {@code KEYCODE_POWER}
     * directly, but a power press turns the screen off, which fires this system
     * broadcast — so we treat it exactly like tapping "Stop".
     */
    private final BroadcastReceiver screenOffReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                TimerScheduler.cancelRerun(TimerService.this);
                stopEverything();
            }
        }
    };
    private boolean screenOffRegistered;

    @Override
    public void onCreate() {
        super.onCreate();
        strings = LocaleHelper.wrap(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_DISMISS.equals(action)) {
            // User stopped/confirmed it: no automatic re-ring.
            TimerScheduler.cancelRerun(this);
            stopEverything();
            return START_NOT_STICKY;
        }

        rerun = intent != null && intent.getIntExtra(TimerScheduler.EXTRA_RERUN, 0) == 1;

        startInForeground();
        startRinging();
        // Let a power-button press (screen off) stop the timer.
        registerScreenOff();
        // Give up ringing after a minute if the user never confirms it.
        timeoutHandler.removeCallbacks(ringTimeout);
        timeoutHandler.postDelayed(ringTimeout, TimerScheduler.RING_TIMEOUT_MS);
        return START_NOT_STICKY;
    }

    /** Reached when the ring was not confirmed within the ring window. */
    private void onRingTimeout() {
        // The first ring gets one more attempt a minute later; the re-ring does
        // not, so the timer then stays quiet for good.
        if (!rerun) {
            TimerScheduler.scheduleRerun(this);
        }
        stopEverything();
    }

    private void stopEverything() {
        timeoutHandler.removeCallbacks(ringTimeout);
        unregisterScreenOff();
        stopRinging();
        // Close the ring screen too, if it happens to be showing.
        sendBroadcast(new Intent(TimerRingActivity.ACTION_FINISH).setPackage(getPackageName()));
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void startInForeground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, strings.getString(R.string.timer_channel_name),
                NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription(strings.getString(R.string.timer_channel_desc));
        // The service plays the timer sound itself; the channel stays silent.
        channel.setSound(null, null);
        nm.createNotificationChannel(channel);

        Intent ring = new Intent(this, TimerRingActivity.class);
        ring.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent fullScreen = PendingIntent.getActivity(
                this, 0, ring,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(strings.getString(R.string.timer_finished))
                .setContentText(strings.getString(R.string.app_name))
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setOngoing(true)
                .setFullScreenIntent(fullScreen, true)
                .setContentIntent(fullScreen)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    /** Starts listening for the screen turning off (power button) while ringing. */
    private void registerScreenOff() {
        if (screenOffRegistered) {
            return;
        }
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenOffReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(screenOffReceiver, filter);
        }
        screenOffRegistered = true;
    }

    private void unregisterScreenOff() {
        if (screenOffRegistered) {
            unregisterReceiver(screenOffReceiver);
            screenOffRegistered = false;
        }
    }

    private void startRinging() {
        // The timer sound is bundled with the app (res/raw/timer_sound.mp3).
        // MediaPlayer (not Ringtone) is used because it loops reliably: a looping
        // Ringtone stops after one pass on some devices, cutting the ring short.
        Uri soundUri = Uri.parse(
                "android.resource://" + getPackageName() + "/" + R.raw.timer_sound);
        mediaPlayer = new MediaPlayer();
        // Play on the alarm stream so it is audible even at low media volume.
        mediaPlayer.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build());
        // Loop so a short clip keeps sounding for the whole ring window.
        mediaPlayer.setLooping(true);
        try {
            mediaPlayer.setDataSource(this, soundUri);
            mediaPlayer.prepare();
            mediaPlayer.start();
        } catch (IOException | IllegalStateException e) {
            // Couldn't play the bundled sound; release so we don't leak it.
            mediaPlayer.release();
            mediaPlayer = null;
        }
    }

    private void stopRinging() {
        if (mediaPlayer != null) {
            if (mediaPlayer.isPlaying()) {
                mediaPlayer.stop();
            }
            mediaPlayer.release();
            mediaPlayer = null;
        }
    }

    @Override
    public void onDestroy() {
        timeoutHandler.removeCallbacks(ringTimeout);
        unregisterScreenOff();
        stopRinging();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
