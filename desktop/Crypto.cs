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

    public static string NewCode() => RandomNumberGenerator.GetString(Alphabet, 26);

    public static string Topic(string code) =>
        "ferry-" + Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes("ferry-topic:" + code)))[..24];

    static byte[] Key(string code) => SHA256.HashData(Encoding.UTF8.GetBytes("ferry-key:" + code));

    /// <summary>The code in groups of four, easier to read and type: "abcd efgh …".</summary>
    public static string Grouped(string code) => string.Join(' ', code.Chunk(4).Select(c => new string(c)));

    /// <summary>"ferry1:" + Base64(nonce | ciphertext | tag), the same layout Java's AES/GCM produces.</summary>
    public static string Seal(string code, string text)
    {
        byte[] plain = Encoding.UTF8.GetBytes(text);
        byte[] output = new byte[12 + plain.Length + 16];
        RandomNumberGenerator.Fill(output.AsSpan(0, 12));
        using var aes = new AesGcm(Key(code), 16);
        aes.Encrypt(output.AsSpan(0, 12), plain, output.AsSpan(12, plain.Length), output.AsSpan(12 + plain.Length, 16), Aad);
        return Prefix + Convert.ToBase64String(output);
    }

    /// <summary>Returns null when the message was not sealed with this code: a stranger, or an old unencrypted message.</summary>
    public static string? Open(string code, string message)
    {
        if (!message.StartsWith(Prefix, StringComparison.Ordinal)) return null;
        try
        {
            byte[] all = Convert.FromBase64String(message[Prefix.Length..].Trim());
            if (all.Length < 12 + 16) return null;
            byte[] plain = new byte[all.Length - 28];
            using var aes = new AesGcm(Key(code), 16);
            aes.Decrypt(all.AsSpan(0, 12), all.AsSpan(12, plain.Length), all.AsSpan(12 + plain.Length, 16), plain, Aad);
            return Encoding.UTF8.GetString(plain);
        }
        catch (Exception e) when (e is FormatException or CryptographicException)
        {
            return null;
        }
    }
}
