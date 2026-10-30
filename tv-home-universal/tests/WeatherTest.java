package io.github.tvhome.universal;

import java.util.List;
import java.util.Locale;

/** Host checks for the weather wallpaper model: WMO mapping, sky palettes and payload parsing. */
public final class WeatherTest {
    static void check(boolean ok) { if (!ok) throw new AssertionError(); }

    private static int luminance(int color) {
        int r = (color >> 16) & 0xFF, g = (color >> 8) & 0xFF, b = color & 0xFF;
        return r + g + b;
    }

    public static void main(String[] args) {
        // WMO weather codes -> animation groups.
        check(WeatherModel.CLEAR.equals(WeatherModel.category(0)));
        check(WeatherModel.PARTLY.equals(WeatherModel.category(1)));
        check(WeatherModel.PARTLY.equals(WeatherModel.category(2)));
        check(WeatherModel.CLOUDY.equals(WeatherModel.category(3)));
        check(WeatherModel.FOG.equals(WeatherModel.category(45)));
        check(WeatherModel.FOG.equals(WeatherModel.category(48)));
        check(WeatherModel.RAIN.equals(WeatherModel.category(51)));   // drizzle
        check(WeatherModel.RAIN.equals(WeatherModel.category(63)));   // rain
        check(WeatherModel.RAIN.equals(WeatherModel.category(81)));   // showers
        check(WeatherModel.SNOW.equals(WeatherModel.category(73)));
        check(WeatherModel.SNOW.equals(WeatherModel.category(85)));
        check(WeatherModel.THUNDER.equals(WeatherModel.category(95)));
        check(WeatherModel.THUNDER.equals(WeatherModel.category(99)));
        check(WeatherModel.isThunder(95) && !WeatherModel.isThunder(61));
        check(WeatherModel.CLOUDY.equals(WeatherModel.category(999))); // unknown code stays calm

        // Day parts drive the palette and the star field.
        check(WeatherModel.daypart(0) == 0 && WeatherModel.daypart(4) == 0 && WeatherModel.daypart(20) == 0);
        check(WeatherModel.daypart(5) == 1 && WeatherModel.daypart(8) == 1);
        check(WeatherModel.daypart(9) == 2 && WeatherModel.daypart(15) == 2);
        check(WeatherModel.daypart(16) == 3 && WeatherModel.daypart(19) == 3);
        check(WeatherModel.isNight(22) && !WeatherModel.isNight(12));

        // Night is darker than day; conditions differ from each other.
        for (String group : new String[]{WeatherModel.CLEAR, WeatherModel.PARTLY, WeatherModel.CLOUDY,
                WeatherModel.FOG, WeatherModel.RAIN, WeatherModel.SNOW, WeatherModel.THUNDER}) {
            check(luminance(WeatherModel.skyTop(group, 2)) < luminance(WeatherModel.skyTop(group, 12)));
            check(WeatherModel.skyTop(group, 12) != WeatherModel.skyBottom(group, 12));
        }
        // Unknown conditions fall back to the clear palette instead of crashing.
        check(WeatherModel.skyTop("nope", 12) == WeatherModel.skyTop(WeatherModel.CLEAR, 12));
        check(WeatherModel.skyTop(WeatherModel.CLEAR, 12) != WeatherModel.skyTop(WeatherModel.RAIN, 12));
        check(luminance(WeatherModel.skyTop(WeatherModel.RAIN, 12))
                < luminance(WeatherModel.skyTop(WeatherModel.CLEAR, 12))); // rain darkens the sky

        // The sun/moon path: horizon at 05:00 and 19:00, top of the arc at midday.
        check(WeatherModel.sunProgress(5f) == 0f);
        check(WeatherModel.sunProgress(19f) == 1f);
        check(Math.abs(WeatherModel.sunProgress(12f) - 0.5f) < 0.001f);
        check(WeatherModel.sunProgress(3f) == 0f);       // clamped before dawn
        check(WeatherModel.sunProgress(22f) == 1f);      // clamped after dusk
        check(WeatherModel.sunHeight(0f) == 0f);
        check(Math.abs(WeatherModel.sunHeight(0.5f) - 1f) < 0.001f);
        check(WeatherModel.sunHeight(1f) < 0.001f);

        // Open-Meteo "current" payload.
        String current = "{\"latitude\":-6.2,\"longitude\":106.8,\"timezone\":\"Asia/Jakarta\","
                + "\"current\":{\"time\":\"2026-11-03T14:00\",\"interval\":900,"
                + "\"temperature_2m\":31.4,\"relative_humidity_2m\":72,\"weather_code\":2,"
                + "\"is_day\":1,\"wind_speed_10m\":11.3}}";
        WeatherModel.Snapshot snapshot = WeatherModel.parseCurrent(current, 1234L, "Jakarta");
        check(snapshot != null && snapshot.valid);
        check(Math.abs(snapshot.temperature - 31.4) < 0.001);
        check(snapshot.code == 2 && WeatherModel.PARTLY.equals(snapshot.category));
        check(snapshot.day && snapshot.humidity == 72);
        check("Jakarta".equals(snapshot.place) && snapshot.fetchedAt == 1234L);
        String summary = WeatherModel.summary(snapshot, "Partly cloudy", Locale.US);
        check(summary != null && summary.startsWith("31°C") && summary.endsWith("Jakarta"));

        check(WeatherModel.parseCurrent(null, 0, "") == null);
        check(WeatherModel.parseCurrent("", 0, "") == null);
        check(WeatherModel.parseCurrent("garbage", 0, "") == null);
        check(WeatherModel.parseCurrent("{\"current\":{}}", 0, "") == null);                     // no values
        check(WeatherModel.parseCurrent("{\"current\":{\"temperature_2m\":99,\"weather_code\":0,"
                + "\"is_day\":1}}", 0, "") == null);                                             // impossible temperature
        check(WeatherModel.parseCurrent("{\"current\":{\"temperature_2m\":30,\"weather_code\":0}}",
                0, "") == null);                                                                 // missing is_day

        // Offline fallback: parsing the cached payload must work without a network.
        WeatherModel.Snapshot cached = WeatherModel.parseCurrent(current, 99L, "Bandung");
        check(cached != null && "Bandung".equals(cached.place));

        // Manual city search (no location permission involved).
        String places = "{\"results\":["
                + "{\"name\":\"Jakarta\",\"admin1\":\"Jakarta\",\"country\":\"Indonesia\","
                + "\"latitude\":-6.2088,\"longitude\":106.8456},"
                + "{\"name\":\"Jakarta Selatan\",\"country\":\"Indonesia\","
                + "\"latitude\":-6.29,\"longitude\":106.82},"
                + "{\"name\":\"Broken\",\"latitude\":999,\"longitude\":0},"
                + "{\"name\":\"\",\"latitude\":1,\"longitude\":1}"
                + "]}";
        List<WeatherModel.Place> found = WeatherModel.parsePlaces(places);
        check(found.size() == 2);                                   // invalid coordinates and empty names drop out
        check("Jakarta".equals(found.get(0).name));
        check("Jakarta, Indonesia".equals(found.get(0).detail));
        check("Indonesia".equals(found.get(1).detail));            // no admin1, country only
        check(found.get(0).latitude == -6.2088 && found.get(0).longitude == 106.8456);
        check(WeatherModel.parsePlaces("").isEmpty());
        check(WeatherModel.parsePlaces("{\"results\":[]}").isEmpty());
        check(WeatherModel.parsePlaces("nope").isEmpty());

        System.out.println("PASS: weather mapping, palettes, sun path, current payload and city search.");
    }

    private WeatherTest() { }
}
