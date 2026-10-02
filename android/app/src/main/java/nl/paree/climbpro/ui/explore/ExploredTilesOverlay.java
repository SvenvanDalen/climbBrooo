package nl.paree.climbpro.ui.explore;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Point;

import org.osmdroid.util.BoundingBox;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.Projection;
import org.osmdroid.views.overlay.Overlay;

/**
 * Draws explored grid cells (issue #194) as filled rectangles. One overlay for all cells
 * instead of a Polygon per cell: only the cells inside the visible box are projected, so
 * thousands of cells stay smooth while panning.
 */
final class ExploredTilesOverlay extends Overlay {

    /** Cells as {south, west, north, east} per cell, flattened. */
    private final double[] bounds;
    private final Paint paint = new Paint();
    private final GeoPoint scratch = new GeoPoint(0.0, 0.0);
    private final Point topLeft = new Point();
    private final Point bottomRight = new Point();

    ExploredTilesOverlay(double[] bounds, int color) {
        this.bounds = bounds;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        paint.setAntiAlias(false);
    }

    @Override
    public void draw(Canvas canvas, MapView mapView, boolean shadow) {
        if (shadow || bounds.length == 0) return;
        Projection p = mapView.getProjection();
        BoundingBox box = p.getBoundingBox();
        double south = box.getLatSouth();
        double north = box.getLatNorth();
        double west = box.getLonWest();
        double east = box.getLonEast();
        for (int i = 0; i + 3 < bounds.length; i += 4) {
            double s = bounds[i];
            double w = bounds[i + 1];
            double n = bounds[i + 2];
            double e = bounds[i + 3];
            if (n < south || s > north || e < west || w > east) continue;
            scratch.setCoords(n, w);
            p.toPixels(scratch, topLeft);
            scratch.setCoords(s, e);
            p.toPixels(scratch, bottomRight);
            // At low zoom a cell is smaller than a pixel: keep it visible.
            int right = Math.max(bottomRight.x, topLeft.x + 2);
            int bottom = Math.max(bottomRight.y, topLeft.y + 2);
            canvas.drawRect(topLeft.x, topLeft.y, right, bottom, paint);
        }
    }
}
