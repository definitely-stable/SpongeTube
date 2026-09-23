# M1-B2 post-merge storage review — 2026-09-23

Status: **Implementation follow-up**
Scope: merged PR #56 / issue #49
Follow-up: PR #57

## Review target

Review the accepted M1-B2 implementation as the storage contract that M1-C/M1-E will consume. The review focuses on failure semantics rather than adding features:

- read-handle ownership and cancellation;
- runtime quarantine/recovery consistency;
- Room/SQLite durability claims;
- ENOSPC/EDQUOT/metadata-full classification;
- verification I/O;
- API 23+ behavior;
- preservation of the no-DB-per-read invariant.

## Confirmed findings

### F1 — read-handle cancellation handoff could lose resource ownership

**Scenario**

`openRead()` acquired the store read lease, entered `withContext(IO)`, opened a FileChannel and returned an `ExtentReadHandle`. A cancellation delivered at the dispatcher handoff could discard the successful result before the caller owned it.

**Impact**

The store's counter could be released by the outer finally path while the FileChannel had no reachable owner. This is a resource/lifetime defect even though the immutable extent itself remained correct.

**Correction**

The created handle is retained in outer ownership state. On every exceptional/cancellation path:

- if a handle exists, close it and let the handle release the read lease;
- otherwise release the lease directly;
- preserve the original failure and attach cleanup failures as suppressed exceptions.

### F2 — Room metadata disk-full escaped the storage failure taxonomy

**Scenario**

The extent file passed the durable rename barrier, then the Room metadata transaction failed because the database/storage was full.

**Impact**

The caller could receive a raw SQLite exception rather than the M1 storage-full outcome, even though file ENOSPC/EDQUOT was already normalized.

**Correction**

For the current `AndroidSQLiteDriver` implementation:

- `SQLiteFullException` maps to `NO_SPACE`;
- `SQLiteDiskIOException` maps to `IO`;
- constraint/domain exceptions are preserved;
- database open/build is inside the same mapping/cleanup boundary.

The immutable final file is intentionally not deleted after the durable barrier; recovery treats it as an orphan if metadata publication did not commit.

### F3 — runtime length quarantine blocked immediate repair

**Scenario**

A published final file became length-invalid after startup. `openRead()` quarantined the metadata row but left the expected-path final file in place.

**Impact**

A same-identity repair attempted before restart failed because the final immutable path was still occupied. Startup recovery would have deleted the file, so runtime and startup behavior diverged.

**Correction**

Read-admission quarantine now mirrors recovery:

1. quarantine metadata;
2. delete the file only when the row points to the canonical expected path;
3. preserve non-canonical paths rather than deleting an arbitrary location.

A same-identity repair can then proceed without a restart.

### F4 — directory creation lost errno

**Scenario**

A new root/shard directory could not be created because storage/quota was exhausted.

**Impact**

`File.mkdir()` returned only false, losing ENOSPC/EDQUOT and producing a generic store exception.

**Correction**

Android directory creation now uses `Os.mkdir()`; ENOSPC/EDQUOT flow through the same `NO_SPACE` classifier. EEXIST is accepted only when the path is actually a directory.

### F5 — verification I/O was not normalized

**Scenario**

Reading a sealed temp file for SHA-256 or validating a published file during recovery failed with an I/O error.

**Impact**

Raw `IOException` could escape a subsystem that otherwise exposes typed storage failures.

**Correction**

Commit verification and startup published-file verification map I/O through `ExtentStorageException(IO)`.

### F6 — durability predicate name overclaimed what was proven

**Scenario**

`isPowerLossHardened` could be read as a guarantee that the device cannot lose committed metadata after sudden power loss.

**Impact**

The name exceeded the evidence. SQLite synchronization depends on the VFS/filesystem/kernel/device honoring the durability primitive.

**Correction**

The predicate is now `meetsM1DurabilityPolicy`. The adopted policy remains:

- Room `JournalMode.TRUNCATE`;
- effective `synchronous=FULL`;
- effective journal/synchronous/busy-timeout values observed at open;
- fail closed on journal/synchronous mismatch.

This is configuration evidence, not a physical-storage guarantee.

### F7 — writer cleanup could mask the primary failure or retain ownership

**Scenario**

A producer/cancellation failure triggered `writeExtent()` cleanup, or commit failed while closing/sealing the temporary stream. Cleanup itself then failed.

**Impact**

A cleanup exception from the `finally` path could replace the producer/cancellation error. In `commitLocked()`, a close failure could also prevent `finishTerminal()`, retaining the writer reservation and blocking later close/retry.

**Correction**

- preserve the original producer/cancellation/commit failure;
- attach abort/delete/close cleanup failures as suppressed exceptions;
- always release writer ownership on terminal commit failure;
- mark the output closed in a `finally` around the durability-owned sync/close operation;
- classify close I/O through the storage taxonomy.

### F8 — ExtentSink bounds validation was integer-overflow-prone

**Scenario**

A caller supplied non-negative `offset` and `length` values whose integer sum overflowed.

**Impact**

The precondition `offset + length <= bytes.size` was not mathematically safe and could accept an invalid range before the underlying stream rejected it.

**Correction**

Use subtraction-based validation:

- `offset <= bytes.size`;
- `length <= bytes.size - offset`.

This matches the already hardened read-handle bounds logic.

## Findings deliberately not changed

- **No per-open SHA-256 rehash.** Startup recovery already verifies SHA-256. Rehashing every playback open would add O(extent bytes) latency without a measured need. Background/incremental integrity remains M6.
- **No switch to WAL.** M1 metadata is not on the steady-state byte read path; concurrency benefit is not currently a measured requirement.
- **No switch from FULL to EXTRA.** For TRUNCATE, current SQLite semantics do not provide the DELETE-mode directory-sync distinction that motivates EXTRA.
- **No packed extent backend.** The public opaque read handle preserves future backend freedom.
- **No automatic orphan adoption.** A failed metadata publish leaves a durable orphan for recovery, preserving the one-way publication barrier.

## Regression coverage added

Host:
- cancelled read admission releases store ownership;
- runtime length corruption is deleted/quarantined and immediately repairable;
- producer failure remains primary when abort cleanup fails;
- sync failure releases writer ownership and permits same-id retry;
- ExtentSink bounds validation rejects overflow-prone ranges;
- existing positional/no-DB-per-read tests remain unchanged.

Android:
- nested SQLite FULL -> `NO_SPACE`;
- SQLite disk I/O -> `IO`;
- SQLite constraint -> unchanged/unmapped;
- existing API 23/34/36 Room durability and reopen tests remain required.

## References

- AndroidX `AndroidSQLiteDriver` documentation/source: implemented by Android SDK SQLite APIs.
- Android framework `SQLiteFullException`: database/disk full.
- SQLite `PRAGMA synchronous` documentation.
- Room 3 connection configuration source for non-WAL `FULL` synchronization.

This review does not make physical-device performance or absolute power-loss guarantees.
