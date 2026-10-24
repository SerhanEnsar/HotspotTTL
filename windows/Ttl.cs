using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Text;

namespace HotspotTTL
{
    /// Bir ağ arayüzünün (IPv4 ya da IPv6 tarafı) anlık hop limit değeri
    sealed class Iface
    {
        public int Index;
        public string Family;   // "IPv4" | "IPv6"
        public int HopLimit;
        public string Alias;
    }

    /// Sistemdeki TTL / hop limit değerlerinin anlık görüntüsü
    sealed class Snapshot
    {
        public int? V4;
        public int? V6;
        public List<Iface> Ifaces = new List<Iface>();

        public bool IsOn => V4 == Ttl.Spoofed && V6 == Ttl.Spoofed;
        public bool AllIfacesOn => Ifaces.All(i => i.HopLimit == Ttl.Spoofed);

        /// Satır biçimi: "G IPv4 128" ve "I 12 IPv6 64 Wi-Fi"
        public static Snapshot Parse(string text)
        {
            var s = new Snapshot();
            foreach (var raw in text.Split('\n'))
            {
                var p = raw.Trim().Split(new[] { ' ' }, 5);
                if (p.Length >= 3 && p[0] == "G" && int.TryParse(p[2], out var g))
                {
                    if (p[1] == "IPv4") s.V4 = g;
                    if (p[1] == "IPv6") s.V6 = g;
                }
                else if (p.Length >= 4 && p[0] == "I" && int.TryParse(p[1], out var idx) && int.TryParse(p[3], out var hop))
                {
                    s.Ifaces.Add(new Iface { Index = idx, Family = p[2], HopLimit = hop, Alias = p.Length > 4 ? p[4] : "" });
                }
            }
            return s;
        }

        public string Serialize()
        {
            var sb = new StringBuilder();
            if (V4 != null) sb.Append("G IPv4 ").Append(V4).Append('\n');
            if (V6 != null) sb.Append("G IPv6 ").Append(V6).Append('\n');
            foreach (var i in Ifaces) sb.Append($"I {i.Index} {i.Family} {i.HopLimit} {i.Alias}\n");
            return sb.ToString();
        }
    }

    /// Windows'ta TTL'i değiştiren kısım.
    ///
    /// Windows'ta bütün uygulamalar (Edge, Chrome, Store...) çekirdeğin TCP/IP yığınını kullanıyor,
    /// macOS'taki gibi kullanıcı alanında paket üreten ayrı bir yığın yok. Bu yüzden iki ayar yetiyor:
    ///  ① global varsayılan hop limit (IPv4 ve IPv6)
    ///  ② her arayüzün "current hop limit" değeri. IPv6'da telefonun Router Advertisement'ı bunu 64'e
    ///    çekebiliyor ve global ayarı ezebiliyor; mod açıkken ağ değişince yeniden yazılıyor.
    /// Değişiklikler netsh "store=active" ile yapılıyor: kalıcı değil, yeniden başlatınca sıfırlanıyor.
    static class Ttl
    {
        public const int Spoofed = 65;
        const int WindowsDefault = 128;

        static string BackupPath =>
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData), "HotspotTTL", "backup.txt");

        // Özellik adları (DefaultHopLimit, CurrentHopLimit) Windows dilinden bağımsız; netsh çıktısı ise yerelleştirilmiş.
        const string ReadScript = @"
$ErrorActionPreference = 'SilentlyContinue'
[Console]::OutputEncoding = [Text.Encoding]::UTF8
'G IPv4 ' + (Get-NetIPv4Protocol).DefaultHopLimit
'G IPv6 ' + (Get-NetIPv6Protocol).DefaultHopLimit
Get-NetIPInterface -ConnectionState Connected |
  Where-Object { $_.InterfaceAlias -notlike 'Loopback*' } |
  ForEach-Object { 'I ' + $_.ifIndex + ' ' + $_.AddressFamily + ' ' + $_.CurrentHopLimit + ' ' + $_.InterfaceAlias }
";

        public static Snapshot Read() => Snapshot.Parse(PowerShell(ReadScript));

        /// Açık mı? Yedek dosyası varsa ve yeniden başlatma sonrası değerler sıfırlandıysa yedeği siler.
        public static Snapshot ReadAndCleanup()
        {
            var s = Read();
            if (!s.IsOn && File.Exists(BackupPath)) File.Delete(BackupPath);
            return s;
        }

        public static string Enable()
        {
            var cur = Read();
            if (cur.V4 == null) return "TTL değerleri okunamadı (PowerShell NetTCPIP modülü yok mu?)";

            // Kapatırken geri dönebilmek için ilk açılıştaki değerleri sakla
            if (!File.Exists(BackupPath) && !cur.IsOn)
            {
                Directory.CreateDirectory(Path.GetDirectoryName(BackupPath));
                File.WriteAllText(BackupPath, cur.Serialize());
            }

            var cmds = new List<string>
            {
                $"interface ipv4 set global defaultcurhoplimit={Spoofed} store=active",
                $"interface ipv6 set global defaultcurhoplimit={Spoofed} store=active",
            };
            cmds.AddRange(cur.Ifaces.Select(i => SetIface(i, Spoofed)));
            return Netsh(cmds);
        }

        /// Mod açıkken ağ değiştiğinde (yeni Wi-Fi, IPv6 RA) 65 olmayan arayüzleri düzeltir.
        public static string Reapply()
        {
            if (!File.Exists(BackupPath)) return null;
            var cur = Read();
            if (!cur.IsOn) return null;
            var cmds = cur.Ifaces.Where(i => i.HopLimit != Spoofed).Select(i => SetIface(i, Spoofed)).ToList();
            return cmds.Count == 0 ? null : Netsh(cmds);
        }

        public static string Disable()
        {
            var backup = File.Exists(BackupPath) ? Snapshot.Parse(File.ReadAllText(BackupPath)) : new Snapshot();
            int v4 = Sane(backup.V4), v6 = Sane(backup.V6);
            var cur = Read();

            var cmds = new List<string>
            {
                $"interface ipv4 set global defaultcurhoplimit={v4} store=active",
                $"interface ipv6 set global defaultcurhoplimit={v6} store=active",
            };
            foreach (var i in cur.Ifaces)
            {
                // Açıkken bağlanılan arayüzlerin eski değeri bilinmiyor: global değeri kullanmaya dön
                var old = backup.Ifaces.FirstOrDefault(b => b.Index == i.Index && b.Family == i.Family);
                // 0 = arayüz global değeri kullanıyor (Windows varsayılanı)
                cmds.Add(SetIface(i, old != null && old.HopLimit != Spoofed ? old.HopLimit : 0));
            }
            var err = Netsh(cmds);
            if (err == null && File.Exists(BackupPath)) File.Delete(BackupPath);
            return err;
        }

        static int Sane(int? v) => v == null || v == Spoofed || v <= 0 ? WindowsDefault : v.Value;

        static string SetIface(Iface i, int value) =>
            $"interface {i.Family.ToLowerInvariant()} set interface interface={i.Index} currenthoplimit={value} store=active";

        /// netsh komutlarını sırayla çalıştırır, hata varsa ilkini döner.
        static string Netsh(IEnumerable<string> commands)
        {
            string firstError = null;
            foreach (var c in commands)
            {
                var (code, output) = Run("netsh.exe", c, Oem);
                if (code != 0 && firstError == null)
                    firstError = $"netsh {c}\n{output.Trim()}";
            }
            return firstError;
        }

        public static string PowerShell(string script)
        {
            var encoded = Convert.ToBase64String(Encoding.Unicode.GetBytes(script));
            return Run("powershell.exe", "-NoProfile -NonInteractive -ExecutionPolicy Bypass -EncodedCommand " + encoded).output;
        }

        // netsh ve diğer konsol araçları OEM kod sayfasıyla (Türkçe Windows'ta 857) yazıyor
        static readonly Encoding Oem = Encoding.GetEncoding(CultureInfo.CurrentCulture.TextInfo.OEMCodePage);

        public static (int code, string output) Run(string file, string args, Encoding encoding = null)
        {
            var psi = new ProcessStartInfo(file, args)
            {
                UseShellExecute = false,
                CreateNoWindow = true,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                StandardOutputEncoding = encoding ?? Encoding.UTF8,
                StandardErrorEncoding = encoding ?? Encoding.UTF8,
            };
            try
            {
                using (var p = Process.Start(psi))
                {
                    var err = p.StandardError.ReadToEndAsync();
                    var output = p.StandardOutput.ReadToEnd();
                    p.WaitForExit();
                    return (p.ExitCode, output + err.Result);
                }
            }
            catch (Exception e)
            {
                return (-1, e.Message);
            }
        }
    }
}
