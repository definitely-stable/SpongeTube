package io.github.definitelystable.spongetube.core.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

internal class TransportEvaluationSelectorTest {
    private val control = HttpRangeFetchExecutor(
        targetFor = { null },
        connectTimeoutMs = 1_000,
        readTimeoutMs = 1_000,
    )

    @Test
    fun unavailableCandidateDoesNotSelectOrFallBackToControl() {
        val selector = TransportEvaluationSelector(control, null, null)
        val candidate = selector.select(TransportEvaluationBackend.PLATFORM_HTTP_ENGINE)
        assertEquals(TransportEvaluationEligibility.UNAVAILABLE_ON_DEVICE, candidate.eligibility)
        assertNull(candidate.executor)
        assertNull(candidate.backendVersion)
        assertNull(candidate.implementationId)
    }

    @Test
    fun candidateSelectionUsesExactSameProvidedExecutorWithoutAmbientFallback() {
        // No network is opened here: the selector's only job is to preserve
        // the candidate object, version and explicit eligibility.
        val candidate = object : RouteBoundFetchAttemptExecutor {
            override suspend fun execute(
                request: FetchRequest, attempt: Int,
                priority: kotlinx.coroutines.flow.StateFlow<FetchPriority>,
                emitChunk: suspend (FetchNetworkChunk) -> Unit,
            ): FetchAttemptDisposition = error("selection must never execute")

            override suspend fun executeCorrelated(
                request: FetchRequest, attempt: Int,
                priority: kotlinx.coroutines.flow.StateFlow<FetchPriority>,
                onTransportCorrelation: suspend (String) -> Unit,
                emitChunk: suspend (FetchNetworkChunk) -> Unit,
            ): FetchAttemptDisposition = error("selection must never execute")

            override suspend fun executeWithRouteBinding(
                request: FetchRequest, attempt: Int,
                priority: kotlinx.coroutines.flow.StateFlow<FetchPriority>,
                routeBinding: io.github.definitelystable.spongetube.core.engine.route.RouteExecutionBinding,
                deliveryBinding:
                    io.github.definitelystable.spongetube.core.engine.delivery.DeliveryBindingSnapshot?,
                onPhysicalAttemptStart: () -> Unit,
                onTransportCorrelation: suspend (String) -> Unit,
                emitChunk: suspend (FetchNetworkChunk) -> Unit,
            ): FetchAttemptDisposition = error("selection must never execute")
        }
        val selected = TransportEvaluationSelector(control, candidate, "http-engine-test")
            .select(TransportEvaluationBackend.PLATFORM_HTTP_ENGINE)
        assertEquals(TransportEvaluationEligibility.ELIGIBLE, selected.eligibility)
        assertSame(candidate, selected.executor)
        assertEquals("http-engine-test", selected.backendVersion)
        assertEquals("android-platform-http-engine", selected.implementationId)
    }

    @Test
    fun controlKeepsTheOriginalExactRouteExecutor() {
        val selector = TransportEvaluationSelector(control, null, null)
        val selected = selector.select(TransportEvaluationBackend.HTTP_URL_CONNECTION_ROUTE_BOUND)
        assertEquals(TransportEvaluationEligibility.ELIGIBLE, selected.eligibility)
        assertSame(control, selected.executor)
        assertEquals("android-platform-url-connection", selected.implementationId)
    }
}
