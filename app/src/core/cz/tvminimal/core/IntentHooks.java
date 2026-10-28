package cz.tvminimal.core;

import java.util.Arrays;
import java.util.List;

/**
 * Which intent extras HomeActivity honours. HomeActivity must be exported (HOME/launcher), so any installed app
 * can send extras. Release builds keep only harmless hooks; the tvm_test_* hooks (forced refresh every 20 s,
 * forced resolve failures, corrupt token, boot-gate reset) work only in a debuggable build.
 */
public final class IntentHooks {
    static final List<String> ALWAYS = Arrays.asList("tvm_via", "tvm_play", "tvm_stop");
    static final List<String> DEBUG_ONLY = Arrays.asList(
            "tvm_test_refresh_s", "tvm_test_corrupt_token", "tvm_test_fail_resolve", "tvm_test_forget_boot");

    private IntentHooks() { }

    public static boolean allowed(String extra, boolean debuggable) {
        return ALWAYS.contains(extra) || (debuggable && DEBUG_ONLY.contains(extra));
    }
}
