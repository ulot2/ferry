using System.Collections;
using System.Drawing.Drawing2D;
using System.Drawing.Text;
using QRCoder;

namespace Ferry;

/*
THESIS: The laptop side of the same sea chart: two harbors and the course between them, then the logbook. Pairing by QR is the one job this window has before a phone is paired.
OWN-WORLD: Nautical chart. Day: blue shallows, buff land, ink-navy harbors; night: a soft charcoal chart with grey contours. Magenta is the course and the one action. Barlow, italic for places. Title bar painted in the water color.
STORY: First run: scan the code, see "Phone paired". Later: glance at the chart and the last crossing, double-click a logbook entry to copy it again, flip a chip.
FIRST VIEWPORT: Chart band (wordmark left, status pill right, harbors and course); under it the last crossing; then the logbook or the QR plate.
FORM: Owner-chosen "Nautical chart" from the four-direction canvas, same as the phone, with a soft charcoal night mode.
*/

/// <summary>The Ferry window: the chart band, the last crossing, and the logbook or the pairing code. See DESIGN.md.</summary>
sealed class MainWindow : Form
{
    readonly TrayApp app;
    readonly TextBox code;
    readonly ListBox history;
    readonly PillButton copy, done, showCode, reset, clear, pause, popups, images, autostart;
    List<Crossing> earlier = [];
    readonly Font wordmark = Chart.Font("Barlow Condensed SemiBold", 15f), pillFont = Chart.Font("Barlow Medium", 9.5f),
        kicker = Chart.Font("Barlow Medium", 8f), crossing = Chart.Font("Barlow Medium", 12.5f), body = Chart.Font("Barlow", 10f),
        bold = Chart.Font("Barlow", 10f, FontStyle.Bold), italic = Chart.Font("Barlow", 10f, FontStyle.Italic),
        title = Chart.Font("Barlow Medium", 13f), small = Chart.Font("Barlow", 9f);
    bool pairingShown;
    string hint = "";
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

        // Paired view: the logbook, the switches as chips, and pairing links.
        clear = AddButton("Clear", PillKind.Link, 338, 240, 64, 28);
        clear.Click += (_, _) => app.ClearHistory();
        history = new ListBox
        {
            Bounds = new Rectangle(22, 272, 376, 200),
            BorderStyle = BorderStyle.None,
            BackColor = Chart.Ground,
            ForeColor = Chart.Ink,
            DrawMode = DrawMode.OwnerDrawFixed,
            IntegralHeight = false,
            AccessibleName = "Logbook",
        };
        history.DrawItem += DrawHistoryItem;
        history.DoubleClick += (_, _) => CopySelected();
        history.KeyDown += (_, e) => { if (e.KeyCode == Keys.Enter) CopySelected(); };
        Controls.Add(history);

        pause = AddButton("Pause", PillKind.Chip, 22, 486, 74, 36);
        pause.Click += (_, _) => app.Paused = !app.Paused;
        popups = AddButton("Pop-ups", PillKind.Chip, 104, 486, 86, 36);
        popups.Click += (_, _) => app.Popups = !app.Popups;
        images = AddButton("Images", PillKind.Chip, 198, 486, 82, 36);
        images.Click += (_, _) => app.Images = !app.Images;
        autostart = AddButton("Start with Windows", PillKind.Chip, 22, 530, 156, 36);
        autostart.Click += (_, _) => { app.StartWithWindows = !app.StartWithWindows; Render(); };
        showCode = AddButton("Show pairing code", PillKind.Link, 12, 574, 160, 32);
        showCode.Click += (_, _) => { pairingShown = true; Render(); };

        reset = AddButton("Reset pairing", PillKind.Link, 296, 574, 112, 32);
        reset.Click += (_, _) => ConfirmReset();

        ResumeLayout(false);
        Render();
    }

    PillButton AddButton(string text, PillKind kind, int x, int y, int w, int h)
    {
        var button = new PillButton(text, kind) { Bounds = new Rectangle(x, y, w, h) };
        Controls.Add(button);
        return button;
    }

    void CopySelected()
    {
        if (history.SelectedIndex < 0 || history.SelectedIndex >= earlier.Count) return;
        var item = earlier[history.SelectedIndex];
        try
        {
            if (item.ImagePath is null) app.CopyQuietly(item.Text);
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

    void DrawHistoryItem(object? sender, DrawItemEventArgs e)
    {
        if (e.Index < 0 || e.Index >= earlier.Count) return;
        var item = earlier[e.Index];
        bool selected = (e.State & DrawItemState.Selected) != 0;
        var g = e.Graphics;
        var b = e.Bounds;
        float s = DeviceDpi / 96f;
        using (var back = new SolidBrush(selected ? Chart.Hover : Chart.Ground)) g.FillRectangle(back, b);
        using (var rule = new Pen(Chart.RuleRow, 1)) g.DrawLine(rule, b.Left, b.Bottom - 1, b.Right, b.Bottom - 1);

        string when = item.Time.Date == DateTime.Today ? item.Time.ToString("t") : item.Time.ToString("d MMM");
        bool toPhone = item.Direction.EndsWith("Phone");
        string line = item.ImagePath is null ? TrayApp.Preview(item.Text) : item.ImagePath == "" ? "Image" : "Image  ·  " + Path.GetFileName(item.ImagePath);
        var flags = TextFormatFlags.Left | TextFormatFlags.VerticalCenter | TextFormatFlags.SingleLine;
        TextRenderer.DrawText(g, when, bold, new Rectangle(b.X + (int)(4 * s), b.Y, (int)(66 * s), b.Height), Chart.Ink, flags);
        TextRenderer.DrawText(g, toPhone ? "→" : "←", bold, new Rectangle(b.X + (int)(72 * s), b.Y, (int)(24 * s), b.Height), Chart.Course, flags);
        TextRenderer.DrawText(g, line, body, new Rectangle(b.X + (int)(98 * s), b.Y, b.Width - (int)(102 * s), b.Height), Chart.Ink,
            flags | TextFormatFlags.EndEllipsis);
        e.DrawFocusRectangle();
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
        if (e.KeyCode == Keys.Escape) Hide();
        base.OnKeyDown(e);
    }

    public void HidePairing()
    {
        pairingShown = false;
        Render();
    }

    void ConfirmReset()
    {
        var answer = MessageBox.Show(this,
            "Ferry makes a new pairing code. Your phone stops syncing until you scan the new code.\n\nReset pairing?",
            "Reset pairing", MessageBoxButtons.YesNo, MessageBoxIcon.Warning, MessageBoxDefaultButton.Button2);
        if (answer == DialogResult.Yes) app.ResetPairing();
    }

    public void Render()
    {
        var st = app.Settings;
        bool paired = st.Peer != "";
        bool pairing = pairingShown || !paired;

        Text = "Ferry · " + app.StatusLabel;
        var c = app.LastCrossing;
        AccessibleDescription = c is null ? "No crossings yet." : $"Last crossing {c.Direction} at {c.Time:t}: {TrayApp.Preview(c.Text)}";

        code.Visible = copy.Visible = pairing;
        done.Visible = pairing && paired;
        foreach (Control ctl in new Control[] { clear, history, pause, popups, images, autostart, showCode })
            ctl.Visible = !pairing;
        code.Text = Crypto.Grouped(st.Code);
        pause.On = st.Paused;
        popups.On = st.Popups;
        images.On = st.Images;
        autostart.On = app.StartWithWindows;

        // The text under the chart shows the newest crossing; the logbook holds the ones before it.
        earlier = st.History.Skip(1).ToList();
        history.ItemHeight = S(40);
        history.BeginUpdate();
        history.Items.Clear();
        foreach (var item in earlier) history.Items.Add(item.Direction + ": " + TrayApp.Preview(item.Text));   // text for screen readers
        history.EndUpdate();
        history.Visible = !pairing && earlier.Count > 0;   // empty: the painted hint under the rule shows instead
        if (hint == "Copied.") hint = "";

        ClientSize = new Size(S(420), S(pairing ? 560 : 616));
        reset.Location = pairing ? new Point(S(296), S(518)) : new Point(S(296), S(574));
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
        bool pairing = code.Visible;

        // The chart band.
        string phone = st.Peer == "" ? "Your phone" : st.Peer;
        bool toPhone = c is null || c.Direction.EndsWith("Phone");
        Chart.DrawChart(g, new RectangleF(0, 0, ClientSize.Width, 150 * s), s, "This laptop", phone, c is null ? "" : $"course {c.Time:t}", toPhone);
        Chart.DrawTracked(g, "FERRY", wordmark, Chart.Ink, 18 * s, 12 * s, 3.5f * s);
        DrawStatusPill(g, s);

        float x = 22 * s, w = ClientSize.Width - 44 * s;
        if (pairing)
        {
            TextRenderer.DrawText(g, st.Peer != "" ? "Pair a phone again" : "Pair your phone", title, new Point((int)x, (int)(164 * s)), Chart.Ink);
            TextRenderer.DrawText(g, "Open Ferry on your phone and tap Scan QR code. Then point the phone at this code.", body,
                new Rectangle((int)x, (int)(192 * s), (int)w, (int)(40 * s)), Chart.InkMuted, TextFormatFlags.WordBreak);
            TextRenderer.DrawText(g, "Can't scan? Type this code in Ferry:", small, new Point((int)x, (int)(462 * s)), Chart.InkMuted);
            DrawQr(g, s);
            return;
        }

        // The last crossing.
        string kick = c is null ? (st.Peer == "" ? "NOT PAIRED YET" : "NO CROSSINGS YET")
            : "LAST CROSSING · " + (toPhone ? "LAPTOP TO PHONE" : "PHONE TO LAPTOP") + (c.Time.Date == DateTime.Today ? "" : " · " + c.Time.ToString("d MMM").ToUpperInvariant());
        Chart.DrawTracked(g, kick, kicker, Chart.InkMuted, x, 166 * s, 1.2f * s);
        string text = c is null ? "Copy something here, or tap Send on your phone."
            : c.ImagePath is not null ? (c.ImagePath == "" ? "An image, now in the phone's clipboard." : "An image, now in your clipboard and Pictures\\Ferry.")
            : $"“{TrayApp.Preview(c.Text)}”";
        TextRenderer.DrawText(g, text, crossing, new Rectangle((int)x, (int)(186 * s), (int)w, (int)(46 * s)), Chart.Ink,
            TextFormatFlags.WordBreak | TextFormatFlags.EndEllipsis);

        // The logbook header and rule; the list itself is the ListBox.
        TextRenderer.DrawText(g, hint == "" ? "Logbook · double-click to copy again" : hint, hint == "" ? italic : body,
            new Point((int)x, (int)(246 * s)), hint == "" ? Chart.InkMuted : Chart.Course);
        using (var rule = new Pen(Chart.Rule, s)) g.DrawLine(rule, x, 270 * s, x + w, 270 * s);
        if (earlier.Count == 0)
            TextRenderer.DrawText(g, "Crossings show up here, newest first.", italic, new Point((int)x, (int)(286 * s)), Chart.InkMuted);
    }

    void DrawStatusPill(Graphics g, float s)
    {
        string label = app.StatusLabel switch { "Connected" => "Steady link", "Offline" => "Adrift", var other => other };
        var size = TextRenderer.MeasureText(g, label, pillFont);
        float h = 26 * s, w = size.Width + 34 * s, x = ClientSize.Width - 18 * s - w, y = 14 * s;
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
