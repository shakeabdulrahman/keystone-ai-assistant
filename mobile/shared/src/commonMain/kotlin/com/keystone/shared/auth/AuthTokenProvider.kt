package com.keystone.shared.auth

/**
 * Supplies the current user's ID token for API calls.
 *
 * Implemented per platform (Firebase Auth on Android, Firebase iOS SDK later), so the
 * shared networking code never depends on a specific identity SDK.
 */
interface AuthTokenProvider {
    /**
     * @param forceRefresh true after the server rejected the cached token (401).
     * @return the token, or null when nobody is signed in.
     */
    suspend fun token(forceRefresh: Boolean = false): String?
}
