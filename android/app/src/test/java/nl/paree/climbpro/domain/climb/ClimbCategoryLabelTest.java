package nl.paree.climbpro.domain.climb;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ClimbCategoryLabelTest {

    @Test
    public void forCategory_returnsShortLabels() {
        assertEquals("HC", ClimbCategoryLabel.forCategory(ClimbCategory.HC));
        assertEquals("Cat. 1", ClimbCategoryLabel.forCategory(ClimbCategory.CAT_1));
        assertEquals("Cat. 2", ClimbCategoryLabel.forCategory(ClimbCategory.CAT_2));
        assertEquals("Cat. 3", ClimbCategoryLabel.forCategory(ClimbCategory.CAT_3));
        assertEquals("Cat. 4", ClimbCategoryLabel.forCategory(ClimbCategory.CAT_4));
    }

    @Test
    public void forCategory_uncategorizedAndNull_areBlank() {
        assertEquals("", ClimbCategoryLabel.forCategory(ClimbCategory.UNCATEGORIZED));
        assertEquals("", ClimbCategoryLabel.forCategory(null));
    }

    @Test
    public void forStoredClimb_computesFromLengthAndGradient() {
        nl.paree.climbpro.data.route.StoredClimb climb = new nl.paree.climbpro.data.route.StoredClimb();
        climb.length = 2000;
        climb.avgGradient = 0.08; // score = 16000 -> CAT_3

        assertEquals("Cat. 3", ClimbCategoryLabel.forStoredClimb(climb));
    }

    @Test
    public void forStoredClimb_null_isBlank() {
        assertEquals("", ClimbCategoryLabel.forStoredClimb(null));
    }
}
