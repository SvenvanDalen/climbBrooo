package nl.paree.climbpro.ui.routes;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import nl.paree.climbpro.R;
import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredFlatSegment;
import nl.paree.climbpro.domain.segment.SurfaceType;

import java.util.ArrayList;
import java.util.List;

public final class RouteDetailAdapter
        extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int VIEW_TYPE_FLAT  = 0;
    private static final int VIEW_TYPE_CLIMB = 1;

    // Surface badge background colors — same mapping as ClimbSegmentAdapter
    private static final int[] SURFACE_BG = {
        0xFF404040, // ASPHALT
        0xFFC8A050, // GRAVEL
        0xFF8B4513, // DIRT
        0xFF909090, // COBBLESTONE
        0xFF9060C0, // MIXED
    };

    public interface OnClimbClickListener {
        void onClimbClick(StoredClimb climb, int climbIndex);
    }

    public interface OnFlatClickListener {
        void onFlatClick(StoredFlatSegment flat);
    }

    public interface OnFlatLongClickListener {
        void onFlatLongClick(StoredFlatSegment flat);
    }

    private List<Object> items = new ArrayList<>();
    private OnClimbClickListener    climbClickListener;
    private OnFlatClickListener     flatClickListener;
    private OnFlatLongClickListener flatLongClickListener;

    public void setItems(List<Object> list) {
        items = list != null ? list : new ArrayList<>();
        notifyDataSetChanged();
    }

    public void setOnClimbClickListener(OnClimbClickListener l)       { climbClickListener = l; }
    public void setOnFlatClickListener(OnFlatClickListener l)         { flatClickListener = l; }
    public void setOnFlatLongClickListener(OnFlatLongClickListener l) { flatLongClickListener = l; }

    @Override
    public int getItemViewType(int position) {
        return items.get(position) instanceof StoredFlatSegment ? VIEW_TYPE_FLAT : VIEW_TYPE_CLIMB;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == VIEW_TYPE_FLAT) {
            return new FlatViewHolder(inflater.inflate(R.layout.item_flat_segment, parent, false));
        }
        return new ClimbViewHolder(inflater.inflate(R.layout.item_climb, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (holder instanceof FlatViewHolder) {
            bindFlat((FlatViewHolder) holder, (StoredFlatSegment) items.get(position));
        } else {
            bindClimb((ClimbViewHolder) holder, position);
        }
    }

    private void bindFlat(FlatViewHolder h, StoredFlatSegment flat) {
        h.distanceView.setText(String.format("%.1f km vlak", flat.length / 1000.0));

        String label = SurfaceType.label(flat.surfaceType);
        if (label != null) {
            h.surfaceBadge.setVisibility(View.VISIBLE);
            h.surfaceBadge.setText(label);
            int st = SurfaceType.fromInt(flat.surfaceType);
            if (st < SURFACE_BG.length) h.surfaceBadge.setBackgroundColor(SURFACE_BG[st]);
        } else {
            h.surfaceBadge.setVisibility(View.INVISIBLE);
        }

        h.itemView.setOnClickListener(v -> {
            if (flatClickListener != null) flatClickListener.onFlatClick(flat);
        });
        h.itemView.setOnLongClickListener(v -> {
            if (flatLongClickListener != null) flatLongClickListener.onFlatLongClick(flat);
            return true;
        });
    }

    private void bindClimb(ClimbViewHolder h, int position) {
        int climbIndex = 0;
        for (int i = 0; i < position; i++) {
            if (items.get(i) instanceof StoredClimb) climbIndex++;
        }
        StoredClimb c = (StoredClimb) items.get(position);
        String name = c.userDisplayName != null ? c.userDisplayName : c.name;
        h.nameView.setText(name != null ? name : "Klim " + (climbIndex + 1));
        h.statsView.setText(String.format("%d m · %.1f%% gem. · %d m hoogte",
                c.length, c.avgGradient * 100, c.elevationGain));
        final int ci = climbIndex;
        h.itemView.setOnClickListener(v -> {
            if (climbClickListener != null) climbClickListener.onClimbClick(c, ci);
        });
    }

    @Override
    public int getItemCount() { return items.size(); }

    static final class FlatViewHolder extends RecyclerView.ViewHolder {
        TextView distanceView;
        TextView surfaceBadge;
        FlatViewHolder(View v) {
            super(v);
            distanceView = v.findViewById(R.id.flat_distance);
            surfaceBadge = v.findViewById(R.id.flat_surface_badge);
        }
    }

    static final class ClimbViewHolder extends RecyclerView.ViewHolder {
        TextView nameView;
        TextView statsView;
        ClimbViewHolder(View v) {
            super(v);
            nameView  = v.findViewById(R.id.climb_name);
            statsView = v.findViewById(R.id.climb_stats);
        }
    }
}
