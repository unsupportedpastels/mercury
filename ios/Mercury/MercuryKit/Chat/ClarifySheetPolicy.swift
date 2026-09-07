import Foundation
import MercuryCore

/// Clarify answer semantics are a shared decision (`MercuryCore.ClarifyAnswerState`,
/// grounded in the desktop clarify card and Android's `ClarificationCard`):
/// selectable choice rows, an always-present "Other" field, mutual exclusivity
/// between typing and picking, Skip as an empty answer, multi-select joined
/// with ", ". This value type wraps the immutable core state for SwiftUI.
enum ClarifySheetPolicy {
    static let skipAnswer = MercuryCore.ClarifyAnswerPolicy.shared.SKIP_ANSWER

    struct State: Equatable {
        private var core: MercuryCore.ClarifyAnswerState

        init(choices: [String], multiSelect: Bool) {
            core = MercuryCore.ClarifyAnswerState(
                choices: choices, multiSelect: multiSelect, answer: "", selectedChoices: []
            )
        }

        var choices: [String] { core.choices }
        var multiSelect: Bool { core.multiSelect }
        var answer: String { core.answer }
        var selectedChoices: Set<String> { core.selectedChoices }

        mutating func select(_ choice: String) {
            core = core.select(choice: choice)
        }

        mutating func typeAnswer(_ text: String) {
            core = core.typeAnswer(text: text)
        }

        var pendingAnswer: String? { core.pendingAnswer }

        var canContinue: Bool { core.canContinue }

        static func == (lhs: State, rhs: State) -> Bool {
            lhs.core.isEqual(rhs.core)
        }
    }

    static func otherFieldLabel(hasChoices: Bool) -> String {
        MercuryCore.ClarifyAnswerPolicy.shared.otherFieldLabel(hasChoices: hasChoices)
    }
}
