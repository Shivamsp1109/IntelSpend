package com.spendwise.di

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Timeouts on the two HTTP clients.
 *
 * Pinned because the failure was invisible and total: with OkHttp's ten-second
 * defaults, *every* smart extraction gave up before the server answered and
 * reported "unreachable: timeout" — while the backend was working normally and
 * about to reply. Nothing looked broken from the server side, and the message
 * pointed at the wrong layer entirely.
 *
 * A vision extraction uploads megabytes of base64, waits on a model, and may be
 * retried on a second slower model. No sensible default covers that, so it gets
 * its own client and the difference is asserted here rather than assumed.
 */
class HttpTimeoutTest {

    private val sync = MySqlModule.provideOkHttpClient()
    private val model = MySqlModule.provideModelOkHttpClient()

    /** A whole vision read plus a possible escalation. */
    @Test
    fun `model calls are given at least a minute to read`() {
        assertTrue(
            "model read timeout was ${model.readTimeoutMillis}ms",
            model.readTimeoutMillis >= TimeUnit.SECONDS.toMillis(60)
        )
    }

    /** On mobile data the upload alone can outlast the old ten-second budget. */
    @Test
    fun `model calls are given at least a minute to write`() {
        assertTrue(
            "model write timeout was ${model.writeTimeoutMillis}ms",
            model.writeTimeoutMillis >= TimeUnit.SECONDS.toMillis(60)
        )
    }

    /**
     * The specific regression. Ten seconds is what OkHttp uses when nothing is
     * configured, and it is the value that made every extraction fail.
     */
    @Test
    fun `model timeouts are not left at the defaults`() {
        val default = TimeUnit.SECONDS.toMillis(10).toInt()

        assertTrue(model.readTimeoutMillis > default)
        assertTrue(model.writeTimeoutMillis > default)
    }

    /**
     * Bounded overall, so a pathological request cannot hold the import screen
     * open indefinitely while the user waits on a spinner.
     */
    @Test
    fun `model calls still have a ceiling`() {
        assertTrue("no call timeout set", model.callTimeoutMillis > 0)
        assertTrue(model.callTimeoutMillis <= TimeUnit.MINUTES.toMillis(5))
    }

    /**
     * Sync stays impatient on purpose. Those calls carry a single row and are
     * retried by WorkManager, so waiting is worse than giving up.
     */
    @Test
    fun `sync stays far quicker to give up than the model client`() {
        assertTrue(
            "sync ${sync.readTimeoutMillis}ms vs model ${model.readTimeoutMillis}ms",
            sync.readTimeoutMillis < model.readTimeoutMillis
        )
        assertTrue(sync.readTimeoutMillis <= TimeUnit.SECONDS.toMillis(60))
    }

    @Test
    fun `both clients still set a connect timeout`() {
        assertTrue(sync.connectTimeoutMillis > 0)
        assertTrue(model.connectTimeoutMillis > 0)
    }
}
