package com.example.ottylab.bitzenyminer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.preference.PreferenceManager;

import androidx.core.app.NotificationCompat;

import com.example.ottylab.bitzenymininglibrary.BitZenyMiningLibrary;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.Deque;

public class MiningService extends Service {
    public static final String ACTION_START = "com.example.yentenminer.START_MINING";
    public static final String ACTION_STOP = "com.example.yentenminer.STOP_MINING";
    public static final String ACTION_STATE = "com.example.yentenminer.MINING_STATE";
    public static final String ACTION_LOG = "com.example.yentenminer.MINING_LOG";
    public static final String EXTRA_SERVER = "server";
    public static final String EXTRA_USER = "user";
    public static final String EXTRA_PASSWORD = "password";
    public static final String EXTRA_THREADS = "threads";
    public static final String EXTRA_ALGORITHM = "algorithm";
    public static final String EXTRA_BENCHMARK = "benchmark";

    private static final String CHANNEL_ID = "yenten_mining_status";
    private static final int NOTIFICATION_ID = 3107;
    private static final int MAX_CACHED_LOG_LINES = 1000;
    private static final Deque<String> RECENT_LOGS = new ArrayDeque<>();

    private BitZenyMiningLibrary miner;
    private PowerManager.WakeLock wakeLock;
    private boolean miningActive;
    private boolean benchmark;
    private String poolAddress = "";

    private static class ServiceLogHandler extends Handler {
        private final WeakReference<MiningService> service;
        ServiceLogHandler(MiningService service) {
            super(Looper.getMainLooper());
            this.service = new WeakReference<>(service);
        }

        @Override public void handleMessage(android.os.Message message) {
            MiningService owner = service.get();
            if (owner == null) return;
            String line = message.getData().getString("log");
            if (line == null) return;
            synchronized (RECENT_LOGS) {
                if (RECENT_LOGS.size() >= MAX_CACHED_LOG_LINES) RECENT_LOGS.removeFirst();
                RECENT_LOGS.addLast(line);
            }
            owner.sendBroadcast(new Intent(ACTION_LOG).setPackage(owner.getPackageName())
                    .putExtra("log", line));
        }
    }

    public static String recentLogs() {
        StringBuilder output = new StringBuilder();
        synchronized (RECENT_LOGS) {
            for (String line : RECENT_LOGS) output.append(line);
        }
        return output.toString();
    }

    @Override public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        miner = new BitZenyMiningLibrary(new ServiceLogHandler(this));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            stopMiningSession();
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(intent.getAction())) return START_NOT_STICKY;

        benchmark = intent.getBooleanExtra(EXTRA_BENCHMARK, false);
        poolAddress = intent.getStringExtra(EXTRA_SERVER);
        if (poolAddress == null) poolAddress = "";

        startForeground(NOTIFICATION_ID, buildNotification());
        acquireCpuWakeLock();
        SharedPreferences.Editor preferences = PreferenceManager.getDefaultSharedPreferences(this).edit();
        preferences.putBoolean("mining_running", true).apply();

        int algorithmIndex = intent.getIntExtra(EXTRA_ALGORITHM, 1);
        BitZenyMiningLibrary.Algorithm algorithm = algorithmIndex == 0
                ? BitZenyMiningLibrary.Algorithm.YESCRYPT
                : BitZenyMiningLibrary.Algorithm.YESPOWER;
        if (benchmark) {
            miner.startBenchmark(intent.getIntExtra(EXTRA_THREADS, 0), algorithm);
        } else {
            miner.startMining(poolAddress, intent.getStringExtra(EXTRA_USER),
                    intent.getStringExtra(EXTRA_PASSWORD), intent.getIntExtra(EXTRA_THREADS, 0), algorithm);
        }

        miningActive = true;
        broadcastState(true);
        return START_REDELIVER_INTENT;
    }

    private void acquireCpuWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) return;
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        if (powerManager == null) return;
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                getPackageName() + ":YentenMining");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire();
    }

    private void stopMiningSession() {
        if (miningActive && miner != null) miner.stopMining();
        miningActive = false;
        PreferenceManager.getDefaultSharedPreferences(this).edit()
                .putBoolean("mining_running", false).apply();
        releaseCpuWakeLock();
        broadcastState(false);
        stopForeground(true);
    }

    private void releaseCpuWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        wakeLock = null;
    }

    private void broadcastState(boolean running) {
        sendBroadcast(new Intent(ACTION_STATE).setPackage(getPackageName())
                .putExtra("running", running));
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                getString(R.string.notification_channel_name), NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.notification_channel_description));
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        Intent openApp = new Intent(this, MainActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 1, openApp,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stopIntent = new Intent(this, MiningService.class).setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(this, 2, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String content = benchmark ? getString(R.string.notification_benchmark_text)
                : getString(R.string.notification_pool_text, poolAddress);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_mining_notification)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(content)
                .setContentIntent(contentIntent)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(0, getString(R.string.notification_stop), stopPendingIntent)
                .build();
    }

    @Override public void onDestroy() {
        stopMiningSession();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
