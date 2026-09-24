package nl.paree.climbpro.domain.quiz;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import nl.paree.climbpro.data.route.StoredClimbAttempt;
import nl.paree.climbpro.domain.quiz.PhotoQuizBuilder.Question;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

public class PhotoQuizBuilderTest {

    private static StoredClimbAttempt photo(String climbId, String file) {
        StoredClimbAttempt a = new StoredClimbAttempt();
        a.climbId = climbId;
        a.photoFileName = file;
        return a;
    }

    private static Map<String, String> names(String... idNamePairs) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < idNamePairs.length; i += 2) m.put(idNamePairs[i], idNamePairs[i + 1]);
        return m;
    }

    @Test
    public void onePhotoQuestionPerClimbWithFourDistinctOptionsIncludingAnswer() {
        List<StoredClimbAttempt> attempts = Arrays.asList(
                photo("c1", "a.jpg"), photo("c1", "b.jpg"), photo("c2", "c.jpg"),
                photo("c3", "d.jpg"), new StoredClimbAttempt());
        Map<String, String> n = names("c1", "Cauberg", "c2", "Keutenberg", "c3", "Camerig",
                "c4", "Gulperberg", "c5", "Eyserbosweg");

        List<Question> qs = PhotoQuizBuilder.build(attempts, n, 10, new Random(1));

        assertEquals(3, qs.size());
        Set<String> answers = new HashSet<>();
        for (Question q : qs) {
            answers.add(q.answer);
            assertEquals(4, q.options.size());
            assertEquals(4, new HashSet<>(q.options).size());
            assertTrue(q.options.contains(q.answer));
        }
        assertEquals(new HashSet<>(Arrays.asList("Cauberg", "Keutenberg", "Camerig")), answers);
        for (Question q : qs) {
            if (q.answer.equals("Cauberg")) {
                assertTrue(q.photoFileName.equals("a.jpg") || q.photoFileName.equals("b.jpg"));
            }
        }
    }

    @Test
    public void photographedClimbsArePreferredAsWrongOptions() {
        List<StoredClimbAttempt> attempts = new ArrayList<>();
        for (int i = 1; i <= 4; i++) attempts.add(photo("c" + i, i + ".jpg"));
        Map<String, String> n = names("c1", "A", "c2", "B", "c3", "C", "c4", "D",
                "x1", "X1", "x2", "X2", "x3", "X3");

        for (Question q : PhotoQuizBuilder.build(attempts, n, 10, new Random(7))) {
            for (String o : q.options) assertTrue(o, o.length() == 1); // never X*
        }
    }

    @Test
    public void fewerOptionsWhenOnlyTwoClimbsExist() {
        List<Question> qs = PhotoQuizBuilder.build(
                Collections.singletonList(photo("c1", "a.jpg")), names("c1", "A", "c2", "B"),
                10, new Random(3));
        assertEquals(1, qs.size());
        assertEquals(new HashSet<>(Arrays.asList("A", "B")), new HashSet<>(qs.get(0).options));
    }

    @Test
    public void emptyWhenNoPhotosOrNoWrongAnswerPossible() {
        assertTrue(PhotoQuizBuilder.build(Collections.singletonList(new StoredClimbAttempt()),
                names("c1", "A", "c2", "B"), 10, new Random()).isEmpty());
        assertTrue(PhotoQuizBuilder.build(Collections.singletonList(photo("c1", "a.jpg")),
                names("c1", "A"), 10, new Random()).isEmpty());
        // Same name on two climbs is still only one distinct option.
        assertTrue(PhotoQuizBuilder.build(Collections.singletonList(photo("c1", "a.jpg")),
                names("c1", "A", "c2", "A"), 10, new Random()).isEmpty());
    }

    @Test
    public void photoOfDeletedClimbIsSkippedAndRoundIsCapped() {
        List<StoredClimbAttempt> attempts = new ArrayList<>();
        attempts.add(photo("gone", "g.jpg"));
        Map<String, String> n = new HashMap<>();
        for (int i = 0; i < 15; i++) {
            attempts.add(photo("c" + i, i + ".jpg"));
            n.put("c" + i, "Klim " + i);
        }
        List<Question> qs = PhotoQuizBuilder.build(attempts, n, 10, new Random(5));
        assertEquals(10, qs.size());
        for (Question q : qs) assertTrue(!q.photoFileName.equals("g.jpg"));
    }
}
