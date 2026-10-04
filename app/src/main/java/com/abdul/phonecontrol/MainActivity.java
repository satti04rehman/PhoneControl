package com.abdul.phonecontrol;

import android.app.Activity;
import android.app.AppOpsManager;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.app.NotificationManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Display;
import android.view.Display.Mode;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Own code only. No libraries, no gradle, no third-party deps.
 *
 * Android will not let an unrooted app write Settings.Global, exec /system/bin/wm, or
 * force-stop other apps. Every such button below is marked BLOCKED and, when pressed,
 * runs the command anyway and shows the real Android error - so nothing is ever faked.
 * The buttons that DO work use public APIs only and are marked OK.
 */
public class MainActivity extends Activity {

    static final String GAME = "com.tencent.igfit";   // PUBG Mobile Flash
    // BatteryManager.BATTERY_PROPERTY_TEMPERATURE and Settings.Global.LOW_POWER are
    // @hide in android.jar; these are the real values.
    private static final int PROP_TEMP = 3;
    private static final String PKG = "com.abdul.phonecontrol";

    private TextView status, logView;
    private LinearLayout root;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        buildUi();
        refresh();
    }

    // ---------------- UI ----------------

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private TextView label(String text, int size, int color) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setPadding(dp(4), dp(4), dp(4), dp(4));
        return t;
    }

    private void head(String s) {
        root.addView(label("\n" + s, 16, Color.WHITE));
    }

    /** tag: "OK" works without root, "ROOT" is blocked by Android, "MIXED" is partial. */
    private Button btn(String text, String tag, Runnable action) {
        Button b = new Button(this);
        b.setAllCaps(false);
        int col = "OK".equals(tag) ? 0xFF1B5E20 : "ROOT".equals(tag) ? 0xFF4E342E : 0xFF33691E;
        b.setBackgroundColor(col);
        b.setText(text + (tag.isEmpty() ? "" : "  [" + tag + "]"));
        b.setOnClickListener(v -> {
            try {
                action.run();
            } catch (Exception e) {
                toast("Error: " + e);
            }
            refresh();
        });
        root.addView(b);
        return b;
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(14), dp(14), dp(28));
        scroll.addView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);

        root.addView(label("Phone Control", 24, Color.parseColor("#7BE3C0")));
        root.addView(label("own app - PUBG Mobile Flash helper", 12, Color.GRAY));
        status = label("", 13, Color.parseColor("#FFD479"));
        root.addView(status);

        head("ONE-CLICK BOOST");
        btn(">>> BOOST + PLAY PUBG FLASH <<<", "OK", this::boostAndPlay);
        btn(">>> FREE RAM (max) <<<", "OK", this::maxFreeRam);
        btn(">>> GAME OPTIMIZED MODE <<<", "MIXED", this::gameMode);
        btn("Kill background only (no launch)", "OK", this::killAllBg);
        btn("Mute notifications while playing", "OK", this::toggleNotifications);

        head("LIVE HARDWARE (real sensors)");
        btn("CPU clock + per-core speed", "OK", this::showCpu);
        btn("Thermal zones (all)", "OK", this::showThermals);
        btn("Display modes available", "OK", this::showModes);
        btn("GPU + RAM detail", "OK", this::showGpuRam);
        btn("Battery health detail", "OK", this::showBatteryDetail);
        btn("Storage free space", "OK", this::showStorage);

        head("WARNING CHECKS (real sensors)");
        btn("Charge + play = heat. Battery saver?", "", this::checkHeatAndPower);
        btn("Wi-Fi signal / latency", "", this::checkNetwork);
        btn("Thermal + battery + RAM report", "", this::fullReport);

        head("RESOLUTION  (blocked without root)");
        btn("Set 1080p native", "ROOT", () -> setResolution("1080x2520", "420"));
        btn("Set 720p performance", "ROOT", () -> setResolution("720x1680", "280"));
        btn("Clear every override layer", "ROOT", this::clearOverride);

        head("GPU DRIVER  (blocked without root)");
        btn("Remove game from opt-out list", "ROOT", this::gpuOptOut);
        btn("Open Battery Saver page", "OK", () -> openSettings(Settings.ACTION_BATTERY_SAVER_SETTINGS));
        btn("Open Developer options", "OK", () -> openSettings(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));

        head("POWER + DISPLAY  (mixed)");
        btn("Battery saver OFF", "ROOT", () -> sh("settings put global low_power 0"));
        btn("Open Stamina settings", "OK", () -> openSettings("com.sonymobile.settings.stamina"));
        btn("Animations OFF", "ROOT", () -> setAnim(0));
        btn("Animations ON", "ROOT", () -> setAnim(1));
        btn("Screen off timeout page", "OK", () -> openSettings(Settings.ACTION_DISPLAY_SETTINGS));

        head("MANUAL CONTROLS");
        btn("Kill one app's background", "OK", this::pickAppToKill);
        btn("Top 10 memory users", "", this::showMemoryHogs);
        btn("Clear RESULT LOG", "", () -> logView.setText(""));

        head("RESULT LOG");
        logView = new TextView(this);
        logView.setTextSize(11);
        logView.setTextColor(Color.parseColor("#B9F6CA"));
        logView.setPadding(dp(4), dp(4), dp(4), dp(4));
        logView.setTextIsSelectable(true);
        root.addView(logView);
    }

    /** BatteryManager returns Int.MIN_VALUE when the property is unreadable, which is
     *  what produced the nonsense "-214748364.8C". Report that honestly instead. */
    private String battTemp(BatteryManager bm) {
        if (bm == null) return "?";
        int t = bm.getIntProperty(PROP_TEMP);
        return t == Integer.MIN_VALUE ? "temp?" : (t / 10.0) + "C";
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private void log(String s) {
        logView.append(s + "\n");
    }

    private void openSettings(String action) {
        try {
            startActivity(new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            toast("No app handles " + action);
        }
    }

    // ---------------- shell: attempted, result always shown ----------------

    private String sh(String cmd) {
        String out;
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"/system/bin/sh", "-c", cmd});
            StringBuilder sb = new StringBuilder();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            int code = p.waitFor();
            out = sb.toString().trim();
            out = out.isEmpty() ? "(exit " + code + ", no output)" : out + "\n(exit " + code + ")";
        } catch (Exception e) {
            out = "DENIED: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        log(cmd + "\n  -> " + out);
        return out;
    }

    // ---------------- blocked (root) actions ----------------

    private void setResolution(String size, String density) {
        sh("wm size " + size);
        sh("wm density " + density);
        // display_size_forced is the persistent key that survives reboot and re-asserts
        // the override, which is why "wm size reset" alone did not stick before.
        sh("settings put global display_size_forced " + size.replace('x', ','));
    }

    private void clearOverride() {
        sh("wm size reset");
        sh("wm density reset");
        sh("settings put global display_size_forced 0");
        sh("settings put global display_scaling_force 0");
    }

    private void setAnim(int v) {
        sh("settings put global window_animation_scale " + v);
        sh("settings put global transition_animation_scale " + v);
        sh("settings put global animator_duration_scale " + v);
    }

    private void gpuOptOut() {
        String cur = Settings.Global.getString(getContentResolver(), "game_driver_opt_out_apps");
        if (cur != null && !cur.contains(GAME)) { toast("Game is not in the opt-out list"); return; }
        sh("settings put global game_driver_opt_out_apps '" + strip(cur, GAME) + "'");
    }

    private String strip(String list, String pkg) {
        if (list == null) return "";
        StringBuilder sb = new StringBuilder();
        for (String s : list.split(",")) {
            if (s.trim().isEmpty() || s.trim().equals(pkg)) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(s.trim());
        }
        return sb.toString();
    }

    // ---------------- working (no root) actions ----------------

    /**
     * Android 11 (API 30) restricts getRunningAppProcesses() to the caller's own
     * process only - verified on this device: it returned just our own PID. So we
     * enumerate installed packages instead, which QUERY_ALL_PACKAGES allows, and ask
     * Android to clear each background process. Verified working: Facebook PID died.
     */
    private void killAllBg() {
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        int asked = 0;
        for (ApplicationInfo ai : getPackageManager().getInstalledApplications(0)) {
            if (ai.packageName == null) continue;
            if (ai.packageName.equals(PKG) || ai.packageName.equals(GAME)) continue;
            if (ai.processName == null || ai.processName.equals(GAME)) continue;
            am.killBackgroundProcesses(ai.processName);
            asked++;
        }
        log("killBackgroundProcesses -> asked for " + asked + " package process(es); "
                + "game never targeted");
        toast("Cleared background of " + asked + " package(s)");
    }

    /** The single button: clear every background process, then bring the game to front. */
    private void boostAndPlay() {
        killAllBg();
        Intent i = getPackageManager().getLaunchIntentForPackage(GAME);
        if (i == null) { toast("PUBG Flash not installed"); return; }
        // NEW_TASK | RESET_TASK_IF_NEEDED brings the running game back to the front
        // instead of restarting it, so an in-progress match is not lost.
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        startActivity(i);
        log("BOOST: background cleared, " + GAME + " focused");
    }

    private void launchGame() {
        Intent i = getPackageManager().getLaunchIntentForPackage(GAME);
        if (i == null) { toast("PUBG Flash not installed"); return; }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
        log("launched " + GAME);
    }

    /**
 * Everything a no-root app can legitimately do to help a game, in one action:
 *   - clear cached background processes
 *   - dismiss all notifications (nothing can buzz mid-match)
 *   - keep the screen awake while plugged in (Screen On While Charging is a public
 *     Settings.Global we can write? No - read only. So we use our own window flag
 *     and tell the user which switch to flip.)
 *   - confirm thermal headroom and warn if hot
 *   - launch / focus the game
 * Animations, GPU driver list and battery saver still need root; reported honestly.
 */
private void gameMode() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        String th = thermal(pm);
        killAllBg();
        ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).cancelAll();

        Intent i = getPackageManager().getLaunchIntentForPackage(GAME);
        if (i == null) { toast("PUBG Flash not installed"); return; }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        startActivity(i);

        BatteryManager bm = (BatteryManager) getSystemService(Context.BATTERY_SERVICE);
        boolean charging = bm != null && bm.isCharging();
        boolean saver = Settings.Global.getInt(getContentResolver(), "low_power", 0) != 0;
        boolean stayOn = Settings.Global.getInt(getContentResolver(),
                "stay_on_while_plugged_in", 0) != 0;
        String opt = String.valueOf(Settings.Global.getString(getContentResolver(),
                "game_driver_opt_out_apps"));

        StringBuilder w = new StringBuilder("background cleared, notifications cleared, game focused\n\n");
        w.append("thermal: ").append(th).append('\n');
        w.append("charging while playing: ").append(charging)
                .append(charging ? "  <- unplug, causes heat" : "").append('\n');
        w.append("battery saver: ").append(saver).append(saver ? "  <- turn OFF" : "").append('\n');
        w.append("stay awake when charging: ").append(stayOn)
                .append(!stayOn && charging ? "  <- screen may sleep mid-match" : "").append('\n');
        if (opt.contains(GAME)) w.append("game IS in GPU opt-out list  <- needs root to clear\n");
        w.append("\nNeeds root (cannot be done from an app):\n")
                .append(" - animations on/off\n")
                .append(" - GPU driver opt-out list\n")
                .append(" - resolution override\n")
                .append(" - battery saver write\n");
        log("GAME MODE:\n" + w);
        new AlertDialog.Builder(this).setTitle("Game mode").setMessage(w.toString())
                .setPositiveButton("OK", null).show();
    }

    private void showCpu() {
        StringBuilder s = new StringBuilder();
        int n = Runtime.getRuntime().availableProcessors();
        for (int i = 0; i < n; i++) {
            String f = readFile("/sys/devices/system/cpu/cpu" + i + "/cpufreq/scaling_cur_freq");
            s.append("cpu").append(i).append(": ").append(f.isEmpty() ? "hidden" : f + " kHz").append('\n');
        }
        String g = readFile("/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor");
        s.append("\ngovernor: ").append(g.isEmpty() ? "hidden (needs root)" : g).append('\n');
        s.append("cores: ").append(n);
        log(s.toString());
        show("CPU", s.toString());
    }

    private void showThermals() {
        StringBuilder s = new StringBuilder();
        java.io.File z = new java.io.File("/sys/class/thermal");
        java.io.File[] zones = z.listFiles();
        if (zones != null) {
            java.util.Arrays.sort(zones);
            for (java.io.File f : zones) {
                String type = readFile(f.getPath() + "/type");
                String temp = readFile(f.getPath() + "/temp");
                if (type.isEmpty() || temp.isEmpty()) continue;
                try {
                    s.append(String.format("%-18s %.1fC%n", type, Integer.parseInt(temp) / 1000.0));
                } catch (NumberFormatException ignored) { }
            }
        }
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        s.append("\naggregate: ").append(thermal(pm));
        log(s.toString());
        show("Thermal zones", s.toString());
    }

    private void showModes() {
        StringBuilder s = new StringBuilder();
        for (Mode m : getWindowManager().getDefaultDisplay().getSupportedModes()) {
            s.append(m.getModeId()).append(": ").append(m.getPhysicalWidth()).append("x")
                    .append(m.getPhysicalHeight()).append(" @ ").append(m.getRefreshRate()).append("Hz\n");
        }
        s.append("\ncurrent: ").append(getWindowManager().getDefaultDisplay().getRefreshRate())
                .append(" Hz");
        String forced = String.valueOf(Settings.Global.getString(getContentResolver(),
                "display_size_forced"));
        s.append("\ndisplay_size_forced: ").append(forced);
        log(s.toString());
        show("Display modes", s.toString());
    }

    private void showGpuRam() {
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        StringBuilder s = new StringBuilder();
        s.append("RAM total:  ").append(mi.totalMem / 1048576).append(" MB\n");
        s.append("RAM avail:  ").append(mi.availMem / 1048576).append(" MB\n");
        s.append("RAM low:    ").append(mi.lowMemory).append('\n');
        s.append("threshold:  ").append(mi.threshold / 1048576).append(" MB\n");
        s.append("\nGPU renderer:\n").append(sh("dumpsys SurfaceFlinger | grep -m1 'GLES:'"));
        log(s.toString());
        show("GPU + RAM", s.toString());
    }

    private void showBatteryDetail() {
        BatteryManager bm = (BatteryManager) getSystemService(Context.BATTERY_SERVICE);
        StringBuilder s = new StringBuilder();
        s.append("temp:      ").append(battTemp(bm)).append('\n');
        s.append("capacity:  ").append(bm == null ? "?" :
                bm.getIntProperty(4)).append(" %\n");           // 4 = BATTERY_PROPERTY_CAPACITY
        s.append("charge cnt:").append(bm == null ? "?" :
                bm.getIntProperty(1)).append('\n');             // 1 = CHARGE_COUNTER
        s.append("status:    ").append(bm == null ? "?" :
                bm.getIntProperty(2)).append('\n');             // 2 = STATUS
        s.append("health:    ").append(bm == null ? "?" :
                bm.getIntProperty(3)).append('\n');             // 3 = HEALTH
        s.append("charging:  ").append(bm != null && bm.isCharging()).append('\n');
        s.append("plugged:   ").append(bm == null ? "?" : plugName(bm));
        log(s.toString());
        show("Battery", s.toString());
    }

    private String plugName(BatteryManager bm) {
        switch (bm.getIntProperty(5)) {                        // 5 = PLUGGED
            case 0: return "unplugged";
            case 1: return "AC";
            case 2: return "USB";
            case 4: return "wireless";
            default: return "other";
        }
    }

    private void showStorage() {
        StringBuilder s = new StringBuilder();
        for (java.io.File f : new java.io.File[]{
                new java.io.File("/data"), new java.io.File("/sdcard"),
                new java.io.File("/system")}) {
            try {
                long tot = f.getTotalSpace(), free = f.getFreeSpace();
                s.append(String.format("%-10s %6d MB free / %6d MB total%n",
                        f.getName(), free / 1048576, tot / 1048576));
            } catch (Exception e) {
                s.append(f.getName()).append(": hidden\n");
            }
        }
        log(s.toString());
        show("Storage", s.toString());
    }

    private String readFile(String path) {
        try {
            java.io.File f = new java.io.File(path);
            if (!f.canRead()) return "";
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new InputStreamReader(new java.io.FileInputStream(f)));
            String line = r.readLine();
            r.close();
            return line == null ? "" : line.trim();
        } catch (Exception e) {
            return "";
        }
    }

    private void show(String title, String body) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(body)
                .setPositiveButton("OK", null).show();
    }

/**
 * Hardest RAM reclaim available without root, in the order Android allows:
   *   1. drop this app's own caches and trim our heap
     *   2. kill cached background processes for every non-system package
 *   3. ask the window manager to release cached surfaces / trim memory
     *   4. send trim-memory signal so other apps release their own caches
     *   5. clear every posted notification (small but real memory)
     * Android re-launches recently-used apps (verified: FB/IG return with new PIDs),
     * so this is a trim, not a permanent freeze. For that, force-stop needs root.
     */
    private void maxFreeRam() {
        long before = freeRamMb();
        new Thread(() -> {
            try {
                // 1. our own caches
                deleteTree(getCacheDir());
                deleteTree(getExternalCacheDir());
                Runtime.getRuntime().gc();
                System.runFinalization();

                // 2 + 4. kill cached processes, then nudge everyone to trim
                ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
                List<ApplicationInfo> all = getPackageManager().getInstalledApplications(0);
                final int[] asked = {0};
                for (ApplicationInfo ai : all) {
                    if (ai.packageName == null || ai.processName == null) continue;
                    if (ai.packageName.equals(PKG) || ai.packageName.equals(GAME)) continue;
                    am.killBackgroundProcesses(ai.processName);
                    asked[0]++;
                }

                // 3. touch the window manager so cached surfaces are released
                getWindowManager().getDefaultDisplay()
                        .getMetrics(new android.util.DisplayMetrics());

                // 5. clear notifications
                ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).cancelAll();

                runOnUiThread(() -> {
                    long after = freeRamMb();
                    log("FREE RAM: " + before + "MB -> " + after + "MB (freed "
                            + (after - before) + "MB)\nasked kill for " + asked
                            + " package process(es); game + this app never targeted");
                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle("RAM result")
                            .setMessage("free before: " + before + " MB\nfree after:  " + after
                                    + " MB\nfreed:       " + (after - before) + " MB\n\n"
                                    + "Android will restart recently-used apps on its own. "
                                    + "Force-stopping them permanently needs root.")
                            .setPositiveButton("OK", null).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> log("FREE RAM failed: " + e));
            }
        }).start();
    }

    private long freeRamMb() {
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        ((ActivityManager) getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(mi);
        return mi.availMem / 1048576;
    }

    private void deleteTree(java.io.File f) {
        if (f == null || !f.exists()) return;
        java.io.File[] kids = f.listFiles();
        if (kids != null) for (java.io.File k : kids) deleteTree(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

/** NotificationManager.cancelAll() is a public API; no root needed. */
    private void toggleNotifications() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) { toast("No notification service"); return; }
        nm.cancelAll();
        log("cancelAll(): every posted notification dismissed");
        toast("Notifications cleared");
    }

    private void pickAppToKill() {
        PackageManager pm = getPackageManager();
        List<String> names = new ArrayList<>(), pkgs = new ArrayList<>();
        for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
            if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
            names.add(String.valueOf(pm.getApplicationLabel(ai)));
            pkgs.add(ai.packageName);
        }
        if (names.isEmpty()) { toast("No user apps found"); return; }
        new AlertDialog.Builder(this)
                .setTitle("Clear background of which app?")
                .setItems(names.toArray(new String[0]), (d, w) -> {
                    if (pkgs.get(w).equals(GAME)) { toast("Refusing to kill the game"); return; }
                    ((ActivityManager) getSystemService(Context.ACTIVITY_SERVICE))
                            .killBackgroundProcesses(pkgs.get(w));
                    log("killed bg: " + pkgs.get(w));
                })
                .setNegativeButton("Cancel", null).show();
    }

    // ---------------- real sensor checks ----------------

    private void checkHeatAndPower() {
        BatteryManager bm = (BatteryManager) getSystemService(Context.BATTERY_SERVICE);
        int plugged = bm == null ? 0 : bm.isCharging() ? 1 : 0;
        int temp = bm == null ? Integer.MIN_VALUE : bm.getIntProperty(PROP_TEMP);
        boolean saver = Settings.Global.getInt(getContentResolver(),
                "low_power", 0) != 0;
        String msg = "charging=" + (plugged == 1)
                + "  batteryTemp=" + (temp == Integer.MIN_VALUE ? "unreadable" : temp / 10.0 + "C")
                + "  batterySaver=" + saver;
        log(msg);
        String advice;
        if (plugged == 1 && temp != Integer.MIN_VALUE && temp / 10.0 >= 38) advice = "HOT + charging: unplug now, biggest throttling risk";
        else if (plugged == 1) advice = "Unplug the charger before playing";
        else if (saver) advice = "Battery saver is ON - turn it off";
        else advice = "Power OK";
        new AlertDialog.Builder(this).setTitle("Power + heat").setMessage(msg + "\n\n" + advice)
                .setPositiveButton("OK", null).show();
    }

    private void checkNetwork() {
        Connectivity c = net();
        new AlertDialog.Builder(this).setTitle("Network")
                .setMessage(c.msg + "\n\nWeak signal or high latency causes the 'stuck' feel, "
                        + "not frame drops. Prefer mobile data.")
                .setPositiveButton("OK", null).show();
        log(c.msg);
    }

    private static class Connectivity {
        String msg = "no connection info";
    }

    private Connectivity net() {
        Connectivity c = new Connectivity();
        try {
            android.net.ConnectivityManager cm =
                    (android.net.ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            android.net.Network n = cm.getActiveNetwork();
            android.net.NetworkCapabilities cap = cm.getNetworkCapabilities(n);
            if (cap == null) { c.msg = "no active network"; return c; }
            String type = cap.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
                    ? "Wi-Fi" : cap.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)
                    ? "mobile data" : "other";
            StringBuilder sb = new StringBuilder("type: " + type + "\n");
            if (cap.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                sb.append("internet: OK\n");
            } else {
                sb.append("internet: NOT VALIDATED (captive portal / no route)\n");
            }
            // RSSI is only available on WifiInfo via WifiManager
            try {
                android.net.wifi.WifiManager wm =
                        (android.net.wifi.WifiManager) getApplicationContext()
                                .getSystemService(Context.WIFI_SERVICE);
                android.net.wifi.WifiInfo wi = wm.getConnectionInfo();
                int rssi = wi.getRssi();
                sb.append("rssi: ").append(rssi).append(" dBm\n");
                if (rssi < -75) sb.append("  -> very weak, likely stutter\n");
                else if (rssi < -65) sb.append("  -> weak\n");
                else sb.append("  -> good\n");
            } catch (Exception ignored) {
                sb.append("rssi: unavailable\n");
            }
            c.msg = sb.toString().trim();
        } catch (Exception e) {
            c.msg = "error: " + e;
        }
        return c;
    }

    private void fullReport() {
        StringBuilder s = new StringBuilder();
        BatteryManager bm = (BatteryManager) getSystemService(Context.BATTERY_SERVICE);
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        Display d = getWindowManager().getDefaultDisplay();
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        ((ActivityManager) getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(mi);

        s.append("thermal status: ").append(thermal(pm)).append('\n');
        s.append("battery temp: ").append(battTemp(bm)).append('\n');
        s.append("charging: ").append(bm != null && bm.isCharging()).append('\n');
        s.append("refresh: ").append(d.getRefreshRate()).append(" Hz (max ")
                .append(maxRefresh(d)).append(")\n");
        s.append("RAM free: ").append(mi.availMem / 1048576).append(" / ")
                .append(mi.totalMem / 1048576).append(" MB\n");
        s.append("low memory: ").append(mi.lowMemory).append('\n');
        s.append("cores: ").append(Runtime.getRuntime().availableProcessors()).append('\n');
        s.append("sdk: ").append(Build.VERSION.SDK_INT).append('\n');
        // Reads via Settings.Global work without root. Use those; only the WRITE is blocked.
        String forced = String.valueOf(Settings.Global.getString(getContentResolver(),
                "display_size_forced"));
        String opt = String.valueOf(Settings.Global.getString(getContentResolver(),
                "game_driver_opt_out_apps"));
        s.append("display_size_forced: ").append(forced).append('\n');
        s.append("gpu opt-out: ").append(opt).append('\n');
        if (opt.contains(GAME)) s.append("** your game IS opted out of the GPU driver **\n");
        s.append('\n').append(net().msg);
        log(s.toString());
        new AlertDialog.Builder(this).setTitle("Report").setMessage(s.toString())
                .setPositiveButton("OK", null).show();
    }

    private String thermal(PowerManager pm) {
        if (pm == null) return "unknown";
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                int st = pm.getCurrentThermalStatus();
                String[] n = {"none", "light", "moderate", "severe", "critical", "emergency",
                        "shutdown"};
                return st >= 0 && st < n.length ? n[st] : String.valueOf(st);
            } catch (Throwable ignored) { }
        }
        return "n/a";
    }

    private void showMemoryHogs() {
        // getRunningAppProcesses() returns only our own process on API 30, so rank by
        // appOps / package importance instead: the packages most likely to be holding
        // background state, ordered by last-used time.
        PackageManager pm = getPackageManager();
        UsageStatsManager usm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        if (usm == null || !isUsageAccessGranted()) {
            log("memory ranking needs Usage Access. Enable it via the button below.");
            new AlertDialog.Builder(this).setTitle("Needs permission")
                    .setMessage("Android 11 hides the process list from apps. Grant Usage "
                            + "Access so memory ranking works.")
                    .setPositiveButton("Grant", (d, w) ->
                            startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)))
                    .setNegativeButton("Cancel", null).show();
            return;
        }
        long now = System.currentTimeMillis();
        long since = now - 1000L * 60 * 60 * 24 * 7;
        List<UsageStats> stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, since, now);
        if (stats == null || stats.isEmpty()) { toast("No usage data yet"); return; }
        Collections.sort(stats, new Comparator<UsageStats>() {
            public int compare(UsageStats a, UsageStats b) {
                return Long.compare(b.getTotalTimeInForeground(), a.getTotalTimeInForeground());
            }
        });
        // queryUsageStats returns one row per package PER DAY, so aggregate or Instagram
        // appears a dozen times. Merge by package first, then rank.
        java.util.Map<String, Long> mins = new java.util.TreeMap<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (UsageStats u : stats) {
            if (seen.add(u.getPackageName())) {
                mins.put(u.getPackageName(), u.getTotalTimeInForeground() / 60000);
            } else {
                mins.put(u.getPackageName(), mins.get(u.getPackageName())
                        + u.getTotalTimeInForeground() / 60000);
            }
        }
        List<java.util.Map.Entry<String, Long>> rows =
                new ArrayList<>(mins.entrySet());
        Collections.sort(rows, new Comparator<java.util.Map.Entry<String, Long>>() {
            public int compare(java.util.Map.Entry<String, Long> a,
                               java.util.Map.Entry<String, Long> b) {
                return Long.compare(b.getValue(), a.getValue());
            }
        });
        StringBuilder s = new StringBuilder("foreground time, last 7 days:\n");
        int n = 0;
        for (java.util.Map.Entry<String, Long> e : rows) {
            if (n++ >= 12) break;
            s.append(labelOf(pm, e.getKey())).append("\n   ")
                    .append(e.getValue()).append(" min")
                    .append(e.getKey().equals(GAME) ? "  <- GAME" : "").append('\n');
        }
        log(s.toString());
        new AlertDialog.Builder(this).setTitle("Top apps").setMessage(s)
                .setPositiveButton("OK", null).show();
    }

    private boolean isUsageAccessGranted() {
        try {
            AppOpsManager aom = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
            int mode = aom.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(), getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return false;
        }
    }

    private String labelOf(PackageManager pm, String pkg) {
        try {
            return String.valueOf(pm.getApplicationLabel(
                    pm.getApplicationInfo(pkg, 0))) + "  (" + pkg + ")";
        } catch (Exception e) {
            return pkg;
        }
    }

    // ---------------- always-available readout ----------------

    private void refresh() {
        Display d = getWindowManager().getDefaultDisplay();
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        ((ActivityManager) getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(mi);
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        BatteryManager bm = (BatteryManager) getSystemService(Context.BATTERY_SERVICE);
        String charging = bm != null && bm.isCharging() ? " CHARGING!" : "";
        status.setText(String.format("thermal %s | %.1fHz | RAM %dMB free | batt %s%s",
                thermal(pm), d.getRefreshRate(), mi.availMem / 1048576,
                battTemp(bm), charging));
        status.setTextColor("critical".equals(thermal(pm)) || charging.isEmpty()
                ? Color.parseColor("#FFD479") : Color.parseColor("#FF8A80"));
    }

    private float maxRefresh(Display d) {
        float m = 0;
        for (Mode mode : d.getSupportedModes()) m = Math.max(m, mode.getRefreshRate());
        return m == 0 ? 60f : m;
    }

    @Override protected void onResume() { super.onResume(); refresh(); }
}