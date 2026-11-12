using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace Ferry;

enum LinkState { Connecting, Connected, Offline }

/// <summary>Talks to ntfy.sh: keeps a JSON stream open for phone messages and publishes laptop copies, all encrypted.</summary>
sealed class Sync
{
    const string Server = "https://ntfy.sh";
    static readonly HttpClient Http = new() { Timeout = Timeout.InfiniteTimeSpan };
    CancellationTokenSource? cts;

    // Raised on a thread-pool thread.
    public event Action<LinkState>? StateChanged;
    public event Action<string, DateTime>? TextReceived;
    public event Action<byte[], DateTime>? ImageReceived;
    public event Action<string, string, DateTime>? FileReceived;   // name, and a temporary file that the handler moves
    public event Action<string, long, DateTime>? FileComing;   // name and size, before the file itself
    public event Action<JsonElement, DateTime>? FileOffered;   // the phone offers a file straight over Wi-Fi
    public event Action<JsonElement>? SessionOffered;   // the phone listens for a session (Helm)
    public event Action<string, string, string>? NotificationReceived;   // app, title, text: a phone notification from Roost
    public event Action<string>? PhonePaired;
    public event Action? PhoneUnpaired;   // the phone tapped Unpair
    public event Action<string>? MessageSeen;   // message id, for catching up after a restart

    /// <summary>ntfy.sh takes attachments up to 15 MB. The name and the encryption add a few hundred bytes.</summary>
    public const int PartBytes = 14 * 1024 * 1024;
    /// <summary>Larger files go in parts. ntfy.sh keeps at most 100 MB of one sender's files at a time, so 90 MB leaves room.</summary>
    public const long MaxFileBytes = 90L * 1024 * 1024;

    // The file whose parts are coming in, the part expected next, and where they are put together.
    // ponytail: one file at a time and kept in memory only; a restart of Ferry in the middle drops that file.
    string? partId;
    int partNext;
    string partPath = "";

    public void Start(string code, string since)
    {
        Stop();
        cts = new CancellationTokenSource();
        _ = Run(code, since, cts.Token);
    }

    public void Stop()
    {
        cts?.Cancel();
        cts = null;
    }

    async Task Run(string code, string since, CancellationToken stop)
    {
        string topic = Crypto.Topic(code);
        StateChanged?.Invoke(LinkState.Connecting);
        while (!stop.IsCancellationRequested)
        {
            try
            {
                // "since" replays what arrived while we were away, so a disconnection or restart loses nothing.
                string url = $"{Server}/{topic}/json" + (since == "" ? "" : $"?since={since}");
                using var response = await Http.GetAsync(url, HttpCompletionOption.ResponseHeadersRead, stop);
                response.EnsureSuccessStatusCode();
                using var reader = new StreamReader(await response.Content.ReadAsStreamAsync(stop));
                StateChanged?.Invoke(LinkState.Connected);
                while (true)
                {
                    // ntfy.sh sends a keepalive every 45 s. 90 s of silence means the link is dead, for example after sleep.
                    using var silence = CancellationTokenSource.CreateLinkedTokenSource(stop);
                    silence.CancelAfter(TimeSpan.FromSeconds(90));
                    string? line = await reader.ReadLineAsync(silence.Token);
                    if (line is null) break;
                    since = await Handle(code, line, stop) ?? since;
                }
            }
            catch (Exception) when (!stop.IsCancellationRequested)
            {
                // fall through to the retry below
            }
            catch (OperationCanceledException)
            {
                return;
            }

            if (stop.IsCancellationRequested) return;
            StateChanged?.Invoke(LinkState.Offline);
            // ponytail: fixed 5 s retry; add backoff if it matters on battery
            try { await Task.Delay(5000, stop); } catch (OperationCanceledException) { return; }
        }
    }

    /// <summary>Returns the message id, or null for events that are not messages.</summary>
    async Task<string?> Handle(string code, string line, CancellationToken stop)
    {
        using var doc = JsonDocument.Parse(line);
        var m = doc.RootElement;
        if (m.GetProperty("event").GetString() != "message") return null;
        string id = m.GetProperty("id").GetString() ?? "";
        MessageSeen?.Invoke(id);
        var tags = m.TryGetProperty("tags", out var t) ? t.EnumerateArray().Select(x => x.GetString()).ToArray() : Array.Empty<string?>();
        if (tags.Contains("laptop")) return id;   // our own message coming back

        var at = m.TryGetProperty("time", out var tm) ? DateTimeOffset.FromUnixTimeSeconds(tm.GetInt64()).LocalDateTime : DateTime.Now;
        if (tags.Contains("image") || tags.Contains("file") || tags.Contains("part"))
        {
            // An image, a file or a part of a file is a sealed attachment. Fetch it only from ntfy.sh and only up to 16 MB.
            if (!m.TryGetProperty("attachment", out var file)) return id;
            string fileUrl = file.TryGetProperty("url", out var fu) ? fu.GetString() ?? "" : "";
            long size = file.TryGetProperty("size", out var fs) ? fs.GetInt64() : 0;
            if (!fileUrl.StartsWith(Server + "/") || size > 16L * 1024 * 1024) return id;
            try
            {
                byte[] sealedBytes = await Http.GetByteArrayAsync(fileUrl, stop);
                if (tags.Contains("image"))
                {
                    if (Crypto.OpenImage(code, sealedBytes) is { } image) ImageReceived?.Invoke(image, at);
                }
                else if (tags.Contains("part")) await AddPart(code, sealedBytes, at);
                else if (Crypto.OpenFile(code, sealedBytes) is { } opened)
                {
                    string path = Path.GetTempFileName();
                    await File.WriteAllBytesAsync(path, opened.Contents, stop);
                    FileReceived?.Invoke(opened.Name, path, at);
                }
            }
            catch (HttpRequestException)
            {
                // Expired (ntfy.sh keeps files 3 hours) or a failed download: skip it, or the stream would replay it forever.
            }
            catch (IOException)
            {
                // The temporary file could not be written (disk full): skip it, as above.
            }
            return id;
        }

        string sealedText = m.TryGetProperty("message", out var msg) ? msg.GetString() ?? "" : "";
        if (tags.Contains("lan"))
        {
            // Sets up a direct Wi-Fi transfer. Older messages are replays after a reconnect: the phone stopped waiting long ago.
            if (DateTime.Now - at > TimeSpan.FromMinutes(2) || Crypto.OpenLan(code, sealedText) is not { } json) return id;
            try
            {
                using var lan = JsonDocument.Parse(json);
                var message = lan.RootElement.Clone();
                switch (message.GetProperty("t").GetString())
                {
                    case "accept": Lan.Answer(message); break;
                    case "offer": FileOffered?.Invoke(message, at); break;
                    case "session": SessionOffered?.Invoke(message); break;
                }
            }
            catch (Exception e) when (e is JsonException or KeyNotFoundException or InvalidOperationException)
            {
                // Not a message this Ferry knows. Skip it, or the stream would replay it forever.
            }
            return id;
        }

        if (tags.Contains("notification"))
        {
            // A phone notification is only worth a pop-up when it is new, not when a reconnect replays it.
            if (DateTime.Now - at > TimeSpan.FromMinutes(2) || Crypto.OpenNotification(code, sealedText) is not { } json) return id;
            try
            {
                using var n = JsonDocument.Parse(json);
                var r = n.RootElement;
                NotificationReceived?.Invoke(r.GetProperty("app").GetString() ?? "", r.GetProperty("title").GetString() ?? "", r.GetProperty("text").GetString() ?? "");
            }
            catch (Exception e) when (e is JsonException or KeyNotFoundException or InvalidOperationException)
            {
                // Not a message this Ferry knows. Skip it, or the stream would replay it forever.
            }
            return id;
        }

        if (tags.Contains("incoming"))
        {
            // "size\nname", sent just before a file
            if (Crypto.OpenIncoming(code, sealedText) is { } notice && notice.Split('\n', 2) is [var bytes, var name] && long.TryParse(bytes, out long n))
                FileComing?.Invoke(name, n, at);
            return id;
        }
        if (m.TryGetProperty("attachment", out var att))
        {
            // ntfy.sh turns long bodies into a file. Fetch it only if it is text from ntfy.sh; skip anything else.
            string url = att.TryGetProperty("url", out var u) ? u.GetString() ?? "" : "";
            string type = att.TryGetProperty("type", out var ty) ? ty.GetString() ?? "" : "";
            if (!type.StartsWith("text/") || !url.StartsWith(Server + "/")) return id;
            sealedText = await Http.GetStringAsync(url, stop);
        }
        string? text = Crypto.Open(code, sealedText);
        if (string.IsNullOrEmpty(text)) return id;   // not from our phone: ignore it

        if (tags.Contains("unpair")) PhoneUnpaired?.Invoke();
        else if (tags.Contains("pair")) PhonePaired?.Invoke(text);   // the phone sends its model name when it pairs
        else TextReceived?.Invoke(text, at);
        return id;
    }

    /// <summary>Parts come in order. Each one is added to a temporary file, and the last one hands it on. A missing part drops the whole file.</summary>
    async Task AddPart(string code, byte[] sealedBytes, DateTime at)
    {
        if (Crypto.OpenPart(code, sealedBytes) is not { } part) return;   // not from our phone
        if (part.Index == 0)
        {
            if (partId is not null) File.Delete(partPath);   // an earlier file that never finished
            (partId, partNext, partPath) = (part.Id, 0, Path.GetTempFileName());
        }
        if (part.Id != partId || part.Index != partNext)
        {
            if (partId is not null) File.Delete(partPath);
            partId = null;
            return;
        }
        using (var output = new FileStream(partPath, FileMode.Append))
            await output.WriteAsync(part.Contents);
        if (++partNext < part.Count) return;
        partId = null;
        FileReceived?.Invoke(part.Name, partPath, at);
    }

    public static Task Send(string code, string text, string tags = "laptop") => Post(code, Crypto.Seal(code, text), tags);

    /// <summary>A message that sets up a direct Wi-Fi transfer: "request" (the laptop sends) or "nolan" (the laptop cannot connect).</summary>
    public static Task PostLan(string code, object message) => Post(code, Crypto.SealLan(code, JsonSerializer.Serialize(message)), "laptop,lan");

    static async Task Post(string code, string sealedText, string tags)
    {
        byte[] body = Encoding.UTF8.GetBytes(sealedText);
        // ntfy.sh rejects messages over 4096 bytes, so long text goes as a text file the phone reads.
        bool asFile = body.Length > 4000;
        using var request = new HttpRequestMessage(asFile ? HttpMethod.Put : HttpMethod.Post, $"{Server}/{Crypto.Topic(code)}")
        {
            Content = new ByteArrayContent(body),
        };
        if (asFile) request.Headers.Add("Filename", "clipboard.txt");
        request.Headers.Add("Tags", tags);
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(15));
        using var response = await Http.SendAsync(request, timeout.Token);
        response.EnsureSuccessStatusCode();
    }

    /// <summary>Encrypts and sends an image (PNG or JPEG bytes) to the phone as a file.</summary>
    public static Task SendImage(string code, byte[] image) => Put(code, Crypto.SealImage(code, image), "image");

    /// <summary>
    /// Encrypts and sends a file with its name. ntfy.sh only sees "file.ferry" or "part.ferry" and the size.
    /// First a short notice tells the phone what is coming. A file over 14 MB goes in 14 MB parts. Reports the part sent, 0 to 1.
    /// </summary>
    public static async Task SendFile(string code, string path, IProgress<double> progress)
    {
        string name = Path.GetFileName(path);
        using var file = File.OpenRead(path);
        long size = file.Length;
        int count = (int)Math.Max(1, (size + PartBytes - 1) / PartBytes);
        await Post(code, Crypto.SealIncoming(code, size, name), "laptop,incoming");
        byte[] id = RandomNumberGenerator.GetBytes(8);
        for (int i = 0; i < count; i++)
        {
            byte[] part = new byte[Math.Min(PartBytes, size - (long)i * PartBytes)];
            await file.ReadExactlyAsync(part);
            int done = i;
            var partProgress = new Inline(p => progress.Report((done + p) / count));
            // One part goes the old way, so a phone with an older Ferry still gets files up to 14 MB.
            if (count == 1) await Put(code, Crypto.SealFile(code, name, part), "file", partProgress);
            else await Put(code, Crypto.SealPart(code, id, i, count, name, part), "part", partProgress);
        }
    }

    static async Task Put(string code, byte[] sealedBytes, string kind, IProgress<double>? progress = null)
    {
        using var request = new HttpRequestMessage(HttpMethod.Put, $"{Server}/{Crypto.Topic(code)}")
        {
            Content = progress is null ? new ByteArrayContent(sealedBytes) : new ProgressContent(sealedBytes, progress),
        };
        request.Headers.Add("Filename", kind + ".ferry");
        request.Headers.Add("Tags", "laptop," + kind);
        using var timeout = new CancellationTokenSource(TimeSpan.FromMinutes(5));   // 14 MB on a slow connection
        using var response = await Http.SendAsync(request, timeout.Token);
        response.EnsureSuccessStatusCode();
    }

    /// <summary>Passes a report on at once. Progress&lt;T&gt; would post it, and a late report from one part could move the bar back.</summary>
    sealed class Inline(Action<double> report) : IProgress<double>
    {
        public void Report(double value) => report(value);
    }

    /// <summary>A request body written in 64 KB parts, so the window can show how much is sent.</summary>
    sealed class ProgressContent(byte[] bytes, IProgress<double> progress) : HttpContent
    {
        protected override Task SerializeToStreamAsync(Stream stream, System.Net.TransportContext? context) =>
            SerializeToStreamAsync(stream, context, CancellationToken.None);

        protected override async Task SerializeToStreamAsync(Stream stream, System.Net.TransportContext? context, CancellationToken stop)
        {
            const int Part = 64 * 1024;
            for (int at = 0; at < bytes.Length; at += Part)
            {
                int n = Math.Min(Part, bytes.Length - at);
                await stream.WriteAsync(bytes.AsMemory(at, n), stop);
                progress.Report((double)(at + n) / bytes.Length);
            }
        }

        protected override bool TryComputeLength(out long length)
        {
            length = bytes.Length;
            return true;
        }
    }
}