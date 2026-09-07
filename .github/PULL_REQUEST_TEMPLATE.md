## Summary

<!-- What user problem does this solve? -->

## Hermes compatibility

<!-- Confirm this uses released, official Hermes interfaces and does not require a custom server route, plugin, dashboard extension, worker, or fork. Mercury Relay changes must stay isolated from direct-mode connection, credential, and catalog state. -->

## Cross-platform

<!-- The same user-visible behavior lands on Android and iOS in this PR, or state which platform is deferred and why. Shared decisions belong in shared/mercury-core. -->

## Verification

- [ ] `./gradlew testDebugUnitTest lintDebug assembleDebug`
- [ ] `./gradlew :shared:mercury-core:check` (when `shared/mercury-core` changes)
- [ ] `cd ios && xcodegen generate && xcodebuild -project Mercury.xcodeproj -scheme Mercury -destination 'platform=iOS Simulator,name=iPhone 17 Pro' build test` (when `ios/` or `shared/` changes; also run by the iOS CI job)
- [ ] `./gradlew validateDebugScreenshotTest` (when UI/reference changes apply)
- [ ] I checked compact, medium, and expanded layouts (when adaptive UI changes apply).
- [ ] I did not add credentials, cookies, tickets, server addresses, prompts, transcripts, attachments, signing material, or generated build output.

## Screenshots

<!-- Add sanitized before/after screenshots for user-visible UI changes. -->
