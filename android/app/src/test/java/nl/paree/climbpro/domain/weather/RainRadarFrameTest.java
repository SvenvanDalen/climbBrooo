package nl.paree.climbpro.domain.weather;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

public class RainRadarFrameTest {

    static final String JSON = "{\"version\":\"2.0\",\"generated\":1790278826,"
            + "\"host\":\"https://tilecache.rainviewer.com\",\"radar\":{\"past\":["
            + "{\"time\":1790278200,\"path\":\"/v2/radar/f7b89777a00e\"},"
            + "{\"time\":1790278800,\"path\":\"/v2/radar/1db0f58ce988\"}],\"nowcast\":[]},"
            + "\"satellite\":{\"infrared\":[]}}";

    @Test public void picksTheNewestPastFrame() throws IOException {
        RainRadarFrame f = RainRadarFrame.parseLatest(JSON);
        assertEquals("https://tilecache.rainviewer.com", f.host);
        assertEquals("/v2/radar/1db0f58ce988", f.path);
        assertEquals(Instant.ofEpochSecond(1790278800L), f.time);
    }

    @Test public void tileUrlUsesSize256ColorSchemeTwoAndSmoothSnow() throws IOException {
        RainRadarFrame f = RainRadarFrame.parseLatest(JSON);
        List<RadarTiles.Tile> t = RadarTiles.covering(50.8, 50.9, 5.7, 5.8, 7, 16);
        assertEquals("https://tilecache.rainviewer.com/v2/radar/1db0f58ce988/256/7/66/42/2/1_1.png",
                f.tileUrl(t.get(0)));
    }

    @Test public void noPastFramesMeansNoFrame() throws IOException {
        assertNull(RainRadarFrame.parseLatest(
                "{\"host\":\"https://tilecache.rainviewer.com\",\"radar\":{\"past\":[]}}"));
    }

    @Test(expected = IOException.class)
    public void missingHostIsAnError() throws IOException {
        RainRadarFrame.parseLatest("{\"radar\":{\"past\":[{\"time\":1,\"path\":\"/x\"}]}}");
    }

    @Test(expected = IOException.class)
    public void notJsonIsAnError() throws IOException {
        RainRadarFrame.parseLatest("<html>503</html>");
    }
}
