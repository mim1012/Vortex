package com.example.twinme

import android.app.Application
import android.util.Log
import com.example.twinme.logging.RemoteLogger
import com.example.twinme.logging.StateLoggingObserver
import com.example.twinme.monitoring.AccessibilityDeathTracker
import com.example.twinme.utils.CrashHandler
import com.example.twinme.utils.ShizukuLifecycleTracker
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * TwinMe Application 클래스
 *
 * 앱 전역 초기화 및 크래시 추적 설정
 */
@HiltAndroidApp
class TwinMeApplication : Application() {

    companion object {
        private const val TAG = "TwinMeApp"
    }

    @Inject
    lateinit var stateLoggingObserver: StateLoggingObserver

    override fun onCreate() {
        super.onCreate()

        Log.d(TAG, "========================================")
        Log.d(TAG, "TwinMe 앱 시작 (프로세스 시작)")
        Log.d(TAG, "========================================")

        // 1. 크래시 핸들러 등록 (가장 먼저)
        CrashHandler.init(this)
        Log.d(TAG, "✅ 전역 크래시 핸들러 등록 완료")

        // 2. Shizuku 생명주기 추적 시작
        ShizukuLifecycleTracker.init(this)
        Log.d(TAG, "✅ Shizuku 생명주기 추적 시작")

        // 3. 앱 시작 로그 전송
        RemoteLogger.logAppStart()
        Log.d(TAG, "✅ APP_START 이벤트 전송")

        // 4. 이전 세션의 마지막 상태 전송 (비정상 종료 추적)
        RemoteLogger.sendPendingStateOnStartup()
        Log.d(TAG, "✅ 이전 세션 상태 복구 시도 완료")

        // 5. 메모리 상태 로그
        logMemoryStatus()

        // 6. 접근성 서비스 비활성화 원인 추적 시작
        AccessibilityDeathTracker.startTracking(this)
        Log.d(TAG, "✅ 접근성 서비스 비활성화 원인 추적 시작")

        // 7. 저메모리 콜백 등록
        registerComponentCallbacks(object : android.content.ComponentCallbacks2 {
            override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {}
            override fun onLowMemory() {
                Log.w(TAG, "⚠️ 시스템 저메모리 경고 발생!")
                RemoteLogger.logError(
                    errorType = "SYSTEM_LOW_MEMORY",
                    message = "시스템에서 저메모리 경고 발생 - 앱이 곧 종료될 수 있음",
                    stackTrace = "가용메모리: ${getAvailableMemoryMB()}MB"
                )
            }

            override fun onTrimMemory(level: Int) {
                val levelName = when (level) {
                    android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> "RUNNING_CRITICAL (곧 죽음)"
                    android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> "RUNNING_LOW (메모리 부족)"
                    android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> "RUNNING_MODERATE (메모리 보통)"
                    android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> "UI_HIDDEN (백그라운드)"
                    android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> "BACKGROUND"
                    android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE -> "MODERATE"
                    android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> "COMPLETE (높은 우선순위로 종료 대상)"
                    else -> "UNKNOWN ($level)"
                }

                Log.w(TAG, "⚠️ onTrimMemory: $levelName")

                if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
                    RemoteLogger.logError(
                        errorType = "MEMORY_PRESSURE_$levelName",
                        message = "메모리 압박 - 레벨: $levelName (가용: ${getAvailableMemoryMB()}MB)",
                        stackTrace = null
                    )
                }
            }
        })

        Log.d(TAG, "✅ 저메모리 모니터링 등록 완료")
    }

    override fun onTerminate() {
        val timestamp = System.currentTimeMillis()
        Log.w(TAG, "========================================")
        Log.w(TAG, "TwinMe 앱 종료 (프로세스 종료) - 시각: $timestamp")
        Log.w(TAG, "========================================")

        // 1. 마지막 상태를 SharedPreferences에 기록 (동기식)
        RemoteLogger.recordLastState(
            event = "APP_TERMINATED",
            details = """
                timestamp: $timestamp
                가용메모리: ${getAvailableMemoryMB()}MB
                Shizuku 상태: ${if (ShizukuLifecycleTracker.isShizukuDead()) "죽음" else "살아있음"}
            """.trimIndent()
        )

        // 2. 동기식 앱 종료 로그 전송
        try {
            RemoteLogger.logErrorSync(
                errorType = "APP_TERMINATED",
                message = "onTerminate 호출 - 앱 프로세스 완전 종료",
                stackTrace = """
                    timestamp: $timestamp
                    가용메모리: ${getAvailableMemoryMB()}MB
                    Shizuku 상태: ${if (ShizukuLifecycleTracker.isShizukuDead()) "죽음" else "살아있음"}
                """.trimIndent()
            )
        } catch (e: Exception) {
            Log.e(TAG, "동기식 로깅 실패: ${e.message}")
        }

        super.onTerminate()
    }

    /**
     * 메모리 상태 로깅
     */
    private fun logMemoryStatus() {
        val runtime = Runtime.getRuntime()
        val maxMemory = runtime.maxMemory() / 1024 / 1024
        val totalMemory = runtime.totalMemory() / 1024 / 1024
        val freeMemory = runtime.freeMemory() / 1024 / 1024
        val usedMemory = totalMemory - freeMemory
        val availableMemory = maxMemory - usedMemory

        Log.d(TAG, "메모리 상태:")
        Log.d(TAG, "  - 최대: ${maxMemory}MB")
        Log.d(TAG, "  - 사용 중: ${usedMemory}MB")
        Log.d(TAG, "  - 가용: ${availableMemory}MB")

        if (availableMemory < 50) {
            Log.w(TAG, "  ⚠️ 가용 메모리 부족! (${availableMemory}MB < 50MB)")
        }
    }

    /**
     * 가용 메모리 반환 (MB)
     */
    private fun getAvailableMemoryMB(): Long {
        val runtime = Runtime.getRuntime()
        return (runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory()) / 1024 / 1024
    }
}
