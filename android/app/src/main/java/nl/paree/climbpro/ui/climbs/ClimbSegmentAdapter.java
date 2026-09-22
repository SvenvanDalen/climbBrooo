package nl.paree.climbpro.ui.climbs;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import nl.paree.climbpro.R;
import nl.paree.climbpro.data.route.StoredSegment;
import nl.paree.climbpro.domain.power.DurationFormat;
import nl.paree.climbpro.domain.segment.SurfaceType;

import java.util.ArrayList;
import java.util.List;

public final class ClimbSegmentAdapter
        extends RecyclerView.Adapter<ClimbSegmentAdapter.ViewHolder> {

    public interface OnSegmentLongClickListener {
        void onSegmentLongClick(int position, StoredSegment segment);
    }

    /** Tap (short click) on a segment row — used to edit its manual target time. */
    public interface OnSegmentClickListener {
        void onSegmentClick(int position, StoredSegment segment);
    }

    private static final int[] SEGMENT_COLORS = SegmentColorPalette.COLORS;

    // Surface badge background colors (match SurfaceType constants)
    private static final int[] SURFACE_BG = {
        0xFF404040, // ASPHALT — dark grey
        0xFFC8A050, // GRAVEL  — sandy yellow
        0xFF8B4513, // DIRT    — brown
        0xFF909090, // COBBLESTONE — medium grey
        0xFF9060C0, // MIXED   — purple
    };

    private List<StoredSegment> items = new ArrayList<>();
    private int[] segmentSeconds; // auto (planner) estimate, null when unavailable
    private OnSegmentLongClickListener longClickListener;
    private OnSegmentClickListener clickListener;

    public void setItems(List<StoredSegment> list) {
        items = list != null ? list : new ArrayList<>();
        notifyDataSetChanged();
    }

    public void setSegmentSeconds(int[] seconds) {
        this.segmentSeconds = seconds;
        notifyDataSetChanged();
    }

    public void setOnSegmentLongClickListener(OnSegmentLongClickListener l) {
        longClickListener = l;
    }

    public void setOnSegmentClickListener(OnSegmentClickListener l) {
        clickListener = l;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_segment, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder h, int position) {
        StoredSegment s = items.get(position);
        h.gradientView.setText(String.format("%.1f%%", s.gradient * 100));
        h.distView.setText(s.distance + " m");
        if (s.manualTargetSec != null) {
            h.timeView.setVisibility(View.VISIBLE);
            h.timeView.setText(DurationFormat.format(s.manualTargetSec) + " ✎");
            h.timeView.setTextColor(0xFFE8C400); // manual override — accent yellow
            h.timeView.setTypeface(null, android.graphics.Typeface.BOLD);
        } else if (segmentSeconds != null && position < segmentSeconds.length) {
            h.timeView.setVisibility(View.VISIBLE);
            h.timeView.setText(DurationFormat.format(segmentSeconds[position]));
            h.timeView.setTextColor(h.defaultTimeColor);
            h.timeView.setTypeface(null, android.graphics.Typeface.NORMAL);
        } else {
            h.timeView.setVisibility(View.GONE);
        }

        h.itemView.setOnClickListener(v -> {
            if (clickListener != null) {
                clickListener.onSegmentClick(position, s);
            }
        });
        int ci = Math.max(0, Math.min(5, s.colorIndex));
        h.colorBar.setBackgroundColor(SEGMENT_COLORS[ci]);

        // Surface badge
        String label = SurfaceType.label(s.surfaceType);
        if (label != null) {
            h.surfaceBadge.setVisibility(View.VISIBLE);
            h.surfaceBadge.setText(label);
            int st = SurfaceType.fromInt(s.surfaceType);
            if (st < SURFACE_BG.length) {
                h.surfaceBadge.setBackgroundColor(SURFACE_BG[st]);
            }
        } else {
            h.surfaceBadge.setVisibility(View.INVISIBLE);
        }

        // Long-press
        h.itemView.setOnLongClickListener(v -> {
            if (longClickListener != null) {
                longClickListener.onSegmentLongClick(position, s);
            }
            return true;
        });
    }

    @Override
    public int getItemCount() { return items.size(); }

    static final class ViewHolder extends RecyclerView.ViewHolder {
        TextView gradientView;
        TextView distView;
        TextView timeView;
        View     colorBar;
        TextView surfaceBadge;
        int defaultTimeColor;
        ViewHolder(View v) {
            super(v);
            gradientView  = v.findViewById(R.id.segment_gradient);
            distView      = v.findViewById(R.id.segment_distance);
            timeView      = v.findViewById(R.id.segment_time);
            colorBar      = v.findViewById(R.id.segment_color_bar);
            surfaceBadge  = v.findViewById(R.id.segment_surface_badge);
            defaultTimeColor = timeView.getCurrentTextColor();
        }
    }
}
