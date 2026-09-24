package nl.paree.climbpro.ui.tire;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import nl.paree.climbpro.R;
import nl.paree.climbpro.data.tire.TirePressureLogEntry;
import nl.paree.climbpro.domain.tire.TirePressureUnits;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Logged tire-pressure checks, newest first; long-press asks to delete. */
public final class TirePressureLogAdapter
        extends RecyclerView.Adapter<TirePressureLogAdapter.EntryVH> {

    public interface OnEntryLongClickListener {
        void onEntryLongClick(TirePressureLogEntry entry);
    }

    private final List<TirePressureLogEntry> entries = new ArrayList<>();
    private final SimpleDateFormat dateFormat =
            new SimpleDateFormat("EEE d MMM yyyy", Locale.getDefault());
    private final OnEntryLongClickListener listener;

    public TirePressureLogAdapter(OnEntryLongClickListener listener) {
        this.listener = listener;
    }

    public void submit(List<TirePressureLogEntry> newEntries) {
        entries.clear();
        if (newEntries != null) entries.addAll(newEntries);
        notifyDataSetChanged();
    }

    String formatDate(long epochSec) {
        return dateFormat.format(new Date(epochSec * 1000L));
    }

    @NonNull
    @Override
    public EntryVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_tire_pressure_entry, parent, false);
        return new EntryVH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull EntryVH holder, int position) {
        TirePressureLogEntry e = entries.get(position);
        holder.date.setText(formatDate(e.timestampEpochSec));
        holder.pressures.setText("Voor " + TirePressureUnits.format(e.frontBar)
                + "  •  Achter " + TirePressureUnits.format(e.rearBar));
        boolean hasNote = e.note != null && !e.note.isEmpty();
        holder.note.setVisibility(hasNote ? View.VISIBLE : View.GONE);
        holder.note.setText(hasNote ? e.note : null);
        holder.itemView.setOnLongClickListener(v -> {
            if (listener != null) listener.onEntryLongClick(e);
            return true;
        });
    }

    @Override
    public int getItemCount() { return entries.size(); }

    static final class EntryVH extends RecyclerView.ViewHolder {
        final TextView date;
        final TextView pressures;
        final TextView note;
        EntryVH(@NonNull View v) {
            super(v);
            date = v.findViewById(R.id.date);
            pressures = v.findViewById(R.id.pressures);
            note = v.findViewById(R.id.note);
        }
    }
}
