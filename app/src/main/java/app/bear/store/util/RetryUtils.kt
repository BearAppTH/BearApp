package app.bear.store.util

import kotlin.math.min
import kotlin.random.Random

/**
 * Utility class for implementing retry logic with exponential backoff
 */
object RetryUtils {
    
    private const val DEFAULT_MAX_RETRIES = 3
    private const val DEFAULT_INITIAL_DELAY_MS = 1000L
    private const val DEFAULT_MAX_DELAY_MS = 30000L
    private const val BACKOFF_MULTIPLIER = 2.0
    
    /**
     * Retry a function with exponential backoff
     * @param maxRetries Maximum number of retry attempts (default: 3)
     * @param initialDelayMs Initial delay in milliseconds (default: 1000ms)
     * @param maxDelayMs Maximum delay between retries (default: 30000ms)
     * @param retryableException Predicate to determine if exception is retryable
     * @param block The function to retry
     * @return Result of the successful function call
     * @throws T If all retries exhausted
     */
    suspend inline fun <T> withRetry(
        maxRetries: Int = DEFAULT_MAX_RETRIES,
        initialDelayMs: Long = DEFAULT_INITIAL_DELAY_MS,
        maxDelayMs: Long = DEFAULT_MAX_DELAY_MS,
        crossinline retryableException: (Exception) -> Boolean = { isNetworkRetryable(it) },
        crossinline block: suspend () -> T
    ): T {
        var lastException: Exception? = null
        var delayMs = initialDelayMs
        
        repeat(maxRetries + 1) { attempt ->
            try {
                return block()
            } catch (e: Exception) {
                lastException = e
                if (attempt < maxRetries && retryableException(e)) {
                    val jitter = Random.nextLong(-delayMs / 4, delayMs / 4)
                    val actualDelay = min(delayMs + jitter, maxDelayMs)
                    AppLogger.w("Retry attempt ${attempt + 1}/$maxRetries after ${actualDelay}ms", e)
                    kotlinx.coroutines.delay(actualDelay)
                    delayMs = (delayMs * BACKOFF_MULTIPLIER).toLong()
                } else if (attempt >= maxRetries) {
                    throw e
                } else {
                    throw e // Non-retryable exception
                }
            }
        }
        
        throw lastException ?: Exception("Unknown error in retry logic")
    }
    
    /**
     * Check if exception is retryable (network-related)
     */
    fun isNetworkRetryable(exception: Exception): Boolean {
        return when (exception) {
            is java.io.IOException -> true
            is java.net.SocketTimeoutException -> true
            is java.net.UnknownHostException -> true
            is java.net.ConnectException -> true
            else -> false
        }
    }
}
