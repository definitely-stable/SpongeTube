package io.github.definitelystable.spongetube.core.engine.route

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Test-only Activity that preserves the real Activity caller contract required by
 * [VpnService.prepare]. Android CTS uses the same startActivityForResult flow.
 *
 * This Activity is packaged only in androidTest and is not product VPN UI.
 */
class RouteVpnFeasibilityConsentActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            return
        }

        val prepare = VpnService.prepare(this)
        if (prepare == null) {
            complete(RESULT_OK)
            finish()
            return
        }
        startActivityForResult(prepare, REQUEST_CODE)
    }

    @Deprecated("Required to mirror the platform VpnService consent contract used by CTS")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE) {
            complete(resultCode)
            finish()
        }
    }

    companion object {
        private const val REQUEST_CODE = 42
        private const val PENDING = Int.MIN_VALUE

        private val resultCode = AtomicInteger(PENDING)

        @Volatile
        private var resultLatch = CountDownLatch(1)

        @Synchronized
        fun resetResult() {
            resultCode.set(PENDING)
            resultLatch = CountDownLatch(1)
        }

        fun awaitResult(timeoutMs: Long): Int? {
            val immediate = resultCode.get()
            if (immediate != PENDING) {
                return immediate
            }

            val latch = resultLatch
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                return null
            }
            return resultCode.get().takeUnless { it == PENDING }
        }

        private fun complete(value: Int) {
            if (resultCode.compareAndSet(PENDING, value)) {
                resultLatch.countDown()
            }
        }
    }
}
