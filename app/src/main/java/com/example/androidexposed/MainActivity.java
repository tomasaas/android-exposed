package com.example.androidexposed;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.telephony.TelephonyManager;
import android.text.InputType;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends AppCompatActivity {
    private static final int PERMISSION_REQUEST = 7;
    private static final int PAGE_SENSORS = 0;
    private static final int PAGE_LIVE = 1;
    private static final int PAGE_OUTPUT = 2;
    private static final int PAGE_CONFIG = 3;
    public static final String PREFS_NAME = "server_config";
    public static final String KEY_SERVER_IP = "server_ip";
    public static final String KEY_SERVER_PORT = "server_port";
    public static final String DEFAULT_SERVER_IP = "localhost";
    public static final int DEFAULT_SERVER_PORT = SensorServerService.DEFAULT_PORT;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final LinkedHashMap<String, SourceInfo> sources = new LinkedHashMap<>();
    private final LinkedHashMap<String, SourceControl> controls = new LinkedHashMap<>();

    private StreamConfig selectedConfig = StreamConfig.defaultConfig();
    private int currentPage = PAGE_SENSORS;

    private TextView statusText;
    private TextView permissionsText;
    private TextView endpointText;
    private TextView liveText;
    private TextView outputText;
    private LinearLayout sensorPage;
    private LinearLayout livePage;
    private LinearLayout outputPage;
    private LinearLayout configPage;
    private LinearLayout sensorRows;
    private CheckBox includeAllCheckBox;
    private Button startStopButton;
    private Button sensorsTabButton;
    private Button liveTabButton;
    private Button outputTabButton;
    private Button configTabButton;
    private Button allSensorsButton;
    private EditText serverIpInput;
    private EditText serverPortInput;
    private TextView currentConfigText;

    private final Runnable uiRefresh = new Runnable() {
        @Override
        public void run() {
            refreshUi();
            uiHandler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        SensorServerService service = SensorServerService.getInstance();
        if (service != null) {
            selectedConfig = service.getConfig().copy();
        }

        loadAvailableSources();
        buildUi();
        refreshUi();
        uiHandler.post(uiRefresh);
    }

    @Override
    protected void onDestroy() {
        uiHandler.removeCallbacks(uiRefresh);
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST) {
            SensorServerService service = SensorServerService.getInstance();
            if (service != null) {
                service.reapplyConfig();
            }
            refreshUi();
        }
    }

    private void loadAvailableSources() {
        SensorManager sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        for (Sensor sensor : sensorManager.getSensorList(Sensor.TYPE_ALL)) {
            String source = SensorCatalog.sourceFor(sensor);
            if (!sources.containsKey(source)) {
                sources.put(source, new SourceInfo(
                        source,
                        source,
                        sensor.getName() + " / " + sensor.getVendor() + " / type " + sensor.getType(),
                        suggestedRate(sensor)
                ));
            }
        }

        LocationManager locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (locationManager != null) {
            sources.put("gps", new SourceInfo("gps", "gps", "Android location providers", 1));
        }

        TelephonyManager telephonyManager = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        if (telephonyManager != null && getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
            sources.put("mobile_signal", new SourceInfo("mobile_signal", "mobile_signal", "Telephony signal strength", 1));
        }

    }

    private void buildUi() {
        int padding = dp(16);

        ScrollView scrollView = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(padding, padding, padding, padding);
        scrollView.addView(root);

        TextView title = new TextView(this);
        title.setText("Android Exposed");
        title.setTextSize(22);
        title.setPadding(0, 0, 0, dp(10));
        root.addView(title, matchWrap());

        statusText = sectionText();
        permissionsText = sectionText();
        root.addView(statusText, matchWrap());

        startStopButton = new Button(this);
        startStopButton.setOnClickListener(v -> toggleExposure());
        root.addView(startStopButton, matchWrap());

        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        sensorsTabButton = new Button(this);
        sensorsTabButton.setText("Sensors");
        sensorsTabButton.setOnClickListener(v -> showPage(PAGE_SENSORS));
        liveTabButton = new Button(this);
        liveTabButton.setText("Live");
        liveTabButton.setOnClickListener(v -> showPage(PAGE_LIVE));
        outputTabButton = new Button(this);
        outputTabButton.setText("Output");
        outputTabButton.setOnClickListener(v -> showPage(PAGE_OUTPUT));
        configTabButton = new Button(this);
        configTabButton.setText("Config");
        configTabButton.setOnClickListener(v -> showPage(PAGE_CONFIG));
        tabs.addView(sensorsTabButton, weightWrap());
        tabs.addView(liveTabButton, weightWrap());
        tabs.addView(outputTabButton, weightWrap());
        tabs.addView(configTabButton, weightWrap());
        root.addView(tabs, matchWrap());

        sensorPage = new LinearLayout(this);
        sensorPage.setOrientation(LinearLayout.VERTICAL);
        root.addView(sensorPage, matchWrap());

        livePage = new LinearLayout(this);
        livePage.setOrientation(LinearLayout.VERTICAL);
        root.addView(livePage, matchWrap());

        outputPage = new LinearLayout(this);
        outputPage.setOrientation(LinearLayout.VERTICAL);
        root.addView(outputPage, matchWrap());

        configPage = new LinearLayout(this);
        configPage.setOrientation(LinearLayout.VERTICAL);
        root.addView(configPage, matchWrap());

        buildSensorPage();
        buildLivePage();
        buildOutputPage();
        buildConfigPage();
        setContentView(scrollView);
        showPage(PAGE_SENSORS);
    }

    private void buildSensorPage() {
        endpointText = sectionText();
        sensorPage.addView(endpointText, matchWrap());

        includeAllCheckBox = new CheckBox(this);
        includeAllCheckBox.setText("Stream all available Android sensors");
        includeAllCheckBox.setChecked(selectedConfig.includeAllAvailable);
        sensorPage.addView(includeAllCheckBox, matchWrap());

        allSensorsButton = new Button(this);
        allSensorsButton.setOnClickListener(v -> toggleAllSensors());
        sensorPage.addView(allSensorsButton, matchWrap());

        sensorRows = new LinearLayout(this);
        sensorRows.setOrientation(LinearLayout.VERTICAL);
        sensorPage.addView(sensorRows, matchWrap());

        for (SourceInfo source : sources.values()) {
            addSourceRow(source);
        }

        Button applyButton = new Button(this);
        applyButton.setText("Apply selection");
        applyButton.setOnClickListener(v -> applySelection());
        sensorPage.addView(applyButton, matchWrap());

        Button permissionButton = new Button(this);
        permissionButton.setText("Request permissions for enabled sources");
        permissionButton.setOnClickListener(v -> requestPermissionsForConfig(readConfigFromControls()));
        sensorPage.addView(permissionButton, matchWrap());

        sensorPage.addView(permissionsText, matchWrap());
    }

    private void buildLivePage() {
        liveText = sectionText();
        livePage.addView(liveText, matchWrap());
    }

    private void buildConfigPage() {
        TextView configTitle = sectionText();
        configTitle.setText(getString(R.string.config_title));
        configTitle.setTextSize(18);
        configPage.addView(configTitle, matchWrap());

        currentConfigText = sectionText();
        configPage.addView(currentConfigText, matchWrap());

        serverIpInput = new EditText(this);
        serverIpInput.setSingleLine(true);
        serverIpInput.setHint(R.string.server_ip);
        serverIpInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        configPage.addView(serverIpInput, matchWrap());

        serverPortInput = new EditText(this);
        serverPortInput.setSingleLine(true);
        serverPortInput.setHint(R.string.server_port);
        serverPortInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        configPage.addView(serverPortInput, matchWrap());

        Button saveButton = new Button(this);
        saveButton.setText(R.string.save_config);
        saveButton.setOnClickListener(view -> saveConfig());
        configPage.addView(saveButton, matchWrap());

        loadConfig();
    }

    private void buildOutputPage() {
        outputText = sectionText();
        outputPage.addView(outputText, matchWrap());
    }

    private void addSourceRow(SourceInfo source) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));

        CheckBox checkBox = new CheckBox(this);
        checkBox.setText(source.label);
        checkBox.setChecked(selectedConfig.rateForKey(source.key) > 0);
        row.addView(checkBox, matchWrap());

        TextView detail = new TextView(this);
        detail.setText(source.detail);
        detail.setTextSize(12);
        row.addView(detail, matchWrap());

        EditText rateEdit = new EditText(this);
        rateEdit.setInputType(InputType.TYPE_CLASS_NUMBER);
        rateEdit.setSingleLine(true);
        rateEdit.setHint("Hz");
        rateEdit.setText(String.valueOf(rateForUi(source)));
        row.addView(rateEdit, matchWrap());

        sensorRows.addView(row, matchWrap());
        controls.put(source.key, new SourceControl(source, checkBox, rateEdit));
    }

    private void toggleAllSensors() {
        boolean allSelected = areAllSensorsSelected();
        for (SourceControl control : controls.values()) {
            control.checkBox.setChecked(!allSelected);
        }
        includeAllCheckBox.setChecked(false);
        refreshUi();
    }

    private boolean areAllSensorsSelected() {
        if (controls.isEmpty()) {
            return false;
        }
        for (SourceControl control : controls.values()) {
            if (!control.checkBox.isChecked()) {
                return false;
            }
        }
        return true;
    }

    private void showPage(int page) {
        currentPage = page;
        sensorPage.setVisibility(page == PAGE_SENSORS ? View.VISIBLE : View.GONE);
        livePage.setVisibility(page == PAGE_LIVE ? View.VISIBLE : View.GONE);
        outputPage.setVisibility(page == PAGE_OUTPUT ? View.VISIBLE : View.GONE);
        configPage.setVisibility(page == PAGE_CONFIG ? View.VISIBLE : View.GONE);
        sensorsTabButton.setEnabled(page != PAGE_SENSORS);
        liveTabButton.setEnabled(page != PAGE_LIVE);
        outputTabButton.setEnabled(page != PAGE_OUTPUT);
        configTabButton.setEnabled(page != PAGE_CONFIG);
        refreshUi();
    }

    private void toggleExposure() {
        SensorServerService service = SensorServerService.getInstance();
        if (service != null && service.isServerRunning()) {
            stopExposure();
        } else {
            startExposure();
        }
    }

    private void startExposure() {
        selectedConfig = readConfigFromControls();
        requestPermissionsForConfig(selectedConfig);

        Intent intent = new Intent(this, SensorServerService.class);
        intent.setAction(SensorServerService.ACTION_START);
        intent.putExtra(SensorServerService.EXTRA_CONFIG_JSON, selectedConfig.toRequestJsonString());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        refreshUi();
    }

    private void stopExposure() {
        stopService(new Intent(this, SensorServerService.class));
        refreshUi();
    }

    private void applySelection() {
        selectedConfig = readConfigFromControls();
        requestPermissionsForConfig(selectedConfig);

        SensorServerService service = SensorServerService.getInstance();
        if (service != null && service.isServerRunning()) {
            service.applyConfig(selectedConfig);
        }
        refreshUi();
    }

    private StreamConfig readConfigFromControls() {
        StreamConfig config = new StreamConfig();
        config.includeAllAvailable = includeAllCheckBox != null && includeAllCheckBox.isChecked();

        for (SourceControl control : controls.values()) {
            if (!control.checkBox.isChecked()) {
                continue;
            }
            config.ratesHz.put(control.info.key, parseRate(control.rateEdit.getText().toString(), control.info.defaultRateHz));
        }
        return config;
    }

    private void requestPermissionsForConfig(StreamConfig config) {
        List<String> missing = new ArrayList<>();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            addIfMissing(missing, Manifest.permission.POST_NOTIFICATIONS);
        }
        if (config.rateForKey("gps") > 0) {
            addIfMissing(missing, Manifest.permission.ACCESS_FINE_LOCATION);
            addIfMissing(missing, Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        if (config.rateForKey("mobile_signal") > 0) {
            addIfMissing(missing, Manifest.permission.READ_PHONE_STATE);
        }
        if (usesBodySensor(config)) {
            addIfMissing(missing, Manifest.permission.BODY_SENSORS);
        }

        if (!missing.isEmpty()) {
            requestPermissions(missing.toArray(new String[0]), PERMISSION_REQUEST);
        }
    }

    private boolean usesBodySensor(StreamConfig config) {
        return config.rateForKey("heart_rate") > 0 || config.rateForKey("heart_beat") > 0;
    }

    private void addIfMissing(List<String> missing, String permission) {
        if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            missing.add(permission);
        }
    }

    private void refreshUi() {
        SensorServerService service = SensorServerService.getInstance();
        boolean running = service != null && service.isServerRunning();
        startStopButton.setText(running ? "Stop sensor exposure" : "Start sensor exposure");

        statusText.setText(
                "Exposure: " + (running ? "running" : "stopped") + "\n" +
                        "Port: " + currentServerPort() + "\n" +
                        "Clients: " + (service == null ? 0 : service.getClientCount()) + "\n" +
                        "Wake lock: " + (service != null && service.isWakeLockHeld())
        );

        endpointText.setText(endpointText());
        permissionsText.setText(formatPermissions(readConfigFromControls()));
        if (allSensorsButton != null) {
            allSensorsButton.setText(areAllSensorsSelected() ? "Clear sensor selection" : "Select every sensor");
        }

        if (currentPage == PAGE_LIVE) {
            liveText.setText(buildLiveText(service));
        } else if (currentPage == PAGE_OUTPUT) {
            outputText.setText(buildOutputText(service));
        }
    }

    private String buildLiveText(SensorServerService service) {
        StringBuilder builder = new StringBuilder();
        builder.append("Live sensor data\n");
        builder.append("This shows the latest sample produced for each source.\n");
        builder.append("Clients connected: ").append(service == null ? 0 : service.getClientCount()).append("\n\n");

        if (service == null || !service.isServerRunning()) {
            builder.append("Exposure is stopped. Tap Start sensor exposure.");
            return builder.toString();
        }

        Map<String, String> latest = service.getLatestSamplesSnapshot();
        Map<String, Long> produced = service.getProducedCountsSnapshot();
        Map<String, Long> sent = service.getCountsSnapshot();
        Map<String, Double> rates = service.getApproxRatesSnapshot();
        Map<String, Long> lastTimes = service.getLastSampleTimesSnapshot();

        if (latest.isEmpty()) {
            builder.append("Waiting for samples.\n");
            builder.append("If this stays empty, check enabled sensors and permissions.");
            return builder.toString();
        }

        for (String source : latest.keySet()) {
            builder.append(source).append('\n');
            builder.append("rate: ").append(formatHz(rates.containsKey(source) ? rates.get(source) : 0.0));
            builder.append("  produced: ").append(produced.containsKey(source) ? produced.get(source) : 0);
            builder.append("  sent: ").append(sent.containsKey(source) ? sent.get(source) : 0);
            builder.append("  last: ").append(formatTime(lastTimes.get(source))).append('\n');
            builder.append(formatSample(latest.get(source))).append("\n\n");
        }

        return builder.toString().trim();
    }

    private String buildOutputText(SensorServerService service) {
        StringBuilder builder = new StringBuilder();
        builder.append("Output data\n");
        builder.append("This shows the latest samples sent to connected clients.\n");
        builder.append("Clients connected: ").append(service == null ? 0 : service.getClientCount()).append("\n\n");

        if (service == null || !service.isServerRunning()) {
            builder.append("Exposure is stopped. Tap Start sensor exposure.");
            return builder.toString();
        }

        if (service.getClientCount() == 0) {
            int port = currentServerPort();
            builder.append("No receiving app is connected.\n");
            builder.append("Run: adb forward tcp:").append(port).append(" tcp:").append(port).append("\n");
            builder.append("Then run receiver (client) program.");
            return builder.toString();
        }

        Map<String, String> latest = service.getLatestSentSamplesSnapshot();
        Map<String, Long> sent = service.getCountsSnapshot();
        Map<String, Long> lastTimes = service.getLastSentSampleTimesSnapshot();

        if (latest.isEmpty()) {
            builder.append("Client is connected, but no samples have been sent yet.\n");
            builder.append("Check enabled sensors and permissions.");
            return builder.toString();
        }

        for (String source : latest.keySet()) {
            builder.append(source).append('\n');
            builder.append("sent: ").append(sent.containsKey(source) ? sent.get(source) : 0);
            builder.append("  last_sent: ").append(formatTime(lastTimes.get(source))).append('\n');
            builder.append(formatSample(latest.get(source))).append("\n\n");
        }

        return builder.toString().trim();
    }

    private String formatPermissions(StreamConfig config) {
        List<String> missing = new ArrayList<>();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            missing.add("notifications");
        }
        if (config.rateForKey("gps") > 0 && !hasLocationPermission()) {
            missing.add("location");
        }
        if (config.rateForKey("mobile_signal") > 0 && checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            missing.add("phone state");
        }
        if (usesBodySensor(config) && checkSelfPermission(Manifest.permission.BODY_SENSORS) != PackageManager.PERMISSION_GRANTED) {
            missing.add("body sensors");
        }

        if (missing.isEmpty()) {
            return "Permissions for enabled sources: granted";
        }
        return "Missing permissions for enabled sources: " + String.join(", ", missing);
    }

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private int suggestedRate(Sensor sensor) {
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

    private int rateForUi(SourceInfo source) {
        int configured = selectedConfig.rateForKey(source.key);
        return configured > 0 ? configured : source.defaultRateHz;
    }

    private int parseRate(String value, int fallback) {
        try {
            return Math.max(1, Integer.parseInt(value.trim()));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private String formatSample(String jsonLine) {
        try {
            JSONObject sample = new JSONObject(jsonLine);
            String source = sample.optString("source", "");

            if ("gps".equals(source)) {
                return formatGpsSample(sample);
            }
            if ("mobile_signal".equals(source)) {
                return formatMobileSignalSample(sample);
            }

            JSONArray values = sample.optJSONArray("values");
            if (values != null) {
                StringBuilder builder = new StringBuilder();
                builder.append("values: ");
                for (int i = 0; i < values.length(); i++) {
                    if (i > 0) {
                        builder.append(", ");
                    }
                    builder.append(formatNumber(values.optDouble(i)));
                }
                String unit = sample.optString("unit", "");
                if (!unit.isEmpty()) {
                    builder.append(' ').append(unit);
                }
                builder.append('\n');
                builder.append("accuracy: ").append(sample.optInt("accuracy", -1));
                builder.append("  timestamp_ns: ").append(sample.optLong("timestamp_ns", 0));
                return builder.toString();
            }

            return jsonLine;
        } catch (Exception ignored) {
            return jsonLine;
        }
    }

    private String formatGpsSample(JSONObject sample) {
        StringBuilder builder = new StringBuilder();
        builder.append("lat: ").append(formatNumber(sample.optDouble("lat")));
        builder.append("  lon: ").append(formatNumber(sample.optDouble("lon")));
        if (sample.has("altitude_m")) {
            builder.append("\naltitude: ").append(formatNumber(sample.optDouble("altitude_m"))).append(" m");
        }
        if (sample.has("speed_mps")) {
            builder.append("  speed: ").append(formatNumber(sample.optDouble("speed_mps"))).append(" m/s");
        }
        if (sample.has("bearing_deg")) {
            builder.append("  bearing: ").append(formatNumber(sample.optDouble("bearing_deg"))).append(" deg");
        }
        if (sample.has("accuracy_m")) {
            builder.append("\naccuracy: ").append(formatNumber(sample.optDouble("accuracy_m"))).append(" m");
        }
        builder.append("  provider: ").append(sample.optString("provider", "unknown"));
        return builder.toString();
    }

    private String formatMobileSignalSample(JSONObject sample) {
        StringBuilder builder = new StringBuilder();
        builder.append("network: ").append(sample.optString("network_type", "UNKNOWN"));
        builder.append("  dbm: ").append(sample.optInt("dbm", 0));
        builder.append("  level: ").append(sample.optInt("level", 0));
        return builder.toString();
    }

    private String formatHz(double hz) {
        return String.format(Locale.US, "%.1f Hz", hz);
    }

    private String formatNumber(double value) {
        return String.format(Locale.US, "%.4g", value);
    }

    private String formatTime(Long timestampMs) {
        if (timestampMs == null || timestampMs <= 0) {
            return "never";
        }
        return new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(timestampMs));
    }

    private TextView sectionText() {
        TextView textView = new TextView(this);
        textView.setTextSize(15);
        textView.setPadding(0, 0, 0, dp(12));
        return textView;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }

    private LinearLayout.LayoutParams weightWrap() {
        return new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
        );
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private String valueOrNone(String value) {
        return value == null || value.isEmpty() ? "none" : value;
    }

    private void loadConfig() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String serverIp = prefs.getString(KEY_SERVER_IP, DEFAULT_SERVER_IP);
        int serverPort = prefs.getInt(KEY_SERVER_PORT, DEFAULT_SERVER_PORT);

        serverIpInput.setText(serverIp);
        serverPortInput.setText(String.valueOf(serverPort));
        showCurrentConfig(serverIp, serverPort);
    }

    private void saveConfig() {
        String serverIp = serverIpInput.getText().toString().trim();
        String serverPortText = serverPortInput.getText().toString().trim();

        if (TextUtils.isEmpty(serverIp)) {
            serverIpInput.setError(getString(R.string.server_ip_required));
            return;
        }

        if (TextUtils.isEmpty(serverPortText)) {
            serverPortInput.setError(getString(R.string.server_port_required));
            return;
        }

        int serverPort;
        try {
            serverPort = Integer.parseInt(serverPortText);
        } catch (NumberFormatException e) {
            serverPortInput.setError(getString(R.string.server_port_invalid));
            return;
        }

        if (serverPort < 1 || serverPort > 65535) {
            serverPortInput.setError(getString(R.string.server_port_invalid));
            return;
        }

        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putString(KEY_SERVER_IP, serverIp)
                .putInt(KEY_SERVER_PORT, serverPort)
                .apply();

        showCurrentConfig(serverIp, serverPort);
        SensorServerService service = SensorServerService.getInstance();
        if (service != null && service.isServerRunning()) {
            service.reapplyConfig();
        }
        refreshUi();
        Toast.makeText(this, R.string.config_saved, Toast.LENGTH_SHORT).show();
    }

    private void showCurrentConfig(String serverIp, int serverPort) {
        currentConfigText.setText(getString(R.string.current_config, serverIp, serverPort));
    }

    private int currentServerPort() {
        SensorServerService service = SensorServerService.getInstance();
        if (service != null && service.isServerRunning()) {
            return service.getServerPort();
        }
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getInt(KEY_SERVER_PORT, DEFAULT_SERVER_PORT);
    }

    private String endpointText() {
        int port = currentServerPort();
        return "Endpoint: http://127.0.0.1:" + port + "\nUse: adb forward tcp:" + port + " tcp:" + port;
    }

    private static class SourceInfo {
        final String key;
        final String label;
        final String detail;
        final int defaultRateHz;

        SourceInfo(String key, String label, String detail, int defaultRateHz) {
            this.key = key;
            this.label = label;
            this.detail = detail;
            this.defaultRateHz = defaultRateHz;
        }
    }

    private static class SourceControl {
        final SourceInfo info;
        final CheckBox checkBox;
        final EditText rateEdit;

        SourceControl(SourceInfo info, CheckBox checkBox, EditText rateEdit) {
            this.info = info;
            this.checkBox = checkBox;
            this.rateEdit = rateEdit;
        }
    }
}
