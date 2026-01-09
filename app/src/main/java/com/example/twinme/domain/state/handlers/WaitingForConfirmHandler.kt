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
 * WAITING_FOR_CONFIRM 상태 핸들러
 * 수락 확인 버튼을 찾아서 클릭 시도
 *
 * 동작 과정:
 * 1. View ID로 btn_positive 버튼 검색 (우선순위 1)
 * 2. 실패 시 텍스트 기반으로 "수락하기" 버튼 검색 (Fallback)
 * 3. 버튼 클릭
 * 4. 성공 시 CALL_ACCEPTED로 전환
 */
class WaitingForConfirmHandler : StateHandler {
    companion object {
        private const val TAG = "WaitingForConfirmHandler"
        private const val CONFIRM_BUTTON_ID = "com.kakao.taxi.driver:id/btn_positive"
        private val FALLBACK_TEXTS = listOf("수락하기", "확인", "수락", "OK", "예", "Yes")
    }

    override val targetState: CallAcceptState = CallAcceptState.WAITING_FOR_CONFIRM

    override fun handle(node: AccessibilityNodeInfo, context: StateContext): StateResult {
        val confirmButtonVisible = node.findAccessibilityNodeInfosByViewId(CONFIRM_BUTTON_ID).isNotEmpty()
        val hasListScreen = node.findAccessibilityNodeInfosByText("예약콜 리스트").isNotEmpty()

        // 화면 상태 로그
        context.logger.logScreenCheck(
            state = CallAcceptState.WAITING_FOR_CONFIRM,
            targetButtonVisible = confirmButtonVisible,
            screenTextSummary = "",
            callKey = context.eligibleCall?.callKey ?: ""
        )

        // 뒤로가기 감지
        if (!confirmButtonVisible && hasListScreen) {
            return StateResult.Error(CallAcceptState.ERROR_TIMEOUT, "뒤로가기 감지")
        }

        // "이미 배차" 감지
        if (node.findAccessibilityNodeInfosByText("이미 배차").isNotEmpty()) {
            return StateResult.Error(CallAcceptState.ERROR_ASSIGNED, "이미 배차됨")
        }

        // 버튼 검색 (View ID → 텍스트)
        var confirmButton = context.findNode(node, CONFIRM_BUTTON_ID)
        var foundBy = "view_id"

        if (confirmButton == null) {
            for (text in FALLBACK_TEXTS) {
                confirmButton = context.findNodeByText(node, text)
                if (confirmButton != null) {
                    foundBy = "text:$text"
                    break
                }
            }
        }

        if (confirmButton == null) {
            context.logger.logButtonSearchFailed(
                currentState = targetState,
                targetViewId = CONFIRM_BUTTON_ID,
                searchDepth = node.childCount,
                nodeDescription = "view_id + fallback"
            )
            return StateResult.NoChange
        }

        // d4 쓰로틀 회피: btn_call_accept면 대기
        val buttonId = confirmButton.viewIdResourceName ?: ""
        if (buttonId.contains("btn_call_accept")) {
            return StateResult.NoChange
        }

        if (!confirmButton.isClickable) return StateResult.NoChange

        // 클릭 실행
        val clickStartTime = System.currentTimeMillis()
        Thread.sleep((50 + Random().nextInt(100)).toLong())
        confirmButton.refresh()

        var success = confirmButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (!success) {
            confirmButton.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            Thread.sleep(50)
            success = confirmButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }

        val elapsedMs = System.currentTimeMillis() - clickStartTime

        // 서버 로그
        context.logger.logAcceptStep(
            step = 3,
            stepName = "WAITING_FOR_CONFIRM",
            targetId = if (foundBy == "view_id") CONFIRM_BUTTON_ID else foundBy,
            buttonFound = true,
            clickSuccess = success,
            elapsedMs = elapsedMs,
            callKey = context.eligibleCall?.callKey ?: ""
        )

        return if (success) {
            StateResult.Transition(CallAcceptState.CALL_ACCEPTED, "확인 버튼 클릭 성공")
        } else {
            StateResult.Error(CallAcceptState.ERROR_UNKNOWN, "확인 버튼 클릭 실패")
        }
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
