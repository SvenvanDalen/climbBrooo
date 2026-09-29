package nl.paree.climbpro.data.planning;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * A packing checklist for one ride type (issue #189), e.g. "Training" or "Bikepacking".
 * Items keep their checked state until the rider resets the list before the next ride.
 * Phone-only.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class PackingList {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class Item {
        public String id;
        public String text;
        public boolean checked;

        public Item() {}

        public Item(String id, String text) {
            this.id = id;
            this.text = text;
        }
    }

    public String id;
    public String name;
    public List<Item> items = new ArrayList<>();

    public int checkedCount() {
        int n = 0;
        for (Item i : items) if (i.checked) n++;
        return n;
    }
}
