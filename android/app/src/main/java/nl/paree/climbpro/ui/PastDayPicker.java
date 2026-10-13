package nl.paree.climbpro.ui;

import android.app.DatePickerDialog;
import android.content.Context;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;


/**
 * Date picker for a day in the past, shared by the log dialogs: the picked day lands at noon
 * (local time) and never in the future, since a noon timestamp for today may still lie ahead.
 */
public final class PastDayPicker {

    /** Receives the picked day as epoch seconds. */
    public interface Listener {
        void onPicked(long epochSec);
    }

    private PastDayPicker() {}

    /** Shows the picker on {@code initialEpochSec} (today when it is 0 or less). */
    public static void show(Context context, long initialEpochSec, Listener listener) {
        ZoneId zone = ZoneId.systemDefault();
        LocalDate base = initialEpochSec > 0
                ? Instant.ofEpochSecond(initialEpochSec).atZone(zone).toLocalDate()
                : LocalDate.now(zone);
        DatePickerDialog dp = new DatePickerDialog(context, (view, y, m, d) -> {
            long noon = LocalDate.of(y, m + 1, d).atTime(12, 0).atZone(zone).toEpochSecond();
            listener.onPicked(Math.min(noon, System.currentTimeMillis() / 1000L));
        }, base.getYear(), base.getMonthValue() - 1, base.getDayOfMonth());
        dp.getDatePicker().setMaxDate(System.currentTimeMillis());
        dp.show();
    }
}
