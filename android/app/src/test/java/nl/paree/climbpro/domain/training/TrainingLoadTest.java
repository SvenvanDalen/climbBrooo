package nl.paree.climbpro.domain.training;

import static org.junit.Assert.assertEquals;

import nl.paree.climbpro.data.ride.StoredRide;

import org.junit.Test;

public class TrainingLoadTest {

    private static StoredRide ride(int movingSec) {
        StoredRide r = new StoredRide();
        r.type = "Ride";
        r.movingTimeSec = movingSec;
        return r;
    }

    @Test
    public void measuredNormalizedPowerGivesClassicTss() {
        StoredRide r = ride(3600);
        r.deviceWatts = true;
        r.weightedAvgWatts = 250;
        r.avgWatts = 230f;
        TrainingLoad.Load load = TrainingLoad.of(r, 250);
        assertEquals(100, load.tss, 1e-9); // one hour at FTP
        assertEquals(TrainingLoad.Source.POWER, load.source);
    }

    @Test
    public void estimatedPowerUsesAverageWatts() {
        StoredRide r = ride(7200);
        r.avgWatts = 175f; // Strava's estimate, no power meter
        TrainingLoad.Load load = TrainingLoad.of(r, 250);
        assertEquals(2 * 0.7 * 0.7 * 100, load.tss, 1e-9);
        assertEquals(TrainingLoad.Source.ESTIMATED_POWER, load.source);
    }

    @Test
    public void withoutPowerOrFtpFallsBackToDuration() {
        StoredRide r = ride(3600);
        r.avgWatts = 200f;
        TrainingLoad.Load noFtp = TrainingLoad.of(r, 0);
        assertEquals(TrainingLoad.Source.DURATION, noFtp.source);
        double intensity = TrainingLoad.DEFAULT_INTENSITY;
        assertEquals(intensity * intensity * 100, noFtp.tss, 1e-9);

        StoredRide commute = ride(3600);
        commute.commute = true;
        assertEquals(TrainingLoad.COMMUTE_INTENSITY * TrainingLoad.COMMUTE_INTENSITY * 100,
                TrainingLoad.of(commute, 250).tss, 1e-9);
    }

    @Test
    public void intensityIsClampedAndEBikesAreLight() {
        StoredRide spike = ride(3600);
        spike.deviceWatts = true;
        spike.weightedAvgWatts = 900;
        assertEquals(TrainingLoad.MAX_INTENSITY * TrainingLoad.MAX_INTENSITY * 100,
                TrainingLoad.of(spike, 250).tss, 1e-9);

        StoredRide ebike = ride(3600);
        ebike.type = "EBikeRide";
        ebike.avgWatts = 250f;
        TrainingLoad.Load load = TrainingLoad.of(ebike, 250);
        assertEquals(TrainingLoad.Source.DURATION, load.source);
        assertEquals(TrainingLoad.EBIKE_INTENSITY * TrainingLoad.EBIKE_INTENSITY * 100,
                load.tss, 1e-9);
    }

    @Test
    public void zeroDurationIsZeroLoad() {
        assertEquals(0, TrainingLoad.of(ride(0), 250).tss, 1e-9);
    }
}
