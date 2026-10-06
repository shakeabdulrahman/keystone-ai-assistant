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
} catch (e: ResponseException) {
    val code = runCatching { e.response.body<ProblemDto>().code }.getOrNull()
    AppResult.Failure(AppError.Http(e.response.status.value, code))
} catch (e: IOException) {
    AppResult.Failure(AppError.Network)
} catch (e: ContentConvertException) {
    AppResult.Failure(AppError.InvalidResponse)
} catch (e: SerializationException) {
    AppResult.Failure(AppError.InvalidResponse)
} catch (e: Exception) {
    AppResult.Failure(AppError.Unknown)
}
