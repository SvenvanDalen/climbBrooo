package nl.paree.climbpro.domain.export;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Issue #19: "N× this climb" repeats mode of {@link ClimbWorkoutWriter}. */
public class ClimbWorkoutRepeatsTest {

    /** Two segments: 5 min at 90 % FTP (5 %), 3 min at 100 % FTP (8 %). */
    private static final List<ClimbWorkoutWriter.Step> CLIMB = Arrays.asList(
            new ClimbWorkoutWriter.Step(300, 0.90, 0.05),
            new ClimbWorkoutWriter.Step(180, 1.00, 0.08));

    @Test
    public void climbSecondsSumsSegments() {
        assertEquals(480, ClimbWorkoutWriter.climbSeconds(CLIMB));
    }

    @Test
    public void defaultRecoveryIsHalfTheClimbRoundedToWholeMinutes() {
        // 8 min climb -> 4 min recovery.
        assertEquals(240, ClimbWorkoutWriter.defaultRecoverySeconds(CLIMB));
        // 13 min climb -> 6.5 min -> rounds to 7 min.
        assertEquals(420, ClimbWorkoutWriter.defaultRecoverySeconds(Arrays.asList(
                new ClimbWorkoutWriter.Step(780, 0.9, 0.06))));
    }

    @Test
    public void defaultRecoveryIsClampedBetweenThreeAndTenMinutes() {
        assertEquals(180, ClimbWorkoutWriter.MIN_RECOVERY_SEC);
        assertEquals(600, ClimbWorkoutWriter.MAX_RECOVERY_SEC);
        assertEquals(ClimbWorkoutWriter.MIN_RECOVERY_SEC,
                ClimbWorkoutWriter.defaultRecoverySeconds(Arrays.asList(
                        new ClimbWorkoutWriter.Step(120, 0.9, 0.06))));
        assertEquals(ClimbWorkoutWriter.MAX_RECOVERY_SEC,
                ClimbWorkoutWriter.defaultRecoverySeconds(Arrays.asList(
                        new ClimbWorkoutWriter.Step(3600, 0.9, 0.06))));
    }

    @Test
    public void repeatBoundsAndDefault() {
        assertEquals(2, ClimbWorkoutWriter.MIN_REPEATS);
        assertEquals(10, ClimbWorkoutWriter.MAX_REPEATS);
        assertEquals(5, ClimbWorkoutWriter.DEFAULT_REPEATS);
    }

    @Test
    public void mainSetInterleavesClimbsWithRecoveries() {
        List<ClimbWorkoutWriter.Step> set = ClimbWorkoutWriter.mainSet(CLIMB, 5, 240);
        // 5 climbs x 2 segments + 4 recoveries in between.
        assertEquals(14, set.size());
        int recoveries = 0;
        for (ClimbWorkoutWriter.Step s : set) {
            if (s.recovery) {
                recoveries++;
                assertEquals(240, s.seconds);
                assertEquals(ClimbWorkoutWriter.RECOVERY_FRACTION, s.ftpFraction, 1e-12);
            }
        }
        assertEquals(4, recoveries);
        assertFalse(set.get(0).recovery);
        assertFalse(set.get(1).recovery);
        assertTrue(set.get(2).recovery);
        assertFalse(set.get(set.size() - 1).recovery); // no trailing recovery before cool-down
        assertEquals(0.90, set.get(3).ftpFraction, 1e-12); // second repeat restarts the climb
    }

    @Test
    public void recoveryPowerIsActiveRecovery() {
        assertEquals(0.50, ClimbWorkoutWriter.RECOVERY_FRACTION, 1e-12);
    }

    @Test
    public void singleRepeatHasNoRecovery() {
        List<ClimbWorkoutWriter.Step> set = ClimbWorkoutWriter.mainSet(CLIMB, 1, 240);
        assertEquals(2, set.size());
        assertFalse(set.get(0).recovery);
        assertFalse(set.get(1).recovery);
    }

    @Test
    public void totalSecondsCountsWarmupClimbsRecoveriesAndCooldown() {
        // 600 warm-up + 5 x 480 + 4 x 240 + 300 cool-down.
        assertEquals(600 + 2400 + 960 + 300, ClimbWorkoutWriter.totalSeconds(CLIMB, 5, 240));
        assertEquals(600 + 480 + 300, ClimbWorkoutWriter.totalSeconds(CLIMB, 1, 240));
    }

    @Test(expected = IllegalArgumentException.class)
    public void zeroRepeatsIsRejected() {
        ClimbWorkoutWriter.mainSet(CLIMB, 0, 240);
    }

    @Test(expected = IllegalArgumentException.class)
    public void negativeRecoveryIsRejected() {
        ClimbWorkoutWriter.mainSet(CLIMB, 3, -1);
    }

    @Test
    public void singleRepeatOutputMatchesPlainExport() {
        assertEquals(ClimbWorkoutWriter.toZwo("Stelvio", CLIMB),
                ClimbWorkoutWriter.toZwo("Stelvio", CLIMB, 1, 240));
        assertEquals(ClimbWorkoutWriter.toErg("Stelvio", CLIMB, 250),
                ClimbWorkoutWriter.toErg("Stelvio", CLIMB, 250, 1, 240));
    }

    @Test
    public void zwoRepeatsExactShape() {
        String zwo = ClimbWorkoutWriter.toZwo("Col d'Izoard", CLIMB, 3, 240);
        String expected = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<workout_file>\n"
                + "  <author>ClimbPro</author>\n"
                + "  <name>3× Col d&apos;Izoard</name>\n"
                + "  <description>Herhaal-klim uit ClimbPro: 3× Col d&apos;Izoard (2 segmenten,"
                + " 8:00 min per keer) met 4:00 min herstel op 50 % FTP ertussen. Vermogen per"
                + " segment volgt de helling. 10 min opwarmen en 5 min uitrijden. Totaal"
                + " 47:00 min.</description>\n"
                + "  <sportType>bike</sportType>\n"
                + "  <tags>\n    <tag name=\"CLIMB\"/>\n    <tag name=\"INTERVALS\"/>\n  </tags>\n"
                + "  <workout>\n"
                + "    <Warmup Duration=\"600\" PowerLow=\"0.45\" PowerHigh=\"0.75\"/>\n"
                + climbZwo(1, 3)
                + recoveryZwo(1, 2)
                + climbZwo(2, 3)
                + recoveryZwo(2, 2)
                + climbZwo(3, 3)
                + "    <Cooldown Duration=\"300\" PowerLow=\"0.60\" PowerHigh=\"0.40\"/>\n"
                + "  </workout>\n"
                + "</workout_file>\n";
        assertEquals(expected, zwo);
    }

    private static String climbZwo(int rep, int reps) {
        return "    <SteadyState Duration=\"300\" Power=\"0.900\">\n"
                + "      <textevent timeoffset=\"0\" message=\"Herhaling " + rep + "/" + reps
                + " · segment 1/2: 5,0 %\"/>\n"
                + "    </SteadyState>\n"
                + "    <SteadyState Duration=\"180\" Power=\"1.000\">\n"
                + "      <textevent timeoffset=\"0\" message=\"Herhaling " + rep + "/" + reps
                + " · segment 2/2: 8,0 %\"/>\n"
                + "    </SteadyState>\n";
    }

    private static String recoveryZwo(int i, int n) {
        return "    <SteadyState Duration=\"240\" Power=\"0.500\">\n"
                + "      <textevent timeoffset=\"0\" message=\"Herstel " + i + "/" + n
                + ": rustig trappen\"/>\n"
                + "    </SteadyState>\n";
    }

    @Test
    public void ergRepeatsExactShape() {
        String erg = ClimbWorkoutWriter.toErg("Test", CLIMB, 200, 2, 240);
        String expected = "[COURSE HEADER]\n"
                + "VERSION = 2\n"
                + "UNITS = METRIC\n"
                + "DESCRIPTION = Herhaal-klim uit ClimbPro: 2× Test (2 segmenten, 8:00 min per"
                + " keer) met 4:00 min herstel op 50 % FTP ertussen. Vermogen per segment volgt"
                + " de helling. 10 min opwarmen en 5 min uitrijden. Totaal 35:00 min.\n"
                + "FILE NAME = 2× Test\n"
                + "FTP = 200\n"
                + "MINUTES WATTS\n"
                + "[END COURSE HEADER]\n"
                + "[COURSE DATA]\n"
                + "0.00\t90\n"
                + "10.00\t150\n"
                // repeat 1
                + "10.00\t180\n15.00\t180\n15.00\t200\n18.00\t200\n"
                // recovery
                + "18.00\t100\n22.00\t100\n"
                // repeat 2
                + "22.00\t180\n27.00\t180\n27.00\t200\n30.00\t200\n"
                // cool-down
                + "30.00\t120\n35.00\t80\n"
                + "[END COURSE DATA]\n";
        assertEquals(expected, erg);
    }

    @Test
    public void ergEndTimeMatchesTotalSeconds() {
        String erg = ClimbWorkoutWriter.toErg("Test", CLIMB, 200, 5, 300);
        String[] lines = erg.split("\n");
        String last = lines[lines.length - 2]; // the line before [END COURSE DATA]
        double minutes = Double.parseDouble(last.split("\t")[0]);
        assertEquals(ClimbWorkoutWriter.totalSeconds(CLIMB, 5, 300) / 60.0, minutes, 0.01);
    }

    @Test
    public void repeatFileNameIsPrefixed() {
        assertEquals("5x_col_d_izoard.zwo",
                ClimbWorkoutWriter.fileName("Col d'Izoard", "zwo", 5));
        assertEquals("col_d_izoard.erg",
                ClimbWorkoutWriter.fileName("Col d'Izoard", "erg", 1));
    }

    @Test
    public void numbersUseDotDecimalsRegardlessOfDefaultLocale() {
        Locale old = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            String erg = ClimbWorkoutWriter.toErg("T", CLIMB, 200, 2, 240);
            assertTrue(erg, erg.contains("22.00\t180"));
        } finally {
            Locale.setDefault(old);
        }
    }
}
