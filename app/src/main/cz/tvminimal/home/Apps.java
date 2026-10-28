package cz.tvminimal.home;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Discovery of TV apps (MAIN + LEANBACK_LAUNCHER) with banners. Package visibility via manifest queries. */
final class Apps {
    static final class Entry {
        final String key;
        final String label;
        final ComponentName component;
        final Drawable banner;
        final Drawable icon;

        Entry(String key, String label, ComponentName c, Drawable banner, Drawable icon) {
            this.key = key;
            this.label = label;
            this.component = c;
            this.banner = banner;
            this.icon = icon;
        }
    }

    /** Called on a worker thread. */
    static Map<String, Entry> query(PackageManager pm, Map<String, Entry> cache) {
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<ResolveInfo> list = pm.queryIntentActivities(i, 0);
        Map<String, Entry> out = new LinkedHashMap<>();
        for (ResolveInfo ri : list) {
            if (ri.activityInfo == null) continue;
            ComponentName cn = new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name);
            String key = cn.flattenToShortString();
            Entry old = cache == null ? null : cache.get(key);
            if (old != null) {
                out.put(key, old);
                continue;
            }
            CharSequence l = ri.loadLabel(pm);
            Drawable banner = null;
            Drawable icon = null;
            try {
                banner = ri.activityInfo.loadBanner(pm);
                if (banner == null) icon = ri.loadIcon(pm);
            } catch (Exception ignored) { }
            out.put(key, new Entry(key, l == null ? cn.getPackageName() : l.toString(), cn, banner, icon));
        }
        return out;
    }
}
