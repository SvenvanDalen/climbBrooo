package nl.paree.climbpro.protocol;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Guards wire-format lockstep across the four contract surfaces. The Monkey C parser has no
 * JVM harness, so this text-level presence check is the safety net: if a wire key lives in the
 * schema + builder but not in CommListener.mc (or vice-versa), the contract has drifted.
 */
public class ProtocolLockstepGuardTest {

    /** Keys that must appear on every surface of the route-mode wire contract. */
    private static final String[] LOCKSTEP_KEYS = { "rtl" };

    @Test
    public void wireKeysPresentOnAllSurfaces() throws Exception {
        File root = repoRoot();
        String schema   = read(new File(root, "protocol/schema.json"));
        String example  = read(new File(root, "protocol/examples/route_mode_full.json"));
        String builder  = read(new File(root,
                "android/app/src/main/java/nl/paree/climbpro/service/ClimbPayloadBuilder.java"));
        String commList = read(new File(root, "garmin/source/CommListener.mc"));

        for (String key : LOCKSTEP_KEYS) {
            String q = "\"" + key + "\"";
            assertTrue(key + " missing from schema.json",         schema.contains(q));
            assertTrue(key + " missing from route example",       example.contains(q));
            assertTrue(key + " missing from ClimbPayloadBuilder", builder.contains(q));
            assertTrue(key + " missing from CommListener.mc",     commList.contains(q));
        }
    }

    private static String read(File f) throws Exception {
        if (!f.exists()) { fail("contract file not found: " + f); }
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    /** Walk up from the test working dir until a dir containing protocol/schema.json is found. */
    private static File repoRoot() {
        File dir = new File("").getAbsoluteFile();
        for (int i = 0; i < 8 && dir != null; i++) {
            if (new File(dir, "protocol/schema.json").exists()) { return dir; }
            dir = dir.getParentFile();
        }
        throw new IllegalStateException("repo root (with protocol/schema.json) not found from "
                + new File("").getAbsolutePath());
    }
}
