package nl.paree.climbpro.ui.planning;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.lifecycle.ViewModelProvider;

import nl.paree.climbpro.R;
import nl.paree.climbpro.data.planning.PackingList;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Issue #189: "Paklijst" — a checklist per ride type (Training, Toerrit, Bikepacking by
 * default), editable and ticked off before leaving. Long-press an item to remove it.
 * Phone-only — see {@link PackingListViewModel}.
 */
public final class PackingListActivity extends AppCompatActivity {

    private PackingListViewModel viewModel;
    private Spinner spinner;
    private TextView progress;
    private LinearLayout itemsContainer;
    private List<PackingList> current = new ArrayList<>();
    /** Set while re-populating the spinner so its callback doesn't overwrite the selection. */
    private boolean binding;

    public static Intent intentFor(Context context) {
        return new Intent(context, PackingListActivity.class);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_packing_list);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        toolbar.setNavigationOnClickListener(v -> finish());

        spinner = findViewById(R.id.listSpinner);
        progress = findViewById(R.id.packingProgress);
        itemsContainer = findViewById(R.id.packingItems);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                if (binding || pos >= current.size()) return;
                viewModel.select(current.get(pos).id);
                renderItems();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) { }
        });

        findViewById(R.id.addItemButton).setOnClickListener(v -> {
            PackingList l = selected();
            if (l != null) prompt("Item toevoegen", "", text -> viewModel.addItem(l.id, text));
        });
        findViewById(R.id.resetButton).setOnClickListener(v -> {
            PackingList l = selected();
            if (l != null) viewModel.resetChecks(l.id);
        });

        viewModel = new ViewModelProvider(this).get(PackingListViewModel.class);
        viewModel.lists().observe(this, this::render);
        viewModel.load();
    }

    @Override
    public boolean onCreateOptionsMenu(android.view.Menu menu) {
        getMenuInflater().inflate(R.menu.packing_list_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(android.view.MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_new_list) {
            prompt("Nieuwe paklijst", "", name -> viewModel.addList(name));
            return true;
        } else if (id == R.id.action_rename_list) {
            PackingList l = selected();
            if (l != null) prompt("Lijst hernoemen", l.name, name -> viewModel.renameList(l.id, name));
            return true;
        } else if (id == R.id.action_delete_list) {
            confirmDeleteList();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void render(List<PackingList> lists) {
        current = lists != null ? lists : new ArrayList<>();
        List<String> names = new ArrayList<>();
        int pos = 0;
        for (int i = 0; i < current.size(); i++) {
            names.add(current.get(i).name);
            if (current.get(i).id.equals(viewModel.selectedId())) pos = i;
        }
        if (!current.isEmpty()) viewModel.select(current.get(pos).id);
        binding = true;
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, names);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(pos, false);
        spinner.post(() -> binding = false);
        renderItems();
    }

    private void renderItems() {
        itemsContainer.removeAllViews();
        PackingList l = selected();
        if (l == null) {
            progress.setText("Nog geen paklijsten. Maak er een via het menu.");
            return;
        }
        progress.setText(l.items.isEmpty() ? "Deze lijst is nog leeg."
                : l.checkedCount() + " van " + l.items.size() + " ingepakt"
                        + (l.checkedCount() == l.items.size() ? " — klaar om te gaan!" : ""));
        int pad = Math.round(8 * getResources().getDisplayMetrics().density);
        for (PackingList.Item item : l.items) {
            CheckBox box = new CheckBox(this);
            box.setText(item.text);
            box.setChecked(item.checked);
            box.setPadding(pad, pad, pad, pad);
            box.setOnCheckedChangeListener((b, checked) ->
                    viewModel.setChecked(l.id, item.id, checked));
            box.setOnLongClickListener(v -> {
                new AlertDialog.Builder(this)
                        .setTitle("\"" + item.text + "\" verwijderen?")
                        .setPositiveButton("Verwijderen",
                                (d, w) -> viewModel.removeItem(l.id, item.id))
                        .setNegativeButton("Annuleren", null)
                        .show();
                return true;
            });
            itemsContainer.addView(box);
        }
    }

    private PackingList selected() {
        for (PackingList l : current) {
            if (l.id.equals(viewModel.selectedId())) return l;
        }
        return null;
    }

    private void confirmDeleteList() {
        PackingList l = selected();
        if (l == null) return;
        new AlertDialog.Builder(this)
                .setTitle("Lijst \"" + l.name + "\" verwijderen?")
                .setPositiveButton("Verwijderen", (d, w) -> viewModel.deleteList(l.id))
                .setNegativeButton("Annuleren", null)
                .show();
    }

    private void prompt(String title, String initial, Consumer<String> onOk) {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setText(initial);
        input.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(input)
                .setPositiveButton("Opslaan", (d, w) -> {
                    String text = input.getText().toString().trim();
                    if (!text.isEmpty()) onOk.accept(text);
                })
                .setNegativeButton("Annuleren", null)
                .show();
    }
}
