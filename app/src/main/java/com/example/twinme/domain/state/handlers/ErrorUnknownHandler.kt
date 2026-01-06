package com.example.twinme.domain.state.handlers

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.twinme.data.CallAcceptState
import com.example.twinme.domain.state.StateContext
import com.example.twinme.domain.state.StateHandler
import com.example.twinme.domain.state.StateResult

/**
 * ERROR_UNKNOWN 상태 핸들러 (원본 APK 방식)
 *
 * 동작:
 * 1. 일시적 에러 (클릭 실패 등)로부터 복구
 * 2. eligibleCall 유지 (재시도 가능)
 * 3. 500ms 지연 후 WAITING_FOR_CALL로 재시작
 *
 * 원본 APK 흐름:
 * ERROR_UNKNOWN (500ms 대기) → WAITING_FOR_CALL
 */
class ErrorUnknownHandler : StateHandler {
    companion object {
        private const val TAG = "ErrorUnknownHandler"
    }

    override val targetState = CallAcceptState.ERROR_UNKNOWN

    override fun handle(node: AccessibilityNodeInfo, context: StateContext): StateResult {
        Log.d(TAG, "일시적 에러 감지 → WAITING_FOR_CALL로 재시작")

        // ⭐ 원본 APK 방식: 500ms 대기 후 자동으로 재시작
        // eligibleCall은 유지 (재시도 가능)
        // 원본: MacroEngine.smali 라인 1780-1786
        // - ERROR_UNKNOWN 상태 진입 시 0x1f4 (500ms) 지연
        // - 지연 후 자동으로 WAITING_FOR_CALL로 전환
        return StateResult.Transition(
            CallAcceptState.WAITING_FOR_CALL,
            "일시적 에러 복구 - 재시작"
        )
    }
}
