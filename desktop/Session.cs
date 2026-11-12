using System.Buffers.Binary;
using System.Collections.Concurrent;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text.Json;

namespace Ferry;

/// <summary>
/// A session: one encrypted connection that stays open while a feature is in use (Helm, Phone photos). As for files,
/// the phone listens and the laptop connects (Lan). The phone (Session.java) must use the same wire format:
/// the laptop sends the hello of Lan; then each side sends frames: a 4-byte big-endian length, then the frame sealed
/// with Crypto.SealSession (one byte for the kind of message, then the body). Each side numbers its own frames from 0.
/// Both sides send a ping every 5 s, and 15 s without a frame ends the session.
/// The phone starts a session with a "session" message through ntfy.sh (Helm). The laptop asks for one with "open" (Open);
/// the phone answers with "session", or with "session" and a "why" when it says no.
/// </summary>
sealed class Session : IDisposable
{
    public const byte Ping = 0;
    const byte FromPhone = 0, ToPhone = 1;
    public const int MaxBody = 64 * 1024;
    static readonly TimeSpan Quiet = TimeSpan.FromSeconds(15);
    static readonly ConcurrentDictionary<string, TaskCompletionSource<JsonElement>> waiting = new();
    readonly NetworkStream net;
    readonly string code;
    readonly byte[] id;
    readonly SemaphoreSlim writing = new(1, 1);
    readonly CancellationTokenSource end = new();
    long sent, got;

    Session(NetworkStream net, string code, byte[] id) => (this.net, this.code, this.id) = (net, code, id);

    /// <summary>
    /// Asks the phone for a session (what: "photos") and connects to it. Returns the session, or null and why not:
    /// "off", "permission" or "nowifi" from the phone; "offline" when the request did not go out; "noanswer" when the phone
    /// did not answer in 10 s (off, or an older Ferry); "noconnect" when it answered but could not be reached.
    /// </summary>
    public static async Task<(Session? Session, string Why)> Open(string code, string what)
    {
        string key = Convert.ToHexStringLower(RandomNumberGenerator.GetBytes(16));
        var answer = new TaskCompletionSource<JsonElement>(TaskCreationOptions.RunContinuationsAsynchronously);
        waiting[key] = answer;
        JsonElement reply;
        try
        {
            await Sync.PostLan(code, new { t = "open", id = key, @for = what });
            reply = await answer.Task.WaitAsync(TimeSpan.FromSeconds(10));
        }
        catch (TimeoutException)
        {
            return (null, "noanswer");
        }
        catch (Exception e) when (e is HttpRequestException or TaskCanceledException)
        {
            return (null, "offline");
        }
        finally
        {
            waiting.TryRemove(key, out _);
        }
        if (reply.TryGetProperty("why", out var why) && why.GetString() is { Length: > 0 } no) return (null, no);
        try
        {
            return await Join(code, reply) is { } session ? (session, "") : (null, "noconnect");
        }
        catch (Exception e) when (e is IOException or SocketException)
        {
            return (null, "noconnect");
        }
    }

    /// <summary>A "session" message from the phone. True when it answers an Open of this laptop; false when the phone starts one itself.</summary>
    public static bool Answer(JsonElement message) =>
        message.TryGetProperty("id", out var id) && id.GetString() is { } key && waiting.TryRemove(key, out var answer) && answer.TrySetResult(message);

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

    /// <summary>Sends a message and does not wait. A failed send ends the session, and Run returns.</summary>
    public void Post(byte kind, byte[] body) =>
        _ = Send(kind, body).ContinueWith(t => { _ = t.Exception; Dispose(); }, TaskContinuationOptions.NotOnRanToCompletion);

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
