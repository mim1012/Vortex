package com.example.twinme.utils

import android.content.Context
import android.os.Build
import android.util.Log
import com.example.twinme.logging.RemoteLogger
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.*

/**
 * 전역 크래시 핸들러
 *
 * 앱의 모든 uncaught exception을 캡처하여
 * 크래시 원인을 Supabase에 전송합니다.
 */
class CrashHandler private constructor(
    private val context: Context
) : Thread.UncaughtExceptionHandler {

    companion object {
        private const val TAG = "CrashHandler"
        private var instance: CrashHandler? = null

        fun init(context: Context) {
            if (instance == null) {
                instance = CrashHandler(context.applicationContext)
                Thread.setDefaultUncaughtExceptionHandler(instance)
                Log.d(TAG, "전역 크래시 핸들러 등록 완료")
            }
        }
    }

    private val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            Log.e(TAG, "💥 앱 크래시 발생!", throwable)

            // 크래시 정보 수집
            val crashInfo = collectCrashInfo(thread, throwable)

            // Supabase에 전송 (동기 전송 - 크래시 직전이므로)
            sendCrashReport(crashInfo)

            Log.e(TAG, "크래시 리포트 전송 완료")

        } catch (e: Exception) {
            Log.e(TAG, "크래시 핸들러 자체에서 예외 발생", e)
        } finally {
            // 기본 핸들러 호출 (앱 종료)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    /**
     * 크래시 정보 수집
     */
    private fun collectCrashInfo(thread: Thread, throwable: Throwable): CrashInfo {
        val timestamp = System.currentTimeMillis()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

        // 스택 트레이스 추출
        val stackTrace = StringWriter().apply {
            throwable.printStackTrace(PrintWriter(this))
        }.toString()

        // 원인 분석
        val crashType = analyzeCrashType(throwable)
        val rootCause = findRootCause(throwable)

        // 시스템 정보
        val systemInfo = mapOf(
            "android_version" to Build.VERSION.RELEASE,
            "sdk_int" to Build.VERSION.SDK_INT,
            "manufacturer" to Build.MANUFACTURER,
            "model" to Build.MODEL,
            "available_memory_mb" to getAvailableMemoryMB(),
            "total_memory_mb" to getTotalMemoryMB(),
            "low_memory" to isLowMemory()
        )

        return CrashInfo(
            timestamp = timestamp,
            timestampFormatted = dateFormat.format(Date(timestamp)),
            threadName = thread.name,
            exceptionType = throwable.javaClass.simpleName,
            exceptionMessage = throwable.message ?: "No message",
            stackTrace = stackTrace,
            crashType = crashType,
            rootCause = rootCause,
            systemInfo = systemInfo
        )
    }

    /**
     * 크래시 타입 분석
     */
    private fun analyzeCrashType(throwable: Throwable): String {
        return when {
            throwable is OutOfMemoryError -> "OOM_CRASH"
            throwable is NullPointerException -> "NPE_CRASH"
            throwable is SecurityException -> "SECURITY_CRASH"
            throwable is IllegalStateException -> "ILLEGAL_STATE_CRASH"
            throwable.message?.contains("Shizuku", ignoreCase = true) == true -> "SHIZUKU_CRASH"
            throwable.message?.contains("accessibility", ignoreCase = true) == true -> "ACCESSIBILITY_CRASH"
            throwable.message?.contains("permission", ignoreCase = true) == true -> "PERMISSION_CRASH"
            else -> "UNKNOWN_CRASH"
        }
    }

    /**
     * 근본 원인 찾기 (exception chain의 최하위)
     */
    private fun findRootCause(throwable: Throwable): String {
        var cause: Throwable? = throwable
        var depth = 0
        val maxDepth = 10

        while (cause?.cause != null && depth < maxDepth) {
            cause = cause.cause
            depth++
        }

        return "${cause?.javaClass?.simpleName}: ${cause?.message ?: "No message"}"
    }

    /**
     * 가용 메모리 (MB)
     */
    private fun getAvailableMemoryMB(): Long {
        val runtime = Runtime.getRuntime()
        return (runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory()) / 1024 / 1024
    }

    /**
     * 전체 메모리 (MB)
     */
    private fun getTotalMemoryMB(): Long {
        return Runtime.getRuntime().maxMemory() / 1024 / 1024
    }

    /**
     * 저메모리 상태 확인
     */
    private fun isLowMemory(): Boolean {
        val availableMB = getAvailableMemoryMB()
        return availableMB < 50 // 50MB 미만이면 위험
    }

    /**
     * 크래시 리포트 전송
     */
    private fun sendCrashReport(crashInfo: CrashInfo) {
        // 동기 전송 (앱이 곧 종료되므로)
        RemoteLogger.logError(
            errorType = "APP_CRASH_${crashInfo.crashType}",
            message = """
                💥 앱 크래시 발생

                타입: ${crashInfo.crashType}
                예외: ${crashInfo.exceptionType}
                메시지: ${crashInfo.exceptionMessage}
                근본 원인: ${crashInfo.rootCause}
                스레드: ${crashInfo.threadName}
                시간: ${crashInfo.timestampFormatted}

                시스템 상태:
                - Android: ${crashInfo.systemInfo["android_version"]} (SDK ${crashInfo.systemInfo["sdk_int"]})
                - 기기: ${crashInfo.systemInfo["manufacturer"]} ${crashInfo.systemInfo["model"]}
                - 가용 메모리: ${crashInfo.systemInfo["available_memory_mb"]} MB
                - 전체 메모리: ${crashInfo.systemInfo["total_memory_mb"]} MB
                - 저메모리: ${crashInfo.systemInfo["low_memory"]}
            """.trimIndent(),
            stackTrace = crashInfo.stackTrace
        )

        // 즉시 전송 (버퍼 무시)
        RemoteLogger.flushLogs()
    }

    /**
     * 크래시 정보 데이터 클래스
     */
    private data class CrashInfo(
        val timestamp: Long,
        val timestampFormatted: String,
        val threadName: String,
        val exceptionType: String,
        val exceptionMessage: String,
        val stackTrace: String,
        val crashType: String,
        val rootCause: String,
        val systemInfo: Map<String, Any>
    )
}
