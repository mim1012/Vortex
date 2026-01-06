package com.example.twinme.domain.state.handlers

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.twinme.data.CallAcceptState
import com.example.twinme.domain.state.StateContext
import com.example.twinme.domain.state.StateHandler
import com.example.twinme.domain.state.StateResult

/**
 * CALL_ACCEPTED 상태 핸들러 (원본 APK 방식)
 *
 * 동작:
 * 1. 콜 수락 완료 후 즉시 WAITING_FOR_CALL로 리셋
 * 2. eligibleCall 초기화는 changeState에서 자동 처리됨
 * 3. 500ms 지연 후 다음 콜 대기 시작 (원본 APK 라인 2523: 0x1f4)
 *
 * 원본 APK 흐름:
 * WAITING_FOR_CONFIRM → CALL_ACCEPTED (500ms 대기) → WAITING_FOR_CALL
 */
class CallAcceptedHandler : StateHandler {
    companion object {
        private const val TAG = "CallAcceptedHandler"
    }

    override val targetState = CallAcceptState.CALL_ACCEPTED

    override fun handle(node: AccessibilityNodeInfo, context: StateContext): StateResult {
        Log.d(TAG, "콜 수락 완료 → WAITING_FOR_CALL로 자동 리셋")

        // ⭐ 원본 APK 방식: 500ms 대기 후 자동으로 다음 콜 대기 상태로 전환
        // 원본: MacroEngine.smali 라인 1702-1708
        // - CALL_ACCEPTED 상태 진입 시 0x1f4 (500ms) 지연
        // - 지연 후 자동으로 WAITING_FOR_CALL로 전환
        return StateResult.Transition(
            CallAcceptState.WAITING_FOR_CALL,
            "콜 수락 완료 - 다음 콜 대기"
        )
    }
}
