package nl.paree.climbpro.update;

import org.junit.Test;
import static org.junit.Assert.*;

public class UpdateCheckerTest {

    /** Current scheme: build-android.yml tags releases v1.<minor>.0. */
    @Test
    public void parseVersion_semverTag_returnsComponents() {
        assertArrayEquals(new int[] { 1, 4, 0 }, UpdateChecker.parseVersion("v1.4.0"));
        assertArrayEquals(new int[] { 1, 0, 97 }, UpdateChecker.parseVersion("1.0.97"));
    }

    /** Local builds fall back to versionName "1.0.0-dev". */
    @Test
    public void parseVersion_suffixedVersion_ignoresSuffix() {
        assertArrayEquals(new int[] { 1, 0, 0 }, UpdateChecker.parseVersion("1.0.0-dev"));
    }

    /** Releases up to v96 used the bare v<run_number> scheme. */
    @Test
    public void parseVersion_legacyTag_readsAsPatch() {
        assertArrayEquals(new int[] { 0, 0, 96 }, UpdateChecker.parseVersion("v96"));
        assertArrayEquals(new int[] { 0, 0, 96 }, UpdateChecker.parseVersion("96"));
    }

    @Test
    public void parseVersion_unrecognisedTag_returnsNull() {
        assertNull(UpdateChecker.parseVersion(null));
        assertNull(UpdateChecker.parseVersion(""));
        assertNull(UpdateChecker.parseVersion("v1.0"));
        assertNull(UpdateChecker.parseVersion("release-97"));
    }

    /** The first minor-bump release must beat every old v1.0.<run_number> build. */
    @Test
    public void compareVersions_minorBumpBeatsOldRunNumber() {
        assertTrue(compare("v1.1.0", "1.0.132") > 0);
        assertTrue(compare("v1.2.0", "1.1.0") > 0);
        assertTrue(compare("v2.0.0", "1.9.0") > 0);
    }

    @Test
    public void compareVersions_sameOrOlderIsNotNewer() {
        assertEquals(0, compare("v1.3.0", "1.3.0"));
        assertTrue(compare("v1.0.132", "1.1.0") < 0);
        assertTrue(compare("v1.10.0", "1.9.0") > 0);
    }

    private static int compare(String remote, String local) {
        return UpdateChecker.compareVersions(
                UpdateChecker.parseVersion(remote), UpdateChecker.parseVersion(local));
    }
}
