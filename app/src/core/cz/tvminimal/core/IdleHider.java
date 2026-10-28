package cz.tvminimal.core;

/** Grid auto-hide: after {@code timeoutMs} without a key while video plays. A key while hidden only reveals. */
public final class IdleHider {
    private final long timeoutMs;
    private long lastActivity;
    private boolean hidden;

    public IdleHider(long timeoutMs) { this.timeoutMs = timeoutMs; }

    public void onShown(long now) {
        hidden = false;
        lastActivity = now;
    }

    public void onHidden() { hidden = true; }

    public boolean isHidden() { return hidden; }

    /** @return true if the key must be consumed because it only revealed the grid. */
    public boolean onKey(long now) {
        boolean wasHidden = hidden;
        onShown(now);
        return wasHidden;
    }

    public boolean shouldHide(long now, boolean videoPlaying) {
        return !hidden && videoPlaying && now - lastActivity >= timeoutMs;
    }

    public long delayUntilHide(long now) { return Math.max(0, lastActivity + timeoutMs - now); }
}
