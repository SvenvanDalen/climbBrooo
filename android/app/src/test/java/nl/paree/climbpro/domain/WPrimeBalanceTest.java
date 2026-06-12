package nl.paree.climbpro.domain;

import nl.paree.climbpro.domain.power.PowerConstants;
import nl.paree.climbpro.domain.power.WPrimeBalance;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class WPrimeBalanceTest {

    private static final double CP = 250.0;
    private static final double WMAX = 20_000.0;

    @Test
    public void startsFull() {
        assertEquals(WMAX, new WPrimeBalance(WMAX, CP).current(), 1e-9);
    }

    @Test
    public void depletesAboveCp() {
        WPrimeBalance b = new WPrimeBalance(WMAX, CP);
        b.applyInterval(CP + 100, 10); // 100 W above CP for 10 s = 1000 J
        assertEquals(WMAX - 1000, b.current(), 1e-6);
    }

    @Test
    public void depletionClampsAtZero() {
        WPrimeBalance b = new WPrimeBalance(WMAX, CP);
        b.applyInterval(CP + 100, 10_000); // would remove 1,000,000 J
        assertEquals(0.0, b.current(), 1e-9);
    }

    @Test
    public void recoversBelowCpTowardMax() {
        WPrimeBalance b = new WPrimeBalance(WMAX, CP);
        b.applyInterval(CP + 100, 100);     // 100 W above CP for 100 s = 10,000 J depleted; 10,000 J remaining
        double afterDeplete = b.current();
        b.applyInterval(CP - 50, 1000);     // recover for 1000 s
        assertTrue("must recover", b.current() > afterDeplete);
        assertTrue("never exceeds max", b.current() <= WMAX + 1e-9);
    }

    @Test
    public void recoveryMatchesExponential() {
        WPrimeBalance b = new WPrimeBalance(WMAX, CP);
        b.applyInterval(CP + 100, 100);     // -> 10,000 J
        double before = b.current();
        double dt = PowerConstants.W_PRIME_TAU_SECONDS; // one tau
        b.applyInterval(CP, dt);            // power == CP counts as recovery branch
        double expected = WMAX - (WMAX - before) * Math.exp(-1.0);
        assertEquals(expected, b.current(), 1e-6);
    }

    @Test
    public void zeroDurationIsNoOp() {
        WPrimeBalance b = new WPrimeBalance(WMAX, CP);
        b.applyInterval(CP + 500, 0);
        assertEquals(WMAX, b.current(), 1e-9);
    }

    @Test
    public void repeatedDepletionLeavesLessThanSingleDepletion() {
        WPrimeBalance a = new WPrimeBalance(WMAX, CP);
        a.applyInterval(CP + 100, 50); // deplete 5,000 J
        double afterFirst = a.current();

        WPrimeBalance b = new WPrimeBalance(WMAX, CP);
        b.applyInterval(CP + 100, 50); // deplete 5,000 J
        b.applyInterval(CP - 100, 60); // partial recovery
        b.applyInterval(CP + 100, 50); // deplete again
        assertTrue("balance after deplete/recover/deplete must be below a single depletion",
                b.current() < afterFirst);
    }
}
