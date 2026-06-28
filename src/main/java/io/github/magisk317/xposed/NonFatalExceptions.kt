package io.github.magisk317.xposed

/**
 * Restores the interrupted status if the receiver is an [InterruptedException].
 */
fun Throwable.markInterruptedIfNeeded() {
    if (this is InterruptedException) {
        Thread.currentThread().interrupt()
    }
}

/**
 * Rethrows the receiver if it is a fatal VM error, [ThreadDeath], or [LinkageError].
 */
fun Throwable.rethrowIfFatal() {
    when (this) {
        is VirtualMachineError, is ThreadDeath, is LinkageError -> throw this
    }
}

/**
 * Runs [block], catching any exception.  Non-fatal exceptions are returned
 * as a failed [Result]; fatal errors are rethrown.
 */
inline fun <T> runNonFatalCatching(block: () -> T): Result<T> {
    val result = runCatching(block)
    result.exceptionOrNull()?.let { throwable ->
        throwable.markInterruptedIfNeeded()
        throwable.rethrowIfFatal()
    }
    return result
}

/**
 * Runs [block], returning `null` on non-fatal failure (fatal errors are rethrown).
 */
inline fun <T> runNonFatalOrNull(block: () -> T): T? {
    return runNonFatalCatching(block).getOrNull()
}
