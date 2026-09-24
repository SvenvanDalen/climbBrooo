package nl.paree.climbpro.widget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.widget.RemoteViews;

import nl.paree.climbpro.R;
import nl.paree.climbpro.data.planning.PlannedClimb;
import nl.paree.climbpro.data.planning.PlannedClimbRepository;
import nl.paree.climbpro.data.route.ClimbAttemptRepository;
import nl.paree.climbpro.data.route.RouteCatalogEntry;
import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.domain.climb.ClimbIdentity;
import nl.paree.climbpro.domain.climb.WeekSummaryCalculator;
import nl.paree.climbpro.domain.planning.PlannedClimbScheduler;
import nl.paree.climbpro.ui.planning.PlannedClimbListActivity;
import nl.paree.climbpro.ui.routes.RouteListActivity;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.time.ZoneId;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Home-screen widget (issue #259): this week's climbing totals and the next planned climb.
 * Refreshed by the system every 30 min, after every route sync ({@code RouteSyncWorker})
 * and whenever the planning list changes, via {@link #refresh}. Phone-only.
 */
public final class WeekWidgetProvider extends AppWidgetProvider {

    private static final String TAG = "WeekWidgetProvider";
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    /** Re-renders every placed instance; cheap no-op when none is placed. */
    public static void refresh(Context ctx) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
        if (mgr == null) return;
        int[] ids = mgr.getAppWidgetIds(new ComponentName(ctx, WeekWidgetProvider.class));
        if (ids == null || ids.length == 0) return;
        Intent update = new Intent(ctx, WeekWidgetProvider.class)
                .setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids);
        ctx.sendBroadcast(update);
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager mgr, int[] ids) {
        Context app = context.getApplicationContext();
        PendingResult pending = goAsync();
        EXECUTOR.execute(() -> {
            try {
                RemoteViews views = render(app);
                for (int id : ids) mgr.updateAppWidget(id, views);
            } catch (Exception e) {
                Log.w(TAG, "Widget update failed", e);
            } finally {
                pending.finish();
            }
        });
    }

    private static RemoteViews render(Context ctx) {
        ZoneId zone = ZoneId.systemDefault();
        long nowSec = System.currentTimeMillis() / 1000L;

        WeekSummaryCalculator.WeekSummary week = WeekSummaryCalculator.compute(
                new ClimbAttemptRepository(ctx).loadAll(), gainByClimbId(ctx), nowSec, zone);
        PlannedClimb next = PlannedClimbScheduler.next(
                new PlannedClimbRepository(ctx).loadAll(), nowSec);

        RemoteViews v = new RemoteViews(ctx.getPackageName(), R.layout.widget_week);
        v.setTextViewText(R.id.widget_week_total, week.label());
        if (next == null) {
            v.setTextViewText(R.id.widget_next_climb, "Geen klim gepland");
        } else {
            String when = new SimpleDateFormat("EEE d MMM HH:mm", new Locale("nl"))
                    .format(new Date(next.plannedAtEpochSec * 1000L));
            v.setTextViewText(R.id.widget_next_climb, next.displayName + " · " + when);
        }
        v.setOnClickPendingIntent(R.id.widget_root,
                activity(ctx, 0, new Intent(ctx, RouteListActivity.class)));
        v.setOnClickPendingIntent(R.id.widget_next_climb,
                activity(ctx, 1, new Intent(ctx, PlannedClimbListActivity.class)));
        return v;
    }

    private static PendingIntent activity(Context ctx, int requestCode, Intent intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(ctx, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static Map<String, Integer> gainByClimbId(Context ctx) {
        RouteRepository repo = new RouteRepository(ctx);
        Map<String, Integer> out = new HashMap<>();
        for (RouteCatalogEntry e : repo.loadCatalog()) {
            try {
                StoredRoute r = repo.loadRoute(e.routeId);
                if (r.climbs == null) continue;
                for (StoredClimb c : r.climbs) out.putIfAbsent(ClimbIdentity.of(c), c.elevationGain);
            } catch (IOException ex) {
                Log.w(TAG, "Skipping route " + e.routeId, ex);
            }
        }
        return out;
    }
}
