package cz.tvminimal.core;

import static org.junit.Assert.*;

import org.junit.Test;

public class IdleHiderTest {
    @Test public void hidesAfterTimeoutOnlyWhileVideoPlays() {
        IdleHider h = new IdleHider(10_000);
        h.onShown(0);
        assertFalse(h.shouldHide(9_999, true));
        assertTrue(h.shouldHide(10_000, true));
        assertFalse(h.shouldHide(60_000, false)); // no video: never hide into black
    }

    @Test public void keyResetsTimer() {
        IdleHider h = new IdleHider(10_000);
        h.onShown(0);
        assertFalse(h.onKey(8_000));
        assertFalse(h.shouldHide(17_999, true));
        assertTrue(h.shouldHide(18_000, true));
    }

    @Test public void keyWhileHiddenRevealsAndIsConsumed() {
        IdleHider h = new IdleHider(10_000);
        h.onShown(0);
        h.onHidden();
        assertTrue(h.isHidden());
        assertTrue(h.onKey(20_000)); // consumed: just reveal, no action on the tile
        assertFalse(h.isHidden());
        assertFalse(h.shouldHide(29_000, true));
    }

    @Test public void delayUntilHideIsNeverNegative() {
        IdleHider h = new IdleHider(10_000);
        h.onShown(0);
        assertEquals(4_000, h.delayUntilHide(6_000));
        assertEquals(0, h.delayUntilHide(16_000));
    }
}
