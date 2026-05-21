package nl.paree.climbpro.ui.routes;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import nl.paree.climbpro.R;
import nl.paree.climbpro.data.route.RouteCatalogEntry;

import java.util.ArrayList;
import java.util.List;

public final class RouteListAdapter
        extends RecyclerView.Adapter<RouteListAdapter.ViewHolder> {

    public interface OnRouteClickListener {
        void onRouteClick(RouteCatalogEntry entry);
        void onRouteLongClick(RouteCatalogEntry entry);
    }

    private List<RouteCatalogEntry> items = new ArrayList<>();
    private OnRouteClickListener listener;

    public void setItems(List<RouteCatalogEntry> list) {
        items = list != null ? list : new ArrayList<>();
        notifyDataSetChanged();
    }

    public void setListener(OnRouteClickListener l) { listener = l; }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_route, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder h, int position) {
        RouteCatalogEntry entry = items.get(position);
        String name = entry.userDisplayName != null ? entry.userDisplayName : entry.name;
        h.nameView.setText(name != null ? name : entry.routeId);
        h.climbCountView.setText(entry.climbCount + " climb(s)");
        h.itemView.setOnClickListener(v -> { if (listener != null) listener.onRouteClick(entry); });
        h.itemView.setOnLongClickListener(v -> {
            if (listener != null) listener.onRouteLongClick(entry);
            return true;
        });
    }

    @Override
    public int getItemCount() { return items.size(); }

    static final class ViewHolder extends RecyclerView.ViewHolder {
        TextView nameView;
        TextView climbCountView;
        ViewHolder(View v) {
            super(v);
            nameView      = v.findViewById(R.id.route_name);
            climbCountView = v.findViewById(R.id.route_climb_count);
        }
    }
}
