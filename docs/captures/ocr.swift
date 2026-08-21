import Foundation
import Vision
import AppKit

for path in CommandLine.arguments.dropFirst() {
    guard let img = NSImage(contentsOfFile: path),
          let cg = img.cgImage(forProposedRect: nil, context: nil, hints: nil) else {
        print("\(path)\t<로드실패>"); continue
    }
    let req = VNRecognizeTextRequest()
    req.recognitionLevel = .accurate
    req.recognitionLanguages = ["ko-KR", "en-US"]
    req.usesLanguageCorrection = false
    let handler = VNImageRequestHandler(cgImage: cg, options: [:])
    try? handler.perform([req])
    let lines = (req.results ?? []).compactMap { $0.topCandidates(1).first?.string }
    // "question": "..." 이 들어간 줄만 뽑는다
    let hit = lines.first { $0.contains("question") && $0.contains(":") }
        ?? lines.first { $0.contains("?") } ?? "<질문 못 찾음>"
    let route = lines.first { $0.contains("route") } ?? ""
    print("\((path as NSString).lastPathComponent)\t\(hit)\t\(route)")
}
