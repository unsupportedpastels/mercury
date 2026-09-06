import Foundation
import UIKit

/// The name this phone reports to a relay host in its admission envelope, so
/// the host's device list can show the phone by name instead of a fingerprint.
/// iOS returns a generic "iPhone" for the user-set name on recent systems, so
/// the model name is appended when the two differ.
enum RelayDeviceIdentity {
    static let name: String? = {
        let device = UIDevice.current
        let userName = device.name.trimmingCharacters(in: .whitespacesAndNewlines)
        let model = device.model.trimmingCharacters(in: .whitespacesAndNewlines)
        let combined: String
        if userName.isEmpty { combined = model }
        else if userName.caseInsensitiveCompare(model) == .orderedSame { combined = model }
        else { combined = userName }
        return combined.isEmpty ? nil : combined
    }()
}
