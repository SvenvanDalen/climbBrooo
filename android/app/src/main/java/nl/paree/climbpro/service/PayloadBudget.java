package nl.paree.climbpro.service;

/**
 * Hard limit imposed by the Connect IQ {@code transmitMessage()} API on the
 * Forerunner 255 Music (4 096 bytes per message). See Garmin Connect IQ
 * Communications API docs.
 */
public final class PayloadBudget {

    private PayloadBudget() {}

    public static final int MAX_BYTES = 4 * 1024;
}
