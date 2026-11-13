#!/bin/bash
# Ubuntu/Debian .deb paketi üretir (dpkg-deb gerekir).
# Kullanım: linux/build-deb.sh <sürüm>      örn. linux/build-deb.sh 1.2
set -euo pipefail
cd "$(dirname "$0")"

VERSION="${1:?Kullanım: $0 <sürüm>}"
PKG="hotspot-ttl_${VERSION}_all"
WORK="$(mktemp -d)"
ROOT="$WORK/$PKG"
OUT="$PWD/dist"
trap 'rm -rf "$WORK"' EXIT

install -Dm755 hotspot_ttl.py "$ROOT/usr/bin/hotspot-ttl"
install -Dm644 hotspot-ttl.desktop "$ROOT/usr/share/applications/hotspot-ttl.desktop"
install -Dm644 com.serhanensar.hotspotttl.policy \
  "$ROOT/usr/share/polkit-1/actions/com.serhanensar.hotspotttl.policy"
install -Dm644 ../LICENSE "$ROOT/usr/share/doc/hotspot-ttl/copyright"

SIZE_KB="$(du -sk "$ROOT" | cut -f1)"
mkdir -p "$ROOT/DEBIAN"
cat > "$ROOT/DEBIAN/control" <<EOF
Package: hotspot-ttl
Version: $VERSION
Section: net
Priority: optional
Architecture: all
Installed-Size: $SIZE_KB
Depends: python3, python3-tk, iptables, policykit-1, procps
Maintainer: SerhanEnsar
Homepage: https://github.com/SerhanEnsar/HotspotTTL
Description: Telefon hotspot'unda giden paketlerin TTL değerini 65 yapar
 Telefonun hotspot'una bağlı bilgisayarın trafiğini operatörün hotspot
 tespitinden ayırmak için sysctl ve iptables (TTL / HL hedefi) kurallarını
 ayarlar. Kapatınca ayarlar eski değerlerine döner.
EOF

mkdir -p "$OUT"
dpkg-deb --root-owner-group --build "$ROOT" "$OUT/$PKG.deb"
echo "Oluşturuldu: $OUT/$PKG.deb"
