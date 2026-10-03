package app.ferry;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.TileService;

/** Quick-settings tile: one tap sends the clipboard to the laptop. */
public class SendTile extends TileService {
    @Override
    @SuppressWarnings("deprecation")
    public void onClick() {
        Intent i = new Intent(this, SendActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE));
        } else {
            startActivityAndCollapse(i);
        }
    }
}
