package nl.paree.climbpro.update;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** Partial mapping of GitHub's /repos/{owner}/{repo}/releases/latest response. */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class GitHubReleaseDto {

    @JsonProperty("tag_name")
    public String tagName;       // e.g. "v42" — build-android.yml tags releases v<run_number>

    @JsonProperty("html_url")
    public String htmlUrl;

    @JsonProperty("assets")
    public List<Asset> assets;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Asset {

        @JsonProperty("name")
        public String name;

        @JsonProperty("browser_download_url")
        public String browserDownloadUrl;
    }
}
