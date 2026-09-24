package nl.paree.climbpro.ui.maintenance;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import nl.paree.climbpro.R;
import nl.paree.climbpro.data.maintenance.MaintenanceComponent;
import nl.paree.climbpro.domain.maintenance.MaintenanceCalculator;
import nl.paree.climbpro.domain.maintenance.MaintenanceCalculator.Status;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** One card per maintenance component: usage vs interval, wear bar and a "Gedaan" button. */
public final class MaintenanceAdapter extends RecyclerView.Adapter<MaintenanceAdapter.RowVH> {

    public interface Listener {
        void onServiced(MaintenanceComponent component);
        void onEdit(MaintenanceComponent component);
    }

    private final List<Status> rows = new ArrayList<>();
    private final Listener listener;
    private final SimpleDateFormat dateFormat =
            new SimpleDateFormat("d MMM yyyy", Locale.getDefault());

    public MaintenanceAdapter(Listener listener) {
        this.listener = listener;
    }

    public void submit(List<Status> statuses) {
        rows.clear();
        if (statuses != null) rows.addAll(statuses);
        notifyDataSetChanged();
    }

    public String formatDate(long epochSec) {
        return dateFormat.format(new Date(epochSec * 1000L));
    }

    @NonNull
    @Override
    public RowVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_maintenance_component, parent, false);
        return new RowVH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull RowVH holder, int position) {
        Status s = rows.get(position);
        MaintenanceComponent c = s.component;
        holder.name.setText(c.name);

        String label = MaintenanceCalculator.stateLabel(s);
        holder.state.setText(label != null ? label : "");
        holder.state.setVisibility(label != null ? View.VISIBLE : View.GONE);
        holder.state.setTextColor(ContextCompat.getColor(holder.itemView.getContext(),
                s.due ? R.color.color_error : R.color.color_accent));

        holder.usage.setText(MaintenanceCalculator.usageText(s));

        boolean hasInterval = c.intervalKm > 0 || c.intervalMonths > 0;
        holder.progress.setVisibility(s.configured && hasInterval ? View.VISIBLE : View.GONE);
        holder.progress.setProgress(Math.min(100, s.wornPercent()));

        StringBuilder detail = new StringBuilder();
        if (s.configured) detail.append("Laatst: ").append(formatDate(c.lastServicedEpochSec));
        if (c.includeVirtualRides) {
            if (detail.length() > 0) detail.append("  •  ");
            detail.append("incl. indoorritten");
        }
        holder.detail.setText(detail);
        holder.detail.setVisibility(detail.length() > 0 ? View.VISIBLE : View.GONE);

        holder.done.setOnClickListener(v -> listener.onServiced(c));
        holder.itemView.setOnClickListener(v -> listener.onEdit(c));
    }

    @Override
    public int getItemCount() { return rows.size(); }

    static final class RowVH extends RecyclerView.ViewHolder {
        final TextView name;
        final TextView state;
        final TextView usage;
        final ProgressBar progress;
        final TextView detail;
        final Button done;
        RowVH(@NonNull View v) {
            super(v);
            name = v.findViewById(R.id.name);
            state = v.findViewById(R.id.state);
            usage = v.findViewById(R.id.usage);
            progress = v.findViewById(R.id.progress);
            detail = v.findViewById(R.id.detail);
            done = v.findViewById(R.id.btn_done);
        }
    }
}
