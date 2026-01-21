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
    }

    override val targetState = CallAcceptState.LIST_DETECTED

    override fun handle(node: AccessibilityNodeInfo, context: StateContext): StateResult {
        // 1. 화면 감지: "예약콜 리스트" 텍스트 확인
        val listNodes = node.findAccessibilityNodeInfosByText("예약콜 리스트")
        val hasListScreen = listNodes.isNotEmpty()

        if (!hasListScreen) {
            return StateResult.NoChange
        }

        // 2. 시간 체크: 새로고침 간격 확인
        val currentTime = System.currentTimeMillis()
        val elapsed = currentTime - context.lastRefreshTime  // ⭐ 변경: context 사용
        val targetDelay = calculateRefreshDelay()

        // ⭐ 새로고침 타이머 체크 로깅
        val willRefresh = elapsed >= targetDelay
        com.example.twinme.logging.RemoteLogger.logRefreshTimerCheck(
            elapsed = elapsed,
            targetDelay = targetDelay,
            willRefresh = willRefresh,
            lastRefreshTime = context.lastRefreshTime
        )

        // 3. 간격 도달 시 REFRESHING으로 전환
        return if (willRefresh) {
            context.lastRefreshTime = currentTime  // ⭐ 변경: context 사용
            context.refreshElapsed = elapsed
            context.refreshTargetDelay = targetDelay

            StateResult.Transition(
                CallAcceptState.REFRESHING,
                "새로고침 간격 도달 (${elapsed}ms)"
            )
        } else {
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
