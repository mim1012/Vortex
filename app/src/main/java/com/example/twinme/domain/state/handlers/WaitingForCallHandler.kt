package com.example.twinme.domain.state.handlers

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.twinme.data.CallAcceptState
import com.example.twinme.domain.state.StateContext
import com.example.twinme.domain.state.StateHandler
import com.example.twinme.domain.state.StateResult

/**
 * WAITING_FOR_CALL 상태 핸들러
 *
 * 원본 APK 방식:
 * - 이 핸들러는 즉시 LIST_DETECTED로 전환만 수행
 * - 시간 체크 및 새로고침 로직은 ListDetectedHandler에서 처리
 *
 * 동작:
 * 1. LIST_DETECTED로 즉시 전환
 */
class WaitingForCallHandler : StateHandler {
    companion object {
        private const val TAG = "WaitingForCallHandler"
    }

    override val targetState = CallAcceptState.WAITING_FOR_CALL

    override fun handle(node: AccessibilityNodeInfo, context: StateContext): StateResult {
        Log.d(TAG, "WAITING_FOR_CALL → LIST_DETECTED로 전환")

        // 즉시 LIST_DETECTED로 전환 (시간 체크는 ListDetectedHandler에서)
        return StateResult.Transition(
            CallAcceptState.LIST_DETECTED,
            "콜 리스트 화면 체크 시작"
        )
    }
}
