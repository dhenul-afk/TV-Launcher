package io.github.tvhome.universal;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import java.util.List;
import java.util.Locale;

/**
 * Settings for the weather live wallpaper: enable it, switch animations off, pick a city manually
 * (Open-Meteo geocoding, no location permission), choose the refresh interval and force an update.
 * The last conditions stay in preferences so the wallpaper keeps working offline.
 */
public class WeatherSettingsActivity extends Activity {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView status;
    private Switch wallpaperToggle;
    private Switch animationToggle;
    private Button cityButton;
    private Button intervalButton;
    private boolean searching;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(0, 0, 0, 0);

        wallpaperToggle = row(UiText.get(R.string.weather_enable), "wallpaper");
        wallpaperToggle.setChecked(WeatherStore.enabled(this));
        panel.addView(wallpaperToggle, new LinearLayout.LayoutParams(-1, dp(36)));

        animationToggle = row(UiText.get(R.string.weather_animations), "screensaver");
        animationToggle.setTag("widget_suboption");
        animationToggle.setChecked(WeatherStore.animations(this));
        panel.addView(animationToggle, new LinearLayout.LayoutParams(-1, dp(36)));

        LinearLayout.LayoutParams subParams = new LinearLayout.LayoutParams(-1, dp(36));
        subParams.leftMargin = dp(16);

        cityButton = subRow(UiText.get(R.string.weather_city), "location");
        cityButton.setOnClickListener(v -> pickCity());
        panel.addView(cityButton, subParams);

        intervalButton = subRow(UiText.get(R.string.weather_interval), "refresh");
        intervalButton.setOnClickListener(v -> pickInterval());
        panel.addView(intervalButton, subParams);

        Button refresh = subRow(UiText.get(R.string.weather_refresh), "refresh");
        refresh.setOnClickListener(v -> refreshNow());
        panel.addView(refresh, new LinearLayout.LayoutParams(-1, dp(36)));

        status = new TextView(this);
        status.setTextSize(10);
        status.setTextColor(0x99FFFFFF);
        status.setPadding(dp(14), dp(8), dp(14), dp(4));
        panel.addView(status, new LinearLayout.LayoutParams(-1, -2));

        TextView attribution = new TextView(this);
        attribution.setTextSize(10);
        attribution.setTextColor(0x80FFFFFF);
        attribution.setPadding(dp(14), 0, dp(14), 0);
        attribution.setText(UiText.get(R.string.weather_attribution));
        panel.addView(attribution, new LinearLayout.LayoutParams(-1, -2));

        TextView offline = new TextView(this);
        offline.setTextSize(10);
        offline.setTextColor(0x80FFFFFF);
        offline.setPadding(dp(14), dp(4), dp(14), 0);
        offline.setText(UiText.get(R.string.weather_offline_note));
        panel.addView(offline, new LinearLayout.LayoutParams(-1, -2));

        wallpaperToggle.setOnCheckedChangeListener((button, checked) -> {
            WeatherStore.setEnabled(this, checked);
            animationToggle.setEnabled(checked);
        });
        animationToggle.setOnCheckedChangeListener((button, checked) ->
                WeatherStore.setAnimations(this, checked));

        updateStatus();
        SettingsPopup.show(this, UiText.get(R.string.weather_title), panel, wallpaperToggle);
    }

    private void updateStatus() {
        String city = WeatherStore.place(this);
        if (city.isEmpty()) city = UiText.get(R.string.weather_no_city);
        String interval = WeatherStore.intervalMinutes(this) + " " + UiText.get(R.string.ui_006);
        status.setText(city + " · " + WeatherStore.statusLine(this) + " · "
                + UiText.get(R.string.weather_interval) + ": " + interval);
        if (cityButton != null) {
            cityButton.setText(UiText.get(R.string.weather_city) + ": " + city);
        }
        if (intervalButton != null) {
            intervalButton.setText(UiText.get(R.string.weather_interval) + ": "
                    + WeatherStore.intervalMinutes(this) + " " + UiText.get(R.string.ui_006));
        }
    }

    private void refreshNow() {
        WeatherStore.refreshNow(this, (snapshot, fromNetwork) -> {
            if (isFinishing() || isDestroyed()) return;
            updateStatus();
            if (!fromNetwork) {
                android.widget.Toast.makeText(this, UiText.get(R.string.weather_error),
                        android.widget.Toast.LENGTH_SHORT).show();
            }
        });
        updateStatus();
    }

    private void pickInterval() {
        final int[] values = {15, 30, 60, 120};
        String[] labels = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            labels[i] = values[i] + " " + UiText.get(R.string.ui_006);
        }
        int current = WeatherStore.intervalMinutes(this);
        int selected = 1;
        for (int i = 0; i < values.length; i++) if (values[i] == current) selected = i;
        new AlertDialog.Builder(this)
                .setTitle(UiText.get(R.string.weather_interval))
                .setSingleChoiceItems(labels, selected, (dialog, which) -> {
                    WeatherStore.setIntervalMinutes(this, values[which]);
                    updateStatus();
                    dialog.dismiss();
                })
                .setNegativeButton(UiText.get(R.string.ui_026), null)
                .show();
    }

    private void pickCity() {
        if (searching) return;
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setSingleLine(true);
        input.setHint(UiText.get(R.string.weather_city_hint));
        input.setText(WeatherStore.place(this));
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(80)});
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(UiText.get(R.string.weather_search_city))
                .setView(input)
                .setPositiveButton(UiText.get(R.string.ui_032), (d, w) -> search(input.getText().toString()))
                .setNegativeButton(UiText.get(R.string.ui_026), null)
                .create();
        dialog.show();
        GlassControls.dialog(dialog);
    }

    private void search(String query) {
        final String city = query == null ? "" : query.trim();
        if (city.isEmpty() || city.length() > 80) return;
        searching = true;
        status.setText(UiText.get(R.string.weather_searching));
        new Thread(() -> {
            String json = WeatherStore.get(WeatherStore.geocodingUrl(city));
            final List<WeatherModel.Place> results = WeatherModel.parsePlaces(json);
            handler.post(() -> {
                searching = false;
                if (isFinishing() || isDestroyed()) return;
                if (results.isEmpty()) {
                    status.setText(UiText.get(R.string.weather_no_results));
                    return;
                }
                String[] labels = new String[results.size()];
                for (int i = 0; i < results.size(); i++) {
                    WeatherModel.Place place = results.get(i);
                    labels[i] = place.detail.isEmpty() ? place.name : place.name + " · " + place.detail;
                }
                new AlertDialog.Builder(WeatherSettingsActivity.this)
                        .setTitle(UiText.get(R.string.weather_search_city))
                        .setSingleChoiceItems(labels, 0, (dialog, which) -> {
                            WeatherModel.Place chosen = results.get(which);
                            saveCity(chosen);
                            dialog.dismiss();
                        })
                        .setNegativeButton(UiText.get(R.string.ui_026), null)
                        .show();
            });
        }, "weather-geocode").start();
    }

    private void saveCity(WeatherModel.Place place) {
        WeatherStore.prefs(this).edit()
                .putString("place", place.name)
                .putFloat("lat", (float) place.latitude)
                .putFloat("lon", (float) place.longitude)
                .putLong("fetched_at", 0)
                .remove("current")
                .remove("retry_at")
                .apply();
        updateStatus();
        refreshNow();
    }

    private Switch row(String label, String icon) {
        Switch row = new Switch(this);
        row.setText(label);
        row.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        row.setSingleLine(true);
        MenuIcon.attach(row, icon, 19, Color.WHITE);
        return row;
    }

    private Button subRow(String label, String icon) {
        Button row = new Button(this);
        row.setText(label);
        row.setTag("widget_suboption");
        row.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        row.setSingleLine(true);
        row.setAllCaps(false);
        MenuIcon.attach(row, icon, 19, Color.WHITE);
        return row;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
