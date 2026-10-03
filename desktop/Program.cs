namespace Ferry;

static class Program
{
    [STAThread]
    static void Main()
    {
        // One copy only. Starting Ferry again opens the window of the copy that is already running.
        using var show = new EventWaitHandle(false, EventResetMode.AutoReset, @"Local\Ferry.Show", out bool first);
        if (!first)
        {
            show.Set();
            return;
        }
        ApplicationConfiguration.Initialize();
        Application.Run(new TrayApp(show));
    }
}
