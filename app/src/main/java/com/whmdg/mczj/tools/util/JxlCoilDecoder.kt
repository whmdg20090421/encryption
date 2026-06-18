package com.whmdg.mczj.tools.util

import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.request.Options
import com.awxkee.jxlcoder.JxlCoder

class JxlDecoder(private val source: coil3.decode.ImageSource) : Decoder {
    override suspend fun decode(): DecodeResult {
        val bytes = source.source().readByteArray()
        val bitmap = JxlCoder.decode(bytes)
        return DecodeResult(
            image = bitmap.asImage(),
            sampled = false
        )
    }
}

class JxlDecoderFactory : Decoder.Factory {
    override suspend fun create(
        result: coil3.decode.SourceResult,
        options: Options,
        imageLoader: ImageLoader
    ): Decoder? {
        val mimeType = result.mimeType
        if (mimeType == "image/jxl") return JxlDecoder(result.source)
        val path = result.source.fileOrNull()?.toString() ?: ""
        if (path.endsWith(".jxl", ignoreCase = true)) return JxlDecoder(result.source)
        return null
    }
}
