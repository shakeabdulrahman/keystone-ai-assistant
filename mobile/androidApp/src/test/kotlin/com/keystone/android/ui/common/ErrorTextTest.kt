package com.keystone.android.ui.common

import com.keystone.android.R
import com.keystone.shared.core.AppError
import org.junit.Assert.assertEquals
import org.junit.Test

class ErrorTextTest {

    @Test
    fun rateLimitShowsTheWait() {
        assertEquals(
            ErrorText.Counted(R.plurals.error_rate_limited_seconds, 7),
            AppError.Http(429, "rate_limited", retryAfterSeconds = 7).errorText(),
        )
        assertEquals(ErrorText.Plain(R.string.error_rate_limited), AppError.Http(429).errorText())
    }

    @Test
    fun absurdRetryAfterIsCapped() {
        assertEquals(
            ErrorText.Counted(R.plurals.error_rate_limited_seconds, 300),
            AppError.Http(429, retryAfterSeconds = 10_800).errorText(),
        )
        assertEquals(
            ErrorText.Counted(R.plurals.error_rate_limited_seconds, 1),
            AppError.Http(429, retryAfterSeconds = 0).errorText(),
        )
    }

    @Test
    fun securityRejectionsAreExplained() {
        assertEquals(ErrorText.Plain(R.string.error_app_not_verified), AppError.Http(403, "app_check_failed").errorText())
        assertEquals(ErrorText.Plain(R.string.error_auth_unavailable), AppError.Http(503, "auth_unavailable").errorText())
        assertEquals(ErrorText.Plain(R.string.error_signed_out), AppError.Http(401).errorText())
    }

    @Test
    fun otherErrorsKeepTheGenericMessage() {
        assertEquals(ErrorText.Plain(R.string.error_server, 403), AppError.Http(403, "forbidden").errorText())
        assertEquals(ErrorText.Plain(R.string.error_server, 500), AppError.Http(500).errorText())
    }
}
