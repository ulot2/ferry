using System.Drawing.Drawing2D;
using System.Runtime.InteropServices;
using Microsoft.Win32;

namespace Ferry;

/// <summary>Harbor palette and drawing helpers, shared with the Android app. See DESIGN.md.</summary>
static class Harbor
{
    public static readonly bool Dark = Registry.GetValue(
        @"HKEY_CURRENT_USER\Software\Microsoft\Windows\CurrentVersion\Themes\Personalize", "AppsUseLightTheme", 1) is 0;

    public static readonly Color Navy = Hex(0x0F2742);
    public static readonly Color OnNavy = Hex(0xF4F7FB);
    public static readonly Color OnNavyMuted = Hex(0xA9BAD0);
    public static readonly Color Signal = Hex(0xF2C230);
    public static readonly Color SignalPressed = Hex(0xD9AC22);
    public static readonly Color OnSignal = Hex(0x0F2742);
    public static readonly Color LampOn = Hex(0x3DD68C);    // ship lights on the navy header: green = connected
    public static readonly Color LampOff = Hex(0xFF6B6F);   // red = offline

    public static readonly Color Ground = Dark ? Hex(0x08121F) : Hex(0xE9EEF4);
    public static readonly Color Surface = Dark ? Hex(0x13233A) : Hex(0xFFFFFF);
    public static readonly Color Ink = Dark ? Hex(0xE6EDF5) : Hex(0x0F2742);
    public static readonly Color InkMuted = Dark ? Hex(0x9AAABD) : Hex(0x4A5B70);
    public static readonly Color Outline = Dark ? Hex(0x2A3D55) : Hex(0xC9D3DE);
    public static readonly Color Hover = Dark ? Hex(0x1B304B) : Hex(0xDCE4EE);

    static Color Hex(int rgb) => Color.FromArgb(255, (rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255);

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

    /// <summary>"FERRY" with wide letter spacing. GDI+ has no tracking, so letters are placed one by one.</summary>
    public static void DrawWordmark(Graphics g, Font font, float x, float y, float tracking)
    {
        using var brush = new SolidBrush(Signal);
        foreach (char c in "FERRY")
        {
            string s = c.ToString();
            g.DrawString(s, font, brush, x, y, StringFormat.GenericTypographic);
            x += g.MeasureString(s, font, PointF.Empty, StringFormat.GenericTypographic).Width + tracking;
        }
    }

    // Material "swap_horiz" glyph (Apache 2.0) on a 24-unit grid, the same icon as the phone app.
    static readonly PointF[] ArrowLeft = [new(6.99f, 11), new(3, 15), new(6.99f, 19), new(6.99f, 16), new(14, 16), new(14, 14), new(6.99f, 14)];
    static readonly PointF[] ArrowRight = [new(21, 9), new(17.01f, 5), new(17.01f, 8), new(10, 8), new(10, 10), new(17.01f, 10), new(17.01f, 13)];

    /// <summary>Navy tile with the yellow swap arrows and, optionally, a status lamp.</summary>
    public static Icon AppIcon(int px, Color? lamp)
    {
        using var bmp = new Bitmap(px, px);
        using (var g = Graphics.FromImage(bmp))
        {
            g.SmoothingMode = SmoothingMode.AntiAlias;
            float k = px / 24f;
            using (var tile = RoundRect(new RectangleF(0, 0, px, px), 5 * k))
            using (var navy = new SolidBrush(Navy)) g.FillPath(navy, tile);
            using (var signal = new SolidBrush(Signal))
            {
                g.FillPolygon(signal, ArrowLeft.Select(p => new PointF(p.X * k, p.Y * k)).ToArray());
                g.FillPolygon(signal, ArrowRight.Select(p => new PointF(p.X * k, p.Y * k)).ToArray());
            }
            if (lamp is Color c)
            {
                float r = 4.5f * k;
                using var ring = new SolidBrush(Navy);
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

    /// <summary>Windows 11: paint the title bar navy so it joins the header. Older Windows ignores it.</summary>
    public static void NavyTitleBar(IntPtr hwnd)
    {
        int caption = Navy.R | Navy.G << 8 | Navy.B << 16, text = OnNavy.R | OnNavy.G << 8 | OnNavy.B << 16;
        DwmSetWindowAttribute(hwnd, 35, ref caption, sizeof(int));   // DWMWA_CAPTION_COLOR
        DwmSetWindowAttribute(hwnd, 36, ref text, sizeof(int));      // DWMWA_TEXT_COLOR
    }

    [DllImport("user32.dll")] static extern bool DestroyIcon(IntPtr handle);
    [DllImport("dwmapi.dll")] static extern int DwmSetWindowAttribute(IntPtr hwnd, int attribute, ref int value, int size);
}

enum PillKind { Primary, Secondary, Quiet }

/// <summary>Harbor button: pill shaped, keyboard focus ring, hover tint.</summary>
sealed class PillButton : Button
{
    readonly PillKind kind;
    bool hover, down;

    public PillButton(string text, PillKind kind)
    {
        this.kind = kind;
        Text = text;
        Cursor = Cursors.Hand;
        FlatStyle = FlatStyle.Flat;
        BackColor = Harbor.Ground;
        Font = new Font("Segoe UI Semibold", 9.75f);
        SetStyle(ControlStyles.UserPaint | ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer, true);
    }

    protected override void OnMouseEnter(EventArgs e) { hover = true; Invalidate(); base.OnMouseEnter(e); }
    protected override void OnMouseLeave(EventArgs e) { hover = down = false; Invalidate(); base.OnMouseLeave(e); }
    protected override void OnMouseDown(MouseEventArgs e) { down = true; Invalidate(); base.OnMouseDown(e); }
    protected override void OnMouseUp(MouseEventArgs e) { down = false; Invalidate(); base.OnMouseUp(e); }

    protected override void OnPaint(PaintEventArgs e)
    {
        var g = e.Graphics;
        g.Clear(BackColor);
        g.SmoothingMode = SmoothingMode.AntiAlias;
        float s = DeviceDpi / 96f;
        var r = new RectangleF(s, s, Width - 2 * s, Height - 2 * s);
        using var pill = Harbor.RoundRect(r, r.Height / 2);

        Color text = Harbor.Ink;
        if (kind == PillKind.Primary)
        {
            using var fill = new SolidBrush(down || hover ? Harbor.SignalPressed : Harbor.Signal);
            g.FillPath(fill, pill);
            text = Harbor.OnSignal;
        }
        else
        {
            if (hover) using (var fill = new SolidBrush(Harbor.Hover)) g.FillPath(fill, pill);
            if (kind == PillKind.Secondary) using (var pen = new Pen(Harbor.Outline, s)) g.DrawPath(pen, pill);
        }
        if (!Enabled) text = Harbor.InkMuted;
        TextRenderer.DrawText(g, Text, Font, ClientRectangle, text,
            TextFormatFlags.HorizontalCenter | TextFormatFlags.VerticalCenter | TextFormatFlags.SingleLine);

        if (Focused && ShowFocusCues)
        {
            var inner = RectangleF.Inflate(r, -3 * s, -3 * s);
            using var ring = Harbor.RoundRect(inner, inner.Height / 2);
            using var pen = new Pen(kind == PillKind.Primary ? Harbor.OnSignal : Harbor.Ink, 2 * s);
            g.DrawPath(pen, ring);
        }
    }
}
