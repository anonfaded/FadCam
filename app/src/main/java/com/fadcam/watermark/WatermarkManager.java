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

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Shared watermark text builder and sensor lifecycle manager used by camera recording,
 * dual-camera recording and FadRec screen recording.
 *
 * <p>One settings set drives every recording mode, and every enabled feature always emits a
 * line: a live value, {@code "--"} while waiting for a fix, or {@code "GPS is off"}. A line
 * never silently disappears while the GPS provider is switching on, which is what made the
 * location/UTM rows look broken for a few seconds after enabling GPS.</p>
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
        if (!"no_watermark".equals(prefs.getWatermarkOption())) {
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
        if (!"no_watermark".equals(prefs.getWatermarkOption())) {
            ensureProviders();
        }
    }

    // ── WatermarkInfoProvider ─────────────────────────────────────────

    @Override
    public String getWatermarkText() {
        watermarkUpdateSequence++;
        String watermarkOption = prefs.getWatermarkOption();
        if ("no_watermark".equals(watermarkOption)) {
            return "";
        }

        // Settings can change mid-recording (the sheet is reachable while recording), so make
        // sure a provider exists for whatever is enabled right now.
        ensureProviders();
        refreshGpsProviderState();

        String locationText = prefs.isLocalisationEnabled() ? getLocationData() : "";
        String utmText = prefs.isUtmEnabled() ? getUtmData() : "";
        String customText = prefs.getWatermarkCustomText();
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
        if (LocationFeedGate.needsLocationData(prefs)) {
            ensureLocationHelper();
        }
        if (prefs.isSpeedEnabled() || prefs.isAltitudeEnabled()
                || prefs.isCompassEnabled() || prefs.isAccuracyEnabled()) {
            ensureSensorProvider();
        }
        if (prefs.isNoiseEnabled()) {
            ensureNoiseMonitor();
        }
        if (prefs.isWeatherEnabled()) {
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
        // Day name (e.g. "Wednesday, ") is included when the Day toggle is on.
        String pattern = (prefs.isWatermarkDayEnabled() ? "EEE, " : "")
                + "dd/MMM/yyyy hh:mm:ss a";
        SimpleDateFormat sdf = new SimpleDateFormat(pattern, Locale.ENGLISH);
        return convertArabicNumeralsToEnglish(sdf.format(new Date()));
    }

    private String getTimezoneSuffix() {
        if (!prefs.isTimezoneEnabled()) return "";
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
        if ("gmt_name".equals(prefs.getTimezoneFormat())) {
            gmt += " (" + tz.getID() + ")";
        }
        return " " + gmt;
    }

    private static String convertArabicNumeralsToEnglish(String text) {
        if (text == null) return null;
        return text.replaceAll("٠", "0").replaceAll("١", "1")
                .replaceAll("٢", "2").replaceAll("٣", "3")
                .replaceAll("٤", "4").replaceAll("٥", "5")
                .replaceAll("٦", "6").replaceAll("٧", "7")
                .replaceAll("٨", "8").replaceAll("٩", "9");
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
        if (!prefs.isLocalisationEnabled()) return "";
        if (!hasLocationPermission()) return "\nLocation: Permission denied";
        if (locationHelper == null) {
            if (!LocationFeedGate.needsLocationData(prefs)) return "";
            ensureLocationHelper();
        }
        if (locationHelper == null) return "\nLocation: " + LOCATING;

        if (!cachedGpsProviderEnabled) {
            cachedLocationWatermarkText = "";
            return "\nLocation: " + GPS_OFF;
        }

        long now = System.currentTimeMillis();
        long intervalMs = prefs.getWatermarkUpdateInterval();
        boolean cacheEmpty = cachedLocationWatermarkText.isEmpty();
        if (cacheEmpty || now - lastLocationWatermarkUpdateMs >= intervalMs) {
            String locData = locationHelper.getLocationData();
            if (locData == null || !locData.contains("Lat:")) {
                cachedLocationWatermarkText = "";
            } else {
                String format = prefs.getWatermarkLocationFormat();
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
        if (!prefs.isUtmEnabled()) return "";
        if (!hasLocationPermission()) return "\nUTM: Permission denied";
        if (!cachedGpsProviderEnabled) return "\nUTM: " + GPS_OFF;
        if (locationHelper == null) {
            if (!LocationFeedGate.needsLocationData(prefs)) return "\nUTM: " + LOCATING;
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

        if (prefs.isSpeedEnabled()) {
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

        if (prefs.isAltitudeEnabled()) {
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

        if (prefs.isAccuracyEnabled()) {
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

        if (prefs.isCompassEnabled()) {
            ensureSensorProvider();
            String direction = sensorDataProvider != null
                    ? sensorDataProvider.getCompassDirection() : null;
            sb.append("\nCompass: ")
                    .append(direction == null || direction.isEmpty() ? "--" : direction);
        }

        if (prefs.isNoiseEnabled()) {
            ensureNoiseMonitor();
            if (noiseMonitor != null && noiseMonitor.isRunning()) {
                sb.append("\nNoise: ").append(noiseMonitor.getReadableDb());
            } else {
                sb.append("\nNoise: --");
            }
        }

        if (prefs.isWeatherEnabled()) {
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
