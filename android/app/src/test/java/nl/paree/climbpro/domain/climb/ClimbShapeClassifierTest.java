package nl.paree.climbpro.domain.climb;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import nl.paree.climbpro.data.route.StoredClimb;

public class ClimbShapeClassifierTest {

    @Test
    public void nearConstantGradient_classifiesSteady() {
        double[] gradients = {
                0.048, 0.052, 0.049, 0.051, 0.050,
                0.048, 0.052, 0.050, 0.049, 0.051,
                0.050, 0.048, 0.052
        };

        assertEquals(ClimbShape.STEADY, ClimbShapeClassifier.classify(gradients));
    }

    @Test
    public void steadilyRampingGradient_classifiesSteepFinish() {
        // 13 segments ramping linearly from 3% to ~11.3%.
        double[] gradients = new double[13];
        for (int i = 0; i < gradients.length; i++) {
            gradients[i] = 0.03 + i * (0.08 / 12.0);
        }

        assertEquals(ClimbShape.STEEP_FINISH, ClimbShapeClassifier.classify(gradients));
    }

    @Test
    public void steadilyEasingGradient_classifiesEasyStart() {
        // Mirror image of the steep-finish case: starts steep, eases off toward the top.
        double[] gradients = new double[13];
        for (int i = 0; i < gradients.length; i++) {
            gradients[i] = 0.11 - i * (0.08 / 12.0);
        }

        assertEquals(ClimbShape.EASY_START, ClimbShapeClassifier.classify(gradients));
    }

    @Test
    public void alternatingSteepAndShallowSegments_classifiesIrregular() {
        // No net trend (starts and ends shallow), but wild segment-to-segment swings.
        double[] gradients = {
                0.02, 0.10, 0.02, 0.10, 0.02,
                0.10, 0.02, 0.10, 0.02, 0.10,
                0.02, 0.10, 0.02
        };

        assertEquals(ClimbShape.IRREGULAR, ClimbShapeClassifier.classify(gradients));
    }

    @Test
    public void fewerThanTwoSegments_defaultsToSteady() {
        assertEquals(ClimbShape.STEADY, ClimbShapeClassifier.classify(new double[]{0.05}));
        assertEquals(ClimbShape.STEADY, ClimbShapeClassifier.classify(new double[0]));
        assertEquals(ClimbShape.STEADY, ClimbShapeClassifier.classify((double[]) null));
    }

    // -- effectiveShape (issue #36: manual override) ------------------------------------------

    @Test
    public void effectiveShape_overridePresent_overrideWins() {
        StoredClimb c = new StoredClimb();
        c.shape         = ClimbShape.STEADY.name();
        c.shapeOverride = ClimbShape.IRREGULAR.name();

        assertEquals(ClimbShape.IRREGULAR, ClimbShapeClassifier.effectiveShape(c));
    }

    @Test
    public void effectiveShape_overrideAbsent_fallsBackToAutoShape() {
        StoredClimb c = new StoredClimb();
        c.shape         = ClimbShape.STEEP_FINISH.name();
        c.shapeOverride = null;

        assertEquals(ClimbShape.STEEP_FINISH, ClimbShapeClassifier.effectiveShape(c));
    }

    @Test
    public void effectiveShape_bothNull_classifiesFromSegmentsWithoutCrashing() {
        StoredClimb c = new StoredClimb();
        c.shape         = null;
        c.shapeOverride = null;
        c.segments      = null;

        assertEquals(ClimbShape.STEADY, ClimbShapeClassifier.effectiveShape(c));
    }

    @Test
    public void effectiveShape_nullClimb_defaultsToSteadyWithoutCrashing() {
        assertEquals(ClimbShape.STEADY, ClimbShapeClassifier.effectiveShape(null));
    }

    @Test
    public void effectiveShape_unknownOverrideValue_ignoredFallsBackToAuto() {
        StoredClimb c = new StoredClimb();
        c.shape         = ClimbShape.EASY_START.name();
        c.shapeOverride = "NOT_A_REAL_SHAPE";

        assertEquals(ClimbShape.EASY_START, ClimbShapeClassifier.effectiveShape(c));
    }
}
