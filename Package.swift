// swift-tools-version: 5.9

import PackageDescription

// Published binary pin. Advance with the checksum of the next clean archive
// during the two-commit release handoff; source development uses native/ios.
let sfioraVersion = "1.3.0"
let sfioraReleaseBaseURL =
    "https://github.com/sandroxy/sfiora/releases/download/\(sfioraVersion)"
let sfioraBinaryURL = "\(sfioraReleaseBaseURL)/sfiora-\(sfioraVersion).xcframework.zip"
let sfioraBinaryChecksum =
    "53f4cfa959925a55cc5b172227d10a5128823ebce54746c0a228f8a87de2f357"

let package = Package(
    name: "Sfiora",
    platforms: [
        .iOS(.v13)
    ],
    products: [
        .library(
            name: "Sfiora",
            targets: ["Sfiora"]
        )
    ],
    targets: [
        .binaryTarget(
            name: "Sfiora",
            url: sfioraBinaryURL,
            checksum: sfioraBinaryChecksum
        )
    ]
)
