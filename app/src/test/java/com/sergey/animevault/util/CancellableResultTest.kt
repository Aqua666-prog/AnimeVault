package com.sergey.animevault.util

import com.google.common.truth.Truth.assertThat
import kotlin.coroutines.cancellation.CancellationException
import org.junit.Assert.assertThrows
import org.junit.Test

class CancellableResultTest {
    @Test
    fun cancellation_isRethrown() {
        assertThrows(CancellationException::class.java) {
            runCatchingCancellable<Int> { throw CancellationException("cancel") }
        }
    }

    @Test
    fun ordinaryFailure_staysInResult() {
        val result = runCatchingCancellable<Int> { error("boom") }

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
    }
}
