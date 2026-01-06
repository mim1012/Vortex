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
        Log.d(TAG, "DETECTED_CALL 진입 - 화면 검증 시작 (clickedAndWaiting=$clickedAndWaiting, waitRetry=$waitRetryCount)")

        // ⭐ 0. 확인 다이얼로그가 이미 떠 있는지 먼저 확인
        val hasConfirmDialog = checkConfirmDialogVisible(node)
        if (hasConfirmDialog) {
            Log.i(TAG, "✅ 확인 다이얼로그 감지됨 (수락하기 버튼) → WAITING_FOR_CONFIRM 전환")
            resetState()
            return StateResult.Transition(
                nextState = CallAcceptState.WAITING_FOR_CONFIRM,
                reason = "확인 다이얼로그 감지됨"
            )
        }

        // ⭐ 0-1. 클릭 후 대기 중인데 다이얼로그가 안 나타난 경우
        if (clickedAndWaiting) {
            waitRetryCount++
            Log.d(TAG, "🔄 다이얼로그 대기 중... ($waitRetryCount/$MAX_CLICK_RETRY)")

            if (waitRetryCount >= MAX_CLICK_RETRY) {
                Log.w(TAG, "⚠️ 다이얼로그 대기 시간 초과 - 재클릭 시도")
                resetState()
                // 다시 클릭 시도하도록 아래로 진행
            } else {
                // 아직 대기 중 - 다음 루프에서 다시 확인
                return StateResult.NoChange
            }
        }

        // ⭐ 0-2. "예약콜 리스트" 텍스트가 있으면 아직 리스트 화면 (화면 전환 안 됨)
        val hasListScreen = node.findAccessibilityNodeInfosByText("예약콜 리스트").isNotEmpty()
        if (hasListScreen) {
            Log.w(TAG, "⚠️ 아직 '예약콜 리스트' 화면 - 화면 전환 안 됨 → CLICKING_ITEM 복귀")
            resetState()
            return StateResult.Error(
                CallAcceptState.CLICKING_ITEM,
                "화면 전환 안 됨 - 재클릭 필요 (still on list screen)"
            )
        }

        // ⭐ 1. 화면 전환 검증 (2단계 Fallback: View ID → 텍스트)
        var hasDetailScreen = false
        var detectionMethod = ""

        // 1-1. View ID 기반 검증 (우선순위 1)
        val hasBtnCallAccept = findNodeByViewId(node, ACCEPT_BUTTON_ID) != null
        val hasMapView = findNodeByViewId(node, MAP_VIEW_ID) != null
        val hasCloseButton = findNodeByViewId(node, CLOSE_BUTTON_ID) != null

        if (hasBtnCallAccept || hasMapView || hasCloseButton) {
            hasDetailScreen = true
            detectionMethod = when {
                hasBtnCallAccept -> "view_id:btn_call_accept"
                hasMapView -> "view_id:map_view"
                else -> "view_id:action_close"
            }
            Log.d(TAG, "✅ View ID로 상세 화면 감지 성공 ($detectionMethod)")
        }

        // 1-2. 텍스트 기반 검증 (Fallback, 우선순위 2) - "예약콜 상세" 텍스트만 허용
        if (!hasDetailScreen) {
            Log.d(TAG, "View ID 검증 실패 - 텍스트 기반 검증 시도")

            // "예약콜 상세" 텍스트가 있어야 상세 화면
            if (node.findAccessibilityNodeInfosByText("예약콜 상세").isNotEmpty()) {
                hasDetailScreen = true
                detectionMethod = "text:예약콜 상세"
                Log.d(TAG, "✅ 텍스트로 상세 화면 감지 성공 ($detectionMethod)")
            }
        }

        // 1-3. 모든 검증 실패
        if (!hasDetailScreen) {
            Log.w(TAG, "⚠️ 콜 상세 화면이 아님 (View ID 및 텍스트 검증 모두 실패) - 클릭 실패로 간주 → CLICKING_ITEM 복귀")

            // CLICKING_ITEM으로 복귀하여 재클릭 시도
            return StateResult.Error(
                CallAcceptState.CLICKING_ITEM,
                "화면 전환 실패 - 재클릭 필요 (detection failed)"
            )
        }

        Log.d(TAG, "✅ 콜 상세 화면 검증 완료 (method: $detectionMethod) - 즉시 버튼 검색")

        // ⭐ Phase 4: 화면 상태 스냅샷 로그 추가
        val screenTexts = mutableListOf<String>()
        collectScreenTexts(node, screenTexts)
        val acceptButtonVisible = findNodeByViewId(node, ACCEPT_BUTTON_ID) != null

        context.logger.logScreenCheck(
            state = CallAcceptState.DETECTED_CALL,
            targetButtonVisible = acceptButtonVisible,
            screenTextSummary = screenTexts.take(5).joinToString(", "),
            callKey = context.eligibleCall?.callKey ?: ""
        )

        // ⭐ 2. "이미 배차" 감지 (원본 APK 방식: 라인 601-604)
        if (node.findAccessibilityNodeInfosByText("이미 배차").isNotEmpty()) {
            Log.w(TAG, "이미 다른 기사에게 배차됨")
            return StateResult.Error(
                CallAcceptState.ERROR_ASSIGNED,
                "이미 다른 기사에게 배차됨"
            )
        }

        // ⭐⭐ MediaEnhanced 방식: findAccessibilityNodeInfosByViewId 사용!
        Log.d(TAG, "5️⃣ 🔍 [버튼 탐색 시작] state=DETECTED_CALL, ViewID 우선 검색 (MediaEnhanced 방식)")

        var acceptButton: AccessibilityNodeInfo? = null
        var foundBy = ""
        var searchMethod = ""

        // 1. MediaEnhanced 방식: findAccessibilityNodeInfosByViewId 사용!
        Log.d(TAG, "5️⃣ 🔍 [ViewID SEARCH] $ACCEPT_BUTTON_ID → searching...")
        val viewIdNodes = node.findAccessibilityNodeInfosByViewId(ACCEPT_BUTTON_ID)
        Log.d(TAG, "5️⃣ 📋 findAccessibilityNodeInfosByViewId 결과: ${viewIdNodes.size}개 노드")

        for (foundNode in viewIdNodes) {
            Log.d(TAG, "  - node: clickable=${foundNode.isClickable}, enabled=${foundNode.isEnabled}, visible=${foundNode.isVisibleToUser}, class=${foundNode.className}")
            if (foundNode.isClickable && foundNode.isEnabled) {
                acceptButton = foundNode
                foundBy = "view_id"
                searchMethod = "VIEW_ID"
                Log.i(TAG, "5️⃣ ✅ [ViewID FOUND] $ACCEPT_BUTTON_ID - clickable & enabled")
                break
            }
        }

        // 2. ViewID로 못 찾으면 텍스트로 시도 (Fallback)
        if (acceptButton == null) {
            Log.w(TAG, "5️⃣ ❌ [ViewID MISS] → TEXT fallback")
            Log.d(TAG, "5️⃣ 🔍 [TEXT SEARCH] keywords=${FALLBACK_TEXTS.joinToString(",")} → searching...")

            for (text in FALLBACK_TEXTS) {
                val nodes = node.findAccessibilityNodeInfosByText(text)
                for (foundNode in nodes) {
                    if (foundNode.isClickable && foundNode.isEnabled) {
                        acceptButton = foundNode
                        foundBy = "text:$text"
                        searchMethod = "TEXT"
                        Log.i(TAG, "5️⃣ ✅ [TEXT FOUND] '$text' - clickable & enabled")
                        break
                    }
                }
                if (acceptButton != null) break
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

        // 3. Bounds 가져오기 및 중앙 좌표 계산
        val bounds = android.graphics.Rect()
        acceptButton.getBoundsInScreen(bounds)
        val centerX = bounds.centerX()
        val centerY = bounds.centerY()

        // ⭐⭐ Phase 4: 최종 버튼 결정 로그 + 상세 검증
        val nodeDesc = "Button{id=${acceptButton.viewIdResourceName}, text=${acceptButton.text}, clickable=${acceptButton.isClickable}, bounds=$bounds}"
        Log.i(TAG, "5️⃣ 🎯 [버튼 결정] method=$searchMethod, node=$nodeDesc")

        // ⭐ 버튼 상태 완전 검증
        Log.d(TAG, "🔍 버튼 상세 정보:")
        Log.d(TAG, "  - viewId: ${acceptButton.viewIdResourceName}")
        Log.d(TAG, "  - text: ${acceptButton.text}")
        Log.d(TAG, "  - className: ${acceptButton.className}")
        Log.d(TAG, "  - isClickable: ${acceptButton.isClickable}")
        Log.d(TAG, "  - isEnabled: ${acceptButton.isEnabled}")
        Log.d(TAG, "  - isVisibleToUser: ${acceptButton.isVisibleToUser}")
        Log.d(TAG, "  - isFocusable: ${acceptButton.isFocusable}")
        Log.d(TAG, "  - bounds: $bounds")

        // 부모 노드도 확인
        val parent = acceptButton.parent
        if (parent != null) {
            Log.d(TAG, "  - parent.className: ${parent.className}")
            Log.d(TAG, "  - parent.isEnabled: ${parent.isEnabled}")
            Log.d(TAG, "  - parent.isVisibleToUser: ${parent.isVisibleToUser}")
        }

        // ⭐⭐⭐ 4. 좌표 보정 (화면 밖 bounds 처리)
        // bounds가 화면 밖이면 수동 테스트 좌표 (540, 2080) 사용
        val tapX: Int
        val tapY: Int

        if (centerX > context.screenWidth || centerX < 0) {
            // 화면 밖 좌표 → 수동 테스트 좌표 사용
            tapX = 540
            tapY = 2080
            Log.w(TAG, "⚠️ [좌표 보정] bounds($centerX, $centerY) 화면 밖 → (540, 2080) 사용")
        } else {
            tapX = centerX
            tapY = centerY
            Log.d(TAG, "✅ [좌표] bounds 정상: ($tapX, $tapY)")
        }

        val clickStartTime = System.currentTimeMillis()

        // ⭐⭐⭐ 5. Shizuku input tap으로 버튼 클릭 (봇 탐지 우회)
        Log.i(TAG, "🚀 [Shizuku] btn_call_accept 클릭 시도: ($tapX, $tapY)")

        // 인간적 랜덤 지연 (50-150ms)
        val randomDelay = 50L + Random().nextInt(100)
        Thread.sleep(randomDelay)
        Log.d(TAG, "🎲 랜덤 지연: ${randomDelay}ms")

        val success = context.shizukuInputTap(tapX, tapY)
        val elapsedMs = System.currentTimeMillis() - clickStartTime

        Log.d(TAG, "🚀 [Shizuku] input tap 결과: $success (${elapsedMs}ms)")

        // 6. 클릭 결과 로깅
        context.logger.logAcceptStep(
            step = 2,
            stepName = "DETECTED_CALL",
            targetId = if (foundBy == "view_id") ACCEPT_BUTTON_ID else foundBy,
            buttonFound = true,
            clickSuccess = success,
            elapsedMs = elapsedMs,
            callKey = context.eligibleCall?.callKey ?: ""
        )

        // 7. 결과에 따른 상태 전환
        if (!success) {
            Log.e(TAG, "❌ Shizuku input tap 실패 → dispatchGesture fallback")
            // Fallback: dispatchGesture
            val gestureSuccess = context.performGestureClick(tapX.toFloat(), tapY.toFloat())
            Log.d(TAG, "🖱️ dispatchGesture fallback 결과: $gestureSuccess")

            if (!gestureSuccess) {
                Log.e(TAG, "❌ 콜 수락 버튼 클릭 최종 실패")
                resetState()
                return StateResult.Error(
                    errorState = CallAcceptState.ERROR_UNKNOWN,
                    reason = "콜 수락 버튼 클릭 실패 (Shizuku + dispatchGesture 모두 실패)"
                )
            }
        }

        // ⭐ 성공 시 즉시 다음 상태로 전이 (WAITING_FOR_CONFIRM)
        Log.i(TAG, "✅ 콜 수락 버튼 클릭 성공 → WAITING_FOR_CONFIRM 전환")
        resetState()
        return StateResult.Transition(
            nextState = CallAcceptState.WAITING_FOR_CONFIRM,
            reason = "Shizuku input tap 성공"
        )
    }

    /**
     * 확인 다이얼로그(수락하기 버튼)가 보이는지 확인
     */
    private fun checkConfirmDialogVisible(node: AccessibilityNodeInfo): Boolean {
        // 1. View ID로 확인
        if (findNodeByViewId(node, CONFIRM_BUTTON_ID) != null) {
            Log.d(TAG, "✅ btn_positive View ID로 다이얼로그 감지")
            return true
        }

        // 2. "수락하기" 텍스트로 확인
        for (text in CONFIRM_TEXTS) {
            val nodes = node.findAccessibilityNodeInfosByText(text)
            if (nodes.isNotEmpty()) {
                // btn_call_accept이 아닌지 확인
                for (foundNode in nodes) {
                    val viewId = foundNode.viewIdResourceName ?: ""
                    if (!viewId.contains("btn_call_accept")) {
                        Log.d(TAG, "✅ '$text' 텍스트로 다이얼로그 감지 (viewId=$viewId)")
                        return true
                    }
                }
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
