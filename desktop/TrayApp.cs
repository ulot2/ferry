using System.Runtime.InteropServices;
using System.Text.RegularExpressions;
using Microsoft.Win32;

namespace Ferry;

record Crossing(string Direction, string Preview, DateTime Time);

/// <summary>Lives in the tray. Owns the settings, the ntfy.sh link, the clipboard watcher and the window.</summary>
sealed class TrayApp : ApplicationContext
{
    const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
    readonly WindowsFormsSynchronizationContext ui = new();
    readonly Sync sync = new();
    readonly NotifyIcon tray = new();
    readonly ClipboardWatcher watcher = new();
    readonly ToolStripMenuItem statusItem = new() { Enabled = false };
    readonly ToolStripMenuItem pauseItem = new("Pause syncing") { CheckOnClick = true };
    readonly ToolStripMenuItem autostartItem = new("Start with Windows") { CheckOnClick = true };
    MainWindow? window;
    string? last;   // text we last sent or applied, so our own clipboard changes do not echo back

    public Settings Settings { get; }
    public LinkState State { get; private set; } = LinkState.Connecting;
    public Crossing? LastCrossing { get; private set; }

    public TrayApp(EventWaitHandle show)
    {
        SynchronizationContext.SetSynchronizationContext(ui);
        bool firstRun = !Settings.Exists;
        Settings = Settings.Load();
        if (Settings.Topic == "")
        {
            Settings.Topic = Settings.NewTopic();
            Settings.Save();
        }
        if (firstRun) StartWithWindows = true;

        pauseItem.Checked = Settings.Paused;
        pauseItem.CheckedChanged += (_, _) => Paused = pauseItem.Checked;
        autostartItem.Checked = StartWithWindows;
        autostartItem.CheckedChanged += (_, _) => StartWithWindows = autostartItem.Checked;

        var menu = new ContextMenuStrip();
        menu.Items.Add(statusItem);
        menu.Items.Add(new ToolStripMenuItem("Open Ferry", null, (_, _) => ShowWindow()) { Font = new Font(menu.Font, FontStyle.Bold) });
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add(pauseItem);
        menu.Items.Add(autostartItem);
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add("Quit Ferry", null, (_, _) => Quit());
        tray.ContextMenuStrip = menu;
        tray.MouseClick += (_, e) => { if (e.Button == MouseButtons.Left) ShowWindow(); };
        tray.BalloonTipClicked += (_, _) => ShowWindow();

        sync.StateChanged += s => ui.Post(_ => { State = s; Changed(); }, null);
        sync.TextReceived += t => ui.Post(_ => Apply(t), null);
        sync.PhonePaired += name => ui.Post(_ => Paired(name), null);
        watcher.ClipboardChanged += OnClipboardChanged;
        ThreadPool.RegisterWaitForSingleObject(show, (_, _) => ui.Post(_ => ShowWindow(), null), null, -1, false);

        Changed();
        tray.Visible = true;
        sync.Start(Settings.Topic);
        if (Settings.Peer == "") ShowWindow();   // not paired yet: show the QR code
    }

    public string StatusLabel => Settings.Paused ? "Paused" : State switch
    {
        LinkState.Connected => "Connected",
        LinkState.Offline => "Offline",
        _ => "Connecting",
    };

    public Color LampColor => Settings.Paused ? Harbor.OnNavyMuted : State switch
    {
        LinkState.Connected => Harbor.LampOn,
        LinkState.Offline => Harbor.LampOff,
        _ => Harbor.OnNavyMuted,
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

    public bool StartWithWindows
    {
        get => Registry.CurrentUser.OpenSubKey(RunKey)?.GetValue("Ferry") is string;
        set
        {
            using var key = Registry.CurrentUser.CreateSubKey(RunKey);
            if (value) key.SetValue("Ferry", $"\"{Environment.ProcessPath}\"");
            else key.DeleteValue("Ferry", false);
            autostartItem.Checked = value;
        }
    }

    public string PairingLink =>
        $"ferry://pair?topic={Settings.Topic}&name={Uri.EscapeDataString(Environment.MachineName)}";

    void Changed()
    {
        statusItem.Text = "Ferry · " + StatusLabel;
        string tip = "Ferry · " + StatusLabel + (LastCrossing is { } c ? $"\nLast: {c.Direction}, {c.Time:t}" : "");
        tray.Text = tip.Length > 127 ? tip[..127] : tip;
        var old = tray.Icon;
        tray.Icon = Harbor.AppIcon(SystemInformation.SmallIconSize.Width, LampColor);
        old?.Dispose();
        window?.Render();
    }

    async void OnClipboardChanged()
    {
        if (Settings.Paused) return;
        string text;
        try
        {
            // Password managers mark their copies with these formats. Never send those.
            if (!Clipboard.ContainsText()
                || Clipboard.ContainsData("ExcludeClipboardContentFromMonitorProcessing")
                || Clipboard.ContainsData("Clipboard Viewer Ignore")) return;
            text = Clipboard.GetText();
        }
        catch (ExternalException)
        {
            return;   // another app is holding the clipboard
        }
        if (text.Length == 0 || text == last) return;

        last = text;
        try
        {
            await Sync.Send(Settings.Topic, text);
            Crossed("Laptop → Phone", text);
        }
        catch (Exception e) when (e is HttpRequestException or TaskCanceledException)
        {
            last = null;
            tray.ShowBalloonTip(4000, "Not sent", "Ferry could not reach ntfy.sh. Copy the text again when you are online.", ToolTipIcon.Warning);
        }
    }

    void Apply(string text)
    {
        if (Settings.Paused) return;
        last = text;
        try
        {
            Clipboard.SetDataObject(text, true, 5, 100);
        }
        catch (ExternalException)
        {
            return;
        }
        Crossed("Phone → Laptop", text);
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
        tray.ShowBalloonTip(3000, "Phone paired", $"{Settings.Peer} is now linked to this laptop.", ToolTipIcon.Info);
        Changed();
    }

    public void ResetPairing()
    {
        Settings.Topic = Settings.NewTopic();
        Settings.Peer = "";
        Settings.Save();
        LastCrossing = null;
        sync.Start(Settings.Topic);
        Changed();
    }

    void Crossed(string direction, string text)
    {
        string preview = Regex.Replace(text.Trim(), @"\s+", " ");
        if (preview.Length > 120) preview = preview[..120] + "…";
        LastCrossing = new Crossing(direction, preview, DateTime.Now);
        Changed();
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
