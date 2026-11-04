namespace Ferry;

static class Program
{
    [STAThread]
    static void Main(string[] args)
    {
        // One copy only. Starting Ferry again opens the window of the copy that is already running.
        // With file paths (Send to > Ferry), it hands the files to that copy instead.
        using var show = new EventWaitHandle(false, EventResetMode.AutoReset, @"Local\Ferry.Show", out bool first);
        if (!first)
        {
            if (args.Length == 0) show.Set();
            else TrayApp.HandOver(args);
            return;
        }
        ApplicationConfiguration.Initialize();
        Application.Run(new TrayApp(show, args));
    }
}
