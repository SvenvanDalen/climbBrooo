package nl.paree.climbpro.testsupport;

import static androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom;
import static androidx.test.espresso.matcher.ViewMatchers.isDescendantOfA;
import static androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.anyOf;

import android.view.View;
import android.widget.ScrollView;
import android.widget.SeekBar;

import androidx.core.widget.NestedScrollView;
import androidx.test.espresso.UiController;
import androidx.test.espresso.ViewAction;
import androidx.test.espresso.action.GeneralClickAction;
import androidx.test.espresso.action.Press;
import androidx.test.espresso.action.ScrollToAction;
import androidx.test.espresso.action.Tap;
import androidx.test.espresso.matcher.ViewMatchers;

import org.hamcrest.Matcher;

/** Espresso actions the stock set lacks. */
public final class EspressoActions {

    private EspressoActions() {}

    /** scrollTo() that also works inside a NestedScrollView. */
    public static ViewAction scrollIntoView() {
        return new ViewAction() {
            @Override public Matcher<View> getConstraints() {
                return allOf(withEffectiveVisibility(ViewMatchers.Visibility.VISIBLE),
                        isDescendantOfA(anyOf(isAssignableFrom(NestedScrollView.class),
                                isAssignableFrom(ScrollView.class))));
            }

            @Override public String getDescription() {
                return "scroll into view";
            }

            @Override public void perform(UiController ui, View view) {
                new ScrollToAction().perform(ui, view);
            }
        };
    }

    /**
     * Taps a SeekBar at {@code fraction} (0..1) of its track, so the listener sees a real user
     * change (fromUser = true) — setProgress() would report fromUser = false.
     */
    public static ViewAction tapSeekBarAt(float fraction) {
        return new GeneralClickAction(Tap.SINGLE, view -> {
            SeekBar bar = (SeekBar) view;
            int[] xy = new int[2];
            bar.getLocationOnScreen(xy);
            float track = bar.getWidth() - bar.getPaddingLeft() - bar.getPaddingRight();
            float x = xy[0] + bar.getPaddingLeft() + track * fraction;
            float y = xy[1] + bar.getHeight() / 2f;
            return new float[]{x, y};
        }, Press.FINGER, 0, 0);
    }
}
