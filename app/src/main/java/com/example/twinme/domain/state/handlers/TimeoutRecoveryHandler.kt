package com.example.twinme.domain.state.handlers

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.twinme.data.CallAcceptState
import com.example.twinme.domain.state.StateContext
import com.example.twinme.domain.state.StateHandler
import com.example.twinme.domain.state.StateResult
import com.example.twinme.service.CallAcceptAccessibilityService

/**
 * TIMEOUT_RECOVERY 상태 핸들러 (원본 MacroEngine.java 라인 630-638 완전 재현)
 *
 * 타임아웃 발생 시 자동 복구를 수행합니다.
 *
 * 원본 동작:
 * 1. "예약콜 리스트" 화면이 있으면 → LIST_DETECTED로 전환
 * 2. "예약콜 리스트" 화면이 없으면 → BACK 버튼 클릭 + 상태 유지 (NoChange)
 * 3. 500ms 후 다시 handle() 실행 (TIMEOUT_RECOVERY 반복)
 * 4. "예약콜 리스트"가 감지될 때까지 무한 반복
 *
 * 이를 통해 팝업, 상세화면 등 모든 장애물을 제거하고 리스트로 복귀
 */
class TimeoutRecoveryHandler : StateHandler {
    companion object {
        private const val TAG = "TimeoutRecoveryHandler"
    }

    // 복구 추적용 변수
    private var recoveryStartTime = 0L
    private var backPressCount = 0

    override val targetState = CallAcceptState.TIMEOUT_RECOVERY

    override fun handle(node: AccessibilityNodeInfo, context: StateContext): StateResult {
        // ⭐ 복구 시작 시간 기록 (첫 진입 시)
        if (recoveryStartTime == 0L) {
            recoveryStartTime = System.currentTimeMillis()
            backPressCount = 0
        }

        // 원본 MacroEngine.java 라인 631-637: "예약콜 리스트" 텍스트 체크
        val hasListScreen = node.findAccessibilityNodeInfosByText("예약콜 리스트")
            .isNotEmpty()

        return if (hasListScreen) {
            // ⭐ 복구 완료 로깅
            val elapsedMs = System.currentTimeMillis() - recoveryStartTime
            com.example.twinme.logging.RemoteLogger.logErrorRecoveryComplete(
                backPressCount = backPressCount,
                elapsedMs = elapsedMs,
                lastRefreshTimeReset = false  // 설정 간격 대기 선택
            )

            Log.d(TAG, "예약콜 리스트로 복귀 완료 (BACK: ${backPressCount}회, 경과: ${elapsedMs}ms)")

            context.eligibleCall = null  // ⭐⭐⭐ v1.4 복원: 오래된 콜 정보 제거

            // ⭐ 복구 추적 변수 리셋
            recoveryStartTime = 0L
            backPressCount = 0

            StateResult.Transition(
                CallAcceptState.LIST_DETECTED,
                "예약콜 리스트로 복귀"
            )
        } else {
            backPressCount++
            Log.d(TAG, "백 버튼 클릭 #${backPressCount} - 예약콜 리스트로 돌아가기")

            // 뒤로가기 버튼 클릭 (GLOBAL_ACTION_BACK)
            val service = CallAcceptAccessibilityService.instance
            service?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)

            // ⭐ 원본처럼 상태 유지 (changeState 호출 안 함)
            // 500ms 후 다시 handle()이 실행되어 "예약콜 리스트" 재확인
            StateResult.NoChange
        }
    }
}
