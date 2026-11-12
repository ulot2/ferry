package app.ferry;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.provider.MediaStore;
import android.util.Size;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Phone photos: lets the laptop look through this phone's photos, newest first, over a session that the laptop opened.
 * Only while "Let the laptop see photos" is on. The laptop (desktop/PhotosWindow.cs) must use the same message kinds.
 */
final class Photos {
    // From the laptop. LIST: the number of photos to skip (4 bytes). THUMB and FULL: the photo id (8 bytes).
    static final byte LIST = 1, THUMB = 2, FULL = 3;
    // To the laptop. PAGE: JSON {"photos":[{"id","t" (ms),"s" (bytes),"n" (name)}],"more":true/false}.
    // THUMB_DATA: id, then a JPEG of at most 256 pixels (empty when there is none). PART: id, then a part of the file.
    // END: id, then 1 when the whole file went, or 0 when it failed.
    static final byte PAGE = 1, THUMB_DATA = 2, PART = 3, END = 4;
    private static final int PAGE_SIZE = 200, CHUNK = 60 * 1024;
    private static final Uri IMAGES = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
    private static volatile Session current;

    /** Serves one session until it ends. Blocks: run it on its own thread. */
    static void serve(Context c, Session s) {
        Session old = current;
        current = s;
        if (old != null) old.close();   // one laptop, one photo session
        try {
            s.read((kind, body) -> {
                if (kind == LIST && body.length == 4) page(c, s, ByteBuffer.wrap(body).getInt());
                else if (kind == THUMB && body.length == 8) thumb(c, s, ByteBuffer.wrap(body).getLong());
                else if (kind == FULL && body.length == 8) full(c, s, ByteBuffer.wrap(body).getLong());
            });
        } finally {
            s.close();
            if (current == s) current = null;
        }
    }

    /** Ends the session now: the Stop button in the notification, or the switch turned off. */
    static void stop() {
        Session s = current;
        if (s != null) s.close();
    }

    private static void page(Context c, Session s, int skip) throws IOException {
        JSONArray list = new JSONArray();
        boolean more;
        // Newest first: by the date the photo was taken, or for photos without one, the date it came to the phone.
        String order = MediaStore.Images.Media.DATE_TAKEN + " DESC, " + MediaStore.Images.Media.DATE_ADDED + " DESC";
        String[] columns = {MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.SIZE, MediaStore.Images.Media.DISPLAY_NAME};
        try (Cursor cur = c.getContentResolver().query(IMAGES, columns, MediaStore.Images.Media.IS_PENDING + " = 0", null, order)) {
            if (cur == null) throw new IOException("No photo list");
            more = cur.getCount() > skip + PAGE_SIZE;
            if (skip >= 0 && cur.moveToPosition(skip)) {
                do {
                    long taken = cur.isNull(1) ? cur.getLong(2) * 1000 : cur.getLong(1);
                    list.put(new JSONObject().put("id", cur.getLong(0)).put("t", taken).put("s", cur.getLong(3))
                            .put("n", cur.isNull(4) ? "photo.jpg" : cur.getString(4)));
                } while (list.length() < PAGE_SIZE && cur.moveToNext());
            }
            s.sendAndWait(PAGE, new JSONObject().put("photos", list).put("more", more).toString().getBytes(StandardCharsets.UTF_8));
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    private static void thumb(Context c, Session s, long id) throws IOException {
        byte[] jpeg = new byte[0];
        try {
            Bitmap b = c.getContentResolver().loadThumbnail(ContentUris.withAppendedId(IMAGES, id), new Size(256, 256), null);
            for (int quality = 80; quality >= 40; quality -= 20) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                b.compress(Bitmap.CompressFormat.JPEG, quality, out);
                jpeg = out.toByteArray();
                if (jpeg.length <= Session.MAX_BODY - 8) break;
            }
            if (jpeg.length > Session.MAX_BODY - 8) jpeg = new byte[0];
            b.recycle();
        } catch (IOException | SecurityException e) {
            // deleted meanwhile, or not a photo this app may read: the laptop shows an empty tile
        }
        s.sendAndWait(THUMB_DATA, ByteBuffer.allocate(8 + jpeg.length).putLong(id).put(jpeg).array());
    }

    /** The whole file as it is on the phone. Android takes the location out of it unless Ferry asks for that permission, which it does not. */
    private static void full(Context c, Session s, long id) throws IOException {
        boolean ok = false;
        try (InputStream in = c.getContentResolver().openInputStream(ContentUris.withAppendedId(IMAGES, id))) {
            if (in != null) {
                byte[] buf = new byte[CHUNK];
                for (int n; (n = readFull(in, buf)) > 0; ) {
                    s.sendAndWait(PART, ByteBuffer.allocate(8 + n).putLong(id).put(Arrays.copyOf(buf, n)).array());
                }
                ok = true;
            }
        } catch (IOException | SecurityException e) {
            if (s.isClosed()) throw new IOException(e);
            // the file went away or cannot be read: END says so
        }
        s.sendAndWait(END, ByteBuffer.allocate(9).putLong(id).put((byte) (ok ? 1 : 0)).array());
    }

    private static int readFull(InputStream in, byte[] buf) throws IOException {
        int n = 0;
        for (int r; n < buf.length && (r = in.read(buf, n, buf.length - n)) != -1; ) n += r;
        return n;
    }

    private Photos() {}
}
