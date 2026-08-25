/*
 * Copyright (c) 2024 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.webdav.client

import java8.nio.file.Path as Java8Path
import at.bitfire.dav4jvm.HttpUtils
import at.bitfire.dav4jvm.Property
import at.bitfire.dav4jvm.ktor.DavCollection
import at.bitfire.dav4jvm.ktor.DavResource
import at.bitfire.dav4jvm.ktor.MultiStatusItem
import at.bitfire.dav4jvm.ktor.Response
import at.bitfire.dav4jvm.ktor.exception.DavException
import at.bitfire.dav4jvm.ktor.exception.NotFoundException
import at.bitfire.dav4jvm.property.webdav.WebDAV
import io.ktor.client.HttpClient
import io.ktor.http.Url
import kotlinx.coroutines.flow.collect
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Collections
import java.util.WeakHashMap
import java.time.Instant
import java8.nio.channels.SeekableByteChannel
import me.zhanghai.android.files.provider.common.LocalWatchService
import me.zhanghai.android.files.provider.common.NotifyEntryModifiedOutputStream
import me.zhanghai.android.files.provider.common.NotifyEntryModifiedSeekableByteChannel

// See also https://github.com/miquels/webdavfs/blob/master/fuse.go
object Client {
    private val FILE_PROPERTIES = arrayOf(
        WebDAV.ResourceType,
        WebDAV.CreationDate,
        WebDAV.GetContentLength,
        WebDAV.GetLastModified
    )

    @Volatile
    lateinit var authenticator: Authenticator

    private val clients = mutableMapOf<Authority, Pair<Authentication, HttpClient>>()

    private val collectionMemberCache = Collections.synchronizedMap(WeakHashMap<Path, Response>())

    @Throws(IOException::class)
    private fun getClient(authority: Authority): HttpClient {
        synchronized(clients) {
            val authentication =
                authenticator.getAuthentication(authority)
                    ?: throw IOException("No authentication found for $authority")
            val cached = clients[authority]
            return if (cached != null && cached.first == authentication) {
                cached.second
            } else {
                cached?.second?.close()
                authentication.createHttpClient(authority).also {
                    clients[authority] = authentication to it
                }
            }
        }
    }

    @Throws(DavException::class)
    fun makeCollection(path: Path) {
        try {
            runBlockingIo {
                DavResource(getClient(path.authority), path.url).mkCol(null) {}
            }
        } catch (e: IOException) {
            throw e.toDavException()
        }
        LocalWatchService.onEntryCreated(path as Java8Path)
    }

    @Throws(DavException::class)
    fun makeFile(path: Path) {
        try {
            put(path).close()
        } catch (e: IOException) {
            throw e.toDavException()
        }
        LocalWatchService.onEntryCreated(path as Java8Path)
    }

    @Throws(DavException::class)
    fun delete(path: Path) {
        try {
            runBlockingIo {
                DavResource(getClient(path.authority), path.url).delete {}
            }
        } catch (e: IOException) {
            throw e.toDavException()
        }
        collectionMemberCache -= path
        LocalWatchService.onEntryDeleted(path as Java8Path)
    }

    @Throws(DavException::class)
    fun move(source: Path, target: Path) {
        if (source.authority != target.authority) {
            throw IOException("Paths aren't on the same authority")
        }
        try {
            runBlockingIo {
                DavResource(getClient(source.authority), source.url).move(target.url, false) {}
            }
        } catch (e: IOException) {
            throw e.toDavException()
        }
        collectionMemberCache -= source
        collectionMemberCache -= target
        LocalWatchService.onEntryDeleted(source as Java8Path)
        LocalWatchService.onEntryCreated(target as Java8Path)
    }

    @Throws(DavException::class)
    fun get(path: Path): InputStream =
        try {
            DavResource(getClient(path.authority), path.url).getCompat("*/*", null)
        } catch (e: IOException) {
            throw e.toDavException()
        }

    @Throws(DavException::class)
    fun findCollectionMembers(path: Path): List<Path> =
        buildList {
            try {
                runBlockingIo {
                    DavCollection(getClient(path.authority), path.url)
                        .propfind(1, *FILE_PROPERTIES)
                        .collect { item ->
                            if (item !is MultiStatusItem.Response) {
                                return@collect
                            }
                            if (item.relation != Response.HrefRelation.MEMBER) {
                                return@collect
                            }
                            val response = item.response
                            this@buildList += path.resolve(response.hrefName())
                                .also {
                                    if (response.isSuccess()) {
                                        collectionMemberCache[it] = response
                                    }
                                }
                        }
                }
            } catch (e: IOException) {
                throw e.toDavException()
            }
        }

    @Throws(DavException::class)
    fun findPropertiesOrNull(path: Path, noFollowLinks: Boolean): Response? =
        try {
            findProperties(path, noFollowLinks)
        } catch (e: NotFoundException) {
            null
        } catch (e: IOException) {
            throw e.toDavException()
        }

    // TODO: Support noFollowLinks.
    @Throws(DavException::class)
    fun findProperties(path: Path, noFollowLinks: Boolean): Response {
        synchronized(collectionMemberCache) {
            collectionMemberCache.remove(path)?.let { return it }
        }
        try {
            return findProperties(
                DavResource(getClient(path.authority), path.url), *FILE_PROPERTIES
            )
        } catch (e: IOException) {
            throw e.toDavException()
        }
    }

    @Throws(DavException::class, IOException::class)
    internal fun findProperties(resource: DavResource, vararg properties: Property.Name): Response {
        var responseRef: Response? = null
        runBlockingIo {
            resource.propfind(0, *properties)
                .collect { item ->
                    if (item !is MultiStatusItem.Response) {
                        return@collect
                    }
                    if (item.relation != Response.HrefRelation.SELF) {
                        return@collect
                    }
                    if (responseRef != null) {
                        throw DavException("Duplicate response for self")
                    }
                    responseRef = item.response
                }
        }
        val response = responseRef ?: throw DavException("Couldn't find a response for self")
        response.checkSuccess()
        return response
    }

    @Throws(DavException::class)
    fun openByteChannel(path: Path, isAppend: Boolean): SeekableByteChannel {
        try {
            val client = getClient(path.authority)
            val resource = DavResource(client, path.url)
            val patchSupport = resource.getPatchSupport()
            return NotifyEntryModifiedSeekableByteChannel(
                FileByteChannel(client, resource, patchSupport, isAppend), path as Java8Path
            )
        } catch (e: IOException) {
            throw e.toDavException()
        }
    }

    @Throws(DavException::class)
    fun setLastModifiedTime(path: Path, lastModifiedTime: Instant) {
        if (true) {
            return
        }
        // The following doesn't work on most servers. See also
        // https://github.com/sabre-io/dav/issues/1277
        try {
            runBlockingIo {
                DavResource(getClient(path.authority), path.url).proppatch(
                    mapOf(WebDAV.GetLastModified to HttpUtils.formatDate(lastModifiedTime)),
                    emptyList()
                ).collect { item ->
                    if (item is MultiStatusItem.Response) {
                        item.response.checkSuccess()
                    }
                }
            }
        } catch (e: IOException) {
            throw e.toDavException()
        }
        LocalWatchService.onEntryModified(path as Java8Path)
    }

    @Throws(DavException::class)
    fun put(path: Path): OutputStream =
        try {
            NotifyEntryModifiedOutputStream(
                getClient(path.authority).putCompat(path.url), path as Java8Path
            )
        } catch (e: IOException) {
            throw e.toDavException()
        }

    // @see DavResource.checkStatus
    private fun Response.checkSuccess() {
        if (isSuccess()) {
            return
        }
        val status = status!!
        // dav4jvm 4.0.0 doesn't allow constructing its specific HttpException subclasses from
        // outside, so carry the status code in a plain DavException and let
        // DavExceptionExtensions map it to the corresponding file system exception.
        throw DavException(
            "HTTP ${status.value} ${status.description}", statusCode = status.value
        )
    }

    interface Path {
        val authority: Authority
        val url: Url
        fun resolve(other: String): Path
    }
}
