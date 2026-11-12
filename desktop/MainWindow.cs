using System.Collections;
using System.Drawing.Drawing2D;
using System.Drawing.Text;
using QRCoder;

namespace Ferry;

/*
THESIS: The laptop side of the same sea chart: two harbors and the course between them, then the logbook. Pairing by QR is the one job this window has before a phone is paired.
OWN-WORLD: Nautical chart. Day: blue shallows, buff land, ink-navy harbors; night: a soft charcoal chart with grey contours. Magenta is the course and the one action. Barlow, italic for places. Title bar painted in the water color.
STORY: First run: scan the code, see "Phone paired". Later: glance at the chart and the last crossing, double-click a logbook entry to copy it again, drop a file to send it. Settings holds what you set once.
FIRST VIEWPORT: Chart band (wordmark left, status pill and settings button right, harbors and course); under it the last crossing; then the logbook, Settings, or the QR plate.
FORM: Owner-chosen "Nautical chart" from the four-direction canvas, same as the phone, with a soft charcoal night mode.
*/

/// <summary>The Ferry window: the chart band, the last crossing, and the logbook, Settings, or the pairing code. See DESIGN.md.</summary>
sealed class MainWindow : Form
{
    enum View { Logbook, Settings, Pairing }

    readonly TrayApp app;
    readonly TextBox code;
    readonly ListBox history;
    readonly PillButton settings, copy, done, sendFile, photos, clear, settingsDone, themeSystem, themeLight, themeDark, showCode, reset, checkUpdate;
    readonly SwitchRow pause, popups, images, autostart, helm;
    List<Crossing> earlier = [];
    List<object> rows = [];   // the logbook as shown: a string is a day heading (TODAY, YESTERDAY, EARLIER), a Crossing is an entry
    readonly Font wordmark = Chart.Font("Barlow Condensed SemiBold", 15f), pillFont = Chart.Font("Barlow Medium", 9.5f),
        kicker = Chart.Font("Barlow Medium", 8f), crossing = Chart.Font("Barlow Medium", 12.5f), body = Chart.Font("Barlow", 10f),
        bold = Chart.Font("Barlow", 10f, FontStyle.Bold), italic = Chart.Font("Barlow", 10f, FontStyle.Italic),
        title = Chart.Font("Barlow Medium", 13f), small = Chart.Font("Barlow", 9f), pageTitle = Chart.Font("Barlow Medium", 16f);
    bool pairingShown, settingsShown;
    string hint = "", aboutHint = "";
    List<BitArray>? qr;
    string? qrFor;

    public MainWindow(TrayApp app)
    {
        this.app = app;
        SuspendLayout();
        AutoScaleDimensions = new SizeF(96F, 96F);
        AutoScaleMode = AutoScaleMode.Dpi;
        ClientSize = new Size(420, 616);
        FormBorderStyle = FormBorderStyle.FixedSingle;
        MaximizeBox = false;
        StartPosition = FormStartPosition.CenterScreen;
        BackColor = Chart.Ground;
        Font = body;
        Icon = Chart.AppIcon(32, null);
        DoubleBuffered = true;
        KeyPreview = true;

        // On the chart band, right of the status pill. Opens Settings, or goes back from it.
        settings = AddButton("Settings", PillKind.Settings, 372, 12, 30, 30);
        settings.Click += (_, _) => { settingsShown = CurrentView != View.Settings; pairingShown = false; Render(); };

        // Pairing view. The QR plate and the texts are painted.
        code = new TextBox
        {
            Bounds = new Rectangle(22, 482, 260, 22),
            ReadOnly = true,
            TabStop = false,   // otherwise it takes focus on open and shows as selected; Copy code is the keyboard path
            BorderStyle = BorderStyle.None,
            BackColor = Chart.Ground,
            ForeColor = Chart.Ink,
            Font = new Font("Consolas", 10f),   // the code is data: a fixed-width face keeps look-alike letters apart
        };
        Controls.Add(code);
        copy = AddButton("Copy code", PillKind.Chip, 290, 474, 108, 36);
        copy.Click += (_, _) => app.CopyQuietly(app.Settings.Code);
        done = AddButton("Done", PillKind.Link, 12, 518, 90, 32);
        done.Click += (_, _) => HidePairing();

        // Logbook view: the list and Send a file. Settings are one click away on the chart band.
        clear = AddButton("Clear", PillKind.Link, 338, 240, 64, 28);
        clear.Click += (_, _) => app.ClearHistory();
        history = new ListBox
        {
            Bounds = new Rectangle(22, 272, 376, 284),
            BorderStyle = BorderStyle.None,
            BackColor = Chart.Ground,
            ForeColor = Chart.Ink,
            DrawMode = DrawMode.OwnerDrawVariable,
            IntegralHeight = false,
            AccessibleName = "Logbook",
        };
        history.DrawItem += DrawHistoryItem;
        history.MeasureItem += (_, e) => e.ItemHeight = S(e.Index < rows.Count && rows[e.Index] is string ? 30 : 40);
        history.DoubleClick += (_, _) => CopySelected();
        history.KeyDown += (_, e) => { if (e.KeyCode == Keys.Enter) CopySelected(); };
        Controls.Add(history);
        sendFile = AddButton("Send a file…", PillKind.Chip, 22, 568, 124, 36);
        sendFile.Click += (_, _) => app.PickFiles();
        photos = AddButton("Phone photos…", PillKind.Chip, 154, 568, 136, 36);
        photos.Click += (_, _) => app.ShowPhotos();

        // Settings view: a page of its own, without the chart band. Titles, section names and hints are painted.
        settingsDone = AddButton("Done", PillKind.Link, 338, 14, 64, 28);
        settingsDone.Click += (_, _) => { settingsShown = false; Render(); };
        // The theme is one choice of three: a segmented control inside a painted frame (see OnPaint), not on/off chips.
        themeSystem = AddButton("System", PillKind.Segment, 25, 87, 123, 32);
        themeSystem.Click += (_, _) => app.Theme = "system";
        themeLight = AddButton("Light", PillKind.Segment, 148, 87, 124, 32);
        themeLight.Click += (_, _) => app.Theme = "light";
        themeDark = AddButton("Dark", PillKind.Segment, 272, 87, 123, 32);
        themeDark.Click += (_, _) => app.Theme = "dark";
        pause = AddSwitch("Pause syncing", "Copies stay on each device until you turn this off.", 184);
        pause.Click += (_, _) => app.Paused = !app.Paused;
        popups = AddSwitch("Show pop-ups", "A pop-up shows what arrives from the phone.", 234);
        popups.Click += (_, _) => app.Popups = !app.Popups;
        images = AddSwitch("Send and receive images", "Copied images cross both ways, up to 10 MB.", 284);
        images.Click += (_, _) => app.Images = !app.Images;
        autostart = AddSwitch("Start with Windows", "Ferry starts in the tray when you sign in.", 334);
        autostart.Click += (_, _) => { app.StartWithWindows = !app.StartWithWindows; Render(); };
        helm = AddSwitch("Let the phone control this laptop", "Helm: the phone moves the pointer, types and locks.", 384);
        helm.Click += (_, _) => app.HelmOn = !app.HelmOn;
        showCode = AddButton("Show pairing code", PillKind.Link, 12, 490, 160, 32);
        showCode.Click += (_, _) => { pairingShown = true; Render(); };
        // Reset pairing unpairs the phone: an outlined adrift button on its own line, apart from the harmless link above.
        reset = AddButton("Reset pairing", PillKind.Danger, 22, 530, 132, 34);
        reset.Click += (_, _) => ConfirmReset();
        checkUpdate = AddButton("Check for updates", PillKind.Link, 12, 622, 220, 32);
        checkUpdate.Click += async (_, _) => await CheckUpdate();

        // Drop files anywhere on the window to send them to the phone.
        foreach (Control target in Controls.Cast<Control>().Append(this))
        {
            target.AllowDrop = true;
            target.DragEnter += (_, e) => e.Effect = e.Data?.GetDataPresent(DataFormats.FileDrop) == true ? DragDropEffects.Copy : DragDropEffects.None;
            target.DragDrop += async (_, e) => { if (e.Data?.GetData(DataFormats.FileDrop) is string[] paths) await app.SendFiles(paths); };
        }

        ResumeLayout(false);
        Render();
    }

    PillButton AddButton(string text, PillKind kind, int x, int y, int w, int h)
    {
        var button = new PillButton(text, kind) { Bounds = new Rectangle(x, y, w, h) };
        Controls.Add(button);
        return button;
    }

    SwitchRow AddSwitch(string text, string hint, int y)
    {
        var row = new SwitchRow(text, hint) { Bounds = new Rectangle(22, y, 376, 46) };
        Controls.Add(row);
        return row;
    }

    View CurrentView => settingsShown && !pairingShown ? View.Settings
        : pairingShown || app.Settings.Peer == "" ? View.Pairing : View.Logbook;

    void CopySelected()
    {
        if (history.SelectedIndex < 0 || history.SelectedIndex >= rows.Count || rows[history.SelectedIndex] is not Crossing item) return;
        try
        {
            if (item.FilePath is not null)
            {
                if (!File.Exists(item.FilePath))
                {
                    hint = "That file is no longer there.";
                    Invalidate();
                    return;
                }
                TrayApp.CopyFileQuietly(item.FilePath);
            }
            else if (item.ImagePath is null) app.CopyQuietly(item.Text);
            else if (File.Exists(item.ImagePath)) app.CopyImageQuietly(item.ImagePath);
            else
            {
                hint = item.ImagePath == "" ? "That image was sent from this laptop." : "That image is no longer in Pictures\\Ferry.";
                Invalidate();
                return;
            }
            hint = "Copied.";
        }
        catch (Exception e) when (e is System.Runtime.InteropServices.ExternalException or IOException or ArgumentException)
        {
            hint = "Not copied: another app is using the clipboard. Try again.";
        }
        Invalidate();
    }

    async Task CheckUpdate()
    {
        if (app.Update is not null)
        {
            app.InstallUpdate();   // the link already says "Update to Ferry x.y.z"
            return;
        }
        checkUpdate.Enabled = false;
        aboutHint = "Looking for a newer version…";
        Invalidate();
        aboutHint = await app.CheckNow();
        checkUpdate.Enabled = true;
        Render();
    }

    void DrawHistoryItem(object? sender, DrawItemEventArgs e)
    {
        if (e.Index < 0 || e.Index >= rows.Count) return;
        var g = e.Graphics;
        var b = e.Bounds;
        float s = DeviceDpi / 96f;
        if (rows[e.Index] is string heading)
        {
            // A day heading: not an entry, so it never shows as selected.
            using (var back = new SolidBrush(Chart.Ground)) g.FillRectangle(back, b);
            g.TextRenderingHint = TextRenderingHint.ClearTypeGridFit;
            Chart.DrawTracked(g, heading, kicker, Chart.InkMuted, b.X + 4 * s, b.Bottom - 18 * s, 1.2f * s);
            return;
        }
        var item = (Crossing)rows[e.Index];
        bool selected = (e.State & DrawItemState.Selected) != 0;
        using (var back = new SolidBrush(selected ? Chart.Hover : Chart.Ground)) g.FillRectangle(back, b);
        using (var rule = new Pen(Chart.RuleRow, 1)) g.DrawLine(rule, b.Left, b.Bottom - 1, b.Right, b.Bottom - 1);

        // Today and Yesterday rows show the time, because the heading gives the day; Earlier rows show the date.
        string when = Group(item.Time) == "EARLIER" ? item.Time.ToString("d MMM") : item.Time.ToString("t");
        bool toPhone = item.Direction.EndsWith("Phone");
        var kind = item.FilePath is not null ? Chart.Kind.File : item.ImagePath is not null ? Chart.Kind.Image
            : TrayApp.Link(item.Text) is not null ? Chart.Kind.Link : Chart.Kind.Text;
        string line = item.FilePath is not null ? item.Text
            : item.ImagePath is null ? TrayApp.Preview(item.Text) : item.ImagePath == "" ? "Image" : Path.GetFileName(item.ImagePath);
        var flags = TextFormatFlags.Left | TextFormatFlags.VerticalCenter | TextFormatFlags.SingleLine;
        TextRenderer.DrawText(g, when, bold, new Rectangle(b.X + (int)(4 * s), b.Y, (int)(66 * s), b.Height), Chart.Ink, flags);
        TextRenderer.DrawText(g, toPhone ? "→" : "←", bold, new Rectangle(b.X + (int)(72 * s), b.Y, (int)(24 * s), b.Height), Chart.Course, flags);
        g.SmoothingMode = SmoothingMode.AntiAlias;
        Chart.DrawKind(g, kind, new RectangleF(b.X + 98 * s, b.Y + (b.Height - 16 * s) / 2, 16 * s, 16 * s), Chart.InkMuted);
        TextRenderer.DrawText(g, line, body, new Rectangle(b.X + (int)(124 * s), b.Y, b.Width - (int)(128 * s), b.Height), Chart.Ink,
            flags | TextFormatFlags.EndEllipsis);
        e.DrawFocusRectangle();
    }

    /// <summary>The logbook group for a time: TODAY, YESTERDAY or EARLIER.</summary>
    static string Group(DateTime t) => t.Date == DateTime.Today ? "TODAY" : t.Date == DateTime.Today.AddDays(-1) ? "YESTERDAY" : "EARLIER";

    /// <summary>The entries with a heading before each day group. Newest first, so the groups come in the order Today, Yesterday, Earlier.</summary>
    static List<object> WithHeadings(List<Crossing> items)
    {
        var list = new List<object>();
        string? group = null;
        foreach (var item in items)
        {
            if (Group(item.Time) != group) list.Add(group = Group(item.Time));
            list.Add(item);
        }
        return list;
    }

    int S(int v) => (int)Math.Round(v * DeviceDpi / 96f);

    protected override void OnHandleCreated(EventArgs e)
    {
        base.OnHandleCreated(e);
        Chart.ChartTitleBar(Handle);
    }

    // DeviceDpi is only right once the window exists, so size the window again here and on monitor changes.
    protected override void OnLoad(EventArgs e)
    {
        base.OnLoad(e);
        Render();
        CenterToScreen();
    }

    protected override void OnDpiChanged(DpiChangedEventArgs e)
    {
        base.OnDpiChanged(e);
        Render();
    }

    protected override void OnFormClosing(FormClosingEventArgs e)
    {
        if (e.CloseReason == CloseReason.UserClosing)
        {
            e.Cancel = true;   // closing the window keeps Ferry running in the tray
            Hide();
        }
        base.OnFormClosing(e);
    }

    protected override void OnKeyDown(KeyEventArgs e)
    {
        if (e.KeyCode == Keys.Escape)
        {
            if (CurrentView == View.Settings) ShowLogbook();   // back from Settings first, like its Done link
            else Hide();
        }
        base.OnKeyDown(e);
    }

    public void HidePairing()
    {
        pairingShown = false;
        Render();
    }

    /// <summary>Back to the logbook, for example when a phone pairs or a file starts sending.</summary>
    public void ShowLogbook()
    {
        pairingShown = settingsShown = false;
        Render();
    }

    public void ShowSettings()
    {
        settingsShown = true;
        pairingShown = false;
        Render();
    }

    void ConfirmReset()
    {
        var answer = MessageBox.Show(this,
            "Ferry makes a new pairing code and unpairs your phone. To pair again, scan the new code with the phone.\n\nReset pairing?",
            "Reset pairing", MessageBoxButtons.YesNo, MessageBoxIcon.Warning, MessageBoxDefaultButton.Button2);
        if (answer == DialogResult.Yes) app.ResetPairing();
    }

    public void Render()
    {
        var st = app.Settings;
        var view = CurrentView;

        Text = "Ferry · " + app.StatusLabel;
        var c = app.LastCrossing;
        AccessibleDescription = c is null ? "No crossings yet." : $"Last crossing {c.Direction} at {c.Time:t}: {TrayApp.Preview(c.Text)}";

        code.Visible = copy.Visible = view == View.Pairing;
        done.Visible = view == View.Pairing && (st.Peer != "" || settingsShown);
        foreach (Control ctl in new Control[] { clear, sendFile, photos })
            ctl.Visible = view == View.Logbook;
        foreach (Control ctl in new Control[] { settingsDone, themeSystem, themeLight, themeDark, pause, popups, images, autostart, helm, showCode, checkUpdate })
            ctl.Visible = view == View.Settings;
        reset.Visible = view != View.Logbook;
        reset.Location = view == View.Pairing ? new Point(S(276), S(516)) : new Point(S(22), S(530));
        settings.Visible = view != View.Settings;   // Settings has its own Done link and no chart band
        if (IsHandleCreated) Chart.ChartTitleBar(Handle, view == View.Settings ? Chart.Ground : null);

        code.Text = Crypto.Grouped(st.Code);
        themeSystem.On = st.Theme == "system";
        themeLight.On = st.Theme == "light";
        themeDark.On = st.Theme == "dark";
        pause.On = st.Paused;
        popups.On = st.Popups;
        images.On = st.Images;
        autostart.On = app.StartWithWindows;
        helm.On = app.HelmOn;
        if (app.Update is { } u && checkUpdate.Enabled) checkUpdate.Text = $"Update to Ferry {u.Version}";

        // The text under the chart shows the newest crossing; the logbook holds the ones before it.
        earlier = st.History.Skip(1).ToList();
        rows = WithHeadings(earlier);
        history.BeginUpdate();
        history.Items.Clear();
        foreach (var row in rows)   // text for screen readers
            history.Items.Add(row is Crossing item ? item.Direction + ": " + TrayApp.Preview(item.Text) : ((string)row).ToLowerInvariant());
        history.EndUpdate();
        history.Visible = view == View.Logbook && earlier.Count > 0;   // empty: the painted hint under the rule shows instead
        if (hint == "Copied.") hint = "";

        ClientSize = new Size(S(420), S(view switch { View.Pairing => 560, View.Settings => 690, _ => 616 }));
        Invalidate();
    }

    protected override void OnPaint(PaintEventArgs e)
    {
        var g = e.Graphics;
        g.SmoothingMode = SmoothingMode.AntiAlias;
        g.TextRenderingHint = TextRenderingHint.ClearTypeGridFit;
        float s = DeviceDpi / 96f;
        var st = app.Settings;
        var c = app.LastCrossing;
        var view = CurrentView;
        float x = 22 * s, w = ClientSize.Width - 44 * s;

        if (view == View.Settings)
        {
            // A page of its own: no chart band, the same order of sections as on the phone.
            TextRenderer.DrawText(g, "Settings", pageTitle, new Point((int)x, (int)(14 * s)), Chart.Ink);
            Chart.DrawTracked(g, "APPEARANCE", kicker, Chart.InkMuted, x, 66 * s, 1.2f * s);
            using (var frame = Chart.RoundRect(new RectangleF(x, 84 * s, w, 38 * s), 19 * s))
            using (var pen = new Pen(Chart.ChipOff, s)) g.DrawPath(pen, frame);
            TextRenderer.DrawText(g, st.Theme == "system" ? $"Follows the light or dark mode of Windows. Now: {(Chart.Dark ? "dark" : "light")}."
                : $"Always {st.Theme}, whatever Windows uses.", small, new Point((int)x, (int)(130 * s)), Chart.InkMuted);
            Chart.DrawTracked(g, "SYNCING", kicker, Chart.InkMuted, x, 166 * s, 1.2f * s);
            Chart.DrawTracked(g, "PAIRING", kicker, Chart.InkMuted, x, 452 * s, 1.2f * s);
            TextRenderer.DrawText(g, st.Peer == "" ? "Not paired yet." : $"Paired with {st.Peer}.", body, new Point((int)x, (int)(470 * s)), Chart.Ink);
            Chart.DrawTracked(g, "ABOUT", kicker, Chart.InkMuted, x, 586 * s, 1.2f * s);
            TextRenderer.DrawText(g, $"Ferry {Updater.Current}", body, new Point((int)x, (int)(604 * s)), Chart.Ink);
            if (aboutHint != "") TextRenderer.DrawText(g, aboutHint, small, new Point((int)x, (int)(660 * s)), Chart.InkMuted);
            return;
        }

        // The chart band.
        string phone = st.Peer == "" ? "Your phone" : st.Peer;
        bool toPhone = c is null || c.Direction.EndsWith("Phone");
        Chart.DrawChart(g, new RectangleF(0, 0, ClientSize.Width, 150 * s), s, "This laptop", phone, c is null ? "" : $"course {c.Time:t}", toPhone);
        Chart.DrawTracked(g, "FERRY", wordmark, Chart.Ink, 18 * s, 12 * s, 3.5f * s);
        DrawStatusPill(g, s);

        if (view == View.Pairing)
        {
            TextRenderer.DrawText(g, st.Peer != "" ? "Pair a phone again" : "Pair your phone", title, new Point((int)x, (int)(164 * s)), Chart.Ink);
            TextRenderer.DrawText(g, "Open Ferry on your phone and tap Scan QR code. Then point the phone at this code.", body,
                new Rectangle((int)x, (int)(192 * s), (int)w, (int)(40 * s)), Chart.InkMuted, TextFormatFlags.WordBreak);
            TextRenderer.DrawText(g, "Can't scan? Type this code in Ferry:", small, new Point((int)x, (int)(462 * s)), Chart.InkMuted);
            DrawQr(g, s);
            return;
        }

        // A file on its way takes the place of the last crossing: its name, then the course filling up as the file goes.
        if (app.Transfer is { } f)
        {
            Chart.DrawTracked(g, (f.ToPhone ? "SENDING TO " : "RECEIVING FROM ") + phone.ToUpperInvariant(), kicker, Chart.InkMuted, x, 166 * s, 1.2f * s);
            TextRenderer.DrawText(g, f.Name, crossing, new Rectangle((int)x, (int)(184 * s), (int)w, (int)(24 * s)), Chart.Ink,
                TextFormatFlags.SingleLine | TextFormatFlags.EndEllipsis);
            var track = new RectangleF(x, 214 * s, w, 6 * s);
            using (var path = Chart.RoundRect(track, 3 * s))
            using (var fill = new SolidBrush(Chart.Rule)) g.FillPath(fill, path);
            if (f.Done > 0)
                using (var path = Chart.RoundRect(track with { Width = Math.Max(6 * s, w * (float)f.Done) }, 3 * s))
                using (var fill = new SolidBrush(Chart.Course)) g.FillPath(fill, path);
            TextRenderer.DrawText(g, f.Bytes < 0 ? $"{(int)(f.Done * 100)}%" : $"{(int)(f.Done * 100)}% of {f.Bytes / 1048576.0:0.0} MB", small, new Point((int)x, (int)(224 * s)), Chart.InkMuted);
        }
        else
        {
            // The last crossing.
            string kick = c is null ? "NO CROSSINGS YET"
                : "LAST CROSSING · " + (toPhone ? "LAPTOP TO PHONE" : "PHONE TO LAPTOP") + (c.Time.Date == DateTime.Today ? "" : " · " + c.Time.ToString("d MMM").ToUpperInvariant());
            Chart.DrawTracked(g, kick, kicker, Chart.InkMuted, x, 166 * s, 1.2f * s);
            string text = c is null ? "Copy something here, or tap Send on your phone."
                : c.FilePath is not null ? (toPhone ? $"{c.Text}, now in Downloads/Ferry on the phone." : $"{c.Text}, now in Downloads\\Ferry.")
                : c.ImagePath is not null ? (c.ImagePath == "" ? "An image, now in the phone's clipboard." : "An image, now in your clipboard and Pictures\\Ferry.")
                : $"“{TrayApp.Preview(c.Text)}”";
            TextRenderer.DrawText(g, text, crossing, new Rectangle((int)x, (int)(186 * s), (int)w, (int)(46 * s)), Chart.Ink,
                TextFormatFlags.WordBreak | TextFormatFlags.EndEllipsis);
        }

        // The logbook header and rule; the list itself is the ListBox.
        TextRenderer.DrawText(g, hint == "" ? "Logbook · double-click to copy again" : hint, hint == "" ? italic : body,
            new Point((int)x, (int)(246 * s)), hint == "" ? Chart.InkMuted : Chart.Course);
        using (var rule = new Pen(Chart.Rule, s)) g.DrawLine(rule, x, 270 * s, x + w, 270 * s);
        if (earlier.Count == 0)
            TextRenderer.DrawText(g, "Crossings show up here, newest first. To send a file to the phone, drop it on this window.", italic,
                new Rectangle((int)x, (int)(286 * s), (int)w, (int)(44 * s)), Chart.InkMuted, TextFormatFlags.WordBreak);
    }

    void DrawStatusPill(Graphics g, float s)
    {
        string label = app.StatusLabel switch { "Connected" => "Steady link", "Offline" => "Adrift", var other => other };
        var size = TextRenderer.MeasureText(g, label, pillFont);
        // Right of it, the settings button (30 px at x 372, 18 px from the edge).
        float h = 26 * s, w = size.Width + 34 * s, x = ClientSize.Width - 56 * s - w, y = 14 * s;
        using (var path = Chart.RoundRect(new RectangleF(x, y, w, h), h / 2))
        using (var fill = new SolidBrush(Chart.Pill)) g.FillPath(fill, path);
        using (var lamp = new SolidBrush(app.LampColor)) g.FillEllipse(lamp, x + 12 * s, y + h / 2 - 4 * s, 8 * s, 8 * s);
        TextRenderer.DrawText(g, label, pillFont, new Rectangle((int)(x + 26 * s), (int)y, size.Width + 4, (int)h), Chart.Ink,
            TextFormatFlags.VerticalCenter | TextFormatFlags.Left | TextFormatFlags.SingleLine);
    }

    void DrawQr(Graphics g, float s)
    {
        string link = app.PairingLink;
        if (qrFor != link)
        {
            using var generator = new QRCodeGenerator();
            using var data = generator.CreateQrCode(link, QRCodeGenerator.ECCLevel.M);
            qr = data.ModuleMatrix;   // includes the white quiet zone scanners need
            qrFor = link;
        }
        // Always a white plate with near-black modules, also at night, so every phone camera reads it.
        var plate = new RectangleF((ClientSize.Width - 210 * s) / 2, 240 * s, 210 * s, 210 * s);
        using (var path = Chart.RoundRect(plate, 12 * s))
        using (var white = new SolidBrush(Color.White)) g.FillPath(white, path);
        int n = qr!.Count;
        float m = (float)Math.Floor(plate.Width / n), x0 = plate.X + (plate.Width - m * n) / 2, y0 = plate.Y + (plate.Height - m * n) / 2;
        g.SmoothingMode = SmoothingMode.None;   // crisp module edges
        using var ink = new SolidBrush(Color.FromArgb(15, 42, 68));
        for (int y = 0; y < n; y++)
            for (int x = 0; x < n; x++)
                if (qr[y][x]) g.FillRectangle(ink, x0 + x * m, y0 + y * m, m, m);
        g.SmoothingMode = SmoothingMode.AntiAlias;
    }
}
