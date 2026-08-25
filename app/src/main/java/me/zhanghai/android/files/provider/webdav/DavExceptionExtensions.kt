package me.zhanghai.android.files.provider.webdav

import at.bitfire.dav4jvm.ktor.exception.ConflictException
import at.bitfire.dav4jvm.ktor.exception.DavException
import at.bitfire.dav4jvm.ktor.exception.ForbiddenException
import at.bitfire.dav4jvm.ktor.exception.NotFoundException
import at.bitfire.dav4jvm.ktor.exception.UnauthorizedException
import java.net.HttpURLConnection
import java8.nio.file.AccessDeniedException
import java8.nio.file.FileAlreadyExistsException
import java8.nio.file.FileSystemException
import java8.nio.file.NoSuchFileException
import me.zhanghai.android.files.provider.webdav.client.DavIOException

fun DavException.toFileSystemException(
    file: String?,
    other: String? = null
): FileSystemException {
    return when (this) {
        is DavIOException ->
            return FileSystemException(file, other, message).apply { initCause(cause) }
        is UnauthorizedException, is ForbiddenException ->
            AccessDeniedException(file, other, message)
        is NotFoundException -> NoSuchFileException(file, other, message)
        is ConflictException -> FileAlreadyExistsException(file, other, message)
        // dav4jvm 4.0.0 doesn't allow constructing its specific HttpException subclasses from
        // outside the library, so also map on the status code carried by a plain DavException.
        else ->
            when (statusCode) {
                HttpURLConnection.HTTP_UNAUTHORIZED, HttpURLConnection.HTTP_FORBIDDEN ->
                    AccessDeniedException(file, other, message)
                HttpURLConnection.HTTP_NOT_FOUND -> NoSuchFileException(file, other, message)
                HttpURLConnection.HTTP_CONFLICT ->
                    FileAlreadyExistsException(file, other, message)
                else -> FileSystemException(file, other, message)
            }
    }.apply { initCause(this@toFileSystemException) }
}
