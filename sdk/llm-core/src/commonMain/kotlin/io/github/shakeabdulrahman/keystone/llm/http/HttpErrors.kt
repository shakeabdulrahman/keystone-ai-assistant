package io.github.shakeabdulrahman.keystone.llm.http

import io.github.shakeabdulrahman.keystone.llm.InternalKeystoneLlmApi
import io.github.shakeabdulrahman.keystone.llm.LlmErrorKind
import io.github.shakeabdulrahman.keystone.llm.LlmException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** HTTP error classification shared by the HTTP providers. */
@InternalKeystoneLlmApi
public object HttpErrors {
    /** Provider error texts are cut to this length before being kept. */
    public const val MAX_PROVIDER_MESSAGE: Int = 300

    public fun classify(status: Int, providerCode: String?): LlmErrorKind = when {
        status == 429 && providerCode == "insufficient_quota" -> LlmErrorKind.QuotaExceeded
        status == 429 -> LlmErrorKind.RateLimited
        // Groq/OpenAI-compatible: the *model* wrote a malformed tool call. A sampling hiccup,
        // not a bad request, so it's worth another try (as in Keystone's backend).
        status == 400 && providerCode == "tool_use_failed" -> LlmErrorKind.ServerError
        status == 400 || status == 413 || status == 422 -> LlmErrorKind.InvalidRequest
        status == 401 -> LlmErrorKind.Authentication
        status == 403 -> LlmErrorKind.PermissionDenied
        status == 404 -> LlmErrorKind.NotFound
        status == 408 -> LlmErrorKind.Timeout
        status == 409 -> LlmErrorKind.ServerError
        status in 500..599 || status == 529 -> LlmErrorKind.ServerError
        status in 400..499 -> LlmErrorKind.InvalidRequest
        else -> LlmErrorKind.Unknown
    }

    /**
     * The wait a server asked for. Prefers `retry-after-ms` (sent by OpenAI), then
     * `Retry-After` as delta-seconds or as an HTTP-date. An HTTP-date is measured against the
     * response's own `Date` header, so a wrong device clock doesn't matter; without a `Date`
     * header a date is ignored. Invalid values are ignored, never thrown.
     */
    public fun retryAfter(retryAfter: String?, retryAfterMs: String?, date: String?): Duration? {
        retryAfterMs?.trim()?.toDoubleOrNull()?.let { ms ->
            if (ms >= 0 && ms.isFinite()) return ms.milliseconds
        }
        val value = retryAfter?.trim().takeUnless { it.isNullOrEmpty() } ?: return null
        if (DELTA.matches(value)) {
            val seconds = value.toDouble()
            return if (seconds.isFinite()) seconds.seconds else null
        }
        val at = parseHttpDate(value) ?: return null
        val now = date?.let(::parseHttpDate) ?: return null
        return (at - now).coerceAtLeast(0).seconds
    }

    /** Builds the exception for an HTTP error response. [providerMessage] is truncated here. */
    public fun exception(
        provider: String,
        status: Int,
        providerCode: String?,
        providerMessage: String?,
        requestId: String?,
        retryAfter: Duration?,
    ): LlmException {
        val kind = classify(status, providerCode)
        val codePart = providerCode?.let { " ($it)" } ?: ""
        return LlmException(
            kind = kind,
            message = "$provider returned HTTP $status$codePart",
            provider = provider,
            httpStatus = status,
            providerCode = providerCode,
            providerMessage = providerMessage?.take(MAX_PROVIDER_MESSAGE),
            requestId = requestId,
            retryAfter = retryAfter,
        )
    }

    private val DELTA = Regex("^\\d+(\\.\\d+)?$")

    // IMF-fixdate, the only format RFC 9110 lets servers send: "Sun, 06 Nov 1994 08:49:37 GMT".
    private val IMF = Regex("^[A-Za-z]{3}, (\\d{2}) ([A-Za-z]{3}) (\\d{4}) (\\d{2}):(\\d{2}):(\\d{2}) GMT$")
    private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    /** Seconds since the epoch, or null if [value] isn't an IMF-fixdate. */
    internal fun parseHttpDate(value: String): Long? {
        val m = IMF.matchEntire(value.trim()) ?: return null
        val (d, mon, y, hh, mm, ss) = m.destructured
        val month = MONTHS.indexOf(mon) + 1
        if (month == 0) return null
        val day = d.toInt()
        if (day !in 1..31 || hh.toInt() > 23 || mm.toInt() > 59 || ss.toInt() > 60) return null
        return daysFromCivil(y.toLong(), month, day) * 86_400 + hh.toInt() * 3600 + mm.toInt() * 60 + ss.toInt()
    }

    // Howard Hinnant's days_from_civil: proleptic Gregorian date -> days since 1970-01-01.
    private fun daysFromCivil(year: Long, month: Int, day: Int): Long {
        val y = if (month <= 2) year - 1 else year
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val mp = (month + 9) % 12
        val doy = (153 * mp + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097 + doe - 719_468
    }
}
