# AFinity UIDT Single-Thread Download Queue Implementation Contract

Date: 2026-06-02
Repository: `/root/source/android/AFinity`
Primary target: Samsung tablet on Android 16 / API 36
Compatibility target: HUAWEI MRDI-W09 on HarmonyOS 4.3 / Android API 31

This file is the execution contract for replacing AFinity's per-media WorkManager download storm with a single-thread media download queue. It is intentionally structured as contracts and proof gates, not as a loose implementation wish list.

## Locked Decisions

- The media download queue is global across all Jellyfin servers and users.
- The queue runs exactly one active media transfer at a time.
- API 34+ uses one user-initiated data transfer job through platform `JobScheduler`.
- API 31-33, including HarmonyOS 4.3/API 31, uses one WorkManager foreground fallback queue worker.
- The current-session downloads screen may remain scoped, but global queue activity must be visible through an app-wide status source.
- `serverId` and `userId` on the claimed download row own transfer identity. Active transfer code must not depend on `sessionManager.currentSession`.
- System interruption, scheduler startup failure, missing required network, foreground-service denial, process death, and user/system stop are resumable states: leave rows `QUEUED` or `PAUSED`, not terminal `FAILED`.
- Real terminal transfer errors remain `FAILED`.
- Connected tablets must not be installed, updated, or used for connected test APKs unless the user explicitly allows it in that turn.

## Current Problem Evidence

Current media downloads create one WorkManager chain per media row:

- `app/src/main/java/com/makd/afinity/data/repository/download/JellyfinDownloadRepository.kt`
- `queueDownloadWork(...)`
- `beginUniqueWork("download_${download.id}", ...)`
- `MediaDownloadWorker`
- chained `TrickplayDownloadWorker`, `ImageDownloadWorker`, `SubtitleDownloadWorker`

`MAX_CONCURRENT_DOWNLOADS = 2` exists in `JellyfinDownloadRepository`, but it does not enforce queue concurrency.

Observed Samsung failure:

```text
NOT ALLOWED TO START SERVICE ... androidx.work.impl.foreground.SystemForegroundService ... APP IS IN BACKGROUND
```

The symptom is not just foreground promotion. A large season/series action can create many independent WorkManager records, so the app loses control of concurrency and recovery state.

## Official Android Sources

Use official Android docs as platform boundary:

- UIDT guide: https://developer.android.com/develop/background-work/background-tasks/uidt
- `JobInfo.Builder.setUserInitiated(...)`: https://developer.android.com/reference/android/app/job/JobInfo.Builder#setUserInitiated(boolean)
- `JobService`: https://developer.android.com/reference/android/app/job/JobService
- `JobParameters.getNetwork()`: https://developer.android.com/reference/android/app/job/JobParameters#getNetwork()
- Foreground-service background-start restrictions: https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
- Foreground-service timeouts: https://developer.android.com/develop/background-work/services/fgs/timeout

Platform facts that must be encoded in code:

- UIDT was introduced in Android 14 / API 34.
- UIDT has no Jetpack wrapper; use platform `JobScheduler`.
- UIDT scheduling must be from a visible/user-initiated foreground path unless a documented platform exception applies.
- UIDT requires a manifest `JobService` with `android.permission.BIND_JOB_SERVICE` and `android:exported="false"`.
- UIDT requires `android.permission.RUN_USER_INITIATED_JOBS`.
- UIDT requires `JobInfo.Builder.setUserInitiated(true)`.
- In Android 14/API 34, UIDT jobs must specify a required network through `setRequiredNetwork(...)` or `setRequiredNetworkType(...)`.
- If a job has a required network, transfer code must use `JobParameters.getNetwork()` for the actual network I/O; otherwise the default network may violate the scheduler constraint.
- UIDT requires `JobService.setNotification(...)`; call it immediately in `onStartJob`, before transfer work. Ten seconds is the platform ANR boundary for required UIDT notifications, but this project must call it immediately.
- `JobService` lifecycle callbacks run on the main thread; transfer work must be offloaded.
- If `onStartJob` starts async work, it must return `true`; completion must call `jobFinished(params, wantsReschedule)` unless `onStopJob` has already been called.
- After `onStopJob`, do not call `jobFinished` for that job instance.
- User-initiated jobs cannot be app-rescheduled when stopped by the user through a system affordance such as Task Manager.

## Non-Goals

- Do not redesign optimized transcoding profiles.
- Do not change offline playback semantics.
- Do not change Jellyfin auth/session storage format unless it is strictly necessary to restore a row-owned `serverId/userId` client.
- Do not remove WorkManager entirely; it remains the API 31-33 fallback.
- Do not implement multi-download concurrency.
- Do not change Audiobookshelf downloads unless explicitly required to preserve shared infrastructure.

## Requirements Matrix

Every implementation review must check this matrix. A requirement is not satisfied until owner, invariant, and proof are all implemented.

| ID | Source | Owner | Required invariant | Proof |
| --- | --- | --- | --- | --- |
| P1 | Android UIDT docs | `DownloadQueueScheduler` | API 34+ schedules platform UIDT only; API 31-33 never executes UIDT symbols. | API guard tests or code review plus API 31 compile/runtime compatibility. |
| P2 | Android UIDT docs | `DownloadQueueScheduler` + manifest | UIDT path declares `RUN_USER_INITIATED_JOBS`, declares `MediaDownloadQueueJobService`, calls `setUserInitiated(true)`, sets required network, sets storage-not-low, and sets payload estimates or documented unknown-size values. | Unit test/buildable scheduler construction plus manifest review. |
| P3 | Android UIDT docs | `AppVisibilityTracker` + `DownloadQueueScheduler` | UIDT schedule is only attempted from visible/user-initiated foreground paths. Background reconciliation may normalize DB state but must not call `JobScheduler.schedule(...)`. A deferred schedule remains observable and is retried on the next visible/user-triggered scheduling opportunity. | Scheduler tests for visible=false, visible=true, and deferred-to-visible liveness. |
| P4 | Android `JobService` docs | `MediaDownloadQueueJobService` | `onStartJob` calls `setNotification(...)` immediately, starts async runner work, returns `true`, and later calls `jobFinished` only if not stopped. `onStopJob` cancels runner work and owns stop persistence. | Focused lifecycle unit test/fake service test. |
| P5 | Android network docs | `UidtNetworkSession` / transfer runner | Required UIDT network is bound to all Jellyfin API calls and media stream requests. Missing or changed-to-null required network pauses; non-null network changes cancel stale-network I/O, rebind to the latest network, and resume from the last persisted byte; no default-network fallback. | Test injected `Network` creates network-bound OkHttp/Jellyfin client; network-change test cancels stale-network I/O and rebinds; null-network test pauses. |
| P6 | Existing WorkManager behavior | `MediaDownloadQueueWorker` | API 31-33 uses one fixed-name WorkManager queue worker with Wi-Fi/mobile and storage-not-low constraints. Foreground denial pauses and stops without tight retry. | WorkManager request construction tests and foreground-denial test. |
| Q1 | User requirement | `DownloadQueueRunner` + DAO | At most one media row is globally `DOWNLOADING` across all servers/users. | Duplicate runner/duplicate scheduler atomic claim test. |
| Q2 | User requirement | DAO / `DatabaseRepository` | Only the runner's atomic claim may move `QUEUED` to `DOWNLOADING`; repository and scheduler never optimistically mark `DOWNLOADING`. | DAO transition tests plus repository code review. |
| Q3 | Current code evidence | `DownloadQueueMigration` | Old `download_active` and `download_${id}` WorkManager chains are cancelled/neutralized before the new queue starts. Persisted old workers cannot transfer concurrently. | Migration test/fake WorkManager verification. |
| Q4 | Current code evidence | `SessionRestoreResolver` | Transfer identity is restored by the claimed row's `serverId` and `userId`. Do not use `currentSession`; do not use server-only token lookup. | Test with two users on same server proves correct token/client. |
| Q5 | Current code evidence | `MediaDownloadTransferRunner` | Progress/final writes are conditional on the row still being the active claim. Pause/cancel/delete wins over stale runner progress, completion, failure, sidecars, and local source creation. | Race tests for pause, cancel, delete, process death. |
| Q6 | Current code evidence | `MediaDownloadTransferRunner` / sidecar queue | Media transfer is the only foreground/UIDT transfer. Sidecars run in-runner after conditional completion or through one bounded non-foreground sidecar queue. | Test no per-episode sidecar foreground WorkManager chain is created. |
| Q7 | UI scope decision | `DownloadQueueStatusRepository` + UI | Global queue activity is visible outside the current-session downloads list. Notification actions target the active global row. | UI/state test or ViewModel test for active row outside current session. |
| Q8 | Existing preferences | `DownloadQueueScheduler` | Wi-Fi-only/mobile and storage-not-low constraints are preserved. Policy changes reschedule pending work. If an active transfer violates a new stricter policy, pause/requeue it; it may continue only when the current bound network/storage state already satisfies the new policy. | Policy-change tests. |
| Q9 | Android notification model | notification builder/status surface | Android 13+ notification permission and download channel state are checked before visible download batches. If required notifications are blocked, UIDT scheduling is deferred and the queue remains `QUEUED`/`PAUSED` with visible user guidance. | Notification permission/channel tests. |
| Q10 | User device boundary | validation workflow | Connected physical devices are never installed/updated without explicit user permission. | Final report lists skipped device checks; no connected test gate by default. |

## Compatibility Boundary

HarmonyOS 4.3 compatibility is mandatory.

The previous connected Huawei target is `MRDI-W09`, HarmonyOS 4.3, Android API 31. UIDT code must not execute on this device.

Hard rule:

```kotlin
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
    scheduleUidtQueueJob()
} else {
    scheduleWorkManagerQueueWorker()
}
```

Rules:

- Do not call API 34-only UIDT methods such as `JobInfo.Builder(...).setUserInitiated(true)` or `JobService.setNotification(...)` outside API guards. Use `JobParameters.getNetwork()` only inside the UIDT `JobService` path, never from API 31-33 fallback code.
- API 31-33 fallback may use foreground WorkManager, but startup/background reconciliation must not create a foreground-work restart loop.
- If API 31-33 foreground promotion is denied, mark the current row `PAUSED` and wait for an explicit visible resume/schedule path.

## Component Ownership

Suggested names may change, but these responsibilities must not merge into one unclear class.

### `DownloadQueueScheduler`

Platform selection facade.

Responsibilities:

- provide `scheduleQueue(trigger)`, `resumeQueue(trigger)`, `cancelQueue()`;
- own stable scheduler identities: one constant UIDT `jobId`, one constant WorkManager unique work name such as `media_download_queue`;
- own visible-app-state gating for API 34+ UIDT scheduling;
- construct UIDT `JobInfo` on API 34+ with:
  - `setUserInitiated(true)`;
  - required network using `NetworkRequest` or `setRequiredNetworkType(...)`;
  - `setRequiresStorageNotLow(true)`;
  - `setEstimatedNetworkBytes(...)` using known bytes or `JobInfo.NETWORK_BYTES_UNKNOWN`;
  - `setMinimumNetworkChunkBytes(...)` when the transfer has a meaningful non-resumable chunk, otherwise document why omitted;
- check `JobScheduler.schedule(...)` result and handle `RESULT_FAILURE`;
- catch `SecurityException` and setup exceptions without marking transfers `FAILED`;
- construct one WorkManager fallback queue worker on API 31-33 with equivalent network/storage constraints;
- never enqueue one media worker/job per media item.

Scheduling result contract:

- `scheduleQueue()` must not mark any row `DOWNLOADING`.
- Failed scheduling leaves rows `QUEUED` or `PAUSED` and surfaces/logs scheduler state.
- Retry scheduling only from visible/user-triggered or otherwise platform-allowed paths.
- Maintain scheduler liveness: if eligible `QUEUED`/`PAUSED` rows exist but API 34+ scheduling is deferred because the app is not visible, record or derive a pending queue state and retry scheduling from the next visible/user-triggered app entry point. A deferred queue must not require toggling an individual row through pause/resume to start.

### `AppVisibilityTracker`

Small process/UI foreground state owner used only for scheduling eligibility.

Responsibilities:

- expose whether the app is currently visible enough to schedule a UIDT job;
- prefer `ProcessLifecycleOwner` or an equivalent app-level lifecycle signal plus explicit user action triggers;
- distinguish `userInitiatedVisible`, `visibleButPassive`, and `backgroundOnly` triggers if the implementation needs different behavior;
- never let startup reconciliation, passive database observation, or background retry callbacks report a false visible state just to schedule UIDT;
- be injectable/testable so scheduler tests can prove visible=false defers scheduling.

### `SchedulerLivenessCoordinator`

This can be a small part of `DownloadQueueScheduler`, but it must be a named responsibility.

Responsibilities:

- observe app-visible/user-triggered entry points that are already allowed to schedule UIDT;
- check whether global eligible `QUEUED`/`PAUSED` rows exist when the app becomes visible;
- call `scheduleQueue(visibleTrigger)` once for the global queue when pending work exists and no scheduler instance is already active;
- never call `JobScheduler.schedule(...)` directly; all platform selection still goes through `DownloadQueueScheduler`;
- debounce duplicate visible events so resume/onStart/recomposition cannot create schedule storms;
- expose tests proving a visible=false deferred queue is eventually scheduled on the next visible event.

### `MediaDownloadQueueJobService`

API 34+ UIDT backend.

Responsibilities:

- be declared in `AndroidManifest.xml` with `android:permission="android.permission.BIND_JOB_SERVICE"` and `android:exported="false"`;
- use a defined DI approach, preferably `@AndroidEntryPoint`; if that is not viable, use a small Hilt entry point and document it;
- in `onStartJob(params)`:
  - immediately build and pass a notification to `setNotification(params, notificationId, notification, policy)`;
  - create a per-job coroutine/scope on IO dispatcher;
  - pass `params.getNetwork()` to the runner/network session;
  - return `true` when async work has been launched;
- update notification and UIDT byte estimates when total bytes/progress are known;
- call `jobFinished(params, wantsReschedule)` when the async runner finishes and `onStopJob` has not already stopped that job instance;
- in `onStopJob(params)`:
  - cancel the corresponding coroutine/runner;
  - persist active row as `PAUSED` with stop reason;
  - return `false` for `STOP_REASON_USER`/Task Manager user stop;
  - return `true` only for retryable system stops where platform reschedule is valid;
  - never rely on `onStopJob` alone, because Task Manager may kill the process before it runs;
- implement `onNetworkChanged(params)` for the UIDT path. On every network change, read the latest `params.getNetwork()` and pass it to the active runner/network session. If the new network is `null`, pause the active row. If it is non-null, cancel active Jellyfin SDK/raw stream I/O on the old network, rebind to the latest network, and resume from the last persisted byte. Continuing on a stale network or default process network is forbidden.

### `MediaDownloadQueueWorker`

API 31-33 WorkManager fallback.

Responsibilities:

- use one fixed unique work name for the queue;
- call the same `DownloadQueueRunner`;
- keep one foreground notification for the current transfer only;
- preserve Wi-Fi-only/mobile and storage-not-low constraints;
- preserve foreground-service denial -> `PAUSED`;
- stop without tight retry loops when foreground promotion is denied.

### `DownloadQueueRunner`

Sequential queue coordinator.

Responsibilities:

- reconcile stale `DOWNLOADING` rows to `PAUSED` on startup and runner start;
- atomically claim exactly one global oldest eligible `QUEUED` row only when no media row is globally `DOWNLOADING`;
- run the claimed row through `MediaDownloadTransferRunner`;
- loop until no eligible `QUEUED` rows remain or the runner is stopped;
- do not depend on an in-process `Mutex` for correctness;
- exit immediately if duplicate scheduler starts race and another active claim exists;
- conditionally finalize the active claim only if the row still exists and still matches the active state.

### `MediaDownloadTransferRunner`

Shared transfer engine extracted from `MediaDownloadWorker`.

Responsibilities:

- load the claimed `DownloadDto`;
- restore the Jellyfin API client by row-owned `serverId` and `userId`;
- fetch item details, build original/optimized download request, and write through `DownloadStorageManager`;
- support UIDT network binding for Jellyfin SDK API calls and raw media stream requests;
- update progress through conditional DAO methods;
- finish media file, then conditionally mark `COMPLETED`;
- create local source only after the conditional completion check passes;
- run image/subtitle/trickplay sidecars according to the sidecar policy;
- return structured `Completed`, `Paused`, or `Failed` results.

### `SessionRestoreResolver`

New or refactored row-owned session/client resolver.

Current risk: `SessionManager.getOrRestoreApiClient(serverId)` restores by `serverId` only, while the new global queue can claim rows from different users on the same server.

Required behavior:

- restore by `(serverId, userId)`;
- use the token matching both row fields;
- return an API/client pair whose access token, base URL, and user id belong to the claimed row;
- support UIDT network-bound clients when a required `Network` is provided;
- do not mutate `currentSession` merely to run a background transfer;
- if no token/client can be restored for that exact row owner, return a terminal auth/session failure. Scheduler/network absence is `PAUSED`; exact row-owned auth/session absence is `FAILED`.

### `UidtNetworkSession`

Network-bound client/session owner for API 34+ UIDT. Suggested internal helpers may include `UidtNetworkBinder`, but the runtime responsibility is a session, not a one-time factory.

Required behavior:

- if a UIDT required network is configured, require non-null `JobParameters.getNetwork()`;
- build or inject a network-bound OkHttp client using `network.socketFactory`;
- use DNS resolution through the same `Network`, for example `network.getAllByName(hostname)`;
- ensure Jellyfin SDK calls and media stream `OkHttpClient` calls use the network-bound client;
- do not silently use the default process network in the UIDT path;
- expose an update operation for `MediaDownloadQueueJobService.onNetworkChanged(params)` that swaps the bound `Network` for future requests;
- on network change, cancel any active Jellyfin API call or media stream read that is using the previous `Network`, rebind to the latest non-null `params.getNetwork()`, and resume from the last persisted byte. If the transfer mode cannot safely resume from that byte, pause the active row and require explicit resume/restart cleanup according to the pause policy. Finishing a read on the old network after `onNetworkChanged(params)` is forbidden;
- if `params.getNetwork()` becomes `null`, return a structured pause result and persist the active row as `PAUSED`;
- if Jellyfin SDK cannot be network-bound safely, add an adapter/fallback design before enabling UIDT.

### `DownloadQueueMigration`

Idempotent upgrade cleanup.

Responsibilities:

- run before the new queue can start after app upgrade;
- cancel old media work:
  - `cancelAllWorkByTag("download_active")`;
  - for each active/non-completed `DownloadDto`, `cancelUniqueWork("download_${download.id}")` and `cancelAllWorkByTag("download_${download.id}")`;
- convert stale legacy `DOWNLOADING` rows to `PAUSED`;
- leave `QUEUED` rows resumable and owned by the new queue;
- never delete `COMPLETED` rows or offline files;
- make persisted old workers harmless: if `MediaDownloadWorker` still starts during the upgrade window, it must not run a concurrent transfer. It should delegate to the new queue or mark the row `PAUSED` and exit.

### `DownloadQueueStatusRepository`

Global runtime status surface.

Responsibilities:

- expose active row id, item title, status, progress, queued count, `serverId`, and `userId`;
- support the current-session downloads screen remaining scoped;
- provide enough data for a top-level banner/status indicator and notification actions;
- keep pause/cancel actions targeting the active global row, not `currentSession`.

## Repository Behavior Contract

### `startDownload(itemId, sourceId)`

New behavior:

- reject offline mode as today unless product requirements change;
- insert or update one row as `QUEUED`;
- preserve row-owned `serverId` and `userId`;
- schedule the queue once;
- if scheduling fails, keep row `QUEUED` or `PAUSED`, surface scheduler state, and do not mark transfer `FAILED`;
- do not create per-download WorkManager chains.

### `startSeasonDownload(seasonId, seriesId)` and `startSeriesDownload(showId)`

New behavior:

- batch insert all eligible episode rows as `QUEUED`;
- preserve per-row `serverId` and `userId`;
- call `scheduleQueue()` once after the batch;
- do not call `startDownload(...)` in a way that schedules once per episode;
- return the number enqueued.

### `resumeDownload(downloadId)`

New behavior:

- allow `PAUSED` and `FAILED` rows to become `QUEUED`;
- schedule the single queue;
- if scheduling fails, keep the row resumable and surface scheduler state;
- do not use `ExistingWorkPolicy.REPLACE` on a per-download work chain.

### `pauseDownload(downloadId)`

New behavior:

- if row is `QUEUED`, mark `PAUSED`;
- if row is `DOWNLOADING`, request active runner cancellation and mark `PAUSED`;
- if optimized/transcoded mode is non-resumable, preserve or explicitly document target cleanup/restart policy;
- stale runner cannot later overwrite pause with `COMPLETED`, `FAILED`, progress, sidecar writes, or local source creation.

### `cancelDownload(downloadId)` / `deleteDownload(downloadId)`

New behavior:

- cancel active runner if the row is the active transfer;
- delete files/local source according to existing UI contract;
- cancel/delete wins over runner completion/failure;
- stale runner cannot reinsert row, create local source, or mark row completed;
- reschedule queue if other eligible `QUEUED` rows remain and scheduling is allowed.

## State Machine

Allowed transitions:

| From | To | Owner | Notes |
| --- | --- | --- | --- |
| none | `QUEUED` | repository start/enqueue path | User initiated single item, season, or series download. |
| `PAUSED` | `QUEUED` | explicit user resume | Scheduler failure may leave it `PAUSED`; resume schedules again. |
| `FAILED` | `QUEUED` | explicit user retry/resume | Keep current UI retry semantics or document any change. |
| `QUEUED` | `DOWNLOADING` | `DownloadQueueRunner` atomic claim only | No optimistic repository/scheduler write. |
| `DOWNLOADING` | `COMPLETED` | active runner conditional finalize only | Only if row still matches the active claim. |
| `DOWNLOADING` | `PAUSED` | user pause, system stop, process reconciliation, foreground denial, retryable interruption | Preserve resumable state where practical. |
| `DOWNLOADING` | `FAILED` | active runner conditional finalize only | Only terminal transfer errors. |
| `QUEUED` | `PAUSED` | user pause or scheduler/system policy | Work cannot start now but is not terminally failed. |
| any non-deleted | deleted/`CANCELLED` | user cancel/delete | Must win over active runner results. |

Global invariants:

- At most one media row is globally `DOWNLOADING`.
- Queue selection is global oldest eligible `QUEUED` first across all servers/users.
- `serverId` and `userId` are row ownership fields, not queue partitions.
- Every progress/final write is conditional.
- `createLocalSource(...)` and sidecar writes occur only after conditional completion still owns the row.
- Stale `DOWNLOADING` reconciliation is idempotent and safe on app startup, runner start, and process death recovery.

## Database Access Contract

Add focused DAO/repository methods if existing methods are insufficient:

- get globally oldest eligible queued media row;
- get active downloading rows;
- atomically claim the next queued row only when no media row is `DOWNLOADING`;
- conditionally update active progress;
- conditionally finalize claimed row as `COMPLETED`, `FAILED`, or `PAUSED`;
- conditionally pause/release active row;
- mark stale downloading rows as paused;
- count queued rows globally and scoped to current `serverId/userId`;
- expose global active queue status.

Atomic claim should be implemented with a Room transaction or equivalent database-level guard. Do not rely only on `Mutex`.

## Notification Contract

UIDT:

- call `JobService.setNotification(...)` immediately in `onStartJob`, before blocking network/file work;
- include item title, progress when known, and queued count where practical;
- include an app-owned graceful pause/stop action. Include cancel/delete only if it matches existing UX and storage safety; if omitted, document the reason and keep pause/stop available;
- notification actions target the active global row;
- on Android 13+/API 33, check `POST_NOTIFICATIONS` and `download_channel` state before scheduling a visible UIDT batch;
- if required notifications are disabled, do not schedule UIDT. Leave rows `QUEUED` or `PAUSED`, surface visible user guidance, and retry only after notification permission/channel state is acceptable from a visible/user-triggered path.

WorkManager fallback:

- use one foreground notification for the current transfer only;
- do not create queued-item notification storms.

Both:

- throttle notification updates;
- do not update on every 8 KB read.

## Error Semantics

| Condition | Status |
| --- | --- |
| `JobScheduler.schedule(...) == RESULT_FAILURE` before transfer starts | keep `QUEUED` or `PAUSED`; surface scheduler error |
| UIDT scheduling `SecurityException` before transfer starts | keep `QUEUED` or `PAUSED`; surface scheduler error |
| UIDT notification permission/channel blocks required notification before transfer starts | keep `QUEUED` or `PAUSED`; surface notification guidance |
| WorkManager enqueue failure before transfer starts | keep `QUEUED` or `PAUSED`; surface scheduler error |
| WorkManager foreground-service denial | `PAUSED` |
| UIDT required network missing from `JobParameters.getNetwork()` | `PAUSED`; no default-network fallback |
| UIDT retryable system stop | `PAUSED` |
| UIDT `STOP_REASON_USER` / Task Manager stop | `PAUSED`; no automatic app reschedule until explicit resume |
| Process killed while downloading | stale `DOWNLOADING` -> `PAUSED` on next startup/runner start |
| Network temporary disconnect / constraints no longer hold | `PAUSED` or queued retry; not terminal `FAILED` |
| Exact row-owned token/client cannot be restored | `FAILED` with auth/session error; do not silently pause or invent pending re-login semantics in this implementation |
| HTTP 401/403 after exact row-owned session restore | `FAILED` |
| HTTP 404 item missing | `FAILED` |
| Server returns non-success download response | `FAILED` |
| File write/storage fatal error | `FAILED` |
| User pause wins race | `PAUSED`; stale runner cannot overwrite |
| User cancel/delete wins race | deleted/`CANCELLED`; no row resurrection/local source |

## Implementation Phases

### Phase 0: Contract and test scaffolding

- Keep this artifact as the source of truth.
- Add focused tests/fakes for scheduler, DAO claim, runner state transitions, row-owned session restore, and network binding before large behavior changes where practical.
- Keep `MediaDownloadWorker` foreground-denial -> `PAUSED` mitigation until the new queue replaces legacy chains.

### Phase 1: Transfer runner extraction

- Extract media transfer logic from `MediaDownloadWorker` into `MediaDownloadTransferRunner`.
- Preserve current behavior behind the existing worker during extraction.
- Make final/progress/local-source writes conditional.
- Introduce structured runner results.
- Compile after extraction.

### Phase 2: DAO claim and WorkManager queue backend

- Add database claim/finalize/reconcile methods.
- Add `DownloadQueueRunner`.
- Add one fixed-name `MediaDownloadQueueWorker`.
- Replace per-download media scheduling with one queue worker on API 31-33.
- Add `DownloadQueueMigration`.
- Move or bound sidecar work.
- Prove season/series enqueue many rows but only one queue work and one active transfer.

This phase alone should improve HarmonyOS 4.3 because API 31 remains WorkManager but no longer runs a worker storm.

### Phase 3: Row-owned session and network binding

- Add `SessionRestoreResolver(serverId, userId, network?)`.
- Fix any server-only token lookup used by background downloads.
- Add `UidtNetworkSession` with network-bound client creation and network-change update support.
- Prove Jellyfin SDK calls and raw media stream calls use the network-bound client on UIDT.

### Phase 4: UIDT backend

- Add manifest `RUN_USER_INITIATED_JOBS`.
- Add `MediaDownloadQueueJobService`.
- Add `AppVisibilityTracker` and API 34 scheduler path with visible-app-state gate.
- Add `SchedulerLivenessCoordinator` or equivalent named scheduler-liveness responsibility so deferred visible=false UIDT schedules are retried when the app becomes visible again.
- Build UIDT job with `setUserInitiated(true)`, required network, storage-not-low, and payload estimates.
- Implement `JobService` lifecycle exactly as defined above.
- Handle schedule failure/security exceptions without terminal transfer failure.
- Ensure API 31 never executes UIDT methods.

### Phase 5: UI/status and policy hardening

- Add app-wide global queue status source.
- Add/adjust global status UI or another visible runtime surface.
- Ensure notification pause/cancel targets active global row.
- Define and test Wi-Fi/storage policy behavior: pending work is rescheduled, active work that violates a stricter new policy is paused/requeued, and active work continues only if the current bound network/storage state already satisfies the new policy.
- Add notification permission/channel guard that defers UIDT scheduling when required notifications are blocked.

### Phase 6: Device validation, only when explicitly allowed

- Samsung Android 16/API 36: verify UIDT path.
- Huawei HarmonyOS 4.3/API 31: verify WorkManager fallback path.
- Do not run this phase on connected physical devices unless the user explicitly allows install/update/device tests.

## Validation Gates

Mandatory local gates:

```bash
ANDROID_HOME=/root/android-sdk sh ./gradlew :app:compileDebugKotlin
ANDROID_HOME=/root/android-sdk sh ./gradlew :app:testDebugUnitTest
git diff --check
```

`connectedDebugAndroidTest` is not a default local gate while physical tablets are attached, because it can install a test APK. Run it only on an emulator or after explicit user permission to install/update a connected device.

Focused tests must cover:

- API guard: API 31 path never constructs/calls UIDT APIs;
- visible-state tests prove background startup/reconciliation defers API 34 UIDT scheduling;
- UIDT scheduler construction includes `setUserInitiated(true)`, required network, storage-not-low, and payload estimates/unknown values;
- visible=false API 34 scheduling defers instead of calling `JobScheduler.schedule(...)`;
- deferred visible=false scheduling is retried on the next visible/user-triggered app entry point without requiring row pause/resume toggles;
- `JobScheduler.schedule(...) == RESULT_FAILURE` leaves rows `QUEUED`/`PAUSED`;
- UIDT scheduling `SecurityException` leaves rows `QUEUED`/`PAUSED`;
- notification permission/channel blocked defers UIDT scheduling and leaves rows `QUEUED`/`PAUSED`;
- `JobService.onStartJob` immediate notification, async launch, return `true`, and completion `jobFinished`;
- `onStopJob` cancellation, no `jobFinished` after stop, user-stop no auto-reschedule;
- `onNetworkChanged(params)` cancels stale-network I/O, rebinds non-null network changes, resumes from the last persisted byte, and pauses on changed-to-null network;
- required UIDT network missing or changed does not fall back to default network;
- network-bound OkHttp/Jellyfin client uses `Network.socketFactory` and `Network.getAllByName`;
- exact row-owned session restore for two users on the same server;
- atomic claim under duplicate scheduler/runner starts;
- global oldest-first ordering across multiple `serverId/userId`;
- stale `DOWNLOADING` reconciliation;
- pause/cancel/delete races against progress, completion, failure, sidecars, and local source creation;
- legacy WorkManager migration cancels old `download_active` and `download_${id}` chains;
- persisted legacy worker cannot transfer concurrently after migration;
- sidecar failures do not recreate old foreground chains or incorrectly fail completed media;
- Wi-Fi-only/storage policy changes reschedule pending work and pause/requeue active work that violates stricter new policy;
- global queue activity remains visible even when current-session downloads list is scoped.

Optional non-device gate:

```bash
ANDROID_HOME=/root/android-sdk sh ./gradlew :app:assembleDebug
```

Device checks, only when explicitly allowed:

Samsung Android 16/API 36:

```bash
/root/android-sdk/platform-tools/adb -s <samsung> shell getprop ro.build.version.sdk
/root/android-sdk/platform-tools/adb -s <samsung> shell dumpsys jobscheduler | rg -C 8 'com.makd.afinity|user'
/root/android-sdk/platform-tools/adb -s <samsung> shell dumpsys notification --noredact | rg -C 6 'com.makd.afinity|download_channel'
```

Expected:

- API level is 34+;
- one UIDT queue job is scheduled through `JobScheduler`;
- job is user-initiated and has required network/storage constraints;
- UIDT transfer uses row-owned `serverId/userId` and network-bound HTTP client;
- legacy per-download WorkManager records do not run alongside UIDT queue;
- failed UIDT scheduling does not mark queued media `FAILED`;
- Task Manager/user stop pauses and does not auto-reschedule;
- one active media transfer;
- no `SystemForegroundService` background-start denial;
- no mass `MediaDownloadWorker RUNNING` records.

Huawei HarmonyOS 4.3/API 31:

```bash
/root/android-sdk/platform-tools/adb -s <huawei> shell getprop ro.build.version.sdk
/root/android-sdk/platform-tools/adb -s <huawei> shell dumpsys jobscheduler | rg -C 8 'com.makd.afinity|androidx.work'
```

Expected:

- API level is 31;
- UIDT job is not scheduled;
- WorkManager fallback queue worker is used;
- one active media transfer;
- no legacy per-download worker storm;
- foreground denial settles to `PAUSED`;
- offline playback still works after completion.

## Acceptance Criteria

- `startSeasonDownload` and `startSeriesDownload` no longer create one WorkManager chain per episode.
- `MAX_CONCURRENT_DOWNLOADS` is removed or replaced by a real enforced single-download queue.
- Legacy per-download WorkManager records are cancelled/neutralized before the new queue starts.
- Persisted legacy workers cannot transfer concurrently after upgrade.
- API 34+ schedules one fixed-id UIDT queue job only while app is visible or from a platform-allowed user-initiated path.
- API 34+ deferred scheduling remains live: queued work deferred while not visible is scheduled from the next visible/user-triggered app entry point.
- API 34+ UIDT job uses `setUserInitiated(true)`, `RUN_USER_INITIATED_JOBS`, required network, storage-not-low, and payload estimate/unknown handling.
- API 34+ UIDT `JobService` follows lifecycle rules: immediate `setNotification`, async work, return `true`, stop cancellation, no `jobFinished` after `onStopJob`.
- API 34+ UIDT transfer uses a network-bound OkHttp/Jellyfin client based on `JobParameters.getNetwork()` and handles `onNetworkChanged(params)` without stale/default-network I/O.
- API 34+ schedule failure/security exception does not mark queued/resumable rows terminally failed.
- API 34+ Task Manager/user stop leaves active work `PAUSED` and does not automatically reschedule.
- API 31-33 schedules one fixed-name WorkManager fallback queue worker with equivalent constraints and no background restart loop after foreground denial.
- HarmonyOS 4.3/API 31 remains compatible and does not execute UIDT code.
- Queue claim is atomic and permits at most one global `DOWNLOADING` media row.
- Queue ordering is global oldest eligible `QUEUED` across all sessions.
- Transfer uses row-owned `serverId/userId`, with a test for two users on the same server.
- Global queue activity is visible through an app-wide status source outside the current-session downloads list.
- Wi-Fi/storage policy changes have tested reschedule behavior for pending work and pause/requeue behavior for active work that violates stricter new policy.
- Pause/cancel/delete races cannot be overwritten by stale runner writes.
- Sidecar work no longer uses the old per-download foreground WorkManager chain.
- Android 13+ notification permission and download channel state are validated before device acceptance.
- If Android 13+ notification permission/channel blocks the required UIDT notification, UIDT scheduling is deferred with visible user guidance instead of starting a job that cannot satisfy `setNotification(...)`.
- A batch of 100 episodes produces many `QUEUED` rows and at most one active transfer.
- Completed downloads still create local sources and play via existing offline playback path.
- Mandatory local gates pass.
- A fresh post-implementation audit finds 0 High and 0 Medium issues in this artifact's scope.

## Final Audit Checklist

Before final report, review the actual diff against this checklist:

- Platform: API 34+ UIDT only; API 31-33 WorkManager only; no API 34 calls outside guards.
- UIDT construction: `setUserInitiated(true)`, required network, storage-not-low, payload estimates/unknown, permission, manifest service.
- Visibility: `AppVisibilityTracker` is testable; background reconciliation cannot fake visible state and schedule UIDT.
- Liveness: visible=false deferred UIDT scheduling is retried from a legitimate visible/user-triggered entry point.
- JobService lifecycle: immediate notification, main-thread offload, async return semantics, stop cancellation, no post-stop `jobFinished`.
- Network: required network is used through network-bound OkHttp/Jellyfin client; `onNetworkChanged(params)` cancels stale-network I/O, rebinds, resumes from persisted byte, or pauses; no default-network fallback.
- Session: row-owned `serverId/userId` client restore; no `currentSession` transfer ownership; two-users-same-server test.
- Migration: old `download_active` and `download_${id}` work cancelled/neutralized.
- Queue: one active media transfer globally; atomic claim; oldest eligible global selection.
- Database: conditional progress/finalize; stale `DOWNLOADING` reconciliation; no optimistic `DOWNLOADING`.
- UI: global queue status visible even if downloads list is scoped.
- Notifications: permission/channel checked before UIDT scheduling; blocked required notifications defer UIDT and surface visible guidance; actions target active global row.
- Policy: Wi-Fi/storage constraints preserved; policy changes reschedule pending work and pause/requeue active work that violates stricter new policy.
- Races: pause/cancel/delete wins over stale progress/completion/failure/local source/sidecars.
- Sidecars: no old per-download foreground chain.
- Compatibility: HarmonyOS 4.3/API 31 build/runtime compatible.
- Tests: focused tests plus compile/unit gates; connected device checks only with explicit permission.

## Suggested Short Codex Goal Field

Implement the UIDT-backed single-thread media download queue in `/root/source/android/AFinity` according to `artifacts/uidt-single-thread-download-queue-implementation-2026-06-02.md`: API 34+ must use one valid user-initiated `JobScheduler` queue job, API 31-33 including HarmonyOS 4.3 must use one WorkManager fallback queue worker, and season/series downloads must enqueue many rows but run only one global media transfer. Stop only when mandatory local gates pass and a fresh audit finds 0 High/Medium findings in the artifact scope.

## Execution Prompt For Another Agent

You are working in `/root/source/android/AFinity`. Implement the contract in `artifacts/uidt-single-thread-download-queue-implementation-2026-06-02.md`.

Rules:

- Do not install or update connected tablets unless explicitly requested.
- Preserve unrelated dirty changes.
- Do not start with UIDT code. First implement the single queue, atomic DB claim, row-owned session restore, and WorkManager fallback.
- Keep HarmonyOS 4.3/API 31 compatible and ensure it never executes UIDT APIs.
- Do not use `currentSession` or server-only token restore for active transfer identity.
- If exact row-owned `(serverId, userId)` token/client restore fails, treat it as terminal auth/session `FAILED`.
- Keep visible=false UIDT scheduling live through `SchedulerLivenessCoordinator` or an equivalent named responsibility.
- Implement `UidtNetworkSession` so `onNetworkChanged(params)` cancels stale-network I/O, rebinds non-null networks, resumes from the last persisted byte, pauses on null networks, and never falls back to default process network.
- If required UIDT notifications are blocked, defer scheduling and leave rows `QUEUED`/`PAUSED`.
- Treat this artifact's Requirements Matrix as the implementation checklist.
- Add focused tests for each implemented contract cluster.
- After each batch, run the relevant focused tests and `git diff --check`.
- Before final report, run the mandatory local gates and do a fresh audit against the Final Audit Checklist.

Required verification:

```bash
ANDROID_HOME=/root/android-sdk sh ./gradlew :app:compileDebugKotlin
ANDROID_HOME=/root/android-sdk sh ./gradlew :app:testDebugUnitTest
git diff --check
```

Final report must include:

- files changed;
- which backend runs on API 34+ and API 31;
- how `setUserInitiated(true)`, required network, notification, and `JobService` lifecycle are implemented;
- how one active transfer is enforced;
- how legacy WorkManager jobs are cancelled/neutralized;
- how row-owned `serverId/userId` session restore works;
- how UIDT network binding works;
- how `SchedulerLivenessCoordinator` or equivalent keeps visible=false deferred UIDT scheduling live;
- how UIDT network changes are handled without stale/default-network I/O;
- how blocked notification permission/channel defers UIDT scheduling;
- how global queue visibility works;
- how Wi-Fi/storage policy changes are handled;
- how pause/cancel/delete races are guarded;
- validation commands and results;
- device checks skipped because physical install/update permission was not granted.
