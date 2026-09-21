package nl.paree.climbpro.ui.climbs;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

import java.util.Locale;

import nl.paree.climbpro.data.route.StoredClimb;

/**
 * Composes a shareable summary image of a completed/viewed climb: title, headline stats
 * (length, average gradient, elevation gain, optional duration) and the existing
 * {@link ClimbProfileView} profile chart, stacked into a single {@link Bitmap}.
 *
 * <p>Phone-only, purely additive (issue #33 "Klim exporteren als deelbare afbeelding") —
 * reuses the existing profile rendering rather than duplicating chart logic, no wire-format
 * or watch impact. The resulting bitmap is handed to {@link ClimbShareHandoff} for sharing.
 */
public final class ClimbShareImageComposer {

    static final int WIDTH = 1080;
    static final int HEADER_HEIGHT = 220;
    static final int PROFILE_HEIGHT = 480;
    static final int FOOTER_HEIGHT = 80;
    static final int TOTAL_HEIGHT = HEADER_HEIGHT + PROFILE_HEIGHT + FOOTER_HEIGHT;

    private ClimbShareImageComposer() {}

    /**
     * @param title            climb display name (user-renamed name if present), never null
     * @param climb            the climb to render; a null or segment-less climb still yields
     *                         a valid image with just the header/footer
     * @param timeEstimateText optional formatted duration/effort string (e.g. "23:40 · 210 W"),
     *                         or null to omit the duration line
     */
    public static Bitmap compose(Context ctx, String title, StoredClimb climb, String timeEstimateText) {
        Bitmap bitmap = Bitmap.createBitmap(WIDTH, TOTAL_HEIGHT, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.WHITE);

        drawHeader(canvas, title, climb, timeEstimateText);
        drawProfile(ctx, canvas, climb);
        drawFooter(canvas);

        return bitmap;
    }

    private static void drawHeader(Canvas canvas, String title, StoredClimb climb, String timeEstimateText) {
        float density = 3f; // fixed density for a headless-composed image (independent of screen)
        Paint titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        titlePaint.setColor(Color.parseColor("#212121"));
        titlePaint.setTextSize(40f * density / 2f);
        titlePaint.setFakeBoldText(true);
        titlePaint.setTextAlign(Paint.Align.LEFT);

        Paint statsPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        statsPaint.setColor(Color.parseColor("#424242"));
        statsPaint.setTextSize(26f * density / 2f);
        statsPaint.setTextAlign(Paint.Align.LEFT);

        float padLeft = 48f;
        float titleBaseline = 96f;
        canvas.drawText(title != null ? title : "Klim", padLeft, titleBaseline, titlePaint);

        String stats = climb != null ? formatStats(climb) : "";
        canvas.drawText(stats, padLeft, titleBaseline + 60f, statsPaint);

        if (timeEstimateText != null && !timeEstimateText.isEmpty()) {
            canvas.drawText(timeEstimateText, padLeft, titleBaseline + 110f, statsPaint);
        }
    }

    private static String formatStats(StoredClimb climb) {
        String lengthLabel = climb.length >= 1000
                ? String.format(Locale.US, "%.1f km", climb.length / 1000f)
                : climb.length + " m";
        return String.format(Locale.US, "%s · %.1f%% gem. · %d m stijging",
                lengthLabel, climb.avgGradient * 100, climb.elevationGain);
    }

    private static void drawProfile(Context ctx, Canvas canvas, StoredClimb climb) {
        ClimbProfileView profileView = new ClimbProfileView(ctx);
        profileView.setSegments(climb != null ? climb.segments : null);

        int widthSpec = View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY);
        int heightSpec = View.MeasureSpec.makeMeasureSpec(PROFILE_HEIGHT, View.MeasureSpec.EXACTLY);
        profileView.measure(widthSpec, heightSpec);
        profileView.layout(0, 0, WIDTH, PROFILE_HEIGHT);

        canvas.save();
        canvas.translate(0, HEADER_HEIGHT);
        profileView.draw(canvas);
        canvas.restore();
    }

    private static void drawFooter(Canvas canvas) {
        Paint footerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        footerPaint.setColor(Color.parseColor("#9E9E9E"));
        footerPaint.setTextSize(22f);
        footerPaint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText("ClimbPro", WIDTH / 2f, HEADER_HEIGHT + PROFILE_HEIGHT + FOOTER_HEIGHT / 2f + 8f,
                footerPaint);
    }
}
