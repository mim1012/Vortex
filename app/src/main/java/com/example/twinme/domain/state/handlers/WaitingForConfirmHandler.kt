package com.example.twinme.domain.state.handlers

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.twinme.data.CallAcceptState
import com.example.twinme.domain.state.StateContext
import com.example.twinme.domain.state.StateHandler
import com.example.twinme.domain.state.StateResult
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
        private const val CONDITION_TAG = "CONDITION"  // ADB 필터용
        private const val CONFIRM_BUTTON_ID = "com.kakao.taxi.driver:id/btn_positive"
        private val FALLBACK_TEXTS = listOf("수락하기", "확인", "수락", "OK", "예", "Yes")
        private const val MAX_CLICK_RETRY = 5  // 최대 클릭 재시도 횟수
    }

    // 클릭 후 다이얼로그 대기 상태 추적
    private var clickedAndWaiting = false
    private var waitRetryCount = 0

    override val targetState: CallAcceptState = CallAcceptState.WAITING_FOR_CONFIRM

    override fun handle(node: AccessibilityNodeInfo, context: StateContext): StateResult {
        // ⭐ 클릭 후 대기 중 처리
        if (clickedAndWaiting) {
            // 1. "이미 배차" 감지 → 다이얼로그 확인 버튼 클릭 (알림 X)
            if (node.findAccessibilityNodeInfosByText("이미 배차").isNotEmpty()) {
                Log.i(CONDITION_TAG, "❌ 이미 배차 → LIST_DETECTED 복귀 (알림X, eligibleCall 초기화)")
                com.example.twinme.logging.RemoteLogger.logError(
                    errorType = "DIALOG_ASSIGNED",
                    message = "이미 배차 다이얼로그 감지 (클릭 후 대기 중)",
                    stackTrace = "callKey: ${context.eligibleCall?.callKey}"
                )
                if (clickDialogConfirmButton(node, context)) {
                    resetState()
                    context.eligibleCall = null
                    // 다이얼로그 닫힌 후 리스트로 복귀 (알림 X)
                    return StateResult.Transition(CallAcceptState.LIST_DETECTED, "이미 배차 다이얼로그 닫음")
                }
                // 버튼 못 찾으면 계속 대기 (재시도)
                return StateResult.NoChange
            }

            // 2. "콜이 취소되었습니다" 감지 → 다이얼로그 확인 버튼 클릭 (알림 X)
            if (node.findAccessibilityNodeInfosByText("콜이 취소되었습니다").isNotEmpty()) {
                Log.i(CONDITION_TAG, "❌ 콜 취소됨 → LIST_DETECTED 복귀 (알림X, eligibleCall 초기화)")
                com.example.twinme.logging.RemoteLogger.logError(
                    errorType = "DIALOG_CANCELLED",
                    message = "콜 취소 다이얼로그 감지 (클릭 후 대기 중)",
                    stackTrace = "callKey: ${context.eligibleCall?.callKey}"
                )
                if (clickDialogConfirmButton(node, context)) {
                    resetState()
                    context.eligibleCall = null
                    // 다이얼로그 닫힌 후 리스트로 복귀 (알림 X)
                    return StateResult.Transition(CallAcceptState.LIST_DETECTED, "콜 취소 다이얼로그 닫음")
                }
                // 버튼 못 찾으면 계속 대기 (재시도)
                return StateResult.NoChange
            }

            // 3. 재시도 카운트 증가
            waitRetryCount++
            Log.i(TAG, "⏳ [CONFIRM] 응답 대기 중 ($waitRetryCount/$MAX_CLICK_RETRY) - 에러 다이얼로그 없음")

            // 4. ⭐ 에러 다이얼로그 없음 = 정상 수락! → CALL_ACCEPTED (알림 O, pause)
            if (waitRetryCount >= MAX_CLICK_RETRY) {
                Log.i(CONDITION_TAG, "✅ 수락 완료! → CALL_ACCEPTED (알림O, pause, callKey=${context.eligibleCall?.callKey})")
                resetState()
                return StateResult.Transition(CallAcceptState.CALL_ACCEPTED, "콜 수락 완료")
            }

            // 5. 계속 대기 (에러 다이얼로그 체크 중)
            return StateResult.NoChange
        }

        // ========== 여기부터는 클릭 전 로직 ==========
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

        // "이미 배차" 감지 (클릭 전) → 다이얼로그 확인 버튼 클릭
        if (node.findAccessibilityNodeInfosByText("이미 배차").isNotEmpty()) {
            Log.d(TAG, "이미 배차 다이얼로그 감지 (클릭 전) - 확인 버튼 클릭 시도")
            com.example.twinme.logging.RemoteLogger.logError(
                errorType = "DIALOG_ASSIGNED",
                message = "이미 배차 다이얼로그 감지 (클릭 전)",
                stackTrace = "callKey: ${context.eligibleCall?.callKey}"
            )
            if (clickDialogConfirmButton(node, context)) {
                context.eligibleCall = null
                com.example.twinme.logging.RemoteLogger.logError(
                    errorType = "DIALOG_ASSIGNED_CLOSED",
                    message = "이미 배차 다이얼로그 확인 버튼 클릭 (클릭 전) → LIST_DETECTED 복귀",
                    stackTrace = ""
                )
                return StateResult.Transition(CallAcceptState.LIST_DETECTED, "이미 배차 다이얼로그 닫음")
            }
            // 버튼 못 찾으면 에러 처리
            context.eligibleCall = null
            return StateResult.Error(CallAcceptState.ERROR_ASSIGNED, "이미 배차됨")
        }

        // "콜이 취소되었습니다" 감지 (클릭 전) → 다이얼로그 확인 버튼 클릭
        if (node.findAccessibilityNodeInfosByText("콜이 취소되었습니다").isNotEmpty()) {
            Log.d(TAG, "콜 취소 다이얼로그 감지 (클릭 전) - 확인 버튼 클릭 시도")
            com.example.twinme.logging.RemoteLogger.logError(
                errorType = "DIALOG_CANCELLED",
                message = "콜 취소 다이얼로그 감지 (클릭 전)",
                stackTrace = "callKey: ${context.eligibleCall?.callKey}"
            )
            if (clickDialogConfirmButton(node, context)) {
                context.eligibleCall = null
                com.example.twinme.logging.RemoteLogger.logError(
                    errorType = "DIALOG_CANCELLED_CLOSED",
                    message = "콜 취소 다이얼로그 확인 버튼 클릭 (클릭 전) → LIST_DETECTED 복귀",
                    stackTrace = ""
                )
                return StateResult.Transition(CallAcceptState.LIST_DETECTED, "콜 취소 다이얼로그 닫음")
            }
            // 버튼 못 찾으면 에러 처리
            context.eligibleCall = null
            return StateResult.Error(CallAcceptState.ERROR_ASSIGNED, "콜이 취소됨")
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
        confirmButton.refresh()

        var success = confirmButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (!success) {
            confirmButton.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
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

        if (!success) {
            resetState()
            return StateResult.Error(CallAcceptState.ERROR_UNKNOWN, "확인 버튼 클릭 실패")
        }

        // ⭐ 클릭 후 즉시 전환하지 말고 대기 상태로
        // 다음 handle()에서 "이미 배차" / "콜이 취소됨" 체크
        clickedAndWaiting = true
        waitRetryCount = 0
        Log.i(TAG, "🔘 [CONFIRM] btn_positive 클릭 완료 - 응답 대기 시작 (callKey: ${context.eligibleCall?.callKey})")
        return StateResult.NoChange
    }

    /**
     * 상태 초기화
     */
    private fun resetState() {
        clickedAndWaiting = false
        waitRetryCount = 0
    }

    /**
     * "이미 배차" / "콜이 취소되었습니다" 다이얼로그의 확인 버튼 클릭
     * @return true if clicked successfully
     */
    private fun clickDialogConfirmButton(node: AccessibilityNodeInfo, context: StateContext): Boolean {
        // 1. android:id/button1 (안드로이드 기본 확인 버튼)
        var confirmButton = context.findNode(node, "android:id/button1")

        // 2. 카카오택시 btn_positive
        if (confirmButton == null) {
            confirmButton = context.findNode(node, CONFIRM_BUTTON_ID)
        }

        // 3. 텍스트로 찾기 ("확인", "닫기", "OK")
        if (confirmButton == null) {
            val dialogTexts = listOf("확인", "닫기", "OK")
            for (text in dialogTexts) {
                confirmButton = context.findNodeByText(node, text)
                if (confirmButton != null && confirmButton.isClickable) {
                    break
                }
            }
        }

        if (confirmButton == null) {
            Log.w(TAG, "다이얼로그 확인 버튼을 찾을 수 없음")
            return false
        }

        // 버튼 클릭 (performAction 사용)
        val success = confirmButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (!success) {
            // Fallback: FOCUS + CLICK
            confirmButton.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            return confirmButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }

        Log.d(TAG, "다이얼로그 확인 버튼 클릭 성공")
        return true
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
