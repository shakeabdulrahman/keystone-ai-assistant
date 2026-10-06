package com.keystone.shared.data.remote

import com.keystone.shared.data.remote.dto.MetaDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get

/** Thin, typed wrapper over the backend's HTTP API. One method per endpoint. */
class KeystoneApi(
    private val client: HttpClient,
    baseUrl: String,
) {
    private val baseUrl = baseUrl.trimEnd('/')

    internal suspend fun meta(): MetaDto = client.get("$baseUrl/v1/meta").body()
}
