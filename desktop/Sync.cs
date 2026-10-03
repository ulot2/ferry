using System.Text;
using System.Text.Json;

namespace Ferry;

enum LinkState { Connecting, Connected, Offline }

/// <summary>Talks to ntfy.sh: keeps a JSON stream open for phone messages and publishes laptop copies.</summary>
sealed class Sync
{
    const string Server = "https://ntfy.sh";
    static readonly HttpClient Http = new() { Timeout = Timeout.InfiniteTimeSpan };
    CancellationTokenSource? cts;

    // Raised on a thread-pool thread.
    public event Action<LinkState>? StateChanged;
    public event Action<string>? TextReceived;
    public event Action<string>? PhonePaired;

    public void Start(string topic)
    {
        Stop();
        cts = new CancellationTokenSource();
        _ = Run(topic, cts.Token);
    }

    public void Stop()
    {
        cts?.Cancel();
        cts = null;
    }

    async Task Run(string topic, CancellationToken stop)
    {
        string? since = null;   // id of the last message seen, so a reconnect catches up on what it missed
        StateChanged?.Invoke(LinkState.Connecting);
        while (!stop.IsCancellationRequested)
        {
            try
            {
                string url = $"{Server}/{topic}/json" + (since is null ? "" : $"?since={since}");
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
                    since = await Handle(line, stop) ?? since;
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
    async Task<string?> Handle(string line, CancellationToken stop)
    {
        using var doc = JsonDocument.Parse(line);
        var m = doc.RootElement;
        if (m.GetProperty("event").GetString() != "message") return null;
        string? id = m.GetProperty("id").GetString();
        var tags = m.TryGetProperty("tags", out var t) ? t.EnumerateArray().Select(x => x.GetString()).ToArray() : Array.Empty<string?>();
        if (tags.Contains("laptop")) return id;   // our own message coming back

        string text = m.TryGetProperty("message", out var msg) ? msg.GetString() ?? "" : "";
        if (tags.Contains("pair"))
        {
            PhonePaired?.Invoke(text);   // the phone sends its model name when it pairs
            return id;
        }
        if (m.TryGetProperty("attachment", out var att))
        {
            // ntfy.sh turns long text into a file. Fetch it only if it is text from ntfy.sh; skip photos and anything else.
            string url = att.TryGetProperty("url", out var u) ? u.GetString() ?? "" : "";
            string type = att.TryGetProperty("type", out var ty) ? ty.GetString() ?? "" : "";
            if (!type.StartsWith("text/") || !url.StartsWith(Server + "/")) return id;
            text = await Http.GetStringAsync(url, stop);
        }
        if (text.Length > 0) TextReceived?.Invoke(text);
        return id;
    }

    public static async Task Send(string topic, string text)
    {
        byte[] bytes = Encoding.UTF8.GetBytes(text);
        // ntfy.sh rejects messages over 4096 bytes, so long text goes as a text file the phone app reads.
        bool asFile = bytes.Length > 4000;
        using var request = new HttpRequestMessage(asFile ? HttpMethod.Put : HttpMethod.Post, $"{Server}/{topic}")
        {
            Content = new ByteArrayContent(bytes),
        };
        if (asFile) request.Headers.Add("Filename", "clipboard.txt");
        request.Headers.Add("Tags", "laptop");
        request.Headers.Add("Title", "From laptop");
        request.Headers.Add("Priority", "2");
        request.Headers.Add("Cache", "no");   // ntfy.sh passes it on and does not store it
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(15));
        using var response = await Http.SendAsync(request, timeout.Token);
        response.EnsureSuccessStatusCode();
    }
}
