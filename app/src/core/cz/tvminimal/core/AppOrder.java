package cz.tvminimal.core;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * App grid order. Keys are "package/activity". The persisted order also remembers uninstalled apps so a
 * reinstall returns to its old place. Unknown (newly installed) apps are appended and flagged as new.
 */
public final class AppOrder {
    /** First-run order by package: native TV entry, then the six preferred apps. */
    public static final List<String> PREFERRED = Arrays.asList(
            "com.realtek.tv",
            "cz.ceskatelevize.ivysilani.tvapp",
            "cz.prima.tvapp",
            "org.smarttube.beta",
            "nl.streamgroup.skylinkcz",
            "com.wbd.stream",
            "com.netflix.ninja");
    /** First-run: these go after everything else. */
    static final List<String> LAST = Arrays.asList("com.android.vending", "com.android.tv.settings");

    private final String self;
    /** Launcher's own tiles (Action Reel) that rank before all apps on first run / reset. */
    private final List<String> builtins;
    private final List<String> order = new ArrayList<>();
    private final Set<String> hidden = new LinkedHashSet<>();
    private final Set<String> fresh = new HashSet<>();

    public AppOrder(String selfPackage) { this(selfPackage, Collections.emptyList()); }

    public AppOrder(String selfPackage, List<String> builtins) {
        self = selfPackage;
        this.builtins = builtins;
    }

    public static AppOrder restore(String self, String order, String hidden) {
        return restore(self, order, hidden, Collections.emptyList());
    }

    /**
     * A built-in tile missing from a saved (non-empty) order is an upgrade: insert it once at the front, i.e. right
     * after the fixed ČT24 tile, flagged new. Once saved, the user's moves and hides of it are kept like any app.
     */
    public static AppOrder restore(String self, String order, String hidden, List<String> builtins) {
        AppOrder o = new AppOrder(self, builtins);
        o.order.addAll(parse(order));
        o.hidden.addAll(parse(hidden));
        if (!o.order.isEmpty()) {
            int at = 0;
            for (String b : builtins) {
                if (o.order.contains(b)) continue;
                o.order.add(at++, b);
                o.fresh.add(b);
            }
        }
        return o;
    }

    private static List<String> parse(String s) {
        List<String> out = new ArrayList<>();
        if (s == null) return out;
        for (String line : s.split("\n")) {
            String k = line.trim();
            if (k.indexOf('/') > 0 && !out.contains(k)) out.add(k);
        }
        return out;
    }

    private static String pkg(String key) { return key.substring(0, key.indexOf('/')); }

    /** Updates the order with the installed set and returns the visible list. */
    public List<String> merge(Map<String, String> installed) {
        boolean firstRun = order.isEmpty();
        List<String> unknown = new ArrayList<>();
        for (String k : installed.keySet()) {
            if (k.indexOf('/') <= 0 || pkg(k).equals(self)) continue;
            if (!order.contains(k)) unknown.add(k);
        }
        Collator c = Collator.getInstance(new Locale("cs", "CZ"));
        unknown.sort((a, b) -> {
            int ra = rank(a), rb = rank(b);
            if (ra != rb) return Integer.compare(ra, rb);
            return c.compare(label(installed, a), label(installed, b));
        });
        for (String k : unknown) {
            order.add(k);
            if (!firstRun) fresh.add(k);
        }
        return visible(installed);
    }

    private int rank(String key) {
        int b = builtins.indexOf(key);
        if (b >= 0) return -100 + b;
        int p = PREFERRED.indexOf(pkg(key));
        if (p >= 0) return p;
        int l = LAST.indexOf(pkg(key));
        return l >= 0 ? 1000 + l : 500;
    }

    private static String label(Map<String, String> installed, String k) {
        String l = installed.get(k);
        return l == null ? k : l;
    }

    private List<String> visible(Map<String, String> installed) {
        List<String> v = new ArrayList<>();
        for (String k : order) {
            if (installed.containsKey(k) && !hidden.contains(k) && !pkg(k).equals(self)) v.add(k);
        }
        return v;
    }

    public boolean isNew(String key) { return fresh.contains(key); }

    public void clearNew(String key) { fresh.remove(key); }

    public void hide(String key) {
        hidden.add(key);
        fresh.remove(key);
    }

    /** Unhidden apps go to the end of the visible list. */
    public void unhide(String key) {
        if (!hidden.remove(key)) return;
        order.remove(key);
        order.add(key);
    }

    public List<String> hiddenInstalled(Map<String, String> installed) {
        List<String> h = new ArrayList<>();
        for (String k : order) if (hidden.contains(k) && installed.containsKey(k)) h.add(k);
        return h;
    }

    /** Moves a visible key by delta positions among visible items; returns its new visible index or -1. */
    public int move(String key, int delta, Map<String, String> installed) {
        List<String> v = visible(installed);
        int from = v.indexOf(key);
        if (from < 0) return -1;
        int to = Math.max(0, Math.min(v.size() - 1, from + delta));
        if (to == from) return from;
        v.remove(from);
        v.add(to, key);
        // Rebuild order: visible keys take the slots previously used by visible keys; others stay put.
        Set<String> vis = new HashSet<>(v);
        int vi = 0;
        for (int i = 0; i < order.size(); i++) {
            if (vis.contains(order.get(i))) order.set(i, v.get(vi++));
        }
        return to;
    }

    public String serializeOrder() { return String.join("\n", order); }

    public String serializeHidden() { return String.join("\n", hidden); }

    public void reset() {
        order.clear();
        hidden.clear();
        fresh.clear();
    }
}
