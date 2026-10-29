package com.risaina.app.core.common

import kotlinx.serialization.json.JsonObject

/**
 * Errors the UI can present truthfully. Network and server failures are kept distinct from
 * authorization problems so screens can choose between "retry" and "sign in again".
 */
sealed class AppError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Offline : AppError("You're offline")
    class Timeout : AppError("The connection timed out")
    class Unauthorized : AppError("Please sign in again")
    class Forbidden(msg: String = "You can't do that") : AppError(msg)
    class NotFound(msg: String = "Not found") : AppError(msg)
    /** [details] is the error's `details` object when the server sent one (games send the current game). */
    class Conflict(val code: String, msg: String, val details: JsonObject? = null) : AppError(msg)
    /**
     * 409 version_conflict: the other phone saved first. [details] carries the server's current copy
     * (`list` for lists, `entry` for calendar entries, `alarm` for alarms) when it sent one.
     */
    class VersionConflict(msg: String, val details: JsonObject? = null) : AppError(msg)
    class RateLimited(val retryAfterSeconds: Int?) : AppError("Too many requests")
    class Validation(msg: String, val details: Any? = null) : AppError(msg)
    class Server(msg: String = "Something went wrong on our side") : AppError(msg)
    class Unknown(cause: Throwable) : AppError(cause.message ?: "Something went wrong", cause)

    val isRetryable: Boolean
        get() = this is Offline || this is Timeout || this is Server || this is RateLimited
}

/** Result type that never carries a raw Throwable to the UI layer. */
sealed interface AppResult<out T> {
    data class Success<T>(val value: T) : AppResult<T>
    data class Failure(val error: AppError) : AppResult<Nothing>

    val isSuccess: Boolean get() = this is Success
    fun getOrNull(): T? = (this as? Success)?.value
    fun errorOrNull(): AppError? = (this as? Failure)?.error
}

inline fun <T, R> AppResult<T>.map(f: (T) -> R): AppResult<R> = when (this) {
    is AppResult.Success -> AppResult.Success(f(value))
    is AppResult.Failure -> this
}

inline fun <T> AppResult<T>.onSuccess(f: (T) -> Unit): AppResult<T> { if (this is AppResult.Success) f(value); return this }
inline fun <T> AppResult<T>.onFailure(f: (AppError) -> Unit): AppResult<T> { if (this is AppResult.Failure) f(error); return this }

inline fun <T> appResult(block: () -> T): AppResult<T> = try {
    AppResult.Success(block())
} catch (e: AppError) {
    AppResult.Failure(e)
} catch (e: kotlinx.coroutines.CancellationException) {
    throw e
} catch (e: Throwable) {
    AppResult.Failure(AppError.Unknown(e))
}
