using System.Buffers.Binary;
using System.Runtime.InteropServices;
using System.Text;

namespace Ferry;

/// <summary>
/// Helm, the phone as a remote: turns the messages of a Helm session into real pointer moves, clicks, scrolls and
/// key presses (SendInput). The phone (HelmActivity.java) must use the same message kinds and bodies.
/// Windows does not let this reach a window that runs as administrator, a UAC prompt or the lock screen.
/// </summary>
static class Helm
{
    // Message kinds. Bodies: Move = dx, dy (2 bytes each, signed); Button = 0 left or 1 right, then 1 down or 0 up;
    // Scroll = vertical, horizontal (2 bytes each, signed, 120 = one wheel notch); Text = UTF-8; Key = a virtual-key code.
    // Echo = the phone's clock (8 bytes), sent back as it came (TrayApp), so the phone can show the time of the link.
    const byte Move = 1, Button = 2, Scroll = 3, Text = 4, Key = 5, Lock = 6;
    public const byte Sleep = 7, Echo = 8;

    /// <summary>The keys that the Helm screen has. Others are ignored, so a phone cannot press the Windows key or a shortcut.</summary>
    static readonly HashSet<byte> AllowedKeys =
    [
        0x08, 0x09, 0x0D, 0x1B,   // Backspace, Tab, Enter, Esc
        0x21, 0x22,   // Page Up, Page Down: slides
        0x25, 0x26, 0x27, 0x28,   // arrows
        0xAD, 0xAE, 0xAF,   // mute, volume down, volume up
        0xB0, 0xB1, 0xB3,   // next track, previous track, play or pause
    ];

    const uint InputMouse = 0, InputKeyboard = 1;
    const uint MouseMove = 0x0001, LeftDown = 0x0002, LeftUp = 0x0004, RightDown = 0x0008, RightUp = 0x0010, Wheel = 0x0800, HWheel = 0x1000,
        VirtualDesk = 0x4000, Absolute = 0x8000;
    const uint KeyExtended = 0x0001, KeyUp = 0x0002, KeyUnicode = 0x0004;

    /// <summary>Does what one message says. Called on a thread-pool thread; SendInput works from any thread.</summary>
    public static void Handle(byte kind, byte[] body)
    {
        switch (kind)
        {
            case Move when body.Length == 4:
                MoveBy(I16(body, 0), I16(body, 2));
                break;
            case Button when body.Length == 2:
                bool down = body[1] == 1;
                Send(Mouse(body[0] == 1 ? (down ? RightDown : RightUp) : (down ? LeftDown : LeftUp)));
                break;
            case Scroll when body.Length == 4:
                short v = I16(body, 0), h = I16(body, 2);
                if (v != 0) Send(Mouse(Wheel, data: v));
                if (h != 0) Send(Mouse(HWheel, data: h));
                break;
            case Text:
                Type(Encoding.UTF8.GetString(body));
                break;
            case Key when body.Length == 1 && AllowedKeys.Contains(body[0]):
                Send(KeyPress(body[0], down: true), KeyPress(body[0], down: false));
                break;
            case Lock:
                LockWorkStation();
                break;
        }
    }

    /// <summary>Types text as Unicode characters, so any keyboard language on the phone works. A new line is the Enter key.</summary>
    static void Type(string text)
    {
        var inputs = new List<INPUT>();
        foreach (char ch in text)
        {
            if (ch == '\n')
            {
                inputs.Add(KeyPress(0x0D, down: true));
                inputs.Add(KeyPress(0x0D, down: false));
                continue;
            }
            if (char.IsControl(ch)) continue;
            inputs.Add(Keyboard(0, ch, KeyUnicode));
            inputs.Add(Keyboard(0, ch, KeyUnicode | KeyUp));
        }
        Send([.. inputs]);
    }

    /// <summary>
    /// Moves the pointer by exactly dx, dy pixels, over all screens. The phone sets the speed curve; a relative move would
    /// get the Windows pointer acceleration on top of it, so the move is to an absolute place.
    /// </summary>
    static void MoveBy(int dx, int dy)
    {
        if (!GetCursorPos(out var at)) return;
        int left = GetSystemMetrics(76), top = GetSystemMetrics(77), width = GetSystemMetrics(78), height = GetSystemMetrics(79);   // the virtual screen
        if (width <= 0 || height <= 0) return;
        int x = Math.Clamp(at.X + dx, left, left + width - 1) - left, y = Math.Clamp(at.Y + dy, top, top + height - 1) - top;
        // Windows maps 0 to 65535 onto the screen and rounds down, so round up here: the pointer lands on exactly this pixel.
        Send(Mouse(MouseMove | Absolute | VirtualDesk, (int)(((long)x * 65536 + width - 1) / width), (int)(((long)y * 65536 + height - 1) / height)));
    }

    static short I16(byte[] b, int at) => BinaryPrimitives.ReadInt16BigEndian(b.AsSpan(at, 2));

    static INPUT Mouse(uint flags, int dx = 0, int dy = 0, int data = 0) =>
        new() { type = InputMouse, u = new InputUnion { mi = new MOUSEINPUT { dx = dx, dy = dy, mouseData = (uint)data, dwFlags = flags } } };

    /// <summary>Page Up, Page Down and the arrows are extended keys. Without the flag, Windows can read them as the number pad.</summary>
    static INPUT KeyPress(byte vk, bool down) =>
        Keyboard(vk, 0, (vk is >= 0x21 and <= 0x28 ? KeyExtended : 0) | (down ? 0 : KeyUp));

    static INPUT Keyboard(ushort vk, ushort scan, uint flags) =>
        new() { type = InputKeyboard, u = new InputUnion { ki = new KEYBDINPUT { wVk = vk, wScan = scan, dwFlags = flags } } };

    static void Send(params INPUT[] inputs)
    {
        if (inputs.Length > 0) SendInput((uint)inputs.Length, inputs, Marshal.SizeOf<INPUT>());
    }

    [StructLayout(LayoutKind.Sequential)]
    struct INPUT
    {
        public uint type;
        public InputUnion u;
    }

    // The largest member sets the size: INPUT is 40 bytes on 64-bit Windows, as SendInput expects.
    [StructLayout(LayoutKind.Explicit)]
    struct InputUnion
    {
        [FieldOffset(0)] public MOUSEINPUT mi;
        [FieldOffset(0)] public KEYBDINPUT ki;
    }

    [StructLayout(LayoutKind.Sequential)]
    struct MOUSEINPUT
    {
        public int dx, dy;
        public uint mouseData, dwFlags, time;
        public IntPtr dwExtraInfo;
    }

    [StructLayout(LayoutKind.Sequential)]
    struct KEYBDINPUT
    {
        public ushort wVk, wScan;
        public uint dwFlags, time;
        public IntPtr dwExtraInfo;
    }

    [DllImport("user32.dll", SetLastError = true)]
    static extern uint SendInput(uint count, INPUT[] inputs, int size);

    [DllImport("user32.dll")]
    static extern bool LockWorkStation();

    [StructLayout(LayoutKind.Sequential)]
    struct POINT
    {
        public int X, Y;
    }

    [DllImport("user32.dll")]
    static extern bool GetCursorPos(out POINT point);

    [DllImport("user32.dll")]
    static extern int GetSystemMetrics(int index);
}
