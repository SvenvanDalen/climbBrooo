package nl.paree.climbpro;

import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import nl.paree.climbpro.service.SyncScheduler;
import nl.paree.climbpro.ui.routes.RouteListActivity;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SyncScheduler.schedulePeriodicSync(this);
        startActivity(new Intent(this, RouteListActivity.class));
        finish();
    }
}
