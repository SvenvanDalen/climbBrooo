package nl.paree.climbpro.ui.climbs;

import android.app.Activity;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredClimbAttempt;
import nl.paree.climbpro.data.route.StoredSegment;
import nl.paree.climbpro.domain.climb.ClimbPacing;
import nl.paree.climbpro.domain.climb.LogbookCalculator;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Power on an attempt in the climb detail history: the summary line (issue #384), the pacing
 * verdict (issue #386) and the per-segment table with a colored bar (issue #385).
 */
final class AttemptPowerViews {

    private AttemptPowerViews() {}

    /** "⚡ 250 W · NP 262 W · 3.31 W/kg · ❤ 160 bpm · 82 rpm"; null without any of them. */
    static String summary(LogbookCalculator.HistoryRow row) {
        StoredClimbAttempt a = row.attempt;
        if (a == null) return null;
        List<String> parts = new ArrayList<>();
        if (a.avgWatts != null && a.avgWatts > 0) parts.add(a.avgWatts + " W");
        if (a.normalizedPower != null) parts.add("NP " + a.normalizedPower + " W");
        if (row.wattsPerKg != null) {
            parts.add(String.format(Locale.getDefault(), "%.2f W/kg", row.wattsPerKg));
        }
        if (a.avgHeartrate != null) parts.add("❤ " + a.avgHeartrate + " bpm");
        if (a.avgCadence != null) parts.add(a.avgCadence + " rpm");
        return parts.isEmpty() ? null : "⚡ " + String.join(" · ", parts);
    }

    /** "Pacing: te hard gestart" or null without per-segment power. */
    static String pacing(LogbookCalculator.HistoryRow row) {
        ClimbPacing.Result r = pacingOf(row);
        return r == null ? null : "Pacing: " + ClimbPacing.label(r.verdict);
    }

    private static ClimbPacing.Result pacingOf(LogbookCalculator.HistoryRow row) {
        return row.attempt == null ? null
                : ClimbPacing.analyze(row.attempt.segWatts, row.attempt.segSplitSec);
    }

    static boolean hasSegments(LogbookCalculator.HistoryRow row) {
        StoredClimbAttempt a = row.attempt;
        return a != null && a.segSplitSec != null
                && (a.segWatts != null || a.segHeartrate != null || a.segCadence != null);
    }

    /** One table line per segment: "3  6.2 %  2:05  290 W (+12 %)  3.8 W/kg  165 bpm  78 rpm". */
    static String segmentLine(LogbookCalculator.HistoryRow row, StoredClimb climb, int k,
                              ClimbPacing.Result pacing) {
        StoredClimbAttempt a = row.attempt;
        if (a == null || a.segSplitSec == null) return "";
        StringBuilder sb = new StringBuilder();
        sb.append(k + 1);
        StoredSegment seg = segment(climb, k, a.segSplitSec.length);
        if (seg != null) sb.append(String.format(Locale.getDefault(), "  %.1f %%", seg.gradient));
        int sec = a.segSplitSec[k];
        sb.append(String.format(Locale.getDefault(), "  %d:%02d", sec / 60, sec % 60));
        int w = value(a.segWatts, k);
        if (w > 0) {
            sb.append("  ").append(w).append(" W");
            if (pacing != null && !Double.isNaN(pacing.deviationPct[k])) {
                sb.append(String.format(Locale.getDefault(), " (%+.0f %%)", pacing.deviationPct[k]));
                if (pacing.flagged(k)) sb.append(" !");
            }
            if (row.wattsPerKg != null && a.avgWatts != null && a.avgWatts > 0) {
                double kg = a.avgWatts / row.wattsPerKg;
                sb.append(String.format(Locale.getDefault(), "  %.2f W/kg", w / kg));
            }
        }
        int hr = value(a.segHeartrate, k);
        if (hr > 0) sb.append("  ").append(hr).append(" bpm");
        int cad = value(a.segCadence, k);
        if (cad > 0) sb.append("  ").append(cad).append(" rpm");
        return sb.toString();
    }

    private static int value(int[] values, int k) {
        return values != null && k < values.length ? values[k] : 0;
    }

    /** The climb's segment {@code k}, only when the attempt used the same segmentation. */
    private static StoredSegment segment(StoredClimb climb, int k, int attemptSegments) {
        if (climb == null || climb.segments == null || climb.segments.size() != attemptSegments) {
            return null;
        }
        return climb.segments.get(k);
    }

    static void showSegments(Activity activity, LogbookCalculator.HistoryRow row,
                             StoredClimb climb) {
        float density = activity.getResources().getDisplayMetrics().density;
        int pad = (int) (16 * density);
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, 0);
        ClimbPacing.Result pacing = pacingOf(row);
        if (pacing != null) {
            text(activity, box, String.format(Locale.getDefault(),
                    "Gemiddeld %d W — %s. ! = meer dan %.0f %% van het gemiddelde.",
                    pacing.avgWatts, ClimbPacing.label(pacing.verdict), ClimbPacing.FLAG_PCT));
        }
        box.addView(bar(activity, row, climb, density));
        TextView header = text(activity, box, "Seg  helling  tijd  vermogen  W/kg  hartslag  cadans");
        header.setTypeface(Typeface.DEFAULT_BOLD);
        int segCount = row.attempt == null || row.attempt.segSplitSec == null
                ? 0 : row.attempt.segSplitSec.length;
        for (int k = 0; k < segCount; k++) {
            TextView line = text(activity, box, segmentLine(row, climb, k, pacing));
            line.setTypeface(Typeface.MONOSPACE);
        }
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(box);
        new AlertDialog.Builder(activity)
                .setTitle("Per segment")
                .setView(scroll)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private static TextView text(Activity activity, ViewGroup parent, String s) {
        TextView t = new TextView(activity);
        t.setText(s);
        t.setTextSize(13f);
        t.setPadding(0, 4, 0, 0);
        parent.addView(t);
        return t;
    }

    /**
     * One block per segment, as wide as the time it took and colored by the segment's
     * gradient class (gray when the climb was re-segmented since).
     */
    private static View bar(Activity activity, LogbookCalculator.HistoryRow row,
                            StoredClimb climb, float density) {
        LinearLayout bar = new LinearLayout(activity);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        int[] splits = row.attempt == null || row.attempt.segSplitSec == null
                ? new int[0] : row.attempt.segSplitSec;
        for (int k = 0; k < splits.length; k++) {
            View block = new View(activity);
            StoredSegment seg = segment(climb, k, splits.length);
            block.setBackgroundColor(seg != null
                    ? SegmentColorPalette.toColor(seg.colorIndex) : 0xFF9E9E9E);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    (int) (14 * density), (float) Math.max(1, splits[k]));
            lp.setMargins(0, 0, (int) density, 0);
            bar.addView(block, lp);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, (int) (8 * density), 0, (int) (8 * density));
        bar.setLayoutParams(lp);
        return bar;
    }
}
