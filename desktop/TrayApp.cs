using System.Runtime.InteropServices;
using System.Text.RegularExpressions;
using Microsoft.Win32;

namespace Ferry;

/// <summary>Lives in the tray. Owns the settings, the ntfy.sh link, the clipboard watcher, updates and the window.</summary>
sealed class TrayApp : ApplicationContext
{
    const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
    const int HistorySize = 10, HistoryMaxChars = 100_000;
    readonly WindowsFormsSynchronizationContext ui = new();
    readonly Sync sync = new();
    readonly NotifyIcon tray = new();
    readonly ClipboardWatcher watcher = new();
    readonly EventWaitHandle show;
    readonly RegisteredWaitHandle showWait;
    readonly ToolStripMenuItem statusItem = new() { Enabled = false };
    readonly ToolStripMenuItem updateItem = new() { Visible = false };
    readonly ToolStripMenuItem pauseItem = new("Pause syncing") { CheckOnClick = true };
    readonly ToolStripMenuItem popupsItem = new("Show pop-ups") { CheckOnClick = true };
    readonly ToolStripMenuItem imagesItem = new("Send and receive images") { CheckOnClick = true };
    readonly ToolStripMenuItem autostartItem = new("Start with Windows") { CheckOnClick = true };
    readonly System.Windows.Forms.Timer updateTimer = new() { Interval = 12 * 60 * 60 * 1000 };
    MainWindow? window;
    string? last;   // text we last sent or applied, so our own clipboard changes do not echo back
    string? lastImage;   // hash of the image we last sent, so one copy is not sent twice
    (string Version, string Url)? update;
    Action balloonClick = () => { };

    public Settings Settings { get; }
    public LinkState State { get; private set; } = LinkState.Connecting;
    public Crossing? LastCrossing => Settings.History.FirstOrDefault();

    public TrayApp(EventWaitHandle show)
    {
        this.show = show;
        SynchronizationContext.SetSynchronizationContext(ui);
        Updater.CleanUp();
        bool firstRun = !Settings.Exists;
        Settings = Settings.Load();
        if (Settings.Code == "")
        {
            // First run, or an update from before encryption: make a new pairing code. The phone pairs again.
            Settings.Code = Crypto.NewCode();
            Settings.Peer = "";
            Settings.LastId = "";
            Settings.Save();
        }
        if (firstRun) StartWithWindows = true;
        StartMenuShortcut();

        pauseItem.Checked = Settings.Paused;
        pauseItem.CheckedChanged += (_, _) => Paused = pauseItem.Checked;
        popupsItem.Checked = Settings.Popups;
        popupsItem.CheckedChanged += (_, _) => Popups = popupsItem.Checked;
        imagesItem.Checked = Settings.Images;
        imagesItem.CheckedChanged += (_, _) => Images = imagesItem.Checked;
        autostartItem.Checked = StartWithWindows;
        autostartItem.CheckedChanged += (_, _) => StartWithWindows = autostartItem.Checked;
        updateItem.Click += (_, _) => InstallUpdate();

        var menu = new ContextMenuStrip();
        menu.Items.Add(statusItem);
        menu.Items.Add(new ToolStripMenuItem("Open Ferry", null, (_, _) => ShowWindow()) { Font = new Font(menu.Font, FontStyle.Bold) });
        menu.Items.Add(updateItem);
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add(pauseItem);
        menu.Items.Add(popupsItem);
        menu.Items.Add(imagesItem);
        menu.Items.Add(autostartItem);
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add("Quit Ferry", null, (_, _) => Quit());
        tray.ContextMenuStrip = menu;
        tray.MouseClick += (_, e) => { if (e.Button == MouseButtons.Left) ShowWindow(); };
        tray.BalloonTipClicked += (_, _) => balloonClick();

        sync.StateChanged += s => ui.Post(_ => { State = s; Changed(); }, null);
        sync.TextReceived += (t, at) => ui.Post(_ => Apply(t, at), null);
        sync.ImageReceived += (image, at) => ui.Post(_ => ApplyImage(image, at), null);
        sync.PhonePaired += name => ui.Post(_ => Paired(name), null);
        sync.MessageSeen += id => ui.Post(_ => { Settings.LastId = id; Settings.Save(); }, null);
        watcher.ClipboardChanged += OnClipboardChanged;
        showWait = ThreadPool.RegisterWaitForSingleObject(show, (_, _) => ui.Post(_ => ShowWindow(), null), null, -1, false);
        updateTimer.Tick += async (_, _) => await CheckForUpdate();
        updateTimer.Start();

        Changed();
        tray.Visible = true;
        sync.Start(Settings.Code, Settings.LastId);
        if (Settings.Peer == "") ShowWindow();   // not paired yet: show the QR code
        _ = CheckForUpdate();
    }

    public string StatusLabel => Settings.Paused ? "Paused" : State switch
    {
        LinkState.Connected => "Connected",
        LinkState.Offline => "Offline",
        _ => "Connecting",
    };

    public Color LampColor => Settings.Paused ? Chart.InkMuted : State switch
    {
        LinkState.Connected => Chart.Steady,
        LinkState.Offline => Chart.Adrift,
        _ => Chart.InkMuted,
    };

    public bool Paused
    {
        get => Settings.Paused;
        set
        {
            if (Settings.Paused == value) return;
            Settings.Paused = value;
            Settings.Save();
            pauseItem.Checked = value;
            Changed();
        }
    }

    public bool Popups
    {
        get => Settings.Popups;
        set
        {
            if (Settings.Popups == value) return;
            Settings.Popups = value;
            Settings.Save();
            popupsItem.Checked = value;
            Changed();
        }
    }

    public bool Images
    {
        get => Settings.Images;
        set
        {
            if (Settings.Images == value) return;
            Settings.Images = value;
            Settings.Save();
            imagesItem.Checked = value;
            Changed();
        }
    }

    public bool StartWithWindows    {
        get => Registry.CurrentUser.OpenSubKey(RunKey)?.GetValue("Ferry") is string;
        set
        {
            using var key = Registry.CurrentUser.CreateSubKey(RunKey);
            if (value) key.SetValue("Ferry", $"\"{Environment.ProcessPath}\"");
            else key.DeleteValue("Ferry", false);
            autostartItem.Checked = value;
        }
    }

    /// <summary>
    /// Keeps a "Ferry" entry in the Start menu, so Ferry can be opened again after Quit.
    /// Written on every start, so it follows Ferry.exe if the file moves.
    /// </summary>
    static void StartMenuShortcut()
    {
        string link = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.Programs), "Ferry.lnk");
        try
        {
            dynamic shell = Activator.CreateInstance(Type.GetTypeFromProgID("WScript.Shell")!)!;
            dynamic shortcut = shell.CreateShortcut(link);
            shortcut.TargetPath = Environment.ProcessPath;
            shortcut.Description = "Ferry: clipboard between this laptop and your phone";
            shortcut.Save();
        }
        catch (Exception)
        {
            // Late-bound COM can fail in several ways (COM, binder, IO). A missing shortcut must never stop Ferry from starting.
        }
    }

    public string PairingLink => $"ferry://pair?code={Settings.Code}&name={Uri.EscapeDataString(Environment.MachineName)}";

    public static string Preview(string text)
    {
        string p = Regex.Replace(text.Trim(), @"\s+", " ");
        return p.Length > 120 ? p[..120] + "…" : p;
    }

    void Changed()
    {
        statusItem.Text = "Ferry · " + StatusLabel;
        string tip = "Ferry · " + StatusLabel + (LastCrossing is { } c ? $"\nLast: {c.Direction}, {c.Time:t}" : "");
        tray.Text = tip.Length > 127 ? tip[..127] : tip;
        var old = tray.Icon;
        tray.Icon = Chart.AppIcon(SystemInformation.SmallIconSize.Width, LampColor);
        old?.Dispose();
        window?.Render();
    }

    void Popup(string title, string text, ToolTipIcon icon, Action onClick)
    {
        balloonClick = onClick;
        tray.ShowBalloonTip(5000, title, text, icon);
    }

    async void OnClipboardChanged()
    {
        if (Settings.Paused) return;
        string text;
        byte[]? image = null;
        try
        {
            // Password managers mark their copies with these formats. Never send those.
            if (Clipboard.ContainsData("ExcludeClipboardContentFromMonitorProcessing")
                || Clipboard.ContainsData("Clipboard Viewer Ignore")
                || OptedOut("CanUploadToCloudClipboard")   // apps set it to 0 for "never send this to other devices"
                || Clipboard.ContainsData(ImageClip.Marker)) return;   // the marker: an image Ferry itself put there
            if (Clipboard.ContainsText()) text = Clipboard.GetText();   // text wins when an app copies both
            else if (Settings.Images && Clipboard.ContainsImage()) (text, image) = ("", ImageClip.FromClipboard());
            else return;
        }
        catch (ExternalException)
        {
            return;   // another app is holding the clipboard
        }
        if (image is not null)
        {
            await SendImage(image);
            return;
        }
        if (text.Length == 0 || text == last) return;

        last = text;
        try
        {
            await Sync.Send(Settings.Code, text);
            Crossed("Laptop → Phone", text, DateTime.Now);
        }
        catch (Exception e) when (e is HttpRequestException or TaskCanceledException)
        {
            last = null;
            Popup("Not sent", "Ferry could not reach ntfy.sh. Copy the text again when you are online.", ToolTipIcon.Warning, ShowWindow);
        }
    }

    /// <summary>The text as a web link, when the whole copy is one http(s) address; otherwise null.</summary>
    public static Uri? Link(string text)
    {
        string s = text.Trim();
        return s.Length < 2048 && !s.Any(char.IsWhiteSpace) && Uri.TryCreate(s, UriKind.Absolute, out var u)
            && (u.Scheme == Uri.UriSchemeHttps || u.Scheme == Uri.UriSchemeHttp) ? u : null;
    }

    /// <summary>Opens a link in the default browser. Only http(s), checked by Link(), so a copy can never start a program.</summary>
    static void OpenLink(Uri url) =>
        System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo(url.AbsoluteUri) { UseShellExecute = true });

    /// <summary>True when the copying app set this Windows clipboard flag to 0 (a 4-byte number), meaning "do not share".</summary>
    static bool OptedOut(string format) =>
        Clipboard.GetData(format) is MemoryStream flag && flag.Length >= 4 && BitConverter.ToInt32(flag.ToArray(), 0) == 0;

    void Apply(string text, DateTime at)
    {
        Crossed("Phone → Laptop", text, at);
        // A copy that waited more than 10 minutes (laptop asleep or off) goes to history only.
        // It must not replace what you copied since.
        if (Settings.Paused || DateTime.Now - at > TimeSpan.FromMinutes(10)) return;
        last = text;
        try
        {
            Clipboard.SetDataObject(text, true, 5, 100);
        }
        catch (ExternalException)
        {
            return;
        }
string from = Settings.Peer == "" ? "your phone" : Settings.Peer;
        if (!Settings.Popups) return;
        // A copied link gets a pop-up that opens it; anything else just says what arrived.
        if (Link(text) is { } url) Popup("Link from " + from, url.AbsoluteUri + "\nClick to open it.", ToolTipIcon.None, () => OpenLink(url));
        else Popup("Copied from " + from, Preview(text), ToolTipIcon.None, ShowWindow);
    }

    async Task SendImage(byte[] image)
    {
        string hash = Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(image));
        if (hash == lastImage) return;   // some apps announce one copy several times
        lastImage = hash;
        byte[]? small = await Task.Run(() => ImageClip.Shrink(image));
        if (small is null)
        {
            Popup("Image not sent", "It is over 10 MB, even after Ferry made it smaller.", ToolTipIcon.Warning, ShowWindow);
            return;
        }
        try
        {
            await Sync.SendImage(Settings.Code, small);
            Crossed("Laptop → Phone", "Image", DateTime.Now, "");
        }
        catch (Exception e) when (e is HttpRequestException or TaskCanceledException)
        {
            lastImage = null;
            Popup("Image not sent", "Ferry could not reach ntfy.sh. Copy the image again when you are online.", ToolTipIcon.Warning, ShowWindow);
        }
    }

    void ApplyImage(byte[] image, DateTime at)
    {
        if (!Settings.Images) return;
        string path;
        try
        {
            path = ImageClip.Save(image);
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            path = "";
        }
        Crossed("Phone → Laptop", "Image", at, path);
        if (Settings.Paused || DateTime.Now - at > TimeSpan.FromMinutes(10)) return;   // late arrivals go to history only, as with text
        try
        {
            ImageClip.ToClipboard(image);
        }
        catch (Exception e) when (e is ExternalException or ArgumentException)
        {
            return;   // clipboard busy, or not a picture Windows can read
        }
        if (Settings.Popups)
            Popup("Image from " + (Settings.Peer == "" ? "your phone" : Settings.Peer),
                path == "" ? "In your clipboard. Press Ctrl+V to paste." : "In your clipboard and in Pictures\\Ferry. Click to open it.",
                ToolTipIcon.None, () => Open(path));
    }

    /// <summary>Puts a saved image back on the clipboard without sending it to the phone.</summary>
    public void CopyImageQuietly(string path) => ImageClip.ToClipboard(File.ReadAllBytes(path));

    static void Open(string path)
    {
        if (path != "" && File.Exists(path)) System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo(path) { UseShellExecute = true });
    }

    /// <summary>Puts text on the clipboard without sending it to the phone.</summary>
    public void CopyQuietly(string text)
    {
        last = text;
        Clipboard.SetDataObject(text, true, 5, 100);
    }

    void Paired(string name)
    {
        name = name.Trim();
        Settings.Peer = name.Length == 0 ? "your phone" : name[..Math.Min(name.Length, 40)];
        Settings.Save();
        window?.HidePairing();
        Popup("Phone paired", $"{Settings.Peer} is now linked to this laptop. Your text is encrypted.", ToolTipIcon.Info, ShowWindow);
        Changed();
    }

    public void ResetPairing()
    {
        Settings.Code = Crypto.NewCode();
        Settings.Peer = "";
        Settings.LastId = "";
        Settings.History.Clear();
        Settings.Save();
        sync.Start(Settings.Code, "");
        Changed();
    }

    public void ClearHistory()
    {
        Settings.History.Clear();
        Settings.Save();
        Changed();
    }

    void Crossed(string direction, string text, DateTime at, string? imagePath = null)
    {
        if (text.Length <= HistoryMaxChars) Settings.History.Insert(0, new Crossing(direction, text, at, imagePath));
        if (Settings.History.Count > HistorySize) Settings.History.RemoveRange(HistorySize, Settings.History.Count - HistorySize);
        Settings.Save();
        Changed();
    }

    async Task CheckForUpdate()
    {
        var found = await Updater.Check();
        if (found is null || found == update) return;
        update = found;
        updateItem.Text = $"Update to Ferry {found.Value.Version}";
        updateItem.Visible = true;
        Popup($"Ferry {found.Value.Version} is ready", "Click to update. Ferry restarts by itself; your pairing stays.", ToolTipIcon.Info, InstallUpdate);
    }

    async void InstallUpdate()
    {
        if (update is not { } u) return;
        updateItem.Enabled = false;
        updateItem.Text = "Downloading update…";
        try
        {
            await Updater.Install(u.Url, () => { showWait.Unregister(null); show.Dispose(); });
            Quit();
        }
        catch (Exception e) when (e is HttpRequestException or TaskCanceledException or IOException or UnauthorizedAccessException)
        {
            updateItem.Enabled = true;
            updateItem.Text = $"Update to Ferry {u.Version}";
            Popup("Update failed", "Ferry could not download or install the update. It keeps working; try again later from the tray menu.", ToolTipIcon.Warning, () => { });
        }
    }

    void ShowWindow()
    {
        if (window is null || window.IsDisposed) window = new MainWindow(this);
        window.Show();
        if (window.WindowState == FormWindowState.Minimized) window.WindowState = FormWindowState.Normal;
        window.Activate();
    }

    void Quit()
    {
        tray.Visible = false;
        updateTimer.Stop();
        sync.Stop();
        watcher.Dispose();
        window?.Dispose();
        ExitThread();
    }
}

/// <summary>Raises ClipboardChanged on the UI thread whenever any app changes the clipboard.</summary>
sealed class ClipboardWatcher : NativeWindow, IDisposable
{
    const int WM_CLIPBOARDUPDATE = 0x031D;
    public event Action? ClipboardChanged;

    public ClipboardWatcher()
    {
        CreateHandle(new CreateParams { Parent = new IntPtr(-3) });   // HWND_MESSAGE: an invisible, message-only window
        AddClipboardFormatListener(Handle);
    }

    protected override void WndProc(ref Message m)
    {
        if (m.Msg == WM_CLIPBOARDUPDATE) ClipboardChanged?.Invoke();
        base.WndProc(ref m);
    }

    public void Dispose()
    {
        RemoveClipboardFormatListener(Handle);
        DestroyHandle();
    }

    [DllImport("user32.dll")] static extern bool AddClipboardFormatListener(IntPtr hwnd);
    [DllImport("user32.dll")] static extern bool RemoveClipboardFormatListener(IntPtr hwnd);
}
