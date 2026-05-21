package nl.paree.climbpro.domain;

import nl.paree.climbpro.domain.segment.GradientColor;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class GradientColorTest {

    @Test public void negativeGradientIsLightYellow() {
        assertEquals(0, GradientColor.forGradient(-0.05));
    }

    @Test public void zeroGradientIsLightYellow() {
        assertEquals(0, GradientColor.forGradient(0.0));
    }

    @Test public void justBelow2PctIsLightYellow() {
        assertEquals(0, GradientColor.forGradient(0.0199));
    }

    @Test public void exactly2PctIsYellow() {
        assertEquals(1, GradientColor.forGradient(0.02));
    }

    @Test public void justBelow4PctIsYellow() {
        assertEquals(1, GradientColor.forGradient(0.0399));
    }

    @Test public void exactly4PctIsDarkYellow() {
        assertEquals(2, GradientColor.forGradient(0.04));
    }

    @Test public void exactly6PctIsOrange() {
        assertEquals(3, GradientColor.forGradient(0.06));
    }

    @Test public void exactly8PctIsDarkOrange() {
        assertEquals(4, GradientColor.forGradient(0.08));
    }

    @Test public void exactly10PctIsRed() {
        assertEquals(5, GradientColor.forGradient(0.10));
    }

    @Test public void above10PctIsRed() {
        assertEquals(5, GradientColor.forGradient(0.25));
    }
}
