package cz.tvminimal.home;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import cz.tvminimal.core.Redact;
import cz.tvminimal.core.StreamData;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Fetches ČT24 stream-data on a worker thread. Never logs or persists the signed URL. */
final class Resolver {
    static final String API = "https://api.ceskatelevize.cz/video/v1/playlist-live/v1/stream-data/channel/CH_24"
            + "?canPlayDrm=false&streamType=hls&quality=web&maxQualityCount=5";
    static final String UA = "TVMinimal/1.0 (Linux; Android TV)";
    private static final long[] BACKOFF_MS = {1_000, 3_000};

    interface Callback { void done(int gen, StreamData.Result r); }

    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    volatile int lastHttp;

    void resolve(int gen, boolean corruptToken, int forceTransient, Callback cb) {
        exec.execute(() -> {
            StreamData.Result r = forceTransient > 0 ? StreamData.Result.of(StreamData.Status.TRANSIENT) : fetchWithRetry();
            if (corruptToken && r.status == StreamData.Status.OK) {
                r = StreamData.Result.ok(StreamData.corruptToken(r.url), r.expiryMs);
            }
            Log.i(App.TAG, "resolve gen=" + gen + " -> " + r.status + " http=" + lastHttp + " host=" + Redact.url(r.url)
                    + (forceTransient > 0 ? " (test-forced)" : "") + (corruptToken ? " (test-corrupt)" : ""));
            StreamData.Result fr = r;
            main.post(() -> cb.done(gen, fr));
        });
    }

    private StreamData.Result fetchWithRetry() {
        StreamData.Result r = fetchOnce();
        for (int i = 0; i < BACKOFF_MS.length && r.status == StreamData.Status.TRANSIENT; i++) {
            try {
                Thread.sleep(BACKOFF_MS[i]);
            } catch (InterruptedException e) {
                return r;
            }
            r = fetchOnce();
        }
        return r;
    }

    private StreamData.Result fetchOnce() {
        HttpURLConnection c = null;
        int code = -1;
        String body = null;
        try {
            c = (HttpURLConnection) new URL(API).openConnection();
            c.setConnectTimeout(8_000);
            c.setReadTimeout(8_000);
            c.setUseCaches(false);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept", "application/json");
            code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in != null) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0 && bo.size() < 256 * 1024) bo.write(buf, 0, n);
                in.close();
                body = new String(bo.toByteArray(), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            Log.w(App.TAG, "resolve io: " + e.getClass().getSimpleName());
            code = -1;
        } finally {
            if (c != null) c.disconnect();
        }
        lastHttp = code;
        return StreamData.parse(code, body, System.currentTimeMillis());
    }

    void shutdown() { exec.shutdownNow(); }
}
