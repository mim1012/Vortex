package com.example.twinme.engine

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.twinme.data.CallAcceptState
import com.example.twinme.data.SettingsManager
import com.example.twinme.domain.interfaces.ICallEngine
import com.example.twinme.domain.interfaces.IFilterSettings
import com.example.twinme.domain.interfaces.ILogger
import com.example.twinme.domain.interfaces.ITimeSettings
import com.example.twinme.domain.state.StateContext
import com.example.twinme.domain.state.StateHandler
import com.example.twinme.domain.state.StateResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * CallAcceptEngineImpl - 원본 APK의 MacroEngine.smali 완전 재현
 *
 * 핵심 메커니즘:
 * startMacroLoop() → executeStateMachineOnce() → scheduleNext() → startMacroLoop() (무한 반복)
 *
 * 원본 Smali 파일 기준:
 * - MacroEngine.smali (라인 2434-2527: startMacroLoop)
 * - MacroEngine.smali (라인 2387-2422: scheduleNext)
 * - MacroEngine.smali (라인 1239-1780: executeStateMachineOnce)
 */
@Singleton
class CallAcceptEngineImpl @Inject constructor(
    private val logger: ILogger,
    private val filterSettings: IFilterSettings,
    private val timeSettings: ITimeSettings,
    private val handlers: Set<@JvmSuppressWildcards StateHandler>,
    private val settingsManager: SettingsManager
) : ICallEngine {

    companion object {
        private const val TAG = "CallAcceptEngineImpl"
        private const val TIMEOUT_MS = 3000L                    // 기본 3초 (원본 APK)
        private const val TIMEOUT_CONFIRM_MS = 7000L            // WAITING_FOR_CONFIRM만 7초 (원본 APK)
        private const val REFRESH_BUTTON_ID = "com.kakao.taxi.driver:id/action_refresh"

        private fun isNodeValid(node: AccessibilityNodeInfo?): Boolean {
            return node != null && try {
                node.childCount
                true
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Node recycled: ${e.message}")
                false
            }
        }
    }

    // ============================================
    // 기존 필드
    // ============================================

    private val _currentState = MutableStateFlow(CallAcceptState.IDLE)
    override val currentState: StateFlow<CallAcceptState> = _currentState.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    override val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _isAutoRefreshEnabled = MutableStateFlow(true)
    override val isAutoRefreshEnabled: StateFlow<Boolean> = _isAutoRefreshEnabled.asStateFlow()

    private val handler = Handler(Looper.getMainLooper())
    private var timeoutRunnable: Runnable? = null

    private val handlerMap: Map<CallAcceptState, StateHandler> by lazy {
        handlers.associateBy { it.targetState }
    }

    // ============================================
    // 새로 추가: 메인 루프 관련 필드 (원본 APK 기준)
    // ============================================

    /**
     * 현재 스케줄된 메인 루프 Runnable
     * 원본: MacroEngine.smali 라인 ???: currentRunnable
     */
    private var currentRunnable: Runnable? = null

    /**
     * 마지막 새로고침 시간 (밀리초)
     * 원본: MacroEngine.smali 라인 ???: lastRefreshTime
     */
    private var lastRefreshTime = 0L

    /**
     * 현재 처리 중인 rootNode (캐시)
     * AccessibilityService에서 업데이트됨
     */
    private var cachedRootNode: AccessibilityNodeInfo? = null

    /**
     * StateContext - 상태 핸들러 간 데이터 공유
     * ⭐ 필드로 유지하여 eligibleCall 값이 상태 전환 시에도 유지되도록 함
     * 원본 APK: MacroEngine 내부의 eligibleCall 필드 역할
     */
    private val stateContext: StateContext by lazy {
        StateContext(
            findNode = { _, viewId ->
                cachedRootNode?.let { findNodeByViewId(it, viewId) }
            },
            findNodeByText = { _, text ->
                cachedRootNode?.let { findNodeByText(it, text) }
            },
            logger = logger,
            filterSettings = filterSettings,
            timeSettings = timeSettings,
            performGestureClick = { x, y ->
                // 원본 APK 방식: dispatchGesture() 사용
                // AccessibilityService의 singleton instance를 통해 제스처 실행
                com.example.twinme.service.CallAcceptAccessibilityService.instance?.performGestureClick(x, y) ?: false
            }
        )
    }

    // ============================================
    // 기존 메서드 (수정)
    // ============================================

    /**
     * 엔진 시작
     * 원본: MacroEngine.smali의 start() 메서드
     */
    override fun start() {
        if (_isRunning.value) return
        Log.d(TAG, "엔진 시작")
        _isRunning.value = true
        changeState(CallAcceptState.WAITING_FOR_CALL, "엔진 시작됨")

        // ⭐ 메인 루프 시작! (원본 APK의 핵심)
        startMacroLoop()
    }

    /**
     * 엔진 정지
     * 원본: MacroEngine.smali의 stop() 메서드
     */
    override fun stop() {
        if (!_isRunning.value) return
        Log.d(TAG, "엔진 정지")
        _isRunning.value = false

        // 스케줄된 메인 루프 취소
        currentRunnable?.let { handler.removeCallbacks(it) }
        currentRunnable = null

        resetTimeout()
        changeState(CallAcceptState.IDLE, "엔진 정지됨")
    }

    /**
     * AccessibilityService로부터 rootNode 수신
     * 메인 루프에서 사용할 rootNode를 캐시에 저장
     */
    override fun processNode(node: AccessibilityNodeInfo) {
        // rootNode 캐시 업데이트 (메인 루프에서 사용)
        cachedRootNode = node
    }

    // ============================================
    // 새로 추가: 메인 루프 메서드 (원본 APK 완전 재현)
    // ============================================

    /**
     * 메인 루프 - 자기 자신을 재귀 호출
     * 원본: MacroEngine.smali 라인 2434-2527
     *
     * 흐름:
     * 1. isRunning 확인 → false면 종료
     * 2. AccessibilityService 확인 → null이면 100ms 후 재시도
     * 3. 화면 감지 ("예약콜 리스트" or "예약콜 상세")
     * 4. ButtonState 업데이트
     * 5. isPaused 확인 → true면 500ms, false면 상태 머신 실행
     * 6. scheduleNext(delayMs, startMacroLoop) ← 재귀!
     */
    private fun startMacroLoop() {
        // 1. isRunning 확인 (원본 라인 2438)
        if (!_isRunning.value) {
            Log.d(TAG, "메인 루프 중단: 엔진 정지됨")
            return
        }

        // 2. rootNode 확인 및 패키지명 검증 (원본 라인 2446)
        var rootNode = cachedRootNode

        // 2-1. 캐시 없으면 직접 가져오기
        if (rootNode == null) {
            val service = com.example.twinme.service.CallAcceptAccessibilityService.instance
            rootNode = service?.rootInActiveWindow

            if (rootNode != null) {
                cachedRootNode = rootNode
                Log.v(TAG, "rootNode 직접 획득")
            }
        }

        // 2-2. 여전히 null이면 재시도
        if (rootNode == null) {
            Log.v(TAG, "rootNode 없음 - 100ms 후 재시도")
            scheduleNext(100L) { startMacroLoop() }
            return
        }

        // 2-3. ⭐ 패키지명 검증 (보안)
        val currentPackage = rootNode.packageName?.toString()
        if (currentPackage != "com.kakao.taxi.driver") {
            Log.w(TAG, "⚠️ 다른 앱이 포그라운드: $currentPackage - 100ms 후 재시도")
            cachedRootNode = null  // 캐시 무효화
            scheduleNext(100L) { startMacroLoop() }
            return
        }

        // 3. 화면 감지 (원본 라인 2451-2465)
        // "예약콜 리스트" 또는 "예약콜 상세" 텍스트 확인
        val hasCallList = hasText(rootNode, "예약콜 리스트")
        val hasCallDetail = hasText(rootNode, "예약콜 상세")

        if (!hasCallList && !hasCallDetail) {
            Log.v(TAG, "콜 리스트 화면 아님 - NO_CALLS")
            // 원본: notifyButtonState(NO_CALLS)
        } else {
            Log.v(TAG, "콜 리스트 화면 감지 - ACTIVE")
            // 원본: notifyButtonState(ACTIVE)
        }

        // 4. isPaused 확인은 제거 (우리 구현에는 없음)
        // 5. 상태 머신 한 번 실행 (원본 라인 2486-2523)
        val delayMs = executeStateMachineOnce(rootNode)

        // 6. 다음 실행 예약 (재귀!) (원본 라인 2523)
        scheduleNext(delayMs) { startMacroLoop() }
    }

    /**
     * 상태 머신 한 번 실행 및 다음 지연 시간 반환
     * 원본: MacroEngine.smali 라인 1239-1780
     *
     * @return 다음 실행까지의 지연 시간 (밀리초)
     */
    private fun executeStateMachineOnce(rootNode: AccessibilityNodeInfo): Long {
        if (!isNodeValid(rootNode)) {
            Log.w(TAG, "Invalid node detected - skipping")
            return 500L
        }

        // ⭐⭐⭐ 제일 중요! cachedRootNode 갱신
        // StateContext의 람다가 최신 rootNode를 참조하도록 보장
        cachedRootNode = rootNode

        val currentTime = System.currentTimeMillis()

        Log.v(TAG, "상태 머신 실행: ${_currentState.value}")

        // ============================================
        // ⛔ DEPRECATED: 새로고침 로직 주석 처리
        // 이제 ListDetectedHandler에서 처리
        // 롤백 필요 시 아래 주석 해제
        // ============================================
        /*
        if (_currentState.value == CallAcceptState.WAITING_FOR_CALL) {
            val elapsedSinceRefresh = currentTime - lastRefreshTime
            val targetRefreshDelay = calculateRefreshDelay()

            Log.v(TAG, "새로고침 체크: 경과=${elapsedSinceRefresh}ms, 목표=${targetRefreshDelay}ms")

            if (elapsedSinceRefresh >= targetRefreshDelay) {
                Log.d(TAG, "새로고침 버튼 클릭 (설정: ${settingsManager.refreshDelay}초, 실제지연: ${elapsedSinceRefresh}ms)")

                val refreshButton = rootNode.findAccessibilityNodeInfosByViewId(REFRESH_BUTTON_ID).firstOrNull()
                val buttonFound = refreshButton != null

                performRefresh(rootNode)
                lastRefreshTime = currentTime

                com.example.twinme.logging.RemoteLogger.logRefreshAttempt(
                    buttonFound = buttonFound,
                    clickSuccess = buttonFound && (refreshButton?.isClickable == true),
                    elapsedSinceLastRefresh = elapsedSinceRefresh,
                    targetDelay = targetRefreshDelay
                )

                return 30L
            } else {
                val remainingMs = targetRefreshDelay - elapsedSinceRefresh
                Log.v(TAG, "새로고침 대기 중 (남은 시간: ${remainingMs}ms)")

                return remainingMs.coerceAtMost(1000L)
            }
        }
        */

        // ============================================
        // 모든 상태를 핸들러에게 위임 (원본 APK 방식)
        // 원본: MacroEngine.smali 라인 1313-1780의 packed-switch
        // ============================================
        val currentHandler = handlerMap[_currentState.value]
        if (currentHandler == null) {
            Log.w(TAG, "핸들러 없음: ${_currentState.value}")
            return 100L  // 기본값 (원본 라인 ???: 0x64 = 100ms)
        }

        // ⭐ StateContext는 필드로 유지되므로 eligibleCall 값이 보존됨
        // rootNode는 cachedRootNode로 람다에서 자동으로 참조됨

        // 핸들러 실행
        when (val result = currentHandler.handle(rootNode, stateContext)) {
            is StateResult.Transition -> {
                changeState(result.nextState, result.reason)
            }
            is StateResult.Error -> {
                changeState(result.errorState, result.reason)
            }
            StateResult.NoChange -> {
                // 상태 유지
            }
        }

        // 상태별 지연 시간 반환 (원본 라인 1313-1780의 반환값)
        return getDelayForState(_currentState.value)
    }

    /**
     * 다음 실행 예약
     * 원본: MacroEngine.smali 라인 2387-2422
     *
     * @param delayMs 지연 시간 (밀리초)
     * @param action 실행할 작업 (람다)
     */
    private fun scheduleNext(delayMs: Long, action: () -> Unit) {
        // 1. 기존 Runnable 제거 (메모리 누수 방지) (원본 라인 2399-2405)
        currentRunnable?.let { handler.removeCallbacks(it) }

        // 2. 새 Runnable 생성 (원본 라인 2409-2413)
        val newRunnable = Runnable { action() }
        currentRunnable = newRunnable

        // 3. Handler에 등록 (원본 라인 2416-2420)
        handler.postDelayed(newRunnable, delayMs)

        Log.v(TAG, "다음 실행 예약: ${delayMs}ms 후")
    }

    /**
     * 새로고침 버튼 클릭
     *
     * @param rootNode 현재 화면의 루트 노드
     */
    private fun performRefresh(rootNode: AccessibilityNodeInfo) {
        val startTime = System.currentTimeMillis()

        val refreshButton = rootNode.findAccessibilityNodeInfosByViewId(REFRESH_BUTTON_ID)
            .firstOrNull()

        if (refreshButton != null && refreshButton.isClickable) {
            val success = refreshButton.performAction(
                AccessibilityNodeInfo.ACTION_CLICK
            )
            val elapsedMs = System.currentTimeMillis() - startTime

            Log.d(TAG, "새로고침 버튼 클릭 ${if (success) "성공" else "실패"} (${elapsedMs}ms)")

            logger.logNodeClick(
                nodeId = REFRESH_BUTTON_ID,
                success = success,
                state = CallAcceptState.WAITING_FOR_CALL,
                elapsedMs = elapsedMs
            )
        } else {
            Log.w(TAG, "새로고침 버튼을 찾을 수 없음")
        }
    }

    /**
     * 새로고침 지연 시간 계산
     * 원본: MacroEngine.smali 라인 1537-1573
     *
     * 공식: refreshDelay * 1000 * (0.9 + random(0~0.2))
     *      = refreshDelay * 1000 * (0.9 ~ 1.1)
     *
     * 예시:
     * - refreshDelay = 5초
     * - 최종 지연시간 = 5000 * (0.9 ~ 1.1) = 4500 ~ 5500ms
     *
     * @return 지연 시간 (밀리초)
     */
    private fun calculateRefreshDelay(): Long {
        // 1. 설정값 읽기 (초 단위, Float)
        val refreshDelay = settingsManager.refreshDelay

        // 2. 밀리초로 변환: refreshDelay * 1000 (원본 라인 1543-1546)
        val baseDelay = (refreshDelay * 1000).toLong()

        // 3. 랜덤 계수 생성: 0.9 + (0 ~ 0.2) = 0.9 ~ 1.1 (원본 라인 1550-1566)
        val randomValue = Random.nextDouble(0.0, 0.2)  // 0 ~ 0.2
        val randomFactor = 0.9 + randomValue            // 0.9 ~ 1.1

        // 4. 최종 지연시간 = baseDelay * randomFactor (원본 라인 1569-1573)
        val finalDelay = (baseDelay * randomFactor).toLong()

        Log.v(TAG, "새로고침 간격 계산: 설정=${refreshDelay}초, 기본=${baseDelay}ms, 최종=${finalDelay}ms")

        return finalDelay
    }

    /**
     * 상태별 지연 시간 반환
     * 원본: MacroEngine.smali 라인 1313-1780의 packed-switch 반환값
     *
     * | 상태 | 지연 시간 | 16진수 |
     * |------|----------|--------|
     * | IDLE | - | - |
     * | WAITING_FOR_CALL | 동적 계산 | - |
     * | DETECTED_CALL | 50ms | 0x32 |
     * | WAITING_FOR_CONFIRM | 10ms | 0xa |
     * | CALL_ACCEPTED | 500ms | 0x1f4 |
     * | ERROR_* | 500ms | 0x1f4 |
     */
    private fun getDelayForState(state: CallAcceptState): Long {
        return when (state) {
            CallAcceptState.IDLE -> Long.MAX_VALUE
            CallAcceptState.WAITING_FOR_CALL -> 10L          // 즉시 전환
            CallAcceptState.LIST_DETECTED -> 10L             // 빠른 체크
            CallAcceptState.REFRESHING -> 30L                // 원본 APK 타이밍
            CallAcceptState.ANALYZING -> 50L                 // 원본 APK 타이밍
            CallAcceptState.CLICKING_ITEM -> 10L             // 원본 APK 타이밍
            CallAcceptState.DETECTED_CALL -> 50L
            CallAcceptState.WAITING_FOR_CONFIRM -> 10L
            CallAcceptState.CALL_ACCEPTED -> 500L
            CallAcceptState.TIMEOUT_RECOVERY -> 100L         // 복구 체크
            CallAcceptState.ERROR_ASSIGNED,
            CallAcceptState.ERROR_TIMEOUT,
            CallAcceptState.ERROR_UNKNOWN -> 500L
        }
    }

    /**
     * 텍스트 존재 여부 확인
     *
     * @param rootNode 루트 노드
     * @param text 찾을 텍스트
     * @return 텍스트가 존재하면 true
     */
    private fun hasText(rootNode: AccessibilityNodeInfo, text: String): Boolean {
        val nodes = rootNode.findAccessibilityNodeInfosByText(text)
        return nodes.isNotEmpty()
    }

    // ============================================
    // 기존 메서드들 (변경 없음)
    // ============================================

    private fun changeState(newState: CallAcceptState, reason: String) {
        if (_currentState.value == newState) return

        val fromState = _currentState.value
        Log.d(TAG, "상태 변경: $fromState -> $newState (이유: $reason)")

        _currentState.value = newState

        // ⭐⭐⭐ eligibleCall 초기화 조건 (중요!)
        //
        // eligibleCall = '이번 사이클에서 잡으려고 확정한 콜 1건' (살아있는 콜)
        //
        // 초기화 조건:
        // 1. WAITING_FOR_CALL: 새로운 사이클 시작 = 이전 콜 정보 삭제
        // 2. ERROR_ASSIGNED: "이미 배차됨" = 콜이 죽음 = 즉시 삭제 필요
        //
        // 초기화 안 하는 경우:
        // - ERROR_TIMEOUT: 일시적 타임아웃 = 재시도 가능
        // - ERROR_UNKNOWN: 일시적 에러 = 재시도 가능
        // - CALL_ACCEPTED: 수락 완료 후 자동으로 WAITING_FOR_CALL로 전환됨
        when (newState) {
            CallAcceptState.WAITING_FOR_CALL,
            CallAcceptState.ERROR_ASSIGNED -> {
                if (stateContext.eligibleCall != null) {
                    val reason = when (newState) {
                        CallAcceptState.ERROR_ASSIGNED -> "콜이 이미 배차됨 (죽은 콜)"
                        else -> "새로운 사이클 시작"
                    }
                    Log.d(TAG, "eligibleCall 초기화: $reason (from=$fromState)")
                    stateContext.eligibleCall = null
                }
            }
            else -> {
                // ERROR_TIMEOUT, ERROR_UNKNOWN 등은 유지 (재시도 가능)
            }
        }

        // 상태 전환 서버 로그 추가 (사후 검증 가능)
        logger.logStateChange(
            from = fromState,
            to = newState,
            reason = reason,
            elapsedMs = 0L
        )

        resetTimeout()
        if (shouldStartTimeout(newState)) {
            startTimeout()
        }
    }

    private fun shouldStartTimeout(state: CallAcceptState): Boolean {
        return state != CallAcceptState.IDLE &&
               state != CallAcceptState.CALL_ACCEPTED &&
               state != CallAcceptState.ERROR_ASSIGNED
    }

    private fun startTimeout() {
        // 원본 APK: 기본 3초, WAITING_FOR_CONFIRM만 7초
        val timeout = when (_currentState.value) {
            CallAcceptState.WAITING_FOR_CONFIRM -> TIMEOUT_CONFIRM_MS  // 7초
            else -> TIMEOUT_MS  // 3초
        }

        timeoutRunnable = Runnable {
            changeState(CallAcceptState.ERROR_TIMEOUT, "상태 변경 타임아웃 (${timeout}ms)")
        }
        handler.postDelayed(timeoutRunnable!!, timeout)
    }

    private fun resetTimeout() {
        timeoutRunnable?.let { handler.removeCallbacks(it) }
        timeoutRunnable = null
    }

    private fun findNodeByViewId(rootNode: AccessibilityNodeInfo, viewId: String): AccessibilityNodeInfo? {
        val nodes = rootNode.findAccessibilityNodeInfosByViewId(viewId)
        return if (nodes.isNotEmpty()) nodes[0] else null
    }

    private fun findNodeByText(rootNode: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val exactNodes = rootNode.findAccessibilityNodeInfosByText(text)
        if (exactNodes.isNotEmpty()) {
            return exactNodes.firstOrNull { it.isClickable } ?: exactNodes[0]
        }
        return findClickableNodeWithText(rootNode, text)
    }

    private fun findClickableNodeWithText(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val nodeText = node.text?.toString() ?: ""
        if (nodeText.contains(text, ignoreCase = true) && node.isClickable) {
            return node
        }

        val contentDesc = node.contentDescription?.toString() ?: ""
        if (contentDesc.contains(text, ignoreCase = true) && node.isClickable) {
            return node
        }

        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                findClickableNodeWithText(child, text)?.let { found ->
                    return found
                }
            }
        }

        return null
    }

    override fun setAutoRefreshEnabled(enabled: Boolean) {
        _isAutoRefreshEnabled.value = enabled
        Log.d(TAG, "자동 새로고침 ${if (enabled) "활성화" else "비활성화"}")
    }
}
