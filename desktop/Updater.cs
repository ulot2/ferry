using System.Diagnostics;
using System.Text.Json;

namespace Ferry;

/// <summary>Finds newer Ferry releases on GitHub and swaps in the new Ferry.exe.</summary>
static class Updater
{
    const string Latest = "https://api.github.com/repos/ulot2/ferry/releases/latest";
    static readonly HttpClient Http = CreateClient();

    static HttpClient CreateClient()
    {
        var http = new HttpClient { Timeout = TimeSpan.FromMinutes(5) };
        http.DefaultRequestHeaders.UserAgent.ParseAdd("Ferry");   // GitHub refuses requests without one
        return http;
    }

    /// <summary>This build's version without the "+commit" suffix, for example "1.1.0" or "1.1.0-dev.14".</summary>
    public static string Current => Application.ProductVersion.Split('+')[0];

    /// <summary>Returns the newer version and its Ferry.exe link, or null when this build is current or GitHub is unreachable.</summary>
    public static async Task<(string Version, string Url)?> Check()
    {
        try
        {
            using var doc = JsonDocument.Parse(await Http.GetStringAsync(Latest));
            var release = doc.RootElement;
            string version = (release.GetProperty("tag_name").GetString() ?? "").TrimStart('v');
            string? url = release.GetProperty("assets").EnumerateArray()
                .Where(a => a.GetProperty("name").GetString() == "Ferry.exe")
                .Select(a => a.GetProperty("browser_download_url").GetString())
                .FirstOrDefault();
            return url is not null && Newer(version, Current) ? (version, url) : null;
        }
        catch (Exception e) when (e is HttpRequestException or TaskCanceledException or JsonException or KeyNotFoundException)
        {
            return null;
        }
    }

    /// <summary>Compares the numbers before any "-" suffix: "1.2.0" is newer than "1.1.0-dev.14".</summary>
    public static bool Newer(string latest, string current)
    {
        int[] a = Parts(latest), b = Parts(current);
        for (int i = 0; i < 3; i++) if (a[i] != b[i]) return a[i] > b[i];
        return false;
    }

    static int[] Parts(string v)
    {
        var p = v.Split('-')[0].Split('.');
        return [.. Enumerable.Range(0, 3).Select(i => i < p.Length && int.TryParse(p[i], out int n) ? n : 0)];
    }

    /// <summary>
    /// Downloads the new Ferry.exe and swaps it in. Windows lets a running exe be renamed but not overwritten,
    /// so the old one becomes Ferry.exe.old (deleted on the next start). Then starts the new one.
    /// </summary>
    public static async Task Install(string url, Action releaseSingleInstance)
    {
        string exe = Environment.ProcessPath!, fresh = exe + ".new", old = exe + ".old";
        await using (var download = await Http.GetStreamAsync(url))
        await using (var file = File.Create(fresh))
            await download.CopyToAsync(file);
        File.Delete(old);
        File.Move(exe, old);
        try
        {
            File.Move(fresh, exe);
        }
        catch
        {
            File.Move(old, exe);   // put the working version back
            throw;
        }
        releaseSingleInstance();
        Process.Start(exe);
    }

    public static void CleanUp()
    {
        try { File.Delete(Environment.ProcessPath + ".old"); } catch (IOException) { } catch (UnauthorizedAccessException) { }
    }
}
