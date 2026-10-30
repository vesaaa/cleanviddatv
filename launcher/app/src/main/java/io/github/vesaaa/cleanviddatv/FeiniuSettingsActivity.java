package io.github.vesaaa.cleanviddatv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.List;

/**
 * 飞牛影视: find the NAS on the network (or type its address), sign in with a user name and password, and later see
 * the account, sign in again, change the signing key pair or sign out. The account feeds the top shelf of the
 * 飞牛影视 app on the dock (FeiniuShelfProvider).
 */
public final class FeiniuSettingsActivity extends Activity {

    private TextView status, hint;
    private LinearLayout rows;
    /** Rows shown for a scan in progress or done; the activity is not rebuilt while one runs. */
    private boolean scanning;
    private Thread scan;
    private List<FeiniuScan.Server> found;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_feiniu_settings);
        status = findViewById(R.id.feiniuStatus);
        hint = findViewById(R.id.feiniuHint);
        rows = findViewById(R.id.feiniuRows);
        if (getResources().getBoolean(R.bool.rtl_menu_bar)) {
            findViewById(android.R.id.content).setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        }
        if (Feiniu.signedIn(this)) showAccount();
        else startScan();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (scan != null) scan.interrupt();
    }

    // ---- signed in -------------------------------------------------------------------------------------------

    private void showAccount() {
        rows.removeAllViews();
        status.setText(R.string.feiniu_signed_in);
        hint.setText(FeiniuShelfProvider.appPackage(this) == null ? getString(R.string.feiniu_no_app)
                : getString(R.string.feiniu_signed_in_hint));
        SettingsItem server = add(R.string.feiniu_server_label, 0);
        server.setValue(Feiniu.display(Feiniu.base(this)), true);
        server.setOnClickListener(v -> startScan());
        SettingsItem account = add(R.string.feiniu_account_label, 0);
        account.setValue(Feiniu.user(this), true);
        account.setOnClickListener(v -> askSignIn(Feiniu.base(this), Feiniu.display(Feiniu.base(this))));
        addKeysRow(20);
        SettingsItem out = add(R.string.feiniu_sign_out, 20);
        out.setArrow();
        out.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle(R.string.feiniu_sign_out)
                .setPositiveButton(R.string.feiniu_sign_out, (d, w) -> {
                    Feiniu.signOut(this);
                    FeiniuShelfProvider.forget(this);
                    changed();
                    startScan();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show());
        server.requestFocus();
    }

    // ---- finding a server ------------------------------------------------------------------------------------

    private void startScan() {
        if (scanning) return;
        scanning = true;
        found = null;
        showServers();
        Context app = getApplicationContext();
        scan = new Thread(() -> {
            List<FeiniuScan.Server> result = FeiniuScan.scan(app);
            boolean network = FeiniuScan.ownAddress(app) != null;
            runOnUiThread(() -> {
                scanning = false;
                if (isFinishing() || isDestroyed()) return;
                found = result;
                showServers();
                if (!network) status.setText(R.string.feiniu_no_network);
            });
        }, "feiniu-scan");
        scan.start();
    }

    /** The servers found so far (none while scanning), then typing an address, scanning again and the keys. */
    private void showServers() {
        View focused = getCurrentFocus();
        rows.removeAllViews();
        hint.setText("");
        if (scanning) status.setText(R.string.feiniu_scanning);
        else if (found == null || found.isEmpty()) status.setText(R.string.feiniu_none_found);
        else status.setText(getString(R.string.feiniu_found, found.size()));
        SettingsItem first = null;
        if (found != null) {
            for (FeiniuScan.Server s : found) {
                SettingsItem row = SettingsItem.row(this);
                row.setText(s.name.isEmpty() ? getString(R.string.feiniu_label) : s.name);
                String where = Feiniu.display(s.base);
                row.setValue(s.version.isEmpty() ? where : where + " · " + getString(R.string.feiniu_server_value,
                        s.version), true);
                row.setOnClickListener(v -> askSignIn(s.base, s.name.isEmpty() ? where : s.name));
                place(row, 0);
                if (first == null) first = row;
            }
        }
        SettingsItem manual = add(R.string.feiniu_manual, found != null && !found.isEmpty() ? 20 : 0);
        manual.setArrow();
        manual.setOnClickListener(v -> askAddress());
        SettingsItem again = add(R.string.feiniu_rescan, 0);
        enableRow(again, !scanning);
        again.setOnClickListener(v -> startScan());
        addKeysRow(20);
        if (focused == null || !focused.isAttachedToWindow()) (first != null ? first : manual).requestFocus();
        else if (first != null && !scanning) first.requestFocus();
        else manual.requestFocus();
    }

    private void askAddress() {
        EditText field = field(getString(R.string.feiniu_address_hint), InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_URI);
        String saved = Feiniu.base(this);
        if (!saved.isEmpty()) field.setText(Feiniu.display(saved));
        field.setImeOptions(EditorInfo.IME_ACTION_DONE);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.feiniu_manual)
                .setView(wrap(field))
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String base = Feiniu.normalise(field.getText().toString());
                    if (base == null) {
                        Toast.makeText(this, R.string.feiniu_address_invalid, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    askSignIn(base, Feiniu.display(base));
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.show();
        field.setOnEditorActionListener((v, action, event) -> {
            if (action != EditorInfo.IME_ACTION_DONE) return false;
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            return true;
        });
    }

    // ---- signing in ------------------------------------------------------------------------------------------

    private void askSignIn(String base, String name) {
        EditText user = field(getString(R.string.feiniu_user_hint), InputType.TYPE_CLASS_TEXT);
        EditText password = field(getString(R.string.feiniu_password_hint), InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        password.setImeOptions(EditorInfo.IME_ACTION_DONE);
        user.setText(Feiniu.user(this));
        LinearLayout box = wrap(user);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        box.addView(password, lp);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.feiniu_sign_in_title, name))
                .setView(box)
                .setPositiveButton(R.string.feiniu_sign_in, (d, w) ->
                        signIn(base, user.getText().toString().trim(), password.getText().toString()))
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.show();
        // The on-screen keyboard covers the buttons on a TV: its Done key on the password signs in.
        password.setOnEditorActionListener((v, action, event) -> {
            if (action != EditorInfo.IME_ACTION_DONE) return false;
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            return true;
        });
        (user.getText().length() > 0 ? password : user).requestFocus();
    }

    private void signIn(String base, String user, String password) {
        if (user.isEmpty() || password.isEmpty()) return;
        AlertDialog progress = new AlertDialog.Builder(this)
                .setMessage(R.string.feiniu_signing_in)
                .setCancelable(false)
                .show();
        String key = Feiniu.apiKey(this), secret = Feiniu.apiSecret(this);
        Context app = getApplicationContext();
        new Thread(() -> {
            String token = null, error = null;
            try {
                token = Feiniu.login(base, key, secret, user, password);
            } catch (Feiniu.ApiException e) {
                error = e.badSign() ? app.getString(R.string.feiniu_bad_sign) : e.getMessage();
            } catch (IOException e) {
                error = app.getString(R.string.feiniu_unreachable);
            }
            String done = token, failure = error;
            runOnUiThread(() -> {
                progress.dismiss();
                if (isFinishing() || isDestroyed()) return;
                if (done == null) {
                    new AlertDialog.Builder(this)
                            .setMessage(getString(R.string.feiniu_sign_in_failed, failure))
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                    return;
                }
                boolean other = !base.equals(Feiniu.base(this)) || !user.equals(Feiniu.user(this));
                Feiniu.save(this, base, user, password, done);
                if (other) FeiniuShelfProvider.forget(this);
                changed();
                Toast.makeText(this, R.string.feiniu_signed_in, Toast.LENGTH_SHORT).show();
                showAccount();
            });
        }, "feiniu-login").start();
    }

    // ---- the key pair ----------------------------------------------------------------------------------------

    private void addKeysRow(int topDp) {
        SettingsItem keys = add(R.string.feiniu_keys_label, topDp);
        keys.setValue(getString(Feiniu.customKeys(this) ? R.string.feiniu_keys_custom : R.string.feiniu_keys_default),
                true);
        keys.setOnClickListener(v -> askKeys());
    }

    private void askKeys() {
        EditText key = field(getString(R.string.feiniu_key_hint), InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        EditText secret = field(getString(R.string.feiniu_secret_hint), InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        key.setText(Feiniu.apiKey(this));
        secret.setText(Feiniu.apiSecret(this));
        LinearLayout box = wrap(key);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        box.addView(secret, lp);
        TextView note = new TextView(this);
        note.setText(R.string.feiniu_keys_hint);
        note.setTextSize(14);
        note.setPadding(0, dp(12), 0, 0);
        box.addView(note);
        new AlertDialog.Builder(this)
                .setTitle(R.string.feiniu_keys_label)
                .setView(box)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String k = key.getText().toString().trim(), s = secret.getText().toString().trim();
                    // The built-in pair typed back counts as the built-in one.
                    Feiniu.setKeys(this, Feiniu.DEFAULT_KEY.equals(k) ? "" : k, Feiniu.DEFAULT_SECRET.equals(s) ? "" : s);
                    redraw();
                })
                .setNeutralButton(R.string.feiniu_keys_reset, (d, w) -> {
                    Feiniu.setKeys(this, "", "");
                    redraw();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void redraw() {
        if (Feiniu.signedIn(this)) showAccount();
        else showServers();
    }

    // ---- views -----------------------------------------------------------------------------------------------

    private SettingsItem add(int label, int topDp) {
        SettingsItem row = SettingsItem.row(this);
        row.setText(label);
        place(row, topDp);
        return row;
    }

    private void place(SettingsItem row, int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(60));
        lp.topMargin = dp(topDp > 0 ? topDp : 8);
        rows.addView(row, lp);
    }

    private static void enableRow(View row, boolean enabled) {
        row.setEnabled(enabled);
        row.setFocusable(enabled);
        row.setAlpha(enabled ? 1f : 0.45f);
    }

    private EditText field(String hintText, int type) {
        EditText e = new EditText(this);
        e.setHint(hintText);
        // Single line first: it replaces the transformation, which would undo a password's dots.
        e.setSingleLine(true);
        e.setInputType(type);
        return e;
    }

    private LinearLayout wrap(View first) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(12), dp(24), 0);
        box.addView(first, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        return box;
    }

    /** Home rebuilds (and asks the shelf again) when it is next in front, as for any settings change. */
    private void changed() {
        android.content.SharedPreferences choices = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        choices.edit().putLong(Config.SETTINGS_REVISION_KEY, choices.getLong(Config.SETTINGS_REVISION_KEY, 0) + 1)
                .apply();
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
