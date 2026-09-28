package nl.paree.climbpro.data.planning;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FavoriteStartPointTest {

    private static final double EPS = 1e-9;

    @Test
    public void validCoordinateBounds() {
        assertTrue(FavoriteStartPoint.isValidCoordinate(0, 0));
        assertTrue(FavoriteStartPoint.isValidCoordinate(-90, 180));
        assertTrue(FavoriteStartPoint.isValidCoordinate(90, -180));
        assertFalse(FavoriteStartPoint.isValidCoordinate(90.0001, 0));
        assertFalse(FavoriteStartPoint.isValidCoordinate(0, 180.0001));
        assertFalse(FavoriteStartPoint.isValidCoordinate(Double.NaN, 0));
        assertFalse(FavoriteStartPoint.isValidCoordinate(0, Double.POSITIVE_INFINITY));
    }

    @Test
    public void parsesCommonCoordinateNotations() {
        assertArrayEquals(new double[]{50.8512, 5.6904},
                FavoriteStartPoint.parseCoordinates("50.8512, 5.6904"), EPS);
        assertArrayEquals(new double[]{50.8512, 5.6904},
                FavoriteStartPoint.parseCoordinates("50.8512,5.6904"), EPS);
        assertArrayEquals(new double[]{50.8512, 5.6904},
                FavoriteStartPoint.parseCoordinates("  50.8512   5.6904 "), EPS);
        // Dutch decimal comma, separated by a semicolon.
        assertArrayEquals(new double[]{50.8512, 5.6904},
                FavoriteStartPoint.parseCoordinates("50,8512; 5,6904"), EPS);
        assertArrayEquals(new double[]{-33.86, -6.26},
                FavoriteStartPoint.parseCoordinates("-33.86, -6.26"), EPS);
    }

    @Test
    public void rejectsGarbageAndOutOfRange() {
        assertNull(FavoriteStartPoint.parseCoordinates(null));
        assertNull(FavoriteStartPoint.parseCoordinates(""));
        assertNull(FavoriteStartPoint.parseCoordinates("Thuis"));
        assertNull(FavoriteStartPoint.parseCoordinates("50.85"));
        assertNull(FavoriteStartPoint.parseCoordinates("50.85, 5.69, 3"));
        assertNull(FavoriteStartPoint.parseCoordinates("95.0, 5.0"));
        assertNull(FavoriteStartPoint.parseCoordinates("50.0, 200.0"));
        assertNull(FavoriteStartPoint.parseCoordinates("NaN, 5.0"));
    }
}
