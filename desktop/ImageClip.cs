using System.Drawing.Imaging;

namespace Ferry;

/// <summary>Images that cross: reading the clipboard, shrinking, saving, and putting them back on the clipboard.</summary>
static class ImageClip
{
    public const int MaxBytes = 10 * 1024 * 1024;   // ntfy.sh takes 15 MB files and limits daily use; Ferry stays well under
    const int ShrinkAbove = 1536 * 1024;

    /// <summary>Marks clipboard data that Ferry put there, so an image from the phone is not sent straight back.</summary>
    public const string Marker = "Ferry.FromPhone";

    /// <summary>The clipboard image as PNG bytes, or null.</summary>
    public static byte[]? FromClipboard()
    {
        // Browsers and the Snipping Tool also add a "PNG" format, which keeps transparency. Use it when it is there.
        if (Clipboard.GetData("PNG") is MemoryStream png) return png.ToArray();
        using var image = Clipboard.GetImage();
        if (image is null) return null;
        using var buffer = new MemoryStream();
        image.Save(buffer, ImageFormat.Png);
        return buffer.ToArray();
    }

    /// <summary>
    /// Big images take long on a slow connection, so anything over 1.5 MB is re-saved as a JPEG
    /// (quality 88, at most 2560 px on the long side). Returns null if it is still over 10 MB.
    /// </summary>
    public static byte[]? Shrink(byte[] image)
    {
        if (image.Length <= ShrinkAbove) return image;
        using var source = Image.FromStream(new MemoryStream(image));
        float scale = Math.Min(1f, 2560f / Math.Max(source.Width, source.Height));
        using var sized = new Bitmap(source, Math.Max(1, (int)(source.Width * scale)), Math.Max(1, (int)(source.Height * scale)));
        var jpeg = ImageCodecInfo.GetImageEncoders().First(c => c.FormatID == ImageFormat.Jpeg.Guid);
        using var quality = new EncoderParameters(1);
        quality.Param[0] = new EncoderParameter(Encoder.Quality, 88L);
        using var buffer = new MemoryStream();
        sized.Save(buffer, jpeg, quality);
        byte[] smaller = buffer.Length < image.Length ? buffer.ToArray() : image;
        return smaller.Length <= MaxBytes ? smaller : null;
    }

    /// <summary>Saves an image from the phone to Pictures\Ferry and returns the file path.</summary>
    public static string Save(byte[] image)
    {
        string dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.MyPictures), "Ferry");
        Directory.CreateDirectory(dir);
        bool jpg = image.Length > 3 && image[0] == 0xFF && image[1] == 0xD8;
        string path = Path.Combine(dir, $"Ferry-{DateTime.Now:yyyyMMdd-HHmmss}{(jpg ? ".jpg" : ".png")}");
        File.WriteAllBytes(path, image);
        return path;
    }

    /// <summary>Puts an image on the clipboard as a bitmap and as PNG, marked as Ferry's own.</summary>
    public static void ToClipboard(byte[] image)
    {
        using var decoded = Image.FromStream(new MemoryStream(image));
        var bitmap = new Bitmap(decoded);   // a copy that does not depend on the stream
        using var png = new MemoryStream();
        bitmap.Save(png, ImageFormat.Png);
        var data = new DataObject();
        data.SetImage(bitmap);
        data.SetData("PNG", new MemoryStream(png.ToArray()));
        data.SetData(Marker, new MemoryStream([1]));   // a stream, not a bool, so no object serialization is involved
        Clipboard.SetDataObject(data, true, 5, 100);
    }
}
