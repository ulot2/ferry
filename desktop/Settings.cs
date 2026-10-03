using System.Security.Cryptography;
using System.Text.Json;

namespace Ferry;

/// <summary>Saved in %APPDATA%\Ferry\settings.json.</summary>
sealed class Settings
{
    public string Topic { get; set; } = "";
    public string Peer { get; set; } = "";   // model of the paired phone; empty = not paired yet
    public bool Paused { get; set; }

    static readonly string Dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "Ferry");
    static readonly string FilePath = Path.Combine(Dir, "settings.json");

    public static bool Exists => File.Exists(FilePath);

    public static Settings Load()
    {
        try
        {
            return JsonSerializer.Deserialize<Settings>(File.ReadAllText(FilePath)) ?? new();
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException or JsonException)
        {
            return new();   // first run, or a damaged file: start fresh and pair again
        }
    }

    public void Save()
    {
        Directory.CreateDirectory(Dir);
        File.WriteAllText(FilePath, JsonSerializer.Serialize(this));
    }

    /// <summary>A secret ntfy.sh topic. Anyone who knows it can read the clipboard, so it is long and random.</summary>
    public static string NewTopic() => "ferry-" + RandomNumberGenerator.GetString("abcdefghijkmnpqrstuvwxyz23456789", 24);
}
