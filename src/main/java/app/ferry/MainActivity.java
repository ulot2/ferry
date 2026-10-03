package app.ferry;

import android.Manifest;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions;
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;

/** The chart screen: link status, last crossing, Send, notices, logbook, switches and pairing. See DESIGN.md. */
public class MainActivity extends Activity implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static final String FERRY_AUTO_HELP = "https://github.com/ulot2/ferry#ferry-auto-automatic-sending";
    private static final int PHOTOS = 1;
    private View header, lamp, crossing, manualRow, batteryCard, xiaomiCard, updateCard, autoCard, historyCard, autoAppInfo, chips, pairCard;
    private TextView statusText, route, preview, pairTitle, pairBody, pairError, updateTitle, updateBody, autoTitle, autoBody, screenshotsBody;
    private Button send, scan, pairAlt, autoToggle, updateButton, autoChip, imagesChip, screenshotsChip;
    private ViewGroup historyList, content;
    private ChartView chart;
    private EditText codeField;
    private View wordmark, statusPill;
    private int wordmarkTop, pillTop, headerHeight;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 0);

        header = findViewById(R.id.header);
        chart = findViewById(R.id.chart);
        lamp = findViewById(R.id.lamp);
        crossing = findViewById(R.id.crossing);
        content = findViewById(R.id.content);
        manualRow = findViewById(R.id.manualRow);
        batteryCard = findViewById(R.id.batteryCard);
        xiaomiCard = findViewById(R.id.xiaomiCard);
        updateCard = findViewById(R.id.updateCard);
        autoCard = findViewById(R.id.autoCard);
        historyCard = findViewById(R.id.historyCard);
        autoAppInfo = findViewById(R.id.autoAppInfo);
        chips = findViewById(R.id.chips);
        pairCard = findViewById(R.id.pairCard);
        statusText = findViewById(R.id.statusText);
        route = findViewById(R.id.route);
        preview = findViewById(R.id.preview);
        pairTitle = findViewById(R.id.pairTitle);
        pairBody = findViewById(R.id.pairBody);
        pairError = findViewById(R.id.pairError);
        updateTitle = findViewById(R.id.updateTitle);
        updateBody = findViewById(R.id.updateBody);
        updateButton = findViewById(R.id.update);
        autoTitle = findViewById(R.id.autoTitle);
        autoBody = findViewById(R.id.autoBody);
        screenshotsBody = findViewById(R.id.screenshotsBody);
        send = findViewById(R.id.send);
        scan = findViewById(R.id.scan);
        pairAlt = findViewById(R.id.pairAlt);
        autoToggle = findViewById(R.id.autoToggle);
        autoChip = findViewById(R.id.autoChip);
        imagesChip = findViewById(R.id.imagesChip);
        screenshotsChip = findViewById(R.id.screenshotsChip);
        historyList = findViewById(R.id.historyList);
        codeField = findViewById(R.id.topic);

        // Edge to edge: the chart runs under the status bar; the content clears the navigation bar.
        wordmark = findViewById(R.id.wordmark);
        statusPill = findViewById(R.id.statusPill);
        wordmarkTop = ((ViewGroup.MarginLayoutParams) wordmark.getLayoutParams()).topMargin;
        pillTop = ((ViewGroup.MarginLayoutParams) statusPill.getLayoutParams()).topMargin;
        headerHeight = header.getLayoutParams().height;
        findViewById(R.id.scroll).setOnApplyWindowInsetsListener(this::applyInsets);

        boolean autoEdition = Ferry.autoEdition(this);
        send.setOnClickListener(v -> startActivity(new Intent(this, SendActivity.class)));
        scan.setOnClickListener(v -> scan());
        pairAlt.setOnClickListener(v -> {
            if (Ferry.paired(this)) {
                Ferry.unpair(this);
            } else {
                manualRow.setVisibility(manualRow.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
                if (manualRow.getVisibility() == View.VISIBLE) codeField.requestFocus();
            }
            render();
        });
        findViewById(R.id.manualSave).setOnClickListener(v -> {
            String code = codeField.getText().toString();
            if (!Crypto.validCode(code)) {
                codeField.setError("The code has 26 letters and numbers. It is under the QR code on your laptop.");
                return;
            }
            Ferry.pair(this, code, null);
            manualRow.setVisibility(View.GONE);
            render();
        });
        autoChip.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        imagesChip.setOnClickListener(v -> {
            Ferry.prefs(this).edit().putBoolean("images", !Ferry.imagesOn(this)).apply();
            restartSync();
        });
        screenshotsChip.setOnClickListener(v -> {
            boolean on = !(Ferry.screenshotsOn(this) && SyncService.canReadPhotos(this));
            if (on && !SyncService.canReadPhotos(this)) {
                // Ask first; it turns on in onRequestPermissionsResult if allowed.
                requestPermissions(new String[]{Build.VERSION.SDK_INT >= 33
                        ? Manifest.permission.READ_MEDIA_IMAGES : Manifest.permission.READ_EXTERNAL_STORAGE}, PHOTOS);
                return;
            }
            Ferry.prefs(this).edit().putBoolean("screenshots", on).apply();
            restartSync();
        });
        findViewById(R.id.clearHistory).setOnClickListener(v -> Ferry.clearHistory(this));
        // Ferry Auto cannot be installed from the phone (Play Protect), so its buttons lead to the computer steps instead.
        updateButton.setOnClickListener(v -> open(autoEdition ? FERRY_AUTO_HELP : Ferry.prefs(this).getString("update_url", "")));
        autoToggle.setOnClickListener(v -> {
            if (autoEdition) startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            else open(FERRY_AUTO_HELP);
        });
        View.OnClickListener appInfo = v -> startActivity(new Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
        autoAppInfo.setOnClickListener(appInfo);
        findViewById(R.id.appSettings).setOnClickListener(appInfo);
        findViewById(R.id.battery).setOnClickListener(v -> startActivity(new Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName()))));

        handle(getIntent());
        if (Ferry.paired(this)) SyncService.start(this);
    }

    // The system-window inset getters cover API 29, where WindowInsets.Type does not exist yet.
    @SuppressWarnings("deprecation")
    private WindowInsets applyInsets(View v, WindowInsets insets) {
        int top = insets.getSystemWindowInsetTop();
        ViewGroup.LayoutParams lp = header.getLayoutParams();
        lp.height = headerHeight + top;
        header.setLayoutParams(lp);
        // The chart fills the whole header, under the status bar; only the wordmark and pill move down.
        ((ViewGroup.MarginLayoutParams) wordmark.getLayoutParams()).topMargin = wordmarkTop + top;
        ((ViewGroup.MarginLayoutParams) statusPill.getLayoutParams()).topMargin = pillTop + top;
        v.setPadding(insets.getSystemWindowInsetLeft(), 0, insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
        return insets.consumeSystemWindowInsets();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handle(intent);
    }

    /** Opened from a scanned ferry://pair link. */
    private void handle(Intent intent) {
        if (intent != null && Intent.ACTION_VIEW.equals(intent.getAction()) && !Ferry.pair(this, intent.getData())) {
            showPairError("That link is not a Ferry pairing code.");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        Ferry.prefs(this).registerOnSharedPreferenceChangeListener(this);
        Updates.checkSoon(this);
        render();   // also picks up settings changed while we were away (battery, accessibility)
    }

    @Override
    protected void onPause() {
        super.onPause();
        Ferry.prefs(this).unregisterOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences p, String key) {
        if ("last_id".equals(key) || "update_checked".equals(key)) return;   // bookkeeping, nothing on screen changes
        render();
        if ("history".equals(key) && ValueAnimator.areAnimatorsEnabled()) {
            // The one motion moment: the new crossing slides up into place.
            crossing.setAlpha(0f);
            crossing.setTranslationY(8 * getResources().getDisplayMetrics().density);
            crossing.animate().alpha(1f).translationY(0f).setDuration(200).setInterpolator(new DecelerateInterpolator()).start();
        }
    }

    private void scan() {
        pairError.setVisibility(View.GONE);
        GmsBarcodeScannerOptions options = new GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE).build();
        GmsBarcodeScanning.getClient(this, options).startScan()
                .addOnSuccessListener(code -> {
                    if (!Ferry.pair(this, Uri.parse(String.valueOf(code.getRawValue())))) {
                        showPairError("That QR code is not a Ferry pairing code. Scan the code in the Ferry window on your laptop.");
                    }
                    render();
                })
                .addOnFailureListener(e -> {
                    showPairError("The scanner did not open. Type the pairing code instead.");
                    manualRow.setVisibility(View.VISIBLE);
                });
    }

    private void showPairError(String message) {
        pairError.setText(message);
        pairError.setVisibility(View.VISIBLE);
    }

    private void render() {
        SharedPreferences p = Ferry.prefs(this);
        boolean paired = Ferry.paired(this);
        boolean autoEdition = Ferry.autoEdition(this);
        boolean auto = paired && Ferry.autoSendOn(this);
        String peer = p.getString("peer", "");
        String laptop = peer.isEmpty() ? "your laptop" : peer;

        // Status pill on the chart. Steady = connected, adrift = offline, muted = connecting or not paired.
        String status = paired ? p.getString("status", Ferry.STATUS_CONNECTING) : "";
        int lampColor;
        String label;
        if (Ferry.STATUS_CONNECTED.equals(status)) {
            lampColor = getColor(R.color.steady);
            label = "Steady link";
        } else if (Ferry.STATUS_OFFLINE.equals(status)) {
            lampColor = getColor(R.color.adrift);
            label = "Adrift";
        } else {
            lampColor = getColor(R.color.ink_muted);
            label = paired ? "Connecting" : "Not paired";
        }
        lamp.setBackgroundTintList(ColorStateList.valueOf(lampColor));
        statusText.setText(label);

        // The chart and the last crossing; the logbook lists the ones before it.
        JSONArray history = Ferry.history(this);
        JSONObject last = history.optJSONObject(0);
        String laptopName = peer.isEmpty() ? "Laptop" : peer;
        if (last == null) {
            chart.set(laptopName, "This phone", "", true);
            route.setText(paired ? "NO CROSSINGS YET" : "NOT PAIRED YET");
            preview.setText(paired ? "Copy something on your laptop. It lands here." : "Pair with your laptop to start.");
        } else {
            long at = last.optLong("at");
            boolean toPhone = Ferry.TO_PHONE.equals(last.optString("dir"));
            String time = DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(at));
            chart.set(laptopName, "This phone", "course " + time, toPhone);
            route.setText(("LAST CROSSING · " + (toPhone ? "LAPTOP TO PHONE" : "PHONE TO LAPTOP")
                    + (DateUtils.isToday(at) ? "" : " · " + DateUtils.formatDateTime(this, at, DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_ABBREV_MONTH))).toUpperCase(Locale.getDefault()));
            preview.setText(!last.has("image") ? "“" + Ferry.preview(last.optString("text")) + "”"
                    : toPhone ? last.optString("text") + ", in your clipboard and in Pictures/Ferry"
                    : last.optString("text") + ", in the laptop's clipboard");
        }
        renderHistory(history);

        // One primary action per state: Send when paired, Scan when not. Pairing moves to the top until paired.
        send.setVisibility(paired ? View.VISIBLE : View.GONE);
        content.removeView(pairCard);
        content.addView(pairCard, paired ? content.getChildCount() : 1);
        ((LinearLayout.LayoutParams) pairCard.getLayoutParams()).topMargin = (int) ((paired ? 24 : 18) * getResources().getDisplayMetrics().density);
        if (paired) {
            String code = Ferry.code(this);
            pairTitle.setText("Paired with " + laptop);
            pairBody.setText("Pairing code ends in " + code.substring(code.length() - 4) + ". Scan a new code if you reset pairing on the laptop.");
            style(scan, false);
            scan.setText("Scan new code");
            pairAlt.setText("Unpair");
        } else {
            pairTitle.setText(p.contains("topic") ? "Pair again" : "Pair with your laptop");
            pairBody.setText(p.contains("topic")
                    ? "Ferry now encrypts your text. Update Ferry on your laptop, then scan the new QR code it shows."
                    : "On your laptop, open Ferry from the tray. Then scan the QR code it shows.");
            style(scan, true);
            scan.setText("Scan QR code");
            pairAlt.setText(manualRow.getVisibility() == View.VISIBLE ? "Hide code entry" : "Type the code");
        }

        String update = p.getString("update_version", "");
        // The saved result may predate an install (for example Ferry Auto over Ferry), so check it against this version.
        if (!update.isEmpty() && !Updates.newer(update, Updates.installed(this))) update = "";
        updateCard.setVisibility(update.isEmpty() ? View.GONE : View.VISIBLE);
        updateTitle.setText((autoEdition ? "Ferry Auto " : "Ferry ") + update + " is ready");
        updateBody.setText(autoEdition
                ? "Install it from your computer, the same way you installed Ferry Auto. Your pairing and history stay."
                : "Download it, then install it over this version. Your pairing and history stay.");
        updateButton.setText(autoEdition ? "How to update" : "Download update");

        // The automatic-sending notice only while it is off; the chip shows its state otherwise.
        autoCard.setVisibility(paired && !auto ? View.VISIBLE : View.GONE);
        if (autoEdition) {
            autoTitle.setText("Send copies automatically");
            autoBody.setText("Skip the Send button. Ferry uses Android's accessibility permission to notice taps on Copy. It reads only the label of what you tap, not the rest of the screen.\n\n"
                    + "If the switch is greyed out, open App info, tap the ⋮ menu, then Allow restricted settings.");
            autoToggle.setText("Turn on");
            autoAppInfo.setVisibility(View.VISIBLE);
        } else {
            autoTitle.setText("Want copies to send by themselves?");
            autoBody.setText("That needs Ferry Auto, a second edition of this app. Google Play Protect blocks it in phone browsers, "
                    + "so you install it from a Windows computer with one script. Your pairing and history stay.");
            autoToggle.setText("How to get Ferry Auto");
            autoAppInfo.setVisibility(View.GONE);
        }

        // Chips: filled = on, outlined = off.
        chips.setVisibility(paired ? View.VISIBLE : View.GONE);
        autoChip.setVisibility(autoEdition ? View.VISIBLE : View.GONE);
        chip(autoChip, "Auto-send", auto);
        chip(imagesChip, "Images", Ferry.imagesOn(this));
        boolean shots = Ferry.screenshotsOn(this) && SyncService.canReadPhotos(this);
        chip(screenshotsChip, "Screenshots", shots);
        screenshotsChip.setEnabled(Ferry.imagesOn(this));
        boolean missingPermission = !SyncService.canReadPhotos(this) && p.getBoolean("screenshots", false);
        screenshotsBody.setVisibility(paired && (shots || missingPermission) ? View.VISIBLE : View.GONE);
        screenshotsBody.setText(missingPermission
                ? "Ferry needs the Photos permission set to \"Allow all\" to notice new screenshots. Open App info > Permissions > Photos."
                : "Each new screenshot goes to the laptop's clipboard by itself.");

        PowerManager pm = getSystemService(PowerManager.class);
        batteryCard.setVisibility(paired && !pm.isIgnoringBatteryOptimizations(getPackageName()) ? View.VISIBLE : View.GONE);
        String maker = Build.MANUFACTURER.toLowerCase(Locale.ROOT);
        boolean xiaomi = maker.contains("xiaomi") || maker.contains("redmi") || maker.contains("poco");
        xiaomiCard.setVisibility(paired && xiaomi ? View.VISIBLE : View.GONE);
    }

    private void renderHistory(JSONArray history) {
        historyList.removeAllViews();
        historyCard.setVisibility(history.length() > 1 ? View.VISIBLE : View.GONE);
        LayoutInflater inflater = getLayoutInflater();
        DateFormat time = DateFormat.getTimeInstance(DateFormat.SHORT);
        int rule = getColor(R.color.rule_row);
        for (int i = 1; i < history.length(); i++) {
            JSONObject item = history.optJSONObject(i);
            if (item == null) continue;
            String text = item.optString("text");
            long at = item.optLong("at");
            if (historyList.getChildCount() > 0) {
                View divider = new View(this);
                divider.setBackgroundColor(rule);
                historyList.addView(divider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
            }
            View row = inflater.inflate(R.layout.item_crossing, historyList, false);
            String when = DateUtils.isToday(at) ? time.format(new Date(at))
                    : DateUtils.formatDateTime(this, at, DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_ABBREV_MONTH);
            boolean toPhone = Ferry.TO_PHONE.equals(item.optString("dir"));
            ((TextView) row.findViewById(R.id.itemMeta)).setText(when);
            ((TextView) row.findViewById(R.id.itemArrow)).setText(toPhone ? "→" : "←");
            String image = item.optString("image", null);
            ((TextView) row.findViewById(R.id.itemText)).setText(image != null ? text : Ferry.preview(text));
            row.setContentDescription((toPhone ? "From laptop, " : "To laptop, ") + when + ". Copy again: " + (image != null ? text : Ferry.preview(text)));
            row.setOnClickListener(v -> {
                if (image == null) copyAgain(text);
                else copyImageAgain(image);
            });
            historyList.addView(row);
        }
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

    private void open(String url) {
        if (!url.isEmpty()) startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
    }

    private void copyImageAgain(String path) {
        java.io.File file = new java.io.File(path);
        if (path.isEmpty() || !file.isFile()) {
            Toast.makeText(this, path.isEmpty() ? "Only images that came to this phone can be copied again here" : "That image is no longer kept. Find it in Pictures/Ferry.", Toast.LENGTH_LONG).show();
            return;
        }
        getSystemService(ClipboardManager.class).setPrimaryClip(Images.clip(this, file));
        if (Build.VERSION.SDK_INT < 33) Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
    }

    private void copyAgain(String text) {
        Ferry.prefs(this).edit().putString("last_text", text).apply();   // so automatic sending does not send it back
        getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Ferry", text));
        if (Build.VERSION.SDK_INT < 33) Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();   // Android 13+ shows its own
    }

    private void style(Button b, boolean primary) {
        b.setBackgroundResource(primary ? R.drawable.bg_primary : R.drawable.bg_chip_off);
        b.setTextColor(getColor(primary ? R.color.on_act : R.color.ink));
    }

    private void chip(Button b, String name, boolean on) {
        b.setText(name);
        b.setBackgroundResource(on ? R.drawable.bg_chip_on : R.drawable.bg_chip_off);
        b.setTextColor(getColor(on ? R.color.chip_on_text : R.color.ink));
        b.setContentDescription(name + (on ? ", on" : ", off"));
    }
}
