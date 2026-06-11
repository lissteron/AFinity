# AFinity Local Library v2 Architecture Contract

Date: 2026-06-11
Repository: `/root/source/android/AFinity`
Scope: Jellyfin offline video downloads, local library import, offline Home, offline playback, local storage settings.

This artifact defines the target architecture for replacing AFinity's current app-internal download layout with a portable local media library. It is intentionally written as an implementation contract and proof checklist, not as a loose product note.

## Problem Classification

This is a systemic ownership problem.

The broken invariant:

> A downloaded or locally available video must remain discoverable and playable from files on disk even if Room database projections are missing, stale, or rebuilt.

Current behavior violates this invariant because downloaded content visibility depends on a combination of:

- `downloads` rows;
- local `sources`;
- cached `movies`, `shows`, `seasons`, and `episodes`;
- current session scope;
- app-specific folder layout.

Observed prior failure shape:

- completed downloads and local sources existed;
- media cache tables such as `episodes` and `movies` were incomplete;
- offline Home could not reliably reconstruct visible content from files.

The rejected local fix is another ad hoc `reconcileCompletedDownloads()` path inside `JellyfinDownloadRepository`. That only patches one projection after it is already broken. The architectural fix is to make the local library folder the durable source of truth, with Room as a rebuildable index.

## Locked Decisions

- Local media files are first-class library entries, not merely side effects of `DownloadDto`.
- AFinity supports multiple local library folders at the same time.
- The user can attach folders from device storage, app-specific storage, SD/removable storage, and SAF-selected custom folders.
- The app indexes all enabled local library folders into one unified offline library.
- One folder can be the default write target for new downloads, but no single folder is the whole local library.
- Root configuration is durable app configuration, not a Room-only table.
- Room stores rebuildable media indexes, scan snapshots, and queue state. It must not be the only authority for what local media exists.
- The downloader writes the same portable format that the scanner can later import.
- A completed media file with a failed metadata/index import is a repairable local-library state, not a lost file and not a terminal media-transfer failure.
- Files that AFinity did not download can still be imported and played when they are parseable.
- Local playback is resolved through a playback-source resolver, not by passing raw database path strings directly to the player.
- Sidecar metadata must never contain Jellyfin access tokens, cookies, refresh tokens, API keys, auth headers, or credential-bearing URLs.
- Physical media deletion is a separate explicit user action owned by local-library deletion policy, not a side effect of cancelling a queue row or removing a root from settings.
- Legacy UUID download folders are supported only as an import/migration source, not as the long-term format.
- Physical connected-device installs/tests remain out of scope unless explicitly authorized in the current turn.

## Goals

- Store video files in human-readable folders.
- Keep metadata sidecars near the media file.
- Allow the local media library to survive Room DB deletion.
- Allow recovery after app data reset or reinstall by re-attaching the same folders and reading root markers/sidecars from disk.
- Allow a user to attach several folders, for example internal storage plus SD card.
- Allow AFinity to import already-existing local Jellyfin/Kodi-style folders.
- Make offline Home and playback depend on the local library index, not completed download rows.
- Keep the current UIDT/single-transfer queue model; only the storage/library ownership changes.

## Non-Goals

- Do not redesign Jellyfin auth/session storage unless required for scanner enrichment.
- Do not implement multi-download concurrency.
- Do not change optimized transcoding profile decisions except where file naming needs the final extension/container.
- Do not require every manually imported file to match a Jellyfin server item before it can appear locally.
- Do not auto-delete or auto-move old files without explicit user action and verification.
- Do not store Jellyfin credentials or request URLs with API keys in sidecars.
- Do not make SAF roots second-class; custom user-selected folders are a primary storage mode.

## Target Folder Format

The canonical root folder is any user-attached library root. It may live on internal storage, SD card, or SAF-selected storage.

Example:

```text
AFinity Library/
  .afinity-root.json

  Movies/
    WALL-E (2008)/
      WALL-E (2008).mkv
      movie.nfo
      poster.jpg
      fanart.jpg
      WALL-E (2008).afinity.json

  Shows/
    Bluey/
      tvshow.nfo
      poster.jpg
      fanart.jpg
      Season 01/
        Bluey - S01E01 - The Magic Xylophone.mkv
        Bluey - S01E01 - The Magic Xylophone.nfo
        Bluey - S01E01 - The Magic Xylophone.en.srt
        Bluey - S01E01 - The Magic Xylophone.afinity.json
```

### Naming Rules

Movies:

```text
Movies/{movieTitle} ({year})/{movieTitle} ({year}).{ext}
Movies/{movieTitle} ({year})/movie.nfo
Movies/{movieTitle} ({year})/{movieTitle} ({year}).afinity.json
```

Episodes:

```text
Shows/{showTitle}/Season {seasonNumber2}/{showTitle} - S{seasonNumber2}E{episodeNumber2} - {episodeTitle}.{ext}
Shows/{showTitle}/Season {seasonNumber2}/{showTitle} - S{seasonNumber2}E{episodeNumber2} - {episodeTitle}.nfo
Shows/{showTitle}/Season {seasonNumber2}/{showTitle} - S{seasonNumber2}E{episodeNumber2} - {episodeTitle}.afinity.json
```

Rules:

- Sanitize path segments deterministically.
- Preserve readable names.
- Avoid UUID-only folders in the canonical format.
- Use `.part` for in-progress media writes.
- Atomic completion is `finalFile.part -> finalFile`, then sidecars, then index update.
- If a name collision exists, append a stable suffix such as source label or short item id.

## Sidecar Contract

AFinity writes two metadata sidecars when it has enough data:

- `.nfo` for Jellyfin/Kodi compatibility and human-readable import.
- `.afinity.json` for exact AFinity/Jellyfin identity and rebuild fidelity.

### `.afinity-root.json`

Each writable root managed by AFinity must contain a root marker before it can receive downloads:

```json
{
  "schemaVersion": 1,
  "rootId": "uuid",
  "createdBy": "AFinity",
  "createdAt": 1781170000000,
  "displayName": "SD Card",
  "libraryLayout": "afinity-local-library-v2"
}
```

Rules:

- A writable root without a marker can be attached for scanning, but it cannot become the default download target until AFinity writes the marker.
- A read-only root without a marker can be attached and scanned, but local-only identities derived from that root have lower stability.
- If app data is reset and the user re-attaches the same marked root, AFinity must reuse the marker `rootId`.
- If app data is reset and the user re-attaches an unmarked read-only root, AFinity must rebuild from sidecars and fingerprints; it must not assume the new generated root id is the same old root.

### `.afinity.json`

Minimum shape:

```json
{
  "schemaVersion": 1,
  "mediaKind": "episode",
  "server": {
    "serverId": "jellyfin-server-id",
    "serverName": "Home Jellyfin",
    "baseUrlHint": "http://192.168.0.200:8096"
  },
  "user": {
    "userId": "jellyfin-user-id"
  },
  "identity": {
    "itemId": "jellyfin-item-id",
    "sourceId": "jellyfin-media-source-id",
    "providerIds": {
      "Imdb": "tt1234567",
      "Tmdb": "12345"
    }
  },
  "localIdentity": {
    "localItemId": "uuid-or-stable-local-id",
    "stableRootId": "root-marker-uuid-if-known",
    "relativePathAtWrite": "Shows/Bluey/Season 01/Bluey - S01E01 - The Magic Xylophone.mkv",
    "fingerprint": {
      "strategy": "size-mtime-sampled-hash-v1",
      "value": "stable-fingerprint"
    }
  },
  "titles": {
    "name": "The Magic Xylophone",
    "showName": "Bluey",
    "year": 2018,
    "seasonNumber": 1,
    "episodeNumber": 1
  },
  "mediaFile": {
    "relativePath": "Shows/Bluey/Season 01/Bluey - S01E01 - The Magic Xylophone.mkv",
    "container": "mkv",
    "sizeBytes": 123456789,
    "runtimeTicks": 4200000000,
    "videoCodec": "h264",
    "audioCodec": "aac",
    "width": 1920,
    "height": 1080
  },
  "download": {
    "qualityMode": "Original file",
    "downloadedAt": 1781170000000,
    "downloadedByAFinity": true
  }
}
```

Rules:

- `server`, `user`, and Jellyfin ids may be missing for manually imported files.
- `baseUrlHint` must be sanitized to an origin/base URL only. It must not include query parameters, API keys, bearer tokens, cookies, or user-specific request paths.
- `user.userId` is an optional Jellyfin identity hint and privacy-sensitive portable metadata. It is not a credential, not an authorization rule, and may be omitted for manually imported or privacy-preserving sidecars.
- If Jellyfin identity is missing and `.afinity.json` has `localIdentity.localItemId`, that id is authoritative for AFinity's local-only item.
- If Jellyfin identity and `localItemId` are both missing, create a deterministic candidate identity from file fingerprint plus relative path.
- `localIdentity.stableRootId` means the durable root marker id from `.afinity-root.json.rootId`. Do not store a newly generated post-reset registry id as proof that old local-only identity is unchanged.
- If the file later matches a Jellyfin item, merge identity without changing the local playable file path.
- `.afinity.json` is authoritative for AFinity-specific identity when present.
- `.nfo` is the compatibility fallback when `.afinity.json` is missing.
- Filename parsing is the last fallback.

### Sidecar Security and Privacy

Sidecars are portable files. Treat them as user-exportable data.

Forbidden sidecar content:

- Jellyfin access tokens;
- refresh tokens;
- cookies;
- bearer tokens;
- `api_key` query parameters;
- Authorization headers;
- full stream/download URLs containing credentials;
- local app-private database paths that are not meaningful outside this install.

Allowed sidecar content:

- Jellyfin item ids and provider ids;
- optional Jellyfin user ids as identity hints only;
- sanitized server origin hints;
- local relative paths;
- media fingerprints and stream metadata;
- human-readable titles and artwork references.

If a future implementation needs credentialed metadata repair, it must use the app's secure credential store after matching the sidecar identity. It must not read credentials from sidecar files.

### Profile and Visibility Contract

File existence is not the same as profile visibility. The scanner may discover a playable file, but UI visibility must be decided by a dedicated local-library visibility owner.

Add a local visibility policy owner, for example:

```text
LocalLibraryVisibilityPolicy
local_media_visibility
```

Rules:

- AFinity-downloaded files default to visible for the app profile/Jellyfin user that created the download.
- Manually imported files without a Jellyfin user hint default to local-only imported content and must pass the current profile and kid-mode policy before appearing in Home.
- Kid mode must never expose newly imported local-only content merely because the file exists. Parent unlock or an explicit allowed-profile/import policy is required.
- `local_media_user_state` stores progress/played/favorite state. It is not the visibility authority.
- `.afinity.json.user.userId` can help match a sidecar to a known Jellyfin user, but it must not be treated as an authorization token.
- If app data is reset, profile-specific visibility preferences may be lost. Re-attached roots must still be scan-recoverable, but local-only content should re-enter through the import/visibility policy instead of bypassing profile restrictions.
- Offline Home, parent-play selection, downloads history, and playback launch must all apply the same visibility policy before resolving a local source.

## Multi-Folder Library Model

AFinity must support a list of library roots.

Root examples:

- internal app-specific folder;
- device shared storage folder;
- secondary/removable storage folder;
- SAF custom tree on SD card;
- SAF custom tree in a user-chosen Downloads/Movies folder.

The storage setting should not be a single selected location anymore. It should become:

- attached local library folders;
- enabled/disabled state per folder;
- mounted/unmounted state per folder;
- read-only/read-write capability;
- default download target;
- optional per-download target override.

### Root State

Root state is split into two layers:

- Durable root registry: app configuration stored outside Room, for example DataStore/preferences plus Android persisted URI permissions for SAF roots.
- Room root snapshot: rebuildable projection used for joins, scan status, and local library queries.

Room DB deletion must not delete configured roots. If the root registry is lost because app data was cleared or the app was reinstalled, the user may need to re-attach SAF folders, but the library must still be recoverable from root markers and sidecars after re-attachment.

Suggested durable registry model:

```kotlin
data class LocalLibraryRootRecord(
    val registryId: UUID,
    val stableRootId: UUID?,
    val displayName: String,
    val kind: LocalLibraryRootKind,
    val uriOrPath: String,
    val enabled: Boolean,
    val writable: Boolean,
    val removable: Boolean,
    val defaultForDownloads: Boolean,
    val priority: Int,
    val persistedUriPermission: Boolean,
    val lastKnownAvailable: Boolean,
)
```

Suggested Room snapshot model:

```kotlin
data class LocalLibraryRootSnapshotDto(
    val registryId: UUID,
    val stableRootId: UUID?,
    val enabled: Boolean,
    val available: Boolean,
    val writable: Boolean,
    val lastScanStartedAt: Long?,
    val lastScanCompletedAt: Long?,
    val lastScanStatus: LocalLibraryScanStatus?,
    val lastError: String?,
)
```

Root kinds:

```kotlin
enum class LocalLibraryRootKind {
    APP_PRIVATE,
    DEVICE_SHARED,
    SECONDARY_FILE_PATH,
    SAF_TREE,
}
```

Rules:

- Multiple roots can be enabled at the same time.
- At most one writable root is the default download target.
- A disabled root is not scanned and does not contribute visible content.
- An unavailable root is retained in settings but its files are marked temporarily unavailable.
- Removing a root from AFinity must not delete files unless the user explicitly chooses a destructive cleanup action.
- A read-only root can be indexed and played but cannot receive downloads or sidecar repairs.
- SAF roots must persist URI permissions and surface permission loss clearly.
- Root priority resolves duplicates only after identity and file availability are considered.
- Root registry writes are owned by `LocalLibraryRootStore`; scanner code may update only scan snapshots/status, not durable user configuration.
- A root created by AFinity must write `.afinity-root.json` before receiving downloads.
- `registryId` is an app-configuration id and may be regenerated after app data reset.
- `stableRootId` is the durable identity copied from `.afinity-root.json.rootId`; it is the only root id allowed in persistent local media identity.
- If a root has no marker, scanner may index it, but local-only identities from that root must be treated as lower-confidence and path/fingerprint based.

### Filesystem Boundary

Scanner, downloader, migration, sidecar reader/writer, and availability checks must depend on a local-library filesystem abstraction, not directly on `java.io.File`.

Suggested boundary:

```text
LocalLibraryFileSystem
FilePathLibraryFileSystem
SafTreeLibraryFileSystem
LocalLibraryNode
LocalLibraryWriteTarget
```

Required behavior:

- list children;
- stat file size and modification time;
- open input stream;
- create directories;
- create `*.part` output targets;
- finish a write atomically where the backend supports rename;
- emulate safe finish by copy/create/delete when SAF cannot rename atomically;
- keep unfinished writes and SAF staging documents out of the visible local library index;
- verify expected size and fingerprint where available before a staged write becomes importable;
- delete only through explicit user-approved cleanup paths;
- report read-only, missing permission, unmounted, and unavailable states distinctly.

No implementation review may accept a scanner or sidecar writer that works only for `File` roots.

### Duplicate Resolution

The scanner should merge entries into one visible item when they have the same durable identity.

Identity priority:

1. `.afinity.json` with same `serverId + itemId + sourceId`.
2. `.afinity.json` with same `providerIds` plus same media kind.
3. `.nfo` provider ids.
4. strong filename parse plus matching runtime/size fingerprint.
5. local-only root/path identity.

Playback source selection:

- prefer a mounted local file;
- prefer explicit user root priority;
- prefer exact Jellyfin source match when launching from a server item;
- prefer higher quality only when the user has not selected a root priority;
- never silently hide duplicates if identity confidence is low.

UI should expose ambiguous duplicates as separate local files until the user or a high-confidence scanner rule merges them.

## Database Ownership

Current `downloads` rows should stop being the source of local library truth.

Add local library tables, for example:

```text
local_library_root_snapshots
local_library_items
local_media_files
local_media_identities
local_media_sidecars
local_media_user_state
local_media_import_jobs
local_library_scan_runs
```

Suggested ownership:

- `downloads`: queue/progress/retry/cancel state only.
- durable root registry outside Room: configured folders and user choices.
- `local_library_root_snapshots`: rebuildable root availability and scan status.
- `local_media_files`: actual discovered playable files.
- `local_library_items`: normalized movies, shows, seasons, episodes for offline UI.
- `local_media_identities`: Jellyfin ids, provider ids, local-only ids.
- `local_media_sidecars`: sidecar paths and parse status.
- `local_media_user_state`: local playback progress and local played/favorite state.
- `local_media_import_jobs`: repairable media-completed/index-pending states and last import errors.
- existing media cache tables: online/server cache, not the only local-library projection.

The scanner owns writes to local library index tables.

The downloader writes files and sidecars, then invokes the same scanner/import path for the completed file. It must not manually update half a dozen projections in parallel.

## Scanner Contract

Create a dedicated scanner/importer component, for example:

```text
LocalLibraryScanner
LocalLibraryRootStore
LocalLibraryFileSystem
LocalLibraryIndexRepository
LocalLibrarySidecarReader
LocalLibrarySidecarWriter
LocalLibraryPathPolicy
LocalLibraryMigrationService
LocalLibraryDeletionPolicy
LocalPlaybackSourceResolver
```

Scanner behavior:

- scan all enabled roots;
- scan one root on demand;
- handle mounted/unmounted roots;
- ignore `*.part`, `*.download`, and known staging files;
- parse `.afinity.json`;
- parse common `.nfo` fields;
- parse show-level `tvshow.nfo` and season-level metadata when present;
- parse movie and episode filenames;
- synthesize minimal show and season rows from episode sidecars when show/season sidecars are missing;
- discover subtitles next to media files;
- discover poster/fanart files;
- compute stable file fingerprints without hashing entire large files by default;
- update index idempotently;
- mark missing files as unavailable, not immediately deleted;
- avoid duplicate visible rows for high-confidence identity matches;
- run incrementally and support cancellation without corrupting the index;
- resume a cancelled/interrupted scan without starting from an inconsistent state;
- perform expensive SAF scans off the UI thread and report progress;
- produce a scan summary for UI and tests.

Required scan outputs:

```kotlin
data class LocalLibraryScanSummary(
    val rootId: UUID,
    val discoveredFiles: Int,
    val importedItems: Int,
    val updatedItems: Int,
    val unavailableItems: Int,
    val duplicateGroups: Int,
    val parseWarnings: Int,
    val cancelled: Boolean,
    val errors: List<String>,
)
```

## Downloader Contract

Downloader v2 must:

- choose a writable root before starting;
- reserve a target path through `LocalLibraryPathPolicy`;
- write media to `*.part`;
- resume only when the `.part` matches the same download identity and request plan;
- atomically finish the media file;
- for SAF roots, treat copy/create/delete finish as incomplete until the final document size and identity are verified;
- write `.afinity.json`;
- write `.nfo` when enough metadata exists;
- write subtitles/images in the canonical location;
- call scanner/importer for the completed file;
- mark the queue row completed only after the media file exists and is importable;
- if the media file is complete but scanner/import fails, move the local entry into a repairable import-pending state and surface a rescan/repair action.

If sidecar writing fails after the media file completes:

- the media file remains valid;
- queue completion may succeed with a warning;
- scanner can still import by filename;
- UI should surface "metadata incomplete" as repairable, not as a terminal download failure.

If scanner/import fails after the media file completes:

- the completed media file must remain in place;
- the queue row must not retry the network transfer from byte zero unless the media file is missing or invalid;
- `local_media_import_jobs` records the failure and target file identity;
- Home/Downloads/Settings surface a repairable "local import pending" or "metadata repair needed" state;
- rescan can recover the file without a new download.

If a process dies during SAF finish or staged copy:

- scanner must not index the partial target as playable media;
- recovery verifies the final document size/fingerprint before marking it importable;
- stale staging documents are surfaced as repairable cleanup, not as completed downloads.

## Local User State Contract

Local playback state must have an explicit owner. It must not be inferred only from Jellyfin cache rows.

Add a local user-state projection, for example `local_media_user_state`, keyed by:

- AFinity local item id;
- current app profile/user;
- optional Jellyfin `serverId + userId + itemId` when known.

Responsibilities:

- store local playback position for imported and downloaded files;
- store local played/favorite state when offline;
- merge with Jellyfin user data when online and identity is known;
- keep local-only progress stable across Room cache rebuilds;
- avoid overwriting newer local offline progress with stale server cache data.

Offline Home continue-watching and parent-play selection must read this owner for local files.

## Playback Source Contract

Playback must resolve local files through `LocalPlaybackSourceResolver`.

Responsibilities:

- accept a local media identity or local media file id;
- verify that the root is enabled and currently available;
- verify that the file exists and is readable through `LocalLibraryFileSystem`;
- return a player-safe source such as `file://`, `content://`, file descriptor, or another player-supported URI;
- attach local subtitles and external streams through resolver-owned URIs;
- reject unavailable roots with a storage-unavailable result;
- avoid falling back to a different episode/movie when the intended local source is missing.

The long-term model should not require `AfinitySource.path: String` to be the only playback contract for local media. Existing callers may use compatibility adapters during migration, but the resolver must become the owner of local playback source selection.

## Deletion and Removal Policy

Create `LocalLibraryDeletionPolicy` as the only owner of local media deletion decisions.

Required actions:

- Cancel queued download: remove or pause queue state only; do not delete completed media.
- Cancel active download: stop transfer and delete only the owned incomplete `*.part` file if safe.
- Remove from downloads history: remove queue/history rows only; do not delete imported local media.
- Remove from local library: hide/detach the indexed item from AFinity; do not delete physical files by default.
- Delete local media: delete media file, sidecars, subtitles, and artwork only after explicit user confirmation.
- Remove root from settings: detach the root; do not delete files by default.
- Cleanup legacy files after migration: allowed only after verified copy/import and explicit confirmation.

All destructive operations must go through `LocalLibraryFileSystem`, respect SAF permissions, and update index state idempotently. A failed physical delete must leave the index in a repairable state, not pretend the file is gone.

## UI/Settings Contract

Replace current single-location storage settings with a local library folders screen.

Expected controls:

- list attached folders;
- add folder;
- add SD/removable folder;
- add custom folder through Android folder picker;
- enable/disable folder;
- set default download target;
- rescan folder;
- repair/write root marker;
- show mounted/unmounted/read-only state;
- show permission-lost state for SAF roots;
- show used/free space per writable root where available;
- remove folder from AFinity without deleting files;
- remove item from local library without deleting physical files;
- delete physical local media behind explicit confirmation;
- show repair/import-pending states for files that exist but failed indexing;
- optional destructive cleanup action behind explicit confirmation.

Download flows:

- default target uses the configured default writable root;
- a per-download target picker can be added after the base architecture is stable;
- season/series batch download uses one chosen root for the batch unless explicitly overridden.

Offline Home:

- reads local library index;
- groups local files across all enabled roots;
- continues to respect kid mode and profile policy;
- does not require `downloads.status == COMPLETED` for visibility.

Player:

- resolves a mounted local source through `LocalPlaybackSourceResolver`;
- if the chosen root is unavailable, show a storage-unavailable state instead of falling back to a wrong episode;
- if online server item has a matching local file, prefer local playback according to existing offline/local-source policy.

## Legacy Migration

Current legacy shape:

```text
{root}/{serverId}/movies/{movieItemId}/media/{sourceId}.{ext}
{root}/{serverId}/shows/{showId}/seasons/{seasonNumber}/{episodeItemId}/media/{sourceId}.{ext}
```

Migration phases:

1. Read-only legacy scanner.
2. Preview target canonical paths.
3. Detect conflicts and duplicates.
4. Copy to new layout by default.
5. Verify copied file size and optional fingerprint.
6. Write `.afinity.json` and `.nfo`.
7. Index the new file.
8. Offer manual cleanup of old legacy files only after verified import.

Do not automatically move old files during background startup.

## Implementation Phases

### Phase 0: Contract and Characterization

- Add this artifact.
- Add tests that encode current failure mode: local files survive, Room projections are missing, offline content must be rebuildable.
- Add test fixtures for canonical movie and episode folders.

### Phase 1: Local Library Roots

- Add `LocalLibraryRootStore` backed by durable app configuration outside Room.
- Add `local_library_root_snapshots` as a rebuildable Room projection.
- Add `LocalLibraryFileSystem` with file-path and SAF-tree implementations.
- Replace single selected download location concept with a root list while keeping compatibility adapters for existing callers.
- Convert existing configured storage location into the first root on migration.
- Add settings state model for multiple roots.

### Phase 2: Sidecar Schema and Path Policy

- Implement `LocalLibraryPathPolicy`.
- Implement `.afinity-root.json` read/write.
- Implement `.afinity.json` read/write.
- Implement sidecar privacy validation that rejects credentials and credential-bearing URLs.
- Implement minimal `.nfo` writer/reader for movies, shows, seasons, and episodes.
- Add path collision tests.

### Phase 3: Scanner and Index

- Add local library index tables.
- Implement scanner for `.afinity.json`, `.nfo`, and filename fallback.
- Index multiple enabled roots into one unified local catalog.
- Implement duplicate resolution and mounted/unmounted handling.
- Implement cancellable/incremental scan execution and import-pending repair records.

### Phase 4: Offline UI/Playback Switch

- Move offline Home to local library index.
- Move offline direct play and parent play to local library sources.
- Add local user-state owner for imported/downloaded local files.
- Add `LocalLibraryVisibilityPolicy` and apply it to local-library Home sections, parent-play selection, and playback launch.
- Add `LocalPlaybackSourceResolver` and route local playback through it.
- Keep current online server cache behavior untouched.
- Add regression tests for "DB wiped, folder survives".

### Phase 5: Downloader v2

- Route new downloads through canonical folder target creation.
- Write media, sidecars, and scanner import through one path.
- Add repairable media-completed/import-pending state handling.
- Keep UIDT/global queue semantics.
- Stop using `DownloadDto.folderPath` as a durable library layout authority.

### Phase 6: Deletion Policy and Legacy Migration

- Add `LocalLibraryDeletionPolicy`.
- Split cancel, remove-from-history, remove-from-library, delete-physical-media, and remove-root semantics.
- Implement old UUID-layout scanner.
- Add migration preview.
- Add copy/verify/import.
- Add optional cleanup.

### Phase 7: Release Hardening

- Run unit tests and release build.
- Test with one internal root.
- Test with two roots: internal plus SD/SAF root.
- Test with root temporarily unavailable.
- Test with manually copied local Jellyfin-style folders.

## Mandatory Proof Gates

The implementation is not complete until these pass:

- Scanner imports a movie from canonical folder with `.afinity.json`.
- Scanner imports an episode from canonical folder with `.afinity.json`.
- Scanner imports a movie from `.nfo` without `.afinity.json`.
- Scanner imports an episode by filename fallback when no sidecar exists.
- Scanner indexes two enabled roots into one catalog.
- Root registry survives Room DB deletion and scanner can rebuild the media index from the same configured roots.
- Re-attaching a marked root after app data reset reuses `.afinity-root.json` identity.
- Duplicate item across two roots is merged or surfaced according to confidence rules.
- Unmounted root does not delete indexed local entries.
- Read-only SAF root can be scanned and played.
- SAF-root scanner/writer uses `LocalLibraryFileSystem`; no scanner path requires `java.io.File`.
- Large SAF/root scan can be cancelled, resumed, and does not block UI.
- Interrupted SAF write/finish leaves no indexed playable partial and recovery verifies or cleans the staged document.
- Default writable root receives new downloads.
- Changing default root affects only future downloads.
- Sidecar writer rejects tokens, cookies, auth headers, API-key URLs, and other credentials.
- Completed media with scanner/import failure is retained and appears as repairable import-pending state.
- Room DB wipe plus existing local folder rebuilds offline Home.
- Local playback works after DB rebuild.
- Local playback uses `LocalPlaybackSourceResolver`; unavailable roots produce storage-unavailable state and never play a different item as fallback.
- Local playback progress survives media cache rebuild through local user-state ownership.
- Local library visibility policy prevents local-only imports from bypassing profile and kid-mode restrictions.
- Show and season grouping is rebuilt from `tvshow.nfo`, season metadata, or episode sidecars.
- Cancel queued/active download does not delete completed imported media.
- Remove from downloads history does not delete local files.
- Remove from local library does not delete physical files by default.
- Physical media delete requires explicit confirmation and goes through `LocalLibraryFileSystem`.
- Legacy UUID-layout file can be migrated into canonical layout.
- Re-running scanner is idempotent.
- Deleting a root from settings does not delete files by default.

Recommended command gate:

```bash
ANDROID_HOME=/root/android-sdk ANDROID_SDK_ROOT=/root/android-sdk sh ./gradlew testDebugUnitTest --no-daemon
```

Release gate when implementation is complete:

```bash
ANDROID_HOME=/root/android-sdk ANDROID_SDK_ROOT=/root/android-sdk sh ./gradlew testDebugUnitTest :app:assembleRelease --no-daemon
```

## Risks

- SAF performance can be poor for deep recursive scans. Scanner must support incremental/on-demand scans and cancellation.
- Removable storage can disappear while scanning or playing. Root availability must be explicit.
- Filename fallback can create false matches. Low-confidence imports must remain local-only until matched.
- Existing UI may assume one storage location. Compatibility adapters are needed during migration.
- Existing `DownloadStorageManager` mixes root selection, target creation, and sidecar creation. It should be split rather than extended indefinitely.
- Existing offline Home may still rely on server cache tables. The local library index must become the offline source.
- Existing delete/cancel paths physically delete current internal folders. They must be replaced with deletion-policy actions before Local Library v2 becomes writable.
- Existing `AfinitySource.path` local playback assumptions may hide SAF and unavailable-root failures. They must be routed through the playback resolver.

## Stop Condition

Stop only when the durable local-library invariant is enforced:

> Any media file in an enabled local library root can be discovered, indexed, shown in offline Home, and played without relying on a pre-existing completed download row or pre-existing cached episode/movie row.

The minimum acceptable implementation proves this for:

- one movie;
- one TV episode;
- two simultaneously attached roots;
- a DB-wipe rebuild;
- a root temporarily unavailable and later restored.
