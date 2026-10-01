package nl.paree.climbpro.i18n;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;

import nl.paree.climbpro.ui.settings.AppLanguage;

/**
 * Multilingual support (issue #261): every translatable string, plural and string-array in the
 * Dutch base ({@code values/}) must exist in each translation ({@code values-en/-de/-fr/-it}),
 * with the same format placeholders, and translations may not carry keys the base dropped.
 * Plain JVM XML parsing, so a missing translation fails {@code testDebugUnitTest} long before
 * a user sees a Dutch string in an English UI.
 */
public class StringTranslationCompletenessTest {

    private static final List<String> TRANSLATIONS = Arrays.asList("en", "de", "fr", "it");

    /**
     * %1$s, %2$.1f, %d, %+.0f … but not the escaped %% nor a literal "95 % FTP" (no space
     * flag: prose like "% van" must not count as a placeholder).
     */
    private static final Pattern PLACEHOLDER =
            Pattern.compile("%(?:(\\d+)\\$)?[-#+0,(]*\\d*(?:\\.\\d+)?([a-zA-Z])");

    @Test
    public void everyBaseKeyIsTranslated() throws Exception {
        Map<String, Entry> base = load(new File(resDir(), "values"));
        assertTrue("base strings not found", base.size() > 100);
        List<String> problems = new ArrayList<>();
        for (String lang : TRANSLATIONS) {
            Map<String, Entry> tr = load(new File(resDir(), "values-" + lang));
            for (Map.Entry<String, Entry> e : base.entrySet()) {
                if (!e.getValue().translatable) continue;
                if (!tr.containsKey(e.getKey())) {
                    problems.add(lang + ": missing " + e.getKey());
                }
            }
        }
        if (!problems.isEmpty()) fail(problems.size() + " missing translations:\n"
                + String.join("\n", problems));
    }

    @Test
    public void translationsHaveNoStaleOrUntranslatableKeys() throws Exception {
        Map<String, Entry> base = load(new File(resDir(), "values"));
        List<String> problems = new ArrayList<>();
        for (String lang : TRANSLATIONS) {
            for (Map.Entry<String, Entry> e : load(new File(resDir(), "values-" + lang)).entrySet()) {
                Entry b = base.get(e.getKey());
                if (b == null) problems.add(lang + ": stale key " + e.getKey());
                else if (!b.translatable) problems.add(lang + ": translates untranslatable " + e.getKey());
            }
        }
        if (!problems.isEmpty()) fail(String.join("\n", problems));
    }

    @Test
    public void placeholdersAndArraySizesMatchTheBase() throws Exception {
        Map<String, Entry> base = load(new File(resDir(), "values"));
        List<String> problems = new ArrayList<>();
        for (String lang : TRANSLATIONS) {
            Map<String, Entry> tr = load(new File(resDir(), "values-" + lang));
            for (Map.Entry<String, Entry> e : tr.entrySet()) {
                Entry b = base.get(e.getKey());
                if (b == null) continue;
                Entry t = e.getValue();
                if (!b.kind.equals(t.kind)) {
                    problems.add(lang + ": " + e.getKey() + " is a " + t.kind + ", base is a " + b.kind);
                    continue;
                }
                if ("string-array".equals(b.kind) && b.values.size() != t.values.size()) {
                    problems.add(lang + ": " + e.getKey() + " has " + t.values.size()
                            + " items, base has " + b.values.size());
                }
                if (b.formatted && !placeholders(b.formatText()).equals(placeholders(t.formatText()))) {
                    problems.add(lang + ": " + e.getKey() + " placeholders "
                            + placeholders(t.formatText()) + " != base " + placeholders(b.formatText()));
                }
                for (String v : t.values) {
                    if (hasUnescapedApostrophe(v)) {
                        problems.add(lang + ": " + e.getKey() + " has an unescaped apostrophe");
                    }
                }
            }
        }
        if (!problems.isEmpty()) fail(String.join("\n", problems));
    }

    @Test
    public void languagePickerMatchesLocaleConfig() throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        Document doc = f.newDocumentBuilder().parse(new File(resDir(), "xml/locales_config.xml"));
        NodeList locales = doc.getElementsByTagName("locale");
        TreeSet<String> configured = new TreeSet<>();
        for (int i = 0; i < locales.getLength(); i++) {
            configured.add(((Element) locales.item(i)).getAttributeNS(
                    "http://schemas.android.com/apk/res/android", "name"));
        }
        TreeSet<String> picker = new TreeSet<>(Arrays.asList(AppLanguage.TAGS));
        picker.remove("");
        assertEquals(configured, picker);
        TreeSet<String> expected = new TreeSet<>(TRANSLATIONS);
        expected.add("nl");
        assertEquals(expected, configured);
        assertEquals(AppLanguage.TAGS.length, AppLanguage.ENDONYMS.length);
    }

    @Test
    public void appLanguageIndexMapsRegionalTags() {
        assertEquals(0, AppLanguage.indexOf(""));
        assertEquals(0, AppLanguage.indexOf("es"));
        assertEquals(3, AppLanguage.indexOf("de-AT"));
        assertEquals(2, AppLanguage.indexOf("en_GB"));
        assertEquals(5, AppLanguage.indexOf("it"));
    }

    // ---------------------------------------------------------------------------------------

    private static final class Entry {
        final String kind;
        final boolean translatable;
        final boolean formatted;
        final List<String> values;

        Entry(String kind, boolean translatable, boolean formatted, List<String> values) {
            this.kind = kind;
            this.translatable = translatable;
            this.formatted = formatted;
            this.values = values;
        }

        /** For plurals the "other" form carries every placeholder; else the first/only value. */
        String formatText() {
            return values.isEmpty() ? "" : values.get(values.size() - 1);
        }
    }

    private static TreeSet<String> placeholders(String text) {
        String s = text.replace("%%", "");
        TreeSet<String> out = new TreeSet<>();
        Matcher m = PLACEHOLDER.matcher(s);
        int implicit = 0;
        while (m.find()) {
            String index = m.group(1) != null ? m.group(1) : String.valueOf(++implicit);
            out.add(index + ":" + m.group(2).toLowerCase(java.util.Locale.ROOT));
        }
        return out;
    }

    private static boolean hasUnescapedApostrophe(String raw) {
        String t = raw.trim();
        if (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"")) return false;
        for (int i = 0; i < t.length(); i++) {
            if (t.charAt(i) == '\'' && (i == 0 || t.charAt(i - 1) != '\\')) return true;
        }
        return false;
    }

    private static Map<String, Entry> load(File dir) throws Exception {
        assertTrue("missing resource dir " + dir, dir.isDirectory());
        Map<String, Entry> out = new LinkedHashMap<>();
        File[] files = dir.listFiles((d, n) -> n.endsWith(".xml"));
        if (files == null) return out;
        Arrays.sort(files);
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        for (File file : files) {
            Element root = f.newDocumentBuilder().parse(file).getDocumentElement();
            for (Node n = root.getFirstChild(); n != null; n = n.getNextSibling()) {
                if (!(n instanceof Element)) continue;
                Element el = (Element) n;
                String kind = el.getTagName();
                if (!kind.equals("string") && !kind.equals("plurals") && !kind.equals("string-array")) {
                    continue;
                }
                boolean translatable = !"false".equals(el.getAttribute("translatable"));
                boolean formatted = !"false".equals(el.getAttribute("formatted"));
                List<String> values = new ArrayList<>();
                if (kind.equals("string")) {
                    values.add(el.getTextContent());
                } else {
                    NodeList items = el.getElementsByTagName("item");
                    for (int i = 0; i < items.getLength(); i++) values.add(items.item(i).getTextContent());
                }
                String name = el.getAttribute("name");
                if (out.put(name, new Entry(kind, translatable, formatted, values)) != null) {
                    fail("duplicate resource " + name + " in " + dir);
                }
            }
        }
        return out;
    }

    private static File resDir() {
        File dir = new File("").getAbsoluteFile();
        for (int i = 0; i < 8 && dir != null; i++) {
            File res = new File(dir, "android/app/src/main/res");
            if (res.isDirectory()) return res;
            res = new File(dir, "src/main/res");
            if (res.isDirectory()) return res;
            dir = dir.getParentFile();
        }
        throw new IllegalStateException("src/main/res not found");
    }
}
