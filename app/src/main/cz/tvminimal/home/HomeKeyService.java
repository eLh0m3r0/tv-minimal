package cz.tvminimal.home;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import cz.tvminimal.core.Autoplay;
import java.io.BufferedReader;
import java.io.FileReader;

/**
 * Brings TV Minimal to front instead of Google TV Home. Reacts to exactly two things:
 * the HOME key (consumed; every other key is passed through untouched and never recorded) and
 * launcherx's HomeActivity becoming the foreground window (boot, wake, Back into the home task).
 */
public final class HomeKeyService extends AccessibilityService {
    static final String LAUNCHERX = "com.google.android.apps.tv.launcherx";
    static final long BOOT_WINDOW_MS = 10 * 60_000L;
    private long lastOpen;

    @Override protected void onServiceConnected() {
        Log.i(App.TAG, "home service connected uptimeS=" + SystemClock.elapsedRealtime() / 1000);
        // launcherx may already be in front if we were bound after it was shown at boot: redirect once per boot.
        if (SystemClock.elapsedRealtime() > BOOT_WINDOW_MS) return;
        SharedPreferences p = getSharedPreferences("tvm", MODE_PRIVATE);
        String boot = bootIdentity();
        if (boot == null || boot.equals(p.getString("serviceBootId", null))) return;
        p.edit().putString("serviceBootId", boot).commit();
        open("boot");
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = e.getPackageName();
        CharSequence cls = e.getClassName();
        if (pkg == null || cls == null || !LAUNCHERX.contentEquals(pkg)) return;
        if (!cls.toString().endsWith(".home.HomeActivity")) return;
        if (SystemClock.uptimeMillis() - lastOpen < 1_000) return; // avoid ping-pong
        open("launcherx");
    }

    @Override protected boolean onKeyEvent(KeyEvent k) {
        if (k.getKeyCode() != KeyEvent.KEYCODE_HOME) return false;
        if (k.getAction() == KeyEvent.ACTION_UP && !k.isCanceled()) open("key");
        return true;
    }

    @Override public void onInterrupt() { }

    private void open(String via) {
        lastOpen = SystemClock.uptimeMillis();
        Log.i(App.TAG, "home redirect via=" + via);
        Intent i = new Intent(this, HomeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                .putExtra("tvm_via", via);
        try {
            startActivity(i);
        } catch (RuntimeException ex) {
            Log.w(App.TAG, "home redirect failed " + ex.getClass().getSimpleName());
        }
    }

    private String bootIdentity() {
        String kernel = null;
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/sys/kernel/random/boot_id"))) {
            kernel = r.readLine();
        } catch (Exception ignored) { }
        return Autoplay.bootIdentity(kernel, Settings.Global.getInt(getContentResolver(), Settings.Global.BOOT_COUNT, -1));
    }
}
