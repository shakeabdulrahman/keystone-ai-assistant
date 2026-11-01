package io.github.shakeabdulrahman.keystone.llm.ktor

import io.github.shakeabdulrahman.keystone.llm.LlmErrorKind
import io.github.shakeabdulrahman.keystone.llm.LlmException
import io.github.shakeabdulrahman.keystone.llm.http.HttpRequest
import io.github.shakeabdulrahman.keystone.llm.http.HttpResponse
import io.github.shakeabdulrahman.keystone.llm.http.HttpTransport
import io.ktor.client.HttpClient
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.content.ByteArrayContent
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import io.ktor.client.statement.HttpResponse as KtorResponse

/**
 * The default [HttpTransport], on Ktor: OkHttp on Android/JVM, Darwin (NSURLSession) on iOS.
 *
 * Pass your own [HttpClient] to share connection pools, proxies or certificate pinning with
 * the rest of your app; then you own it and must close it. Don't install plugins that read
 * or log bodies or headers: they'd buffer streams and could log keys.
 */
public class KtorHttpTransport private constructor(
    private val client: HttpClient,
    private val ownsClient: Boolean,
) : HttpTransport {

    /** Uses [client] as is. You keep ownership: [close] doesn't close it. */
    public constructor(client: HttpClient) : this(client, ownsClient = false)

    /**
     * Creates its own client. [socketTimeout] is the longest silence between bytes; the
     * client's own [io.github.shakeabdulrahman.keystone.llm.Timeouts] are usually tighter.
     * There is no total request timeout here: long streams are legitimate.
     */
    public constructor(
        connectTimeout: Duration = 15.seconds,
        socketTimeout: Duration = 120.seconds,
    ) : this(
        HttpClient {
            expectSuccess = false
            install(HttpTimeout) {
                connectTimeoutMillis = connectTimeout.inWholeMilliseconds
                socketTimeoutMillis = socketTimeout.inWholeMilliseconds
            }
        },
        ownsClient = true,
    )

    override suspend fun <T> execute(request: HttpRequest, handler: suspend (HttpResponse) -> T): T {
        try {
            return client.prepareRequest {
                method = HttpMethod.parse(request.method)
                url(request.url)
                request.headers.forEach { (name, value) -> headers.append(name, value) }
                request.body?.let { bytes ->
                    val type = request.contentType?.let(ContentType::parse) ?: ContentType.Application.Json
                    setBody(ByteArrayContent(bytes, type))
                }
            }.execute { response -> handler(Response(response, response.bodyAsChannel())) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: LlmException) {
            throw e
        } catch (e: HttpRequestTimeoutException) {
            throw LlmException(LlmErrorKind.Timeout, "HTTP request timed out", cause = e)
        } catch (e: ConnectTimeoutException) {
            throw LlmException(LlmErrorKind.Timeout, "Connect timed out", cause = e)
        } catch (e: SocketTimeoutException) {
            throw LlmException(LlmErrorKind.Timeout, "Socket timed out", cause = e)
        } catch (e: kotlinx.io.IOException) {
            // Message omitted on purpose: some engines put the full URL in it.
            throw LlmException(LlmErrorKind.Network, "Network error (${e::class.simpleName})", cause = e)
        }
    }

    override fun close() {
        if (ownsClient) client.close()
    }

    private class Response(private val response: KtorResponse, private val body: ByteReadChannel) : HttpResponse {
        private val buffer = ByteArray(8 * 1024)

        override val status: Int get() = response.status.value

        override fun header(name: String): String? = response.headers[name]

        override suspend fun readChunk(): ByteArray? {
            while (true) {
                val read = body.readAvailable(buffer, 0, buffer.size)
                if (read == -1) return null
                if (read > 0) return buffer.copyOf(read)
                if (body.isClosedForRead) return null
            }
        }
    }
}
