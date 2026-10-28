package cz.tvminimal.core;

import java.net.URI;

/** Log-safe rendering of signed URLs: scheme and host only. Paths and queries may carry tokens. */
public final class Redact {
    private Redact() { }

    public static String url(String u) {
        if (u == null) return "<none>";
        try {
            URI uri = new URI(u);
            if (uri.getScheme() == null || uri.getHost() == null) return "<bad-url>";
            return uri.getScheme() + "://" + uri.getHost() + "/…";
        } catch (Exception e) {
            return "<bad-url>";
        }
    }
}
