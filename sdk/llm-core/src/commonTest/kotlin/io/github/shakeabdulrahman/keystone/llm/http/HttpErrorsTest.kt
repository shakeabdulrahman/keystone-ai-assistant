package io.github.shakeabdulrahman.keystone.llm.http

import io.github.shakeabdulrahman.keystone.llm.LlmErrorKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class HttpErrorsTest {

    @Test
    fun statusCodesMapToKindsWithProviderCodesRefiningThem() {
        assertEquals(LlmErrorKind.RateLimited, HttpErrors.classify(429, null))
        assertEquals(LlmErrorKind.QuotaExceeded, HttpErrors.classify(429, "insufficient_quota"))
        assertEquals(LlmErrorKind.InvalidRequest, HttpErrors.classify(400, null))
        assertEquals(LlmErrorKind.ServerError, HttpErrors.classify(400, "tool_use_failed"))
        assertEquals(LlmErrorKind.Authentication, HttpErrors.classify(401, null))
        assertEquals(LlmErrorKind.PermissionDenied, HttpErrors.classify(403, null))
        assertEquals(LlmErrorKind.NotFound, HttpErrors.classify(404, null))
        assertEquals(LlmErrorKind.Timeout, HttpErrors.classify(408, null))
        assertEquals(LlmErrorKind.ServerError, HttpErrors.classify(503, null))
        assertEquals(LlmErrorKind.ServerError, HttpErrors.classify(529, null))
        assertEquals(LlmErrorKind.InvalidRequest, HttpErrors.classify(418, null))
    }

    @Test
    fun transientKindsAreExactlyTheRetryableOnes() {
        val transient = LlmErrorKind.entries.filter { it.isTransient }.toSet()
        assertEquals(
            setOf(LlmErrorKind.Network, LlmErrorKind.Timeout, LlmErrorKind.RateLimited, LlmErrorKind.ServerError),
            transient,
        )
    }

    @Test
    fun retryAfterAsSecondsDecimalsAndMilliseconds() {
        assertEquals(3.seconds, HttpErrors.retryAfter("3", null, null))
        assertEquals(1.5.seconds, HttpErrors.retryAfter(" 1.5 ", null, null))
        assertEquals(250.milliseconds, HttpErrors.retryAfter("3", "250", null)) // ms header wins
    }

    @Test
    fun retryAfterHttpDateIsMeasuredAgainstServerDateHeader() {
        val date = "Wed, 21 Oct 2015 07:28:00 GMT"
        assertEquals(90.seconds, HttpErrors.retryAfter("Wed, 21 Oct 2015 07:29:30 GMT", null, date))
        assertEquals(0.seconds, HttpErrors.retryAfter("Wed, 21 Oct 2015 07:00:00 GMT", null, date)) // in the past
        assertNull(HttpErrors.retryAfter("Wed, 21 Oct 2015 07:29:30 GMT", null, null)) // no Date: ignored
    }

    @Test
    fun invalidRetryAfterValuesAreIgnoredNotThrown() {
        assertNull(HttpErrors.retryAfter("1.2.3", null, null))
        assertNull(HttpErrors.retryAfter("-5", null, null))
        assertNull(HttpErrors.retryAfter("soon", null, null))
        assertNull(HttpErrors.retryAfter("", "NaN", null))
        assertNull(HttpErrors.retryAfter(null, null, null))
    }

    @Test
    fun httpDateParsingMatchesKnownEpochValues() {
        assertEquals(784111777L, HttpErrors.parseHttpDate("Sun, 06 Nov 1994 08:49:37 GMT"))
        assertEquals(0L, HttpErrors.parseHttpDate("Thu, 01 Jan 1970 00:00:00 GMT"))
        assertEquals(951782400L, HttpErrors.parseHttpDate("Tue, 29 Feb 2000 00:00:00 GMT"))
    }

    @Test
    fun exceptionMessageExcludesProviderTextWhichIsKeptSeparatelyAndTruncated() {
        val e = HttpErrors.exception("groq", 400, "bad", "x".repeat(1000), "req-1", null)
        assertEquals("groq returned HTTP 400 (bad)", e.message)
        assertEquals(HttpErrors.MAX_PROVIDER_MESSAGE, e.providerMessage!!.length)
        assertEquals("req-1", e.requestId)
    }
}
