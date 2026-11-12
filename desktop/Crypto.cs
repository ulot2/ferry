using System.Security.Cryptography;
using System.Text;

namespace Ferry;

/// <summary>
/// End-to-end encryption, identical to the phone's Crypto.java. The pairing code is one 26-letter
/// secret shared only by the two devices (in the QR code). Both the ntfy.sh topic and the AES key
/// come from it, so ntfy.sh never sees the key and cannot read the text.
/// </summary>
static class Crypto
{
    const string Alphabet = "abcdefghijkmnpqrstuvwxyz23456789";   // no 0/o, 1/l look-alikes
    const string Prefix = "ferry1:";
    static readonly byte[] Aad = Encoding.UTF8.GetBytes("ferry1");
    static readonly byte[] AadImage = Encoding.UTF8.GetBytes("ferry1-image");   // an image can never be opened as text, or the reverse
    static readonly byte[] AadFile = Encoding.UTF8.GetBytes("ferry1-file");
    // Newer kinds get their own AAD, so an older Ferry cannot open them and ignores them instead of pasting them.
    static readonly byte[] AadPart = Encoding.UTF8.GetBytes("ferry1-part");
    static readonly byte[] AadIncoming = Encoding.UTF8.GetBytes("ferry1-incoming");
    static readonly byte[] AadLan = Encoding.UTF8.GetBytes("ferry1-lan");
    static readonly byte[] AadHello = Encoding.UTF8.GetBytes("ferry1-hello");
    static readonly byte[] AadNotification = Encoding.UTF8.GetBytes("ferry1-notification");
    static readonly byte[] AadSession = Encoding.UTF8.GetBytes("ferry1-session");

    public static string NewCode() => RandomNumberGenerator.GetString(Alphabet, 26);

    public static string Topic(string code) =>
        "ferry-" + Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes("ferry-topic:" + code)))[..24];

    static byte[] Key(string code) => SHA256.HashData(Encoding.UTF8.GetBytes("ferry-key:" + code));

    /// <summary>The code in groups of four, easier to read and type: "abcd efgh …".</summary>
    public static string Grouped(string code) => string.Join(' ', code.Chunk(4).Select(c => new string(c)));

    /// <summary>"ferry1:" + Base64(nonce | ciphertext | tag), the same layout Java's AES/GCM produces.</summary>
    public static string Seal(string code, string text) => Seal(code, text, Aad);

    /// <summary>Returns null when the message was not sealed with this code: a stranger, or an old unencrypted message.</summary>
    public static string? Open(string code, string message) => Open(code, message, Aad);

    /// <summary>The notice sent before a file: "size\nname".</summary>
    public static string SealIncoming(string code, long size, string name) => Seal(code, $"{size}\n{name}", AadIncoming);

    public static string? OpenIncoming(string code, string message) => Open(code, message, AadIncoming);

    /// <summary>A message that sets up a direct Wi-Fi transfer (JSON).</summary>
    public static string SealLan(string code, string json) => Seal(code, json, AadLan);

    public static string? OpenLan(string code, string message) => Open(code, message, AadLan);

    /// <summary>A phone notification (JSON: app, title, text). Its own AAD, so it can never be opened as a copy.</summary>
    public static string? OpenNotification(string code, string message) => Open(code, message, AadNotification);

    /// <summary>The first thing on a direct connection: the transfer id, sealed, so only our other device gets in.</summary>
    public static byte[] SealHello(string code, byte[] id) => SealRaw(code, id, AadHello);

    public static bool OpenHello(string code, byte[] sealedBytes, byte[] id) => OpenRaw(code, sealedBytes, AadHello) is { } got && got.AsSpan().SequenceEqual(id);

    /// <summary>
    /// One frame of a direct transfer. The AAD holds the transfer id, the frame number and the last-frame flag,
    /// so a frame cannot be moved, repeated, or cut off at the end without the other side seeing it.
    /// </summary>
    public static byte[] SealFrame(string code, byte[] id, long index, bool last, ReadOnlySpan<byte> plain) => SealRaw(code, plain, FrameAad(id, index, last));

    public static byte[]? OpenFrame(string code, byte[] id, long index, bool last, byte[] sealedBytes) => OpenRaw(code, sealedBytes, FrameAad(id, index, last));

    /// <summary>"ferry1-lan" | 16-byte id | 8-byte big-endian frame number | 1 if last, else 0</summary>
    static byte[] FrameAad(byte[] id, long index, bool last)
    {
        byte[] aad = [.. AadLan, .. id, 0, 0, 0, 0, 0, 0, 0, 0, (byte)(last ? 1 : 0)];
        System.Buffers.Binary.BinaryPrimitives.WriteInt64BigEndian(aad.AsSpan(AadLan.Length + 16, 8), index);
        return aad;
    }

    /// <summary>
    /// One frame of a session (Session.cs). The AAD holds the session id, the direction (0 phone to laptop, 1 laptop to phone)
    /// and the frame number, so a frame cannot be repeated, reordered, or sent back to the device that sealed it.
    /// </summary>
    public static byte[] SealSession(string code, byte[] id, byte direction, long index, ReadOnlySpan<byte> plain) => SealRaw(code, plain, SessionAad(id, direction, index));

    public static byte[]? OpenSession(string code, byte[] id, byte direction, long index, byte[] sealedBytes) => OpenRaw(code, sealedBytes, SessionAad(id, direction, index));

    /// <summary>"ferry1-session" | 16-byte id | direction | 8-byte big-endian frame number</summary>
    static byte[] SessionAad(byte[] id, byte direction, long index)
    {
        byte[] aad = [.. AadSession, .. id, direction, 0, 0, 0, 0, 0, 0, 0, 0];
        System.Buffers.Binary.BinaryPrimitives.WriteInt64BigEndian(aad.AsSpan(AadSession.Length + 17, 8), index);
        return aad;
    }

    static string Seal(string code, string text, byte[] aad) =>Prefix + Convert.ToBase64String(SealRaw(code, Encoding.UTF8.GetBytes(text), aad));

    static string? Open(string code, string message, byte[] aad)
    {
        if (!message.StartsWith(Prefix, StringComparison.Ordinal)) return null;
        try
        {
            byte[]? plain = OpenRaw(code, Convert.FromBase64String(message[Prefix.Length..].Trim()), aad);
            return plain is null ? null : Encoding.UTF8.GetString(plain);
        }
        catch (FormatException)
        {
            return null;
        }
    }

    /// <summary>Encrypts an image file (PNG or JPEG bytes) as raw nonce | ciphertext | tag, sent as a file.</summary>
    public static byte[] SealImage(string code, byte[] image) => SealRaw(code, image, AadImage);

    /// <summary>Returns null unless the bytes are an image sealed with this code.</summary>
    public static byte[]? OpenImage(string code, byte[] sealedBytes) => OpenRaw(code, sealedBytes, AadImage);

    /// <summary>Encrypts a file: 2-byte big-endian name length | UTF-8 name | contents. The name is sealed too, so ntfy.sh never sees it.</summary>
    public static byte[] SealFile(string code, string name, byte[] contents)
    {
        byte[] n = Encoding.UTF8.GetBytes(name);
        return SealRaw(code, [(byte)(n.Length >> 8), (byte)n.Length, .. n, .. contents], AadFile);
    }

    /// <summary>Returns null unless the bytes are a file sealed with this code.</summary>
    public static (string Name, byte[] Contents)? OpenFile(string code, byte[] sealedBytes)
    {
        byte[]? plain = OpenRaw(code, sealedBytes, AadFile);
        if (plain is null || plain.Length < 2) return null;
        int n = plain[0] << 8 | plain[1];
        if (plain.Length < 2 + n) return null;
        return (Encoding.UTF8.GetString(plain, 2, n), plain[(2 + n)..]);
    }

    /// <summary>
    /// One part of a file too large for one ntfy.sh attachment:
    /// 8-byte transfer id | 2-byte part index | 2-byte part count | 2-byte name length | UTF-8 name | contents. All big-endian.
    /// </summary>
    public static byte[] SealPart(string code, byte[] id, int index, int count, string name, byte[] contents)
    {
        byte[] n = Encoding.UTF8.GetBytes(name);
        return SealRaw(code, [.. id, (byte)(index >> 8), (byte)index, (byte)(count >> 8), (byte)count, (byte)(n.Length >> 8), (byte)n.Length, .. n, .. contents], AadPart);
    }

    /// <summary>Returns null unless the bytes are a file part sealed with this code.</summary>
    public static (string Id, int Index, int Count, string Name, byte[] Contents)? OpenPart(string code, byte[] sealedBytes)
    {
        byte[]? plain = OpenRaw(code, sealedBytes, AadPart);
        if (plain is null || plain.Length < 14) return null;
        int n = plain[12] << 8 | plain[13];
        if (plain.Length < 14 + n) return null;
        return (Convert.ToBase64String(plain, 0, 8), plain[8] << 8 | plain[9], plain[10] << 8 | plain[11], Encoding.UTF8.GetString(plain, 14, n), plain[(14 + n)..]);
    }

    static byte[] SealRaw(string code, ReadOnlySpan<byte> plain, byte[] aad)
    {
        byte[] output = new byte[12 + plain.Length + 16];
        RandomNumberGenerator.Fill(output.AsSpan(0, 12));
        using var aes = new AesGcm(Key(code), 16);
        aes.Encrypt(output.AsSpan(0, 12), plain, output.AsSpan(12, plain.Length), output.AsSpan(12 + plain.Length, 16), aad);
        return output;
    }

    static byte[]? OpenRaw(string code, byte[] all, byte[] aad)
    {
        if (all.Length < 12 + 16) return null;
        try
        {
            byte[] plain = new byte[all.Length - 28];
            using var aes = new AesGcm(Key(code), 16);
            aes.Decrypt(all.AsSpan(0, 12), all.AsSpan(12, plain.Length), all.AsSpan(12 + plain.Length, 16), plain, aad);
            return plain;
        }
        catch (CryptographicException)
        {
            return null;
        }
    }
}
