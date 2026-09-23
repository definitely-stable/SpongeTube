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
- active consumer IDs;
- effective priority;
- requested range;
- network/unique/duplicate/rejected byte totals;
- terminal outcome.

`fetch-events-v1` remains historical evidence and is not rewritten.

## Public API gate

Only the opaque FetchKey identity is left public at this stage. FetchBroker construction, requests, consumers, outcomes, executor and runtime evidence models remain module-internal until #50 provider-feasibility findings are reviewed. This deliberately avoids freezing the current deterministic F1 implementation detail that a broker work item publishes one ExtentSpec.

## Deterministic tests

The engine unit suite covers:

- concurrent consumers joining one owner;
- playback escalation of an in-flight reserve owner;
- one joined consumer cancelling without cancelling the remaining consumer;
- final-consumer cancellation;
- CANCELLING replacement-owner barrier;
- bounded sequential retry;
- duplicate-range byte accounting across retry;
- incompatible FetchKey/work identity rejection;
- rejected range bytes producing no publication.

## Deferred

- provider-specific descriptor refresh;
- YouTube/SABR/PO-token/client-profile behavior;
- production transport selection;
- provider-aware backoff/request policy;
- persistent unfinished-attempt continuation across process death;
- PlaybackBridge/Media3 remote-miss integration (#40);
- canonical origin-trace execution and final M1 acceptance publication (#42).
