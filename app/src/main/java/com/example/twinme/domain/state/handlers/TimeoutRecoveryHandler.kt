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
 * TIMEOUT_RECOVERY 상태 핸들러 (원본 APK 방식)
 *
 * 타임아웃 발생 시 자동 복구를 수행합니다.
 *
 * 동작 (원본 APK 라인 630-638):
 * 1. "예약콜 리스트" 화면이 있으면 LIST_DETECTED로 복귀
 * 2. 없으면 뒤로가기 버튼 클릭 (GLOBAL_ACTION_BACK)
 * 3. WAITING_FOR_CALL로 재시작
 */
class TimeoutRecoveryHandler : StateHandler {
    companion object {
        private const val TAG = "TimeoutRecoveryHandler"
    }

    override val targetState = CallAcceptState.TIMEOUT_RECOVERY

    override fun handle(node: AccessibilityNodeInfo, context: StateContext): StateResult {
        Log.d(TAG, "타임아웃 복구 시도")

        // 원본 APK 라인 630-638: 리스트 화면 감지
        val hasListScreen = node.findAccessibilityNodeInfosByText("예약콜 리스트")
            .isNotEmpty()

        return if (hasListScreen) {
            Log.d(TAG, "리스트 화면 감지 → LIST_DETECTED로 복귀")
            StateResult.Transition(
                CallAcceptState.LIST_DETECTED,
                "타임아웃 후 리스트 화면으로 복귀"
            )
        } else {
            Log.d(TAG, "리스트 화면 없음 → 뒤로가기 클릭")

            // 뒤로가기 버튼 클릭 (GLOBAL_ACTION_BACK)
            // AccessibilityService 인스턴스 가져오기 (Singleton 패턴 사용)
            val service = CallAcceptAccessibilityService.instance
            val backSuccess = service?.performGlobalAction(
                AccessibilityService.GLOBAL_ACTION_BACK
            ) ?: false

            if (backSuccess) {
                Log.d(TAG, "뒤로가기 성공 → WAITING_FOR_CALL로 재시작")
                StateResult.Transition(
                    CallAcceptState.WAITING_FOR_CALL,
                    "뒤로가기 후 재시작"
                )
            } else {
                Log.e(TAG, "뒤로가기 실패")
                StateResult.Error(
                    CallAcceptState.ERROR_UNKNOWN,
                    "뒤로가기 버튼 클릭 실패"
                )
            }
        }
    }
}
