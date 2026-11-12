package nl.paree.climbpro.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.app.DatePickerDialog;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.shadows.ShadowDatePickerDialog;

import java.time.LocalDate;
import java.time.ZoneId;

@RunWith(RobolectricTestRunner.class)
public class PickerDatesTest {

    private static final ZoneId AMS = ZoneId.of("Europe/Amsterdam");

    @Test
    public void pickerMonthsAreZeroBased() {
        assertEquals(LocalDate.of(2026, 1, 31), PickerDates.pickedDay(2026, 0, 31));
        assertEquals(LocalDate.of(2026, 12, 1), PickerDates.pickedDay(2026, 11, 1));
    }

    @Test
    public void dayOfUsesTheGivenZone() {
        // 2026-03-01 23:30 UTC is already 2 March in Amsterdam.
        long sec = LocalDate.of(2026, 3, 1).atTime(23, 30).atZone(ZoneId.of("UTC")).toEpochSecond();
        assertEquals(LocalDate.of(2026, 3, 2), PickerDates.dayOf(sec, AMS));
        assertEquals(LocalDate.of(2026, 3, 1), PickerDates.dayOf(sec, ZoneId.of("UTC")));
    }

    @Test
    public void startOfDayNoonAndTimeAreLocal() {
        LocalDate day = LocalDate.of(2026, 7, 14);
        long start = PickerDates.startOfDay(day, AMS);
        assertEquals(day.atStartOfDay(AMS).toEpochSecond(), start);
        assertEquals(start + 12 * 3600, PickerDates.noon(day, AMS));
        assertEquals(start + 7 * 3600 + 45 * 60, PickerDates.at(day, 7, 45, AMS));
    }

    @Test
    public void noonStaysOnTheDayAcrossDst() {
        LocalDate spring = LocalDate.of(2026, 3, 29); // clocks go forward at 02:00
        assertEquals(spring, PickerDates.dayOf(PickerDates.noon(spring, AMS), AMS));
        LocalDate autumn = LocalDate.of(2026, 10, 25); // clocks go back at 03:00
        assertEquals(autumn, PickerDates.dayOf(PickerDates.noon(autumn, AMS), AMS));
    }

    @Test
    public void pastDayPickerReportsNoonOfThePickedDayAndNeverTheFuture() {
        Application app = ApplicationProvider.getApplicationContext();
        long[] picked = {0};
        long initial = PickerDates.noon(LocalDate.of(2025, 5, 10), ZoneId.systemDefault());
        PastDayPicker.show(app, initial, sec -> picked[0] = sec);
        DatePickerDialog dialog = (DatePickerDialog) ShadowDatePickerDialog.getLatestDialog();
        assertNotNull(dialog);
        assertEquals(2025, dialog.getDatePicker().getYear());
        assertEquals(4, dialog.getDatePicker().getMonth());
        assertEquals(10, dialog.getDatePicker().getDayOfMonth());

        dialog.onDateChanged(dialog.getDatePicker(), 2025, 4, 12);
        dialog.onClick(dialog, DatePickerDialog.BUTTON_POSITIVE);
        assertEquals(PickerDates.noon(LocalDate.of(2025, 5, 12), ZoneId.systemDefault()), picked[0]);

        PastDayPicker.show(app, 0, sec -> picked[0] = sec);
        DatePickerDialog today = (DatePickerDialog) ShadowDatePickerDialog.getLatestDialog();
        LocalDate now = LocalDate.now(ZoneId.systemDefault());
        today.onDateChanged(today.getDatePicker(), now.getYear(), now.getMonthValue() - 1,
                now.getDayOfMonth());
        today.onClick(today, DatePickerDialog.BUTTON_POSITIVE);
        assertTrue(picked[0] <= System.currentTimeMillis() / 1000L);
    }
}
