package nl.paree.climbpro.domain.bike;

/**
 * Euro amounts as integer cents for the bike cost overview (issue #233). Parses what a user
 * types and formats Dutch style ({@code € 1.234,56}) without floating point, and without
 * {@code NumberFormat} whose separators/spacing differ between JVM and Android versions.
 *
 * <p>Parsing rules: an optional {@code €} and whitespace are ignored. With a comma, the comma
 * is the decimal separator and dots must be thousands separators ({@code 1.234,56}). Without a
 * comma, a single dot followed by at most two digits is a decimal point ({@code 12.50}, typed
 * on an English keyboard); otherwise dots are thousands separators ({@code 1.234}). At most
 * two decimals; the result must lie in {@code [1, MAX_CENTS]}.
 */
public final class EuroAmount {

    private EuroAmount() {}

    /** € 1.000.000,00 — far above any bike part, and keeps cost-per-km maths far from overflow. */
    public static final long MAX_CENTS = 100_000_000L;
    public static final long INVALID = -1L;

    public static long parseCents(String input) {
        if (input == null) return INVALID;
        String s = input.replace(' ', ' ').replace("€", "").replaceAll("\\s+", "");
        if (s.isEmpty()) return INVALID;

        String intPart;
        String fracPart;
        int comma = s.indexOf(',');
        if (comma >= 0) {
            if (s.indexOf(',', comma + 1) >= 0) return INVALID;
            intPart  = s.substring(0, comma);
            fracPart = s.substring(comma + 1);
            if (!validThousands(intPart)) return INVALID;
            intPart = intPart.replace(".", "");
        } else {
            int lastDot = s.lastIndexOf('.');
            int dots = 0;
            for (int i = 0; i < s.length(); i++) {
                if (s.charAt(i) == '.') dots++;
            }
            if (dots == 1 && s.length() - lastDot - 1 <= 2) {
                intPart  = s.substring(0, lastDot);
                fracPart = s.substring(lastDot + 1);
            } else {
                if (!validThousands(s)) return INVALID;
                intPart  = s.replace(".", "");
                fracPart = "";
            }
        }

        if (intPart.isEmpty() && fracPart.isEmpty()) return INVALID;
        if (!allDigits(intPart) || !allDigits(fracPart) || fracPart.length() > 2) return INVALID;
        if (intPart.length() > 9) return INVALID; // way above MAX_CENTS, avoids overflow

        long euros = intPart.isEmpty() ? 0 : Long.parseLong(intPart);
        long frac  = fracPart.isEmpty() ? 0
                : Long.parseLong(fracPart) * (fracPart.length() == 1 ? 10 : 1);
        long cents = euros * 100 + frac;
        if (cents <= 0 || cents > MAX_CENTS) return INVALID;
        return cents;
    }

    /** {@code "€ 1.234,56"}; negative amounts (never stored) as {@code "-€ 3,10"}. */
    public static String format(long cents) {
        boolean negative = cents < 0;
        long abs = Math.abs(cents);
        long euros = abs / 100;
        long rest  = abs % 100;
        return (negative ? "-€ " : "€ ") + groupThousands(euros) + ","
                + (rest < 10 ? "0" : "") + rest;
    }

    /** Dutch thousands grouping with dots: {@code 1234567 -> "1.234.567"}. */
    public static String groupThousands(long value) {
        String digits = String.valueOf(Math.abs(value));
        StringBuilder sb = new StringBuilder();
        int lead = digits.length() % 3;
        if (lead == 0) lead = 3;
        sb.append(digits, 0, lead);
        for (int i = lead; i < digits.length(); i += 3) {
            sb.append('.').append(digits, i, i + 3);
        }
        return (value < 0 ? "-" : "") + sb;
    }

    /** No dots, or dot-separated groups: first 1–3 characters, every further group exactly 3. */
    private static boolean validThousands(String part) {
        if (part.indexOf('.') < 0) return true;
        String[] groups = part.split("\\.", -1);
        if (groups[0].isEmpty() || groups[0].length() > 3) return false;
        for (int i = 1; i < groups.length; i++) {
            if (groups[i].length() != 3) return false;
        }
        return true;
    }

    private static boolean allDigits(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }
}
