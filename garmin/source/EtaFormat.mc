// Issue #142: ETA next to the distance to the next climb ("in 2.3 km - 9 min"). Pure,
// unit-tested in garmin/test/EtaFormatTest.mc; the seconds come from ClimbData.etaSeconds
// (remaining distance / current speed), which returns -1 while the rider is (nearly) stopped.

// "9 min", "1h05", or "" when the ETA is unknown (< 0). Under a minute rounds up to "1 min".
function etaShortLabel(etaSec) {
    if (etaSec == null || etaSec < 0) { return ""; }
    var min = (etaSec + 59) / 60;
    if (min < 1) { min = 1; }
    if (min < 60) { return min + " min"; }
    var h = min / 60;
    var m = min % 60;
    return h + "h" + (m < 10 ? "0" : "") + m;
}

// distLabel ("in 2.3 km") plus the ETA, when known.
function withEta(distLabel, etaSec) {
    var e = etaShortLabel(etaSec);
    return e.equals("") ? distLabel : distLabel + " - " + e;
}
