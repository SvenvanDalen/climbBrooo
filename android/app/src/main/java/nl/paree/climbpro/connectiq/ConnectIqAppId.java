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

    /** Watch app (browse/select UI; was the widget). Counterpart for incoming messages. */
    public static final String VALUE = "fedcba9876543210fedcba9876543210";

    /** Existing ClimbPro datafield — receives the active route/climb payload. */
    public static final String DATAFIELD = "0123456789abcdef0123456789abcdef";

    /** Surface-sections datafield. MUST equal the id in garmin-surface/manifest.xml. */
    public static final String SURFACE_FIELD = "00112233445566770011223344556677";

    /** "ClimbPro Onboard" watch app (on-watch route parsing). MUST equal the id in garmin-onboard/manifest.xml. */
    public static final String ONBOARD = "a0b1c2d3e4f50617a0b1c2d3e4f50617";
}
