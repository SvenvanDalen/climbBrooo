package nl.paree.climbpro.ui.health;

import android.os.Bundle;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

/**
 * Privacy explanation Health Connect shows from its permission screen (issue #255). Required
 * by Health Connect: without an activity handling the rationale intent, the permission
 * request is refused.
 */
public final class HealthConnectRationaleActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        TextView text = new TextView(this);
        text.setPadding(pad, pad, pad, pad);
        text.setTextSize(16);
        text.setText("ClimbPro en Health Connect\n\n"
                + "Schrijven: je fietsritten uit Strava als training, met afstand, "
                + "hoogtemeters en (als Strava het weet) calorieën. Zo staan ze naast je "
                + "andere gezondheidsdata.\n\n"
                + "Lezen: alleen je meest recente gewicht, en alleen als je in de instellingen "
                + "op 'Gewicht overnemen' tikt. Het wordt gebruikt voor de klimtijd-schattingen "
                + "van je rijdersprofiel.\n\n"
                + "Er gaat niets naar een server van ClimbPro. Je trekt de toegang in via "
                + "Health Connect.");
        ScrollView scroll = new ScrollView(this);
        scroll.addView(text);
        setContentView(scroll);
    }
}
