import Foundation

/// Pure presentation policy for the clarify blocking sheet, ported from
/// Android's `ClarificationCard`:
///
/// - choices are selectable rows (single- or multi-select);
/// - an "Other" free-text field is always offered alongside them;
/// - typing and picking are mutually exclusive;
/// - Skip sends an empty answer ("no preference / proceed");
/// - multi-select answers join the picked choices with ", ".
enum ClarifySheetPolicy {
    static let skipAnswer = ""

    struct State: Equatable {
        let choices: [String]
        let multiSelect: Bool

        var answer: String = ""
        var selectedChoices: Set<String> = []

        mutating func select(_ choice: String) {
            answer = ""
            if multiSelect {
                if selectedChoices.contains(choice) {
                    selectedChoices.remove(choice)
                } else {
                    selectedChoices.insert(choice)
                }
            } else {
                selectedChoices = [choice]
            }
        }

        mutating func typeAnswer(_ text: String) {
            answer = text
            // Typing is its own answer — clear any picked choice so the two
            // inputs can't both look selected.
            if !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                selectedChoices.removeAll()
            }
        }

        var pendingAnswer: String? {
            if multiSelect && !selectedChoices.isEmpty {
                return choices.filter { selectedChoices.contains($0) }.joined(separator: ", ")
            }
            if !multiSelect && !selectedChoices.isEmpty {
                return selectedChoices.first
            }
            let trimmed = answer.trimmingCharacters(in: .whitespacesAndNewlines)
            return trimmed.isEmpty ? nil : trimmed
        }

        var canContinue: Bool { pendingAnswer != nil }
    }

    static func otherFieldLabel(hasChoices: Bool) -> String {
        hasChoices ? "Other" : "Response"
    }
}
