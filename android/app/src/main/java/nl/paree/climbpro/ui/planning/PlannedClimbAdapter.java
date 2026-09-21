package nl.paree.climbpro.ui.planning;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import nl.paree.climbpro.R;
import nl.paree.climbpro.data.planning.PlannedClimb;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class PlannedClimbAdapter extends RecyclerView.Adapter<PlannedClimbAdapter.VH> {

    public interface OnRemove { void onRemove(PlannedClimb plan); }

    private final List<PlannedClimb> items = new ArrayList<>();
    private final OnRemove onRemove;
    private final SimpleDateFormat dateFormat =
            new SimpleDateFormat("EEE d MMM yyyy, HH:mm", new Locale("nl", "NL"));

    public PlannedClimbAdapter(OnRemove onRemove) { this.onRemove = onRemove; }

    public void submit(List<PlannedClimb> plans) {
        items.clear();
        items.addAll(plans);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_planned_climb, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        PlannedClimb plan = items.get(position);
        h.name.setText(plan.displayName != null ? plan.displayName : "Geplande klim");
        h.date.setText(dateFormat.format(new java.util.Date(plan.plannedAtEpochSec * 1000L)));
        h.remove.setOnClickListener(v -> {
            if (onRemove != null) onRemove.onRemove(plan);
        });
    }

    @Override
    public int getItemCount() { return items.size(); }

    static final class VH extends RecyclerView.ViewHolder {
        final TextView name;
        final TextView date;
        final View remove;
        VH(@NonNull View v) {
            super(v);
            name = v.findViewById(R.id.name);
            date = v.findViewById(R.id.date);
            remove = v.findViewById(R.id.remove);
        }
    }
}
