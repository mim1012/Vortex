package com.example.twinme.domain.state.handlers

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.twinme.data.CallAcceptState
import com.example.twinme.domain.state.StateContext
import com.example.twinme.domain.state.StateHandler
import com.example.twinme.domain.state.StateResult

/**
 * CLICKING_ITEM 상태 핸들러 (원본 APK 방식)
 *
 * 동작:
 * 1. AnalyzingHandler에서 저장한 eligibleCall 가져오기
 * 2. **"이미 배차" 텍스트 감지** ⭐ (원본 APK 라인 415-417)
 * 3. 해당 뷰 클릭
 * 4. 로깅 (ACCEPT_STEP step=1)
 * 5. DETECTED_CALL로 전환
 */
class ClickingItemHandler : StateHandler {
    companion object {
        private const val TAG = "ClickingItemHandler"
        private const val MAX_RETRY = 3  // 최대 3회 재시도
    }

    // ⭐ 재시도 카운터 (Singleton으로 등록되어야 상태 유지됨)
    private var retryCount = 0

    override val targetState = CallAcceptState.CLICKING_ITEM

    override fun handle(node: AccessibilityNodeInfo, context: StateContext): StateResult {
        // ⭐ 재시도 횟수 로깅
        if (retryCount > 0) {
            Log.w(TAG, "🔄 콜 클릭 재시도 ${retryCount}/$MAX_RETRY")
        } else {
            Log.d(TAG, "콜 아이템 클릭 시작")
        }

        // ⭐ 원본 APK 방식: "이미 배차" 감지 (라인 415-417)
        if (node.findAccessibilityNodeInfosByText("이미 배차").isNotEmpty()) {
            Log.w(TAG, "이미 다른 기사에게 배차됨")
            retryCount = 0  // 리셋
            return StateResult.Error(
                CallAcceptState.ERROR_ASSIGNED,
                "이미 다른 기사에게 배차됨"
            )
        }

        // 1. AnalyzingHandler에서 전달받은 콜 정보 확인
        val eligibleCall = context.eligibleCall
            ?: return StateResult.Error(
                CallAcceptState.ERROR_UNKNOWN,
                "클릭할 콜 정보가 없음 (eligibleCall = null)"
            )

        Log.d(TAG, "클릭 대상: ${eligibleCall.destination}, ${eligibleCall.price}원")

        // 2. 클릭 실행 - performAction 우선 사용 (원본 APK 방식)
        val startTime = System.currentTimeMillis()
        var clickSuccess = false

        // 2-1. clickableNode가 있으면 performAction 사용 (가장 안정적)
        if (eligibleCall.clickableNode != null) {
            Log.d(TAG, "performAction으로 클릭 시도 (clickableNode 있음)")
            clickSuccess = eligibleCall.clickableNode.performAction(
                AccessibilityNodeInfo.ACTION_CLICK
            )
        }

        // 2-2. performAction 실패 시 좌표 클릭으로 폴백
        if (!clickSuccess) {
            Log.w(TAG, "performAction 실패 또는 clickableNode 없음 - 좌표 클릭 시도")
            val bounds = eligibleCall.bounds
            val centerX = bounds.centerX().toFloat()
            val centerY = bounds.centerY().toFloat()
            Log.d(TAG, "클릭 좌표: ($centerX, $centerY), bounds=$bounds")
            clickSuccess = context.performGestureClick(centerX, centerY)
        }

        val elapsedMs = System.currentTimeMillis() - startTime

        // 4. 로깅 (ACCEPT_STEP step=1)
        context.logger.logAcceptStep(
            step = 1,
            stepName = "콜 아이템 클릭",
            targetId = "call_item_${eligibleCall.destination}",
            buttonFound = true,  // bounds가 있으면 항상 true
            clickSuccess = clickSuccess,
            elapsedMs = elapsedMs
        )

        // 5. 결과 처리 (재시도 로직 포함)
        if (clickSuccess) {
            // ⭐ 성공 시 retryCount 리셋
            retryCount = 0
            Log.d(TAG, "✅ 콜 아이템 클릭 성공 → DETECTED_CALL 전환")

            return StateResult.Transition(
                CallAcceptState.DETECTED_CALL,
                "콜 아이템 클릭 성공 (${eligibleCall.price}원, ${eligibleCall.destination})"
            )
        } else {
            // ⭐ 실패 시 재시도 로직
            retryCount++

            if (retryCount >= MAX_RETRY) {
                Log.e(TAG, "❌ 콜 클릭 실패 - 최대 재시도 횟수($MAX_RETRY) 초과")
                retryCount = 0  // 리셋

                return StateResult.Error(
                    CallAcceptState.ERROR_UNKNOWN,
                    "콜 클릭 실패 (최대 재시도 초과)"
                )
            } else {
                Log.w(TAG, "⚠️ 콜 클릭 실패 - 다음 루프에서 재시도 ($retryCount/$MAX_RETRY)")

                // NoChange 반환 → 다음 루프(50ms 후)에서 재시도
                return StateResult.NoChange
            }
        }
    }
}
