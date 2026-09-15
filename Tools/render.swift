// README ekran görüntülerini üretir: ./Tools/render.sh
// PanelView'u ekran dışı bir pencerede çizip açık/koyu tema PNG'leri olarak docs/ altına yazar.
import SwiftUI
import AppKit

@main
struct Render {
    @MainActor
    static func main() {
        _ = NSApplication.shared
        let outDir = CommandLine.arguments.dropFirst().first ?? "docs"

        let variants: [(String, Bool, NSAppearance.Name)] = [
            ("panel-on-light", true, .aqua),
            ("panel-off-light", false, .aqua),
            ("panel-on-dark", true, .darkAqua),
            ("panel-off-dark", false, .darkAqua),
        ]

        for (name, on, appearance) in variants {
            let state = TTLState(preview: true)
            state.ipv4 = on ? 65 : 64
            state.ipv6 = on ? 65 : 64
            state.ipv6Off = on

            let shape = RoundedRectangle(cornerRadius: 12)
            let root = PanelView(state: state)
                .background(Color(nsColor: .windowBackgroundColor))
                .clipShape(shape)
                .overlay(shape.strokeBorder(Color(nsColor: .separatorColor), lineWidth: 1))
                .shadow(color: .black.opacity(0.18), radius: 14, y: 6)
                .padding(24) // gölge için boşluk
                .environment(\.colorScheme, appearance == .darkAqua ? .dark : .light)

            let host = NSHostingView(rootView: root)
            host.appearance = NSAppearance(named: appearance)
            host.frame.size = host.fittingSize

            // Etkin olmayan pencerede vurgulu düğmeler gri çizilir; ekran dışında key pencere yapıyoruz
            let window = KeyWindow(contentRect: NSRect(origin: NSPoint(x: -20000, y: -20000), size: host.frame.size),
                                   styleMask: .borderless, backing: .buffered, defer: false)
            window.appearance = NSAppearance(named: appearance)
            window.backgroundColor = .clear
            window.isOpaque = false
            window.contentView = host
            NSApp.activate(ignoringOtherApps: true)
            window.makeKeyAndOrderFront(nil)
            host.layoutSubtreeIfNeeded()
            RunLoop.current.run(until: Date().addingTimeInterval(0.3))
            host.display()

            // 2x çözünürlükte çiz
            let size = host.bounds.size
            let rep = NSBitmapImageRep(bitmapDataPlanes: nil,
                                       pixelsWide: Int(size.width * 2), pixelsHigh: Int(size.height * 2),
                                       bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false,
                                       colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
            rep.size = size
            host.cacheDisplay(in: host.bounds, to: rep)

            let url = URL(fileURLWithPath: outDir).appendingPathComponent("\(name).png")
            try! rep.representation(using: .png, properties: [:])!.write(to: url)
            print("yazıldı: \(url.path)")
            window.orderOut(nil)
        }
    }
}

final class KeyWindow: NSWindow {
    override var canBecomeKey: Bool { true }
    override var canBecomeMain: Bool { true }
}
