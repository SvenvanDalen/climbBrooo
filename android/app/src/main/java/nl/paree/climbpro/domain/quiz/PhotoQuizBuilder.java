package nl.paree.climbpro.domain.quiz;

import nl.paree.climbpro.data.route.StoredClimbAttempt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * "Welke klim is dit?" (issue #252): turns the rider's own attempt photos into multiple-choice
 * questions. Pure; the caller passes the climb names (by {@code ClimbIdentity} key) and a
 * {@link Random} so tests are deterministic.
 *
 * <p>One question per photographed climb (a climb with several photos gets one of them at
 * random), so the same climb never comes up twice in a round. Wrong options are other
 * climbs' names — photographed climbs first, since those are the ones the rider will
 * recognise — and names are deduplicated so two climbs that happen to share a name can
 * never both show up as options.
 */
public final class PhotoQuizBuilder {

    public static final int OPTIONS = 4;

    public static final class Question {
        public final String photoFileName;
        public final String answer;
        /** {@link #answer} plus up to three distinct wrong names, shuffled. */
        public final List<String> options;

        Question(String photoFileName, String answer, List<String> options) {
            this.photoFileName = photoFileName;
            this.answer = answer;
            this.options = Collections.unmodifiableList(options);
        }
    }

    private PhotoQuizBuilder() {}

    /**
     * @param namesByClimbId display name per climb; attempts whose climb has no name here
     *                       (the climb was deleted) are skipped
     * @param maxQuestions   round length
     * @return questions in random order; empty when fewer than two distinct climb names
     *         exist (no wrong answer to offer)
     */
    public static List<Question> build(List<StoredClimbAttempt> attempts,
                                       Map<String, String> namesByClimbId,
                                       int maxQuestions, Random random) {
        Map<String, List<String>> photosByName = new LinkedHashMap<>();
        for (StoredClimbAttempt a : attempts) {
            if (a.photoFileName == null || a.photoFileName.isEmpty()) continue;
            String name = namesByClimbId.get(a.climbId);
            if (name == null || name.isEmpty()) continue;
            photosByName.computeIfAbsent(name, k -> new ArrayList<>()).add(a.photoFileName);
        }
        Set<String> allNames = new LinkedHashSet<>(photosByName.keySet());
        for (String n : namesByClimbId.values()) if (n != null && !n.isEmpty()) allNames.add(n);
        if (photosByName.isEmpty() || allNames.size() < 2) return new ArrayList<>();

        List<String> photographed = new ArrayList<>(photosByName.keySet());
        Collections.shuffle(photographed, random);
        List<Question> out = new ArrayList<>();
        for (String answer : photographed) {
            if (out.size() >= maxQuestions) break;
            List<String> photos = photosByName.get(answer);
            String photo = photos.get(random.nextInt(photos.size()));
            out.add(new Question(photo, answer, options(answer, photosByName.keySet(), allNames, random)));
        }
        return out;
    }

    private static List<String> options(String answer, Set<String> photographed,
                                        Set<String> allNames, Random random) {
        List<String> preferred = new ArrayList<>();
        List<String> rest = new ArrayList<>();
        for (String n : allNames) {
            if (n.equals(answer)) continue;
            (photographed.contains(n) ? preferred : rest).add(n);
        }
        Collections.shuffle(preferred, random);
        Collections.shuffle(rest, random);
        List<String> options = new ArrayList<>();
        options.add(answer);
        for (List<String> pool : java.util.Arrays.asList(preferred, rest)) {
            for (String n : pool) {
                if (options.size() >= OPTIONS) break;
                options.add(n);
            }
        }
        Collections.shuffle(options, random);
        return options;
    }
}
