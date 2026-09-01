# Open Host Files In External Apps Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** When a user taps a real Hermes host file in chat, Artifacts, or Host files, HAM downloads it through the existing managed-file read and opens it in another Android app, with a signed-in Files settings page that stores a disabled coming-soon in-app preview toggle per server origin.

**Architecture:** Keep all click, MEDIA, artifact, and host-file row decisions in a platform-independent policy plus a small open-attempt reducer. Reuse the existing managed-file read, `shared-artifacts` cache, and non-exported FileProvider already used by Share. v1 always launches `ACTION_VIEW`; in-app HTML/SVG/mermaid/file preview is out of scope and must not be built. Persist `inAppFilePreviewEnabled` per normalized origin even though the switch cannot be turned on yet.

**Tech Stack:** Kotlin, JUnit4 local tests, Jetpack Compose, Navigation 3 `NavKey`s, DataStore Preferences, Android `ACTION_VIEW` + `FileProvider`.

## Global Constraints

- Native Android client for unchanged official `hermes serve` interfaces only. No new server routes, plugins, forks, or gateway workers.
- Observer may open files (read path via existing `readManagedFile` / `GET /api/files/read`). Do not require controller.
- Conservative Hermes compatibility: do not take over a remote runtime; do not close a shared runtime on disconnect.
- Scope file cache, settings, and credentials by normalized server origin.
- Reuse Share's cache pattern: write bytes under `cacheDir/shared-artifacts/`, then `FileProvider.getUriForFile` with authority `${applicationId}.files`.
- FileProvider stays `android:exported="false"` with `grantUriPermissions="true"`. Grant `FLAG_GRANT_READ_URI_PERMISSION` only. Add no exported components.
- Do not execute or render HTML/SVG in a WebView. SECURITY.md already states this; keep that sentence true.
- Images stay in-app preview. Audio stays as today's in-app player on the Artifacts sheet. Remote https Open already exists and stays. Mermaid in a chat code fence is not a file — leave it as copyable fenced code. No in-app HTML/SVG/mermaid renderer.
- If no app can handle the file: short error on that row/chip; never fail silently. While downloading: show `Opening…` on that chip/row.
- Settings hub section **Files** sits immediately after **Servers** and before **Connection & profile**. Signed-in only (same visibility as Offline & privacy). Screen title `Files`. Hub summary `How files from chat open on this phone`. Layout matches Offline & privacy (title, short note, row + Switch). Toggle **In-app file preview**, default off, **disabled**, with a coming-soon note. Persist per origin even though it cannot be turned on. In-app preview implementation is out of scope.
- Keep Share and Save on Artifacts. Attach stays on Host files. Folders still drill.
- Do not enable global cleartext HTTP. Do not log paths into credentials/tokens/transcripts beyond existing bounded error copy (max 160 characters, no file bytes).
- TDD: failing unit tests first for reducers/settings persistence, markdown link click policy, MEDIA chip vs image, artifact Open, host-file Open, and "no app can open this". Do not manufacture tests for chip styling. After implementation, required gates are `./gradlew testDebugUnitTest lintDebug assembleDebug` (do not run that suite in this plan-only commit).

---

## File structure

| File | Responsibility |
| --- | --- |
| Create: `app/src/main/java/com/unsupportedpastels/hermesandroid/files/HostFileOpenPolicy.kt` | Pure click/MEDIA/artifact/row/open-attempt policy. No Android types. |
| Create: `app/src/test/java/com/unsupportedpastels/hermesandroid/files/HostFileOpenPolicyTest.kt` | Failing-first tests for every v1 decision listed below. |
| Create: `app/src/main/java/com/unsupportedpastels/hermesandroid/files/FilesPreferences.kt` | Per-origin `inAppFilePreviewEnabled` snapshot and repository contract. |
| Create: `app/src/main/java/com/unsupportedpastels/hermesandroid/files/FilesPreferencesRepository.kt` | DataStore persistence keyed by normalized origin. Default `false`. |
| Create: `app/src/test/java/com/unsupportedpastels/hermesandroid/files/FilesPreferencesRepositoryTest.kt` | Persistence isolation across origins; default off. |
| Modify: `app/src/main/java/com/unsupportedpastels/hermesandroid/navigation/Routes.kt` | Add `SettingsFilesRoute`. |
| Modify: `app/src/main/java/com/unsupportedpastels/hermesandroid/ui/HermesApp.kt` | Hub enum + routing, Files section UI, Open on Artifacts, tap-to-open on Host files, shared `openManagedHostFile`, Opening… / error copy. |
| Modify: `app/src/main/java/com/unsupportedpastels/hermesandroid/ui/MessageMarkdown.kt` | Host-path markdown links tappable; non-picture `MEDIA:` becomes a filename chip. |
| Modify: `app/src/test/java/com/unsupportedpastels/hermesandroid/ui/MessageMarkdownTest.kt` | MEDIA chip vs image; mermaid fence stays code. |
| Modify: `app/src/test/java/com/unsupportedpastels/hermesandroid/ui/HermesAppTest.kt` | Hub order/visibility; Files screen disabled toggle; unauthenticated hub still Servers-only. |
| Modify: `app/src/main/java/com/unsupportedpastels/hermesandroid/MainActivity.kt` (only if needed to construct the Files preferences ViewModel/repository the same way other settings stores are provided) | Wire repository into `HermesApp` / `HermesAppHost`. |
| Do not modify: `SECURITY.md` unless the existing FileProvider / no-WebView sentence is no longer accurate (it should remain accurate). | |
| Do not create: in-app HTML/SVG/mermaid/file preview renderer, new Hermes routes, new exported components. | |

---

### Task 1: Host-file open policy and reducer

**Files:**
- Create: `app/src/test/java/com/unsupportedpastels/hermesandroid/files/HostFileOpenPolicyTest.kt`
- Create: `app/src/main/java/com/unsupportedpastels/hermesandroid/files/HostFileOpenPolicy.kt`

**Interfaces:**
- Consumes: `validCanonicalHostFilePath` from `HostFileModels.kt`; `validateGatewayMediaPath` and `validateRemoteMediaUrl` from `RemoteMediaImage.kt`; `Artifact`, `ArtifactOrigin`, `ArtifactType` from `ArtifactModels.kt`; `HostFileEntry` from `HostFileModels.kt`.
- Produces: `HostFileOpenPolicy` with the types below. Later tasks must use these names unchanged.

```kotlin
sealed interface MarkdownLinkTarget {
    data class RemoteWeb(val url: String) : MarkdownLinkTarget
    data class ManagedHostPath(val path: String) : MarkdownLinkTarget
    data object Ignore : MarkdownLinkTarget
}

sealed interface MediaLineKind {
    data class InAppImage(val source: String) : MediaLineKind
    data class FileChip(val source: String, val displayName: String) : MediaLineKind
    data object Ignore : MediaLineKind
}

sealed interface HostFileRowAction {
    data object DrillFolder : HostFileRowAction
    data class OpenFile(val path: String) : HostFileRowAction
    data object Ignore : HostFileRowAction
}

sealed interface HostFileOpenUiState {
    data object Idle : HostFileOpenUiState
    data object Opening : HostFileOpenUiState
    data class Failed(val message: String) : HostFileOpenUiState
}

sealed interface HostFileOpenEvent {
    data object Requested : HostFileOpenEvent
    data object LaunchSucceeded : HostFileOpenEvent
    data object NoAppHandler : HostFileOpenEvent
    data class Failed(val message: String) : HostFileOpenEvent
}

object HostFileOpenPolicy {
    const val OPENING_LABEL = "Opening…"
    const val NO_HANDLER_MESSAGE = "No app on this phone can open this file"
    const val OPEN_FAILED_MESSAGE = "Could not open file"

    fun markdownLinkTarget(href: String): MarkdownLinkTarget
    fun mediaLineKind(source: String): MediaLineKind
    fun displayName(path: String): String
    fun artifactOpenAvailable(origin: ArtifactOrigin): Boolean
    fun hostFileRowAction(entry: HostFileEntry): HostFileRowAction
    fun reduce(state: HostFileOpenUiState, event: HostFileOpenEvent): HostFileOpenUiState
}
```

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.unsupportedpastels.hermesandroid.files

import com.unsupportedpastels.hermesandroid.artifacts.ArtifactOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostFileOpenPolicyTest {
    @Test
    fun markdownHttpAndHttpsStayRemoteWebAndHostPathsBecomeManagedOpen() {
        assertEquals(
            MarkdownLinkTarget.RemoteWeb("https://example.invalid/doc"),
            HostFileOpenPolicy.markdownLinkTarget("https://example.invalid/doc"),
        )
        assertEquals(
            MarkdownLinkTarget.RemoteWeb("http://127.0.0.1:9119/readme"),
            HostFileOpenPolicy.markdownLinkTarget("http://127.0.0.1:9119/readme"),
        )
        assertEquals(
            MarkdownLinkTarget.ManagedHostPath("/home/mark/out/report.pdf"),
            HostFileOpenPolicy.markdownLinkTarget("/home/mark/out/report.pdf"),
        )
        assertEquals(MarkdownLinkTarget.Ignore, HostFileOpenPolicy.markdownLinkTarget("file:///etc/passwd"))
        assertEquals(MarkdownLinkTarget.Ignore, HostFileOpenPolicy.markdownLinkTarget("javascript:alert(1)"))
        assertEquals(MarkdownLinkTarget.Ignore, HostFileOpenPolicy.markdownLinkTarget("relative/report.pdf"))
        assertEquals(MarkdownLinkTarget.Ignore, HostFileOpenPolicy.markdownLinkTarget("/home/mark/../secret.pdf"))
    }

    @Test
    fun mediaPictureStaysInAppImageAndNonPictureHostPathBecomesFilenameChip() {
        val picture = HostFileOpenPolicy.mediaLineKind("/home/mark/design/mockup.png")
        assertEquals(MediaLineKind.InAppImage("/home/mark/design/mockup.png"), picture)

        val pdf = HostFileOpenPolicy.mediaLineKind("/home/mark/out/report.pdf")
        assertEquals(
            MediaLineKind.FileChip("/home/mark/out/report.pdf", "report.pdf"),
            pdf,
        )

        val svg = HostFileOpenPolicy.mediaLineKind("/tmp/chart.svg")
        assertTrue(svg is MediaLineKind.FileChip)
        assertEquals("chart.svg", (svg as MediaLineKind.FileChip).displayName)

        assertEquals(MediaLineKind.Ignore, HostFileOpenPolicy.mediaLineKind("not-a-path"))
        assertEquals(MediaLineKind.Ignore, HostFileOpenPolicy.mediaLineKind("/home/mark/../secret.html"))
    }

    @Test
    fun artifactOpenIsAvailableForManagedHostFilesAndRemoteOpenAlreadyExists() {
        assertTrue(HostFileOpenPolicy.artifactOpenAvailable(ArtifactOrigin.ManagedPath))
        assertFalse(HostFileOpenPolicy.artifactOpenAvailable(ArtifactOrigin.RemoteUrl))
    }

    @Test
    fun hostFileRowsOpenFilesAndStillDrillFolders() {
        val folder = HostFileEntry("docs", "/srv/project/docs", isDirectory = true)
        val file = HostFileEntry("notes.txt", "/srv/project/notes.txt", isDirectory = false)
        assertEquals(HostFileRowAction.DrillFolder, HostFileOpenPolicy.hostFileRowAction(folder))
        assertEquals(
            HostFileRowAction.OpenFile("/srv/project/notes.txt"),
            HostFileOpenPolicy.hostFileRowAction(file),
        )
    }

    @Test
    fun openAttemptReducerShowsOpeningThenRowErrorWhenNoAppCanHandleTheFile() {
        val opening = HostFileOpenPolicy.reduce(HostFileOpenUiState.Idle, HostFileOpenEvent.Requested)
        assertEquals(HostFileOpenUiState.Opening, opening)
        assertEquals("Opening…", HostFileOpenPolicy.OPENING_LABEL)

        val noHandler = HostFileOpenPolicy.reduce(opening, HostFileOpenEvent.NoAppHandler)
        assertEquals(
            HostFileOpenUiState.Failed("No app on this phone can open this file"),
            noHandler,
        )
        assertEquals(
            "No app on this phone can open this file",
            HostFileOpenPolicy.NO_HANDLER_MESSAGE,
        )

        val recovered = HostFileOpenPolicy.reduce(noHandler, HostFileOpenEvent.Requested)
        assertEquals(HostFileOpenUiState.Opening, recovered)

        val success = HostFileOpenPolicy.reduce(recovered, HostFileOpenEvent.LaunchSucceeded)
        assertEquals(HostFileOpenUiState.Idle, success)

        val failed = HostFileOpenPolicy.reduce(
            HostFileOpenUiState.Opening,
            HostFileOpenEvent.Failed("Could not download file"),
        )
        assertEquals(HostFileOpenUiState.Failed("Could not download file"), failed)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests com.unsupportedpastels.hermesandroid.files.HostFileOpenPolicyTest`

Expected: FAIL compiling (`Unresolved reference: HostFileOpenPolicy`).

- [ ] **Step 3: Write minimal implementation**

Implement `HostFileOpenPolicy` in `HostFileOpenPolicy.kt`:

- `markdownLinkTarget`: `http://` / `https://` (case-insensitive) → `RemoteWeb`. Else `validCanonicalHostFilePath(href)` → `ManagedHostPath`. Else `Ignore`. Do not accept `file:`, `content:`, `javascript:`, or relative paths.
- `mediaLineKind`: if `validateGatewayMediaPath(source)` or (`validateRemoteMediaUrl(source)` and the path looks like an image via `validateGatewayMediaPath` on the URI path, or reuse `validateGatewayMediaPath` for host paths and treat https image URLs as today via `validateRemoteMediaUrl` plus image extension check matching `validateGatewayMediaPath`'s extension set) → `InAppImage`. Else if `validCanonicalHostFilePath(source) != null` → `FileChip` with `displayName`. Else `Ignore`. Do not treat a mermaid fence as media; this function only classifies a `MEDIA:` payload.
- `displayName`: last `/` or `\` segment of a canonical path, falling back to the path itself.
- `artifactOpenAvailable`: `true` only for `ArtifactOrigin.ManagedPath`. Remote URL Open stays on the existing Artifacts `uriHandler.openUri` button.
- `hostFileRowAction`: directories → `DrillFolder`; files with `validCanonicalHostFilePath(entry.path)` → `OpenFile`; else `Ignore`.
- `reduce`: `Requested` → `Opening`; `LaunchSucceeded` → `Idle`; `NoAppHandler` → `Failed(NO_HANDLER_MESSAGE)`; `Failed(message)` → `Failed` with the bounded message (blank falls back to `OPEN_FAILED_MESSAGE`).

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests com.unsupportedpastels.hermesandroid.files.HostFileOpenPolicyTest`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/unsupportedpastels/hermesandroid/files/HostFileOpenPolicy.kt \
  app/src/test/java/com/unsupportedpastels/hermesandroid/files/HostFileOpenPolicyTest.kt
git commit -m "$(cat <<'EOF'
feat(files): classify host-file taps before any Android launch

Keep markdown, MEDIA, artifact, and browser-row decisions in one
tested policy so chat links cannot silently no-op and missing
handlers always surface a row error.
EOF
)"
```

---

### Task 2: Persist in-app preview setting per origin

**Files:**
- Create: `app/src/test/java/com/unsupportedpastels/hermesandroid/files/FilesPreferencesRepositoryTest.kt`
- Create: `app/src/main/java/com/unsupportedpastels/hermesandroid/files/FilesPreferences.kt`
- Create: `app/src/main/java/com/unsupportedpastels/hermesandroid/files/FilesPreferencesRepository.kt`

**Interfaces:**
- Consumes: `ServerOrigin` from `ServerOrigin.kt`. Reuse the in-memory DataStore fake pattern from `ServerCatalogRepositoryTest` (`InMemoryCatalogDataStore`).
- Produces:

```kotlin
data class FilesPreferences(
    val inAppFilePreviewEnabled: Boolean = false,
)

interface FilesPreferencesRepository {
    fun preferences(origin: ServerOrigin): Flow<FilesPreferences>
    suspend fun setInAppFilePreviewEnabled(origin: ServerOrigin, enabled: Boolean)
}
```

Do not add this flag to `ServerCatalogEntry`. Catalog JSON is strict (`ignoreUnknownKeys = false`) and is origin identity plus server-row metadata, not feature flags. Do not implement an in-app preview renderer in this task or later tasks in this plan. v1 open path must ignore the stored flag and always use external `ACTION_VIEW`. The repository exists so the disabled Files switch still round-trips per origin.

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.unsupportedpastels.hermesandroid.files

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.unsupportedpastels.hermesandroid.connection.ServerOrigin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class FilesPreferencesRepositoryTest {
    @Test
    fun missingOriginDefaultsToPreviewOff() = runTest {
        val repository = DataStoreFilesPreferencesRepository(InMemoryFilesPreferencesDataStore(emptyPreferences()))
        val origin = ServerOrigin.parse("https://hermes.example")
        assertEquals(FilesPreferences(inAppFilePreviewEnabled = false), repository.preferences(origin).first())
    }

    @Test
    fun persistsPreviewFlagPerNormalizedOriginWithoutLeakingToAnotherServer() = runTest {
        val repository = DataStoreFilesPreferencesRepository(InMemoryFilesPreferencesDataStore(emptyPreferences()))
        val first = ServerOrigin.parse("HTTPS://FIRST.example/")
        val second = ServerOrigin.parse("https://second.example")

        repository.setInAppFilePreviewEnabled(first, true)

        assertTrue(repository.preferences(ServerOrigin.parse("https://first.example")).first().inAppFilePreviewEnabled)
        assertFalse(repository.preferences(second).first().inAppFilePreviewEnabled)
    }
}

private fun assertTrue(value: Boolean) {
    org.junit.Assert.assertTrue(value)
}

private class InMemoryFilesPreferencesDataStore(initial: Preferences) : DataStore<Preferences> {
    private val state = MutableStateFlow(initial)
    override val data: Flow<Preferences> = state
    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
        val updated = transform(state.value)
        state.value = updated
        return updated
    }
}
```

Use `org.junit.Assert.assertTrue` directly instead of a private wrapper if that is cleaner; do not duplicate `ServerCatalogRepositoryTest`'s helper names unless needed. Prefer copying `InMemoryCatalogDataStore` locally into this test file so the engineer does not have to hunt.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests com.unsupportedpastels.hermesandroid.files.FilesPreferencesRepositoryTest`

Expected: FAIL compiling (`Unresolved reference: FilesPreferencesRepository` / `DataStoreFilesPreferencesRepository`).

- [ ] **Step 3: Write minimal implementation**

- DataStore name: `files_preferences`.
- Store a JSON object map of `origin.value` → boolean, or a string set of origins with preview enabled (default empty → all false). JSON map is clearer for an explicit `true`/`false`.
- Key preferences by `origin.value` after `ServerOrigin.parse` normalization so `HTTPS://FIRST.example/` and `https://first.example` share a row.
- `setInAppFilePreviewEnabled` must actually write `true` even though the UI cannot turn the switch on yet.

```kotlin
class DataStoreFilesPreferencesRepository(
    private val dataStore: DataStore<Preferences>,
) : FilesPreferencesRepository {
    constructor(context: Context) : this(context.applicationContext.filesPreferencesDataStore)

    override fun preferences(origin: ServerOrigin): Flow<FilesPreferences> =
        dataStore.data.map { prefs ->
            FilesPreferences(inAppFilePreviewEnabled = readFlag(prefs, origin))
        }

    override suspend fun setInAppFilePreviewEnabled(origin: ServerOrigin, enabled: Boolean) {
        dataStore.edit { prefs -> writeFlag(prefs, origin, enabled) }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests com.unsupportedpastels.hermesandroid.files.FilesPreferencesRepositoryTest`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/unsupportedpastels/hermesandroid/files/FilesPreferences.kt \
  app/src/main/java/com/unsupportedpastels/hermesandroid/files/FilesPreferencesRepository.kt \
  app/src/test/java/com/unsupportedpastels/hermesandroid/files/FilesPreferencesRepositoryTest.kt
git commit -m "$(cat <<'EOF'
feat(files): remember per-origin in-app preview preference

Store the coming-soon toggle against the normalized server origin
now so a later preview ship does not need a second persistence
design, even while v1 cannot turn it on.
EOF
)"
```

---

### Task 3: Settings hub Files section

**Files:**
- Modify: `app/src/main/java/com/unsupportedpastels/hermesandroid/navigation/Routes.kt`
- Modify: `app/src/main/java/com/unsupportedpastels/hermesandroid/ui/HermesApp.kt` (`SettingsSection`, `isSettingsRoute`, `openSettingsSection`, `entry<SettingsFilesRoute>`, hub availability, `ServerSettingsScreen` Files block)
- Modify: `app/src/test/java/com/unsupportedpastels/hermesandroid/ui/HermesAppTest.kt` (`settingsHubListsSectionsAndOpensModelSectionForAuthenticatedServer`, `unauthenticatedSettingsHubOnlyOffersServers`, plus a new Files-section test)
- Modify: `app/src/main/java/com/unsupportedpastels/hermesandroid/MainActivity.kt` and `HermesApp` / `HermesAppHost` only as needed to pass `FilesPreferences` and a setter into settings (mirror `transcriptCachingEnabled`)

**Interfaces:**
- Consumes: `FilesPreferencesRepository.preferences(origin)` and `setInAppFilePreviewEnabled`.
- Produces: `SettingsFilesRoute` (`NavKey`), `SettingsSection.Files` with title `Files` and summary `How files from chat open on this phone`, signed-in visibility identical to Offline & privacy.

Copy locked in this task (use these strings verbatim):

- Hub title: `Files`
- Hub summary: `How files from chat open on this phone`
- Screen title: `Files` (via `section.title`)
- Note: `Files the agent puts in chat open in another app on this phone. In-app preview is coming soon.`
- Switch row: `In-app file preview`
- Switch content description: `In-app file preview`
- Switch: `checked` from persisted value (default false), `enabled = false`

- [ ] **Step 1: Write the failing tests**

Extend `settingsHubListsSectionsAndOpensModelSectionForAuthenticatedServer` so the authenticated hub shows Files, and add a focused test:

```kotlin
@Test
fun settingsHubPlacesFilesAfterServersAndOpensDisabledPreviewToggle() {
    val snapshot = connectedSnapshot.copy(authenticationState = AuthenticationState.Authenticated)
    composeRule.setContent {
        HermesAndroidTheme { HermesApp(snapshot = snapshot) }
    }

    composeRule.onNodeWithContentDescription("Settings").performClick()
    composeRule.onNodeWithContentDescription("Open Files settings").assertIsDisplayed()
    composeRule.onNodeWithContentDescription("Open Files settings").performClick()
    composeRule.onNodeWithText("Files").assertIsDisplayed()
    composeRule.onNodeWithText("How files from chat open on this phone").assertDoesNotExist()
    composeRule.onNodeWithText("In-app file preview").assertIsDisplayed()
    composeRule.onNodeWithContentDescription("In-app file preview").assertIsNotEnabled()
}

@Test
fun unauthenticatedSettingsHubDoesNotOfferFiles() {
    composeRule.setContent {
        HermesAndroidTheme {
            HermesApp(snapshot = HermesGatewaySnapshot(connectionState = ConnectionState.Connected))
        }
    }
    composeRule.onNodeWithContentDescription("Settings").performClick()
    composeRule.onNodeWithContentDescription("Open Servers settings").assertIsDisplayed()
    composeRule.onNodeWithContentDescription("Open Files settings").assertDoesNotExist()
    composeRule.onNodeWithContentDescription("Open Offline & privacy settings").assertDoesNotExist()
}
```

If `unauthenticatedSettingsHubOnlyOffersServers` already covers Offline/Account absence, add only the Files assertion there instead of a second unauthenticated test. Prefer extending the existing test.

Insert `Files` in the enum so hub order is Servers, Files, Connection, Model, Voice, Offline, Jobs, Account. `SettingsHubScreen` already iterates `SettingsSection.entries`.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests com.unsupportedpastels.hermesandroid.ui.HermesAppTest.settingsHubPlacesFilesAfterServersAndOpensDisabledPreviewToggle`

Expected: FAIL (`Open Files settings` does not exist).

- [ ] **Step 3: Write minimal implementation**

1. Add to `Routes.kt`:

```kotlin
@Serializable
data object SettingsFilesRoute : NavKey
```

2. Insert in `SettingsSection` immediately after `Servers`:

```kotlin
Files("Files", "How files from chat open on this phone"),
```

3. Include `SettingsFilesRoute` in `isSettingsRoute()`.
4. Map `SettingsSection.Files -> SettingsFilesRoute` in `openSettingsSection`.
5. Add `entry<SettingsFilesRoute> { renderSettingsSection(SettingsSection.Files) }` next to the other settings entries.
6. Authenticated hub already uses `SettingsSection.entries.toSet()`, so Files appears only when signed in, matching Offline.
7. In `ServerSettingsScreen`, add a Files block modeled on Offline (title, short note, row + Switch). Pass `inAppFilePreviewEnabled` and `onInAppFilePreviewChanged`. The Switch must be `enabled = false`. Still call the setter from `onCheckedChange` only if Compose invokes it; because the switch is disabled, production users cannot turn it on. Persistence tests in Task 2 cover writing `true`.
8. Wire repository through `HermesAppHost` the same way offline cache is provided. Collect preferences for the active origin. Do not read this flag in the open path.

Do not add screenshot goldens for this row. Hub presence and the disabled switch are policy/behavior, not visual work.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests com.unsupportedpastels.hermesandroid.ui.HermesAppTest`

Expected: the new Files assertions PASS; existing hub tests still PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/unsupportedpastels/hermesandroid/navigation/Routes.kt \
  app/src/main/java/com/unsupportedpastels/hermesandroid/ui/HermesApp.kt \
  app/src/main/java/com/unsupportedpastels/hermesandroid/MainActivity.kt \
  app/src/test/java/com/unsupportedpastels/hermesandroid/ui/HermesAppTest.kt
git commit -m "$(cat <<'EOF'
feat(settings): add a signed-in Files hub for later in-app preview

Put file-open policy next to Servers so people can see how chat
files open on the phone, while keeping preview off and disabled
until a real in-app renderer exists.
EOF
)"
```

---

### Task 4: Parse non-picture MEDIA lines as file chips

**Files:**
- Modify: `app/src/test/java/com/unsupportedpastels/hermesandroid/ui/MessageMarkdownTest.kt`
- Modify: `app/src/main/java/com/unsupportedpastels/hermesandroid/ui/MessageMarkdown.kt`

**Interfaces:**
- Consumes: `HostFileOpenPolicy.mediaLineKind`.
- Produces: `MarkdownFileChipBlock(source: String, displayName: String)` as a `MarkdownBlock`. `parseMessageMarkdown` emits it instead of leaving `MEDIA:/path/file.pdf` as text. Picture `MEDIA:` lines still emit `MarkdownImageBlock`. Fenced `mermaid` (and every other fence) still emits `MarkdownCodeBlock`.

- [ ] **Step 1: Write the failing tests**

Add to `MessageMarkdownTest`:

```kotlin
@Test
fun parsesNonPictureHostMediaDirectiveAsFileChipInsteadOfRawText() {
    val path = "/home/mark/out/report.pdf"
    val blocks = parseMessageMarkdown("Result:\n\nMEDIA:$path\n\nDone")
    val chip = blocks.filterIsInstance<MarkdownFileChipBlock>().single()
    assertEquals(path, chip.source)
    assertEquals("report.pdf", chip.displayName)
    assertFalse(blocks.filterIsInstance<MarkdownTextBlock>().any { it.plainText.contains("MEDIA:") })
    assertTrue(blocks.filterIsInstance<MarkdownImageBlock>().isEmpty())
}

@Test
fun keepsPictureMediaDirectiveAsImageAndMermaidFenceAsCopyableCode() {
    val imagePath = "/home/mark/project/design/generated-mockup.jpg"
    val blocks = parseMessageMarkdown(
        "Result:\n\nMEDIA:$imagePath\n\n```mermaid\nflowchart LR\nA-->B\n```\n",
    )
    assertEquals(imagePath, blocks.filterIsInstance<MarkdownImageBlock>().single().url)
    val code = blocks.filterIsInstance<MarkdownCodeBlock>().single()
    assertEquals("mermaid", code.language)
    assertTrue(code.code.contains("flowchart LR"))
    assertTrue(blocks.filterIsInstance<MarkdownFileChipBlock>().isEmpty())
}
```

Do not add Compose screenshot tests for chip chrome.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests com.unsupportedpastels.hermesandroid.ui.MessageMarkdownTest.parsesNonPictureHostMediaDirectiveAsFileChipInsteadOfRawText`

Expected: FAIL (`Unresolved reference: MarkdownFileChipBlock` or image/text assertions).

- [ ] **Step 3: Write minimal implementation**

In `MessageMarkdown.kt`, replace the `MEDIA:` branch that only accepts `validateRemoteMediaUrl || validateGatewayMediaPath` with `HostFileOpenPolicy.mediaLineKind`:

```kotlin
internal data class MarkdownFileChipBlock(
    val source: String,
    val displayName: String,
) : MarkdownBlock

// inside parseMessageMarkdown, when mediaDirectivePattern matches:
when (val kind = HostFileOpenPolicy.mediaLineKind(media.groupValues[1])) {
    is MediaLineKind.InAppImage -> {
        flushParagraph()
        blocks += MarkdownImageBlock(kind.source)
        index += 1
        continue
    }
    is MediaLineKind.FileChip -> {
        flushParagraph()
        blocks += MarkdownFileChipBlock(kind.source, kind.displayName)
        index += 1
        continue
    }
    MediaLineKind.Ignore -> Unit
}
```

Leave the `MarkdownMessage` `when` without a chip renderer until Task 6; tests in this task only cover `parseMessageMarkdown`. If the `when` on `MarkdownBlock` becomes non-exhaustive, add a temporary branch that renders the chip label as `Text(block.displayName)` so the module compiles. Prefer adding the real chip UI in Task 6 in the same change set if compilation requires it.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests com.unsupportedpastels.hermesandroid.ui.MessageMarkdownTest`

Expected: PASS, including existing image MEDIA tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/unsupportedpastels/hermesandroid/ui/MessageMarkdown.kt \
  app/src/test/java/com/unsupportedpastels/hermesandroid/ui/MessageMarkdownTest.kt
git commit -m "$(cat <<'EOF'
feat(chat): turn non-picture MEDIA host paths into file chips

Picture MEDIA lines already preview in-app; other host files were
dead text. Classify them as chips so later taps can open the real
file without adding an HTML renderer.
EOF
)"
```

---

### Task 5: Markdown link click policy in annotated text

**Files:**
- Modify: `app/src/test/java/com/unsupportedpastels/hermesandroid/files/HostFileOpenPolicyTest.kt` (already covers href classification; add a parser-level test here)
- Modify: `app/src/test/java/com/unsupportedpastels/hermesandroid/ui/MessageMarkdownTest.kt`
- Modify: `app/src/main/java/com/unsupportedpastels/hermesandroid/ui/MessageMarkdown.kt`

**Interfaces:**
- Consumes: `HostFileOpenPolicy.markdownLinkTarget`.
- Produces: `MarkdownMessage(onOpenManagedPath: ((String) -> Unit)? = null)`. http(s) links keep `LinkAnnotation.Url`. Canonical host paths become `LinkAnnotation.Clickable` that invoke `onOpenManagedPath`. Other hrefs stay styled but not tappable.

- [ ] **Step 1: Write the failing test**

```kotlin
@Test
fun hostPathMarkdownLinksAreClassifiedForOpenWhileHttpStaysWeb() {
    val blocks = parseMessageMarkdown(
        "See [docs](https://example.invalid/readme) and [report](/home/mark/out/report.pdf).",
    )
    val inlines = (blocks.single() as MarkdownTextBlock).inlines
    assertEquals("https://example.invalid/readme", inlines.single { it.text == "docs" }.link)
    assertEquals("/home/mark/out/report.pdf", inlines.single { it.text == "report" }.link)
    assertEquals(
        MarkdownLinkTarget.RemoteWeb("https://example.invalid/readme"),
        HostFileOpenPolicy.markdownLinkTarget(inlines.single { it.text == "docs" }.link!!),
    )
    assertEquals(
        MarkdownLinkTarget.ManagedHostPath("/home/mark/out/report.pdf"),
        HostFileOpenPolicy.markdownLinkTarget(inlines.single { it.text == "report" }.link!!),
    )
}
```

This is a unit test of parse + policy, not a Compose click test. Do not add a screenshot.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests com.unsupportedpastels.hermesandroid.ui.MessageMarkdownTest.hostPathMarkdownLinksAreClassifiedForOpenWhileHttpStaysWeb`

Expected: FAIL until the markdown already parses host-path links (it should already parse them as `inline.link`) — if parse already works, this test PASSES at policy level after Task 1. If it passes immediately, keep it as a regression pin and still do Step 3 for `annotatedMarkdown` click wiring.

If the test PASSES from Task 1 + existing parser, do not invent a failing UI test. Wire `LinkAnnotation.Clickable` next; verification is the existing unit classification plus a later chat open helper test in Task 6.

- [ ] **Step 3: Write minimal implementation**

Change `annotatedMarkdown` to take `onOpenManagedPath: ((String) -> Unit)?` and:

```kotlin
when (val target = inline.link?.let(HostFileOpenPolicy::markdownLinkTarget)) {
    is MarkdownLinkTarget.RemoteWeb -> withLink(LinkAnnotation.Url(target.url)) {
        withStyle(style) { append(inline.text) }
    }
    is MarkdownLinkTarget.ManagedHostPath -> {
        val click = LinkAnnotation.Clickable("host-file") {
            onOpenManagedPath?.invoke(target.path)
        }
        withLink(click) { withStyle(style) { append(inline.text) } }
    }
    else -> withStyle(style) { append(inline.text) }
}
```

Pass `onOpenManagedPath` from `MarkdownMessage` through `MarkdownText`. Do not open `file:`, `javascript:`, or relative hrefs.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests com.unsupportedpastels.hermesandroid.ui.MessageMarkdownTest`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/unsupportedpastels/hermesandroid/ui/MessageMarkdown.kt \
  app/src/test/java/com/unsupportedpastels/hermesandroid/ui/MessageMarkdownTest.kt
git commit -m "$(cat <<'EOF'
feat(chat): make managed host-path markdown links tappable

http(s) already opened in a browser; host-path links only looked
like links. Route canonical managed paths through the same open
policy so tapping a real file can download and view it.
EOF
)"
```

---

### Task 6: Download via managed-file read and launch ACTION_VIEW

**Files:**
- Modify: `app/src/main/java/com/unsupportedpastels/hermesandroid/ui/HermesApp.kt` (extract a shared opener next to `writeSharedArtifact`)
- Create: `app/src/test/java/com/unsupportedpastels/hermesandroid/files/HostFileExternalOpenTest.kt` if a JVM-safe wrapper is extracted; otherwise keep launch mapping in `HostFileOpenPolicy.reduce` (already tested) and test only the failure mapping

**Interfaces:**
- Consumes: `onLoadManagedFile: suspend (String) -> Result<HostFileContent>` (existing SessionDetail callback; it already uses official `readManagedFile`). `writeSharedArtifact`. `FileProvider` authority `${packageName}.files`. `HostFileOpenPolicy.reduce`.
- Produces: a shared suspend helper used by chat chips/links, Artifacts Open, and Host files row tap:

```kotlin
internal suspend fun openManagedHostFile(
    context: Context,
    source: String,
    displayName: String,
    load: suspend (String) -> Result<HostFileContent>,
    startActivity: (Intent) -> Unit = context::startActivity,
): HostFileOpenEvent
```

Behavior:

1. Reduce UI to `Opening` before the call (`HostFileOpenEvent.Requested`).
2. `load(source)` — existing managed-file read, 10 MiB bound already in `MAX_HOST_FILE_BYTES`.
3. On failure → `HostFileOpenEvent.Failed` with `failure.message` bounded to 160 characters or `Could not download file`.
4. On success, `withContext(Dispatchers.IO)` write bytes with `writeSharedArtifact` using a synthetic `Artifact` (`ArtifactType.File`, `ArtifactOrigin.ManagedPath`, `stableIdentity = source`, `displayName`).
5. `FileProvider.getUriForFile(context, "${context.packageName}.files", file)`.
6. `Intent(Intent.ACTION_VIEW)` with `setDataAndType(uri, content.mimeType)`, `FLAG_GRANT_READ_URI_PERMISSION`. Do not use `createChooser` (that is Share). Do not add write or persistable URI flags. Do not export FileProvider.
7. `startActivity(intent)`.
8. Catch `ActivityNotFoundException` → `HostFileOpenEvent.NoAppHandler`.
9. Other launch failures → `HostFileOpenEvent.Failed(OPEN_FAILED_MESSAGE)`.
10. Success → `HostFileOpenEvent.LaunchSucceeded`.

Do not use a WebView. Do not inspect `inAppFilePreviewEnabled`. Observer sessions may call this; there is no controller gate.

- [ ] **Step 1: Write the failing test**

Keep Android `startActivity` out of the JVM policy module. Test the helper by injecting `startActivity`:

```kotlin
class HostFileExternalOpenTest {
    @Test
    fun noHandlerFromStartActivityMapsToNoAppHandlerEvent() = runTest {
        val event = openManagedHostFileForTest(
            load = {
                Result.success(
                    HostFileContent(
                        name = "report.pdf",
                        path = "/home/mark/out/report.pdf",
                        mimeType = "application/pdf",
                        bytes = byteArrayOf(1, 2, 3),
                    ),
                )
            },
            startActivity = { throw android.content.ActivityNotFoundException() },
        )
        assertEquals(HostFileOpenEvent.NoAppHandler, event)
    }

    @Test
    fun downloadFailureMapsToFailedEventWithoutSilentIgnore() = runTest {
        val event = openManagedHostFileForTest(
            load = { Result.failure(IllegalStateException("Could not download file")) },
            startActivity = { error("must not launch") },
        )
        assertEquals(HostFileOpenEvent.Failed("Could not download file"), event)
    }
}
```

If extracting a Context-free function is awkward, put the mapping in `HostFileOpenPolicy.eventForLaunchThrowable(error: Throwable): HostFileOpenEvent` and test that instead:

```kotlin
@Test
fun activityNotFoundBecomesNoAppHandler() {
    assertEquals(
        HostFileOpenEvent.NoAppHandler,
        HostFileOpenPolicy.eventForLaunchThrowable(ActivityNotFoundException()),
    )
}
```

Prefer this if `openManagedHostFile` stays in `HermesApp.kt` as a private function. Then Task 1's reducer tests already cover the UI state. Add `eventForLaunchThrowable` to Task 1's policy object if not already present — if Task 1 shipped without it, add the test here first so it fails, then add the function.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests com.unsupportedpastels.hermesandroid.files.HostFileOpenPolicyTest`

Expected: FAIL on missing `eventForLaunchThrowable` (or FAIL on missing `openManagedHostFileForTest`).

- [ ] **Step 3: Write minimal implementation**

Add to `HostFileOpenPolicy`:

```kotlin
fun eventForLaunchThrowable(error: Throwable): HostFileOpenEvent =
    if (error is ActivityNotFoundException || error.classNameEndsWith("ActivityNotFoundException")) {
        HostFileOpenEvent.NoAppHandler
    } else {
        HostFileOpenEvent.Failed(OPEN_FAILED_MESSAGE)
    }
```

`ActivityNotFoundException` is an Android type. To keep the policy Android-free, match on `error::class.java.name == "android.content.ActivityNotFoundException"` or `error.javaClass.simpleName == "ActivityNotFoundException"` so unit tests can pass a stub:

```kotlin
class FakeActivityNotFoundException : RuntimeException()
// production: simpleName check plus message, or pass a sealed LaunchFailure from the Android wrapper
```

Cleaner production shape (use this):

```kotlin
sealed interface HostFileLaunchFailure {
    data object NoHandler : HostFileLaunchFailure
    data class Other(val message: String?) : HostFileLaunchFailure
}

fun eventForLaunchFailure(failure: HostFileLaunchFailure): HostFileOpenEvent = when (failure) {
    HostFileLaunchFailure.NoHandler -> HostFileOpenEvent.NoAppHandler
    is HostFileLaunchFailure.Other -> HostFileOpenEvent.Failed(
        failure.message?.take(160)?.takeIf { it.isNotBlank() } ?: OPEN_FAILED_MESSAGE,
    )
}
```

Android wrapper catches `ActivityNotFoundException` and passes `HostFileLaunchFailure.NoHandler`. Policy stays platform-independent.

Implement `openManagedHostFile` next to `writeSharedArtifact` in `HermesApp.kt`. Reuse the existing `shared-artifacts` FileProvider path; do not add a second cache directory unless `file_paths.xml` is updated in the same change (prefer reuse so the manifest stays as-is).

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests com.unsupportedpastels.hermesandroid.files.HostFileOpenPolicyTest`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/unsupportedpastels/hermesandroid/files/HostFileOpenPolicy.kt \
  app/src/test/java/com/unsupportedpastels/hermesandroid/files/HostFileOpenPolicyTest.kt \
  app/src/main/java/com/unsupportedpastels/hermesandroid/ui/HermesApp.kt
git commit -m "$(cat <<'EOF'
feat(files): open downloaded host files through FileProvider view

Share already stages bytes in the app-private cache. Reuse that
grant so another app can view the file without a new exported
component or a WebView.
EOF
)"
```

---

### Task 7: Chat surfaces — tappable host links and MEDIA chips

**Files:**
- Modify: `app/src/main/java/com/unsupportedpastels/hermesandroid/ui/MessageMarkdown.kt`
- Modify: `app/src/main/java/com/unsupportedpastels/hermesandroid/ui/HermesApp.kt` (`SessionDetailScreen` / `MarkdownMessage` call sites)

**Interfaces:**
- Consumes: `MarkdownMessage(onOpenManagedPath)`, `MarkdownFileChipBlock`, `openManagedHostFile`, `HostFileOpenPolicy.reduce`.
- Produces: chat markdown host-path links and non-picture `MEDIA:` chips that download and `ACTION_VIEW`. Per-source `HostFileOpenUiState` on the chip (Opening… / error). Link taps share the same opener; show error on the nearest chip if the source matches, otherwise a one-line error under the message is acceptable if a chip is not present — prefer a small status line on the chip only, and for standalone links set a message-level error `Text` in `MarkdownMessage` keyed by source.

Chip UI (behavior, not a screenshot target): filename `AssistChip` or compact `Surface`+`Text`; while opening, label `Opening…`; on failure, error color `bodySmall` with the reducer message. Do not render HTML/SVG. Do not special-case mermaid.

- [ ] **Step 1: Write the failing test**

Do not add a Compose screenshot. Pin chip rendering through parse tests from Task 4 plus policy tests from Task 1. If a Compose test is needed for the chip label, add a focused JVM Compose test that supplies `parseMessageMarkdown` output through `MarkdownMessage` and asserts `onNodeWithText("report.pdf")` and `onNodeWithText("Opening…")` after click by faking a hanging loader — only if that is cheap with existing `createComposeRule`. Skip if wiring `MarkdownMessage` requires the full `HermesApp`. Prefer extracting `MarkdownFileChip(displayName, state, onClick)` as `internal` and testing:

```kotlin
@Test
fun fileChipShowsOpeningLabelThenNoHandlerError() {
    // Compose test of MarkdownFileChip only
}
```

If extracting a tiny composable feels like a new pattern, skip the Compose test and rely on reducer tests. Do not manufacture visual tests.

- [ ] **Step 2: Run test to verify it fails**

Run the chip Compose test if written; otherwise skip to implementation after Task 4/1 tests still pass.

- [ ] **Step 3: Write minimal implementation**

In `MarkdownMessage`:

- Hold `var openStates by remember { mutableStateOf<Map<String, HostFileOpenUiState>>(emptyMap()) }`.
- `onClick(path)` launches a coroutine: reduce `Requested`, call `onOpenManagedFile(path)` passed from the parent (parent owns `openManagedHostFile`), reduce the resulting event.
- Render `MarkdownFileChipBlock` with filename, Opening…, error.
- Pass `onOpenManagedPath` into annotated links.

In `SessionDetailScreen`, pass `onLoadManagedFile` into `MarkdownMessage` via a lambda that calls `openManagedHostFile`. Images still use `RemoteMediaImage`. Audio in chat as `MEDIA:` of a non-image audio file is a chip that opens externally; Artifacts sheet audio player is unchanged.

- [ ] **Step 4: Run tests**

Run: `./gradlew testDebugUnitTest --tests com.unsupportedpastels.hermesandroid.ui.MessageMarkdownTest --tests com.unsupportedpastels.hermesandroid.files.HostFileOpenPolicyTest`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/unsupportedpastels/hermesandroid/ui/MessageMarkdown.kt \
  app/src/main/java/com/unsupportedpastels/hermesandroid/ui/HermesApp.kt
git commit -m "$(cat <<'EOF'
feat(chat): open tapped host files from markdown and MEDIA chips

Chat already showed those paths as links or dead MEDIA lines.
Download through the existing managed-file read so the phone's
other apps can view them.
EOF
)"
```

---

### Task 8: Artifacts sheet Open for managed host files

**Files:**
- Modify: `app/src/main/java/com/unsupportedpastels/hermesandroid/ui/HermesApp.kt` (`ArtifactBrowserSheet`)
- Modify: `app/src/test/java/com/unsupportedpastels/hermesandroid/files/HostFileOpenPolicyTest.kt` (artifact Open availability already in Task 1)

**Interfaces:**
- Consumes: `HostFileOpenPolicy.artifactOpenAvailable`, `openManagedHostFile`, existing Share/Save.
- Produces: an **Open** `TextButton` on managed-path artifacts (including files that also have Share/Save). Remote URL Open stays as today's `uriHandler.openUri`. Images keep in-app preview/zoom. Managed audio keeps Play. Open still uses `ACTION_VIEW` for managed files (optional extra for images/audio; required for `ArtifactType.File`). Show Opening… on the Open button (`enabled = false` while opening that artifact) and the reducer error under that row.

- [ ] **Step 1: Write the failing test**

Policy already asserts `artifactOpenAvailable(ManagedPath)`. Add a Compose test only if there is an existing Artifact sheet test harness. Search `HermesAppTest` for `"Share"` / `"Artifacts"` before adding UI tests. If none exists, do not start a screenshot suite; the policy test is the required gate. Optionally add:

```kotlin
@Test
fun managedPathArtifactsExposeOpenWhileRemoteUrlsKeepExistingOpen() {
    assertTrue(HostFileOpenPolicy.artifactOpenAvailable(ArtifactOrigin.ManagedPath))
    assertFalse(HostFileOpenPolicy.artifactOpenAvailable(ArtifactOrigin.RemoteUrl))
}
```

If Task 1 already has this, do not duplicate. Proceed to implement the button.

- [ ] **Step 2: Run test to verify it fails**

Only if a new test was added. Otherwise continue.

- [ ] **Step 3: Write minimal implementation**

In `ArtifactBrowserSheet`, next to Share/Save for `artifact.origin == ArtifactOrigin.ManagedPath`:

```kotlin
TextButton(
    enabled = openState !is HostFileOpenUiState.Opening,
    onClick = { openManaged(artifact) },
) {
    Text(if (openState is HostFileOpenUiState.Opening) HostFileOpenPolicy.OPENING_LABEL else "Open")
}
```

Keep Share and Save. `openManaged` copies `shareManaged` but calls `openManagedHostFile` instead of `ACTION_SEND` chooser. Per-artifact `openStates` map keyed by `stableIdentity`.

- [ ] **Step 4: Run tests**

Run: `./gradlew testDebugUnitTest --tests com.unsupportedpastels.hermesandroid.files.HostFileOpenPolicyTest --tests com.unsupportedpastels.hermesandroid.artifacts.ArtifactExtractorTest`

Expected: PASS. Extractor behavior unchanged.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/unsupportedpastels/hermesandroid/ui/HermesApp.kt
git commit -m "$(cat <<'EOF'
feat(artifacts): open managed host files from the artifacts sheet

Share and Save already downloaded the bytes. Add Open so a host
file can be viewed in another app without leaving those actions.
EOF
)"
```

---

### Task 9: Host files sheet — tap a file to open

**Files:**
- Modify: `app/src/main/java/com/unsupportedpastels/hermesandroid/ui/HermesApp.kt` (`HostFileBrowserSheet`)

**Interfaces:**
- Consumes: `HostFileOpenPolicy.hostFileRowAction`, `openManagedHostFile`.
- Produces: file row tap opens the file; directory tap still drills; **Attach** stays on rows as today. Opening… / error on that row. Pass `onLoadManagedFile` into the sheet (SessionDetail already has it).

- [ ] **Step 1: Write the failing test**

Task 1 already tests `hostFileRowAction`. Do not add a screenshot of the sheet. If a Host files Compose test exists, extend it; otherwise stop at the policy test.

Confirm folders remain `DrillFolder` and files `OpenFile` — already in Task 1. Add a test only if `Attach` eligibility needs a policy function. Attach remains a trailing `TextButton` for every entry, including folders, matching current behavior.

- [ ] **Step 2: Run test to verify it fails**

Skip if no new test.

- [ ] **Step 3: Write minimal implementation**

Change `HostFileBrowserSheet` signature to take `onLoadManagedFile`. Row `clickable`:

```kotlin
when (val action = HostFileOpenPolicy.hostFileRowAction(entry)) {
    HostFileRowAction.DrillFolder -> load(entry.path)
    is HostFileRowAction.OpenFile -> openPath(action.path, entry.name)
    HostFileRowAction.Ignore -> Unit
}
```

Enable click for both files and folders (`enabled = !loading`). Keep Attach `TextButton`. Show `HostFileOpenPolicy.OPENING_LABEL` on the opening row (supporting or trailing text) and the failed message in error color on that row, not as a sheet-wide silent no-op. Sheet-level `error` can remain for listing failures.

Update `HostFileBrowserSheet(...)` call site in `SessionDetailScreen` to pass `onLoadManagedFile`.

- [ ] **Step 4: Run tests**

Run: `./gradlew testDebugUnitTest --tests com.unsupportedpastels.hermesandroid.files.HostFileOpenPolicyTest --tests com.unsupportedpastels.hermesandroid.files.HostFileModelsTest`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/unsupportedpastels/hermesandroid/ui/HermesApp.kt
git commit -m "$(cat <<'EOF'
feat(files): open a host file from the browser sheet on tap

Folders already drilled and Attach already staged a reference.
Tapping a file now views it the same way chat and artifacts do.
EOF
)"
```

---

### Task 10: Intent, observer, and security closeout

**Files:**
- Modify: `app/src/main/AndroidManifest.xml` only if package-visibility `<queries>` is required for `ACTION_VIEW`. Prefer catching `ActivityNotFoundException` without adding queries; `startActivity` still throws when nothing handles the MIME type.
- Do not modify `SECURITY.md` unless the FileProvider / no-WebView sentence would become false (it must stay true).
- Modify tests only if a manifest or policy assertion is missing.

**Interfaces:**
- Consumes: existing FileProvider; `readManagedFile`.
- Produces: no new exported components; no new Hermes routes; observer can open.

Checklist the implementer verifies by reading the diff (not by screenshots):

- FileProvider still `exported=false`.
- Open grants read URI permission only.
- No WebView, no `loadData` of HTML/SVG, no mermaid renderer.
- `inAppFilePreviewEnabled` is not read by the open path.
- Chat, Artifacts Open, and Host files all call the same helper.
- Errors use `HostFileOpenPolicy.NO_HANDLER_MESSAGE` / bounded download errors on the row or chip.
- Required later gate (run after implementation, not during this plan-only change):

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

- [ ] **Step 1: Write any missing failing test**

If Task 1–9 already pin no-handler, skip new tests. If Open accidentally used `createChooser`, do not add a test for chooser copy; code review is enough.

- [ ] **Step 2: Confirm no WebView / no new routes**

Grep the diff for `WebView`, `/api/files/` additions, and `android:exported="true"`. Only MainActivity stays exported.

- [ ] **Step 3: Commit only if this task changed files**

```bash
git add app/src/main/AndroidManifest.xml SECURITY.md
git commit -m "$(cat <<'EOF'
fix(files): keep host-file open on the existing FileProvider grant

Opening in another app must not widen export surface or start
rendering untrusted HTML inside HAM.
EOF
)"
```

If nothing changed, skip the commit.

---

## Out of scope (explicit)

- In-app file preview renderer (HTML, SVG, mermaid, PDF, code). The Files switch stays disabled.
- Changing audio playback on the Artifacts sheet.
- Changing in-app image preview / zoom.
- Treating fenced mermaid (or any fenced code) as a file.
- New Hermes server routes, multi-subscriber streaming, or runtime takeover.
- Screenshot golden updates.
- Running `./gradlew testDebugUnitTest lintDebug assembleDebug` as part of writing this plan.

## Self-review

1. Spec coverage: default tap-to-open; four surfaces; images/audio/https/mermaid/security; no silent failure + Opening…; Files hub placement/copy/disabled persist; observer read path; TDD list — each has a task.
2. Placeholder scan: no TBD; in-app preview is named out of scope rather than deferred inside a task body.
3. Type names: `HostFileOpenPolicy`, `MarkdownLinkTarget`, `MediaLineKind`, `HostFileOpenUiState`, `FilesPreferences`, `SettingsFilesRoute` are consistent across tasks.
