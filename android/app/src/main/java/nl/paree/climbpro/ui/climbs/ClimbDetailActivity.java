package nl.paree.climbpro.ui.climbs;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.MenuItem;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;

import nl.paree.climbpro.databinding.ActivityClimbDetailBinding;

/**
 * Activity that displays detailed information about a single climb
 * within a selected route.
 *
 * <p>This screen shows:
 * <ul>
 *     <li>The climb name</li>
 *     <li>Total distance</li>
 *     <li>Average gradient</li>
 *     <li>Elevation gain</li>
 *     <li>A visual climb profile</li>
 *     <li>A list of climb segments</li>
 * </ul>
 *
 * <p>The activity receives a route ID and climb index through intent extras
 * and loads the corresponding climb via {@link ClimbDetailViewModel}.
 *
 * <p>Users can also rename the climb using the rename dialog.
 */
public final class ClimbDetailActivity extends AppCompatActivity {

    /**
     * Intent extra key for the route ID.
     */
    private static final String EXTRA_ROUTE_ID = "route_id";

    /**
     * Intent extra key for the climb index within the route.
     */
    private static final String EXTRA_CLIMB_INDEX = "climb_index";

    private ActivityClimbDetailBinding binding;
    private ClimbDetailViewModel viewModel;
    private ClimbSegmentAdapter adapter;

    /**
     * ID of the currently selected route.
     */
    private String routeId;

    /**
     * Index of the selected climb inside the route.
     */
    private int climbIndex;

    /**
     * Creates an intent used to open this activity.
     *
     * @param ctx        Context used to create the intent
     * @param routeId    Unique route identifier
     * @param climbIndex Index of the climb inside the route
     * @return Configured intent for launching this activity
     */
    public static Intent intentFor(Context ctx, String routeId, int climbIndex) {
        Intent i = new Intent(ctx, ClimbDetailActivity.class);
        i.putExtra(EXTRA_ROUTE_ID, routeId);
        i.putExtra(EXTRA_CLIMB_INDEX, climbIndex);
        return i;
    }

    /**
     * Initializes the UI, toolbar, RecyclerView, ViewModel observers,
     * and loads the requested climb.
     *
     * @param savedInstanceState Previously saved instance state
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityClimbDetailBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        setSupportActionBar(binding.toolbar);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        routeId = getIntent().getStringExtra(EXTRA_ROUTE_ID);
        climbIndex = getIntent().getIntExtra(EXTRA_CLIMB_INDEX, 0);

        viewModel = new ViewModelProvider(this).get(ClimbDetailViewModel.class);
        adapter = new ClimbSegmentAdapter();

        binding.segmentsRecycler.setLayoutManager(new LinearLayoutManager(this));
        binding.segmentsRecycler.setAdapter(adapter);

        /*
         * Observe climb data updates and refresh the UI whenever
         * the selected climb changes.
         */
        viewModel.climb().observe(this, climb -> {
            if (climb == null) return;

            String name = climb.userDisplayName != null
                    ? climb.userDisplayName
                    : climb.name;

            binding.toolbar.setTitle(
                    name != null ? name : "Climb " + (climbIndex + 1)
            );

            binding.climbStats.setText(String.format(
                    "%d m total · %.1f%% avg gradient · %d m elevation gain",
                    climb.length,
                    climb.avgGradient * 100,
                    climb.elevationGain
            ));

            binding.climbProfile.setSegments(climb.segments);
            adapter.setItems(climb.segments);
        });

        /*
         * Show errors from the ViewModel as toast messages.
         */
        viewModel.error().observe(this,
                msg -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());

        /*
         * Open rename dialog when the rename button is pressed.
         */
        binding.btnRenameClimb.setOnClickListener(v -> showRenameDialog());

        /*
         * Open re-segment dialog when the re-segment button is pressed.
         */
        binding.btnReSegment.setOnClickListener(v -> showReSegmentDialog());

        /*
         * Show a confirmation toast whenever the ViewModel reports a successful save.
         */
        viewModel.saved().observe(this, isSaved -> {
            if (Boolean.TRUE.equals(isSaved)) {
                Toast.makeText(this, "Opgeslagen", Toast.LENGTH_SHORT).show();
            }
        });

        /*
         * Load the requested climb from the ViewModel.
         */
        viewModel.loadClimb(routeId, climbIndex);
    }

    /**
     * Handles toolbar back button presses.
     *
     * @param item Selected menu item
     * @return True if handled, otherwise default behavior
     */
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    /**
     * Displays a dialog allowing the user to rename the current climb.
     *
     * <p>The entered name is passed to the ViewModel which stores
     * the custom climb name.
     */
    private void showRenameDialog() {
        EditText input = new EditText(this);
        input.setHint("Climb name");

        new AlertDialog.Builder(this)
                .setTitle("Rename climb")
                .setView(input)
                .setPositiveButton("Save", (d, w) ->
                        viewModel.renameClimb(
                                routeId,
                                climbIndex,
                                input.getText().toString().trim()
                        ))
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * Displays a NumberPicker dialog allowing the user to choose how many
     * segments the current climb should be divided into, then triggers
     * re-segmentation via the ViewModel.
     */
    private void showReSegmentDialog() {
        android.widget.NumberPicker picker = new android.widget.NumberPicker(this);
        picker.setMinValue(4);
        picker.setMaxValue(32);
        nl.paree.climbpro.data.route.StoredClimb current = viewModel.climb().getValue();
        int defaultCount = (current != null && current.segmentCount > 0) ? current.segmentCount : 16;
        picker.setValue(defaultCount);

        new AlertDialog.Builder(this)
                .setTitle("Segmenten per klim")
                .setView(picker)
                .setPositiveButton("Herbereken", (dialog, which) ->
                        viewModel.reSegment(routeId, climbIndex, picker.getValue()))
                .setNegativeButton("Annuleer", null)
                .show();
    }
}