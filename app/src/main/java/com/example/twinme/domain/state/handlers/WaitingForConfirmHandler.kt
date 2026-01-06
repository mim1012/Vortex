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

        // 1. View ID로 버튼 검색 (우선순위 1)
        var confirmButton = context.findNode(node, CONFIRM_BUTTON_ID)
        var foundBy = "view_id"

        // 2. View ID로 못 찾으면 텍스트 기반 검색 (Fallback)
        if (confirmButton == null) {
            Log.w(TAG, "View ID로 수락 확인 버튼을 찾지 못함 - 텍스트 기반 검색 시도")

            for (text in FALLBACK_TEXTS) {
                confirmButton = context.findNodeByText(node, text)
                if (confirmButton != null) {
                    foundBy = "text:$text"
                    Log.i(TAG, "텍스트 기반으로 버튼 발견: $text")
                    break
                }
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

        if (!confirmButton.isClickable) {
            Log.w(TAG, "수락 확인 버튼을 찾았으나 클릭 불가능 (검색 방법: $foundBy)")
            return StateResult.NoChange
        }

        // 3. Bounds 가져오기 및 중앙 좌표 계산 (원본 APK 방식)
        val bounds = android.graphics.Rect()
        confirmButton.getBoundsInScreen(bounds)
        val centerX = bounds.centerX().toFloat()
        val centerY = bounds.centerY().toFloat()

        // 4. 제스처 클릭 시도 (원본 APK 방식: dispatchGesture)
        Log.d(TAG, "수락 확인 버튼 클릭 시도 (검색 방법: $foundBy, 좌표: $centerX, $centerY)")
        val clickStartTime = System.currentTimeMillis()
        val success = context.performGestureClick(centerX, centerY)
        val elapsedMs = System.currentTimeMillis() - clickStartTime

        // 5. 클릭 결과 로깅 (검색 방법 포함)
        context.logger.logNodeClick(
            nodeId = if (foundBy == "view_id") CONFIRM_BUTTON_ID else foundBy,
            success = success,
            state = targetState,
            elapsedMs = elapsedMs
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
}
