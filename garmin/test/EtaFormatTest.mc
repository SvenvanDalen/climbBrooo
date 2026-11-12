using Toybox.Test;

// Issue #142: ETA label next to the distance to the next climb.

(:test)
function eta_label_minutesAndHours(logger) {
    Test.assert(etaShortLabel(540).equals("9 min"));
    Test.assert(etaShortLabel(541).equals("10 min"));
    Test.assert(etaShortLabel(5).equals("1 min"));
    Test.assert(etaShortLabel(3900).equals("1h05"));
    Test.assert(etaShortLabel(7200).equals("2h00"));
    return true;
}

(:test)
function eta_label_unknownIsEmpty(logger) {
    Test.assert(etaShortLabel(-1).equals(""));
    Test.assert(etaShortLabel(null).equals(""));
    Test.assert(withEta("in 2.3 km", -1).equals("in 2.3 km"));
    Test.assert(withEta("in 2.3 km", 600).equals("in 2.3 km - 10 min"));
    return true;
}
