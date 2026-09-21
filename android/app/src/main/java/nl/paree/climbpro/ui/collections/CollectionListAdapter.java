package nl.paree.climbpro.ui.collections;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import nl.paree.climbpro.R;
import nl.paree.climbpro.data.route.RouteCollection;

import java.util.ArrayList;
import java.util.List;

public final class CollectionListAdapter
        extends RecyclerView.Adapter<CollectionListAdapter.ViewHolder> {

    public interface OnCollectionClickListener {
        void onCollectionClick(RouteCollection collection);
        void onCollectionLongClick(RouteCollection collection);
    }

    private List<RouteCollection> items = new ArrayList<>();
    private OnCollectionClickListener listener;

    public void setItems(List<RouteCollection> list) {
        items = list != null ? list : new ArrayList<>();
        notifyDataSetChanged();
    }

    public void setListener(OnCollectionClickListener l) { listener = l; }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_collection, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder h, int position) {
        RouteCollection c = items.get(position);
        h.nameView.setText(c.name != null ? c.name : "(naamloos)");
        int routeCount = c.routeIds != null ? c.routeIds.size() : 0;
        int climbCount = c.climbs   != null ? c.climbs.size()   : 0;
        h.countView.setText(routeCount + " route(s) · " + climbCount + " klim(men)");
        h.itemView.setOnClickListener(v -> { if (listener != null) listener.onCollectionClick(c); });
        h.itemView.setOnLongClickListener(v -> {
            if (listener != null) listener.onCollectionLongClick(c);
            return true;
        });
    }

    @Override
    public int getItemCount() { return items.size(); }

    static final class ViewHolder extends RecyclerView.ViewHolder {
        TextView nameView;
        TextView countView;
        ViewHolder(View v) {
            super(v);
            nameView  = v.findViewById(R.id.collection_name);
            countView = v.findViewById(R.id.collection_count);
        }
    }
}
