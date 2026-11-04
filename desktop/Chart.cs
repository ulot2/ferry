using System.Drawing.Drawing2D;
using System.Drawing.Text;
using System.Reflection;
using System.Runtime.InteropServices;
using Microsoft.Win32;

namespace Ferry;

/// <summary>The Nautical chart palette (day, and a soft charcoal night chart), the Barlow font, and drawing helpers. See DESIGN.md.</summary>
static class Chart
{
    /// <summary>True for the night chart. Set by Apply; the colors below follow it.</summary>
    public static bool Dark { get; private set; } = SystemDark();

    /// <summary>The theme from Settings: "light", "dark", or "system" (follow the Windows app mode). Open windows must be rebuilt.</summary>
    public static void Apply(string theme) => Dark = theme switch { "dark" => true, "light" => false, _ => SystemDark() };

    static bool SystemDark() => Registry.GetValue(
        @"HKEY_CURRENT_USER\Software\Microsoft\Windows\CurrentVersion\Themes\Personalize", "AppsUseLightTheme", 1) is 0;

    public static Color Water => Dark ? Hex(0x1C2127) : Hex(0xDCEBF2);
    public static Color Contour1 => Dark ? Hex(0x2C333B) : Hex(0xB9D6E4);
    public static Color Contour2 => Dark ? Hex(0x252B32) : Hex(0xC8DFEA);
    public static Color Land => Dark ? Hex(0x26251F) : Hex(0xE9E1CC);
    public static Color Ground => Dark ? Hex(0x16191D) : Hex(0xF2F6F8);
    public static Color Ink => Dark ? Hex(0xDDE2E6) : Hex(0x0F2A44);
    public static Color InkMuted => Dark ? Hex(0xA0A9B2) : Hex(0x3D5A73);
    public static Color Course => Dark ? Hex(0xE05AA0) : Hex(0xA3125F);
    public static Color Act => Dark ? Hex(0xC2297A) : Hex(0xA3125F);
    public static Color ActPressed => Dark ? Hex(0xA3125F) : Hex(0x7A0D47);
    public static Color Rule => Dark ? Hex(0x2F363E) : Hex(0xC9D9E2);
    public static Color RuleRow => Dark ? Hex(0x232930) : Hex(0xDCE6EC);
    public static Color ChipOn => Dark ? Hex(0xDDE2E6) : Hex(0x0F2A44);
    public static Color ChipOnText => Dark ? Hex(0x16191D) : Hex(0xF2F6F8);
    public static Color ChipOff => Dark ? Hex(0x47505A) : Hex(0x9DB2C2);
    public static Color Pill => Dark ? Hex(0x16191D) : Hex(0xF2F6F8);
    public static Color Steady => Dark ? Hex(0x4CC38A) : Hex(0x1E8E5A);
    public static Color Adrift => Dark ? Hex(0xF2B8B5) : Hex(0xB3261E);
    public static Color Hover => Dark ? Hex(0x222830) : Hex(0xE3ECF1);

    // The tray and window icon keep the day chart in both themes, like the phone's launcher icon.
    static readonly Color IconWater = Hex(0xDCEBF2), IconInk = Hex(0x0F2A44), IconCourse = Hex(0xA3125F);

    static Color Hex(int rgb) => Color.FromArgb(255, (rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255);

    // Barlow (SIL OFL), embedded from src/main/res/font. AddFontMemResourceEx makes it visible to GDI
    // (TextRenderer); the PrivateFontCollection gives GDI+ the font families to build Font objects from.
    static readonly PrivateFontCollection Fonts = LoadFonts();

    static PrivateFontCollection LoadFonts()
    {
        var fonts = new PrivateFontCollection();
        var asm = Assembly.GetExecutingAssembly();
        foreach (string name in asm.GetManifestResourceNames().Where(n => n.EndsWith(".ttf")))
        {
            using var stream = asm.GetManifestResourceStream(name)!;
            byte[] data = new byte[stream.Length];
            stream.ReadExactly(data);
            IntPtr mem = Marshal.AllocCoTaskMem(data.Length);   // kept for the life of the process, as GDI+ requires
            Marshal.Copy(data, 0, mem, data.Length);
            fonts.AddMemoryFont(mem, data.Length);
            uint count = 0;
            AddFontMemResourceEx(mem, (uint)data.Length, IntPtr.Zero, ref count);
        }
        return fonts;
    }

    /// <summary>A Barlow font: family "Barlow" or "Barlow Condensed SemiBold"; falls back to Segoe UI if missing.</summary>
    public static Font Font(string family, float points, FontStyle style = FontStyle.Regular)
    {
        var f = Fonts.Families.FirstOrDefault(x => x.Name == family);
        return f is not null && f.IsStyleAvailable(style) ? new Font(f, points, style) : new Font("Segoe UI", points, style);
    }

    public static GraphicsPath RoundRect(RectangleF r, float radius)
    {
        float d = Math.Min(radius * 2, Math.Min(r.Width, r.Height));
        var p = new GraphicsPath();
        p.AddArc(r.X, r.Y, d, d, 180, 90);
        p.AddArc(r.Right - d, r.Y, d, d, 270, 90);
        p.AddArc(r.Right - d, r.Bottom - d, d, d, 0, 90);
        p.AddArc(r.X, r.Bottom - d, d, d, 90, 90);
        p.CloseFigure();
        return p;
    }

    /// <summary>Text with letter-spacing, placed glyph by glyph (GDI+ has no tracking).</summary>
    public static void DrawTracked(Graphics g, string text, Font font, Color color, float x, float y, float tracking)
    {
        using var brush = new SolidBrush(color);
        foreach (char c in text)
        {
            string s = c.ToString();
            g.DrawString(s, font, brush, x, y, StringFormat.GenericTypographic);
            x += g.MeasureString(s, font, PointF.Empty, StringFormat.GenericTypographic).Width + (c == ' ' ? font.Size * 0.3f : 0) + tracking;
        }
    }

    /// <summary>
    /// The chart band: water, depth contours, land at both edges, two harbors and the dashed course between
    /// them, its arrow pointing the way the last crossing went, and italic place labels.
    /// </summary>
    public static void DrawChart(Graphics g, RectangleF r, float s, string left, string right, string course, bool towardRight)
    {
        g.SmoothingMode = SmoothingMode.AntiAlias;
        using (var water = new SolidBrush(Water)) g.FillRectangle(water, r);
        float w = r.Width, h = r.Height, x0 = r.X, y0 = r.Y;
        for (int i = 0; i < 4; i++)
        {
            float y = y0 + h * (0.2f + i * 0.2f), dy = h * 0.1f;
            using var pen = new Pen(i < 2 ? Contour1 : Contour2, 1.2f * s);
            g.DrawBezier(pen, x0 - 10 * s, y, x0 + w * 0.22f, y - dy, x0 + w * 0.42f, y + dy, x0 + w * 0.66f, y);
            g.DrawBezier(pen, x0 + w * 0.66f, y, x0 + w * 0.82f, y - dy * 0.6f, x0 + w * 0.95f, y - dy * 0.2f, x0 + w + 10 * s, y + dy * 0.4f);
        }
        using (var land = new SolidBrush(Land))
        using (var leftLand = new GraphicsPath())
        using (var rightLand = new GraphicsPath())
        {
            leftLand.AddBezier(x0 + w * 0.12f, y0, x0 + w * 0.17f, y0 + h * 0.3f, x0 + w * 0.09f, y0 + h * 0.6f, x0 + w * 0.15f, y0 + h);
            leftLand.AddLine(x0 + w * 0.15f, y0 + h, x0, y0 + h);
            leftLand.AddLine(x0, y0 + h, x0, y0);
            leftLand.CloseFigure();
            rightLand.AddBezier(x0 + w * 0.88f, y0, x0 + w * 0.83f, y0 + h * 0.35f, x0 + w * 0.91f, y0 + h * 0.6f, x0 + w * 0.86f, y0 + h);
            rightLand.AddLine(x0 + w * 0.86f, y0 + h, x0 + w, y0 + h);
            rightLand.AddLine(x0 + w, y0 + h, x0 + w, y0);
            rightLand.CloseFigure();
            g.FillPath(land, leftLand);
            g.FillPath(land, rightLand);
        }

        float hy = y0 + h * 0.66f, x1 = x0 + w * 0.16f, x2 = x0 + w * 0.84f, top = y0 + h * 0.34f;
        using (var dashed = new Pen(Course, 2.5f * s) { DashPattern = [2.8f, 2.4f], DashCap = DashCap.Round })
            g.DrawBezier(dashed, x1, hy, x0 + w * 0.36f, top, x0 + w * 0.64f, top, x2, hy);
        float mx = x0 + w * 0.5f, my = hy - (hy - top) * 0.75f, a = 6 * s, dir = towardRight ? 1 : -1;
        using (var arrow = new Pen(Course, 2.5f * s) { StartCap = LineCap.Round, EndCap = LineCap.Round, LineJoin = LineJoin.Round })
            g.DrawLines(arrow, [new PointF(mx - dir * a, my - a), new PointF(mx + dir * a * 0.4f, my), new PointF(mx - dir * a, my + a)]);
        using (var ink = new SolidBrush(Ink))
        {
            g.FillEllipse(ink, x1 - 6 * s, hy - 6 * s, 12 * s, 12 * s);
            g.FillEllipse(ink, x2 - 6 * s, hy - 6 * s, 12 * s, 12 * s);
        }

        using var label = Font("Barlow", 9.5f, FontStyle.Italic);
        TextRenderer.DrawText(g, left, label, new Point((int)(x0 + 12 * s), (int)(hy + 14 * s)), Ink, TextFormatFlags.NoPadding);
        var rightSize = TextRenderer.MeasureText(g, right, label, Size.Empty, TextFormatFlags.NoPadding);
        TextRenderer.DrawText(g, right, label, new Point((int)(x0 + w - 12 * s - rightSize.Width), (int)(hy + 14 * s)), Ink, TextFormatFlags.NoPadding);
        if (course.Length > 0)
        {
            var size = TextRenderer.MeasureText(g, course, label, Size.Empty, TextFormatFlags.NoPadding);
            TextRenderer.DrawText(g, course, label, new Point((int)(mx - size.Width / 2f), (int)(my - 12 * s - size.Height)), Course, TextFormatFlags.NoPadding);
        }
    }

    public enum Kind { Text, Link, Image, File }

    /// <summary>The logbook icon for what crossed, drawn in a square box: lines of text, two chain links, a framed hill, or a page with a folded corner.</summary>
    public static void DrawKind(Graphics g, Kind kind, RectangleF box, Color color)
    {
        float u = box.Width / 24f, x = box.X, y = box.Y;
        PointF P(float px, float py) => new(x + px * u, y + py * u);
        using var pen = new Pen(color, 1.8f * u) { StartCap = LineCap.Round, EndCap = LineCap.Round, LineJoin = LineJoin.Round };
        switch (kind)
        {
            case Kind.Text:
                g.DrawLine(pen, P(4, 7), P(20, 7));
                g.DrawLine(pen, P(4, 12), P(20, 12));
                g.DrawLine(pen, P(4, 17), P(14, 17));
                break;
            case Kind.Link:
                using (var left = RoundRect(new RectangleF(x + 2 * u, y + 8 * u, 10 * u, 8 * u), 4 * u)) g.DrawPath(pen, left);
                using (var right = RoundRect(new RectangleF(x + 12 * u, y + 8 * u, 10 * u, 8 * u), 4 * u)) g.DrawPath(pen, right);
                g.DrawLine(pen, P(8.5f, 12), P(15.5f, 12));
                break;
            case Kind.Image:
                using (var frame = RoundRect(new RectangleF(x + 3 * u, y + 5 * u, 18 * u, 14 * u), 2 * u)) g.DrawPath(pen, frame);
                g.DrawLines(pen, [P(6.5f, 16.5f), P(10, 12), P(13, 15.5f), P(15, 13.2f), P(17.5f, 16.5f)]);
                using (var sun = new SolidBrush(color)) g.FillEllipse(sun, x + 14.5f * u, y + 7.3f * u, 3 * u, 3 * u);
                break;
            case Kind.File:
                g.DrawPolygon(pen, [P(6, 3), P(13.5f, 3), P(19, 8.5f), P(19, 21), P(6, 21)]);
                g.DrawLines(pen, [P(13.5f, 3), P(13.5f, 8.5f), P(19, 8.5f)]);
                break;
        }
    }

    /// <summary>The app icon: day-chart water with two harbors and the course, plus an optional status dot.</summary>
    public static Icon AppIcon(int px, Color? lamp)
    {
        using var bmp = new Bitmap(px, px);
        using (var g = Graphics.FromImage(bmp))
        {
            g.SmoothingMode = SmoothingMode.AntiAlias;
            float k = px / 24f;
            using (var tile = RoundRect(new RectangleF(0, 0, px, px), 5 * k))
            using (var water = new SolidBrush(IconWater)) g.FillPath(water, tile);
            using (var course = new Pen(IconCourse, Math.Max(1.4f, 2.2f * k)) { StartCap = LineCap.Round, EndCap = LineCap.Round })
                g.DrawBezier(course, 5 * k, 15 * k, 8 * k, 7 * k, 16 * k, 7 * k, 19 * k, 15 * k);
            using (var ink = new SolidBrush(IconInk))
            {
                g.FillEllipse(ink, 2.5f * k, 12.5f * k, 5 * k, 5 * k);
                g.FillEllipse(ink, 16.5f * k, 12.5f * k, 5 * k, 5 * k);
            }
            if (lamp is Color c)
            {
                float r = 4.5f * k;
                using var ring = new SolidBrush(IconWater);
                using var dot = new SolidBrush(c);
                g.FillEllipse(ring, px - 2 * r - k, px - 2 * r - k, 2 * r + k, 2 * r + k);
                g.FillEllipse(dot, px - 2 * r - 0.5f * k, px - 2 * r - 0.5f * k, 2 * r, 2 * r);
            }
        }
        IntPtr handle = bmp.GetHicon();
        using var temp = Icon.FromHandle(handle);
        var icon = (Icon)temp.Clone();
        DestroyIcon(handle);
        return icon;
    }

    /// <summary>Windows 11: paint the title bar in the color under it (the chart's water, or the page in Settings) so they join. Older Windows ignores it.</summary>
    public static void ChartTitleBar(IntPtr hwnd, Color? under = null)
    {
        var back = under ?? Water;
        int caption = back.R | back.G << 8 | back.B << 16, text = Ink.R | Ink.G << 8 | Ink.B << 16;
        DwmSetWindowAttribute(hwnd, 35, ref caption, sizeof(int));   // DWMWA_CAPTION_COLOR
        DwmSetWindowAttribute(hwnd, 36, ref text, sizeof(int));      // DWMWA_TEXT_COLOR
    }

    [DllImport("user32.dll")] static extern bool DestroyIcon(IntPtr handle);
    [DllImport("dwmapi.dll")] static extern int DwmSetWindowAttribute(IntPtr hwnd, int attribute, ref int value, int size);
    [DllImport("gdi32.dll")] static extern IntPtr AddFontMemResourceEx(IntPtr font, uint length, IntPtr reserved, ref uint fonts);
}

enum PillKind { Primary, Chip, Link, Settings, Segment, Danger }

/// <summary>
/// Chart button: magenta primary pill, an on/off chip (filled when on), a link in the course color, the round settings
/// button on the chart, one choice of a segmented control (filled when chosen), or an outlined button that undoes something.
/// </summary>
sealed class PillButton : Button
{
    readonly PillKind kind;
    bool hover, down, on;

    public PillButton(string text, PillKind kind)
    {
        this.kind = kind;
        Text = text;
        Cursor = Cursors.Hand;
        FlatStyle = FlatStyle.Flat;
        BackColor = Chart.Ground;
        if (kind == PillKind.Settings)
        {
            // It sits on the chart band: a see-through background lets the form paint the chart behind it, with no box around the pill.
            SetStyle(ControlStyles.SupportsTransparentBackColor, true);
            BackColor = Color.Transparent;
        }
        Font = Chart.Font("Barlow", kind == PillKind.Primary ? 10.5f : 9.75f, kind == PillKind.Primary ? FontStyle.Bold : FontStyle.Regular);
        SetStyle(ControlStyles.UserPaint | ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer, true);
        if (kind == PillKind.Chip) AccessibleRole = AccessibleRole.CheckButton;
        if (kind == PillKind.Segment) AccessibleRole = AccessibleRole.RadioButton;
    }

    /// <summary>For chips: filled when on; screen readers hear "on" or "off". For segments: filled when chosen; they hear "selected".</summary>
    [System.ComponentModel.DesignerSerializationVisibility(System.ComponentModel.DesignerSerializationVisibility.Hidden)]   // set in code, never by the form designer
    public bool On
    {
        get => on;
        set
        {
            on = value;
            AccessibleName = kind == PillKind.Segment ? Text + (value ? ", selected" : "") : Text + (value ? ", on" : ", off");
            Invalidate();
        }
    }

    protected override void OnMouseEnter(EventArgs e) { hover = true; Invalidate(); base.OnMouseEnter(e); }
    protected override void OnMouseLeave(EventArgs e) { hover = down = false; Invalidate(); base.OnMouseLeave(e); }
    protected override void OnMouseDown(MouseEventArgs e) { down = true; Invalidate(); base.OnMouseDown(e); }
    protected override void OnMouseUp(MouseEventArgs e) { down = false; Invalidate(); base.OnMouseUp(e); }

    protected override void OnPaint(PaintEventArgs e)
    {
        var g = e.Graphics;
        if (BackColor.A == 255) g.Clear(BackColor);   // a see-through button already has the chart painted behind it
        g.SmoothingMode = SmoothingMode.AntiAlias;
        float s = DeviceDpi / 96f;
        var r = new RectangleF(s, s, Width - 2 * s, Height - 2 * s);
        using var pill = Chart.RoundRect(r, r.Height / 2);

        Color text = Chart.Ink;
        var align = TextFormatFlags.HorizontalCenter;
        var bounds = ClientRectangle;
        switch (kind)
        {
            case PillKind.Primary:
                using (var fill = new SolidBrush(down || hover ? Chart.ActPressed : Chart.Act)) g.FillPath(fill, pill);
                text = Color.White;
                break;
            case PillKind.Chip when on:
                using (var fill = new SolidBrush(Chart.ChipOn)) g.FillPath(fill, pill);
                text = Chart.ChipOnText;
                break;
            case PillKind.Chip:
                if (hover) using (var fill = new SolidBrush(Chart.Hover)) g.FillPath(fill, pill);
                using (var pen = new Pen(Chart.ChipOff, s)) g.DrawPath(pen, pill);
                break;
            case PillKind.Link:
                if (hover) using (var fill = new SolidBrush(Chart.Hover)) g.FillPath(fill, pill);
                text = Chart.Course;
                align = TextFormatFlags.Left;   // links read as text, in line with the text above
                bounds = Rectangle.FromLTRB((int)(10 * s), 0, Width, Height);
                break;
            case PillKind.Segment when on:
                using (var fill = new SolidBrush(Chart.ChipOn)) g.FillPath(fill, pill);
                text = Chart.ChipOnText;
                break;
            case PillKind.Segment:
                if (hover) using (var fill = new SolidBrush(Chart.Hover)) g.FillPath(fill, pill);
                break;
            case PillKind.Danger:
                if (hover) using (var fill = new SolidBrush(Chart.Hover)) g.FillPath(fill, pill);
                using (var pen = new Pen(Chart.Adrift, s)) g.DrawPath(pen, pill);
                text = Chart.Adrift;
                break;
            case PillKind.Settings:
                // Three sliders in ink on a round pill, the same icon as on the phone. Text is only the accessible name.
                using (var fill = new SolidBrush(hover || down ? Chart.Hover : Chart.Pill)) g.FillPath(fill, pill);
                float u = Math.Min(r.Width, r.Height) / 24f, cx = r.X + r.Width / 2, cy = r.Y + r.Height / 2;
                using (var ink = new SolidBrush(Chart.Ink))
                    foreach (var (dy, knob) in new[] { (-4.5f, 2f), (0f, -2.5f), (4.5f, 1f) })
                    {
                        g.FillRectangle(ink, cx - 6.5f * u, cy + (dy - 0.6f) * u, 13 * u, 1.2f * u);
                        g.FillEllipse(ink, cx + (knob - 1.9f) * u, cy + (dy - 1.9f) * u, 3.8f * u, 3.8f * u);
                    }
                break;
        }
        if (!Enabled) text = Chart.InkMuted;
        if (kind != PillKind.Settings)
            TextRenderer.DrawText(g, Text, Font, bounds, text, align | TextFormatFlags.VerticalCenter | TextFormatFlags.SingleLine);

        if (Focused && ShowFocusCues)
        {
            var inner = RectangleF.Inflate(r, -3 * s, -3 * s);
            using var ring = Chart.RoundRect(inner, inner.Height / 2);
            using var pen = new Pen(kind == PillKind.Primary ? Color.White : Chart.Ink, 2 * s);
            g.DrawPath(pen, ring);
        }
    }
}

/// <summary>A setting you turn on or off: the label and a short hint on the left, a switch on the right. Clicking anywhere on the row, or Space, flips it.</summary>
sealed class SwitchRow : Control
{
    readonly Font label = Chart.Font("Barlow Medium", 10.5f), small = Chart.Font("Barlow", 9f);
    readonly string hint;
    bool on, hover;

    public SwitchRow(string text, string hint)
    {
        Text = text;
        this.hint = hint;
        Cursor = Cursors.Hand;
        BackColor = Chart.Ground;
        TabStop = true;
        SetStyle(ControlStyles.UserPaint | ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer
            | ControlStyles.Selectable | ControlStyles.ResizeRedraw, true);
        AccessibleRole = AccessibleRole.CheckButton;
        AccessibleDescription = hint;
    }

    [System.ComponentModel.DesignerSerializationVisibility(System.ComponentModel.DesignerSerializationVisibility.Hidden)]   // set in code, never by the form designer
    public bool On
    {
        get => on;
        set
        {
            on = value;
            AccessibleName = Text + (value ? ", on" : ", off");
            Invalidate();
        }
    }

    protected override void OnMouseEnter(EventArgs e) { hover = true; Invalidate(); base.OnMouseEnter(e); }
    protected override void OnMouseLeave(EventArgs e) { hover = false; Invalidate(); base.OnMouseLeave(e); }
    protected override void OnGotFocus(EventArgs e) { Invalidate(); base.OnGotFocus(e); }
    protected override void OnLostFocus(EventArgs e) { Invalidate(); base.OnLostFocus(e); }

    protected override void OnKeyDown(KeyEventArgs e)
    {
        if (e.KeyCode == Keys.Space) OnClick(EventArgs.Empty);
        base.OnKeyDown(e);
    }

    protected override void OnPaint(PaintEventArgs e)
    {
        var g = e.Graphics;
        g.Clear(BackColor);
        g.SmoothingMode = SmoothingMode.AntiAlias;
        float s = DeviceDpi / 96f, w = 40 * s, h = 22 * s;
        var track = new RectangleF(Width - w - 5 * s, 6 * s, w, h);   // room on the right for the focus ring
        int textWidth = (int)(track.X - 12 * s);
        TextRenderer.DrawText(g, Text, label, new Rectangle(0, (int)(4 * s), textWidth, (int)(22 * s)), Chart.Ink,
            TextFormatFlags.Left | TextFormatFlags.SingleLine | TextFormatFlags.EndEllipsis | TextFormatFlags.NoPadding);
        TextRenderer.DrawText(g, hint, small, new Rectangle(0, (int)(27 * s), textWidth, (int)(18 * s)), Chart.InkMuted,
            TextFormatFlags.Left | TextFormatFlags.SingleLine | TextFormatFlags.EndEllipsis | TextFormatFlags.NoPadding);

        // The switch: a course-colored track with a white knob on the right when on; an outlined track with the knob on the left when off.
        using (var path = Chart.RoundRect(track, h / 2))
        {
            if (on)
                using (var fill = new SolidBrush(Chart.Course)) g.FillPath(fill, path);
            else
            {
                if (hover) using (var fill = new SolidBrush(Chart.Hover)) g.FillPath(fill, path);
                using (var pen = new Pen(Chart.ChipOff, 1.5f * s)) g.DrawPath(pen, path);
            }
        }
        float d = h - 8 * s, kx = on ? track.Right - 4 * s - d : track.X + 4 * s;
        using (var knob = new SolidBrush(on ? Color.White : Chart.ChipOff)) g.FillEllipse(knob, kx, track.Y + 4 * s, d, d);

        if (Focused && ShowFocusCues)
        {
            using var ring = Chart.RoundRect(RectangleF.Inflate(track, 3 * s, 3 * s), h / 2 + 3 * s);
            using var pen = new Pen(Chart.Ink, 1.5f * s);
            g.DrawPath(pen, ring);
        }
    }
}
