package com.sergey.animevault.util

import kotlin.coroutines.cancellation.CancellationException

/**
 * `runCatching` also catches [CancellationException]. In coroutine code that can
 * turn a normal cancellation into an error message, retry or stale UI update.
 * This variant keeps structured concurrency intact while preserving Result-based
 * error handling for real failures.
 */
inline fun <T> runCatchingCancellable(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (error: CancellationException) {
    throw error
} catch (error: Throwable) {
    Result.failure(error)
}
