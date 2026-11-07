// Renders the Mac app icon (same glyph as apps/android/src/main/res/drawable/ic_launcher_glyph.xml) into an asset catalog.
// Usage: swift tools/icon/render-mac-icon.swift apps/macos/Bridge/Assets.xcassets/AppIcon.appiconset
import AppKit

let out = URL(fileURLWithPath: CommandLine.arguments[1])
try FileManager.default.createDirectory(at: out, withIntermediateDirectories: true)

func render(_ px: Int) -> Data {
    let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: px, pixelsHigh: px, bitsPerSample: 8, samplesPerPixel: 4,
                               hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
    NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)
    let cg = NSGraphicsContext.current!.cgContext
    let k = CGFloat(px) / 1024
    cg.scaleBy(x: k, y: k)
    // macOS template: 824 pt rounded square centred in 1024, with a soft drop shadow.
    let tile = CGRect(x: 100, y: 100, width: 824, height: 824)
    let shape = CGPath(roundedRect: tile, cornerWidth: 185, cornerHeight: 185, transform: nil)
    cg.saveGState()
    cg.setShadow(offset: CGSize(width: 0, height: -10), blur: 24, color: NSColor.black.withAlphaComponent(0.3).cgColor)
    cg.addPath(shape); cg.setFillColor(NSColor.black.cgColor); cg.fillPath()
    cg.restoreGState()
    cg.saveGState()
    cg.addPath(shape); cg.clip()
    let gradient = CGGradient(colorsSpace: nil, colors: [NSColor(srgbRed: 0x1A / 255.0, green: 0x73 / 255.0, blue: 0xE8 / 255.0, alpha: 1).cgColor,
                                                         NSColor(srgbRed: 0x0B / 255.0, green: 0x3D / 255.0, blue: 0x91 / 255.0, alpha: 1).cgColor] as CFArray,
                              locations: [0, 1])!
    cg.drawLinearGradient(gradient, start: CGPoint(x: 100, y: 924), end: CGPoint(x: 924, y: 100), options: [])
    cg.restoreGState()

    // Glyph in the Android 108-unit space (y down). Its ink spans y 45…76 (the curve peaks halfway to its control point),
    // so it's centred on 60.5, like the Android group's translateY.
    let s: CGFloat = 824 / 82
    func p(_ x: CGFloat, _ y: CGFloat) -> CGPoint { CGPoint(x: 512 + (x - 54) * s, y: 512 - (y - 60.5) * s) }
    cg.setStrokeColor(.white); cg.setFillColor(.white); cg.setLineCap(.round)
    cg.setLineWidth(6 * s)
    cg.move(to: p(33, 64)); cg.addQuadCurve(to: p(75, 64), control: p(54, 26)); cg.strokePath()
    cg.move(to: p(28, 64)); cg.addLine(to: p(80, 64)); cg.strokePath()
    cg.setLineWidth(3 * s)
    for (x, top) in [(43.0, 52.5), (54.0, 45.0), (65.0, 52.5)] { cg.move(to: p(x, top)); cg.addLine(to: p(x, 64)) }
    cg.strokePath()
    cg.setLineWidth(6 * s)
    for x in [36.0, 72.0] { cg.move(to: p(x, 64)); cg.addLine(to: p(x, 76)) }
    cg.strokePath()
    NSGraphicsContext.current = nil
    return rep.representation(using: .png, properties: [:])!
}

var images: [String] = []
for pt in [16, 32, 128, 256, 512] {
    for scale in [1, 2] {
        let name = "icon_\(pt)x\(pt)\(scale == 2 ? "@2x" : "").png"
        try render(pt * scale).write(to: out.appendingPathComponent(name))
        images.append(#"{ "idiom": "mac", "size": "\#(pt)x\#(pt)", "scale": "\#(scale)x", "filename": "\#(name)" }"#)
    }
}
try #"{ "images": [ \#(images.joined(separator: ", ")) ], "info": { "author": "xcode", "version": 1 } }"#
    .write(to: out.appendingPathComponent("Contents.json"), atomically: true, encoding: .utf8)
try #"{ "info": { "author": "xcode", "version": 1 } }"#
    .write(to: out.deletingLastPathComponent().appendingPathComponent("Contents.json"), atomically: true, encoding: .utf8)
print("wrote \(images.count) icons")
