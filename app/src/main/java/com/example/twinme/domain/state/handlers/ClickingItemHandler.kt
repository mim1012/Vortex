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

    /**
     * 주어진 좌표에서 클릭 가능한 노드 찾기
     */
    private fun findClickableNodeAtPoint(node: AccessibilityNodeInfo, x: Int, y: Int): AccessibilityNodeInfo? {
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)

        // 좌표가 이 노드 범위 안에 있는지 확인
        if (!bounds.contains(x, y)) {
            return null
        }

        // 자식 노드 중에서 찾기 (더 구체적인 노드 우선)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findClickableNodeAtPoint(child, x, y)
            if (result != null) {
                return result
            }
        }

        // 자신이 클릭 가능하면 반환
        if (node.isClickable) {
            return node
        }

        return null
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

        // 2. 클릭 실행 - 3단계 시도
        val startTime = System.currentTimeMillis()
        var clickSuccess = false
        var clickMethod = "none"

        val bounds = eligibleCall.bounds
        val centerX = bounds.centerX().toFloat()
        val centerY = bounds.centerY().toFloat()
        Log.d(TAG, "클릭 좌표: ($centerX, $centerY), bounds=$bounds")

        // 2-1. clickableNode performAction 시도
        if (eligibleCall.clickableNode != null) {
            Log.d(TAG, "1차 시도: clickableNode.performAction()")
            clickSuccess = eligibleCall.clickableNode.performAction(
                AccessibilityNodeInfo.ACTION_CLICK
            )
            if (clickSuccess) {
                clickMethod = "performAction_clickableNode"
                Log.d(TAG, "✅ clickableNode performAction 성공")
            }
        }

        // 2-2. 실패 시 rootNode에서 해당 좌표에 있는 노드 찾아서 클릭
        if (!clickSuccess) {
            Log.w(TAG, "2차 시도: 좌표로 노드 찾아서 performAction()")
            val nodeAtPoint = findClickableNodeAtPoint(node, centerX.toInt(), centerY.toInt())
            if (nodeAtPoint != null) {
                clickSuccess = nodeAtPoint.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (clickSuccess) {
                    clickMethod = "performAction_nodeAtPoint"
                    Log.d(TAG, "✅ 좌표 기반 노드 performAction 성공")
                }
            }
        }

        // 2-3. 마지막으로 제스처 클릭 시도
        if (!clickSuccess) {
            Log.w(TAG, "3차 시도: dispatchGesture 좌표 클릭")
            clickSuccess = context.performGestureClick(centerX, centerY)
            if (clickSuccess) {
                clickMethod = "dispatchGesture"
                Log.d(TAG, "✅ 제스처 클릭 전송됨 (실제 클릭 여부는 화면 전환으로 확인)")
            }
        }

        Log.d(TAG, "클릭 방법: $clickMethod, 결과: $clickSuccess")

        val elapsedMs = System.currentTimeMillis() - startTime

        // 4. 로깅 (ACCEPT_STEP step=1)
        context.logger.logAcceptStep(
            step = 1,
            stepName = "CLICKING_ITEM",  // ⭐ Phase 4: LocalLogger가 인식할 수 있는 이름
            targetId = "call_item_${eligibleCall.destination}",
            buttonFound = true,  // bounds가 있으면 항상 true
            clickSuccess = clickSuccess,
            elapsedMs = elapsedMs,
            callKey = eligibleCall.callKey  // ⭐ Phase 4: 콜 식별자 추가
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
