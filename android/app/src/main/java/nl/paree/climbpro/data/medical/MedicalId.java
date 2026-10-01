package nl.paree.climbpro.data.medical;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The rider's medical ID (issue #230): what first responders need after a crash. Stored on the
 * phone, shown on the lock screen (opt-in notification) and pushed to the watch widget.
 * Every text field may be null.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class MedicalId {

    /** Watch field length caps: keep the MEDICAL_ID message small and the lines readable. */
    static final int MAX_BLOOD_TYPE = 6;
    static final int MAX_TEXT = 60;
    static final int MAX_NAME = 24;
    static final int MAX_PHONE = 20;
    static final int MAX_NOTES = 80;

    public String name;
    public String bloodType;
    public String allergies;
    public String medication;
    public String contactName;
    public String contactPhone;
    public String notes;
    /** Show a permanent notification with the ID on the lock screen. */
    public boolean showOnLockscreen;

    @JsonIgnore
    public boolean isEmpty() {
        return blank(name) && blank(bloodType) && blank(allergies) && blank(medication)
                && blank(contactName) && blank(contactPhone) && blank(notes);
    }

    /**
     * Watch message for the widget: {@code {type:"MEDICAL_ID", nm?, bt?, al?, md?, ec?, ep?,
     * nt?}}. Only filled fields are sent, whitespace-collapsed and capped; an empty ID sends
     * just the type, which tells the watch to delete its copy.
     */
    public Map<String, Object> toWatchMessage() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "MEDICAL_ID");
        put(m, "nm", name, MAX_NAME);
        put(m, "bt", bloodType, MAX_BLOOD_TYPE);
        put(m, "al", allergies, MAX_TEXT);
        put(m, "md", medication, MAX_TEXT);
        put(m, "ec", contactName, MAX_NAME);
        put(m, "ep", contactPhone, MAX_PHONE);
        put(m, "nt", notes, MAX_NOTES);
        return m;
    }

    /** Lock-screen text, one fact per line; empty string for an empty ID. */
    public String lockscreenText() {
        StringBuilder sb = new StringBuilder();
        line(sb, "Naam", name);
        line(sb, "Bloedgroep", bloodType);
        line(sb, "Allergieën", allergies);
        line(sb, "Medicatie", medication);
        line(sb, "Noodcontact", join(contactName, contactPhone));
        line(sb, "Info", notes);
        return sb.toString();
    }

    /** Short one-line summary for the collapsed notification. */
    public String summary() {
        String contact = join(contactName, contactPhone);
        if (!blank(bloodType) && contact != null) {
            return "Bloedgroep " + bloodType.trim() + " · ICE " + contact;
        }
        if (contact != null) return "ICE " + contact;
        if (!blank(bloodType)) return "Bloedgroep " + bloodType.trim();
        return lockscreenText().split("\n")[0];
    }

    private static String join(String a, String b) {
        if (blank(a) && blank(b)) return null;
        if (blank(a)) return b.trim();
        if (blank(b)) return a.trim();
        return a.trim() + " " + b.trim();
    }

    private static void line(StringBuilder sb, String label, String value) {
        if (blank(value)) return;
        if (sb.length() > 0) sb.append('\n');
        sb.append(label).append(": ").append(value.trim());
    }

    private static void put(Map<String, Object> m, String key, String value, int max) {
        if (blank(value)) return;
        String v = value.trim().replaceAll("\\s+", " ");
        m.put(key, v.length() > max ? v.substring(0, max) : v);
    }

    static boolean blank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
