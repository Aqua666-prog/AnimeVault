package com.sergey.animevault.localai

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/** Token boundaries can split a Russian letter or a four-byte Unicode code point. */
internal class Utf8Deltas {
    private val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    private var pending = byteArrayOf()

    fun append(bytes: ByteArray, end: Boolean = false): String {
        val input = ByteBuffer.wrap(pending + bytes)
        val output = CharBuffer.allocate(input.remaining() + 2)
        val result = decoder.decode(input, output, end)
        if (result.isError) result.throwException()
        pending = ByteArray(input.remaining()).also(input::get)
        if (end) {
            val flushed = decoder.flush(output)
            if (flushed.isError) flushed.throwException()
        }
        output.flip()
        return output.toString()
    }
}
