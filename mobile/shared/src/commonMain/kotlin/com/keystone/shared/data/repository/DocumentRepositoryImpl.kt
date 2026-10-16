package com.keystone.shared.data.repository

import com.keystone.shared.core.AppResult
import com.keystone.shared.data.remote.KeystoneApi
import com.keystone.shared.data.remote.dto.DocumentDto
import com.keystone.shared.data.remote.safeApiCall
import com.keystone.shared.domain.model.PolicyDocument
import com.keystone.shared.domain.repository.DocumentRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.LocalDate

/**
 * Fetches documents and keeps them in memory for the app session: tapping several sources
 * of the same policy downloads it once. Policies are company-wide, not personal data, but
 * they're still only kept in memory (not on disk), like the rest of the session.
 */
class DocumentRepositoryImpl(private val api: KeystoneApi) : DocumentRepository {
    private val cache = mutableMapOf<String, PolicyDocument>()
    private val mutex = Mutex()

    override suspend fun document(id: String): AppResult<PolicyDocument> {
        mutex.withLock { cache[id] }?.let { return AppResult.Success(it) }
        // Mapping inside safeApiCall: a malformed date becomes a Failure, not a crash.
        val result = safeApiCall { api.getDocument(id).toDomain() }
        if (result is AppResult.Success) mutex.withLock { cache[id] = result.value }
        return result
    }
}

internal fun DocumentDto.toDomain() = PolicyDocument(
    id = id,
    title = title,
    version = version,
    effective = LocalDate.parse(effective),
    owner = owner,
    sections = sections.map { PolicyDocument.Section(it.index, it.heading, it.body) },
)
