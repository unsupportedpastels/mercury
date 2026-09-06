#if canImport(MercuryCore)
import MercuryCore

/// Phase 0 spike (docs/plans/kmp-shared-core.md): proves the generated KMP
/// framework imports and its exported immutable model is callable from Swift.
/// Review the generated interface of `SessionRef` in Xcode (optionals,
/// equality, `detached()`) — Swift-facing API quality is an acceptance
/// criterion for the spike. Replaced by real facades once Phase 1 migrates
/// `AttachmentPolicy`.
enum SharedCoreSpike {
    static func detach(_ ref: SessionRef) -> SessionRef {
        ref.detached()
    }
}
#else
// Framework not built (no JDK / spike disabled): the spike compiles out and
// the iOS app builds exactly as before.
#endif
