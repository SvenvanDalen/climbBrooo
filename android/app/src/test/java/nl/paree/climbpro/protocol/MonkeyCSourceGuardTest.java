package nl.paree.climbpro.protocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

public class MonkeyCSourceGuardTest {

    @Test
    public void balanced_ignoresBracesInCommentsAndStrings() {
        assertTrue(MonkeyCSourceGuard.bracesBalanced("function f() { var s = \"}{ ) (\"; }"));
        assertTrue(MonkeyCSourceGuard.bracesBalanced("// } } }\nfunction f() { }"));
        assertTrue(MonkeyCSourceGuard.bracesBalanced("/* { ( */ function f() { return 1; }"));
    }

    @Test
    public void balanced_detectsMissingBrace() {
        assertFalse(MonkeyCSourceGuard.bracesBalanced("function f() { return 1;"));
        assertFalse(MonkeyCSourceGuard.bracesBalanced("function f( { }"));
    }

    @Test
    public void testsWithoutReturnTrue_flagsMissing() {
        String bad = "(:test)\nfunction t1(l) { Test.assertEqual(1,1); }\n"
                   + "(:test)\nfunction t2(l) { return true; }\n";
        List<String> missing = MonkeyCSourceGuard.testsWithoutReturnTrue(bad);
        assertEquals(1, missing.size());
        assertTrue(missing.contains("t1"));
    }

    @Test
    public void realMonkeyCSourcesPassGuard() throws Exception {
        File root = repoRoot();
        List<File> files = new ArrayList<>();
        String[] dirs = {
            "garmin/source", "garmin/test",
            "garmin-widget/source", "garmin-widget/test",
            "garmin-surface/source", "garmin-surface/test",
            "garmin-onboard/source", "garmin-onboard/test",
        };
        for (String d : dirs) {
            File dir = new File(root, d);
            if (!dir.isDirectory()) { continue; }
            File[] mc = dir.listFiles((f, n) -> n.endsWith(".mc"));
            if (mc != null) { for (File f : mc) { files.add(f); } }
        }
        assertFalse("expected to find .mc files", files.isEmpty());
        for (File f : files) {
            String src = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            assertTrue("unbalanced braces/parens in " + f, MonkeyCSourceGuard.bracesBalanced(src));
            List<String> missing = MonkeyCSourceGuard.testsWithoutReturnTrue(src);
            assertTrue("(:test) without 'return true' in " + f + ": " + missing, missing.isEmpty());
        }
    }

    private static File repoRoot() {
        File dir = new File("").getAbsoluteFile();
        for (int i = 0; i < 8 && dir != null; i++) {
            if (new File(dir, "protocol/schema.json").exists()) { return dir; }
            dir = dir.getParentFile();
        }
        throw new IllegalStateException("repo root not found from " + new File("").getAbsolutePath());
    }
}
