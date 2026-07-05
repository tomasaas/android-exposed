# Android Exposed

Minimal Android app that exposes phone sensor data through a local HTTP server on port `8765`.

## Build and run

Open the project in Android Studio and run the `app` configuration on a physical phone.

Or from PowerShell:

```powershell
.\run.ps1
```

The app opens stopped. Choose the sensor sources and Hz values you want, then tap `Start sensor exposure`.

When exposure is running, the app uses a foreground service with a persistent notification. This keeps the local server alive when the app is not visible or the phone screen is off.

## Phone controls

Main screen:

* `Start sensor exposure` / `Stop sensor exposure` turns the local server and sensor streaming on or off.
* `Stream all available Android sensors` enables every detected Android sensor that supports normal listener callbacks.
* Each detected source has a checkbox and Hz field.
* `Apply selection` updates the running server without reinstalling or restarting the app.
* `Request permissions for enabled sources` asks only for permissions needed by the currently enabled sources.

Live screen:

* Shows the latest sample produced for each active source.
* Shows produced count, sent count, approximate Hz, and last sample time.
* Useful for checking that the phone itself is producing data.

Output screen:

* Shows the latest sample sent to a connected receiving app.
* Shows sent count and last sent time per source.
* Shows a clear message when no receiving app is connected.

## Connect over USB

```bash
adb devices
adb forward tcp:8765 tcp:8765
curl http://127.0.0.1:8765/health
curl http://127.0.0.1:8765/sensors
python tools/read_stream.py
python tools/live_output.py
```

Tap `Start sensor exposure` on the phone before running the `curl` or Python commands.

## Endpoints

```text
GET  /health
GET  /sensors
GET  /config
POST /config
GET  /stream
```

`/stream` returns newline-delimited JSON. Android sensors use `timestamp_ns`. GPS and mobile signal use `timestamp_ms`.

Example config:

```bash
curl -X POST http://127.0.0.1:8765/config \
  -H "Content-Type: application/json" \
  -d "{\"sensors\":{\"accelerometer\":200,\"gyroscope\":200,\"rotation_vector\":100,\"magnetometer\":50,\"gps\":1,\"mobile_signal\":1},\"include_all_available\":false}"
```

Use `include_all_available: true` to stream every detected Android sensor that supports normal listener callbacks.

## Python stream reader

```bash
python tools/read_stream.py
python tools/read_stream.py --output samples.jsonl
python tools/read_stream.py --limit 100
```

## Live output dashboard

```bash
python tools/live_output.py
python tools/live_output.py --output samples.jsonl
```

The dashboard shows every live `/stream` source with counts, estimated Hz, and latest values.

## Notes

Android requires a foreground-service notification for reliable background operation. This app starts with the `connectedDevice` foreground-service type because the phone is serving sensor data to an external computer over ADB/TCP. If location permission is granted, it also enables the location foreground-service type. The app holds a partial wake lock while the service is running so the CPU can keep processing sensor/server work with the screen off.

GPS needs location permission and an enabled location provider.

Mobile signal uses Android's public `TelephonyManager.getSignalStrength()` API on Android 9+ and needs `READ_PHONE_STATE`. Some devices or carriers may still return no signal details.
