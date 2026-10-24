package com.example.calmzone;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/** Foreground service so noise cancelling keeps running with the screen off. */
public class AncService extends Service {
    private static final String CHANNEL = "anc";
    private static final String EXTRA_CANCEL = "cancel";
    private static final String EXTRA_CAL = "cal";
    private static final String ACTION_STOP = "stop";

    public static void start(Context c, boolean thenCancel, boolean forceCalibration) {
        Intent i = new Intent(c, AncService.class)
                .putExtra(EXTRA_CANCEL, thenCancel)
                .putExtra(EXTRA_CAL, forceCalibration);
        c.startForegroundService(i);
    }

    public static void stop(Context c) {
        AncEngine.get().stop();
        c.startService(new Intent(c, AncService.class).setAction(ACTION_STOP));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        AncEngine.get().setOnFinished(this::stopSelf);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            AncEngine.get().stop();
            stopSelf();
            return START_NOT_STICKY;
        }
        goForeground();
        boolean cancel = intent == null || intent.getBooleanExtra(EXTRA_CANCEL, true);
        boolean cal = intent != null && intent.getBooleanExtra(EXTRA_CAL, false);
        AncEngine.get().start(getApplicationContext(), cancel, cal);
        return START_NOT_STICKY;
    }

    private void goForeground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Noise cancelling", NotificationManager.IMPORTANCE_LOW));
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("Calm Zone")
                .setContentText("Noise cancelling is running")
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(1, n);
        }
    }

    @Override
    public void onDestroy() {
        AncEngine.get().stop();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
