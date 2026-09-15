# Hotspot TTL

Telefon hotspot'u üzerinden internete çıkarken Mac'in TTL değerini tek tıkla 65'e çeken, menü çubuğunda duran küçük bir macOS uygulaması.

## Neden 65?

Telefonlar paketleri TTL=64 ile gönderir. Mac de 64 gönderir, ama paket telefondan geçerken TTL 1 düşer ve operatöre 63 olarak ulaşır. Operatör bu farktan trafiğin hotspot üzerinden geldiğini anlayabilir. Mac 65 gönderirse telefon çıkışında değer 64 olur ve trafik telefonun kendi trafiği gibi görünür.

Linux'taki karşılığı:

```
net.ipv4.ip_default_ttl=65
net.ipv6.conf.all.hop_limit=65
net.ipv6.conf.default.hop_limit=65
```

macOS'ta ayarlanan değerler:

```
net.inet.ip.ttl=65
net.inet6.ip6.hlim=65
```

## Kurulum

```bash
./build.sh            # HotspotTTL.app oluşturur
./build.sh --install  # /Applications'a da kopyalar
open HotspotTTL.app
```

Gereksinim: macOS 13+ ve Xcode Command Line Tools (`xcode-select --install`).

## Kullanım

- Menü çubuğundaki anten ikonuna tıkla, açılan panelden **Aç** ya da **Kapat**'a bas.
- Değer değişirken macOS yönetici şifresini (veya Touch ID) ister.
- Ayar kalıcı değildir. Mac yeniden başlatılınca 64'e döner, ihtiyaç olduğunda tekrar açılır.

## Tanı (internet yokken)

Hotspot'ta sayfalar açılmıyorsa panelde **Tanı Çalıştır**'a bas (veya `./diagnose.sh`).
Yaklaşık 30–60 sn sürer; TTL, rota, DNS, ping, traceroute, HTTP/HTTPS ve hız testlerini
`~/Desktop/HotspotTTL-rapor-<tarih>.txt` dosyasına yazar. İnternet gerektirmez.

En faydalısı iki rapor almaktır: biri TTL **kapalıyken**, biri **açıkken**.

## Sınırlar

- TTL tek tespit yöntemi değildir. Operatör DPI ile de tespit yapabilir (işletim sistemi güncelleme trafiği, User-Agent vb.).
- Operatör hotspot trafiğini ayrı bir APN'e yönlendiriyorsa TTL ayarı etkisiz kalır.
- Hat tarifenizin hotspot koşullarını kontrol edin.
