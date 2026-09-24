package nl.paree.climbpro.ui.voice;

import java.util.Locale;

/**
 * The voice-assistant / launcher shortcuts of issue #260. Resolved from either the shortcut's
 * own intent action or the free-text {@code feature} Google Assistant passes for
 * {@code actions.intent.OPEN_APP_FEATURE} ("Hey Google, open volgende klim in ClimbPro").
 */
public enum VoiceCommand {
    START_RIDE,
    NEXT_CLIMB;

    public static final String ACTION_START_RIDE = "nl.paree.climbpro.action.START_RIDE";
    public static final String ACTION_NEXT_CLIMB = "nl.paree.climbpro.action.NEXT_CLIMB";
    public static final String EXTRA_FEATURE = "feature";

    /** @return the command, or {@code null} when neither input names one. */
    public static VoiceCommand parse(String action, String feature) {
        if (ACTION_START_RIDE.equals(action)) return START_RIDE;
        if (ACTION_NEXT_CLIMB.equals(action)) return NEXT_CLIMB;
        if (feature == null) return null;
        String f = feature.toLowerCase(Locale.ROOT);
        if (f.contains("klim") || f.contains("climb")) return NEXT_CLIMB;
        if (f.contains("rit") || f.contains("ride") || f.contains("start")) return START_RIDE;
        return null;
    }
}
