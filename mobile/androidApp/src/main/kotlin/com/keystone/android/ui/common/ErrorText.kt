package com.keystone.android.ui.common

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.keystone.android.R
import com.keystone.shared.core.AppError

/** User-facing copy for an error, as resource IDs so the mapping is unit-testable. */
sealed interface ErrorText {
    data class Plain(@StringRes val res: Int, val arg: Int? = null) : ErrorText
    data class Counted(@PluralsRes val res: Int, val count: Int) : ErrorText
}

/**
 * Server errors the user can act on get their own message; everything else keeps the
 * generic "server error (status)". Retry-After is capped so a bad header can't say
 * "try again in 3 hours".
 */
fun AppError.Http.errorText(): ErrorText = when {
    status == 429 -> retryAfterSeconds
        ?.let { ErrorText.Counted(R.plurals.error_rate_limited_seconds, it.coerceIn(1, 300)) }
        ?: ErrorText.Plain(R.string.error_rate_limited)
    status == 403 && code == "app_check_failed" -> ErrorText.Plain(R.string.error_app_not_verified)
    status == 503 && code == "auth_unavailable" -> ErrorText.Plain(R.string.error_auth_unavailable)
    status == 401 -> ErrorText.Plain(R.string.error_signed_out)
    status == 413 -> ErrorText.Plain(R.string.error_too_large)
    else -> ErrorText.Plain(R.string.error_server, status)
}

@Composable
fun ErrorText.resolve(): String = when (this) {
    is ErrorText.Plain -> if (arg != null) stringResource(res, arg) else stringResource(res)
    is ErrorText.Counted -> pluralStringResource(res, count, count)
}

@Composable
fun AppError.Http.userMessage(): String = errorText().resolve()
