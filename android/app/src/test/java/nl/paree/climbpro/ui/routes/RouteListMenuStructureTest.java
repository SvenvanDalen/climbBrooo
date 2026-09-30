package nl.paree.climbpro.ui.routes;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;

/**
 * Guards the grouped overflow menu of the route list (issue #351): the top level stays
 * short, every group holds real entries, and every leaf action is handled by
 * {@code RouteListActivity.onOptionsItemSelected} (and vice versa).
 */
public class RouteListMenuStructureTest {

    private static final int MAX_TOP_LEVEL_ITEMS = 10;
    private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";

    @Test
    public void topLevelStaysShort() throws Exception {
        List<Element> topLevel = childItems(parseMenu().getDocumentElement());
        assertTrue("top-level menu has " + topLevel.size() + " items",
                topLevel.size() <= MAX_TOP_LEVEL_ITEMS);
    }

    @Test
    public void everyGroupHasAtLeastTwoEntries() throws Exception {
        for (Element item : childItems(parseMenu().getDocumentElement())) {
            Element sub = subMenu(item);
            if (sub != null) {
                assertTrue("group " + id(item) + " is nearly empty", childItems(sub).size() >= 2);
            }
        }
    }

    @Test
    public void everyLeafActionIsHandledAndViceVersa() throws Exception {
        Set<String> leaves = new TreeSet<>();
        collectLeaves(parseMenu().getDocumentElement(), leaves);

        String activity = read(new File(androidAppDir(),
                "src/main/java/nl/paree/climbpro/ui/routes/RouteListActivity.java"));
        int start = activity.indexOf("public boolean onOptionsItemSelected");
        int end = activity.indexOf("return super.onOptionsItemSelected", start);
        Set<String> handled = new TreeSet<>();
        Matcher m = Pattern.compile("R\\.id\\.(action_\\w+)")
                .matcher(activity.substring(start, end));
        while (m.find()) {
            handled.add(m.group(1));
        }
        assertEquals(leaves, handled);
    }

    private static void collectLeaves(Element menu, Set<String> out) {
        for (Element item : childItems(menu)) {
            Element sub = subMenu(item);
            if (sub == null) {
                out.add(id(item));
            } else {
                collectLeaves(sub, out);
            }
        }
    }

    private static List<Element> childItems(Element menu) {
        List<Element> items = new ArrayList<>();
        for (Node n = menu.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element && "item".equals(n.getNodeName())) {
                items.add((Element) n);
            }
        }
        return items;
    }

    private static Element subMenu(Element item) {
        for (Node n = item.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element && "menu".equals(n.getNodeName())) {
                return (Element) n;
            }
        }
        return null;
    }

    private static String id(Element item) {
        return item.getAttributeNS(ANDROID_NS, "id").replace("@+id/", "");
    }

    private static Document parseMenu() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder()
                .parse(new File(androidAppDir(), "src/main/res/menu/route_list_menu.xml"));
    }

    private static String read(File f) throws Exception {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    private static File androidAppDir() {
        File dir = new File("").getAbsoluteFile();
        for (int i = 0; i < 8 && dir != null; i++) {
            File app = new File(dir, "android/app");
            if (new File(app, "src/main/res/menu").isDirectory()) { return app; }
            if (new File(dir, "src/main/res/menu").isDirectory()) { return dir; }
            dir = dir.getParentFile();
        }
        throw new IllegalStateException("android app dir not found from "
                + new File("").getAbsolutePath());
    }
}
