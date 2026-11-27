package com.fadcam.watermark;

import android.content.Context;
import android.location.Location;

import com.fadcam.FLog;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.opengl.WatermarkInfoProvider;
import com.fadcam.sensors.SensorDataProvider;
import com.fadcam.audio.NoiseMonitor;
import com.fadcam.network.WeatherService;
import com.fadcam.services.LocationFeedGate;
import com.fadcam.services.LocationGeocoder;
import com.fadcam.ui.LocationHelper;

import org.osmdroid.util.GeoPoint;

import java.util.Locale;

/**
 * Watermark text builder and sensor lifecycle manager for one recording mode.
 *
 * <p>Camera recording and screen recording each own a {@link WatermarkPrefs} set, so the style
 * and the enabled lines are independent: changing the watermark for screen recording no longer
 * rewrites what camera clips get stamped with. Both modes still emit a line for every enabled
 * feature — a live value, {@code "--"} while waiting for a fix, or {@code "GPS is off"} — so a
 * line never silently disappears while the GPS provider is switching on.</p>
 *
 * <p>Implements {@link WatermarkInfoProvider} so it can be passed directly to the GL
 * pipelines.</p>
 */
public class WatermarkManager implements WatermarkInfoProvider {

    private static final String TAG = "WatermarkManager";

    /** Shown while the GPS provider is enabled but no usable fix has arrived yet. */
    private static final String LOCATING = "Locating...";
    private static final String GPS_OFF = "GPS is off";

    /** Re-check provider state often enough that enabling GPS updates the overlay immediately. */
    private static final long GPS_PROVIDER_CHECK_INTERVAL_MS = 1000;

    private final Context context;
    private final SharedPreferencesManager prefs;

    /** Camera modes say "Captured by"; screen recording says "Recorded by". */
    public enum Mode { CAMERA, SCREEN_RECORDING }

    private final Mode mode;

    /**
     * Stored watermark settings for this mode. Camera and screen recording each keep their
     * own set, so a style or sensor line chosen for one does not appear in the other.
     */
    private final WatermarkPrefs settings;

    // ── Sensor / data providers ──────────────────────────────────────
    private LocationHelper locationHelper;
    private LocationGeocoder locationGeocoder;
    private SensorDataProvider sensorDataProvider;
    private NoiseMonitor noiseMonitor;
    private WeatherService weatherService;
    private boolean initialized;

    // ── Cached location state (throttled GPS check + location text) ──
    private long lastLocationWatermarkUpdateMs;
    private String cachedLocationWatermarkText = "";
    private boolean cachedGpsProviderEnabled;
    private long lastGpsProviderCheckMs;

    // ── Debug ──────────────────────────────────────────────────────────
    private int watermarkUpdateSequence;

    // ── Construction ───────────────────────────────────────────────────

    public WatermarkManager(Context context, SharedPreferencesManager prefs) {
        this(context, prefs, Mode.CAMERA);
    }

    public WatermarkManager(Context context, SharedPreferencesManager prefs, Mode mode) {
        this.context = context.getApplicationContext();
        this.prefs = prefs;
        this.mode = mode == null ? Mode.CAMERA : mode;
        this.settings = this.mode == Mode.SCREEN_RECORDING
                ? WatermarkPrefs.forScreenRecording(prefs)
                : WatermarkPrefs.forCamera(prefs);
    }

    // ── Sensor lifecycle ──────────────────────────────────────────────

    /**
     * Initialise the providers needed by the current settings. Safe to call repeatedly: a
     * later call only adopts a newer {@link LocationHelper} (the recording service recreates
     * one when permissions change) without leaking or restarting the others.
     */
    public void initialize(LocationHelper existingLocationHelper) {
        if (!initialized) {
            initialized = true;
            locationHelper = existingLocationHelper;
            if (locationGeocoder == null) {
                locationGeocoder = new LocationGeocoder();
            }
        } else if (existingLocationHelper != null && existingLocationHelper != locationHelper) {
            updateLocationHelper(existingLocationHelper);
        }

        refreshGpsProviderState();
        if (!"no_watermark".equals(settings.getWatermarkOption())) {
            ensureProviders();
        }
        FLog.d(TAG, "Watermark providers initialised");
    }

    /**
     * Points the manager at a new helper (or {@code null} when every location feature was
     * turned off). Stops the previous helper so only one GPS listener stays registered.
     */
    public void updateLocationHelper(LocationHelper helper) {
        if (helper == locationHelper) return;
        if (locationHelper != null) {
            locationHelper.stopLocationUpdates();
        }
        locationHelper = helper;
        cachedLocationWatermarkText = "";
        lastLocationWatermarkUpdateMs = 0;
    }

    /** Tear down all providers. Safe to call multiple times. */
    public void destroy() {
        if (locationHelper != null) {
            locationHelper.stopLocationUpdates();
            locationHelper = null;
        }
        if (sensorDataProvider != null) {
            sensorDataProvider.stop();
            sensorDataProvider = null;
        }
        if (noiseMonitor != null) {
            noiseMonitor.stop();
            noiseMonitor = null;
        }
        if (locationGeocoder != null) {
            locationGeocoder.shutdown();
            locationGeocoder = null;
        }
        weatherService = null;
        initialized = false;
        cachedGpsProviderEnabled = false;
        cachedLocationWatermarkText = "";
        lastLocationWatermarkUpdateMs = 0;
        lastGpsProviderCheckMs = 0;
        FLog.d(TAG, "Watermark providers destroyed");
    }

    /** Pause sensors (called when recording is paused). */
    public void pauseSensors() {
        if (noiseMonitor != null) noiseMonitor.stop();
        if (sensorDataProvider != null) sensorDataProvider.stop();
    }

    /** Resume sensors (called when recording is resumed). */
    public void resumeSensors(LocationHelper existingLocationHelper) {
        if (existingLocationHelper != null) {
            updateLocationHelper(existingLocationHelper);
        }
        if (!"no_watermark".equals(settings.getWatermarkOption())) {
            ensureProviders();
        }
    }

    // ── WatermarkInfoProvider ─────────────────────────────────────────

    @Override
    public String getWatermarkText() {
        watermarkUpdateSequence++;
        String watermarkOption = settings.getWatermarkOption();
        if ("no_watermark".equals(watermarkOption)) {
            return "";
        }

        // Settings can change mid-recording (the sheet is reachable while recording), so make
        // sure a provider exists for whatever is enabled right now.
        ensureProviders();
        refreshGpsProviderState();

        String locationText = settings.isLocationEnabled() ? getLocationData() : "";
        String utmText = settings.isUtmEnabled() ? getUtmData() : "";
        String customText = settings.getCustomText();
        String customTextLine = (customText != null && !customText.isEmpty()) ? "\n" + customText : "";

        String baseText;
        switch (watermarkOption) {
            case "timestamp_fadcam":
                baseText = (mode == Mode.SCREEN_RECORDING ? "Recorded by <ICON>" : "Captured by <FADCAM_ICON>")
                        + " - " + getCurrentTimestamp() + getTimezoneSuffix();
                break;
            case "badge_fadcam":
                // The preview promises location below the badge too; keep the recording in sync.
                baseText = mode == Mode.SCREEN_RECORDING
                        ? "Recorded by <ICON>" : "Captured by <FADCAM_ICON>";
                break;
            case "timestamp":
                baseText = getCurrentTimestamp() + getTimezoneSuffix();
                break;
            default:
                baseText = (mode == Mode.SCREEN_RECORDING ? "Recorded by FadRec" : "Captured by FadCam")
                        + " - " + getCurrentTimestamp() + getTimezoneSuffix();
                break;
        }

        // Same order as the settings preview: location, custom text, UTM, extended sensors.
        String finalText = baseText + locationText + customTextLine + utmText
                + getExtendedSensorData();

        if (watermarkUpdateSequence == 1) {
            FLog.d(TAG, "Watermark text: [" + finalText.replace("\n", " | ") + "]");
        }
        return finalText;
    }

    // ── Provider bootstrap ────────────────────────────────────────────

    private void ensureProviders() {
        if (prefs == null) return;

        if (locationGeocoder == null) {
            locationGeocoder = new LocationGeocoder();
        }
        if (settings.needsLocationData()) {
            ensureLocationHelper();
        }
        if (settings.isSpeedEnabled() || settings.isAltitudeEnabled()
                || settings.isCompassEnabled() || settings.isAccuracyEnabled()) {
            ensureSensorProvider();
        }
        if (settings.isNoiseEnabled()) {
            ensureNoiseMonitor();
        }
        if (settings.isWeatherEnabled()) {
            ensureWeatherService();
        }
    }

    private void ensureLocationHelper() {
        if (locationHelper != null) return;
        locationHelper = new LocationHelper(context);
        locationHelper.startLocationUpdates();
    }

    private void ensureSensorProvider() {
        if (sensorDataProvider != null) return;
        sensorDataProvider = SensorDataProvider.getInstance(context);
        Location seed = locationHelper != null ? locationHelper.getRawLocation() : null;
        sensorDataProvider.start(seed);
    }

    private void ensureNoiseMonitor() {
        if (noiseMonitor != null) return;
        noiseMonitor = NoiseMonitor.getInstance();
        noiseMonitor.start(context);
    }

    private void ensureWeatherService() {
        if (weatherService != null) return;
        weatherService = WeatherService.getInstance(context);
    }

    private boolean hasLocationPermission() {
        return context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                == android.content.pm.PackageManager.PERMISSION_GRANTED
                || context.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    // ── Timestamp helpers ─────────────────────────────────────────────

    private String getCurrentTimestamp() {
        return WatermarkTimestamp.now(settings.isDayEnabled());
    }

    private String getTimezoneSuffix() {
        if (!settings.isTimezoneEnabled()) return "";
        java.util.TimeZone tz = java.util.TimeZone.getDefault();
        int offsetMs = tz.getOffset(System.currentTimeMillis());
        int totalMinutes = offsetMs / 60000;
        int hours = totalMinutes / 60;
        int minutes = Math.abs(totalMinutes % 60);
        String sign = offsetMs >= 0 ? "+" : "";
        String gmt;
        if (minutes == 0) {
            gmt = "GMT" + sign + hours;
        } else {
            gmt = "GMT" + sign + hours + ":" + String.format(Locale.US, "%02d", minutes);
        }
        if ("gmt_name".equals(settings.getTimezoneFormat())) {
            gmt += " (" + tz.getID() + ")";
        }
        return " " + gmt;
    }

    // ── Location data ─────────────────────────────────────────────────

    /**
     * Refreshes the cached GPS-provider flag and, on an off→on or on→off transition, clears
     * the cached text so the very next frame shows either the new value or {@code Locating...}
     * instead of briefly dropping the line.
     */
    private void refreshGpsProviderState() {
        long now = System.currentTimeMillis();
        if (now - lastGpsProviderCheckMs < GPS_PROVIDER_CHECK_INTERVAL_MS) return;
        lastGpsProviderCheckMs = now;

        android.location.LocationManager lm = (android.location.LocationManager)
                context.getSystemService(Context.LOCATION_SERVICE);
        boolean enabled = lm != null
                && lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER);
        if (enabled != cachedGpsProviderEnabled) {
            cachedGpsProviderEnabled = enabled;
            cachedLocationWatermarkText = "";
            lastLocationWatermarkUpdateMs = 0;
        } else {
            cachedGpsProviderEnabled = enabled;
        }
    }

    private String getLocationData() {
        if (!settings.isLocationEnabled()) return "";
        if (!hasLocationPermission()) return "\nLocation: Permission denied";
        if (locationHelper == null) {
            if (!settings.needsLocationData()) return "";
            ensureLocationHelper();
        }
        if (locationHelper == null) return "\nLocation: " + LOCATING;

        if (!cachedGpsProviderEnabled) {
            cachedLocationWatermarkText = "";
            return "\nLocation: " + GPS_OFF;
        }

        long now = System.currentTimeMillis();
        long intervalMs = settings.getUpdateInterval();
        boolean cacheEmpty = cachedLocationWatermarkText.isEmpty();
        if (cacheEmpty || now - lastLocationWatermarkUpdateMs >= intervalMs) {
            String locData = locationHelper.getLocationData();
            if (locData == null || !locData.contains("Lat:")) {
                cachedLocationWatermarkText = "";
            } else {
                String format = settings.getLocationFormat();
                if ("address".equals(format) && locationGeocoder != null) {
                    GeoPoint geoPoint = locationHelper.getCurrentLocation();
                    if (geoPoint != null) {
                        locationGeocoder.geocodeAsync(geoPoint.getLatitude(), geoPoint.getLongitude(),
                                result -> { /* async — result used next cycle */ });
                        LocationGeocoder.GeocodeResult result = locationGeocoder.getCurrentResult();
                        if (!result.isEmpty()) {
                            cachedLocationWatermarkText = "\nLat: " + geoPoint.getLatitude()
                                    + ", Long: " + geoPoint.getLongitude() + "\n" + result.formatted;
                        } else {
                            // Coordinates are usable immediately; the address replaces them
                            // as soon as the reverse-geocode result is cached.
                            cachedLocationWatermarkText = locData;
                        }
                    } else {
                        cachedLocationWatermarkText = locData;
                    }
                } else {
                    cachedLocationWatermarkText = locData;
                }
            }
            lastLocationWatermarkUpdateMs = now;
        }

        if (cachedLocationWatermarkText.isEmpty()) {
            // GPS is on but the first fix has not arrived yet. Keep the line present so the
            // watermark never goes blank while the receiver warms up.
            return "\nLocation: " + LOCATING;
        }
        return cachedLocationWatermarkText;
    }

    /** UTM is independent of the location-overlay toggle, matching the settings preview. */
    private String getUtmData() {
        if (!settings.isUtmEnabled()) return "";
        if (!hasLocationPermission()) return "\nUTM: Permission denied";
        if (!cachedGpsProviderEnabled) return "\nUTM: " + GPS_OFF;
        if (locationHelper == null) {
            if (!settings.needsLocationData()) return "\nUTM: " + LOCATING;
            ensureLocationHelper();
        }
        if (locationHelper == null) return "\nUTM: " + LOCATING;

        GeoPoint point = locationHelper.getCurrentLocation();
        if (point == null) return "\nUTM: " + LOCATING;
        String utm = com.fadcam.utils.UTMConverter.latLonToUTM(
                point.getLatitude(), point.getLongitude());
        if (utm == null || utm.isEmpty()) return "\nUTM: " + LOCATING;
        return "\n" + utm;
    }

    // ── Extended sensor data ─────────────────────────────────────────

    private String getExtendedSensorData() {
        if (prefs == null) return "";

        // Feed the newest real fix into the sensor provider. Without this the speed,
        // altitude and accuracy lines stay on "--" even though GPS has a fix.
        if (locationHelper != null && sensorDataProvider != null) {
            Location rawLoc = locationHelper.getRawLocation();
            if (rawLoc != null) {
                sensorDataProvider.updateLocation(rawLoc);
            }
        }

        boolean gpsOn = cachedGpsProviderEnabled;
        StringBuilder sb = new StringBuilder();

        if (settings.isSpeedEnabled()) {
            ensureSensorProvider();
            if (!gpsOn) {
                sb.append("\nSpeed: ").append(GPS_OFF);
            } else if (sensorDataProvider != null && sensorDataProvider.hasFreshFix()) {
                float speed = sensorDataProvider.getSpeedKmh();
                sb.append("\nSpeed: ").append(String.format(Locale.US, "%.0f", speed)).append(" km/h");
            } else {
                sb.append("\nSpeed: --");
            }
        }

        if (settings.isAltitudeEnabled()) {
            ensureSensorProvider();
            if (!gpsOn) {
                sb.append("\nAlt: ").append(GPS_OFF);
            } else if (sensorDataProvider != null && sensorDataProvider.hasFreshFix()) {
                double alt = sensorDataProvider.getAltitude();
                sb.append("\nAlt: ").append(String.format(Locale.US, "%.0f", alt)).append("m");
            } else {
                sb.append("\nAlt: --");
            }
        }

        if (settings.isAccuracyEnabled()) {
            ensureSensorProvider();
            if (!gpsOn) {
                sb.append("\nAccuracy: ").append(GPS_OFF);
            } else if (sensorDataProvider != null && sensorDataProvider.hasFreshFix()) {
                float acc = sensorDataProvider.getAccuracy();
                sb.append("\nAccuracy: ").append(String.format(Locale.US, "%.0f", acc)).append("m");
            } else {
                sb.append("\nAccuracy: --");
            }
        }

        if (settings.isCompassEnabled()) {
            ensureSensorProvider();
            String direction = sensorDataProvider != null
                    ? sensorDataProvider.getCompassDirection() : null;
            sb.append("\nCompass: ")
                    .append(direction == null || direction.isEmpty() ? "--" : direction);
        }

        if (settings.isNoiseEnabled()) {
            ensureNoiseMonitor();
            if (noiseMonitor != null && noiseMonitor.isRunning()) {
                sb.append("\nNoise: ").append(noiseMonitor.getReadableDb());
            } else {
                sb.append("\nNoise: --");
            }
        }

        if (settings.isWeatherEnabled()) {
            ensureWeatherService();
            if (!gpsOn) {
                sb.append("\nWeather: ").append(GPS_OFF);
                sb.append("\nWind: ").append(GPS_OFF);
            } else {
                GeoPoint geoPoint = locationHelper != null
                        ? locationHelper.getCurrentLocation() : null;
                String weather = null;
                String wind = null;
                if (geoPoint != null && weatherService != null) {
                    weatherService.fetchWeather(geoPoint, (w, windResult) -> {});
                    weather = weatherService.getCurrentWeather();
                    wind = weatherService.getCurrentWind();
                }
                sb.append("\n").append(weather != null && !weather.isEmpty()
                        ? weather : "Weather: --");
                sb.append("\nWind: ").append(wind != null && !wind.isEmpty() ? wind : "--");
            }
        }

        return sb.toString();
    }
}
