package nl.paree.climbpro.ui.climbs;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import nl.paree.climbpro.R;
import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.domain.climb.ClimbShapeLabel;

import java.util.ArrayList;
import java.util.List;

public final class ClimbListAdapter
        extends RecyclerView.Adapter<ClimbListAdapter.ViewHolder> {

    public interface OnClimbClickListener {
        void onClimbClick(StoredClimb climb, int index);
    }

    private List<StoredClimb>     items    = new ArrayList<>();
    private OnClimbClickListener  listener;

    public void setItems(List<StoredClimb> list) {
        items = list != null ? list : new ArrayList<>();
        notifyDataSetChanged();
    }

    public void setListener(OnClimbClickListener l) { listener = l; }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_climb, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder h, int position) {
        StoredClimb c = items.get(position);
        String name = c.userDisplayName != null ? c.userDisplayName : c.name;
        h.nameView.setText(name != null ? name : "Climb " + (position + 1));
        h.statsView.setText(String.format("%d m · %.1f%% avg · %d m gain · %s",
                c.length, c.avgGradient * 100, c.elevationGain, ClimbShapeLabel.forStoredClimb(c)));
        final int idx = position;
        h.itemView.setOnClickListener(v -> { if (listener != null) listener.onClimbClick(c, idx); });
    }

    @Override
    public int getItemCount() { return items.size(); }

    static final class ViewHolder extends RecyclerView.ViewHolder {
        TextView nameView;
        TextView statsView;
        ViewHolder(View v) {
            super(v);
            nameView  = v.findViewById(R.id.climb_name);
            statsView = v.findViewById(R.id.climb_stats);
        }
    }
}
