package nl.paree.climbpro.data.strava;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Response of {@code GET /segments/explore} (issue #35): the top segments inside a bounding
 * box. Unlike the starred list, each entry calls its gradient {@code avg_grade} (a
 * percentage), so {@link Entry#toSegment()} maps it onto the shared {@link StravaSegmentDto}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class StravaSegmentExploreDto {

    @JsonProperty("segments")
    public List<Entry> segments;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Entry {
        @JsonProperty("id")
        public long id;

        @JsonProperty("name")
        public String name;

        @JsonProperty("avg_grade")
        public float avgGrade;

        @JsonProperty("distance")
        public float distance;

        @JsonProperty("climb_category")
        public int climbCategory;

        @JsonProperty("start_latlng")
        public double[] startLatlng;

        @JsonProperty("end_latlng")
        public double[] endLatlng;

        public StravaSegmentDto toSegment() {
            StravaSegmentDto s = new StravaSegmentDto();
            s.id = id;
            s.name = name;
            s.averageGrade = avgGrade;
            s.distance = distance;
            s.startLatlng = startLatlng;
            s.endLatlng = endLatlng;
            return s;
        }
    }
}
