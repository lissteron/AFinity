# Codex Goal And Execution Prompt: AFinity UIDT Single-Thread Download Queue

## Goal Field

Implement the UIDT-backed single-thread media download queue in `/root/source/android/AFinity` according to `artifacts/uidt-single-thread-download-queue-implementation-2026-06-02.md`: API 34+ must use one valid user-initiated `JobScheduler` queue job, API 31-33 including HarmonyOS 4.3/API 31 must use one WorkManager fallback queue worker, and season/series downloads must enqueue many rows but run only one global media transfer. Stop only when mandatory local gates pass and a fresh audit against the contract finds 0 High and 0 Medium findings.

## Execution Prompt

You are working in `/root/source/android/AFinity`.

Implement the contract in:

```text
artifacts/uidt-single-thread-download-queue-implementation-2026-06-02.md
```

Treat that contract as the source of truth. Do not implement from memory or from this prompt alone. Start by reading the full contract, then inspect the real owner files listed or implied by it.

### Non-Negotiable Rules

- Do not install, update, or run connected-device APK tests on physical tablets unless the user explicitly grants permission in this turn.
- Preserve unrelated dirty changes. Do not revert files you did not intentionally change.
- Do not start with UIDT. First implement the single queue, atomic DB claim/finalize/reconcile, row-owned session restore, and WorkManager fallback.
- Keep HarmonyOS 4.3/API 31 compatible. API 31 must never execute UIDT APIs.
- Do not use `sessionManager.currentSession` or server-only token/client restore for active transfer ownership.
- If exact row-owned `(serverId, userId)` token/client restore fails, treat it as terminal auth/session `FAILED`; do not silently pause it.
- Do not rely on an in-process `Mutex` for the one-active-transfer invariant.
- Do not recreate per-download media WorkManager chains or per-episode foreground sidecar chains.
- Persisted legacy workers must be cancelled/neutralized and must not transfer concurrently during upgrade.
- Scheduler/system startup failures must leave rows `QUEUED` or `PAUSED`, not terminal `FAILED`.
- Real terminal transfer failures remain `FAILED`.

### Implementation Order

1. Read the contract and current code paths:
   - `app/src/main/java/com/makd/afinity/data/repository/download/JellyfinDownloadRepository.kt`
   - `app/src/main/java/com/makd/afinity/data/workers/MediaDownloadWorker.kt`
   - existing download DAO/database/repository code
   - current session/auth/token restore code
   - current image/subtitle/trickplay sidecar workers
2. Extract `MediaDownloadTransferRunner` from `MediaDownloadWorker` while preserving current transfer behavior.
3. Add database methods for global oldest eligible claim, conditional progress/finalize/pause, stale `DOWNLOADING` reconciliation, and global queue status.
4. Add `DownloadQueueRunner` and prove at most one global media row can be `DOWNLOADING`.
5. Add the API 31-33 `MediaDownloadQueueWorker` fallback with one fixed unique work name and equivalent Wi-Fi/mobile and storage-not-low constraints.
6. Replace per-media scheduling in single item, season, and series flows with batch enqueue plus one queue schedule.
7. Add `DownloadQueueMigration` to cancel/neutralize old `download_active` and `download_${id}` WorkManager chains and make persisted old workers harmless.
8. Add `SessionRestoreResolver(serverId, userId, network?)` and remove server-only/current-session transfer ownership from background downloads.
9. Add `UidtNetworkSession` for API 34+ network-bound Jellyfin SDK and raw media stream calls.
10. Add the API 34+ UIDT backend only after the queue/fallback path is correct.
11. Add global queue status UI/state surface and notification actions targeting the active global row.
12. Add policy and notification hardening.

### UIDT Requirements

On API 34+:

- declare `android.permission.RUN_USER_INITIATED_JOBS`;
- declare `MediaDownloadQueueJobService` with `android.permission.BIND_JOB_SERVICE` and `android:exported="false"`;
- build one fixed-id queue job with `setUserInitiated(true)`;
- set required network, storage-not-low, and known/unknown payload byte estimates;
- schedule only from visible/user-initiated foreground paths through a testable visibility gate;
- keep visible=false deferrals live through `SchedulerLivenessCoordinator` or an equivalent named responsibility, so queued work starts from the next legitimate visible/user-triggered app entry point without row pause/resume toggles;
- call `JobService.setNotification(...)` immediately in `onStartJob`, before transfer work;
- offload transfer work from the main thread;
- return `true` from `onStartJob` when async work starts;
- call `jobFinished(...)` only if the job has not already been stopped;
- cancel runner work in `onStopJob`;
- do not app-reschedule after user/Task Manager stop;
- implement `onNetworkChanged(params)`;
- use `JobParameters.getNetwork()` through a network-bound OkHttp/Jellyfin client for all UIDT Jellyfin API and media stream calls;
- cancel stale-network I/O on network changes, rebind non-null network changes, resume from the last persisted byte, and pause when the required network becomes `null`;
- never finish reads on a stale network and never fall back to the default process network;
- if Android 13+ notification permission or the download channel blocks the required UIDT notification, defer UIDT scheduling, leave rows `QUEUED`/`PAUSED`, and surface visible user guidance.

### Required Tests

Add focused tests before or alongside implementation for:

- API 31 path never constructs/calls UIDT APIs;
- API 34 scheduler includes `setUserInitiated(true)`, required network, storage-not-low, and byte estimates/unknown values;
- visible=false API 34 scheduling defers instead of calling `JobScheduler.schedule(...)`;
- visible=false deferred scheduling is retried on the next legitimate visible/user-triggered app entry point;
- scheduler failures/security exceptions leave rows `QUEUED` or `PAUSED`;
- notification permission/channel blocked defers UIDT scheduling and leaves rows `QUEUED`/`PAUSED`;
- `JobService` lifecycle: immediate notification, async launch, return `true`, no `jobFinished` after stop;
- `onNetworkChanged(params)` cancels stale-network I/O, rebinds non-null network changes, resumes from persisted byte, and pauses on changed-to-null network;
- required UIDT network missing/changed does not fall back to default network;
- network-bound client uses `Network.socketFactory` and DNS through `Network.getAllByName`;
- exact row-owned session restore for two users on the same server;
- atomic global claim under duplicate runner/scheduler starts;
- global oldest-first queue ordering across multiple `serverId/userId`;
- stale `DOWNLOADING` reconciliation;
- pause/cancel/delete races against progress, completion, failure, sidecars, and local source creation;
- legacy WorkManager migration cancels old `download_active` and `download_${id}` work;
- persisted legacy workers cannot transfer concurrently after migration;
- sidecar policy no longer creates old per-download foreground chains;
- global queue visibility outside the current-session downloads list;
- Wi-Fi/storage policy changes reschedule pending work and pause/requeue active work that violates stricter new policy.

### Mandatory Validation

Run:

```bash
ANDROID_HOME=/root/android-sdk sh ./gradlew :app:compileDebugKotlin
ANDROID_HOME=/root/android-sdk sh ./gradlew :app:testDebugUnitTest
git diff --check
```

Do not run `connectedDebugAndroidTest` on attached physical tablets unless the user explicitly permits device install/update. It may be run only on an emulator or with explicit permission.

### Stop Condition

Do not stop at partial implementation. Stop only when:

- mandatory local gates pass;
- every row of the contract Requirements Matrix is satisfied;
- season/series downloads enqueue many rows but create only one queue backend and one active media transfer;
- API 34+ uses one valid UIDT `JobScheduler` queue job;
- API 31-33 including HarmonyOS 4.3/API 31 uses one WorkManager fallback queue worker and never executes UIDT APIs;
- a fresh audit against the contract Final Audit Checklist finds 0 High and 0 Medium issues.

### Final Report

Report:

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
