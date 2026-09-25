package nl.paree.climbpro.ui.routes;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.Rect;

import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.Projection;
import org.osmdroid.views.overlay.Overlay;

import nl.paree.climbpro.domain.weather.RadarTiles;

import java.util.List;

/**
 * Semi-transparent RainViewer radar tiles (zoom ≤ 7) stretched over the route map at any map
 * zoom (issue #245). Tiles and bitmaps are parallel lists.
 */
final class RainRadarOverlay extends Overlay {

    private final List<RadarTiles.Tile> tiles;
    private final List<Bitmap> bitmaps;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Point nw = new Point();
    private final Point se = new Point();
    private final Rect dst = new Rect();

    RainRadarOverlay(List<RadarTiles.Tile> tiles, List<Bitmap> bitmaps) {
        this.tiles = tiles;
        this.bitmaps = bitmaps;
        paint.setAlpha(160);
    }

    @Override
    public void draw(Canvas canvas, Projection projection) {
        int n = Math.min(tiles.size(), bitmaps.size());
        for (int i = 0; i < n; i++) {
            RadarTiles.Tile t = tiles.get(i);
            projection.toPixels(new GeoPoint(t.north, t.west), nw);
            projection.toPixels(new GeoPoint(t.south, t.east), se);
            dst.set(nw.x, nw.y, se.x, se.y);
            canvas.drawBitmap(bitmaps.get(i), null, dst, paint);
        }
    }
}
