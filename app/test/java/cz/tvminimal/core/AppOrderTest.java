package cz.tvminimal.core;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/** Ordering, discovery of new apps, hide/unhide, move and persistence. */
public class AppOrderTest {
    static final String TV = "com.realtek.tv/.TvActivity";
    static final String IVYS = "cz.ceskatelevize.ivysilani.tvapp/.MainActivity";
    static final String PRIMA = "cz.prima.tvapp/.Main";
    static final String STUBE = "org.smarttube.beta/.Splash";
    static final String SKY = "nl.streamgroup.skylinkcz/.Fullscreen";
    static final String MAX = "com.wbd.stream/.Main";
    static final String NFLX = "com.netflix.ninja/.MainActivity";
    static final String YT = "com.google.android.youtube.tv/.Shell";
    static final String TWITCH = "tv.twitch.android.app/.Starshot";
    static final String SETTINGS = "com.android.tv.settings/.MainSettings";
    static final String SELF = "cz.tvminimal.home/.HomeActivity";

    static final Map<String, String> LABELS = new LinkedHashMap<>();
    static {
        LABELS.put(YT, "YouTube");
        LABELS.put(TWITCH, "Twitch");
        LABELS.put(SETTINGS, "Nastavení");
    }

    static Map<String, String> installed(String... keys) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String k : keys) m.put(k, LABELS.getOrDefault(k, k));
        return m;
    }

    @Test public void firstRunPutsPreferredFirstSettingsLastSelfExcluded() {
        AppOrder o = new AppOrder("cz.tvminimal.home");
        List<String> v = o.merge(installed(YT, SETTINGS, NFLX, TWITCH, SELF, MAX, SKY, STUBE, PRIMA, IVYS, TV));
        assertEquals(Arrays.asList(TV, IVYS, PRIMA, STUBE, SKY, MAX, NFLX, TWITCH, YT, SETTINGS), v);
    }

    @Test public void newlyInstalledAppIsAppendedAndUninstalledDisappears() {
        AppOrder o = new AppOrder("cz.tvminimal.home");
        o.merge(installed(TV, IVYS, SETTINGS));
        List<String> v = o.merge(installed(TV, SETTINGS, YT)); // IVYS removed, YT new
        assertEquals(Arrays.asList(TV, SETTINGS, YT), v);
        assertTrue(o.isNew(YT));
        assertFalse(o.isNew(TV));
    }

    @Test public void reinstalledAppKeepsItsOldPosition() {
        AppOrder o = new AppOrder("cz.tvminimal.home");
        o.merge(installed(TV, IVYS, SETTINGS));
        o.merge(installed(TV, SETTINGS));
        assertEquals(Arrays.asList(TV, IVYS, SETTINGS), o.merge(installed(TV, IVYS, SETTINGS)));
    }

    @Test public void hideAndUnhide() {
        AppOrder o = new AppOrder("cz.tvminimal.home");
        o.merge(installed(TV, IVYS, YT));
        o.hide(YT);
        assertEquals(Arrays.asList(TV, IVYS), o.merge(installed(TV, IVYS, YT)));
        assertEquals(Arrays.asList(YT), o.hiddenInstalled(installed(TV, IVYS, YT)));
        o.unhide(YT);
        assertEquals(Arrays.asList(TV, IVYS, YT), o.merge(installed(TV, IVYS, YT)));
    }

    @Test public void moveWithinVisibleListSkipsHiddenAndClamps() {
        AppOrder o = new AppOrder("cz.tvminimal.home");
        Map<String, String> inst = installed(TV, IVYS, YT, TWITCH);
        o.merge(inst); // TV IVYS Twitch YouTube
        o.hide(IVYS);
        assertEquals(1, o.move(TV, +1, inst)); // TV jumps over hidden IVYS, lands after TWITCH
        assertEquals(Arrays.asList(TWITCH, TV, YT), o.merge(inst));
        assertEquals(2, o.move(TV, +10, inst)); // clamp at end
        assertEquals(Arrays.asList(TWITCH, YT, TV), o.merge(inst));
        assertEquals(0, o.move(TV, -10, inst));
        assertEquals(Arrays.asList(TV, TWITCH, YT), o.merge(inst));
    }

    @Test public void persistenceRoundTrip() {
        AppOrder o = new AppOrder("cz.tvminimal.home");
        Map<String, String> inst = installed(TV, IVYS, YT);
        o.merge(inst);
        o.move(YT, -2, inst);
        o.hide(IVYS);
        AppOrder r = AppOrder.restore("cz.tvminimal.home", o.serializeOrder(), o.serializeHidden());
        assertEquals(Arrays.asList(YT, TV), r.merge(inst));
        assertFalse(r.isNew(YT));
    }

    @Test public void restoreToleratesGarbage() {
        AppOrder r = AppOrder.restore("cz.tvminimal.home", "\n\n  \nbogus\n", null);
        assertEquals(Arrays.asList(TV), r.merge(installed(TV)));
    }

    @Test public void resetRestoresDefaults() {
        AppOrder o = new AppOrder("cz.tvminimal.home");
        Map<String, String> inst = installed(YT, TV);
        o.merge(inst);
        o.move(YT, -1, inst);
        o.hide(TV);
        o.reset();
        assertEquals(Arrays.asList(TV, YT), o.merge(inst));
    }

    // ---------------------------------------------------------------- built-in Action Reel tile (1.3.0)

    static final String AR = "#tvm/actionreel";
    static final List<String> BUILTINS = Arrays.asList(AR);

    @Test public void upgradeFrom120InsertsActionReelFirstOnceAndKeepsUserOrderAndHidden() {
        String savedOrder = String.join("\n", NFLX, TV, YT, IVYS); // user-customised 1.2.0 prefs
        AppOrder o = AppOrder.restore("cz.tvminimal.home", savedOrder, YT, BUILTINS);
        Map<String, String> inst = installed(NFLX, TV, YT, IVYS, AR);
        assertEquals(Arrays.asList(AR, NFLX, TV, IVYS), o.merge(inst)); // right after the fixed ČT24 tile
        assertEquals(String.join("\n", AR, NFLX, TV, YT, IVYS), o.serializeOrder());
        assertEquals(YT, o.serializeHidden());
        assertTrue(o.isNew(AR)); // badge once, like a newly installed app
        assertFalse(o.isNew(NFLX));
    }

    @Test public void movedOrHiddenActionReelIsNotReinsertedAfterRestart() {
        AppOrder o = AppOrder.restore("cz.tvminimal.home", String.join("\n", TV, IVYS, NFLX), null, BUILTINS);
        Map<String, String> inst = installed(TV, IVYS, NFLX, AR);
        o.merge(inst);
        assertEquals(2, o.move(AR, 2, inst));
        AppOrder moved = AppOrder.restore("cz.tvminimal.home", o.serializeOrder(), o.serializeHidden(), BUILTINS);
        assertEquals(Arrays.asList(TV, IVYS, AR, NFLX), moved.merge(inst));
        assertFalse(moved.isNew(AR));

        moved.hide(AR);
        AppOrder hidden = AppOrder.restore("cz.tvminimal.home", moved.serializeOrder(), moved.serializeHidden(), BUILTINS);
        assertEquals(Arrays.asList(TV, IVYS, NFLX), hidden.merge(inst));
        assertEquals(Arrays.asList(AR), hidden.hiddenInstalled(inst));
    }

    @Test public void freshInstallAndResetPutActionReelFirstWithoutBadge() {
        AppOrder o = new AppOrder("cz.tvminimal.home", BUILTINS);
        Map<String, String> inst = installed(YT, NFLX, TV, AR, SETTINGS);
        assertEquals(Arrays.asList(AR, TV, NFLX, YT, SETTINGS), o.merge(inst));
        assertFalse(o.isNew(AR));
        o.move(AR, 3, inst);
        o.hide(AR);
        o.reset();
        assertEquals(Arrays.asList(AR, TV, NFLX, YT, SETTINGS), o.merge(inst));
    }
}
