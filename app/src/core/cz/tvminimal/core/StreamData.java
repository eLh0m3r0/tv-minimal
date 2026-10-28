package cz.tvminimal.core;

import org.json.JSONObject;

/**
 * Parser for the public ČT live stream-data endpoint (the one the official ct24 web player calls).
 * Respects isBlocked and DRM-only flags; never tries to work around them.
 */
public final class StreamData {
    /** Used when the URL carries no usable expiry: refresh well before the observed ~1 h token lifetime. */
    public static final long DEFAULT_LIFETIME_MS = 45 * 60_000L;

    public enum Status { OK, BLOCKED, DRM, API_ERROR, TRANSIENT }

    public static final class Result {
        public final Status status;
        public final String url;
        public final long expiryMs;

        Result(Status s, String u, long e) { status = s; url = u; expiryMs = e; }

        public static Result ok(String url, long expiryMs) { return new Result(Status.OK, url, expiryMs); }

        public static Result of(Status s) { return new Result(s, null, 0); }

        @Override public String toString() { return status + (url == null ? "" : " " + Redact.url(url)); }
    }

    private StreamData() { }

    /** @param http HTTP status, or -1 for an I/O failure / timeout. */
    public static Result parse(int http, String body, long nowMs) {
        if (http < 0 || http == 408 || http == 429 || http >= 500) return Result.of(Status.TRANSIENT);
        if (http == 451) return Result.of(Status.BLOCKED);
        if (http != 200 || body == null || body.isEmpty()) return Result.of(Status.API_ERROR);
        JSONObject streams;
        try {
            streams = new JSONObject(body).optJSONObject("streamUrls");
        } catch (Exception e) {
            return Result.of(Status.API_ERROR);
        }
        if (streams == null) return Result.of(Status.API_ERROR);
        if (streams.optBoolean("isBlocked", false)) return Result.of(Status.BLOCKED);
        String main = streams.isNull("main") ? null : streams.optString("main", null);
        if (main == null || !main.startsWith("https://")) return Result.of(Status.API_ERROR);
        if ("true".equals(queryParam(main, "drmOnly"))) return Result.of(Status.DRM);
        long expiry = DEFAULT_LIFETIME_MS + nowMs;
        String exp = queryParam(main, "expiry");
        if (exp != null) {
            try {
                long e = Long.parseLong(exp) * 1000L;
                if (e > nowMs) expiry = e;
            } catch (NumberFormatException ignored) { }
        }
        return Result.ok(main, expiry);
    }

    static String queryParam(String url, String key) {
        int q = url.indexOf('?');
        if (q < 0) return null;
        for (String part : url.substring(q + 1).split("&")) {
            int eq = part.indexOf('=');
            if (eq > 0 && part.substring(0, eq).equals(key)) return part.substring(eq + 1);
        }
        return null;
    }

    /** Test hook only: flips the last character of the token so the CDN rejects it (forces the error/retry path). */
    public static String corruptToken(String url) {
        int t = url.indexOf("token=");
        if (t < 0) return url;
        int end = url.indexOf('&', t);
        if (end < 0) end = url.length();
        if (end <= t + 6) return url;
        char last = url.charAt(end - 1);
        return url.substring(0, end - 1) + (last == 'z' ? 'y' : 'z') + url.substring(end);
    }
}
