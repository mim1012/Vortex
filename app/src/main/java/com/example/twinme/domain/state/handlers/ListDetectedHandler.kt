package com.example.twinme.domain.state.handlers

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.twinme.data.CallAcceptState
import com.example.twinme.data.SettingsManager
import com.example.twinme.domain.state.StateContext
import com.example.twinme.domain.state.StateHandler
import com.example.twinme.domain.state.StateResult
import javax.inject.Inject

/**
 * LIST_DETECTED 상태 핸들러
 *
 * 원본 APK 방식 (사용자 피드백 반영):
 * - "콜 리스트 화면일 때만" 새로고침 타이머 확인
 * - 화면 감지 + 시간 체크를 동시에 수행
 *
 * 동작:
 * 1. "예약콜 리스트" 텍스트 감지 (화면 체크)
 * 2. 새로고침 간격 확인 (시간 체크)
 * 3. 조건 충족 시 REFRESHING으로 전환
 */
class ListDetectedHandler @Inject constructor(
    private val settingsManager: SettingsManager
) : StateHandler {
    companion object {
        private const val TAG = "ListDetectedHandler"
        private var lastRefreshTime = 0L
    }

    override val targetState = CallAcceptState.LIST_DETECTED

    override fun handle(node: AccessibilityNodeInfo, context: StateContext): StateResult {
        // 1. 화면 감지: "예약콜 리스트" 텍스트 확인
        val listNodes = node.findAccessibilityNodeInfosByText("예약콜 리스트")
        val hasListScreen = listNodes.isNotEmpty()

        // ⭐ 디버그: 현재 패키지 및 화면 정보
        val packageName = node.packageName?.toString() ?: "unknown"

        if (!hasListScreen) {
            // 다른 텍스트로도 시도 (공백 차이 등)
            val altNodes1 = node.findAccessibilityNodeInfosByText("예약콜")
            val altNodes2 = node.findAccessibilityNodeInfosByText("리스트")

            Log.d(TAG, "화면 감지 실패 - pkg=$packageName, 예약콜=${altNodes1.size}개, 리스트=${altNodes2.size}개")

            // ⭐ 리스트 화면 아니면 그냥 대기 (뒤로가기 안 함)
            return StateResult.NoChange
        }

        // 2. 시간 체크: 새로고침 간격 확인 (원본 방식: 리스트 화면일 때만)
        val currentTime = System.currentTimeMillis()
        val elapsed = currentTime - lastRefreshTime
        val targetDelay = calculateRefreshDelay()

        Log.d(TAG, "화면 감지 성공 | 경과: ${elapsed}ms / 목표: ${targetDelay}ms")

        // 3. 간격 도달 시 REFRESHING으로 전환
        return if (elapsed >= targetDelay) {
            lastRefreshTime = currentTime
            Log.d(TAG, "새로고침 간격 도달 → REFRESHING 전환")

            // StateContext에 경과 시간 정보 저장 (RefreshingHandler에서 사용)
            context.refreshElapsed = elapsed
            context.refreshTargetDelay = targetDelay

            StateResult.Transition(
                CallAcceptState.REFRESHING,
                "새로고침 간격 도달 (${elapsed}ms >= ${targetDelay}ms)"
            )
        } else {
            // 아직 시간 안 됨 → 대기
            val remaining = targetDelay - elapsed
            Log.v(TAG, "새로고침 대기 중 (남은 시간: ${remaining}ms)")

            StateResult.NoChange
        }
    }

    /**
     * 새로고침 간격 계산 (±10% 랜덤)
     * 원본 APK: 5초 기준 4.5~5.5초
     */
    private fun calculateRefreshDelay(): Long {
        val baseDelay = (settingsManager.refreshDelay * 1000).toLong()  // 설정값 (초 → ms)
        val randomFactor = 0.9 + kotlin.random.Random.nextDouble(0.0, 0.2)  // ±10%
        return (baseDelay * randomFactor).toLong()
    }
}
