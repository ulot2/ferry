package app.ferry;

import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.provider.MediaStore;

import androidx.core.content.FileProvider;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Image files that cross: reading, shrinking, saving, and putting them on the clipboard. */
final class Images {
    static final String AUTHORITY = "app.ferry.files";
    static final int MAX_BYTES = 10 * 1024 * 1024;   // ntfy.sh takes 15 MB files and limits daily use; Ferry stays well under
    private static final int SHRINK_ABOVE = 1536 * 1024;

    /** Reads an image someone shared or copied. Returns null if it is unreadable or far too large to bother with. */
    static byte[] read(Context c, Uri uri) throws IOException {
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            if (in == null) return null;
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[64 * 1024];
            for (int n; (n = in.read(chunk)) != -1; ) {
                buf.write(chunk, 0, n);
                if (buf.size() > 4 * MAX_BYTES) return null;
            }
            return buf.toByteArray();
        }
    }

    /**
     * Big images take long on a slow connection, so anything over 1.5 MB is re-saved as a JPEG
     * (quality 88, at most 2560 px on the long side). Returns null if it is still over 10 MB.
     */
    static byte[] shrink(byte[] image) {
        if (image.length <= SHRINK_ABOVE) return image;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(image, 0, image.length, bounds);
        BitmapFactory.Options opts = new BitmapFactory.Options();
        int longSide = Math.max(bounds.outWidth, bounds.outHeight);
        while (longSide / opts.inSampleSize > 2560 * 2) opts.inSampleSize *= 2;
        Bitmap bmp = BitmapFactory.decodeByteArray(image, 0, image.length, opts);
        if (bmp == null) return image.length <= MAX_BYTES ? image : null;
        float scale = Math.min(1f, 2560f / Math.max(bmp.getWidth(), bmp.getHeight()));
        if (scale < 1f) bmp = Bitmap.createScaledBitmap(bmp, Math.round(bmp.getWidth() * scale), Math.round(bmp.getHeight() * scale), true);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.JPEG, 88, out);
        byte[] smaller = out.size() < image.length ? out.toByteArray() : image;
        return smaller.length <= MAX_BYTES ? smaller : null;
    }

    static String extension(byte[] image) {
        return image.length > 3 && (image[0] & 0xFF) == 0xFF && (image[1] & 0xFF) == 0xD8 ? "jpg" : "png";
    }

    /**
     * Keeps a received image in the app's own storage (for the clipboard and "copy again") and
     * saves a copy to the gallery in Pictures/Ferry. Returns the private file.
     */
    static File keep(Context c, byte[] image) throws IOException {
        String name = "Ferry-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date()) + "." + extension(image);
        File dir = new File(c.getCacheDir(), "images");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create " + dir);
        File file = new File(dir, name);
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(image);
        }
        ContentResolver cr = c.getContentResolver();
        ContentValues v = new ContentValues();
        v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        v.put(MediaStore.Images.Media.MIME_TYPE, "jpg".equals(extension(image)) ? "image/jpeg" : "image/png");
        v.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Ferry");
        v.put(MediaStore.Images.Media.IS_PENDING, 1);
        Uri item = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
        if (item != null) {
            try (OutputStream out = cr.openOutputStream(item)) {
                if (out != null) out.write(image);
            }
            v.clear();
            v.put(MediaStore.Images.Media.IS_PENDING, 0);
            cr.update(item, v, null, null);
        }
        prune(dir);
        return file;
    }

    /** Puts a kept image on the clipboard. Apps that can paste images get read access when they paste. */
    static ClipData clip(Context c, File file) {
        Uri uri = FileProvider.getUriForFile(c, AUTHORITY, file);
        return ClipData.newUri(c.getContentResolver(), "Image from Ferry", uri);
    }

    static boolean isOurs(Uri uri) {
        return uri != null && AUTHORITY.equals(uri.getAuthority());
    }

    /** Only the last 10 images can be copied again from the history, so older private copies go. */
    private static void prune(File dir) {
        File[] files = dir.listFiles();
        if (files == null || files.length <= 10) return;
        java.util.Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (int i = 10; i < files.length; i++) //noinspection ResultOfMethodCallIgnored
            files[i].delete();
    }

    private Images() {}
}
