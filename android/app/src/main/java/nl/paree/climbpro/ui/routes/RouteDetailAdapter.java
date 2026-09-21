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
import nl.paree.climbpro.data.route.StoredStarredSegment;
import nl.paree.climbpro.data.route.StoredSurfaceSection;
import nl.paree.climbpro.domain.segment.GradientColor;
import nl.paree.climbpro.domain.segment.SurfaceType;
import nl.paree.climbpro.ui.climbs.SegmentColorPalette;

import java.util.ArrayList;
import java.util.List;

public final class RouteDetailAdapter
        extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int VIEW_TYPE_FLAT    = 0;
    private static final int VIEW_TYPE_CLIMB   = 1;
    private static final int VIEW_TYPE_STARRED = 2;
    private static final int VIEW_TYPE_SURFACE = 3;

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

    public interface OnClimbLongClickListener {
        void onClimbLongClick(StoredClimb climb, int climbIndex);
    }

    public interface OnFlatClickListener {
        void onFlatClick(StoredFlatSegment flat);
    }

    public interface OnFlatLongClickListener {
        void onFlatLongClick(StoredFlatSegment flat);
    }

    public interface OnStarredClickListener { void onStarredClick(StoredStarredSegment seg); }

    public interface OnSurfaceClickListener { void onSurfaceClick(StoredSurfaceSection section); }

    private List<Object> items = new ArrayList<>();
    private OnClimbClickListener     climbClickListener;
    private OnClimbLongClickListener climbLongClickListener;
    private OnFlatClickListener     flatClickListener;
    private OnFlatLongClickListener flatLongClickListener;
    private OnStarredClickListener  starredClickListener;
    private OnSurfaceClickListener  surfaceClickListener;
    private int[] climbTargetSeconds; // index = climb position; -1 = none

    public void setClimbTargetSeconds(int[] secs) {
        this.climbTargetSeconds = secs;
        notifyDataSetChanged();
    }

    public void setItems(List<Object> list) {
        items = list != null ? list : new ArrayList<>();
        notifyDataSetChanged();
    }

    public void setOnClimbClickListener(OnClimbClickListener l)       { climbClickListener = l; }
    public void setOnClimbLongClickListener(OnClimbLongClickListener l) { climbLongClickListener = l; }
    public void setOnFlatClickListener(OnFlatClickListener l)         { flatClickListener = l; }
    public void setOnFlatLongClickListener(OnFlatLongClickListener l) { flatLongClickListener = l; }
    public void setOnStarredClickListener(OnStarredClickListener l)   { starredClickListener = l; }
    public void setOnSurfaceClickListener(OnSurfaceClickListener l)   { surfaceClickListener = l; }

    @Override
    public int getItemViewType(int position) {
        Object o = items.get(position);
        if (o instanceof StoredFlatSegment)    return VIEW_TYPE_FLAT;
        if (o instanceof StoredStarredSegment) return VIEW_TYPE_STARRED;
        if (o instanceof StoredSurfaceSection) return VIEW_TYPE_SURFACE;
        return VIEW_TYPE_CLIMB;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == VIEW_TYPE_FLAT) {
            return new FlatViewHolder(inflater.inflate(R.layout.item_flat_segment, parent, false));
        }
        if (viewType == VIEW_TYPE_STARRED) {
            return new StarredViewHolder(inflater.inflate(R.layout.item_starred_segment, parent, false));
        }
        if (viewType == VIEW_TYPE_SURFACE) {
            return new SurfaceViewHolder(inflater.inflate(R.layout.item_surface_section, parent, false));
        }
        return new ClimbViewHolder(inflater.inflate(R.layout.item_climb, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (holder instanceof FlatViewHolder) {
            bindFlat((FlatViewHolder) holder, (StoredFlatSegment) items.get(position));
            return;
        }
        if (holder instanceof StarredViewHolder) {
            bindStarred((StarredViewHolder) holder, (StoredStarredSegment) items.get(position));
            return;
        }
        if (holder instanceof SurfaceViewHolder) {
            bindSurface((SurfaceViewHolder) holder, (StoredSurfaceSection) items.get(position));
            return;
        }
        bindClimb((ClimbViewHolder) holder, position);
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
        h.colorBar.setBackgroundColor(
                SegmentColorPalette.toColor(GradientColor.forGradient(c.avgGradient)));
        double distanceIntoRouteKm = c.startDistance / 1000.0;
        double difficulty = nl.paree.climbpro.domain.climb.DifficultyScoreCalculator
                .score(c.elevationGain, c.avgGradient, distanceIntoRouteKm);
        String categoryLabel = nl.paree.climbpro.domain.climb.ClimbCategoryLabel.forStoredClimb(c);
        String categorySuffix = categoryLabel.isEmpty() ? "" : "  ·  " + categoryLabel;
        h.statsView.setText(String.format("%d m · %.1f%% gem. · %d m hoogte · moeilijkheid %.0f%s",
                c.length, c.avgGradient * 100, c.elevationGain, difficulty, categorySuffix));
        if (climbTargetSeconds != null && climbIndex < climbTargetSeconds.length
                && climbTargetSeconds[climbIndex] >= 0) {
            h.statsView.setText(h.statsView.getText() + "  ·  ⏱ "
                    + nl.paree.climbpro.domain.power.DurationFormat.format(
                            climbTargetSeconds[climbIndex]));
        }
        final int ci = climbIndex;
        h.itemView.setOnClickListener(v -> {
            if (climbClickListener != null) climbClickListener.onClimbClick(c, ci);
        });
        h.itemView.setOnLongClickListener(v -> {
            if (climbLongClickListener != null) climbLongClickListener.onClimbLongClick(c, ci);
            return true;
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
        View     colorBar;
        ClimbViewHolder(View v) {
            super(v);
            nameView  = v.findViewById(R.id.climb_name);
            statsView = v.findViewById(R.id.climb_stats);
            colorBar  = v.findViewById(R.id.climb_color_bar);
        }
    }

    private void bindStarred(StarredViewHolder h, StoredStarredSegment s) {
        String name = s.userDisplayName != null ? s.userDisplayName : s.name;
        h.nameView.setText(String.format("%s · %.1f km",
                name != null ? name : "Ster-segment", s.length / 1000.0));

        String label = SurfaceType.label(s.surfaceType);
        if (label != null) {
            h.surfaceBadge.setVisibility(View.VISIBLE);
            h.surfaceBadge.setText(label);
            int st = SurfaceType.fromInt(s.surfaceType);
            if (st < SURFACE_BG.length) h.surfaceBadge.setBackgroundColor(SURFACE_BG[st]);
        } else {
            h.surfaceBadge.setVisibility(View.INVISIBLE);
        }

        h.itemView.setOnClickListener(v -> {
            if (starredClickListener != null) starredClickListener.onStarredClick(s);
        });
    }

    static final class StarredViewHolder extends RecyclerView.ViewHolder {
        TextView nameView;
        TextView surfaceBadge;
        StarredViewHolder(View v) {
            super(v);
            nameView     = v.findViewById(R.id.starred_name);
            surfaceBadge = v.findViewById(R.id.starred_surface_badge);
        }
    }

    private void bindSurface(SurfaceViewHolder h, StoredSurfaceSection s) {
        String label = s.name != null ? s.name : "Ondergrond-stuk";
        h.nameView.setText(String.format("%s · %.1f–%.1f km",
                label, s.startDistance / 1000.0, s.endDistance / 1000.0));

        String badge = SurfaceType.label(s.surfaceType);
        if (badge != null) {
            h.surfaceBadge.setVisibility(View.VISIBLE);
            h.surfaceBadge.setText(badge);
            int st = SurfaceType.fromInt(s.surfaceType);
            if (st < SURFACE_BG.length) h.surfaceBadge.setBackgroundColor(SURFACE_BG[st]);
        } else {
            h.surfaceBadge.setVisibility(View.INVISIBLE);
        }

        h.itemView.setOnClickListener(v -> {
            if (surfaceClickListener != null) surfaceClickListener.onSurfaceClick(s);
        });
    }

    static final class SurfaceViewHolder extends RecyclerView.ViewHolder {
        TextView nameView;
        TextView surfaceBadge;
        SurfaceViewHolder(View v) {
            super(v);
            nameView     = v.findViewById(R.id.surface_name);
            surfaceBadge = v.findViewById(R.id.surface_surface_badge);
        }
    }
}
