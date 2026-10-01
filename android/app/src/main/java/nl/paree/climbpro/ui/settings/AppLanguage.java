package nl.paree.climbpro.ui.settings;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;

/**
 * Per-app language choice (issue #261). Index 0 follows the system language; the others
 * pin one of the translated locales. Dutch is the base language ({@code values/}), the
 * rest live in {@code values-en/-de/-fr/-it}. Keep in sync with {@code res/xml/locales_config.xml}.
 *
 * <p>AppCompat persists the choice itself (system storage on API 33+, the
 * {@code AppLocalesMetadataHolderService} in the manifest below that), so there is no pref here.
 */
public final class AppLanguage {

    /** Language tags in picker order; "" = follow the system. */
    public static final String[] TAGS = {"", "nl", "en", "de", "fr", "it"};

    /** Endonyms, so a user who picked a language they cannot read can still find their own. */
    public static final String[] ENDONYMS = {
            null, "Nederlands", "English", "Deutsch", "Français", "Italiano"};

    private AppLanguage() {}

    /** Index into {@link #TAGS} of the currently applied app language (0 = system). */
    public static int currentIndex() {
        LocaleListCompat locales = AppCompatDelegate.getApplicationLocales();
        if (locales.isEmpty() || locales.get(0) == null) return 0;
        return indexOf(locales.get(0).getLanguage());
    }

    /** Index for a language tag such as "de" or "de-AT"; unknown tags map to 0 (system). */
    public static int indexOf(String languageTag) {
        if (languageTag == null || languageTag.isEmpty()) return 0;
        String language = languageTag.split("[-_]")[0];
        for (int i = 1; i < TAGS.length; i++) {
            if (TAGS[i].equalsIgnoreCase(language)) return i;
        }
        return 0;
    }

    /** Applies the language at {@code index}; AppCompat recreates the running activities. */
    public static void apply(int index) {
        String tag = index <= 0 || index >= TAGS.length ? "" : TAGS[index];
        AppCompatDelegate.setApplicationLocales(tag.isEmpty()
                ? LocaleListCompat.getEmptyLocaleList()
                : LocaleListCompat.forLanguageTags(tag));
    }
}
