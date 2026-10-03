package app.ferry;

import android.content.Context;
import android.content.pm.PackageManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Checks GitHub for a newer Ferry release, at most every 6 hours. The result goes into prefs for MainActivity. */
final class Updates {
    private static final String LATEST = "https://api.github.com/repos/ulot2/ferry/releases/latest";

    static void checkSoon(Context c) {
        long last = Ferry.prefs(c).getLong("update_checked", 0);
        if (System.currentTimeMillis() - last < 6 * 3600_000L) return;
        Ferry.prefs(c).edit().putLong("update_checked", System.currentTimeMillis()).apply();
        new Thread(() -> check(c.getApplicationContext())).start();
    }

    private static void check(Context c) {
        try {
            HttpURLConnection con = (HttpURLConnection) new URL(LATEST).openConnection();
            con.setConnectTimeout(10_000);
            con.setReadTimeout(15_000);
            con.setRequestProperty("Accept", "application/vnd.github+json");
            JSONObject release;
            try (InputStream in = con.getInputStream()) {
                release = new JSONObject(Ferry.readAll(in));
            } finally {
                con.disconnect();
            }
            String version = release.getString("tag_name").replaceFirst("^v", "");
            String file = Ferry.autoEdition(c) ? "Ferry-Auto.apk" : "Ferry.apk";   // stay on the same edition
            String apk = "";
            JSONArray assets = release.optJSONArray("assets");
            for (int i = 0; assets != null && i < assets.length(); i++) {
                JSONObject a = assets.getJSONObject(i);
                if (file.equals(a.optString("name"))) apk = a.optString("browser_download_url");
            }
            boolean newer = !apk.isEmpty() && newer(version, installed(c));
            Ferry.prefs(c).edit()
                    .putString("update_version", newer ? version : "")
                    .putString("update_url", newer ? apk : "")
                    .apply();
        } catch (Exception ignored) {
            // offline or GitHub unreachable: try again at the next check
        }
    }

    static String installed(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "0";
        }
    }

    /** Compares the numbers before any "-" suffix: "1.2.0" is newer than "1.1.0-dev.14". */
    static boolean newer(String latest, String current) {
        int[] a = parts(latest), b = parts(current);
        for (int i = 0; i < 3; i++) if (a[i] != b[i]) return a[i] > b[i];
        return false;
    }

    private static int[] parts(String v) {
        int[] out = new int[3];
        String[] p = (v == null ? "" : v).split("-")[0].split("\\.");
        for (int i = 0; i < 3 && i < p.length; i++) {
            try {
                out[i] = Integer.parseInt(p[i]);
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    private Updates() {}
}
