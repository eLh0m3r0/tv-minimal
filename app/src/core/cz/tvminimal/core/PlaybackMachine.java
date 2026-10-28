package cz.tvminimal.core;

import java.util.function.LongSupplier;

/**
 * Playback state machine for one {@link Source} at a time. Pure logic; all I/O goes through {@link Effects}.
 * ČT24 follows the diagram below. A fixed-URL source (Action Reel) skips RESOLVING/REFRESHING entirely: it goes
 * straight to PLAYING, a player error re-prepares the same URL once per stable period, then FAILED. Starting a
 * different source while active stops (releases) the current one first; nothing ever switches source by itself.
 *
 * <pre>
 * IDLE --start--> WAITING_NETWORK --net--> RESOLVING --ok--> PLAYING --refreshDue--> REFRESHING --ok--> PLAYING
 *                       |timeout                |blocked/drm/api -> FAILED (no retry)
 *                       v                       |transient -> RETRYING (once) -> FAILED
 *                     FAILED          PLAYING --player error--> RETRYING (once per stable period) -> FAILED
 * any --stop--> IDLE (player released, timers cancelled, in-flight results ignored via generation)
 * </pre>
 */
public final class PlaybackMachine {
    public static final long REFRESH_LEAD_MS = 5 * 60_000L;
    public static final long REFRESH_RETRY_MS = 60_000L;
    public static final long MIN_REFRESH_DELAY_MS = 20_000L;
    public static final long STABLE_MS = 60_000L;
    public static final long NETWORK_WAIT_MS = 30_000L;

    public enum State { IDLE, WAITING_NETWORK, RESOLVING, PLAYING, REFRESHING, RETRYING, FAILED }

    public enum Failure { BLOCKED, DRM, API, NETWORK, PLAYBACK }

    public enum PlayerError { HTTP_AUTH, BEHIND_LIVE_WINDOW, NETWORK, OTHER }

    public interface Effects {
        boolean networkAvailable();

        /** Start an async resolve; the result must come back through {@link #onResolved(int, StreamData.Result)}. */
        void resolve(int gen);

        /** Start (refresh=false) or swap (refresh=true) the media URL. */
        void play(String url, boolean refresh);

        void seekToLiveEdge();

        void releasePlayer();

        void scheduleRefresh(long atMs);

        void scheduleNetworkTimeout(long atMs);

        void cancelTimers();

        void showFailure(Failure f);

        void stateChanged(State s);
    }

    private final Effects fx;
    private final LongSupplier clock;
    private State state = State.IDLE;
    private Source source = Source.DEFAULT;
    private int gen;
    private boolean retryUsed;
    private long playingSince;
    private long expiryMs;
    private long testRefreshAfterMs = -1;
    private int refreshCount;
    private Failure lastFailure;

    public PlaybackMachine(Effects fx, LongSupplier clock) {
        this.fx = fx;
        this.clock = clock;
    }

    public State state() { return state; }

    public int refreshCount() { return refreshCount; }

    public long expiryMs() { return expiryMs; }

    public Failure lastFailure() { return lastFailure; }

    public boolean isActive() {
        return state == State.WAITING_NETWORK || state == State.RESOLVING || state == State.PLAYING
                || state == State.REFRESHING || state == State.RETRYING;
    }

    /** Test hook: refresh N ms after (re)start instead of shortly before expiry. Floored at MIN_REFRESH_DELAY_MS. */
    public void setTestRefreshAfterMs(long ms) { testRefreshAfterMs = ms; }

    /** Current or last played source (kept after stop/failure so Retry and the failure UI know it). */
    public Source source() { return source; }

    /** Same source while active: no-op (Home / repeated starts never restart). Other source: release first, then start. */
    public void start(Source s) {
        if (isActive()) {
            if (s == source) return;
            stop();
        }
        source = s;
        gen++;
        retryUsed = false;
        lastFailure = null;
        expiryMs = 0;
        if (!fx.networkAvailable()) {
            set(State.WAITING_NETWORK);
            fx.scheduleNetworkTimeout(clock.getAsLong() + NETWORK_WAIT_MS);
            return;
        }
        begin();
    }

    /** Play hook: starts the default source only when nothing is running; never switches a running source. */
    public void playDefaultIfIdle() {
        if (!isActive()) start(Source.DEFAULT);
    }

    private void begin() {
        if (source.needsResolve()) {
            set(State.RESOLVING);
            fx.resolve(gen);
        } else { // fixed URL: no resolver, no expiry, no refresh timer
            playingSince = clock.getAsLong();
            set(State.PLAYING);
            fx.play(source.fixedUrl, false);
        }
    }

    public void stop() {
        gen++;
        fx.cancelTimers();
        if (state != State.IDLE) {
            fx.releasePlayer();
            set(State.IDLE);
        }
    }

    public void onNetworkAvailable() {
        if (state != State.WAITING_NETWORK) return;
        fx.cancelTimers();
        begin();
    }

    public void onNetworkTimeout() {
        if (state == State.WAITING_NETWORK) fail(Failure.NETWORK);
    }

    public void onResolved(int g, StreamData.Result r) {
        if (g != gen) return; // stale (stopped or superseded)
        if (state != State.RESOLVING && state != State.REFRESHING && state != State.RETRYING) return;
        long now = clock.getAsLong();
        switch (r.status) {
            case OK:
                boolean refresh = state == State.REFRESHING;
                if (refresh) refreshCount++;
                else playingSince = now;
                expiryMs = r.expiryMs;
                set(State.PLAYING);
                fx.play(r.url, refresh);
                scheduleRefreshFor(now, r.expiryMs);
                return;
            case BLOCKED:
                fail(Failure.BLOCKED);
                return;
            case DRM:
                fail(Failure.DRM);
                return;
            case API_ERROR:
                if (state == State.REFRESHING) { // old URL still valid; try again shortly
                    set(State.PLAYING);
                    fx.scheduleRefresh(now + REFRESH_RETRY_MS);
                } else {
                    fail(Failure.API);
                }
                return;
            case TRANSIENT:
            default:
                if (state == State.REFRESHING) {
                    set(State.PLAYING);
                    fx.scheduleRefresh(now + REFRESH_RETRY_MS);
                } else if (state == State.RESOLVING && !retryUsed) {
                    retryUsed = true;
                    gen++;
                    set(State.RETRYING);
                    fx.resolve(gen);
                } else {
                    fail(Failure.NETWORK);
                }
        }
    }

    public void onRefreshDue() {
        if (state != State.PLAYING || !source.needsResolve()) return;
        gen++;
        set(State.REFRESHING);
        fx.resolve(gen);
    }

    public void onPlayerError(PlayerError e) {
        if (state != State.PLAYING && state != State.REFRESHING) return;
        if (e == PlayerError.BEHIND_LIVE_WINDOW) {
            fx.seekToLiveEdge();
            return;
        }
        long now = clock.getAsLong();
        boolean canRetry = !retryUsed || now - playingSince >= STABLE_MS;
        if (!canRetry) {
            fail(Failure.PLAYBACK);
            return;
        }
        retryUsed = true;
        if (!source.needsResolve()) { // one re-prepare of the same fixed URL (it has no timers); never another source
            playingSince = now;
            fx.play(source.fixedUrl, false);
            return;
        }
        gen++;
        fx.cancelTimers();
        set(State.RETRYING);
        fx.resolve(gen); // fresh resolve: new token handles 401/403/412 after expiry
    }

    private void scheduleRefreshFor(long now, long expiry) {
        long at = testRefreshAfterMs > 0 ? now + testRefreshAfterMs : expiry - REFRESH_LEAD_MS;
        fx.scheduleRefresh(Math.max(at, now + MIN_REFRESH_DELAY_MS));
    }

    private void fail(Failure f) {
        gen++;
        lastFailure = f;
        fx.cancelTimers();
        fx.releasePlayer();
        set(State.FAILED);
        fx.showFailure(f);
    }

    private void set(State s) {
        if (state == s) return;
        state = s;
        fx.stateChanged(s);
    }
}
