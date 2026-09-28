package nl.paree.climbpro.ui.pain;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import nl.paree.climbpro.R;
import nl.paree.climbpro.data.pain.PainLogEntry;
import nl.paree.climbpro.domain.pain.PainArea;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Logged complaints, newest first; long-press asks to delete. */
public final class PainLogAdapter extends RecyclerView.Adapter<PainLogAdapter.EntryVH> {

    public interface OnEntryLongClickListener {
        void onEntryLongClick(PainLogEntry entry);
    }

    private final List<PainLogEntry> entries = new ArrayList<>();
    private final SimpleDateFormat dateFormat =
            new SimpleDateFormat("EEE d MMM yyyy", Locale.getDefault());
    private final OnEntryLongClickListener listener;

    public PainLogAdapter(OnEntryLongClickListener listener) {
        this.listener = listener;
    }

    public void submit(List<PainLogEntry> newEntries) {
        entries.clear();
        if (newEntries != null) entries.addAll(newEntries);
        notifyDataSetChanged();
    }

    String formatDate(long epochSec) {
        return dateFormat.format(new Date(epochSec * 1000L));
    }

    static String areasText(List<String> areas) {
        StringBuilder sb = new StringBuilder();
        if (areas != null) {
            for (String name : areas) {
                PainArea a = PainArea.fromName(name);
                if (a == null) continue;
                if (sb.length() > 0) sb.append(", ");
                sb.append(a.label);
            }
        }
        return sb.length() > 0 ? sb.toString() : "Geen plek aangegeven";
    }

    @NonNull
    @Override
    public EntryVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_pain_log_entry, parent, false);
        return new EntryVH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull EntryVH holder, int position) {
        PainLogEntry e = entries.get(position);
        holder.date.setText(formatDate(e.timestampEpochSec));
        holder.areas.setText(areasText(e.areas) + "  •  " + e.severity + "/5");
        StringBuilder detail = new StringBuilder();
        if (e.bike != null) detail.append(e.bike);
        if (e.setup != null) detail.append(detail.length() > 0 ? " · " : "").append(e.setup);
        if (e.note != null) detail.append(detail.length() > 0 ? "\n" : "").append(e.note);
        holder.detail.setVisibility(detail.length() > 0 ? View.VISIBLE : View.GONE);
        holder.detail.setText(detail);
        holder.itemView.setOnLongClickListener(v -> {
            if (listener != null) listener.onEntryLongClick(e);
            return true;
        });
    }

    @Override
    public int getItemCount() { return entries.size(); }

    static final class EntryVH extends RecyclerView.ViewHolder {
        final TextView date;
        final TextView areas;
        final TextView detail;
        EntryVH(@NonNull View v) {
            super(v);
            date = v.findViewById(R.id.date);
            areas = v.findViewById(R.id.areas);
            detail = v.findViewById(R.id.detail);
        }
    }
}
