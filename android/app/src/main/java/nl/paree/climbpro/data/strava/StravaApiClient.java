package nl.paree.climbpro.data.strava;

import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.GET;
import retrofit2.http.Header;
import retrofit2.http.PUT;
import retrofit2.http.Path;
import retrofit2.http.Query;

import java.util.List;

public interface StravaApiClient {

    String BASE_URL = "https://www.strava.com/api/v3/";

    @GET("athlete/routes")
    Call<List<StravaRouteDto>> listRoutes(
            @Header("Authorization") String bearerToken,
            @Query("page") int page,
            @Query("per_page") int perPage);

    @GET("routes/{id}/export_gpx")
    Call<ResponseBody> exportGpx(
            @Header("Authorization") String bearerToken,
            @Path("id") long routeId);

    @GET("segments/starred")
    Call<List<StravaSegmentDto>> listStarredSegments(
            @Header("Authorization") String bearerToken,
            @Query("page") int page,
            @Query("per_page") int perPage);

    /**
     * Top segments inside {@code bounds} ({@code sw_lat,sw_lng,ne_lat,ne_lng}), used to name
     * climbs on a route after known Strava segments (issue #35). At most 10 per call.
     */
    @GET("segments/explore")
    Call<StravaSegmentExploreDto> exploreSegments(
            @Header("Authorization") String bearerToken,
            @Query("bounds") String bounds,
            @Query("activity_type") String activityType);

    @GET("athlete/activities")
    Call<List<StravaActivityDto>> listActivities(
            @Header("Authorization") String bearerToken,
            @Query("after") long afterEpochSec,
            @Query("page") int page,
            @Query("per_page") int perPage);

    /**
     * Newest-first window {@code (after, before)} for the history backfill (issue #312): always
     * page 1, the caller moves {@code before} down instead of paging, so a resumed run is stable.
     */
    @GET("athlete/activities")
    Call<List<StravaActivityDto>> listActivitiesBefore(
            @Header("Authorization") String bearerToken,
            @Query("before") long beforeEpochSec,
            @Query("after") long afterEpochSec,
            @Query("per_page") int perPage);

    @GET("activities/{id}/streams?key_by_type=true")
    Call<StravaStreamsDto> getStreams(
            @Header("Authorization") String bearerToken,
            @Path("id") long activityId,
            @Query("keys") String keys);

    /** Requires the {@code activity:write} OAuth scope (added for issue #60). */
    @PUT("activities/{id}")
    Call<StravaActivityDto> updateActivity(
            @Header("Authorization") String bearerToken,
            @Path("id") long activityId,
            @Body StravaUpdateActivityDto body);
}
