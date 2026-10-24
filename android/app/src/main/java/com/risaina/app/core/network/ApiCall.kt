package com.risaina.app.core.network

import com.risaina.app.core.common.AppError
import com.risaina.app.core.common.AppResult
import kotlinx.coroutines.CancellationException

/** Run an API call and translate failures. Cancellation always propagates. */
suspend inline fun <T> apiCall(crossinline block: suspend () -> T): AppResult<T> = try {
    AppResult.Success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    AppResult.Failure(e.toAppError())
}

/** Variant that rethrows as [AppError] for callers that compose several steps. */
suspend inline fun <T> apiOrThrow(crossinline block: suspend () -> T): T = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: AppError) {
    throw e
} catch (e: Throwable) {
    throw e.toAppError()
}
