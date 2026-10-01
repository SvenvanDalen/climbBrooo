using Toybox.Application.Storage as Storage;
using Toybox.Graphics as Gfx;

// Colorblind-friendly palette (issue #258, protocol/colors.md). The phone sends "pal" = 1
// in its climb payloads when the rider turned the palette on; the widget remembers the last
// live value in Storage so every screen -- including the glance, which never parses a
// payload -- draws with the rider's current choice. Saved routes replayed from storage do
// not overwrite it (see PhoneMessageCallback.replaying).
(:glance)
module WidgetPalette {

    const COLORBLIND = 1;
    const STORAGE_KEY = "pal";

    // Gradient colors per colorIndex. Same band order as ProfileDrawer.COLORS; the
    // colorblind values must match GradientPalette.java and garmin/ClimbProView.mc verbatim.
    const DEFAULT_COLORS = [
        0x99FF99, 0xFFFF00, 0xFFAA00, 0xFF5500, 0xFF0000, 0xAA0000,
    ];
    const CVD_COLORS = [
        0xFFFFAA,  // 0: pale yellow (0-2%)
        0x55FFFF,  // 1: light cyan (2-4%)
        0x55AAFF,  // 2: sky blue (4-6%)
        0x0055FF,  // 3: blue (6-8%)
        0x0000FF,  // 4: pure blue (8-10%)
        0x0000AA,  // 5: navy (10%+)
    ];

    // Status colors: blue = good/connected, orange = bad/no connection.
    const CVD_OK_COLOR = 0x00AAFF;
    const CVD_BAD_COLOR = 0xFF5500;

    // Wire "pal" -> palette: only the exact Number 1 selects the colorblind palette.
    function parse(pal) {
        return (pal != null && pal instanceof Toybox.Lang.Number && pal == COLORBLIND) ? COLORBLIND : 0;
    }

    // Last palette the phone sent; default when nothing is stored or Storage is unavailable.
    function current() {
        try {
            return parse(Storage.getValue(STORAGE_KEY));
        } catch (e) {
            return 0;
        }
    }

    function remember(palette) {
        try {
            Storage.setValue(STORAGE_KEY, parse(palette));
        } catch (e) {
            // Storage full/unavailable: keep drawing with the previous value.
        }
    }

    function gradientColors(palette) {
        return palette == COLORBLIND ? CVD_COLORS : DEFAULT_COLORS;
    }

    // defaultOk lets each screen keep its own green shade in the default palette.
    function okColor(palette, defaultOk) {
        return palette == COLORBLIND ? CVD_OK_COLOR : defaultOk;
    }

    function badColor(palette) {
        return palette == COLORBLIND ? CVD_BAD_COLOR : Gfx.COLOR_RED;
    }

    // Phone-connection dot. Default: filled green / filled red. Colorblind: filled blue /
    // orange ring, so the state doesn't depend on color alone.
    function drawConnectionDot(dc, x, y, connected, palette) {
        dc.setColor(connected ? okColor(palette, 0x00AA00) : badColor(palette), Gfx.COLOR_TRANSPARENT);
        if (connected || palette != COLORBLIND) {
            dc.fillCircle(x, y, 5);
        } else {
            dc.setPenWidth(2);
            dc.drawCircle(x, y, 4);
            dc.setPenWidth(1);
        }
    }
}
