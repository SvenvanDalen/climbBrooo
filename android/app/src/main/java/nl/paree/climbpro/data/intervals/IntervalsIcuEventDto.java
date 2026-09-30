package nl.paree.climbpro.data.intervals;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDate;

import nl.paree.climbpro.domain.export.IntervalsIcuExport;

/**
 * intervals.icu calendar event (issue #78): request body of
 * {@code POST /api/v1/athlete/{id}/events} and its response. A {@code .zwo} goes in
 * {@code file_contents} as plain XML (only binary formats such as FIT need base64).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public final class IntervalsIcuEventDto {

    /** Set by intervals.icu in the response; never sent. */
    @JsonProperty("id")
    public Long id;

    @JsonProperty("category")
    public String category;

    @JsonProperty("start_date_local")
    public String startDateLocal;

    @JsonProperty("type")
    public String type;

    @JsonProperty("name")
    public String name;

    @JsonProperty("description")
    public String description;

    @JsonProperty("file_contents")
    public String fileContents;

    @JsonProperty("filename")
    public String filename;

    public IntervalsIcuEventDto() {}

    /** A planned workout from a {@code .zwo} file on {@code date}. */
    public static IntervalsIcuEventDto workout(String name, String description, String zwo,
                                               String filename, LocalDate date, boolean indoor) {
        IntervalsIcuEventDto e = new IntervalsIcuEventDto();
        e.category = "WORKOUT";
        e.startDateLocal = IntervalsIcuExport.startDateLocal(date);
        e.type = IntervalsIcuExport.activityType(indoor);
        e.name = name;
        e.description = description;
        e.fileContents = zwo;
        e.filename = filename;
        return e;
    }
}
