package com.karaokei.core.common.result

/**
 * A lightweight Result type for the data layer.
 *
 * - [Success] holds a value.
 * - [Failure] holds a typed [AppError].
 *
 * This is intentionally not a `sealed interface` re-implementation of
 * `kotlin.Result`; it exists so the rest of the app can carry typed
 * failures through coroutines without exception plumbing.
 */
sealed interface AppResult<out T> {
    data class Success<T>(val value: T) : AppResult<T>
    data class Failure(val error: AppError) : AppResult<Nothing>
}

inline fun <T, R> AppResult<T>.map(transform: (T) -> R): AppResult<R> = when (this) {
    is AppResult.Success -> AppResult.Success(transform(value))
    is AppResult.Failure -> this
}

inline fun <T> AppResult<T>.onSuccess(block: (T) -> Unit): AppResult<T> {
    if (this is AppResult.Success) block(value)
    return this
}

inline fun <T> AppResult<T>.onFailure(block: (AppError) -> Unit): AppResult<T> {
    if (this is AppResult.Failure) block(error)
    return this
}

fun <T> AppResult<T>.getOrNull(): T? = (this as? AppResult.Success<T>)?.value

fun <T> AppResult<T>.getOrThrow(): T = when (this) {
    is AppResult.Success -> value
    is AppResult.Failure -> throw IllegalStateException(
        error.message,
        error.cause,
    )
}

/**
 * Domain-level error types. The data layer maps platform exceptions
 * (IOException, SQLiteException, ONNX exceptions, etc.) into one of
 * these so upstream code can react meaningfully.
 */
sealed class AppError(open val message: String, open val cause: Throwable? = null) {
    data class NotFound(override val message: String) : AppError(message)
    data class Io(override val message: String, override val cause: Throwable? = null) : AppError(message, cause)
    data class Network(override val message: String, override val cause: Throwable? = null) : AppError(message, cause)
    data class Database(override val message: String, override val cause: Throwable? = null) : AppError(message, cause)
    data class Model(override val message: String, override val cause: Throwable? = null) : AppError(message, cause)
    data class Inference(override val message: String, override val cause: Throwable? = null) : AppError(message, cause)
    data class Audio(override val message: String, override val cause: Throwable? = null) : AppError(message, cause)
    data class UserCancelled(override val message: String = "cancelled by user") : AppError(message)
    data class Unknown(override val message: String, override val cause: Throwable? = null) : AppError(message, cause)
}

inline fun <T> runCatchingResult(block: () -> T): AppResult<T> = try {
    AppResult.Success(block())
} catch (t: Throwable) {
    AppResult.Failure(AppError.Unknown(t.message ?: t::class.java.simpleName, t))
}
