package nl.paree.climbpro.ui.climbs;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import nl.paree.climbpro.R;
import nl.paree.climbpro.ui.climbs.ClimbTimelineViewModel.TimelineRow;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Chronological attempt list, with a month header row inserted whenever the
 * calendar month changes between consecutive rows (rows are already sorted
 * newest-first by the view model).
 */
public final class TimelineAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    public interface OnClick { void onAttempt(TimelineRow row); }
    public interface OnLongClick { void onAttemptLongPress(TimelineRow row); }

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_ATTEMPT = 1;

    /** Flattened list entry: either a month header label or an attempt row. */
    private static final class Entry {
        final String header;   // non-null for a header entry
        final TimelineRow row; // non-null for an attempt entry
        Entry(String header) { this.header = header; this.row = null; }
        Entry(TimelineRow row) { this.header = null; this.row = row; }
    }

    private final List<Entry> entries = new ArrayList<>();
    private final OnClick onClick;
    private final OnLongClick onLongClick;
    private final SimpleDateFormat monthFormat = new SimpleDateFormat("MMMM yyyy", Locale.getDefault());
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("d MMM", Locale.getDefault());

    public TimelineAdapter(OnClick onClick) { this(onClick, null); }

    public TimelineAdapter(OnClick onClick, OnLongClick onLongClick) {
        this.onClick = onClick;
        this.onLongClick = onLongClick;
    }

    public void submit(List<TimelineRow> rows) {
        entries.clear();
        String lastMonth = null;
        for (TimelineRow row : rows) {
            String month = monthFormat.format(new Date(row.dateEpochSec * 1000L));
            if (!month.equals(lastMonth)) {
                entries.add(new Entry(month));
                lastMonth = month;
            }
            entries.add(new Entry(row));
        }
        notifyDataSetChanged();
    }

    @Override
    public int getItemViewType(int position) {
        return entries.get(position).header != null ? TYPE_HEADER : TYPE_ATTEMPT;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == TYPE_HEADER) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_timeline_header, parent, false);
            return new HeaderVH(v);
        }
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_timeline_attempt, parent, false);
        return new AttemptVH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Entry entry = entries.get(position);
        if (holder instanceof HeaderVH) {
            ((HeaderVH) holder).label.setText(entry.header);
            return;
        }
        AttemptVH h = (AttemptVH) holder;
        TimelineRow row = entry.row;
        h.name.setText(row.displayName);
        String date = dateFormat.format(new Date(row.dateEpochSec * 1000L));
        h.stats.setText(String.format(Locale.getDefault(),
                "%s  •  %d m  •  %.1f%%  •  %s",
                date, row.lengthM, row.avgGradient, formatTime(row.elapsedSec)));
        h.itemView.setOnClickListener(v -> {
            if (onClick != null) onClick.onAttempt(row);
        });
        h.itemView.setOnLongClickListener(v -> {
            if (onLongClick == null) return false;
            onLongClick.onAttemptLongPress(row);
            return true;
        });
    }

    @Override
    public int getItemCount() { return entries.size(); }

    static String formatTime(int sec) {
        int m = sec / 60, s = sec % 60;
        return String.format(Locale.getDefault(), "%d:%02d", m, s);
    }

    static final class HeaderVH extends RecyclerView.ViewHolder {
        final TextView label;
        HeaderVH(@NonNull View v) {
            super(v);
            label = v.findViewById(R.id.monthLabel);
        }
    }

    static final class AttemptVH extends RecyclerView.ViewHolder {
        final TextView name;
        final TextView stats;
        AttemptVH(@NonNull View v) {
            super(v);
            name = v.findViewById(R.id.name);
            stats = v.findViewById(R.id.stats);
        }
    }
}
