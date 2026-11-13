#!/usr/bin/python3
"""Hotspot TTL: Ubuntu / Linux sürümü.

Telefonun hotspot'una bağlıyken bilgisayarın giden paketlerinin TTL (IPv6'da
hop limit) değerini 65'e çeker. Operatör telefondan çıkan trafiği telefonun
kendi trafiği gibi görür.

Kullanım:
  hotspot-ttl                 pencereli arayüz
  hotspot-ttl --status        durumu göster (root gerekmez)
  sudo hotspot-ttl --on       aç
  sudo hotspot-ttl --off      kapat
  hotspot-ttl --dry-run --on  hiçbir şey değiştirmeden çalıştırılacak komutları yaz

Yöntem:
  1. sysctl: yerel soketlerin varsayılan TTL / hop limit değeri 65 olur.
  2. iptables/ip6tables mangle POSTROUTING: loopback dışındaki her giden paketin
     TTL'i (IPv6'da hop limit) 65'e zorlanır. Uygulama kendi TTL'ini ayarlasa
     ya da IPv6 Router Advertisement hop limit'i 64'e çekse bile son söz bu kuralda.

Ayarlar kalıcı değildir; yeniden başlatınca sıfırlanır. Kapatınca eski değerler
/var/lib/hotspot-ttl/state.json dosyasından geri yüklenir.
"""

import argparse
import json
import os
import queue
import shutil
import subprocess
import sys
import threading

TTL = 65
COMMENT = "hotspot-ttl"
STATE_DIR = "/var/lib/hotspot-ttl"
STATE_FILE = os.path.join(STATE_DIR, "state.json")
SYSCTL_KEYS = (
    "net.ipv4.ip_default_ttl",
    "net.ipv6.conf.all.hop_limit",
    "net.ipv6.conf.default.hop_limit",
)
# (komut, mangle POSTROUTING kuralı)
RULES = (
    ("iptables", ["!", "-o", "lo", "-m", "comment", "--comment", COMMENT,
                  "-j", "TTL", "--ttl-set", str(TTL)]),
    ("ip6tables", ["!", "-o", "lo", "-m", "comment", "--comment", COMMENT,
                   "-j", "HL", "--hl-set", str(TTL)]),
)
ENV = {"PATH": "/usr/sbin:/sbin:/usr/bin:/bin", "LC_ALL": "C"}
SELF = os.path.abspath(__file__)

DRY_RUN = False


class Error(Exception):
    pass


def _exec(args, write=False):
    """Komutu çalıştırır. DRY_RUN iken yazma komutları sadece yazdırılır."""
    if write and DRY_RUN:
        print("+ " + " ".join(args))
        return 0, "", ""
    try:
        res = subprocess.run(args, capture_output=True, text=True, env=ENV)
    except FileNotFoundError:
        return 127, "", f"{args[0]} bulunamadı"
    return res.returncode, res.stdout, res.stderr


def write(args):
    rc, out, err = _exec(args, write=True)
    if rc != 0:
        raise Error(f"Komut başarısız: {' '.join(args)}\n{(err or out).strip()}")


def read(args):
    rc, out, _ = _exec(args)
    return out.strip() if rc == 0 else None


def is_root():
    return os.geteuid() == 0


def require_root():
    if not DRY_RUN and not is_root():
        raise Error("Yönetici yetkisi gerekli: sudo hotspot-ttl --on")


def load_state():
    try:
        with open(STATE_FILE) as f:
            return json.load(f)
    except (OSError, ValueError):
        return None


def save_state(state):
    os.makedirs(STATE_DIR, mode=0o755, exist_ok=True)
    tmp = STATE_FILE + ".tmp"
    with open(tmp, "w") as f:
        json.dump(state, f)
    os.replace(tmp, STATE_FILE)


def sysctl_get(key):
    return read(["sysctl", "-n", key])


def sysctl_set(key, value):
    write(["sysctl", "-q", "-w", f"{key}={value}"])


def _rule_args(action, cmd, spec):
    return [cmd, "-t", "mangle", action, "POSTROUTING", *spec]


def rule_present(cmd, spec):
    if not shutil.which(cmd, path=ENV["PATH"]):
        return False
    return _exec(_rule_args("-C", cmd, spec))[0] == 0


def add_rules():
    for cmd, spec in RULES:
        if not shutil.which(cmd, path=ENV["PATH"]):
            raise Error(f"{cmd} bulunamadı. Kur: sudo apt install iptables")
        if not rule_present(cmd, spec):
            write(_rule_args("-A", cmd, spec))


def remove_rules():
    for cmd, spec in RULES:
        # Aynı kuralın birden fazla eklenmiş olma ihtimaline karşı sınırlı döngü.
        for _ in range(1 if DRY_RUN else 20):
            if not rule_present(cmd, spec):
                break
            write(_rule_args("-D", cmd, spec))


def turn_on():
    require_root()
    state = load_state()
    if state is None:
        # Eski değerleri kurallar değişmeden önce kaydet: kapatırken geri dönebilelim.
        state = {"sysctl": {key: sysctl_get(key) for key in SYSCTL_KEYS}}
        if not DRY_RUN:
            save_state(state)
    try:
        for key in SYSCTL_KEYS:
            if state["sysctl"].get(key) is not None:
                sysctl_set(key, TTL)
        add_rules()
    except Error:
        try:
            turn_off()
        except Error:
            pass
        raise


def turn_off():
    require_root()
    remove_rules()
    state = load_state()
    if state is not None:
        for key, value in state["sysctl"].items():
            if value is not None:
                sysctl_set(key, value)
        if not DRY_RUN:
            os.remove(STATE_FILE)


def status_text():
    lines = []
    state = load_state()
    lines.append("Durum: " + ("AÇIK" if state is not None else "KAPALI"))
    for key in SYSCTL_KEYS:
        lines.append(f"  {key} = {sysctl_get(key) or '?'}")
    if is_root():
        for cmd, spec in RULES:
            lines.append(f"  {cmd} kuralı: {'var' if rule_present(cmd, spec) else 'yok'}")
    else:
        lines.append("  (kural durumu için sudo gerekir)")
    return "\n".join(lines)


def gui():
    try:
        import tkinter as tk
        from tkinter import scrolledtext, ttk
    except ImportError:
        print("Pencere için python3-tk gerekli: sudo apt install python3-tk", file=sys.stderr)
        return 1

    root = tk.Tk()
    root.title("Hotspot TTL")
    root.resizable(False, False)
    frm = ttk.Frame(root, padding=16)
    frm.grid(sticky="nsew")

    ttk.Label(frm, text="Hotspot TTL", font=("Sans", 14, "bold")).grid(
        row=0, column=0, columnspan=3, sticky="w")
    ttk.Label(frm, text="Telefon hotspot'unda operatör engelini aşmak için TTL 65.",
              wraplength=460).grid(row=1, column=0, columnspan=3, sticky="w", pady=(0, 12))
    status_var = tk.StringVar()
    ttk.Label(frm, textvariable=status_var, font=("Monospace", 10), justify="left").grid(
        row=2, column=0, columnspan=3, sticky="w")

    log = scrolledtext.ScrolledText(frm, width=64, height=10, state="disabled",
                                    font=("Monospace", 9))
    log.grid(row=4, column=0, columnspan=3, sticky="nsew", pady=(8, 0))
    results = queue.Queue()
    buttons = []

    def write_log(text):
        log.configure(state="normal")
        log.insert("end", text.rstrip() + "\n")
        log.see("end")
        log.configure(state="disabled")

    def refresh():
        status_var.set(status_text())

    def busy(flag):
        for b in buttons:
            b.configure(state="disabled" if flag else "normal")

    def poll():
        try:
            rc, text = results.get_nowait()
        except queue.Empty:
            root.after(100, poll)
            return
        if text.strip():
            write_log(text)
        if rc == 0:
            write_log("Tamam.")
        elif rc in (126, 127):
            write_log("Yetki verilmedi veya işlem iptal edildi.")
        else:
            write_log(f"Hata (çıkış kodu {rc}).")
        busy(False)
        refresh()

    def action(flag):
        busy(True)
        cmd = [SELF, flag]
        if not is_root():
            cmd = ["pkexec", *cmd]

        def worker():
            try:
                res = subprocess.run(cmd, capture_output=True, text=True)
                results.put((res.returncode, res.stdout + res.stderr))
            except OSError as e:
                results.put((1, str(e)))

        threading.Thread(target=worker, daemon=True).start()
        poll()

    btn_on = ttk.Button(frm, text="Aç", command=lambda: action("--on"))
    btn_off = ttk.Button(frm, text="Kapat", command=lambda: action("--off"))
    btn_refresh = ttk.Button(frm, text="Yenile", command=refresh)
    buttons.extend([btn_on, btn_off, btn_refresh])
    for col, b in enumerate(buttons):
        b.grid(row=3, column=col, sticky="ew", pady=(12, 0), padx=4)

    write_log("Aç ve Kapat yönetici yetkisi ister (pkexec).")
    refresh()
    root.mainloop()
    return 0


def main(argv=None):
    global DRY_RUN
    parser = argparse.ArgumentParser(prog="hotspot-ttl", description="Hotspot TTL (Linux)")
    group = parser.add_mutually_exclusive_group()
    group.add_argument("--on", action="store_true", help="TTL ve hop limit'i 65 yap")
    group.add_argument("--off", action="store_true", help="ayarları eski değerlerine döndür")
    group.add_argument("--status", action="store_true", help="durumu göster")
    parser.add_argument("--dry-run", action="store_true",
                        help="hiçbir şey değiştirmeden çalıştırılacak komutları yazdır")
    args = parser.parse_args(argv)
    DRY_RUN = args.dry_run

    if not (args.on or args.off or args.status):
        return gui()

    try:
        if args.on:
            turn_on()
            print("Açıldı: TTL ve hop limit 65.")
        elif args.off:
            turn_off()
            print("Kapatıldı: ayarlar eski değerlerine döndü.")
        else:
            print(status_text())
    except Error as e:
        print(f"Hata: {e}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
