package cz.tvminimal.core;

import static org.junit.Assert.*;

import org.junit.Test;

/** HomeActivity is exported (HOME/launcher), so any installed app can send it extras. */
public class IntentHooksTest {
    static final String[] TEST_HOOKS = {"tvm_test_refresh_s", "tvm_test_corrupt_token", "tvm_test_fail_resolve", "tvm_test_forget_boot"};

    @Test public void releaseBuildIgnoresAllTestHooks() {
        for (String h : TEST_HOOKS) assertFalse(h, IntentHooks.allowed(h, false));
    }

    @Test public void debuggableBuildKeepsTestHooksForOnDeviceTests() {
        for (String h : TEST_HOOKS) assertTrue(h, IntentHooks.allowed(h, true));
    }

    @Test public void harmlessHooksStayInRelease() { // play = default source only when idle; stop = what any app launch does
        assertTrue(IntentHooks.allowed("tvm_play", false));
        assertTrue(IntentHooks.allowed("tvm_stop", false));
        assertTrue(IntentHooks.allowed("tvm_via", false));
    }

    @Test public void unknownExtrasAreIgnored() {
        assertFalse(IntentHooks.allowed("tvm_source", false));
        assertFalse(IntentHooks.allowed("tvm_test_other", true));
        assertFalse(IntentHooks.allowed("url", true));
    }
}
