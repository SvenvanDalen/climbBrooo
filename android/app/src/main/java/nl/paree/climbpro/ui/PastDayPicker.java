package nl.paree.climbpro.ui;

import android.app.DatePickerDialog;
import android.content.Context;

import java.util.Calendar;

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
        Calendar base = Calendar.getInstance();
        if (initialEpochSec > 0) base.setTimeInMillis(initialEpochSec * 1000L);
        DatePickerDialog dp = new DatePickerDialog(context, (view, y, m, d) -> {
            Calendar cal = Calendar.getInstance();
            cal.clear();
            cal.set(y, m, d, 12, 0, 0);
            listener.onPicked(Math.min(cal.getTimeInMillis(), System.currentTimeMillis()) / 1000L);
        }, base.get(Calendar.YEAR), base.get(Calendar.MONTH), base.get(Calendar.DAY_OF_MONTH));
        dp.getDatePicker().setMaxDate(System.currentTimeMillis());
        dp.show();
    }
}
