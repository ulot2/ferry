package app.ferry;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** One screen: the topic, and a button to let Ferry run in the background. */
public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 0);

        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(pad, pad, pad, pad);

        TextView help = new TextView(this);
        help.setTextSize(16);
        help.setText("Type the same topic that is in topic.txt on your laptop.\n\n"
                + "Laptop to phone: copy on the laptop. The text goes into the clipboard of this phone.\n\n"
                + "Phone to laptop: copy the text. Then tap \"Send clipboard\" in the Ferry notification "
                + "or in the quick settings tile. You can also share text to \"Send to laptop\".\n\n"
                + "On Xiaomi phones, also turn on Autostart for Ferry in Settings > Apps.");

        EditText topic = new EditText(this);
        topic.setHint("Topic");
        topic.setSingleLine();
        topic.setText(Ferry.topic(this));

        Button save = new Button(this);
        save.setText("Save and connect");
        save.setOnClickListener(v -> {
            String t = topic.getText().toString().trim();
            if (!t.matches("[A-Za-z0-9_-]{1,64}")) {   // ntfy.sh topic rules; also keeps the URL safe
                topic.setError("Use only letters, numbers, - and _.");
                return;
            }
            getSharedPreferences("ferry", MODE_PRIVATE).edit().putString("topic", t).apply();
            stopService(new Intent(this, SyncService.class));   // restart so the new topic is used
            SyncService.start(this);
            Toast.makeText(this, "Connecting", Toast.LENGTH_SHORT).show();
        });

        // Without this, Android cuts the connection when the phone sleeps.
        Button battery = new Button(this);
        battery.setText("Let Ferry run in the background");
        battery.setOnClickListener(v -> startActivity(new Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName()))));

        col.addView(help);
        col.addView(topic);
        col.addView(save);
        col.addView(battery);

        ScrollView root = new ScrollView(this);
        root.setFitsSystemWindows(true);   // keep content clear of the status and navigation bars
        root.addView(col);
        setContentView(root);

        if (!Ferry.topic(this).isEmpty()) SyncService.start(this);
    }
}
