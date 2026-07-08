package com.juncehome.lifepo4ble

import java.io.File
import android.util.Log

object AppLog {
    private const val DEFAULT_TAG = "KMF-BLE"

    @Volatile
    private var logger: Logger = StdoutLogger

    fun installAndroidLogger(logFile: File? = null) {
        logger = if (logFile == null) {
            AndroidLogger
        } else {
            CompositeLogger(AndroidLogger, FileLogger(logFile))
        }
    }

    fun d(message: String, tag: String = DEFAULT_TAG) {
        logger.d(tag, message)
    }

    fun w(message: String, tag: String = DEFAULT_TAG) {
        logger.w(tag, message)
    }

    fun e(message: String, throwable: Throwable? = null, tag: String = DEFAULT_TAG) {
        logger.e(tag, message, throwable)
    }

    private interface Logger {
        fun d(tag: String, message: String)
        fun w(tag: String, message: String)
        fun e(tag: String, message: String, throwable: Throwable?)
    }

    private object StdoutLogger : Logger {
        override fun d(tag: String, message: String) {
            println("D/$tag: $message")
        }

        override fun w(tag: String, message: String) {
            println("W/$tag: $message")
        }

        override fun e(tag: String, message: String, throwable: Throwable?) {
            println("E/$tag: $message")
            throwable?.printStackTrace()
        }
    }

    private object AndroidLogger : Logger {
        override fun d(tag: String, message: String) {
            Log.d(tag, message)
        }

        override fun w(tag: String, message: String) {
            Log.w(tag, message)
        }

        override fun e(tag: String, message: String, throwable: Throwable?) {
            if (throwable == null) {
                Log.e(tag, message)
            } else {
                Log.e(tag, message, throwable)
            }
        }
    }

    private class FileLogger(
        private val file: File,
    ) : Logger {
        init {
            file.parentFile?.mkdirs()
        }

        override fun d(tag: String, message: String) {
            append("D", tag, message)
        }

        override fun w(tag: String, message: String) {
            append("W", tag, message)
        }

        override fun e(tag: String, message: String, throwable: Throwable?) {
            append("E", tag, message)
            throwable?.let {
                append("E", tag, it.stackTraceToString().trimEnd())
            }
        }

        private fun append(level: String, tag: String, message: String) {
            synchronized(file) {
                file.appendText("$level/$tag: $message\n")
            }
        }
    }

    private class CompositeLogger(
        private val first: Logger,
        private val second: Logger,
    ) : Logger {
        override fun d(tag: String, message: String) {
            first.d(tag, message)
            second.d(tag, message)
        }

        override fun w(tag: String, message: String) {
            first.w(tag, message)
            second.w(tag, message)
        }

        override fun e(tag: String, message: String, throwable: Throwable?) {
            first.e(tag, message, throwable)
            second.e(tag, message, throwable)
        }
    }
}
