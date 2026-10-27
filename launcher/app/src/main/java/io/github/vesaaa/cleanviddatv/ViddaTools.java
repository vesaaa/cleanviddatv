package io.github.vesaaa.cleanviddatv;

import android.Manifest;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.os.SystemClock;
import android.provider.Settings;

import java.io.File;
import java.io.IOException;
import java.net.Inet4Address;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The two Vidda tools in the launcher's settings:
 *
 * ADB switch. Opening developer options on a China-market Hisense / Vidda set is hard (factory menu), so the
 * launcher keeps a switch for it. It writes Settings.Global.ADB_ENABLED, which needs WRITE_SECURE_SETTINGS
 * (granted with pm grant by install-vidda.sh). MT9653 firmware runs adbd with ro.adb.secure=0 on port 5555: anyone
 * on the network gets a shell while it is on, so the switch turns itself off after ADB_AUTO_OFF_MS and at the next
 * boot.
 *
 * Restore. Puts the stock home (聚好看) and everything install-vidda.sh disabled back, without a computer: the
 * launcher turns ADB on, connects to its own adbd (LocalAdb) and runs `pm enable` as the shell user, which an app
 * cannot do itself. install-vidda.sh records what to restore in Settings.Global (RESTORE_* keys).
 */
final class ViddaTools {

    /** Comma-separated packages install-vidda.sh disabled (the stock home and the second vendor home included). */
    static final String RESTORE_LIST = "cleanviddatv_disabled";
    /** The stock home component Home pointed to before, e.g. com.jamdeo.tv.vod/com.ju.main.launcher.LauncherActivity. */
    static final String RESTORE_HOME = "cleanviddatv_stock_home";
    /** enabled_accessibility_services before install-vidda.sh set it to this launcher's helper alone. */
    static final String RESTORE_A11Y = "cleanviddatv_a11y_before";

    static final String RESTORED_MARK = "CLEANVIDDATV_RESTORED";
    static final long ADB_AUTO_OFF_MS = 30 * 60 * 1000L;
    static final int ADB_PORT = 5555;

    private static final String PREFS = "vidda_tools";
    private static final String PREF_ADB_BY_SWITCH = "adb_on_by_switch";
    private static final String PREF_ADB_BOOT = "adb_boot";

    private static final Pattern PACKAGE = Pattern.compile("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+");
    private static final Pattern COMPONENT = Pattern.compile("[A-Za-z][A-Za-z0-9_.]*/[A-Za-z0-9_.$]+");
    private static final Pattern SERVICES = Pattern.compile("[A-Za-z0-9_.$/:]*");

    private ViddaTools() { }

    // ---------------------------------------------------------------- ADB switch

    static boolean adbOn(Context context) {
        return Settings.Global.getInt(context.getContentResolver(), Settings.Global.ADB_ENABLED, 0) == 1;
    }

    /** The switch works only with WRITE_SECURE_SETTINGS, which install-vidda.sh grants. */
    static boolean canSwitchAdb(Context context) {
        return context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED;
    }

    /** Turns ADB on (armed to turn itself off again) or off. Throws SecurityException without the permission. */
    static void setAdb(Context context, boolean on) {
        ContentResolver resolver = context.getContentResolver();
        Settings.Global.putInt(resolver, Settings.Global.ADB_ENABLED, on ? 1 : 0);
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        AlarmManager alarms = context.getSystemService(AlarmManager.class);
        PendingIntent off = PendingIntent.getBroadcast(context, 0, new Intent(context, AdbOffReceiver.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        if (on) {
            prefs.edit().putBoolean(PREF_ADB_BY_SWITCH, true)
                    .putInt(PREF_ADB_BOOT, Settings.Global.getInt(resolver, Settings.Global.BOOT_COUNT, -1)).apply();
            if (alarms != null) alarms.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + ADB_AUTO_OFF_MS, off);
        } else {
            prefs.edit().remove(PREF_ADB_BY_SWITCH).remove(PREF_ADB_BOOT).apply();
            if (alarms != null) alarms.cancel(off);
        }
    }

    /** At boot: ADB that the switch turned on before the restart goes off again (the alarm did not survive it). */
    static void adbOffAfterReboot(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!prefs.getBoolean(PREF_ADB_BY_SWITCH, false) || !canSwitchAdb(context)) return;
        int boot = Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        if (boot == prefs.getInt(PREF_ADB_BOOT, boot)) return;
        try {
            setAdb(context, false);
        } catch (SecurityException ignored) {
        }
    }

    /** The alarm that ends the ADB_AUTO_OFF_MS window. */
    public static final class AdbOffReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            try {
                setAdb(context, false);
            } catch (SecurityException ignored) {
            }
        }
    }

    /** The TV's IPv4 address on the active network, for "connect to <ip>:5555"; empty when there is none. */
    static String address(Context context) {
        ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
        if (cm == null) return "";
        Network network = cm.getActiveNetwork();
        LinkProperties link = network == null ? null : cm.getLinkProperties(network);
        if (link == null) return "";
        for (LinkAddress a : link.getLinkAddresses()) {
            if (a.getAddress() instanceof Inet4Address) return a.getAddress().getHostAddress();
        }
        return "";
    }

    // ---------------------------------------------------------------- Restore

    /** install-vidda.sh left a record, so there is something to restore. */
    static boolean canRestore(Context context) {
        String list = Settings.Global.getString(context.getContentResolver(), RESTORE_LIST);
        return list != null && !list.trim().isEmpty() && canSwitchAdb(context);
    }

    /**
     * The shell script that undoes install-vidda.sh. The stock home goes first, so the TV has a Home even if the
     * script stops half way; anything that does not look like a package or component name is skipped.
     */
    static String restoreScript(String list, String home, String a11y) {
        List<String> packages = new ArrayList<>();
        String homePackage = home != null && COMPONENT.matcher(home).matches() ? home.substring(0, home.indexOf('/')) : "";
        if (!homePackage.isEmpty()) packages.add(homePackage);
        if (list != null) {
            for (String p : list.split(",")) {
                p = p.trim();
                if (PACKAGE.matcher(p).matches() && !packages.contains(p)) packages.add(p);
            }
        }
        StringBuilder s = new StringBuilder();
        for (String p : packages) s.append("pm enable --user 0 ").append(p).append(" >/dev/null 2>&1; ");
        if (a11y == null || a11y.isEmpty() || "null".equals(a11y) || !SERVICES.matcher(a11y).matches()) {
            s.append("settings delete secure enabled_accessibility_services >/dev/null 2>&1; ");
        } else {
            s.append("settings put secure enabled_accessibility_services '").append(a11y).append("'; ");
        }
        if (!homePackage.isEmpty()) {
            s.append("cmd package set-home-activity --user 0 ").append(home).append(" >/dev/null 2>&1; ");
        }
        s.append("settings delete global ").append(RESTORE_LIST).append("; ");
        s.append("settings delete global ").append(RESTORE_HOME).append("; ");
        s.append("settings delete global ").append(RESTORE_A11Y).append("; ");
        s.append("echo ").append(RESTORED_MARK);
        return s.toString();
    }

    /**
     * Runs the restore. Blocking (network and waiting for adbd): call it off the main thread. Returns null on
     * success, or the reason it failed. ADB goes back to the state it was in before.
     */
    static String restore(Context context) {
        ContentResolver resolver = context.getContentResolver();
        String script = restoreScript(Settings.Global.getString(resolver, RESTORE_LIST),
                Settings.Global.getString(resolver, RESTORE_HOME), Settings.Global.getString(resolver, RESTORE_A11Y));
        boolean wasOn = adbOn(context);
        try {
            if (!wasOn) setAdb(context, true);
            String out = runShell(context, script);
            return out.contains(RESTORED_MARK) ? null : out.trim();
        } catch (SecurityException e) {
            return context.getString(R.string.restore_err_permission);
        } catch (java.net.ConnectException | java.net.SocketTimeoutException e) {
            return context.getString(R.string.restore_err_connect);
        } catch (IOException e) {
            return context.getString(R.string.restore_err_adb, String.valueOf(e.getMessage()));
        } finally {
            if (!wasOn) {
                try {
                    setAdb(context, false);
                } catch (SecurityException ignored) {
                }
            }
        }
    }

    /** adbd needs a moment after ADB_ENABLED flips, so the first connections may be refused. */
    private static String runShell(Context context, String command) throws IOException {
        KeyPair key = adbKey(context);
        IOException last = null;
        for (int attempt = 0; attempt < 10; attempt++) {
            try (LocalAdb adb = LocalAdb.connect("127.0.0.1", ADB_PORT, key, 60_000)) {
                return adb.shell(command);
            } catch (IOException e) {
                last = e;
                SystemClock.sleep(1000);
            }
        }
        throw last;
    }

    /** This app's own ADB key, made once and kept in its private files. */
    static KeyPair adbKey(Context context) throws IOException {
        File priv = new File(context.getNoBackupFilesDir(), "adbkey");
        File pub = new File(context.getNoBackupFilesDir(), "adbkey.pub");
        try {
            KeyFactory rsa = KeyFactory.getInstance("RSA");
            if (priv.isFile() && pub.isFile()) {
                return new KeyPair(rsa.generatePublic(new X509EncodedKeySpec(Files.readAllBytes(pub.toPath()))),
                        rsa.generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(priv.toPath()))));
            }
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair key = generator.generateKeyPair();
            Files.write(priv.toPath(), key.getPrivate().getEncoded());
            Files.write(pub.toPath(), key.getPublic().getEncoded());
            return key;
        } catch (GeneralSecurityException e) {
            throw new IOException("cannot make an adb key", e);
        }
    }
}
