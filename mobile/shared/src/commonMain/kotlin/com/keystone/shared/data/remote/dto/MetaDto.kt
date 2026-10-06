package com.keystone.shared.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Wire format of `GET /v1/meta`. Kept separate from the domain model on purpose. */
@Serializable
internal data class MetaDto(
    @SerialName("name") val name: String,
    @SerialName("version") val version: String,
    @SerialName("environment") val environment: String,
    @SerialName("api_version") val apiVersion: String,
)
