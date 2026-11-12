package nl.paree.climbpro.protocol;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Headless static checks over Monkey C source (no Connect IQ SDK needed). */
public final class MonkeyCSourceGuard {

    private MonkeyCSourceGuard() {}

    /**
     * Replace line comments, block comments, and string literals with spaces so token
     * counting is reliable.
     */
    static String stripCommentsAndStrings(String src) {
        StringBuilder out = new StringBuilder(src.length());
        int n = src.length();
        int i = 0;
        while (i < n) {
            char c = src.charAt(i);
            char d = (i + 1 < n) ? src.charAt(i + 1) : '\0';
            if (c == '/' && d == '/') {
                i = skipLineComment(src, i);
            } else if (c == '/' && d == '*') {
                i = skipBlockComment(src, i);
            } else if (c == '"') {
                i = skipString(src, i);
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /** Index of the newline ending the line comment at {@code i} (or the end of the source). */
    private static int skipLineComment(String src, int i) {
        int n = src.length();
        while (i < n && src.charAt(i) != '\n') { i++; }
        return i;
    }

    /** Index just past the block comment starting at {@code i}. */
    private static int skipBlockComment(String src, int i) {
        int n = src.length();
        i += 2;
        while (i + 1 < n && !(src.charAt(i) == '*' && src.charAt(i + 1) == '/')) { i++; }
        return i + 2;
    }

    /** Index just past the string literal starting at {@code i} (escapes skipped). */
    private static int skipString(String src, int i) {
        int n = src.length();
        i++;
        while (i < n && src.charAt(i) != '"') {
            if (src.charAt(i) == '\\') { i++; }
            i++;
        }
        return i + 1;
    }

    /** True when {} and () are balanced (ignoring comments/strings). */
    static boolean bracesBalanced(String src) {
        String s = stripCommentsAndStrings(src);
        int curly = 0;
        int paren = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') { curly++; }
            else if (c == '}') { curly--; if (curly < 0) { return false; } }
            else if (c == '(') { paren++; }
            else if (c == ')') { paren--; if (paren < 0) { return false; } }
        }
        return curly == 0 && paren == 0;
    }

    private static final Pattern TEST_FN =
            Pattern.compile("\\(:test\\)\\s*function\\s+(\\w+)");

    /** Names of (:test) functions whose body lacks a {@code return true}. */
    static List<String> testsWithoutReturnTrue(String src) {
        String s = stripCommentsAndStrings(src);
        List<int[]> spans = new ArrayList<>();
        List<String> names = new ArrayList<>();
        Matcher m = TEST_FN.matcher(s);
        while (m.find()) {
            spans.add(new int[]{ m.start(), m.end() });
            names.add(m.group(1));
        }
        List<String> missing = new ArrayList<>();
        for (int k = 0; k < spans.size(); k++) {
            int from = spans.get(k)[1];
            int to = (k + 1 < spans.size()) ? spans.get(k + 1)[0] : s.length();
            String body = s.substring(from, to);
            if (!body.contains("return true")) { missing.add(names.get(k)); }
        }
        return missing;
    }
}
