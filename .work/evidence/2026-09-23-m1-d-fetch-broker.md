# M1-D FetchBroker implementation — 2026-09-23

Status: implementation in PR #59
Issue: #39

## Scope

This slice implements the provider-independent single-flight ownership kernel required before PlaybackBridge. It does not freeze a production provider/transport API while #50 remains open.

## Implemented invariants

- one active SharedFetch owner pipeline per FetchKey;
- fetchId identifies one SharedFetch lifetime;
- bounded physical attempts are sequential children of one fetchId and receive stable attemptCorrelationId values;
- consumer demand is represented by independent reserve/playback leases;
- cancelling one lease cannot cancel work still required by another lease;
- final-consumer release moves the owner to CANCELLING and keeps the registry entry until the physical owner is terminal;
- a replacement acquire for the same FetchKey waits across CANCELLING rather than creating an overlapping owner;
- late demand does not reset the previous owner's bounded request budget: CANCELLED_NO_CONSUMERS permits a fresh owner, while late success or another terminal result is handed to the waiter;
- priority is monotonic RESERVE -> PLAYBACK and escalation never restarts the owner;
- same active FetchKey with incompatible immutable ExtentSpec fails closed;
- failed attempts publish nothing; publication is delegated to the existing ExtentStore write/abort/commit barrier;
- M1 retries restart with a fresh writer rather than inventing persistent partial continuation identity;
- network, accepted unique range, accepted duplicate range and rejected/unmapped bytes are distinct counters;
- storage/integrity/range/cancellation/transport outcomes remain provider-independent.

## Evidence contract

New M1-D runs use `fetch-events-v2`. The event contract separates owner lifetime from physical attempts and carries:

- fetchId;
- attempt number;
- attemptCorrelationId;
- opaque transportCorrelationId for exact broker-to-origin joining;
- active consumer IDs;
- effective priority;
- requested range;
- network/unique/duplicate/rejected byte totals;
- terminal outcome;
- Android event time in the `SystemClock.elapsedRealtimeNanos()` domain, never subtracted from host Media Lab monotonic time.

`fetch-events-v1` remains historical evidence and is not rewritten.

## Public API gate

Only the opaque FetchKey identity is left public at this stage. FetchBroker construction, requests, consumers, outcomes, executor and runtime evidence models remain module-internal until #50 provider-feasibility findings are reviewed. This deliberately avoids freezing the current deterministic F1 implementation detail that a broker work item publishes one ExtentSpec.

## Deterministic tests

The engine unit suite covers:

- concurrent consumers joining one owner;
- playback escalation of an in-flight reserve owner;
- one joined consumer cancelling without cancelling the remaining consumer;
- final-consumer cancellation;
- cancellation of a coroutine blocked in `FetchHandle.await()`;
- CANCELLING replacement-owner barrier;
- late success handoff without refetch;
- late terminal failure handoff without resetting the attempt budget;
- bounded sequential retry and retry-budget exhaustion;
- duplicate-range byte accounting across retry;
- incompatible FetchKey/work identity rejection;
- rejected range bytes producing no publication;
- fatal `Error` registry cleanup followed by rethrow rather than swallowing.

## Executed origin proof

Android Smoke runs a real Media Lab HTTP Range request through the test-only transport seam, real FetchBroker ownership, real ExtentStore publication and `fetch-events-v2`. The independent host verifier first validates every runtime event against the checked-in schema, then requires one fetchId, one physical origin request, exact transportCorrelationId/requestId equality, playback escalation and zero duplicate bytes.

## Deferred

- provider-specific descriptor refresh;
- YouTube/SABR/PO-token/client-profile behavior;
- production transport selection;
- provider-aware backoff/request policy;
- persistent unfinished-attempt continuation across process death;
- PlaybackBridge/Media3 remote-miss integration (#40);
- final cross-slice M1 acceptance publication (#42); the M1-D-specific origin proof is already executable in Android Smoke.
