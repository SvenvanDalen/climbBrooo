package nl.paree.climbpro.data.bike;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Root object of {@code bike_costs.json}: the bike garage (issue #187) with each bike's costs
 * (issue #233). The file name predates the garage and is kept so existing data stays put. A
 * missing file means no bikes yet.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class BikeCostLog {
    /** 1 = cost overview only; from this version on the garage migration has run. */
    public static final int GARAGE_VERSION = 2;

    public int version = 1;
    public List<Bike> bikes = new ArrayList<>();
    /** The bike used for estimates and as ride fallback; null = first bike in use. */
    public String activeBikeId;
    /** The bike indoor rides ({@code VirtualRide}) go to; null = first trainer-type bike. */
    public String indoorBikeId;
}
