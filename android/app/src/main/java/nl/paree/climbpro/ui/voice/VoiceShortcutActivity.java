package nl.paree.climbpro.ui.voice;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import nl.paree.climbpro.data.route.RouteCatalogEntry;
import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.domain.matching.NextClimbFinder;
import nl.paree.climbpro.ui.routes.QuickStart;
import nl.paree.climbpro.ui.routes.RouteListActivity;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Entry point for the voice-assistant and launcher shortcuts (issue #260): "start mijn rit"
 * runs quick start on the active route (issue #263), "hoe ver is de volgende klim?" answers
 * from the phone's last known location against the active route. The answer is shown and
 * spoken, so it works hands-free. Translucent: no screen of its own besides the answer.
 */
public final class VoiceShortcutActivity extends AppCompatActivity {

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private TextToSpeech tts;
    private boolean ttsReady;
    private String pendingSpeech;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(new Locale("nl", "NL"));
                ttsReady = true;
                if (pendingSpeech != null) speak(pendingSpeech);
            }
        });

        Intent in = getIntent();
        VoiceCommand command = VoiceCommand.parse(in.getAction(),
                in.getStringExtra(VoiceCommand.EXTRA_FEATURE));
        if (command == null && in.getData() != null) {
            command = VoiceCommand.parse(null, in.getData().getQueryParameter(VoiceCommand.EXTRA_FEATURE));
        }
        if (command == null) {
            openApp();
            return;
        }
        VoiceCommand cmd = command;
        executor.execute(() -> {
            String answer = cmd == VoiceCommand.START_RIDE ? startRide() : nextClimb();
            runOnUiThread(() -> {
                if (answer == null) {
                    openApp();
                } else {
                    answer(answer);
                }
            });
        });
    }

    /** @return the spoken answer, or null when the user has to pick a route in the app. */
    private String startRide() {
        RouteCatalogEntry route = QuickStart.resolve(QuickStart.activeRouteId(this),
                new RouteRepository(this).loadCatalog());
        if (route == null) return null;
        QuickStart.start(this, route.routeId);
        return "Rit gestart. " + QuickStart.displayName(route) + " wordt naar je horloge gestuurd.";
    }

    private String nextClimb() {
        String activeId = QuickStart.activeRouteId(this);
        if (activeId == null) return "Je hebt nog geen actieve route. Kies er een in ClimbPro.";
        StoredRoute route;
        try {
            route = new RouteRepository(this).loadRoute(activeId);
        } catch (Exception e) {
            return "Ik kan je actieve route niet openen.";
        }
        Location loc = lastKnownLocation();
        return NextClimbFinder.find(route,
                loc != null ? loc.getLatitude() : null,
                loc != null ? loc.getLongitude() : null).toSpeech();
    }

    /** Freshest of the GPS/network fixes Android already has; never starts a new fix. */
    private Location lastKnownLocation() {
        boolean fine = ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean coarse = ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        if (!fine && !coarse) return null;
        LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (lm == null) return null;
        Location best = null;
        for (String provider : new String[]{LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER}) {
            try {
                Location l = lm.getLastKnownLocation(provider);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            } catch (SecurityException | IllegalArgumentException ignored) {
                // provider missing or permission revoked mid-call: try the next one
            }
        }
        return best;
    }

    private void answer(String text) {
        speak(text);
        new AlertDialog.Builder(this)
                .setTitle("ClimbPro")
                .setMessage(text)
                .setPositiveButton("OK", null)
                .setOnDismissListener(d -> finish())
                .show();
    }

    private void speak(String text) {
        if (!ttsReady) {
            pendingSpeech = text;
            return;
        }
        pendingSpeech = null;
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "climbpro-voice");
    }

    private void openApp() {
        startActivity(new Intent(this, RouteListActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK));
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
        if (tts != null) tts.shutdown();
    }
}
