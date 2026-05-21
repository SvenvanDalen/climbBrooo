package nl.paree.climbpro.ui.climbs;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import nl.paree.climbpro.R;
import nl.paree.climbpro.data.route.StoredSegment;

import java.util.ArrayList;
import java.util.List;

public final class ClimbSegmentAdapter
        extends RecyclerView.Adapter<ClimbSegmentAdapter.ViewHolder> {

    private static final int[] SEGMENT_COLORS = {
            Color.parseColor("#FFFF99"),  // 0 light yellow
            Color.parseColor("#FFFF00"),  // 1 yellow
            Color.parseColor("#FFD700"),  // 2 dark yellow
            Color.parseColor("#FF8C00"),  // 3 orange
            Color.parseColor("#FF4500"),  // 4 dark orange
            Color.parseColor("#FF0000"),  // 5 red
    };

    private List<StoredSegment> items = new ArrayList<>();

    public void setItems(List<StoredSegment> list) {
        items = list != null ? list : new ArrayList<>();
        notifyDataSetChanged();
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
        int ci = Math.max(0, Math.min(5, s.colorIndex));
        h.colorBar.setBackgroundColor(SEGMENT_COLORS[ci]);
    }

    @Override
    public int getItemCount() { return items.size(); }

    static final class ViewHolder extends RecyclerView.ViewHolder {
        TextView gradientView;
        TextView distView;
        View     colorBar;
        ViewHolder(View v) {
            super(v);
            gradientView = v.findViewById(R.id.segment_gradient);
            distView     = v.findViewById(R.id.segment_distance);
            colorBar     = v.findViewById(R.id.segment_color_bar);
        }
    }
}
