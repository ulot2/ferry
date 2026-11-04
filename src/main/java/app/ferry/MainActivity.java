package app.ferry;

import android.Manifest;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
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
import android.widget.ImageView;
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

/** The chart screen: link status, last crossing, Send, one notice at most, and the logbook. Pairing until paired. See DESIGN.md. */
public class MainActivity extends Activity implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static final String FERRY_AUTO_HELP = "https://github.com/ulot2/ferry#ferry-auto-automatic-sending";
    private View header, lamp, crossing, manualRow, batteryCard, updateCard, autoCard, historyCard, pairCard;
    private TextView statusText, route, preview, pairTitle, pairBody, pairError, updateTitle, updateBody;
    private Button send, pairAlt, updateButton;
    private ViewGroup historyList;
    private ChartView chart;
    private EditText codeField;
    private View wordmark, statusPill, settings;
    private int wordmarkTop, pillTop, settingsTop, headerHeight;
    private String theme;   // the theme this screen was built with; Settings can change it

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        Configuration o = Ferry.themeOverride(base);
        if (o != null) applyOverrideConfiguration(o);
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        theme = Ferry.theme(this);
        setContentView(R.layout.activity_main);
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 0);

        header = findViewById(R.id.header);
        chart = findViewById(R.id.chart);
        lamp = findViewById(R.id.lamp);
        crossing = findViewById(R.id.crossing);
        manualRow = findViewById(R.id.manualRow);
        batteryCard = findViewById(R.id.batteryCard);
        updateCard = findViewById(R.id.updateCard);
        autoCard = findViewById(R.id.autoCard);
        historyCard = findViewById(R.id.historyCard);
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
        send = findViewById(R.id.send);
        pairAlt = findViewById(R.id.pairAlt);
        historyList = findViewById(R.id.historyList);
        codeField = findViewById(R.id.topic);

        // Edge to edge: the chart runs under the status bar; the content clears the navigation bar.
        wordmark = findViewById(R.id.wordmark);
        statusPill = findViewById(R.id.statusPill);
        settings = findViewById(R.id.settings);
        wordmarkTop = ((ViewGroup.MarginLayoutParams) wordmark.getLayoutParams()).topMargin;
        pillTop = ((ViewGroup.MarginLayoutParams) statusPill.getLayoutParams()).topMargin;
        settingsTop = ((ViewGroup.MarginLayoutParams) settings.getLayoutParams()).topMargin;
        headerHeight = header.getLayoutParams().height;
        findViewById(R.id.scroll).setOnApplyWindowInsetsListener(this::applyInsets);

        boolean autoEdition = Ferry.autoEdition(this);
        settings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        send.setOnClickListener(v -> startActivity(new Intent(this, SendActivity.class)));
        findViewById(R.id.scan).setOnClickListener(v -> scan());
        pairAlt.setOnClickListener(v -> {
            manualRow.setVisibility(manualRow.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
            if (manualRow.getVisibility() == View.VISIBLE) codeField.requestFocus();
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
        findViewById(R.id.clearHistory).setOnClickListener(v -> Ferry.clearHistory(this));
        // Ferry Auto cannot be installed from the phone (Play Protect), so its update button leads to the computer steps instead.
        updateButton.setOnClickListener(v -> open(autoEdition ? FERRY_AUTO_HELP : Ferry.prefs(this).getString("update_url", "")));
        findViewById(R.id.autoToggle).setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        findViewById(R.id.autoAppInfo).setOnClickListener(v -> startActivity(new Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))));
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
        // The chart fills the whole header, under the status bar; only the wordmark, the pill and the settings button move down.
        ((ViewGroup.MarginLayoutParams) wordmark.getLayoutParams()).topMargin = wordmarkTop + top;
        ((ViewGroup.MarginLayoutParams) statusPill.getLayoutParams()).topMargin = pillTop + top;
        ((ViewGroup.MarginLayoutParams) settings.getLayoutParams()).topMargin = settingsTop + top;
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
        if (!theme.equals(Ferry.theme(this))) {
            recreate();   // the theme changed in Settings
            return;
        }
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
        String peer = p.getString("peer", "");

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
            preview.setText(last.has("file") ? last.optString("text") + (toPhone ? ", in Downloads/Ferry" : ", in Downloads\\Ferry on the laptop")
                    : !last.has("image") ? "“" + Ferry.preview(last.optString("text")) + "”"
                    : toPhone ? last.optString("text") + ", in your clipboard and in Pictures/Ferry"
                    : last.optString("text") + ", in the laptop's clipboard");
        }
        renderHistory(history);

        // One primary action per state: Send when paired, Scan when not. Pairing shows only until paired; after that it is in Settings.
        send.setVisibility(paired ? View.VISIBLE : View.GONE);
        pairCard.setVisibility(paired ? View.GONE : View.VISIBLE);
        if (!paired) {
            pairTitle.setText(p.contains("topic") ? "Pair again" : "Pair with your laptop");
            pairBody.setText(p.contains("topic")
                    ? "Ferry now encrypts your text. Update Ferry on your laptop, then scan the new QR code it shows."
                    : "On your laptop, open Ferry from the tray. Then scan the QR code it shows.");
            pairAlt.setText(manualRow.getVisibility() == View.VISIBLE ? "Hide code entry" : "Type the code");
        }

        // At most one notice, the most urgent first: an update, then background use, then automatic sending (Ferry Auto).
        String update = p.getString("update_version", "");
        // The saved result may predate an install (for example Ferry Auto over Ferry), so check it against this version.
        if (!update.isEmpty() && !Updates.newer(update, Updates.installed(this))) update = "";
        boolean showUpdate = !update.isEmpty();
        boolean showBattery = !showUpdate && paired && !getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName());
        boolean showAuto = !showUpdate && !showBattery && paired && autoEdition && !Ferry.autoSendOn(this);
        updateCard.setVisibility(showUpdate ? View.VISIBLE : View.GONE);
        batteryCard.setVisibility(showBattery ? View.VISIBLE : View.GONE);
        autoCard.setVisibility(showAuto ? View.VISIBLE : View.GONE);
        updateTitle.setText((autoEdition ? "Ferry Auto " : "Ferry ") + update + " is ready");
        updateBody.setText(autoEdition
                ? "Install it from your computer, the same way you installed Ferry Auto. Your pairing and history stay."
                : "Download it, then install it over this version. Your pairing and history stay.");
        updateButton.setText(autoEdition ? "How to update" : "Download update");
    }

    private void renderHistory(JSONArray history) {
        historyList.removeAllViews();
        historyCard.setVisibility(history.length() > 1 ? View.VISIBLE : View.GONE);
        LayoutInflater inflater = getLayoutInflater();
        DateFormat time = DateFormat.getTimeInstance(DateFormat.SHORT);
        int rule = getColor(R.color.rule_row);
        float dp = getResources().getDisplayMetrics().density;
        String group = null;
        for (int i = 1; i < history.length(); i++) {
            JSONObject item = history.optJSONObject(i);
            if (item == null) continue;
            String text = item.optString("text");
            long at = item.optLong("at");
            // Newest first, so a heading goes in where the day group changes: Today, Yesterday, then Earlier.
            String g = DateUtils.isToday(at) ? "TODAY" : DateUtils.isToday(at + DateUtils.DAY_IN_MILLIS) ? "YESTERDAY" : "EARLIER";
            if (!g.equals(group)) {
                group = g;
                TextView heading = new TextView(this, null, 0, R.style.Ferry_Kicker);
                heading.setText(g);
                heading.setPadding(0, (int) ((historyList.getChildCount() == 0 ? 14 : 22) * dp), 0, (int) (4 * dp));
                heading.setAccessibilityHeading(true);
                historyList.addView(heading);
            } else {
                View divider = new View(this);
                divider.setBackgroundColor(rule);
                historyList.addView(divider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
            }
            View row = inflater.inflate(R.layout.item_crossing, historyList, false);
            // Earlier rows show the date; Today and Yesterday rows show the time, because the heading gives the day.
            String when = g.equals("EARLIER") ? DateUtils.formatDateTime(this, at, DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_ABBREV_MONTH)
                    : time.format(new Date(at));
            boolean toPhone = Ferry.TO_PHONE.equals(item.optString("dir"));
            ((TextView) row.findViewById(R.id.itemMeta)).setText(when);
            ((TextView) row.findViewById(R.id.itemArrow)).setText(toPhone ? "→" : "←");
            String image = item.optString("image", null), file = item.optString("file", null);
            boolean link = file == null && image == null && Ferry.link(text) != null;
            ((ImageView) row.findViewById(R.id.itemKind)).setImageResource(file != null ? R.drawable.ic_kind_file
                    : image != null ? R.drawable.ic_kind_image : link ? R.drawable.ic_kind_link : R.drawable.ic_kind_text);
            ((TextView) row.findViewById(R.id.itemText)).setText(image != null || file != null ? text : Ferry.preview(text));
            String kind = file != null ? "File" : image != null ? "Image" : link ? "Link" : "Text";
            row.setContentDescription(kind + (toPhone ? " from laptop, " : " to laptop, ") + when + (file != null ? ". Open file: " + text
                    : ". Copy again: " + (image != null ? text : Ferry.preview(text))));
            row.setOnClickListener(v -> {
                if (file != null) openFile(file);
                else if (image == null) copyAgain(text);
                else copyImageAgain(image);
            });
            historyList.addView(row);
        }
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

    private void openFile(String uri) {
        if (uri.isEmpty()) {
            Toast.makeText(this, "Only files that came to this phone can be opened here", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            startActivity(Ferry.viewFile(this, Uri.parse(uri)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No app on this phone opens this kind of file. Find it in Downloads/Ferry.", Toast.LENGTH_LONG).show();
        }
    }

    private void copyAgain(String text) {
        Ferry.prefs(this).edit().putString("last_text", text).apply();   // so automatic sending does not send it back
        getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Ferry", text));
        if (Build.VERSION.SDK_INT < 33) Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();   // Android 13+ shows its own
    }
}
