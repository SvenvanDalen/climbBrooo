package nl.paree.climbpro.data.strava;

import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.http.GET;
import retrofit2.http.Header;
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
}
