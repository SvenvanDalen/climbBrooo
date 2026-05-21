package nl.paree.climbpro.ui.strava;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;

import net.openid.appauth.AuthorizationException;
import net.openid.appauth.AuthorizationRequest;
import net.openid.appauth.AuthorizationResponse;
import net.openid.appauth.AuthorizationService;

import nl.paree.climbpro.databinding.ActivityStravaAuthBinding;

public final class StravaAuthActivity extends AppCompatActivity {

    private ActivityStravaAuthBinding binding;
    private StravaAuthViewModel       viewModel;
    private AuthorizationService      authService;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding     = ActivityStravaAuthBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        setSupportActionBar(binding.toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        viewModel   = new ViewModelProvider(this).get(StravaAuthViewModel.class);
        authService = new AuthorizationService(this);

        viewModel.authorised().observe(this, ok -> {
            if (Boolean.TRUE.equals(ok)) {
                Toast.makeText(this, "Signed in to Strava!", Toast.LENGTH_SHORT).show();
                finish();
            }
        });
        viewModel.error().observe(this,
                msg -> Toast.makeText(this, "Auth error: " + msg, Toast.LENGTH_LONG).show());

        binding.btnSignIn.setOnClickListener(v -> startAuth());
    }

    private void startAuth() {
        AuthorizationRequest req = viewModel.buildAuthRequest();
        Intent completionIntent = new Intent(this, StravaAuthCallbackActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, completionIntent,
                PendingIntent.FLAG_MUTABLE);
        authService.performAuthorizationRequest(req, pi);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        authService.dispose();
    }
}
