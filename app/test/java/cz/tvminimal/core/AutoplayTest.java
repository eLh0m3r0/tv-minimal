package cz.tvminimal.core;

import static org.junit.Assert.*;

import cz.tvminimal.core.Autoplay.Decision;
import cz.tvminimal.core.Autoplay.Mode;
import org.junit.Test;

/** Boot-once gate, wake detection and the "Home never retriggers" rule. */
public class AutoplayTest {
    @Test public void firstVisibleInNewBootPlaysOnceAndStoresBoot() {
        Decision d = Autoplay.decide(Mode.BOOT_AND_WAKE, "bid:A", null, false);
        assertTrue(d.play);
        assertEquals("bid:A", d.storeBootId);
        Decision again = Autoplay.decide(Mode.BOOT_AND_WAKE, "bid:A", d.storeBootId, false);
        assertFalse(again.play); // Home / process restart in the same boot
    }

    @Test public void bootAndWakeAutoplayAlwaysChooseCt24() { // never the last tile (e.g. Action Reel)
        for (Mode mode : new Mode[] {Mode.ALWAYS, Mode.BOOT_AND_WAKE, Mode.BOOT_ONLY}) {
            assertSame(Source.CT24, Autoplay.decide(mode, "bid:B", "bid:A", false).source); // boot
        }
        assertSame(Source.CT24, Autoplay.decide(Mode.ALWAYS, "bid:A", "bid:A", true).source); // wake
        assertSame(Source.CT24, Autoplay.decide(Mode.BOOT_AND_WAKE, "bid:A", "bid:A", true).source);
        assertNull(Autoplay.decide(Mode.ALWAYS, "bid:A", "bid:A", false).source); // Home / return: nothing
        assertNull(Autoplay.decide(Mode.OFF, "bid:B", "bid:A", true).source);
    }

    @Test public void wakeAfterActionReelStartsCt24() { // onStop released Action Reel; wake decision drives the machine
        long[] t = {0};
        java.util.List<String> log = new java.util.ArrayList<>();
        PlaybackMachine m = new PlaybackMachine(new PlaybackMachine.Effects() {
            @Override public boolean networkAvailable() { return true; }
            @Override public void resolve(int gen) { log.add("resolve"); }
            @Override public void play(String url, boolean refresh) { log.add("play:" + url); }
            @Override public void seekToLiveEdge() { }
            @Override public void releasePlayer() { log.add("release"); }
            @Override public void scheduleRefresh(long atMs) { }
            @Override public void scheduleNetworkTimeout(long atMs) { }
            @Override public void cancelTimers() { }
            @Override public void showFailure(PlaybackMachine.Failure f) { }
            @Override public void stateChanged(PlaybackMachine.State s) { }
        }, () -> t[0]);
        m.start(Source.ACTION_REEL);
        m.stop(); // screen off -> onStop
        log.clear();
        Decision d = Autoplay.decide(Mode.ALWAYS, "bid:A", "bid:A", true);
        assertTrue(d.play);
        m.start(d.source);
        assertSame(Source.CT24, m.source());
        assertEquals(java.util.List.of("resolve"), log);
    }

    @Test public void newBootIdentityPlaysAgain() {
        assertTrue(Autoplay.decide(Mode.BOOT_ONLY, "bid:B", "bid:A", false).play);
    }

    @Test public void offStillConsumesTheBoot() {
        Decision d = Autoplay.decide(Mode.OFF, "bid:A", null, false);
        assertFalse(d.play);
        assertEquals("bid:A", d.storeBootId);
        assertFalse(Autoplay.decide(Mode.OFF, "bid:A", "bid:A", true).play);
    }

    @Test public void unknownBootIdentityNeverBootPlays() {
        Decision d = Autoplay.decide(Mode.BOOT_AND_WAKE, null, null, false);
        assertFalse(d.play);
        assertNull(d.storeBootId);
    }

    @Test public void wakePlaysOnlyWhenEnabled() {
        assertTrue(Autoplay.decide(Mode.BOOT_AND_WAKE, "bid:A", "bid:A", true).play);
        assertFalse(Autoplay.decide(Mode.BOOT_ONLY, "bid:A", "bid:A", true).play);
    }

    @Test public void modeParsingDefaultsToAlways() {
        assertEquals(Mode.ALWAYS, Mode.parse(null));
        assertEquals(Mode.ALWAYS, Mode.parse("garbage"));
        assertEquals(Mode.OFF, Mode.parse("OFF"));
        assertEquals(Mode.BOOT_AND_WAKE, Mode.parse("BOOT_AND_WAKE"));
    }

    @Test public void alwaysModePlaysOnWakeAndBoot() {
        assertTrue(Autoplay.decide(Mode.ALWAYS, "bid:A", "bid:A", true).play);
        assertTrue(Autoplay.decide(Mode.ALWAYS, "bid:B", "bid:A", false).play);
        assertFalse(Autoplay.decide(Mode.ALWAYS, "bid:A", "bid:A", false).play); // Home never retriggers
    }

    @Test public void onlyAlwaysModePullsLauncherToFrontOnScreenOn() {
        assertTrue(Autoplay.bringToFrontOnScreenOn(Mode.ALWAYS, false));
        assertFalse(Autoplay.bringToFrontOnScreenOn(Mode.ALWAYS, true)); // already visible: onStart handles it
        assertFalse(Autoplay.bringToFrontOnScreenOn(Mode.BOOT_AND_WAKE, false));
        assertFalse(Autoplay.bringToFrontOnScreenOn(Mode.BOOT_ONLY, false));
        assertFalse(Autoplay.bringToFrontOnScreenOn(Mode.OFF, false));
    }

    // ---- WakeDetector ----
    @Test public void homeWithoutScreenOffIsNotWake() {
        Autoplay.WakeDetector w = new Autoplay.WakeDetector();
        assertFalse(w.consumeWake(1000, true));
    }

    @Test public void screenOffThenOnThenVisibleIsWakeExactlyOnce() {
        Autoplay.WakeDetector w = new Autoplay.WakeDetector();
        w.onScreenOff(1000);
        w.onScreenOn(5000);
        assertTrue(w.consumeWake(7000, true));
        assertFalse(w.consumeWake(8000, true)); // subsequent Home
    }

    @Test public void visibleBeforeScreenOnBroadcastStillCountsAsWake() {
        Autoplay.WakeDetector w = new Autoplay.WakeDetector();
        w.onScreenOff(1000);
        assertTrue(w.consumeWake(9000, true));
    }

    @Test public void lateVisibleAfterWakeIsNotWake() { // woke into another app, Home pressed much later
        Autoplay.WakeDetector w = new Autoplay.WakeDetector();
        w.onScreenOff(1000);
        w.onScreenOn(5000);
        assertFalse(w.consumeWake(5000 + Autoplay.WakeDetector.WINDOW_MS + 1, true));
        assertFalse(w.consumeWake(5000 + Autoplay.WakeDetector.WINDOW_MS + 2, true));
    }

    @Test public void nonInteractiveVisibilityDoesNotConsume() {
        Autoplay.WakeDetector w = new Autoplay.WakeDetector();
        w.onScreenOff(1000);
        assertFalse(w.consumeWake(2000, false));
        w.onScreenOn(3000);
        assertTrue(w.consumeWake(4000, true));
    }

    @Test public void bootIdentityPrefersKernelBootId() {
        assertEquals("bid:0b1d2c3e-4f50-4a61-8b72-9c83d4e5f601",
                Autoplay.bootIdentity(" 0b1d2c3e-4f50-4a61-8b72-9c83d4e5f601\n", 22));
        assertEquals("bc:22", Autoplay.bootIdentity(null, 22));
        assertEquals("bc:22", Autoplay.bootIdentity("", 22));
        assertNull(Autoplay.bootIdentity(null, -1));
    }
}
