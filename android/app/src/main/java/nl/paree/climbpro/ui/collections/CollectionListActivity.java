package nl.paree.climbpro.ui.collections;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

import nl.paree.climbpro.R;
import nl.paree.climbpro.data.route.RouteCollection;

public final class CollectionListActivity extends AppCompatActivity {

    private CollectionListViewModel viewModel;
    private CollectionListAdapter   adapter;
    private View                    emptyView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_collection_list);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        emptyView = findViewById(R.id.empty);
        RecyclerView recyclerView = findViewById(R.id.recycler_view);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new CollectionListAdapter();
        recyclerView.setAdapter(adapter);

        adapter.setListener(new CollectionListAdapter.OnCollectionClickListener() {
            @Override
            public void onCollectionClick(RouteCollection collection) {
                startActivity(CollectionDetailActivity.intentFor(
                        CollectionListActivity.this, collection.id));
            }
            @Override
            public void onCollectionLongClick(RouteCollection collection) {
                showCollectionActions(collection);
            }
        });

        viewModel = new ViewModelProvider(this).get(CollectionListViewModel.class);
        viewModel.collections().observe(this, list -> {
            adapter.setItems(list);
            emptyView.setVisibility(list == null || list.isEmpty() ? View.VISIBLE : View.GONE);
        });
        viewModel.error().observe(this,
                msg -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());

        FloatingActionButton fab = findViewById(R.id.fab);
        fab.setOnClickListener(v -> showCreateDialog());
    }

    @Override
    protected void onResume() {
        super.onResume();
        viewModel.load();
    }

    @Override
    public boolean onOptionsItemSelected(android.view.MenuItem item) {
        if (item.getItemId() == android.R.id.home) { finish(); return true; }
        return super.onOptionsItemSelected(item);
    }

    private void showCreateDialog() {
        EditText input = new EditText(this);
        input.setHint("Bv. Alpen 2026");
        new AlertDialog.Builder(this)
                .setTitle("Nieuwe collectie")
                .setView(input)
                .setPositiveButton("Aanmaken", (d, w) ->
                        viewModel.create(input.getText().toString()))
                .setNegativeButton("Annuleer", null)
                .show();
    }

    private void showCollectionActions(RouteCollection collection) {
        new AlertDialog.Builder(this)
                .setItems(new String[]{"Hernoemen", "Verwijderen"}, (d, which) -> {
                    if (which == 0) showRenameDialog(collection);
                    else confirmDelete(collection);
                })
                .show();
    }

    private void showRenameDialog(RouteCollection collection) {
        EditText input = new EditText(this);
        input.setText(collection.name);
        new AlertDialog.Builder(this)
                .setTitle("Hernoem collectie")
                .setView(input)
                .setPositiveButton("Opslaan", (d, w) ->
                        viewModel.rename(collection.id, input.getText().toString()))
                .setNegativeButton("Annuleer", null)
                .show();
    }

    private void confirmDelete(RouteCollection collection) {
        new AlertDialog.Builder(this)
                .setTitle("\"" + collection.name + "\" verwijderen?")
                .setMessage("De routes en klimmen zelf blijven bestaan.")
                .setPositiveButton("Verwijder", (d, w) -> viewModel.delete(collection.id))
                .setNegativeButton("Annuleer", null)
                .show();
    }

    public static Intent intentFor(android.content.Context ctx) {
        return new Intent(ctx, CollectionListActivity.class);
    }
}
