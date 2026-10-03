using System.Text.Json;

namespace Ferry;

record Crossing(string Direction, string Text, DateTime Time);

/// <summary>Saved in %APPDATA%\Ferry\settings.json (only this Windows user can read it).</summary>
sealed class Settings
{
    public string Code { get; set; } = "";   // pairing secret; versions before encryption saved "Topic" instead
    public string Peer { get; set; } = "";   // model of the paired phone; empty = not paired yet
    public bool Paused { get; set; }
    public bool Popups { get; set; } = true;
    public string LastId { get; set; } = "";   // last ntfy.sh message seen, so a restart catches up on what it missed
    public List<Crossing> History { get; set; } = [];   // newest first

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
}
