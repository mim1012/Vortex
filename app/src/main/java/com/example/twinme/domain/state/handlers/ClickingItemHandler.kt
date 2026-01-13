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
        // "이미 배차" 감지
        if (node.findAccessibilityNodeInfosByText("이미 배차").isNotEmpty()) {
            retryCount = 0
            context.eligibleCall = null  // ⭐⭐⭐ v1.4 복원: 오래된 콜 정보 제거
            return StateResult.Error(CallAcceptState.ERROR_ASSIGNED, "이미 배차됨")
        }

        val eligibleCall = context.eligibleCall
            ?: return StateResult.Error(CallAcceptState.ERROR_UNKNOWN, "eligibleCall is null")

        val startTime = System.currentTimeMillis()
        var clickSuccess = false
        var clickMethod = "none"

        val bounds = eligibleCall.bounds
        val centerX = bounds.centerX().toFloat()
        val centerY = bounds.centerY().toFloat()

        // 1차: clickableNode performAction
        if (eligibleCall.clickableNode != null) {
            clickSuccess = eligibleCall.clickableNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (clickSuccess) clickMethod = "performAction_node"
        }

        // 2차: 좌표로 노드 찾기
        if (!clickSuccess) {
            val nodeAtPoint = findClickableNodeAtPoint(node, centerX.toInt(), centerY.toInt())
            if (nodeAtPoint != null) {
                clickSuccess = nodeAtPoint.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (clickSuccess) clickMethod = "performAction_coord"
            }
        }

        // 3차: dispatchGesture
        if (!clickSuccess) {
            clickSuccess = context.performGestureClick(centerX, centerY)
            if (clickSuccess) clickMethod = "dispatchGesture"
        }

        val elapsedMs = System.currentTimeMillis() - startTime

        // RemoteLogger 전송
        context.logger.logAcceptStep(
            step = 1,
            stepName = "CLICKING_ITEM",
            targetId = "call_item_${eligibleCall.destination}",
            buttonFound = true,
            clickSuccess = clickSuccess,
            elapsedMs = elapsedMs,
            callKey = eligibleCall.callKey
        )

        // 결과 처리
        if (clickSuccess) {
            retryCount = 0
            return StateResult.Transition(
                CallAcceptState.DETECTED_CALL,
                "콜 클릭 성공 ($clickMethod)"
            )
        } else {
            retryCount++
            if (retryCount >= MAX_RETRY) {
                Log.w(TAG, "콜 클릭 실패 - 최대 재시도 초과")
                retryCount = 0
                context.eligibleCall = null  // ⭐⭐⭐ v1.4 복원: 오래된 콜 정보 제거
                return StateResult.Error(CallAcceptState.ERROR_UNKNOWN, "콜 클릭 실패")
            }
            return StateResult.NoChange
        }
    }
}
