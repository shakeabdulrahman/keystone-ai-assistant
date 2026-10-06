package com.keystone.shared.data.remote

import com.keystone.shared.core.AppError
import com.keystone.shared.core.AppResult
import io.ktor.client.call.body
import io.ktor.client.plugins.ResponseException
import io.ktor.serialization.ContentConvertException
import kotlinx.coroutines.CancellationException
import kotlinx.io.IOException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException

@Serializable
private data class ProblemDto(val code: String? = null)

/**
 * Runs a network call and turns every expected failure into an [AppError].
 * Coroutine cancellation is always rethrown so structured concurrency keeps working.
 */
internal suspend fun <T> safeApiCall(block: suspend () -> T): AppResult<T> = try {
    AppResult.Success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    AppResult.Failure(e.toAppError())
}

/** Maps any exception from the networking stack to an [AppError]. Never call with a cancellation. */
internal suspend fun Throwable.toAppError(): AppError = when (this) {
    is ResponseException -> {
        // Streaming responses aren't buffered, so the problem body may be unreadable: that's fine.
        val code = runCatching { response.body<ProblemDto>().code }.getOrNull()
        AppError.Http(response.status.value, code)
    }
    is IOException -> AppError.Network
    is ContentConvertException, is SerializationException -> AppError.InvalidResponse
    else -> AppError.Unknown
}
