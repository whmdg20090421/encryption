/*
 * Copyright (c) 2024 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package com.whmdg.mczj.tools.fileop.webdav.client

import at.bitfire.dav4jvm.DavResource
import at.bitfire.dav4jvm.QuotedStringUtils
import at.bitfire.dav4jvm.ResponseCallback
import at.bitfire.dav4jvm.exception.DavException
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.Pipe
import okio.buffer
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch

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

// This doesn't follow redirects since the request body is one-shot anyway.
@Throws(DavException::class, IOException::class)
fun DavResource.putCompat(
    ifETag: String? = null,
    ifScheduleTag: String? = null,
    ifNoneMatch: Boolean = false,
    headers: Map<String, String> = emptyMap(),
): OutputStream {
    val pipe = Pipe(DEFAULT_BUFFER_SIZE.toLong())
    val body = object : RequestBody() {
        override fun contentType(): MediaType? = null
        override fun isOneShot() = true
        override fun writeTo(sink: BufferedSink) {
            sink.writeAll(pipe.source)
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
    var exceptionRef: IOException? = null
    var responseRef: Response? = null
    val callbackLatch = CountDownLatch(1)
    httpClient.newCall(builder.build()).enqueue(
        object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                exceptionRef = e
                callbackLatch.countDown()
            }

            override fun onResponse(call: Call, response: Response) {
                responseRef = response
                callbackLatch.countDown()
            }
        }
    )
    val delegateStream = pipe.sink.buffer().outputStream()
    return object : OutputStream() {
        override fun write(b: Int) = delegateStream.write(b)
        override fun write(b: ByteArray) = delegateStream.write(b)
        override fun write(b: ByteArray, off: Int, len: Int) = delegateStream.write(b, off, len)
        override fun flush() = delegateStream.flush()
        override fun close() {
            delegateStream.close()
            callbackLatch.await()
            exceptionRef?.let { throw it }
            checkStatus(responseRef!!)
        }
    }
}

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
