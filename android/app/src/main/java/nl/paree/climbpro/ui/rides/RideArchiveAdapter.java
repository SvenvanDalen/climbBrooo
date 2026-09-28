package nl.paree.climbpro.ui.rides;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import nl.paree.climbpro.R;
import nl.paree.climbpro.data.ride.StoredRide;
import nl.paree.climbpro.data.route.AttemptPhotoStore;
import nl.paree.climbpro.domain.ride.RideCategoryLabel;
import nl.paree.climbpro.domain.ride.SummitGroupPhotos;
import nl.paree.climbpro.ui.rides.RideArchiveViewModel.Row;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Flat list of archived rides, newest first, each with its automatic category. */
public final class RideArchiveAdapter extends RecyclerView.Adapter<RideArchiveAdapter.RowVH> {

    private final List<Row> rows = new ArrayList<>();
    private final SimpleDateFormat dateFormat =
            new SimpleDateFormat("EEE d MMM yyyy", Locale.getDefault());

    /** Decodes group-photo thumbnails off the main thread. */
    private final ExecutorService thumbnailExecutor = Executors.newSingleThreadExecutor();

    public void shutdown() { thumbnailExecutor.shutdownNow(); }

    public void submit(List<Row> newRows) {
        rows.clear();
        if (newRows != null) rows.addAll(newRows);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public RowVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_ride, parent, false);
        return new RowVH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull RowVH holder, int position) {
        Row row = rows.get(position);
        StoredRide r = row.ride;
        holder.name.setText(r.name != null && !r.name.isEmpty() ? r.name : "Rit");
        holder.category.setText(RideCategoryLabel.forCategory(row.category));

        String date = r.startEpochSec > 0
                ? dateFormat.format(new Date(r.startEpochSec * 1000L)) : "onbekende datum";
        holder.stats.setText(String.format(Locale.getDefault(),
                "%s  •  %.1f km  •  %d hm  •  %.1f km/u",
                date, r.distanceM / 1000f, Math.round(r.elevationGainM), r.avgSpeedMps * 3.6f));

        bindGroupPhoto(holder, row);
    }

    @Override
    public int getItemCount() { return rows.size(); }

    private void bindGroupPhoto(RowVH holder, Row row) {
        String caption = SummitGroupPhotos.rideCaption(row.groupPhotos);
        if (caption == null) {
            holder.groupRow.setVisibility(View.GONE);
            holder.groupPhoto.setTag(null);
            holder.groupPhoto.setImageDrawable(null);
            return;
        }
        holder.groupRow.setVisibility(View.VISIBLE);
        holder.groupCaption.setText("👥 " + caption);

        String fileName = row.groupPhotos.get(0).photoFileName;
        ImageView target = holder.groupPhoto;
        target.setTag(fileName);
        target.setImageDrawable(null);
        // Reset on bind, since a recycled holder may still carry the previous row's GONE state.
        target.setVisibility(View.VISIBLE);
        File file = AttemptPhotoStore.fileFor(target.getContext(), fileName);
        if (!file.exists()) {
            target.setVisibility(View.GONE);
            return;
        }
        int sizePx = (int) (56 * target.getResources().getDisplayMetrics().density);
        if (thumbnailExecutor.isShutdown()) return;
        thumbnailExecutor.execute(() -> {
            Bitmap bmp = decode(file, sizePx);
            target.post(() -> {
                // The holder may have been recycled for another ride meanwhile.
                if (!fileName.equals(target.getTag())) return;
                if (bmp == null) {
                    target.setVisibility(View.GONE);
                } else {
                    target.setVisibility(View.VISIBLE);
                    target.setImageBitmap(bmp);
                }
            });
        });
    }

    /** Downsampled decode (same approach as PhotoQuizActivity); null when missing/unreadable. */
    private static Bitmap decode(File file, int targetPx) {
        if (!file.exists()) return null;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        int sample = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= targetPx) sample *= 2;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        return BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
    }

    static final class RowVH extends RecyclerView.ViewHolder {
        final TextView name;
        final TextView category;
        final TextView stats;
        final View groupRow;
        final ImageView groupPhoto;
        final TextView groupCaption;
        RowVH(@NonNull View v) {
            super(v);
            name = v.findViewById(R.id.name);
            category = v.findViewById(R.id.category);
            stats = v.findViewById(R.id.stats);
            groupRow = v.findViewById(R.id.group_photo_row);
            groupPhoto = v.findViewById(R.id.group_photo);
            groupCaption = v.findViewById(R.id.group_caption);
        }
    }
}
