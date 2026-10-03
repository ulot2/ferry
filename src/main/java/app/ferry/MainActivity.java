package app.ferry;

import android.Manifest;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.format.DateUtils;
import android.view.View;
import android.view.WindowInsets;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions;
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning;

import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;

/** The terminal screen: link status, last crossing, Send, pairing, and notices. See DESIGN.md. */
public class MainActivity extends Activity implements SharedPreferences.OnSharedPreferenceChangeListener {
    private View header, lamp, ticket, manualRow, batteryCard, xiaomiCard;
    private TextView statusText, headerLine, route, preview, stubTime, stubDay, pairTitle, pairBody, pairError;
    private Button send, scan, pairAlt;
    private EditText topicField;
    private int headerTop;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 0);

        header = findViewById(R.id.header);
        lamp = findViewById(R.id.lamp);
        ticket = findViewById(R.id.ticket);
        manualRow = findViewById(R.id.manualRow);
        batteryCard = findViewById(R.id.batteryCard);
        xiaomiCard = findViewById(R.id.xiaomiCard);
        statusText = findViewById(R.id.statusText);
        headerLine = findViewById(R.id.headerLine);
        route = findViewById(R.id.route);
        preview = findViewById(R.id.preview);
        stubTime = findViewById(R.id.stubTime);
        stubDay = findViewById(R.id.stubDay);
        pairTitle = findViewById(R.id.pairTitle);
        pairBody = findViewById(R.id.pairBody);
        pairError = findViewById(R.id.pairError);
        send = findViewById(R.id.send);
        scan = findViewById(R.id.scan);
        pairAlt = findViewById(R.id.pairAlt);
        topicField = findViewById(R.id.topic);

        // Edge to edge: the navy header runs under the status bar; the list clears the navigation bar.
        headerTop = header.getPaddingTop();
        View scroll = findViewById(R.id.scroll);
        scroll.setOnApplyWindowInsetsListener(this::applyInsets);

        send.setOnClickListener(v -> startActivity(new Intent(this, SendActivity.class)));
        scan.setOnClickListener(v -> scan());
        pairAlt.setOnClickListener(v -> {
            if (paired()) {
                Ferry.unpair(this);
            } else {
                manualRow.setVisibility(manualRow.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
                if (manualRow.getVisibility() == View.VISIBLE) topicField.requestFocus();
            }
            render();
        });
        findViewById(R.id.manualSave).setOnClickListener(v -> {
            String t = topicField.getText().toString().trim();
            if (!Ferry.validTopic(t)) {
                topicField.setError("Use only letters, numbers, - and _. The code is under the QR code on your laptop.");
                return;
            }
            Ferry.pair(this, t, null);
            manualRow.setVisibility(View.GONE);
            render();
        });
        findViewById(R.id.battery).setOnClickListener(v -> startActivity(new Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName()))));
        findViewById(R.id.appSettings).setOnClickListener(v -> startActivity(new Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))));

        handle(getIntent());
        if (paired()) SyncService.start(this);
    }

    // The system-window inset getters cover API 29, where WindowInsets.Type does not exist yet.
    @SuppressWarnings("deprecation")
    private WindowInsets applyInsets(View v, WindowInsets insets) {
        header.setPadding(header.getPaddingLeft(), headerTop + insets.getSystemWindowInsetTop(),
                header.getPaddingRight(), header.getPaddingBottom());
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
        render();   // also picks up a battery setting changed while we were away
    }

    @Override
    protected void onPause() {
        super.onPause();
        Ferry.prefs(this).unregisterOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences p, String key) {
        render();
        if ("cross_time".equals(key) && ValueAnimator.areAnimatorsEnabled()) {
            // The one motion moment: a new ticket slides up into place.
            ticket.setAlpha(0f);
            ticket.setTranslationY(8 * getResources().getDisplayMetrics().density);
            ticket.animate().alpha(1f).translationY(0f).setDuration(200).setInterpolator(new DecelerateInterpolator()).start();
        }
    }

    private boolean paired() {
        return !Ferry.topic(this).isEmpty();
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
        boolean paired = paired();
        String peer = p.getString("peer", "");
        String laptop = peer.isEmpty() ? "your laptop" : peer;

        // Status lamp: ship lights. Green = connected, red = offline, muted = connecting or not paired.
        String status = paired ? p.getString("status", Ferry.STATUS_CONNECTING) : "";
        int lampColor;
        String label;
        if (Ferry.STATUS_CONNECTED.equals(status)) {
            lampColor = getColor(R.color.lamp_connected);
            label = "Connected";
        } else if (Ferry.STATUS_OFFLINE.equals(status)) {
            lampColor = getColor(R.color.lamp_offline);
            label = "Offline";
        } else {
            lampColor = getColor(R.color.on_harbor_muted);
            label = paired ? "Connecting" : "Not paired";
        }
        lamp.setBackgroundTintList(ColorStateList.valueOf(lampColor));
        statusText.setText(label);
        headerLine.setText(paired
                ? "Copies from " + laptop + " land on this phone. Tap Send to cross the other way."
                : "Ferry moves your clipboard between your laptop and this phone.");

        // The ticket.
        long time = p.getLong("cross_time", 0);
        if (time == 0) {
            route.setText(paired ? "No crossings yet" : "Not paired yet");
            preview.setText(paired ? "Copy something on your laptop. It lands here." : "Pair with your laptop to start.");
            stubTime.setText("—");
            stubDay.setText("");
        } else {
            route.setText(p.getString("cross_dir", ""));
            preview.setText("“" + p.getString("cross_text", "") + "”");
            stubTime.setText(DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(time)));
            stubDay.setText(DateUtils.isToday(time) ? "TODAY"
                    : DateUtils.formatDateTime(this, time, DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_ABBREV_MONTH).toUpperCase(Locale.getDefault()));
        }

        // One primary action per state: Send when paired, Scan when not.
        send.setVisibility(paired ? View.VISIBLE : View.GONE);
        if (paired) {
            pairTitle.setText("Paired with " + laptop);
            String t = Ferry.topic(this);
            pairBody.setText("Pairing code ends in " + t.substring(Math.max(0, t.length() - 5)) + ". Scan a new code if you reset pairing on the laptop.");
            styleSecondary(scan);
            scan.setText("Scan new code");
            pairAlt.setText("Unpair");
        } else {
            pairTitle.setText("Pair with your laptop");
            pairBody.setText("On your laptop, open Ferry from the tray. Then scan the QR code it shows.");
            stylePrimary(scan);
            scan.setText("Scan QR code");
            pairAlt.setText(manualRow.getVisibility() == View.VISIBLE ? "Hide code entry" : "Type the code");
        }

        PowerManager pm = getSystemService(PowerManager.class);
        batteryCard.setVisibility(paired && !pm.isIgnoringBatteryOptimizations(getPackageName()) ? View.VISIBLE : View.GONE);
        String maker = Build.MANUFACTURER.toLowerCase(Locale.ROOT);
        boolean xiaomi = maker.contains("xiaomi") || maker.contains("redmi") || maker.contains("poco");
        xiaomiCard.setVisibility(paired && xiaomi ? View.VISIBLE : View.GONE);
    }

    private void stylePrimary(Button b) {
        b.setBackgroundResource(R.drawable.bg_primary);
        b.setTextColor(getColor(R.color.on_signal));
    }

    private void styleSecondary(Button b) {
        b.setBackgroundResource(R.drawable.bg_secondary);
        b.setTextColor(getColor(R.color.ink));
    }
}
