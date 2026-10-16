package com.keystone.shared.domain.repository

import com.keystone.shared.core.AppResult
import com.keystone.shared.domain.model.PolicyDocument

/** Company policy documents, opened from an answer's sources. */
interface DocumentRepository {
    suspend fun document(id: String): AppResult<PolicyDocument>
}
