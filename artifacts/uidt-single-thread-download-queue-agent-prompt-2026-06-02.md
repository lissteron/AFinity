# Agent Prompt: Implement AFinity UIDT Single-Thread Download Queue

You are working in `/root/source/android/AFinity`.

Implement the contract in:

```text
artifacts/uidt-single-thread-download-queue-implementation-2026-06-02.md
```

Treat that artifact as the source of truth. Do not implement from memory or from this prompt alone. Start by reading the whole contract, then inspect the real owner files listed or implied by the contract.

## Goal

Replace AFinity's per-media WorkManager download storm with a single-thread media download queue:

- API 34+ must use one valid user-initiated data transfer `JobScheduler` job.
- API 31-33, including HarmonyOS 4.3/API 31, must use one WorkManager foreground fallback queue worker.
- Season/series downloads must enqueue many rows but run at most one global media transfer at a time.
- Scheduler/system startup failures must leave downloads `QUEUED` or `PAUSED`, not terminal `FAILED`.
- Real terminal transfer failures remain `FAILED`.

## Hard Rules

- Do not install, update, or run connected-device APK tests on physical tablets unless the user explicitly grants permission in this turn.
- Preserve unrelated dirty changes.
- Do not start with UIDT code. Implement the single queue, atomic DB claim, row-owned session restore, and WorkManager fallback first.
- Keep HarmonyOS 4.3/API 31 compatible; API 31 must never execute UIDT APIs.
- Do not use `sessionManager.currentSession` as transfer ownership for active queue rows.
- Do not use server-only token/client restore for downloads. Restore by row-owned `(serverId, userId)`.
- If exact row-owned `(serverId, userId)` token/client restore fails, treat it as terminal auth/session `FAILED`; do not silently pause it.
- Do not rely on an in-process `Mutex` for the one-active-transfer invariant.
- Do not recreate per-download media WorkManager chains or per-episode foreground sidecar chains.
- Persisted legacy workers must be cancelled/neutralized and must not transfer concurrently during upgrade.
- API 34 visible=false scheduling deferral must remain live: the queue must schedule from the next legitimate visible/user-triggered app entry point without requiring row pause/resume toggles.

## Required Implementation Shape

Implement or refactor the ownership areas defined in the contract:

- `DownloadQueueScheduler`
- `AppVisibilityTracker`
- `MediaDownloadQueueJobService`
- `MediaDownloadQueueWorker`
- `DownloadQueueRunner`
- `MediaDownloadTransferRunner`
- `SessionRestoreResolver`
- `UidtNetworkSession`
- `DownloadQueueMigration`
- `DownloadQueueStatusRepository`

Names may change, but responsibilities must remain separate and reviewable.

## UIDT Must-Haves

On API 34+ UIDT path:

- declare `android.permission.RUN_USER_INITIATED_JOBS`;
- declare `MediaDownloadQueueJobService` with `android.permission.BIND_JOB_SERVICE` and `android:exported="false"`;
- build the job with `setUserInitiated(true)`;
- set required network and storage-not-low constraints;
- provide known or unknown payload byte estimates;
- schedule only from visible/user-initiated foreground paths through a testable visibility gate;
- call `JobService.setNotification(...)` immediately in `onStartJob`, before transfer work;
- offload transfer work from the main thread;
- return `true` from `onStartJob` when async work starts;
- call `jobFinished(...)` only if the job has not already been stopped;
- cancel runner work in `onStopJob`;
- do not app-reschedule after user/Task Manager stop;
- implement `onNetworkChanged(params)`;
- use `JobParameters.getNetwork()` through a network-bound OkHttp/Jellyfin client for all UIDT Jellyfin API and media stream calls;
- cancel stale-network I/O on network changes, rebind non-null network changes, resume from the last persisted byte, and pause when the required network becomes `null`; never finish reads on stale network and never fall back to the default process network;
- if Android 13+ notification permission or the download channel blocks the required UIDT notification, defer UIDT scheduling, leave rows `QUEUED`/`PAUSED`, and surface visible user guidance.

## Required Tests

Add focused tests for the contract clusters before or alongside implementation:

- API 31 path never constructs/calls UIDT APIs;
- API 34 scheduler includes `setUserInitiated(true)`, required network, storage-not-low, and byte estimates/unknown values;
- visible=false API 34 scheduling defers instead of calling `JobScheduler.schedule(...)`;
- visible=false deferred scheduling is retried on the next legitimate visible/user-triggered app entry point;
- scheduler failures/security exceptions leave rows `QUEUED` or `PAUSED`;
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
- notification permission/channel blocked defers UIDT scheduling and leaves rows `QUEUED`/`PAUSED`;
- Wi-Fi/storage policy changes reschedule pending work and pause/requeue active work that violates stricter new policy.

## Validation

Mandatory local gates:

```bash
ANDROID_HOME=/root/android-sdk sh ./gradlew :app:compileDebugKotlin
ANDROID_HOME=/root/android-sdk sh ./gradlew :app:testDebugUnitTest
git diff --check
```

Do not run `connectedDebugAndroidTest` on attached physical tablets unless the user explicitly permits device install/update. It may be run only on an emulator or with explicit permission.

## Stop Condition

Stop only when:

- mandatory local gates pass;
- the implementation satisfies every row of the Requirements Matrix in the contract;
- a fresh audit against the contract's Final Audit Checklist finds 0 High and 0 Medium issues.

## Final Report

Report:

- files changed;
- which backend runs on API 34+ and API 31;
- how `setUserInitiated(true)`, required network, notification, and `JobService` lifecycle are implemented;
- how one active transfer is enforced;
- how legacy WorkManager jobs are cancelled/neutralized;
- how row-owned `serverId/userId` session restore works;
- how UIDT network binding works;
- how UIDT network changes are handled without stale/default-network I/O;
- how blocked notification permission/channel defers UIDT scheduling;
- how global queue visibility works;
- how Wi-Fi/storage policy changes are handled;
- how pause/cancel/delete races are guarded;
- validation commands and results;
- device checks skipped because physical install/update permission was not granted.
