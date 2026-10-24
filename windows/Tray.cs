using System;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Net;
using System.Net.NetworkInformation;
using System.Reflection;
using System.Threading.Tasks;
using System.Windows.Forms;
using Microsoft.Win32;

namespace HotspotTTL
{
    /// Bildirim alanındaki ikon ve ona tıklayınca açılan panel
    sealed class TrayApp : ApplicationContext
    {
        readonly NotifyIcon tray;
        readonly Icon iconOn = LoadIcon("icon.ico"), iconOff = LoadIcon("icon-off.ico");
        readonly Panel panel;
        readonly Timer reapplyTimer = new Timer { Interval = 30_000 };
        Snapshot state = new Snapshot();
        bool busy, diagnosing;
        DateTime lastNetworkChange;

        public TrayApp()
        {
            panel = new Panel(this);
            var menu = new ContextMenuStrip();
            menu.Items.Add("Paneli göster", null, (_, __) => ShowPanel());
            menu.Items.Add("Aç / Kapat", null, (_, __) => Toggle());
            menu.Items.Add(new ToolStripSeparator());
            menu.Items.Add("Çıkış", null, (_, __) => Quit());

            tray = new NotifyIcon { Icon = iconOff, Text = "Hotspot TTL", ContextMenuStrip = menu, Visible = true };
            tray.MouseClick += (_, e) => { if (e.Button == MouseButtons.Left) ShowPanel(); };

            // Mod açıkken: Wi-Fi değişince ya da IPv6 RA arayüz hop limit'ini 64'e çekince yeniden 65 yaz
            reapplyTimer.Tick += async (_, __) => await Reapply();
            reapplyTimer.Start();
            NetworkChange.NetworkAddressChanged += (_, __) => lastNetworkChange = DateTime.Now;
            var debounce = new Timer { Interval = 3_000 };
            debounce.Tick += async (_, __) =>
            {
                if (lastNetworkChange != default && DateTime.Now - lastNetworkChange > TimeSpan.FromSeconds(2))
                {
                    lastNetworkChange = default;
                    await Reapply();
                }
            };
            debounce.Start();

            _ = Refresh(cleanup: true);
            ShowPanel();
        }

        public Snapshot State => state;
        public bool Busy => busy;
        public bool Diagnosing => diagnosing;
        public string Message { get; private set; }
        public bool MessageIsError { get; private set; }

        void ShowPanel()
        {
            panel.ShowNearTray();
            _ = Refresh();
        }

        public async Task Refresh(bool cleanup = false)
        {
            state = await Task.Run(() => cleanup ? Ttl.ReadAndCleanup() : Ttl.Read());
            Update();
        }

        public async void Toggle()
        {
            if (busy) return;
            busy = true;
            SetMessage(null);
            bool on = !state.IsOn;
            var err = await Task.Run(() => on ? Ttl.Enable() : Ttl.Disable());
            busy = false;
            if (err != null) SetMessage(err, true);
            await Refresh();
        }

        async Task Reapply()
        {
            if (busy || !state.IsOn) return;
            busy = true;
            await Task.Run(() => Ttl.Reapply());
            busy = false;
            await Refresh();
        }

        /// Hotspot üzerinden iki adrese bağlanmayı dener
        public async void Test()
        {
            if (busy) return;
            busy = true;
            SetMessage("Test ediliyor…");
            var result = await Task.Run(() =>
                Probe("http://connectivitycheck.gstatic.com/generate_204") + "\n" +
                Probe("https://www.google.com/"));
            busy = false;
            SetMessage(result, result.Contains("✗"));
        }

        static string Probe(string url)
        {
            var host = new Uri(url).Host;
            var sw = Stopwatch.StartNew();
            try
            {
                ServicePointManager.SecurityProtocol |= SecurityProtocolType.Tls12;
                var req = (HttpWebRequest)WebRequest.Create(url);
                req.Timeout = 8000;
                req.AllowAutoRedirect = false;
                using (var resp = (HttpWebResponse)req.GetResponse())
                    return $"✓ {host}: {(int)resp.StatusCode} ({sw.ElapsedMilliseconds} ms)";
            }
            catch (WebException e) when (e.Response is HttpWebResponse r)
            {
                return $"✓ {host}: {(int)r.StatusCode} ({sw.ElapsedMilliseconds} ms)";
            }
            catch (Exception e)
            {
                return $"✗ {host}: {(e is WebException w && w.Status == WebExceptionStatus.Timeout ? "zaman aşımı" : e.Message)}";
            }
        }

        /// Gömülü diagnose.ps1'i çalıştırır, raporu Masaüstü'ne yazar ve Gezgin'de gösterir
        public async void Diagnose()
        {
            if (diagnosing) return;
            diagnosing = true;
            SetMessage(null);
            var path = await Task.Run(() =>
            {
                string script;
                using (var s = Assembly.GetExecutingAssembly().GetManifestResourceStream("diagnose.ps1"))
                using (var r = new StreamReader(s))
                    script = r.ReadToEnd();
                return Ttl.PowerShell(script).Trim();
            });
            diagnosing = false;
            if (File.Exists(path))
            {
                SetMessage("Rapor Masaüstü'ne kaydedildi");
                Process.Start("explorer.exe", $"/select,\"{path}\"");
            }
            else SetMessage("Tanı başarısız", true);
        }

        void SetMessage(string msg, bool error = false)
        {
            Message = msg;
            MessageIsError = error;
            Update();
        }

        void Update()
        {
            tray.Icon = state.IsOn ? iconOn : iconOff;
            tray.Text = state.IsOn ? "Hotspot TTL: açık (TTL 65)" : "Hotspot TTL: kapalı";
            panel.Render();
        }

        public void Quit()
        {
            // Ayarlar zaten kalıcı değil; uygulama kapanınca da açık bırakmıyoruz, çünkü yeniden yazan kimse kalmıyor
            if (state.IsOn) Ttl.Disable();
            tray.Visible = false;
            ExitThread();
        }

        static Icon LoadIcon(string name)
        {
            using (var s = Assembly.GetExecutingAssembly().GetManifestResourceStream(name))
                return new Icon(s, SystemInformation.SmallIconSize);
        }
    }

    /// Görev çubuğunun yanında açılan küçük panel (macOS sürümündeki menü çubuğu paneline benzer)
    sealed class Panel : Form
    {
        readonly TrayApp app;
        readonly Label title = new Label(), subtitle = new Label(), v4 = new Label(), v6 = new Label(),
            ifaces = new Label(), message = new Label();
        readonly Button toggle = new Button(), test = new Button(), diagnose = new Button(),
            refresh = new Button(), quit = new Button();
        readonly PictureBox icon = new PictureBox();
        readonly Image imgOn, imgOff;
        readonly bool dark;
        readonly Color fg, dim, bg, card, green = Color.FromArgb(46, 160, 67), red = Color.FromArgb(218, 54, 51);

        public Panel(TrayApp app)
        {
            this.app = app;
            dark = IsDarkMode();
            bg = dark ? Color.FromArgb(32, 32, 32) : Color.FromArgb(249, 249, 249);
            card = dark ? Color.FromArgb(45, 45, 45) : Color.FromArgb(236, 236, 236);
            fg = dark ? Color.White : Color.FromArgb(26, 26, 26);
            dim = dark ? Color.FromArgb(170, 170, 170) : Color.FromArgb(100, 100, 100);

            using (var s = Assembly.GetExecutingAssembly().GetManifestResourceStream("icon.ico")) imgOn = new Icon(s, 64, 64).ToBitmap();
            using (var s = Assembly.GetExecutingAssembly().GetManifestResourceStream("icon-off.ico")) imgOff = new Icon(s, 64, 64).ToBitmap();

            Text = "Hotspot TTL";
            FormBorderStyle = FormBorderStyle.FixedToolWindow;
            ShowInTaskbar = false;
            TopMost = true;
            StartPosition = FormStartPosition.Manual;
            BackColor = bg;
            ForeColor = fg;
            Font = new Font("Segoe UI", 9.75f);
            AutoScaleMode = AutoScaleMode.Dpi;
            AutoSize = true;
            AutoSizeMode = AutoSizeMode.GrowAndShrink;
            Padding = new Padding(16);

            var root = new TableLayoutPanel { ColumnCount = 1, AutoSize = true, Dock = DockStyle.Fill, Width = 290 };
            root.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 290));

            // Başlık satırı
            icon.SizeMode = PictureBoxSizeMode.Zoom;
            icon.Size = new Size(36, 36);
            icon.Margin = new Padding(0, 0, 10, 0);
            title.Text = "Hotspot TTL";
            title.Font = new Font("Segoe UI Semibold", 12f);
            title.AutoSize = true;
            subtitle.AutoSize = true;
            subtitle.ForeColor = dim;
            subtitle.Font = new Font("Segoe UI", 8.75f);
            var titles = new FlowLayoutPanel { FlowDirection = FlowDirection.TopDown, AutoSize = true, Margin = Padding.Empty };
            titles.Controls.Add(title);
            titles.Controls.Add(subtitle);
            var header = new FlowLayoutPanel { AutoSize = true, Margin = new Padding(0, 0, 0, 12), WrapContents = false };
            header.Controls.Add(icon);
            header.Controls.Add(titles);
            root.Controls.Add(header);

            // Aç / Kapat
            Style(toggle, filled: true);
            toggle.Font = new Font("Segoe UI Semibold", 11f);
            toggle.Height = 40;
            toggle.Dock = DockStyle.Fill;
            toggle.Margin = new Padding(0, 0, 0, 12);
            toggle.Click += (_, __) => app.Toggle();
            root.Controls.Add(toggle);

            // Değerler
            var values = new TableLayoutPanel { ColumnCount = 2, AutoSize = true, Dock = DockStyle.Fill, BackColor = card,
                Padding = new Padding(10, 8, 10, 8), Margin = new Padding(0, 0, 0, 12) };
            values.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 60));
            values.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 40));
            AddRow(values, "IPv4 TTL", v4);
            AddRow(values, "IPv6 hop limit", v6);
            ifaces.AutoSize = true;
            ifaces.ForeColor = dim;
            ifaces.Font = new Font("Segoe UI", 8.75f);
            ifaces.MaximumSize = new Size(260, 0);
            ifaces.Margin = new Padding(0, 6, 0, 0);
            values.Controls.Add(ifaces);
            values.SetColumnSpan(ifaces, 2);
            root.Controls.Add(values);

            // Test + Tanı
            var actions = new TableLayoutPanel { ColumnCount = 2, AutoSize = true, Dock = DockStyle.Fill, Margin = new Padding(0, 0, 0, 8) };
            actions.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 50));
            actions.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 50));
            Style(test);
            test.Text = "Test Et";
            test.Click += (_, __) => app.Test();
            Style(diagnose);
            diagnose.Click += (_, __) => app.Diagnose();
            test.Dock = diagnose.Dock = DockStyle.Fill;
            test.Margin = new Padding(0, 0, 4, 0);
            diagnose.Margin = new Padding(4, 0, 0, 0);
            actions.Controls.Add(test, 0, 0);
            actions.Controls.Add(diagnose, 1, 0);
            root.Controls.Add(actions);

            message.AutoSize = true;
            message.MaximumSize = new Size(290, 0);
            message.Font = new Font("Segoe UI", 8.75f);
            message.Margin = new Padding(0, 0, 0, 8);
            root.Controls.Add(message);

            // Alt satır
            var footer = new TableLayoutPanel { ColumnCount = 2, AutoSize = true, Dock = DockStyle.Fill, Margin = Padding.Empty };
            footer.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 50));
            footer.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 50));
            Style(refresh, flat: true);
            refresh.Text = "Yenile";
            refresh.Click += async (_, __) => await app.Refresh();
            Style(quit, flat: true);
            quit.Text = "Çıkış";
            quit.Anchor = AnchorStyles.Right;
            quit.Click += (_, __) => app.Quit();
            footer.Controls.Add(refresh, 0, 0);
            footer.Controls.Add(quit, 1, 0);
            root.Controls.Add(footer);

            Controls.Add(root);
            Render();
        }

        void AddRow(TableLayoutPanel t, string name, Label value)
        {
            t.Controls.Add(new Label { Text = name, ForeColor = dim, AutoSize = true, Margin = new Padding(0, 3, 0, 3) });
            value.AutoSize = true;
            value.Anchor = AnchorStyles.Right;
            value.Font = new Font("Segoe UI Semibold", 9.75f);
            value.Margin = new Padding(0, 3, 0, 3);
            t.Controls.Add(value);
        }

        void Style(Button b, bool filled = false, bool flat = false)
        {
            b.FlatStyle = FlatStyle.Flat;
            b.UseVisualStyleBackColor = false;
            b.Cursor = Cursors.Hand;
            b.Height = 32;
            b.AutoSize = flat;
            b.FlatAppearance.BorderSize = filled || flat ? 0 : 1;
            b.FlatAppearance.BorderColor = dark ? Color.FromArgb(80, 80, 80) : Color.FromArgb(200, 200, 200);
            b.BackColor = flat ? bg : card;
            b.ForeColor = filled ? Color.White : fg;
        }

        public void Render()
        {
            if (InvokeRequired) { BeginInvoke((Action)Render); return; }
            var s = app.State;
            bool on = s.IsOn;
            icon.Image = on ? imgOn : imgOff;
            subtitle.Text = app.Busy ? "Uygulanıyor…" : on ? "Aktif: TTL 65" : "Kapalı: varsayılan";

            toggle.Text = on ? "Kapat" : "Aç";
            toggle.BackColor = on ? red : green;
            toggle.FlatAppearance.MouseOverBackColor = ControlPaint.Light(toggle.BackColor, 0.15f);
            toggle.Enabled = !app.Busy;

            Value(v4, s.V4);
            Value(v6, s.V6);
            var list = new System.Text.StringBuilder();
            foreach (var i in s.Ifaces)
                list.Append($"{i.Alias} ({i.Family}): {i.HopLimit}\n");
            ifaces.Text = list.Length > 0 ? list.ToString().TrimEnd() : "Bağlı arayüz yok";

            test.Enabled = !app.Busy;
            diagnose.Enabled = !app.Diagnosing;
            diagnose.Text = app.Diagnosing ? "Tanı sürüyor (~1 dk)…" : "Tanı Çalıştır";

            message.Visible = app.Message != null;
            message.Text = app.Message ?? "";
            message.ForeColor = app.MessageIsError ? red : dim;

            if (Visible) PlaceNearTray();
        }

        void Value(Label l, int? v)
        {
            l.Text = v?.ToString() ?? "—";
            l.ForeColor = v == Ttl.Spoofed ? green : fg;
        }

        public void ShowNearTray()
        {
            Render();
            PlaceNearTray();
            Show();
            Activate();
        }

        /// Ekranın sağ altına, görev çubuğunun hemen üstüne yerleştir
        void PlaceNearTray()
        {
            var area = Screen.FromPoint(Cursor.Position).WorkingArea;
            Location = new Point(area.Right - Width - 12, area.Bottom - Height - 12);
        }

        protected override void OnFormClosing(FormClosingEventArgs e)
        {
            // Pencereyi kapatmak uygulamayı kapatmasın, bildirim alanında çalışmaya devam etsin
            if (e.CloseReason == CloseReason.UserClosing)
            {
                e.Cancel = true;
                Hide();
            }
            base.OnFormClosing(e);
        }

        protected override void OnHandleCreated(EventArgs e)
        {
            base.OnHandleCreated(e);
            // Koyu temada başlık çubuğunu da koyu yap (Windows 10 20H1+ / 11)
            int v = dark ? 1 : 0;
            DwmSetWindowAttribute(Handle, 20, ref v, sizeof(int));
        }

        [System.Runtime.InteropServices.DllImport("dwmapi.dll")]
        static extern int DwmSetWindowAttribute(IntPtr hwnd, int attr, ref int value, int size);

        static bool IsDarkMode()
        {
            using (var k = Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Themes\Personalize"))
                return k?.GetValue("AppsUseLightTheme") is int v && v == 0;
        }
    }
}
