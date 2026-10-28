package cz.tvminimal.home;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import cz.tvminimal.core.AppOrder;
import cz.tvminimal.core.Autoplay;
import cz.tvminimal.core.IdleHider;
import cz.tvminimal.core.IntentHooks;
import cz.tvminimal.core.PlaybackMachine;
import cz.tvminimal.core.PlaybackMachine.Failure;
import cz.tvminimal.core.PlaybackMachine.PlayerError;
import cz.tvminimal.core.PlaybackMachine.State;
import cz.tvminimal.core.Source;
import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileReader;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** HOME activity: app grid over live ČT24 (default) or the manually chosen Action Reel video. */
public final class HomeActivity extends Activity implements PlaybackMachine.Effects, Ct24Player.Listener {
    static final String IVYS_PKG = "cz.ceskatelevize.ivysilani.tvapp";
    static final String K_CT24 = "#ct24";
    /** Lives in AppOrder (movable/hideable like an app); the '/' makes it a valid order key. */
    static final String K_ACTION_REEL = "#tvm/actionreel";
    static final String K_SETTINGS = "#settings";
    static final long HIDE_AFTER_MS = 10_000;
    static final int COLUMNS = 6;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private final IdleHider idle = new IdleHider(HIDE_AFTER_MS);
    private final Resolver resolver = new Resolver();
    private PlaybackMachine machine;
    private Ct24Player player;
    private SharedPreferences prefs;
    private AppOrder order;
    private Map<String, Apps.Entry> installed = new LinkedHashMap<>();
    private final List<String> items = new ArrayList<>();
    private TileAdapter adapter;

    private View root;
    private View overlay;
    private GridView grid;
    private TextView status;
    private View failPanel;
    private TextView failText;
    private View btnRetry;
    private View btnIvys;
    private TextView hint;
    private View a11yPanel;
    private AlertDialog dialog;

    private String moveKey;
    private int swallowKeyUp = -1;
    private boolean started;
    /** Read by App's SCREEN_ON receiver (main thread). */
    static boolean visible;
    private long nextRefreshAt = -1;
    private int testForceTransient;
    private boolean testCorruptNext;
    private String bootId;
    private String bootSource = "-";
    private String lastDecision = "-";
    private String lastVia = "-";
    private int visibleCount;
    private int autoplayCount;
    private ConnectivityManager.NetworkCallback netCb;

    private final Runnable refreshTask = () -> machine.onRefreshDue();
    private final Runnable netTimeoutTask = () -> machine.onNetworkTimeout();
    private final Runnable hideCheck = new Runnable() {
        @Override public void run() {
            if (!started) return;
            long now = SystemClock.uptimeMillis();
            boolean playing = player.isPlaying() && machine.state() == State.PLAYING;
            if (moveKey == null && dialog == null && idle.shouldHide(now, playing)) {
                hideGrid();
            } else if (!idle.isHidden()) {
                handler.postDelayed(this, playing ? Math.max(250, idle.delayUntilHide(now)) : 1_000);
            }
        }
    };

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            Uri d = i.getData();
            String pkg = d == null ? null : d.getSchemeSpecificPart();
            Log.i(App.TAG, "package event " + i.getAction() + " " + pkg);
            if (pkg != null) {
                Map<String, Apps.Entry> copy = new LinkedHashMap<>(installed);
                copy.keySet().removeIf(k -> k.startsWith(pkg + "/"));
                installed = copy;
            }
            reloadApps();
        }
    };

    // ---------------------------------------------------------------- lifecycle

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_home);
        root = findViewById(R.id.root);
        overlay = findViewById(R.id.overlay);
        grid = findViewById(R.id.grid);
        status = findViewById(R.id.status);
        failPanel = findViewById(R.id.failPanel);
        failText = findViewById(R.id.failText);
        btnRetry = findViewById(R.id.btnRetry);
        hint = findViewById(R.id.hint);
        SurfaceView video = findViewById(R.id.video);

        prefs = getSharedPreferences("tvm", MODE_PRIVATE);
        order = AppOrder.restore(getPackageName(), prefs.getString("order", null), prefs.getString("hidden", null),
                Collections.singletonList(K_ACTION_REEL));
        machine = new PlaybackMachine(this, System::currentTimeMillis);
        player = new Ct24Player(this, video, this);

        adapter = new TileAdapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((p, v, pos, id) -> onTile(pos));
        grid.setOnItemLongClickListener((p, v, pos, id) -> {
            onTileMenu(pos);
            return true;
        });
        grid.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) { adapter.notifySelection(); }

            @Override public void onNothingSelected(AdapterView<?> p) { }
        });
        grid.setOnFocusChangeListener((v, has) -> adapter.notifySelection());
        btnRetry.setOnClickListener(v -> {
            machine.stop();
            machine.start(machine.source()); // retries the source that failed, never another one
            grid.requestFocus();
        });
        btnIvys = findViewById(R.id.btnIvys);
        btnIvys.setOnClickListener(v -> openIvysilani());
        a11yPanel = findViewById(R.id.a11yPanel);
        findViewById(R.id.btnA11y).setOnClickListener(v -> launch(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));

        IntentFilter pf = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pf.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pf.addAction(Intent.ACTION_PACKAGE_REPLACED);
        pf.addDataScheme("package");
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(packageReceiver, pf, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(packageReceiver, pf);

        bootId = readBootIdentity();
        rebuildItems();
        updateStatus();
        handleIntent(getIntent(), true);
        Log.i(App.TAG, "onCreate bootId=" + bootId + " src=" + bootSource);
    }

    @Override protected void onStart() {
        super.onStart();
        started = true;
        visible = true;
        visibleCount++;
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        boolean interactive = pm.isInteractive();
        boolean wake = App.WAKE.consumeWake(SystemClock.elapsedRealtime(), interactive);
        Autoplay.Mode mode = Autoplay.Mode.parse(prefs.getString("autoplay", null));
        Autoplay.Decision d = Autoplay.decide(mode, bootId, prefs.getString("bootId", null), wake);
        if (d.storeBootId != null) prefs.edit().putString("bootId", d.storeBootId).commit(); // before playback
        lastDecision = d.reason + ":" + (d.play ? "play" : "no") + " mode=" + mode + " wake=" + wake + " interactive=" + interactive;
        Log.i(App.TAG, "onStart decision " + lastDecision);
        a11yPanel.setVisibility(homeServiceEnabled() ? View.GONE : View.VISIBLE);
        registerNetwork();
        reloadApps();
        showGrid(true);
        if (d.play) {
            autoplayCount++;
            machine.start(d.source); // always ČT24
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        Log.i(App.TAG, "onNewIntent started=" + started + " state=" + machine.state());
        dismissDialog();
        endMove();
        if (started) showGrid(true); // Home pressed while visible: show grid, never restart playback
        handleIntent(intent, false);
    }

    @Override protected void onStop() {
        super.onStop();
        started = false;
        visible = false;
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (!pm.isInteractive()) App.WAKE.onScreenOff(SystemClock.elapsedRealtime());
        Log.i(App.TAG, "onStop interactive=" + pm.isInteractive());
        endMove();
        dismissDialog();
        machine.stop();
        handler.removeCallbacks(hideCheck);
        unregisterNetwork();
    }

    @Override protected void onDestroy() {
        unregisterReceiver(packageReceiver);
        machine.stop();
        player.release();
        resolver.shutdown();
        bg.shutdownNow();
        super.onDestroy();
    }

    /**
     * Automation hooks (README: Testovací háčky). Any app can send these to an exported HOME
     * activity, so release builds honour only tvm_via/tvm_play/tvm_stop; tvm_test_* need a debuggable build.
     */
    private void handleIntent(Intent i, boolean fromCreate) {
        if (i == null) return;
        boolean debuggable = (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        for (String k : new String[] {"tvm_test_forget_boot", "tvm_test_refresh_s", "tvm_test_corrupt_token", "tvm_test_fail_resolve"}) {
            if (i.hasExtra(k) && !IntentHooks.allowed(k, debuggable)) {
                Log.w(App.TAG, "ignored test hook " + k + " (release build)");
                i.removeExtra(k);
            }
        }
        if (i.hasExtra("tvm_via")) {
            lastVia = i.getStringExtra("tvm_via");
            Log.i(App.TAG, "opened via " + lastVia);
            i.removeExtra("tvm_via");
        }
        if (i.getBooleanExtra("tvm_test_forget_boot", false)) { // simulate "first visibility in a new boot"
            prefs.edit().remove("bootId").remove("serviceBootId").commit();
            Log.i(App.TAG, "test: boot identity forgotten");
        }
        if (i.hasExtra("tvm_test_refresh_s")) {
            int s = i.getIntExtra("tvm_test_refresh_s", 0);
            machine.setTestRefreshAfterMs(s > 0 ? s * 1000L : -1);
            Log.i(App.TAG, "test refresh override s=" + s);
        }
        if (i.getBooleanExtra("tvm_test_corrupt_token", false)) testCorruptNext = true;
        if (i.hasExtra("tvm_test_fail_resolve")) testForceTransient = Math.max(0, i.getIntExtra("tvm_test_fail_resolve", 0));
        if (i.getBooleanExtra("tvm_stop", false)) machine.stop();
        if (i.getBooleanExtra("tvm_play", false)) { // ČT24 only if nothing plays; never switches a running source
            if (fromCreate) handler.post(() -> { if (started) machine.playDefaultIfIdle(); });
            else if (started) machine.playDefaultIfIdle();
        }
        i.removeExtra("tvm_play");
        i.removeExtra("tvm_stop");
        i.removeExtra("tvm_test_corrupt_token");
        i.removeExtra("tvm_test_fail_resolve");
        i.removeExtra("tvm_test_refresh_s");
        i.removeExtra("tvm_test_forget_boot");
    }

    // ---------------------------------------------------------------- keys

    private static boolean passThrough(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_VOLUME_MUTE:
            case KeyEvent.KEYCODE_MUTE:
            case KeyEvent.KEYCODE_POWER:
            case KeyEvent.KEYCODE_SLEEP:
            case KeyEvent.KEYCODE_WAKEUP:
            case KeyEvent.KEYCODE_HOME:
            case KeyEvent.KEYCODE_TV_POWER:
            case KeyEvent.KEYCODE_SETTINGS:
            case KeyEvent.KEYCODE_TV_INPUT:
                return true;
            default:
                return false;
        }
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        if (passThrough(code)) return super.dispatchKeyEvent(e);
        long now = SystemClock.uptimeMillis();
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            if (idle.isHidden()) {
                swallowKeyUp = code;
                showGrid(false);
                return true;
            }
            idle.onKey(now);
            scheduleHideCheck();
            if (moveKey != null && dialog == null) return handleMoveKey(code);
            if (code == KeyEvent.KEYCODE_BACK) {
                if (dialog == null && machine.state() == State.PLAYING && player.isPlaying()) hideGrid();
                return true; // HOME never finishes on Back
            }
            if (code == KeyEvent.KEYCODE_MENU) {
                int pos = grid.getSelectedItemPosition();
                if (grid.hasFocus() && pos >= 0) onTileMenu(pos);
                return true;
            }
        } else if (e.getAction() == KeyEvent.ACTION_UP) {
            if (code == swallowKeyUp) {
                swallowKeyUp = -1;
                return true;
            }
            if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_MENU) return true;
            if (moveKey != null && dialog == null) return true;
        }
        if (swallowKeyUp == code) return true; // repeats of a reveal key
        return super.dispatchKeyEvent(e);
    }

    private boolean handleMoveKey(int code) {
        int delta;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT: delta = -1; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: delta = 1; break;
            case KeyEvent.KEYCODE_DPAD_UP: delta = -COLUMNS; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: delta = COLUMNS; break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_BACK:
                endMove();
                return true;
            default:
                return true;
        }
        int idx = order.move(moveKey, delta, labels());
        rebuildItems();
        if (idx >= 0) grid.setSelection(idx + 1); // +1 for the ČT24 tile
        return true;
    }

    // ---------------------------------------------------------------- grid

    private void onTile(int pos) {
        if (pos < 0 || pos >= items.size()) return;
        String key = items.get(pos);
        Source src = sourceFor(key);
        if (src != null) {
            if (K_ACTION_REEL.equals(key)) order.clearNew(key);
            if (machine.source() == src && machine.state() == State.PLAYING && player.isPlaying()) hideGrid();
            else {
                machine.stop(); // releases the current player/audio (either source) before the chosen one starts
                machine.start(src);
            }
            return;
        }
        if (K_SETTINGS.equals(key)) {
            showSettings();
            return;
        }
        Apps.Entry a = installed.get(key);
        if (a == null) return;
        order.clearNew(key);
        launch(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER).setComponent(a.component));
    }

    /** Stops and releases playback (any source) before handing the screen to another app. */
    private void launch(Intent i) {
        machine.stop();
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            startActivity(i);
            Log.i(App.TAG, "launched " + (i.getComponent() != null ? i.getComponent().flattenToShortString() : i.getAction()));
        } catch (ActivityNotFoundException | SecurityException ex) {
            Toast.makeText(this, R.string.cannot_open, Toast.LENGTH_SHORT).show();
        }
    }

    private void openIvysilani() {
        Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(IVYS_PKG);
        if (i == null) i = getPackageManager().getLaunchIntentForPackage(IVYS_PKG);
        if (i == null) {
            Toast.makeText(this, R.string.cannot_open, Toast.LENGTH_SHORT).show();
            return;
        }
        launch(i);
    }

    private void onTileMenu(int pos) {
        if (pos < 0 || pos >= items.size()) return;
        String key = items.get(pos);
        if (K_CT24.equals(key) || K_SETTINGS.equals(key)) {
            if (K_SETTINGS.equals(key)) showSettings();
            return;
        }
        Apps.Entry a = installed.get(key);
        String[] opts = a != null
                ? new String[] {getString(R.string.menu_move), getString(R.string.menu_hide), getString(R.string.menu_info), getString(R.string.menu_cancel)}
                : new String[] {getString(R.string.menu_move), getString(R.string.menu_hide), getString(R.string.menu_cancel)};
        showDialog(new AlertDialog.Builder(this, R.style.Dialog)
                .setTitle(label(key))
                .setItems(opts, (dlg, which) -> {
                    if (which == 0) startMove(key);
                    else if (which == 1) {
                        order.hide(key);
                        saveOrder();
                        rebuildItems();
                    } else if (which == 2 && a != null) {
                        launch(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", a.component.getPackageName(), null)));
                    }
                }));
    }

    private void startMove(String key) {
        moveKey = key;
        hint.setVisibility(View.VISIBLE);
        adapter.notifyDataSetChanged();
    }

    private void endMove() {
        if (moveKey == null) return;
        moveKey = null;
        hint.setVisibility(View.GONE);
        saveOrder();
        adapter.notifyDataSetChanged();
    }

    private void showSettings() {
        Autoplay.Mode mode = Autoplay.Mode.parse(prefs.getString("autoplay", null));
        int hiddenCount = order.hiddenInstalled(labels()).size();
        String[] opts = {
                getString(R.string.set_autoplay, modeLabel(mode)),
                getString(R.string.set_hidden, hiddenCount),
                getString(R.string.set_reset),
                getString(R.string.set_system),
                getString(R.string.set_about)};
        showDialog(new AlertDialog.Builder(this, R.style.Dialog)
                .setTitle(R.string.launcher_settings)
                .setItems(opts, (dlg, which) -> {
                    dialog = null;
                    if (which == 0) chooseAutoplay(mode);
                    else if (which == 1) showHidden();
                    else if (which == 2) confirmReset();
                    else if (which == 3) launch(new Intent(Settings.ACTION_SETTINGS));
                    else if (which == 4) showAbout();
                }));
    }

    private String modeLabel(Autoplay.Mode m) {
        switch (m) {
            case OFF: return getString(R.string.mode_off);
            case BOOT_ONLY: return getString(R.string.mode_boot);
            case BOOT_AND_WAKE: return getString(R.string.mode_boot_wake);
            default: return getString(R.string.mode_always);
        }
    }

    private void chooseAutoplay(Autoplay.Mode current) {
        Autoplay.Mode[] modes = {Autoplay.Mode.ALWAYS, Autoplay.Mode.BOOT_AND_WAKE, Autoplay.Mode.BOOT_ONLY, Autoplay.Mode.OFF};
        String[] labels = new String[modes.length];
        int sel = 0;
        for (int i = 0; i < modes.length; i++) {
            labels[i] = modeLabel(modes[i]);
            if (modes[i] == current) sel = i;
        }
        showDialog(new AlertDialog.Builder(this, R.style.Dialog)
                .setTitle(getString(R.string.set_autoplay, ""))
                .setSingleChoiceItems(labels, sel, (dlg, which) -> {
                    prefs.edit().putString("autoplay", modes[which].name()).apply();
                    Log.i(App.TAG, "autoplay mode -> " + modes[which]);
                    dlg.dismiss();
                }));
    }

    private void showHidden() {
        List<String> hidden = order.hiddenInstalled(labels());
        if (hidden.isEmpty()) {
            Toast.makeText(this, R.string.no_hidden, Toast.LENGTH_SHORT).show();
            return;
        }
        String[] names = new String[hidden.size()];
        for (int i = 0; i < names.length; i++) names[i] = label(hidden.get(i));
        showDialog(new AlertDialog.Builder(this, R.style.Dialog)
                .setTitle(getString(R.string.set_hidden, hidden.size()))
                .setItems(names, (dlg, which) -> {
                    order.unhide(hidden.get(which));
                    saveOrder();
                    rebuildItems();
                }));
    }

    private void confirmReset() {
        showDialog(new AlertDialog.Builder(this, R.style.Dialog)
                .setMessage(R.string.reset_confirm)
                .setPositiveButton(R.string.yes, (dlg, w) -> {
                    order.reset();
                    rebuildItems();
                    saveOrder();
                })
                .setNegativeButton(R.string.menu_cancel, null));
    }

    private void showAbout() {
        String v = "?";
        try {
            v = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException ignored) { }
        showDialog(new AlertDialog.Builder(this, R.style.Dialog)
                .setMessage(getString(R.string.about_text, v))
                .setPositiveButton(android.R.string.ok, null));
    }

    private void showDialog(AlertDialog.Builder b) {
        dismissDialog();
        AlertDialog d = b.create();
        d.setOnDismissListener(x -> {
            if (dialog == x) dialog = null;
            idle.onShown(SystemClock.uptimeMillis());
            scheduleHideCheck();
        });
        dialog = d;
        d.show();
    }

    private void dismissDialog() {
        if (dialog != null) {
            AlertDialog d = dialog;
            dialog = null;
            d.dismiss();
        }
    }

    private void saveOrder() {
        prefs.edit().putString("order", order.serializeOrder()).putString("hidden", order.serializeHidden()).apply();
    }

    /** "Installed" set for AppOrder: real apps plus the built-in Action Reel tile. */
    private Map<String, String> labels() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(K_ACTION_REEL, getString(R.string.action_reel));
        for (Apps.Entry e : installed.values()) m.put(e.key, e.label);
        return m;
    }

    private String label(String key) {
        if (K_ACTION_REEL.equals(key)) return getString(R.string.action_reel);
        Apps.Entry a = installed.get(key);
        return a != null ? a.label : key;
    }

    private static Source sourceFor(String key) {
        if (K_CT24.equals(key)) return Source.CT24;
        if (K_ACTION_REEL.equals(key)) return Source.ACTION_REEL;
        return null;
    }

    private static String tileKey(Source s) { return s == Source.ACTION_REEL ? K_ACTION_REEL : K_CT24; }

    private String sourceName(Source s) { return getString(s == Source.ACTION_REEL ? R.string.action_reel : R.string.ct24); }

    private void reloadApps() {
        PackageManager pm = getPackageManager();
        Map<String, Apps.Entry> cache = installed;
        bg.execute(() -> {
            Map<String, Apps.Entry> fresh = Apps.query(pm, cache);
            handler.post(() -> {
                installed = fresh;
                String selected = selectedKey();
                rebuildItems();
                saveOrder();
                if (selected != null && items.indexOf(selected) > 0 && grid.hasFocus()) grid.setSelection(items.indexOf(selected));
            });
        });
    }

    private String selectedKey() {
        int p = grid.getSelectedItemPosition();
        return p >= 0 && p < items.size() ? items.get(p) : null;
    }

    private void rebuildItems() {
        items.clear();
        items.add(K_CT24);
        if (!installed.isEmpty()) items.addAll(order.merge(labels()));
        items.add(K_SETTINGS);
        adapter.notifyDataSetChanged();
    }

    private void showGrid(boolean focusFirst) {
        idle.onShown(SystemClock.uptimeMillis());
        overlay.animate().cancel();
        overlay.setVisibility(View.VISIBLE);
        overlay.animate().alpha(1f).setDuration(180).start();
        if (focusFirst) {
            if (failPanel.getVisibility() == View.VISIBLE) btnRetry.requestFocus();
            else {
                grid.requestFocus();
                grid.setSelection(Math.max(0, items.indexOf(machine.isActive() ? tileKey(machine.source()) : K_CT24)));
            }
        } else if (!grid.hasFocus() && !failPanel.hasFocus()) {
            grid.requestFocus();
        }
        scheduleHideCheck();
    }

    private void hideGrid() {
        endMove();
        idle.onHidden();
        handler.removeCallbacks(hideCheck);
        overlay.animate().cancel();
        overlay.animate().alpha(0f).setDuration(400).withEndAction(() -> {
            if (idle.isHidden()) overlay.setVisibility(View.INVISIBLE);
        }).start();
        root.requestFocus();
        Log.i(App.TAG, "grid hidden");
    }

    private void scheduleHideCheck() {
        handler.removeCallbacks(hideCheck);
        if (started) handler.postDelayed(hideCheck, Math.max(250, idle.delayUntilHide(SystemClock.uptimeMillis())));
    }

    private void updateStatus() {
        int res;
        switch (machine.state()) {
            case WAITING_NETWORK: res = R.string.status_wait_net; break;
            case RESOLVING: res = R.string.status_resolving; break;
            case PLAYING:
                if (!player.isPlaying()) res = R.string.status_resolving;
                else res = machine.source() == Source.CT24 ? R.string.status_playing : R.string.status_playing_ar;
                break;
            case REFRESHING: res = R.string.status_refreshing; break;
            case RETRYING: res = R.string.status_retrying; break;
            case FAILED: res = R.string.status_failed; break;
            default: res = R.string.status_idle;
        }
        status.setText(getString(res, sourceName(machine.source())));
    }

    // ---------------------------------------------------------------- PlaybackMachine.Effects

    @Override public boolean networkAvailable() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        Network n = cm.getActiveNetwork();
        NetworkCapabilities c = n == null ? null : cm.getNetworkCapabilities(n);
        // INTERNET, not VALIDATED: validation can lag after wake or be blocked by private DNS setups.
        return c != null && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    @Override public void resolve(int gen) {
        boolean corrupt = testCorruptNext;
        testCorruptNext = false;
        int force = testForceTransient;
        if (testForceTransient > 0) testForceTransient--;
        resolver.resolve(gen, corrupt, force, (g, r) -> machine.onResolved(g, r));
    }

    @Override public void play(String url, boolean refresh) { player.play(url, refresh); }

    @Override public void seekToLiveEdge() { player.seekToLiveEdge(); }

    @Override public void releasePlayer() { player.release(); }

    @Override public void scheduleRefresh(long atMs) {
        handler.removeCallbacks(refreshTask);
        nextRefreshAt = atMs;
        handler.postDelayed(refreshTask, Math.max(0, atMs - System.currentTimeMillis()));
        Log.i(App.TAG, "refresh scheduled in s=" + (atMs - System.currentTimeMillis()) / 1000);
    }

    @Override public void scheduleNetworkTimeout(long atMs) {
        handler.removeCallbacks(netTimeoutTask);
        handler.postDelayed(netTimeoutTask, Math.max(0, atMs - System.currentTimeMillis()));
    }

    @Override public void cancelTimers() {
        handler.removeCallbacks(refreshTask);
        handler.removeCallbacks(netTimeoutTask);
        nextRefreshAt = -1;
    }

    @Override public void showFailure(Failure f) {
        boolean ct24 = machine.source() == Source.CT24;
        int res;
        if (!ct24) res = f == Failure.NETWORK ? R.string.fail_ar_network : R.string.fail_ar_playback;
        else {
            switch (f) {
                case BLOCKED: res = R.string.fail_blocked; break;
                case DRM: res = R.string.fail_drm; break;
                case API: res = R.string.fail_api; break;
                case NETWORK: res = R.string.fail_network; break;
                default: res = R.string.fail_playback;
            }
        }
        failText.setText(res);
        btnIvys.setVisibility(ct24 ? View.VISIBLE : View.GONE); // iVysílání is no substitute for Red Bull
        boolean focusRetry = grid.hasFocus() && (selectedKey() == null || sourceFor(selectedKey()) != null);
        failPanel.setVisibility(View.VISIBLE);
        if (idle.isHidden()) showGrid(false);
        if (focusRetry) btnRetry.requestFocus();
        Log.w(App.TAG, "failure shown " + f);
    }

    @Override public void stateChanged(State s) {
        Log.i(App.TAG, "state -> " + s);
        if (s != State.FAILED && failPanel.getVisibility() == View.VISIBLE) {
            boolean hadFocus = failPanel.hasFocus();
            failPanel.setVisibility(View.GONE);
            if (hadFocus) grid.requestFocus();
        }
        if (s != State.PLAYING && s != State.REFRESHING && idle.isHidden()) showGrid(false);
        updateStatus();
    }

    // ---------------------------------------------------------------- Ct24Player.Listener

    @Override public void onError(PlayerError e, String detail) { machine.onPlayerError(e); }

    @Override public void onPlayingChanged(boolean playing) {
        updateStatus();
        if (playing) scheduleHideCheck();
    }

    @Override public void onAudioFocusLost() { machine.stop(); }

    // ---------------------------------------------------------------- network + boot identity

    private void registerNetwork() {
        if (netCb != null) return;
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        netCb = new ConnectivityManager.NetworkCallback() {
            @Override public void onCapabilitiesChanged(Network n, NetworkCapabilities c) {
                if (c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) handler.post(() -> machine.onNetworkAvailable());
            }
        };
        try {
            cm.registerDefaultNetworkCallback(netCb);
        } catch (RuntimeException ex) {
            netCb = null;
        }
    }

    private void unregisterNetwork() {
        if (netCb == null) return;
        try {
            getSystemService(ConnectivityManager.class).unregisterNetworkCallback(netCb);
        } catch (RuntimeException ignored) { }
        netCb = null;
    }

    /** Android removes the service from the enabled list e.g. after "Force stop"; then Home goes to Google TV. */
    private boolean homeServiceEnabled() {
        String s = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return s != null && s.contains(getPackageName() + "/");
    }

    private String readBootIdentity() {
        String kernel = null;
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/sys/kernel/random/boot_id"))) {
            kernel = r.readLine();
            bootSource = "kernel";
        } catch (Exception e) {
            bootSource = "boot_count(" + e.getClass().getSimpleName() + ")";
        }
        int bc = Settings.Global.getInt(getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        return Autoplay.bootIdentity(kernel, bc);
    }

    // ---------------------------------------------------------------- dumpsys

    @Override public void dump(String prefix, FileDescriptor fd, PrintWriter w, String[] args) {
        long now = System.currentTimeMillis();
        w.println(prefix + "TVM source=" + machine.source() + " state=" + machine.state() + " active=" + machine.isActive() + " refreshCount=" + machine.refreshCount()
                + " lastFailure=" + machine.lastFailure());
        w.println(prefix + "  expiryInS=" + (machine.expiryMs() > 0 ? (machine.expiryMs() - now) / 1000 : -1)
                + " nextRefreshInS=" + (nextRefreshAt > 0 ? (nextRefreshAt - now) / 1000 : -1));
        w.println(prefix + "  started=" + started + " gridHidden=" + idle.isHidden() + " overlayVis=" + overlay.getVisibility()
                + " failPanel=" + (failPanel.getVisibility() == View.VISIBLE) + " a11yNotice=" + (a11yPanel.getVisibility() == View.VISIBLE) + " moveKey=" + moveKey + " dialog=" + (dialog != null));
        View f = getCurrentFocus();
        String fn = "-";
        if (f != null) {
            try {
                fn = f.getId() == View.NO_ID ? f.getClass().getSimpleName() : getResources().getResourceEntryName(f.getId());
            } catch (RuntimeException ex) {
                fn = f.getClass().getSimpleName();
            }
        }
        w.println(prefix + "  focus=" + fn
                + " selected=" + grid.getSelectedItemPosition() + ":" + selectedKey() + " items=" + items.size());
        w.println(prefix + "  items=" + items);
        w.println(prefix + "  bootId=" + bootId + " src=" + bootSource + " stored=" + prefs.getString("bootId", null)
                + " mode=" + Autoplay.Mode.parse(prefs.getString("autoplay", null)));
        w.println(prefix + "  lastDecision=" + lastDecision + " visibleCount=" + visibleCount + " autoplayCount=" + autoplayCount
                + " processAgeS=" + (SystemClock.elapsedRealtime() - App.PROCESS_START) / 1000 + " lastVia=" + lastVia);
        w.println(prefix + "  wake=" + App.WAKE.describe() + " nowElapsed=" + SystemClock.elapsedRealtime());
        w.println(prefix + "  hidden=" + order.hiddenInstalled(labels()));
        player.dump(w);
    }

    // ---------------------------------------------------------------- adapter

    private final class TileAdapter extends BaseAdapter {
        @Override public int getCount() { return items.size(); }

        @Override public Object getItem(int p) { return items.get(p); }

        @Override public long getItemId(int p) { return items.get(p).hashCode(); }

        @Override public boolean hasStableIds() { return true; }

        void notifySelection() {
            int sel = grid.getSelectedItemPosition();
            for (int i = 0; i < grid.getChildCount(); i++) {
                View c = grid.getChildAt(i);
                boolean s = grid.getFirstVisiblePosition() + i == sel && grid.hasFocus();
                float scale = s ? 1.08f : 1f;
                c.animate().scaleX(scale).scaleY(scale).setDuration(120).start();
            }
        }

        @Override public View getView(int pos, View v, ViewGroup parent) {
            if (v == null) v = LayoutInflater.from(HomeActivity.this).inflate(R.layout.tile, parent, false);
            String key = items.get(pos);
            View card = v.findViewById(R.id.card);
            ImageView banner = v.findViewById(R.id.banner);
            View textBox = v.findViewById(R.id.textBox);
            TextView title = v.findViewById(R.id.cardTitle);
            TextView sub = v.findViewById(R.id.cardSub);
            TextView label = v.findViewById(R.id.label);
            View badge = v.findViewById(R.id.badge);
            card.setClipToOutline(true);
            badge.setVisibility(View.GONE);
            if (K_CT24.equals(key) || K_SETTINGS.equals(key)) {
                boolean ct = K_CT24.equals(key);
                card.setBackgroundResource(ct ? R.drawable.tile_ct24 : R.drawable.tile_bg);
                banner.setVisibility(View.GONE);
                textBox.setVisibility(View.VISIBLE);
                title.setText(ct ? getString(R.string.ct24) : "⚙");
                sub.setText(ct ? getString(R.string.live) : "");
                sub.setVisibility(ct ? View.VISIBLE : View.GONE);
                label.setText(ct ? getString(R.string.ct24) : getString(R.string.launcher_settings));
            } else if (K_ACTION_REEL.equals(key)) {
                card.setBackgroundResource(R.drawable.tile_actionreel);
                banner.setVisibility(View.GONE);
                textBox.setVisibility(View.VISIBLE);
                title.setText(R.string.action_reel);
                sub.setText(R.string.action_reel_sub);
                sub.setVisibility(View.VISIBLE);
                label.setText(R.string.action_reel);
                badge.setVisibility(order.isNew(key) ? View.VISIBLE : View.GONE);
            } else {
                Apps.Entry a = installed.get(key);
                card.setBackgroundResource(R.drawable.tile_bg);
                textBox.setVisibility(View.GONE);
                banner.setVisibility(View.VISIBLE);
                if (a != null && a.banner != null) {
                    banner.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    banner.setPadding(0, 0, 0, 0);
                    banner.setImageDrawable(a.banner);
                } else {
                    banner.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                    int p = (int) (14 * getResources().getDisplayMetrics().density);
                    banner.setPadding(p, p, p, p);
                    banner.setImageDrawable(a != null ? a.icon : null);
                }
                label.setText(a != null ? a.label : key);
                badge.setVisibility(order.isNew(key) ? View.VISIBLE : View.GONE);
            }
            boolean moving = key.equals(moveKey);
            v.setAlpha(moveKey != null && !moving ? 0.55f : 1f);
            boolean sel = pos == grid.getSelectedItemPosition() && grid.hasFocus();
            v.setScaleX(sel ? 1.08f : 1f);
            v.setScaleY(sel ? 1.08f : 1f);
            return v;
        }
    }
}
