package app.bear.store.util

import android.util.Log

/**
 * Centralized logging utility for the app
 */
object AppLogger {
    private const val TAG = "BearApp"
    
    fun d(message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.d(TAG, message, throwable)
        } else {
            Log.d(TAG, message)
        }
    }
    
    fun e(message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.e(TAG, message, throwable)
        } else {
            Log.e(TAG, message)
        }
    }
    
    fun w(message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.w(TAG, message, throwable)
        } else {
            Log.w(TAG, message)
        }
    }
    
    fun i(message: String) {
        Log.i(TAG, message)
    }
}
