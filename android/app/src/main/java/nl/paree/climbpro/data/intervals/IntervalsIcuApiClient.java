package nl.paree.climbpro.data.intervals;

import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.GET;
import retrofit2.http.Header;
import retrofit2.http.POST;
import retrofit2.http.Path;

/**
 * intervals.icu REST API (issue #78), see https://intervals.icu/api/v1/docs. Auth is HTTP
 * Basic with user {@code API_KEY} and the personal key as password.
 */
public interface IntervalsIcuApiClient {

    String BASE_URL = "https://intervals.icu/api/v1/";

    @GET("athlete/{id}")
    Call<IntervalsIcuAthleteDto> getAthlete(
            @Header("Authorization") String basicAuth,
            @Path("id") String athleteId);

    @POST("athlete/{id}/events")
    Call<IntervalsIcuEventDto> createEvent(
            @Header("Authorization") String basicAuth,
            @Path("id") String athleteId,
            @Body IntervalsIcuEventDto event);
}
