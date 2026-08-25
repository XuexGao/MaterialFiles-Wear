/*
 * Copyright (c) 2024 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.webdav.client

import android.os.Parcelable
import android.util.Log
import at.bitfire.dav4jvm.ktor.DomainAuthProvider
import at.bitfire.dav4jvm.ktor.PreemptiveBasicDigestAuthProvider
import at.bitfire.dav4jvm.ktor.UrlUtils
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.engine.okhttp.engine
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.AuthProvider
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.auth.HttpAuthHeader
import kotlinx.parcelize.Parcelize
import me.zhanghai.android.files.app.okHttpClient

sealed class Authentication : Parcelable {
    abstract fun createHttpClient(authority: Authority): HttpClient
}

// See also https://github.com/miquels/webdavfs/blob/master/fuse.go
internal fun newHttpClient(
    authority: Authority,
    configure: HttpClientConfig<*>.() -> Unit
): HttpClient {
    val baseOkHttpClient = okHttpClient.newBuilder()
        // Turn off follow redirects, as dav4jvm handles redirects itself.
        .followRedirects(false)
        .cookieJar(MemoryCookieJar())
        .build()
    return HttpClient(OkHttp) {
        engine {
            preconfigured = baseOkHttpClient
        }
        expectSuccess = false
        followRedirects = false
        configure()
    }
}

@Parcelize
data object NoneAuthentication : Authentication() {
    override fun createHttpClient(authority: Authority): HttpClient = newHttpClient(authority) {}
}

@Parcelize
data class PasswordAuthentication(
    val password: String
) : Authentication() {
    override fun createHttpClient(authority: Authority): HttpClient =
        newHttpClient(authority) {
            install(Auth) {
                providers.add(
                    DomainAuthProvider(
                        UrlUtils.hostToDomain(authority.host),
                        PreemptiveBasicDigestAuthProvider(authority.username, password)
                    )
                )
            }
        }
}

@Parcelize
data class AccessTokenAuthentication(
    val accessToken: String
) : Authentication() {
    override fun createHttpClient(authority: Authority): HttpClient =
        newHttpClient(authority) {
            install(Auth) {
                providers.add(BearerAccessTokenAuthProvider(authority.host, accessToken))
            }
        }
}

// Replaces the old Authorization-header interceptor, as a ktor AuthProvider.
private class BearerAccessTokenAuthProvider(
    private val host: String,
    private val accessToken: String
) : AuthProvider {
    override fun sendWithoutRequest(request: HttpRequestBuilder): Boolean = isDomainMatch(request)

    override fun isApplicable(auth: HttpAuthHeader): Boolean = false

    override suspend fun addRequestHeaders(request: HttpRequestBuilder, authHeader: HttpAuthHeader?) {
        if (!isDomainMatch(request)) {
            return
        }
        request.headers.remove(HttpHeaders.Authorization)
        request.header(HttpHeaders.Authorization, "Bearer $accessToken")
    }

    private fun isDomainMatch(request: HttpRequestBuilder): Boolean {
        val requestHost = request.url.host
        val domain = UrlUtils.hostToDomain(host)
        return if (UrlUtils.hostToDomain(requestHost).equals(domain, true)) {
            true
        } else {
            Log.w(
                LOG_TAG,
                "Not authenticating against $requestHost because it doesn't belong to " +
                    domain
            )
            false
        }
    }

    companion object {
        private val LOG_TAG = AccessTokenAuthentication::class.java.simpleName
    }
}
