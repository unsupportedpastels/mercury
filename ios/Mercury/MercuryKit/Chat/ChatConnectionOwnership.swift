struct ChatConnectionOwnership {
    struct Token: Equatable {
        fileprivate let generation: UInt64
    }

    private var generation: UInt64 = 0

    mutating func publish() -> Token {
        generation &+= 1
        return Token(generation: generation)
    }

    mutating func publish(when allowed: Bool) -> Token? {
        guard allowed else { return nil }
        return publish()
    }

    func isCurrent(_ token: Token) -> Bool {
        token.generation == generation
    }

    @discardableResult
    mutating func release(ifCurrent token: Token) -> Bool {
        guard isCurrent(token) else { return false }
        generation &+= 1
        return true
    }

    mutating func invalidate() {
        generation &+= 1
    }
}
