package io.github.shakeabdulrahman.keystone.llm

/**
 * Shared between this SDK's own modules (SSE decoding, HTTP error classification). Not for
 * application code: it can change in any release.
 */
@RequiresOptIn(
    message = "Internal to the Keystone LLM modules. It can change in any release.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY, AnnotationTarget.TYPEALIAS)
public annotation class InternalKeystoneLlmApi
