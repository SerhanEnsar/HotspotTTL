#!/bin/bash
# Hotspot bağlantı tanısı. İnternet gerektirmez; sonuçları Masaüstü'ne rapor olarak yazar.
# Kullanım: ./diagnose.sh   (veya uygulamadaki "Tanı Çalıştır" butonu)

OUT="$HOME/Desktop/HotspotTTL-rapor-$(date +%Y%m%d-%H%M%S).txt"
T=6  # komut başına zaman aşımı (sn)

section() { printf '\n===== %s =====\n' "$1"; }
run() { printf '$ %s\n' "$*"; "$@" 2>&1; printf '\n'; }
# macOS'ta timeout yok; arka planda çalıştırıp süre dolunca öldür
tmo() {
  "$@" 2>&1 & local pid=$!
  ( sleep "$T"; kill -9 $pid 2>/dev/null ) 2>/dev/null & local w=$!
  disown $w
  wait $pid 2>/dev/null; kill $w 2>/dev/null
}

{
  echo "HotspotTTL tanı raporu - $(date)"
  echo "macOS $(sw_vers -productVersion)"

  section "TTL"
  run sysctl net.inet.ip.ttl net.inet6.ip6.hlim
  echo "--- IPv6 TCP'nin fiilen kullandığı arayüz hop limit'i"
  ndp -i en0 2>&1 | head -1
  networksetup -getinfo Wi-Fi 2>&1 | grep -E "^IPv6:"

  section "Ağ arayüzü / rota"
  run route -n get default
  GW=$(route -n get default 2>/dev/null | awk '/gateway:/{print $2}')
  IF=$(route -n get default 2>/dev/null | awk '/interface:/{print $2}')
  run ifconfig "${IF:-en0}"
  run networksetup -listallhardwareports
  echo "--- DHCP"
  ipconfig getpacket "${IF:-en0}" 2>&1 | grep -vE "chaddr|sname|file" | head -30
  echo "--- IPv6 adres / varsayılan rota"
  ifconfig "${IF:-en0}" | grep inet6
  netstat -rn -f inet6 | grep -E "^default"

  section "DNS ayarları"
  scutil --dns | grep -E "nameserver|if_index" | head -10

  section "Ağ geçidine ping (yanıt TTL'i telefon türünü gösterir)"
  [ -n "$GW" ] && tmo ping -c 3 -W 1000 "$GW"

  section "İnternete ping (IP ile, DNS'siz)"
  tmo ping -c 4 -W 1000 1.1.1.1
  tmo ping -c 4 -W 1000 8.8.8.8
  echo "--- IPv6"
  tmo ping6 -c 3 2606:4700:4700::1111

  section "Traceroute (ilk 6 atlama)"
  tmo traceroute -n -m 6 -w 1 -q 1 1.1.1.1

  section "DNS çözümleme"
  for d in google.com vodafone.com.tr apple.com; do
    echo "--- $d (sistem DNS)"; tmo dig +short +time=2 +tries=1 "$d"
    echo "--- $d (@1.1.1.1)";    tmo dig +short +time=2 +tries=1 @1.1.1.1 "$d"
  done

  section "HTTP / HTTPS"
  for u in http://captive.apple.com/hotspot-detect.html http://neverssl.com \
           https://www.google.com https://1.1.1.1 https://www.youtube.com https://github.com; do
    curl -sS -m "$T" -o /dev/null \
      -w "$u -> kod=%{http_code} ip=%{remote_ip} süre=%{time_total}s yönlendirme=%{redirect_url}\n" "$u" 2>&1
  done
  echo "--- IPv4 zorla / IPv6 zorla"
  curl -4 -sS -m "$T" -o /dev/null -w "ipv4 kod=%{http_code} süre=%{time_total}s\n" https://www.google.com 2>&1
  curl -6 -sS -m "$T" -o /dev/null -w "ipv6 kod=%{http_code} süre=%{time_total}s\n" https://www.google.com 2>&1
  echo "--- Operatör yönlendirme sayfası var mı? (HTTP başlıkları + gövde başı)"
  curl -sS -m "$T" -i http://neverssl.com 2>&1 | head -25

  section "Claude / Anthropic"
  for u in https://claude.ai https://api.anthropic.com https://statsig.anthropic.com; do
    curl -sS -m "$T" -o /dev/null -w "$u -> kod=%{http_code} ip=%{remote_ip} süre=%{time_total}s\n" "$u" 2>&1
  done

  section "UDP (QUIC/DNS yolu)"
  echo "--- UDP 53 üzerinden harici DNS"
  tmo dig +short +time=2 +tries=1 @8.8.8.8 youtube.com
  echo "--- UDP 443 (QUIC) - nc ile paket gönderimi"
  tmo nc -u -z -w 2 142.250.187.110 443 && echo "gönderildi"

  section "Hız (2 MB)"
  curl -sS -m 15 -o /dev/null -w "indirme=%{speed_download} B/s süre=%{time_total}s\n" \
    'https://speed.cloudflare.com/__down?bytes=2000000' 2>&1

  section "Uzun indirme (20 sn) - geç devreye giren engel var mı?"
  curl -sS -m 20 -o /dev/null -w "toplam=%{size_download} B ort=%{speed_download} B/s süre=%{time_total}s\n" \
    'https://speed.cloudflare.com/__down?bytes=200000000' 2>&1
  echo "--- 20 sn sonra tekrar kısa test"
  curl -sS -m "$T" -o /dev/null -w "google kod=%{http_code} süre=%{time_total}s\n" https://www.google.com 2>&1

  section "Proxy / tarayıcı"
  scutil --proxy 2>&1 | grep -E "Enable|Proxy :"
  pgrep -lf "Google Chrome.app/Contents/MacOS" | head -1

  echo; echo "Bitti."
} > "$OUT" 2>&1

echo "$OUT"
