package cz.tvminimal.home;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import cz.tvminimal.core.Autoplay;

/** Process-wide screen on/off observation for wake detection. No services, no manifest receivers. */
public final class App extends Application {
    static final String TAG = "TVM";
    static final Autoplay.WakeDetector WAKE = new Autoplay.WakeDetector();
    static final long PROCESS_START = SystemClock.elapsedRealtime();

    @Override public void onCreate() {
        super.onCreate();
        androidx.media3.common.util.Log.setLogLevel(androidx.media3.common.util.Log.LOG_LEVEL_WARNING);
        BroadcastReceiver screen = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                long t = SystemClock.elapsedRealtime();
                if (Intent.ACTION_SCREEN_OFF.equals(i.getAction())) WAKE.onScreenOff(t);
                else if (Intent.ACTION_SCREEN_ON.equals(i.getAction())) {
                    WAKE.onScreenOn(t);
                    Autoplay.Mode mode = Autoplay.Mode.parse(getSharedPreferences("tvm", MODE_PRIVATE).getString("autoplay", null));
                    if (Autoplay.bringToFrontOnScreenOn(mode, HomeActivity.visible)) {
                        Log.i(TAG, "screen on: bringing launcher to front (mode ALWAYS)");
                        try {
                            startActivity(new Intent(c, HomeActivity.class)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                                    .putExtra("tvm_via", "wake"));
                        } catch (RuntimeException ex) {
                            Log.w(TAG, "bring to front failed " + ex.getClass().getSimpleName());
                        }
                    }
                }
                Log.i(TAG, "screen " + i.getAction() + " t=" + t);
            }
        };
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screen, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(screen, f);
        Log.i(TAG, "process start");
    }
}
