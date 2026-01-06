package com.example.twinme.domain.state.handlers

import android.view.accessibility.AccessibilityNodeInfo
import com.example.twinme.data.CallAcceptState
import com.example.twinme.domain.state.StateContext
import com.example.twinme.domain.state.StateHandler
import com.example.twinme.domain.state.StateResult

/**
 * IDLE 상태 핸들러
 * 대기 상태에서는 아무 작업도 수행하지 않음
 */
class IdleHandler : StateHandler {
    override val targetState: CallAcceptState = CallAcceptState.IDLE

    override fun handle(node: AccessibilityNodeInfo, context: StateContext): StateResult {
        // IDLE 상태에서는 아무 작업도 하지 않음
        return StateResult.NoChange
    }
}
