package io.github.tvhome.universal;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import javax.net.ssl.HttpsURLConnection;

/**
 * Weather state for the live wallpaper.
 * Data source: Open-Meteo (free for non-commercial use, no API key, attribution required).
 * The city is chosen manually in settings, so no location permission is requested for weather.
 * The last successful response is cached, so the wallpaper keeps working offline.
 */
final class WeatherStore {
    static final String PREFS = "weather_wallpaper";
    static final String ATTRIBUTION = "Weather data by Open-Meteo.com";
    /** Open-Meteo limits for the free plan: 10,000 calls/day, 5,000/hour. One TV refresh every 15+ minutes. */
    static final int MIN_INTERVAL_MINUTES = 15;
    static final int DEFAULT_INTERVAL_MINUTES = 30;
    static final int MAX_INTERVAL_MINUTES = 240;
    private static final long RETRY_MS = 10 * 60 * 1000L;

    private static final Handler HANDLER = new Handler(Looper.getMainLooper());

    private WeatherStore() { }

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean enabled(Context context) { return prefs(context).getBoolean("enabled", true); }

    static void setEnabled(Context context, boolean value) {
        prefs(context).edit().putBoolean("enabled", value).apply();
    }

    static boolean animations(Context context) { return prefs(context).getBoolean("animations", true); }

    static void setAnimations(Context context, boolean value) {
        prefs(context).edit().putBoolean("animations", value).apply();
    }

    static int intervalMinutes(Context context) {
        int value = prefs(context).getInt("interval_min", DEFAULT_INTERVAL_MINUTES);
        if (value < MIN_INTERVAL_MINUTES || value > MAX_INTERVAL_MINUTES) value = DEFAULT_INTERVAL_MINUTES;
        return value;
    }

    static void setIntervalMinutes(Context context, int value) {
        if (value < MIN_INTERVAL_MINUTES) value = MIN_INTERVAL_MINUTES;
        if (value > MAX_INTERVAL_MINUTES) value = MAX_INTERVAL_MINUTES;
        prefs(context).edit().putInt("interval_min", value).apply();
    }

    static String place(Context context) { return prefs(context).getString("place", ""); }

    static long fetchedAt(Context context) { return prefs(context).getLong("fetched_at", 0); }

    /** Last known conditions, restored from prefs when offline. */
    static WeatherModel.Snapshot cached(Context context) {
        SharedPreferences prefs = prefs(context);
        String json = prefs.getString("current", "");
        if (json.isEmpty()) return null;
        WeatherModel.Snapshot snapshot = WeatherModel.parseCurrent(json, prefs.getLong("fetched_at", 0),
                prefs.getString("place", ""));
        return snapshot;
    }

    static void save(Context context, String json, String place, double lat, double lon, long fetchedAt) {
        prefs(context).edit()
                .putString("current", json)
                .putString("place", place)
                .putFloat("lat", (float) lat)
                .putFloat("lon", (float) lon)
                .putLong("fetched_at", fetchedAt)
                .remove("last_error")
                .apply();
    }

    static void recordError(Context context, String message) {
        prefs(context).edit().putString("last_error", message).putLong("error_at", System.currentTimeMillis()).apply();
    }

    /** "Updated 14:32 WIB" or "Not updated yet" for settings and the widget footer. */
    static String statusLine(Context context) {
        long fetched = fetchedAt(context);
        if (fetched <= 0) return UiText.get(R.string.weather_never_updated);
        SimpleDateFormat format = new SimpleDateFormat("HH:mm", Locale.getDefault());
        format.setTimeZone(TimeZone.getTimeZone(FootballData.TIMEZONE));
        return String.format(Locale.getDefault(), "%s %s",
                UiText.get(R.string.weather_updated_at), format.format(new Date(fetched)));
    }

    static String forecastUrl(double lat, double lon) {
        return "https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon
                + "&current=temperature_2m,relative_humidity_2m,weather_code,is_day,wind_speed_10m"
                + "&timezone=auto";
    }

    static String geocodingUrl(String city) {
        return "https://geocoding-api.open-meteo.com/v1/search?count=8&language="
                + URLEncoder.encode(Locale.getDefault().getLanguage())
                + "&format=json&name=" + URLEncoder.encode(city);
    }

    /**
     * HTTPS GET with an explicit host allowlist, timeouts and a response size cap.
     * Returns null on any failure; the caller keeps the cached state.
     */
    static String get(String address) {
        HttpsURLConnection connection = null;
        try {
            URL url = new URL(address);
            if (!"https".equals(url.getProtocol())) return null;
            String host = url.getHost();
            if (!"api.open-meteo.com".equals(host) && !"geocoding-api.open-meteo.com".equals(host)) return null;
            connection = (HttpsURLConnection) url.openConnection();
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(10000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "application/json");
            if (connection.getResponseCode() != 200) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = connection.getInputStream()) {
                byte[] buffer = new byte[4096];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    if (out.size() + read > 262144) return null;
                    out.write(buffer, 0, read);
                }
            }
            return out.toString("UTF-8");
        } catch (Exception ignored) {
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    interface Listener {
        void onWeatherUpdated(WeatherModel.Snapshot snapshot, boolean fromNetwork);
    }

    /** Refreshes when the cache is older than the configured interval. Runs off the UI thread. */
    static void refreshIfNeeded(Context context, Listener listener) {
        SharedPreferences prefs = prefs(context);
        if (!enabled(context)) return;
        float lat = prefs.getFloat("lat", Float.NaN);
        float lon = prefs.getFloat("lon", Float.NaN);
        if (!Double.isFinite(lat) || !Double.isFinite(lon)) return;
        long age = System.currentTimeMillis() - prefs.getLong("fetched_at", 0);
        long interval = intervalMinutes(context) * 60_000L;
        if (age >= 0 && age < interval) return;
        long retryAt = prefs.getLong("retry_at", 0);
        if (System.currentTimeMillis() < retryAt) return;
        request(context, lat, lon, prefs.getString("place", ""), listener);
    }

    /** Forces a fetch, used by the settings refresh button. */
    static void refreshNow(Context context, Listener listener) {
        SharedPreferences prefs = prefs(context);
        float lat = prefs.getFloat("lat", Float.NaN);
        float lon = prefs.getFloat("lon", Float.NaN);
        if (!Double.isFinite(lat) || !Double.isFinite(lon)) return;
        prefs.edit().remove("retry_at").apply();
        request(context, lat, lon, prefs.getString("place", ""), listener);
    }

    private static boolean running;

    private static void request(Context context, double lat, double lon, String place, Listener listener) {
        final Context app = context.getApplicationContext();
        if (running) return;
        running = true;
        new Thread(() -> {
            String json = get(forecastUrl(lat, lon));
            WeatherModel.Snapshot snapshot = null;
            if (json != null) snapshot = WeatherModel.parseCurrent(json, System.currentTimeMillis(), place);
            final WeatherModel.Snapshot result = snapshot;
            final boolean fromNetwork = snapshot != null;
            if (fromNetwork) {
                save(app, json, place, lat, lon, snapshot.fetchedAt);
            } else {
                prefs(app).edit().putLong("retry_at", System.currentTimeMillis() + RETRY_MS).apply();
                recordError(app, "network");
            }
            HANDLER.post(() -> {
                running = false;
                if (listener != null) listener.onWeatherUpdated(result, fromNetwork);
            });
        }, "weather-fetch").start();
    }
}
