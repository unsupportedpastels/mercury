import UIKit
import MercuryShareKit

/// Share-extension entry point. The user must tap "Add to Mercury" before the
/// App Group inbox is written. The extension cannot submit a prompt and has no
/// server credentials, session transport, or send callback.
final class ShareViewController: UIViewController {
    private let titleLabel = UILabel()
    private let detailLabel = UILabel()
    private let addButton = UIButton(type: .system)
    private let cancelButton = UIButton(type: .system)
    private let spinner = UIActivityIndicatorView(style: .medium)

    override func viewDidLoad() {
        super.viewDidLoad()
        configureView()
    }

    private func configureView() {
        view.backgroundColor = .systemBackground
        preferredContentSize = CGSize(width: 360, height: 220)

        titleLabel.text = "Add to Mercury"
        titleLabel.font = .preferredFont(forTextStyle: .headline)
        titleLabel.textAlignment = .center

        detailLabel.text = "Shared text and files will be placed in your composer the next time Mercury opens. Nothing will be sent automatically."
        detailLabel.font = .preferredFont(forTextStyle: .subheadline)
        detailLabel.textColor = .secondaryLabel
        detailLabel.numberOfLines = 0
        detailLabel.textAlignment = .center

        addButton.setTitle("Add to Mercury", for: .normal)
        addButton.titleLabel?.font = .preferredFont(forTextStyle: .headline)
        addButton.addTarget(self, action: #selector(addToMercury), for: .touchUpInside)

        cancelButton.setTitle("Cancel", for: .normal)
        cancelButton.addTarget(self, action: #selector(cancel), for: .touchUpInside)

        let buttons = UIStackView(arrangedSubviews: [cancelButton, addButton])
        buttons.axis = .horizontal
        buttons.distribution = .fillEqually
        buttons.spacing = 12

        let stack = UIStackView(arrangedSubviews: [titleLabel, detailLabel, spinner, buttons])
        stack.axis = .vertical
        stack.spacing = 16
        stack.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(stack)
        NSLayoutConstraint.activate([
            stack.leadingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.leadingAnchor, constant: 20),
            stack.trailingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.trailingAnchor, constant: -20),
            stack.centerYAnchor.constraint(equalTo: view.centerYAnchor),
        ])
    }

    @objc private func addToMercury() {
        guard let context = extensionContext else { return }
        guard let appGroup = Bundle.main.object(forInfoDictionaryKey: "MercuryAppGroupIdentifier") as? String,
              !appGroup.isEmpty else {
            showFailure("Mercury sharing is not configured.")
            return
        }
        let providers = context.inputItems
            .compactMap { $0 as? NSExtensionItem }
            .compactMap(\.attachments)
            .flatMap { $0 }
        guard !providers.isEmpty else {
            showFailure("Nothing readable was shared.")
            return
        }

        setBusy(true)
        Task { @MainActor in
            do {
                let store = try ShareInboxStore(appGroupIdentifier: appGroup)
                let result = await ShareExtensionLoader(store: store).stage(providers: providers)
                guard !result.payload.isEmpty else {
                    setBusy(false)
                    showFailure(result.rejections.first ?? "Nothing readable was shared.")
                    return
                }
                try store.enqueue(result.payload)
                context.completeRequest(returningItems: nil)
            } catch {
                setBusy(false)
                showFailure("Could not add this share to Mercury.")
            }
        }
    }

    @objc private func cancel() {
        extensionContext?.cancelRequest(withError: NSError(
            domain: NSCocoaErrorDomain,
            code: NSUserCancelledError
        ))
    }

    private func setBusy(_ busy: Bool) {
        addButton.isEnabled = !busy
        cancelButton.isEnabled = !busy
        busy ? spinner.startAnimating() : spinner.stopAnimating()
    }

    private func showFailure(_ message: String) {
        detailLabel.text = message
        detailLabel.textColor = .systemRed
    }
}
