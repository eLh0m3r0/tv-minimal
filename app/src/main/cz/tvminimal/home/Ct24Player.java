package cz.tvminimal.home;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;
import android.view.SurfaceView;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.exoplayer.DecoderCounters;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import cz.tvminimal.core.PlaybackMachine.PlayerError;
import java.io.PrintWriter;

/** Media3 wrapper (ČT24 and Action Reel): bounded buffers, audio focus, created on play and fully released on stop. */
final class Ct24Player {
    interface Listener {
        void onError(PlayerError e, String detail);

        void onPlayingChanged(boolean playing);

        void onAudioFocusLost();
    }

    private final Context ctx;
    private final SurfaceView surface;
    private final Listener listener;
    private ExoPlayer player;
    private String videoDecoder = "-";
    private String audioDecoder = "-";
    private long swapStartedAt = -1;
    long lastSwapGapMs = -1;
    long firstFrameAt = -1;
    long playRequestedAt = -1;
    long startupMs = -1;
    int errors;
    String lastError = "-";
    int created;

    Ct24Player(Context ctx, SurfaceView surface, Listener listener) {
        this.ctx = ctx;
        this.surface = surface;
        this.listener = listener;
    }

    boolean isPlaying() { return player != null && player.isPlaying(); }

    boolean exists() { return player != null; }

    void play(String url, boolean refresh) {
        if (player == null) create();
        long now = SystemClock.elapsedRealtime();
        if (refresh) swapStartedAt = now;
        else {
            playRequestedAt = now;
            startupMs = -1;
        }
        player.setMediaItem(MediaItem.fromUri(url));
        player.prepare();
        player.setPlayWhenReady(true);
        surface.setKeepScreenOn(true);
    }

    void seekToLiveEdge() {
        if (player == null) return;
        player.seekToDefaultPosition();
        player.prepare();
    }

    void release() {
        if (player == null) return;
        player.stop();
        player.release();
        player = null;
        surface.setKeepScreenOn(false);
        Log.i(App.TAG, "player released");
    }

    private void create() {
        created++;
        DefaultLoadControl load = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(15_000, 30_000, 2_500, 5_000)
                .setTargetBufferBytes(32 * 1024 * 1024)
                .setPrioritizeTimeOverSizeThresholds(false)
                .build();
        DefaultTrackSelector tracks = new DefaultTrackSelector(ctx);
        tracks.setParameters(tracks.buildUponParameters()
                .setMaxVideoSize(1920, 1080)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true) // no subtitle UI; Action Reel marks en-US DEFAULT=YES
                .setPreferredAudioLanguage("cs")
                .setPreferredAudioMimeType(MimeTypes.AUDIO_AAC)
                .setPreferredAudioRoleFlags(C.ROLE_FLAG_MAIN));
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent(Resolver.UA)
                .setConnectTimeoutMs(8_000)
                .setReadTimeoutMs(8_000)
                .setAllowCrossProtocolRedirects(false);
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build();
        player = new ExoPlayer.Builder(ctx)
                .setLoadControl(load)
                .setTrackSelector(tracks)
                .setMediaSourceFactory(new HlsMediaSource.Factory(http))
                .setAudioAttributes(attrs, /* handleAudioFocus= */ true)
                .setHandleAudioBecomingNoisy(true)
                .build();
        player.setVideoSurfaceView(surface);
        player.addListener(new Player.Listener() {
            @Override public void onIsPlayingChanged(boolean playing) {
                long now = SystemClock.elapsedRealtime();
                if (playing && swapStartedAt > 0) {
                    lastSwapGapMs = now - swapStartedAt;
                    swapStartedAt = -1;
                    Log.i(App.TAG, "refresh swap gap ms=" + lastSwapGapMs);
                }
                if (playing && startupMs < 0 && playRequestedAt > 0) {
                    startupMs = now - playRequestedAt;
                    Log.i(App.TAG, "startup ms=" + startupMs);
                }
                Log.i(App.TAG, "isPlaying=" + playing);
                listener.onPlayingChanged(playing);
            }

            @Override public void onPlayWhenReadyChanged(boolean pwr, int reason) {
                if (!pwr && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS) {
                    Log.i(App.TAG, "audio focus lost permanently");
                    listener.onAudioFocusLost();
                }
            }

            @Override public void onPlayerError(PlaybackException e) {
                errors++;
                PlayerError kind;
                String detail = e.getErrorCodeName();
                if (e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    kind = PlayerError.BEHIND_LIVE_WINDOW;
                } else if (e.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS) {
                    kind = PlayerError.HTTP_AUTH;
                    if (e.getCause() instanceof HttpDataSource.InvalidResponseCodeException) {
                        detail += " " + ((HttpDataSource.InvalidResponseCodeException) e.getCause()).responseCode;
                    }
                } else if (e.errorCode >= 2000 && e.errorCode < 3000) {
                    kind = PlayerError.NETWORK;
                } else {
                    kind = PlayerError.OTHER;
                }
                lastError = detail;
                Log.w(App.TAG, "player error " + kind + " " + detail);
                listener.onError(kind, detail);
            }

            @Override public void onRenderedFirstFrame() {
                firstFrameAt = SystemClock.elapsedRealtime();
                Log.i(App.TAG, "first frame rendered");
            }
        });
        player.addAnalyticsListener(new AnalyticsListener() {
            @Override public void onVideoDecoderInitialized(EventTime t, String name, long a, long b) {
                videoDecoder = name;
                Log.i(App.TAG, "video decoder " + name);
            }

            @Override public void onAudioDecoderInitialized(EventTime t, String name, long a, long b) {
                audioDecoder = name;
                Log.i(App.TAG, "audio decoder " + name);
            }
        });
    }

    void dump(PrintWriter w) {
        w.println("  player.exists=" + (player != null) + " created=" + created + " errors=" + errors + " lastError=" + lastError);
        w.println("  startupMs=" + startupMs + " lastSwapGapMs=" + lastSwapGapMs);
        if (player == null) return;
        w.println("  playbackState=" + player.getPlaybackState() + " isPlaying=" + player.isPlaying()
                + " pwr=" + player.getPlayWhenReady() + " suppression=" + player.getPlaybackSuppressionReason());
        w.println("  liveOffsetMs=" + player.getCurrentLiveOffset() + " bufferedMs=" + player.getTotalBufferedDuration()
                + " volume=" + player.getVolume());
        Format v = player.getVideoFormat();
        Format a = player.getAudioFormat();
        w.println("  video=" + (v == null ? "-" : v.width + "x" + v.height + " " + v.codecs + " br=" + v.bitrate)
                + " decoder=" + videoDecoder);
        w.println("  audio=" + (a == null ? "-" : a.sampleMimeType + " " + a.channelCount + "ch " + a.sampleRate + "Hz lang=" + a.language)
                + " decoder=" + audioDecoder);
        DecoderCounters vc = player.getVideoDecoderCounters();
        if (vc != null) {
            vc.ensureUpdated();
            w.println("  videoFrames rendered=" + vc.renderedOutputBufferCount + " dropped=" + vc.droppedBufferCount
                    + " skipped=" + vc.skippedOutputBufferCount);
        }
        DecoderCounters ac = player.getAudioDecoderCounters();
        if (ac != null) {
            ac.ensureUpdated();
            w.println("  audioBuffers rendered=" + ac.renderedOutputBufferCount);
        }
    }
}
