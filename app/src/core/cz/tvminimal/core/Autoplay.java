package cz.tvminimal.core;

/**
 * Decides whether ČT24 may start by itself.
 *
 * <p>Boot: at most once per boot identity, persisted by the caller before playback starts, so process
 * restarts and Home presses in the same boot never retrigger. Wake: only the first visibility right after
 * a screen-off/screen-on cycle. Everything else (Home, Back, return from app) never autoplays.
 */
public final class Autoplay {
    public enum Mode {
        OFF, BOOT_ONLY, BOOT_AND_WAKE, ALWAYS;

        public static Mode parse(String s) {
            if (s != null) {
                for (Mode m : values()) if (m.name().equals(s)) return m;
            }
            return ALWAYS;
        }
    }

    public static final class Decision {
        public final boolean play;
        /** Boot identity to persist (null = keep stored value). */
        public final String storeBootId;
        public final String reason;
        /** Source to start when {@link #play}; null otherwise. */
        public final Source source;

        Decision(boolean p, String s, String reason) {
            play = p;
            storeBootId = s;
            this.reason = reason;
            source = p ? Source.DEFAULT : null; // autoplay is ČT24 only, whatever tile was used last
        }
    }

    private Autoplay() { }

    public static Decision decide(Mode mode, String bootId, String storedBootId, boolean isWake) {
        if (bootId != null && !bootId.equals(storedBootId)) {
            return new Decision(mode != Mode.OFF, bootId, "boot");
        }
        if (isWake) return new Decision(mode == Mode.BOOT_AND_WAKE || mode == Mode.ALWAYS, null, "wake");
        return new Decision(false, null, bootId == null ? "no-boot-id" : "same-boot");
    }

    /** ALWAYS: on screen-on, pull the launcher over whatever app was resumed, so the wake gate can play ČT24. */
    public static boolean bringToFrontOnScreenOn(Mode mode, boolean launcherVisible) {
        return mode == Mode.ALWAYS && !launcherVisible;
    }

    /** Kernel boot_id when readable, else Settings.Global.BOOT_COUNT, else unknown (null). */
    public static String bootIdentity(String kernelBootId, int bootCount) {
        if (kernelBootId != null && !kernelBootId.trim().isEmpty()) return "bid:" + kernelBootId.trim();
        if (bootCount >= 0) return "bc:" + bootCount;
        return null;
    }

    /** Screen-off marker consumed by the first interactive visibility shortly after screen-on. */
    public static final class WakeDetector {
        public static final long WINDOW_MS = 12_000L;
        private boolean offSeen;
        private long lastOff = -1;
        private long lastOn = -1;

        public synchronized void onScreenOff(long t) {
            offSeen = true;
            lastOff = t;
        }

        public synchronized void onScreenOn(long t) { lastOn = t; }

        public synchronized boolean consumeWake(long now, boolean interactive) {
            if (!interactive || !offSeen) return false;
            offSeen = false;
            if (lastOn < lastOff) return true; // SCREEN_ON broadcast not delivered yet, but we are interactive now
            return now - lastOn <= WINDOW_MS;
        }

        public synchronized String describe() {
            return "offSeen=" + offSeen + " lastOff=" + lastOff + " lastOn=" + lastOn;
        }
    }
}
