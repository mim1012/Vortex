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
        private const val CONFIRM_BUTTON_ID = "com.kakao.taxi.driver:id/btn_positive"  // 확인 다이얼로그 버튼
        private const val MAP_VIEW_ID = "com.kakao.taxi.driver:id/map_view"  // 상세 화면 지도 뷰
        private const val CLOSE_BUTTON_ID = "com.kakao.taxi.driver:id/action_close"  // 상세 화면 닫기 버튼
        private val FALLBACK_TEXTS = listOf("콜 수락")  // 무조건 "콜 수락"만 사용
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

        Log.d(TAG, "✅ 콜 상세 화면 검증 완료 (method: $detectionMethod) - 300ms 딜레이 후 버튼 검색")

        // ⭐ 화면 전환 후 UI 로딩 대기 (300ms)
        try {
            Thread.sleep(300)
        } catch (e: InterruptedException) {
            // ignore
        }

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

        // ⭐⭐ Phase 4: 버튼 탐색 시작 로그
        Log.d(TAG, "5️⃣ 🔍 [버튼 탐색 시작] state=DETECTED_CALL, target=$ACCEPT_BUTTON_ID")

        // 1. View ID로 버튼 검색 (우선순위 1)
        var acceptButton = context.findNode(node, ACCEPT_BUTTON_ID)
        var foundBy = "view_id"
        var searchMethod = "VIEW_ID"

        if (acceptButton != null) {
            // ⭐ Phase 4: ViewID HIT 로그
            Log.d(TAG, "5️⃣ ✅ [ViewID HIT] $ACCEPT_BUTTON_ID")
        } else {
            // ⭐ Phase 4: ViewID MISS 로그
            Log.w(TAG, "5️⃣ ❌ [ViewID MISS] $ACCEPT_BUTTON_ID → fallback")

            // 2. 텍스트 기반 검색 (Fallback)
            Log.d(TAG, "5️⃣ 🔍 [TEXT SEARCH] keywords=${FALLBACK_TEXTS.joinToString(",")} → searching...")

            for (text in FALLBACK_TEXTS) {
                acceptButton = context.findNodeByText(node, text)
                if (acceptButton != null) {
                    foundBy = "text:$text"
                    searchMethod = "TEXT"
                    Log.i(TAG, "5️⃣ ✅ [TEXT FOUND] $text")
                    break
                }
            }

            if (acceptButton == null) {
                Log.w(TAG, "5️⃣ ❌ [TEXT MISS] 모든 fallback 텍스트 검색 실패")
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
        val centerX = bounds.centerX().toFloat()
        val centerY = bounds.centerY().toFloat()

        // ⭐⭐ Phase 4: 최종 버튼 결정 로그
        val nodeDesc = "Button{id=${acceptButton.viewIdResourceName}, text=${acceptButton.text}, clickable=${acceptButton.isClickable}, bounds=$bounds}"
        Log.i(TAG, "5️⃣ 🎯 [버튼 결정] method=$searchMethod, node=$nodeDesc")

        // 4. 클릭 시도 - 제스처 클릭 우선 (performAction이 작동 안 하는 경우 대비)
        Log.d(TAG, "콜 수락 버튼 클릭 시도 (검색 방법: $foundBy, 좌표: $centerX, $centerY)")
        val clickStartTime = System.currentTimeMillis()

        // 4-1. 제스처 클릭 먼저 시도 (좌표 기반, 더 확실함)
        var success = context.performGestureClick(centerX, centerY)
        var clickMethod = "dispatchGesture"

        if (success) {
            Log.d(TAG, "✅ 제스처 클릭 전송됨")
        } else {
            // 4-2. 실패 시 performAction 시도
            Log.w(TAG, "제스처 클릭 실패 → performAction 시도")
            success = acceptButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
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
            step = 2,
            stepName = "DETECTED_CALL",  // LocalLogger가 인식할 수 있는 이름
            targetId = if (foundBy == "view_id") ACCEPT_BUTTON_ID else foundBy,
            buttonFound = true,
            clickSuccess = success,
            elapsedMs = elapsedMs,
            callKey = context.eligibleCall?.callKey ?: ""
        )

        // 4. 결과 반환 - 클릭 후 다이얼로그 대기
        return if (success) {
            Log.d(TAG, "콜 수락 버튼 클릭 성공 - 다이얼로그 대기 시작")

            // 클릭 후 즉시 다이얼로그 확인
            Thread.sleep(300)  // 다이얼로그 출현 대기

            // 다이얼로그가 바로 나타났는지 확인
            val dialogAppeared = checkConfirmDialogVisible(node)
            if (dialogAppeared) {
                Log.i(TAG, "✅ 다이얼로그 즉시 감지 → WAITING_FOR_CONFIRM 전환")
                resetState()
                StateResult.Transition(
                    nextState = CallAcceptState.WAITING_FOR_CONFIRM,
                    reason = "콜 수락 버튼 클릭 후 다이얼로그 감지"
                )
            } else {
                // 다이얼로그 아직 안 나타남 - 대기 상태로 전환
                Log.d(TAG, "⏳ 다이얼로그 미출현 - 대기 상태로 전환")
                clickedAndWaiting = true
                waitRetryCount = 0
                StateResult.NoChange
            }
        } else {
            Log.e(TAG, "콜 수락 버튼 클릭 실패")
            StateResult.Error(
                errorState = CallAcceptState.ERROR_UNKNOWN,
                reason = "콜 수락 버튼 클릭 실패"
            )
        }
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
