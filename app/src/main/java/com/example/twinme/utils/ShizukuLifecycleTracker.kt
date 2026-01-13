package com.example.twinme.utils

import android.content.Context
import android.os.IBinder
import android.util.Log
import com.example.twinme.logging.RemoteLogger
import rikka.shizuku.Shizuku

/**
 * Shizuku 생명주기 추적기
 *
 * Shizuku 프로세스가 죽으면 자동으로 감지하여
 * 크래시 원인 분석에 활용합니다.
 */
object ShizukuLifecycleTracker {
    private const val TAG = "ShizukuLifecycle"

    private var isInitialized = false
    private var shizukuDeathTime: Long = 0
    private var appStartTime: Long = 0

    /**
     * 초기화 및 Shizuku 바인더 Death Recipient 등록
     */
    fun init(context: Context) {
        if (isInitialized) {
            Log.w(TAG, "이미 초기화됨")
            return
        }

        appStartTime = System.currentTimeMillis()
        isInitialized = true

        try {
            // Shizuku 상태 확인
            if (!Shizuku.pingBinder()) {
                Log.w(TAG, "Shizuku가 실행되지 않았거나 권한이 없음")
                return
            }

            // Shizuku 바인더 Death Recipient 등록
            val deathRecipient = object : IBinder.DeathRecipient {
                override fun binderDied() {
                    shizukuDeathTime = System.currentTimeMillis()
                    val uptime = shizukuDeathTime - appStartTime

                    Log.e(TAG, "💀 Shizuku 프로세스가 죽었습니다!")
                    Log.e(TAG, "   - 앱 시작 후 ${uptime}ms 경과")

                    // Supabase에 전송
                    RemoteLogger.logError(
                        errorType = "SHIZUKU_BINDER_DEATH",
                        message = "Shizuku 프로세스 종료 감지 (앱 시작 후 ${uptime}ms)",
                        stackTrace = """
                            앱 시작: ${appStartTime}
                            Shizuku 종료: ${shizukuDeathTime}
                            경과 시간: ${uptime}ms (${uptime / 1000}초)

                            예상 원인:
                            1. 무선 디버깅 연결 끊김
                            2. Shizuku 앱 강제 종료
                            3. 배터리 최적화에 의한 종료
                            4. 시스템 메모리 부족 (LMK)
                        """.trimIndent()
                    )

                    // 즉시 전송
                    RemoteLogger.flushLogs()
                }
            }

            // Shizuku 바인더에 등록
            Shizuku.getBinder()?.linkToDeath(deathRecipient, 0)

            Log.d(TAG, "✅ Shizuku Death Recipient 등록 완료")
            Log.d(TAG, "   Shizuku가 종료되면 자동으로 감지됩니다")

        } catch (e: SecurityException) {
            Log.w(TAG, "Shizuku 권한이 없어 Death Recipient 등록 실패", e)
        } catch (e: Exception) {
            Log.e(TAG, "Shizuku 초기화 실패", e)
        }
    }

    /**
     * Shizuku가 언제 죽었는지 반환 (0이면 아직 살아있음)
     */
    fun getShizukuDeathTime(): Long {
        return shizukuDeathTime
    }

    /**
     * Shizuku가 죽은 후 경과 시간 (ms)
     */
    fun getTimeSinceShizukuDeath(): Long {
        return if (shizukuDeathTime > 0) {
            System.currentTimeMillis() - shizukuDeathTime
        } else {
            0
        }
    }

    /**
     * Shizuku가 죽은 상태인지 확인
     */
    fun isShizukuDead(): Boolean {
        return shizukuDeathTime > 0
    }
}
