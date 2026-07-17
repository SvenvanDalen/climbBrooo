package nl.paree.climbpro.service;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pure unit test for the connect-wait loop. Does not construct the Worker
 * (that needs a WorkManager context) — only the static helper.
 */
public class CiqRebindWorkerTest {

    @Test
    public void awaitConnected_immediateWhenAlreadyConnected() {
        assertTrue(CiqRebindWorker.awaitConnected(() -> true, 1_000, 1));
    }

    @Test
    public void awaitConnected_succeedsWhenConnectionComesUpDuringWait() {
        AtomicInteger polls = new AtomicInteger();
        assertTrue(CiqRebindWorker.awaitConnected(
                () -> polls.incrementAndGet() > 3, 1_000, 1));
    }

    @Test
    public void awaitConnected_falseOnTimeout() {
        assertFalse(CiqRebindWorker.awaitConnected(() -> false, 50, 10));
    }

    @Test
    public void awaitConnected_falseOnInterrupt() {
        Thread.currentThread().interrupt();
        try {
            assertFalse(CiqRebindWorker.awaitConnected(() -> false, 10_000, 10));
        } finally {
            // clear the flag so later tests on this thread aren't poisoned
            Thread.interrupted();
        }
    }
}
