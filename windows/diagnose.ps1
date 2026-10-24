# Hotspot TTL tanı raporu (Windows). İnternet gerektirmez; raporu Masaüstü'ne yazar ve yolunu basar.
# Elle çalıştırma: powershell -ExecutionPolicy Bypass -File diagnose.ps1
$ErrorActionPreference = 'Continue'
[Console]::OutputEncoding = [Text.Encoding]::UTF8
$out = Join-Path ([Environment]::GetFolderPath('Desktop')) ("HotspotTTL-rapor-" + (Get-Date -Format 'yyyyMMdd-HHmmss') + ".txt")
$sb = New-Object Text.StringBuilder

function Section($title, [scriptblock]$body) {
    [void]$sb.AppendLine("`n===== $title =====")
    try { [void]$sb.AppendLine((& $body 2>&1 | Out-String -Width 200).TrimEnd()) }
    catch { [void]$sb.AppendLine("HATA: $_") }
}
function Http($url, $ip) {
    $r = & curl.exe $ip -sS -o NUL -m 10 -w "%{http_code} %{time_total}s" $url 2>&1
    "{0,-4} {1,-40} {2}" -f $ip, $url, $r
}

[void]$sb.AppendLine("Hotspot TTL tanı raporu - $(Get-Date)")
[void]$sb.AppendLine("Windows: $((Get-CimInstance Win32_OperatingSystem).Caption) $([Environment]::OSVersion.Version)")

Section "TTL / hop limit" {
    "IPv4 DefaultHopLimit: $((Get-NetIPv4Protocol).DefaultHopLimit)"
    "IPv6 DefaultHopLimit: $((Get-NetIPv6Protocol).DefaultHopLimit)"
    Get-NetIPInterface -ConnectionState Connected | Sort-Object ifIndex |
        Format-Table ifIndex, InterfaceAlias, AddressFamily, CurrentHopLimit, NlMtu -AutoSize
}
Section "Wi-Fi" { netsh wlan show interfaces }
Section "IP yapılandırması" { ipconfig /all }
Section "Rotalar" { route print -4; Get-NetRoute -AddressFamily IPv6 -DestinationPrefix '::/0' | Format-Table -AutoSize }
Section "DNS sunucuları" { Get-DnsClientServerAddress | Where-Object ServerAddresses | Format-Table InterfaceAlias, AddressFamily, ServerAddresses -AutoSize }
Section "Ping" { ping -n 3 -w 2000 1.1.1.1; ping -n 3 -w 2000 8.8.8.8; ping -6 -n 3 -w 2000 2606:4700:4700::1111 }
Section "Traceroute (ilk 6 atlama)" { tracert -d -h 6 -w 1000 1.1.1.1 }
Section "DNS" {
    Resolve-DnsName www.google.com -Type A -DnsOnly | Format-Table -AutoSize
    Resolve-DnsName www.google.com -Type A -Server 1.1.1.1 -DnsOnly | Format-Table -AutoSize
    Resolve-DnsName www.google.com -Type AAAA -DnsOnly | Format-Table -AutoSize
}
Section "HTTP / HTTPS" {
    foreach ($u in 'http://connectivitycheck.gstatic.com/generate_204', 'https://www.google.com/', 'https://www.youtube.com/', 'https://github.com/', 'https://claude.ai/', 'https://api.anthropic.com/') {
        Http $u '-4'; Http $u '-6'
    }
}
Section "Hız (2 MB)" {
    & curl.exe -4 -sS -o NUL -m 20 -w "%{size_download} bayt, %{speed_download} bayt/sn, %{time_total}s" "https://speed.cloudflare.com/__down?bytes=2000000" 2>&1
}
Section "Kesintisiz indirme (20 sn)" {
    & curl.exe -4 -sS -o NUL -m 20 -w "%{size_download} bayt, %{speed_download} bayt/sn, %{time_total}s" "https://speed.cloudflare.com/__down?bytes=500000000" 2>&1
}

[IO.File]::WriteAllText($out, $sb.ToString(), (New-Object Text.UTF8Encoding $true))
$out
