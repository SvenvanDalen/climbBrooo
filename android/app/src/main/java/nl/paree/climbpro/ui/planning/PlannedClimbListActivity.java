package nl.paree.climbpro.ui.planning;

import android.Manifest;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;
import android.widget.CheckBox;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import nl.paree.climbpro.R;
import nl.paree.climbpro.data.planning.PlannedClimb;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * View/add/remove screen for planned climbs (issue #70 — Kalenderintegratie voor geplande
 * klimmen). Phone-only: no watch or protocol involvement. Reminders are delivered by
 * PlannedClimbReminderWorker; this activity only manages the plan list.
 */
public final class PlannedClimbListActivity extends AppCompatActivity {

    private PlannedClimbListViewModel viewModel;
    private PlannedClimbAdapter adapter;
    private TextView empty;

    private CheckBox pendingCalendarCheckbox;
    private PlannedClimbListViewModel.Pickable pendingTarget;

    private static final String STATE_PENDING_ROUTE_ID    = "pendingRouteId";
    private static final String STATE_PENDING_CLIMB_INDEX = "pendingClimbIndex";
    private static final String STATE_PENDING_LABEL       = "pendingLabel";
    private static final String STATE_PENDING_PLANNED_AT  = "pendingPlannedAtEpochSec";

    private final ActivityResultLauncher<String> notificationPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> { });
    private final ActivityResultLauncher<String[]> calendarPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(),
                    result -> continueAddFlowAfterCalendarPermission(
                            Boolean.TRUE.equals(result.get(Manifest.permission.WRITE_CALENDAR))
                                    && Boolean.TRUE.equals(result.get(Manifest.permission.READ_CALENDAR))));

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_planned_climbs);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        empty = findViewById(R.id.empty);
        RecyclerView list = findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new PlannedClimbAdapter(this::confirmRemove);
        list.setAdapter(adapter);

        viewModel = new ViewModelProvider(this).get(PlannedClimbListViewModel.class);
        viewModel.upcoming().observe(this, plans -> {
            adapter.submit(plans);
            empty.setVisibility(plans.isEmpty() ? View.VISIBLE : View.GONE);
        });
        viewModel.error().observe(this,
                msg -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show());

        findViewById(R.id.addButton).setOnClickListener(v -> showPickTargetDialog());

        ensureNotificationPermission();
        viewModel.load();

        if (savedInstanceState != null) {
            String routeId = savedInstanceState.getString(STATE_PENDING_ROUTE_ID);
            if (routeId != null) {
                pendingTarget = new PlannedClimbListViewModel.Pickable(
                        routeId,
                        savedInstanceState.getInt(STATE_PENDING_CLIMB_INDEX),
                        savedInstanceState.getString(STATE_PENDING_LABEL));
                pendingPlannedAtEpochSec = savedInstanceState.getLong(STATE_PENDING_PLANNED_AT);
            }
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        // Bridges the calendar-permission request (async system dialog) to its result callback
        // across a configuration change — without this, rotating the device while the WRITE/
        // READ_CALENDAR prompt is up silently drops the plan the user just configured.
        if (pendingTarget != null) {
            outState.putString(STATE_PENDING_ROUTE_ID, pendingTarget.routeId);
            outState.putInt(STATE_PENDING_CLIMB_INDEX, pendingTarget.climbIndex);
            outState.putString(STATE_PENDING_LABEL, pendingTarget.label);
            outState.putLong(STATE_PENDING_PLANNED_AT, pendingPlannedAtEpochSec);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        viewModel.load();
    }

    private void ensureNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
        }
    }

    private void confirmRemove(PlannedClimb plan) {
        new AlertDialog.Builder(this)
                .setTitle("Plan verwijderen?")
                .setMessage(plan.displayName)
                .setPositiveButton("Verwijderen", (d, w) -> viewModel.removePlan(plan))
                .setNegativeButton("Annuleren", null)
                .show();
    }

    private void showPickTargetDialog() {
        viewModel.loadPickables(pickables -> runOnUiThread(() -> {
            if (pickables.isEmpty()) {
                Toast.makeText(this, "Geen routes gevonden om te plannen", Toast.LENGTH_SHORT).show();
                return;
            }
            List<String> labels = new ArrayList<>();
            for (PlannedClimbListViewModel.Pickable p : pickables) labels.add(p.label);
            new AlertDialog.Builder(this)
                    .setTitle("Wat wil je plannen?")
                    .setItems(labels.toArray(new String[0]), (d, which) ->
                            showDateTimePicker(pickables.get(which)))
                    .show();
        }));
    }

    private void showDateTimePicker(PlannedClimbListViewModel.Pickable target) {
        Calendar now = Calendar.getInstance();
        new DatePickerDialog(this, (view, year, month, day) -> {
            Calendar picked = Calendar.getInstance();
            picked.set(year, month, day);
            new TimePickerDialog(this, (tView, hour, minute) -> {
                picked.set(Calendar.HOUR_OF_DAY, hour);
                picked.set(Calendar.MINUTE, minute);
                picked.set(Calendar.SECOND, 0);
                picked.set(Calendar.MILLISECOND, 0);
                showCalendarOptInAndSave(target, picked.getTimeInMillis() / 1000L);
            }, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), true).show();
        }, now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH)).show();
    }

    private long pendingPlannedAtEpochSec;

    private void showCalendarOptInAndSave(PlannedClimbListViewModel.Pickable target, long plannedAtEpochSec) {
        pendingTarget = target;
        pendingPlannedAtEpochSec = plannedAtEpochSec;

        CheckBox checkbox = new CheckBox(this);
        checkbox.setText("Ook toevoegen aan agenda");
        checkbox.setPadding(48, 32, 48, 32);
        pendingCalendarCheckbox = checkbox;

        new AlertDialog.Builder(this)
                .setTitle("Klim inplannen")
                .setMessage(target.label.trim())
                .setView(checkbox)
                .setPositiveButton("Plannen", (d, w) -> {
                    if (checkbox.isChecked()) {
                        requestCalendarPermissionThenSave();
                    } else {
                        viewModel.addPlan(pendingTarget, pendingPlannedAtEpochSec, false);
                    }
                })
                .setNegativeButton("Annuleren", null)
                .show();
    }

    private void requestCalendarPermissionThenSave() {
        boolean granted = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_CALENDAR)
                        == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR)
                        == PackageManager.PERMISSION_GRANTED;
        if (granted) {
            viewModel.addPlan(pendingTarget, pendingPlannedAtEpochSec, true);
        } else {
            // PlannedClimbCalendarWriter.findWritableCalendarId() needs READ_CALENDAR too,
            // not just WRITE_CALENDAR — request both so the opt-in actually works.
            calendarPermissionLauncher.launch(new String[]{
                    Manifest.permission.WRITE_CALENDAR,
                    Manifest.permission.READ_CALENDAR
            });
        }
    }

    private void continueAddFlowAfterCalendarPermission(boolean granted) {
        if (pendingTarget == null) return;
        // Whether granted or not, the plan itself must still be saved — calendar sync is
        // optional and the reminder must never be blocked on it.
        viewModel.addPlan(pendingTarget, pendingPlannedAtEpochSec, granted);
        if (!granted) {
            Toast.makeText(this,
                    "Geen agenda-toestemming — klim is wel gepland, herinnering werkt gewoon.",
                    Toast.LENGTH_LONG).show();
        }
        pendingTarget = null;
    }
}
