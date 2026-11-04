package app.ferry;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.format.DateUtils;
import android.view.View;
import android.widget.Button;
import android.widget.RadioGroup;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

/** Settings: appearance, syncing switches, pairing, keeping Ferry running, and updates. See activity_settings.xml. */
public class SettingsActivity extends Activity implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static final String HELP = "https://github.com/ulot2/ferry#readme";
    private static final String FERRY_AUTO_HELP = "https://github.com/ulot2/ferry#ferry-auto-automatic-sending";
    private static final int PHOTOS = 1;
    private Button autoHelp, battery, update;
    private RadioGroup themeGroup;
    private Switch autoSwitch, imagesSwitch, screenshotsSwitch;
    private TextView themeHint, autoTitle, autoBody, screenshotsBody, pairTitle, pairBody, batteryBody, version, updateBody;
    private View syncing, pairing, running, xiaomi;
    private boolean checking;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        Configuration theme = Ferry.themeOverride(base);
        if (theme != null) applyOverrideConfiguration(theme);
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_settings);
        themeGroup = findViewById(R.id.themeGroup);
        themeHint = findViewById(R.id.themeHint);
        autoTitle = findViewById(R.id.autoTitle);
        autoSwitch = findViewById(R.id.autoSwitch);
        autoBody = findViewById(R.id.autoBody);
        autoHelp = findViewById(R.id.autoHelp);
        imagesSwitch = findViewById(R.id.imagesSwitch);
        screenshotsSwitch = findViewById(R.id.screenshotsSwitch);
        screenshotsBody = findViewById(R.id.screenshotsBody);
        pairTitle = findViewById(R.id.pairTitle);
        pairBody = findViewById(R.id.pairBody);
        batteryBody = findViewById(R.id.batteryBody);
        battery = findViewById(R.id.battery);
        version = findViewById(R.id.version);
        updateBody = findViewById(R.id.updateBody);
        update = findViewById(R.id.update);
        syncing = findViewById(R.id.syncing);
        pairing = findViewById(R.id.pairing);
        running = findViewById(R.id.running);
        xiaomi = findViewById(R.id.xiaomi);

        findViewById(R.id.back).setOnClickListener(v -> finish());
        themeGroup.setOnCheckedChangeListener((group, id) ->
                chooseTheme(id == R.id.themeLight ? "light" : id == R.id.themeDark ? "dark" : "system"));

        // Android only lets the user turn on an accessibility service, so the switch opens that page.
        autoSwitch.setOnClickListener(v -> {
            autoSwitch.setChecked(Ferry.autoSendOn(this));
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        });
        autoHelp.setOnClickListener(v -> {
            if (Ferry.autoEdition(this)) startActivity(appInfo());
            else open(FERRY_AUTO_HELP);
        });
        imagesSwitch.setOnClickListener(v -> {
            Ferry.prefs(this).edit().putBoolean("images", imagesSwitch.isChecked()).apply();
            restartSync();
        });
        screenshotsSwitch.setOnClickListener(v -> {
            boolean on = screenshotsSwitch.isChecked();
            if (on && !SyncService.canReadPhotos(this)) {
                // Ask first; it turns on in onRequestPermissionsResult if allowed.
                screenshotsSwitch.setChecked(false);
                requestPermissions(new String[]{Build.VERSION.SDK_INT >= 33
                        ? Manifest.permission.READ_MEDIA_IMAGES : Manifest.permission.READ_EXTERNAL_STORAGE}, PHOTOS);
                return;
            }
            Ferry.prefs(this).edit().putBoolean("screenshots", on).apply();
            restartSync();
        });
        findViewById(R.id.unpair).setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Unpair from " + laptop() + "?")
                .setMessage("Ferry stops syncing on this phone and on the laptop. The laptop shows a new code to scan if you want to pair again.")
                .setPositiveButton("Unpair", (d, w) -> {
                    Ferry.unpair(this);
                    finish();   // the chart screen now shows the pairing steps
                })
                .setNegativeButton("Cancel", null)
                .show());
        battery.setOnClickListener(v -> startActivity(new Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName()))));
        findViewById(R.id.appSettings).setOnClickListener(v -> startActivity(appInfo()));
        update.setOnClickListener(v -> {
            String ready = readyUpdate();
            if (!ready.isEmpty()) {
                // Ferry Auto cannot be installed from the phone (Play Protect), so it leads to the computer steps.
                open(Ferry.autoEdition(this) ? FERRY_AUTO_HELP : Ferry.prefs(this).getString("update_url", ""));
                return;
            }
            checking = true;
            render();
            Updates.checkNow(this, ok -> runOnUiThread(() -> {
                checking = false;
                if (!ok) Toast.makeText(this, "Ferry could not reach GitHub. Try again later.", Toast.LENGTH_LONG).show();
                render();
            }));
        });
        findViewById(R.id.help).setOnClickListener(v -> open(HELP));
    }

    @Override
    protected void onResume() {
        super.onResume();
        Ferry.prefs(this).registerOnSharedPreferenceChangeListener(this);
        render();   // also picks up what changed in Android settings (battery, accessibility)
    }

    @Override
    protected void onPause() {
        super.onPause();
        Ferry.prefs(this).unregisterOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences p, String key) {
        if (!"last_id".equals(key)) render();
    }

    private void chooseTheme(String theme) {
        if (theme.equals(Ferry.theme(this))) return;
        Ferry.setTheme(this, theme);
        recreate();   // this screen at once; the chart screen picks it up when you go back (see MainActivity.onResume)
    }

    private void render() {
        SharedPreferences p = Ferry.prefs(this);
        boolean paired = Ferry.paired(this), autoEdition = Ferry.autoEdition(this);

        String theme = Ferry.theme(this);
        themeGroup.check(theme.equals("light") ? R.id.themeLight : theme.equals("dark") ? R.id.themeDark : R.id.themeSystem);
        boolean night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        themeHint.setText(theme.equals("system")
                ? "Follows the light or dark mode of Android. Now: " + (night ? "dark." : "light.")
                : "Always " + theme + ", whatever Android uses.");

        syncing.setVisibility(paired ? View.VISIBLE : View.GONE);
        // Only Ferry Auto can send by itself. Standard Ferry shows a title and a link, not a switch that cannot turn on.
        autoSwitch.setVisibility(autoEdition ? View.VISIBLE : View.GONE);
        autoTitle.setVisibility(autoEdition ? View.GONE : View.VISIBLE);
        autoSwitch.setChecked(Ferry.autoSendOn(this));
        if (autoEdition) {
            autoBody.setText("Skip the Send button. Ferry uses Android's accessibility permission to notice taps on Copy. "
                    + "It reads only the label of what you tap. If the switch in Android is greyed out, open App info, tap ⋮, then Allow restricted settings.");
            autoHelp.setText("App info");
        } else {
            autoBody.setText("That needs Ferry Auto, a second edition of this app. You install it from a Windows computer with one script. Your pairing and history stay.");
            autoHelp.setText("How to get Ferry Auto");
        }
        imagesSwitch.setChecked(Ferry.imagesOn(this));
        boolean canRead = SyncService.canReadPhotos(this);
        screenshotsSwitch.setChecked(Ferry.screenshotsOn(this) && canRead);
        screenshotsSwitch.setEnabled(Ferry.imagesOn(this));
        screenshotsBody.setText(!canRead && p.getBoolean("screenshots", false)
                ? "Ferry needs the Photos permission set to \"Allow all\" to notice new screenshots. Open App info > Permissions > Photos."
                : "Each new screenshot goes to the laptop's clipboard by itself.");

        pairing.setVisibility(paired ? View.VISIBLE : View.GONE);
        if (paired) {
            String code = Ferry.code(this);
            pairTitle.setText("Paired with " + laptop());
            pairBody.setText("The pairing code ends in " + code.substring(code.length() - 4) + ".");
        }

        running.setVisibility(paired ? View.VISIBLE : View.GONE);
        boolean allowed = getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName());
        batteryBody.setText(allowed ? "Allowed. Ferry keeps the link open while the phone sleeps."
                : "Not allowed. Android cuts the connection when the phone sleeps, so laptop copies stop arriving.");
        battery.setVisibility(allowed ? View.GONE : View.VISIBLE);
        String maker = Build.MANUFACTURER.toLowerCase(Locale.ROOT);
        xiaomi.setVisibility(maker.contains("xiaomi") || maker.contains("redmi") || maker.contains("poco") ? View.VISIBLE : View.GONE);

        version.setText((autoEdition ? "Ferry Auto " : "Ferry ") + Updates.installed(this));
        String ready = readyUpdate();
        long checked = p.getLong("update_checked", 0);
        if (checking) {
            updateBody.setText("Looking for a newer version…");
        } else if (!ready.isEmpty()) {
            updateBody.setText("Version " + ready + " is ready. " + (autoEdition
                    ? "Install it from your computer, the same way you installed Ferry Auto."
                    : "Download it, then install it over this version.") + " Your pairing and history stay.");
        } else {
            updateBody.setText(checked == 0 ? "This is the version on this phone."
                    : "This is the newest version. Checked " + DateUtils.getRelativeTimeSpanString(checked, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS) + ".");
        }
        update.setText(!ready.isEmpty() ? (autoEdition ? "How to update" : "Download update") : "Check for updates");
        update.setEnabled(!checking);
    }

    /** The newer version that the last check found, or "". The saved result can predate an install, so it is checked against this version. */
    private String readyUpdate() {
        String v = Ferry.prefs(this).getString("update_version", "");
        return !v.isEmpty() && Updates.newer(v, Updates.installed(this)) ? v : "";
    }

    private String laptop() {
        String peer = Ferry.prefs(this).getString("peer", "");
        return peer.isEmpty() ? "your laptop" : peer;
    }

    @Override
    public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request != PHOTOS) return;
        boolean allowed = SyncService.canReadPhotos(this);
        Ferry.prefs(this).edit().putBoolean("screenshots", allowed).apply();
        if (allowed) restartSync();
        render();
    }

    private void restartSync() {
        stopService(new Intent(this, SyncService.class));
        SyncService.start(this);
    }

    private Intent appInfo() {
        return new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()));
    }

    private void open(String url) {
        if (!url.isEmpty()) startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
    }
}
