package nl.paree.climbpro.domain.strava;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders a free-form Strava activity-title template (issue #60) against already-available
 * attempt/climb data. Pure and phone-only — no protocol or watch change, used purely when
 * creating/updating the Strava activity after a sync.
 *
 * Supported placeholders: {@code {climb}}, {@code {time}}, {@code {delta}}, {@code {vam}}.
 * {@code {power}} is intentionally not supported in v1: the app only fetches the
 * {@code latlng,time} activity streams (see {@code StravaActivitiesRepository.STREAM_KEYS}),
 * so no per-attempt average-power value exists yet to substitute.
 */
public final class StravaTitleTemplateRenderer {

    /** Placeholder keys this renderer understands, for the settings-screen reference list. */
    public static final String[] KNOWN_PLACEHOLDERS = {"climb", "time", "delta", "vam"};

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\w+)\\}");

    private StravaTitleTemplateRenderer() {}

    /**
     * Substitutes every recognised {@code {placeholder}} in {@code template} with its value
     * from {@code context}. An unrecognised or malformed placeholder (including a future
     * {@code {power}}) is left in the output as literal text rather than dropped or causing
     * a crash, so an unsupported/typo'd template still degrades visibly.
     */
    public static String render(String template, TitleContext context) {
        if (template == null || template.isEmpty()) return "";
        Map<String, String> values = context != null ? context.values : new LinkedHashMap<>();

        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (m.find()) {
            String key = m.group(1).toLowerCase(Locale.US);
            String value = values.get(key);
            out.append(template, last, m.start());
            out.append(value != null ? value : m.group()); // unknown placeholder -> keep literal
            last = m.end();
        }
        out.append(template.substring(last));
        return out.toString();
    }

    /** Pre-formatted, ready-to-substitute values for one rendered title. */
    public static final class TitleContext {
        private final Map<String, String> values = new LinkedHashMap<>();

        private TitleContext() {}

        /**
         * @param climbName    display name of the matched climb (user rename if present).
         * @param elapsedSec   time on the climb for this attempt.
         * @param deltaToPrSec elapsedSec minus the pre-existing PR, or {@code null} when this
         *                     is the first-ever recorded attempt (renders as "PR").
         * @param vamMPerH     gradient-implied average VAM for the climb, or {@code null} when
         *                     unavailable.
         */
        public static TitleContext of(String climbName, int elapsedSec,
                                       Integer deltaToPrSec, Integer vamMPerH) {
            return of(climbName, elapsedSec, deltaToPrSec, vamMPerH, true);
        }

        /**
         * @param prEligible false for a route-deviated attempt (issue #77): it can't set a PR,
         *                   so {@code {delta}} renders empty instead of "PR" when it would
         *                   otherwise claim one; a slower-than-PR delta still renders.
         */
        public static TitleContext of(String climbName, int elapsedSec,
                                       Integer deltaToPrSec, Integer vamMPerH,
                                       boolean prEligible) {
            TitleContext ctx = new TitleContext();
            ctx.values.put("climb", climbName != null ? climbName : "");
            ctx.values.put("time", formatDuration(elapsedSec));
            boolean claimsPr = deltaToPrSec == null || deltaToPrSec <= 0;
            ctx.values.put("delta", claimsPr && !prEligible ? "" : formatDelta(deltaToPrSec));
            ctx.values.put("vam", vamMPerH != null ? vamMPerH + " m/h" : "");
            return ctx;
        }

        /** {@code totalSec} as {@code mm:ss}, or {@code h:mm:ss} once it reaches an hour. */
        public static String formatDuration(int totalSec) {
            if (totalSec < 0) totalSec = 0;
            int h = totalSec / 3600;
            int m = (totalSec % 3600) / 60;
            int s = totalSec % 60;
            return h > 0
                    ? String.format(Locale.US, "%d:%02d:%02d", h, m, s)
                    : String.format(Locale.US, "%d:%02d", m, s);
        }

        /** {@code null} or non-positive (this attempt IS the new PR) renders as {@code "PR"}. */
        public static String formatDelta(Integer deltaToPrSec) {
            if (deltaToPrSec == null || deltaToPrSec <= 0) return "PR";
            return "+" + formatDuration(deltaToPrSec);
        }
    }
}
