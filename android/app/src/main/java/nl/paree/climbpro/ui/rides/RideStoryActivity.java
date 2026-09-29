package nl.paree.climbpro.ui.rides;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.FileProvider;
import androidx.lifecycle.ViewModelProvider;

import nl.paree.climbpro.R;
import nl.paree.climbpro.ui.climbs.ClimbShareHandoff;

import java.io.File;
import java.io.IOException;

/**
 * Ride story (issue #193): preview of the shareable ride summary image, shared through the
 * standard share sheet with the same FileProvider handoff as the climb share image (#33).
 */
public final class RideStoryActivity extends AppCompatActivity {

    private static final String EXTRA_ACTIVITY_ID = "activity_id";

    private Bitmap image;

    public static Intent intentFor(Context ctx, long activityId) {
        return new Intent(ctx, RideStoryActivity.class).putExtra(EXTRA_ACTIVITY_ID, activityId);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ride_story);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        toolbar.setNavigationOnClickListener(v -> finish());

        TextView status = findViewById(R.id.status);
        ImageView preview = findViewById(R.id.preview);
        Button share = findViewById(R.id.btn_share);
        share.setOnClickListener(v -> share());

        RideStoryViewModel vm = new ViewModelProvider(this).get(RideStoryViewModel.class);
        vm.image().observe(this, bmp -> {
            image = bmp;
            preview.setImageBitmap(bmp);
            status.setVisibility(View.GONE);
            share.setEnabled(true);
        });
        vm.error().observe(this, status::setText);
        vm.load(getIntent().getLongExtra(EXTRA_ACTIVITY_ID, -1L));
    }

    private void share() {
        if (image == null) return;
        try {
            File file = ClimbShareHandoff.writeShareImage(this, image);
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
            startActivity(Intent.createChooser(ClimbShareHandoff.buildShareIntent(uri),
                    "Deel rit-verhaal"));
        } catch (IOException e) {
            Toast.makeText(this, "Kon afbeelding niet opslaan", Toast.LENGTH_SHORT).show();
        }
    }
}
