package com.example.twinme.domain.state.handlers

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.twinme.data.CallAcceptState
import com.example.twinme.domain.state.StateContext
import com.example.twinme.domain.state.StateHandler
import com.example.twinme.domain.state.StateResult

/**
 * REFRESHING 상태 핸들러
 *
 * 동작:
 * 1. 새로고침 버튼 찾기
 * 2. 버튼 클릭
 * 3. 5초 간격으로 서버 로그 전송 (이벤트는 UI 설정값대로 발생)
 * 4. ANALYZING으로 전환
 */
class RefreshingHandler : StateHandler {
    companion object {
        private const val TAG = "RefreshingHandler"
        private const val REFRESH_BUTTON_ID = "com.kakao.taxi.driver:id/action_refresh"
        private const val MIN_LOG_INTERVAL_MS = 5000L  // 서버 로그는 최소 5초 간격
        private var lastLogTime = 0L
    }

    override val targetState = CallAcceptState.REFRESHING

    override fun handle(node: AccessibilityNodeInfo, context: StateContext): StateResult {
        // 1. 새로고침 버튼 검색
        val refreshButton = node.findAccessibilityNodeInfosByViewId(REFRESH_BUTTON_ID)
            .firstOrNull()

        val buttonFound = refreshButton != null
        val clickSuccess = refreshButton?.let {
            it.isClickable && it.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } ?: false

        // 2. 서버 로그 전송 (5초 간격)
        val currentTime = System.currentTimeMillis()
        if ((currentTime - lastLogTime) >= MIN_LOG_INTERVAL_MS) {
            com.example.twinme.logging.RemoteLogger.logRefreshAttempt(
                buttonFound = buttonFound,
                clickSuccess = clickSuccess,
                elapsedSinceLastRefresh = context.refreshElapsed ?: 0L,
                targetDelay = context.refreshTargetDelay ?: 0L
            )
            lastLogTime = currentTime
        }

        // 3. 결과 반환
        return if (clickSuccess) {
            Log.d(TAG, "✅ 새로고침 성공")

            StateResult.Transition(
                CallAcceptState.ANALYZING,
                "새로고침 성공"
            )
        } else {
            Log.w(TAG, "새로고침 버튼 ${if (buttonFound) "클릭 실패" else "미발견"}")
            StateResult.Error(
                CallAcceptState.ERROR_UNKNOWN,
                "새로고침 버튼 ${if (buttonFound) "클릭 실패" else "미발견"}"
            )
        }
    }
}
