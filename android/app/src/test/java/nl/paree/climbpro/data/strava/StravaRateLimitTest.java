package nl.paree.climbpro.data.strava;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class StravaRateLimitTest {

    @Test
    public void plentyLeft_doesNotPause() {
        assertFalse(StravaRateLimit.nearLimit("100,1000", "20,300"));
    }

    @Test
    public void fifteenMinuteReserveReached_pauses() {
        assertTrue(StravaRateLimit.nearLimit("100,1000", "90,300"));
    }

    @Test
    public void dailyReserveReached_pauses() {
        assertTrue(StravaRateLimit.nearLimit("100,1000", "5,900"));
    }

    @Test
    public void missingOrMalformedHeaders_neverPause() {
        assertFalse(StravaRateLimit.nearLimit(null, "90,900"));
        assertFalse(StravaRateLimit.nearLimit("100", "99"));
        assertFalse(StravaRateLimit.nearLimit("a,b", "99,999"));
        assertFalse(StravaRateLimit.nearLimit((okhttp3.Headers) null));
    }

    @Test
    public void fallsBackToOverallHeadersWhenReadHeadersAbsent() {
        okhttp3.Headers h = new okhttp3.Headers.Builder()
                .add("X-RateLimit-Limit", "200,2000")
                .add("X-RateLimit-Usage", "195,10")
                .build();
        assertTrue(StravaRateLimit.nearLimit(h));
    }
}
