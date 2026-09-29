package nl.paree.climbpro.update;

import org.junit.Test;
import static org.junit.Assert.*;

public class UpdateCheckerTest {

    /** Current scheme: build-android.yml tags releases v1.0.<run_number>. */
    @Test
    public void parseVersion_semverTag_returnsRunNumber() {
        assertEquals(97, UpdateChecker.parseVersion("v1.0.97"));
        assertEquals(97, UpdateChecker.parseVersion("1.0.97"));
    }

    /** Releases up to v96 used the bare v<run_number> scheme. */
    @Test
    public void parseVersion_legacyTag_returnsRunNumber() {
        assertEquals(96, UpdateChecker.parseVersion("v96"));
        assertEquals(96, UpdateChecker.parseVersion("96"));
    }

    @Test
    public void parseVersion_unrecognisedTag_returnsMinusOne() {
        assertEquals(-1, UpdateChecker.parseVersion(null));
        assertEquals(-1, UpdateChecker.parseVersion(""));
        assertEquals(-1, UpdateChecker.parseVersion("v1.0"));
        assertEquals(-1, UpdateChecker.parseVersion("release-97"));
    }
}
