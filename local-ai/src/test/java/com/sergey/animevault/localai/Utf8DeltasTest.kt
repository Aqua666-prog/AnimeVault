package com.sergey.animevault.localai

import org.junit.Assert.assertEquals
import org.junit.Test

class Utf8DeltasTest {
    @Test fun russianAndEmojiSplitAtEveryByte() {
        val expected = "Посоветуй психологический хоррор 😸"
        val decoder = Utf8Deltas()
        val actual = buildString {
            expected.toByteArray().forEach { append(decoder.append(byteArrayOf(it))) }
            append(decoder.append(byteArrayOf(), end = true))
        }
        assertEquals(expected, actual)
    }

    @Test fun cancellationDropsIncompleteCodepoint() {
        val decoder = Utf8Deltas()
        assertEquals("", decoder.append(byteArrayOf(0xD0.toByte())))
    }

    @Test(expected = java.nio.charset.CharacterCodingException::class)
    fun invalidUtf8FailsInsteadOfCorruptingTheTranscript() {
        Utf8Deltas().append(byteArrayOf(0xFF.toByte()))
    }

    @Test(expected = IllegalArgumentException::class)
    fun responseMustFitInContext() { AiLimits(contextTokens = 256, responseTokens = 256) }
}
