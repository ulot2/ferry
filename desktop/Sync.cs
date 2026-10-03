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
    public event Action<string>? PhonePaired;
    public event Action<string>? MessageSeen;   // message id, for catching up after a restart

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
        if (tags.Contains("image"))
        {
            // An image is a sealed file. Fetch it only from ntfy.sh and only up to 16 MB.
            if (!m.TryGetProperty("attachment", out var file)) return id;
            string fileUrl = file.TryGetProperty("url", out var fu) ? fu.GetString() ?? "" : "";
            long size = file.TryGetProperty("size", out var fs) ? fs.GetInt64() : 0;
            if (!fileUrl.StartsWith(Server + "/") || size > 16L * 1024 * 1024) return id;
            try
            {
                byte[]? image = Crypto.OpenImage(code, await Http.GetByteArrayAsync(fileUrl, stop));
                if (image is not null) ImageReceived?.Invoke(image, at);
            }
            catch (HttpRequestException)
            {
                // Expired (ntfy.sh keeps files 3 hours) or a failed download: skip it, or the stream would replay it forever.
            }
            return id;
        }

        string sealedText = m.TryGetProperty("message", out var msg) ? msg.GetString() ?? "" : "";
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

        if (tags.Contains("pair")) PhonePaired?.Invoke(text);   // the phone sends its model name when it pairs
        else TextReceived?.Invoke(text, at);
        return id;
    }

    public static async Task Send(string code, string text)
    {
        byte[] body = Encoding.UTF8.GetBytes(Crypto.Seal(code, text));
        // ntfy.sh rejects messages over 4096 bytes, so long text goes as a text file the phone reads.
        bool asFile = body.Length > 4000;
        using var request = new HttpRequestMessage(asFile ? HttpMethod.Put : HttpMethod.Post, $"{Server}/{Crypto.Topic(code)}")
        {
            Content = new ByteArrayContent(body),
        };
        if (asFile) request.Headers.Add("Filename", "clipboard.txt");
        request.Headers.Add("Tags", "laptop");
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(15));
        using var response = await Http.SendAsync(request, timeout.Token);
        response.EnsureSuccessStatusCode();
    }

    /// <summary>Encrypts and sends an image (PNG or JPEG bytes) to the phone as a file.</summary>
    public static async Task SendImage(string code, byte[] image)
    {
        using var request = new HttpRequestMessage(HttpMethod.Put, $"{Server}/{Crypto.Topic(code)}")
        {
            Content = new ByteArrayContent(Crypto.SealImage(code, image)),
        };
        request.Headers.Add("Filename", "image.ferry");
        request.Headers.Add("Tags", "laptop,image");
        using var timeout = new CancellationTokenSource(TimeSpan.FromMinutes(2));   // a few MB on a slow connection
        using var response = await Http.SendAsync(request, timeout.Token);
        response.EnsureSuccessStatusCode();
    }
}