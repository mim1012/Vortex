package com.example.twinme.domain.state.handlers

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.twinme.data.CallAcceptState
import com.example.twinme.domain.state.StateContext
import com.example.twinme.domain.state.StateHandler
import com.example.twinme.domain.state.StateResult

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
        private val FALLBACK_TEXTS = listOf("콜 수락", "수락", "승낙", "accept")
    }

    override val targetState: CallAcceptState = CallAcceptState.DETECTED_CALL

    override fun handle(node: AccessibilityNodeInfo, context: StateContext): StateResult {
        Log.d(TAG, "DETECTED_CALL 진입 - 화면 검증 시작")

        // ⭐ 1. 화면 전환 검증 (콜 상세 화면인지 확인)
        val hasDetailScreen = node.findAccessibilityNodeInfosByText("예약콜 상세").isNotEmpty()
                || node.findAccessibilityNodeInfosByText("예약콜").isNotEmpty()

        if (!hasDetailScreen) {
            Log.w(TAG, "⚠️ 콜 상세 화면이 아님 - 클릭 실패로 간주 → CLICKING_ITEM 복귀")

            // CLICKING_ITEM으로 복귀하여 재클릭 시도
            return StateResult.Error(
                CallAcceptState.CLICKING_ITEM,
                "화면 전환 실패 - 재클릭 필요"
            )
        }

        Log.d(TAG, "✅ 콜 상세 화면 검증 완료 - 버튼 검색 시작")

        // ⭐ 2. "이미 배차" 감지 (원본 APK 방식: 라인 601-604)
        if (node.findAccessibilityNodeInfosByText("이미 배차").isNotEmpty()) {
            Log.w(TAG, "이미 다른 기사에게 배차됨")
            return StateResult.Error(
                CallAcceptState.ERROR_ASSIGNED,
                "이미 다른 기사에게 배차됨"
            )
        }

        // 1. View ID로 버튼 검색 (우선순위 1)
        var acceptButton = context.findNode(node, ACCEPT_BUTTON_ID)
        var foundBy = "view_id"

        // 2. View ID로 못 찾으면 텍스트 기반 검색 (Fallback)
        if (acceptButton == null) {
            Log.w(TAG, "View ID로 콜 수락 버튼을 찾지 못함 - 텍스트 기반 검색 시도")

            for (text in FALLBACK_TEXTS) {
                acceptButton = context.findNodeByText(node, text)
                if (acceptButton != null) {
                    foundBy = "text:$text"
                    Log.i(TAG, "텍스트 기반으로 버튼 발견: $text")
                    break
                }
            }
        }

        if (acceptButton == null) {
            Log.d(TAG, "콜 수락 버튼을 찾지 못함 (View ID 및 텍스트 검색 모두 실패)")

            // ⭐ 서버로 버튼 검색 실패 로그 전송
            context.logger.logButtonSearchFailed(
                currentState = targetState,
                targetViewId = ACCEPT_BUTTON_ID,
                searchDepth = node.childCount,
                nodeDescription = "Searched by viewId and fallback texts: ${FALLBACK_TEXTS.joinToString()}"
            )

            return StateResult.NoChange
        }

        if (!acceptButton.isClickable) {
            Log.w(TAG, "콜 수락 버튼을 찾았으나 클릭 불가능 (검색 방법: $foundBy)")
            return StateResult.NoChange
        }

        // 3. Bounds 가져오기 및 중앙 좌표 계산 (원본 APK 방식)
        val bounds = android.graphics.Rect()
        acceptButton.getBoundsInScreen(bounds)
        val centerX = bounds.centerX().toFloat()
        val centerY = bounds.centerY().toFloat()

        // 4. 제스처 클릭 시도 (원본 APK 방식: dispatchGesture)
        Log.d(TAG, "콜 수락 버튼 클릭 시도 (검색 방법: $foundBy, 좌표: $centerX, $centerY)")
        val clickStartTime = System.currentTimeMillis()
        val success = context.performGestureClick(centerX, centerY)
        val elapsedMs = System.currentTimeMillis() - clickStartTime

        // 5. 클릭 결과 로깅 (검색 방법 포함)
        context.logger.logNodeClick(
            nodeId = if (foundBy == "view_id") ACCEPT_BUTTON_ID else foundBy,
            success = success,
            state = targetState,
            elapsedMs = elapsedMs
        )

        // 4. 결과 반환
        return if (success) {
            Log.d(TAG, "콜 수락 버튼 클릭 성공")
            StateResult.Transition(
                nextState = CallAcceptState.WAITING_FOR_CONFIRM,
                reason = "콜 수락 버튼 클릭 성공"
            )
        } else {
            Log.e(TAG, "콜 수락 버튼 클릭 실패")
            StateResult.Error(
                errorState = CallAcceptState.ERROR_UNKNOWN,
                reason = "콜 수락 버튼 클릭 실패"
            )
        }
    }
}
