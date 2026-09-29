package nl.paree.climbpro.ui.rides;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;

import nl.paree.climbpro.domain.ride.RideStory;
import nl.paree.climbpro.domain.ride.RideTrack;
import nl.paree.climbpro.domain.ride.RouteShape;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Draws a {@link RideStory} as one shareable portrait image (issue #193): title and date,
 * headline stats, the route shape (no map tiles, so no network or attribution needed) next to
 * a photo from the ride when there is one, temperature, the climbs with PRs marked, and a
 * footer. App colours: black background, yellow accent.
 */
final class RideStoryImageComposer {

    static final int WIDTH = 1080;
    static final int HEIGHT = 1350;
    private static final float PAD = 64f;
    private static final float SHAPE_TOP = 330f;
    private static final float SHAPE_HEIGHT = 520f;

    private static final int BG = Color.BLACK;
    private static final int SURFACE = Color.parseColor("#1A1A1A");
    private static final int ACCENT = Color.parseColor("#FFD400");
    private static final int TEXT = Color.WHITE;
    private static final int TEXT_DIM = Color.parseColor("#B3B3B3");

    private RideStoryImageComposer() {}

    /**
     * @param track may be null (no GPS or not fetched): the shape box then says so
     * @param photo may be null
     */
    static Bitmap compose(RideStory story, RideTrack track, Bitmap photo) {
        Bitmap bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bitmap);
        c.drawColor(BG);

        Paint title = text(64, TEXT, true);
        Paint dim = text(36, TEXT_DIM, false);
        Paint stat = text(44, ACCENT, true);
        Paint body = text(40, TEXT, false);

        c.drawText(ellipsize(story.title, title, WIDTH - 2 * PAD), PAD, 130, title);
        if (story.startEpochSec > 0) {
            String date = new SimpleDateFormat("EEEE d MMMM yyyy", new Locale("nl"))
                    .format(new Date(story.startEpochSec * 1000L));
            c.drawText(date, PAD, 190, dim);
        }
        c.drawText(ellipsize(String.join("   ·   ", story.stats), stat, WIDTH - 2 * PAD),
                PAD, 270, stat);

        float boxRight = WIDTH - PAD;
        float shapeRight = photo != null ? WIDTH / 2f + 180 : boxRight;
        drawShape(c, track, new RectF(PAD, SHAPE_TOP, shapeRight, SHAPE_TOP + SHAPE_HEIGHT));
        if (photo != null) {
            drawPhoto(c, photo, new RectF(shapeRight + 24, SHAPE_TOP, boxRight,
                    SHAPE_TOP + SHAPE_HEIGHT));
        }

        float y = SHAPE_TOP + SHAPE_HEIGHT + 90;
        if (story.avgTempC != null) {
            c.drawText(String.format(Locale.GERMANY, "Temperatuur onderweg: %.0f °C",
                    story.avgTempC), PAD, y, dim);
            y += 70;
        }
        if (story.climbs.isEmpty()) {
            c.drawText("Geen bekende klimmen op deze rit", PAD, y, dim);
        } else {
            Paint right = text(40, TEXT, false);
            right.setTextAlign(Paint.Align.RIGHT);
            for (RideStory.ClimbLine line : story.climbs) {
                String badge = line.pr ? "PR"
                        : line.timesRidden <= 1 ? "eerste keer" : line.timesRidden + "e keer";
                Paint badgePaint = text(34, line.pr ? ACCENT : TEXT_DIM, line.pr);
                badgePaint.setTextAlign(Paint.Align.RIGHT);
                c.drawText(badge, boxRight, y, badgePaint);
                c.drawText(duration(line.elapsedSec), boxRight - 230, y, right);
                c.drawText(ellipsize(line.name, body, WIDTH - 2 * PAD - 460), PAD, y, body);
                y += 64;
            }
        }

        Paint footer = text(30, TEXT_DIM, false);
        footer.setTextAlign(Paint.Align.CENTER);
        c.drawText("ClimbPro", WIDTH / 2f, HEIGHT - 48, footer);
        return bitmap;
    }

    private static void drawShape(Canvas c, RideTrack track, RectF box) {
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setColor(SURFACE);
        c.drawRoundRect(box, 24, 24, bg);
        float inset = 48;
        float[] xy = track != null
                ? RouteShape.fit(track.lat, track.lon, box.width() - 2 * inset,
                        box.height() - 2 * inset)
                : new float[0];
        if (xy.length < 4) {
            Paint dim = text(34, TEXT_DIM, false);
            dim.setTextAlign(Paint.Align.CENTER);
            c.drawText("Geen GPS-spoor", box.centerX(), box.centerY(), dim);
            return;
        }
        float ox = box.left + inset;
        float oy = box.top + inset;
        Path path = new Path();
        path.moveTo(ox + xy[0], oy + xy[1]);
        for (int i = 2; i < xy.length; i += 2) path.lineTo(ox + xy[i], oy + xy[i + 1]);
        Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        line.setColor(ACCENT);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(8);
        line.setStrokeJoin(Paint.Join.ROUND);
        line.setStrokeCap(Paint.Cap.ROUND);
        c.drawPath(path, line);

        Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        dot.setColor(Color.parseColor("#3DDC61"));
        c.drawCircle(ox + xy[0], oy + xy[1], 14, dot);
        dot.setColor(Color.parseColor("#FF5C5C"));
        c.drawCircle(ox + xy[xy.length - 2], oy + xy[xy.length - 1], 14, dot);
    }

    /** Centre-cropped into {@code box}. */
    private static void drawPhoto(Canvas c, Bitmap photo, RectF box) {
        float boxRatio = box.width() / box.height();
        float photoRatio = photo.getWidth() / (float) photo.getHeight();
        Rect src;
        if (photoRatio > boxRatio) {
            int w = Math.round(photo.getHeight() * boxRatio);
            int x = (photo.getWidth() - w) / 2;
            src = new Rect(x, 0, x + w, photo.getHeight());
        } else {
            int h = Math.round(photo.getWidth() / boxRatio);
            int y = (photo.getHeight() - h) / 2;
            src = new Rect(0, y, photo.getWidth(), y + h);
        }
        c.save();
        Path clip = new Path();
        clip.addRoundRect(box, 24, 24, Path.Direction.CW);
        c.clipPath(clip);
        c.drawBitmap(photo, src, box, new Paint(Paint.FILTER_BITMAP_FLAG));
        c.restore();
    }

    private static Paint text(float size, int color, boolean bold) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTextSize(size);
        p.setColor(color);
        p.setFakeBoldText(bold);
        return p;
    }

    private static String ellipsize(String s, Paint p, float maxWidth) {
        if (p.measureText(s) <= maxWidth) return s;
        String out = s;
        while (out.length() > 1 && p.measureText(out + "…") > maxWidth) {
            out = out.substring(0, out.length() - 1);
        }
        return out + "…";
    }

    static String duration(int sec) {
        if (sec >= 3600) {
            return String.format(Locale.GERMANY, "%d:%02d:%02d", sec / 3600, (sec / 60) % 60, sec % 60);
        }
        return String.format(Locale.GERMANY, "%d:%02d", sec / 60, sec % 60);
    }
}
