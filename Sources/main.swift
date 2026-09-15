import SwiftUI
import AppKit

// MARK: - sysctl erişimi

enum TTL {
    static let spoofed = 65
    static let normal = 64

    static func read(_ key: String) -> Int? {
        let task = Process()
        task.executableURL = URL(fileURLWithPath: "/usr/sbin/sysctl")
        task.arguments = ["-n", key]
        let pipe = Pipe()
        task.standardOutput = pipe
        task.standardError = Pipe()
        do {
            try task.run()
            task.waitUntilExit()
        } catch {
            return nil
        }
        let data = pipe.fileHandleForReading.readDataToEndOfFile()
        let text = String(data: data, encoding: .utf8)?.trimmingCharacters(in: .whitespacesAndNewlines)
        return text.flatMap(Int.init)
    }

    /// Wi-Fi servisinde IPv6 kapalı mı ("IPv6: Off")
    static func ipv6Disabled() -> Bool {
        let task = Process()
        task.executableURL = URL(fileURLWithPath: "/usr/sbin/networksetup")
        task.arguments = ["-getinfo", "Wi-Fi"]
        let pipe = Pipe()
        task.standardOutput = pipe
        task.standardError = Pipe()
        guard (try? task.run()) != nil else { return false }
        task.waitUntilExit()
        let out = String(data: pipe.fileHandleForReading.readDataToEndOfFile(), encoding: .utf8) ?? ""
        return out.contains("IPv6: Off")
    }

    /// Yönetici yetkisiyle TTL ve IPv6 hop limit değerlerini yazar. Hata durumunda mesaj döner.
    /// macOS IPv6 TCP'de arayüzün açılışta sabitlenen hop limit'ini (ndp curhlim=64) kullandığı için
    /// sysctl hlim yetmez; mod açıkken Wi-Fi'da IPv6 kapatılır, trafik IPv4'ten gider.
    /// Safari/URLSession gibi Network.framework kullanan uygulamalar paketlerini kullanıcı alanında
    /// oluşturup sysctl TTL'ini yok saydığı için ayrıca pf ile Wi-Fi'dan çıkan her pakete min-ttl 65 zorlanır.
    static let pfAnchor = "com.apple/250.HotspotTTL"

    static func write(_ value: Int) -> String? {
        let on = value == spoofed
        let v6 = on ? "-setv6off Wi-Fi" : "-setv6automatic Wi-Fi"
        let pf = on
            ? "echo 'scrub out on en0 all min-ttl \(spoofed)' | /sbin/pfctl -a \(pfAnchor) -f - 2>/dev/null; /sbin/pfctl -e 2>/dev/null"
            : "/sbin/pfctl -a \(pfAnchor) -F all 2>/dev/null"
        let cmd = "/usr/sbin/sysctl -w net.inet.ip.ttl=\(value) net.inet6.ip6.hlim=\(value); /usr/sbin/networksetup \(v6); \(pf); true"
        let source = "do shell script \"\(cmd)\" with administrator privileges"
        var error: NSDictionary?
        NSAppleScript(source: source)?.executeAndReturnError(&error)
        guard let error else { return nil }
        // -128: kullanıcı şifre penceresini iptal etti
        if (error[NSAppleScript.errorNumber] as? Int) == -128 { return "İptal edildi" }
        return error[NSAppleScript.errorMessage] as? String ?? "Bilinmeyen hata"
    }
}

// MARK: - Durum

@MainActor
final class TTLState: ObservableObject {
    @Published var ipv4: Int?
    @Published var ipv6: Int?
    @Published var ipv6Off = false
    @Published var busy = false
    @Published var message: String?
    @Published var diagnosing = false

    var isOn: Bool { ipv4 == TTL.spoofed && ipv6Off }

    init() { refresh() }

    func refresh() {
        ipv4 = TTL.read("net.inet.ip.ttl")
        ipv6 = TTL.read("net.inet6.ip6.hlim")
        ipv6Off = TTL.ipv6Disabled()
    }

    func set(_ on: Bool) {
        busy = true
        message = nil
        let value = on ? TTL.spoofed : TTL.normal
        Task.detached {
            let err = TTL.write(value)
            await MainActor.run {
                self.message = err
                self.busy = false
                self.refresh()
            }
        }
    }

    /// Paketlenmiş diagnose.sh'i çalıştırır, oluşan raporu Finder'da gösterir.
    func diagnose() {
        guard let script = Bundle.main.path(forResource: "diagnose", ofType: "sh") else {
            message = "diagnose.sh bulunamadı"
            return
        }
        diagnosing = true
        message = nil
        Task.detached {
            let task = Process()
            task.executableURL = URL(fileURLWithPath: "/bin/bash")
            task.arguments = [script]
            let pipe = Pipe()
            task.standardOutput = pipe
            try? task.run()
            task.waitUntilExit()
            let path = String(data: pipe.fileHandleForReading.readDataToEndOfFile(), encoding: .utf8)?
                .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            await MainActor.run {
                self.diagnosing = false
                if FileManager.default.fileExists(atPath: path) {
                    self.message = "Rapor Masaüstü'ne kaydedildi"
                    NSWorkspace.shared.activateFileViewerSelecting([URL(fileURLWithPath: path)])
                } else {
                    self.message = "Tanı başarısız"
                }
            }
        }
    }
}

// MARK: - Arayüz

struct PanelView: View {
    @ObservedObject var state: TTLState

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack {
                Image(systemName: state.isOn ? "antenna.radiowaves.left.and.right" : "antenna.radiowaves.left.and.right.slash")
                    .font(.title2)
                    .foregroundStyle(state.isOn ? .green : .secondary)
                VStack(alignment: .leading, spacing: 2) {
                    Text("Hotspot TTL").font(.headline)
                    Text(state.isOn ? "Aktif: TTL 65, IPv6 kapalı" : "Kapalı: varsayılan")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Spacer()
                if state.busy { ProgressView().controlSize(.small) }
            }

            Button {
                state.set(!state.isOn)
            } label: {
                Text(state.isOn ? "Kapat" : "Aç")
                    .font(.headline)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 6)
            }
            .buttonStyle(.borderedProminent)
            .tint(state.isOn ? .red : .green)
            .disabled(state.busy)

            VStack(spacing: 6) {
                row("IPv4 TTL", state.ipv4)
                row("IPv6 hop limit", state.ipv6)
                HStack {
                    Text("Wi-Fi IPv6").foregroundStyle(.secondary)
                    Spacer()
                    Text(state.ipv6Off ? "Kapalı" : "Açık")
                        .fontWeight(.semibold)
                        .foregroundStyle(state.ipv6Off ? .green : .primary)
                }
                .font(.callout)
            }
            .padding(10)
            .background(.quaternary.opacity(0.5), in: RoundedRectangle(cornerRadius: 8))

            Button {
                state.diagnose()
            } label: {
                HStack {
                    if state.diagnosing { ProgressView().controlSize(.mini) }
                    Text(state.diagnosing ? "Tanı sürüyor (~1 dk)…" : "Tanı Çalıştır")
                }
                .frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
            .disabled(state.diagnosing)

            if let msg = state.message {
                Text(msg).font(.caption)
                    .foregroundStyle(msg.hasPrefix("Rapor") ? Color.secondary : Color.red)
            }

            Divider()

            HStack {
                Button("Yenile") { state.refresh() }
                Spacer()
                Button("Çıkış") { NSApp.terminate(nil) }
            }
            .buttonStyle(.borderless)
        }
        .padding(16)
        .frame(width: 260)
        .onAppear { state.refresh() }
    }

    private func row(_ title: String, _ value: Int?) -> some View {
        HStack {
            Text(title).foregroundStyle(.secondary)
            Spacer()
            Text(value.map(String.init) ?? "—")
                .monospacedDigit()
                .fontWeight(.semibold)
                .foregroundStyle(value == TTL.spoofed ? .green : .primary)
        }
        .font(.callout)
    }
}

@main
struct HotspotTTLApp: App {
    @StateObject private var state = TTLState()

    var body: some Scene {
        MenuBarExtra {
            PanelView(state: state)
        } label: {
            Image(systemName: state.isOn ? "antenna.radiowaves.left.and.right" : "antenna.radiowaves.left.and.right.slash")
        }
        .menuBarExtraStyle(.window)
    }
}
