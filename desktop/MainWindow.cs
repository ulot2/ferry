using System.Collections;
using System.Drawing.Drawing2D;
using System.Drawing.Text;
using QRCoder;

namespace Ferry;

/*
THESIS: The laptop side of the same terminal. Status and the last crossing first, then the one job this window exists for: pairing by QR.
OWN-WORLD: Harbor. Navy title bar and header as one field, signal-yellow wordmark, ship-light status lamp, the perforated crossing ticket with a yellow stub, a white QR plate with navy modules.
STORY: First run: scan the code, see "Phone paired". Later: glance at the ticket to trust the link, pause or reset when needed.
FIRST VIEWPORT: Header with wordmark left and lamp pill right; the ticket overlaps its lower edge; below it the QR plate (unpaired) or paired details.
FORM: Owner-pinned "Harbor" direction, single-column terminal stack, same as the phone.
*/

/// <summary>The Ferry window: header, crossing ticket, and pairing or paired details. See DESIGN.md.</summary>
sealed class MainWindow : Form
{
    readonly TrayApp app;
    readonly Label headerLine, title, body, codeLabel;
    readonly TextBox code;
    readonly PillButton copy, done, showCode, reset;
    readonly CheckBox pause, autostart;
    readonly Font kicker = new("Segoe UI Semibold", 7.5f), route = new("Segoe UI Semibold", 13f), small = new("Segoe UI", 9f),
        stubTime = new("Segoe UI Semibold", 17f), wordmark = new("Segoe UI Semibold", 10.5f), pill = new("Segoe UI Semibold", 8.25f);
    bool pairingShown;
    List<BitArray>? qr;
    string? qrFor;

    public MainWindow(TrayApp app)
    {
        this.app = app;
        SuspendLayout();
        AutoScaleDimensions = new SizeF(96F, 96F);
        AutoScaleMode = AutoScaleMode.Dpi;
        ClientSize = new Size(380, 628);
        FormBorderStyle = FormBorderStyle.FixedSingle;
        MaximizeBox = false;
        StartPosition = FormStartPosition.CenterScreen;
        BackColor = Harbor.Ground;
        Font = new Font("Segoe UI", 9.75f);
        Icon = Harbor.AppIcon(32, null);
        DoubleBuffered = true;
        KeyPreview = true;

        headerLine = AddLabel(24, 58, 332, 40, Harbor.OnNavyMuted, Harbor.Navy, Font);
        title = AddLabel(24, 236, 332, 26, Harbor.Ink, Harbor.Ground, new Font("Segoe UI Semibold", 12f));
        body = AddLabel(24, 264, 332, 40, Harbor.InkMuted, Harbor.Ground, Font);

        // Pairing view (the QR plate itself is painted).
        codeLabel = AddLabel(24, 540, 220, 18, Harbor.InkMuted, Harbor.Ground, small);
        codeLabel.Text = "Can't scan? Type this code on the phone:";
        code = new TextBox
        {
            Bounds = new Rectangle(24, 560, 236, 22),
            ReadOnly = true,
            BorderStyle = BorderStyle.None,
            BackColor = Harbor.Ground,
            ForeColor = Harbor.Ink,
            Font = new Font("Consolas", 10f),   // the code is data: a fixed-width face keeps look-alike letters apart
        };
        Controls.Add(code);
        copy = AddButton("Copy code", PillKind.Secondary, 268, 550, 88, 36);
        copy.Click += (_, _) => app.CopyQuietly(app.Settings.Topic);
        done = AddButton("Done", PillKind.Secondary, 268, 590, 88, 32);
        done.Click += (_, _) => HidePairing();

        // Paired view.
        pause = AddCheck("Pause syncing", 320);
        pause.CheckedChanged += (_, _) => app.Paused = pause.Checked;
        autostart = AddCheck("Start with Windows", 350);
        autostart.CheckedChanged += (_, _) => app.StartWithWindows = autostart.Checked;
        showCode = AddButton("Show pairing code", PillKind.Secondary, 24, 392, 168, 40);
        showCode.Click += (_, _) => { pairingShown = true; Render(); };

        reset = AddButton("Reset pairing", PillKind.Quiet, 16, 590, 124, 32);
        reset.Click += (_, _) => ConfirmReset();

        ResumeLayout(false);
        Render();
    }

    Label AddLabel(int x, int y, int w, int h, Color fore, Color back, Font font)
    {
        var label = new Label { Bounds = new Rectangle(x, y, w, h), ForeColor = fore, BackColor = back, Font = font };
        Controls.Add(label);
        return label;
    }

    PillButton AddButton(string text, PillKind kind, int x, int y, int w, int h)
    {
        var button = new PillButton(text, kind) { Bounds = new Rectangle(x, y, w, h) };
        Controls.Add(button);
        return button;
    }

    CheckBox AddCheck(string text, int y)
    {
        var box = new CheckBox { Text = text, Bounds = new Rectangle(24, y, 332, 26), ForeColor = Harbor.Ink, BackColor = Harbor.Ground };
        Controls.Add(box);
        return box;
    }

    int S(int v) => (int)Math.Round(v * DeviceDpi / 96f);

    protected override void OnHandleCreated(EventArgs e)
    {
        base.OnHandleCreated(e);
        Harbor.NavyTitleBar(Handle);
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
        headerLine.Text = paired
            ? $"Copies on this laptop land on {st.Peer}. Copies sent from the phone land here."
            : "Ferry moves your clipboard between this laptop and your phone.";
        title.Text = pairing ? (paired ? "Pair a phone again" : "Pair your phone") : $"Paired with {st.Peer}";
        body.Text = pairing
            ? "Open Ferry on your phone and tap Scan QR code. Then point the phone at this code."
            : "Copies cross to your phone by themselves. On the phone, tap Send to cross the other way.";

        codeLabel.Visible = code.Visible = copy.Visible = pairing;
        done.Visible = pairing && paired;
        pause.Visible = autostart.Visible = showCode.Visible = !pairing;
        code.Text = st.Topic;
        pause.Checked = st.Paused;
        autostart.Checked = app.StartWithWindows;

        ClientSize = new Size(S(380), S(pairing ? 628 : 480));
        reset.Top = S(pairing ? 590 : 436);
        Invalidate();
    }

    protected override void OnPaint(PaintEventArgs e)
    {
        var g = e.Graphics;
        g.SmoothingMode = SmoothingMode.AntiAlias;
        g.TextRenderingHint = TextRenderingHint.ClearTypeGridFit;
        float s = DeviceDpi / 96f;

        using (var navy = new SolidBrush(Harbor.Navy)) g.FillRectangle(navy, 0, 0, ClientSize.Width, 150 * s);
        Harbor.DrawWordmark(g, wordmark, 24 * s, 22 * s, 3.5f * s);
        DrawStatusPill(g, s);
        DrawTicket(g, s);
        if (code.Visible) DrawQr(g, s);
    }

    void DrawStatusPill(Graphics g, float s)
    {
        string label = app.StatusLabel;
        var size = TextRenderer.MeasureText(g, label, pill);
        float h = 26 * s, w = size.Width + 34 * s, x = ClientSize.Width - 24 * s - w, y = 18 * s;
        using (var path = Harbor.RoundRect(new RectangleF(x, y, w, h), h / 2))
        using (var fill = new SolidBrush(Color.FromArgb(31, 255, 255, 255))) g.FillPath(fill, path);
        using (var lamp = new SolidBrush(app.LampColor)) g.FillEllipse(lamp, x + 12 * s, y + h / 2 - 4 * s, 8 * s, 8 * s);
        TextRenderer.DrawText(g, label, pill, new Rectangle((int)(x + 26 * s), (int)y, size.Width + 4, (int)h), Harbor.OnNavy,
            TextFormatFlags.VerticalCenter | TextFormatFlags.Left | TextFormatFlags.SingleLine);
    }

    void DrawTicket(Graphics g, float s)
    {
        var r = new RectangleF(24 * s, 104 * s, 332 * s, 112 * s);
        float stubX = r.Right - 92 * s, notch = 10 * s;

        using (var path = Harbor.RoundRect(r, 16 * s))
        {
            using (var surface = new SolidBrush(Harbor.Surface)) g.FillPath(surface, path);
            var saved = g.Save();
            g.SetClip(path, CombineMode.Intersect);
            using (var signal = new SolidBrush(Harbor.Signal)) g.FillRectangle(signal, stubX, r.Top, r.Right - stubX, r.Height);
            g.Restore(saved);
        }
        // Perforation: notches show what is behind the ticket (navy above, ground below), then a dashed tear line.
        using (var navy = new SolidBrush(Harbor.Navy)) g.FillEllipse(navy, stubX - notch, r.Top - notch, 2 * notch, 2 * notch);
        using (var ground = new SolidBrush(Harbor.Ground)) g.FillEllipse(ground, stubX - notch, r.Bottom - notch, 2 * notch, 2 * notch);
        using (var tear = new Pen(Harbor.Ground, 2 * s) { DashPattern = [2f, 2.5f] })
            g.DrawLine(tear, stubX, r.Top + notch + 4 * s, stubX, r.Bottom - notch - 4 * s);

        bool paired = app.Settings.Peer != "";
        var c = app.LastCrossing;
        string routeText = c?.Direction ?? (paired ? "No crossings yet" : "Not paired yet");
        string previewText = c is null
            ? (paired ? "Copy something here, or tap Send on your phone." : "Pair your phone to start.")
            : $"“{c.Preview}”";
        float left = r.Left + 20 * s, textWidth = stubX - left - 16 * s;

        TextRenderer.DrawText(g, "LAST CROSSING", kicker, new Point((int)left, (int)(r.Top + 18 * s)), Harbor.InkMuted);
        TextRenderer.DrawText(g, routeText, route, new Rectangle((int)left, (int)(r.Top + 34 * s), (int)textWidth, (int)(28 * s)), Harbor.Ink,
            TextFormatFlags.Left | TextFormatFlags.SingleLine | TextFormatFlags.EndEllipsis);
        TextRenderer.DrawText(g, previewText, small, new Rectangle((int)left, (int)(r.Top + 64 * s), (int)textWidth, (int)(36 * s)), Harbor.InkMuted,
            TextFormatFlags.Left | TextFormatFlags.WordBreak | TextFormatFlags.EndEllipsis);

        var stub = new Rectangle((int)stubX, (int)r.Top, (int)(r.Right - stubX), (int)r.Height);
        string time = c?.Time.ToString("t") ?? "—";
        TextRenderer.DrawText(g, time, stubTime, new Rectangle(stub.X, stub.Y + (int)(30 * s), stub.Width, (int)(34 * s)), Harbor.OnSignal,
            TextFormatFlags.HorizontalCenter | TextFormatFlags.SingleLine);
        if (c is not null)
            TextRenderer.DrawText(g, c.Time.Date == DateTime.Today ? "TODAY" : c.Time.ToString("d MMM").ToUpperInvariant(), kicker,
                new Rectangle(stub.X, stub.Y + (int)(66 * s), stub.Width, (int)(16 * s)), Harbor.OnSignal, TextFormatFlags.HorizontalCenter);
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
        // Always a white plate with navy modules, also in dark mode, so every phone camera reads it.
        var plate = new RectangleF(80 * s, 312 * s, 220 * s, 220 * s);
        using (var path = Harbor.RoundRect(plate, 12 * s))
        using (var white = new SolidBrush(Color.White)) g.FillPath(white, path);
        int n = qr!.Count;
        float m = (float)Math.Floor(plate.Width / n), x0 = plate.X + (plate.Width - m * n) / 2, y0 = plate.Y + (plate.Height - m * n) / 2;
        g.SmoothingMode = SmoothingMode.None;   // crisp module edges
        using var navy = new SolidBrush(Harbor.Navy);
        for (int y = 0; y < n; y++)
            for (int x = 0; x < n; x++)
                if (qr[y][x]) g.FillRectangle(navy, x0 + x * m, y0 + y * m, m, m);
        g.SmoothingMode = SmoothingMode.AntiAlias;
    }
}
