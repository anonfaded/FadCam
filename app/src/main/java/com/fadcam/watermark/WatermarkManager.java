package com.fadcam.watermark;

import android.content.Context;
import android.location.Location;

import com.fadcam.FLog;
import com.fadcam.SharedPreferencesManager;
import com.fadcam.opengl.WatermarkInfoProvider;
import com.fadcam.sensors.SensorDataProvider;
import com.fadcam.audio.NoiseMonitor;
import com.fadcam.network.WeatherService;
import com.fadcam.services.LocationGeocoder;
import com.fadcam.ui.LocationHelper;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Shared watermark text builder and sensor lifecycle manager used by both
 * {@code RecordingService} and {@code DualCameraRecordingService}.
 * <p>
 * Implements {@link WatermarkInfoProvider} so it can be passed directly to
 * {@code GLRecordingPipeline} as the watermark source.
 */
public class WatermarkManager implements WatermarkInfoProvider {

    private static final String TAG = "WatermarkManager";

    private final Context context;
    private final SharedPreferencesManager prefs;

    // ── Sensor / data providers ──────────────────────────────────────
    private LocationHelper locationHelper;
    private LocationGeocoder locationGeocoder;
    private SensorDataProvider sensorDataProvider;
    private NoiseMonitor noiseMonitor;
    private WeatherService weatherService;

    // ── Cached location state (throttled GPS check + location text) ──
    private long lastLocationWatermarkUpdateMs;
    private String cachedLocationWatermarkText = "";
    private boolean cachedGpsProviderEnabled;
    private long lastGpsProviderCheckMs;
    private static final long GPS_PROVIDER_CHECK_INTERVAL_MS = 5000;

    // ── Debug ──────────────────────────────────────────────────────────
    private int watermarkUpdateSequence;

    // ── Construction ───────────────────────────────────────────────────

    public WatermarkManager(Context context, SharedPreferencesManager prefs) {
        this.context = context.getApplicationContext();
        this.prefs = prefs;
    }

    // ── Sensor lifecycle ──────────────────────────────────────────────

    /** Initialise all sensor / location providers needed for watermark text. */
    public void initialize(LocationHelper existingLocationHelper) {
        locationHelper = existingLocationHelper;

        // If the master watermark is disabled, don't start any providers.
        String option = prefs.getWatermarkOption();
        if ("no_watermark".equals(option)) return;

        if (locationHelper == null) {
            locationHelper = new LocationHelper(context);
            locationHelper.startLocationUpdates();
        }

        if (prefs.isSpeedEnabled() || prefs.isAltitudeEnabled()
                || prefs.isCompassEnabled() || prefs.isAccuracyEnabled()) {
            sensorDataProvider = SensorDataProvider.getInstance(context);
            org.osmdroid.util.GeoPoint gp = locationHelper.getCurrentLocation();
            Location androidLoc = null;
            if (gp != null) {
                androidLoc = new Location("manual");
                androidLoc.setLatitude(gp.getLatitude());
                androidLoc.setLongitude(gp.getLongitude());
            }
            sensorDataProvider.start(androidLoc);
        }

        if (prefs.isNoiseEnabled()) {
            noiseMonitor = NoiseMonitor.getInstance();
            noiseMonitor.start(context);
        }

        if (prefs.isWeatherEnabled()) {
            weatherService = WeatherService.getInstance(context);
        }

        locationGeocoder = new LocationGeocoder();
        FLog.d(TAG, "Watermark providers initialised");
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
        cachedGpsProviderEnabled = false;
        cachedLocationWatermarkText = "";
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
            locationHelper = existingLocationHelper;
        }
        if (prefs.isSpeedEnabled() || prefs.isAltitudeEnabled()
                || prefs.isCompassEnabled() || prefs.isAccuracyEnabled()) {
            if (sensorDataProvider == null) {
                sensorDataProvider = SensorDataProvider.getInstance(context);
            }
            org.osmdroid.util.GeoPoint gp = locationHelper != null ? locationHelper.getCurrentLocation() : null;
            Location androidLoc = null;
            if (gp != null) {
                androidLoc = new Location("manual");
                androidLoc.setLatitude(gp.getLatitude());
                androidLoc.setLongitude(gp.getLongitude());
            }
            sensorDataProvider.start(androidLoc);
        }
        if (prefs.isNoiseEnabled()) {
            if (noiseMonitor == null) noiseMonitor = NoiseMonitor.getInstance();
            noiseMonitor.start(context);
        }
    }

    // ── WatermarkInfoProvider ─────────────────────────────────────────

    @Override
    public String getWatermarkText() {
        watermarkUpdateSequence++;
        String watermarkOption = prefs.getWatermarkOption();
        String locationText = prefs.isLocalisationEnabled() ? getLocationData() : "";
        String customText = prefs.getWatermarkCustomText();
        String customTextLine = (customText != null && !customText.isEmpty()) ? "\n" + customText : "";

        String finalText;
        switch (watermarkOption) {
            case "timestamp_fadcam":
                finalText = "Captured by <FADCAM_ICON> - " + getCurrentTimestamp() + getTimezoneSuffix()
                        + locationText + customTextLine;
                break;
            case "badge_fadcam":
                finalText = "Captured by <FADCAM_ICON>" + customTextLine;
                break;
            case "timestamp":
                finalText = getCurrentTimestamp() + getTimezoneSuffix() + locationText + customTextLine;
                break;
            case "no_watermark":
                finalText = "";
                break;
            default:
                finalText = "Captured by FadCam - " + getCurrentTimestamp() + getTimezoneSuffix()
                        + locationText + customTextLine;
        }

        finalText += getExtendedSensorData();

        if (watermarkUpdateSequence == 1) {
            FLog.d(TAG, "Watermark text: [" + finalText.replace("\n", " | ") + "]");
        }
        return finalText;
    }

    // ── Timestamp helpers ─────────────────────────────────────────────

    private String getCurrentTimestamp() {
        SimpleDateFormat sdf = new SimpleDateFormat("dd/MMM/yyyy hh:mm:ss a", Locale.ENGLISH);
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

    private String getLocationData() {
        if (locationHelper == null) return "";

        long now = System.currentTimeMillis();
        long intervalMs = prefs.getWatermarkUpdateInterval();
        long elapsed = now - lastLocationWatermarkUpdateMs;

        if (elapsed >= intervalMs) {
            String locData = locationHelper.getLocationData();
            if (locData == null || !locData.contains("Lat:")) {
                cachedLocationWatermarkText = "";
            } else {
                String format = prefs.getWatermarkLocationFormat();
                if ("address".equals(format) && locationGeocoder != null) {
                    org.osmdroid.util.GeoPoint geoPoint = locationHelper.getCurrentLocation();
                    if (geoPoint != null) {
                        locationGeocoder.geocodeAsync(geoPoint.getLatitude(), geoPoint.getLongitude(),
                                result -> { /* async — result used next cycle */ });
                        LocationGeocoder.GeocodeResult result = locationGeocoder.getCurrentResult();
                        if (!result.isEmpty()) {
                            cachedLocationWatermarkText = "\nLat: " + geoPoint.getLatitude()
                                    + ", Long: " + geoPoint.getLongitude() + "\n" + result.formatted;
                        } else {
                            cachedLocationWatermarkText = locData;
                        }
                    } else {
                        cachedLocationWatermarkText = locData;
                    }
                } else {
                    cachedLocationWatermarkText = locData;
                }
            }

            if (prefs.isUtmEnabled() && locationHelper != null) {
                org.osmdroid.util.GeoPoint utmPt = locationHelper.getCurrentLocation();
                if (utmPt != null) {
                    String utm = com.fadcam.utils.UTMConverter.latLonToUTM(
                            utmPt.getLatitude(), utmPt.getLongitude());
                    if (utm != null && !utm.isEmpty()) {
                        cachedLocationWatermarkText += "\n" + utm;
                    }
                }
            }

            lastLocationWatermarkUpdateMs = now;
        }

        if (!cachedGpsProviderEnabled) {
            StringBuilder off = new StringBuilder("\nLocation: GPS is off");
            if (prefs.isUtmEnabled()) off.append("\nUTM: GPS is off");
            return off.toString();
        }
        if (cachedLocationWatermarkText.isEmpty()) {
            lastLocationWatermarkUpdateMs = 0; // force refresh next call
        }
        return cachedLocationWatermarkText;
    }

    // ── Extended sensor data ─────────────────────────────────────────

    private String getExtendedSensorData() {
        if (prefs == null) return "";

        // Throttled GPS provider check
        long now = System.currentTimeMillis();
        if (now - lastGpsProviderCheckMs >= GPS_PROVIDER_CHECK_INTERVAL_MS) {
            android.location.LocationManager lm = (android.location.LocationManager)
                    context.getSystemService(Context.LOCATION_SERVICE);
            cachedGpsProviderEnabled = lm != null
                    && lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER);
            lastGpsProviderCheckMs = now;
        }

        // Feed fresh location into SensorDataProvider
        if (locationHelper != null && sensorDataProvider != null) {
            Location rawLoc = locationHelper.getRawLocation();
            if (rawLoc != null) {
                sensorDataProvider.updateLocation(rawLoc);
            }
        }

        StringBuilder sb = new StringBuilder();

        if (prefs.isSpeedEnabled() && sensorDataProvider != null) {
            if (!cachedGpsProviderEnabled) {
                sb.append("\nSpeed: GPS is off");
            } else {
                float speed = sensorDataProvider.getSpeedKmh();
                sb.append("\nSpeed: ").append(String.format("%.0f", speed)).append(" km/h");
            }
        }

        if (prefs.isAltitudeEnabled() && sensorDataProvider != null) {
            if (!cachedGpsProviderEnabled) {
                sb.append("\nAlt: GPS is off");
            } else {
                double alt = sensorDataProvider.getAltitude();
                sb.append("\nAlt: ").append(String.format("%.0f", alt)).append("m");
            }
        }

        if (prefs.isAccuracyEnabled() && sensorDataProvider != null) {
            if (!cachedGpsProviderEnabled) {
                sb.append("\nAccuracy: GPS is off");
            } else {
                float acc = sensorDataProvider.getAccuracy();
                sb.append("\nAccuracy: ").append(String.format("%.0f", acc)).append("m");
            }
        }

        if (prefs.isCompassEnabled() && sensorDataProvider != null) {
            sb.append("\nCompass: ").append(sensorDataProvider.getCompassDirection());
        }

        if (prefs.isNoiseEnabled() && noiseMonitor != null && noiseMonitor.isRunning()) {
            sb.append("\nNoise: ").append(noiseMonitor.getReadableDb());
        }

        if (prefs.isWeatherEnabled() && weatherService != null && locationHelper != null) {
            org.osmdroid.util.GeoPoint geoPoint = locationHelper.getCurrentLocation();
            if (geoPoint != null) {
                weatherService.fetchWeather(geoPoint, (weather, wind) -> {});
                String weather = weatherService.getCurrentWeather();
                String wind = weatherService.getCurrentWind();
                if (weather != null && !weather.isEmpty()) sb.append("\n").append(weather);
                if (wind != null && !wind.isEmpty()) sb.append("\nWind: ").append(wind);
            }
        }

        return sb.toString();
    }
}
