package com.ahdownload.domain.resolver.youtube

import kotlinx.coroutines.CancellationException

/**
 * Captures ordinary failures without converting coroutine cancellation into a resolver failure.
 *
 * Cancellation is control flow: callers must be allowed to stop stale or superseded analyses.
 */
internal suspend fun <T> runCatchingCancellable(block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        Result.failure(error)
    }
