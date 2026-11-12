using System.Buffers.Binary;
using System.Net.Sockets;
using System.Text.Json;

namespace Ferry;

/// <summary>
/// A session: one encrypted connection that stays open while a feature is in use (Helm). As for files, the phone
/// listens and the laptop connects (Lan). The phone (Session.java) must use the same wire format:
/// the laptop sends the hello of Lan; then each side sends frames: a 4-byte big-endian length, then the frame sealed
/// with Crypto.SealSession (one byte for the kind of message, then the body). Each side numbers its own frames from 0.
/// Both sides send a ping every 5 s, and 15 s without a frame ends the session.
/// </summary>
sealed class Session : IDisposable
{
    public const byte Ping = 0;
    const byte FromPhone = 0, ToPhone = 1;
    const int MaxBody = 64 * 1024;
    static readonly TimeSpan Quiet = TimeSpan.FromSeconds(15);
    readonly NetworkStream net;
    readonly string code;
    readonly byte[] id;
    readonly SemaphoreSlim writing = new(1, 1);
    readonly CancellationTokenSource end = new();
    long sent, got;

    Session(NetworkStream net, string code, byte[] id) => (this.net, this.code, this.id) = (net, code, id);

    /// <summary>Connects to a session that the phone offered. Null when the offer is not valid or the phone cannot be reached.</summary>
    public static async Task<Session?> Join(string code, JsonElement offer)
    {
        if (!offer.TryGetProperty("id", out var key) || key.GetString() is not { Length: 32 } hex || !hex.All(Uri.IsHexDigit)) return null;
        byte[] id = Convert.FromHexString(hex);
        if (await Lan.Connect(offer) is not { } socket) return null;
        socket.NoDelay = true;   // each message goes at once: a pointer move cannot wait for the next one
        var net = new NetworkStream(socket, ownsSocket: true);
        try
        {
            await Lan.Hello(net, code, id);
        }
        catch
        {
            net.Dispose();
            throw;
        }
        return new Session(net, code, id);
    }

    /// <summary>Hands each message from the phone to handle, until the phone closes, goes quiet for 15 s, or Dispose. Pings the phone meanwhile.</summary>
    public async Task Run(Action<byte, byte[]> handle)
    {
        _ = Pinging();
        try
        {
            byte[] header = new byte[4];
            while (true)
            {
                using var quiet = CancellationTokenSource.CreateLinkedTokenSource(end.Token);
                quiet.CancelAfter(Quiet);
                await net.ReadExactlyAsync(header, quiet.Token);
                int n = BinaryPrimitives.ReadInt32BigEndian(header);
                if (n is < 12 + 16 + 1 or > 12 + 16 + 1 + MaxBody) return;   // not a frame of ours
                byte[] sealedBytes = new byte[n];
                await net.ReadExactlyAsync(sealedBytes, quiet.Token);
                if (Crypto.OpenSession(code, id, FromPhone, got++, sealedBytes) is not { } plain) return;   // changed, repeated or out of order
                if (plain[0] != Ping) handle(plain[0], plain[1..]);
            }
        }
        catch (Exception e) when (e is IOException or OperationCanceledException or SocketException or ObjectDisposedException)
        {
            // The phone closed the session, the Wi-Fi dropped, or Dispose.
        }
        finally
        {
            Dispose();
        }
    }

    async Task Pinging()
    {
        try
        {
            while (true)
            {
                await Send(Ping, []);
                await Task.Delay(TimeSpan.FromSeconds(5), end.Token);
            }
        }
        catch (Exception e) when (e is IOException or OperationCanceledException or SocketException or ObjectDisposedException)
        {
            Dispose();
        }
    }

    async Task Send(byte kind, byte[] body)
    {
        await writing.WaitAsync(end.Token);
        try
        {
            byte[] sealedBytes = Crypto.SealSession(code, id, ToPhone, sent++, [kind, .. body]);
            byte[] frame = new byte[4 + sealedBytes.Length];
            BinaryPrimitives.WriteInt32BigEndian(frame, sealedBytes.Length);
            sealedBytes.CopyTo(frame, 4);
            await net.WriteAsync(frame, end.Token);
        }
        finally
        {
            writing.Release();
        }
    }

    public void Dispose()
    {
        end.Cancel();
        net.Dispose();
    }
}
