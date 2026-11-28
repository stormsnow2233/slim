import CommonCrypto
import UIKit

enum JmImageDescrambler {
    private static let scrambleID = 220980

    static func descramble(_ image: UIImage, imageURL: URL) -> UIImage {
        guard image.imageOrientation == .up,
              let source = image.cgImage,
              let (albumID, filename) = imageIdentity(from: imageURL) else { return image }
        let segmentCount = segmentCount(albumID: albumID, filename: filename)
        let width = source.width
        let height = source.height
        guard segmentCount > 1, height >= segmentCount else { return image }

        let segmentHeight = height / segmentCount
        let remainder = height % segmentCount
        var slices: [(CGImage, Int, Int)] = []
        for index in 0..<segmentCount {
            var sliceHeight = segmentHeight
            let sourceY = height - segmentHeight * (index + 1) - remainder
            var destinationY = segmentHeight * index
            if index == 0 {
                sliceHeight += remainder
            } else {
                destinationY += remainder
            }
            guard let slice = source.cropping(to: CGRect(x: 0, y: sourceY, width: width, height: sliceHeight)) else {
                return image
            }
            slices.append((slice, destinationY, sliceHeight))
        }

        let format = UIGraphicsImageRendererFormat()
        format.scale = image.scale
        let renderer = UIGraphicsImageRenderer(size: image.size, format: format)
        return renderer.image { context in
            context.cgContext.interpolationQuality = .none
            for (slice, destinationY, sliceHeight) in slices {
                UIImage(cgImage: slice).draw(in: CGRect(
                    x: 0,
                    y: CGFloat(destinationY) / image.scale,
                    width: image.size.width,
                    height: CGFloat(sliceHeight) / image.scale
                ))
            }
        }
    }

    private static func imageIdentity(from url: URL) -> (Int, String)? {
        let path = url.path
        let nsPath = path as NSString
        guard let regex = try? NSRegularExpression(pattern: #"/media/photos/(\d+)/([^/]+)$"#),
              let match = regex.firstMatch(in: path, range: NSRange(location: 0, length: nsPath.length)),
              let albumID = Int(nsPath.substring(with: match.range(at: 1))) else { return nil }
        let fullFilename = nsPath.substring(with: match.range(at: 2))
        let filename = String(fullFilename[..<(fullFilename.lastIndex(of: ".") ?? fullFilename.endIndex)])
        guard !filename.isEmpty else { return nil }
        return (albumID, filename)
    }

    private static func segmentCount(albumID: Int, filename: String) -> Int {
        guard albumID >= scrambleID else { return 0 }
        if albumID < 268850 { return 10 }
        let modulo = albumID < 421926 ? 10 : 8
        let input = Data("\(albumID)\(filename)".utf8)
        var digest = [UInt8](repeating: 0, count: Int(CC_MD5_DIGEST_LENGTH))
        digest.withUnsafeMutableBufferPointer { output in
            input.withUnsafeBytes { bytes in
                _ = CC_MD5(bytes.baseAddress, CC_LONG(input.count), output.baseAddress)
            }
        }
        let lastHexCharacter = Int(digest[digest.count - 1] & 0x0F)
        let asciiCode = Int(Array("0123456789abcdef")[lastHexCharacter].asciiValue ?? 48)
        return (asciiCode % modulo) * 2 + 2
    }
}
