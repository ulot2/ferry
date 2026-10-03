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

    public static string NewCode() => RandomNumberGenerator.GetString(Alphabet, 26);

    public static string Topic(string code) =>
        "ferry-" + Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes("ferry-topic:" + code)))[..24];

    static byte[] Key(string code) => SHA256.HashData(Encoding.UTF8.GetBytes("ferry-key:" + code));

    /// <summary>The code in groups of four, easier to read and type: "abcd efgh …".</summary>
    public static string Grouped(string code) => string.Join(' ', code.Chunk(4).Select(c => new string(c)));

    /// <summary>"ferry1:" + Base64(nonce | ciphertext | tag), the same layout Java's AES/GCM produces.</summary>
    public static string Seal(string code, string text) => Prefix + Convert.ToBase64String(SealRaw(code, Encoding.UTF8.GetBytes(text), Aad));

    /// <summary>Returns null when the message was not sealed with this code: a stranger, or an old unencrypted message.</summary>
    public static string? Open(string code, string message)
    {
        if (!message.StartsWith(Prefix, StringComparison.Ordinal)) return null;
        try
        {
            byte[]? plain = OpenRaw(code, Convert.FromBase64String(message[Prefix.Length..].Trim()), Aad);
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

    static byte[] SealRaw(string code, byte[] plain, byte[] aad)
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
