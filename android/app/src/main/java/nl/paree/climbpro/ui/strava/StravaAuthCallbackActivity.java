package nl.paree.climbpro.ui.strava;

import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;

import net.openid.appauth.AuthorizationException;
import net.openid.appauth.AuthorizationResponse;
import net.openid.appauth.AuthorizationService;
import net.openid.appauth.TokenRequest;

import nl.paree.climbpro.databinding.ActivityStravaAuthCallbackBinding;

/**
 * Receives the OAuth redirect from the browser and exchanges the code for tokens.
 */
public final class StravaAuthCallbackActivity extends AppCompatActivity {

    private ActivityStravaAuthCallbackBinding binding;
    private StravaAuthViewModel               viewModel;
    private AuthorizationService              authService;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding     = ActivityStravaAuthCallbackBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        viewModel   = new ViewModelProvider(this).get(StravaAuthViewModel.class);
        authService = new AuthorizationService(this);

        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        AuthorizationResponse resp = AuthorizationResponse.fromIntent(intent);
        AuthorizationException ex  = AuthorizationException.fromIntent(intent);

        if (ex != null) {
            viewModel.onAuthError(ex.getMessage());
            finish();
            return;
        }
        if (resp == null) {
            finish();
            return;
        }

        binding.statusText.setText("Exchanging tokens…");

        TokenRequest tokenReq = resp.createTokenExchangeRequest();
        authService.performTokenRequest(tokenReq, (tokenResponse, tokenEx) -> {
            if (tokenEx != null) {
                viewModel.onAuthError(tokenEx.getMessage());
            } else if (tokenResponse != null) {
                net.openid.appauth.AuthState state =
                        new net.openid.appauth.AuthState(resp, tokenResponse, null);
                viewModel.onAuthStateReceived(state);
            }
            finish();
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        authService.dispose();
    }
}
