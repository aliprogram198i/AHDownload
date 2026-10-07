package com.ahdownload.core.common

sealed interface AppResult<out T> {
    data class Success<T>(val data: T) : AppResult<T>
    data class Failure(
        val code: String,
        val message: String? = null,
        val cause: Throwable? = null,
    ) : AppResult<Nothing>
}
