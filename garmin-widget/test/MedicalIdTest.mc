using Toybox.Test;
using Toybox.Application as App;
using Toybox.Application.Storage as Storage;

// Issue #230: medical ID on the watch. Covers the message parser (empty/malformed fields),
// storing + clearing via the MEDICAL_ID message, the display lines + word wrap, the view
// render/scroll bounds, and the route list's medical row offset.

(:test)
function med_fromMessage_keepsOnlyFilledStrings(logger) {
    var d = medicalIdFromMessage({ "type" => "MEDICAL_ID", "bt" => "A+", "al" => "",
                                   "ep" => 112, "ec" => "Anna" });
    Test.assert(d != null);
    Test.assert(d.get("bt").equals("A+"));
    Test.assert(d.get("ec").equals("Anna"));
    Test.assert(d.get("al") == null);
    Test.assert(d.get("ep") == null);
    return true;
}

(:test)
function med_fromMessage_noFieldsIsNull(logger) {
    Test.assert(medicalIdFromMessage({ "type" => "MEDICAL_ID" }) == null);
    Test.assert(medicalIdFromMessage(null) == null);
    Test.assert(medicalIdFromMessage("x") == null);
    return true;
}

(:test)
function med_message_storesAndClears(logger) {
    var cb = new PhoneMessageCallback();
    cb.onMessage({ "type" => "MEDICAL_ID", "bt" => "O-", "ep" => "0612345678" });
    var d = loadMedicalId();
    Test.assert(d != null && d.get("bt").equals("O-"));
    cb.onMessage({ "type" => "MEDICAL_ID" });
    Test.assert(loadMedicalId() == null);
    return true;
}

(:test)
function med_lines_labelsInOrder(logger) {
    var lines = medicalIdLines({ "ep" => "112", "bt" => "B+", "nm" => "Sven" }, 30);
    Test.assert(lines.size() == 3);
    Test.assert(lines[0].equals("Naam: Sven"));
    Test.assert(lines[1].equals("Bloedgroep: B+"));
    Test.assert(lines[2].equals("Tel: 112"));
    Test.assert(medicalIdLines(null, 30).size() == 0);
    return true;
}

(:test)
function med_wrap_breaksOnSpacesAndCutsLongWords(logger) {
    var w = wrapText("Allergie: penicilline en wespen", 12);
    Test.assert(w.size() == 3);
    Test.assert(w[0].equals("Allergie:"));
    Test.assert(w[1].equals("penicilline"));
    Test.assert(w[2].equals("en wespen"));
    var c = wrapText("abcdefghij", 4);
    Test.assert(c.size() == 3 && c[2].equals("ij"));
    Test.assert(wrapText("kort", 10).size() == 1);
    return true;
}

(:test)
function med_view_rendersAndClampsScroll(logger) {
    storeMedicalId({ "nm" => "Sven", "bt" => "A+", "al" => "Penicilline, noten en wespensteken",
                     "md" => "Geen", "ec" => "Anna", "ep" => "+31 6 12345678",
                     "nt" => "Orgaandonor, zorgverzekering bij de ANWB" });
    var v = new MedicalIdView();
    v.onShow();
    Test.assert(v.lineCount() > v.VISIBLE_LINES);
    v.onUpdate(wMakeDc());
    v.firstLine = v.maxFirstLine();
    v.onUpdate(wMakeDc());
    Test.assert(v.maxFirstLine() == v.lineCount() - v.VISIBLE_LINES);
    storeMedicalId(null);
    var empty = new MedicalIdView();
    empty.onShow();
    Test.assert(empty.maxFirstLine() == 0);
    empty.onUpdate(wMakeDc());
    return true;
}

(:test)
function med_routeList_addsFirstRowWhenStored(logger) {
    var app = App.getApp() as ClimbWidgetApp;
    app.climbData = new ClimbData();
    app.phoneRouteIndex = new PhoneRouteIndex();
    storeMedicalId(null);
    var v = new RouteListView();
    var without = v.getTotalCount();
    Test.assert(v.medicalOffset == 0);
    storeMedicalId({ "bt" => "A+" });
    v.onUpdate(wMakeDc());              // picks up the new row live
    Test.assert(v.medicalOffset == 1);
    Test.assert(v.getTotalCount() == without + 1);
    Test.assert(v.selectedIndex == 1);  // same item stays selected
    storeMedicalId(null);
    return true;
}
