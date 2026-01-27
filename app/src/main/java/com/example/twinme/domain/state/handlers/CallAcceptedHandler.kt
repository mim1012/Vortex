package com.example.twinme.domain.state.handlers

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.twinme.data.CallAcceptState
import com.example.twinme.domain.state.StateContext
import com.example.twinme.domain.state.StateHandler
import com.example.twinme.domain.state.StateResult
import com.example.twinme.util.NotificationHelper

/**
 * CALL_ACCEPTED 상태 핸들러 (원본 APK 방식)
 *
 * 동작:
 * 1. 콜 수락 완료 후 엔진 일시정지 (pause)
 * 2. IDLE 상태로 전환
 * 3. 사용자가 수동으로 resume() 호출해야 다음 콜 대기 시작
 *
 * 원본 APK 흐름 (MacroEngine.smali 라인 1347-1378):
 * WAITING_FOR_CONFIRM → CALL_ACCEPTED → pause() 호출 → IDLE (종료 상태 유지)
 *
 * ⚠️ 이전 잘못된 구현:
 * - MacroEngine.smali 라인 1702-1708 참조는 **FALSE**
 * - 실제 원본은 라인 1347-1378: SUCCESS 상태에서 pause() + IDLE 전환
 */
class CallAcceptedHandler : StateHandler {
    companion object {
        private const val TAG = "CallAcceptedHandler"
        private const val CONDITION_TAG = "CONDITION"  // ADB 필터용
    }

    override val targetState = CallAcceptState.CALL_ACCEPTED

    override fun handle(node: AccessibilityNodeInfo, context: StateContext): StateResult {
        Log.i(CONDITION_TAG, "🎉 CALL_ACCEPTED 핸들러 실행 (callKey=${context.eligibleCall?.callKey ?: "null"})")
        Log.i(CONDITION_TAG, "🎉 → PauseAndTransition(IDLE) 반환 → pause() 호출 예정")

        // ⭐ 원본 APK 방식: 알림음 + Toast (MacroEngine.java line 434-440)
        // playSuccessSound() + Toast.makeText(context, "예약 완료", 0).show()
        context.applicationContext?.let { ctx ->
            NotificationHelper.playSuccessSound(ctx)
            NotificationHelper.showToast(ctx, "예약 완료")
            Log.i(CONDITION_TAG, "🎉 알림음 + Toast 표시 완료")
        }

        // ⭐ 서버 로그: 콜 수락 성공
        com.example.twinme.logging.RemoteLogger.logCallAccepted(
            callKey = context.eligibleCall?.callKey ?: "unknown",
            price = context.eligibleCall?.price ?: 0,
            source = context.eligibleCall?.source ?: "",
            destination = context.eligibleCall?.destination ?: ""
        )

        // ⭐ 원본 APK 방식: MacroEngine.smali 라인 1347-1378
        // .line 288: invoke-virtual {v0}, Lorg/twinlife/device/android/twinme/MacroEngine;->pause()V
        // .line 289: sget-object v1, Lorg/twinlife/device/android/twinme/MacroEngine$MacroState;->IDLE:...
        return StateResult.PauseAndTransition(
            CallAcceptState.IDLE,
            "콜 수락 완료 - 엔진 일시정지"
        )
    }
}
