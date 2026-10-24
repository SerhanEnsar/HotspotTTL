using System;
using System.Runtime.InteropServices;
using System.Threading;
using System.Windows.Forms;

namespace HotspotTTL
{
    static class Program
    {
        [STAThread]
        static int Main(string[] args)
        {
            // Komut satırı: HotspotTTL.exe --status | --on | --off
            if (args.Length > 0) return Cli(args[0]);

            using (var mutex = new Mutex(true, @"Global\HotspotTTL", out bool first))
            {
                if (!first) return 0;
                Application.EnableVisualStyles();
                Application.SetCompatibleTextRenderingDefault(false);
                Application.Run(new TrayApp());
            }
            return 0;
        }

        static int Cli(string cmd)
        {
            AttachConsole(-1);
            string err = null;
            switch (cmd)
            {
                case "--on": err = Ttl.Enable(); break;
                case "--off": err = Ttl.Disable(); break;
                case "--status": break;
                default:
                    Console.WriteLine("Kullanım: HotspotTTL.exe [--status | --on | --off]");
                    return 2;
            }
            if (err != null) Console.Error.WriteLine("HATA: " + err);
            var s = Ttl.Read();
            Console.WriteLine(s.Serialize().TrimEnd());
            Console.WriteLine(s.IsOn ? "Durum: AÇIK" : "Durum: KAPALI");
            return err == null ? 0 : 1;
        }

        [DllImport("kernel32.dll")]
        static extern bool AttachConsole(int processId);
    }
}
