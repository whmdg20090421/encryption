/*
 * Copyright (c) 2024 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package com.whmdg.mczj.tools.fileop.webdav.client

import at.bitfire.dav4jvm.DavResource
import at.bitfire.dav4jvm.QuotedStringUtils
import at.bitfire.dav4jvm.ResponseCallback
import at.bitfire.dav4jvm.exception.DavException
import okhttp3.Headers
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.nio.ByteBuffer

@Throws(DavException::class, IOException::class)
fun DavResource.getCompat(accept: String, headers: Headers?): InputStream =
    get(accept, headers).also { checkStatus(it) }.body!!.byteStream()

@Throws(DavException::class, IOException::class)
fun DavResource.getRangeCompat(
    accept: String,
    offset: Long,
    size: Int,
    headers: Headers?
): InputStream =
    followRedirects {
        val request = Request.Builder().get().url(location)
        if (headers != null) {
            request.headers(headers)
        }
        request.header("Accept", accept)
        val lastIndex = offset + size - 1
        request.header("Range", "bytes=$offset-$lastIndex")
        httpClient.newCall(request.build()).execute()
    }
        .also {
            checkStatus(it)
            if (it.code != HttpURLConnection.HTTP_PARTIAL) {
                throw IOException("Expected HTTP 206 Partial Content, got ${it.code}")
            }
        }
        .body!!.byteStream()

// 同步 PUT：RequestBody.writeTo() 在调用线程执行，无 Pipe、无异步、无缓冲。
// onProgress 接收每次写入的增量字节数。
@Throws(DavException::class, IOException::class)
fun DavResource.putCompat(
    contentLength: Long,
    inputStream: InputStream,
    onProgress: (Long) -> Unit,
    ifETag: String? = null,
    ifScheduleTag: String? = null,
    ifNoneMatch: Boolean = false,
    headers: Map<String, String> = emptyMap(),
): Response {
    val body = object : RequestBody() {
        override fun contentType(): MediaType? = null
        override fun contentLength(): Long = contentLength
        override fun isOneShot() = true
        override fun writeTo(sink: BufferedSink) {
            sink.flush()  // 刷新缓冲区，确保直接写网络
            val out = sink.outputStream()
            val buf = ByteArray(UPLOAD_BUFFER_SIZE)
            while (true) {
                val n = inputStream.read(buf)
                if (n == -1) break
                out.write(buf, 0, n)
                out.flush()  // 每128KB立即发送到网络
                onProgress(n.toLong())
            }
        }
    }
    val builder = Request.Builder().put(body).url(location)
    if (ifETag != null) {
        builder.header("If-Match", QuotedStringUtils.asQuotedString(ifETag))
    }
    if (ifScheduleTag != null) {
        builder.header("If-Schedule-Tag-Match", QuotedStringUtils.asQuotedString(ifScheduleTag))
    }
    if (ifNoneMatch) {
        builder.header("If-None-Match", "*")
    }
    for ((key, value) in headers) {
        builder.header(key, value)
    }
    return httpClient.newCall(builder.build()).execute()
}

private const val UPLOAD_BUFFER_SIZE = 128 * 1024

enum class PatchSupport {
    NONE,
    APACHE,
    SABRE
}

@Throws(DavException::class, IOException::class)
fun DavResource.getPatchSupport(): PatchSupport {
    lateinit var patchSupport: PatchSupport
    options { davCapabilities, response ->
        patchSupport = when {
            response.headers["Server"]?.contains("Apache") == true &&
                "<http://apache.org/dav/propset/fs/1>" in davCapabilities ->
                PatchSupport.APACHE

            "sabredav-partialupdate" in davCapabilities -> PatchSupport.SABRE
            else -> PatchSupport.NONE
        }
    }
    return patchSupport
}

// https://sabre.io/dav/http-patch/
@Throws(DavException::class, IOException::class)
fun DavResource.patchCompat(
    buffer: ByteBuffer,
    offset: Long,
    ifETag: String? = null,
    ifScheduleTag: String? = null,
    ifNoneMatch: Boolean = false,
    callback: ResponseCallback
) {
    followRedirects {
        val builder = Request.Builder()
            .patch(buffer.toRequestBody("application/x-sabredav-partialupdate".toMediaType()))
            .url(location)
        val lastIndex = offset + buffer.remaining() - 1
        builder.header("X-Update-Range", "bytes=$offset-$lastIndex")
        if (ifETag != null) {
            builder.header("If-Match", QuotedStringUtils.asQuotedString(ifETag))
        }
        if (ifScheduleTag != null) {
            builder.header("If-Schedule-Tag-Match", QuotedStringUtils.asQuotedString(ifScheduleTag))
        }
        if (ifNoneMatch) {
            builder.header("If-None-Match", "*")
        }
        httpClient.newCall(builder.build()).execute()
    }.use { response ->
        checkStatus(response)
        callback.onResponse(response)
    }
}

@Throws(DavException::class, IOException::class)
fun DavResource.putRangeCompat(
    buffer: ByteBuffer,
    offset: Long,
    ifETag: String? = null,
    ifScheduleTag: String? = null,
    ifNoneMatch: Boolean = false,
    callback: ResponseCallback
) {
    followRedirects {
        val builder = Request.Builder()
            .put(buffer.toRequestBody())
            .url(location)
        val lastIndex = offset + buffer.remaining() - 1
        builder.header("Range", "bytes=$offset-$lastIndex/*")
        if (ifETag != null) {
            builder.header("If-Match", QuotedStringUtils.asQuotedString(ifETag))
        }
        if (ifScheduleTag != null) {
            builder.header("If-Schedule-Tag-Match", QuotedStringUtils.asQuotedString(ifScheduleTag))
        }
        if (ifNoneMatch) {
            builder.header("If-None-Match", "*")
        }
        httpClient.newCall(builder.build()).execute()
    }.use { response ->
        checkStatus(response)
        callback.onResponse(response)
    }
}

@Throws(IOException::class)
private fun checkStatus(response: Response) {
    if (!response.isSuccessful) {
        throw IOException("WebDAV HTTP ${response.code}: ${response.message}")
    }
}

private fun DavResource.followRedirects(sendRequest: () -> Response): Response {
    var response = sendRequest()
    var redirects = 0
    while (response.code in 301..308 && redirects < 10) {
        val locationHeader = response.header("Location") ?: break
        val redirectUrl = response.request.url.resolve(locationHeader) ?: break
        response.close()
        val builder = response.request.newBuilder().url(redirectUrl)
        if (response.code in 301..302) {
            builder.get()
        }
        response = httpClient.newCall(builder.build()).execute()
        redirects++
    }
    return response
}

private fun ByteBuffer.toRequestBody(contentType: MediaType? = null): RequestBody {
    val contentLength = remaining().toLong()
    mark()
    return object : RequestBody() {
        override fun contentType() = contentType

        override fun contentLength(): Long = contentLength

        override fun writeTo(sink: BufferedSink) {
            reset()
            sink.write(this@toRequestBody)
        }
    }
}
