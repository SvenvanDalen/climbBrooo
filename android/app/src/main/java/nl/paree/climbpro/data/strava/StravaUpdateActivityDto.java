package nl.paree.climbpro.data.strava;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Request body for {@code PUT activities/{id}} (Strava "UpdatableActivity"). Only the title
 * is ever set by this app (issue #60 — Strava title template) — other updatable fields
 * (type, description, gear, ...) are intentionally omitted.
 */
public final class StravaUpdateActivityDto {

    @JsonProperty("name")
    public String name;

    public StravaUpdateActivityDto() {}

    public StravaUpdateActivityDto(String name) {
        this.name = name;
    }
}
