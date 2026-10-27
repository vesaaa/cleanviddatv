package io.github.vesaaa.cleanviddatv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.PowerManager;

/**
 * Brings Home back after an install replaced this app while it was Home. On the 65V1N-X the install kills the
 * process, Android starts Home again at once, and the install's last step kills that new process too: the Home
 * activity record stays on top with no process ("startProcessLocked but app pid is :0"), every Home press goes to
 * that dead record, and the screen stays black until the app is force-stopped. Once the install is done, the system
 * starts this app again for MY_PACKAGE_REPLACED and to rebind HomeKeyService; either one lands here and opens Home
 * in a fresh task, which replaces the dead record.
 */
public final class UpdateRelaunch extends BroadcastReceiver {
    /** Only right after an install: later starts of the process (a reboot, a crash) are not this case. */
    private static final long WINDOW_MS = 2 * 60 * 1000L;
    private static final String PREFS = "update_relaunch";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) check(context);
    }

    /** Opens Home once per install, if this app is Home, the screen is on and no Home of this process is alive. */
    static void check(Context ctx) {
        if (MainActivity.alive > 0) return;
        PackageManager pm = ctx.getPackageManager();
        long updated;
        try {
            updated = pm.getPackageInfo(ctx.getPackageName(), 0).lastUpdateTime;
        } catch (PackageManager.NameNotFoundException e) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now < updated || now - updated > WINDOW_MS) return;
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo current = pm.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
        if (current == null || !ctx.getPackageName().equals(current.activityInfo.packageName)) return;
        PowerManager power = ctx.getSystemService(PowerManager.class);
        if (power != null && !power.isInteractive()) return;   // the screen is off: Home starts when it comes on
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (p.getLong("done_for", 0) == updated) return;
        p.edit().putLong("done_for", updated).apply();
        ctx.startActivity(home.setClass(ctx, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
    }
}
