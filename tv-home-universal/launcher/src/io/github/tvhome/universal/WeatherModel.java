package io.github.tvhome.universal;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Weather condition model for the live wallpaper, based on Open-Meteo responses
 * (https://api.open-meteo.com, free for non-commercial use, no API key, WMO weather codes).
 * Keeps the palette and the mapping pure so the host test suite can verify it.
 */
final class WeatherModel {

    private WeatherModel() { }

    // Condition groups, derived from WMO weather codes.
    static final String CLEAR = "clear";
    static final String PARTLY = "partly";
    static final String CLOUDY = "cloudy";
    static final String FOG = "fog";
    static final String RAIN = "rain";
    static final String SNOW = "snow";
    static final String THUNDER = "thunder";

    /** Maps a WMO weather code to one animation group. */
    static String category(int code) {
        if (code == 0) return CLEAR;
        if (code == 1 || code == 2) return PARTLY;
        if (code == 3) return CLOUDY;
        if (code == 45 || code == 48) return FOG;
        if (code >= 51 && code <= 67) return RAIN;      // drizzle shares the rain animation
        if (code >= 71 && code <= 77) return SNOW;
        if (code >= 80 && code <= 82) return RAIN;
        if (code >= 85 && code <= 86) return SNOW;
        if (code >= 95 && code <= 99) return THUNDER;
        return CLOUDY;
    }

    static boolean isThunder(int code) { return code >= 95 && code <= 99; }

    /** Day parts: 0 night, 1 morning, 2 day, 3 evening. */
    static int daypart(int hour) {
        if (hour < 0 || hour > 23) hour = 12;
        if (hour >= 20 || hour < 5) return 0;
        if (hour < 9) return 1;
        if (hour < 16) return 2;
        return 3;
    }

    private static final String[] GROUPS = {CLEAR, PARTLY, CLOUDY, FOG, RAIN, SNOW, THUNDER};
    // Sky palettes [group][daypart] = {top, bottom}. Dark enough to keep icons readable.
    private static final int[][][] PALETTE = {
            // clear
            {{0xFF050A1C, 0xFF122246}, {0xFF274A78, 0xFF7FA3C9}, {0xFF2E6BB0, 0xFF8FBBE4}, {0xFF5A3F72, 0xFFB98A86}},
            // partly
            {{0xFF070D20, 0xFF16284C}, {0xFF31537E, 0xFF8AA9C8}, {0xFF3D6E9F, 0xFF9CBEDD}, {0xFF4A4270, 0xFFA88FA0}},
            // cloudy
            {{0xFF080C18, 0xFF1A2338}, {0xFF3C4E66, 0xFF7E8FA3}, {0xFF4B6078, 0xFF93A5B8}, {0xFF3B3A54, 0xFF7A7286}},
            // fog
            {{0xFF0A0E16, 0xFF1E2530}, {0xFF4A5460, 0xFF8B939C}, {0xFF5A646E, 0xFF9BA4AC}, {0xFF42435A, 0xFF80808F}},
            // rain
            {{0xFF060A14, 0xFF141C2E}, {0xFF2E3E52, 0xFF657586}, {0xFF3A4C60, 0xFF74869A}, {0xFF33344C, 0xFF666A80}},
            // snow
            {{0xFF0A1020, 0xFF1D2942}, {0xFF4C5C76, 0xFF8FA0B8}, {0xFF5D6F8C, 0xFFA5B4CA}, {0xFF454A66, 0xFF878CA6}},
            // thunder
            {{0xFF05060F, 0xFF131427}, {0xFF29303F, 0xFF575D6B}, {0xFF333A48, 0xFF666C79}, {0xFF2B2740, 0xFF57516A}},
    };

    private static int[] palette(String group, int daypart) {
        int index = 0;
        for (int i = 0; i < GROUPS.length; i++) if (GROUPS[i].equals(group)) { index = i; break; }
        return PALETTE[index][daypart];
    }

    static int skyTop(String group, int hour) { return palette(group, daypart(hour))[0]; }

    static int skyBottom(String group, int hour) { return palette(group, daypart(hour))[1]; }

    /** True when the sun and daylight glow are replaced by the moon and stars. */
    static boolean isNight(int hour) { return daypart(hour) == 0; }

    /**
     * Sun position across the sky for a local hour, 0..1 left to right.
     * The sun travels during daylight; at night the moon takes the same slow path.
     */
    static float sunProgress(float hour) {
        float h = hour % 24f;
        if (h < 5f) h = 5f;
        if (h > 19f) h = 19f;
        return (h - 5f) / 14f;
    }

    /** Height of the sun/moon arc: 0 at the horizon, 1 at the top of its path. */
    static float sunHeight(float progress) {
        double radians = Math.PI * progress;
        return (float) Math.sin(radians);
    }

    static final class Snapshot {
        String category;
        int code;
        double temperature;
        int humidity;
        double wind;
        boolean day;
        String place;
        long fetchedAt;
        boolean valid;
    }

    /** Parses the Open-Meteo "current" block. Returns null for unusable payloads. */
    static Snapshot parseCurrent(String json, long fetchedAt, String place) {
        if (json == null || json.isEmpty()) return null;
        try {
            JSONObject root = new JSONObject(json);
            JSONObject current = root.optJSONObject("current");
            if (current == null) return null;
            double temperature = current.optDouble("temperature_2m", Double.NaN);
            int code = current.optInt("weather_code", -1);
            int isDay = current.optInt("is_day", -1);
            if (!Double.isFinite(temperature) || temperature < -90 || temperature > 60) return null;
            if (code < 0 || code > 99) return null;
            if (isDay != 0 && isDay != 1) return null;
            Snapshot snapshot = new Snapshot();
            snapshot.temperature = temperature;
            snapshot.code = code;
            snapshot.category = category(code);
            snapshot.humidity = current.optInt("relative_humidity_2m", -1);
            snapshot.wind = current.optDouble("wind_speed_10m", Double.NaN);
            snapshot.day = isDay == 1;
            snapshot.place = place == null ? "" : place;
            snapshot.fetchedAt = fetchedAt;
            snapshot.valid = true;
            return snapshot;
        } catch (Exception ignored) {
            return null;
        }
    }

    static final class Place {
        String name;
        String detail;
        double latitude;
        double longitude;
    }

    /** Parses the Open-Meteo geocoding response for the manual city picker. */
    static List<Place> parsePlaces(String json) {
        List<Place> out = new ArrayList<>();
        if (json == null || json.isEmpty()) return out;
        try {
            JSONObject root = new JSONObject(json);
            JSONArray results = root.optJSONArray("results");
            if (results == null) return out;
            for (int i = 0; i < results.length() && i < 8; i++) {
                JSONObject item = results.optJSONObject(i);
                if (item == null) continue;
                Place place = new Place();
                place.name = item.optString("name", "");
                if (place.name.isEmpty()) continue;
                String admin = item.optString("admin1", "");
                String country = item.optString("country", "");
                place.detail = admin.isEmpty() ? country : (country.isEmpty() ? admin : admin + ", " + country);
                place.latitude = item.optDouble("latitude", Double.NaN);
                place.longitude = item.optDouble("longitude", Double.NaN);
                if (!Double.isFinite(place.latitude) || !Double.isFinite(place.longitude)) continue;
                if (place.latitude < -90 || place.latitude > 90 || place.longitude < -180 || place.longitude > 180) continue;
                out.add(place);
            }
        } catch (Exception ignored) {
            return out;
        }
        return out;
    }

    /** "30°C · Cerah · Jakarta" one-line summary used by the clock header. */
    static String summary(Snapshot snapshot, String conditionLabel, Locale locale) {
        if (snapshot == null || !snapshot.valid) return null;
        String temperature = String.format(locale, "%.0f°C", snapshot.temperature);
        if (snapshot.place == null || snapshot.place.isEmpty()) return temperature + " · " + conditionLabel;
        return temperature + " · " + conditionLabel + " · " + snapshot.place;
    }
}
