package nl.paree.climbpro.ui.routes;

import android.content.Context;
import android.content.Intent;

import nl.paree.climbpro.R;
import nl.paree.climbpro.ui.settings.SettingsActivity;
import nl.paree.climbpro.ui.strava.StravaAuthActivity;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/** The route list's overflow-menu items that just open another screen. */
final class RouteListMenuScreens {

    private static final Map<Integer, Function<Context, Intent>> SCREENS = new HashMap<>();

    static {
        SCREENS.put(R.id.action_strava, c -> new Intent(c, StravaAuthActivity.class));
        SCREENS.put(R.id.action_logbook, c -> new Intent(c,
                nl.paree.climbpro.ui.climbs.ClimbLogbookActivity.class));
        SCREENS.put(R.id.action_planning, c -> new Intent(c,
                nl.paree.climbpro.ui.planning.PlannedClimbListActivity.class));
        SCREENS.put(R.id.action_elevation_target,
                nl.paree.climbpro.ui.planning.ElevationTargetActivity::intentFor);
        SCREENS.put(R.id.action_loop_generator,
                nl.paree.climbpro.ui.planning.LoopGeneratorActivity::intentFor);
        SCREENS.put(R.id.action_climb_of_the_week,
                nl.paree.climbpro.ui.climbs.ClimbOfTheWeekActivity::intentFor);
        SCREENS.put(R.id.action_top_climbs, nl.paree.climbpro.ui.climbs.TopClimbsActivity::intentFor);
        SCREENS.put(R.id.action_favorite_start_points,
                nl.paree.climbpro.ui.planning.FavoriteStartPointsActivity::intentFor);
        SCREENS.put(R.id.action_timeline, c -> new Intent(c,
                nl.paree.climbpro.ui.climbs.ClimbTimelineActivity.class));
        SCREENS.put(R.id.action_rides, nl.paree.climbpro.ui.rides.RideArchiveActivity::intentFor);
        SCREENS.put(R.id.action_records, nl.paree.climbpro.ui.records.RideRecordsActivity::intentFor);
        SCREENS.put(R.id.action_friend_feed, nl.paree.climbpro.ui.social.FriendFeedActivity::intentFor);
        SCREENS.put(R.id.action_ride_buddies, nl.paree.climbpro.ui.social.RideBuddyActivity::intentFor);
        SCREENS.put(R.id.action_group_ride_planner,
                nl.paree.climbpro.ui.social.GroupRidePlannerActivity::intentFor);
        SCREENS.put(R.id.action_hr_drift, nl.paree.climbpro.ui.records.HeartRateDriftActivity::intentFor);
        SCREENS.put(R.id.action_mywhoosh, nl.paree.climbpro.ui.mywhoosh.MyWhooshActivity::intentFor);
        SCREENS.put(R.id.action_power_curve, nl.paree.climbpro.ui.records.PowerCurveActivity::intentFor);
        SCREENS.put(R.id.action_ftp_test, nl.paree.climbpro.ui.records.FtpTestActivity::intentFor);
        SCREENS.put(R.id.action_zone_distribution,
                nl.paree.climbpro.ui.records.ZoneDistributionActivity::intentFor);
        SCREENS.put(R.id.action_tire_pressure_log,
                nl.paree.climbpro.ui.tire.TirePressureLogActivity::intentFor);
        SCREENS.put(R.id.action_saddle_height, nl.paree.climbpro.ui.fit.SaddleHeightActivity::intentFor);
        SCREENS.put(R.id.action_battery_status, nl.paree.climbpro.ui.battery.BatteryActivity::intentFor);
        SCREENS.put(R.id.action_pain_log, nl.paree.climbpro.ui.pain.PainLogActivity::intentFor);
        SCREENS.put(R.id.action_sweat_loss, nl.paree.climbpro.ui.hydration.SweatLossActivity::intentFor);
        SCREENS.put(R.id.action_safe_home, nl.paree.climbpro.ui.safehome.SafeHomeActivity::intentFor);
        SCREENS.put(R.id.action_medical_id, nl.paree.climbpro.ui.medical.MedicalIdActivity::intentFor);
        SCREENS.put(R.id.action_sunscreen, nl.paree.climbpro.ui.sunscreen.SunscreenActivity::intentFor);
        SCREENS.put(R.id.action_clothing, nl.paree.climbpro.ui.clothing.ClothingActivity::intentFor);
        SCREENS.put(R.id.action_air_quality,
                nl.paree.climbpro.ui.airquality.AirQualityActivity::intentFor);
        SCREENS.put(R.id.action_comeback_plan,
                nl.paree.climbpro.ui.comeback.ComebackPlanActivity::intentFor);
        SCREENS.put(R.id.action_unfinished_climbs, c -> new Intent(c,
                nl.paree.climbpro.ui.climbs.UnfinishedClimbsActivity.class));
        SCREENS.put(R.id.action_wrapped, c -> new Intent(c,
                nl.paree.climbpro.ui.wrapped.ClimbWrappedActivity.class));
        SCREENS.put(R.id.action_recovery, c -> new Intent(c,
                nl.paree.climbpro.ui.recovery.RecoveryAdviceActivity.class));
        SCREENS.put(R.id.action_periodization,
                nl.paree.climbpro.ui.training.ClimbPeriodizationActivity::intentFor);
        SCREENS.put(R.id.action_fitness, nl.paree.climbpro.ui.fitness.FitnessActivity::intentFor);
        SCREENS.put(R.id.action_training_load_calendar,
                nl.paree.climbpro.ui.fitness.TrainingLoadCalendarActivity::intentFor);
        SCREENS.put(R.id.action_photo_quiz, nl.paree.climbpro.ui.quiz.PhotoQuizActivity::intentFor);
        SCREENS.put(R.id.action_collections,
                nl.paree.climbpro.ui.collections.CollectionListActivity::intentFor);
        SCREENS.put(R.id.action_maintenance,
                nl.paree.climbpro.ui.maintenance.MaintenanceActivity::intentFor);
        SCREENS.put(R.id.action_torque, nl.paree.climbpro.ui.maintenance.TorqueActivity::intentFor);
        SCREENS.put(R.id.action_frame_size, nl.paree.climbpro.ui.frame.FrameSizeActivity::intentFor);
        SCREENS.put(R.id.action_bike_garage, nl.paree.climbpro.ui.bike.BikeGarageActivity::intentFor);
        SCREENS.put(R.id.action_bike_costs, nl.paree.climbpro.ui.bike.BikeCostActivity::intentFor);
        SCREENS.put(R.id.action_climb_hygiene,
                nl.paree.climbpro.ui.climbs.ClimbHygieneActivity::intentFor);
        SCREENS.put(R.id.action_privacy,
                nl.paree.climbpro.ui.privacy.PrivacyDashboardActivity::intentFor);
        SCREENS.put(R.id.action_explore_map, nl.paree.climbpro.ui.explore.ExploreMapActivity::intentFor);
        SCREENS.put(R.id.action_visited_regions,
                nl.paree.climbpro.ui.regions.VisitedRegionsActivity::intentFor);
        SCREENS.put(R.id.action_elevation_goal,
                nl.paree.climbpro.ui.goals.ElevationGoalActivity::intentFor);
        SCREENS.put(R.id.action_goal_event, nl.paree.climbpro.ui.goals.GoalEventActivity::intentFor);
        SCREENS.put(R.id.action_event_calendar,
                nl.paree.climbpro.ui.events.EventCalendarActivity::intentFor);
        SCREENS.put(R.id.action_packing_list,
                nl.paree.climbpro.ui.planning.PackingListActivity::intentFor);
        SCREENS.put(R.id.action_bike_passport,
                nl.paree.climbpro.ui.bike.BikePassportActivity::intentFor);
        SCREENS.put(R.id.action_badges, nl.paree.climbpro.ui.goals.BadgesActivity::intentFor);
        SCREENS.put(R.id.action_monthly_challenge,
                nl.paree.climbpro.ui.goals.MonthlyChallengeActivity::intentFor);
        SCREENS.put(R.id.action_settings, c -> new Intent(c, SettingsActivity.class));
    }

    private RouteListMenuScreens() {}

    /** The screen a menu item opens, or null when the item does something else. */
    static Intent intentFor(Context context, int itemId) {
        Function<Context, Intent> screen = SCREENS.get(itemId);
        return screen != null ? screen.apply(context) : null;
    }
}
