package nl.paree.climbpro.ui.quiz;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import nl.paree.climbpro.R;
import nl.paree.climbpro.data.route.AttemptPhotoStore;
import nl.paree.climbpro.data.route.ClimbAttemptRepository;
import nl.paree.climbpro.data.route.RouteCatalogEntry;
import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredClimbAttempt;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.domain.climb.ClimbIdentity;
import nl.paree.climbpro.domain.quiz.PhotoQuizBuilder;
import nl.paree.climbpro.domain.quiz.PhotoQuizBuilder.Question;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Photo quiz (issue #252): shows one of your own attempt photos and four climb names; guess
 * the climb. A round is up to {@link #ROUND_LENGTH} photos; the best score is kept. Reuses
 * the attempt photos of issue #46. Phone-only.
 */
public final class PhotoQuizActivity extends AppCompatActivity {

    public static final int ROUND_LENGTH = 10;
    private static final String PREF_BEST = "photo_quiz_best_pct";
    private static final String TAG = "PhotoQuizActivity";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private List<Question> questions = new ArrayList<>();
    private int index;
    private int score;

    private TextView progress;
    private TextView message;
    private ImageView photo;
    private LinearLayout options;
    private Button next;

    public static Intent intentFor(Context ctx) {
        return new Intent(ctx, PhotoQuizActivity.class);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_photo_quiz);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        toolbar.setNavigationOnClickListener(v -> finish());

        progress = findViewById(R.id.quiz_progress);
        message = findViewById(R.id.quiz_message);
        photo = findViewById(R.id.quiz_photo);
        options = findViewById(R.id.quiz_options);
        next = findViewById(R.id.quiz_next);
        next.setOnClickListener(v -> {
            if (index + 1 < questions.size()) {
                index++;
                showQuestion();
            } else if (index < questions.size()) {
                index++;
                showResult();
            } else {
                startRound();
            }
        });
        startRound();
    }

    private void startRound() {
        message.setText("Foto's laden…");
        options.removeAllViews();
        next.setVisibility(View.GONE);
        executor.execute(() -> {
            // Skip photos whose file is gone (e.g. data restored without the photo folder);
            // they would give a question with a blank picture.
            List<StoredClimbAttempt> withPhoto = new ArrayList<>();
            for (StoredClimbAttempt a : new ClimbAttemptRepository(this).loadAll()) {
                if (a.photoFileName == null || a.photoFileName.isEmpty()
                        || AttemptPhotoStore.fileFor(this, a.photoFileName).exists()) {
                    withPhoto.add(a);
                }
            }
            List<Question> qs = PhotoQuizBuilder.build(withPhoto, climbNames(),
                    ROUND_LENGTH, new Random());
            runOnUiThread(() -> {
                questions = qs;
                index = 0;
                score = 0;
                if (qs.isEmpty()) {
                    progress.setText("");
                    photo.setImageDrawable(null);
                    message.setText("Nog geen quiz mogelijk. Voeg in het logboek een foto toe aan "
                            + "je klimpogingen; je hebt ook minstens twee klimmen nodig.");
                } else {
                    showQuestion();
                }
            });
        });
    }

    private void showQuestion() {
        Question q = questions.get(index);
        progress.setText("Foto " + (index + 1) + " van " + questions.size() + " · score " + score);
        message.setText("Welke klim is dit?");
        next.setVisibility(View.GONE);
        photo.setImageDrawable(null);
        int target = Math.max(getResources().getDisplayMetrics().widthPixels, 512);
        executor.execute(() -> {
            Bitmap bmp = decode(AttemptPhotoStore.fileFor(this, q.photoFileName), target);
            runOnUiThread(() -> {
                // moved on: next question, the result screen (index == size) or a new round
                if (index >= questions.size() || questions.get(index) != q) return;
                photo.setImageBitmap(bmp);
            });
        });

        options.removeAllViews();
        for (String option : q.options) {
            Button b = new Button(this, null, 0, R.style.Widget_ClimbPro_Button_Outline);
            b.setText(option);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = (int) (8 * getResources().getDisplayMetrics().density);
            b.setLayoutParams(lp);
            b.setOnClickListener(v -> answer(q, option));
            options.addView(b);
        }
    }

    private void answer(Question q, String chosen) {
        if (next.getVisibility() == View.VISIBLE) return; // already answered (double tap)
        boolean right = q.answer.equals(chosen);
        if (right) score++;
        message.setText(right ? "Goed! Dit is " + q.answer : "Helaas, dit is " + q.answer);
        for (int i = 0; i < options.getChildCount(); i++) {
            Button b = (Button) options.getChildAt(i);
            b.setEnabled(false);
            String text = b.getText().toString();
            if (text.equals(q.answer)) {
                tint(b, R.color.color_success);
            } else if (text.equals(chosen)) {
                tint(b, R.color.color_error);
            }
        }
        progress.setText("Foto " + (index + 1) + " van " + questions.size() + " · score " + score);
        next.setText(index + 1 < questions.size() ? "Volgende" : "Uitslag");
        next.setVisibility(View.VISIBLE);
    }

    private void showResult() {
        int pct = Math.round(100f * score / questions.size());
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        int best = prefs.getInt(PREF_BEST, -1);
        boolean record = pct > best;
        if (record) prefs.edit().putInt(PREF_BEST, pct).apply();

        options.removeAllViews();
        photo.setImageDrawable(null);
        progress.setText("");
        message.setText(score + " van de " + questions.size() + " goed (" + pct + "%)"
                + (record ? "\nNieuw record!" : "\nJe record: " + best + "%"));
        next.setText("Nog een ronde");
        next.setVisibility(View.VISIBLE);
    }

    private void tint(Button b, int colorRes) {
        b.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, colorRes)));
    }

    /** Display name per climb identity; the first route containing a climb wins. */
    private Map<String, String> climbNames() {
        RouteRepository repo = new RouteRepository(this);
        Map<String, String> names = new HashMap<>();
        for (RouteCatalogEntry e : repo.loadCatalog()) {
            try {
                StoredRoute r = repo.loadRoute(e.routeId);
                if (r.climbs == null) continue;
                for (StoredClimb c : r.climbs) {
                    String n = c.userDisplayName != null && !c.userDisplayName.isEmpty()
                            ? c.userDisplayName : c.name;
                    if (n != null && !n.isEmpty()) names.putIfAbsent(ClimbIdentity.of(c), n);
                }
            } catch (IOException ex) {
                Log.w(TAG, "Skipping route " + e.routeId, ex);
            }
        }
        return names;
    }

    /** Downsampled decode so a full-resolution photo doesn't blow the heap. */
    private static Bitmap decode(File file, int targetPx) {
        if (!file.exists()) return null;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        int sample = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= targetPx) sample *= 2;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        return BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }
}
