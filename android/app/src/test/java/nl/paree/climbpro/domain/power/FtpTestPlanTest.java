package nl.paree.climbpro.domain.power;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import nl.paree.climbpro.domain.export.ClimbWorkoutWriter;

import org.junit.Test;

import java.util.List;

public class FtpTestPlanTest {

    @Test
    public void protocolIsWarmupBlowOutRecoveryTestCooldown() {
        List<FtpTestPlan.Phase> phases = FtpTestPlan.phases();
        assertEquals(5, phases.size());
        assertEquals(FtpTestPlan.PhaseType.WARMUP, phases.get(0).type);
        assertEquals(FtpTestPlan.PhaseType.BLOW_OUT, phases.get(1).type);
        assertEquals(5 * 60, phases.get(1).seconds);
        assertEquals(FtpTestPlan.PhaseType.RECOVERY, phases.get(2).type);
        assertEquals(FtpTestPlan.PhaseType.TEST, phases.get(3).type);
        assertEquals(20 * 60, phases.get(3).seconds);
        assertEquals(FtpTestPlan.PhaseType.COOLDOWN, phases.get(4).type);
    }

    @Test
    public void totalIsSumOfPhases() {
        int sum = 0;
        for (FtpTestPlan.Phase p : FtpTestPlan.phases()) sum += p.seconds;
        assertEquals(sum, FtpTestPlan.totalSeconds());
        assertEquals(60 * 60, FtpTestPlan.totalSeconds());
    }

    @Test
    public void testTargetIsCurrentFtpOverPointNinetyFive() {
        // 250 W FTP means holding 263 W for 20 minutes.
        assertEquals(263, FtpTestPlan.testTargetWatts(250));
        assertEquals(0, FtpTestPlan.testTargetWatts(0));
    }

    @Test
    public void ftpIsNinetyFivePercentOfTwentyMinutePower() {
        assertEquals(285, FtpTestPlan.ftpFromTwentyMinuteWatts(300));
        assertEquals(0, FtpTestPlan.ftpFromTwentyMinuteWatts(0));
        // Round-trips with the target: hitting the target confirms the current FTP.
        assertEquals(250, FtpTestPlan.ftpFromTwentyMinuteWatts(FtpTestPlan.testTargetWatts(250)));
    }

    @Test
    public void phaseWattsFollowFtp() {
        FtpTestPlan.Phase warmup = FtpTestPlan.phases().get(0);
        assertEquals(Math.round(warmup.fromFraction * 200), warmup.fromWatts(200));
        assertEquals(Math.round(warmup.toFraction * 200), warmup.toWatts(200));
    }

    @Test
    public void zwoIsPlainAndCarriesEveryPhase() {
        String zwo = FtpTestPlan.toZwo();
        assertTrue(zwo.startsWith("<?xml"));
        assertTrue(zwo.contains("<name>" + FtpTestPlan.WORKOUT_NAME + "</name>"));
        assertTrue(FtpTestPlan.WORKOUT_NAME.length() <= 40);
        assertTrue(zwo, zwo.contains("<Warmup Duration=\"900\" PowerLow=\"0.50\" PowerHigh=\"0.75\"/>"));
        assertTrue(zwo, zwo.contains("<SteadyState Duration=\"1200\" Power=\"1.05\"/>"));
        assertTrue(zwo, zwo.contains("<Cooldown Duration=\"600\" PowerLow=\"0.60\" PowerHigh=\"0.40\"/>"));
        assertEquals(3, count(zwo, "<SteadyState "));
        // MyWhoosh's importer rejects text events and tags.
        assertFalse(zwo.contains("textevent"));
        assertFalse(zwo.contains("<tags>"));
        assertTrue(zwo.contains("ERG"));
    }

    @Test
    public void fileNameIsStable() {
        assertEquals("ftp_test_20_min.zwo", FtpTestPlan.FILE_NAME);
    }

    @Test
    public void plainZwoMatchesMyWhooshExport() {
        // toMyWhooshZwo goes through the same writer, so both stay importable.
        String zwo = ClimbWorkoutWriter.toPlainZwo("A & B", "d", java.util.Arrays.asList(
                ClimbWorkoutWriter.Block.steady(60, 0.8)));
        assertTrue(zwo.contains("<name>A &amp; B</name>"));
        assertTrue(zwo.contains("<SteadyState Duration=\"60\" Power=\"0.80\"/>"));
    }

    private static int count(String s, String needle) {
        int n = 0;
        for (int i = s.indexOf(needle); i >= 0; i = s.indexOf(needle, i + 1)) n++;
        return n;
    }
}
