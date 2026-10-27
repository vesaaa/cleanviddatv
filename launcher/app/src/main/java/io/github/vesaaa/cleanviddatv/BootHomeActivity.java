package io.github.vesaaa.cleanviddatv;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.UserManager;
import android.view.View;

/**
 * Home while the user is still locked at boot, like AOSP's FallbackHome. MainActivity reads credential-protected
 * storage (settings, themes), so it is not direct-boot aware and Android cannot start it before the unlock. With
 * the stock launcher (聚好看) disabled, a Hisense / Vidda MT9653 set then had no Home it could start at all: user 0
 * stayed BOOTING and sys.boot_completed never came (examples/hisense-e5q-pro/README.md, "Cold boot and Home
 * recovery"). This activity is direct-boot aware, touches no credential storage, shows a black screen, and opens
 * MainActivity as soon as the user is unlocked. Its HOME filter has priority -999: above FallbackHome (-1000), so no
 * chooser at boot, and below MainActivity, which stays the default Home once unlocked.
 */
public final class BootHomeActivity extends Activity {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private BroadcastReceiver unlocked;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        View black = new View(this);
        black.setBackgroundColor(Color.BLACK);
        setContentView(black);
        if (isUnlocked()) {
            openHome();
            return;
        }
        unlocked = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                openHome();
            }
        };
        registerReceiver(unlocked, new IntentFilter(Intent.ACTION_USER_UNLOCKED));
        // As FallbackHome does: the broadcast can arrive before the receiver is registered.
        handler.postDelayed(this::checkUnlocked, 500);
    }

    private void checkUnlocked() {
        if (isFinishing()) return;
        if (isUnlocked()) openHome();
        else handler.postDelayed(this::checkUnlocked, 500);
    }

    private boolean isUnlocked() {
        UserManager users = getSystemService(UserManager.class);
        return users == null || users.isUserUnlocked();
    }

    /** The default Home once unlocked: MainActivity normally, the stock launcher after the restore put it back. */
    private void openHome() {
        if (isFinishing()) return;
        handler.removeCallbacksAndMessages(null);
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo resolved = getPackageManager().resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
        String target = resolved == null || resolved.activityInfo == null ? null
                : resolved.activityInfo.packageName + "/" + resolved.activityInfo.name;
        // This activity itself, or Android's chooser ("android"): no usable default, so this launcher.
        if (target == null || target.startsWith("android/") || target.endsWith("." + getClass().getSimpleName())) {
            home.setClass(this, MainActivity.class);
        } else {
            home.setClassName(resolved.activityInfo.packageName, resolved.activityInfo.name);
        }
        startActivity(home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
        finish();
        overridePendingTransition(0, 0);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (unlocked != null) {
            unregisterReceiver(unlocked);
            unlocked = null;
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        // Nothing behind Home at boot.
    }
}
