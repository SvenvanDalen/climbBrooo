package nl.paree.climbpro.service;

/**
 * Watch-side payload size cap.
 * Start conservative at 8 KB; tune in Phase 7 after real-device measurement.
 */
public final class PayloadBudget {

    private PayloadBudget() {}

    public static final int MAX_BYTES = 8 * 1024;
}
