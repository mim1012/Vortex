package com.example.twinme.domain.state.handlers

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.twinme.data.CallAcceptState
import com.example.twinme.domain.state.StateContext
import com.example.twinme.domain.state.StateHandler
import com.example.twinme.domain.state.StateResult
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * DETECTED_CALL 상태 핸들러
 * 콜 상세 화면에서 '콜 수락' 버튼을 찾아 클릭합니다.
 *
 * 동작 과정:
 * 1. View ID로 btn_call_accept 버튼 검색 (우선순위 1)
 * 2. 실패 시 텍스트 기반으로 "수락" 버튼 검색 (Fallback)
 * 3. 버튼 클릭
 * 4. 성공 시 WAITING_FOR_CONFIRM으로 전환
 */
class DetectedCallHandler : StateHandler {
    companion object {
        private const val TAG = "DetectedCallHandler"
        private const val ACCEPT_BUTTON_ID = "com.kakao.taxi.driver:id/btn_call_accept"
        private const val CONFIRM_BUTTON_ID = "com.kakao.taxi.driver:id/btn_positive"  // 확인 다이얼로그 버튼
        private const val MAP_VIEW_ID = "com.kakao.taxi.driver:id/map_view"  // 상세 화면 지도 뷰
        private const val CLOSE_BUTTON_ID = "com.kakao.taxi.driver:id/action_close"  // 상세 화면 닫기 버튼
        private val FALLBACK_TEXTS = listOf("수락", "직접결제 수락", "자동결제 수락", "콜 수락")  // Media_enhanced 방식
        private val CONFIRM_TEXTS = listOf("수락하기")  // 확인 다이얼로그 텍스트
        private val DETAIL_SCREEN_TEXTS = listOf("예약콜 상세", "예약콜", "출발지", "도착지")
        private const val MAX_CLICK_RETRY = 5  // 최대 클릭 재시도 횟수
    }

    // 클릭 후 다이얼로그 대기 상태 추적
    private var clickedAndWaiting = false
    private var waitRetryCount = 0

    override val targetState: CallAcceptState = CallAcceptState.DETECTED_CALL

    override fun handle(node: AccessibilityNodeInfo, context: StateContext): StateResult {
        // 0. 확인 다이얼로그 감지
        if (checkConfirmDialogVisible(node)) {
            resetState()
            return StateResult.Transition(CallAcceptState.WAITING_FOR_CONFIRM, "다이얼로그 감지")
        }

        // 클릭 후 대기 중 처리
        if (clickedAndWaiting) {
            waitRetryCount++
            if (waitRetryCount >= MAX_CLICK_RETRY) {
                resetState()
            } else {
                return StateResult.NoChange
            }
        }

        // 리스트 화면이면 CLICKING_ITEM 복귀
        if (node.findAccessibilityNodeInfosByText("예약콜 리스트").isNotEmpty()) {
            resetState()
            return StateResult.Error(CallAcceptState.CLICKING_ITEM, "화면 전환 안 됨")
        }

        // 상세 화면 검증 (View ID → 텍스트)
        val hasBtnCallAccept = findNodeByViewId(node, ACCEPT_BUTTON_ID) != null
        val hasMapView = findNodeByViewId(node, MAP_VIEW_ID) != null
        val hasCloseButton = findNodeByViewId(node, CLOSE_BUTTON_ID) != null
        val hasDetailText = node.findAccessibilityNodeInfosByText("예약콜 상세").isNotEmpty()

        if (!hasBtnCallAccept && !hasMapView && !hasCloseButton && !hasDetailText) {
            return StateResult.Error(CallAcceptState.CLICKING_ITEM, "화면 전환 실패")
        }

        // 화면 상태 로그 (서버 전송)
        context.logger.logScreenCheck(
            state = CallAcceptState.DETECTED_CALL,
            targetButtonVisible = hasBtnCallAccept,
            screenTextSummary = "",
            callKey = context.eligibleCall?.callKey ?: ""
        )

        // "이미 배차" 감지
        if (node.findAccessibilityNodeInfosByText("이미 배차").isNotEmpty()) {
            return StateResult.Error(CallAcceptState.ERROR_ASSIGNED, "이미 배차됨")
        }

        // 버튼 검색 (View ID → 텍스트)
        var acceptButton: AccessibilityNodeInfo? = null
        var foundBy = ""

        val viewIdNodes = node.findAccessibilityNodeInfosByViewId(ACCEPT_BUTTON_ID)
        for (foundNode in viewIdNodes) {
            if (foundNode.isClickable && foundNode.isEnabled) {
                acceptButton = foundNode
                foundBy = "view_id"
                break
            }
        }

        if (acceptButton == null) {
            for (text in FALLBACK_TEXTS) {
                val nodes = node.findAccessibilityNodeInfosByText(text)
                for (foundNode in nodes) {
                    if (foundNode.isClickable && foundNode.isEnabled) {
                        acceptButton = foundNode
                        foundBy = "text:$text"
                        break
                    }
                }
                if (acceptButton != null) break
            }
        }

        if (acceptButton == null || !acceptButton.isClickable) {
            context.logger.logButtonSearchFailed(
                currentState = targetState,
                targetViewId = ACCEPT_BUTTON_ID,
                searchDepth = node.childCount,
                nodeDescription = "view_id + fallback texts"
            )
            return StateResult.NoChange
        }

        // 좌표 계산
        val bounds = android.graphics.Rect()
        acceptButton.getBoundsInScreen(bounds)
        val tapX = if (bounds.centerX() > context.screenWidth || bounds.centerX() < 0) 540 else bounds.centerX()
        val tapY = if (bounds.centerX() > context.screenWidth || bounds.centerX() < 0) 2080 else bounds.centerY()

        val clickStartTime = System.currentTimeMillis()

        // 랜덤 지연 + Shizuku 클릭
        Thread.sleep(50L + Random().nextInt(100).toLong())
        val success = context.shizukuInputTap(tapX, tapY)
        val elapsedMs = System.currentTimeMillis() - clickStartTime

        // 서버 로그
        context.logger.logAcceptStep(
            step = 2,
            stepName = "DETECTED_CALL",
            targetId = if (foundBy == "view_id") ACCEPT_BUTTON_ID else foundBy,
            buttonFound = true,
            clickSuccess = success,
            elapsedMs = elapsedMs,
            callKey = context.eligibleCall?.callKey ?: ""
        )

        // Shizuku 실패 시 dispatchGesture fallback
        if (!success) {
            val gestureSuccess = context.performGestureClick(tapX.toFloat(), tapY.toFloat())
            if (!gestureSuccess) {
                resetState()
                return StateResult.Error(CallAcceptState.ERROR_UNKNOWN, "클릭 실패")
            }
        }

        resetState()
        return StateResult.Transition(CallAcceptState.WAITING_FOR_CONFIRM, "수락 버튼 클릭 성공")
    }

    /**
     * 확인 다이얼로그(수락하기 버튼)가 보이는지 확인
     */
    private fun checkConfirmDialogVisible(node: AccessibilityNodeInfo): Boolean {
        // View ID로 확인
        if (findNodeByViewId(node, CONFIRM_BUTTON_ID) != null) return true

        // "수락하기" 텍스트로 확인
        for (text in CONFIRM_TEXTS) {
            val nodes = node.findAccessibilityNodeInfosByText(text)
            for (foundNode in nodes) {
                val viewId = foundNode.viewIdResourceName ?: ""
                if (!viewId.contains("btn_call_accept")) return true
            }
        }
        return false
    }

    /**
     * 상태 초기화
     */
    private fun resetState() {
        clickedAndWaiting = false
        waitRetryCount = 0
    }

    /**
     * View ID로 노드 찾기 (재귀 탐색)
     * ViewIdParsingStrategy와 동일한 로직
     */
    private fun findNodeByViewId(rootNode: AccessibilityNodeInfo, viewId: String): AccessibilityNodeInfo? {
        // 현재 노드 확인
        if (rootNode.viewIdResourceName == viewId) {
            return rootNode
        }

        // 자식 노드 재귀 탐색
        for (i in 0 until rootNode.childCount) {
            val child = rootNode.getChild(i) ?: continue
            val result = findNodeByViewId(child, viewId)
            if (result != null) {
                return result
            }
        }

        return null
    }

    /**
     * Phase 4: 화면의 주요 텍스트 수집 (최대 10개)
     */
    private fun collectScreenTexts(node: AccessibilityNodeInfo, texts: MutableList<String>) {
        if (texts.size >= 10) return

        node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
            if (!texts.contains(it)) texts.add(it)
        }

        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectScreenTexts(it, texts) }
        }
    }
}
