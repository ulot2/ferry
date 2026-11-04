using System.IO.Pipes;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Text.Json;
using System.Text.RegularExpressions;
using Microsoft.Win32;

namespace Ferry;

/// <summary>Lives in the tray. Owns the settings, the ntfy.sh link, the clipboard watcher, updates and the window.</summary>
sealed class TrayApp : ApplicationContext
{
    const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
    const int HistorySize = 10, HistoryMaxChars = 100_000;
    static readonly string PipeName = "Ferry.Send." + Environment.UserName;   // Send to > Ferry hands files over this pipe
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
    string? pending;   // a copy that could not be sent; it goes when the link is back
    DateTime pendingAt;
    (string Version, string Url)? update;
    Action balloonClick = () => { };

    public Settings Settings { get; }
    public LinkState State { get; private set; } = LinkState.Connecting;
    public Crossing? LastCrossing => Settings.History.FirstOrDefault();
    /// <summary>The file on its way, its size (-1 when unknown), the part done (0 to 1), and its direction; null when no file is crossing.</summary>
    // ponytail: one bar; a send and a receive at the same time share it
    public (string Name, long Bytes, double Done, bool ToPhone)? Transfer { get; private set; }

    public TrayApp(EventWaitHandle show, string[] files)
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
        Chart.Apply(Settings.Theme);
        Shortcuts();

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
        menu.Items.Add("Send a file to the phone…", null, (_, _) => PickFiles());
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

        sync.StateChanged += s => ui.Post(_ => { State = s; Changed(); if (s == LinkState.Connected) SendPending(); }, null);
        sync.TextReceived += (t, at) => ui.Post(_ => Apply(t, at), null);
        sync.ImageReceived += (image, at) => ui.Post(_ => ApplyImage(image, at), null);
        sync.FileReceived += (name, path, at) => ui.Post(_ => ApplyFile(name, path, at), null);
        sync.FileComing += (name, bytes, at) => ui.Post(_ => FileComing(name, bytes, at), null);
        sync.FileOffered += (offer, at) => ui.Post(_ => ReceiveDirect(offer, at), null);
        sync.NotificationReceived += (app, title, text) => ui.Post(_ => PhoneNotification(app, title, text), null);
        sync.PhonePaired += name => ui.Post(_ => Paired(name), null);
        sync.PhoneUnpaired += () => ui.Post(_ => PhoneUnpaired(), null);
        sync.MessageSeen += id => ui.Post(_ => { Settings.LastId = id; Settings.Save(); }, null);
        watcher.ClipboardChanged += OnClipboardChanged;
        // After a network change or sleep the old stream is dead, and Sync needs up to 90 s to notice. Reconnect at once.
        NetworkChange.NetworkAddressChanged += OnNetworkChanged;
        SystemEvents.PowerModeChanged += OnPowerModeChanged;
        SystemEvents.UserPreferenceChanged += OnUserPreferenceChanged;
        showWait = ThreadPool.RegisterWaitForSingleObject(show, (_, _) => ui.Post(_ => ShowWindow(), null), null, -1, false);
        updateTimer.Tick += async (_, _) => await CheckForUpdate();
        updateTimer.Start();

        Changed();
        tray.Visible = true;
        sync.Start(Settings.Code, Settings.LastId);
        if (Settings.Peer == "") ShowWindow();   // not paired yet: show the QR code
        _ = CheckForUpdate();
        _ = ListenForFiles();
        if (files.Length > 0) _ = SendFiles(files);   // Send to > Ferry while Ferry was not running
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

    /// <summary>"system", "light" or "dark". The window is built again in the new colors, with Settings still open.</summary>
    public string Theme
    {
        get => Settings.Theme;
        set
        {
            if (Settings.Theme == value) return;
            Settings.Theme = value;
            Settings.Save();
            ApplyTheme(fromSettings: true);
        }
    }

    void ApplyTheme(bool fromSettings)
    {
        bool wasDark = Chart.Dark;
        Chart.Apply(Settings.Theme);
        if (Chart.Dark == wasDark || window is null || window.IsDisposed)
        {
            window?.Render();   // the chips show the new choice
            return;
        }
        // Controls keep the colors they were made with, so a new window is simpler than repainting each one.
        bool visible = window.Visible;
        var at = window.Location;
        window.Dispose();
        window = new MainWindow(this);
        if (!visible) return;
        ShowWindow();
        window.Location = at;
        if (fromSettings) window.ShowSettings();
    }

    /// <summary>Follows Windows when it switches between light and dark apps, while the theme is "system".</summary>
    void OnUserPreferenceChanged(object? sender, UserPreferenceChangedEventArgs e)
    {
        if (e.Category == UserPreferenceCategory.General && Settings.Theme == "system") ui.Post(_ => ApplyTheme(fromSettings: false), null);
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
    /// Keeps a "Ferry" entry in the Start menu, so Ferry can be opened again after Quit, and a
    /// "Ferry (phone)" entry in the Send to menu of File Explorer. Written on every start, so they follow Ferry.exe if the file moves.
    /// </summary>
    static void Shortcuts()
    {
        Shortcut(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.Programs), "Ferry.lnk"), "Ferry: clipboard between this laptop and your phone");
        Shortcut(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.SendTo), "Ferry (phone).lnk"), "Send the file to your phone with Ferry");
    }

    static void Shortcut(string link, string description)
    {
        try
        {
            dynamic shell = Activator.CreateInstance(Type.GetTypeFromProgID("WScript.Shell")!)!;
            dynamic shortcut = shell.CreateShortcut(link);
            shortcut.TargetPath = Environment.ProcessPath;
            shortcut.Description = description;
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
            (pending, pendingAt) = (text, DateTime.Now);
            Popup("Not sent yet", "Ferry could not reach ntfy.sh. It sends the text when the link is back.", ToolTipIcon.Warning, ShowWindow);
        }
    }

    /// <summary>Sends the copy that failed earlier. A copy older than 10 minutes is dropped, the same rule as for arrivals.</summary>
    async void SendPending()
    {
        if (pending is not { } text || Settings.Paused) return;
        pending = null;   // taken, so a second reconnect does not send it twice
        if (DateTime.Now - pendingAt > TimeSpan.FromMinutes(10)) return;
        try
        {
            await Sync.Send(Settings.Code, text);
            Crossed("Laptop → Phone", text, DateTime.Now);
        }
        catch (Exception e) when (e is HttpRequestException or TaskCanceledException)
        {
            pending ??= text;   // still offline: try again on the next connection, unless a newer copy is waiting
        }
    }

    void OnNetworkChanged(object? sender, EventArgs e) => ui.Post(_ => Reconnect(), null);

    void OnPowerModeChanged(object? sender, PowerModeChangedEventArgs e)
    {
        if (e.Mode == PowerModes.Resume) ui.Post(_ => Reconnect(), null);
    }

    /// <summary>Opens a new stream. "since" replays what arrived meanwhile, so nothing is lost.</summary>
    void Reconnect() => sync.Start(Settings.Code, Settings.LastId);

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

    /// <summary>Puts a file on the clipboard, to paste in a folder or an email. Ferry never sends a copied file.</summary>
    public static void CopyFileQuietly(string path) => Clipboard.SetFileDropList(new System.Collections.Specialized.StringCollection { path });

    /// <summary>Sends files the user chose: Send to, drag and drop, or the tray menu. Copying a file never sends it.</summary>
    public async Task SendFiles(IEnumerable<string> paths)
    {
        ShowWindow();   // the window shows the progress
        window?.ShowLogbook();
        // One file at a time, also across two Send to clicks: the phone puts parts together in the order they come.
        await sending.WaitAsync();
        try
        {
            foreach (string path in paths) await SendFile(path);
        }
        finally
        {
            sending.Release();
        }
    }

    readonly SemaphoreSlim sending = new(1, 1);

    async Task SendFile(string path)
    {
        string name = Path.GetFileName(path);
        var info = new FileInfo(path);
        if (!info.Exists)
        {
            Popup("File not sent", $"{name} is a folder, or it is gone. Ferry sends files only.", ToolTipIcon.Warning, ShowWindow);
            return;
        }
        bool over = false;   // a progress report can arrive after the send ends; it must not bring the bar back
        var progress = new Progress<double>(done =>
        {
            if (over || Transfer is not { } t || (int)(done * 100) == (int)(t.Done * 100)) return;
            Transfer = (name, info.Length, done, true);
            ShowTransfer();
        });
        Transfer = (name, info.Length, 0, true);
        ShowTransfer();
        try
        {
            // Straight over Wi-Fi when the phone is on the same network; otherwise through ntfy.sh, up to 90 MB.
            bool direct;
            try
            {
                direct = await Lan.Send(Settings.Code, path, progress);
            }
            catch (Exception e) when (e is IOException or SocketException or OperationCanceledException)
            {
                if (info.Length > Sync.MaxFileBytes)
                {
                    Popup("File not sent", $"{name} did not reach the phone: the Wi-Fi connection broke off. Send it again.", ToolTipIcon.Warning, ShowWindow);
                    return;
                }
                direct = false;
            }
            if (!direct)
            {
                if (info.Length > Sync.MaxFileBytes)
                {
                    Popup("File not sent", $"{name} is over 90 MB. Ferry sends files that large only when the laptop and the phone are on the same Wi-Fi.", ToolTipIcon.Warning, ShowWindow);
                    return;
                }
                await Sync.SendFile(Settings.Code, path, progress);
            }
            over = true;
            Transfer = null;
            Crossed("Laptop → Phone", name, DateTime.Now, filePath: path);
            if (Settings.Popups) Popup("Sent to " + (Settings.Peer == "" ? "your phone" : Settings.Peer), name + "\nIn Downloads/Ferry on the phone.", ToolTipIcon.None, ShowWindow);
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            Popup("File not sent", $"Ferry could not read {name}. Another program can be using it.", ToolTipIcon.Warning, ShowWindow);
        }
        catch (HttpRequestException e) when (e.StatusCode is HttpStatusCode.RequestEntityTooLarge or HttpStatusCode.TooManyRequests)
        {
            Popup("File not sent", "ntfy.sh refused the file. It keeps at most 100 MB of your files at a time, for 3 hours. Try again later.", ToolTipIcon.Warning, ShowWindow);
        }
        catch (Exception e) when (e is HttpRequestException or TaskCanceledException)
        {
            Popup("File not sent", "Ferry could not reach ntfy.sh. Send the file again when you are online.", ToolTipIcon.Warning, ShowWindow);
        }
        finally
        {
            if (!over)
            {
                over = true;
                Transfer = null;
                Changed();
            }
        }
    }

    /// <summary>The phone offers a file straight over Wi-Fi. If the laptop cannot take it, it says so, and the phone uses ntfy.sh at once.</summary>
    async void ReceiveDirect(JsonElement offer, DateTime at)
    {
        string name = offer.TryGetProperty("name", out var n) ? n.GetString() ?? "file" : "file";
        long size = offer.TryGetProperty("size", out var z) && z.TryGetInt64(out long b) ? b : -1;
        FileComing(name, size, at);
        bool over = false;
        var progress = new Progress<double>(done =>
        {
            if (over || Transfer is not { } t || (int)(done * 100) == (int)(t.Done * 100)) return;
            Transfer = (name, size, done, false);
            ShowTransfer();
        });
        Transfer = (name, size, 0, false);
        ShowTransfer();
        string temp = "";
        try
        {
            // Written next to where it ends up, so the move after is instant even for a large file.
            string dir = Path.Combine(DownloadsFolder(), "Ferry");
            Directory.CreateDirectory(dir);
            temp = Path.Combine(dir, $"{Guid.NewGuid():N}.part");
            if (await Lan.Receive(Settings.Code, offer, temp, progress))
            {
                over = true;
                Transfer = null;
                ApplyFile(name, temp, at);
                return;
            }
        }
        catch (Exception e) when (e is IOException or SocketException or OperationCanceledException or UnauthorizedAccessException)
        {
            // Broke off. The phone sees it too, and sends a file up to 90 MB through ntfy.sh.
        }
        finally
        {
            if (!over)
            {
                over = true;
                Transfer = null;
                Changed();
            }
        }
        try { if (temp != "") File.Delete(temp); } catch (IOException) { }
        try
        {
            await Sync.PostLan(Settings.Code, new { t = "nolan", id = offer.GetProperty("id").GetString() });
        }
        catch (Exception e) when (e is HttpRequestException or TaskCanceledException)
        {
            // Offline: the phone stops waiting by itself after 10 seconds.
        }
    }

    void ShowTransfer()
    {
        if (Transfer is not { } t) return;
        string tip = $"Ferry · {(t.ToPhone ? "Sending" : "Receiving")} {t.Name}, {(int)(t.Done * 100)}%";
        tray.Text = tip.Length > 127 ? tip[..127] : tip;
        window?.Invalidate();
    }

    public async void PickFiles()
    {
        using var dialog = new OpenFileDialog { Title = "Send to the phone", Multiselect = true };
        if (dialog.ShowDialog() == DialogResult.OK) await SendFiles(dialog.FileNames);
    }

    /// <summary>Receives file paths from a second Ferry.exe that Send to > Ferry started. Only this Windows user can connect.</summary>
    async Task ListenForFiles()
    {
        while (true)
        {
            var paths = new List<string>();
            try
            {
                using var pipe = new NamedPipeServerStream(PipeName, PipeDirection.In, 1, PipeTransmissionMode.Byte, PipeOptions.Asynchronous | PipeOptions.CurrentUserOnly);
                await pipe.WaitForConnectionAsync();
                using var reader = new StreamReader(pipe);
                while (await reader.ReadLineAsync() is { } line) paths.Add(line);
            }
            catch (Exception e) when (e is IOException or UnauthorizedAccessException)
            {
                await Task.Delay(1000);   // the other end closed early, or the pipe is busy: wait for the next one
                continue;
            }
            _ = SendFiles(paths);   // not awaited, so the next Send to is not kept waiting
        }
    }

    /// <summary>Gives file paths to the Ferry that is already running. A second Ferry.exe, started by Send to > Ferry, calls this.</summary>
    public static void HandOver(string[] paths)
    {
        try
        {
            using var pipe = new NamedPipeClientStream(".", PipeName, PipeDirection.Out, PipeOptions.CurrentUserOnly);
            pipe.Connect(5000);
            using var writer = new StreamWriter(pipe);
            foreach (string p in paths) writer.WriteLine(Path.GetFullPath(p));
        }
        catch (Exception e) when (e is TimeoutException or IOException or UnauthorizedAccessException)
        {
            MessageBox.Show("Ferry could not take the file. Quit Ferry from the tray, open it again, and then send the file again.", "Ferry", MessageBoxButtons.OK, MessageBoxIcon.Warning);
        }
    }

    /// <summary>The phone is about to send a file: say what is coming before it is all here.</summary>
    /// <summary>A phone notification that Roost passed on. Shown only; Ferry keeps nothing of it.</summary>
    void PhoneNotification(string app, string title, string text)
    {
        if (Settings.Paused) return;
        string heading = app == "" ? title : title == "" ? app : app + " · " + title;
        Popup(heading == "" ? "Phone notification" : heading, text == "" ? " " : Preview(text), ToolTipIcon.None, () => { });
    }

    void FileComing(string name, long bytes, DateTime at)
    {
        if (Settings.Popups && !Settings.Paused && DateTime.Now - at < TimeSpan.FromMinutes(10))
            Popup("Coming from " + (Settings.Peer == "" ? "your phone" : Settings.Peer), (bytes < 0 ? name : $"{name}, {bytes / 1048576.0:0.0} MB") + "\nFerry saves it in Downloads\\Ferry.", ToolTipIcon.None, ShowWindow);
    }

    /// <summary>A file from the phone, in a temporary file that this moves to Downloads\Ferry.</summary>
    void ApplyFile(string name, string temp, DateTime at)
    {
        string path;
        try
        {
            path = SaveDownload(name, temp);
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            try { File.Delete(temp); } catch (IOException) { }
            Popup("File not saved", $"Ferry could not save {name} in Downloads\\Ferry.", ToolTipIcon.Warning, ShowWindow);
            return;
        }
        Crossed("Phone → Laptop", Path.GetFileName(path), at, filePath: path);
        if (Settings.Popups && !Settings.Paused && DateTime.Now - at < TimeSpan.FromMinutes(10))
            Popup("File from " + (Settings.Peer == "" ? "your phone" : Settings.Peer), Path.GetFileName(path) + "\nIn Downloads\\Ferry. Click to show it.",
                ToolTipIcon.None, () => ShowInFolder(path));
    }

    /// <summary>Saves a file from the phone in Downloads\Ferry. It never overwrites: a second "a.pdf" becomes "a (2).pdf".</summary>
    static string SaveDownload(string name, string temp)
    {
        string dir = Path.Combine(DownloadsFolder(), "Ferry");
        Directory.CreateDirectory(dir);
        string safe = SafeName(name), path = Path.Combine(dir, safe);
        for (int i = 2; File.Exists(path); i++) path = Path.Combine(dir, $"{Path.GetFileNameWithoutExtension(safe)} ({i}){Path.GetExtension(safe)}");
        File.Move(temp, path);
        // Mark the file as from the internet, as a browser does, so Windows warns before it runs a program or a macro in it.
        try
        {
            File.WriteAllText(path + ":Zone.Identifier", "[ZoneTransfer]\r\nZoneId=3\r\n");
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException or NotSupportedException)
        {
            // A drive without NTFS cannot keep the mark. The file is still saved.
        }
        return path;
    }

    /// <summary>The name from the phone, made safe for Windows: no folder parts, no forbidden characters, no device names.</summary>
    static string SafeName(string name)
    {
        var bad = Path.GetInvalidFileNameChars();
        string s = new string(Path.GetFileName(name).Select(ch => bad.Contains(ch) ? '_' : ch).ToArray()).Trim().TrimEnd('.');
        if (Regex.IsMatch(s, @"^(CON|PRN|AUX|NUL|COM\d|LPT\d)(\..*)?$", RegexOptions.IgnoreCase)) s = "_" + s;
        return s == "" ? "file" : s;
    }

    static string DownloadsFolder() =>
        SHGetKnownFolderPath(new Guid("374DE290-123F-4565-9164-39C4925E467B"), 0, IntPtr.Zero, out string path) == 0
            ? path : Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads");

    [DllImport("shell32.dll", CharSet = CharSet.Unicode)]
    static extern int SHGetKnownFolderPath([MarshalAs(UnmanagedType.LPStruct)] Guid id, uint flags, IntPtr token, out string path);

    /// <summary>Opens File Explorer with the file selected. It never opens the file itself.</summary>
    public static void ShowInFolder(string path)
    {
        if (File.Exists(path)) System.Diagnostics.Process.Start("explorer.exe", $"/select,\"{path}\"");
    }

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
        window?.ShowLogbook();
        Popup("Phone paired", $"{Settings.Peer} is now linked to this laptop. Your text is encrypted.", ToolTipIcon.Info, ShowWindow);
        Changed();
    }

    /// <summary>Makes a new pairing code and tells the phone on the old code to unpair too.</summary>
    public void ResetPairing()
    {
        string old = Settings.Code;
        NewPairing();
        _ = TellPhoneUnpaired(old);
    }

    /// <summary>If the laptop is offline, the phone is not told. It then stays on the old code, which nothing sends to any more.</summary>
    static async Task TellPhoneUnpaired(string oldCode)
    {
        try
        {
            await Sync.Send(oldCode, "unpair", "laptop,unpair");
        }
        catch (Exception e) when (e is HttpRequestException or TaskCanceledException)
        {
        }
    }

    void PhoneUnpaired()
    {
        string who = Settings.Peer == "" ? "Your phone" : Settings.Peer;
        NewPairing();
        Popup("Phone unpaired", $"{who} unpaired Ferry. To pair again, scan the new code with the phone.", ToolTipIcon.Info, ShowWindow);
    }

    void NewPairing()
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

    void Crossed(string direction, string text, DateTime at, string? imagePath = null, string? filePath = null)
    {
        if (text.Length <= HistoryMaxChars) Settings.History.Insert(0, new Crossing(direction, text, at, imagePath, filePath));
        if (Settings.History.Count > HistorySize) Settings.History.RemoveRange(HistorySize, Settings.History.Count - HistorySize);
        if (at >= pendingAt) pending = null;   // a newer crossing replaces the copy that waits
        Settings.Save();
        Changed();
    }

    /// <summary>The newer version found by the last check, or null.</summary>
    public (string Version, string Url)? Update => update;

    /// <summary>"Check for updates" in Settings. Returns the line to show under it.</summary>
    public async Task<string> CheckNow()
    {
        await CheckForUpdate();
        return update is { } u ? $"Ferry {u.Version} is ready. Click Update to Ferry {u.Version} above." : "Ferry found no newer version.";
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

    public async void InstallUpdate()
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
        NetworkChange.NetworkAddressChanged -= OnNetworkChanged;
        SystemEvents.PowerModeChanged -= OnPowerModeChanged;
        SystemEvents.UserPreferenceChanged -= OnUserPreferenceChanged;
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
