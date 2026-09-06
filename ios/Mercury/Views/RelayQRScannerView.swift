import AVFoundation
import SwiftUI
import UIKit

/// AVFoundation QR scanner (Apple frameworks only, per the project's
/// zero-third-party-dependency rule). Calls `onScan` once with the first
/// decoded QR string; the caller owns validation and never re-displays the
/// payload.
///
/// Camera authorization is requested lazily on first appearance. On denial,
/// simulators, or hardware without a usable camera, `onUnavailable` fires so
/// the host view can steer the user to the paste field instead of leaving a
/// silent black rectangle.
struct RelayQRScannerView: UIViewControllerRepresentable {
    let onScan: (String) -> Void
    var onUnavailable: (String) -> Void = { _ in }

    func makeUIViewController(context: Context) -> RelayQRScannerController {
        let controller = RelayQRScannerController()
        controller.onScan = onScan
        controller.onUnavailable = onUnavailable
        return controller
    }

    func updateUIViewController(_ controller: RelayQRScannerController, context: Context) {}
}

final class RelayQRScannerController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
    var onScan: ((String) -> Void)?
    var onUnavailable: ((String) -> Void)?

    private let session = AVCaptureSession()
    private var previewLayer: AVCaptureVideoPreviewLayer?
    private var delivered = false
    private var configured = false
    private var statusLabel: UILabel?

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        showStatus("Starting camera…")
        requestAccessThenConfigure()
    }

    // MARK: - Authorization

    private func requestAccessThenConfigure() {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            configureSession()
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
                DispatchQueue.main.async {
                    if granted {
                        self?.configureSession()
                    } else {
                        self?.fail("Camera access is off. Paste the pairing code below, or enable the camera for Mercury in Settings.")
                    }
                }
            }
        case .denied, .restricted:
            fail("Camera access is off. Paste the pairing code below, or enable the camera for Mercury in Settings.")
        @unknown default:
            fail("The camera is unavailable. Paste the pairing code below.")
        }
    }

    // MARK: - Session

    private func configureSession() {
        guard !configured else { return }
        guard let device = AVCaptureDevice.default(for: .video),
              let input = try? AVCaptureDeviceInput(device: device),
              session.canAddInput(input)
        else {
            fail("The camera is unavailable. Paste the pairing code below.")
            return
        }
        session.beginConfiguration()
        session.addInput(input)

        let output = AVCaptureMetadataOutput()
        guard session.canAddOutput(output) else {
            session.commitConfiguration()
            fail("The camera is unavailable. Paste the pairing code below.")
            return
        }
        session.addOutput(output)
        output.setMetadataObjectsDelegate(self, queue: .main)
        guard output.availableMetadataObjectTypes.contains(.qr) else {
            session.commitConfiguration()
            fail("This device can't scan QR codes. Paste the pairing code below.")
            return
        }
        output.metadataObjectTypes = [.qr]
        session.commitConfiguration()

        let layer = AVCaptureVideoPreviewLayer(session: session)
        layer.videoGravity = .resizeAspectFill
        layer.frame = view.layer.bounds
        view.layer.insertSublayer(layer, at: 0)
        previewLayer = layer
        configured = true

        hideStatus()
        startSession()
    }

    private func startSession() {
        guard configured, !session.isRunning else { return }
        DispatchQueue.global(qos: .userInitiated).async { [session] in
            session.startRunning()
        }
    }

    // MARK: - Status overlay

    private func showStatus(_ text: String) {
        if statusLabel == nil {
            let label = UILabel()
            label.textColor = .secondaryLabel
            label.font = .preferredFont(forTextStyle: .footnote)
            label.textAlignment = .center
            label.numberOfLines = 0
            label.translatesAutoresizingMaskIntoConstraints = false
            view.addSubview(label)
            NSLayoutConstraint.activate([
                label.centerXAnchor.constraint(equalTo: view.centerXAnchor),
                label.centerYAnchor.constraint(equalTo: view.centerYAnchor),
                label.leadingAnchor.constraint(greaterThanOrEqualTo: view.leadingAnchor, constant: 16),
                label.trailingAnchor.constraint(lessThanOrEqualTo: view.trailingAnchor, constant: -16)
            ])
            statusLabel = label
        }
        statusLabel?.text = text
        statusLabel?.isHidden = false
    }

    private func hideStatus() {
        statusLabel?.isHidden = true
    }

    private func fail(_ message: String) {
        showStatus(message)
        onUnavailable?(message)
    }

    // MARK: - Lifecycle

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        delivered = false
        startSession()
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        if session.isRunning { session.stopRunning() }
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        previewLayer?.frame = view.layer.bounds
    }

    // MARK: - Detection

    func metadataOutput(
        _ output: AVCaptureMetadataOutput,
        didOutput metadataObjects: [AVMetadataObject],
        from connection: AVCaptureConnection
    ) {
        guard !delivered,
              let object = metadataObjects.first as? AVMetadataMachineReadableCodeObject,
              object.type == .qr,
              let text = object.stringValue,
              !text.isEmpty
        else { return }
        delivered = true
        session.stopRunning()
        onScan?(text)
    }
}
