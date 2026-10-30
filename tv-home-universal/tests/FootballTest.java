package io.github.tvhome.universal;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Host checks for the football widget logic: status mapping, WIB times, parsing and URL safety. */
public final class FootballTest {
    static void check(boolean ok) { if (!ok) throw new AssertionError(); }

    private static final String MATCHES = "{\"matches\":["
            + "{\"id\":1,\"utcDate\":\"2026-11-03T20:00:00Z\",\"status\":\"TIMED\","
            + "\"competition\":{\"code\":\"PL\"},"
            + "\"homeTeam\":{\"name\":\"Arsenal FC\",\"crest\":\"https://crests.football-data.org/57.png\"},"
            + "\"awayTeam\":{\"name\":\"Chelsea FC\",\"crest\":\"https://crests.football-data.org/61.png\"},"
            + "\"score\":{\"winner\":null,\"fullTime\":{\"home\":null,\"away\":null}}},"
            + "{\"id\":2,\"utcDate\":\"2026-11-01T15:00:00Z\",\"status\":\"FINISHED\","
            + "\"competition\":{\"code\":\"PD\"},"
            + "\"homeTeam\":{\"name\":\"Real Madrid\"},\"awayTeam\":{\"name\":\"Barcelona\"},"
            + "\"score\":{\"winner\":\"HOME_TEAM\",\"fullTime\":{\"home\":2,\"away\":1}}},"
            + "{\"id\":3,\"utcDate\":\"2026-11-03T21:00:00Z\",\"status\":\"IN_PLAY\","
            + "\"competition\":{\"code\":\"CL\"},"
            + "\"homeTeam\":{\"name\":\"Inter\"},\"awayTeam\":{\"name\":\"Bayern\"},"
            + "\"score\":{\"fullTime\":{\"home\":1,\"away\":0}}}"
            + "]}";

    private static final String STANDINGS = "{\"standings\":[{\"stage\":\"TOTAL\",\"type\":\"TOTAL\","
            + "\"group\":null,\"table\":["
            + "{\"position\":1,\"team\":{\"name\":\"Liverpool FC\","
            + "\"crest\":\"https://crests.football-data.org/64.png\"},\"playedGames\":10,"
            + "\"won\":8,\"draw\":1,\"lost\":1,\"points\":25,\"goalDifference\":15},"
            + "{\"position\":2,\"team\":{\"name\":\"Arsenal FC\"},\"playedGames\":10,"
            + "\"won\":7,\"draw\":2,\"lost\":1,\"points\":23,\"goalDifference\":12}]}]}";

    private static final String GROUPED = "{\"standings\":[{\"stage\":\"GROUP_STAGE\",\"type\":\"GROUP\","
            + "\"group\":\"Group A\",\"table\":[{\"position\":1,\"team\":{\"name\":\"Inter\"},"
            + "\"playedGames\":3,\"won\":3,\"draw\":0,\"lost\":0,\"points\":9,\"goalDifference\":6}]}]}";

    public static void main(String[] args) {
        // Provider status -> the five states the widget can show.
        check(FootballData.state("SCHEDULED").equals(FootballData.STATE_NOT_STARTED));
        check(FootballData.state("TIMED").equals(FootballData.STATE_NOT_STARTED));
        check(FootballData.state("IN_PLAY").equals(FootballData.STATE_LIVE));
        check(FootballData.state("PAUSED").equals(FootballData.STATE_PAUSED));
        check(FootballData.state("FINISHED").equals(FootballData.STATE_FINISHED));
        check(FootballData.state("AWARDED").equals(FootballData.STATE_FINISHED));
        check(FootballData.state("POSTPONED").equals(FootballData.STATE_POSTPONED));
        check(FootballData.state("CANCELLED").equals(FootballData.STATE_CANCELLED));
        check(FootballData.state("SUSPENDED").equals(FootballData.STATE_SUSPENDED));
        check(FootballData.state(null).equals(FootballData.STATE_UNKNOWN));
        check(FootballData.state("SOMETHING_NEW").equals(FootballData.STATE_UNKNOWN));

        // UTC -> Asia/Jakarta (WIB) conversion: 13:00Z is 20:00 in Jakarta.
        long epoch = FootballData.epoch("2026-11-03T13:00:00Z");
        check(epoch != Long.MAX_VALUE);
        check(FootballData.wibTime(epoch, Locale.US).equals("20:00"));
        check(FootballData.wibLine(epoch, Locale.US).equals("3 Nov 20:00"));
        check(FootballData.wibDate(epoch).equals("2026-11-03"));
        check(FootballData.epoch("not-a-date") == Long.MAX_VALUE);
        check(FootballData.epoch(null) == Long.MAX_VALUE);

        // Match parsing: order, states, scores and crests.
        List<FootballData.Match> matches = FootballData.parseMatches(MATCHES, null);
        check(matches != null && matches.size() == 3);
        check(matches.get(0).epoch <= matches.get(1).epoch && matches.get(1).epoch <= matches.get(2).epoch);
        FootballData.Match first = FootballData.parseMatches(MATCHES, null).get(0);
        check(first.competition.equals("PD"));
        check(first.state.equals(FootballData.STATE_FINISHED));
        check(FootballData.scoreLine(first).equals("2 - 1"));
        check(first.home.equals("Real Madrid") && first.away.equals("Barcelona"));

        List<FootballData.Match> parsed = FootballData.parseMatches(MATCHES, null);
        for (FootballData.Match match : parsed) {
            if (match.competition.equals("PL")) {
                check(match.state.equals(FootballData.STATE_NOT_STARTED));
                check(FootballData.scoreLine(match) == null);       // no score published yet
                check(match.homeCrest != null && match.awayCrest != null);
            }
            if (match.competition.equals("CL")) {
                check(match.state.equals(FootballData.STATE_LIVE));
                check(FootballData.scoreLine(match).equals("1 - 0"));
                check(match.homeCrest == null);                     // no crest in the payload
            }
        }

        // Fallback competition code and unusable payloads.
        String noCode = "{\"matches\":[{\"utcDate\":\"2026-11-03T20:00:00Z\",\"status\":\"TIMED\","
                + "\"homeTeam\":{\"name\":\"A\"},\"awayTeam\":{\"name\":\"B\"}}]}";
        List<FootballData.Match> fallback = FootballData.parseMatches(noCode, "CL");
        check(fallback != null && fallback.size() == 1 && fallback.get(0).competition.equals("CL"));
        check(FootballData.parseMatches(null, null) == null);
        check(FootballData.parseMatches("", null) == null);
        check(FootballData.parseMatches("not json at all", null) == null);
        check(FootballData.parseMatches("{}", null) == null);
        List<FootballData.Match> badDate = FootballData.parseMatches(
                "{\"matches\":[{\"utcDate\":\"bad\"}]}", null);
        check(badDate != null && badDate.isEmpty());   // unusable dates are dropped, never guessed

        // Standings parsing, including a Champions League group table.
        List<FootballData.Row> table = FootballData.parseStandings(STANDINGS);
        check(table != null && table.size() == 2);
        check(table.get(0).position == 1 && table.get(0).points == 25);
        check(table.get(0).team.equals("Liverpool FC"));
        check(table.get(0).crest != null);
        check(table.get(1).goalDifference == 12 && table.get(1).crest == null);
        check(table.get(0).group == null || table.get(0).group.isEmpty());
        List<FootballData.Row> grouped = FootballData.parseStandings(GROUPED);
        check(grouped != null && grouped.size() == 1);
        check("Group A".equals(grouped.get(0).group));
        check(FootballData.parseStandings("{}") == null);
        check(FootballData.parseStandings("garbage") == null);
        check(FootballData.parseStandings(null) == null);

        // Crest URLs: only https from the provider CDN.
        check(FootballData.validCrest("https://crests.football-data.org/57.png"));
        check(FootballData.validCrest("https://crests.football-data.org/61.png"));
        check(!FootballData.validCrest("http://crests.football-data.org/57.png"));
        check(!FootballData.validCrest("https://evil.example.com/57.png"));
        check(!FootballData.validCrest("https://user@crests.football-data.org/57.png"));
        check(!FootballData.validCrest("https://crests.football-data.org:8443/57.png"));
        check(!FootballData.validCrest(null));
        check(!FootballData.validCrest("javascript:alert(1)"));

        // Results tab ordering helper.
        List<FootballData.Match> results = FootballData.parseMatches(MATCHES, null);
        FootballData.sortNewestFirst(results);
        check(results.get(0).epoch >= results.get(results.size() - 1).epoch);
        check(FootballData.limit(results, 2).size() == 2);
        check(FootballData.limit(results, 10).size() == results.size());

        System.out.println("PASS: football status mapping, WIB times, match/standings parsing and crest safety.");
    }

    private FootballTest() { }
}
