<h1 align="center">📡 Hotspot TTL</h1>

<p align="center">
  Telefon hotspot'una bağlıyken Mac trafiğini telefonun kendi trafiği gibi gösteren, menü çubuğunda duran tek tıklık bir macOS uygulaması.
</p>

<p align="center">
  <img alt="macOS 13+" src="https://img.shields.io/badge/macOS-13%2B-black?logo=apple">
  <img alt="Swift" src="https://img.shields.io/badge/Swift-SwiftUI-F05138?logo=swift&logoColor=white">
  <img alt="Bağımlılık yok" src="https://img.shields.io/badge/ba%C4%9F%C4%B1ml%C4%B1l%C4%B1k-yok-brightgreen">
</p>

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/panel-off-dark.png">
    <img alt="Panel: kapalı" src="docs/panel-off-light.png" width="300">
  </picture>
  &nbsp;&nbsp;
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/panel-on-dark.png">
    <img alt="Panel: açık" src="docs/panel-on-light.png" width="300">
  </picture>
</p>

---

## İçindekiler

- [Sorun](#sorun)
- [Çözüm: Aç düğmesi ne yapıyor?](#çözüm-aç-düğmesi-ne-yapıyor)
- [Kurulum](#kurulum)
- [Kullanım](#kullanım)
- [Tanı aracı](#tanı-aracı)
- [Nasıl bulundu?](#nasıl-bulundu)
- [Proje yapısı](#proje-yapısı)
- [Sınırlar](#sınırlar)

## Sorun

Her IP paketinde bir **TTL** (Time To Live) sayacı vardır. Paket bir yönlendiriciden geçtiğinde bu sayı 1 azalır. Telefonlar paketleri `TTL=64` ile gönderir. Hotspot'a bağlı bir bilgisayar da 64 ile gönderir, ama telefon bu paketleri yönlendirirken sayı 1 azalır. Operatör böylece iki farklı değer görür:

```mermaid
flowchart LR
    subgraph T[" "]
        direction LR
        P1["📱 Telefonun kendi trafiği<br/>TTL 64"] -->|"değişmeden"| O1["🏢 Operatör<br/>görür: 64 ✅"]
    end
    subgraph H[" "]
        direction LR
        M1["💻 Mac<br/>TTL 64"] -->|"hotspot"| P2["📱 Telefon<br/>−1"] --> O2["🏢 Operatör<br/>görür: 63 ❌ hotspot!"]
    end
```

Operatör `63` gördüğü bağlantıları hotspot sayıp engelleyebilir. Bu durumda ping ve DNS çalışır ama web sayfaları açılmaz.

Mac paketleri **65** ile gönderirse telefondan çıkışta değer 64 olur ve trafik telefonun kendi trafiğinden ayırt edilemez:

```mermaid
flowchart LR
    M["💻 Mac<br/>TTL 65"] -->|"hotspot"| P["📱 Telefon<br/>−1"] --> O["🏢 Operatör<br/>görür: 64 ✅"]
```

## Çözüm: Aç düğmesi ne yapıyor?

Linux'ta bunun için tek bir `sysctl` ayarı yetiyor:

```ini
net.ipv4.ip_default_ttl=65
net.ipv6.conf.all.hop_limit=65
```

**macOS'ta tek ayar yetmiyor.** Farklı uygulamalar paketleri farklı yollardan oluşturuyor ve her yol ayrı bir düzeltme gerektiriyor:

```mermaid
flowchart TB
    subgraph Apps["Uygulamalar"]
        A1["Brave · Chrome · curl<br/><sub>BSD soketleri</sub>"]
        A2["Safari · App Store · Mail<br/><sub>Network.framework</sub>"]
    end

    A1 --> K["Çekirdek TCP/IP<br/>sysctl net.inet.ip.ttl=65 ①"]
    A2 --> U["Kullanıcı alanı ağ yığını<br/><sub>sysctl TTL'ini <b>yok sayar</b></sub>"]

    K --> V4{"IPv4 mü<br/>IPv6 mı?"}
    U --> V4

    V4 -->|IPv4| PF["pf: scrub out on en0 min-ttl 65 ②<br/><sub>her paketi en az 65'e zorlar</sub>"]
    V4 -->|IPv6| X["❌ Arayüz hop limit'i açılışta 64'e sabit<br/>ndp -i en0 → curhlim=64"]
    X -.->|"③ Wi-Fi IPv6 kapatılır"| PF

    PF --> WIFI["📶 Wi-Fi → 📱 Telefon → 🏢 Operatör<br/><b>TTL 64 ✅</b>"]
```

| # | Adım | Komut | Neden gerekli |
|:-:|------|-------|---------------|
| ① | Çekirdek TTL | `sysctl -w net.inet.ip.ttl=65 net.inet6.ip6.hlim=65` | Brave, Chrome, curl gibi klasik soket kullanan uygulamalar için |
| ② | pf kuralı | `scrub out on en0 all min-ttl 65` | Safari ve Network.framework kullanan uygulamalar paketleri kullanıcı alanında oluşturuyor ve `sysctl` değerini kullanmıyor |
| ③ | IPv6 kapatma | `networksetup -setv6off Wi-Fi` | macOS IPv6 bağlantılarında arayüzün açılışta sabitlenen hop limit'ini (64) kullanıyor, bu değer sonradan değiştirilemiyor |

**Kapat** düğmesi üç adımı da geri alır: TTL 64'e döner, pf anchor'ı (`com.apple/250.HotspotTTL`) temizlenir, IPv6 "Otomatik"e döner. Sistemin kendi pf kurallarına dokunulmaz.

## Kurulum

Gereksinimler: **macOS 13+** ve **Xcode Command Line Tools**. Xcode'un kendisine gerek yok.

```bash
xcode-select --install                      # yoksa
git clone https://github.com/SerhanEnsar/HotspotTTL.git
cd HotspotTTL
./build.sh --install                        # derler ve /Applications'a kopyalar
open /Applications/HotspotTTL.app
```

`./build.sh` tek başına çalıştırılırsa sadece proje klasöründe `HotspotTTL.app` oluşturur.

> Açılışta kendiliğinden başlasın istersen: **Sistem Ayarları → Genel → Giriş Öğeleri** bölümüne `HotspotTTL.app`'i ekle.

## Kullanım

1. Telefonun hotspot'una bağlan.
2. Menü çubuğundaki **📡 anten** ikonuna tıkla ve **Aç**'a bas. macOS yönetici şifresini ya da Touch ID'yi ister.
3. Safari açıksa **⌘Q** ile kapatıp yeniden aç, çünkü ağ değişikliğini fark etmesi gerekiyor.
4. Hotspot'tan ayrılınca **Kapat**'a bas. Yoksa diğer Wi-Fi ağlarında IPv6 kapalı kalır.

| Durum | Menü çubuğu ikonu |
|-------|-------------------|
| Kapalı | `antenna.radiowaves.left.and.right.slash` |
| Açık | `antenna.radiowaves.left.and.right` |

> Ayarlar **kalıcı değildir**. Mac yeniden başlayınca hepsi varsayılana döner. Bu bilerek böyle yapıldı: hotspot kullanmadığın zamanlarda sistem normal çalışır.

## Tanı aracı

Hotspot'ta internet yokken yardım istemek zordur. **Tanı Çalıştır** düğmesi internete ihtiyaç duymadan bir rapor hazırlar ve `~/Desktop/HotspotTTL-rapor-<tarih>.txt` dosyasına yazar. Terminalden `./diagnose.sh` ile de çalıştırılabilir. Yaklaşık 1 dakika sürer.

| Bölüm | Neyi gösterir |
|-------|---------------|
| TTL | `sysctl` değerleri, arayüz `curhlim`, Wi-Fi IPv6 durumu |
| Network.framework testi | Safari'nin kullandığı yığın bağlantı kurabiliyor mu |
| Ağ / DHCP | Ağ geçidi, telefon türü (ör. `ANDROID_METERED`) |
| Ping / Traceroute | Bağlantı IP seviyesinde var mı, nerede kesiliyor |
| DNS | Sistem DNS'i ve `1.1.1.1` ayrı ayrı |
| HTTP / HTTPS | Google, YouTube, GitHub; IPv4 ve IPv6 zorlanarak ayrı ayrı |
| Claude / UDP | claude.ai, API ve QUIC yolu |
| Hız | 2 MB kısa test ve 20 saniyelik kesintisiz indirme (geç devreye giren engeli yakalar) |

**İpucu:** Biri mod **kapalıyken**, biri **açıkken** olmak üzere iki rapor al ve karşılaştır.

## Nasıl bulundu?

Uygulama, gerçek bir Vodafone hattında ve Samsung Android hotspot'unda tanı raporlarıyla adım adım geliştirildi:

```mermaid
timeline
    title Hata ayıklama süreci
    TTL 64 : Ping ve DNS çalışıyor : Bütün TCP bağlantıları zaman aşımına düşüyor
           : Operatör TTL engeli doğrulandı
    sysctl TTL 65 : IPv4 siteleri açıldı
                  : IPv6 siteleri (Google, YouTube) hâlâ kapalı
                  : curhlim=64 tespit edildi
    + IPv6 kapalı : curl ile her şey çalışıyor, 50 MB indirme tamam
                  : Brave açılıyor, Safari açılmıyor
    + pf min-ttl : URLSession testi önce zaman aşımı, sonra 200
                 : Safari dahil hepsi çalışıyor ✅
```

## Proje yapısı

```
HotspotTTL/
├── Sources/main.swift   # SwiftUI MenuBarExtra: panel, sysctl/pf/networksetup çağrıları
├── Info.plist           # LSUIElement: Dock'ta görünmez
├── build.sh             # swiftc ile derleme ve ad-hoc imzalama (Xcode projesi yok)
├── diagnose.sh          # internet gerektirmeyen tanı raporu
├── Tools/
│   ├── render.swift     # README ekran görüntülerini üretir
│   └── render.sh
└── docs/                # panel görselleri (açık/koyu tema)
```

- **Tek dosya, bağımlılık yok.** `swiftc` ile doğrudan derleniyor.
- **Yetki:** root yetkisiyle çalışan ayrı bir yardımcı süreç ya da daemon yok. Her değişiklikte `NSAppleScript` üzerinden `do shell script … with administrator privileges` çağrılıyor ve macOS kendi şifre penceresini gösteriyor.
- **Okuma** (`sysctl -n`, `networksetup -getinfo`) yetki istemiyor. Panel her açıldığında durumu yeniliyor.

README görsellerini yeniden üretmek için:

```bash
./Tools/render.sh
```

## Sınırlar

- **TTL tek tespit yöntemi değildir.** Operatör trafiğin içeriğine de bakabilir (DPI): işletim sistemi güncelleme sunucuları, User-Agent vb.
- **Ayrı hotspot APN'i:** operatör hotspot trafiğini farklı bir APN'den geçiriyorsa bu ayarlar işe yaramaz.
- **Sadece Wi-Fi (`en0`):** USB ya da Bluetooth ile paylaşım için pf kuralında arayüz adı değiştirilmeli.
- **IPv6** mod açıkken tamamen kapalıdır, bütün trafik IPv4'ten gider.
- Hat tarifenizin hotspot koşullarını kontrol edin. Kullanım sorumluluğu size aittir.
