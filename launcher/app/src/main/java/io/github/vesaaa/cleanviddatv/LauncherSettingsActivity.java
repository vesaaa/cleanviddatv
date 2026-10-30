package io.github.vesaaa.cleanviddatv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.provider.Settings;
import android.view.FocusFinder;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Remote-friendly settings owned by the launcher; system settings stay one row away. Categories run down the left;
 * the focused one's rows show on the right. Right goes into a page, Left from a page's first column comes back.
 */
public final class LauncherSettingsActivity extends Activity {
    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));   // locale
    }

    static final String EXTRA_REORDER = "io.github.vesaaa.cleanviddatv.extra.REORDER_FROM_SETTINGS";
    private SharedPreferences choices;
    private final TextView[] columnChoices = new TextView[4];
    private final TextView[] rowChoices = new TextView[4];
    private static final int[] DOCK_OPACITIES = {100, 70, 50, 30};
    private final TextView[] dockChoices = new TextView[DOCK_OPACITIES.length];
    private SettingsItem wallpaperChoice;
    private SettingsItem animationsChoice;
    private SettingsItem advancedChoice;
    private SettingsItem followThemeChoice;
    private SettingsItem openOnStartChoice;
    private SettingsItem adbChoice;
    private SettingsItem updateChoice, updateBetaChoice;
    private SettingsItem parallaxChoice, clock24hChoice, screensaverChoice, screensaverStyleChoice;
    private SettingsItem topShelfChoice, vstvChoice, feiniuChoice;
    private boolean parallax, clock24h, topShelf;
    private static final String[] VSTV_PACKAGES = {"com.vesaa.mytv", "com.chinablue.tv"};
    private static final int[] NAV_IDS = {R.id.navLayout, R.id.navLook, R.id.navClock, R.id.navApps, R.id.navHome,
            R.id.navUpdate, R.id.navTools};
    private static final int[] PAGE_IDS = {R.id.pageLayout, R.id.pageLook, R.id.pageClock, R.id.pageApps,
            R.id.pageHome, R.id.pageUpdate, R.id.pageTools};
    private static final String STATE_PAGE = "page";
    private final View[] navs = new View[NAV_IDS.length];
    private final ViewGroup[] pages = new ViewGroup[PAGE_IDS.length];
    private int page;
    /** The row last focused on each page, so Right goes back to it. */
    private final View[] lastInPage = new View[PAGE_IDS.length];
    private ScrollView scroll;
    private int screensaverMinutes;
    private String screensaverStyle;
    private static final int[] SCREENSAVER_MINUTES = {0, 5, 10, 15, 30, 60};
    /** An update check or download is running: further presses wait for it. */
    private boolean updateBusy;
    private int columns;
    private int rowsBelowDock;
    private int dockOpacity;
    private boolean animations;
    private boolean advancedEffects;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_launcher_settings);
        choices = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        Config config = Config.load(this);
        scroll = findViewById(R.id.settingsScroll);
        ((TextView) findViewById(R.id.settingsVersion)).setText(getString(R.string.settings_version,
                Updater.currentVersion(this)));
        for (int i = 0; i < NAV_IDS.length; i++) {
            final int index = i;
            navs[i] = findViewById(NAV_IDS[i]);
            pages[i] = findViewById(PAGE_IDS[i]);
            navs[i].setOnFocusChangeListener((v, focused) -> {
                if (focused) showPage(index);
            });
            navs[i].setOnClickListener(v -> enterPage());
        }
        // Remember the focused row of each page.
        getWindow().getDecorView().getViewTreeObserver().addOnGlobalFocusChangeListener((oldFocus, newFocus) -> {
            if (newFocus != null && isInside(newFocus, pages[page])) lastInPage[page] = newFocus;
        });
        View reorderChoice = findViewById(R.id.reorderChoice);
        reorderChoice.setVisibility(config.launcherSettingsButton ? View.VISIBLE : View.GONE);
        reorderChoice.setOnClickListener(v -> {
            setResult(RESULT_OK, new Intent().putExtra(EXTRA_REORDER, true));
            finish();
        });
        columns = config.columns;
        rowsBelowDock = config.rowsBelowDock;
        dockOpacity = config.dockOpacity;
        animations = config.animations;
        advancedEffects = config.animations && config.advancedEffects;

        int[] ids = {R.id.columns4, R.id.columns5, R.id.columns6, R.id.columns7};
        for (int i = 0; i < ids.length; i++) {
            final int value = i + 4;
            columnChoices[i] = findViewById(ids[i]);
            columnChoices[i].setOnClickListener(v -> {
                if (columns == value) return;
                columns = value;
                choices.edit().putInt("columns", value).apply();
                changed();
                refresh();
            });
        }
        int[] rowIds = {R.id.rows0, R.id.rows1, R.id.rows2, R.id.rows3};
        for (int i = 0; i < rowIds.length; i++) {
            final int value = i;
            rowChoices[i] = findViewById(rowIds[i]);
            rowChoices[i].setOnClickListener(v -> {
                if (rowsBelowDock == value) return;
                rowsBelowDock = value;
                choices.edit().putInt(Config.ROWS_BELOW_DOCK_KEY, value).apply();
                changed();
                refresh();
            });
        }
        int[] dockIds = {R.id.dock100, R.id.dock70, R.id.dock50, R.id.dock30};
        for (int i = 0; i < dockIds.length; i++) {
            final int value = DOCK_OPACITIES[i];
            dockChoices[i] = findViewById(dockIds[i]);
            dockChoices[i].setOnClickListener(v -> {
                if (dockOpacity == value) return;
                dockOpacity = value;
                choices.edit().putInt(Config.DOCK_OPACITY_KEY, value).apply();
                changed();
                refresh();
            });
        }
        // Mirror text alignment without globally opting the launcher into RTL. Numeric rows
        // still need explicit ordering in Android's no-supportsRtl compatibility mode.
        if (getResources().getBoolean(R.bool.rtl_menu_bar))
            findViewById(android.R.id.content).setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        if (getResources().getBoolean(R.bool.rtl_app_grid)) {
            reverseRow(columnChoices);
            reverseRow(rowChoices);
            reverseRow(dockChoices);
        }
        wallpaperChoice = findViewById(R.id.wallpaperChoice);
        wallpaperChoice.setOnClickListener(v -> showWallpaperPicker());
        animationsChoice = findViewById(R.id.animationsChoice);
        animationsChoice.setOnClickListener(v -> {
            animations = !animations;
            if (!animations) advancedEffects = false;
            choices.edit().putBoolean("animations", animations)
                    .putBoolean("advanced_effects", advancedEffects).apply();
            changed();
            refresh();
        });
        advancedChoice = findViewById(R.id.advancedChoice);
        advancedChoice.setOnClickListener(v -> {
            if (advancedEffects) {
                setAdvanced(false);
            } else {
                new AlertDialog.Builder(this)
                        .setTitle(R.string.advanced_effects)
                        .setMessage(R.string.advanced_effects_warning)
                        .setPositiveButton(R.string.enable, (dialog, which) -> setAdvanced(true))
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            }
        });
        parallax = config.parallaxTiles;
        clock24h = config.clock24h;
        screensaverMinutes = config.homeIdleClockMinutes;
        screensaverStyle = config.screensaverStyle;
        parallaxChoice = findViewById(R.id.parallaxChoice);
        parallaxChoice.setOnClickListener(v -> {
            parallax = !parallax;
            choices.edit().putBoolean(Config.PARALLAX_KEY, parallax).apply();
            changed();
            refresh();
        });
        clock24hChoice = findViewById(R.id.clock24hChoice);
        clock24hChoice.setOnClickListener(v -> {
            clock24h = !clock24h;
            choices.edit().putBoolean(Config.CLOCK_24H_KEY, clock24h).apply();
            changed();
            refresh();
        });
        screensaverChoice = findViewById(R.id.screensaverChoice);
        screensaverChoice.setOnClickListener(v -> showScreensaverPicker());
        screensaverStyleChoice = findViewById(R.id.screensaverStyleChoice);
        screensaverStyleChoice.setOnClickListener(v -> {
            screensaverStyle = Config.SCREENSAVER_PHOTOS.equals(screensaverStyle)
                    ? Config.SCREENSAVER_CLOCK : Config.SCREENSAVER_PHOTOS;
            choices.edit().putString(Config.SCREENSAVER_STYLE_KEY, screensaverStyle).apply();
            refresh();
        });
        topShelf = config.topShelf;
        topShelfChoice = findViewById(R.id.topShelfChoice);
        topShelfChoice.setOnClickListener(v -> {
            topShelf = !topShelf;
            choices.edit().putBoolean(Config.TOP_SHELF_KEY, topShelf).apply();
            changed();
            refresh();
        });
        vstvChoice = findViewById(R.id.vstvChoice);
        vstvChoice.setOnClickListener(v -> openVsTv());
        feiniuChoice = findViewById(R.id.feiniuChoice);
        feiniuChoice.setOnClickListener(v -> startActivity(new Intent(this, FeiniuSettingsActivity.class)));
        openOnStartChoice = findViewById(R.id.openOnStartChoice);
        if (config.openOnStartChoice) {
            openOnStartChoice.setVisibility(View.VISIBLE);
            openOnStartChoice.setOnClickListener(v -> showOpenOnStartPicker());
        }
        followThemeChoice = findViewById(R.id.followThemeChoice);
        followThemeChoice.setOnClickListener(v -> {
            if (!hasOnTvChoices()) return;
            choices.edit().remove("columns").remove(Config.ROWS_BELOW_DOCK_KEY).remove("animations")
                    .remove("advanced_effects").remove(Config.WALLPAPER_CHOICE_KEY)
                    .remove(Config.DOCK_OPACITY_KEY).remove(OpenOnStart.KEY).remove(Config.PARALLAX_KEY)
                    .remove(Config.CLOCK_24H_KEY).remove(Config.SCREENSAVER_MINUTES_KEY)
                    .remove(Config.SCREENSAVER_STYLE_KEY).remove(Config.TOP_SHELF_KEY).commit();
            Config current = Config.load(this);
            columns = current.columns;
            rowsBelowDock = current.rowsBelowDock;
            dockOpacity = current.dockOpacity;
            animations = current.animations;
            advancedEffects = current.animations && current.advancedEffects;
            parallax = current.parallaxTiles;
            clock24h = current.clock24h;
            topShelf = current.topShelf;
            screensaverMinutes = current.homeIdleClockMinutes;
            screensaverStyle = current.screensaverStyle;
            changed();
            refresh();
        });
        findViewById(R.id.defaultHomeChoice).setOnClickListener(v -> {
            Toast.makeText(this, R.string.home_settings_help, Toast.LENGTH_LONG).show();
            open(new Intent(Settings.ACTION_HOME_SETTINGS));
        });
        findViewById(R.id.tvSettingsChoice).setOnClickListener(v ->
                open(SystemSettings.intent(this, Settings.ACTION_SETTINGS)));
        adbChoice = findViewById(R.id.adbChoice);
        adbChoice.setOnClickListener(v -> toggleAdb());
        findViewById(R.id.restoreChoice).setOnClickListener(v -> confirmRestore());
        updateChoice = findViewById(R.id.updateChoice);
        updateChoice.setOnClickListener(v -> checkForUpdate());
        updateBetaChoice = findViewById(R.id.updateBetaChoice);
        updateBetaChoice.setOnClickListener(v -> {
            Updater.prefs(this).edit().putBoolean(Updater.KEY_BETA, !Updater.beta(this))
                    .putString(Updater.KEY_AVAILABLE, "").apply();
            refresh();
        });
        refresh();
        int start = state == null ? 0 : Math.max(0, Math.min(state.getInt(STATE_PAGE, 0), NAV_IDS.length - 1));
        showPage(start);
        navs[start].requestFocus();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt(STATE_PAGE, page);
    }

    /** The category's rows on the right; the category stays marked while focus is in them. */
    private void showPage(int index) {
        page = index;
        for (int i = 0; i < pages.length; i++) {
            pages[i].setVisibility(i == index ? View.VISIBLE : View.GONE);
            navs[i].setSelected(i == index);
        }
        scroll.scrollTo(0, 0);
    }

    /** Right (or OK) on a category: into its page, at the row last used there. */
    private void enterPage() {
        View target = lastInPage[page];
        if (target == null || !target.isShown() || !target.isFocusable() || !target.isEnabled()) {
            target = FocusFinder.getInstance().findNextFocus(pages[page], null, View.FOCUS_DOWN);
        }
        if (target != null) target.requestFocus();
    }

    private static boolean isInside(View v, ViewGroup group) {
        for (Object p = v.getParent(); p instanceof View; p = ((View) p).getParent()) if (p == group) return true;
        return false;
    }

    /**
     * Right from a category enters its page; Left from a page where nothing lies further left goes back to the
     * category; Up and Down stay inside the page (the categories are not below or above its rows).
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        View focus = getCurrentFocus();
        if (event.getAction() != KeyEvent.ACTION_DOWN || focus == null) return super.dispatchKeyEvent(event);
        boolean rtl = getResources().getBoolean(R.bool.rtl_menu_bar);
        int code = event.getKeyCode();
        int into = rtl ? KeyEvent.KEYCODE_DPAD_LEFT : KeyEvent.KEYCODE_DPAD_RIGHT;
        int back = rtl ? KeyEvent.KEYCODE_DPAD_RIGHT : KeyEvent.KEYCODE_DPAD_LEFT;
        int navIndex = -1;
        for (int i = 0; i < navs.length; i++) if (navs[i] == focus) navIndex = i;
        if (navIndex >= 0) {
            if (code == into) {
                enterPage();
                return true;
            }
            if (code == back) return true;
            return super.dispatchKeyEvent(event);
        }
        if (!isInside(focus, pages[page])) return super.dispatchKeyEvent(event);
        int dir = code == back ? (rtl ? View.FOCUS_RIGHT : View.FOCUS_LEFT)
                : code == into ? (rtl ? View.FOCUS_LEFT : View.FOCUS_RIGHT)
                : code == KeyEvent.KEYCODE_DPAD_UP ? View.FOCUS_UP
                : code == KeyEvent.KEYCODE_DPAD_DOWN ? View.FOCUS_DOWN : 0;
        if (dir == 0) return super.dispatchKeyEvent(event);
        View next = FocusFinder.getInstance().findNextFocus(pages[page], focus, dir);
        if (next != null) {
            next.requestFocus(dir);
            return true;
        }
        if (code == back) navs[page].requestFocus();
        return true;     // the page's edge: stay
    }

    @Override
    public void onBackPressed() {
        View focus = getCurrentFocus();
        if (focus != null && isInside(focus, pages[page])) {
            navs[page].requestFocus();
            return;
        }
        super.onBackPressed();
    }

    /** VsTV opens when it is installed; otherwise the row says so. */
    private void openVsTv() {
        for (String pkg : VSTV_PACKAGES) {
            Intent launch = getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
            if (launch == null) launch = getPackageManager().getLaunchIntentForPackage(pkg);
            if (launch != null) {
                open(launch);
                return;
            }
        }
        Toast.makeText(this, R.string.app_not_installed, Toast.LENGTH_SHORT).show();
    }

    /** Connected (it publishes a shelf), installed but too old, or not installed. */
    private String vstvState() {
        Map<String, android.content.pm.ProviderInfo> shelves = ShelfSource.providers(this);
        boolean installed = false;
        for (String pkg : VSTV_PACKAGES) {
            if (shelves.containsKey(pkg)) return getString(R.string.vstv_connected);
            try {
                getPackageManager().getPackageInfo(pkg, 0);
                installed = true;
            } catch (PackageManager.NameNotFoundException ignored) {
                // not this flavour
            }
        }
        return getString(installed ? R.string.vstv_update : R.string.app_not_installed);
    }

    private String feiniuState() {
        if (!Feiniu.signedIn(this)) return getString(R.string.feiniu_signed_out);
        String who = Feiniu.user(this) + " @ " + Feiniu.display(Feiniu.base(this));
        return FeiniuShelfProvider.appPackage(this) == null ? getString(R.string.feiniu_no_app) : who;
    }

    /** The choices in reverse, after their caption, which stays first. */
    private void reverseRow(TextView[] views) {
        LinearLayout row = (LinearLayout) views[0].getParent();
        for (TextView v : views) row.removeView(v);
        for (int i = views.length - 1; i >= 0; i--) row.addView(views[i]);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Back from the wallpaper gallery or 飞牛影视 settings: show what changed on the rows.
        if (wallpaperChoice != null) refresh();
    }

    /** The gallery: thumbnails, a full-screen preview of the focused one, and the online pictures. */
    private void showWallpaperPicker() {
        startActivity(new Intent(this, WallpaperGalleryActivity.class));
    }

    /** The current wallpaper choice as the picker names it. */
    private String wallpaperLabel(String choice) {
        if (choice == null) return getString(R.string.wallpaper_theme_default);
        if (WallpaperRotation.CHOICE_NONE.equals(choice)) return getString(R.string.wallpaper_none);
        if (BuiltinWallpapers.PHOTOS.equals(choice)) return getString(R.string.wallpaper_builtin_photos);
        if (BuiltinWallpapers.ART.equals(choice)) return getString(R.string.wallpaper_builtin_art);
        if (OnlineWallpapers.GROUP.equals(choice)) return getString(R.string.wallpaper_downloaded_rotate);
        for (File dir : new File[]{BuiltinWallpapers.dir(this), OnlineWallpapers.dir(this)}) {
            File f = new File(dir, choice);
            if (f.isFile()) return BuiltinWallpapers.label(WallpaperCaptions.read(dir), f);
        }
        return choice;
    }

    /** Checks for a newer version, then offers it; the download and the install follow on the viewer's yes. */
    private void checkForUpdate() {
        if (updateBusy) return;
        updateBusy = true;
        updateChoice.setValue(getString(R.string.update_checking), false);
        new Thread(() -> {
            Updates.Release release;
            String error = null;
            try {
                release = Updater.check(this);
            } catch (java.io.IOException e) {
                release = null;
                error = getString(R.string.update_err_network, String.valueOf(e.getMessage()));
            }
            Updates.Release found = release;
            String failure = error;
            runOnUiThread(() -> {
                updateBusy = false;
                refresh();
                if (isFinishing()) return;
                if (failure != null) {
                    showUpdateError(failure);
                } else if (found == null) {
                    Toast.makeText(this, R.string.update_latest, Toast.LENGTH_SHORT).show();
                } else {
                    new AlertDialog.Builder(this)
                            .setTitle(getString(R.string.update_found_title, found.version))
                            .setMessage((found.notes.isEmpty() ? "" : found.notes + "\n\n") + getString(R.string.update_found_notes))
                            .setPositiveButton(R.string.update_now, (d, w) -> downloadUpdate(found))
                            .setNegativeButton(R.string.update_later, null)
                            .show();
                }
            });
        }, "update-check").start();
    }

    private void downloadUpdate(Updates.Release release) {
        updateBusy = true;
        AlertDialog progress = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.update_found_title, release.version))
                .setMessage(getString(R.string.update_downloading, 0))
                .setCancelable(false)
                .show();
        new Thread(() -> {
            String error = null;
            java.io.File apk = null;
            try {
                apk = Updater.download(this, release,
                        percent -> runOnUiThread(() -> progress.setMessage(getString(R.string.update_downloading, percent))));
            } catch (java.io.IOException | RuntimeException e) {
                error = String.valueOf(e.getMessage());
            }
            String failure = error;
            java.io.File downloaded = apk;
            runOnUiThread(() -> {
                updateBusy = false;
                progress.dismiss();
                if (isFinishing()) return;
                if (failure != null) {
                    showUpdateError(failure);
                    return;
                }
                Intent install = Updater.installIntent(this, downloaded);
                try {
                    if (install == null) throw new ActivityNotFoundException();
                    startActivity(install);
                } catch (ActivityNotFoundException | SecurityException e) {
                    showUpdateError(getString(R.string.update_err_install, getString(R.string.update_err_no_installer)));
                }
            });
        }, "update-download").start();
    }

    private void showUpdateError(String message) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.update_failed_title)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    /** Off, then the idle times; saved at once, Home picks it up when it comes back. */
    private void showScreensaverPicker() {
        String[] labels = new String[SCREENSAVER_MINUTES.length];
        int checked = -1;
        for (int i = 0; i < labels.length; i++) {
            labels[i] = screensaverLabel(SCREENSAVER_MINUTES[i]);
            if (SCREENSAVER_MINUTES[i] == screensaverMinutes) checked = i;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.screensaver_label)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    dialog.dismiss();
                    screensaverMinutes = SCREENSAVER_MINUTES[which];
                    choices.edit().putInt(Config.SCREENSAVER_MINUTES_KEY, screensaverMinutes).apply();
                    changed();
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private String screensaverLabel(int minutes) {
        return minutes <= 0 ? getString(R.string.off) : getString(R.string.screensaver_after, minutes);
    }

    /** Off, then every app with a TV entry point by name. Saved at once; it is read on the next boot. */
    private void showOpenOnStartPicker() {
        PackageManager pm = getPackageManager();
        List<String[]> apps = new ArrayList<>();
        Intent tv = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        for (ResolveInfo r : pm.queryIntentActivities(tv, 0)) {
            String pkg = r.activityInfo.packageName;
            if (pkg.equals(getPackageName())) continue;
            boolean seen = false;
            for (String[] a : apps) seen |= a[1].equals(pkg);
            if (!seen) apps.add(new String[]{r.loadLabel(pm).toString(), pkg});
        }
        Collections.sort(apps, (a, b) -> a[0].compareToIgnoreCase(b[0]));
        String[] labels = new String[apps.size() + 1];
        labels[0] = getString(R.string.off);
        String current = choices.getString(OpenOnStart.KEY, "");
        int checked = 0;
        for (int i = 0; i < apps.size(); i++) {
            labels[i + 1] = apps.get(i)[0];
            if (apps.get(i)[1].equals(current)) checked = i + 1;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.open_on_start_label)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    dialog.dismiss();
                    if (which == 0) choices.edit().remove(OpenOnStart.KEY).apply();
                    else {
                        OpenOnStart.skipThisBoot(this);
                        choices.edit().putString(OpenOnStart.KEY, apps.get(which - 1)[1]).apply();
                    }
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** The picked app's name, its package when it is gone, or Off. */
    private String openOnStartLabel() {
        String pkg = choices.getString(OpenOnStart.KEY, "");
        if (pkg.isEmpty()) return getString(R.string.off);
        try {
            PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    /**
     * A choice was saved. Home rebuilds when it next resumes because the change count moved, whether these
     * settings are left with Back or with the remote's Home button (which cancels the activity result).
     */
    private void changed() {
        setResult(RESULT_OK);
        choices.edit().putLong(Config.SETTINGS_REVISION_KEY,
                choices.getLong(Config.SETTINGS_REVISION_KEY, 0) + 1).apply();
    }

    private void setAdvanced(boolean enabled) {
        advancedEffects = enabled;
        choices.edit().putBoolean("advanced_effects", enabled).apply();
        changed();
        refresh();
    }

    private void refresh() {
        for (int i = 0; i < columnChoices.length; i++) {
            columnChoices[i].setSelected(columns == i + 4);
        }
        for (int i = 0; i < rowChoices.length; i++) {
            rowChoices[i].setSelected(rowsBelowDock == i);
        }
        for (int i = 0; i < dockChoices.length; i++) {
            dockChoices[i].setSelected(dockOpacity == DOCK_OPACITIES[i]);
        }
        animationsChoice.setToggle(animations);
        advancedChoice.setToggle(advancedEffects);
        enable(advancedChoice, animations);
        // Parallax needs the advanced effects, like the rest of the tvOS motion.
        parallaxChoice.setToggle(parallax && advancedEffects);
        enable(parallaxChoice, advancedEffects);
        clock24hChoice.setToggle(clock24h);
        screensaverChoice.setValue(screensaverLabel(screensaverMinutes), true);
        screensaverStyleChoice.setValue(getString(Config.SCREENSAVER_CLOCK.equals(screensaverStyle)
                ? R.string.screensaver_clock : R.string.screensaver_photos), false);
        enable(screensaverStyleChoice, screensaverMinutes > 0);
        String wallpaperCurrent = choices.getString(Config.WALLPAPER_CHOICE_KEY, null);
        wallpaperChoice.setValue(wallpaperLabel(wallpaperCurrent), true);
        topShelfChoice.setToggle(topShelf);
        vstvChoice.setValue(vstvState(), false);
        feiniuChoice.setValue(feiniuState(), true);
        openOnStartChoice.setValue(openOnStartLabel(), true);
        followThemeChoice.setValue(getString(hasOnTvChoices() ? R.string.follow_theme_value_reset
                : R.string.follow_theme_value_following), false);
        defaultHome().setArrow();
        tvSettings().setArrow();
        ((SettingsItem) findViewById(R.id.restoreChoice)).setArrow();
        String address = ViddaTools.address(this);
        String version = Updater.currentVersion(this);
        String available = Updater.prefs(this).getString(Updater.KEY_AVAILABLE, "");
        if (!updateBusy) {
            updateChoice.setValue(!available.isEmpty() && Updates.compare(available, version) > 0
                    ? getString(R.string.update_available_value, available)
                    : getString(R.string.update_current, version), true);
        }
        updateBetaChoice.setToggle(Updater.beta(this));
        if (ViddaTools.adbOn(this)) {
            adbChoice.setValue(getString(R.string.adb_on_value, address.isEmpty() ? "?" : address), false);
        } else {
            adbChoice.setToggle(false);
        }
    }

    private SettingsItem defaultHome() {
        return findViewById(R.id.defaultHomeChoice);
    }

    private SettingsItem tvSettings() {
        return findViewById(R.id.tvSettingsChoice);
    }

    /** A row that does nothing while what it depends on is off: dimmed, and skipped by the remote. */
    private static void enable(View row, boolean enabled) {
        row.setEnabled(enabled);
        row.setFocusable(enabled);
        row.setAlpha(enabled ? 1f : 0.45f);
    }

    /** ADB on asks first (anyone on the network gets a shell while it is on); off is immediate. */
    private void toggleAdb() {
        if (!ViddaTools.canSwitchAdb(this)) {
            Toast.makeText(this, R.string.adb_unavailable, Toast.LENGTH_LONG).show();
            return;
        }
        if (ViddaTools.adbOn(this)) {
            setAdb(false);
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.adb_warning_title)
                .setMessage(R.string.adb_warning)
                .setPositiveButton(R.string.enable, (dialog, which) -> setAdb(true))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void setAdb(boolean on) {
        try {
            ViddaTools.setAdb(this, on);
        } catch (SecurityException e) {
            Toast.makeText(this, R.string.adb_unavailable, Toast.LENGTH_LONG).show();
        }
        refresh();
    }

    private void confirmRestore() {
        if (!ViddaTools.canRestore(this)) {
            Toast.makeText(this, R.string.restore_unavailable, Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.restore_title)
                .setMessage(R.string.restore_message)
                .setPositiveButton(R.string.restore_confirm, (dialog, which) -> runRestore())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** ViddaTools.restore talks to adbd and waits for it, so it runs on its own thread behind a dialog. */
    private void runRestore() {
        AlertDialog progress = new AlertDialog.Builder(this)
                .setMessage(R.string.restore_running)
                .setCancelable(false)
                .show();
        new Thread(() -> {
            String failure = ViddaTools.restore(getApplicationContext());
            runOnUiThread(() -> {
                progress.dismiss();
                if (isFinishing() || isDestroyed()) return;
                refresh();
                if (failure != null) {
                    new AlertDialog.Builder(this)
                            .setMessage(getString(R.string.restore_failed, failure))
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                    return;
                }
                new AlertDialog.Builder(this)
                        .setTitle(R.string.restore_done_title)
                        .setMessage(R.string.restore_done)
                        .setPositiveButton(R.string.restore_uninstall, (dialog, which) -> open(
                                new Intent(Intent.ACTION_DELETE, android.net.Uri.parse("package:" + getPackageName()))))
                        .setNegativeButton(R.string.restore_later, null)
                        .show();
            });
        }, "restore").start();
    }

    private boolean hasOnTvChoices() {
        return choices.contains("columns") || choices.contains(Config.ROWS_BELOW_DOCK_KEY)
                || choices.contains("animations") || choices.contains("advanced_effects")
                || choices.contains(Config.WALLPAPER_CHOICE_KEY) || choices.contains(Config.DOCK_OPACITY_KEY)
                || choices.contains(OpenOnStart.KEY) || choices.contains(Config.PARALLAX_KEY)
                || choices.contains(Config.CLOCK_24H_KEY) || choices.contains(Config.SCREENSAVER_MINUTES_KEY)
                || choices.contains(Config.SCREENSAVER_STYLE_KEY) || choices.contains(Config.TOP_SHELF_KEY);
    }

    private void open(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_LONG).show();
        }
    }
}
