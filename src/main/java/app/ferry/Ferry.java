package app.ferry;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Shared bits: the saved topic and talking to ntfy.sh. */
final class Ferry {
    static final String SERVER = "https://ntfy.sh/";

    static String topic(Context c) {
        return c.getSharedPreferences("ferry", Context.MODE_PRIVATE).getString("topic", "");
    }

    /** Sends text to the laptop. Blocks, so call it off the main thread. */
    static void send(String topic, String text) throws IOException {
        HttpURLConnection con = (HttpURLConnection) new URL(SERVER + topic).openConnection();
        try {
            con.setRequestMethod("POST");
            con.setDoOutput(true);
            con.setConnectTimeout(10_000);
            con.setReadTimeout(15_000);
            con.setRequestProperty("Tags", "phone");   // lets this phone skip its own message when it comes back
            con.setRequestProperty("Title", "From phone");
            try (OutputStream out = con.getOutputStream()) {
                out.write(text.getBytes(StandardCharsets.UTF_8));
            }
            int code = con.getResponseCode();
            if (code != 200) throw new IOException("ntfy.sh answered " + code);
        } finally {
            con.disconnect();
        }
    }

    static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        for (int n; (n = in.read(chunk)) != -1; ) buf.write(chunk, 0, n);
        return buf.toString("UTF-8");
    }

    private Ferry() {}
}
