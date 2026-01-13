package com.example.twinme.utils

import android.util.Log
import rikka.shizuku.Shizuku

/**
 * Shizuku 서비스 상태 체크 유틸리티
 *
 * Shizuku가 죽으면 접근성 서비스도 같이 꺼지는 경우가 많으므로
 * Shizuku 상태를 주기적으로 모니터링합니다.
 */
object ShizukuStatusChecker {
    private const val TAG = "ShizukuStatus"

    /**
     * Shizuku 바인더가 살아있는지 확인
     *
     * @return true: Shizuku 정상 작동, false: Shizuku 죽음/비활성화
     */
    fun isShizukuAvailable(): Boolean {
        return try {
            val binderAlive = Shizuku.pingBinder()
            Log.d(TAG, "Shizuku 바인더 상태: ${if (binderAlive) "살아있음" else "죽음"}")
            binderAlive
        } catch (e: Exception) {
            Log.e(TAG, "Shizuku 상태 확인 실패: ${e.message}")
            false
        }
    }

    /**
     * Shizuku 권한이 부여되어 있는지 확인
     *
     * @return true: 권한 부여됨, false: 권한 없음
     */
    fun hasShizukuPermission(): Boolean {
        return try {
            val hasPermission = Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
            Log.d(TAG, "Shizuku 권한 상태: ${if (hasPermission) "부여됨" else "없음"}")
            hasPermission
        } catch (e: Exception) {
            Log.e(TAG, "Shizuku 권한 확인 실패: ${e.message}")
            false
        }
    }

    /**
     * Shizuku 전체 상태 체크 (바인더 + 권한)
     *
     * @return true: Shizuku 사용 가능, false: Shizuku 사용 불가
     */
    fun isShizukuFullyOperational(): Boolean {
        val binderAlive = isShizukuAvailable()
        val hasPermission = hasShizukuPermission()
        val operational = binderAlive && hasPermission

        Log.d(TAG, "Shizuku 전체 상태: ${if (operational) "정상" else "비정상"} (바인더=$binderAlive, 권한=$hasPermission)")
        return operational
    }

    /**
     * Shizuku 상태 요약 메시지
     *
     * @return 사용자에게 보여줄 상태 메시지
     */
    fun getStatusMessage(): String {
        return when {
            !isShizukuAvailable() -> "⚠️ Shizuku가 실행되지 않았습니다"
            !hasShizukuPermission() -> "⚠️ Shizuku 권한이 부여되지 않았습니다"
            else -> "✅ Shizuku 정상 작동 중"
        }
    }

    /**
     * Shizuku 비활성화 시 권장 조치사항
     *
     * @return 안내 메시지
     */
    fun getRecommendedAction(): String {
        return """
            Shizuku 재시작 방법:
            1. Shizuku 앱 열기
            2. 무선 디버깅 재연결
            3. 권한 재부여

            또는:
            - ADB 케이블 연결 후 재시작
            - 루팅된 기기: Shizuku(root) 사용 권장
        """.trimIndent()
    }
}
