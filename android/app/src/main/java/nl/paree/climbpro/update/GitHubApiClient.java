package nl.paree.climbpro.update;

import retrofit2.Call;
import retrofit2.http.GET;
import retrofit2.http.Path;

public interface GitHubApiClient {

    String BASE_URL = "https://api.github.com/";

    @GET("repos/{owner}/{repo}/releases/latest")
    Call<GitHubReleaseDto> latestRelease(
            @Path("owner") String owner,
            @Path("repo") String repo);
}
