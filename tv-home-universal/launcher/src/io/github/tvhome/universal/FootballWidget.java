package io.github.tvhome.universal;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

import javax.net.ssl.HttpsURLConnection;

/**
 * Schedule / results / standings widget for the Premier League, LaLiga and Champions League.
 * Data: football-data.org v4 (free plan: 10 requests/minute, attribution required, scores are
 * delayed by the provider). Responses are cached per tab, failures keep the previous data and the
 * footer always shows when the data was last updated. Nothing is ever invented: when the API or
 * the network is unavailable the widget says so.
 */
final class FootballWidget extends LinearLayout {

    private static final String TAB_SCHEDULE = "schedule";
    private static final String TAB_RESULTS = "results";
    private static final String TAB_STANDINGS = "standings";

    private static final long CACHE_MS = 10 * 60 * 1000L;
    private static final long STANDINGS_CACHE_MS = 60 * 60 * 1000L;
    private static final long MIN_REQUEST_GAP_MS = 6100L;   // stay under 10 requests/minute
    private static final long RETRY_MS = 5 * 60 * 1000L;
    private static final int MAX_ROWS = 4;

    private final Activity activity;
    private final SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final TextView[] tabs = new TextView[3];
    private final LinearLayout rows;
    private final TextView footer;
    private final Runnable staleCheck = this::refreshIfStale;

    private String tab = TAB_SCHEDULE;
    private boolean active, loading;
    private long retryAt, nextAllowedRequest;
    private String message;

    FootballWidget(Activity activity) {
        super(activity);
        this.activity = activity;
        prefs = activity.getSharedPreferences("football_widget", Context.MODE_PRIVATE);
        setOrientation(VERTICAL);
        setClipChildren(false);

        LinearLayout tabBar = new LinearLayout(activity);
        tabBar.setOrientation(HORIZONTAL);
        String[] labels = {
                UiText.get(R.string.football_schedule),
                UiText.get(R.string.football_results),
                UiText.get(R.string.football_standings)
        };
        final String[] keys = {TAB_SCHEDULE, TAB_RESULTS, TAB_STANDINGS};
        for (int i = 0; i < tabs.length; i++) {
            TextView tabView = new TextView(activity);
            tabView.setText(labels[i]);
            tabView.setTextSize(10);
            tabView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            tabView.setTextColor(0xE6FFFFFF);
            tabView.setGravity(Gravity.CENTER);
            tabView.setPadding(dp(10), dp(4), dp(10), dp(4));
            tabView.setFocusable(true);
            tabView.setClickable(true);
            tabView.setId(View.generateViewId());
            android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable();
            background.setCornerRadius(dp(9));
            background.setColor(0x2EFFFFFF);
            tabView.setBackground(background);
            tabView.setOnFocusChangeListener((v, has) -> {
                background.setColor(has ? 0x66FFFFFF : 0x2EFFFFFF);
                background.setStroke(has ? dp(1) : 0, 0xBBFFFFFF);
            });
            final int index = i;
            tabView.setOnClickListener(v -> selectTab(keys[index]));
            tabs[i] = tabView;
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(24), 1f);
            params.rightMargin = dp(6);
            tabBar.addView(tabView, params);
        }
        addView(tabBar, new LayoutParams(-1, -2));

        rows = new LinearLayout(activity);
        rows.setOrientation(VERTICAL);
        rows.setPadding(0, dp(6), 0, 0);
        addView(rows, new LayoutParams(-1, -2));

        footer = new TextView(activity);
        footer.setTextSize(9);
        footer.setTextColor(0x99FFFFFF);
        footer.setPadding(0, dp(6), 0, 0);
        footer.setSingleLine(true);
        footer.setEllipsize(android.text.TextUtils.TruncateAt.END);
        addView(footer, new LayoutParams(-1, -2));

        setContentDescription(UiText.get(R.string.football_title));
        selectTab(prefs.getString("tab", TAB_SCHEDULE));
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    // ---------------------------------------------------------------- lifecycle

    void resume() {
        active = true;
        update();
    }

    void pause() {
        active = false;
        handler.removeCallbacks(staleCheck);
    }

    void update() {
        handler.removeCallbacks(staleCheck);
        if (!active) return;
        render();
        refreshIfStale();
    }

    private void selectTab(String next) {
        if (!TAB_SCHEDULE.equals(next) && !TAB_RESULTS.equals(next) && !TAB_STANDINGS.equals(next)) {
            next = TAB_SCHEDULE;
        }
        tab = next;
        prefs.edit().putString("tab", next).apply();
        int selected = TAB_SCHEDULE.equals(next) ? 0 : TAB_RESULTS.equals(next) ? 1 : 2;
        for (int i = 0; i < tabs.length; i++) {
            tabs[i].setTextColor(i == selected ? Color.WHITE : 0xA6FFFFFF);
            tabs[i].getPaint().setFakeBoldText(i == selected);
        }
        tabs[selected].setSelected(true);
        invalidate();
        update();
    }

    // ---------------------------------------------------------------- settings

    private List<String> competitions() {
        Set<String> out = new LinkedHashSet<>();
        String raw = prefs.getString("competitions", "PL,PD,CL");
        for (String code : raw.split(",")) {
            String value = code.trim().toUpperCase(Locale.US);
            for (String allowed : FootballData.COMPETITIONS) if (allowed.equals(value)) out.add(value);
        }
        if (out.isEmpty()) out.addAll(Arrays.asList(FootballData.COMPETITIONS));
        return new ArrayList<>(out);
    }

    private String apiKey() { return prefs.getString("api_key", "").trim(); }

    void settings() {
        LinearLayout fields = new LinearLayout(activity);
        fields.setOrientation(VERTICAL);
        fields.setPadding(dp(20), dp(8), dp(20), 0);

        final EditText keyInput = new EditText(activity);
        keyInput.setSingleLine(true);
        keyInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        keyInput.setHint(UiText.get(R.string.football_api_key));
        keyInput.setText(apiKey());
        fields.addView(keyInput);

        TextView hint = new TextView(activity);
        hint.setTextSize(11);
        hint.setTextColor(0x99FFFFFF);
        hint.setPadding(0, dp(4), 0, dp(8));
        hint.setText(UiText.get(R.string.football_api_hint));
        fields.addView(hint);

        final String[] codes = FootballData.COMPETITIONS;
        String[] names = {
                UiText.get(R.string.football_comp_pl),
                UiText.get(R.string.football_comp_pd),
                UiText.get(R.string.football_comp_cl)
        };
        final List<String> selected = competitions();
        final android.widget.Switch[] toggles = new android.widget.Switch[codes.length];
        for (int i = 0; i < codes.length; i++) {
            android.widget.Switch option = new android.widget.Switch(activity);
            option.setText(names[i]);
            option.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            option.setSingleLine(true);
            option.setTextSize(13);
            option.setMinHeight(dp(36));
            option.setChecked(selected.contains(codes[i]));
            MenuIcon.attach(option, "order", 19, 0xFFFFFFFF);
            toggles[i] = option;
            fields.addView(option, new LinearLayout.LayoutParams(-1, dp(36)));
        }

        TextView attribution = new TextView(activity);
        attribution.setTextSize(10);
        attribution.setTextColor(0x80FFFFFF);
        attribution.setPadding(0, dp(8), 0, 0);
        attribution.setText(UiText.get(R.string.football_attribution) + " · "
                + UiText.get(R.string.football_rate_limit));
        fields.addView(attribution);

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(UiText.get(R.string.football_settings))
                .setView(fields)
                .setPositiveButton(UiText.get(R.string.ui_120), null)
                .setNegativeButton(UiText.get(R.string.ui_026), null)
                .setNeutralButton(UiText.get(R.string.football_refresh), (d, w) -> update())
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
            String value = keyInput.getText().toString().trim();
            if (value.length() > 200) { keyInput.setError(UiText.get(R.string.ui_057)); return; }
            StringBuilder codesBuilder = new StringBuilder();
            for (int i = 0; i < codes.length; i++) {
                if (toggles[i].isChecked()) {
                    if (codesBuilder.length() > 0) codesBuilder.append(',');
                    codesBuilder.append(codes[i]);
                }
            }
            if (codesBuilder.length() == 0) {
                toggles[0].setError(UiText.get(R.string.football_competitions_required));
                return;
            }
            prefs.edit()
                    .putString("api_key", value)
                    .putString("competitions", codesBuilder.toString())
                    .remove("schedule_at").remove("results_at")
                    .apply();
            for (String code : FootballData.COMPETITIONS) {
                prefs.edit().remove("standings_at_" + code).apply();
            }
            message = null;
            retryAt = 0;
            dialog.dismiss();
            update();
        }));
        dialog.show();
        GlassControls.dialog(dialog);
    }

    // ---------------------------------------------------------------- data

    private void refreshIfStale() {
        handler.removeCallbacks(staleCheck);
        if (!active) return;
        if (apiKey().isEmpty()) {
            message = UiText.get(R.string.football_need_key);
            render();
            return;
        }
        if (loading || SystemClock.elapsedRealtime() < retryAt) { scheduleCheck(); return; }
        if (SystemClock.elapsedRealtime() < nextAllowedRequest) { scheduleCheck(); return; }
        if (!stale()) { scheduleCheck(); return; }
        fetch();
    }

    private void scheduleCheck() {
        handler.removeCallbacks(staleCheck);
        if (active) handler.postDelayed(staleCheck, 30_000);
    }

    private boolean stale() {
        long now = System.currentTimeMillis();
        if (TAB_STANDINGS.equals(tab)) {
            for (String code : competitions()) {
                if (now - prefs.getLong("standings_at_" + code, 0) >= STANDINGS_CACHE_MS) return true;
            }
            return false;
        }
        String key = TAB_SCHEDULE.equals(tab) ? "schedule_at" : "results_at";
        long age = now - prefs.getLong(key, 0);
        return age >= CACHE_MS || age < 0;
    }

    private void fetch() {
        if (loading) return;
        loading = true;
        message = UiText.get(R.string.football_loading);
        render();
        final String wantedTab = tab;
        final String key = apiKey();
        final List<String> codes = competitions();
        new Thread(() -> {
            String error = null;
            try {
                if (TAB_STANDINGS.equals(wantedTab)) {
                    for (String code : codes) {
                        if (Thread.currentThread().isInterrupted()) return;
                        String url = FootballData.BASE + "/competitions/" + code + "/standings";
                        Result result = request(url, key);
                        if (result.error != null) { error = result.error; break; }
                        if (FootballData.parseStandings(result.body) == null) {
                            // Some provider errors arrive as a 200 body with an errorCode field.
                            error = result.body != null && result.body.contains("\"errorCode\"")
                                    ? "invalid_key" : "parse";
                            break;
                        }
                        prefs.edit().putString("standings_" + code, result.body)
                                .putLong("standings_at_" + code, System.currentTimeMillis()).apply();
                    }
                } else {
                    long now = System.currentTimeMillis();
                    String from = TAB_SCHEDULE.equals(wantedTab)
                            ? FootballData.wibDate(now)
                            : FootballData.wibDate(now - 14L * 24 * 60 * 60 * 1000L);
                    String to = FootballData.wibDate(now + (TAB_SCHEDULE.equals(wantedTab)
                            ? 7L * 24 * 60 * 60 * 1000L : 0L));
                    String url = FootballData.BASE + "/matches?dateFrom=" + from + "&dateTo=" + to;
                    Result result = request(url, key);
                    if (result.error != null) error = result.error;
                    else if (FootballData.parseMatches(result.body, null) == null) {
                        error = result.body != null && result.body.contains("\"errorCode\"")
                                ? "invalid_key" : "parse";
                    } else {
                        String at = TAB_SCHEDULE.equals(wantedTab) ? "schedule_at" : "results_at";
                        String json = TAB_SCHEDULE.equals(wantedTab) ? "schedule" : "results";
                        prefs.edit().putString(json, result.body)
                                .putLong(at, System.currentTimeMillis()).apply();
                    }
                }
            } catch (RuntimeException ignored) {
                error = "network";
            }
            final String failure = error;
            handler.post(() -> {
                loading = false;
                nextAllowedRequest = SystemClock.elapsedRealtime() + MIN_REQUEST_GAP_MS;
                if (failure != null) {
                    retryAt = SystemClock.elapsedRealtime() + RETRY_MS;
                    message = "invalid_key".equals(failure)
                            ? UiText.get(R.string.football_invalid_key)
                            : UiText.get(R.string.football_no_data);
                } else {
                    message = null;
                    retryAt = SystemClock.elapsedRealtime() + RETRY_MS;
                }
                if (active && wantedTab.equals(tab)) render();
                scheduleCheck();
            });
        }, "football-fetch").start();
    }

    private static final class Result {
        String body;
        String error;
    }

    private static Result request(String address, String key) {
        Result out = new Result();
        HttpsURLConnection connection = null;
        try {
            URL url = new URL(address);
            if (!"https".equals(url.getProtocol()) || !"api.football-data.org".equals(url.getHost())) {
                out.error = "host";
                return out;
            }
            connection = (HttpsURLConnection) url.openConnection();
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(12000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("X-Auth-Token", key);
            connection.setRequestProperty("Accept", "application/json");
            int status = connection.getResponseCode();
            if (status == 400 || status == 401 || status == 403) { out.error = "invalid_key"; return out; }
            if (status == 429) { out.error = "rate"; return out; }
            if (status != 200) { out.error = "http"; return out; }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (InputStream in = connection.getInputStream()) {
                byte[] chunk = new byte[4096];
                int read;
                while ((read = in.read(chunk)) != -1) {
                    if (buffer.size() + read > 1024 * 1024) { out.error = "size"; return out; }
                    buffer.write(chunk, 0, read);
                }
            }
            out.body = buffer.toString("UTF-8");
            return out;
        } catch (Exception ignored) {
            out.error = "network";
            return out;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    // ---------------------------------------------------------------- rendering

    private void render() {
        rows.removeAllViews();
        if (apiKey().isEmpty()) {
            footer.setText(UiText.get(R.string.football_need_key));
            return;
        }
        List<FootballData.Match> matches = readMatches();
        if (TAB_STANDINGS.equals(tab)) {
            renderStandings();
            return;
        }
        if (matches == null) {
            addEmpty(message != null ? message : UiText.get(R.string.football_no_data));
            footer.setText(footerText(false));
            return;
        }
        List<FootballData.Match> visible = new ArrayList<>();
        long startToday = todayStart();
        for (FootballData.Match match : matches) {
            if (!competitions().contains(match.competition)) continue;
            if (TAB_RESULTS.equals(tab)) {
                if (match.finished) visible.add(match);
            } else if (match.epoch >= startToday) {
                visible.add(match);
            }
        }
        if (TAB_RESULTS.equals(tab)) FootballData.sortNewestFirst(visible);
        else FootballData.sort(visible);
        visible = FootballData.limit(visible, MAX_ROWS);
        if (visible.isEmpty()) {
            addEmpty(UiText.get(R.string.football_no_matches));
            footer.setText(footerText(true));
            return;
        }
        for (FootballData.Match match : visible) rows.addView(matchRow(match));
        footer.setText(footerText(true));
    }

    private void renderStandings() {
        List<String> codes = competitions();
        int shown = 0;
        for (String code : codes) {
            String json = prefs.getString("standings_" + code, "");
            List<FootballData.Row> table = FootballData.parseStandings(json);
            if (table == null) continue;
            addHeader(UiText.get(competitionName(code)));
            int perCompetition = 0;
            for (FootballData.Row row : table) {
                if (shown >= 6 || perCompetition >= 3) break;
                rows.addView(standingRow(row));
                shown++;
                perCompetition++;
            }
        }
        if (shown == 0) {
            addEmpty(message != null ? message : UiText.get(R.string.football_standings_unavailable));
        }
        footer.setText(footerText(true));
    }

    private List<FootballData.Match> readMatches() {
        String key = TAB_SCHEDULE.equals(tab) ? "schedule" : "results";
        return FootballData.parseMatches(prefs.getString(key, ""), null);
    }

    private long todayStart() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone(FootballData.TIMEZONE));
        try {
            String today = FootballData.wibDate(System.currentTimeMillis()) + " 00:00";
            return format.parse(today).getTime();
        } catch (Exception ignored) {
            return 0;
        }
    }

    private View matchRow(FootballData.Match match) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(VERTICAL);
        row.setPadding(0, dp(3), 0, dp(3));

        LinearLayout line = new LinearLayout(activity);
        line.setOrientation(HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);

        TextView home = teamLabel(match.home, Gravity.END);
        TextView centre = new TextView(activity);
        centre.setTextSize(12);
        centre.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        centre.setTextColor(Color.WHITE);
        centre.setGravity(Gravity.CENTER);
        centre.setMinWidth(dp(76));
        TextView away = teamLabel(match.away, Gravity.START);

        String score = FootballData.scoreLine(match);
        if (score != null) centre.setText(score);
        else if (FootballData.STATE_NOT_STARTED.equals(match.state)) {
            centre.setText(FootballData.wibTime(match.epoch, UiText.locale()));
            centre.setTextColor(0xCCFFFFFF);
            centre.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        } else {
            centre.setText("–");
            centre.setTextColor(0x99FFFFFF);
        }

        line.addView(home, new LinearLayout.LayoutParams(0, -2, 1f));
        line.addView(centre, new LinearLayout.LayoutParams(-2, -2));
        line.addView(away, new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(line, new LayoutParams(-1, -2));

        TextView caption = new TextView(activity);
        caption.setTextSize(9);
        caption.setTextColor(0x99FFFFFF);
        caption.setSingleLine(true);
        caption.setEllipsize(android.text.TextUtils.TruncateAt.END);
        caption.setText(UiText.get(competitionName(match.competition)) + " · " + stateLabel(match.state)
                + " · " + FootballData.wibLine(match.epoch, UiText.locale()) + " WIB");
        if (FootballData.STATE_LIVE.equals(match.state) || FootballData.STATE_PAUSED.equals(match.state)) {
            caption.setTextColor(0xFFB7E4C7);
        }
        row.addView(caption, new LayoutParams(-1, -2));

        int crestSize = dp(14);
        TeamCrest.attach(home, match.homeCrest, crestSize, false);
        TeamCrest.attach(away, match.awayCrest, crestSize, true);
        return row;
    }

    private TextView teamLabel(String name, int gravity) {
        TextView label = new TextView(activity);
        label.setText(name);
        label.setTextSize(12);
        label.setTextColor(0xF0FFFFFF);
        label.setSingleLine(true);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        label.setGravity(gravity);
        label.setCompoundDrawablePadding(dp(5));
        return label;
    }

    private View standingRow(FootballData.Row row) {
        LinearLayout line = new LinearLayout(activity);
        line.setOrientation(HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(0, dp(2), 0, dp(2));

        TextView position = new TextView(activity);
        position.setTextSize(11);
        position.setTextColor(0x99FFFFFF);
        position.setGravity(Gravity.END);
        position.setMinWidth(dp(20));
        position.setText(row.position + ".");

        TextView team = teamLabel(row.team, Gravity.START);
        team.setTextSize(11);

        TextView points = new TextView(activity);
        points.setTextSize(11);
        points.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        points.setTextColor(Color.WHITE);
        points.setGravity(Gravity.END);
        points.setMinWidth(dp(34));
        points.setText(row.points < 0 ? "–" : row.points + " " + UiText.get(R.string.football_points));

        line.addView(position, new LinearLayout.LayoutParams(-2, -2));
        line.addView(team, new LinearLayout.LayoutParams(0, -2, 1f));
        line.addView(points, new LinearLayout.LayoutParams(-2, -2));
        line.setPadding(dp(6), 0, dp(6), 0);
        TeamCrest.attach(team, row.crest, dp(12), true);
        return line;
    }

    private void addHeader(String text) {
        TextView header = new TextView(activity);
        header.setTextSize(9);
        header.setTextColor(0xB3FFFFFF);
        header.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        header.setLetterSpacing(0.08f);
        header.setSingleLine(true);
        header.setPadding(0, dp(5), 0, dp(1));
        header.setText(text.toUpperCase(UiText.locale()));
        rows.addView(header, new LayoutParams(-1, -2));
    }

    private void addEmpty(String text) {
        TextView empty = new TextView(activity);
        empty.setTextSize(11);
        empty.setTextColor(0xCCFFFFFF);
        empty.setPadding(0, dp(2), 0, dp(2));
        empty.setText(text);
        rows.addView(empty, new LayoutParams(-1, -2));
    }

    private String footerText(boolean hasData) {
        long updated = 0;
        if (TAB_STANDINGS.equals(tab)) {
            for (String code : competitions()) {
                updated = Math.max(updated, prefs.getLong("standings_at_" + code, 0));
            }
        } else {
            updated = prefs.getLong(TAB_SCHEDULE.equals(tab) ? "schedule_at" : "results_at", 0);
        }
        String time = null;
        if (updated > 0) {
            SimpleDateFormat format = new SimpleDateFormat("HH:mm", Locale.US);
            format.setTimeZone(TimeZone.getTimeZone(FootballData.TIMEZONE));
            time = format.format(new Date(updated));
        }
        String source = FootballData.SOURCE;
        if (time == null) return source;
        return (hasData ? UiText.get(R.string.football_updated_at) + " " + time + " WIB"
                : UiText.get(R.string.football_no_data)) + " · " + source;
    }

    private int competitionName(String code) {
        if ("PL".equals(code)) return R.string.football_comp_pl;
        if ("PD".equals(code)) return R.string.football_comp_pd;
        if ("CL".equals(code)) return R.string.football_comp_cl;
        return R.string.football_title;
    }

    private String stateLabel(String state) {
        if (FootballData.STATE_LIVE.equals(state)) return UiText.get(R.string.football_live);
        if (FootballData.STATE_PAUSED.equals(state)) return UiText.get(R.string.football_half_time);
        if (FootballData.STATE_FINISHED.equals(state)) return UiText.get(R.string.football_finished);
        if (FootballData.STATE_POSTPONED.equals(state)) return UiText.get(R.string.football_postponed);
        if (FootballData.STATE_CANCELLED.equals(state)) return UiText.get(R.string.football_cancelled);
        if (FootballData.STATE_SUSPENDED.equals(state)) return UiText.get(R.string.football_suspended);
        if (FootballData.STATE_NOT_STARTED.equals(state)) return UiText.get(R.string.football_not_started);
        return UiText.get(R.string.football_unknown);
    }
}
