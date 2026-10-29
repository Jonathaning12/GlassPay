import AVFoundation
import SwiftUI

// Equivalente iOS de CameraScanner.kt (CameraX + ML Kit). AVFoundation ya lee QR sin dependencias.
// Solo entrega tokens con prefijo "glasspay:"; los demás códigos se ignoran sin llamar al servidor.

struct QRScannerView: UIViewRepresentable {
    let onToken: (String) -> Void

    func makeUIView(context: Context) -> ScannerPreview {
        let view = ScannerPreview()
        view.onToken = onToken
        view.start()
        return view
    }

    func updateUIView(_ uiView: ScannerPreview, context: Context) {
        uiView.onToken = onToken
    }

    static func dismantleUIView(_ uiView: ScannerPreview, coordinator: ()) {
        uiView.stop()
    }

    static var cameraAuthorized: Bool { AVCaptureDevice.authorizationStatus(for: .video) == .authorized }

    static func requestCamera() async -> Bool {
        await AVCaptureDevice.requestAccess(for: .video)
    }
}

final class ScannerPreview: UIView, AVCaptureMetadataOutputObjectsDelegate {
    var onToken: (String) -> Void = { _ in }
    private let session = AVCaptureSession()
    private let sessionQueue = DispatchQueue(label: "glasspay.camera")

    override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
    private var previewLayer: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }

    func start() {
        previewLayer.session = session
        previewLayer.videoGravity = .resizeAspectFill
        sessionQueue.async { [weak self] in
            guard let self else { return }
            self.session.beginConfiguration()
            if self.session.inputs.isEmpty,
               let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
               let input = try? AVCaptureDeviceInput(device: device),
               self.session.canAddInput(input) {
                self.session.addInput(input)
                let output = AVCaptureMetadataOutput()
                if self.session.canAddOutput(output) {
                    self.session.addOutput(output)
                    output.setMetadataObjectsDelegate(self, queue: .main)
                    output.metadataObjectTypes = [.qr]
                }
            }
            self.session.commitConfiguration()
            self.session.startRunning()
        }
    }

    func stop() {
        sessionQueue.async { [session] in
            if session.isRunning { session.stopRunning() }
        }
    }

    func metadataOutput(
        _ output: AVCaptureMetadataOutput,
        didOutput metadataObjects: [AVMetadataObject],
        from connection: AVCaptureConnection
    ) {
        for object in metadataObjects {
            guard let code = object as? AVMetadataMachineReadableCodeObject,
                  let token = QrTokenParser.extractToken(code.stringValue) else { continue }
            onToken(token)
            return
        }
    }
}
