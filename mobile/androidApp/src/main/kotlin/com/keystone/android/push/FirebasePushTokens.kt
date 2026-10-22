package com.keystone.android.push

import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirebasePushTokens @Inject constructor() : PushTokens {
    override suspend fun current(): String? = try {
        FirebaseMessaging.getInstance().token.await()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null // push is a bonus: the app works without it
    }

    override suspend fun delete() {
        try {
            FirebaseMessaging.getInstance().deleteToken().await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Best effort: the backend no longer maps this token to the old user anyway.
        }
    }
}
