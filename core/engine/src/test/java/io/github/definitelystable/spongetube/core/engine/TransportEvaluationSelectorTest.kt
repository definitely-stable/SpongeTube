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
    fun controlKeepsTheOriginalExactRouteExecutor() {
        val selector = TransportEvaluationSelector(control, null, null)
        val selected = selector.select(TransportEvaluationBackend.HTTP_URL_CONNECTION_ROUTE_BOUND)
        assertEquals(TransportEvaluationEligibility.ELIGIBLE, selected.eligibility)
        assertSame(control, selected.executor)
        assertEquals("android-platform-url-connection", selected.implementationId)
    }
}
