using Toybox.Graphics as Gfx;

class ProfileDrawer {

    hidden const COLORS = [
        0x99FF99, 0xFFFF00, 0xFFAA00, 0xFF5500, 0xFF0000, 0xAA0000,
    ];
    hidden const SURFACE_COLORS = [
        0x404040, 0xC8A050, 0x8B4513, 0x909090, 0x9060C0,
    ];

    function drawProfile(dc, data, ci, x, y, w, h) {
        var totalLen = data.climbLength[ci];
        if (totalLen <= 0) { return; }
        var totalElev = data.climbElevGain[ci];
        if (totalElev <= 0) { totalElev = 1; }
        var segCount = data.segCount[ci];
        if (segCount <= 0) { return; }

        var baseline = y + h - 3;
        var stepW = w.toFloat() / segCount.toFloat();
        var cumElev = 0;

        for (var s = 0; s < segCount; s++) {
            var segE = data.segElevGain[ci][s];
            var colorIdx = data.segColor[ci][s];
            if (colorIdx < 0) { colorIdx = 0; }
            if (colorIdx > 5) { colorIdx = 5; }

            var x1 = x + (s * stepW).toNumber();
            var x2 = x + ((s + 1) * stepW).toNumber();
            if (x2 <= x1) { x2 = x1 + 1; }

            var y1 = baseline - ((cumElev * h) / totalElev);
            var y2 = baseline - (((cumElev + segE) * h) / totalElev);

            dc.setColor(COLORS[colorIdx], Gfx.COLOR_TRANSPARENT);
            var width = x2 - x1;
            for (var px = 0; px < width; px++) {
                var interp = px.toFloat() / width.toFloat();
                var py = (y1 + ((y2 - y1) * interp)).toNumber();
                if (py < y) { py = y; }
                if (py > baseline) { py = baseline; }
                dc.fillRectangle(x1 + px, py, 1, baseline - py);
            }
            dc.setColor(Gfx.COLOR_BLACK, Gfx.COLOR_TRANSPARENT);
            dc.drawLine(x1, y1, x2, y2);
            cumElev += segE;
        }

        dc.setColor(Gfx.COLOR_BLACK, Gfx.COLOR_TRANSPARENT);
        dc.drawLine(x, baseline, x + w, baseline);
    }

    function drawSurfaceBar(dc, data, ci, barX, barY, barWidth) {
        var segCnt = data.segCount[ci];
        if (segCnt <= 0) { return; }
        var allUnknown = true;
        for (var s = 0; s < segCnt; s++) {
            if (data.segSurf[ci][s] != 5) { allUnknown = false; break; }
        }
        if (allUnknown) { return; }

        var segW = barWidth / segCnt;
        if (segW < 1) { segW = 1; }
        for (var s = 0; s < segCnt; s++) {
            var surf = data.segSurf[ci][s];
            if (surf < 0 || surf >= SURFACE_COLORS.size()) { continue; }
            dc.setColor(SURFACE_COLORS[surf], Gfx.COLOR_TRANSPARENT);
            var bx = barX + s * segW;
            var bw = (s == segCnt - 1) ? (barX + barWidth - bx) : segW;
            dc.fillRectangle(bx, barY, bw, 5);
        }
    }
}
