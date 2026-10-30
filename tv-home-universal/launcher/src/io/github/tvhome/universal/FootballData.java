package io.github.tvhome.universal;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Football-data.org (https://api.football-data.org/v4) response handling.
 * Free plan: 10 requests/minute, covers Premier League (PL), LaLiga (PD) and Champions League (CL).
 * Scores on the free plan are delayed by the provider; the app only shows what the API returns.
 * All times are rendered in Asia/Jakarta (WIB) as required for the TV.
 * Pure Java: no Android classes, so the logic runs in the host test suite.
 */
final class FootballData {
    static final String BASE = "https://api.football-data.org/v4";
    static final String TIMEZONE = "Asia/Jakarta";
    static final String[] COMPETITIONS = {"PL", "PD", "CL"};
    /** Attribution required by the provider. */
    static final String SOURCE = "football-data.org";

    private FootballData() { }

    static final class Match {
        String competition;
        String home;
        String away;
        String homeCrest;
        String awayCrest;
        String state;          // one of the STATE_* constants
        String utcDate;        // raw ISO-8601 value from the API
        long epoch;            // parsed instant, for sorting
        int homeScore = -1;
        int awayScore = -1;
        boolean finished;

        boolean hasScore() { return homeScore >= 0 && awayScore >= 0; }
    }

    static final class Row {
        int position;
        int played;
        int won;
        int draw;
        int lost;
        int points;
        int goalDifference;
        String team;
        String crest;
        String group;
    }

    /** Only https crest images from known provider CDNs are accepted. */
    static boolean validCrest(String address) {
        if (address == null || address.length() > 300) return false;
        try {
            java.net.URL url = new java.net.URL(address);
            if (!"https".equals(url.getProtocol()) || url.getUserInfo() != null) return false;
            if (url.getPort() != -1 && url.getPort() != 443) return false;
            String host = url.getHost();
            return host != null && (host.endsWith(".football-data.org")
                    || host.equals("football-data.org")
                    || host.endsWith(".football-data.co.uk"));
        } catch (Exception ignored) {
            return false;
        }
    }

    static final String STATE_NOT_STARTED = "not_started";
    static final String STATE_LIVE = "live";
    static final String STATE_PAUSED = "paused";
    static final String STATE_FINISHED = "finished";
    static final String STATE_POSTPONED = "postponed";
    static final String STATE_CANCELLED = "cancelled";
    static final String STATE_SUSPENDED = "suspended";
    static final String STATE_UNKNOWN = "unknown";

    /** Maps a provider status onto the five states the widget can display. */
    static String state(String status) {
        if (status == null) return STATE_UNKNOWN;
        switch (status) {
            case "SCHEDULED":
            case "TIMED":
                return STATE_NOT_STARTED;
            case "IN_PLAY":
                return STATE_LIVE;
            case "PAUSED":
                return STATE_PAUSED;
            case "FINISHED":
            case "AWARDED":
                return STATE_FINISHED;
            case "POSTPONED":
                return STATE_POSTPONED;
            case "CANCELLED":
                return STATE_CANCELLED;
            case "SUSPENDED":
                return STATE_SUSPENDED;
            default:
                return STATE_UNKNOWN;
        }
    }

    static long epoch(String utcDate) {
        if (utcDate == null) return Long.MAX_VALUE;
        try {
            SimpleDateFormat parser = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
            parser.setTimeZone(TimeZone.getTimeZone("UTC"));
            parser.setLenient(false);
            Date parsed = parser.parse(utcDate);
            return parsed == null ? Long.MAX_VALUE : parsed.getTime();
        } catch (ParseException e) {
            try {
                SimpleDateFormat fallback = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssX", Locale.US);
                fallback.setTimeZone(TimeZone.getTimeZone("UTC"));
                Date parsed = fallback.parse(utcDate);
                return parsed == null ? Long.MAX_VALUE : parsed.getTime();
            } catch (ParseException ignored) {
                return Long.MAX_VALUE;
            }
        }
    }

    /** "3 Nov 20:00" style line in WIB for the given locale. */
    static String wibLine(long epoch, Locale locale) {
        SimpleDateFormat format = new SimpleDateFormat("d MMM HH:mm", locale);
        format.setTimeZone(TimeZone.getTimeZone(TIMEZONE));
        return format.format(new Date(epoch));
    }

    /** "20:00" clock time in WIB. */
    static String wibTime(long epoch, Locale locale) {
        SimpleDateFormat format = new SimpleDateFormat("HH:mm", locale);
        format.setTimeZone(TimeZone.getTimeZone(TIMEZONE));
        return format.format(new Date(epoch));
    }

    /** "2026-11-03" date in WIB, used for the today/upcoming range query. */
    static String wibDate(long epoch) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone(TIMEZONE));
        return format.format(new Date(epoch));
    }

    /** Sort by kick-off time, oldest first; unparseable entries go last. */
    static void sort(List<Match> matches) {
        Collections.sort(matches, new Comparator<Match>() {
            @Override public int compare(Match a, Match b) {
                if (a.epoch != b.epoch) return Long.compare(a.epoch, b.epoch);
                return 0;
            }
        });
    }

    /** Most recent first; used for the results tab. */
    static void sortNewestFirst(List<Match> matches) {
        Collections.sort(matches, new Comparator<Match>() {
            @Override public int compare(Match a, Match b) {
                if (a.epoch != b.epoch) return Long.compare(b.epoch, a.epoch);
                return 0;
            }
        });
    }

    static List<Match> limit(List<Match> matches, int count) {
        if (matches.size() <= count) return matches;
        return new ArrayList<>(matches.subList(0, count));
    }

    /** Parses /competitions/{code}/matches. Returns null when the payload is not usable. */
    static List<Match> parseMatches(String json, String fallbackCompetition) {
        if (json == null || json.isEmpty()) return null;
        try {
            JSONObject root = new JSONObject(json);
            JSONArray array = root.optJSONArray("matches");
            if (array == null) return null;
            List<Match> out = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) continue;
                Match match = new Match();
                JSONObject competition = item.optJSONObject("competition");
                match.competition = competition == null ? "" : text(competition, "code");
                if (match.competition.isEmpty()) match.competition = fallbackCompetition == null ? "" : fallbackCompetition;
                JSONObject home = item.optJSONObject("homeTeam");
                JSONObject away = item.optJSONObject("awayTeam");
                match.home = teamName(home);
                match.away = teamName(away);
                if (match.home == null || match.away == null) continue;
                match.homeCrest = crest(home);
                match.awayCrest = crest(away);
                match.state = state(text(item, "status"));
                match.utcDate = text(item, "utcDate");
                match.epoch = epoch(match.utcDate);
                if (match.epoch == Long.MAX_VALUE) continue;
                match.finished = STATE_FINISHED.equals(match.state);
                JSONObject score = item.optJSONObject("score");
                if (score != null) {
                    int[] full = readScore(score.optJSONObject("fullTime"));
                    if (full == null) full = readScore(score.optJSONObject("halfTime"));
                    if (full != null) { match.homeScore = full[0]; match.awayScore = full[1]; }
                }
                out.add(match);
            }
            sort(out);
            return out;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String text(JSONObject object, String key) {
        if (object == null || !object.has(key) || object.isNull(key)) return "";
        return object.optString(key, "");
    }

    private static String crest(JSONObject team) {
        String value = text(team, "crest");
        return validCrest(value) ? value : null;
    }

    private static String teamName(JSONObject team) {
        String name = text(team, "name");
        if (name.isEmpty()) name = text(team, "shortName");
        if (name.isEmpty()) name = text(team, "tla");
        if (name.isEmpty() || name.length() > 60) return null;
        return name;
    }

    private static int[] readScore(JSONObject value) {
        if (value == null || value.isNull("home") || value.isNull("away")) return null;
        int home = value.optInt("home", -1);
        int away = value.optInt("away", -1);
        if (home < 0 || away < 0 || home > 99 || away > 99) return null;
        return new int[]{home, away};
    }

    /** Parses /competitions/{code}/standings, including group tables (Champions League). */
    static List<Row> parseStandings(String json) {
        if (json == null || json.isEmpty()) return null;
        try {
            JSONObject root = new JSONObject(json);
            JSONArray tables = root.optJSONArray("standings");
            if (tables == null) return null;
            List<Row> out = new ArrayList<>();
            for (int i = 0; i < tables.length(); i++) {
                JSONObject section = tables.optJSONObject(i);
                if (section == null) continue;
                String group = section.isNull("group") ? "" : text(section, "group");
                JSONArray rows = section.optJSONArray("table");
                if (rows == null) continue;
                for (int r = 0; r < rows.length(); r++) {
                    JSONObject value = rows.optJSONObject(r);
                    if (value == null) continue;
                    JSONObject team = value.optJSONObject("team");
                    if (team == null) continue;
                    String name = teamName(team);
                    if (name == null) continue;
                    Row row = new Row();
                    row.team = name;
                    row.crest = crest(team);
                    row.group = group;
                    row.position = value.optInt("position", -1);
                    row.played = value.optInt("playedGames", -1);
                    row.won = value.optInt("won", -1);
                    row.draw = value.optInt("draw", -1);
                    row.lost = value.optInt("lost", -1);
                    row.points = value.optInt("points", -1);
                    row.goalDifference = value.optInt("goalDifference", 0);
                    if (row.position < 1 || row.points < 0) continue;
                    out.add(row);
                }
            }
            return out.isEmpty() ? null : out;
        } catch (Exception ignored) {
            return null;
        }
    }

    /** "2 - 1" score line, or null when the provider has not published a score yet. */
    static String scoreLine(Match match) {
        if (match == null || !match.hasScore()) return null;
        return match.homeScore + " - " + match.awayScore;
    }
}
