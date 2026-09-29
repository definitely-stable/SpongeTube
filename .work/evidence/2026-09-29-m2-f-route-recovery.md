# M2-F canonical route/recovery retained evidence

Date: 2026-09-29  
Milestone: M2 — Network & Provider Resilience  
Slice: M2-F — Android Route/VPN Recovery Integration  
Closure commit: `4989547227ac9fe74e3bcc7fd46b61eeb95ffae4`

This record closes the retained-evidence bookkeeping for M2-F after F4 merged to `main`.
It does not introduce new runtime claims. It binds the already-passing API 36 F2/F3
canonical runs to immutable GitHub Actions artifact archives and records an
independent retained-bundle re-verification.

## Retained workflow artifacts

### F2 — actual default-route loss / restore

- Workflow run: `36546265321`
- Artifact id: `11022926697`
- Artifact name: `m2-f2-route-recovery`
- ZIP SHA-256: `105479d473b1b283987cec677dc74aa00162dc304a3d4a94a07c1223a97d14b5`
- Run manifest git commit: `4989547227ac9fe74e3bcc7fd46b61eeb95ffae4`
- Scenario: `N6 / DEFAULT_ROUTE_LOSS_RESTORE`
- Scenario SHA-256: `05e0359cb33d864600e98547776b98b5a436f372c99ab325e7f0797c80d42c5a`
- Semantic replay SHA-256: `a152627697e53500d3afbb394c2bbfad4008e89e683939b54c4163d3dd94ef6f`
- Route events: 13
- Physical attempts: 2
- M2-ACC-04: PASS
- M2-ACC-05: PASS
- M2-ACC-06: PASS

Observed normalized runtime sequence:

```text
SESSION:DIRECT_DEFAULT_ALLOWED
PERMIT:E1:ROUTE_READY
OWNER:1
FAIL:E1:TRANSIENT_TRANSPORT:SCHEDULE_BACKOFF
PAUSE:NONE:NO_USABLE_DEFAULT
PERMIT:E2:ROUTE_READY
OWNER:2
TERMINAL:SUCCESS
```

### F3 — VPN continuity restore

- Workflow run: `36546265498`
- Artifact id: `11022732451`
- Artifact name: `m2-f3-vpn-continuity`
- ZIP SHA-256: `c7d51f1c30b209af9352ea87fd4f87142679646234654304e6ae0268703629db`
- Run manifest git commit: `4989547227ac9fe74e3bcc7fd46b61eeb95ffae4`
- Scenario: `N7 / VPN_CONTINUITY_RESTORE`
- Scenario SHA-256: `34c90d6d66a6401d7fb28180668a17e94b0829ac9383f5e0599db2af08982adc`
- Semantic replay SHA-256: `5ea340f0dd689b8f127e0971d8e2df50102feec38719835d887ece6bf488fcbb`
- Route events: 27
- Physical attempts: 2
- M2-ACC-03: PASS
- M2-ACC-04: PASS
- M2-ACC-05: PASS
- M2-ACC-06: PASS

Observed normalized runtime sequence:

```text
SESSION:VPN_CONTINUITY_REQUIRED
PERMIT:E1:ROUTE_READY
OWNER:1
FAIL:E1:TRANSIENT_TRANSPORT:SCHEDULE_BACKOFF
PAUSE:E2:VPN_CONTINUITY_REQUIRED
PERMIT:E3:ROUTE_READY
OWNER:2
TERMINAL:SUCCESS
```

### F3 — explicit direct override

The direct-override case is retained inside the same F3 workflow artifact above.

- Scenario: `N7 / VPN_CONTINUITY_DIRECT_OVERRIDE`
- Scenario SHA-256: `6a827ed39763162f5b29b0c41785176319476099aacf741b112b1406185f0163`
- Semantic replay SHA-256: `b8781bb89434d454f9c8639b3e5fd624107bacc2b2126082ea5a4729bfd67bf4`
- Route events: 17
- Physical attempts: 2
- M2-ACC-03: PASS
- M2-ACC-04: PASS
- M2-ACC-05: PASS
- M2-ACC-06: PASS
- Direct pause route-event watermark: 16
- Override resume route-event watermark: 16

Observed normalized runtime sequence:

```text
SESSION:VPN_CONTINUITY_REQUIRED
PERMIT:E1:ROUTE_READY
OWNER:1
FAIL:E1:TRANSIENT_TRANSPORT:SCHEDULE_BACKOFF
PAUSE:E2:VPN_CONTINUITY_REQUIRED
PERMIT:E2:EXPLICIT_DIRECT_OVERRIDE
OWNER:2
TERMINAL:SUCCESS
```

The equal route-event watermark proves that explicit direct override woke the
already-paused gate without requiring an unrelated Android route callback.

## Independent retained-bundle verification

After both merged-main artifacts were downloaded, the retained ZIP archives were
unpacked and re-verified independently of the workflow working directory.

The closure verification checked:

- every artifact listed by each `evidence-index.json` against its recorded SHA-256;
- `run-manifest.json.gitCommit` equals the merged M2-F closure commit;
- scenario canonical JSON SHA-256 equals the run-manifest and F4 summary scenario hash;
- runId/sessionId/recoveryChainId joins across route, recovery, failure and fetch evidence;
- complete pre-existing committed extent identity is unchanged after route transitions;
- F2 NO_USABLE_DEFAULT pause occurs before the replacement-route permit;
- F3 direct replacement is not silently allowed after a VPN-protected session;
- VPN restore resumes only on a later VPN route epoch;
- explicit direct override resumes on the already-current direct epoch without another route event;
- exactly two physical owners and one retained RecoveryChain per canonical case;
- retained Media Lab origin trace contains exactly one successful target GET per case;
- semantic replay digests recomputed from runtime route/recovery evidence equal the retained F4 summaries.

All checks passed.

## Scope boundary

M2-F establishes route identity, route gating, VPN continuity/privacy, bounded
recovery ownership, persisted-media invariance and canonical retained evidence
on the API 36 emulator.

It does **not** select or rank production transport strategies, establish
representative physical-device performance, or make a transport-backend product
decision. Those questions belong to M2-G Transport Evidence Evaluation.
