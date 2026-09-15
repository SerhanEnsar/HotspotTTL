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

    /// Yönetici yetkisiyle TTL ve IPv6 hop limit değerlerini yazar. Hata durumunda mesaj döner.
    static func write(_ value: Int) -> String? {
        let cmd = "/usr/sbin/sysctl -w net.inet.ip.ttl=\(value) net.inet6.ip6.hlim=\(value)"
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
    @Published var busy = false
    @Published var message: String?

    var isOn: Bool { ipv4 == TTL.spoofed && ipv6 == TTL.spoofed }

    init() { refresh() }

    func refresh() {
        ipv4 = TTL.read("net.inet.ip.ttl")
        ipv6 = TTL.read("net.inet6.ip6.hlim")
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
                    Text(state.isOn ? "Aktif: TTL 65" : "Kapalı: varsayılan 64")
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
            }
            .padding(10)
            .background(.quaternary.opacity(0.5), in: RoundedRectangle(cornerRadius: 8))

            if let msg = state.message {
                Text(msg).font(.caption).foregroundStyle(.red)
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
