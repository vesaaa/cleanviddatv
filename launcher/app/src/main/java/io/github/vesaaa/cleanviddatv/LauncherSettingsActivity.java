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
import android.view.View;
import android.widget.TextView;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Remote-friendly settings owned by the launcher; system settings stay one row away. */
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
    private TextView wallpaperChoice;
    private TextView animationsChoice;
    private TextView advancedChoice;
    private TextView followThemeChoice;
    private TextView openOnStartChoice;
    private TextView adbChoice;
    private TextView updateChoice, updateBetaChoice;
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
                    .remove(Config.DOCK_OPACITY_KEY).remove(OpenOnStart.KEY).commit();
            Config current = Config.load(this);
            columns = current.columns;
            rowsBelowDock = current.rowsBelowDock;
            dockOpacity = current.dockOpacity;
            animations = current.animations;
            advancedEffects = current.animations && current.advancedEffects;
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
        if (config.launcherSettingsButton) reorderChoice.requestFocus();
        else columnChoices[Math.max(0, Math.min(columns - 4, 3))].requestFocus();
    }

    private void reverseRow(TextView[] views) {
        LinearLayout row = (LinearLayout) views[0].getParent();
        row.removeAllViews();
        for (int i = views.length - 1; i >= 0; i--) row.addView(views[i]);
    }

    private void showWallpaperPicker() {
        File dir = Config.dir(this);
        List<File> images = new java.util.ArrayList<>(WallpaperRotation.images(
                dir == null ? null : new File(dir, "wallpapers")));
        // A theme with a single wallpaper.jpg offers that picture too (Config maps the choice back to it).
        File single = dir == null ? null : new File(dir, Config.WALLPAPER_NAME);
        if (single != null && single.isFile()) images.add(0, single);
        List<File> builtins = BuiltinWallpapers.all(this);
        Map<String, String> captions = WallpaperCaptions.read(BuiltinWallpapers.dir(this));
        // Rows: the two bundled groups, each bundled picture, the theme's pictures, none, then the photo credits.
        List<String> labels = new ArrayList<>();
        List<String> values = new ArrayList<>();
        if (!builtins.isEmpty()) {
            labels.add(getString(R.string.wallpaper_builtin_photos));
            values.add(BuiltinWallpapers.PHOTOS);
            labels.add(getString(R.string.wallpaper_builtin_art));
            values.add(BuiltinWallpapers.ART);
            for (File f : builtins) {
                labels.add(BuiltinWallpapers.label(captions, f));
                values.add(f.getName());
            }
        }
        for (File f : images) {
            labels.add(f.getName());
            values.add(f.getName());
        }
        labels.add(getString(R.string.wallpaper_none));
        values.add(WallpaperRotation.CHOICE_NONE);
        if (!builtins.isEmpty()) {
            labels.add(getString(R.string.wallpaper_credits));
            values.add(null);
        }
        String current = choices.getString(Config.WALLPAPER_CHOICE_KEY, null);
        int checked = current == null ? -1 : values.indexOf(current);
        new AlertDialog.Builder(this)
                .setTitle(R.string.wallpaper_label)
                .setSingleChoiceItems(labels.toArray(new String[0]), checked, (dialog, which) -> {
                    dialog.dismiss();
                    String value = values.get(which);
                    if (value == null) {
                        showWallpaperCredits(builtins, captions);
                        return;
                    }
                    choices.edit().putString(Config.WALLPAPER_CHOICE_KEY, value).apply();
                    changed();
                    Toast.makeText(this, R.string.wallpaper_applied, Toast.LENGTH_SHORT).show();
                    refresh();
                })
                .setNeutralButton(R.string.wallpaper_theme_default, (dialog, which) -> {
                    choices.edit().remove(Config.WALLPAPER_CHOICE_KEY).apply();
                    changed();
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** The bundled photos' authors and licences, which CC BY-SA asks to show with them. */
    private void showWallpaperCredits(List<File> builtins, Map<String, String> captions) {
        StringBuilder text = new StringBuilder();
        for (File f : BuiltinWallpapers.group(builtins, BuiltinWallpapers.PHOTOS)) {
            String caption = captions.get(f.getName());
            if (caption != null) text.append("• ").append(caption).append('\n');
        }
        text.append('\n').append(getString(R.string.wallpaper_credits_note));
        new AlertDialog.Builder(this)
                .setTitle(R.string.wallpaper_credits)
                .setMessage(text)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> showWallpaperPicker())
                .show();
    }

    /** The current wallpaper choice as the picker names it. */
    private String wallpaperLabel(String choice) {
        if (choice == null) return getString(R.string.wallpaper_theme_default);
        if (WallpaperRotation.CHOICE_NONE.equals(choice)) return getString(R.string.wallpaper_none);
        if (BuiltinWallpapers.PHOTOS.equals(choice)) return getString(R.string.wallpaper_builtin_photos);
        if (BuiltinWallpapers.ART.equals(choice)) return getString(R.string.wallpaper_builtin_art);
        File builtin = new File(BuiltinWallpapers.dir(this), choice);
        return builtin.isFile() ? BuiltinWallpapers.label(WallpaperCaptions.read(builtin.getParentFile()), builtin) : choice;
    }

    /** Checks for a newer version, then offers it; the download and the install follow on the viewer's yes. */
    private void checkForUpdate() {
        if (updateBusy) return;
        updateBusy = true;
        updateChoice.setText(R.string.update_checking);
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
            try {
                java.io.File apk = Updater.download(this, release,
                        percent -> runOnUiThread(() -> progress.setMessage(getString(R.string.update_downloading, percent))));
                Updater.install(this, apk);
            } catch (java.io.IOException | RuntimeException e) {
                error = String.valueOf(e.getMessage());
            }
            String failure = error;
            runOnUiThread(() -> {
                updateBusy = false;
                progress.dismiss();
                if (failure != null && !isFinishing()) showUpdateError(failure);
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
        animationsChoice.setText(getString(R.string.animations_state,
                getString(animations ? R.string.on : R.string.off)));
        advancedChoice.setText(getString(R.string.advanced_effects_state,
                getString(advancedEffects ? R.string.on : R.string.off)));
        advancedChoice.setEnabled(animations);
        advancedChoice.setAlpha(animations ? 1f : 0.5f);
        String wallpaperCurrent = choices.getString(Config.WALLPAPER_CHOICE_KEY, null);
        wallpaperChoice.setText(getString(R.string.wallpaper_state, wallpaperLabel(wallpaperCurrent)));
        openOnStartChoice.setText(getString(R.string.open_on_start_state, openOnStartLabel()));
        followThemeChoice.setSelected(!hasOnTvChoices());
        String address = ViddaTools.address(this);
        String version = Updater.currentVersion(this);
        String available = Updater.prefs(this).getString(Updater.KEY_AVAILABLE, "");
        updateChoice.setText(!available.isEmpty() && Updates.compare(available, version) > 0
                ? getString(R.string.update_state_available, available, version)
                : getString(R.string.update_state, version));
        updateBetaChoice.setText(getString(R.string.update_beta_state,
                getString(Updater.beta(this) ? R.string.on : R.string.off)));
        adbChoice.setText(ViddaTools.adbOn(this)
                ? getString(R.string.adb_state_on, address.isEmpty() ? "?" : address)
                : getString(R.string.adb_state, getString(R.string.off)));
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
                || choices.contains(OpenOnStart.KEY);
    }

    private void open(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_LONG).show();
        }
    }
}
