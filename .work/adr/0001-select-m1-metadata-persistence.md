# ADR-0001: Select Room 3 for M1 metadata persistence

Status: **Accepted**
Date: **2026-09-22**

## Context

M1 needs crash-consistent metadata publication for immutable media extents.

The durable architecture is already fixed:

~~~text
media bytes
  -> immutable extent file in app-private storage

metadata
  -> transactional SQLite index/journal
~~~

Only the metadata implementation layer remains open.

As of 2026-09-22, the relevant stable AndroidX releases are:

- Room 3.0.3, released 2026-09-09;
- AndroidX SQLite 2.7.1, released 2026-09-09.

Room 3 is Kotlin/KSP-only, coroutine-first and backed by the SQLiteDriver APIs. SpongeTube is already Kotlin-first on AGP 9.4 built-in Kotlin, and M1 introduces asynchronous storage/fetch work.

## Decision drivers

- transaction correctness around the extent publish barrier;
- compile-time SQL/query checking;
- explicit schema and migration review;
- deterministic testability;
- minimal hand-written SQLite plumbing in a correctness-critical subsystem;
- no legacy SupportSQLite dependency surface;
- compatibility with the coroutine-based M1 direction;
- containment of KSP/codegen cost to the storage-owning module;
- ability to fall back to lower-level SQLiteDriver APIs if evidence requires it.

## Options considered

### Option A — Room 3.0.3 + AndroidSQLiteDriver 2.7.1

Benefits:
- compile-time query/schema validation;
- generated database plumbing instead of hand-written cursor/statement mapping;
- explicit migration/schema tooling;
- coroutine-native transaction APIs;
- Room 3 is already SQLiteDriver based;
- AndroidSQLiteDriver uses the platform Android SQLite implementation and adds no bundled native SQLite engine.

Costs/risks:
- KSP/code generation enters the build graph;
- Room 3 is a new major line and needs focused M1-B integration coverage;
- storage code must remain isolated so generated/database types do not leak into core contracts.

Primary references:
- https://developer.android.com/jetpack/androidx/releases/room3
- https://developer.android.com/jetpack/androidx/releases/sqlite
- https://developer.android.com/reference/kotlin/androidx/sqlite/driver/AndroidSQLiteDriver
- https://developer.android.com/build/releases/agp-9-0-0-release-notes

### Option B — AndroidX SQLite 2.7.1 directly

Benefits:
- no Room compiler/codegen;
- smallest abstraction surface;
- direct control of statements and transactions.

Costs/risks:
- SpongeTube would own schema validation, SQL mapping, migration plumbing and more low-level persistence code;
- larger hand-written correctness surface in the exact subsystem where M1 proves crash/recovery invariants;
- no compiler-checked DAO queries.

This remains the fallback if Room exposes a demonstrated blocker.

### Option C — Room 2.8.x

Rejected for a greenfield storage subsystem: it starts on the older package/API generation and preserves compatibility concepts M1 does not need.

### Option D — BundledSQLiteDriver

Deferred. A bundled engine improves SQLite-version determinism but adds native/APK complexity before M1 demonstrates a need.

## Decision

Use **Room 3.0.3** as the M1 metadata implementation and **AndroidSQLiteDriver from AndroidX SQLite 2.7.1** as the initial driver.

The database stores metadata/index/journal only. Media payload bytes remain immutable external files.

Room/KSP dependencies are introduced only when M1-B creates the real storage module/schema. M1-A records the decision but does not add unused runtime/codegen dependencies.

When Room is introduced:
- use SQLiteDriver/Room 3 APIs directly;
- do not use SupportSQLite compatibility wrappers;
- commit exported Room schema files;
- keep entities/DAOs/database types internal to storage;
- retain constructor wiring rather than adding DI solely for Room;
- validate minSdk/API compatibility in executable M1-B CI before treating it as proven.

## Consequences

Positive:
- smaller custom persistence correctness surface;
- compile-time SQL/schema checking;
- migration state becomes reviewable evidence;
- transaction APIs align with coroutine-based M1 work.

Negative:
- KSP becomes part of the M1-B build;
- build/codegen cost must be observed after adoption;
- Room 3 becomes an internal implementation dependency.

Operational/recovery implications:
- SQLite commit is the metadata publication point only after the external extent passed the durable file barrier;
- database rollback cannot turn a non-published file into coverage;
- startup recovery still validates file existence/length/SHA-256 independently of row existence.

## Verification

M1-B must falsify this decision if:
- Room/AndroidSQLiteDriver cannot satisfy the API 23 project baseline;
- transaction cancellation/restart behavior breaks the publication invariant;
- schema export/migration tests cannot be deterministic;
- KSP integration conflicts with AGP 9.4 built-in Kotlin;
- generated abstraction blocks required recovery queries/transactions.

If confirmed, switch the internal metadata implementation to AndroidX SQLite 2.7.x directly without changing ExtentStore/CoverageIndex contracts.

## Supersession

None.

## Canonical-doc impact

Updated: .work/milestones/M1.md.
No higher-level storage invariant changes are required.
