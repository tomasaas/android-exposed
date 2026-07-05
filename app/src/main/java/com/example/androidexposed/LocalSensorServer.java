package com.example.androidexposed;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.telephony.CellSignalStrength;
import android.telephony.SignalStrength;
import android.telephony.TelephonyManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

class LocalSensorServer {
    private static final String TAG = "AndroidExposed";

    private final Context context;
    private final int port;
    private final StreamBroadcaster broadcaster;
    private final SensorStreamer sensorStreamer;
    private final LocationStreamer locationStreamer;
    private final TelephonyStreamer telephonyStreamer;
    private final ExecutorService clientPool = Executors.newCachedThreadPool();
    private final AtomicBoolean running = new AtomicBoolean(false);

    private volatile StreamConfig config;
    private volatile ServerSocket serverSocket;
    private volatile String lastError;
    private Thread serverThread;

    LocalSensorServer(
            Context context,
            int port,
            StreamBroadcaster broadcaster,
            SensorStreamer sensorStreamer,
            LocationStreamer locationStreamer,
            TelephonyStreamer telephonyStreamer,
            StreamConfig config
    ) {
        this.context = context.getApplicationContext();
        this.port = port;
        this.broadcaster = broadcaster;
        this.sensorStreamer = sensorStreamer;
        this.locationStreamer = locationStreamer;
        this.telephonyStreamer = telephonyStreamer;
        this.config = config;
    }

    void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }

        serverThread = new Thread(this::acceptLoop, "LocalSensorServer");
        serverThread.start();
    }

    void stop() {
        running.set(false);
        closeQuietly(serverSocket);
        clientPool.shutdownNow();
        broadcaster.closeAll();
    }

    boolean isRunning() {
        return running.get() && lastError == null;
    }

    String getLastError() {
        return lastError;
    }

    StreamConfig getConfig() {
        return config;
    }

    void applyConfig(StreamConfig next) {
        config = next.copy();
        sensorStreamer.applyConfig(config);
        locationStreamer.applyConfig(config);
        telephonyStreamer.applyConfig(config);
    }

    private void acceptLoop() {
        try {
            ServerSocket socket = new ServerSocket();
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(port));
            serverSocket = socket;
            lastError = null;
            Log.i(TAG, "Local server started on port " + port);

            while (running.get()) {
                Socket client = socket.accept();
                clientPool.execute(() -> handleClient(client));
            }
        } catch (IOException e) {
            if (running.get()) {
                lastError = e.getMessage();
                Log.e(TAG, "Server stopped with error", e);
            }
        } finally {
            running.set(false);
            closeQuietly(serverSocket);
            Log.i(TAG, "Local server stopped");
        }
    }

    private void handleClient(Socket socket) {
        try {
            socket.setSoTimeout(15000);
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String requestLine = reader.readLine();
            if (requestLine == null || requestLine.isEmpty()) {
                return;
            }

            String[] requestParts = requestLine.split(" ");
            if (requestParts.length < 2) {
                sendError(socket, 400, "Bad request");
                return;
            }

            Map<String, String> headers = readHeaders(reader);
            String body = readBody(reader, headers);
            route(socket, requestParts[0], URI.create(requestParts[1]), body);
        } catch (HttpError e) {
            sendError(socket, e.statusCode, e.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "HTTP request failed", e);
            sendError(socket, 500, e.getMessage());
        } finally {
            closeQuietly(socket);
        }
    }

    private Map<String, String> readHeaders(BufferedReader reader) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        String line;
        while ((line = reader.readLine()) != null && !line.isEmpty()) {
            int split = line.indexOf(':');
            if (split > 0) {
                headers.put(
                        line.substring(0, split).trim().toLowerCase(Locale.US),
                        line.substring(split + 1).trim()
                );
            }
        }
        return headers;
    }

    private String readBody(BufferedReader reader, Map<String, String> headers) throws IOException {
        int contentLength = parseInt(headers.get("content-length"), 0);
        if (contentLength <= 0) {
            return "";
        }

        char[] body = new char[contentLength];
        int total = 0;
        while (total < contentLength) {
            int read = reader.read(body, total, contentLength - total);
            if (read < 0) {
                break;
            }
            total += read;
        }
        return new String(body, 0, total);
    }

    private void route(Socket socket, String method, URI uri, String body) throws IOException, JSONException, HttpError {
        String path = uri.getPath();
        if ("OPTIONS".equals(method)) {
            sendBytes(socket, 204, "text/plain; charset=utf-8", new byte[0]);
            return;
        }

        if ("GET".equals(method) && "/health".equals(path)) {
            sendJson(socket, 200, healthJson());
            return;
        }

        if ("GET".equals(method) && "/sensors".equals(path)) {
            sendJson(socket, 200, sensorStreamer.catalogJson());
            return;
        }

        if ("GET".equals(method) && "/config".equals(path)) {
            sendJson(socket, 200, config.toJson(sensorStreamer, locationStreamer, telephonyStreamer));
            return;
        }

        if ("POST".equals(method) && "/config".equals(path)) {
            StreamConfig next = StreamConfig.fromJson(body, config);
            applyConfig(next);
            sendJson(socket, 200, next.toJson(sensorStreamer, locationStreamer, telephonyStreamer));
            return;
        }

        if ("GET".equals(method) && "/stream".equals(path)) {
            handleStream(socket);
            return;
        }

        sendError(socket, 404, "Not found");
    }

    private void handleStream(Socket socket) throws IOException {
        socket.setSoTimeout(0);
        OutputStream output = socket.getOutputStream();
        String headers = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/x-ndjson; charset=utf-8\r\n" +
                "Cache-Control: no-cache\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Connection: close\r\n\r\n";
        output.write(headers.getBytes(StandardCharsets.UTF_8));
        output.flush();

        StreamClient client = broadcaster.addClient(output);
        Log.i(TAG, "Stream client connected. Count=" + broadcaster.getClientCount());
        try {
            while (client.isOpen() && running.get()) {
                Thread.sleep(1000);
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } finally {
            broadcaster.removeClient(client);
            Log.i(TAG, "Stream client disconnected. Count=" + broadcaster.getClientCount());
        }
    }

    private JSONObject healthJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("ok", isRunning());
        json.put("timestamp_ms", System.currentTimeMillis());
        json.put("server_port", port);
        json.put("device_model", Build.MANUFACTURER + " " + Build.MODEL);
        json.put("android_version", Build.VERSION.RELEASE);
        json.put("android_sdk", Build.VERSION.SDK_INT);
        json.put("streaming_active", broadcaster.getClientCount() > 0);
        json.put("stream_client_count", broadcaster.getClientCount());
        json.put("sensor_streaming_active", sensorStreamer.isActive());
        json.put("gps_active", locationStreamer.isActive());
        json.put("mobile_signal_active", telephonyStreamer.isActive());
        json.put("missing_permissions", missingPermissionsJson());
        json.put("last_server_error", nullable(lastError));
        json.put("last_sensor_error", nullable(sensorStreamer.getLastError()));
        json.put("last_gps_error", nullable(locationStreamer.getLastError()));
        json.put("last_mobile_signal_error", nullable(telephonyStreamer.getLastError()));
        return json;
    }

    private JSONArray missingPermissionsJson() throws JSONException {
        JSONArray array = new JSONArray();
        addMissing(array, Manifest.permission.ACCESS_FINE_LOCATION);
        addMissing(array, Manifest.permission.ACCESS_COARSE_LOCATION);
        addMissing(array, Manifest.permission.READ_PHONE_STATE);
        if (sensorStreamer.hasBodySensor()) {
            addMissing(array, Manifest.permission.BODY_SENSORS);
        }
        return array;
    }

    private void addMissing(JSONArray array, String permission) throws JSONException {
        if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            array.put(permission);
        }
    }

    private Object nullable(String value) {
        return value == null || value.isEmpty() ? JSONObject.NULL : value;
    }

    private void sendJson(Socket socket, int statusCode, JSONObject json) throws IOException, JSONException {
        sendBytes(socket, statusCode, "application/json; charset=utf-8", json.toString(2).getBytes(StandardCharsets.UTF_8));
    }

    private void sendError(Socket socket, int statusCode, String message) {
        try {
            JSONObject json = new JSONObject();
            json.put("error", message == null ? "unknown error" : message);
            sendJson(socket, statusCode, json);
        } catch (Exception ignored) {
        }
    }

    private void sendBytes(Socket socket, int statusCode, String contentType, byte[] bytes) throws IOException {
        OutputStream output = socket.getOutputStream();
        ByteArrayOutputStream response = new ByteArrayOutputStream();
        response.write(("HTTP/1.1 " + statusCode + " " + statusText(statusCode) + "\r\n").getBytes(StandardCharsets.UTF_8));
        response.write(("Content-Type: " + contentType + "\r\n").getBytes(StandardCharsets.UTF_8));
        response.write(("Content-Length: " + bytes.length + "\r\n").getBytes(StandardCharsets.UTF_8));
        response.write("Access-Control-Allow-Origin: *\r\n".getBytes(StandardCharsets.UTF_8));
        response.write("Connection: close\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        response.write(bytes);
        output.write(response.toByteArray());
        output.flush();
    }

    private String statusText(int code) {
        switch (code) {
            case 200:
                return "OK";
            case 204:
                return "No Content";
            case 400:
                return "Bad Request";
            case 403:
                return "Forbidden";
            case 404:
                return "Not Found";
            case 501:
                return "Not Implemented";
            default:
                return "Error";
        }
    }

    private void closeQuietly(Object closeable) {
        try {
            if (closeable instanceof ServerSocket) {
                ((ServerSocket) closeable).close();
            } else if (closeable instanceof Socket) {
                ((Socket) closeable).close();
            }
        } catch (IOException ignored) {
        }
    }

    private int parseInt(String value, int fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}

class StreamConfig {
    final LinkedHashMap<String, Integer> ratesHz = new LinkedHashMap<>();
    boolean includeAllAvailable;

    static StreamConfig defaultConfig() {
        StreamConfig config = new StreamConfig();
        config.ratesHz.put("accelerometer", 100);
        config.ratesHz.put("gyroscope", 100);
        config.ratesHz.put("magnetometer", 50);
        config.ratesHz.put("rotation_vector", 100);
        config.ratesHz.put("gps", 1);
        config.ratesHz.put("mobile_signal", 1);
        config.includeAllAvailable = false;
        return config;
    }

    StreamConfig copy() {
        StreamConfig copy = new StreamConfig();
        copy.ratesHz.putAll(ratesHz);
        copy.includeAllAvailable = includeAllAvailable;
        return copy;
    }

    static StreamConfig fromJson(String body, StreamConfig current) throws JSONException, HttpError {
        if (body == null || body.trim().isEmpty()) {
            throw new HttpError(400, "POST /config requires a JSON body");
        }

        JSONObject json = new JSONObject(body);
        StreamConfig next = new StreamConfig();
        next.includeAllAvailable = json.optBoolean("include_all_available", current.includeAllAvailable);
        next.ratesHz.putAll(current.ratesHz);

        JSONObject sensors = json.optJSONObject("sensors");
        if (sensors != null) {
            next.ratesHz.clear();
            Iterator<String> keys = sensors.keys();
            while (keys.hasNext()) {
                String key = normalize(keys.next());
                int hz = Math.max(0, (int) Math.round(sensors.optDouble(key, 0)));
                if (hz > 0) {
                    next.ratesHz.put(key, hz);
                }
            }
        }
        return next;
    }

    String toRequestJsonString() {
        JSONObject json = new JSONObject();
        try {
            json.put("include_all_available", includeAllAvailable);
            JSONObject sensors = new JSONObject();
            for (Map.Entry<String, Integer> entry : ratesHz.entrySet()) {
                sensors.put(entry.getKey(), entry.getValue());
            }
            json.put("sensors", sensors);
        } catch (JSONException ignored) {
        }
        return json.toString();
    }

    JSONObject toJson(SensorStreamer sensors, LocationStreamer location, TelephonyStreamer telephony) {
        JSONObject json = new JSONObject();
        try {
            json.put("include_all_available", includeAllAvailable);
            JSONObject rates = new JSONObject();
            for (Map.Entry<String, Integer> entry : ratesHz.entrySet()) {
                rates.put(entry.getKey(), entry.getValue());
            }
            json.put("sensors", rates);
            json.put("active_android_sensor_rates_hz", sensors.activeRatesJson());
            json.put("gps_active", location.isActive());
            json.put("gps_rate_hz", location.getRateHz());
            json.put("mobile_signal_active", telephony.isActive());
            json.put("mobile_signal_rate_hz", telephony.getRateHz());
        } catch (JSONException ignored) {
        }
        return json;
    }

    int rateForKey(String key) {
        Integer rate = ratesHz.get(normalize(key));
        return rate == null ? 0 : rate;
    }

    static String normalize(String key) {
        return key == null ? "" : key.trim().toLowerCase(Locale.US).replace('-', '_').replace(' ', '_');
    }
}

class StreamBroadcaster {
    private final CopyOnWriteArrayList<StreamClient> clients = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, AtomicLong> sentCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> producedCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> firstSampleMs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> lastSampleMs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> latestSamples = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> lastSentSampleMs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> latestSentSamples = new ConcurrentHashMap<>();

    StreamClient addClient(OutputStream output) {
        StreamClient client = new StreamClient(output);
        clients.add(client);
        return client;
    }

    void removeClient(StreamClient client) {
        clients.remove(client);
        client.close();
    }

    void broadcast(String source, String line) {
        long now = System.currentTimeMillis();
        producedCounts.computeIfAbsent(source, ignored -> new AtomicLong()).incrementAndGet();
        firstSampleMs.computeIfAbsent(source, ignored -> new AtomicLong(now));
        lastSampleMs.computeIfAbsent(source, ignored -> new AtomicLong()).set(now);
        latestSamples.put(source, line);

        if (clients.isEmpty()) {
            return;
        }

        byte[] bytes = (line + "\n").getBytes(StandardCharsets.UTF_8);
        boolean sent = false;
        for (StreamClient client : clients) {
            if (!client.write(bytes)) {
                removeClient(client);
            } else {
                sent = true;
            }
        }

        if (sent) {
            sentCounts.computeIfAbsent(source, ignored -> new AtomicLong()).incrementAndGet();
            lastSentSampleMs.computeIfAbsent(source, ignored -> new AtomicLong()).set(now);
            latestSentSamples.put(source, line);
        }
    }

    int getClientCount() {
        return clients.size();
    }

    Map<String, Long> getCountsSnapshot() {
        return snapshot(sentCounts);
    }

    Map<String, Long> getProducedCountsSnapshot() {
        return snapshot(producedCounts);
    }

    Map<String, Long> getLastSampleTimesSnapshot() {
        return snapshot(lastSampleMs);
    }

    Map<String, String> getLatestSamplesSnapshot() {
        return new LinkedHashMap<>(latestSamples);
    }

    Map<String, Long> getLastSentSampleTimesSnapshot() {
        return snapshot(lastSentSampleMs);
    }

    Map<String, String> getLatestSentSamplesSnapshot() {
        return new LinkedHashMap<>(latestSentSamples);
    }

    Map<String, Double> getApproxRatesSnapshot() {
        LinkedHashMap<String, Double> snapshot = new LinkedHashMap<>();
        long now = System.currentTimeMillis();
        for (Map.Entry<String, AtomicLong> entry : producedCounts.entrySet()) {
            String source = entry.getKey();
            long count = entry.getValue().get();
            AtomicLong first = firstSampleMs.get(source);
            if (first == null || count < 2) {
                snapshot.put(source, 0.0);
            } else {
                double seconds = Math.max(0.001, (now - first.get()) / 1000.0);
                snapshot.put(source, count / seconds);
            }
        }
        return snapshot;
    }

    private Map<String, Long> snapshot(ConcurrentHashMap<String, AtomicLong> counts) {
        LinkedHashMap<String, Long> snapshot = new LinkedHashMap<>();
        for (Map.Entry<String, AtomicLong> entry : counts.entrySet()) {
            snapshot.put(entry.getKey(), entry.getValue().get());
        }
        return snapshot;
    }

    void closeAll() {
        for (StreamClient client : clients) {
            removeClient(client);
        }
    }
}

class StreamClient {
    private final OutputStream output;
    private final AtomicBoolean open = new AtomicBoolean(true);

    StreamClient(OutputStream output) {
        this.output = output;
    }

    boolean isOpen() {
        return open.get();
    }

    boolean write(byte[] bytes) {
        if (!open.get()) {
            return false;
        }
        synchronized (this) {
            try {
                output.write(bytes);
                output.flush();
                return true;
            } catch (IOException e) {
                open.set(false);
                return false;
            }
        }
    }

    void close() {
        open.set(false);
    }
}

class SensorStreamer implements SensorEventListener {
    private final Context context;
    private final SensorManager sensorManager;
    private final StreamBroadcaster broadcaster;
    private final HandlerThread sensorThread = new HandlerThread("SensorStreamer");
    private final Handler sensorHandler;
    private final Object lock = new Object();
    private final AtomicLong sequence = new AtomicLong();
    private final LinkedHashMap<String, Integer> activeRatesHz = new LinkedHashMap<>();

    private volatile boolean active;
    private volatile String lastError;

    SensorStreamer(Context context, StreamBroadcaster broadcaster) {
        this.context = context.getApplicationContext();
        this.broadcaster = broadcaster;
        sensorManager = (SensorManager) this.context.getSystemService(Context.SENSOR_SERVICE);
        sensorThread.start();
        sensorHandler = new Handler(sensorThread.getLooper());
    }

    void applyConfig(StreamConfig config) {
        synchronized (lock) {
            sensorManager.unregisterListener(this);
            activeRatesHz.clear();
            lastError = null;

            List<Sensor> sensors = sensorManager.getSensorList(Sensor.TYPE_ALL);
            for (Sensor sensor : sensors) {
                int rateHz = requestedRate(sensor, config);
                if (rateHz <= 0 || isOneShot(sensor)) {
                    continue;
                }

                int periodUs = samplingPeriodUs(sensor, rateHz);
                try {
                    boolean registered = sensorManager.registerListener(this, sensor, periodUs, 0, sensorHandler);
                    if (registered) {
                        activeRatesHz.put(SensorCatalog.sourceFor(sensor), estimatedRateHz(periodUs));
                    }
                } catch (SecurityException e) {
                    lastError = "Missing permission for " + SensorCatalog.sourceFor(sensor);
                    Log.w("AndroidExposed", lastError, e);
                }
            }

            active = !activeRatesHz.isEmpty();
            Log.i("AndroidExposed", "Active Android sensors: " + activeRatesHz.keySet());
        }
    }

    void stop() {
        sensorManager.unregisterListener(this);
        sensorThread.quitSafely();
        active = false;
    }

    boolean isActive() {
        return active;
    }

    String getLastError() {
        return lastError;
    }

    boolean hasBodySensor() {
        for (Sensor sensor : sensorManager.getSensorList(Sensor.TYPE_ALL)) {
            if (sensor.getType() == Sensor.TYPE_HEART_RATE || sensor.getType() == Sensor.TYPE_HEART_BEAT) {
                return true;
            }
        }
        return false;
    }

    JSONObject catalogJson() {
        JSONObject root = new JSONObject();
        JSONArray array = new JSONArray();
        try {
            for (Sensor sensor : sensorManager.getSensorList(Sensor.TYPE_ALL)) {
                array.put(SensorCatalog.toJson(sensor));
            }
            root.put("count", array.length());
            root.put("sensors", array);
        } catch (JSONException ignored) {
        }
        return root;
    }

    JSONObject activeRatesJson() {
        JSONObject json = new JSONObject();
        synchronized (lock) {
            try {
                for (Map.Entry<String, Integer> entry : activeRatesHz.entrySet()) {
                    json.put(entry.getKey(), entry.getValue());
                }
            } catch (JSONException ignored) {
            }
        }
        return json;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        Sensor sensor = event.sensor;
        String source = SensorCatalog.sourceFor(sensor);
        try {
            JSONObject json = new JSONObject();
            json.put("seq", sequence.incrementAndGet());
            json.put("source", source);
            json.put("timestamp_ns", event.timestamp);
            json.put("accuracy", event.accuracy);
            JSONArray values = new JSONArray();
            for (float value : event.values) {
                values.put(value);
            }
            json.put("values", values);
            json.put("unit", SensorCatalog.unitFor(sensor));
            broadcaster.broadcast(source, json.toString());
        } catch (JSONException e) {
            lastError = e.getMessage();
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    private int requestedRate(Sensor sensor, StreamConfig config) {
        String source = SensorCatalog.sourceFor(sensor);
        int rate = config.rateForKey(source);
        if (rate > 0) {
            return rate;
        }

        rate = config.rateForKey(sensor.getStringType());
        if (rate > 0) {
            return rate;
        }

        rate = config.rateForKey(String.valueOf(sensor.getType()));
        if (rate > 0) {
            return rate;
        }

        return config.includeAllAvailable ? defaultRate(sensor) : 0;
    }

    private int defaultRate(Sensor sensor) {
        switch (sensor.getType()) {
            case Sensor.TYPE_ACCELEROMETER:
            case Sensor.TYPE_GYROSCOPE:
            case Sensor.TYPE_ROTATION_VECTOR:
            case Sensor.TYPE_GAME_ROTATION_VECTOR:
            case Sensor.TYPE_LINEAR_ACCELERATION:
            case Sensor.TYPE_GRAVITY:
                return 100;
            case Sensor.TYPE_MAGNETIC_FIELD:
            case Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED:
                return 50;
            default:
                return 10;
        }
    }

    private int samplingPeriodUs(Sensor sensor, int rateHz) {
        int requested = Math.max(1, 1000000 / Math.max(1, rateHz));
        int minDelay = sensor.getMinDelay();
        if (minDelay > 0 && requested < minDelay) {
            return minDelay;
        }
        return requested;
    }

    private int estimatedRateHz(int periodUs) {
        if (periodUs <= 0) {
            return 0;
        }
        return Math.max(1, Math.round(1000000f / periodUs));
    }

    private boolean isOneShot(Sensor sensor) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP
                && sensor.getReportingMode() == Sensor.REPORTING_MODE_ONE_SHOT;
    }
}

class SensorCatalog {
    static JSONObject toJson(Sensor sensor) throws JSONException {
        JSONObject json = new JSONObject();
        json.put("type", sensor.getType());
        json.put("source", sourceFor(sensor));
        json.put("string_type", sensor.getStringType());
        json.put("name", sensor.getName());
        json.put("vendor", sensor.getVendor());
        json.put("version", sensor.getVersion());
        json.put("resolution", sensor.getResolution());
        json.put("maximum_range", sensor.getMaximumRange());
        json.put("min_delay_us", sensor.getMinDelay());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            json.put("max_delay_us", sensor.getMaxDelay());
            json.put("reporting_mode", reportingMode(sensor.getReportingMode()));
            json.put("wake_up", sensor.isWakeUpSensor());
        }
        return json;
    }

    static String sourceFor(Sensor sensor) {
        switch (sensor.getType()) {
            case Sensor.TYPE_ACCELEROMETER:
                return "accelerometer";
            case Sensor.TYPE_ACCELEROMETER_UNCALIBRATED:
                return "accelerometer_uncalibrated";
            case Sensor.TYPE_GYROSCOPE:
                return "gyroscope";
            case Sensor.TYPE_GYROSCOPE_UNCALIBRATED:
                return "gyroscope_uncalibrated";
            case Sensor.TYPE_MAGNETIC_FIELD:
                return "magnetometer";
            case Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED:
                return "magnetometer_uncalibrated";
            case Sensor.TYPE_ORIENTATION:
                return "orientation";
            case Sensor.TYPE_ROTATION_VECTOR:
                return "rotation_vector";
            case Sensor.TYPE_GAME_ROTATION_VECTOR:
                return "game_rotation_vector";
            case Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR:
                return "geomagnetic_rotation_vector";
            case Sensor.TYPE_GRAVITY:
                return "gravity";
            case Sensor.TYPE_LINEAR_ACCELERATION:
                return "linear_acceleration";
            case Sensor.TYPE_LIGHT:
                return "light";
            case Sensor.TYPE_PROXIMITY:
                return "proximity";
            case Sensor.TYPE_PRESSURE:
                return "pressure";
            case Sensor.TYPE_RELATIVE_HUMIDITY:
                return "relative_humidity";
            case Sensor.TYPE_AMBIENT_TEMPERATURE:
                return "ambient_temperature";
            case Sensor.TYPE_STEP_COUNTER:
                return "step_counter";
            case Sensor.TYPE_STEP_DETECTOR:
                return "step_detector";
            case Sensor.TYPE_HEART_RATE:
                return "heart_rate";
            case Sensor.TYPE_HEART_BEAT:
                return "heart_beat";
            case Sensor.TYPE_SIGNIFICANT_MOTION:
                return "significant_motion";
            case Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT:
                return "low_latency_offbody_detect";
            case Sensor.TYPE_STATIONARY_DETECT:
                return "stationary_detect";
            case Sensor.TYPE_MOTION_DETECT:
                return "motion_detect";
            default:
                String stringType = sourceFromStringType(sensor.getStringType());
                if (!stringType.isEmpty()) {
                    return stringType;
                }
                return "sensor_type_" + sensor.getType();
        }
    }

    private static String sourceFromStringType(String stringType) {
        if (stringType == null || stringType.trim().isEmpty()) {
            return "";
        }
        String source = stringType.trim().toLowerCase(Locale.US);
        source = source.replace("android.sensor.", "");
        source = source.replace("com.google.sensor.", "");
        source = source.replace('.', '_').replace('-', '_').replace(' ', '_');
        return source;
    }

    static String unitFor(Sensor sensor) {
        switch (sensor.getType()) {
            case Sensor.TYPE_ACCELEROMETER:
            case Sensor.TYPE_ACCELEROMETER_UNCALIBRATED:
            case Sensor.TYPE_GRAVITY:
            case Sensor.TYPE_LINEAR_ACCELERATION:
                return "m/s^2";
            case Sensor.TYPE_GYROSCOPE:
            case Sensor.TYPE_GYROSCOPE_UNCALIBRATED:
                return "rad/s";
            case Sensor.TYPE_MAGNETIC_FIELD:
            case Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED:
                return "uT";
            case Sensor.TYPE_ORIENTATION:
                return "deg";
            case Sensor.TYPE_LIGHT:
                return "lux";
            case Sensor.TYPE_PROXIMITY:
                return "cm";
            case Sensor.TYPE_PRESSURE:
                return "hPa";
            case Sensor.TYPE_RELATIVE_HUMIDITY:
                return "%";
            case Sensor.TYPE_AMBIENT_TEMPERATURE:
                return "C";
            case Sensor.TYPE_ROTATION_VECTOR:
            case Sensor.TYPE_GAME_ROTATION_VECTOR:
            case Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR:
                return "unitless";
            default:
                return "unknown";
        }
    }

    private static String reportingMode(int mode) {
        switch (mode) {
            case Sensor.REPORTING_MODE_CONTINUOUS:
                return "continuous";
            case Sensor.REPORTING_MODE_ON_CHANGE:
                return "on_change";
            case Sensor.REPORTING_MODE_ONE_SHOT:
                return "one_shot";
            case Sensor.REPORTING_MODE_SPECIAL_TRIGGER:
                return "special_trigger";
            default:
                return "unknown";
        }
    }
}

class LocationStreamer {
    private final Context context;
    private final StreamBroadcaster broadcaster;
    private final LocationManager locationManager;
    private final AtomicLong sequence = new AtomicLong();
    private final LocationListener listener;

    private volatile boolean active;
    private volatile int rateHz;
    private volatile String lastError;

    LocationStreamer(Context context, StreamBroadcaster broadcaster) {
        this.context = context.getApplicationContext();
        this.broadcaster = broadcaster;
        locationManager = (LocationManager) this.context.getSystemService(Context.LOCATION_SERVICE);
        listener = new LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                sendLocation(location);
            }

            @Override
            public void onStatusChanged(String provider, int status, Bundle extras) {
            }

            @Override
            public void onProviderEnabled(String provider) {
            }

            @Override
            public void onProviderDisabled(String provider) {
            }
        };
    }

    void applyConfig(StreamConfig config) {
        stop();
        rateHz = config.rateForKey("gps");
        if (rateHz <= 0) {
            return;
        }

        if (!hasLocationPermission()) {
            lastError = "Missing location permission";
            return;
        }

        long minTimeMs = Math.max(100, 1000L / Math.max(1, rateHz));
        try {
            boolean requested = false;
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, minTimeMs, 0f, listener, Looper.getMainLooper());
                requested = true;
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, minTimeMs, 0f, listener, Looper.getMainLooper());
                requested = true;
            }
            active = requested;
            lastError = requested ? null : "No enabled location provider";
        } catch (SecurityException e) {
            lastError = "Missing location permission";
        } catch (IllegalArgumentException e) {
            lastError = e.getMessage();
        }
    }

    void stop() {
        try {
            locationManager.removeUpdates(listener);
        } catch (SecurityException ignored) {
        }
        active = false;
    }

    boolean isActive() {
        return active;
    }

    int getRateHz() {
        return active ? rateHz : 0;
    }

    String getLastError() {
        return lastError;
    }

    private boolean hasLocationPermission() {
        return context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void sendLocation(Location location) {
        try {
            JSONObject json = new JSONObject();
            json.put("seq", sequence.incrementAndGet());
            json.put("source", "gps");
            json.put("timestamp_ms", location.getTime());
            json.put("provider", location.getProvider());
            json.put("lat", location.getLatitude());
            json.put("lon", location.getLongitude());
            if (location.hasAltitude()) {
                json.put("altitude_m", location.getAltitude());
            }
            if (location.hasSpeed()) {
                json.put("speed_mps", location.getSpeed());
            }
            if (location.hasBearing()) {
                json.put("bearing_deg", location.getBearing());
            }
            if (location.hasAccuracy()) {
                json.put("accuracy_m", location.getAccuracy());
            }
            broadcaster.broadcast("gps", json.toString());
        } catch (JSONException e) {
            lastError = e.getMessage();
        }
    }
}

class TelephonyStreamer {
    private final Context context;
    private final StreamBroadcaster broadcaster;
    private final TelephonyManager telephonyManager;
    private final HandlerThread thread = new HandlerThread("TelephonyStreamer");
    private final Handler handler;
    private final AtomicLong sequence = new AtomicLong();

    private volatile boolean active;
    private volatile int rateHz;
    private volatile String lastError;

    private final Runnable poller = new Runnable() {
        @Override
        public void run() {
            pollOnce();
            if (active) {
                handler.postDelayed(this, Math.max(1000L, 1000L / Math.max(1, rateHz)));
            }
        }
    };

    TelephonyStreamer(Context context, StreamBroadcaster broadcaster) {
        this.context = context.getApplicationContext();
        this.broadcaster = broadcaster;
        telephonyManager = (TelephonyManager) this.context.getSystemService(Context.TELEPHONY_SERVICE);
        thread.start();
        handler = new Handler(thread.getLooper());
    }

    void applyConfig(StreamConfig config) {
        stop();
        rateHz = config.rateForKey("mobile_signal");
        if (rateHz <= 0) {
            return;
        }

        if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            lastError = "Missing READ_PHONE_STATE permission";
            return;
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            lastError = "Signal strength polling requires Android 9 or newer";
            return;
        }

        active = true;
        lastError = null;
        handler.post(poller);
    }

    void stop() {
        active = false;
        handler.removeCallbacks(poller);
    }

    void close() {
        stop();
        thread.quitSafely();
    }

    boolean isActive() {
        return active;
    }

    int getRateHz() {
        return active ? rateHz : 0;
    }

    String getLastError() {
        return lastError;
    }

    @SuppressWarnings("deprecation")
    private void pollOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return;
        }

        try {
            SignalStrength signalStrength = telephonyManager.getSignalStrength();
            if (signalStrength == null) {
                return;
            }

            int dbm = 0;
            int level = signalStrength.getLevel();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                List<CellSignalStrength> cells = signalStrength.getCellSignalStrengths();
                if (!cells.isEmpty()) {
                    dbm = cells.get(0).getDbm();
                    level = cells.get(0).getLevel();
                }
            }

            JSONObject json = new JSONObject();
            json.put("seq", sequence.incrementAndGet());
            json.put("source", "mobile_signal");
            json.put("timestamp_ms", System.currentTimeMillis());
            json.put("network_type", networkTypeName(telephonyManager.getDataNetworkType()));
            json.put("dbm", dbm);
            json.put("level", level);
            broadcaster.broadcast("mobile_signal", json.toString());
        } catch (SecurityException e) {
            lastError = "Missing READ_PHONE_STATE permission";
            active = false;
        } catch (JSONException e) {
            lastError = e.getMessage();
        }
    }

    private String networkTypeName(int networkType) {
        switch (networkType) {
            case TelephonyManager.NETWORK_TYPE_LTE:
                return "LTE";
            case TelephonyManager.NETWORK_TYPE_NR:
                return "NR";
            case TelephonyManager.NETWORK_TYPE_EDGE:
                return "EDGE";
            case TelephonyManager.NETWORK_TYPE_GPRS:
                return "GPRS";
            case TelephonyManager.NETWORK_TYPE_HSDPA:
            case TelephonyManager.NETWORK_TYPE_HSPA:
            case TelephonyManager.NETWORK_TYPE_HSUPA:
                return "HSPA";
            case TelephonyManager.NETWORK_TYPE_UMTS:
                return "UMTS";
            default:
                return "UNKNOWN";
        }
    }
}

class HttpError extends Exception {
    final int statusCode;

    HttpError(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }
}
