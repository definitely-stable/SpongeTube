package io.github.definitelystable.spongetube.core.engine.route

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Deterministic `route-events-v1` producer for the host oracle
 * (`scripts/measurement/m2_route_oracle.py`, run by
 * `scripts/ci/verify-m2-b-route-evidence.sh`).
 *
 * Each case replays a scripted platform signal sequence through the
 * production reducer, session guard and recorder for one API level. The
 * oracle re-derives every state and decision independently; this test only
 * produces the artifacts and checks a few anchors.
 */
class RouteEvidenceHostTest {
    @Test
    fun canonicalRouteCasesProduceEvidence() {
        File(EVIDENCE_ROOT).deleteRecursively()
        vpnSessionReplacedByDirectRoute()
        directSessionWithTransientVpn()
        api23LegacySnapshots()
        api28StartupRaceAndSuspension()
        api24UnorderedCallbacks()
    }

    @Test
    fun recorderRefusesToRenderOverflowedEvidence() {
        val recorder = RouteEvidenceRecorder("run", "session", 34, capacity = 2)
        val reducer = DefaultRouteReducer(recorder)
        reducer.start(1)
        reducer.reduce(RouteSignal.Available(A), 2)
        reducer.reduce(RouteSignal.Lost(A), 3)

        assertThrows<IllegalStateException> { recorder.toArtifactMap() }
    }

    /** API 34: session starts on VPN; the default route becomes direct. */
    private fun vpnSessionReplacedByDirectRoute() = case("api34-vpn-continuity", api = 34) {
        policy()
        signal(RouteSignal.BootstrapSnapshot(A))
        signal(RouteSignal.Available(A))
        signal(RouteSignal.CapabilitiesChanged(A, VPN))
        signal(RouteSignal.LinkPropertiesChanged(A))
        signal(RouteSignal.BlockedStatusChanged(A, blocked = false))
        policy(expect = ExternalFetchRouteReason.ROUTE_READY)
        signal(RouteSignal.Available(B))
        policy(expect = ExternalFetchRouteReason.CAPABILITIES_PENDING)
        signal(RouteSignal.CapabilitiesChanged(A, VPN))
        signal(RouteSignal.CapabilitiesChanged(B, DIRECT))
        signal(RouteSignal.BlockedStatusChanged(B, blocked = false))
        policy(expect = ExternalFetchRouteReason.VPN_CONTINUITY_REQUIRED)
        signal(RouteSignal.Lost(A))
        guard.explicitDirectOverride = true
        policy(expect = ExternalFetchRouteReason.EXPLICIT_DIRECT_OVERRIDE)
        signal(RouteSignal.BlockedStatusChanged(B, blocked = true))
        policy(expect = ExternalFetchRouteReason.NETWORK_BLOCKED)
        signal(RouteSignal.Lost(B))
        policy(expect = ExternalFetchRouteReason.NO_USABLE_DEFAULT)
        guard.explicitDirectOverride = false
        signal(RouteSignal.Available(C))
        signal(RouteSignal.CapabilitiesChanged(C, VPN))
        signal(RouteSignal.BlockedStatusChanged(C, blocked = false))
        policy(expect = ExternalFetchRouteReason.ROUTE_READY)
        assertEquals(SessionRouteGuardState.VPN_CONTINUITY_REQUIRED, guard.state)
        assertEquals(3L, (reducer.current.state as DefaultRouteState.Available).routeEpoch)
    }

    /** API 36-shaped: direct start, VPN appears and disappears. */
    private fun directSessionWithTransientVpn() = case("api36-direct-transient-vpn", api = 36) {
        signal(RouteSignal.Available(A))
        signal(RouteSignal.CapabilitiesChanged(A, DIRECT.copy(metered = ObservedBoolean.TRUE)))
        signal(RouteSignal.LinkPropertiesChanged(A))
        signal(RouteSignal.BlockedStatusChanged(A, blocked = false))
        signal(RouteSignal.BootstrapSnapshot(A))
        policy(expect = ExternalFetchRouteReason.ROUTE_READY)
        signal(RouteSignal.Available(B))
        signal(RouteSignal.CapabilitiesChanged(B, VPN))
        policy(expect = ExternalFetchRouteReason.ROUTE_READY)
        signal(RouteSignal.Available(A))
        signal(RouteSignal.CapabilitiesChanged(A, DIRECT.copy(validated = ObservedBoolean.FALSE)))
        policy(expect = ExternalFetchRouteReason.ROUTE_READY)
        assertEquals(SessionRouteGuardState.SYSTEM_DEFAULT_ALLOWED, guard.state)
    }

    /** API 23: CONNECTIVITY_ACTION snapshots only; blocked/suspended stay UNKNOWN. */
    private fun api23LegacySnapshots() = case("api23-legacy-snapshots", api = 23) {
        signal(RouteSignal.LegacySnapshot(null, null))
        policy(expect = ExternalFetchRouteReason.NO_USABLE_DEFAULT)
        signal(RouteSignal.LegacySnapshot(A, null))
        policy(expect = ExternalFetchRouteReason.CAPABILITIES_PENDING)
        signal(RouteSignal.LegacySnapshot(A, LEGACY_VPN))
        policy(expect = ExternalFetchRouteReason.ROUTE_READY)
        signal(RouteSignal.LegacySnapshot(B, LEGACY_DIRECT))
        policy(expect = ExternalFetchRouteReason.VPN_CONTINUITY_REQUIRED)
        signal(RouteSignal.LegacySnapshot(null, null))
        signal(RouteSignal.LegacySnapshot(B, LEGACY_VPN))
        policy(expect = ExternalFetchRouteReason.ROUTE_READY)
    }

    /** API 28: session starts before any observation; suspended is observable. */
    private fun api28StartupRaceAndSuspension() = case("api28-startup-suspended", api = 28) {
        policy(expect = ExternalFetchRouteReason.INITIALIZING)
        signal(RouteSignal.BootstrapSnapshot(null))
        policy(expect = ExternalFetchRouteReason.NO_USABLE_DEFAULT)
        signal(RouteSignal.Available(A))
        policy(expect = ExternalFetchRouteReason.CAPABILITIES_PENDING)
        signal(RouteSignal.CapabilitiesChanged(A, DIRECT.copy(vpn = ObservedBoolean.UNKNOWN)))
        policy(expect = ExternalFetchRouteReason.SESSION_ROUTE_UNRESOLVED)
        signal(RouteSignal.CapabilitiesChanged(A, DIRECT.copy(suspended = ObservedBoolean.TRUE)))
        policy(expect = ExternalFetchRouteReason.NETWORK_SUSPENDED)
        signal(RouteSignal.CapabilitiesChanged(A, DIRECT.copy(restricted = ObservedBoolean.TRUE)))
        policy(expect = ExternalFetchRouteReason.NETWORK_RESTRICTED)
        signal(RouteSignal.CapabilitiesChanged(A, DIRECT.copy(internet = ObservedBoolean.FALSE)))
        policy(expect = ExternalFetchRouteReason.NO_INTERNET_CAPABILITY)
        assertEquals(SessionRouteGuardState.SYSTEM_DEFAULT_ALLOWED, guard.state)
    }

    /** API 24: no ordered-callback guarantee; stale and duplicate events. */
    private fun api24UnorderedCallbacks() = case("api24-unordered-callbacks", api = 24) {
        signal(RouteSignal.CapabilitiesChanged(A, LEGACY_DIRECT))
        signal(RouteSignal.Available(A))
        signal(RouteSignal.LinkPropertiesChanged(A))
        signal(RouteSignal.BootstrapSnapshot(A))
        policy(expect = ExternalFetchRouteReason.CAPABILITIES_PENDING)
        signal(RouteSignal.CapabilitiesChanged(A, LEGACY_DIRECT))
        signal(RouteSignal.Available(A))
        signal(RouteSignal.Available(B))
        signal(RouteSignal.LinkPropertiesChanged(A))
        signal(RouteSignal.Lost(A))
        signal(RouteSignal.CapabilitiesChanged(B, LEGACY_VPN))
        policy(expect = ExternalFetchRouteReason.ROUTE_READY)
        signal(RouteSignal.Lost(B))
        signal(RouteSignal.Available(B))
        policy(expect = ExternalFetchRouteReason.CAPABILITIES_PENDING)
    }

    private fun case(caseId: String, api: Int, script: CaseScope.() -> Unit) {
        val recorder = RouteEvidenceRecorder(
            runId = "m2-b-host-$caseId",
            sessionId = "m2-b-host-$caseId-session",
            androidApi = api,
        )
        val scope = CaseScope(recorder)
        scope.reducer.start(scope.tick())
        scope.script()
        scope.reducer.stop(scope.tick())

        val artifact = recorder.toArtifactMap()
        assertTrue(recorder.events().size >= 2)
        val root = File(EVIDENCE_ROOT, caseId).apply { mkdirs() }
        File(root, "route-events.json").writeText(json(artifact) + "\n")
        File(root, "android-api.txt").writeText("$api\n")
    }

    private class CaseScope(val recorder: RouteEvidenceRecorder) {
        val reducer = DefaultRouteReducer(recorder)
        val guard = SessionRouteGuard()
        private var clock = 1_000_000L

        fun tick(): Long {
            clock += 250_000
            return clock
        }

        fun signal(signal: RouteSignal) {
            reducer.reduce(signal, tick())
        }

        fun policy(expect: ExternalFetchRouteReason? = null) {
            val decision = recorder.evaluate(guard, reducer.current)
            if (expect != null) {
                assertEquals(expect, decision.reason)
            }
        }
    }

    private companion object {
        const val EVIDENCE_ROOT = "build/m2-b-route"

        val A = PlatformRouteRef("p1")
        val B = PlatformRouteRef("p2")
        val C = PlatformRouteRef("p3")

        val DIRECT = ObservedRouteCapabilities(
            internet = ObservedBoolean.TRUE,
            validated = ObservedBoolean.TRUE,
            vpn = ObservedBoolean.FALSE,
            metered = ObservedBoolean.FALSE,
            restricted = ObservedBoolean.FALSE,
            suspended = ObservedBoolean.FALSE,
        )
        val VPN = DIRECT.copy(vpn = ObservedBoolean.TRUE)
        val LEGACY_DIRECT = DIRECT.copy(suspended = ObservedBoolean.UNKNOWN)
        val LEGACY_VPN = VPN.copy(suspended = ObservedBoolean.UNKNOWN)

        fun json(value: Any?): String = when (value) {
            null -> "null"
            is String -> buildString {
                append('"')
                value.forEach { char ->
                    when (char) {
                        '\\' -> append("\\\\")
                        '"' -> append("\\\"")
                        '\n' -> append("\\n")
                        else -> append(char)
                    }
                }
                append('"')
            }
            is Number, is Boolean -> value.toString()
            is Map<*, *> -> value.entries.joinToString(prefix = "{", postfix = "}") { (key, item) ->
                json(key.toString()) + ":" + json(item)
            }
            is Iterable<*> -> value.joinToString(prefix = "[", postfix = "]") { json(it) }
            else -> error("unsupported JSON value: $value")
        }
    }
}
