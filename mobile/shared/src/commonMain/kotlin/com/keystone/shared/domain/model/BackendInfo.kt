package com.keystone.shared.domain.model

/** What the app knows about the backend it is talking to. */
data class BackendInfo(
    val name: String,
    val version: String,
    val environment: String,
)
