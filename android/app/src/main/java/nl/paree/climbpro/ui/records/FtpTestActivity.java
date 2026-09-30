package nl.paree.climbpro.ui.records;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.FileProvider;
import androidx.lifecycle.ViewModelProvider;

import nl.paree.climbpro.R;
import nl.paree.climbpro.domain.power.FtpTestPlan;
import nl.paree.climbpro.domain.power.FtpTestResultDetector;
import nl.paree.climbpro.ui.climbs.ClimbWorkoutExportHandoff;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * "FTP-test" screen (issue #181): explains the 20-minute test with targets from the current
 * FTP, exports it as a {@code .zwo} for MyWhoosh/Zwift, and shows the test detected after the
 * Strava sync with an explicit "FTP bijwerken" — the FTP is never changed without asking.
 * Phone-only.
 */
public final class FtpTestActivity extends AppCompatActivity {

    private final SimpleDateFormat dateFormat =
            new SimpleDateFormat("EEE d MMM yyyy", new Locale("nl"));

    private FtpTestViewModel viewModel;

    public static Intent intentFor(Context context) {
        return new Intent(context, FtpTestActivity.class);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ftp_test);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.ftp_test_title);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        TextView target = findViewById(R.id.target);
        LinearLayout phases = findViewById(R.id.phases);
        TextView exportedAt = findViewById(R.id.exported_at);
        TextView result = findViewById(R.id.result);
        Button apply = findViewById(R.id.apply);
        Button dismiss = findViewById(R.id.dismiss);
        Button export = findViewById(R.id.export);

        viewModel = new ViewModelProvider(this).get(FtpTestViewModel.class);
        export.setOnClickListener(v -> viewModel.exportWorkout());

        viewModel.state().observe(this, st -> {
            int ftp = st.currentFtpWatts;
            target.setText(ftp > 0
                    ? getString(R.string.ftp_test_current_ftp, ftp, FtpTestPlan.testTargetWatts(ftp))
                    : getString(R.string.ftp_test_no_ftp));
            renderPhases(phases, ftp);

            if (st.exportedAtEpochSec > 0) {
                exportedAt.setVisibility(View.VISIBLE);
                exportedAt.setText(getString(R.string.ftp_test_exported_at,
                        dateFormat.format(new Date(st.exportedAtEpochSec * 1000L))));
            } else {
                exportedAt.setVisibility(View.GONE);
            }

            FtpTestResultDetector.Result r = st.result;
            String pending = st.ridesAwaitingAnalysis > 0
                    ? "\n\n" + getString(R.string.ftp_test_result_pending, st.ridesAwaitingAnalysis)
                    : "";
            if (r == null) {
                result.setText(getString(R.string.ftp_test_result_none,
                        FtpTestResultDetector.EXPORT_WINDOW_DAYS) + pending);
                apply.setVisibility(View.GONE);
                dismiss.setVisibility(View.GONE);
                return;
            }
            String name = r.ride.name != null && !r.ride.name.isEmpty()
                    ? r.ride.name : getString(R.string.ftp_test_unknown_ride);
            String date = dateFormat.format(new Date(r.ride.startEpochSec * 1000L));
            StringBuilder text = new StringBuilder(getString(R.string.ftp_test_result_found,
                    name, date, r.twentyMinuteWatts, r.ftpWatts));
            if (ftp > 0) {
                text.append('\n').append(r.ftpWatts == ftp
                        ? getString(R.string.ftp_test_result_same)
                        : getString(R.string.ftp_test_result_delta, r.ftpWatts - ftp, ftp));
            }
            result.setText(text.append(pending).toString());
            dismiss.setVisibility(View.VISIBLE);
            dismiss.setOnClickListener(v -> viewModel.dismissResult(r));
            if (r.ftpWatts == ftp) {
                apply.setVisibility(View.GONE);
            } else {
                apply.setVisibility(View.VISIBLE);
                apply.setText(getString(R.string.ftp_test_apply, r.ftpWatts));
                apply.setOnClickListener(v -> confirmApply(r, ftp));
            }
        });

        viewModel.export().observe(this, ex -> {
            if (ex == null) return;
            viewModel.consumeExport();
            if (ex.file == null) {
                Toast.makeText(this, getString(R.string.ftp_test_export_failed, ex.error),
                        Toast.LENGTH_LONG).show();
                return;
            }
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", ex.file);
            Intent share = ClimbWorkoutExportHandoff.buildShareIntent(uri,
                    ClimbWorkoutExportHandoff.ZWO_MIME);
            new AlertDialog.Builder(this)
                    .setTitle(R.string.ftp_test_export_dialog_title)
                    .setMessage(getString(R.string.ftp_test_export_dialog_message,
                            FtpTestResultDetector.EXPORT_WINDOW_DAYS))
                    .setPositiveButton(R.string.ftp_test_share, (d, w) -> startActivity(
                            Intent.createChooser(share, getString(R.string.ftp_test_share_chooser))))
                    .setNegativeButton(R.string.ftp_test_cancel, null)
                    .show();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        viewModel.load();
    }

    private void confirmApply(FtpTestResultDetector.Result r, int currentFtp) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.ftp_test_apply_confirm_title)
                .setMessage(currentFtp > 0
                        ? getString(R.string.ftp_test_apply_confirm_message, currentFtp, r.ftpWatts)
                        : getString(R.string.ftp_test_apply_confirm_message_new, r.ftpWatts))
                .setPositiveButton(getString(R.string.ftp_test_apply, r.ftpWatts), (d, w) -> {
                    viewModel.applyResult(r);
                    Toast.makeText(this, getString(R.string.ftp_test_applied, r.ftpWatts),
                            Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(R.string.ftp_test_cancel, null)
                .show();
    }

    private void renderPhases(ViewGroup container, int ftp) {
        container.removeAllViews();
        for (FtpTestPlan.Phase p : FtpTestPlan.phases()) {
            View v = LayoutInflater.from(this).inflate(R.layout.item_ride_record, container, false);
            ((TextView) v.findViewById(R.id.title)).setText(
                    getString(R.string.ftp_test_phase_title, getString(phaseName(p.type)),
                            p.seconds / 60));
            ((TextView) v.findViewById(R.id.value)).setText(power(p, ftp));
            ((TextView) v.findViewById(R.id.detail)).setText(phaseDetail(p.type));
            container.addView(v);
        }
        TextView total = new TextView(this);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        total.setPadding(pad, 0, pad, pad);
        total.setText(getString(R.string.ftp_test_total, FtpTestPlan.totalSeconds() / 60));
        container.addView(total);
    }

    private String power(FtpTestPlan.Phase p, int ftp) {
        if (ftp > 0) {
            return p.isRamp()
                    ? getString(R.string.ftp_test_watts_ramp, p.fromWatts(ftp), p.toWatts(ftp))
                    : getString(R.string.ftp_test_watts_steady, p.fromWatts(ftp));
        }
        int from = (int) Math.round(p.fromFraction * 100);
        int to = (int) Math.round(p.toFraction * 100);
        return p.isRamp()
                ? getString(R.string.ftp_test_pct_ramp, from, to)
                : getString(R.string.ftp_test_pct_steady, from);
    }

    private static int phaseName(FtpTestPlan.PhaseType type) {
        switch (type) {
            case WARMUP: return R.string.ftp_test_phase_warmup;
            case BLOW_OUT: return R.string.ftp_test_phase_blow_out;
            case RECOVERY: return R.string.ftp_test_phase_recovery;
            case TEST: return R.string.ftp_test_phase_test;
            default: return R.string.ftp_test_phase_cooldown;
        }
    }

    private static int phaseDetail(FtpTestPlan.PhaseType type) {
        switch (type) {
            case WARMUP: return R.string.ftp_test_detail_warmup;
            case BLOW_OUT: return R.string.ftp_test_detail_blow_out;
            case RECOVERY: return R.string.ftp_test_detail_recovery;
            case TEST: return R.string.ftp_test_detail_test;
            default: return R.string.ftp_test_detail_cooldown;
        }
    }
}
