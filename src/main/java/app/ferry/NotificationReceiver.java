package app.ferry;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import java.io.IOException;

/**
 * A phone notification from Roost, to show on the laptop. Roost shares its identity with the broadcast
 * (Android 14+), so Ferry takes it only from app.roost signed with Roost's key. Any other app is ignored:
 * without this check, any app could put fake notifications on the laptop.
 * Nothing goes on the clipboard or in the history.
 */
public class NotificationReceiver extends BroadcastReceiver {
    private static final String ROOST = "app.roost";
    /** SHA-256 of Roost's signing certificate. */
    private static final byte[] ROOST_CERT = {
            (byte) 0x1E, (byte) 0x6F, (byte) 0xC0, (byte) 0x64, (byte) 0xFF, (byte) 0x64, (byte) 0xC2, (byte) 0x71,
            (byte) 0x37, (byte) 0x6B, (byte) 0x8D, (byte) 0x51, (byte) 0xEF, (byte) 0xD9, (byte) 0x35, (byte) 0xB0,
            (byte) 0xD5, (byte) 0x54, (byte) 0x4B, (byte) 0x9C, (byte) 0x70, (byte) 0x88, (byte) 0xA3, (byte) 0x70,
            (byte) 0x81, (byte) 0xBC, (byte) 0xD9, (byte) 0x47, (byte) 0xC8, (byte) 0x24, (byte) 0x34, (byte) 0x1F};

    @Override
    public void onReceive(Context c, Intent intent) {
        if (Build.VERSION.SDK_INT < 34 || !ROOST.equals(getSentFromPackage())
                || !c.getPackageManager().hasSigningCertificate(ROOST, ROOST_CERT, PackageManager.CERT_INPUT_SHA256)) return;
        String app = intent.getStringExtra("app"), title = intent.getStringExtra("title"), text = intent.getStringExtra("text");
        PendingResult done = goAsync();
        new Thread(() -> {
            try {
                Ferry.sendNotification(c, app == null ? "" : app, title == null ? "" : title, text == null ? "" : text);
            } catch (IOException e) {
                Log.w("Ferry", "notification not sent: " + e);   // offline or not paired: a late notification is no use, so it is dropped
            } finally {
                done.finish();
            }
        }).start();
    }
}
