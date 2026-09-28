package nl.paree.climbpro.ui.battery;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import nl.paree.climbpro.R;
import nl.paree.climbpro.data.battery.BatteryDevice;
import nl.paree.climbpro.domain.battery.BatteryKind;
import nl.paree.climbpro.domain.battery.BatteryStatusCalculator;

import java.util.ArrayList;
import java.util.List;

/** Tracked batteries; tap edits, "Opgeladen" logs a charge now. */
public final class BatteryAdapter extends RecyclerView.Adapter<BatteryAdapter.DeviceVH> {

    public interface Listener {
        void onEdit(BatteryDevice d);
        void onCharged(BatteryDevice d);
    }

    private final List<BatteryDevice> devices = new ArrayList<>();
    private final Listener listener;

    public BatteryAdapter(Listener listener) {
        this.listener = listener;
    }

    public void submit(List<BatteryDevice> newDevices) {
        devices.clear();
        if (newDevices != null) devices.addAll(newDevices);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public DeviceVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_battery_device, parent, false);
        return new DeviceVH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull DeviceVH holder, int position) {
        BatteryDevice d = devices.get(position);
        long now = System.currentTimeMillis() / 1000L;
        holder.name.setText(d.name);
        holder.kind.setText(BatteryKind.fromName(d.kind).label);
        holder.status.setText(BatteryStatusCalculator.statusText(d, now));
        holder.status.setTextColor(ContextCompat.getColor(holder.itemView.getContext(),
                BatteryStatusCalculator.isDue(d, now)
                        ? R.color.color_accent : R.color.color_text_tertiary));
        holder.itemView.setOnClickListener(v -> listener.onEdit(d));
        holder.charged.setOnClickListener(v -> listener.onCharged(d));
    }

    @Override
    public int getItemCount() { return devices.size(); }

    static final class DeviceVH extends RecyclerView.ViewHolder {
        final TextView name;
        final TextView kind;
        final TextView status;
        final Button charged;
        DeviceVH(@NonNull View v) {
            super(v);
            name = v.findViewById(R.id.name);
            kind = v.findViewById(R.id.kind);
            status = v.findViewById(R.id.status);
            charged = v.findViewById(R.id.btn_charged);
        }
    }
}
