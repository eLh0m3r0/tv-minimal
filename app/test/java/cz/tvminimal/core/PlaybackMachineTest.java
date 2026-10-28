package cz.tvminimal.core;

import static org.junit.Assert.*;

import cz.tvminimal.core.PlaybackMachine.Failure;
import cz.tvminimal.core.PlaybackMachine.PlayerError;
import cz.tvminimal.core.PlaybackMachine.State;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

public class PlaybackMachineTest {
    long now = 1_000_000L;
    final List<String> log = new ArrayList<>();
    boolean network = true;
    int lastGen = -1;
    long refreshAt = -1;
    PlaybackMachine m;

    final PlaybackMachine.Effects fx = new PlaybackMachine.Effects() {
        @Override public boolean networkAvailable() { return network; }
        @Override public void resolve(int gen) { lastGen = gen; log.add("resolve"); }
        @Override public void play(String url, boolean refresh) { log.add((refresh ? "swap:" : "play:") + url); }
        @Override public void seekToLiveEdge() { log.add("liveEdge"); }
        @Override public void releasePlayer() { log.add("release"); }
        @Override public void scheduleRefresh(long atMs) { refreshAt = atMs; log.add("refreshAt:" + atMs); }
        @Override public void scheduleNetworkTimeout(long atMs) { log.add("netTimeoutAt:" + atMs); }
        @Override public void cancelTimers() { refreshAt = -1; log.add("cancel"); }
        @Override public void showFailure(Failure f) { log.add("fail:" + f); }
        @Override public void stateChanged(State s) { }
    };

    StreamData.Result ok(String url, long expiryMs) { return StreamData.Result.ok(url, expiryMs); }

    @Before public void setUp() { m = new PlaybackMachine(fx, () -> now); }

    @Test public void startResolvesThenPlaysAndSchedulesRefreshBeforeExpiry() {
        m.start(Source.CT24);
        assertEquals(State.RESOLVING, m.state());
        m.onResolved(lastGen, ok("u1", now + 3_600_000));
        assertEquals(State.PLAYING, m.state());
        assertTrue(log.contains("play:u1"));
        assertEquals(now + 3_600_000 - PlaybackMachine.REFRESH_LEAD_MS, refreshAt);
    }

    @Test public void startWhileActiveIsIdempotent() { // Home must not restart playback
        m.start(Source.CT24);
        m.onResolved(lastGen, ok("u1", now + 3_600_000));
        log.clear();
        m.start(Source.CT24);
        assertEquals(State.PLAYING, m.state());
        assertTrue(log.isEmpty());
    }

    @Test public void refreshSwapsUrlWithoutFailure() {
        m.start(Source.CT24);
        m.onResolved(lastGen, ok("u1", now + 3_600_000));
        now = refreshAt;
        m.onRefreshDue();
        assertEquals(State.REFRESHING, m.state());
        m.onResolved(lastGen, ok("u2", now + 3_600_000));
        assertEquals(State.PLAYING, m.state());
        assertTrue(log.contains("swap:u2"));
        assertFalse(log.toString().contains("fail"));
    }

    @Test public void failedRefreshKeepsPlayingAndRetriesLater() {
        m.start(Source.CT24);
        m.onResolved(lastGen, ok("u1", now + 3_600_000));
        now = refreshAt;
        m.onRefreshDue();
        m.onResolved(lastGen, StreamData.Result.of(StreamData.Status.TRANSIENT));
        assertEquals(State.PLAYING, m.state());
        assertEquals(now + PlaybackMachine.REFRESH_RETRY_MS, refreshAt);
    }

    @Test public void blockedDuringRefreshStopsPlayback() {
        m.start(Source.CT24);
        m.onResolved(lastGen, ok("u1", now + 3_600_000));
        m.onRefreshDue();
        m.onResolved(lastGen, StreamData.Result.of(StreamData.Status.BLOCKED));
        assertEquals(State.FAILED, m.state());
        assertTrue(log.contains("release"));
        assertTrue(log.contains("fail:BLOCKED"));
    }

    @Test public void shortRefreshLeadIsClampedToMinimumDelay() {
        m.start(Source.CT24);
        m.onResolved(lastGen, ok("u1", now + 60_000)); // expiry sooner than lead
        assertEquals(now + PlaybackMachine.MIN_REFRESH_DELAY_MS, refreshAt);
    }

    @Test public void testRefreshOverrideIsBounded() {
        m.setTestRefreshAfterMs(1_000); // below floor
        m.start(Source.CT24);
        m.onResolved(lastGen, ok("u1", now + 3_600_000));
        assertEquals(now + PlaybackMachine.MIN_REFRESH_DELAY_MS, refreshAt);
    }

    @Test public void playerHttpErrorGetsExactlyOneRetryThenFails() {
        m.start(Source.CT24);
        m.onResolved(lastGen, ok("u1", now + 3_600_000));
        m.onPlayerError(PlayerError.HTTP_AUTH);
        assertEquals(State.RETRYING, m.state());
        m.onResolved(lastGen, ok("u2", now + 3_600_000));
        assertEquals(State.PLAYING, m.state());
        now += 5_000; // fails again quickly: no second retry
        m.onPlayerError(PlayerError.HTTP_AUTH);
        assertEquals(State.FAILED, m.state());
        assertTrue(log.contains("fail:PLAYBACK"));
        assertTrue(log.contains("release"));
    }

    @Test public void retryBudgetRefillsAfterStablePlayback() {
        m.start(Source.CT24);
        m.onResolved(lastGen, ok("u1", now + 3_600_000));
        m.onPlayerError(PlayerError.NETWORK);
        m.onResolved(lastGen, ok("u2", now + 3_600_000));
        now += PlaybackMachine.STABLE_MS;
        m.onPlayerError(PlayerError.NETWORK);
        assertEquals(State.RETRYING, m.state());
    }

    @Test public void behindLiveWindowSeeksWithoutUsingRetry() {
        m.start(Source.CT24);
        m.onResolved(lastGen, ok("u1", now + 3_600_000));
        m.onPlayerError(PlayerError.BEHIND_LIVE_WINDOW);
        assertEquals(State.PLAYING, m.state());
        assertTrue(log.contains("liveEdge"));
        m.onPlayerError(PlayerError.HTTP_AUTH);
        assertEquals(State.RETRYING, m.state());
    }

    @Test public void initialTransientResolveErrorRetriesOnceThenFails() {
        m.start(Source.CT24);
        m.onResolved(lastGen, StreamData.Result.of(StreamData.Status.TRANSIENT));
        assertEquals(State.RETRYING, m.state());
        m.onResolved(lastGen, StreamData.Result.of(StreamData.Status.TRANSIENT));
        assertEquals(State.FAILED, m.state());
        assertTrue(log.contains("fail:NETWORK"));
    }

    @Test public void blockedAndDrmAndApiErrorsFailWithoutRetry() {
        for (StreamData.Status s : new StreamData.Status[] {StreamData.Status.BLOCKED, StreamData.Status.DRM, StreamData.Status.API_ERROR}) {
            m = new PlaybackMachine(fx, () -> now);
            log.clear();
            m.start(Source.CT24);
            m.onResolved(lastGen, StreamData.Result.of(s));
            assertEquals(s.toString(), State.FAILED, m.state());
            assertEquals(1, log.stream().filter("resolve"::equals).count());
        }
    }

    @Test public void staleResolveAfterStopIsIgnored() {
        m.start(Source.CT24);
        int gen = lastGen;
        m.stop();
        assertEquals(State.IDLE, m.state());
        log.clear();
        m.onResolved(gen, ok("u1", now + 3_600_000));
        assertEquals(State.IDLE, m.state());
        assertTrue(log.isEmpty());
    }

    @Test public void stopReleasesAndCancelsTimers() {
        m.start(Source.CT24);
        m.onResolved(lastGen, ok("u1", now + 3_600_000));
        m.stop();
        assertEquals(State.IDLE, m.state());
        assertTrue(log.contains("release"));
        assertTrue(log.contains("cancel"));
        log.clear();
        m.onRefreshDue();
        m.onPlayerError(PlayerError.NETWORK);
        assertTrue(log.isEmpty());
    }

    @Test public void waitsForNetworkThenResolvesOrTimesOut() {
        network = false;
        m.start(Source.CT24);
        assertEquals(State.WAITING_NETWORK, m.state());
        assertTrue(log.contains("netTimeoutAt:" + (now + PlaybackMachine.NETWORK_WAIT_MS)));
        network = true;
        m.onNetworkAvailable();
        assertEquals(State.RESOLVING, m.state());

        m.stop();
        network = false;
        m.start(Source.CT24);
        m.onNetworkTimeout();
        assertEquals(State.FAILED, m.state());
        assertTrue(log.contains("fail:NETWORK"));
    }

    // ---------------------------------------------------------------- sources (Action Reel)

    static final String AR = Source.ACTION_REEL.fixedUrl;

    @Test public void defaultSourceIsCt24() {
        assertSame(Source.CT24, Source.DEFAULT);
        assertSame(Source.CT24, m.source());
    }

    @Test public void actionReelUrlIsFixedHttpsWithoutToken() {
        assertTrue(AR.startsWith("https://play.redbull.com/"));
        assertFalse(AR.contains("token") || AR.contains("hdnts") || AR.contains("expiry"));
        assertFalse(Source.ACTION_REEL.needsResolve());
        assertTrue(Source.CT24.needsResolve());
    }

    @Test public void actionReelPlaysFixedUrlWithoutResolveOrRefresh() {
        m.start(Source.ACTION_REEL);
        assertEquals(State.PLAYING, m.state());
        assertSame(Source.ACTION_REEL, m.source());
        assertEquals(List.of("play:" + AR), log);
        assertEquals(-1, refreshAt);
        assertEquals(0, m.expiryMs());
    }

    @Test public void refreshTimerAndTestRefreshNeverTouchActionReel() {
        m.setTestRefreshAfterMs(25_000);
        m.start(Source.ACTION_REEL);
        log.clear();
        m.onRefreshDue(); // e.g. a stale ČT24 timer firing late
        assertEquals(State.PLAYING, m.state());
        assertTrue(log.isEmpty());
        assertEquals(0, m.refreshCount());
    }

    @Test public void switchingCt24ToActionReelReleasesFirstAndIgnoresStaleCt24Results() {
        m.start(Source.CT24);
        int ct24Gen = lastGen; // ČT24 resolve still in flight
        log.clear();
        m.start(Source.ACTION_REEL);
        assertEquals(List.of("cancel", "release", "play:" + AR), log);
        log.clear();
        m.onResolved(ct24Gen, ok("ct24url", now + 3_600_000));
        assertTrue(log.isEmpty());
        assertSame(Source.ACTION_REEL, m.source());
    }

    @Test public void switchingActionReelToCt24ReleasesThenResolves() {
        m.start(Source.ACTION_REEL);
        log.clear();
        m.start(Source.CT24);
        assertEquals(List.of("cancel", "release", "resolve"), log);
        m.onResolved(lastGen, ok("u1", now + 3_600_000));
        assertTrue(log.contains("play:u1"));
        assertSame(Source.CT24, m.source());
    }

    @Test public void homeAndPlayHookKeepRunningActionReel() { // Home never switches or restarts
        m.start(Source.ACTION_REEL);
        log.clear();
        m.start(Source.ACTION_REEL); // same tile again
        m.playDefaultIfIdle(); // tvm_play hook
        assertTrue(log.isEmpty());
        assertSame(Source.ACTION_REEL, m.source());
        assertEquals(State.PLAYING, m.state());
    }

    @Test public void playHookWhenIdleStartsCt24EvenAfterActionReel() { // wake/boot path after onStop
        m.start(Source.ACTION_REEL);
        m.stop();
        log.clear();
        m.playDefaultIfIdle();
        assertSame(Source.CT24, m.source());
        assertEquals(List.of("resolve"), log);
    }

    @Test public void actionReelErrorRetriesSameUrlOnceThenFailsWithoutSwitching() {
        m.start(Source.ACTION_REEL);
        log.clear();
        m.onPlayerError(PlayerError.HTTP_AUTH);
        assertEquals(List.of("play:" + AR), log); // bounded retry: same fixed URL, no resolver
        assertEquals(State.PLAYING, m.state());
        now += 5_000;
        m.onPlayerError(PlayerError.NETWORK);
        assertEquals(State.FAILED, m.state());
        assertEquals(Failure.PLAYBACK, m.lastFailure());
        assertSame(Source.ACTION_REEL, m.source()); // failure UI knows which source failed
        assertFalse(log.contains("resolve")); // never falls back to ČT24
        assertEquals(1, log.stream().filter(s -> s.startsWith("play:")).count());
    }

    @Test public void actionReelRetryBudgetRefillsAfterStablePlayback() {
        m.start(Source.ACTION_REEL);
        m.onPlayerError(PlayerError.NETWORK);
        now += PlaybackMachine.STABLE_MS;
        log.clear();
        m.onPlayerError(PlayerError.NETWORK);
        assertEquals(State.PLAYING, m.state());
        assertEquals(List.of("play:" + AR), log);
    }

    @Test public void actionReelWaitsForNetworkThenPlaysDirectlyOrFails() {
        network = false;
        m.start(Source.ACTION_REEL);
        assertEquals(State.WAITING_NETWORK, m.state());
        m.onNetworkAvailable();
        assertEquals(State.PLAYING, m.state());
        assertTrue(log.contains("play:" + AR));
        assertFalse(log.contains("resolve"));

        m.stop();
        m.start(Source.ACTION_REEL);
        m.onNetworkTimeout();
        assertEquals(State.FAILED, m.state());
        assertEquals(Failure.NETWORK, m.lastFailure());
        assertSame(Source.ACTION_REEL, m.source());
    }

    @Test public void userRetryAfterActionReelFailureRetriesActionReel() {
        m.start(Source.ACTION_REEL);
        m.onPlayerError(PlayerError.OTHER);
        m.onPlayerError(PlayerError.OTHER);
        assertEquals(State.FAILED, m.state());
        log.clear();
        m.stop();
        m.start(m.source()); // Retry button
        assertEquals(State.PLAYING, m.state());
        assertTrue(log.contains("play:" + AR));
    }

    @Test public void userRetryFromFailedStartsFresh() {
        m.start(Source.CT24);
        m.onResolved(lastGen, StreamData.Result.of(StreamData.Status.API_ERROR));
        assertEquals(State.FAILED, m.state());
        m.start(Source.CT24);
        assertEquals(State.RESOLVING, m.state());
    }
}
