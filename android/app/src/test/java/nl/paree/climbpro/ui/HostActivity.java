package nl.paree.climbpro.ui;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import nl.paree.climbpro.R;

/** A bare AppCompat activity for helpers that need an Activity (dialogs, toasts, intents). */
public class HostActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(R.style.Theme_ClimbPro);
        super.onCreate(savedInstanceState);
    }
}
