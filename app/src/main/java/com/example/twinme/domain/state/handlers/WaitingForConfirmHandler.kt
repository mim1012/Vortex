package com.example.twinme.domain.state.handlers

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.twinme.data.CallAcceptState
import com.example.twinme.domain.state.StateContext
import com.example.twinme.domain.state.StateHandler
import com.example.twinme.domain.state.StateResult

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
        Log.d(TAG, "수락 확인 버튼 찾는 중...")

        // ⭐ Phase 4: 화면 상태 스냅샷 로그 추가
        val screenTexts = mutableListOf<String>()
        collectScreenTexts(node, screenTexts)
        val confirmButtonVisible = node.findAccessibilityNodeInfosByViewId(CONFIRM_BUTTON_ID).isNotEmpty()

        context.logger.logScreenCheck(
            state = CallAcceptState.WAITING_FOR_CONFIRM,
            targetButtonVisible = confirmButtonVisible,
            screenTextSummary = screenTexts.take(5).joinToString(", "),
            callKey = context.eligibleCall?.callKey ?: ""
        )

        // 사용자 수동 조작 감지 (뒤로가기)
        val hasAcceptButton = node.findAccessibilityNodeInfosByViewId(CONFIRM_BUTTON_ID).isNotEmpty()
        val hasListScreen = node.findAccessibilityNodeInfosByText("예약콜 리스트").isNotEmpty()

        if (!hasAcceptButton && hasListScreen) {
            Log.w(TAG, "사용자가 뒤로가기로 복귀 - ERROR_TIMEOUT 전환")
            return StateResult.Error(
                CallAcceptState.ERROR_TIMEOUT,
                "화면 불일치 - 뒤로가기 감지"
            )
        }

        // ⭐ 원본 APK 방식: "이미 배차" 감지 (라인 621-623)
        if (node.findAccessibilityNodeInfosByText("이미 배차").isNotEmpty()) {
            Log.w(TAG, "이미 다른 기사에게 배차됨")
            return StateResult.Error(
                CallAcceptState.ERROR_ASSIGNED,
                "이미 다른 기사에게 배차됨"
            )
        }

        // ⭐⭐ Phase 4: 버튼 탐색 시작 로그
        Log.d(TAG, "6️⃣ 🔍 [버튼 탐색 시작] state=WAITING_FOR_CONFIRM, target=$CONFIRM_BUTTON_ID")

        // 1. View ID로 버튼 검색 (우선순위 1)
        var confirmButton = context.findNode(node, CONFIRM_BUTTON_ID)
        var foundBy = "view_id"
        var searchMethod = "VIEW_ID"

        if (confirmButton != null) {
            // ⭐ Phase 4: ViewID HIT 로그
            Log.d(TAG, "6️⃣ ✅ [ViewID HIT] $CONFIRM_BUTTON_ID")
        } else {
            // ⭐ Phase 4: ViewID MISS 로그
            Log.w(TAG, "6️⃣ ❌ [ViewID MISS] $CONFIRM_BUTTON_ID → fallback")

            // 2. 텍스트 기반 검색 (Fallback)
            Log.d(TAG, "6️⃣ 🔍 [TEXT SEARCH] keywords=${FALLBACK_TEXTS.joinToString(",")} → searching...")

            for (text in FALLBACK_TEXTS) {
                confirmButton = context.findNodeByText(node, text)
                if (confirmButton != null) {
                    foundBy = "text:$text"
                    searchMethod = "TEXT"
                    Log.i(TAG, "6️⃣ ✅ [TEXT FOUND] $text")
                    break
                }
            }

            if (confirmButton == null) {
                Log.w(TAG, "6️⃣ ❌ [TEXT MISS] 모든 fallback 텍스트 검색 실패")
            }
        }

        if (confirmButton == null) {
            Log.d(TAG, "수락 확인 버튼을 찾지 못함 (View ID 및 텍스트 검색 모두 실패)")

            // ⭐ 서버로 버튼 검색 실패 로그 전송
            context.logger.logButtonSearchFailed(
                currentState = targetState,
                targetViewId = CONFIRM_BUTTON_ID,
                searchDepth = node.childCount,
                nodeDescription = "Searched by viewId and fallback texts: ${FALLBACK_TEXTS.joinToString()}"
            )

            return StateResult.NoChange
        }

        // ⭐ btn_call_accept 버튼이 보이면 다이얼로그가 아직 안 나타남 → 재클릭 시도
        val buttonId = confirmButton.viewIdResourceName ?: ""
        if (buttonId.contains("btn_call_accept")) {
            Log.w(TAG, "⚠️ btn_call_accept 발견 - 확인 다이얼로그 미출현 → 재클릭 시도")

            // btn_call_accept 다시 클릭
            val bounds = android.graphics.Rect()
            confirmButton.getBoundsInScreen(bounds)
            val centerX = bounds.centerX().toFloat()
            val centerY = bounds.centerY().toFloat()

            val reClickSuccess = context.performGestureClick(centerX, centerY)
            Log.d(TAG, "btn_call_accept 재클릭 결과: $reClickSuccess (좌표: $centerX, $centerY)")

            return StateResult.NoChange  // 다음 사이클에서 다이얼로그 확인
        }

        if (!confirmButton.isClickable) {
            Log.w(TAG, "수락 확인 버튼을 찾았으나 클릭 불가능 (검색 방법: $foundBy)")
            return StateResult.NoChange
        }

        // 3. Bounds 가져오기 및 중앙 좌표 계산
        val bounds = android.graphics.Rect()
        confirmButton.getBoundsInScreen(bounds)
        val centerX = bounds.centerX().toFloat()
        val centerY = bounds.centerY().toFloat()

        // ⭐⭐ Phase 4: 최종 버튼 결정 로그
        val nodeDesc = "Button{id=${confirmButton.viewIdResourceName}, text=${confirmButton.text}, clickable=${confirmButton.isClickable}, bounds=$bounds}"
        Log.i(TAG, "6️⃣ 🎯 [버튼 결정] method=$searchMethod, node=$nodeDesc")

        // 4. 클릭 시도 - 제스처 클릭 우선 (performAction이 작동 안 하는 경우 대비)
        Log.d(TAG, "수락 확인 버튼 클릭 시도 (검색 방법: $foundBy, 좌표: $centerX, $centerY)")
        val clickStartTime = System.currentTimeMillis()

        // 4-1. 제스처 클릭 먼저 시도 (좌표 기반, 더 확실함)
        var success = context.performGestureClick(centerX, centerY)
        var clickMethod = "dispatchGesture"

        if (success) {
            Log.d(TAG, "✅ 제스처 클릭 전송됨")
        } else {
            // 4-2. 실패 시 performAction 시도
            Log.w(TAG, "제스처 클릭 실패 → performAction 시도")
            success = confirmButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            clickMethod = "performAction"
            if (success) {
                Log.d(TAG, "✅ performAction 클릭 성공")
            }
        }

        Log.d(TAG, "클릭 방법: $clickMethod, 결과: $success")
        val elapsedMs = System.currentTimeMillis() - clickStartTime

        // 5. 클릭 결과 로깅 (검색 방법 포함)
        // ⭐ Phase 4: logNodeClick → logAcceptStep으로 변경, callKey 추가
        context.logger.logAcceptStep(
            step = 3,
            stepName = "WAITING_FOR_CONFIRM",  // LocalLogger가 인식할 수 있는 이름
            targetId = if (foundBy == "view_id") CONFIRM_BUTTON_ID else foundBy,
            buttonFound = true,
            clickSuccess = success,
            elapsedMs = elapsedMs,
            callKey = context.eligibleCall?.callKey ?: ""
        )

        // 4. 결과 반환
        return if (success) {
            Log.d(TAG, "수락 확인 버튼 클릭 성공")
            StateResult.Transition(
                nextState = CallAcceptState.CALL_ACCEPTED,
                reason = "수락 확인 버튼 클릭 성공"
            )
        } else {
            Log.e(TAG, "수락 확인 버튼 클릭 실패")
            StateResult.Error(
                errorState = CallAcceptState.ERROR_UNKNOWN,
                reason = "수락 확인 버튼 클릭 실패"
            )
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
