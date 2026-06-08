package nl.paree.climbpro.connectiq;

/**
 * The Connect IQ application UUID the phone communicates with.
 *
 * MUST equal the {@code id} attribute of the SYNC counterpart watch app.
 * Per docs/superpowers/specs/2026-06-08-garmin-widget-sync-and-storage-design.md
 * the counterpart is the WIDGET (ClimbWidgetApp), not the datafield.
 *
 * Contract: this value is duplicated in garmin-widget/manifest.xml — change
 * both together. (For a public release, regenerate both to a fresh UUID.)
 */
public final class ConnectIqAppId {
    private ConnectIqAppId() {}

    public static final String VALUE = "fedcba9876543210fedcba9876543210";
}
