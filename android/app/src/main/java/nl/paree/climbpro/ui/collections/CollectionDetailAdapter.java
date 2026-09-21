package nl.paree.climbpro.ui.collections;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import nl.paree.climbpro.R;

import java.util.ArrayList;
import java.util.List;

final class CollectionDetailAdapter
        extends RecyclerView.Adapter<CollectionDetailAdapter.ViewHolder> {

    interface OnMemberLongClickListener { void onMemberLongClick(CollectionMember member); }

    private List<CollectionMember> items = new ArrayList<>();
    private OnMemberLongClickListener listener;

    void setItems(List<CollectionMember> list) {
        items = list != null ? list : new ArrayList<>();
        notifyDataSetChanged();
    }

    void setListener(OnMemberLongClickListener l) { listener = l; }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_collection_member, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder h, int position) {
        CollectionMember m = items.get(position);
        h.labelView.setText(m.label);
        h.typeView.setText(m.isClimb() ? "Klim" : "Route");
        h.itemView.setOnLongClickListener(v -> {
            if (listener != null) listener.onMemberLongClick(m);
            return true;
        });
    }

    @Override
    public int getItemCount() { return items.size(); }

    static final class ViewHolder extends RecyclerView.ViewHolder {
        TextView labelView;
        TextView typeView;
        ViewHolder(View v) {
            super(v);
            labelView = v.findViewById(R.id.member_label);
            typeView  = v.findViewById(R.id.member_type);
        }
    }
}
