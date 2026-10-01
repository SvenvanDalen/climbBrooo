using Toybox.Application.Storage as Storage;
using Toybox.WatchUi as Ui;
using Toybox.Graphics as Gfx;

// Issue #230: medical ID on the watch. The phone sends
//   { "type" => "MEDICAL_ID", "nm"?, "bt"?, "al"?, "md"?, "ec"?, "ep"?, "nt"? }
// (short strings, capped on the phone). The widget keeps a copy in Storage so it shows
// offline, as the first row of the route list. A message without any field deletes it.

const MEDICAL_ID_KEY = "medical_id";
const MEDICAL_ID_FIELDS = ["nm", "bt", "al", "md", "ec", "ep", "nt"];

// Message -> Dictionary with only the non-empty String fields, or null when none is set.
function medicalIdFromMessage(msg) {
    if (msg == null || !(msg instanceof Toybox.Lang.Dictionary)) { return null; }
    var out = {};
    var any = false;
    for (var i = 0; i < MEDICAL_ID_FIELDS.size(); i++) {
        var k = MEDICAL_ID_FIELDS[i];
        var v = msg.get(k);
        if (v instanceof Toybox.Lang.String && v.length() > 0) {
            out.put(k, v);
            any = true;
        }
    }
    return any ? out : null;
}

// Stores (or with null, deletes) the watch copy.
function storeMedicalId(d) {
    if (d == null) {
        Storage.deleteValue(MEDICAL_ID_KEY);
    } else {
        Storage.setValue(MEDICAL_ID_KEY, d);
    }
}

function loadMedicalId() {
    var d = Storage.getValue(MEDICAL_ID_KEY);
    return (d instanceof Toybox.Lang.Dictionary) ? d : null;
}

// Display lines for the ID, labels in Dutch, long values wrapped at maxChars.
function medicalIdLines(d, maxChars) {
    var lines = [];
    if (d == null) { return lines; }
    addMedicalLine(lines, "Naam", d.get("nm"), maxChars);
    addMedicalLine(lines, "Bloedgroep", d.get("bt"), maxChars);
    addMedicalLine(lines, "Allergie", d.get("al"), maxChars);
    addMedicalLine(lines, "Medicatie", d.get("md"), maxChars);
    addMedicalLine(lines, "Noodcontact", d.get("ec"), maxChars);
    addMedicalLine(lines, "Tel", d.get("ep"), maxChars);
    addMedicalLine(lines, "Info", d.get("nt"), maxChars);
    return lines;
}

function addMedicalLine(lines, label, value, maxChars) {
    if (value == null) { return; }
    var wrapped = wrapText(label + ": " + value, maxChars);
    for (var i = 0; i < wrapped.size(); i++) { lines.add(wrapped[i]); }
}

// Greedy word wrap into lines of at most maxChars (a longer single word is cut).
function wrapText(text, maxChars) {
    var out = [];
    var rest = text;
    while (rest.length() > maxChars) {
        var cut = -1;
        for (var i = maxChars; i > 0; i--) {
            if (rest.substring(i, i + 1).equals(" ")) { cut = i; break; }
        }
        if (cut <= 0) {
            out.add(rest.substring(0, maxChars));
            rest = rest.substring(maxChars, rest.length());
        } else {
            out.add(rest.substring(0, cut));
            rest = rest.substring(cut + 1, rest.length());
        }
    }
    if (rest.length() > 0) { out.add(rest); }
    return out;
}

// Full-screen medical ID: red header, then the lines, scrolled with up/down.
class MedicalIdView extends Ui.View {

    const LINE_CHARS = 22;
    const VISIBLE_LINES = 7;

    var firstLine = 0;
    hidden var lines = [];

    function initialize() {
        View.initialize();
    }

    function onShow() {
        lines = medicalIdLines(loadMedicalId(), LINE_CHARS);
    }

    function lineCount() {
        return lines.size();
    }

    function maxFirstLine() {
        var m = lines.size() - VISIBLE_LINES;
        return m > 0 ? m : 0;
    }

    function onUpdate(dc) {
        var w = dc.getWidth();
        var h = dc.getHeight();
        dc.setColor(Gfx.COLOR_BLACK, Gfx.COLOR_BLACK);
        dc.clear();
        dc.setColor(Gfx.COLOR_RED, Gfx.COLOR_RED);
        dc.fillRectangle(0, 0, w, 44);
        dc.setColor(Gfx.COLOR_WHITE, Gfx.COLOR_TRANSPARENT);
        dc.drawText(w / 2, 18, Gfx.FONT_XTINY, "+ MEDISCHE ID", Gfx.TEXT_JUSTIFY_CENTER);

        if (lines.size() == 0) {
            dc.drawText(w / 2, h / 2, Gfx.FONT_XTINY, "Geen gegevens",
                Gfx.TEXT_JUSTIFY_CENTER | Gfx.TEXT_JUSTIFY_VCENTER);
            return;
        }
        var y = 50;
        for (var i = firstLine; i < lines.size() && i < firstLine + VISIBLE_LINES; i++) {
            dc.drawText(w / 2, y, Gfx.FONT_XTINY, lines[i], Gfx.TEXT_JUSTIFY_CENTER);
            y += 24;
        }
    }
}

class MedicalIdDelegate extends Ui.BehaviorDelegate {

    function initialize() { BehaviorDelegate.initialize(); }

    function onNextPage() {
        var view = Ui.getCurrentView()[0];
        if (view instanceof MedicalIdView && view.firstLine < view.maxFirstLine()) {
            view.firstLine++;
            Ui.requestUpdate();
        }
        return true;
    }

    function onPreviousPage() {
        var view = Ui.getCurrentView()[0];
        if (view instanceof MedicalIdView && view.firstLine > 0) {
            view.firstLine--;
            Ui.requestUpdate();
        }
        return true;
    }

    function onBack() {
        Ui.popView(Ui.SLIDE_RIGHT);
        return true;
    }
}
