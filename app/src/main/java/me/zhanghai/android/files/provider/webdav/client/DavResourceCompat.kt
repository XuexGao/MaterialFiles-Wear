/*
 * Copyright (c) 2024 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.webdav.client

import at.bitfire.dav4jvm.QuotedStringUtils
import at.bitfire.dav4jvm.ktor.DavResource
import at.bitfire.dav4jvm.ktor.exception.DavException
import at.bitfire.dav4jvm.ktor.exception.HttpException
import at.bitfire.dav4jvm.ktor.resolve
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.headers
import io.ktor.client.request.preparePut
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.HttpStatement
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HeadersBuilder
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.content.OutgoingContent
import io.ktor.http.isSecure
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.copyAndClose
import io.ktor.utils.io.jvm.javaio.toInputStream
import io.ktor.utils.io.jvm.javaio.toOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import me.zhanghai.android.files.provider.common.DelegateOutputStream

/**
 * Blocks the current thread until [block] completes, like the synchronous okhttp calls used before
 * the migration to the Ktor-based dav4jvm. Interruption is mapped to [InterruptedIOException] so
 * that callers keep the same semantics as before.
 */
@Throws(InterruptedIOException::class)
internal fun <T> runBlockingIo(block: suspend CoroutineScope.() -> T): T =
    try {
        runBlocking(block = block)
    } catch (e: CancellationException) {
        throw InterruptedIOException().apply { initCause(e) }
    } catch (e: InterruptedException) {
        throw InterruptedIOException().apply { initCause(e) }
    }

@Throws(DavException::class, IOException::class)
fun DavResource.getCompat(accept: String, headers: Headers?): InputStream =
    runBlockingIo {
        get(additionalHeaders = additionalAcceptHeaders(accept, headers)) { response ->
            response.bodyAsChannel().toInputStream()
        }
    }

@Throws(DavException::class, IOException::class)
fun DavResource.getRangeCompat(
    accept: String,
    offset: Long,
    size: Int,
    headers: Headers?
): InputStream =
    runBlockingIo {
        getRange(offset, size, additionalAcceptHeaders(accept, headers)) { response ->
            if (response.status != HttpStatusCode.PartialContent) {
                throw HttpException.fromResponse(response)
            }
            response.bodyAsChannel().toInputStream()
        }
    }

// This doesn't follow redirects since the request body is one-shot anyway.
@Throws(DavException::class, IOException::class)
fun HttpClient.putCompat(
    location: Url,
    ifETag: String? = null,
    ifScheduleTag: String? = null,
    ifNoneMatch: Boolean = false,
    headers: Map<String, String> = emptyMap(),
): OutputStream {
    val channel = ByteChannel(autoFlush = true)
    val body = object : OutgoingContent.WriteChannelContent() {
        override suspend fun writeTo(requestChannel: ByteWriteChannel) {
            channel.copyAndClose(requestChannel)
        }
    }
    val additionalHeaders = Headers.build {
        ifHeaders(ifETag, ifScheduleTag, ifNoneMatch)
        // Add custom headers
        for ((key, value) in headers) {
            append(key, value)
        }
    }
    val error = AtomicReference<Throwable?>()
    val finished = CountDownLatch(1)
    @OptIn(DelicateCoroutinesApi::class)
    GlobalScope.launch(Dispatchers.IO) {
        try {
            preparePut(location) {
                this.headers.appendAll(additionalHeaders)
                setBody(body)
            }.execute { response ->
                checkStatusCompat(response)
            }
        } catch (e: Throwable) {
            error.set(e)
        } finally {
            finished.countDown()
        }
    }
    return object : DelegateOutputStream(channel.toOutputStream()) {
        override fun close() {
            super.close()
            try {
                finished.await()
            } catch (e: InterruptedException) {
                throw InterruptedIOException().apply { initCause(e) }
            }
            error.get()?.let { throw it }
        }
    }
}

enum class PatchSupport {
    NONE,
    APACHE,
    SABRE
}

@Throws(DavException::class, IOException::class)
fun DavResource.getPatchSupport(): PatchSupport =
    runBlockingIo {
        val optionsResponse = options(true)
        when {
            optionsResponse.headers["Server"]?.contains("Apache") == true &&
                "<http://apache.org/dav/propset/fs/1>" in optionsResponse.davCapabilities ->
                PatchSupport.APACHE

            "sabredav-partialupdate" in optionsResponse.davCapabilities -> PatchSupport.SABRE
            else -> PatchSupport.NONE
        }
    }

// https://sabre.io/dav/http-patch/
@Throws(DavException::class, IOException::class)
fun HttpClient.patchCompat(
    location: Url,
    buffer: ByteBuffer,
    offset: Long,
    ifETag: String? = null,
    ifScheduleTag: String? = null,
    ifNoneMatch: Boolean = false,
): HttpResponse =
    runBlockingIo {
        val bytes = buffer.toByteArray()
        followRedirectsCompat(location, { currentLocation ->
            prepareRequest(currentLocation) {
                method = HttpMethod.parse("PATCH")

                header("X-Update-Range", "bytes=$offset-${offset + bytes.size - 1}")
                headers {
                    ifHeaders(ifETag, ifScheduleTag, ifNoneMatch)
                }

                setBody(ByteArrayContent(bytes, SABRE_PATCH_CONTENT_TYPE))
            }
        }) { response ->
            checkStatusCompat(response)
            response
        }
    }

@Throws(DavException::class, IOException::class)
fun HttpClient.putRangeCompat(
    location: Url,
    buffer: ByteBuffer,
    offset: Long,
    ifETag: String? = null,
    ifScheduleTag: String? = null,
    ifNoneMatch: Boolean = false,
): HttpResponse =
    runBlockingIo {
        val bytes = buffer.toByteArray()
        followRedirectsCompat(location, { currentLocation ->
            preparePut(currentLocation) {
                header(HttpHeaders.Range, "bytes=$offset-${offset + bytes.size - 1}/*")
                headers {
                    ifHeaders(ifETag, ifScheduleTag, ifNoneMatch)
                }

                setBody(ByteArrayContent(bytes))
            }
        }) { response ->
            checkStatusCompat(response)
            response
        }
    }

private fun additionalAcceptHeaders(accept: String, headers: Headers?): Headers = Headers.build {
    append(HttpHeaders.Accept, accept)
    if (headers != null) {
        for (name in headers.names()) {
            for (value in headers.getAll(name) ?: emptyList()) {
                append(name, value)
            }
        }
    }
}

private fun HeadersBuilder.ifHeaders(
    ifETag: String?,
    ifScheduleTag: String?,
    ifNoneMatch: Boolean
) {
    if (ifETag != null) {
        // only overwrite specific version
        append(HttpHeaders.IfMatch, QuotedStringUtils.asQuotedString(ifETag))
    }
    if (ifScheduleTag != null) {
        // only overwrite specific version
        append("If-Schedule-Tag-Match", QuotedStringUtils.asQuotedString(ifScheduleTag))
    }
    if (ifNoneMatch) {
        // don't overwrite anything existing
        append(HttpHeaders.IfNoneMatch, "*")
    }
}

// @see DavResource.checkStatus
@Throws(DavException::class, HttpException::class)
private suspend fun checkStatusCompat(response: HttpResponse, multiStatusIsError: Boolean = false) {
    // handle 2xx response codes
    if (response.status.isSuccess()) {
        if (response.status == HttpStatusCode.MultiStatus && multiStatusIsError) {
            throw HttpException.fromResponse(response)
        }
        return
    }

    // handle other response codes
    throw HttpException.fromResponse(response)
}

/**
 * Outcome of a single [followRedirectsCompat] hop: either the server redirected us to
 * [RedirectOutcome.Redirected.destination] (follow up with another request), or we're done with
 * the final value produced by the caller's block.
 */
private sealed class RedirectOutcome<out T> {
    data class Redirected(val destination: Url) : RedirectOutcome<Nothing>()
    data class Done<T>(val value: T) : RedirectOutcome<T>()
}

// @see DavResource.followRedirects
@Throws(DavException::class, IOException::class)
private suspend fun <T> HttpClient.followRedirectsCompat(
    location: Url,
    prepareRequest: suspend (Url) -> HttpStatement,
    block: suspend (HttpResponse) -> T
): T {
    var currentLocation = location
    var redirectCount = 0
    while (true) {
        val outcome = prepareRequest(currentLocation).execute { response ->
            val isRedirect =
                response.status == HttpStatusCode.MovedPermanently ||
                    response.status == HttpStatusCode.Found ||
                    response.status == HttpStatusCode.TemporaryRedirect ||
                    response.status == HttpStatusCode.PermanentRedirect
            if (isRedirect) {
                // take new location from response header
                val newLocation = response.headers[HttpHeaders.Location]
                    ?: throw DavException("Redirected without new Location")

                // resolve possible relative location URL
                val destination = currentLocation.resolve(newLocation)
                    ?: throw DavException("Redirected to invalid Location")

                // block insecure redirects
                if (currentLocation.protocol.isSecure() && !destination.protocol.isSecure()) {
                    throw DavException("Received redirect from HTTPS to HTTP")
                }

                RedirectOutcome.Redirected(destination)
            } else {
                // no redirect: run block and return its value
                RedirectOutcome.Done(block(response))
            }
        }
        when (outcome) {
            is RedirectOutcome.Redirected -> {
                // prevent redirect loop
                if (++redirectCount >= DavResource.MAX_REDIRECTS) {
                    throw DavException("Too many redirects")
                }

                // save new location and follow it in the next loop iteration
                currentLocation = outcome.destination
            }
            is RedirectOutcome.Done -> return outcome.value
        }
    }
}

private fun ByteBuffer.toByteArray(): ByteArray {
    val bytes = ByteArray(remaining())
    get(bytes)
    return bytes
}

private val SABRE_PATCH_CONTENT_TYPE = ContentType.parse("application/x-sabredav-partialupdate")
