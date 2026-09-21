package nl.paree.climbpro.ui.collections;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import nl.paree.climbpro.R;
import nl.paree.climbpro.data.route.RouteCollection;

public final class CollectionDetailActivity extends AppCompatActivity {

    private static final String EXTRA_COLLECTION_ID = "collection_id";

    private CollectionDetailViewModel viewModel;
    private CollectionDetailAdapter   adapter;
    private View                      emptyView;
    private Toolbar                   toolbar;
    private String                    collectionId;

    public static Intent intentFor(Context ctx, String collectionId) {
        Intent i = new Intent(ctx, CollectionDetailActivity.class);
        i.putExtra(EXTRA_COLLECTION_ID, collectionId);
        return i;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_collection_detail);

        collectionId = getIntent().getStringExtra(EXTRA_COLLECTION_ID);

        toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        emptyView = findViewById(R.id.empty);
        RecyclerView list = findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new CollectionDetailAdapter();
        list.setAdapter(adapter);
        adapter.setListener(this::confirmRemoveMember);

        viewModel = new ViewModelProvider(this).get(CollectionDetailViewModel.class);
        viewModel.collection().observe(this, c -> {
            if (c != null) setTitle(c.name != null ? c.name : "Collectie");
        });
        viewModel.members().observe(this, members -> {
            adapter.setItems(members);
            emptyView.setVisibility(members == null || members.isEmpty() ? View.VISIBLE : View.GONE);
        });
        viewModel.error().observe(this,
                msg -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());

        viewModel.load(collectionId);
    }

    @Override
    protected void onResume() {
        super.onResume();
        viewModel.load(collectionId);
    }

    private void setTitle(String name) {
        toolbar.setTitle(name);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.collection_detail_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == android.R.id.home) { finish(); return true; }
        if (id == R.id.action_rename_collection) { showRenameDialog(); return true; }
        if (id == R.id.action_delete_collection) { confirmDeleteCollection(); return true; }
        return super.onOptionsItemSelected(item);
    }

    private void showRenameDialog() {
        RouteCollection current = viewModel.collection().getValue();
        EditText input = new EditText(this);
        input.setText(current != null ? current.name : "");
        new AlertDialog.Builder(this)
                .setTitle("Hernoem collectie")
                .setView(input)
                .setPositiveButton("Opslaan", (d, w) ->
                        viewModel.rename(collectionId, input.getText().toString()))
                .setNegativeButton("Annuleer", null)
                .show();
    }

    private void confirmDeleteCollection() {
        new AlertDialog.Builder(this)
                .setTitle("Collectie verwijderen?")
                .setMessage("De routes en klimmen zelf blijven bestaan.")
                .setPositiveButton("Verwijder", (d, w) -> {
                    viewModel.delete(collectionId);
                    finish();
                })
                .setNegativeButton("Annuleer", null)
                .show();
    }

    private void confirmRemoveMember(CollectionMember member) {
        new AlertDialog.Builder(this)
                .setTitle("\"" + member.label + "\" verwijderen uit collectie?")
                .setPositiveButton("Verwijder", (d, w) -> {
                    if (member.isClimb()) {
                        viewModel.removeClimb(collectionId, member.routeId, member.climbIndex);
                    } else {
                        viewModel.removeRoute(collectionId, member.routeId);
                    }
                })
                .setNegativeButton("Annuleer", null)
                .show();
    }
}
