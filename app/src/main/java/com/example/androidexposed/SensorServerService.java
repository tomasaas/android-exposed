package com.example.androidexposed;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import androidx.annotation.Nullable;

import java.util.Map;

public class SensorServerService extends Service {
    static final int DEFAULT_PORT = 8765;
    static final String ACTION_START = "com.example.androidexposed.START";
    static final String ACTION_STOP = "com.example.androidexposed.STOP";
    static final String EXTRA_CONFIG_JSON = "config_json";

    private static final String TAG = "AndroidExposed";
    private static final String CHANNEL_ID = "android_exposed_server";
    private static final int NOTIFICATION_ID = 8765;

    private static volatile SensorServerService instance;

    private StreamBroadcaster broadcaster;
    private SensorStreamer sensorStreamer;
    private LocationStreamer locationStreamer;
    private TelephonyStreamer telephonyStreamer;
    private LocalSensorServer server;
    private PowerManager.WakeLock wakeLock;
    private int serverPort = DEFAULT_PORT;

    static SensorServerService getInstance() {
        return instance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;

        createNotificationChannel();
        updateForegroundTypes();
        acquireWakeLock();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }

        StreamConfig requestedConfig = configFromIntent(intent);
        if (server == null || !server.isRunning()) {
            startServer(requestedConfig);
        } else {
            applyConfig(requestedConfig);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopServer();
        releaseWakeLock();
        instance = null;
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    void reapplyConfig() {
        if (server == null) {
            return;
        }

        applyConfig(server.getConfig());
    }

    void applyConfig(StreamConfig config) {
        if (server == null || sensorStreamer == null || locationStreamer == null || telephonyStreamer == null) {
            return;
        }

        int configuredPort = getConfiguredPort();
        if (configuredPort != serverPort) {
            stopServer();
            startServer(config);
            return;
        }

        updateForegroundTypes();
        server.applyConfig(config);
    }

    boolean isServerRunning() {
        return server != null && server.isRunning();
    }

    int getServerPort() {
        return serverPort;
    }

    int getClientCount() {
        return broadcaster == null ? 0 : broadcaster.getClientCount();
    }

    boolean isSensorActive() {
        return sensorStreamer != null && sensorStreamer.isActive();
    }

    boolean isGpsActive() {
        return locationStreamer != null && locationStreamer.isActive();
    }

    boolean isMobileSignalActive() {
        return telephonyStreamer != null && telephonyStreamer.isActive();
    }

    boolean isWakeLockHeld() {
        return wakeLock != null && wakeLock.isHeld();
    }

    boolean hasBodySensor() {
        return sensorStreamer != null && sensorStreamer.hasBodySensor();
    }

    String getLastServerError() {
        return server == null ? null : server.getLastError();
    }

    StreamConfig getConfig() {
        return server == null ? StreamConfig.defaultConfig() : server.getConfig();
    }

    String getConfigText() {
        if (server == null) {
            return StreamConfig.defaultConfig().ratesHz.toString();
        }
        return server.getConfig().toJson(sensorStreamer, locationStreamer, telephonyStreamer).toString();
    }

    Map<String, Long> getCountsSnapshot() {
        return broadcaster == null ? java.util.Collections.emptyMap() : broadcaster.getCountsSnapshot();
    }

    Map<String, Long> getProducedCountsSnapshot() {
        return broadcaster == null ? java.util.Collections.emptyMap() : broadcaster.getProducedCountsSnapshot();
    }

    Map<String, Long> getLastSampleTimesSnapshot() {
        return broadcaster == null ? java.util.Collections.emptyMap() : broadcaster.getLastSampleTimesSnapshot();
    }

    Map<String, String> getLatestSamplesSnapshot() {
        return broadcaster == null ? java.util.Collections.emptyMap() : broadcaster.getLatestSamplesSnapshot();
    }

    Map<String, Long> getLastSentSampleTimesSnapshot() {
        return broadcaster == null ? java.util.Collections.emptyMap() : broadcaster.getLastSentSampleTimesSnapshot();
    }

    Map<String, String> getLatestSentSamplesSnapshot() {
        return broadcaster == null ? java.util.Collections.emptyMap() : broadcaster.getLatestSentSamplesSnapshot();
    }

    Map<String, Double> getApproxRatesSnapshot() {
        return broadcaster == null ? java.util.Collections.emptyMap() : broadcaster.getApproxRatesSnapshot();
    }

    String getActiveRatesText() {
        if (sensorStreamer == null) {
            return "{}";
        }
        return sensorStreamer.activeRatesJson().toString();
    }

    String getLastSensorError() {
        return sensorStreamer == null ? null : sensorStreamer.getLastError();
    }

    String getLastGpsError() {
        return locationStreamer == null ? null : locationStreamer.getLastError();
    }

    String getLastMobileSignalError() {
        return telephonyStreamer == null ? null : telephonyStreamer.getLastError();
    }

    private StreamConfig configFromIntent(Intent intent) {
        if (intent != null) {
            String json = intent.getStringExtra(EXTRA_CONFIG_JSON);
            if (json != null && !json.trim().isEmpty()) {
                try {
                    return StreamConfig.fromJson(json, getConfig());
                } catch (Exception e) {
                    Log.w(TAG, "Invalid config intent extra", e);
                }
            }
        }
        return getConfig();
    }

    private void startServer(StreamConfig config) {
        if (server != null && server.isRunning()) {
            applyConfig(config);
            return;
        }
        if (server != null) {
            stopServer();
        }

        broadcaster = new StreamBroadcaster();
        sensorStreamer = new SensorStreamer(this, broadcaster);
        locationStreamer = new LocationStreamer(this, broadcaster);
        telephonyStreamer = new TelephonyStreamer(this, broadcaster);

        sensorStreamer.applyConfig(config);
        locationStreamer.applyConfig(config);
        telephonyStreamer.applyConfig(config);

        serverPort = getConfiguredPort();
        server = new LocalSensorServer(
                this,
                serverPort,
                broadcaster,
                sensorStreamer,
                locationStreamer,
                telephonyStreamer,
                config
        );
        server.start();
        updateForegroundTypes();
        Log.i(TAG, "Foreground sensor server service started");
    }

    private void stopServer() {
        if (server != null) {
            server.stop();
        }
        if (sensorStreamer != null) {
            sensorStreamer.stop();
        }
        if (locationStreamer != null) {
            locationStreamer.stop();
        }
        if (telephonyStreamer != null) {
            telephonyStreamer.close();
        }
        server = null;
        broadcaster = null;
        sensorStreamer = null;
        locationStreamer = null;
        telephonyStreamer = null;
        Log.i(TAG, "Foreground sensor server service stopped");
    }

    private int getConfiguredPort() {
        return getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE)
                .getInt(MainActivity.KEY_SERVER_PORT, DEFAULT_PORT);
    }

    private void acquireWakeLock() {
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AndroidExposed:SensorServer");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire();
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }

        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Android Exposed server",
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("Keeps the local sensor server running.");

        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(channel);
    }

    private void updateForegroundTypes() {
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification);
            return;
        }

        int types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
        if (hasLocationPermission()) {
            types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
        }
        try {
            startForeground(NOTIFICATION_ID, notification, types);
        } catch (SecurityException e) {
            Log.w(TAG, "Could not update foreground service types", e);
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        }
    }

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private Notification buildNotification() {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        return builder
                .setContentTitle("Android Exposed running")
                .setContentText("Sensor server listening on port " + serverPort)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }
}
