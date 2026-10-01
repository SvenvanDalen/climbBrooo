using Toybox.Lang;

// Stat-slot layout for the datafield's "HUIDIGE KLIM" page, chosen in the phone app and sent
// as the optional top-level 'lay' key. Codes must match domain/watch/WatchFieldLayout.java and
// protocol/schema.json. Kept free of Dc/Properties access so tests can call it directly.
module FieldLayout {

    const SLOT_COUNT = 5;

    const REM_DIST = 0;
    const REM_ELEV = 1;
    const CUR_GRAD = 2;
    const AVG_GRAD = 3;
    const VAM = 4;
    const ETA = 5;
    const GHOST = 6;
    const BLOCK = 7;
    const SPEED = 8;
    const HEART_RATE = 9;
    const POWER = 10;
    const CADENCE = 11;
    const ELAPSED = 12;
    const EMPTY = 13;
    const AUTO_ROW4 = 14;     // interval block, else VAM (the pre-layout row 4)
    const AUTO_BOTTOM = 15;   // vs PR / vs plan, else ETA (the pre-layout bottom line)
    const MAX_CODE = 15;

    // [left, middle, right, row 4, bottom line] -- the screen as it was before 'lay'.
    function defaults() {
        return [REM_DIST, REM_ELEV, CUR_GRAD, AUTO_ROW4, AUTO_BOTTOM];
    }

    // Missing, non-array or wrong-length 'lay' -> full default; a bad element -> that
    // slot's default. Never throws: a malformed payload must not blank the datafield.
    function parse(raw) {
        var out = defaults();
        if (raw == null || !(raw instanceof Toybox.Lang.Array) || raw.size() != SLOT_COUNT) {
            return out;
        }
        for (var i = 0; i < SLOT_COUNT; i++) {
            var c = raw[i];
            if (c != null && c instanceof Toybox.Lang.Number && c >= 0 && c <= MAX_CODE) {
                out[i] = c;
            }
        }
        return out;
    }

    // Text for the plain metrics. GHOST, BLOCK and the AUTO_* codes need colour or a
    // fallback and are drawn by the view, so they return null; EMPTY returns "".
    // v: values the view gathers once per redraw. wide: centred slot (middle, row 4,
    // bottom line) -- the narrow side slots get the compact form.
    function metricText(code, v, wide) {
        if (code == REM_DIST) { return formatDist(v[:remaining]); }
        if (code == REM_ELEV) { return v[:remElev] + "m↑"; }
        if (code == CUR_GRAD) { return formatGrad(v[:curGrad]); }
        if (code == AVG_GRAD) { return "~" + formatGrad(v[:avgGrad]); }
        if (code == VAM) {
            if (!v[:hasVam]) { return "--"; }
            return wide ? "VAM " + v[:vamAvg] + "/" + v[:vamPeak] : "" + v[:vamAvg];
        }
        if (code == ETA) { return "ETA " + formatEta(v[:etaSec]); }
        if (code == SPEED) { return (v[:speedMps] * 3.6).format("%.1f") + "km/u"; }
        if (code == HEART_RATE) { return v[:hr] == null ? "--" : v[:hr] + "bpm"; }
        if (code == POWER) { return v[:power] == null ? "--" : v[:power].toNumber() + "W"; }
        if (code == CADENCE) { return v[:cadence] == null ? "--" : v[:cadence] + "rpm"; }
        if (code == ELAPSED) { return formatElapsed(v[:timerMs]); }
        if (code == EMPTY) { return ""; }
        return null;
    }

    function formatDist(meters) {
        if (meters >= 1000) {
            var km = meters / 1000;
            var hm = (meters % 1000) / 100;
            return km + "." + hm + "km";
        }
        return meters + "m";
    }

    // Whole-seconds ETA as "m:ss"; a negative value (speed too low/unknown, see
    // ClimbData.etaSeconds) renders as a placeholder rather than a bogus duration.
    function formatEta(seconds) {
        if (seconds < 0) { return "--:--"; }
        var m = seconds / 60;
        var s = seconds % 60;
        return m + ":" + (s < 10 ? "0" + s : "" + s);
    }

    // Fixed-point gradient (pct x 10) as "7.4%".
    function formatGrad(fp) {
        var whole = fp / 10;
        var frac = fp % 10;
        if (frac < 0) { frac = -frac; }
        return whole + "." + frac + "%";
    }

    // Activity timer (ms) as "h:mm:ss".
    function formatElapsed(ms) {
        var total = ms / 1000;
        var h = total / 3600;
        var m = (total % 3600) / 60;
        var s = total % 60;
        return h + ":" + (m < 10 ? "0" + m : "" + m) + ":" + (s < 10 ? "0" + s : "" + s);
    }
}
