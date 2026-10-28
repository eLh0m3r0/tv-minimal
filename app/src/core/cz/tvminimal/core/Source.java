package cz.tvminimal.core;

/**
 * Video sources behind the grid. ČT24 needs the stream-data resolver and token refresh; Action Reel is a fixed
 * public Red Bull TV master playlist (no token, no DRM, 720p max) played as-is: no resolver, no refresh.
 */
public enum Source {
    CT24(null),
    ACTION_REEL("https://play.redbull.com/main/v1/rbtv/en/cz/personal_computer/http/"
            + "rrn:content:video-channels:69a66f02-21fd-42a1-be5b-6965541cfe6a.m3u8?device_group=group_5");

    /** Boot, wake and the play hook always use this; it is never persisted or changed by tile choice. */
    public static final Source DEFAULT = CT24;

    /** Fixed media URL, or null when the source must be resolved. */
    public final String fixedUrl;

    Source(String fixedUrl) { this.fixedUrl = fixedUrl; }

    public boolean needsResolve() { return fixedUrl == null; }
}
