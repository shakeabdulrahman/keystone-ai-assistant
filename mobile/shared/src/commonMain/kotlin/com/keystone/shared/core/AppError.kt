package com.keystone.shared.core

/**
 * Every failure the app knows how to explain to a user.
 * The UI maps each case to its own message; nothing outside this list leaks to the screen.
 */
sealed interface AppError {
    /** No connection, DNS failure, timeout: worth retrying. */
    data object Network : AppError

    /** The server answered with an error status. `code` is the backend's stable problem code. */
    data class Http(val status: Int, val code: String? = null) : AppError

    /** The server answered, but not in the shape we expected (usually a version mismatch). */
    data object InvalidResponse : AppError

    data object Unknown : AppError
}
