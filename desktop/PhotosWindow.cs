using System.Buffers.Binary;
using System.Collections.Concurrent;
using System.Drawing.Drawing2D;
using System.Text.Json;

namespace Ferry;

/// <summary>
/// Phone photos: the phone's photos in a grid, newest first, over a session that this window opens and closes.
/// Thumbnails come only for the photos on screen and stay in memory. A full photo comes when you open or drag it,
/// into a temporary folder. The phone (Photos.java) must use the same message kinds.
/// </summary>
sealed class PhotosWindow : Form
{
    // To the phone. AskList: the number of photos to skip (4 bytes). AskThumb and AskFull: the photo id (8 bytes).
    const byte AskList = 1, AskThumb = 2, AskFull = 3;
    // From the phone. GotPage: JSON {"photos":[{"id","t","s","n"}],"more"}. GotThumb: id, then a JPEG (empty when none).
    // GotPart: id, then a part of the file. GotEnd: id, then 1 when the whole file came, or 0 when it failed.
    const byte GotPage = 1, GotThumb = 2, GotPart = 3, GotEnd = 4;
    /// <summary>Thumbnails asked for at a time. More would queue up on the phone while you scroll past them.</summary>
    const int MaxAsked = 8;
    /// <summary>Thumbnails kept in memory (about 250 KB each). The oldest goes first, and comes again if you scroll back.</summary>
    const int MaxThumbs = 600;
    static readonly string Temp = Path.Combine(Path.GetTempPath(), "Ferry", "Photos");

    record Photo(long Id, DateTime Taken, long Size, string Name);

    readonly string code, phone;
    readonly ListView grid;
    readonly Label status;
    readonly PillButton again;
    readonly Font small = Chart.Font("Barlow", 9f);
    readonly int tile;
    readonly List<Photo> photos = [];
    readonly Dictionary<long, int> places = [];   // photo id -> its place in the grid
    readonly Dictionary<long, Image?> thumbs = [];   // null: the phone has no thumbnail for it
    readonly Queue<long> thumbOrder = new();
    readonly HashSet<long> asked = [];
    readonly List<long> wanted = [];   // drawn without a thumbnail; the last one drawn is asked for first
    readonly Dictionary<long, string> fetched = [];   // photo id -> the full photo in the temporary folder
    readonly ConcurrentDictionary<long, (FileStream File, string Path, TaskCompletionSource<string?> Done)> downloads = new();
    Session? session;
    bool more, listing;
    string ready = "";

    public PhotosWindow(string code, string phone)
    {
        this.code = code;
        this.phone = phone;
        try { Directory.Delete(Temp, true); } catch (Exception e) when (e is IOException or UnauthorizedAccessException) { }   // photos from last time

        SuspendLayout();
        AutoScaleDimensions = new SizeF(96F, 96F);
        AutoScaleMode = AutoScaleMode.Dpi;
        ClientSize = new Size(760, 580);
        MinimumSize = new Size(420, 320);
        StartPosition = FormStartPosition.CenterScreen;
        Text = "Phone photos · " + phone;
        Icon = Chart.AppIcon(32, null);
        BackColor = Chart.Ground;
        ForeColor = Chart.Ink;
        Font = Chart.Font("Barlow", 10f);
        KeyPreview = true;

        tile = (int)(150 * DeviceDpi / 96f);
        grid = new ListView
        {
            Dock = DockStyle.Fill,
            View = System.Windows.Forms.View.LargeIcon,
            VirtualMode = true,
            OwnerDraw = true,
            MultiSelect = true,
            BorderStyle = BorderStyle.None,
            BackColor = Chart.Ground,
            ForeColor = Chart.Ink,
            // The image list is empty: it only sets the size of each tile. Owner drawing paints the photos.
            LargeImageList = new ImageList { ImageSize = new Size(Math.Min(256, tile), Math.Min(256, tile)) },
            AccessibleName = "Phone photos",
        };
        grid.RetrieveVirtualItem += (_, e) =>
        {
            var p = photos[e.ItemIndex];
            e.Item = new ListViewItem(p.Name) { ToolTipText = $"{p.Name}, {p.Taken:g}, {p.Size / 1048576.0:0.0} MB" };
            if (e.ItemIndex >= photos.Count - 40) More();   // near the end of what came: ask for the next page
        };
        grid.DrawItem += DrawTile;
        grid.DoubleClick += (_, _) => OpenSelected();
        grid.KeyDown += (_, e) => { if (e.KeyCode == Keys.Enter) OpenSelected(); };
        grid.ItemDrag += (_, _) => DragSelected();
        grid.ShowItemToolTips = true;

        var top = new Panel { Dock = DockStyle.Top, Height = 44, Padding = new Padding(16, 8, 12, 8), BackColor = Chart.Ground };
        status = new Label { Dock = DockStyle.Fill, TextAlign = ContentAlignment.MiddleLeft, ForeColor = Chart.InkMuted, AutoEllipsis = true };
        again = new PillButton("Try again", PillKind.Chip) { Dock = DockStyle.Right, Width = 110, Visible = false };
        again.Click += async (_, _) => await Connect();
        top.Controls.Add(status);
        top.Controls.Add(again);
        Controls.Add(grid);
        Controls.Add(top);
        ResumeLayout(false);
    }

    protected override void OnHandleCreated(EventArgs e)
    {
        base.OnHandleCreated(e);
        Chart.ChartTitleBar(Handle, Chart.Ground);
    }

    protected override async void OnShown(EventArgs e)
    {
        base.OnShown(e);
        await Connect();
    }

    protected override void OnFormClosed(FormClosedEventArgs e)
    {
        session?.Dispose();   // the phone stops showing that the laptop looks
        foreach (var image in thumbs.Values) image?.Dispose();
        base.OnFormClosed(e);
    }

    async Task Connect()
    {
        again.Visible = false;
        Status($"Connecting to {phone}…");
        var (s, why) = await Session.Open(code, "photos");
        if (IsDisposed)
        {
            s?.Dispose();
            return;
        }
        if (s is null)
        {
            Status(why switch
            {
                "off" => $"On {phone}, open Ferry Settings and turn on \"Let the laptop see photos\".",
                "permission" => $"Ferry on {phone} needs the Photos permission set to \"Allow all\". Open Ferry Settings on the phone.",
                "nowifi" => $"Connect {phone} to the same Wi-Fi as this laptop.",
                "offline" => "This laptop is offline. Phone photos needs the internet to start.",
                "noconnect" => $"Ferry could not reach {phone}. Make sure that both are on the same Wi-Fi.",
                _ => $"{phone} did not answer. Make sure that it is on and online, and that it has the newest Ferry.",
            });
            again.Visible = true;
            return;
        }
        session = s;
        photos.Clear();
        places.Clear();
        grid.VirtualListSize = 0;
        (more, listing) = (true, false);
        More();
        Status($"Looking for photos on {phone}…");
        await Task.Run(() => s.Run(Received));   // on a thread-pool thread; each message comes back here through Ui
        foreach (long id in downloads.Keys) Failed(id);   // a photo that was on its way does not come now
        if (IsDisposed) return;
        session = null;
        asked.Clear();
        wanted.Clear();
        Status($"The link to {phone} ended. The phone left the Wi-Fi, or \"Stop\" was tapped on it.");
        again.Visible = true;
    }

    /// <summary>A message from the phone, on a thread-pool thread. A part of a file goes straight to disk; the rest goes to the window.</summary>
    void Received(byte kind, byte[] body)
    {
        long id = body.Length >= 8 ? BinaryPrimitives.ReadInt64BigEndian(body) : 0;
        switch (kind)
        {
            case GotPage:
                var page = new List<Photo>();
                bool hasMore = false;
                try
                {
                    using var doc = JsonDocument.Parse(body);
                    foreach (var p in doc.RootElement.GetProperty("photos").EnumerateArray())
                        page.Add(new Photo(p.GetProperty("id").GetInt64(), DateTimeOffset.FromUnixTimeMilliseconds(p.GetProperty("t").GetInt64()).LocalDateTime,
                            p.GetProperty("s").GetInt64(), p.GetProperty("n").GetString() ?? "photo.jpg"));
                    hasMore = doc.RootElement.GetProperty("more").GetBoolean();
                }
                catch (Exception e) when (e is JsonException or KeyNotFoundException or InvalidOperationException or FormatException or ArgumentOutOfRangeException)
                {
                    // Not a page this Ferry knows: stop asking for more.
                }
                Ui(() => AddPage(page, hasMore));
                break;
            case GotThumb when body.Length >= 8:
                Image? image = null;
                if (body.Length > 8)
                {
                    try
                    {
                        using var stream = new MemoryStream(body, 8, body.Length - 8);
                        using var raw = Image.FromStream(stream);
                        image = new Bitmap(raw);   // a copy, so the stream can close
                    }
                    catch (ArgumentException)
                    {
                        // not an image: the tile stays empty
                    }
                }
                Ui(() => ThumbArrived(id, image));
                break;
            case GotPart when body.Length >= 8:
                if (downloads.TryGetValue(id, out var d)) d.File.Write(body, 8, body.Length - 8);
                break;
            case GotEnd when body.Length == 9:
                if (body[8] != 1) Failed(id);
                else if (downloads.TryRemove(id, out var done))
                {
                    done.File.Dispose();
                    done.Done.TrySetResult(done.Path);
                }
                break;
        }
    }

    void Failed(long id)
    {
        if (!downloads.TryRemove(id, out var d)) return;
        d.File.Dispose();
        try { File.Delete(d.Path); } catch (Exception e) when (e is IOException or UnauthorizedAccessException) { }
        d.Done.TrySetResult(null);
    }

    void Ui(Action a)
    {
        try
        {
            if (!IsDisposed) BeginInvoke(a);
        }
        catch (InvalidOperationException)
        {
            // the window closed meanwhile
        }
    }

    void Status(string text) => status.Text = text;

    void More()
    {
        if (listing || !more || session is not { } s) return;
        listing = true;
        byte[] skip = new byte[4];
        BinaryPrimitives.WriteInt32BigEndian(skip, photos.Count);
        s.Post(AskList, skip);
    }

    void AddPage(List<Photo> page, bool hasMore)
    {
        foreach (var p in page)
        {
            places[p.Id] = photos.Count;
            photos.Add(p);
        }
        (more, listing) = (hasMore && page.Count > 0, false);
        grid.VirtualListSize = photos.Count;
        ready = photos.Count == 0 ? $"There are no photos on {phone}."
            : $"{photos.Count}{(more ? "+" : "")} photos on {phone}. Double-click a photo to open it, or drag photos to a folder to copy them.";
        Status(ready);
    }

    void DrawTile(object? sender, DrawListViewItemEventArgs e)
    {
        var g = e.Graphics;
        var p = photos[e.ItemIndex];
        bool selected = (e.State & ListViewItemStates.Selected) != 0;
        float s = DeviceDpi / 96f;
        var r = e.Bounds;
        using (var ground = new SolidBrush(selected ? Chart.Hover : Chart.Ground)) g.FillRectangle(ground, r);
        int pad = (int)(6 * s), label = (int)(18 * s);
        int side = Math.Max(1, Math.Min(r.Width, r.Height - label) - 2 * pad);
        var box = new Rectangle(r.X + (r.Width - side) / 2, r.Y + pad, side, side);

        if (thumbs.TryGetValue(p.Id, out var image) && image is not null)
        {
            // The whole photo, as large as the box allows, without changing its shape.
            float k = Math.Min((float)side / image.Width, (float)side / image.Height);
            var fit = new RectangleF(box.X + (side - image.Width * k) / 2, box.Y + (side - image.Height * k) / 2, image.Width * k, image.Height * k);
            g.InterpolationMode = InterpolationMode.HighQualityBicubic;
            g.DrawImage(image, fit);
        }
        else
        {
            using var water = new SolidBrush(Chart.Water);
            g.FillRectangle(water, box);
            if (!thumbs.ContainsKey(p.Id)) Want(p.Id);
        }
        if (selected)
            using (var pen = new Pen(Chart.Course, 2 * s)) g.DrawRectangle(pen, Rectangle.Inflate(box, (int)(2 * s), (int)(2 * s)));
        TextRenderer.DrawText(g, p.Taken.ToString("d MMM yyyy"), small, new Rectangle(r.X, box.Bottom + (int)(3 * s), r.Width, label),
            Chart.InkMuted, TextFormatFlags.HorizontalCenter | TextFormatFlags.SingleLine | TextFormatFlags.EndEllipsis);
        if ((e.State & ListViewItemStates.Focused) != 0 && grid.Focused) ControlPaint.DrawFocusRectangle(g, r);
    }

    void Want(long id)
    {
        if (asked.Contains(id)) return;
        wanted.Remove(id);
        wanted.Add(id);
        Pump();
    }

    /// <summary>Asks for thumbnails, a few at a time, newest drawn first. Photos that scrolled away meanwhile are skipped.</summary>
    void Pump()
    {
        while (asked.Count < MaxAsked && wanted.Count > 0 && session is { } s)
        {
            long id = wanted[^1];
            wanted.RemoveAt(wanted.Count - 1);
            if (thumbs.ContainsKey(id) || !places.TryGetValue(id, out int at) || !grid.ClientRectangle.IntersectsWith(grid.GetItemRect(at))) continue;
            asked.Add(id);
            byte[] body = new byte[8];
            BinaryPrimitives.WriteInt64BigEndian(body, id);
            s.Post(AskThumb, body);
        }
    }

    void ThumbArrived(long id, Image? image)
    {
        asked.Remove(id);
        thumbs[id] = image;
        thumbOrder.Enqueue(id);
        while (thumbOrder.Count > MaxThumbs && thumbOrder.TryDequeue(out long old))
        {
            if (thumbs.Remove(old, out var gone)) gone?.Dispose();
        }
        if (places.TryGetValue(id, out int at) && at < grid.VirtualListSize) grid.RedrawItems(at, at, true);
        Pump();
    }

    /// <summary>The full photo, from the phone into the temporary folder. Null when it did not come.</summary>
    async Task<string?> Fetch(Photo p)
    {
        if (fetched.TryGetValue(p.Id, out var have) && File.Exists(have)) return have;
        if (downloads.TryGetValue(p.Id, out var running)) return await running.Done.Task;
        if (session is not { } s) return null;
        string dir = Path.Combine(Temp, p.Id.ToString());
        Directory.CreateDirectory(dir);
        string path = Path.Combine(dir, TrayApp.SafeName(p.Name));
        var done = new TaskCompletionSource<string?>(TaskCreationOptions.RunContinuationsAsynchronously);
        downloads[p.Id] = (new FileStream(path, FileMode.Create, FileAccess.Write, FileShare.Read), path, done);
        byte[] body = new byte[8];
        BinaryPrimitives.WriteInt64BigEndian(body, p.Id);
        s.Post(AskFull, body);
        string? got = await done.Task;
        if (got is not null) fetched[p.Id] = got;
        return got;
    }

    async void OpenSelected()
    {
        if (grid.SelectedIndices.Count == 0) return;
        var p = photos[grid.SelectedIndices[0]];
        Status($"Getting {p.Name} from {phone}…");
        string? path = await Fetch(p);
        if (IsDisposed) return;
        Status(path is null ? $"{p.Name} did not come from {phone}. Try again." : ready);
        if (path is not null) TrayApp.Open(path);
    }

    /// <summary>
    /// Drags the selected photos out as files. Windows needs the files before the drag starts, so they come from the
    /// phone first; that takes a moment for each photo the first time.
    /// </summary>
    async void DragSelected()
    {
        var chosen = grid.SelectedIndices.Cast<int>().Take(100).Select(i => photos[i]).ToList();
        if (chosen.Count == 0) return;
        Status(chosen.Count == 1 ? $"Getting {chosen[0].Name} from {phone}…" : $"Getting {chosen.Count} photos from {phone}…");
        var paths = new List<string>();
        foreach (var p in chosen)
            if (await Fetch(p) is { } path) paths.Add(path);   // one at a time: the phone sends one file at a time
        if (IsDisposed) return;
        if (paths.Count < chosen.Count) Status($"{chosen.Count - paths.Count} of the photos did not come from {phone}.");
        else if (MouseButtons != MouseButtons.Left) Status("The photos are ready. Drag them again to drop them.");
        else Status(ready);
        if (paths.Count > 0 && MouseButtons == MouseButtons.Left)
            grid.DoDragDrop(new DataObject(DataFormats.FileDrop, paths.ToArray()), DragDropEffects.Copy);
    }
}
