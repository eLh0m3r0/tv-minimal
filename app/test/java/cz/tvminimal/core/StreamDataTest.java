package cz.tvminimal.core;

import static org.junit.Assert.*;

import org.junit.Test;

/** Token/stream-data parser: blocking, DRM, errors, expiry, and redaction. */
public class StreamDataTest {
    static final long NOW = 1_800_000_000_000L;
    static final long EXP_S = 1_800_003_600L; // NOW + 3600 s

    static String body(String main, boolean blocked) {
        return "{\"id\":2402,\"title\":\"ČT24\",\"channel\":\"CH_24\",\"message\":null,"
                + "\"streamUrls\":{\"isBlocked\":" + blocked + ",\"main\":" + (main == null ? "null" : "\"" + main + "\"")
                + ",\"availableQualities\":[\"720p\"]}}";
    }

    static String url(String drmOnly) {
        return "https://ivys-nw-cdn.o2tv.cz/cdn/uri/get/?token=abcdef0123456789abcdef0123456789abcdef01&userId=u-1"
                + "&contentId=CH_24&streamType=hls&quality=1080p&contentType=live&region=1&expiry=" + EXP_S
                + "&drmOnly=" + drmOnly + "&qualityCount=5&skipIpAddressCheck=false";
    }

    @Test public void okResultCarriesUrlAndExpiry() {
        StreamData.Result r = StreamData.parse(200, body(url("false"), false), NOW);
        assertEquals(StreamData.Status.OK, r.status);
        assertEquals(url("false"), r.url);
        assertEquals(EXP_S * 1000L, r.expiryMs);
    }

    @Test public void blockedIsRespected() {
        StreamData.Result r = StreamData.parse(200, body(url("false"), true), NOW);
        assertEquals(StreamData.Status.BLOCKED, r.status);
        assertNull(r.url);
    }

    @Test public void drmOnlyIsNotPlayed() {
        StreamData.Result r = StreamData.parse(200, body(url("true"), false), NOW);
        assertEquals(StreamData.Status.DRM, r.status);
        assertNull(r.url);
    }

    @Test public void missingMainIsApiError() {
        assertEquals(StreamData.Status.API_ERROR, StreamData.parse(200, body(null, false), NOW).status);
    }

    @Test public void invalidJsonIsApiError() {
        assertEquals(StreamData.Status.API_ERROR, StreamData.parse(200, "<html>nope", NOW).status);
        assertEquals(StreamData.Status.API_ERROR, StreamData.parse(200, "", NOW).status);
        assertEquals(StreamData.Status.API_ERROR, StreamData.parse(200, null, NOW).status);
    }

    @Test public void httpErrorsAreClassified() {
        assertEquals(StreamData.Status.API_ERROR, StreamData.parse(404, "{}", NOW).status);
        assertEquals(StreamData.Status.BLOCKED, StreamData.parse(451, "{}", NOW).status);
        assertEquals(StreamData.Status.TRANSIENT, StreamData.parse(503, "", NOW).status);
        assertEquals(StreamData.Status.TRANSIENT, StreamData.parse(-1, null, NOW).status); // IO failure
    }

    @Test public void nonHttpsUrlIsRejected() {
        String plain = url("false").replace("https://", "http://");
        assertEquals(StreamData.Status.API_ERROR, StreamData.parse(200, body(plain, false), NOW).status);
    }

    @Test public void missingOrPastExpiryFallsBackToConservativeLifetime() {
        String noExp = url("false").replaceAll("&expiry=\\d+", "");
        StreamData.Result r = StreamData.parse(200, body(noExp, false), NOW);
        assertEquals(StreamData.Status.OK, r.status);
        assertEquals(NOW + StreamData.DEFAULT_LIFETIME_MS, r.expiryMs);
        String past = url("false").replace("expiry=" + EXP_S, "expiry=1000");
        assertEquals(NOW + StreamData.DEFAULT_LIFETIME_MS, StreamData.parse(200, body(past, false), NOW).expiryMs);
    }

    @Test public void redactionNeverLeaksQueryOrPath() {
        String red = Redact.url(url("false"));
        assertEquals("https://ivys-nw-cdn.o2tv.cz/…", red);
        assertFalse(red.contains("token"));
        assertEquals("<none>", Redact.url(null));
        assertEquals("<bad-url>", Redact.url("not a url"));
    }

    @Test public void corruptTokenChangesOnlyTokenValue() {
        String bad = StreamData.corruptToken(url("false"));
        assertNotEquals(url("false"), bad);
        assertEquals(url("false").length(), bad.length());
        assertTrue(bad.contains("&expiry=" + EXP_S));
    }
}
