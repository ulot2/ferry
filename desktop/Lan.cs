using System.Buffers.Binary;
using System.Collections.Concurrent;
using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text.Json;

namespace Ferry;

/// <summary>
/// Direct transfer over Wi-Fi, for files of any size. The phone always listens and the laptop always connects,
/// so Windows Firewall never asks. ntfy.sh carries only the short sealed messages that set it up.
/// The phone (Lan.java) must use the same wire format:
/// the laptop sends a hello (4-byte length, then the sealed transfer id); then the sender sends frames
/// (4-byte header: top bit set on the last frame, the rest the sealed length; then the frame, at most 1 MB sealed);
/// then the receiver answers one byte, 1, when it has all of it. Every number is big-endian.
/// </summary>
static class Lan
{
    const int Frame = 1024 * 1024;
    /// <summary>Big socket buffers keep the Wi-Fi busy while the next frame is read and sealed.</summary>
    const int Buffer = 4 * 1024 * 1024;
    /// <summary>A link with no progress for this long is dead, for example after the laptop left the Wi-Fi.</summary>
    static readonly TimeSpan Stall = TimeSpan.FromSeconds(30);
    static readonly ConcurrentDictionary<string, TaskCompletionSource<JsonElement>> waiting = new();

    /// <summary>The phone answered a request to send: where it listens, or no addresses when it is not on Wi-Fi.</summary>
    public static void Answer(JsonElement accept)
    {
        if (accept.TryGetProperty("id", out var id) && id.GetString() is { } key && waiting.TryRemove(key, out var answer)) answer.TrySetResult(accept);
    }

    /// <summary>
    /// Sends a file straight to the phone. Returns false when the phone cannot be reached (another network,
    /// or an older Ferry); then nothing was sent. Throws when the transfer broke off.
    /// </summary>
    public static async Task<bool> Send(string code, string path, IProgress<double> progress)
    {
        using var file = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read, Frame, FileOptions.Asynchronous | FileOptions.SequentialScan);
        byte[] id = RandomNumberGenerator.GetBytes(16);
        string key = Convert.ToHexStringLower(id);
        var answer = new TaskCompletionSource<JsonElement>(TaskCreationOptions.RunContinuationsAsynchronously);
        waiting[key] = answer;
        JsonElement accept;
        try
        {
            await Sync.PostLan(code, new { t = "request", id = key, name = Path.GetFileName(path), size = file.Length });
            accept = await answer.Task.WaitAsync(TimeSpan.FromSeconds(8));   // the phone answers in about a second
        }
        catch (TimeoutException)
        {
            return false;   // an older Ferry on the phone, or the phone is off
        }
        finally
        {
            waiting.TryRemove(key, out _);
        }
        using var socket = await Connect(accept);
        if (socket is null) return false;
        using var net = new NetworkStream(socket);
        await Hello(net, code, id);
        await SendFrames(net, code, id, file, file.Length, progress);
        return true;
    }

    /// <summary>
    /// Takes a file that the phone offered and writes it to dest. Returns false when the phone cannot be reached;
    /// then dest is not made. Throws when the transfer broke off, and dest can then hold a part of the file.
    /// </summary>
    public static async Task<bool> Receive(string code, JsonElement offer, string dest, IProgress<double> progress)
    {
        string key = offer.GetProperty("id").GetString() ?? "";
        if (key.Length != 32) return false;
        byte[] id = Convert.FromHexString(key);
        long size = offer.TryGetProperty("size", out var z) ? z.GetInt64() : -1;
        using var socket = await Connect(offer);
        if (socket is null) return false;
        using var net = new NetworkStream(socket);
        await Hello(net, code, id);
        using (var output = new FileStream(dest, FileMode.CreateNew, FileAccess.Write, FileShare.None, Frame, FileOptions.Asynchronous))
        {
            using var stall = new CancellationTokenSource(Stall);
            byte[] header = new byte[4];
            long got = 0;
            for (long i = 0; ; i++)
            {
                await net.ReadExactlyAsync(header, stall.Token);
                int h = BinaryPrimitives.ReadInt32BigEndian(header);
                bool last = h < 0;
                int n = h & int.MaxValue;
                if (n > Frame + 28) throw new IOException("A frame from the phone is too large.");
                byte[] sealedBytes = new byte[n];
                await net.ReadExactlyAsync(sealedBytes, stall.Token);
                byte[] plain = Crypto.OpenFrame(code, id, i, last, sealedBytes) ?? throw new IOException("A frame from the phone did not open.");
                await output.WriteAsync(plain, stall.Token);
                got += plain.Length;
                progress.Report(size > 0 ? Math.Min(1, (double)got / size) : 0);
                stall.CancelAfter(Stall);   // it moved: start the stall time again
                if (last) break;
            }
        }
        await net.WriteAsync(new byte[] { 1 });
        return true;
    }

    /// <summary>
    /// Frames out of the file. Reads one frame ahead, because the last frame must say that it is the last.
    /// A write ends when the frame is in the 4 MB socket buffer, so the Wi-Fi sends it while the next frame is read and sealed.
    /// </summary>
    static async Task SendFrames(NetworkStream net, string code, byte[] id, Stream file, long size, IProgress<double> progress)
    {
        using var stall = new CancellationTokenSource(Stall);
        byte[] buf = new byte[Frame], next = new byte[Frame], header = new byte[4];
        int n = await file.ReadAtLeastAsync(buf, Frame, throwOnEndOfStream: false);
        long sent = 0;
        for (long i = 0; ; i++)
        {
            int m = n == Frame ? await file.ReadAtLeastAsync(next, Frame, throwOnEndOfStream: false) : 0;
            bool last = m == 0;
            byte[] sealedBytes = Crypto.SealFrame(code, id, i, last, buf.AsSpan(0, n));
            BinaryPrimitives.WriteInt32BigEndian(header, sealedBytes.Length | (last ? int.MinValue : 0));
            await net.WriteAsync(header, stall.Token);
            await net.WriteAsync(sealedBytes, stall.Token);
            sent += n;
            progress.Report(size > 0 ? Math.Min(1, (double)sent / size) : 0);
            stall.CancelAfter(Stall);
            if (last) break;
            (buf, next, n) = (next, buf, m);
        }
        byte[] ok = new byte[1];
        if (await net.ReadAsync(ok, stall.Token) != 1 || ok[0] != 1) throw new IOException("The phone did not confirm the file.");
    }

    static async Task Hello(NetworkStream net, string code, byte[] id)
    {
        byte[] hello = Crypto.SealHello(code, id);
        byte[] message = new byte[4 + hello.Length];
        BinaryPrimitives.WriteInt32BigEndian(message, hello.Length);
        hello.CopyTo(message, 4);
        await net.WriteAsync(message);
    }

    /// <summary>Tries every address of the phone at once and keeps the first that answers. Null when none does in 3 s.</summary>
    static async Task<Socket?> Connect(JsonElement where)
    {
        int port = where.TryGetProperty("port", out var p) ? p.GetInt32() : 0;
        var ips = where.TryGetProperty("ips", out var list)
            ? list.EnumerateArray().Select(x => IPAddress.TryParse(x.GetString(), out var a) ? a : null).OfType<IPAddress>().Where(a => a.AddressFamily == AddressFamily.InterNetwork).ToList()
            : new List<IPAddress>();
        if (ips.Count == 0 || port is <= 0 or > 65535) return null;
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(3));
        var tries = ips.Select(async ip =>
        {
            var s = new Socket(AddressFamily.InterNetwork, SocketType.Stream, ProtocolType.Tcp) { SendBufferSize = Buffer, ReceiveBufferSize = Buffer };
            try
            {
                await s.ConnectAsync(ip, port, timeout.Token);
                return s;
            }
            catch (Exception e) when (e is SocketException or OperationCanceledException)
            {
                s.Dispose();
                return null;
            }
        }).ToList();
        Socket? won = null;
        while (tries.Count > 0)
        {
            var done = await Task.WhenAny(tries);
            tries.Remove(done);
            if (await done is not { } s) continue;
            if (won is null)
            {
                won = s;
                timeout.Cancel();   // the others stop trying
            }
            else s.Dispose();
        }
        return won;
    }
}
